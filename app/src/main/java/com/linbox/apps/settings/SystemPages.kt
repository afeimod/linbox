package com.linbox.apps.settings

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.MobileFriendly
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.BuildConfig
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowManager
import com.linbox.util.SystemControl
import java.util.Date

/**
 * 系统功能窗口页（v2.13）：打印机和扫描仪 / 无线显示器（投屏）/ VPN /
 * 移动数据 / 飞行模式 / 移动热点。
 *
 * 全部为应用内独立小窗（openSettingsSection 打开），点击设置卡片不再跳转
 * 手机系统设置；系统面板只作为窗口内的兜底按钮。
 */

/** 打开一个应用内设置子窗口（v2.13：所有"跳系统"入口改为小窗） */
internal fun openSettingsSection(
    section: String,
    title: String,
    width: Int = 520,
    height: Int = 480
) {
    WindowManager.get().open(
        appId = "settings",
        title = title,
        launchMode = LaunchMode.FLOATING,
        launchArgs = mapOf("section" to section),
        initialWidth = width,
        initialHeight = height
    )
}

/** 窗口内兜底：打开系统设置面板（仅作为辅助入口，不再是主跳转） */
private fun openSysPanel(context: Context, action: String, name: String) {
    val opened = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) Toast.makeText(context, "无法打开$name", Toast.LENGTH_SHORT).show()
}

/** 页面通用说明卡 */
@Composable
internal fun InfoCard(text: String) {
    val theme = LocalWinTheme.current
    Text(
        text,
        color = theme.secondaryTextColor,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.windowBackgroundColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
    Spacer(Modifier.height(8.dp))
}

/** 窗口内操作按钮行 */
@Composable
internal fun PanelButtonRow(vararg buttons: Pair<String, () -> Unit>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        buttons.forEachIndexed { i, (label, action) ->
            if (i == 0) {
                Button(onClick = action, shape = RoundedCornerShape(6.dp)) {
                    Text(label, fontSize = 12.sp)
                }
            } else {
                OutlinedButton(onClick = action, shape = RoundedCornerShape(6.dp)) {
                    Text(label, fontSize = 12.sp)
                }
            }
        }
    }
}

// ============================================================
// 打印机和扫描仪
// ============================================================

@Composable
internal fun PrintersPage() {
    val context = LocalContext.current
    val theme = LocalWinTheme.current

    // 打印服务（反射读取隐藏 API，多数设备可读）
    val services = remember {
        runCatching {
            val pm = context.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            val m = pm.javaClass.getMethod("getPrintServices", Int::class.javaPrimitiveType)
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (m.invoke(pm, 1) as? List<Any>) ?: emptyList()
        }.getOrNull()
    }
    var jobsCount by remember {
        mutableStateOf(
            runCatching {
                val pm = context.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                pm.printJobs.size
            }.getOrDefault(0)
        )
    }

    SectionHeader("打印机和扫描仪", "打印服务、测试打印、扫描说明（应用内管理）")

    // ===== 打印服务 =====
    SettingsCard(
        icon = Icons.Default.Print,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "打印服务",
        subtitle = when {
            services == null -> "无法直接读取服务列表（系统限制）"
            services.isEmpty() -> "已启用的打印服务：无"
            else -> "已启用的打印服务：${services.size} 个"
        }
    )
    Spacer(Modifier.height(6.dp))
    if (services != null && services.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            services.take(6).forEach { s ->
                Text(
                    "· $s",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ===== 打印测试页（真实打印流程） =====
    SettingsCard(
        icon = Icons.Default.Computer,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "打印测试页",
        subtitle = "生成一页 A4 测试文档并发送到打印服务（系统打印确认界面会出现在本应用上层）",
        onClick = { printTestPage(context) }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 打印任务 =====
    SettingsCard(
        icon = Icons.Default.Print,
        iconBackgroundColor = Color(0xFF00B294),
        title = "打印任务",
        subtitle = "本应用待处理任务：$jobsCount 个",
        onClick = {
            jobsCount = runCatching {
                val pm = context.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                pm.printJobs.size
            }.getOrDefault(0)
        }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 扫描仪 =====
    SettingsCard(
        icon = Icons.Default.Scanner,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "扫描仪",
        subtitle = "Android 未提供公共扫描 API，扫描由打印服务应用提供",
        onClick = { openSysPanel(context, AndroidSettings.ACTION_PRINT_SETTINGS, "系统打印设置") }
    )
    Spacer(Modifier.height(8.dp))

    InfoCard(
        "说明：打印服务（如“默认打印服务”、厂商打印插件）由系统安装和管理。" +
            "此处展示真实的服务与任务状态；如需添加打印机或安装打印插件，可使用下方系统面板。"
    )
    PanelButtonRow(
        "系统打印服务" to { openSysPanel(context, AndroidSettings.ACTION_PRINT_SETTINGS, "系统打印设置") }
    )
}

/** 真实打印一页 A4 测试文档 */
private fun printTestPage(context: Context) {
    runCatching {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        pm.print(
            "LinBox 测试页",
            object : android.print.PrintDocumentAdapter() {

                override fun onLayout(
                    oldAttributes: android.print.PrintAttributes?,
                    newAttributes: android.print.PrintAttributes,
                    cancellationSignal: android.os.CancellationSignal?,
                    callback: android.print.PrintDocumentAdapter.LayoutResultCallback,
                    extras: android.os.Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }
                    val info = android.print.PrintDocumentInfo.Builder("linbox_test_page.pdf")
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build()
                    callback.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out android.print.PageRange>?,
                    destination: android.os.ParcelFileDescriptor,
                    cancellationSignal: android.os.CancellationSignal?,
                    callback: android.print.PrintDocumentAdapter.WriteResultCallback
                ) {
                    runCatching {
                        val doc = android.graphics.pdf.PdfDocument()
                        val page = doc.startPage(
                            android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
                        )
                        val c = page.canvas
                        c.drawColor(android.graphics.Color.WHITE)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        paint.color = android.graphics.Color.BLACK
                        paint.textSize = 28f
                        c.drawText("LinBox 打印测试页", 60f, 120f, paint)
                        paint.textSize = 16f
                        c.drawText("设备：${Build.MODEL}", 60f, 170f, paint)
                        c.drawText("Android ${Build.VERSION.RELEASE} · LinBox v${BuildConfig.VERSION_NAME}", 60f, 196f, paint)
                        c.drawText("时间：${Date()}", 60f, 222f, paint)
                        paint.textSize = 14f
                        c.drawText("本页由 LinBox 设置中心的打印测试功能生成。", 60f, 280f, paint)
                        c.drawText("能打印出这一页，说明打印服务链路工作正常。", 60f, 304f, paint)
                        doc.finishPage(page)
                        java.io.FileOutputStream(destination.fileDescriptor).use { doc.writeTo(it) }
                        doc.close()
                        callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                    }.onFailure {
                        callback.onWriteFailed(it.message)
                    }
                }
            },
            android.print.PrintAttributes.Builder()
                .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
                .build()
        )
    }.onFailure {
        Toast.makeText(context, "无法启动打印：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

// ============================================================
// 无线显示器（投屏）
// ============================================================

@Composable
internal fun WirelessDisplayPage() {
    val context = LocalContext.current
    val theme = LocalWinTheme.current

    data class RouteRow(
        val name: String,
        val description: String,
        val enabled: Boolean,
        val selected: Boolean
    )

    fun readRoutes(): List<RouteRow> = runCatching {
        val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as android.media.MediaRouter
        val routerClass = android.media.MediaRouter::class.java

        // MediaRouter.getRoutes()/getSelectedRoute() 无参版本与 RouteInfo.getName() 无参
        // 均为隐藏 API（不在公开 android.jar 中，v2.13 CI 编译失败点），统一改用反射读取；
        // getRoutes() 受限时回退到公开（已废弃）的 getRouteCount()+getRouteAt() 枚举，
        // 被选中路由不再依赖 getSelectedRoute()，改用每个路由自身的 isSelected()
        fun call(obj: Any, name: String): Any? =
            runCatching { obj.javaClass.getMethod(name).invoke(obj) }.getOrNull()

        fun nameOf(route: Any): String {
            val n = call(route, "getName")
                ?: runCatching {
                    route.javaClass.getMethod("getName", Context::class.java).invoke(route, context)
                }.getOrNull()
            return (n as? CharSequence)?.toString() ?: "未知路由"
        }

        @Suppress("UNCHECKED_CAST")
        val rawRoutes: List<Any> = runCatching {
            routerClass.getMethod("getRoutes").invoke(router) as? List<Any>
        }.getOrNull() ?: runCatching {
            val at = routerClass.getMethod("getRouteAt", Int::class.javaPrimitiveType)
            val count = routerClass.getMethod("getRouteCount").invoke(router) as? Int ?: 0
            (0 until count).mapNotNull { i -> runCatching { at.invoke(router, i) }.getOrNull() }
        }.getOrNull() ?: emptyList()

        rawRoutes.map { r ->
            RouteRow(
                name = nameOf(r),
                description = (call(r, "getDescription") as? CharSequence)?.toString() ?: "",
                enabled = call(r, "isEnabled") as? Boolean ?: false,
                selected = call(r, "isSelected") as? Boolean ?: false
            )
        }
    }.getOrDefault(emptyList())

    fun readDisplays(): List<String> = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        dm.displays.map { d ->
            val label = if (d.displayId == android.view.Display.DEFAULT_DISPLAY) "内置屏幕" else "外接显示器"
            @Suppress("DEPRECATION")
            "${d.name}（$label）"
        }
    }.getOrDefault(emptyList())

    var routes by remember { mutableStateOf(readRoutes()) }
    var displays by remember { mutableStateOf(readDisplays()) }

    SectionHeader("无线显示器", "投屏路由、可用显示器、连接（应用内管理）")

    // ===== 当前路由 =====
    val current = routes.firstOrNull { it.selected }
    SettingsCard(
        icon = Icons.Default.Cast,
        iconBackgroundColor = Color(0xFF8E8CD8),
        title = "当前输出",
        subtitle = current?.name ?: "使用设备内置屏幕"
    )
    Spacer(Modifier.height(8.dp))

    // ===== 无线路由列表 =====
    Text(
        "投屏路由（${routes.size}）",
        color = if (theme.isDark) Color.White else Color.Black,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
    )
    Spacer(Modifier.height(8.dp))
    if (routes.isEmpty()) {
        InfoCard("未发现可用投屏路由。请确认接收端（电视/投屏器）已开启，且与本机在同一网络。")
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            routes.take(8).forEach { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.cardBackgroundColor)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            r.name,
                            color = if (theme.isDark) Color.White else Color.Black,
                            fontSize = 12.sp,
                            fontWeight = if (r.selected) FontWeight.Medium else FontWeight.Normal
                        )
                        if (r.description.isNotEmpty()) {
                            Text(r.description, color = theme.secondaryTextColor, fontSize = 10.sp)
                        }
                    }
                    if (r.selected) {
                        Text("当前", color = theme.accentColor, fontSize = 11.sp)
                    } else if (r.enabled) {
                        Text("可用", color = theme.secondaryTextColor, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ===== 物理显示器 =====
    if (displays.isNotEmpty()) {
        SettingsCard(
            icon = Icons.Default.Computer,
            iconBackgroundColor = Color(0xFF0078D7),
            title = "系统显示器（${displays.size}）",
            subtitle = displays.joinToString(" · ")
        )
        Spacer(Modifier.height(8.dp))
    }

    // ===== 刷新 =====
    SettingsCard(
        icon = Icons.Default.Refresh,
        iconBackgroundColor = Color(0xFF00B294),
        title = "刷新列表",
        subtitle = "重新扫描投屏路由与显示器",
        onClick = {
            routes = readRoutes()
            displays = readDisplays()
        }
    )
    Spacer(Modifier.height(8.dp))

    InfoCard(
        "说明：投屏路由来自系统 MediaRouter（真实数据）。第三方应用选择远程路由可能被系统拒绝，" +
            "被拒绝时可使用下方系统投屏面板完成连接。"
    )
    PanelButtonRow(
        "系统投屏面板" to { openSysPanel(context, AndroidSettings.ACTION_CAST_SETTINGS, "投屏设置") }
    )
}

// ============================================================
// VPN
// ============================================================

@Composable
internal fun VpnPage() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(SystemControl.readVpnStatus(context)) }

    SectionHeader("VPN", "VPN 状态、始终开启配置（应用内查看）")

    SettingsCard(
        icon = Icons.Default.VpnKey,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "VPN 连接",
        subtitle = if (status.active) "已连接 · 当前网络走 VPN 传输层" else "未连接 · 当前网络未使用 VPN"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Router,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "始终开启的 VPN",
        subtitle = when {
            status.alwaysOnApp != null && status.lockdown -> "已配置（${status.alwaysOnApp}）· 已开启阻断无 VPN 网络"
            status.alwaysOnApp != null -> "已配置（${status.alwaysOnApp}）"
            else -> "未配置"
        }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Refresh,
        iconBackgroundColor = Color(0xFF00B294),
        title = "刷新状态",
        subtitle = "重新检测当前网络与 VPN 配置",
        onClick = { status = SystemControl.readVpnStatus(context) }
    )
    Spacer(Modifier.height(8.dp))

    InfoCard(
        "说明：VPN 状态为系统真实数据（ConnectivityManager 传输层检测 + 安全设置读取）。" +
            "VPN 配置与开关由系统管理，点击下方按钮进入系统 VPN 配置。"
    )
    PanelButtonRow(
        "系统 VPN 配置" to { openSysPanel(context, AndroidSettings.ACTION_VPN_SETTINGS, "VPN 设置") }
    )
}

// ============================================================
// 移动数据
// ============================================================

@Composable
internal fun MobileDataPage() {
    val context = LocalContext.current

    var mobileOn by remember { mutableStateOf(SystemControl.isMobileData(context)) }
    var rxMobile by remember { mutableStateOf(TrafficStats.getMobileRxBytes().coerceAtLeast(0L)) }
    var txMobile by remember { mutableStateOf(TrafficStats.getMobileTxBytes().coerceAtLeast(0L)) }
    var rxAll by remember { mutableStateOf(TrafficStats.getTotalRxBytes().coerceAtLeast(0L)) }
    var txAll by remember { mutableStateOf(TrafficStats.getTotalTxBytes().coerceAtLeast(0L)) }

    fun refresh() {
        mobileOn = SystemControl.isMobileData(context)
        rxMobile = TrafficStats.getMobileRxBytes().coerceAtLeast(0L)
        txMobile = TrafficStats.getMobileTxBytes().coerceAtLeast(0L)
        rxAll = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        txAll = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
    }

    fun trySwitch(want: Boolean) {
        val ok = SystemControl.setMobileData(context, want)
        if (ok) {
            refresh()
            Toast.makeText(
                context,
                if (SystemControl.isMobileData(context) == want) "已${if (want) "开启" else "关闭"}移动数据"
                else "系统已拦截本次切换",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                context,
                "需要“修改系统设置”权限且部分 ROM 限制切换，请使用系统流量设置",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    SectionHeader("移动数据", "开关、流量统计（应用内管理）")

    SettingsCard(
        icon = Icons.Default.NetworkCell,
        iconBackgroundColor = Color(0xFF00B294),
        title = "移动数据",
        subtitle = if (mobileOn) "已开启" else "已关闭",
        trailingContent = { ToggleSwitch(mobileOn) { trySwitch(!mobileOn) } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.DataUsage,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "流量统计（自开机起）",
        subtitle = "移动：↓${SystemControl.formatBytes(rxMobile)} ↑${SystemControl.formatBytes(txMobile)} · " +
            "全部：↓${SystemControl.formatBytes(rxAll)} ↑${SystemControl.formatBytes(txAll)}"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Refresh,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "刷新",
        subtitle = "重新读取移动数据开关状态与流量计数",
        onClick = { refresh() }
    )
    Spacer(Modifier.height(8.dp))

    InfoCard(
        "说明：开关尝试直接修改系统移动数据设置（需“修改系统设置”授权，部分 ROM 仅允许系统应用切换）；" +
            "流量统计来自系统 TrafficStats，为真实数据。"
    )
    PanelButtonRow(
        "系统流量设置" to { openSysPanel(context, AndroidSettings.ACTION_DATA_USAGE_SETTINGS, "流量使用设置") }
    )
}

// ============================================================
// 飞行模式
// ============================================================

@Composable
internal fun AirplanePage() {
    val context = LocalContext.current
    var airplaneOn by remember { mutableStateOf(SystemControl.isAirplaneMode(context)) }

    fun trySwitch(want: Boolean) {
        val ok = SystemControl.setAirplaneMode(context, want)
        airplaneOn = SystemControl.isAirplaneMode(context)
        if (ok && airplaneOn == want) {
            Toast.makeText(
                context, if (want) "已开启飞行模式" else "已关闭飞行模式", Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                context, "系统限制切换，请使用系统飞行模式面板", Toast.LENGTH_LONG
            ).show()
        }
    }

    SectionHeader("飞行模式", "无线开关（应用内管理）")

    SettingsCard(
        icon = Icons.Default.AirplanemodeActive,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "飞行模式",
        subtitle = if (airplaneOn) "已开启 · 移动网络/蓝牙/Wi-Fi 无线关闭" else "已关闭",
        trailingContent = { ToggleSwitch(airplaneOn) { trySwitch(!airplaneOn) } }
    )
    Spacer(Modifier.height(8.dp))

    InfoCard(
        "说明：状态为系统真实读取。第三方应用切换飞行模式在 Android 4.2 后受系统限制，" +
            "本页会尝试切换并回读真实结果；被拦截时可用下方系统面板完成切换。"
    )
    PanelButtonRow(
        "系统飞行模式" to { openSysPanel(context, AndroidSettings.ACTION_AIRPLANE_MODE_SETTINGS, "飞行模式设置") }
    )
}

// ============================================================
// 移动热点
// ============================================================

@Composable
internal fun HotspotPage() {
    val context = LocalContext.current
    var hotspot by remember { mutableStateOf(SystemControl.hotspotEnabled(context)) }

    SectionHeader("移动热点", "热点状态（应用内查看）")

    SettingsCard(
        icon = Icons.Default.WifiTethering,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "热点状态",
        subtitle = when (hotspot) {
            true -> "已开启"
            false -> "已关闭"
            null -> "系统限制，无法直接读取（多数新系统如此）"
        }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.MobileFriendly,
        iconBackgroundColor = Color(0xFF00B294),
        title = "热点说明",
        subtitle = "热点开关属系统级权限（需系统应用），此处提供系统热点面板入口"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Refresh,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "重新读取",
        subtitle = "尝试再次读取热点状态",
        onClick = { hotspot = SystemControl.hotspotEnabled(context) }
    )
    Spacer(Modifier.height(8.dp))

    InfoCard(
        "说明：热点状态通过系统 Wi-Fi 接口读取（部分系统隐藏该接口时显示“无法读取”）。" +
            "开启/关闭热点请使用下方系统热点面板。"
    )
    PanelButtonRow(
        "系统热点面板" to { openSysPanel(context, AndroidSettings.ACTION_WIRELESS_SETTINGS, "网络设置") }
    )
}
