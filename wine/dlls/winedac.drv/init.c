/*
 * winedac.drv — 驱动初始化 / user_driver_funcs 注册表
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 结构仿 winex11.drv（wine 9.2）：
 *   PE 侧 dllmain.c → __wine_init_unix_call() → dac_call(unix_init)
 *   unix 侧本文件 dac_init → dac_platform_init → __wine_set_user_driver
 *
 * 注册策略：
 *   - 不注册 dc_funcs.pCreateDC/pCreateCompatibleDC（win32u 对两者判空，
 *     缺省时 GDI 走内建 dib 软件管线 —— 正是 DAC 的 CPU 位图设计）；
 *   - user_driver_funcs 全字段注册（win32u 多处直接调用，无判空），
 *     未实现的能力用安全桩：FALSE/~0U/NULL 语义回退 win32u 默认路径。
 */

#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <stdarg.h>
#include <stdlib.h>
#include <string.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS
#include "windef.h"
#include "winbase.h"
#include "winuser.h"
#include "winnls.h"
#include "winreg.h"
#include "ntuser.h"

#define VK_NO_PROTOTYPES
#define WINE_VK_HOST
#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"

#include "dacdrv.h"
#include "unixlib.h"
#include "wine/debug.h"

WINE_DEFAULT_DEBUG_CHANNEL(dac);

static const struct user_driver_funcs dac_drv_funcs;

/* ---------------- 显示设备注册（单 GPU / 单显示器 / 桌面尺寸模式列表） ---------------- */
static UINT desktop_width = 1280, desktop_height = 720;

static BOOL update_display_devices( const struct gdi_device_manager *manager, BOOL force, void *param )
{
    static const struct gdi_gpu gpu =
    {
        .id = 0,
        .name = { 'L','i','n','B','o','x',' ','D','A','C',0 },
    };
    static const struct gdi_adapter adapter =
    {
        .id = 0,
        .state_flags = DISPLAY_DEVICE_ATTACHED_TO_DESKTOP | DISPLAY_DEVICE_PRIMARY_DEVICE |
                       DISPLAY_DEVICE_VGA_COMPATIBLE,
    };
    struct gdi_monitor monitor = { 0 };
    DEVMODEW mode = { 0 };
    RECT rect = dac_desktop_rect();

    if (rect.right)
    {
        monitor.rc_monitor = rect;
        monitor.rc_work = rect;
        desktop_width  = rect.right - rect.left;
        desktop_height = rect.bottom - rect.top;
    }
    else
    {
        monitor.rc_monitor.right  = desktop_width;
        monitor.rc_monitor.bottom = desktop_height;
        monitor.rc_work = monitor.rc_monitor;
    }
    monitor.state_flags = DISPLAY_DEVICE_ACTIVE | DISPLAY_DEVICE_ATTACHED;

    mode.dmFields = DM_DISPLAYORIENTATION | DM_PELSWIDTH | DM_PELSHEIGHT | DM_BITSPERPEL |
                    DM_DISPLAYFLAGS | DM_DISPLAYFREQUENCY | DM_POSITION;
    mode.dmBitsPerPel = 32;
    mode.dmPelsWidth  = desktop_width;
    mode.dmPelsHeight = desktop_height;
    mode.dmDisplayFrequency = 60;

    manager->add_gpu( &gpu, param );
    manager->add_adapter( &adapter, param );
    manager->add_monitor( &monitor, param );
    manager->add_mode( &mode, TRUE, param );
    return TRUE;
}

/* ---------------- 显示模式 ---------------- */
static INT dac_GetDisplayDepth( LPCWSTR name, BOOL primary )
{
    return 32;
}

static LONG dac_ChangeDisplaySettings( LPDEVMODEW displays, LPCWSTR primary_name, HWND hwnd,
                                       DWORD flags, LPVOID lpdata )
{
    if (displays && (displays->dmFields & DM_PELSWIDTH) && (displays->dmFields & DM_PELSHEIGHT))
        dac_desktop_ensure( displays->dmPelsWidth, displays->dmPelsHeight );
    return DISP_CHANGE_SUCCESSFUL;
}

static BOOL dac_GetCurrentDisplaySettings( LPCWSTR name, BOOL primary, LPDEVMODEW dm )
{
    RECT rect = dac_desktop_rect();

    memset( dm, 0, FIELD_OFFSET( DEVMODEW, dmDriverExtra ) );
    dm->dmSize = FIELD_OFFSET( DEVMODEW, dmDriverExtra );
    dm->dmFields = DM_BITSPERPEL | DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY |
                   DM_DISPLAYFLAGS | DM_DISPLAYORIENTATION | DM_POSITION;
    dm->dmBitsPerPel = 32;
    dm->dmPelsWidth  = rect.right  ? rect.right - rect.left : 1280;
    dm->dmPelsHeight = rect.bottom ? rect.bottom - rect.top : 720;
    dm->dmDisplayFrequency = 60;
    return TRUE;
}

/* ---------------- 键盘辅助（映射逻辑在 keyboard.c；未命中回退 win32u 内建 US 表） ---------------- */
static UINT dac_MapVirtualKeyEx( UINT code, UINT type, HKL layout )
{
    return dac_kbd_map_virtual_key( code, type );
}

static SHORT dac_VkKeyScanEx( WCHAR ch, HKL layout )
{
    return dac_kbd_vkkeyscan( ch );
}

static INT dac_GetKeyNameText( LONG lparam, LPWSTR buffer, INT size )
{
    static const WCHAR keyW[] = { 'K','e','y',0 };
    INT len = min( size - 1, ARRAY_SIZE( keyW ) - 1 );

    if (size < 1) return 0;
    memcpy( buffer, keyW, len * sizeof(WCHAR) );
    buffer[len] = 0;
    return len;
}

static INT dac_ToUnicodeEx( UINT virt, UINT scan, const BYTE *state, LPWSTR buf,
                            int size, UINT flags, HKL layout )
{
    if (size < 1) return 0;
    buf[0] = 0;
    return 0;
}

static BOOL dac_ActivateKeyboardLayout( HKL layout, UINT flags )
{
    return TRUE;
}

static UINT dac_GetKeyboardLayoutList( INT size, HKL *layouts )
{
    if (size >= 1 && layouts) layouts[0] = 0;   /* 单一默认布局 */
    return 1;
}

static BOOL dac_RegisterHotKey( HWND hwnd, UINT key, UINT modifiers )
{
    return TRUE;
}

static void dac_UnregisterHotKey( HWND hwnd, UINT key, UINT modifiers )
{
}

static const KBDTABLES *dac_KbdLayerDescriptor( HKL layout )
{
    return NULL;    /* win32u 回退内建 kbdus_tables */
}

static void dac_ReleaseKbdTables( const KBDTABLES *tables )
{
}

/* ---------------- IME ---------------- */
static UINT dac_ImeProcessKey( HIMC himc, UINT vkey, UINT lparam, const BYTE *state )
{
    return 0;
}

static void dac_NotifyIMEStatus( HWND hwnd, UINT status )
{
}

/* ---------------- 桌面/光标杂项 ---------------- */
static void dac_DestroyCursorIcon( HCURSOR handle )
{
}

static void dac_Beep( void )
{
}

/* 光标形状：委托 window.c（管桥 CURSOR 事件）；光标位置裁剪：window.c 裁剪态 */
static void dac_SetCursor( HWND hwnd, HCURSOR cursor )
{
    dac_set_cursor_shape( cursor );
}

static BOOL dac_ClipCursor( const RECT *clip, BOOL fullscreen )
{
    dac_set_cursor_clip( clip, clip != NULL );
    return TRUE;
}

/* ---------------- 通知图标 / 剪贴板 / 系统托盘（v1 不承载，回退默认路径） ---------------- */
static LRESULT dac_NotifyIcon( HWND hwnd, UINT msg, NOTIFYICONDATAW *data )
{
    return 0;
}

static void dac_CleanupIcons( HWND hwnd )
{
}

static void dac_SystrayDockInit( HWND hwnd )
{
}

static BOOL dac_SystrayDockInsert( HWND hwnd, UINT cx, UINT cy, void *icon )
{
    return FALSE;
}

static void dac_SystrayDockClear( HWND hwnd )
{
}

static BOOL dac_SystrayDockRemove( HWND hwnd )
{
    return FALSE;
}

static LRESULT dac_ClipboardWindowProc( HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam )
{
    return 0;   /* 交回 win32u 默认处理 */
}

static void dac_UpdateClipboard( void )
{
}

/* ---------------- 窗口管理杂项（无窗口系统；回退 win32u 默认路径） ---------------- */
static void dac_SetDesktopWindow( HWND hwnd )
{
    RECT rect = dac_desktop_rect();
    if (rect.right) dac_desktop_ensure( rect.right - rect.left, rect.bottom - rect.top );
}

static UINT dac_ShowWindow( HWND hwnd, INT cmd, RECT *rect, UINT swp )
{
    return ~0U;    /* win32u 语义：~0 = 未处理 → 默认 SWP 流程 */
}

static LRESULT dac_DesktopWindowProc( HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam )
{
    return 0;
}

static LRESULT dac_WindowMessage( HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam )
{
    return 0;
}

static void dac_FlashWindowEx( FLASHWINFO *info )
{
}

static void dac_GetDC( HDC hdc, HWND hwnd, HWND parent, const RECT *win_rect,
                       const RECT *client_rect, DWORD style )
{
}

static void dac_ReleaseDC( HWND hwnd, HDC hdc )
{
}

static BOOL dac_ScrollDC( HDC hdc, INT dx, INT dy, HRGN update_rgn )
{
    return FALSE;   /* 未处理 → 调用方走失效重绘路径 */
}

static void dac_SetLayeredWindowAttributes( HWND hwnd, COLORREF key, BYTE alpha, DWORD flags )
{
}

static BOOL dac_UpdateLayeredWindow( HWND hwnd, const struct tagUPDATELAYEREDWINDOWINFO *info,
                                     const RECT *window_rect )
{
    return FALSE;
}

static void dac_SetParent( HWND hwnd, HWND parent, HWND old_parent )
{
}

static void dac_SetWindowRgn( HWND hwnd, HRGN region, BOOL redraw )
{
}

static void dac_SetWindowIcon( HWND hwnd, UINT type, HICON icon )
{
}

static void dac_SetWindowStyle( HWND hwnd, INT offset, STYLESTRUCT *style )
{
}

static LRESULT dac_SysCommand( HWND hwnd, WPARAM wparam, LPARAM lparam )
{
    return 0;   /* 未处理（v1 无驱动侧移动/缩放循环） */
}

static BOOL dac_SystemParametersInfo( UINT action, UINT val, void *ptr, UINT winini )
{
    return FALSE;   /* win32u 语义：FALSE → 回退注册表默认实现 */
}

static void dac_ThreadDetach( void )
{
}

/* ---------------- Vulkan / WGL ---------------- */
static const struct vulkan_funcs *dac_wine_get_vulkan_driver( UINT version )
{
    return dac_vulkan_driver_init( version );
}

static struct opengl_funcs *dac_wine_get_wgl_driver( UINT version )
{
    return NULL;   /* v1 无 OpenGL（DXVK Vulkan 覆盖 D3D9/10/11） */
}

/* ---------------- unixlib 入口 ---------------- */
static NTSTATUS dac_init( void *arg )
{
    TRACE( "winedac.drv initializing (Direct Android Compositing)\n" );

    if (dac_platform_init())
        WARN( "AHB platform init failed — 桌面 CPU 路径将不可用\n" );

    /* Vulkan 驱动加载失败不阻塞（仍可显示纯 GDI 应用） */
    if (!dac_vulkan_driver_init( WINE_VULKAN_DRIVER_VERSION ))
        WARN( "Vulkan host driver unavailable — DXVK 不可用\n" );

    __wine_set_user_driver( &dac_drv_funcs, WINE_GDI_DRIVER_VERSION );
    return STATUS_SUCCESS;
}

const unixlib_entry_t __wine_unix_call_funcs[] =
{
    dac_init,
};

C_ASSERT( ARRAY_SIZE( __wine_unix_call_funcs ) == unix_funcs_count );

/* ---------------- user_driver_funcs 注册表（全字段，签名对齐 wine-9.2 gdi_driver.h） ---------------- */
static const struct user_driver_funcs dac_drv_funcs =
{
    .dc_funcs.priority = GDI_PRIORITY_GRAPHICS_DRV,

    /* keyboard */
    .pActivateKeyboardLayout = dac_ActivateKeyboardLayout,
    .pBeep = dac_Beep,
    .pGetKeyNameText = dac_GetKeyNameText,
    .pGetKeyboardLayoutList = dac_GetKeyboardLayoutList,
    .pMapVirtualKeyEx = dac_MapVirtualKeyEx,
    .pRegisterHotKey = dac_RegisterHotKey,
    .pToUnicodeEx = dac_ToUnicodeEx,
    .pUnregisterHotKey = dac_UnregisterHotKey,
    .pVkKeyScanEx = dac_VkKeyScanEx,
    .pKbdLayerDescriptor = dac_KbdLayerDescriptor,
    .pReleaseKbdTables = dac_ReleaseKbdTables,
    /* IME */
    .pImeProcessKey = dac_ImeProcessKey,
    .pNotifyIMEStatus = dac_NotifyIMEStatus,
    /* cursor/icon */
    .pDestroyCursorIcon = dac_DestroyCursorIcon,
    .pSetCursor = dac_SetCursor,
    .pGetCursorPos = dac_GetCursorPos,
    .pSetCursorPos = dac_SetCursorPos,
    .pClipCursor = dac_ClipCursor,
    /* notify icon */
    .pNotifyIcon = dac_NotifyIcon,
    .pCleanupIcons = dac_CleanupIcons,
    .pSystrayDockInit = dac_SystrayDockInit,
    .pSystrayDockInsert = dac_SystrayDockInsert,
    .pSystrayDockClear = dac_SystrayDockClear,
    .pSystrayDockRemove = dac_SystrayDockRemove,
    /* clipboard */
    .pClipboardWindowProc = dac_ClipboardWindowProc,
    .pUpdateClipboard = dac_UpdateClipboard,
    /* display modes */
    .pChangeDisplaySettings = dac_ChangeDisplaySettings,
    .pGetCurrentDisplaySettings = dac_GetCurrentDisplaySettings,
    .pGetDisplayDepth = dac_GetDisplayDepth,
    .pUpdateDisplayDevices = update_display_devices,
    /* windowing */
    .pCreateDesktop = dac_CreateDesktop,
    .pCreateWindow = dac_CreateWindow,
    .pDesktopWindowProc = dac_DesktopWindowProc,
    .pDestroyWindow = dac_DestroyWindow,
    .pFlashWindowEx = dac_FlashWindowEx,
    .pGetDC = dac_GetDC,
    .pProcessEvents = dac_ProcessEvents,
    .pReleaseDC = dac_ReleaseDC,
    .pScrollDC = dac_ScrollDC,
    .pSetCapture = dac_SetCapture,
    .pSetDesktopWindow = dac_SetDesktopWindow,
    .pSetFocus = dac_SetFocus,
    .pSetLayeredWindowAttributes = dac_SetLayeredWindowAttributes,
    .pSetParent = dac_SetParent,
    .pSetWindowRgn = dac_SetWindowRgn,
    .pSetWindowIcon = dac_SetWindowIcon,
    .pSetWindowStyle = dac_SetWindowStyle,
    .pSetWindowText = dac_SetWindowText,
    .pShowWindow = dac_ShowWindow,
    .pSysCommand = dac_SysCommand,
    .pUpdateLayeredWindow = dac_UpdateLayeredWindow,
    .pWindowMessage = dac_WindowMessage,
    .pWindowPosChanging = dac_WindowPosChanging,
    .pWindowPosChanged = dac_WindowPosChanged,
    /* system parameters */
    .pSystemParametersInfo = dac_SystemParametersInfo,
    /* vulkan support */
    .pwine_get_vulkan_driver = dac_wine_get_vulkan_driver,
    /* opengl support */
    .pwine_get_wgl_driver = dac_wine_get_wgl_driver,
    /* thread management */
    .pThreadDetach = dac_ThreadDetach,
};
