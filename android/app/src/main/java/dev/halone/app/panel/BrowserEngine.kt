package dev.halone.app.panel

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import dev.halone.compositor.PanelEngine
import dev.halone.compositor.TopLayer
import dev.halone.compositor.TouchPhase
import dev.halone.engine.webview.WebViewEngine
import dev.halone.shell.BrowserBand
import dev.halone.shell.BrowserControl
import dev.halone.shell.BrowserPrompt
import dev.halone.shell.BrowserState
import dev.halone.shell.BrowserTab
import dev.halone.shell.MenuItem
import org.json.JSONObject

/** What a web window needs from the shell for its context menu. */
interface BrowserHost {
    /** Shows a menu over the window, answering with the item chosen or null; returns its id, or null if it could not show. */
    fun showMenu(items: List<MenuItem>, xPx: Float, yPx: Float, onChosen: (String?) -> Unit): Int?

    /** Shows a dialog over the window (the protocol's `DialogSpec`); returns its id, or null if it could not show. */
    fun showDialog(dialog: JSONObject, onAnswer: (String?, JSONObject) -> Unit): Int?

    /** Takes down a menu or dialog the page no longer wants answered. */
    fun cancelPopup(id: Int)
    fun openWindow(url: String, private: Boolean)

    /** Opens [url] in a tab of the browser, as a link out of an installed app does. */
    fun openInBrowser(url: String, private: Boolean)
    fun clipboardText(): String?
    fun setClipboardText(text: String)
    fun download(url: String, referrer: String?, private: Boolean)
    fun searchUrl(text: String): String
    fun isBookmarked(url: String): Boolean
    fun toggleBookmark()
}

/**
 * A web window: Gecko's tabs below, the browser chrome (a system page in a
 * WebView) in a band across the top. One engine to the rest of the shell, so
 * input reaches the right half by where it lands without anyone else knowing
 * there are two.
 *
 * The window never changes size: the band and the page share a fixed height,
 * and the page's viewport takes up what the band leaves. Worn, growing the
 * window instead moved the page under the wearer's finger mid-scroll, which
 * Gecko read as more scrolling, which brought the toolbar back — a flicker.
 */
class BrowserEngine(
    context: android.content.Context,
    private val content: GeckoPanel,
    private val chrome: WebViewEngine,
    private val autoHideToolbar: () -> Boolean,
    private val host: BrowserHost,
) : PanelEngine, TopLayer, BrowserControl {

    override val widthPx: Int get() = content.widthPx
    @Volatile
    override var heightPx: Int = content.heightPx + TOOLBAR_PX
        private set

    /** The window takes the new size; the page keeps what the band leaves, and the chrome spans the width. */
    override fun resize(widthPx: Int, heightPx: Int) {
        this.heightPx = heightPx
        content.resizeViewport(widthPx, heightPx - visibleHeightPx)
        chrome.resize(widthPx, chrome.heightPx)
    }

    override val topLayer: TopLayer get() = this
    override val engine: PanelEngine get() = chrome

    @Volatile
    override var visibleHeightPx: Int = TOOLBAR_PX
        private set

    override var onChange: (() -> Unit)? = null

    /** Whether the chrome's address field has focus, which is where typing then goes. */
    @Volatile
    var chromeFieldFocused = false

    private var hiddenByScroll = false
    private var wantHidden = false
    private var lastScrollY = 0

    /** A finger is on the page, or the page moved, this recently: no time to change its viewport. */
    private var touching = false
    private var lastActivityAt = 0L

    /**
     * When the wearer last scrolled, and until when the page's own movement is
     * not theirs. Resizing the viewport and keeping the page still both scroll
     * it; read as the wearer scrolling, that brought the toolbar back, whose
     * own scroll hid it again, and so on for ever.
     */
    private var lastInputAt = 0L
    private var ignoreScrollUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val settleCheck = Runnable { applyWhenSettled() }

    /** Where the wearer last pressed the page, in window pixels; a dropdown opens there. */
    private var lastPressX = content.widthPx / 2f
    private var lastPressY = content.heightPx / 2f

    /** A press on the page: it is where typing goes now, so the address field lets go, as it does on a desktop. */
    private fun pressed(x: Float, y: Float) {
        lastPressX = x
        lastPressY = y
        if (chromeFieldFocused) {
            chromeFieldFocused = false
            chrome.textInput?.dismiss()
        }
    }

    private val pageMenu = PageMenu(content, host, standalone = false)

    init {
        content.promptDelegate = GeckoPrompts(context, content, host) { lastPressX to lastPressY }
        content.onChange = {
            updateBand(keepPageStill = false)
            onChange?.invoke()
        }
        content.onScroll = ::onScroll
        content.onFullScreen = { full ->
            fullScreen = full
            updateBand(keepPageStill = false)
            onFullScreenChange?.invoke(full)
        }
    }

    /** While the page is full screen the toolbar is gone and the page has the whole window. */
    var fullScreen = false
        private set

    /** For the shell to give the window the screen's size while the page is full screen. */
    var onFullScreenChange: ((Boolean) -> Unit)? = null

    private fun onScroll(scrollY: Int) {
        val delta = scrollY - lastScrollY
        lastScrollY = scrollY
        val now = SystemClock.uptimeMillis()
        if (now < ignoreScrollUntil || now - lastInputAt > USER_SCROLL_MS) return
        wantHidden = when {
            !autoHideToolbar() || scrollY <= NEAR_TOP_PX -> false
            delta > SCROLL_SLOP_PX -> true
            delta < -SCROLL_SLOP_PX -> false
            else -> wantHidden
        }
        activity()
    }

    private fun input() {
        lastInputAt = SystemClock.uptimeMillis()
        activity()
    }

    private fun activity() {
        lastActivityAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(settleCheck)
        handler.postDelayed(settleCheck, SETTLE_MS)
    }

    /**
     * Hides or shows the toolbar once scrolling has come to rest. Changing the
     * viewport while a finger is down moves the page's coordinates under it.
     */
    private fun applyWhenSettled() {
        if (touching || SystemClock.uptimeMillis() - lastActivityAt < SETTLE_MS) return
        if (wantHidden == hiddenByScroll) return
        hiddenByScroll = wantHidden
        updateBand(keepPageStill = true)
    }

    fun setDesktopUserAgent(desktop: Boolean) = content.setDesktopUserAgent(desktop)

    /** Re-reads the auto-hide setting, so turning it off brings the toolbar straight back. */
    fun settingsChanged() {
        if (!autoHideToolbar() && hiddenByScroll) {
            hiddenByScroll = false
            wantHidden = false
            updateBand(keepPageStill = lastScrollY > NEAR_TOP_PX)
        }
    }

    /**
     * Gives the page whatever height the band leaves. When the toolbar goes or
     * comes back mid-page, the page is scrolled by the same amount so what the
     * wearer was reading stays where it was; at the top of the page, and when
     * the tab row comes or goes, it simply reflows.
     */
    private fun updateBand(keepPageStill: Boolean) {
        val next = BrowserBand.heightPx(content.tabs.size, content.active.prompts.isNotEmpty(), fullScreen, hiddenByScroll)
        val previous = visibleHeightPx
        if (next == previous) return
        visibleHeightPx = next
        ignoreScrollUntil = SystemClock.uptimeMillis() + OWN_SCROLL_MS
        content.resizeViewport(content.widthPx, heightPx - next)
        if (keepPageStill && (next < previous || lastScrollY > NEAR_TOP_PX)) content.scrollBy(next - previous)
    }

    override fun state(): BrowserState {
        val active = content.active
        return BrowserState(
            tabs = content.tabs.map { BrowserTab(it.id, it.title, it.url.orEmpty(), it.loading, it.private) },
            activeTab = active.id,
            canGoBack = active.canGoBack,
            canGoForward = active.canGoForward,
            loading = active.loading,
            prompt = active.prompts.firstOrNull()?.let { BrowserPrompt(it.id, it.kind, it.host, it.rememberable) },
        )
    }

    override fun answerPrompt(id: Int, allow: Boolean, remember: Boolean) {
        if (!content.answerPrompt(id, allow, remember)) throw dev.halone.shell.HaloneException("no-such-prompt", "no such prompt: $id")
    }

    override fun navigate(url: String) = content.load(url)
    override fun back() = content.back()
    override fun forward() = content.forward()
    override fun reload() = content.reload()
    override fun stop() = content.stop()

    override fun newTab(url: String?, private: Boolean) {
        content.newTab(url, private)
    }

    override fun iconUrl(): String? = content.active.iconUrl

    override fun manifest(): JSONObject? = content.active.manifest

    override fun dispatchContextMenu(x: Float, y: Float) {
        if (inChrome(y)) return chrome.dispatchContextMenu(x, y)
        pageMenu.show(x, y, y - visibleHeightPx)
    }

    override fun selectTab(id: Int) {
        content.tabs.firstOrNull { it.id == id }?.let(content::select)
    }

    override fun closeTab(id: Int) {
        content.tabs.firstOrNull { it.id == id }?.let(content::close)
    }

    override fun createTexture(textureId: Int): SurfaceTexture = content.createTexture(textureId)

    override fun attach() = content.attach()

    /** The engine a press began on, so a drag that crosses the band stays with it. */
    private var touchTarget: PanelEngine? = null

    private fun inChrome(y: Float) = y < visibleHeightPx

    override fun dispatchTap(x: Float, y: Float) {
        if (inChrome(y)) return chrome.dispatchTap(x, y)
        pressed(x, y)
        content.dispatchTap(x, y - visibleHeightPx)
    }

    override fun dispatchScroll(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int) {
        if (inChrome(fromY)) return chrome.dispatchScroll(fromX, fromY, toX, toY, steps)
        content.dispatchScroll(fromX, fromY - visibleHeightPx, toX, toY - visibleHeightPx, steps)
        input()
    }

    override fun dispatchTouch(phase: TouchPhase, x: Float, y: Float) {
        val target = if (phase == TouchPhase.DOWN) (if (inChrome(y)) chrome else content).also { touchTarget = it }
        else touchTarget ?: return
        if (target === content && phase == TouchPhase.DOWN) pressed(x, y)
        target.dispatchTouch(phase, x, if (target === content) y - visibleHeightPx else y)
        if (target === content) {
            touching = phase == TouchPhase.DOWN || phase == TouchPhase.MOVE
            input()
        }
        if (phase == TouchPhase.UP || phase == TouchPhase.CANCEL) touchTarget = null
    }

    override fun dispatchWheel(x: Float, y: Float, horizontal: Float, vertical: Float) {
        if (inChrome(y)) return chrome.dispatchWheel(x, y, horizontal, vertical)
        content.dispatchWheel(x, y - visibleHeightPx, horizontal, vertical)
        input()
    }

    private var hoveringChrome = false

    override fun dispatchHover(x: Float, y: Float) {
        hoveringChrome = inChrome(y)
        if (hoveringChrome) chrome.dispatchHover(x, y) else content.dispatchHover(x, y - visibleHeightPx)
    }

    override val cursorShape get() = if (hoveringChrome) chrome.cursorShape else content.cursorShape

    override fun dispatchHoverExit() {
        chrome.dispatchHoverExit()
        content.dispatchHoverExit()
    }

    /** On a link, opens it in a new tab behind this one, as a desktop browser does. */
    override fun dispatchMiddleClick(x: Float, y: Float) {
        if (inChrome(y)) return chrome.dispatchMiddleClick(x, y)
        val tab = content.active
        content.middleClick(x, y - visibleHeightPx) { link ->
            if (link != null && content.tabs.any { it === tab }) content.newTab(link, tab.private, background = true)
        }
    }

    override fun dispatchKey(event: KeyEvent): Boolean =
        if (chromeFieldFocused) chrome.dispatchKey(event) else content.dispatchKey(event)

    override fun close() {
        handler.removeCallbacks(settleCheck)
        chrome.close()
        content.close()
    }

    companion object {
        private const val TOOLBAR_PX = BrowserBand.TOOLBAR_PX
        private const val TABS_PX = BrowserBand.TABS_PX

        private const val SCROLL_SLOP_PX = 8

        /** Near the top the toolbar always shows, and there is room to scroll by its height. */
        private const val NEAR_TOP_PX = TOOLBAR_PX + TABS_PX + 16

        /** How long scrolling has to be still before the toolbar hides or comes back. */
        private const val SETTLE_MS = 150L

        /** How long after the wearer's last input the page moving is still theirs: a fling. */
        private const val USER_SCROLL_MS = 1_500L

        /** How long the page's own movement after a viewport change is ignored. */
        private const val OWN_SCROLL_MS = 600L
    }
}
