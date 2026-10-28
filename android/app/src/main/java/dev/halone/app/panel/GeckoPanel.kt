package dev.halone.app.panel

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import dev.halone.compositor.CursorShape
import dev.halone.compositor.PanelEngine
import dev.halone.compositor.TouchPhase
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.PanZoomController
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import org.json.JSONObject

/**
 * A web page rendered into a texture we own.
 *
 * GeckoView can render straight into a `SurfaceTexture` — Mozilla documents
 * this exact case for "showing Gecko output in an OpenGL scene like a game or
 * VR/AR environment". That is why the browser needs no `VirtualDisplay`
 * indirection, unlike a system WebView.
 *
 * Coordinates handed to [dispatchTap] and [dispatchScroll] are in panel pixels,
 * matching the surface size given to Gecko. The compositor's job is to turn a
 * pointer ray into those pixels; this class assumes that has been done.
 */
class GeckoPanel(
    context: Context,
    initialWidthPx: Int,
    initialHeightPx: Int,
    /** Loaded when the panel attaches. Gecko needs a real origin, not `data:`. */
    private val url: String,
    private val log: (String) -> Unit,
    private val keyboard: GeckoKeyboard? = null,
    desktopUserAgent: Boolean = true,
    /** Whether the first tab is a private one. */
    firstTabPrivate: Boolean = false,
    /** Asks the wearer, on the phone, for Android permissions Gecko needs, such as the microphone's. */
    private val androidPermissions: (Array<String>, (Set<String>) -> Unit) -> Unit = { _, done -> done(emptySet()) },
    /** What `display-mode` pages see: an installed app's window is standalone. */
    private val displayMode: Int = GeckoSessionSettings.DISPLAY_MODE_BROWSER,
) : PanelEngine {
    /** Whether pages are asked for as by a desktop browser, which most sites now key their layout on. */
    var desktopUserAgent = desktopUserAgent
        private set

    /** Switches every tab's user agent, reloading them so the sites see it. */
    fun setDesktopUserAgent(desktop: Boolean) {
        if (desktop == desktopUserAgent) return
        desktopUserAgent = desktop
        tabs.filter { !it.suspended }.forEach {
            it.session.settings.userAgentMode = userAgentMode()
            if (it.url != null) it.session.reload()
        }
    }

    private fun userAgentMode() =
        if (desktopUserAgent) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

    private val appContext = context.applicationContext

    override val cursorShape get() = active.cursor
    private val runtime: GeckoRuntime = sharedRuntime(context)
    private val extensions = GeckoExtensions.of(runtime, log)

    /**
     * One page and its history; only the active tab's session holds the
     * display. A background tab may be [suspended]: its session is closed to
     * free its memory, and a new one restores [sessionState] when it is selected.
     */
    class Tab(val id: Int, val private: Boolean) {
        lateinit var session: GeckoSession
            internal set
        @Volatile var url: String? = null
        @Volatile var title: String = ""
        @Volatile var canGoBack = false
        @Volatile var canGoForward = false
        @Volatile var loading = false
        @Volatile var cursor = CursorShape.DEFAULT

        /** The page's own icon, as its helper reported it. */
        @Volatile var iconUrl: String? = null

        /** The page's web app manifest, as Gecko processed it. */
        @Volatile var manifest: JSONObject? = null

        /** The page helper of the page now in the tab, while it is connected. */
        var port: WebExtension.Port? = null

        /** Permissions the tab's page asked for, oldest first; the chrome shows the first. */
        val prompts = ArrayDeque<PermissionPrompt>()

        /** Where the page was, history included, as Gecko last reported it. */
        internal var sessionState: GeckoSession.SessionState? = null

        var suspended = false
            internal set

        /** When the tab last had the display, or was opened; elapsed realtime. */
        var lastSeenAt = SystemClock.elapsedRealtime()
            internal set

        /** Playing sound or video, or using the camera or microphone: not to be put to sleep. */
        @Volatile var playing = false
        @Volatile var capturing = false
    }

    /** A permission a page asked for; [kind] is the protocol's `PermissionKind`. */
    class PermissionPrompt(
        val id: Int,
        val kind: String,
        val host: String,
        val rememberable: Boolean,
        val answer: (allow: Boolean, remember: Boolean) -> Unit,
    )

    private var nextPrompt = 1

    /** An ordinary tab's page was visited, for history. UI thread. */
    var onVisited: ((Tab, String) -> Unit)? = null

    /** A tab's title changed. UI thread. */
    var onTitle: ((Tab) -> Unit)? = null

    /** A tab's page found its icon. UI thread. */
    var onPageIcon: ((Tab) -> Unit)? = null

    /**
     * A page asked for a new window. True when it was opened elsewhere, which
     * leaves the page without a handle on it; otherwise it becomes a tab here. UI thread.
     */
    var onNewWindow: ((String) -> Boolean)? = null

    /**
     * The phone's clipboard, for Ctrl+C, Ctrl+X and Ctrl+V from a physical
     * keyboard, which Gecko does not act on for a view that never has window
     * focus; the page helper does them instead.
     */
    var clipboard: dev.halone.engine.webview.TextClipboard? = null

    /** Keys whose press was a clipboard shortcut, so their release goes nowhere either. */
    private val clipboardKeys = HashSet<Int>()

    /** A response the tab will not show, which is a download. UI thread. */
    var onDownload: ((Tab, WebResponse) -> Unit)? = null

    private var nextRequest = 1
    private val pendingInspections = HashMap<Int, (JSONObject?) -> Unit>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var nextTab = 1
    val tabs = mutableListOf<Tab>()
    var active: Tab
        private set
    private val session: GeckoSession get() = active.session
    private var display: GeckoDisplay? = null
    private var displayTab: Tab? = null
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null

    /** The page's viewport, which a resize, a browser toolbar hiding or a tab row showing changes. */
    @Volatile
    override var widthPx: Int = initialWidthPx
        private set

    @Volatile
    override var heightPx: Int = initialHeightPx
        private set

    /** Tabs, titles, history or loading changed. UI thread. */
    var onChange: (() -> Unit)? = null

    /** The active tab scrolled, with its new vertical offset. UI thread. */
    var onScroll: ((Int) -> Unit)? = null

    /** The active tab's page entered or left full screen. UI thread. */
    var onFullScreen: ((Boolean) -> Unit)? = null

    @Volatile
    var lastLocation: String? = null
        private set

    @Volatile
    var scrollY: Int = 0
        private set

    @Volatile
    var lastScrollAt: Long = 0
        private set

    @Volatile
    var pageLoaded = false
        private set

    init {
        active = openTab(firstTabPrivate)
        tabs += active
        bind(active)
    }

    /** Shows a page's dialogs: alerts, dropdowns, file pickers. Applies to every tab, those to come included. */
    var promptDelegate: GeckoSession.PromptDelegate? = null
        set(value) {
            field = value
            tabs.forEach { it.session.promptDelegate = value }
        }

    /** Opens a tab's session at once, or leaves that to Gecko when it asked for the tab. */
    private fun openTab(private: Boolean, open: Boolean = true): Tab {
        val tab = Tab(nextTab++, private)
        tab.session = newSession(tab)
        if (open) tab.session.open(runtime)
        return tab
    }

    private fun newSession(tab: Tab): GeckoSession {
        val settings = GeckoSessionSettings.Builder().usePrivateMode(tab.private).displayMode(displayMode).build()
        val session = GeckoSession(settings)
        session.settings.userAgentMode = userAgentMode()
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                permissions: MutableList<org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                tab.url = url
                if (tab === active) lastLocation = url
                log("  gecko: tab ${tab.id} location -> $url (userGesture=$hasUserGesture)")
                onChange?.invoke()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
                onChange?.invoke()
            }

            /** A link to a new window, or `window.open`: a new tab next to this one, in the same mode. */
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (onNewWindow?.invoke(uri) == true) return null
                log("  gecko: tab ${tab.id} opens a new tab for $uri")
                val child = openTab(tab.private, open = false)
                tabs.add(tabs.indexOf(tab) + 1, child)
                mainHandler.post { select(child) }
                return GeckoResult.fromValue(child.session)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
                onChange?.invoke()
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                denyPrompts(tab)
                tab.manifest = null
                tab.loading = true
                onChange?.invoke()
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.loading = false
                if (tab === active) pageLoaded = success
                log("  gecko: tab ${tab.id} page stop (success=$success)")
                onChange?.invoke()
            }

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                tab.sessionState = sessionState
            }
        }

        session.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                tab.playing = true
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                tab.playing = false
            }

            override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
                tab.playing = false
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                tab.playing = false
            }
        }

        session.mediaDelegate = object : GeckoSession.MediaDelegate {
            override fun onRecordingStatusChanged(session: GeckoSession, devices: Array<GeckoSession.MediaDelegate.RecordingDevice>) {
                tab.capturing = devices.isNotEmpty()
            }
        }

        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                if (tab !== active) return
                this@GeckoPanel.scrollY = scrollY
                lastScrollAt = SystemClock.elapsedRealtime()
                onScroll?.invoke(scrollY)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tab.title = title.orEmpty()
                onTitle?.invoke(tab)
                onChange?.invoke()
            }

            override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) {
                log("  gecko: tab ${tab.id} manifest $manifest")
                tab.manifest = manifest
                onChange?.invoke()
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                log("  gecko: tab ${tab.id} download ${response.uri}")
                onDownload?.invoke(tab, response)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                log("  gecko: tab ${tab.id} ${if (fullScreen) "entered" else "left"} full screen")
                if (tab === active) onFullScreen?.invoke(fullScreen)
            }

            override fun onPointerIconChange(session: GeckoSession, icon: android.view.PointerIcon) {
                tab.cursor = CursorShape.of(appContext, icon)
            }

            override fun onCrash(session: GeckoSession) = log("  gecko: CONTENT PROCESS CRASHED")
            override fun onKill(session: GeckoSession) = log("  gecko: content process killed")
        }

        session.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onVisited(session: GeckoSession, url: String, lastVisitedURL: String?, flags: Int): GeckoResult<Boolean> {
                val skipped = GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE or
                    GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE_PERMANENT or
                    GeckoSession.HistoryDelegate.VISIT_UNRECOVERABLE_ERROR
                val record = !tab.private && flags and GeckoSession.HistoryDelegate.VISIT_TOP_LEVEL != 0 && flags and skipped == 0
                if (record) onVisited?.invoke(tab, url)
                return GeckoResult.fromValue(record)
            }
        }

        session.permissionDelegate = permissionDelegate(tab)
        session.selectionActionDelegate = object : GeckoSession.SelectionActionDelegate {
            override fun onShowClipboardPermissionRequest(
                session: GeckoSession,
                permission: GeckoSession.SelectionActionDelegate.ClipboardPermission,
            ): GeckoResult<org.mozilla.geckoview.AllowOrDeny> {
                val result = GeckoResult<org.mozilla.geckoview.AllowOrDeny>()
                ask(tab, "clipboard", hostOf(permission.uri), rememberable = false) { allow, _ ->
                    result.complete(if (allow) org.mozilla.geckoview.AllowOrDeny.ALLOW else org.mozilla.geckoview.AllowOrDeny.DENY)
                }
                return result
            }

            override fun onDismissClipboardPermissionRequest(session: GeckoSession) {
                if (tab.prompts.removeAll { it.kind == "clipboard" }) onChange?.invoke()
            }
        }
        session.promptDelegate = promptDelegate
        extensions.attach(session, helperDelegate(tab))
        return session
    }

    /**
     * What a page may do without asking, what it is never allowed, and what
     * waits for the wearer. Notifications are refused outright: there is no
     * notification system to show them in.
     */
    private fun permissionDelegate(tab: Tab) = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
            val allow = GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            val deny = GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            val kind = when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> return allow
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "geolocation"
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "persistent-storage"
                GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "drm"
                GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "storage-access"
                GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> "local-network"
                GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> "local-device"
                else -> return deny
            }
            val asker = if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS) perm.thirdPartyOrigin else perm.uri
            val result = GeckoResult<Int>()
            ask(tab, kind, hostOf(asker ?: perm.uri), rememberable = !perm.privateMode) { granted, remember ->
                val value = if (granted) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                if (remember && !perm.privateMode) runtime.storageController.setPermission(perm, value)
                result.complete(value)
            }
            return result
        }

        /** The microphone only: the phone's camera is in a pocket, and screen sharing is not offered. */
        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            val microphone = audio?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE }
            if (!video.isNullOrEmpty() || microphone == null) return callback.reject()
            ask(tab, "microphone", hostOf(uri), rememberable = false) { granted, _ ->
                if (granted) callback.grant(null as GeckoSession.PermissionDelegate.MediaSource?, microphone) else callback.reject()
            }
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            val missing = permissions.orEmpty().filter {
                appContext.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) return callback.grant()
            androidPermissions(missing.toTypedArray()) { granted ->
                // Android offers "approximate" location, which grants the coarse permission
                // without the fine one Gecko asks for alongside it; that is enough to locate.
                val location = setOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)
                val enough = missing.all { it in granted || (it in location && granted.any { g -> g in location }) }
                if (enough) callback.grant() else callback.reject()
            }
        }
    }

    private fun hostOf(uri: String): String = runCatching { java.net.URI(uri).host }.getOrNull() ?: uri

    private fun ask(tab: Tab, kind: String, host: String, rememberable: Boolean, answer: (Boolean, Boolean) -> Unit) {
        log("  gecko: tab ${tab.id} asks for $kind ($host)")
        tab.prompts.addLast(PermissionPrompt(nextPrompt++, kind, host, rememberable, answer))
        onChange?.invoke()
    }

    /** Asks the wearer in the prompt bar on behalf of [session]'s tab, as a page's own request does. UI thread. */
    fun askFor(session: GeckoSession, kind: String, host: String, answer: (Boolean) -> Unit) {
        val tab = tabs.firstOrNull { it.session === session } ?: return answer(false)
        ask(tab, kind, host, rememberable = false) { allow, _ -> answer(allow) }
    }

    /** Answers a prompt in any tab; true if there was one with that id. UI thread. */
    fun answerPrompt(id: Int, allow: Boolean, remember: Boolean): Boolean {
        val tab = tabs.firstOrNull { tab -> tab.prompts.any { it.id == id } } ?: return false
        val prompt = tab.prompts.first { it.id == id }
        tab.prompts.remove(prompt)
        log("  gecko: tab ${tab.id} ${if (allow) "allowed" else "denied"} ${prompt.kind}${if (remember) ", remembered" else ""}")
        prompt.answer(allow, remember)
        onChange?.invoke()
        return true
    }

    /** A page that goes takes its questions with it. */
    private fun denyPrompts(tab: Tab) {
        if (tab.prompts.isEmpty()) return
        val pending = tab.prompts.toList()
        tab.prompts.clear()
        pending.forEach { it.answer(false, false) }
        onChange?.invoke()
    }

    private fun helperDelegate(tab: Tab) = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            tab.port = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val json = message as? JSONObject ?: return
                    when (json.optString("type")) {
                        "page" -> json.optString("icon").takeIf { it.isNotEmpty() }?.let {
                            tab.iconUrl = it
                            onPageIcon?.invoke(tab)
                        }
                        "context", "middle" -> pendingInspections.remove(json.optInt("id"))?.invoke(json)
                        "copied" -> json.optString("text").takeIf { it.isNotEmpty() }?.let { clipboard?.write(it) }
                    }
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (tab.port === port) tab.port = null
                }
            })
        }
    }

    /**
     * Asks the active page what is at ([x], [y]) in its pixels: a link, an
     * image, an editable field, the selected text, or that the page shows its
     * own menu there. Null when the page has no helper, as on an error page,
     * or it does not answer in time. UI thread.
     */
    fun inspect(x: Float, y: Float, answer: (JSONObject?) -> Unit) = ask("context", x, y, answer)

    /**
     * Middle-clicks at ([x], [y]) in the active page's pixels, which the page
     * hears as `auxclick`; [answer] gets the link there to open in a new tab,
     * or null when there is none or the page handled the click. UI thread.
     */
    fun middleClick(x: Float, y: Float, answer: (String?) -> Unit) = ask("middle", x, y) { reply ->
        answer(reply?.optString("link")?.takeIf { reply.has("link") && !reply.isNull("link") && it.isNotEmpty() })
    }

    private fun ask(type: String, x: Float, y: Float, answer: (JSONObject?) -> Unit) {
        val port = active.port ?: return answer(null)
        val id = nextRequest++
        pendingInspections[id] = answer
        mainHandler.postDelayed({ pendingInspections.remove(id)?.invoke(null) }, INSPECT_TIMEOUT_MS)
        runCatching { port.postMessage(JSONObject().put("type", type).put("id", id).put("x", x).put("y", y)) }
            .onFailure { pendingInspections.remove(id)?.invoke(null) }
    }

    /** Edits the field of the last [inspect]: `paste` with [text], `cut` or `selectAll`. */
    fun edit(command: String, text: String? = null) {
        val message = JSONObject().put("type", command)
        text?.let { message.put("text", it) }
        runCatching { active.port?.postMessage(message) }
    }

    /**
     * Gives [tab] the display. Without `setActive` and `setFocused` Gecko treats
     * a session as backgrounded and does not paint, so the surface only ever
     * receives blank frames; the tab giving the display up is backgrounded.
     */
    private fun bind(tab: Tab) {
        val now = SystemClock.elapsedRealtime()
        tab.lastSeenAt = now
        displayTab?.takeIf { it !== tab }?.let { previous ->
            previous.lastSeenAt = now
            display?.let {
                it.surfaceDestroyed()
                previous.session.releaseDisplay(it)
            }
            display = null
            previous.session.setActive(false)
            keyboard?.detach(previous.session)
        }
        displayTab = tab
        tab.session.setActive(true)
        tab.session.setFocused(true)
        keyboard?.attach(tab.session)
        val target = surface ?: return
        if (display == null) {
            val newDisplay = tab.session.acquireDisplay()
            display = newDisplay
            newDisplay.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(target).size(widthPx, heightPx).build())
        }
    }

    /** Opens a tab and makes it the active one; with no [uri] it stays blank, for the wearer to type into. */
    /** Opens a tab, at the end; one opened in the [background] loads without taking the display. */
    fun newTab(uri: String?, private: Boolean = false, background: Boolean = false): Tab {
        val tab = openTab(private)
        tabs += tab
        if (background) {
            tab.session.setActive(false)
            uri?.let { tab.session.load(GeckoSession.Loader().uri(it)) }
            onChange?.invoke()
            return tab
        }
        select(tab)
        uri?.let { load(it) }
        return tab
    }

    fun select(tab: Tab) {
        if (tab !in tabs) return
        wake(tab)
        active = tab
        lastLocation = tab.url
        bind(tab)
        onChange?.invoke()
    }

    /** Closes a tab; the last one cannot be closed, which is closing the window. */
    fun close(tab: Tab): Boolean {
        if (tabs.size <= 1 || tab !in tabs) return false
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        if (tab === active) select(tabs[minOf(index, tabs.lastIndex)])
        closeSession(tab)
        onChange?.invoke()
        return true
    }

    /** The tabs other than the active one whose pages are still loaded. */
    fun backgroundTabs(): List<Tab> = tabs.filter { it !== active && !it.suspended }

    /** Puts a background tab to sleep, closing its page; selecting it brings the page back where it was. */
    fun suspend(tab: Tab): Boolean {
        if (tab === active || tab.suspended || tab !in tabs) return false
        log("  gecko: tab ${tab.id} put to sleep after ${(SystemClock.elapsedRealtime() - tab.lastSeenAt) / 1000} s unseen")
        closeSession(tab)
        tab.suspended = true
        tab.loading = false
        tab.playing = false
        tab.capturing = false
        tab.port = null
        onChange?.invoke()
        return true
    }

    private fun wake(tab: Tab) {
        if (!tab.suspended) return
        log("  gecko: tab ${tab.id} woken")
        tab.suspended = false
        tab.session = newSession(tab)
        tab.session.open(runtime)
        val state = tab.sessionState
        if (state != null) tab.session.restoreState(state) else tab.url?.let { tab.session.load(GeckoSession.Loader().uri(it)) }
    }

    private fun closeSession(tab: Tab) {
        denyPrompts(tab)
        if (tab.suspended) return
        keyboard?.detach(tab.session)
        extensions.detach(tab.session)
        tab.session.close()
    }

    /** Resizes the page's viewport; the page reflows to fit, as in any browser. UI thread. */
    fun resizeViewport(width: Int, height: Int) {
        if (width == widthPx && height == heightPx) return
        widthPx = width
        heightPx = height
        surfaceTexture?.setDefaultBufferSize(width, height)
        val target = surface ?: return
        display?.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(target).size(width, height).build())
    }

    override fun resize(widthPx: Int, heightPx: Int) = resizeViewport(widthPx, heightPx)

    fun scrollBy(dy: Int) = session.panZoomController.scrollBy(
        ScreenLength.fromPixels(0.0),
        ScreenLength.fromPixels(dy.toDouble()),
        PanZoomController.SCROLL_BEHAVIOR_AUTO,
    )

    fun back() = session.goBack()
    fun forward() = session.goForward()
    fun reload() = session.reload()
    fun stop() = session.stop()

    /**
     * Phase one, on the **GL thread**: a `SurfaceTexture` needs a live texture
     * name from the current context, so it can only be built here.
     */
    override fun createTexture(textureId: Int): SurfaceTexture {
        val surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(widthPx, heightPx)
        }
        this.surfaceTexture = surfaceTexture
        surface = Surface(surfaceTexture)
        log("  gecko: texture $textureId created at ${widthPx}x$heightPx")
        return surfaceTexture
    }

    /**
     * Phase two, on the **UI thread**: GeckoView asserts the UI thread in
     * `acquireDisplay`, so attaching cannot happen where the texture was made.
     * Panel creation is therefore always a two-step handshake across threads —
     * the compositor has to be built expecting that.
     */
    override fun attach() {
        surface ?: error("createTexture must run before attach")
        bind(active)
        log("  gecko: display attached")
        load(url)
    }

    fun load(uri: String) {
        log("  gecko: loading $uri")
        pageLoaded = false
        session.load(GeckoSession.Loader().uri(uri))
    }

    fun loadHtml(html: String) {
        log("  gecko: loading ${html.length} bytes of inline html")
        pageLoaded = false
        session.load(GeckoSession.Loader().data(html, "text/html"))
    }

    /** Synthesises a tap in panel pixels and feeds it to Gecko. */
    override fun dispatchTap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, down, x, y)
        send(MotionEvent.ACTION_UP, down, down + TAP_MS, x, y)
    }

    /**
     * Synthesises a drag. Gecko's pan/zoom controller needs a plausible sequence
     * of moves with sane timestamps to recognise a fling versus a pan, so this
     * interpolates rather than jumping straight to the end point.
     */
    override fun dispatchScroll(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        steps: Int,
    ) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, down, fromX, fromY)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            send(
                MotionEvent.ACTION_MOVE,
                down,
                down + (i * STEP_MS),
                fromX + (toX - fromX) * t,
                fromY + (toY - fromY) * t,
            )
        }
        send(MotionEvent.ACTION_UP, down, down + (steps * STEP_MS), toX, toY)
    }

    override fun dispatchWheel(x: Float, y: Float, horizontal: Float, vertical: Float) {
        sendMouse(MotionEvent.ACTION_SCROLL, x, y, horizontal, vertical)
    }

    private var hoverX = 0f
    private var hoverY = 0f

    override fun dispatchHover(x: Float, y: Float) {
        hoverX = x
        hoverY = y
        sendMouse(MotionEvent.ACTION_HOVER_MOVE, x, y, 0f, 0f)
    }

    override fun dispatchHoverExit() = sendMouse(MotionEvent.ACTION_HOVER_EXIT, hoverX, hoverY, 0f, 0f)

    private fun sendMouse(action: Int, x: Float, y: Float, horizontal: Float, vertical: Float) {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try {
            // onMouseEvent drops every event from a mouse tool; hover and the wheel are motion events.
            session.panZoomController.onMotionEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun clipboardKey(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP) return clipboardKeys.remove(event.keyCode)
        val clipboard = clipboard ?: return false
        val port = active.port ?: return false
        if (!(event.isCtrlPressed || event.isMetaPressed) || event.isAltPressed) return false
        val message = when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_C -> JSONObject().put("type", "copyKey").put("id", nextRequest++).put("cut", false)
            android.view.KeyEvent.KEYCODE_X -> JSONObject().put("type", "copyKey").put("id", nextRequest++).put("cut", true)
            android.view.KeyEvent.KEYCODE_V -> JSONObject().put("type", "pasteKey").put("text", clipboard.read() ?: "")
            else -> return false
        }
        clipboardKeys += event.keyCode
        if (event.repeatCount == 0) runCatching { port.postMessage(message) }
        return true
    }

    override fun dispatchKey(event: android.view.KeyEvent): Boolean = if (clipboardKey(event)) true else when (event.action) {
        android.view.KeyEvent.ACTION_DOWN -> session.textInput.onKeyDown(event.keyCode, event)
        android.view.KeyEvent.ACTION_UP -> session.textInput.onKeyUp(event.keyCode, event)
        else -> false
    }

    private var touchDownTime = 0L

    override fun dispatchTouch(phase: TouchPhase, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (phase == TouchPhase.DOWN) touchDownTime = now
        val action = when (phase) {
            TouchPhase.DOWN -> MotionEvent.ACTION_DOWN
            TouchPhase.MOVE -> MotionEvent.ACTION_MOVE
            TouchPhase.UP -> MotionEvent.ACTION_UP
            TouchPhase.CANCEL -> MotionEvent.ACTION_CANCEL
        }
        send(action, touchDownTime, now, x, y)
    }

    private fun send(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            session.panZoomController.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    override fun close() {
        display?.let {
            it.surfaceDestroyed()
            displayTab?.session?.releaseDisplay(it)
        }
        display = null
        displayTab = null
        surface?.release()
        surface = null
        surfaceTexture = null
        tabs.forEach(::closeSession)
        tabs.clear()
    }

    companion object {
        /** What pages are told the screen is: a web window's size, which full screen then takes. */
        const val SCREEN_WIDTH_PX = 1280
        const val SCREEN_HEIGHT_PX = 800

        private const val TAP_MS = 60L
        private const val INSPECT_TIMEOUT_MS = 600L
        private const val STEP_MS = 12L

        @Volatile
        private var runtimeInstance: GeckoRuntime? = null

        /** Exactly one runtime per process; a second create() throws. */
        fun sharedRuntime(context: Context): GeckoRuntime =
            runtimeInstance ?: synchronized(this) {
                runtimeInstance ?: GeckoRuntime.create(
                    context.applicationContext,
                    GeckoRuntimeSettings.Builder()
                        .consoleOutput(true)
                        // Without this, Gecko renders at the phone's density
                        // (~3.75x here), so a 1280x800 panel shows barely a
                        // third of the page and panel pixels no longer map 1:1
                        // to CSS pixels — which breaks ray-to-pixel hit testing.
                        // Panels want their own scale, set per panel by the
                        // compositor, not the handset's.
                        .displayDensityOverride(1.0f)
                        .displayDpiOverride(160)
                        // Gecko lays a full-screen element out to the screen,
                        // which would otherwise be the phone's, portrait: the
                        // player came out 1080 wide and 2340 tall, controls
                        // far below the window.
                        .screenSizeOverride(SCREEN_WIDTH_PX, SCREEN_HEIGHT_PX)
                        .build()
                ).also {
                    runtimeInstance = it
                    clearGpuProcessOverride()
                    blockNotifications()
                }
            }

        /**
         * We briefly disabled the out-of-process GPU compositor while chasing
         * blank frames. It was not the cause — the real problem was rendering a
         * top-level `data:` document — so undo the persisted pref rather than
         * leaving a performance-relevant override in the profile.
         */
        /**
         * Notifications report "denied" from the start rather than "default",
         * which is what sites check before showing their own "turn on
         * notifications?" banner; push goes with them. Set on the default
         * branch, so nothing is written to the profile.
         */
        private fun blockNotifications() {
            GeckoPreferenceController.setGeckoPref("permissions.default.desktop-notification", 2, GeckoPreferenceController.PREF_BRANCH_DEFAULT)
            GeckoPreferenceController.setGeckoPref("dom.push.enabled", false, GeckoPreferenceController.PREF_BRANCH_DEFAULT)
        }

        private fun clearGpuProcessOverride() {
            GeckoPreferenceController
                .clearGeckoUserPref("layers.gpu-process.enabled")
                .accept({ }, { })
        }
    }
}
