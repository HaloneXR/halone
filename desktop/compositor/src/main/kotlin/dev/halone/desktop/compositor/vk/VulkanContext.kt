package dev.halone.desktop.compositor.vk

import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.EXTMetalObjects
import org.lwjgl.vulkan.GOOGLEDisplayTiming
import org.lwjgl.vulkan.KHRPortabilityEnumeration
import org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR
import org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR
import org.lwjgl.vulkan.KHRSwapchain
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkCommandPoolCreateInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkExtensionProperties
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkQueueFamilyProperties

fun vkCheck(result: Int, what: String) {
    if (result != VK_SUCCESS) throw IllegalStateException("$what failed: VkResult $result")
}

/** Instance, surface, device and the one graphics-and-present queue the compositor uses. */
class VulkanContext(window: Long) : AutoCloseable {
    val instance: VkInstance
    val surface: Long
    val physicalDevice: VkPhysicalDevice
    val device: VkDevice
    val queue: VkQueue
    val queueFamily: Int
    val commandPool: Long
    val deviceName: String
    val apiVersion: Int
    val enabledExtensions: Set<String>
    val availableExtensions: Set<String>

    val hasMetalObjects get() = EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME in enabledExtensions
    val hasDisplayTiming get() = GOOGLEDisplayTiming.VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME in enabledExtensions

    init {
        stackPush().use { stack ->
            val glfwExtensions = glfwGetRequiredInstanceExtensions()
                ?: throw IllegalStateException("GLFW found no Vulkan support")
            val instanceExtensions = mutableListOf<String>()
            for (i in 0 until glfwExtensions.limit()) instanceExtensions += glfwExtensions.getStringUTF8(i)
            val portable = KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME in instanceExtensionNames(stack)
            if (portable) instanceExtensions += KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME

            val app = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("Halone"))
                .apiVersion(VK_API_VERSION_1_3)
            val instanceInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(app)
                .ppEnabledExtensionNames(strings(stack, instanceExtensions))
            if (portable) instanceInfo.flags(KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
            val pInstance = stack.mallocPointer(1)
            vkCheck(vkCreateInstance(instanceInfo, null, pInstance), "vkCreateInstance")
            instance = VkInstance(pInstance.get(0), instanceInfo)

            val pSurface = stack.mallocLong(1)
            vkCheck(glfwCreateWindowSurface(instance, window, null, pSurface), "glfwCreateWindowSurface")
            surface = pSurface.get(0)

            val pCount = stack.mallocInt(1)
            vkEnumeratePhysicalDevices(instance, pCount, null)
            val pDevices = stack.mallocPointer(pCount.get(0))
            vkEnumeratePhysicalDevices(instance, pCount, pDevices)
            physicalDevice = VkPhysicalDevice(pDevices.get(0), instance)

            val props = VkPhysicalDeviceProperties.calloc(stack)
            vkGetPhysicalDeviceProperties(physicalDevice, props)
            deviceName = props.deviceNameString()
            apiVersion = props.apiVersion()

            queueFamily = findQueueFamily(stack)
            availableExtensions = deviceExtensions(stack)

            val wanted = listOf(
                KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                "VK_KHR_portability_subset",
                EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME,
                GOOGLEDisplayTiming.VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME,
            )
            enabledExtensions = wanted.filter { it in availableExtensions }.toSet()

            val priority = stack.floats(1f)
            val queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(queueFamily)
                .pQueuePriorities(priority)
            val features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                .dynamicRendering(true)
                .synchronization2(true)
            val deviceInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(features13)
                .pQueueCreateInfos(queueInfo)
                .ppEnabledExtensionNames(strings(stack, enabledExtensions.toList()))
            val pDevice = stack.mallocPointer(1)
            vkCheck(vkCreateDevice(physicalDevice, deviceInfo, null, pDevice), "vkCreateDevice")
            device = VkDevice(pDevice.get(0), physicalDevice, deviceInfo)

            val pQueue = stack.mallocPointer(1)
            vkGetDeviceQueue(device, queueFamily, 0, pQueue)
            queue = VkQueue(pQueue.get(0), device)

            val poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queueFamily)
            val pPool = stack.mallocLong(1)
            vkCheck(vkCreateCommandPool(device, poolInfo, null, pPool), "vkCreateCommandPool")
            commandPool = pPool.get(0)
        }
    }

    private fun findQueueFamily(stack: MemoryStack): Int {
        val pCount = stack.mallocInt(1)
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pCount, null)
        val families = VkQueueFamilyProperties.malloc(pCount.get(0), stack)
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pCount, families)
        val supported = stack.mallocInt(1)
        for (i in 0 until families.capacity()) {
            vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, i, surface, supported)
            if (families[i].queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0 && supported.get(0) == VK_TRUE) return i
        }
        throw IllegalStateException("no queue family can both draw and present")
    }

    private fun instanceExtensionNames(stack: MemoryStack): Set<String> {
        val pCount = stack.mallocInt(1)
        vkEnumerateInstanceExtensionProperties(null as String?, pCount, null)
        val props = VkExtensionProperties.malloc(pCount.get(0), stack)
        vkEnumerateInstanceExtensionProperties(null as String?, pCount, props)
        return (0 until props.capacity()).map { props[it].extensionNameString() }.toSet()
    }

    private fun deviceExtensions(stack: MemoryStack): Set<String> {
        val pCount = stack.mallocInt(1)
        vkEnumerateDeviceExtensionProperties(physicalDevice, null as String?, pCount, null)
        val props = VkExtensionProperties.malloc(pCount.get(0), stack)
        vkEnumerateDeviceExtensionProperties(physicalDevice, null as String?, pCount, props)
        return (0 until props.capacity()).map { props[it].extensionNameString() }.toSet()
    }

    fun versionString(version: Int = apiVersion) =
        "${VK_API_VERSION_MAJOR(version)}.${VK_API_VERSION_MINOR(version)}.${VK_API_VERSION_PATCH(version)}"

    override fun close() {
        vkDeviceWaitIdle(device)
        vkDestroyCommandPool(device, commandPool, null)
        vkDestroyDevice(device, null)
        vkDestroySurfaceKHR(instance, surface, null)
        vkDestroyInstance(instance, null)
    }

    companion object {
        fun isMac() = System.getProperty("os.name").lowercase().contains("mac")

        fun strings(stack: MemoryStack, values: List<String>): PointerBuffer {
            val buffer = stack.mallocPointer(values.size)
            values.forEach { buffer.put(stack.UTF8(it)) }
            return buffer.flip()
        }
    }
}
