revision="2026e-1"
# 不需要区分架构
# 源：Debian pool（tzdata 为 _all.deb，含全平台 zoneinfo）。
# ⚠ Debian pool 会定期清理旧版本（2026c-1 已被清理 404，导致 CI 整轮报废），
# revision 必须跟随 pool 现存版本更新；snapshot.debian.org 永久保留任意
# 历史版本且文件名相同，作为兑底源（wget 会跟随 302 重定向）。
url="http://deb.debian.org/debian/pool/main/t/tzdata/tzdata_${revision}_all.deb"
backupUrls=(
  "https://snapshot.debian.org/archive/debian/20261015T000000Z/pool/main/t/tzdata/tzdata_${revision}_all.deb"
  "http://deb.debian.org/debian/pool/main/t/tzdata/tzdata_2026e-0+deb13u1_all.deb"
)
urlType="wget"
license="public-domain"
arch="aarch64 x86_64"
buildSys="others"
extra_fuction() {
  # .deb = ar 归档（binutils 的 ar 为 runner 自带，不依赖 bsdtar）。
  # 用 glob 匹配而非写死 ${revision} 文件名：主源与兑底源文件名可能
  # 不同（如 deb13u1 变体），写死会在走兑底源后找不到已下载的文件。
  local _deb
  _deb="$(ls /tmp/download-src/tzdata_*_all.deb 2>/dev/null | head -1)"
  [[ -z "${_deb}" ]] && { echo "未找到 tzdata deb 文件!" && exit 1; }
  local _tmpDir="/tmp/download-src/tzdata-extract"
  rm -rf "${_tmpDir}" && mkdir -p "${_tmpDir}"
  ar p "${_deb}" data.tar.xz > "${_tmpDir}/data.tar.xz" || { echo "读取 data.tar.xz 失败!" && exit 1; }
  # 修复：Debian deb 的 data.tar.xz 成员路径带 ./ 前缀（./usr/share/zoneinfo/...），
  # 旧模式 'usr/share/zoneinfo/*' 匹配不到任何成员，tar 报
  # "usr/share/zoneinfo/*: Not found in archive" 直接失败；
  # 模式必须带 ./ 前缀才能命中（fix: leading ./ in wildcard pattern）。
  tar -xJf "${_tmpDir}/data.tar.xz" -C "${_tmpDir}" \
    --wildcards './usr/share/zoneinfo/*' || { echo "解压失败!" && exit 1; }
  # 修复：zoneinfo 安装回 ${prefix}/share/zoneinfo（与 Arch 源时代一致的单层 usr，
  # 旧代码误写成 ${prefix}/usr/share/zoneinfo，会在 rootfs 里多出一层 usr/usr）
  mkdir -p "${destDir}${prefix}/share/zoneinfo"
  cp -a "${_tmpDir}/usr/share/zoneinfo/." "${destDir}${prefix}/share/zoneinfo/" \
    || { echo "安装 zoneinfo 失败!" && exit 1; }
  rm -rf "${_tmpDir}"
}
