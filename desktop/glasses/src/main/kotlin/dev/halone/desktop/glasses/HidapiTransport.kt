package dev.halone.desktop.glasses

import dev.halone.glasses.transport.HidTransport
import dev.halone.glasses.transport.HidTransportProvider
import org.hid4java.HidDevice

/**
 * One HID interface through hidapi. The glasses use no report IDs, so writes go
 * out behind hidapi's mandatory report ID 0 and reads come back as bare
 * payloads, which matches what the drivers expect from hidraw.
 */
class HidapiTransport(private val device: HidDevice) : HidTransport {
    override val name = "hidapi:iface${device.interfaceNumber}"

    override fun write(packet: ByteArray, timeoutMs: Int): Int =
        device.write(packet, packet.size, 0, false)

    override fun read(into: ByteArray, timeoutMs: Int): Int =
        device.read(into, timeoutMs)

    override fun close() {
        if (device.isOpen) device.close()
    }
}

/** Opens interfaces of one physical device, which hidapi lists as separate devices. */
class HidapiTransportProvider(private val interfaces: List<HidDevice>) : HidTransportProvider, AutoCloseable {
    private val opened = mutableListOf<HidapiTransport>()

    override fun open(interfaceId: Int): Result<HidTransport> {
        val device = interfaces.firstOrNull { it.interfaceNumber == interfaceId }
            ?: return Result.failure(IllegalArgumentException("no HID interface $interfaceId"))
        if (!device.isOpen && !device.open()) {
            return Result.failure(IllegalStateException("could not open interface $interfaceId: ${device.lastErrorMessage}"))
        }
        return Result.success(HidapiTransport(device).also { opened += it })
    }

    override fun close() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }
}
