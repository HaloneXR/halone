package dev.halone.desktop.compositor.vk

import org.lwjgl.glfw.GLFW.glfwGetFramebufferSize
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkFenceCreateInfo
import org.lwjgl.vulkan.VkImageMemoryBarrier2
import org.lwjgl.vulkan.VkDependencyInfo
import org.lwjgl.vulkan.VkPresentInfoKHR
import org.lwjgl.vulkan.VkSemaphoreCreateInfo
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo
import org.lwjgl.vulkan.VkSubmitInfo2

/** What a frame records into: the command buffer and the swapchain image it targets. */
class FrameTarget(val commands: VkCommandBuffer, val image: Long, val view: Long, val width: Int, val height: Int, val format: Int)

/**
 * Acquire, record, submit, present, with [FRAMES_IN_FLIGHT] frames overlapping.
 * The caller records into a target already in COLOR_ATTACHMENT_OPTIMAL and
 * leaves it there; the loop moves it to and from the presentable layout.
 */
class FrameLoop(private val vk: VulkanContext, private val window: Long) : AutoCloseable {
    var swapchain: Swapchain
        private set
    private val commandBuffers: Array<VkCommandBuffer>
    private val imageAvailable = LongArray(FRAMES_IN_FLIGHT)
    private val inFlight = LongArray(FRAMES_IN_FLIGHT)
    private var renderFinished = LongArray(0)
    private var frame = 0
    private var resized = false

    init {
        swapchain = Swapchain(vk, framebufferSize().first, framebufferSize().second)
        stackPush().use { stack ->
            val alloc = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(vk.commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(FRAMES_IN_FLIGHT)
            val pBuffers = stack.mallocPointer(FRAMES_IN_FLIGHT)
            vkCheck(vkAllocateCommandBuffers(vk.device, alloc, pBuffers), "vkAllocateCommandBuffers")
            commandBuffers = Array(FRAMES_IN_FLIGHT) { VkCommandBuffer(pBuffers.get(it), vk.device) }
            for (i in 0 until FRAMES_IN_FLIGHT) {
                imageAvailable[i] = semaphore()
                inFlight[i] = fence(signalled = true)
            }
        }
        renderFinished = LongArray(swapchain.images.size) { semaphore() }
    }

    fun onResize() {
        resized = true
    }

    /** Renders one frame; returns false when there was nothing to draw into (minimised). */
    fun frame(record: (FrameTarget) -> Unit): Boolean = stackPush().use { stack ->
        val (fbWidth, fbHeight) = framebufferSize()
        if (fbWidth == 0 || fbHeight == 0) return false

        vkWaitForFences(vk.device, inFlight[frame], true, Long.MAX_VALUE)
        val pIndex = stack.mallocInt(1)
        val acquired = vkAcquireNextImageKHR(vk.device, swapchain.handle, Long.MAX_VALUE, imageAvailable[frame], VK_NULL_HANDLE, pIndex)
        if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
            rebuild()
            return false
        }
        val index = pIndex.get(0)
        vkResetFences(vk.device, inFlight[frame])

        val cmd = commandBuffers[frame]
        vkResetCommandBuffer(cmd, 0)
        vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
        val image = swapchain.images[index]
        transition(cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, 0,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
        record(FrameTarget(cmd, image, swapchain.views[index], swapchain.width, swapchain.height, swapchain.format))
        transition(cmd, image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_2_NONE, 0)
        vkCheck(vkEndCommandBuffer(cmd), "vkEndCommandBuffer")

        val wait = VkSemaphoreSubmitInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
            .semaphore(imageAvailable[frame]).stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
        val signal = VkSemaphoreSubmitInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
            .semaphore(renderFinished[index]).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
        val cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO).commandBuffer(cmd)
        val submit = VkSubmitInfo2.calloc(1, stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2)
            .pWaitSemaphoreInfos(wait).pSignalSemaphoreInfos(signal).pCommandBufferInfos(cmdInfo)
        vkCheck(vkQueueSubmit2(vk.queue, submit, inFlight[frame]), "vkQueueSubmit2")

        val present = VkPresentInfoKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
            .pWaitSemaphores(stack.longs(renderFinished[index]))
            .swapchainCount(1)
            .pSwapchains(stack.longs(swapchain.handle))
            .pImageIndices(stack.ints(index))
        val presented = vkQueuePresentKHR(vk.queue, present)
        frame = (frame + 1) % FRAMES_IN_FLIGHT
        if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR || resized) rebuild()
        true
    }

    private fun rebuild() {
        resized = false
        vkDeviceWaitIdle(vk.device)
        val (w, h) = framebufferSize()
        if (w == 0 || h == 0) return
        val old = swapchain
        swapchain = Swapchain(vk, w, h, old)
        old.close()
        renderFinished.forEach { vkDestroySemaphore(vk.device, it, null) }
        renderFinished = LongArray(swapchain.images.size) { semaphore() }
    }

    private fun framebufferSize(): Pair<Int, Int> = stackPush().use { stack ->
        val w = stack.mallocInt(1)
        val h = stack.mallocInt(1)
        glfwGetFramebufferSize(window, w, h)
        w.get(0) to h.get(0)
    }

    private fun semaphore(): Long = stackPush().use { stack ->
        val p = stack.mallocLong(1)
        vkCheck(vkCreateSemaphore(vk.device, VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO), null, p), "vkCreateSemaphore")
        p.get(0)
    }

    private fun fence(signalled: Boolean): Long = stackPush().use { stack ->
        val info = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
        if (signalled) info.flags(VK_FENCE_CREATE_SIGNALED_BIT)
        val p = stack.mallocLong(1)
        vkCheck(vkCreateFence(vk.device, info, null, p), "vkCreateFence")
        p.get(0)
    }

    override fun close() {
        vkDeviceWaitIdle(vk.device)
        imageAvailable.forEach { vkDestroySemaphore(vk.device, it, null) }
        renderFinished.forEach { vkDestroySemaphore(vk.device, it, null) }
        inFlight.forEach { vkDestroyFence(vk.device, it, null) }
        swapchain.close()
    }

    companion object {
        const val FRAMES_IN_FLIGHT = 2

        fun transition(
            cmd: VkCommandBuffer, image: Long, from: Int, to: Int,
            srcStage: Long, srcAccess: Long, dstStage: Long, dstAccess: Long,
        ) = stackPush().use { stack ->
            val barrier = VkImageMemoryBarrier2.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                .srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess)
                .oldLayout(from).newLayout(to)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)
            vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO).pImageMemoryBarriers(barrier))
        }
    }
}
