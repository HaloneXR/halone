package dev.halone.glasses.drivers.viture

import dev.halone.glasses.api.DeviceInfo
import dev.halone.glasses.api.DisplayMode
import dev.halone.glasses.api.DriverMatch
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.GlassesDriver
import dev.halone.glasses.api.Optics
import dev.halone.glasses.api.StereoLayout
import dev.halone.glasses.api.TransportRequest
import dev.halone.glasses.api.UsbIdentity
import dev.halone.glasses.transport.HidTransportProvider

class VitureDriver : GlassesDriver {
    override val id = "viture"
    override val supportedDevices = "VITURE Luma Pro (Gen1 SDK protocol; awaiting hardware verification)"

    override fun probe(identity: UsbIdentity): DriverMatch? {
        if (identity.vendorId != 0x35ca || identity.productId !in setOf(0x1121, 0x1141)) return null
        return DriverMatch(
            info = DeviceInfo("VITURE", "Luma Pro", "viture:lumapro"),
            optics = OPTICS,
            transports = TransportRequest(listOf(0, 1)),
        )
    }

    override fun connect(match: DriverMatch, transports: HidTransportProvider): Result<GlassesDevice> {
        if (match.info.driverId != "viture:lumapro" || match.transports.interfaceIds != listOf(0, 1)) {
            return Result.failure(IllegalArgumentException("match did not come from VitureDriver"))
        }
        val imu = transports.open(0).getOrElse { return Result.failure(it) }
        val mcu = transports.open(1).getOrElse {
            // VITURE cannot start tracking without its MCU, unlike XREAL's
            // optional display channel. Release the first claim on partial failure.
            imu.close()
            return Result.failure(it)
        }
        return Result.success(VitureSession(match.info, match.optics, imu, mcu))
    }

    internal companion object {
        val OPTICS = Optics(
            // Advertised values, not measured rendering calibration. See the
            // live-device checklist before tuning against these numbers.
            diagonalFovDegrees = 52f,
            perEyeWidth = 1920,
            perEyeHeight = 1200,
            imuRateHz = 120,
            eyeAspect = 16f / 10f,
        )
        val MODE_BYTES = linkedMapOf(
            "mirror-1080-60" to 0x31,
            "sbs-1080-60" to 0x32,
            "mirror-1080-90" to 0x33,
            "mirror-1080-120" to 0x34,
            "mirror-1200-60" to 0x41,
            "sbs-1200-60" to 0x42,
            "mirror-1200-90" to 0x43,
            "mirror-1200-120" to 0x44,
        )
        // The SDK defines 90 Hz SBS constants, but the product specification
        // promises 60 Hz SBS. Do not select a higher unconfirmed rate by default.
        val MODES = listOf(
            DisplayMode("mirror-1080-60", StereoLayout.MONO, 1920, 1080, 60f),
            DisplayMode("sbs-1080-60", StereoLayout.FULL_SBS, 3840, 1080, 60f),
            DisplayMode("mirror-1080-90", StereoLayout.MONO, 1920, 1080, 90f),
            DisplayMode("mirror-1080-120", StereoLayout.MONO, 1920, 1080, 120f),
            DisplayMode("mirror-1200-60", StereoLayout.MONO, 1920, 1200, 60f),
            DisplayMode("sbs-1200-60", StereoLayout.FULL_SBS, 3840, 1200, 60f),
            DisplayMode("mirror-1200-90", StereoLayout.MONO, 1920, 1200, 90f),
            DisplayMode("mirror-1200-120", StereoLayout.MONO, 1920, 1200, 120f),
        )
    }
}
