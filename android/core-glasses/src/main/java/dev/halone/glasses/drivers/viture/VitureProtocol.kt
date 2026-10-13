package dev.halone.glasses.drivers.viture

import dev.halone.glasses.api.TrackingSample
import dev.halone.glasses.math.Quaternion

/** Gen1 framing, checked against the supplied VITURE Web SDK. See docs/protocol/viture-hid.md. */
internal object VitureProtocol {
    const val PACKET_SIZE = 64
    const val READ_BRIGHTNESS = 0x0005
    const val WRITE_BRIGHTNESS = 0x0006
    const val READ_MODE = 0x0007
    const val WRITE_MODE = 0x0008
    const val IMU_ENABLE = 0x0015
    const val IMU_FREQUENCY = 0x0018
    const val POSE = 0x0308
    const val PAYLOAD_OFFSET = 18

    fun command(messageId: Int, vararg payload: Int): ByteArray {
        require(payload.size <= PACKET_SIZE - PAYLOAD_OFFSET)
        return ByteArray(PACKET_SIZE).also { packet ->
            packet[0] = 0xff.toByte()
            packet[1] = 0xfe.toByte()
            putU16(packet, 4, 12 + payload.size)
            putU16(packet, 14, messageId)
            payload.forEachIndexed { index, value -> packet[PAYLOAD_OFFSET + index] = value.toByte() }
            putU16(packet, 2, crc(packet, PAYLOAD_OFFSET + payload.size))
        }
    }

    fun valid(packet: ByteArray, length: Int, header: Int): Boolean {
        if (length !in PAYLOAD_OFFSET..minOf(packet.size, PACKET_SIZE)) return false
        if (u8(packet, 0) != 0xff || u8(packet, 1) != header) return false
        val end = 6 + u16(packet, 4)
        return end in PAYLOAD_OFFSET..length && u16(packet, 2) == crc(packet, end)
    }

    fun messageId(packet: ByteArray): Int = u16(packet, 14)
    fun payloadLength(packet: ByteArray): Int = u16(packet, 4) - 12
    fun u8(packet: ByteArray, at: Int): Int = packet[at].toInt() and 0xff

    fun pose(packet: ByteArray, length: Int, arrivedAtNs: Long): TrackingSample.Oriented? {
        if (!valid(packet, length, 0xfc) || messageId(packet) != POSE || payloadLength(packet) < 36) return null
        // The SDK reads qw/qx/qy/qz after 12 bytes of Euler angles and 8 bytes
        // of device timing. Its NWU basis is forward/left/up; Halone is right/up/back.
        val w = floatBe(packet, 38)
        val x = -floatBe(packet, 46)
        val y = floatBe(packet, 50)
        val z = -floatBe(packet, 42)
        if (!w.isFinite() || !x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        val normSquared = w * w + x * x + y * y + z * z
        if (normSquared !in 0.5f..1.5f) return null
        // The device clock has no measured host offset yet. Publishing it as
        // System.nanoTime would make HeadTracker's age and prediction meaningless.
        return TrackingSample.Oriented(arrivedAtNs, Quaternion(w, x, y, z).normalized())
    }

    private fun floatBe(packet: ByteArray, at: Int): Float = Float.fromBits(
        (u8(packet, at) shl 24) or (u8(packet, at + 1) shl 16) or
            (u8(packet, at + 2) shl 8) or u8(packet, at + 3)
    )

    private fun u16(packet: ByteArray, at: Int): Int = u8(packet, at) or (u8(packet, at + 1) shl 8)

    private fun putU16(packet: ByteArray, at: Int, value: Int) {
        packet[at] = value.toByte()
        packet[at + 1] = (value ushr 8).toByte()
    }

    private fun crc(packet: ByteArray, end: Int): Int {
        var value = 0
        for (at in 4 until end) {
            value = value xor (u8(packet, at) shl 8)
            repeat(8) { value = ((value shl 1) xor if (value and 0x8000 != 0) 0x1021 else 0) and 0xffff }
        }
        return value
    }
}
