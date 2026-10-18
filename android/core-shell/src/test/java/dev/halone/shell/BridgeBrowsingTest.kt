package dev.halone.shell

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeBrowsingTest {
    private val base: File = Files.createTempDirectory("halone-browsing").toFile().canonicalFile
    private val apps = APPS + AppInfo("menu", "Menu", "", "", "/menu/index.html", 320, 400, hidden = true)
    private val scene = Scene()
    private val platform = FakePlatform(scene)
    private val shell = Shell(scene, apps, ShellSettings(MemoryStore(), SCHEMA), platform)
    private val browsing = BrowserData(BrowserHistory(MemoryStore()), Bookmarks(MemoryStore()), Downloads(MemoryStore()))
    private val written = mutableListOf<String>()
    private val files = SharedFiles({ listOf(FileRoot("storage", "Storage", base.path)) }, FakeAccess()).also {
        it.onWritten = { paths -> written += paths }
    }
    private val host = BridgeHost(shell, SystemInfo("test", "Test Glasses", 19.0, 1920, 1080), files = files, browsing = browsing)
    private var nextId = 1

    private fun connect(windowId: String) = FakeChannel().also { host.connect(windowId, it) }

    private fun FakeChannel.send(method: String, params: JSONObject? = null): Int {
        val id = nextId++
        val request = JSONObject().put("type", "req").put("id", id).put("method", method)
        params?.let { request.put("params", it) }
        receive(request.toString())
        return id
    }

    private fun FakeChannel.response(id: Int): JSONObject? =
        posted.map { JSONObject(it) }.lastOrNull { it.optString("type") == "res" && it.getInt("id") == id }

    private fun FakeChannel.call(method: String, params: JSONObject? = null): JSONObject = response(send(method, params))!!

    private fun FakeChannel.events(name: String) =
        posted.map { JSONObject(it) }.filter { it.optString("type") == "evt" && it.getString("name") == name }

    private fun items(vararg labels: String) = JSONArray(labels.map { JSONObject().put("id", it.lowercase()).put("label", it) })

    @Test
    fun `a menu answers only once the wearer chooses, after the overlay has drawn it`() {
        val window = shell.launch("files")
        val app = connect(window.id)
        val overlay = connect(Shell.MENU)

        val asked = app.send("menu.show", JSONObject().put("items", items("Copy", "Paste")).put("xPx", 100).put("yPx", 50))
        assertEquals(null, app.response(asked))
        val shown = overlay.events("menu.changed").last().getJSONObject("data")
        assertEquals("[prepare 240x112]", platform.menuCalls.toString())

        overlay.call("menu.rendered", JSONObject().put("menuId", shown.getInt("menuId")))
        assertEquals("show ${window.id} at 100,50", platform.menuCalls.last())
        overlay.call("menu.choose", JSONObject().put("menuId", shown.getInt("menuId")).put("itemId", "paste"))

        assertEquals("paste", app.response(asked)!!.getJSONObject("result").getString("itemId"))
        assertEquals("hide", platform.menuCalls.last())
        assertTrue(overlay.events("menu.changed").last().isNull("data"))
    }

    @Test
    fun `a second menu dismisses the first with no choice`() {
        val window = shell.launch("files")
        val app = connect(window.id)
        val first = app.send("menu.show", JSONObject().put("items", items("Copy")).put("xPx", 0).put("yPx", 0))
        app.send("menu.show", JSONObject().put("items", items("Paste")).put("xPx", 0).put("yPx", 0))

        assertTrue(app.response(first)!!.getJSONObject("result").isNull("itemId"))
        assertEquals("paste", shell.menu!!.items.single().id)
    }

    @Test
    fun `closing the window under a menu dismisses it`() {
        val window = shell.launch("files")
        val app = connect(window.id)
        val asked = app.send("menu.show", JSONObject().put("items", items("Copy")).put("xPx", 0).put("yPx", 0))
        shell.close(window.id)

        assertEquals(null, shell.menu)
        assertTrue(platform.menuCalls.contains("hide"))
        assertTrue(app.response(asked)!!.getJSONObject("result").isNull("itemId"))
    }

    @Test
    fun `a disabled item cannot be chosen`() {
        val window = shell.launch("files")
        val items = JSONArray().put(JSONObject().put("id", "paste").put("label", "Paste").put("disabled", true))
        connect(window.id).send("menu.show", JSONObject().put("items", items).put("xPx", 0).put("yPx", 0))
        val overlay = connect(Shell.MENU)

        val response = overlay.call("menu.choose", JSONObject().put("menuId", shell.menu!!.menuId).put("itemId", "paste"))
        assertFalse(response.getBoolean("ok"))
    }

    @Test
    fun `files on the clipboard last only while its text is theirs`() {
        val app = connect(shell.launch("files").id)
        app.call("clipboard.writeFiles", JSONObject().put("paths", JSONArray(listOf("/a", "/b"))).put("cut", true))
        val read = app.call("clipboard.read").getJSONObject("result")
        assertEquals("/a\n/b", read.getString("text"))
        assertTrue(read.getJSONObject("files").getBoolean("cut"))

        platform.clipboard = "copied in a web page"
        assertTrue(app.call("clipboard.read").getJSONObject("result").isNull("files"))
    }

    @Test
    fun `copying into the same folder numbers the copy, and the new file is reported`() {
        File(base, "a.txt").writeText("a")
        val app = connect(shell.launch("files").id)
        val id = app.send("files.copy", JSONObject().put("paths", JSONArray(listOf(File(base, "a.txt").path))).put("to", base.path))

        val copy = app.response(id)!!.getJSONArray("result").getJSONObject(0)
        assertEquals("a (2).txt", copy.getString("name"))
        assertEquals("a", File(base, "a (2).txt").readText())
        assertEquals(listOf(File(base, "a (2).txt").path), written)
    }

    @Test
    fun `a folder cannot be moved into itself`() {
        val folder = File(base, "f").apply { mkdirs() }
        val inner = File(folder, "inner").apply { mkdirs() }
        val app = connect(shell.launch("files").id)

        val response = app.response(app.send("files.move", JSONObject().put("paths", JSONArray(listOf(folder.path))).put("to", inner.path)))!!
        assertFalse(response.getBoolean("ok"))
        assertTrue(folder.isDirectory)
    }

    @Test
    fun `renaming refuses a name that is taken and a name with a slash`() {
        File(base, "a.txt").writeText("a")
        File(base, "b.txt").writeText("b")
        val app = connect(shell.launch("files").id)

        assertEquals("exists", app.call("files.rename", JSONObject().put("path", File(base, "a.txt").path).put("name", "b.txt")).getJSONObject("error").getString("code"))
        assertEquals("invalid-params", app.call("files.rename", JSONObject().put("path", File(base, "a.txt").path).put("name", "x/y")).getJSONObject("error").getString("code"))
        assertEquals("c.txt", app.call("files.rename", JSONObject().put("path", File(base, "a.txt").path).put("name", "c.txt")).getJSONObject("result").getString("name"))
    }

    @Test
    fun `a storage root cannot be deleted`() {
        val app = connect(shell.launch("files").id)
        val response = app.response(app.send("files.delete", JSONObject().put("paths", JSONArray(listOf(base.path)))))!!

        assertFalse(response.getBoolean("ok"))
        assertTrue(base.isDirectory)
    }

    @Test
    fun `launching a running app with a route tells it where to go`() {
        val window = shell.launch("settings", "browser")
        val app = connect(window.id)
        assertEquals("browser", app.call("windows.route").getJSONObject("result").getString("route"))

        shell.launch("settings", "gestures")
        assertEquals("gestures", app.events("window.routed").last().getJSONObject("data").getString("route"))
    }

    @Test
    fun `a page opened from elsewhere goes into the web window used last`() {
        val web = shell.openUrl("https://a.example/")
        scene.focus(web.panel)
        shell.poll()
        val launcher = connect(shell.launch("files").id)

        val opened = launcher.call("browser.open", JSONObject().put("url", "https://b.example/").put("private", true))
        assertEquals(web.id, opened.getJSONObject("result").getString("id"))
        val tab = platform.browsers.getValue(web.id).tabs.last()
        assertEquals("https://b.example/", tab.url)
        assertTrue(tab.private)
    }

    @Test
    fun `with no web window open, a private page opens a private window`() {
        val app = connect(shell.launch("files").id)
        val opened = app.call("browser.open", JSONObject().put("url", "https://b.example/").put("private", true))

        assertEquals(listOf(opened.getJSONObject("result").getString("id")), platform.privateOpened)
    }

    @Test
    fun `the chrome shows whether its page is bookmarked, and hears when that changes`() {
        val web = shell.openUrl("https://a.example/")
        val chrome = connect(web.id)
        assertFalse(chrome.call("browser.state").getJSONObject("result").getBoolean("bookmarked"))

        chrome.call("browser.toggleBookmark")
        assertTrue(chrome.events("browser.changed").last().getJSONObject("data").getBoolean("bookmarked"))
        assertEquals("https://a.example/", browsing.bookmarks.list().single().url)

        chrome.call("browser.toggleBookmark")
        assertTrue(browsing.bookmarks.list().isEmpty())
    }

    @Test
    fun `clearing browsing data clears history here and the rest in the platform`() {
        browsing.history.visited("https://a.example/")
        val app = connect(shell.launch("files").id)
        app.call("browser.clearData", JSONObject().put("history", true).put("cookies", true).put("cache", false))

        assertTrue(browsing.history.list().isEmpty())
        assertEquals(listOf(true to false), platform.cleared)
    }

    @Test
    fun `the chrome sees a page's permission prompt and answers it`() {
        val web = shell.openUrl("https://a.example/")
        val chrome = connect(web.id)
        val browser = platform.browsers.getValue(web.id)
        browser.prompt = BrowserPrompt(7, "microphone", "a.example", rememberable = false)

        val prompt = chrome.call("browser.state").getJSONObject("result").getJSONObject("prompt")
        assertEquals("microphone", prompt.getString("kind"))
        chrome.call("browser.answerPrompt", JSONObject().put("id", 7).put("allow", true).put("remember", false))

        assertEquals(listOf(Triple(7, true, false)), browser.answers)
        assertTrue(chrome.events("browser.changed").last().getJSONObject("data").isNull("prompt"))
    }

    private fun dialogSpec(kind: String = "text") = JSONObject()
        .put("kind", kind)
        .put("title", "Name?")
        .put("message", "What should it be called?")
        .put("buttons", JSONArray().put(JSONObject().put("id", "cancel").put("label", "Cancel")).put(JSONObject().put("id", "ok").put("label", "OK")))

    @Test
    fun `a dialog is centred over its window, modal, and hands back what was typed`() {
        val window = shell.launch("files")
        val overlay = connect(Shell.MENU)
        var answered: Pair<String?, JSONObject>? = null
        val request = shell.showDialog(window.id, dialogSpec()) { button, answer -> answered = button to answer }

        val (width, height) = DialogLayout.size(dialogSpec())
        assertEquals("prepare ${width}x$height", platform.menuCalls.last())
        assertEquals((1280 - width) / 2f, request.xPx, 0.5f)
        assertTrue(request.modal)
        assertTrue(overlay.events("menu.changed").last().getJSONObject("data").getJSONObject("dialog").getString("kind") == "text")

        overlay.call("menu.choose", JSONObject().put("menuId", request.menuId).put("itemId", "ok").put("answer", JSONObject().put("text", "Report")))
        assertEquals("ok", answered!!.first)
        assertEquals("Report", answered!!.second.getString("text"))
    }

    @Test
    fun `a dialog only takes its own buttons`() {
        val window = shell.launch("files")
        val request = shell.showDialog(window.id, dialogSpec()) { _, _ -> }
        val response = connect(Shell.MENU).call("menu.choose", JSONObject().put("menuId", request.menuId).put("itemId", "delete-everything"))

        assertFalse(response.getBoolean("ok"))
        assertEquals(request, shell.menu)
    }

    @Test
    fun `a long message scrolls past a height instead of growing the dialog`() {
        val short = DialogLayout.size(dialogSpec("message")).second
        val long = DialogLayout.size(dialogSpec("message").put("message", "word ".repeat(2000))).second

        assertTrue(long > short)
        assertTrue(long <= DialogLayout.MAX_HEIGHT_PX)
    }

    @Test
    fun `a long dropdown scrolls rather than leaving the window`() {
        val items = (1..200).map { MenuItem("c$it", "Country $it") }
        assertEquals(MenuLayout.MAX_HEIGHT_PX, MenuLayout.size(items).second)
    }
}
