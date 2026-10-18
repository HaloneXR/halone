package dev.halone.desktop.compositor

import dev.halone.desktop.compositor.vk.PresentRecord
import dev.halone.glasses.fusion.DisplayClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Summarises what the presentation engine reports, and how it lines up with the glasses' own refresh. */
class TimingProbe(private val clock: DisplayClock?) {
    private val latencies = ArrayList<Double>()
    private val poseToPresent = ArrayList<Double>()
    private val sampled = HashMap<Long, Long>()
    private val aimed = HashMap<Long, Long>()
    private val aimErrors = ArrayList<Double>()

    /** Frame [id] was drawn for a photon at [photonHostNs]. */
    fun aimed(id: Long, photonHostNs: Long) {
        aimed[id] = photonHostNs
        if (aimed.size > 512) aimed.keys.sorted().take(256).forEach { aimed.remove(it) }
    }

    /** Frame [id] took its head pose at [sampleNs]. */
    fun sampled(id: Long, sampleNs: Long) {
        sampled[id] = sampleNs
        if (sampled.size > 512) sampled.keys.sorted().take(256).forEach { sampled.remove(it) }
    }

    private val phases = ArrayList<Double>()
    private var presented = 0
    private var unreported = 0

    fun record(r: PresentRecord) {
        presented++
        if (r.actualNs <= 0) {
            unreported++
            return
        }
        latencies += (r.actualNs - r.submittedNs) / 1e6
        sampled.remove(r.id)?.let { poseToPresent += (r.actualNs - it) / 1e6 }
        aimed.remove(r.id)?.let { aimErrors += (r.actualNs - it) / 1e6 }
        clock?.takeIf { it.ready }?.let { phases += it.phaseAt(it.deviceTimeAt(r.actualNs)).toDouble() }
    }

    fun report(refreshNs: Long): String {
        val sorted = latencies.sorted()
        fun pct(p: Double) = if (sorted.isEmpty()) Double.NaN else sorted[minOf(sorted.size - 1, (sorted.size * p).toInt())]
        val phase = if (phases.isEmpty()) {
            if (presented == 0) "no present times to compare with the glasses' refresh" else "no glasses clock yet"
        } else {
            val c = phases.sumOf { cos(it * 2 * Math.PI) } / phases.size
            val s = phases.sumOf { sin(it * 2 * Math.PI) } / phases.size
            val mean = ((atan2(s, c) / (2 * Math.PI)) + 1) % 1
            "glasses phase at present %.3f (resultant %.3f, 1 = locked)".format(mean, sqrt(c * c + s * s))
        }
        val pose = poseToPresent.sorted()
        fun posePct(p: Double) = if (pose.isEmpty()) Double.NaN else pose[minOf(pose.size - 1, (pose.size * p).toInt())]
        val errors = aimErrors.map { kotlin.math.abs(it) }.sorted()
        val aim = if (errors.isEmpty()) "not aimed" else "aim error |p50| %.2f |p95| %.2f ms, %d off by a refresh or more".format(
            errors[errors.size / 2], errors[minOf(errors.size - 1, (errors.size * 0.95).toInt())], errors.count { it > 8.0 })
        val line = "timing: %s; %d presented, %d without a time, refresh %.3f ms, submit->present p50 %.2f p95 %.2f max %.2f ms, pose->present p50 %.2f p95 %.2f ms; %s%s".format(
            aim, presented, unreported, refreshNs / 1e6, pct(0.5), pct(0.95), pct(1.0), posePct(0.5), posePct(0.95), phase,
            clock?.let { if (it.ready) ", glasses period %.3f ms".format(it.periodNs / 1e6) else ", glasses clock not ready" } ?: "")
        latencies.clear()
        poseToPresent.clear()
        aimErrors.clear()
        phases.clear()
        presented = 0
        unreported = 0
        return line
    }
}
