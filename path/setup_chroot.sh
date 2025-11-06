#!/bin/bash
set -e

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

# 安装构建工具
apt install -y build-essential autoconf automake flex bison libtool \
  pkg-config gettext nasm yasm cmake meson ninja-build

# 安装开发库
apt install -y libx11-dev libfreetype6-dev libfontconfig1-dev \
  libasound2-dev libpulse-dev libdbus-1-dev libudev-dev \
  libsdl2-dev libgnutls28-dev libldap2-dev libjpeg-dev \
  libpng-dev libtiff5-dev libmpg123-dev \
  libcups2-dev libosmesa6-dev libpcap-dev libusb-1.0-0-dev \
  libsane-dev libv4l-dev libgphoto2-dev liblcms2-dev \
  libpcsclite-dev libacl1-dev libxml2-dev libxslt1-dev

# 安装交叉编译器
apt install -y gcc-mingw-w64 g++-mingw-w64
update-alternatives --set x86_64-w64-mingw32-gcc /usr/bin/x86_64-w64-mingw32-gcc-posix
update-alternatives --set x86_64-w64-mingw32-g++ /usr/bin/x86_64-w64-mingw32-g++-posix
update-alternatives --set i686-w64-mingw32-gcc /usr/bin/i686-w64-mingw32-gcc-posix
update-alternatives --set i686-w64-mingw32-g++ /usr/bin/i686-w64-mingw32-g++-posix

# 清理
apt clean
rm -rf /var/lib/apt/lists/*