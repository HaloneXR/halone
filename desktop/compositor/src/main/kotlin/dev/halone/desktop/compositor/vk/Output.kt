package dev.halone.desktop.compositor.vk

import org.lwjgl.glfw.GLFW.glfwGetFramebufferSize
import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.EXTAcquireDrmDisplay.VK_EXT_ACQUIRE_DRM_DISPLAY_EXTENSION_NAME
import org.lwjgl.vulkan.EXTAcquireDrmDisplay.vkAcquireDrmDisplayEXT
import org.lwjgl.vulkan.EXTAcquireDrmDisplay.vkGetDrmDisplayEXT
import org.lwjgl.vulkan.EXTDirectModeDisplay.VK_EXT_DIRECT_MODE_DISPLAY_EXTENSION_NAME
import org.lwjgl.vulkan.EXTDirectModeDisplay.vkReleaseDisplayEXT
import org.lwjgl.vulkan.KHRDisplay.*
import org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.VK_NULL_HANDLE
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import org.lwjgl.vulkan.VkDisplayModePropertiesKHR
import org.lwjgl.vulkan.VkDisplayPlaneCapabilitiesKHR
import org.lwjgl.vulkan.VkDisplayPlanePropertiesKHR
import org.lwjgl.vulkan.VkDisplaySurfaceCreateInfoKHR
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkPhysicalDevice
import kotlin.math.abs

/** Where frames are shown: a GLFW window, or a display leased from the desktop compositor. */
interface Output {
    val instanceExtensions: List<String>

    /** A surface presenting here from [device], or VK_NULL_HANDLE when [device] cannot reach this output. */
    fun surface(instance: VkInstance, device: VkPhysicalDevice): Long

    fun size(): Pair<Int, Int>

    /** Called after the surface is destroyed and before the instance is. */
    fun release() {}

    /** The refresh the output was set to, when it is known up front. */
    val refreshHz: Int? get() = null
}

class WindowOutput(val window: Long) : Output {
    private var surface = VK_NULL_HANDLE

    override val instanceExtensions: List<String> get() {
        val names = glfwGetRequiredInstanceExtensions() ?: throw IllegalStateException("GLFW found no Vulkan support")
        return (0 until names.limit()).map { names.getStringUTF8(it) }
    }

    override fun surface(instance: VkInstance, device: VkPhysicalDevice): Long {
        if (surface == VK_NULL_HANDLE) stackPush().use { stack ->
            val p = stack.mallocLong(1)
            vkCheck(glfwCreateWindowSurface(instance, window, null, p), "glfwCreateWindowSurface")
            surface = p.get(0)
        }
        return surface
    }

    override fun release() {
        surface = VK_NULL_HANDLE
    }

    override fun size(): Pair<Int, Int> = stackPush().use { stack ->
        val w = stack.mallocInt(1)
        val h = stack.mallocInt(1)
        glfwGetFramebufferSize(window, w, h)
        w.get(0) to h.get(0)
    }
}

/**
 * A DRM connector taken over through [drmFd] (a lease fd), shown directly on a display
 * plane with no desktop compositor in between. Picks the mode closest to [width]x[height]
 * at [wantedHz].
 */
class DisplayOutput(
    private val drmFd: Int,
    private val connectorId: Int,
    private val width: Int,
    private val height: Int,
    private val wantedHz: Float,
) : Output {
    private var physicalDevice: VkPhysicalDevice? = null
    private var display = VK_NULL_HANDLE
    private var extent = width to height
    override var refreshHz: Int? = null
        private set

    override val instanceExtensions = listOf(
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_DISPLAY_EXTENSION_NAME,
        VK_EXT_DIRECT_MODE_DISPLAY_EXTENSION_NAME,
        VK_EXT_ACQUIRE_DRM_DISPLAY_EXTENSION_NAME,
    )

    override fun surface(instance: VkInstance, device: VkPhysicalDevice): Long = stackPush().use { stack ->
        if (physicalDevice != null) return VK_NULL_HANDLE
        val pDisplay = stack.mallocLong(1)
        if (vkGetDrmDisplayEXT(device, drmFd, connectorId, pDisplay) != VK_SUCCESS || pDisplay.get(0) == VK_NULL_HANDLE) return VK_NULL_HANDLE
        val found = pDisplay.get(0)
        vkCheck(vkAcquireDrmDisplayEXT(device, drmFd, found), "vkAcquireDrmDisplayEXT")
        physicalDevice = device
        display = found

        val pCount = stack.mallocInt(1)
        vkGetDisplayModePropertiesKHR(device, display, pCount, null)
        val modes = VkDisplayModePropertiesKHR.malloc(pCount.get(0), stack)
        vkGetDisplayModePropertiesKHR(device, display, pCount, modes)
        val mode = (0 until modes.capacity()).map { modes[it] }.minByOrNull {
            val p = it.parameters()
            val sizeMiss = if (p.visibleRegion().width() == width && p.visibleRegion().height() == height) 0 else 1_000_000
            sizeMiss + abs(p.refreshRate() - wantedHz * 1000).toInt()
        } ?: throw IllegalStateException("the leased display lists no modes")
        val region = mode.parameters().visibleRegion()
        extent = region.width() to region.height()
        refreshHz = Math.round(mode.parameters().refreshRate() / 1000f)

        vkGetPhysicalDeviceDisplayPlanePropertiesKHR(device, pCount, null)
        val planes = VkDisplayPlanePropertiesKHR.malloc(pCount.get(0), stack)
        vkGetPhysicalDeviceDisplayPlanePropertiesKHR(device, pCount, planes)
        val plane = (0 until planes.capacity()).firstOrNull { index ->
            val current = planes[index].currentDisplay()
            (current == VK_NULL_HANDLE || current == display) && planeSupports(device, index, display)
        } ?: throw IllegalStateException("no display plane can show the leased display")

        val caps = VkDisplayPlaneCapabilitiesKHR.malloc(stack)
        vkGetDisplayPlaneCapabilitiesKHR(device, mode.displayMode(), plane, caps)
        val alpha = if (caps.supportedAlpha() and VK_DISPLAY_PLANE_ALPHA_OPAQUE_BIT_KHR != 0) VK_DISPLAY_PLANE_ALPHA_OPAQUE_BIT_KHR
            else VK_DISPLAY_PLANE_ALPHA_GLOBAL_BIT_KHR

        val info = VkDisplaySurfaceCreateInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DISPLAY_SURFACE_CREATE_INFO_KHR)
            .displayMode(mode.displayMode())
            .planeIndex(plane)
            .planeStackIndex(planes[plane].currentStackIndex())
            .transform(org.lwjgl.vulkan.KHRSurface.VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
            .globalAlpha(1f)
            .alphaMode(alpha)
            .imageExtent { it.width(extent.first).height(extent.second) }
        val pSurface = stack.mallocLong(1)
        vkCheck(vkCreateDisplayPlaneSurfaceKHR(instance, info, null, pSurface), "vkCreateDisplayPlaneSurfaceKHR")
        pSurface.get(0)
    }

    private fun planeSupports(device: VkPhysicalDevice, plane: Int, display: Long): Boolean = stackPush().use { stack ->
        val pCount = stack.mallocInt(1)
        vkGetDisplayPlaneSupportedDisplaysKHR(device, plane, pCount, null)
        val displays = stack.mallocLong(pCount.get(0))
        vkGetDisplayPlaneSupportedDisplaysKHR(device, plane, pCount, displays)
        (0 until pCount.get(0)).any { displays.get(it) == display }
    }

    override fun size() = extent

    override fun release() {
        physicalDevice?.let { if (display != VK_NULL_HANDLE) vkReleaseDisplayEXT(it, display) }
        display = VK_NULL_HANDLE
        physicalDevice = null
    }
}
