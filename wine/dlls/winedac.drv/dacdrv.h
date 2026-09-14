/*
 * winedac.drv 内部公共声明（unix 侧）
 *
 * Copyright 2026 LinBox Project (MIT)
 */

#ifndef __WINEDAC_INTERNAL_H
#define __WINEDAC_INTERNAL_H

#include <stdint.h>
#include <pthread.h>

#define WIN32_NO_STATUS
#include "windef.h"
#include "winbase.h"
#include "winuser.h"
#include "wingdi.h"

#include "wine/gdi_driver.h"
#include "dac.h"

/* ---------- protocol.c ---------- */
extern int  dac_connect( const char *exe_name );
extern void dac_disconnect(void);
extern int  dac_connected(void);
extern int  dac_send_msg( uint32_t type, const void *payload, uint32_t len,
                          const int *fds, int fd_count );
extern int  dac_recv_msg( uint32_t *type, void *buf, uint32_t buf_size,
                          uint32_t *len_out, int *fds, int *fd_count );
extern void dac_close_recv_fds( int *fds, int n );
extern int  dac_sock_fd(void);
extern int  dac_read_event( uint32_t *type, void *buf, uint32_t buf_size,
                            uint32_t *len_out, int *fds, int *fd_count,
                            int timeout_ms );

/* ---------- platform.c（AHB 后端） ---------- */
struct dac_ahb
{
    void   *ahb;          /* AHardwareBuffer*（bionic）或 NULL */
    int     fd;           /* 原始 dmabuf fd（mmap 用；-1 无） */
    void   *map;          /* CPU 映射（桌面槽位用） */
    uint32_t width, height, stride;   /* stride 单位像素 */
    uint32_t format;
    uint64_t usage;
    /* SIDECAR 模式：从 dac_allocd 代理来的序列化 AHB 句柄（原样转发给 bridge） */
    unsigned char pending_handle[512];
    uint32_t    pending_len;
    int         pending_fds[DAC_MAX_DMABUF_PLANES];
    int         pending_fd_count;
};

enum dac_ahb_mode
{
    DAC_AHB_MODE_BIONIC = 0,   /* 进程内 dlopen libandroid */
    DAC_AHB_MODE_SIDECAR = 1,  /* 经 dac_allocd 守护进程 */
    DAC_AHB_MODE_DMABUF = 2,   /* Vulkan 导出的 dmabuf 直接用（VK 图像路径） */
};

extern int  dac_platform_init( void );
extern enum dac_ahb_mode dac_platform_mode(void);
extern BOOL dac_platform_alloc_ahb( uint32_t w, uint32_t h, uint32_t format,
                                    uint64_t usage, struct dac_ahb *out );
extern void dac_platform_free_ahb( struct dac_ahb *buf );
extern int  dac_platform_send_ahb( const struct dac_ahb *buf );  /* 序列化句柄 → socket */
extern void *dac_platform_map_ahb( struct dac_ahb *buf, BOOL write );
extern void dac_platform_unmap_ahb( struct dac_ahb *buf );
extern int  dac_platform_import_dmabuf( int fd, uint32_t w, uint32_t h,
                                        uint32_t stride_px, struct dac_ahb *out );

/* ---------- vulkan.c ---------- */
struct vulkan_funcs;   /* wine/vulkan_driver.h */
extern const struct vulkan_funcs *dac_vulkan_driver_init( UINT version );
/* 由 window.c 提供给 vulkan.c：某 hwnd 对应的 VK layer 更新 */
extern void dac_vulkan_notify_window_rect( HWND hwnd, const RECT *desktop_rect );

/* ---------- window.c（驱动表入口，init.c 引用） ---------- */
extern BOOL  dac_CreateDesktop( const WCHAR *name, UINT width, UINT height );
extern BOOL  dac_CreateWindow( HWND hwnd );
extern void  dac_DestroyWindow( HWND hwnd );
extern BOOL  dac_WindowPosChanging( HWND hwnd, HWND insert_after, UINT swp_flags,
                                    const RECT *window_rect, const RECT *client_rect,
                                    RECT *visible_rect, struct window_surface **surface );
extern void  dac_WindowPosChanged( HWND hwnd, HWND insert_after, UINT swp_flags,
                                   const RECT *rect_window, const RECT *rect_client,
                                   const RECT *visible_rect, const RECT *valid_rects,
                                   struct window_surface *surface );
extern void  dac_SetWindowText( HWND hwnd, LPCWSTR text );
extern BOOL  dac_GetCursorPos( POINT *pt );
extern BOOL  dac_SetCursorPos( int x, int y );
extern void  dac_set_cursor_clip( LPCRECT rect, BOOL enable );
extern void  dac_SetCapture( HWND hwnd, DWORD flags );
extern void  dac_SetFocus( HWND hwnd );
extern BOOL  dac_ProcessEvents( DWORD mask );
extern BOOL  dac_desktop_ensure( uint32_t width, uint32_t height );
extern void  dac_desktop_present( const RECT *damage );
extern RECT  dac_desktop_rect(void);
extern void  dac_process_bridge_event( uint32_t type, const void *payload, uint32_t len,
                                       int *fds, int fd_count );
extern void  dac_set_cursor_shape( HCURSOR cursor );
extern void  start_event_thread(void);
extern void  vulkan_surface_released( const void *payload, uint32_t len,
                                      const int *fds, int fd_count );

/* ---------- keyboard.c ---------- */
extern void  dac_kbd_send_key( const struct dac_input_key *key );
extern UINT  dac_kbd_map_virtual_key( UINT code, UINT type );
extern SHORT dac_kbd_vkkeyscan( WCHAR ch );

/* 桌面单例（window.c） */
struct dac_desktop
{
    uint32_t    width, height;
    struct dac_ahb slots[DAC_DESKTOP_MAX_SLOTS];
    int         next_slot;
    uint32_t    slot_pending;              /* 位图：已 present 未 release */
    RECT        rect;
    BOOL        ready;
};
extern struct dac_desktop dac_desktop;

#endif /* __WINEDAC_INTERNAL_H */
