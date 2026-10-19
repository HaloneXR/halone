package dev.halone.glasses.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.halone.glasses.api.UsbIdentity

/**
 * A claimed USB HID interface, exposed as raw bytes.
 *
 * `claimInterface(force = true)` detaches the kernel HID driver, which is what
 * makes this equivalent to writing hidraw on Linux — no report IDs are
 * involved, so protocol bytes go on the wire exactly as the reference drivers
 * send them.
 */
class UsbHidTransport internal constructor(
    override val name: String,
    private val connection: UsbDeviceConnection,
    internal val iface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint?,
) : HidTransport {

    override fun write(packet: ByteArray, timeoutMs: Int): Int =
        if (outEndpoint != null) {
            connection.bulkTransfer(outEndpoint, packet, packet.size, timeoutMs)
        } else {
            // HID permits output reports on endpoint zero. VITURE's MCU can
            // expose only an interrupt IN endpoint; requiring OUT rejects it.
            connection.controlTransfer(0x21, 0x09, 0x0200, iface.id, packet, packet.size, timeoutMs)
        }

    override fun read(into: ByteArray, timeoutMs: Int): Int =
        connection.bulkTransfer(inEndpoint, into, into.size, timeoutMs)

    override fun close() {
        runCatching { connection.releaseInterface(iface) }
    }
}

/**
 * Claims interfaces on one USB device and hands them out as transports.
 *
 * Owns the [UsbDeviceConnection] shared by every interface on the device, so
 * [close] must outlive all transports it produced.
 */
class UsbHidTransportProvider(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) : HidTransportProvider, AutoCloseable {

    private val opened = mutableListOf<UsbHidTransport>()

    override fun open(interfaceId: Int): Result<HidTransport> {
        val iface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceId }
            ?: return Result.failure(IllegalArgumentException("no interface with id $interfaceId"))

        if (!connection.claimInterface(iface, true)) {
            return Result.failure(IllegalStateException("claimInterface($interfaceId) refused"))
        }

        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
        }

        if (inEp == null || (outEp == null && iface.interfaceClass != UsbConstants.USB_CLASS_HID)) {
            connection.releaseInterface(iface)
            return Result.failure(
                IllegalStateException("interface $interfaceId needs an IN endpoint and either OUT or HID output reports")
            )
        }

        val transport = UsbHidTransport("usb:iface$interfaceId", connection, iface, inEp, outEp)
        opened += transport
        return Result.success(transport)
    }

    override fun close() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        runCatching { connection.close() }
    }

    companion object {
        fun identityOf(device: UsbDevice) = UsbIdentity(
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturerName = runCatching { device.manufacturerName }.getOrNull(),
            productName = runCatching { device.productName }.getOrNull(),
            busAddress = device.deviceName,
        )

        fun identities(manager: UsbManager): List<Pair<UsbDevice, UsbIdentity>> =
            manager.deviceList.values.map { it to identityOf(it) }
    }
}
