package com.linbox.core.window

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 应用定义：注册到 AppRegistry 中，桌面/开始菜单/任务栏从中读取。
 *
 * @property id            唯一ID，例如 "browser" / "notepad"
 * @property displayName   显示名称（中文）
 * @property iconAsset     assets 中的图标路径（PNG/SVG）
 * @property launchMode    启动模式
 * @property defaultWidth  浮动模式默认宽度
 * @property defaultHeight 浮动模式默认高度
 * @property pinnedToTaskbar  是否固定到任务栏
 * @property pinnedToDesktop  是否在桌面显示图标
 * @property onTitleBarLongPress  标题栏长按回调（v2.22.4 fix11c：X11 用它
 *                                弹出"X11 设置"面板；null = 无长按行为）
 * @property onMenuButton         标题栏菜单按钮回调（v3.1.7：定义即在
 *                                最小化按钮左侧显示菜单按钮；X11 桌面
 *                                用它调出 X11 设置菜单，替代难发现的
 *                                长按手势）
 * @property onToolbarToggleButton 标题栏工具条开关按钮回调（v3.4.6：定义
 *                                即在菜单按钮旁边显示一个上/下箭头按钮）。
 *                                X11 桌面用它切换底部控制条（分辨率/切窗/
 *                                键盘栏）的隐藏态 —— 旧入口是画面顶部中央
 *                                的悬浮玻璃片（v3.4.5），既遮挡游戏画面，
 *                                其 ⌃/⌄ 文本字符又不在 Android 字体内（渲染
 *                                成口口方块）。归位到标题栏后：图标全部
 *                                自绘矢量，不再依赖字符字形。
 * @property toolbarToggleActive   工具条开关按钮激活态（true = 底部控制条
 *                                已隐藏，箭头朝下提示可展开）。在组合期间
 *                                读取可观察状态（如 X11UiPrefs.toolbarHidden）
 *                                以驱动按钮图标翻转与重绘。
 * @property content       该应用的 Composable 内容，接收 WindowContentScope 参数
 */
data class AppDef(
    val id: String,
    val displayName: String,
    val iconAsset: String,
    val launchMode: LaunchMode,
    val defaultWidth: Dp = 720.dp,
    val defaultHeight: Dp = 520.dp,
    val pinnedToTaskbar: Boolean = false,
    val pinnedToDesktop: Boolean = true,
    val onTitleBarLongPress: ((Context) -> Unit)? = null,
    val onMenuButton: ((Context) -> Unit)? = null,
    val onToolbarToggleButton: ((Context) -> Unit)? = null,
    val toolbarToggleActive: () -> Boolean = { false },
    val content: @Composable (WindowContentScope) -> Unit
)

/**
 * 窗口内容的作用域，提供窗口状态和工具。
 */
class WindowContentScope(
    val windowState: WindowState,
    val onClose: () -> Unit,
    val onTitleChange: (String) -> Unit
)

/**
 * 应用注册表：全局单例，所有内置应用启动时注册自己。
 */
object AppRegistry {
    private val apps = mutableMapOf<String, AppDef>()

    fun register(app: AppDef) {
        apps[app.id] = app
    }

    fun get(id: String): AppDef? = apps[id]

    fun all(): List<AppDef> = apps.values.toList()

    fun desktopApps(): List<AppDef> = apps.values.filter { it.pinnedToDesktop }

    fun taskbarApps(): List<AppDef> = apps.values.filter { it.pinnedToTaskbar }
}
