revision="1.5.4"
url="https://xorg.freedesktop.org/archive/individual/lib/libXrandr-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
args="
  --enable-malloc0returnsnull=yes"
# 显示分辨率/刷新率切换（wine 分辨率模拟、vulkan xlib_xrandr WSI 依赖）
deps="libX11 libXext libXrender libXfixes xorgproto"

install() {
  make install DESTDIR="${destDir}"
  # SONAME 版本别名：本构建系统按 Termux/Android 惯例产无版本 .so（bionic
  # 按文件名加载），但 wine 等预编译产物的 DT_NEEDED 记录上游标准 SONAME。
  # 版本号取自上游 src/Makefile.am 的 libtool -version-number <major>:*:*
  cd "${destDir}/${prefix}/lib"
  ln -sf libXrandr.so libXrandr.so.2
}
