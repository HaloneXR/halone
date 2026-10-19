package dev.halone.app.panel

import dev.halone.shell.PageTarget
import org.json.JSONObject
import dev.halone.shell.PageMenu as SharedPageMenu

/**
 * The context menu over a web page in Gecko: asks the page's helper what is
 * there and shows the shared menu for it. A page that shows its own menu
 * there gets the event instead, as with a right click on a desktop.
 */
class PageMenu(
    private val content: GeckoPanel,
    host: BrowserHost,
    standalone: Boolean,
) {
    private val menu = SharedPageMenu(host, standalone)

    /** Shows the menu at ([x], [y]) on the window, over what is at [pageY] on the page. */
    fun show(x: Float, y: Float, pageY: Float) {
        val tab = content.active
        content.inspect(x, pageY) { info ->
            if (content.active !== tab || content.tabs.none { it === tab }) return@inspect
            if (info?.optBoolean("handled") == true) return@inspect
            val found = info ?: JSONObject()
            val target = PageTarget(
                link = found.text("link"),
                image = found.text("image"),
                media = found.text("media"),
                mediaKind = found.optString("mediaKind"),
                editable = found.optBoolean("editable"),
                selection = found.text("selection"),
            )
            menu.show(page(tab), target, x, y)
        }
    }

    private fun JSONObject.text(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) && it.isNotEmpty() }

    private fun page(tab: GeckoPanel.Tab) = object : SharedPageMenu.Page {
        override val url get() = tab.url
        override val canGoBack get() = tab.canGoBack
        override val canGoForward get() = tab.canGoForward
        override val private get() = tab.private
        override val open get() = content.tabs.any { it === tab }
        override fun newTab(url: String, private: Boolean) {
            content.newTab(url, private)
        }
        override fun back() = content.back()
        override fun forward() = content.forward()
        override fun reload() = content.reload()
        override fun edit(command: String, text: String?) = content.edit(command, text)
    }
}
