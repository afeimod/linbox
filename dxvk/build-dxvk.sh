#!/usr/bin/env bash
# ============================================================
# build-dxvk.sh — 构建 DXVK 并部署到 wine prefix
# ============================================================
# DAC 路径下 DXVK 无需任何补丁：swapchain 由 winedac.drv 在 wine 层
# 仿真（vkCreateSwapchainKHR → AHardwareBuffer/dmabuf 图像），DXVK 只当
# 普通 d3d9/d3d10/d3d11 → Vulkan 翻译层使用。
#
# 环境：Linux 主机 + mingw-w64 交叉工具链 + meson/ninja + glslang
#   （Ubuntu: apt install g++-mingw-w64-x86-64 g++-mingw-w64-i686
#             meson ninja glslang-tools）
# 用法：
#   DXVK_VERSION=2.4 ./build-dxvk.sh [wine-prefix 路径]
#   # 仅构建不部署：DEPLOY=0 ./build-dxvk.sh
# ============================================================
set -e

DXVK_VERSION="${DXVK_VERSION:-2.4}"
WORK="${WORK:-$HOME/dxvk-build}"
PREFIX_DIR="${1:-${PREFIX_DIR:-$HOME/.wine}}"
DEPLOY="${DEPLOY:-1}"

mkdir -p "$WORK" && cd "$WORK"

if [ ! -d dxvk ]; then
    git clone https://github.com/doitsujin/dxvk.git dxvk
fi
cd dxvk
git checkout "v${DXVK_VERSION}"

# DXVK 2.x 的 Vulkan-Headers / SPIRV-Headers / mingw-directx-headers /
# libdisplay-info（windows 分支）均为 git 子模块，普通 clone 不会拉取；
# 缺了它们 meson 配置期必报 Missing Vulkan-Headers / Missing SPIRV-Headers
# （对应 dxvk meson.build 的 fs.is_dir('include/vulkan/include') 检查）
git submodule update --init --recursive

# package-release.sh <release-dir-name> <output-dir> [--no-package]
# 注意：第一个参数是产物目录名（非 git ref —— ref 已由上面 checkout）
./package-release.sh "${DXVK_VERSION}" "$WORK/out" --no-package

OUT="$WORK/out/dxvk-${DXVK_VERSION}"

# package-release.sh（v2.x）的 32 位产物目录叫 x32（build_arch 32 → --bindir x32，
# 见 dxvk 源码 package-release.sh:87 build_arch 32），官方 release 打包层才用 x86。
# 这里统一规范为 x64/x86 —— 与 CI 校验、下方部署段（x86→syswow64）及
# 社区通用习惯保持一致，避免"构建成功但校验找不到目录"的错位。
if [ -d "$OUT/x32" ] && [ ! -e "$OUT/x86" ]; then
    mv "$OUT/x32" "$OUT/x86"
fi

# 产物自检：两个架构目录必须存在且核心 DLL 齐备，缺了当场报错退出
# （d3d8 仅 1.10.3+ 默认构建，不在强校验之列）
for arch in x64 x86; do
    [ -d "$OUT/$arch" ] || { echo "✗ 构建产物缺 $arch/ 目录"; exit 1; }
    for dll in d3d9 d3d10core d3d11 dxgi; do
        [ -f "$OUT/$arch/$dll.dll" ] || { echo "✗ 构建产物缺 $arch/$dll.dll"; exit 1; }
    done
done

echo ">> 构建 OK：$OUT（x64/x86 核心 DLL 齐备）"
ls -lh "$OUT/x64" "$OUT/x86"

if [ "$DEPLOY" = "1" ]; then
    echo ">> 部署到 prefix: $PREFIX_DIR"
    for arch in x64 x86; do
        DLLDIR="$PREFIX_DIR/drive_c/windows/system32"
        [ "$arch" = "x86" ] && DLLDIR="$PREFIX_DIR/drive_c/windows/syswow64"
        [ -d "$OUT/$arch" ] || continue
        mkdir -p "$DLLDIR"
        for dll in d3d9 d3d10core d3d11 dxvk_config; do
            cp "$OUT/$arch/$dll.dll" "$DLLDIR/" 2>/dev/null || true
        done
    done
    # dxgi.dll 不覆盖：wine 内建 dxgi 与 DAC 交换链仿真配合
    # （如需 DXVK dxgi，把上面列表加 dxgi 即可）

    echo ">> 设置 DLL 覆盖（native）："
    cat > /tmp/dxvk.reg <<'EOF'
REGEDIT4

[HKEY_CURRENT_USER\Software\Wine\DllOverrides]
"d3d9"="native"
"d3d10core"="native"
"d3d11"="native"
EOF
    echo "   运行: wine regedit /tmp/dxvk.reg（或导入到目标 prefix）"
fi

echo "=============================================="
echo " DXVK $DXVK_VERSION 构建完成"
echo " GPU 渲染依赖：mesa/build-turnip-android.sh 的 AHB ICD"
echo "   （无 GPU ICD 时自动落 lavapipe：CPU 渲染兜底）"
echo "=============================================="
