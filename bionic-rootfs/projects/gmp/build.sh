# https://ftp.gnu.org/gnu/gmp/gmp-6.3.0.tar.xz
revision="6.3.0"
# 注意：gmplib.org 会间歇性屏蔽 GitHub Actions 出口 IP（2026-09-19 两份日志：
# 一次 200 OK、此前一次 wget 连接超时全败），不能作为唯一源。
# 主源走 GNU 官方镜像（runner 实测可达，与 libiconv/libunistring 同源），
# gmplib 仅作末位兜底。
url="${gnuMirrorURL}/gmp/gmp-${revision}.tar.xz"
backupUrls=(
  "https://ftpmirror.gnu.org/gmp/gmp-${revision}.tar.xz"
  "https://mirrors.kernel.org/gnu/gmp/gmp-${revision}.tar.xz"
  "https://gmplib.org/download/gmp/gmp-${revision}.tar.xz"
)
urlType="tar"
buildSys="autotools"
arch="aarch64 x86_64"
license="LGPL-3.0-or-later"
args="
  --enable-cxx
  --without-readline
"
deps="libc++"
pre_setup() {
  export CXXFLAGS+=" -L${prefix}/lib -Wl,-rpath=${prefix}/lib"
}