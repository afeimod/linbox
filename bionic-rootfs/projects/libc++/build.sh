# 对应的ndk包内的动态库实际API版本不一定是NDK的API版本
revision="$api"
urlType="local"
buildSys="others"
arch="aarch64 x86_64"
license="NCSA"
agreementTargetFile="${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/NOTICE"

extra_fuction() {
  mkdir -p ${destDir}/${prefix}/lib/
  cp -r -p "${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${targetArch}-linux-android/libc++_shared.so" "${destDir}/${prefix}/lib/" || { exit 1 && echo "文件复制失败!" ;}
}