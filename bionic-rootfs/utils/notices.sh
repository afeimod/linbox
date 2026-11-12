#!/bin/bash
# 第三方组件协议清单生成
# 用法: gen_notices <输出目录> [包名...]
#   在 <输出目录>/share/licenses/THIRD_PARTY_NOTICES 写出组件 / 协议 / 来源三列表
#   不传包名时扫描 projects/*/build.sh；传了包名则只列这些包（用于只统计已打包的组件）
#   依赖: wsDir（脚本工作目录）、empty.sh、log.sh 的 ok()

gen_notices() {
  local _outDir="${1:-${prefix}}"
  shift
  local _noticesDir="${_outDir}/share/licenses"
  mkdir -p "${_noticesDir}"
  local _notices="${_noticesDir}/THIRD_PARTY_NOTICES"

  local _names=()
  if [[ $# -gt 0 ]]; then
    _names=("$@")
  else
    local _dir
    for _dir in "${wsDir}"/projects/*/; do
      [[ -f "${_dir}build.sh" ]] && _names+=("$(basename "$_dir")")
    done
  fi

  # 先取完字段再输出: source 放在重定向块外，防止 build.sh 里任何写到 stdout 的内容
  # 混进清单; 字段存进数组后统一格式化
  local _pjName
  local _buildFile
  local _license
  local _url
  local -a _rows=()
  for _pjName in "${_names[@]}"; do
    _buildFile="${wsDir}/projects/${_pjName}/build.sh"
    _license="未知"
    _url="无"
    if [[ -f "${_buildFile}" ]]; then
      . "${wsDir}/empty.sh"
      . "${_buildFile}"
      _license="${license:-未知}"
      _url="${url:-无}"
    fi
    _rows+=("${_pjName}" "${_license}" "${_url}")
  done
  . "${wsDir}/empty.sh"

  {
    echo "Android Bionic Rootfs 第三方组件协议清单"
    echo "本文件由 make-rootfs.sh 自动生成"
    echo
    printf "%-24s %-24s %s\n" "组件" "协议" "来源"
    echo "---------------------------------------------------------------"
    local _i
    for ((_i = 0; _i < ${#_rows[@]}; _i += 3)); do
      printf "%-24s %-24s %s\n" "${_rows[_i]}" "${_rows[_i + 1]}" "${_rows[_i + 2]}"
    done
  } >"${_notices}"
  ok "第三方组件协议清单已生成 => ${_notices}"
}
