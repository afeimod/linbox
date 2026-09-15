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
    # aarch64 glibc 构建（grun 直接跑 arm64 wine 本机侧；PE 侧 i386/x86_64
    # 走 mingw new WoW64 —— 设备端 x86 PE 指令由 box64 转译）。
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

# ---- 6. 打包 ----
mkdir -p "$BUILD_DIR/dist"
( cd "$BUILD_DIR/out" && tar -cJf "$BUILD_DIR/dist/${OUT}.tar.xz" "$OUT" )
echo "=============================================="
echo " 完成: $BUILD_DIR/dist/${OUT}.tar.xz"
echo " 部署（LinBox 终端内）:"
echo "   tar -xJf ${OUT}.tar.xz -C \$HOME"
echo "   \$HOME/$OUT/bin/wine explorer /desktop=dac,1280x720 game.exe"
echo " 验证: lib/wine/*/winedac.so 存在 → linbox-dac doctor"
echo "=============================================="
