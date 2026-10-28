package dev.halone.app.shell

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import dev.halone.shell.MemoryLimits
import dev.halone.shell.MemoryPressure

/**
 * The phone's memory, for [MemoryLimits]: whether it has little, and how
 * short of it things are now. Pressure is the worst of Android's own word,
 * how close Halone's process is to its share under Android 17's memory
 * limiter, and a recent trim callback.
 */
class MemoryWatch(context: Context, private val log: (String) -> Unit) : ComponentCallbacks2 {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val totalBytes = memoryInfo().totalMem
    private val shareBytes = MemoryLimits.processShareBytes(totalBytes)

    val lowMemoryDevice: Boolean = activityManager.isLowRamDevice || totalBytes < MemoryLimits.LOW_MEMORY_BELOW_BYTES

    /** Android warned of pressure; checked at once rather than at the next round. */
    var onPressure: (() -> Unit)? = null

    private var reported = MemoryPressure.NONE
    private var reportedAt = 0L
    private var last = MemoryPressure.NONE

    init {
        log("  memory: ${totalBytes / MIB} MiB, share ${shareBytes?.let { "${it / MIB} MiB" } ?: "unlimited"}, low-memory phone: $lowMemoryDevice")
    }

    fun pressure(): MemoryPressure {
        val info = memoryInfo()
        val system = when {
            info.lowMemory -> MemoryPressure.CRITICAL
            info.availMem < info.threshold * 2 -> MemoryPressure.MODERATE
            else -> MemoryPressure.NONE
        }
        val used = ownBytes()
        val own = if (shareBytes == null || used == null) MemoryPressure.NONE else when {
            used > shareBytes * CRITICAL_SHARE -> MemoryPressure.CRITICAL
            used > shareBytes * MODERATE_SHARE -> MemoryPressure.MODERATE
            else -> MemoryPressure.NONE
        }
        val recent = if (SystemClock.elapsedRealtime() - reportedAt < REPORT_HOLD_MS) reported else MemoryPressure.NONE
        val pressure = maxOf(system, own, recent)
        if (pressure != last) {
            log("  memory: pressure $pressure (system $system, own ${used?.let { it / MIB }} MiB, reported $recent)")
            last = pressure
        }
        return pressure
    }

    private fun memoryInfo() = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)

    /** What the memory limiter counts: anonymous memory resident or swapped out. */
    private fun ownBytes(): Long? = runCatching {
        val fields = java.io.File("/proc/self/status").readLines()
            .map { it.split(':', limit = 2) }
            .filter { it.size == 2 }
            .associate { (key, value) -> key to value.trim().substringBefore(' ').toLongOrNull() }
        val kib = (fields["RssAnon"] ?: return null) + (fields["VmSwap"] ?: 0)
        kib * 1024
    }.getOrNull()

    private fun report(pressure: MemoryPressure) {
        reported = pressure
        reportedAt = SystemClock.elapsedRealtime()
        onPressure?.invoke()
    }

    // Android 14 and later no longer send the RUNNING levels to a process in front; older ones do.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> report(MemoryPressure.CRITICAL)
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_MODERATE -> report(MemoryPressure.MODERATE)
        else -> Unit
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = report(MemoryPressure.CRITICAL)

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    companion object {
        private const val MIB = 1024L * 1024
        private const val MODERATE_SHARE = 0.6
        private const val CRITICAL_SHARE = 0.8

        /** How long a trim callback's warning counts for. */
        private const val REPORT_HOLD_MS = 60_000L
    }
}
