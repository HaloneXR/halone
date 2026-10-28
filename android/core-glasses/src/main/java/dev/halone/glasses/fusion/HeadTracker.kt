package dev.halone.glasses.fusion

import dev.halone.glasses.api.DeviceCalibration
import dev.halone.glasses.api.Optics
import dev.halone.glasses.api.TrackingSample
import dev.halone.glasses.api.TrackingSink
import dev.halone.glasses.api.TrackingSource
import dev.halone.glasses.math.Quaternion
import dev.halone.glasses.math.Vec3
import java.util.concurrent.atomic.AtomicReference

data class HeadPose(
    val orientation: Quaternion,
    val angularVelocity: Vec3,
    val timestampNs: Long,
    val sampleCount: Long,
)

/**
 * Turns any [TrackingSource] into a head pose the compositor can read.
 *
 * This is the payoff of splitting [TrackingSample] into inertial and oriented
 * variants: bias estimation, recentring and forward prediction live here once,
 * and every driver inherits them. A device that fuses on-board skips only the
 * filter, not the rest.
 *
 * Thread model: [onSample] runs on the driver's reader thread at up to ~1kHz
 * and publishes through an [AtomicReference], so the render thread can call
 * [pose] or [predictedPose] without locking or blocking the reader.
 */
class HeadTracker(
    private val optics: Optics,
    private val filter: OrientationFilter = OrientationFilter(),
    /**
     * Per-unit factory calibration, when the glasses would give it up.
     *
     * Everything it supplies here was previously a constant guessed at
     * per-model: accelerometer bias was zero, gyro bias started at zero and was
     * measured over the first still minute, and the sensor was assumed square
     * to the displays.
     */
    val calibration: DeviceCalibration? = null,
) : TrackingSink {


    private var initialBias: Vec3 = Vec3.ZERO

    private val published = AtomicReference(
        HeadPose(Quaternion.IDENTITY, Vec3.ZERO, 0L, 0L)
    )
    private val biasEstimator = GyroBiasEstimator()
    private val stillness = StillnessDetector()

    /** Everything [publish] applies on top of the gravity-referenced pose: heading, then the bed frame. */
    @Volatile
    private var worldReference: Quaternion = Quaternion.IDENTITY

    @Volatile
    private var bedFrame: Quaternion? = null

    /**
     * Whether forward is the ceiling rather than the horizon, for a wearer
     * lying on their back.
     */
    val inBedMode: Boolean get() = bedFrame != null

    /** The newest orientation against gravity, with no recentre or bed frame applied. */
    @Volatile
    var gravityOrientation: Quaternion = Quaternion.IDENTITY
        private set

    /**
     * The head frame — the displays, which are what the wearer actually aims —
     * as seen from the IMU. Composed on the body side of the filter's
     * orientation: the IMU turns with the head, so a turn about the world's
     * vertical must stay one through any mount. Composing it on the world side
     * instead tips the horizon by up to the mount angle once the wearer looks
     * away from where they started, which a degree-sized mount hides and the
     * One Pro's ~35° does not (see `HeadTrackerMountTest`).
     *
     * Falls back to the per-model pitch guess when there is no calibration.
     * With one, it carries the real mounting rotation, which on this hardware
     * is about a degree of *roll* — a term the pitch-only guess could not
     * express at all, and which shows up as a horizon that is level to the
     * sensor and tilted to the eye.
     */
    @Volatile
    private var mountingCorrection: Quaternion =
        Quaternion.fromPitchDegrees(-optics.imuPitchOffsetDegrees)

    @Volatile
    var biasCalibrated: Boolean = false
        private set

    /** True once the factory bias has been loaded, before any measurement. */
    @Volatile
    var biasSeeded: Boolean = false
        private set

    /**
     * Whether there is a bias estimate good enough to render against.
     *
     * Waiting on [biasCalibrated] alone cost a session: stillness detection is
     * a heuristic, it can take a minute to latch, and until it did there was no
     * recentring and the panels sat wherever the wearer happened to be facing.
     * A seeded factory bias is available immediately and is good to a few
     * hundredths of a degree per second, so there is nothing to wait for.
     */
    val hasUsableBias: Boolean get() = biasSeeded || biasCalibrated

    /** What the factory measured, for comparison against what we measure. */
    @Volatile
    var factoryGyroBias: Vec3? = null
        private set

    private var samples = 0L

    private var smoothedRate: Vec3 = Vec3.ZERO
    private var lastRateNs = 0L

    val pose: HeadPose get() = published.get()

    /**
     * The newest rate, bias-corrected but **unsmoothed**.
     *
     * Anything measuring *when* motion began must read this rather than the
     * published pose. Smoothing delays the estimate by design, which is
     * harmless for prediction — it is the price of not over-extrapolating — but
     * poisonous for a latency rig: a trigger watching the smoothed rate fires
     * late and quietly inflates the motion-to-photon figure it exists to
     * measure, which then feeds straight back into the prediction horizon.
     */
    @Volatile
    var rawAngularVelocity: Vec3 = Vec3.ZERO
        private set

    // Last of the initialisers on purpose: Kotlin runs them in order, so state
    // declared below an init block resets whatever the block wrote to it.
    init {
        calibration?.imu?.let { imu ->
            filter.accelBias = imu.accelBias
            filter.magnetToBody = imu.gyroToMag
            // Seeded, not trusted. Gyro bias moves with temperature and with
            // the years since the factory measured it, so this is a starting
            // point that makes the first minute usable — not a substitute for
            // measuring it, which still happens and still takes over.
            filter.gyroBias = imu.gyroBias
            initialBias = imu.gyroBias
            factoryGyroBias = imu.gyroBias
            biasSeeded = true
        }
        calibration?.let { mountingCorrection = it.headFromImu }

        // On, because wearing it settled the question decisively.
        //
        // Worn, moving normally, measured against a real object by recentring
        // on it and looking back: over ten minutes, **~19° with it against the
        // ~99° the gyro alone reaches**. Short runs flatter it — two minutes
        // gives 0.58°/min, which does not extrapolate, because what this does
        // is bound the error rather than remove it. The
        // baseline puts a whole panel width between the wearer and the object
        // in two minutes, which is what it looks like when yaw has no reference
        // at all and the stillness detector never fires to re-measure bias.
        //
        // It costs something in the opposite regime: on a stationary desk with
        // bias estimation converged, yaw wandered 0.206°/min with it on against
        // 0.035°/min with it off, because the indoor field itself moves ~1.5°.
        // That trade is worth taking without hesitation — 0.2°/min is invisible
        // over a session and 10°/min is not.
        filter.magnetometer.enabled = MAGNETOMETER_ON_BY_DEFAULT
    }

    override fun onSample(sample: TrackingSample) {
        samples++
        when (sample) {
            is TrackingSample.Inertial -> {
                trackBias(sample)
                filter.update(sample)
                // Bias-corrected, because this rate is what prediction and the
                // scanout correction extrapolate along. The raw sample carries
                // the ~0.5 deg/s bias the filter is careful to subtract before
                // integrating, and handing it out unsubtracted quietly puts that
                // bias back into everything downstream.
                val rate = sample.gyro - filter.gyroBias
                rawAngularVelocity = rate
                publish(filter.orientation, smoothRate(rate, sample.timestampNs), sample.timestampNs)
            }

            is TrackingSample.Oriented -> {
                publish(sample.orientation, sample.angularVelocity ?: Vec3.ZERO, sample.timestampNs)
            }
        }
    }

    private fun publish(raw: Quaternion, angularVelocity: Vec3, timestampNs: Long) {
        val corrected = (raw * mountingCorrection).normalized()
        gravityOrientation = corrected
        // The rate was measured about the IMU's axes; prediction turns the head
        // about its own.
        val rate = mountingCorrection.conjugate().rotate(angularVelocity)
        val live = (worldReference * corrected).normalized()
        val held = stabilise(live, rate, timestampNs)
        published.set(
            HeadPose(
                orientation = held ?: live,
                // A held pose is not moving, so nothing may extrapolate it.
                angularVelocity = if (held != null) Vec3.ZERO else rate,
                timestampNs = timestampNs,
                sampleCount = samples,
            )
        )
    }

    /**
     * Degrees the published pose may hold still at rest; see
     * [dev.halone.glasses.api.Optics.restDeadbandDegrees]. Live-tunable.
     */
    @Volatile
    var restDeadbandDegrees: Float = optics.restDeadbandDegrees

    private var heldPose: Quaternion? = null
    private var lastStabiliseNs = 0L

    /** Set from any thread; the reader thread drops the hold on its next sample. */
    @Volatile
    private var holdReset = false

    /**
     * The rest hold: while the head turns slower than [REST_DEGREES_PER_SECOND]
     * and stays within [restDeadbandDegrees] of where the pose was held, the
     * held pose is published unchanged, so sensor noise at rest never reaches
     * the screen. A slow creep past the band is followed gently; any real
     * movement releases the hold at once and the live pose goes out untouched,
     * so turning carries no added lag.
     *
     * Returns the pose to publish instead of [live], or null to publish live.
     */
    private fun stabilise(live: Quaternion, rate: Vec3, timestampNs: Long): Quaternion? {
        val band = restDeadbandDegrees
        val dt = if (lastStabiliseNs == 0L) 0f else ((timestampNs - lastStabiliseNs).coerceAtLeast(0L) / 1e9f)
        lastStabiliseNs = timestampNs
        if (holdReset) {
            holdReset = false
            heldPose = null
        }
        if (band <= 0f) {
            heldPose = null
            return null
        }
        val speed = Math.toDegrees(rate.length.toDouble()).toFloat()
        val held = heldPose
        if (held == null || speed > REST_DEGREES_PER_SECOND) {
            heldPose = live
            return null
        }
        val apart = angleBetweenDegrees(held, live)
        if (apart <= band) return held
        // Drifting past the band at rest: close the excess gently, not in a step.
        val alpha = (1f - kotlin.math.exp(-dt / REST_CATCH_UP_SECONDS)).coerceIn(0f, 1f)
        val next = nlerp(held, live, alpha)
        heldPose = next
        return next
    }

    private fun angleBetweenDegrees(a: Quaternion, b: Quaternion): Float {
        val cosHalf = kotlin.math.abs(a.dot(b)).coerceAtMost(1f)
        return Math.toDegrees(2.0 * kotlin.math.acos(cosHalf.toDouble())).toFloat()
    }

    private fun nlerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
        val to = if (a.dot(b) < 0f) b.negated() else b
        return Quaternion(
            a.w + (to.w - a.w) * t,
            a.x + (to.x - a.x) * t,
            a.y + (to.y - a.y) * t,
            a.z + (to.z - a.z) * t,
        ).normalized()
    }

    /**
     * Makes the current heading the new forward, **without** touching pitch or
     * roll.
     *
     * Cancelling the whole orientation would also cancel the gravity-referenced
     * part of it, so whatever tilt the wearer happened to have when they
     * recentred becomes the new "level" and the horizon stays crooked for the
     * rest of the session. Only the yaw twist about the up axis is arbitrary and
     * safe to zero; pitch and roll are anchored to gravity and should be left
     * exactly where the filter puts them.
     *
     * In bed mode there is no horizon to keep, so the whole pose becomes
     * forward and upright, roll included.
     *
     * Returns the rotation by which the world frame turned, for anything else
     * that points in world coordinates and has to turn with it.
     */
    fun recenter(): Quaternion {
        if (bedFrame == null) return reframe(null)
        val previous = worldReference
        worldReference = currentGravityOrientation().conjugate()
        holdReset = true
        return (worldReference * previous.conjugate()).normalized()
    }

    /**
     * Turns the world so the direction the wearer faces lying on their back
     * becomes forward, and the top of their head becomes up. Pitch and roll
     * are then measured against the bed rather than gravity, which is what
     * lets the placement limits keep working unchanged.
     *
     * [forwardElevationDegrees] is the gaze elevation that becomes forward:
     * 90 is the ceiling, lying flat; a pillow tips the head toward the feet.
     *
     * Returns the world turn, as [recenter] does.
     */
    fun enterBedMode(forwardElevationDegrees: Float = 90f): Quaternion {
        // The measured field's error depends on head pose, so **[suspected]** a
        // reference learned upright rejects every reading taken lying down and
        // leaves the image to roll with gyro drift. Lying still holds one pose,
        // which is when a fresh reference works best.
        filter.magnetometer.reanchor()
        return reframe(lyingFrame(currentGravityOrientation(), forwardElevationDegrees).conjugate())
    }

    /** Back to gravity, recentred on the current heading. Returns the world turn. */
    fun exitBedMode(): Quaternion {
        filter.magnetometer.reanchor()
        return reframe(null)
    }

    private fun currentGravityOrientation() = (filter.orientation * mountingCorrection).normalized()

    private fun reframe(bed: Quaternion?): Quaternion {
        val tilt = bed ?: Quaternion.IDENTITY
        val current = (tilt * currentGravityOrientation()).normalized()
        val previous = worldReference
        bedFrame = bed
        worldReference = (yawOf(current).conjugate() * tilt).normalized()
        // A pose held in the old world frame means nothing in the new one.
        holdReset = true
        return (worldReference * previous.conjugate()).normalized()
    }

    /**
     * Keeps the gyro bias estimate current.
     *
     * An accelerometer anchors pitch and roll but says nothing about yaw —
     * rotation about the gravity vector is unobservable — so yaw error is
     * whatever the uncorrected gyro bias integrates to. A single estimate at
     * startup is not enough: bias moves as the hardware warms up, and the
     * measured 0.47 deg/s on this device is ~28 deg/min if left alone.
     *
     * So after the initial fix, keep refining whenever the device is detected
     * still, with a slow enough constant that genuine slow head motion is not
     * absorbed as bias. This cannot eliminate yaw drift — nothing short of a
     * heading reference can — but it takes it from obvious to tolerable between
     * recentres.
     */
    private fun trackBias(sample: TrackingSample.Inertial) {
        stillness.add(sample)
        if (!stillness.isStill) return

        if (!biasCalibrated) {
            biasEstimator.add(sample)
            if (biasEstimator.done) {
                val estimate = biasEstimator.bias()
                filter.gyroBias = estimate
                initialBias = estimate
                biasCalibrated = true
            }
            return
        }

        val bias = filter.gyroBias
        val anchor = initialBias
        // Clamped to a band around the first estimate. Stillness detection is a
        // heuristic and will occasionally admit slow real motion; without a limit
        // that motion is absorbed permanently and the error never comes back.
        filter.gyroBias = Vec3(
            clampNear(anchor.x, bias.x + (sample.gyro.x - bias.x) * BIAS_TRACKING_ALPHA),
            clampNear(anchor.y, bias.y + (sample.gyro.y - bias.y) * BIAS_TRACKING_ALPHA),
            clampNear(anchor.z, bias.z + (sample.gyro.z - bias.z) * BIAS_TRACKING_ALPHA),
        )
    }

    val gyroBias: Vec3 get() = filter.gyroBias

    /** Exposed so the levelling rate can be tuned while wearing the glasses. */
    var accelWeight: Float
        get() = filter.accelWeight
        set(value) { filter.accelWeight = value }

    /** Neck-pivot offset for rotation compensation; null disables it. */
    var pivotOffset: dev.halone.glasses.math.Vec3?
        get() = filter.pivotOffset
        set(value) { filter.pivotOffset = value }

    /** Rotation rate above which the accelerometer is ignored, rad/s. */
    var trustNoneAbove: Float
        get() = filter.trustNoneAbove
        set(value) { filter.trustNoneAbove = value }

    /** Exposed so a spike can watch the heading reference work. */
    val magnetometer get() = filter.magnetometer

    /** Accelerometer bias currently being subtracted, canonical frame. */
    var accelBias: Vec3
        get() = filter.accelBias
        set(value) { filter.accelBias = value }

    private fun clampNear(anchor: Float, value: Float) =
        value.coerceIn(anchor - BIAS_DRIFT_LIMIT, anchor + BIAS_DRIFT_LIMIT)

    /** Discards the bias estimate so it is measured again, e.g. after a long session. */
    fun recalibrateBias() {
        biasCalibrated = false
        biasEstimator.reset()
        stillness.reset()
    }

    /**
     * Overrides the prediction horizon in milliseconds; null uses the device's
     * configured constants.
     *
     * Exists because the measured latency is only good to about +/-25 ms, and the
     * penalty is asymmetric: too little prediction leaves lag, too much
     * overshoots and makes the world wobble. A number that can be changed while
     * wearing the glasses is worth more than a more precise one that cannot,
     * since the wearer can find the point where lag disappears before wobble
     * appears.
     */
    @Volatile
    var predictionOverrideMs: Float? = null

    /**
     * Time constant for smoothing the published angular velocity, in
     * milliseconds. Zero publishes the newest sample as it arrives.
     *
     * Prediction extrapolates along this rate at constant velocity for the whole
     * motion-to-photon horizon, which makes it a low-pass problem rather than a
     * noise problem: any motion that *reverses* faster than the horizon gets
     * confidently extrapolated in the direction it has already stopped going.
     * Physiological head tremor is exactly that — around 8-12 Hz, so a period
     * shorter than a 100 ms horizon — which is why the shake appears on a head
     * and not on a table.
     *
     * Smoothing the rate removes the tremor band from the prediction while
     * leaving a sustained turn intact: a real turn lasts many time constants, so
     * the estimate settles on it and the horizon is still covered.
     */
    @Volatile
    var velocitySmoothingMs: Float = DEFAULT_VELOCITY_SMOOTHING_MS

    /** The horizon currently in use, for diagnostics. */
    fun predictionHorizonMs(frametimeMs: Float): Float =
        predictionOverrideMs
            ?: (optics.predictionConstantMs + optics.predictionFrametimeMultiplier * frametimeMs)

    /**
     * Extrapolates the pose forward to cover motion-to-photon latency. Without
     * this, panels lag behind head motion and the world feels like it swims.
     */
    fun predictedPose(frametimeMs: Float): HeadPose {
        val horizonMs = predictionHorizonMs(frametimeMs)
        return if (horizonMs <= 0f) published.get() else poseAfterMs(horizonMs)
    }

    /**
     * The pose [horizonMs] after the newest sample, extrapolated at the current
     * rate. Negative horizons run the same extrapolation backwards.
     *
     * Separate from [predictedPose] because reprojection aims at a *photon*
     * rather than at a frame: it resamples later than the renderer did, so its
     * horizon is what is left of the same target, not the frame's own.
     */
    fun poseAfterMs(horizonMs: Float): HeadPose {
        val current = published.get()
        if (horizonMs == 0f) return current

        val dt = horizonMs / 1000f
        val w = current.angularVelocity
        val half = dt * 0.5f
        val delta = Quaternion(1f, w.x * half, w.y * half, w.z * half)
        return current.copy(orientation = (current.orientation * delta).normalized())
    }

    fun attachTo(source: TrackingSource) = source.start(this)

    /**
     * One-pole low pass on the angular rate. Reader thread only, so no
     * synchronisation beyond the volatile knob.
     */
    private fun smoothRate(rate: Vec3, timestampNs: Long): Vec3 {
        val tau = velocitySmoothingMs / 1000f
        val previousNs = lastRateNs
        lastRateNs = timestampNs

        if (tau <= 0f) {
            smoothedRate = rate
            return rate
        }

        val dt = if (previousNs == 0L) 0f else (timestampNs - previousNs) / 1_000_000_000f
        // A gap means the stream stalled; carrying an old estimate across it
        // would extrapolate along a rate from before the gap.
        if (dt <= 0f || dt > MAX_RATE_GAP_SECONDS) {
            smoothedRate = rate
            return rate
        }

        val alpha = (dt / tau).coerceAtMost(1f)
        val smoothed = smoothedRate + (rate - smoothedRate) * alpha
        smoothedRate = smoothed
        return smoothed
    }

    private companion object {
        /**
         * Chosen by a wearer over the `jitter` measurement, which favoured 50 ms.
         * In two blind `smoothab` rounds (0 / 15 / 50 ms) 50 ms ran on after fast
         * turns and was never picked; 0 ms won once but stuttered on slow turns.
         */
        const val DEFAULT_VELOCITY_SMOOTHING_MS = 15f

        /**
         * Below this the head counts as at rest for the hold. Above a GT
         * series' resting noise (~0.7 °/s) and well below a deliberate glance.
         */
        const val REST_DEGREES_PER_SECOND = 4f

        /** How quickly a held pose closes a creep past its band. */
        const val REST_CATCH_UP_SECONDS = 0.25f

        /** Beyond this the stream stalled, so the estimate restarts. */
        const val MAX_RATE_GAP_SECONDS = 0.1f

        /** See the note in `init`: ~99° without over ten minutes, ~19° with. */
        const val MAGNETOMETER_ON_BY_DEFAULT = true

        /**
         * Per-sample blend while still. At ~1kHz this is a time constant of a
         * couple of seconds — fast enough to follow thermal drift, slow enough
         * that a gentle real turn is not mistaken for bias.
         */
        const val BIAS_TRACKING_ALPHA = 0.0005f

        /** How far tracked bias may wander from the first estimate: ~0.6 deg/s. */
        const val BIAS_DRIFT_LIMIT = 0.01f

        /**
         * Facing [elevationDegrees] above the horizon with the top of the head
         * toward where [head]'s is. Only the heading of the head's up axis is
         * used, so how far the gaze is from that elevation stays as pitch.
         */
        fun lyingFrame(head: Quaternion, elevationDegrees: Float): Quaternion {
            val up = head.rotate(Vec3(0f, 1f, 0f))
            val back = head.rotate(Vec3(0f, 0f, 1f))
            val crown = if (up.x * up.x + up.z * up.z > 1e-4f) up else back
            val heading = kotlin.math.atan2(crown.x, crown.z)
            return (Quaternion.fromYawRadians(heading) * Quaternion.fromPitchDegrees(elevationDegrees)).normalized()
        }

        /**
         * The rotation about the canonical up axis (+Y) contained in [q]: the
         * "twist" component, obtained by dropping the axis terms that tilt away
         * from vertical and renormalising.
         */
        fun yawOf(q: Quaternion): Quaternion {
            val yaw = Quaternion(q.w, 0f, q.y, 0f)
            // Looking almost straight up or down leaves no meaningful heading.
            val magnitude = kotlin.math.sqrt(yaw.w * yaw.w + yaw.y * yaw.y)
            return if (magnitude < 1e-5f) Quaternion.IDENTITY else yaw.normalized()
        }
    }
}
