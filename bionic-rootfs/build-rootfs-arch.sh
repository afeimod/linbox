#!/bin/bash
wsDir="$(dirname $(readlink -f "$0"))"
. "${wsDir}/utils/log.sh"

# 固定 UTC：源码/工具的日志与内嵌时间戳不随构建者时区变化，便于跨机器比对产物
export TZ=UTC

# 固定 UTF-8 locale：宿主未导出 LC_ALL 时各发行版默认不同（C / POSIX / C.UTF-8），
# 凡是把字节当字符处理的地方（日志、文件名、sort/grep 输出）都会随环境分叉。
# 首选 en_US.UTF-8：host-deps.sh 已在两个分支跑过 locale-gen 生成它，wine 的
# UTF-8 代码页转换与 gettext 目录都以它为准，退到 C.UTF-8 会让 wine 按另一套
# 区域设置走。仍做可用性探测是防 host-deps.sh 没跑过——设一个本机不存在的
# locale，glibc 会给每条外部命令打 setlocale 警告，反而污染日志。
# 比较前先去掉连字符并转小写，因为 locale -a 的名字在各 glibc 上是
# en_US.utf8 或 en_US.UTF-8 两种写法。
if [[ -z "${LC_ALL}" ]]; then
  _locales="$(locale -a 2>/dev/null | LC_ALL=C tr -d '-' | tr '[:upper:]' '[:lower:]')"
  for _lc in en_US.UTF-8 C.UTF-8 C; do
    _key="${_lc,,}"
    _key="${_key//-/}"
    if printf '%s\n' "${_locales}" | grep -qx "${_key}"; then
      export LC_ALL="${_lc}"
      break
    fi
  done
  unset _locales _lc _key
fi

guard_dev_null() {
  if [[ ! -c /dev/null ]]; then
    warn "/dev/null 不是字符设备，正在修复..."
    rm -rf /dev/null
    mknod -m 666 /dev/null c 1 3 || { err "无法创建 /dev/null" && exit 1; }
  fi
}
guard_dev_null

# v3.2.9：磁盘水位监控（proton arm64ec runner 失联无日志的根因防御）
# GitHub Actions runner 磁盘写满时 runner 进程自身无法写日志/心跳，服务端
# 标记 job 失败且一切后续步骤（含 if:always() 的日志上传）全部 skipped
#（CI run 41287894412 实证：构建步骤 failure、上传日志 skipped、连 Post
# 缓存步骤都 skipped）。wine arm64ec 构建链（ccache + wine 树 + 3 目标
# PE 编译产物）在 ubuntu-24.04 标准盘上接近满载。每包开始前检查水位：
# 低于 4G 清理 ccache 缓存并告警；仍低于 2G 硬失败（明确报错优于
# runner 失联无日志）。
disk_watermark_guard() {
  local _avail _avail2
  _avail=$(df -BG --output=avail / 2>/dev/null | tail -1 | tr -dc '0-9')
  [[ -n "${_avail:-}" ]] || return 0
  if (( _avail < 4 )); then
    warn "磁盘剩余 ${_avail}G < 4G：清理 ccache 缓存释放空间..."
    ccache -c >/dev/null 2>&1 || true
    _avail2=$(df -BG --output=avail / 2>/dev/null | tail -1 | tr -dc '0-9')
    if [[ -n "${_avail2:-}" ]] && (( _avail2 < 2 )); then
      err "磁盘剩余 ${_avail2}G，不足以继续构建 —— 请扩大 runner 磁盘或减少缓存"
      df -h / || true
      exit 1
    fi
  fi
}

if [[ -f ${wsDir}/custom.conf ]]; then
  info "加载自定义配置文件"
  . ${wsDir}/custom.conf
elif [[ -f ${wsDir}/default.conf ]]; then
  . ${wsDir}/default.conf
fi
gnuMirrorURL="${gnuMirrorURL:-https://ftp.gnu.org/pub/gnu}"
# 目标架构列表 (通过 --arch 指定)
targetArchs=()

# 重建模式标志（--rebuild-deps 和 --rebuild all 时使用，全量删除重建）
rebuildMode=false
# 指定要重建的目标包名（--rebuild pkg 时使用，仅删该包的 tar）
rebuildTarget=""

# 跳过源码获取标志（--has-src 时使用，src 下已有源码则跳过 get_src）
hasSrc=false
# 复用已有 prefix 标志（--has-prefix 时使用，跳过依赖收集与包解压）
hasPrefix=false
# 强制重新下载 NDK 标志（--update-ndk 时使用）
updateNdk=false

mkdir -p "${pkgDir}"
mkdir -p "${srcDir}"
mkdir -p "${ndkDir}"

# @declare
# 反向图: rgraph_[dep] = 需要 dep 的项目列表
declare -A _rgraph_
# 入度
declare -A _indeg_
# 访问
declare -A _visited_
declare -a _allPkgs_
declare -a _buildOrder_



# @license
# 第三方协议清单由 utils/notices.sh 的 gen_notices 生成, 在 make-rootfs.sh 导出 rootfs
# 时写入 ${prefix}/share/licenses/THIRD_PARTY_NOTICES —— 不在这里生成: package() 只把
# ${destDir} 打进 tar, 而 ${prefix} 是构建机上的临时目录, 写在那里既进不了任何包, 也会
# 在宿主机上遗留一棵空的 /data/data/... 目录树

# @help
# 用法: usage [退出码] —— 参数错误时传 1, 让脚本以失败退出
usage() {
  echo "用法: $0 [选项]"
  echo "选项:"
  echo "  --build <pjName|all>         构建指定包或所有包 (可空格分隔多个)"
  echo "  --rebuild <pjName|all>       仅重建指定包 (可空格分隔多个, 依赖不解构)"
  echo "  --rebuild-deps <pjName|all>  重建指定包及其所有依赖 (可空格分隔多个)"
  echo "  --arch <archs>              指定目标架构 (arm64|aarch64 x86_64|amd64|all), 默认 all"
  echo "  --has-src               src 下已有对应源码时跳过代码获取"
  echo "  --has-prefix            复用已有 prefix，跳过依赖构建与包解压"
  echo "  --update-ndk          删除并重新下载 NDK 后应用补丁"
  echo "  --load-env [arch]     加载环境变量用于调试, 默认 aarch64"
  echo "  --clean-ccache        清理 ccache 缓存"
  echo "  --update-host-deps    更新主机依赖"
  echo "  --help                显示此帮助信息"
  echo ""
  echo "以上选项可任意先后顺序书写: 动作类选项(--build/--rebuild/--rebuild-deps)只登记,"
  echo "解析完整个命令行才执行, 因此 --arch 写在 --build 后面同样生效。"
  exit "${1:-0}"
}

# 架构名归一化: arm64->aarch64, amd64->x86_64
normalize_arch() {
  case "$1" in
  arm64) echo "aarch64" ;;
  amd64) echo "x86_64" ;;
  *) echo "$1" ;;
  esac
}

# 检查包的 arch 是否包含指定架构
arch_contains() {
  local _pkgArchs="$1"
  local _target="$2"
  local _a
  for _a in $_pkgArchs; do
    [[ "$_a" == "$_target" ]] && return 0
  done
  return 1
}

# 获取包与目标架构的交集
filter_archs() {
  local _pkgArchs="$1"
  local _filtered=()
  local _a
  for _a in ${_pkgArchs[@]}; do
    if [[ ${#targetArchs[@]} -eq 0 ]]; then
      _filtered+=("$_a")
    else
      local _t
      for _t in "${targetArchs[@]}"; do
        [[ "$_a" == "$_t" ]] && {
          _filtered+=("$_a")
          break
        }
      done
    fi
  done
  echo "${_filtered[@]}"
}

clean_ccache() {
  info "清理 ccache 缓存..."
  local _ccDir
  for _ccDir in "${wsDir}/bionic_ccache" "${wsDir}/host_ccache" "${wsDir}/cross_ccache"; do
    if [[ -d "$_ccDir" ]] && cd "$_ccDir"; then
      rm -rf ./*
      cd "$wsDir"
    fi
  done
  ok "ccache 缓存已清理"
}

# @ndk-patch

# 将 stdin 中的 @占位符@ 按 patch-rules.conf 映射替换为配置变量值，写到 stdout。
# 映射格式: @占位符@ 变量名（变量须已在 default.conf/custom.conf 定义）。
# 替换在 bash 层完成；bash 5.2+ 的 patsub_replacement 会把替换串里的 & 展开为
# 匹配文本，函数内临时禁用以免路径值被误解释；
# 变量未定义、或替换后仍残留 @TERMUX_*/@ROOTFS_* 占位符时返回失败，
# 避免把字面量写进源码/sysroot（libsndfile 的 @AUTOGEN_BIN@ 等
# automake 自动替换变量不在残留检查范围）。
expand_patch_placeholders() {
  local _rulesFile="${wsDir}/patch-rules.conf"
  local _content _placeholder _varName
  local _patsubWas=""
  # bash < 5.2 无此选项，shopt -p 失败时 _patsubWas 保持空、跳过恢复
  _patsubWas="$(shopt -p patsub_replacement 2>/dev/null)" && shopt -u patsub_replacement

  if [[ ! -f "$_rulesFile" ]]; then
    err "缺少补丁替换规则文件 => ${_rulesFile}" >&2
    [[ -n "$_patsubWas" ]] && eval "$_patsubWas"
    return 1
  fi

  _content="$(cat)"

  while read -r _placeholder _varName; do
    [[ -z "${_placeholder:-}" || "$_placeholder" == \#* ]] && continue
    if [[ -z "${_varName:-}" ]]; then
      err "补丁规则缺少变量名 => ${_placeholder}" >&2
      [[ -n "$_patsubWas" ]] && eval "$_patsubWas"
      return 1
    fi
    if [[ ! -v "$_varName" ]]; then
      err "补丁规则引用了未定义变量 => ${_placeholder} -> \${${_varName}}" >&2
      [[ -n "$_patsubWas" ]] && eval "$_patsubWas"
      return 1
    fi
    _content="${_content//${_placeholder}/${!_varName}}"
  done < "$_rulesFile"

  if [[ "$_content" =~ @(TERMUX|ROOTFS)_[A-Za-z0-9_]+@ ]]; then
    err "补丁占位符未被替换 => ${BASH_REMATCH[0]}（patch-rules.conf 缺少该规则）" >&2
    [[ -n "$_patsubWas" ]] && eval "$_patsubWas"
    return 1
  fi

  [[ -n "$_patsubWas" ]] && eval "$_patsubWas"
  printf '%s\n' "$_content"
}

apply_ndk_patches() {
  local _ndkVersion
  # 从ndkURL中提取版本号，例如"r29"
  _ndkVersion=$(echo "$ndkURL" | grep -oP 'r\d+[a-z]*' | head -1)
  [[ -z "$_ndkVersion" ]] && { err "无法从ndkURL中提取NDK版本" && exit 1; }

  local _patchDir="${wsDir}/ndk_patches/${_ndkVersion}"
  [[ ! -d "$_patchDir" ]] && { warn "NDK补丁目录不存在: $_patchDir" && return 0; }

  local _patchFiles
  # 按文件名字典序应用：目录读取顺序不保证，且下方状态指纹由补丁内容拼接得出，
  # 顺序漂移会让指纹变化、误判为补丁已变更而重下 NDK，故用 LC_ALL=C 固定排序
  _patchFiles=($(ls "$_patchDir"/*.patch 2>/dev/null | LC_ALL=C sort))
  [[ ${#_patchFiles[@]} -eq 0 ]] && { info "NDK版本 $_ndkVersion 无需应用任何补丁" && return 0; }

  local _ndkSysroot="${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
  [[ ! -d "$_ndkSysroot" ]] && { warn "NDK sysroot不存在: $_ndkSysroot" && return 0; }

  # 补丁占位符替换规则与 apply_patches 共用 patch-rules.conf
  [[ -f "${wsDir}/patch-rules.conf" ]] || { err "缺少补丁替换规则文件 => ${wsDir}/patch-rules.conf" && exit 1; }

  # 状态键 = 路径配置 + 补丁/规则内容指纹；任一变化都需重新下载并打补丁。
  # 旧状态文件只存 prefix，格式不匹配会触发一次全量重下，顺带修复历史坏补丁
  # （旧双层 sed 规则漏展开 ${rootfsHome} 时写进 pwd.h 的字面量）。
  # 指纹排除 patch-rules.conf 注释行，改注释不会触发重下 NDK。
  local _fingerprint
  _fingerprint="$(
    {
      cat "${_patchFiles[@]}"
      sed -e 's/#.*$//' -e '/^[[:space:]]*$/d' "${wsDir}/patch-rules.conf"
    } | sha256sum | cut -d' ' -f1
  )"
  local _stateKey="v2|${prefix}|${rootfsHome}|${rootfsBase}|${_fingerprint}"

  local _stateFile="${ndkDir}/.ndk_patches_state"
  local _lastState=""
  [[ -f "$_stateFile" ]] && _lastState="$(<"$_stateFile")"

  if [[ "$_lastState" == "$_stateKey" ]]; then
    info "NDK补丁已应用，配置与补丁未变化，跳过"
    return 0
  fi

  # 状态变化（含旧版仅存 prefix 的状态）时重新下载 NDK：
  # 补丁无法在已污染的 sysroot 上安全重打，只能整包重来
  if [[ -n "$_lastState" ]]; then
    info "NDK补丁状态变化（路径配置或补丁内容变更），重新下载NDK..."
    rm -rf "$ndkDir"
    return 0
  fi

  info "首次应用NDK补丁..."

  cd "$_ndkSysroot"

  local _patch _expanded
  for _patch in "${_patchFiles[@]}"; do
    if ! _expanded="$(expand_patch_placeholders < "$_patch")"; then
      err "补丁占位符展开失败=> $_patch" && exit 1
    fi
    if printf '%s\n' "$_expanded" | patch -p1 --dry-run >/dev/null 2>&1; then
      info "测试通过=> $_patch"
      info "应用NDK补丁=> $_patch"
      printf '%s\n' "$_expanded" | patch --no-backup-if-mismatch -N -p1 || { err "NDK补丁应用失败=> $_patch" && exit 1; }
    else
      err "NDK补丁应用失败=> $_patch" && exit 1
    fi
  done

  # 程序真找不到头文件再放，纯clang编译头文件放进去将会导致wine dinput编译失败
  # local _headerFiles
  # _headerFiles=($(find "$_patchDir" -maxdepth 1 -name "*.h" 2>/dev/null))
  # if [[ ${#_headerFiles[@]} -gt 0 ]]; then
  #   local _includeDir="${_ndkSysroot}/usr/include"
  #   mkdir -p "$_includeDir"
  #   for _header in "${_headerFiles[@]}"; do
  #     echo "注入头文件=> $($_header)"
  #     cp "$_header" "$_includeDir/"
  #   done
  # fi

  # 保存状态键到状态文件
  printf '%s\n' "$_stateKey" >"$_stateFile"

  cd -
}

# 校验/补齐 NDK：补丁状态变化或 NDK 缺失时才下载。
# 只在真正要构建或显式 --update-ndk 时调用，避免 --help 之类的命令触发下载。
ensure_ndk() {
  # 应用NDK补丁（会在prefix变化时重新下载NDK）
  apply_ndk_patches

  # 如果NDK目录不存在（可能被apply_ndk_patches删除），则下载
  if [[ ! -d "${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
    info "NDK 不存在，开始下载..."
    mkdir -p "$ndkDir"
    # 783MB 大文件：--timeout 限制单次连接/读取阻塞时长，--tries 自动重试，
    # 断点续传 -c；此前无任何容错参数，CI 网络一次抖动就废掉整轮构建。
    # 且下载后必须校验完整性（unzip 对损坏 zip 会中途报错但 wget 已返回 0）。
    local _ndkTries=0
    local _ndkOk=0
    while (( _ndkTries < 3 )); do
      (( _ndkTries++ ))
      if wget -c -nv --timeout=120 --tries=3 --waitretry=5 -O "/tmp/ndk.zip" "$ndkURL" \
          && unzip -t /tmp/ndk.zip >/dev/null 2>&1; then
        _ndkOk=1
        break
      fi
      warn "NDK 下载/校验失败（第 ${_ndkTries}/3 次），重试..."
      rm -f /tmp/ndk.zip
    done
    [[ "${_ndkOk}" == "1" ]] || { err "NDK 下载或完整性校验失败 => ${ndkURL}"; return 1; }

    rm -rf /tmp/ndk-tmp
    mkdir /tmp/ndk-tmp

    #7z x /tmp/ndk.zip -o/tmp/ndk-tmp
    # 7z 解压可能导致链接损坏
    unzip /tmp/ndk.zip -d /tmp/ndk-tmp

    mv /tmp/ndk-tmp/*/* "${ndkDir}/"

    rm -f /tmp/ndk.zip
    rm -rf /tmp/ndk-tmp

    # 下载后重新应用补丁
    apply_ndk_patches
  fi
}

# 提取独立工具链
extract_standalone() {
  local _toolchainArch="$1"
  local _toolchainDir="${wsDir}/toolchain-${_toolchainArch}"
  if [[ -d "${_toolchainDir}/bin" ]]; then
    info "独立工具链已存在: ${_toolchainDir}"
    return
  fi
  info "提取独立工具链: ${_toolchainArch} ..."
  python3 "${ndkDir}/build/tools/make_standalone_toolchain.py" \
    --arch "${_toolchainArch}" \
    --api "${api}" \
    --install-dir "${_toolchainDir}" || { err "独立工具链提取失败! => $_toolchainArch" && exit 1; }
}

#for _arch in arm64 x86_64; do
#  extract_standalone "$_arch"
#done

load_env() {
  local _targetArch="${1:-aarch64}"
  # aarch64 -> arm64, x86_64 -> x86_64
  local _toolchainArch="${_targetArch}"
  [[ "$_targetArch" == "aarch64" ]] && _toolchainArch="arm64"
  local _toolchainDir="${wsDir}/ndk/toolchains/llvm/prebuilt/linux-x86_64"
  local _toolchainBin="${_toolchainDir}/bin"
  local _toolchainSysroot="${_toolchainDir}/sysroot"

  # 根据目标架构设置 -march
  local _march=""
  [[ "$_targetArch" == "aarch64" ]] && _march="-march=armv8-a"
  [[ "$_targetArch" == "x86_64" ]] && _march="-march=x86-64"

  export CC="ccache ${_toolchainBin}/${_targetArch}-linux-android${api}-clang"
  export CXX="ccache ${_toolchainBin}/${_targetArch}-linux-android${api}-clang++"
  export AR="${_toolchainBin}/llvm-ar"
  export READELF="${_toolchainBin}/llvm-readelf"
  export STRIP="${_toolchainBin}/llvm-strip"
  export RANLIB="${_toolchainBin}/llvm-ranlib"
  export LD="${_toolchainBin}/ld.lld"
  export ASM="${_toolchainBin}/yasm"

  # --sysroot=${_toolchainSysroot}
  export CPPFLAGS=""
  export CFLAGS="--sysroot=${_toolchainSysroot} -I${prefix}/include ${_march} ${cflags}"
  export CXXFLAGS="--sysroot=${_toolchainSysroot} -I${prefix}/include ${_march} ${cxxflags}"
  export LDFLAGS="--sysroot=${_toolchainSysroot} -L${prefix}/lib ${ldflags}"

  export LIBS=""

  export PATH="${_toolchainBin}:$PATH"

  export PKG_CONFIG="pkg-config"
  export PKG_CONFIG_LIBDIR="${prefix}/lib/pkgconfig:${prefix}/share/pkgconfig"
  unset PKG_CONFIG_SYSROOT_DIR

  export USE_CCACHE=1
  export CCACHE_EXEC="/usr/bin/ccache"
  export CCACHE_DIR="${wsDir}/bionic_ccache/${targetArch}"
  mkdir -p "$CCACHE_DIR"
  # v3.2.9：ccache 限容（默认 5G/无限因平台而异）——wine arm64ec 三目标 PE
  # + 全依赖闭包的缓存对象在多次 CI 迭代后持续膨胀，叠加 wine 源码树与
  # 编译产物把 runner 磁盘顶爆（runner 失联无日志的头部嫌凶，见
  # disk_watermark_guard 注释）。8G 上限兼顾命中率与磁盘安全；超出部分
  # 自动 LRU 淘汰。
  CCACHE_DIR="$CCACHE_DIR" ccache -M 8G >/dev/null 2>&1 || true
}

load_env_host() {
  # host glibc env
  local _hostArch
  _hostArch=$(uname -m)

  export CC="ccache gcc"
  export CXX="ccache g++"
  export AR="gcc-ar"
  export READELF="readelf"
  export STRIP="strip"
  export RANLIB="gcc-ranlib"
  export LD="ld"

  export CPPFLAGS=""

  export ASM=""
  export LIBS=""

  export CFLAGS="-O2 -pipe"
  export CXXFLAGS="-O2 -pipe"
  export LDFLAGS="-s"

  export PKG_CONFIG="pkg-config"
  #export PKG_CONFIG_LIBDIR="${prefix}/lib/pkgconfig:${prefix}/share/pkgconfig"
  #export PKG_CONFIG_SYSROOT_DIR="${prefix}"
  unset PKG_CONFIG_LIBDIR=""
  unset PKG_CONFIG_SYSROOT_DIR=""

  export CCACHE_DIR="${wsDir}/host_ccache/${_hostArch}"

  mkdir -p "$CCACHE_DIR"
}

# @src

dl_src() {
  # 支持备用下载源：项目可在 build.sh 中定义 backupUrls=(...) 数组，主源失败时按序回退
  # （LinBox：bzip2/gmp/gnutls/libiconv 等包依赖此机制绕开上游对 CI 出口 IP 的限流）
  #
  # v3.1.7 下载健壮性（rootfs CI 实证：libXdmcp-1.1.5 从 xorg.freedesktop.org
  # 下载遇 "OpenSSL: error:0A00026: unexpected eof while reading" —— 上游
  # 偶发断流/SSL 中断，整条 rootfs 构建直接折在这一步）：
  #   1. xorg.freedesktop.org 的 URL 自动补两个镜像（www.x.org/releases 与
  #      ftp.x.org/pub，同路径结构，实测可用）—— 19 个 X 库配方无需逐一加
  #      backupUrls；
  #   2. 整张 URL 列表重试一轮（wget 自带 --tries=2 只覆盖单 URL 的连接
  #      级重试，覆盖不了"换源也不行、稍等再试就好"的瞬时故障）。
  local _urls=("$url")
  local _u
  if [[ -n "${backupUrls[*]:-}" ]]; then
    _urls+=("${backupUrls[@]}")
  fi
  # xorg.freedesktop.org → 追加 x.org 镜像（archive/individual → releases|pub/individual 同构）
  local _expanded=()
  for _u in "${_urls[@]}"; do
    _expanded+=("$_u")
    case "$_u" in
      https://xorg.freedesktop.org/archive/*)
        _expanded+=("${_u/#https:\/\/xorg.freedesktop.org\/archive/https://www.x.org/releases}")
        _expanded+=("${_u/#https:\/\/xorg.freedesktop.org\/archive/https://ftp.x.org/pub}")
        ;;
    esac
  done
  _urls=("${_expanded[@]}")

  local _pass 
  for _pass in 1 2; do
    rm -rf /tmp/download-src
    mkdir -p /tmp/download-src
    for _u in "${_urls[@]}"; do
      info "下载 => $_u"
      # 快速失败：--timeout 限制单次连接/读取最长 60s，避免对被墙/屏蔽的源傻等半小时
      # -nv：单行进度输出（完整进度条逐 KB 刷屏对排查无益，纯日志膨胀）
      if wget -nv --timeout=60 --tries=2 --waitretry=3 -P /tmp/download-src/ "$_u"; then
        return 0
      fi
      warn "下载 $_u 失败，切换下一个源..."
      rm -rf /tmp/download-src
      mkdir -p /tmp/download-src
    done
    if [[ "$_pass" == "1" ]]; then
      warn "第一轮全部下载源失败，10 秒后整表重试一次（瞬时 SSL/断流恢复）..."
      sleep 10
    fi
  done
  err "全部下载源均失败 => ${_urls[*]}"
  return 1
}

get_src() {
  cd "${srcDir}"
  rm -rf $pjName
  mkdir $pjName
  guard_dev_null
  case $urlType in
  git)
    local _gitRetries=5
    local _gitTry
    # v3.3.16：分支名别名回退 —— 不少上游把默认分支从 master 改名 main
    #（mesa 26.x 起 gitlab 上已无 master，CI 实测 "fatal: Remote branch
    # master not found in upstream origin" 重试 5 次全灭）。首次失败后对
    # master↔main 做一次别名探测（git ls-remote --symref HEAD 读远端
    # 默认分支），命中则改用远端默认分支继续，其余失败照常走重试。
    local _rev="${revision}"
    local _aliasProbed=0
    for ((_gitTry = 1; _gitTry <= _gitRetries; _gitTry++)); do
      info "克隆 ${pjName}@${_rev} (第 ${_gitTry}/${_gitRetries} 次尝试)"
      # -c advice.detachedHead=false: 约12行 detached HEAD 提示对每个 git 包都会刷一遍
      if git -c advice.detachedHead=false clone --recursive --depth=1 -b "${_rev}" "${url}" $pjName; then
        _gitTry=0
        break
      fi
      if [[ "${_aliasProbed}" == "0" && "${_rev}" == "master" ]]; then
        local _def
        _def="$(git ls-remote --symref "${url}" HEAD 2>/dev/null | sed -n 's|^ref: *refs/heads/\([^[:space:]]*\).*|\1|p' | head -n 1)"
        if [[ -n "${_def}" && "${_def}" != "master" ]]; then
          warn "远端无 ${_rev} 分支，检测到默认分支为 ${_def}（master→main 改名类上游），切换重试"
          _rev="${_def}"
          _aliasProbed=1
          rm -rf $pjName
          continue
        fi
      fi
      if [[ $_gitTry -lt $_gitRetries ]]; then
        warn "源码克隆失败,5秒后重试..."
        rm -rf $pjName
        sleep 5
      fi
    done
    if [[ $_gitTry -ne 0 ]]; then
      err "源码克隆失败!" && exit 1
    fi
    # 分支名被别名回退改写时，同步 revision 变量（打包名/状态指纹/依赖
    # 报告均引用 revision，保持一致，避免 "mesa-master" 包名长期固化）
    if [[ "${_rev}" != "${revision}" ]]; then
      warn "revision 由 ${revision} 修正为 ${_rev}（远端默认分支）"
      revision="${_rev}"
    fi
    ;;
  tar)
    if dl_src; then
      cd "${srcDir}"
      tar --strip-components=1 -xf /tmp/download-src/$(ls /tmp/download-src/) -C $pjName/ || { err "文件解压失败！" && exit 1; }
    else
      err "源码处理失败!" && exit 1
    fi
    ;;
  7z)
    if dl_src; then
      cd "${srcDir}"
      rm -rf /tmp/7z-tmp
      mkdir /tmp/7z-tmp
      unzip /tmp/downlaod-src/$(ls /tmp/downlaod-src) -d /tmp/7z-tmp/ || { err "文件解压失败！" && exit 1; }
      mv /tmp/7z-tmp/*/* $pjName/
    else
      err "源码下载失败!" && exit 1
    fi
    ;;
  wget)
    if dl_src; then
      info "源码下载完成"
    fi
    ;;
  local)
    info "此项目源码类型为本地"
    return 0
    ;;
  others)
    if ! declare -F custom_url >/dev/null; then
      err "如果urlType为others,请定义custom_url函数!"
      exit 1
    else
      info "存在自定义URL方法,开始执行"
      custom_url || { err "源码下载失败!" && exit 1; }
    fi
    ;;
  esac
}

# 源码准备状态指纹：revision、补丁内容、补丁规则与占位符变量任一变化都必须重新获取源码
src_state_fingerprint() {
  local _patches=()
  _patches=($(ls "${wsDir}"/projects/"${pjName}"/*.patch 2>/dev/null | LC_ALL=C sort || true))
  [[ ! -f "${_patches[0]:-}" ]] && _patches=()

  local _manifest
  _manifest="$(
    printf 'v1\nrevision=%s\ndoNotApplyPatch=%s\nprefix=%s\nrootfsBase=%s\nrootfsHome=%s\n' \
      "${revision}" "${doNotApplyPatch:-0}" "${prefix}" "${rootfsBase}" "${rootfsHome}"
    if declare -F custom_patch >/dev/null; then
      sha256sum "${wsDir}/projects/${pjName}/build.sh" || true
    else
      [[ ${#_patches[@]} -gt 0 ]] && sha256sum "${_patches[@]}" || true
      [[ -f "${wsDir}/patch-rules.conf" ]] && sha256sum "${wsDir}/patch-rules.conf" || true
    fi
  )"
  printf '%s' "${_manifest}" | sha256sum | cut -d' ' -f1
}

# 可复用源码的构建系统：它们各自会清理上一次的构建产物（cmake/meson 删 build，
# autotools/make 走 make distclean），跨架构复用不会混入上一架构的目标文件
src_reuse_supported() {
  case "${buildSys}" in
  cmake | meson | autotools | make) return 0 ;;
  *) return 1 ;;
  esac
}

# 状态文件集中放在 ${srcDir}/.state 下，避免往源码目录（含 projects/ 下的本地源码）写隐藏文件
src_state_file() {
  printf '%s\n' "${srcDir}/.state/${pjName}"
}

can_reuse_src() {
  local _srcDir="$1"
  [[ -d "${_srcDir}" ]] || return 1
  src_reuse_supported || return 1
  local _stateFile
  _stateFile="$(src_state_file)"
  [[ -f "${_stateFile}" ]] || return 1
  [[ "$(cat "${_stateFile}")" == "$(src_state_fingerprint)" ]]
}

# 获取并准备源码：状态未变时复用，否则重新获取并重新打补丁
prepare_src() {
  local _srcDir="${pkgSrcDir:-${srcDir}/${pjName}}"
  if can_reuse_src "${_srcDir}"; then
    info "复用源码: ${_srcDir} (revision 与补丁均未变化，跳过获取与打补丁)"
    return 0
  fi
  get_src
  if [[ "${doNotApplyPatch:-0}" != 1 ]]; then
    apply_patches
  fi
  # 打完补丁再记录状态，避免补丁失败时留下"已准备好"的假状态
  if src_reuse_supported; then
    local _stateFile
    _stateFile="$(src_state_file)"
    mkdir -p "${srcDir}/.state"
    src_state_fingerprint > "${_stateFile}"
  fi
}

# @patch
#
# 项目级可选标志（在项目 build.sh 顶层定义，apply_patches 读取）：
#   patchOptional=1   补丁 dry-run 失败时【警告跳过】而非中止构建。
#                     供"补丁有版本基线、按选定 revision 条件生效"的项目
#                     使用（如 box64 0001 补丁基线 v0.4.4：选 <0.4.4 的旧版
#                     或 master 漂移时跳过补丁继续构建，不让 CI 直接阵亡）。
#                     跳过记录在全局变量 patchesSkipped（项目 install() 可
#                     据此条件化与补丁强相关的冒烟断言）。
#   patchesApplied    全局变量：本次实际应用成功的补丁文件名列表
#   patchesSkipped    全局变量：patchOptional 生效后被跳过的补丁文件名列表
apply_patches() {
  local _patchFiles
  local _patch

  if declare -F custom_patch >/dev/null; then
    info "使用自定义补丁"
    custom_patch || { err "自定义补丁应用失败!" && exit 1; }
    return 0
  fi

  [[ -z $pjName ]] && { err "pjName 未定义！" && exit 1; }
  # 扫描项目补丁目录并按文件名字典序排序（LC_ALL=C，不受 locale 影响）
  _patchFiles=($(ls "${wsDir}"/projects/"${pjName}"/*.patch 2>/dev/null | LC_ALL=C sort))
  [[ ! -f "${_patchFiles[0]}" ]] && _patchFiles=()
  [[ ${#_patchFiles[@]} -eq 0 ]] && { info "$pjName 无需应用任何补丁" && return 0; }

  # 占位符映射统一在 patch-rules.conf 中维护（expand_patch_placeholders 运行时替换）
  [[ -f "${wsDir}/patch-rules.conf" ]] || { err "缺少补丁替换规则文件 => ${wsDir}/patch-rules.conf" && exit 1; }

  local _srcDir
  if [[ -n "${pkgSrcDir:-}" ]]; then
    _srcDir="${pkgSrcDir}"
  else
    _srcDir="${srcDir}/${pjName}"
  fi
  cd "$_srcDir"

  # v3.3.10：补丁应用/跳过状态记录（项目 install() 条件化冒烟用）
  patchesApplied="${patchesApplied:-}"
  patchesSkipped="${patchesSkipped:-}"

  local _patch _expanded
  for _patch in "${_patchFiles[@]}"; do
    if ! _expanded="$(expand_patch_placeholders < "$_patch")"; then
      err "补丁占位符展开失败=> $_patch" && exit 1
    fi
    if printf '%s\n' "$_expanded" | patch -p1 --dry-run; then
      info "测试通过=> $_patch"
      info "应用补丁=> $_patch"
      printf '%s\n' "$_expanded" | patch --no-backup-if-mismatch -N -p1 || { err "补丁应用失败=> $_patch" && exit 1; }
      patchesApplied+=" $(basename "${_patch}")"
    elif [[ "${patchOptional:-0}" == "1" ]]; then
      # v3.3.10（box64 构建修复）：补丁基线与选定 revision 不匹配时跳过
      # 而非中止 —— 旧版本（<基线）选型 / master 漂移均不再让构建阵亡
      warn "补丁不适用于当前源码（dry-run 失败，patchOptional=1），跳过=> $_patch"
      warn "  （该补丁有版本基线；选旧版/漂移版本时按选择跳过，相关特性随之缺失）"
      patchesSkipped+=" $(basename "${_patch}")"
    else
      err "补丁应用失败=> $_patch" && exit 1
    fi
  done

  cd -
}

# @license

install_license() {
  local _srcDir="${pkgSrcDir:-${srcDir}/${pjName}}"
  [[ ! -d "${_srcDir}" ]] && { warn "源码目录不存在，跳过协议安装 => ${_srcDir}" && return 0; }

  # 与 gen_notices 的 ${prefix}/share/licenses 保持同一目录层级（此前这里写成
  # share/_licenses，结果 rootfs 里同时存在 licenses/ 与 _licenses/ 两套目录）
  local _licDir="${destDir}${prefix}/share/licenses/${pjName}"
  mkdir -p "${_licDir}"

  # 收集协议文件：优先源码目录，否则回退父目录（如 icu pkgSrcDir=src/icu/source）
  local _licSrcDir=""
  local _f
  for _f in $(ls -A "$_srcDir" 2>/dev/null); do
    if [[ -f "${_srcDir}/${_f}" ]] && [[ "${_f^^}" =~ ^(LICENSE|LICENCE|COPYING|COPYRIGHT|NOTICE) ]]; then
      _licSrcDir="$_srcDir"
      break
    fi
  done
  if [[ -z "$_licSrcDir" && -n "${pkgSrcDir:-}" ]]; then
    local _parentDir="$(dirname "${pkgSrcDir}")"
    [[ "$_parentDir" != "$_srcDir" ]] && for _f in $(ls -A "$_parentDir" 2>/dev/null); do
      if [[ -f "${_parentDir}/${_f}" ]] && [[ "${_f^^}" =~ ^(LICENSE|LICENCE|COPYING|COPYRIGHT|NOTICE) ]]; then
        _licSrcDir="$_parentDir"
        break
      fi
    done
  fi

  local _found=0
  if [[ -n "$_licSrcDir" ]]; then
    for _f in $(ls -A "$_licSrcDir" 2>/dev/null); do
      if [[ -f "${_licSrcDir}/${_f}" ]] && [[ "${_f^^}" =~ ^(LICENSE|LICENCE|COPYING|COPYRIGHT|NOTICE) ]]; then
        cp -a "${_licSrcDir}/${_f}" "${_licDir}/"
        info "安装协议文件 => ${pjName}/${_f}"
        _found=1
      fi
    done
  fi

  # 自定义协议文件: agreementTargetFile 指向的文件一并复制到协议目录
  # 支持多个文件/通配符（空格分隔），支持绝对路径或相对工作目录的路径
  if [[ -n "${agreementTargetFile:-}" ]]; then
    local _agreeSrc
    local _agree
    for _agreeSrc in ${agreementTargetFile}; do
      [[ "$_agreeSrc" != /* ]] && _agreeSrc="${wsDir}/${_agreeSrc}"
      local _agreeMatched=0
      for _agree in ${_agreeSrc}; do
        if [[ -f "$_agree" ]]; then
          cp -a "$_agree" "${_licDir}/"
          info "安装自定义协议文件 => ${pjName}/$(basename "$_agree")"
          _found=1
          _agreeMatched=1
        fi
      done
      [[ $_agreeMatched -eq 0 ]] && warn "自定义协议文件不存在 => ${_agreeSrc}"
    done
  fi

  if [[ $_found -eq 0 ]]; then
    # 源码中无协议文件时，写入最小协议信息（含 SPDX 标识与上游地址）
    warn "未找到 ${pjName} 的协议文件，写入最小协议信息"
    {
      echo "Package: ${pjName}"
      echo "Version: ${revision}"
      echo "License: ${license:-unknown}"
      echo "URL: ${url}"
    } >"${_licDir}/LICENSE"
  fi
}

# @package

package() {
  if [[ "${doNotMakePackage:-0}" == 1 ]]; then
    info "${pjName}: doNotMakePackage=1, 跳过打包"
    return 0
  fi
  [[ ! -d "${destDir}" ]] && { err "destDir 不存在 => ${destDir}" && exit 1; }
  install_license
  cd "${destDir}"
  if [[ -e dev/null ]]; then
    warn "${pjName} 的 make install 在 DESTDIR 下创建了 dev/null，正在清理..."
    rm -rf dev/null
  fi
  # v3.3.0：GNU tar 打包 "." 收尾会尝试恢复目录时间戳，在无主目录上
  # 报 "tar: .: Cannot utime: Operation not permitted"（CI 实测每个包
  # 都报、exit 1）—— 此时 archive 数据已完整写出。改为：tar 失败后校验
  # 归档可完整列出即放行，其余失败照常中止（不再让 set -e 吞掉真错误）。
  tar -cf "${wsDir}/pkgs/${pjName}-${revision}-${targetArch}.tar" . || {
    if tar -tf "${wsDir}/pkgs/${pjName}-${revision}-${targetArch}.tar" >/dev/null 2>&1; then
      warn "${pjName}: tar 收尾 utime 失败（归档校验通过，忽略）"
    else
      err "${pjName}: 打包失败（归档不完整）" && exit 1
    fi
  }
  rm -rf "${destDir}"
  cd "${wsDir}"
}

# @dep

get_dep() {
  local _buildFile="${wsDir}/projects/$1/build.sh"
  if [[ ! -f "$_buildFile" ]]; then
    err "项目不存在 => $1" >&2
    return 1
  fi
  # v3.3.11（CI 修复）：本函数的 stdout 会被 $(...) 捕获为依赖列表，
  # 因此 source 项目 build.sh 期间【任何顶层 stdout 输出都会混进 deps】
  # —— box64 v3.3.10 顶层裸 echo "[INFO] ..." 曾使 collect_dep 把
  # "[INFO]" 当作包名，报 "项目不存在 => [INFO]"，box64/bionic-rootfs
  # 相关构建全部阵亡。防御：source 全程把 stdout 改道 stderr（诊断
  # 信息照常进日志），组合命令结束后 stdout 自动恢复，deps 在组外
  # 干净输出。build.sh 顶层的 return/exit 语义不受重定向影响。
  {
    . "${wsDir}/empty.sh"
    . "$_buildFile"
  } >&2
  echo "${deps[@]}"
}

collect_dep() {
  local _project="$1"
  [[ -n "${_visited_[$_project]:-}" ]] && {
    return
  }
  _visited_[$_project]=1
  _allPkgs_+=("$_project")

  local _deps
  _deps=$(get_dep "$_project") || { err "无法获取 $_project 的依赖" && exit 1; }
  local _dep
  for _dep in $_deps; do
    collect_dep "$_dep"
  done
}

# 拓补排序

toposort() {
  # 构建反向图: _rgraph_[dep] = 需要 dep 的项目列表
  # _indeg_[project] = project 依赖的包数量
  local _project
  for _project in "${_allPkgs_[@]}"; do
    local _deps
    _deps=$(get_dep "$_project") || { err "无法获取 $_project 的依赖" && exit 1; }
    _indeg_["$_project"]=0
    local _dep
    for _dep in $_deps; do
      _indeg_["$_project"]=$((${_indeg_["$_project"]} + 1))
      _rgraph_["$_dep"]+="${_project} "
    done
  done

  # 初始化队列，入度为0的项目（无依赖）
  local _queue=()
  for _project in "${_allPkgs_[@]}"; do
    [[ ${_indeg_["$_project"]} -eq 0 ]] && {
      _queue+=("$_project")
    }
  done

  # Kahn 算法
  while [[ ${#_queue[@]} -gt 0 ]]; do
    local _project="${_queue[0]}"
    _queue=("${_queue[@]:1}") # 出队
    _buildOrder_+=("$_project")

    # 遍历所有依赖 _project 的包，减少其入度
    local _dependents="${_rgraph_["$_project"]:-}"
    local _dep
    for _dep in $_dependents; do
      _indeg_["$_dep"]=$((${_indeg_["$_dep"]} - 1))
      [[ ${_indeg_["$_dep"]} -eq 0 ]] && {
        _queue+=("$_dep")
      }
    done
  done

  # 检测循环依赖
  if [[ ${#_buildOrder_[@]} -ne ${#_allPkgs_[@]} ]]; then
    err "存在循环依赖!"
    err "未排序的包:"
    for _project in "${_allPkgs_[@]}"; do
      [[ ! " ${_buildOrder_[@]} " =~ " $_project " ]] && {
        err "$_project"
      }
    done
    exit 1
  fi
}

# @build

configure_err() {
  err "构建失败"
  exit 1
}

compile_err() {
  err "编译失败"
  exit 1
}

build_system() {
  local _buildArchs
  _buildArchs=$(filter_archs "${arch[@]}")
  [[ -z "$_buildArchs" ]] && {
    info "跳过: 目标架构不在包的 arch 列表中"
    return 0
  }
  # 加载基础配置参数
  . "${wsDir}/base-configure-args.conf"
  #cat >> "${wsDir}/build_temp.sh" << "EOF"
  if [[ -n "${pkgSrcDir:-}" ]]; then
    info "存在定义的pkgSrcDir,进入"
    local _srcDir="${pkgSrcDir}"
  else
    local _srcDir="${srcDir}/${pjName}"
  fi
  case $buildSys in
  cmake)
    for targetArch in $_buildArchs; do
      destDir="/tmp/build-${targetArch}"
      export CCACHE_DIR="${wsDir}/bionic_ccache/${targetArch}"
      mkdir -p "$CCACHE_DIR"
      info "target arch=> $targetArch"
      info "Use cmake..."
      cd "${_srcDir}"
      #load_env "$targetArch"
      rm -rf build
      mkdir build
      [[ ! $targetArch == $(uname -m) ]] && {
        cmakeBaseArgs+=" -DCMAKE_SYSTEM_PROCESSOR=$targetArch"
      }
      if [[ $targetArch == aarch64 ]]; then
        cmakeBaseArgs+=" -DANDROID_ABI=arm64-v8a"
      else
        cmakeBaseArgs+=" -DANDROID_ABI=x86_64"
      fi
      if declare -F pre_setup >/dev/null; then
        info "存在前置操作,开始执行"
        pre_setup || { err "${pjName}: pre_setup 失败"; return 1; }
      fi
      if [[ $doNotUseNinja == 1 ]]; then
        cd build
        if [[ -n "${fullCustomArgs:-}" ]]; then
          cmake .. ${fullCustomArgs} || configure_err
        else
          cmake .. ${cmakeBaseArgs[@]} ${args[@]} || configure_err
        fi
        make -j$(nproc) || compile_err
      else
        if [[ -n "${fullCustomArgs:-}" ]]; then
          cmake -G Ninja -S . -B build ${fullCustomArgs} || configure_err
        else
          cmake -G Ninja -S . -B build ${cmakeBaseArgs[@]} ${args[@]} || configure_err
        fi
        cmake --build build || compile_err
      fi

      if declare -F install >/dev/null; then
        # v3.2.14：install() 失败必须中止 —— 此前返回值被忽略（box64
        # v3.2.13 实证：冒烟校验 return 1 后框架照常"构建完成"，
        # CI 绿灯带错误字样发布，残废包难定位）。
        DESTDIR="${destDir}" PREFIX="${prefix}" install \
          || { err "${pjName}: install() 返回失败，中止构建"; exit 1; }
      else
        if [[ $doNotRunCMakeInstallOnCmake == 1 ]]; then
          DESTDIR="${destDir}" PREFIX="${prefix}" make install
        else
          DESTDIR="${destDir}" cmake --install build --prefix "${prefix}"
        fi
      fi
      if declare -F pre_package >/dev/null; then
        info "存在前置打包操作,开始执行"
        # v3.1.5：pre_package 失败必须中止 —— 此前返回值被忽略，DLL 注入
        # 下载失败照样 package() 出残废包，rootfs/发行包校验才炸（难排查）。
        pre_package || { err "${pjName}: 前置打包操作失败，中止构建"; exit 1; }
      fi
      package
    done
    ;;
  meson)
    for targetArch in $_buildArchs; do
      destDir="/tmp/build-${targetArch}"
      info "target arch=> $targetArch"
      info "Use meson..."
      cd "${_srcDir}"
      load_env "$targetArch"
      rm -rf build
      mkdir build

      # 1) pre_setup 始终先于 cross file 生成（可修改 LDFLAGS/CFLAGS/extraMesonCrossProps 等）
      if declare -F pre_setup >/dev/null; then
        info "存在前置操作，开始执行"
        pre_setup || { err "${pjName}: pre_setup 失败"; return 1; }
      fi

      # 2) 生成 cross file
      if [[ -n "${customCrossFile:-}" ]]; then
        # 自定义 cross file：展开路径中的变量（如 ${targetArch}），复制到标准位置
        local _crossSrc="${customCrossFile//\{targetArch\}/${targetArch}}"
        _crossSrc="${_crossSrc//\$\{targetArch\}/${targetArch}}"
        cp "${_crossSrc}" /tmp/cross-${targetArch}-meson.txt
      else
        . "${wsDir}/cross_file/cross-${targetArch}-meson.sh"
      fi
      mesonBaseArgs+=(--cross-file=/tmp/cross-${targetArch}-meson.txt)

      # 3) fullCustomArgs 完全接管整个构建参数（包括 base），仅保留 cross file
      if [[ -n "${fullCustomArgs:-}" ]]; then
        meson setup build ${mesonBaseArgs[@]} ${fullCustomArgs} || configure_err
      else
        meson setup build ${mesonBaseArgs[@]} ${args[@]} || configure_err
      fi
      meson compile -C build || compile_err
      if declare -F install >/dev/null; then
        # v3.2.14：install() 失败必须中止（cmake 段同款修复）
        DESTDIR="${destDir}" PREFIX="${prefix}" install \
          || { err "${pjName}: install() 返回失败，中止构建"; exit 1; }
      else
        DESTDIR="${destDir}" PREFIX="${prefix}" meson install -C build
      fi
      if declare -F pre_package >/dev/null; then
        info "存在前置打包操作,开始执行"
        # v3.1.5：pre_package 失败必须中止 —— 此前返回值被忽略，DLL 注入
        # 下载失败照样 package() 出残废包，rootfs/发行包校验才炸（难排查）。
        pre_package || { err "${pjName}: 前置打包操作失败，中止构建"; exit 1; }
      fi
      package
    done
    ;;
  autotools | make)
    for targetArch in $_buildArchs; do
      destDir="/tmp/build-${targetArch}"
      info "target arch=> $targetArch"
      info "Use autotools..."
      cd "${_srcDir}"
      # Clean previous build artifacts to prevent arch contamination
      make distclean >/dev/null || true
      # Reload base args to prevent accumulation across iterations
      . "${wsDir}/base-configure-args.conf"
      load_env "$targetArch"
      configureBaseArgs+=(--host=${targetArch}-linux-android --build=$(uname -m)-linux-gnu)

      if declare -F pre_setup >/dev/null; then
        info "存在前置操作,开始执行"
        pre_setup || { err "${pjName}: pre_setup 失败"; return 1; }
      fi

      if [[ -f autogen.sh ]] && [[ ! $doNotUseAutogenSh == 1 ]] && [[ ! -f configure ]]; then
        if [[ ! $argsToAutogenSh == 1 ]]; then
          ./autogen.sh || { err "autogen.sh: err" && exit 1; }
        else
          info "构建参数传入autogen.sh"
          if [[ -n "${fullCustomArgs:-}" ]]; then
            ./autogen.sh ${fullCustomArgs} || configure_err
          else
            ./autogen.sh ${configureBaseArgs[@]} ${args[@]} || configure_err
          fi
        fi
      fi

      if declare -F custom_configure >/dev/null; then
        info "存在自定义构建,开始执行"
        custom_configure
      else
        if [[ ! $argsToAutogenSh == 1 ]] && [[ ! $forceAutoreconf == 1 ]]; then
          if [[ -f configure ]]; then
            if [[ -n "${fullCustomArgs:-}" ]]; then
              ./configure ${fullCustomArgs} || configure_err
            else
              ./configure ${configureBaseArgs[@]} ${args[@]} || configure_err
            fi
          else
            info "未能找到configure,将通过autoreconf -fi自动生成"
            autoreconf -fi || { err "autoreconf 失败!" && exit 1; }
            if [[ -n "${fullCustomArgs:-}" ]]; then
              ./configure ${fullCustomArgs} || configure_err
            else
              ./configure ${configureBaseArgs[@]} ${args[@]} || configure_err
            fi
          fi
        else
          info "configure将通过autoreconf -fi自动生成"
          autoreconf -fi || { err "autoreconf 失败!" && exit 1; }
          if [[ -n "${fullCustomArgs:-}" ]]; then
            ./configure ${fullCustomArgs} || configure_err
          else
            ./configure ${configureBaseArgs[@]} ${args[@]} || configure_err
          fi
        fi
      fi
      make ${makeSilent:+-s} -j$(nproc) || compile_err
      if declare -F install >/dev/null; then
        # v3.2.14：install() 失败必须中止（cmake 段同款修复）
        DESTDIR="${destDir}" PREFIX="${prefix}" install \
          || { err "${pjName}: install() 返回失败，中止构建"; exit 1; }
      else
        # v3.2.24：make install 失败必须中止 —— wine x86_64 CI 实证：
        # make（编译）成功，make install 阶段还要编译 unix 侧 implib
        # 对象（dlls/dinput/data_formats.o 等），报错退出（-mabi=ms，
        # clang 不支持）后此路径无错误检查 → 上层照打 [OK] 构建完成、
        # package() 出残缺包（d 之后字母序的 dll 全缺）。与 install()
        # 路径同款硬失败。
        PREFIX="${prefix}" make install DESTDIR="${destDir}" \
          || { err "${pjName}: make install 失败，中止构建"; exit 1; }
      fi
      if declare -F pre_package >/dev/null; then
        info "存在前置打包操作,开始执行"
        # v3.1.5：pre_package 失败必须中止 —— 此前返回值被忽略，DLL 注入
        # 下载失败照样 package() 出残废包，rootfs/发行包校验才炸（难排查）。
        pre_package || { err "${pjName}: 前置打包操作失败，中止构建"; exit 1; }
      fi
      package
    done
    ;;
  debug)
    info "加载调试环境"
    for targetArch in $_buildArchs; do
      info "target arch=> $targetArch"
      load_env "$targetArch"
      bash
    done
    ;;
  others) if ! declare -F extra_fuction >/dev/null; then
    err "如果buildSys 为others,那么你需要定义extra_fuction 函数!"
    exit 1
  else
    for targetArch in $_buildArchs; do
      destDir="/tmp/build-${targetArch}"
      mkdir -p "${destDir}/${prefix}"
      cd "${_srcDir}"
      load_env "$targetArch"
      extra_fuction || { err "失败!" && exit 1; }
      package
    done
  fi ;;
  esac
  #EOF
}

make_pkg() {
  local _needBuild=false
  local _targetArch
  local _pkgArchs
  _pkgArchs=$(filter_archs "${arch[@]}")
  [[ -z "$_pkgArchs" ]] && {
    info "跳过 $pjName: 目标架构不在包的 arch 列表中"
    return 0
  }

  # doNotMakePackage=1: 无包项目，不生成/解压 tar，每次都进入构建流程，
  # 由项目脚本内部判断是否真正重建（如 wine-tools 的宿主工具版本比对）
  if [[ "${doNotMakePackage:-0}" == 1 ]]; then
    local _srcDir="${pkgSrcDir:-${srcDir}/${pjName}}"
    # --has-src: 使用现有源码，不重新获取，也不重新打补丁（视为已打好）
    if [[ "$hasSrc" == true ]]; then
      if [[ ! -d "$_srcDir" ]]; then
        err "--has-src 指定但源码目录不存在 => $_srcDir"
        exit 1
      fi
    else
      prepare_src
    fi
    build_system || return 1
    return 0
  fi

  # --rebuild pkg: 只删除指定包的 tar
  if [[ -n "${rebuildTarget:-}" && "$pjName" == "$rebuildTarget" ]]; then
    for _targetArch in $_pkgArchs; do
      local _pkgFile="${pkgDir}/${pjName}-${revision}-${_targetArch}.tar"
      if [[ -f "$_pkgFile" ]]; then
        info "重建模式：删除已存在的包 => $_pkgFile"
        rm -f "$_pkgFile"
      fi
    done
  # --rebuild all / --rebuild-deps all: 全量删除重建
  elif [[ "$rebuildMode" == true ]]; then
    for _targetArch in $_pkgArchs; do
      local _pkgFile="${pkgDir}/${pjName}-${revision}-${_targetArch}.tar"
      if [[ -f "$_pkgFile" ]]; then
        info "重建模式：删除已存在的包 => $_pkgFile"
        rm -f "$_pkgFile"
      fi
    done
  fi

  # 检查是否有任一架构的包缺失
  for _targetArch in $_pkgArchs; do
    local _pkgFile="${pkgDir}/${pjName}-${revision}-${_targetArch}.tar"
    if [[ ! -f "$_pkgFile" ]]; then
      _needBuild=true
      break
    fi
  done

  # 需要编译
  if $_needBuild; then
    if [[ "$hasSrc" == true ]]; then
      local _hasSrcDir="${pkgSrcDir:-${srcDir}/${pjName}}"
      if [[ -d "$_hasSrcDir" ]]; then
        info "跳过源码获取: $_hasSrcDir (--has-src 模式，现有源码视为已打好补丁，跳过 apply_patches)"
      else
        err "--has-src 指定但源码目录不存在 => $_hasSrcDir"
        exit 1
      fi
    else
      prepare_src
    fi
    build_system || return 1
    # 构建完成后清理源码目录（local 源码由用户手动放置，不删除）
    if [[ "$cleanSrcDir" == "1" && "$urlType" != "local" ]]; then
      local _cleanTarget="${pkgSrcDir:-${srcDir}/${pjName}}"
      if [[ -d "$_cleanTarget" ]]; then
        info "清理源码目录: $_cleanTarget"
        rm -rf "$_cleanTarget"
      fi
    fi
  fi

  # 清理同项目旧 revision 的包 tar（cleanOldPackage=1 时启用）
  if [[ "${cleanOldPackage:-0}" == 1 ]]; then
    local _oldPkg
    local _oldBase
    # 包名为 <pjName>-<revision>-<arch>.tar，pjName 与 revision 都可能含 '-'
    # （如 wayland 与 wayland-protocols、mesa-26.2.1），"${pjName}-*" 会顺带匹配到
    # 名字以 "${pjName}-" 开头的其它项目的包，故先用 projects/ 下已知项目名排除
    # （与 make-rootfs.sh 分组的思路一致）
    local _siblingPjs=()
    local _pjDirPath
    local _pjDirName
    for _pjDirPath in "${wsDir}"/projects/*/; do
      _pjDirName="${_pjDirPath%/}"
      _pjDirName="${_pjDirName##*/}"
      [[ "$_pjDirName" == "${pjName}-"* ]] && _siblingPjs+=("$_pjDirName")
    done

    for _targetArch in $_pkgArchs; do
      for _oldPkg in "${pkgDir}"/${pjName}-*-${_targetArch}.tar; do
        [[ -e "$_oldPkg" ]] || continue
        _oldBase="${_oldPkg##*/}"
        [[ "$_oldBase" == "${pjName}-${revision}-${_targetArch}.tar" ]] && continue
        local _sibling
        local _isSiblingPkg=0
        for _sibling in ${_siblingPjs[@]+"${_siblingPjs[@]}"}; do
          [[ "$_oldBase" == "${_sibling}-"* ]] && {
            _isSiblingPkg=1
            break
          }
        done
        [[ $_isSiblingPkg -eq 1 ]] && continue
        info "清理旧版本包 => $_oldBase"
        rm -f "$_oldPkg"
      done
    done
  fi

  # --has-prefix: 依赖已安装在 prefix 中，不再把包解压回去
  if [[ "$hasPrefix" == true ]]; then
    info "--has-prefix: 跳过 ${pjName} 的包解压"
    return 0
  fi

  # 只解压当前 targetArch 的包到 prefix
  for _targetArch in $_pkgArchs; do
    [[ "$_targetArch" == "$targetArch" ]] || continue
    local _pkgFile="${pkgDir}/${pjName}-${revision}-${_targetArch}.tar"
    if [[ -f "$_pkgFile" ]]; then
      # v3.3.1：解包容根噪声治理 —— package() 打包 "."（归档含根条目），
      # tar -xf -C / 收尾会在 "/"（root 属主）上恢复时间戳/权限，非 root
      # 必报 "tar: .: Cannot utime: Operation not permitted"（CI 日志每个
      # 包一条，与 v3.3.0 package() 侧修的是同一机制）。此时数据条目已
      # 全部落盘。改为 -m（--touch，不恢复归档 mtime）消除 utime 尝试；
      # 仍失败（如根 chmod）则校验归档可完整列出即放行，真错误照常中止。
      if ! tar -xmf "$_pkgFile" -C /; then
        if tar -tf "$_pkgFile" >/dev/null 2>&1; then
          warn "${pjName}: tar 解包容根时间戳/权限收尾失败（数据完整，忽略）"
        else
          err "包解压失败 => $_pkgFile"
          exit 1
        fi
      fi
      guard_dev_null
    else
      err "包不存在 => $_pkgFile"
      exit 1
    fi
  done
}

# 校验 projects/ 下确有可构建的项目
# projects/*/ 是 glob: 一个子目录都没有时 bash 默认保留字面量, 循环只会跑一轮并拿到
# pjName="*", build.sh 存在性判断不通过就被跳过, 最终还打印"全部完成"——构建全部
# 静默空跑, 没有任何报错。这里显式拦一道, 让残缺 checkout / 目录被误删立刻暴露。
check_projects_not_empty() {
  local _pjDir
  for _pjDir in "${wsDir}"/projects/*/; do
    [[ -f "${_pjDir}/build.sh" ]] && return 0
  done
  err "未在 ${wsDir}/projects/ 下找到任何带 build.sh 的项目, 请检查仓库是否完整"
  exit 1
}

build() {
  local _target="$1"

  # 保存原始 targetArchs，避免 --build all 时污染全局状态
  local _savedTargetArchs=("${targetArchs[@]}")

  # 确定要构建的架构列表
  local _archs
  if [[ ${#_savedTargetArchs[@]} -eq 0 ]]; then
    _archs="aarch64 x86_64"
  else
    _archs="${_savedTargetArchs[*]}"
  fi

  # 顶层按架构循环：每次只构建一个架构，确保 prefix 中只有当前架构的包
  local _arch
  for _arch in $_archs; do
    info "========== 构建架构: $_arch =========="
    # 每个架构 pass 开始前完整清理 prefix，避免上一个架构的库污染链接
    # （已有包 tar 在 pkgDir，make_pkg 会自动解压，依赖在本 pass 内按拓扑序重建）
    # --has-prefix 时 prefix 就是要复用的对象，不能清
    if [[ -d "${prefix}" ]]; then
      if [[ "$hasPrefix" == true ]]; then
        info "保留已有 prefix (--has-prefix): ${prefix}"
      else
        info "清理 prefix: ${prefix}"
        rm -rf "${prefix}"
      fi
    fi
    # 临时将 targetArchs 和 targetArch 设为当前架构
    targetArchs=("$_arch")
    targetArch="$_arch"

    # 重置依赖解析状态
    unset _visited_ _rgraph_ _indeg_ _allPkgs_ _buildOrder_
    declare -A _rgraph_=() _indeg_=() _visited_=()
    declare -a _allPkgs_=() _buildOrder_=()

    if [[ "$hasPrefix" == true ]]; then
      info "--has-prefix: 跳过依赖解析，仅构建 $_target"
      _buildOrder_=("$_target")
    else
      collect_dep "$_target"
      toposort
      info "构建顺序: ${_buildOrder_[*]}"
    fi
    local _project
    for _project in "${_buildOrder_[@]}"; do
      disk_watermark_guard
      info "构建=> $_project"
      . "${wsDir}/empty.sh"
      . "${wsDir}/projects/$_project/build.sh" || { err "配置获取失败" && exit 1; }
      pjName="$_project"
      make_pkg || { err "构建失败!" && exit 1; }
    done
  done

  # 恢复原始 targetArchs，确保 --build all 下一个包能正确判断架构
  targetArchs=("${_savedTargetArchs[@]}")
  ok "构建完成: $_target"
}

# 重建所有包的所有依赖（全局去重，一次 pass）
build_all_deps() {
  local _savedTargetArchs=("${targetArchs[@]}")

  local _archs
  if [[ ${#_savedTargetArchs[@]} -eq 0 ]]; then
    _archs="aarch64 x86_64"
  else
    _archs="${_savedTargetArchs[*]}"
  fi

  local _arch
  for _arch in $_archs; do
    info "========== 构建所有依赖: $_arch =========="
    check_projects_not_empty
    # 每个架构 pass 开始前完整清理 prefix，避免上一个架构的库污染链接
    # （已有包 tar 在 pkgDir，make_pkg 会自动解压，依赖在本 pass 内按拓扑序重建）
    # --has-prefix 时 prefix 就是要复用的对象，不能清
    if [[ -d "${prefix}" ]]; then
      if [[ "$hasPrefix" == true ]]; then
        info "保留已有 prefix (--has-prefix): ${prefix}"
      else
        info "清理 prefix: ${prefix}"
        rm -rf "${prefix}"
      fi
    fi
    targetArchs=("$_arch")
    targetArch="$_arch"

    unset _visited_ _rgraph_ _indeg_ _allPkgs_ _buildOrder_
    declare -A _rgraph_=() _indeg_=() _visited_=()
    declare -a _allPkgs_=() _buildOrder_=()

    local _pj
    if [[ "$hasPrefix" == true ]]; then
      info "--has-prefix: 跳过依赖解析，按目录顺序构建全部包"
      for _pj in "${wsDir}"/projects/*/; do
        local _pjName=$(basename "$_pj")
        [[ -f "${_pj}/build.sh" ]] && _buildOrder_+=("$_pjName")
      done
    else
      for _pj in "${wsDir}"/projects/*/; do
        local _pjName=$(basename "$_pj")
        [[ -f "${_pj}/build.sh" ]] && collect_dep "$_pjName"
      done
      toposort
    fi
    info "构建顺序: ${_buildOrder_[*]}"
    local _project
    for _project in "${_buildOrder_[@]}"; do
      disk_watermark_guard
      info "构建=> $_project"
      . "${wsDir}/empty.sh"
      . "${wsDir}/projects/$_project/build.sh" || { err "配置获取失败" && exit 1; }
      pjName="$_project"
      make_pkg || { err "构建失败!" && exit 1; }
    done
  done
  targetArchs=("${_savedTargetArchs[@]}")
}

# 主逻辑
[[ $# -eq 0 ]] && usage

# 构建动作先登记、等整个命令行解析完再执行。
#
# 选项是一个 while 循环按顺序消费的，而 --arch 决定 build() 里按几个架构各跑一遍。
# 若在解析到 --build 时立刻开跑，写在它后面的 --arch 根本还没被读到，就被静默忽略了：
# 实测 `--build all --arch arm64` 会按 aarch64 + x86_64 各构建一遍（想只要 arm64 反而
# 多花一倍时间、pkgs 里还多出一套 x86_64 包）。usage 里 --build 排在 --arch 之前，
# 按它抄下来正好会踩这个顺序。同样的道理，--has-src 之类的开关写在 --build 后面也会失效。
_actionModes=()
_actionTargets=()

push_action() {
  local _mode="$1"
  local _items="$2"
  local _item
  for _item in $_items; do
    _actionModes+=("$_mode")
    _actionTargets+=("$_item")
  done
}

run_actions() {
  local _i
  local _mode
  local _target
  local _pj
  local _pjName
  for ((_i = 0; _i < ${#_actionModes[@]}; _i++)); do
    _mode="${_actionModes[$_i]}"
    _target="${_actionTargets[$_i]}"
    if [[ "$_target" == "all" ]]; then
      case "$_mode" in
      build)
        check_projects_not_empty
        for _pj in "${wsDir}"/projects/*/; do
          _pjName=$(basename "$_pj")
          [[ -f "${_pj}/build.sh" ]] && build "$_pjName"
        done
        ;;
      # --rebuild all 与 --rebuild-deps all 语义相同：全量删除重建
      rebuild | rebuild-deps)
        rebuildMode=true
        build_all_deps
        ;;
      esac
    else
      case "$_mode" in
      build)
        build "$_target"
        ;;
      rebuild)
        rebuildTarget="$_target"
        build "$_target"
        unset rebuildTarget
        ;;
      rebuild-deps)
        rebuildMode=true
        build "$_target"
        ;;
      esac
    fi
  done
}

while [[ $# -gt 0 ]]; do
  case "$1" in
  --arch)
    [[ -z "$2" ]] && {
      err "--arch 需要指定架构"
      exit 1
    }
    if [[ "$2" == "all" ]]; then
      targetArchs=()
    else
      # 注意: 这里是顶层作用域(不在函数内), 不能用 local, 否则 bash 会报
      # "local: can only be used in a function"
      for _a in $2; do
        targetArchs+=("$(normalize_arch "$_a")")
      done
    fi
    shift 2
    ;;
  --build)
    [[ -z "$2" ]] && {
      err "--build 需要指定包名或 all"
      exit 1
    }
    # 与 --arch 语义一致: 支持空格分隔的多个包名 (整体加引号传入)
    push_action build "$2"
    shift 2
    ;;
  --rebuild)
    [[ -z "$2" ]] && {
      err "--rebuild 需要指定包名或 all"
      exit 1
    }
    # 与 --arch 语义一致: 支持空格分隔的多个包名 (整体加引号传入)
    push_action rebuild "$2"
    shift 2
    ;;
  --rebuild-deps)
    [[ -z "$2" ]] && {
      err "--rebuild-deps 需要指定包名或 all"
      exit 1
    }
    # 与 --arch 语义一致: 支持空格分隔的多个包名 (整体加引号传入)
    push_action rebuild-deps "$2"
    shift 2
    ;;
  --clean-ccache)
    clean_ccache
    shift
    ;;
  --has-src)
    hasSrc=true
    shift
    ;;
  --has-prefix)
    hasPrefix=true
    shift
    ;;
  --update-ndk)
    updateNdk=true
    shift
    ;;
  --update-host-deps)
    bash ${wsDir}/host-deps.sh || exit 1
    shift
    ;;
  --help | -h)
    usage
    ;;
  *)
    err "未知选项: $1"
    usage 1
    ;;
  esac
done

# prefix 路径不含架构，--has-prefix 复用的一定是某一种架构的产物。
# 多架构或未指定架构时会让第二个架构直接链接到第一个架构的库，必须拦下。
if [[ "$hasPrefix" == true && ${#targetArchs[@]} -ne 1 ]]; then
  err "--has-prefix 需要配合单一 --arch 使用 (如 --arch arm64)"
  exit 1
fi

[[ "$hasPrefix" == true && ! -d "${prefix}" ]] && { err "--has-prefix 指定但 prefix 不存在 => ${prefix}"; exit 1; }

# 没指定 --arch 时按两个架构各构建一遍。这是默认行为，但日志里只体现为连着两次
# "构建架构: xxx"，很容易被当成"指定了 arm64 却还在构建 x86"——先明说一句。
if [[ ${#targetArchs[@]} -eq 0 ]]; then
  warn "未指定 --arch: 将按 aarch64 与 x86_64 两个架构各构建一遍 (只要一个架构请加 --arch arm64)"
fi

# NDK 只在有构建动作或显式 --update-ndk 时处理，--help/--clean-ccache 等不再触发下载
if [[ "$updateNdk" == true ]]; then
  info "--update-ndk: 删除旧 NDK 并重新下载"
  rm -rf "$ndkDir"
fi
if [[ "$updateNdk" == true || ${#_actionModes[@]} -gt 0 ]]; then
  ensure_ndk
fi

run_actions

ok "全部完成"
