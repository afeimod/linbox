#!/bin/bash
echo "安装完整的构建依赖..."

# 基础构建工具
apt install -y build-essential autoconf automake libtool pkg-config
apt install -y flex bison cmake ninja-build meson

# Gettext 和相关工具
apt install -y gettext gettext-base gettext-tools autopoint
apt install -y libgettextpo-dev

# 编译器和工具链
apt install -y gcc-14 g++-14 gcc-multilib g++-multilib
apt install -y ccache make cmake

# 必要的库
apt install -y libc6-dev libc6-dev-i386 libc6-dev-x32
apt install -y linux-libc-dev

# 检查关键工具
echo "检查构建工具..."
which msgfmt || echo "msgfmt 未找到"
which make || echo "make 未找到"
which gcc || echo "gcc 未找到"
which pkg-config || echo "pkg-config 未找到"

echo "依赖安装完成"
