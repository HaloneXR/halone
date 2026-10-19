package dev.halone.desktop.compositor.vk

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.EXTExternalMemoryDmaBuf
import org.lwjgl.vulkan.EXTImageDrmFormatModifier
import org.lwjgl.vulkan.EXTMetalObjects
import org.lwjgl.vulkan.EXTQueueFamilyForeign
import org.lwjgl.vulkan.KHRExternalMemoryFd
import org.lwjgl.vulkan.GOOGLEDisplayTiming
import org.lwjgl.vulkan.KHRPortabilityEnumeration
import org.lwjgl.vulkan.KHRPresentId
import org.lwjgl.vulkan.KHRPresentWait
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR
import org.lwjgl.vulkan.VkPhysicalDevicePresentWaitFeaturesKHR
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
class VulkanContext(val output: Output) : AutoCloseable {
    constructor(window: Long) : this(WindowOutput(window))

    val instance: VkInstance
    var surface: Long
        private set
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
    val hasDmaBuf get() = DMA_BUF_EXTENSIONS.all { it in enabledExtensions }
    val hasDisplayTiming get() = GOOGLEDisplayTiming.VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME in enabledExtensions
    val hasPresentId get() = KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME in enabledExtensions
    val hasPresentWait get() = KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME in enabledExtensions

    init {
        stackPush().use { stack ->
            val instanceExtensions = output.instanceExtensions.toMutableList()
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

            val pCount = stack.mallocInt(1)
            vkEnumeratePhysicalDevices(instance, pCount, null)
            val pDevices = stack.mallocPointer(pCount.get(0))
            vkEnumeratePhysicalDevices(instance, pCount, pDevices)
            val props = VkPhysicalDeviceProperties.calloc(stack)
            val ranked = (0 until pCount.get(0)).map { VkPhysicalDevice(pDevices.get(it), instance) }
                .sortedBy { device ->
                    vkGetPhysicalDeviceProperties(device, props)
                    DEVICE_PREFERENCE.indexOf(props.deviceType()).let { if (it < 0) DEVICE_PREFERENCE.size else it }
                }
            val (chosen, chosenSurface, family) = ranked.firstNotNullOfOrNull { device ->
                val surface = output.surface(instance, device)
                if (surface == VK_NULL_HANDLE) return@firstNotNullOfOrNull null
                presentQueueFamily(device, surface, stack).takeIf { it >= 0 }?.let { Triple(device, surface, it) }
            } ?: throw IllegalStateException("no Vulkan device can both draw and present to this output")
            physicalDevice = chosen
            surface = chosenSurface
            queueFamily = family
            vkGetPhysicalDeviceProperties(physicalDevice, props)
            deviceName = props.deviceNameString()
            apiVersion = props.apiVersion()

            availableExtensions = deviceExtensions(stack)

            val presentIdFeature = VkPhysicalDevicePresentIdFeaturesKHR.calloc(stack).sType(KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR)
            val presentWaitFeature = VkPhysicalDevicePresentWaitFeaturesKHR.calloc(stack).sType(KHRPresentWait.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR)
                .pNext(presentIdFeature.address())
            vkGetPhysicalDeviceFeatures2(physicalDevice, VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(presentWaitFeature.address()))
            val presentIdSupported = presentIdFeature.presentId() && KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME in availableExtensions
            val presentWaitSupported = presentIdSupported && presentWaitFeature.presentWait() &&
                KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME in availableExtensions

            val wanted = listOfNotNull(
                KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                "VK_KHR_portability_subset",
                EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME,
                GOOGLEDisplayTiming.VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME,
                KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME.takeIf { presentIdSupported },
                KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME.takeIf { presentWaitSupported },
            ) + DMA_BUF_EXTENSIONS + EXTQueueFamilyForeign.VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME
            enabledExtensions = wanted.filter { it in availableExtensions }.toSet()

            val priority = stack.floats(1f)
            val queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(queueFamily)
                .pQueuePriorities(priority)
            val enablePresentId = VkPhysicalDevicePresentIdFeaturesKHR.calloc(stack)
                .sType(KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR).presentId(presentIdSupported)
            val enablePresentWait = VkPhysicalDevicePresentWaitFeaturesKHR.calloc(stack)
                .sType(KHRPresentWait.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR).presentWait(presentWaitSupported)
                .pNext(enablePresentId.address())
            val features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                .dynamicRendering(true)
                .synchronization2(true)
                .pNext(enablePresentWait.address())
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

    /** A queue family of [device] that can both draw and present to [surface], or -1. */
    private fun presentQueueFamily(device: VkPhysicalDevice, surface: Long, stack: MemoryStack): Int {
        val pCount = stack.mallocInt(1)
        vkGetPhysicalDeviceQueueFamilyProperties(device, pCount, null)
        val families = VkQueueFamilyProperties.malloc(pCount.get(0), stack)
        vkGetPhysicalDeviceQueueFamilyProperties(device, pCount, families)
        val supported = stack.mallocInt(1)
        for (i in 0 until families.capacity()) {
            vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, supported)
            if (families[i].queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0 && supported.get(0) == VK_TRUE) return i
        }
        return -1
    }

    private fun instanceExtensionNames(stack: MemoryStack): Set<String> {
        val pCount = stack.mallocInt(1)
        vkEnumerateInstanceExtensionProperties(null as String?, pCount, null)
        return VkExtensionProperties.malloc(pCount.get(0)).use { props ->
            vkEnumerateInstanceExtensionProperties(null as String?, pCount, props)
            (0 until pCount.get(0)).map { props[it].extensionNameString() }.toSet()
        }
    }

    private fun deviceExtensions(stack: MemoryStack): Set<String> {
        val pCount = stack.mallocInt(1)
        vkEnumerateDeviceExtensionProperties(physicalDevice, null as String?, pCount, null)
        // Heap, not the MemoryStack: RADV lists ~250 extensions at 260 bytes each, which overflows LWJGL's 64 KiB stack.
        return VkExtensionProperties.malloc(pCount.get(0)).use { props ->
            vkEnumerateDeviceExtensionProperties(physicalDevice, null as String?, pCount, props)
            (0 until pCount.get(0)).map { props[it].extensionNameString() }.toSet()
        }
    }

    /** Replaces a lost surface, such as a leased display's after its link dropped; the swapchain must be gone already. */
    fun recreateSurface() {
        vkDeviceWaitIdle(device)
        vkDestroySurfaceKHR(instance, surface, null)
        output.release()
        surface = output.surface(instance, physicalDevice)
        if (surface == VK_NULL_HANDLE && output.renew()) surface = output.surface(instance, physicalDevice)
        check(surface != VK_NULL_HANDLE) { "the output could not be reached again" }
    }

    fun versionString(version: Int = apiVersion) =
        "${VK_API_VERSION_MAJOR(version)}.${VK_API_VERSION_MINOR(version)}.${VK_API_VERSION_PATCH(version)}"

    override fun close() {
        vkDeviceWaitIdle(device)
        vkDestroyCommandPool(device, commandPool, null)
        vkDestroyDevice(device, null)
        vkDestroySurfaceKHR(instance, surface, null)
        output.release()
        vkDestroyInstance(instance, null)
    }

    companion object {
        private val DMA_BUF_EXTENSIONS = listOf(
            KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
            EXTExternalMemoryDmaBuf.VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
            EXTImageDrmFormatModifier.VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME,
        )

        /** A real GPU before a software one: Linux usually lists llvmpipe beside the hardware. */
        private val DEVICE_PREFERENCE = listOf(
            VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU,
            VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU,
            VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU,
            VK_PHYSICAL_DEVICE_TYPE_OTHER,
            VK_PHYSICAL_DEVICE_TYPE_CPU,
        )

        fun isMac() = System.getProperty("os.name").lowercase().contains("mac")

        fun strings(stack: MemoryStack, values: List<String>): PointerBuffer {
            val buffer = stack.mallocPointer(values.size)
            values.forEach { buffer.put(stack.UTF8(it)) }
            return buffer.flip()
        }
    }
}
