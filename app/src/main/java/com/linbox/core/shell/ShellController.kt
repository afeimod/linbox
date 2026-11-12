package com.linbox.core.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * LinBox 全屏导航中枢。
 *
 * LinBox 是以 **Wine 容器管理为主页** 的应用，页面结构：
 * - HOME：主页（默认）。游戏容器主页 —— 游戏模式主卡（选中容器名 /
 *   wine 版本 / 运行状态）+ 四大功能入口（按键映射/性能增强/截图录屏/
 *   手柄连接）+ 游戏库预览行。
 * - LIBRARY：游戏库（v4.0 按用户图片重制）。暗色主题网格卡片，
 *   “我的游戏 共 N 款” + 添加游戏 + 搜索 + 打开/长按菜单。
 * - ADVANCED：高级页。终端、浏览器、容器高级设置（DY/FEX/Box/Wine 安装/
 *   MangoHud/生态配置）、容器体检、文件管理器、X11 / DAC 显示、应用设置。
 * - TERMINAL：真实 Termux 终端（linbox 自有，未从 AnWind 移植）。
 * - X11：X11 图形页（linbox 自有；wine 容器的 X 服务广播到达时自动跳转，
 *   亦承载 `linbox-x11 :13` 终端侧会话）。
 * - DAC：DAC 显示器（winedac.drv 直合显示栈）。
 * - SETTINGS：应用设置（显示 / 终端背景 / 输入 / 手柄 / 开发者 / 关于）。
 *
 * 底部导航（HOME / LIBRARY / ADVANCED 页内渲染）：[主页] [游戏库]
 * [(+)容器] [设置] [高级] —— 中间为蓝色 + 浮钮（打开容器管理器窗口），
 * 原"我的"页由"高级"取代（终端 / 浏览器 / 容器高级设置）。
 *
 * 修改 screen 的入口：
 * - ShellController.showXxx()（UI 按钮 / LinBoxShellBridge 命令桥）
 * - X11WindowController.onBroadcastReceived（X server 广播自动跳转）
 * - DacApp.requestStart（DAC_START 广播）
 */
object ShellController {

    enum class Screen { HOME, LIBRARY, ADVANCED, TERMINAL, X11, DAC, SETTINGS }

    var screen by mutableStateOf(Screen.HOME)
        private set

    /** 主页（游戏容器主页）。 */
    fun showHome() {
        screen = Screen.HOME
    }

    /** 游戏库（网格卡片 / 添加游戏 / 搜索）。v4.0 按用户图片重制。 */
    fun showLibrary() {
        screen = Screen.LIBRARY
    }

    /** 高级页（终端 / 浏览器 / 容器高级设置）。 */
    fun showAdvanced() {
        screen = Screen.ADVANCED
    }

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
