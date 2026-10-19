package dev.halone.desktop.compositor.producer

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import dev.halone.desktop.compositor.vk.VulkanContext
import dev.halone.desktop.compositor.vk.vkCheck
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT
import org.lwjgl.vulkan.EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT
import org.lwjgl.vulkan.EXTImageDrmFormatModifier.VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT
import org.lwjgl.vulkan.KHRExternalMemoryFd.VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR
import org.lwjgl.vulkan.KHRExternalMemoryFd.VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR
import org.lwjgl.vulkan.KHRExternalMemoryFd.vkGetMemoryFdPropertiesKHR
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageDrmFormatModifierExplicitCreateInfoEXT
import org.lwjgl.vulkan.VkImportMemoryFdInfoKHR
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo
import org.lwjgl.vulkan.VkMemoryFdPropertiesKHR
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkSubresourceLayout
import org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND
import org.lwjgl.glfw.GLFW.glfwGetPlatform
import java.io.File

@Structure.FieldOrder(
    "kind", "panel", "index", "count", "width", "height", "fourcc", "planes", "generation", "pad",
    "modifier", "seq", "begunNs", "paintedNs", "readyNs", "arrivedNs", "fds", "sizes", "offsets", "strides", "name",
    "flags", "title", "text", "number", "state", "received", "total", "x", "y", "extra", "path", "mime",
)
class UnixBridgeEvent : Structure() {
    @JvmField var kind = 0
    @JvmField var panel = 0
    @JvmField var index = 0
    @JvmField var count = 0
    @JvmField var width = 0
    @JvmField var height = 0
    @JvmField var fourcc = 0
    @JvmField var planes = 0
    @JvmField var generation = 0
    @JvmField var pad = 0
    @JvmField var modifier = 0L
    @JvmField var seq = 0L
    @JvmField var begunNs = 0L
    @JvmField var paintedNs = 0L
    @JvmField var readyNs = 0L
    @JvmField var arrivedNs = 0L
    @JvmField var fds = IntArray(MAX_SURFACES)
    @JvmField var sizes = LongArray(MAX_SURFACES)
    @JvmField var offsets = IntArray(MAX_SURFACES * MAX_PLANES)
    @JvmField var strides = IntArray(MAX_SURFACES * MAX_PLANES)
    @JvmField var name = ByteArray(BridgeEvent.CURSOR_NAME_BYTES)
    @JvmField var flags = 0
    @JvmField var title = ByteArray(BridgeEvent.TITLE_BYTES)
    @JvmField var text = ByteArray(BridgeEvent.URL_BYTES)
    @JvmField var number = 0
    @JvmField var state = 0
    @JvmField var received = 0L
    @JvmField var total = 0L
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var extra = ByteArray(BridgeEvent.URL_BYTES)
    @JvmField var path = ByteArray(BridgeEvent.PATH_BYTES)
    @JvmField var mime = ByteArray(BridgeEvent.MIME_BYTES)

    companion object {
        const val MAX_SURFACES = 8
        const val MAX_PLANES = 4
    }
}

@Suppress("FunctionName")
interface UnixBridge : Library {
    fun halone_listen(path: String): Int
    fun halone_accept(listener: Int, timeoutMs: Int): Int
    fun halone_receive(connection: Int, out: UnixBridgeEvent, timeoutMs: Int): Int
    fun halone_send_tick(connection: Int, targetNs: Long): Int
    fun halone_send_input(
        connection: Int, panel: Int, kind: Int, x: Float, y: Float, dx: Float, dy: Float,
        button: Int, clicks: Int, modifiers: Int, keyCode: Int, character: Int, unmodified: Int,
    ): Int
    fun halone_send_resize(connection: Int, panel: Int, width: Int, height: Int): Int
    fun halone_send_release(connection: Int, panel: Int, index: Int, generation: Int): Int
    fun halone_send_open(connection: Int, panel: Int, width: Int, height: Int, flags: Int, url: String, origin: String, script: String, root: String): Int
    fun halone_send_command(connection: Int, panel: Int, kind: Int, text: String): Int
    fun halone_send_download_control(connection: Int, download: Int, cancel: Int, path: String): Int
    fun halone_send_prompt_answer(connection: Int, panel: Int, prompt: Int, allow: Int, remember: Int): Int
    fun halone_send_close(connection: Int, panel: Int): Int
    fun halone_close(fd: Int)

    companion object {
        val native: UnixBridge by lazy { Native.load("halone_bridge", UnixBridge::class.java) }
    }
}

/**
 * A Unix socket carrying DMA-BUFs. The producer only writes a surface after the
 * compositor releases it, so every frame is released exactly once: when skipped,
 * or once the GPU is done with it after it was shown.
 */
class UnixTransport : ProducerTransport {
    private val bridge = UnixBridge.native
    private val path = File(System.getenv("XDG_RUNTIME_DIR") ?: System.getProperty("java.io.tmpdir"),
        "halone-producer-${ProcessHandle.current().pid()}.sock").path
    private val listener = bridge.halone_listen(path).also { check(it >= 0) { "could not listen on $path: errno ${-it}" } }
    @Volatile private var connection = -1
    private val event = UnixBridgeEvent()

    override val producerArguments = listOf("--spike-socket=$path")

    override val connected get() = connection >= 0

    override fun receive(timeoutMs: Int): ProducerEvent? {
        if (connection < 0) {
            connection = bridge.halone_accept(listener, timeoutMs)
            return null
        }
        if (bridge.halone_receive(connection, event, timeoutMs) != 0) {
            Thread.sleep(timeoutMs.toLong())
            return null
        }
        return when (event.kind) {
            ProducerEvent.HELLO -> ProducerEvent(ProducerEvent.HELLO, event.panel, generation = event.generation,
                surfaces = DmaBufs(event))
            ProducerEvent.FRAME -> ProducerEvent(ProducerEvent.FRAME, event.panel, event.index, event.generation,
                event.seq, event.readyNs, event.arrivedNs)
            ProducerEvent.CURSOR -> ProducerEvent(ProducerEvent.CURSOR, event.panel,
                cursor = event.name.takeWhile { it != 0.toByte() }.toByteArray().decodeToString())
            ProducerEvent.PAGE -> ProducerEvent(ProducerEvent.PAGE, event.panel, flags = event.flags,
                title = cString(event.title), text = cString(event.text))
            ProducerEvent.POPUP -> ProducerEvent(ProducerEvent.POPUP, event.panel, flags = event.flags, text = cString(event.text))
            ProducerEvent.CONTEXT -> ProducerEvent(ProducerEvent.CONTEXT, event.panel, flags = event.flags, number = event.number,
                x = event.x, y = event.y, text = cString(event.text), extra = cString(event.extra), title = cString(event.title))
            ProducerEvent.DOWNLOAD -> ProducerEvent(ProducerEvent.DOWNLOAD, event.panel, number = event.number, state = event.state,
                received = event.received, total = event.total, text = cString(event.text), title = cString(event.title),
                mime = cString(event.mime), path = cString(event.path))
            ProducerEvent.PROMPT -> ProducerEvent(ProducerEvent.PROMPT, event.panel, number = event.number, flags = event.flags,
                cursor = cString(event.name), title = cString(event.title))
            ProducerEvent.FULLSCREEN -> ProducerEvent(ProducerEvent.FULLSCREEN, event.panel, flags = event.flags)
            ProducerEvent.SCROLL -> ProducerEvent(ProducerEvent.SCROLL, event.panel, y = event.y)
            else -> null
        }
    }

    override fun tick(targetNs: Long) {
        if (connection >= 0) bridge.halone_send_tick(connection, targetNs)
    }

    override fun input(panel: Int, kind: Int, x: Float, y: Float, dx: Float, dy: Float, button: Int, clicks: Int,
                       modifiers: Int, keyCode: Int, character: Int, unmodified: Int) {
        if (connection >= 0) bridge.halone_send_input(connection, panel, kind, x, y, dx, dy, button, clicks, modifiers,
            if (kind == ProducerHost.INPUT_KEY_DOWN || kind == ProducerHost.INPUT_KEY_UP) keyCode + scancodeToXkb else keyCode,
            character, unmodified)
    }

    /** The producer takes XKB keycodes, as GLFW gives on X11; on Wayland GLFW gives evdev codes, 8 lower. */
    private val scancodeToXkb = if (glfwGetPlatform() == GLFW_PLATFORM_WAYLAND) 8 else 0

    override fun resize(panel: Int, width: Int, height: Int) {
        if (connection >= 0) bridge.halone_send_resize(connection, panel, width, height)
    }

    override fun open(panel: Int, width: Int, height: Int, flags: Int, url: String, origin: String, script: String, root: String) {
        if (connection >= 0) bridge.halone_send_open(connection, panel, width, height, flags, url, origin, script, root)
    }

    override fun command(panel: Int, kind: Int, text: String) {
        if (connection >= 0) bridge.halone_send_command(connection, panel, kind, text)
    }
    override fun downloadControl(download: Int, cancel: Boolean, path: String) {
        if (connection >= 0) bridge.halone_send_download_control(connection, download, if (cancel) 1 else 0, path)
    }

    override fun answerPrompt(panel: Int, prompt: Int, allow: Boolean, remember: Boolean) {
        if (connection >= 0) bridge.halone_send_prompt_answer(connection, panel, prompt, if (allow) 1 else 0, if (remember) 1 else 0)
    }

    override fun closePanel(panel: Int) {
        if (connection >= 0) bridge.halone_send_close(connection, panel)
    }

    override fun showing(panel: Int, texture: ProducerTexture, index: Int) {}

    override fun done(panel: Int, texture: ProducerTexture, index: Int) = skipped(panel, index, texture.surfaces.generation)

    override fun skipped(panel: Int, index: Int, generation: Int) {
        if (connection >= 0) bridge.halone_send_release(connection, panel, index, generation)
    }

    override fun close() {
        bridge.halone_close(connection)
        bridge.halone_close(listener)
        File(path).delete()
    }

    /** One HELLO's DMA-BUFs; each fd passes to Vulkan when imported. */
    private class DmaBufs(e: UnixBridgeEvent) : ProducerSurfaces {
        override val count = e.count
        override val width = e.width
        override val height = e.height
        override val generation = e.generation
        private val fds = e.fds.copyOf(e.count)
        private val sizes = e.sizes.copyOf(e.count)
        private val offsets = e.offsets.copyOf()
        private val strides = e.strides.copyOf()
        private val planes = e.planes
        private val modifier = e.modifier
        private val format = when (e.fourcc) {
            DRM_FORMAT_ABGR8888 -> VK_FORMAT_R8G8B8A8_UNORM
            else -> VK_FORMAT_B8G8R8A8_UNORM
        }

        override fun import(vk: VulkanContext, index: Int): Pair<Long, Long> = stackPush().use { stack ->
            check(vk.hasDmaBuf) { "importing DMA-BUFs needs VK_EXT_external_memory_dma_buf and VK_EXT_image_drm_format_modifier" }
            val layouts = VkSubresourceLayout.calloc(planes, stack)
            for (p in 0 until planes) {
                val k = index * UnixBridgeEvent.MAX_PLANES + p
                layouts[p].offset(offsets[k].toLong() and 0xffffffffL).rowPitch(strides[k].toLong() and 0xffffffffL)
            }
            val explicit = VkImageDrmFormatModifierExplicitCreateInfoEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT)
                .drmFormatModifier(modifier).pPlaneLayouts(layouts)
            val external = VkExternalMemoryImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                .pNext(explicit.address())
                .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
            val info = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(external.address())
                .imageType(VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT).usage(VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            info.extent().set(width, height, 1)
            val pImage = stack.mallocLong(1)
            vkCheck(vkCreateImage(vk.device, info, null, pImage), "vkCreateImage (DMA-BUF)")
            val image = pImage.get(0)

            val reqs = VkMemoryRequirements.malloc(stack)
            vkGetImageMemoryRequirements(vk.device, image, reqs)
            val fdProps = VkMemoryFdPropertiesKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR)
            vkCheck(vkGetMemoryFdPropertiesKHR(vk.device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fds[index], fdProps),
                "vkGetMemoryFdPropertiesKHR")
            val dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO).image(image)
            val import = VkImportMemoryFdInfoKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR)
                .pNext(dedicated.address())
                .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT).fd(fds[index])
            val alloc = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(import.address())
                .allocationSize(maxOf(reqs.size(), sizes[index]))
                .memoryTypeIndex(memoryType(vk, reqs.memoryTypeBits() and fdProps.memoryTypeBits()))
            val pMemory = stack.mallocLong(1)
            vkCheck(vkAllocateMemory(vk.device, alloc, null, pMemory), "vkAllocateMemory (DMA-BUF)")
            fds[index] = -1
            vkCheck(vkBindImageMemory(vk.device, image, pMemory.get(0), 0), "vkBindImageMemory (DMA-BUF)")
            image to pMemory.get(0)
        }

        override fun release() {
            fds.forEachIndexed { i, fd -> if (fd >= 0) UnixBridge.native.halone_close(fd).also { fds[i] = -1 } }
        }

        private fun memoryType(vk: VulkanContext, bits: Int): Int = stackPush().use { stack ->
            val props = VkPhysicalDeviceMemoryProperties.malloc(stack)
            vkGetPhysicalDeviceMemoryProperties(vk.physicalDevice, props)
            (0 until props.memoryTypeCount()).firstOrNull { bits and (1 shl it) != 0 }
                ?: throw IllegalStateException("no memory type can hold the producer's DMA-BUF")
        }

        private companion object {
            const val DRM_FORMAT_ABGR8888 = 0x34324241
        }
    }
}
