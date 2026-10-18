package dev.halone.desktop.compositor.render

import dev.halone.compositor.NoticeLayout
import dev.halone.desktop.compositor.vk.StaticTexture
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.compositor.vk.VulkanContext
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The notice card, painted as NoticePainter does on Android and repainted only
 * when the text changes; the bar is drawn as geometry over it.
 */
class NoticePainter(private val vk: VulkanContext) {
    private var painted: StaticTexture? = null
    private var paintedText: String? = null
    private val retired = ArrayDeque<Pair<StaticTexture, Long>>()

    fun texture(text: String, frame: Long, forget: (TextureSource) -> Unit): TextureSource {
        painted?.let { if (text == paintedText) return it }
        painted?.let { retired.addLast(it to frame) }
        val texture = StaticTexture(vk, (NoticeLayout.WIDTH_PX * SCALE).toInt(), (NoticeLayout.HEIGHT_PX * SCALE).toInt(), paint(text), mipmapped = true)
        painted = texture
        paintedText = text
        release(frame, forget)
        return texture
    }

    fun release(frame: Long, forget: (TextureSource) -> Unit) {
        while (retired.isNotEmpty() && frame - retired.first().second > KEEP_FRAMES) {
            val texture = retired.removeFirst().first
            forget(texture)
            texture.close()
        }
    }

    fun close() {
        painted?.close()
        retired.forEach { it.first.close() }
    }

    private fun paint(text: String): ByteBuffer {
        val image = BufferedImage((NoticeLayout.WIDTH_PX * SCALE).toInt(), (NoticeLayout.HEIGHT_PX * SCALE).toInt(), BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.scale(SCALE.toDouble(), SCALE.toDouble())
        g.color = Color(NoticeLayout.BACKGROUND_ARGB, true)
        val diameter = 2f * NoticeLayout.RADIUS_PX
        g.fill(RoundRectangle2D.Float(0f, 0f, NoticeLayout.WIDTH_PX, NoticeLayout.HEIGHT_PX, diameter, diameter))
        g.color = Color(NoticeLayout.TEXT_ARGB, true)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(NoticeLayout.TEXT_SIZE_PX)
        val metrics = g.fontMetrics
        val baseline = NoticeLayout.TEXT_MID_Y_PX + (metrics.ascent - metrics.descent) / 2f
        g.drawString(text, (NoticeLayout.WIDTH_PX - metrics.stringWidth(text)) / 2f, baseline)
        g.dispose()
        val ints = (image.raster.dataBuffer as DataBufferInt).data
        return ByteBuffer.allocateDirect(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asIntBuffer().put(ints) }
    }

    private companion object {
        const val SCALE = 2f
        const val KEEP_FRAMES = 4
    }
}
