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
 * - CONTAINERS：v5.0 容器管理器全屏页（安卓化：不再以浮动窗口形式，
 *   顶部安卓 app bar + 底部导航，容器列表 / 五选项卡 / 高级设置 / 体检）。
 * - TERMINAL：真实 Termux 终端（linbox 自有，未从 AnWind 移植）。
 * - X11：X11 图形页（linbox 自有；wine 容器的 X 服务广播到达时自动跳转，
 *   亦承载 `linbox-x11 :13` 终端侧会话）。
 * - DAC：DAC 显示器（winedac.drv 直合显示栈）。
 * - SETTINGS：应用设置（显示 / 输入 / 手柄 / 容器高级 / 开发者 / 关于，
 *   v5.0 全部设置收进底部导航“设置”页，安卓风重制）。
 *
 * 底部导航（HOME / LIBRARY / ADVANCED 页内渲染）：[主页] [游戏库]
 * [(+)容器] [设置] [高级] —— 中间为蓝色 + 浮钮（进入容器管理器全屏页），
 * 原"我的"页由"高级"取代（终端 / 浏览器 / 容器高级设置）。
 *
 * 修改 screen 的入口：
 * - ShellController.showXxx()（UI 按钮 / LinBoxShellBridge 命令桥）
 * - X11WindowController.onBroadcastReceived（X server 广播自动跳转）
 * - DacApp.requestStart（DAC_START 广播）
 */
object ShellController {

    enum class Screen {
        HOME, LIBRARY, ADVANCED, CONTAINERS, TERMINAL, X11, DAC, SETTINGS,
        BROWSER, FILES, EXECHOOSER
    }

    var screen by mutableStateOf(Screen.HOME)
        private set

    /**
     * v6.1：进入页面级全屏页（BROWSER / FILES / EXECHOOSER）前的来源页。
     *
     * 修复：发起文件选择后只能回「高级」页，永远回不到发起页 ——
     * 现在选完文件 / 返回都回到真正的发起页（游戏库弹窗选 exe、
     * 容器编辑器选驱动…均去而复返）。
     */
    private var pageOrigin: Screen = Screen.HOME

    /** 进入页面级全屏页，记录来源（已在页内再次拉起时不覆盖原来源）。 */
    private fun openShellPage(target: Screen) {
        if (screen != Screen.BROWSER && screen != Screen.FILES && screen != Screen.EXECHOOSER) {
            pageOrigin = screen
        }
        screen = target
    }

    /** v6.1：页面级返回（文件选择完成/取消后回到发起页）。 */
    fun closePage() {
        screen = pageOrigin
    }

    /**
     * v6.0：安卓全屏页深链参数（浏览器 / 文件管理器 / exe 选择器）。
     *
     * 一次性消费（同 ContainerNav 模式）：
     * - BROWSER：url（打开指定网址）
     * - FILES：pickMode（text/media/image/dir/exe/driver…）、targetApp、
     *   targetWindow（FilePickBus 回投路由）、path（初始目录）
     * - EXECHOOSER：path（待运行的 exe 完整路径）
     */
    object PageNav {
        var url by mutableStateOf<String?>(null)
        var pickMode by mutableStateOf<String?>(null)
        var targetApp by mutableStateOf<String?>(null)
        var targetWindow by mutableStateOf<String?>(null)
        var path by mutableStateOf<String?>(null)

        fun consume(): Map<String, String> {
            val args = buildMap {
                url?.let { put("url", it) }
                pickMode?.let { put("pickMode", it) }
                targetApp?.let { put("targetApp", it) }
                targetWindow?.let { put("targetWindow", it) }
                path?.let { put("path", it) }
            }
            url = null
            pickMode = null
            targetApp = null
            targetWindow = null
            path = null
            return args
        }
    }

    /** 浏览器安卓全屏页（url 可空 = 新标签页）。v6.0 去窗口化。 */
    fun showBrowser(url: String? = null) {
        PageNav.url = url
        openShellPage(Screen.BROWSER)
    }

    /** 文件管理器安卓全屏页（pickMode/targetApp 为选择模式参数）。v6.0 去窗口化。 */
    fun showFiles(
        pickMode: String? = null,
        targetApp: String? = null,
        targetWindow: String? = null,
        path: String? = null
    ) {
        PageNav.pickMode = pickMode
        PageNav.targetApp = targetApp
        PageNav.targetWindow = targetWindow
        PageNav.path = path
        openShellPage(Screen.FILES)
    }

    /** exe 选择器安卓页（path = 待运行 exe 完整路径；文件管理器双击 exe 调起）。 */
    fun showExeChooser(path: String) {
        PageNav.path = path
        openShellPage(Screen.EXECHOOSER)
    }

    /**
     * v5.0：容器管理器深链参数（进入 CONTAINERS 页一次性消费）。
     *
     * - focus：选中指定容器（首页卡片“配置”按钮 / 游戏库长按菜单）
     * - page："advanced"（高级设置视图）/ "doctor"（容器体检）
     * - autorun：启动器快捷方式自动运行指定容器
     */
    object ContainerNav {
        var focus by mutableStateOf<String?>(null)
        var page by mutableStateOf<String?>(null)
        var autorun by mutableStateOf<String?>(null)

        fun consume(): Map<String, String> {
            val args = buildMap {
                focus?.let { put("focus", it) }
                page?.let { put("page", it) }
                autorun?.let { put("autorun", it) }
            }
            focus = null
            page = null
            autorun = null
            return args
        }
    }

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

    /** v5.0：容器管理器全屏页（focus=选中容器；page=advanced/doctor；autorun=快捷方式自动运行）。 */
    fun openContainers(focus: String? = null, page: String? = null, autorun: String? = null) {
        ContainerNav.focus = focus
        ContainerNav.page = page
        ContainerNav.autorun = autorun
        screen = Screen.CONTAINERS
    }

    /** v5.0：容器管理器页内返回（返回上一页或主页）。 */
    fun closeContainers() {
        screen = Screen.HOME
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
