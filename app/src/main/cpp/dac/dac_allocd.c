/*
 * dac_allocd — bionic 侧车守护进程（glibc Wine 的 AHardwareBuffer 代理）
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 用途：Wine 以 glibc 构建（glibc-runner/grun 运行）时无法加载 bionic 库，
 * 由本进程（bionic ELF，随 APK 分发、安装期拷入 $PREFIX/bin）代为：
 *   1. 调 AHardwareBuffer_allocate 分配 gralloc 缓冲
 *   2. raw fd（SCM_RIGHTS）→ 请求方（wine 侧 mmap 写桌面像素）
 *   3. AHardwareBuffer_sendHandleToUnixSocket 序列化句柄 → 请求方转发给 bridge
 *
 * 协议（$PREFIX/tmp/linbox-dac-allocd.sock）：
 *   请求 [u32 cmd=1][u32 w][u32 h][u32 fmt][u64 usage]
 *   应答 [u32 ok][struct AHardwareBuffer_Desc(40B)] + cmsg(raw fd)
 *   紧接 [sendHandleToUnixSocket 原样字节 + cmsg]（代理转发）
 *   cmd=2 ping → 应答 ok=1
 *
 * 构建：NDK 独立编译为可执行，伪装 libdac_allocd.so 入 APK（同
 * linbox-reprefix 方案）；TermuxBootstrapInstaller 拷入 $PREFIX/bin 后，
 * 由 linbox-dac 脚本在 wine 启动前拉起：
 *   /system/bin/linker64 $PREFIX/bin/dac_allocd  (直接 bionic 执行)
 * 或作为普通 ELF exec（termux 链接器可直接跑 bionic 动态可执行）。
 */

#include <android/hardware_buffer.h>
#include <dlfcn.h>

/* native_handle 最小前向声明（避免私有头 cutils/native_handle.h） */
struct native_handle
{
    int version;
    int numFds;
    int numInts;
    int data[];
};

/* 隐藏但稳定存在的符号（libnativewindow，API 29+） */
__attribute__((weak))
const struct native_handle *AHardwareBuffer_getNativeHandle( const AHardwareBuffer *buffer );
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <signal.h>

#define ALLOCD_CMD_ALLOC 1
#define ALLOCD_CMD_PING  2
#define DAC_ALLOCD_SOCKET_NAME "linbox-dac-allocd.sock"

static int g_listen_fd = -1;

static int write_all( int fd, const void *buf, size_t len )
{
    const unsigned char *p = buf;
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

static void forward_serialized_handle( int client, const AHardwareBuffer *ahb )
{
    /* AHardwareBuffer_sendHandleToUnixSocket 直接向 client socket 发送
     * 序列化句柄（payload + SCM_RIGHTS），接收端原样转发即可。 */
    AHardwareBuffer_sendHandleToUnixSocket( ahb, client );
}

static void handle_client( int client )
{
    for (;;)
    {
        struct
        {
            uint32_t cmd;
            uint32_t w, h, fmt;
            uint64_t usage;
        } req;
        ssize_t n = recv( client, &req, sizeof(req), 0 );
        if (n != (ssize_t)sizeof(req)) break;

        if (req.cmd == ALLOCD_CMD_PING)
        {
            uint32_t ok = 1;
            if (write_all( client, &ok, sizeof(ok) )) break;
            continue;
        }

        if (req.cmd != ALLOCD_CMD_ALLOC) break;

        {
            AHardwareBuffer_Desc desc;
            AHardwareBuffer *ahb = NULL;
            memset( &desc, 0, sizeof(desc) );
            desc.width  = req.w;
            desc.height = req.h;
            desc.layers = 1;
            desc.format = req.fmt;
            desc.usage  = req.usage;

            struct
            {
                uint32_t ok;
                AHardwareBuffer_Desc desc;
            } resp;
            memset( &resp, 0, sizeof(resp) );

            if (AHardwareBuffer_allocate( &desc, &ahb ) == 0)
            {
                resp.ok = 1;
                AHardwareBuffer_describe( ahb, &resp.desc );
            }

            /* 应答 + raw fd（第一个 fd） */
            if (resp.ok)
            {
                /* 取 raw fd：native_handle 首个 fd。走 AHardwareBuffer NDK
                 * 无直接 fd 访问 API —— 但 gralloc 的 AHardwareBuffer 可经
                 * AHardwareBuffer_lock(CPU) 写；为让 wine 直接 mmap，
                 * 这里改用 lock/copy 接口不现实（跨进程指针无效）。
                 * 实现方案：AHardwareBuffer 的 native_handle 经
                 * AHardwareBuffer_sendHandleToUnixSocket 发给 wine？
                 * wine 无法解析。因此 raw fd 用下述私有头获取： */
                int raw_fd = -1;
                if (AHardwareBuffer_getNativeHandle)
                {
                    const struct native_handle *nh = AHardwareBuffer_getNativeHandle( ahb );
                    if (nh && nh->numFds > 0) raw_fd = nh->data[0];
                }

                struct msghdr msg;
                struct iovec iov;
                char cbuf[CMSG_SPACE( sizeof(int) )];
                struct cmsghdr *cmsg;

                iov.iov_base = &resp;
                iov.iov_len  = sizeof(resp);
                memset( &msg, 0, sizeof(msg) );
                msg.msg_iov = &iov;
                msg.msg_iovlen = 1;
                msg.msg_control = cbuf;
                msg.msg_controllen = sizeof(cbuf);
                cmsg = CMSG_FIRSTHDR( &msg );
                cmsg->cmsg_level = SOL_SOCKET;
                cmsg->cmsg_type  = SCM_RIGHTS;
                cmsg->cmsg_len   = CMSG_LEN( sizeof(int) );
                int fd_out = raw_fd;
                memcpy( CMSG_DATA( cmsg ), &fd_out, sizeof(int) );
                if (sendmsg( client, &msg, MSG_NOSIGNAL ) != (ssize_t)sizeof(resp))
                    break;
                if (raw_fd < 0) break;   /* getNativeHandle 不可用 */

                /* 紧接序列化句柄（供 wine 代理转发给 bridge） */
                forward_serialized_handle( client, ahb );
                AHardwareBuffer_release( ahb );   /* fd/handle 已发出，本端不再持有 */
            }
            else
            {
                if (write_all( client, &resp, sizeof(resp) )) break;
            }
        }
    }
    close( client );
}

static void *client_thread( void *arg )
{
    int client = (int)(intptr_t)arg;
    handle_client( client );
    return NULL;
}

int main( int argc, char **argv )
{
    struct sockaddr_un addr;
    const char *tmpdir = getenv( "TMPDIR" );
    char path[512];

    signal( SIGPIPE, SIG_IGN );

    if (!tmpdir || !*tmpdir) tmpdir = "/tmp";
    snprintf( path, sizeof(path), "%s/%s", tmpdir, DAC_ALLOCD_SOCKET_NAME );

    unlink( path );
    g_listen_fd = socket( AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0 );
    if (g_listen_fd < 0)
    {
        perror( "socket" );
        return 1;
    }
    memset( &addr, 0, sizeof(addr) );
    addr.sun_family = AF_UNIX;
    strncpy( addr.sun_path, path, sizeof(addr.sun_path) - 1 );
    if (bind( g_listen_fd, (struct sockaddr *)&addr, sizeof(addr) ) ||
        listen( g_listen_fd, 4 ))
    {
        perror( "bind/listen" );
        return 1;
    }
    chmod( path, 0666 );

    fprintf( stderr, "[dac_allocd] listening on %s\n", path );

    for (;;)
    {
        int client = accept( g_listen_fd, NULL, NULL );
        if (client < 0)
        {
            if (errno == EINTR) continue;
            break;
        }
        fcntl( client, F_SETFD, FD_CLOEXEC );
        pthread_t tid;
        if (!pthread_create( &tid, NULL, client_thread, (void *)(intptr_t)client ))
            pthread_detach( tid );
        else
            close( client );
    }
    return 0;
}
