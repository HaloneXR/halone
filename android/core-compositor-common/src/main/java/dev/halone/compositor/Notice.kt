package dev.halone.compositor

/** A short message held in front of the wearer, with a bar showing how far along it is. */
data class Notice(val text: String, val progress: Float)

/** The notice card's layout and colours, in its own pixels, for every renderer. */
object NoticeLayout {
    const val WIDTH_PX = 560f
    const val HEIGHT_PX = 120f
    const val BAR_INSET_PX = 40f
    const val BAR_MID_Y_PX = 86f
    const val BAR_HEIGHT_PX = 8f
    const val TEXT_MID_Y_PX = 46f
    const val TEXT_SIZE_PX = 26f
    const val RADIUS_PX = 24f

    /** The card's height for each metre of distance: about 3.4°, a sixth of the view. */
    const val HEIGHT_PER_METRE = 0.06f

    const val BACKGROUND_ARGB = 0xEB161C26.toInt()
    const val TEXT_ARGB = 0xFFE8EEF7.toInt()

    /** The bar's track, premultiplied. */
    val TRACK = floatArrayOf(0.18f, 0.18f, 0.18f, 0.18f)
    val FILL = floatArrayOf(0.36f, 0.62f, 1f, 1f)

    const val BAR_WIDTH_PX = WIDTH_PX - 2f * BAR_INSET_PX

    fun metresPerPx(distanceMetres: Float) = distanceMetres * HEIGHT_PER_METRE / HEIGHT_PX

    fun filledPx(notice: Notice) = BAR_WIDTH_PX * notice.progress.coerceIn(0f, 1f)
}
