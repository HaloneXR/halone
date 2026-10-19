package dev.halone.desktop.compositor.shell

import dev.halone.desktop.compositor.producer.ContextTarget
import dev.halone.desktop.compositor.producer.PageState
import dev.halone.desktop.compositor.producer.ProducerHost
import dev.halone.desktop.compositor.producer.PromptEvent
import dev.halone.desktop.compositor.render.DesktopPanel
import dev.halone.desktop.compositor.render.PanelLayer
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.shell.BrowserBand
import dev.halone.shell.BrowserControl
import dev.halone.shell.BrowserHistory
import dev.halone.shell.BrowserPrompt
import dev.halone.shell.BrowserState
import dev.halone.shell.BrowserTab
import dev.halone.shell.HaloneException
import dev.halone.shell.PageMenu
import dev.halone.shell.PageMenuHost
import dev.halone.shell.PageTarget
import dev.halone.shell.ToolbarAutoHide

/**
 * A web window, as BrowserEngine is on Android: the browser page (a system
 * app, with the bridge) in a band across the top, and below it the tabs, each
 * a page of the producer's, the active one shown. The window keeps its size;
 * the tabs' pages get what the band leaves. The band goes as the wearer
 * scrolls down and comes back as they scroll up, grows a row for a page's
 * permission prompt, and goes while the page is full screen.
 */
class DesktopBrowser(
    private val host: ProducerHost,
    val panel: DesktopPanel,
    private val chromeHeightPx: Int,
    private val history: BrowserHistory,
    private val menuHost: PageMenuHost,
    autoHideToolbar: () -> Boolean,
    private val frame: () -> Long,
    /** Called for each producer panel this window opens, so its frames and pages find their way back here. */
    private val claim: (index: Int) -> Unit,
    private val release: (index: Int) -> Unit,
) : BrowserControl {
    class Tab(val id: Int, val producer: Int, val private: Boolean, url: String) {
        var texture: TextureSource = NoTexture
        var page = PageState("", url, loading = true, canGoBack = false, canGoForward = false)
        val prompts = ArrayDeque<PromptEvent>()
    }

    private val tabs = mutableListOf<Tab>()
    private var active: Tab? = null
    private var nextTab = 1
    private val pageMenu = PageMenu(menuHost, standalone = false)

    override var onChange: (() -> Unit)? = null

    /** For the shell to give the window the screen's size while the page is full screen. */
    var onFullScreenChange: ((Boolean) -> Unit)? = null

    /** While the page is full screen the toolbar is gone and the page has the whole window. */
    var fullScreen = false
        private set

    /** When the toolbar's state machine asked to be looked at again, in [System.nanoTime] milliseconds; 0 for never. */
    private var settleAtMs = 0L

    private val autoHide = ToolbarAutoHide(
        enabled = autoHideToolbar,
        clock = ::nowMs,
        schedule = { delayMs -> settleAtMs = nowMs() + delayMs },
        apply = { updateBand(keepPageStill = true) },
    )

    private fun nowMs() = System.nanoTime() / 1_000_000

    private fun band() = BrowserBand.heightPx(tabs.size, active?.prompts?.isNotEmpty() == true, fullScreen, autoHide.hidden)

    /** Opens the window's band and its first tab; [panel] already has the window's size. */
    fun start(chromeIndex: Int, url: String, private: Boolean) {
        panel.layer = PanelLayer(NoTexture, chromeIndex, BrowserBand.TOOLBAR_PX)
        claim(chromeIndex)
        newTab(url, private)
    }

    val chromeIndex: Int? get() = panel.layer?.producerIndex

    /** Runs the toolbar's state machine when it asked to be. Every frame. */
    fun tick() {
        if (settleAtMs != 0L && nowMs() >= settleAtMs) {
            settleAtMs = 0L
            autoHide.settle()
        }
    }

    /** The wearer moved the page themselves, with the wheel or a press; [pressed] while a button is down on it. */
    fun contentInput(pressed: Boolean? = null) {
        if (pressed != null) autoHide.touching = pressed
        autoHide.input()
    }

    fun settingsChanged() {
        if (autoHide.settingsChanged()) updateBand(keepPageStill = !autoHide.nearTop)
    }

    /** A frame of one of this window's pages arrived, or its surfaces changed size. */
    fun texture(index: Int, texture: TextureSource) {
        if (index == chromeIndex) {
            panel.layer?.texture = texture
            return
        }
        val tab = tabOf(index) ?: return
        tab.texture = texture
        if (tab === active) panel.texture = texture
    }

    private fun tabOf(index: Int) = tabs.firstOrNull { it.producer == index }

    fun page(index: Int, reported: PageState) {
        val tab = tabOf(index) ?: return
        val previous = tab.page
        // CEF reports a page before its first address, as empty; the band takes an empty URL for a new tab and focuses its field.
        val page = if (reported.url.isEmpty()) reported.copy(url = previous.url) else reported
        tab.page = page
        if (!tab.private && page.url.isNotEmpty()) {
            if (page.url != previous.url) history.visited(page.url, page.title.ifBlank { null })
            else if (page.title != previous.title && page.title.isNotBlank()) history.titled(page.url, page.title)
        }
        onChange?.invoke()
    }

    /** A page asked for a link in a new window: a new tab of this one, as Android's browser opens it. */
    fun popup(index: Int, url: String, background: Boolean) {
        val tab = tabOf(index) ?: return
        openTab(url, tab.private, select = !background)
    }

    fun scrolled(index: Int, y: Float) {
        if (tabOf(index) === active) autoHide.onScroll(y.toInt())
    }

    fun prompt(index: Int, prompt: PromptEvent) {
        val tab = tabOf(index) ?: return
        if (prompt.gone) tab.prompts.removeAll { it.id == prompt.id } else tab.prompts.addLast(prompt)
        if (tab === active) updateBand(keepPageStill = false)
        onChange?.invoke()
    }

    override fun answerPrompt(id: Int, allow: Boolean, remember: Boolean) {
        val tab = tabs.firstOrNull { tab -> tab.prompts.any { it.id == id } } ?: throw HaloneException("no-such-prompt", "no such prompt: $id")
        tab.prompts.removeAll { it.id == id }
        host.answerPrompt(tab.producer, id, allow, remember)
        if (tab === active) updateBand(keepPageStill = false)
        onChange?.invoke()
    }

    fun fullScreen(index: Int, full: Boolean) {
        if (tabOf(index) !== active || full == fullScreen) return
        fullScreen = full
        updateBand(keepPageStill = false)
        onFullScreenChange?.invoke(full)
    }

    /** The page's context menu, at ([target].x, [target].y) on the page, as Android's PageMenu shows it. */
    fun contextMenu(index: Int, target: ContextTarget) {
        val tab = tabOf(index) ?: return
        val pageTarget = PageTarget(
            link = target.link,
            image = target.source?.takeIf { target.media == "image" },
            media = target.source?.takeIf { target.media == "video" || target.media == "audio" },
            mediaKind = target.media,
            editable = target.editable,
            selection = target.selection.ifEmpty { null },
        )
        pageMenu.show(menuPage(tab), pageTarget, target.x, target.y + panel.bandPx)
    }

    private fun menuPage(tab: Tab) = object : PageMenu.Page {
        override val url get() = tab.page.url.ifEmpty { null }
        override val canGoBack get() = tab.page.canGoBack
        override val canGoForward get() = tab.page.canGoForward
        override val private get() = tab.private
        override val open get() = tab in tabs
        override fun newTab(url: String, private: Boolean) = openTab(url, private, select = true)
        override fun back() = host.command(tab.producer, ProducerHost.COMMAND_BACK)
        override fun forward() = host.command(tab.producer, ProducerHost.COMMAND_FORWARD)
        override fun reload() = host.command(tab.producer, ProducerHost.COMMAND_RELOAD)
        override fun edit(command: String, text: String?) = when (command) {
            "cut" -> host.command(tab.producer, ProducerHost.COMMAND_CUT)
            "paste" -> host.command(tab.producer, ProducerHost.COMMAND_PASTE)
            "selectAll" -> host.command(tab.producer, ProducerHost.COMMAND_SELECT_ALL)
            else -> Unit
        }
    }

    /** Downloads [url] in the active tab's page, as a link it asked for would be. */
    fun download(url: String) {
        active?.let { host.command(it.producer, ProducerHost.COMMAND_DOWNLOAD, url) }
    }

    val activePrivate: Boolean get() = active?.private ?: false

    override fun state(): BrowserState {
        val current = active
        return BrowserState(
            tabs = tabs.map { BrowserTab(it.id, it.page.title, it.page.url, it.page.loading, it.private) },
            activeTab = current?.id ?: 0,
            canGoBack = current?.page?.canGoBack ?: false,
            canGoForward = current?.page?.canGoForward ?: false,
            loading = current?.page?.loading ?: false,
            prompt = current?.prompts?.firstOrNull()?.let { BrowserPrompt(it.id, it.kind, it.host, it.rememberable) },
        )
    }

    override fun navigate(url: String) {
        val tab = active ?: return
        host.command(tab.producer, ProducerHost.COMMAND_NAVIGATE, url)
        panel.keysToLayer = false
    }

    override fun back() = command(ProducerHost.COMMAND_BACK)
    override fun forward() = command(ProducerHost.COMMAND_FORWARD)
    override fun reload() = command(ProducerHost.COMMAND_RELOAD)
    override fun stop() = command(ProducerHost.COMMAND_STOP)

    private fun command(kind: Int) {
        active?.let { host.command(it.producer, kind) }
    }

    override fun newTab(url: String?, private: Boolean) = openTab(url ?: BLANK, private, select = true)

    private fun openTab(url: String, private: Boolean, select: Boolean) {
        val id = nextTab++
        val index = host.open(panel.widthPx, panel.heightPx - BrowserBand.heightPx(tabs.size + 1, false, fullScreen, autoHide.hidden),
            url, private = private)
        val tab = Tab(id, index, private, url)
        claim(index)
        tabs += tab
        if (select || active == null) show(tab)
        updateBand(keepPageStill = false)
        onChange?.invoke()
    }

    override fun selectTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: throw HaloneException("no-such-tab", "no such tab: $id")
        show(tab)
        updateBand(keepPageStill = false)
        onChange?.invoke()
    }

    override fun closeTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: throw HaloneException("no-such-tab", "no such tab: $id")
        if (tabs.size == 1) throw HaloneException("last-tab", "closing the last tab is closing the window")
        val at = tabs.indexOf(tab)
        tabs.remove(tab)
        host.close(tab.producer, frame())
        release(tab.producer)
        if (tab === active) show(tabs[minOf(at, tabs.size - 1)])
        updateBand(keepPageStill = false)
        onChange?.invoke()
    }

    private fun show(tab: Tab) {
        active = tab
        panel.producerIndex = tab.producer
        panel.texture = tab.texture
    }

    /** The window took a new size: the band spans it, and every tab gets what the band leaves. */
    fun resized(widthPx: Int, heightPx: Int) {
        chromeIndex?.let { host.resize(it, widthPx, chromeHeightPx) }
        tabs.forEach { host.resize(it.producer, widthPx, heightPx - panel.bandPx) }
    }

    /**
     * Gives the pages whatever height the band leaves. When the toolbar goes
     * or comes back mid-page, the page is scrolled by the same amount so what
     * the wearer was reading stays where it was; at the top of the page, and
     * when the tab row comes or goes, it simply reflows.
     */
    private fun updateBand(keepPageStill: Boolean) {
        val next = band()
        val layer = panel.layer ?: return
        val previous = layer.visibleHeightPx
        if (next == previous) return
        layer.visibleHeightPx = next
        autoHide.bandChanged()
        tabs.forEach { host.resize(it.producer, panel.widthPx, panel.heightPx - next) }
        val tab = active
        if (tab != null && keepPageStill && (next < previous || !autoHide.nearTop)) {
            host.command(tab.producer, ProducerHost.COMMAND_SCROLL_BY, (next - previous).toString())
        }
    }

    /** Lets go of every page the window has, when it closes. */
    fun close() {
        tabs.forEach {
            host.close(it.producer, frame())
            release(it.producer)
        }
        tabs.clear()
        chromeIndex?.let {
            host.close(it, frame())
            release(it)
        }
    }

    private companion object {
        const val BLANK = "about:blank"
    }
}

/** A panel's picture before the producer's first frame: nothing to draw yet. */
internal object NoTexture : TextureSource {
    override val view = 0L
    override val widthPx = 1
    override val heightPx = 1
    override val ready = false
    override fun close() = Unit
}
