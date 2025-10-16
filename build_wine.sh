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

# Prevent launching as root
if [ $EUID = 0 ] && [ -z "$ALLOW_ROOT" ]; then
	echo "Do not run this script as root!"
	echo
	echo "If you really need to run it as root and you know what you are doing,"
	echo "set the ALLOW_ROOT environment variable."

	exit 1
fi

# Wine version to compile.
# You can set it to "latest" to compile the latest available version.
# You can also set it to "git" to compile the latest git revision.
#
# This variable affects only vanilla and staging branches. Other branches
# use their own versions.
export WINE_VERSION="${WINE_VERSION:-latest}"

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
export TERMUX_GLIBC="${TERMUX_GLIBC:-false}"

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
export EXPERIMENTAL_WOW64="${EXPERIMENTAL_WOW64:-false}"

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

export WINE_BUILD_OPTIONS="--disable-winemenubuilder --disable-win16 --enable-win64 --disable-tests --without-capi --without-coreaudio --without-cups --without-gphoto --without-osmesa --without-oss --without-pcap --without-pcsclite --without-sane --without-udev --without-unwind --without-usb --without-v4l2 --without-wayland --without-xinerama"

# A temporary directory where the Wine source code will be stored.
# Do not set this variable to an existing non-empty directory!
# This directory is removed and recreated on each script run.
export BUILD_DIR="${HOME}"/build_wine

# Implement a new WoW64 specific check which will change the way Wine is built.
# New WoW64 builds will use a different bootstrap which require different
# variables and they are not compatible with old WoW64 build mode.
if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then

   export BOOTSTRAP_X64=/opt/chroots/noble64_chroot

   export scriptdir="$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")"

   export CC="gcc-14"
   export CXX="g++-14"
   
   export CROSSCC_X64="x86_64-w64-mingw32-gcc"
   export CROSSCXX_X64="x86_64-w64-mingw32-g++"

   export CFLAGS_X64="-march=x86-64 -msse3 -mfpmath=sse -O3 -ftree-vectorize -pipe"
   export LDFLAGS="-Wl,-O1,--sort-common,--as-needed"
   
   export CROSSCFLAGS_X64="${CFLAGS_X64}"
   export CROSSLDFLAGS="${LDFLAGS}"

   if [ "$USE_CCACHE" = "true" ]; then
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

    bwrap --ro-bind "${BOOTSTRAP_PATH}" / --dev /dev --ro-bind /sys /sys \
		  --proc /proc --tmpfs /tmp --tmpfs /home --tmpfs /run --tmpfs /var \
		  --tmpfs /mnt --tmpfs /media --bind "${BUILD_DIR}" "${BUILD_DIR}" \
		  --bind-try "${XDG_CACHE_HOME}"/ccache "${XDG_CACHE_HOME}"/ccache \
		  --bind-try "${HOME}"/.ccache "${HOME}"/.ccache \
		  --setenv PATH "/bin:/sbin:/usr/bin:/usr/sbin" \
			"$@"
}

else

export BOOTSTRAP_X64=/opt/chroots/bionic64_chroot
export BOOTSTRAP_X32=/opt/chroots/bionic32_chroot

export scriptdir="$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")"

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

    bwrap --ro-bind "${BOOTSTRAP_PATH}" / --dev /dev --ro-bind /sys /sys \
		  --proc /proc --tmpfs /tmp --tmpfs /home --tmpfs /run --tmpfs /var \
		  --tmpfs /mnt --tmpfs /media --bind "${BUILD_DIR}" "${BUILD_DIR}" \
		  --bind-try "${XDG_CACHE_HOME}"/ccache "${XDG_CACHE_HOME}"/ccache \
		  --bind-try "${HOME}"/.ccache "${HOME}"/.ccache \
		  --setenv PATH "/bin:/sbin:/usr/bin:/usr/sbin" \
			"$@"
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

# Replace the "latest" parameter with the actual latest Wine version
if [ "${WINE_VERSION}" = "latest" ] || [ -z "${WINE_VERSION}" ]; then
	WINE_VERSION="$(wget -q -O - "https://raw.githubusercontent.com/wine-mirror/wine/master/VERSION" | tail -c +14)"
fi

# Stable and Development versions have a different source code location
# Determine if the chosen version is stable or development
if [ "$(echo "$WINE_VERSION" | cut -c3)" = "0" ]; then
	WINE_URL_VERSION=$(echo "$WINE_VERSION" | cut -c1).0
else
	WINE_URL_VERSION=$(echo "$WINE_VERSION" | cut -c1).x
fi

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}" || exit 1

echo
echo "Downloading the source code and patches"
echo "Preparing Wine for compilation"
echo

if [ -n "${CUSTOM_SRC_PATH}" ]; then
	is_url="$(echo "${CUSTOM_SRC_PATH}" | head -c 6)"

	if [ "${is_url}" = "git://" ] || [ "${is_url}" = "https:" ]; then
		git clone "${CUSTOM_SRC_PATH}" wine
	else
		if [ ! -f "${CUSTOM_SRC_PATH}"/configure ]; then
			echo "CUSTOM_SRC_PATH is set to an incorrect or non-existent directory!"
			echo "Please make sure to use a directory with the correct Wine source code."
			exit 1
		fi

		cp -r "${CUSTOM_SRC_PATH}" wine
	fi

	WINE_VERSION="$(cat wine/VERSION | tail -c +14)"
	BUILD_NAME="${WINE_VERSION}"-custom
elif [ "$WINE_BRANCH" = "staging-tkg" ]; then
	git clone https://github.com/Kron4ek/wine-tkg wine

	WINE_VERSION="$(cat wine/VERSION | tail -c +14)"
	BUILD_NAME="${WINE_VERSION}"-staging-tkg
elif [ "$WINE_BRANCH" = "wayland" ]; then
	git clone https://github.com/Kron4ek/wine-wayland wine

	WINE_VERSION="$(cat wine/VERSION | tail -c +14)"
	BUILD_NAME="${WINE_VERSION}"-wayland

	export WINE_BUILD_OPTIONS="--without-x --without-xcomposite \
                               --without-xfixes --without-xinerama \
                               --without-xinput --without-xinput2 \
                               --without-xrandr --without-xrender \
                               --without-xshape --without-xshm  \
                               --without-xslt --without-xxf86vm \
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

		wget -q --show-progress "https://dl.winehq.org/wine/source/${WINE_URL_VERSION}/wine-${WINE_VERSION}.tar.xz"
		tar xf "wine-${WINE_VERSION}.tar.xz"
		mv "wine-${WINE_VERSION}" wine
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

    if [ "${WINE_BRANCH}" = "vanilla" ]; then
    BUILD_NAME="${WINE_VERSION}"
    else
    BUILD_NAME="${WINE_VERSION}"-staging
fi

    wget -q --show-progress "https://github.com/wine-staging/wine-staging/archive/v${WINE_VERSION}.tar.gz"
    tar xf v"${WINE_VERSION}".tar.gz

    if [ ! -f v"${WINE_VERSION}".tar.gz ]; then
        git clone https://github.com/wine-staging/wine-staging wine-staging-"${WINE_VERSION}"
    fi
fi

if [ -f wine-staging-"${WINE_VERSION}"/patches/patchinstall.sh ]; then
    staging_patcher=("${BUILD_DIR}"/wine-staging-"${WINE_VERSION}"/patches/patchinstall.sh
                    DESTDIR="${BUILD_DIR}"/wine)
else
    staging_patcher=("${BUILD_DIR}"/wine-staging-"${WINE_VERSION}"/staging/patchinstall.py)
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
			echo "Skipping Wine-Staging patches..."
		fi
     
		if [ $? -ne 0 ]; then
			echo
			echo "Wine-Staging patches were not applied correctly!"
			exit 1
		fi

cd "${BUILD_DIR}" || exit 1
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
	clear
	echo "No Wine source code found!"
	echo "Make sure that the correct Wine version is specified."
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
echo "Applying additional address space patch... (credits to Bylaws)"
patch -p1 < "${scriptdir}"/wine-virtual-memory.patch || {
        echo "This patch did not apply. Stopping..."
	exit 1
    }
    clear
fi

###
dlls/winevulkan/make_vulkan
tools/make_requests
tools/make_specfiles
autoreconf -f
cd "${BUILD_DIR}" || exit 1

if [ "${DO_NOT_COMPILE}" = "true" ]; then
	clear
	echo "DO_NOT_COMPILE is set to true"
	echo "Force exiting"
	exit
fi

if ! command -v bwrap 1>/dev/null; then
	echo "Bubblewrap is not installed on your system!"
	echo "Please install it and run the script again"
	exit 1
fi

if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
    if [ ! -d "${BOOTSTRAP_X64}" ]; then
        clear
        echo "Bootstraps are required for compilation!"
        exit 1
    fi
else    
    if [ ! -d "${BOOTSTRAP_X64}" ] || [ ! -d "${BOOTSTRAP_X32}" ]; then
        clear
        echo "Bootstraps are required for compilation!"
        exit 1
    fi
fi

if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
BWRAP64="build_with_bwrap"
else
BWRAP64="build_with_bwrap 64"
BWRAP32="build_with_bwrap 32"
fi

if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then

export CROSSCC="${CROSSCC_X64}"
export CROSSCXX="${CROSSCXX_X64}"
export CFLAGS="${CFLAGS_X64}"
export CXXFLAGS="${CFLAGS_X64}"
export CROSSCFLAGS="${CROSSCFLAGS_X64}"
export CROSSCXXFLAGS="${CROSSCFLAGS_X64}"

mkdir "${BUILD_DIR}"/build64
cd "${BUILD_DIR}"/build64 || exit
${BWRAP64} "${BUILD_DIR}"/wine/configure --enable-archs=i386,x86_64 ${WINE_BUILD_OPTIONS} --prefix "${BUILD_DIR}"/wine-"${BUILD_NAME}"-amd64
${BWRAP64} make -j8
${BWRAP64} make install

else

export CROSSCC="${CROSSCC_X64}"
export CROSSCXX="${CROSSCXX_X64}"
export CFLAGS="${CFLAGS_X64}"
export CXXFLAGS="${CFLAGS_X64}"
export CROSSCFLAGS="${CROSSCFLAGS_X64}"
export CROSSCXXFLAGS="${CROSSCFLAGS_X64}"

mkdir "${BUILD_DIR}"/build64
cd "${BUILD_DIR}"/build64 || exit
${BWRAP64} "${BUILD_DIR}"/wine/configure --enable-win64 ${WINE_BUILD_OPTIONS} --prefix "${BUILD_DIR}"/wine-"${BUILD_NAME}"-amd64
${BWRAP64} make -j8
${BWRAP64} make install

export CROSSCC="${CROSSCC_X32}"
export CROSSCXX="${CROSSCXX_X32}"
export CFLAGS="${CFLAGS_X32}"
export CXXFLAGS="${CFLAGS_X32}"
export CROSSCFLAGS="${CROSSCFLAGS_X32}"
export CROSSCXXFLAGS="${CROSSCFLAGS_X32}"

mkdir "${BUILD_DIR}"/build32-tools
cd "${BUILD_DIR}"/build32-tools || exit
PKG_CONFIG_LIBDIR=/usr/lib/i386-linux-gnu/pkgconfig:/usr/local/lib/pkgconfig:/usr/local/lib/i386-linux-gnu/pkgconfig ${BWRAP32} "${BUILD_DIR}"/wine/configure ${WINE_BUILD_OPTIONS} --prefix "${BUILD_DIR}"/wine-"${BUILD_NAME}"-x86
${BWRAP32} make -j$(nproc)
${BWRAP32} make install

export CFLAGS="${CFLAGS_X64}"
export CXXFLAGS="${CFLAGS_X64}"
export CROSSCFLAGS="${CROSSCFLAGS_X64}"
export CROSSCXXFLAGS="${CROSSCFLAGS_X64}"

mkdir "${BUILD_DIR}"/build32
cd "${BUILD_DIR}"/build32 || exit
PKG_CONFIG_LIBDIR=/usr/lib/i386-linux-gnu/pkgconfig:/usr/local/lib/pkgconfig:/usr/local/lib/i386-linux-gnu/pkgconfig ${BWRAP32} "${BUILD_DIR}"/wine/configure --with-wine64="${BUILD_DIR}"/build64 --with-wine-tools="${BUILD_DIR}"/build32-tools ${WINE_BUILD_OPTIONS} --prefix "${BUILD_DIR}"/wine-${BUILD_NAME}-amd64
${BWRAP32} make -j8
${BWRAP32} make install

fi

echo
echo "Compilation complete"
echo "Creating and compressing archives..."

cd "${BUILD_DIR}" || exit

if touch "${scriptdir}"/write_test; then
	rm -f "${scriptdir}"/write_test
	result_dir="${scriptdir}"
else
	result_dir="${HOME}"
fi

export XZ_OPT="-9"

if [ "${EXPERIMENTAL_WOW64}" = "true" ]; then
	mv wine-${BUILD_NAME}-amd64 wine-${BUILD_NAME}-exp-wow64-amd64

	builds_list="wine-${BUILD_NAME}-exp-wow64-amd64"
else
	builds_list="wine-${BUILD_NAME}-x86 wine-${BUILD_NAME}-amd64"
fi

for build in ${builds_list}; do
	if [ -d "${build}" ]; then
		rm -rf "${build}"/include "${build}"/share/applications "${build}"/share/man

		if [ -f wine/wine-tkg-config.txt ]; then
			cp wine/wine-tkg-config.txt "${build}"
		fi

		tar -Jcf "${build}".tar.xz "${build}"
		mv "${build}".tar.xz "${result_dir}"
	fi
done

rm -rf "${BUILD_DIR}"

echo
echo "Done"
echo "The builds should be in ${result_dir}"
