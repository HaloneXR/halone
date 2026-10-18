package dev.halone.desktop.compositor.shell

import dev.halone.desktop.compositor.producer.PageState
import dev.halone.desktop.compositor.producer.ProducerHost
import dev.halone.desktop.compositor.render.DesktopPanel
import dev.halone.desktop.compositor.render.PanelLayer
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.shell.BrowserBand
import dev.halone.shell.BrowserControl
import dev.halone.shell.BrowserHistory
import dev.halone.shell.BrowserState
import dev.halone.shell.BrowserTab
import dev.halone.shell.HaloneException

/**
 * A web window, as BrowserEngine is on Android: the browser page (a system
 * app, with the bridge) in a band across the top, and below it the tabs, each
 * a page of the producer's, the active one shown. The window keeps its size;
 * the tabs' pages get what the band leaves.
 */
class DesktopBrowser(
    private val host: ProducerHost,
    val panel: DesktopPanel,
    private val chromeHeightPx: Int,
    private val history: BrowserHistory,
    private val frame: () -> Long,
    /** Called for each producer panel this window opens, so its frames and pages find their way back here. */
    private val claim: (index: Int) -> Unit,
    private val release: (index: Int) -> Unit,
) : BrowserControl {
    class Tab(val id: Int, val producer: Int, val private: Boolean, url: String) {
        var texture: TextureSource = NoTexture
        var page = PageState("", url, loading = true, canGoBack = false, canGoForward = false)
    }

    private val tabs = mutableListOf<Tab>()
    private var active: Tab? = null
    private var nextTab = 1

    override var onChange: (() -> Unit)? = null

    private val band get() = BrowserBand.heightPx(tabs.size, prompting = false)

    /** Opens the window's band and its first tab; [panel] already has the window's size. */
    fun start(chromeIndex: Int, url: String, private: Boolean) {
        panel.layer = PanelLayer(NoTexture, chromeIndex, BrowserBand.TOOLBAR_PX)
        claim(chromeIndex)
        newTab(url, private)
    }

    val chromeIndex: Int? get() = panel.layer?.producerIndex

    /** A frame of one of this window's pages arrived, or its surfaces changed size. */
    fun texture(index: Int, texture: TextureSource) {
        if (index == chromeIndex) {
            panel.layer?.texture = texture
            return
        }
        val tab = tabs.firstOrNull { it.producer == index } ?: return
        tab.texture = texture
        if (tab === active) panel.texture = texture
    }

    fun page(index: Int, reported: PageState) {
        val tab = tabs.firstOrNull { it.producer == index } ?: return
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
        val tab = tabs.firstOrNull { it.producer == index } ?: return
        openTab(url, tab.private, select = !background)
    }

    override fun state(): BrowserState {
        val current = active
        return BrowserState(
            tabs = tabs.map { BrowserTab(it.id, it.page.title, it.page.url, it.page.loading, it.private) },
            activeTab = current?.id ?: 0,
            canGoBack = current?.page?.canGoBack ?: false,
            canGoForward = current?.page?.canGoForward ?: false,
            loading = current?.page?.loading ?: false,
            prompt = null,
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
        val before = band
        val index = host.open(panel.widthPx, panel.heightPx - BrowserBand.heightPx(tabs.size + 1, prompting = false), url, private = private)
        claim(index)
        val tab = Tab(nextTab++, index, private, url)
        tabs += tab
        if (select || active == null) show(tab)
        bandChanged(before)
        onChange?.invoke()
    }

    override fun selectTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: throw HaloneException("no-such-tab", "no such tab: $id")
        show(tab)
        onChange?.invoke()
    }

    override fun closeTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: throw HaloneException("no-such-tab", "no such tab: $id")
        if (tabs.size == 1) throw HaloneException("last-tab", "closing the last tab is closing the window")
        val before = band
        val at = tabs.indexOf(tab)
        tabs.remove(tab)
        host.close(tab.producer, frame())
        release(tab.producer)
        if (tab === active) show(tabs[minOf(at, tabs.size - 1)])
        bandChanged(before)
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
        tabs.forEach { host.resize(it.producer, widthPx, heightPx - band) }
    }

    /** A tab came or went, and with it the tab row: the pages are laid out again in what is left. */
    private fun bandChanged(before: Int) {
        val now = band
        panel.layer?.visibleHeightPx = now
        if (now != before) tabs.forEach { host.resize(it.producer, panel.widthPx, panel.heightPx - now) }
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
