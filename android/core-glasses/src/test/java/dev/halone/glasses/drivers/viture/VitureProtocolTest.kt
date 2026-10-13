package dev.halone.glasses.drivers.viture

import dev.halone.glasses.math.Quaternion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VitureProtocolTest {
    @Test
    fun `commands match the vendor SDK byte for byte`() {
        assertArrayEquals(VitureSdkVectors.READ_MODE, VitureProtocol.command(VitureProtocol.READ_MODE))
        assertArrayEquals(VitureSdkVectors.SBS_1200, VitureProtocol.command(VitureProtocol.WRITE_MODE, 0x42))
        assertArrayEquals(VitureSdkVectors.READ_BRIGHTNESS, VitureProtocol.command(VitureProtocol.READ_BRIGHTNESS))
        assertArrayEquals(VitureSdkVectors.BRIGHTNESS_4, VitureProtocol.command(VitureProtocol.WRITE_BRIGHTNESS, 4, 0))
        assertArrayEquals(VitureSdkVectors.FREQUENCY_120, VitureProtocol.command(VitureProtocol.IMU_FREQUENCY, 2))
        assertArrayEquals(VitureSdkVectors.IMU_ON, VitureProtocol.command(VitureProtocol.IMU_ENABLE, 1))
        assertArrayEquals(VitureSdkVectors.IMU_OFF, VitureProtocol.command(VitureProtocol.IMU_ENABLE, 0))
    }

    @Test
    fun `SDK decoded quaternions map NWU to right up back`() {
        val half = kotlin.math.sqrt(0.5f)
        val cases = listOf(
            VitureSdkVectors.IDENTITY to Quaternion.IDENTITY,
            VitureSdkVectors.NWU_ROLL_90 to Quaternion(half, 0f, 0f, -half),
            VitureSdkVectors.NWU_PITCH_90 to Quaternion(half, -half, 0f, 0f),
            VitureSdkVectors.NWU_YAW_90 to Quaternion(half, 0f, half, 0f),
        )
        for ((packet, expected) in cases) {
            val sample = VitureProtocol.pose(packet, packet.size, 123456L)!!
            assertEquals(123456L, sample.timestampNs)
            assertNull(sample.angularVelocity)
            assertEquals(1f, kotlin.math.abs(sample.orientation.dot(expected)), 0.00001f)
        }
    }

    @Test
    fun `truncation corrupt CRC wrong header and unknown reports never publish a pose`() {
        val packet = VitureSdkVectors.IDENTITY
        for (length in 0 until 54) assertNull(VitureProtocol.pose(packet, length, 1))
        assertNull(VitureProtocol.pose(packet, packet.size + 1, 1))
        for (offset in listOf(0, 1, 2, 4, 14, 38, 53)) {
            val corrupt = packet.copyOf()
            corrupt[offset] = (corrupt[offset].toInt() xor 1).toByte()
            assertNull("offset $offset", VitureProtocol.pose(corrupt, corrupt.size, 1))
        }
        val unrelated = VitureProtocol.command(0x0309, *IntArray(36))
        unrelated[1] = 0xfc.toByte()
        assertNull(VitureProtocol.pose(unrelated, unrelated.size, 1))
    }

    @Test
    fun `invalid quaternions are rejected even with a valid CRC`() {
        for (bits in listOf(0, Float.NaN.toRawBits(), Float.POSITIVE_INFINITY.toRawBits(), 4f.toRawBits())) {
            val payload = IntArray(36)
            repeat(4) { payload[20 + it] = bits ushr (24 - it * 8) and 0xff }
            val packet = VitureProtocol.command(VitureProtocol.POSE, *payload)
            packet[1] = 0xfc.toByte()
            assertNull(VitureProtocol.pose(packet, packet.size, 1))
        }
    }
}
