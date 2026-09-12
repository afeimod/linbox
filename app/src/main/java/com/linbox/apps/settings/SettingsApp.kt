package com.linbox.apps.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linbox.LinBoxApp
import com.linbox.BuildConfig
import com.linbox.core.desktop.IconPainter
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.input.keyboardAware
import com.linbox.core.theme.Themes
import com.linbox.core.theme.WindowsVariant
import com.linbox.core.window.AppDef
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowManager
import com.linbox.core.window.WindowContentScope
import com.linbox.util.ImmersiveMode
import com.linbox.util.L
import com.linbox.util.L10n
import com.linbox.util.SystemControl
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.io.File

val SettingsApp = AppDef(
    id = "settings",
    displayName = "设置",
    iconAsset = "app:settings",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 880.dp,
    defaultHeight = 600.dp,
    pinnedToTaskbar = true,
    pinnedToDesktop = true
) { scope ->
    SettingsContent(scope)
}

/** 打开系统设置面板（v2.9：真实逻辑，失败时 Toast 提示） */
private fun openSystemPanel(
    context: Context,
    action: String,
    name: String,
    fallbackAction: String? = null
) {
    val opened = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) {
        // 设备不支持该 action（如部分定制 ROM）时，回退到备用设置页
        val fallbackOpened = fallbackAction?.let { fb ->
            runCatching {
                context.startActivity(Intent(fb).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        } == true
        if (!fallbackOpened) {
            Toast.makeText(context, "无法打开$name", Toast.LENGTH_SHORT).show()
        }
    }
}

/** 应用真实屏幕亮度（对本应用窗口生效，v2.9） */
private fun applyWindowBrightness(context: Context, value: Float) {
    val activity = ImmersiveMode.findActivity(context) ?: return
    runCatching {
        val lp = activity.window.attributes
        lp.screenBrightness = value.coerceIn(0.05f, 1f)
        activity.window.attributes = lp
    }
}

/**
 * 设置中心 - Win11 风格重构
 *
 * 左侧：垂直导航栏（系统、蓝牙和设备、个性化、应用、账户、时间和语言、
 *        隐私和安全、Windows 更新、关于）
 * 右侧：内容区，垂直滚动，每项以卡片形式展示
 */
@Composable
private fun SettingsContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    var activeSection by remember {
        mutableStateOf(scope.windowState.launchArgs["section"] ?: "system")
    }
    // 设置搜索（v2.9：真实过滤导航项）
    var searchText by remember { mutableStateOf("") }

    Row(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {

        // ===== 左侧导航栏（Win11 风格） =====
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(theme.cardBackgroundColor)
                .padding(12.dp)
        ) {
            // 顶部标题 + 应用图标
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp, top = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "设置",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 搜索框（v2.9：输入关键字实时过滤设置分类）
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("查找设置", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(14.dp)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .keyboardAware(
                        value = { searchText },
                        onValue = { searchText = it },
                        singleLine = true
                    ),
                shape = RoundedCornerShape(6.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
            )

            // 导航项列表（按搜索关键字过滤）；v2.17："账户"替换为"桌面设置"
            val allNavItems = remember {
                listOf(
                    Triple("system", "系统", Icons.Default.Computer),
                    Triple("bluetooth", "蓝牙和设备", Icons.Default.Bluetooth),
                    Triple("network", "网络和 Internet", Icons.Default.Wifi),
                    Triple("personalization", "个性化", Icons.Default.Palette),
                    Triple("apps", "应用", Icons.Default.Apps),
                    Triple("desktop", "桌面设置", Icons.Default.DesktopWindows),
                    Triple("time", "时间和语言", Icons.Default.Schedule),
                    Triple("gaming", "游戏", Icons.Default.SportsEsports),
                    Triple("accessibility", "辅助功能", Icons.Default.Accessibility),
                    Triple("privacy", "隐私和安全", Icons.Default.Security),
                    Triple("update", "Windows 更新", Icons.Default.Update),
                    Triple("about", "关于", Icons.Default.Info)
                )
            }
            val keyword = searchText.trim()
            val visibleNavItems = if (keyword.isEmpty()) allNavItems
                else allNavItems.filter { it.second.contains(keyword, ignoreCase = true) }

            if (keyword.isNotEmpty() && visibleNavItems.isEmpty()) {
                Text(
                    "没有找到与 \"$keyword\" 相关的设置",
                    color = theme.secondaryTextColor,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                )
            }

            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                visibleNavItems.forEach { (id, label, icon) ->
                    SettingsNavItem(L(label), icon, active = activeSection == id) { activeSection = id }
                }
            }
        }

        // ===== 右侧内容区 =====
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (activeSection) {
                    "system" -> SystemSection()
                    "bluetooth" -> BluetoothDevicesSection()
                    "network" -> NetworkInternetSection()
                    "personalization" -> PersonalizationSection()
                    "apps" -> AppsSection()
                    // v2.17："账户"替换为桌面设置（不再跳转手机系统设置）
                    "desktop" -> DesktopSettingsSection()
                    "time" -> TimeLanguageSection()
                    "gaming" -> GamingSection()
                    "accessibility" -> AccessibilitySection()
                    "privacy" -> PrivacySection()
                    "update" -> WindowsUpdateSection()
                    "about" -> AboutSection()
                    // v2.13：应用内系统功能小窗（点击设置卡片以新窗口打开）
                    "printers" -> PrintersPage()
                    "cast" -> WirelessDisplayPage()
                    "vpn" -> VpnPage()
                    "mobile_data" -> MobileDataPage()
                    "airplane" -> AirplanePage()
                    "hotspot" -> HotspotPage()
                    // v2.17：锁屏设置独立小窗（设置→个性化→锁屏界面）
                    "lock_settings" -> LockSettingsPage()
                }
            }
        }
    }
}

/**
 * Win11 风格导航项：左侧图标 + 文字，选中项左侧有彩色指示条
 */
@Composable
private fun SettingsNavItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .background(if (active) theme.accentColor.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) theme.accentColor else (if (theme.isDark) Color.White else Color.Black),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (active) theme.accentColor else (if (theme.isDark) Color.White else Color.Black),
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
        )
    }
}

// === 内容区头部组件 ===

@Composable
internal fun SectionHeader(title: String, description: String? = null) {
    val theme = LocalWinTheme.current
    Text(
        L(title),
        color = if (theme.isDark) Color.White else Color.Black,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold
    )
    if (description != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            L(description),
            color = theme.secondaryTextColor,
            fontSize = 12.sp
        )
    }
    Spacer(Modifier.height(20.dp))
}

/**
 * Win11 风格设置卡片：行布局，左侧图标 + 标题/副标题，右侧控件
 */
@Composable
internal fun SettingsCard(
    icon: ImageVector,
    iconBackgroundColor: Color,
    title: String,
    subtitle: String,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(iconBackgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
        if (trailingContent != null) {
            trailingContent()
        }
    }
}

@Composable
internal fun ToggleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onCheckedChange)
}

// === 各内容区域 ===

@Composable
private fun SystemSection() {
    val theme = LocalWinTheme.current
    val app = LinBoxApp.get()
    val context = LocalContext.current
    val scope0 = rememberCoroutineScope()
    val showSeconds by app.settingsStore.showSeconds.collectAsState(initial = false)
    val taskbarAutohide by app.settingsStore.taskbarAutohide.collectAsState(initial = false)
    val notificationsEnabled by app.settingsStore.notificationsEnabled.collectAsState(initial = true)
    val doNotDisturb by app.settingsStore.doNotDisturb.collectAsState(initial = false)

    // v2.12：真实系统状态（电池 / 省电模式）
    val battery = remember { SystemControl.readBattery(context) }
    var powerSaverReal by remember { mutableStateOf(SystemControl.isPowerSaveMode(context)) }

    // v2.12：应用内显示设置子页 + 系统信息弹窗
    var showDisplayPage by remember { mutableStateOf(false) }
    var showDeviceInfo by remember { mutableStateOf(false) }
    if (showDisplayPage) {
        DisplaySettingsPage(onBack = { showDisplayPage = false })
        return
    }

    // 音量控制（真实 AudioManager STREAM_MUSIC，v2.9）
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember {
        mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    // 多任务处理：关闭全部窗口的确认对话框
    var showCloseAllDialog by remember { mutableStateOf(false) }
    val wm = remember { WindowManager.get() }
    var wmTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { wm.observe { wmTick++ } }
    val openWindowCount = remember(wmTick) { wm.windows.size }

    SectionHeader("系统", "显示、声音、通知、电源")

    // LinBox 设备（真实设备信息，点击查看系统信息）
    SettingsCard(
        icon = Icons.Default.Computer,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "LinBox 设备",
        subtitle = buildString {
            append(Build.MODEL ?: "Android 设备")
            append(" · Android ${Build.VERSION.RELEASE}")
            if (battery.percent >= 0) {
                append(" · 电量 ${battery.percent}%")
                if (battery.charging) append(" · 充电中")
            }
        },
        onClick = { showDeviceInfo = true }
    )
    if (showDeviceInfo) {
        SystemInfoDialog(onDismiss = { showDeviceInfo = false })
    }
    Spacer(Modifier.height(8.dp))

    // 显示（v2.12：应用内显示设置子页，不再跳转手机系统设置）
    SettingsCard(
        icon = Icons.Default.BrightnessMedium,
        iconBackgroundColor = Color(0xFF0067C0),
        title = "显示",
        subtitle = "缩放、亮度、图标大小、方向、任务栏",
        onClick = { showDisplayPage = true }
    )
    Spacer(Modifier.height(8.dp))

    // 亮度（v2.12：真实系统亮度，未授权时回退窗口亮度）
    BrightnessSliderCard()
    Spacer(Modifier.height(8.dp))

    // 声音（展开真实音量滑块，v2.9）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("声音", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text("媒体音量（直接控制系统音量）", color = theme.secondaryTextColor, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = theme.secondaryTextColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = currentVolume.toFloat(),
                    onValueChange = { v ->
                        val vol = v.roundToInt().coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                        currentVolume = vol
                    },
                    valueRange = 0f..maxVolume.toFloat(),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("$currentVolume/$maxVolume", color = theme.secondaryTextColor, fontSize = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // 通知
    SettingsCard(
        icon = Icons.Default.Notifications,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "通知",
        subtitle = "来自应用和系统的通知提醒",
        trailingContent = {
            ToggleSwitch(notificationsEnabled) { v -> scope0.launch { app.settingsStore.setNotificationsEnabled(v) } }
        }
    )
    Spacer(Modifier.height(8.dp))

    // 免打扰
    SettingsCard(
        icon = Icons.Default.DoNotDisturbOn,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "专注助手",
        subtitle = "免打扰模式，隐藏所有通知",
        trailingContent = {
            ToggleSwitch(doNotDisturb) { v -> scope0.launch { app.settingsStore.setDoNotDisturb(v) } }
        }
    )
    Spacer(Modifier.height(8.dp))

    // 电源和电池（v2.12：真实省电模式 + 真实电量）
    SettingsCard(
        icon = Icons.Default.PowerSettingsNew,
        iconBackgroundColor = Color(0xFFF7630C),
        title = "电源和电池",
        subtitle = buildString {
            if (battery.percent >= 0) {
                append("电量 ${battery.percent}%")
                append(
                    when {
                        battery.charging -> " · 充电中"
                        battery.full -> " · 已充满"
                        else -> ""
                    }
                )
                append(" · ")
            }
            append(if (powerSaverReal) "系统省电模式已开启" else "系统省电模式已关闭")
        },
        trailingContent = {
            ToggleSwitch(powerSaverReal) { v ->
                val ok = SystemControl.setPowerSaveMode(context, v)
                if (ok) {
                    powerSaverReal = v
                    Toast.makeText(
                        context,
                        if (v) "已开启系统省电模式" else "已关闭系统省电模式",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // 原生 Android 禁止三方直接切换省电模式 → 引导系统省电设置页
                    Toast.makeText(
                        context,
                        "此设备需要系统权限，已打开系统省电设置",
                        Toast.LENGTH_SHORT
                    ).show()
                    openSystemPanel(context, AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS, "省电模式设置")
                }
                scope0.launch { app.settingsStore.setPowerSaver(v) }
            }
        }
    )
    Spacer(Modifier.height(8.dp))

    // 存储（打开内置文件资源管理器，v2.9）
    SettingsCard(
        icon = Icons.Default.Storage,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "存储",
        subtitle = "存储空间 · 驱动器 · 配置规则",
        onClick = {
            WindowManager.get().open(
                appId = "file_explorer",
                title = "文件资源管理器",
                launchMode = LaunchMode.FLOATING,
                initialWidth = 920,
                initialHeight = 620
            )
        }
    )
    Spacer(Modifier.height(8.dp))

    // 多任务处理（一键关闭所有窗口，v2.9）
    if (showCloseAllDialog) {
        AlertDialog(
            onDismissRequest = { showCloseAllDialog = false },
            title = { Text("关闭所有窗口") },
            text = { Text("确定要关闭当前打开的 $openWindowCount 个窗口吗？未保存的内容将丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    wm.closeAll()
                    showCloseAllDialog = false
                }) { Text("全部关闭") }
            },
            dismissButton = {
                TextButton(onClick = { showCloseAllDialog = false }) { Text("取消") }
            }
        )
    }
    SettingsCard(
        icon = Icons.Default.Tab,
        iconBackgroundColor = Color(0xFF8E8CD8),
        title = "多任务处理",
        subtitle = "当前 $openWindowCount 个窗口 · 点击一键关闭全部窗口",
        onClick = { showCloseAllDialog = true }
    )
    Spacer(Modifier.height(8.dp))

    // 时钟显示秒（图标大小/缩放/方向已移入“显示”子页）
    SettingsCard(
        icon = Icons.Default.Schedule,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "时钟显示秒",
        subtitle = "在任务栏时钟中显示秒数",
        trailingContent = { ToggleSwitch(showSeconds) { v -> scope0.launch { app.settingsStore.setShowSeconds(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    // 任务栏自动隐藏
    SettingsCard(
        icon = Icons.Default.ViewDay,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "任务栏自动隐藏",
        subtitle = "指针移到底部边缘时任务栏自动出现",
        trailingContent = { ToggleSwitch(taskbarAutohide) { v -> scope0.launch { app.settingsStore.setTaskbarAutohide(v) } } }
    )
}

/**
 * 应用内显示设置子页（v2.12）。
 * 集合桌面全部显示相关设置：亮度（真实系统亮度）、UI 缩放、图标大小、
 * 显示方向、任务栏高度、占用刘海屏、显示器信息。不再跳转手机系统设置。
 */
@Composable
private fun DisplaySettingsPage(onBack: () -> Unit) {
    val theme = LocalWinTheme.current
    val app = LinBoxApp.get()
    val context = LocalContext.current
    val scope0 = rememberCoroutineScope()
    val iconSize by app.settingsStore.iconSize.collectAsState(initial = 48f)
    val uiScale by app.settingsStore.uiScale.collectAsState(initial = 1f)
    val orientation by app.settingsStore.displayOrientation.collectAsState(initial = "auto")
    val taskbarHeightPref by app.settingsStore.taskbarHeight.collectAsState(initial = 0f)
    val useCutout by app.settingsStore.useCutout.collectAsState(initial = true)

    // 返回 + 标题
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "返回",
                tint = if (theme.isDark) Color.White else Color.Black
            )
        }
        Column {
            Text(
                "显示",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "缩放、亮度、图标大小、方向、任务栏",
                color = theme.secondaryTextColor,
                fontSize = 12.sp
            )
        }
    }
    Spacer(Modifier.height(20.dp))

    // 显示器信息（真实分辨率/密度/刷新率）
    val displayInfo = remember { SystemControl.readDeviceInfo(context) }
    SettingsCard(
        icon = Icons.Default.Monitor,
        iconBackgroundColor = Color(0xFF0067C0),
        title = "显示器",
        subtitle = "${displayInfo.screenWidthPx} × ${displayInfo.screenHeightPx} · " +
            "${displayInfo.densityDpi}dpi · " + String.format("%.1f", displayInfo.refreshRateHz) + "Hz"
    )
    Spacer(Modifier.height(8.dp))

    // 亮度（真实系统亮度）
    BrightnessSliderCard()
    Spacer(Modifier.height(8.dp))

    // UI 缩放
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("UI 缩放", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = uiScale,
                    onValueChange = { scope0.launch { app.settingsStore.setUiScale(it) } },
                    // v2.16：范围扩到 0.6..3.0；100% 档位实际渲染为旧版 60% 效果
                    valueRange = 0.6f..3.0f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("${(uiScale * 100).roundToInt()}%", color = theme.secondaryTextColor, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "缩放整个桌面的图标、文字和窗口大小，立即生效。100% 现在为更紧凑的小尺寸布局（相当于旧版 60%），需要更大界面可调至最高 300%。",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    // 桌面图标大小
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("桌面图标大小", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = iconSize,
                    onValueChange = { scope0.launch { app.settingsStore.setIconSize(it) } },
                    valueRange = 28f..72f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("${iconSize.roundToInt()}dp", color = theme.secondaryTextColor, fontSize = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // 显示方向
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("显示方向", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption("自动", orientation == "auto") { scope0.launch { app.settingsStore.setDisplayOrientation("auto") } }
                SegmentedOption("竖屏", orientation == "portrait") { scope0.launch { app.settingsStore.setDisplayOrientation("portrait") } }
                SegmentedOption("横屏", orientation == "landscape") { scope0.launch { app.settingsStore.setDisplayOrientation("landscape") } }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // 任务栏高度
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "任务栏高度",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (taskbarHeightPref >= 36f) "${taskbarHeightPref.roundToInt()}dp" else "默认 (${theme.taskbarHeight.value.roundToInt()}dp)",
                    color = theme.secondaryTextColor,
                    fontSize = 11.sp
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { scope0.launch { app.settingsStore.setTaskbarHeight(0f) } },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("重置", fontSize = 11.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ViewDay, null, tint = theme.secondaryTextColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = if (taskbarHeightPref >= 36f) taskbarHeightPref else theme.taskbarHeight.value,
                    onValueChange = { scope0.launch { app.settingsStore.setTaskbarHeight(it) } },
                    valueRange = 36f..80f,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "调节任务栏高度，图标会随高度自动缩放；点击“重置”恢复主题默认。",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    // 占用刘海屏
    SettingsCard(
        icon = Icons.Default.Smartphone,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "占用刘海屏",
        subtitle = if (useCutout) "已开启 · 桌面延伸绘制到刘海/挖孔区域" else "关闭 · 刘海区域不显示内容",
        trailingContent = {
            ToggleSwitch(useCutout) { v -> scope0.launch { app.settingsStore.setUseCutout(v) } }
        }
    )
}

/**
 * 亮度卡片（v2.12）：真实读写系统亮度。
 * 已授予“修改系统设置”权限 → 写 Settings.System.SCREEN_BRIGHTNESS（影响整个系统）；
 * 未授权 → 回退为窗口亮度，并显示“去授权”入口。
 */
@Composable
private fun BrightnessSliderCard() {
    val theme = LocalWinTheme.current
    val app = LinBoxApp.get()
    val context = LocalContext.current
    val scope0 = rememberCoroutineScope()

    var canWrite by remember { mutableStateOf(SystemControl.canWriteSystemSettings(context)) }
    var autoMode by remember { mutableStateOf(SystemControl.isBrightnessAuto(context)) }
    var sliderValue by remember {
        val sys = SystemControl.getSystemBrightness(context)
        mutableStateOf(if (sys >= 0) sys / 255f else 0.8f)
    }

    // 从系统授权页返回时刷新权限与亮度状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canWrite = SystemControl.canWriteSystemSettings(context)
                autoMode = SystemControl.isBrightnessAuto(context)
                val sys = SystemControl.getSystemBrightness(context)
                if (sys >= 0 && !autoMode) sliderValue = sys / 255f
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "亮度",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (autoMode) "自动" else "${(sliderValue * 100).roundToInt()}%",
                    color = theme.secondaryTextColor,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BrightnessMedium, null, tint = theme.secondaryTextColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        sliderValue = it
                        val ok = SystemControl.setSystemBrightness(context, it)
                        if (!ok) applyWindowBrightness(context, it)
                        scope0.launch { app.settingsStore.setBrightness(it) }
                    },
                    valueRange = 0.05f..1.0f,
                    modifier = Modifier.weight(1f)
                )
            }
            if (!canWrite) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "未授予“修改系统设置”权限，当前仅调整本应用亮度",
                        color = theme.secondaryTextColor,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { SystemControl.openWriteSettingsPage(context) },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("去授权", fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "自动调整亮度",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                ToggleSwitch(autoMode) { v ->
                    val ok = SystemControl.setBrightnessAuto(context, v)
                    if (ok) {
                        autoMode = v
                        if (!v) {
                            val sys = SystemControl.getSystemBrightness(context)
                            if (sys >= 0) sliderValue = sys / 255f
                        }
                    } else {
                        Toast.makeText(context, "需要“修改系统设置”权限", Toast.LENGTH_SHORT).show()
                        SystemControl.openWriteSettingsPage(context)
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalizationSection() {
    val theme = LocalWinTheme.current
    val app = LinBoxApp.get()
    val wm = remember { WindowManager.get() }
    val scope0 = rememberCoroutineScope()
    var currentVariant by remember { mutableStateOf(theme.variant) }
    val customWallpaper by app.settingsStore.customWallpaper.collectAsState(initial = null)

    // ===== v2.14 个性化真实化：颜色 / 字体 / 任务栏 / 锁屏全部可操作 =====
    val colorMode by app.settingsStore.appColorMode.collectAsState(initial = "auto")
    val accent by app.settingsStore.appAccent.collectAsState(initial = "default")
    val fontScale by app.settingsStore.fontScale.collectAsState(initial = 1f)
    val fontColor by app.settingsStore.fontColor.collectAsState(initial = "auto")
    val fontStyle by app.settingsStore.fontStyle.collectAsState(initial = "default")
    val taskbarCentered by app.settingsStore.taskbarCentered.collectAsState(initial = true)
    val autohide by app.settingsStore.taskbarAutohide.collectAsState(initial = false)

    SectionHeader("个性化", "背景、颜色、主题、锁屏界面")

    // 主题选择
    Text("主题", color = if (theme.isDark) Color.White else Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Themes.all.forEach { themeOption ->
            ThemeCard(
                themeOption = themeOption,
                isSelected = currentVariant == themeOption.variant,
                onClick = {
                    currentVariant = themeOption.variant
                    scope0.launch { app.themeManager.setTheme(themeOption.variant) }
                }
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    // 背景（v2.14：壁纸改从应用内文件资源管理器选择，不再拉起手机系统文件管理器）
    // v2.18：壁纸支持图片与视频（视频 = DreamScene 风格动态桌面，静音循环播放）
    Text(L("背景"), color = if (theme.isDark) Color.White else Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    SettingsCard(
        icon = Icons.Default.Image,
        iconBackgroundColor = Color(0xFF0078D7),
        title = L("壁纸"),
        subtitle = L("从文件资源管理器选择图片或视频"),
        onClick = {
            wm.open(
                appId = "file_explorer",
                title = "选择壁纸",
                launchMode = AppRegistry.get("file_explorer")?.launchMode
                    ?: com.linbox.core.window.LaunchMode.FLOATING,
                launchArgs = mapOf("pickMode" to "wallpaper"),
                initialWidth = 920,
                initialHeight = 620
            )
        }
    )
    Spacer(Modifier.height(8.dp))

    // v2.18：视频桌面壁纸（直达 Movies 文件夹选择视频文件）
    SettingsCard(
        icon = Icons.Default.PlayCircle,
        iconBackgroundColor = Color(0xFF10893E),
        title = L("视频壁纸"),
        subtitle = L("选择视频作为动态桌面壁纸（静音循环，更耗电）"),
        onClick = {
            wm.open(
                appId = "file_explorer",
                title = "选择视频壁纸",
                launchMode = AppRegistry.get("file_explorer")?.launchMode
                    ?: com.linbox.core.window.LaunchMode.FLOATING,
                launchArgs = mapOf("pickMode" to "wallpaper_video"),
                initialWidth = 920,
                initialHeight = 620
            )
        }
    )
    Spacer(Modifier.height(8.dp))

    if (customWallpaper != null) {
        SettingsCard(
            icon = Icons.Default.Restore,
            iconBackgroundColor = Color(0xFFCA5010),
            title = L("恢复默认壁纸"),
            subtitle = L("使用主题自带的默认壁纸"),
            onClick = { scope0.launch { app.settingsStore.setCustomWallpaper(null) } }
        )
        Spacer(Modifier.height(8.dp))
    }

    // ===== 颜色（v2.14：深浅模式作用于所有 Windows 主题 + 强调色覆盖） =====
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text(
                L("颜色"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                L("深浅模式对所有 Windows 主题生效"),
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption(L("跟随主题"), colorMode == "auto") {
                    scope0.launch { app.settingsStore.setAppColorMode("auto") }
                }
                SegmentedOption(L("浅色"), colorMode == "light") {
                    scope0.launch { app.settingsStore.setAppColorMode("light") }
                }
                SegmentedOption(L("深色"), colorMode == "dark") {
                    scope0.launch { app.settingsStore.setAppColorMode("dark") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                L("强调色"),
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 主题自带（描边圆点）+ 5 个预设强调色
                AccentSwatch(
                    color = theme.accentColor,
                    selected = accent == "default",
                    outline = true,
                    onClick = { scope0.launch { app.settingsStore.setAppAccent("default") } }
                )
                listOf(
                    "#0078D7", "#10893E", "#8764B8", "#CA5010", "#C42B1C"
                ).forEach { hex ->
                    AccentSwatch(
                        color = Color(android.graphics.Color.parseColor(hex)),
                        selected = accent == hex,
                        outline = false,
                        onClick = { scope0.launch { app.settingsStore.setAppAccent(hex) } }
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // ===== 锁屏界面（v2.17：打开锁屏设置 —— 壁纸/密码/自动锁定/开关） =====
    SettingsCard(
        icon = Icons.Default.Lock,
        iconBackgroundColor = Color(0xFF00B294),
        title = L("锁屏界面"),
        subtitle = L("锁屏壁纸（图片/视频）、密码、自动锁定、开关"),
        onClick = {
            openSettingsSection("lock_settings", L10n.t("锁屏设置"), 560, 640)
        }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 字体（v2.14：大小 / 颜色 / 样式全局生效） =====
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text(
                L("字体"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    L("字体大小"),
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${(fontScale * 100).roundToInt()}%",
                    color = theme.secondaryTextColor,
                    fontSize = 11.sp
                )
            }
            Slider(
                value = fontScale,
                onValueChange = { scope0.launch { app.settingsStore.setFontScale(it) } },
                valueRange = 0.85f..1.4f,
                steps = 10
            )
            Spacer(Modifier.height(8.dp))
            Text(L("字体颜色"), color = theme.secondaryTextColor, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption(L("跟随主题色"), fontColor == "auto") {
                    scope0.launch { app.settingsStore.setFontColor("auto") }
                }
                SegmentedOption(L("白色"), fontColor == "white") {
                    scope0.launch { app.settingsStore.setFontColor("white") }
                }
                SegmentedOption(L("黑色"), fontColor == "black") {
                    scope0.launch { app.settingsStore.setFontColor("black") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(L("字体样式"), color = theme.secondaryTextColor, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption(L("无衬线（默认）"), fontStyle == "default") {
                    scope0.launch { app.settingsStore.setFontStyle("default") }
                }
                SegmentedOption(L("衬线"), fontStyle == "serif") {
                    scope0.launch { app.settingsStore.setFontStyle("serif") }
                }
                SegmentedOption(L("等宽"), fontStyle == "mono") {
                    scope0.launch { app.settingsStore.setFontStyle("mono") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                L("预览：LinBox Windows 桌面体验 AaBbCc 123"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 14.sp
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    // ===== 任务栏（v2.14：居中/左对齐真实生效 + 自动隐藏） =====
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text(
                L("任务栏图标对齐方式"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption(L("居中"), taskbarCentered) {
                    scope0.launch { app.settingsStore.setTaskbarCentered(true) }
                }
                SegmentedOption(L("左对齐"), !taskbarCentered) {
                    scope0.launch { app.settingsStore.setTaskbarCentered(false) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    L("自动隐藏任务栏"),
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                ToggleSwitch(autohide) { v ->
                    scope0.launch { app.settingsStore.setTaskbarAutohide(v) }
                }
            }
        }
    }
}

/** v2.14 强调色色块（卡片内展示用） */
@Composable
private fun AccentSwatch(
    color: Color,
    selected: Boolean,
    outline: Boolean,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(color)
            .border(
                width = if (selected) 3.dp else if (outline) 2.dp else 0.dp,
                color = if (selected) theme.accentColor else if (outline) theme.secondaryTextColor else Color.Transparent,
                shape = RoundedCornerShape(13.dp)
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun ThemeCard(themeOption: com.linbox.core.theme.WinTheme, isSelected: Boolean, onClick: () -> Unit) {
    val currentTheme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .background(
                if (isSelected) currentTheme.accentColor.copy(alpha = 0.1f) else currentTheme.cardBackgroundColor
            )
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) currentTheme.accentColor else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp)).background(themeOption.taskbarColor))
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp)).background(themeOption.windowTitleBarColor))
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp)).background(themeOption.accentColor))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                themeOption.displayName,
                color = if (currentTheme.isDark) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                when (themeOption.variant) {
                    WindowsVariant.WIN95  -> "经典灰色 · 圆角窗口 · 居中任务栏"
                    WindowsVariant.WIN_XP -> "Luna 蓝色调 · 现代圆角"
                    WindowsVariant.WIN7   -> "Aero 玻璃 · 半透明任务栏"
                    WindowsVariant.WIN10  -> "扁平深色 · 居中任务栏"
                    WindowsVariant.WIN11  -> "Mica 材质 · 大圆角 · 居中任务栏"
                },
                color = currentTheme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
        if (isSelected) {
            Icon(Icons.Default.Check, "已选择", tint = currentTheme.accentColor, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AppsSection() {
    val app = LinBoxApp.get()
    val scope0 = rememberCoroutineScope()
    val defaultBrowser by app.settingsStore.defaultBrowser.collectAsState(initial = "browser")
    val uaMode by app.settingsStore.browserUaMode.collectAsState(initial = "desktop")
    val home by app.settingsStore.defaultBrowserHome.collectAsState(initial = "https://www.bing.com")
    var homeInput by remember { mutableStateOf(home) }

    SectionHeader("应用", "已安装的应用、默认应用、可选功能")

    // 已安装应用列表
    val allApps = remember { AppRegistry.all() }
    Text("已安装应用 (${allApps.size})", color = if (LocalWinTheme.current.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        allApps.forEach { appDef ->
            SettingsCard(
                icon = Icons.Default.Apps,
                iconBackgroundColor = Color(0xFF0078D7),
                title = appDef.displayName,
                subtitle = "ID: ${appDef.id} · ${appDef.launchMode.name}"
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    // 默认应用
    Text("默认应用", color = if (LocalWinTheme.current.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    SettingsCard(
        icon = Icons.Default.Public,
        iconBackgroundColor = Color(0xFF00B294),
        title = "默认浏览器",
        subtitle = "$defaultBrowser · 用于打开网页链接",
        onClick = { scope0.launch { app.settingsStore.setDefaultBrowser("browser") } }
    )
    Spacer(Modifier.height(8.dp))

    // 浏览器 UA 模式
    val theme = LocalWinTheme.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text(L("浏览器 UA 模式"), color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption(L("桌面模式"), uaMode == "desktop") { scope0.launch { app.settingsStore.setBrowserUaMode("desktop") } }
                SegmentedOption(L("手机模式"), uaMode == "mobile") { scope0.launch { app.settingsStore.setBrowserUaMode("mobile") } }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // 浏览器主页
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("浏览器主页", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = homeInput,
                onValueChange = { homeInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .keyboardAware(
                        value = { homeInput },
                        onValue = { homeInput = it },
                        singleLine = true
                    ),
                singleLine = true,
                shape = RoundedCornerShape(6.dp)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { scope0.launch { app.settingsStore.setDefaultBrowserHome(homeInput) } },
                shape = RoundedCornerShape(6.dp)
            ) { Text("保存") }
        }
    }
    Spacer(Modifier.height(16.dp))

    SettingsCard(
        icon = Icons.Default.Extension,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "可选功能",
        subtitle = "安装额外功能、字体、工具"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.AppShortcut,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "应用执行别名",
        subtitle = "管理应用的可执行别名"
    )
}

@Composable
private fun TimeLanguageSection() {
    val app = LinBoxApp.get()
    val context = LocalContext.current
    val scope0 = rememberCoroutineScope()
    val timeFormat24h by app.settingsStore.timeFormat24h.collectAsState(initial = true)
    val language by app.settingsStore.language.collectAsState(initial = "zh-CN")

    SectionHeader("时间和语言", "日期和时间、语言区域、输入")

    SettingsCard(
        icon = Icons.Default.Schedule,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "日期和时间",
        subtitle = "时区、自动设置时间（打开系统日期设置）",
        onClick = {
            openSystemPanel(context, AndroidSettings.ACTION_DATE_SETTINGS, "系统日期时间设置")
        }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Schedule,
        iconBackgroundColor = Color(0xFF00B294),
        title = "24 小时制",
        subtitle = "使用 24 小时制时间显示",
        trailingContent = { ToggleSwitch(timeFormat24h) { v -> scope0.launch { app.settingsStore.setTimeFormat24h(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Language,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "语言和区域",
        subtitle = "当前语言：${if (language == "zh-CN") "中文（简体）" else "English (US)"}（点击打开系统语言设置）",
        onClick = {
            openSystemPanel(context, AndroidSettings.ACTION_LOCALE_SETTINGS, "系统语言设置")
        }
    )
    Spacer(Modifier.height(8.dp))

    // 语言切换（v2.14：真实生效 —— 设置中心导航/分区/开始菜单/任务栏/桌面菜单即时切换）
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text(
                L("显示语言"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption(L("中文"), language == "zh-CN") {
                    scope0.launch {
                        app.settingsStore.setLanguage("zh-CN")
                        com.linbox.util.L10n.current = "zh-CN"
                        Toast.makeText(
                            LinBoxApp.get(),
                            com.linbox.util.L10n.t("已切换为中文，主要界面即时生效"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                SegmentedOption(L("English"), language == "en-US") {
                    scope0.launch {
                        app.settingsStore.setLanguage("en-US")
                        com.linbox.util.L10n.current = "en-US"
                        Toast.makeText(
                            LinBoxApp.get(),
                            com.linbox.util.L10n.t("已切换为 English，主要界面即时生效"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                L("切换后设置中心、开始菜单、任务栏等主要界面即时生效"),
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Keyboard,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "输入",
        subtitle = "键盘、字典、自动更正（打开系统输入法设置）",
        onClick = {
            openSystemPanel(context, AndroidSettings.ACTION_INPUT_METHOD_SETTINGS, "输入法设置")
        }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.RecordVoiceOver,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "语音",
        subtitle = "语音输入、文本到语音（打开系统语音设置）",
        onClick = {
            openSystemPanel(context, AndroidSettings.ACTION_VOICE_INPUT_SETTINGS, "语音输入设置")
        }
    )
}

@Composable
private fun GamingSection() {
    val context = LocalContext.current
    // 游戏模式：可切换的本地开关（v2.9）
    var gameMode by remember { mutableStateOf(true) }

    SectionHeader("游戏", "Xbox Game Bar、游戏模式、屏幕捕获")

    SettingsCard(
        icon = Icons.Default.SportsEsports,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "Xbox Game Bar",
        subtitle = "在游戏中打开覆盖层、性能监控"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.VideogameAsset,
        iconBackgroundColor = Color(0xFF00B294),
        title = "游戏模式",
        subtitle = if (gameMode) "已开启 · 优化系统性能" else "已关闭",
        trailingContent = { ToggleSwitch(gameMode) { gameMode = it } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.PhotoCamera,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "屏幕捕获",
        subtitle = "录制屏幕截图、视频（打开系统录屏快捷方式说明）",
        onClick = {
            Toast.makeText(context, "大多数设备可用：下拉状态栏 → 屏幕录制 / 截图", Toast.LENGTH_LONG).show()
        }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.GraphicEq,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "游戏音频",
        subtitle = "空间音效、麦克风监控（可在“系统-声音”调节音量）"
    )
}

@Composable
private fun AccessibilitySection() {
    val app = LinBoxApp.get()
    val scope0 = rememberCoroutineScope()
    val theme = LocalWinTheme.current

    // v2.17：全部改为对 LinBox 桌面真实生效的设置（不再跳转手机系统辅助功能）
    val fontScale by app.settingsStore.fontScale.collectAsState(initial = 1f)
    val uiScale by app.settingsStore.uiScale.collectAsState(initial = 1f)
    val cursorSize by app.settingsStore.mouseCursorSize.collectAsState(initial = 26f)
    val cursorTheme by app.settingsStore.mouseCursorTheme.collectAsState(initial = "white")
    val clickMode by app.settingsStore.mouseClickMode.collectAsState(initial = "single")
    val rightClick by app.settingsStore.mouseRightClick.collectAsState(initial = "twofinger")
    val vibration by app.settingsStore.keyboardVibration.collectAsState(initial = true)
    val touchFeedback by app.settingsStore.touchFeedback.collectAsState(initial = false)

    SectionHeader("辅助功能", "视觉、交互、反馈（对 LinBox 桌面生效）")

    // ===== 视觉 =====
    SettingsBlock(L("视觉")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                L("字体大小"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text("${(fontScale * 100).roundToInt()}%", color = theme.secondaryTextColor, fontSize = 11.sp)
        }
        androidx.compose.material3.Slider(
            value = fontScale,
            onValueChange = { scope0.launch { app.settingsStore.setFontScale(it) } },
            valueRange = 0.85f..1.4f,
            steps = 10
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                L("UI 缩放"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text("${(uiScale * 100).roundToInt()}%", color = theme.secondaryTextColor, fontSize = 11.sp)
        }
        androidx.compose.material3.Slider(
            value = uiScale,
            onValueChange = { scope0.launch { app.settingsStore.setUiScale(it) } },
            valueRange = 0.6f..3.0f
        )
        Spacer(Modifier.height(6.dp))
        Text(
            L("鼠标指针大小"),
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp
        )
        androidx.compose.material3.Slider(
            value = cursorSize,
            onValueChange = { scope0.launch { app.settingsStore.setMouseCursorSize(it) } },
            valueRange = 16f..48f
        )
        Text(
            L("指针颜色"),
            color = theme.secondaryTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedOption(L("经典白"), cursorTheme == "white") {
                scope0.launch { app.settingsStore.setMouseCursorTheme("white") }
            }
            SegmentedOption(L("经典黑"), cursorTheme == "black") {
                scope0.launch { app.settingsStore.setMouseCursorTheme("black") }
            }
            SegmentedOption(L("蓝色"), cursorTheme == "blue") {
                scope0.launch { app.settingsStore.setMouseCursorTheme("blue") }
            }
            SegmentedOption(L("高对比绿"), cursorTheme == "green") {
                scope0.launch { app.settingsStore.setMouseCursorTheme("green") }
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    // ===== 交互 =====
    SettingsBlock(L("交互")) {
        Text(
            L("图标打开方式"),
            color = theme.secondaryTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedOption(L("单击打开"), clickMode == "single") {
                scope0.launch { app.settingsStore.setMouseClickMode("single") }
            }
            SegmentedOption(L("双击打开"), clickMode == "double") {
                scope0.launch { app.settingsStore.setMouseClickMode("double") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            L("右键手势"),
            color = theme.secondaryTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedOption(L("双指轻点"), rightClick == "twofinger") {
                scope0.launch { app.settingsStore.setMouseRightClick("twofinger") }
            }
            SegmentedOption(L("长按"), rightClick == "longpress") {
                scope0.launch { app.settingsStore.setMouseRightClick("longpress") }
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    // ===== 听觉与反馈 =====
    SettingsBlock(L("听觉与反馈")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                L("虚拟键盘按键振动"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            ToggleSwitch(vibration) { v -> scope0.launch { app.settingsStore.setKeyboardVibration(v) } }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                L("按键触摸反馈动画"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            ToggleSwitch(touchFeedback) { v -> scope0.launch { app.settingsStore.setTouchFeedback(v) } }
        }
    }
}

@Composable
private fun PrivacySection() {
    val app = LinBoxApp.get()
    val context = LocalContext.current
    val scope0 = rememberCoroutineScope()
    val location by app.settingsStore.locationEnabled.collectAsState(initial = false)
    val camera by app.settingsStore.cameraAccess.collectAsState(initial = true)
    val mic by app.settingsStore.microphoneAccess.collectAsState(initial = true)
    val diagnostics by app.settingsStore.diagnosticsOptIn.collectAsState(initial = false)

    SectionHeader("隐私和安全", "锁屏、浏览数据、应用权限、诊断")

    // v2.17：锁屏与密码（桌面级真实入口）
    SettingsCard(
        icon = Icons.Default.Lock,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "锁屏与密码",
        subtitle = "锁屏壁纸、锁屏密码、自动锁定定时",
        onClick = { openSettingsSection("lock_settings", "锁屏设置", 560, 640) }
    )
    Spacer(Modifier.height(8.dp))

    // v2.17：真实清除浏览历史（Room HistoryDao.clearAll）
    SettingsCard(
        icon = Icons.Default.DeleteSweep,
        iconBackgroundColor = Color(0xFFC42B1C),
        title = "清除浏览历史",
        subtitle = "删除浏览器的访问历史记录（书签保留）",
        onClick = {
            scope0.launch {
                val ok = runCatching {
                    LinBoxApp.get().database.historyDao().clearAll()
                }.isSuccess
                Toast.makeText(
                    context,
                    if (ok) "已清除浏览历史" else "清除失败，请重试",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Security,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "Windows 安全中心",
        subtitle = "病毒防护、防火墙、账户保护"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.LocationSearching,
        iconBackgroundColor = Color(0xFF00B294),
        title = "查找我的设备",
        subtitle = "定位、远程锁定或清除设备",
        trailingContent = { ToggleSwitch(location) { v -> scope0.launch { app.settingsStore.setLocationEnabled(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.LocationOn,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "位置",
        subtitle = if (location) "已开启 · 应用可访问位置信息" else "关闭",
        trailingContent = { ToggleSwitch(location) { v -> scope0.launch { app.settingsStore.setLocationEnabled(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Camera,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "相机",
        subtitle = "允许应用访问相机",
        trailingContent = { ToggleSwitch(camera) { v -> scope0.launch { app.settingsStore.setCameraAccess(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Mic,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "麦克风",
        subtitle = "允许应用访问麦克风",
        trailingContent = { ToggleSwitch(mic) { v -> scope0.launch { app.settingsStore.setMicrophoneAccess(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Notifications,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "通知",
        subtitle = "应用通知、推送、横幅"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.History,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "活动历史记录",
        subtitle = "记录设备使用历史"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.BugReport,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "诊断和反馈",
        subtitle = "发送诊断数据、获取反馈",
        trailingContent = { ToggleSwitch(diagnostics) { v -> scope0.launch { app.settingsStore.setDiagnosticsOptIn(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.AutoMirrored.Filled.ForwardToInbox,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "反馈中心",
        subtitle = "提交反馈、报告问题"
    )
}

@Composable
private fun WindowsUpdateSection() {
    val app = LinBoxApp.get()
    val scope0 = rememberCoroutineScope()
    val autoUpdate by app.settingsStore.autoUpdate.collectAsState(initial = true)
    val channel by app.settingsStore.updateChannel.collectAsState(initial = "stable")

    SectionHeader("Windows 更新", "获取最新更新、安全补丁、新功能")

    // 状态卡片
    SettingsCard(
        icon = Icons.Default.CheckCircle,
        iconBackgroundColor = Color(0xFF00B294),
        title = "你使用的是最新版本",
        subtitle = "上次检查时间：刚刚"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Refresh,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "检查更新",
        subtitle = "立即检查 LinBox 是否有新版本",
        onClick = { /* 触发检查 */ }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Update,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "自动更新",
        subtitle = "在 Wi-Fi 时自动下载并安装更新",
        trailingContent = { ToggleSwitch(autoUpdate) { v -> scope0.launch { app.settingsStore.setAutoUpdate(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    // 更新通道
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("更新通道", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption("稳定版", channel == "stable") { scope0.launch { app.settingsStore.setUpdateChannel("stable") } }
                SegmentedOption("测试版", channel == "beta") { scope0.launch { app.settingsStore.setUpdateChannel("beta") } }
                SegmentedOption("开发版", channel == "dev") { scope0.launch { app.settingsStore.setUpdateChannel("dev") } }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.History,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "更新历史记录",
        subtitle = "查看已安装的更新列表"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Pause,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "暂停更新",
        subtitle = "暂停 1 周 · 1 个月 · 35 天"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Tune,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "高级选项",
        subtitle = "传递优化、活动时间、可选更新"
    )
}

@Composable
private fun AboutSection() {
    val theme = LocalWinTheme.current
    SectionHeader("关于", "LinBox 系统信息")

    SettingsCard(
        icon = Icons.Default.Info,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "LinBox",
        subtitle = "Android 上模拟 Windows 操作系统的桌面环境"
    )
    Spacer(Modifier.height(8.dp))

    // v2.12：真实设备信息（替换硬编码假数据）
    val context = LocalContext.current
    val info = remember { SystemControl.readDeviceInfo(context) }
    AboutRow("设备名称", info.deviceName)
    AboutRow("品牌", info.brand)
    AboutRow("处理器", "${info.cpuCores} 核 · ${info.cpuAbis}")
    AboutRow("已安装的 RAM", SystemControl.formatBytes(info.totalRamBytes))
    AboutRow("可用 RAM", SystemControl.formatBytes(info.availRamBytes))
    AboutRow("存储", "${SystemControl.formatBytes(info.storageTotalBytes - info.storageAvailBytes)} 已用 / ${SystemControl.formatBytes(info.storageTotalBytes)}")
    AboutRow("设备 ID", "LINBOX-${SystemControl.deviceId(context)}")
    AboutRow("产品 ID", "00LINBOX-00000-00000-00000")
    AboutRow("系统类型", "${info.cpuAbis} · Android ${info.androidVersion} (API ${info.sdkInt})")
    AboutRow("屏幕", "${info.screenWidthPx} × ${info.screenHeightPx} · ${info.densityDpi}dpi")
    AboutRow("应用名称", "LinBox")
    AboutRow("版本", BuildConfig.VERSION_NAME)
    AboutRow("包名", context.packageName)
    AboutRow("当前主题", theme.displayName)
    AboutRow("minSdk", "24 (Android 7.0)")
    AboutRow("targetSdk", "34 (Android 14)")
    AboutRow("项目", "LinBox - Android Windows Simulator")
    AboutRow("License", "MIT")
    Spacer(Modifier.height(16.dp))

    // 重置 / 备份
    SettingsCard(
        icon = Icons.Default.RestorePage,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "重置应用",
        subtitle = "清除所有数据，恢复初始状态"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Backup,
        iconBackgroundColor = Color(0xFF00B294),
        title = "备份数据",
        subtitle = "导出应用设置到本地"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Code,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "查看源码",
        subtitle = "GitHub: LinBox 项目仓库"
    )
}

@Composable
internal fun AboutRow(label: String, value: String) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
        Text(value, color = theme.secondaryTextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * Win11 风格的分段选项按钮
 */
@Composable
internal fun SegmentedOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) theme.accentColor.copy(alpha = 0.15f) else theme.windowBackgroundColor
            )
            .border(
                width = if (selected) 1.dp else 1.dp,
                color = if (selected) theme.accentColor else theme.dividerColor,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (selected) theme.accentColor else (if (theme.isDark) Color.White else Color.Black),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}
