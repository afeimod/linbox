FROM debian:12

# 设置环境变量
ENV DEBIAN_FRONTEND=noninteractive
ENV BUILD_DIR=/build
ENV INSTALL_DIR=/opt/wine-arm64
ENV LLVMMINGW_NAME=llvm-mingw-20251202-ucrt-ubuntu-22.04-x86_64
ENV WINE_VERSION=wine-10.16

# 添加所有需要的架构
RUN dpkg --add-architecture arm64 && \
    dpkg --add-architecture amd64 && \
    echo "deb [arch=amd64] http://deb.debian.org/debian bookworm main contrib non-free" > /etc/apt/sources.list && \
    echo "deb [arch=amd64] http://deb.debian.org/debian bookworm-updates main contrib non-free" >> /etc/apt/sources.list && \
    echo "deb [arch=amd64] http://security.debian.org/debian-security bookworm-security main contrib non-free" >> /etc/apt/sources.list && \
    echo "deb [arch=arm64] http://deb.debian.org/debian bookworm main contrib non-free" >> /etc/apt/sources.list && \
    echo "deb [arch=arm64] http://deb.debian.org/debian bookworm-updates main contrib non-free" >> /etc/apt/sources.list && \
    echo "deb [arch=arm64] http://security.debian.org/debian-security bookworm-security main contrib non-free" >> /etc/apt/sources.list

# 安装基础工具（amd64架构）
RUN apt-get update && apt-get install -y \
    build-essential \
    git \
    wget \
    curl \
    ca-certificates \
    pkg-config \
    flex \
    bison \
    nasm \
    cmake \
    ninja-build \
    python3 \
    gettext \
    autoconf \
    automake \
    libtool \
    llvm \
    clang \
    lld

# 安装交叉编译工具链
RUN apt-get install -y \
    gcc-aarch64-linux-gnu \
    g++-aarch64-linux-gnu \
    binutils-aarch64-linux-gnu \
    libc-dev-arm64-cross

# 安装amd64架构的依赖（用于构建64位工具链）
RUN apt-get install -y \
    libx11-dev:amd64 \
    libfreetype-dev:amd64 \
    libfontconfig-dev:amd64 \
    libxinerama-dev:amd64 \
    libxcursor-dev:amd64 \
    libxi-dev:amd64 \
    libxrandr-dev:amd64 \
    libxrender-dev:amd64 \
    libxfixes-dev:amd64 \
    libxcomposite-dev:amd64 \
    libxshmfence-dev:amd64 \
    libxxf86vm-dev:amd64 \
    libwayland-dev:amd64 \
    libxkbcommon-dev:amd64 \
    libxkbregistry-dev:amd64 \
    libdbus-1-dev:amd64 \
    libsane-dev:amd64 \
    libusb-1.0-0-dev:amd64 \
    libv4l-dev:amd64 \
    libpulse-dev:amd64 \
    libgstreamer1.0-dev:amd64 \
    libgstreamer-plugins-base1.0-dev:amd64 \
    libgstreamer-plugins-bad1.0-dev:amd64 \
    libudev-dev:amd64 \
    libunwind-dev:amd64 \
    libsdl2-dev:amd64 \
    libfontconfig1-dev:amd64 \
    libkrb5-dev:amd64 \
    libvulkan-dev:amd64 \
    libgl-dev:amd64 \
    libgnutls28-dev:amd64 \
    libcups2-dev:amd64 \
    libepoxy-dev:amd64 \
    libgphoto2-dev:amd64 \
    liblcms2-dev:amd64 \
    libldap2-dev:amd64 \
    libopenal-dev:amd64 \
    libcapi20-dev:amd64 \
    libgsm1-dev:amd64 \
    libmpg123-dev:amd64 \
    libosmesa6-dev:amd64 \
    libpcap-dev:amd64 \
    libpng-dev:amd64 \
    libvkd3d-dev:amd64 \
    libva-dev:amd64 \
    libvpx-dev:amd64

# 安装ARM64架构的依赖（用于交叉编译）
RUN apt-get install -y \
    libx11-dev:arm64 \
    libfreetype-dev:arm64 \
    libfontconfig-dev:arm64 \
    libxinerama-dev:arm64 \
    libxcursor-dev:arm64 \
    libxi-dev:arm64 \
    libxrandr-dev:arm64 \
    libxrender-dev:arm64 \
    libxfixes-dev:arm64 \
    libxcomposite-dev:arm64 \
    libxshmfence-dev:arm64 \
    libxxf86vm-dev:arm64 \
    libwayland-dev:arm64 \
    libxkbcommon-dev:arm64 \
    libxkbregistry-dev:arm64 \
    libdbus-1-dev:arm64 \
    libsane-dev:arm64 \
    libusb-1.0-0-dev:arm64 \
    libv4l-dev:arm64 \
    libpulse-dev:arm64 \
    libgstreamer1.0-dev:arm64 \
    libgstreamer-plugins-base1.0-dev:arm64 \
    libgstreamer-plugins-bad1.0-dev:arm64 \
    libudev-dev:arm64 \
    libunwind-dev:arm64 \
    libsdl2-dev:arm64 \
    libfontconfig1-dev:arm64 \
    libkrb5-dev:arm64 \
    libvulkan-dev:arm64 \
    libgl-dev:arm64 \
    libgnutls28-dev:arm64 \
    libcups2-dev:arm64 \
    libepoxy-dev:arm64 \
    libgphoto2-dev:arm64 \
    liblcms2-dev:arm64 \
    libldap2-dev:arm64 \
    libopenal-dev:arm64 \
    libcapi20-dev:arm64 \
    libgsm1-dev:arm64 \
    libmpg123-dev:arm64 \
    libosmesa6-dev:arm64 \
    libpcap-dev:arm64 \
    libpng-dev:arm64 \
    libvkd3d-dev:arm64 \
    libva-dev:arm64 \
    libvpx-dev:arm64

# 下载和安装llvm-mingw
RUN cd /opt && \
    wget https://github.com/mstorsjo/llvm-mingw/releases/download/20251202/$LLVMMINGW_NAME.tar.xz && \
    tar -xJf $LLVMMINGW_NAME.tar.xz && \
    rm $LLVMMINGW_NAME.tar.xz

ENV PATH="/opt/$LLVMMINGW_NAME/bin:$PATH"

# 创建工作目录
WORKDIR $BUILD_DIR

# 下载Wine源代码
RUN echo "下载Wine源代码 (版本: $WINE_VERSION)..." && \
    git clone --depth 1 --branch $WINE_VERSION https://github.com/wine-mirror/wine.git wine-source

# 下载补丁
RUN echo "下载补丁文件..." && \
    wget -O wine_do_not_create_dxgi_manager2.patch https://github.com/afeimod/linbox/raw/main/path/wine_do_not_create_dxgi_manager2.patch

# 应用补丁
RUN cd wine-source && \
    patch -p1 < ../wine_do_not_create_dxgi_manager2.patch || echo "补丁可能已应用或不需要"

# 构建64位工具链
RUN mkdir -p wine64-build && \
    cd wine64-build && \
    ../wine-source/configure --enable-win64 --prefix=$INSTALL_DIR && \
    make __tooldeps__ -j$(nproc)

# 配置和构建ARM64 Wine
RUN mkdir -p wine-arm64-build && \
    cd wine-arm64-build && \
    ../wine-source/configure \
        --prefix=$INSTALL_DIR \
        --with-mingw=clang \
        --enable-archs=arm64ec,aarch64,i386 \
        --enable-tools \
        --disable-tests \
        --host=aarch64-linux-gnu \
        --with-wine-tools=$BUILD_DIR/wine64-build \
        CC=aarch64-linux-gnu-gcc \
        CXX=aarch64-linux-gnu-g++ \
        PKG_CONFIG_PATH=/usr/lib/aarch64-linux-gnu/pkgconfig

RUN cd wine-arm64-build && make -j$(nproc)

# 安装到指定目录
RUN cd wine-arm64-build && make install

# 验证构建
RUN $INSTALL_DIR/bin/wine --version

# 打包构建结果
RUN tar -czf /wine-arm64-debian12.tar.gz -C $INSTALL_DIR .

# 输出构建信息
RUN echo "Wine ARM64构建完成！" && \
    echo "安装路径: $INSTALL_DIR" && \
    echo "输出文件: /wine-arm64-debian12.tar.gz" && \
    ls -la $INSTALL_DIR/bin/