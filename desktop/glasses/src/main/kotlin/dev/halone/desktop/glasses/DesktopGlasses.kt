package dev.halone.desktop.glasses

import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.UsbIdentity
import dev.halone.glasses.registry.GlassesRegistry
import dev.halone.glasses.registry.ProbeResult
import org.hid4java.HidDevice
import org.hid4java.HidManager
import org.hid4java.HidServices
import org.hid4java.HidServicesSpecification
import org.hid4java.ScanMode

/** A physical USB device, gathered from the per-interface entries hidapi lists. */
data class AttachedDevice(val identity: UsbIdentity, val interfaces: List<HidDevice>)

/** The desktop counterpart of the app's `GlassesConnection`: hidapi in place of Android's USB stack. */
class DesktopGlasses(private val registry: GlassesRegistry = GlassesRegistry.default()) : AutoCloseable {
    private val services: HidServices = HidManager.getHidServices(
        HidServicesSpecification().apply {
            isAutoStart = false
            isAutoDataRead = false
            scanMode = ScanMode.NO_SCAN
        }
    )
    private var provider: HidapiTransportProvider? = null
    var device: GlassesDevice? = null
        private set

    fun attached(): List<AttachedDevice> =
        services.attachedHidDevices
            .groupBy { Triple(it.vendorId, it.productId, it.serialNumber.orEmpty()) }
            .map { (key, interfaces) ->
                val first = interfaces.first()
                AttachedDevice(
                    UsbIdentity(
                        vendorId = key.first,
                        productId = key.second,
                        manufacturerName = first.manufacturer,
                        productName = first.product,
                        busAddress = key.third.ifEmpty { null },
                    ),
                    interfaces.sortedBy { it.interfaceNumber },
                )
            }

    fun findSupported(): Pair<AttachedDevice, ProbeResult>? =
        attached().firstNotNullOfOrNull { attached -> registry.probe(attached.identity)?.let { attached to it } }

    fun connect(): Result<GlassesDevice> {
        val (attached, probe) = findSupported()
            ?: return Result.failure(IllegalStateException("no supported glasses attached"))
        close()
        val transports = HidapiTransportProvider(attached.interfaces)
        provider = transports
        return registry.connect(probe, transports)
            .onSuccess { device = it }
            .onFailure { close() }
    }

    override fun close() {
        device?.let { runCatching { it.close() } }
        device = null
        provider?.let { runCatching { it.close() } }
        provider = null
    }

    fun shutdown() {
        close()
        services.shutdown()
    }

    val nativeVersion: String get() = HidServices.getNativeVersion()
}
