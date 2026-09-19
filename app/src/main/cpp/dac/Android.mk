# LinBox DAC native 桥 + 侧车守护进程
#
# Copyright 2026 LinBox Project (MIT)
#
# ⚠️ v1.19：APK 正式构建走 app/build.gradle.kts 的 Gradle Exec 块
# （linbox_dac_bridge.cpp，API 26 目标 + API 29 符号运行时 dlsym）。
# 本 mk 引用的 linbox_dac_bridge.c 为遗留参考实现（API 29 直链版），
# 仅作对照，勿用于出包 —— 低版本设备上会 dlopen 失败。
#
# 集成方式（二选一）：
#   A) 在 app/build.gradle.kts 的 externalNativeBuild 中加入本 Android.mk
#   B) Gradle Exec 直接调用 NDK clang（同 linbox-reprefix 方案，见
#      integration/build.gradle.kts.snippet）
#
# 注意：dac_allocd 是可执行程序，ndk-build 限制模块名不能带 .so 后缀，
# 因此按 LinBox 约定以 libdac_allocd.so 名义进 APK，由安装器拷出重命名。

LOCAL_PATH := $(call my-dir)

# ---------- JNI 桥（app 进程内） ----------
include $(CLEAR_VARS)
LOCAL_MODULE     := linbox_dac_bridge
LOCAL_SRC_FILES  := linbox_dac_bridge.c
LOCAL_CFLAGS     := -O2 -Wall -Wextra -fvisibility=default
LOCAL_LDLIBS     := -llog -landroid -lEGL -lGLESv2
include $(BUILD_SHARED_LIBRARY)

# ---------- 侧车守护进程（glibc Wine 支持；可执行伪装 so） ----------
include $(CLEAR_VARS)
LOCAL_MODULE     := dac_allocd
LOCAL_MODULE_FILENAME := libdac_allocd.so
LOCAL_SRC_FILES  := dac_allocd.c
LOCAL_CFLAGS     := -O2 -Wall -Wextra
LOCAL_LDLIBS     := -landroid -llog
include $(BUILD_EXECUTABLE)
