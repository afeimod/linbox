revision="1.3.7"
url="https://downloads.xvid.com/downloads/xvidcore-${revision}.tar.gz"
# downloads.xvid.com 偶发不可达，Debian pool 兜底（Debian 源为 bz2 压缩，
# get_src 的 tar 解包按实际压缩格式处理，与主源 gz 等价）
backupUrls=(
  "http://deb.debian.org/debian/pool/main/x/xvidcore/xvidcore_${revision}.orig.tar.bz2"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="GPL-2.0"
pkgSrcDir="${srcDir}/xvidcore/build/generic"