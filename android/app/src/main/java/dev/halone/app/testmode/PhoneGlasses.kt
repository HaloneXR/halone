package dev.halone.app.testmode

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import dev.halone.glasses.api.BrightnessControl
import dev.halone.glasses.api.DeviceEventListener
import dev.halone.glasses.api.DeviceInfo
import dev.halone.glasses.api.DisplayControl
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.Optics
import dev.halone.glasses.api.TrackingSample
import dev.halone.glasses.api.TrackingSink
import dev.halone.glasses.api.TrackingSource
import dev.halone.glasses.math.Vec3

/**
 * The phone standing in for glasses, for the hidden test mode: its own gyroscope
 * and accelerometer are the IMU, and [PhoneDisplay] is the screen.
 *
 * Android's sensor frame held upright in portrait, screen toward the viewer,
 * is already the canonical one, so samples pass through unmapped.
 */
class PhoneGlasses(context: Context) : GlassesDevice {
    override val info = DeviceInfo(vendor = "Halone", model = "Test Mode", driverId = "phone:test")

    override val optics = Optics(
        diagonalFovDegrees = DIAGONAL_FOV_DEGREES,
        perEyeWidth = PhoneDisplay.WIDTH,
        perEyeHeight = PhoneDisplay.HEIGHT,
        imuRateHz = IMU_RATE_HZ,
        scanoutCompensation = 0f,
    )

    override val tracking = PhoneImu(context)
    override val display: DisplayControl? = null
    override val brightness: BrightnessControl? = null
    override val displayNameHints: List<String> = emptyList()

    override fun addEventListener(listener: DeviceEventListener) = Unit
    override fun removeEventListener(listener: DeviceEventListener) = Unit

    override fun close() = tracking.stop()

    class PhoneImu(context: Context) : TrackingSource, SensorEventListener {
        private val sensors = context.getSystemService(SensorManager::class.java)
        private val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private var thread: HandlerThread? = null
        private var sink: TrackingSink? = null
        private var accel = Vec3(0f, SensorManager.GRAVITY_EARTH, 0f)

        override val providesOrientation = false
        override val providesPosition = false

        val available: Boolean get() = gyroscope != null && accelerometer != null

        override fun start(sink: TrackingSink) {
            stop()
            this.sink = sink
            val reader = HandlerThread("phone-imu").also { it.start() }
            thread = reader
            val handler = Handler(reader.looper)
            sensors.registerListener(this, accelerometer, SAMPLING_PERIOD_US, handler)
            sensors.registerListener(this, gyroscope, SAMPLING_PERIOD_US, handler)
        }

        override fun stop() {
            sensors.unregisterListener(this)
            thread?.quitSafely()
            thread = null
            sink = null
        }

        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> accel = Vec3(x, y, z)
                Sensor.TYPE_GYROSCOPE -> sink?.onSample(TrackingSample.Inertial(event.timestamp, Vec3(x, y, z), accel))
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

        private companion object {
            /** 200 Hz, the most Android 12 allows without `HIGH_SAMPLING_RATE_SENSORS`. */
            const val SAMPLING_PERIOD_US = 1_000_000 / IMU_RATE_HZ
        }
    }

    companion object {
        private const val DIAGONAL_FOV_DEGREES = 50f
        const val IMU_RATE_HZ = 200
    }
}
