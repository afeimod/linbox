#!/usr/bin/env bash
# ============================================================
# build_wine_dac.sh — 构建 + 集成 winedac.drv 的 Wine
# ============================================================
# 基于 LinBox path/build_wine.sh（Kron4ek Wine-Builds 模板），追加：
#   1. 可选应用 LinBox path/ 现有补丁（esync / mfplat / termux-wine-fix）
#   2. 应用 wine/patches/0001-configure-ac-add-winedac.drv.patch
#   3. 把 wine/dlls/winedac.drv/ 整树拷入 wine 源码
#   4. 常规构建 + 打包 tarball
#
# 运行时目标说明：
#   LinBox 终端在 aarch64 设备上经 box64/grun 运行 **x86_64 glibc Linux**
#   版 wine（与官方 Kron4ek tarball 一致）。winedac.drv 的 AHB 分配在该
#   模式下走 dac_allocd 侧车（arm64 bionic，由 APK 安装到 $PREFIX/bin），
#   CPU 位图槽位经 fd mmap 直接写入 —— 因此默认构建 TARGET=x86_64-linux。
#
#   ⚠ TARGET=aarch64-glibc 产出的 arm64 本机 wine 经 glibc loader 即可
#     运行，但 wine 9.2 没有内置 x86 CPU 模拟（ntdll 无 box64/FEX 支持），
#     该目标无法运行 x86/x86_64 PE 程序（游戏/explorer/taskmgr 全是 x86）。
#     要在设备上跑 Windows 程序，请用默认 TARGET=x86_64-linux（box64
#     整进程转译）—— aarch64 目标仅供 ARM64 PE 特殊场景 / 未来接入
#     Hangover 类模拟层。
#
#   设备端自举（v1.11 → v1.12）：安卓上裸 glibc ELF 无法直接 exec ——
#     x86_64 构建报 "Exec format error"，aarch64 构建报
#     "required file not found"（缺 /lib/ld-linux-aarch64.so.1）。
#     因此 tarball 自带：① sysroot/lib（全部 unix ELF 的 ldd glibc 闭包，
#     含 ld-linux，Mobox 同款）；② bin/wine|wineserver|wineboot 为自举
#     wrapper（自动找 loader/box64 启动同名 .real 原生 ELF）。
#
#   v1.12 自带 box64（真机 "invalid ELF header" 修复）：APK 自带 box64
#     被 patchelf 固定解释器到 $PREFIX/glibc/lib/ld-linux-aarch64.so.1，
#     自身 libc 解析完全依赖 APK 的 glibc 目录 —— 该目录若含 ld 链接脚本
#     版 libc.so（文本文件），box64 启动即报
#       "box64: error while loading shared libraries:
#        /data/.../usr/glibc/lib/libc.so: invalid ELF header"
#     且外部 export LD_LIBRARY_PATH 指向 x86_64 库会二次污染其原生加载器。
#     故 x86_64 目标改为：CI 交叉构建 aarch64 box64 打进 tarball（bin/box64）
#     + 私有 aarch64 glibc 闭包（sysroot-arm/lib，按 DT_NEEDED 精确收集）
#     + 私有 loader 直启（ld-linux --library-path），全程不读 APK 的 glibc
#     目录、不依赖 PATH、原生 LD_LIBRARY_PATH 一律清空。
#
#   v1.17 安卓 seccomp 安全（真机 "Bad system call" 根因修复）：vanilla
#     glibc（noble 2.39）的 loader/libc 启动即调 statx / rseq / clone3，
#     安卓 App 域 seccomp 白名单不放行 → SIGSYS 击杀，wine 零输出瞬间死。
#     修法：sysroot-arm 改用 termux gpkg 的 "GNU libc for Android"
#     （Mobox / glibc-runner 同源，已补丁：disable-clone3 / fstatat 弃
#     statx / rseq 去注册 / faccessat2 降级 / fakesyscall 兜底层）。
#     CI 下载 gpkg glibc deb 解包后以 GARM=<lib目录> 传入本脚本；
#     未提供 GARM 时回退交叉 sysroot（本地快速打包场景），并在
#     wrapper 里加 GLIBC_TUNABLES rseq=0 双保险 + 启动自检探针。
#
#   v1.18 首次建前缀卡死修复（无显示器场景）：wineboot 初始化新前缀
#     时会弹 "Wine Mono Installer" / "Wine Gecko Installer" 确认框
#     等点击；此时 DAC 窗口往往尚未连接（无显示器），无人能点
#     "取消" → 前缀创建永久卡住（真机实测：wine explorer 卡死不动）。
#     修法（Lutris/PlayOnLinux 同款）：wrapper 默认导出
#       WINEDLLOVERRIDES="mscoree,mshtml="
#     禁用 mscoree/mshtml 内建覆盖，wine 直接按"已禁用"处理，不再弹框。
#     LINBOX_DAC_MONO_PROMPT=1 或自设 WINEDLLOVERRIDES 可恢复默认行为。
#
#   v1.21.2 wine 启动即 SIGSEGV 修复（真机 2026-09 实测）：box64 打印
#     "Error initializing native libpthread.so.0 (last dlerror is libc.so:
#     cannot open shared object file: Permission denied)" 后 pthread 符号
#     全部 404、ntdll.so 无法加载、段错误。根因：sysroot-arm/lib 按 box64
#     的 DT_NEEDED 收集是"浮动清单"—— glibc 2.34+ 链接器不再为 -lpthread
#     stub 产生 DT_NEEDED，libpthread.so.0 从未进 sysroot-arm；而 box64 的
#     全部 pthread_* 符号映射恰在 wrapped libpthread（wrapped libc 不提供），
#     其初始化 dlopen("libpthread.so.0") 落空 → ALTNAME dlopen("libc.so")
#     也落空 → 断链。修法：不依赖 DT_NEEDED，无条件收集 glibc 运行时
#     全家桶（GARM 优先）+ 放置 libc.so/libm.so 回退名副本（ALTNAME 防御）；
#     workflow 同步补上 v1.17 起就支持却从未接线的 GARM（安卓补丁版
#     gpkg glibc，规避 seccomp 击杀 vanilla glibc 的 clone3/statx/rseq）。
#
#   v1.21.3 wine 启动链与 wineserver 拉起修复（真机 2026-09 实测）：
#     ① "wine: could not exec the wine loader"（ntdll 的 __wine_main
#     fatal_error）。根因链：wine 9.2 --enable-archs=i386,x86_64 构建的
#     starter 名为 bin/wine、preloader 名为 bin/wine-preloader；
#     ntdll preloader_exec 按"loader basename 是否以 64 结尾"选 preloader
#     文件名，且 exec 前置逻辑依赖 /proc/self/exe（box64 下指向 box64 自身
#     所在目录）。tarball 把 starter 改名 wine.real 后：basename 不以 64
#     结尾 → ntdll 找 wine-preloader；而 box64 的 execve/execv 拦截只有
#     在目标路径以 "wine64-preloader" 结尾时才走"跳过 preloader 直跑
#     wine 本体"的官方通道 —— 两条命名约定错位，starter → preloader 的
#     exec 只能落到脆弱的"box64 直接执行 preloader"回退链，真机两跳全败。
#     修法：starter 原位改名为 bin/wine64（basename 以 64 结尾）——
#       ntdll 选 wine64-preloader；box64 命中 skip_first 通道，直接
#       re-exec `box64 bin/wine64 ...`；新进程 basename "wine64" 又触发
#       box64 的 "Wine64 detected"（地址空间 prereserve + wine 专项处理）；
#       WINELOADERNOEXEC=1 随环境继承，ntdll 不再二次 exec。wrapper 对外
#       仍叫 bin/wine（用户接口不变），内部 REAL=$DIR/wine64。
#     ② "posix_spawn(.../bin/wineserver) IsX64=0" → 落回安卓原生 spawn →
#     /system/bin/sh 被拉起即 "CANNOT LINK EXECUTABLE ... cannot find
#     libc.so from verneed[0]"（环境被访客侧库路径污染）。根因：wineserver
#     曾被包成 shell wrapper，box64 的 posix_spawn 拦截对脚本
#     FileIsX64ELF=0 不接管。修法：bin/wineserver 保持真实 x86_64 ELF
#     （ntdll exec_wineserver 固定 posix_spawn /proc/self/exe 同目录的
#     wineserver，与自带 box64 同在 bin/）→ IsX64=1 → box64 直接接管转译，
#     原生 sh 永不被拉起。wineboot 保留 wrapper（其内部 wineboot 是 PE，
#     经 NtCreateUserProcess，不走 unix posix_spawn）。
#     ③ preloader 双名兜底：bin/wine-preloader 与 bin/wine64-preloader
#     缺哪个补哪个（wine 9.2 双架构构建只装前者；纯 win64 构建只装后者），
#     保证 ntdll 无论按哪个名字查找都能命中。
#
# 用法（Ubuntu x86_64 主机 / GitHub Actions runner 均可）：
#   ./build_wine_dac.sh                              # 默认 x86_64-linux
#   TARGET=aarch64-glibc ./build_wine_dac.sh         # aarch64 交叉（grun arm64）
#   WINE_VERSION=9.2 WINE_BRANCH=vanilla ./build_wine_dac.sh
#   GARM=/path/to/gpkg-glibc-lib ./build_wine_dac.sh # 安卓补丁版 glibc（v1.17）
#
# 产物：$BUILD_DIR/dist/wine-dac-<ver>-<target>.tar.xz
# ============================================================
set -e

WINE_VERSION="${WINE_VERSION:-9.2}"
WINE_BRANCH="${WINE_BRANCH:-vanilla}"
TARGET="${TARGET:-x86_64-linux}"
APPLY_LINBOX_PATCHES="${APPLY_LINBOX_PATCHES:-0}"
BUILD_DIR="${BUILD_DIR:-$HOME/build_wine_dac}"
JOBS="${JOBS:-$(nproc)}"
GARM="${GARM:-}"   # v1.17：安卓补丁版 glibc 的 lib 目录（CI 传入）
SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"
LINBOX_ROOT="$(dirname "$SCRIPT_DIR")"            # 仓库根（含 path/）
DAC_SRC="$SCRIPT_DIR/dlls/winedac.drv"
DAC_PATCH="$SCRIPT_DIR/patches/0001-configure-ac-add-winedac.drv.patch"

echo ">> Wine $WINE_VERSION ($WINE_BRANCH) → TARGET=$TARGET"

# ---- 1. 下载/准备 wine 源码 ----
rm -rf "$BUILD_DIR/wine" "$BUILD_DIR/build" "$BUILD_DIR/build-aarch64"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

URL_VER="$(echo "$WINE_VERSION" | cut -d. -f1).x"
if [ ! -d wine ]; then
    wget -q --show-progress "https://dl.winehq.org/wine/source/${URL_VER}/wine-${WINE_VERSION}.tar.xz"
    tar xf "wine-${WINE_VERSION}.tar.xz"
    mv "wine-${WINE_VERSION}" wine
fi

# staging（可选）
if [ "$WINE_BRANCH" = "staging" ]; then
    wget -q "https://github.com/wine-staging/wine-staging/archive/v${WINE_VERSION}.tar.gz"
    tar xf "v${WINE_VERSION}.tar.gz"
    ( cd wine && ../wine-staging-"${WINE_VERSION}"/patches/patchinstall.sh DESTDIR="$(pwd)" --all )
fi

# ---- 2. 可选：LinBox path/ 现有补丁（esync、mfplat、termux-wine-fix…） ----
# 注意：path/ 下多为手工摘录的 diff 残片（esync.patch 等对 wine-9.2 干树
# 并不完整可应用），且用户原版 path/build_wine.sh 走 wine-staging 补丁、
# 从不使用这些散装文件。此处仅作尽力而为的可选增强：失败即回滚该补丁
# 的半应用内容并继续，绝不因此中断构建（APPLY_LINBOX_PATCHES 默认 0）。
if [ "$APPLY_LINBOX_PATCHES" = "1" ] && [ -d "$LINBOX_ROOT/path" ]; then
    echo ">> 应用 LinBox path/ 补丁（尽力而为，失败自动回滚并跳过）..."
    for p in "$LINBOX_ROOT"/path/*.patch; do
        [ -e "$p" ] || continue
        echo "   - $(basename "$p")"
        if ( cd wine && patch -Np1 --forward -s < "$p" > /dev/null 2>&1 ); then
            echo "     ✓ 已应用"
        elif ( cd wine && patch -Np1 -R --dry-run < "$p" > /dev/null 2>&1 ); then
            echo "     ⊘ 已包含等价改动，跳过"
        else
            # 回滚半应用 hunks，保持源码树可编译
            ( cd wine && patch -Np1 -R -s < "$p" > /dev/null 2>&1 ) || true
            echo "     ⚠ 应用失败（残片/冲突），已回滚跳过 —— 功能不受影响"
        fi
    done
fi

# ---- 3. 注入 DAC 驱动 ----
echo ">> 注入 winedac.drv ..."
( cd wine && patch -Np1 --forward < "$DAC_PATCH" )
rm -rf wine/dlls/winedac.drv
cp -r "$DAC_SRC" wine/dlls/winedac.drv
echo ">> winedac.drv 注入完成"

# ---- 3.5 默认图形驱动 → dac（免注册表，bin/wine 直跑即用 DAC）----
# wine 9.2 programs/explorer/desktop.c：无 HKCU\Software\Wine\Drivers
# "Graphics" 值时默认串为 mac,x11 —— 本构建 --without-x，两者皆无，
# 直跑必然 "The graphics driver is missing"（null driver）。
# DAC 专用构建直接把默认串改为 dac（→ winedac.drv，映射机制与
# x11 → winex11.drv 相同），免去 linbox-dac-reg 注册步骤。
DESKTOP_C="wine/programs/explorer/desktop.c"
if [ -f "$DESKTOP_C" ] && grep -qF "{'m','a','c',',','x','1','1',0}" "$DESKTOP_C"; then
    sed -i "s/{'m','a','c',',','x','1','1',0}/{'d','a','c',0}/" "$DESKTOP_C"
    echo ">> 默认图形驱动已改为 dac（explorer/desktop.c）"
else
    echo "⚠ 未找到 default_driver 字面量（wine 版本差异？）—— 部署后需运行" >&2
    echo "  linbox-dac-reg 设置 HKCU\\Software\\Wine\\Drivers Graphics=dac" >&2
fi

# ---- 4. 生成器 + autoreconf ----
( cd wine && dlls/winevulkan/make_vulkan && tools/make_requests && tools/make_specfiles && autoreconf -f )

# ---- 5. 构建参数（跳过 X11/桌面栈 —— DAC 替代其显示职责） ----
CONFIGURE_OPTS="
    --without-x --without-wayland --without-oss --without-cups
    --without-gphoto --without-pcsclite --without-sane --without-v4l2
    --without-xinerama --disable-tests
"

if [ "$TARGET" = "x86_64-linux" ]; then
    # 原生 x86_64 构建：unix 侧 x86_64 + PE 侧 i386/x86_64（新 WoW64 模式）
    # 设备端经 box64（x86_64 模拟）运行，AHB 走 dac_allocd 侧车。
    #
    # ⚠ 必须传 --enable-archs=i386,x86_64：wine 在 x86_64 主机上默认走
    #   32 位 unix 构建（configure 里 CC -m32 + PKG_CONFIG_LIBDIR 指向
    #   i386 目录），需要 freetype 等所有 :i386 多架构开发库 —— CI 只有
    #   amd64 库，必挂 "FreeType development files not found"。
    #   传了 --enable-archs 后 configure.ac 的 -m32 分支被跳过
    #   （host_cpu=x86_64），unix 侧 64 位，PE 侧 i386/x86_64 全走
    #   mingw，无需任何 :i386 库；且产出真正的 bin/wine 加载器
    #   （--enable-win64 只会得到 bin/wine64），32 位 Windows 程序
    #   经 WoW64 也能跑。
    OUT="wine-dac-${WINE_VERSION}-x86_64"
    ( cd wine && ./configure --prefix="$BUILD_DIR/out/$OUT" \
        --enable-archs=i386,x86_64 $CONFIGURE_OPTS )
    make -C wine -j"$JOBS"
    make -C wine install

elif [ "$TARGET" = "aarch64-glibc" ]; then
    # aarch64 glibc 构建（grun/loader 直接跑 arm64 wine 本机侧；PE 侧
    # i386/x86_64 走 mingw new WoW64）。⚠ wine 9.2 无内置 x86 模拟器，
    # 本目标无法运行 x86/x86_64 PE 程序（详见文件头说明）。
    OUT="wine-dac-${WINE_VERSION}-aarch64"
    if [ "$(uname -m)" = "aarch64" ]; then
        # ---- arm64 主机原生构建（GitHub ubuntu-24.04-arm runner / 本机）----
        # 本机 gcc 即 aarch64 目标编译器，无需任何交叉工具链；
        # PE 侧 i386/x86_64 由 mingw 提供（arm64 仓库同样有这两个 target）。
        echo ">> arm64 主机原生构建（无需交叉工具链）"
        ( cd wine && ./configure --prefix="$BUILD_DIR/out/$OUT" \
            --enable-archs=i386,x86_64 $CONFIGURE_OPTS )
        make -C wine -j"$JOBS"
        make -C wine install
    else
        # ---- x86_64 主机交叉构建（需完整 aarch64 交叉工具链 + arm64 侧
        # freetype/fontconfig 等开发库；CI 请改用 ubuntu-24.04-arm runner，
        # workflow 已按 TARGET 自动路由，此处仅保留给本地已配好交叉环境的场景）。
        if ! command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
            echo "✗ aarch64-glibc 目标在 x86_64 主机上需要 aarch64 交叉工具链：" >&2
            echo "    gcc-aarch64-linux-gnu / g++-aarch64-linux-gnu / arm64 侧 freetype 等开发库" >&2
            echo "  推荐（零交叉配置）：GitHub Actions 选 TARGET=aarch64-glibc ——" >&2
            echo "    workflow 会自动路由到 ubuntu-24.04-arm runner 原生构建（本机 gcc 即目标编译器）" >&2
            echo "  或改用 TARGET=x86_64-linux（产物经设备端 box64/grun 运行）" >&2
            exit 1
        fi
        if [ ! -d "$BUILD_DIR/tools" ]; then
            echo ">> 构建 host tools（本机 gcc）..."
            mkdir -p "$BUILD_DIR/tools" && cd "$BUILD_DIR/tools"
            "$BUILD_DIR/wine/configure" $CONFIGURE_OPTS \
                && make -j"$JOBS" __tooldeps__ libs/wine
            cd "$BUILD_DIR"
        fi
        mkdir -p "$BUILD_DIR/build-aarch64" && cd "$BUILD_DIR/build-aarch64"
        CC=aarch64-linux-gnu-gcc CXX=aarch64-linux-gnu-g++ \
        CFLAGS="-O3 -fomit-frame-pointer" CXXFLAGS="-O3 -fomit-frame-pointer" \
        CROSSCC=x86_64-w64-mingw32-gcc \
        "$BUILD_DIR/wine/configure" \
            --with-wine-tools="$BUILD_DIR/tools" \
            --enable-archs=i386,x86_64 \
            --prefix="$BUILD_DIR/out/$OUT" \
            $CONFIGURE_OPTS
        make -j"$JOBS"
        make install
    fi
else
    echo "未知 TARGET=$TARGET（支持 x86_64-linux | aarch64-glibc）"; exit 1
fi

# ---- 6. 设备端自举改造（安卓无法裸 exec glibc ELF）----
#   x86_64:  "cannot execute binary file: Exec format error"  → 需 box64 转译
#   aarch64: "cannot execute: required file not found"        → 缺 glibc loader
# 方案（glibc-runner / Mobox 同款，免 root 免安装）：
#   a) sysroot/lib：全部 unix ELF 的 glibc 依赖闭包 + 动态 loader（ldd 收集）
#   b) bin/{wine,wineserver,wineboot,...} 改名 *.real，原位生成自举 wrapper：
#      x86_64  → box64（设备端自备，LinBox 体系标准组件）
#      aarch64 → sysroot 自带 ld-linux 直接启动
#   c) wine 主入口额外自动拉起 DAC 显示器（APK 内 DacReceiver）+ dac_allocd 侧车
OUTDIR="$BUILD_DIR/out/$OUT"
SYSLIB="$OUTDIR/sysroot/lib"
if [ "$TARGET" = "x86_64-linux" ]; then
    BOOT_MODE=box64;  LD_NAME=ld-linux-x86-64.so.2
else
    BOOT_MODE=loader; LD_NAME=ld-linux-aarch64.so.1
fi

echo ">> 6a. 收集 glibc 依赖闭包 → sysroot/lib"
mkdir -p "$SYSLIB"
DEPS_LIST="$BUILD_DIR/.sysroot-deps"
: > "$DEPS_LIST"
# CI 上主机架构 == 目标架构（x86_64→x86_64 runner / aarch64→arm runner），
# ldd 原生可用；PE 侧文件非 ELF，ldd 静默失败自然跳过
while IFS= read -r elf; do
    ldd "$elf" 2>/dev/null | awk '
        /=>[[:space:]]*\// { print $3; next }
        /^[[:space:]]*\//  { print $1 }
    ' >> "$DEPS_LIST"
done < <(find "$OUTDIR/bin" "$OUTDIR/lib" -type f \( -perm -u+x -o -name '*.so*' \) 2>/dev/null)
sort -u "$DEPS_LIST" | while IFS= read -r lib; do
    _base="$(basename "$lib")"
    # v1.17：aarch64-glibc 目标且提供 GARM 时，glibc 闭包改用安卓补丁版
    if [ "$TARGET" = "aarch64-glibc" ] && [ -n "$GARM" ] && [ -f "$GARM/$_base" ]; then
        cp -L "$GARM/$_base" "$SYSLIB/" 2>/dev/null \
            || echo "  ⚠ 无法复制依赖(GARM): $_base"
        continue
    fi
    [ -f "$lib" ] || continue
    cp -L "$lib" "$SYSLIB/" 2>/dev/null || echo "  ⚠ 无法复制依赖: $lib"
done
[ -f "$SYSLIB/$LD_NAME" ] || { echo "✗ sysroot 缺动态 loader $LD_NAME"; exit 1; }
echo "   sysroot/lib：$(ls -1 "$SYSLIB" | wc -l) 个文件，$(du -sh "$SYSLIB" | cut -f1)"

# ---- 6a2.（仅 x86_64 目标）构建 tarball 自带 box64 + 私有 aarch64 glibc 闭包 ----
# 见文件头 v1.12 说明：自带 box64 用私有 loader + 私有 libc 直启，
# 规避 APK 自带 box64 的 "invalid ELF header" 与原生 LD_LIBRARY_PATH 污染。
# BUILD_BUNDLED_BOX64=0 可跳过（本地快速重打包 / 测试桩场景）。
SYSARM="$OUTDIR/sysroot-arm/lib"
if [ "$TARGET" = "x86_64-linux" ] && [ "${BUILD_BUNDLED_BOX64:-1}" = "1" ]; then
    echo ">> 6a2. 构建 tarball 自带 box64（aarch64 交叉）"
    if [ -z "$GARM" ]; then
        echo "   ⚠ 未提供 GARM（安卓补丁版 glibc）—— sysroot-arm 将用交叉主机 vanilla glibc"
        echo "     vanilla glibc 在安卓 App 域可能被 seccomp 击杀（Bad system call）；CI 已默认提供 GARM"
    fi
    if ! command -v aarch64-linux-gnu-gcc >/dev/null 2>&1 \
       || ! command -v cmake >/dev/null 2>&1 \
       || ! command -v git >/dev/null 2>&1; then
        echo "   安装交叉工具链（gcc-aarch64-linux-gnu / libc6-dev-arm64-cross / cmake / git）..."
        _sudo=""; [ "$(id -u)" = "0" ] || _sudo="sudo"
        $_sudo apt-get update -qq || true
        $_sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
            gcc-aarch64-linux-gnu libc6-dev-arm64-cross cmake git || true
    fi
    command -v aarch64-linux-gnu-gcc >/dev/null 2>&1 || { echo "✗ 缺 aarch64-linux-gnu-gcc，无法构建自带 box64"; exit 1; }
    command -v cmake                 >/dev/null 2>&1 || { echo "✗ 缺 cmake，无法构建自带 box64"; exit 1; }
    command -v git                   >/dev/null 2>&1 || { echo "✗ 缺 git，无法拉取 box64 源码"; exit 1; }
    if [ ! -x "$OUTDIR/bin/box64" ]; then
        if [ ! -d "$BUILD_DIR/box64-src" ]; then
            _ok=""
            for _i in 1 2 3; do
                git clone --depth 1 https://github.com/ptitSeb/box64.git \
                    "$BUILD_DIR/box64-src" && _ok=1 && break \
                    || { rm -rf "$BUILD_DIR/box64-src"; sleep 15; }
            done
            [ -n "$_ok" ] || { echo "✗ box64 源码拉取失败（3 次）"; exit 1; }
        fi
        cmake -S "$BUILD_DIR/box64-src" -B "$BUILD_DIR/box64-build" \
            -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
            -DCMAKE_BUILD_TYPE=Release \
            -DARM64=ON -DARM_DYNAREC=ON \
            > "$BUILD_DIR/box64-cmake.log" 2>&1 \
            || { echo "✗ box64 cmake 失败（日志: $BUILD_DIR/box64-cmake.log）"; exit 1; }
        make -C "$BUILD_DIR/box64-build" -j"$JOBS" \
            > "$BUILD_DIR/box64-make.log" 2>&1 \
            || { echo "✗ box64 make 失败（日志: $BUILD_DIR/box64-make.log）"; exit 1; }
        [ -f "$BUILD_DIR/box64-build/box64" ] || {
            echo "   ⚠ dynarec 构建无产物，回退 ARM_DYNAREC=OFF 重试（可用但转译性能较低）"
            cmake -S "$BUILD_DIR/box64-src" -B "$BUILD_DIR/box64-build" \
                -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
                -DCMAKE_BUILD_TYPE=Release \
                -DARM64=ON -DARM_DYNAREC=OFF > "$BUILD_DIR/box64-cmake.log" 2>&1
            make -C "$BUILD_DIR/box64-build" -j"$JOBS" \
                > "$BUILD_DIR/box64-make.log" 2>&1 \
                || { echo "✗ box64 make 二次失败"; exit 1; }
        }
        readelf -h "$BUILD_DIR/box64-build/box64" | grep -q "Machine:.*AArch64" \
            || { echo "✗ box64 产物不是 AArch64 ELF"; exit 1; }
        install -m 755 "$BUILD_DIR/box64-build/box64" "$OUTDIR/bin/box64"
    fi
    # box64 自身的 glibc 闭包：严格按 DT_NEEDED 收集；
    # v1.17：GARM（安卓补丁版 glibc）优先 —— vanilla glibc 在安卓 App 域
    # 会被 seccomp 击杀（Bad system call）
    mkdir -p "$SYSARM"
    for _lib in $(readelf -d "$OUTDIR/bin/box64" \
                  | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p'); do
        [ -f "$SYSARM/$_lib" ] && continue
        _src=""
        if [ -n "$GARM" ] && [ -f "$GARM/$_lib" ]; then
            _src="$GARM/$_lib"
        else
            _src="/usr/aarch64-linux-gnu/lib/$_lib"
            [ -f "$_src" ] || _src="/usr/aarch64-linux-gnu/lib/aarch64-linux-gnu/$_lib"
        fi
        if [ -f "$_src" ]; then
            cp -L "$_src" "$SYSARM/"
        else
            echo "  ⚠ box64 依赖 $_lib 缺失（GARM/交叉 sysroot 均无；部署后 box64 可能无法启动）"
        fi
    done
    # v1.21.2 修复"wine 启动即 SIGSEGV（pthread 符号全丢 / ntdll 无法加载）"：
    # box64 的 wrapped 库系列（libpthread/libdl/librt/libutil/libresolv）初始化时
    # 会 dlopen 各自的 soname，找不到时回退 ALTNAME（libpthread 的 ALTNAME 是
    # "libc.so"）。上面按 box64 的 DT_NEEDED 收集是"浮动清单"—— 随 box64 版本
    # 与工具链变化（glibc 2.34+ 里 pthread 并入 libc，stub 库可能不在
    # DT_NEEDED 里），一旦 libpthread.so.0 缺席：dlopen 失败 → ALTNAME
    # dlopen("libc.so") 也失败（glibc 没有无版本号 libc.so）→ wrapped libpthread
    # 初始化失败 → wrapped libc 的 NEEDED 链断 → wine.real 的全部 pthread_*
    # 重定位 404 → ntdll.so 加载失败 → SIGSEGV（真机 2026-09 实测）。
    # 因此这里不依赖 DT_NEEDED，无条件收集 glibc 运行时全家桶
    #（GARM 安卓补丁版优先，交叉 sysroot vanilla 兜底）。
    for _stub in libpthread.so.0 libdl.so.2 librt.so.1 libutil.so.1 libresolv.so.2; do
        [ -f "$SYSARM/$_stub" ] && continue
        _src=""
        if [ -n "$GARM" ] && [ -f "$GARM/$_stub" ]; then
            _src="$GARM/$_stub"
        else
            _src="/usr/aarch64-linux-gnu/lib/$_stub"
            [ -f "$_src" ] || _src="/usr/aarch64-linux-gnu/lib/aarch64-linux-gnu/$_stub"
        fi
        if [ -f "$_src" ]; then
            cp -L "$_src" "$SYSARM/" || echo "  ⚠ 无法复制 glibc stub: $_stub"
        else
            echo "  ⚠ glibc stub $_stub 无法收集（GARM/交叉 sysroot 均无）—— box64 wrapped 可能初始化失败"
        fi
    done
    # ALTNAME 防御：box64 wrapped libpthread/libm 的回退 dlopen 名是
    # "libc.so"/"libm.so"（无版本号）。glibc 体系没有这两个 soname —— 在
    # sysroot-arm 各放一份 .6 的内容副本（soname 不变，loader 按 DT_NEEDED
    # 精确名解析不会误用；仅兜底 dlopen 命中）。零成本消除这类回退失败。
    if [ -f "$SYSARM/libc.so.6" ] && [ ! -f "$SYSARM/libc.so" ]; then
        cp -L "$SYSARM/libc.so.6" "$SYSARM/libc.so" 2>/dev/null || true
    fi
    if [ -f "$SYSARM/libm.so.6" ] && [ ! -f "$SYSARM/libm.so" ]; then
        cp -L "$SYSARM/libm.so.6" "$SYSARM/libm.so" 2>/dev/null || true
    fi
    # glibc 动态 loader 本体（--library-path 直启用）；GARM（安卓版）优先
    _ldarm=""
    [ -n "$GARM" ] && [ -f "$GARM/ld-linux-aarch64.so.1" ] && _ldarm="$GARM/ld-linux-aarch64.so.1"
    [ -n "$_ldarm" ] || _ldarm="/usr/aarch64-linux-gnu/lib/ld-linux-aarch64.so.1"
    [ -f "$_ldarm" ] || _ldarm="/usr/aarch64-linux-gnu/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
    cp -L "$_ldarm" "$SYSARM/" 2>/dev/null || { echo "✗ 缺 ld-linux-aarch64.so.1（GARM/交叉 sysroot）"; exit 1; }
    echo "   自带 box64：$(du -h "$OUTDIR/bin/box64" | cut -f1)；sysroot-arm/lib：$(ls -1 "$SYSARM" | wc -l) 个文件"
fi

echo ">> 6b. 生成自举 wrapper（boot=$BOOT_MODE）"
# emit_wrapper <对外名> <主入口0/1> [真实ELF名，缺省<对外名>.real]
# v1.21.3：wine 主入口的真实 ELF 名固定为 wine64（见文件头：ntdll 按
# basename 尾 "64" 选 preloader，box64 也只对 wine64-preloader 走直跳通道）
emit_wrapper() { # $1=名字  $2=1 表示 wine 主入口（附 DAC 自动拉起）  $3=真实ELF名
    local name="$1" main="$2" realname="${3:-${1}.real}"
    {
        cat <<'HDR'
#!/system/bin/sh
# ============================================================
# LinBox-DAC 自举 wrapper —— 构建时自动生成，请勿手改
# 安卓内核不能直接 exec glibc ELF（Exec format error /
# required file not found），由本脚本完成 loader/box64 启动。
# ============================================================
HDR
        cat <<'PATHS'
DIR=$(CDPATH= cd "$(dirname "$0")" && pwd -P)
ROOT=$(dirname "$DIR")
PATHS
        echo "REAL=\"\$DIR/$realname\""
        cat <<'CHECKS'
SYSLIB="$ROOT/sysroot/lib"
die() { echo "[wine-dac] ✗ $*" >&2; exit 1; }
[ -f "$REAL" ] || die "缺 $REAL（tarball 解压不完整？）"
[ -d "$SYSLIB" ] || die "缺 $SYSLIB（tarball 解压不完整？）"
: "${PREFIX:=}"
if [ -z "$PREFIX" ]; then
    for _p in /data/user/*/com.linbox/files/usr /data/data/com.linbox/files/usr; do
        [ -d "$_p" ] && PREFIX="$_p" && break
    done
fi
[ -n "$PREFIX" ] || PREFIX=/data/user/0/com.linbox/files/usr
export PREFIX
# winedac.drv 用 TMPDIR 定位 bridge socket（$TMPDIR/linbox-dac.sock）；
# 若终端未导出 TMPDIR，wine 会去 /tmp 找 → 永远连不上（v1.13 修复）。
TMPDIR="$PREFIX/tmp"
export TMPDIR
mkdir -p "$TMPDIR" 2>/dev/null || true
# v1.18 防前缀创建卡死：禁 Wine Mono / Gecko 安装确认框 ——
# wineboot 初始化新前缀时弹框等点击，DAC 未连接（无显示器）时
# 无人能点"取消"，前缀初始化永久卡住。禁用 mscoree/mshtml 覆盖
# 即可跳过弹窗（Lutris/PlayOnLinux 同款方案）。
# .NET/MSHTML 程序需要真实 mono 时：LINBOX_DAC_MONO_PROMPT=1 恢复弹窗，
# 或自设 WINEDLLOVERRIDES（本块仅在未设置时生效）。
if [ -z "$WINEDLLOVERRIDES" ] && [ "${LINBOX_DAC_MONO_PROMPT:-0}" != 1 ]; then
    WINEDLLOVERRIDES="mscoree,mshtml="
    export WINEDLLOVERRIDES
fi
CHECKS
        if [ "$BOOT_MODE" = box64 ]; then
            cat <<'B64'
SYSARM="$ROOT/sysroot-arm/lib"
# ---- 选择 box64：优先 tarball 自带（带私有 aarch64 glibc 闭包，零外部依赖）----
# 自带 box64 用私有 loader 直启，完全不读 APK 的 glibc 目录，规避
# "box64: .../usr/glibc/lib/libc.so: invalid ELF header"。
LDP=""
LOADER=""
BOX64=""
if [ -n "$BOX64_BIN" ] && [ -x "$BOX64_BIN" ]; then
    BOX64="$BOX64_BIN"
    # v1.21.3 防呆：wine 内部按 /proc/self/exe 所在目录（= box64 目录）
    # 推导 wineserver/preloader 路径 —— 外部 box64 与 wine 不同目录时
    # 该推导会指向错误位置，wineserver 拉起 / ntdll 二次 exec 都会乱。
    if [ "$(dirname "$BOX64")" != "$DIR" ]; then
        echo "[wine-dac] ⚠ BOX64_BIN 指向外部 box64（$BOX64）—— 不与本目录 wine64/wineserver 同目录" >&2
        echo "[wine-dac]   ntdll 按 box64 所在目录推导 wine 组件路径，建议改用 $DIR/box64" >&2
    fi
elif [ -x "$DIR/box64" ]; then
    BOX64="$DIR/box64"
    if [ -x "$SYSARM/ld-linux-aarch64.so.1" ]; then
        LOADER="$SYSARM/ld-linux-aarch64.so.1"
        LDP="$SYSARM"
    fi
elif command -v box64 >/dev/null 2>&1; then
    BOX64="$(command -v box64)"
    echo "[wine-dac] ⚠ 使用 PATH 中的外部 box64（$BOX64）—— 应保证与本目录 wine64/wineserver 同目录，否则 wine 内部组件推导会乱" >&2
elif [ -x "$PREFIX/bin/box64" ]; then
    BOX64="$PREFIX/bin/box64"
elif [ -x /data/data/com.termux/files/usr/bin/box64 ]; then
    BOX64="/data/data/com.termux/files/usr/bin/box64"
fi
[ -n "$BOX64" ] || die "未找到 box64 —— x86_64 wine 必须经 box64 转译（tarball 自带 bin/box64，缺失说明解压不完整；也可自备放入 PATH 或 $PREFIX/bin）"
# 访客侧（x86_64 glibc 闭包 + wine unix 库）搜索路径：box64 内部加载器专用。
# 注意用 BOX64_LD_LIBRARY_PATH 而非原生 LD_LIBRARY_PATH —— 后者会被
# box64 自身的加载器读取，混入 x86_64 库（wrong ELF class）或 APK
# glibc 目录（libc.so 为 ld 链接脚本文本 → invalid ELF header）都会
# 让 box64 启动即崩（v1.12 前真机实测失败原因）。
export BOX64_LD_LIBRARY_PATH="$SYSLIB:$ROOT/lib/wine/x86_64-unix:$ROOT/lib/wine${BOX64_LD_LIBRARY_PATH:+:$BOX64_LD_LIBRARY_PATH}"
# 原生 LD_LIBRARY_PATH/LD_PRELOAD 一律清空：自带 box64 的原生依赖由
# 私有 loader --library-path 提供；外部回退 box64 自带解释器自解析。
unset LD_LIBRARY_PATH LD_PRELOAD
# v1.17：安卓 seccomp 双保险 —— 禁用 rseq 注册。gpkg 安卓版 glibc 本就
# 已移除 rseq（此 tunable 对它无副作用）；若混入 vanilla glibc（≥2.35）
# 启动即调 rseq，安卓 App 域 seccomp 直接 SIGSYS。
GLIBC_TUNABLES="${GLIBC_TUNABLES:+$GLIBC_TUNABLES:}glibc.pthread.rseq=0"
export GLIBC_TUNABLES
# v1.17 启动自检：私有 loader + 私有 glibc 先空跑一次 box64 --version。
# 若被 seccomp 击杀（Bad system call），当场给出人话指引，不再黑屏猜。
if [ -n "$LOADER" ]; then
    if ! "$LOADER" --library-path "$LDP" "$BOX64" -v >/dev/null 2>&1; then
        echo "[wine-dac] ⚠ 自检失败：私有 glibc 无法在本机启动 box64（Bad system call = 安卓 seccomp 拦截）" >&2
        echo "[wine-dac]   → 本 tarball 的 sysroot-arm 不是安卓补丁版 glibc，请换 v1.17+ CI 产物" >&2
        echo "[wine-dac]   → 设备安卓版本：$(getprop ro.build.version.release 2>/dev/null || echo 未知)" >&2
    fi
fi
B64
        else
            cat <<'ALD'
LOADER="$SYSLIB/ld-linux-aarch64.so.1"
[ -x "$LOADER" ] || die "缺 $LOADER（sysroot 不完整？）"
# v1.17：同 x86_64 分支 —— 清污染 + rseq 双保险
unset LD_LIBRARY_PATH LD_PRELOAD
GLIBC_TUNABLES="${GLIBC_TUNABLES:+$GLIBC_TUNABLES:}glibc.pthread.rseq=0"
export GLIBC_TUNABLES
ALD
        fi
        if [ "$main" = 1 ]; then
            cat <<'MAIN'
# ---- DAC 独立显示器自动拉起（APK 内原生安卓显示器，不依赖 X11） ----
if [ "${LINBOX_DAC_AUTO:-1}" = 1 ] && [ ! -S "$PREFIX/tmp/linbox-dac.sock" ]; then
    SIZE="${LINBOX_DAC_SIZE:-1280x720}"
    DW=${SIZE%x*}; DH=${SIZE#*x}
    # 必须显式指定包名（-p com.linbox）：targetSdk≥26 的应用，
    # 清单注册的 receiver 收不到隐式广播（隐式会被系统静默丢弃），
    # 这正是 v1.13 前"DAC 窗口未就绪"的主因之一。
    if command -v am >/dev/null 2>&1; then
        _amrc=0
        _amout=$(unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG; \
            am broadcast -p com.linbox \
            -a com.linbox.action.DAC_START \
            --ei width "$DW" --ei height "$DH" 2>&1) || _amrc=$?
        if [ "$_amrc" -ne 0 ] || [ -z "$_amout" ]; then
            echo "[wine-dac] ⚠ am broadcast 失败（exit=$_amrc）：$_amout" >&2
            echo "[wine-dac]   若上方有 Aborted：logcat -d -b crash | tail -30 看 am 崩溃栈" >&2
            echo "[wine-dac]   绕过：直接在 LinBox 里点开「DAC 显示器」应用即可（winedac 会自动接上）" >&2
        fi
    else
        echo "[wine-dac] ⚠ 终端缺少 am 命令（bootstrap 不完整？）——无法自动拉起 DAC 窗口" >&2
    fi
    i=0
    while [ ! -S "$PREFIX/tmp/linbox-dac.sock" ] && [ "$i" -lt 15 ]; do
        sleep 1; i=$((i+1))
    done
    if [ ! -S "$PREFIX/tmp/linbox-dac.sock" ]; then
        echo "[wine-dac] ⚠ DAC 窗口未就绪——不用管顺序，winedac 每 2 秒自动重连（v1.21）" >&2
        echo "[wine-dac] DAC 显示（v1.21 起任意顺序均可，无需重启 wine）：" >&2
        echo "[wine-dac]   · 先开 DAC 再跑 wine：直接点「DAC 显示器」应用即可" >&2
        echo "[wine-dac]   · 先跑 wine 再开 DAC：本命令跑完后，随时点开「DAC 显示器」" >&2
        echo "[wine-dac]     （悬浮球菜单 → DAC 显示器；2 秒内自动接上画面）" >&2
        echo "[wine-dac]   · DAC_START 广播到达时在终端页：会以迷你悬浮窗出现，" >&2
        echo "[wine-dac]     点小窗标题条/▣ 还原全屏，✕ 关闭" >&2
        echo "[wine-dac]   自查四点：" >&2
        echo "[wine-dac]   0) getprop ro.build.version.release  —— 安卓版本（越老 seccomp 越严）" >&2
        echo "[wine-dac]   1) ls $PREFIX/bin/linbox-dac  —— 不存在说明 APK 未含 DAC 模块或未重装/重进过 LinBox" >&2
        echo "[wine-dac]   2) logcat -d -s LinBoxDAC       —— 看 DAC_START 是否到达、DacView 是否报错" >&2
        echo "[wine-dac]   3) 发广播时 LinBox 必须处于前台（其 Activity 才能承载 DAC 画面）" >&2
    fi
fi
# ---- dac_allocd 侧车（glibc wine 的 AHardwareBuffer 分配代理） ----
if [ ! -S "$PREFIX/tmp/linbox-dac-allocd.sock" ]; then
    for _c in "$PREFIX/bin/dac_allocd" "$PREFIX/bin/libdac_allocd.so"; do
        [ -f "$_c" ] || continue
        chmod +x "$_c" 2>/dev/null
        LD_LIBRARY_PATH= TMPDIR="$PREFIX/tmp" "$_c" >/dev/null 2>&1 &
        echo $! > "$PREFIX/tmp/linbox-dac-allocd.pid" 2>/dev/null
        sleep 1
        break
    done
fi
# ---- v1.21 前缀创建进度提示：首次运行前缀在手机上要 1~5 分钟，
# 不提示会被当成卡死；Mono/Gecko 弹窗 v1.18 起已默认禁用 ----
if [ -z "${WINEPREFIX:-}" ]; then
    WINEPREFIX="$HOME/.wine"
    export WINEPREFIX
fi
if [ ! -d "$WINEPREFIX/drive_c/windows/system32" ]; then
    echo "[wine-dac] ● 首次运行：正在创建 wine 前缀（$WINEPREFIX）" >&2
    echo "[wine-dac]   手机上约需 1~5 分钟，期间无输出属正常，请勿退出" >&2
    echo "[wine-dac]   （Mono/Gecko 下载弹窗已默认禁用；超 10 分钟无响应可跑 linbox-dac doctor）" >&2
fi
export WINEDEBUG="${WINEDEBUG:-fixme-all}"
MAIN
        fi
        if [ "$BOOT_MODE" = box64 ]; then
            cat <<'EXECB64'
if [ -n "$LOADER" ]; then
    exec "$LOADER" --library-path "$LDP" "$BOX64" "$REAL" "$@"
else
    exec "$BOX64" "$REAL" "$@"
fi
EXECB64
        else
            echo 'exec "$LOADER" --library-path "$SYSLIB" "$REAL" "$@"'
        fi
    } > "$OUTDIR/bin/$name"
    chmod +x "$OUTDIR/bin/$name"
}

# v1.21.3 命名策略（详见文件头）：
#   wine       → starter ELF 改名 bin/wine64（basename 以 "64" 结尾）：
#                ntdll 选 wine64-preloader、box64 走 skip_first 直跳通道、
#                "Wine64 detected" prereserve 全部命中；wrapper 仍叫 bin/wine
#   wineserver → 保持真实 x86_64 ELF（ntdll exec_wineserver 固定 posix_spawn
#                /proc/self/exe 同目录的它；wrapper 脚本会让 box64 的
#                posix_spawn 拦截 IsX64=0 失守，落回安卓原生 spawn 被 sh 坑）
#   其余       → 改名 *.real + wrapper（纯人工工具，无内部 spawn）
for _n in wine wineserver wineboot winecfg msiexec reg regsvr32; do
    _f="$OUTDIR/bin/$_n"
    [ -f "$_f" ] || continue
    [ "$(head -c 4 "$_f" | od -An -tx1 | tr -d ' \n')" = "7f454c46" ] || continue
    if [ "$_n" = wine ]; then
        mv "$_f" "$OUTDIR/bin/wine64"
        emit_wrapper "$_n" 1 wine64
        echo "   + bin/wine → wrapper → bin/wine64"
    elif [ "$_n" = wineserver ]; then
        chmod +x "$_f" 2>/dev/null || true
        echo "   + bin/wineserver 保持真实 ELF（box64 posix_spawn 直接接管，IsX64=1）"
    else
        mv "$_f" "$_f.real"
        emit_wrapper "$_n" 0
        echo "   + bin/$_n → wrapper → ${_n}.real"
    fi
done

# v1.21.3 preloader 双名兜底：ntdll preloader_exec 按 loader basename
# 是否以 "64" 结尾二选一（wine64 → wine64-preloader / 其余 → wine-preloader）。
# wine 9.2 --enable-archs=i386,x86_64 构建只装 wine-preloader；纯 win64
# 构建只装 wine64-preloader —— 两个名字都备齐，查找哪个都能命中。
_pl64="$OUTDIR/bin/wine64-preloader"
_pl32="$OUTDIR/bin/wine-preloader"
if [ ! -f "$_pl64" ] && [ -f "$_pl32" ]; then
    cp "$_pl32" "$_pl64"
    echo "   + 复制 wine-preloader → wine64-preloader（双名兜底）"
elif [ ! -f "$_pl32" ] && [ -f "$_pl64" ]; then
    cp "$_pl64" "$_pl32"
    echo "   + 复制 wine64-preloader → wine-preloader（双名兜底）"
fi

# v1.21.3 产物自检：四个关键文件缺一不可
[ -f "$OUTDIR/bin/wine" ] && [ -f "$OUTDIR/bin/wine64" ] \
    || { echo "✗ bin/wine wrapper / bin/wine64 starter 生成失败"; exit 1; }
[ -f "$OUTDIR/bin/wine64-preloader" ] \
    || { echo "✗ 缺 bin/wine64-preloader（ntdll 二次 exec 必需，见 v1.21.3 说明）"; exit 1; }
[ -f "$OUTDIR/bin/wineserver" ] \
    && [ "$(head -c 4 "$OUTDIR/bin/wineserver" | od -An -tx1 | tr -d ' \n')" = "7f454c46" ] \
    || { echo "✗ bin/wineserver 不是真实 x86_64 ELF（posix_spawn IsX64 识别必需）"; exit 1; }

# ---- 7. 打包 ----
mkdir -p "$BUILD_DIR/dist"
( cd "$BUILD_DIR/out" && tar -cJf "$BUILD_DIR/dist/${OUT}.tar.xz" "$OUT" )
echo "=============================================="
echo " 完成: $BUILD_DIR/dist/${OUT}.tar.xz"
echo " 自举: $BOOT_MODE（sysroot 含 $LD_NAME + wrapper，安卓免 root 直跑）"
echo " 部署（LinBox 终端内）:"
echo "   tar -xJf ${OUT}.tar.xz -C \$HOME"
echo "   \$HOME/$OUT/bin/wine explorer /desktop=dac,1280x720 taskmgr"
echo "   # wrapper 自动：box64/loader 启动 + 拉起 DAC 显示器 + dac_allocd 侧车"
echo "   # 环境变量：LINBOX_DAC_SIZE=1920x1080 改分辨率；LINBOX_DAC_AUTO=0 关自动拉起"
if [ "$TARGET" != "x86_64-linux" ]; then
echo "   ⚠ aarch64 目标仅能跑 ARM64 PE；普通 x86/x86_64 程序请用 x86_64-linux + box64"
fi
echo " 验证: lib/wine/*/winedac.so 存在 → linbox-dac doctor"
echo " v1.18: wrapper 已默认禁 Mono/Gecko 弹窗（建前缀不再卡死）；LINBOX_DAC_MONO_PROMPT=1 恢复"
echo " v1.21: winedac 断线看门狗每 2 秒自动重连（任意启动顺序均可）；首次建前缀有进度提示"
echo " v1.21.3: starter 真身改名为 bin/wine64（ntdll/box64 preloader 命名约定对齐，"
echo "   修 \"could not exec the wine loader\"）；bin/wineserver 保持真实 ELF"
echo "   （box64 posix_spawn IsX64=1 直接接管，修 /system/bin/sh 链接失败）；"
echo "   wine-preloader / wine64-preloader 双名兜底。人工跑 wineserver 用："
echo "   \$HOME/$OUT/bin/box64 \$HOME/$OUT/bin/wineserver（或让 wine 自动拉起）"
echo "=============================================="
