package dev.halone.desktop.compositor.render

import dev.halone.compositor.HitPart
import dev.halone.compositor.PanelRect
import dev.halone.compositor.TitleBar
import dev.halone.desktop.compositor.vk.StaticTexture
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.compositor.vk.VulkanContext
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The title bar under each window, painted the way TitleBarPainter does on
 * Android: same layout, colours and hand-drawn icons, at twice the panel's
 * pixels and mipmapped. Repainted only when what it shows changes.
 */
class TitleBarPainter(private val vk: VulkanContext) {
    private data class Look(
        val title: String,
        val tag: String?,
        val pinned: Boolean,
        val back: Boolean,
        val hovered: HitPart?,
        val widthPx: Int,
        val heightPx: Int,
    )

    private class Painted(val texture: StaticTexture, val look: Look)

    private val painted = HashMap<String, Painted>()

    /** Textures replaced or forgotten, kept until no frame in flight can still be drawing them. */
    private val retired = ArrayDeque<Pair<StaticTexture, Long>>()

    fun texture(panel: DesktopPanel, hovered: HitPart?, frame: Long, forget: (TextureSource) -> Unit): TextureSource {
        val look = Look(
            title = panel.title,
            tag = panel.tag,
            pinned = !panel.follow.enabled,
            back = panel.backButton,
            hovered = hovered?.takeIf { it == HitPart.PIN || it == HitPart.CLOSE || it == HitPart.BACK },
            widthPx = panel.widthPx,
            heightPx = panel.heightPx,
        )
        painted[panel.id]?.let { if (it.look == look) return it.texture }
        val bar = TitleBar.bar(panel.widthPx, panel.heightPx)
        val texture = StaticTexture(vk, (bar.width * SCALE).toInt(), (bar.height * SCALE).toInt(), paint(look, bar, panel), mipmapped = true)
        painted.put(panel.id, Painted(texture, look))?.let { retire(it.texture, frame) }
        release(frame, forget)
        return texture
    }

    /** Retires the bars of windows that are gone. */
    fun keepOnly(liveIds: Set<String>, frame: Long, forget: (TextureSource) -> Unit) {
        painted.keys.filter { it !in liveIds }.forEach { retire(painted.remove(it)!!.texture, frame) }
        release(frame, forget)
    }

    private fun retire(texture: StaticTexture, frame: Long) {
        retired.addLast(texture to frame)
    }

    private fun release(frame: Long, forget: (TextureSource) -> Unit) {
        while (retired.isNotEmpty() && frame - retired.first().second > KEEP_FRAMES) {
            val texture = retired.removeFirst().first
            forget(texture)
            texture.close()
        }
    }

    fun close() {
        painted.values.forEach { it.texture.close() }
        retired.forEach { it.first.close() }
    }

    private fun paint(look: Look, bar: PanelRect, panel: DesktopPanel): ByteBuffer {
        val image = BufferedImage((bar.width * SCALE).toInt(), (bar.height * SCALE).toInt(), BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.scale(SCALE.toDouble(), SCALE.toDouble())

        g.color = BACKGROUND
        g.fill(RoundRectangle2D.Float(0f, 0f, bar.width, bar.height, bar.height, bar.height))

        val midY = bar.height / 2f
        g.color = MUTED
        val gripX = TitleBar.PADDING_START_PX
        for (column in 0..1) for (row in -1..1) circle(g, gripX + column * 7f, midY + row * 7f, 2f, fill = true)

        val pin = TitleBar.pin(panel.widthPx, panel.heightPx).relativeTo(bar)
        val close = TitleBar.close(panel.widthPx, panel.heightPx).relativeTo(bar)
        var textEnd = pin.left - GAP_PX

        look.tag?.let { tag ->
            g.font = font(15f)
            val width = g.fontMetrics.stringWidth(tag) + 20f
            val left = textEnd - width
            g.color = BORDER
            g.stroke = BasicStroke(1f)
            g.draw(RoundRectangle2D.Float(left, midY - 13f, width, 26f, 20f, 20f))
            g.color = MUTED
            g.drawString(tag, left + 10f, baseline(g, midY))
            textEnd = left - GAP_PX
        }

        g.font = font(20f)
        g.color = TEXT
        val back = TitleBar.back(panel.widthPx, panel.heightPx).relativeTo(bar)
        val titleStart = if (look.back) back.right + GAP_PX / 2f else gripX + 7f + 2f + GAP_PX
        g.drawString(ellipsize(g, look.title, (textEnd - titleStart).coerceAtLeast(0f)), titleStart, baseline(g, midY))

        if (look.back) {
            button(g, back, active = false, hovered = look.hovered == HitPart.BACK)
            drawBack(g, back, iconColor(look.hovered == HitPart.BACK))
        }
        button(g, pin, active = look.pinned, hovered = look.hovered == HitPart.PIN)
        drawPin(g, pin, filled = look.pinned, color = iconColor(look.pinned || look.hovered == HitPart.PIN))
        button(g, close, active = false, hovered = look.hovered == HitPart.CLOSE)
        drawClose(g, close, iconColor(look.hovered == HitPart.CLOSE))
        g.dispose()

        val ints = (image.raster.dataBuffer as DataBufferInt).data
        return ByteBuffer.allocateDirect(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asIntBuffer().put(ints) }
    }

    private fun button(g: Graphics2D, rect: PanelRect, active: Boolean, hovered: Boolean) {
        if (!active && !hovered) return
        g.color = HOVER
        circle(g, rect.left + rect.width / 2f, rect.top + rect.height / 2f, rect.width / 2f, fill = true)
    }

    private fun drawPin(g: Graphics2D, rect: PanelRect, filled: Boolean, color: Color) {
        val cx = rect.left + rect.width / 2f
        val cy = rect.top + rect.height / 2f
        g.color = color
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER)
        if (filled) circle(g, cx, cy - 4f, 5f, fill = true)
        circle(g, cx, cy - 4f, 5f, fill = false)
        g.draw(Line2D.Float(cx, cy + 1f, cx, cy + 10f))
    }

    private fun drawBack(g: Graphics2D, rect: PanelRect, color: Color) {
        val cx = rect.left + rect.width / 2f
        val cy = rect.top + rect.height / 2f
        g.color = color
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Path2D.Float().apply {
            moveTo(cx + 7f, cy); lineTo(cx - 7f, cy)
            moveTo(cx - 1f, cy - 6f); lineTo(cx - 7f, cy); lineTo(cx - 1f, cy + 6f)
        })
    }

    private fun drawClose(g: Graphics2D, rect: PanelRect, color: Color) {
        val cx = rect.left + rect.width / 2f
        val cy = rect.top + rect.height / 2f
        g.color = color
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER)
        g.draw(Path2D.Float().apply {
            moveTo(cx - 6f, cy - 6f); lineTo(cx + 6f, cy + 6f)
            moveTo(cx + 6f, cy - 6f); lineTo(cx - 6f, cy + 6f)
        })
    }

    private fun circle(g: Graphics2D, cx: Float, cy: Float, r: Float, fill: Boolean) {
        val shape = Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r)
        if (fill) g.fill(shape) else g.draw(shape)
    }

    private fun iconColor(strong: Boolean) = if (strong) TEXT else Color(232, 238, 247, 179)

    private fun font(sizePx: Float) = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(sizePx)

    private fun baseline(g: Graphics2D, midY: Float): Float {
        val metrics = g.fontMetrics
        return midY + (metrics.ascent - metrics.descent) / 2f
    }

    private fun ellipsize(g: Graphics2D, text: String, maxWidth: Float): String {
        val metrics = g.fontMetrics
        if (metrics.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + ELLIPSIS) > maxWidth) end--
        return if (end == 0) "" else text.substring(0, end) + ELLIPSIS
    }

    private fun PanelRect.relativeTo(bar: PanelRect) = PanelRect(left - bar.left, top - bar.top, width, height)

    private companion object {
        const val SCALE = 2f
        const val GAP_PX = 14f
        const val KEEP_FRAMES = 4
        const val ELLIPSIS = "…"
        val BACKGROUND = Color(22, 28, 38, 235)
        val TEXT = Color(232, 238, 247)
        val MUTED = Color(139, 152, 171)
        val BORDER = Color(42, 52, 68)
        val HOVER = Color(29, 37, 51)
    }
}
