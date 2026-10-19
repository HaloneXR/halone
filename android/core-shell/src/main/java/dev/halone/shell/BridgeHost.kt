package dev.halone.shell

import dev.halone.compositor.SizeLimits
import dev.halone.compositor.StereoLayout
import dev.halone.compositor.StereoRegion
import org.json.JSONArray
import org.json.JSONObject

/**
 * A reply that comes later, from a method that waits on the wearer or on the
 * disk: [start] is handed the reply, to call once, from any thread.
 */
class Later(val start: (reply: (Result<Any?>) -> Unit) -> Unit)

/** The host's end of one window's port. */
interface BridgeChannel {
    fun post(message: String)
    fun onReceive(handler: (String) -> Unit)
    fun close()
}

/**
 * Serves the protocol to every connected window.
 *
 * A window's identity is the channel it arrived on, never something it says, so
 * `windows.close()` with no id closes the caller and nothing a page sends can
 * make it act as another window.
 */
class BridgeHost(
    private val shell: Shell<*>,
    private val systemInfo: SystemInfo,
    private val log: (String) -> Unit = {},
    private val files: SharedFiles? = null,
    private val media: Media? = null,
    private val browsing: BrowserData? = null,
    /** Where a [Later] reply is posted from: the thread the bridge is served on. */
    private val onMain: (Runnable) -> Unit = Runnable::run,
    /** Where slow work such as copying files runs. */
    private val background: (Runnable) -> Unit = Runnable::run,
    private val sockets: Sockets? = null,
) : ShellListener {
    private val channels = HashMap<String, BridgeChannel>()

    init {
        shell.addListener(this)
        media?.onChange = { owner, state -> emit(owner, "media.changed", state) }
        sockets?.onEvent = { owner, name, data -> emit(owner, name, data) }
        browsing?.let { data ->
            data.history.onChange = { broadcast("history.changed", JSONObject.NULL) }
            data.bookmarks.onChange = {
                broadcast("bookmarks.changed", JSONArray(data.bookmarks.list().map { it.toJson() }))
                webStatesChanged()
            }
            data.downloads.onChange = {
                broadcast("downloads.changed", JSONArray(data.downloads.list().map { it.toJson() }))
                webStatesChanged()
            }
        }
    }

    private fun webStatesChanged() = shell.webWindows().forEach { window ->
        runCatching { browserJson(window.id) }.getOrNull()?.let { emit(window.id, "browser.changed", it) }
    }

    /** A web window's state as its chrome shows it, with what the shell knows about its page. */
    private fun browserJson(windowId: String): JSONObject {
        val state = shell.browser(windowId).state()
        val webApp = shell.installableApp(windowId)?.let { manifest ->
            JSONObject()
                .put("appId", manifest.appId)
                .put("name", manifest.name)
                .put("installed", shell.webApps?.find(manifest.appId) != null)
        }
        return state.toJson()
            .put("bookmarked", browsing?.bookmarks?.find(state.active?.url) != null)
            .put("activeDownloads", browsing?.downloads?.active ?: 0)
            .put("webApp", webApp ?: JSONObject.NULL)
    }

    /** Tells every window that storage access changed; call from a poller. */
    fun checkFileAccess() {
        val files = files ?: return
        if (files.accessChanged()) broadcast("files.accessChanged", JSONObject().put("granted", files.granted))
    }

    /** Returns the message to post to the page with the other end of [channel]. */
    fun connect(windowId: String, channel: BridgeChannel): String {
        channels.remove(windowId)?.close()
        channels[windowId] = channel
        channel.onReceive { text -> handle(windowId, text) { channel.post(it) }?.let(channel::post) }
        log("  bridge: $windowId connected")
        return Protocol.connectMessage(windowId)
    }

    fun disconnect(windowId: String) {
        channels.remove(windowId)?.close()
    }

    fun connected(): Set<String> = channels.keys.toSet()

    override fun onWindowsChanged() {
        val open = shell.windows().map { it.id }.toSet() + shell.overlayIds
        (channels.keys - open).forEach { disconnect(it) }
        media?.windowsChanged(open)
        sockets?.windowsChanged(open)
        broadcast("windows.changed", windowList())
    }

    override fun onRecordingChanged(state: RecordingState) = broadcast("screen.recordingChanged", state.toJson())

    override fun onFileOpened(windowId: String, path: String) = emit(windowId, "window.fileOpened", JSONObject().put("path", path))

    override fun onSettingsChanged() = broadcast("settings.changed", shell.settings.toJson())

    override fun onLauncherChanged(open: Boolean) = broadcast("launcher.changed", JSONObject().put("open", open))

    override fun onBatteryChanged(battery: Battery) = broadcast("system.batteryChanged", battery.toJson())

    private fun Battery.toJson() = JSONObject().put("level", level).put("charging", charging)

    override fun onBrowserChanged(windowId: String, state: BrowserState) {
        emit(windowId, "browser.changed", runCatching { browserJson(windowId) }.getOrElse { state.toJson() })
    }

    override fun onBrowserCommand(windowId: String, command: String) =
        emit(windowId, "browser.command", JSONObject().put("command", command))

    override fun onAppsChanged() {
        broadcast("apps.changed", JSONObject.NULL)
        webStatesChanged()
    }

    override fun onPluginsChanged() = broadcast("plugins.changed", pluginList())

    override fun onStatusItemsChanged() = broadcast("status.changed", shell.statusItems.toJson())

    private fun pluginList() = JSONArray(shell.nativeApps?.plugins().orEmpty().map { it.toJson() })

    override fun onRouted(windowId: String, route: String) = emit(windowId, "window.routed", JSONObject().put("route", route))

    override fun onMenuChanged(menu: MenuRequest?) = emit(Shell.MENU, "menu.changed", menu?.toJson() ?: JSONObject.NULL)

    override fun onFocusChanged(previous: String?, current: String?) {
        previous?.let { emit(it, "window.focusChanged", JSONObject().put("focused", false)) }
        current?.let { emit(it, "window.focusChanged", JSONObject().put("focused", true)) }
    }

    /**
     * Answers one request, or null for anything that is not a request and for
     * a method that answers later, through [reply].
     */
    fun handle(windowId: String, text: String, reply: (String) -> Unit = {}): String? {
        val request = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (request.optString("type") != "req") return null
        val id = request.optInt("id")
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        val outcome = runCatching {
            checkAllowed(windowId, method, params)
            dispatch(windowId, method, params)
        }
        val later = outcome.getOrNull() as? Later ?: return respond(id, method, windowId, outcome)
        later.start { result -> onMain { reply(respond(id, method, windowId, result)) } }
        return null
    }

    private fun respond(id: Int, method: String, windowId: String, outcome: Result<Any?>): String {
        val response = JSONObject().put("type", "res").put("id", id)
        outcome.fold(
            onSuccess = { response.put("ok", true).put("result", it ?: JSONObject.NULL) },
            onFailure = { error ->
                if (error is HaloneException) {
                    response.put("ok", false).put("error", errorJson(error.code, error.message))
                } else {
                    log("  bridge: $method from $windowId failed: $error")
                    response.put("ok", false).put("error", errorJson("internal", error.message ?: error.toString()))
                }
            },
        )
        return response.toString()
    }

    /** Reads a package off the main thread, then asks the wearer over [caller] whether to install it. */
    private fun offerPackage(caller: String, file: java.io.File) = Later { reply ->
        background {
            val read = runCatching { HalonePackage.read(file, shell.reservedIds, systemInfo.version) }
            onMain {
                reply(read.mapCatching { pkg ->
                    shell.offerPackage(caller, pkg, "Files")
                    shell.info(shell.find(caller))
                })
            }
        }
    }

    /** Runs [work] in the background and replies with what it returns. */
    private fun slowly(work: () -> Any?) = Later { reply -> background { reply(runCatching(work)) } }

    private fun dispatch(caller: String, method: String, params: JSONObject): Any? = when (method) {
        "system.info" -> systemInfo.toJson()
        "system.ping" -> JSONObject()
            .put("sentAt", params.optDouble("sentAt"))
            .put("receivedAt", System.nanoTime() / 1e6)
        "system.battery" -> shell.battery?.toJson() ?: throw HaloneException("unavailable", "battery level is not known")
        "screen.capture" -> shell.captureScreen(params.optLong("delayMs", 0)).let { null }
        "screen.recording" -> shell.recording.toJson()
        "screen.startRecording" -> shell.startRecording(params.optLong("delayMs", 0)).let { null }
        "screen.stopRecording" -> shell.stopRecording().let { null }
        "browser.state" -> browserJson(caller)
        "browser.navigate" -> shell.browser(caller).navigate(params.requireString("url")).let { null }
        "browser.back" -> shell.browser(caller).back().let { null }
        "browser.forward" -> shell.browser(caller).forward().let { null }
        "browser.reload" -> shell.browser(caller).reload().let { null }
        "browser.stop" -> shell.browser(caller).stop().let { null }
        "browser.newTab" -> shell.browser(caller)
            .newTab(params.optString("url").takeIf { it.isNotEmpty() && !params.isNull("url") }, params.optBoolean("private"))
            .let { null }
        "browser.selectTab" -> shell.browser(caller).selectTab(params.requireInt("tabId")).let { null }
        "browser.closeTab" -> shell.closeTab(caller, params.requireInt("tabId")).let { null }
        "browser.answerPrompt" -> shell.browser(caller)
            .answerPrompt(params.requireInt("id"), params.requireBoolean("allow"), params.optBoolean("remember"))
            .let { null }
        "browser.toggleBookmark" -> {
            val browser = shell.browser(caller)
            val tab = browser.state().active ?: throw HaloneException("no-page", "no page is open")
            val bookmarks = browsing().bookmarks
            bookmarks.find(tab.url)?.let { bookmarks.remove(it.id) } ?: bookmarks.add(tab.url, tab.title, browser.iconUrl())
            null
        }
        "browser.installApp" -> Later { reply ->
            runCatching { shell.installWebApp(caller) { result -> reply(result.map { it.toAppInfo().toJson() }) } }
                .onFailure { reply(Result.failure(it)) }
        }
        "browser.open" -> shell.info(shell.openInBrowser(params.requireString("url"), params.optBoolean("private")))
        "browser.clearData" -> {
            if (params.optBoolean("history")) browsing().history.clear()
            shell.clearBrowsingData(params.optBoolean("cookies"), params.optBoolean("cache"))
            null
        }
        "history.list" -> JSONArray(
            browsing().history
                .list(params.optString("query").takeIf { !params.isNull("query") }, params.optInt("limit", HISTORY_PAGE).coerceIn(1, HISTORY_PAGE))
                .map { it.toJson() },
        )
        "history.remove" -> browsing().history.remove(params.requireString("url")).let { null }
        "bookmarks.list" -> JSONArray(browsing().bookmarks.list().map { it.toJson() })
        "bookmarks.add" -> browsing().bookmarks.add(
            params.requireString("url"),
            params.optString("title"),
            params.optString("iconUrl").takeIf { it.isNotEmpty() && !params.isNull("iconUrl") },
        ).toJson()
        "bookmarks.update" -> browsing().bookmarks.update(
            params.requireString("id"),
            params.optString("title").takeIf { params.has("title") && !params.isNull("title") },
            params.optString("url").takeIf { params.has("url") && !params.isNull("url") },
        ).toJson()
        "bookmarks.remove" -> browsing().bookmarks.remove(params.requireString("id")).let { null }
        "downloads.list" -> JSONArray(browsing().downloads.list().map { it.toJson() })
        "downloads.cancel" -> browsing().downloads.cancel(params.requireInt("id")).let { null }
        "downloads.remove" -> browsing().downloads.remove(params.requireInt("id")).let { null }
        "downloads.clear" -> browsing().downloads.clear().let { null }
        "menu.show" -> {
            val items = MenuItem.parseList(params.optJSONArray("items"))
            val x = params.requireNumber("xPx").toFloat()
            val y = params.requireNumber("yPx").toFloat()
            Later { reply ->
                shell.showMenu(caller, items, x, y) { itemId -> reply(Result.success(JSONObject().put("itemId", itemId ?: JSONObject.NULL))) }
            }
        }
        "menu.current" -> shell.menu?.toJson()
        "menu.rendered" -> shell.menuRendered(params.requireInt("menuId")).let { null }
        "menu.choose" -> shell.chooseMenu(
            params.requireInt("menuId"),
            params.optString("itemId").takeIf { params.has("itemId") && !params.isNull("itemId") },
            params.optJSONObject("answer") ?: JSONObject(),
        ).let { null }
        "clipboard.read" -> {
            val (text, clipboardFiles) = shell.clipboard()
            JSONObject()
                .put("text", text ?: JSONObject.NULL)
                .put("files", clipboardFiles?.let { JSONObject().put("paths", JSONArray(it.paths)).put("cut", it.cut) } ?: JSONObject.NULL)
        }
        "clipboard.writeText" -> shell.writeClipboardText(params.requireString("text")).let { null }
        "clipboard.writeFiles" -> shell.writeClipboardFiles(ClipboardFiles(params.requirePaths(), params.optBoolean("cut"))).let { null }
        "input.keyCapture" -> JSONObject().put("enabled", shell.keyCaptureOn())
        "input.openKeyCaptureSettings" -> shell.openKeyCaptureSettings().let { null }
        "input.releaseToDesktop" -> shell.releaseToDesktop().let { null }
        "launcher.get" -> JSONObject().put("open", shell.launcherOpen)
        "launcher.setOpen" -> shell.setLauncherOpen(params.requireBoolean("open")).let { null }
        "apps.list" -> JSONArray(shell.apps.filter { params.optBoolean("includeHidden") || !it.hidden }.map { it.toJson() })
        "apps.launch" -> shell.info(
            shell.launch(params.requireString("appId"), params.optString("route").takeIf { it.isNotEmpty() && !params.isNull("route") }),
        )
        "apps.uninstall" -> shell.uninstall(params.requireString("appId")).let { null }
        "plugins.list" -> pluginList()
        "status.list" -> shell.statusItems.toJson()
        "status.click" -> shell.clickStatusItem(params.requireString("id")).let { null }
        "plugins.setEnabled" -> shell.setPluginEnabled(params.requireString("packageName"), params.requireBoolean("enabled")).let { null }
        "apps.open" -> {
            val entry = files().stat(params.requireString("path"))
            if (entry.getString("kind") != "file") throw HaloneException("not-a-file", "not a file: ${entry.getString("path")}")
            val appId = params.optString("appId").takeIf { it.isNotEmpty() }
            val mimeType = FileTypes.mimeTypeOf(entry.getString("name"))
            if (mimeType == HalonePackage.MIME_TYPE && appId == null) {
                offerPackage(caller, files().resolve(entry.getString("path")))
            } else {
                shell.info(shell.openFile(entry.getString("path"), mimeType, appId))
            }
        }
        "sockets.connect" -> {
            val host = params.requireString("host")
            val port = params.requireInt("port")
            val timeoutMs = params.optInt("timeoutMs", DEFAULT_CONNECT_TIMEOUT_MS)
            val tls = params.optBoolean("tls")
            Later { reply ->
                runCatching { sockets().connect(caller, host, port, timeoutMs, tls) { result -> reply(result.map { JSONObject().put("socketId", it) }) } }
                    .onFailure { reply(Result.failure(it)) }
            }
        }
        "sockets.write" -> {
            val id = params.requireInt("socketId")
            val data = params.requireString("data")
            Later { reply ->
                runCatching { sockets().write(caller, id, data) { result -> reply(result.map { null }) } }.onFailure { reply(Result.failure(it)) }
            }
        }
        "sockets.ack" -> sockets().ack(caller, params.requireInt("socketId")).let { null }
        "sockets.close" -> sockets().close(caller, params.requireInt("socketId")).let { null }
        "files.access" -> JSONObject().put("granted", files().granted)
        "files.requestAccess" -> files().requestAccess().let { null }
        "files.roots" -> JSONArray(files().roots().map { it.toJson() })
        "files.list" -> JSONArray(files().list(params.requireString("path")))
        "files.stat" -> files().stat(params.requireString("path"))
        "files.copy" -> {
            val paths = params.requirePaths()
            val to = params.requireString("to")
            slowly { JSONArray(files().copy(paths, to)) }
        }
        "files.move" -> {
            val paths = params.requirePaths()
            val to = params.requireString("to")
            shell.filesMoved(paths)
            slowly { JSONArray(files().move(paths, to)) }
        }
        "files.rename" -> files().rename(params.requireString("path"), params.requireString("name"))
        "files.delete" -> {
            val paths = params.requirePaths()
            slowly { files().delete(paths).let { null } }
        }
        "files.createDirectory" -> files().createDirectory(params.requireString("parent"), params.requireString("name"))
        "media.open" -> {
            val file = files().resolve(params.requireString("path"))
            if (!file.isFile || FileTypes.mimeTypeOf(file.name)?.startsWith("video/") != true) {
                throw HaloneException("not-a-video", "not a video: ${file.path}")
            }
            val layout = MediaLayout.parse(params.optJSONObject("layout"))
            media().open(caller, file.path, file, layout, params.optDouble("startSeconds", 0.0).takeIf { !it.isNaN() } ?: 0.0)
        }
        "media.state" -> media().state()
        "media.play" -> media().play(caller).let { null }
        "media.pause" -> media().pause(caller).let { null }
        "media.seek" -> media().seek(caller, params.requireNumber("seconds")).let { null }
        "media.setLayout" -> media().setLayout(caller, MediaLayout.parse(params.optJSONObject("layout"))).let { null }
        "media.recenter" -> media().recenter(caller).let { null }
        "media.setRotatable" -> media().setRotatable(caller, params.requireBoolean("rotatable")).let { null }
        "media.close" -> media().close(caller).let { null }
        "windows.list" -> windowList()
        "windows.self" -> shell.info(shell.find(caller))
        "windows.openUrl" -> shell.info(shell.openUrl(params.requireString("url")))
        "windows.focus" -> shell.focus(params.requireString("id")).let { null }
        "windows.close" -> shell.close(params.target(caller)).let { null }
        "windows.setPinned" -> shell.setPinned(params.target(caller), params.requireBoolean("pinned")).let { null }
        "windows.setTitle" -> shell.setTitle(caller, params.requireString("title")).let { null }
        "windows.openedFile" -> JSONObject().put("path", shell.find(caller).openedFile ?: JSONObject.NULL)
        "windows.route" -> JSONObject().put("route", shell.find(caller).route ?: JSONObject.NULL)
        "windows.setStereoRegion" -> shell.setStereoRegion(caller, params.optJSONObject("region")?.toStereoRegion()).let { null }
        "windows.resize" -> {
            val anchor = params.optString("anchor", "top")
            if (anchor != "top" && anchor != "bottom") throw HaloneException("invalid-params", "anchor must be top or bottom")
            val (width, height) = shell.resize(
                caller,
                params.requireNumber("widthPx").toInt(),
                params.requireNumber("heightPx").toInt(),
                keepTop = anchor == "top",
            )
            JSONObject().put("widthPx", width).put("heightPx", height)
        }
        "windows.setAutoHide" -> shell.setAutoHideRequest(caller, params.requireBoolean("enabled")).let { null }
        "windows.setSizeLimits" -> shell.setSizeLimits(caller, params.optJSONObject("limits")?.toSizeLimits()).let { null }
        "scene.recenter" -> shell.recenter().let { null }
        "scene.headPose" -> shell.headPose().toJson()
        "settings.get" -> shell.settings.toJson()
        "settings.set" -> shell.setSetting(params.requireString("key"), params.opt("value")).let { null }
        else -> throw HaloneException("unknown-method", "no such method: $method")
    }

    /**
     * A package's window may call what every app may, and what its grant
     * covers; the shell's own windows may call anything.
     */
    private fun checkAllowed(caller: String, method: String, params: JSONObject) {
        val granted = shell.grantOf(caller) ?: return
        val needed = PERMISSIONS[method]
        val allowed = when {
            method in SELF_ONLY -> params.isNull("id") || params.optString("id") == caller
            method in OPEN_TO_ALL -> true
            needed != null -> needed in granted
            else -> false
        }
        if (allowed) return
        if (needed != null) throw HaloneException("permission-denied", "$method needs the ${needed.wire} permission")
        throw HaloneException("permission-denied", "$method is not available to apps")
    }

    /** Whether window [windowId] hears event [name]. */
    private fun hears(windowId: String, name: String): Boolean {
        val granted = shell.grantOf(windowId) ?: return true
        return when (name) {
            in OWN_EVENTS -> true
            "windows.changed" -> Permission.WINDOWS in granted
            "apps.changed" -> Permission.APPS in granted
            "files.accessChanged" -> Permission.FILES in granted
            "screen.recordingChanged" -> Permission.SCREEN in granted
            else -> false
        }
    }

    private fun sockets() = sockets ?: throw HaloneException("unavailable", "this host has no sockets")

    private fun files() = files ?: throw HaloneException("unavailable", "this host has no shared storage")

    private fun media() = media ?: throw HaloneException("unavailable", "this host has no video player")

    private fun browsing() = browsing ?: throw HaloneException("unavailable", "this host has no browser")

    private fun JSONObject.requirePaths(): List<String> {
        val array = optJSONArray("paths") ?: throw HaloneException("invalid-params", "paths must be a list")
        return (0 until array.length()).map { array.opt(it) as? String ?: throw HaloneException("invalid-params", "paths must be strings") }
    }

    private fun windowList() = JSONArray(shell.windows().map { shell.info(it) })

    private fun broadcast(name: String, data: Any) = channels.keys.toList().forEach { emit(it, name, data) }

    private fun emit(windowId: String, name: String, data: Any) {
        if (!hears(windowId, name)) return
        channels[windowId]?.post(JSONObject().put("type", "evt").put("name", name).put("data", data).toString())
    }

    private fun errorJson(code: String, message: String?) =
        JSONObject().put("code", code).put("message", message ?: code)

    private fun JSONObject.requireString(key: String): String =
        (opt(key) as? String) ?: throw HaloneException("invalid-params", "$key must be a string")

    private fun JSONObject.requireInt(key: String): Int =
        (opt(key) as? Number)?.toInt() ?: throw HaloneException("invalid-params", "$key must be a number")

    private fun JSONObject.toStereoRegion(): StereoRegion {
        val layout = StereoLayout.entries.firstOrNull { it.wire == optString("stereo") && it != StereoLayout.MONO }
            ?: throw HaloneException("invalid-params", "stereo must be side-by-side or top-bottom")
        val region = StereoRegion(
            layout,
            requireNumber("xPx").toFloat(),
            requireNumber("yPx").toFloat(),
            requireNumber("widthPx").toFloat(),
            requireNumber("heightPx").toFloat(),
        )
        if (region.widthPx <= 0f || region.heightPx <= 0f) throw HaloneException("invalid-params", "region must have a size")
        return region
    }

    private fun JSONObject.toSizeLimits(): SizeLimits {
        val values = listOf("minWidthPx", "minHeightPx", "maxWidthPx", "maxHeightPx").map { requireNumber(it).toInt() }
        if (values.any { it < 1 || it > MAX_WINDOW_PX }) {
            throw HaloneException("invalid-params", "limits must be whole pixels between 1 and $MAX_WINDOW_PX")
        }
        val (minWidth, minHeight, maxWidth, maxHeight) = values
        if (minWidth > maxWidth || minHeight > maxHeight) throw HaloneException("invalid-params", "a minimum is above its maximum")
        return SizeLimits(minWidth, minHeight, maxWidth, maxHeight)
    }

    private fun JSONObject.requireNumber(key: String): Double =
        (opt(key) as? Number)?.toDouble() ?: throw HaloneException("invalid-params", "$key must be a number")

    private fun JSONObject.requireBoolean(key: String): Boolean =
        (opt(key) as? Boolean) ?: throw HaloneException("invalid-params", "$key must be a boolean")

    private fun JSONObject.target(caller: String): String =
        if (isNull("id")) caller else requireString("id")

    companion object {
        private const val MAX_WINDOW_PX = 4096
        private const val HISTORY_PAGE = 500
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000

        /** What every app may do: learn about the system, run its own window, menus over it, write the clipboard, open a page. */
        internal val OPEN_TO_ALL = setOf(
            "system.info", "system.ping", "system.battery",
            "windows.self", "windows.setTitle", "windows.resize", "windows.setSizeLimits", "windows.setStereoRegion",
            "windows.setAutoHide", "windows.openedFile", "windows.route", "windows.openUrl",
            "menu.show", "clipboard.writeText", "browser.open", "scene.recenter",
            "media.state", "media.play", "media.pause", "media.seek", "media.setLayout", "media.recenter",
            "media.setRotatable", "media.close",
        )

        /** Allowed on the caller's own window only. */
        internal val SELF_ONLY = setOf("windows.close", "windows.setPinned")

        internal val PERMISSIONS = mapOf(
            "clipboard.read" to Permission.CLIPBOARD,
            "files.access" to Permission.FILES,
            "files.roots" to Permission.FILES,
            "files.list" to Permission.FILES,
            "files.stat" to Permission.FILES,
            "files.copy" to Permission.FILES,
            "files.move" to Permission.FILES,
            "files.rename" to Permission.FILES,
            "files.delete" to Permission.FILES,
            "files.createDirectory" to Permission.FILES,
            "clipboard.writeFiles" to Permission.FILES,
            "apps.open" to Permission.FILES,
            "media.open" to Permission.FILES,
            "apps.list" to Permission.APPS,
            "apps.launch" to Permission.APPS,
            "windows.list" to Permission.WINDOWS,
            "windows.focus" to Permission.WINDOWS,
            "screen.capture" to Permission.SCREEN,
            "screen.recording" to Permission.SCREEN,
            "screen.startRecording" to Permission.SCREEN,
            "screen.stopRecording" to Permission.SCREEN,
            "scene.headPose" to Permission.SENSORS,
            "sockets.connect" to Permission.NETWORK_TCP,
            "sockets.write" to Permission.NETWORK_TCP,
            "sockets.ack" to Permission.NETWORK_TCP,
            "sockets.close" to Permission.NETWORK_TCP,
        )

        /** Events about the app's own window, or the system, that any app hears. */
        internal val OWN_EVENTS = setOf(
            "window.focusChanged", "window.fileOpened", "window.routed", "media.changed", "system.batteryChanged",
            "socket.data", "socket.closed",
        )
    }
}
