#!/bin/bash
set -e

echo "开始设置 32位 chroot 环境..."

# 设置软件源
cat > /etc/apt/sources.list << 'SOURCES'
deb http://archive.ubuntu.com/ubuntu/ bionic main universe multiverse
deb http://archive.ubuntu.com/ubuntu/ bionic-updates main universe multiverse
deb http://archive.ubuntu.com/ubuntu/ bionic-security main universe multiverse
SOURCES

# 更新包列表
apt update

# 安装基础工具
apt install -y software-properties-common apt-utils
add-apt-repository -y ppa:ubuntu-toolchain-r/test
apt update

# 分阶段安装，避免依赖冲突
echo "阶段1: 安装基础构建工具..."
apt install -y build-essential autoconf automake flex bison libtool \
  pkg-config gettext nasm yasm cmake meson ninja-build

echo "阶段2: 安装核心系统库..."
apt install -y libc6-dev libx11-dev libfreetype6-dev libfontconfig1-dev \
  libasound2-dev libpulse-dev libdbus-1-dev libudev-dev \
  libsdl2-dev libgnutls28-dev libldap2-dev libjpeg-dev \
  libpng-dev libtiff5-dev libmpg123-dev libopenal-dev

echo "阶段3: 安装多媒体库依赖..."
# 先安装 ORC 库和其他依赖
apt install -y liborc-0.4-0 liborc-0.4-dev \
  libvpx-dev libx264-dev libx265-dev libva-dev libdrm-dev

echo "阶段4: 安装 GStreamer 相关库..."
# 安装 GStreamer 核心包
apt install -y libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
  libgstreamer-plugins-good1.0-dev libgstreamer-plugins-bad1.0-dev \
  gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
  gstreamer1.0-plugins-bad gstreamer1.0-libav

echo "阶段5: 安装编解码器库..."
apt install -y libavcodec-dev libavformat-dev libavutil-dev libswresample-dev \
  libavfilter-dev libswscale-dev

echo "阶段6: 安装图形和显示库..."
apt install -y libwayland-dev libxkbcommon-dev libegl1-mesa-dev libgl1-mesa-dev \
  libgles2-mesa-dev libglu1-mesa-dev libxi-dev libxrandr-dev \
  libxfixes-dev libxcursor-dev libxinerama-dev libxcomposite-dev \
  libxdamage-dev libxxf86vm-dev libxt-dev libxmu-dev libxtst-dev

echo "阶段7: 安装 Vulkan 和硬件支持库..."
apt install -y vulkan-utils libvulkan1 || echo "Vulkan 包不可用，继续..."
apt install -y libcapi20-3 libcapi20-dev || echo "CAPI 包不可用，继续..."
apt install -y libcups2-dev libosmesa6-dev libpcap-dev libusb-1.0-0-dev \
  libsane-dev libv4l-dev libgphoto2-dev liblcms2-dev \
  libpcsclite-dev libacl1-dev libxml2-dev libxslt1-dev

echo "阶段8: 安装编译器和交叉编译器..."
# 安装 GCC 编译器
apt install -y gcc-9 g++-9 gcc-10 g++-10 || echo "较新 GCC 版本不可用，安装默认版本"
apt install -y gcc g++

# 安装32位交叉编译器（注意包名不同）
apt install -y gcc-mingw-w64-i686 g++-mingw-w64-i686

# 设置编译器替代
update-alternatives --install /usr/bin/gcc gcc /usr/bin/gcc-9 100
update-alternatives --install /usr/bin/g++ g++ /usr/bin/g++-9 100
update-alternatives --install /usr/bin/cc cc /usr/bin/gcc-9 100
update-alternatives --install /usr/bin/c++ c++ /usr/bin/g++-9 100

# 设置交叉编译器为 posix 版本
update-alternatives --set i686-w64-mingw32-gcc /usr/bin/i686-w64-mingw32-gcc-posix
update-alternatives --set i686-w64-mingw32-g++ /usr/bin/i686-w64-mingw32-g++-posix

echo "阶段9: 安装 ccache..."
apt install -y ccache

# 验证安装
echo "验证安装..."
gcc --version
g++ --version
i686-w64-mingw32-gcc --version

echo "验证 GStreamer 安装..."
pkg-config --modversion gstreamer-1.0
pkg-config --modversion gstreamer-plugins-base-1.0

# 清理
apt clean
rm -rf /var/lib/apt/lists/*

echo "32位 chroot 环境设置完成"