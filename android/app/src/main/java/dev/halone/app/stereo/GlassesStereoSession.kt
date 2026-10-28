package dev.halone.app.stereo

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import dev.halone.compositor.Panel
import dev.halone.compositor.PanelEngine
import dev.halone.compositor.PanelPose
import dev.halone.compositor.Scene
import dev.halone.compositor.SmoothFollow
import dev.halone.compositor.StereoCompositor
import dev.halone.compositor.TitleBarHiding
import dev.halone.display.GlassesDisplaySession
import dev.halone.display.RenderPresentation
import dev.halone.glasses.api.DisplayMode
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.Optics
import dev.halone.glasses.fusion.DisplayClock
import dev.halone.glasses.fusion.HeadTracker

/**
 * Wires the layers together: `core-display` gets the glasses into
 * stereo and hosts a render surface, `core-compositor` draws the scene, and an
 * engine paints each panel.
 *
 * There is deliberately almost nothing here. Everything this class used to do
 * itself now lives in a module that can be tested and reused; what remains is
 * the ordering between them.
 */
class GlassesStereoSession(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val displays = GlassesDisplaySession(context, log)
    private val presentation = RenderPresentation(context, log)

    val scene = Scene()

    var compositor: StereoCompositor? = null
        private set

    private var optics: Optics? = null

    /** Latch pacing as this session would choose it, for the `auto` frame-pacing setting. */
    var autoPaceOnLatch = true
        private set

    var titleBarHiding = TitleBarHiding.NEVER
        set(value) {
            field = value
            compositor?.titleBarHiding = value
        }

    /** Glasses pacing as this session would choose it: for a rate the phone's screen is out of step with. */
    var autoPaceToGlasses = false
        private set

    /** The glasses' display the compositor was last shown on. */
    var display: Display? = null
        private set

    /** Told on the UI thread when the system takes the compositor's window off the glasses. */
    var onLost: (() -> Unit)?
        get() = presentation.onLost
        set(value) { presentation.onLost = value }

    /** Told on any thread when the frame turns 2D or 3D, or the display changes rate. */
    @Volatile
    var onDisplayChanged: (() -> Unit)? = null

    suspend fun enterStereo(device: GlassesDevice, mode: DisplayMode? = null): Display? =
        displays.enterStereo(device, mode)

    suspend fun awaitGlassesDisplay(device: GlassesDevice, notId: Int?, timeoutMs: Long): Display? =
        displays.awaitGlassesDisplay(device, notId, timeoutMs)

    /** Builds the compositor and shows it. Must run on the UI thread. */
    fun show(
        display: Display,
        tracker: HeadTracker,
        optics: Optics,
        displayClock: DisplayClock? = null,
        rateHz: Float? = null,
    ) {
        dismiss()
        this.optics = optics
        this.displayClock = displayClock
        compositor = StereoCompositor(tracker, optics, scene, log).also {
            // The tracker already loaded it, and the two must agree: the head
            // frame the tracker publishes is defined by the same eye rotations
            // the compositor renders with.
            it.calibration = tracker.calibration
            it.displayClock = displayClock
            it.titleBarHiding = titleBarHiding
            it.onStereoChanged = { onDisplayChanged?.invoke() }
            meterFrames(it)
            present(it, display, rateHz)
        }
    }

    /**
     * Carries the running compositor over to [display], windows and all: the
     * glasses' display came back after going away, or a mode switch recreated
     * it. Must run on the UI thread.
     */
    fun moveTo(display: Display, rateHz: Float? = null) {
        val compositor = compositor ?: return
        present(compositor, display, rateHz)
    }

    /** Takes the compositor off the glasses and keeps it, for [moveTo]. */
    fun detach() {
        unwatchRate()
        presentation.detach()
    }

    private var displayClock: DisplayClock? = null

    private fun present(compositor: StereoCompositor, display: Display, rateHz: Float?) {
        this.display = display
        presentation.show(display, compositor, rateHz)
        // The rate the display actually runs at, not the one asked for:
        // **[measured]** a OnePlus 12 accepts the request for 120 Hz on a
        // GT Max all the way down to SurfaceFlinger and still drives the
        // link at 60, and timing a 60 Hz panel as 120 under-predicts by a
        // frame and halves the scanout span.
        compositor.refreshHz = display.refreshRate
        watchRate(display, compositor)
        // Latch pacing waits on frame timestamps that arrive a beat late;
        // tuned at XREAL's 60 Hz it fits, but at 120 Hz without a display
        // clock (RayNeo GT series, measured) it halved the frame rate.
        autoPaceOnLatch = !(displayClock == null && presentation.refreshHz > 90f)
        compositor.paceOnLatch = autoPaceOnLatch
        autoPaceToGlasses = displayClock != null && !PhoneScreen.inStep(display.mode.refreshRate, PhoneScreen.rates(context))
        compositor.paceToGlasses = autoPaceToGlasses
        log("  pacing: ${if (autoPaceToGlasses) "the glasses' clock, out of step with the phone's screen" else if (autoPaceOnLatch) "each frame's latch" else "free"}")
    }

    /**
     * Puts a panel in the scene. The compositor runs the engine handshake on its
     * next frame, so this is safe to call at any point after [show].
     */
    fun openPanel(
        id: String,
        engine: PanelEngine,
        follow: SmoothFollow = SmoothFollow(),
        verticalFill: Float = PANEL_VERTICAL_FILL,
        distanceMetres: Float? = null,
    ): Panel? {
        val panel = sizedPanel(id, engine, follow, verticalFill, distanceMetres) ?: return null
        scene.open(panel)
        log("  opened $panel at %.1f m".format(panel.pose.distanceMetres))
        return panel
    }

    /**
     * Gives the scene its dock and launcher, which sit outside the arrangement.
     * Sized like any panel; the scene places them every frame.
     */
    fun openOverlays(dock: Panel, launcher: Panel) {
        scene.setOverlays(dock, launcher)
        log("  opened overlays '${dock.id}' and '${launcher.id}'")
    }

    fun closeOverlays() {
        listOfNotNull(scene.dock, scene.launcher, scene.popup).forEach { compositor?.detach(it) ?: it.engine.close() }
        scene.setOverlays(null, null)
        scene.setPopupPanel(null)
    }

    /** A panel sized from the field of view, not in metres: distance is a comfort choice, angular size the layout one. */
    fun sizedPanel(
        id: String,
        engine: PanelEngine,
        follow: SmoothFollow = SmoothFollow(),
        verticalFill: Float = PANEL_VERTICAL_FILL,
        distanceMetres: Float? = null,
    ): Panel? {
        val compositor = compositor ?: run { log("  not in stereo"); return null }
        val distance = distanceMetres ?: optics?.virtualImageDistanceMetres ?: DEFAULT_PANEL_DISTANCE_M
        return Panel(id, engine, PanelPose(distanceMetres = distance), follow).apply {
            fitToFov(compositor.verticalFovDegrees, verticalFill)
        }
    }

    /** When a panel was last closed, on the uptime clock; 0 for never. */
    @Volatile
    var lastPanelClosedAt = 0L
        private set

    fun closePanel(panel: Panel) {
        lastPanelClosedAt = android.os.SystemClock.uptimeMillis()
        scene.remove(panel)
        compositor?.detach(panel) ?: panel.engine.close()
        // A drift, not a jump: closing a panel is a layout change like any
        // other, and the panels left behind should be seen to take the room.
        scene.layOutCylindrical(immediate = false)
        log("  closed panel '${panel.id}'")
    }

    /** Frames the compositor drew over the last second, or null before a second has passed. */
    @Volatile
    var renderFps: Double? = null
        private set

    private val meterHandler = Handler(Looper.getMainLooper())
    private var meter: Runnable? = null

    private fun meterFrames(compositor: StereoCompositor) {
        stopMeter()
        var frames = compositor.framesDrawn
        var at = android.os.SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.elapsedRealtime()
                val drawn = compositor.framesDrawn
                if (now > at) renderFps = (drawn - frames) * 1000.0 / (now - at)
                frames = drawn
                at = now
                meterHandler.postDelayed(this, METER_INTERVAL_MS)
            }
        }
        meter = tick
        meterHandler.postDelayed(tick, METER_INTERVAL_MS)
    }

    private fun stopMeter() {
        meter?.let { meterHandler.removeCallbacks(it) }
        meter = null
        renderFps = null
    }

    private var rateListener: DisplayManager.DisplayListener? = null

    /** Follows the display if the system does switch its rate later. */
    private fun watchRate(display: Display, compositor: StereoCompositor) {
        unwatchRate()
        val manager = context.getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != display.displayId) return
                val hz = manager.getDisplay(displayId)?.refreshRate ?: return
                if (kotlin.math.abs(hz - compositor.refreshHz) > 1f) {
                    compositor.refreshHz = hz
                    log("  display ${displayId} now at ${"%.0f".format(hz)} Hz")
                    onDisplayChanged?.invoke()
                }
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        rateListener = listener
    }

    private fun unwatchRate() {
        rateListener?.let { context.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
        rateListener = null
    }

    fun dismiss() {
        unwatchRate()
        stopMeter()
        // The presentation goes first: it stops the render thread, so nothing is
        // mid-draw against a texture we are about to release.
        presentation.dismiss()
        compositor = null
        scene.panels.forEach {
            it.engine.close()
            it.detachTexture()
        }
        scene.clear()
    }

    /** Returns the glasses to a mono mode so the phone is usable again. */
    fun leaveStereo(device: GlassesDevice) = displays.leaveStereo(device)

    companion object {
        /** Leaves headroom at the edges, where these optics are least comfortable. */
        const val PANEL_VERTICAL_FILL = 0.75f
        private const val METER_INTERVAL_MS = 1_000L
        private const val DEFAULT_PANEL_DISTANCE_M = 4f
    }
}
