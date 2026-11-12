revision="v2.15.3"
url="https://github.com/GNOME/libxml2"
urlType="git"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
argsToAutogenSh=1
args="
  --with-iconv
  --with-icu
  --with-zlib
  --without-iso8859x
  --without-http
  --without-catalog
  --without-debug
  --without-history
  --without-readline
  --without-modules
  --without-python
  --without-docs
"
deps="libiconv icu libandroid-glob zlib"
pre_setup() {
  LDFLAGS+=" -landroid-glob -liconv"
}

# ===== backup: original cmake build =====
# buildSys="cmake"
# args="-DLIBXML2_WITH_ICONV=ON -DLIBXML2_WITH_ISO8859X=OFF -DLIBXML2_WITH_HTTP=OFF -DLIBXML2_WITH_CATALOG=OFF -DLIBXML2_WITH_DEBUG=OFF -DLIBXML2_WITH_HISTORY=OFF -DLIBXML2_WITH_READLINE=OFF -DLIBXML2_WITH_MODULES=OFF -DLIBXML2_WITH_PYTHON=OFF -DLIBXML2_WITH_ZLIB=ON -DLIBXML2_WITH_LZMA=OFF -DLIBXML2_WITH_ICU=ON -DLIBXML2_WITH_TESTS=OFF -DLIBXML2_WITH_PROGRAMS=OFF -DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF"
# pre_setup() {
#   cmakeBaseArgs+=" -DCMAKE_EXE_LINKER_FLAGS=-landroid-glob -DCMAKE_SHARED_LINKER_FLAGS=-landroid-glob"
# }