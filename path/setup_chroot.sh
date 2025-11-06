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
echo "启用多架构支持..."
dpkg --add-architecture i386

# 更新包列表
apt update

# 安装基本工具和依赖
apt install -y software-properties-common apt-utils
add-apt-repository -y ppa:ubuntu-toolchain-r/test
apt update

# 安装基础构建工具
apt install -y build-essential autoconf automake flex bison libtool \
  pkg-config gettext nasm yasm cmake meson ninja-build

# 安装核心开发库（先安装64位）
echo "安装核心开发库（64位）..."
apt install -y \
  libc6-dev \
  libx11-dev libfreetype6-dev libfontconfig1-dev \
  libasound2-dev libpulse-dev libdbus-1-dev libudev-dev \
  libsdl2-dev libgnutls28-dev libldap2-dev \
  libjpeg-dev libpng-dev libtiff5-dev \
  libcups2-dev libosmesa6-dev libpcap-dev libusb-1.0-0-dev \
  libsane-dev libv4l-dev libgphoto2-dev liblcms2-dev \
  libpcsclite-dev libacl1-dev libxml2-dev libxslt1-dev \
  libvulkan-dev

# 安装 ORC 工具（解决依赖问题）- 使用正确的包名
echo "安装 ORC 工具..."
apt install -y liborc-0.4-0 liborc-0.4-dev

# 安装多媒体相关库（64位）
echo "安装多媒体库（64位）..."
apt install -y \
  libgstreamer1.0-dev \
  libgstreamer-plugins-base1.0-dev \
  libgstreamer-plugins-good1.0-dev \
  libgstreamer-plugins-bad1.0-dev \
  gstreamer1.0-plugins-base \
  gstreamer1.0-plugins-good \
  gstreamer1.0-plugins-bad \
  gstreamer1.0-libav \
  libmpg123-dev \
  libopenal-dev

# 安装 GCC 11 和构建工具
echo "安装 GCC 11..."
apt install -y gcc-11 g++-11 gcc-11-multilib g++-11-multilib
update-alternatives --install /usr/bin/gcc gcc /usr/bin/gcc-11 100
update-alternatives --install /usr/bin/g++ g++ /usr/bin/g++-11 100
update-alternatives --install /usr/bin/cc cc /usr/bin/gcc-11 100
update-alternatives --install /usr/bin/c++ c++ /usr/bin/g++-11 100

# 现在安装32位开发库
echo "安装32位开发库..."
apt install -y libc6-dev-i386

# 安装32位基础库
apt install -y \
  libx11-dev:i386 libfreetype6-dev:i386 libfontconfig1-dev:i386 \
  libasound2-dev:i386 libpulse-dev:i386 libdbus-1-dev:i386 \
  libudev-dev:i386 libsdl2-dev:i386 libgnutls28-dev:i386 \
  libjpeg-dev:i386 libpng-dev:i386 libtiff5-dev:i386 \
  libcups2-dev:i386 libosmesa6-dev:i386 \
  libxml2-dev:i386 libxslt1-dev:i386

# 安装32位 ORC 工具
apt install -y liborc-0.4-0:i386 liborc-0.4-dev:i386

# 安装32位多媒体库
echo "安装32位多媒体库..."
apt install -y \
  libgstreamer1.0-dev:i386 \
  libgstreamer-plugins-base1.0-dev:i386 \
  libgstreamer-plugins-good1.0-dev:i386 \
  libmpg123-dev:i386 \
  libopenal-dev:i386

# 安装交叉编译器
echo "安装交叉编译器..."
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

# 验证安装
echo "验证编译器安装..."
gcc --version
g++ --version
x86_64-w64-mingw32-gcc --version
i686-w64-mingw32-gcc --version

echo "验证32位库..."
find /usr/lib/i386-linux-gnu -name "libgstreamer*" 2>/dev/null | head -5
find /usr/lib/i386-linux-gnu -name "liborc*" 2>/dev/null | head -5

# 清理
apt clean
rm -rf /var/lib/apt/lists/*

echo "chroot 环境设置完成"