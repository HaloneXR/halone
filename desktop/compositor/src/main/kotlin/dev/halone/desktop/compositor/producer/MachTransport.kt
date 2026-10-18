package dev.halone.desktop.compositor.producer

import com.sun.jna.ptr.IntByReference
import dev.halone.desktop.compositor.vk.StaticTexture
import dev.halone.desktop.compositor.vk.VulkanContext
import dev.halone.desktop.compositor.vk.vkCheck
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.EXTMetalObjects
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImportMetalIOSurfaceInfoEXT
import org.lwjgl.vulkan.VkMemoryRequirements

/** IOSurfaces through Mach ports: the producer's own use counts say which surfaces the compositor still reads. */
class MachTransport : ProducerTransport {
    private val bridge = Bridge.native
    private val service = "dev.halone.desktop.${ProcessHandle.current().pid()}"
    private val port: Int
    @Volatile private var producerPort = 0
    private val event = BridgeEvent()

    init {
        val pPort = IntByReference()
        val kr = bridge.halone_check_in(service, pPort)
        check(kr == 0) { "bootstrap_check_in failed: $kr" }
        port = pPort.value
    }

    override val producerArguments = listOf("--spike-service=$service")

    override val connected get() = producerPort != 0

    override fun receive(timeoutMs: Int): ProducerEvent? {
        if (bridge.halone_receive(port, event, timeoutMs) != 0) return null
        return when (event.kind) {
            BridgeEvent.HELLO -> {
                if (producerPort == 0) producerPort = event.reply else bridge.halone_release_port(event.reply)
                ProducerEvent(ProducerEvent.HELLO, event.panel,
                    surfaces = if (event.count == 0) null else IOSurfaces(event.surfaces.copyOf(event.count), event.width, event.height))
            }
            BridgeEvent.FRAME -> ProducerEvent(ProducerEvent.FRAME, event.panel, event.index, seq = event.seq,
                readyNs = event.readyNs, arrivedNs = event.arrivedNs)
            BridgeEvent.CURSOR -> ProducerEvent(ProducerEvent.CURSOR, event.panel,
                cursor = event.name.takeWhile { it != 0.toByte() }.toByteArray().decodeToString())
            else -> null
        }
    }

    override fun tick(targetNs: Long) {
        if (producerPort != 0) bridge.halone_send_tick(producerPort, targetNs)
    }

    override fun input(panel: Int, kind: Int, x: Float, y: Float, dx: Float, dy: Float, button: Int, clicks: Int,
                       modifiers: Int, keyCode: Int, character: Int, unmodified: Int) {
        if (producerPort != 0) bridge.halone_send_input(producerPort, panel, kind, x, y, dx, dy, button, clicks, modifiers, keyCode, character, unmodified)
    }

    override fun resize(panel: Int, width: Int, height: Int) {
        if (producerPort != 0) bridge.halone_send_resize(producerPort, panel, width, height)
    }

    override fun open(panel: Int, width: Int, height: Int, url: String, origin: String, script: String) {
        if (producerPort != 0) bridge.halone_send_open(producerPort, panel, width, height, url, origin, script)
    }

    override fun closePanel(panel: Int) {
        if (producerPort != 0) bridge.halone_send_close(producerPort, panel)
    }

    override fun showing(panel: Int, texture: ProducerTexture, index: Int) {
        bridge.halone_surface_use((texture.surfaces as IOSurfaces).handles[index], 1)
    }

    override fun done(panel: Int, texture: ProducerTexture, index: Int) {
        bridge.halone_surface_use((texture.surfaces as IOSurfaces).handles[index], -1)
    }

    override fun skipped(panel: Int, index: Int, generation: Int) {}

    override fun close() {
        bridge.halone_release_port(producerPort)
    }

    private class IOSurfaces(val handles: LongArray, override val width: Int, override val height: Int) : ProducerSurfaces {
        override val count get() = handles.size
        override val generation = 0

        override fun import(vk: VulkanContext, index: Int): Pair<Long, Long> = stackPush().use { stack ->
            check(vk.hasMetalObjects) { "importing IOSurfaces needs VK_EXT_metal_objects" }
            val import = VkImportMetalIOSurfaceInfoEXT.calloc(stack)
                .sType(EXTMetalObjects.VK_STRUCTURE_TYPE_IMPORT_METAL_IO_SURFACE_INFO_EXT)
                .ioSurface(handles[index])
            val info = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(import.address())
                .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_B8G8R8A8_UNORM)
                .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            info.extent().set(width, height, 1)
            val pImage = stack.mallocLong(1)
            vkCheck(vkCreateImage(vk.device, info, null, pImage), "vkCreateImage (IOSurface)")
            val image = pImage.get(0)
            val reqs = VkMemoryRequirements.malloc(stack)
            vkGetImageMemoryRequirements(vk.device, image, reqs)
            val memory = StaticTexture.allocate(vk, reqs, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
            vkBindImageMemory(vk.device, image, memory, 0)
            image to memory
        }

        override fun release() {
            handles.forEach(Bridge.native::halone_surface_release)
        }
    }
}
