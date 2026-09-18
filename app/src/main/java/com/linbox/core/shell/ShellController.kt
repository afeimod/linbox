package com.linbox.core.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * LinBox 全屏导航中枢。
 *
 * LinBox 只有四个全屏页面，没有浮动窗口/桌面启动器：
 * - TERMINAL：主页。真实 Termux 终端铺满全屏（App 打开即终端）。
 * - X11：图形界面。终端执行 `linbox-x11`（X server 广播到达）或点
 *   工具栏"X11"按钮时跳转；返回键回主页。
 * - DAC：DAC 显示器（v1.18）。悬浮球"DAC 显示器"按钮或 DAC_START
 *   广播（wine wrapper 自动拉起）进入；winedac.drv 画面直出安卓屏，
 *   返回键回主页。
 * - SETTINGS：设置页（虚拟手柄开关等），工具栏"设置"进入。
 *
 * 修改 screen 的入口：
 * - ShellController.showXxx()（UI 按钮 / LinBoxShellBridge 命令桥）
 * - X11WindowController.onBroadcastReceived（`linbox-x11` 广播自动跳转）
 * - DacApp.requestStart（DAC_START 广播 / `linbox-dac` 脚本自动跳转）
 */
object ShellController {

    enum class Screen { TERMINAL, X11, DAC, SETTINGS }

    var screen by mutableStateOf(Screen.TERMINAL)
        private set

    fun showTerminal() {
        screen = Screen.TERMINAL
    }

    fun showX11() {
        screen = Screen.X11
    }

    /** v1.18：跳转「DAC 显示器」全屏页面（悬浮球按钮 / DAC_START 广播共用入口） */
    fun showDac() {
        screen = Screen.DAC
    }

    fun showSettings() {
        screen = Screen.SETTINGS
    }
}
