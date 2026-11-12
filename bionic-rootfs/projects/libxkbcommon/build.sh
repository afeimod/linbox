revision="xkbcommon-1.9.2"
url="https://github.com/xkbcommon/libxkbcommon.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
# LinBox 使用 X11 显示：enable-x11 生成 libxkbcommon-x11（XKB over XCB），
# 供 GTK/Qt 等使用；wayland 相关保持关闭
args="
  -Denable-docs=false
  -Denable-wayland=false
  -Denable-x11=true
"
deps="libxml2 xorgproto libxcb libX11"

install() {
  DESTDIR="${destDir}" PREFIX="${prefix}" meson install -C build
  # SONAME 版本别名：本构建系统按 Termux/Android 惯例产无版本 .so（meson 的
  # android 分支同样去版本化，见 libdrm meson.build "Build an unversioned so
  # on android"），但 wine 等预编译产物的 DT_NEEDED 记录上游标准 SONAME。
  cd "${destDir}/${prefix}/lib"
  # 上游 meson.build：1.x.y 产 libxkbcommon.so.0.x.y → SONAME .0（x11 变体同）
  ln -sf libxkbcommon.so libxkbcommon.so.0
  ln -sf libxkbcommon-x11.so libxkbcommon-x11.so.0
  [[ -f libxkbregistry.so ]] && ln -sf libxkbregistry.so libxkbregistry.so.0 || true
}
