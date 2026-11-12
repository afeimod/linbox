revision="0.6"
urlType="local"
license="BSD-3-Clause"
pkgSrcDir="${wsDir}/projects/libandroid-glob"
arch="aarch64 x86_64"
buildSys="others"
extra_fuction() {
  rm -f *.o *.so *.a
  $CC $CFLAGS $CPPFLAGS -I${pkgSrcDir} -c ${pkgSrcDir}/glob.c
  $CC $LDFLAGS -shared glob.o -o libandroid-glob.so
  $AR rcu libandroid-glob.a glob.o
  mkdir -p "${destDir}${prefix}/include" "${destDir}${prefix}/lib"
  install -Dm600 glob.h "${destDir}${prefix}/include/glob.h"
  install -Dm600 libandroid-glob.a "${destDir}${prefix}/lib/libandroid-glob.a"
  install -Dm600 libandroid-glob.so "${destDir}${prefix}/lib/libandroid-glob.so"
  rm -f *.o *.so *.a
}