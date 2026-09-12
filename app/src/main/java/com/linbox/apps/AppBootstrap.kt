package com.linbox.apps

import com.linbox.apps.settings.SettingsApp
import com.linbox.apps.terminal.TerminalApp
import com.linbox.apps.terminal.SimpleTerminalApp
import com.linbox.apps.x11.X11App
import com.linbox.core.window.AppRegistry

/**
 * 应用启动注册：所有内置应用在此注册到全局 AppRegistry。
 *
 * LinBox 定位为 Termux + X11 专用应用，仅注册终端与 X11 图形：
 * - TerminalApp       真实 Termux 移植版终端（默认，启动时自动打开）
 * - SimpleTerminalApp 旧模拟版简易终端（备用）
 * - X11App            内置 X11 图形（显示号 :13，终端 linbox-x11 命令调起）
 * - SettingsApp       设置（显示/主题/输入/游戏手柄等配置入口）
 *
 * 由 [com.linbox.LinBoxApp.onCreate] 调用。
 */
object AppBootstrap {
    fun registerAll() {
        AppRegistry.register(SettingsApp)
        // v2.22：终端双轨 —— 真实 Termux 移植版（默认）+ 旧模拟版（简易终端）
        AppRegistry.register(TerminalApp)
        AppRegistry.register(SimpleTerminalApp)
        // v2.22.2：内置 X11 桌面（全屏显示端，终端 linbox-x11 命令调起）
        AppRegistry.register(X11App)
    }
}
