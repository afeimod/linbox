revision="1.3.6"
url="https://downloads.xiph.org/releases/ogg/libogg-${revision}.tar.gz"
# downloads.xiph.org 历史上多次长时间不可用（2026-11-11 CI 实证：瞬时
# 断流 + SSL unexpected eof 双杀）；三级兜底 —— ① Debian pool（1.3.6 期
# 已从 pool 撤下 → 404，仅留作历史版本兜底）；② OSUOSL 官方 xiph 镜像
# （200 实测可达）；③ GitHub codeload 自动归档（v1.3.6 tag 实测 200；
# 顶层目录 ogg-1.3.6/，框架 --strip-components=1 兼容）。
backupUrls=(
  "http://deb.debian.org/debian/pool/main/libo/libogg/libogg_${revision}.orig.tar.gz"
  "https://ftp.osuosl.org/pub/xiph/releases/ogg/libogg-${revision}.tar.gz"
  "https://codeload.github.com/xiph/ogg/tar.gz/refs/tags/v${revision}"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD-3-Clause"
args="-DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF -DBUILD_TESTING=OFF"

# ===== backup: original autotools build =====
# buildSys="autotools"