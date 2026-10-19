package dev.halone.glasses.fusion

import dev.halone.glasses.api.ClockSample
import dev.halone.glasses.api.ScanoutEvent
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * When the display will next refresh, on the clock the IMU stamps its samples
 * with.
 *
 * A compositor that predicts has to answer "how far ahead?", and answering it
 * with a constant — however carefully measured — leaves the phase unknown: a
 * frame submitted just after a refresh waits a whole period longer than one
 * submitted just before, and nothing in the pipeline says which happened. That
 * is up to a full frame of prediction error that no amount of tuning removes,
 * because it is not a bias, it is a sawtooth.
 *
 * Two measurements fix it, and the glasses supply both:
 *
 * - a **timing beacon**, carrying the device-clock time of a refresh. It does
 *   not arrive every frame — on an Air 2 Pro it is one beacon per 256 — but a
 *   refresh grid needs only phase and period, and a long baseline measures the
 *   period far better than consecutive frames would.
 * - a **clock round trip**, which ties the device's clock to this one. The IMU
 *   already stamps on the device clock, so this is needed only to place *now*
 *   on that grid.
 *
 * Thread model: beacons arrive on a reader thread, clock samples from whatever
 * paces them, and the render thread reads. State is published as one immutable
 * snapshot so a reader never sees half an update.
 */
class DisplayClock(
    private val nowNs: () -> Long = System::nanoTime,
) {
    private data class State(
        val firstBeaconDeviceNs: Long = 0,
        val firstBeaconSequence: Long = 0,
        val lastBeaconDeviceNs: Long = 0,
        val lastBeaconSequence: Long = 0,
        val reportedPeriodNs: Long = 0,
        val refreshesPerBeacon: Int = 0,
        val beacons: Int = 0,
        val offsetNs: Long = 0,
        val offsetRoundTripNs: Long = Long.MAX_VALUE,
        val offsetAtNs: Long = 0,
        val clockSamples: Int = 0,
    ) {
        /**
         * Fitted across the whole run when there is a baseline to fit across,
         * because 256 refreshes between beacons turns a microsecond-quantised
         * timestamp into nanosecond-resolution period.
         */
        val periodNs: Double
            get() {
                val spanBeacons = lastBeaconSequence - firstBeaconSequence
                if (spanBeacons <= 0 || refreshesPerBeacon <= 0) return reportedPeriodNs.toDouble()
                val refreshes = spanBeacons * refreshesPerBeacon
                return (lastBeaconDeviceNs - firstBeaconDeviceNs).toDouble() / refreshes
            }
    }

    private val state = AtomicReference(State())

    val hasTiming: Boolean get() = state.get().beacons >= MIN_BEACONS
    val hasClock: Boolean get() = state.get().clockSamples > 0

    /** True once both halves are in and the grid can be projected forward. */
    val ready: Boolean get() = hasTiming && hasClock

    /** Forgets everything, for glasses that were reconnected and count their beacons and clock afresh. */
    fun reset() = state.set(State())

    val periodNs: Double get() = state.get().periodNs
    val reportedPeriodNs: Long get() = state.get().reportedPeriodNs
    val refreshesPerBeacon: Int get() = state.get().refreshesPerBeacon
    val beacons: Int get() = state.get().beacons
    val offsetNs: Long get() = state.get().offsetNs
    val offsetRoundTripNs: Long get() = state.get().offsetRoundTripNs
    val clockSamples: Int get() = state.get().clockSamples

    /** Nanoseconds since the last beacon; large means the display went quiet. */
    val beaconAgeNs: Long
        get() = state.get().let { if (it.beacons == 0) Long.MAX_VALUE else deviceNow() - it.lastBeaconDeviceNs }

    fun onBeacon(event: ScanoutEvent) {
        while (true) {
            val old = state.get()
            val restart = old.beacons == 0 ||
                event.deviceTimeNs < old.lastBeaconDeviceNs ||
                periodChanged(old.reportedPeriodNs, event.periodNs)
            val new = if (restart) {
                // First beacon, the device restarted its clock underneath us, or
                // the refresh rate changed and the old baseline would blend both.
                State(
                    firstBeaconDeviceNs = event.deviceTimeNs,
                    firstBeaconSequence = event.sequence,
                    lastBeaconDeviceNs = event.deviceTimeNs,
                    lastBeaconSequence = event.sequence,
                    reportedPeriodNs = event.periodNs,
                    beacons = 1,
                    offsetNs = old.offsetNs,
                    offsetRoundTripNs = old.offsetRoundTripNs,
                    offsetAtNs = old.offsetAtNs,
                    clockSamples = old.clockSamples,
                )
            } else {
                val steps = event.sequence - old.lastBeaconSequence
                val perBeacon = if (steps > 0 && event.periodNs > 0) {
                    Math.round(
                        (event.deviceTimeNs - old.lastBeaconDeviceNs).toDouble() /
                            steps / event.periodNs
                    ).toInt()
                } else {
                    old.refreshesPerBeacon
                }
                old.copy(
                    lastBeaconDeviceNs = event.deviceTimeNs,
                    lastBeaconSequence = event.sequence,
                    reportedPeriodNs = event.periodNs,
                    refreshesPerBeacon = if (perBeacon > 0) perBeacon else old.refreshesPerBeacon,
                    beacons = old.beacons + 1,
                )
            }
            if (state.compareAndSet(old, new)) return
        }
    }

    /**
     * Keeps the sample with the shortest round trip, because the offset is only
     * known to within half of it — a quiet exchange is worth more than a recent
     * one. An old best is let go once it is older than [OFFSET_TRUST_NS], since
     * two crystals drift apart and a stale offset is confidently wrong.
     */
    fun onClockSample(sample: ClockSample) {
        if (sample.roundTripNs <= 0 || sample.roundTripNs > MAX_ROUND_TRIP_NS) return
        val at = nowNs()
        while (true) {
            val old = state.get()
            val stale = at - old.offsetAtNs > OFFSET_TRUST_NS
            val better = sample.roundTripNs < old.offsetRoundTripNs
            val new = if (old.clockSamples == 0 || stale || better) {
                old.copy(
                    offsetNs = sample.offsetNs,
                    offsetRoundTripNs = sample.roundTripNs,
                    offsetAtNs = at,
                    clockSamples = old.clockSamples + 1,
                )
            } else {
                old.copy(clockSamples = old.clockSamples + 1)
            }
            if (state.compareAndSet(old, new)) return
        }
    }

    private fun periodChanged(oldNs: Long, newNs: Long): Boolean =
        oldNs > 0 && newNs > 0 && abs(newNs - oldNs) > oldNs * PERIOD_CHANGE_FRACTION

    fun deviceTimeAt(hostTimeNs: Long): Long = hostTimeNs + state.get().offsetNs

    fun deviceNow(): Long = deviceTimeAt(nowNs())

    /**
     * The device time of the [count]-th refresh at or after [deviceTimeNs].
     *
     * `count = 1` is the next one. Projecting the grid from a beacon that may
     * be seconds old is the whole point: the phase is exact and only the period
     * estimate accumulates error, which over a few seconds is nanoseconds.
     */
    fun refreshAfter(deviceTimeNs: Long, count: Int = 1): Long {
        val snapshot = state.get()
        if (snapshot.beacons == 0) return deviceTimeNs
        val period = snapshot.periodNs
        if (period <= 0) return deviceTimeNs
        val elapsed = (deviceTimeNs - snapshot.lastBeaconDeviceNs).toDouble()
        val next = ceil(elapsed / period) + (count - 1)
        return snapshot.lastBeaconDeviceNs + Math.round(next * period)
    }

    /**
     * How far ahead of [fromDeviceNs] the [count]-th coming refresh is, in
     * milliseconds — the horizon a predictor should use, measured rather than
     * assumed.
     *
     * Raw, so it steps by a whole refresh whenever the caller's own timing
     * crosses a boundary. A caller that runs every frame wants [RefreshPhase]
     * around it; one that asks occasionally does not.
     */
    fun horizonMsTo(fromDeviceNs: Long, count: Int): Float =
        (refreshAfter(deviceNow(), count) - fromDeviceNs) / 1e6f

    /**
     * Where [deviceTimeNs] sits inside the refresh interval it falls in, as a
     * fraction of a period. 0 is a refresh boundary.
     */
    fun phaseAt(deviceTimeNs: Long): Float {
        val snapshot = state.get()
        if (snapshot.beacons == 0 || snapshot.periodNs <= 0) return 0f
        val periods = (deviceTimeNs - snapshot.lastBeaconDeviceNs) / snapshot.periodNs
        return (periods - floor(periods)).toFloat()
    }

    fun describe(): String = state.get().let {
        if (it.beacons == 0) return "display clock: no beacons yet"
        buildString {
            append("display clock: %.4f Hz (%.6f ms)".format(1e9 / it.periodNs, it.periodNs / 1e6))
            append(", %d beacons of %d refreshes".format(it.beacons, it.refreshesPerBeacon))
            append(", reported %.6f ms".format(it.reportedPeriodNs / 1e6))
            if (it.clockSamples > 0) {
                append("\noffset %d ns from %d samples, best round trip %.2f ms".format(
                    it.offsetNs, it.clockSamples, it.offsetRoundTripNs / 1e6))
            } else {
                append("\nno clock samples — device time is not tied to ours")
            }
        }
    }

    private companion object {
        /** One beacon gives phase; a second is what gives a period. */
        const val MIN_BEACONS = 2

        /** Beyond this the exchange was queued behind something and says little. */
        const val MAX_ROUND_TRIP_NS = 20_000_000L

        /** Two crystals a few tens of ppm apart drift a millisecond in this. */
        const val OFFSET_TRUST_NS = 30_000_000_000L

        const val PERIOD_CHANGE_FRACTION = 0.01
    }
}


/**
 * Locks onto where a repeating job sits inside the refresh cycle.
 *
 * A compositor that asks "when is the next refresh?" once per frame gets an
 * answer that steps by a whole period the moment its own timing crosses a
 * boundary. Some of those steps are real — a renderer and a panel running on
 * different crystals drift through the cycle, and the frame genuinely starts
 * landing one refresh later. Most are not: a millisecond of scheduling jitter
 * either side of the boundary flips the answer back and forth, and a horizon
 * that alternates by 16.7 ms is worse than the constant it replaced.
 *
 * Measured on an Air 2 Pro before this existed: the horizon ranged over
 * **17.67 ms**, slightly more than one refresh, across twelve seconds of
 * otherwise steady 60 fps.
 *
 * Telling the two apart needs only the observation that real drift is slow. So
 * the phase is smoothed, the shortest way round the circle, and a crossing has
 * to persist to take effect. What comes out still steps by a full refresh when
 * the frame really does land later — it just does it once.
 */
class RefreshPhase(private val smoothing: Float = DEFAULT_SMOOTHING) {

    /** Smoothed position in the cycle, 0 at a refresh boundary. NaN until fed. */
    var fraction = Float.NaN
        private set

    /** How often the smoothed phase has wrapped: real drift, not jitter. */
    var crossings = 0L
        private set

    fun reset() {
        fraction = Float.NaN
        crossings = 0
    }

    fun update(raw: Float): Float {
        if (fraction.isNaN()) {
            fraction = raw
            return fraction
        }
        // The shortest way round, so 0.99 → 0.01 reads as a small step forward
        // rather than a large step back.
        var delta = raw - fraction
        if (delta > 0.5f) delta -= 1f
        if (delta < -0.5f) delta += 1f

        val moved = fraction + smoothing * delta
        fraction = when {
            moved >= 1f -> { crossings++; moved - 1f }
            moved < 0f -> { crossings++; moved + 1f }
            else -> moved
        }
        return fraction
    }

    private companion object {
        /** About a twenty-frame time constant: slower than jitter, faster than drift. */
        const val DEFAULT_SMOOTHING = 0.05f
    }
}
