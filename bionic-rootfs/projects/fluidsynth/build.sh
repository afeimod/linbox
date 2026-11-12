revision="v2.6.1"
url="https://github.com/FluidSynth/fluidsynth.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="LGPL-2.1"
# 注意写成字符串形式（与仓内其它 44 个包一致），不要用数组：build-rootfs-arch.sh
# 里以 ${args[@]} 展开，字符串会被按空白切成多个参数、数组则逐元素展开，两种都支持；
# 但早期脚本写的是 ${args:-}（等价于 ${args[0]}），数组形式下只有第 0 个元素生效，
# 其余 -Denable-* 会被静默丢弃 → CMake 落回上游默认值。
args="
  -DLIB_INSTALL_DIR=${prefix}/lib
  -Denable-pulseaudio=ON
  -Denable-opensles=ON
  -Denable-oboe=OFF
  -Denable-alsa=OFF
  -Denable-aufile=OFF
  -Denable-dbus=OFF
  -Denable-ipv6=OFF
  -Denable-jack=OFF
  -Denable-ladspa=OFF
  -Denable-libsndfile=OFF
  -Denable-midishare=OFF
  -Denable-network=OFF
  -Denable-oss=OFF
  -Denable-dsound=OFF
  -Denable-wasapi=OFF
  -Denable-waveout=OFF
  -Denable-winmidi=OFF
  -Denable-sdl3=OFF
  -Denable-pipewire=OFF
  -Denable-readline=OFF
  -Denable-threads=OFF
  -Denable-unicode=OFF
  -Denable-native-dls=OFF
  -Denable-signalsmith=OFF
  -Denable-systemd=OFF
  -Denable-coreaudio=OFF
  -Denable-coremidi=OFF
  -Denable-framework=OFF
  -Denable-dart=OFF
  -Denable-kai=OFF
  -Denable-portaudio=OFF
  -Denable-coverage=OFF
  -Denable-floats=OFF
  -Denable-fpe-check=OFF
  -Denable-profiling=OFF
  -Denable-trap-on-fpe=OFF
  -Denable-ubsan=OFF
  -Denable-openmp=OFF
"
deps="pulseaudio"

# OpenMP：链 NDK 里的静态运行时（libomp.a），不要共享档（libomp.so）
#
# NDK 的 clang 资源目录里 libomp 有两份（lib/clang/<ver>/lib/linux/<arch>/libomp.{so,a}，
# 按 target 架构分档），CMake 的 FindOpenMP 按默认的 .so;.a 顺序会挑中 .so → 产物带上
# `NEEDED libomp.so`；而 bionic 平台侧（NDK sysroot）与设备 rootfs 里都没有这份 so，
# 装到设备上必然 dlopen 失败。这里把 OpenMP 用的库钉到同目录的 libomp.a，符号直接进
# libfluidsynth.so，产物里不再留这份显式 so 依赖。
#
# 不动 CMAKE_FIND_LIBRARY_SUFFIXES：那会波及本包所有 find_library（例如 ${prefix}/lib
# 里的 libpulse-simple 会从 .so 被换成 .a）。取值走 wrapper clang 的 -print-file-name，
# 既不写死 clang 版本号，也自动跟随当前 target 架构。
# 注意：pre_setup 在 cmake 分支里是按架构各调一次的，cmakeBaseArgs 会因此多一份
# -DOpenMP_omp_LIBRARY（后者覆盖前者），与脚本里既有的 -DANDROID_ABI 行为一致。
# pre_setup() {
#   local _clang="${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/bin/${targetArch}-linux-android${api}-clang"
#   local _libomp _hit
#   _libomp="$("${_clang}" -print-file-name=libomp.a 2>/dev/null)"
#   # 找不到时 clang 会原样回显文件名 → 再按目录兜一次（仍不写死 clang 版本）
#   if [[ "$_libomp" != /* ]]; then
#     _hit=("${ndkDir}"/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux/"${targetArch}"/libomp.a)
#     [[ -f "${_hit[0]:-}" ]] || { err "fluidsynth: 找不到 ${targetArch} 的 libomp.a"; return 1; }
#     _libomp="${_hit[0]}"
#   fi
#   info "fluidsynth: OpenMP 静态运行时 => ${_libomp}"
#   cmakeBaseArgs+=(-Denable-openmp=ON "-DOpenMP_omp_LIBRARY=${_libomp}")
# }
