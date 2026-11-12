revision="2026-v1"
urlType="local"
arch="aarch64 x86_64"
deps="pthread-stub"
pkgSrcDir="${wsDir}/projects/base-config"
buildSys="others"
license="GPL-3.0"

# linux一些基本的配置文件

extra_fuction() {
  mkdir -p "${destDir}${prefix}/etc"
echo "写入hosts"
cat > "${destDir}${prefix}/etc/hosts" << EOF
127.0.0.1 localhost
::1 ip6-localhost

EOF
echo "写入resolv"
cat > "${destDir}${prefix}/etc/resolv.conf" << EOF
nameserver 223.5.5.5
nameserver 8.8.8.8
nameserver 8.8.4.4

EOF

  mkdir -p "${destDir}${prefix}/etc/ca-certificates"
  wget -nv -P "${destDir}${prefix}/etc/ca-certificates/" https://curl.haxx.se/ca/cacert.pem || { echo "文件下载失败!" && exit 1;}

cat > "${destDir}${prefix}/etc/machine-id" << EOF
d0f88608a7756c173fe9057c6a620caf
EOF

  # ---- LinBox X11 (termux-x11 / libXlorie) 运行时环境 ----
  # LinBox 内置 X 服务监听 App 侧前缀 tmp/.X11-unix/Xn（默认显示号 :1），
  # rootfs 内的 X 客户端（wine 等）通过这些环境变量连接它。
  mkdir -p "${destDir}${prefix}/etc/profile.d"
  cat > "${destDir}${prefix}/etc/profile.d/linbox-x11.sh" << EOF
# LinBox X11 运行时环境（由 bionic rootfs 构建系统生成）
export DISPLAY=\${DISPLAY:-:1}
export XDG_RUNTIME_DIR=\${XDG_RUNTIME_DIR:-/data/data/com.linbox/files/usr/tmp}
# XKB 键盘数据（xkeyboard-config 安装位置）
export XKB_CONFIG_ROOT=\${XKB_CONFIG_ROOT:-${prefix}/share/X11/xkb}
EOF

  # X11 unix socket 目录占位（X 服务在 App 侧前缀创建同名目录）
  mkdir -p "${destDir}${prefix}/../tmp/.X11-unix" 2>/dev/null || true
  mkdir -p "${destDir}${prefix}/var/tmp"

  # ---- v3.2.23（用户问题 7）：fontconfig 用户字体目录 ----
  # App 启动链把 /storage/emulated/0/LinBox/fonts/ 里的用户字体动态
  # 导入 $prefix/share/fonts/linbox-user/（rootfs-fonts 的默认扫描根
  # 是 $prefix/share/fonts，本配置显式列出子目录 —— 防个别 fontconfig
  # 版本/配置不递归；cachedir 指向 App 可写路径，缓存损坏自动重建）。
  mkdir -p "${destDir}${prefix}/etc/fonts" "${destDir}${prefix}/share/fonts/linbox-user"
  cat > "${destDir}${prefix}/etc/fonts/local.conf" << EOF
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <!-- LinBox 用户字体（App 从 内部存储/LinBox/fonts/ 动态导入） -->
  <dir>${prefix}/share/fonts/linbox-user</dir>
  <!-- 缓存放 App 数据区（rootfs 其余路径对运行期只读） -->
  <cachedir>/data/data/com.linbox/files/rootfs/var/cache/fontconfig</cachedir>
</fontconfig>
EOF
}
