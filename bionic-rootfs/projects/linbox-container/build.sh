revision="1.0.0"
urlType="local"
pkgSrcDir="${wsDir}/projects/linbox-container"
arch="aarch64 x86_64"
buildSys="others"
license="MIT"

# ============================================================
# linbox-container — LinBox Wine 容器管理（bionic rootfs / X11）
# 纯脚本包：安装 linbox-container（容器管理 CLI）与 linbox-wine
# （后端感知启动器）进 rootfs。数据模型参考 上游参考/上游实现/
# 上游项目：每容器独立 WINEPREFIX + CPU 翻译后端 + 个性化环境。
#   后端: box64（x86_64 wine，主力）/ hangover（aarch64 qemu）
#         / fexcore（探测槽位）/ native（x86_64 设备）
#   显示: X11 —— LinBox 内置 X 服务（App 侧 tmp/.X11-unix/Xn，
#         默认 :1），自动探测 DISPLAY
#   音频: PulseAudio TCP 127.0.0.1:4713
# ============================================================

extra_fuction() {
  local _binDir="${destDir}${prefix}/bin"
  local _etcDir="${destDir}${prefix}/etc/linbox"
  local _profDir="${destDir}${prefix}/etc/profile.d"

  mkdir -p "${_binDir}" "${_etcDir}" "${_profDir}"

  echo "安装 linbox-container / linbox-wine ..."
  install -Dm755 "${pkgSrcDir}/linbox-container" "${_binDir}/linbox-container"
  install -Dm755 "${pkgSrcDir}/linbox-wine"      "${_binDir}/linbox-wine"

  # 全局配置（默认容器等；conf_write_kv 运行时回写）
  cat > "${_etcDir}/container.conf" << 'EOF'
# LinBox Wine 容器全局配置（linbox-container 维护）
# default_container=<容器名>
EOF

  # 登录 profile：把 rootfs bin 并入 PATH，终端直接敲 linbox-container
  cat > "${_profDir}/linbox-container.sh" << 'EOF'
# LinBox Wine 容器管理（由 bionic rootfs 构建系统安装）
case ":${PATH}:" in
  *":/data/data/com.linbox/files/rootfs/usr/bin:"*) ;;
  *) export PATH="/data/data/com.linbox/files/rootfs/usr/bin:${PATH}" ;;
esac
EOF

  # 容器存储与日志目录（rootfs home 下）
  mkdir -p "${destDir}/data/data/com.linbox/files/rootfs/home/.linbox/containers/logs" 2>/dev/null || true

  echo "linbox-container 安装完成"
}
