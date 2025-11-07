#!/bin/bash
set -e

echo "开始设置 32位 chroot 环境..."

# 设置软件源
echo "deb http://archive.ubuntu.com/ubuntu/ bionic main universe multiverse" > /etc/apt/sources.list
echo "deb http://archive.ubuntu.com/ubuntu/ bionic-updates main universe multiverse" >> /etc/apt/sources.list
echo "deb http://archive.ubuntu.com/ubuntu/ bionic-security main universe multiverse" >> /etc/apt/sources.list

# 更新包列表
apt update
apt install -y software-properties-common
add-apt-repository -y ppa:ubuntu-toolchain-r/test

# Update and install basic build tools first
apt update
apt install -y build-essential autoconf automake flex bison libtool \
  pkg-config gettext nasm yasm cmake meson ninja-build

# Install development libraries (using available packages in bionic)
apt install -y libx11-dev libfreetype6-dev libfontconfig1-dev \
  libasound2-dev libpulse-dev libdbus-1-dev libudev-dev \
  libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
  libsdl2-dev libgnutls28-dev libldap2-dev libjpeg-dev \
  libpng-dev libtiff5-dev libmpg123-dev libopenal-dev \
  libcups2-dev libosmesa6-dev libpcap-dev libusb-1.0-0-dev \
  libsane-dev libv4l-dev libgphoto2-dev liblcms2-dev \
  libpcsclite-dev libacl1-dev libxml2-dev libxslt1-dev \
  libavcodec-dev libavformat-dev libavutil-dev libswresample-dev \
  libvpx-dev libx264-dev libx265-dev libva-dev libdrm-dev \
  libwayland-dev libxkbcommon-dev libegl1-mesa-dev libgl1-mesa-dev \
  libgles2-mesa-dev libglu1-mesa-dev libxi-dev libxrandr-dev \
  libxfixes-dev libxcursor-dev libxinerama-dev libxcomposite-dev \
  libxdamage-dev libxxf86vm-dev libxt-dev libxmu-dev libxtst-dev

# Install Vulkan development packages if available
apt install -y vulkan-utils || echo "Vulkan not available, continuing..."
apt install -y libvulkan1 || echo "libvulkan1 not available, continuing..."

# Install CAPI development packages if available
apt install -y libcapi20-3 libcapi20-dev || echo "CAPI packages not available, continuing..."

# Install newer GCC if available
apt install -y gcc-9 g++-9 gcc-10 g++-10 || echo "Newer GCC versions not available, installing default"
apt install -y gcc g++

# Install cross-compilers
apt install -y gcc-mingw-w64-i686 g++-mingw-w64-i686
update-alternatives --set i686-w64-mingw32-gcc /usr/bin/i686-w64-mingw32-gcc-posix
update-alternatives --set i686-w64-mingw32-g++ /usr/bin/i686-w64-mingw32-g++-posix

echo "阶段9: 安装 ccache..."
apt install -y ccache bubblewrap

# 验证安装
echo "验证安装..."
gcc --version
g++ --version
i686-w64-mingw32-gcc --version

echo "验证 GStreamer 安装..."
pkg-config --modversion gstreamer-1.0
pkg-config --modversion gstreamer-plugins-base-1.0

# 测试简单编译
echo "测试简单编译..."
echo 'int main() { return 0; }' > /tmp/test.c
gcc -o /tmp/test /tmp/test.c && echo "✓ 编译测试成功" || echo "✗ 编译测试失败"

# 清理
apt clean
rm -rf /var/lib/apt/lists/*

echo "32位 chroot 环境设置完成"