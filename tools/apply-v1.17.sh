#!/usr/bin/env bash
# ============================================================
# apply-v1.17.sh — 将 v1.17 修复覆盖进 linbox 仓库
# 用法：./apply-v1.17.sh /path/to/linbox
# 只覆盖 3 个既有文件，不动其他任何内容：
#   .github/workflows/linbox-build.yml   （wine/dxvk job 加固 + gpkg 安卓 glibc）
#   wine/build_wine_dac.sh               （GARM 收集 + wrapper seccomp 自检/am 诊断）
#   dxvk/build-dxvk.sh                   （clone/submodule 重试）
# ============================================================
set -e
REPO="${1:?用法: ./apply-v1.17.sh /path/to/linbox}"
[ -d "$REPO/.github/workflows" ] || { echo "✗ $REPO 不是 linbox 仓库根"; exit 1; }
SRC="$(cd "$(dirname "$0")/.." && pwd)"

cp -v "$SRC/.github/workflows/linbox-build.yml" "$REPO/.github/workflows/linbox-build.yml"
cp -v "$SRC/wine/build_wine_dac.sh"             "$REPO/wine/build_wine_dac.sh"
cp -v "$SRC/dxvk/build-dxvk.sh"                 "$REPO/dxvk/build-dxvk.sh"
chmod +x "$REPO/wine/build_wine_dac.sh" "$REPO/dxvk/build-dxvk.sh"

fail=0
grep -q "Fetch Android-patched glibc" "$REPO/.github/workflows/linbox-build.yml" \
  || { echo "✗ workflow 未带上 gpkg 步骤"; fail=1; }
grep -q "GARM" "$REPO/wine/build_wine_dac.sh" \
  || { echo "✗ build_wine_dac.sh 未带上 GARM"; fail=1; }
grep -q "submodule 拉取重试" "$REPO/dxvk/build-dxvk.sh" \
  || { echo "✗ build-dxvk.sh 未带上重试"; fail=1; }
[ "$fail" = 0 ] && echo "✓ v1.17 已应用：安卓补丁版 glibc（Bad system call 根因修复）+ CI 加固"
exit "$fail"
