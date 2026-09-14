#!/usr/bin/env bash
# ============================================================
# build-turnip-android.sh — 构建 platforms=android 的 Mesa Turnip（Adreno）
# ============================================================
# 目标：提供 VK_ANDROID_external_memory_android_hardware_buffer 能力的
# Vulkan ICD（DAC 的 DXVK → AHB 路径依赖它）。Termux 官方 mesa 的
# turnip 构建面向 xcb/wayland，缺 AHB 扩展；本脚本启用 android 平台 +
# KGSL 后端（免 libdrm/免 root，直接 ioctl GPU）。
#
# 环境：Linux x86_64 主机 + Android NDK r26+（NDK 环境变量），需要
#   meson / ninja（pip install meson ninja）。
# 产物：libvulkan_freedreno.so + freedreno_icd.aarch64.json
#
# 用法：
#   MESA_VERSION=24.0.9 NDK=/opt/ndk ./build-turnip-android.sh
# ============================================================
set -e

MESA_VERSION="${MESA_VERSION:-24.0.9}"
WORK="${WORK:-$HOME/mesa-build}"
NDK="${NDK:-$ANDROID_NDK_HOME}"
API="${API:-29}"          # AHB NDK API 需要 ≥26；KGSL 后端建议 29
TARGET="${TARGET:-aarch64-linux-android}"
HOST_TAG="${HOST_TAG:-linux-x86_64}"

[ -n "$NDK" ] || { echo "✗ 未设置 NDK（NDK=路径 或 ANDROID_NDK_HOME）"; exit 1; }
[ -d "$NDK/toolchains/llvm/prebuilt/$HOST_TAG" ] || { echo "✗ NDK 路径无效: $NDK"; exit 1; }

CLANG="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
mkdir -p "$WORK" && cd "$WORK"

[ -d mesa ] || {
    wget -q "https://archive.mesa3d.org/mesa-${MESA_VERSION}.tar.xz"
    tar xf "mesa-${MESA_VERSION}.tar.xz"
    mv "mesa-${MESA_VERSION}" mesa
}

# ---- Meson 交叉文件 ----
cat > cross.txt <<EOF
[binaries]
c = '$CLANG/${TARGET}${API}-clang'
cpp = '$CLANG/${TARGET}${API}-clang++'
ar = '$CLANG/llvm-ar'
strip = '$CLANG/llvm-strip'
pkg-config = 'false'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

# ---- 配置：android 平台 + turnip（vulkan 驱动即 freedreno/turnip） ----
# 关键选项：
#   -Dplatforms=android            启用 VK_KHR_android_surface / AHB WSI
#   -Dplatform-sdk-version=$API    Android 平台 API 级别
#   -Dvulkan-drivers=freedreno     turnip（KGSL 后端，无 libdrm 依赖）
#   -Dgallium-drivers=             不构建 GL（DAC 只走 Vulkan）
#   -Dxmlconfig=disabled           免 expat（NDK 无此库）
rm -rf build-aarch64
meson setup build-aarch64 mesa \
    --cross-file cross.txt \
    -Dplatforms=android \
    -Dplatform-sdk-version="$API" \
    -Dgallium-drivers= \
    -Dvulkan-drivers=freedreno \
    -Dgallium-vulkan-layers= \
    -Dbuild-tests=false \
    -Dglx=disabled \
    -Dgbm=disabled \
    -Degl=disabled \
    -Dllvm=disabled \
    -Dzstd=disabled \
    -Dxmlconfig=disabled \
    --buildtype release

ninja -C build-aarch64

OUT="$WORK/out"
mkdir -p "$OUT"
cp build-aarch64/src/freedreno/vulkan/libvulkan_freedreno.so "$OUT/"
[ -f build-aarch64/src/freedreno/vulkan/freedreno_icd.aarch64.json ] && \
    cp build-aarch64/src/freedreno/vulkan/freedreno_icd.aarch64.json "$OUT/" || \
    cat > "$OUT/freedreno_icd.aarch64.json" <<EOF
{
  "ICD": {
    "library_path": "libvulkan_freedreno.so",
    "api_version": "1.3.0"
  }
}
EOF

echo "=============================================="
echo " 构建完成: $OUT"
echo "   libvulkan_freedreno.so"
echo "   freedreno_icd.aarch64.json"
echo "----------------------------------------------"
echo " 部署（LinBox 终端内）："
echo "   mkdir -p \$PREFIX/share/vulkan/icd.d"
echo "   cp libvulkan_freedreno.so \$PREFIX/lib/"
echo "   cp freedreno_icd.aarch64.json \$PREFIX/share/vulkan/icd.d/"
echo "   export VK_ICD_FILENAMES=\$PREFIX/share/vulkan/icd.d/freedreno_icd.aarch64.json"
echo "=============================================="
