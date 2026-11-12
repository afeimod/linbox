revision="1.33.7"
url="https://mpg123.de/download/mpg123-${revision}.tar.bz2"
# mpg123.de 自建服务器带宽有限偶发超时，Debian pool 兜底
backupUrls=(
  "http://deb.debian.org/debian/pool/main/m/mpg123/mpg123_${revision}.orig.tar.bz2"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="LGPL-2.1 GPL-2.0"
args="
  --disable-components 
  --enable-libmpg123 
  --enable-libout123 
  --enable-libsyn12
"