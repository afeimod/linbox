revision="v1.16.0"
url="https://github.com/webmproject/libvpx.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="autotools"
license=""
args="
  --disable-examples
  --disable-unit-tests
  --disable-docs
  --disable-tools
  --enable-shared
  --enable-pic
  --enable-postproc
  --enable-vp8
  --enable-vp9
  --enable-vp9-highbitdepth
  --enable-vp9-temporal-denoising
  --enable-vp9-postproc
  --enable-runtime-cpu-detect
  --enable-error-concealment
  --enable-better-hw-compatibility
  --as=auto
  --extra-cflags="-fPIC"
"
deps="libc++"
custom_configure() {
  local _cfgArgs=()
  local _arg
  for _arg in "${configureBaseArgs[@]}"; do
    case "$_arg" in
    --host=* | --build=* | --sysconfdir=* | --localstatedir=*) ;;
    --enable-shared | --disable-static) ;;
    *) _cfgArgs+=("$_arg") ;;
    esac
  done
  local _tgt="${targetArch}"
  [[ "$_tgt" == "aarch64" ]] && _tgt="arm64"
  _cfgArgs+=(
    --target="${_tgt}-android-gcc"
    --disable-shared
    --enable-static
  )
  if [[ "$targetArch" == "aarch64" ]]; then
    export AS="${CC#ccache }"
  else
    export AS="${ASM}"
  fi
  ./configure "${_cfgArgs[@]}" ${args[@]} || configure_err
}