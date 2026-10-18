package dev.halone.desktop.compositor.shell

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import dev.halone.shell.Battery
import java.io.File

/** How the machine is online; [bars] of 4 for Wi-Fi. */
data class NetworkState(val kind: Kind, val bars: Int = 0) {
    enum class Kind { OFFLINE, WIFI, WIRED }
}

/** What the OS knows that the shell shows: the battery, the network, and the thumbnails its file browser makes. */
object DesktopSystem {
    private val os = System.getProperty("os.name").lowercase()

    /** The internal battery, or null on a machine without one. */
    fun battery(): Battery? = runCatching {
        when {
            os.contains("mac") -> Mac.battery()
            os.contains("win") -> Windows.battery()
            else -> linuxBattery()
        }
    }.getOrNull()

    /** How the machine is online, or null where that is not known here. */
    fun network(): NetworkState? = runCatching {
        when {
            os.contains("mac") -> Mac.network()
            os.contains("win") -> null
            else -> linuxNetwork()
        }
    }.getOrNull()

    /** The OS's own thumbnail of [file] fitting [sizePx], as a JPEG; null where there is none, or no such service here. */
    val thumbnailer: ((File, Int, Float) -> ByteArray?)? = if (os.contains("mac")) Mac::thumbnail else null

    private fun linuxBattery(): Battery? {
        val battery = File("/sys/class/power_supply").listFiles().orEmpty()
            .filter { File(it, "type").readTextOrNull() == "Battery" && File(it, "scope").readTextOrNull() != "Device" }
            .minByOrNull { it.name } ?: return null
        val capacity = File(battery, "capacity").readTextOrNull()?.toDoubleOrNull() ?: return null
        val status = File(battery, "status").readTextOrNull()
        return Battery((capacity / 100.0).coerceIn(0.0, 1.0), status == "Charging" || status == "Full")
    }

    /**
     * Wi-Fi from `/proc/net/wireless`, whose link quality is out of 70 as
     * the kernel's drivers report it; otherwise any interface but loopback
     * with a carrier is a wired one.
     */
    private fun linuxNetwork(): NetworkState {
        val up = File("/sys/class/net").listFiles().orEmpty().filter { it.name != "lo" && File(it, "carrier").readTextOrNull() == "1" }
        val wireless = File("/proc/net/wireless").readTextOrNull()?.lines().orEmpty().drop(2).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val name = fields.getOrNull(0)?.removeSuffix(":") ?: return@mapNotNull null
            val quality = fields.getOrNull(2)?.removeSuffix(".")?.toDoubleOrNull() ?: return@mapNotNull null
            name to quality
        }.toMap()
        val wifi = up.firstOrNull { it.name in wireless }
        if (wifi != null) return NetworkState(NetworkState.Kind.WIFI, ((wireless.getValue(wifi.name) / 70.0) * 4).toInt().coerceIn(0, 4))
        val wired = up.any { !File(it, "wireless").exists() && File(it, "type").readTextOrNull() == "1" && File(it, "device").exists() }
        return NetworkState(if (wired) NetworkState.Kind.WIRED else NetworkState.Kind.OFFLINE)
    }

    private fun File.readTextOrNull() = runCatching { readText().trim() }.getOrNull()

    @Suppress("FunctionName")
    private interface MacSystem : Library {
        fun halone_battery(level: DoubleByReference, charging: IntByReference): Int
        fun halone_thumbnail(path: String, sizePx: Int, quality: Double, out: PointerByReference, length: IntByReference): Int
        fun halone_free(pointer: Pointer)
        fun halone_network(kind: IntByReference, bars: IntByReference)
    }

    private object Mac {
        private val native by lazy { Native.load("halone_bridge", MacSystem::class.java) }

        fun battery(): Battery? {
            val level = DoubleByReference()
            val charging = IntByReference()
            if (native.halone_battery(level, charging) != 0) return null
            return Battery(level.value.coerceIn(0.0, 1.0), charging.value != 0)
        }

        fun network(): NetworkState {
            val kind = IntByReference()
            val bars = IntByReference()
            native.halone_network(kind, bars)
            return when (kind.value) {
                1 -> NetworkState(NetworkState.Kind.WIFI, bars.value)
                2 -> NetworkState(NetworkState.Kind.WIRED)
                else -> NetworkState(NetworkState.Kind.OFFLINE)
            }
        }

        fun thumbnail(file: File, sizePx: Int, quality: Float): ByteArray? {
            val out = PointerByReference()
            val length = IntByReference()
            if (native.halone_thumbnail(file.path, sizePx, quality.toDouble(), out, length) != 0) return null
            return try {
                out.value.getByteArray(0, length.value)
            } finally {
                native.halone_free(out.value)
            }
        }
    }

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun GetSystemPowerStatus(status: PowerStatus): Boolean
    }

    @Structure.FieldOrder("acLineStatus", "batteryFlag", "batteryLifePercent", "systemStatusFlag", "batteryLifeTime", "batteryFullLifeTime")
    class PowerStatus : Structure() {
        @JvmField var acLineStatus: Byte = 0
        @JvmField var batteryFlag: Byte = 0
        @JvmField var batteryLifePercent: Byte = 0
        @JvmField var systemStatusFlag: Byte = 0
        @JvmField var batteryLifeTime: Int = 0
        @JvmField var batteryFullLifeTime: Int = 0
    }

    private object Windows {
        private val kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
        private const val NO_BATTERY = 128
        private const val UNKNOWN = 255

        fun battery(): Battery? {
            val status = PowerStatus()
            if (!kernel32.GetSystemPowerStatus(status)) return null
            val flag = status.batteryFlag.toInt() and 0xFF
            val percent = status.batteryLifePercent.toInt() and 0xFF
            if (flag and NO_BATTERY != 0 || flag == UNKNOWN || percent == UNKNOWN) return null
            return Battery(percent / 100.0, status.acLineStatus.toInt() == 1)
        }
    }
}
