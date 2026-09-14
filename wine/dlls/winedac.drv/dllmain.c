/*
 * winedac.drv entry points（PE 侧）
 *
 * Copyright 2026 LinBox Project (MIT)
 */

#include "winedac_dll.h"
#include "wine/debug.h"

WINE_DEFAULT_DEBUG_CHANNEL(dac);

HMODULE winedac_module = 0;

BOOL WINAPI DllMain( HINSTANCE instance, DWORD reason, void *reserved )
{
    if (reason != DLL_PROCESS_ATTACH) return TRUE;

    DisableThreadLibraryCalls( instance );
    winedac_module = instance;
    if (__wine_init_unix_call()) return FALSE;
    if (DAC_CALL( init, NULL )) return FALSE;
    return TRUE;
}
