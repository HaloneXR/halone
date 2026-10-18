package dev.halone.desktop.compositor.shell

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The network meter's icons, painted as masks the dock tints as it tints
 * plugins' icons: a Wi-Fi fan with its bars filled to the signal and the rest
 * faint, a wired plug, or the fan struck through when offline.
 */
object NetworkIcons {
    private const val SIZE = 48
    private const val FAINT = 0.3f

    fun png(state: NetworkState): ByteArray {
        val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        when (state.kind) {
            NetworkState.Kind.WIFI -> wifi(g, state.bars)
            NetworkState.Kind.WIRED -> wired(g)
            NetworkState.Kind.OFFLINE -> {
                wifi(g, 0)
                g.color = Color.WHITE
                g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Float(8f, 8f, 40f, 40f))
            }
        }
        g.dispose()
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    /** Four nested wedges of a fan opening upward from its point near the bottom. */
    private fun wifi(g: java.awt.Graphics2D, bars: Int) {
        val cx = SIZE / 2.0
        val cy = SIZE * 0.84
        val band = SIZE * 0.155
        for (bar in 4 downTo 1) {
            val radius = band * (bar + 0.3)
            g.color = Color(1f, 1f, 1f, if (bar <= bars) 1f else FAINT)
            if (bar == 1) {
                g.fill(Arc2D.Double(cx - radius, cy - radius, 2 * radius, 2 * radius, 45.0, 90.0, Arc2D.PIE))
            } else {
                val inner = radius - band + 2.5
                val stroke = radius - inner
                val mid = (radius + inner) / 2
                g.stroke = BasicStroke(stroke.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
                g.draw(Arc2D.Double(cx - mid, cy - mid, 2 * mid, 2 * mid, 45.0, 90.0, Arc2D.OPEN))
            }
        }
    }

    /** A plug: its body, two prongs above and the cable below. */
    private fun wired(g: java.awt.Graphics2D) {
        g.color = Color.WHITE
        g.fill(RoundRectangle2D.Float(13f, 16f, 22f, 16f, 6f, 6f))
        g.fill(RoundRectangle2D.Float(17f, 8f, 4f, 9f, 2f, 2f))
        g.fill(RoundRectangle2D.Float(27f, 8f, 4f, 9f, 2f, 2f))
        g.fill(RoundRectangle2D.Float(22f, 31f, 4f, 10f, 2f, 2f))
    }
}
