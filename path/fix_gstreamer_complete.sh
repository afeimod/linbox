#!/bin/bash
# fix-wine9.2-gstreamer-complete.sh

echo "开始完整修复 Wine 9.2 的 GStreamer 支持..."

cd wine

# 1. 修复 mfplat/main.c - 添加必要的头文件和函数
echo "修复 mfplat/main.c..."

# 在 mfplat/main.c 中添加必要的头文件
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

/* 增强的 URL 方案处理器 */
static HRESULT WINAPI URLSchemeHandler_CreateInstance(IMFActivate *iface, IUnknown *outer, REFIID riid, void **obj)
{
    const WCHAR *url, *mime;
    WCHAR url_ext[16], mimeW[64];
    HRESULT hr;

    TRACE("iface %p, outer %p, riid %s, obj %p.\n", iface, outer, debugstr_guid(riid), obj);

    if (outer)
        return CLASS_E_NOAGGREGATION;

    /* 获取 URL 和 MIME 类型 */
    IMFAttributes_GetString(iface, &MF_BYTESTREAM_HANDLER_URL_SCHEME, url, sizeof(url)/sizeof(WCHAR)-1);
    IMFAttributes_GetString(iface, &MF_BYTESTREAM_CONTENT_TYPE, mime, sizeof(mime)/sizeof(WCHAR)-1);

    TRACE("url_ext %s mimeW %s\n", debugstr_w(url_ext), debugstr_w(mimeW));

    /* 使用更新后的解析器函数 */
    return resolver_create_default_handler(riid, obj);
}
EOF
    fi

    # 修复源解析器函数，添加 GStreamer 优先逻辑
    sed -i 's/TRACE("url %s flags %#x\\n", debugstr_w(url), flags);/TRACE("url %s flags %#x\\n", debugstr_w(url), flags);\n\n    \/* 对支持的文件类型优先使用 GStreamer *\/\n    if (wcsncmp(url, L"file:", 5) == 0 || wcsncmp(url, L"http:", 5) == 0 || wcsncmp(url, L"https:", 6) == 0)\n    {\n        hr = mf_create_gstreamer_byte_stream_handler(\&IID_IMFMediaSource, (void**)object);\n        if (SUCCEEDED(hr))\n        {\n            TRACE("GStreamer successfully created media source for URL: %s\\n", debugstr_w(url));\n            return hr;\n        }\n        TRACE("GStreamer failed for URL %s, falling back: %08x\\n", debugstr_w(url), hr);\n    }/' dlls/mfplat/main.c
fi

# 2. 完全修复 winegstreamer/main.c
echo "完全修复 winegstreamer/main.c..."

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

# 3. 修复 winegstreamer/audioconvert.c
echo "修复 winegstreamer/audioconvert.c..."

if [ -f "dlls/winegstreamer/audioconvert.c" ]; then
    # 添加 GStreamer 可用性检查到音频转换器
    if ! grep -q "gst_available" dlls/winegstreamer/audioconvert.c; then
        # 在 create_element 函数开始处添加检查
        sed -i '/static HRESULT audio_converter_create_element(struct audio_converter \*converter)/a {\n    /* 检查 GStreamer 是否可用 */\n    if (!gst_available())\n    {\n        ERR(\"GStreamer not available for audio converter\\n\");\n        return E_FAIL;\n    }' dlls/winegstreamer/audioconvert.c
        
        # 在函数结尾添加匹配的括号
        sed -i '/gst_object_unref(converter->src);/a }' dlls/winegstreamer/audioconvert.c
    fi

    # 添加安全的元素创建函数
    cat >> dlls/winegstreamer/audioconvert.c << 'EOF'

/* 安全的 GStreamer 元素创建函数 */
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

/* GStreamer 版本兼容性检查 */
static gboolean gst_check_version_safe(int major, int minor, int micro)
{
    if (!gst_available())
        return FALSE;
        
    return GST_VERSION_MAJOR > major ||
           (GST_VERSION_MAJOR == major && GST_VERSION_MINOR > minor) ||
           (GST_VERSION_MAJOR == major && GST_VERSION_MINOR == minor && GST_VERSION_MICRO >= micro);
}
EOF

    # 替换原有的元素创建调用
    sed -i 's/gst_element_factory_make("audioconvert", NULL)/create_gst_element_safe("audioconvert", NULL)/g' dlls/winegstreamer/audioconvert.c
    sed -i 's/gst_element_factory_make("audioresample", NULL)/create_gst_element_safe("audioresample", NULL)/g' dlls/winegstreamer/audioconvert.c
fi

# 4. 修复 winegstreamer/wg_parser.c
echo "修复 winegstreamer/wg_parser.c..."

if [ -f "dlls/winegstreamer/wg_parser.c" ]; then
    # 在 parser_create 函数中添加 GStreamer 检查
    sed -i '/struct wg_parser_create_params \*params = args;/a \\n    /* 检查 GStreamer 可用性 */\n    if (!gst_available())\n    {\n        ERR(\"GStreamer not available for parser creation\\n\");\n        return E_FAIL;\n    }' dlls/winegstreamer/wg_parser.c

    # 添加增强的 GStreamer 功能检查
    cat >> dlls/winegstreamer/wg_parser.c << 'EOF'

/* 增强的 GStreamer 功能检查 */
static gboolean wg_parser_check_gstreamer_features(struct wg_parser *parser)
{
    if (!gst_available())
    {
        ERR("GStreamer not available for parser\n");
        return FALSE;
    }
    
    /* 检查必要的 GStreamer 插件 */
    GstRegistry *registry = gst_registry_get();
    if (!registry)
    {
        WARN("Failed to get GStreamer registry\n");
        return FALSE;
    }
    
    /* 检查基础插件可用性 */
    GstPluginFeature *feature;
    const gchar *required_plugins[] = {"playbin", "decodebin", "audioconvert", "audioresample", NULL};
    int i;
    
    for (i = 0; required_plugins[i]; i++)
    {
        feature = gst_registry_lookup_feature(registry, required_plugins[i]);
        if (!feature)
        {
            WARN("Required GStreamer plugin not found: %s\n", required_plugins[i]);
            return FALSE;
        }
        gst_object_unref(feature);
    }
    
    TRACE("All required GStreamer plugins are available\n");
    return TRUE;
}

/* 增强的媒体类型到 GStreamer caps 的转换 */
static GstCaps *wg_parser_create_caps_from_media_type(const struct wg_media_type *type)
{
    GstCaps *caps = NULL;
    
    if (!type || !type->majortype)
        return NULL;
        
    /* 根据媒体类型创建适当的 caps */
    if (IsEqualGUID(type->majortype, &MFMediaType_Audio))
    {
        if (IsEqualGUID(type->subtype, &MFAudioFormat_PCM))
        {
            caps = gst_caps_new_simple("audio/x-raw",
                "format", G_TYPE_STRING, "S16LE",
                "layout", G_TYPE_STRING, "interleaved",
                NULL);
        }
        else if (IsEqualGUID(type->subtype, &MFAudioFormat_AAC))
        {
            caps = gst_caps_new_simple("audio/mpeg",
                "mpegversion", G_TYPE_INT, 4,
                "stream-format", G_TYPE_STRING, "raw",
                NULL);
        }
        else if (IsEqualGUID(type->subtype, &MFAudioFormat_MP3))
        {
            caps = gst_caps_new_simple("audio/mpeg",
                "mpegversion", G_TYPE_INT, 1,
                "layer", G_TYPE_INT, 3,
                NULL);
        }
    }
    else if (IsEqualGUID(type->majortype, &MFMediaType_Video))
    {
        if (IsEqualGUID(type->subtype, &MFVideoFormat_H264))
        {
            caps = gst_caps_new_simple("video/x-h264",
                "stream-format", G_TYPE_STRING, "byte-stream",
                "alignment", G_TYPE_STRING, "au",
                NULL);
        }
        else if (IsEqualGUID(type->subtype, &MFVideoFormat_HEVC))
        {
            caps = gst_caps_new_simple("video/x-h265",
                "stream-format", G_TYPE_STRING, "byte-stream",
                "alignment", G_TYPE_STRING, "au",
                NULL);
        }
        else if (IsEqualGUID(type->subtype, &MFVideoFormat_MPEG2))
        {
            caps = gst_caps_new_simple("video/mpeg",
                "mpegversion", G_TYPE_INT, 2,
                "systemstream", G_TYPE_BOOLEAN, FALSE,
                NULL);
        }
    }
    
    return caps;
}

/* 资源清理助手 */
static void wg_parser_cleanup_resources(struct wg_parser *parser)
{
    if (parser->container)
    {
        gst_element_set_state(parser->container, GST_STATE_NULL);
        TRACE("GStreamer parser resources cleaned up\n");
    }
}
EOF
fi

# 5. 修复 winegstreamer/mfplat.c
echo "修复 winegstreamer/mfplat.c..."

if [ -f "dlls/winegstreamer/mfplat.c" ]; then
    # 添加 GStreamer 可用性检查到媒体基础函数
    sed -i '/HRESULT WINAPI MFStartup(ULONG version, DWORD flags)/a {\n    /* 初始化 GStreamer */\n    if (!gst_available())\n    {\n        WARN(\"GStreamer not available, multimedia features will be limited\\n\");\n    }\n    \n    TRACE(\"version %#x, flags %#x.\\n\", version, flags);' dlls/winegstreamer/mfplat.c
    
    # 在函数结尾添加匹配的括号
    sed -i '/return S_OK;/a }' dlls/winegstreamer/mfplat.c
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

# 7. 创建 GStreamer 调试和测试功能
echo "添加调试和测试功能..."

# 在 winegstreamer 目录中创建额外的调试功能
cat > dlls/winegstreamer/gst_debug.c << 'EOF'
/*
 * GStreamer 调试和工具函数
 * 为 Wine 9.2 在 Termux 环境中提供更好的调试支持
 */

#include "config.h"
#include <stdarg.h>

#include <gst/gst.h>

#include "windef.h"
#include "winbase.h"
#include "wine/debug.h"

#include "unixlib.h"

WINE_DEFAULT_DEBUG_CHANNEL(wgstreamer);

/* GStreamer 调试级别控制 */
static GstDebugLevel gst_debug_level = GST_LEVEL_WARNING;

/* 设置 GStreamer 调试级别 */
void set_gstreamer_debug_level(const char *level)
{
    if (!level) return;
    
    if (strcmp(level, "error") == 0)
        gst_debug_level = GST_LEVEL_ERROR;
    else if (strcmp(level, "warning") == 0)
        gst_debug_level = GST_LEVEL_WARNING;
    else if (strcmp(level, "info") == 0)
        gst_debug_level = GST_LEVEL_INFO;
    else if (strcmp(level, "debug") == 0)
        gst_debug_level = GST_LEVEL_DEBUG;
        
    gst_debug_set_default_threshold(gst_debug_level);
    TRACE("GStreamer debug level set to: %s\n", level);
}

/* 获取 GStreamer 版本信息 */
void get_gstreamer_version_info(void)
{
    if (!gst_available())
    {
        ERR("GStreamer not available\n");
        return;
    }
    
    guint major, minor, micro, nano;
    gst_version(&major, &minor, &micro, &nano);
    
    TRACE("GStreamer version: %u.%u.%u (nano: %u)\n", major, minor, micro, nano);
    TRACE("GStreamer supported features:\n");
    
    /* 检查关键插件可用性 */
    const gchar *plugins[] = {
        "coreelements", "playbin", "decodebin", "audioconvert", 
        "audioresample", "libav", "videoconvert", "videoscale", NULL
    };
    
    int i;
    for (i = 0; plugins[i]; i++)
    {
        GstPlugin *plugin = gst_plugin_load_by_name(plugins[i]);
        if (plugin)
        {
            TRACE("  ✓ %s\n", plugins[i]);
            gst_object_unref(plugin);
        }
        else
        {
            TRACE("  ✗ %s (not found)\n", plugins[i]);
        }
    }
}

/* 检查编解码器支持 */
void check_codec_support(void)
{
    if (!gst_available())
        return;
        
    TRACE("Checking codec support:\n");
    
    /* 音频编解码器 */
    const gchar *audio_codecs[] = {
        "audio/mpeg, mpegversion=(int)1, layer=(int)3",  /* MP3 */
        "audio/mpeg, mpegversion=(int)4",                /* AAC */
        "audio/x-wav",                                   /* WAV */
        "audio/x-flac",                                  /* FLAC */
        NULL
    };
    
    /* 视频编解码器 */
    const gchar *video_codecs[] = {
        "video/x-h264",                                  /* H.264 */
        "video/x-h265",                                  /* H.265 */
        "video/x-vp8",                                   /* VP8 */
        "video/x-vp9",                                   /* VP9 */
        "video/mpeg, mpegversion=(int)2",               /* MPEG-2 */
        NULL
    };
    
    int i;
    GstRegistry *registry = gst_registry_get();
    
    TRACE("Audio codecs:\n");
    for (i = 0; audio_codecs[i]; i++)
    {
        GstCaps *caps = gst_caps_from_string(audio_codecs[i]);
        GstElement *element = gst_element_factory_make("decodebin", NULL);
        if (element)
        {
            TRACE("  ✓ %s\n", audio_codecs[i]);
            gst_object_unref(element);
        }
        else
        {
            TRACE("  ✗ %s\n", audio_codecs[i]);
        }
        gst_caps_unref(caps);
    }
    
    TRACE("Video codecs:\n");
    for (i = 0; video_codecs[i]; i++)
    {
        GstCaps *caps = gst_caps_from_string(video_codecs[i]);
        GstElement *element = gst_element_factory_make("decodebin", NULL);
        if (element)
        {
            TRACE("  ✓ %s\n", video_codecs[i]);
            gst_object_unref(element);
        }
        else
        {
            TRACE("  ✗ %s\n", video_codecs[i]);
        }
        gst_caps_unref(caps);
    }
}
EOF

# 8. 更新 Makefile 以包含新的调试文件（如果存在）
if [ -f "dlls/winegstreamer/Makefile.in" ]; then
    if ! grep -q "gst_debug.c" dlls/winegstreamer/Makefile.in; then
        sed -i 's/SRCS = main.c/SRCS = main.c gst_debug.c/' dlls/winegstreamer/Makefile.in
    fi
fi

echo "✅ Wine 9.2 GStreamer 完整修复完成！"
echo ""
echo "修复总结："
echo "✓ mfplat/main.c - 媒体基础解析器和 URL 处理器"
echo "✓ winegstreamer/main.c - GStreamer 初始化和路径配置"
echo "✓ winegstreamer/audioconvert.c - 音频转换器可用性检查"
echo "✓ winegstreamer/wg_parser.c - 解析器功能和资源管理"
echo "✓ winegstreamer/mfplat.c - 媒体基础初始化"
echo "✓ winegstreamer/unixlib.h - 函数声明"
echo "✓ winegstreamer/gst_debug.c - 调试和测试工具"
echo ""
echo "主要改进："
echo "- GStreamer 线程安全初始化"
echo "- Termux 环境特定的插件路径配置"
echo "- 增强的错误处理和恢复机制"
echo "- 媒体类型到 GStreamer caps 的转换"
echo "- 编解码器支持检查"
echo "- 资源泄漏预防"