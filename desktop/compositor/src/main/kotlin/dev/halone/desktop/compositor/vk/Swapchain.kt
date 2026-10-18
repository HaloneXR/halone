package dev.halone.desktop.compositor.vk

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkExtent2D
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR
import org.lwjgl.vulkan.VkSurfaceFormatKHR
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR

/** The window's images. Rebuilt whenever the surface changes size. */
class Swapchain(private val vk: VulkanContext, width: Int, height: Int, old: Swapchain? = null) : AutoCloseable {
    val handle: Long
    val format: Int
    val width: Int
    val height: Int
    val images: LongArray
    val views: LongArray

    init {
        stackPush().use { stack ->
            val caps = VkSurfaceCapabilitiesKHR.calloc(stack)
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(vk.physicalDevice, vk.surface, caps)

            val pCount = stack.mallocInt(1)
            vkGetPhysicalDeviceSurfaceFormatsKHR(vk.physicalDevice, vk.surface, pCount, null)
            val formats = VkSurfaceFormatKHR.malloc(pCount.get(0), stack)
            vkGetPhysicalDeviceSurfaceFormatsKHR(vk.physicalDevice, vk.surface, pCount, formats)
            val chosen = (0 until formats.capacity()).map { formats[it] }
                .firstOrNull { it.format() == VK_FORMAT_B8G8R8A8_UNORM } ?: formats[0]
            format = chosen.format()

            val extent: VkExtent2D = caps.currentExtent()
            this.width = if (extent.width() == -1) width else extent.width()
            this.height = if (extent.height() == -1) height else extent.height()

            var imageCount = caps.minImageCount() + 1
            if (caps.maxImageCount() in 1 until imageCount) imageCount = caps.maxImageCount()

            val info = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(vk.surface)
                .minImageCount(imageCount)
                .imageFormat(format)
                .imageColorSpace(chosen.colorSpace())
                .imageExtent { it.width(this.width).height(this.height) }
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(caps.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .presentMode(VK_PRESENT_MODE_FIFO_KHR)
                .clipped(true)
                .oldSwapchain(old?.handle ?: VK_NULL_HANDLE)
            val pSwapchain = stack.mallocLong(1)
            vkCheck(vkCreateSwapchainKHR(vk.device, info, null, pSwapchain), "vkCreateSwapchainKHR")
            handle = pSwapchain.get(0)

            vkGetSwapchainImagesKHR(vk.device, handle, pCount, null)
            val pImages = stack.mallocLong(pCount.get(0))
            vkGetSwapchainImagesKHR(vk.device, handle, pCount, pImages)
            images = LongArray(pCount.get(0)) { pImages.get(it) }
            views = LongArray(images.size) { createView(images[it]) }
        }
    }

    private fun createView(image: Long): Long = stackPush().use { stack ->
        val info = VkImageViewCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .image(image)
            .viewType(VK_IMAGE_VIEW_TYPE_2D)
            .format(format)
        info.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)
        val pView = stack.mallocLong(1)
        vkCheck(vkCreateImageView(vk.device, info, null, pView), "vkCreateImageView")
        pView.get(0)
    }

    override fun close() {
        views.forEach { vkDestroyImageView(vk.device, it, null) }
        vkDestroySwapchainKHR(vk.device, handle, null)
    }
}
