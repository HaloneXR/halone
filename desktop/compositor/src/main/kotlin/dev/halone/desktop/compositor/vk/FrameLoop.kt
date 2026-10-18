package dev.halone.desktop.compositor.vk

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR
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
import org.lwjgl.vulkan.VkClearValue
import org.lwjgl.vulkan.VkRect2D
import org.lwjgl.vulkan.VkRenderingAttachmentInfo
import org.lwjgl.vulkan.VkRenderingInfo
import org.lwjgl.vulkan.GOOGLEDisplayTiming
import org.lwjgl.vulkan.KHRPresentId
import org.lwjgl.vulkan.KHRPresentWait
import org.lwjgl.vulkan.VkPastPresentationTimingGOOGLE
import org.lwjgl.vulkan.VkPresentIdKHR
import org.lwjgl.vulkan.VkPresentTimeGOOGLE
import org.lwjgl.vulkan.VkPresentTimesInfoGOOGLE
import org.lwjgl.vulkan.VkRefreshCycleDurationGOOGLE

/** One frame as the presentation engine saw it. Times are in its clock, [submittedNs] in System.nanoTime. */
data class PresentRecord(
    val id: Long,
    val submittedNs: Long,
    val desiredNs: Long,
    val actualNs: Long,
    val earliestNs: Long,
    val marginNs: Long,
)

/** What a frame records into: the command buffer and the swapchain image it targets. */
class FrameTarget(val commands: VkCommandBuffer, val image: Long, val view: Long, val width: Int, val height: Int, val format: Int)

/**
 * Acquire, record, submit, present, with [FRAMES_IN_FLIGHT] frames overlapping.
 * The caller records into a target already in COLOR_ATTACHMENT_OPTIMAL and
 * leaves it there; the loop moves it to and from the presentable layout.
 */
class FrameLoop(private val vk: VulkanContext, private var images: Int? = null) : AutoCloseable {
    var swapchain: Swapchain
        private set
    private val commandBuffers: Array<VkCommandBuffer>
    private val primeBuffers: Array<VkCommandBuffer>
    private val imageAvailable = LongArray(FRAMES_IN_FLIGHT)
    private val inFlight = LongArray(FRAMES_IN_FLIGHT)
    private var renderFinished = LongArray(0)
    private var frame = 0
    private var resized = false

    init {
        swapchain = Swapchain(vk, framebufferSize().first, framebufferSize().second, requestedImages = images)
        stackPush().use { stack ->
            val alloc = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(vk.commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(FRAMES_IN_FLIGHT)
            val pBuffers = stack.mallocPointer(FRAMES_IN_FLIGHT)
            vkCheck(vkAllocateCommandBuffers(vk.device, alloc, pBuffers), "vkAllocateCommandBuffers")
            commandBuffers = Array(FRAMES_IN_FLIGHT) { VkCommandBuffer(pBuffers.get(it), vk.device) }
            vkCheck(vkAllocateCommandBuffers(vk.device, alloc, pBuffers), "vkAllocateCommandBuffers")
            primeBuffers = Array(FRAMES_IN_FLIGHT) { VkCommandBuffer(pBuffers.get(it), vk.device) }
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

    /**
     * Clears the acquired image in a submission of its own, before anything of
     * the frame is decided.
     *
     * MoltenVK only takes the Metal drawable when a submission first needs the
     * image, and blocks there until one is free: about 12 ms a frame on the
     * glasses. Left in the frame's own submission, that wait falls between
     * sampling the head pose and the frame being queued, so every frame was
     * drawn 12 ms further from where the head would be. Taking it here moves
     * the wait before the sample.
     */
    private fun prime(image: Long, view: Long, available: Long) = stackPush().use { stack ->
        val start = System.nanoTime()
        val cmd = primeBuffers[frame]
        vkResetCommandBuffer(cmd, 0)
        vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
        transition(cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, 0,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
        val clear = VkClearValue.calloc(stack)
        clear.color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f)
        val attachment = VkRenderingAttachmentInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
            .imageView(view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE).clearValue(clear)
        val area = VkRect2D.calloc(stack)
        area.extent().set(swapchain.width, swapchain.height)
        vkCmdBeginRendering(cmd, VkRenderingInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
            .renderArea(area).layerCount(1).pColorAttachments(attachment))
        vkCmdEndRendering(cmd)
        vkCheck(vkEndCommandBuffer(cmd), "vkEndCommandBuffer")
        val wait = VkSemaphoreSubmitInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
            .semaphore(available).stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
        val cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO).commandBuffer(cmd)
        vkCheck(vkQueueSubmit2(vk.queue, VkSubmitInfo2.calloc(1, stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2)
            .pWaitSemaphoreInfos(wait).pCommandBufferInfos(cmdInfo), VK_NULL_HANDLE), "vkQueueSubmit2 (prime)")
        primeBlockNs += System.nanoTime() - start
    }

    /** Rebuilds the swapchain with [count] images, or the default when null. */
    fun setImages(count: Int?) {
        if (count == images) return
        images = count
        rebuild()
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
        if (acquired == VK_ERROR_SURFACE_LOST_KHR) {
            recoverSurface()
            return false
        }
        if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) vkCheck(acquired, "vkAcquireNextImageKHR")
        val index = pIndex.get(0)
        vkResetFences(vk.device, inFlight[frame])

        val image = swapchain.images[index]
        prime(image, swapchain.views[index], imageAvailable[frame])

        val cmd = commandBuffers[frame]
        vkResetCommandBuffer(cmd, 0)
        vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
        transition(cmd, image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
        record(FrameTarget(cmd, image, swapchain.views[index], swapchain.width, swapchain.height, swapchain.format))
        val recordedNs = System.nanoTime()
        transition(cmd, image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_2_NONE, 0)
        vkCheck(vkEndCommandBuffer(cmd), "vkEndCommandBuffer")

        val signal = VkSemaphoreSubmitInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
            .semaphore(renderFinished[index]).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
        val cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO).commandBuffer(cmd)
        val submit = VkSubmitInfo2.calloc(1, stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2)
            .pSignalSemaphoreInfos(signal).pCommandBufferInfos(cmdInfo)
        vkCheck(vkQueueSubmit2(vk.queue, submit, inFlight[frame]), "vkQueueSubmit2")
        val queuedNs = System.nanoTime()
        submitBlockNs += queuedNs - recordedNs

        val id = ++lastPresentId
        val present = VkPresentInfoKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
            .pWaitSemaphores(stack.longs(renderFinished[index]))
            .swapchainCount(1)
            .pSwapchains(stack.longs(swapchain.handle))
            .pImageIndices(stack.ints(index))
        var chain = 0L
        if (vk.hasPresentId) {
            chain = VkPresentIdKHR.calloc(stack).sType(KHRPresentId.VK_STRUCTURE_TYPE_PRESENT_ID_KHR)
                .swapchainCount(1).pPresentIds(stack.longs(id)).address()
        }
        if (vk.hasDisplayTiming) {
            val times = VkPresentTimeGOOGLE.calloc(1, stack).presentID(id.toInt()).desiredPresentTime(desiredPresentNs)
            chain = VkPresentTimesInfoGOOGLE.calloc(stack).sType(GOOGLEDisplayTiming.VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE)
                .pNext(chain).swapchainCount(1).pTimes(times).address()
        }
        present.pNext(chain)
        submitted[id] = System.nanoTime()
        val presented = vkQueuePresentKHR(vk.queue, present)
        presentBlockNs += System.nanoTime() - submitted[id]!!
        frame = (frame + 1) % FRAMES_IN_FLIGHT
        collectTimings()
        waitableId = if (presented == VK_SUCCESS) id else 0
        if (presented != VK_SUCCESS) presentResults.merge(presented, 1, Int::plus)
        if (presented == VK_ERROR_SURFACE_LOST_KHR) recoverSurface()
        else if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR || resized) rebuild()
        true
    }

    /** Presents that did not return VK_SUCCESS since last read, by result code, for diagnostics. */
    val presentResults = HashMap<Int, Int>()

    /** Off with HALONE_PRESENT_WAIT=0, to tell a driver's present wait apart from everything else. */
    var presentWaitEnabled = System.getenv("HALONE_PRESENT_WAIT") != "0"

    private var waitableId = 0L

    /** Time spent inside vkQueueSubmit2 and vkQueuePresentKHR since last read, for diagnostics. */
    var primeBlockNs = 0L
    var submitBlockNs = 0L
    var presentBlockNs = 0L

    /** The newest frame handed to the presentation engine. */
    var lastPresentId = 0L
        private set

    /** When set, asks the presentation engine not to show the next frame before this time (its clock). */
    var desiredPresentNs = 0L

    /** Called with each frame's presentation once the engine reports it. */
    var onPresented: ((PresentRecord) -> Unit)? = null

    private val submitted = HashMap<Long, Long>()

    /**
     * Blocks until the presentation engine has taken the newest frame, or
     * [timeoutNs] passes. Returns false on timeout, when unsupported, or when
     * there is no frame it can wait on.
     *
     * Only a frame presented successfully on the current swapchain qualifies.
     * Waiting on one that was not, as after an out-of-date present or a rebuild,
     * crashed RADV on Wayland inside vkWaitForPresentKHR.
     *
     * On MoltenVK this returns a few milliseconds after submission, not when the
     * frame is displayed, which is still two refreshes away even with an empty
     * queue. It bounds the queue all the same: with two images, pacing on it
     * keeps a frame two refreshes from the display instead of three. Waiting for
     * the display timing report instead halves the frame rate.
     */
    fun waitForLastPresent(timeoutNs: Long): Boolean {
        if (!presentWaitEnabled || !vk.hasPresentWait || waitableId <= 0) return false
        return KHRPresentWait.vkWaitForPresentKHR(vk.device, swapchain.handle, waitableId, timeoutNs) == VK_SUCCESS
    }

    /** The display's refresh period as the presentation engine reports it, or 0. */
    fun refreshDurationNs(): Long = if (!vk.hasDisplayTiming) 0 else stackPush().use { stack ->
        val duration = VkRefreshCycleDurationGOOGLE.calloc(stack)
        if (GOOGLEDisplayTiming.vkGetRefreshCycleDurationGOOGLE(vk.device, swapchain.handle, duration) == VK_SUCCESS) duration.refreshDuration() else 0
    }

    private fun collectTimings() {
        if (!vk.hasDisplayTiming) return
        stackPush().use { stack ->
            val pCount = stack.mallocInt(1)
            GOOGLEDisplayTiming.vkGetPastPresentationTimingGOOGLE(vk.device, swapchain.handle, pCount, null)
            val count = pCount.get(0)
            if (count == 0) return
            val timings = VkPastPresentationTimingGOOGLE.malloc(count, stack)
            GOOGLEDisplayTiming.vkGetPastPresentationTimingGOOGLE(vk.device, swapchain.handle, pCount, timings)
            for (i in 0 until pCount.get(0)) {
                val t = timings[i]
                val id = t.presentID().toLong() and 0xffffffffL
                val submittedNs = submitted.remove(id) ?: continue
                onPresented?.invoke(PresentRecord(id, submittedNs, t.desiredPresentTime(), t.actualPresentTime(), t.earliestPresentTime(), t.presentMargin()))
            }
            if (submitted.size > 256) submitted.keys.sorted().take(submitted.size - 256).forEach { submitted.remove(it) }
        }
    }

    private fun rebuild() {
        resized = false
        waitableId = 0
        vkDeviceWaitIdle(vk.device)
        val (w, h) = framebufferSize()
        if (w == 0 || h == 0) return
        val old = swapchain
        swapchain = Swapchain(vk, w, h, old, images)
        old.close()
        renderFinished.forEach { vkDestroySemaphore(vk.device, it, null) }
        renderFinished = LongArray(swapchain.images.size) { semaphore() }
    }

    /** Times the surface was lost and made again since last read, for diagnostics. */
    var surfacesLost = 0

    private fun recoverSurface() {
        resized = false
        waitableId = 0
        vkDeviceWaitIdle(vk.device)
        swapchain.close()
        vk.recreateSurface()
        surfacesLost++
        val (w, h) = framebufferSize()
        swapchain = Swapchain(vk, w, h, null, images)
        renderFinished.forEach { vkDestroySemaphore(vk.device, it, null) }
        renderFinished = LongArray(swapchain.images.size) { semaphore() }
    }

    private fun framebufferSize() = vk.output.size()

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
