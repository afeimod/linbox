revision="R_2_8_2"
url="https://github.com/libexpat/libexpat.git"
urlType="git"
license="MIT"
pkgSrcDir="${srcDir}/libexpat/expat"
arch="aarch64 x86_64"
buildSys="autotools"
args="
  --without-xmlwf
  --without-examples
  --without-tests
  --without-docbook
"
custom_configure() {
  #cd "${pkgSrcDir}"
	local a
	for a in LIBCURRENT LIBAGE; do
		local _${a}=$(sed -En 's/^'"${a}"'=([0-9]+).*/\1/p' configure.ac)
	done
  ./buildconf.sh
  #configureBaseArgs=+( --host=${targetArch}-linux-android --build=$(uname -m)-linux-gnu)
  ./configure ${configureBaseArgs[@]} ${args[@]} || configure_err
  make -j`nproc`
  DESTDIR="$destDir" make install
}