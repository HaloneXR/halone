package dev.halone.compositor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils

/** The cursor's textures, made on the GL thread: the ring, and the arrow's shapes from [images] as they change. */
internal class CursorTextures {
    private var uploaded: Map<CursorShape, CursorImage>? = null
    private val textures = HashMap<CursorShape, Int>()
    private var ring = 0

    /** The ring for the gyro pointer and gaze, centred on its hotspot. */
    fun ring(): Int {
        if (ring == 0) {
            val bitmap = paintRing()
            ring = upload(bitmap)
            bitmap.recycle()
        }
        return ring
    }

    /** The texture for [shape], or for the default arrow when [images] lacks it; null with no images at all. */
    fun arrow(images: Map<CursorShape, CursorImage>, shape: CursorShape): Pair<Int, CursorImage>? {
        if (images !== uploaded) {
            textures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
            textures.clear()
            images.forEach { (key, image) -> textures[key] = upload(image.bitmap) }
            uploaded = images
        }
        val key = if (shape in textures) shape else CursorShape.DEFAULT
        val texture = textures[key] ?: return null
        return texture to images.getValue(key)
    }

    private fun upload(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        return ids[0]
    }

    private fun paintRing(): Bitmap {
        val bitmap = Bitmap.createBitmap(RING_PX, RING_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = RING_PX / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        paint.color = ChromeLayout.RING_BODY_ARGB
        paint.strokeWidth = RING_PX * 0.2f
        canvas.drawCircle(centre, centre, RING_PX * 0.3f, paint)
        paint.color = ChromeLayout.RING_RIM_ARGB
        paint.strokeWidth = RING_PX * 0.08f
        canvas.drawCircle(centre, centre, RING_PX * 0.4f, paint)
        canvas.drawCircle(centre, centre, RING_PX * 0.2f, paint)
        return bitmap
    }

    private companion object {
        const val RING_PX = 128
    }
}
