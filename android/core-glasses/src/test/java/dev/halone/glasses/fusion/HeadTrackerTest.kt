package dev.halone.glasses.fusion

import dev.halone.glasses.api.Optics
import dev.halone.glasses.api.TrackingSample
import dev.halone.glasses.math.Quaternion
import dev.halone.glasses.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OrientationFilterTest {

    private fun inertial(t: Long, gyro: Vec3, accel: Vec3 = Vec3(0f, 9.81f, 0f)) =
        TrackingSample.Inertial(timestampNs = t, gyro = gyro, accel = accel)

    @Test
    fun `stays at identity when still`() {
        val f = OrientationFilter()
        var t = 0L
        repeat(100) { t += 1_000_000L; f.update(inertial(t, Vec3.ZERO)) }

        val (yaw, pitch, roll) = f.orientation.toEulerDegrees()
        assertEquals(0f, yaw, 0.5f)
        assertEquals(0f, pitch, 0.5f)
        assertEquals(0f, roll, 0.5f)
    }

    @Test
    fun `integrates a constant yaw rate`() {
        val f = OrientationFilter(accelWeight = 0f)
        val rate = Math.toRadians(90.0).toFloat()
        var t = 0L
        repeat(1000) { t += 1_000_000L; f.update(inertial(t, Vec3(0f, rate, 0f))) }

        assertEquals(90f, f.orientation.toEulerDegrees().first, 1.5f)
    }

    @Test
    fun `first sample only establishes the time base`() {
        val f = OrientationFilter()
        f.update(inertial(999_999_999L, Vec3(10f, 10f, 10f)))
        assertEquals(Quaternion.IDENTITY, f.orientation)
    }

    @Test
    fun `implausible time gap is ignored rather than flinging the view`() {
        val f = OrientationFilter(accelWeight = 0f)
        f.update(inertial(1_000_000L, Vec3.ZERO))
        f.update(inertial(10_000_000_000L, Vec3(0f, 1f, 0f)))
        assertEquals(Quaternion.IDENTITY, f.orientation)
    }

    @Test
    fun `bias is subtracted during integration`() {
        val f = OrientationFilter(accelWeight = 0f)
        val rate = Math.toRadians(90.0).toFloat()
        f.gyroBias = Vec3(0f, rate, 0f)
        var t = 0L
        repeat(1000) { t += 1_000_000L; f.update(inertial(t, Vec3(0f, rate, 0f))) }

        assertEquals(0f, f.orientation.toEulerDegrees().first, 0.1f)
    }

    @Test
    fun `gravity correction pulls a drifted estimate back`() {
        val f = OrientationFilter(accelWeight = 0.2f)
        var t = 0L
        repeat(200) {
            t += 1_000_000L
            f.update(inertial(t, Vec3(Math.toRadians(50.0).toFloat(), 0f, 0f), Vec3(0f, 9.81f, 0f)))
        }
        val drifted = abs(f.orientation.toEulerDegrees().second)
        repeat(3000) { t += 1_000_000L; f.update(inertial(t, Vec3.ZERO, Vec3(0f, 9.81f, 0f))) }
        val settled = abs(f.orientation.toEulerDegrees().second)

        assertTrue("drifted=$drifted settled=$settled", settled < drifted)
    }

    /**
     * Under hard acceleration the accelerometer no longer measures gravity, so
     * feeding it into the correction would tilt the world. Both filters start
     * from the same deliberately-tilted estimate; only the one given a plausible
     * gravity reading should be pulled back toward level.
     */
    /**
     * The bug this guards: a head turn swings the glasses about the neck, so the
     * accelerometer picks up sideways acceleration that is only a few percent of
     * gravity in magnitude — passing any magnitude check — but several degrees off
     * in direction. Believing it turns a yaw movement into visible roll.
     */
    @Test
    fun `sideways acceleration during a turn does not roll the horizon`() {
        val turning = OrientationFilter(accelWeight = 0.2f)
        var t = 0L
        val yawRate = Math.toRadians(200.0).toFloat()

        // Gravity plus 1.5 m/s^2 sideways: what a 200 deg/s turn on a 12 cm
        // radius actually produces. Magnitude 9.93, direction off by 8.7 deg.
        repeat(500) {
            t += 1_000_000L
            turning.update(inertial(t, Vec3(0f, yawRate, 0f), Vec3(1.5f, 9.81f, 0f)))
        }

        val roll = abs(turning.orientation.toEulerDegrees().third)
        assertTrue("a turn must not roll the horizon, got $roll deg", roll < 1.5f)
    }

    @Test
    fun `the same tilted reading is trusted when the device is still`() {
        val still = OrientationFilter(accelWeight = 0.2f)
        var t = 0L
        // Identical accelerometer reading, but no rotation: now it is genuine
        // gravity and the filter should follow it.
        repeat(2000) {
            t += 1_000_000L
            still.update(inertial(t, Vec3.ZERO, Vec3(1.5f, 9.81f, 0f)))
        }

        val roll = abs(still.orientation.toEulerDegrees().third)
        assertTrue("a still device should adopt the measured gravity, got $roll deg", roll > 5f)
    }

/**
     * With compensation on, the accelerometer should survive a turn intact — the
     * point being to keep gravity usable rather than to stop believing it.
     */
    @Test
    fun `rotation compensation keeps the horizon level through a sustained turn`() {
        val f = OrientationFilter(accelWeight = 0.02f)
        f.pivotOffset = Vec3(0f, 0.08f, -0.10f)
        f.trustNoneAbove = 100f          // deliberately trust it throughout

        val yawRate = Math.toRadians(200.0).toFloat()
        val w = Vec3(0f, yawRate, 0f)
        val r = Vec3(0f, 0.08f, -0.10f)
        // What the sensor genuinely feels: gravity plus the swing about the neck.
        val centripetal = w cross (w cross r)
        val measured = Vec3(centripetal.x, 9.81f + centripetal.y, centripetal.z)

        var t = 0L
        repeat(1500) { t += 1_000_000L; f.update(inertial(t, w, measured)) }

        val (_, pitch, roll) = f.orientation.toEulerDegrees()
        assertTrue("roll should stay level, got $roll", abs(roll) < 1.5f)
        assertTrue("pitch should stay level, got $pitch", abs(pitch) < 2.5f)
    }

    /** Without compensation the same input tilts the estimate: the bug it fixes. */
    @Test
    fun `without compensation the same turn tilts the estimate`() {
        val f = OrientationFilter(accelWeight = 0.02f)
        f.pivotOffset = null
        f.trustNoneAbove = 100f

        val yawRate = Math.toRadians(200.0).toFloat()
        val w = Vec3(0f, yawRate, 0f)
        val r = Vec3(0f, 0.08f, -0.10f)
        val centripetal = w cross (w cross r)
        val measured = Vec3(centripetal.x, 9.81f + centripetal.y, centripetal.z)

        var t = 0L
        repeat(1500) { t += 1_000_000L; f.update(inertial(t, w, measured)) }

        val tilt = abs(f.orientation.toEulerDegrees().second) +
            abs(f.orientation.toEulerDegrees().third)
        assertTrue("uncompensated turn should visibly tilt, got $tilt deg", tilt > 2f)
    }

    @Test
    fun `compensation leaves a stationary reading untouched`() {
        val f = OrientationFilter(accelWeight = 0.02f)
        f.pivotOffset = Vec3(0f, 0.08f, -0.10f)

        var t = 0L
        repeat(2000) { t += 1_000_000L; f.update(inertial(t, Vec3.ZERO, Vec3(0f, 9.81f, 0f))) }

        val (yaw, pitch, roll) = f.orientation.toEulerDegrees()
        assertEquals(0f, pitch, 0.5f)
        assertEquals(0f, roll, 0.5f)
        assertEquals(0f, yaw, 0.5f)
    }

    @Test
    fun `implausible accelerometer magnitude is not used for correction`() {
        val shaken = OrientationFilter(accelWeight = 0.5f)
        val trusted = OrientationFilter(accelWeight = 0.5f)
        val tilt = Math.toRadians(30.0).toFloat()

        // Build a tilt while the accelerometer reads out-of-band. That the tilt
        // survives at all is the first half of the assertion: a trusted reading
        // here would have cancelled it as fast as the gyro produced it.
        var t = 0L
        repeat(100) {
            t += 1_000_000L
            shaken.update(inertial(t, Vec3(tilt, 0f, 0f), Vec3(0f, 40f, 0f)))
            trusted.update(inertial(t, Vec3(tilt, 0f, 0f), Vec3(0f, 40f, 0f)))
        }
        val tiltedPitch = abs(shaken.orientation.toEulerDegrees().second)
        assertTrue(
            "an out-of-band reading should not have cancelled the gyro tilt, got $tiltedPitch",
            tiltedPitch > 1f,
        )

        repeat(1000) {
            t += 1_000_000L
            // 40 m/s^2 is far outside the gravity band and must be ignored.
            shaken.update(inertial(t, Vec3.ZERO, Vec3(0f, 40f, 0f)))
            trusted.update(inertial(t, Vec3.ZERO, Vec3(0f, 9.81f, 0f)))
        }

        assertEquals(
            "an implausible reading must leave the estimate untouched",
            tiltedPitch,
            abs(shaken.orientation.toEulerDegrees().second),
            0.01f,
        )
        assertTrue(
            "a plausible reading should have corrected the tilt away",
            abs(trusted.orientation.toEulerDegrees().second) < tiltedPitch / 2f,
        )
    }
}

class GyroBiasEstimatorTest {

    private fun inertial(gyro: Vec3) =
        TrackingSample.Inertial(timestampNs = 0, gyro = gyro, accel = Vec3(0f, 9.81f, 0f))

    @Test
    fun `averages the samples it is given`() {
        val e = GyroBiasEstimator(target = 100)
        repeat(100) { e.add(inertial(Vec3(0.01f, -0.02f, 0.03f))) }

        assertTrue(e.done)
        assertEquals(0.01f, e.bias().x, 1e-5f)
        assertEquals(-0.02f, e.bias().y, 1e-5f)
        assertEquals(0.03f, e.bias().z, 1e-5f)
    }

    @Test
    fun `reports zero before any samples`() {
        assertEquals(Vec3.ZERO, GyroBiasEstimator().bias())
    }

    @Test
    fun `ignores samples once complete`() {
        val e = GyroBiasEstimator(target = 10)
        repeat(10) { e.add(inertial(Vec3(1f, 1f, 1f))) }
        repeat(100) { e.add(inertial(Vec3(50f, 50f, 50f))) }
        assertEquals(1f, e.bias().x, 1e-5f)
    }
}

class StillnessDetectorTest {

    private fun inertial(gyro: Vec3) =
        TrackingSample.Inertial(timestampNs = 0, gyro = gyro, accel = Vec3(0f, 9.81f, 0f))

    @Test
    fun `reports still after enough quiet samples`() {
        val d = StillnessDetector(requiredSamples = 10)
        repeat(10) { d.add(inertial(Vec3(0.001f, 0f, 0f))) }
        assertTrue(d.isStill)
    }

    @Test
    fun `motion resets the run`() {
        val d = StillnessDetector(requiredSamples = 10)
        repeat(9) { d.add(inertial(Vec3.ZERO)) }
        d.add(inertial(Vec3(5f, 0f, 0f)))
        assertTrue(!d.isStill)
    }

    /**
     * The Air 2 Pro reads 10.14 m/s^2 at rest. Measured against nominal gravity
     * that is permanent "acceleration", so the device was never still, gyro bias
     * was never calibrated, and yaw drifted untreated. Found by wearing it.
     */
    @Test
    fun `an accelerometer scale error does not read as motion forever`() {
        val d = StillnessDetector(requiredSamples = 10)
        val readsHigh = TrackingSample.Inertial(
            timestampNs = 0, gyro = Vec3(0.001f, 0f, 0f), accel = Vec3(0f, 10.14f, 0f),
        )

        repeat(10) { d.add(readsHigh) }

        assertTrue("a 3.4% scale error is not motion", d.isStill)
    }

    @Test
    fun `real acceleration still resets the run on a device that reads high`() {
        val d = StillnessDetector(requiredSamples = 10)
        fun sample(magnitude: Float) = TrackingSample.Inertial(
            timestampNs = 0, gyro = Vec3.ZERO, accel = Vec3(0f, magnitude, 0f),
        )

        repeat(50) { d.add(sample(10.14f)) }
        assertTrue(d.isStill)

        // A shove, relative to what this device reads while resting.
        d.add(sample(10.14f + 1.0f))
        assertTrue("motion is still motion", !d.isStill)
    }

    @Test
    fun `free fall is not stillness`() {
        val d = StillnessDetector(requiredSamples = 10)
        val falling = TrackingSample.Inertial(
            timestampNs = 0, gyro = Vec3.ZERO, accel = Vec3(0f, 0.2f, 0f),
        )

        repeat(50) { d.add(falling) }

        assertTrue(!d.isStill)
    }
}

class HeadTrackerTest {

    private val optics = Optics(
        diagonalFovDegrees = 46f,
        perEyeWidth = 1920,
        perEyeHeight = 1080,
        imuRateHz = 1000,
    )

    private fun inertial(t: Long, gyro: Vec3, accel: Vec3 = Vec3(0f, 9.81f, 0f)) =
        TrackingSample.Inertial(timestampNs = t, gyro = gyro, accel = accel)

    @Test
    fun `passes an on-device orientation straight through`() {
        val tracker = HeadTracker(optics)
        val q = Quaternion.fromPitchDegrees(30f)
        tracker.onSample(TrackingSample.Oriented(timestampNs = 1, orientation = q))

        assertEquals(q.x, tracker.pose.orientation.x, 1e-5f)
        assertEquals(q.w, tracker.pose.orientation.w, 1e-5f)
    }

    @Test
    fun `counts samples from both sources`() {
        val tracker = HeadTracker(optics)
        tracker.onSample(inertial(1_000_000L, Vec3.ZERO))
        tracker.onSample(TrackingSample.Oriented(2_000_000L, Quaternion.IDENTITY))
        assertEquals(2L, tracker.pose.sampleCount)
    }

    @Test
    fun `calibrates bias automatically once the device is still`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = 0L
        repeat(600) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, 0.01f, 0f))) }
        assertTrue("bias should have been calibrated after 600 still samples", tracker.biasCalibrated)
    }

    /**
     * Bias moves as the hardware warms up, so a startup-only estimate goes
     * stale and the stale part integrates straight into yaw drift.
     */
    @Test
    fun `bias tracking follows a drifting gyro offset`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = 0L

        val initialBias = 0.01f
        repeat(600) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, initialBias, 0f))) }
        assertTrue(tracker.biasCalibrated)
        assertEquals(initialBias, tracker.gyroBias.y, 1e-3f)

        // The hardware warms and the offset moves; still-and-drifting is exactly
        // the case a one-shot estimate cannot handle.
        val warmedBias = 0.02f
        repeat(20_000) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, warmedBias, 0f))) }

        assertEquals("bias should have followed the drift", warmedBias, tracker.gyroBias.y, 2e-3f)
    }

    @Test
    fun `bias tracking does not absorb real head motion`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = 0L
        repeat(600) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3.ZERO)) }
        val calibrated = tracker.gyroBias

        // A real turn is far above the stillness threshold, so it must not be
        // mistaken for bias — otherwise turning your head slowly would teach the
        // filter that "turning" is the new zero.
        val turn = Math.toRadians(30.0).toFloat()
        repeat(5_000) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, turn, 0f))) }

        assertEquals("bias must be untouched by motion", calibrated.y, tracker.gyroBias.y, 1e-4f)
    }

    @Test
    fun `yaw drift is reduced once bias is tracked`() {
        val bias = Vec3(0f, 0.008f, 0f)
        val uncorrected = OrientationFilter(accelWeight = 0f)
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))

        var t = 0L
        repeat(30_000) {
            t += 1_000_000L
            val sample = inertial(t, bias)
            uncorrected.update(sample)
            tracker.onSample(sample)
        }

        val uncorrectedYaw = abs(uncorrected.orientation.toEulerDegrees().first)
        val trackedYaw = abs(tracker.pose.orientation.toEulerDegrees().first)
        assertTrue(
            "tracked yaw drift ($trackedYaw) should be well under uncorrected ($uncorrectedYaw)",
            trackedYaw < uncorrectedYaw / 4f,
        )
    }

    @Test
    fun `does not calibrate bias while the device is moving`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = 0L
        repeat(600) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, 2f, 0f))) }
        assertTrue("a moving device must not be mistaken for a bias reading", !tracker.biasCalibrated)
    }

    @Test
    fun `recentre makes the current heading forward`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        val rate = Math.toRadians(90.0).toFloat()
        var t = 0L
        repeat(1000) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, rate, 0f))) }

        tracker.recenter()
        tracker.onSample(inertial(t + 1_000_000L, Vec3.ZERO))

        val (yaw, pitch, roll) = tracker.pose.orientation.toEulerDegrees()
        assertEquals(0f, yaw, 0.5f)
        assertEquals(0f, pitch, 0.5f)
        assertEquals(0f, roll, 0.5f)
    }

    /**
     * The failure this guards against is visible and horrible: recentring while
     * tilted would bake that tilt in as level, leaving the whole world crooked.
     */
    @Test
    fun `recentre cancels heading but leaves the horizon level`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        val yawRate = Math.toRadians(60.0).toFloat()
        val rollRate = Math.toRadians(20.0).toFloat()

        var t = 0L
        repeat(1000) {
            t += 1_000_000L
            tracker.onSample(inertial(t, Vec3(0f, yawRate, rollRate)))
        }
        val tiltedRoll = tracker.pose.orientation.toEulerDegrees().third
        assertTrue("setup should have produced roll, got $tiltedRoll", abs(tiltedRoll) > 5f)

        tracker.recenter()
        tracker.onSample(inertial(t + 1_000_000L, Vec3.ZERO))

        val (yaw, _, roll) = tracker.pose.orientation.toEulerDegrees()
        // Not exactly zero: extracting the yaw twist about +Y and then measuring
        // heading via a Y-X-Z Euler decomposition do not commute once there is
        // roll in the pose. A couple of degrees left over from 60 is the
        // decomposition disagreeing with itself, not the heading surviving.
        assertTrue("heading should be substantially cancelled, got $yaw", abs(yaw) < 2f)
        assertEquals("roll must survive recentring", tiltedRoll, roll, 1.5f)
    }

    /**
     * The phone pointer lives in the world frame too. Worn: look left, recentre,
     * and the pointer sat as far left of where the phone pointed as the head had
     * turned, because only the head was told the world had moved.
     */
    @Test
    fun `recentre reports the turn that keeps world directions where they were`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        val forward = Vec3(0f, 0f, -1f)
        var t = 0L
        for (rate in listOf(40.0, -65.0)) {
            repeat(1000) {
                t += 1_000_000L
                tracker.onSample(inertial(t, Vec3(0f, Math.toRadians(rate).toFloat(), 0f)))
            }
            val facing = tracker.pose.orientation.rotate(forward)
            val turned = tracker.recenter().rotate(facing)
            assertEquals(0f, turned.x, 1e-3f)
            assertEquals(0f, turned.y, 1e-3f)
            assertEquals(-1f, turned.z, 1e-3f)
        }
    }

    @Test
    fun `recentre from level leaves the pose at identity`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        val yawRate = Math.toRadians(90.0).toFloat()
        var t = 0L
        repeat(1000) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, yawRate, 0f))) }

        tracker.recenter()
        tracker.onSample(inertial(t + 1_000_000L, Vec3.ZERO))

        val (yaw, pitch, roll) = tracker.pose.orientation.toEulerDegrees()
        assertEquals(0f, yaw, 0.5f)
        assertEquals(0f, pitch, 0.5f)
        assertEquals(0f, roll, 0.5f)
    }

    @Test
    fun `prediction advances the pose along the angular velocity`() {
        val predicting = optics.copy(
            predictionConstantMs = 20f,
            predictionFrametimeMultiplier = 0f,
        )
        val tracker = HeadTracker(predicting, OrientationFilter(accelWeight = 0f))
        val rate = Math.toRadians(90.0).toFloat()
        var t = 0L
        repeat(100) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, rate, 0f))) }

        val now = tracker.pose.orientation.toEulerDegrees().first
        val ahead = tracker.predictedPose(frametimeMs = 0f).orientation.toEulerDegrees().first

        // 20ms at 90 deg/s is 1.8 degrees of lead.
        assertEquals(1.8f, ahead - now, 0.3f)
    }

    @Test
    fun `prediction is a no-op when the device declares no latency`() {
        val tracker = HeadTracker(optics)
        tracker.onSample(inertial(1_000_000L, Vec3.ZERO))
        assertEquals(tracker.pose.orientation, tracker.predictedPose(16.6f).orientation)
    }

    @Test
    fun `imu mounting pitch is corrected out`() {
        val tilted = optics.copy(imuPitchOffsetDegrees = 10f)
        val tracker = HeadTracker(tilted, OrientationFilter(accelWeight = 0f))
        tracker.onSample(TrackingSample.Oriented(1L, Quaternion.fromPitchDegrees(10f)))

        val (_, pitch, _) = tracker.pose.orientation.toEulerDegrees()
        assertEquals("a 10 deg mount offset should cancel a 10 deg reading", 0f, pitch, 0.5f)
    }

    /**
     * The rest hold: small jitter with the head still never reaches the
     * published pose, and a real turn is followed at once with no added lag.
     */
    @Test
    fun `rest hold absorbs jitter at rest and releases on a real turn`() {
        val tracker = HeadTracker(optics.copy(restDeadbandDegrees = 0.5f), OrientationFilter(accelWeight = 0f))
        tracker.velocitySmoothingMs = 0f
        fun yaw(degrees: Float): Quaternion {
            val half = Math.toRadians(degrees / 2.0)
            return Quaternion(kotlin.math.cos(half).toFloat(), 0f, kotlin.math.sin(half).toFloat(), 0f)
        }
        var t = 0L
        tracker.onSample(TrackingSample.Oriented(t, yaw(0f), Vec3.ZERO))
        val rest = tracker.pose.orientation
        // Jitter of ±0.3°, slow: held.
        for (jitter in listOf(0.3f, -0.2f, 0.25f, -0.3f)) {
            t += 2_000_000L
            tracker.onSample(TrackingSample.Oriented(t, yaw(jitter), Vec3(0f, 0.01f, 0f)))
            assertEquals("held through jitter", rest, tracker.pose.orientation)
        }
        // A real turn at 60°/s: passed straight through.
        t += 2_000_000L
        val turnRate = Math.toRadians(60.0).toFloat()
        tracker.onSample(TrackingSample.Oriented(t, yaw(5f), Vec3(0f, turnRate, 0f)))
        val (turned, _, _) = tracker.pose.orientation.toEulerDegrees()
        assertEquals(5f, turned, 0.05f)
        assertEquals(turnRate, tracker.pose.angularVelocity.y, 1e-4f)
    }

    /**
     * The mounting is in the glasses' frame, so it must stay corrected after a
     * turn. Composed on the world side it only held at the starting heading: a
     * 29° board pitch (RayNeo GT Max) turned into roll as the wearer panned.
     */
    @Test
    fun `imu mounting pitch stays corrected after turning`() {
        val tilted = optics.copy(imuPitchOffsetDegrees = 29f)
        val tracker = HeadTracker(tilted, OrientationFilter(accelWeight = 0f))
        for (yawDegrees in listOf(0f, 45f, 90f, -120f)) {
            val half = Math.toRadians(yawDegrees / 2.0)
            val yaw = Quaternion(kotlin.math.cos(half).toFloat(), 0f, kotlin.math.sin(half).toFloat(), 0f)
            // Head level and turned; the board reads 29° nose-up in its own frame.
            tracker.onSample(TrackingSample.Oriented(1L, yaw * Quaternion.fromPitchDegrees(29f)))

            val (turned, pitch, roll) = tracker.pose.orientation.toEulerDegrees()
            assertEquals("pitch at $yawDegrees°", 0f, pitch, 0.5f)
            assertEquals("roll at $yawDegrees°", 0f, roll, 0.5f)
            assertEquals("yaw at $yawDegrees°", yawDegrees, turned, 0.5f)
        }
    }

    /**
     * The rate the compositor extrapolates along must be the corrected one. The
     * filter subtracts bias before integrating; publishing the raw sample put it
     * straight back into prediction and the scanout correction.
     */
    @Test
    fun `published angular velocity has the gyro bias removed`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        // Bias removal, not the filter that follows it.
        tracker.velocitySmoothingMs = 0f
        var t = 0L
        val bias = 0.02f
        repeat(600) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, bias, 0f))) }
        assertTrue(tracker.biasCalibrated)

        // Still: the corrected rate is zero even though the gyro reads the bias.
        assertEquals(0f, tracker.pose.angularVelocity.y, 1e-3f)

        val turn = 1.0f
        t += 1_000_000L
        tracker.onSample(inertial(t, Vec3(0f, turn + bias, 0f)))
        assertEquals(turn, tracker.pose.angularVelocity.y, 1e-3f)
    }

    @Test
    fun `zero smoothing publishes the newest rate as it arrives`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        tracker.velocitySmoothingMs = 0f

        tracker.onSample(inertial(1_000_000L, Vec3(0f, 2f, 0f)))
        assertEquals(2f, tracker.pose.angularVelocity.y, 1e-4f)
        tracker.onSample(inertial(2_000_000L, Vec3(0f, -2f, 0f)))
        assertEquals(-2f, tracker.pose.angularVelocity.y, 1e-4f)
    }

    /** A real turn lasts many time constants, so it must survive intact. */
    @Test
    fun `smoothing settles on a sustained turn`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        tracker.velocitySmoothingMs = 30f

        var t = 0L
        val rate = 2f
        repeat(200) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, rate, 0f))) }

        assertEquals(rate, tracker.pose.angularVelocity.y, 0.02f)
    }

    /**
     * Tremor is the motion prediction cannot help with: by the time the horizon
     * arrives it has reversed. The rate estimate has to not believe it.
     */
    @Test
    fun `smoothing attenuates tremor while a table stays still`() {
        val tremorHz = 10.0
        val amplitude = 1.0f

        fun peakRate(smoothingMs: Float): Float {
            val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
            tracker.velocitySmoothingMs = smoothingMs
            var t = 0L
            var peak = 0f
            // Two full tremor cycles at 1 kHz, measuring the last one so the
            // filter is past its start-up transient.
            repeat(200) { i ->
                t += 1_000_000L
                val rate = amplitude * kotlin.math.sin(2.0 * Math.PI * tremorHz * i / 1000.0).toFloat()
                tracker.onSample(inertial(t, Vec3(0f, rate, 0f)))
                if (i > 100) peak = maxOf(peak, kotlin.math.abs(tracker.pose.angularVelocity.y))
            }
            return peak
        }

        val raw = peakRate(0f)
        val smoothed = peakRate(30f)
        assertEquals("unsmoothed follows the tremor", amplitude, raw, 0.02f)
        assertTrue(
            "30 ms should cut a 10 Hz tremor to well under half, was $smoothed",
            smoothed < amplitude * 0.5f,
        )
    }

    /** A stalled stream must not leave a stale rate to extrapolate along. */
    @Test
    fun `a gap in the stream restarts the rate estimate`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        tracker.velocitySmoothingMs = 30f

        var t = 0L
        repeat(200) { t += 1_000_000L; tracker.onSample(inertial(t, Vec3(0f, 2f, 0f))) }
        assertEquals(2f, tracker.pose.angularVelocity.y, 0.02f)

        t += 500_000_000L
        tracker.onSample(inertial(t, Vec3.ZERO))
        assertEquals(0f, tracker.pose.angularVelocity.y, 1e-4f)
    }

    private fun turn(tracker: HeadTracker, start: Long, gyro: Vec3, ms: Int = 1000): Long {
        var t = start
        repeat(ms) { t += 1_000_000L; tracker.onSample(inertial(t, gyro)) }
        return t
    }

    private fun degreesPerSecond(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) =
        Vec3(Math.toRadians(x).toFloat(), Math.toRadians(y).toFloat(), Math.toRadians(z).toFloat())

    private fun settle(tracker: HeadTracker, t: Long) = tracker.onSample(inertial(t + 1_000_000L, Vec3.ZERO))

    @Test
    fun `bed mode makes looking at the ceiling forward`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = turn(tracker, 0L, degreesPerSecond(y = 50.0))
        t = turn(tracker, t, degreesPerSecond(x = 90.0))

        tracker.enterBedMode()
        settle(tracker, t)

        assertTrue(tracker.inBedMode)
        val (yaw, pitch, roll) = tracker.pose.orientation.toEulerDegrees()
        assertEquals(0f, yaw, 0.5f)
        assertEquals(0f, pitch, 0.5f)
        assertEquals(0f, roll, 0.5f)
    }

    @Test
    fun `in bed mode a gaze short of the ceiling stays as pitch`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        val t = turn(tracker, 0L, degreesPerSecond(x = 70.0))

        tracker.enterBedMode()
        settle(tracker, t)

        val (yaw, pitch, _) = tracker.pose.orientation.toEulerDegrees()
        assertEquals(0f, yaw, 0.5f)
        assertEquals(-20f, pitch, 0.5f)
    }

    @Test
    fun `with a pillow a lower elevation becomes forward`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        val t = turn(tracker, 0L, degreesPerSecond(x = 70.0))

        tracker.enterBedMode(forwardElevationDegrees = 70f)
        settle(tracker, t)

        val (yaw, pitch, roll) = tracker.pose.orientation.toEulerDegrees()
        assertEquals(0f, yaw, 0.5f)
        assertEquals(0f, pitch, 0.5f)
        assertEquals(0f, roll, 0.5f)
    }

    @Test
    fun `in bed mode turning the head on the pillow is yaw`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = turn(tracker, 0L, degreesPerSecond(x = 90.0))
        tracker.enterBedMode()
        t = turn(tracker, t, degreesPerSecond(y = 30.0))
        settle(tracker, t)

        val (yaw, pitch, _) = tracker.pose.orientation.toEulerDegrees()
        assertEquals(30f, yaw, 0.5f)
        assertEquals(0f, pitch, 0.5f)
    }

    @Test
    fun `entering bed mode reports the turn that keeps world directions where they were`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = turn(tracker, 0L, degreesPerSecond(y = -35.0))
        t = turn(tracker, t, degreesPerSecond(x = 80.0))
        settle(tracker, t)
        val forward = Vec3(0f, 0f, -1f)
        val facing = tracker.pose.orientation.rotate(forward)

        val turned = tracker.enterBedMode().rotate(facing)
        settle(tracker, t + 1_000_000L)
        val nowFacing = tracker.pose.orientation.rotate(forward)

        assertEquals(nowFacing.x, turned.x, 1e-3f)
        assertEquals(nowFacing.y, turned.y, 1e-3f)
        assertEquals(nowFacing.z, turned.z, 1e-3f)
    }

    @Test
    fun `recentring in bed mode stays in bed mode`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = turn(tracker, 0L, degreesPerSecond(x = 90.0))
        tracker.enterBedMode()
        t = turn(tracker, t, degreesPerSecond(y = 40.0))

        tracker.recenter()
        settle(tracker, t)

        assertTrue(tracker.inBedMode)
        val (yaw, pitch, _) = tracker.pose.orientation.toEulerDegrees()
        assertEquals(0f, yaw, 0.5f)
        assertEquals(0f, pitch, 0.5f)
    }

    @Test
    fun `recentring in bed mode levels roll too`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = turn(tracker, 0L, degreesPerSecond(x = 90.0))
        tracker.enterBedMode()
        t = turn(tracker, t, degreesPerSecond(z = 25.0))
        t = turn(tracker, t, degreesPerSecond(x = -10.0))

        tracker.recenter()
        settle(tracker, t)

        val (yaw, pitch, roll) = tracker.pose.orientation.toEulerDegrees()
        assertEquals(0f, yaw, 0.5f)
        assertEquals(0f, pitch, 0.5f)
        assertEquals(0f, roll, 0.5f)
    }

    @Test
    fun `bed mode relearns the magnetic reference on the way in and out`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = 0L
        fun field() {
            t += 1_000_000L
            tracker.onSample(TrackingSample.Inertial(t, Vec3.ZERO, Vec3(0f, 9.81f, 0f), magnetRaw = Vec3(300f, -200f, 0f)))
        }
        repeat(2) { field() }
        assertTrue(tracker.magnetometer.anchored)

        tracker.enterBedMode()
        assertTrue(!tracker.magnetometer.anchored)
        repeat(2) { field() }
        tracker.exitBedMode()
        assertTrue(!tracker.magnetometer.anchored)
    }

    @Test
    fun `leaving bed mode measures against gravity again`() {
        val tracker = HeadTracker(optics, OrientationFilter(accelWeight = 0f))
        var t = turn(tracker, 0L, degreesPerSecond(x = 90.0))
        tracker.enterBedMode()
        t = turn(tracker, t, degreesPerSecond(x = -90.0))

        tracker.exitBedMode()
        settle(tracker, t)

        assertTrue(!tracker.inBedMode)
        val (yaw, pitch, roll) = tracker.pose.orientation.toEulerDegrees()
        assertEquals(0f, yaw, 0.5f)
        assertEquals(0f, pitch, 0.5f)
        assertEquals(0f, roll, 0.5f)
    }
}
