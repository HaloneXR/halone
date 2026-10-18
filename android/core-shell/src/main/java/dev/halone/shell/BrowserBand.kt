package dev.halone.shell

/**
 * The band the browser page draws across the top of a web window, in its
 * pixels: the toolbar, the tab row with more than one tab, then a page's
 * permission prompt. The page below gets what the band leaves.
 */
object BrowserBand {
    const val TOOLBAR_PX = 76
    const val TABS_PX = 52
    const val PROMPT_PX = 64

    fun heightPx(tabs: Int, prompting: Boolean, fullScreen: Boolean = false, hiddenByScroll: Boolean = false): Int = when {
        fullScreen -> 0
        hiddenByScroll && !prompting -> 0
        else -> TOOLBAR_PX + (if (tabs > 1) TABS_PX else 0) + (if (prompting) PROMPT_PX else 0)
    }
}
