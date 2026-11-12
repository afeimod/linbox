revision="2.6.2"
url="https://mirrors.kernel.org/gnu/libtool/libtool-${revision}.tar.gz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="GPL-2.0"

install() {
  make install DESTDIR="${destDir}" || exit 1
  sed -i \
    -e "s|\"/usr/bin/|\"${prefix}/bin/|" \
    -e "s|\"/bin/|\"${prefix}/bin/|" \
    "${destDir}${prefix}/bin/libtool" \
    "${destDir}${prefix}/bin/libtoolize"
}
