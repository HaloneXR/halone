package dev.halone.app.pip

import android.content.SharedPreferences
import android.graphics.RectF
import kotlin.math.min

/**
 * Where the mirrored phone screen sits on the glasses. On each axis the
 * window keeps to the edge it is nearest, [right] or left and [bottom] or
 * top, [x] and [y] away from it, so a window in a corner or against an edge
 * stays there when the phone turns. [size] is the window's longer side; it
 * and both gaps are shares of the display's shorter side.
 */
data class PipFrame(
    val right: Boolean = true,
    val x: Float = 0f,
    val bottom: Boolean = false,
    val y: Float = 0f,
    val size: Float = 0.5f,
) {

    /** The window for content [aspect] wide per unit of height, in an area [width] by [height]. */
    fun place(width: Float, height: Float, aspect: Float): RectF {
        val area = Area(width, height)
        val side = size * area.short
        var w = if (aspect >= 1f) side else side * aspect
        var h = if (aspect >= 1f) side / aspect else side
        val fit = min(1f, min(area.roomWidth / w, area.roomHeight / h))
        w *= fit
        h *= fit
        val freeWidth = area.roomWidth - w
        val freeHeight = area.roomHeight - h
        val gapX = (x * area.short).coerceIn(0f, freeWidth)
        val gapY = (y * area.short).coerceIn(0f, freeHeight)
        val left = area.margin + if (right) freeWidth - gapX else gapX
        val top = area.margin + if (bottom) freeHeight - gapY else gapY
        return RectF(left, top, left + w, top + h)
    }

    fun moved(dx: Float, dy: Float, width: Float, height: Float, aspect: Float): PipFrame {
        val area = Area(width, height)
        val window = place(width, height, aspect)
        val freeWidth = area.roomWidth - window.width()
        val freeHeight = area.roomHeight - window.height()
        val fromLeft = (window.left - area.margin + dx).coerceIn(0f, freeWidth)
        val fromTop = (window.top - area.margin + dy).coerceIn(0f, freeHeight)
        val fromRight = freeWidth - fromLeft
        val fromBottom = freeHeight - fromTop
        return copy(
            right = fromRight < fromLeft,
            x = min(fromLeft, fromRight) / area.short,
            bottom = fromBottom < fromTop,
            y = min(fromTop, fromBottom) / area.short,
        )
    }

    fun scaled(factor: Float, width: Float, height: Float, aspect: Float): PipFrame {
        val area = Area(width, height)
        val largest = if (aspect >= 1f) min(area.roomWidth, area.roomHeight * aspect) else min(area.roomHeight, area.roomWidth / aspect)
        return copy(size = (size * factor).coerceIn(MIN_SIZE, largest / area.short))
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_RIGHT, right)
            .putFloat(KEY_X, x)
            .putBoolean(KEY_BOTTOM, bottom)
            .putFloat(KEY_Y, y)
            .putFloat(KEY_SIZE, size)
            .apply()
    }

    private class Area(width: Float, height: Float) {
        val short = min(width, height)
        val margin = MARGIN * short
        val roomWidth = width - 2 * margin
        val roomHeight = height - 2 * margin
    }

    companion object {
        /** Kept clear along every edge, where the glasses' optics blur. */
        const val MARGIN = 0.04f
        const val MIN_SIZE = 0.15f
        private const val KEY_RIGHT = "pip-right"
        private const val KEY_X = "pip-gap-x"
        private const val KEY_BOTTOM = "pip-bottom"
        private const val KEY_Y = "pip-gap-y"
        private const val KEY_SIZE = "pip-size"

        fun load(prefs: SharedPreferences): PipFrame {
            val default = PipFrame()
            return PipFrame(
                prefs.getBoolean(KEY_RIGHT, default.right),
                prefs.getFloat(KEY_X, default.x),
                prefs.getBoolean(KEY_BOTTOM, default.bottom),
                prefs.getFloat(KEY_Y, default.y),
                prefs.getFloat(KEY_SIZE, default.size),
            )
        }
    }
}
