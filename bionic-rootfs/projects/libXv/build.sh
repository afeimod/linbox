revision="1.0.13"
url="https://xorg.freedesktop.org/archive/individual/lib/libXv-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
args="
  --enable-malloc0returnsnull=yes"
# X Video 扩展（gstreamer xvimagesink 视频输出）
deps="libX11 libXext xorgproto"

install() {
  make install DESTDIR="${destDir}"
  # SONAME 版本别名：本构建系统按 Termux/Android 惯例产无版本 .so（bionic
  # 按文件名加载），但 wine 等预编译产物的 DT_NEEDED 记录上游标准 SONAME。
  # 版本号取自上游 src/Makefile.am 的 libtool -version-number <major>:*:*
  cd "${destDir}/${prefix}/lib"
  ln -sf libXv.so libXv.so.1
}
