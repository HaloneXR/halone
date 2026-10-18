package dev.halone.desktop.compositor.render

import dev.halone.compositor.ChromeLayout
import dev.halone.compositor.CursorShape
import dev.halone.desktop.compositor.vk.StaticTexture
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.compositor.vk.VulkanContext
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** A cursor picture with its hotspot, as fractions of its width and height from the top left. */
class CursorPicture(val texture: TextureSource, val hotspotX: Float, val hotspotY: Float)

/**
 * The cursor's pictures, as CursorTextures makes them on Android: the ring,
 * and the arrow's shapes from the same `cursors` assets, bundled as resources.
 */
class CursorTextures(private val vk: VulkanContext) : AutoCloseable {
    val ring = CursorPicture(StaticTexture(vk, RING_PX, RING_PX, paintRing(), mipmapped = true), 0.5f, 0.5f)
    private val arrows: Map<CursorShape, CursorPicture> = loadArrows()

    /** The picture for [shape], or the default arrow's when there is none for it; null with no arrows at all. */
    fun arrow(shape: CursorShape): CursorPicture? = arrows[shape] ?: arrows[CursorShape.DEFAULT]

    private fun loadArrows(): Map<CursorShape, CursorPicture> {
        val json = javaClass.getResource("/$DIR/hotspots.json")?.readText() ?: return emptyMap()
        val hotspots = HOTSPOT.findAll(json).associate { it.groupValues[1] to (it.groupValues[2].toFloat() to it.groupValues[3].toFloat()) }
        return CursorShape.entries.mapNotNull { shape ->
            val (x, y) = hotspots[shape.css] ?: return@mapNotNull null
            val image = javaClass.getResourceAsStream("/$DIR/${shape.css}.png")?.use(ImageIO::read) ?: return@mapNotNull null
            shape to CursorPicture(StaticTexture(vk, image.width, image.height, premultiplied(image), mipmapped = true), x, y)
        }.toMap()
    }

    private fun premultiplied(source: BufferedImage): ByteBuffer {
        val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB_PRE)
        image.createGraphics().apply { drawImage(source, 0, 0, null); dispose() }
        return bytesOf(image)
    }

    private fun paintRing(): ByteBuffer {
        val image = BufferedImage(RING_PX, RING_PX, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val centre = RING_PX / 2.0
        fun circle(radius: Double, width: Float, argb: Int) {
            g.color = Color(argb, true)
            g.stroke = BasicStroke(width)
            g.draw(Ellipse2D.Double(centre - radius, centre - radius, 2 * radius, 2 * radius))
        }
        circle(RING_PX * 0.3, RING_PX * 0.2f, ChromeLayout.RING_BODY_ARGB)
        circle(RING_PX * 0.4, RING_PX * 0.08f, ChromeLayout.RING_RIM_ARGB)
        circle(RING_PX * 0.2, RING_PX * 0.08f, ChromeLayout.RING_RIM_ARGB)
        g.dispose()
        return bytesOf(image)
    }

    private fun bytesOf(image: BufferedImage): ByteBuffer {
        val ints = (image.raster.dataBuffer as DataBufferInt).data
        return ByteBuffer.allocateDirect(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asIntBuffer().put(ints) }
    }

    override fun close() {
        ring.texture.close()
        arrows.values.forEach { it.texture.close() }
    }

    private companion object {
        const val DIR = "cursors"
        const val RING_PX = 128
        val HOTSPOT = Regex(""""([a-z-]+)"\s*:\s*\[\s*([0-9.]+)\s*,\s*([0-9.]+)\s*]""")
    }
}
