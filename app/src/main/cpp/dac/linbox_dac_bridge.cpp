/*
 * LinBox DAC bridge — Direct Android Compositing 呈现桥（app 进程内 JNI）
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 职责：
 *   1. 监听 $PREFIX/tmp/linbox-dac.sock，接受 winedac.drv 连接（同 UID 无需特权）
 *   2. 导入 wine 侧发来的 AHardwareBuffer（recvHandle）或 dmabuf fd
 *   3. 三级呈现后端：
 *      [SF_DIRECT]  API29+: ASurfaceControl.createFromWindow(SurfaceView 的
 *                   ANativeWindow) + ASurfaceTransaction.setBuffer(ahb, fence)
 *                   —— buffer 直交 SurfaceFlinger 合成，零拷贝（桌面层 +
 *                   DXVK 独立图层）
 *      [AHB_CANVAS] API26-28: AHardwareBuffer.lock(CPU_READ) →
 *                   逐像素拷贝 → Surface lockCanvas 绘制（兼容降级）
 *      [DMABUF_EGL] glibc Wine：dmabuf fd → EGLImage → GL blit（兼容兜底）
 *   4. buffer 释放回执（OnComplete → RELEASE / VK_RELEASED + release fence）
 *   5. 输入下行：Kotlin 侧注入 → INPUT_MOUSE / INPUT_KEY 帧 → wine
 *
 * 线程模型：
 *   - accept 线程（连接到达后转为该连接的读线程）
 *   - OnComplete 回调线程（SurfaceFlinger 提供）
 *   - Kotlin UI 线程（输入注入经加锁队列由读线程统一发送）
 */

#include <jni.h>
#include <stdio.h>
#include <dlfcn.h>
#include <android/api-level.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <android/data_space.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>

#include "bridge.h"

#define LOG_TAG "LinBoxDAC"

#ifndef max
#define max(a,b) ((a) > (b) ? (a) : (b))
#define min(a,b) ((a) < (b) ? (a) : (b))
#endif
#define LOGI(...) __android_log_print( ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__ )
#define LOGW(...) __android_log_print( ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__ )
#define LOGE(...) __android_log_print( ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__ )

/* =====================================================================
 * 全局状态
 * =================================================================== */
static JavaVM    *g_vm;
static jobject    g_title_sink;                 /* DacNative.TitleSink 对象 */
static jmethodID  g_title_mid;

static int        g_listen_fd = -1;
static int        g_conn_fd = -1;               /* 当前 wine 连接 */
static pthread_mutex_t g_send_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t  g_accept_thread;

static ANativeWindow *g_anw;                    /* SurfaceView 的窗口 */
static ASurfaceControl *g_root_sc;              /* 由窗口创建的根控制块 */
static int        g_backend = DAC_BACKEND_NONE;
static int        g_api_level;

/* 桌面槽位 */
struct desktop_slot
{
    AHardwareBuffer *ahb;
    uint32_t width, height, stride, format;
    /* AHB_CANVAS 降级用 */
    void *bits;
    int imported;
};
static struct desktop_slot g_slots[DAC_DESKTOP_MAX_SLOTS];
static uint32_t g_desktop_w, g_desktop_h;

/* VK surface */
struct vk_surface
{
    uint64_t id;
    uint32_t image_count, width, height, format, is_dmabuf;
    AHardwareBuffer *images[8];
    uint32_t strides[8];
    ASurfaceControl *sc;
    uint32_t pending_mask;
    int32_t x, y;
    uint32_t w, h, z;
};
static struct vk_surface g_surfaces[4];

static pthread_mutex_t g_state_lock = PTHREAD_MUTEX_INITIALIZER;

/* =====================================================================
 * fd 发送
 * =================================================================== */
static int write_all( int fd, const void *buf, size_t len )
{
    const unsigned char *p = (const unsigned char *)buf;
    size_t off = 0;
    while (off < len)
    {
        ssize_t n = send( fd, p + off, len - off, MSG_NOSIGNAL );
        if (n <= 0)
        {
            if (n < 0 && errno == EINTR) continue;
            return -1;
        }
        off += n;
    }
    return 0;
}

static int read_full( int fd, void *buf, size_t len )
{
    unsigned char *p = (unsigned char *)buf;
    size_t off = 0;
    while (off < len)
    {
        ssize_t n = recv( fd, p + off, len - off, 0 );
        if (n <= 0) return -1;
        off += n;
    }
    return 0;
}

/* =====================================================================
 * AHB 导入
 * =================================================================== */
/* AHardwareBuffer_recvHandleFromUnixSocket：API 26+（libandroid） */
static int (*p_recv_handle)( int socket_fd, AHardwareBuffer **outBuffer );

static AHardwareBuffer *import_ahb( int conn )
{
    AHardwareBuffer *ahb = NULL;
    if (p_recv_handle( conn, &ahb ))
    {
        LOGE( "recvHandle failed: %s", strerror( errno ) );
        return NULL;
    }
    return ahb;
}

static void release_ahb( AHardwareBuffer *ahb )
{
    if (ahb) AHardwareBuffer_release( ahb );
}

/* =====================================================================
 * [SF_DIRECT] SurfaceControl 呈现
 * =================================================================== */
static ASurfaceTransaction *make_transaction(void)
{
    return ASurfaceTransaction_create();
}

/* vk_surface 几何应用（定位+缩放+层级）：
 * 统一走 ASurfaceTransaction_setGeometry（API 29 稳定接口）。
 * 注意：setPosition/setScale 是 API 31 才引入，本文件按 C++ 编译
 * （surface_control.h 的 setGeometry/setBuffer 签名含 C++ 引用/默认
 * 参数，纯 C 模式无法解析）——见 app/build.gradle.kts 的
 * buildDacBridge 任务（clang++ -x c++）。 */
static void apply_vk_geometry( ASurfaceTransaction *trans, struct vk_surface *sf )
{
    ARect src, dst;
    const int32_t bw = (int32_t)sf->width, bh = (int32_t)sf->height;
    /* source = 整幅 buffer；destination = 父坐标系 (x,y,w,h)，
     * 由 SurfaceFlinger 完成 source→destination 的缩放映射 */
    const int32_t dw = sf->w ? (int32_t)sf->w : bw;
    const int32_t dh = sf->h ? (int32_t)sf->h : bh;
    src.left = 0;  src.top  = 0;
    src.right = bw; src.bottom = bh;
    dst.left = (int32_t)sf->x; dst.top = (int32_t)sf->y;
    dst.right = dst.left + dw; dst.bottom = dst.top + dh;
    ASurfaceTransaction_setGeometry( trans, sf->sc, src, dst, 0 );

    ASurfaceTransaction_setZOrder( trans, sf->sc, (int32_t)sf->z + 1 );
    ASurfaceTransaction_setVisibility( trans, sf->sc, ASURFACE_TRANSACTION_VISIBILITY_SHOW );
}

/* 预留：v2 直连路径（不经 tracked 回调） */
__attribute__((unused))
static void attach_vk_buffer_direct( struct vk_surface *sf, unsigned int idx, int fence_fd )
{
    ASurfaceTransaction *trans = make_transaction();
    if (!trans) return;

    ASurfaceTransaction_setBuffer( trans, sf->sc, sf->images[idx], fence_fd );

    apply_vk_geometry( trans, sf );

    ASurfaceTransaction_apply( trans );
    ASurfaceTransaction_delete( trans );
}

/* =====================================================================
 * [AHB_CANVAS] 降级：CPU 拷贝 → SurfaceView
 * =================================================================== */
static ANativeWindow_Buffer g_canvas_buffer;

static void present_desktop_canvas( struct desktop_slot *slot, int32_t dl, int32_t dt,
                                    int32_t dr, int32_t db )
{
    AHardwareBuffer_Desc desc;
    void *bits = NULL;

    AHardwareBuffer_describe( slot->ahb, &desc );
    if (AHardwareBuffer_lock( slot->ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                              -1, NULL, &bits ))
    {
        LOGW( "AHB lock failed" );
        return;
    }

    {
        ANativeWindow *anw = g_anw;
        if (anw && ANativeWindow_getWidth( anw ) > 0)
        {
            int32_t vw = ANativeWindow_getWidth( anw );
            int32_t vh = ANativeWindow_getHeight( anw );
            uint32_t row;
            /* 1:1 + 居中（v1；完整缩放拟合 v2） */
            int32_t ox = (vw - (int32_t)slot->width) / 2;
            int32_t oy = (vh - (int32_t)slot->height) / 2;
            if (ox < 0) ox = 0;
            if (oy < 0) oy = 0;

            if (ANativeWindow_lock( anw, &g_canvas_buffer, NULL ) == 0)
            {
                uint8_t *dst = (uint8_t *)g_canvas_buffer.bits;
                uint8_t *src = (uint8_t *)bits;
                int32_t y0 = max( dt, 0 ), y1 = min( db, (int32_t)vh );
                for (row = y0; row < (uint32_t)y1; row++)
                {
                    int32_t sy = row, dy = row + oy;
                    if (dy >= vh) break;
                    memcpy( dst + (size_t)dy * g_canvas_buffer.stride * 4 + (size_t)ox * 4,
                            src + (size_t)sy * desc.stride * 4,
                            (size_t)min( dr, vw ) * 4 );
                }
                ANativeWindow_unlockAndPost( anw );
            }
        }
    }
    AHardwareBuffer_unlock( slot->ahb, NULL );
}

/* =====================================================================
 * [DMABUF_EGL] 兜底：EGLImage blit（glibc wine 的 dmabuf 模式）
 * =================================================================== */
static EGLDisplay g_dpy = EGL_NO_DISPLAY;
static EGLContext g_ctx = EGL_NO_CONTEXT;
static EGLSurface g_egl_surf = EGL_NO_SURFACE;
static EGLConfig  g_cfg;

static PFNEGLCREATEIMAGEKHRPROC p_eglCreateImageKHR;
static PFNEGLDESTROYIMAGEKHRPROC p_eglDestroyImageKHR;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC p_glEGLImageTargetTexture2DOES;

struct dmabuf_image
{
    EGLImageKHR image;
    GLuint tex;
    uint32_t slot_or_idx;
    uint64_t surface_id;
};
static struct dmabuf_image g_dmabufs[32];
static pthread_mutex_t g_egl_lock = PTHREAD_MUTEX_INITIALIZER;

/* 预留：dmabuf 直合路径初始化 */
__attribute__((unused))
static int egl_init( ANativeWindow *anw )
{
    EGLint num;
    const EGLint cfg_attr[] =
    {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE
    };
    const EGLint ctx_attr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };

    g_dpy = eglGetDisplay( EGL_DEFAULT_DISPLAY );
    if (g_dpy == EGL_NO_DISPLAY || !eglInitialize( g_dpy, NULL, NULL ))
    {
        LOGE( "eglInitialize failed" );
        return -1;
    }
    if (!eglChooseConfig( g_dpy, cfg_attr, &g_cfg, 1, &num ) || num < 1)
    {
        LOGE( "eglChooseConfig failed" );
        return -1;
    }
    g_ctx = eglCreateContext( g_dpy, g_cfg, EGL_NO_CONTEXT, ctx_attr );
    g_egl_surf = eglCreateWindowSurface( g_dpy, g_cfg, anw, NULL );
    if (g_ctx == EGL_NO_CONTEXT || g_egl_surf == EGL_NO_SURFACE)
    {
        LOGE( "egl ctx/surface failed" );
        return -1;
    }

    p_eglCreateImageKHR =
        (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress( "eglCreateImageKHR" );
    p_eglDestroyImageKHR =
        (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress( "eglDestroyImageKHR" );
    p_glEGLImageTargetTexture2DOES =
        (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress( "glEGLImageTargetTexture2DOES" );
    if (!p_eglCreateImageKHR || !p_glEGLImageTargetTexture2DOES)
    {
        LOGE( "missing EGL_KHR_image extensions" );
        return -1;
    }
    LOGI( "EGL blit backend ready" );
    return 0;
}

static EGLImageKHR import_dmabuf_egl( int fd, uint32_t w, uint32_t h,
                                      uint32_t stride_px, uint32_t fourcc )
{
    EGLint attrs[13];
    int i = 0;
    /* EGL_LINUX_DMA_BUF_EXT = 0x3270 */
    attrs[i++] = 0x3270;
    /* EGL_WIDTH 0x3057 / EGL_HEIGHT 0x3056 */
    attrs[i++] = 0x3057; attrs[i++] = w;
    attrs[i++] = 0x3056; attrs[i++] = h;
    /* EGL_LINUX_DRM_FOURCC_EXT 0x3271 */
    attrs[i++] = 0x3271; attrs[i++] = fourcc;
    /* EGL_DMA_BUF_PLANE0_FD_EXT 0x3272 / _OFFSET 0x3273 / _PITCH 0x3274 */
    attrs[i++] = 0x3272; attrs[i++] = fd;
    attrs[i++] = 0x3273; attrs[i++] = 0;
    attrs[i++] = 0x3274; attrs[i++] = stride_px * 4;
    attrs[i++] = EGL_NONE;

    return p_eglCreateImageKHR( g_dpy, EGL_NO_CONTEXT, 0x3270 /* EGL_LINUX_DMA_BUF_EXT */,
                                (EGLClientBuffer)0, attrs );
}

/* 全屏纹理着色器（GL_TEXTURE_EXTERNAL_OES 兼容 path 见 README 备注） */
static GLuint g_prog;

static GLuint compile_shader( GLenum type, const char *src )
{
    GLuint sh = glCreateShader( type );
    glShaderSource( sh, 1, &src, NULL );
    glCompileShader( sh );
    return sh;
}

static void egl_init_program(void)
{
    static const char vs[] =
        "attribute vec2 a_pos; varying vec2 v_uv;\n"
        "void main() { v_uv = (a_pos + 1.0) * 0.5; v_uv.y = 1.0 - v_uv.y;\n"
        "  gl_Position = vec4(a_pos, 0.0, 1.0); }\n";
    static const char fs[] =
        "precision mediump float; varying vec2 v_uv;\n"
        "uniform sampler2D u_tex;\n"
        "void main() { gl_FragColor = texture2D(u_tex, v_uv); }\n";
    GLuint vs_s, fs_s, prog;
    GLint ok = 0;
    const float quad[] = { -1,-1, 1,-1, -1,1, 1,1 };

    vs_s = compile_shader( GL_VERTEX_SHADER, vs );
    fs_s = compile_shader( GL_FRAGMENT_SHADER, fs );
    prog = glCreateProgram();
    glAttachShader( prog, vs_s );
    glAttachShader( prog, fs_s );
    glLinkProgram( prog );
    glGetProgramiv( prog, GL_LINK_STATUS, &ok );
    if (!ok) { glDeleteProgram( prog ); return; }
    glUseProgram( prog );
    {
        GLint loc = glGetAttribLocation( prog, "a_pos" );
        GLuint vbo;
        glGenBuffers( 1, &vbo );
        glBindBuffer( GL_ARRAY_BUFFER, vbo );
        glBufferData( GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW );
        glEnableVertexAttribArray( loc );
        glVertexAttribPointer( loc, 2, GL_FLOAT, GL_FALSE, 0, 0 );
    }
    {
        GLint loc = glGetUniformLocation( prog, "u_tex" );
        glUniform1i( loc, 0 );
    }
    g_prog = prog;
}

static void dac_egl_draw_texture( GLuint tex )
{
    if (!g_prog) egl_init_program();
    if (!g_prog) return;
    glActiveTexture( GL_TEXTURE0 );
    glBindTexture( GL_TEXTURE_2D, tex );
    glDrawArrays( GL_TRIANGLE_STRIP, 0, 4 );
}

static void blit_dmabuf( int fd, uint32_t w, uint32_t h, uint32_t stride_px,
                         uint32_t fourcc )
{
    struct dmabuf_image *img = NULL;

    pthread_mutex_lock( &g_egl_lock );
    if (g_dpy == EGL_NO_DISPLAY) { pthread_mutex_unlock( &g_egl_lock ); return; }
    eglMakeCurrent( g_dpy, g_egl_surf, g_egl_surf, g_ctx );

    /* 复用单槽：每帧重建 image（dmabuf fd 每帧不同；语义上无状态） */
    img = &g_dmabufs[0];
    if (img->image)
    {
        glDeleteTextures( 1, &img->tex );
        p_eglDestroyImageKHR( g_dpy, img->image );
        img->image = NULL;
    }
    img->image = import_dmabuf_egl( fd, w, h, stride_px, fourcc );
    if (img->image == EGL_NO_IMAGE_KHR)
    {
        pthread_mutex_unlock( &g_egl_lock );
        return;
    }
    glGenTextures( 1, &img->tex );
    glBindTexture( GL_TEXTURE_2D, img->tex );
    p_glEGLImageTargetTexture2DOES( GL_TEXTURE_2D, img->image );

    dac_egl_draw_texture( img->tex );


    glDeleteTextures( 1, &img->tex );
    p_eglDestroyImageKHR( g_dpy, img->image );
    img->image = NULL;
    eglSwapBuffers( g_dpy, g_egl_surf );
    eglMakeCurrent( g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT );
    pthread_mutex_unlock( &g_egl_lock );
}

/* =====================================================================
 * buffer 释放回执
 * =================================================================== */
static void send_release( uint32_t slot, int fence_fd )
{
    pthread_mutex_lock( &g_send_lock );
    if (g_conn_fd >= 0)
    {
        struct dac_frame_hdr hdr =
        {
            DAC_PROTOCOL_MAGIC, DAC_MSG_RELEASE, sizeof(uint32_t)
        };
        int fd = fence_fd;
        if (fence_fd >= 0)
        {
            struct msghdr msg;
            struct iovec iov;
            char cbuf[CMSG_SPACE( sizeof(int) )];
            struct cmsghdr *cmsg;

            iov.iov_base = &hdr;
            iov.iov_len  = sizeof(hdr);
            memset( &msg, 0, sizeof(msg) );
            msg.msg_iov = &iov;
            msg.msg_iovlen = 1;
            msg.msg_control = cbuf;
            msg.msg_controllen = sizeof(cbuf);
            cmsg = CMSG_FIRSTHDR( &msg );
            cmsg->cmsg_level = SOL_SOCKET;
            cmsg->cmsg_type  = SCM_RIGHTS;
            cmsg->cmsg_len   = CMSG_LEN( sizeof(int) );
            memcpy( CMSG_DATA( cmsg ), &fd, sizeof(int) );
            if (sendmsg( g_conn_fd, &msg, MSG_NOSIGNAL ) == (ssize_t)sizeof(hdr) &&
                write_all( g_conn_fd, &slot, sizeof(slot) ) == 0)
            { /* sent */ }
            else
                close( fence_fd );
        }
        else
        {
            write_all( g_conn_fd, &hdr, sizeof(hdr) );
            write_all( g_conn_fd, &slot, sizeof(slot) );
        }
    }
    else if (fence_fd >= 0)
        close( fence_fd );
    pthread_mutex_unlock( &g_send_lock );
}

static void send_vk_released( uint64_t surface_id, uint32_t idx, int fence_fd )
{
    struct dac_present rel = { idx, surface_id, 0 };

    pthread_mutex_lock( &g_send_lock );
    if (g_conn_fd >= 0)
    {
        struct dac_frame_hdr hdr =
        {
            DAC_PROTOCOL_MAGIC, DAC_MSG_VK_RELEASED, sizeof(rel)
        };
        int fd = fence_fd;
        if (fence_fd >= 0)
        {
            struct msghdr msg;
            struct iovec iov;
            char cbuf[CMSG_SPACE( sizeof(int) )];
            struct cmsghdr *cmsg;

            iov.iov_base = &hdr;
            iov.iov_len  = sizeof(hdr);
            memset( &msg, 0, sizeof(msg) );
            msg.msg_iov = &iov;
            msg.msg_iovlen = 1;
            msg.msg_control = cbuf;
            msg.msg_controllen = sizeof(cbuf);
            cmsg = CMSG_FIRSTHDR( &msg );
            cmsg->cmsg_level = SOL_SOCKET;
            cmsg->cmsg_type  = SCM_RIGHTS;
            cmsg->cmsg_len   = CMSG_LEN( sizeof(int) );
            memcpy( CMSG_DATA( cmsg ), &fd, sizeof(int) );
            sendmsg( g_conn_fd, &msg, MSG_NOSIGNAL );
            write_all( g_conn_fd, &rel, sizeof(rel) );
            close( fence_fd );
        }
        else
        {
            write_all( g_conn_fd, &hdr, sizeof(hdr) );
            write_all( g_conn_fd, &rel, sizeof(rel) );
        }
    }
    else if (fence_fd >= 0)
        close( fence_fd );
    pthread_mutex_unlock( &g_send_lock );
}

/* OnComplete context 编码：0x1_0000|slot = 桌面；0x2_xxxxxx = vk */
#define DAC_CTXT_DESKTOP(slot)      (0x10000u | (uint64_t)(slot))
#define DAC_CTXT_VK(id, idx)        (0x20000000u | ((uint64_t)(id) << 8) | (uint64_t)(idx))

static void on_transaction_complete( void *context, ASurfaceTransactionStats *stats )
{
    uint64_t kind = (uint64_t)(uintptr_t)context;
    int fence_fd = -1;
    ASurfaceControl **controls = NULL;
    size_t count = 0, i;

    /* 真实 API：getASurfaceControls 返回数组 + size（NDK 无按索引
     * getASurfaceControl / getASurfaceControlCount，原写法为臆造）。 */
    ASurfaceTransactionStats_getASurfaceControls( stats, &controls, &count );
    for (i = 0; i < count && controls; i++)
    {
        int fd = ASurfaceTransactionStats_getPreviousReleaseFenceFd( stats, controls[i] );
        if (fd >= 0) fence_fd = fd;
    }
    if (controls)
        ASurfaceTransactionStats_releaseASurfaceControls( controls );

    if (kind & 0x10000u)
        send_release( (uint32_t)(kind & 0xffff), fence_fd );
    else if (kind & 0x20000000u)
        send_vk_released( kind >> 8, kind & 0xff, fence_fd );
    else if (fence_fd >= 0)
        close( fence_fd );
}

static void attach_desktop_buffer_tracked( uint32_t slot, int fence_fd,
                                           int32_t dl, int32_t dt, int32_t dr, int32_t db )
{
    ASurfaceTransaction *trans = make_transaction();
    if (!trans) return;

    ASurfaceTransaction_setOnComplete( trans, (void *)(uintptr_t)DAC_CTXT_DESKTOP( slot ),
                                   on_transaction_complete );
    ASurfaceTransaction_setBuffer( trans, g_root_sc, g_slots[slot].ahb, fence_fd );
    if (dr > dl && db > dt)
    {
        /* 真实 API：setDamageRegion 收 ARect 数组 + count
         * （NDK 无 ASurfaceDamageRegion/ASurfaceRect 类型）。 */
        ARect damage;
        damage.left = dl; damage.top = dt;
        damage.right = dr; damage.bottom = db;
        ASurfaceTransaction_setDamageRegion( trans, g_root_sc, &damage, 1 );
    }
    ASurfaceTransaction_setVisibility( trans, g_root_sc, ASURFACE_TRANSACTION_VISIBILITY_SHOW );
    ASurfaceTransaction_setZOrder( trans, g_root_sc, 0 );
    ASurfaceTransaction_apply( trans );
    ASurfaceTransaction_delete( trans );
}

static void attach_vk_buffer_tracked( struct vk_surface *sf, unsigned int idx, int fence_fd )
{
    ASurfaceTransaction *trans = make_transaction();
    if (!trans) return;

    ASurfaceTransaction_setOnComplete( trans, (void *)(uintptr_t)DAC_CTXT_VK( sf->id, idx ),
                                   on_transaction_complete );
    ASurfaceTransaction_setBuffer( trans, sf->sc, sf->images[idx], fence_fd );
    apply_vk_geometry( trans, sf );

    ASurfaceTransaction_apply( trans );
    ASurfaceTransaction_delete( trans );
}

/* =====================================================================
 * 输入下行（Kotlin → wine）
 * =================================================================== */
static int send_frame( uint32_t type, const void *payload, uint32_t len )
{
    struct dac_frame_hdr hdr = { DAC_PROTOCOL_MAGIC, type, len };
    int ret;

    pthread_mutex_lock( &g_send_lock );
    if (g_conn_fd < 0) { pthread_mutex_unlock( &g_send_lock ); return -1; }
    ret = write_all( g_conn_fd, &hdr, sizeof(hdr) );
    if (!ret && len) ret = write_all( g_conn_fd, payload, len );
    pthread_mutex_unlock( &g_send_lock );
    return ret;
}

extern "C" void Java_com_linbox_apps_dac_DacNative_nativeSendMouse( JNIEnv *env, jclass clazz,
        jint flags, jfloat x, jfloat y, jint buttons, jfloat wheel_dx, jfloat wheel_dy,
        jboolean absolute )
{
    struct dac_input_mouse m =
    {
        .flags = (uint32_t)flags,
        .x = (int32_t)x, .y = (int32_t)y,
        .buttons = (uint32_t)buttons,
        .wheel_dx = (int32_t)wheel_dx, .wheel_dy = (int32_t)wheel_dy,
        .absolute = (uint32_t)absolute,
    };
    send_frame( DAC_MSG_INPUT_MOUSE, &m, sizeof(m) );
}

extern "C" void Java_com_linbox_apps_dac_DacNative_nativeSendKey( JNIEnv *env, jclass clazz,
        jboolean down, jint keycode, jint meta_state, jint unicode, jint repeat,
        jint source )
{
    struct dac_input_key k =
    {
        .down = (uint32_t)down, .keycode = (uint32_t)keycode, .meta_state = (uint32_t)meta_state,
        .unicode = (uint32_t)unicode, .repeat = (uint32_t)repeat, .source = (uint32_t)source,
    };
    send_frame( DAC_MSG_INPUT_KEY, &k, sizeof(k) );
}

/* =====================================================================
 * 消息读取主循环
 * =================================================================== */
static void handle_hello( int conn, const struct dac_hello *hello )
{
    struct dac_hello_ack ack =
    {
        .ok = 1,
        .backend = (uint32_t)g_backend,
        .api_level = (uint32_t)g_api_level,
        .max_desktop_slots = DAC_DESKTOP_MAX_SLOTS,
    };
    struct dac_frame_hdr hdr = { DAC_PROTOCOL_MAGIC, DAC_MSG_HELLO_ACK, sizeof(ack) };

    g_desktop_w = hello->desktop_width;
    g_desktop_h = hello->desktop_height;
    LOGI( "hello: desktop %ux%u exe %.64s", g_desktop_w, g_desktop_h, hello->exe );

    write_all( conn, &hdr, sizeof(hdr) );
    write_all( conn, &ack, sizeof(ack) );
}

static void handle_desktop_alloc( int conn, const struct dac_buffer_meta *meta )
{
    struct desktop_slot *slot;

    if (meta->slot >= DAC_DESKTOP_MAX_SLOTS) return;
    slot = &g_slots[meta->slot];

    memset( slot, 0, sizeof(*slot) );
    slot->width = meta->width;
    slot->height = meta->height;
    slot->stride = meta->stride;
    slot->format = meta->format;

    /* 帧后紧跟序列化 AHB 句柄 */
    slot->ahb = import_ahb( conn );
    if (!slot->ahb)
    {
        LOGE( "desktop slot %u import failed", meta->slot );
        return;
    }
    LOGI( "desktop slot %u %ux%u stride %u imported",
          meta->slot, meta->width, meta->height, meta->stride );
}

static void handle_desktop_free( uint32_t slot_idx )
{
    if (slot_idx >= DAC_DESKTOP_MAX_SLOTS) return;
    release_ahb( g_slots[slot_idx].ahb );
    memset( &g_slots[slot_idx], 0, sizeof(g_slots[slot_idx]) );
}

static void handle_desktop_present( int conn, const struct dac_present *present )
{
    struct dac_rect dmg;
    struct desktop_slot *slot;

    if (present->slot >= DAC_DESKTOP_MAX_SLOTS) return;
    if (present->damage_count && read_full( conn, &dmg, sizeof(dmg) ))
        return;

    slot = &g_slots[present->slot];
    if (!slot->ahb) return;

    if (g_backend == DAC_BACKEND_SF_DIRECT)
        attach_desktop_buffer_tracked( present->slot, -1,
                                       dmg.left, dmg.top, dmg.right, dmg.bottom );
    else if (g_backend == DAC_BACKEND_AHB_CANVAS)
        present_desktop_canvas( slot, dmg.left, dmg.top, dmg.right, dmg.bottom );
    else
    {
        /* NONE：无呈现面（纯后台运行） */
    }
}

static struct vk_surface *find_or_create_surface( uint64_t id )
{
    int i;
    for (i = 0; i < 4; i++)
        if (g_surfaces[i].id == id) return &g_surfaces[i];
    for (i = 0; i < 4; i++)
        if (!g_surfaces[i].id)
        {
            memset( &g_surfaces[i], 0, sizeof(g_surfaces[i]) );
            g_surfaces[i].id = id;
            if (g_backend == DAC_BACKEND_SF_DIRECT && g_root_sc)
            {
                char name[32];
                snprintf( name, sizeof(name), "linbox-vk-%llu", (unsigned long long)id );
                g_surfaces[i].sc = ASurfaceControl_create( g_root_sc, name );
            }
            return &g_surfaces[i];
        }
    return NULL;
}

static void handle_vk_surface_new( const struct dac_vk_surface_new *msg )
{
    struct vk_surface *sf = find_or_create_surface( msg->id );
    if (!sf) return;
    sf->image_count = msg->image_count;
    sf->width  = msg->width;
    sf->height = msg->height;
    sf->format = msg->format;
    sf->is_dmabuf = msg->is_dmabuf;
    sf->z = 10;   /* DXVK 层默认在桌面层之上 */
    LOGI( "vk surface %llu %ux%u images %u dmabuf %d",
          (unsigned long long)msg->id, msg->width, msg->height,
          msg->image_count, msg->is_dmabuf );
}

static void handle_vk_image( const struct dac_buffer_meta *meta,
                             int *frame_fds, int frame_fd_count )
{
    /* surface id：协议顺序保证 VK_SURFACE_NEW 先行；取最近注册者 */
    int i;
    struct vk_surface *sf = NULL;
    for (i = 3; i >= 0; i--)
        if (g_surfaces[i].id && g_surfaces[i].image_count) { sf = &g_surfaces[i]; break; }
    if (!sf || meta->slot >= sf->image_count) return;

    if (sf->is_dmabuf)
    {
        /* dmabuf：fd 附着在本帧（主循环 recvmsg 已捕获） */
        if (frame_fd_count > 0 && g_backend == DAC_BACKEND_DMABUF_EGL)
        {
            /* 保存 fd 到 images 位（复用 AHardwareBuffer* 槽位存 int 句柄） */
            sf->images[meta->slot] = (AHardwareBuffer *)(intptr_t)frame_fds[0];
            sf->strides[meta->slot] = meta->stride;
            /* fd 所有权移交 images 槽位：置 -1 防主循环误关 */
            int fi;
            for (fi = 1; fi < frame_fd_count; fi++) close( frame_fds[fi] );
            for (fi = 0; fi < frame_fd_count; fi++) frame_fds[fi] = -1;
        }
        return;
    }

    sf->images[meta->slot] = import_ahb( g_conn_fd );
}

static void handle_vk_present( const struct dac_present *present,
                               const int *frame_fds, int frame_fd_count )
{
    int i;
    struct vk_surface *sf = NULL;
    int fence_fd = -1;

    for (i = 0; i < 4; i++)
        if (g_surfaces[i].id == present->surface_id) { sf = &g_surfaces[i]; break; }
    if (!sf) return;

    /* acquire fence 附着在本帧（主循环 recvmsg 已捕获）；接管所有权 */
    if (frame_fd_count > 0)
    {
        fence_fd = frame_fds[0];
        for (i = 1; i < frame_fd_count; i++) close( frame_fds[i] );
        ((int *)frame_fds)[0] = -1;   /* 所有权转移，防主循环误关 */
    }

    if (sf->is_dmabuf)
    {
        int fd = (int)(intptr_t)sf->images[present->slot];
        if (fd >= 0)
            blit_dmabuf( fd, sf->width, sf->height,
                         sf->strides[present->slot] ? sf->strides[present->slot]
                                                    : sf->width,
                         sf->format );
        send_vk_released( sf->id, present->slot, fence_fd );  /* blit 后立即可复用 */
        return;
    }

    if (!sf->images[present->slot])
    {
        if (fence_fd >= 0) close( fence_fd );
        return;
    }

    if (g_backend == DAC_BACKEND_SF_DIRECT)
        attach_vk_buffer_tracked( sf, present->slot, fence_fd );
        /* fence 所有权已移交 ASurfaceTransaction */
    else
    {
        if (fence_fd >= 0) close( fence_fd );
        /* AHB_CANVAS：DXVK 在低版本 API 不可直合，直接回执释放 */
        send_vk_released( sf->id, present->slot, -1 );
    }
}

static void handle_vk_layer_pos( const struct dac_vk_layer_pos *pos )
{
    int i;
    for (i = 0; i < 4; i++)
        if (g_surfaces[i].id == pos->id)
        {
            g_surfaces[i].x = pos->x;
            g_surfaces[i].y = pos->y;
            g_surfaces[i].w = pos->width;
            g_surfaces[i].h = pos->height;
            g_surfaces[i].z = pos->z;
            return;
        }
}

static void handle_vk_surface_del( uint64_t id )
{
    int i, j;
    for (i = 0; i < 4; i++)
        if (g_surfaces[i].id == id)
        {
            for (j = 0; j < 8; j++)
                if (!g_surfaces[i].is_dmabuf) release_ahb( g_surfaces[i].images[j] );
                else close( (int)(intptr_t)g_surfaces[i].images[j] );
            if (g_surfaces[i].sc) ASurfaceControl_release( g_surfaces[i].sc );
            memset( &g_surfaces[i], 0, sizeof(g_surfaces[i]) );
            return;
        }
}

/* 窗口标题包（wine → app）：hwnd + UTF-8 文本（缓冲 512B） */
struct dac_title_payload { uint64_t hwnd; char text[512]; };

static void handle_title( JNIEnv *env, const void *payload, uint32_t len )
{
    struct dac_title_payload *tp = (struct dac_title_payload *)payload;
    if (!g_title_sink || len < 8 || !env) return;
    {
        /* C++ 编译：JNIEnv 为结构体，方法走 env->Xxx（C 版 (*env)->Xxx 不可用） */
        jstring str = env->NewStringUTF( tp->text );
        if (str)
        {
            jclass cls = env->GetObjectClass( g_title_sink );
            jmethodID mid = env->GetMethodID( cls, "onTitle", "(Ljava/lang/String;)V" );
            if (mid) env->CallVoidMethod( g_title_sink, mid, str );
            env->DeleteLocalRef( str );
            env->DeleteLocalRef( cls );
        }
    }
}

static JNIEnv *attach_current_env( JNIEnv *env_holder )
{
    JNIEnv *env = NULL;
    if (g_vm && !env_holder)
        g_vm->AttachCurrentThread( &env, NULL );
    return env;
}

static void close_connection(void)
{
    int i, j;

    pthread_mutex_lock( &g_send_lock );
    if (g_conn_fd >= 0)
    {
        close( g_conn_fd );
        g_conn_fd = -1;
    }
    pthread_mutex_unlock( &g_send_lock );

    pthread_mutex_lock( &g_state_lock );
    for (i = 0; i < DAC_DESKTOP_MAX_SLOTS; i++)
    {
        release_ahb( g_slots[i].ahb );
        memset( &g_slots[i], 0, sizeof(g_slots[i]) );
    }
    for (i = 0; i < 4; i++)
    {
        if (g_surfaces[i].id)
        {
            for (j = 0; j < 8; j++)
                if (g_surfaces[i].images[j])
                {
                    if (g_surfaces[i].is_dmabuf)
                        close( (int)(intptr_t)g_surfaces[i].images[j] );
                    else
                        release_ahb( g_surfaces[i].images[j] );
                }
            if (g_surfaces[i].sc) ASurfaceControl_release( g_surfaces[i].sc );
            memset( &g_surfaces[i], 0, sizeof(g_surfaces[i]) );
        }
    }
    pthread_mutex_unlock( &g_state_lock );
    LOGI( "wine connection closed" );
}

/* 读一帧；SCM_RIGHTS fd 捕获到 frame_fds（调用方负责关闭） */
static int read_frame( int conn, struct dac_frame_hdr *hdr, void *buf, uint32_t buf_size,
                       int *frame_fds, int *frame_fd_count )
{
    struct msghdr msg;
    struct iovec iov;
    char cbuf[CMSG_SPACE( sizeof(int) * 8 )];
    struct cmsghdr *cmsg;
    ssize_t n;
    int nfds = 0;

    iov.iov_base = hdr;
    iov.iov_len  = sizeof(*hdr);
    memset( &msg, 0, sizeof(msg) );
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);
    do { n = recvmsg( conn, &msg, 0 ); } while (n < 0 && errno == EINTR);
    if (n != (ssize_t)sizeof(*hdr)) return -1;
    if (hdr->magic != DAC_PROTOCOL_MAGIC) return -1;
    if (hdr->len > buf_size) return -1;
    if (hdr->len && read_full( conn, buf, hdr->len )) return -1;

    for (cmsg = CMSG_FIRSTHDR( &msg ); cmsg; cmsg = CMSG_NXTHDR( &msg, cmsg ))
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS)
        {
            int cnt = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            if (cnt > 8) cnt = 8;
            memcpy( frame_fds, CMSG_DATA( cmsg ), sizeof(int) * cnt );
            nfds = cnt;
        }
    *frame_fd_count = nfds;
    return 0;
}

static void *connection_thread( void *arg )
{
    int conn = (int)(intptr_t)arg;
    unsigned char buf[2048];
    struct dac_frame_hdr hdr;
    JNIEnv *env = attach_current_env( NULL );

    g_conn_fd = conn;
    LOGI( "wine connected" );

    for (;;)
    {
        int frame_fds[8], frame_fd_count = 0;

        if (read_frame( conn, &hdr, buf, sizeof(buf), frame_fds, &frame_fd_count ))
            break;

        switch (hdr.type)
        {
        case DAC_MSG_HELLO:
            handle_hello( conn, (const struct dac_hello *)buf );
            break;
        case DAC_MSG_DESKTOP_ALLOC:
            /* 帧后跟 AHB 序列化句柄（recvHandle 内联消费，协议顺序敏感） */
            handle_desktop_alloc( conn, (const struct dac_buffer_meta *)buf );
            break;
        case DAC_MSG_DESKTOP_FREE:
            handle_desktop_free( *(uint32_t *)buf );
            break;
        case DAC_MSG_DESKTOP_PRESENT:
            handle_desktop_present( conn, (const struct dac_present *)buf );
            break;
        case DAC_MSG_VK_SURFACE_NEW:
            handle_vk_surface_new( (const struct dac_vk_surface_new *)buf );
            break;
        case DAC_MSG_VK_IMAGE:
            handle_vk_image( (const struct dac_buffer_meta *)buf,
                             frame_fds, frame_fd_count );
            break;
        case DAC_MSG_VK_SURFACE_DEL:
            handle_vk_surface_del( *(uint64_t *)buf );
            break;
        case DAC_MSG_VK_PRESENT:
            handle_vk_present( (const struct dac_present *)buf, frame_fds, frame_fd_count );
            break;
        case DAC_MSG_VK_LAYER_POS:
            handle_vk_layer_pos( (const struct dac_vk_layer_pos *)buf );
            break;
        case DAC_MSG_CURSOR:
            break;   /* v1：系统指针复用 LinBox 虚拟鼠标 */
        case DAC_MSG_TITLE:
            handle_title( env, buf, hdr.len );
            break;
        case DAC_MSG_PING:
        case DAC_MSG_LOG:
            break;
        default:
            break;
        }
        {
            int fi;
            for (fi = 0; fi < frame_fd_count; fi++)
                if (frame_fds[fi] >= 0) close( frame_fds[fi] );
        }
    }

    close_connection();
    return NULL;
}

static void *accept_thread( void *arg )
{
    for (;;)
    {
        int conn = accept( g_listen_fd, NULL, NULL );
        if (conn < 0)
        {
            if (errno == EINTR) continue;
            if (errno == EBADF || errno == EINVAL) break;
            continue;
        }
        fcntl( conn, F_SETFD, FD_CLOEXEC );
        close_connection();   /* 单连接：新连接替换旧连接 */
        {
            pthread_t tid;
            if (!pthread_create( &tid, NULL, connection_thread, (void *)(intptr_t)conn ))
                pthread_detach( tid );
            else
                close( conn );
        }
    }
    return NULL;
}

/* =====================================================================
 * JNI 入口
 * =================================================================== */
static int start_listen( const char *path )
{
    struct sockaddr_un addr;
    int fd;

    unlink( path );
    fd = socket( AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0 );
    if (fd < 0) return -1;
    memset( &addr, 0, sizeof(addr) );
    addr.sun_family = AF_UNIX;
    strncpy( addr.sun_path, path, sizeof(addr.sun_path) - 1 );
    if (bind( fd, (struct sockaddr *)&addr, sizeof(addr) ) ||
        listen( fd, 2 ))
    {
        close( fd );
        return -1;
    }
    chmod( path, 0666 );   /* 同 UID 直连；socket 位于 app 数据目录内 */
    return fd;
}

extern "C" jint Java_com_linbox_apps_dac_DacNative_nativeConnect( JNIEnv *env, jclass clazz,
                                                       jobject surface, jstring socket_path )
{
    const char *path = env->GetStringUTFChars( socket_path, NULL );

    if (!p_recv_handle)
    {
        void *lib = dlopen( "libandroid.so", RTLD_NOW );
        if (lib) p_recv_handle = (int (*)( int, AHardwareBuffer ** ))(uintptr_t)dlsym( lib, "AHardwareBuffer_recvHandleFromUnixSocket" );
        if (!p_recv_handle)
        {
            LOGE( "AHardwareBuffer_recvHandleFromUnixSocket unavailable" );
            env->ReleaseStringUTFChars( socket_path, path );
            return DAC_BACKEND_NONE;
        }
    }

    g_api_level = android_get_device_api_level();

    g_anw = ANativeWindow_fromSurface( env, surface );
    if (!g_anw)
    {
        env->ReleaseStringUTFChars( socket_path, path );
        return DAC_BACKEND_NONE;
    }

    /* 后端选择 */
    if (g_api_level >= 29)
    {
        g_root_sc = ASurfaceControl_createFromWindow( g_anw, "linbox-dac-root" );
        g_backend = g_root_sc ? DAC_BACKEND_SF_DIRECT : DAC_BACKEND_AHB_CANVAS;
    }
    else
        g_backend = g_api_level >= 26 ? DAC_BACKEND_AHB_CANVAS : DAC_BACKEND_NONE;

    if (g_backend == DAC_BACKEND_NONE)
    {
        LOGE( "device API %d below 26, DAC unsupported", g_api_level );
    }

    g_listen_fd = start_listen( path );
    env->ReleaseStringUTFChars( socket_path, path );
    if (g_listen_fd < 0)
    {
        LOGE( "listen failed: %s", strerror( errno ) );
        return DAC_BACKEND_NONE;
    }

    if (!pthread_create( &g_accept_thread, NULL, accept_thread, NULL ))
        pthread_detach( g_accept_thread );

    LOGI( "DAC bridge listening, backend=%d api=%d", g_backend, g_api_level );
    return g_backend;
}

extern "C" void Java_com_linbox_apps_dac_DacNative_nativeDisconnect( JNIEnv *env, jclass clazz )
{
    close_connection();
    if (g_listen_fd >= 0)
    {
        close( g_listen_fd );
        g_listen_fd = -1;
    }
    if (g_root_sc)
    {
        ASurfaceControl_release( g_root_sc );
        g_root_sc = NULL;
    }
    if (g_anw)
    {
        ANativeWindow_release( g_anw );
        g_anw = NULL;
    }
    g_backend = DAC_BACKEND_NONE;
}

extern "C" void Java_com_linbox_apps_dac_DacNative_nativeSetTitleSink( JNIEnv *env, jclass clazz,
                                                            jobject sink )
{
    if (g_title_sink)
    {
        env->DeleteGlobalRef( g_title_sink );
        g_title_sink = NULL;
    }
    if (sink)
    {
        jclass cls = env->GetObjectClass( sink );
        g_title_mid = env->GetMethodID( cls, "onTitle", "(Ljava/lang/String;)V" );
        env->DeleteLocalRef( cls );
        g_title_sink = env->NewGlobalRef( sink );
    }
}

jint JNI_OnLoad( JavaVM *vm, void *reserved )
{
    g_vm = vm;
    return JNI_VERSION_1_6;
}
