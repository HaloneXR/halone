package dev.halone.shell

import dev.halone.compositor.ScenePanel
import dev.halone.compositor.WindowPanel

/**
 * Key presses into shell shortcuts, for any platform's keyboard: Meta pressed
 * and released alone is a chord of its own, and a key whose press ran a
 * shortcut has its release taken too. [run] runs a chord, answering whether it
 * was a shortcut. Call on the shell's thread.
 */
class ShortcutKeys(private val run: (KeyChord) -> Boolean) {
    /** Meta went down and nothing else has been pressed since: on its release, `Meta`. */
    private var metaAlone = false

    private val taken = HashSet<Int>()

    /**
     * One key event, true when a shortcut takes it. [code] names the key from
     * its press to its release, [isMeta] says whether it is a Meta key, and
     * [chord] is what it presses, or null for a key no shortcut uses. With
     * [captured], the event came from key capture, which decides only what
     * the system would otherwise take for itself.
     */
    fun onKey(code: Int, isMeta: Boolean, down: Boolean, repeat: Boolean, chord: KeyChord?, captured: Boolean = false): Boolean {
        if (isMeta) {
            if (down) {
                if (!repeat) metaAlone = true
            } else if (metaAlone) {
                metaAlone = false
                run(KeyChord("Meta"))
            }
            return captured
        }
        if (down) metaAlone = false
        if (!down) return taken.remove(code)
        chord ?: return false
        if (captured && !Shortcuts.reservedBySystem(chord)) return false
        if (repeat) return code in taken
        val handled = run(chord)
        if (handled || (captured && Shortcuts.reservedBySystem(chord))) taken += code
        return code in taken
    }
}

/** Runs the shortcut [chord] names, on the window keys go to ([keyTarget]); false when it names none. */
fun <P> Shell<P>.runShortcut(chord: KeyChord, shortcuts: Shortcuts, keyTarget: P?, captureOn: Boolean, log: (String) -> Unit = {}): Boolean
    where P : ScenePanel, P : WindowPanel {
    val target = keyTarget?.let { panel -> windows().firstOrNull { it.panel === panel } }
    val action = shortcuts.resolve(chord, inBrowser = target?.kind == WindowKind.WEB, captureOn = captureOn) ?: return false
    log("  shortcut $chord -> $action")
    return runShortcut(action, target?.id)
}
