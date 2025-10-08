#!/bin/bash
echo "开始简化修复 Wine 9.2 mfplat 问题..."

if [ -f "dlls/mfplat/main.c" ]; then
    echo "找到 mfplat/main.c 文件，开始修复..."
    
    # 备份原始文件
    cp dlls/mfplat/main.c dlls/mfplat/main.c.backup
    
    # 1. 重命名函数
    echo "步骤 1: 重命名函数..."
    sed -i 's/resolver_create_gstreamer_handler/resolver_create_default_handler/g' dlls/mfplat/main.c
    
    # 2. 替换 CLSID 引用
    echo "步骤 2: 替换 CLSID 引用..."
    sed -i 's/CLSID_GStreamerByteStreamHandler/CLSID_AVIByteStreamHandler/g' dlls/mfplat/main.c
    sed -i 's/CLSID_MPEG4ByteStreamHandlerPlugin/CLSID_AVIByteStreamHandler/g' dlls/mfplat/main.c
    
    # 3. 在 MFCreateDXGIDeviceManager 函数开头添加环境变量检查
    echo "步骤 3: 添加环境变量检查..."
    python3 << 'EOF'
import re

with open('dlls/mfplat/main.c', 'r') as f:
    content = f.read()

# 在 MFCreateDXGIDeviceManager 函数开头添加环境变量检查
pattern = r'(HRESULT WINAPI MFCreateDXGIDeviceManager\(UINT \*token, IMFDXGIDeviceManager \*\*manager\)\s*\{)'
replacement = r'''\1
    const char *do_not_create = getenv("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER");
    if (do_not_create && do_not_create[0] != '\\0')
    {
        FIXME("stubbing out\\\\n");
        return E_NOTIMPL;
    }
'''

content = re.sub(pattern, replacement, content)

with open('dlls/mfplat/main.c', 'w') as f:
    f.write(content)
EOF
    
    # 4. 添加必要的头文件
    echo "步骤 4: 添加头文件..."
    if ! grep -q '#include "wine/mfinternal.h"' dlls/mfplat/main.c; then
        sed -i '/#include "evr.h"/a\
#include "wine/mfinternal.h"' dlls/mfplat/main.c
    fi
    
    echo "✅ 简化修复完成"
else
    echo "❌ 错误: dlls/mfplat/main.c 文件不存在"
    exit 1
fi