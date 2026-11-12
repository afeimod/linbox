revision="1.17.0"
url="https://xorg.freedesktop.org/archive/individual/lib/libxcb-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# XCB 核心 + 全部扩展库（shm/render/randr/xfixes/sync/present/dri2/dri3/
# glx/xkb/xinput/shape/damage/xv 等，随 xcb-proto 的 XML 一并生成）
deps="xcb-proto libxau libxdmcp"

pre_setup() {
  # X11 unix socket 路径对齐 LinBox 内置 X 服务：
  # libXlorie(termux-x11) 在 App 侧前缀 tmp/.X11-unix/Xn 监听
  # （默认 /data/data/com.linbox/files/usr/tmp/.X11-unix/X1，显示号 :1），
  # rootfs 内 X 客户端（wine 等）必须连这里而非 /tmp。
  # 与 default.conf 的 appPrefix 保持一致。
  sed -i "s|/tmp/.X11-unix/X|${appPrefix:-/data/data/com.linbox/files/usr}/tmp/.X11-unix/X|g" src/xcb_util.c
  grep -q "linbox\|files/usr/tmp/.X11-unix" src/xcb_util.c || { echo "xcb socket 路径补丁失败!" && exit 1; }

  # libxcb 构建时需要宿主 python 导入 xcbgen（xcb-proto 已安装到 prefix）
  local _pyDir
  _pyDir="$(ls -d "${prefix}"/lib/python3/dist-packages "${prefix}"/lib/python3*/site-packages 2>/dev/null | head -1)"
  if [[ -n "$_pyDir" ]]; then
    export PYTHONPATH="${_pyDir}:${PYTHONPATH:-}"
  else
    echo "警告: 未找到 xcbgen 所在目录（xcb-proto 未安装?）"
  fi
  echo "PYTHONPATH=${PYTHONPATH:-}"
}

install() {
  make install DESTDIR="${destDir}"
  # SONAME 版本别名：本构建系统按 Termux/Android 惯例产无版本 .so（bionic
  # 按文件名加载），但 wine 等预编译产物的 DT_NEEDED 记录上游标准 SONAME。
  # 版本号取自上游 src/Makefile.am 的 libtool -version-number <major>:*:*
  cd "${destDir}/${prefix}/lib"
  # libtool -version-info current:revision:age → SONAME=current-age：
  #   libxcb 2:0:1→.1，sync/xkb 1:0:0→.1，dri3/randr/xinput 1:0:1→.0，其余 0:0:0→.0
  ln -sf libxcb.so libxcb.so.1
  local _f _v
  for _f in libxcb-*.so; do
    [[ -f "$_f" ]] || continue
    case "$_f" in
      libxcb-sync.so | libxcb-xkb.so) _v=1 ;;
      *) _v=0 ;;
    esac
    ln -sf "$_f" "${_f}.${_v}"
  done
}
