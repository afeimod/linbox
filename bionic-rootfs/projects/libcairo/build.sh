revision="1.18.4"
url="https://gitlab.freedesktop.org/cairo/cairo.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="LGPL-2.1-or-later OR MPL-1.1"
args="
  -Dpng=enabled
  -Dzlib=enabled
  -Dglib=enabled
  -Dgtk_doc=false
  -Dtests=disabled
"
deps="fontconfig freetype glib libandroid-shmem libandroid-execinfo libpixman libpng zlib libxcb libX11"
pre_setup() {
  LDFLAGS+=" -landroid-shmem -landroid-execinfo"
}