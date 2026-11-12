revision="VER-2-14-3"
url="https://github.com/freetype/freetype.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="FTL-1.1 OR GPL-2.0-or-later"
# freetype 与 harfbuzz 互相依赖，禁用可选方向避免循环依赖（同 termux-packages 的做法）；
# 且 prefix 中会残留上次构建的 harfbuzz.pc，auto 可能误探测导致链接失败
args="
  -Dbrotli=enabled
  -Dbzip2=enabled
  -Dharfbuzz=disabled
  -Dmmap=enabled
  -Dpng=enabled
  -Dtests=disabled
  -Dzlib=enabled
"
deps="brotli bzip2 libpng zlib"
# v3.1.7：SONAME 版本别名（对齐 libX11/pulseaudio 配方）—— bionic 无版本
# .so + 上游标准 SONAME 别名（libfreetype.so.6），兼容记录 glibc 风格
# DT_NEEDED 的预编译产物与 wine CI 8.5a 关键库校验。
install() {
  DESTDIR="${destDir}" meson install -C build
  cd "${destDir}${prefix}/lib" || exit 1
  [[ -e libfreetype.so.6 ]] || ln -sf libfreetype.so libfreetype.so.6
}
