package dev.halone.glasses.drivers.viture

import dev.halone.glasses.api.BrightnessControl
import dev.halone.glasses.api.DeviceEvent
import dev.halone.glasses.api.DeviceEventListener
import dev.halone.glasses.api.DeviceInfo
import dev.halone.glasses.api.DisplayControl
import dev.halone.glasses.api.DisplayMode
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.Optics
import dev.halone.glasses.api.TrackingSink
import dev.halone.glasses.api.TrackingSource
import dev.halone.glasses.transport.HidTransport
import java.util.concurrent.CopyOnWriteArrayList

internal class VitureSession(
    override val info: DeviceInfo,
    override val optics: Optics,
    private val imu: HidTransport,
    mcuTransport: HidTransport,
) : GlassesDevice {
    override val displayNameHints = listOf("VITURE", "Luma")
    private val listeners = CopyOnWriteArrayList<DeviceEventListener>()
    private val mcu = VitureMcuChannel(mcuTransport)
    @Volatile private var closed = false

    override val display = object : DisplayControl {
        override val supportedModes = VitureDriver.MODES
        // Treat the mode switch as a hotplug until Android display behavior has
        // been measured; keeping an old surface across a resolution change is unsafe.
        override val modeChangeRecreatesDisplay = true

        override fun currentMode(): Result<DisplayMode> = mcu.request(VitureProtocol.READ_MODE).mapCatching { data ->
            check(data.size == 1) { "invalid VITURE mode reply length ${data.size}" }
            val raw = VitureProtocol.u8(data, 0)
            val key = VitureDriver.MODE_BYTES.entries.firstOrNull { it.value == raw }?.key
            supportedModes.firstOrNull { it.key == key } ?: error("unknown VITURE mode 0x${raw.toString(16)}")
        }

        override fun setMode(mode: DisplayMode): Result<Unit> {
            if (mode !in supportedModes) return Result.failure(IllegalArgumentException("unsupported VITURE mode $mode"))
            return mcu.request(VitureProtocol.WRITE_MODE, VitureDriver.MODE_BYTES.getValue(mode.key)).map { }
        }
    }

    override val brightness = object : BrightnessControl {
        override val range = 0..8
        override fun current(): Result<Int> = mcu.request(VitureProtocol.READ_BRIGHTNESS).mapCatching { data ->
            check(data.size == 1) { "invalid VITURE brightness reply length ${data.size}" }
            VitureProtocol.u8(data, 0).also { check(it in range) { "invalid VITURE brightness $it" } }
        }

        override fun set(level: Int): Result<Unit> {
            if (level !in range) return Result.failure(IllegalArgumentException("brightness $level outside $range"))
            return mcu.request(VitureProtocol.WRITE_BRIGHTNESS, level, 0).map { }
        }
    }

    override val tracking = object : TrackingSource {
        override val providesOrientation = true
        override val providesPosition = false
        @Volatile private var running = false
        private var thread: Thread? = null

        @Synchronized
        override fun start(sink: TrackingSink) {
            if (running || closed || thread?.isAlive == true) return
            val configured = mcu.request(VitureProtocol.IMU_FREQUENCY, 2)
                .mapCatching { mcu.request(VitureProtocol.IMU_ENABLE, 1).getOrThrow() }
            if (configured.isFailure) {
                emit(DeviceEvent.Disconnected("VITURE tracking could not start: ${configured.exceptionOrNull()?.message}"))
                return
            }
            running = true
            val reader = Thread({ readLoop(sink) }, "viture-imu").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
            thread = reader
            reader.start()
        }

        @Synchronized
        override fun stop() {
            val reader = thread ?: return
            running = false
            mcu.request(VitureProtocol.IMU_ENABLE, 0)
            if (reader !== Thread.currentThread()) reader.join(500)
            // A stalled sink can outlive the USB read timeout. Keep its reader
            // reference so start cannot create a second reader on the same link.
            if (!reader.isAlive) thread = null
        }

        private fun readLoop(sink: TrackingSink) {
            val packet = ByteArray(VitureProtocol.PACKET_SIZE)
            var lastPoseAtNs = System.nanoTime()
            try {
                while (running && !closed) {
                    val length = imu.read(packet, 100)
                    val arrivedAtNs = System.nanoTime()
                    if (!running || closed) break
                    val sample = VitureProtocol.pose(packet, length, arrivedAtNs)
                    if (sample != null) {
                        lastPoseAtNs = arrivedAtNs
                        sink.onSample(sample)
                    }
                    check(arrivedAtNs - lastPoseAtNs < 5_000_000_000L) { "VITURE IMU produced no valid poses for 5 seconds" }
                }
            } catch (error: Exception) {
                if (running && !closed) emit(DeviceEvent.Disconnected(error.message ?: "VITURE tracking failed"))
            } finally {
                running = false
            }
        }
    }

    override fun addEventListener(listener: DeviceEventListener) { listeners += listener }
    override fun removeEventListener(listener: DeviceEventListener) { listeners -= listener }
    private fun emit(event: DeviceEvent) = listeners.forEach { it.onEvent(event) }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        tracking.stop()
        mcu.close()
        imu.close()
        listeners.clear()
    }
}
