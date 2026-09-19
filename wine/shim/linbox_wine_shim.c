/* ============================================================================
 * linbox_wine_shim.c — LinBox-DAC 静态自举包装器（构建时生成 bin/<name>）
 * ============================================================================
 * 背景（v1.21.3 真机实测）：
 *   wine.real（box64 转译运行）初始化前缀时会用 posix_spawn 拉起
 *   bin/wineserver。此前 bin/wineserver 是 "#!/system/bin/sh" 的 shell
 *   自举 wrapper —— 内核经 shebang 调起 bionic 的 /system/bin/sh，而
 *   wine 链路的环境里带着 glibc 侧的 LD_LIBRARY_PATH（指向 sysroot-arm，
 *   其中 v1.21.2 起含有 soname=libc.so.6 的 arm64 glibc libc.so 副本）。
 *   bionic linker 为 sh 解析 DT_NEEDED "libc.so" 时命中该文件（soname
 *   不是 libc.so）→ verneed 校验失败：
 *     CANNOT LINK EXECUTABLE "/system/bin/sh": cannot find "libc.so"
 *       from verneed[0] in DT_NEEDED list for "/system/bin/sh"
 *   sh 秒退 → wine 的 start_server() waitpid 得非 0 → exit(status)，
 *   建前缀静默失败（无任何 wine 报错）。
 *
 * 方案：用「静态 arm64 原生 ELF」替代 shell wrapper —— 内核直接 exec，
 *   不经过任何动态链接器/解释器，对环境零敏感；wine 内部 spawn 与
 *   用户终端直接调用（wineserver -k 等）走同一条路径，行为一致。
 *   本包装器按自身文件名定位 <name>.real，再经私有 glibc loader +
 *   tarball 自带 box64 启动真正的 x86_64 二进制（与 shell wrapper 等价）。
 *
 * 构建（CI / x86_64 主机）：
 *   aarch64-linux-gnu-gcc -static -O2 -s -o bin/wineserver linbox_wine_shim.c
 *
 * ⚠ 静态链接 + 不使用 locale/NSS/dlopen —— 保证在安卓 App 域 seccomp
 *   下仅依赖 execve/readlink/stat/openat/getdents64 等白名单系统调用。
 * ==========================================================================*/
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <dirent.h>
#include <unistd.h>

static const char *SHIM_TAG = "[wine-dac]";

static void die(const char *fmt, const char *a1, const char *a2)
{
    fprintf(stderr, "%s ✗ ", SHIM_TAG);
    fprintf(stderr, fmt, a1 ? a1 : "", a2 ? a2 : "");
    fputc('\n', stderr);
    exit(1);
}

/* 取路径末段（不改写输入） */
static const char *path_tail(const char *p)
{
    const char *s = strrchr(p, '/');
    return s ? s + 1 : p;
}

/* dirname 到调用者缓冲（不改写输入） */
static void path_dir(const char *p, char *out, size_t outsz)
{
    const char *s = strrchr(p, '/');
    if (!s) {
        snprintf(out, outsz, ".");
        return;
    }
    if (s == p)
        snprintf(out, outsz, "/");
    else
        snprintf(out, outsz, "%.*s", (int)(s - p), p);
}

static int is_exec_file(const char *path)
{
    struct stat st;
    return access(path, X_OK) == 0 && stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

/* 复刻 shell wrapper 的 PREFIX 探测：/data/user/<uid>/com.linbox/files/usr 优先 */
static void detect_prefix(char *out, size_t outsz)
{
    const char *env = getenv("PREFIX");
    static const char *fixed[] = {
        "/data/data/com.linbox/files/usr",
        NULL
    };
    int i;
    DIR *d;
    struct dirent *e;
    char cand[PATH_MAX];

    if (env && env[0]) {
        snprintf(out, outsz, "%s", env);
        return;
    }
    d = opendir("/data/user");
    if (d) {
        while ((e = readdir(d))) {
            struct stat st;
            if (e->d_name[0] == '.') continue;
            snprintf(cand, sizeof(cand), "/data/user/%s/com.linbox/files/usr", e->d_name);
            if (stat(cand, &st) == 0 && S_ISDIR(st.st_mode)) {
                closedir(d);
                snprintf(out, outsz, "%s", cand);
                return;
            }
        }
        closedir(d);
    }
    for (i = 0; fixed[i]; i++) {
        struct stat st;
        if (stat(fixed[i], &st) == 0 && S_ISDIR(st.st_mode)) {
            snprintf(out, outsz, "%s", fixed[i]);
            return;
        }
    }
    snprintf(out, outsz, "/data/user/0/com.linbox/files/usr");
}

int main(int argc, char **argv)
{
    char self[PATH_MAX], dir[PATH_MAX + 64], root[PATH_MAX + 64];
    char real[PATH_MAX + 64], syslib[PATH_MAX + 64], sysarm[PATH_MAX + 64];
    char loader[PATH_MAX + 96], box64[PATH_MAX + 64], b64ld[PATH_MAX * 3 + 64];
    char prefix[PATH_MAX + 32], tmpdir[PATH_MAX + 32];
    char tun[PATH_MAX + 64];
    const char *name, *env;
    struct stat st;
    char **xargv;
    int i, n = 0;

    (void)argc;

    memset(self, 0, sizeof(self));
    if (readlink("/proc/self/exe", self, sizeof(self) - 1) <= 0)
        die("无法读取 /proc/self/exe（%s）", strerror(errno), NULL);

    name = path_tail(self);
    path_dir(self, dir, sizeof(dir));
    path_dir(dir, root, sizeof(root));

    snprintf(real, sizeof(real), "%s/%s.real", dir, name);
    snprintf(syslib, sizeof(syslib), "%s/sysroot/lib", root);
    snprintf(sysarm, sizeof(sysarm), "%s/sysroot-arm/lib", root);
    snprintf(box64, sizeof(box64), "%s/box64", dir);
    snprintf(loader, sizeof(loader), "%s/ld-linux-aarch64.so.1", sysarm);

    if (!is_exec_file(real))
        die("缺 %s（tarball 解压不完整？）", real, NULL);
    if (stat(syslib, &st) != 0 || !S_ISDIR(st.st_mode))
        die("缺 %s（tarball 解压不完整？）", syslib, NULL);

    /* ---- 环境自举（与 shell wrapper 的 CHECKS/B64 块等价） ---- */
    detect_prefix(prefix, sizeof(prefix));
    setenv("PREFIX", prefix, 0 /* 不覆盖外部 */);

    if (!getenv("TMPDIR")) {
        snprintf(tmpdir, sizeof(tmpdir), "%s/tmp", prefix);
        setenv("TMPDIR", tmpdir, 1);
        mkdir(tmpdir, 0777); /* 尽力而为 */
    }

    /* v1.18：默认禁 Wine Mono/Gecko 安装确认弹窗（防建前缀卡死） */
    env = getenv("WINEDLLOVERRIDES");
    if (!env || !env[0]) {
        const char *mono = getenv("LINBOX_DAC_MONO_PROMPT");
        if (!mono || strcmp(mono, "1") != 0)
            setenv("WINEDLLOVERRIDES", "mscoree,mshtml=", 1);
    }

    /* 访客侧（x86_64 glibc 闭包 + wine unix 库）搜索路径：box64 内部加载器专用。
     * 注意必须用 BOX64_LD_LIBRARY_PATH 而非原生 LD_LIBRARY_PATH（v1.12 教训） */
    env = getenv("BOX64_LD_LIBRARY_PATH");
    snprintf(b64ld, sizeof(b64ld),
             "%s/sysroot/lib:%s/lib/wine/x86_64-unix:%s/lib/wine%s%s",
             root, root, root, (env && env[0]) ? ":" : "", (env && env[0]) ? env : "");
    setenv("BOX64_LD_LIBRARY_PATH", b64ld, 1);

    /* 外部注入的 LD_PRELOAD 一律清空（原生依赖由私有 loader --library-path 提供） */
    unsetenv("LD_PRELOAD");

    /* v1.17：安卓 seccomp 双保险 —— 禁用 rseq 注册（vanilla glibc 兜底） */
    env = getenv("GLIBC_TUNABLES");
    snprintf(tun, sizeof(tun), "%s%sglibc.pthread.rseq=0",
             (env && env[0]) ? env : "", (env && env[0]) ? ":" : "");
    setenv("GLIBC_TUNABLES", tun, 1);

    /* ---- 选择 box64（优先 tarball 自带，其次 $PREFIX/bin，最后 termux） ---- */
    env = getenv("BOX64_BIN");
    if (env && env[0] && is_exec_file(env)) {
        snprintf(box64, sizeof(box64), "%s", env);
    } else if (is_exec_file(box64)) {
        ; /* tarball 自带（已在 box64 缓冲里） */
    } else {
        snprintf(box64, sizeof(box64), "%s/bin/box64", prefix);
        if (!is_exec_file(box64))
            snprintf(box64, sizeof(box64), "%s",
                     "/data/data/com.termux/files/usr/bin/box64");
    }
    if (!is_exec_file(box64))
        die("未找到 box64 —— x86_64 wine 必须经 box64 转译（tarball 自带 bin/box64，"
            "缺失说明解压不完整；也可自备放入 $PREFIX/bin 或设 BOX64_BIN）", NULL, NULL);

    /* ---- 组装 argv 并 exec（环境零污染、无解释器参与） ---- */
    xargv = (char **)calloc((size_t)argc + 8, sizeof(char *));
    if (!xargv)
        die("内存不足", NULL, NULL);

    if (is_exec_file(loader)) {
        /* 私有 loader 直启：--library-path 使 box64 自身 glibc 闭包与其
         * wrapped 库 dlopen 全部落在 sysroot-arm，与外部环境完全隔离 */
        xargv[n++] = loader;
        xargv[n++] = (char *)"--library-path";
        xargv[n++] = sysarm;
        xargv[n++] = box64;
    } else {
        xargv[n++] = box64;
    }
    xargv[n++] = real;
    for (i = 1; i < argc; i++)
        xargv[n++] = argv[i];
    xargv[n] = NULL;

    execv(xargv[0], xargv);
    die("exec %s 失败（%s）", xargv[0], strerror(errno));
    return 1;
}
