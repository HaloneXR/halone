package dev.halone.glasses.drivers.viture

import dev.halone.glasses.api.DeviceEvent
import dev.halone.glasses.api.StereoLayout
import dev.halone.glasses.api.TrackingSink
import dev.halone.glasses.api.UsbIdentity
import dev.halone.glasses.api.preferredStereoMode
import dev.halone.glasses.registry.GlassesRegistry
import dev.halone.glasses.transport.HidTransport
import dev.halone.glasses.transport.HidTransportProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class VitureSessionTest {
    @Test
    fun `registry claims both Luma Pro variants and rejects other VITURE families`() {
        val registry = GlassesRegistry.default()
        for (pid in listOf(0x1121, 0x1141)) {
            assertEquals("viture:lumapro", registry.probe(UsbIdentity(0x35ca, pid))!!.match.info.driverId)
        }
        for (pid in listOf(0x1101, 0x1102, 0x1104, 0x1201, 0x1211, 0x1301, 0xffff)) {
            assertNull(registry.probe(UsbIdentity(0x35ca, pid)))
        }
        assertNull(VitureDriver().probe(UsbIdentity(0x3318, 0x1121)))
        assertNotNull(registry.probe(UsbIdentity(0x3318, 0x0432)))
    }

    @Test
    fun `failure opening the required MCU releases the IMU`() {
        val imu = TestTransport()
        val driver = VitureDriver()
        val result = driver.connect(driver.probe(UsbIdentity(0x35ca, 0x1121))!!, object : HidTransportProvider {
            override fun open(interfaceId: Int): Result<HidTransport> =
                if (interfaceId == 0) Result.success(imu) else Result.failure(IllegalStateException("MCU unavailable"))
        })
        assertTrue(result.isFailure)
        assertEquals(1, imu.closeCount)
    }

    @Test
    fun `mode and brightness reads exclude status and validate returned values`() {
        val mcu = TestTransport { command ->
            when (VitureProtocol.messageId(command)) {
                VitureProtocol.READ_MODE -> listOf(reply(VitureProtocol.READ_MODE, 0, 0x41))
                VitureProtocol.READ_BRIGHTNESS -> listOf(reply(VitureProtocol.READ_BRIGHTNESS, 0, 4))
                else -> listOf(reply(VitureProtocol.messageId(command), 0))
            }
        }
        session(mcu = mcu).use { device ->
            assertEquals("mirror-1200-60", device.display.currentMode().getOrThrow().key)
            val stereo = device.display.preferredStereoMode()!!
            assertEquals(StereoLayout.FULL_SBS, stereo.layout)
            assertEquals(1200, stereo.perEyeHeight)
            assertEquals(60f, stereo.refreshHz, 0f)
            assertTrue(device.display.supportedModes.none { it.verified })
            assertTrue(device.display.setMode(stereo).isSuccess)
            assertEquals(4, device.brightness.current().getOrThrow())
            assertTrue(device.brightness.set(8).isSuccess)
            val before = mcu.written.size
            assertTrue(device.brightness.set(9).isFailure)
            assertTrue(device.display.setMode(stereo.copy(frameWidth = 1920)).isFailure)
            assertEquals(before, mcu.written.size)
        }
    }

    @Test
    fun `wrong ACK bad CRC stale ACK and quiet reads cannot complete a request`() {
        val mcu = TestTransport { _ ->
            val corrupt = reply(VitureProtocol.READ_MODE, 0, 0x32).also { it[2] = (it[2].toInt() xor 1).toByte() }
            listOf(reply(VitureProtocol.READ_BRIGHTNESS, 0, 7), corrupt, reply(VitureProtocol.READ_MODE, 0, 0x41))
        }
        mcu.inbound.add(reply(VitureProtocol.READ_MODE, 0, 0x32))
        session(mcu = mcu).use { device ->
            assertEquals("mirror-1200-60", device.display.currentMode().getOrThrow().key)
        }
    }

    @Test
    fun `device rejection malformed values and short writes are failures`() {
        session(mcu = TestTransport { command -> listOf(reply(VitureProtocol.messageId(command), 2)) }).use { device ->
            assertTrue(device.display.setMode(device.display.supportedModes.first()).isFailure)
        }
        session(mcu = TestTransport { command -> listOf(reply(VitureProtocol.messageId(command), 0, 0xff)) }).use { device ->
            assertTrue(device.display.currentMode().isFailure)
            assertTrue(device.brightness.current().isFailure)
        }
        session(mcu = TestTransport { command -> listOf(reply(VitureProtocol.messageId(command), 0)) }).use { device ->
            assertTrue(device.display.currentMode().isFailure)
            assertTrue(device.brightness.current().isFailure)
        }
        val short = TestTransport().apply { writeResult = 0 }
        assertTrue(VitureMcuChannel(short).request(VitureProtocol.READ_MODE).isFailure)
    }

    @Test
    fun `tracking starts once can stop and restart and close releases both links once`() {
        val imu = TestTransport()
        val mcu = TestTransport { command -> listOf(reply(VitureProtocol.messageId(command), 0)) }
        val device = session(imu, mcu)
        assertTrue(device.tracking.providesOrientation)
        assertFalse(device.tracking.providesPosition)
        val first = CountDownLatch(1)
        val sink = TrackingSink { first.countDown() }
        device.tracking.start(sink)
        device.tracking.start(sink)
        imu.inbound.add(VitureSdkVectors.IDENTITY)
        assertTrue(first.await(2, TimeUnit.SECONDS))
        device.tracking.stop()
        device.tracking.stop()
        val second = CountDownLatch(1)
        device.tracking.start { second.countDown() }
        imu.inbound.add(VitureSdkVectors.NWU_YAW_90)
        assertTrue(second.await(2, TimeUnit.SECONDS))
        device.close()
        device.close()
        device.tracking.start(sink)
        assertEquals(1, imu.closeCount)
        assertEquals(1, mcu.closeCount)
        assertEquals(listOf(1, 0, 1, 0), mcu.written.filter {
            VitureProtocol.messageId(it) == VitureProtocol.IMU_ENABLE
        }.map { VitureProtocol.u8(it, 18) })
        assertEquals(2, mcu.written.count { VitureProtocol.messageId(it) == VitureProtocol.IMU_FREQUENCY })
    }

    @Test
    fun `failed IMU enable reports a disconnect and emits no samples`() {
        val mcu = TestTransport { command ->
            val id = VitureProtocol.messageId(command)
            listOf(reply(id, if (id == VitureProtocol.IMU_ENABLE) 1 else 0))
        }
        session(mcu = mcu).use { device ->
            val events = mutableListOf<DeviceEvent>()
            device.addEventListener { events += it }
            var samples = 0
            device.tracking.start { samples++ }
            assertEquals(0, samples)
            assertTrue(events.single() is DeviceEvent.Disconnected)
        }
    }

    @Test
    fun `missing ACK times out and closed channels refuse more writes`() {
        val transport = TestTransport()
        val channel = VitureMcuChannel(transport)
        assertTrue(channel.request(VitureProtocol.READ_MODE).isFailure)
        channel.close()
        channel.close()
        assertTrue(channel.request(VitureProtocol.READ_MODE).isFailure)
        assertEquals(1, transport.written.size)
        assertEquals(1, transport.closeCount)
    }

    private fun session(imu: TestTransport = TestTransport(), mcu: TestTransport): VitureSession =
        VitureSession(VitureDriver().probe(UsbIdentity(0x35ca, 0x1121))!!.info, VitureDriver.OPTICS, imu, mcu)

    private fun reply(messageId: Int, vararg payload: Int): ByteArray =
        VitureProtocol.command(messageId, *payload).also { it[1] = 0xfd.toByte() }

    private class TestTransport(private val replies: (ByteArray) -> List<ByteArray> = { emptyList() }) : HidTransport {
        override val name = "viture-test"
        val inbound = LinkedBlockingQueue<ByteArray>()
        val written = Collections.synchronizedList(mutableListOf<ByteArray>())
        var closeCount = 0
        var writeResult: Int? = null
        override fun write(packet: ByteArray, timeoutMs: Int): Int {
            written += packet.copyOf()
            inbound.addAll(replies(packet))
            return writeResult ?: packet.size
        }
        override fun read(into: ByteArray, timeoutMs: Int): Int {
            val packet = inbound.poll(minOf(timeoutMs, 5).toLong(), TimeUnit.MILLISECONDS) ?: return -1
            packet.copyInto(into)
            return packet.size
        }
        override fun close() { closeCount++ }
    }
}
