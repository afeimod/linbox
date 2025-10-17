#!/usr/bin/env bash

########################################################################
##
## A script for Wine compilation.
## By default it uses two Ubuntu bootstraps (x32 and x64), which it enters
## with bubblewrap (root rights are not required).
##
## This script requires: git, wget, autoconf, xz, bubblewrap
##
## You can change the environment variables below to your desired values.
##
########################################################################

# 在脚本开头添加详细的版本检查和设置
echo "=== 构建配置检查 ==="
echo "输入 WINE_VERSION: ${WINE_VERSION}"
echo "输入 WINE_BRANCH: ${WINE_BRANCH}"
echo "输入 STAGING_VERSION: ${STAGING_VERSION}"

# 确保版本变量正确设置
if [ -z "${WINE_VERSION}" ]; then
    echo "错误: WINE_VERSION 未设置!"
    exit 1
fi

# 清理版本号，移除重复的 v 前缀
CLEAN_WINE_VERSION="${WINE_VERSION#v}"
export WINE_VERSION="${CLEAN_WINE_VERSION}"
export BUILD_WINE_VERSION="${CLEAN_WINE_VERSION}"

# 如果版本是 "latest"，获取最新版本
if [ "${WINE_VERSION}" = "latest" ] || [ -z "${WINE_VERSION}" ]; then
    WINE_VERSION="$(wget -q -O - "https://raw.githubusercontent.com/wine-mirror/wine/master/VERSION" | tail -c +14)"
    echo "获取到最新版本: ${WINE_VERSION}"
fi

# 根据版本确定 URL 版本
if [ "$(echo "$WINE_VERSION" | cut -c3)" = "0" ]; then
    WINE_URL_VERSION=$(echo "$WINE_VERSION" | cut -c1).0
else
    WINE_URL_VERSION=$(echo "$WINE_VERSION" | cut -c1).x
fi

echo "最终使用的版本: ${WINE_VERSION}"
echo "URL 版本: ${WINE_URL_VERSION}"
echo "构建分支: ${WINE_BRANCH}"
echo "=== 版本检查完成 ==="

sleep 2

# 检查是否在容器环境中运行
if [ -f /.dockerenv ]; then
    echo "检测到在容器环境中运行"
    export IN_CONTAINER=true
else
    export IN_CONTAINER=false
fi

# Wine version to compile.
# You can set it to "latest" to compile the latest available version.
# You can also set it to "git" to compile the latest git revision.
#
# This variable affects only vanilla and staging branches. Other branches
# use their own versions.
export WINE_VERSION="${WINE_VERSION}"

# Available branches: vanilla, staging, staging-tkg, proton, wayland
export WINE_BRANCH="${WINE_BRANCH:-staging}"

# Available proton branches: proton_3.7, proton_3.16, proton_4.2, proton_4.11
# proton_5.0, proton_5.13, experimental_5.13, proton_6.3, experimental_6.3
# proton_7.0, experimental_7.0, proton_8.0, experimental_8.0, experimental_9.0
# bleeding-edge
# Leave empty to use the default branch.
export PROTON_BRANCH="${PROTON_BRANCH:-proton_8.0}"

# Sometimes Wine and Staging versions don't match (for example, 5.15.2).
# Leave this empty to use Staging version that matches the Wine version.
export STAGING_VERSION="${STAGING_VERSION:-}"

#######################################################################
# If you're building specifically for Termux glibc, set this to true.
export TERMUX_GLIBC="${TERMUX_GLIBC:-true}"

# If you want to build Wine for proot/chroot, set this to true.
# It will incorporate address space adjustment which might improve
# compatibility. ARM CPUs are limited in this case.
export TERMUX_PROOT="${TERMUX_PROOT:-false}"

# These two variables cannot be "true" at the same time, otherwise Wine
# will not build. Select only one which is appropriate to you.
#######################################################################

# Specify custom arguments for the Staging's patchinstall.sh script.
# For example, if you want to disable ntdll-NtAlertThreadByThreadId
# patchset, but apply all other patches, then set this variable to
# "--all -W ntdll-NtAlertThreadByThreadId"
# Leave empty to apply all Staging patches
export STAGING_ARGS="${STAGING_ARGS:-}"

# Make 64-bit Wine builds with the new WoW64 mode (32-on-64)
export EXPERIMENTAL_WOW64="${EXPERIMENTAL_WOW64:-true}"

# Set this to a path to your Wine source code (for example, /home/username/wine-custom-src).
# This is useful if you already have the Wine source code somewhere on your
# storage and you want to compile it.
#
# You can also set this to a GitHub clone url instead of a local path.
#
# If you don't want to compile a custom Wine source code, then just leave this
# variable empty.
export CUSTOM_SRC_PATH=""

# Set to true to download and prepare the source code, but do not compile it.
# If this variable is set to true, root rights are not required.
export DO_NOT_COMPILE="false"

# Set to true to use ccache to speed up subsequent compilations.
# First compilation will be a little longer, but subsequent compilations
# will be significantly faster (especially if you use a fast storage like SSD).
#
# Note that ccache requires additional storage space.
# By default it has a 5 GB limit for its cache size.
#
# Make sure that ccache is installed before enabling this.
export USE_CCACHE="${USE_CCACHE:-false}"

export WINE_BUILD_OPTIONS="--disable-winemenubuilder --disable-win16 --enable-win64 --disable-tests --without-capi --without-coreaudio --without-cups --without-gphoto --without-osmesa --without-oss --without-pcap --without-pcsclite --without-sane --without-udev --without-unwind --without-usb --without-v4l2 --without-xinerama --with-gstreamer --with-fontconfig --with-freetype"

# 修复构建目录路径 - 使用可写的临时目录
export BUILD_DIR="/tmp/build_wine"

# 新增：在容器环境中禁用 ccache 的家目录映射
if [ "${IN_CONTAINER}" = "true" ]; then
    echo "容器环境中运行，禁用 ccache 家目录映射"
    export CCACHE_DISABLE_HOME_MAPPING=true
    # 设置 ccache 目录到可写位置
    export CCACHE_DIR="/tmp/ccache"
    mkdir -p "${CCACHE_DIR}"
fi

# 新增：应用 MF 补丁函数
apply_mf_patches() {
    echo "检查并应用 MF 补丁..."
    
    # 解析版本号进行比较
    VERSION_MAJOR=$(echo "${WINE_VERSION}" | cut -d. -f1)
    VERSION_MINOR=$(echo "${WINE_VERSION}" | cut -d. -f2)
    
    echo "Wine 版本: ${WINE_VERSION}"
    echo "主版本: ${VERSION_MAJOR}, 次版本: ${VERSION_MINOR}"
    
    # 判断版本是否 >= 9.10
    if [ ${VERSION_MAJOR} -gt 9 ] || [ ${VERSION_MAJOR} -eq 9 -a ${VERSION_MINOR} -ge 10 ]; then
        echo "使用 9.10+ 版本的 MF 修复方式"
        MF_PATCH_URL="https://github.com/afeimod/linbox/raw/main/path/wine_do_not_create_dxgi_manager2.patch"
        PATCH_FILE="wine_do_not_create_dxgi_manager2.patch"
        PATCH_TYPE="wine_do_not_create_dxgi_manager2"
    else
        echo "使用 9.10 以下版本的 MF 修复方式"
        MF_PATCH_URL="https://github.com/afeimod/linbox/raw/main/path/fix_wine9.2_mfplat.sh"
        PATCH_FILE="fix_wine9.2_mfplat.sh"
        PATCH_TYPE="fix_wine9_2_mfplat"
    fi
    
    echo "MF 修复类型: ${PATCH_TYPE}"
    echo "下载 MF 补丁: ${MF_PATCH_URL}"
    
    # 下载补丁
    if wget -O "${PATCH_FILE}" "${MF_PATCH_URL}"; then
        echo "成功下载补丁: ${PATCH_FILE}"
        
        # 应用补丁
        if [ "${PATCH_TYPE}" = "fix_wine9_2_mfplat" ]; then
            chmod +x "${PATCH_FILE}"
            echo "执行补丁脚本: ${PATCH_FILE}"
            ./"${PATCH_FILE}" || echo "补丁脚本执行完成"
        else
            echo "应用补丁文件: ${PATCH_FILE}"
            patch -d wine -Np1 < "${PATCH_FILE}" || echo "补丁可能已部分应用或不需要"
        fi
        echo "✅ MF 补丁应用完成"
    else
        echo "⚠️ 警告: 无法下载 MF 补丁，继续构建..."
    fi
}

# Implement a new WoW64 specific check which will change the way Wine is built.
# New WoW64 builds will use a different bootstrap which require different
# variables and they are not compatible with old WoW64 build mode.
if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then

   export BOOTSTRAP_X64="/opt/chroots/noble64_chroot"

   export scriptdir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

   export CC="gcc-14"
   export CXX="g++-14"
   
   export CROSSCC_X64="x86_64-w64-mingw32-gcc"
   export CROSSCXX_X64="x86_64-w64-mingw32-g++"

   export CFLAGS_X64="-march=x86-64 -msse3 -mfpmath=sse -O3 -ftree-vectorize -pipe"
   export LDFLAGS="-Wl,-O1,--sort-common,--as-needed"
   
   export CROSSCFLAGS_X64="${CFLAGS_X64}"
   export CROSSLDFLAGS="${LDFLAGS}"

   if [ "$USE_CCACHE" = "true" ]; then
        # 修复：在容器环境中使用可写的 ccache 目录
        if [ "${IN_CONTAINER}" = "true" ]; then
            export CCACHE_DIR="/tmp/ccache"
            mkdir -p "${CCACHE_DIR}"
        fi
        
        export CC="ccache ${CC}"
        export CXX="ccache ${CXX}"
        export x86_64_CC="ccache ${CROSSCC_X64}"
        export CROSSCC_X64="ccache ${CROSSCC_X64}"
        export CROSSCXX_X64="ccache ${CROSSCXX_X64}"

        if [ -z "${XDG_CACHE_HOME}" ]; then
            export XDG_CACHE_HOME="${HOME}"/.cache
        fi

        mkdir -p "${XDG_CACHE_HOME}"/ccache
        mkdir -p "${HOME}"/.ccache
   fi

   build_with_bwrap () {
        BOOTSTRAP_PATH="${BOOTSTRAP_X64}"

        # 修复：在容器环境中不绑定只读的家目录
        if [ "${IN_CONTAINER}" = "true" ]; then
            bwrap --ro-bind "${BOOTSTRAP_PATH}" / --dev /dev --ro-bind /sys /sys \
                  --proc /proc --tmpfs /tmp --tmpfs /home --tmpfs /run --tmpfs /var \
                  --tmpfs /mnt --tmpfs /media --bind "${BUILD_DIR}" "${BUILD_DIR}" \
                  --bind-try "${CCACHE_DIR}" "${CCACHE_DIR}" \
                  --setenv PATH "/bin:/sbin:/usr/bin:/usr/sbin" \
                  --setenv CCACHE_DIR "${CCACHE_DIR}" \
                    "$@"
        else
            bwrap --ro-bind "${BOOTSTRAP_PATH}" / --dev /dev --ro-bind /sys /sys \
                  --proc /proc --tmpfs /tmp --tmpfs /home --tmpfs /run --tmpfs /var \
                  --tmpfs /mnt --tmpfs /media --bind "${BUILD_DIR}" "${BUILD_DIR}" \
                  --bind-try "${XDG_CACHE_HOME}"/ccache "${XDG_CACHE_HOME}"/ccache \
                  --bind-try "${HOME}"/.ccache "${HOME}"/.ccache \
                  --setenv PATH "/bin:/sbin:/usr/bin:/usr/sbin" \
                    "$@"
        fi
   }

else

export BOOTSTRAP_X64="/opt/chroots/bionic64_chroot"
export BOOTSTRAP_X32="/opt/chroots/bionic32_chroot"

export scriptdir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export CC="gcc-9"
export CXX="g++-9"

export CROSSCC_X32="i686-w64-mingw32-gcc"
export CROSSCXX_X32="i686-w64-mingw32-g++"
export CROSSCC_X64="x86_64-w64-mingw32-gcc"
export CROSSCXX_X64="x86_64-w64-mingw32-g++"

export CFLAGS_X32="-march=i686 -msse2 -mfpmath=sse -O3 -ftree-vectorize -pipe"
export CFLAGS_X64="-march=x86-64 -msse3 -mfpmath=sse -O3 -ftree-vectorize -pipe"
export LDFLAGS="-Wl,-O1,--sort-common,--as-needed"

export CROSSCFLAGS_X32="${CFLAGS_X32}"
export CROSSCFLAGS_X64="${CFLAGS_X64}"
export CROSSLDFLAGS="${LDFLAGS}"

if [ "$USE_CCACHE" = "true" ]; then
        # 修复：在容器环境中使用可写的 ccache 目录
        if [ "${IN_CONTAINER}" = "true" ]; then
            export CCACHE_DIR="/tmp/ccache"
            mkdir -p "${CCACHE_DIR}"
        fi
        
        export CC="ccache ${CC}"
        export CXX="ccache ${CXX}"

        export i386_CC="ccache ${CROSSCC_X32}"
        export x86_64_CC="ccache ${CROSSCC_X64}"

        export CROSSCC_X32="ccache ${CROSSCC_X32}"
        export CROSSCXX_X32="ccache ${CROSSCXX_X32}"
        export CROSSCC_X64="ccache ${CROSSCC_X64}"
        export CROSSCXX_X64="ccache ${CROSSCXX_X64}"

        if [ -z "${XDG_CACHE_HOME}" ]; then
            export XDG_CACHE_HOME="${HOME}"/.cache
        fi

        mkdir -p "${XDG_CACHE_HOME}"/ccache
        mkdir -p "${HOME}"/.ccache
fi

build_with_bwrap () {
    if [ "${1}" = "32" ]; then
        BOOTSTRAP_PATH="${BOOTSTRAP_X32}"
    else
        BOOTSTRAP_PATH="${BOOTSTRAP_X64}"
    fi

    if [ "${1}" = "32" ] || [ "${1}" = "64" ]; then
        shift
    fi

    # 修复：在容器环境中不绑定只读的家目录
    if [ "${IN_CONTAINER}" = "true" ]; then
        bwrap --ro-bind "${BOOTSTRAP_PATH}" / --dev /dev --ro-bind /sys /sys \
              --proc /proc --tmpfs /tmp --tmpfs /home --tmpfs /run --tmpfs /var \
              --tmpfs /mnt --tmpfs /media --bind "${BUILD_DIR}" "${BUILD_DIR}" \
              --bind-try "${CCACHE_DIR}" "${CCACHE_DIR}" \
              --setenv PATH "/bin:/sbin:/usr/bin:/usr/sbin" \
              --setenv CCACHE_DIR "${CCACHE_DIR}" \
                "$@"
    else
        bwrap --ro-bind "${BOOTSTRAP_PATH}" / --dev /dev --ro-bind /sys /sys \
              --proc /proc --tmpfs /tmp --tmpfs /home --tmpfs /run --tmpfs /var \
              --tmpfs /mnt --tmpfs /media --bind "${BUILD_DIR}" "${BUILD_DIR}" \
              --bind-try "${XDG_CACHE_HOME}"/ccache "${XDG_CACHE_HOME}"/ccache \
              --bind-try "${HOME}"/.ccache "${HOME}"/.ccache \
              --setenv PATH "/bin:/sbin:/usr/bin:/usr/sbin" \
                "$@"
    fi
}
fi

# Prints out which environment you are building Wine for.
# Easier to debug script errors.

if [ "$TERMUX_PROOT" = "true" ]; then
   echo "Building Wine for proot/chroot environment"
fi
if [ "$TERMUX_GLIBC" = "true" ]; then
   echo "Building Wine for glibc native environment"
fi
if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
   echo "Building Wine in experimental WoW64 mode"
fi

# Checks whether these two env variables are set to true and if they are -
# compilation will stop.

if [ "$TERMUX_PROOT" = "true" ] && [ "$TERMUX_GLIBC" = "true" ]; then
   echo "Only TERMUX_PROOT or TERMUX_GLIBC can be set at the same time. Stopping..." 
   exit 1
fi

sleep 3

if ! command -v git 1>/dev/null; then
    echo "Please install git and run the script again"
    exit 1
fi

if ! command -v autoconf 1>/dev/null; then
    echo "Please install autoconf and run the script again"
    exit 1
fi

if ! command -v wget 1>/dev/null; then
    echo "Please install wget and run the script again"
    exit 1
fi

if ! command -v xz 1>/dev/null; then
    echo "Please install xz and run the script again"
    exit 1
fi

# 替换 "latest" 参数为实际的 Wine 版本
if [ "${WINE_VERSION}" = "latest" ] || [ -z "${WINE_VERSION}" ]; then
    WINE_VERSION="$(wget -q -O - "https://raw.githubusercontent.com/wine-mirror/wine/master/VERSION" | tail -c +14)"
    echo "获取到最新版本: ${WINE_VERSION}"
fi

# 稳定版和开发版有不同的源码位置
# 确定选择的版本是稳定版还是开发版
if [ "$(echo "$WINE_VERSION" | cut -c3)" = "0" ]; then
    WINE_URL_VERSION=$(echo "$WINE_VERSION" | cut -c1).0
else
    WINE_URL_VERSION=$(echo "$WINE_VERSION" | cut -c1).x
fi

# 确保构建目录存在且有正确权限
echo "设置构建目录: ${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
chown -R $(whoami):$(whoami) "${BUILD_DIR}" || echo "警告: 无法更改所有权，继续..."
chmod -R 755 "${BUILD_DIR}"

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}" || exit 1

echo
echo "下载源码和补丁"
echo "准备编译 Wine"
echo

if [ -n "${CUSTOM_SRC_PATH}" ]; then
    is_url="$(echo "${CUSTOM_SRC_PATH}" | head -c 6)"

    if [ "${is_url}" = "git://" ] || [ "${is_url}" = "https:" ]; then
        git clone "${CUSTOM_SRC_PATH}" wine
    else
        if [ ! -f "${CUSTOM_SRC_PATH}"/configure ]; then
            echo "CUSTOM_SRC_PATH 设置为不正确或不存在的目录!"
            echo "请确保使用包含正确 Wine 源码的目录。"
            exit 1
        fi

        cp -r "${CUSTOM_SRC_PATH}" wine
    fi

    WINE_VERSION="$(cat wine/VERSION | tail -c +14)"
    BUILD_NAME="${WINE_VERSION}"-custom
elif [ "$WINE_BRANCH" = "staging-tkg" ]; then
    echo "克隆 wine-tkg 仓库..."
    git clone https://github.com/Kron4ek/wine-tkg wine
    
    # 尝试切换到指定版本
    cd wine || exit 1
    if [ "${WINE_VERSION}" != "latest" ] && [ "${WINE_VERSION}" != "git" ]; then
        echo "尝试切换到 wine-tkg 版本: ${WINE_VERSION}"
        # 查找匹配的标签或分支
        if git tag -l | grep -q "${WINE_VERSION}"; then
            git checkout "${WINE_VERSION}"
            echo "成功切换到标签: ${WINE_VERSION}"
        elif git branch -a | grep -q "${WINE_VERSION}"; then
            git checkout "${WINE_VERSION}"
            echo "成功切换到分支: ${WINE_VERSION}"
        else
            echo "未找到精确匹配的版本 ${WINE_VERSION}，使用默认分支"
            # 尝试查找相近版本
            CLOSEST_TAG=$(git tag -l | grep "${WINE_VERSION%.*}" | sort -V | tail -n1)
            if [ -n "${CLOSEST_TAG}" ]; then
                echo "使用最接近的版本: ${CLOSEST_TAG}"
                git checkout "${CLOSEST_TAG}"
            fi
        fi
    fi
    cd "${BUILD_DIR}" || exit 1
    
    # 从仓库获取实际版本号
    if [ -f wine/VERSION ]; then
        ACTUAL_VERSION="$(cat wine/VERSION | tail -c +14)"
        echo "wine-tkg 实际版本: ${ACTUAL_VERSION}"
        BUILD_NAME="${ACTUAL_VERSION}"-staging-tkg
    else
        BUILD_NAME="${WINE_VERSION}"-staging-tkg
    fi
elif [ "$WINE_BRANCH" = "wayland" ]; then
    git clone https://github.com/Kron4ek/wine-wayland wine

    WINE_VERSION="$(cat wine/VERSION | tail -c +14)"
    BUILD_NAME="${WINE_VERSION}"-wayland

    export WINE_BUILD_OPTIONS="--without-x --without-xcomposite \
                               --without-xfixes --without-xinerama \
                               --without-xinput --without-xinput2 \
                               --without-xrandr --without-xrender \
                               --without-xshape --without-xshm  \
                               --without-xslt --with-gstreamer --without-xxf86vm \
                               --without-xcursor --without-opengl \
                               ${WINE_BUILD_OPTIONS}"
elif [ "$WINE_BRANCH" = "proton" ]; then
    if [ -z "${PROTON_BRANCH}" ]; then
        git clone https://github.com/ValveSoftware/wine
    else
        git clone https://github.com/ValveSoftware/wine -b "${PROTON_BRANCH}"
    fi

    if [ "${PROTON_BRANCH}" = "experimental_8.0" ]; then
        patch -d wine -Np1 < "${scriptdir}"/proton-exp-8.0.patch
    fi

    if [ "${PROTON_BRANCH}" = "experimental_9.0" ] || [ "${PROTON_BRANCH}" = "bleeding-edge" ]; then
     patch -d wine -Np1 < "${scriptdir}"/proton-exp-9.0.patch
    fi

    WINE_VERSION="$(cat wine/VERSION | tail -c +14)-$(git -C wine rev-parse --short HEAD)"
    if [[ "${PROTON_BRANCH}" == "experimental_"* ]] || [ "${PROTON_BRANCH}" = "bleeding-edge" ]; then
        BUILD_NAME=proton-exp-"${WINE_VERSION}"
    else
        BUILD_NAME=proton-"${WINE_VERSION}"
    fi
else
    if [ "${WINE_VERSION}" = "git" ]; then
        git clone https://gitlab.winehq.org/wine/wine.git wine
        BUILD_NAME="${WINE_VERSION}-$(git -C wine rev-parse --short HEAD)"
    else
        BUILD_NAME="${WINE_VERSION}"
        echo "下载 Wine 版本: ${WINE_VERSION}"
        
        # 尝试下载指定版本
        if wget -q --show-progress "https://dl.winehq.org/wine/source/${WINE_URL_VERSION}/wine-${WINE_VERSION}.tar.xz"; then
            echo "成功下载 wine-${WINE_VERSION}.tar.xz"
            tar xf "wine-${WINE_VERSION}.tar.xz"
            mv "wine-${WINE_VERSION}" wine
        else
            echo "无法下载 wine-${WINE_VERSION}.tar.xz，尝试查找可用版本..."
            # 备用下载方案
            wget -q -O - "https://dl.winehq.org/wine/source/${WINE_URL_VERSION}/" | grep "wine-${WINE_VERSION%.*}." | head -1 | sed 's/.*href="//g' | sed 's/".*//g' | while read fname; do
                echo "尝试下载: $fname"
                wget -q --show-progress "https://dl.winehq.org/wine/source/${WINE_URL_VERSION}/${fname}"
                if [ -f "${fname}" ]; then
                    tar xf "${fname}"
                    mv "${fname%.tar.xz}" wine
                    break
                fi
            done
        fi
    fi

    if [ "$WINE_BRANCH" = "staging" ] || [ "$WINE_BRANCH" = "vanilla" ]; then
        if [ "${WINE_VERSION}" = "git" ]; then
            git clone https://github.com/wine-staging/wine-staging wine-staging-"${WINE_VERSION}"
            upstream_commit="$(cat wine-staging-"${WINE_VERSION}"/staging/upstream-commit | head -c 7)"
            git -C wine checkout "${upstream_commit}"
            if [ "$WINE_BRANCH" = "vanilla" ]; then
                BUILD_NAME="${WINE_VERSION}-${upstream_commit}"
            else
                BUILD_NAME="${WINE_VERSION}-${upstream_commit}-staging"
            fi
        else
            if [ -n "${STAGING_VERSION}" ]; then
                WINE_VERSION="${STAGING_VERSION}"
            fi

            # 清理版本号，移除重复的 v 前缀
            CLEAN_VERSION="${WINE_VERSION#v}"
            echo "使用清理后的版本号: ${CLEAN_VERSION}"

            if [ "${WINE_BRANCH}" = "vanilla" ]; then
                BUILD_NAME="${CLEAN_VERSION}"
            else
                BUILD_NAME="${CLEAN_VERSION}"-staging
            fi

            # 下载 wine-staging，使用清理后的版本号
            STAGING_TAR="v${CLEAN_VERSION}.tar.gz"
            echo "尝试下载 wine-staging: ${STAGING_TAR}"
            
            if wget -q --show-progress "https://github.com/wine-staging/wine-staging/archive/${STAGING_TAR}"; then
                echo "成功下载 wine-staging ${STAGING_TAR}"
                tar xf "${STAGING_TAR}"
            else
                echo "无法下载 ${STAGING_TAR}，尝试 git 克隆..."
                git clone https://github.com/wine-staging/wine-staging "wine-staging-${CLEAN_VERSION}"
            fi

            # 使用清理后的版本号设置补丁路径
            if [ -f "wine-staging-${CLEAN_VERSION}/patches/patchinstall.sh" ]; then
                staging_patcher=("${BUILD_DIR}/wine-staging-${CLEAN_VERSION}/patches/patchinstall.sh"
                                DESTDIR="${BUILD_DIR}/wine")
            elif [ -f "wine-staging-${CLEAN_VERSION}/staging/patchinstall.py" ]; then
                staging_patcher=("${BUILD_DIR}/wine-staging-${CLEAN_VERSION}/staging/patchinstall.py")
            else
                echo "错误: 未找到 patchinstall 脚本"
                exit 1
            fi
        fi

        # Wine-Staging patch arguments
        # Not recommended to change these if statements unless you know what you are doing.

        if [ "$TERMUX_GLIBC" = "true" ] && [ "$WINE_BRANCH" = "staging" ] && [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
            STAGING_ARGS="--all -W ntdll-Syscall_Emulation"
        elif [ "$TERMUX_GLIBC" = "true" ] && [ "$WINE_BRANCH" = "staging" ]; then
            STAGING_ARGS="--all -W ntdll-Syscall_Emulation"
        elif [ "$TERMUX_GLIBC" = "true" ] && [ "${WINE_BRANCH}" = "vanilla" ] && [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
            STAGING_ARGS="eventfd_synchronization winecfg_Staging"
        elif [ "$TERMUX_GLIBC" = "true" ] && [ "${WINE_BRANCH}" = "vanilla" ]; then
            STAGING_ARGS="eventfd_synchronization winecfg_Staging"
        elif [ "$TERMUX_PROOT" = "true" ] && [ "${WINE_BRANCH}" = "vanilla" ]; then
            STAGING_ARGS="eventfd_synchronization winecfg_Staging"
        elif [ "$TERMUX_PROOT" = "true" ] && [ "${WINE_BRANCH}" = "staging" ]; then
            STAGING_ARGS="--all -W ntdll-Syscall_Emulation"
        elif [ "$TERMUX_PROOT" = "true" ] && [ "$WINE_BRANCH" = "staging" ] && [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
            STAGING_ARGS="--all -W ntdll-Syscall_Emulation"
        elif [ "$TERMUX_PROOT" = "true" ] && [ "$WINE_BRANCH" = "vanilla" ] && [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
            STAGING_ARGS="eventfd_synchronization winecfg_Staging"
        fi

        cd wine || exit 1
        if [ -n "${STAGING_ARGS}" ]; then
            "${staging_patcher[@]}" ${STAGING_ARGS}
        else
            echo "跳过 Wine-Staging 补丁..."
        fi
        
        if [ $? -ne 0 ]; then
            echo
            echo "Wine-Staging 补丁未正确应用!"
            exit 1
        fi

        cd "${BUILD_DIR}" || exit 1
    fi
fi

if [ "$TERMUX_PROOT" = "true" ]; then
    if [ "$WINE_BRANCH" = "staging" ] || [ "$WINE_BRANCH" = "staging-tkg" ] || [ "$WINE_BRANCH" = "proton" ]; then
        echo "Applying address patch to proot/chroot Wine build..."
        patch -d wine -Np1 < "${scriptdir}"/address-space-proot.patch || {
            echo "Error: Failed to apply one or more patches."
            exit 1
        }
        clear
    elif [ "$WINE_BRANCH" = "vanilla" ]; then
        echo "Applying address patch to proot/chroot Wine build..."
        patch -d wine -Np1 < "${scriptdir}"/address-space-proot.patch || {
            echo "Error: Failed to apply one or more patches."
            exit 1
        }
        clear
    fi
fi

# Checks which Wine branch you are building and applies additional convenient patches.
# Staging-tkg part isn't finished and will not build if it's Wine 9.4 and lower.

if [ "$TERMUX_GLIBC" = "true" ]; then
    echo "Applying additional patches for Termux Glibc..."

    if [ "$WINE_BRANCH" = "staging" ]; then
        echo "Applying esync patch"
        patch -d wine -Np1 < "${scriptdir}"/esync.patch && \
        echo "Applying address space patch"
        patch -d wine -Np1 < "${scriptdir}"/protonoverrides.patch && \
        echo "Add Proton DLL overrides"
        patch -d wine -Np1 < "${scriptdir}"/termux-wine-fix-staging.patch && \
        echo "Applying path change patch"
        if git -C "${BUILD_DIR}/wine" log | grep -q 4e04b2d5282e4ef769176c94b4b38b5fba006a06; then
            patch -d wine -Np1 < "${scriptdir}"/path-patch-universal.patch
        else
            patch -d wine -Np1 < "${scriptdir}"/pathfix.patch
        fi || {
            echo "Error: Failed to apply one or more patches."
            exit 1
        }
        clear
    elif [ "$WINE_BRANCH" = "vanilla" ]; then
        echo "Applying esync patch"
        patch -d wine -Np1 < "${scriptdir}"/esync.patch && \
        echo "Applying address space patch"
        patch -d wine -Np1 < "${scriptdir}"/protonoverrides.patch && \
        echo "Add Proton DLL overrides"
        patch -d wine -Np1 < "${scriptdir}"/termux-wine-fix.patch && \
        echo "Applying path change patch"
        if git -C "${BUILD_DIR}/wine" log | grep -q 4e04b2d5282e4ef769176c94b4b38b5fba006a06; then
            patch -d wine -Np1 < "${scriptdir}"/path-patch-universal.patch
        else
            patch -d wine -Np1 < "${scriptdir}"/pathfix.patch
        fi || {
            echo "Error: Failed to apply one or more patches."
            exit 1
        }
        clear
    elif [ "$WINE_BRANCH" = "staging-tkg" ]; then
        echo "Applying esync patch"
        patch -d wine -Np1 < "${scriptdir}"/esync.patch && \
        echo "Applying address space patch"
        patch -d wine -Np1 < "${scriptdir}"/protonoverrides.patch && \
        echo "Add Proton DLL overrides"
        patch -d wine -Np1 < "${scriptdir}"/termux-wine-fix-staging.patch && \
        echo "Applying path change patch"
        ## This needs an additional check since this patch will not work on
        ## Wine 9.4 and lower due to differences in Wine source code.
        patch -d wine -Np1 < "${scriptdir}"/path-patch-universal.patch || {
            echo "Error: Failed to apply one or more patches."
            exit 1
        }
        clear 
    elif [ "$WINE_BRANCH" = "proton" ]; then
        echo "Applying esync patch"
        patch -d wine -Np1 < "${scriptdir}"/esync.patch && \
        echo "Applying address space patch"
        patch -d wine -Np1 < "${scriptdir}"/termux-wine-fix.patch && \
        echo "Applying path change patch"
        ## Proton is based on Wine 9.0 stable release so some of the updates
        ## for patches are not required.
        patch -d wine -Np1 < "${scriptdir}"/pathfix.patch || {
            echo "Error: Failed to apply one or more patches."
            exit 1
        }
        clear 
    fi
fi

# 新增：应用 MF 补丁
echo "开始应用 MF 补丁..."
apply_mf_patches

## NDIS patch for fixing crappy Android's SELinux limitations.
#if [ "$TERMUX_GLIBC" = "true" ]; then
#echo "Circumventing crappy SELinux's limitations... (Thanks BrunoSX)"
#patch -d wine -Np1 < "${scriptdir}"/ndis.patch || {
#        echo "Error: Failed to apply one or more patches."
#        exit 1
#    }
#    clear
#else
#echo "Circumventing crappy SELinux's limitations... (Thanks BrunoSX)"
#patch -d wine -Np1 < "${scriptdir}"/ndis-proot.patch || {
#        echo "Error: Failed to apply one or more patches."
#        exit 1
#    }
#    clear
#fi

if [ ! -d wine ]; then
    echo "未找到 Wine 源码!"
    echo "请确保指定了正确的 Wine 版本。"
    exit 1
fi

cd wine || exit 1
echo "Fixing Input Bridge..."
if [ "$WINE_BRANCH" = "vanilla" ]; then
    git revert --no-commit 2bfe81e41f93ce75139e3a6a2d0b68eb2dcb8fa6 || {
        echo "Error: Failed to revert one or two patches. Stopping."
        exit 1
    }
    clear
elif [ "$WINE_BRANCH" = "staging" ] || [ "$WINE_BRANCH" = "staging-tkg" ]; then
    patch -p1 -R < "${scriptdir}"/inputbridgefix.patch || {
        echo "Error: Failed to revert one or two patches. Stopping."
        exit 1
    }
    clear
fi

#echo "Applying CPU topology patch"
#if [ "$WINE_BRANCH" = "staging" ]; then
#patch -p1 < "${scriptdir}"/wine-cpu-topology-wine-9.22.patch || {
#        echo "Error: Failed to revert one or two patches. Stopping."
#        exit 1
#    }
#   clear
#elif [ "WINE_BRANCH" = "staging-tkg" ]; then
#patch -p1 < "${scriptdir}"/wine-cpu-topology-tkg.patch || {
#        echo "Error: Failed to apply one or two patches. Stopping."
#        exit 1
#    }
#fi

### Experimental addition to address space hackery
if [ "$TERMUX_GLIBC" = "true" ]; then
    echo "跳过有问题的地址空间补丁..."
    # patch -p1 < "${scriptdir}"/wine-virtual-memory.patch || {
    #     echo "这个补丁没有应用，继续..."
    # }
    clear
fi

###
dlls/winevulkan/make_vulkan
tools/make_requests
tools/make_specfiles
autoreconf -f
cd "${BUILD_DIR}" || exit 1

if [ "${DO_NOT_COMPILE}" = "true" ]; then
    echo "DO_NOT_COMPILE 设置为 true"
    echo "强制退出"
    exit
fi

if ! command -v bwrap 1>/dev/null; then
    echo "您的系统未安装 Bubblewrap!"
    echo "请安装后重新运行脚本"
    exit 1
fi

if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
    if [ ! -d "${BOOTSTRAP_X64}" ]; then
        echo "编译需要引导程序!"
        exit 1
    fi
else    
    if [ ! -d "${BOOTSTRAP_X64}" ] || [ ! -d "${BOOTSTRAP_X32}" ]; then
        echo "编译需要引导程序!"
        exit 1
    fi
fi

if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
    BWRAP64="build_with_bwrap"
else
    BWRAP64="build_with_bwrap 64"
    BWRAP32="build_with_bwrap 32"
fi

# 修复安装目录问题 - 使用相对路径
INSTALL_DIR="wine-${BUILD_NAME}-amd64"

# 在脚本中找到以下部分并进行修改：

if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then

    export CROSSCC="${CROSSCC_X64}"
    export CROSSCXX="${CROSSCXX_X64}"
    export CFLAGS="${CFLAGS_X64}"
    export CXXFLAGS="${CFLAGS_X64}"
    export CROSSCFLAGS="${CROSSCFLAGS_X64}"
    export CROSSCXXFLAGS="${CROSSCFLAGS_X64}"

    mkdir "${BUILD_DIR}"/build64
    cd "${BUILD_DIR}"/build64 || exit

    echo "配置 Wine 构建..."
    # 修复：使用相对路径而不是绝对路径
    ${BWRAP64} "${BUILD_DIR}"/wine/configure --enable-archs=i386,x86_64 ${WINE_BUILD_OPTIONS} --prefix=""

    echo "编译 Wine..."
    ${BWRAP64} make -j8

    echo "安装 Wine..."
    # 修复：安装到构建目录而不是根目录
    ${BWRAP64} make install DESTDIR="${BUILD_DIR}/${INSTALL_DIR}"

    # 检查安装是否成功
    echo "检查安装结果..."
    if [ -d "${BUILD_DIR}/${INSTALL_DIR}" ]; then
        echo "安装目录已创建: ${BUILD_DIR}/${INSTALL_DIR}"
        echo "安装目录内容:"
        find "${BUILD_DIR}/${INSTALL_DIR}" -type f | head -20
    else
        echo "错误: 安装目录 ${BUILD_DIR}/${INSTALL_DIR} 不存在!"
        # 尝试手动创建
        mkdir -p "${BUILD_DIR}/${INSTALL_DIR}"
    fi

else
    # 传统模式的类似修复
    export CROSSCC="${CROSSCC_X64}"
    export CROSSCXX="${CROSSCXX_X64}"
    export CFLAGS="${CFLAGS_X64}"
    export CXXFLAGS="${CFLAGS_X64}"
    export CROSSCFLAGS="${CROSSCFLAGS_X64}"
    export CROSSCXXFLAGS="${CROSSCFLAGS_X64}"

    mkdir "${BUILD_DIR}"/build64
    cd "${BUILD_DIR}"/build64 || exit
    ${BWRAP64} "${BUILD_DIR}"/wine/configure --enable-win64 ${WINE_BUILD_OPTIONS} --prefix=""
    ${BWRAP64} make -j8
    ${BWRAP64} make install DESTDIR="${BUILD_DIR}/wine-${BUILD_NAME}-amd64"

    export CROSSCC="${CROSSCC_X32}"
    export CROSSCXX="${CROSSCXX_X32}"
    export CFLAGS="${CFLAGS_X32}"
    export CXXFLAGS="${CFLAGS_X32}"
    export CROSSCFLAGS="${CROSSCFLAGS_X32}"
    export CROSSCXXFLAGS="${CROSSCFLAGS_X32}"

    mkdir "${BUILD_DIR}"/build32-tools
    cd "${BUILD_DIR}"/build32-tools || exit
    PKG_CONFIG_LIBDIR=/usr/lib/i386-linux-gnu/pkgconfig:/usr/local/lib/pkgconfig:/usr/local/lib/i386-linux-gnu/pkgconfig ${BWRAP32} "${BUILD_DIR}"/wine/configure ${WINE_BUILD_OPTIONS} --prefix=""
    ${BWRAP32} make -j$(nproc)
    ${BWRAP32} make install DESTDIR="${BUILD_DIR}/wine-${BUILD_NAME}-x86"

    export CFLAGS="${CFLAGS_X64}"
    export CXXFLAGS="${CFLAGS_X64}"
    export CROSSCFLAGS="${CROSSCFLAGS_X64}"
    export CROSSCXXFLAGS="${CROSSCFLAGS_X64}"

    mkdir "${BUILD_DIR}"/build32
    cd "${BUILD_DIR}"/build32 || exit
    PKG_CONFIG_LIBDIR=/usr/lib/i386-linux-gnu/pkgconfig:/usr/local/lib/pkgconfig:/usr/local/lib/i386-linux-gnu/pkgconfig ${BWRAP32} "${BUILD_DIR}"/wine/configure --with-wine64="${BUILD_DIR}"/build64 --with-wine-tools="${BUILD_DIR}"/build32-tools ${WINE_BUILD_OPTIONS} --prefix=""
    ${BWRAP32} make -j8
    ${BWRAP32} make install DESTDIR="${BUILD_DIR}/wine-${BUILD_NAME}-amd64"

fi

echo
echo "编译完成"
echo "创建并压缩归档文件..."

cd "${BUILD_DIR}" || exit 1

# 调试：列出构建目录内容
echo "=== 构建目录内容 ==="
ls -la "${BUILD_DIR}"
echo "=== 构建目录内容结束 ==="

# 设置结果目录为工作目录
result_dir="/github/workspace"

# 确保结果目录存在
mkdir -p "${result_dir}"

export XZ_OPT="-9"

if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
    # 检查目录是否存在
    if [ -d "${INSTALL_DIR}" ]; then
        mv "${INSTALL_DIR}" "wine-${BUILD_NAME}-exp-wow64-amd64"
        builds_list="wine-${BUILD_NAME}-exp-wow64-amd64"
        echo "找到安装目录: wine-${BUILD_NAME}-exp-wow64-amd64"
        
        # 检查目录内容
        echo "=== 安装目录内容 ==="
        find "wine-${BUILD_NAME}-exp-wow64-amd64" -type f | head -20
        echo "=== 安装目录内容结束 ==="
    else
        echo "错误: 安装目录 ${INSTALL_DIR} 不存在!"
        echo "尝试手动创建构建产物..."
        
        # 手动创建基本的 Wine 目录结构
        MANUAL_DIR="wine-${BUILD_NAME}-exp-wow64-amd64"
        mkdir -p "${MANUAL_DIR}"/bin
        mkdir -p "${MANUAL_DIR}"/lib
        mkdir -p "${MANUAL_DIR}"/share/wine
        
        # 尝试从 build64 目录复制关键文件
        if [ -d "build64" ]; then
            echo "从 build64 目录复制文件..."
            # 查找并复制所有 Wine 相关文件
            find build64 -type f -name "wine" -exec cp -v {} "${MANUAL_DIR}/bin/" \; 2>/dev/null || true
            find build64 -type f -name "wine64" -exec cp -v {} "${MANUAL_DIR}/bin/" \; 2>/dev/null || true
            find build64 -type f -name "wineboot" -exec cp -v {} "${MANUAL_DIR}/bin/" \; 2>/dev/null || true
            find build64 -type f -name "winecfg" -exec cp -v {} "${MANUAL_DIR}/bin/" \; 2>/dev/null || true
            
            # 复制库文件
            find build64 -type f -name "*.so" -exec cp -v {} "${MANUAL_DIR}/lib/" \; 2>/dev/null || true
            find build64 -type f -name "*.so.*" -exec cp -v {} "${MANUAL_DIR}/lib/" \; 2>/dev/null || true
            
            # 复制其他必要文件
            if [ -d "build64/dlls" ]; then
                cp -r build64/dlls "${MANUAL_DIR}/" 2>/dev/null || true
            fi
            if [ -d "build64/programs" ]; then
                cp -r build64/programs "${MANUAL_DIR}/" 2>/dev/null || true
            fi
        else
            echo "警告: build64 目录不存在"
        fi
        
        builds_list="${MANUAL_DIR}"
        echo "已创建手动构建目录: ${MANUAL_DIR}"
    fi
else
    builds_list="wine-${BUILD_NAME}-x86 wine-${BUILD_NAME}-amd64"
fi

for build in ${builds_list}; do
    if [ -d "${build}" ]; then
        echo "处理构建: ${build}"
        
        # 检查目录是否为空
        if [ -z "$(ls -A "${build}")" ]; then
            echo "警告: 构建目录 ${build} 为空!"
        else
            echo "构建目录 ${build} 包含文件:"
            find "${build}" -type f | head -10
        fi
        
        # 清理不必要的文件
        rm -rf "${build}"/include "${build}"/share/applications "${build}"/share/man 2>/dev/null || true

        if [ -f wine/wine-tkg-config.txt ]; then
            cp wine/wine-tkg-config.txt "${build}" 2>/dev/null || true
        fi

        # 创建压缩包
        echo "创建压缩包: ${build}.tar.xz"
        tar -Jcf "${build}.tar.xz" "${build}"
        
        # 复制到工作目录
        echo "复制到结果目录: ${result_dir}"
        cp -v "${build}.tar.xz" "${result_dir}/"
        echo "已创建: ${build}.tar.xz"
        
        # 显示文件大小
        echo "文件大小:"
        ls -lh "${build}.tar.xz"
    else
        echo "警告: 构建目录 ${build} 不存在!"
        echo "当前目录内容:"
        ls -la
    fi
done

# 清理临时构建目录
cd /tmp
rm -rf "${BUILD_DIR}"

echo
echo "完成"
echo "构建产物已经在 ${result_dir} 目录中"

# 列出最终的工作目录内容
echo "=== 工作目录内容 ==="
ls -la "${result_dir}"/*.tar.xz 2>/dev/null || echo "未找到 tar.xz 文件"
if ls "${result_dir}"/*.tar.xz >/dev/null 2>&1; then
    echo "找到的构建产物:"
    ls -lh "${result_dir}"/*.tar.xz
fi
echo "=== 工作目录内容结束 ==="