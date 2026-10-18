package dev.halone.shell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppsTest {
    private val store = MemoryStore()
    private val apps = NativeApps(store, 1)
    private val scene = Scene()
    private val platform = FakePlatform(scene)
    private val shell = Shell(scene, APPS, ShellSettings(MemoryStore(), SCHEMA), platform, nativeApps = apps)

    private fun plugin(certificate: String = "aa", contract: Int = 1, packageName: String = "dev.example.plugin") =
        PluginPackage(packageName, "$packageName.Provider", "Example", "1.0", contract, certificate)

    private fun app(id: String, name: String = id.replaceFirstChar(Char::uppercase), hidden: Boolean = false) =
        ProvidedApp(id, name, "", 900, 700, hidden, "1")

    private fun enabledWith(vararg provided: ProvidedApp): PluginPackage {
        val found = plugin()
        apps.update(listOf(found))
        apps.setEnabled(found.packageName, true)
        apps.setApps(found.packageName, provided.toList())
        return found
    }

    @Test
    fun `a reinstall of the same version counts as a change`() {
        enabledWith(app("views"))
        var changes = 0
        apps.onChange = { changes++ }

        apps.update(listOf(plugin()))
        apps.update(listOf(plugin().copy(updatedAt = 42)))

        assertEquals(1, changes)
        assertEquals(PluginState.ENABLED, apps.stateOf("dev.example.plugin"))
    }

    @Test
    fun `a plugin found for the first time is disabled, runs nothing and is announced`() {
        apps.update(listOf(plugin()))

        assertEquals(PluginState.DISABLED, apps.stateOf("dev.example.plugin"))
        assertTrue(apps.runnable().isEmpty())
        assertEquals(listOf("New plugin “Example”: turn it on in Settings → Plugins"), platform.notices)

        apps.update(listOf(plugin()))
        assertEquals(1, platform.notices.size)
    }

    @Test
    fun `apps listed before the plugin is enabled are ignored`() {
        apps.update(listOf(plugin()))
        apps.setApps("dev.example.plugin", listOf(app("pattern")))

        assertTrue(apps.list().isEmpty())
    }

    @Test
    fun `an enabled plugin's apps join the launcher with an icon served by the shell`() {
        enabledWith(app("pattern", "Test pattern"), app("secret", hidden = true))

        val info = shell.apps.first { it.id == "dev.example.plugin/pattern" }
        assertEquals(AppKind.NATIVE, info.kind)
        assertEquals("Test pattern", info.name)
        assertEquals("dev.example.plugin", info.provider)
        assertEquals("https://halone.local/native-icons/dev.example.plugin/pattern/1.png", info.iconUrl)
        assertTrue(shell.apps.first { it.id == "dev.example.plugin/secret" }.hidden)
    }

    @Test
    fun `a different certificate refuses the plugin until it is enabled again`() {
        enabledWith(app("pattern"))
        apps.update(listOf(plugin(certificate = "bb")))

        assertEquals(PluginState.CERTIFICATE_CHANGED, apps.stateOf("dev.example.plugin"))
        assertTrue(apps.runnable().isEmpty())
        assertTrue(apps.list().isEmpty())

        apps.setEnabled("dev.example.plugin", true)
        assertEquals(PluginState.ENABLED, apps.stateOf("dev.example.plugin"))
    }

    @Test
    fun `a plugin built for a newer contract cannot be enabled`() {
        apps.update(listOf(plugin(contract = 99)))

        assertEquals(PluginState.INCOMPATIBLE, apps.stateOf("dev.example.plugin"))
        assertEquals("incompatible", assertThrows(HaloneException::class.java) { apps.setEnabled("dev.example.plugin", true) }.code)
    }

    @Test
    fun `the enablement and the last apps listed survive a restart, and a plugin gone is forgotten`() {
        enabledWith(app("pattern"))

        val again = NativeApps(store, 1)
        again.update(listOf(plugin()))
        assertEquals(listOf("dev.example.plugin/pattern"), again.list().map { it.id })

        again.update(emptyList())
        again.update(listOf(plugin()))
        assertEquals(PluginState.DISABLED, again.stateOf("dev.example.plugin"))
    }

    @Test
    fun `a plugin's tile colour reaches the launcher and survives a restart`() {
        enabledWith(app("pattern").copy(color = "#5b2a86"), app("views"))
        assertEquals(listOf("#5b2a86", null), apps.list().map { it.toAppInfo().color })

        val again = NativeApps(store, 1)
        again.update(listOf(plugin()))
        assertEquals("#5b2a86", again.list().first().toAppInfo().color)
    }

    @Test
    fun `launching focuses the app's window, and the plugin can ask for another`() {
        enabledWith(app("views"))

        val first = shell.launch("dev.example.plugin/views")
        assertEquals(WindowKind.NATIVE, first.kind)
        assertEquals("Views", first.title)
        assertEquals(900, first.panel.widthPx)
        assertSame(first, shell.launch("dev.example.plugin/views"))

        val second = shell.openNativePanel("dev.example.plugin/views", null)
        assertNotSame(first, second)
        assertEquals(2, shell.windows().size)
    }

    @Test
    fun `a plugin launching a hidden app opens it once, then focuses it`() {
        enabledWith(app("views"), app("phone.app", hidden = true))

        val first = shell.launchNative("dev.example.plugin/phone.app", null)
        assertSame(first, shell.launchNative("dev.example.plugin/phone.app", null))
        assertEquals(1, shell.windows().size)
        assertEquals(listOf(first.id), platform.reopened)
        assertEquals("no-such-app", assertThrows(HaloneException::class.java) { shell.launchNative("dev.example.plugin/gone", null) }.code)
    }

    @Test
    fun `disabling a plugin closes its windows and takes its apps away`() {
        enabledWith(app("views"))
        shell.launch("dev.example.plugin/views")
        shell.launch("files")

        shell.setPluginEnabled("dev.example.plugin", false)

        assertEquals(listOf("files"), shell.windows().map { it.appId })
        assertTrue(shell.apps.none { it.kind == AppKind.NATIVE })
    }

    @Test
    fun `a plugin's apps cannot be uninstalled from the launcher`() {
        enabledWith(app("views"))

        assertEquals("not-removable", assertThrows(HaloneException::class.java) { shell.uninstall("dev.example.plugin/views") }.code)
    }

    private fun status(id: String, text: String = "") = ProvidedStatusItem(id, byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), "Signal", text, true)

    @Test
    fun `status items are listed for the dock, replaced by id, capped per plugin, and clicks reach their plugin`() {
        enabledWith(app("views"))
        val host = BridgeHost(shell, SystemInfo("test", "Test Glasses", 19.0, 1920, 1080))
        val dock = FakeChannel().also { host.connect("dock", it) }
        fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
            dock.receive(JSONObject().put("type", "req").put("id", 1).put("method", method).put("params", params).toString())
            return JSONObject(dock.posted.last { JSONObject(it).optString("type") == "res" })
        }

        shell.statusItems.set("dev.example.plugin", status("wifi", "3"))
        shell.statusItems.set("dev.example.plugin", status("wifi", "4"))
        (1..6).forEach { shell.statusItems.set("dev.example.plugin", status("extra$it")) }

        val listed = call("status.list").getJSONArray("result")
        assertEquals(StatusItems.MAX_PER_PROVIDER, listed.length())
        val wifi = listed.getJSONObject(0)
        assertEquals("dev.example.plugin/wifi", wifi.getString("id"))
        assertEquals("4", wifi.getString("text"))
        assertTrue(wifi.getString("iconUrl").startsWith("data:image/png;base64,"))
        assertTrue(dock.posted.any { JSONObject(it).optString("name") == "status.changed" })

        assertTrue(call("status.click", JSONObject().put("id", "dev.example.plugin/wifi")).getBoolean("ok"))
        assertEquals(listOf("dev.example.plugin/wifi"), platform.statusClicks)
        assertEquals("no-such-item", call("status.click", JSONObject().put("id", "nope")).getJSONObject("error").getString("code"))
    }

    @Test
    fun `disabling a plugin takes its status items away`() {
        enabledWith(app("views"))
        shell.statusItems.set("dev.example.plugin", status("wifi"))

        shell.setPluginEnabled("dev.example.plugin", false)

        assertTrue(shell.statusItems.list().isEmpty())
    }

    @Test
    fun `settings lists the plugins and turns them on and off over the bridge`() {
        apps.update(listOf(plugin()))
        val host = BridgeHost(shell, SystemInfo("test", "Test Glasses", 19.0, 1920, 1080))
        val settings = FakeChannel().also { host.connect(shell.launch("settings").id, it) }
        fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
            settings.receive(JSONObject().put("type", "req").put("id", 1).put("method", method).put("params", params).toString())
            return JSONObject(settings.posted.last { JSONObject(it).optString("type") == "res" })
        }

        val listed = call("plugins.list").getJSONArray("result").getJSONObject(0)
        assertEquals("dev.example.plugin", listed.getString("packageName"))
        assertEquals("disabled", listed.getString("state"))

        assertTrue(call("plugins.setEnabled", JSONObject().put("packageName", "dev.example.plugin").put("enabled", true)).getBoolean("ok"))
        assertEquals(PluginState.ENABLED, apps.stateOf("dev.example.plugin"))
        val event = settings.posted.map { JSONObject(it) }.last { it.optString("name") == "plugins.changed" }
        assertEquals("enabled", event.getJSONArray("data").getJSONObject(0).getString("state"))

        val unknown = call("plugins.setEnabled", JSONObject().put("packageName", "dev.example.other").put("enabled", true))
        assertEquals("no-such-plugin", unknown.getJSONObject("error").getString("code"))
    }
}
