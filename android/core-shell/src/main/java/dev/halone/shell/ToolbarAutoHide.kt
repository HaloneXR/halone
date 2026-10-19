package dev.halone.shell

/**
 * When a web window's toolbar hides as the wearer scrolls down a page and
 * comes back as they scroll up, deciding only once scrolling has come to rest.
 *
 * [clock] is in milliseconds. [schedule] asks for [settle] to be called after
 * the delay, replacing any earlier request. [apply] is told when the toolbar
 * should hide or come back, and lays the band out again.
 */
class ToolbarAutoHide(
    private val enabled: () -> Boolean,
    private val clock: () -> Long,
    private val schedule: (delayMs: Long) -> Unit,
    private val apply: (hidden: Boolean) -> Unit,
) {
    var hidden = false
        private set
    private var wantHidden = false

    var lastScrollY = 0
        private set

    /** A finger is on the page: no time to change its viewport. */
    var touching = false

    private var lastActivityAt = 0L

    /**
     * When the wearer last scrolled, and until when the page's own movement is
     * not theirs. Resizing the viewport and keeping the page still both scroll
     * it; read as the wearer scrolling, that brought the toolbar back, whose
     * own scroll hid it again, and so on for ever.
     */
    private var lastInputAt = 0L
    private var ignoreScrollUntil = 0L

    /** Near the top the toolbar always shows, and there is room to scroll by its height. */
    val nearTop: Boolean get() = lastScrollY <= NEAR_TOP_PX

    fun onScroll(scrollY: Int) {
        val delta = scrollY - lastScrollY
        lastScrollY = scrollY
        val now = clock()
        if (now < ignoreScrollUntil || now - lastInputAt > USER_SCROLL_MS) return
        wantHidden = when {
            !enabled() || scrollY <= NEAR_TOP_PX -> false
            delta > SCROLL_SLOP_PX -> true
            delta < -SCROLL_SLOP_PX -> false
            else -> wantHidden
        }
        activity()
    }

    /** The wearer moved the page themselves: a scroll, a touch or the wheel. */
    fun input() {
        lastInputAt = clock()
        activity()
    }

    private fun activity() {
        lastActivityAt = clock()
        schedule(SETTLE_MS)
    }

    /** Hides or shows the toolbar once scrolling has come to rest; changing the viewport under a finger moves the page under it. */
    fun settle() {
        if (touching || clock() - lastActivityAt < SETTLE_MS) return
        if (wantHidden == hidden) return
        hidden = wantHidden
        apply(hidden)
    }

    /** The band changed size, and the page moves on its own for a moment. */
    fun bandChanged() {
        ignoreScrollUntil = clock() + OWN_SCROLL_MS
    }

    /** Re-reads the setting; true when turning it off brought the toolbar straight back. */
    fun settingsChanged(): Boolean {
        if (enabled() || !hidden) return false
        hidden = false
        wantHidden = false
        return true
    }

    companion object {
        private const val SCROLL_SLOP_PX = 8
        const val NEAR_TOP_PX = BrowserBand.TOOLBAR_PX + BrowserBand.TABS_PX + 16

        /** How long scrolling has to be still before the toolbar hides or comes back. */
        const val SETTLE_MS = 150L

        /** How long after the wearer's last input the page moving is still theirs: a fling. */
        private const val USER_SCROLL_MS = 1_500L

        /** How long the page's own movement after a viewport change is ignored. */
        private const val OWN_SCROLL_MS = 600L
    }
}
