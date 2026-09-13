package com.linbox.apps.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linbox.BuildConfig
import com.linbox.LinBoxApp
import com.linbox.apps.terminal.TerminalKeepAliveService
import com.linbox.core.input.gamepad.GamepadController
import com.linbox.core.shell.ShellController
import com.linbox.core.theme.LocalWinTheme
import com.linbox.util.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * LinBox 设置（全屏页，终端主页工具栏"设置"进入）。
 *
 * 只保留对本应用（终端 + X11 + 虚拟手柄）真实有意义的设置：
 * - 显示：分辨率信息 / UI 缩放 / 显示方向 / 刘海屏
 * - 终端背景：背景色 / 自定义图片背景 / 显示语言
 * - 输入：虚拟键盘（KeyboardSettingsPage）
 * - 游戏手柄：开关 + 悬浮设置窗入口（X11 游戏用）
 * - 开发者选项：停止限制子进程（防报错 9）/ 忽略电池优化 / CPU 保持唤醒
 * - 关于：应用与设备信息
 *
 * 返回键回终端主页。
 */
@Composable
fun SettingsScreen() {
    androidx.activity.compose.BackHandler(enabled = true) {
        ShellController.showTerminal()
    }
    SettingsContent()
}

// ============================================================
// 路由
// ============================================================

/** 右侧内容路由（子页优先） */
private sealed interface SettingsRoute {
    data object Home : SettingsRoute
    data object Keyboard : SettingsRoute
}

private enum class NavSection(val id: String, val label: String, val desc: String) {
    DISPLAY("display", "显示", "缩放、方向、刘海屏"),
    PERSONALIZATION("personalization", "终端背景", "背景色、自定义图片、透明度、语言"),
    INPUT("input", "输入", "虚拟键盘"),
    GAMEPAD("gamepad", "游戏手柄", "虚拟手柄开关与布局设置"),
    DEVELOPER("developer", "开发者选项", "进程保护、后台存活"),
    ABOUT("about", "关于", "应用与设备信息")
}

@Composable
private fun SettingsContent() {
    val theme = LocalWinTheme.current
    var section by remember { mutableStateOf(NavSection.DISPLAY) }
    var route by remember { mutableStateOf<SettingsRoute>(SettingsRoute.Home) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
    ) {
        // ===== 左侧导航 =====
        Column(
            modifier = Modifier
                .width(200.dp)
                .fillMaxHeight()
                .background(theme.cardBackgroundColor)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            Text(
                "LinBox 设置",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)
            )
            NavSection.entries.forEach { item ->
                SettingsNavItem(
                    label = L(item.label),
                    active = section == item && route == SettingsRoute.Home,
                    onClick = {
                        section = item
                        route = SettingsRoute.Home
                    }
                )
            }
        }

        // ===== 右侧内容 =====
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            when (route) {
                SettingsRoute.Home -> when (section) {
                    NavSection.DISPLAY -> DisplaySection()
                    NavSection.PERSONALIZATION -> PersonalizationSection()
                    NavSection.INPUT -> InputSection(onOpenKeyboard = { route = SettingsRoute.Keyboard })
                    NavSection.GAMEPAD -> GamepadSection()
                    NavSection.DEVELOPER -> DeveloperSection()
                    NavSection.ABOUT -> AboutSection()
                }
                SettingsRoute.Keyboard -> KeyboardSettingsPage(onBack = { route = SettingsRoute.Home })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ============================================================
// 导航 / 通用组件
// ============================================================

@Composable
private fun SettingsNavItem(label: String, active: Boolean, onClick: () -> Unit) {
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
        Text(
            label,
            color = if (active) theme.accentColor else (if (theme.isDark) Color.White else Color.Black),
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
        )
    }
}

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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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

/** Win11 风格的分段选项按钮 */
@Composable
internal fun SegmentedOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) theme.accentColor.copy(alpha = 0.18f) else theme.cardBackgroundColor
            )
            .border(
                1.dp,
                if (selected) theme.accentColor else theme.dividerColor,
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) theme.accentColor else theme.secondaryTextColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
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

// ============================================================
// 显示
// ============================================================

@Composable
private fun DisplaySection() {
    val theme = LocalWinTheme.current
    val app = LinBoxApp.get()
    val context = LocalContext.current
    val scope0 = rememberCoroutineScope()
    val uiScale by app.settingsStore.uiScale.collectAsState(initial = 1f)
    val orientation by app.settingsStore.displayOrientation.collectAsState(initial = "auto")
    val useCutout by app.settingsStore.useCutout.collectAsState(initial = true)

    SectionHeader("显示", "缩放、方向、刘海屏")

    // 屏幕信息（真实分辨率/密度/刷新率）
    val displayInfo = remember {
        val dm = context.resources.displayMetrics
        val refresh = if (Build.VERSION.SDK_INT >= 30)
            context.display?.refreshRate ?: 60f else 60f
        "${dm.widthPixels} × ${dm.heightPixels} · ${dm.densityDpi}dpi · " +
            String.format("%.1f", refresh) + "Hz"
    }
    SettingsCard(
        icon = Icons.Default.Monitor,
        iconBackgroundColor = Color(0xFF0067C0),
        title = "屏幕",
        subtitle = displayInfo
    )
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
                    valueRange = 0.6f..3.0f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("${(uiScale * 100).roundToInt()}%", color = theme.secondaryTextColor, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "缩放全部界面（窗口、文字、虚拟键盘与手柄），立即生效。100% 为紧凑布局，最大 300%。",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
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

    // 占用刘海屏
    SettingsCard(
        icon = Icons.Default.Smartphone,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "占用刘海屏",
        subtitle = if (useCutout) "已开启 · 内容延伸绘制到刘海/挖孔区域" else "关闭 · 刘海区域不显示内容",
        trailingContent = {
            ToggleSwitch(useCutout) { v -> scope0.launch { app.settingsStore.setUseCutout(v) } }
        }
    )
}

// ============================================================
// 终端背景：背景色 / 自定义图片背景 / 显示语言
// ============================================================

/** 终端背景色预设（色值键，"default" = 经典黑 #0C0C0C）。 */
private val TERMINAL_BG_PRESETS = listOf(
    "default" to "默认",
    "#000000" to "纯黑",
    "#1E1E1E" to "深灰",
    "#0D1B2A" to "深蓝",
    "#0B2011" to "墨绿",
    "#2A0E0E" to "酒红"
)

@Composable
private fun PersonalizationSection() {
    val theme = LocalWinTheme.current
    val app = LinBoxApp.get()
    val scope0 = rememberCoroutineScope()
    val language by app.settingsStore.language.collectAsState(initial = "zh-CN")
    val bgColorKey by app.settingsStore.terminalBgColor.collectAsState(initial = "default")
    val bgImageEnabled by app.settingsStore.terminalBgImage.collectAsState(initial = false)
    val context = LocalContext.current
    val imageFile = remember { java.io.File(context.filesDir, "terminal_bg.jpg") }
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // 预览缩略图（与终端实际使用的降采样解码同路）
    LaunchedEffect(bgImageEnabled) {
        previewBitmap = if (bgImageEnabled) withContext(Dispatchers.IO) {
            decodeBgPreview(imageFile)
        } else null
    }

    // 选图（SAF，不需要存储权限）：拷贝到 filesDir 持久化，避免
    // content:// URI 权限随重启失效
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope0.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    imageFile.outputStream().use { output -> input.copyTo(output) }
                }
                app.settingsStore.setTerminalBgImage(true)
            } catch (e: Exception) {
                android.util.Log.w("LinBoxSettings", "终端背景图保存失败: ${e.message}")
            }
        }
    }

    SectionHeader("终端背景", "背景色、自定义图片背景、透明度")

    // ===== 背景色 =====
    SettingsBlockCard("背景色" + if (bgImageEnabled) "（图片背景启用时不生效）" else "") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TERMINAL_BG_PRESETS.forEach { (key, label) ->
                BgSwatch(label, key, bgColorKey) {
                    scope0.launch { app.settingsStore.setTerminalBgColor(key) }
                }
            }
        }
    }

    // ===== 自定义图片背景 =====
    SettingsBlockCard("自定义图片背景") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 96.dp, height = 64.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (bgImageEnabled) "已启用：图片暗化叠加，保证文字可读"
                    else "未设置：使用上方纯色背景",
                    color = theme.secondaryTextColor,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentedOption("选择图片…", selected = false) { pickImage.launch("image/*") }
                    SegmentedOption("恢复默认", selected = false) {
                        scope0.launch(Dispatchers.IO) {
                            imageFile.delete()
                            app.settingsStore.setTerminalBgImage(false)
                        }
                    }
                }
            }
        }
    }

    // ===== 背景透明度 =====
    SettingsBlockCard("背景透明度") {
        // 拖动中仅更新本地值（顺滑不卡顿），松手才落盘
        val stored by app.settingsStore.terminalBgTransparency.collectAsState(initial = 0f)
        var dragValue by remember { mutableStateOf<Float?>(null) }
        val shown = dragValue ?: stored
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "背景向黑底淡化，文字不受影响",
                color = theme.secondaryTextColor,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(shown * 100).roundToInt()}%",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = shown,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { v -> scope0.launch { app.settingsStore.setTerminalBgTransparency(v) } }
            },
            valueRange = 0f..1f
        )
    }

    // ===== 显示语言 =====
    SettingsBlockCard("显示语言") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedOption("简体中文", language == "zh-CN") { scope0.launch { app.settingsStore.setLanguage("zh-CN") } }
            SegmentedOption("English", language == "en-US") { scope0.launch { app.settingsStore.setLanguage("en-US") } }
        }
    }
}

@Composable
private fun SettingsBlockCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalWinTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Text(
            title,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
    Spacer(Modifier.height(8.dp))
}

/** 终端背景色色块（选中态白描边）。 */
@Composable
private fun BgSwatch(label: String, colorKey: String, current: String, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    val color = if (colorKey == "default") Color(0xFF0C0C0C)
    else Color(android.graphics.Color.parseColor(colorKey))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color)
                .border(
                    width = if (current == colorKey) 2.dp else 1.dp,
                    color = if (current == colorKey) Color.White else theme.dividerColor,
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable(onClick = onClick)
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = theme.secondaryTextColor, fontSize = 10.sp)
    }
}

/** 背景图预览缩略图解码（降采样，与终端实际使用同路）。 */
private fun decodeBgPreview(file: java.io.File): android.graphics.Bitmap? = try {
    if (!file.isFile) null else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outHeight / sample > 1280 || bounds.outWidth / sample > 720) sample *= 2
        android.graphics.BitmapFactory.decodeFile(
            file.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
} catch (_: Exception) {
    null
}

// ============================================================
// 输入
// ============================================================

@Composable
private fun InputSection(onOpenKeyboard: () -> Unit) {
    SectionHeader("输入", "虚拟键盘")

    // v2.27：鼠标设置已移除（对现有输入链路无实际作用）；虚拟鼠标指针
    // 按默认行为（显示、触控模式）继续工作，数据层保留无副作用。
    SettingsCard(
        icon = Icons.Default.Keyboard,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "虚拟键盘",
        subtitle = "全键盘开关、布局（功能键行/小键盘）、大小、主题、位置",
        onClick = onOpenKeyboard
    )
}

// ============================================================
// 游戏手柄
// ============================================================

@Composable
private fun GamepadSection() {
    val theme = LocalWinTheme.current
    val app = LinBoxApp.get()
    val scope0 = rememberCoroutineScope()
    val gamepadEnabled by app.settingsStore.gamepadEnabled.collectAsState(initial = false)

    SectionHeader("游戏手柄", "X11 游戏用的屏幕虚拟手柄（摇杆/十字键/按钮）")

    SettingsCard(
        icon = Icons.Default.SportsEsports,
        iconBackgroundColor = Color(0xFF00B294),
        title = "显示虚拟手柄",
        subtitle = if (gamepadEnabled) "已开启 · 屏幕上显示手柄元素（可在手柄工具条隐藏）"
        else "已关闭 · 开启后屏幕出现虚拟摇杆/按钮，映射为键盘/鼠标事件",
        trailingContent = {
            ToggleSwitch(gamepadEnabled) { v ->
                scope0.launch { app.settingsStore.setGamepadEnabled(v) }
            }
        }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Settings,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "手柄布局设置",
        subtitle = "添加/删除摇杆、十字键、按钮，调整位置大小与按键映射",
        onClick = {
            // 打开悬浮设置窗（GamepadSettingsWindow 由壳层常驻组合）
            if (!gamepadEnabled) {
                scope0.launch { app.settingsStore.setGamepadEnabled(true) }
            }
            GamepadController.settingsOpen = true
        }
    )
    Spacer(Modifier.height(8.dp))

    Text(
        "提示：手柄元素会把触控动作映射为键盘按键（WASD/方向/空格等）或鼠标事件，" +
            "适用于 X11 窗口内运行的游戏；布局修改即时保存。",
        color = theme.secondaryTextColor,
        fontSize = 11.sp
    )
}

// ============================================================
// 开发者选项
// ============================================================

/** 幽灵进程监控开关的系统键（Android 12+，与 adb settings put global 同名）。 */
private const val KEY_PHANTOM_MONITOR = "settings_enable_monitor_phantom_procs"
/** device_config 命名空间与键：单应用子进程数量上限。 */
private const val NS_PHANTOM = "activity_manager"
private const val KEY_PHANTOM_MAX = "max_phantom_processes"
/** AOSP 默认子进程上限（32）：关闭保护时恢复用。 */
private const val PHANTOM_MAX_DEFAULT = "32"

/**
 * Settings.Config（device_config 读写 API，运行时仅存在于 Android 11+/API 30+）。
 *
 * 该嵌套类不在公开 SDK 的 android.jar 里（compileSdk 34 下直接引用也会报
 * "Unresolved reference: Config"，CI 已实证），故统一经反射访问：
 * - API 30+ 设备：反射真实读写 device_config（activity_manager/max_phantom_processes）；
 * - 低版本/反射失败：安全降级返回 null/false，不影响公开的
 *   Settings.Global "settings_enable_monitor_phantom_procs" 主链路。
 */
private val settingsConfigClass: Class<*>? by lazy {
    try {
        Class.forName("android.provider.Settings\$Config")
    } catch (_: Throwable) {
        null
    }
}

/** 读 device_config：等价 Settings.Config.getString(cr, ns, key)。 */
private fun deviceConfigGetString(
    cr: android.content.ContentResolver, ns: String, key: String
): String? {
    val cls = settingsConfigClass ?: return null
    return try {
        cls.getMethod(
            "getString",
            android.content.ContentResolver::class.java,
            String::class.java,
            String::class.java
        ).invoke(null, cr, ns, key) as? String
    } catch (_: Throwable) {
        null
    }
}

/** 写 device_config：等价 Settings.Config.putString(cr, ns, key, value)。 */
private fun deviceConfigPutString(
    cr: android.content.ContentResolver, ns: String, key: String, value: String
): Boolean {
    val cls = settingsConfigClass ?: return false
    return try {
        cls.getMethod(
            "putString",
            android.content.ContentResolver::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        ).invoke(null, cr, ns, key, value)
        true
    } catch (_: Throwable) {
        false
    }
}

/** WRITE_SECURE_SETTINGS 一次性授权命令（复制给用户在电脑上执行）。 */
private const val ADB_GRANT_CMD = "adb shell pm grant com.linbox android.permission.WRITE_SECURE_SETTINGS"

/**
 * 开发者选项（v2.27）：进程保护 / 后台存活。
 * 目标是解决"终端进程莫名被杀（Killed / signal 9 / 报错 9）"一类问题：
 * - 停止限制子进程：关闭系统幽灵进程杀手（需 WRITE_SECURE_SETTINGS，
 *   页面内提供一次性 ADB 授权命令并支持复制）；
 * - 忽略电池优化：跳转系统授权页，降低 Doze 冻结概率；
 * - CPU 保持唤醒：终端常驻服务持有 partial wake lock（真实生效）。
 */
@Composable
private fun DeveloperSection() {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val app = LinBoxApp.get()
    val scope0 = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // ===== 系统侧真实状态（实时读取，非本地记忆） =====
    var granted by remember { mutableStateOf(false) }
    var phantomOff by remember { mutableStateOf(false) }
    var battIgnored by remember { mutableStateOf(false) }
    var showGrantDialog by remember { mutableStateOf(false) }

    fun refreshStatus() {
        granted = try {
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        phantomOff = try {
            val cr = context.contentResolver
            val monitor = android.provider.Settings.Global.getString(cr, KEY_PHANTOM_MONITOR)
            val max = if (Build.VERSION.SDK_INT >= 30)
                deviceConfigGetString(cr, NS_PHANTOM, KEY_PHANTOM_MAX) else null
            monitor == "false" || ((max?.toLongOrNull() ?: 0L) > 32L)
        } catch (_: Exception) {
            false
        }
        battIgnored = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    // 首次进入 + 从系统授权页返回（ON_RESUME）时刷新真实状态
    LaunchedEffect(Unit) { refreshStatus() }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshStatus()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    SectionHeader("开发者选项", "进程保护 / 后台存活（解决报错 9 等问题）")

    // ===== 停止限制子进程（幽灵进程杀手 → 报错 9 根因） =====
    SettingsCard(
        icon = Icons.Default.Shield,
        iconBackgroundColor = Color(0xFF00B294),
        title = "停止限制子进程",
        subtitle = if (phantomOff) "已停止限制 · 子进程不再被系统幽灵进程杀手清理（防报错 9）"
        else if (granted) "已具备授权 · 开启后解除系统对子进程数量的限制"
        else "需一次性 ADB 授权 · 解决 [Process killed / signal 9] 报错",
        trailingContent = {
            ToggleSwitch(phantomOff) { v ->
                if (v) {
                    if (!granted) {
                        showGrantDialog = true
                    } else {
                        val ok = try {
                            val cr = context.contentResolver
                            android.provider.Settings.Global.putString(cr, KEY_PHANTOM_MONITOR, "false")
                            if (Build.VERSION.SDK_INT >= 30) {
                                deviceConfigPutString(cr, NS_PHANTOM, KEY_PHANTOM_MAX, "2147483647")
                            }
                            true
                        } catch (_: Exception) {
                            false
                        }
                        if (!ok) {
                            android.widget.Toast.makeText(
                                context, "写入系统设置失败", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        refreshStatus()
                    }
                } else {
                    try {
                        val cr = context.contentResolver
                        android.provider.Settings.Global.putString(cr, KEY_PHANTOM_MONITOR, "true")
                        if (Build.VERSION.SDK_INT >= 30) {
                            deviceConfigPutString(cr, NS_PHANTOM, KEY_PHANTOM_MAX, PHANTOM_MAX_DEFAULT)
                        }
                    } catch (_: Exception) {
                    }
                    refreshStatus()
                }
            }
        }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 忽略电池优化 =====
    SettingsCard(
        icon = Icons.Default.BatteryChargingFull,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "忽略电池优化",
        subtitle = if (battIgnored) "已忽略 · 系统省电/休眠策略不再冻结 LinBox"
        else "未开启 · 点击申请，显著降低后台被杀概率",
        onClick = {
            if (!battIgnored) {
                try {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                } catch (_: Exception) {
                    // 个别 ROM 无此页面 → 退到电池优化列表页
                    try {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }
    )
    Spacer(Modifier.height(8.dp))

    // ===== CPU 保持唤醒（终端常驻服务真实持有 wake lock） =====
    val cpuAwake by app.settingsStore.devKeepCpuAwake.collectAsState(initial = false)
    SettingsCard(
        icon = Icons.Default.Memory,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "CPU 保持唤醒",
        subtitle = if (cpuAwake) "已开启 · 终端常驻服务持有唤醒锁，后台下载/编译不因休眠中断"
        else "关闭（默认）· 系统可正常休眠省电",
        trailingContent = {
            ToggleSwitch(cpuAwake) { v ->
                scope0.launch { app.settingsStore.setDevKeepCpuAwake(v) }
                TerminalKeepAliveService.setCpuAwake(context, v)
            }
        }
    )
    Spacer(Modifier.height(8.dp))

    Text(
        "报错 9 说明：终端进程突然消失并提示 Killed（signal 9 / 退出码 9），" +
            "多为 Android 12+ 幽灵进程限制（单应用子进程数超限即被杀）或省电冻结所致。" +
            "开启「停止限制子进程」与「忽略电池优化」即可解决。",
        color = theme.secondaryTextColor,
        fontSize = 11.sp
    )

    if (showGrantDialog) {
        AlertDialog(
            onDismissRequest = { showGrantDialog = false },
            title = { Text("需要一次 ADB 授权") },
            text = {
                Text(
                    "「停止限制子进程」需授予 LinBox 写入系统设置的权限。\n\n" +
                        "电脑连接设备后执行一次（可复制下方命令）：\n\n" +
                        ADB_GRANT_CMD + "\n\n" +
                        "执行后回到本页重新打开开关即可。",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("adb", ADB_GRANT_CMD))
                        android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                    }
                    showGrantDialog = false
                }) { Text("复制命令") }
            },
            dismissButton = {
                TextButton(onClick = { showGrantDialog = false }) { Text("关闭") }
            }
        )
    }
}

// ============================================================
// 关于
// ============================================================

@Composable
private fun AboutSection() {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    SectionHeader("关于", "LinBox 应用信息")

    SettingsCard(
        icon = Icons.Default.Info,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "LinBox",
        subtitle = "Android 上的 Termux 终端 + X11 图形一体化应用"
    )
    Spacer(Modifier.height(8.dp))

    val runtime = Runtime.getRuntime()
    AboutRow("设备名称", Build.MODEL)
    AboutRow("品牌", Build.BRAND)
    AboutRow("处理器", "${runtime.availableProcessors()} 核 · ${Build.SUPPORTED_ABIS.firstOrNull() ?: ""}")
    AboutRow("系统", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    AboutRow("屏幕", "${context.resources.displayMetrics.widthPixels} × ${context.resources.displayMetrics.heightPixels}")
    AboutRow("应用名称", "LinBox")
    AboutRow("版本", BuildConfig.VERSION_NAME)
    AboutRow("包名", context.packageName)
    AboutRow("X11 显示号", ":13（终端 linbox-x11 命令调起）")
    AboutRow("项目", "LinBox - Termux + X11")
    AboutRow("License", "MIT")
}
