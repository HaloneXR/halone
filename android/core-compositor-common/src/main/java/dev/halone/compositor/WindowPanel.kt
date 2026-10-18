package dev.halone.compositor

/** What the shell keeps on a window's panel, whichever renderer draws it. */
interface WindowPanel {
    /** Shown in the title bar; the shell keeps it current. */
    var title: String

    /** A short label beside the title, such as "web", or null for none. */
    var tag: String?

    /** Part of the window holding a stereo picture, each eye shown its own half; null for none. */
    var stereoRegion: StereoRegion?

    /** Lays the content out at a new size, which the panel then reports as its own. */
    fun resizeContent(widthPx: Int, heightPx: Int)
}

/** In the window's own pixels, y down. [layout] is never [StereoLayout.MONO]. */
data class StereoRegion(val layout: StereoLayout, val xPx: Float, val yPx: Float, val widthPx: Float, val heightPx: Float)

/** How a frame holds the two eyes' pictures; the left eye is on the left or on top. */
enum class StereoLayout(val wire: String) {
    MONO("mono"),
    SIDE_BY_SIDE("side-by-side"),
    TOP_BOTTOM("top-bottom"),
}

enum class MediaProjection(val wire: String) {
    FLAT("flat"),
    EQUIRECT_180("equirect-180"),
    EQUIRECT_360("equirect-360"),
}
