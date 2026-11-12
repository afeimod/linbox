package com.linbox.shellui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.shell.ShellController
import com.linbox.core.shell.ShellController.Screen

/**
 * v4.0 底部导航（按用户图片重制）：五槽位 + 中央凸起按钮。
 *
 * [主页] [游戏库] [ (＋) 容器 ] [设置] [高级]
 *
 * - 中央为蓝色渐变凸起 + 浮钮（带阴影）：打开容器管理器浮动窗口
 *   （Bionic 容器：列表 + 创建 + 五选项卡配置）；
 * - 原"我的"按钮由"高级"取代（终端 / 浏览器 / 容器高级设置，
 *   用户需求）；
 * - 主页用浅色玻璃（图 1），游戏库 / 高级用深色玻璃（图 2），
 *   由 [palette] 参数决定。
 */
@Composable
fun ShellBottomBar(
    current: Screen,
    palette: GlassPalette = GlassPalette.Dark
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bgBottom.copy(alpha = 0.92f))
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            verticalAlignment = Alignment.Bottom
        ) {
            BottomNavItem(
                label = "主页",
                emoji = "🏠",
                selected = current == Screen.HOME,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { ShellController.showHome() }

            BottomNavItem(
                label = "游戏库",
                emoji = "🎮",
                selected = current == Screen.LIBRARY,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { ShellController.showLibrary() }

            // ===== 中央 (＋) 容器 浮钮 =====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset(y = (-14).dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .shadow(10.dp, CircleShape, spotColor = palette.blue)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(palette.blue, palette.purple)))
                            .clickable {
                                openManagerWindow(context)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("＋", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "容器",
                        fontSize = 11.sp,
                        color = palette.textSecondary
                    )
                }
            }

            BottomNavItem(
                label = "设置",
                emoji = "⚙",
                selected = current == Screen.SETTINGS,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { ShellController.showSettings() }

            BottomNavItem(
                label = "高级",
                emoji = "🧰",
                selected = current == Screen.ADVANCED,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { ShellController.showAdvanced() }
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    emoji: String,
    selected: Boolean,
    palette: GlassPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (selected) palette.blue.copy(alpha = 0.16f)
                    else Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 18.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) palette.blue else palette.textSecondary
        )
        // 选中指示条
        Spacer(Modifier.height(1.dp))
        Box(
            Modifier
                .width(if (selected) 16.dp else 0.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) palette.blue else Color.Transparent)
        )
    }
}
