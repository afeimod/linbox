# LinBox DAC native 桥 + 侧车守护进程
#
# Copyright 2026 LinBox Project (MIT)
#
# 集成方式（二选一）：
#   A) 在 app/build.gradle.kts 的 externalNativeBuild 中加入本 Android.mk
#   B) Gradle Exec 直接调用 NDK clang/clang++（同 linbox-reprefix 方案，见
#      integration/build.gradle.kts.snippet）
#
# 注意：dac_allocd 是可执行程序，ndk-build 限制模块名不能带 .so 后缀，
# 因此按 LinBox 约定以 libdac_allocd.so 名义进 APK，由安装器拷出重命名。
# 注意：linbox_dac_bridge 用 .cpp（ndk-build 按 .cpp 自动走 C++）：
# NDK r26 的 surface_control.h 含 C++ 签名（引用/默认参数）且无
# __cplusplus 分流，纯 C 模式无法解析。

LOCAL_PATH := $(call my-dir)

# ---------- JNI 桥（app 进程内） ----------
include $(CLEAR_VARS)
LOCAL_MODULE     := linbox_dac_bridge
LOCAL_SRC_FILES  := linbox_dac_bridge.cpp
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
