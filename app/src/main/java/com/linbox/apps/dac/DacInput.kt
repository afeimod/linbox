package com.linbox.apps.dac

import android.util.Log
import android.view.KeyEvent
import com.linbox.core.input.VirtualKeyboard
import com.linbox.core.input.VirtualMouse
import com.linbox.core.input.gamepad.GamepadController

/**
 * DacInput — LinBox 输入系统 → DAC 协议适配
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 复用 LinBox 已有输入组件（core/input）：
 *  - VirtualKeyboard：虚拟键盘按键 → INPUT_KEY
 *  - VirtualMouse：虚拟鼠标按钮/滚轮 → INPUT_MOUSE（移动由 DacView 触摸直注）
 *  - GamepadController：手柄按键 → 键盘/鼠标动作映射（沿用 X11 游戏映射规则）
 */
object DacInput : DacNative.TitleSink {

    private const val TAG = "LinBoxDAC"

    // LinBox 输入监听在 DacApp 中接线；本对象只做协议翻译与去重

    private var lastButtons = 0
    var titleListener: ((String) -> Unit)? = null

    /** 虚拟键盘事件（VirtualKeyboard.KeyListener 回调转接） */
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

    /** 虚拟鼠标按钮（VirtualMouse.ActionListener 回调转接） */
    fun onMouseButton(button: Int, down: Boolean) {
        val bit = when (button) {
            VirtualMouse.BUTTON_LEFT -> 0
            VirtualMouse.BUTTON_RIGHT -> 1
            VirtualMouse.BUTTON_MIDDLE -> 2
            else -> 2
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

    /** 手柄 → 键盘/鼠标动作（复用 GamepadController 的按键映射结果） */
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
