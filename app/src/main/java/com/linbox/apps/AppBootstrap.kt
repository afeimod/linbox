package com.linbox.apps


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
        // v6.0（去 Windows 窗口化）：浏览器 / 文件管理器 / exe 选择器全部
        // 改为安卓全屏页（ShellController.BROWSER / FILES / EXECHOOSER），
        // 不再注册窗口应用。AppRegistry 保留给遗留 openWindowOrSystem
        // 探测（notepad / media_player 等未注册应用自动回退系统打开）。
        // v3.2.13 的 ExeRunChooserApp / BrowserApp / FileExplorerApp 定义
        // 保留（内容组件被 ShellPages.kt 复用），仅不再进窗口注册表。
    }
}
