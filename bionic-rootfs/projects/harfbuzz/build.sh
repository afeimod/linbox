revision="14.3.1"
url="https://github.com/harfbuzz/harfbuzz.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
# prefix 中会残留上次构建的 *.pc（.so 已被清理），cairo/icu 用 auto 可能误探测
# 导致 libharfbuzz-cairo/libharfbuzz-icu 链接失败；libcairo 经 deps 声明，
# 保证构建 harfbuzz 前已装入 prefix
args="
  -Dcpp_std=c++17
  -Ddocs=disabled
  -Dgobject=disabled
  -Dgraphite=disabled
  -Dintrospection=disabled
  -Dtests=disabled
  -Dfreetype=enabled
  -Dglib=enabled
  -Dcairo=enabled
  -Dicu=disabled
"
deps="freetype glib libcairo"
