package com.linbox.shellui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.apps.containers.ContainerListContent
import com.linbox.core.shell.ShellController
import com.linbox.core.shell.ShellController.Screen
import com.linbox.core.theme.LinShellTheme
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import com.linbox.core.window.WindowState

/**
 * v5.0（安卓化重制）：容器管理器全屏页。
 *
 * 用户需求："容器创建和管理为何还是老的 windows 窗口啊，改掉，改成
 * 安卓交互" —— 容器管理器不再以可拖拽缩放的浮动窗口形式展示，改为
 * 标准安卓全屏页面：
 * - 顶部安卓 app bar（返回钮 + 标题"Bionic 容器" + 容器体检钮）；
 * - 主体复用 [ContainerListContent]（容器列表 / 五选项卡配置 /
 *   高级设置 / 体检，全部功能原样保留）；
 * - 底部导航常驻（跨页切换不迷路）。
 *
 * 深链参数经 [ShellController.ContainerNav] 一次性消费（focus 选中容器 /
 * page=advanced·doctor 直达视图 / autorun 快捷方式自动运行）。
 */
@Composable
fun ContainersScreen() {
    // 深链参数：进入时一次性快照（consume 清空 pending，旋转/重组不重复触发）；
    // 体检钮可再次注入 page=doctor（state 变更 → fakeState 重建 → 深链生效）
    var launchArgs by remember { mutableStateOf(ShellController.ContainerNav.consume()) }

    // 伪造窗口状态（ContainerListContent 的 WindowContentScope 契约兼容壳：
    // 只消费 launchArgs 与 onClose，无真实窗口几何依赖）
    val fakeState = remember(launchArgs) {
        WindowState(
            appId = "wine-containers",
            title = "Bionic 容器",
            launchMode = LaunchMode.FLOATING,
            launchArgs = launchArgs
        )
    }
    val scope = remember(fakeState) {
        WindowContentScope(
            windowState = fakeState,
            onClose = { ShellController.closeContainers() },
            onTitleChange = { }
        )
    }

    // 系统返回键等效关闭本页
    androidx.activity.compose.BackHandler(enabled = true) {
        ShellController.closeContainers()
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
            // 返回钮（圆形玻璃底）
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(LinShellTheme.card)
                    .clickable { ShellController.closeContainers() },
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
                    "Bionic 容器",
                    color = LinShellTheme.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "容器列表 · 五选项卡配置 · 高级设置",
                    color = LinShellTheme.textSecondary,
                    fontSize = 11.sp
                )
            }
            // 容器体检快捷钮（注入 doctor 深链，ContainerListContent 内部消费）
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(LinShellTheme.card)
                    .clickable { launchArgs = mapOf("page" to "doctor") },
                contentAlignment = Alignment.Center
            ) {
                Text("🩺", fontSize = 18.sp)
            }
        }

        // ===== 主体（容器管理器全部功能）=====
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            ContainerListContent(scope)
        }

        // ===== 底部导航 =====
        ShellBottomBar(current = Screen.CONTAINERS)
    }
}
