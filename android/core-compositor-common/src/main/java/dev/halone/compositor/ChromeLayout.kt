package dev.halone.compositor

import dev.halone.glasses.math.Vec3

/** What a window's chrome looks like and where its parts go, for every renderer. */
object ChromeLayout {
    const val FRAME_GAP_PX = 4f
    const val FRAME_THICKNESS_PX = 3f

    /** Chrome of a window that is neither focused nor held. */
    const val UNFOCUSED_CHROME = 0.3f

    const val INACTIVE_TITLE_BAR = 0.55f

    /** A resize handle shown because the pointer is near, rather than on it. */
    const val HANDLE_NEAR_ALPHA = 0.55f

    const val PREVIEW_THICKNESS_PX = 4f

    /** How much nearer than its panel the cursor and the resize preview are drawn, as a fraction of the distance. */
    const val CURSOR_STANDOFF_FRACTION = 0.004f

    /** The ring cursor's size, in its panel's pixels. */
    const val RING_PX = 26f

    /** The arrow's picture, which leaves room around the arrow itself, in panel pixels. */
    const val ARROW_PX = 40f

    /** The ring in the arrow theme's colours, Material Bibata Beige's outline and body: the glasses show the rim alone. */
    const val RING_RIM_ARGB = 0xFFF5E6CA.toInt()
    const val RING_BODY_ARGB = 0xFF4A4232.toInt()

    /**
     * How much nearer a held window is drawn. It has to clear the ~8 cm of
     * depth a panel's own width spans at 4 m; at 15 cm the vergence change is
     * under 0.04 degrees, and being drawn whole rather than sliced is what
     * makes it look picked up.
     */
    const val GRAB_LIFT_METRES = 0.15f

    val frameMarginPx: Float get() = FRAME_GAP_PX + FRAME_THICKNESS_PX + 1f

    data class Rgb(val r: Float, val g: Float, val b: Float)

    /**
     * A held window gets its own colour rather than a brighter one: while
     * dragging, the wearer needs to know which window is coming with them at a
     * glance, and pinned-or-following is the state they are about to change.
     */
    fun frameColor(grabbed: Boolean, following: Boolean): Rgb = when {
        grabbed -> Rgb(1f, 1f, 1f)
        following -> Rgb(1f, 0.72f, 0.2f)
        else -> Rgb(0.25f, 0.8f, 1f)
    }

    fun frameAlpha(focused: Boolean, grabbed: Boolean, opacity: Float) =
        (if (focused || grabbed) 1f else UNFOCUSED_CHROME) * opacity

    fun titleBarAlpha(active: Boolean, opacity: Float) = (if (active) 1f else INACTIVE_TITLE_BAR) * opacity

    fun <P : ScenePanel> drawDistance(scene: PanelScene<P>, panel: P): Float =
        panel.pose.distanceMetres - if (panel === scene.grabbed) GRAB_LIFT_METRES else 0f

    /** A resize handle: which corner, and whether the pointer is on it or only near it. */
    data class Handle(val corner: Corner, val onHandle: Boolean)

    /**
     * The handle to show on [panel], if any: the corner the pointer is on, or
     * the one it is near unless it is on another window's content. None while
     * anything is being moved or resized.
     */
    fun <P : ScenePanel> handle(scene: PanelScene<P>, panel: P, lastHit: PanelSceneHit<P>?, aim: Vec3?): Handle? {
        if (scene.resizing != null || scene.grabbed != null || scene.isAnimatingResize(panel)) return null
        val onHandle = lastHit?.takeIf { it.panel === panel }?.part?.corner
        if (onHandle != null) return Handle(onHandle, true)
        if (lastHit != null && lastHit.panel !== panel && lastHit.part == HitPart.CONTENT) return null
        val point = aim?.let { scene.pointOnPanel(panel, it) } ?: return null
        val corner = ResizeHandles.cornerNear(panel.widthPx, panel.heightPx, point.x, point.y) ?: return null
        return Handle(corner, false)
    }

    /** Where a handle's quad goes, in its panel's pixels (y down), and how large it is. */
    data class HandleQuad(val centreX: Float, val centreY: Float, val sizePx: Float, val outsidePx: Float, val insidePx: Float)

    fun handleQuad(widthPx: Float, heightPx: Float, cornerRadiusPx: Float, corner: Corner): HandleQuad {
        val outside = ResizeHandles.GAP_PX + ResizeHandles.THICKNESS_PX + 2f
        val inside = cornerRadiusPx + ResizeHandles.EXTEND_PX
        val toCentre = (inside - outside) / 2f
        val point = corner.pointPx(widthPx, heightPx)
        return HandleQuad(point.x - corner.sx * toCentre, point.y + corner.sy * toCentre, inside + outside, outside, inside)
    }
}
