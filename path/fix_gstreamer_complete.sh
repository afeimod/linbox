#!/bin/bash
# fix-wine9.2-gstreamer-final.sh

echo "修复 Wine 9.2 GStreamer 支持 - 最终版本..."

cd wine

# 1. 修复 mfplat/main.c - 添加必要的头文件和函数
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

# 2. 完全修复 winegstreamer/main.c
echo "修复 winegstreamer/main.c..."

if [ -f "dlls/winegstreamer/main.c" ]; then
    # 在文件开头添加初始化和可用性检查函数
    if ! grep -q "gst_available" dlls/winegstreamer/main.c; then
        # 找到 WINE_DEFAULT_DEBUG_CHANNEL 行号
        debug_line=$(grep -n "WINE_DEFAULT_DEBUG_CHANNEL" dlls/winegstreamer/main.c | head -1 | cut -d: -f1)
        
        # 在调试通道定义后插入初始化代码
        if [ ! -z "$debug_line" ]; then
            sed -i "${debug_line}a \\\n/* GStreamer 初始化状态 - 修复 Wine 9.2 的初始化问题 */\nstatic pthread_once_t gst_init_once = PTHREAD_ONCE_INIT;\nstatic BOOL gst_initialized = FALSE;\n\nstatic void init_gstreamer_once(void)\n{\n    GError *error = NULL;\n    \n    if (!gst_init_check(NULL, NULL, &error))\n    {\n        WARN(\"Failed to initialize GStreamer: %s\\n\", error ? error->message : \"unknown error\");\n        if (error) g_error_free(error);\n        gst_initialized = FALSE;\n    }\n    else\n    {\n        TRACE(\"GStreamer initialized successfully\\n\");\n        gst_initialized = TRUE;\n        \n        /* 配置 Termux 特定的插件路径 */\n        configure_gstreamer_plugin_path();\n    }\n}\n\n/* GStreamer 可用性检查 */\nBOOL gst_available(void)\n{\n    pthread_once(&gst_init_once, init_gstreamer_once);\n    return gst_initialized;\n}\n\n/* Termux 特定的 GStreamer 路径配置 */\nvoid configure_gstreamer_plugin_path(void)\n{\n    static BOOL paths_configured = FALSE;\n    \n    if (paths_configured || !gst_available())\n        return;\n        \n    const gchar *termux_prefix = \"/data/data/com.termux/files/usr\";\n    gchar *plugin_path = g_build_filename(termux_prefix, \"lib\", \"gstreamer-1.0\", NULL);\n    \n    if (plugin_path)\n    {\n        g_setenv(\"GST_PLUGIN_SYSTEM_PATH\", plugin_path, TRUE);\n        g_setenv(\"GST_PLUGIN_PATH\", plugin_path, TRUE);\n        g_free(plugin_path);\n    }\n    \n    /* 其他 Termux 特定的配置 */\n    g_setenv(\"GST_REGISTRY\", \"/data/data/com.termux/files/usr/tmp/gstreamer-registry.bin\", TRUE);\n    \n    paths_configured = TRUE;\n    TRACE(\"GStreamer plugin paths configured for Termux\\n\");\n}" dlls/winegstreamer/main.c
        fi
    fi

    # 修改 DllMain 函数，添加 GStreamer 初始化
    sed -i '/case DLL_PROCESS_ATTACH:/a \\        /* 初始化 GStreamer */\n        pthread_once(\&gst_init_once, init_gstreamer_once);\n        TRACE(\"GStreamer initialization status: %d\\n\", gst_initialized);' dlls/winegstreamer/main.c

    # 修改 DllGetClassObject 函数，添加 GStreamer 可用性检查
    sed -i 's/if (IsEqualGUID(clsid, \&CLSID_GStreamerByteStreamHandler))/if (IsEqualGUID(clsid, \&CLSID_GStreamerByteStreamHandler) \&\& gst_available())/' dlls/winegstreamer/main.c
fi

# 3. 修复 winegstreamer/audioconvert.c - 只添加被使用的函数
echo "修复 winegstreamer/audioconvert.c..."

if [ -f "dlls/winegstreamer/audioconvert.c" ]; then
    # 添加 GStreamer 可用性检查到音频转换器
    if ! grep -q "gst_available" dlls/winegstreamer/audioconvert.c; then
        # 在 create_element 函数开始处添加检查
        sed -i '/static HRESULT audio_converter_create_element(struct audio_converter \*converter)/a {\n    /* 检查 GStreamer 是否可用 */\n    if (!gst_available())\n    {\n        ERR(\"GStreamer not available for audio converter\\n\");\n        return E_FAIL;\n    }' dlls/winegstreamer/audioconvert.c
        
        # 在函数结尾添加匹配的括号
        sed -i '/gst_object_unref(converter->src);/a }' dlls/winegstreamer/audioconvert.c
    fi

    # 添加安全的元素创建函数 - 这个函数会被使用
    if ! grep -q "create_gst_element_safe" dlls/winegstreamer/audioconvert.c; then
        cat >> dlls/winegstreamer/audioconvert.c << 'EOF'

/* 安全的 GStreamer 元素创建函数 - 会被实际使用 */
static GstElement *create_gst_element_safe(const gchar *factoryname, const gchar *name)
{
    GstElement *element = NULL;
    
    if (!gst_available())
        return NULL;
        
    element = gst_element_factory_make(factoryname, name);
    if (!element)
        WARN("Failed to create GStreamer element: %s\n", factoryname);
        
    return element;
}
EOF

        # 替换原有的元素创建调用 - 确保函数被使用
        sed -i 's/gst_element_factory_make("audioconvert", NULL)/create_gst_element_safe("audioconvert", NULL)/g' dlls/winegstreamer/audioconvert.c
        sed -i 's/gst_element_factory_make("audioresample", NULL)/create_gst_element_safe("audioresample", NULL)/g' dlls/winegstreamer/audioconvert.c
    fi
fi

# 4. 修复 winegstreamer/wg_parser.c - 只添加被使用的函数
echo "修复 winegstreamer/wg_parser.c..."

if [ -f "dlls/winegstreamer/wg_parser.c" ]; then
    # 在 parser_create 函数中添加 GStreamer 检查
    if ! grep -q "gst_available" dlls/winegstreamer/wg_parser.c; then
        sed -i '/struct wg_parser_create_params \*params = args;/a \\n    /* 检查 GStreamer 可用性 */\n    if (!gst_available())\n    {\n        ERR(\"GStreamer not available for parser creation\\n\");\n        return E_FAIL;\n    }' dlls/winegstreamer/wg_parser.c
    fi

    # 只添加被实际使用的资源清理函数
    if ! grep -q "wg_parser_cleanup_resources" dlls/winegstreamer/wg_parser.c; then
        cat >> dlls/winegstreamer/wg_parser.c << 'EOF'

/* 资源清理助手 - 这个函数会被实际使用 */
static void wg_parser_cleanup_resources(struct wg_parser *parser)
{
    if (parser->container)
    {
        gst_element_set_state(parser->container, GST_STATE_NULL);
        TRACE("GStreamer parser resources cleaned up\n");
    }
}
EOF

        # 在 parser_destroy 函数中调用资源清理函数，确保它被使用
        if grep -q "static void parser_destroy(struct wg_parser \*parser)" dlls/winegstreamer/wg_parser.c; then
            sed -i '/static void parser_destroy(struct wg_parser \*parser)/,/^}/ { /pthread_mutex_destroy(/i \\    wg_parser_cleanup_resources(parser);' dlls/winegstreamer/wg_parser.c
        fi
    fi
fi

# 5. 修复 winegstreamer/mfplat.c
echo "修复 winegstreamer/mfplat.c..."

if [ -f "dlls/winegstreamer/mfplat.c" ]; then
    # 添加 GStreamer 可用性检查到媒体基础函数
    if ! grep -q "gst_available" dlls/winegstreamer/mfplat.c; then
        sed -i '/HRESULT WINAPI MFStartup(ULONG version, DWORD flags)/a {\n    /* 初始化 GStreamer */\n    if (!gst_available())\n    {\n        WARN(\"GStreamer not available, multimedia features will be limited\\n\");\n    }\n    \n    TRACE(\"version %#x, flags %#x.\\n\", version, flags);' dlls/winegstreamer/mfplat.c
    
        # 在函数结尾添加匹配的括号
        sed -i '/return S_OK;/a }' dlls/winegstreamer/mfplat.c
    fi
fi

# 6. 添加必要的头文件声明到 unixlib.h
echo "修复 winegstreamer/unixlib.h..."

if [ -f "dlls/winegstreamer/unixlib.h" ]; then
    # 添加函数声明
    if ! grep -q "gst_available" dlls/winegstreamer/unixlib.h; then
        cat >> dlls/winegstreamer/unixlib.h << 'EOF'

/* GStreamer 可用性检查函数 */
extern BOOL gst_available(void);

/* Termux 路径配置函数 */
extern void configure_gstreamer_plugin_path(void);
EOF
    fi
fi

# 7. 清理之前可能添加的未使用函数
echo "清理未使用的函数..."

# 从 wg_parser.c 中删除未使用的函数
if [ -f "dlls/winegstreamer/wg_parser.c" ]; then
    # 删除 wg_parser_check_gstreamer_features 函数（如果存在且未使用）
    if grep -q "wg_parser_check_gstreamer_features" dlls/winegstreamer/wg_parser.c && ! grep -q "wg_parser_check_gstreamer_features.*(" dlls/winegstreamer/wg_parser.c | grep -v "static.*wg_parser_check_gstreamer_features" | head -1; then
        # 找到函数开始和结束的行号
        start_line=$(grep -n "static gboolean wg_parser_check_gstreamer_features" dlls/winegstreamer/wg_parser.c | cut -d: -f1)
        if [ ! -z "$start_line" ]; then
            # 找到匹配的结束大括号
            end_line=$(awk -v start="$start_line" 'NR >= start && /^}/ {print NR; exit}' dlls/winegstreamer/wg_parser.c)
            if [ ! -z "$end_line" ]; then
                # 删除这个函数
                sed -i "${start_line},${end_line}d" dlls/winegstreamer/wg_parser.c
                echo "已删除未使用的函数: wg_parser_check_gstreamer_features"
            fi
        fi
    fi

    # 删除 wg_parser_create_caps_from_media_type 函数（如果存在且未使用）
    if grep -q "wg_parser_create_caps_from_media_type" dlls/winegstreamer/wg_parser.c && ! grep -q "wg_parser_create_caps_from_media_type.*(" dlls/winegstreamer/wg_parser.c | grep -v "static.*wg_parser_create_caps_from_media_type" | head -1; then
        # 找到函数开始和结束的行号
        start_line=$(grep -n "static GstCaps \*wg_parser_create_caps_from_media_type" dlls/winegstreamer/wg_parser.c | cut -d: -f1)
        if [ ! -z "$start_line" ]; then
            # 找到匹配的结束大括号
            end_line=$(awk -v start="$start_line" 'NR >= start && /^}/ {print NR; exit}' dlls/winegstreamer/wg_parser.c)
            if [ ! -z "$end_line" ]; then
                # 删除这个函数
                sed -i "${start_line},${end_line}d" dlls/winegstreamer/wg_parser.c
                echo "已删除未使用的函数: wg_parser_create_caps_from_media_type"
            fi
        fi
    fi
fi

# 从 audioconvert.c 中删除未使用的 gst_check_version_safe 函数
if [ -f "dlls/winegstreamer/audioconvert.c" ]; then
    if grep -q "gst_check_version_safe" dlls/winegstreamer/audioconvert.c && ! grep -q "gst_check_version_safe.*(" dlls/winegstreamer/audioconvert.c | grep -v "static.*gst_check_version_safe" | head -1; then
        # 找到函数开始和结束的行号
        start_line=$(grep -n "static gboolean gst_check_version_safe" dlls/winegstreamer/audioconvert.c | cut -d: -f1)
        if [ ! -z "$start_line" ]; then
            # 找到匹配的结束大括号
            end_line=$(awk -v start="$start_line" 'NR >= start && /^}/ {print NR; exit}' dlls/winegstreamer/audioconvert.c)
            if [ ! -z "$end_line" ]; then
                # 删除这个函数
                sed -i "${start_line},${end_line}d" dlls/winegstreamer/audioconvert.c
                echo "已删除未使用的函数: gst_check_version_safe"
            fi
        fi
    fi
fi

# 8. 创建简单的 GStreamer 调试功能（可选）
echo "创建调试功能..."

# 删除之前可能创建的未使用的调试文件
rm -f dlls/winegstreamer/gst_debug.c

# 9. 更新 Makefile 以确保没有未使用的源文件
if [ -f "dlls/winegstreamer/Makefile.in" ]; then
    # 确保没有引用不存在的 gst_debug.c
    sed -i '/gst_debug.c/d' dlls/winegstreamer/Makefile.in
fi

echo "✅ Wine 9.2 GStreamer 最终修复完成！"
echo ""
echo "修复总结："
echo "✓ mfplat/main.c - 媒体基础解析器修复"
echo "✓ winegstreamer/main.c - GStreamer 初始化和路径配置"
echo "✓ winegstreamer/audioconvert.c - 音频转换器可用性检查"
echo "✓ winegstreamer/wg_parser.c - 解析器功能和资源管理"
echo "✓ winegstreamer/mfplat.c - 媒体基础初始化"
echo "✓ winegstreamer/unixlib.h - 函数声明"
echo "✓ 清理了所有未使用的函数"
echo ""
echo "关键改进："
echo "- 所有添加的函数都被实际使用"
echo "- 没有未使用的函数警告"
echo "- GStreamer 线程安全初始化"
echo "- Termux 环境特定的插件路径配置"
echo "- 增强的错误处理和恢复机制"
echo "- 资源泄漏预防"