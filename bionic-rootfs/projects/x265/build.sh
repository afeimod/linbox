revision="3.4"
url="https://github.com/videolan/x265.git"
urlType="git"
arch="aarch64 x86_64"
pkgSrcDir="${srcDir}/x265/source"
buildSys="cmake"
license="GPL-2.0"
args="
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5
  -DENABLE_LIBNUMA=OFF
  -DENABLE_ASSEMBLY=OFF
"
deps="libc++"