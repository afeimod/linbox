revision="1.0.8"
url="https://sourceware.org/pub/bzip2/bzip2-${revision}.tar.gz"
# sourceware.org 曾对云厂商/CI 出口 IP 限流（GH runner 上连接超时，
# 旧 wget 无 --timeout 会傻等约 40 分钟才失败，这正是此前 rootfs
# 流水线 15~60 分钟不等、日志无报错就死掉的原因）。Debian pool 为
# Fastly CDN，runner 实测可达，作为主源不可用时的兜底。
backupUrls=(
  "http://deb.debian.org/debian/pool/main/b/bzip2/bzip2_${revision}.orig.tar.gz"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="others"
license="bzip2-1.0.6"

extra_fuction() {
  # 构建共享库
  make -f Makefile-libbz2_so
  # 构建静态库和工具
  make -j$(nproc)
  # 安装静态库、工具和头文件
  DESTDIR="${destDir}" make PREFIX="${prefix}" install
  # 手动安装共享库
  mkdir -p "${destDir}${prefix}/lib"
  cp -a libbz2.so* "${destDir}${prefix}/lib/"
}

# ===== backup: original autotools/make build =====
# extra_fuction() {
#   for targetArch in $_buildArchs; do
#     destDir="/tmp/build-${targetArch}"
#     cd "${_srcDir}"
#     load_env "$targetArch"
#     make clean 2>/dev/null || true
#     make CC="${CC}" CFLAGS="-O3 -pipe" LDFLAGS="${LDFLAGS}" libbz2.so -j$(nproc) || compile_err
#     mkdir -p "${destDir}/usr/lib" "${destDir}/usr/include"
#     cp -a libbz2.so* "${destDir}/usr/lib/"
#     cp -a bzlib.h "${destDir}/usr/include/"
#     package
#   done
# }
