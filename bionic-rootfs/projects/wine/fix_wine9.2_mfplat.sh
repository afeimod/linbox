#!/bin/bash
# ============================================================================
# fix_wine9.2_mfplat.sh —— mfplat DX manager stub + bytestream handler 修复
# ----------------------------------------------------------------------------
# 来源：afeimod/linbox path/fix_wine9.2_mfplat.sh（用户 wine-wow64-no.yml
# 引用的 < 9.10 档 mf 补丁）移植 + 参数化扩展。
#
# 用法（在 wine 源码树根执行）：
#   ./fix_wine9.2_mfplat.sh [AVI|MPEG4]
#     AVI   → CLSID_AVIByteStreamHandler（< 9.10 档，fix_wine9.2_mfplat.sh
#             原行为 —— wine 9.x 无 CLSID_MPEG4ByteStreamHandlerPlugin）
#     MPEG4 → CLSID_MPEG4ByteStreamHandlerPlugin（>= 9.10 档，
#             wine_do_not_create_dxgi_manager2.patch 原行为，需
#             wine/mfinternal.h 头）
#
# 三处修改（全部内容锚点，无行号依赖，任何 9.x/10.x/11.x 树可跑）：
#   1) resolver_create_gstreamer_handler → resolver_create_default_handler
#      + CLSID 替换（GStreamer handler 在 Android/bionic 构建上常缺，
#      回退到内置 bytestream handler）；
#   2) MFCreateDXGIDeviceManager 开头加 WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER
#      环境变量检查（设非空 → E_NOTIMPL stub out —— 上游游戏/播放器在
#      mfplat 强建 DX manager 时崩溃的标准规避）；
#   3) #include "wine/mfinternal.h"（CLSID_MPEG4ByteStreamHandlerPlugin 声明）。
#
# 幂等：重复执行无副作用（已改过的树再跑一遍内容不变）。
# ============================================================================
set -euo pipefail

if [[ ! -f dlls/mfplat/main.c ]]; then
    echo "❌ 错误: dlls/mfplat/main.c 文件不存在（源码树形态不符）"
    exit 1
fi

MODE="${1:-AVI}"
case "${MODE}" in
    AVI)   CLSID="CLSID_AVIByteStreamHandler" ;;
    MPEG4) CLSID="CLSID_MPEG4ByteStreamHandlerPlugin" ;;
    *)     echo "❌ 未知档位: ${MODE}（可选 AVI / MPEG4）"; exit 1 ;;
esac

echo "开始修复 wine mfplat（模式=${MODE}，CLSID=${CLSID}）..."

# 1. 重命名函数（原脚本步骤 1）
echo "步骤 1: 重命名 bytestream handler 函数..."
sed -i 's/resolver_create_gstreamer_handler/resolver_create_default_handler/g' dlls/mfplat/main.c

# 2. 替换 CLSID 引用（原脚本步骤 2 + 幂等归一：patch 已改过的树重定向到同档）
echo "步骤 2: 替换 CLSID 引用 → ${CLSID}..."
sed -i "s/CLSID_GStreamerByteStreamHandler/${CLSID}/g" dlls/mfplat/main.c
sed -i "s/CLSID_MPEG4ByteStreamHandlerPlugin/${CLSID}/g" dlls/mfplat/main.c
sed -i "s/CLSID_AVIByteStreamHandler/${CLSID}/g" dlls/mfplat/main.c

# 3. MFCreateDXGIDeviceManager 开头添加环境变量检查（原脚本步骤 3）
echo "步骤 3: 添加 WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER 检查..."
python3 <<'PYEOF'
import re

p = 'dlls/mfplat/main.c'
with open(p, 'r') as f:
    content = f.read()

if 'WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER' in content:
    print('  （已存在环境变量检查，跳过）')
else:
    pattern = r'(HRESULT WINAPI MFCreateDXGIDeviceManager\(UINT \*token, IMFDXGIDeviceManager \*\*manager\)\s*\{)'
    replacement = (
        r'''\1
    const char *do_not_create = getenv("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER");

    if (do_not_create && do_not_create[0] != '\\0')
    {
        FIXME("stubbing out\\n");
        return E_NOTIMPL;
    }
'''
    )
    new_content, n = re.subn(pattern, replacement, content, count=1)
    if n == 0:
        print('  ⚠ 警告: MFCreateDXGIDeviceManager 锚点未命中（函数签名漂移？），env 检查未注入')
    else:
        with open(p, 'w') as f:
            f.write(new_content)
        print('  [OK] env 检查已注入')
PYEOF

# 4. 添加必要的头文件（原脚本步骤 4）
echo "步骤 4: 补充头文件 wine/mfinternal.h..."
if ! grep -q '#include "wine/mfinternal.h"' dlls/mfplat/main.c; then
    sed -i '/#include "evr.h"/a #include "wine/mfinternal.h"' dlls/mfplat/main.c
fi

echo "✅ mfplat 修复完成（${CLSID}；WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1 可 stub 掉 DX manager）"
