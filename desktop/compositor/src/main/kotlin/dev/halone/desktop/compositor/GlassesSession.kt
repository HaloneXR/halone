package dev.halone.desktop.compositor

import dev.halone.desktop.glasses.DesktopGlasses
import dev.halone.glasses.api.DeviceCalibration
import dev.halone.glasses.api.DisplayMode
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.preferredMonoMode
import dev.halone.glasses.api.preferredStereoMode
import dev.halone.glasses.fusion.DisplayClock
import dev.halone.glasses.fusion.HeadTracker
import org.lwjgl.glfw.GLFW.*

/** Connected glasses, their tracker, and the display they show up as. */
class GlassesSession private constructor(
    private val glasses: DesktopGlasses,
    val device: GlassesDevice,
    val calibration: DeviceCalibration?,
    val tracker: HeadTracker,
) : AutoCloseable {
    var stereoMode: DisplayMode? = null
        private set

    /** The glasses' refresh timing on the host clock, when they report it. */
    val displayClock: DisplayClock? = device.scanout?.let { DisplayClock() }

    @Volatile
    private var syncing = true
    private val clockSync: Thread? = displayClock?.let { clock ->
        val scanout = device.scanout!!
        scanout.start { clock.onBeacon(it) }
        Thread({
            while (syncing) {
                repeat(CLOCK_BURST) {
                    scanout.syncClock().onSuccess { clock.onClockSample(it) }
                    Thread.sleep(CLOCK_BURST_GAP_MS)
                }
                var waited = 0L
                while (syncing && waited < CLOCK_RESYNC_MS) {
                    Thread.sleep(100)
                    waited += 100
                }
            }
        }, "display-clock").apply { isDaemon = true; start() }
    }

    /** Switches to the best stereo mode, unless the glasses are already in one. */
    fun enterStereo(): Result<DisplayMode> {
        val display = device.display ?: return Result.failure(IllegalStateException("no display control"))
        val target = display.preferredStereoMode() ?: return Result.failure(IllegalStateException("no stereo mode"))
        val current = display.currentMode().getOrNull()
        if (current?.isStereo == true) return Result.success(current.also { stereoMode = it })
        return display.setMode(target).map { target.also { stereoMode = it } }
    }

    /**
     * The GLFW monitor the glasses appear as, once it reports [frameWidth]. A mode
     * change makes the host drop the display and add it back, so this waits.
     *
     * macOS names a monitor after the product, which the driver's name hints
     * match. On Linux GLFW names it after the connector ("DP-2"), so when no name
     * matches, the only monitor at the stereo width is taken instead.
     */
    fun findMonitor(frameWidth: Int, timeoutSeconds: Double = 15.0): Long? {
        val hints = device.displayNameHints.map { it.lowercase() }
        val deadline = glfwGetTime() + timeoutSeconds
        while (glfwGetTime() < deadline) {
            glfwPollEvents()
            val monitors = glfwGetMonitors()
            if (monitors != null) {
                val atWidth = (0 until monitors.limit()).map { monitors.get(it) }
                    .filter { glfwGetVideoMode(it)?.width() == frameWidth }
                atWidth.firstOrNull { monitor -> glfwGetMonitorName(monitor)?.lowercase()?.let { name -> hints.any { name.contains(it) } } == true }
                    ?.let { return it }
                if (atWidth.size == 1) return atWidth[0]
            }
            Thread.sleep(200)
        }
        return null
    }

    override fun close() {
        syncing = false
        clockSync?.join(1000)
        runCatching { device.scanout?.stop() }
        runCatching { device.tracking.stop() }
        device.display?.let { display -> display.preferredMonoMode()?.let { runCatching { display.setMode(it) } } }
        glasses.shutdown()
    }

    companion object {
        private const val CLOCK_BURST = 5
        private const val CLOCK_BURST_GAP_MS = 25L
        private const val CLOCK_RESYNC_MS = 5_000L

        fun open(log: (String) -> Unit): GlassesSession {
            val glasses = DesktopGlasses()
            val device = glasses.connect().getOrElse {
                glasses.shutdown()
                throw IllegalStateException("could not connect to glasses: ${it.message}", it)
            }
            log("glasses: ${device.info.displayName}")
            val calibration = device.calibration?.load()
            log("calibration: ${calibration?.source ?: "none"}")
            val tracker = HeadTracker(device.optics, calibration = calibration)
            device.tracking.start(tracker)
            return GlassesSession(glasses, device, calibration, tracker)
        }
    }
}
