revision="1.5.0"
url="https://github.com/xiph/flac.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD-3-Clause"
# 说明：本配方必须走 CMake，禁止回退 autotools！
# git 源码无预生成 configure，autotools 路径只能跑 autogen.sh，而
# flac 的 autogen.sh 会 `command -v libtool` 强制检查宿主机 libtool
# 二进制（2026-09-19 rootfs 流水线日志实测：libtoolize 可用但 libtool
# 缺失，构建 40 分钟到 libflac 才死于 "Missing program 'libtool'."）。
# FLAC 1.5.0 官方首选 CMake 构建（与 libogg 同路），FindOgg 支持显式
# 传入 OGG_INCLUDE_DIR / OGG_LIBRARY，无需依赖 pkg-config。
args="
  -DBUILD_SHARED_LIBS=ON
  -DBUILD_CXXLIBS=OFF
  -DBUILD_PROGRAMS=OFF
  -DBUILD_EXAMPLES=OFF
  -DBUILD_TESTING=OFF
  -DBUILD_DOCS=OFF
  -DINSTALL_MANPAGES=OFF
  -DWITH_OGG=ON
  -DOGG_INCLUDE_DIR=${prefix}/include
  -DOGG_LIBRARY=${prefix}/lib/libogg.so
"
deps="libogg"
