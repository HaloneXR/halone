package dev.halone.compositor

import org.junit.Assert.assertEquals
import org.junit.Test

class PresentTimingTest {

    private val period = 16_666_667L

    private fun presentedAt(presentNs: Long) = FrameTimes(presentNs - 20_000_000, presentNs - 30_000_000, presentNs)

    @Test
    fun `a frame is only asked about once it is old enough`() {
        val timing = PresentTiming(resolveAfterFrames = 2)
        val asked = mutableListOf<Long>()
        timing.submitted(1, 0, null)
        timing.submitted(2, period, null)
        timing.resolve { asked += it; presentedAt(4 * period) }
        assertEquals(emptyList<Long>(), asked)

        timing.submitted(3, 2 * period, null)
        timing.resolve { asked += it; presentedAt(4 * period) }
        assertEquals(listOf(1L), asked)
    }

    @Test
    fun `draw to present is measured from the pose sample`() {
        val timing = PresentTiming(resolveAfterFrames = 0)
        timing.submitted(1, 1_000_000_000L, null)
        timing.resolve { presentedAt(1_000_000_000L + 4 * period) }
        assertEquals(1L, timing.presented)
        assertEquals(4 * period / 1e6, timing.drawToPresent.mean, 1e-6)
        assertEquals(4 * period / 1e6 - 30.0, timing.drawToLatch.mean, 1e-6)
    }

    @Test
    fun `a slip onto the next refresh shows as a whole refresh of aim error`() {
        val timing = PresentTiming(resolveAfterFrames = 0)
        timing.submitted(1, 0, aimedNs = 100 * period)
        timing.resolve { presentedAt(100 * period) }
        timing.submitted(2, 0, aimedNs = 101 * period)
        timing.resolve { presentedAt(102 * period) }
        assertEquals(0.0, timing.aimError.min, 1e-6)
        assertEquals(period / 1e6, timing.aimError.max, 1e-6)
    }

    @Test
    fun `a pending frame is asked again rather than counted`() {
        val timing = PresentTiming(resolveAfterFrames = 0)
        timing.submitted(1, 0, null)
        timing.resolve { FrameTimes(FrameTimes.PENDING, FrameTimes.PENDING, FrameTimes.PENDING) }
        assertEquals(0L, timing.presented)
        timing.resolve { presentedAt(4 * period) }
        assertEquals(1L, timing.presented)
    }

    @Test
    fun `never shown is dropped, forgotten is expired`() {
        val timing = PresentTiming(resolveAfterFrames = 0)
        timing.submitted(1, 0, null)
        timing.submitted(2, 0, null)
        timing.resolve { id -> if (id == 1L) FrameTimes(10, 20, FrameTimes.INVALID) else null }
        assertEquals(1L, timing.dropped)
        assertEquals(1L, timing.expired)
        assertEquals(0L, timing.presented)
    }

    @Test
    fun `a frame far from the usual aim error is kept as a hitch with what the loop saw`() {
        val timing = PresentTiming(resolveAfterFrames = 0)
        for (i in 0 until 40L) {
            timing.submitted(i, i * period, aimedNs = i * period + 50_000_000)
            timing.resolve { presentedAt(i * period + 50_000_000) }
        }
        assertEquals(0, timing.hitches.size)

        timing.submitted(40, 40 * period, aimedNs = 40 * period + 50_000_000,
            waitedNs = 3_000_000, latchSeenLateNs = 9_000_000)
        timing.resolve { presentedAt(40 * period + 50_000_000 + period) }
        val hitch = timing.hitches.single()
        assertEquals(period / 1e6, hitch.aimErrorMs, 1e-6)
        assertEquals(3.0, hitch.waitedMs, 1e-9)
        assertEquals(9.0, hitch.latchSeenLateMs, 1e-9)
        assertEquals((50_000_000 + period) / 1e6, hitch.presentMs, 1e-6)
    }
}
