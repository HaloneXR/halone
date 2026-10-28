package dev.halone.app

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.NetworkRequest
import dev.halone.glasses.registry.GlassesRegistry
import dev.halone.glasses.registry.ProbeResult
import dev.halone.glasses.transport.HidTransportProvider
import dev.halone.glasses.transport.LinkLocalNetworks
import dev.halone.glasses.transport.SocketTransportProvider
import dev.halone.glasses.transport.UsbHidTransportProvider
import dev.halone.glasses.transport.usbnet.UsbEcmLink
import dev.halone.glasses.transport.usbnet.UsbNetTransportProvider
import javax.net.SocketFactory

/**
 * Ties the vendor-neutral registry to Android's USB stack: find a recognised
 * device, open it once permission exists, hand the driver its transports.
 *
 * Nothing here knows what XREAL is, which is the point of the exercise.
 */
class GlassesConnection(
    private val context: Context,
    private val registry: GlassesRegistry = GlassesRegistry.default(),
) {
    private val usbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private val linkLocal by lazy { LinkLocalNetworks(context) }

    @Volatile
    private var provider: AutoCloseable? = null

    @Volatile
    var device: GlassesDevice? = null
        private set

    fun attachedDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun findSupported(): Pair<UsbDevice, ProbeResult>? =
        UsbHidTransportProvider.identities(usbManager)
            .firstNotNullOfOrNull { (usbDevice, identity) ->
                registry.probe(identity)?.let { usbDevice to it }
            }

    /** Network devices skip the prompt only when the phone brought their network up itself. */
    fun hasPermission(usbDevice: UsbDevice): Boolean {
        val transports = registry.probe(UsbHidTransportProvider.identityOf(usbDevice))?.match?.transports
        return hasSystemLink(transports?.network) || usbManager.hasPermission(usbDevice)
    }

    private fun hasSystemLink(network: NetworkRequest?) =
        network != null && network.hosts.any { linkLocal.networkFor(it) != null }

    /**
     * True for a network device whose link is not there at all: the phone has
     * no network to it, and it offers none over USB for Halone to run itself.
     */
    fun linkMissing(usbDevice: UsbDevice, probe: ProbeResult): Boolean {
        val network = probe.match.transports.network ?: return false
        return !hasSystemLink(network) && !UsbEcmLink.hasEcm(usbDevice)
    }

    /** Asks the device to turn its link on; it re-enumerates when this succeeds. Blocking. */
    fun enableLink(usbDevice: UsbDevice, probe: ProbeResult): Result<Unit> {
        val setup = probe.match.setup ?: return Result.failure(IllegalStateException("this device cannot do that"))
        val connection = usbManager.openDevice(usbDevice)
            ?: return Result.failure(IllegalStateException("openDevice returned null — permission not granted?"))
        return UsbHidTransportProvider(usbDevice, connection).use { transports ->
            transports.open(setup.controlInterface).mapCatching { setup.enableLink(it).getOrThrow() }
        }
    }

    fun requestPermission(usbDevice: UsbDevice, intent: android.app.PendingIntent) =
        usbManager.requestPermission(usbDevice, intent)

    fun connect(): Result<GlassesDevice> {
        val (usbDevice, probe) = findSupported()
            ?: return Result.failure(IllegalStateException("no supported glasses attached"))

        close()

        val transports = transportsFor(usbDevice, probe).getOrElse { return Result.failure(it) }

        return registry.connect(probe, transports)
            .onSuccess { device = it }
            .onFailure { close() }
            .recoverCatching { error ->
                if (probe.match.transports.network == null) throw error
                throw IllegalStateException("${error.message}\nnetworks seen:\n${linkLocal.describe()}", error)
            }
    }

    fun standIn(device: GlassesDevice) {
        close()
        this.device = device
    }

    private fun transportsFor(usbDevice: UsbDevice, probe: ProbeResult): Result<HidTransportProvider> {
        val network = probe.match.transports.network
        if (network != null && network.hosts.any { linkLocal.networkFor(it) != null }) {
            val transports = SocketTransportProvider(network.hosts) { host ->
                linkLocal.socketFactoryFor(host) ?: SocketFactory.getDefault()
            }
            provider = transports
            return Result.success(transports)
        }

        val connection = usbManager.openDevice(usbDevice)
            ?: return Result.failure(IllegalStateException("openDevice returned null — permission not granted?"))
        if (network != null) {
            return Result.success(UsbNetTransportProvider(usbDevice, connection, network.hosts).also { provider = it })
        }
        return Result.success(UsbHidTransportProvider(usbDevice, connection).also { provider = it })
    }

    fun close() {
        device?.let { runCatching { it.close() } }
        device = null
        provider?.let { runCatching { it.close() } }
        provider = null
    }

    fun describeSupported(): String = registry.describeSupported()
}
