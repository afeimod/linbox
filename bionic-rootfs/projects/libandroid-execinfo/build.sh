revision="0.1"
urlType="local"
pkgSrcDir="${wsDir}/projects/libandroid-execinfo"
arch="aarch64 x86_64"
buildSys="others"
doNotApplyPatch="1"
extra_fuction() {
  rm -f *.o *.so *.a

  $CC $CFLAGS $CPPFLAGS -I${pkgSrcDir} -c ${pkgSrcDir}/execinfo.c
  $CC $LDFLAGS -shared execinfo.o -o libandroid-execinfo.so \
		-Wl,-soname=libandroid-execinfo.so
  $AR rcu libandroid-execinfo.a execinfo.o
  mkdir -p "${destDir}${prefix}/include" "${destDir}${prefix}/lib"
  install -Dm600 execinfo.h "${destDir}${prefix}/include/execinfo.h"
  install -Dm600 libandroid-execinfo.a "${destDir}${prefix}/lib/libandroid-execinfo.a"
  install -Dm600 libandroid-execinfo.so "${destDir}${prefix}/lib/libandroid-execinfo.so"
  cd "${destDir}${prefix}/lib/"
  ln -sfr libandroid-execinfo.so libexecinfo.so
  cd "${pkgSrcDir}"
  rm -f *.o *.so *.a
}