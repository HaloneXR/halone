package dev.halone.desktop.compositor.mac

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import org.lwjgl.glfw.GLFW.*

/**
 * Raw keyboard on macOS: an event tap, allowed by the Accessibility permission, takes
 * the keys the system would keep for itself (⌘ and Ctrl shortcuts, the function row)
 * while Halone's window is focused, and hands them over as GLFW would have. Typing
 * still reaches GLFW, so dead keys and input methods keep working. Force Quit is left
 * to the system. Under Secure Input macOS sends the tap nothing and the system keeps
 * its shortcuts again.
 */
class MacKeys private constructor() {
    var holding = false
        private set

    fun hold(on: Boolean) {
        holding = on
        native.halone_keys_hold(if (on) 1 else 0)
    }

    val secureInput: Boolean get() = native.halone_keys_secure() != 0

    /** Hands the keys taken since the last call to [onKey], as GLFW's key callback would. */
    fun drain(onKey: (key: Int, scancode: Int, action: Int, mods: Int) -> Unit) {
        while (native.halone_keys_next(keycode, down, repeat, flags) != 0) {
            val key = GLFW_KEYS.getOrElse(keycode.value) { GLFW_KEY_UNKNOWN }
            val action = when {
                down.value == 0 -> GLFW_RELEASE
                repeat.value != 0 -> GLFW_REPEAT
                else -> GLFW_PRESS
            }
            onKey(key, keycode.value, action, modsOf(flags.value))
        }
    }

    private val keycode = IntByReference()
    private val down = IntByReference()
    private val repeat = IntByReference()
    private val flags = LongByReference()

    @Suppress("FunctionName")
    private interface Bridge : Library {
        fun halone_keys_trusted(prompt: Int): Int
        fun halone_keys_start(): Int
        fun halone_keys_hold(on: Int)
        fun halone_keys_secure(): Int
        fun halone_keys_next(keycode: IntByReference, down: IntByReference, repeat: IntByReference, flags: LongByReference): Int
    }

    companion object {
        private val native by lazy { Native.load("halone_bridge", Bridge::class.java) }
        private var started: MacKeys? = null

        /** Whether this process may tap the keyboard; [prompt] has macOS ask for the permission when not. */
        fun trusted(prompt: Boolean) = native.halone_keys_trusted(if (prompt) 1 else 0) != 0

        /** The tap, started on first use; null while the Accessibility permission is missing. */
        fun start(): MacKeys? {
            started?.let { return it }
            if (native.halone_keys_start() != 0) return null
            return MacKeys().also { started = it }
        }

        private fun modsOf(flags: Long): Int {
            var mods = 0
            if (flags and 0x20000L != 0L) mods = mods or GLFW_MOD_SHIFT
            if (flags and 0x40000L != 0L) mods = mods or GLFW_MOD_CONTROL
            if (flags and 0x80000L != 0L) mods = mods or GLFW_MOD_ALT
            if (flags and 0x100000L != 0L) mods = mods or GLFW_MOD_SUPER
            if (flags and 0x10000L != 0L) mods = mods or GLFW_MOD_CAPS_LOCK
            return mods
        }

        /** GLFW's own table from macOS virtual key codes, by position on the keyboard. */
        private val GLFW_KEYS = IntArray(128) { GLFW_KEY_UNKNOWN }.also { t ->
            val letters = intArrayOf(0, 11, 8, 2, 14, 3, 5, 4, 34, 38, 40, 37, 46, 45, 31, 35, 12, 15, 1, 17, 32, 9, 13, 7, 16, 6)
            letters.forEachIndexed { i, code -> t[code] = GLFW_KEY_A + i }
            intArrayOf(29, 18, 19, 20, 21, 23, 22, 26, 28, 25).forEachIndexed { i, code -> t[code] = GLFW_KEY_0 + i }
            intArrayOf(82, 83, 84, 85, 86, 87, 88, 89, 91, 92).forEachIndexed { i, code -> t[code] = GLFW_KEY_KP_0 + i }
            intArrayOf(122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111, 105, 107, 113, 106, 64, 79, 80, 90)
                .forEachIndexed { i, code -> t[code] = GLFW_KEY_F1 + i }
            mapOf(
                24 to GLFW_KEY_EQUAL, 27 to GLFW_KEY_MINUS, 30 to GLFW_KEY_RIGHT_BRACKET, 33 to GLFW_KEY_LEFT_BRACKET,
                39 to GLFW_KEY_APOSTROPHE, 41 to GLFW_KEY_SEMICOLON, 42 to GLFW_KEY_BACKSLASH, 43 to GLFW_KEY_COMMA,
                44 to GLFW_KEY_SLASH, 47 to GLFW_KEY_PERIOD, 50 to GLFW_KEY_GRAVE_ACCENT, 10 to GLFW_KEY_WORLD_1,
                36 to GLFW_KEY_ENTER, 48 to GLFW_KEY_TAB, 49 to GLFW_KEY_SPACE, 51 to GLFW_KEY_BACKSPACE, 53 to GLFW_KEY_ESCAPE,
                114 to GLFW_KEY_INSERT, 115 to GLFW_KEY_HOME, 116 to GLFW_KEY_PAGE_UP, 117 to GLFW_KEY_DELETE,
                119 to GLFW_KEY_END, 121 to GLFW_KEY_PAGE_DOWN, 123 to GLFW_KEY_LEFT, 124 to GLFW_KEY_RIGHT,
                125 to GLFW_KEY_DOWN, 126 to GLFW_KEY_UP, 65 to GLFW_KEY_KP_DECIMAL, 67 to GLFW_KEY_KP_MULTIPLY,
                69 to GLFW_KEY_KP_ADD, 71 to GLFW_KEY_NUM_LOCK, 75 to GLFW_KEY_KP_DIVIDE, 76 to GLFW_KEY_KP_ENTER,
                78 to GLFW_KEY_KP_SUBTRACT, 81 to GLFW_KEY_KP_EQUAL,
            ).forEach { (code, key) -> t[code] = key }
        }
    }
}
