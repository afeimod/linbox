package com.linbox.shellui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.linbox.apps.browser.BrowserContent
import com.linbox.apps.containers.ExeRunChooserContent
import com.linbox.apps.filemanager.FileExplorerContent
import com.linbox.core.shell.ShellController
import com.linbox.core.theme.LinShellTheme
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import com.linbox.core.window.WindowState

/**
 * v6.0 安卓全屏页（去 Windows 窗口化重制）。
 *
 * 用户诉求："好多窗口还是 windows 类窗口，比如浏览器等，好多都是没有
 * 进行安卓交互" —— 浏览器 / 文件管理器 / exe 选择器不再以可拖拽缩放的
 * 仿 Windows 浮动窗口（WindowChrome 标题栏 + 最小化/关闭按钮）展示，
 * 改为标准安卓全屏页面：
 *
 * - 顶部安卓 app bar（返回钮 + 标题）—— 浏览器例外：其自带标签栏 +
 *   地址工具栏已是完整 Android 浏览器交互（仅去掉窗口框即可）；
 * - 内容 100% 复用原有实现（BrowserContent / FileExplorerContent /
 *   ExeRunChooserContent，功能零删减）—— 通过伪 WindowContentScope
 *   壳提供 launchArgs / onClose 契约（与 ContainersScreen 同一模式）；
 * - 系统返回键 = 退出本页回上一页（exe 选择器返回 = 取消选择）。
 *
 * 深链参数经 [ShellController.PageNav] 一次性消费（url / pickMode /
 * targetApp / targetWindow / path）。
 */

/** 深链参数快照 + 伪窗口契约壳（内容组件只依赖 launchArgs 与 onClose）。 */
private class PageHost(
    val args: Map<String, String>,
    val appId: String,
    val title: String,
    val onExit: () -> Unit
) {
    val state = WindowState(
        appId = appId,
        title = title,
        launchMode = LaunchMode.FLOATING,
        launchArgs = args
    )

    val scope = WindowContentScope(
        windowState = state,
        onClose = { onExit() },
        onTitleChange = { }
    )
}

@Composable
private fun rememberPageHost(appId: String, title: String, exitTo: () -> Unit): PageHost {
    val args = remember { ShellController.PageNav.consume() }
    return remember(appId, args) { PageHost(args, appId, title, exitTo) }
}

// ===========================================================================
// 浏览器（安卓全屏页）
// ===========================================================================

/**
 * 浏览器页：自带 TabBar + 地址工具栏 + 书签/历史侧板，全屏承载。
 * 返回键退出回上一页；标签会话挂 BrowserSessions 注册表（跨进出存活，
 * 再进不丢标签 —— 与旧窗口最小化语义一致）。
 */
@Composable
fun BrowserScreen() {
    val host = rememberPageHost(appId = "browser", title = "浏览器") {
        // v6.1：退出回【发起页】（不再固定回高级页）
        ShellController.closePage()
    }

    // 系统返回：退出浏览器页（内容内部的 BackHandler 优先消费：
    // 网页历史后退 > 视频全屏退出 > 页面级全屏退出 > 本兜底）
    androidx.activity.compose.BackHandler(enabled = true) {
        ShellController.closePage()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LinShellTheme.bg)
    ) {
        BrowserContent(host.scope)
    }

    // 页面退出时销毁标签会话（WebView 全量释放，防"关了浏览器还在响"）
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            com.linbox.apps.browser.BrowserSessions.destroy(host.state.id)
        }
    }
}

// ===========================================================================
// 文件管理器（安卓全屏页）
// ===========================================================================

/** 文件管理器页：顶部安卓 app bar（返回 + 标题 + 选择模式提示）。 */
@Composable
fun FilesScreen() {
    // v6.1：退出回【发起页】（游戏库弹窗选 exe / 容器编辑器选驱动…
    // 选完或取消都回到真正发起的地方）
    val host = rememberPageHost(appId = "file_explorer", title = "文件管理器") {
        ShellController.closePage()
    }
    val pickMode = host.args["pickMode"] ?: ""

    androidx.activity.compose.BackHandler(enabled = true) {
        ShellController.closePage()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LinShellTheme.bg)
    ) {
        // ===== 安卓 app bar =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(LinShellTheme.card)
                    .clickable { ShellController.closePage() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = LinShellTheme.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "文件管理器",
                    color = LinShellTheme.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when (pickMode) {
                        "exe" -> "选择一个程序文件（exe/bat/msi）"
                        "image" -> "选择一张图片"
                        "text" -> "选择一个文本文件"
                        "media" -> "选择一个媒体文件"
                        "dir" -> "浏览到目标目录后选择"
                        else -> "存储 · 容器盘符 · 压缩包"
                    },
                    color = LinShellTheme.textSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            FileExplorerContent(host.scope)
        }
    }
}

// ===========================================================================
// exe 选择器（安卓全屏页：文件管理器双击 exe 调起）
// ===========================================================================

/** exe 选择器页：选容器 + 显示设置 + 启动参数，底部大按钮运行。 */
@Composable
fun ExeChooserScreen() {
    val host = rememberPageHost(appId = "exe_run_chooser", title = "选择容器运行") {
        ShellController.closePage()
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        ShellController.closePage()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LinShellTheme.bg)
    ) {
        // ===== 安卓 app bar =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(LinShellTheme.card)
                    .clickable { ShellController.closePage() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = LinShellTheme.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "运行程序",
                    color = LinShellTheme.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "选择容器运行 · 显示设置 · 启动参数",
                    color = LinShellTheme.textSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            ExeRunChooserContent(host.scope)
        }
    }
}
