revision="3.7.1"
url="https://github.com/libffi/libffi/releases/download/v${revision}/libffi-${revision}.tar.gz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# 注意：必须用官方发布 tarball（自带预生成的 configure 脚本）。
# 不能用 git 仓库：git 源码不含 configure，custom_configure 会触发 autogen.sh
# （autoreconf），在 ubuntu-24.04 runner 上因 LT_SYS_SYMBOL_USCORE 宏解析
# 失败而中断（autoconf/automake/libtool 版本组合问题）。tarball 中的
# configure 已预生成，直接跨编译配置，稳定可靠。
args="
  --disable-docs
  --disable-multi-os-directory
"
custom_configure() {
  if [[ ! -f configure ]]; then
    echo "configure 不存在，尝试运行 autogen.sh"
    if [[ -f autogen.sh ]]; then
      ./autogen.sh || { echo "autogen.sh 失败" && exit 1; }
    else
      echo "autogen.sh 也不存在，无法生成 configure"
      exit 1
    fi
  fi
  ./configure ${configureBaseArgs[@]} ${args[@]} || configure_err
  local _include_dir="$(ls -d *-linux-android)"
  if [[ ! -f ${_include_dir}/fficonfig.h ]]; then
    echo "fficonfig.h未生成"
    exit 1
  fi
  echo "#define FFI_MMAP_EXEC_WRIT 1" >> ${_include_dir}/fficonfig.h
}