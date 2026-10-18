package dev.halone.desktop.compositor.vk

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkSubmitInfo
import java.nio.ByteBuffer

/** Something a panel can be drawn from: a sampled image view, ready in SHADER_READ_ONLY_OPTIMAL. */
interface TextureSource : AutoCloseable {
    val view: Long
    val widthPx: Int
    val heightPx: Int
    val ready: Boolean get() = true
}

/** A device-local BGRA image filled once from CPU pixels. */
class StaticTexture(private val vk: VulkanContext, override val widthPx: Int, override val heightPx: Int, pixels: ByteBuffer) : TextureSource {
    private val image: Long
    private val memory: Long
    override val view: Long

    init {
        val (img, mem) = createImage(vk, widthPx, heightPx, VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT)
        image = img
        memory = mem
        view = createView(vk, image)
        upload(pixels)
    }

    private fun upload(pixels: ByteBuffer) = stackPush().use { stack ->
        val size = widthPx.toLong() * heightPx * 4
        val bufferInfo = VkBufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
            .size(size).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        val pBuffer = stack.mallocLong(1)
        vkCheck(vkCreateBuffer(vk.device, bufferInfo, null, pBuffer), "vkCreateBuffer")
        val buffer = pBuffer.get(0)
        val reqs = VkMemoryRequirements.malloc(stack)
        vkGetBufferMemoryRequirements(vk.device, buffer, reqs)
        val staging = allocate(vk, reqs, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        vkBindBufferMemory(vk.device, buffer, staging, 0)
        val pData = stack.mallocPointer(1)
        vkMapMemory(vk.device, staging, 0, size, 0, pData)
        MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), pData.get(0), size)
        vkUnmapMemory(vk.device, staging)

        oneShot(vk) { cmd ->
            FrameLoop.transition(cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT)
            val region = VkBufferImageCopy.calloc(1, stack)
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)
            region.imageExtent().set(widthPx, heightPx, 1)
            vkCmdCopyBufferToImage(cmd, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region)
            FrameLoop.transition(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
        }
        vkDestroyBuffer(vk.device, buffer, null)
        vkFreeMemory(vk.device, staging, null)
    }

    override fun close() {
        vkDestroyImageView(vk.device, view, null)
        vkDestroyImage(vk.device, image, null)
        vkFreeMemory(vk.device, memory, null)
    }

    companion object {
        fun createImage(vk: VulkanContext, width: Int, height: Int, usage: Int): Pair<Long, Long> = stackPush().use { stack ->
            val info = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_B8G8R8A8_UNORM)
                .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            info.extent().set(width, height, 1)
            val pImage = stack.mallocLong(1)
            vkCheck(vkCreateImage(vk.device, info, null, pImage), "vkCreateImage")
            val image = pImage.get(0)
            val reqs = VkMemoryRequirements.malloc(stack)
            vkGetImageMemoryRequirements(vk.device, image, reqs)
            val memory = allocate(vk, reqs, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
            vkBindImageMemory(vk.device, image, memory, 0)
            image to memory
        }

        fun createView(vk: VulkanContext, image: Long, format: Int = VK_FORMAT_B8G8R8A8_UNORM): Long = stackPush().use { stack ->
            val info = VkImageViewCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
            info.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)
            val pView = stack.mallocLong(1)
            vkCheck(vkCreateImageView(vk.device, info, null, pView), "vkCreateImageView")
            pView.get(0)
        }

        fun allocate(vk: VulkanContext, reqs: VkMemoryRequirements, properties: Int): Long = stackPush().use { stack ->
            val memProps = VkPhysicalDeviceMemoryProperties.malloc(stack)
            vkGetPhysicalDeviceMemoryProperties(vk.physicalDevice, memProps)
            val type = (0 until memProps.memoryTypeCount()).first {
                reqs.memoryTypeBits() and (1 shl it) != 0 && memProps.memoryTypes(it).propertyFlags() and properties == properties
            }
            val info = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size()).memoryTypeIndex(type)
            val pMemory = stack.mallocLong(1)
            vkCheck(vkAllocateMemory(vk.device, info, null, pMemory), "vkAllocateMemory")
            pMemory.get(0)
        }

        fun oneShot(vk: VulkanContext, record: (VkCommandBuffer) -> Unit) = stackPush().use { stack ->
            val alloc = VkCommandBufferAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(vk.commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1)
            val pCmd = stack.mallocPointer(1)
            vkAllocateCommandBuffers(vk.device, alloc, pCmd)
            val cmd = VkCommandBuffer(pCmd.get(0), vk.device)
            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
            record(cmd)
            vkEndCommandBuffer(cmd)
            vkQueueSubmit(vk.queue, VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(pCmd), VK_NULL_HANDLE)
            vkQueueWaitIdle(vk.queue)
            vkFreeCommandBuffers(vk.device, vk.commandPool, pCmd)
        }
    }
}
