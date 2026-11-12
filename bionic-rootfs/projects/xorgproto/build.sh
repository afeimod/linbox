revision="2024.1"
url="https://xorg.freedesktop.org/archive/individual/proto/xorgproto-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
# X11 协议头文件（xproto/xextproto/renderproto/randrproto/fixesproto/
# inputproto/xineramaproto/compositeproto/damageproto/xf86vidmodeproto/
# videoproto/dri3proto/presentproto 等全部合并于本包，含各历史 .pc 文件）
args="
  -Dlegacy=false
"
deps=""
