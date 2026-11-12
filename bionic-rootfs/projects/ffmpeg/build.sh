revision="n9.0"
url="https://github.com/FFmpeg/FFmpeg.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="autotools"
license="GPL-3.0"
args="
  --enable-version3
  --enable-gpl
  --disable-symver
  --enable-pic
  --disable-doc
  --disable-htmlpages
  --disable-manpages
  --disable-debug
  --disable-x86asm
  --enable-gmp
  --enable-gnutls
  --enable-libmp3lame
  --enable-libopus
  --enable-libvorbis
  --enable-libvpx
  --enable-libopenh264
  --enable-libxvid
  --enable-libfreetype
  --enable-libfontconfig
  --enable-libharfbuzz
  --enable-libxml2
  --enable-libdrm
  --enable-vulkan
  --enable-zlib
  --enable-bzlib
  --enable-iconv
  --enable-alsa
  --enable-libpulse
  --enable-libx265
  --extra-libs=-landroid-glob
  --extra-libs=-landroid-shmem
"
deps="libandroid-glob libandroid-shmem gnutls gmp mp3lame libopus libogg libvorbis libvpx openh264 xvidcore freetype fontconfig harfbuzz libxml2 libdrm vulkan-headers vulkan-icd-loader alsa-lib pulseaudio zlib bzip2 libiconv x265"
custom_configure() {
  local _cfgArgs=()
  local _arg
  for _arg in "${configureBaseArgs[@]}"; do
    case "$_arg" in
    --host=* | --build=* | --sysconfdir=* | --localstatedir=*) ;;
    *) _cfgArgs+=("$_arg") ;;
    esac
  done
  local _cc="${CC#ccache }"
  local _cxx="${CXX#ccache }"
  _cfgArgs+=(
    --arch="${targetArch}"
    --enable-cross-compile
    --target-os=android
    --cc="${_cc}"
    --cxx="${_cxx}"
    --ar="${AR}"
    --ranlib="${RANLIB}"
    --nm="llvm-nm"
    --strip="${STRIP}"
    --pkg-config="pkg-config"
  )
  if [[ "$targetArch" == "x86_64" ]]; then
    _cfgArgs+=(--as="${ASM}")
  fi
  ./configure "${_cfgArgs[@]}" ${args[@]} || configure_err
}
