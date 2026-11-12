revision="${LINBOX_GLIBC_AARCH64_REV:-2.39}"
urlType="others"
arch="aarch64"
buildSys="others"
license="LGPL-2.1+"
pkgSrcDir="${wsDir}/projects/glibc-aarch64"
deps=""

# ============================================================
# aarch64 glibc 运行层（usr/lib/aarch64-linux-gnu + 私有加载器
# ld-linux-aarch64.so.1）—— bionic rootfs 的 glibc 兼容目录
# ------------------------------------------------------------
# 本 rootfs 是 bionic 系统（NDK 链接），自身不需要 glibc；本配方
# 安装的是一套【运行时兼容层】，与 glibc-x86_64（仿真侧 x86_64
# 程序的运行库，lib/x86_64-linux-gnu）互为镜像：
#
#   * glibc 形态 box64（build-box64.yml 工作流产物
#     linbox-box64-*-aarch64-glibc.tar.gz，上游参考/上游项目 同款
#     -DWINLATOR_GLIBC 形态）的 PT_INTERP / DT_RPATH 指向
#     /data/data/com.linbox/files/rootfs/usr/lib/aarch64-linux-gnu/
#     ld-linux-aarch64.so.1 —— rootfs 必须自带这套运行库，该形态
#     才能直接 exec（内核按 PT_INTERP 绝对路径加载私有加载器，
#     全程不碰 bionic）。
#   * rootfs 内运行"glibc 链接的 aarch64 Linux 二进制"：显式经
#     私有加载器启动（与 App 侧 termux glibc-runner 的 [F4] 手法
#     同源），如
#       $RFS/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 \
#           --library-path <glibc目录> <程序>
#
# 隔离性：bionic 加载器（linker64）永不搜索 aarch64-linux-gnu
# 目录，rootfs 自身 bionic 库与该目录零串扰；反之 glibc 私有
# 加载器只按 RPATH / LD_LIBRARY_PATH 找库，不会捡到 bionic 的
# 库（目录不重叠）。
#
# 实现（与 glibc-x86_64 同思路，arm64 包在 ports.ubuntu.com）：
#   * 从 Ubuntu noble（glibc 2.39，向后兼容旧二进制）的
#     noble-updates / noble-security / noble 三个 pocket 的
#     Packages.gz 索引解析 libc6 / libgcc-s1 / libstdc++6 / zlib1g
#     的最新版本与 pool 路径（Filename 字段）—— Ubuntu pool 会
#     清理旧修订（glibc-x86_64 已踩过 zlib 404 的坑），索引解析
#     可自愈版本漂移，无需改脚本
#   * 解包后把 usr/lib/aarch64-linux-gnu/* 平铺安装到
#       $prefix/lib/aarch64-linux-gnu/
#     （含 ld-linux-aarch64.so.1 / libc.so.6 / libm / libdl /
#     libpthread / librt / libgcc_s / libstdc++ / libz 及符号链接）
#   * 同时安装 deb 自带的便捷符号链接
#       $prefix/lib/ld-linux-aarch64.so.1 → aarch64-linux-gnu/…
#     （相对链接，与 Ubuntu usrmerge 布局一致）
#
# 版本钉死（不走索引解析）可用环境变量：
#   LINBOX_GLIBC_VER / LINBOX_GCC_VER / LINBOX_ZLIB_VER
#   （URL 按 g/glibc、g/gcc-14、z/zlib 的 pool 惯例拼装）
# 包名 revision 亦可用 LINBOX_GLIBC_AARCH64_REV 覆盖（默认取
# glibc 上游版本号 2.39；索引解析出的完整修订会打进构建日志，
# 本地缓存 pkgs/ 下同 revision 旧 tar 想强制刷新时用 --rebuild）。
#
# 仅 aarch64 rootfs 需要（x86_64 设备原生 bionic 运行，无 glibc
# 兼容层需求）。box64 配方 deps 挂了本包：`--build box64` /
# `--build all` 都会先构建本层再构建 box64，保证"box64 完整文件
# 集"（仿真器本体 + 双 glibc 运行层 + 配套库）一次到位。
# ============================================================

UBUNTU_PORTS="http://ports.ubuntu.com/ubuntu-ports"
# v3.3.37 镜像池（同 glibc-x86_64：GH runner 瞬时网络抖动两连击即整轮报废，
# box64-build-log-aarch64(7).zip 实证）。ports 有镜像池，依次试全部镜像，
# 全部失败才判死；可用 LINBOX_PORTS_MIRRORS 覆盖。
PORTS_MIRRORS="${LINBOX_PORTS_MIRRORS:-http://ports.ubuntu.com/ubuntu-ports https://ftp.yz.yamagata-u.ac.jp/pub/linux/ubuntu-ports https://mirror.nju.edu.cn/ubuntu-ports}"
# 环境变量钉死值（为空则走 Packages.gz 索引解析）
GLIBC_VER_PIN="${LINBOX_GLIBC_VER:-}"
GCC_VER_PIN="${LINBOX_GCC_VER:-}"
ZLIB_VER_PIN="${LINBOX_ZLIB_VER:-}"

_glibcPkgs="libc6 libgcc-s1 libstdc++6 zlib1g"

# 下载 noble 三 pocket 的 arm64 索引并解析 <pkg> 的最新版本与
# pool 路径；输出两行："<完整版本>" 与 "<pool相对路径>"
_resolve_pkg() {
  local _pkg="$1"
  local _idxDir="${srcDir}/${pjName}/pkgindex"
  local _pocket _gz _cand
  mkdir -p "${_idxDir}"
  for _pocket in noble-updates noble-security noble; do
    _gz="${_idxDir}/${_pocket}.gz"
    if [[ ! -s "${_gz}" ]]; then
      echo "索引 => ${_pocket}" >&2
      # v3.3.37：索引下载也走镜像池（索引拿不到则解析全失败，比 deb 更前置）
      for _m in ${PORTS_MIRRORS}; do
        if wget -q --timeout=90 --tries=3 --waitretry=5 --retry-connrefused \
          -O "${_gz}.tmp" "${_m}/dists/${_pocket}/main/binary-arm64/Packages.gz"; then
          mv -f "${_gz}.tmp" "${_gz}"
          break
        fi
        rm -f "${_gz}.tmp"
        echo "  [!] 索引镜像失败，换下一个 => ${_m}（${_pocket}）" >&2
      done
      [[ -s "${_gz}" ]] || echo "警告: 索引下载失败 => ${_pocket}（全部镜像）" >&2
    fi
  done
  # 汇总候选三列："<去epoch版本><TAB><完整版本><TAB><pool路径>"，
  # 按第 1 列版本排序取最高（epoch 前缀如 "1:" 不参与版本比较）
  _cand="$(
    for _pocket in noble-updates noble-security noble; do
      _gz="${_idxDir}/${_pocket}.gz"
      [[ -s "${_gz}" ]] || continue
      zcat "${_gz}" | awk -v p="${_pkg}" '
        $1 == "Package:"  { name = $2 }
        $1 == "Version:"  { ver  = $2 }
        $1 == "Filename:" { if (name == p) print ver "\t" $2 }
      '
    done | awk -F'\t' '{ v = $1; sub(/^[0-9]+:/, "", v); print v "\t" $0 }' \
      | sort -t$'\t' -k1,1V | tail -n 1 | cut -f2,3
  )"
  [[ -n "${_cand}" ]] || return 1
  printf '%s\n%s\n' "$(printf '%s' "${_cand}" | cut -f1)" \
    "$(printf '%s' "${_cand}" | cut -f2)"
}

custom_url() {
  # 必须用绝对路径：get_src 在 cd "${srcDir}" 后调用本函数，而
  # extra_fuction 执行时构建系统已 cd 进 pkgSrcDir（源码树目录），
  # 相对路径在两个阶段指向不同位置（glibc-x86_64 的 CI 实测教训）
  local _debDir="${srcDir}/${pjName}"
  local _pkg _ver _file _url _name
  local _resolved _rver _rfile
  mkdir -p "${_debDir}"

  local _urls=()
  for _pkg in ${_glibcPkgs}; do
    case "${_pkg}" in
      libc6)                        _ver="${GLIBC_VER_PIN}" ;;
      libgcc-s1 | libstdc++6)       _ver="${GCC_VER_PIN}" ;;
      zlib1g)                       _ver="${ZLIB_VER_PIN}" ;;
    esac
    if [[ -n "${_ver}" ]]; then
      # 环境变量钉死：按 pool 惯例拼路径（版本漂移时需自行维护覆盖值）
      case "${_pkg}" in
        libc6)      _file="pool/main/g/glibc/libc6_${_ver}_arm64.deb" ;;
        libgcc-s1)  _file="pool/main/g/gcc-14/libgcc-s1_${_ver}_arm64.deb" ;;
        libstdc++6) _file="pool/main/g/gcc-14/libstdc++6_${_ver}_arm64.deb" ;;
        zlib1g)     _file="pool/main/z/zlib/zlib1g_${_ver}_arm64.deb" ;;
      esac
      echo "${_pkg} => ${_ver}（环境变量钉定）"
    else
      # 索引解析最新版本（自愈 Ubuntu pool 清理旧修订导致的 404）
      _resolved="$(_resolve_pkg "${_pkg}")" \
        || { echo "错误: 索引中找不到包 => ${_pkg}" && return 1; }
      _rver="$(printf '%s\n' "${_resolved}" | sed -n '1p')"
      _rfile="$(printf '%s\n' "${_resolved}" | sed -n '2p')"
      # epoch 前缀不参与 deb 文件名（zlib 的 "1:1.3.dfsg…" → "1.3.dfsg…"）
      _ver="$(printf '%s' "${_rver}" | sed 's/^[0-9]*://')"
      _file="${_rfile}"
      echo "${_pkg} => ${_rver}"
    fi
    _urls+=("${UBUNTU_PORTS}/${_file}")
  done

  for _url in "${_urls[@]}"; do
    _name="$(basename "${_url}")"
    # v3.3.37：镜像池+强重试（单镜像 --tries=2 对瞬时超时不够用；
    # 剥掉原镜像前缀得到 /pool/... 相对路径，对池内全部镜像成立）
    _rel="/${_url#*/ubuntu-ports/}"
    echo "下载 => ${_name}"
    _ok=0
    for _m in ${PORTS_MIRRORS}; do
      if wget -nv --timeout=90 --tries=3 --waitretry=5 --retry-connrefused \
        -O "${_debDir}/${_name}" "${_m}${_rel}"; then
        _ok=1; break
      fi
      echo "  [!] 镜像失败，换下一个 => ${_m}${_rel}" >&2
      rm -f "${_debDir}/${_name}"
    done
    [[ ${_ok} == 1 ]] \
      || { echo "下载失败 => ${_url}（全部镜像重试后仍失败——网络问题非代码问题，请直接重跑 workflow）" && return 1; }
  done
}

extra_fuction() {
  local _destLib="${destDir}${prefix}/lib/aarch64-linux-gnu"
  local _debDir="${srcDir}/${pjName}"   # custom_url 的绝对下载目录，见上
  local _deb _name _ext

  mkdir -p "${_destLib}"

  for _deb in "${_debDir}"/*.deb; do
    [[ -f "${_deb}" ]] || continue
    _name="$(basename "${_deb}")"
    _ext="${pjName}/deb-${_name%.deb}"

    rm -rf "${_ext}"
    mkdir -p "${_ext}"
    dpkg-deb -x "${_deb}" "${_ext}" \
      || { echo "deb 解包失败 => ${_name}" && return 1; }

    # 平铺 usr/lib/aarch64-linux-gnu/*（loader/libc/libm/libpthread/
    # libdl/librt/libstdc++/libgcc_s/libz 及其符号链接）
    if [[ -d "${_ext}/usr/lib/aarch64-linux-gnu" ]]; then
      cp -a "${_ext}/usr/lib/aarch64-linux-gnu/." "${_destLib}/"
    fi
    # 兼容旧布局：lib/aarch64-linux-gnu 也扫一遍（若有）
    if [[ -d "${_ext}/lib/aarch64-linux-gnu" ]]; then
      cp -a "${_ext}/lib/aarch64-linux-gnu/." "${_destLib}/"
    fi
    # deb 的 usr-merge 便捷链接：usr/lib/ld-linux-aarch64.so.1 →
    # aarch64-linux-gnu/ld-linux-aarch64.so.1（相对链接，装进
    # $prefix/lib/ 后同样成立）
    if [[ -L "${_ext}/usr/lib/ld-linux-aarch64.so.1" ]]; then
      cp -a "${_ext}/usr/lib/ld-linux-aarch64.so.1" \
        "${destDir}${prefix}/lib/ld-linux-aarch64.so.1"
    fi
  done

  # ---- 安装校验：核心文件必须齐（缺一即失败，不出残废包） ----
  local _f _miss=0
  for _f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libdl.so.2 \
            libpthread.so.0 librt.so.1 libgcc_s.so.1 libstdc++.so.6 libz.so.1; do
    if [[ ! -e "${_destLib}/${_f}" ]]; then
      echo "错误: aarch64-linux-gnu 缺少 ${_f}"
      _miss=1
    fi
  done
  if [[ ! -e "${destDir}${prefix}/lib/ld-linux-aarch64.so.1" ]]; then
    echo "错误: 缺少便捷链接 ${prefix}/lib/ld-linux-aarch64.so.1"
    _miss=1
  fi
  if [[ "${_miss}" != "0" ]]; then
    echo "安装内容:" && ls "${_destLib}" | head -30
    return 1
  fi

  # ---- 架构校验：加载器必须是 aarch64 ELF（防止索引/覆盖值拉错架构） ----
  local _machine
  _machine="$(readelf -h "${_destLib}/ld-linux-aarch64.so.1" 2>/dev/null \
    | awk '/^ *Machine:/{print $2; exit}')"
  if [[ "${_machine}" != "AArch64" ]]; then
    echo "错误: ld-linux-aarch64.so.1 非 aarch64 ELF（Machine: ${_machine:-未知}）"
    return 1
  fi

  # glibc 2.34+ 把 libpthread/libdl/librt 并入 libc（留占位兼容旧
  # 二进制），noble 的 deb 自带这三个占位 .so —— 上面校验已覆盖。
  echo "glibc-aarch64 安装完成: $(ls "${_destLib}" | wc -l) 项, $(du -sh "${_destLib}" | cut -f1)"
  echo "私有加载器: ${prefix}/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
}
