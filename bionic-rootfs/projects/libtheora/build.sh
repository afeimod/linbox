revision="v1.2.0"
url="https://github.com/xiph/theora.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="autotools"
license="BSD"
deps="libogg libvorbis"
args="--disable-examples --disable-doc"
pre_setup() {
  if [[ $targetArch == aarch64 ]]; then
    args+=" --disable-asm"
  fi
}