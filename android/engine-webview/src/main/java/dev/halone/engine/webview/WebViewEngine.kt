package dev.halone.engine.webview

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import dev.halone.compositor.CursorShape
import dev.halone.compositor.CursorShapes
import dev.halone.compositor.MotionSynthesis
import dev.halone.compositor.PanelEngine
import dev.halone.compositor.TouchPhase

/**
 * A system-app panel: a `WebView` rendered into a texture we own.
 *
 * Unlike GeckoView, a `WebView` cannot render into a `Surface` directly. The
 * officially supported route is a `VirtualDisplay` backed by a `SurfaceTexture`
 * with the `WebView` inside a `Presentation` on that display, which keeps the
 * content on the **hardware-accelerated** path. The tempting alternative —
 * `view.draw(canvas)` on a `Surface` canvas — is software-only and silently
 * drops WebGL and `<video>`, so it is never used here.
 *
 * Content is served from `https://halone.local/` via `WebViewAssetLoader` so
 * system apps run in a secure context and can use service workers, PWA APIs and
 * the clipboard.
 */
class WebViewEngine(
    private val context: Context,
    initialWidthPx: Int,
    initialHeightPx: Int,
    /** Path under [origin], loaded when the panel attaches. */
    private val path: String,
    private val log: (String) -> Unit,
    /** Where the pages are, and the only origin given the bridge and text input. */
    val origin: String = ORIGIN,
    /** The files served at [origin], or null to serve the app's assets there (or nothing, for an origin of its own). */
    private val root: java.io.File? = null,
    /** More of the shell's own files at [ORIGIN], by path prefix, ahead of the assets. */
    private val paths: List<Pair<String, WebViewAssetLoader.PathHandler>> = emptyList(),
) : PanelEngine {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var webView: WebView? = null

    /** A page finished loading. UI thread. */
    var onLoaded: (() -> Unit)? = null

    @Volatile
    override var widthPx: Int = initialWidthPx
        private set

    @Volatile
    override var heightPx: Int = initialHeightPx
        private set

    override fun resize(widthPx: Int, heightPx: Int) {
        if (widthPx == this.widthPx && heightPx == this.heightPx) return
        this.widthPx = widthPx
        this.heightPx = heightPx
        surfaceTexture?.setDefaultBufferSize(widthPx, heightPx)
        virtualDisplay?.resize(widthPx, heightPx, PANEL_DENSITY_DPI)
        // In place: once shown, the params are the Presentation's frame layout's
        // own subclass, and replacing them with plain ones crashes its measure.
        webView?.let {
            it.layoutParams = it.layoutParams.apply {
                width = widthPx
                height = heightPx
            }
        }
    }

    @Volatile
    var pageFinished = false
        private set

    /** Set before [attach] to give pages on our origin a bridge port. */
    var bridgeConnector: BridgeConnector? = null

    /** Set before [attach] to hear when a page on our origin focuses a text field, or null when it lets go. */
    var textFieldListener: ((TextField?) -> Unit)? = null

    var textInput: WebViewTextInput? = null
        private set

    private var awaitingBridge = false

    @Volatile
    override var cursorShape = CursorShape.DEFAULT
        private set

    /** Set before [attach] for copy and paste from a physical keyboard. */
    var clipboard: TextClipboard? = null

    /** Set before [attach] to show the page's dropdowns; [answer] takes the options chosen, or null when none was. */
    var selectListener: ((SelectPopup, answer: (List<Int>?) -> Unit) -> Unit)? = null

    private val assetLoader: WebViewAssetLoader? = when {
        root != null -> WebViewAssetLoader.Builder()
            .setDomain(android.net.Uri.parse(origin).host!!)
            .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(context, root))
            .build()
        origin == ORIGIN -> WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .apply { paths.forEach { (prefix, handler) -> addPathHandler(prefix, handler) } }
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        else -> null
    }

    /** Phase one, on the **GL thread**: the texture needs a live GL context. */
    override fun createTexture(textureId: Int): SurfaceTexture {
        val surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(widthPx, heightPx)
        }
        this.surfaceTexture = surfaceTexture
        surface = Surface(surfaceTexture)
        log("  webview: texture $textureId created at ${widthPx}x$heightPx")
        return surfaceTexture
    }

    /**
     * Phase two, on the **UI thread**: `VirtualDisplay`, `Presentation` and
     * `WebView` all require it.
     */
    @SuppressLint("SetJavaScriptEnabled")
    override fun attach() {
        val target = surface ?: error("createTexture must run before attach")
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        // A density of 160dpi makes one CSS pixel one panel pixel, so a pointer
        // ray converts to page coordinates with no scale factor in between.
        // Same lesson as the Gecko panel, different API.
        val display = displayManager.createVirtualDisplay(
            "halone-panel-${hashCode()}",
            widthPx,
            heightPx,
            PANEL_DENSITY_DPI,
            target,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        ) ?: error("createVirtualDisplay returned null")
        virtualDisplay = display

        // The Presentation must exist first: a WebView takes its CSS pixel scale
        // from its Context's resources, not from the display it happens to be
        // shown on. Built from the Activity context it renders at the handset's
        // density (2.625 here), so a "1280x800 panel" gets a 488px CSS viewport
        // and panel pixels stop matching CSS pixels. The Presentation's context
        // is a display context carrying the virtual display's 160dpi.
        val presentationForDisplay = Presentation(context, display.display)
        presentation = presentationForDisplay

        val view = object : WebView(presentationForDisplay.context) {
            override fun setPointerIcon(pointerIcon: android.view.PointerIcon?) {
                super.setPointerIcon(pointerIcon)
                cursorShape = CursorShapes.of(context, pointerIcon)
            }
        }.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            layoutParams = android.view.ViewGroup.LayoutParams(widthPx, heightPx)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ) = assetLoader?.shouldInterceptRequest(request.url)

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    awaitingBridge = true
                }

                // Also called for history.pushState, which keeps the page and so its bridge.
                override fun onPageFinished(view: WebView, url: String) {
                    pageFinished = true
                    log("  webview: page finished $url")
                    onLoaded?.invoke()
                    if (!awaitingBridge) return
                    awaitingBridge = false
                    bridgeConnector?.let { WebViewBridge.connect(view, url, origin, it, log) }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    log("  webview console: ${message.message()}")
                    return true
                }
            }
        }
        webView = view
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, NATIVE_CONTEXT_MENU_FILTER, setOf(origin))
        }

        textInput = textFieldListener?.let { listener ->
            WebViewTextInput.install(view, origin, clipboard, listener) ?: run {
                log("  webview: text input unsupported by this WebView")
                null
            }
        }

        selectListener?.let { listener ->
            WebViewSelects.install(view, origin, listener) ?: log("  webview: dropdowns unsupported by this WebView")
        }

        presentationForDisplay.setContentView(view)
        presentationForDisplay.show()

        val url = "$origin/$path"
        log("  webview: loading $url on virtual display ${display.display.displayId}")
        view.loadUrl(url)
    }

    fun currentUrl(): String? = webView?.url

    /** Reads a JS expression back, so a spike can assert instead of squinting. */
    fun evaluate(expression: String, onResult: (String?) -> Unit) {
        webView?.evaluateJavascript(expression) { onResult(it) } ?: onResult(null)
    }

    /** Evaluates a JS expression and parses the result as JSON. */
    suspend fun status(
        expression: String = "window.panelStatus ? window.panelStatus() : {}",
    ): org.json.JSONObject? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val view = webView
            if (view == null) {
                continuation.resume(null) { _, _, _ -> }
                return@suspendCancellableCoroutine
            }
            view.evaluateJavascript("JSON.stringify($expression)") { raw ->
                // evaluateJavascript hands back a JSON-encoded value, so a
                // string result arrives quoted and escaped; unwrap it once.
                val json = runCatching {
                    org.json.JSONObject(org.json.JSONTokener(raw).nextValue().toString())
                }.getOrNull()
                continuation.resume(json) { _, _, _ -> }
            }
        }

    /**
     * Because the `WebView` lives in a `Presentation` in our own process, plain
     * `dispatchTouchEvent` is enough — no system-level input injection and no
     * special permission.
     */
    private val motion = MotionSynthesis(
        onTouch = { webView?.dispatchTouchEvent(it) },
        onGeneric = { webView?.dispatchGenericMotionEvent(it) },
    )

    override fun dispatchTap(x: Float, y: Float) = motion.tap(x, y)

    override fun dispatchScroll(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int) =
        motion.scroll(fromX, fromY, toX, toY, steps)

    /**
     * A `contextmenu` event on the element at the point, as a right click
     * would fire; a page that listens for it shows its own menu. Dispatched
     * from script rather than as a secondary button press, which a WebView
     * without window focus does not turn into one.
     */
    override fun dispatchContextMenu(x: Float, y: Float) {
        webView?.evaluateJavascript(
            "(() => { const t = document.elementFromPoint($x, $y) || document.body; " +
                "t.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, composed: true, " +
                "clientX: $x, clientY: $y, button: 2, buttons: 2, view: window })); })()",
            null,
        )
    }

    override fun dispatchWheel(x: Float, y: Float, horizontal: Float, vertical: Float) = motion.wheel(x, y, horizontal, vertical)

    override fun dispatchHover(x: Float, y: Float) = motion.hover(x, y)

    override fun dispatchHoverExit() = motion.hoverExit()

    /** From script, as [dispatchContextMenu] is: a page that listens for `auxclick` handles it. */
    override fun dispatchMiddleClick(x: Float, y: Float) {
        webView?.evaluateJavascript(
            "(() => { const t = document.elementFromPoint($x, $y) || document.body; " +
                "const init = { bubbles: true, cancelable: true, composed: true, clientX: $x, clientY: $y, button: 1, view: window }; " +
                "t.dispatchEvent(new MouseEvent('mousedown', { ...init, buttons: 4 })); " +
                "t.dispatchEvent(new MouseEvent('mouseup', init)); " +
                "t.dispatchEvent(new MouseEvent('auxclick', init)); })()",
            null,
        )
    }

    override fun dispatchKey(event: android.view.KeyEvent): Boolean = textInput?.key(event) ?: false

    override fun dispatchTouch(phase: TouchPhase, x: Float, y: Float) = motion.touch(phase, x, y)

    override fun close() {
        presentation?.dismiss()
        presentation = null
        webView?.destroy()
        webView = null
        virtualDisplay?.release()
        virtualDisplay = null
        surface?.release()
        surface = null
    }

    companion object {
        const val ASSET_DOMAIN = "halone.local"
        const val ORIGIN = "https://$ASSET_DOMAIN"
        private const val PANEL_DENSITY_DPI = 160

        /**
         * Every context menu the wearer asks for arrives from [dispatchContextMenu],
         * untrusted. A trusted one is the WebView's own, fired as a touch selects
         * text (focusing the browser's address field did it), so the page never
         * sees it.
         */
        private val NATIVE_CONTEXT_MENU_FILTER = """
            window.addEventListener('contextmenu', (event) => {
              if (!event.isTrusted) return;
              event.stopImmediatePropagation();
              event.preventDefault();
            }, true);
        """.trimIndent()
    }
}
