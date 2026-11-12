revision="1.2.3"
url="https://xorg.freedesktop.org/archive/individual/lib/libXcursor-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
args="
  --enable-malloc0returnsnull=yes"
# 光标库；patch1 将图标/光标主题搜索路径改到 rootfs 前缀
deps="libX11 libXfixes libXrender xorgproto"

install() {
  make install DESTDIR="${destDir}"
  # SONAME 版本别名：本构建系统按 Termux/Android 惯例产无版本 .so（bionic
  # 按文件名加载），但 wine 等预编译产物的 DT_NEEDED 记录上游标准 SONAME。
  # 版本号取自上游 src/Makefile.am 的 libtool -version-number <major>:*:*
  cd "${destDir}/${prefix}/lib"
  ln -sf libXcursor.so libXcursor.so.1
}
