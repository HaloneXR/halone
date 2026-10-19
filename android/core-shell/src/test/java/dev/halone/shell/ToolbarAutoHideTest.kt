package dev.halone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarAutoHideTest {
    private var now = 1_000L
    private var enabled = true
    private val applied = mutableListOf<Boolean>()
    private val toolbar = ToolbarAutoHide({ enabled }, { now }, {}, { applied += it })

    private fun scrollTo(y: Int) {
        toolbar.input()
        toolbar.onScroll(y)
    }

    private fun rest() {
        now += ToolbarAutoHide.SETTLE_MS
        toolbar.settle()
    }

    @Test
    fun `scrolling down a page hides the toolbar once scrolling rests, and scrolling up brings it back`() {
        scrollTo(400)
        scrollTo(600)
        assertTrue(applied.isEmpty())
        rest()
        assertEquals(listOf(true), applied)
        scrollTo(500)
        rest()
        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `near the top the toolbar always shows`() {
        scrollTo(400)
        scrollTo(600)
        rest()
        scrollTo(ToolbarAutoHide.NEAR_TOP_PX)
        rest()
        assertFalse(toolbar.hidden)
    }

    @Test
    fun `the page moving after the band changed is not the wearer scrolling`() {
        scrollTo(100)
        toolbar.bandChanged()
        toolbar.onScroll(700)
        rest()
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `nothing changes while a finger is on the page`() {
        scrollTo(400)
        scrollTo(600)
        toolbar.touching = true
        rest()
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `turning the setting off brings a hidden toolbar back`() {
        scrollTo(400)
        scrollTo(600)
        rest()
        enabled = false
        assertTrue(toolbar.settingsChanged())
        assertFalse(toolbar.hidden)
    }
}
