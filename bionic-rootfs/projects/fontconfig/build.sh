revision="2.18.2"
url="https://gitlab.freedesktop.org/fontconfig/fontconfig.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
argsToAutogenSh="1"
args="
  --disable-nls
  --enable-iconv
  --without-libxml2
  --disable-docbook
  --disable-docs
  --with-default-fonts="${prefix}/share/fonts"
  ac_cv_va_copy=C99
"
# --with-add-fonts=/path/to/fonts
# --with-default-fonts=/system/fonts
# 修复：fontconfig 2.18 的 XML 解析后端默认是 expat，配方又显式
# --without-libxml2 关闭了 libxml2 后端，configure 会探测 EXPAT/expat.h，
# 依赖列表里却没有 libexpat，导致 "checking for EXPAT... no" 直接报错退出。
# （fix: add missing libexpat dependency; libxml2 kept for other consumers）
deps="libexpat libxml2 libiconv freetype"