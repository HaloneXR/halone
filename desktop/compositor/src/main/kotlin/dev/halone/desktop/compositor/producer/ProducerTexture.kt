package dev.halone.desktop.compositor.producer

import dev.halone.desktop.compositor.vk.FrameLoop
import dev.halone.desktop.compositor.vk.StaticTexture
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.compositor.vk.VulkanContext
import org.lwjgl.vulkan.VK13.*

/** A producer's ring of surfaces, each imported once as a sampled Vulkan image; [view] is the newest frame's. */
class ProducerTexture(private val vk: VulkanContext, val surfaces: ProducerSurfaces) : TextureSource {
    override val widthPx = surfaces.width
    override val heightPx = surfaces.height
    private val images = LongArray(surfaces.count)
    private val memories = LongArray(surfaces.count)
    private val views = LongArray(surfaces.count)

    /** The surface the panel shows; -1 until the producer's first frame. */
    var current = -1

    override val view: Long get() = views[current.coerceAtLeast(0)]

    override val ready: Boolean get() = current >= 0

    init {
        for (i in 0 until surfaces.count) {
            val (image, memory) = surfaces.import(vk, i)
            images[i] = image
            memories[i] = memory
            views[i] = StaticTexture.createView(vk, image)
        }
        StaticTexture.oneShot(vk) { cmd ->
            for (image in images) {
                FrameLoop.transition(cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
            }
        }
    }

    override fun close() {
        for (i in images.indices) {
            vkDestroyImageView(vk.device, views[i], null)
            vkDestroyImage(vk.device, images[i], null)
            vkFreeMemory(vk.device, memories[i], null)
        }
        surfaces.release()
    }
}
