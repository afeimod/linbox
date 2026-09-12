package com.linbox.apps.clock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppDef
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

val ClockApp = AppDef(
    id = "clock",
    displayName = "时钟",
    iconAsset = "app:clock",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 480.dp,
    defaultHeight = 320.dp,
    pinnedToDesktop = true
) { scope ->
    ClockContent(scope)
}

@Composable
private fun ClockContent(_scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // 大时钟
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        Text(
            text = timeFormat.format(Date(tick)),
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 56.sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.Monospace
        )

        // 日期
        val dateFormat = SimpleDateFormat("yyyy 年 MM 月 dd 日  EEEE", Locale.CHINA)
        Text(
            text = dateFormat.format(Date(tick)),
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(12.dp))

        // 世界时钟
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WorldClock("伦敦", "Europe/London", tick)
            WorldClock("纽约", "America/New_York", tick)
            WorldClock("东京", "Asia/Tokyo", tick)
        }
    }
}

@Composable
private fun WorldClock(label: String, tzId: String, tick: Long) {
    val theme = LocalWinTheme.current
    val format = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone(tzId)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = format.format(Date(tick)),
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 10.sp
        )
    }
}
