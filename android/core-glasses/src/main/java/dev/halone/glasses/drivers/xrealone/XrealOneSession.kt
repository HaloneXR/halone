package dev.halone.glasses.drivers.xrealone

import dev.halone.glasses.api.AxisConvention
import dev.halone.glasses.api.BrightnessControl
import dev.halone.glasses.api.CalibrationSource
import dev.halone.glasses.api.DeviceCalibration
import dev.halone.glasses.api.DeviceEvent
import dev.halone.glasses.api.DeviceEventListener
import dev.halone.glasses.api.DeviceInfo
import dev.halone.glasses.api.DisplayControl
import dev.halone.glasses.api.DisplayMode
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.Optics
import dev.halone.glasses.api.TrackingSink
import dev.halone.glasses.math.Vec3
import dev.halone.glasses.api.TrackingSource
import dev.halone.glasses.drivers.xreal.XrealCalibrationParser
import dev.halone.glasses.transport.HidTransport
import java.util.concurrent.CopyOnWriteArrayList

internal class XrealOneSession(
    override val info: DeviceInfo,
    private val model: XrealOneModel,
    private val reportsTransport: HidTransport,
    controlTransport: HidTransport?,
    private val axes: AxisConvention,
) : GlassesDevice {

    override val optics: Optics = model.optics

    override val displayNameHints = listOf(model.model, "XREAL One", "XREAL")

    private val listeners = CopyOnWriteArrayList<DeviceEventListener>()

    private val control = controlTransport?.let {
        XrealOneControl(it, ::onControlEvent) { reason -> emit(DeviceEvent.Disconnected(reason)) }
            .apply { start() }
    }

    private val reports = Reports()

    override val tracking: TrackingSource = reports

    override val display: DisplayControl? = control?.let { InputModeControl(it) }

    override val brightness: BrightnessControl? = control?.let { Brightness(it) }

    override val calibration: CalibrationSource? = control?.let { Calibration(it) }

    init {
        reports.startReader()
    }

    override fun addEventListener(listener: DeviceEventListener) {
        listeners += listener
    }

    override fun removeEventListener(listener: DeviceEventListener) {
        listeners -= listener
    }

    private fun emit(event: DeviceEvent) = listeners.forEach { it.onEvent(event) }

    override fun close() {
        reports.close()
        control?.close()
    }

    private fun onControlEvent(magic: Int, payload: ByteArray) {
        if (magic == XrealOneProtocol.MSG_KEY_STATE && payload.size >= 8 && payload[4].toInt() == KEY_DOWN) {
            emit(DeviceEvent.ButtonPressed(DeviceEvent.Button.UNKNOWN))
        }
    }

    /**
     * The glasses stream reports from the moment the port is opened, so the
     * reader runs for the whole session and [start] only attaches a sink. A
     * reader that paused would come back to seconds of buffered samples.
     */
    private inner class Reports : TrackingSource {
        override val providesOrientation = false
        override val providesPosition = false

        @Volatile
        private var sink: TrackingSink? = null

        @Volatile
        private var running = false
        private var thread: Thread? = null
        private val framer = XrealOneFramer(MAX_REPORT_PAYLOAD)
        private var pendingMagnet: Vec3? = null

        override fun start(sink: TrackingSink) {
            this.sink = sink
        }

        override fun stop() {
            sink = null
        }

        fun startReader() {
            running = true
            thread = Thread(::readLoop, "xreal-one-reports").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }
        }

        fun close() {
            sink = null
            running = false
            runCatching { reportsTransport.close() }
            thread?.join(500)
            thread = null
        }

        private fun readLoop() {
            val chunk = ByteArray(READ_CHUNK)
            var silentReads = 0
            while (running) {
                val n = reportsTransport.read(chunk, READ_TIMEOUT_MS)
                if (n < 0 || (n == 0 && ++silentReads >= MAX_SILENT_READS)) {
                    if (running) emit(DeviceEvent.Disconnected("report stream stopped"))
                    running = false
                    break
                }
                if (n == 0) continue
                silentReads = 0
                framer.append(chunk, n)
                framer.drain(::onMessage)
            }
        }

        private fun onMessage(magic: Int, data: ByteArray, offset: Int, length: Int) {
            if (magic != XrealOneProtocol.MSG_REPORT || length != XrealOneReport.SIZE) return
            XrealOneReport.magnet(data, offset, axes)?.let {
                pendingMagnet = it
                return
            }
            val target = sink ?: return
            XrealOneReport.inertial(data, offset, axes, pendingMagnet)?.let {
                pendingMagnet = null
                target.onSample(it)
            }
        }
    }

    private inner class InputModeControl(private val channel: XrealOneControl) : DisplayControl {

        override val supportedModes = model.modes

        override val modeChangeRecreatesDisplay = true

        override val rateChosenByHost = true

        override fun currentMode(): Result<DisplayMode> =
            channel.request(XrealOneProtocol.MSG_GET_INPUT_MODE, XrealOneProtocol.getBody())
                .mapCatching { value ->
                    val raw = (value as? XrealOneProtocol.Value.Number)?.value?.toInt()
                        ?: error("input mode reply was not a number")
                    val key = XrealOneDriver.INPUT_MODES.entries.firstOrNull { it.value == raw }?.key
                        ?: error("unknown input mode $raw")
                    model.modes.first { it.key == key }
                }

        override fun setMode(mode: DisplayMode): Result<Unit> {
            val raw = XrealOneDriver.INPUT_MODES[mode.key]
                ?: return Result.failure(IllegalArgumentException("mode ${mode.key} is not an XREAL One mode"))
            return channel.request(XrealOneProtocol.MSG_SET_INPUT_MODE, XrealOneProtocol.setBody(raw)).map { }
        }
    }

    private inner class Brightness(private val channel: XrealOneControl) : BrightnessControl {

        override val range = 0..9

        override fun current(): Result<Int> =
            channel.request(XrealOneProtocol.MSG_GET_BRIGHTNESS, XrealOneProtocol.getBody())
                .mapCatching {
                    (it as? XrealOneProtocol.Value.Number)?.value?.toInt() ?: error("brightness reply was not a number")
                }

        override fun set(level: Int): Result<Unit> {
            if (level !in range) {
                return Result.failure(IllegalArgumentException("brightness $level outside $range"))
            }
            return channel.request(XrealOneProtocol.MSG_SET_BRIGHTNESS, XrealOneProtocol.setBody(level)).map { }
        }
    }

    private inner class Calibration(private val channel: XrealOneControl) : CalibrationSource {

        override fun read(onReply: (ByteArray) -> Unit): Result<ByteArray> =
            channel.request(XrealOneProtocol.MSG_GET_CONFIG, XrealOneProtocol.getBody(), XrealOneControl.CONFIG_TIMEOUT_MS)
                .mapCatching {
                    (it as? XrealOneProtocol.Value.Bytes)?.value ?: error("config reply was not a string")
                }
                .onSuccess(onReply)

        override fun load(): DeviceCalibration =
            read()
                .mapCatching { bytes ->
                    XrealCalibrationParser.parse(
                        bytes, axes, DeviceCalibration.Source.DEVICE, factoryFrame = AxisConvention.IDENTITY,
                    ).getOrThrow()
                }
                .onFailure { emit(DeviceEvent.CalibrationFallback(it.message ?: "unknown error")) }
                .getOrElse { uncalibrated() }

        /**
         * Empty rather than a bundled unit's numbers: there is no One-series
         * blob we can ship, and an Air's would impose the wrong field of view
         * and mounting angle. With no eyes the compositor renders from [optics].
         */
        private fun uncalibrated() = DeviceCalibration(
            serial = null,
            source = DeviceCalibration.Source.FALLBACK,
            eyes = emptyMap(),
            imu = null,
        )
    }

    private companion object {
        const val KEY_DOWN = 1
        const val MAX_REPORT_PAYLOAD = 4096
        const val READ_CHUNK = 16 * 1024
        const val READ_TIMEOUT_MS = 100
        const val MAX_SILENT_READS = 50
    }
}
