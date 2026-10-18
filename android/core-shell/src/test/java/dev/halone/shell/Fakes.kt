package dev.halone.shell

import dev.halone.compositor.PanelPose
import dev.halone.compositor.PanelScene
import dev.halone.compositor.ScenePanel
import dev.halone.compositor.StereoRegion
import dev.halone.compositor.WindowPanel

class FakePanel(id: String, override var widthPx: Int = 1280, override var heightPx: Int = 800) :
    ScenePanel(id, PanelPose()), WindowPanel {
    override var title = id
    override var tag: String? = null
    override var stereoRegion: StereoRegion? = null

    var closed = false
        private set

    override fun resizeContent(widthPx: Int, heightPx: Int) {
        this.widthPx = widthPx
        this.heightPx = heightPx
    }

    fun close() {
        closed = true
    }
}

class Scene : PanelScene<FakePanel>()

class FakeBrowser(url: String) : BrowserControl {
    private var nextTab = 2
    val tabs = mutableListOf(BrowserTab(1, "", url, false))
    var active = 1
    val calls = mutableListOf<String>()
    override var onChange: (() -> Unit)? = null

    var prompt: BrowserPrompt? = null
    var manifest: org.json.JSONObject? = null
    val answers = mutableListOf<Triple<Int, Boolean, Boolean>>()

    override fun state() = BrowserState(tabs.toList(), active, canGoBack = false, canGoForward = false, loading = false, prompt = prompt)

    override fun manifest() = manifest

    override fun answerPrompt(id: Int, allow: Boolean, remember: Boolean) {
        if (prompt?.id != id) throw HaloneException("no-such-prompt", "no such prompt: $id")
        answers += Triple(id, allow, remember)
        prompt = null
        onChange?.invoke()
    }

    fun titled(title: String) {
        tabs.replaceAll { if (it.id == active) it.copy(title = title) else it }
        onChange?.invoke()
    }

    override fun navigate(url: String) {
        calls += "navigate $url"
        tabs.replaceAll { if (it.id == active) it.copy(url = url) else it }
        onChange?.invoke()
    }

    override fun back() { calls += "back" }
    override fun forward() { calls += "forward" }
    override fun reload() { calls += "reload" }
    override fun stop() { calls += "stop" }

    override fun newTab(url: String?, private: Boolean) {
        tabs += BrowserTab(nextTab, "", url.orEmpty(), false, private)
        active = nextTab++
        onChange?.invoke()
    }

    override fun selectTab(id: Int) {
        active = id
        onChange?.invoke()
    }

    override fun closeTab(id: Int) {
        tabs.removeAll { it.id == id }
        if (active == id) active = tabs.first().id
        onChange?.invoke()
    }
}

class FakeWebApp(override var url: String?) : WebAppControl {
    override var title = ""
    override var onChange: (() -> Unit)? = null
    val navigated = mutableListOf<String>()

    override fun navigate(url: String) {
        navigated += url
        this.url = url
        onChange?.invoke()
    }
}

class FakePlatform(private val scene: Scene) : ShellPlatform<FakePanel> {
    val browsers = HashMap<String, FakeBrowser>()
    val webApps = HashMap<String, FakeWebApp>()
    val notices = mutableListOf<String>()

    override fun openWebApp(windowId: String, app: WebApp, url: String): FakePanel? =
        open(windowId, url, WebApp.WIDTH_PX, WebApp.HEIGHT_PX)?.also { webApps[windowId] = FakeWebApp(url) }

    override fun webApp(windowId: String): WebAppControl? = webApps[windowId]

    val packageFiles = HashMap<String, java.io.File?>()

    override fun openPackageApp(windowId: String, app: InstalledApp, files: java.io.File?, url: String): FakePanel? =
        open(windowId, url, app.manifest.widthPx, app.manifest.heightPx)?.also { packageFiles[windowId] = files }

    override fun notice(text: String) {
        notices += text
    }

    val nativeArgs = HashMap<String, Any?>()
    val statusClicks = mutableListOf<String>()
    val reopened = mutableListOf<String>()

    override fun reopenNativeApp(windowId: String) {
        reopened += windowId
    }

    override fun statusItemClicked(provider: String, id: String) {
        statusClicks += "$provider/$id"
    }

    override fun openNativeApp(windowId: String, app: NativeApp, args: Any?): FakePanel? =
        open(windowId, "", app.app.widthPx, app.app.heightPx)?.also { nativeArgs[windowId] = args }

    override fun browser(windowId: String): BrowserControl? = browsers[windowId]

    var headYaw = 0f
    var headPitch = 0f
    var recentred = 0
    var available = true
    val opened = mutableListOf<Pair<String, String>>()
    val applied = mutableListOf<String?>()

    override fun openApp(windowId: String, app: AppInfo, url: String): FakePanel? = open(windowId, url, app.widthPx, app.heightPx)
    val privateOpened = mutableListOf<String>()

    override fun openWeb(windowId: String, url: String, private: Boolean): FakePanel? =
        open(windowId, url, 1280, 800)?.also {
            browsers[windowId] = FakeBrowser(url)
            if (private) privateOpened += windowId
        }

    private fun open(windowId: String, url: String, width: Int, height: Int): FakePanel? {
        if (!available) return null
        opened += windowId to url
        return scene.open(FakePanel(windowId, width, height))
    }

    override fun close(panel: FakePanel) {
        scene.remove(panel)
        panel.close()
    }

    override fun openOverlays(dock: AppInfo, dockUrl: String, launcher: AppInfo, launcherUrl: String) {
        opened += dock.id to dockUrl
        opened += launcher.id to launcherUrl
        scene.setOverlays(
            FakePanel(dock.id, dock.widthPx, dock.heightPx),
            FakePanel(launcher.id, launcher.widthPx, launcher.heightPx),
        )
    }

    override fun recenter() {
        recentred++
    }

    override fun headYawRadians() = headYaw
    override fun headPitchRadians() = headPitch

    override fun applySettings(settings: ShellSettings, changedKey: String?) {
        applied += changedKey
    }

    var autoHideApplied = false
    val captures = mutableListOf<Long>()
    val recordings = mutableListOf<Long>()

    override fun captureScreen(delayMs: Long) {
        captures += delayMs
    }

    override fun startRecording(delayMs: Long) {
        recordings += delayMs
    }

    override fun setWindowsAutoHide(enabled: Boolean) {
        autoHideApplied = enabled
    }

    var clipboard: String? = null
    val menuCalls = mutableListOf<String>()
    val cleared = mutableListOf<Pair<Boolean, Boolean>>()

    override fun clipboardText() = clipboard

    override fun setClipboardText(text: String) {
        clipboard = text
    }

    override fun prepareMenu(widthPx: Int, heightPx: Int) {
        menuCalls += "prepare ${widthPx}x$heightPx"
    }

    override fun showMenu(sourceId: String, xPx: Float, yPx: Float) {
        menuCalls += "show $sourceId at ${xPx.toInt()},${yPx.toInt()}"
    }

    override fun hideMenu() {
        menuCalls += "hide"
    }

    override fun clearBrowsingData(cookies: Boolean, cache: Boolean) {
        cleared += cookies to cache
    }
}

class MemoryStore(var stored: String? = null) : SettingsStore {
    override fun load() = stored
    override fun save(json: String) {
        stored = json
    }
}

class FakeChannel : BridgeChannel {
    val posted = mutableListOf<String>()
    var closed = false
        private set
    private var handler: ((String) -> Unit)? = null

    override fun post(message: String) {
        posted += message
    }

    override fun onReceive(handler: (String) -> Unit) {
        this.handler = handler
    }

    override fun close() {
        closed = true
    }

    fun receive(text: String) = handler!!(text)
}

val APPS = listOf(
    AppInfo("files", "Files", "", "", "/files/index.html", 1280, 800),
    AppInfo("dock", "Dock", "", "", "/dock/index.html", 880, 104, hidden = true),
    AppInfo("launcher", "Launcher", "", "", "/launcher/index.html", 1280, 800, hidden = true),
    AppInfo("settings", "Settings", "", "", "/settings/index.html", 1024, 800),
    AppInfo("secret", "Secret", "", "", "/secret/index.html", 800, 600, hidden = true),
)

fun radians(degrees: Float) = Math.toRadians(degrees.toDouble()).toFloat()

/** The schema the system apps build ships, read from the source tree. */
val SCHEMA: String = java.io.File("../../web/apps/system/public/settings-schema.json").readText()
