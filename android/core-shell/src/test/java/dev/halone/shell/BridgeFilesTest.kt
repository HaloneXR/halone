package dev.halone.shell

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeFilesTest {
    private val base: File = Files.createTempDirectory("halone-bridge").toFile().canonicalFile
    private val photo = File(base, "a.jpg").apply { writeText("jpeg") }
    private val film = File(base, "trip_360.mp4").apply { writeText("mp4") }
    private val apps = APPS + listOf(
        AppInfo("images", "Images", "", "", "/images/index.html", 1280, 800, opens = listOf("image/*")),
        AppInfo("video", "Video", "", "", "/video/index.html", 1280, 800, opens = listOf("video/*")),
    )
    private val scene = Scene()
    private val shell = Shell(scene, apps, ShellSettings(MemoryStore(), SCHEMA), FakePlatform(scene))
    private val access = FakeAccess()
    private val backend = FakeBackend()
    private val host = BridgeHost(
        shell,
        SystemInfo("test", "Test Glasses", 19.0, 1920, 1080),
        files = SharedFiles({ listOf(FileRoot("storage", "Storage", base.path)) }, access),
        media = Media(backend),
    )
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

    private val layout = JSONObject().put("projection", "equirect-360").put("stereo", "mono")

    @Test
    fun `a file opens in the app that declares its type`() {
        val (_, files) = connected("files")

        val response = files.call("apps.open", JSONObject().put("path", photo.path))

        assertTrue(response.getBoolean("ok"))
        val window = shell.find(response.getJSONObject("result").getString("id"))
        assertEquals("images", window.appId)
        assertEquals(photo.path, window.openedFile)
    }

    @Test
    fun `the opened window asks for its file, and hears of the next one`() {
        val (_, files) = connected("files")
        val first = files.call("apps.open", JSONObject().put("path", photo.path)).getJSONObject("result").getString("id")
        val viewer = FakeChannel()
        host.connect(first, viewer)

        assertEquals(photo.path, viewer.call("windows.openedFile").getJSONObject("result").getString("path"))
        val other = File(base, "b.png").apply { writeText("png") }
        files.call("apps.open", JSONObject().put("path", other.path))
        assertEquals(other.path, viewer.events("window.fileOpened").last().getJSONObject("data").getString("path"))
    }

    @Test
    fun `a file nothing opens is refused`() {
        val (_, files) = connected("files")
        val archive = File(base, "a.zip").apply { writeText("zip") }

        val response = files.call("apps.open", JSONObject().put("path", archive.path))

        assertFalse(response.getBoolean("ok"))
        assertEquals("no-handler", response.getJSONObject("error").getString("code"))
    }

    @Test
    fun `the player belongs to the window that opened it`() {
        val (player, video) = connected("video")
        val (_, files) = connected("files")

        val opened = video.call("media.open", JSONObject().put("path", film.path).put("layout", layout))
        assertTrue(opened.getJSONObject("result").getBoolean("open"))
        assertEquals("not-owner", files.call("media.pause").getJSONObject("error").getString("code"))

        shell.close(player.id)
        assertEquals("close", backend.calls.last())
    }

    @Test
    fun `only a video goes to the player`() {
        val (_, video) = connected("video")

        val response = video.call("media.open", JSONObject().put("path", photo.path).put("layout", layout))

        assertEquals("not-a-video", response.getJSONObject("error").getString("code"))
    }

    @Test
    fun `losing access is broadcast`() {
        val (_, files) = connected("files")
        host.checkFileAccess()
        access.granted = false
        host.checkFileAccess()

        assertFalse(files.events("files.accessChanged").last().getJSONObject("data").getBoolean("granted"))
        assertEquals("permission-denied", files.call("files.roots").getJSONObject("error").getString("code"))
    }
}
