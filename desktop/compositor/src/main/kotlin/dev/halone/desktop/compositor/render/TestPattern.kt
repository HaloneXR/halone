package dev.halone.desktop.compositor.render

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Panel content with something to judge alignment and motion by: a grid, a label and the corners marked. */
object TestPattern {
    fun pixels(width: Int, height: Int, label: String, hue: Float): ByteBuffer {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, Color.getHSBColor(hue, 0.6f, 0.35f), width.toFloat(), height.toFloat(), Color.getHSBColor(hue + 0.15f, 0.6f, 0.15f))
        g.fillRect(0, 0, width, height)
        g.color = Color(255, 255, 255, 40)
        for (x in 0 until width step 64) g.drawLine(x, 0, x, height)
        for (y in 0 until height step 64) g.drawLine(0, y, width, y)
        g.color = Color.WHITE
        g.stroke = BasicStroke(6f)
        g.drawRect(3, 3, width - 6, height - 6)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, height / 8)
        val metrics = g.fontMetrics
        g.drawString(label, (width - metrics.stringWidth(label)) / 2, height / 2 + metrics.ascent / 3)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, height / 24)
        g.drawString("${width}x$height", 24, height - 24)
        g.dispose()
        return bytesOf(image)
    }

    /** The ring cursor, as CursorTextures paints it on Android: a dark body between two light rims. */
    fun cursor(size: Int): ByteBuffer {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val centre = size / 2.0
        fun ring(radius: Double, width: Float, color: Color) {
            g.color = color
            g.stroke = BasicStroke(width)
            g.draw(java.awt.geom.Ellipse2D.Double(centre - radius, centre - radius, 2 * radius, 2 * radius))
        }
        ring(size * 0.3, size * 0.2f, Color(0x4A, 0x42, 0x32))
        ring(size * 0.4, size * 0.08f, Color(0xF5, 0xE6, 0xCA))
        ring(size * 0.2, size * 0.08f, Color(0xF5, 0xE6, 0xCA))
        g.dispose()
        return bytesOf(image)
    }

    private fun bytesOf(image: BufferedImage): ByteBuffer {
        val ints = (image.raster.dataBuffer as DataBufferInt).data
        val bytes = ByteBuffer.allocateDirect(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asIntBuffer().put(ints)
        return bytes
    }
}
