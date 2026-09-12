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
