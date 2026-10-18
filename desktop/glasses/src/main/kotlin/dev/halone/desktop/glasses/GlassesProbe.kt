package dev.halone.desktop.glasses

import dev.halone.glasses.api.DeviceCalibration
import dev.halone.glasses.api.TrackingSample
import dev.halone.glasses.fusion.HeadTracker
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlin.system.exitProcess

private const val USAGE = """usage: glasses-probe list
       glasses-probe track [seconds]"""

fun main(args: Array<String>) {
    val glasses = DesktopGlasses()
    try {
        when (args.firstOrNull()) {
            "list" -> list(glasses)
            "track" -> track(glasses, args.getOrNull(1)?.toDoubleOrNull() ?: 20.0)
            else -> {
                System.err.println(USAGE)
                exitProcess(2)
            }
        }
    } finally {
        glasses.shutdown()
    }
}

private fun list(glasses: DesktopGlasses) {
    println("hidapi ${glasses.nativeVersion}")
    for (device in glasses.attached()) {
        val id = device.identity
        println("%04x:%04x %s %s serial=%s".format(id.vendorId, id.productId, id.manufacturerName, id.productName, id.busAddress))
        for (iface in device.interfaces) {
            println("  interface %d usage %04x:%04x %s".format(iface.interfaceNumber, iface.usagePage and 0xffff, iface.usage and 0xffff, iface.path))
        }
    }
    val supported = glasses.findSupported()
    println(if (supported == null) "no supported glasses" else "supported: ${supported.second.match.info.displayName}")
}

private fun track(glasses: DesktopGlasses, seconds: Double) {
    val device = glasses.connect().getOrElse {
        System.err.println("connect failed: ${it.message}")
        exitProcess(1)
    }
    println("connected: ${device.info.displayName} (${device.info.driverId})")
    println("  display control: ${device.display?.currentMode()?.getOrNull() ?: "none"}")
    println("  brightness: ${device.brightness?.current()?.getOrNull() ?: "none"}")
    device.addEventListener { println("  event: $it") }

    val calibration = device.calibration?.load()
    println("  calibration: ${calibration?.source ?: "none"}")
    if (calibration?.source == DeviceCalibration.Source.FALLBACK) println("  (another unit's numbers)")

    val head = HeadTracker(device.optics, calibration = calibration)
    val samples = AtomicLong()
    val gyroDps = java.util.concurrent.atomic.AtomicReference(0.0)
    device.tracking.start { sample ->
        head.onSample(sample)
        samples.incrementAndGet()
        if (sample is TrackingSample.Inertial) {
            val g = sample.gyro
            gyroDps.set(Math.toDegrees(sqrt((g.x * g.x + g.y * g.y + g.z * g.z).toDouble())))
        }
    }

    val start = System.nanoTime()
    var lastPrint = start
    var lastCount = 0L
    var settledYaw: Float? = null
    var settledAt = 0L
    var lastYaw = 0f
    while (System.nanoTime() - start < seconds * 1e9) {
        Thread.sleep(250)
        val now = System.nanoTime()
        val count = samples.get()
        val rate = (count - lastCount) / ((now - lastPrint) / 1e9)
        val (yaw, pitch, roll) = head.poseAfterMs(0f).orientation.toEulerDegrees()
        println("yaw %7.2f  pitch %7.2f  roll %7.2f  | %6.0f samples/s  gyro %6.2f °/s".format(yaw, pitch, roll, rate, gyroDps.get()))
        if (settledYaw == null && now - start > 5e9) {
            settledYaw = yaw
            settledAt = now
        }
        lastYaw = yaw
        lastPrint = now
        lastCount = count
    }
    device.tracking.stop()
    val elapsed = (System.nanoTime() - start) / 1e9
    println("summary: %d samples in %.1f s (%.0f/s)".format(samples.get(), elapsed, samples.get() / elapsed))
    settledYaw?.let {
        val minutes = (System.nanoTime() - settledAt) / 60e9
        var drift = lastYaw - it
        if (drift > 180) drift -= 360
        if (drift < -180) drift += 360
        println("yaw change after settling: %.2f° over %.2f min (%.2f°/min); meaningful only if the glasses sat still".format(drift, minutes, drift / minutes))
    }
}
