package com.linbox.apps

import com.linbox.apps.browser.BrowserApp
import com.linbox.apps.containers.ContainersApp
import com.linbox.apps.containers.ExeRunChooserApp
import com.linbox.apps.filemanager.FileExplorerApp
import com.linbox.core.window.AppRegistry

/**
 * 应用启动注册：窗口化应用在此注册到全局 AppRegistry。
 *
 * LinBox 移植改造（去仿 Windows 桌面）+ v5.0 安卓化：
 * - 容器管理器已改为 **全屏页**（ShellController.Screen.CONTAINERS，
 *   ContainersPage），不再注册为窗口应用；
 * - [ExeRunChooserApp] exe 双击调出的「选择容器运行」窗口
 * - [BrowserApp]     浏览器（高级页入口，多标签 / 书签 / Flash）
 * - [FileExplorerApp] 文件管理器（exe/图片/驱动包选择、容器盘符浏览）
 *
 * 终端（terminal）与 X11 显示端（x11）是 linbox 自有的全屏页面
 * （ShellController.Screen.TERMINAL / X11），不经窗口系统注册。
 *
 * 由 [com.linbox.LinBoxApp.onCreate] 调用。
 */
object AppBootstrap {
    fun registerAll() {
        // v3.2.13：文件管理器双击 exe/bat 调出的「选择容器运行」窗口
        AppRegistry.register(ExeRunChooserApp)
        // 浏览器（高级页「浏览器」入口）
        AppRegistry.register(BrowserApp)
        // 文件管理器（容器 exe 选择 / 卡片图片选择 / 驱动包安装）
        AppRegistry.register(FileExplorerApp)
    }
}
