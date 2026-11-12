package com.linbox.shellui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.apps.containers.ContainerManager
import com.linbox.core.shell.ShellController
import com.linbox.core.shell.ShellController.Screen
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 高级页（底部导航"高级"，原"我的"按钮改造）。
 *
 * 汇集 LinBox 的全部高级入口：
 * - 🖥 终端（真实 Termux，linbox 自有）
 * - 🌐 浏览器（AnWind 移植：多标签 / 书签 / Flash）
 * - 📦 容器管理器（Bionic 容器：列表 + 五选项卡配置，窗口形式）
 * - ⚙ 容器高级设置（DY / FEX / Box64 / Wine 安装 / MangoHud / 生态配置）
 * - 🩺 容器体检（doctor：rootfs / X11 / wine / box64 / FEX 探活）
 * - 📁 文件管理器（exe 图标识别 / 容器盘符浏览 / 压缩包）
 * - 🖼 X11 显示器（linbox 自有全屏 X11 页）
 * - 🎮 DAC 显示器（winedac.drv 直合显示栈）
 * - 🎨 应用设置（显示 / 输入 / 手柄 / 关于）
 */
@Composable
fun AdvancedScreen() {
    val context = LocalContext.current
    val theme = LocalWinTheme.current

    // 概览状态
    var tick by remember { mutableIntStateOf(0) }
    var rootfsReady by remember { mutableStateOf(true) }
    var containerCount by remember { mutableIntStateOf(0) }
    var wineCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            rootfsReady = ContainerManager.rootfsReady()
            containerCount = ContainerManager.list().size
            wineCount = ContainerManager.listWines().size
        }
    }

    val entries = listOf(
        AdvEntry("🖥", "终端", "Termux 真实终端（pkg/apt）") { ShellController.showTerminal() },
        AdvEntry("🌐", "浏览器", "多标签 · 书签 · Flash") {
            WindowManager.get().openOrRestore(
                appId = "browser",
                title = "浏览器",
                launchMode = LaunchMode.FLOATING
            )
        },
        AdvEntry("📦", "容器管理器", "容器列表 · 五选项卡配置") {
            openManagerWindow(context)
        },
        AdvEntry("⚙️", "容器高级设置", "DY/FEX/Box/Wine安装/MangoHud/生态") {
            openManagerWindow(context, page = "advanced")
        },
        AdvEntry("🩺", "容器体检", "rootfs/X11/wine/box64 探活") {
            openManagerWindow(context, page = "doctor")
        },
        AdvEntry("📁", "文件管理器", "exe 图标 · 盘符 · 压缩包") {
            WindowManager.get().openOrRestore(
                appId = "file_explorer",
                title = "此电脑",
                launchMode = LaunchMode.FLOATING
            )
        },
        AdvEntry("🖼", "X11 显示器", "全屏 X11 图形页") { ShellController.showX11() },
        AdvEntry("🎮", "DAC 显示器", "winedac 直合显示（实验）") { ShellController.showDac() },
        AdvEntry("🎨", "应用设置", "显示/输入/手柄/关于") { ShellController.showSettings() }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
    ) {
        // 标题 + 状态概览
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "高级",
                color = theme.windowTitleBarTextColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(10.dp))
            Text(
                buildString {
                    append(if (rootfsReady) "rootfs ✔" else "rootfs ✘")
                    append(" · $containerCount 容器 · $wineCount Wine")
                },
                color = theme.windowTitleBarTextColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // 入口网格
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
        ) {
            items(entries, key = { it.title }) { e ->
                AdvCard(e)
            }
        }

        ShellBottomBar(current = Screen.ADVANCED)
    }
}

private data class AdvEntry(
    val emoji: String,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

@Composable
private fun AdvCard(e: AdvEntry) {
    val theme = LocalWinTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(theme.windowBackgroundColor)
            .clickable(onClick = e.onClick)
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(theme.accentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(e.emoji, fontSize = 20.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            e.title,
            color = theme.windowTitleBarTextColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            e.subtitle,
            color = theme.windowTitleBarTextColor.copy(alpha = 0.55f),
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
