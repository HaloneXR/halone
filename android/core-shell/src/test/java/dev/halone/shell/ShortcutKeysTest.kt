package dev.halone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutKeysTest {
    private val ran = mutableListOf<KeyChord>()
    private val keys = ShortcutKeys { chord -> ran += chord; chord.key != "B" }

    @Test
    fun `meta pressed and released alone is a chord of its own`() {
        keys.onKey(META, isMeta = true, down = true, repeat = false, chord = null)
        keys.onKey(META, isMeta = true, down = false, repeat = false, chord = null)
        assertEquals(listOf(KeyChord("Meta")), ran)
    }

    @Test
    fun `meta used with another key is not`() {
        keys.onKey(META, isMeta = true, down = true, repeat = false, chord = null)
        keys.onKey(W, isMeta = false, down = true, repeat = false, chord = KeyChord("W", meta = true))
        keys.onKey(META, isMeta = true, down = false, repeat = false, chord = null)
        assertEquals(listOf(KeyChord("W", meta = true)), ran)
    }

    @Test
    fun `a shortcut takes its key's repeats and release, and another key passes`() {
        assertTrue(keys.onKey(W, isMeta = false, down = true, repeat = false, chord = KeyChord("W", meta = true)))
        assertTrue(keys.onKey(W, isMeta = false, down = true, repeat = true, chord = KeyChord("W", meta = true)))
        assertTrue(keys.onKey(W, isMeta = false, down = false, repeat = false, chord = KeyChord("W", meta = true)))
        assertFalse(keys.onKey(B, isMeta = false, down = true, repeat = false, chord = KeyChord("B")))
        assertFalse(keys.onKey(B, isMeta = false, down = false, repeat = false, chord = KeyChord("B")))
        assertEquals(1, ran.count { it.key == "W" })
    }

    @Test
    fun `key capture only decides what the system would take`() {
        assertFalse(keys.onKey(W, isMeta = false, down = true, repeat = false, chord = KeyChord("W", ctrl = true), captured = true))
        assertTrue(ran.isEmpty())
    }

    private companion object {
        const val META = 1
        const val W = 2
        const val B = 3
    }
}
