revision="1.0"
urlType="local"
pkgSrcDir="${wsDir}/projects/pthread-stub"
arch="aarch64 x86_64"
buildSys="others"
license="public-domain"

# ============================================================
# pthread-stub — bionic pthread 链接桩
# ------------------------------------------------------------
# bionic 的 pthread_* 符号全部内建于 libc，不存在独立的
# libpthread.so。但大量 autotools 包（libX11 等）经 AX_PTHREAD
# 检测后会在 LIBS 里写死 -lpthread，导致链接阶段报
# "unable to find library -lpthread"。
# 本包生成空的 libpthread.a / libpthread.so 放入 rootfs：
#   链接期：-lpthread 命中空桩，pthread 符号实际由 libc 提供；
#   运行期：DT_NEEDED libpthread.so 由 rootfs 内空桩满足。
# 参考 termux 的同款做法。
# ============================================================

extra_fuction() {
  local _libDir="${destDir}${prefix}/lib"
  local _tmpC="/tmp/pthread-stub-empty.c"
  local _tmpO="${_libDir}/pthread-stub-empty.o"

  echo "生成 pthread stub（空 libpthread.a / libpthread.so）..."
  mkdir -p "${_libDir}"
  printf '/* pthread stub: bionic libc provides pthread_* */\n' > "${_tmpC}"
  $CC -c "${_tmpC}" -o "${_tmpO}" || { echo "stub 编译失败" && exit 1; }
  $AR rcs "${_libDir}/libpthread.a" "${_tmpO}" || { echo "stub .a 失败" && exit 1; }
  $CC -shared -nostdlib -o "${_libDir}/libpthread.so" "${_tmpO}" \
    || { echo "stub .so 失败" && exit 1; }
  rm -f "${_tmpO}" "${_tmpC}"
  echo "pthread stub 安装完成"
}
