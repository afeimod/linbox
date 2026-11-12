revision="1.6.1"
url="https://downloads.xiph.org/releases/opus/opus-${revision}.tar.gz"
# downloads.xiph.org 同 libogg（2026-11-11 CI 实证宕机）；三级兜底 ——
# Debian pool（1.6.1 或撤下 → 404 风险，历史版本兜底）+ OSUOSL 官方
# xiph 镜像 + GitHub codeload 自动归档（xiph/opus tag v1.6.1；顶层目录
# opus-1.6.1/，框架 --strip-components=1 兼容）。
backupUrls=(
  "http://deb.debian.org/debian/pool/main/o/opus/opus_${revision}.orig.tar.gz"
  "https://ftp.osuosl.org/pub/xiph/releases/opus/opus-${revision}.tar.gz"
  "https://codeload.github.com/xiph/opus/tar.gz/refs/tags/v${revision}"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="
Copyright 2001-2023 Xiph.Org, Skype Limited, Octasic,
                    Jean-Marc Valin, Timothy B. Terriberry,
                    CSIRO, Gregory Maxwell, Mark Borgerding,
                    Erik de Castro Lopo, Mozilla, Amazon
"
args="--disable-extra-programs"