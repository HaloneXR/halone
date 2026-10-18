package dev.halone.shell

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppsTest {
    private fun manifest(
        name: String? = "Squoosh",
        startUrl: String? = "https://squoosh.app/?utm=pwa",
        scope: String? = "https://squoosh.app/",
        display: String? = "standalone",
    ) = JSONObject().apply {
        name?.let { put("name", it) }
        startUrl?.let { put("start_url", it) }
        scope?.let { put("scope", it) }
        display?.let { put("display", it) }
    }

    private fun icon(src: String, sizes: Any?, purpose: Any? = null, type: String? = null) = JSONObject().apply {
        put("src", src)
        sizes?.let { put("sizes", it) }
        purpose?.let { put("purpose", it) }
        type?.let { put("type", it) }
    }

    @Test
    fun `a manifest with a name, a start in scope and an app display can be installed`() {
        val parsed = WebManifest.parse(manifest().put("theme_color", "#ff3385"))!!
        assertEquals("Squoosh", parsed.name)
        assertEquals("https://squoosh.app/", parsed.scope)
        assertEquals("standalone", parsed.display)
        assertEquals("#ff3385", parsed.themeColor)
    }

    @Test
    fun `a browser display, no name, or a start outside the scope cannot`() {
        assertNull(WebManifest.parse(manifest(display = "browser")))
        assertNull(WebManifest.parse(manifest(name = null)))
        assertNull(WebManifest.parse(manifest(startUrl = "https://squoosh.app/", scope = "https://squoosh.app/app/")))
        assertNull(WebManifest.parse(manifest(startUrl = "file:///index.html")))
    }

    @Test
    fun `a display override can make a browser display installable, and a short name stands in for the name`() {
        val parsed = WebManifest.parse(
            manifest(name = null, display = "browser").put("short_name", "Sq").put("display_override", JSONArray(listOf("tabbed", "minimal-ui"))),
        )!!
        assertEquals("Sq", parsed.name)
        assertEquals("minimal-ui", parsed.display)
    }

    @Test
    fun `without a scope the start URL's directory is the scope`() {
        assertEquals("https://example.com/app/", WebManifest.parse(manifest(startUrl = "https://example.com/app/index.html?x=1", scope = null))!!.scope)
    }

    @Test
    fun `the id is the same whatever the start URL when the manifest has one, and differs between apps`() {
        val first = WebManifest.parse(manifest().put("id", "https://squoosh.app/"))!!
        val moved = WebManifest.parse(manifest(startUrl = "https://squoosh.app/new").put("id", "https://squoosh.app/"))!!
        val other = WebManifest.parse(manifest(startUrl = "https://example.com/", scope = "https://example.com/"))!!
        assertEquals(first.appId, moved.appId)
        assertFalse(first.appId == other.appId)
        assertTrue(first.appId.startsWith("web-"))
    }

    @Test
    fun `the icon kept is the smallest any-purpose bitmap of at least 192, or else the largest`() {
        val icons = JSONArray(
            listOf(
                icon("https://a/48.png", "48x48"),
                icon("https://a/512.png", JSONArray(listOf("512x512"))),
                icon("https://a/256.png", "256x256"),
                icon("https://a/mask.png", "192x192", purpose = "maskable"),
                icon("https://a/vector.svg", "any", type = "image/svg+xml"),
            ),
        )
        assertEquals("https://a/256.png", WebManifest.parse(manifest().put("icons", icons))!!.bestIcon()!!.src)

        val small = JSONArray(listOf(icon("https://a/48.png", "48x48"), icon("https://a/96.png", "96x96 72x72", purpose = "any maskable")))
        assertEquals("https://a/96.png", WebManifest.parse(manifest().put("icons", small))!!.bestIcon()!!.src)
    }

    @Test
    fun `installing keeps the icon and the first install time, and reinstalling replaces the rest`() {
        val store = MemoryStore()
        var now = 100L
        val loaded = mutableListOf<String>()
        val apps = WebApps(store, loadIcon = { url, done -> loaded += url; done("data:image/png;base64,AAAA") }, clock = { now })
        val parsed = WebManifest.parse(manifest().put("icons", JSONArray(listOf(icon("https://squoosh.app/icon.png", "192x192")))))!!

        var installed: WebApp? = null
        apps.install(parsed) { installed = it }
        assertEquals(listOf("https://squoosh.app/icon.png"), loaded)
        assertEquals("data:image/png;base64,AAAA", installed!!.iconUrl)

        now = 200L
        apps.install(parsed.copy(name = "Squoosh 2", icons = emptyList())) {}
        val again = apps.list().single()
        assertEquals("Squoosh 2", again.name)
        assertEquals("data:image/png;base64,AAAA", again.iconUrl)
        assertEquals(100L, again.installedAt)

        assertEquals(again, WebApps(store).list().single())
    }

    private val scene = Scene()
    private val platform = FakePlatform(scene)
    private val store = WebApps(MemoryStore())
    private val shell = Shell(scene, APPS, ShellSettings(MemoryStore(), SCHEMA), platform, store)

    private fun installed(): WebApp {
        var app: WebApp? = null
        store.install(WebManifest.parse(manifest())!!) { app = it }
        return app!!
    }

    @Test
    fun `an installed app is listed after the system apps and opens its start URL in a window of its own`() {
        val app = installed()
        assertEquals(app.id, shell.apps.last().id)
        assertEquals(AppKind.WEB, shell.apps.last().kind)

        val window = shell.launch(app.id)
        assertEquals(WindowKind.APP, window.kind)
        assertEquals(app.id, window.appId)
        assertEquals("Squoosh", window.title)
        assertEquals(window.id to app.startUrl, platform.opened.last())
    }

    @Test
    fun `launching it again focuses the window, going to a route inside the app but not outside`() {
        val app = installed()
        val window = shell.launch(app.id)
        assertEquals(window, shell.launch(app.id, "https://squoosh.app/editor"))
        assertEquals(window, shell.launch(app.id, "https://evil.example/"))
        assertEquals(listOf("https://squoosh.app/editor"), platform.webApps.getValue(window.id).navigated)
        assertEquals(1, shell.windows().size)
    }

    @Test
    fun `a page outside the app shows its site in the title bar`() {
        val window = shell.launch(installed().id)
        platform.webApps.getValue(window.id).navigate("https://accounts.example.com/login")
        assertEquals("Squoosh — accounts.example.com", window.title)
        platform.webApps.getValue(window.id).navigate("https://squoosh.app/done")
        assertEquals("Squoosh", window.title)
    }

    @Test
    fun `installing from a web window needs a manifest that can be installed`() {
        val window = shell.openUrl("https://squoosh.app/")
        val browser = platform.browsers.getValue(window.id)
        assertNull(shell.installableApp(window.id))
        browser.manifest = manifest(display = "browser")
        assertNull(shell.installableApp(window.id))

        browser.manifest = manifest()
        var app: WebApp? = null
        shell.installWebApp(window.id) { app = it.getOrThrow() }
        assertNotNull(store.find(app!!.id))
        assertEquals(listOf("Added “Squoosh” to the launcher"), platform.notices)
    }

    @Test
    fun `uninstalling closes its window and unpins it, and system apps cannot be uninstalled`() {
        val app = installed()
        val window = shell.launch(app.id)
        shell.setSetting(ShellSettings.DOCK_PINNED_APPS, listOf("files", app.id))

        shell.uninstall(app.id)
        assertTrue(shell.windows().none { it.id == window.id })
        assertEquals(listOf("files"), shell.settings.strings(ShellSettings.DOCK_PINNED_APPS))
        assertTrue(store.list().isEmpty())

        val error = runCatching { shell.uninstall("files") }.exceptionOrNull() as HaloneException
        assertEquals("not-removable", error.code)
    }

    @Test
    fun `the bridge installs the page, tells the chrome it is installed, and tells every window the apps changed`() {
        val host = BridgeHost(shell, SystemInfo("test", "Test Glasses", 19.0, 1920, 1080))
        val window = shell.openUrl("https://squoosh.app/")
        platform.browsers.getValue(window.id).manifest = manifest()
        val chrome = FakeChannel().also { host.connect(window.id, it) }

        fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
            chrome.receive(JSONObject().put("type", "req").put("id", 1).put("method", method).put("params", params).toString())
            return JSONObject(chrome.posted.last { JSONObject(it).optString("type") == "res" })
        }

        val before = call("browser.state").getJSONObject("result").getJSONObject("webApp")
        assertFalse(before.getBoolean("installed"))

        val installedApp = call("browser.installApp").getJSONObject("result")
        assertEquals("web", installedApp.getString("kind"))
        assertTrue(chrome.posted.any { JSONObject(it).optString("name") == "apps.changed" })
        val after = chrome.posted.map { JSONObject(it) }.last { it.optString("name") == "browser.changed" }
        assertTrue(after.getJSONObject("data").getJSONObject("webApp").getBoolean("installed"))

        call("apps.uninstall", JSONObject().put("appId", installedApp.getString("id")))
        assertTrue(store.list().isEmpty())
    }
}
