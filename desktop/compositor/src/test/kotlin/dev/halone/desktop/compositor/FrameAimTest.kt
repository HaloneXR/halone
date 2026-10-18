package dev.halone.desktop.compositor

import dev.halone.glasses.api.ClockSample
import dev.halone.glasses.api.ScanoutEvent
import dev.halone.glasses.api.UsbIdentity
import dev.halone.glasses.fusion.DisplayClock
import dev.halone.glasses.fusion.HeadTracker
import dev.halone.glasses.registry.GlassesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAimTest {
    private val period = 16_666_667L
    private val offset = 500_000_000L
    private var hostNow = 2_000_000_000L
    private val clock = DisplayClock { hostNow }

    private fun aim(): FrameAim {
        val optics = GlassesRegistry.default().probe(UsbIdentity(vendorId = 0x3318, productId = 0x0432))!!.match.optics
        val beacon = 1_000_000_000L
        clock.onBeacon(ScanoutEvent(sequence = 0, deviceTimeNs = beacon, periodNs = period, hostTimeNs = beacon - offset))
        clock.onBeacon(ScanoutEvent(sequence = 60, deviceTimeNs = beacon + 60 * period, periodNs = period, hostTimeNs = beacon + 60 * period - offset))
        clock.onClockSample(ClockSample(offsetNs = offset, roundTripNs = 1_000_000))
        return FrameAim(clock, HeadTracker(optics))
    }

    @Test
    fun `without present reports, photons land on the glasses' refresh grid`() {
        val aim = aim()
        aim.clockGridAhead = 2
        assertTrue("clock should be ready", aim.ready)
        for (sample in listOf(hostNow, hostNow + 3_000_000, hostNow + 16_000_000)) {
            val photon = aim.photonHostNs(sample)
            val phase = clock.phaseAt(clock.deviceTimeAt(photon))
            assertTrue("phase $phase should be a refresh boundary", phase < 1e-3f || phase > 1 - 1e-3f)
            val nextBoundary = clock.refreshAfter(clock.deviceTimeAt(sample)) - offset
            assertEquals(2 * period.toDouble(), (photon - nextBoundary).toDouble(), 2.0)
        }
    }

    @Test
    fun `the count is how many refreshes after the next one`() {
        val aim = aim()
        aim.clockGridAhead = 1
        val one = aim.photonHostNs(hostNow)
        aim.clockGridAhead = 3
        val three = aim.photonHostNs(hostNow)
        assertEquals(2 * period.toDouble(), (three - one).toDouble(), 2.0)
    }

    @Test
    fun `not ready until a present is reported, when aiming from presents`() {
        val aim = aim()
        assertTrue(!aim.ready)
        aim.onPresented(sampleNs = hostNow, actualNs = hostNow + 2 * period, refreshNs = period)
        assertTrue(aim.ready)
    }

    @Test
    fun `samples jittering across a refresh still aim at the refresh they land on`() {
        val aim = aim()
        val jitter = listOf(-300_000L, 200_000L, -100_000L, 400_000L, -250_000L, 50_000L)
        var vsync = hostNow
        repeat(240) { i ->
            vsync += period
            val sample = vsync + jitter[i % jitter.size]
            val actual = vsync + period
            if (i >= 120) assertEquals(actual.toDouble(), aim.photonHostNs(sample).toDouble(), 1_000.0)
            aim.onPresented(sampleNs = sample, actualNs = actual, refreshNs = period)
        }
    }
}
