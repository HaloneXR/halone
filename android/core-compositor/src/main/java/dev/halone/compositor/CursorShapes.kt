package dev.halone.compositor

import android.content.Context
import android.graphics.Bitmap
import android.view.PointerIcon

/** The [CursorShape] each Android pointer icon stands for. */
object CursorShapes {
    private val pointerIconTypes = mapOf(
        CursorShape.DEFAULT to PointerIcon.TYPE_ARROW,
        CursorShape.POINTER to PointerIcon.TYPE_HAND,
        CursorShape.TEXT to PointerIcon.TYPE_TEXT,
        CursorShape.VERTICAL_TEXT to PointerIcon.TYPE_VERTICAL_TEXT,
        CursorShape.CROSSHAIR to PointerIcon.TYPE_CROSSHAIR,
        CursorShape.GRAB to PointerIcon.TYPE_GRAB,
        CursorShape.GRABBING to PointerIcon.TYPE_GRABBING,
        CursorShape.NOT_ALLOWED to PointerIcon.TYPE_NO_DROP,
        CursorShape.ALL_SCROLL to PointerIcon.TYPE_ALL_SCROLL,
        CursorShape.EW_RESIZE to PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,
        CursorShape.NS_RESIZE to PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW,
        CursorShape.NWSE_RESIZE to PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW,
        CursorShape.NESW_RESIZE to PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW,
        CursorShape.HELP to PointerIcon.TYPE_HELP,
        CursorShape.WAIT to PointerIcon.TYPE_WAIT,
        CursorShape.ZOOM_IN to PointerIcon.TYPE_ZOOM_IN,
        CursorShape.ZOOM_OUT to PointerIcon.TYPE_ZOOM_OUT,
        CursorShape.CONTEXT_MENU to PointerIcon.TYPE_CONTEXT_MENU,
        CursorShape.COPY to PointerIcon.TYPE_COPY,
        CursorShape.ALIAS to PointerIcon.TYPE_ALIAS,
        CursorShape.CELL to PointerIcon.TYPE_CELL,
        CursorShape.NONE to PointerIcon.TYPE_NULL,
    )

    private var systemIcons: Map<CursorShape, PointerIcon>? = null

    /**
     * The shape a view asked for with `setPointerIcon`, as WebView and
     * GeckoView do for the CSS cursor. [PointerIcon] keeps its type to
     * itself, but a system icon equals another of the same type.
     */
    fun of(context: Context, icon: PointerIcon?): CursorShape {
        icon ?: return CursorShape.DEFAULT
        val icons = systemIcons ?: pointerIconTypes.mapValues { PointerIcon.getSystemIcon(context, it.value) }.also { systemIcons = it }
        return icons.entries.firstOrNull { it.value == icon }?.key ?: CursorShape.DEFAULT
    }
}

/** A cursor picture with its hotspot, as fractions of its width and height from the top left. */
class CursorImage(val bitmap: Bitmap, val hotspotX: Float, val hotspotY: Float)
