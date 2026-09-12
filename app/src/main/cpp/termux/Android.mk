LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

# LinBox Termux 移植版原生库：
#   - termux.c        —— 上游 termux terminal-emulator 的 PTY/子进程管理（Apache-2.0）
#   - linbox_bridge.c —— LinBox 专有：FIFO 桥（shell → App 命令回传）
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c linbox_bridge.c

include $(BUILD_SHARED_LIBRARY)

# ============================================================
# linbox-reprefix：包安装后前缀重写工具（可执行）
# ============================================================
# 官方仓库 deb 与 bootstrap 把 "com.termux" 焊死在文件内容里
# （ELF .rodata / 脚本 shebang / tar 成员路径）。本工具做**等长
# 字节替换** com.termux → com.linbox（两者同为 10 字节，不改文件
# 长度与 ELF 偏移，文本与二进制通用），三种模式：
#   --file <path>   显式补丁单个文件
#   --tree <dir>    递归处理目录树（改名/内容/链接），输出改动数
#   （无参数）        按 dpkg info/*.list 增量补丁（stamp 机制）
#
# ⚠️ 构建方式说明（2026-09 修订）：
# 此前曾尝试在 ndk-build 内以 LOCAL_MODULE=liblinbox_reprefix.so
# 把可执行文件伪装成 so 打包 —— NDK 会直接拒绝：
#   "LOCAL_MODULE_FILENAME must not contain a file extension"
#（BUILD_EXECUTABLE 对产物名做扩展名白名单检查，.so/.a 一律禁止）。
# 现改为：由 app/build.gradle.kts 的 buildReprefixExecutables 任务
# 直接调用本 NDK 的 clang 包装器编译 linbox_reprefix.c，产出
# <buildDir>/reprefix/<abi>/liblinbox_reprefix.so 加入 jniLibs。
# 安装期仍由 TermuxBootstrapInstaller 从 nativeLibraryDir 拷贝到
# $PREFIX/bin/linbox-reprefix 并 chmod 0700（文件名不变，无需改）。
# ============================================================
