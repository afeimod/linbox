revision="v0.7"
url="https://github.com/termux/libandroid-shmem.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="others"
license="BSD-3-Clause"
extra_fuction() {
  make -j`nproc` || { echo "编译失败!" && exit 1;}
  DESTDIR="${destDir}" PREFIX="${prefix}" make install
}