/*
 * winedac.drv — Vulkan WSI：DXVK → AHardwareBuffer → SurfaceFlinger
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * wine 9.2 的 winevulkan 把全部 WSI 委托给图形驱动（include/wine/vulkan_driver.h
 * WINE_VULKAN_DRIVER_VERSION 14）。本文件实现无窗口系统的 swapchain 仿真：
 *
 *   vkCreateSwapchainKHR :
 *     每个交换链图像 = AHardwareBuffer（GPU_COLOR_OUTPUT|GPU_SAMPLED|GPU_FRAMEBUFFER）
 *     经 VK_ANDROID_external_memory_android_hardware_buffer 导入为 VkImage
 *     （dedicated allocation）。图像句柄经 socket 发给 app 内 bridge。
 *   vkAcquireNextImageKHR :
 *     返回空闲图像索引，立即向应用信号量提交 signal（队列 0）。
 *   vkQueuePresentKHR :
 *     1. 把应用的 present 信号量经"转发提交"接到本驱动的可导出信号量上
 *     2. vkGetSemaphoreFdKHR 导出 sync fd（GPU 完成即置位）
 *     3. VK_PRESENT{surface_id, image_idx} + fence fd 发给 bridge
 *     bridge: ASurfaceTransaction.setBuffer(ahb, fence) → SurfaceFlinger 直合
 *   bridge 回执 VK_RELEASED（附 release fence fd）后图像重新可用，
 *     并用同一提交链向 acquire 信号量补发（等待 GPU 释放）。
 *
 * dmabuf 模式（glibc Wine + glibc turnip/lavapipe）：图像走
 *   VK_KHR_external_memory_fd OPAQUE_FD，LINEAR tiling（stride 可查），
 *   bridge 端 EGLImage blit 呈现（性能低于 AHB 直合，作兼容兜底）。
 */

#if 0
#pragma makedep unix  /* 本文件仅编译 unix 侧（wine 9.2 驱动模型） */
#endif

#include "config.h"

#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "windef.h"
#include "winbase.h"
#include "winuser.h"
#include "ntuser.h"

#define VK_NO_PROTOTYPES
#define WINE_VK_HOST

#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"
#include "wine/list.h"

#include "dacdrv.h"
#include "wine/debug.h"

WINE_DEFAULT_DEBUG_CHANNEL(vulkan);

/* ---------------- 本地 Vulkan 结构声明（wine/vulkan.h 未含 fd 扩展） ---------------- */#define VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID 1000128000
#define VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID  1000128002
#define VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID 1000128003
#define VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT 0x00000008
#define VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT 0x00000001
#define VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT 0x00000001
#define VK_IMAGE_TILING_LINEAR 1
#define VK_IMAGE_LAYOUT_UNDEFINED 0

/* KHR fd 扩展结构类型号（Khronos 官方值，wine/vulkan.h 未含） */
#define VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR 1000011000
#define VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR    1000011001
#define VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR    1000074000
#define VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR       1000074001

struct VkImportAndroidHardwareBufferInfoANDROID
{
    VkStructureType sType;
    const void *pNext;
    void *buffer;                 /* AHardwareBuffer* */
};
typedef struct VkImportAndroidHardwareBufferInfoANDROID VkImportAndroidHardwareBufferInfoANDROID;

struct VkAndroidHardwareBufferPropertiesANDROID
{
    VkStructureType sType;
    void *pNext;
    VkDeviceSize allocationSize;
    uint32_t memoryTypeBits;
};
typedef struct VkAndroidHardwareBufferPropertiesANDROID VkAndroidHardwareBufferPropertiesANDROID;

struct VkImportMemoryFdInfoKHR
{
    VkStructureType sType;
    const void *pNext;
    VkExternalMemoryHandleTypeFlagBits handleType;
    int fd;
};
typedef struct VkImportMemoryFdInfoKHR VkImportMemoryFdInfoKHR;

struct VkSemaphoreGetFdInfoKHR
{
    VkStructureType sType;
    const void *pNext;
    VkSemaphore semaphore;
    VkExternalSemaphoreHandleTypeFlagBits handleType;
};
typedef struct VkSemaphoreGetFdInfoKHR VkSemaphoreGetFdInfoKHR;

struct VkImportSemaphoreFdInfoKHR
{
    VkStructureType sType;
    const void *pNext;
    VkSemaphore semaphore;
    VkExternalSemaphoreHandleTypeFlagBits handleType;
    int fd;
    VkSemaphoreImportFlags flags;
};
typedef struct VkImportSemaphoreFdInfoKHR VkImportSemaphoreFdInfoKHR;

struct VkMemoryGetFdInfoKHR
{
    VkStructureType sType;
    const void *pNext;
    VkDeviceMemory memory;
    VkExternalMemoryHandleTypeFlagBits handleType;
};
typedef struct VkMemoryGetFdInfoKHR VkMemoryGetFdInfoKHR;


/* ---------------- host ICD 函数指针 ---------------- */
static void *vulkan_handle;
static VkResult (*pvkCreateInstance)( const VkInstanceCreateInfo *, const VkAllocationCallbacks *, VkInstance * );
static void (*pvkDestroyInstance)( VkInstance, const VkAllocationCallbacks * );
static VkResult (*pvkEnumerateInstanceExtensionProperties)( const char *, uint32_t *, VkExtensionProperties * );
static void * (*pvkGetDeviceProcAddr)( VkDevice, const char * );
static void * (*pvkGetInstanceProcAddr)( VkInstance, const char * );
static void (*pvkDestroyImage)( VkDevice, VkImage, const VkAllocationCallbacks * );
static VkResult (*pvkAllocateMemory)( VkDevice, const VkMemoryAllocateInfo *, const VkAllocationCallbacks *, VkDeviceMemory * );
static void (*pvkFreeMemory)( VkDevice, VkDeviceMemory, const VkAllocationCallbacks * );
static VkResult (*pvkCreateImage)( VkDevice, const VkImageCreateInfo *, const VkAllocationCallbacks *, VkImage * );
static VkResult (*pvkBindImageMemory)( VkDevice, VkImage, VkDeviceMemory, VkDeviceSize );
static VkResult (*pvkBindImageMemory2)( VkDevice, uint32_t, const VkBindImageMemoryInfo * );
static void (*pvkGetDeviceQueue)( VkDevice, uint32_t, uint32_t, VkQueue * );
static VkResult (*pvkQueueSubmit)( VkQueue, uint32_t, const VkSubmitInfo *, VkFence );
static VkResult (*pvkCreateSemaphore)( VkDevice, const VkSemaphoreCreateInfo *, const VkAllocationCallbacks *, VkSemaphore * );
static void (*pvkDestroySemaphore)( VkDevice, VkSemaphore, const VkAllocationCallbacks * );
static VkResult (*pvkGetSemaphoreFdKHR)( VkDevice, const VkSemaphoreGetFdInfoKHR *, int * );
static VkResult (*pvkImportSemaphoreFdKHR)( VkDevice, const VkImportSemaphoreFdInfoKHR * );
static VkResult (*pvkGetMemoryFdKHR)( VkDevice, const VkMemoryGetFdInfoKHR *, int * );
static VkResult (*pvkGetImageSubresourceLayout)( VkDevice, VkImage, const VkImageSubresource *, VkSubresourceLayout * );
static void (*pvkGetImageMemoryRequirements)( VkDevice, VkImage, VkMemoryRequirements * );
/* VK_ANDROID_external_memory_android_hardware_buffer */
static VkResult (*pvkGetAndroidHardwareBufferPropertiesANDROID)( VkDevice, const void *, VkAndroidHardwareBufferPropertiesANDROID * );


static pthread_mutex_t vk_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct list surface_list = LIST_INIT( surface_list );
static uint64_t next_surface_id = 1;
static const struct vulkan_funcs vulkan_funcs;   /* 前置：proc-addr 查找引用 */

/* ---------------- 对象 ---------------- */
struct wine_vk_surface
{
    LONG ref;
    struct list entry;
    HWND hwnd;
    uint64_t id;
    DWORD hwnd_thread_id;
    void *swapchain;          /* 关联的 dac_vk_swapchain（单链 v1） */
};

struct dac_vk_swapchain
{
    uint32_t            magic;         /* 存活校验 */
#define VK_SWAPCHAIN_MAGIC 0x44414353
    LONG                ref;
    struct wine_vk_surface *surface;
    VkDevice            device;
    uint32_t            image_count;
    VkImage             images[8];
    VkDeviceMemory      memories[8];
    struct dac_ahb      ahbs[8];
    uint32_t            pending_mask;  /* 已 present 未 release */
    uint32_t            width, height;
    VkFormat            format;
    BOOL                is_dmabuf;
    pthread_mutex_t     lock;
    pthread_cond_t      cond;
    /* acquire 时的应用信号量（release 时补 signal 由本表驱动） */
    VkSemaphore         acquire_sem[8];
};

static inline struct wine_vk_surface *surface_from_handle( VkSurfaceKHR handle )
{
    return (struct wine_vk_surface *)(uintptr_t)handle;
}

static inline struct dac_vk_swapchain *swapchain_from_handle( VkSwapchainKHR handle )
{
    struct dac_vk_swapchain *sc = (struct dac_vk_swapchain *)(uintptr_t)handle;
    if (!sc || sc->magic != VK_SWAPCHAIN_MAGIC) return NULL;
    return sc;
}

/* ---------------- AHB 格式映射 ---------------- */
static BOOL vkfmt_to_ahb( VkFormat fmt, uint32_t *ahb_fmt, BOOL *srgb )
{
    *srgb = FALSE;
    switch (fmt)
    {
    case 37: *ahb_fmt = DAC_AHB_FMT_RGBA8888; return TRUE;   /* R8G8B8A8_UNORM */
    case 43: *ahb_fmt = DAC_AHB_FMT_RGBA8888; *srgb = TRUE; return TRUE;
    case 44: *ahb_fmt = DAC_AHB_FMT_BGRA8888; return TRUE;   /* B8G8R8A8_UNORM */
    case 51: *ahb_fmt = DAC_AHB_FMT_BGRA8888; *srgb = TRUE; return TRUE;
    case 97: *ahb_fmt = DAC_AHB_FMT_RGBA_FP16; return TRUE;  /* R16G16B16A16_SFLOAT */
    default: return FALSE;
    }
}

/* DRM fourcc（dmabuf 模式 bridge EGL 导入用） */
static BOOL vkfmt_to_fourcc( VkFormat fmt, uint32_t *fourcc )
{
    switch (fmt)
    {
    case 37: case 43: *fourcc = 0x34325241; return TRUE;  /* DRM_FORMAT_ABGR8888 */
    case 44: case 51: *fourcc = 0x34324241; return TRUE;  /* DRM_FORMAT_ARGB8888 */
    case 97: *fourcc = 0x48324241; return TRUE;           /* DRM_FORMAT_ABGR16161616F */
    default: return FALSE;
    }
}

/* ---------------- init ---------------- */
static BOOL vulkan_load_host( void )
{
    const char *names[] = { "libvulkan.so.1", "libvulkan.so", "libvulkan.1.so", NULL };
    int i;

    for (i = 0; names[i]; i++)
        if ((vulkan_handle = dlopen( names[i], RTLD_NOW | RTLD_LOCAL ))) break;
    if (!vulkan_handle)
    {
        ERR( "cannot load host Vulkan loader（安装 mesa-vulkan 或 turnip）\n" );
        return FALSE;
    }

#define LOAD(f) \
    if (!(pvk##f = dlsym( vulkan_handle, "vk" #f ))) \
    { \
        ERR( "missing symbol vk%s\n", #f ); \
        return FALSE; \
    }
    LOAD(CreateInstance);
    LOAD(DestroyInstance);
    LOAD(EnumerateInstanceExtensionProperties);
    LOAD(GetDeviceProcAddr);
    LOAD(GetInstanceProcAddr);
    LOAD(DestroyImage);
    LOAD(AllocateMemory);
    LOAD(FreeMemory);
    LOAD(CreateImage);
    LOAD(BindImageMemory);
    LOAD(GetDeviceQueue);
    LOAD(QueueSubmit);
    LOAD(CreateSemaphore);
    LOAD(DestroySemaphore);
    LOAD(GetImageMemoryRequirements);
#undef LOAD
    /* 可选符号 */
    pvkBindImageMemory2 = dlsym( vulkan_handle, "vkBindImageMemory2" );
    pvkGetSemaphoreFdKHR = dlsym( vulkan_handle, "vkGetSemaphoreFdKHR" );
    pvkImportSemaphoreFdKHR = dlsym( vulkan_handle, "vkImportSemaphoreFdKHR" );
    pvkGetMemoryFdKHR = dlsym( vulkan_handle, "vkGetMemoryFdKHR" );
    pvkGetImageSubresourceLayout = dlsym( vulkan_handle, "vkGetImageSubresourceLayout" );
    pvkGetAndroidHardwareBufferPropertiesANDROID =
        dlsym( vulkan_handle, "vkGetAndroidHardwareBufferPropertiesANDROID" );

    TRACE( "host Vulkan loader loaded (%s)\n", names[i] );
    return TRUE;
}

/* ---------------- instance ---------------- */
static VkResult DAC_vkCreateInstance( const VkInstanceCreateInfo *create_info,
        const VkAllocationCallbacks *allocator, VkInstance *instance )
{
    VkInstanceCreateInfo host = *create_info;
    const char **filtered = NULL;
    unsigned int i, j = 0;
    VkResult res;

    if (create_info->enabledExtensionCount)
    {
        filtered = calloc( create_info->enabledExtensionCount, sizeof(*filtered) );
        if (!filtered) return VK_ERROR_OUT_OF_HOST_MEMORY;
        for (i = 0; i < create_info->enabledExtensionCount; i++)
        {
            /* win32 surface 由本驱动仿真，不透传给宿主 ICD */
            if (!strcmp( create_info->ppEnabledExtensionNames[i], "VK_KHR_win32_surface" ))
                continue;
            filtered[j++] = create_info->ppEnabledExtensionNames[i];
        }
        host.enabledExtensionCount = j;
        host.ppEnabledExtensionNames = filtered;
    }

    res = pvkCreateInstance( &host, allocator, instance );
    free( filtered );

    if (res == VK_SUCCESS)
        TRACE( "host instance %p\n", *instance );
    else
        ERR( "vkCreateInstance failed res=%d\n", res );
    return res;
}

static VkResult DAC_vkEnumerateInstanceExtensionProperties( const char *layer_name,
        uint32_t *count, VkExtensionProperties *properties )
{
    return pvkEnumerateInstanceExtensionProperties( layer_name, count, properties );
}

static void DAC_vkDestroyInstance( VkInstance instance, const VkAllocationCallbacks *allocator )
{
    pvkDestroyInstance( instance, allocator );
}

/* ---------------- surface ---------------- */
static VkResult DAC_vkCreateWin32SurfaceKHR( VkInstance instance,
        const VkWin32SurfaceCreateInfoKHR *create_info,
        const VkAllocationCallbacks *allocator, VkSurfaceKHR *ret )
{
    struct wine_vk_surface *surface;

    if (!(surface = calloc( 1, sizeof(*surface) )))
        return VK_ERROR_OUT_OF_HOST_MEMORY;

    surface->ref    = 1;
    surface->hwnd   = create_info->hwnd;
    surface->id     = next_surface_id++;
    surface->hwnd_thread_id = create_info->hwnd ? NtUserGetWindowThread( create_info->hwnd, NULL ) : 0;

    pthread_mutex_lock( &vk_mutex );
    list_add_tail( &surface_list, &surface->entry );
    pthread_mutex_unlock( &vk_mutex );

    *ret = (uintptr_t)surface;
    TRACE( "created DAC surface %llu hwnd %p\n", (unsigned long long)surface->id, surface->hwnd );
    return VK_SUCCESS;
}

static void surface_release( struct wine_vk_surface *surface )
{
    if (InterlockedDecrement( &surface->ref )) return;
    pthread_mutex_lock( &vk_mutex );
    list_remove( &surface->entry );
    pthread_mutex_unlock( &vk_mutex );
    free( surface );
}

static void DAC_vkDestroySurfaceKHR( VkInstance instance, VkSurfaceKHR handle,
        const VkAllocationCallbacks *allocator )
{
    struct wine_vk_surface *surface = surface_from_handle( handle );
    if (surface) surface_release( surface );
}

static VkSurfaceKHR DAC_wine_get_host_surface( VkSurfaceKHR handle )
{
    return 0;   /* 无宿主 surface —— swapchain 完全仿真 */
}

/* ---------------- caps / formats ---------------- */
static void fill_caps( struct wine_vk_surface *surface, VkSurfaceCapabilitiesKHR *caps )
{
    RECT win;

    memset( caps, 0, sizeof(*caps) );
    caps->minImageCount       = 2;
    caps->maxImageCount       = 8;
    caps->minImageExtent      = (VkExtent2D){ 64, 64 };
    caps->maxImageExtent      = (VkExtent2D){ 4096, 4096 };
    caps->maxImageArrayLayers = 1;
    caps->supportedTransforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    caps->currentTransform    = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    caps->supportedCompositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR |
                                    VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    caps->supportedUsageFlags = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT |
                                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;

    if (surface->hwnd && NtUserGetWindowRect( surface->hwnd, &win ))
    {
        RECT desk = dac_desktop_rect();
        int w = win.right - win.left, h = win.bottom - win.top;
        if (w < 1) w = 1;
        if (h < 1) h = 1;
        /* 虚拟桌面内窗口尺寸（DXVK 通常请求整个桌面） */
        if (desk.right && w > desk.right - desk.left) w = desk.right - desk.left;
        if (desk.bottom && h > desk.bottom - desk.top) h = desk.bottom - desk.top;
        caps->currentExtent = (VkExtent2D){ w, h };
    }
    else
        caps->currentExtent = (VkExtent2D){ 0xffffffff, 0xffffffff };
}

static VkResult DAC_vkGetPhysicalDeviceSurfaceCapabilitiesKHR( VkPhysicalDevice phys_dev,
        VkSurfaceKHR handle, VkSurfaceCapabilitiesKHR *capabilities )
{
    struct wine_vk_surface *surface = surface_from_handle( handle );
    if (!surface) return VK_ERROR_SURFACE_LOST_KHR;
    fill_caps( surface, capabilities );
    return VK_SUCCESS;
}

static VkResult DAC_vkGetPhysicalDeviceSurfaceCapabilities2KHR( VkPhysicalDevice phys_dev,
        const VkPhysicalDeviceSurfaceInfo2KHR *surface_info, VkSurfaceCapabilities2KHR *capabilities )
{
    return DAC_vkGetPhysicalDeviceSurfaceCapabilitiesKHR( phys_dev, surface_info->surface,
                                                          &capabilities->surfaceCapabilities );
}

static const VkSurfaceFormatKHR surface_formats[] =
{
    { VK_FORMAT_R8G8B8A8_UNORM,   VK_COLOR_SPACE_SRGB_NONLINEAR_KHR },
    { VK_FORMAT_B8G8R8A8_UNORM,   VK_COLOR_SPACE_SRGB_NONLINEAR_KHR },
    { VK_FORMAT_R8G8B8A8_SRGB,    VK_COLOR_SPACE_SRGB_NONLINEAR_KHR },
    { VK_FORMAT_B8G8R8A8_SRGB,    VK_COLOR_SPACE_SRGB_NONLINEAR_KHR },
    { VK_FORMAT_R16G16B16A16_SFLOAT, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR },
};

static VkResult DAC_vkGetPhysicalDeviceSurfaceFormatsKHR( VkPhysicalDevice phys_dev,
        VkSurfaceKHR handle, uint32_t *count, VkSurfaceFormatKHR *formats )
{
    unsigned int i;

    if (*count < ARRAY_SIZE( surface_formats ))
    {
        *count = ARRAY_SIZE( surface_formats );
        return VK_INCOMPLETE;
    }
    *count = ARRAY_SIZE( surface_formats );
    if (formats)
        for (i = 0; i < ARRAY_SIZE( surface_formats ); i++) formats[i] = surface_formats[i];
    return VK_SUCCESS;
}

static VkResult DAC_vkGetPhysicalDeviceSurfaceFormats2KHR( VkPhysicalDevice phys_dev,
        const VkPhysicalDeviceSurfaceInfo2KHR *surface_info, uint32_t *count,
        VkSurfaceFormat2KHR *formats )
{
    unsigned int i;
    VkResult res = DAC_vkGetPhysicalDeviceSurfaceFormatsKHR( phys_dev, surface_info->surface, count, NULL );
    if (res != VK_SUCCESS && res != VK_INCOMPLETE) return res;
    if (formats)
        for (i = 0; i < *count; i++)
        {
            formats[i].sType = VK_STRUCTURE_TYPE_SURFACE_FORMAT_2_KHR;
            formats[i].surfaceFormat = surface_formats[i];
        }
    return *count ? VK_SUCCESS : VK_INCOMPLETE;
}

static VkResult DAC_vkGetPhysicalDevicePresentRectanglesKHR( VkPhysicalDevice phys_dev,
        VkSurfaceKHR handle, uint32_t *count, VkRect2D *rects )
{
    struct wine_vk_surface *surface = surface_from_handle( handle );
    RECT win;

    if (!surface) return VK_ERROR_SURFACE_LOST_KHR;
    if (!*count) { *count = 1; return VK_SUCCESS; }
    *count = 1;
    if (!rects) return VK_SUCCESS;
    if (surface->hwnd && NtUserGetWindowRect( surface->hwnd, &win ))
    {
        rects[0].offset = (VkOffset2D){ win.left, win.top };
        rects[0].extent = (VkExtent2D){ win.right - win.left, win.bottom - win.top };
    }
    else
        rects[0].extent = (VkExtent2D){ 0, 0 };
    return VK_SUCCESS;
}

static VkBool32 DAC_vkGetPhysicalDeviceWin32PresentationSupportKHR( VkPhysicalDevice phys_dev,
        uint32_t queue_index )
{
    return VK_TRUE;
}

/* ---------------- swapchain ---------------- */
static void swapchain_notify_destroy( struct dac_vk_swapchain *sc )
{
    struct dac_vk_surface_new del = { 0 };
    del.id = sc->surface->id;
    dac_send_msg( DAC_MSG_VK_SURFACE_DEL, &del, sizeof(del), NULL, 0 );
}

static VkResult create_swapchain_image_ahb( struct dac_vk_swapchain *sc, unsigned int idx,
                                            uint32_t ahb_format, VkImageUsageFlags usage )
{
    VkImageCreateInfo image_info = { 0 };
    VkMemoryAllocateInfo alloc_info = { 0 };
    VkImportAndroidHardwareBufferInfoANDROID import_ahb;
    VkMemoryDedicatedAllocateInfo dedicated;
    VkAndroidHardwareBufferPropertiesANDROID ahb_props = { 0 };
    void *ahb;
    VkResult res;

    image_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image_info.imageType   = VK_IMAGE_TYPE_2D;
    image_info.format      = sc->format;
    image_info.extent      = (VkExtent3D){ sc->width, sc->height, 1 };
    image_info.mipLevels   = 1;
    image_info.arrayLayers = 1;
    image_info.samples     = VK_SAMPLE_COUNT_1_BIT;
    image_info.tiling      = VK_IMAGE_TILING_OPTIMAL;
    image_info.usage       = usage | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if ((res = pvkCreateImage( sc->device, &image_info, NULL, &sc->images[idx] )) != VK_SUCCESS)
    {
        ERR( "vkCreateImage[%u] failed %d\n", idx, res );
        return res;
    }

    /* AHB 在平台层分配（bionic 直连 / sidecar） */
    if (!dac_platform_alloc_ahb( sc->width, sc->height, ahb_format,
                                 DAC_AHB_USAGE_GPU_COLOR_OUTPUT |
                                 DAC_AHB_USAGE_GPU_SAMPLED_IMAGE |
                                 DAC_AHB_USAGE_GPU_FRAMEBUFFER,
                                 &sc->ahbs[idx] ))
    {
        ERR( "AHB alloc[%u] failed\n", idx );
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    ahb = sc->ahbs[idx].ahb;

    ahb_props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    if (pvkGetAndroidHardwareBufferPropertiesANDROID &&
        (res = pvkGetAndroidHardwareBufferPropertiesANDROID( sc->device, ahb, &ahb_props )))
    {
        ERR( "GetAHBProperties[%u] failed %d\n", idx, res );
        return res;
    }

    import_ahb.sType  = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import_ahb.pNext  = NULL;
    import_ahb.buffer = ahb;

    dedicated.sType  = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.pNext  = &import_ahb;
    dedicated.image  = sc->images[idx];
    dedicated.buffer = 0;

    alloc_info.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc_info.pNext           = &dedicated;
    alloc_info.allocationSize  = ahb_props.allocationSize;
    alloc_info.memoryTypeIndex = 0;

    /* 从 ahb_props.memoryTypeBits 里挑 DEVICE_LOCAL 位（v1 取最低可用位） */
    {
        uint32_t bits = ahb_props.memoryTypeBits;
        int i;
        for (i = 0; bits; i++, bits >>= 1)
            if (bits & 1) { alloc_info.memoryTypeIndex = i; break; }
    }

    if ((res = pvkAllocateMemory( sc->device, &alloc_info, NULL, &sc->memories[idx] )))
    {
        ERR( "AllocateMemory[%u] failed %d\n", idx, res );
        return res;
    }

    if (pvkBindImageMemory2)
    {
        VkBindImageMemoryInfo bind = { 0 };
        bind.sType  = VK_STRUCTURE_TYPE_BIND_IMAGE_MEMORY_INFO;
        bind.image  = sc->images[idx];
        bind.memory = sc->memories[idx];
        bind.memoryOffset = 0;
        if ((res = pvkBindImageMemory2( sc->device, 1, &bind ))) return res;
    }
    else if ((res = pvkBindImageMemory( sc->device, sc->images[idx], sc->memories[idx], 0 )))
        return res;

    return VK_SUCCESS;
}

static VkResult create_swapchain_image_dmabuf( struct dac_vk_swapchain *sc, unsigned int idx,
                                               uint32_t fourcc, VkImageUsageFlags usage,
                                               uint32_t *stride_out )
{
    VkImageCreateInfo image_info = { 0 };
    VkMemoryAllocateInfo alloc_info = { 0 };
    VkMemoryRequirements reqs = { 0 };
    VkMemoryGetFdInfoKHR get_fd;
    VkSubresourceLayout layout;
    VkImageSubresource subres;
    VkDeviceMemory mem;
    int fd = -1;
    VkResult res;

    image_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image_info.imageType   = VK_IMAGE_TYPE_2D;
    image_info.format      = sc->format;
    image_info.extent      = (VkExtent3D){ sc->width, sc->height, 1 };
    image_info.mipLevels   = 1;
    image_info.arrayLayers = 1;
    image_info.samples     = VK_SAMPLE_COUNT_1_BIT;
    image_info.tiling      = VK_IMAGE_TILING_LINEAR;   /* dmabuf 布局可查 */
    image_info.usage       = usage | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                             VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if ((res = pvkCreateImage( sc->device, &image_info, NULL, &sc->images[idx] )))
    {
        ERR( "dmabuf vkCreateImage[%u] failed %d（LINEAR 渲染不受支持？）\n", idx, res );
        return res;
    }

    alloc_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    pvkGetImageMemoryRequirements( sc->device, sc->images[idx], &reqs );
    alloc_info.allocationSize = reqs.size;
    {
        uint32_t bits = reqs.memoryTypeBits;
        int i;
        for (i = 0; bits; i++, bits >>= 1)
            if (bits & 1) { alloc_info.memoryTypeIndex = i; break; }
    }
    if ((res = pvkAllocateMemory( sc->device, &alloc_info, NULL, &mem )))
    {
        ERR( "dmabuf AllocateMemory[%u] failed %d\n", idx, res );
        return res;
    }
    sc->memories[idx] = mem;
    if ((res = pvkBindImageMemory( sc->device, sc->images[idx], mem, 0 ))) return res;

    if (!pvkGetMemoryFdKHR)
    {
        ERR( "host ICD lacks VK_KHR_external_memory_fd — dmabuf 模式不可用\n" );
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    get_fd.sType      = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
    get_fd.memory     = mem;
    get_fd.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    if ((res = pvkGetMemoryFdKHR( sc->device, &get_fd, &fd )))
    {
        ERR( "GetMemoryFd[%u] failed %d\n", idx, res );
        return res;
    }

    subres.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    subres.mipLevel = 0; subres.arrayLayer = 0;
    pvkGetImageSubresourceLayout( sc->device, sc->images[idx], &subres, &layout );
    *stride_out = layout.rowPitch / 4;

    /* 记录 dmabuf fd（发 VK_IMAGE 帧时随帧传 fd） */
    dac_platform_import_dmabuf( fd, sc->width, sc->height, *stride_out, &sc->ahbs[idx] );
    close( fd );   /* import_dmabuf 内部已 dup */
    return VK_SUCCESS;
}

static void send_vk_images( struct dac_vk_swapchain *sc, uint32_t fourcc_or_ahb )
{
    unsigned int i;

    for (i = 0; i < sc->image_count; i++)
    {
        struct dac_buffer_meta meta = { 0 };
        meta.slot   = i;
        meta.width  = sc->width;
        meta.height = sc->height;
        meta.stride = sc->ahbs[i].stride;
        meta.format = fourcc_or_ahb;
        meta.is_dmabuf = sc->is_dmabuf;
        meta.usage  = sc->ahbs[i].usage;
        meta.fd_count = 0;

        if (dac_send_msg( DAC_MSG_VK_IMAGE, &meta, sizeof(meta), NULL, 0 )) return;

        if (sc->is_dmabuf)
        {
            struct dac_buffer_meta m2 = meta;
            int fd = sc->ahbs[i].fd >= 0 ? dup( sc->ahbs[i].fd ) : -1;
            m2.fd_count = fd >= 0 ? 1 : 0;
            if (dac_send_msg( DAC_MSG_VK_IMAGE, &m2, sizeof(m2), &fd, m2.fd_count ))
            {
                if (fd >= 0) close( fd );
                return;
            }
            if (fd >= 0) close( fd );
        }
        else if (dac_platform_send_ahb( &sc->ahbs[i] ))
            return;
    }
}

static VkResult DAC_vkCreateSwapchainKHR( VkDevice device,
        const VkSwapchainCreateInfoKHR *create_info,
        const VkAllocationCallbacks *allocator, VkSwapchainKHR *ret )
{
    struct wine_vk_surface *surface = surface_from_handle( create_info->surface );
    struct dac_vk_swapchain *sc;
    VkImageUsageFlags usage;
    uint32_t ahb_fmt = 0, fourcc = 0;
    BOOL srgb;
    struct dac_vk_surface_new msg = { 0 };
    RECT win;
    unsigned int i;
    VkResult res;

    if (!surface) return VK_ERROR_SURFACE_LOST_KHR;

    if (!vkfmt_to_ahb( create_info->imageFormat, &ahb_fmt, &srgb ) ||
        !vkfmt_to_fourcc( create_info->imageFormat, &fourcc ))
    {
        FIXME( "unsupported swapchain format %d\n", create_info->imageFormat );
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    }

    if (!(sc = calloc( 1, sizeof(*sc) ))) return VK_ERROR_OUT_OF_HOST_MEMORY;
    sc->magic   = VK_SWAPCHAIN_MAGIC;
    sc->ref     = 1;
    sc->surface = surface;
    surface->ref++;
    sc->device  = device;
    surface->swapchain = sc;
    sc->format  = create_info->imageFormat;
    sc->is_dmabuf = (dac_platform_mode() == DAC_AHB_MODE_DMABUF);
    pthread_mutex_init( &sc->lock, NULL );
    pthread_cond_init( &sc->cond, NULL );

    if (NtUserGetWindowRect( surface->hwnd, &win ))
    {
        sc->width  = win.right - win.left;
        sc->height = win.bottom - win.top;
    }
    if (!sc->width || !sc->height)
    {
        sc->width  = dac_desktop.width;
        sc->height = dac_desktop.height;
    }
    sc->width  = max( sc->width,  create_info->imageExtent.width );
    sc->height = max( sc->height, create_info->imageExtent.height );

    sc->image_count = create_info->minImageCount;
    if (sc->image_count < 3) sc->image_count = 3;
    if (sc->image_count > 8) sc->image_count = 8;

    usage = create_info->imageUsage;
    if (!usage) usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;

    TRACE( "creating swapchain %ux%u fmt %d count %u dmabuf %d\n",
           sc->width, sc->height, sc->format, sc->image_count, sc->is_dmabuf );

    for (i = 0; i < sc->image_count; i++)
    {
        uint32_t stride = sc->width;
        if (sc->is_dmabuf)
            res = create_swapchain_image_dmabuf( sc, i, fourcc, usage, &stride );
        else
            res = create_swapchain_image_ahb( sc, i, ahb_fmt, usage );
        if (res) goto fail;
        sc->ahbs[i].width  = sc->width;
        sc->ahbs[i].height = sc->height;
        if (!sc->ahbs[i].stride) sc->ahbs[i].stride = stride;
    }

    /* 通知 bridge：surface 注册 */
    msg.id         = surface->id;
    msg.image_count= sc->image_count;
    msg.format     = sc->is_dmabuf ? fourcc : ahb_fmt;
    msg.width      = sc->width;
    msg.height     = sc->height;
    msg.is_dmabuf  = sc->is_dmabuf;
    if (dac_send_msg( DAC_MSG_VK_SURFACE_NEW, &msg, sizeof(msg), NULL, 0 ))
    {
        res = VK_ERROR_INITIALIZATION_FAILED;
        goto fail;
    }
    send_vk_images( sc, sc->is_dmabuf ? fourcc : ahb_fmt );

    /* 初始图层位置 */
    dac_vulkan_notify_window_rect( surface->hwnd, NULL );

    *ret = (uintptr_t)sc;
    return VK_SUCCESS;

fail:
    ERR( "swapchain creation failed res=%d\n", res );
    for (i = 0; i < sc->image_count; i++)
    {
        if (sc->images[i]) pvkDestroyImage( sc->device, sc->images[i], NULL );
        if (sc->memories[i]) pvkFreeMemory( sc->device, sc->memories[i], NULL );
        dac_platform_free_ahb( &sc->ahbs[i] );
    }
    surface_release( surface );
    pthread_mutex_destroy( &sc->lock );
    pthread_cond_destroy( &sc->cond );
    free( sc );
    return res;
}

static void DAC_vkDestroySwapchainKHR( VkDevice device, VkSwapchainKHR handle,
        const VkAllocationCallbacks *allocator )
{
    struct dac_vk_swapchain *sc = swapchain_from_handle( handle );
    unsigned int i;

    if (!sc) return;
    sc->magic = 0;
    if (sc->surface) sc->surface->swapchain = NULL;

    swapchain_notify_destroy( sc );
    for (i = 0; i < sc->image_count; i++)
    {
        if (sc->images[i]) pvkDestroyImage( sc->device, sc->images[i], NULL );
        if (sc->memories[i]) pvkFreeMemory( sc->device, sc->memories[i], NULL );
        dac_platform_free_ahb( &sc->ahbs[i] );
    }
    pthread_mutex_destroy( &sc->lock );
    pthread_cond_destroy( &sc->cond );
    surface_release( sc->surface );
    free( sc );
}

static VkResult DAC_vkGetSwapchainImagesKHR( VkDevice device, VkSwapchainKHR handle,
        uint32_t *count, VkImage *images )
{
    struct dac_vk_swapchain *sc = swapchain_from_handle( handle );
    unsigned int i;

    if (!sc) return VK_ERROR_SURFACE_LOST_KHR;
    if (!count) return VK_ERROR_DEVICE_LOST;

    if (!images) { *count = sc->image_count; return VK_SUCCESS; }
    if (*count > sc->image_count) *count = sc->image_count;
    for (i = 0; i < *count; i++) images[i] = sc->images[i];
    return *count == sc->image_count ? VK_SUCCESS : VK_INCOMPLETE;
}

/* ---------------- acquire ---------------- */
static VkResult DAC_vkAcquireNextImageKHR( VkDevice device, VkSwapchainKHR handle,
        uint64_t timeout, VkSemaphore semaphore, VkFence fence, uint32_t *image_index )
{
    struct dac_vk_swapchain *sc = swapchain_from_handle( handle );
    struct timespec ts;
    uint32_t idx;
    VkQueue queue;
    VkResult res;

    if (!sc) return VK_ERROR_SURFACE_LOST_KHR;

    pthread_mutex_lock( &sc->lock );
    if (timeout)
        clock_gettime( CLOCK_REALTIME, &ts );
    while (sc->pending_mask == ((1u << sc->image_count) - 1))
    {
        if (!timeout) { pthread_mutex_unlock( &sc->lock ); return VK_NOT_READY; }
        if (timeout == 0xffffffffffffffffull)
        {
            pthread_cond_wait( &sc->cond, &sc->lock );
            continue;
        }
        ts.tv_sec  += timeout / 1000000000ull;
        ts.tv_nsec += timeout % 1000000000ull;
        if (ts.tv_nsec >= 1000000000) { ts.tv_sec++; ts.tv_nsec -= 1000000000; }
        if (pthread_cond_timedwait( &sc->cond, &sc->lock, &ts ))
        {
            pthread_mutex_unlock( &sc->lock );
            return VK_TIMEOUT;
        }
    }
    for (idx = 0; idx < sc->image_count; idx++)
        if (!(sc->pending_mask & (1u << idx))) break;
    sc->acquire_sem[idx] = semaphore;
    pthread_mutex_unlock( &sc->lock );

    /* 图像空闲：立即向应用信号量发 signal（队列0） */
    pvkGetDeviceQueue( device, 0, 0, &queue );
    {
        VkSubmitInfo submit = { 0 };
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.signalSemaphoreCount = semaphore ? 1 : 0;
        submit.pSignalSemaphores = &semaphore;
        res = pvkQueueSubmit( queue, 1, &submit, fence );
        if (res) ERR( "acquire signal submit failed %d\n", res );
    }

    *image_index = idx;
    return VK_SUCCESS;
}

/* ---------------- present ---------------- */
static VkResult DAC_vkQueuePresentKHR( VkQueue queue, const VkPresentInfoKHR *present_info )
{
    VkResult res = VK_SUCCESS;
    unsigned int i;

    for (i = 0; i < present_info->swapchainCount; i++)
    {
        struct dac_vk_swapchain *sc = swapchain_from_handle( present_info->pSwapchains[i] );
        VkSemaphore fwd_sem = 0;
        uint32_t idx;
        int fence_fd = -1;

        if (!sc) { res = VK_ERROR_DEVICE_LOST; continue; }
        idx = present_info->pImageIndices[i];
        if (idx >= sc->image_count) { res = VK_ERROR_DEVICE_LOST; continue; }

        /* 1. 转发提交：等应用 present 信号量 → 置位本驱动可导出信号量 */
        if (present_info->waitSemaphoreCount && pvkGetSemaphoreFdKHR)
        {
            VkSemaphoreCreateInfo sem_info = { 0 };
            VkExportSemaphoreCreateInfo export_info;
            VkSubmitInfo submit = { 0 };

            sem_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
            export_info.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
            export_info.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
            sem_info.pNext = &export_info;
            if (pvkCreateSemaphore( sc->device, &sem_info, NULL, &fwd_sem ))
                fwd_sem = 0;

            if (fwd_sem)
            {
                submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                submit.waitSemaphoreCount   = present_info->waitSemaphoreCount;
                submit.pWaitSemaphores      = present_info->pWaitSemaphores;
                submit.signalSemaphoreCount = 1;
                submit.pSignalSemaphores    = &fwd_sem;
                if (pvkQueueSubmit( queue, 1, &submit, (VkFence)0 ))
                {
                    pvkDestroySemaphore( sc->device, fwd_sem, NULL );
                    fwd_sem = 0;
                }
            }
        }

        /* 2. 导出 sync fd */
        if (fwd_sem)
        {
            VkSemaphoreGetFdInfoKHR fd_info = { 0 };
            fd_info.sType      = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
            fd_info.semaphore  = fwd_sem;
            fd_info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
            if (pvkGetSemaphoreFdKHR( sc->device, &fd_info, &fence_fd ))
                fence_fd = -1;
            pvkDestroySemaphore( sc->device, fwd_sem, NULL );
        }

        /* 3. 通知 bridge present */
        {
            struct dac_present present = { 0 };
            present.slot = idx;
            present.surface_id = sc->surface->id;
            present.damage_count = 0;

            if (fence_fd >= 0)
            {
                if (!dac_send_msg( DAC_MSG_VK_PRESENT, &present, sizeof(present), &fence_fd, 1 ))
                    close( fence_fd );   /* fd 已复制进内核 */
            }
            else
                dac_send_msg( DAC_MSG_VK_PRESENT, &present, sizeof(present), NULL, 0 );
        }

        pthread_mutex_lock( &sc->lock );
        sc->pending_mask |= (1u << idx);
        pthread_mutex_unlock( &sc->lock );
    }

    return res;
}

/* ---------------- bridge 回调：图像释放 ---------------- */
void vulkan_surface_released( const void *payload, uint32_t len,
                              const int *fds, int fd_count )
{
    const struct dac_present *rel = payload;
    struct dac_vk_swapchain *sc = NULL;
    struct wine_vk_surface *surface;
    VkQueue queue;
    VkSubmitInfo submit = { 0 };
    VkSemaphore release_sem = 0, acquire_sem;
    VkImportSemaphoreFdInfoKHR import_info;
    VkSemaphoreCreateInfo sem_info = { 0 };
    int fence_fd = -1;
    uint32_t idx;
    BOOL found = FALSE;

    if (len < sizeof(*rel)) return;
    idx = rel->slot;

    pthread_mutex_lock( &vk_mutex );
    LIST_FOR_EACH_ENTRY( surface, &surface_list, struct wine_vk_surface, entry )
        if (surface->id == rel->surface_id) { found = TRUE; break; }
    if (found) surface->ref++;
    pthread_mutex_unlock( &vk_mutex );

    if (!found) return;
    sc = surface->swapchain;
    surface->ref--;

    if (!sc || sc->magic != VK_SWAPCHAIN_MAGIC || idx >= sc->image_count) return;

    if (fd_count > 0 && fds[0] >= 0)
        fence_fd = dup( fds[0] );   /* 调用方负责关闭原 fd */

    /* release fence → 导入临时信号量 → submit(wait) → signal 应用 acquire 信号量 */
    if (fence_fd >= 0 && pvkImportSemaphoreFdKHR)
    {
        sem_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        if (!pvkCreateSemaphore( sc->device, &sem_info, NULL, &release_sem ))
        {
            import_info.sType      = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
            import_info.semaphore  = release_sem;
            import_info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
            import_info.fd         = fence_fd;
            import_info.flags      = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
            if (pvkImportSemaphoreFdKHR( sc->device, &import_info ))
            {
                pvkDestroySemaphore( sc->device, release_sem, NULL );
                release_sem = 0;
            }
        }
        close( fence_fd );
    }

    pthread_mutex_lock( &sc->lock );
    acquire_sem = sc->acquire_sem[idx];
    sc->acquire_sem[idx] = 0;
    sc->pending_mask &= ~(1u << idx);
    pthread_cond_broadcast( &sc->cond );
    pthread_mutex_unlock( &sc->lock );

    if (acquire_sem)
    {
        pvkGetDeviceQueue( sc->device, 0, 0, &queue );
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        if (release_sem)
        {
            submit.waitSemaphoreCount = 1;
            submit.pWaitSemaphores = &release_sem;
        }
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &acquire_sem;
        if (pvkQueueSubmit( queue, 1, &submit, (VkFence)0 ))
            ERR( "release signal submit failed\n" );
        if (release_sem)
            pvkDestroySemaphore( sc->device, release_sem, NULL );
    }
}

/* ---------------- 窗口矩形 → bridge 图层 ---------------- */
void dac_vulkan_notify_window_rect( HWND hwnd, const RECT *rect_window )
{
    struct wine_vk_surface *surface;
    struct dac_vk_layer_pos pos = { 0 };
    RECT win, desk;

    if (!hwnd) return;
    if (!rect_window)
    {
        if (!NtUserGetWindowRect( hwnd, &win )) return;
        rect_window = &win;
    }

    desk = dac_desktop_rect();
    pos.x = rect_window->left - desk.left;
    pos.y = rect_window->top - desk.top;
    pos.width  = rect_window->right - rect_window->left;
    pos.height = rect_window->bottom - rect_window->top;

    pthread_mutex_lock( &vk_mutex );
    LIST_FOR_EACH_ENTRY( surface, &surface_list, struct wine_vk_surface, entry )
        if (surface->hwnd == hwnd)
        {
            pos.id = surface->id;
            pthread_mutex_unlock( &vk_mutex );
            dac_send_msg( DAC_MSG_VK_LAYER_POS, &pos, sizeof(pos), NULL, 0 );
            return;
        }
    pthread_mutex_unlock( &vk_mutex );
}

/* ---------------- proc addr ---------------- */
static void *DAC_get_vk_device_proc_addr( const char *name )
{
    return get_vulkan_driver_device_proc_addr( &vulkan_funcs, name );
}

static void *DAC_get_vk_instance_proc_addr( VkInstance instance, const char *name )
{
    return get_vulkan_driver_instance_proc_addr( &vulkan_funcs, instance, name );
}

static void *DAC_vkGetDeviceProcAddr( VkDevice device, const char *name )
{
    void *proc_addr;

    if (!pvkGetDeviceProcAddr( device, name )) return NULL;
    if ((proc_addr = DAC_get_vk_device_proc_addr( name ))) return proc_addr;
    return pvkGetDeviceProcAddr( device, name );
}

static void *DAC_vkGetInstanceProcAddr( VkInstance instance, const char *name )
{
    void *proc_addr;

    if (!pvkGetInstanceProcAddr( instance, name )) return NULL;
    if ((proc_addr = DAC_get_vk_instance_proc_addr( instance, name ))) return proc_addr;
    return pvkGetInstanceProcAddr( instance, name );
}

static const struct vulkan_funcs vulkan_funcs =
{
    DAC_vkCreateInstance,
    DAC_vkCreateSwapchainKHR,
    DAC_vkCreateWin32SurfaceKHR,
    DAC_vkDestroyInstance,
    DAC_vkDestroySurfaceKHR,
    DAC_vkDestroySwapchainKHR,
    DAC_vkEnumerateInstanceExtensionProperties,
    DAC_vkGetDeviceProcAddr,
    DAC_vkGetInstanceProcAddr,
    DAC_vkGetPhysicalDevicePresentRectanglesKHR,
    DAC_vkGetPhysicalDeviceSurfaceCapabilities2KHR,
    DAC_vkGetPhysicalDeviceSurfaceCapabilitiesKHR,
    DAC_vkGetPhysicalDeviceSurfaceFormats2KHR,
    DAC_vkGetPhysicalDeviceSurfaceFormatsKHR,
    DAC_vkGetPhysicalDeviceWin32PresentationSupportKHR,
    DAC_vkGetSwapchainImagesKHR,
    DAC_vkQueuePresentKHR,

    DAC_wine_get_host_surface,
};

static void vulkan_load_once(void)
{
    vulkan_load_host();
}

const struct vulkan_funcs *dac_vulkan_driver_init( UINT version )
{
    static pthread_once_t init_once = PTHREAD_ONCE_INIT;

    if (version != WINE_VULKAN_DRIVER_VERSION)
    {
        ERR( "version mismatch, vulkan wants %u but driver has %u\n",
             version, WINE_VULKAN_DRIVER_VERSION );
        return NULL;
    }
    pthread_once( &init_once, vulkan_load_once );
    return vulkan_handle ? &vulkan_funcs : NULL;
}
