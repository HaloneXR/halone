package dev.halone.desktop.compositor.vk

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkMemoryRequirements

/** A host-visible buffer filled once: small, static geometry such as the distortion mesh. */
class HostBuffer private constructor(private val vk: VulkanContext, val handle: Long, private val memory: Long) : AutoCloseable {
    override fun close() {
        vkDestroyBuffer(vk.device, handle, null)
        vkFreeMemory(vk.device, memory, null)
    }

    companion object {
        fun of(vk: VulkanContext, usage: Int, floats: FloatArray): HostBuffer =
            create(vk, usage, floats.size * 4L) { MemoryUtil.memFloatBuffer(it, floats.size).put(floats) }

        fun of(vk: VulkanContext, usage: Int, shorts: ShortArray): HostBuffer =
            create(vk, usage, shorts.size * 2L) { MemoryUtil.memShortBuffer(it, shorts.size).put(shorts) }

        private fun create(vk: VulkanContext, usage: Int, size: Long, fill: (Long) -> Unit): HostBuffer = stackPush().use { stack ->
            val info = VkBufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            val pBuffer = stack.mallocLong(1)
            vkCheck(vkCreateBuffer(vk.device, info, null, pBuffer), "vkCreateBuffer")
            val reqs = VkMemoryRequirements.malloc(stack)
            vkGetBufferMemoryRequirements(vk.device, pBuffer.get(0), reqs)
            val memory = StaticTexture.allocate(vk, reqs, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
            vkBindBufferMemory(vk.device, pBuffer.get(0), memory, 0)
            val pData = stack.mallocPointer(1)
            vkMapMemory(vk.device, memory, 0, size, 0, pData)
            fill(pData.get(0))
            vkUnmapMemory(vk.device, memory)
            HostBuffer(vk, pBuffer.get(0), memory)
        }
    }
}
