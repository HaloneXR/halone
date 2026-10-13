package dev.halone.glasses.drivers.viture

import dev.halone.glasses.transport.HidTransport

/** Serializes exchanges because Gen1 SDK commands carry no transaction counter. */
internal class VitureMcuChannel(private val transport: HidTransport) {
    private val buffer = ByteArray(VitureProtocol.PACKET_SIZE)
    private var closed = false

    @Synchronized
    fun request(messageId: Int, vararg payload: Int): Result<ByteArray> = runCatching {
        check(!closed) { "VITURE control channel is closed" }
        // A previous timeout can leave an ACK queued. Discard queued packets
        // before writing; a matching message ID alone does not establish freshness.
        for (attempt in 0 until 8) {
            if (transport.read(buffer, 10) <= 0) break
        }
        val command = VitureProtocol.command(messageId, *payload)
        check(transport.write(command) == command.size) { "VITURE command 0x${messageId.toString(16)} write failed" }
        val deadline = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val length = transport.read(buffer, 100)
            // Android also returns -1 for a USB timeout, so retry until the
            // exchange deadline instead of treating one quiet read as unplugging.
            if (!VitureProtocol.valid(buffer, length, 0xfd) || VitureProtocol.messageId(buffer) != messageId) continue
            val payloadLength = VitureProtocol.payloadLength(buffer)
            check(payloadLength >= 1) { "VITURE reply has no status" }
            val status = VitureProtocol.u8(buffer, VitureProtocol.PAYLOAD_OFFSET)
            check(status == 0) { "VITURE rejected command 0x${messageId.toString(16)} with status $status" }
            return@runCatching buffer.copyOfRange(VitureProtocol.PAYLOAD_OFFSET + 1, VitureProtocol.PAYLOAD_OFFSET + payloadLength)
        }
        error("VITURE command 0x${messageId.toString(16)} timed out")
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        transport.close()
    }
}
