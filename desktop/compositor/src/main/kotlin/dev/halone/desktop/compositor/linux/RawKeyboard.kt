package dev.halone.desktop.compositor.linux

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.lwjgl.glfw.GLFW.*

/**
 * Turns the helper's raw evdev key events into what GLFW would have given: its
 * key codes, actions, modifiers and typed characters, through xkbcommon with the
 * compositor's own keymap, so layouts and options match the desktop's, and its
 * compose table, so dead keys (US International's ' and ") type as GLFW types them.
 */
class RawKeyboard(keymap: String?) {
    @Suppress("FunctionName")
    private interface Xkb : Library {
        fun xkb_context_new(flags: Int): Pointer?
        fun xkb_keymap_new_from_string(context: Pointer, string: String, format: Int, flags: Int): Pointer?
        fun xkb_keymap_new_from_names(context: Pointer, names: Pointer?, flags: Int): Pointer?
        fun xkb_state_new(keymap: Pointer): Pointer?
        fun xkb_state_update_key(state: Pointer, key: Int, direction: Int): Int
        fun xkb_state_key_get_utf32(state: Pointer, key: Int): Int
        fun xkb_state_mod_name_is_active(state: Pointer, name: String, type: Int): Int
        fun xkb_state_key_get_one_sym(state: Pointer, key: Int): Int
        fun xkb_keysym_to_utf32(keysym: Int): Int
        fun xkb_compose_table_new_from_locale(context: Pointer, locale: String, flags: Int): Pointer?
        fun xkb_compose_state_new(table: Pointer, flags: Int): Pointer?
        fun xkb_compose_state_feed(state: Pointer, keysym: Int): Int
        fun xkb_compose_state_get_status(state: Pointer): Int
        fun xkb_compose_state_get_one_sym(state: Pointer): Int
        fun xkb_compose_state_reset(state: Pointer)
    }

    private val xkb = Native.load("xkbcommon", Xkb::class.java)
    private val state: Pointer
    private val compose: Pointer?

    init {
        val context = xkb.xkb_context_new(0) ?: error("xkb_context_new failed")
        val map = keymap?.let { xkb.xkb_keymap_new_from_string(context, it, XKB_KEYMAP_FORMAT_TEXT_V1, 0) }
            ?: xkb.xkb_keymap_new_from_names(context, null, 0) ?: error("no xkb keymap")
        state = xkb.xkb_state_new(map) ?: error("xkb_state_new failed")
        val locale = listOf("LC_ALL", "LC_CTYPE", "LANG").firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotEmpty) } ?: "C"
        compose = xkb.xkb_compose_table_new_from_locale(context, locale, 0)?.let { xkb.xkb_compose_state_new(it, 0) }
    }

    private var deadKey = 0

    /**
     * What a key down types, through the compose table: nothing while a dead key waits,
     * and for a key the dead key has no accent for, the dead key's own character and then
     * the key's, as desktop apps type ' then k on US International.
     */
    private fun typed(keycode: Int): IntArray {
        val plain = xkb.xkb_state_key_get_utf32(state, keycode)
        val compose = compose ?: return intArrayOf(plain)
        val sym = xkb.xkb_state_key_get_one_sym(state, keycode)
        if (xkb.xkb_compose_state_feed(compose, sym) != XKB_COMPOSE_FEED_ACCEPTED) return intArrayOf(plain)
        return when (xkb.xkb_compose_state_get_status(compose)) {
            XKB_COMPOSE_COMPOSING -> IntArray(0).also { if (deadKey == 0) deadKey = sym }
            XKB_COMPOSE_COMPOSED -> intArrayOf(xkb.xkb_keysym_to_utf32(xkb.xkb_compose_state_get_one_sym(compose))).also { reset(compose) }
            XKB_COMPOSE_CANCELLED -> intArrayOf(spacing(compose, deadKey), plain).also { reset(compose) }
            else -> intArrayOf(plain)
        }
    }

    private fun reset(compose: Pointer) {
        xkb.xkb_compose_state_reset(compose)
        deadKey = 0
    }

    /** A dead key's character on its own, as the compose table types it before a space. */
    private fun spacing(compose: Pointer, dead: Int): Int {
        if (dead == 0) return 0
        xkb.xkb_compose_state_reset(compose)
        xkb.xkb_compose_state_feed(compose, dead)
        xkb.xkb_compose_state_feed(compose, XKB_KEY_SPACE)
        val composed = xkb.xkb_compose_state_get_status(compose) == XKB_COMPOSE_COMPOSED
        return if (composed) xkb.xkb_keysym_to_utf32(xkb.xkb_compose_state_get_one_sym(compose)) else 0
    }

    val mods: Int get() {
        fun on(name: String) = xkb.xkb_state_mod_name_is_active(state, name, XKB_STATE_MODS_EFFECTIVE) > 0
        var m = 0
        if (on("Shift")) m = m or GLFW_MOD_SHIFT
        if (on("Control")) m = m or GLFW_MOD_CONTROL
        if (on("Mod1")) m = m or GLFW_MOD_ALT
        if (on("Mod4")) m = m or GLFW_MOD_SUPER
        if (on("Lock")) m = m or GLFW_MOD_CAPS_LOCK
        if (on("Mod2")) m = m or GLFW_MOD_NUM_LOCK
        return m
    }

    /**
     * One evdev event ([value] 0 up, 1 down, 2 repeat): [key] gets what GLFW's key
     * callback would, with the evdev code as the scancode as GLFW gives it on
     * Wayland, then [character] what its char callback would.
     */
    fun handle(code: Int, value: Int, key: (key: Int, scancode: Int, action: Int, mods: Int) -> Unit, character: (Int, Int) -> Unit) {
        val keycode = code + 8
        val glfwKey = KEYS[code] ?: GLFW_KEY_UNKNOWN
        when (value) {
            0 -> {
                xkb.xkb_state_update_key(state, keycode, XKB_KEY_UP)
                key(glfwKey, code, GLFW_RELEASE, mods)
            }
            1, 2 -> {
                val modsBefore = mods
                val typed = typed(keycode)
                if (value == 1) xkb.xkb_state_update_key(state, keycode, XKB_KEY_DOWN)
                key(glfwKey, code, if (value == 1) GLFW_PRESS else GLFW_REPEAT, if (value == 1) mods else modsBefore)
                val plain = modsBefore and (GLFW_MOD_CONTROL or GLFW_MOD_ALT) == 0
                if (plain) typed.filter { it >= 0x20 && it != 0x7F }.forEach { character(it, modsBefore) }
            }
        }
    }

    private companion object {
        const val XKB_KEYMAP_FORMAT_TEXT_V1 = 1
        const val XKB_STATE_MODS_EFFECTIVE = 1 shl 3
        const val XKB_KEY_UP = 0
        const val XKB_KEY_DOWN = 1
        const val XKB_COMPOSE_FEED_ACCEPTED = 1
        const val XKB_COMPOSE_COMPOSING = 1
        const val XKB_COMPOSE_COMPOSED = 2
        const val XKB_COMPOSE_CANCELLED = 3
        const val XKB_KEY_SPACE = 0x20

        /** GLFW's own table from evdev codes to its keys, as in its Wayland backend. */
        val KEYS: Map<Int, Int> = buildMap {
            val letters = mapOf(30 to 'A', 48 to 'B', 46 to 'C', 32 to 'D', 18 to 'E', 33 to 'F', 34 to 'G', 35 to 'H', 23 to 'I',
                36 to 'J', 37 to 'K', 38 to 'L', 50 to 'M', 49 to 'N', 24 to 'O', 25 to 'P', 16 to 'Q', 19 to 'R', 31 to 'S',
                20 to 'T', 22 to 'U', 47 to 'V', 17 to 'W', 45 to 'X', 21 to 'Y', 44 to 'Z')
            letters.forEach { (code, c) -> put(code, c.code) }
            for (digit in 1..9) put(1 + digit, GLFW_KEY_0 + digit)
            put(11, GLFW_KEY_0)
            putAll(mapOf(
                41 to GLFW_KEY_GRAVE_ACCENT, 57 to GLFW_KEY_SPACE, 12 to GLFW_KEY_MINUS, 13 to GLFW_KEY_EQUAL,
                26 to GLFW_KEY_LEFT_BRACKET, 27 to GLFW_KEY_RIGHT_BRACKET, 39 to GLFW_KEY_SEMICOLON, 40 to GLFW_KEY_APOSTROPHE,
                51 to GLFW_KEY_COMMA, 52 to GLFW_KEY_PERIOD, 53 to GLFW_KEY_SLASH, 43 to GLFW_KEY_BACKSLASH, 86 to GLFW_KEY_WORLD_2,
                1 to GLFW_KEY_ESCAPE, 15 to GLFW_KEY_TAB, 42 to GLFW_KEY_LEFT_SHIFT, 54 to GLFW_KEY_RIGHT_SHIFT,
                29 to GLFW_KEY_LEFT_CONTROL, 97 to GLFW_KEY_RIGHT_CONTROL, 56 to GLFW_KEY_LEFT_ALT, 100 to GLFW_KEY_RIGHT_ALT,
                125 to GLFW_KEY_LEFT_SUPER, 126 to GLFW_KEY_RIGHT_SUPER, 127 to GLFW_KEY_MENU, 69 to GLFW_KEY_NUM_LOCK,
                58 to GLFW_KEY_CAPS_LOCK, 99 to GLFW_KEY_PRINT_SCREEN, 70 to GLFW_KEY_SCROLL_LOCK, 119 to GLFW_KEY_PAUSE,
                111 to GLFW_KEY_DELETE, 14 to GLFW_KEY_BACKSPACE, 28 to GLFW_KEY_ENTER, 102 to GLFW_KEY_HOME, 107 to GLFW_KEY_END,
                104 to GLFW_KEY_PAGE_UP, 109 to GLFW_KEY_PAGE_DOWN, 110 to GLFW_KEY_INSERT, 105 to GLFW_KEY_LEFT,
                106 to GLFW_KEY_RIGHT, 108 to GLFW_KEY_DOWN, 103 to GLFW_KEY_UP,
                87 to GLFW_KEY_F11, 88 to GLFW_KEY_F12,
                98 to GLFW_KEY_KP_DIVIDE, 55 to GLFW_KEY_KP_MULTIPLY, 74 to GLFW_KEY_KP_SUBTRACT, 78 to GLFW_KEY_KP_ADD,
                82 to GLFW_KEY_KP_0, 79 to GLFW_KEY_KP_1, 80 to GLFW_KEY_KP_2, 81 to GLFW_KEY_KP_3, 75 to GLFW_KEY_KP_4,
                76 to GLFW_KEY_KP_5, 77 to GLFW_KEY_KP_6, 71 to GLFW_KEY_KP_7, 72 to GLFW_KEY_KP_8, 73 to GLFW_KEY_KP_9,
                83 to GLFW_KEY_KP_DECIMAL, 117 to GLFW_KEY_KP_EQUAL, 96 to GLFW_KEY_KP_ENTER,
            ))
            for (f in 0 until 10) put(59 + f, GLFW_KEY_F1 + f)
            for (f in 0 until 12) put(183 + f, GLFW_KEY_F13 + f)
        }
    }
}
