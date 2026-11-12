# NDK API version
revision="29"
urlType="local"
pkgSrcDir="${wsDir}/projects/libandroid-stub"
arch="aarch64 x86_64"
buildSys="others"
license="NCSA"
doNotApplyPatch="1"
extra_fuction() {
  local common_flags=(
    $CFLAGS $LDFLAGS
    -Wno-deprecated-declarations
    -Wl,--no-use-android-relr-tags
    -Wl,--pack-dyn-relocs=android
  )

  # 平台 namespace 库：创建独立 linker namespace，只搜索 /system/lib64
  $CC -shared -fPIC \
    -o libtermux-platform-ns.so \
    "${pkgSrcDir}/platform-ns.c" \
    "${common_flags[@]}" || { echo "编译 libtermux-platform-ns.so 失败!" && exit 1; }

  # 所有代理库共享同一个 namespace，避免进程中出现两份 libbinder.so
  local stub
  for stub in android mediandk OpenSLES binder_ndk; do
    $CC -shared -fPIC \
      -o "lib${stub}.so" \
      "${pkgSrcDir}/lib${stub}-wrapper.c" \
      -I"${pkgSrcDir}" \
      "${common_flags[@]}" \
      -L. -ltermux-platform-ns || { echo "编译 lib${stub}.so 代理失败!" && exit 1; }
  done

  mkdir -p "${destDir}${prefix}/lib"
  install -Dm644 libtermux-platform-ns.so "${destDir}${prefix}/lib/libtermux-platform-ns.so"
  for stub in android mediandk OpenSLES binder_ndk; do
    install -Dm644 "lib${stub}.so" "${destDir}${prefix}/lib/lib${stub}.so"
  done

  rm -f *.so *.o
}
