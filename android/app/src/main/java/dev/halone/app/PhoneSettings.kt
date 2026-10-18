package dev.halone.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import dev.halone.app.shell.WebMessagePortChannel
import dev.halone.engine.webview.BridgeConnector
import dev.halone.engine.webview.WebViewBridge
import dev.halone.engine.webview.WebViewEngine
import dev.halone.shell.BridgeHost

/**
 * The Settings app on the phone's own screen, over everything else, talking
 * to [bridge] as a window of its own would. Links leave for the phone's browser.
 */
class PhoneSettings(
    private val activity: ComponentActivity,
    private val bridge: BridgeHost,
    private val onClosed: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val assets = WebViewAssetLoader.Builder()
        .setDomain(WebViewEngine.ASSET_DOMAIN)
        .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(activity))
        .build()

    private var view: WebView? = null
    private var frame: FrameLayout? = null
    private var awaitingBridge = false

    val shown: Boolean get() = view != null

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        if (view != null) return
        val web = WebView(activity).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assets.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == WebViewEngine.ASSET_DOMAIN) return false
                    openExternally(request.url)
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    bridge.disconnect(WINDOW_ID)
                    awaitingBridge = true
                }

                // Also called for history.pushState, which keeps the page and so its bridge.
                override fun onPageFinished(view: WebView, url: String) {
                    if (!awaitingBridge) return
                    awaitingBridge = false
                    WebViewBridge.connect(view, url, ORIGIN, BridgeConnector { port -> bridge.connect(WINDOW_ID, WebMessagePortChannel(port)) }, log)
                }
            }
            settings.setSupportMultipleWindows(true)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                    val probe = WebView(activity)
                    probe.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            openExternally(request.url)
                            probe.destroy()
                            return true
                        }
                    }
                    (resultMsg.obj as WebView.WebViewTransport).webView = probe
                    resultMsg.sendToTarget()
                    return true
                }
            }
        }
        val container = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            ViewCompat.setOnApplyWindowInsetsListener(this) { padded, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                padded.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.requestApplyInsets(container)
        web.loadUrl("$ORIGIN/settings/index.html?phone")
        view = web
        frame = container
    }

    /** Back goes up a level inside Settings first, then closes it. */
    fun back() {
        val web = view ?: return
        if (web.canGoBack()) web.goBack() else hide()
    }

    fun hide() {
        val web = view ?: return
        view = null
        bridge.disconnect(WINDOW_ID)
        frame?.let { (it.parent as? ViewGroup)?.removeView(it) }
        frame = null
        web.destroy()
        onClosed()
    }

    private fun openExternally(url: Uri) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, url)) }.onFailure { log("  settings: cannot open $url: $it") }
    }

    private companion object {
        const val WINDOW_ID = "phone-settings"
        const val ORIGIN = "https://${WebViewEngine.ASSET_DOMAIN}"
    }
}
