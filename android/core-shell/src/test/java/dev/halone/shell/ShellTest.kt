package dev.halone.shell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellTest {
    private val scene = Scene()
    private val platform = FakePlatform(scene)
    private val store = MemoryStore()
    private val shell = Shell(scene, APPS, ShellSettings(store, SCHEMA), platform)

    @Test
    fun `an app opens from its entry on our origin`() {
        val window = shell.launch("settings")

        assertEquals("https://halone.local/settings/index.html", window.url)
        assertEquals(WindowKind.APP, window.kind)
        assertEquals(1024, window.panel.widthPx)
        assertTrue(window.panel in scene.panels)
    }

    @Test
    fun `apps are single instance`() {
        val first = shell.launch("settings")
        shell.launch("files")

        assertSame(first, shell.launch("settings"))
        assertEquals(2, shell.windows().size)
    }

    @Test
    fun `an unknown app is refused by code`() {
        val error = assertThrows(HaloneException::class.java) { shell.launch("nope") }
        assertEquals("no-such-app", error.code)
    }

    @Test
    fun `only http and https open as web windows`() {
        assertEquals("invalid-url", assertThrows(HaloneException::class.java) { shell.openUrl("javascript:alert(1)") }.code)
        assertEquals("invalid-url", assertThrows(HaloneException::class.java) { shell.openUrl("file:///sdcard/x") }.code)
        assertEquals("invalid-url", assertThrows(HaloneException::class.java) { shell.openUrl("not a url") }.code)

        val window = shell.openUrl("https://example.com/page")
        assertEquals(WindowKind.WEB, window.kind)
        assertEquals("example.com", window.title)
    }

    @Test
    fun `nothing opens without a display`() {
        platform.available = false
        assertEquals("unavailable", assertThrows(HaloneException::class.java) { shell.launch("files") }.code)
        assertTrue(shell.windows().isEmpty())
    }

    @Test
    fun `pinning goes through the scene and keeps the follow tuning`() {
        val window = shell.launch("files")

        shell.setPinned(window.id, true)
        assertFalse(window.panel.follow.enabled)
        assertEquals(true, shell.info(window).getBoolean("pinned"))

        shell.setPinned(window.id, false)
        assertTrue(window.panel.follow.enabled)
    }

    @Test
    fun `closing releases the engine and forgets the window`() {
        val window = shell.launch("files")
        shell.close(window.id)

        assertTrue(window.panel.closed)
        assertTrue(shell.windows().isEmpty())
        assertEquals("no-such-window", assertThrows(HaloneException::class.java) { shell.find(window.id) }.code)
    }

    @Test
    fun `a panel removed behind the shell's back is noticed`() {
        val window = shell.launch("files")
        scene.remove(window.panel)

        shell.poll()

        assertTrue(shell.windows().isEmpty())
    }

    @Test
    fun `headings cross the boundary compass-style`() {
        platform.headYaw = radians(20f)
        platform.headPitch = radians(5f)

        val pose = shell.headPose()
        assertEquals(-20.0, pose.yawDegrees, 1e-4)
        assertEquals(5.0, pose.pitchDegrees, 1e-4)

        val window = shell.launch("files")
        window.panel.pose = window.panel.pose.copy(yawRadians = radians(30f))
        assertEquals(-30.0, shell.info(window).getDouble("yawDegrees"), 1e-4)
    }

    @Test
    fun `focusing a window focuses it and brings the arrangement round to it`() {
        shell.launch("files")
        val settings = shell.launch("settings")
        val slot = settings.panel.slotYawRadians
        platform.headYaw = 1f
        shell.setLauncherOpen(true)

        shell.focus(settings.id)
        repeat(300) { scene.advanceFollow(platform.headYaw, 1f / 60) }

        assertSame(settings.panel, scene.focused)
        assertEquals(1f, settings.panel.pose.yawRadians, 0.02f)
        assertEquals(slot, settings.panel.slotYawRadians, 1e-6f)
        assertFalse(shell.launcherOpen)
    }

    @Test
    fun `pinned apps are a list of distinct app ids, kept across a restart`() {
        assertEquals(listOf("files", "settings"), shell.settings.strings(ShellSettings.DOCK_PINNED_APPS))

        shell.setSetting(ShellSettings.DOCK_PINNED_APPS, org.json.JSONArray(listOf("about", "files")))
        assertEquals(listOf("about", "files"), ShellSettings(store, SCHEMA).strings(ShellSettings.DOCK_PINNED_APPS))

        for (bad in listOf(listOf("a", "a"), listOf("a", 3), List(13) { "app$it" }, "settings")) {
            val value = if (bad is List<*>) org.json.JSONArray(bad) else bad
            assertEquals("invalid-setting", assertThrows(HaloneException::class.java) { shell.setSetting(ShellSettings.DOCK_PINNED_APPS, value) }.code)
        }
    }

    @Test
    fun `the launcher is part of the shell, shown on its own with no window open`() {
        val events = mutableListOf<Boolean>()
        shell.addListener(object : ShellListener {
            override fun onLauncherChanged(open: Boolean) {
                events += open
            }
        })
        shell.openOverlays()
        assertTrue(shell.launcherOpen)

        val files = shell.launch("files")
        assertFalse("opening a window puts the launcher away", shell.launcherOpen)
        shell.setLauncherOpen(true)
        assertTrue(shell.launcherOpen)
        shell.close(files.id)
        shell.setLauncherOpen(false)
        assertTrue("with nothing open it stays", shell.launcherOpen)

        assertEquals(listOf(true, false, true), events)
        assertThrows(HaloneException::class.java) { shell.launch("launcher") }
    }

    @Test
    fun `focus changes are reported once`() {
        val events = mutableListOf<Pair<String?, String?>>()
        shell.addListener(object : ShellListener {
            override fun onFocusChanged(previous: String?, current: String?) {
                events += previous to current
            }
        })
        shell.launch("files")

        shell.poll()
        shell.poll()

        assertEquals(listOf(null to "w1"), events)
    }

    @Test
    fun `settings are validated, applied and persisted`() {
        assertEquals(listOf<String?>(null), platform.applied)

        assertEquals("invalid-setting", assertThrows(HaloneException::class.java) { shell.setSetting("display.brightness", 3) }.code)
        assertEquals("invalid-setting", assertThrows(HaloneException::class.java) { shell.setSetting("nope", 1) }.code)
        assertEquals("invalid-setting", assertThrows(HaloneException::class.java) { shell.setSetting("follow.enabled", "yes") }.code)

        shell.setSetting(ShellSettings.SLOT_SPACING_DEGREES, 40)

        assertEquals(40.0, shell.settings.number(ShellSettings.SLOT_SPACING_DEGREES), 0.0)
        assertEquals(ShellSettings.SLOT_SPACING_DEGREES, platform.applied.last())
        assertEquals(40.0, ShellSettings(store, SCHEMA).number(ShellSettings.SLOT_SPACING_DEGREES), 0.0)
    }

    @Test
    fun `gesture slots accept only their own actions`() {
        assertEquals("click", shell.settings.string(ShellSettings.glassesTap("left")))
        assertEquals("disabled", shell.settings.string(ShellSettings.glassesDoubleTap("right")))
        assertEquals("invalid-setting", assertThrows(HaloneException::class.java) { shell.setSetting(ShellSettings.padTap(1), "scroll") }.code)

        shell.setSetting(ShellSettings.padHold(2), "recenter-view")

        assertEquals("recenter-view", shell.settings.string(ShellSettings.padHold(2)))
    }

    @Test
    fun `stored settings that no longer validate fall back to defaults`() {
        val stored = JSONObject()
            .put("display.brightness", 9)
            .put("follow.marginDegrees", 12)
            .put("gone", true)
        val settings = ShellSettings(MemoryStore(stored.toString()), SCHEMA)

        assertEquals(1.0, settings.number(ShellSettings.DISPLAY_BRIGHTNESS), 0.0)
        assertEquals(12.0, settings.number(ShellSettings.FOLLOW_MARGIN_DEGREES), 0.0)
        assertEquals("avoid-all", ShellSettings(MemoryStore("{broken"), SCHEMA).string(ShellSettings.COLLISION_POLICY))
    }

    @Test
    fun `the registry parses the shipped format`() {
        val apps = AppInfo.parseRegistry(
            """[{"id":"files","name":"Launcher","description":"d","icon":"🏠","entry":"/launcher/index.html","widthPx":1280,"heightPx":800}]""",
        )
        assertEquals(listOf(AppInfo("files", "Launcher", "d", "🏠", "/launcher/index.html", 1280, 800)), apps)
    }

    @Test
    fun `the dock can pin a plugin app with a long id, such as a phone app's component name`() {
        val settings = ShellSettings(MemoryStore(), SCHEMA)
        val id = "dev.halone.plugin.android/com.android.chrome/com.google.android.apps.chrome.Main"
        settings.set(ShellSettings.DOCK_PINNED_APPS, listOf("files", id))
        assertEquals(listOf("files", id), settings.strings(ShellSettings.DOCK_PINNED_APPS))
    }

    @Test
    fun `a registry entry may carry an icon image and a tile colour`() {
        val app = AppInfo.parseRegistry(
            """[{"id":"files","name":"Files","icon":"","iconUrl":"/icons/apps/files.svg","color":"#224466","entry":"/files/index.html","widthPx":1280,"heightPx":800}]""",
        ).single()
        assertEquals("/icons/apps/files.svg", app.iconUrl)
        assertEquals("#224466", app.toJson().getString("color"))
    }
}
