package dev.halone.display

import android.app.Presentation
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager

/**
 * Puts a GL renderer on the glasses.
 *
 * A `Presentation` holding one `GLSurfaceView` is the whole of it, but two
 * details are not optional: the wide mode has to be pinned explicitly, because
 * the display advertises it only while the glasses are in SBS and will otherwise
 * hand back a mirror-mode surface; and `FLAG_KEEP_SCREEN_ON` here covers the
 * external display only, so the phone still needs its own hold (and, in the
 * real shell, a foreground service — without one the render thread stops within
 * seconds of being backgrounded and the glasses freeze on a stale frame).
 */
class RenderPresentation(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private var presentation: Presentation? = null

    var surfaceView: GLSurfaceView? = null
        private set

    /** Refresh rate of the mode asked for in the last [show], or the display's own. */
    var refreshHz: Float = 60f
        private set

    /** [rateHz] is the rate to ask for on a 2D display; null asks for its fastest. */
    fun show(display: Display, renderer: GLSurfaceView.Renderer, rateHz: Float? = null) {
        dismiss()

        val stereo = widestStereoMode(display)
        val wide = stereo ?: flatMode(display, rateHz)
        refreshHz = wide?.refreshRate ?: display.refreshRate

        // Only for a 2D display: asking for its fastest rate (RayNeo GT
        // series) or the one the wearer chose (XREAL One series). A
        // side-by-side mode is pinned by id alone, as before: **[suspected]**
        // an XREAL Air switched to 3D and then never came up once the rate
        // was forced as well, the non-seamless switch it asks for resetting
        // the glasses' own mode.
        val forceRate = stereo == null && wide != null
        val rate = refreshHz
        val view = object : GLSurfaceView(context) {}.apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            // The mode id alone is a request the system may weigh against
            // others; saying the surface itself is a fixed-rate source at that
            // rate is what tells it a 60 Hz mode would drop frames.
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (!forceRate) return
                    // ALWAYS, not the default only-if-seamless: on a GT Max over
                    // USB-C the display offered 60 and 120 Hz yet stayed at 60,
                    // and a switch from 60 to 120 on an external link is not
                    // seamless, so a seamless-only request is never acted on.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            holder.surface.setFrameRate(
                                rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS,
                            )
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching { holder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
                    }
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
        surfaceView = view

        presentation = KeptPresentation(context, display, log).apply {
            setOnDismissListener { dismissed ->
                if (presentation !== dismissed) return@setOnDismissListener
                presentation = null
                surfaceView = null
                log("  presentation on display ${display.displayId} taken down by the system")
            }
            window?.let { w ->
                wide?.let { mode ->
                    w.attributes = w.attributes.apply {
                        preferredDisplayModeId = mode.modeId
                        if (forceRate) preferredRefreshRate = mode.refreshRate
                    }
                }
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            setContentView(view)
            show()
        }.also { shown ->
            // And again once the window exists: a request made before the
            // window is attached has been seen not to take on external
            // displays, and asking twice costs nothing.
            wide?.takeIf { forceRate }?.let { mode ->
                view.postDelayed({
                    shown.window?.let { w ->
                        w.attributes = w.attributes.apply {
                            preferredDisplayModeId = mode.modeId
                            preferredRefreshRate = mode.refreshRate
                        }
                    }
                }, REAPPLY_MODE_MS)
            }
        }

        log("  presentation shown on display ${display.displayId}, requested mode ${wide?.modeId} at ${"%.0f".format(refreshHz)} Hz")
    }

    fun dismiss() {
        val shown = presentation
        presentation = null
        surfaceView = null
        shown?.dismiss()
    }

    /**
     * Android cancels a presentation whenever its display's metrics change,
     * even while the display stays, and the glasses then mirror the phone.
     * **[suspected]** behind a One Pro on a Galaxy S948U1 falling back to
     * mirroring, unpredictably and with no display removed. Kept up while the
     * display is valid; a removed display still takes it down.
     */
    private class KeptPresentation(context: Context, display: Display, private val log: (String) -> Unit) :
        Presentation(context, display) {
        override fun cancel() {
            if (display.isValid) {
                log("  presentation kept through a change to display ${display.displayId}")
                return
            }
            super.cancel()
        }
    }

    private companion object {
        const val REAPPLY_MODE_MS = 500L

        /**
         * No side-by-side mode means the glasses are in 2D: take the refresh
         * nearest [rateHz] at the current resolution, or the fastest, which on
         * RayNeo GT glasses is 120 Hz against the 60 the default mode may be.
         */
        fun flatMode(display: Display, rateHz: Float?): Display.Mode? {
            val current = display.mode
            val modes = display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            return if (rateHz == null) modes.maxByOrNull { it.refreshRate }
            else modes.minByOrNull { kotlin.math.abs(it.refreshRate - rateHz) }
        }

        /** Widest mode shaped like two 16:9 halves glued together. */
        fun widestStereoMode(display: Display): Display.Mode? =
            display.supportedModes
                .filter { it.physicalWidth >= 2 * it.physicalHeight }
                .maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
    }
}
