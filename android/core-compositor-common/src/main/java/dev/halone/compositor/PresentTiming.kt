package dev.halone.compositor

import kotlin.math.sqrt

/** What the driver reports about one swapped frame, in `System.nanoTime` ns. */
data class FrameTimes(
    val renderingCompleteNs: Long,
    val latchNs: Long,
    val presentNs: Long,
) {
    val pending: Boolean
        get() = renderingCompleteNs == PENDING || latchNs == PENDING || presentNs == PENDING

    companion object {
        const val PENDING = -2L
        const val INVALID = -1L
    }
}

class RunningStats {
    var count = 0L
        private set
    var mean = 0.0
        private set
    var min = Double.MAX_VALUE
        private set
    var max = -Double.MAX_VALUE
        private set
    private var m2 = 0.0

    val sd: Double get() = if (count > 1) sqrt(m2 / (count - 1)) else 0.0

    fun add(x: Double) {
        count++
        val delta = x - mean
        mean += delta / count
        m2 += delta * (x - mean)
        if (x < min) min = x
        if (x > max) max = x
    }
}

/**
 * One frame that landed well away from where frames usually land, with what
 * the loop saw on the way. Times are ms from sampling the pose.
 */
data class Hitch(
    val drawNs: Long,
    val aimErrorMs: Double,
    val renderingCompleteMs: Double,
    val latchMs: Double,
    val presentMs: Double,
    val waitedMs: Double,
    val latchSeenLateMs: Double,
)

/**
 * Pairs each frame we drew with when the display actually showed it.
 *
 * The driver only knows a frame's present time a few refreshes after the swap,
 * so frames wait in [pending] until they are old enough to ask about. A frame
 * the display never showed is [dropped]; one the driver has already forgotten
 * is [expired], which means we asked too late rather than anything about the
 * pipeline.
 */
class PresentTiming(
    private val resolveAfterFrames: Int = 8,
    private val capacity: Int = 64,
    private val hitchThresholdMs: Double = 8.0,
    private val hitchesKept: Int = 20,
) {
    private class Submitted(
        val frameId: Long,
        val drawNs: Long,
        val aimedNs: Long?,
        val waitedNs: Long,
        val latchSeenLateNs: Long,
    )

    private val pending = ArrayDeque<Submitted>()

    var presented = 0L
        private set
    var dropped = 0L
        private set
    var expired = 0L
        private set

    /** From sampling the pose to the frame being put on the display, in ms. */
    var drawToPresent = RunningStats()
        private set
    var drawToLatch = RunningStats()
        private set

    /** Present time minus the refresh the frame was aimed at, in ms. A slip moves it by a whole refresh. */
    var aimError = RunningStats()
        private set

    /** Newest last. Only frames after the aim error has a mean to stand out from. */
    val hitches = ArrayDeque<Hitch>()

    fun submitted(
        frameId: Long,
        drawNs: Long,
        aimedNs: Long?,
        waitedNs: Long = 0,
        latchSeenLateNs: Long = 0,
    ) {
        pending.addLast(Submitted(frameId, drawNs, aimedNs, waitedNs, latchSeenLateNs))
        while (pending.size > capacity) {
            pending.removeFirst()
            expired++
        }
    }

    fun resolve(query: (Long) -> FrameTimes?) {
        while (pending.size > resolveAfterFrames) {
            val frame = pending.first()
            val times = query(frame.frameId)
            if (times == null) {
                pending.removeFirst()
                expired++
                continue
            }
            if (times.pending) return
            pending.removeFirst()
            if (times.presentNs <= 0) {
                dropped++
                continue
            }
            presented++
            drawToPresent.add((times.presentNs - frame.drawNs) / 1e6)
            if (times.latchNs > 0) drawToLatch.add((times.latchNs - frame.drawNs) / 1e6)
            frame.aimedNs?.let { aimed ->
                val error = (times.presentNs - aimed) / 1e6
                if (aimError.count >= HITCH_BASELINE_FRAMES &&
                    Math.abs(error - aimError.mean) > hitchThresholdMs
                ) {
                    recordHitch(frame, times, error)
                }
                aimError.add(error)
            }
        }
    }

    private fun recordHitch(frame: Submitted, times: FrameTimes, aimErrorMs: Double) {
        fun sinceDraw(ns: Long) = if (ns > 0) (ns - frame.drawNs) / 1e6 else Double.NaN
        hitches.addLast(
            Hitch(
                drawNs = frame.drawNs,
                aimErrorMs = aimErrorMs,
                renderingCompleteMs = sinceDraw(times.renderingCompleteNs),
                latchMs = sinceDraw(times.latchNs),
                presentMs = sinceDraw(times.presentNs),
                waitedMs = frame.waitedNs / 1e6,
                latchSeenLateMs = frame.latchSeenLateNs / 1e6,
            )
        )
        while (hitches.size > hitchesKept) hitches.removeFirst()
    }

    fun reset() {
        hitches.clear()
        presented = 0
        dropped = 0
        expired = 0
        drawToPresent = RunningStats()
        drawToLatch = RunningStats()
        aimError = RunningStats()
    }

    private companion object {
        const val HITCH_BASELINE_FRAMES = 30L
    }
}
