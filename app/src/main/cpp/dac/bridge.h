/*
 * LinBox DAC bridge — app 侧协议镜像定义
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 与 wine/dlls/winedac.drv/dac.h 保持同步！（布局必须一致）
 * 本文件追加 JNI / ANativeWindow 侧专用声明。
 */

#ifndef LINBOX_DAC_BRIDGE_H
#define LINBOX_DAC_BRIDGE_H

#include <stdint.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/surface_control.h>

#define DAC_PROTOCOL_MAGIC     0x44414332u
#define DAC_PROTOCOL_VERSION   1

#define DAC_SOCKET_NAME        "linbox-dac.sock"
#define DAC_ALLOCD_SOCKET_NAME "linbox-dac-allocd.sock"

enum dac_msg_type
{
    /* wine → app */
    DAC_MSG_HELLO           = 1,
    DAC_MSG_DESKTOP_ALLOC   = 2,
    DAC_MSG_DESKTOP_FREE    = 3,
    DAC_MSG_DESKTOP_PRESENT = 4,
    DAC_MSG_VK_SURFACE_NEW  = 10,
    DAC_MSG_VK_IMAGE        = 11,
    DAC_MSG_VK_SURFACE_DEL  = 12,
    DAC_MSG_VK_PRESENT      = 13,
    DAC_MSG_VK_LAYER_POS    = 15,
    DAC_MSG_CURSOR          = 20,
    DAC_MSG_TITLE           = 21,
    DAC_MSG_PING            = 30,
    DAC_MSG_LOG             = 40,

    /* app → wine */
    DAC_MSG_HELLO_ACK       = 100,
    DAC_MSG_RELEASE         = 101,
    DAC_MSG_VK_RELEASED     = 102,
    DAC_MSG_DESKTOP_RESIZE  = 103,
    DAC_MSG_INPUT_MOUSE     = 110,
    DAC_MSG_INPUT_KEY       = 111,
    DAC_MSG_INPUT_GAMEPAD   = 112,
    DAC_MSG_PONG            = 130,
};

struct dac_frame_hdr
{
    uint32_t magic;
    uint32_t type;
    uint32_t len;
};

struct dac_hello
{
    uint32_t proto_version;
    uint32_t desktop_width;
    uint32_t desktop_height;
    uint32_t fd_count;
    char     exe[256];
};

struct dac_hello_ack
{
    uint32_t ok;
    uint32_t backend;
    uint32_t api_level;
    uint32_t max_desktop_slots;
};

enum dac_backend
{
    DAC_BACKEND_SF_DIRECT = 0,
    DAC_BACKEND_AHB_CANVAS = 1,
    DAC_BACKEND_DMABUF_EGL = 2,
    DAC_BACKEND_NONE = 3,
};

struct dac_buffer_meta
{
    uint32_t slot;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t format;
    uint32_t fd_count;
    uint32_t is_dmabuf;
    uint64_t usage;
    uint64_t offset;
    uint64_t modifier;
};

struct dac_present
{
    uint32_t slot;
    uint64_t surface_id;
    uint32_t damage_count;
};

struct dac_rect
{
    int32_t left, top, right, bottom;
};

struct dac_vk_surface_new
{
    uint64_t id;
    uint32_t image_count;
    uint32_t format;
    uint32_t width;
    uint32_t height;
    uint32_t is_dmabuf;
};

struct dac_vk_layer_pos
{
    uint64_t id;
    int32_t  x, y;
    uint32_t width, height;
    uint32_t z;
};

struct dac_cursor
{
    uint32_t visible;
    int32_t  x, y;
    uint32_t shape;
};

struct dac_input_mouse
{
    uint32_t flags;
    int32_t  x, y;
    uint32_t buttons;
    int32_t  wheel_dx, wheel_dy;
    uint32_t absolute;
};

enum dac_mouse_flags
{
    DAC_MOUSE_MOVE   = 1,
    DAC_MOUSE_BUTTON = 2,
    DAC_MOUSE_WHEEL  = 4,
    DAC_MOUSE_HWHEEL = 8,
};

struct dac_input_key
{
    uint32_t down;
    uint32_t keycode;
    uint32_t meta_state;
    uint32_t unicode;
    uint32_t repeat;
    uint32_t source;
};

#define DAC_DESKTOP_MAX_SLOTS 3
#define DAC_MAX_DMABUF_PLANES 4

/* ------- JNI 桥接口（由 DacView / DacApp 调用） ------- */
/*
 * nativeConnect(surface, socketPath) → backend
 *   启动 socket 服务线程；surface 为 SurfaceView 的 Surface。
 */
JNIEXPORT jint JNICALL
Java_com_linbox_apps_dac_DacNative_nativeConnect( JNIEnv *env, jclass clazz,
                                                  jobject surface, jstring socket_path );

JNIEXPORT void JNICALL
Java_com_linbox_apps_dac_DacNative_nativeDisconnect( JNIEnv *env, jclass clazz );

/* 输入注入（LinBox 虚拟键鼠 → wine） */
JNIEXPORT void JNICALL
Java_com_linbox_apps_dac_DacNative_nativeSendMouse( JNIEnv *env, jclass clazz,
        jint flags, jfloat x, jfloat y, jint buttons, jfloat wheel_dx, jfloat wheel_dy,
        jboolean absolute );

JNIEXPORT void JNICALL
Java_com_linbox_apps_dac_DacNative_nativeSendKey( JNIEnv *env, jclass clazz,
        jboolean down, jint keycode, jint meta_state, jint unicode, jint repeat,
        jint source );

/* 事件回调（C → Kotlin）：新标题 */
JNIEXPORT void JNICALL
Java_com_linbox_apps_dac_DacNative_nativeSetTitleSink( JNIEnv *env, jclass clazz,
                                                       jobject sink );

#endif /* LINBOX_DAC_BRIDGE_H */
