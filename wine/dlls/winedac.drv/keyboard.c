/*
 * winedac.drv — 键盘映射与按键注入
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * bridge 上行 Android AKEYCODE_* → Windows vkey/scancode。
 * 映射表覆盖常用键位（字母数字、方向、修饰、功能键、小键盘），
 * 未覆盖键经 unicode 兜底。表结构参考 wineandroid.drv/keyboard.c。
 */

#if 0
#pragma makedep unix  /* 本文件仅编译 unix 侧（wine 9.2 驱动模型） */
#endif

#include "config.h"

#include <stdlib.h>
#include <string.h>

#include "windef.h"
#include "winbase.h"
#include "winuser.h"
#include "winnls.h"
#include "ntuser.h"

#include "dacdrv.h"
#include "wine/debug.h"

WINE_DEFAULT_DEBUG_CHANNEL(dac);

/* Android keycode（与 android/keycodes.h 同值，自行声明避免头依赖） */
enum
{
    AK_UNKNOWN = 0, AK_SOFT_LEFT = 1, AK_SOFT_RIGHT = 2, AK_HOME = 3,
    AK_BACK = 4, AK_CALL = 5, AK_ENDCALL = 6, AK_0 = 7, AK_1 = 8, AK_2 = 9,
    AK_3 = 10, AK_4 = 11, AK_5 = 12, AK_6 = 13, AK_7 = 14, AK_8 = 15,
    AK_9 = 16, AK_STAR = 17, AK_POUND = 18, AK_DPAD_UP = 19, AK_DPAD_DOWN = 20,
    AK_DPAD_LEFT = 21, AK_DPAD_RIGHT = 22, AK_DPAD_CENTER = 23, AK_VOLUME_UP = 24,
    AK_VOLUME_DOWN = 25, AK_POWER = 26, AK_CAMERA = 27, AK_FOCUS = 28,
    AK_MENU = 82, AK_A = 29, AK_B = 30, AK_C = 31, AK_D = 32, AK_E = 33,
    AK_F = 34, AK_G = 35, AK_H = 36, AK_I = 37, AK_J = 38, AK_K = 39,
    AK_L = 40, AK_M = 41, AK_N = 42, AK_O = 43, AK_P = 44, AK_Q = 45,
    AK_R = 46, AK_S = 47, AK_T = 48, AK_U = 49, AK_V = 50, AK_W = 51,
    AK_X = 52, AK_Y = 53, AK_Z = 54, AK_COMMA = 55, AK_PERIOD = 56,
    AK_ALT_LEFT = 57, AK_ALT_RIGHT = 58, AK_SHIFT_LEFT = 59, AK_SHIFT_RIGHT = 60,
    AK_TAB = 61, AK_SPACE = 62, AK_SYM = 63, AK_EXPLORER = 64, AK_ENVELOPE = 65,
    AK_ENTER = 66, AK_DEL = 67, AK_GRAVE = 68, AK_MINUS = 69, AK_EQUALS = 70,
    AK_LEFT_BRACKET = 71, AK_RIGHT_BRACKET = 72, AK_BACKSLASH = 73,
    AK_SEMICOLON = 74, AK_APOSTROPHE = 75, AK_SLASH = 76, AK_AT = 77,
    AK_NUM = 78, AK_HEADSETHOOK = 79, AK_PLUS = 81,
    AK_PAGE_UP = 92, AK_PAGE_DOWN = 93, AK_ESCAPE = 111, AK_FORWARD_DEL = 112,
    AK_CTRL_LEFT = 113, AK_CTRL_RIGHT = 114, AK_CAPS_LOCK = 115,
    AK_SCROLL_LOCK = 116, AK_META_LEFT = 117, AK_META_RIGHT = 118,
    AK_FUNCTION = 119, AK_SYSRQ = 120, AK_BREAK = 121,
    AK_MOVE_HOME = 122, AK_MOVE_END = 123, AK_INSERT = 124,
    AK_F1 = 131, AK_F2 = 132, AK_F3 = 133, AK_F4 = 134, AK_F5 = 135,
    AK_F6 = 136, AK_F7 = 137, AK_F8 = 138, AK_F9 = 139, AK_F10 = 140,
    AK_F11 = 141, AK_F12 = 142,
    AK_NUMPAD_0 = 144, AK_NUMPAD_1 = 145, AK_NUMPAD_2 = 146, AK_NUMPAD_3 = 147,
    AK_NUMPAD_4 = 148, AK_NUMPAD_5 = 149, AK_NUMPAD_6 = 150, AK_NUMPAD_7 = 151,
    AK_NUMPAD_8 = 152, AK_NUMPAD_9 = 153, AK_NUMPAD_DIVIDE = 154,
    AK_NUMPAD_MULTIPLY = 155, AK_NUMPAD_SUBTRACT = 156, AK_NUMPAD_ADD = 157,
    AK_NUMPAD_DOT = 158, AK_NUMPAD_ENTER = 160,
};

/* Android meta state 位 */
#define AMETA_ALT_ON        0x02
#define AMETA_SHIFT_ON      0x01
#define AMETA_CTRL_ON       0x1000
#define AMETA_META_ON       0x20000

struct key_entry
{
    uint8_t  akeycode;
    uint8_t  vkey;         /* VK_* */
    uint8_t  scancode;     /* 硬件扫描码 set1（&0xff） */
    uint8_t  ext;          /* 1 = KEYEVENTF_EXTENDEDKEY */
};

static const struct key_entry keymap[] =
{
    { AK_BACK,        VK_ESCAPE,      0x01, 0 },
    { AK_1,           '1',            0x02, 0 }, { AK_2, '2', 0x03, 0 },
    { AK_3,           '3',            0x04, 0 }, { AK_4, '4', 0x05, 0 },
    { AK_5,           '5',            0x06, 0 }, { AK_6, '6', 0x07, 0 },
    { AK_7,           '7',            0x08, 0 }, { AK_8, '8', 0x09, 0 },
    { AK_9,           '9',            0x0a, 0 }, { AK_0, '0', 0x0b, 0 },
    { AK_MINUS,       VK_OEM_MINUS,   0x0c, 0 },
    { AK_EQUALS,      VK_OEM_PLUS,    0x0d, 0 },
    { AK_DEL,         VK_BACK,        0x0e, 0 },
    { AK_TAB,         VK_TAB,         0x0f, 0 },
    { AK_Q,           'Q',            0x10, 0 }, { AK_W, 'W', 0x11, 0 },
    { AK_E,           'E',            0x12, 0 }, { AK_R, 'R', 0x13, 0 },
    { AK_T,           'T',            0x14, 0 }, { AK_Y, 'Y', 0x15, 0 },
    { AK_U,           'U',            0x16, 0 }, { AK_I, 'I', 0x17, 0 },
    { AK_O,           'O',            0x18, 0 }, { AK_P, 'P', 0x19, 0 },
    { AK_LEFT_BRACKET,  VK_OEM_4,     0x1a, 0 },
    { AK_RIGHT_BRACKET, VK_OEM_6,     0x1b, 0 },
    { AK_ENTER,       VK_RETURN,      0x1c, 0 },
    { AK_CTRL_LEFT,   VK_LCONTROL,    0x1d, 0 },
    { AK_A,           'A',            0x1e, 0 }, { AK_S, 'S', 0x1f, 0 },
    { AK_D,           'D',            0x20, 0 }, { AK_F, 'F', 0x21, 0 },
    { AK_G,           'G',            0x22, 0 }, { AK_H, 'H', 0x23, 0 },
    { AK_J,           'J',            0x24, 0 }, { AK_K, 'K', 0x25, 0 },
    { AK_L,           'L',            0x26, 0 },
    { AK_SEMICOLON,   VK_OEM_1,       0x27, 0 },
    { AK_APOSTROPHE,  VK_OEM_7,       0x28, 0 },
    { AK_GRAVE,       VK_OEM_3,       0x29, 0 },
    { AK_SHIFT_LEFT,  VK_LSHIFT,      0x2a, 0 },
    { AK_BACKSLASH,   VK_OEM_5,       0x2b, 0 },
    { AK_Z,           'Z',            0x2c, 0 }, { AK_X, 'X', 0x2d, 0 },
    { AK_C,           'C',            0x2e, 0 }, { AK_V, 'V', 0x2f, 0 },
    { AK_B,           'B',            0x30, 0 }, { AK_N, 'N', 0x31, 0 },
    { AK_M,           'M',            0x32, 0 },
    { AK_COMMA,       VK_OEM_COMMA,   0x33, 0 },
    { AK_PERIOD,      VK_OEM_PERIOD,  0x34, 0 },
    { AK_SLASH,       VK_OEM_2,       0x35, 0 },
    { AK_SHIFT_RIGHT, VK_RSHIFT,      0x36, 0 },
    { AK_NUMPAD_MULTIPLY, VK_MULTIPLY, 0x37, 0 },
    { AK_ALT_LEFT,    VK_LMENU,       0x38, 0 },
    { AK_SPACE,       VK_SPACE,       0x39, 0 },
    { AK_CAPS_LOCK,   VK_CAPITAL,     0x3a, 0 },
    { AK_F1, VK_F1, 0x3b, 0 }, { AK_F2, VK_F2, 0x3c, 0 },
    { AK_F3, VK_F3, 0x3d, 0 }, { AK_F4, VK_F4, 0x3e, 0 },
    { AK_F5, VK_F5, 0x3f, 0 }, { AK_F6, VK_F6, 0x40, 0 },
    { AK_F7, VK_F7, 0x41, 0 }, { AK_F8, VK_F8, 0x42, 0 },
    { AK_F9, VK_F9, 0x43, 0 }, { AK_F10, VK_F10, 0x44, 0 },
    { AK_NUMPAD_DIVIDE,   VK_DIVIDE,   0x35, 1 },
    { AK_NUMPAD_0, VK_NUMPAD0, 0x52, 0 }, { AK_NUMPAD_1, VK_NUMPAD1, 0x4f, 0 },
    { AK_NUMPAD_2, VK_NUMPAD2, 0x50, 0 }, { AK_NUMPAD_3, VK_NUMPAD3, 0x51, 0 },
    { AK_NUMPAD_4, VK_NUMPAD4, 0x4b, 0 }, { AK_NUMPAD_5, VK_NUMPAD5, 0x4c, 0 },
    { AK_NUMPAD_6, VK_NUMPAD6, 0x4d, 0 }, { AK_NUMPAD_7, VK_NUMPAD7, 0x47, 0 },
    { AK_NUMPAD_8, VK_NUMPAD8, 0x48, 0 }, { AK_NUMPAD_9, VK_NUMPAD9, 0x49, 0 },
    { AK_NUMPAD_SUBTRACT, VK_SUBTRACT, 0x4a, 0 },
    { AK_NUMPAD_ADD,      VK_ADD,      0x4e, 0 },
    { AK_NUMPAD_DOT,      VK_DECIMAL,  0x53, 0 },
    { AK_NUMPAD_ENTER,    VK_RETURN,   0x1c, 1 },
    { AK_DPAD_UP,    VK_UP,    0x48, 1 },
    { AK_DPAD_DOWN,  VK_DOWN,  0x50, 1 },
    { AK_DPAD_LEFT,  VK_LEFT,  0x4b, 1 },
    { AK_DPAD_RIGHT, VK_RIGHT, 0x4d, 1 },
    { AK_DPAD_CENTER, VK_RETURN, 0x1c, 0 },
    { AK_ESCAPE,      VK_ESCAPE,  0x01, 0 },
    { AK_FORWARD_DEL, VK_DELETE,  0x53, 1 },
    { AK_CTRL_RIGHT,  VK_RCONTROL, 0x1d, 1 },
    { AK_PAGE_UP,     VK_PRIOR,   0x49, 1 },
    { AK_PAGE_DOWN,   VK_NEXT,    0x51, 1 },
    { AK_MOVE_HOME,   VK_HOME,    0x47, 1 },
    { AK_MOVE_END,    VK_END,     0x4f, 1 },
    { AK_INSERT,      VK_INSERT,  0x52, 1 },
    { AK_F11, VK_F11, 0x57, 0 }, { AK_F12, VK_F12, 0x58, 0 },
    { AK_MENU,        VK_LWIN,    0x5b, 1 },
    { AK_HOME,        VK_LWIN,    0x5b, 1 },
    { AK_VOLUME_UP,   VK_VOLUME_UP,   0x30, 1 },
    { AK_VOLUME_DOWN, VK_VOLUME_DOWN, 0x2e, 1 },
};

static const struct key_entry *lookup_key( uint32_t keycode )
{
    unsigned int i;
    for (i = 0; i < ARRAY_SIZE( keymap ); i++)
        if (keymap[i].akeycode == keycode) return &keymap[i];
    return NULL;
}

/* 修饰键状态跟踪（合成 shift/control 的字母大小写） */
static uint32_t active_mods;

/* init.c 驱动表入口 */
UINT dac_kbd_map_virtual_key( UINT code, UINT type )
{
    unsigned int i;

    switch (type)
    {
    case MAPVK_VK_TO_VSC:
        for (i = 0; i < ARRAY_SIZE( keymap ); i++)
            if (keymap[i].vkey == (code & 0xff) && !keymap[i].ext)
                return keymap[i].scancode;
        return 0;
    case MAPVK_VSC_TO_VK:
        for (i = 0; i < ARRAY_SIZE( keymap ); i++)
            if (keymap[i].scancode == (code & 0xff)) return keymap[i].vkey;
        return 0;
    default:
        return 0;
    }
}

SHORT dac_kbd_vkkeyscan( WCHAR ch )
{
    if (ch >= 'a' && ch <= 'z') return (SHORT)( ch - 'a' + 'A' );
    if (ch >= 'A' && ch <= 'Z') return (SHORT)( 0x0100 | (ch - 'A' + 'A') ); /* shift */
    if (ch >= '0' && ch <= '9') return ch;
    switch (ch)
    {
    case ' ': return VK_SPACE;
    case '.': return VK_OEM_PERIOD;
    case ',': return VK_OEM_COMMA;
    case ';': return VK_OEM_1;
    case ':': return (SHORT)( 0x0100 | VK_OEM_1 );
    case '/': return VK_OEM_2;
    case '\\': return VK_OEM_5;
    case '-': return VK_OEM_MINUS;
    case '=': return VK_OEM_PLUS;
    case '[': return VK_OEM_4;
    case ']': return VK_OEM_6;
    case '\'': return VK_OEM_7;
    case '`': return VK_OEM_3;
    case '\n': case '\r': return VK_RETURN;
    case '\t': return VK_TAB;
    default: return -1;
    }
}

void dac_kbd_send_key( const struct dac_input_key *key )
{
    INPUT input;
    const struct key_entry *entry;
    BOOL down = key->down;
    UINT vkey, scan;

    switch (key->keycode)
    {
    case AK_SHIFT_LEFT: case AK_SHIFT_RIGHT:
        if (down) active_mods |= AMETA_SHIFT_ON; else active_mods &= ~AMETA_SHIFT_ON;
        break;
    case AK_CTRL_LEFT: case AK_CTRL_RIGHT:
        if (down) active_mods |= AMETA_CTRL_ON; else active_mods &= ~AMETA_CTRL_ON;
        break;
    case AK_ALT_LEFT: case AK_ALT_RIGHT:
        if (down) active_mods |= AMETA_ALT_ON; else active_mods &= ~AMETA_ALT_ON;
        break;
    }

    entry = lookup_key( key->keycode );
    if (entry)
    {
        vkey = entry->vkey;
        scan = entry->scancode;

        /* shift 按住时字母给出大写 vkey；数字行给出符号（wine 内部
           依赖 ToUnicode，vkey 层面 shift 已由修饰状态表达） */
        if ((active_mods & AMETA_SHIFT_ON) && vkey >= 'a' && vkey <= 'z')
            vkey = vkey - 'a' + 'A';

        memset( &input, 0, sizeof(input) );
        input.type = INPUT_KEYBOARD;
        input.ki.wVk  = 0;
        input.ki.wScan = entry->scancode;
        input.ki.dwFlags = KEYEVENTF_SCANCODE |
                           (entry->ext ? KEYEVENTF_EXTENDEDKEY : 0) |
                           (down ? 0 : KEYEVENTF_KEYUP);
        __wine_send_input( 0, &input, NULL );
        return;
    }

    /* 未映射键：unicode 兜底 */
    if (key->unicode && down)
    {
        memset( &input, 0, sizeof(input) );
        input.type = INPUT_KEYBOARD;
        input.ki.wScan = key->unicode;
        input.ki.dwFlags = KEYEVENTF_UNICODE;
        __wine_send_input( 0, &input, NULL );
        memset( &input, 0, sizeof(input) );
        input.type = INPUT_KEYBOARD;
        input.ki.wScan = key->unicode;
        input.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;
        __wine_send_input( 0, &input, NULL );
    }
}
