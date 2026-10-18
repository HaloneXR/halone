package dev.halone.shell

import dev.halone.compositor.StereoLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeWindowsTest {
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

    private fun limits(minWidth: Int, minHeight: Int, maxWidth: Int, maxHeight: Int) = JSONObject()
        .put("minWidthPx", minWidth).put("minHeightPx", minHeight).put("maxWidthPx", maxWidth).put("maxHeightPx", maxHeight)

    @Test
    fun `a window resizes itself within its limits`() {
        val (window, channel) = connected("files")

        val result = channel.call("windows.resize", JSONObject().put("widthPx", 100000).put("heightPx", 600)).getJSONObject("result")

        assertEquals(1280 * 5 / 2, result.getInt("widthPx"))
        assertEquals(600, result.getInt("heightPx"))
        assertEquals(600, window.panel.heightPx)
    }

    @Test
    fun `locked limits let a window go below its usual floor, and clearing them restores it`() {
        val (window, channel) = connected("files")
        channel.call("windows.setSizeLimits", JSONObject().put("limits", limits(1280, 180, 1280, 180)))
        channel.call("windows.resize", JSONObject().put("widthPx", 1280).put("heightPx", 180).put("anchor", "bottom"))
        assertEquals(180, window.panel.heightPx)

        channel.call("windows.setSizeLimits", JSONObject().put("limits", JSONObject.NULL))
        val restored = channel.call("windows.resize", JSONObject().put("widthPx", 1280).put("heightPx", 180)).getJSONObject("result")
        assertEquals(360, restored.getInt("heightPx"))
    }

    @Test
    fun `limits must make sense`() {
        val (_, channel) = connected("files")

        val inverted = channel.call("windows.setSizeLimits", JSONObject().put("limits", limits(900, 100, 800, 200)))
        val huge = channel.call("windows.setSizeLimits", JSONObject().put("limits", limits(100, 100, 99999, 200)))

        assertEquals("invalid-params", inverted.getJSONObject("error").getString("code"))
        assertEquals("invalid-params", huge.getJSONObject("error").getString("code"))
    }

    @Test
    fun `a stereo region is set on the caller's panel and cleared with null`() {
        val (window, channel) = connected("files")
        val region = JSONObject().put("stereo", "side-by-side").put("xPx", 10).put("yPx", 20).put("widthPx", 640).put("heightPx", 360)

        channel.call("windows.setStereoRegion", JSONObject().put("region", region))
        val set = window.panel.stereoRegion!!
        assertEquals(StereoLayout.SIDE_BY_SIDE, set.layout)
        assertEquals(640f, set.widthPx, 0f)

        channel.call("windows.setStereoRegion", JSONObject().put("region", JSONObject.NULL))
        assertNull(window.panel.stereoRegion)
    }

    @Test
    fun `a mono or empty region is refused`() {
        val (_, channel) = connected("files")
        val mono = JSONObject().put("stereo", "mono").put("xPx", 0).put("yPx", 0).put("widthPx", 10).put("heightPx", 10)
        val empty = JSONObject().put("stereo", "top-bottom").put("xPx", 0).put("yPx", 0).put("widthPx", 0).put("heightPx", 10)

        assertEquals("invalid-params", channel.call("windows.setStereoRegion", JSONObject().put("region", mono)).getJSONObject("error").getString("code"))
        assertEquals("invalid-params", channel.call("windows.setStereoRegion", JSONObject().put("region", empty)).getJSONObject("error").getString("code"))
    }

    @Test
    fun `a window's auto-hide lasts as long as the window`() {
        val (window, channel) = connected("files")

        channel.call("windows.setAutoHide", JSONObject().put("enabled", true))
        assertEquals(true, platform.autoHideApplied)
        shell.close(window.id)
        assertEquals(false, platform.autoHideApplied)
    }

    @Test
    fun `the wearer's setting holds whatever windows ask`() {
        val (_, channel) = connected("files")
        shell.setSetting(ShellSettings.WINDOWS_AUTO_HIDE, true)

        channel.call("windows.setAutoHide", JSONObject().put("enabled", true))
        channel.call("windows.setAutoHide", JSONObject().put("enabled", false))
        assertEquals(true, platform.autoHideApplied)
        shell.setSetting(ShellSettings.WINDOWS_AUTO_HIDE, false)
        assertEquals(false, platform.autoHideApplied)
    }

    @Test
    fun `a screenshot waits as asked, within reason`() {
        val (_, channel) = connected("files")

        channel.call("screen.capture", JSONObject().put("delayMs", 400))
        channel.call("screen.capture", JSONObject().put("delayMs", 999_999))

        assertEquals(listOf(400L, 10_000L), platform.captures)
    }

    @Test
    fun `recording is reported to every window, and not started twice`() {
        val (_, channel) = connected("files")
        channel.call("screen.startRecording", JSONObject().put("delayMs", 400))
        shell.setRecording(RecordingState(true, 1_000L, audio = true))
        channel.call("screen.startRecording")

        assertEquals(listOf(400L), platform.recordings)
        val event = channel.posted.map { JSONObject(it) }.last { it.optString("name") == "screen.recordingChanged" }
        assertEquals(true, event.getJSONObject("data").getBoolean("audio"))
        assertEquals(true, channel.call("screen.recording").getJSONObject("result").getBoolean("recording"))
    }
}
