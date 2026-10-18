package dev.halone.desktop.compositor

import dev.halone.glasses.fusion.DisplayClock
import dev.halone.glasses.fusion.HeadPose
import dev.halone.glasses.fusion.HeadTracker
import dev.halone.glasses.math.Quaternion
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Where a frame will land, and the poses to draw and warp it with.
 *
 * On macOS the glasses' frames are presented exactly on their refresh grid, and
 * a frame reaches the display a fixed number of refreshes after the one it was
 * sampled in. Both are measured here from the presentation engine's reports,
 * rather than tuned, and the photon time is then converted to the glasses'
 * clock to aim the tracker at, the way StereoCompositor aims at its own
 * measured photon on Android.
 */
class FrameAim(private val clock: DisplayClock, private val tracker: HeadTracker) {
    /** The glasses light a row this long after the refresh that carries it; StereoCompositor's tuned value. */
    var scanoutOffsetMs = 5f

    /**
     * Scales the span between the first and last rows' poses, as
     * StereoCompositor.scanoutCompensation does: 1 assumes the panel scans a
     * whole refresh top row first, 0 turns the shear correction off, and
     * negative values invert it. The display does not say which is right.
     */
    var scanoutCompensation = 1f

    private val ahead = ArrayDeque<Int>()
    private var anchorHostNs = 0L
    private var measuredPeriodNs = 0.0
    private var phaseCos = 0.0
    private var phaseSin = 0.0

    /**
     * Set where nothing reports present times (RADV on Wayland): frames are then
     * aimed at the glasses' own refresh grid, this many refreshes after the one
     * following the sample, instead of at the measured one. On macOS presents
     * land exactly on that grid, so only the count is unknown.
     */
    var clockGridAhead: Int? = null

    private val periodNs: Double get() = if (clockGridAhead != null) clock.periodNs else measuredPeriodNs

    val ready: Boolean get() = clock.ready && (clockGridAhead != null || (anchorHostNs != 0L && measuredPeriodNs > 0))

    fun onPresented(sampleNs: Long, actualNs: Long, refreshNs: Long) {
        if (actualNs <= 0 || refreshNs <= 0) return
        measuredPeriodNs = refreshNs.toDouble()
        anchorHostNs = actualNs
        val angle = 2 * PI * Math.floorMod(sampleNs - anchorHostNs, refreshNs) / measuredPeriodNs
        phaseCos += (cos(angle) - phaseCos) * PHASE_SMOOTHING
        phaseSin += (sin(angle) - phaseSin) * PHASE_SMOOTHING
        val k = ((actualNs - referenceVsync(sampleNs)) / measuredPeriodNs).roundToInt()
        ahead.addLast(k)
        if (ahead.size > HISTORY) ahead.removeFirst()
    }

    /** Whole refreshes between the one after sampling and the one the frame is shown on; the median of recent frames. */
    val refreshesAhead: Int get() = clockGridAhead ?: ahead.sorted().let { if (it.isEmpty()) 1 else it[it.size / 2] }

    /**
     * The refresh a sample counts from: the grid line at the samples' usual phase. A loop
     * woken by the flip samples right at a refresh, where the next one after it flips
     * with sub-millisecond jitter; rounding from the usual phase keeps the count steady.
     */
    private fun referenceVsync(sampleNs: Long): Long {
        val usualNs = atan2(phaseSin, phaseCos) / (2 * PI) * measuredPeriodNs
        return anchorHostNs + (((sampleNs - usualNs - anchorHostNs) / measuredPeriodNs).roundToLong() * measuredPeriodNs).toLong()
    }

    /** Host time at which a frame sampled at [sampleNs] should reach the display. */
    fun photonHostNs(sampleNs: Long): Long {
        clockGridAhead?.let { count ->
            val offset = clock.deviceTimeAt(sampleNs) - sampleNs
            return clock.refreshAfter(clock.deviceTimeAt(sampleNs), 1 + count) - offset
        }
        return referenceVsync(sampleNs) + (refreshesAhead * measuredPeriodNs).toLong()
    }

    private fun firstRowDeviceNs(photonHostNs: Long): Long =
        clock.deviceTimeAt(photonHostNs) + (scanoutOffsetMs * 1e6).toLong()

    /** The pose to draw at: mid-scanout, so the warp has half a refresh to reach either way. */
    fun renderPose(photonHostNs: Long): HeadPose {
        val midScanout = firstRowDeviceNs(photonHostNs) + (periodNs * scanoutCompensation / 2).toLong()
        return tracker.poseAfterMs((midScanout - tracker.pose.timestampNs) / 1e6f)
    }

    /** Poses for the first and last rows, from the newest sample there is; sampled as late as the frame allows. */
    fun warpPoses(photonHostNs: Long): Pair<Quaternion, Quaternion> {
        val latest = tracker.pose
        val firstMs = (firstRowDeviceNs(photonHostNs) - latest.timestampNs) / 1e6f
        val lastMs = firstMs + (periodNs / 1e6).toFloat() * scanoutCompensation
        return tracker.poseAfterMs(firstMs).orientation to tracker.poseAfterMs(lastMs).orientation
    }

    private companion object {
        const val HISTORY = 120
        const val PHASE_SMOOTHING = 0.05
    }
}
