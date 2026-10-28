package dev.halone.shell

import dev.halone.compositor.Scene
import dev.halone.shell.MemoryLimits.BackgroundTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLimitsTest {
    private val scene = Scene()
    private val platform = FakePlatform(scene)
    private val shell = Shell(scene, APPS, ShellSettings(MemoryStore(), SCHEMA), platform)

    private fun openWebWindows(count: Int) = repeat(count) { shell.openUrl("https://example.com/$it") }

    @Test
    fun `six windows open at once, and a seventh is refused with a notice`() {
        openWebWindows(MemoryLimits.NORMAL_WINDOWS)

        val error = assertThrows(HaloneException::class.java) { shell.launch("settings") }
        assertEquals("too-many-windows", error.code)
        assertEquals(MemoryLimits.NORMAL_WINDOWS, shell.windows().size)
        assertEquals(listOf("Close a window first: at most 6 can be open at once"), platform.notices)
    }

    @Test
    fun `an open app is still focused at the limit, and closing a window makes room`() {
        val settings = shell.launch("settings")
        openWebWindows(MemoryLimits.NORMAL_WINDOWS - 1)

        assertEquals(settings, shell.launch("settings"))
        shell.close(shell.windows().last().id)
        shell.launch("files")
    }

    @Test
    fun `a low-memory phone opens fewer windows, and the developer setting overrides it`() {
        platform.lowMemory = true
        openWebWindows(MemoryLimits.LOW_WINDOWS)
        assertThrows(HaloneException::class.java) { shell.openUrl("https://example.com/more") }

        shell.setSetting(ShellSettings.DEVELOPER_WINDOW_LIMIT, "high")
        openWebWindows(MemoryLimits.HIGH_WINDOWS - MemoryLimits.LOW_WINDOWS)
        assertThrows(HaloneException::class.java) { shell.openUrl("https://example.com/more") }

        shell.setSetting(ShellSettings.DEVELOPER_WINDOW_LIMIT, "none")
        openWebWindows(5)
        assertEquals(MemoryLimits.HIGH_WINDOWS + 5, shell.windows().size)
    }

    @Test
    fun `simulating a low-memory phone lowers the limit on any phone`() {
        shell.setSetting(ShellSettings.DEVELOPER_WINDOW_LIMIT, "low")
        assertEquals(MemoryLimits.LOW_WINDOWS, shell.windowLimit)
    }

    @Test
    fun `pressure tightens the tab policy, except with no limit`() {
        assertEquals(MemoryLimits.NORMAL_TABS, MemoryLimits.tabs("auto", false, MemoryPressure.NONE))
        assertEquals(MemoryLimits.LOW_TABS, MemoryLimits.tabs("auto", true, MemoryPressure.NONE))
        assertEquals(MemoryLimits.LOW_TABS, MemoryLimits.tabs("auto", false, MemoryPressure.MODERATE))
        assertEquals(MemoryLimits.LOW_TABS, MemoryLimits.tabs("high", false, MemoryPressure.MODERATE))
        assertEquals(MemoryLimits.CRITICAL_TABS, MemoryLimits.tabs("high", false, MemoryPressure.CRITICAL))
        assertNull(MemoryLimits.tabs("none", true, MemoryPressure.CRITICAL))
    }

    @Test
    fun `tabs unseen too long sleep first, then the longest unseen until few enough are left`() {
        val policy = MemoryLimits.TabPolicy(liveBackgroundTabs = 2, idleMs = 1_000)
        val tabs = listOf(
            BackgroundTab("stale", 5_000, exempt = false),
            BackgroundTab("playing", 9_000, exempt = true),
            BackgroundTab("a", 900, exempt = false),
            BackgroundTab("b", 500, exempt = false),
            BackgroundTab("c", 100, exempt = false),
        )

        assertEquals(listOf("stale", "a"), MemoryLimits.toSuspend(tabs, policy))
        assertTrue(MemoryLimits.toSuspend(tabs.take(2), MemoryLimits.TabPolicy(2, 10_000)).isEmpty())
        assertEquals(listOf("stale", "a", "b", "c"), MemoryLimits.toSuspend(tabs, MemoryLimits.CRITICAL_TABS))
    }

    @Test
    fun `the process share follows the memory limiter's tiers`() {
        val gib = 1024L * 1024 * 1024
        assertEquals(4 * gib, MemoryLimits.processShareBytes(5_600L * 1024 * 1024))
        assertEquals(5 * gib, MemoryLimits.processShareBytes(7_400L * 1024 * 1024))
        assertEquals(8 * gib, MemoryLimits.processShareBytes(11_200L * 1024 * 1024))
        assertNull(MemoryLimits.processShareBytes(24 * gib))
    }
}
