package com.linbox.core.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.linbox.LinBoxApp
import com.linbox.apps.settings.SettingsScreen
import com.linbox.apps.terminal.TerminalScreen
import com.linbox.apps.x11.X11Screen
import com.linbox.core.input.MouseController
import com.linbox.core.input.MouseCursorOverlay
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.input.VirtualKeyboardOverlay
import com.linbox.core.input.gamepad.GamepadOverlay
import com.linbox.core.input.gamepad.GamepadSettingsWindow

/**
 * LinBox 壳层：终端主页 + X11 / 设置全屏页 + 输入覆盖层。
 *
 * 无浮动窗口、无桌面启动器（原桌面环境的壁纸/图标网格/任务栏/开始菜单
 * 及替代它的启动器卡片均已移除）——App 打开即终端，终端就是主页：
 * - 主页 = 真实 Termux 终端铺满全屏；
 * - 终端执行 `linbox-x11`（或点工具栏"X11"）→ 整屏跳转 X11 图形界面；
 * - 工具栏"设置"→ 全屏设置页（虚拟手柄开关在这里）。
 *
 * 结构（自底向上）：
 * 1. 当前页面（ShellController.screen 决定：终端 / X11 / 设置）
 * 2. VirtualKeyboardOverlay（虚拟键盘，可拖动）
 * 3. GamepadOverlay + GamepadSettingsWindow（虚拟游戏手柄：开启后
 *    摇杆/按钮/迷你工具条悬浮在当前页面之上，X11 游戏可用）
 * 4. MouseCursorOverlay（虚拟鼠标指针，最顶层）
 */
@Composable
fun LinBoxShell() {
    val app = LinBoxApp.get()
    val density = LocalDensity.current

    // 输入设置（触控板模式 / 虚拟鼠标指针 / 右键手势）
    val mouseCursorEnabled by app.settingsStore.mouseCursorEnabled.collectAsState(initial = true)
    val mouseControlMode by app.settingsStore.mouseControlMode.collectAsState(initial = "touch")
    val mouseRightClick by app.settingsStore.mouseRightClick.collectAsState(initial = "twofinger")

    // View 层触控板路由器接线：
    // 触控板模式的手势仲裁由 MainActivity.dispatchTouchEvent → TrackpadRouter 完成。
    SideEffect {
        val newEnabled = mouseControlMode == "trackpad"
        if (TrackpadRouter.enabled && !newEnabled) TrackpadRouter.onDisabled()
        TrackpadRouter.enabled = newEnabled
        TrackpadRouter.longPressRightClick = mouseRightClick == "longpress"
        TrackpadRouter.density = density.density
    }

    // 虚拟鼠标指针初始化（触控板模式下指针即鼠标本体，强制初始化；
    // 具体位置由虚拟鼠标 / 注入流驱动）
    SideEffect {
        if (mouseCursorEnabled || mouseControlMode == "trackpad") {
            MouseController.initialize(0f, 0f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ===== 1. 当前页面（终端主页 / X11 / 设置） =====
        when (ShellController.screen) {
            ShellController.Screen.TERMINAL -> TerminalScreen()
            ShellController.Screen.X11 -> X11Screen()
            ShellController.Screen.SETTINGS -> SettingsScreen()
        }

        // ===== 2. 虚拟键盘层（可拖动全键盘） =====
        VirtualKeyboardOverlay()

        // ===== 3. 虚拟游戏手柄层（摇杆/十字键/按钮 + 迷你工具条 ⚙✎✕） =====
        GamepadOverlay()
        GamepadSettingsWindow()

        // ===== 4. 虚拟鼠标指针层（最顶层） =====
        MouseCursorOverlay()
    }
}
