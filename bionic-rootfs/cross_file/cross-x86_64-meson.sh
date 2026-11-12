#!/bin/bash
# Strip ccache prefix from compiler paths for Meson cross file
# Meson handles ccache via USE_CCACHE=1 environment variable
_cOnly="${CC#ccache }"
_cppOnly="${CXX#ccache }"

# 仅 sysroot 模式（PKG_CONFIG_SYSROOT_DIR 指向 sysroot 树）时追加 sysroot 头文件目录；
# 无 sysroot 时 CFLAGS/CXXFLAGS 已含 -I${prefix}/include，避免无效 -I 触发
# clang -Wmissing-include-dirs 警告
# Use exported ASM (yasm from NDK toolchain) or default path
_nasm="${ASM:-${wsDir}/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/yasm}"

# [built-in options] are derived from the current environment,
# so flags appended to CFLAGS/CXXFLAGS/LDFLAGS (e.g. in pre_setup) land in the cross file
_cArgs="${CFLAGS:-}"
_cppArgs="${CXXFLAGS:-}"
_linkArgs="${LDFLAGS:--L${prefix}/lib}"
if [[ -n "${PKG_CONFIG_SYSROOT_DIR:-}" ]]; then
  _cArgs="-I${PKG_CONFIG_SYSROOT_DIR}/usr/include -I${PKG_CONFIG_SYSROOT_DIR}/include ${_cArgs}"
  _cppArgs="-I${PKG_CONFIG_SYSROOT_DIR}/usr/include -I${PKG_CONFIG_SYSROOT_DIR}/include ${_cppArgs}"
fi

# join words into a quoted meson array string, e.g. 'a', 'b'
meson_args() {
  local _s="" _w
  for _w in "$@"; do
    [[ -n "$_s" ]] && _s+=", "
    _s+="'${_w}'"
  done
  printf '%s' "$_s"
}

cat > /tmp/cross-x86_64-meson.txt << EOF
[binaries]
c = '${_cOnly}'
cpp = '${_cppOnly}'
ar = '${AR}'
strip = '${STRIP}'
ranlib = '${RANLIB}'
ld = '${LD}'
nasm = '${_nasm}'

[host_machine]
system = '${mesonHostSystem:-android}'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'

[built-in options]
c_args = [$(meson_args ${_cArgs})]
cpp_args = [$(meson_args ${_cppArgs})]
c_link_args = [$(meson_args ${_linkArgs})]
cpp_link_args = [$(meson_args ${_linkArgs})]

[properties]
needs_exe_wrapper = true
${extraMesonCrossProps:-}
EOF
