#!/bin/bash
set -e

echo "开始设置 chroot 环境..."

# 设置软件源
cat > /etc/apt/sources.list << 'SOURCES'
deb http://archive.ubuntu.com/ubuntu/ bionic main universe multiverse
deb http://archive.ubuntu.com/ubuntu/ bionic-updates main universe multiverse
deb http://archive.ubuntu.com/ubuntu/ bionic-security main universe multiverse
SOURCES

# 启用多架构支持
dpkg --add-architecture i386
apt update

# 安装基础工具
apt install -y software-properties-common
add-apt-repository -y ppa:ubuntu-toolchain-r/test
apt update

# 安装 GCC 和基础构建工具
apt install -y \
  build-essential \
  gcc-11 g++-11 gcc-11-multilib g++-11-multilib \
  autoconf automake libtool pkg-config \
  cmake ninja-build nasm yasm gettext

# 设置编译器替代
update-alternatives --install /usr/bin/gcc gcc /usr/bin/gcc-11 100
update-alternatives --install /usr/bin/g++ g++ /usr/bin/g++-11 100
update-alternatives --install /usr/bin/cc cc /usr/bin/gcc-11 100
update-alternatives --install /usr/bin/c++ c++ /usr/bin/g++-11 100

# 安装核心开发库（根据配置选项）
echo "安装必需的开发库..."
apt install -y \
  libc6-dev libc6-dev-i386 \
  libx11-dev libx11-dev:i386 \
  libfreetype6-dev libfreetype6-dev:i386 \
  libfontconfig1-dev libfontconfig1-dev:i386 \
  libasound2-dev libasound2-dev:i386 \
  libpulse-dev libpulse-dev:i386 \
  libvulkan-dev libvulkan-dev:i386
apt update
apt install -f liborc-0.4-dev -y
apt install -f liborc-0.4-dev:i386 -y
apt install -y libunwind-dev
# 安装 GStreamer（因为配置中有 --with-gstreamer）
apt install -y \
  libgstreamer1.0-dev \
  libgstreamer-plugins-base1.0-dev \
  libgstreamer1.0-dev:i386 \
  libgstreamer-plugins-base1.0-dev:i386

# 安装交叉编译器
apt install -y gcc-mingw-w64 g++-mingw-w64
update-alternatives --set x86_64-w64-mingw32-gcc /usr/bin/x86_64-w64-mingw32-gcc-posix
update-alternatives --set x86_64-w64-mingw32-g++ /usr/bin/x86_64-w64-mingw32-g++-posix
update-alternatives --set i686-w64-mingw32-gcc /usr/bin/i686-w64-mingw32-gcc-posix
update-alternatives --set i686-w64-mingw32-g++ /usr/bin/i686-w64-mingw32-g++-posix

# 安装 ccache
apt install -y ccache

# 验证
echo "验证安装..."
gcc --version
g++ --version
echo "64位库检查:"
pkg-config --cflags x11 freetype2 fontconfig alsa pulseaudio vulkan gstreamer-1.0 2>/dev/null || true
echo "32位库检查:"
PKG_CONFIG_LIBDIR=/usr/lib/i386-linux-gnu/pkgconfig pkg-config --cflags x11 freetype2 fontconfig alsa pulseaudio vulkan gstreamer-1.0 2>/dev/null || true

# 清理
apt clean
rm -rf /var/lib/apt/lists/*

echo "chroot 环境设置完成"