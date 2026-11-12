revision="1.1.8"
url="https://ftp.samba.org/pub/cwrap/resolv_wrapper-${revision}.tar.gz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD 3-Clause"

# bionic 把 resolv 的实现在 libc.so 里，没有独立的 libresolv.so。全局的
# CMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH 会让 find_library(resolv) 翻到宿主机
# 的 x86_64 libresolv.so，随后每个 check_function_exists 都在交叉链接这一步失败，
# 结果 HAVE_RES_*/HAVE___RES_* 全缺，resolv_wrapper.c 里的 #error 就对上了。
# 本包只依赖 base-config，不靠 prefix 里的库，所以把库查找收回 sysroot：
# 库找不到即 NOTFOUND，探测退回只链 libc，__res_* 就能从 bionic libc 里查到。
args=" -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY"

deps="base-config"

pre_setup() {
  CFLAGS+=" -DANDROID_CHANGES -DLIBC_SO=\\\"libc.so\\\""
}