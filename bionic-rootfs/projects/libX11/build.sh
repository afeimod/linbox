revision="1.8.11"
url="https://xorg.freedesktop.org/archive/individual/lib/libX11-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# X11 核心客户端库 + libX11-xcb（mesa x11 平台依赖）
# X 传输层由 xtrans 提供（已补丁对齐 LinBox 内置 X 服务的 socket 路径）
deps="pthread-stub xtrans libxcb xorgproto"

args="
  --disable-xf86bigfont
  --without-xmlto
  --without-fop
  --without-xsltproc
"

install() {
  make install DESTDIR="${destDir}"
  # SONAME 版本别名：本构建系统按 Termux/Android 惯例产无版本 .so（bionic
  # 按文件名加载），但 wine 等预编译产物的 DT_NEEDED 记录上游标准 SONAME。
  # 版本号取自上游 src/Makefile.am 的 libtool -version-number <major>:*:*
  cd "${destDir}/${prefix}/lib"
  ln -sf libX11.so libX11.so.6
  ln -sf libX11-xcb.so libX11-xcb.so.1
}
