package com.linbox.shellui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.apps.containers.ContainerManager
import com.linbox.core.shell.ShellController
import com.linbox.core.shell.ShellController.Screen
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
        // v6.0：浏览器 / 文件管理器改安卓全屏页（去 Windows 窗口化）
        AdvEntry("🌐", "浏览器", "多标签 · 书签 · Flash") {
            ShellController.showBrowser()
        },
        AdvEntry("📦", "容器管理", "winlator 式列表 · 编辑表单") {
            ShellController.openContainers()
        },
        AdvEntry("⚙️", "容器高级设置", "DY/FEX/Box/Wine安装/MangoHud/生态") {
            ShellController.openContainers(page = "advanced")
        },
        AdvEntry("🩺", "容器体检", "rootfs/X11/wine/box64 探活") {
            ShellController.openContainers(page = "doctor")
        },
        AdvEntry("📁", "文件管理器", "exe 图标 · 盘符 · 压缩包") {
            ShellController.showFiles()
        },
        AdvEntry("🖼", "X11 显示器", "全屏 X11 图形页") { ShellController.showX11() },
        AdvEntry("🎮", "DAC 显示器", "winedac 直合显示（实验）") { ShellController.showDac() }
        // v5.0：「应用设置」入口移除 —— 全部设置已收编进底部导航
        //      「设置」页（用户：“把所有还有的设置都放在下方设置栏”）。
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.linbox.core.theme.LinShellTheme.bg)
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
                color = com.linbox.core.theme.LinShellTheme.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(10.dp))
            Text(
                buildString {
                    append(if (rootfsReady) "rootfs ✔" else "rootfs ✘")
                    append(" · $containerCount 容器 · $wineCount Wine")
                },
                color = com.linbox.core.theme.LinShellTheme.textSecondary,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(com.linbox.core.theme.LinShellTheme.card)
            .border(1.dp, com.linbox.core.theme.LinShellTheme.cardBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = e.onClick)
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(com.linbox.core.theme.LinShellTheme.blue.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(e.emoji, fontSize = 20.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            e.title,
            color = com.linbox.core.theme.LinShellTheme.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            e.subtitle,
            color = com.linbox.core.theme.LinShellTheme.textSecondary,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
