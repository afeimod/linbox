/*
 * winedac.drv — AHardwareBuffer 平台适配层
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 双后端设计（用户要求两种 Wine 形态都兼容）：
 *
 * [BIONIC]  Wine 针对 Bionic 构建（Mobox 风格 termux 原生）：
 *           dlopen libandroid.so，直接调用 AHardwareBuffer_* / fence API。
 *           特征检测：编译期 __ANDROID_API__ >= 26 或运行时 dlopen 成功。
 *
 * [SIDECAR] Wine 针对 glibc 构建（glibc-runner/grun/proot 运行）：
 *           无法加载 bionic 库 → 由 bionic 侧车进程 dac_allocd（随 APK 分发，
 *           经 NDK 编译的可执行）代为分配 AHardwareBuffer：
 *             - 侧车把 AHB 序列化句柄发回（bridge 用 recvHandle 导入合成）
 *             - 同时单独传 raw fd + mmap 偏移（wine 侧 CPU 写入桌面像素）
 *
 * 所有 bionic 符号一律 dlsym 解析，绝不静态链接，保证两种形态二进制兼容。
 */

#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS
#include "windef.h"
#include "winbase.h"

#include "dacdrv.h"
#include "wine/debug.h"

WINE_DEFAULT_DEBUG_CHANNEL(dac);

/* =====================================================================
 * bionic AHardwareBuffer 接口（自绘声明，避免依赖 NDK 头）
 * ===================================================================== */
struct AHardwareBuffer_Desc
{
    uint32_t width;
    uint32_t height;
    uint32_t layers;
    uint32_t format;
    uint64_t usage;
    uint32_t stride;
    uint32_t rfu0;
    uint64_t rfu1;
};

/* libandroid.so / libnativewindow.so 函数指针 */
static int  (*pAHardwareBuffer_allocate)( const struct AHardwareBuffer_Desc *desc,
                                          void **outBuffer );
static void (*pAHardwareBuffer_release)( void *buffer );
static void (*pAHardwareBuffer_describe)( const void *buffer,
                                          struct AHardwareBuffer_Desc *outDesc );
static int  (*pAHardwareBuffer_lock)( void *buffer, uint64_t usage, int fence,
                                      const struct dac_rect *rect, void **outVirtual );
static int  (*pAHardwareBuffer_unlock)( void *buffer, int *fence );
static int  (*pAHardwareBuffer_sendHandleToUnixSocket)( const void *buffer, int socket_fd );

static void *ahb_lib;
static enum dac_ahb_mode platform_mode = DAC_AHB_MODE_DMABUF;
static int sidecar_sock = -1;

enum dac_ahb_mode dac_platform_mode(void)
{
    return platform_mode;
}

/* ---------------------------------------------------------------------
 * BIONIC 后端
 * ------------------------------------------------------------------- */
static BOOL bionic_init(void)
{
    const char *libs[] = { "libandroid.so", "libnativewindow.so", NULL };
    int i;

    for (i = 0; libs[i]; i++)
    {
        ahb_lib = dlopen( libs[i], RTLD_NOW | RTLD_LOCAL );
        if (ahb_lib) break;
    }
    if (!ahb_lib)
    {
        TRACE( "no bionic AHB library available\n" );
        return FALSE;
    }

#define SYM(f) \
    do { p##f = dlsym( ahb_lib, #f ); if (!p##f) goto fail; } while (0)
    SYM(AHardwareBuffer_allocate);
    SYM(AHardwareBuffer_release);
    SYM(AHardwareBuffer_describe);
    SYM(AHardwareBuffer_lock);
    SYM(AHardwareBuffer_unlock);
    SYM(AHardwareBuffer_sendHandleToUnixSocket);
#undef SYM

    TRACE( "bionic AHB backend ready (%s)\n", libs[i] );
    return TRUE;

fail:
    TRACE( "%s missing symbols, not bionic?\n", libs[i] );
    dlclose( ahb_lib );
    ahb_lib = NULL;
    return FALSE;
}

/* ---------------------------------------------------------------------
 * SIDECAR 后端（dac_allocd 协议）
 *   请求帧: [u32 cmd][u32 w][u32 h][u32 fmt][u64 usage]
 *   应答帧: [u32 ok][struct AHardwareBuffer_Desc] + SCM_RIGHTS(raw fd)
 *   之后紧接一帧 sendHandle 序列化 AHB（直接转发给 bridge）
 *   cmd: 1=alloc 2=ping
 * ------------------------------------------------------------------- */
#define ALLOCD_CMD_ALLOC 1
#define ALLOCD_CMD_PING  2

static int sidecar_connect(void)
{
    struct sockaddr_un addr;
    const char *tmpdir = getenv( "TMPDIR" );
    char path[512];

    if (sidecar_sock >= 0) return 0;
    if (!tmpdir || !*tmpdir) tmpdir = "/tmp";

    memset( &addr, 0, sizeof(addr) );
    addr.sun_family = AF_UNIX;
    snprintf( path, sizeof(path), "%s/%s", tmpdir, DAC_ALLOCD_SOCKET_NAME );
    snprintf( addr.sun_path, sizeof(addr.sun_path), "%s", path );

    sidecar_sock = socket( AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0 );
    if (sidecar_sock < 0) return -1;
    if (connect( sidecar_sock, (struct sockaddr *)&addr, sizeof(addr) ))
    {
        TRACE( "sidecar %s not running: %s\n", path, strerror(errno) );
        close( sidecar_sock );
        sidecar_sock = -1;
        return -1;
    }
    TRACE( "connected to dac_allocd at %s\n", path );
    return 0;
}

static int read_full( int fd, void *buf, size_t len )
{
    unsigned char *p = buf;
    size_t off = 0;
    while (off < len)
    {
        ssize_t n = recv( fd, p + off, len - off, 0 );
        if (n <= 0)
        {
            if (n < 0 && errno == EINTR) continue;
            return -1;
        }
        off += n;
    }
    return 0;
}

int dac_platform_init( void )
{
    if (bionic_init())
    {
        platform_mode = DAC_AHB_MODE_BIONIC;
        return 0;
    }
    if (!sidecar_connect())
    {
        platform_mode = DAC_AHB_MODE_SIDECAR;
        return 0;
    }
    WARN( "no AHB backend: bionic unavailable, sidecar not running\n" );
    platform_mode = DAC_AHB_MODE_DMABUF;
    return -1;
}

static int sidecar_alloc( uint32_t w, uint32_t h, uint32_t format, uint64_t usage,
                          struct dac_ahb *out )
{
    uint32_t req[5] = { ALLOCD_CMD_ALLOC, w, h, format, 0 };
    struct { uint32_t ok; struct AHardwareBuffer_Desc desc; } resp;
    struct msghdr msg;
    struct iovec iov;
    char cbuf[CMSG_SPACE( sizeof(int) * 4 )];
    struct cmsghdr *cmsg;
    int raw_fd = -1;
    uint64_t usage_hi = usage;

    if (sidecar_connect()) return -1;

    memcpy( &req[4], &usage_hi, sizeof(uint64_t) );
    iov.iov_base = req;
    iov.iov_len  = sizeof(req);
    memset( &msg, 0, sizeof(msg) );
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);

    if (sendmsg( sidecar_sock, &msg, MSG_NOSIGNAL ) != (ssize_t)sizeof(req)) return -1;

    memset( cbuf, 0, sizeof(cbuf) );
    iov.iov_base = &resp;
    iov.iov_len  = sizeof(resp);
    memset( &msg, 0, sizeof(msg) );
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);
    if (recvmsg( sidecar_sock, &msg, 0 ) != (ssize_t)sizeof(resp) || !resp.ok) return -1;

    /* sidecar 紧接着转发 AHardwareBuffer 序列化句柄（原样字节 + SCM_RIGHTS），
     * wine 存下来，dac_platform_send_ahb 时转发给 bridge */
    {
        unsigned char hbuf[512];
        struct msghdr hmsg;
        struct iovec hiov;
        char hcbuf[CMSG_SPACE( sizeof(int) * DAC_MAX_DMABUF_PLANES + 8 )];
        struct cmsghdr *hcmsg;
        int hfds[DAC_MAX_DMABUF_PLANES], hn = 0;
        ssize_t hnread;

        memset( hcbuf, 0, sizeof(hcbuf) );
        hiov.iov_base = hbuf;
        hiov.iov_len  = sizeof(hbuf);
        memset( &hmsg, 0, sizeof(hmsg) );
        hmsg.msg_iov = &hiov;
        hmsg.msg_iovlen = 1;
        hmsg.msg_control = hcbuf;
        hmsg.msg_controllen = sizeof(hcbuf);
        do { hnread = recvmsg( sidecar_sock, &hmsg, 0 ); } while (hnread < 0 && errno == EINTR);
        if (hnread <= 0) { close( raw_fd ); return -1; }
        for (hcmsg = CMSG_FIRSTHDR( &hmsg ); hcmsg; hcmsg = CMSG_NXTHDR( &hmsg, hcmsg ))
        {
            if (hcmsg->cmsg_level == SOL_SOCKET && hcmsg->cmsg_type == SCM_RIGHTS)
            {
                int cnt = (hcmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
                if (cnt > DAC_MAX_DMABUF_PLANES) cnt = DAC_MAX_DMABUF_PLANES;
                memcpy( hfds, CMSG_DATA( hcmsg ), sizeof(int) * cnt );
                hn = cnt;
            }
        }
        out->pending_len = hnread;
        memcpy( out->pending_handle, hbuf, hnread );
        out->pending_fd_count = hn;
        memcpy( out->pending_fds, hfds, sizeof(int) * hn );
    }

    for (cmsg = CMSG_FIRSTHDR( &msg ); cmsg; cmsg = CMSG_NXTHDR( &msg, cmsg ))
    {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS)
        {
            int *f = (int *)CMSG_DATA( cmsg );
            raw_fd = f[0];   /* 后续 fd（如有）由 sidecare 只发 1 个 */
        }
    }
    if (raw_fd < 0) return -1;

    memset( out, 0, sizeof(*out) );
    out->pending_len = 0;
    out->pending_fd_count = 0;
    out->ahb    = NULL;
    out->fd     = raw_fd;
    out->width  = resp.desc.width;
    out->height = resp.desc.height;
    out->stride = resp.desc.stride ? resp.desc.stride : resp.desc.width;
    out->format = resp.desc.format;
    out->usage  = resp.desc.usage;
    return 0;
}

BOOL dac_platform_alloc_ahb( uint32_t w, uint32_t h, uint32_t format,
                             uint64_t usage, struct dac_ahb *out )
{
    memset( out, 0, sizeof(*out) );
    out->fd = -1;

    if (platform_mode == DAC_AHB_MODE_BIONIC)
    {
        struct AHardwareBuffer_Desc desc;
        void *ahb = NULL;

        memset( &desc, 0, sizeof(desc) );
        desc.width  = w;
        desc.height = h;
        desc.layers = 1;
        desc.format = format;
        desc.usage  = usage;

        if (pAHardwareBuffer_allocate( &desc, &ahb )) return FALSE;
        pAHardwareBuffer_describe( ahb, &desc );

        out->ahb    = ahb;
        out->fd     = -1;
        out->width  = desc.width;
        out->height = desc.height;
        out->stride = desc.stride ? desc.stride : desc.width;
        out->format = desc.format;
        out->usage  = desc.usage;
        return TRUE;
    }

    if (platform_mode == DAC_AHB_MODE_SIDECAR)
        return sidecar_alloc( w, h, format, usage, out ) == 0;

    return FALSE;
}

void dac_platform_free_ahb( struct dac_ahb *buf )
{
    if (!buf) return;
    dac_platform_unmap_ahb( buf );
    if (buf->ahb) pAHardwareBuffer_release( buf->ahb );
    if (buf->fd >= 0) close( buf->fd );
    memset( buf, 0, sizeof(*buf) );
    buf->fd = -1;
}

/* 把 AHB 原生句柄序列化到 bridge 连接。
 * 协议约定：必须在 DESKTOP_ALLOC / VK_IMAGE 帧发出后立即调用，
 * bridge 收到帧后第一时间 recvHandle 导入（导入完成前不得读后续帧）。
 * 返回 0 成功。 */
int dac_platform_send_ahb( const struct dac_ahb *buf )
{
    if (!buf) return -1;

    if (platform_mode == DAC_AHB_MODE_BIONIC && buf->ahb)
        return pAHardwareBuffer_sendHandleToUnixSocket( buf->ahb, dac_sock_fd() );

    if (platform_mode == DAC_AHB_MODE_SIDECAR && buf->pending_len)
    {
        /* 原样代理：payload 字节 + SCM_RIGHTS fd 组（接收端 recvHandle 解析） */
        struct msghdr msg;
        struct iovec iov;
        char cbuf[CMSG_SPACE( sizeof(int) * DAC_MAX_DMABUF_PLANES + 8 )];
        struct cmsghdr *cmsg;
        int ret;
        int fds[DAC_MAX_DMABUF_PLANES];
        int i, n = buf->pending_fd_count;

        for (i = 0; i < n; i++) fds[i] = buf->pending_fds[i];

        iov.iov_base = (void *)buf->pending_handle;
        iov.iov_len  = buf->pending_len;
        memset( &msg, 0, sizeof(msg) );
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        if (n > 0)
        {
            memset( cbuf, 0, sizeof(cbuf) );
            msg.msg_control = cbuf;
            msg.msg_controllen = CMSG_SPACE( sizeof(int) * n );
            cmsg = CMSG_FIRSTHDR( &msg );
            cmsg->cmsg_level = SOL_SOCKET;
            cmsg->cmsg_type  = SCM_RIGHTS;
            cmsg->cmsg_len   = CMSG_LEN( sizeof(int) * n );
            memcpy( CMSG_DATA( cmsg ), fds, sizeof(int) * n );
        }
        do { ret = sendmsg( dac_sock_fd(), &msg, MSG_NOSIGNAL );
        } while (ret < 0 && errno == EINTR);

        /* fd 已随 sendmsg 复制进内核，关闭本地副本 */
        for (i = 0; i < n; i++) if (buf->pending_fds[i] >= 0) close( buf->pending_fds[i] );
        return (ret == (ssize_t)buf->pending_len) ? 0 : -1;
    }

    return -1;
}

void *dac_platform_map_ahb( struct dac_ahb *buf, BOOL write )
{
    if (!buf) return NULL;
    if (buf->map) return buf->map;

    if (platform_mode == DAC_AHB_MODE_BIONIC && buf->ahb)
    {
        void *virt = NULL;
        uint64_t usage = write ? DAC_AHB_USAGE_CPU_WRITE_OFTEN
                               : DAC_AHB_USAGE_CPU_READ_OFTEN;
        if (!pAHardwareBuffer_lock( buf->ahb, usage, -1, NULL, &virt ))
            buf->map = virt;
        return buf->map;
    }

    /* SIDECAR / DMABUF: raw dmabuf mmap */
    if (buf->fd >= 0)
    {
        size_t size = (size_t)buf->stride * 4 * buf->height;
        void *virt = mmap( NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, buf->fd, 0 );
        if (virt == MAP_FAILED) return NULL;
        buf->map = virt;
    }
    return buf->map;
}

void dac_platform_unmap_ahb( struct dac_ahb *buf )
{
    if (!buf || !buf->map) return;

    if (platform_mode == DAC_AHB_MODE_BIONIC && buf->ahb)
        pAHardwareBuffer_unlock( buf->ahb, NULL );
    else if (buf->fd >= 0)
        munmap( buf->map, (size_t)buf->stride * 4 * buf->height );

    buf->map = NULL;
}

/* dmabuf 模式：vulkan.c 把 VK 导出的 dmabuf fd 包装进来（仅描述用） */
int dac_platform_import_dmabuf( int fd, uint32_t w, uint32_t h,
                                uint32_t stride_px, struct dac_ahb *out )
{
    memset( out, 0, sizeof(*out) );
    out->ahb    = NULL;
    out->fd     = dup( fd );
    out->width  = w;
    out->height = h;
    out->stride = stride_px;
    out->format = DAC_AHB_FMT_RGBA8888;
    out->usage  = 0;
    return 0;
}
