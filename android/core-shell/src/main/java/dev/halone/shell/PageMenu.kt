package dev.halone.shell

/** What a context menu on a web page is over; [mediaKind] is `audio`, or anything else for video. */
data class PageTarget(
    val link: String? = null,
    val image: String? = null,
    val media: String? = null,
    val mediaKind: String? = null,
    val editable: Boolean = false,
    val selection: String? = null,
)

/** What a page's context menu needs from the shell around it. */
interface PageMenuHost {
    /** Shows a menu over the window, answering with the item chosen or null; returns its id, or null if it could not show. */
    fun showMenu(items: List<MenuItem>, xPx: Float, yPx: Float, onChosen: (String?) -> Unit): Int?
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
 * The context menu over a web page: a link's, an image's or a video's
 * actions, editing in a field, the selection, and otherwise the page's own.
 * In an installed app's window ([standalone]) there are no tabs, so what
 * would open one opens in the browser.
 */
class PageMenu(private val host: PageMenuHost, private val standalone: Boolean) {
    /** The tab the menu is over, which may be gone by the time something is chosen. */
    interface Page {
        val url: String?
        val canGoBack: Boolean
        val canGoForward: Boolean
        val private: Boolean
        val open: Boolean
        fun newTab(url: String, private: Boolean)
        fun back()
        fun forward()
        fun reload()

        /** `cut`, `paste` with [text], or `selectAll`, in the focused field. */
        fun edit(command: String, text: String? = null)
    }

    /** Shows the menu at ([x], [y]) on the window, for [target] on [page]. */
    fun show(page: Page, target: PageTarget, x: Float, y: Float) {
        val link = target.link
        val image = target.image
        val media = target.media
        val mediaLabel = if (target.mediaKind == "audio") "audio" else "video"
        val selection = target.selection
        val items = mutableListOf<MenuItem>()
        fun section(vararg entries: MenuItem?) {
            if (items.isNotEmpty()) items += MenuItem.SEPARATOR
            items += entries.filterNotNull()
        }
        if (link != null) section(
            MenuItem("open-tab", if (standalone) "Open link in browser" else "Open link in new tab"),
            MenuItem("open-private", "Open link in private tab"),
            if (standalone) null else MenuItem("open-window", "Open link in new window"),
            MenuItem("copy-link", "Copy link address"),
            MenuItem("download-link", "Download link"),
        )
        if (image != null) section(
            MenuItem("open-image", if (standalone) "Open image in browser" else "Open image in new tab"),
            MenuItem("save-image", "Save image"),
            MenuItem("copy-image", "Copy image address"),
        )
        if (media != null && !media.startsWith("blob:")) section(
            MenuItem("save-media", "Save $mediaLabel"),
            MenuItem("copy-media", "Copy $mediaLabel address"),
        )
        if (target.editable) section(
            MenuItem("cut", "Cut", disabled = selection == null),
            MenuItem("copy", "Copy", disabled = selection == null),
            MenuItem("paste", "Paste", disabled = host.clipboardText().isNullOrEmpty()),
            MenuItem("select-all", "Select all"),
        ) else if (selection != null) section(
            MenuItem("copy", "Copy"),
            MenuItem("search", "Search for “${selection.trim().replace(Regex("\\s+"), " ").let { if (it.length > 24) it.take(24) + "…" else it }}”"),
        )
        val pageUrl = page.url
        if (items.isEmpty()) {
            section(
                MenuItem("back", "Back", disabled = !page.canGoBack),
                MenuItem("forward", "Forward", disabled = !page.canGoForward),
                MenuItem("reload", "Reload"),
            )
            if (standalone) section(
                MenuItem("open-page", "Open in browser", disabled = pageUrl == null),
                MenuItem("copy-page", "Copy page address", disabled = pageUrl == null),
            ) else section(
                MenuItem("bookmark", if (pageUrl != null && host.isBookmarked(pageUrl)) "Remove bookmark" else "Bookmark page", disabled = pageUrl == null),
                MenuItem("copy-page", "Copy page address", disabled = pageUrl == null),
            )
        }
        fun open(url: String, private: Boolean = page.private) =
            if (standalone) host.openInBrowser(url, private) else page.newTab(url, private)
        host.showMenu(items, x, y) { choice ->
            if (choice == null || !page.open) return@showMenu
            when (choice) {
                "open-tab" -> link?.let { open(it) }
                "open-private" -> link?.let { open(it, private = true) }
                "open-window" -> link?.let { host.openWindow(it, page.private) }
                "copy-link" -> link?.let(host::setClipboardText)
                "download-link" -> link?.let { host.download(it, pageUrl, page.private) }
                "open-image" -> image?.let { open(it) }
                "save-image" -> image?.let { host.download(it, pageUrl, page.private) }
                "copy-image" -> image?.let(host::setClipboardText)
                "save-media" -> media?.let { host.download(it, pageUrl, page.private) }
                "copy-media" -> media?.let(host::setClipboardText)
                "cut" -> selection?.let {
                    host.setClipboardText(it)
                    page.edit("cut")
                }
                "copy" -> selection?.let(host::setClipboardText)
                "paste" -> host.clipboardText()?.let { page.edit("paste", it) }
                "select-all" -> page.edit("selectAll")
                "search" -> selection?.let { open(host.searchUrl(it.trim())) }
                "back" -> page.back()
                "forward" -> page.forward()
                "reload" -> page.reload()
                "bookmark" -> host.toggleBookmark()
                "open-page" -> pageUrl?.let { host.openInBrowser(it, false) }
                "copy-page" -> pageUrl?.let(host::setClipboardText)
            }
        }
    }
}

/** A downloaded file's name made safe for any file system, as the browser saves it. */
object DownloadNames {
    private const val MAX_NAME = 200

    fun sanitise(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(MAX_NAME).ifBlank { "download" }
}

/** The search engines the browser setting names, and where each takes a search. */
object SearchEngines {
    fun url(engine: String, text: String): String {
        val query = java.net.URLEncoder.encode(text, Charsets.UTF_8)
        return when (engine) {
            "google" -> "https://www.google.com/search?q=$query"
            "bing" -> "https://www.bing.com/search?q=$query"
            else -> "https://duckduckgo.com/?q=$query"
        }
    }
}
