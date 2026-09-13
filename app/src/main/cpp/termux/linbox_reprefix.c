/*
 * linbox_reprefix.c — LinBox Termux 移植：包安装后前缀重写工具
 *
 * 背景：
 *   LinBox 的 applicationId 为 com.linbox，与官方 Termux 的 com.termux
 *   恰好同为 10 字节。官方仓库（packages.termux.org 等）的 .deb 全部按
 *   /data/data/com.termux 前缀构建：ELF 的 .rodata、脚本 shebang、
 *   intent 组件名等把 "com.termux" 焊死在文件内容里。
 *
 * 原理：
 *   等长字节替换 com.termux → com.linbox 不改变文件长度、不移动任何
 *   ELF 段偏移，对文本与二进制都安全——等效于"按 com.linbox 前缀重新
 *   编译"了该包。
 *
 * 触发时机（由 dpkg 包装器 / apt DPkg::Post-Invoke 调用）：
 *   扫描 $PREFIX/var/lib/dpkg/info 目录中比 stamp 更新的 .list 清单，
 *   对清单里的每个文件执行等长重写；同时重写清单文件自身、
 *   符号链接目标与 dpkg status 描述文本。
 *
 * 用法：
 *   linbox-reprefix [--full] [--quiet]
 *     --full   忽略 stamp，处理全部清单（首次部署 / 存量迁移用）
 *     --quiet  静默（钩子模式），仅出错时输出
 *   linbox-reprefix --tree <dir>
 *     树模式：等长改写整棵目录树（目录名/文件内容/链接目标），
 *     向 stdout 输出改动总数；改名失败时 stderr 报错并以 1 退出
 *     （v5：不再静默——改名失败=官方前缀目录留在树里=dpkg
 *     unable to stat 的直接病灶，必须让调用方看到并走降级路径）。
 *   linbox-reprefix --verify <dir>
 *     复检模式（v5）：独立统计树中残留的 com.termux 出现处
 *     （目录/文件名 + 链接目标 + 普通文件内容），stdout 输出纯数字。
 *     供 linbox-debfix 在重写完成后复核，把引擎的任何静默失效
 *     （段错误被吞/部分失败/只读文件跳过）变成可见诊断。
 *   linbox-reprefix --version
 *     版本指纹（v5）：设备端一键核验部署状态。
 *
 * 退出码：0 正常（包括无可改内容）；1 内部错误（不阻塞 dpkg 结果）。
 *
 * v5（fix8.3）要点：
 *   1) patch_file：tar 保留的只读权限（0444/0555，如 man 页/部分
 *      配置）曾使 open(O_RDWR) EACCES、内容改写被【静默跳过】——
 *      现临时加写位重试，改写完成后恢复原权限；
 *   2) walk_tree：目录改名失败不再静默吞掉，置错误位并由 --tree
 *      以非零退出；
 *   3) 新增 --verify 复检与 --version 指纹。
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <limits.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>

/* 编译期可覆盖前缀（仅本地宿主机测试用；Android 构建使用默认值） */
#ifndef LINBOX_PREFIX
#define LINBOX_PREFIX "/data/data/com.linbox/files/usr"
#endif

#define PREFIX       LINBOX_PREFIX
#define DPKG_INFO    PREFIX "/var/lib/dpkg/info"
#define DPKG_STATUS  PREFIX "/var/lib/dpkg/status"
#define STAMP_DIR    PREFIX "/var/lib/linbox"
#define STAMP_FILE   STAMP_DIR "/reprefix.stamp"

static const char OLD_PKG[] = "com.termux"; /* 10 字节 */
static const char NEW_PKG[] = "com.linbox"; /* 10 字节（等长） */
#define PKG_LEN 10

static size_t g_files_patched = 0;
static size_t g_occurrences   = 0;
static size_t g_symlinks_patched = 0;
static size_t g_tree_errors   = 0;   /* v5：树模式中失败的改名数 */
static size_t g_verify_hits   = 0;   /* v5：verify 模式的残留计数 */

/* ------------------------------------------------------------------
 * 内存区域内的等长替换
 * ---------------------------------------------------------------- */
static size_t replace_in(char *buf, size_t len)
{
    size_t count = 0;
    if (len < PKG_LEN) return 0;
    const size_t last = len - PKG_LEN;
    size_t i = 0;
    while (i <= last) {
        if (buf[i] == 'c' && memcmp(buf + i, OLD_PKG, PKG_LEN) == 0) {
            memcpy(buf + i, NEW_PKG, PKG_LEN);
            count++;
            /* NEW_PKG 不包含 OLD_PKG，可安全跳过整段 */
            i += PKG_LEN;
        } else {
            i++;
        }
    }
    return count;
}

/* ------------------------------------------------------------------
 * 单个普通文件的就地重写（mmap，零拷贝，不改变文件大小）
 * ---------------------------------------------------------------- */
static void patch_file(const char *path)
{
    struct stat st;
    if (stat(path, &st) != 0) return;            /* 条目不存在（目录占位等） */
    if (!S_ISREG(st.st_mode)) return;
    if (st.st_size < (off_t)PKG_LEN) return;

    int fd = open(path, O_RDWR | O_CLOEXEC);
    int restore_mode = 0;
    if (fd < 0 && errno == EACCES) {
        /* v5：只读权限文件（0444/0555）曾在此被【静默跳过】，内容
         * 里的 com.termux 残留（脚本 shebang 路径等）。属主对无写位
         * 的自有文件同样 open 失败——临时加写位重试，完成后还原。 */
        if (chmod(path, (st.st_mode & 07777) | S_IWUSR) == 0) {
            fd = open(path, O_RDWR | O_CLOEXEC);
            restore_mode = 1;
        }
    }
    if (fd < 0) return;                          /* 仍无权限——跳过 */

    void *map = mmap(NULL, (size_t)st.st_size, PROT_READ | PROT_WRITE,
                     MAP_SHARED, fd, 0);
    close(fd);
    if (map == MAP_FAILED) {
        if (restore_mode) chmod(path, st.st_mode & 07777);
        return;
    }

    size_t n = replace_in((char *)map, (size_t)st.st_size);
    if (n > 0) {
        msync(map, (size_t)st.st_size, MS_SYNC);
        g_files_patched++;
        g_occurrences += n;
    }
    munmap(map, (size_t)st.st_size);
    if (restore_mode) chmod(path, st.st_mode & 07777);
}

/* ------------------------------------------------------------------
 * 只读计数（--verify 用）：统计内存区域内 com.termux 出现次数，
 * 不改动内容。与 replace_in 逻辑一致但 const 安全。
 * ---------------------------------------------------------------- */
static size_t count_in(const char *buf, size_t len)
{
    size_t count = 0;
    if (len < PKG_LEN) return 0;
    const size_t last = len - PKG_LEN;
    size_t i = 0;
    while (i <= last) {
        if (buf[i] == 'c' && memcmp(buf + i, OLD_PKG, PKG_LEN) == 0) {
            count++;
            i += PKG_LEN;
        } else {
            i++;
        }
    }
    return count;
}

/* ------------------------------------------------------------------
 * 绝对路径符号链接目标重写：
 *   deb 里可能出现 target 含 com.termux 的绝对链接，
 *   先重写目标再原子替换链接本身
 * ---------------------------------------------------------------- */
static void patch_symlink(const char *path)
{
    char target[PATH_MAX];
    ssize_t tl = readlink(path, target, sizeof(target) - 1);
    if (tl <= 0) return;
    target[tl] = '\0';

    /* 目标里是否含有旧包名 */
    int has_old = 0;
    for (ssize_t i = 0; i + PKG_LEN <= tl; i++) {
        if (target[i] == 'c' && memcmp(target + i, OLD_PKG, PKG_LEN) == 0) {
            has_old = 1;
            break;
        }
    }
    if (!has_old) return;

    replace_in(target, (size_t)tl);

    char tmp[PATH_MAX];
    if (snprintf(tmp, sizeof(tmp), "%s.linboxtmp", path) >= (int)sizeof(tmp)) return;
    unlink(tmp);
    if (symlink(target, tmp) != 0) return;
    if (rename(tmp, path) != 0) {
        unlink(tmp);
        return;
    }
    g_symlinks_patched++;
}

/* ------------------------------------------------------------------
 * 处理一份 dpkg 清单（*.list）：逐行解析路径并重写
 * ---------------------------------------------------------------- */
static void process_list(const char *list_path)
{
    FILE *f = fopen(list_path, "rb");
    if (!f) return;

    char *line = NULL;
    size_t cap = 0;
    ssize_t n;
    while ((n = getline(&line, &cap, f)) > 0) {
        /* 去掉行尾换行 */
        while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = '\0';

        /* 路径本身可能含 com.termux（dpkg 记录的虚拟绝对路径）——先归一 */
        replace_in(line, (size_t)n);
        const char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        if (*p == '\0') continue;

        char full[PATH_MAX];
        if (p[0] == '/') {
            snprintf(full, sizeof(full), "%s", p);
        } else {
            snprintf(full, sizeof(full), PREFIX "/%s", p);
        }

        struct stat st;
        if (lstat(full, &st) == 0 && S_ISLNK(st.st_mode)) {
            patch_symlink(full);     /* 链接本体 */
        } else {
            patch_file(full);        /* 普通文件 */
        }
    }
    free(line);
    fclose(f);
}

/* ------------------------------------------------------------------
 * 树模式（--tree <dir>）：递归处理目录树
 *   - 目录名含 com.termux → 等长重命名（先递归后改名）
 *   - 普通文件 → 内容等长重写
 *   - 符号链接 → 目标重写
 * 用于 linbox-debfix 重打包官方 deb（tar 成员路径与文件内容一次改净）。
 * 向 stdout 输出改动总数（文件+目录+链接），供脚本判断是否需要重建。
 * ---------------------------------------------------------------- */
static size_t g_tree_changes = 0;

static void patch_path_object(const char *full); /* fwd */

static void walk_tree(const char *dir)
{
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        char full[PATH_MAX];
        snprintf(full, sizeof(full), "%s/%s", dir, e->d_name);
        patch_path_object(full);
    }
    closedir(d);

    /* 子项处理完后，改写本层目录名中可能存在的 com.termux */
    const char *base = strrchr(dir, '/');
    base = base ? base + 1 : dir;
    if (strstr(base, OLD_PKG) != NULL) {
        char name[NAME_MAX + 1];
        snprintf(name, sizeof(name), "%s", base);
        replace_in(name, strlen(name));
        char parent[PATH_MAX];
        snprintf(parent, sizeof(parent), "%.*s", (int)(base - dir), dir);
        char renamed[PATH_MAX];
        snprintf(renamed, sizeof(renamed), "%s%s", parent, name);
        if (rename(dir, renamed) == 0) {
            g_tree_changes++;
        } else {
            /* v5：改名失败不再静默。目标已存在（上次中断残留）或
             * 权限异常都会让官方前缀目录原样留在树里——正是 dpkg
             * "unable to stat" 的直接病灶。报错并置失败位，--tree
             * 以非零退出，调用方（linbox-debfix）据此走降级路径。 */
            fprintf(stderr, "linbox-reprefix: 错误: 改名 %s -> %s: %s\n",
                    dir, renamed, strerror(errno));
            g_tree_errors++;
        }
    }
}

/* ------------------------------------------------------------------
 * 复检（--verify <dir>）：递归统计树中残留的 com.termux 出现处
 * （目录/文件名 + 链接目标 + 普通文件内容）
 * ---------------------------------------------------------------- */
static void verify_path(const char *full)
{
    struct stat st;
    if (lstat(full, &st) != 0) return;
    if (S_ISDIR(st.st_mode)) {
        const char *base = strrchr(full, '/');
        base = base ? base + 1 : full;
        if (strstr(base, OLD_PKG) != NULL) g_verify_hits++;
        DIR *d = opendir(full);
        if (!d) return;
        struct dirent *e;
        while ((e = readdir(d)) != NULL) {
            if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
            char sub[PATH_MAX];
            snprintf(sub, sizeof(sub), "%s/%s", full, e->d_name);
            verify_path(sub);
        }
        closedir(d);
    } else if (S_ISLNK(st.st_mode)) {
        char target[PATH_MAX];
        ssize_t tl = readlink(full, target, sizeof(target) - 1);
        if (tl <= 0) return;
        target[tl] = '\0';
        for (ssize_t i = 0; i + PKG_LEN <= tl; i++) {
            if (target[i] == 'c' && memcmp(target + i, OLD_PKG, PKG_LEN) == 0) {
                g_verify_hits++;
                break;
            }
        }
    } else if (S_ISREG(st.st_mode)) {
        const char *base = strrchr(full, '/');
        base = base ? base + 1 : full;
        if (strstr(base, OLD_PKG) != NULL) g_verify_hits++;
        if (st.st_size < (off_t)PKG_LEN) return;
        int fd = open(full, O_RDONLY | O_CLOEXEC);
        if (fd < 0) return;
        void *map = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (map == MAP_FAILED) return;
        g_verify_hits += count_in((const char *)map, (size_t)st.st_size);
        munmap(map, (size_t)st.st_size);
    }
}

static void patch_path_object(const char *full)
{
    struct stat st;
    if (lstat(full, &st) != 0) return;
    if (S_ISDIR(st.st_mode)) {
        walk_tree(full);
    } else if (S_ISLNK(st.st_mode)) {
        char target[PATH_MAX];
        ssize_t tl = readlink(full, target, sizeof(target) - 1);
        if (tl <= 0) return;
        target[tl] = '\0';
        if (strstr(target, OLD_PKG) == NULL) return;
        replace_in(target, (size_t)tl);
        char tmp[PATH_MAX + 16];
        snprintf(tmp, sizeof(tmp), "%s.linboxtmp", full);
        unlink(tmp);
        if (symlink(target, tmp) == 0 && rename(tmp, full) == 0) {
            g_tree_changes++;
        } else {
            unlink(tmp);
        }
    } else if (S_ISREG(st.st_mode)) {
        size_t before = g_occurrences;
        patch_file(full);
        if (g_occurrences > before) g_tree_changes++;
    }
}

static void update_stamp(void)
{
    mkdir(STAMP_DIR, 0700);
    int fd = open(STAMP_FILE, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd >= 0) {
        ssize_t w = write(fd, "ok\n", 3);
        (void)w;
        close(fd);
    }
}

int main(int argc, char **argv)
{
    int quiet = 0, full = 0, show_version = 0;
    const char *tree_root = NULL;
    const char *verify_root = NULL;
    const char *single_file = NULL;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--quiet") == 0) quiet = 1;
        else if (strcmp(argv[i], "--full") == 0) full = 1;
        else if (strcmp(argv[i], "--tree") == 0 && i + 1 < argc) tree_root = argv[++i];
        else if (strcmp(argv[i], "--verify") == 0 && i + 1 < argc) verify_root = argv[++i];
        else if (strcmp(argv[i], "--file") == 0 && i + 1 < argc) single_file = argv[++i];
        else if (strcmp(argv[i], "--version") == 0) show_version = 1;
    }

    /* 版本指纹（v5）：设备端一键核验部署状态 */
    if (show_version) {
        printf("linbox-reprefix v5.2 (fix8.5, EXTRAS_REVISION 41)\n");
        return 0;
    }

    /* 复检模式（v5）：输出残留计数（纯数字），供 debfix 独立复核 */
    if (verify_root != NULL) {
        struct stat st;
        if (lstat(verify_root, &st) != 0 || !S_ISDIR(st.st_mode)) {
            fprintf(stderr, "linbox-reprefix: --verify 需要一个存在的目录\n");
            return 1;
        }
        verify_path(verify_root);
        printf("%zu\n", g_verify_hits);
        return 0;
    }

    /* 单文件模式：显式补丁一个文件（如迁移期的 dpkg.real，不在任何 .list 里） */
    if (single_file != NULL) {
        patch_file(single_file);
        if (!quiet)
            fprintf(stderr, "linbox-reprefix: %s: %zu file(s) patched\n", single_file, g_files_patched);
        return 0;
    }

    /* 树模式：供 linbox-debfix 重打包官方 deb 用，与 stamp/list 逻辑无关 */
    if (tree_root != NULL) {
        struct stat st;
        if (lstat(tree_root, &st) != 0 || !S_ISDIR(st.st_mode)) {
            fprintf(stderr, "linbox-reprefix: --tree 需要一个存在的目录\n");
            return 1;
        }
        walk_tree(tree_root);
        printf("%zu\n", g_tree_changes);
        if (g_tree_errors > 0) {
            /* v5：任何改名失败都让 --tree 非零退出——调用方必须知道
             * 树未改净（否则官方前缀成员会原样进 deb，dpkg 必然报
             * unable to stat），并走目录改名降级路径补救。 */
            fprintf(stderr, "linbox-reprefix: %zu 处改名失败，树未改净\n",
                    g_tree_errors);
            return 1;
        }
        return 0;
    }

    /* stamp 状态：不存在视为首次运行 → 全量 */
    struct stamp_stat { time_t mtime; } stamp = { 0 };
    struct stat st;
    int have_stamp = (stat(STAMP_FILE, &st) == 0);
    if (have_stamp) stamp.mtime = st.st_mtime;
    if (!have_stamp) full = 1;

    DIR *d = opendir(DPKG_INFO);
    if (!d) {
        if (!quiet)
            fprintf(stderr, "linbox-reprefix: dpkg 数据库不可用: %s\n", strerror(errno));
        return 0;   /* 没有数据库不算失败（bootstrap 未装等） */
    }

    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        size_t l = strlen(e->d_name);
        if (l < 6 || strcmp(e->d_name + l - 5, ".list") != 0) continue;

        char path[PATH_MAX];
        snprintf(path, sizeof(path), DPKG_INFO "/%s", e->d_name);

        if (!full) {
            struct stat lst;
            if (stat(path, &lst) != 0) continue;
            /* 清单不比 stamp 新 → 自上次以来无该包变动 */
            if (lst.st_mtime < stamp.mtime) continue;
        }

        process_list(path);
        patch_file(path);   /* 清单自身可能含 com.termux 路径 */
    }
    closedir(d);

    patch_file(DPKG_STATUS);
    update_stamp();

    if (!quiet) {
        fprintf(stderr, "linbox-reprefix: 重写 %zu 个文件 / %zu 处, 符号链接 %zu 个\n",
                g_files_patched, g_occurrences, g_symlinks_patched);
    }
    return 0;
}
