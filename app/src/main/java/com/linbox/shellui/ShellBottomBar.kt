package com.linbox.shellui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.shell.ShellController
import com.linbox.core.shell.ShellController.Screen
import com.linbox.core.theme.LocalWinTheme

/**
 * 底部导航：[容器] [高级]。
 *
 * 原 LinBox 底部"我的"按钮改为"高级"（用户需求）：内容为终端、浏览器、
 * 容器的高级设置等入口（见 [AdvancedScreen]）。
 */
@Composable
fun ShellBottomBar(current: Screen) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.windowTitleBarColor)
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomNavItem(
            label = "容器",
            emoji = "📦",
            selected = current == Screen.HOME,
            modifier = Modifier.weight(1f)
        ) { ShellController.showHome() }
        BottomNavItem(
            label = "高级",
            emoji = "⚙️",
            selected = current == Screen.ADVANCED,
            modifier = Modifier.weight(1f)
        ) { ShellController.showAdvanced() }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    emoji: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
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
                    if (selected) theme.accentColor.copy(alpha = 0.25f)
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
            color = if (selected) theme.accentColor else theme.windowTitleBarTextColor.copy(alpha = 0.7f)
        )
    }
}
