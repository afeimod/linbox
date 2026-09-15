/*
 * winedac.drv — 窗口管理 / 桌面合成 / 事件循环 / 输入
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * v1 显示模型（虚拟桌面整体合成）：
 *   - Wine 以虚拟桌面模式运行：wine explorer /desktop=dac,WxH app.exe
 *   - 每个顶层窗口一个 window_surface（CPU 位图，wine window_surface 标准约定，
 *     surface 矩形为窗口相对坐标，语义与 winex11 一致）
 *   - flush 时按 z 序把所有与脏区相交的可见窗口位图合成进桌面 AHB 槽位
 *   - PRESENT 帧 → app bridge → ASurfaceControl/ASurfaceTransaction →
 *     SurfaceFlinger 直接合成（API29+），或降级 Canvas / EGL 路径
 *   - DXVK 交换链由 vulkan.c 注册为独立图层（VK_LAYER_POS 定位）
 */

#if 0
#pragma makedep unix  /* 本文件仅编译 unix 侧（wine 9.2 驱动模型） */
#endif

#include "config.h"

#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define OEMRESOURCE
#include "windef.h"
#include "winbase.h"
#include "winternl.h"
#include "winuser.h"
#include "wingdi.h"
#include "winnls.h"
#include "ntuser.h"

#include "dacdrv.h"
#include "wine/server.h"
#include "wine/debug.h"

WINE_DEFAULT_DEBUG_CHANNEL(dac);

#define SWP_AGG_NOPOSCHANGE (SWP_NOSIZE | SWP_NOMOVE | SWP_NOCLIENTSIZE | SWP_NOCLIENTMOVE | SWP_NOZORDER)

/* =====================================================================
 * 前向声明
 * =================================================================== */
struct dac_surface;

static void composite_windows( uint32_t slot, const RECT *damage );
BOOL dac_WindowPosChanging( HWND hwnd, HWND insert_after, UINT swp_flags,
                            const RECT *window_rect, const RECT *client_rect,
                            RECT *visible_rect, struct window_surface **surface );

/* =====================================================================
 * 状态
 * =================================================================== */
struct dac_desktop dac_desktop = {0};
static pthread_mutex_t desktop_cs = PTHREAD_MUTEX_INITIALIZER;

static pthread_mutex_t win_data_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct dac_win_data *win_data_context[32768];

static BOOL   driver_ready;
static POINT  dac_cursor_pos;
static HWND   dac_capture_hwnd;
static HCURSOR dac_last_cursor;
static RECT    dac_cursor_clip;
static BOOL    dac_cursor_clip_on;

/* =====================================================================
 * 窗口私有数据
 * =================================================================== */
struct dac_win_data
{
    HWND   hwnd;
    RECT   window_rect;      /* USER 窗口矩形（屏幕坐标） */
    RECT   whole_rect;
    RECT   client_rect;
    BOOL   visible;
    struct window_surface *surface;
};

static inline int context_idx( HWND hwnd )
{
    return LOWORD( hwnd ) >> 1;
}

static struct dac_win_data *alloc_win_data( HWND hwnd )
{
    struct dac_win_data *data;
    if ((data = calloc( 1, sizeof(*data) )))
    {
        data->hwnd = hwnd;
        pthread_mutex_lock( &win_data_mutex );
        win_data_context[context_idx(hwnd)] = data;
    }
    return data;
}

static void free_win_data( struct dac_win_data *data )
{
    win_data_context[context_idx( data->hwnd )] = NULL;
    pthread_mutex_unlock( &win_data_mutex );
    if (data->surface) window_surface_release( data->surface );
    free( data );
}

static struct dac_win_data *get_win_data( HWND hwnd )
{
    struct dac_win_data *data;
    if (!hwnd) return NULL;
    pthread_mutex_lock( &win_data_mutex );
    if ((data = win_data_context[context_idx(hwnd)]) && data->hwnd == hwnd) return data;
    pthread_mutex_unlock( &win_data_mutex );
    return NULL;
}

static void release_win_data( struct dac_win_data *data )
{
    if (data) pthread_mutex_unlock( &win_data_mutex );
}

static BOOL intersect_rect( RECT *dst, const RECT *src1, const RECT *src2 )
{
    dst->left   = max( src1->left, src2->left );
    dst->top    = max( src1->top, src2->top );
    dst->right  = min( src1->right, src2->right );
    dst->bottom = min( src1->bottom, src2->bottom );
    return !IsRectEmpty( dst );
}

RECT dac_desktop_rect(void)
{
    return dac_desktop.rect;
}

/* =====================================================================
 * window_surface 实现（每顶层窗口一张 CPU 位图）
 * =================================================================== */
struct dac_surface
{
    struct window_surface header;
    HWND                  hwnd;
    BITMAPINFO            info;
    void                 *bits;
    RECT                  bounds;    /* 脏区（surface 坐标） */
    pthread_mutex_t       cs;
};

static inline struct dac_surface *get_dac_surface( struct window_surface *surface )
{
    return CONTAINING_RECORD( surface, struct dac_surface, header );
}

/* ---- unix 侧替代：win32 的 UnionRect / WideCharToMultiByte 在 .so 链接期不存在 ---- */
static void dac_union_rect( RECT *dst, const RECT *r1, const RECT *r2 )
{
    BOOL e1 = (r1->left >= r1->right || r1->top >= r1->bottom);
    BOOL e2 = (r2->left >= r2->right || r2->top >= r2->bottom);
    if (e1 && e2) { dst->left = dst->top = dst->right = dst->bottom = 0; return; }
    if (e1) { *dst = *r2; return; }
    if (e2) { *dst = *r1; return; }
    dst->left   = (r1->left   < r2->left)   ? r1->left   : r2->left;
    dst->top    = (r1->top    < r2->top)    ? r1->top    : r2->top;
    dst->right  = (r1->right  > r2->right)  ? r1->right  : r2->right;
    dst->bottom = (r1->bottom > r2->bottom) ? r1->bottom : r2->bottom;
}

/* UTF-16 → UTF-8（含代理对）；返回写入字节数（含终止 0），截断时安全收敛 */
static int dac_wc_to_utf8( const WCHAR *src, char *dst, int dst_size )
{
    unsigned int i = 0, o = 0;
    if (dst_size <= 0) return 0;
    while (src[i])
    {
        unsigned int c = src[i++], n;
        if (c >= 0xd800 && c <= 0xdbff && src[i] >= 0xdc00 && src[i] <= 0xdfff)
            c = 0x10000 + ((c - 0xd800) << 10) + (src[i++] - 0xdc00);
        if (c < 0x80)      { n = 1; }
        else if (c < 0x800){ n = 2; }
        else if (c < 0x10000){ n = 3; }
        else               { n = 4; }
        if (o + n >= (unsigned)dst_size) break;
        if (n == 1) dst[o++] = c;
        else if (n == 2) { dst[o++] = 0xc0 | (c >> 6);  dst[o++] = 0x80 | (c & 0x3f); }
        else if (n == 3) { dst[o++] = 0xe0 | (c >> 12); dst[o++] = 0x80 | ((c >> 6) & 0x3f); dst[o++] = 0x80 | (c & 0x3f); }
        else { dst[o++] = 0xf0 | (c >> 18); dst[o++] = 0x80 | ((c >> 12) & 0x3f); dst[o++] = 0x80 | ((c >> 6) & 0x3f); dst[o++] = 0x80 | (c & 0x3f); }
    }
    dst[o] = 0;
    return o + 1;
}

static void dac_surface_lock( struct window_surface *window_surface )
{
    pthread_mutex_lock( &get_dac_surface(window_surface)->cs );
}

static void dac_surface_unlock( struct window_surface *window_surface )
{
    pthread_mutex_unlock( &get_dac_surface(window_surface)->cs );
}

static void *dac_surface_get_info( struct window_surface *window_surface, BITMAPINFO *info )
{
    struct dac_surface *surface = get_dac_surface( window_surface );
    memcpy( info, &surface->info, sizeof(BITMAPINFOHEADER) + 3 * sizeof(DWORD) );
    return surface->bits;
}

static RECT *dac_surface_get_bounds( struct window_surface *window_surface )
{
    return &get_dac_surface( window_surface )->bounds;
}

static void dac_surface_set_region( struct window_surface *window_surface, HRGN region )
{
    /* 可见区域裁剪由 wine 绘制阶段完成；合成器按矩形 blit */
}

static void dac_surface_flush( struct window_surface *window_surface )
{
    struct dac_surface *surface = get_dac_surface( window_surface );
    RECT bounds, screen;
    struct dac_win_data *data;

    pthread_mutex_lock( &surface->cs );
    bounds = surface->bounds;
    SetRectEmpty( &surface->bounds );
    pthread_mutex_unlock( &surface->cs );

    if (IsRectEmpty( &bounds )) return;

    data = get_win_data( surface->hwnd );
    if (!data) return;
    screen.left   = data->window_rect.left + bounds.left;
    screen.top    = data->window_rect.top  + bounds.top;
    screen.right  = screen.left + (bounds.right - bounds.left);
    screen.bottom = screen.top  + (bounds.bottom - bounds.top);
    release_win_data( data );

    dac_desktop_present( &screen );
}

static void dac_surface_destroy( struct window_surface *window_surface )
{
    struct dac_surface *surface = get_dac_surface( window_surface );
    TRACE( "destroy surface hwnd %p\n", surface->hwnd );
    pthread_mutex_destroy( &surface->cs );
    free( surface->bits );
    free( surface );
}

static const struct window_surface_funcs dac_surface_funcs =
{
    dac_surface_lock,
    dac_surface_unlock,
    dac_surface_get_info,
    dac_surface_get_bounds,
    dac_surface_set_region,
    dac_surface_flush,
    dac_surface_destroy,
};

/* surface 矩形语义与 winex11 相同：可见区平移为窗口相对坐标，32 对齐 */
static BOOL get_surface_rect( const RECT *visible_rect, RECT *surface_rect )
{
    *surface_rect = NtUserGetVirtualScreenRect();
    if (!intersect_rect( surface_rect, surface_rect, visible_rect ))
        return FALSE;
    OffsetRect( surface_rect, -visible_rect->left, -visible_rect->top );
    surface_rect->left &= ~31;
    surface_rect->top  &= ~31;
    surface_rect->right  = max( surface_rect->left + 32, (surface_rect->right + 31) & ~31 );
    surface_rect->bottom = max( surface_rect->top + 32, (surface_rect->bottom + 31) & ~31 );
    return TRUE;
}

static struct window_surface *create_window_surface( HWND hwnd, const RECT *surface_rect )
{
    struct dac_surface *surface;
    int width  = surface_rect->right - surface_rect->left;
    int height = surface_rect->bottom - surface_rect->top;
    DWORD *pixels;

    if (!(surface = calloc( 1, FIELD_OFFSET( struct dac_surface, info.bmiColors[3] ) )))
        return NULL;

    surface->header.funcs = &dac_surface_funcs;
    surface->header.rect  = *surface_rect;
    surface->header.ref   = 1;
    surface->hwnd         = hwnd;

    surface->info.bmiHeader.biSize        = sizeof(BITMAPINFOHEADER);
    surface->info.bmiHeader.biWidth       = width;
    surface->info.bmiHeader.biHeight      = -height;   /* top-down */
    surface->info.bmiHeader.biPlanes      = 1;
    surface->info.bmiHeader.biBitCount    = 32;
    surface->info.bmiHeader.biCompression = BI_RGB;
    surface->info.bmiHeader.biSizeImage   = width * height * 4;
    surface->info.bmiColors[0].rgbRed     = 0xff;
    surface->info.bmiColors[1].rgbGreen   = 0xff;
    surface->info.bmiColors[2].rgbBlue    = 0xff;

    if (!(pixels = calloc( 1, (size_t)width * height * 4 )))
    {
        free( surface );
        return NULL;
    }
    surface->bits = pixels;

    pthread_mutex_init( &surface->cs, NULL );
    TRACE( "created surface hwnd %p %dx%d\n", hwnd, width, height );
    return &surface->header;
}

/* =====================================================================
 * 桌面槽位（AHB 环形缓冲）与合成器
 * =================================================================== */
static BOOL desktop_create_slots( uint32_t width, uint32_t height )
{
    static const uint64_t desktop_usage =
        DAC_AHB_USAGE_CPU_WRITE_OFTEN | DAC_AHB_USAGE_CPU_READ_OFTEN |
        DAC_AHB_USAGE_GPU_SAMPLED_IMAGE;
    int i;

    dac_desktop.width  = width;
    dac_desktop.height = height;
    dac_desktop.rect.left = 0; dac_desktop.rect.top = 0;
    dac_desktop.rect.right = width; dac_desktop.rect.bottom = height;
    dac_desktop.next_slot = 0;
    dac_desktop.slot_pending = 0;

    for (i = 0; i < DAC_DESKTOP_MAX_SLOTS; i++)
    {
        struct dac_buffer_meta meta;
        struct dac_ahb *slot = &dac_desktop.slots[i];

        if (!dac_platform_alloc_ahb( width, height, DAC_AHB_FMT_RGBA8888,
                                     desktop_usage, slot ))
        {
            ERR( "desktop AHB %ux%u alloc failed (slot %d)\n", width, height, i );
            return FALSE;
        }

        memset( &meta, 0, sizeof(meta) );
        meta.slot     = i;
        meta.width    = slot->width;
        meta.height   = slot->height;
        meta.stride   = slot->stride;
        meta.format   = slot->format;
        meta.usage    = slot->usage;
        meta.is_dmabuf = 0;

        /* 先发帧，紧接着序列化 AHB 句柄（bridge 顺序导入，见 DAC-PROTOCOL.md） */
        if (dac_send_msg( DAC_MSG_DESKTOP_ALLOC, &meta, sizeof(meta), NULL, 0 ) ||
            dac_platform_send_ahb( slot ))
        {
            ERR( "send DESKTOP_ALLOC slot %d failed\n", i );
            return FALSE;
        }
        TRACE( "desktop slot %d %ux%u stride %u fmt %u\n",
               i, slot->width, slot->height, slot->stride, slot->format );
    }
    return TRUE;
}

BOOL dac_desktop_ensure( uint32_t width, uint32_t height )
{
    static BOOL tried;

    if (dac_desktop.ready && dac_desktop.width == width &&
        dac_desktop.height == height) return TRUE;

    pthread_mutex_lock( &desktop_cs );

    if (!dac_connected() && !tried)
    {
        tried = TRUE;
        if (!dac_connect( "winedac" ))
            start_event_thread();
    }

    if (dac_connected())
    {
        int i;
        if (dac_desktop.ready)
        {
            for (i = 0; i < DAC_DESKTOP_MAX_SLOTS; i++)
            {
                if (dac_desktop.slots[i].width)
                {
                    uint32_t s = i;
                    dac_send_msg( DAC_MSG_DESKTOP_FREE, &s, sizeof(s), NULL, 0 );
                    dac_platform_free_ahb( &dac_desktop.slots[i] );
                }
            }
            dac_desktop.ready = FALSE;
        }
        dac_desktop.ready = desktop_create_slots( width, height );
        if (dac_desktop.ready)
            TRACE( "desktop ready %ux%u\n", width, height );
    }
    pthread_mutex_unlock( &desktop_cs );

    if (!dac_desktop.ready)
        WARN( "desktop %ux%u not ready (bridge 未连接)\n", width, height );
    return dac_desktop.ready;
}

/*
 * 软件合成器：把所有与 damage 相交的可见窗口位图按 z 序
 * （自底向上）blit 进桌面槽位。
 */
static void composite_windows( uint32_t slot, const RECT *damage )
{
    struct dac_ahb *dst = &dac_desktop.slots[slot];
    void *dst_bits;
    uint32_t dst_stride;
    HWND hwnd, last;
    int row;

    if (!dst->width) return;

    dst_bits = dac_platform_map_ahb( dst, TRUE );
    if (!dst_bits)
    {
        ERR( "map desktop slot %u failed\n", slot );
        return;
    }
    dst_stride = dst->stride * 4;

    last = NtUserGetWindowRelative( NtUserGetDesktopWindow(), GW_HWNDLAST );
    for (hwnd = last; hwnd; hwnd = NtUserGetWindowRelative( hwnd, GW_HWNDPREV ))
    {
        struct dac_win_data *data = get_win_data( hwnd );
        struct dac_surface *surface;
        RECT wnd, vis, clip;
        int src_height, bottom_up, src_stride;
        unsigned char *src_base;

        if (!data) continue;
        if (!data->visible || !data->surface)
        {
            release_win_data( data );
            continue;
        }
        wnd = data->window_rect;
        surface = get_dac_surface( data->surface );

        vis.left   = wnd.left + surface->header.rect.left;
        vis.top    = wnd.top  + surface->header.rect.top;
        vis.right  = vis.left + (surface->header.rect.right - surface->header.rect.left);
        vis.bottom = vis.top  + (surface->header.rect.bottom - surface->header.rect.top);

        if (!intersect_rect( &clip, &vis, damage ))
        {
            release_win_data( data );
            continue;
        }

        data->surface->funcs->lock( data->surface );
        {
            BITMAPINFO bmi = { { 0 } };
            void *bits = data->surface->funcs->get_info( data->surface, &bmi );
            if (!bits || bmi.bmiHeader.biBitCount != 32)
            {
                data->surface->funcs->unlock( data->surface );
                release_win_data( data );
                continue;
            }
            src_height = abs( bmi.bmiHeader.biHeight );
            bottom_up  = bmi.bmiHeader.biHeight > 0;
            src_stride = bmi.bmiHeader.biWidth * 4;
            src_base   = bits;

            for (row = clip.top; row < clip.bottom; row++)
            {
                int sy = row - wnd.top;
                unsigned char *src, *dstp;
                int copy_x = max( wnd.left, clip.left );
                int copy_w = min( wnd.right, clip.right ) - copy_x;
                if (copy_w <= 0) continue;
                if (sy < 0 || sy >= src_height) continue;
                if (bottom_up) sy = src_height - 1 - sy;
                src  = (unsigned char *)src_base + (size_t)sy * src_stride
                       + (size_t)(copy_x - wnd.left) * 4;
                dstp = (unsigned char *)dst_bits + (size_t)row * dst_stride
                       + (size_t)copy_x * 4;
                memcpy( dstp, src, (size_t)copy_w * 4 );
            }
        }
        data->surface->funcs->unlock( data->surface );
        release_win_data( data );
    }

    dac_platform_unmap_ahb( dst );
}

/* present 当前桌面内容（damage 为屏幕坐标脏区）。
 * 单帧携带 [struct dac_present][damage rects]，避免多余帧型。 */
void dac_desktop_present( const RECT *damage )
{
    unsigned char buf[sizeof(struct dac_present) + sizeof(struct dac_rect)];
    struct dac_present *present = (struct dac_present *)buf;
    struct dac_rect *r = (struct dac_rect *)(buf + sizeof(*present));
    RECT dmg;
    uint32_t slot;

    if (!dac_desktop.ready || !dac_connected()) return;

    /* 槽位背压：全部被 bridge 持有时丢弃本帧（v1 简化策略） */
    if ((dac_desktop.slot_pending & ((1u << DAC_DESKTOP_MAX_SLOTS) - 1)) ==
        ((1u << DAC_DESKTOP_MAX_SLOTS) - 1))
        return;

    pthread_mutex_lock( &desktop_cs );
    slot = dac_desktop.next_slot;
    dac_desktop.next_slot = (dac_desktop.next_slot + 1) % DAC_DESKTOP_MAX_SLOTS;
    pthread_mutex_unlock( &desktop_cs );

    dmg = *damage;
    OffsetRect( &dmg, -dac_desktop.rect.left, -dac_desktop.rect.top );
    if (dmg.left < 0) dmg.left = 0;
    if (dmg.top < 0) dmg.top = 0;
    if (dmg.right > (int)dac_desktop.width) dmg.right = dac_desktop.width;
    if (dmg.bottom > (int)dac_desktop.height) dmg.bottom = dac_desktop.height;
    if (dmg.left >= dmg.right || dmg.top >= dmg.bottom) return;

    composite_windows( slot, &dmg );

    memset( present, 0, sizeof(*present) );
    present->slot = slot;
    present->surface_id = 0;
    present->damage_count = 1;
    r->left = dmg.left; r->top = dmg.top; r->right = dmg.right; r->bottom = dmg.bottom;

    if (!dac_send_msg( DAC_MSG_DESKTOP_PRESENT, buf, sizeof(buf), NULL, 0 ))
        dac_desktop.slot_pending |= (1u << slot);
}

/* =====================================================================
 * 驱动窗口函数
 * =================================================================== */
BOOL dac_CreateDesktop( const WCHAR *name, UINT width, UINT height )
{
    TRACE( "virtual desktop %ux%u\n", width, height );
    driver_ready = TRUE;
    if (!dac_desktop_ensure( width, height ))
        ERR( "desktop init failed — LinBox DAC 窗口未开启？\n" );
    return TRUE;
}

BOOL dac_CreateWindow( HWND hwnd )
{
    struct dac_win_data *data = alloc_win_data( hwnd );
    if (!data) return FALSE;
    release_win_data( data );
    return TRUE;
}

void dac_DestroyWindow( HWND hwnd )
{
    struct dac_win_data *data = get_win_data( hwnd );
    if (!data) return;
    free_win_data( data );
}

BOOL dac_WindowPosChanging( HWND hwnd, HWND insert_after, UINT swp_flags,
                            const RECT *window_rect, const RECT *client_rect,
                            RECT *visible_rect, struct window_surface **surface )
{
    struct dac_win_data *data = get_win_data( hwnd );
    RECT surface_rect;

    if (!data && !(data = alloc_win_data( hwnd ))) return TRUE;

    data->window_rect = *window_rect;
    data->client_rect = *client_rect;

    *visible_rect = *window_rect;
    if (dac_desktop.ready)
        intersect_rect( visible_rect, visible_rect, &dac_desktop.rect );

    data->whole_rect = *visible_rect;
    data->visible    = !(swp_flags & SWP_HIDEWINDOW) &&
                       (NtUserGetWindowLongW( hwnd, GWL_STYLE ) & WS_VISIBLE);

    if (!data->visible) goto done;
    if (NtUserGetWindowLongW( hwnd, GWL_EXSTYLE ) & WS_EX_LAYERED) goto done;
    if (!get_surface_rect( visible_rect, &surface_rect )) goto done;

    if (*surface) window_surface_release( *surface );
    *surface = NULL;

    if (data->surface)
    {
        if (EqualRect( &data->surface->rect, &surface_rect ))
        {
            window_surface_add_ref( data->surface );
            *surface = data->surface;
            goto done;
        }
        window_surface_release( data->surface );
        data->surface = NULL;
    }

    data->surface = create_window_surface( hwnd, &surface_rect );
    if (data->surface)
    {
        *surface = data->surface;
        /* 新表面整体重绘 */
        data->surface->funcs->lock( data->surface );
        *data->surface->funcs->get_bounds( data->surface ) = data->surface->rect;
        data->surface->funcs->unlock( data->surface );
    }

done:
    release_win_data( data );
    return TRUE;
}

void dac_WindowPosChanged( HWND hwnd, HWND insert_after, UINT swp_flags,
                           const RECT *rect_window, const RECT *rect_client,
                           const RECT *visible_rect, const RECT *valid_rects,
                           struct window_surface *surface )
{
    struct dac_win_data *data = get_win_data( hwnd );
    RECT damage, old;

    if (!data) return;

    old = data->window_rect;
    data->window_rect = *rect_window;
    data->client_rect = *rect_client;
    data->whole_rect  = *visible_rect;
    data->visible     = NtUserGetWindowLongW( hwnd, GWL_STYLE ) & WS_VISIBLE;

    if (surface && surface != data->surface)
    {
        if (surface) window_surface_add_ref( surface );
        if (data->surface) window_surface_release( data->surface );
        data->surface = surface;
    }

    dac_union_rect( &damage, &old, rect_window );
    release_win_data( data );

    if (!(swp_flags & SWP_AGG_NOPOSCHANGE) || (swp_flags & (SWP_HIDEWINDOW | SWP_SHOWWINDOW)))
    {
        dac_vulkan_notify_window_rect( hwnd, rect_window );
        dac_desktop_present( &damage );
    }
}

void dac_SetWindowText( HWND hwnd, LPCWSTR text )
{
    struct { uint64_t hwnd; char text[512]; } payload = { 0 };
    payload.hwnd = (uintptr_t)hwnd;
    if (text)
        dac_wc_to_utf8( text, payload.text, sizeof(payload.text) );
    dac_send_msg( DAC_MSG_TITLE, &payload, sizeof(payload), NULL, 0 );
}

BOOL dac_GetCursorPos( POINT *pt )
{
    *pt = dac_cursor_pos;
    if (dac_cursor_clip_on)
    {
        if (pt->x < dac_cursor_clip.left) pt->x = dac_cursor_clip.left;
        if (pt->x >= dac_cursor_clip.right) pt->x = dac_cursor_clip.right - 1;
        if (pt->y < dac_cursor_clip.top) pt->y = dac_cursor_clip.top;
        if (pt->y >= dac_cursor_clip.bottom) pt->y = dac_cursor_clip.bottom - 1;
    }
    return TRUE;
}

void dac_set_cursor_clip( LPCRECT rect, BOOL enable )
{
    if (enable && rect)
    {
        dac_cursor_clip = *rect;
        dac_cursor_clip_on = TRUE;
    }
    else dac_cursor_clip_on = FALSE;
}

BOOL dac_SetCursorPos( int x, int y )
{
    dac_cursor_pos.x = x;
    dac_cursor_pos.y = y;
    return TRUE;
}

void dac_SetCapture( HWND hwnd, DWORD flags )
{
    dac_capture_hwnd = hwnd;
}

void dac_SetFocus( HWND hwnd )
{
    TRACE( "focus %p\n", hwnd );
}

BOOL dac_ProcessEvents( DWORD mask )
{
    return 0;   /* 输入由事件线程直接注入 */
}

/* =====================================================================
 * 光标
 * =================================================================== */
void dac_set_cursor_shape( HCURSOR cursor )
{
    struct dac_cursor c = { 0 };

    if (cursor == dac_last_cursor) return;
    dac_last_cursor = cursor;

    c.visible = 1;
    c.x = dac_cursor_pos.x - dac_desktop.rect.left;
    c.y = dac_cursor_pos.y - dac_desktop.rect.top;
    c.shape = DAC_CURSOR_ARROW;
    dac_send_msg( DAC_MSG_CURSOR, &c, sizeof(c), NULL, 0 );
}

/* =====================================================================
 * 事件线程：bridge 事件（输入 / 释放 / 尺寸）→ wine
 * =================================================================== */
static void handle_input_mouse( const struct dac_input_mouse *m )
{
    INPUT input;

    if (m->flags & (DAC_MOUSE_WHEEL | DAC_MOUSE_HWHEEL))
    {
        memset( &input, 0, sizeof(input) );
        input.type = INPUT_MOUSE;
        if (m->flags & DAC_MOUSE_HWHEEL)
        {
            input.mi.dwFlags = MOUSEEVENTF_HWHEEL;
            input.mi.mouseData = (m->wheel_dx > 0) ? WHEEL_DELTA : (DWORD)(-(LONG)WHEEL_DELTA);
        }
        else
        {
            input.mi.dwFlags = MOUSEEVENTF_WHEEL;
            input.mi.mouseData = (m->wheel_dy > 0) ? WHEEL_DELTA : (DWORD)(-(LONG)WHEEL_DELTA);
        }
        __wine_send_input( 0, &input, NULL );
        return;
    }

    if (m->flags & DAC_MOUSE_MOVE)
    {
        dac_cursor_pos.x = m->x + dac_desktop.rect.left;
        dac_cursor_pos.y = m->y + dac_desktop.rect.top;

        memset( &input, 0, sizeof(input) );
        input.type = INPUT_MOUSE;
        input.mi.dwFlags = MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_MOVE;
        input.mi.dx = dac_cursor_pos.x * 65535 / (max( 1, (int)dac_desktop.width  - 1) );
        input.mi.dy = dac_cursor_pos.y * 65535 / (max( 1, (int)dac_desktop.height - 1) );
        __wine_send_input( 0, &input, NULL );
    }

    if (m->flags & DAC_MOUSE_BUTTON)
    {
        static const DWORD down_flags[5] =
        {
            MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_MIDDLEDOWN,
            MOUSEEVENTF_XDOWN, MOUSEEVENTF_XDOWN
        };
        static const DWORD up_flags[5] =
        {
            MOUSEEVENTF_LEFTUP, MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_MIDDLEUP,
            MOUSEEVENTF_XUP, MOUSEEVENTF_XUP
        };
        static uint32_t prev_buttons;
        uint32_t changed = m->buttons ^ prev_buttons;
        unsigned i;

        for (i = 0; i < 5; i++)
        {
            if (!(changed & (1u << i))) continue;
            memset( &input, 0, sizeof(input) );
            input.type = INPUT_MOUSE;
            input.mi.dwFlags = (m->buttons & (1u << i)) ? down_flags[i] : up_flags[i];
            if (i >= 3)
                input.mi.mouseData = (i == 3) ? XBUTTON1 : XBUTTON2;
            __wine_send_input( 0, &input, NULL );
        }
        prev_buttons = m->buttons;
    }
}

void dac_process_bridge_event( uint32_t type, const void *payload, uint32_t len,
                               int *fds, int fd_count )
{
    switch (type)
    {
    case DAC_MSG_INPUT_MOUSE:
        if (len >= sizeof(struct dac_input_mouse))
            handle_input_mouse( payload );
        break;

    case DAC_MSG_INPUT_KEY:
        if (len >= sizeof(struct dac_input_key))
            dac_kbd_send_key( payload );
        break;

    case DAC_MSG_RELEASE:
        if (len >= sizeof(uint32_t))
        {
            uint32_t slot = *(const uint32_t *)payload;
            if (slot < DAC_DESKTOP_MAX_SLOTS)
            {
                dac_desktop.slot_pending &= ~(1u << slot);
                TRACE( "slot %u released\n", slot );
            }
        }
        break;

    case DAC_MSG_VK_RELEASED:
        vulkan_surface_released( payload, len, fds, fd_count );
        break;

    case DAC_MSG_DESKTOP_RESIZE:
        if (len >= 2 * sizeof(uint32_t))
        {
            const uint32_t *wh = payload;
            TRACE( "desktop resize %ux%u\n", wh[0], wh[1] );
            dac_desktop_ensure( wh[0], wh[1] );
        }
        break;

    default:
        TRACE( "unhandled bridge msg %u len %u\n", type, len );
        break;
    }
}

static void *event_thread_proc( void *arg )
{
    unsigned char buf[4096];
    uint32_t type, len;
    int fds[DAC_MAX_DMABUF_PLANES], fd_count = 0;

    for (;;)
    {
        int res = dac_read_event( &type, buf, sizeof(buf), &len, fds, &fd_count, 500 );
        if (res < 0)
        {
            TRACE( "bridge closed, event thread exit\n" );
            break;
        }
        if (res > 0)
        {
            if (!dac_connected()) break;
            continue;
        }
        dac_process_bridge_event( type, buf, len, fds, fd_count );
        dac_close_recv_fds( fds, fd_count );
    }
    return NULL;
}

void start_event_thread(void)
{
    pthread_t tid;
    if (pthread_create( &tid, NULL, event_thread_proc, NULL ))
    {
        ERR( "failed to start event thread\n" );
        return;
    }
    pthread_detach( tid );
}
