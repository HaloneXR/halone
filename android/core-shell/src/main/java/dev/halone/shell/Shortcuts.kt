package dev.halone.shell

import org.json.JSONArray

/**
 * A key with its modifiers, named as a page's keyboard events name it:
 * letters upper case, `Tab`, `` ` ``, `Escape`, `ArrowLeft`, `F5`,
 * `PrintScreen`, and `Meta` for the Meta key pressed and released alone.
 */
data class KeyChord(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    companion object {
        /** `Ctrl+Shift+Tab` and the like, as `shortcuts.json` writes them. */
        fun parse(keys: String): KeyChord {
            val parts = keys.split('+')
            val modifiers = parts.dropLast(1).toSet()
            return KeyChord(parts.last(), "Ctrl" in modifiers, "Alt" in modifiers, "Shift" in modifiers, "Meta" in modifiers)
        }
    }
}

enum class ShortcutAction(val wire: String) {
    NEXT_WINDOW("next-window"),
    PREVIOUS_WINDOW("previous-window"),
    CLOSE_WINDOW("close-window"),
    TOGGLE_LAUNCHER("toggle-launcher"),
    SCREENSHOT("screenshot"),
    RECENTER("recenter"),
    TOGGLE_PIN("toggle-pin"),
    NEW_TAB("new-tab"),
    NEW_PRIVATE_TAB("new-private-tab"),
    CLOSE_TAB("close-tab"),
    NEXT_TAB("next-tab"),
    PREVIOUS_TAB("previous-tab"),
    FOCUS_ADDRESS("focus-address"),
    RELOAD("reload"),
    BACK("back"),
    FORWARD("forward"),
}

/**
 * The keyboard shortcuts, from `shortcuts.json`, which Settings lists too:
 * the shell's everywhere, the browser's in a web window. Meta, Alt+Tab,
 * Alt+F4 and PrintScreen only reach Halone while key capture is on; without
 * it the fallbacks stand in, and with it they are left to apps, such as a
 * terminal's Alt+letter.
 */
class Shortcuts(json: String, mac: Boolean = false) {
    private data class Binding(val chord: KeyChord, val action: ShortcutAction, val browserOnly: Boolean, val fallback: Boolean)

    private val bindings: List<Binding> = JSONArray(json).let { array ->
        (0 until array.length()).mapNotNull { index ->
            val entry = array.getJSONObject(index)
            val keys = if (mac && entry.has("mac")) entry.getString("mac") else entry.optString("keys")
            if (keys.isEmpty()) return@mapNotNull null
            Binding(
                chord = KeyChord.parse(keys),
                action = ShortcutAction.entries.first { it.wire == entry.getString("action") },
                browserOnly = entry.optBoolean("browser"),
                fallback = entry.optBoolean("fallback"),
            )
        }
    }

    /**
     * The action for [chord], in a web window when [inBrowser], with the fallbacks only while [captureOn] is false.
     * In a web window the browser's own binding wins, as ⌘W closes the tab there and the window elsewhere.
     */
    fun resolve(chord: KeyChord, inBrowser: Boolean, captureOn: Boolean): ShortcutAction? =
        bindings.filter { it.chord == chord && (inBrowser || !it.browserOnly) && !(captureOn && it.fallback) }
            .let { matches -> matches.firstOrNull { it.browserOnly } ?: matches.firstOrNull() }?.action

    companion object {
        const val ASSET = "shortcuts.json"

        /** Whether Android would act on [chord] itself, unless key capture takes it first. */
        fun reservedBySystem(chord: KeyChord): Boolean =
            chord.meta || chord.key == "Meta" || chord.key == "PrintScreen" ||
                (chord.alt && (chord.key == "Tab" || chord.key == "F4"))
    }
}
