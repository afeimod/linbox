revision="3.8.13"
url="https://www.gnupg.org/ftp/gcrypt/gnutls/v${revision%.*}/gnutls-${revision}.tar.xz"
# gnupg.org 自建 CDN 偶发对 CI 出口 IP 限流，Debian pool（Fastly CDN）兜底
backupUrls=(
  "http://deb.debian.org/debian/pool/main/g/gnutls28/gnutls28_${revision}.orig.tar.xz"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"

args="
  --enable-cxx
  --with-default-trust-store-file=${prefix}/etc/ca-certificates/cert.pem
  --disable-static
  --enable-shared
  --disable-doc
  --disable-tools
  --disable-hardware-acceleration
  --disable-openssl-compatibility
  --disable-tests
  --disable-nls
  --disable-guile
  --with-brotli
  --with-zlib
  --without-zstd
  --without-p11-kit
  --with-included-libtasn1
"
deps="base-config brotli gmp nettle libc++ libunistring libiconv zlib"
pre_setup() {
  CFLAGS+=" -DNO_INLINE_GETPASS=1"
}