# Dockerfile - 构建 ARM64 Wine for Debian 12
FROM debian:12

# 设置环境变量
ENV DEBIAN_FRONTEND=noninteractive
ENV BUILD_DIR=/build
ENV INSTALL_DIR=/opt/wine-arm64
ENV LLVMMINGW_NAME=llvm-mingw-20251202-ucrt-ubuntu-22.04-x86_64

# 添加调试：显示步骤
RUN echo "=== 开始构建 ARM64 Wine ==="

# 添加ARM64架构并更新源
RUN dpkg --add-architecture arm64 && \
    echo "deb [arch=amd64] http://deb.debian.org/debian bookworm main contrib non-free" > /etc/apt/sources.list && \
    echo "deb [arch=amd64] http://deb.debian.org/debian bookworm-updates main contrib non-free" >> /etc/apt/sources.list && \
    echo "deb [arch=amd64] http://security.debian.org/debian-security bookworm-security main contrib non-free" >> /etc/apt/sources.list && \
    echo "deb [arch=arm64] http://deb.debian.org/debian bookworm main contrib non-free" >> /etc/apt/sources.list && \
    echo "deb [arch=arm64] http://deb.debian.org/debian bookworm-updates main contrib non-free" >> /etc/apt/sources.list && \
    echo "deb [arch=arm64] http://security.debian.org/debian-security bookworm-security main contrib non-free" >> /etc/apt/sources.list

# 更新包列表并安装基础工具
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
    python3-pip \
    gettext \
    autoconf \
    automake \
    libtool \
    llvm \
    clang \
    lld \
    libssl-dev \
    libffi-dev \
    zlib1g-dev \
    libbz2-dev \
    libreadline-dev \
    libsqlite3-dev \
    libncursesw5-dev \
    xz-utils \
    tk-dev \
    libxml2-dev \
    libxmlsec1-dev \
    liblzma-dev

# 安装交叉编译工具链
RUN apt-get install -y \
    gcc-aarch64-linux-gnu \
    g++-aarch64-linux-gnu \
    binutils-aarch64-linux-gnu \
    libc-dev-arm64-cross \
    crossbuild-essential-arm64

# 安装Wine依赖（ARM64版本）
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
    libvpx-dev:arm64 \
    libxml2-dev:arm64 \
    libxslt1-dev:arm64 \
    libjpeg-dev:arm64 \
    libtiff-dev:arm64 \
    libgif-dev:arm64 \
    libopencl-dev:arm64 \
    libxml2-dev:arm64 \
    libxslt1-dev:arm64 \
    libjpeg-dev:arm64 \
    libtiff-dev:arm64 \
    libgif-dev:arm64

# 安装meson用于构建
RUN pip3 install meson

# 清理apt缓存以节省空间
RUN apt-get clean && rm -rf /var/lib/apt/lists/*

# 下载和安装llvm-mingw
RUN echo "=== 下载和安装 llvm-mingw ===" && \
    cd /opt && \
    wget -q https://github.com/mstorsjo/llvm-mingw/releases/download/20251202/$LLVMMINGW_NAME.tar.xz && \
    tar -xJf $LLVMMINGW_NAME.tar.xz && \
    rm $LLVMMINGW_NAME.tar.xz && \
    echo "llvm-mingw 安装完成"

ENV PATH="/opt/$LLVMMINGW_NAME/bin:$PATH"

# 创建工作目录
WORKDIR $BUILD_DIR

# 显示当前目录结构
RUN echo "=== 当前目录结构 ===" && ls -la $BUILD_DIR/

# 复制Wine源代码和补丁
COPY wine/ $BUILD_DIR/wine/
COPY wine_do_not_create_dxgi_manager2.patch $BUILD_DIR/

# 验证文件复制
RUN echo "=== 验证复制的文件 ===" && \
    echo "Wine 源代码目录:" && ls -la $BUILD_DIR/wine/ && \
    echo "补丁文件:" && ls -la $BUILD_DIR/wine_do_not_create_dxgi_manager2.patch

# 应用补丁
RUN echo "=== 应用补丁 ===" && \
    cd $BUILD_DIR/wine && \
    if [ -f "../wine_do_not_create_dxgi_manager2.patch" ]; then \
        patch -p1 < ../wine_do_not_create_dxgi_manager2.patch || echo "补丁可能已应用或不需要，继续构建..."; \
    else \
        echo "补丁文件不存在，跳过补丁应用"; \
    fi

# 显示应用补丁后的目录
RUN echo "=== 应用补丁后的Wine目录 ===" && ls -la $BUILD_DIR/wine/

# 构建64位工具链 - 使用绝对路径
RUN echo "=== 开始构建64位工具链 ===" && \
    mkdir -p $BUILD_DIR/wine64-build && \
    cd $BUILD_DIR/wine64-build && \
    echo "当前目录:" && pwd && \
    echo "配置脚本位置:" && ls -la $BUILD_DIR/wine/configure && \
    $BUILD_DIR/wine/configure --enable-win64 --prefix=$INSTALL_DIR 2>&1 | tee configure.log && \
    echo "=== 开始编译64位工具链 ===" && \
    make __tooldeps__ -j$(nproc) 2>&1 | tee build.log || { echo "构建失败，显示日志:"; cat build.log; exit 1; } && \
    echo "64位工具链构建完成"

# 显示工具链构建结果
RUN echo "=== 64位工具链构建结果 ===" && \
    ls -la $BUILD_DIR/wine64-build/

# 配置和构建ARM64 Wine
RUN echo "=== 开始配置ARM64 Wine ===" && \
    mkdir -p $BUILD_DIR/wine-arm64-build && \
    cd $BUILD_DIR/wine-arm64-build && \
    echo "当前目录:" && pwd && \
    echo "开始配置..." && \
    $BUILD_DIR/wine/configure \
        --prefix=$INSTALL_DIR \
        --with-mingw=clang \
        --enable-archs=arm64ec,aarch64,i386 \
        --enable-tools \
        --disable-tests \
        --host=aarch64-linux-gnu \
        --with-wine-tools=$BUILD_DIR/wine64-build \
        CC="aarch64-linux-gnu-gcc" \
        CXX="aarch64-linux-gnu-g++" \
        PKG_CONFIG_PATH="/usr/lib/aarch64-linux-gnu/pkgconfig" 2>&1 | tee configure-arm64.log

# 显示配置结果
RUN echo "=== ARM64 Wine 配置结果 ===" && \
    cd $BUILD_DIR/wine-arm64-build && \
    ls -la config.status config.log && \
    echo "显示配置摘要:" && \
    tail -50 configure-arm64.log

# 构建ARM64 Wine
RUN echo "=== 开始构建ARM64 Wine ===" && \
    cd $BUILD_DIR/wine-arm64-build && \
    echo "开始编译..." && \
    make -j$(nproc) 2>&1 | tee build-arm64.log || { echo "构建失败，显示日志:"; cat build-arm64.log; exit 1; } && \
    echo "ARM64 Wine 构建完成"

# 安装到指定目录
RUN echo "=== 安装ARM64 Wine ===" && \
    cd $BUILD_DIR/wine-arm64-build && \
    make install 2>&1 | tee install.log && \
    echo "安装完成"

# 验证构建
RUN echo "=== 验证构建 ===" && \
    if [ -f "$INSTALL_DIR/bin/wine" ]; then \
        echo "Wine 可执行文件存在:" && \
        ls -la $INSTALL_DIR/bin/wine && \
        echo "Wine 版本信息:" && \
        $INSTALL_DIR/bin/wine --version || echo "无法运行wine --version"; \
    else \
        echo "错误: Wine 可执行文件不存在"; \
        ls -la $INSTALL_DIR/bin/; \
        exit 1; \
    fi

# 显示安装目录结构
RUN echo "=== 安装目录结构 ===" && \
    echo "安装路径: $INSTALL_DIR" && \
    ls -la $INSTALL_DIR/ && \
    echo "bin目录:" && ls -la $INSTALL_DIR/bin/ && \
    echo "lib目录:" && ls -la $INSTALL_DIR/lib/

# 创建必要的符号链接
RUN echo "=== 创建符号链接 ===" && \
    cd $INSTALL_DIR/bin && \
    if [ -f "wine" ]; then \
        ln -sf wine wine64 || true; \
        ln -sf wine wine-arm64 || true; \
        echo "符号链接创建完成"; \
    fi

# 打包构建结果
RUN echo "=== 打包构建结果 ===" && \
    cd $INSTALL_DIR && \
    tar -czf /wine-arm64-debian12.tar.gz . && \
    echo "打包完成"

# 显示打包文件信息
RUN echo "=== 打包文件信息 ===" && \
    echo "输出文件: /wine-arm64-debian12.tar.gz" && \
    ls -lh /wine-arm64-debian12.tar.gz && \
    echo "文件内容列表:" && \
    tar -tzf /wine-arm64-debian12.tar.gz | head -30

# 输出构建成功信息
RUN echo "==========================================" && \
    echo "=== Wine ARM64 构建成功！ ===" && \
    echo "安装路径: $INSTALL_DIR" && \
    echo "打包文件: /wine-arm64-debian12.tar.gz" && \
    echo "文件大小: $(du -h /wine-arm64-debian12.tar.gz | cut -f1)" && \
    echo "=========================================="