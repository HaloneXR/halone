package dev.halone.shell

/** How short of memory Android is, or how close Halone is to the share Android allows it. */
enum class MemoryPressure { NONE, MODERATE, CRITICAL }

/**
 * How much Halone keeps open, by the phone's memory and the developer
 * settings' `developer.windowLimit` and `developer.tabLimit`: `auto` goes by
 * the phone, `low` treats it as a phone with little memory, `high` raises the
 * limits and `none` lifts them.
 *
 * A low-memory phone is one with under 12 GB: Halone needs USB-C video out,
 * so in practice a 6 or 8 GB "FE" or older flagship, whose share under
 * Android 17's memory limiter is 4 or 5 GB a process.
 */
object MemoryLimits {
    /** Background tabs kept loaded, and how long one may sit unseen before it is put to sleep. */
    data class TabPolicy(val liveBackgroundTabs: Int, val idleMs: Long)

    /** A background tab, as [toSuspend] weighs it; an [exempt] one, such as one playing sound, is left alone. */
    data class BackgroundTab<K>(val key: K, val idleMs: Long, val exempt: Boolean)

    const val NORMAL_WINDOWS = 6
    const val LOW_WINDOWS = 4
    const val HIGH_WINDOWS = 12

    val NORMAL_TABS = TabPolicy(liveBackgroundTabs = 6, idleMs = 30 * MINUTE_MS)
    val LOW_TABS = TabPolicy(liveBackgroundTabs = 2, idleMs = 10 * MINUTE_MS)
    val HIGH_TABS = TabPolicy(liveBackgroundTabs = 20, idleMs = 120 * MINUTE_MS)
    val CRITICAL_TABS = TabPolicy(liveBackgroundTabs = 0, idleMs = 0)

    /** Below this much RAM, as Android reports it, a phone counts as low on memory: the memory limiter's 12 GB tier starts here. */
    const val LOW_MEMORY_BELOW_BYTES = 9216L * 1024 * 1024

    /** The most windows open at once, or null for no limit. */
    fun windows(setting: String, lowMemoryDevice: Boolean): Int? = when (setting) {
        "none" -> null
        "high" -> HIGH_WINDOWS
        "low" -> LOW_WINDOWS
        else -> if (lowMemoryDevice) LOW_WINDOWS else NORMAL_WINDOWS
    }

    /** How background tabs are put to sleep, or null when they never are. Pressure tightens any limit but `none`. */
    fun tabs(setting: String, lowMemoryDevice: Boolean, pressure: MemoryPressure): TabPolicy? {
        val base = when (setting) {
            "none" -> return null
            "high" -> HIGH_TABS
            "low" -> LOW_TABS
            else -> if (lowMemoryDevice) LOW_TABS else NORMAL_TABS
        }
        return when (pressure) {
            MemoryPressure.NONE -> base
            MemoryPressure.MODERATE -> TabPolicy(minOf(base.liveBackgroundTabs, LOW_TABS.liveBackgroundTabs), minOf(base.idleMs, LOW_TABS.idleMs))
            MemoryPressure.CRITICAL -> CRITICAL_TABS
        }
    }

    /** The tabs to put to sleep: those unseen for too long, then the longest unseen until few enough are left. */
    fun <K> toSuspend(tabs: List<BackgroundTab<K>>, policy: TabPolicy): List<K> {
        val candidates = tabs.filter { !it.exempt }.sortedByDescending { it.idleMs }
        val (stale, fresh) = candidates.partition { it.idleMs >= policy.idleMs }
        val excess = (fresh.size - policy.liveBackgroundTabs).coerceAtLeast(0)
        return (stale + fresh.take(excess)).map { it.key }
    }

    /** Android 17's memory limiter's share for a visible process on a phone with [totalBytes], or null where it sets none. */
    fun processShareBytes(totalBytes: Long): Long? {
        val mib = totalBytes / (1024 * 1024)
        val shareMib = when {
            mib < 3200 -> return null
            mib < 4800 -> 2048L
            mib < 6800 -> 4096L
            mib < 9216 -> 5120L
            mib < 14336 -> 8192L
            mib < 18432 -> 10240L
            else -> return null
        }
        return shareMib * 1024 * 1024
    }

    private const val MINUTE_MS = 60_000L
}
