/*
 * winedac.drv — 与 LinBox app 内 bridge 的 socket 协议层
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 连接：$PREFIX/tmp/linbox-dac.sock（app 侧监听；同 UID，无需特权）
 * 帧：[u32 magic][u32 type][u32 len][payload]；fd 以 SCM_RIGHTS 附于帧。
 * 事件与发送分离：发送锁保护整帧 sendmsg；接收仅由事件线程完成。
 */

#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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

static int dac_sock = -1;
static pthread_mutex_t send_mutex = PTHREAD_MUTEX_INITIALIZER;
static char dac_exe_name[256];

int dac_connected(void)
{
    return dac_sock >= 0;
}

int dac_sock_fd(void)
{
    return dac_sock;
}

static int send_fds( int sock, const void *buf, size_t len, const int *fds, int fd_count )
{
    struct msghdr msg;
    struct iovec iov;
    char cbuf[CMSG_SPACE( sizeof(int) * DAC_MAX_DMABUF_PLANES + 8 )];
    ssize_t n;

    iov.iov_base = (void *)buf;
    iov.iov_len  = len;

    memset( &msg, 0, sizeof(msg) );
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    if (fds && fd_count > 0 && fd_count <= DAC_MAX_DMABUF_PLANES)
    {
        struct cmsghdr *cmsg;
        memset( cbuf, 0, sizeof(cbuf) );
        msg.msg_control = cbuf;
        msg.msg_controllen = CMSG_SPACE( sizeof(int) * fd_count );
        cmsg = CMSG_FIRSTHDR( &msg );
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type  = SCM_RIGHTS;
        cmsg->cmsg_len   = CMSG_LEN( sizeof(int) * fd_count );
        memcpy( CMSG_DATA( cmsg ), fds, sizeof(int) * fd_count );
    }

    do { n = sendmsg( sock, &msg, MSG_NOSIGNAL ); } while (n < 0 && errno == EINTR);
    return (n == (ssize_t)len) ? 0 : -1;
}

static int write_all( int sock, const void *buf, size_t len )
{
    const unsigned char *p = buf;
    size_t off = 0;
    while (off < len)
    {
        ssize_t n = send( sock, p + off, len - off, MSG_NOSIGNAL );
        if (n <= 0)
        {
            if (n < 0 && errno == EINTR) continue;
            return -1;
        }
        off += n;
    }
    return 0;
}

int dac_send_msg( uint32_t type, const void *payload, uint32_t len,
                  const int *fds, int fd_count )
{
    struct dac_frame_hdr hdr;
    int ret = -1;

    if (dac_sock < 0) return -1;

    hdr.magic = DAC_PROTOCOL_MAGIC;
    hdr.type  = type;
    hdr.len   = len;

    pthread_mutex_lock( &send_mutex );
    if (fds && fd_count > 0)
        ret = send_fds( dac_sock, &hdr, sizeof(hdr), fds, fd_count );
    else
        ret = write_all( dac_sock, &hdr, sizeof(hdr) );
    if (!ret && len) ret = write_all( dac_sock, payload, len );
    pthread_mutex_unlock( &send_mutex );

    if (ret) WARN( "send msg %u failed: %s\n", type, strerror(errno) );
    return ret;
}

static int recv_all( int sock, void *buf, size_t len )
{
    unsigned char *p = buf;
    size_t off = 0;
    while (off < len)
    {
        ssize_t n = recv( sock, p + off, len - off, 0 );
        if (n <= 0)
        {
            if (n < 0 && errno == EINTR) continue;
            return -1;
        }
        off += n;
    }
    return 0;
}

/* 接收整帧；fds 出参由调用方负责关闭 */
int dac_recv_msg( uint32_t *type, void *buf, uint32_t buf_size,
                  uint32_t *len_out, int *fds, int *fd_count )
{
    struct dac_frame_hdr hdr;
    struct msghdr msg;
    struct iovec iov;
    char cbuf[CMSG_SPACE( sizeof(int) * DAC_MAX_DMABUF_PLANES + 8 )];
    struct cmsghdr *cmsg;
    ssize_t n;
    int got_fds = 0;

    iov.iov_base = &hdr;
    iov.iov_len  = sizeof(hdr);
    memset( &msg, 0, sizeof(msg) );
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);

    do { n = recvmsg( dac_sock, &msg, 0 ); } while (n < 0 && errno == EINTR);
    if (n != (ssize_t)sizeof(hdr)) return -1;
    if (hdr.magic != DAC_PROTOCOL_MAGIC) return -1;

    for (cmsg = CMSG_FIRSTHDR( &msg ); cmsg; cmsg = CMSG_NXTHDR( &msg, cmsg ))
    {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS)
        {
            int count = (cmsg->cmsg_len - CMSG_LEN( 0 )) / sizeof(int);
            if (count > DAC_MAX_DMABUF_PLANES) count = DAC_MAX_DMABUF_PLANES;
            memcpy( fds, CMSG_DATA( cmsg ), sizeof(int) * count );
            got_fds = count;
        }
    }

    if (hdr.len > buf_size)
    {
        /* 太大的帧丢弃 payload 但保持流同步 */
        char tmp[4096];
        uint32_t left = hdr.len;
        while (left)
        {
            uint32_t chunk = min( left, sizeof(tmp) );
            if (recv_all( dac_sock, tmp, chunk )) break;
            left -= chunk;
        }
        return -1;
    }
    if (hdr.len && recv_all( dac_sock, buf, hdr.len )) return -1;

    *type = hdr.type;
    *len_out = hdr.len;
    *fd_count = got_fds;
    return 0;
}

/* 带超时的事件读取（事件线程用） */
int dac_read_event( uint32_t *type, void *buf, uint32_t buf_size,
                    uint32_t *len_out, int *fds, int *fd_count,
                    int timeout_ms )
{
    struct pollfd pfd;

    if (dac_sock < 0) return -1;
    pfd.fd = dac_sock;
    pfd.events = POLLIN;
    if (poll( &pfd, 1, timeout_ms ) <= 0) return 1;   /* 超时/无事件 */

    return dac_recv_msg( type, buf, buf_size, len_out, fds, fd_count );
}

void dac_close_recv_fds( int *fds, int n )
{
    int i;
    for (i = 0; i < n; i++)
        if (fds[i] >= 0) { close( fds[i] ); fds[i] = -1; }
}

void dac_disconnect(void)
{
    pthread_mutex_lock( &send_mutex );
    if (dac_sock >= 0)
    {
        close( dac_sock );
        dac_sock = -1;
    }
    pthread_mutex_unlock( &send_mutex );
}

/*
 * 连接 bridge 并完成握手。成功返回 0。
 * 桌面槽位由调用方在握手后按需 DESKTOP_ALLOC。
 */
int dac_connect( const char *exe_name )
{
    struct sockaddr_un addr;
    struct dac_hello hello = {0};
    struct dac_frame_hdr hdr;
    unsigned char ack[512];
    uint32_t type, len;
    int fds[4], fd_count = 0;
    const char *tmpdir = getenv( "TMPDIR" );
    char path[512];

    if (dac_sock >= 0) return 0;
    if (!tmpdir || !*tmpdir) tmpdir = "/tmp";

    memset( &addr, 0, sizeof(addr) );
    addr.sun_family = AF_UNIX;
    snprintf( path, sizeof(path), "%s/%s", tmpdir, DAC_SOCKET_NAME );
    snprintf( addr.sun_path, sizeof(addr.sun_path), "%s", path );

    dac_sock = socket( AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0 );
    if (dac_sock < 0)
    {
        ERR( "socket failed: %s\n", strerror(errno) );
        return -1;
    }

    if (connect( dac_sock, (struct sockaddr *)&addr, sizeof(addr) ))
    {
        ERR( "connect %s failed: %s（LinBox 尚未开启 DAC 显示窗口？）\n",
             path, strerror(errno) );
        close( dac_sock );
        dac_sock = -1;
        return -1;
    }

    if (exe_name)
        snprintf( dac_exe_name, sizeof(dac_exe_name), "%s", exe_name );
    TRACE( "connected to bridge at %s\n", path );

    /* 桌面尺寸由 window.c 初始化前注入 dac_desktop；这里只发 HELLO */
    pthread_mutex_lock( &send_mutex );
    {
        struct dac_desktop *dt = &dac_desktop;
        hello.proto_version  = DAC_PROTOCOL_VERSION;
        hello.desktop_width  = dt->width;
        hello.desktop_height = dt->height;
        hello.fd_count       = 0;
        if (exe_name) snprintf( hello.exe, sizeof(hello.exe), "%s", exe_name );

        hdr.magic = DAC_PROTOCOL_MAGIC;
        hdr.type  = DAC_MSG_HELLO;
        hdr.len   = sizeof(hello);
        if (write_all( dac_sock, &hdr, sizeof(hdr) ) ||
            write_all( dac_sock, &hello, sizeof(hello) ))
        {
            pthread_mutex_unlock( &send_mutex );
            goto fail;
        }
    }
    pthread_mutex_unlock( &send_mutex );

    /* 等 HELLO_ACK（最多 5s） */
    {
        int i;
        for (i = 0; i < 50; i++)
        {
            if (dac_read_event( &type, ack, sizeof(ack), &len, fds, &fd_count, 100 ))
            {
                if (!dac_connected()) goto fail;
                continue;
            }
            dac_close_recv_fds( fds, fd_count );
            if (type == DAC_MSG_HELLO_ACK)
            {
                struct dac_hello_ack *a = (struct dac_hello_ack *)ack;
                if (len < sizeof(*a) || !a->ok) goto fail;
                TRACE( "bridge backend=%u api=%u\n", a->backend, a->api_level );
                return 0;
            }
        }
    }
    ERR( "bridge handshake timeout\n" );

fail:
    dac_disconnect();
    return -1;
}
