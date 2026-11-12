revision="0.4.42"
url="https://github.com/GStreamer/orc.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="BSD-2-Clause, BSD-3-Clause"
args="
  -Dorc-test=disabled
  -Dtests=disabled
  -Dbenchmarks=disabled
  -Dexamples=disabled
  -Dhotdoc=disabled
"
pre_setup() {
  case "${targetArch}" in
  aarch64)
    args+=" -Dorc-target=neon"
    ;;
  x86_64)
    args+=" -Dorc-target=sse,avx"
    ;;
  esac
}
