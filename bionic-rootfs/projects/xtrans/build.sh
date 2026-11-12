revision="1.5.2"
url="https://xorg.freedesktop.org/archive/individual/lib/xtrans-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# X 传输层（libX11 编译时内嵌）。
# patch1: 去掉 socket 目录创建时的 root 属主检查（Android 应用非 root）
# patch2: X11 unix socket 路径改为 LinBox App 侧前缀
#         （libXlorie 在 $APP_PREFIX/tmp/.X11-unix/Xn 监听）
deps="xorgproto"
