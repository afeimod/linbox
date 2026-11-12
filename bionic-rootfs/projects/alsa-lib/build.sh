revision="v1.2.16.1"
url="https://github.com/alsa-project/alsa-lib.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="autotools"
license="LGPL-2.1"
args="
  --with-versioned=no
  --with-tmpdir=$prefix/tmp
"
deps="libandroid-shmem"
pre_setup() {
  LDFLAGS+=" -landroid-shmem"
  autoreconf -fi
}

install() {
  make install DESTDIR="${destDir}"
  cd "${destDir}/${prefix}/lib"
  ln -sf libasound.so libasound.so.2
}