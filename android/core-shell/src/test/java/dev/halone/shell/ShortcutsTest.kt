package dev.halone.shell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    private val shortcuts = Shortcuts(java.io.File("../../web/apps/system/public/shortcuts.json").readText())

    @Test
    fun `system keys work everywhere, the browser's only in a web window`() {
        assertEquals(ShortcutAction.NEXT_WINDOW, shortcuts.resolve(KeyChord("Tab", alt = true), inBrowser = false, captureOn = true))
        assertEquals(ShortcutAction.TOGGLE_LAUNCHER, shortcuts.resolve(KeyChord("Meta"), inBrowser = false, captureOn = true))
        assertEquals(ShortcutAction.CLOSE_TAB, shortcuts.resolve(KeyChord("W", ctrl = true), inBrowser = true, captureOn = true))
        assertNull(shortcuts.resolve(KeyChord("W", ctrl = true), inBrowser = false, captureOn = true))
        assertNull(shortcuts.resolve(KeyChord("C", ctrl = true), inBrowser = true, captureOn = true))
    }

    @Test
    fun `the Alt fallbacks stand in without key capture and leave Alt to apps with it`() {
        assertEquals(ShortcutAction.CLOSE_WINDOW, shortcuts.resolve(KeyChord("W", alt = true), inBrowser = false, captureOn = false))
        assertEquals(ShortcutAction.NEXT_WINDOW, shortcuts.resolve(KeyChord("`", alt = true), inBrowser = false, captureOn = false))
        assertNull(shortcuts.resolve(KeyChord("W", alt = true), inBrowser = false, captureOn = true))
        assertNull(shortcuts.resolve(KeyChord("S", alt = true), inBrowser = false, captureOn = true))
    }

    @Test
    fun `every entry in the shared table names a known action`() {
        val table = org.json.JSONArray(java.io.File("../../web/apps/system/public/shortcuts.json").readText())
        assertTrue(table.length() > 20)
        assertEquals(KeyChord("Tab", ctrl = true, shift = true), KeyChord.parse("Ctrl+Shift+Tab"))
    }

    @Test
    fun `what Android keeps for itself is known, so capture can take it`() {
        assertTrue(Shortcuts.reservedBySystem(KeyChord("Tab", alt = true)))
        assertTrue(Shortcuts.reservedBySystem(KeyChord("W", meta = true)))
        assertTrue(Shortcuts.reservedBySystem(KeyChord("PrintScreen")))
        assertFalse(Shortcuts.reservedBySystem(KeyChord("W", ctrl = true)))
        assertFalse(Shortcuts.reservedBySystem(KeyChord("`", alt = true)))
    }

    private val scene = Scene()
    private val platform = FakePlatform(scene)
    private val shell = Shell(scene, APPS, ShellSettings(MemoryStore(), SCHEMA), platform)

    @Test
    fun `window shortcuts cycle, close and pin the window with the keyboard`() {
        val files = shell.launch("files")
        val settings = shell.launch("settings")
        val web = shell.openUrl("https://example.com/")

        assertTrue(shell.runShortcut(ShortcutAction.NEXT_WINDOW, web.id))
        assertEquals(listOf(files), shell.windows().filter { scene.focused === it.panel })
        shell.runShortcut(ShortcutAction.PREVIOUS_WINDOW, files.id)
        assertEquals(listOf(web), shell.windows().filter { scene.focused === it.panel })

        shell.runShortcut(ShortcutAction.TOGGLE_PIN, settings.id)
        assertFalse(settings.panel.follow.enabled)
        shell.runShortcut(ShortcutAction.CLOSE_WINDOW, settings.id)
        assertTrue(shell.windows().none { it.id == settings.id })
        assertFalse(shell.runShortcut(ShortcutAction.CLOSE_WINDOW, null))
    }

    @Test
    fun `browser shortcuts drive the web window's tabs and need one`() {
        val files = shell.launch("files")
        val web = shell.openUrl("https://example.com/")
        val browser = platform.browsers.getValue(web.id)
        val host = BridgeHost(shell, SystemInfo("test", "Test Glasses", 19.0, 1920, 1080))
        val chrome = FakeChannel().also { host.connect(web.id, it) }

        assertFalse(shell.runShortcut(ShortcutAction.NEW_TAB, files.id))
        shell.runShortcut(ShortcutAction.NEW_TAB, web.id)
        assertEquals(2, browser.tabs.size)
        shell.runShortcut(ShortcutAction.NEXT_TAB, web.id)
        assertEquals(1, browser.active)
        shell.runShortcut(ShortcutAction.CLOSE_TAB, web.id)
        assertEquals(1, browser.tabs.size)
        shell.runShortcut(ShortcutAction.RELOAD, web.id)
        assertEquals("reload", browser.calls.last())
        shell.runShortcut(ShortcutAction.FOCUS_ADDRESS, web.id)
        val command = chrome.posted.map { JSONObject(it) }.last { it.optString("name") == "browser.command" }
        assertEquals("focus-address", command.getJSONObject("data").getString("command"))

        shell.runShortcut(ShortcutAction.CLOSE_TAB, web.id)
        assertTrue(shell.windows().none { it.id == web.id })
    }
}
