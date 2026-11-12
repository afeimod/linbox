# apply_patches / apply_ndk_patches 应用补丁前的占位符替换规则（按书写顺序执行）
# ${prefix} 为构建前缀变量（default.conf 定义，可被 custom.conf 覆盖），
# 由 build-rootfs-arch.sh 的 expand_patch_rules 在运行时展开，本文件不写死 rootfs 路径
s|@TERMUX_PREFIX@|${prefix}|g
s|@TERMUX_PREFIX_CLASSICAL@|${prefix}|g
s|@TERMUX_HOME@|${rootfsHome}|g
s|@ROOTFS_PREFIX@|${prefix}|g

# LinBox App 侧 termux 前缀（X11 unix socket 所在位置）。
# LinBox 内置 X 服务（libXlorie / termux-x11）在
#   /data/data/com.linbox/files/usr/tmp/.X11-unix/Xn
# 监听标准 X11 协议，bionic rootfs 里的 X 客户端（wine 等）必须连这里。
# 该路径与 rootfs 前缀（${prefix}）不同，故单独占位；App 包名变更时
# 同步修改 default.conf 的 appPrefix 与下面这一行。
s|@APP_PREFIX@|/data/data/com.linbox/files/usr|g

# 注意：不要添加 __TERMUX__ -> __ANDROID__ 的文本替换！
# __TERMUX__ / __TERMUX_PREFIX__ 是编译期宏，由 NDK 补丁 sys-cdefs.h.patch 定义
# （#define __TERMUX__ 1）。文本替换会破坏补丁中的宏定义和 !defined(__TERMUX__) 类
# 条件判断。
