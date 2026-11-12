revision="v1.3.2"
url="https://github.com/madler/zlib.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="autotools"
license="zlib"
args="--shared"
pre_setup() {
  if [[ $targetArch == aarch64 ]]; then
    CFLAGS+=" -march=armv8-a+crc"
    CXXFLAGS+=" -march=armv8-a+crc"
  fi
  LDFLAGS+=" -Wl,--undefined-version"
}
custom_configure() {
  ./configure --prefix="${prefix}" "${args[@]}"
}

# ===== backup: original cmake build =====
# buildSys="cmake"
# args="-DZLIB_BUILD_SHARED=ON -DZLIB_BUILD_STATIC=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON"
# pre_setup() {
#   if [[ $targetArch == aarch64 ]]; then
#     cmakeBaseArgs+=" -DCMAKE_C_FLAGS=\"-march=armv8-a+crc\" -DCMAKE_CXX_FLAGS=\"-march=armv8-a+crc\""
#   fi
# }