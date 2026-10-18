package dev.halone.compositor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.text.TextPaint
import dev.halone.compositor.NoticeLayout.HEIGHT_PX
import dev.halone.compositor.NoticeLayout.RADIUS_PX
import dev.halone.compositor.NoticeLayout.TEXT_MID_Y_PX
import dev.halone.compositor.NoticeLayout.TEXT_SIZE_PX
import dev.halone.compositor.NoticeLayout.WIDTH_PX

/**
 * Paints a [Notice]'s card into a texture, repainting only when the text
 * changes; the bar's fill is geometry, so it moves every frame without a
 * repaint. Render thread only.
 */
internal class NoticePainter {
    private var texture = 0
    private var paintedText: String? = null

    fun texture(text: String): Int {
        if (text == paintedText) return texture
        if (texture == 0) texture = newTexture()
        val bitmap = paint(text)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        bitmap.recycle()
        paintedText = text
        return texture
    }

    private fun newTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun paint(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap((WIDTH_PX * SCALE).toInt(), (HEIGHT_PX * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(SCALE, SCALE)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NoticeLayout.BACKGROUND_ARGB }
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH_PX, HEIGHT_PX), RADIUS_PX, RADIUS_PX, fill)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TEXT_SIZE_PX
            color = NoticeLayout.TEXT_ARGB
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val baseline = TEXT_MID_Y_PX - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, WIDTH_PX / 2f, baseline, textPaint)
        return bitmap
    }

    private companion object {
        const val SCALE = 2f
    }
}
