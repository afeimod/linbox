revision="1.17.0"
url="https://xorg.freedesktop.org/archive/individual/proto/xcb-proto-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# XCB 协议描述（XML）+ xcbgen python 模块。
# 本包不需要任何目标架构二进制；xcbgen 供 libxcb 构建时由宿主 python 导入。
# xml/pc 文件安装到 ${prefix}/share，xcbgen 由下方 install() 钩子
# 归位到固定路径 ${prefix}/lib/python3/dist-packages/xcbgen，
# libxcb 构建时通过 PYTHONPATH 引用。
deps=""

install() {
  make install DESTDIR="${destDir}" PREFIX="${prefix}"
  # 归位 xcbgen 到确定性路径（发行版 python 的 pythondir 命名不一致：
  #   * python3.10（ubuntu-22.04）: ${prefix}/lib/python3.10/dist-packages
  #   * python3.12（ubuntu-24.04）: ${prefix}/local/lib/python3.12/dist-packages
  #     （Debian 补丁把 pythondir 挂到 /usr/local 子树，因此必须同时搜索
  #      ${prefix}/lib 与 ${prefix}/local/lib 两棵子树）
  mkdir -p "${destDir}${prefix}/lib/python3/dist-packages"
  local _gen _target
  _target="${destDir}${prefix}/lib/python3/dist-packages/xcbgen"
  _gen="$(find "${destDir}${prefix}/lib" "${destDir}${prefix}/local/lib" \
            -mindepth 1 -maxdepth 6 -type d -name xcbgen 2>/dev/null | head -1)"
  # 兜底：在整个 DESTDIR 前缀树内查找（排除归位目标自身，防止误拿空目录）
  if [[ -z "$_gen" ]]; then
    _gen="$(find "${destDir}${prefix}" -path "$_target" -prune -o \
              -mindepth 1 -type d -name xcbgen -print 2>/dev/null | head -1)"
  fi
  if [[ -n "$_gen" && "$_gen" != "$_target" ]]; then
    rm -rf "$_target"
    cp -a "${_gen}" "$_target"
  fi
  # 守护：确保 xcbgen 完整（含 __init__.py），否则 libxcb 构建必然失败
  [[ -f "${_target}/__init__.py" ]] || {
    echo "xcbgen 归位失败!" && exit 1
  }
}
