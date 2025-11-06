#!/bin/bash

# 设置引导环境的脚本
# 这个脚本会在 chroot 环境中执行

set -e

echo "开始设置引导环境..."

# 添加 universe 和 multiverse 软件源
echo "deb http://archive.ubuntu.com/ubuntu/ bionic main universe multiverse" > /etc/apt/sources.list
echo "deb http://archive.ubuntu.com/ubuntu/ bionic-updates main universe multiverse" >> /etc/apt/sources.list
echo "deb http://archive.ubuntu.com/ubuntu/ bionic-security main universe multiverse" >> /etc/apt/sources.list

# 更新包列表
apt update

# 安装基本工具
apt install -y software-properties-common
add-apt-repository -y ppa:ubuntu-toolchain-r/test

# 再次更新包列表
apt update

# 安装基本构建工具
apt install -y build-essential autoconf automake flex bison libtool \
  pkg-config gettext nasm yasm cmake meson ninja-build

# 安装开发库
apt install -y libx11-dev libfreetype6-dev libfontconfig1-dev \
  libasound2-dev libpulse-dev libdbus-1-dev libudev-dev \
  libsdl2-dev libgnutls28-dev libldap2-dev libjpeg-dev \
  libpng-dev libtiff5-dev libmpg123-dev \
  libcups2-dev libosmesa6-dev libpcap-dev libusb-1.0-0-dev \
  libsane-dev libv4l-dev libgphoto2-dev liblcms2-dev \
  libpcsclite-dev libacl1-dev libxml2-dev libxslt1-dev \
  libavcodec-dev libavformat-dev libavutil-dev libswresample-dev \
  libvpx-dev libx264-dev libx265-dev libva-dev libdrm-dev \
  libwayland-dev libxkbcommon-dev libegl1-mesa-dev libgl1-mesa-dev \
  libgles2-mesa-dev libglu1-mesa-dev libxi-dev libxrandr-dev \
  libxfixes-dev libxcursor-dev libxinerama-dev libxcomposite-dev \
  libxdamage-dev libxxf86vm-dev libxt-dev libxmu-dev libxtst-dev

# 可选安装 GStreamer
apt install -y libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev || echo "GStreamer 开发包不可用，跳过"

# 安装 Vulkan 开发包
apt install -y vulkan-utils || echo "Vulkan 不可用，继续..."
apt install -y libvulkan1 || echo "libvulkan1 不可用，继续..."

# 安装 CAPI 开发包
apt install -y libcapi20-3 libcapi20-dev || echo "CAPI 包不可用，继续..."

# 安装更新的 GCC
apt install -y gcc-9 g++-9 gcc-10 g++-10 || echo "更新的 GCC 版本不可用，安装默认版本"
apt install -y gcc g++

# 安装交叉编译器
apt install -y gcc-mingw-w64 g++-mingw-w64
update-alternatives --set x86_64-w64-mingw32-gcc /usr/bin/x86_64-w64-mingw32-gcc-posix
update-alternatives --set x86_64-w64-mingw32-g++ /usr/bin/x86_64-w64-mingw32-g++-posix
update-alternatives --set i686-w64-mingw32-gcc /usr/bin/i686-w64-mingw32-gcc-posix
update-alternatives --set i686-w64-mingw32-g++ /usr/bin/i686-w64-mingw32-g++-posix

echo "引导环境设置完成!"