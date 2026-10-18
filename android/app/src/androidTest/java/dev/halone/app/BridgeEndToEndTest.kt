package dev.halone.app

import android.graphics.SurfaceTexture
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import dev.halone.app.shell.WebMessagePortChannel
import dev.halone.compositor.Panel
import dev.halone.compositor.PanelEngine
import dev.halone.compositor.PanelPose
import dev.halone.compositor.Scene
import dev.halone.engine.webview.TextField
import dev.halone.engine.webview.WebViewBridge
import dev.halone.engine.webview.WebViewEngine
import dev.halone.engine.webview.WebViewTextInput
import dev.halone.shell.AppInfo
import dev.halone.shell.BridgeHost
import dev.halone.shell.SettingsStore
import dev.halone.shell.Shell
import dev.halone.shell.ShellPlatform
import dev.halone.shell.ShellSettings
import dev.halone.shell.ShellWindow
import dev.halone.shell.SystemInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The built System app, the real SDK bundle, a real WebView message port and
 * the Kotlin host, with no glasses: panels have no pixels, and the page is read
 * back through the DOM. Asserts on text the page can only have got over the
 * bridge.
 */
@RunWith(AndroidJUnit4::class)
class BridgeEndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scene = Scene()
    private lateinit var shell: Shell<Panel>
    private lateinit var host: BridgeHost
    private lateinit var webView: WebView

    private class NoPixels(override val widthPx: Int, override val heightPx: Int) : PanelEngine {
        override fun createTexture(textureId: Int): SurfaceTexture = error("no GL in this test")
        override fun attach() = Unit
        override fun dispatchTap(x: Float, y: Float) = Unit
        override fun dispatchScroll(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int) = Unit
        override fun close() = Unit
    }

    private val openedUrls = mutableListOf<String>()
    private val fields = mutableListOf<TextField?>()
    private var textInput: WebViewTextInput? = null

    private val platform = object : ShellPlatform<Panel> {
        override fun openApp(windowId: String, app: AppInfo, url: String) = open(windowId, app.widthPx, app.heightPx)
        override fun openWeb(windowId: String, url: String, private: Boolean): Panel {
            openedUrls += url
            return open(windowId, 1280, 800)
        }
        private fun open(id: String, width: Int, height: Int) = scene.open(Panel(id, NoPixels(width, height), PanelPose()))
        override fun close(panel: Panel) = scene.remove(panel)
        override fun openOverlays(dock: AppInfo, dockUrl: String, launcher: AppInfo, launcherUrl: String) = Unit
        override fun recenter() = Unit
        override fun headYawRadians() = 0f
        override fun headPitchRadians() = 0f
        override fun applySettings(settings: ShellSettings, changedKey: String?) = Unit
    }

    private fun load(window: ShellWindow<Panel>) {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(WebViewEngine.ASSET_DOMAIN)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    loader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    WebViewBridge.connect(view, url, WebViewEngine.ORIGIN, { port -> host.connect(window.id, WebMessagePortChannel(port)) }) {}
                }
            }
        }
        textInput = WebViewTextInput.install(webView, WebViewEngine.ORIGIN, null) { field -> fields += field }
        webView.loadUrl(window.url)
    }

    private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

    private fun evaluate(expression: String): String? {
        var result: String? = null
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(expression) {
                result = JSONTokener(it).nextValue() as? String
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun waitForText(expected: String, timeoutMs: Long = 20_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var text = ""
        while (System.currentTimeMillis() < deadline) {
            text = evaluate("document.body.textContent") ?: ""
            if (expected in text) return text
            Thread.sleep(250)
        }
        throw AssertionError("page never showed '$expected'; last text: $text")
    }

    private fun startShell(appId: String) {
        instrumentation.runOnMainSync {
            val store = object : SettingsStore {
                override fun load(): String? = null
                override fun save(json: String) = Unit
            }
            shell = Shell(scene, AppInfo.parseRegistry(asset("apps.json")), ShellSettings(store, asset(ShellSettings.SCHEMA_ASSET)), platform)
            host = BridgeHost(shell, SystemInfo("e2e", "Emulated Glasses", 19.0, 1920, 1080))
            load(shell.launch(appId))
        }
    }

    private fun <T> waitFor(what: String, timeoutMs: Long = 10_000, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** The phone's keyboard typing into the real launcher: focus, text, Enter, and a window opens. */
    @Test
    fun typingIntoTheLauncherOpensAPage() {
        startShell("launcher")
        waitForText("Open windows")

        evaluate("document.querySelector('.address input').focus(), 'ok'")
        val field = waitFor("a focused field") { fields.lastOrNull() }
        assertEquals("Search or enter a web address", field.placeholder)
        assertFalse(field.multiline)

        instrumentation.runOnMainSync { textInput!!.setText("example.com", 11, 11) }
        waitFor("the page to take the text") { evaluate("document.querySelector('.address input').value")?.takeIf { it == "example.com" } }

        instrumentation.runOnMainSync { textInput!!.submit() }
        assertEquals("https://example.com", waitFor("a window to open") { openedUrls.firstOrNull() })

        instrumentation.runOnMainSync { textInput!!.dismiss() }
        waitFor("the field to let go") { if (fields.lastOrNull() == null && fields.size > 1) true else null }
    }

    /** A physical keyboard's keys, dispatched to a panel's WebView, which never has window focus. */
    @Test
    fun physicalKeysTypeIntoTheFocusedField() {
        startShell("launcher")
        waitForText("Open windows")
        evaluate("document.querySelector('.address input').focus(), 'ok'")

        waitFor("a focused field") { fields.lastOrNull() }

        instrumentation.runOnMainSync {
            val keys = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            keys.getEvents("hix".toCharArray())!!.forEach { textInput!!.key(it) }
            press(android.view.KeyEvent.KEYCODE_DEL)
        }
        waitFor("the keys to arrive") { evaluate("document.querySelector('.address input').value")?.takeIf { it == "hi" } }

        instrumentation.runOnMainSync { press(android.view.KeyEvent.KEYCODE_ENTER) }
        assertEquals("https://duckduckgo.com/?q=hi", waitFor("a search to open") { openedUrls.firstOrNull() })
    }

    private fun press(keyCode: Int) {
        textInput!!.key(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        textInput!!.key(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    @Test
    fun theSystemAppTalksToTheKotlinHost() {
        startShell("about")

        val text = waitForText("Emulated Glasses")
        assertTrue("platform reported by the host", "android" in text)
        assertTrue("window id from windows.self", "w1" in text)
        assertTrue("protocol versions agree", "host 1 · sdk 1" in text)

        waitForText("1 window open")
        instrumentation.runOnMainSync { shell.launch("settings") }
        waitForText("2 windows open")

        evaluate("[...document.querySelectorAll('button')].find(b => b.textContent.includes('Measure')).click(), 'ok'")
        val measured = waitForText("ms median")
        assertTrue(measured, Regex("""[\d.]+ ms median""").containsMatchIn(measured))
    }
}
