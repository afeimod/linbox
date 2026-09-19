package com.linbox.apps.dac

/**
 * DacNative — LinBox DAC 桥 JNI 绑定
 *
 * v1.19：available=false 时提供真实失败原因 loadError（dlopen 原文）
 * 与设备 API 级别 deviceApi，供 DacScreen 精确诊断 ——
 * 旧版只猜「可能低于 Android 10」，而实际常见失败是 APK 未包含本机 ABI
 * 的 so 或符号缺失，误导排查。
 *
 * Copyright 2026 LinBox Project (MIT)
 */
object DacNative {

    const val BACKEND_SF_DIRECT = 0   // API29+: ASurfaceControl 直合（零拷贝）
    const val BACKEND_AHB_CANVAS = 1  // API26-28: AHB CPU 拷贝降级
    const val BACKEND_DMABUF_EGL = 2  // glibc wine dmabuf EGL blit 兜底
    const val BACKEND_NONE = 3

    /** 虚拟鼠标标志位（与协议 enum dac_mouse_flags 一致） */
    const val MOUSE_MOVE = 1
    const val MOUSE_BUTTON = 2
    const val MOUSE_WHEEL = 4
    const val MOUSE_HWHEEL = 8

    /** 鼠标按键位：bit0 左 / bit1 右 / bit2 中 / bit3 X1 / bit4 X2 */
    const val BTN_LEFT = 1
    const val BTN_RIGHT = 2
    const val BTN_MIDDLE = 4

    fun backendName(backend: Int): String = when (backend) {
        BACKEND_SF_DIRECT -> "SurfaceFlinger 直合（ASurfaceControl）"
        BACKEND_AHB_CANVAS -> "AHB CPU 拷贝降级"
        BACKEND_DMABUF_EGL -> "dmabuf EGL blit 兜底"
        else -> "不可用"
    }

    /**
     * 连接：创建 socket 监听并绑定 Surface。
     * @param surface DacView (SurfaceView) 的 Surface
     * @param socketPath 绝对路径（$PREFIX/tmp/linbox-dac.sock）
     * @return 后端类型（BACKEND_*）
     */
    @JvmStatic
    external fun nativeConnect(surface: Any, socketPath: String): Int

    @JvmStatic
    external fun nativeDisconnect()

    /**
     * v1.21：呈现目标重绑定（最小化悬浮窗/还原页面时 SurfaceView 重建）。
     * socket 连接与已导入 buffer 保留，仅替换 ANativeWindow/ASurfaceControl。
     * @return 0 = 重绑成功；-1 = 显示未激活或 Surface 无效（可忽略）
     */
    @JvmStatic
    external fun nativeSetSurface(surface: Any): Int

    @JvmStatic
    external fun nativeSendMouse(
        flags: Int, x: Float, y: Float, buttons: Int,
        wheelDx: Float, wheelDy: Float, absolute: Boolean
    )

    @JvmStatic
    external fun nativeSendKey(
        down: Boolean, keycode: Int, metaState: Int,
        unicode: Int, repeat: Int, source: Int
    )

    @JvmStatic
    external fun nativeSetTitleSink(sink: TitleSink?)

    interface TitleSink {
        fun onTitle(title: String)
    }

    /** 设备 API 级别（诊断用） */
    val deviceApi: Int = android.os.Build.VERSION.SDK_INT

    /**
     * 加载诊断（v1.19）：null = liblinbox_dac_bridge.so 已就绪；
     * 否则为 dlopen 失败原文（UnsatisfiedLinkError.message）。
     * lazy 保证只 load 一次且结果全应用一致（加载失败的库重试无意义）。
     */
    val loadError: String? by lazy {
        try {
            System.loadLibrary("linbox_dac_bridge")
            null
        } catch (t: Throwable) {
            t.message ?: t.toString()
        }
    }

    val available: Boolean
        get() = loadError == null
}
