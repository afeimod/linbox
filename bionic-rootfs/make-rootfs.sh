#!/bin/bash
set -euo pipefail

wsDir="$(dirname "$(readlink -f "$0")")"
pkgDir="${wsDir}/pkgs"
. "${wsDir}/utils/log.sh"
. "${wsDir}/utils/notices.sh"

# 固定 UTC：解压/校验时的时间处理不随构建者时区变化
export TZ=UTC

exportPath=""
targetArch=""
liteMode=false
# 解压成功后立即删除对应包 tar（CI 磁盘紧张时启用，把组装峰值占用从
# (pkgs+导出) 降为 (pkgs+单包)）；默认关闭——保留 pkgs 便于重复导出
rmPkgs=false

usage() {
  echo "用法: $0 --export <目标路径> --arch <架构> [--lite] [--rm-pkgs]"
  echo "选项:"
  echo "  --export <路径>    指定解压目标路径"
  echo "  --arch <架构>      指定架构 (aarch64|x86_64)"
  echo "  --lite             移除头文件/静态库/文档等仅编译期使用的文件"
  echo "  --rm-pkgs          每个包解压成功后立即删除其 tar（磁盘紧张时用）"
  echo "  --help             显示此帮助信息"
  exit 0
}

normalize_arch() {
  case "$1" in
  arm64) echo "aarch64" ;;
  amd64) echo "x86_64" ;;
  aarch64|x86_64) echo "$1" ;;
  *) return 1 ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
  --export)
    [[ -z "${2:-}" ]] && { err "--export 需要指定目标路径"; exit 1; }
    exportPath="$2"
    shift 2
    ;;
  --arch)
    [[ -z "${2:-}" ]] && { err "--arch 需要指定架构"; exit 1; }
    targetArch="$(normalize_arch "$2")" || { err "不支持的架构 => $2 (仅支持 aarch64|x86_64)"; exit 1; }
    shift 2
    ;;
  --lite)
    liteMode=true
    shift
    ;;
  --rm-pkgs)
    rmPkgs=true
    shift
    ;;
  --help | -h)
    usage
    ;;
  *)
    err "未知选项: $1"
    usage
    ;;
  esac
done

[[ -z "$exportPath" ]] && { err "必须指定 --export <目标路径>"; usage; }
[[ -z "$targetArch" ]] && { err "必须指定 --arch <架构>"; usage; }
[[ ! -d "$pkgDir" ]] && { err "包目录不存在 => $pkgDir"; exit 1; }

mkdir -p "$exportPath"

shopt -s nullglob
pkgFiles=("${pkgDir}"/*-"${targetArch}".tar)
shopt -u nullglob

[[ ${#pkgFiles[@]} -eq 0 ]] && {
  err "未找到匹配的包 (arch=${targetArch})"
  echo "pkgs 目录内容（前 20 项）:"
  ls -lh "${pkgDir}" 2>/dev/null | head -20 || true
  exit 1
}

# ---- 磁盘空间预检：解压目标需要至少与包体积相当的空间 ----
# 注意：du/df 输出必须校验为纯数字后才参与比较，避免诊断脚本本身
# 因异常输入（空值/非数字）产生二次错误或误判。
needKB="$(du -sk "${pkgDir}" | cut -f1 | tr -d '[:space:]')"
freeKB="$(df -Pk "$exportPath" | awk 'NR==2{print $4}' | tr -d '[:space:]')"
if [[ "$needKB" =~ ^[0-9]+$ && "$freeKB" =~ ^[0-9]+$ ]]; then
  info "包总计: $((needKB / 1024))MB (${#pkgFiles[@]} 个), 解压目标可用: $((freeKB / 1024))MB"
  if [[ "$freeKB" -lt "$needKB" ]]; then
    err "磁盘空间不足：解压需约 $((needKB / 1024))MB，仅剩 $((freeKB / 1024))MB"
    echo "提示：请确认组装步骤已清理 src/、/data/data 前缀、/tmp/build-* 等构建残留。"
    df -h "$exportPath" | tail -2
    exit 2
  fi
else
  warn "磁盘预检数值异常 (needKB='${needKB}' freeKB='${freeKB}')，跳过预检继续组装"
fi

# @package 同名包多 revision 去重：每个项目只解压最新的 tar
# 包文件名为 <pjName>-<revision>-<arch>.tar，pjName 与 revision 都可能含 '-'，
# 因此用 projects/ 下已知项目名做最长前缀匹配分组
_pjNames=()
for _dir in "${wsDir}/projects"/*/; do
  [[ -d "$_dir" ]] && _pjNames+=("$(basename "$_dir")")
done
# 按项目名长度降序排列，保证最长前缀匹配（如 wine 与 wine-amd64）
IFS=$'\n' _pjNames=($(printf '%s\n' "${_pjNames[@]}" | awk '{print length, $0}' | sort -rn | cut -d' ' -f2-))
unset IFS

_groupOf() {
  local _base="${1%-${targetArch}.tar}"
  local _pj
  for _pj in "${_pjNames[@]}"; do
    [[ "$_base" == "${_pj}-"* ]] && { echo "$_pj"; return 0; }
  done
  # 未匹配到已知项目（如 legacy 包）：回退为去掉末段 revision 的名字
  echo "${_base%-*}"
}

declare -A _latestByPj_=()
for pkg in "${pkgFiles[@]}"; do
  _key="$(_groupOf "$(basename "$pkg")")"
  _sep=""
  [[ -n "${_latestByPj_[${_key}]:-}" ]] && _sep=$'\n'
  _latestByPj_["${_key}"]+="${_sep}${pkg}"
done

pkgFiles=()
for _key in "${!_latestByPj_[@]}"; do
  _count=$(sort -uV <<< "${_latestByPj_[${_key}]}" | wc -l)
  _latest="$(sort -uV <<< "${_latestByPj_[${_key}]}" | tail -n 1)"
  [[ "$_count" -gt 1 ]] && warn "包 ${_key} 存在多个 revision, 使用最新 => $(basename "$_latest")"
  pkgFiles+=("$_latest")
done
# 保持与文件名排序一致的解压顺序，确保结果可复现
IFS=$'\n' pkgFiles=($(printf '%s\n' "${pkgFiles[@]}" | sort))
unset IFS

# @lite
# 运行时不需要编译期产物：头文件、静态库、*.la、pkg-config/cmake 元数据、
# aclocal/libtool 宏、man/doc/info、vala/gdb 支持文件、vulkan 注册表等。
# 只裁剪导出目录，pkgs/ 下的包保持完整，随时可以重新导出非 lite 版本。
slim_rootfs() {
  local _root="$1"
  local _removed=0
  local _path
  local _file

  local _dirs=(
    usr/include
    usr/man
    usr/share/man
    usr/share/doc
    usr/share/info
    usr/share/aclocal
    usr/share/libtool
    usr/share/cmake
    usr/share/pkgconfig
    usr/share/gir-1.0
    usr/share/vala
    usr/share/gdb
    usr/share/gettext
    usr/share/wayland-protocols
    usr/share/icu
    usr/share/xml
    usr/share/ffmpeg/examples
    usr/share/vulkan/registry
    usr/lib/pkgconfig
    usr/lib/cmake
    usr/lib/icu
  )
  for _path in "${_dirs[@]}"; do
    [[ -e "${_root}/${_path}" ]] || continue
    info "移除编译期目录 => ${_path}"
    rm -rf "${_root:?}/${_path}"
    _removed=$((_removed + 1))
  done

  # 静态库与 libtool 归档只用于链接，运行时不会加载
  while IFS= read -r -d '' _file; do
    rm -f "${_file}"
    _removed=$((_removed + 1))
  done < <(find "${_root}/usr" -type f \( -name '*.a' -o -name '*.la' \) -print0)

  # *-config 与 libtool 只服务于 configure/编译
  local _configs=()
  shopt -s nullglob
  _configs=("${_root}"/usr/bin/*-config "${_root}"/usr/bin/libtool*)
  shopt -u nullglob
  for _file in "${_configs[@]}"; do
    info "移除编译期工具 => ${_file#"${_root}/"}"
    rm -f "${_file}"
    _removed=$((_removed + 1))
  done

  info "精简完成: 移除 ${_removed} 项"
}

info "解压 ${#pkgFiles[@]} 个包到 ${exportPath} (arch=${targetArch})"

# ---- 逐包解压：单包失败不中断，最后汇总报告（便于一眼定位问题包） ----
errFile="/tmp/.make-rootfs-err.$$"
failedPkgs=()
for pkg in "${pkgFiles[@]}"; do
  info "解压 => $(basename "$pkg")"
  if tar -xf "$pkg" -C "$exportPath" 2>"$errFile"; then
    [[ "$rmPkgs" == true ]] && rm -f -- "$pkg"
  else
    err "解压失败 => $(basename "$pkg")"
    # 注意顺序：head 先截断再交给 sed，避免 sed 在长错误清单下触发 SIGPIPE/pipefail
    head -15 "$errFile" | sed 's/^/    /' || true
    failedPkgs+=("$(basename "$pkg")")
  fi
done
rm -f "$errFile"

if [[ ${#failedPkgs[@]} -gt 0 ]]; then
  err "共 ${#failedPkgs[@]} 个包解压失败: ${failedPkgs[*]}"
  echo "=== 解压目标磁盘状态 ==="
  df -h "$exportPath" | tail -2
  exit 2
fi

# 读取 prefix（rootfsBase），创建 usr-merge 标准软链接
source "${wsDir}/default.conf"
_rootfsRoot="${exportPath}${prefix%/*}"

if [[ -d "${_rootfsRoot}/usr" ]]; then
  info "创建 usr-merge 软链接: ${_rootfsRoot}"
  for _dir in bin lib sbin lib32 lib64 libexec etc var; do
    [[ -d "${_rootfsRoot}/usr/${_dir}" && ! -e "${_rootfsRoot}/${_dir}" ]] && {
      ln -sf "usr/${_dir}" "${_rootfsRoot}/${_dir}"
      info "  ${_dir} -> usr/${_dir}"
    }
  done
fi

# ---- SONAME 版本别名兜底 ----
# 构建系统遵循 Termux/Android 惯例产出无版本号 .so（bionic 按文件名加载），
# 但 wine 等预编译产物的 DT_NEEDED 记录上游标准 SONAME（libX11.so.6 等），
# 缺别名即加载失败。各项目的 install() 现已在包内携带别名；此处再按映射表
# 统一补一遍：兼容【别名修复前产出的存量包】，也让"只重建个别包"的组装
# 场景无需全量重编。版本号全部取自上游源码：
#   X11 系  : <lib>/src/Makefile.am 的 libtool -version-number major:minor:micro
#   libxcb 系: src/Makefile.am 的 -version-info current:revision:age（SONAME=current-age）
#   meson 系 : 各 meson.build 的 version/soversion 字段
declare -A _sonameMap_=(
  [libX11.so]="libX11.so.6"
  [libX11-xcb.so]="libX11-xcb.so.1"
  [libXext.so]="libXext.so.6"
  [libXrender.so]="libXrender.so.1"
  [libXfixes.so]="libXfixes.so.3"
  [libXrandr.so]="libXrandr.so.2"
  [libXi.so]="libXi.so.6"
  [libXcursor.so]="libXcursor.so.1"
  [libXinerama.so]="libXinerama.so.1"
  [libXcomposite.so]="libXcomposite.so.1"
  [libXxf86vm.so]="libXxf86vm.so.1"
  [libXv.so]="libXv.so.1"
  [libXdamage.so]="libXdamage.so.1"
  [libxshmfence.so]="libxshmfence.so.1"
  [libXau.so]="libXau.so.6"
  [libXdmcp.so]="libXdmcp.so.6"
  [libxkbcommon.so]="libxkbcommon.so.0"
  [libxkbcommon-x11.so]="libxkbcommon-x11.so.0"
  [libdrm.so]="libdrm.so.2"
  [libdrm_amdgpu.so]="libdrm_amdgpu.so.1"
  [libdrm_freedreno.so]="libdrm_freedreno.so.1"
  [libGL.so]="libGL.so.1"
  [libEGL.so]="libEGL.so.1"
  [libGLESv2.so]="libGLESv2.so.2"
  [libgbm.so]="libgbm.so.1"
)
_libFixes=0
for _base in "${!_sonameMap_[@]}"; do
  _soname="${_sonameMap_[${_base}]}"
  if [[ -e "${_rootfsRoot}/usr/lib/${_base}" && ! -e "${_rootfsRoot}/usr/lib/${_soname}" ]]; then
    ln -sf -- "$_base" "${_rootfsRoot}/usr/lib/${_soname}"
    _libFixes=$((_libFixes + 1))
  fi
done
# libxcb 核心与全部扩展库（上游 -version-info：核心/sync/xkb → .1，其余 → .0）
for _f in "${_rootfsRoot}"/usr/lib/libxcb*.so; do
  [[ -f "$_f" ]] || continue
  _base="$(basename "$_f")"
  case "$_base" in
    libxcb.so | libxcb-sync.so | libxcb-xkb.so) _v=1 ;;
    *) _v=0 ;;
  esac
  if [[ ! -e "${_rootfsRoot}/usr/lib/${_base}.${_v}" ]]; then
    ln -sf -- "$_base" "${_rootfsRoot}/usr/lib/${_base}.${_v}"
    _libFixes=$((_libFixes + 1))
  fi
done
info "SONAME 版本别名: 本次补建 ${_libFixes} 个（包内已携带的自动跳过）"

# ---- 绝对符号链接重写：让导出树自包含 ----
# meson 的 install_symlink（如 xkeyboard-config compat-rules 的
# share/X11/xkb -> <prefix>/share/xkeyboard-config-2）会生成指向设备最终
# 前缀 /data/data/com.linbox/files/rootfs 的绝对符号链接。该前缀在构建机
# 上并不存在（组装前已清理），导出树内的这类链接全部为断链：产物校验的
# [[ -e ]] 跟随链接会误判缺失，设备侧也被迫依赖固定挂载路径。这里把
# "指向导出树自身"的绝对链接统一改写为相对链接；指向树外（App 侧前缀
# 等）的链接不在导出树内、不属于 rootfs 自身内容，保持原样。
while IFS= read -r -d '' link; do
  tgt="$(readlink -- "$link")" || continue
  case "$tgt" in
    "$rootfsBase" | "$rootfsBase"/*) ;;
    *) continue ;;
  esac
  absTgt="${exportPath}${tgt}"
  if [[ ! -e "$absTgt" && ! -L "$absTgt" ]]; then
    warn "符号链接目标不在导出树内，保持原样: ${link#"$exportPath"/} -> $tgt"
    continue
  fi
  newTgt="$(realpath -m --relative-to="$(dirname -- "$link")" "$absTgt")" || {
    warn "无法计算相对路径（保留原样）: ${link#"$exportPath"/}"
    continue
  }
  if ln -snf -- "$newTgt" "$link" 2>/dev/null; then
    info "重写绝对符号链接 => ${link#"$exportPath"/}: '$tgt' -> '$newTgt'"
  else
    warn "符号链接重写失败（保留原样）: ${link#"$exportPath"/}"
  fi
done < <(find "$exportPath" -type l -print0)

[[ "$liteMode" == true && -d "${_rootfsRoot}/usr" ]] && slim_rootfs "${_rootfsRoot}"

# 第三方组件协议清单：写在导出目录里，确保它真的进入 rootfs（构建阶段写在
# ${prefix} 的话既不进任何 tar，也只会给宿主机留一棵空目录树）
gen_notices "${exportPath}${prefix}" "${!_latestByPj_[@]}"

info "=== 组装完成概览 ==="
du -sh "$exportPath" 2>/dev/null || true
ok "完成"
