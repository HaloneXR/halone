package dev.halone.app.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import dev.halone.app.panel.BrowserEngine
import dev.halone.app.panel.BrowserHost
import dev.halone.app.panel.GeckoExtensions
import dev.halone.app.panel.GeckoKeyboard
import dev.halone.app.panel.GeckoPanel
import dev.halone.app.panel.WebAppEngine
import dev.halone.app.stereo.GlassesStereoSession
import dev.halone.compositor.CollisionPolicy
import dev.halone.compositor.DockVisibility
import dev.halone.compositor.Panel
import dev.halone.compositor.SizeLimits
import dev.halone.engine.plugin.PluginConnection
import dev.halone.engine.plugin.PluginDirectory
import dev.halone.engine.plugin.PluginIcons
import dev.halone.engine.plugin.PluginPanelEngine
import dev.halone.engine.plugin.PluginWindow
import dev.halone.engine.webview.BridgeConnector
import dev.halone.engine.webview.SelectPopup
import dev.halone.engine.webview.TextField
import dev.halone.engine.webview.WebViewEngine
import dev.halone.glasses.api.BrightnessControl
import dev.halone.plugin.PluginContract
import dev.halone.shell.AppInfo
import dev.halone.shell.Battery
import dev.halone.shell.Bookmarks
import dev.halone.shell.BridgeHost
import dev.halone.shell.BrowserControl
import dev.halone.shell.BrowserData
import dev.halone.shell.BrowserHistory
import dev.halone.shell.Downloads
import dev.halone.shell.FileRoot
import dev.halone.shell.FileServer
import dev.halone.shell.HalonePackage
import dev.halone.shell.InstalledApp
import dev.halone.shell.Media
import dev.halone.shell.MenuItem
import dev.halone.shell.NativeApp
import dev.halone.shell.NativeApps
import dev.halone.shell.PackageApps
import dev.halone.shell.PluginPackage
import dev.halone.shell.ProvidedApp
import dev.halone.shell.ProvidedStatusItem
import dev.halone.shell.SharedFiles
import dev.halone.shell.Shell
import dev.halone.shell.ShellPlatform
import dev.halone.shell.ShellSettings
import dev.halone.shell.Shortcuts
import dev.halone.shell.Sockets
import dev.halone.shell.StorageAccess
import dev.halone.shell.SystemInfo
import dev.halone.shell.WebApp
import dev.halone.shell.WebAppControl
import dev.halone.shell.WebApps
import dev.halone.shell.WebManifest
import dev.halone.shell.WindowKind
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * The shell on the glasses: system apps from `https://halone.local/` on WebView
 * with the bridge, web windows on Gecko without it, all in the stereo session's
 * scene. Everything here runs on the UI thread except brightness, which is a
 * blocking HID round trip.
 */
class ShellSession(
    private val context: Context,
    private val stereo: GlassesStereoSession,
    deviceName: String,
    private val brightness: BrightnessControl?,
    private val onRecenter: () -> Unit,
    private val onPointer: (String) -> Unit,
    private val onTextField: (WebViewEngine, TextField?) -> Unit,
    private val geckoKeyboard: GeckoKeyboard,
    private val pluginKeyboard: dev.halone.engine.plugin.PluginKeyboard,
    /** Asks the wearer, on the phone, to share the sound for a recording; null when they decline. */
    private val requestProjection: ((android.media.projection.MediaProjection?) -> Unit) -> Unit,
    /** Asks the wearer, on the phone, for Android permissions; told which were granted. */
    private val requestPermissions: (Array<String>, (Set<String>) -> Unit) -> Unit,
    private val log: (String) -> Unit,
) : ShellPlatform<Panel> {
    private val handler = Handler(Looper.getMainLooper())
    private val hardware = Executors.newSingleThreadExecutor()
    private val poller = object : Runnable {
        override fun run() {
            shell.poll()
            bridge.checkFileAccess()
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** Declared before [shell], whose constructor already applies settings to them. */
    private val browsers = HashMap<String, BrowserEngine>()
    private val webAppWindows = HashMap<String, WebAppEngine>()
    private val webApps = WebApps(
        FileStore(context, "webapps.json", hardware),
        loadIcon = { url, done -> loadIcon(url, done) },
        onMain = { handler.post(it) },
    )
    private val packageApps = PackageApps(java.io.File(context.filesDir, "apps"), FileStore(context, "packages.json", hardware))
    private val pluginIo = Executors.newCachedThreadPool()
    private val pluginDirectory = PluginDirectory(context, log)
    private val pluginConnections = HashMap<String, PluginConnection>()
    private val pluginWindows = HashMap<String, PluginPanelEngine>()
    private val pluginIcons = PluginIcons(java.io.File(context.filesDir, "plugin-icons"), { pluginConnections[it] }, log)
    private val nativeApps = NativeApps(FileStore(context, "plugins.json", hardware), PluginContract.VERSION)
    private val mediaBackend = MediaPlayerBackend({ stereo.compositor }, log)
    private val notices = TransientNotice { stereo.compositor }
    private val volume = VolumeIndicator(context, notices)
    private val capture = ScreenCapture(context, { stereo.compositor }, notices, log)
    private val recorder = ScreenRecorder(context, { stereo.compositor }, notices, log) { state -> shell.setRecording(state) }

    val shell = Shell(
        scene = stereo.scene,
        systemApps = AppInfo.parseRegistry(asset(REGISTRY)),
        settings = ShellSettings(PreferencesStore(context), asset(ShellSettings.SCHEMA_ASSET)),
        platform = this,
        webApps = webApps,
        packageApps = packageApps,
        nativeApps = nativeApps,
    )

    private val files = SharedFiles(::storageRoots, AllFilesAccess(context)).also { files ->
        files.onWritten = { paths ->
            if (paths.isNotEmpty()) android.media.MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        }
    }
    private val background = Executors.newFixedThreadPool(2)
    private val browsing = BrowserData(
        BrowserHistory(FileStore(context, "history.json", hardware)),
        Bookmarks(FileStore(context, "bookmarks.json", hardware)),
        Downloads(FileStore(context, "downloads.json", hardware)),
    )
    private val downloader = Downloader(context, browsing.downloads, log) { notices.show(it, forMs = DOWNLOAD_NOTICE_MS) }
    private val sockets = Sockets(onMain = { handler.post(it) })
    private val fileServer = FileServer(files, thumbnailer = Thumbnails(context)::of, log = log).also { files.urlBase = it.base }

    val bridge = BridgeHost(
        shell,
        SystemInfo(
            version = dev.halone.app.BuildConfig.VERSION_NAME,
            deviceName = deviceName,
            verticalFovDegrees = (stereo.compositor?.renderedVerticalFovDegrees ?: 0f).toDouble(),
            displayWidthPx = EYE_WIDTH_PX,
            displayHeightPx = EYE_HEIGHT_PX,
        ),
        log,
        files = files,
        media = Media(mediaBackend),
        browsing = browsing,
        onMain = { handler.post(it) },
        background = { background.execute(it) },
        sockets = sockets,
    )

    private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

    val shortcuts = Shortcuts(asset(Shortcuts.ASSET))

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            shell.setBattery(Battery(level.toDouble() / scale, charging))
        }
    }

    /** The system apps' origin also serves the plugins' icons, from [pluginIcons]. */
    private val shellPaths = listOf(
        "/${NativeApps.ICON_PATH}/" to androidx.webkit.WebViewAssetLoader.PathHandler { path ->
            val packageName = path.substringBefore('/')
            val rest = path.substringAfter('/', "")
            val appId = rest.substringBeforeLast('/', "")
            val version = rest.substringAfterLast('/').removeSuffix(".png")
            val png = if (appId.isEmpty()) null else pluginIcons.read(packageName, appId, version)
            if (png != null) {
                android.webkit.WebResourceResponse("image/png", null, java.io.ByteArrayInputStream(png))
            } else {
                android.webkit.WebResourceResponse("image/png", null, 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }
        },
    )

    private val pluginListener = object : PluginConnection.Listener {
        override fun onApps(plugin: PluginPackage, apps: List<ProvidedApp>) = nativeApps.setApps(plugin.packageName, apps)

        override fun onOpenRequested(plugin: PluginPackage, appId: String, args: android.os.Bundle) {
            runCatching { shell.openNativePanel("${plugin.packageName}/$appId", args) }.onFailure { log("  plugins: ${plugin.packageName} could not open $appId: $it") }
        }

        override fun onLaunchRequested(plugin: PluginPackage, appId: String, args: android.os.Bundle) {
            runCatching { shell.launchNative("${plugin.packageName}/$appId", args) }.onFailure { log("  plugins: ${plugin.packageName} could not launch $appId: $it") }
        }

        override fun onStatusItem(plugin: PluginPackage, id: String, item: ProvidedStatusItem?) {
            if (item != null) shell.statusItems.set(plugin.packageName, item) else shell.statusItems.remove(plugin.packageName, id)
        }

        override fun onStopped(plugin: PluginPackage) = shell.statusItems.retain { it != plugin.packageName }
    }

    override fun statusItemClicked(provider: String, id: String) {
        pluginConnections[provider]?.clickStatusItem(id)
    }

    private fun scanPlugins() = background.execute {
        val found = runCatching { pluginDirectory.scan() }.onFailure { log("  plugins: scan failed: $it") }.getOrNull() ?: return@execute
        handler.post { nativeApps.update(found) }
    }

    /** Keeps the enabled plugins bound and nothing else; a plugin that changed is bound again, and its windows close. */
    private fun connectPlugins() {
        val runnable = nativeApps.runnable().associateBy { it.packageName }
        pluginConnections.entries.toList().forEach { (packageName, connection) ->
            if (runnable[packageName] == connection.plugin) return@forEach
            connection.unbind()
            pluginConnections.remove(packageName)
            shell.windows().filter { it.kind == WindowKind.NATIVE && it.appId?.startsWith("$packageName/") == true }.forEach { shell.close(it.id) }
            if (packageName !in runnable) background.execute { pluginIcons.forget(packageName) }
        }
        runnable.values.filter { it.packageName !in pluginConnections }.forEach { plugin ->
            val connection = PluginConnection(context, plugin, pluginIo, log)
            connection.listener = pluginListener
            pluginConnections[plugin.packageName] = connection
            connection.bind()
        }
    }

    private val devReceiver by lazy { DevReceiver(context, shell, background, log) }

    fun start() {
        handler.post(poller)
        shell.addListener(object : dev.halone.shell.ShellListener {
            override fun onPluginsChanged() = connectPlugins()
        })
        pluginDirectory.watch { scanPlugins() }
        scanPlugins()
        devReceiver.register()
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            batteryReceiver.onReceive(context, it)
        }
        shell.openOverlays()
        volume.start()
    }

    fun stop() {
        handler.removeCallbacks(poller)
        devReceiver.unregister()
        mediaBackend.close()
        fileServer.close()
        sockets.closeAll()
        pluginDirectory.unwatch()
        downloader.close()
        background.shutdown()
        volume.stop()
        stopRecording()
        notices.clear()
        runCatching { context.unregisterReceiver(batteryReceiver) }
        shell.windows().forEach { shell.close(it.id) }
        pluginConnections.values.forEach { it.unbind() }
        pluginConnections.clear()
        pluginIo.shutdown()
        shell.overlayIds.forEach { bridge.disconnect(it) }
        stereo.closeOverlays()
        hardware.shutdown()
    }

    override fun openOverlays(dock: AppInfo, dockUrl: String, launcher: AppInfo, launcherUrl: String) {
        val dockPanel = overlayPanel(dock, dockUrl) ?: return
        val launcherPanel = overlayPanel(launcher, launcherUrl) ?: return
        dockPanel.cornerRadiusPx = dock.heightPx / 2f
        stereo.openOverlays(dockPanel, launcherPanel)
    }

    private fun overlayPanel(app: AppInfo, url: String): Panel? {
        val engine = WebViewEngine(context, app.widthPx, app.heightPx, url.removePrefix(Shell.ORIGIN + "/"), log, paths = shellPaths)
        engine.bridgeConnector = BridgeConnector { port -> bridge.connect(app.id, WebMessagePortChannel(port)) }
        engine.clipboard = textClipboard
        engine.textFieldListener = { field -> onTextField(engine, field) }
        engine.selectListener = { popup, answer -> showSelect(app.id, popup, answer) }
        return stereo.sizedPanel(app.id, engine, verticalFill = fillFor(app.heightPx), distanceMetres = distance())
    }

    private var menuEngine: WebViewEngine? = null

    override fun openMenuOverlay(app: AppInfo, url: String) {
        val panel = overlayPanel(app, url) ?: return
        menuEngine = panel.engine as WebViewEngine
        panel.cornerRadiusPx = MENU_CORNER_PX
        stereo.scene.setPopupPanel(panel)
    }

    override fun prepareMenu(widthPx: Int, heightPx: Int) {
        menuEngine?.resize(widthPx, heightPx)
    }

    override fun showMenu(sourceId: String, xPx: Float, yPx: Float) {
        val scene = stereo.scene
        val parent = when (sourceId) {
            Shell.DOCK -> scene.dock
            Shell.LAUNCHER -> scene.launcher
            else -> runCatching { shell.find(sourceId).panel }.getOrNull()
        } ?: return
        scene.showPopup(parent, xPx, yPx)
    }

    override fun hideMenu() = stereo.scene.hidePopup()

    private val textClipboard = object : dev.halone.engine.webview.TextClipboard {
        override fun read() = clipboardText()
        override fun write(text: String) = shell.writeClipboardText(text)
    }

    private val clipboard get() = context.getSystemService(android.content.ClipboardManager::class.java)

    override fun clipboardText(): String? =
        runCatching { clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()

    override fun setClipboardText(text: String) {
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("halone", text))
    }

    override fun clearBrowsingData(cookies: Boolean, cache: Boolean) {
        var flags = 0L
        if (cookies) {
            flags = flags or org.mozilla.geckoview.StorageController.ClearFlags.COOKIES or
                org.mozilla.geckoview.StorageController.ClearFlags.DOM_STORAGES or
                org.mozilla.geckoview.StorageController.ClearFlags.AUTH_SESSIONS or
                org.mozilla.geckoview.StorageController.ClearFlags.PERMISSIONS
        }
        if (cache) flags = flags or org.mozilla.geckoview.StorageController.ClearFlags.ALL_CACHES
        GeckoPanel.sharedRuntime(context).storageController.clearData(flags).accept(
            { log("  browser: cleared ${if (cookies) "cookies " else ""}${if (cache) "cache" else ""}") },
            { error -> log("  browser: clearing failed: $error") },
        )
    }

    override fun openApp(windowId: String, app: AppInfo, url: String): Panel? {
        val engine = WebViewEngine(context, app.widthPx, app.heightPx, url.removePrefix(Shell.ORIGIN + "/"), log, paths = shellPaths)
        engine.bridgeConnector = BridgeConnector { port -> bridge.connect(windowId, WebMessagePortChannel(port)) }
        engine.clipboard = textClipboard
        engine.textFieldListener = { field -> onTextField(engine, field) }
        engine.selectListener = { popup, answer -> showSelect(windowId, popup, answer) }
        val panel = stereo.openPanel(windowId, engine, verticalFill = fillFor(app.heightPx), distanceMetres = distance())
            ?: return null
        val defaults = SizeLimits.defaultFor(app.widthPx, app.heightPx)
        panel.sizeLimits = defaults
        engine.onLoaded = {
            engine.evaluate(DECLARED_LIMITS_JS) { result -> panel.sizeLimits = declaredLimits(result, defaults) }
        }
        return panel
    }

    /** What a page declares about its size, over [defaults] for anything it leaves out. */
    private fun declaredLimits(result: String?, defaults: SizeLimits): SizeLimits {
        val json = result?.let { runCatching { org.json.JSONObject(org.json.JSONTokener(it).nextValue() as String) }.getOrNull() }
            ?: return defaults
        fun read(key: String, fallback: Int) = json.optInt(key, 0).takeIf { it > 0 } ?: fallback
        return SizeLimits(
            minWidthPx = read("minWidth", defaults.minWidthPx),
            minHeightPx = read("minHeight", defaults.minHeightPx),
            maxWidthPx = read("maxWidth", defaults.maxWidthPx),
            maxHeightPx = read("maxHeight", defaults.maxHeightPx),
        )
    }

    override fun openWeb(windowId: String, url: String, private: Boolean): Panel? {
        val chromeApp = shell.apps.firstOrNull { it.id == BROWSER_CHROME } ?: return null
        val chrome = WebViewEngine(context, chromeApp.widthPx, chromeApp.heightPx, chromeApp.entry.removePrefix("/"), log, paths = shellPaths)
        val content = GeckoPanel(context, WEB_WIDTH_PX, WEB_HEIGHT_PX, url, log, geckoKeyboard, desktopUserAgent(), private, requestPermissions)
        content.clipboard = textClipboard
        chrome.clipboard = textClipboard
        GeckoExtensions.of(GeckoPanel.sharedRuntime(context), log).setAdBlocker(adBlocker())
        content.onVisited = { tab, visited -> browsing.history.visited(visited, tab.title) }
        content.onTitle = { tab -> if (!tab.private) tab.url?.let { browsing.history.titled(it, tab.title) } }
        content.onPageIcon = { tab -> tab.url?.let { page -> tab.iconUrl?.let { browsing.bookmarks.iconFound(page, it) } } }
        content.onDownload = { _, response -> if (isPackage(response)) offerDownload(windowId, response) else downloader.save(response) }
        val browser = BrowserEngine(
            context,
            content,
            chrome,
            autoHideToolbar = { shell.settings.boolean(ShellSettings.BROWSER_AUTO_HIDE_TOOLBAR) },
            host = browserHost(windowId),
        )
        chrome.bridgeConnector = BridgeConnector { port -> bridge.connect(windowId, WebMessagePortChannel(port)) }
        chrome.textFieldListener = { field ->
            browser.chromeFieldFocused = field != null
            onTextField(chrome, field)
        }
        val panel = stereo.openPanel(windowId, browser, verticalFill = fillFor(browser.heightPx), distanceMetres = distance())
            ?: return null.also { browser.close() }
        browsers[windowId] = browser
        browser.onFullScreenChange = { full -> fitFullScreen(windowId, full) }
        panel.sizeLimits = SizeLimits(
            minWidthPx = BROWSER_MIN_WIDTH_PX,
            minHeightPx = BROWSER_MIN_HEIGHT_PX,
            maxWidthPx = (browser.widthPx * SizeLimits.MAX_GROWTH).toInt(),
            maxHeightPx = (browser.heightPx * SizeLimits.MAX_GROWTH).toInt(),
        )
        return panel
    }

    override fun browser(windowId: String): BrowserControl? = browsers[windowId]

    /** A page's dropdown over window or overlay [sourceId]: a menu at the field, or for several choices a dialog. */
    private fun showSelect(sourceId: String, popup: SelectPopup, answer: (List<Int>?) -> Unit) {
        val items = mutableListOf<MenuItem>()
        var group: String? = null
        popup.options.forEachIndexed { index, option ->
            if (option.group != group) {
                group = option.group
                option.group?.let { items += MenuItem("group:$index", it, disabled = true) }
            }
            val label = if (option.group != null) "    ${option.label}" else option.label
            items += MenuItem(index.toString(), label, disabled = option.disabled, checked = option.selected)
        }
        if (items.none { !it.separator && !it.disabled }) return answer(null)
        runCatching {
            if (!popup.multiple) {
                shell.showMenu(sourceId, items, popup.xPx, popup.yPx) { choice -> answer(choice?.toIntOrNull()?.let(::listOf)) }
            } else {
                val dialog = org.json.JSONObject()
                    .put("kind", "choices")
                    .put("title", "")
                    .put("message", "")
                    .put("choices", org.json.JSONArray(items.map { it.toJson() }))
                    .put(
                        "buttons",
                        org.json.JSONArray()
                            .put(org.json.JSONObject().put("id", "cancel").put("label", "Cancel"))
                            .put(org.json.JSONObject().put("id", "ok").put("label", "OK").put("primary", true)),
                    )
                shell.showDialog(sourceId, dialog) { choice, chosen ->
                    val selected = chosen.optJSONArray("selected") ?: org.json.JSONArray()
                    answer(if (choice != "ok") null else (0 until selected.length()).mapNotNull { selected.optString(it).toIntOrNull() })
                }
            }
        }.onFailure {
            log("  shell: dropdown over $sourceId failed: $it")
            answer(null)
        }
    }

    private fun isPackage(response: org.mozilla.geckoview.WebResponse): Boolean {
        val headers = response.headers.mapKeys { it.key.lowercase() }
        if (headers["content-type"]?.substringBefore(';')?.trim() == HalonePackage.MIME_TYPE) return true
        val name = android.webkit.URLUtil.guessFileName(response.uri, headers["content-disposition"], null)
        return name.endsWith(".${HalonePackage.EXTENSION}")
    }

    /** A package downloaded in web window [windowId]: kept aside, read, and offered for install over that window. */
    private fun offerDownload(windowId: String, response: org.mozilla.geckoview.WebResponse) {
        val body = response.body ?: return
        val source = runCatching { java.net.URI(response.uri).host }.getOrNull() ?: response.uri
        val folder = java.io.File(context.cacheDir, "packages").apply { mkdirs() }
        val file = java.io.File(folder, "download-${System.currentTimeMillis()}.${HalonePackage.EXTENSION}")
        background.execute {
            val read = runCatching {
                body.use { input -> file.outputStream().use { input.copyTo(it) } }
                HalonePackage.read(file, shell.reservedIds, dev.halone.app.BuildConfig.VERSION_NAME)
            }
            handler.post {
                read.fold(
                    { pkg -> runCatching { shell.offerPackage(windowId, pkg, source) { file.delete() } }.onFailure { file.delete() } },
                    { error ->
                        file.delete()
                        notices.show("Not a Halone app: ${error.message}", forMs = DOWNLOAD_NOTICE_MS)
                    },
                )
            }
        }
    }

    override fun openWebApp(windowId: String, app: WebApp, url: String): Panel? {
        val displayMode = when (app.display) {
            "fullscreen" -> GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN
            "minimal-ui" -> GeckoSessionSettings.DISPLAY_MODE_MINIMAL_UI
            else -> GeckoSessionSettings.DISPLAY_MODE_STANDALONE
        }
        val content = GeckoPanel(
            context, WEB_WIDTH_PX, WEB_HEIGHT_PX, url, log, geckoKeyboard, desktopUserAgent(),
            androidPermissions = requestPermissions, displayMode = displayMode,
        )
        content.clipboard = textClipboard
        GeckoExtensions.of(GeckoPanel.sharedRuntime(context), log).setAdBlocker(adBlocker())
        content.onVisited = { tab, visited -> browsing.history.visited(visited, tab.title) }
        content.onTitle = { tab -> tab.url?.let { browsing.history.titled(it, tab.title) } }
        content.onDownload = { _, response -> downloader.save(response) }
        val engine = WebAppEngine(context, content, browserHost(windowId))
        val panel = stereo.openPanel(windowId, engine, verticalFill = fillFor(engine.heightPx), distanceMetres = distance())
            ?: return null.also { engine.close() }
        webAppWindows[windowId] = engine
        engine.onFullScreenChange = { full -> fitFullScreen(windowId, full) }
        panel.sizeLimits = SizeLimits(
            minWidthPx = BROWSER_MIN_WIDTH_PX,
            minHeightPx = BROWSER_MIN_HEIGHT_PX,
            maxWidthPx = (engine.widthPx * SizeLimits.MAX_GROWTH).toInt(),
            maxHeightPx = (engine.heightPx * SizeLimits.MAX_GROWTH).toInt(),
        )
        return panel
    }

    override fun webApp(windowId: String): WebAppControl? = webAppWindows[windowId]

    override fun openPackageApp(windowId: String, app: InstalledApp, files: java.io.File?, url: String): Panel? {
        val uri = android.net.Uri.parse(url)
        val origin = "${uri.scheme}://${uri.encodedAuthority}"
        val path = url.removePrefix("$origin/")
        val engine = WebViewEngine(context, app.manifest.widthPx, app.manifest.heightPx, path, log, origin, files)
        engine.bridgeConnector = BridgeConnector { port -> bridge.connect(windowId, WebMessagePortChannel(port)) }
        engine.clipboard = textClipboard
        engine.textFieldListener = { field -> onTextField(engine, field) }
        engine.selectListener = { popup, answer -> showSelect(windowId, popup, answer) }
        val panel = stereo.openPanel(windowId, engine, verticalFill = fillFor(app.manifest.heightPx), distanceMetres = distance())
            ?: return null
        val defaults = SizeLimits.defaultFor(app.manifest.widthPx, app.manifest.heightPx)
        panel.sizeLimits = defaults
        engine.onLoaded = {
            engine.evaluate(DECLARED_LIMITS_JS) { result -> panel.sizeLimits = declaredLimits(result, defaults) }
        }
        return panel
    }

    override fun openNativeApp(windowId: String, app: NativeApp, args: Any?): Panel? {
        val window = object : PluginWindow {
            override fun setTitle(title: String) = act { shell.setTitle(windowId, title) }
            override fun close() = act { shell.close(windowId) }
            override fun focus() = act { shell.focus(windowId) }
            override fun setBackButton(visible: Boolean) = act { shell.find(windowId).panel.backButton = visible }
            override fun setPinned(pinned: Boolean) = act { shell.setPinned(windowId, pinned) }
            override fun resize(widthPx: Int, heightPx: Int) = act { shell.resize(windowId, widthPx, heightPx) }
            override fun setSizeLimits(minWidthPx: Int, minHeightPx: Int, maxWidthPx: Int, maxHeightPx: Int) =
                act { shell.setSizeLimits(windowId, SizeLimits(minWidthPx, minHeightPx, maxWidthPx, maxHeightPx)) }

            private fun act(block: () -> Unit) {
                runCatching(block).onFailure { log("  plugins: window $windowId: $it") }
            }
        }
        val engine = PluginPanelEngine(
            pluginConnections[app.packageName], app.app.id, args as android.os.Bundle? ?: android.os.Bundle(), app.app.name,
            app.app.widthPx, app.app.heightPx, window, log, pluginKeyboard,
        )
        val panel = stereo.openPanel(windowId, engine, verticalFill = fillFor(app.app.heightPx), distanceMetres = distance())
            ?: return null.also { engine.close() }
        panel.sizeLimits = SizeLimits.defaultFor(app.app.widthPx, app.app.heightPx)
        pluginWindows[windowId] = engine
        return panel
    }

    override fun reopenNativeApp(windowId: String) {
        pluginWindows[windowId]?.reopenIfStopped()
    }

    override fun clearAppData(app: InstalledApp) {
        android.webkit.WebStorage.getInstance().deleteOrigin(app.origin)
        val cookies = android.webkit.CookieManager.getInstance()
        cookies.getCookie(app.origin)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
            cookies.setCookie(app.origin, "$name=; Max-Age=0")
        }
        cookies.flush()
    }

    override fun keyCaptureOn() = KeyCaptureService.running

    override fun openKeyCaptureSettings() {
        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun notice(text: String) = notices.show(text, forMs = DOWNLOAD_NOTICE_MS)

    /** An app's icon, scaled down and kept as a `data:` URL so the launcher shows it offline. */
    private fun loadIcon(url: String, done: (String?) -> Unit) {
        fun encode(bytes: ByteArray?) = background.execute { done(bytes?.let(::iconDataUrl)) }
        if (url.startsWith("data:")) return encode(runCatching { decodeDataUrl(url) }.getOrNull())
        val request = org.mozilla.geckoview.WebRequest.Builder(url).build()
        org.mozilla.geckoview.GeckoWebExecutor(GeckoPanel.sharedRuntime(context)).fetch(request).accept(
            { response ->
                background.execute {
                    val bytes = runCatching { response?.takeIf { it.statusCode in 200..299 }?.body?.use { it.readBytes() } }.getOrNull()
                    done(bytes?.let(::iconDataUrl))
                }
            },
            { error ->
                log("  webapps: icon $url failed: $error")
                done(null)
            },
        )
    }

    private fun decodeDataUrl(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) return null
        return android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
    }

    private fun iconDataUrl(bytes: ByteArray): String? {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = minOf(1f, WebManifest.ICON_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        } else {
            bitmap
        }
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun browserHost(windowId: String) = object : BrowserHost {
        override fun showMenu(items: List<MenuItem>, xPx: Float, yPx: Float, onChosen: (String?) -> Unit): Int? =
            runCatching { shell.showMenu(windowId, items, xPx, yPx, onChosen).menuId }.onFailure { log("  browser: menu failed: $it") }.getOrNull()

        override fun showDialog(dialog: org.json.JSONObject, onAnswer: (String?, org.json.JSONObject) -> Unit): Int? =
            runCatching { shell.showDialog(windowId, dialog, onAnswer).menuId }.onFailure { log("  browser: dialog failed: $it") }.getOrNull()

        override fun cancelPopup(id: Int) {
            if (shell.menu?.menuId == id) shell.chooseMenu(id, null)
        }

        override fun openWindow(url: String, private: Boolean) {
            runCatching { shell.openUrl(url, private) }.onFailure { log("  browser: could not open $url: $it") }
        }

        override fun openInBrowser(url: String, private: Boolean) {
            runCatching { shell.openInBrowser(url, private) }.onFailure { log("  browser: could not open $url: $it") }
        }

        override fun clipboardText() = this@ShellSession.clipboardText()

        override fun setClipboardText(text: String) = shell.writeClipboardText(text)

        override fun download(url: String, referrer: String?, private: Boolean) =
            downloader.fetch(GeckoPanel.sharedRuntime(context), url, referrer, private)

        override fun searchUrl(text: String): String {
            val query = java.net.URLEncoder.encode(text, "UTF-8")
            return when (shell.settings.string(ShellSettings.BROWSER_SEARCH_ENGINE)) {
                "google" -> "https://www.google.com/search?q=$query"
                "bing" -> "https://www.bing.com/search?q=$query"
                else -> "https://duckduckgo.com/?q=$query"
            }
        }

        override fun isBookmarked(url: String) = browsing.bookmarks.find(url) != null

        override fun toggleBookmark() {
            val browser = browsers[windowId] ?: return
            val tab = browser.state().active ?: return
            browsing.bookmarks.find(tab.url)?.let { browsing.bookmarks.remove(it.id) }
                ?: runCatching { browsing.bookmarks.add(tab.url, tab.title, browser.iconUrl()) }
        }
    }

    private fun adBlocker() = shell.settings.boolean(ShellSettings.BROWSER_AD_BLOCKER)

    /** Sizes before full screen, to go back to. */
    private val sizesBeforeFullScreen = HashMap<String, Pair<Int, Int>>()

    /**
     * Gives a web window exactly the screen Gecko was told about while its page
     * is full screen, so the full-screen element fills it, and then its old size.
     */
    private fun fitFullScreen(windowId: String, full: Boolean) {
        val window = runCatching { shell.find(windowId) }.getOrNull() ?: return
        if (full) {
            sizesBeforeFullScreen.getOrPut(windowId) { window.panel.widthPx to window.panel.heightPx }
            shell.resize(windowId, GeckoPanel.SCREEN_WIDTH_PX, GeckoPanel.SCREEN_HEIGHT_PX)
        } else {
            val (width, height) = sizesBeforeFullScreen.remove(windowId) ?: return
            shell.resize(windowId, width, height)
        }
    }

    override fun captureScreen(delayMs: Long) = capture.take(delayMs)

    override fun startRecording(delayMs: Long) {
        requestProjection { projection -> handler.postDelayed({ recorder.start(projection) }, delayMs) }
    }

    override fun stopRecording() {
        if (!recorder.recording) return
        recorder.stop()
        dev.halone.app.session.XrSessionService.endProjection(context)
    }

    override fun setWindowsAutoHide(enabled: Boolean) {
        stereo.scene.windowsAutoHide = enabled
    }

    private fun storageRoots(): List<FileRoot> {
        fun public(type: String) = android.os.Environment.getExternalStoragePublicDirectory(type).path
        val storage = android.os.Environment.getExternalStorageDirectory().path
        return listOf(
            FileRoot("storage", "Phone", storage),
            FileRoot("pictures", "Pictures", public(android.os.Environment.DIRECTORY_PICTURES)),
            FileRoot("camera", "Camera", public(android.os.Environment.DIRECTORY_DCIM) + "/Camera"),
            FileRoot("movies", "Movies", public(android.os.Environment.DIRECTORY_MOVIES)),
            FileRoot("music", "Music", public(android.os.Environment.DIRECTORY_MUSIC)),
            FileRoot("downloads", "Downloads", public(android.os.Environment.DIRECTORY_DOWNLOADS)),
            FileRoot("documents", "Documents", public(android.os.Environment.DIRECTORY_DOCUMENTS)),
        )
    }

    /**
     * Shared storage for a file manager needs "all files access", which the
     * wearer grants in the phone's settings; there is no dialog for it.
     */
    private class AllFilesAccess(private val context: Context) : StorageAccess {
        override val granted: Boolean
            get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }

        override fun request() {
            val action = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            } else {
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            }
            val intent = Intent(action, android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun desktopUserAgent() = shell.settings.string(ShellSettings.BROWSER_USER_AGENT) == "desktop"

    private fun distance() = shell.settings.number(ShellSettings.PANEL_DISTANCE_METRES).toFloat()

    override fun close(panel: Panel) {
        browsers.remove(panel.id)
        webAppWindows.remove(panel.id)
        pluginWindows.remove(panel.id)
        sizesBeforeFullScreen.remove(panel.id)
        stereo.closePanel(panel)
    }

    override fun recenter() = onRecenter()

    override fun onFocusRequested(panel: Panel) {
        val describe = { stereo.scene.describe(headYawRadians()).replace("\n", " |") }
        log("  shell: focus '${panel.id}' asked: ${describe()}")
        handler.postDelayed({ log("  shell: focus '${panel.id}' after 2.5 s: ${describe()}") }, FOCUS_REPORT_MS)
    }

    override fun headYawRadians() = stereo.compositor?.headYawRadians ?: 0f

    override fun headPitchRadians() = stereo.compositor?.headPitchRadians ?: 0f

    override fun applySettings(settings: ShellSettings, changedKey: String?) {
        val scene = stereo.scene
        scene.followsHead = settings.boolean(ShellSettings.FOLLOW_ENABLED)
        scene.followMarginDegrees = settings.number(ShellSettings.FOLLOW_MARGIN_DEGREES).toFloat()
        scene.catchUpSeconds = settings.number(ShellSettings.FOLLOW_CATCH_UP_SECONDS).toFloat()
        scene.collisionPolicy = when (settings.string(ShellSettings.COLLISION_POLICY)) {
            "overlap" -> CollisionPolicy.OVERLAP
            "avoid-pinned" -> CollisionPolicy.AVOID_PINNED
            else -> CollisionPolicy.AVOID_ALL
        }
        scene.dockVisibility = if (settings.string(ShellSettings.DOCK_VISIBILITY) == "auto-hide") {
            DockVisibility.AUTO_HIDE
        } else {
            DockVisibility.ALWAYS
        }
        scene.windowsHideDelaySeconds = settings.number(ShellSettings.WINDOWS_AUTO_HIDE_DELAY_SECONDS).toFloat()
        val spacing = settings.number(ShellSettings.SLOT_SPACING_DEGREES).toFloat()
        if (spacing != scene.slotSpacingDegrees) scene.layOutCylindrical(spacing, immediate = false)

        if (changedKey == ShellSettings.DISPLAY_BRIGHTNESS) {
            val control = brightness
            val level = settings.number(ShellSettings.DISPLAY_BRIGHTNESS)
            if (control != null) hardware.execute { applyBrightness(control, level) }
        }
        if (changedKey == ShellSettings.PANEL_DISTANCE_METRES) {
            val fov = stereo.compositor?.verticalFovDegrees
            val metres = settings.number(ShellSettings.PANEL_DISTANCE_METRES).toFloat()
            // Refitted, so only the stereo disparity changes and never the size.
            if (fov != null) (scene.panels + listOfNotNull(scene.dock, scene.launcher)).forEach { panel ->
                panel.pose = panel.pose.copy(distanceMetres = metres)
                panel.fitToFov(fov, fillFor(panel.engine.heightPx))
            }
        }
        if (changedKey == ShellSettings.INPUT_POINTER) onPointer(settings.string(ShellSettings.INPUT_POINTER))
        if (changedKey == null || changedKey == ShellSettings.DEVELOPER_ENABLED) {
            val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            android.webkit.WebView.setWebContentsDebuggingEnabled(debuggable || settings.boolean(ShellSettings.DEVELOPER_ENABLED))
        }
        mediaBackend.screenWidthDegrees = settings.number(ShellSettings.MEDIA_SCREEN_WIDTH_DEGREES).toFloat()
        mediaBackend.sphereZoom = settings.number(ShellSettings.MEDIA_SPHERE_ZOOM).toFloat()
        mediaBackend.sphereDistanceMetres = settings.number(ShellSettings.MEDIA_SPHERE_DISTANCE_METRES).toFloat()
        browsers.values.forEach {
            it.settingsChanged()
            it.setDesktopUserAgent(desktopUserAgent())
        }
        webAppWindows.values.forEach { it.setDesktopUserAgent(desktopUserAgent()) }
        if (browsers.isNotEmpty() || webAppWindows.isNotEmpty()) GeckoExtensions.of(GeckoPanel.sharedRuntime(context), log).setAdBlocker(adBlocker())
    }

    private fun applyBrightness(control: BrightnessControl, fraction: Double) {
        val range = control.range
        val level = range.first + ((range.last - range.first) * fraction).roundToInt()
        control.set(level).onFailure { log("  shell: brightness $level failed: $it") }
    }

    /**
     * One CSS pixel subtends the same angle on every panel, so an app chooses
     * its size by choosing its pixels, as in the simulator. An 800px-tall panel
     * fills the session's usual 75%.
     */
    private fun fillFor(heightPx: Int) =
        GlassesStereoSession.PANEL_VERTICAL_FILL * heightPx / REFERENCE_HEIGHT_PX

    companion object {
        private const val REGISTRY = "apps.json"
        private const val BROWSER_CHROME = "browser"
        private const val MENU_CORNER_PX = 14f
        private const val DOWNLOAD_NOTICE_MS = 2_500L

        /** A phone-sized web window, as Chrome on the desktop allows. */
        private const val BROWSER_MIN_WIDTH_PX = 400
        private const val BROWSER_MIN_HEIGHT_PX = 600

        /**
         * A page's declared size limits: `<meta name="halone:window" content="min-width=…,
         * min-height=…, max-width=…, max-height=…">`, and failing a minimum width there,
         * a numeric `width=` in the viewport tag. Kept in step with the simulator's
         * `limits.ts`, which reads the same tags.
         */
        private val DECLARED_LIMITS_JS = """
            (() => {
              const read = (name) => document.querySelector('meta[name="' + name + '"]')?.content ?? '';
              const pairs = (text) => Object.fromEntries(text.split(',').map((p) => p.split('=').map((s) => s.trim())).filter((p) => p.length === 2));
              const n = (v) => { const x = parseInt(v, 10); return x > 0 ? x : 0; };
              const own = pairs(read('halone:window'));
              const viewport = pairs(read('viewport'));
              return JSON.stringify({
                minWidth: n(own['min-width']) || n(viewport['width']),
                minHeight: n(own['min-height']),
                maxWidth: n(own['max-width']),
                maxHeight: n(own['max-height']),
              });
            })()
        """.trimIndent()
        private const val POLL_MS = 200L
        private const val FOCUS_REPORT_MS = 2_500L
        private const val EYE_WIDTH_PX = 1920
        private const val EYE_HEIGHT_PX = 1080
        private const val WEB_WIDTH_PX = GeckoPanel.SCREEN_WIDTH_PX
        private const val WEB_HEIGHT_PX = GeckoPanel.SCREEN_HEIGHT_PX
        private const val REFERENCE_HEIGHT_PX = 800f
    }
}
