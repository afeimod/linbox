revision="1.4.2"
url="${gnuMirrorURL}/libunistring/libunistring-${revision}.tar.gz"
# ftp.gnu.org 偶发对 CI 出口 IP 限流，ftpmirror/kernel.org 镜像兜底（runner 实测可达）
backupUrls=(
  "https://ftpmirror.gnu.org/libunistring/libunistring-${revision}.tar.gz"
  "https://mirrors.kernel.org/gnu/libunistring/libunistring-${revision}.tar.gz"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="GPL-3.0"
deps="libiconv"