package com.linbox.apps.dac

import android.util.Log
import android.view.KeyEvent

/**
 * DacInput — 输入事件 → DAC 协议适配（自包含，不依赖 core/input）
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 各类输入源的接入口：
 *  - 键盘：Android KeyEvent（软键盘/物理键盘直通）→ INPUT_KEY
 *  - 鼠标：按钮/滚轮 → INPUT_MOUSE（移动由 DacView 触摸直注）
 *  - 手柄：外部（如 GamepadController 的键鼠映射结果）经
 *    onGamepadAction/onHardwareKey 注入，本对象不直接依赖其类型
 */
object DacInput : DacNative.TitleSink {

    private const val TAG = "LinBoxDAC"

    /** 鼠标按钮常量（与 DacNative.BTN_* 位掩码一致：bit0 左 / bit1 右 / bit2 中） */
    const val BUTTON_LEFT = DacNative.BTN_LEFT
    const val BUTTON_RIGHT = DacNative.BTN_RIGHT
    const val BUTTON_MIDDLE = DacNative.BTN_MIDDLE

    // 输入监听在 DacApp/壳层接线；本对象只做协议翻译与去重

    private var lastButtons = 0
    var titleListener: ((String) -> Unit)? = null

    /** 虚拟键盘事件（软键盘/键盘条回调转接） */
    fun onKeyboardEvent(keyEvent: KeyEvent, down: Boolean) {
        // unicode 由 KeyEvent 提供（软件键盘最佳猜测）
        val unicode = if (down && keyEvent.unicodeChar in 1..0xFFFF) keyEvent.unicodeChar else 0
        DacNative.nativeSendKey(
            down,
            keyEvent.keyCode,
            keyEvent.metaState,
            unicode,
            keyEvent.repeatCount,
            0
        )
    }

    /** 虚拟鼠标按钮（button ∈ BUTTON_*；按键条/手柄映射回调转接） */
    fun onMouseButton(button: Int, down: Boolean) {
        val bit = when (button) {
            BUTTON_LEFT, DacNative.BTN_LEFT -> 0
            BUTTON_RIGHT, DacNative.BTN_RIGHT -> 1
            else -> 2   // BUTTON_MIDDLE / 未知按钮归中键位
        }
        val mask = 1 shl bit
        val buttons = if (down) lastButtons or mask else lastButtons and mask.inv()
        val flags = DacNative.MOUSE_MOVE or DacNative.MOUSE_BUTTON
        DacNative.nativeSendMouse(flags, -1f, -1f, buttons, 0f, 0f, false)
        lastButtons = buttons
    }

    /** 虚拟鼠标滚轮 */
    fun onMouseWheel(deltaY: Float, deltaX: Float = 0f) {
        DacNative.nativeSendMouse(DacNative.MOUSE_WHEEL, -1f, -1f, 0, deltaX, deltaY, false)
    }

    /** 手柄 → 键盘/鼠标动作（接外部 GamepadController 的按键映射结果） */
    fun onGamepadAction(mappedKeyCode: Int, down: Boolean) {
        DacNative.nativeSendKey(down, mappedKeyCode, 0, 0, 0, 1)
    }

    /** Android 物理键（外接键鼠 / 手柄映射）直通 */
    fun onHardwareKey(keyCode: Int, metaState: Int, down: Boolean) {
        DacNative.nativeSendKey(down, keyCode, metaState, 0, 0, 0)
    }

    override fun onTitle(title: String) {
        Log.d(TAG, "wine title: $title")
        titleListener?.invoke(title)
    }
}
