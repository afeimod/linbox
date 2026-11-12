revision="2026-v1"
urlType="local"
arch="aarch64 x86_64"
buildSys="others"
license="GPL-2.0"
pkgSrcDir="${wsDir}/projects/rootfs-locale"

# ============================================================
# rootfs 语言/locale 数据（usr/lib/locale/locale-archive）
# ------------------------------------------------------------
# 此前 rootfs 缺少语言数据：容器内 glibc 程序（含经 box64 加载的
# x86_64 Linux 原生程序、proot 场景）setlocale(zh_CN.UTF-8) 找不到
# locale 数据只能回退 C locale。
# 这里在构建机（Ubuntu runner）用宿主 localedef（glibc 同源工具）
# 以 --prefix 方式把常用语言编入 locale-archive，安装到
#   $prefix/lib/locale/locale-archive
# 说明：
#   * localedef --prefix=ROOT 的输出固定落在 ROOT/usr/lib/locale/，
#     而本构建系统 device 侧前缀 prefix=.../rootfs/usr（已含 usr），
#     因此 --prefix 传 DESTDIR 下的 rootfs 根（destDir + rfsRoot），
#     恰好落进 destDir${prefix}/lib/locale/locale-archive。
#   * archive 与目标设备同为小端（x86_64 构建机 → aarch64/x86_64
#     设备），glibc 直接可读。
#   * 输入用宿主绝对路径（-i/-f 显式指定），不依赖 --prefix 的
#     输入查找逻辑。
# ============================================================

# 编入 archive 的语言（可按需增删；UTF-8 全集）
LOCALES="en_US.UTF-8 zh_CN.UTF-8 zh_TW.UTF-8 ja_JP.UTF-8 ko_KR.UTF-8 de_DE.UTF-8 fr_FR.UTF-8 es_ES.UTF-8 ru_RU.UTF-8 pt_BR.UTF-8"

extra_fuction() {
  local _rfsRoot="/data/data/com.linbox/files/rootfs"
  local _localePrefix="${destDir}${_rfsRoot}"
  local _archive="${destDir}${prefix}/lib/locale/locale-archive"
  local _loc

  if ! command -v localedef >/dev/null 2>&1; then
    echo "错误: 构建机缺少 localedef（host-deps.sh 需安装 locales 包）"
    exit 1
  fi

  # localedef --prefix 模式固定把 archive 落到 $PREFIX/usr/lib/locale/，
  # 目录必须预先存在 —— localedef 不会自建，缺失时报
  # "cannot create temporary file: .../locale-archive.XXXX: No such
  # file or directory"（CI aarch64 run 实测），全部语言失败。
  mkdir -p "${_localePrefix}/usr/lib/locale"

  # Ubuntu locales 包只带 gzip 压缩 charmap（UTF-8.gz），而 localedef
  # 不会透明解压 —— 直接把 .gz 路径喂给 -f 会把 gzip 二进制当 charmap
  # 文本逐行解析，报 "invalid UTF-8 sequence / syntax error in prolog /
  # premature end of file"（CI aarch64 run 实测），任何 locale 都编
  # 不出来。这里先解压成纯文本再传给 -f（只需解压一次，循环外缓存）。
  local _charmap="${wsDir}/tmp/UTF-8.charmap"
  if [[ ! -s "${_charmap}" ]]; then
    mkdir -p "$(dirname "${_charmap}")"
    zcat /usr/share/i18n/charmaps/UTF-8.gz > "${_charmap}" \
      || { echo "错误: 解压 UTF-8 charmap 失败"; exit 1; }
  fi

  for _loc in $LOCALES; do
    local _name="${_loc%.UTF-8}"
    echo "localedef => ${_name}.utf8"
    # -c: 遇非致命告警继续编译（个别 locale 有警告仍产出数据）
    # 输入显式给宿主绝对路径：--prefix 会给默认的系统输入目录也加前缀，
    # 隐式名（-f UTF-8）会被解析到 PREFIX 下导致找不到 charmap
    localedef --prefix="${_localePrefix}" -c \
      -i "/usr/share/i18n/locales/${_name}" \
      -f "${_charmap}" \
      "${_loc}" \
      || echo "警告: localedef ${_loc} 失败（跳过该语言，不影响其余语言）"
  done

  # 校验：archive 必须生成且非空（任一语言成功即有产出）
  if [[ ! -s "${_archive}" ]]; then
    echo "错误: locale-archive 未生成 => ${_archive}"
    exit 1
  fi
  echo "locale-archive 生成完成: $(du -h "${_archive}" | cut -f1)"
}
