package dev.halone.desktop.compositor.shell

import dev.halone.compositor.Notice
import dev.halone.compositor.PanelPose
import dev.halone.compositor.SizeLimits
import dev.halone.desktop.compositor.producer.ProducerHost
import dev.halone.desktop.compositor.render.DesktopCompositor
import dev.halone.desktop.compositor.render.DesktopPanel
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.shell.AdBlocker
import dev.halone.desktop.shell.AppData
import dev.halone.desktop.shell.BridgeSockets
import dev.halone.desktop.shell.DevServer
import dev.halone.desktop.shell.DesktopFolders
import dev.halone.desktop.shell.DesktopStorageAccess
import dev.halone.desktop.shell.DesktopThumbnails
import dev.halone.desktop.shell.FileStore
import dev.halone.shell.AppInfo
import dev.halone.desktop.compositor.producer.DownloadEvent
import dev.halone.shell.Bookmarks
import dev.halone.shell.DownloadNames
import dev.halone.shell.HalonePackage
import dev.halone.shell.MenuItem
import dev.halone.shell.PageMenuHost
import dev.halone.shell.SearchEngines
import dev.halone.shell.BridgeHost
import dev.halone.shell.BrowserBand
import dev.halone.shell.BrowserControl
import dev.halone.shell.BrowserData
import dev.halone.shell.BrowserHistory
import dev.halone.shell.Downloads
import dev.halone.shell.ShellListener
import dev.halone.shell.DevCommands
import dev.halone.shell.InstalledApp
import dev.halone.shell.PackageApps
import dev.halone.shell.ProvidedStatusItem
import dev.halone.shell.FileServer
import dev.halone.shell.KeyChord
import dev.halone.shell.SharedFiles
import dev.halone.shell.Shell
import dev.halone.shell.ShellPlatform
import dev.halone.shell.ShellSettings
import dev.halone.shell.ShortcutKeys
import dev.halone.shell.Shortcuts
import dev.halone.shell.Sockets
import dev.halone.shell.SystemInfo
import dev.halone.shell.applyTo
import dev.halone.shell.runShortcut
import org.lwjgl.glfw.GLFW.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * The shell on the desktop: what ShellSession is on Android. Windows are
 * pages in the producer, the system apps are served from [webRoot] as
 * https://halone.local/, and the bridge reaches them through [BridgeSockets].
 * Everything but the queue behind [post] runs on the render thread, the
 * shell's own; call [tick] once a frame.
 */
class DesktopShell(
    private val compositor: DesktopCompositor,
    private val host: ProducerHost,
    private val webRoot: File,
    private val verticalFovDegrees: Float,
    private val frame: () -> Long,
    private val onRecenter: () -> Unit,
    private val version: String,
    deviceName: String,
    private val log: (String) -> Unit,
    /** The browser's ad blocker, kept current here for the producer's next start; null where there is no producer. */
    private val adBlocker: AdBlocker? = null,
) : ShellPlatform<DesktopPanel>, AutoCloseable {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "shell-io").apply { isDaemon = true } }
    private val background = Executors.newFixedThreadPool(2) { Thread(it, "shell-background").apply { isDaemon = true } }
    private val sockets = BridgeSockets(::post, log)
    private val byProducer = HashMap<Int, DesktopPanel>()
    private val browsers = HashMap<String, DesktopBrowser>()
    private val sizesBeforeFullScreen = HashMap<String, Pair<Int, Int>>()
    private val browserOf = HashMap<Int, DesktopBrowser>()
    private val browsing = BrowserData(
        BrowserHistory(FileStore("history.json", io)),
        Bookmarks(FileStore("bookmarks.json", io)),
        Downloads(FileStore("downloads.json", io)),
    )
    private var noticeUntilNs = 0L
    private var lastPollNs = 0L

    private val scene get() = compositor.scene

    val shell = Shell(
        scene = scene,
        systemApps = AppInfo.parseRegistry(File(webRoot, REGISTRY).readText()),
        settings = ShellSettings(FileStore("settings.json", io), File(webRoot, ShellSettings.SCHEMA_ASSET).readText()),
        platform = this,
        packageApps = PackageApps(File(AppData.dir, "apps"), FileStore("packages.json", io)),
    )

    private val devCommands = DevCommands(
        shell, version, source = "cli", devServerHint = "on this machine",
        background = { background.execute(it) }, onMain = ::post, log = log,
    )
    private val devServer = DevServer(devCommands::run, ::post, log)

    private val files = SharedFiles(DesktopFolders::roots, DesktopStorageAccess)
    private val fileServer = FileServer(files, thumbnailer = DesktopThumbnails(DesktopSystem.thumbnailer)::of, log = log)
        .also { files.urlBase = it.base }
    private var lastBatteryNs = 0L
    private var lastNetworkNs = 0L
    private var network: NetworkState? = null
    private val tcp = Sockets(onMain = ::post)

    val bridge = BridgeHost(
        shell,
        SystemInfo(
            version = version,
            deviceName = deviceName,
            verticalFovDegrees = verticalFovDegrees.toDouble(),
            displayWidthPx = EYE_WIDTH_PX,
            displayHeightPx = EYE_HEIGHT_PX,
            platform = "desktop",
            os = System.getProperty("os.name").lowercase().let { name ->
                when {
                    name.contains("mac") -> "macos"
                    name.contains("win") -> "windows"
                    else -> "linux"
                }
            },
        ),
        log,
        files = files,
        browsing = browsing,
        onMain = ::post,
        background = { background.execute(it) },
        sockets = tcp,
    )

    private val shortcuts = Shortcuts(File(webRoot, Shortcuts.ASSET).readText(), mac = System.getProperty("os.name").lowercase().contains("mac"))

    private val shortcutKeys = ShortcutKeys { chord ->
        runCatching { shell.runShortcut(chord, shortcuts, scene.keyTarget, captureOn = false, log = log) }
            .onFailure { log("  shortcut $chord failed: $it") }
            .getOrDefault(true)
    }

    /** A GLFW key event; true when a shortcut takes it, so it goes no further. */
    fun key(key: Int, action: Int, modifiers: Int): Boolean {
        val meta = key == GLFW_KEY_LEFT_SUPER || key == GLFW_KEY_RIGHT_SUPER
        val chord = chordOf(key)?.let {
            KeyChord(it, ctrl = modifiers and GLFW_MOD_CONTROL != 0, alt = modifiers and GLFW_MOD_ALT != 0,
                shift = modifiers and GLFW_MOD_SHIFT != 0, meta = modifiers and GLFW_MOD_SUPER != 0)
        }
        return shortcutKeys.onKey(key, meta, action != GLFW_RELEASE, action == GLFW_REPEAT, chord)
    }

    /** The keys `shortcuts.json` names, as MainActivity's chordOf names them. */
    private fun chordOf(key: Int): String? = when (key) {
        in GLFW_KEY_A..GLFW_KEY_Z -> ('A' + (key - GLFW_KEY_A)).toString()
        in GLFW_KEY_F1..GLFW_KEY_F12 -> "F${key - GLFW_KEY_F1 + 1}"
        GLFW_KEY_TAB -> "Tab"
        GLFW_KEY_SPACE -> "Space"
        GLFW_KEY_GRAVE_ACCENT -> "`"
        GLFW_KEY_LEFT_BRACKET -> "["
        GLFW_KEY_RIGHT_BRACKET -> "]"
        GLFW_KEY_ESCAPE -> "Escape"
        GLFW_KEY_PRINT_SCREEN -> "PrintScreen"
        GLFW_KEY_LEFT -> "ArrowLeft"
        GLFW_KEY_RIGHT -> "ArrowRight"
        else -> null
    }

    init {
        host.onPanelReady = ::textureArrived
        host.onPanelResized = ::textureArrived
        host.onCursorChanged = { index, shape -> byProducer[index]?.cursorShape = shape }
        host.onPageChanged = { index, page -> browserOf[index]?.page(index, page) }
        host.onPopup = { index, url, background -> browserOf[index]?.popup(index, url, background) }
        host.onContextMenu = { index, target -> browserOf[index]?.contextMenu(index, target) }
        host.onPrompt = { index, prompt -> browserOf[index]?.prompt(index, prompt) }
        host.onFullScreen = { index, full -> browserOf[index]?.fullScreen(index, full) }
        host.onScroll = { index, y -> browserOf[index]?.scrolled(index, y) }
        host.onDownload = ::downloadChanged
        shell.addListener(object : ShellListener {
            override fun onBrowserCommand(windowId: String, command: String) {
                if (command == FOCUS_ADDRESS) browsers[windowId]?.panel?.keysToLayer = true
            }
        })
    }

    fun start() {
        adBlocker?.takeIf { shell.settings.boolean(ShellSettings.BROWSER_AD_BLOCKER) }?.let { background.execute(it::update) }
        applySettings(shell.settings, null)
        shell.openOverlays()
    }

    /** Runs what other threads posted, and polls the shell a few times a second, as ShellSession does. */
    fun tick(nowNs: Long) {
        while (true) (queue.poll() ?: break).run()
        browsers.values.forEach { it.tick() }
        if (lastNetworkNs == 0L || nowNs - lastNetworkNs >= NETWORK_NS) {
            lastNetworkNs = nowNs
            background.execute { DesktopSystem.network()?.let { state -> post { showNetwork(state) } } }
        }
        if (lastBatteryNs == 0L || nowNs - lastBatteryNs >= BATTERY_NS) {
            lastBatteryNs = nowNs
            background.execute { DesktopSystem.battery()?.let { battery -> post { shell.setBattery(battery) } } }
        }
        if (nowNs - lastPollNs >= POLL_NS) {
            lastPollNs = nowNs
            shell.poll()
            bridge.checkFileAccess()
        }
        if (compositor.notice != null && nowNs >= noticeUntilNs) compositor.notice = null
    }

    /**
     * The network meter, which the Android integration plugin shows on the
     * phone. Here it only informs: there are no network settings to open.
     */
    private fun showNetwork(state: NetworkState) {
        if (state == network) return
        network = state
        val label = when (state.kind) {
            NetworkState.Kind.WIFI -> "Wi-Fi, signal ${state.bars} of 4"
            NetworkState.Kind.WIRED -> "Ethernet"
            NetworkState.Kind.OFFLINE -> "Offline"
        }
        shell.statusItems.set(STATUS_PROVIDER, ProvidedStatusItem(NETWORK_ITEM, NetworkIcons.png(state), label, "", tinted = true))
    }

    private fun post(task: Runnable) {
        queue += task
    }

    /** A panel drawn by the producer, sized as Android sizes its windows; [origin] gets the bridge, and [root] serves it. */
    private fun producerPanel(id: String, widthPx: Int, heightPx: Int, url: String, origin: String?, root: File? = null): DesktopPanel {
        val panel = DesktopPanel(id, widthPx, heightPx, NoTexture, PanelPose(distanceMetres = distance()))
        panel.fitToFov(verticalFovDegrees, fillFor(heightPx))
        val script = origin?.let { sockets.register(id, it) { channel -> bridge.connect(id, channel) } }.orEmpty()
        val index = host.open(widthPx, heightPx, url, origin.orEmpty(), script, root)
        panel.producerIndex = index
        panel.onResizeContent = { width, height -> host.resize(index, width, height) }
        byProducer[index] = panel
        return panel
    }

    private fun textureArrived(index: Int, texture: TextureSource) {
        val browser = browserOf[index]
        if (browser != null) browser.texture(index, texture) else byProducer[index]?.texture = texture
    }

    private fun dispose(panel: DesktopPanel) {
        browsers.remove(panel.id)?.let { browser ->
            browser.close()
            sockets.unregister(panel.id)
            return
        }
        panel.producerIndex?.let {
            byProducer.remove(it)
            host.close(it, frame())
        }
        sockets.unregister(panel.id)
    }

    override fun openApp(windowId: String, app: AppInfo, url: String): DesktopPanel {
        val panel = producerPanel(windowId, app.widthPx, app.heightPx, url, Shell.ORIGIN)
        panel.sizeLimits = SizeLimits.defaultFor(app.widthPx, app.heightPx)
        scene.open(panel)
        log("  shell: opened ${app.id} as $windowId")
        return panel
    }

    override fun openPackageApp(windowId: String, app: InstalledApp, files: File?, url: String): DesktopPanel {
        val origin = java.net.URI(url).let { "${it.scheme}://${it.rawAuthority}" }
        val panel = producerPanel(windowId, app.manifest.widthPx, app.manifest.heightPx, url, origin, files)
        panel.sizeLimits = SizeLimits.defaultFor(app.manifest.widthPx, app.manifest.heightPx)
        scene.open(panel)
        log("  shell: opened package ${app.id} as $windowId from $origin")
        return panel
    }

    /** A web window, as ShellSession makes one: the browser page across the top, with the bridge, and the tabs below. */
    override fun openWeb(windowId: String, url: String, private: Boolean): DesktopPanel? {
        val chromeApp = shell.apps.firstOrNull { it.id == BROWSER_CHROME } ?: return null
        val height = WEB_HEIGHT_PX + BrowserBand.TOOLBAR_PX
        val panel = DesktopPanel(windowId, WEB_WIDTH_PX, height, NoTexture, PanelPose(distanceMetres = distance()))
        panel.fitToFov(verticalFovDegrees, fillFor(height))
        val script = sockets.register(windowId, Shell.ORIGIN) { channel -> bridge.connect(windowId, channel) }
        val chromeIndex = host.open(WEB_WIDTH_PX, chromeApp.heightPx, Shell.ORIGIN + chromeApp.entry, Shell.ORIGIN, script)
        val browser = DesktopBrowser(
            host, panel, chromeApp.heightPx, browsing.history, menuHost(windowId),
            autoHideToolbar = { shell.settings.boolean(ShellSettings.BROWSER_AUTO_HIDE_TOOLBAR) },
            frame = frame,
            claim = { index ->
                byProducer[index] = panel
                browserOf[index] = browsers.getValue(windowId)
            },
            release = { index ->
                byProducer.remove(index)
                browserOf.remove(index)
            },
        )
        browsers[windowId] = browser
        browser.onFullScreenChange = { full -> fitFullScreen(windowId, full) }
        browser.start(chromeIndex, url, private)
        panel.onResizeContent = browser::resized
        panel.onContentInput = browser::contentInput
        panel.sizeLimits = SizeLimits.defaultFor(WEB_WIDTH_PX, height)
        scene.open(panel)
        log("  shell: opened a web window as $windowId")
        return panel
    }

    override fun browser(windowId: String): BrowserControl? = browsers[windowId]

    /** What a web window's page menu does in the shell, as ShellSession's browserHost does on Android. */
    private fun menuHost(windowId: String) = object : PageMenuHost {
        override fun showMenu(items: List<MenuItem>, xPx: Float, yPx: Float, onChosen: (String?) -> Unit): Int? =
            runCatching { shell.showMenu(windowId, items, xPx, yPx, onChosen).menuId }.getOrNull()

        override fun openWindow(url: String, private: Boolean) {
            runCatching { shell.openUrl(url, private) }.onFailure { log("  browser: could not open $url: $it") }
        }

        override fun openInBrowser(url: String, private: Boolean) {
            runCatching { shell.openInBrowser(url, private) }.onFailure { log("  browser: could not open $url: $it") }
        }

        override fun clipboardText() = this@DesktopShell.clipboardText()

        override fun setClipboardText(text: String) = shell.writeClipboardText(text)

        override fun download(url: String, referrer: String?, private: Boolean) {
            browsers[windowId]?.download(url)
        }

        override fun searchUrl(text: String) = SearchEngines.url(shell.settings.string(ShellSettings.BROWSER_SEARCH_ENGINE), text)

        override fun isBookmarked(url: String) = browsing.bookmarks.find(url) != null

        override fun toggleBookmark() {
            val browser = browsers[windowId] ?: return
            val tab = browser.state().active ?: return
            browsing.bookmarks.find(tab.url)?.let { browsing.bookmarks.remove(it.id) }
                ?: runCatching { browsing.bookmarks.add(tab.url, tab.title, browser.iconUrl()) }
        }
    }

    /** The window takes the screen's size while its page is full screen, and gets its own back after, as on Android. */
    private fun fitFullScreen(windowId: String, full: Boolean) {
        val window = runCatching { shell.find(windowId) }.getOrNull() ?: return
        if (full) {
            sizesBeforeFullScreen.getOrPut(windowId) { window.panel.widthPx to window.panel.heightPx }
            shell.resize(windowId, WEB_WIDTH_PX, WEB_HEIGHT_PX)
        } else {
            val (width, height) = sizesBeforeFullScreen.remove(windowId) ?: return
            shell.resize(windowId, width, height)
        }
    }

    private class Saving(val shellId: Int, val windowId: String?, val name: String, val packageFile: File?)

    /** The pages' downloads, as Android's Downloader keeps them: in the Downloads folder, in the shared list, with notices. */
    private val saving = HashMap<Int, Saving>()

    private fun downloadChanged(index: Int, download: DownloadEvent) {
        val windowId = browserOf[index]?.panel?.id
        when (download.state) {
            ProducerHost.DOWNLOAD_STARTED -> {
                val name = DownloadNames.sanitise(download.name)
                val isPackage = download.mime.substringBefore(';').trim().lowercase() == HalonePackage.MIME_TYPE ||
                    name.lowercase().endsWith("." + HalonePackage.EXTENSION)
                val target = if (isPackage) {
                    File(AppData.dir, "cache/packages").apply { mkdirs() }.let { File(it, "download-${System.currentTimeMillis()}.${HalonePackage.EXTENSION}") }
                } else {
                    freeFile(downloadsFolder(), name)
                }
                val id = browsing.downloads.start(download.url, target.name.takeIf { !isPackage } ?: name,
                    download.mime.substringBefore(';').ifBlank { null }?.takeIf { it != "application/octet-stream" },
                    download.total.takeIf { it > 0 }) { host.downloadControl(download.id, cancel = true) }
                saving[download.id] = Saving(id, windowId, if (isPackage) name else target.name, target.takeIf { isPackage })
                host.downloadControl(download.id, cancel = false, path = target.path)
                if (!isPackage) notice("Downloading ${target.name}")
            }
            ProducerHost.DOWNLOAD_PROGRESS -> saving[download.id]?.let { browsing.downloads.progress(it.shellId, download.received) }
            ProducerHost.DOWNLOAD_DONE -> {
                val save = saving.remove(download.id) ?: return
                if (save.packageFile != null) {
                    browsing.downloads.remove(save.shellId)
                    offerPackage(save)
                    return
                }
                browsing.downloads.finish(save.shellId, download.path, File(download.path).name)
                notice("Downloaded ${File(download.path).name}")
            }
            ProducerHost.DOWNLOAD_FAILED, ProducerHost.DOWNLOAD_CANCELLED -> {
                val save = saving.remove(download.id) ?: return
                save.packageFile?.delete()
                runCatching {
                    if (download.state == ProducerHost.DOWNLOAD_FAILED) browsing.downloads.fail(save.shellId, "the download failed")
                    else browsing.downloads.cancel(save.shellId)
                }
                if (download.state == ProducerHost.DOWNLOAD_FAILED) notice("Download failed: ${save.name}")
            }
        }
    }

    /** A `.halone` the page downloaded, offered for install over its window as on Android. */
    private fun offerPackage(save: Saving) {
        val file = save.packageFile ?: return
        background.execute {
            val read = runCatching { HalonePackage.read(file, shell.reservedIds, version) }
            post {
                read.onSuccess { pkg ->
                    val window = save.windowId
                    if (window == null) file.delete() else shell.offerPackage(window, pkg, "download") { file.delete() }
                }.onFailure {
                    file.delete()
                    notice("Not a Halone app: ${it.message}")
                }
            }
        }
    }

    private fun downloadsFolder() = File(DesktopFolders.roots().first { it.kind == "downloads" }.path).apply { mkdirs() }

    /** [name] in [folder], numbered as a browser numbers a name already taken: `name (1).ext`. */
    private fun freeFile(folder: File, name: String): File {
        var file = File(folder, name)
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (file.exists()) file = File(folder, "$stem (${n++})$extension")
        return file
    }

    override fun openOverlays(dock: AppInfo, dockUrl: String, launcher: AppInfo, launcherUrl: String) {
        val dockPanel = producerPanel(dock.id, dock.widthPx, dock.heightPx, dockUrl, Shell.ORIGIN)
        dockPanel.cornerRadiusPx = dock.heightPx / 2f
        val launcherPanel = producerPanel(launcher.id, launcher.widthPx, launcher.heightPx, launcherUrl, Shell.ORIGIN)
        scene.setOverlays(dockPanel, launcherPanel)
    }

    override fun openMenuOverlay(app: AppInfo, url: String) {
        val panel = producerPanel(app.id, app.widthPx, app.heightPx, url, Shell.ORIGIN)
        panel.cornerRadiusPx = MENU_CORNER_PX
        scene.setPopupPanel(panel)
    }

    override fun prepareMenu(widthPx: Int, heightPx: Int) {
        scene.popup?.resizeContent(widthPx, heightPx)
    }

    override fun showMenu(sourceId: String, xPx: Float, yPx: Float) {
        val parent = when (sourceId) {
            Shell.DOCK -> scene.dock
            Shell.LAUNCHER -> scene.launcher
            else -> runCatching { shell.find(sourceId).panel }.getOrNull()
        } ?: return
        scene.showPopup(parent, xPx, yPx)
    }

    override fun hideMenu() = scene.hidePopup()

    override fun close(panel: DesktopPanel) {
        scene.remove(panel)
        dispose(panel)
        scene.layOutCylindrical(immediate = false)
    }

    override fun recenter() = onRecenter()

    override fun headYawRadians() = compositor.headYawRadians

    override fun headPitchRadians() = scene.gazePitchRadians ?: 0f

    /** Whether raw keyboard mode holds the keys now, for the Settings app's key capture state. */
    var keyCapture: () -> Boolean = { false }

    /** Gives the keyboard and mouse back to the computer's desktop, as the launcher's button asks. */
    var onReleaseToDesktop: () -> Unit = {}

    /** Every settings change, after the shell's own, for the compositor's settings (raw keyboard, EDID patch). */
    var onSettingChanged: ((ShellSettings, String) -> Unit)? = null

    override fun keyCaptureOn(): Boolean = keyCapture()

    override fun releaseToDesktop() = onReleaseToDesktop()

    override fun applySettings(settings: ShellSettings, changedKey: String?) {
        changedKey?.let { key -> onSettingChanged?.invoke(settings, key) }
        settings.applyTo(scene)
        if (changedKey == ShellSettings.BROWSER_AUTO_HIDE_TOOLBAR) browsers.values.forEach { it.settingsChanged() }
        // Chromium loads extensions only at start, so the ad blocker comes and goes with the next one.
        if (changedKey == ShellSettings.BROWSER_AD_BLOCKER) {
            if (settings.boolean(changedKey)) adBlocker?.let { background.execute(it::update) }
            notice(if (settings.boolean(changedKey)) "The ad blocker turns on next time Halone starts" else "The ad blocker turns off next time Halone starts")
        }
        if (changedKey == ShellSettings.PANEL_DISTANCE_METRES) {
            val metres = distance()
            (scene.panels + listOfNotNull(scene.dock, scene.launcher)).forEach { panel ->
                panel.pose = panel.pose.copy(distanceMetres = metres)
                panel.fitToFov(verticalFovDegrees, fillFor(panel.heightPx))
            }
        }
    }

    override fun setWindowsAutoHide(enabled: Boolean) {
        scene.windowsAutoHide = enabled
    }

    override fun notice(text: String) {
        compositor.notice = Notice(text, 0f)
        noticeUntilNs = System.nanoTime() + NOTICE_NS
    }

    override fun clipboardText(): String? = glfwGetClipboardString(0L)

    override fun setClipboardText(text: String) = glfwSetClipboardString(0L, text)

    private fun distance() = shell.settings.number(ShellSettings.PANEL_DISTANCE_METRES).toFloat()

    private fun fillFor(heightPx: Int) = PANEL_VERTICAL_FILL * heightPx / REFERENCE_HEIGHT_PX

    override fun close() {
        devServer.close()
        fileServer.close()
        sockets.close()
        io.shutdown()
        background.shutdown()
    }

    private companion object {
        const val REGISTRY = "apps.json"
        const val PANEL_VERTICAL_FILL = 0.75f
        const val REFERENCE_HEIGHT_PX = 800f
        const val BROWSER_CHROME = "browser"
        const val FOCUS_ADDRESS = "focus-address"
        const val WEB_WIDTH_PX = 1280
        const val WEB_HEIGHT_PX = 800
        const val MENU_CORNER_PX = 14f
        const val EYE_WIDTH_PX = 1920
        const val EYE_HEIGHT_PX = 1080
        const val POLL_NS = 200_000_000L
        const val BATTERY_NS = 30_000_000_000L
        const val NETWORK_NS = 5_000_000_000L
        const val STATUS_PROVIDER = "dev.halone.desktop"
        const val NETWORK_ITEM = "network"
        const val NOTICE_NS = 2_500_000_000L
    }
}
