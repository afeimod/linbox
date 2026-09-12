package com.linbox.core.desktop

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.linbox.LinBoxApp
import com.linbox.core.theme.WinTheme
import com.linbox.util.ImmersiveMode
import com.linbox.util.SystemControl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Win11 风格日历弹窗 - 点击任务栏时钟后显示
 *
 * - 顶部：当前日期 + 下拉箭头
 * - 月份导航：← 2026年8月 →
 * - 月历网格：星期一~星期日，今日高亮（圆形背景）
 * - 半透明 Mica/Acrylic 风格
 */
@Composable
fun CalendarFlyout(
    theme: WinTheme,
    modifier: Modifier = Modifier
) {
    val now = remember { Calendar.getInstance() }
    var displayYear by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var displayMonth by remember { mutableStateOf(now.get(Calendar.MONTH)) }

    val today = Triple(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))

    val monthCalendar = remember(displayYear, displayMonth) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, displayYear)
            set(Calendar.MONTH, displayMonth)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        cal
    }
    val daysInMonth = monthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    // 一=0,二=1,...,日=6
    val firstDayOfWeek = (monthCalendar.get(Calendar.DAY_OF_WEEK) + 5) % 7

    val weekdayNames = listOf("日", "一", "二", "三", "四", "五", "六")
    val monthNames = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
    val headerText = "星期${weekdayNames[now.get(Calendar.DAY_OF_WEEK) - 1]}, " +
            "${now.get(Calendar.DAY_OF_MONTH)} ${monthNames[now.get(Calendar.MONTH)]}"

    val popupColor = if (theme.isDark) Color(0xE6323232) else Color(0xE6F9F9F9)

    Box(
        modifier = modifier
            .width(320.dp)
            .height(360.dp)
            .shadow(16.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(popupColor)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部：日期 + 下拉箭头
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = headerText,
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.KeyboardArrowDown, null,
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // 月份导航
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ChevronLeft, null,
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable {
                            var m = displayMonth - 1
                            var y = displayYear
                            if (m < 0) { m = 11; y-- }
                            displayMonth = m; displayYear = y
                        }
                        .padding(2.dp)
                )
                Text(
                    text = "${displayYear}年${displayMonth + 1}月",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Icon(
                    Icons.Default.ChevronRight, null,
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable {
                            var m = displayMonth + 1
                            var y = displayYear
                            if (m > 11) { m = 0; y++ }
                            displayMonth = m; displayYear = y
                        }
                        .padding(2.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // 星期表头
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                    Text(
                        text = day,
                        color = theme.secondaryTextColor,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 日期网格 - 6 行
            for (week in 0..5) {
                if (week * 7 - firstDayOfWeek + 1 > daysInMonth) break
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    for (dow in 0..6) {
                        val dayIndex = week * 7 + dow - firstDayOfWeek + 1
                        if (dayIndex < 1 || dayIndex > daysInMonth) {
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            val isToday = displayYear == today.first &&
                                    displayMonth == today.second &&
                                    dayIndex == today.third
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isToday) theme.accentColor else Color.Transparent
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dayIndex.toString(),
                                        color = if (isToday) Color.White
                                                else if (theme.isDark) Color.White else Color.Black,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Win11 风格快速设置面板 - 点击任务栏 wifi/电池/音量后显示
 *
 * v2.10 全部接入真实逻辑：
 * - WiFi：Android 9 及以下直接开关；Android 10+ 打开系统互联网面板（系统限制）
 * - 蓝牙：直接开关（Android 12+ 自动请求 BLUETOOTH_CONNECT 权限；失败回退蓝牙设置页）
 * - 飞行模式：显示真实状态，点击打开系统飞行模式面板（系统限制无法直接切换）
 * - 手电筒：CameraManager.setTorchMode 真实开关闪光灯
 * - 辅助功能：打开系统无障碍设置页
 * - 音量：滑块直接控制系统媒体音量 + 静音/恢复按钮
 * - 亮度：滑块实时调整应用窗口亮度（与设置中心联动）
 * - 电池：真实电量 + 充电状态，点击打开系统省电设置
 */
@Composable
fun QuickSettingsPanel(
    theme: WinTheme,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val app = LinBoxApp.get()
    val scope = rememberCoroutineScope()
    val popupColor = if (theme.isDark) Color(0xE6323232) else Color(0xE6F9F9F9)
    val fg = if (theme.isDark) Color.White else Color.Black

    // ===== 虚拟游戏手柄（v2.15）：快速开关，首开顺手弹设置窗 =====
    val gamepadEnabled by app.settingsStore.gamepadEnabled.collectAsState(initial = false)

    /** 打开系统设置面板 */
    fun openSystemPanel(action: String, name: String) {
        runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, "无法打开$name", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== WiFi =====
    val wifiManager = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    var wifiEnabled by remember {
        mutableStateOf(runCatching { wifiManager?.isWifiEnabled }.getOrNull() == true)
    }

    fun toggleWifi(want: Boolean) {
        val ok = runCatching {
            @Suppress("DEPRECATION")
            wifiManager?.setWifiEnabled(want) == true
        }.getOrDefault(false)
        if (ok) {
            wifiEnabled = want
            Toast.makeText(context, if (want) "已开启 WiFi" else "已关闭 WiFi", Toast.LENGTH_SHORT).show()
        } else {
            // Android 10+ 第三方应用无法直接开关 WiFi（系统限制）→ 打开系统面板
            Toast.makeText(context, "系统限制，已打开互联网面板", Toast.LENGTH_SHORT).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openSystemPanel(AndroidSettings.Panel.ACTION_INTERNET_CONNECTIVITY, "互联网面板")
            } else {
                openSystemPanel(AndroidSettings.ACTION_WIFI_SETTINGS, "WiFi 设置")
            }
        }
    }

    // ===== 蓝牙（Android 12+ 需要 BLUETOOTH_CONNECT 运行时权限） =====
    val bluetoothAdapter = remember {
        runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        }.getOrNull()
    }
    var bluetoothEnabled by remember {
        mutableStateOf(runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false))
    }
    var pendingBtEnable by remember { mutableStateOf<Boolean?>(null) }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val want = pendingBtEnable
        pendingBtEnable = null
        if (granted && want != null) {
            val ok = runCatching {
                if (want) bluetoothAdapter?.enable() else bluetoothAdapter?.disable()
                true
            }.getOrDefault(false)
            if (ok) {
                bluetoothEnabled = want
                Toast.makeText(context, if (want) "已开启蓝牙" else "已关闭蓝牙", Toast.LENGTH_SHORT).show()
            } else {
                openSystemPanel(AndroidSettings.ACTION_BLUETOOTH_SETTINGS, "蓝牙设置")
            }
        } else if (!granted) {
            Toast.makeText(context, "需要蓝牙权限才能开关蓝牙", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleBluetooth(want: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingBtEnable = want
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        val ok = runCatching {
            if (want) bluetoothAdapter?.enable() else bluetoothAdapter?.disable()
            true
        }.getOrDefault(false)
        if (ok) {
            bluetoothEnabled = want
            Toast.makeText(context, if (want) "已开启蓝牙" else "已关闭蓝牙", Toast.LENGTH_SHORT).show()
        } else {
            openSystemPanel(AndroidSettings.ACTION_BLUETOOTH_SETTINGS, "蓝牙设置")
        }
    }

    // ===== 飞行模式（真实状态；切换需系统权限，点击打开系统面板） =====
    var airplaneOn by remember {
        mutableStateOf(
            runCatching {
                AndroidSettings.Global.getInt(
                    context.contentResolver, AndroidSettings.Global.AIRPLANE_MODE_ON
                ) == 1
            }.getOrDefault(false)
        )
    }

    // ===== 手电筒（CameraManager.setTorchMode，无需运行时权限） =====
    var torchOn by remember { mutableStateOf(false) }
    fun toggleTorch() {
        runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: error("设备无闪光灯")
            cm.setTorchMode(cameraId, !torchOn)
            torchOn = !torchOn
            Toast.makeText(
                context,
                if (torchOn) "手电筒已开启" else "手电筒已关闭",
                Toast.LENGTH_SHORT
            ).show()
        }.onFailure {
            Toast.makeText(context, "无法切换手电筒: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 音量（真实控制系统媒体音量） =====
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volume by remember {
        mutableStateOf(
            runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        )
    }
    val maxVolume = remember {
        runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)
    }
    var volumeBeforeMute by remember { mutableStateOf(-1) }

    // ===== 亮度（v2.12：优先真实系统亮度；未授权时回退窗口亮度） =====
    var brightnessValue by remember {
        mutableStateOf(
            runCatching {
                val sys = SystemControl.getSystemBrightness(context)
                if (sys >= 0 && SystemControl.canWriteSystemSettings(context)) sys / 255f else 0.8f
            }.getOrDefault(0.8f)
        )
    }

    // ===== 电池（真实电量 + 充电状态） =====
    val batteryInfo = remember {
        runCatching {
            val battery = context.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
            pct to charging
        }.getOrDefault(100 to false)
    }

    Box(
        modifier = modifier
            .width(320.dp)
            .height(500.dp)
            .shadow(16.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(popupColor)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 顶部 3x3 toggle 网格（全部真实逻辑） =====
            val toggles = listOf(
                QuickToggle(
                    "WiFi", if (wifiEnabled) "已开启" else "已关闭",
                    Icons.Default.Wifi, wifiEnabled, onClick = { toggleWifi(!wifiEnabled) }
                ),
                QuickToggle(
                    "蓝牙", if (bluetoothEnabled) "已开启" else "已关闭",
                    Icons.Default.Bluetooth, bluetoothEnabled, onClick = { toggleBluetooth(!bluetoothEnabled) }
                ),
                QuickToggle(
                    "飞行模式", if (airplaneOn) "开启" else "关闭",
                    Icons.Default.AirplanemodeActive, airplaneOn,
                    onClick = {
                        // 系统限制无法直接切换飞行模式；Settings.Panel 也没有飞行模式面板
                        // （Panel 仅 WIFI / INTERNET_CONNECTIVITY / VOLUME / NFC 四个公开面板，
                        //  v2.10.0 曾引用不存在的 Panel.ACTION_AIRPLANE_MODE 导致编译失败）。
                        // ACTION_AIRPLANE_MODE_SETTINGS 是全版本公开 API，直接打开飞行模式设置页。
                        openSystemPanel(AndroidSettings.ACTION_AIRPLANE_MODE_SETTINGS, "飞行模式设置")
                    }
                ),
                QuickToggle(
                    "手电筒", if (torchOn) "已开启" else "已关闭",
                    Icons.Default.FlashlightOn, torchOn, onClick = { toggleTorch() }
                ),
                QuickToggle(
                    "虚拟手柄", if (gamepadEnabled) "已开启" else "已关闭",
                    Icons.Default.SportsEsports, gamepadEnabled,
                    onClick = {
                        scope.launch { app.settingsStore.setGamepadEnabled(!gamepadEnabled) }
                        if (!gamepadEnabled) {
                            // 首次开启顺手打开设置窗，方便布置按键
                            com.linbox.core.input.gamepad.GamepadController.settingsOpen = true
                        }
                    }
                ),
                QuickToggle(
                    "辅助功能", "系统设置",
                    Icons.Default.Accessibility, false,
                    onClick = { openSystemPanel(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS, "辅助功能设置") }
                ),
                QuickToggle(
                    "设置中心", "所有设置",
                    Icons.Default.Settings, false, onClick = onOpenSettings
                )
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                toggles.chunked(3).forEach { rowToggles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowToggles.forEach { toggle ->
                            QuickToggleCell(toggle, theme, Modifier.weight(1f))
                        }
                        repeat(3 - rowToggles.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ===== 音量滑块（真实控制系统音量）+ 静音按钮 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (theme.isDark) Color(0x33FFFFFF) else Color(0x11000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 静音/恢复
                Icon(
                    if (volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    "静音/恢复",
                    tint = fg,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (volume == 0) {
                                val restore = if (volumeBeforeMute > 0) volumeBeforeMute
                                else (maxVolume * 0.4f).toInt().coerceAtLeast(1)
                                runCatching {
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
                                }
                                volume = restore
                            } else {
                                volumeBeforeMute = volume
                                runCatching {
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                                }
                                volume = 0
                            }
                        }
                        .padding(3.dp)
                )
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { v ->
                        val newVol = v.toInt()
                        if (newVol != volume) {
                            runCatching {
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            }
                            volume = newVol
                        }
                    },
                    valueRange = 0f..maxVolume.toFloat(),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (volume == 0) "静音" else "$volume",
                    color = fg.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.width(30.dp),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(8.dp))

            // ===== 亮度滑块（v2.12：优先调节真实系统亮度） =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (theme.isDark) Color(0x33FFFFFF) else Color(0x11000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.BrightnessMedium, "亮度", tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = brightnessValue,
                    onValueChange = {
                        brightnessValue = it
                        // v2.12：优先写系统亮度；未授权时回退窗口亮度
                        val ok = SystemControl.setSystemBrightness(context, it)
                        if (!ok) applyPanelBrightness(context, it)
                        scope.launch { app.settingsStore.setBrightness(it) }
                    },
                    valueRange = 0.05f..1.0f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${(brightnessValue * 100).roundToInt()}%",
                    color = fg.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.width(30.dp),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(1f))

            // ===== 底部电池（真实电量/充电状态，点击打开省电设置）+ 设置齿轮 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        openSystemPanel(AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS, "省电设置")
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (batteryInfo.second) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                    "电池",
                    tint = fg,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (batteryInfo.second) "${batteryInfo.first}% 充电中" else "${batteryInfo.first}%",
                    color = fg,
                    fontSize = 12.sp
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Settings, null,
                        tint = fg,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** 应用真实屏幕亮度（对本应用窗口生效，v2.10 快速设置面板） */
private fun applyPanelBrightness(context: Context, value: Float) {
    val activity = ImmersiveMode.findActivity(context) ?: return
    runCatching {
        val lp = activity.window.attributes
        lp.screenBrightness = value.coerceIn(0.05f, 1f)
        activity.window.attributes = lp
    }
}

private data class QuickToggle(
    val label: String,
    val state: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val active: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun QuickToggleCell(
    toggle: QuickToggle,
    theme: WinTheme,
    modifier: Modifier = Modifier
) {
    val bg = if (toggle.active) theme.accentColor
             else if (theme.isDark) Color(0x22FFFFFF) else Color(0x11000000)
    val contentColor = if (toggle.active) Color.White
                       else if (theme.isDark) Color.White else Color.Black

    Column(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = toggle.onClick
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            toggle.icon, null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = toggle.label,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = toggle.state,
            color = contentColor.copy(alpha = 0.7f),
            fontSize = 9.sp
        )
    }
}

// ============================================================
// 托盘时钟样式设置（v2.10）— 长按任务栏时间弹出
// ============================================================

/**
 * 时钟样式设置弹窗（v2.11 重排）：
 * - 顶部实时预览（与任务栏时钟同款渲染）
 * - 显示模式：数字 / 表盘 / 液晶
 * - 字号：8..18sp 滑杆（含"自动"）
 * - 排版：两行 / 单行
 * - 显示日期 / 显示秒数 / 24 小时制开关
 * - 恢复默认 / 完成
 *
 * v2.11 修复"内容太紧凑/显示不完整"：
 * - 去掉固定 320x434dp 尺寸（旧内容实际需要约 500dp 高，固定高度导致溢出被裁剪）；
 * - 高度改为随内容自适应，并由外部通过 modifier.heightIn(max=…) 钳制在屏幕内；
 * - 内容区加 verticalScroll，小屏幕上可滚动查看全部选项；
 * - 加宽到 340dp，分组间距统一 14dp，开关行加高，滑杆加标签区。
 */
@Composable
fun TrayClockSettingsFlyout(
    theme: WinTheme,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    val app = LinBoxApp.get()
    val scope = rememberCoroutineScope()
    val popupColor = if (theme.isDark) Color(0xE6323232) else Color(0xE6F9F9F9)
    val fg = if (theme.isDark) Color.White else Color.Black
    val sub = theme.secondaryTextColor

    val mode by app.settingsStore.trayClockMode.collectAsState(initial = "digital")
    val fontSize by app.settingsStore.trayClockFontSize.collectAsState(initial = 0f)
    val showDate by app.settingsStore.trayShowDate.collectAsState(initial = true)
    val trayLayout by app.settingsStore.trayLayout.collectAsState(initial = "stacked")
    val showSeconds by app.settingsStore.showSeconds.collectAsState(initial = false)
    val time24 by app.settingsStore.timeFormat24h.collectAsState(initial = true)

    // 实时预览时钟
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }

    Box(
        modifier = modifier
            .width(340.dp)
            .shadow(16.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(popupColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 标题栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "时钟样式",
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Close, "关闭",
                    tint = sub,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss
                        )
                        .padding(4.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // ===== 实时预览 =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (theme.isDark) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)
                    ),
                contentAlignment = Alignment.Center
            ) {
                ClockPreview(
                    theme = theme,
                    tick = tick,
                    mode = mode,
                    fontSize = fontSize,
                    showDate = showDate,
                    layout = trayLayout,
                    showSeconds = showSeconds,
                    time24 = time24
                )
            }

            Spacer(Modifier.height(14.dp))

            // ===== 显示模式 =====
            Text("显示模式", color = sub, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StyleChip("数字", mode == "digital", theme) {
                    scope.launch { app.settingsStore.setTrayClockMode("digital") }
                }
                StyleChip("表盘", mode == "clock", theme) {
                    scope.launch { app.settingsStore.setTrayClockMode("clock") }
                }
                StyleChip("液晶", mode == "lcd", theme) {
                    scope.launch { app.settingsStore.setTrayClockMode("lcd") }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ===== 字号 =====
            Text(
                "字号：${if (fontSize < 8f) "自动（跟随任务栏高度）" else "${fontSize.roundToInt()}sp"}",
                color = sub,
                fontSize = 11.sp
            )
            Slider(
                value = if (fontSize < 8f) 8f else fontSize,
                onValueChange = {
                    scope.launch { app.settingsStore.setTrayClockFontSize(it) }
                },
                valueRange = 8f..18f
            )
            // 恢复"自动"字号：把滑杆拖到最左（8sp）后再点此处回到自适应
            Text(
                if (fontSize < 8f) "当前为自动字号" else "拖到最左端并点击可恢复自动",
                color = sub.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
            if (fontSize >= 8f) {
                TextButton(onClick = {
                    scope.launch { app.settingsStore.setTrayClockFontSize(0f) }
                }) {
                    Text("恢复自动字号", fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            // ===== 排版 =====
            Text("排版", color = sub, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StyleChip("两行（时间/日期）", trayLayout == "stacked", theme) {
                    scope.launch { app.settingsStore.setTrayLayout("stacked") }
                }
                StyleChip("单行", trayLayout == "inline", theme) {
                    scope.launch { app.settingsStore.setTrayLayout("inline") }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ===== 开关组 =====
            ToggleRow("显示日期", showDate, theme) {
                scope.launch { app.settingsStore.setTrayShowDate(it) }
            }
            ToggleRow("显示秒数", showSeconds, theme) {
                scope.launch { app.settingsStore.setShowSeconds(it) }
            }
            ToggleRow("24 小时制", time24, theme) {
                scope.launch { app.settingsStore.setTimeFormat24h(it) }
            }

            Spacer(Modifier.height(8.dp))

            // ===== 底部按钮 =====
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    scope.launch {
                        app.settingsStore.setTrayClockMode("digital")
                        app.settingsStore.setTrayClockFontSize(0f)
                        app.settingsStore.setTrayShowDate(true)
                        app.settingsStore.setTrayLayout("stacked")
                    }
                }) {
                    Text("恢复默认", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss) {
                    Text("完成", fontSize = 12.sp)
                }
            }
        }
    }
}

/** 时钟样式弹窗的实时预览（与任务栏 TrayClock 同款渲染逻辑） */
@Composable
private fun ClockPreview(
    theme: WinTheme,
    tick: Long,
    mode: String,
    fontSize: Float,
    showDate: Boolean,
    layout: String,
    showSeconds: Boolean,
    time24: Boolean
) {
    val timePattern = when {
        showSeconds && time24 -> "HH:mm:ss"
        showSeconds -> "hh:mm:ss a"
        time24 -> "HH:mm"
        else -> "hh:mm a"
    }
    val timeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(tick))
    val dateStr = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(tick))
    val timeSize = if (fontSize >= 8f) fontSize.sp else 12.sp
    val dateSize = (timeSize.value - 1f).coerceAtLeast(8f).sp

    when (mode) {
        // 表盘预览
        "clock" -> Row(verticalAlignment = Alignment.CenterVertically) {
            AnalogClockCanvas(
                size = 34.dp,
                tick = tick,
                showSeconds = showSeconds,
                color = theme.taskbarClockColor,
                accent = theme.accentColor
            )
            if (showDate) {
                Spacer(Modifier.width(6.dp))
                Text(dateStr, color = theme.taskbarClockColor, fontSize = 12.sp)
            }
        }
        // 液晶预览
        "lcd" -> {
            val lcdColor = if (theme.isDark) Color(0xFF4AF2A1) else Color(0xFF0B7A4B)
            val style = TextStyle(
                color = lcdColor,
                fontSize = timeSize,
                fontFamily = FontFamily.Monospace,
                shadow = Shadow(color = lcdColor.copy(alpha = 0.75f), blurRadius = 7f)
            )
            val dateStyle = style.copy(
                fontSize = dateSize,
                shadow = Shadow(color = lcdColor.copy(alpha = 0.5f), blurRadius = 5f)
            )
            if (showDate && layout == "inline") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeStr, style = style)
                    Spacer(Modifier.width(6.dp))
                    Text(dateStr, style = dateStyle)
                }
            } else if (showDate) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(timeStr, style = style)
                    Text(dateStr, style = dateStyle)
                }
            } else {
                Text(timeStr, style = style)
            }
        }
        // 数字预览（默认）
        else -> {
            if (showDate && layout == "inline") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeStr, color = theme.taskbarClockColor, fontSize = timeSize)
                    Spacer(Modifier.width(6.dp))
                    Text(dateStr, color = theme.taskbarClockColor.copy(alpha = 0.75f), fontSize = dateSize)
                }
            } else if (showDate) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(timeStr, color = theme.taskbarClockColor, fontSize = timeSize)
                    Text(dateStr, color = theme.taskbarClockColor.copy(alpha = 0.75f), fontSize = dateSize)
                }
            } else {
                Text(timeStr, color = theme.taskbarClockColor, fontSize = timeSize)
            }
        }
    }
}

/** 选项芯片（时钟样式弹窗用） */
@Composable
private fun StyleChip(label: String, selected: Boolean, theme: WinTheme, onClick: () -> Unit) {
    val bg = if (selected) theme.accentColor
             else if (theme.isDark) Color(0x22FFFFFF) else Color(0x11000000)
    val fg = if (selected) Color.White
             else if (theme.isDark) Color.White else Color.Black
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/** 开关行（时钟样式弹窗用） */
@Composable
private fun ToggleRow(label: String, checked: Boolean, theme: WinTheme, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
