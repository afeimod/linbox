revision="2.39-0ubuntu8.9"
urlType="others"
arch="aarch64"
buildSys="others"
license="LGPL-2.1+"
pkgSrcDir="${wsDir}/projects/glibc-x86_64"

# ============================================================
# box64 用的 x86_64 glibc 运行库（usr/lib/x86_64-linux-gnu）
# ------------------------------------------------------------
# 背景：本 rootfs 为 bionic 系统，wine 本体是 bionic 链接、box64
# 直接加载无需 glibc；但用户还会用 box64 跑"常规 x86_64 Linux
# 程序"（glibc 链接的 ELF），这类程序需要 x86_64 版 glibc 运行库。
# 此前 rootfs 缺少该目录，box64 跑 glibc 程序直接报
#   "libpthread/libdl/libc.so.6: cannot open shared object file"
# 实现（glibc-runner 同款思路，位置对齐 Debian 布局）：
#   * 从 Ubuntu noble（glibc 2.39，向后兼容旧二进制）pool 抓取
#     libc6 / libgcc-s1 / libstdc++6 / zlib1g 四个核心 deb
#   * 解包后把 usr/lib/x86_64-linux-gnu/* 平铺安装到
#       $prefix/lib/x86_64-linux-gnu/
#     （含 ld-linux-x86-64.so.2；box64 会在 BOX64_LD_LIBRARY_PATH
#       各目录中查找 PT_INTERP 的加载器）
#   * bionic 加载器永不搜索该目录（仅 box64 使用），与 rootfs 自身
#     库零串扰；linbox-wine 在检测到该目录时自动导出
#     BOX64_LD_LIBRARY_PATH。
# 仅 aarch64 rootfs 需要（x86_64 设备原生运行，无需模拟层）。
# 版本可用环境变量覆盖（版本号变了改 URL 即可）：
#   LINBOX_GLIBC_URL / LINBOX_GCC_URL（在 URL 里换文件名）
# ============================================================

UBUNTU_POOL="http://archive.ubuntu.com/ubuntu/pool/main"
# v3.3.37 镜像池（box64-build-log-aarch64(7).zip 实证：GH runner 到
# archive.ubuntu.com 的瞬时网络抖动——“Read error (Connection timed
# out) in headers”两连击即整轮报废，构建连 box64 配方都没进）。
# custom_url 依次试池内全部镜像，每镜像 wget 自带内部重试，
# 全部镜像失败才判死。镜像列表可用 LINBOX_UBUNTU_MIRRORS 覆盖。
UBUNTU_MIRRORS="${LINBOX_UBUNTU_MIRRORS:-http://archive.ubuntu.com/ubuntu http://azure.archive.ubuntu.com/ubuntu http://us.archive.ubuntu.com/ubuntu https://mirrors.edge.kernel.org/ubuntu}"
GLIBC_VER="${LINBOX_GLIBC_VER:-2.39-0ubuntu8.9}"
GCC_VER="${LINBOX_GCC_VER:-14.2.0-4ubuntu2~24.04.1}"
# ⚠ Ubuntu pool 会清理旧修订（1.3.dfsg-3.1ubuntu2.2 已 404，导致 CI 整轮报废），
#   版本漂移时可用环境变量 LINBOX_ZLIB_VER 覆盖，无需改脚本。
ZLIB_VER="${LINBOX_ZLIB_VER:-1.3.dfsg-3.1ubuntu2.3}"

DEBS="
${UBUNTU_POOL}/g/glibc/libc6_${GLIBC_VER}_amd64.deb
${UBUNTU_POOL}/g/gcc-14/libgcc-s1_${GCC_VER}_amd64.deb
${UBUNTU_POOL}/g/gcc-14/libstdc++6_${GCC_VER}_amd64.deb
${UBUNTU_POOL}/z/zlib/zlib1g_${ZLIB_VER}_amd64.deb
"

custom_url() {
  # 必须用绝对路径：get_src 在 cd "${srcDir}" 后调用本函数，而
  # extra_fuction 执行时构建系统已 cd 进 pkgSrcDir（源码树目录），
  # 相对路径在两个阶段指向不同位置 -> 下载成功但校验报"缺少已下载的包"
  # （CI run#14 rootfs 构建折在此处）。
  local _deb _name _rel _m _ok
  mkdir -p "${srcDir}/${pjName}"
  for _deb in $DEBS; do
    _name="$(basename "${_deb}")"
    # v3.3.37：剥掉原镜像前缀得到 /pool/main/... 相对路径（对全部镜像成立），
    # 依次试镜像池；单镜像 --tries=2 对瞬时超时不够用（日志 7 两连击即死）。
    _rel="/${_deb#*/ubuntu/}"
    echo "下载 => ${_name}"
    _ok=0
    for _m in ${UBUNTU_MIRRORS}; do
      if wget -nv --timeout=90 --tries=3 --waitretry=5 --retry-connrefused \
        -O "${srcDir}/${pjName}/${_name}" "${_m}${_rel}"; then
        _ok=1; break
      fi
      echo "  [!] 镜像失败，换下一个 => ${_m}${_rel}" >&2
      rm -f "${srcDir}/${pjName}/${_name}"
    done
    [[ ${_ok} == 1 ]] \
      || { echo "下载失败 => ${_deb}（全部镜像重试后仍失败——网络问题非代码问题，请直接重跑 workflow）" && return 1; }
  done
}

extra_fuction() {
  local _destLib="${destDir}${prefix}/lib/x86_64-linux-gnu"
  local _debDir="${srcDir}/${pjName}"   # custom_url 的绝对下载目录，见上
  local _deb _name _ext

  mkdir -p "${_destLib}"

  for _deb in $DEBS; do
    _name="$(basename "${_deb}")"
    _ext="${pjName}/deb-${_name%.deb}"

    if [[ ! -f "${_debDir}/${_name}" ]]; then
      echo "错误: 缺少已下载的包 => ${_debDir}/${_name}" && exit 1
    fi
    rm -rf "${_ext}"
    mkdir -p "${_ext}"
    dpkg-deb -x "${_debDir}/${_name}" "${_ext}" \
      || { echo "deb 解包失败 => ${_name}" && exit 1; }

    # 平铺 usr/lib/x86_64-linux-gnu/*（libc/libm/libpthread/ld-linux/
    # libstdc++/libgcc_s/libz 及其符号链接；Ubuntu usrmerge 后
    # ld-linux-x86-64.so.2 实体也在该目录）
    if [[ -d "${_ext}/usr/lib/x86_64-linux-gnu" ]]; then
      cp -a "${_ext}/usr/lib/x86_64-linux-gnu/." "${_destLib}/"
    fi
    # 兼容旧布局：lib/x86_64-linux-gnu 与 lib64 也扫一遍（若有）
    [[ -d "${_ext}/lib/x86_64-linux-gnu" ]] && \
      cp -a "${_ext}/lib/x86_64-linux-gnu/." "${_destLib}/"
    if [[ -d "${_ext}/lib64" ]]; then
      # lib64 下是 ld-linux 的绝对符号链接（指向 /usr/lib64/...），
      # 解包后为断链——仅当目标实体缺失时才补拷实体名
      local _ld="${_destLib}/ld-linux-x86-64.so.2"
      if [[ ! -e "${_ld}" ]] && ls "${_ext}"/lib64/ld-linux-x86-64.so.* >/dev/null 2>&1; then
        echo "警告: ld-linux 实体缺失，尝试从 lib64 链接目标解析"
      fi
    fi
  done

  # ---- 安装校验：核心文件必须齐 ----
  local _f _miss=0
  for _f in libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0 \
            librt.so.1 ld-linux-x86-64.so.2 libstdc++.so.6 libgcc_s.so.1 libz.so.1; do
    if [[ ! -e "${_destLib}/${_f}" ]]; then
      echo "错误: x86_64-linux-gnu 缺少 ${_f}"
      _miss=1
    fi
  done
  [[ "$_miss" == "0" ]] || { echo "安装内容:" && ls "${_destLib}" | head -30 && exit 1; }

  # glibc 2.34+ 把 libpthread/libdl/librt 并入 libc（留占位兼容旧
  # 二进制）， noble 的 deb 自带这三个占位 .so —— 上面校验已覆盖。
  echo "glibc-x86_64 安装完成: $(ls "${_destLib}" | wc -l) 项, $(du -sh "${_destLib}" | cut -f1)"
}
