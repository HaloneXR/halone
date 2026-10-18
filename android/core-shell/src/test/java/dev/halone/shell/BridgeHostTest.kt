package dev.halone.shell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHostTest {
    private val scene = Scene()
    private val platform = FakePlatform(scene)
    private val shell = Shell(scene, APPS, ShellSettings(MemoryStore(), SCHEMA), platform)
    private val host = BridgeHost(shell, SystemInfo("test", "Test Glasses", 19.0, 1920, 1080))
    private var nextId = 1

    private fun connected(appId: String): Pair<ShellWindow<FakePanel>, FakeChannel> {
        val window = shell.launch(appId)
        val channel = FakeChannel()
        host.connect(window.id, channel)
        return window to channel
    }

    private fun FakeChannel.call(method: String, params: JSONObject? = null): JSONObject {
        val id = nextId++
        val request = JSONObject().put("type", "req").put("id", id).put("method", method)
        params?.let { request.put("params", it) }
        receive(request.toString())
        return posted.map { JSONObject(it) }.last { it.optString("type") == "res" && it.getInt("id") == id }
    }

    private fun FakeChannel.events(name: String) =
        posted.map { JSONObject(it) }.filter { it.optString("type") == "evt" && it.getString("name") == name }

    @Test
    fun `the connect message names the window and the protocol`() {
        val message = JSONObject(host.connect("w9", FakeChannel()))

        assertEquals("connect", message.getString("halone"))
        assertEquals(Protocol.VERSION, message.getInt("protocolVersion"))
        assertEquals("w9", message.getString("windowId"))
    }

    @Test
    fun `a request is answered with its id`() {
        val (_, channel) = connected("files")

        val response = channel.call("system.info")

        assertTrue(response.getBoolean("ok"))
        val result = response.getJSONObject("result")
        assertEquals("android", result.getString("platform"))
        assertEquals(19.0, result.getJSONObject("device").getDouble("verticalFovDegrees"), 0.0)
    }

    @Test
    fun `hidden apps are not listed`() {
        val (_, channel) = connected("files")

        val apps = channel.call("apps.list").getJSONArray("result")

        assertEquals(listOf("files", "settings"), (0 until apps.length()).map { apps.getJSONObject(it).getString("id") })
    }

    @Test
    fun `a window acts on itself when it names no window`() {
        val (launcher, _) = connected("files")
        val (settings, channel) = connected("settings")

        assertEquals(settings.id, channel.call("windows.self").getJSONObject("result").getString("id"))
        channel.call("windows.close")

        assertEquals(listOf(launcher.id), shell.windows().map { it.id })
    }

    @Test
    fun `a window cannot set another window's title`() {
        val (launcher, _) = connected("files")
        val (_, channel) = connected("settings")

        channel.call("windows.setTitle", JSONObject().put("title", "Hijacked").put("id", launcher.id))

        assertEquals("Files", launcher.title)
    }

    @Test
    fun `errors carry their code`() {
        val (_, channel) = connected("files")

        val unknown = channel.call("nope.nothing")
        val badApp = channel.call("apps.launch", JSONObject().put("appId", "nope"))
        val badParams = channel.call("apps.launch", JSONObject().put("appId", 3))
        val badSetting = channel.call("settings.set", JSONObject().put("key", "display.brightness").put("value", 7))

        assertEquals("unknown-method", unknown.getJSONObject("error").getString("code"))
        assertEquals("no-such-app", badApp.getJSONObject("error").getString("code"))
        assertEquals("invalid-params", badParams.getJSONObject("error").getString("code"))
        assertEquals("invalid-setting", badSetting.getJSONObject("error").getString("code"))
        assertFalse(badApp.getBoolean("ok"))
    }

    @Test
    fun `void methods answer with null`() {
        val (_, channel) = connected("files")

        val response = channel.call("scene.recenter")

        assertTrue(response.getBoolean("ok"))
        assertTrue(response.isNull("result"))
        assertEquals(1, platform.recentred)
    }

    @Test
    fun `a settings change reaches every window`() {
        val (_, launcher) = connected("files")
        val (_, settings) = connected("settings")

        settings.call("settings.set", JSONObject().put("key", "layout.slotSpacingDegrees").put("value", 45))

        for (channel in listOf(launcher, settings)) {
            val event = channel.events("settings.changed").single()
            assertEquals(45.0, event.getJSONObject("data").getDouble("layout.slotSpacingDegrees"), 0.0)
        }
    }

    @Test
    fun `opening a window tells the others`() {
        val (_, channel) = connected("files")

        channel.call("windows.openUrl", JSONObject().put("url", "https://example.com"))

        val latest = channel.events("windows.changed").last().getJSONArray("data")
        assertEquals(2, latest.length())
        assertEquals("web", latest.getJSONObject(1).getString("kind"))
    }

    @Test
    fun `focus moves are reported to the two windows involved`() {
        val (launcher, launcherChannel) = connected("files")
        val (_, settingsChannel) = connected("settings")
        shell.poll()
        assertEquals(true, launcherChannel.events("window.focusChanged").last().getJSONObject("data").getBoolean("focused"))

        shell.close(launcher.id)

        assertEquals(true, settingsChannel.events("window.focusChanged").last().getJSONObject("data").getBoolean("focused"))
    }

    @Test
    fun `reconnecting replaces the old channel`() {
        val (window, first) = connected("files")
        val second = FakeChannel()

        host.connect(window.id, second)

        assertTrue(first.closed)
        assertEquals(setOf(window.id), host.connected())
    }

    @Test
    fun `a closed window's channel is closed`() {
        val (window, channel) = connected("files")

        shell.close(window.id)

        assertTrue(channel.closed)
        assertTrue(host.connected().isEmpty())
    }

    @Test
    fun `anything that is not a request is ignored`() {
        assertNull(host.handle("w1", "not json"))
        assertNull(host.handle("w1", JSONObject().put("type", "evt").toString()))
    }

    @Test
    fun `a web window's chrome drives its tabs, and only it hears about them`() {
        val web = shell.openUrl("https://example.com/")
        val chrome = FakeChannel()
        host.connect(web.id, chrome)
        val (_, other) = connected("settings")
        val browser = platform.browsers.getValue(web.id)

        assertEquals("https://example.com/", chrome.call("browser.state").getJSONObject("result").getJSONArray("tabs").getJSONObject(0).getString("url"))
        chrome.call("browser.navigate", JSONObject().put("url", "https://halone.dev/"))
        chrome.call("browser.back")
        assertEquals(listOf("navigate https://halone.dev/", "back"), browser.calls)
        assertEquals("https://halone.dev/", web.url)

        browser.titled("halone")
        assertEquals("halone", web.title)
        assertEquals("halone", web.panel.title)
        assertTrue(chrome.events("browser.changed").isNotEmpty())
        assertTrue(other.events("browser.changed").isEmpty())

        assertEquals("not-a-browser", other.call("browser.state").getJSONObject("error").getString("code"))
    }

    @Test
    fun `closing the last tab closes the window`() {
        val web = shell.openUrl("https://example.com/")
        val chrome = FakeChannel()
        host.connect(web.id, chrome)
        chrome.call("browser.newTab")
        assertEquals(2, platform.browsers.getValue(web.id).tabs.size)

        chrome.call("browser.closeTab", JSONObject().put("tabId", 2))
        assertEquals(listOf(web.id), shell.windows().map { it.id })
        chrome.call("browser.closeTab", JSONObject().put("tabId", 1))
        assertTrue(shell.windows().isEmpty())
    }
}
