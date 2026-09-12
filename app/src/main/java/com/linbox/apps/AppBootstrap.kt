package com.linbox.apps

import com.linbox.apps.browser.BrowserApp
import com.linbox.apps.calculator.CalculatorApp
import com.linbox.apps.clock.ClockApp
import com.linbox.apps.filemanager.FileExplorerApp
import com.linbox.apps.imageviewer.ImageViewerApp
import com.linbox.apps.media.MediaPlayerApp
import com.linbox.apps.minesweeper.MinesweeperApp
import com.linbox.apps.music.MusicPlayerApp
import com.linbox.apps.notepad.NotepadApp
import com.linbox.apps.settings.SettingsApp
import com.linbox.apps.sysinfo.SysInfoApp
import com.linbox.apps.terminal.TerminalApp
import com.linbox.apps.terminal.SimpleTerminalApp
import com.linbox.apps.x11.X11App
import com.linbox.core.window.AppRegistry

/**
 * 应用启动注册：所有内置应用在此注册到全局 AppRegistry。
 *
 * 由 [com.linbox.LinBoxApp.onCreate] 调用。
 */
object AppBootstrap {
    fun registerAll() {
        AppRegistry.register(BrowserApp)
        AppRegistry.register(FileExplorerApp)
        AppRegistry.register(SettingsApp)
        AppRegistry.register(NotepadApp)
        AppRegistry.register(CalculatorApp)
        AppRegistry.register(SysInfoApp)
        AppRegistry.register(ImageViewerApp)
        AppRegistry.register(ClockApp)
        // v2.22：终端双轨 —— 真实 Termux 移植版（默认）+ 旧模拟版（简易终端）
        AppRegistry.register(TerminalApp)
        AppRegistry.register(SimpleTerminalApp)
        // v2.22.2：内置 X11 桌面（全屏显示端，终端 linbox-x11 命令调起）
        AppRegistry.register(X11App)
        AppRegistry.register(MediaPlayerApp)
        AppRegistry.register(MusicPlayerApp)
        AppRegistry.register(MinesweeperApp)
    }
}
