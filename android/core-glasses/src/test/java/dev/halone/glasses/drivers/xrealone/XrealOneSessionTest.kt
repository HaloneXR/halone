package dev.halone.glasses.drivers.xrealone

import dev.halone.glasses.api.DeviceCalibration
import dev.halone.glasses.api.DeviceEvent
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.RefreshRate
import dev.halone.glasses.api.modeFor
import dev.halone.glasses.api.refreshRates
import dev.halone.glasses.api.StereoLayout
import dev.halone.glasses.api.TrackingSample
import dev.halone.glasses.api.UsbIdentity
import dev.halone.glasses.transport.HidTransport
import dev.halone.glasses.transport.HidTransportProvider
import dev.halone.glasses.transport.ReplayHidTransport.Companion.parseHex
import dev.halone.glasses.transport.ReplayHidTransport.Companion.toHexKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class XrealOneSessionTest {

    /**
     * Holds every read until [open] so a test can attach listeners first, then
     * plays [inbound], answers writes from [replies], and reports end of stream.
     */
    private class GatedTransport(
        inbound: List<ByteArray> = emptyList(),
        private val replies: Map<String, ByteArray> = emptyMap(),
        private val endOfStream: Boolean = false,
    ) : HidTransport {
        override val name = "gated"
        private val gate = CountDownLatch(1)
        private val queue = ConcurrentLinkedQueue(inbound)
        val written = CopyOnWriteArrayList<ByteArray>()

        fun open() = gate.countDown()

        override fun write(packet: ByteArray, timeoutMs: Int): Int {
            written += packet
            replies[packet.toHexKey()]?.let { queue += it }
            return packet.size
        }

        override fun read(into: ByteArray, timeoutMs: Int): Int {
            if (!gate.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) return 0
            val next = queue.poll() ?: return if (endOfStream) -1 else 0.also { Thread.sleep(1) }
            next.copyInto(into)
            return next.size
        }

        override fun close() = open()
    }

    private fun connect(reports: HidTransport, control: HidTransport?): GlassesDevice {
        val driver = XrealOneDriver()
        val match = driver.probe(UsbIdentity(vendorId = 0x3318, productId = 0x0436))!!
        return driver.connect(match, object : HidTransportProvider {
            override fun open(interfaceId: Int): Result<HidTransport> = when (interfaceId) {
                XrealOneProtocol.PORT_REPORTS -> Result.success(reports)
                XrealOneProtocol.PORT_CONTROL -> control?.let { Result.success(it) }
                    ?: Result.failure(IllegalStateException("refused"))
                else -> Result.failure(IllegalArgumentException("port $interfaceId"))
            }
        }).getOrThrow()
    }

    private fun request(magic: Int, transaction: Int, body: ByteArray) =
        XrealOneProtocol.request(magic, transaction, body).toHexKey()

    @Test
    fun `every imu report in the capture reaches the sink, in order`() {
        val stream = XrealOneCaptures.REPORTS
        val chunks = stream.toList().chunked(1000).map { it.toByteArray() }
        val reports = GatedTransport(chunks, endOfStream = true)
        val samples = CopyOnWriteArrayList<TrackingSample>()
        val gone = CountDownLatch(1)

        connect(reports, control = null).use { device ->
            device.addEventListener { if (it is DeviceEvent.Disconnected) gone.countDown() }
            device.tracking.start { samples += it }
            reports.open()
            assertTrue("stream end was not reported", gone.await(5, TimeUnit.SECONDS))
        }

        val expected = XrealOneCaptures.messages(stream)
            .count { (_, body) -> XrealOneReport.type(body, 0) == XrealOneReport.TYPE_IMU }
        assertEquals(expected, samples.size)
        val timestamps = samples.map { it.timestampNs }
        assertTrue(timestamps.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `calibration is read from the config and keeps the mounting angle`() {
        val config = XrealOneCaptures.reply(
            XrealOneProtocol.MSG_GET_CONFIG, 1, XrealOneCaptures.stringReplyBody(XrealOneCaptures.CONFIG_JSON),
        )
        val control = GatedTransport(
            replies = mapOf(request(XrealOneProtocol.MSG_GET_CONFIG, 1, XrealOneProtocol.getBody()) to config),
        )

        connect(GatedTransport(), control).use { device ->
            control.open()
            val calibration = device.calibration!!.load()

            assertEquals(DeviceCalibration.Source.DEVICE, calibration.source)
            assertEquals("TEST00000", calibration.serial)
            assertEquals(2, calibration.eyes.size)
            val tilt = Math.toDegrees(2.0 * Math.acos(calibration.headFromImu.w.toDouble()))
            assertEquals(35.0, tilt, 0.1)
            assertNotNull(calibration.imu)
        }
    }

    @Test
    fun `a rejected config read falls back to no calibration and says so`() {
        val rejected = XrealOneCaptures.reply(XrealOneProtocol.MSG_GET_CONFIG, 1, "22 03 08 91 4e".parseHex())
        val control = GatedTransport(
            replies = mapOf(request(XrealOneProtocol.MSG_GET_CONFIG, 1, XrealOneProtocol.getBody()) to rejected),
        )
        val events = CopyOnWriteArrayList<DeviceEvent>()

        connect(GatedTransport(), control).use { device ->
            device.addEventListener { events += it }
            control.open()
            val calibration = device.calibration!!.load()

            assertEquals(DeviceCalibration.Source.FALLBACK, calibration.source)
            assertTrue(calibration.eyes.isEmpty())
            assertTrue(events.any { it is DeviceEvent.CalibrationFallback })
        }
    }

    @Test
    fun `switching to side by side sends the published bytes`() {
        val control = GatedTransport(
            replies = mapOf(
                request(XrealOneProtocol.MSG_SET_INPUT_MODE, 1, XrealOneProtocol.setBody(1)) to
                    XrealOneCaptures.reply(XrealOneProtocol.MSG_SET_INPUT_MODE, 1, "22 00".parseHex()),
            ),
        )

        connect(GatedTransport(), control).use { device ->
            control.open()
            val display = device.display!!
            display.setMode(display.supportedModes.first { it.layout == StereoLayout.FULL_SBS }).getOrThrow()
        }

        assertArrayEquals("28 22 00 00 00 08 80 00 00 01 1a 02 08 01".parseHex(), control.written.single())
    }

    @Test
    fun `the current mode is read back from the glasses`() {
        val control = GatedTransport(
            replies = mapOf(
                request(XrealOneProtocol.MSG_GET_INPUT_MODE, 1, XrealOneProtocol.getBody()) to
                    XrealOneCaptures.reply(XrealOneProtocol.MSG_GET_INPUT_MODE, 1, "22 02 10 01".parseHex()),
            ),
        )

        connect(GatedTransport(), control).use { device ->
            control.open()
            assertEquals("sbs-60", device.display!!.currentMode().getOrThrow().key)
        }
    }

    @Test
    fun `2D offers every rate the glasses take and 3D only 60`() {
        connect(GatedTransport(), GatedTransport()).use { device ->
            val display = device.display!!
            assertTrue(display.rateChosenByHost)
            assertEquals(
                listOf(RefreshRate(60, true), RefreshRate(90, false), RefreshRate(120, false)),
                display.refreshRates(),
            )
            assertEquals("mono-120", display.modeFor(120, stereo = true)?.key)
        }
    }

    @Test
    fun `a key press on the control channel becomes a button event`() {
        val payload = ByteArray(64).also { it[0] = 1; it[4] = 1 }
        val control = GatedTransport(inbound = listOf(XrealOneCaptures.frame(XrealOneProtocol.MSG_KEY_STATE, payload)))
        val pressed = CountDownLatch(1)

        connect(GatedTransport(), control).use { device ->
            device.addEventListener { if (it is DeviceEvent.ButtonPressed) pressed.countDown() }
            control.open()
            assertTrue(pressed.await(2, TimeUnit.SECONDS))
        }
    }

    /** A tester's Galaxy crashed with NetworkOnMainThreadException: the UI asked for the display mode. */
    @Test
    fun `requests are written off the calling thread`() {
        val writers = CopyOnWriteArrayList<Thread>()
        val reply = XrealOneCaptures.reply(XrealOneProtocol.MSG_GET_INPUT_MODE, 1, "22 02 10 00".parseHex())
        val transport = object : HidTransport {
            override val name = "recording"
            private val queue = ConcurrentLinkedQueue<ByteArray>()
            override fun write(packet: ByteArray, timeoutMs: Int): Int {
                writers += Thread.currentThread()
                queue += reply
                return packet.size
            }
            override fun read(into: ByteArray, timeoutMs: Int): Int {
                val next = queue.poll() ?: return 0.also { Thread.sleep(1) }
                next.copyInto(into)
                return next.size
            }
            override fun close() = Unit
        }
        XrealOneControl(transport, { _, _ -> }, {}).use { control ->
            control.start()
            assertTrue(control.request(XrealOneProtocol.MSG_GET_INPUT_MODE, XrealOneProtocol.getBody()).isSuccess)
        }
        assertTrue("written on ${writers.single().name}", writers.single() != Thread.currentThread())
    }

    @Test
    fun `requests off the allowlist are never sent`() {
        val transport = GatedTransport()
        XrealOneControl(transport, { _, _ -> }, {}).use { control ->
            control.start()
            transport.open()
            val result = control.request(0x2834, XrealOneProtocol.setBody(0))
            assertTrue(result.isFailure)
        }
        assertTrue(transport.written.isEmpty())
    }

    @Test
    fun `tracking still connects without the control port`() {
        connect(GatedTransport(), control = null).use { device ->
            assertEquals(null, device.display)
            assertEquals(null, device.calibration)
        }
    }
}
