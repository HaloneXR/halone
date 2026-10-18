package dev.halone.desktop.compositor

import dev.halone.desktop.glasses.DesktopGlasses
import dev.halone.glasses.api.DeviceCalibration
import dev.halone.glasses.api.DisplayMode
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.preferredMonoMode
import dev.halone.glasses.api.preferredStereoMode
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
     */
    fun findMonitor(frameWidth: Int, timeoutSeconds: Double = 15.0): Long? {
        val hints = device.displayNameHints.map { it.lowercase() }
        val deadline = glfwGetTime() + timeoutSeconds
        while (glfwGetTime() < deadline) {
            glfwPollEvents()
            val monitors = glfwGetMonitors()
            if (monitors != null) {
                for (i in 0 until monitors.limit()) {
                    val monitor = monitors.get(i)
                    val name = glfwGetMonitorName(monitor)?.lowercase() ?: continue
                    val mode = glfwGetVideoMode(monitor) ?: continue
                    if (hints.any { name.contains(it) } && mode.width() == frameWidth) return monitor
                }
            }
            Thread.sleep(200)
        }
        return null
    }

    override fun close() {
        runCatching { device.tracking.stop() }
        device.display?.let { display -> display.preferredMonoMode()?.let { runCatching { display.setMode(it) } } }
        glasses.shutdown()
    }

    companion object {
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
