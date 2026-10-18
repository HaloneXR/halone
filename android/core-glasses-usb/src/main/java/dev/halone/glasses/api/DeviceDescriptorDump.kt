package dev.halone.glasses.api

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/**
 * Full descriptor dump for a USB device.
 *
 * Deliberately part of the public API rather than spike-only code: the first
 * thing anyone adding support for new glasses needs is a way to see what the
 * hardware actually exposes, and the first thing we will ask them to paste into
 * an issue is this output.
 */
object DeviceDescriptorDump {

    fun describe(device: UsbDevice): String = buildString {
        appendLine("device: ${device.deviceName}")
        appendLine("  vendor=0x%04x product=0x%04x".format(device.vendorId, device.productId))
        appendLine("  manufacturer=${nameOf { device.manufacturerName }} product=${nameOf { device.productName }}")
        appendLine("  serial=${nameOf { device.serialNumber }}")
        appendLine("  interfaces=${device.interfaceCount} configurations=${device.configurationCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            appendLine(
                "  [idx $i] ifaceId=${iface.id} alt=${iface.alternateSetting} " +
                    "class=${classLabel(iface.interfaceClass)} sub=${iface.interfaceSubclass} " +
                    "proto=${iface.interfaceProtocol} endpoints=${iface.endpointCount}"
            )
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                appendLine(
                    "      ep addr=0x%02x dir=%s type=%s maxPacket=%d interval=%d".format(
                        ep.address,
                        if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                        typeLabel(ep.type),
                        ep.maxPacketSize,
                        ep.interval,
                    )
                )
            }
        }
    }

    private inline fun nameOf(block: () -> String?) =
        runCatching(block).getOrNull() ?: "<unavailable>"

    private fun classLabel(c: Int) = when (c) {
        UsbConstants.USB_CLASS_HID -> "HID($c)"
        UsbConstants.USB_CLASS_VIDEO -> "VIDEO($c)"
        UsbConstants.USB_CLASS_AUDIO -> "AUDIO($c)"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR($c)"
        else -> "$c"
    }

    private fun typeLabel(t: Int) = when (t) {
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
        else -> "$t"
    }
}
