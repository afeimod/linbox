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
# 用法（Ubuntu x86_64 主机 / GitHub Actions runner 均可）：
#   ./build_wine_dac.sh                              # 默认 x86_64-linux
#   TARGET=aarch64-glibc ./build_wine_dac.sh         # aarch64 交叉（grun arm64）
#   WINE_VERSION=9.2 WINE_BRANCH=vanilla ./build_wine_dac.sh
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
    if ! command -v aarch64-linux-gnu-gcc >/dev/null 2>&1 \
       || ! command -v cmake >/dev/null 2>&1 \
       || ! command -v git >/dev/null 2>&1; then
        echo "   安装交叉工具链（gcc-aarch64-linux-gnu / libc6-dev-arm64-cross / cmake / git）..."
        sudo apt-get update -qq || true
        sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
            gcc-aarch64-linux-gnu libc6-dev-arm64-cross cmake git || true
    fi
    command -v aarch64-linux-gnu-gcc >/dev/null 2>&1 || { echo "✗ 缺 aarch64-linux-gnu-gcc，无法构建自带 box64"; exit 1; }
    command -v cmake                 >/dev/null 2>&1 || { echo "✗ 缺 cmake，无法构建自带 box64"; exit 1; }
    command -v git                   >/dev/null 2>&1 || { echo "✗ 缺 git，无法拉取 box64 源码"; exit 1; }
    if [ ! -x "$OUTDIR/bin/box64" ]; then
        if [ ! -d "$BUILD_DIR/box64-src" ]; then
            git clone --depth 1 https://github.com/ptitSeb/box64.git "$BUILD_DIR/box64-src"
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
    # box64 自身的 glibc 闭包：严格按 DT_NEEDED 从交叉 sysroot 收集
    mkdir -p "$SYSARM"
    for _lib in $(readelf -d "$OUTDIR/bin/box64" \
                  | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p'); do
        [ -f "$SYSARM/$_lib" ] && continue
        _src="/usr/aarch64-linux-gnu/lib/$_lib"
        [ -f "$_src" ] || _src="/usr/aarch64-linux-gnu/lib/aarch64-linux-gnu/$_lib"
        if [ -f "$_src" ]; then
            cp -L "$_src" "$SYSARM/"
        else
            echo "  ⚠ box64 依赖 $_lib 在交叉 sysroot 缺失（部署后 box64 可能无法启动）"
        fi
    done
    # glibc 动态 loader 本体（--library-path 直启用）
    _ldarm="/usr/aarch64-linux-gnu/lib/ld-linux-aarch64.so.1"
    [ -f "$_ldarm" ] || _ldarm="/usr/aarch64-linux-gnu/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
    cp -L "$_ldarm" "$SYSARM/" 2>/dev/null || { echo "✗ 交叉 sysroot 缺 ld-linux-aarch64.so.1"; exit 1; }
    echo "   自带 box64：$(du -h "$OUTDIR/bin/box64" | cut -f1)；sysroot-arm/lib：$(ls -1 "$SYSARM" | wc -l) 个文件"
fi

echo ">> 6b. 生成自举 wrapper（boot=$BOOT_MODE）"
emit_wrapper() { # $1=名字  $2=1 表示 wine 主入口（附 DAC 自动拉起）
    local name="$1" main="$2"
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
        echo "REAL=\"\$DIR/${name}.real\""
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
elif [ -x "$DIR/box64" ]; then
    BOX64="$DIR/box64"
    if [ -x "$SYSARM/ld-linux-aarch64.so.1" ]; then
        LOADER="$SYSARM/ld-linux-aarch64.so.1"
        LDP="$SYSARM"
    fi
elif command -v box64 >/dev/null 2>&1; then
    BOX64="$(command -v box64)"
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
B64
        else
            cat <<'ALD'
LOADER="$SYSLIB/ld-linux-aarch64.so.1"
[ -x "$LOADER" ] || die "缺 $LOADER（sysroot 不完整？）"
ALD
        fi
        if [ "$main" = 1 ]; then
            cat <<'MAIN'
# ---- DAC 独立显示器自动拉起（APK 内原生安卓显示器，不依赖 X11） ----
if [ "${LINBOX_DAC_AUTO:-1}" = 1 ] && [ ! -S "$PREFIX/tmp/linbox-dac.sock" ]; then
    SIZE="${LINBOX_DAC_SIZE:-1280x720}"
    DW=${SIZE%x*}; DH=${SIZE#*x}
    LD_LIBRARY_PATH= am broadcast -a com.linbox.action.DAC_START \
        --ei width "$DW" --ei height "$DH" >/dev/null 2>&1
    i=0
    while [ ! -S "$PREFIX/tmp/linbox-dac.sock" ] && [ "$i" -lt 15 ]; do
        sleep 1; i=$((i+1))
    done
    [ -S "$PREFIX/tmp/linbox-dac.sock" ] || \
        echo "[wine-dac] ⚠ DAC 窗口未就绪（APK 需含 DAC 模块并保持安装）——仍继续启动 wine" >&2
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

for _n in wine wineserver wineboot winecfg msiexec reg regsvr32; do
    _f="$OUTDIR/bin/$_n"
    [ -f "$_f" ] || continue
    [ "$(head -c 4 "$_f" | od -An -tx1 | tr -d ' \n')" = "7f454c46" ] || continue
    mv "$_f" "$_f.real"
    if [ "$_n" = wine ]; then emit_wrapper "$_n" 1; else emit_wrapper "$_n" 0; fi
    echo "   + bin/$_n → wrapper → ${_n}.real"
done
[ -f "$OUTDIR/bin/wine" ] && [ -f "$OUTDIR/bin/wine.real" ] \
    || { echo "✗ bin/wine 自举 wrapper 生成失败"; exit 1; }

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
echo "=============================================="
