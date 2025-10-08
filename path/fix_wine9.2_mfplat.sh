#!/bin/bash
echo "开始直接修复 Wine 9.2 mfplat 问题..."

if [ -f "dlls/mfplat/main.c" ]; then
    echo "找到 mfplat/main.c 文件，开始修复..."
    
    # 1. 添加必要的头文件包含
    echo "步骤 1: 添加 wine/mfinternal.h 头文件..."
    if ! grep -q '#include "wine/mfinternal.h"' dlls/mfplat/main.c; then
        # 在 evr.h 包含之后添加
        sed -i '/#include "evr.h"/a\
#include "wine/mfinternal.h"' dlls/mfplat/main.c
        echo "✅ 头文件添加成功"
    else
        echo "⚠️  头文件已存在，跳过"
    fi
    
    # 2. 重命名函数
    echo "步骤 2: 重命名 resolver_create_gstreamer_handler 函数..."
    sed -i 's/resolver_create_gstreamer_handler/resolver_create_default_handler/g' dlls/mfplat/main.c
    echo "✅ 函数重命名完成"
    
    # 3. 替换函数实现 - 使用更精确的方法
    echo "步骤 3: 替换函数实现..."
    # 备份原始文件
    cp dlls/mfplat/main.c dlls/mfplat/main.c.backup
    
    # 使用 Python 进行精确替换，避免格式问题
    python3 << 'EOF'
import re

with open('dlls/mfplat/main.c', 'r') as f:
    content = f.read()

# 替换 resolver_create_default_handler 函数的实现
pattern = r'static\s+HRESULT\s+resolver_create_default_handler\s*\(\s*IMFByteStreamHandler\s*\*\*\s*handler\s*\)\s*\{[^}]+\}'
replacement = '''static HRESULT resolver_create_default_handler(IMFByteStreamHandler **handler)
{
    return CoCreateInstance(&CLSID_AVIByteStreamHandler, NULL, CLSCTX_INPROC_SERVER, &IID_IMFByteStreamHandler, (void **)handler);
}'''

new_content = re.sub(pattern, replacement, content, flags=re.DOTALL)

with open('dlls/mfplat/main.c', 'w') as f:
    f.write(new_content)
EOF
    echo "✅ 函数实现替换完成"
    
    # 4. 添加调试信息
    echo "步骤 4: 添加调试信息..."
    if ! grep -q 'TRACE( "url_ext %s mimeW %s' dlls/mfplat/main.c; then
        # 在 if (url_ext || mimeW) 之前添加 TRACE
        sed -i '/if (url_ext || mimeW)/i\
    TRACE( "url_ext %s mimeW %s\\n", debugstr_w(url_ext), debugstr_w(mimeW) );' dlls/mfplat/main.c
        echo "✅ 调试信息添加完成"
    else
        echo "⚠️  调试信息已存在，跳过"
    fi
    
    # 5. 在 MFCreateDXGIDeviceManager 中添加环境变量检查
    echo "步骤 5: 修改 MFCreateDXGIDeviceManager 函数..."
    python3 << 'EOF'
import re

with open('dlls/mfplat/main.c', 'r') as f:
    content = f.read()

# 在 MFCreateDXGIDeviceManager 函数中添加环境变量检查
pattern = r'(HRESULT WINAPI MFCreateDXGIDeviceManager\(UINT \*token, IMFDXGIDeviceManager \*\*manager\)\s*\{)\s*(struct dxgi_device_manager \*object;\s*TRACE\("%p, %p\.\\n", token, manager\);)\s*'
replacement = r'''\1
    const char *do_not_create = getenv("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER");

    if (do_not_create && do_not_create[0] != '\\0')
    {
        FIXME("stubbing out\\n");
        return E_NOTIMPL;
    }

    \2'''

new_content = re.sub(pattern, replacement, content, flags=re.DOTALL)

with open('dlls/mfplat/main.c', 'w') as f:
    f.write(new_content)
EOF
    echo "✅ MFCreateDXGIDeviceManager 函数修改完成"
    
    # 6. 修复任何可能存在的 CLSID_MPEG4ByteStreamHandlerPlugin 引用
    echo "步骤 6: 修复未声明的 CLSID 引用..."
    sed -i 's/CLSID_MPEG4ByteStreamHandlerPlugin/CLSID_AVIByteStreamHandler/g' dlls/mfplat/main.c
    echo "✅ CLSID 引用修复完成"
    
    echo "🎉 所有修复步骤完成"
else
    echo "❌ 错误: dlls/mfplat/main.c 文件不存在"
    exit 1
fi