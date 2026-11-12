# icu4c
revision="78.3"
url="https://github.com/unicode-org/icu/releases/download/release-${revision}/icu4c-${revision}-sources.tgz"
urlType="tar"
license="Unicode-DFS-2016"
pkgSrcDir="${srcDir}/icu/source"
arch="aarch64 x86_64"
buildSys="autotools"
args="--disable-samples --disable-tests"
custom_configure() {
  # 参考宿主工具方式：主机 ICU 构建目录持久保存到 host_tools/icu，
  # 交叉编译通过 --with-cross-build 引用，避免 get_src 清空 src 后每次重建主机 ICU
  local hostTools="${wsDir}/host_tools/icu"
  local hostBuildDir="${hostTools}/host_build"
  # 版本一致才跳过宿主机构建
  local hostVer=""
  if [[ -f "${hostTools}/.icu-version" ]]; then
    hostVer="$(cat "${hostTools}/.icu-version")"
  fi
  if [[ "$hostVer" != "$revision" || ! -f "${hostBuildDir}/config/icucross.mk" ]]; then
    echo "构建宿主机 ICU..."
    load_env_host
    rm -rf "${hostTools}"
    mkdir -p "${hostBuildDir}"
    cd "${hostBuildDir}" || { echo "ICU: cd host_build failed" && exit 1; }
    "${pkgSrcDir}/runConfigureICU" Linux/gcc || { echo "ICU: host configure failed" && exit 1; }
    make -j$(nproc) || { echo "ICU: host build failed" && exit 1; }
    echo "$revision" > "${hostTools}/.icu-version"
  fi
  load_env "$targetArch"
  cd "${pkgSrcDir}" || { echo "ICU: cd pkgSrcDir failed" && exit 1; }
  mkdir -p "${targetArch}_build"
  cd "${targetArch}_build" || { echo "ICU: cd ${targetArch}_build failed" && exit 1; }
  ../configure ${configureBaseArgs[@]} ${args[@]} --with-cross-build="${hostBuildDir}" || configure_err
}