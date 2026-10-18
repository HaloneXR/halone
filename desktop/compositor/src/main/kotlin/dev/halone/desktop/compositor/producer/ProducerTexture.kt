package dev.halone.desktop.compositor.producer

import dev.halone.desktop.compositor.vk.FrameLoop
import dev.halone.desktop.compositor.vk.StaticTexture
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.compositor.vk.VulkanContext
import dev.halone.desktop.compositor.vk.vkCheck
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.EXTMetalObjects
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImportMetalIOSurfaceInfoEXT
import org.lwjgl.vulkan.VkMemoryRequirements

/** A producer's ring of IOSurfaces, each imported once as a sampled Vulkan image; [view] is the newest frame's. */
class ProducerTexture(
    private val vk: VulkanContext,
    override val widthPx: Int,
    override val heightPx: Int,
    val surfaces: LongArray,
) : TextureSource {
    private val images = LongArray(surfaces.size)
    private val memories = LongArray(surfaces.size)
    private val views = LongArray(surfaces.size)

    /** The surface the panel shows; -1 until the producer's first frame. */
    var current = -1

    override val view: Long get() = views[current.coerceAtLeast(0)]

    override val ready: Boolean get() = current >= 0

    init {
        check(vk.hasMetalObjects) { "importing IOSurfaces needs VK_EXT_metal_objects" }
        for (i in surfaces.indices) {
            stackPush().use { stack ->
                val import = VkImportMetalIOSurfaceInfoEXT.calloc(stack)
                    .sType(EXTMetalObjects.VK_STRUCTURE_TYPE_IMPORT_METAL_IO_SURFACE_INFO_EXT)
                    .ioSurface(surfaces[i])
                val info = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .pNext(import.address())
                    .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_B8G8R8A8_UNORM)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                info.extent().set(widthPx, heightPx, 1)
                val pImage = stack.mallocLong(1)
                vkCheck(vkCreateImage(vk.device, info, null, pImage), "vkCreateImage (IOSurface)")
                images[i] = pImage.get(0)
                val reqs = VkMemoryRequirements.malloc(stack)
                vkGetImageMemoryRequirements(vk.device, images[i], reqs)
                memories[i] = StaticTexture.allocate(vk, reqs, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
                vkBindImageMemory(vk.device, images[i], memories[i], 0)
                views[i] = StaticTexture.createView(vk, images[i])
            }
        }
        StaticTexture.oneShot(vk) { cmd ->
            for (image in images) {
                FrameLoop.transition(cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
            }
        }
    }

    override fun close() {
        for (i in surfaces.indices) {
            vkDestroyImageView(vk.device, views[i], null)
            vkDestroyImage(vk.device, images[i], null)
            vkFreeMemory(vk.device, memories[i], null)
            Bridge.native.halone_surface_release(surfaces[i])
        }
    }
}
