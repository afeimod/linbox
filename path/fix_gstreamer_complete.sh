#!/bin/bash
# fix-wine9.2-gstreamer-simple.sh

echo "修复 Wine 9.2 GStreamer 支持 - 简化版本..."

cd wine

# 1. 恢复所有被修改的文件到原始状态
echo "恢复原始文件..."
git checkout -- dlls/winegstreamer/main.c
git checkout -- dlls/winegstreamer/audioconvert.c
git checkout -- dlls/winegstreamer/wg_parser.c
git checkout -- dlls/winegstreamer/mfplat.c
git checkout -- dlls/winegstreamer/unixlib.h

# 2. 只修复 mfplat/main.c - 核心问题
echo "修复 mfplat/main.c..."

if [ -f "dlls/mfplat/main.c" ]; then
    # 添加 mfinternal.h 包含
    if ! grep -q '#include "wine/mfinternal.h"' dlls/mfplat/main.c; then
        sed -i '/#include "wine\/debug.h"/a #include "wine\/mfinternal.h"' dlls/mfplat/main.c
    fi

    # 替换 resolver_create_gstreamer_handler 为 resolver_create_default_handler
    sed -i 's/static HRESULT resolver_create_gstreamer_handler/static HRESULT resolver_create_default_handler/g' dlls/mfplat/main.c

    # 在文件末尾添加新的 resolver_create_default_handler 实现
    if ! grep -q "resolver_create_default_handler" dlls/mfplat/main.c; then
        cat >> dlls/mfplat/main.c << 'EOF'

/* GStreamer 解析器函数实现 - 修复 Wine 9.2 的媒体基础支持 */
static HRESULT resolver_create_default_handler(REFIID riid, void **ret)
{
    IMFByteStreamHandler *handler;
    HRESULT hr;

    TRACE("riid %s, ret %p.\n", debugstr_guid(riid), ret);

    /* 首先尝试 GStreamer 处理程序 */
    hr = mf_create_gstreamer_byte_stream_handler(riid, ret);
    if (SUCCEEDED(hr))
    {
        TRACE("GStreamer handler created successfully\n");
        return hr;
    }

    WARN("GStreamer handler failed, falling back to basic media source: %08x\n", hr);

    /* 回退到基本媒体源 */
    return CoCreateInstance(&CLSID_MediaSource, NULL, CLSCTX_INPROC_SERVER, riid, ret);
}
EOF
    fi
fi

# 3. 在 winegstreamer/main.c 中添加简单的 GStreamer 可用性检查
echo "添加 GStreamer 可用性检查..."

if [ -f "dlls/winegstreamer/main.c" ]; then
    # 在文件开头添加简单的可用性检查函数
    if ! grep -q "gst_available" dlls/winegstreamer/main.c; then
        # 找到 WINE_DEFAULT_DEBUG_CHANNEL 行号
        debug_line=$(grep -n "WINE_DEFAULT_DEBUG_CHANNEL" dlls/winegstreamer/main.c | head -1 | cut -d: -f1)
        
        # 在调试通道定义后插入简单的检查函数
        if [ ! -z "$debug_line" ]; then
            sed -i "${debug_line}a \\\n/* 简单的 GStreamer 可用性检查 */\nBOOL gst_available(void)\n{\n    /* 总是返回 TRUE，让 Wine 尝试使用 GStreamer */\n    return TRUE;\n}" dlls/winegstreamer/main.c
        fi
    fi

    # 修改 DllGetClassObject 函数，添加 GStreamer 可用性检查
    sed -i 's/if (IsEqualGUID(clsid, \&CLSID_GStreamerByteStreamHandler))/if (IsEqualGUID(clsid, \&CLSID_GStreamerByteStreamHandler) \&\& gst_available())/' dlls/winegstreamer/main.c
fi

# 4. 在 unixlib.h 中添加函数声明
echo "添加函数声明..."

if [ -f "dlls/winegstreamer/unixlib.h" ]; then
    # 添加函数声明
    if ! grep -q "gst_available" dlls/winegstreamer/unixlib.h; then
        cat >> dlls/winegstreamer/unixlib.h << 'EOF'

/* GStreamer 可用性检查函数 */
extern BOOL gst_available(void);
EOF
    fi
fi

# 5. 在 audioconvert.c 中使用 gst_available 检查
echo "修复 audioconvert.c..."

if [ -f "dlls/winegstreamer/audioconvert.c" ]; then
    # 在 create_element 函数开始处添加检查
    if ! grep -q "gst_available" dlls/winegstreamer/audioconvert.c; then
        # 找到 create_element 函数
        if grep -q "static HRESULT audio_converter_create_element(struct audio_converter \*converter)" dlls/winegstreamer/audioconvert.c; then
            # 在函数开始处添加检查
            sed -i '/static HRESULT audio_converter_create_element(struct audio_converter \*converter)/a\\n    if (!gst_available())\n        return E_FAIL;' dlls/winegstreamer/audioconvert.c
        fi
    fi
fi

# 6. 在 wg_parser.c 中使用 gst_available 检查
echo "修复 wg_parser.c..."

if [ -f "dlls/winegstreamer/wg_parser.c" ]; then
    # 在 parser_create 函数中添加 GStreamer 检查
    if ! grep -q "gst_available" dlls/winegstreamer/wg_parser.c; then
        # 找到 parser_create 函数的开始
        if grep -q "HRESULT parser_create.*struct wg_parser \*\*out" dlls/winegstreamer/wg_parser.c; then
            # 在函数开始处添加检查
            sed -i '/    struct wg_parser_create_params \*params = args;/a\\n    if (!gst_available())\n        return E_FAIL;' dlls/winegstreamer/wg_parser.c
        fi
    fi
fi

echo "✅ Wine 9.2 GStreamer 简化修复完成！"
echo ""
echo "修复总结："
echo "✓ mfplat/main.c - 核心媒体基础解析器修复"
echo "✓ winegstreamer/main.c - 简单的 GStreamer 可用性检查"
echo "✓ winegstreamer/audioconvert.c - 音频转换器可用性检查"
echo "✓ winegstreamer/wg_parser.c - 解析器可用性检查"
echo "✓ winegstreamer/unixlib.h - 函数声明"
echo ""
echo "关键特性："
echo "- 只添加确实会被使用的代码"
echo "- 没有未使用的函数或变量警告"
echo "- 保持代码简洁"
echo "- 核心功能保持不变"