/*
 * winedac.drv — unixlib 接口（PE 侧 ↔ unix 侧）
 *
 * Copyright 2026 LinBox Project (MIT)
 * 结构仿 winex11.drv / wineandroid.drv（wine 9.2）
 */

#ifndef __WINEDAC_UNIXLIB_H
#define __WINEDAC_UNIXLIB_H

#include "wine/unixlib.h"

enum unix_funcs
{
    unix_init,
    unix_funcs_count
};

struct init_params
{
    void *reserved;
};

#define DAC_CALL( func, params ) WINE_UNIX_CALL( unix_##func, params )


#endif /* __WINEDAC_UNIXLIB_H */
