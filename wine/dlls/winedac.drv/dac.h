/*
 * LinBox Direct Android Compositing (DAC) — shared definitions
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * wine 侧 winedac.drv 与 app 侧 linbox_dac_bridge 共用的结构体与协议常量。
 * 两侧各持一份拷贝（wine 侧不能 include NDK 头），字段布局必须保持一致！
 * 修改任何结构体/消息号，必须同步 app/src/main/cpp/dac/bridge.h。
 *
 * 传输层：unix stream socket $PREFIX/tmp/linbox-dac.sock（app 监听，wine 连接）
 * 帧：[u32 magic][u32 type][u32 payload_len][payload]
 * fd 传递：SCM_RIGHTS 附加在对应帧上，帧内 fd_count 声明数量
 */

#ifndef __WINEDAC_DAC_H
#define __WINEDAC_DAC_H

#include <stdint.h>

#define DAC_PROTOCOL_MAGIC     0x44414332u   /* "DAC2" */
#define DAC_PROTOCOL_VERSION   1

/* socket 路径（终端侧 $PREFIX/tmp；app 侧与其绑定同 UID 可访问） */
#define DAC_SOCKET_NAME        "linbox-dac.sock"
#define DAC_ALLOCD_SOCKET_NAME "linbox-dac-allocd.sock"

/* ---------- 消息类型 ---------- */
enum dac_msg_type
{
    /* wine → app */
    DAC_MSG_HELLO           = 1,   /* 握手，声明桌面尺寸 */
    DAC_MSG_DESKTOP_ALLOC   = 2,   /* 桌面槽位注册（附 AHB 句柄 fd 组） */
    DAC_MSG_DESKTOP_FREE    = 3,
    DAC_MSG_DESKTOP_PRESENT = 4,   /* 桌面合成（附 acquire fence fd） */
    DAC_MSG_VK_SURFACE_NEW  = 10,  /* DXVK surface 注册 */
    DAC_MSG_VK_IMAGE        = 11,  /* 交换链图像注册（附 AHB/dmabuf 元数据 + fd 组） */
    DAC_MSG_VK_SURFACE_DEL  = 12,
    DAC_MSG_VK_PRESENT      = 13,  /* 交换链 present（附 acquire fence fd） */
    DAC_MSG_VK_LAYER_POS    = 15,  /* DXVK 窗口在桌面中的矩形 */
    DAC_MSG_CURSOR          = 20,  /* 光标形状/可见性 */
    DAC_MSG_TITLE           = 21,
    DAC_MSG_PING            = 30,
    DAC_MSG_LOG             = 40,

    /* app → wine */
    DAC_MSG_HELLO_ACK       = 100, /* 背包能力协商结果 */
    DAC_MSG_RELEASE         = 101, /* 槽位释放（附 release fence fd，可选） */
    DAC_MSG_VK_RELEASED     = 102, /* 交换链图像释放（附 release fence fd） */
    DAC_MSG_DESKTOP_RESIZE  = 103, /* 窗口尺寸变化要求桌面重建 */
    DAC_MSG_INPUT_MOUSE     = 110,
    DAC_MSG_INPUT_KEY       = 111,
    DAC_MSG_INPUT_GAMEPAD   = 112,
    DAC_MSG_PONG            = 130,
};

/* ---------- 帧头 ---------- */
struct dac_frame_hdr
{
    uint32_t magic;
    uint32_t type;
    uint32_t len;      /* payload 字节数 */
};

/* ---------- HELLO / HELLO_ACK ---------- */
struct dac_hello
{
    uint32_t proto_version;
    uint32_t desktop_width;
    uint32_t desktop_height;
    uint32_t fd_count;             /* 本帧携带 fd 数（桌面槽位另行发） */
    char     exe[256];             /* 启动的 exe（诊断显示用），可空 */
};

struct dac_hello_ack
{
    uint32_t ok;                   /* 1 = 接受连接 */
    uint32_t backend;              /* enum dac_backend */
    uint32_t api_level;            /* 设备 API level */
    uint32_t max_desktop_slots;
};

enum dac_backend
{
    DAC_BACKEND_SF_DIRECT = 0,     /* API29+: ASurfaceControl.setBuffer(AHB) 零拷贝直合 */
    DAC_BACKEND_AHB_CANVAS = 1,    /* API26-28: AHB lock(CPU_READ) → Canvas → SurfaceView */
    DAC_BACKEND_DMABUF_EGL = 2,    /* glibc wine: dmabuf fd → EGLImage → GL blit */
    DAC_BACKEND_NONE = 3,
};

/* ---------- AHB / dmabuf 元数据（DESKTOP_ALLOC / VK_IMAGE payload） ---------- */
/* backend == SF_DIRECT / AHB_CANVAS 时走 AHB：
 *   fd 组 = AHardwareBuffer_sendHandleToUnixSocket 序列化句柄（1 组完整原生句柄）
 * backend == DMABUF_EGL 时走 dmabuf：
 *   fd 组 = [plane0_fd, plane1_fd...]，配合 dmabuf 元数据
 */
struct dac_buffer_meta
{
    uint32_t slot;                 /* 桌面：槽位号；VK：图像索引 */
    uint32_t width;
    uint32_t height;
    uint32_t stride;               /* 像素（非字节） */
    uint32_t format;               /* enum dac_ahb_format 或 DRM fourcc(dmabuf 模式) */
    uint32_t fd_count;
    uint32_t is_dmabuf;            /* 1 = dmabuf 模式 */
    uint64_t usage;                /* AHB usage 位（诊断用） */
    uint64_t offset;               /* dmabuf plane0 offset */
    uint64_t modifier;             /* dmabuf modifier（0 = linear） */
};

/* 桌面槽位申请成功后 bridge 回执用不到，wine 端直接假定成功（失败则桥关闭连接） */

/* ---------- PRESENT ---------- */
struct dac_present
{
    uint32_t slot;                 /* 桌面槽位 或 VK 图像索引 */
    uint64_t surface_id;           /* VK 用；桌面填 0 */
    uint32_t damage_count;         /* 后随 damage_count 个 dac_rect */
};

struct dac_rect
{
    int32_t left, top, right, bottom;
};

/* ---------- VK_SURFACE_NEW / LAYER_POS ---------- */
struct dac_vk_surface_new
{
    uint64_t id;
    uint32_t image_count;
    uint32_t format;               /* 首图 AHB format / drm fourcc */
    uint32_t width;
    uint32_t height;
    uint32_t is_dmabuf;
};

struct dac_vk_layer_pos
{
    uint64_t id;
    int32_t  x, y;                 /* 桌面坐标（层左上角） */
    uint32_t width, height;        /* 桌面中的显示尺寸（0=用图像原尺寸） */
    uint32_t z;                    /* z 序，越大越靠上 */
};

/* ---------- CURSOR ---------- */
struct dac_cursor
{
    uint32_t visible;
    int32_t  x, y;                 /* 桌面坐标 */
    uint32_t shape;                /* enum dac_cursor_shape */
};

enum dac_cursor_shape
{
    DAC_CURSOR_ARROW = 0,
    DAC_CURSOR_IBEAM,
    DAC_CURSOR_WAIT,
    DAC_CURSOR_CROSS,
    DAC_CURSOR_SIZEALL,
    DAC_CURSOR_HAND,
    DAC_CURSOR_NO,
    DAC_CURSOR_SIZENWSE,
    DAC_CURSOR_SIZENESW,
    DAC_CURSOR_SIZENS,
    DAC_CURSOR_SIZEWE,
};

/* ---------- INPUT（bridge → wine） ---------- */
struct dac_input_mouse
{
    uint32_t flags;                /* enum dac_mouse_flags */
    int32_t  x, y;                 /* 桌面绝对坐标 */
    uint32_t buttons;              /* bit0 L, bit1 R, bit2 M, bit3 X1, bit4 X2 */
    int32_t  wheel_dx, wheel_dy;   /* 本次滚轮增量（>0 向上/右） */
    uint32_t absolute;             /* 1 = x/y 为绝对桌面坐标 */
};

enum dac_mouse_flags
{
    DAC_MOUSE_MOVE    = 1,
    DAC_MOUSE_BUTTON  = 2,
    DAC_MOUSE_WHEEL   = 4,
    DAC_MOUSE_HWHEEL  = 8,
};

struct dac_input_key
{
    uint32_t down;                 /* 1 按下 / 0 释放 */
    uint32_t keycode;              /* Android AKEYCODE_* */
    uint32_t meta_state;           /* AMETA_* 掩码 */
    uint32_t unicode;              /* 最佳猜测字符（可 0） */
    uint32_t repeat;
    uint32_t source;               /* 0=keyboard 1=gamepad 映射的按键 */
};

/* ---------- LOG ---------- */
struct dac_log
{
    uint32_t level;
    char     text[1];              /* 变长 */
};

/* ---------- AHB 像素格式（与 NDK AHARDWAREBUFFER_FORMAT_* 同值） ---------- */
enum dac_ahb_format
{
    DAC_AHB_FMT_RGBA8888    = 1,   /* AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM */
    DAC_AHB_FMT_RGBX8888    = 2,   /* AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM */
    DAC_AHB_FMT_RGB565      = 4,
    DAC_AHB_FMT_BGRA8888    = 5,   /* AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM */
    DAC_AHB_FMT_BGRX8888    = 6,
    DAC_AHB_FMT_RGBA_FP16   = 0x16,
    DAC_AHB_FMT_RGBA1010102 = 0x12,
};

/* AHB usage 位（与 NDK 同值） */
#define DAC_AHB_USAGE_CPU_READ_RARELY   (2ULL << 0)
#define DAC_AHB_USAGE_CPU_READ_OFTEN    (3ULL << 0)
#define DAC_AHB_USAGE_CPU_WRITE_RARELY  (2ULL << 4)
#define DAC_AHB_USAGE_CPU_WRITE_OFTEN   (3ULL << 4)
#define DAC_AHB_USAGE_GPU_COLOR_OUTPUT  (1ULL << 4)
#define DAC_AHB_USAGE_GPU_SAMPLED_IMAGE (1ULL << 8)
#define DAC_AHB_USAGE_GPU_FRAMEBUFFER   (1ULL << 9)
#define DAC_AHB_USAGE_COMPOSER_OVERLAY  (1ULL << 15)

/* 桌面槽位最大数（环形缓冲） */
#define DAC_DESKTOP_MAX_SLOTS 3
#define DAC_MAX_DAMAGE_RECTS  16
#define DAC_MAX_DMABUF_PLANES 4

#endif /* __WINEDAC_DAC_H */
