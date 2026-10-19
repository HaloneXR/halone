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
import java.util.concurrent.atomic.AtomicBoolean

/** Connected glasses, their tracker, and the display they show up as. */
class GlassesSession private constructor(
    private var glasses: DesktopGlasses,
    device: GlassesDevice,
    val calibration: DeviceCalibration?,
    val tracker: HeadTracker,
) : AutoCloseable {
    var device: GlassesDevice = device
        private set

    var stereoMode: DisplayMode? = null
        private set

    /** The glasses' refresh timing on the host clock, when they report it. */
    val displayClock: DisplayClock? = device.scanout?.let { DisplayClock() }

    @Volatile
    private var syncing = true
    private var clockSync: Thread? = startClock()

    private fun startClock(): Thread? = displayClock?.let { clock ->
        val scanout = device.scanout ?: return null
        syncing = true
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

    private fun stopDevice() {
        syncing = false
        clockSync?.join(1000)
        runCatching { device.scanout?.stop() }
        runCatching { device.tracking.stop() }
    }

    /**
     * Opens the glasses again after they went away, as they do when unplugged:
     * the same tracker and display clock carry on, with the clock counting
     * afresh, and the glasses go back to stereo. Waits up to [timeoutMs],
     * calling [idle] in between tries.
     */
    fun reconnect(timeoutMs: Long, idle: () -> Unit, log: (String) -> Unit): Result<DisplayMode> {
        stopDevice()
        runCatching { glasses.shutdown() }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val next = DesktopGlasses()
            val connected = next.connect()
            if (connected.isSuccess) {
                glasses = next
                device = connected.getOrThrow()
                break
            }
            next.shutdown()
            if (System.nanoTime() > deadline) return Result.failure(IllegalStateException("the glasses did not come back"))
            idle()
            Thread.sleep(RECONNECT_POLL_MS)
        }
        log("glasses: reconnected ${device.info.displayName}")
        device.tracking.start(tracker)
        displayClock?.reset()
        clockSync = startClock()
        return enterStereo()
    }

    /**
     * Back to their 2D mode if they are still in 3D (as glasses left by a run that ended
     * abruptly are), since only their 2D EDID lists the timings an EDID patch is built from.
     */
    fun leaveStereo(): Boolean {
        val display = device.display ?: return false
        if (display.currentMode().getOrNull()?.isStereo != true) return false
        val mono = display.preferredMonoMode() ?: return false
        return display.setMode(mono).isSuccess
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

    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopDevice()
        device.display?.let { display -> display.preferredMonoMode()?.let { runCatching { display.setMode(it) } } }
        glasses.shutdown()
    }

    companion object {
        private const val CLOCK_BURST = 5
        private const val CLOCK_BURST_GAP_MS = 25L
        private const val CLOCK_RESYNC_MS = 5_000L
        private const val RECONNECT_POLL_MS = 500L

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
