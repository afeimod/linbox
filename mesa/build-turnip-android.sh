#!/usr/bin/env bash
# ============================================================
# build-turnip-android.sh — 构建 platforms=android 的 Mesa Turnip（Adreno）
# ============================================================
# 目标：提供 VK_ANDROID_external_memory_android_hardware_buffer 能力的
# Vulkan ICD（DAC 的 DXVK → AHB 路径依赖它）。
#
# ⚠ 版本要求：turnip 的 AHB 支持自 mesa 24.2 起才有
#   （24.0/24.1 的 tu_android.cc 只有 gralloc 导入路径，无 AHB 扩展），
#   因此默认 MESA_VERSION=24.2.1；低于 24.2 构建成功也用不了 DAC。
#
# 关键配置（均对 mesa-24.2.1 真实 meson_options.txt 核实过）：
#   -Dplatforms=android            启用 VK_KHR_android_surface / AHB WSI
#   -Dplatform-sdk-version=$API    Android 平台 API 级别
#   -Dandroid-stub=true            NDK 交叉必需：避免 pkg-config 查
#                                  cutils/hardware/sync（NDK 没有），
#                                  改用 mesa 自带 src/android_stub
#   -Dfreedreno-kmds=kgsl          KGSL 内核后端（Android 免 libdrm/免 root）
#                                  默认 msm 需要 libdrm → 交叉环境必挂
#   -Dvulkan-drivers=freedreno     turnip
#   -Dgallium-drivers=             不构建 GL（DAC 只走 Vulkan）
#   -Dxmlconfig=disabled           免 expat（NDK 无此库）
#
# ⚠ 无 -Dgallium-vulkan-layers 选项（mesa 从无此选项，正确名称是
#   vulkan-layers 且默认即空 —— 传了必报 Unknown option）
#
# 环境：Linux x86_64 主机 + Android NDK r26+（NDK 环境变量），需要
#   meson / ninja / python3-mako（pip install meson ninja mako）。
# 产物：libvulkan_freedreno.so + freedreno_icd.aarch64.json
#
# 用法：
#   MESA_VERSION=24.2.1 NDK=/opt/ndk ./build-turnip-android.sh
# ============================================================
set -e

MESA_VERSION="${MESA_VERSION:-24.2.1}"
WORK="${WORK:-$HOME/mesa-build}"
NDK="${NDK:-$ANDROID_NDK_HOME}"
API="${API:-29}"          # AHB NDK API 需要 ≥26；KGSL 后端建议 29
TARGET="${TARGET:-aarch64-linux-android}"
HOST_TAG="${HOST_TAG:-linux-x86_64}"

[ -n "$NDK" ] || { echo "✗ 未设置 NDK（NDK=路径 或 ANDROID_NDK_HOME）"; exit 1; }
[ -d "$NDK/toolchains/llvm/prebuilt/$HOST_TAG" ] || { echo "✗ NDK 路径无效: $NDK"; exit 1; }

CLANG="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
mkdir -p "$WORK" && cd "$WORK"

# mesa 的 meson 构建硬性要求 python mako（缺了 setup 期直接报错）
python3 -c 'import mako' 2>/dev/null || {
    echo ">> 安装 python mako ..."
    pip3 install --break-system-packages mako 2>/dev/null || pip3 install mako
}

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
# 所有选项名均对 mesa-24.2.1 meson_options.txt 核实
rm -rf build-aarch64
meson setup build-aarch64 mesa \
    --cross-file cross.txt \
    -Dplatforms=android \
    -Dplatform-sdk-version="$API" \
    -Dandroid-stub=true \
    -Dfreedreno-kmds=kgsl \
    -Dgallium-drivers= \
    -Dvulkan-drivers=freedreno \
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
