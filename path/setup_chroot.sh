#!/bin/bash
set -e

echo "开始设置 chroot 环境..."

# 设置软件源
cat > /etc/apt/sources.list << 'SOURCES'
deb http://archive.ubuntu.com/ubuntu/ bionic main universe multiverse
deb http://archive.ubuntu.com/ubuntu/ bionic-updates main universe multiverse
deb http://archive.ubuntu.com/ubuntu/ bionic-security main universe multiverse
SOURCES

# 更新和安装基本工具
apt update
apt install -y software-properties-common
add-apt-repository -y ppa:ubuntu-toolchain-r/test
apt update

# 安装 GCC 11 和构建工具
apt install -y gcc-11 g++-11 gcc-11-multilib g++-11-multilib
update-alternatives --install /usr/bin/gcc gcc /usr/bin/gcc-11 100
update-alternatives --install /usr/bin/g++ g++ /usr/bin/g++-11 100
update-alternatives --install /usr/bin/cc cc /usr/bin/gcc-11 100
update-alternatives --install /usr/bin/c++ c++ /usr/bin/g++-11 100

# 安装基本构建工具
apt install -y build-essential autoconf automake flex bison libtool \
  pkg-config gettext nasm yasm cmake meson ninja-build \
  libc6-dev libc6-dev-i386

# 安装开发库
apt install -y libx11-dev libfreetype6-dev libfontconfig1-dev \
  libasound2-dev libpulse-dev libdbus-1-dev libudev-dev \
  libsdl2-dev libgnutls28-dev libldap2-dev libjpeg-dev \
  libpng-dev libtiff5-dev libmpg123-dev \
  libcups2-dev libosmesa6-dev libpcap-dev libusb-1.0-0-dev \
  libsane-dev libv4l-dev libgphoto2-dev liblcms2-dev \
  libpcsclite-dev libacl1-dev libxml2-dev libxslt1-dev \
  libvulkan-dev libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev

# 安装交叉编译器
apt install -y gcc-mingw-w64 g++-mingw-w64
update-alternatives --set x86_64-w64-mingw32-gcc /usr/bin/x86_64-w64-mingw32-gcc-posix
update-alternatives --set x86_64-w64-mingw32-g++ /usr/bin/x86_64-w64-mingw32-g++-posix
update-alternatives --set i686-w64-mingw32-gcc /usr/bin/i686-w64-mingw32-gcc-posix
update-alternatives --set i686-w64-mingw32-g++ /usr/bin/i686-w64-mingw32-g++-posix

# 安装 ccache
apt install -y ccache

# 创建必要的符号链接
ln -sf /usr/bin/gcc-11 /usr/bin/gcc
ln -sf /usr/bin/g++-11 /usr/bin/g++

# 验证编译器
echo "验证编译器安装..."
gcc --version
g++ --version
x86_64-w64-mingw32-gcc --version
i686-w64-mingw32-gcc --version

# 清理
apt clean
rm -rf /var/lib/apt/lists/*

echo "chroot 环境设置完成"