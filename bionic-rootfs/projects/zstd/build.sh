revision="v1.5.7"
url="https://github.com/facebook/zstd.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD-3-Clause"
deps="zlib"
pkgSrcDir="${srcDir}/zstd/build/cmake"
args="
  -DZSTD_BUILD_CONTRIB=OFF
  -DZSTD_BUILD_PROGRAMS=OFF
  -DZSTD_BUILD_SHARED=ON
  -DZSTD_BUILD_STATIC=OFF
  -DZSTD_BUILD_TESTS=OFF
  -DZSTD_LZ4_SUPPORT=OFF
  -DZSTD_LZMA_SUPPORT=OFF
  -DZSTD_PROGRAMS_LINK_SHARED=ON
  -DZSTD_ZLIB_SUPPORT=OFF
"
pre_package() {
  cd "${destDir}/${prefix}/lib"
  ln -sf libzstd.so libzstd.so.1
}