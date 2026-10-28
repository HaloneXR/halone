package dev.halone.shell

import dev.halone.compositor.Corner
import dev.halone.compositor.Panel
import dev.halone.compositor.Scene
import dev.halone.compositor.TitleBar
import dev.halone.compositor.SizeLimits
import dev.halone.compositor.StereoRegion
import org.json.JSONArray
import org.json.JSONObject

/** What the shell needs from whatever hosts it: engines, the head, and the hardware. */
interface ShellPlatform {
    /** A system app on WebView with the bridge; null when there is nowhere to show it. */
    fun openApp(windowId: String, app: AppInfo, url: String): Panel?

    /** Arbitrary web content, without the bridge; [private] makes its first tab a private one. */
    fun openWeb(windowId: String, url: String, private: Boolean = false): Panel?

    /** An installed web app at [url], without the browser's chrome and without the bridge. */
    fun openWebApp(windowId: String, app: WebApp, url: String): Panel? = null

    /** A Halone app from a package, on WebView with the bridge, in its own origin; [files] is null for a dev app. */
    fun openPackageApp(windowId: String, app: InstalledApp, files: java.io.File?, url: String): Panel? = null

    /** A plugin app's window; [args] are what the plugin asked to open it with, or null from the launcher. */
    fun openNativeApp(windowId: String, app: NativeApp, args: android.os.Bundle?): Panel? = null

    /** Brings plugin window [windowId] back to life if its plugin stopped, before it is focused. */
    fun reopenNativeApp(windowId: String) {}

    /** The wearer clicked status item [id] of plugin [provider]. */
    fun statusItemClicked(provider: String, id: String) {}

    /** Deletes what an uninstalled package kept in its origin: storage, cookies, caches. */
    fun clearAppData(app: InstalledApp) {}

    /** The page of a web app's window, or null for any other. */
    fun webApp(windowId: String): WebAppControl? = null

    /** Whether key capture, the accessibility service that takes Alt+Tab, Meta and PrintScreen from Android, is on. */
    fun keyCaptureOn(): Boolean = false

    /** Opens Android's accessibility settings on the phone, where key capture is turned on. */
    fun openKeyCaptureSettings(): Unit = throw HaloneException("unavailable", "this host has no key capture")

    fun pointingDeviceConnected(): Boolean = false

    /** Offers Halone's log to the phone's share sheet, while crash reports are on. */
    fun shareLog(): Unit = throw HaloneException("unavailable", "this host keeps no log")

    fun displayState(): DisplayState = DisplayState()

    fun restartDisplay(): Unit = throw HaloneException("unavailable", "the environment is not running")

    /** A short message on the glasses, gone by itself. */
    fun notice(text: String) {}

    /** Opens the dock and the launcher, which live outside the arrangement. */
    fun openOverlays(dock: AppInfo, dockUrl: String, launcher: AppInfo, launcherUrl: String)

    fun close(panel: Panel)

    fun recenter()

    /** Native radians: positive yaw is to the left, as in `Scene`. */
    fun headYawRadians(): Float
    fun headPitchRadians(): Float

    fun applySettings(settings: ShellSettings, changedKey: String?)

    /** A window was asked to come to the front, for a platform that wants to watch it happen. */
    fun onFocusRequested(panel: Panel) {}

    /** The tabs and navigation of a web window, or null for any other. */
    fun browser(windowId: String): BrowserControl? = null

    /** Whether windows hide while the pointer is on none of them: the setting, or a window asking. */
    fun setWindowsAutoHide(enabled: Boolean) {}

    /** Saves the left eye to the gallery after [delayMs]. */
    fun captureScreen(delayMs: Long): Unit = throw HaloneException("unavailable", "this host cannot capture the view")

    /** Starts a recording after [delayMs]; the platform reports it through [Shell.setRecording]. */
    fun startRecording(delayMs: Long): Unit = throw HaloneException("unavailable", "this host cannot record the view")

    fun stopRecording() {}

    /** Opens the context menu overlay, which stays hidden until a menu is shown. */
    fun openMenuOverlay(app: AppInfo, url: String) {}

    /** Gives the menu overlay the size of the menu about to be drawn in it. */
    fun prepareMenu(widthPx: Int, heightPx: Int) {}

    /** Shows the menu overlay over [sourceId], a window or an overlay, with its corner at its pixel ([xPx], [yPx]). */
    fun showMenu(sourceId: String, xPx: Float, yPx: Float) {}

    fun hideMenu() {}

    fun clipboardText(): String? = null

    fun setClipboardText(text: String) {}

    /** Clears what Gecko keeps for sites: cookies and site storage, and its caches. */
    fun clearBrowsingData(cookies: Boolean, cache: Boolean) {}
}

interface ShellListener {
    fun onWindowsChanged() {}
    fun onSettingsChanged() {}
    fun onFocusChanged(previous: String?, current: String?) {}
    fun onLauncherChanged(open: Boolean) {}
    fun onBatteryChanged(battery: Battery) {}
    fun onDisplayChanged() {}
    fun onBrowserChanged(windowId: String, state: BrowserState) {}
    fun onFileOpened(windowId: String, path: String) {}
    fun onRecordingChanged(state: RecordingState) {}
    fun onRouted(windowId: String, route: String) {}
    fun onAppsChanged() {}
    fun onPluginsChanged() {}

    /** The wearer changed a setting of plugin [packageName]. */
    fun onPluginSettingsChanged(packageName: String) {}
    fun onStatusItemsChanged() {}

    /** Something the browser chrome of web window [windowId] should do, such as `focus-address`. */
    fun onBrowserCommand(windowId: String, command: String) {}

    /** The menu the overlay should show, or null when there is none. */
    fun onMenuChanged(menu: MenuRequest?) {}
}

data class ClipboardFiles(val paths: List<String>, val cut: Boolean)

data class RecordingState(val recording: Boolean = false, val startedAt: Long? = null, val audio: Boolean = false) {
    fun toJson(): JSONObject = JSONObject()
        .put("recording", recording)
        .put("startedAt", startedAt ?: JSONObject.NULL)
        .put("audio", audio)
}

data class Battery(val level: Double, val charging: Boolean)

data class DisplayRate(val hz: Int, val stereo: Boolean, val matchesPhone: Boolean = true)

data class DisplayState(
    val running: Boolean = false,
    val stereo: Boolean = false,
    val refreshHz: Double? = null,
    val renderFps: Double? = null,
    val rates: List<DisplayRate> = emptyList(),
    val restartNeeded: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("running", running)
        .put("stereo", stereo)
        .put("refreshHz", refreshHz ?: JSONObject.NULL)
        .put("renderFps", renderFps ?: JSONObject.NULL)
        .put("rates", JSONArray(rates.map { JSONObject().put("hz", it.hz).put("stereo", it.stereo).put("matchesPhone", it.matchesPhone) }))
        .put("restartNeeded", restartNeeded)
}

enum class WindowKind(val wire: String) { APP("app"), WEB("web"), NATIVE("native") }

class ShellWindow(
    val id: String,
    val kind: WindowKind,
    val appId: String?,
    var title: String,
    var url: String,
    val panel: Panel,
) {
    /** The file the window was last asked to open. */
    var openedFile: String? = null

    /** The limits its page declared, kept while the window overrides them. */
    var declaredLimits: SizeLimits? = null

    /** Where `apps.launch` last asked the window to go. */
    var route: String? = null

    /** Set once the window has its remembered size, or the wearer chose one. */
    internal var sizeSettled = false
}

/**
 * The size the wearer last chose for each app's windows, so an app that opens
 * portrait comes back at the shape it was given.
 */
interface WindowSizeStore {
    fun load(key: String): Pair<Int, Int>?
    fun save(key: String, widthPx: Int, heightPx: Int)
}

/**
 * The window manager behind `halone.windows`, `halone.apps` and `halone.settings`.
 *
 * Arrangement, following and collision stay in [Scene]; the shell adds what a
 * window is — which app, what title, whether it gets the bridge — and turns
 * requests into scene operations. Call on the UI thread.
 */
class Shell(
    private val scene: Scene,
    private val systemApps: List<AppInfo>,
    val settings: ShellSettings,
    private val platform: ShellPlatform,
    val webApps: WebApps? = null,
    val packageApps: PackageApps? = null,
    val nativeApps: NativeApps? = null,
    val pluginSettings: PluginSettings? = null,
) {
    /** The system apps, then the sites installed as apps, then the Halone apps, then the plugins' apps. */
    val apps: List<AppInfo>
        get() = systemApps +
            webApps?.list().orEmpty().map { it.toAppInfo() } +
            packageApps?.let { store -> store.list().map { it.toAppInfo(store.iconUrl(it)) } }.orEmpty() +
            nativeApps?.list().orEmpty().map { it.toAppInfo() }

    /** Ids no package may take. */
    val reservedIds: Set<String> get() = systemApps.map { it.id }.toSet() + overlayIds

    /** What the bridge lets window [id] do: everything for the shell's own windows, a package's grant for its. */
    fun grantOf(id: String): Set<Permission>? {
        val window = windows[id] ?: return null
        return packageApps?.find(window.appId)?.granted
    }

    /** What plugins show in the dock's status area. */
    val statusItems = StatusItems()

    private val windows = LinkedHashMap<String, ShellWindow>()
    private val listeners = mutableListOf<ShellListener>()
    private var nextWindow = 1
    private var lastFocused: String? = null
    private var lastLauncherShown: Boolean? = null
    private val autoHideRequests = HashSet<String>()
    private var lastWebWindow: String? = null
    private var nextMenu = 1
    private var clipboardFiles: ClipboardFiles? = null
    private var clipboardFilesText: String? = null

    /** The context menu being shown, or about to be. */
    var menu: MenuRequest? = null
        private set

    var battery: Battery? = null
        private set

    var recording = RecordingState()
        private set

    fun setRecording(state: RecordingState) {
        if (state == recording) return
        recording = state
        listeners.forEach { it.onRecordingChanged(state) }
    }

    fun captureScreen(delayMs: Long) = platform.captureScreen(delayMs.coerceIn(0, MAX_DELAY_MS))

    fun startRecording(delayMs: Long) {
        if (recording.recording) return
        platform.startRecording(delayMs.coerceIn(0, MAX_DELAY_MS))
    }

    fun stopRecording() = platform.stopRecording()

    init {
        platform.applySettings(settings, null)
        platform.setWindowsAutoHide(windowsAutoHide)
        webApps?.onChange = { listeners.forEach { it.onAppsChanged() } }
        packageApps?.onChange = { listeners.forEach { it.onAppsChanged() } }
        statusItems.onChange = { listeners.forEach { it.onStatusItemsChanged() } }
        nativeApps?.onChange = {
            val running = nativeApps.runnable().map { it.packageName }.toSet()
            statusItems.retain { it in running }
            val present = nativeApps.list().map { it.id }.toSet()
            windows.values.filter { it.kind == WindowKind.NATIVE && it.appId !in present }.forEach { close(it.id) }
            listeners.forEach { it.onAppsChanged() }
            listeners.forEach { it.onPluginsChanged() }
        }
        nativeApps?.onDiscovered = { plugin -> platform.notice("New plugin “${plugin.label}”: turn it on in Settings → Plugins") }
    }

    val windowsAutoHide: Boolean get() = settings.boolean(ShellSettings.WINDOWS_AUTO_HIDE) || autoHideRequests.isNotEmpty()

    /** A window's own request for the auto-hide, which ends when it closes. */
    fun setAutoHideRequest(id: String, enabled: Boolean) {
        find(id)
        if (enabled) autoHideRequests += id else autoHideRequests -= id
        platform.setWindowsAutoHide(windowsAutoHide)
    }

    fun addListener(listener: ShellListener) {
        listeners += listener
    }

    fun windows(): List<ShellWindow> = windows.values.toList()

    fun find(id: String): ShellWindow =
        windows[id] ?: throw HaloneException("no-such-window", "no such window: $id")

    /** Opens the dock and the launcher; called once the platform can show them. */
    fun openOverlays() {
        val dock = apps.firstOrNull { it.id == DOCK } ?: return
        val launcher = apps.firstOrNull { it.id == LAUNCHER } ?: return
        platform.openOverlays(dock, ORIGIN + dock.entry, launcher, ORIGIN + launcher.entry)
        apps.firstOrNull { it.id == MENU }?.let { platform.openMenuOverlay(it, ORIGIN + it.entry) }
        checkLauncher()
    }

    val overlayIds: Set<String> get() = setOf(DOCK, LAUNCHER, MENU)

    /**
     * Shows a context menu over [sourceId], a window or an overlay, replacing
     * any menu already up, which is dismissed. [onChosen] hears the item
     * chosen, or null when the menu goes without a choice. The menu overlay
     * draws it first and says so with [menuRendered]; only then does it show,
     * so the wearer never sees the last menu in the new one's place.
     */
    fun showMenu(sourceId: String, items: List<MenuItem>, xPx: Float, yPx: Float, onChosen: (String?) -> Unit): MenuRequest {
        if (sourceId != DOCK && sourceId != LAUNCHER) find(sourceId)
        val (width, height) = MenuLayout.size(items)
        return present(MenuRequest(nextMenu++, items, sourceId, xPx, yPx) { itemId, _ -> onChosen(itemId) }, width, height)
    }

    /**
     * Shows a dialog centred over the window [sourceId], replacing any menu
     * or dialog already up. [onAnswer] hears the button pressed, or null when
     * it goes without one, and what its fields held.
     */
    fun showDialog(sourceId: String, dialog: JSONObject, onAnswer: (String?, JSONObject) -> Unit): MenuRequest {
        val panel = find(sourceId).panel
        val (width, height) = DialogLayout.size(dialog)
        val x = ((panel.widthPx - width) / 2f).coerceAtLeast(0f)
        val y = ((panel.heightPx - height) / 2f).coerceAtLeast(0f)
        return present(MenuRequest(nextMenu++, emptyList(), sourceId, x, y, dialog, onAnswer), width, height)
    }

    private fun present(request: MenuRequest, width: Int, height: Int): MenuRequest {
        dismissMenu()
        menu = request
        platform.prepareMenu(width, height)
        listeners.forEach { it.onMenuChanged(request) }
        return request
    }

    fun menuRendered(menuId: Int) {
        val request = menu?.takeIf { it.menuId == menuId } ?: return
        platform.showMenu(request.sourceId, request.xPx, request.yPx)
    }

    fun chooseMenu(menuId: Int, itemId: String?, answer: JSONObject = JSONObject()) {
        val request = menu?.takeIf { it.menuId == menuId } ?: return
        if (itemId != null && !request.accepts(itemId)) throw HaloneException("invalid-params", "no such item: $itemId")
        menu = null
        platform.hideMenu()
        listeners.forEach { it.onMenuChanged(null) }
        request.onChosen(itemId, answer)
    }

    fun dismissMenu() {
        menu?.let { chooseMenu(it.menuId, null) }
    }

    /** What is on the clipboard: the phone's text, and files put there by [writeClipboardFiles] while that text is still what it holds. */
    fun clipboard(): Pair<String?, ClipboardFiles?> {
        val text = platform.clipboardText()
        return text to clipboardFiles?.takeIf { text == clipboardFilesText }
    }

    fun writeClipboardText(text: String) {
        clipboardFiles = null
        platform.setClipboardText(text)
    }

    /** Files to paste; their paths go on the phone's clipboard as text too, so copying anything else replaces them. */
    fun writeClipboardFiles(files: ClipboardFiles) {
        val text = files.paths.joinToString("\n")
        platform.setClipboardText(text)
        clipboardFiles = files.takeIf { it.paths.isNotEmpty() }
        clipboardFilesText = text
    }

    /** Files cut and then moved are gone from where the clipboard says they are. */
    fun filesMoved(paths: List<String>) {
        val files = clipboardFiles ?: return
        if (files.cut && files.paths.any { it in paths }) clipboardFiles = null
    }

    /** Shown, which with no window open it is whatever was asked for. */
    val launcherOpen: Boolean get() = scene.launcherShown

    fun setLauncherOpen(open: Boolean) {
        scene.launcherRequested = open
        checkLauncher()
    }

    fun setBattery(battery: Battery) {
        if (battery == this.battery) return
        this.battery = battery
        listeners.forEach { it.onBatteryChanged(battery) }
    }

    fun launch(appId: String, route: String? = null): ShellWindow {
        if (appId in overlayIds) throw HaloneException("no-such-app", "$appId is part of the shell, not an app to launch")
        webApps?.find(appId)?.let { return launchWebApp(it, route) }
        packageApps?.find(appId)?.let { return launchPackageApp(it, route) }
        if (nativeApps?.find(appId) != null) return launchNative(appId, null)
        val app = apps.firstOrNull { it.id == appId } ?: throw HaloneException("no-such-app", "no such app: $appId")
        windows.values.firstOrNull { it.appId == appId }?.let { window ->
            if (route != null) {
                window.route = route
                listeners.forEach { it.onRouted(window.id, route) }
            }
            focus(window.id)
            return window
        }
        val id = nextId()
        val url = ORIGIN + app.entry
        val panel = platform.openApp(id, app, url) ?: throw unavailable()
        return add(ShellWindow(id, WindowKind.APP, app.id, app.name, url, panel).also { it.route = route })
    }

    private fun launchPackageApp(app: InstalledApp, route: String?): ShellWindow {
        windows.values.firstOrNull { it.appId == app.id }?.let { window ->
            if (route != null) {
                window.route = route
                listeners.forEach { it.onRouted(window.id, route) }
            }
            focus(window.id)
            return window
        }
        val id = nextId()
        val panel = platform.openPackageApp(id, app, packageApps?.filesOf(app.id), app.entryUrl) ?: throw unavailable()
        return add(ShellWindow(id, WindowKind.APP, app.id, app.manifest.name, app.entryUrl, panel).also { it.route = route })
    }

    /** Focuses plugin app [appId]'s window, or opens one with [args], as the launcher does. */
    fun launchNative(appId: String, args: android.os.Bundle?): ShellWindow {
        val app = nativeApps?.find(appId) ?: throw HaloneException("no-such-app", "no such app: $appId")
        windows.values.firstOrNull { it.appId == appId }?.let { window ->
            platform.reopenNativeApp(window.id)
            focus(window.id)
            return window
        }
        return openNative(app, args)
    }

    /** A new window of plugin app [appId], as the plugin asked, beside any it already has. */
    fun openNativePanel(appId: String, args: android.os.Bundle?): ShellWindow {
        val app = nativeApps?.find(appId) ?: throw HaloneException("no-such-app", "no such app: $appId")
        return openNative(app, args)
    }

    private fun openNative(app: NativeApp, args: android.os.Bundle?): ShellWindow {
        val id = nextId()
        val panel = platform.openNativeApp(id, app, args) ?: throw unavailable()
        return add(ShellWindow(id, WindowKind.NATIVE, app.id, app.app.name, "", panel))
    }

    fun clickStatusItem(id: String) {
        val entry = statusItems.find(id) ?: throw HaloneException("no-such-item", "no such status item: $id")
        platform.statusItemClicked(entry.provider, entry.item.id)
    }

    /** Plugins as Settings lists them, each saying whether it has a page of settings. */
    fun plugins(): List<PluginInfo> = nativeApps?.plugins().orEmpty().map { info ->
        info.copy(hasSettings = pluginSettings?.page(info.plugin) != null)
    }

    fun pluginSettingsPage(packageName: String): PluginSettings.Page {
        val plugin = nativeApps?.plugin(packageName) ?: throw HaloneException("no-such-plugin", "no such plugin: $packageName")
        return pluginSettings?.page(plugin) ?: throw HaloneException("no-settings", "${plugin.label} has no settings")
    }

    fun setPluginSetting(packageName: String, key: String, value: Any?) {
        pluginSettingsPage(packageName).values.set(key, value)
        listeners.forEach { it.onPluginSettingsChanged(packageName) }
    }

    fun acknowledgePlugins() = nativeApps?.acknowledge()

    fun setPluginEnabled(packageName: String, enabled: Boolean) {
        val store = nativeApps ?: throw HaloneException("unavailable", "this host has no plugins")
        store.setEnabled(packageName, enabled)
    }

    /** Installs [pkg], which the wearer agreed to, and closes the old version's window so the new one opens next. */
    fun installPackage(pkg: HalonePackage, source: String): InstalledApp {
        val store = packageApps ?: throw HaloneException("unavailable", "this host cannot install apps")
        val app = store.install(pkg, source)
        windows.values.filter { it.appId == app.id }.forEach { close(it.id) }
        platform.notice("Installed “${app.manifest.name}”")
        return app
    }

    /**
     * Opens a web app's window, or focuses the one already open; [url], when
     * inside the app, is where it goes instead of its start.
     */
    private fun launchWebApp(app: WebApp, url: String?): ShellWindow {
        val target = url?.takeIf(app::inScope)
        windows.values.firstOrNull { it.appId == app.id }?.let { window ->
            if (target != null) platform.webApp(window.id)?.navigate(target)
            focus(window.id)
            return window
        }
        val id = nextId()
        val start = target ?: app.startUrl
        val panel = platform.openWebApp(id, app, start) ?: throw unavailable()
        val window = add(ShellWindow(id, WindowKind.APP, app.id, app.name, start, panel))
        platform.webApp(id)?.let { page -> page.onChange = { onWebAppChanged(window, app, page) } }
        return window
    }

    /** The app's name in the title bar, with the site's when the page has left the app, as for a sign-in. */
    private fun onWebAppChanged(window: ShellWindow, app: WebApp, page: WebAppControl) {
        if (windows[window.id] !== window) return
        val url = page.url ?: return
        val outside = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { !app.inScope(url) }
        val title = if (outside != null) "${app.name} — $outside" else app.name
        if (title == window.title && url == window.url) return
        window.title = title.take(MAX_TITLE)
        window.panel.title = window.title
        window.url = url
        notifyWindows()
    }

    /** The active page of web window [id] as an app that can be installed, or null when it is not one. */
    fun installableApp(id: String): WebManifest? = platform.browser(id)?.manifest()?.let(WebManifest::parse)

    /** Installs the active page of web window [id] as an app; [done] hears the app once it is in the launcher. */
    fun installWebApp(id: String, done: (Result<WebApp>) -> Unit) {
        val store = webApps ?: throw HaloneException("unavailable", "this host cannot install web apps")
        browser(id)
        val manifest = installableApp(id) ?: throw HaloneException("not-installable", "this page cannot be installed as an app")
        store.install(manifest) { app ->
            platform.notice("Added “${app.name}” to the launcher")
            done(Result.success(app))
        }
    }

    /**
     * Asks the wearer over window [sourceId] whether to install [pkg], listing
     * what it may do, or for an update only what it adds; [done] hears the app
     * installed, or null when they decline or it cannot be.
     */
    fun offerPackage(sourceId: String, pkg: HalonePackage, source: String, done: (InstalledApp?) -> Unit = {}) {
        val store = packageApps ?: throw HaloneException("unavailable", "this host cannot install apps")
        val manifest = pkg.manifest
        val previous = store.find(manifest.id)?.takeIf { it.devUrl == null }
        if (previous != null && HalonePackage.compareVersions(manifest.version, previous.manifest.version) <= 0) {
            platform.notice("${manifest.name} ${previous.manifest.version} is already installed")
            return done(null)
        }
        val added = store.newPermissions(pkg)
        val rights = when {
            added.isEmpty() && previous != null -> ""
            added.isEmpty() -> "\n\nIt needs nothing beyond its own window."
            else -> "\n\nIt will${if (previous != null) " also" else ""} be able to:\n" + added.joinToString("\n") { "•  ${it.prompt}" }
        }
        val action = if (previous != null) "Update" else "Install"
        val dialog = JSONObject()
            .put("kind", "message")
            .put("title", "$action “${manifest.name}”?")
            .put(
                "message",
                (if (previous != null) "${previous.manifest.version} → ${manifest.version}" else manifest.version) +
                    " from $source" + rights,
            )
            .put(
                "buttons",
                org.json.JSONArray()
                    .put(JSONObject().put("id", "cancel").put("label", "Cancel"))
                    .put(JSONObject().put("id", "install").put("label", action).put("primary", true)),
            )
        showDialog(sourceId, dialog) { choice, _ ->
            if (choice != "install") return@showDialog done(null)
            done(runCatching { installPackage(pkg, source) }.onFailure { platform.notice(it.message ?: "Could not install ${manifest.name}") }.getOrNull())
        }
    }

    /** A developer's app, loaded from their server; only while developer mode is on. */
    fun installDevApp(manifest: PackageManifest, url: String): InstalledApp {
        if (!settings.boolean(ShellSettings.DEVELOPER_ENABLED)) throw HaloneException("developer-mode-off", "turn on developer mode in Settings first")
        val store = packageApps ?: throw HaloneException("unavailable", "this host cannot install apps")
        val app = store.installDev(manifest, url)
        windows.values.filter { it.appId == app.id }.forEach { close(it.id) }
        return app
    }

    private fun removeDevApps() {
        val store = packageApps ?: return
        val dev = store.list().filter { it.devUrl != null }.map { it.id }.toSet()
        windows.values.filter { it.appId in dev }.forEach { close(it.id) }
        store.removeDevApps()
    }

    /** Closes an installed app's window and takes it out of the launcher and the dock. */
    fun uninstall(appId: String) {
        val web = webApps?.takeIf { it.find(appId) != null }
        val packaged = packageApps?.takeIf { it.find(appId) != null }
        if (web == null && packaged == null) {
            if (nativeApps?.find(appId) != null) throw HaloneException("not-removable", "a plugin's apps go with the plugin: uninstall it on the phone")
            val known = systemApps.any { it.id == appId }
            throw HaloneException(if (known) "not-removable" else "no-such-app", if (known) "$appId is part of the system" else "no such app: $appId")
        }
        windows.values.filter { it.appId == appId }.forEach { close(it.id) }
        val pinned = settings.strings(ShellSettings.DOCK_PINNED_APPS)
        if (appId in pinned) setSetting(ShellSettings.DOCK_PINNED_APPS, pinned - appId)
        web?.remove(appId)
        packaged?.find(appId)?.let { app ->
            packaged.remove(appId)
            platform.clearAppData(app)
        }
    }

    /** Opens [path] in [appId], or in the first app that declares its type. */
    fun openFile(path: String, mimeType: String?, appId: String? = null): ShellWindow {
        val app = if (appId != null) {
            apps.firstOrNull { it.id == appId } ?: throw HaloneException("no-such-app", "no such app: $appId")
        } else {
            apps.firstOrNull { app -> app.opens.any { FileTypes.matches(it, mimeType) } }
                ?: throw HaloneException("no-handler", "no app opens ${mimeType ?: "this kind of file"}")
        }
        val window = launch(app.id)
        window.openedFile = path
        listeners.forEach { it.onFileOpened(window.id, path) }
        return window
    }

    fun openUrl(url: String, private: Boolean = false): ShellWindow {
        val parsed = webUrl(url)
        val id = nextId()
        val panel = platform.openWeb(id, url, private) ?: throw unavailable()
        val window = add(ShellWindow(id, WindowKind.WEB, null, parsed.host, url, panel))
        platform.browser(id)?.let { browser -> browser.onChange = { onBrowserChanged(window, browser) } }
        return window
    }

    /** Opens [url] in a new tab of the web window used last, or in a new web window if none is open. */
    fun openInBrowser(url: String, private: Boolean = false): ShellWindow {
        webUrl(url)
        val window = lastWebWindow?.let { windows[it] } ?: windows.values.lastOrNull { it.kind == WindowKind.WEB }
            ?: return openUrl(url, private)
        browser(window.id).newTab(url, private)
        focus(window.id)
        return window
    }

    private fun webUrl(url: String): java.net.URI {
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
            ?.takeIf { it.isAbsolute && !it.host.isNullOrEmpty() }
            ?: throw HaloneException("invalid-url", "not a URL: $url")
        if (parsed.scheme != "https" && parsed.scheme != "http") {
            throw HaloneException("invalid-url", "only http and https can be opened: $url")
        }
        return parsed
    }

    fun keyCaptureOn() = platform.keyCaptureOn()

    fun pointingDeviceConnected() = platform.pointingDeviceConnected()

    fun openKeyCaptureSettings() = platform.openKeyCaptureSettings()

    fun shareLog() = platform.shareLog()

    fun displayState() = platform.displayState()

    fun restartDisplay() = platform.restartDisplay()

    /** The glasses started, stopped or changed mode; listeners read [displayState] again. */
    fun displayChanged() = listeners.forEach { it.onDisplayChanged() }

    fun clearBrowsingData(cookies: Boolean, cache: Boolean) {
        if (cookies || cache) platform.clearBrowsingData(cookies, cache)
    }

    fun webWindows(): List<ShellWindow> = windows.values.filter { it.kind == WindowKind.WEB }

    fun browser(id: String): BrowserControl =
        platform.browser(find(id).id) ?: throw HaloneException("not-a-browser", "window $id is not a web window")

    /** Closes a tab, or the window when it is the last one. */
    fun closeTab(id: String, tabId: Int) {
        val browser = browser(id)
        if (browser.state().tabs.size <= 1) close(id) else browser.closeTab(tabId)
    }

    private fun onBrowserChanged(window: ShellWindow, browser: BrowserControl) {
        if (windows[window.id] !== window) return
        val state = browser.state()
        state.active?.let { tab ->
            val title = tab.title.ifBlank { runCatching { java.net.URI(tab.url).host }.getOrNull() ?: tab.url }
            if (title.isNotBlank() && title != window.title || tab.url != window.url) {
                window.title = title.take(MAX_TITLE)
                window.panel.title = window.title
                window.url = tab.url
                notifyWindows()
            }
        }
        listeners.forEach { it.onBrowserChanged(window.id, state) }
    }

    /**
     * Runs a keyboard shortcut. [targetId] is the window that has the
     * keyboard, if a window does; the browser's shortcuts need a web window.
     * Returns whether it applied.
     */
    fun runShortcut(action: ShortcutAction, targetId: String?): Boolean {
        val target = targetId?.let { windows[it] }
        val browser = target?.takeIf { it.kind == WindowKind.WEB }?.let { platform.browser(it.id) }
        when (action) {
            ShortcutAction.NEXT_WINDOW, ShortcutAction.PREVIOUS_WINDOW -> {
                val all = windows.values.toList()
                if (all.isEmpty()) return false
                val from = all.indexOfFirst { it.id == (target?.id ?: lastFocused) }
                val step = if (action == ShortcutAction.NEXT_WINDOW) 1 else -1
                val next = if (from < 0) all.first() else all[(from + step + all.size) % all.size]
                focus(next.id)
            }
            ShortcutAction.CLOSE_WINDOW -> close(target?.id ?: return false)
            ShortcutAction.TOGGLE_LAUNCHER -> setLauncherOpen(!launcherOpen)
            ShortcutAction.SCREENSHOT -> captureScreen(0)
            ShortcutAction.RECENTER -> recenter()
            ShortcutAction.TOGGLE_PIN -> target?.let { setPinned(it.id, it.panel.follow.enabled) } ?: return false
            ShortcutAction.NEW_TAB -> browser?.newTab(null) ?: return false
            ShortcutAction.NEW_PRIVATE_TAB -> browser?.newTab(null, private = true) ?: return false
            ShortcutAction.CLOSE_TAB -> {
                val state = browser?.state() ?: return false
                closeTab(target.id, state.activeTab)
            }
            ShortcutAction.NEXT_TAB, ShortcutAction.PREVIOUS_TAB -> {
                val state = browser?.state() ?: return false
                val at = state.tabs.indexOfFirst { it.id == state.activeTab }
                val step = if (action == ShortcutAction.NEXT_TAB) 1 else -1
                browser.selectTab(state.tabs[(at + step + state.tabs.size) % state.tabs.size].id)
            }
            ShortcutAction.FOCUS_ADDRESS -> {
                if (browser == null) return false
                listeners.forEach { it.onBrowserCommand(target.id, "focus-address") }
            }
            ShortcutAction.RELOAD -> browser?.reload() ?: return false
            ShortcutAction.BACK -> browser?.back() ?: return false
            ShortcutAction.FORWARD -> browser?.forward() ?: return false
        }
        return true
    }

    /** Focuses a window and brings the arrangement round to it, as a click in the dock does. */
    fun focus(id: String) {
        val panel = find(id).panel
        scene.focus(panel)
        scene.centreOn(panel, platform.headYawRadians())
        setLauncherOpen(false)
        platform.onFocusRequested(panel)
    }

    fun close(id: String) {
        val window = find(id)
        if (menu?.sourceId == id) dismissMenu()
        windows.remove(id)
        platform.close(window.panel)
        if (autoHideRequests.remove(id)) platform.setWindowsAutoHide(windowsAutoHide)
        notifyWindows()
        poll()
    }

    fun setPinned(id: String, pinned: Boolean) {
        val panel = find(id).panel
        if (panel.follow.enabled != pinned) return
        scene.setFollow(panel, panel.follow.copy(enabled = !pinned))
        notifyWindows()
    }

    /** Remembers each app's chosen window size; null, as under test, forgets. */
    var windowSizes: WindowSizeStore? = null

    /**
     * What a chosen size is remembered under. Plugin windows go by title as
     * well, because one plugin can show many apps — the Android apps plugin
     * opens every Android app as a window of its own, named after it.
     */
    private fun sizeKey(window: ShellWindow): String? {
        val app = window.appId ?: return null
        return if (window.kind == WindowKind.NATIVE) "$app|${window.title}" else app
    }

    /**
     * The wearer's size button: the next preset shape, kept for the next time
     * this app opens. Not bound by the window's limits, which an app that
     * declares only portrait sizes would otherwise use to refuse landscape.
     */
    fun cycleSize(id: String): Pair<Int, Int> {
        val window = find(id)
        val (width, height) = TitleBar.nextSize(window.panel.widthPx, window.panel.heightPx)
        applySize(window, width, height)
        window.sizeSettled = true
        sizeKey(window)?.let { windowSizes?.save(it, width, height) }
        return width to height
    }

    private fun restoreSize(window: ShellWindow) {
        if (window.sizeSettled) return
        val key = sizeKey(window) ?: return
        val (width, height) = windowSizes?.load(key) ?: return
        window.sizeSettled = true
        applySize(window, width, height)
    }

    /** About the bottom centre, so the title bar under the window stays put. */
    private fun applySize(window: ShellWindow, width: Int, height: Int) {
        val panel = window.panel
        val oldWidth = panel.widthPx
        val oldHeight = panel.heightPx
        if (width == oldWidth && height == oldHeight) return
        panel.engine.resize(width, height)
        scene.resized(panel, oldWidth, oldHeight, Corner.BOTTOM_LEFT, keepCentreX = true)
        notifyWindows()
    }

    /** Resizes a window at its own request, within its limits, keeping its top or bottom edge where it is. */
    fun resize(id: String, widthPx: Int, heightPx: Int, keepTop: Boolean = true): Pair<Int, Int> {
        val panel = find(id).panel
        val limits = scene.limitsOf(panel)
        val width = kotlin.math.round(limits.clampWidth(widthPx.toFloat())).toInt()
        val height = kotlin.math.round(limits.clampHeight(heightPx.toFloat())).toInt()
        val oldWidth = panel.widthPx
        val oldHeight = panel.heightPx
        if (width != oldWidth || height != oldHeight) {
            panel.engine.resize(width, height)
            scene.resized(panel, oldWidth, oldHeight, if (keepTop) Corner.TOP_LEFT else Corner.BOTTOM_LEFT)
            notifyWindows()
        }
        return panel.widthPx to panel.heightPx
    }

    /** Overrides a window's size limits, or with null goes back to what its page declared. */
    fun setSizeLimits(id: String, limits: SizeLimits?) {
        val window = find(id)
        if (limits == null) {
            window.declaredLimits?.let { window.panel.sizeLimits = it }
            window.declaredLimits = null
            return
        }
        if (window.declaredLimits == null) window.declaredLimits = scene.limitsOf(window.panel)
        window.panel.sizeLimits = limits
    }

    fun setStereoRegion(id: String, region: StereoRegion?) {
        find(id).panel.stereoRegion = region
    }

    fun setTitle(id: String, title: String) {
        val window = find(id)
        window.title = title.take(MAX_TITLE)
        window.panel.title = window.title
        // A plugin window is known by its title, which arrives after it opens.
        if (window.kind == WindowKind.NATIVE) restoreSize(window)
        notifyWindows()
    }

    fun recenter() = platform.recenter()

    fun headPose() = HeadPose(
        yawDegrees = -Math.toDegrees(platform.headYawRadians().toDouble()),
        pitchDegrees = Math.toDegrees(platform.headPitchRadians().toDouble()),
    )

    fun setSetting(key: String, value: Any?) {
        settings.set(key, value)
        platform.applySettings(settings, key)
        if (key == ShellSettings.WINDOWS_AUTO_HIDE) platform.setWindowsAutoHide(windowsAutoHide)
        if (key == ShellSettings.DEVELOPER_ENABLED && !settings.boolean(key)) removeDevApps()
        listeners.forEach { it.onSettingsChanged() }
    }

    /**
     * Picks up what the scene changed on its own: focus follows the pointer
     * every frame, and a panel may be closed by something other than the shell.
     * Call at a few hertz.
     */
    fun poll() {
        val orphans = windows.values.filter { it.panel !in scene.panels }
        orphans.forEach { windows.remove(it.id) }
        if (orphans.any { it.id == menu?.sourceId }) dismissMenu()
        if (orphans.any { autoHideRequests.remove(it.id) }) platform.setWindowsAutoHide(windowsAutoHide)
        val focused = windows.values.firstOrNull { it.panel === scene.focused }?.id
        if (focused != null && windows[focused]?.kind == WindowKind.WEB) lastWebWindow = focused
        val focusMoved = focused != lastFocused
        if (focusMoved) {
            val previous = lastFocused
            lastFocused = focused
            listeners.forEach { it.onFocusChanged(previous, focused) }
        }
        if (orphans.isNotEmpty() || focusMoved) notifyWindows()
        checkLauncher()
    }

    private fun checkLauncher() {
        val launcherShown = scene.launcherShown
        if (launcherShown != lastLauncherShown) {
            lastLauncherShown = launcherShown
            listeners.forEach { it.onLauncherChanged(launcherShown) }
        }
    }

    fun isFocused(window: ShellWindow) = scene.focused === window.panel

    fun info(window: ShellWindow): JSONObject {
        val pose = window.panel.pose
        return JSONObject()
            .put("id", window.id)
            .put("kind", window.kind.wire)
            .put("appId", window.appId ?: JSONObject.NULL)
            .put("title", window.title)
            .put("url", window.url)
            .put("yawDegrees", -Math.toDegrees(pose.yawRadians.toDouble()))
            .put("pitchDegrees", Math.toDegrees(pose.pitchRadians.toDouble()))
            .put("widthPx", window.panel.engine.widthPx)
            .put("heightPx", window.panel.engine.heightPx)
            .put("pinned", !window.panel.follow.enabled)
            .put("focused", isFocused(window))
    }

    private fun add(window: ShellWindow): ShellWindow {
        window.panel.title = window.title
        window.panel.tag = if (window.kind == WindowKind.WEB) "web" else null
        windows[window.id] = window
        if (window.kind != WindowKind.NATIVE) restoreSize(window)
        scene.launcherRequested = false
        notifyWindows()
        checkLauncher()
        return window
    }

    private fun nextId() = "w${nextWindow++}"

    private fun notifyWindows() = listeners.forEach { it.onWindowsChanged() }

    private fun unavailable() = HaloneException("unavailable", "no display to open a window on")

    companion object {
        const val ORIGIN = "https://halone.local"
        const val DOCK = "dock"
        const val LAUNCHER = "launcher"
        const val MENU = "menu"
        private const val MAX_TITLE = 120
        private const val MAX_DELAY_MS = 10_000L
    }
}
