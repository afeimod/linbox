package com.linbox.apps.dac

/**
 * DacNative — LinBox DAC 桥 JNI 绑定
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

    val available: Boolean
        get() = runCatching { System.loadLibrary("linbox_dac_bridge"); true }
            .getOrDefault(false)
}
