revision="0.46.4"
url="https://cairographics.org/releases/pixman-${revision}.tar.gz"
# cairographics.org 单点托管偶发限流/超时，Debian pool 兜底
backupUrls=(
  "http://deb.debian.org/debian/pool/main/p/pixman/pixman_${revision}.orig.tar.gz"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
args="
  -Dlibpng=disabled
  -Dtests=disabled
"
deps="zlib"
