// The CEF producer on Linux: CEF's DMA-BUF paints are copied on the GPU into each
// panel's own ring of exported DMA-BUFs, which go to the compositor over a Unix socket.

#include <dlfcn.h>
#include <fcntl.h>
#include <drm/drm_fourcc.h>
#include <linux/dma-buf.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <condition_variable>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <thread>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include "include/base/cef_callback.h"
#include "include/cef_app.h"
#include "include/cef_browser.h"
#include "include/cef_client.h"
#include "include/cef_command_line.h"
#include "include/cef_context_menu_handler.h"
#include "include/cef_download_handler.h"
#include "include/cef_permission_handler.h"
#include "include/wrapper/cef_closure_task.h"
#include "producer_common.h"

namespace {

uint64_t nowNs() {
    timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec;
}

double millis(uint64_t ns) { return ns / 1e6; }

#define VK_FUNCTIONS(X)                                \
    X(vkGetDeviceProcAddr)                             \
    X(vkEnumeratePhysicalDevices)                      \
    X(vkGetPhysicalDeviceProperties)                   \
    X(vkGetPhysicalDeviceQueueFamilyProperties)        \
    X(vkGetPhysicalDeviceMemoryProperties)             \
    X(vkGetPhysicalDeviceFormatProperties2)            \
    X(vkCreateDevice)                                  \
    X(vkGetDeviceQueue)                                \
    X(vkCreateCommandPool)                             \
    X(vkAllocateCommandBuffers)                        \
    X(vkCreateFence)                                   \
    X(vkResetFences)                                   \
    X(vkWaitForFences)                                 \
    X(vkResetCommandBuffer)                            \
    X(vkBeginCommandBuffer)                            \
    X(vkEndCommandBuffer)                              \
    X(vkCmdPipelineBarrier)                            \
    X(vkCmdCopyImage)                                  \
    X(vkQueueSubmit)                                   \
    X(vkCreateImage)                                   \
    X(vkDestroyImage)                                  \
    X(vkGetImageMemoryRequirements)                    \
    X(vkAllocateMemory)                                \
    X(vkFreeMemory)                                    \
    X(vkBindImageMemory)                               \
    X(vkGetImageSubresourceLayout)                     \
    X(vkCreateSemaphore)                               \
    X(vkDeviceWaitIdle)                                \
    X(vkGetMemoryFdKHR)                                \
    X(vkGetMemoryFdPropertiesKHR)                      \
    X(vkImportSemaphoreFdKHR)                          \
    X(vkGetImageDrmFormatModifierPropertiesEXT)

PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr;
PFN_vkCreateInstance vkCreateInstance;
#define DECLARE(name) PFN_##name name;
VK_FUNCTIONS(DECLARE)
#undef DECLARE

struct Gpu {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer commands = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkSemaphore painted = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memory = {};
    std::vector<VkDrmFormatModifierPropertiesEXT> modifiers;
} gGpu;

const VkFormat kFormat = VK_FORMAT_B8G8R8A8_UNORM;
const uint32_t kFourcc = DRM_FORMAT_ARGB8888;

#define CHECK_VK(call)                                                       \
    do {                                                                     \
        VkResult r_ = (call);                                                \
        if (r_ != VK_SUCCESS) {                                              \
            fprintf(stderr, "producer: %s failed: %d\n", #call, (int)r_);    \
            exit(1);                                                         \
        }                                                                    \
    } while (0)

bool initVulkan() {
    void *library = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!library) return false;
    vkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)dlsym(library, "vkGetInstanceProcAddr");
    vkCreateInstance = (PFN_vkCreateInstance)vkGetInstanceProcAddr(nullptr, "vkCreateInstance");

    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Halone CEF producer";
    app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo info = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    CHECK_VK(vkCreateInstance(&info, nullptr, &gGpu.instance));
#define LOAD(name) name = (PFN_##name)vkGetInstanceProcAddr(gGpu.instance, #name);
    VK_FUNCTIONS(LOAD)
#undef LOAD

    uint32_t count = 0;
    vkEnumeratePhysicalDevices(gGpu.instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(gGpu.instance, &count, devices.data());
    // The GPU CEF paints on: a real one before llvmpipe.
    const VkPhysicalDeviceType preference[] = {VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU, VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU};
    for (VkPhysicalDeviceType wanted : preference) {
        for (VkPhysicalDevice d : devices) {
            VkPhysicalDeviceProperties props;
            vkGetPhysicalDeviceProperties(d, &props);
            if (props.deviceType == wanted && !gGpu.physical) gGpu.physical = d;
        }
    }
    if (!gGpu.physical) return false;
    vkGetPhysicalDeviceMemoryProperties(gGpu.physical, &gGpu.memory);

    vkGetPhysicalDeviceQueueFamilyProperties(gGpu.physical, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    vkGetPhysicalDeviceQueueFamilyProperties(gGpu.physical, &count, families.data());
    for (uint32_t i = 0; i < count; i++) {
        if (families[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_TRANSFER_BIT)) {
            gGpu.family = i;
            break;
        }
    }

    const char *extensions[] = {
        VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
        VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
        VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME,
        VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
        VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
    };
    float priority = 1;
    VkDeviceQueueCreateInfo queueInfo = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueInfo.queueFamilyIndex = gGpu.family;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    VkDeviceCreateInfo deviceInfo = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = sizeof extensions / sizeof extensions[0];
    deviceInfo.ppEnabledExtensionNames = extensions;
    CHECK_VK(vkCreateDevice(gGpu.physical, &deviceInfo, nullptr, &gGpu.device));
#define LOAD_DEVICE(name) \
    if (auto f = (PFN_##name)vkGetDeviceProcAddr(gGpu.device, #name)) name = f;
    VK_FUNCTIONS(LOAD_DEVICE)
#undef LOAD_DEVICE
    vkGetDeviceQueue(gGpu.device, gGpu.family, 0, &gGpu.queue);

    VkCommandPoolCreateInfo poolInfo = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = gGpu.family;
    CHECK_VK(vkCreateCommandPool(gGpu.device, &poolInfo, nullptr, &gGpu.pool));
    VkCommandBufferAllocateInfo alloc = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    alloc.commandPool = gGpu.pool;
    alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    alloc.commandBufferCount = 1;
    CHECK_VK(vkAllocateCommandBuffers(gGpu.device, &alloc, &gGpu.commands));
    VkFenceCreateInfo fenceInfo = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    CHECK_VK(vkCreateFence(gGpu.device, &fenceInfo, nullptr, &gGpu.fence));
    VkSemaphoreCreateInfo semaphoreInfo = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    CHECK_VK(vkCreateSemaphore(gGpu.device, &semaphoreInfo, nullptr, &gGpu.painted));

    // Single-plane modifiers only: the compositor samples what it imports, and a
    // compressed layout's metadata planes would need ownership transfers on both sides.
    VkDrmFormatModifierPropertiesListEXT list = {VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_EXT};
    VkFormatProperties2 formatProps = {VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2, &list};
    vkGetPhysicalDeviceFormatProperties2(gGpu.physical, kFormat, &formatProps);
    std::vector<VkDrmFormatModifierPropertiesEXT> all(list.drmFormatModifierCount);
    list.pDrmFormatModifierProperties = all.data();
    vkGetPhysicalDeviceFormatProperties2(gGpu.physical, kFormat, &formatProps);
    const VkFormatFeatureFlags needed = VK_FORMAT_FEATURE_TRANSFER_DST_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    for (const auto &m : all)
        if (m.drmFormatModifierPlaneCount == 1 && (m.drmFormatModifierTilingFeatures & needed) == needed) gGpu.modifiers.push_back(m);
    return !gGpu.modifiers.empty();
}

uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags wanted) {
    for (uint32_t i = 0; i < gGpu.memory.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (gGpu.memory.memoryTypes[i].propertyFlags & wanted) == wanted) return i;
    for (uint32_t i = 0; i < gGpu.memory.memoryTypeCount; i++)
        if (bits & (1u << i)) return i;
    return 0;
}

struct Surface {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    int fd = -1;
    uint64_t size = 0;
    spike_plane plane = {};
    bool inUse = false;
};

uint64_t gModifier = DRM_FORMAT_MOD_LINEAR;

Surface createSurface(int width, int height) {
    Surface s;
    std::vector<uint64_t> modifiers;
    for (const auto &m : gGpu.modifiers) modifiers.push_back(m.drmFormatModifier);
    VkImageDrmFormatModifierListCreateInfoEXT modifierList = {VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_LIST_CREATE_INFO_EXT};
    modifierList.drmFormatModifierCount = (uint32_t)modifiers.size();
    modifierList.pDrmFormatModifiers = modifiers.data();
    VkExternalMemoryImageCreateInfo external = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, &modifierList};
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
    VkImageCreateInfo info = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &external};
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = kFormat;
    info.extent = {(uint32_t)width, (uint32_t)height, 1};
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT;
    info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    CHECK_VK(vkCreateImage(gGpu.device, &info, nullptr, &s.image));

    VkMemoryRequirements reqs;
    vkGetImageMemoryRequirements(gGpu.device, s.image, &reqs);
    VkMemoryDedicatedAllocateInfo dedicated = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image = s.image;
    VkExportMemoryAllocateInfo exportInfo = {VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, &dedicated};
    exportInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
    VkMemoryAllocateInfo alloc = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &exportInfo};
    alloc.allocationSize = reqs.size;
    alloc.memoryTypeIndex = memoryType(reqs.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    CHECK_VK(vkAllocateMemory(gGpu.device, &alloc, nullptr, &s.memory));
    CHECK_VK(vkBindImageMemory(gGpu.device, s.image, s.memory, 0));
    s.size = reqs.size;

    VkMemoryGetFdInfoKHR fdInfo = {VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR};
    fdInfo.memory = s.memory;
    fdInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
    CHECK_VK(vkGetMemoryFdKHR(gGpu.device, &fdInfo, &s.fd));

    VkImageDrmFormatModifierPropertiesEXT chosen = {VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_PROPERTIES_EXT};
    CHECK_VK(vkGetImageDrmFormatModifierPropertiesEXT(gGpu.device, s.image, &chosen));
    gModifier = chosen.drmFormatModifier;
    VkImageSubresource subresource = {VK_IMAGE_ASPECT_MEMORY_PLANE_0_BIT_EXT, 0, 0};
    VkSubresourceLayout layout;
    vkGetImageSubresourceLayout(gGpu.device, s.image, &subresource, &layout);
    s.plane.offset = (uint32_t)layout.offset;
    s.plane.stride = (uint32_t)layout.rowPitch;
    return s;
}

void destroySurface(Surface &s) {
    if (s.fd >= 0) close(s.fd);
    if (s.image) vkDestroyImage(gGpu.device, s.image, nullptr);
    if (s.memory) vkFreeMemory(gGpu.device, s.memory, nullptr);
    s = Surface();
}

/** CEF's pooled buffers, imported once each and recognised by the DMA-BUF's inode. */
struct Imported {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    int width = 0;
    int height = 0;
    uint64_t modifier = 0;
    uint64_t lastUsed = 0;
};
std::map<ino_t, Imported> gImported;
uint64_t gImportClock = 0;

void forget(Imported &i) {
    vkDestroyImage(gGpu.device, i.image, nullptr);
    vkFreeMemory(gGpu.device, i.memory, nullptr);
}

Imported *importPaint(const CefAcceleratedPaintInfo &info) {
    const auto &plane = info.planes[0];
    struct stat st;
    if (fstat(plane.fd, &st) != 0) return nullptr;
    int width = info.extra.coded_size.width;
    int height = info.extra.coded_size.height;
    auto found = gImported.find(st.st_ino);
    if (found != gImported.end()) {
        Imported &i = found->second;
        if (i.width == width && i.height == height && i.modifier == info.modifier) {
            i.lastUsed = ++gImportClock;
            return &i;
        }
        vkDeviceWaitIdle(gGpu.device);
        forget(i);
        gImported.erase(found);
    }
    if (gImported.size() >= 8) {
        auto oldest = gImported.begin();
        for (auto it = gImported.begin(); it != gImported.end(); ++it)
            if (it->second.lastUsed < oldest->second.lastUsed) oldest = it;
        vkDeviceWaitIdle(gGpu.device);
        forget(oldest->second);
        gImported.erase(oldest);
    }

    Imported i;
    i.width = width;
    i.height = height;
    i.modifier = info.modifier;
    VkSubresourceLayout layout = {};
    layout.offset = plane.offset;
    layout.rowPitch = plane.stride;
    VkImageDrmFormatModifierExplicitCreateInfoEXT explicitInfo = {VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT};
    explicitInfo.drmFormatModifier = info.modifier;
    explicitInfo.drmFormatModifierPlaneCount = 1;
    explicitInfo.pPlaneLayouts = &layout;
    VkExternalMemoryImageCreateInfo external = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, &explicitInfo};
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
    VkImageCreateInfo imageInfo = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &external};
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = info.format == CEF_COLOR_TYPE_RGBA_8888 ? VK_FORMAT_R8G8B8A8_UNORM : kFormat;
    imageInfo.extent = {(uint32_t)width, (uint32_t)height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(gGpu.device, &imageInfo, nullptr, &i.image) != VK_SUCCESS) return nullptr;

    int fd = dup(plane.fd);
    VkMemoryFdPropertiesKHR fdProps = {VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
    vkGetMemoryFdPropertiesKHR(gGpu.device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fd, &fdProps);
    VkMemoryRequirements reqs;
    vkGetImageMemoryRequirements(gGpu.device, i.image, &reqs);
    VkMemoryDedicatedAllocateInfo dedicated = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image = i.image;
    VkImportMemoryFdInfoKHR importInfo = {VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, &dedicated};
    importInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
    importInfo.fd = fd;
    VkMemoryAllocateInfo alloc = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &importInfo};
    alloc.allocationSize = std::max<uint64_t>(reqs.size, plane.size);
    alloc.memoryTypeIndex = memoryType(reqs.memoryTypeBits & fdProps.memoryTypeBits, 0);
    if (vkAllocateMemory(gGpu.device, &alloc, nullptr, &i.memory) != VK_SUCCESS) {
        close(fd);
        vkDestroyImage(gGpu.device, i.image, nullptr);
        return nullptr;
    }
    vkBindImageMemory(gGpu.device, i.image, i.memory, 0);
    i.lastUsed = ++gImportClock;
    return &(gImported[st.st_ino] = i);
}

/** Waits, on the GPU, for whatever CEF's GPU process still has writing to [fd]. */
bool waitForWriters(int fd) {
    dma_buf_export_sync_file exported = {};
    exported.flags = DMA_BUF_SYNC_READ;
    if (ioctl(fd, DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &exported) != 0) return false;
    VkImportSemaphoreFdInfoKHR import = {VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR};
    import.semaphore = gGpu.painted;
    import.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
    import.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    import.fd = exported.fd;
    if (vkImportSemaphoreFdKHR(gGpu.device, &import) != VK_SUCCESS) {
        close(exported.fd);
        return false;
    }
    return true;
}

void barrier(VkImage image, VkImageLayout from, VkImageLayout to, uint32_t fromFamily, uint32_t toFamily,
             VkAccessFlags srcAccess, VkAccessFlags dstAccess) {
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = srcAccess;
    b.dstAccessMask = dstAccess;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = fromFamily;
    b.dstQueueFamilyIndex = toFamily;
    b.image = image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(gGpu.commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0,
                         nullptr, 1, &b);
}

/** Copies [visible] of [source] into the top left of [target], and waits for it. */
void copy(Imported &source, const cef_rect_t &visible, Surface &target, int width, int height, bool waited) {
    vkResetCommandBuffer(gGpu.commands, 0);
    VkCommandBufferBeginInfo begin = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(gGpu.commands, &begin);
    barrier(source.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_QUEUE_FAMILY_FOREIGN_EXT,
            gGpu.family, 0, VK_ACCESS_TRANSFER_READ_BIT);
    barrier(target.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED,
            VK_QUEUE_FAMILY_IGNORED, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
    VkImageCopy region = {};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.dstSubresource = region.srcSubresource;
    region.srcOffset = {visible.x, visible.y, 0};
    region.extent = {(uint32_t)std::min(visible.width, width), (uint32_t)std::min(visible.height, height), 1};
    vkCmdCopyImage(gGpu.commands, source.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target.image,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    barrier(source.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, gGpu.family,
            VK_QUEUE_FAMILY_FOREIGN_EXT, VK_ACCESS_TRANSFER_READ_BIT, 0);
    barrier(target.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, gGpu.family,
            VK_QUEUE_FAMILY_FOREIGN_EXT, VK_ACCESS_TRANSFER_WRITE_BIT, 0);
    vkEndCommandBuffer(gGpu.commands);

    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo submit = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = waited ? 1 : 0;
    submit.pWaitSemaphores = &gGpu.painted;
    submit.pWaitDstStageMask = &stage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &gGpu.commands;
    CHECK_VK(vkQueueSubmit(gGpu.queue, 1, &submit, gGpu.fence));
    vkWaitForFences(gGpu.device, 1, &gGpu.fence, VK_TRUE, UINT64_MAX);
    vkResetFences(gGpu.device, 1, &gGpu.fence);
}

int gSocket = -1;
void requestQuit();
std::mutex gSendLock;
double gMarginMs = 10;
uint32_t gSurfaceCount = 4;

void sendPacket(const void *data, size_t size, const int *fds = nullptr, uint32_t fdCount = 0) {
    std::lock_guard<std::mutex> lock(gSendLock);
    iovec iov = {const_cast<void *>(data), size};
    msghdr msg = {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    std::vector<char> control;
    if (fdCount) {
        control.resize(CMSG_SPACE(sizeof(int) * fdCount));
        msg.msg_control = control.data();
        msg.msg_controllen = control.size();
        cmsghdr *c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int) * fdCount);
        memcpy(CMSG_DATA(c), fds, sizeof(int) * fdCount);
    }
    if (sendmsg(gSocket, &msg, MSG_NOSIGNAL) < 0) CefPostTask(TID_UI, base::BindOnce(&requestQuit));
}

struct Panel {
    uint32_t number = 0;
    int width = 0;
    int height = 0;
    std::string url;
    Surface surfaces[SPIKE_MAX_SURFACES];
    uint32_t generation = 0;
    uint32_t next = 0;
    uint64_t seq = 0;
    uint64_t begun = 0;
    CefRefPtr<CefBrowser> browser;
    uint32_t paints = 0;
    uint32_t busy = 0;
    bool resizing = false;
    std::string origin;
    std::string root;
    std::string script;
    bool isPrivate = false;
    producer::Page page;
    bool closing = false;
    producer::Window beginToPaint;
    producer::Window blit;
};

std::map<uint32_t, Panel *> gPanels;
bool gContextReady = false;
bool gQuitting = false;
std::string gWebRoot;

Panel *findPanel(uint32_t number) {
    auto it = gPanels.find(number);
    return it == gPanels.end() || it->second->closing ? nullptr : it->second;
}

std::vector<Panel *> livePanels() {
    std::vector<Panel *> live;
    for (auto &entry : gPanels)
        if (!entry.second->closing) live.push_back(entry.second);
    return live;
}

void freeSurfaces(Panel *p) {
    vkDeviceWaitIdle(gGpu.device);
    for (uint32_t i = 0; i < gSurfaceCount; i++) destroySurface(p->surfaces[i]);
}

void allocateSurfaces(Panel *p) {
    for (uint32_t i = 0; i < gSurfaceCount; i++) p->surfaces[i] = createSurface(p->width, p->height);
}

void sendHello(Panel *p) {
    spike_hello hello = {};
    hello.id = SPIKE_MSG_HELLO;
    hello.panel = p->number;
    hello.count = gSurfaceCount;
    hello.width = (uint32_t)p->width;
    hello.height = (uint32_t)p->height;
    hello.fourcc = kFourcc;
    hello.planes = 1;
    hello.generation = p->generation;
    hello.modifier = gModifier;
    int fds[SPIKE_MAX_SURFACES];
    for (uint32_t i = 0; i < gSurfaceCount; i++) {
        hello.sizes[i] = p->surfaces[i].size;
        hello.layout[i][0] = p->surfaces[i].plane;
        fds[i] = p->surfaces[i].fd;
    }
    sendPacket(&hello, sizeof hello, fds, gSurfaceCount);
}

void sendCursor(Panel *p, const char *name) {
    spike_cursor c = {};
    c.id = SPIKE_MSG_CURSOR;
    c.panel = p->number;
    snprintf(c.name, sizeof c.name, "%s", name);
    sendPacket(&c, sizeof c);
}

void sendPage(Panel *p, const producer::Page &page) {
    if (page == p->page) return;
    p->page = page;
    spike_page g = {};
    g.id = SPIKE_MSG_PAGE;
    g.panel = p->number;
    g.flags = page.flags;
    snprintf(g.title, sizeof g.title, "%s", page.title.c_str());
    snprintf(g.url, sizeof g.url, "%s", page.url.c_str());
    sendPacket(&g, sizeof g);
}

void sendPopup(Panel *p, const std::string &url, bool background) {
    spike_popup u = {};
    u.id = SPIKE_MSG_POPUP;
    u.panel = p->number;
    u.background = background ? 1 : 0;
    snprintf(u.url, sizeof u.url, "%s", url.c_str());
    sendPacket(&u, sizeof u);
}

template <typename Message>
void sendMessage(Message &m, uint32_t id) {
    m.id = id;
    sendPacket(&m, sizeof m);
}

void sendContext(Panel *p, int x, int y, const producer::Context &context) {
    spike_context c = {};
    c.panel = p->number;
    c.flags = context.flags;
    c.media = context.media;
    c.x = (float)x;
    c.y = (float)y;
    snprintf(c.link, sizeof c.link, "%s", context.link.c_str());
    snprintf(c.source, sizeof c.source, "%s", context.source.c_str());
    snprintf(c.selection, sizeof c.selection, "%s", context.selection.c_str());
    sendMessage(c, SPIKE_MSG_CONTEXT);
}

const double DOWNLOAD_PROGRESS_MS = 250;

// Downloads in flight, by CEF's id: the callback that says where one goes, until it is told, and the one that stops it.
struct Download {
    uint32_t panel = 0;
    CefRefPtr<CefBeforeDownloadCallback> before;
    CefRefPtr<CefDownloadItemCallback> item;
    uint64_t lastSent = 0;
    uint32_t lastState = 0;
};
std::map<uint32_t, Download> gDownloads;

void sendDownload(uint32_t panel, CefRefPtr<CefDownloadItem> item, uint32_t state, const std::string &name) {
    spike_download d = {};
    d.panel = panel;
    d.download = item->GetId();
    d.state = state;
    d.received = item->GetReceivedBytes();
    d.total = item->GetTotalBytes();
    snprintf(d.url, sizeof d.url, "%s", item->GetURL().ToString().c_str());
    snprintf(d.name, sizeof d.name, "%s", name.c_str());
    snprintf(d.mime, sizeof d.mime, "%s", item->GetMimeType().ToString().c_str());
    snprintf(d.path, sizeof d.path, "%s", item->GetFullPath().ToString().c_str());
    sendMessage(d, SPIKE_MSG_DOWNLOAD);
}

void handleDownloadControl(const spike_download_control &c) {
    auto it = gDownloads.find(c.download);
    if (it == gDownloads.end()) return;
    Download &d = it->second;
    std::string path = producer::text(c.path, sizeof c.path);
    if (c.cancel) {
        if (d.item) d.item->Cancel();
        d.before = nullptr;
        if (!d.item) gDownloads.erase(it);
        return;
    }
    if (d.before && !path.empty()) {
        d.before->Continue(path, false);
        d.before = nullptr;
    }
}

// Permission requests waiting on the wearer, by the number given them here.
struct Prompt {
    uint32_t panel = 0;
    std::string kind;
    std::string origin;
    uint64_t cefId = 0;
    CefRefPtr<CefPermissionPromptCallback> permission;
    CefRefPtr<CefMediaAccessCallback> media;
    uint32_t mediaRequested = 0;
    std::string popup;
    bool popupBackground = false;
};
std::map<uint32_t, Prompt> gPrompts;
uint32_t gNextPrompt = 1;

void sendPrompt(uint32_t panel, uint32_t id, const std::string &kind, const std::string &host, uint32_t flags) {
    spike_prompt q = {};
    q.panel = panel;
    q.prompt = id;
    q.flags = flags;
    snprintf(q.kind, sizeof q.kind, "%s", kind.c_str());
    snprintf(q.host, sizeof q.host, "%s", host.c_str());
    sendMessage(q, SPIKE_MSG_PROMPT);
}

uint32_t ask(Panel *p, Prompt prompt, bool rememberable) {
    uint32_t id = gNextPrompt++;
    prompt.panel = p->number;
    sendPrompt(p->number, id, prompt.kind, producer::hostOf(prompt.origin), rememberable ? SPIKE_PROMPT_REMEMBERABLE : 0);
    gPrompts[id] = prompt;
    return id;
}

void answer(Prompt &prompt, bool allow, bool remember);

/**
 * Denies what [panel]'s page still waits on, as Android does when a page starts or its tab closes. Each prompt
 * leaves the map before it is answered: CEF calls OnDismissPermissionPrompt from inside Continue.
 */
void denyPrompts(uint32_t panel) {
    std::vector<uint32_t> ids;
    for (auto &entry : gPrompts)
        if (entry.second.panel == panel) ids.push_back(entry.first);
    for (uint32_t id : ids) {
        auto it = gPrompts.find(id);
        if (it == gPrompts.end()) continue;
        Prompt prompt = it->second;
        gPrompts.erase(it);
        answer(prompt, false, false);
        sendPrompt(panel, id, "", "", SPIKE_PROMPT_GONE);
    }
}

void sendFullscreen(Panel *p, bool fullscreen) {
    spike_fullscreen f = {};
    f.panel = p->number;
    f.fullscreen = fullscreen ? 1 : 0;
    sendMessage(f, SPIKE_MSG_FULLSCREEN);
}

void sendScroll(Panel *p, double y) {
    spike_scroll c = {};
    c.panel = p->number;
    c.y = (float)y;
    sendMessage(c, SPIKE_MSG_SCROLL);
}

void deliver(Panel *p, const CefAcceleratedPaintInfo &info) {
    uint64_t painted = nowNs();
    p->paints++;
    if (p->begun) p->beginToPaint.add(millis(painted - p->begun));
    if (info.plane_count != 1) {
        static bool warned;
        if (!warned) fprintf(stderr, "producer: paints with %d planes are not handled\n", info.plane_count);
        warned = true;
        return;
    }
    const cef_rect_t &visible = info.extra.visible_rect;
    if (p->resizing) {
        if (visible.width != p->width || visible.height != p->height) return;
        p->resizing = false;
    }
    uint32_t index = UINT32_MAX;
    for (uint32_t k = 0; k < gSurfaceCount; k++) {
        uint32_t i = (p->next + k) % gSurfaceCount;
        if (!p->surfaces[i].inUse) {
            index = i;
            break;
        }
    }
    if (index == UINT32_MAX) {
        p->busy++;
        return;
    }
    Imported *source = importPaint(info);
    if (!source) {
        static bool warned;
        if (!warned) fprintf(stderr, "producer: could not import a paint (modifier %#llx)\n", (unsigned long long)info.modifier);
        warned = true;
        return;
    }
    copy(*source, visible, p->surfaces[index], p->width, p->height, waitForWriters(info.planes[0].fd));
    uint64_t ready = nowNs();
    p->blit.add(millis(ready - painted));
    p->next = (index + 1) % gSurfaceCount;
    p->surfaces[index].inUse = true;

    spike_frame f = {};
    f.id = SPIKE_MSG_FRAME;
    f.panel = p->number;
    f.index = index;
    f.generation = p->generation;
    f.seq = ++p->seq;
    f.begun = p->begun;
    f.painted = painted;
    f.ready = ready;
    sendPacket(&f, sizeof f);
}

class PanelClient : public CefClient, public CefRenderHandler, public CefLifeSpanHandler, public CefDisplayHandler, public CefLoadHandler,
                    public CefContextMenuHandler, public CefDownloadHandler, public CefPermissionHandler {
public:
    explicit PanelClient(Panel *panel) : panel_(panel) {}

    CefRefPtr<CefContextMenuHandler> GetContextMenuHandler() override { return this; }
    CefRefPtr<CefDownloadHandler> GetDownloadHandler() override { return this; }
    CefRefPtr<CefPermissionHandler> GetPermissionHandler() override { return this; }

    CefRefPtr<CefRenderHandler> GetRenderHandler() override { return this; }
    CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
    CefRefPtr<CefDisplayHandler> GetDisplayHandler() override { return this; }
    CefRefPtr<CefLoadHandler> GetLoadHandler() override { return this; }

    void GetViewRect(CefRefPtr<CefBrowser>, CefRect &rect) override { rect = CefRect(0, 0, panel_->width, panel_->height); }

    void OnPaint(CefRefPtr<CefBrowser>, PaintElementType, const RectList &, const void *, int, int) override {
        static bool warned;
        if (!warned) fprintf(stderr, "producer: got a software paint; shared textures are not in use\n");
        warned = true;
    }

    void OnAcceleratedPaint(CefRefPtr<CefBrowser>, PaintElementType type, const RectList &,
                            const CefAcceleratedPaintInfo &info) override {
        if (type == PET_VIEW) deliver(panel_, info);
    }

    void OnAfterCreated(CefRefPtr<CefBrowser> browser) override {
        panel_->browser = browser;
        if (panel_->closing) browser->GetHost()->CloseBrowser(true);
    }

    void OnBeforeClose(CefRefPtr<CefBrowser>) override {
        panel_->browser = nullptr;
        if (!panel_->closing) return;
        gPanels.erase(panel_->number);
        freeSurfaces(panel_);
        delete panel_;
        panel_ = nullptr;
        if (gQuitting && gPanels.empty()) CefQuitMessageLoop();
    }

    void OnLoadEnd(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame, int) override {
        if (panel_) producer::injectBridge(frame, panel_->origin, panel_->script);
    }

    void OnTitleChange(CefRefPtr<CefBrowser>, const CefString &title) override {
        if (!panel_) return;
        producer::Page page = panel_->page;
        page.title = title.ToString();
        sendPage(panel_, page);
    }

    void OnAddressChange(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame, const CefString &url) override {
        if (!panel_ || !frame->IsMain()) return;
        producer::Page page = panel_->page;
        page.url = url.ToString();
        sendPage(panel_, page);
    }

    void OnLoadingStateChange(CefRefPtr<CefBrowser>, bool isLoading, bool canGoBack, bool canGoForward) override {
        if (!panel_) return;
        producer::Page page = panel_->page;
        page.flags = producer::pageFlags(isLoading, canGoBack, canGoForward);
        sendPage(panel_, page);
    }

    // A new window or tab is the compositor's to open, as a tab of the same window. One the wearer did not ask for
    // waits on a popup prompt, as Gecko's popup blocker makes it on Android.
    bool OnBeforePopup(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame>, int, const CefString &url, const CefString &,
                       WindowOpenDisposition disposition, bool userGesture, const CefPopupFeatures &, CefWindowInfo &,
                       CefRefPtr<CefClient> &, CefBrowserSettings &, CefRefPtr<CefDictionaryValue> &, bool *) override {
        if (!panel_) return true;
        if (userGesture) {
            sendPopup(panel_, url.ToString(), producer::inBackground(disposition));
            return true;
        }
        Prompt prompt;
        prompt.kind = "popup";
        prompt.origin = browser->GetMainFrame()->GetURL().ToString();
        prompt.popup = url.ToString();
        prompt.popupBackground = producer::inBackground(disposition);
        ask(panel_, prompt, false);
        return true;
    }

    // The shell shows the menu, as Android's PageMenu does; Chromium's own has nowhere to appear.
    bool RunContextMenu(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame>, CefRefPtr<CefContextMenuParams> params,
                        CefRefPtr<CefMenuModel>, CefRefPtr<CefRunContextMenuCallback> callback) override {
        if (panel_) sendContext(panel_, params->GetXCoord(), params->GetYCoord(), producer::contextOf(params));
        callback->Cancel();
        return true;
    }

    bool OnBeforeDownload(CefRefPtr<CefBrowser>, CefRefPtr<CefDownloadItem> item, const CefString &suggested,
                          CefRefPtr<CefBeforeDownloadCallback> callback) override {
        if (!panel_) return false;
        Download &d = gDownloads[item->GetId()];
        d.panel = panel_->number;
        d.before = callback;
        d.lastState = SPIKE_DOWNLOAD_STARTED;
        sendDownload(panel_->number, item, SPIKE_DOWNLOAD_STARTED, suggested.ToString());
        return true;
    }

    void OnDownloadUpdated(CefRefPtr<CefBrowser>, CefRefPtr<CefDownloadItem> item, CefRefPtr<CefDownloadItemCallback> callback) override {
        auto it = gDownloads.find(item->GetId());
        if (it == gDownloads.end() || it->second.before) return;
        Download &d = it->second;
        d.item = callback;
        uint32_t state = producer::downloadState(item);
        uint64_t now = nowNs();
        bool final = state != SPIKE_DOWNLOAD_PROGRESS;
        if (!final && d.lastState == SPIKE_DOWNLOAD_PROGRESS && millis(now - d.lastSent) < DOWNLOAD_PROGRESS_MS) return;
        d.lastSent = now;
        d.lastState = state;
        std::string path = item->GetFullPath().ToString();
        sendDownload(d.panel, item, state, path.substr(path.find_last_of('/') + 1));
        if (final) gDownloads.erase(it);
    }

    bool OnShowPermissionPrompt(CefRefPtr<CefBrowser>, uint64_t promptId, const CefString &origin, uint32_t requested,
                                CefRefPtr<CefPermissionPromptCallback> callback) override {
        std::string kind = producer::permissionKind(requested);
        if (!panel_ || kind.empty()) {
            callback->Continue(CEF_PERMISSION_RESULT_DENY);
            return true;
        }
        Prompt prompt;
        prompt.kind = kind;
        prompt.origin = origin.ToString();
        prompt.cefId = promptId;
        prompt.permission = callback;
        ask(panel_, prompt, kind != "clipboard" && !panel_->isPrivate);
        return true;
    }

    void OnDismissPermissionPrompt(CefRefPtr<CefBrowser>, uint64_t promptId, cef_permission_request_result_t) override {
        for (auto it = gPrompts.begin(); it != gPrompts.end(); ++it) {
            if (it->second.cefId != promptId || !it->second.permission) continue;
            sendPrompt(it->second.panel, it->first, "", "", SPIKE_PROMPT_GONE);
            gPrompts.erase(it);
            return;
        }
    }

    // The microphone alone, as on Android; a camera is refused.
    bool OnRequestMediaAccessPermission(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame>, const CefString &origin,
                                        uint32_t requested, CefRefPtr<CefMediaAccessCallback> callback) override {
        uint32_t video = CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE | CEF_MEDIA_PERMISSION_DESKTOP_VIDEO_CAPTURE;
        if (!panel_ || (requested & video) || !(requested & CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE)) {
            callback->Cancel();
            return true;
        }
        Prompt prompt;
        prompt.kind = "microphone";
        prompt.origin = origin.ToString();
        prompt.media = callback;
        prompt.mediaRequested = requested;
        ask(panel_, prompt, false);
        return true;
    }

    void OnLoadStart(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame, TransitionType) override {
        if (panel_ && frame->IsMain()) denyPrompts(panel_->number);
    }

    void OnFullscreenModeChange(CefRefPtr<CefBrowser>, bool fullscreen) override {
        if (panel_) sendFullscreen(panel_, fullscreen);
    }

    void OnScrollOffsetChanged(CefRefPtr<CefBrowser>, double, double y) override {
        if (panel_) sendScroll(panel_, y);
    }

    bool OnCursorChange(CefRefPtr<CefBrowser>, CefCursorHandle, cef_cursor_type_t type, const CefCursorInfo &) override {
        const char *name = producer::cssCursor(type);
        if (name != cursor_) {
            cursor_ = name;
            sendCursor(panel_, name);
        }
        return true;
    }

    bool OnConsoleMessage(CefRefPtr<CefBrowser>, cef_log_severity_t, const CefString &message, const CefString &,
                          int) override {
        fprintf(stderr, "panel %u console: %s\n", panel_->number, message.ToString().c_str());
        return true;
    }

private:
    Panel *panel_;
    const char *cursor_ = "default";
    IMPLEMENT_REFCOUNTING(PanelClient);
};

void createBrowser(Panel *p) {
    producer::serveOrigin(p->origin, p->root);
    producer::allowBridge(p->origin, p->script);
    CefWindowInfo info;
    info.SetAsWindowless(0);
    info.shared_texture_enabled = true;
    info.external_begin_frame_enabled = true;
    CefBrowserSettings settings;
    settings.windowless_frame_rate = 60;
    CefBrowserHost::CreateBrowser(info, new PanelClient(p), p->url, settings, nullptr, producer::contextFor(p->isPrivate));
}

class ProducerApp : public CefApp, public CefBrowserProcessHandler {
public:
    CefRefPtr<CefBrowserProcessHandler> GetBrowserProcessHandler() override { return this; }

    void OnBeforeCommandLineProcessing(const CefString &, CefRefPtr<CefCommandLine> commandLine) override {
        producer::commonSwitches(commandLine);
        // Keeps Chromium off the desktop's keyring.
        commandLine->AppendSwitchWithValue("password-store", "basic");
    }

    void OnContextInitialized() override {
        producer::serveWebRoot(gWebRoot);
        producer::allowExtensionsInPrivate();
        gContextReady = true;
        for (Panel *p : livePanels()) createBrowser(p);
    }

private:
    IMPLEMENT_REFCOUNTING(ProducerApp);
};

// XKB keycodes (evdev + 8), as GLFW reports scancodes, to the Windows key codes CEF dispatches on.
int windowsKeyCode(uint32_t xkb) {
    static const uint8_t table[] = {
        /*  0 */ 0, 0x1B, '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', 0xBD, 0xBB, 0x08, 0x09,
        /* 16 */ 'Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P', 0xDB, 0xDD, 0x0D, 0x11, 'A', 'S',
        /* 32 */ 'D', 'F', 'G', 'H', 'J', 'K', 'L', 0xBA, 0xDE, 0xC0, 0x10, 0xDC, 'Z', 'X', 'C', 'V',
        /* 48 */ 'B', 'N', 'M', 0xBC, 0xBE, 0xBF, 0x10, 0x6A, 0x12, 0x20, 0x14, 0x70, 0x71, 0x72, 0x73, 0x74,
        /* 64 */ 0x75, 0x76, 0x77, 0x78, 0x79, 0x90, 0x91, 0x67, 0x68, 0x69, 0x6D, 0x64, 0x65, 0x66, 0x6B, 0x61,
        /* 80 */ 0x62, 0x63, 0x60, 0x6E, 0, 0, 0xE2, 0x7A, 0x7B, 0, 0, 0, 0, 0, 0, 0,
        /* 96 */ 0x0D, 0x11, 0x6F, 0, 0x12, 0, 0x24, 0x26, 0x21, 0x25, 0x27, 0x23, 0x28, 0x22, 0x2D, 0x2E,
        /*112 */ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x5B, 0x5C, 0x5D,
    };
    uint32_t evdev = xkb - 8;
    return evdev < sizeof table ? table[evdev] : 0;
}

/** The compositor sends AppKit's characters; Linux's CEF wants plain control characters, and none for arrows and the like. */
uint16_t linuxCharacter(uint16_t c) {
    if (c == 0x7F) return 0x08;
    if (c == 0x03) return 0x0D;
    if (c >= 0xF700 && c <= 0xF8FF) return 0;
    return c;
}

void focus(Panel *target) {
    for (Panel *p : livePanels())
        if (p->browser) p->browser->GetHost()->SetFocus(p == target);
}

void handleInput(spike_input in) {
    Panel *p = findPanel(in.panel);
    if (!p || !p->browser) return;
    if (in.kind == SPIKE_INPUT_DOWN || in.kind == SPIKE_INPUT_FOCUS) focus(p);
    if (in.kind == SPIKE_INPUT_FOCUS) return;
    producer::dispatchInput(p->browser->GetHost(), in, [](const spike_input &key, CefKeyEvent &event) {
        uint16_t character = linuxCharacter(key.character);
        event.native_key_code = (int)key.keyCode;
        event.windows_key_code = windowsKeyCode(key.keyCode);
        event.character = character;
        event.unmodified_character = linuxCharacter(key.unmodified);
        return character != 0;
    });
}

void handleResize(spike_resize r) {
    Panel *p = findPanel(r.panel);
    if (!p || r.width == 0 || r.height == 0) return;
    if ((int)r.width == p->width && (int)r.height == p->height) return;
    freeSurfaces(p);
    p->width = (int)r.width;
    p->height = (int)r.height;
    p->generation++;
    p->next = 0;
    p->resizing = true;
    allocateSurfaces(p);
    sendHello(p);
    if (p->browser) p->browser->GetHost()->WasResized();
}

void handleRelease(spike_release r) {
    Panel *p = findPanel(r.panel);
    if (p && r.generation == p->generation && r.index < gSurfaceCount) p->surfaces[r.index].inUse = false;
}

void handleOpen(const spike_open &o) {
    if (gPanels.count(o.panel) || o.width == 0 || o.height == 0) return;
    Panel *p = new Panel;
    p->number = o.panel;
    p->width = (int)o.width;
    p->height = (int)o.height;
    p->url = producer::text(o.url, sizeof o.url);
    p->origin = producer::text(o.origin, sizeof o.origin);
    p->root = producer::text(o.root, sizeof o.root);
    p->script = producer::text(o.script, sizeof o.script);
    p->isPrivate = (o.flags & SPIKE_OPEN_PRIVATE) != 0;
    gPanels[p->number] = p;
    allocateSurfaces(p);
    sendHello(p);
    if (gContextReady) createBrowser(p);
}

void handleCommand(const spike_command &c) {
    Panel *p = findPanel(c.panel);
    if (p && p->browser) producer::runCommand(p->browser, c.kind, producer::text(c.text, sizeof c.text));
}

void answer(Prompt &prompt, bool allow, bool remember) {
    if (prompt.permission) {
        prompt.permission->Continue(allow ? CEF_PERMISSION_RESULT_ACCEPT : CEF_PERMISSION_RESULT_DENY);
        cef_content_setting_types_t type;
        if (remember && producer::rememberedAs(prompt.kind, type)) {
            CefRequestContext::GetGlobalContext()->SetContentSetting(prompt.origin, prompt.origin, type,
                allow ? CEF_CONTENT_SETTING_VALUE_ALLOW : CEF_CONTENT_SETTING_VALUE_BLOCK);
        }
    } else if (prompt.media) {
        if (allow) prompt.media->Continue(prompt.mediaRequested); else prompt.media->Cancel();
    } else if (!prompt.popup.empty() && allow) {
        if (Panel *p = findPanel(prompt.panel)) sendPopup(p, prompt.popup, prompt.popupBackground);
    }
}

void handlePromptAnswer(spike_prompt_answer a) {
    auto it = gPrompts.find(a.prompt);
    if (it == gPrompts.end() || it->second.panel != a.panel) return;
    Prompt prompt = it->second;
    gPrompts.erase(it);
    answer(prompt, a.allow != 0, a.remember != 0);
}

void handleClose(spike_close c) {
    Panel *p = findPanel(c.panel);
    if (!p) return;
    denyPrompts(p->number);
    p->closing = true;
    if (p->browser) p->browser->GetHost()->CloseBrowser(true);
}

/** CEF must close every browser before it shuts down, or CefShutdown never returns. */
void requestQuit() {
    if (gQuitting) return;
    gQuitting = true;
    bool waiting = false;
    for (auto &entry : gPanels) {
        Panel *p = entry.second;
        p->closing = true;
        if (p->browser) {
            p->browser->GetHost()->CloseBrowser(true);
            waiting = true;
        }
    }
    if (!waiting) CefQuitMessageLoop();
    else CefPostDelayedTask(TID_UI, base::BindOnce(&CefQuitMessageLoop), 3000);
}

void beginFrame() {
    uint64_t now = nowNs();
    for (Panel *p : livePanels()) {
        if (!p->browser) continue;
        p->begun = now;
        p->browser->GetHost()->SendExternalBeginFrame();
    }
}

/** Turns each tick into a begin-frame [gMarginMs] before its target, on its own thread for the timing. */
struct BeginTimer {
    std::mutex lock;
    std::condition_variable wake;
    uint64_t target = 0;
    bool stopping = false;

    void schedule(uint64_t tickTarget) {
        std::lock_guard<std::mutex> l(lock);
        target = tickTarget - (uint64_t)(gMarginMs * 1e6);
        wake.notify_one();
    }

    void run() {
        std::unique_lock<std::mutex> l(lock);
        while (!stopping) {
            if (target == 0) {
                wake.wait(l);
                continue;
            }
            uint64_t at = target;
            uint64_t now = nowNs();
            if (at > now) {
                l.unlock();
                timespec ts = {(time_t)(at / 1000000000ull), (long)(at % 1000000000ull)};
                clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, nullptr);
                l.lock();
                if (target != at) continue;
            }
            target = 0;
            CefPostTask(TID_UI, base::BindOnce(&beginFrame));
        }
    }
} gTimer;

void receive() {
    union Packet {
        uint32_t id;
        spike_tick tick;
        spike_input input;
        spike_resize resize;
        spike_release release;
        spike_open open;
        spike_close close;
        spike_command command;
        spike_download_control downloadControl;
        spike_prompt_answer promptAnswer;
    };
    std::vector<char> buffer(sizeof(Packet));
    for (;;) {
        ssize_t n = recv(gSocket, buffer.data(), buffer.size(), 0);
        if (n <= 0) break;
        Packet &in = *reinterpret_cast<Packet *>(buffer.data());
        switch (in.id) {
            case SPIKE_MSG_TICK: gTimer.schedule(in.tick.target); break;
            case SPIKE_MSG_INPUT: CefPostTask(TID_UI, base::BindOnce(&handleInput, in.input)); break;
            case SPIKE_MSG_RESIZE: CefPostTask(TID_UI, base::BindOnce(&handleResize, in.resize)); break;
            case SPIKE_MSG_RELEASE: CefPostTask(TID_UI, base::BindOnce(&handleRelease, in.release)); break;
            case SPIKE_MSG_CLOSE: CefPostTask(TID_UI, base::BindOnce(&handleClose, in.close)); break;
            case SPIKE_MSG_PROMPT_ANSWER: CefPostTask(TID_UI, base::BindOnce(&handlePromptAnswer, in.promptAnswer)); break;
            case SPIKE_MSG_DOWNLOAD_CONTROL: {
                auto control = std::make_shared<spike_download_control>(in.downloadControl);
                CefPostTask(TID_UI, base::BindOnce([](std::shared_ptr<spike_download_control> c) { handleDownloadControl(*c); }, control));
                break;
            }
            case SPIKE_MSG_COMMAND: {
                auto command = std::make_shared<spike_command>(in.command);
                CefPostTask(TID_UI, base::BindOnce([](std::shared_ptr<spike_command> c) { handleCommand(*c); }, command));
                break;
            }
            case SPIKE_MSG_OPEN: {
                auto open = std::make_shared<spike_open>(in.open);
                CefPostTask(TID_UI, base::BindOnce([](std::shared_ptr<spike_open> o) { handleOpen(*o); }, open));
                break;
            }
        }
    }
    CefPostTask(TID_UI, base::BindOnce(&requestQuit));
}

void report() {
    for (Panel *p : livePanels()) {
        fprintf(stderr, "producer: panel %u %u paints, begin->paint p50 %.2f p95 %.2f ms, copy p50 %.2f p95 %.2f ms, %u busy\n",
                p->number, p->paints, p->beginToPaint.percentile(0.5), p->beginToPaint.percentile(0.95),
                p->blit.percentile(0.5), p->blit.percentile(0.95), p->busy);
        p->paints = p->busy = 0;
        p->beginToPaint.values.clear();
        p->blit.values.clear();
    }
    CefPostDelayedTask(TID_UI, base::BindOnce(&report), 2000);
}

}  // namespace

// Chromium re-seeds the stack guard in forked children, so main's own canary no longer matches when it returns.
[[gnu::no_stack_protector]] int main(int argc, char *argv[]) {
    CefMainArgs mainArgs(argc, argv);
    int code = CefExecuteProcess(mainArgs, nullptr, nullptr);
    if (code >= 0) return code;

    CefRefPtr<CefCommandLine> commandLine = CefCommandLine::CreateCommandLine();
    commandLine->InitFromArgv(argc, argv);
    std::string path = commandLine->GetSwitchValue("spike-socket").ToString();
    std::vector<producer::PanelSpec> specs;
    std::string panels = commandLine->GetSwitchValue("spike-panels").ToString();
    if (path.empty() || (!panels.empty() && !producer::parsePanels(panels, specs))) {
        fprintf(stderr, "usage: producer --spike-socket=<path> [--spike-panels=WxH@url[,WxH@url...]] [--spike-margin=ms] [--spike-surfaces=n] [--halone-web-root=dir]\n");
        return 2;
    }
    if (commandLine->HasSwitch("spike-margin")) gMarginMs = std::stod(commandLine->GetSwitchValue("spike-margin").ToString());
    if (commandLine->HasSwitch("spike-surfaces"))
        gSurfaceCount = (uint32_t)std::clamp(std::stoi(commandLine->GetSwitchValue("spike-surfaces").ToString()), 2, SPIKE_MAX_SURFACES);
    gWebRoot = commandLine->GetSwitchValue("halone-web-root").ToString();

    gSocket = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    sockaddr_un address = {};
    address.sun_family = AF_UNIX;
    snprintf(address.sun_path, sizeof address.sun_path, "%s", path.c_str());
    if (connect(gSocket, (sockaddr *)&address, sizeof address) != 0) {
        fprintf(stderr, "producer: could not connect to %s: %s\n", path.c_str(), strerror(errno));
        return 1;
    }
    if (!initVulkan()) {
        fprintf(stderr, "producer: no Vulkan device with DMA-BUF export\n");
        return 1;
    }
    for (const producer::PanelSpec &s : specs) {
        Panel *p = new Panel;
        p->number = s.number;
        p->width = s.width;
        p->height = s.height;
        p->url = s.url;
        gPanels[p->number] = p;
        allocateSurfaces(p);
        sendHello(p);
    }

    static int termPipe[2];
    if (pipe2(termPipe, O_CLOEXEC) != 0) return 1;
    signal(SIGTERM, [](int) { (void)!write(termPipe[1], "q", 1); });

    CefSettings settings;
    settings.no_sandbox = true;
    settings.windowless_rendering_enabled = true;
    settings.log_severity = LOGSEVERITY_ERROR;
    CefString(&settings.root_cache_path) = "/tmp/halone-cef-producer";
    CefRefPtr<ProducerApp> app(new ProducerApp);
    if (!CefInitialize(mainArgs, settings, app.get(), nullptr)) return CefGetExitCode();

    std::thread([] { gTimer.run(); }).detach();
    std::thread(receive).detach();
    std::thread([] {
        char c;
        if (read(termPipe[0], &c, 1) == 1) CefPostTask(TID_UI, base::BindOnce(&requestQuit));
    }).detach();
    CefPostDelayedTask(TID_UI, base::BindOnce(&report), 2000);

    CefRunMessageLoop();
    std::thread([] {
        std::this_thread::sleep_for(std::chrono::seconds(5));
        fprintf(stderr, "producer: CEF did not shut down; exiting anyway\n");
        _exit(0);
    }).detach();
    for (auto &entry : gPanels) entry.second->browser = nullptr;
    CefShutdown();
    return 0;
}
