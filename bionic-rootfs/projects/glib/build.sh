revision="2.89.2"
url="https://github.com/GNOME/glib.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="LGPL-2.1-or-later"
args="
  -Dintrospection=disabled
  -Druntime_dir="${prefix}/var/run"
  -Dlibmount=disabled
  -Dman-pages=disabled
  -Dselinux=disabled
  -Dglib_debug=disabled
  -Ddocumentation=false
  -Dtests=false
"
deps="libffi libiconv pcre2 zlib"
pre_setup() {
  CFLAGS+=" -D__BIONIC__=1"
#  LDFLAGS+=" -lm"
}