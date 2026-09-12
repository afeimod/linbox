package com.linbox.apps.sysinfo

import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppDef
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import java.io.File

val SysInfoApp = AppDef(
    id = "sysinfo",
    displayName = "系统信息",
    iconAsset = "app:sysinfo",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 480.dp,
    defaultHeight = 560.dp,
    pinnedToDesktop = true
) { scope ->
    SysInfoContent(scope)
}

@Composable
private fun SysInfoContent(_scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
            .padding(16.dp)
    ) {
        Text(
            "系统信息",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        val sections = listOf(
            "系统概览" to listOf(
                "操作系统" to "LinBox ${theme.displayName}",
                "Android 版本" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "安全补丁" to getSecurityPatch(),
                "构建编号" to Build.DISPLAY
            ),
            "硬件信息" to listOf(
                "制造商" to Build.MANUFACTURER,
                "品牌" to Build.BRAND,
                "型号" to Build.MODEL,
                "设备" to Build.DEVICE,
                "主板" to Build.BOARD,
                "处理器架构" to Build.SUPPORTED_ABIS.joinToString(", ")
            ),
            "运行时" to listOf(
                "可用处理器" to "${Runtime.getRuntime().availableProcessors()} 核",
                "JVM 最大内存" to "${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB",
                "JVM 已用内存" to "${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024} MB",
                "JVM 空闲内存" to "${Runtime.getRuntime().freeMemory() / 1024 / 1024} MB"
            ),
            "存储" to listOf(
                "内部存储总容量" to formatSize(getInternalStorageTotal()),
                "内部存储可用" to formatSize(getInternalStorageAvailable()),
                "应用数据目录" to context.filesDir.absolutePath
            ),
            "LinBox" to listOf(
                "版本" to "1.0.0",
                "包名" to "com.linbox",
                "当前主题" to theme.displayName,
                "默认浏览器主页" to "必应 (bing.com)"
            )
        )

        sections.forEach { (sectionTitle, items) ->
            SectionHeader(sectionTitle)
            items.forEach { (label, value) ->
                InfoRow(label, value)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val theme = LocalWinTheme.current
    Text(
        title,
        color = theme.accentColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    HorizontalDivider()
}

@Composable
private fun InfoRow(label: String, value: String) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp
        )
        Text(
            value,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun getInternalStorageTotal(): Long {
    return try {
        val stat = StatFs(File("/").absolutePath)
        stat.totalBytes
    } catch (_: Exception) { 0L }
}

private fun getInternalStorageAvailable(): Long {
    return try {
        val stat = StatFs(File("/").absolutePath)
        stat.availableBytes
    } catch (_: Exception) { 0L }
}

/**
 * 获取安全补丁日期（API 23+ 才有 Build.VERSION.SECURITY_PATCH）
 */
private fun getSecurityPatch(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Build.VERSION.SECURITY_PATCH
    } else {
        "unknown"
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return String.format("%.2f %s", v, units[i])
}
