package dev.halone.glasses.registry

import dev.halone.glasses.api.DriverMatch
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.GlassesDriver
import dev.halone.glasses.api.UsbIdentity
import dev.halone.glasses.transport.HidTransportProvider

data class ProbeResult(val driver: GlassesDriver, val match: DriverMatch)

/**
 * Holds the known drivers and picks one for a device.
 *
 * Registration is an explicit list rather than reflection or `ServiceLoader`:
 * on Android that keeps drivers out of the way of R8, makes the supported-device
 * set greppable, and means adding hardware support is a one-line diff plus a
 * driver — which is what we want from an outside contributor.
 */
class GlassesRegistry(private val drivers: List<GlassesDriver>) {

    fun driverIds(): List<String> = drivers.map { it.id }

    /** First driver that recognises the device. Order is priority order. */
    fun probe(identity: UsbIdentity): ProbeResult? =
        drivers.firstNotNullOfOrNull { driver ->
            driver.probe(identity)?.let { ProbeResult(driver, it) }
        }

    fun probeAll(identities: List<UsbIdentity>): List<ProbeResult> =
        identities.mapNotNull { probe(it) }

    fun connect(result: ProbeResult, transports: HidTransportProvider): Result<GlassesDevice> =
        result.driver.connect(result.match, transports)

    fun describeSupported(): String =
        drivers.joinToString("\n") { "${it.id}: ${it.supportedDevices}" }

    companion object {
        /**
         * The supported-hardware list. Add a driver here to enable it.
         */
        fun default(): GlassesRegistry = GlassesRegistry(
            listOf(
                dev.halone.glasses.drivers.xreal.XrealDriver(),
                dev.halone.glasses.drivers.viture.VitureDriver(),
            )
        )
    }
}
