revision="nettle_4.0_release_20260205"
url="https://github.com/gnutls/nettle.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="autotools"
license="GPL-2.0+ LGPL-3.0+"
agreementTargetFile="src/nettle/COPYING*"
args="--disable-documentation"
deps="gmp"
pre_setup() {
  ./.bootstrap || exit 1
}