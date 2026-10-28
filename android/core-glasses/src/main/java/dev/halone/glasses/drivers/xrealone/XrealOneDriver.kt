package dev.halone.glasses.drivers.xrealone

import dev.halone.glasses.api.AxisConvention
import dev.halone.glasses.api.DeviceInfo
import dev.halone.glasses.api.DeviceSetup
import dev.halone.glasses.api.DisplayMode
import dev.halone.glasses.api.DriverMatch
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.GlassesDriver
import dev.halone.glasses.api.NetworkRequest
import dev.halone.glasses.api.Optics
import dev.halone.glasses.api.StereoLayout
import dev.halone.glasses.api.TransportRequest
import dev.halone.glasses.api.UsbIdentity
import dev.halone.glasses.transport.HidTransport
import dev.halone.glasses.transport.HidTransportProvider

/**
 * Stabilizer and Follow mode live only in the glasses' menu; the host can
 * neither read nor set them, and with Stabilizer on the sensor port stays shut.
 * The network link can be off too, in which case it is switched back on through
 * the one HID write `XrealOneHid` permits.
 */
internal object XrealOneSetup : DeviceSetup {
    override val beforeStarting =
        "In the glasses' menu (double-click the X button), set the screen to Follow mode and turn Stabilizer off. " +
            "Halone does the head tracking, and the two fight each other otherwise."

    override val linkOffHelp =
        "Halone reads the glasses' sensors over their network connection, which is switched off. " +
            "Turn on TCP/IP in their Developer page (double-click X, choose Other, press minus until Serial Number, " +
            "then hold X for five seconds), or let Halone switch it on."

    override val controlInterface = XrealOneHid.HID_INTERFACE

    override fun enableLink(transport: HidTransport): Result<Unit> {
        val hid = XrealOneHid(transport)
        val functions = hid.usbFunctions().getOrElse { return Result.failure(it) }
        if (functions.networkOn) {
            return Result.failure(
                IllegalStateException("The glasses say their network is already on. Unplug them and plug them back in.")
            )
        }
        return hid.enableNetwork()
    }
}

internal data class XrealOneModel(
    val productId: Int,
    val model: String,
    val optics: Optics,
    val modes: List<DisplayMode>,
)

/**
 * XREAL One and One Pro. Unlike the Air series these carry their sensors and
 * controls over a USB network link (see `docs/protocol/xreal-one.md`), so the
 * driver asks for TCP ports and never claims a USB interface.
 *
 * Written from published reverse-engineering and one real capture; nothing
 * here has run against hardware we own.
 */
class XrealOneDriver : GlassesDriver {

    override val id = "xrealone"

    override val supportedDevices =
        MODELS.values.joinToString(", ") { "XREAL ${it.model}" } +
            " (from published protocol notes; not yet verified on hardware)"

    override fun probe(identity: UsbIdentity): DriverMatch? {
        if (identity.vendorId != VENDOR_ID) return null
        val model = MODELS[identity.productId] ?: return null
        return DriverMatch(
            info = DeviceInfo(
                vendor = "XREAL",
                model = model.model,
                driverId = "$id:${model.model.lowercase().replace(" ", "")}",
            ),
            optics = model.optics,
            transports = TransportRequest(
                network = NetworkRequest(
                    hosts = listOf(XrealOneProtocol.HOST_NCM, XrealOneProtocol.HOST_ECM),
                    ports = listOf(XrealOneProtocol.PORT_REPORTS, XrealOneProtocol.PORT_CONTROL),
                ),
            ),
            tag = model,
            setup = XrealOneSetup,
        )
    }

    override fun connect(match: DriverMatch, transports: HidTransportProvider): Result<GlassesDevice> {
        val model = match.tag as? XrealOneModel
            ?: return Result.failure(IllegalArgumentException("match did not come from XrealOneDriver"))

        val reports = transports.open(XrealOneProtocol.PORT_REPORTS).getOrElse {
            return Result.failure(
                IllegalStateException(
                    "could not open the report stream: ${it.message}. Check that the glasses are in " +
                        "Follow mode with Stabilizer off, and that TCP/IP is on (glasses menu: Other, " +
                        "press minus until Serial Number, hold X for 5 s, Developer page).",
                )
            )
        }
        val control = transports.open(XrealOneProtocol.PORT_CONTROL).getOrNull()

        return Result.success(XrealOneSession(match.info, model, reports, control, AXES))
    }

    internal companion object {
        const val VENDOR_ID = 0x3318

        /**
         * The report frame is x right, y down, z forward. Measured on a 1S, then
         * confirmed on one One Pro by a test-kit session: look up/down, tilt and
         * worn gravity all agree, with the IMU pitched ~31° against the head.
         */
        val AXES = AxisConvention(
            xFrom = AxisConvention.Axis.X_POS,
            yFrom = AxisConvention.Axis.Y_NEG,
            zFrom = AxisConvention.Axis.Z_NEG,
        )

        val INPUT_MODES = mapOf(
            "mono-60" to XrealOneProtocol.INPUT_MODE_MONO,
            "mono-90" to XrealOneProtocol.INPUT_MODE_MONO,
            "mono-120" to XrealOneProtocol.INPUT_MODE_MONO,
            "sbs-60" to XrealOneProtocol.INPUT_MODE_SBS,
        )

        /** The rates a One Pro's EDID offers; 3D is 60 Hz only. */
        private val MODES = listOf(
            DisplayMode("mono-60", StereoLayout.MONO, 1920, 1080, 60f),
            DisplayMode("mono-90", StereoLayout.MONO, 1920, 1080, 90f),
            DisplayMode("mono-120", StereoLayout.MONO, 1920, 1080, 120f),
            DisplayMode("sbs-60", StereoLayout.FULL_SBS, 3840, 1080, 60f),
        )

        /** Spec-sheet field of view; the factory calibration replaces it when readable. */
        private fun optics(diagonalFovDegrees: Float) = Optics(
            diagonalFovDegrees = diagonalFovDegrees,
            perEyeWidth = 1920,
            perEyeHeight = 1080,
            imuRateHz = 1000,
            // Blind A/B by two One Pro wearers (test kit 0.6.0) settled on 35 and
            // 17.5 ms; both rejected 105 outright. The Air's 85 is far too much.
            predictionConstantMs = 30f,
            predictionFrametimeMultiplier = 1f,
            // The same rounds put the right field of view at 0.83 and 0.95 of the
            // factory calibration's.
            calibratedFovScale = 0.9f,
        )

        val MODELS = mapOf(
            0x0436 to XrealOneModel(0x0436, "One Pro", optics(57f), MODES),
            0x0438 to XrealOneModel(0x0438, "One", optics(50f), MODES),
        )
    }
}
