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
import com.linbox.apps.dac.DacScreen
import com.linbox.apps.settings.SettingsScreen
import com.linbox.apps.terminal.TerminalScreen
import com.linbox.apps.x11.X11Screen
import com.linbox.core.input.MouseController
import com.linbox.core.input.MouseCursorOverlay
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.input.VirtualKeyboardOverlay
import com.linbox.core.input.gamepad.GamepadController
import com.linbox.core.input.gamepad.GamepadFloatingBall
import com.linbox.core.input.gamepad.GamepadOverlay
import com.linbox.core.input.gamepad.GamepadSettingsWindow
import com.linbox.core.window.WinDialogHost
import com.linbox.core.window.WindowHost
import kotlinx.coroutines.flow.first
import com.linbox.shellui.AdvancedScreen
import com.linbox.shellui.ContainerHomeScreen
import com.linbox.shellui.ContainersScreen
import com.linbox.shellui.GameLibraryScreen

/**
 * LinBox 壳层：**以 Wine 容器管理为主页** 的直接性容器管理界面。
 *
 * 结构（自底向上）：
 * 1. 当前页面（ShellController.screen 决定）：
 *    - HOME 主页 = 游戏容器主页（游戏模式主卡 + 四大功能入口 + 游戏库预览）
 *    - LIBRARY 游戏库 = 暗色网格封面卡片 + 添加游戏 + 搜索（v4.0 重制）
 *    - ADVANCED 高级页 = 终端 / 浏览器 / 容器高级设置 / 体检 / 文件管理器
 *      / X11 / DAC / 应用设置（底部导航）
 *    - TERMINAL / X11 / DAC / SETTINGS = linbox 自有全屏页面（无底部导航）
 * 2. 浮动窗口层（AnWind 窗口系统移植）：容器管理器（五选项卡配置）、
 *    exe 选择器、浏览器、文件管理器、日志/体检弹窗等以可拖拽缩放的
 *    窗口形式展示 —— "容器界面以窗口形式展示各种容器"
 * 3. WinDialogHost（Qt 风格弹窗栈，容器管理器的全部弹窗）
 * 4. VirtualKeyboardOverlay（虚拟键盘，可拖动）
 * 5. GamepadOverlay + GamepadSettingsWindow + GamepadFloatingBall
 *    （虚拟游戏手柄 + 常驻悬浮球）
 * 6. MouseCursorOverlay（虚拟鼠标指针，最顶层）
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

    // 性能治理（AnWind v3.4.0 移植）：X11 页面（真全屏游戏会话）期间，
    // 主界面周期性刷新（容器运行态轮询等）全部静止，把大核让给 wine/box64
    SideEffect {
        com.linbox.core.PerfGovernor.update(ShellController.screen == ShellController.Screen.X11)
    }

    // ===== v6.0：虚拟手柄配置全局一次加载 =====
    // 旧版在每处 GamepadOverlayContent 组合里各自 LaunchedEffect 加载 ——
    // 悬浮球"开启手柄"先置 visible=true，覆盖层的迟到加载再把 visible 写回
    // 旧配置值（padVisible=false），手柄闪现即消失 / 多次点击才出现；
    // X11 ComposeView 宿主与主树双组合并发加载还会互相踩状态。
    // 全局唯一加载点 + loaded 守卫，彻底消除竞态。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val json = app.settingsStore.gamepadConfig.first()
        GamepadController.ensureLoaded(json)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ===== 1. 当前页面 =====
        when (ShellController.screen) {
            ShellController.Screen.HOME -> ContainerHomeScreen()
            ShellController.Screen.LIBRARY -> GameLibraryScreen()
            ShellController.Screen.ADVANCED -> AdvancedScreen()
            ShellController.Screen.CONTAINERS -> ContainersScreen()
            ShellController.Screen.TERMINAL -> TerminalScreen()
            ShellController.Screen.X11 -> X11Screen()
            ShellController.Screen.DAC -> DacScreen()
            ShellController.Screen.SETTINGS -> SettingsScreen()
            // v6.0：浏览器 / 文件管理器 / exe 选择器 —— 安卓全屏页
            //（去 Windows 窗口化，内容复用 ShellPages.kt 伪窗口壳）
            ShellController.Screen.BROWSER -> com.linbox.shellui.BrowserScreen()
            ShellController.Screen.FILES -> com.linbox.shellui.FilesScreen()
            ShellController.Screen.EXECHOOSER -> com.linbox.shellui.ExeChooserScreen()
        }

        // ===== 2. 浮动窗口层（浏览器 / 文件管理器 / exe 选择器…；
        //      v5.0 容器管理器已改全屏页，不再走窗口系统） =====
        WindowHost()

        // ===== 3. Qt 风格弹窗栈（容器管理器全部弹窗基于此） =====
        WinDialogHost()

        // ===== 4. 虚拟键盘层（可拖动全键盘） =====
        VirtualKeyboardOverlay()

        // ===== 5. 虚拟游戏手柄层（悬浮球 + 摇杆/按钮 + 设置窗） =====
        GamepadOverlay()
        GamepadSettingsWindow()
        GamepadFloatingBall()

        // ===== 6. 虚拟鼠标指针层（最顶层） =====
        MouseCursorOverlay()
    }
}
