package com.linbox.apps.settings

/**
 * v2.12 设置中心真实化：设备与网络分区。
 *
 * - SystemInfoDialog：真实系统信息（型号 / Android 版本 / 内存 / 存储 / 电池 / 省电模式）
 * - BluetoothDevicesSection：真实蓝牙开关（BluetoothAdapter.enable/disable）+
 *   已配对设备列表（adapter.bondedDevices）+ 应用内"添加设备"搜索配对流
 *   （ACTION_FOUND 扫描 + createBond 配对，不再跳转系统蓝牙设置）
 * - NetworkInternetSection：真实网络状态（ConnectivityManager/WifiManager）+
 *   Wi-Fi 开关 + 附近 Wi-Fi 列表（真实扫描）+ 真实流量统计（TrafficStats）
 *
 * 复用同包 SettingsApp.kt 中的 SettingsCard / SectionHeader / ToggleSwitch / AboutRow。
 */

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.linbox.LinBoxApp
import com.linbox.BuildConfig
import com.linbox.core.theme.LocalWinTheme
import com.linbox.util.SystemControl
import kotlinx.coroutines.launch

// ============================================================
// 系统信息对话框（"LinBox 设备"卡片点击弹出，全部真实数据）
// ============================================================

@Composable
internal fun SystemInfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val theme = LocalWinTheme.current
    val info = remember { SystemControl.readDeviceInfo(context) }
    val battery = remember { SystemControl.readBattery(context) }
    val powerSave = remember { SystemControl.isPowerSaveMode(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("系统信息") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AboutRow("设备名称", info.deviceName)
                AboutRow("品牌", info.brand)
                AboutRow("Android 版本", "${info.androidVersion}（API ${info.sdkInt}）")
                AboutRow("安全补丁", info.securityPatch)
                AboutRow("处理器", "${info.cpuCores} 核 · ${info.cpuAbis}")
                AboutRow(
                    "内存",
                    "${SystemControl.formatBytes(info.availRamBytes)} 可用 / ${SystemControl.formatBytes(info.totalRamBytes)}"
                )
                AboutRow(
                    "存储",
                    "${SystemControl.formatBytes(info.storageAvailBytes)} 可用 / ${SystemControl.formatBytes(info.storageTotalBytes)}"
                )
                AboutRow("屏幕", "${info.screenWidthPx} × ${info.screenHeightPx} · ${info.densityDpi}dpi")
                AboutRow("刷新率", String.format("%.1f Hz", info.refreshRateHz))
                AboutRow(
                    "电池",
                    if (battery.percent >= 0) {
                        "${battery.percent}%" + when {
                            battery.charging -> " · 充电中"
                            battery.full -> " · 已充满"
                            else -> ""
                        }
                    } else "未知"
                )
                AboutRow("省电模式", if (powerSave) "已开启" else "关闭")
                AboutRow("LinBox 版本", BuildConfig.VERSION_NAME)
                AboutRow("设备 ID", "LINBOX-${SystemControl.deviceId(context)}")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = theme.accentColor) }
        }
    )
}

// ============================================================
// 蓝牙和设备（真实控制）
// ============================================================

@Composable
internal fun BluetoothDevicesSection() {
    val context = LocalContext.current
    val app = LinBoxApp.get()
    val theme = LocalWinTheme.current
    val scope = rememberCoroutineScope()

    // v2.13：鼠标/键盘设置子页（应用内，替代旧的零散卡片）
    var showMousePage by remember { mutableStateOf(false) }
    var showKeyboardPage by remember { mutableStateOf(false) }
    if (showMousePage) {
        MouseSettingsPage(onBack = { showMousePage = false })
        return
    }
    if (showKeyboardPage) {
        KeyboardSettingsPage(onBack = { showKeyboardPage = false })
        return
    }

    // ===== 真实蓝牙适配器 =====
    val adapter = remember {
        runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        }.getOrNull()
    }
    var btEnabled by remember {
        mutableStateOf(runCatching { adapter?.isEnabled == true }.getOrDefault(false))
    }
    var hasConnectPerm by remember { mutableStateOf(hasBluetoothConnect(context)) }
    var bonded by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var showAddDevice by remember { mutableStateOf(false) }

    fun refreshBonded() {
        bonded = runCatching {
            if (adapter?.isEnabled == true) adapter.bondedDevices?.toList().orEmpty() else emptyList()
        }.getOrDefault(emptyList())
    }

    // ===== 蓝牙开关权限申请（Android 12+ 需要 BLUETOOTH_CONNECT） =====
    var pendingBtEnable by remember { mutableStateOf<Boolean?>(null) }
    val btPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasConnectPerm = grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        val want = pendingBtEnable
        pendingBtEnable = null
        if (hasConnectPerm && want != null) {
            val ok = runCatching {
                if (want) adapter?.enable() else adapter?.disable()
                true
            }.getOrDefault(false)
            if (ok) {
                btEnabled = want
                Toast.makeText(
                    context, if (want) "已开启蓝牙" else "已关闭蓝牙", Toast.LENGTH_SHORT
                ).show()
                if (want) refreshBonded()
            }
        }
    }

    fun switchBluetooth(want: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasConnectPerm) {
            pendingBtEnable = want
            btPermLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        val ok = runCatching {
            if (want) adapter?.enable() else adapter?.disable()
            true
        }.getOrDefault(false)
        if (ok) {
            btEnabled = want
            Toast.makeText(
                context, if (want) "已开启蓝牙" else "已关闭蓝牙", Toast.LENGTH_SHORT
            ).show()
            if (want) refreshBonded()
        } else {
            Toast.makeText(context, "蓝牙操作失败，已打开系统蓝牙设置", Toast.LENGTH_SHORT).show()
            openPanel(context, AndroidSettings.ACTION_BLUETOOTH_SETTINGS, "蓝牙设置")
        }
    }

    // ===== 蓝牙状态/配对变化实时刷新 =====
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = i.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                        )
                        btEnabled = state == BluetoothAdapter.STATE_ON
                        if (state == BluetoothAdapter.STATE_ON) refreshBonded()
                        else bonded = emptyList()
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshBonded()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(btEnabled) { if (btEnabled) refreshBonded() }

    SectionHeader("蓝牙和设备", "蓝牙、设备配对、鼠标、键盘、触摸")

    // ===== 添加设备（v2.12：应用内搜索配对，不再跳转系统设置） =====
    SettingsCard(
        icon = Icons.Default.Add,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "添加设备",
        subtitle = "搜索并配对附近的蓝牙设备",
        onClick = {
            when {
                adapter == null -> Toast.makeText(context, "此设备不支持蓝牙", Toast.LENGTH_SHORT).show()
                !btEnabled -> Toast.makeText(context, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
                else -> showAddDevice = true
            }
        }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 蓝牙（真实开关手机蓝牙） =====
    SettingsCard(
        icon = Icons.Default.Bluetooth,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "蓝牙",
        subtitle = when {
            adapter == null -> "此设备不支持蓝牙"
            btEnabled -> "已开启 · ${bonded.size} 个已配对设备"
            else -> "关闭"
        },
        trailingContent = { ToggleSwitch(btEnabled) { switchBluetooth(!btEnabled) } }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 已配对设备（真实读取手机蓝牙配对记录） =====
    if (btEnabled) {
        Text(
            "已配对设备 (${bonded.size})",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        if (bonded.isEmpty()) {
            Text(
                "暂无已配对设备，点击上方“添加设备”搜索并配对新设备",
                color = theme.secondaryTextColor,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bonded.forEach { dev -> BondedDeviceRow(dev) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ===== 鼠标（v2.13：应用内子页） =====
    SettingsCard(
        icon = Icons.Default.Mouse,
        iconBackgroundColor = Color(0xFF00B294),
        title = "鼠标",
        subtitle = "指针主题、大小、单击/双击打开、右键手势",
        onClick = { showMousePage = true }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 键盘（v2.13：应用内子页，含振动/触摸反馈/布局/主题） =====
    SettingsCard(
        icon = Icons.Default.Keyboard,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "键盘",
        subtitle = "布局（功能键行/小键盘）、大小、主题、位置、振动与触摸反馈",
        onClick = { showKeyboardPage = true }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 虚拟游戏手柄（v2.15：开启 + 悬浮设置窗） =====
    val gamepadEnabled by app.settingsStore.gamepadEnabled.collectAsState(initial = false)
    SettingsCard(
        icon = Icons.Default.SportsEsports,
        iconBackgroundColor = Color(0xFF107C10),
        title = "虚拟游戏手柄",
        subtitle = "摇杆/十字键/按钮布局、键鼠映射、方向键↔WASD（游戏时显示）",
        onClick = {
            scope.launch { app.settingsStore.setGamepadEnabled(!gamepadEnabled) }
            com.linbox.core.input.gamepad.GamepadController.settingsOpen = true
        },
        trailingContent = {
            ToggleSwitch(gamepadEnabled) { v ->
                scope.launch { app.settingsStore.setGamepadEnabled(v) }
            }
        }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 外设（v2.13：应用内小窗，不再跳系统设置） =====
    SettingsCard(
        icon = Icons.Default.Print,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "打印机和扫描仪",
        subtitle = "打印服务、测试打印、打印任务（应用内小窗）",
        onClick = { openSettingsSection("printers", "打印机和扫描仪", 560, 520) }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Cast,
        iconBackgroundColor = Color(0xFF8E8CD8),
        title = "无线显示器",
        subtitle = "投屏路由、可用显示器、连接（应用内小窗）",
        onClick = { openSettingsSection("cast", "无线显示器", 520, 520) }
    )

    // ===== 添加设备对话框（应用内搜索 + 配对） =====
    if (showAddDevice) {
        AddBluetoothDeviceDialog(adapter = adapter, onDismiss = { showAddDevice = false })
    }
}

/** 已配对设备行 */
@Composable
private fun BondedDeviceRow(dev: BluetoothDevice) {
    val theme = LocalWinTheme.current
    val name = remember(dev.address) { runCatching { dev.name }.getOrNull() } ?: dev.address
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            bluetoothDeviceIcon(dev), null,
            tint = if (theme.isDark) Color.White else Color.Black,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 13.sp
            )
            Text(dev.address, color = theme.secondaryTextColor, fontSize = 11.sp)
        }
        Text("已配对", color = theme.secondaryTextColor, fontSize = 11.sp)
    }
}

/** 搜索到的设备行（点击发起配对） */
@Composable
private fun FoundDeviceRow(
    dev: BluetoothDevice,
    bondState: Int,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    val name = remember(dev.address) { runCatching { dev.name }.getOrNull() } ?: "未知设备"
    val bondText: String
    val bondColor: Color
    when (bondState) {
        BluetoothDevice.BOND_BONDED -> { bondText = "已配对"; bondColor = theme.accentColor }
        BluetoothDevice.BOND_BONDING -> { bondText = "配对中…"; bondColor = theme.secondaryTextColor }
        else -> { bondText = "未配对"; bondColor = theme.secondaryTextColor }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .background(theme.cardBackgroundColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            bluetoothDeviceIcon(dev), null,
            tint = if (theme.isDark) Color.White else Color.Black,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 13.sp
            )
            Text(dev.address, color = theme.secondaryTextColor, fontSize = 10.sp)
        }
        Text(bondText, color = bondColor, fontSize = 11.sp)
    }
}

/**
 * 添加设备对话框：真实扫描附近蓝牙设备（ACTION_FOUND），
 * 点击设备调用 createBond() 发起配对（系统配对确认弹窗由 OS 显示）。
 */
@Composable
private fun AddBluetoothDeviceDialog(
    adapter: BluetoothAdapter?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val theme = LocalWinTheme.current

    var discovered by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var bondStates by remember { mutableStateOf(mapOf<String, Int>()) }
    var permsAsked by remember { mutableStateOf(false) }

    // 搜索所需权限：Android 12+ 为 BLUETOOTH_SCAN/CONNECT；更早版本为位置权限
    fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPerms(): Boolean = requiredPerms().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun startScan() {
        if (!hasPerms()) {
            Toast.makeText(context, "请先授予所需权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (adapter?.isEnabled != true) {
            Toast.makeText(context, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        discovered = emptyList()
        val ok = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        scanning = ok
        if (!ok) Toast.makeText(context, "无法开始搜索，请稍后重试", Toast.LENGTH_SHORT).show()
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScan()
        else Toast.makeText(context, "需要权限才能搜索附近的蓝牙设备", Toast.LENGTH_SHORT).show()
    }

    // 打开对话框即申请权限并开始搜索
    LaunchedEffect(Unit) {
        if (!permsAsked) {
            permsAsked = true
            if (hasPerms()) startScan() else permLauncher.launch(requiredPerms())
        }
    }

    // 搜索 / 配对状态广播（均为系统保护广播）
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = runCatching {
                            IntentCompat.getParcelableExtra(
                                i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                            )
                        }.getOrNull()
                        if (dev != null && discovered.none { it.address == dev.address }) {
                            discovered = discovered + dev
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> scanning = true
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val dev = runCatching {
                            IntentCompat.getParcelableExtra(
                                i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                            )
                        }.getOrNull()
                        val state = i.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE
                        )
                        if (dev != null) bondStates = bondStates + (dev.address to state)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            runCatching { adapter?.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加设备") },
        text = {
            Column {
                Text(
                    when {
                        scanning -> "正在搜索附近设备…"
                        discovered.isEmpty() -> "未发现设备。请确保设备已进入配对模式。"
                        else -> "点击设备开始配对"
                    },
                    color = theme.secondaryTextColor,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(discovered, key = { it.address }) { dev ->
                        FoundDeviceRow(
                            dev = dev,
                            bondState = bondStates[dev.address]
                                ?: runCatching { dev.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
                        ) { pairDevice(context, dev) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { startScan() }) { Text("重新搜索") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 发起配对（系统会弹出配对确认框，无需跳转设置页） */
private fun pairDevice(context: Context, dev: BluetoothDevice) {
    runCatching {
        val state = runCatching { dev.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
        if (state == BluetoothDevice.BOND_BONDED) {
            Toast.makeText(context, "该设备已配对", Toast.LENGTH_SHORT).show()
        } else {
            val queued = dev.createBond()
            if (!queued) {
                Toast.makeText(context, "配对请求未发起，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }.onFailure {
        Toast.makeText(context, "配对失败：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 按设备大类选择图标 */
private fun bluetoothDeviceIcon(dev: BluetoothDevice): ImageVector {
    val major = runCatching { dev.bluetoothClass?.majorDeviceClass }.getOrNull()
    return when (major) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> Icons.Default.Headset
        BluetoothClass.Device.Major.PERIPHERAL -> Icons.Default.Mouse
        BluetoothClass.Device.Major.PHONE -> Icons.Default.Smartphone
        BluetoothClass.Device.Major.COMPUTER -> Icons.Default.Computer
        else -> Icons.Default.Bluetooth
    }
}

private fun hasBluetoothConnect(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

// ============================================================
// 网络和 Internet（真实状态与控制）
// ============================================================

@Composable
internal fun NetworkInternetSection() {
    val context = LocalContext.current
    val theme = LocalWinTheme.current

    val connManager = remember {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val wifiManager = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    // ===== 实时状态读取 =====
    fun netType(): String {
        val caps = runCatching {
            connManager.getNetworkCapabilities(connManager.activeNetwork)
        }.getOrNull() ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "other"
            else -> "none"
        }
    }

    fun online(): Boolean = runCatching {
        connManager.getNetworkCapabilities(connManager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    var wifiEnabled by remember {
        // 注意 "== true"：wifiManager 可空，缺了它整个表达式会变成 Boolean?，
        // 导致后续 !wifiEnabled / ToggleSwitch(wifiEnabled) 报可空类型错误
        mutableStateOf(runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false))
    }
    var netTypeName by remember { mutableStateOf(netType()) }
    var isOnline by remember { mutableStateOf(online()) }
    var ssid by remember { mutableStateOf<String?>(null) }
    var linkSpeed by remember { mutableStateOf(0) }
    var ipAddress by remember { mutableStateOf("") }

    fun refreshWifi() {
        wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)
        val info = runCatching { wifiManager?.connectionInfo }.getOrNull()
        val rawSsid = info?.ssid?.removeSurrounding("\"")
        ssid = rawSsid?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        linkSpeed = info?.linkSpeed ?: 0
        ipAddress = formatIp(info?.ipAddress ?: 0)
        netTypeName = netType()
        isOnline = online()
    }

    // 网络/Wi-Fi 状态变化实时刷新（均为系统保护广播）
    DisposableEffect(Unit) {
        refreshWifi()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { refreshWifi() }
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // ===== Wi-Fi 开关（Android 9- 直接切换；10+ 受系统限制时打开互联网面板） =====
    fun switchWifi(want: Boolean) {
        val ok = runCatching {
            @Suppress("DEPRECATION")
            wifiManager?.setWifiEnabled(want) == true
        }.getOrDefault(false)
        if (ok) {
            Toast.makeText(
                context, if (want) "已开启 Wi-Fi" else "已关闭 Wi-Fi", Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(context, "系统限制，已打开互联网面板", Toast.LENGTH_SHORT).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openPanel(context, AndroidSettings.Panel.ACTION_INTERNET_CONNECTIVITY, "互联网面板")
            } else {
                openPanel(context, AndroidSettings.ACTION_WIFI_SETTINGS, "Wi-Fi 设置")
            }
        }
    }

    // ===== 附近 Wi-Fi（真实扫描，需位置权限） =====
    var wifiList by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    fun doScan() {
        runCatching { wifiManager?.startScan() }
        wifiList = runCatching { wifiManager?.scanResults.orEmpty() }
            .getOrDefault(emptyList())
            .filter { !it.SSID.isNullOrBlank() }
            .distinctBy { it.SSID }
            .sortedByDescending { it.level }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationGranted = granted
        if (granted) doScan()
        else Toast.makeText(context, "需要位置权限才能查看附近的网络", Toast.LENGTH_SHORT).show()
    }

    fun refreshWifiList() {
        if (!locationGranted) locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        else doScan()
    }

    // ===== 移动数据 / 飞行模式（真实状态读取） =====
    val mobileDataOn = remember {
        runCatching {
            AndroidSettings.Global.getInt(context.contentResolver, "mobile_data", 0) == 1
        }.getOrDefault(false)
    }
    val airplaneOn = remember {
        runCatching {
            AndroidSettings.Global.getInt(
                context.contentResolver, AndroidSettings.Global.AIRPLANE_MODE_ON
            ) == 1
        }.getOrDefault(false)
    }

    // ===== 真实流量统计（自开机起） =====
    val mobileBytes = remember {
        (TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()).coerceAtLeast(0)
    }
    val totalBytes = remember {
        (TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()).coerceAtLeast(0)
    }

    SectionHeader("网络和 Internet", "Wi-Fi、移动网络、飞行模式、数据使用量")

    // ===== 网络状态（真实） =====
    val statusText = when {
        netTypeName == "wifi" && isOnline -> "已连接 · Wi-Fi${ssid?.let { "（$it）" } ?: ""}"
        netTypeName == "wifi" -> "Wi-Fi 已连接（无法访问 Internet）"
        netTypeName == "cellular" && isOnline -> "已连接 · 移动数据"
        netTypeName == "cellular" -> "移动数据（无法访问 Internet）"
        netTypeName == "ethernet" -> "已连接 · 以太网"
        netTypeName == "other" && isOnline -> "已连接"
        netTypeName == "other" -> "已连接（无法访问 Internet）"
        else -> "未连接任何网络"
    }
    SettingsCard(
        icon = Icons.Default.Language,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "网络状态",
        subtitle = statusText + if (ipAddress.isNotEmpty() && netTypeName != "none") " · IP $ipAddress" else ""
    )
    Spacer(Modifier.height(8.dp))

    // ===== Wi-Fi（真实开关 + 状态） =====
    SettingsCard(
        icon = Icons.Default.Wifi,
        iconBackgroundColor = Color(0xFF0067C0),
        title = "Wi-Fi",
        subtitle = when {
            !wifiEnabled -> "关闭"
            ssid != null -> "已连接 $ssid · ${linkSpeed}Mbps"
            else -> "已开启 · 未连接"
        },
        trailingContent = { ToggleSwitch(wifiEnabled) { switchWifi(!wifiEnabled) } }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 附近 Wi-Fi 列表（真实扫描） =====
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
                    "附近的 Wi-Fi 网络",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { refreshWifiList() },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("刷新", fontSize = 11.sp) }
            }
            Spacer(Modifier.height(4.dp))
            if (wifiList.isEmpty()) {
                Text(
                    if (locationGranted) "暂无扫描结果，请确保系统位置服务已开启"
                    else "需要位置权限才能查看附近的网络，点击“刷新”授予权限",
                    color = theme.secondaryTextColor,
                    fontSize = 11.sp
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    wifiList.take(10).forEach { scan ->
                        WifiNetworkRow(scan, connected = scan.SSID == ssid) {
                            connectWifi(context)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // ===== 移动数据（v2.13：应用内小窗） =====
    SettingsCard(
        icon = Icons.Default.NetworkCell,
        iconBackgroundColor = Color(0xFF00B294),
        title = "移动数据",
        subtitle = if (mobileDataOn) "已开启 · 点击打开应用内移动数据窗口" else "已关闭 · 点击打开应用内移动数据窗口",
        onClick = { openSettingsSection("mobile_data", "移动数据", 520, 540) }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 飞行模式（v2.13：应用内小窗） =====
    SettingsCard(
        icon = Icons.Default.AirplanemodeActive,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "飞行模式",
        subtitle = if (airplaneOn) "已开启 · 点击打开应用内飞行模式窗口" else "已关闭 · 点击打开应用内飞行模式窗口",
        onClick = { openSettingsSection("airplane", "飞行模式", 480, 420) }
    )
    Spacer(Modifier.height(8.dp))

    // ===== 数据使用量（真实 TrafficStats） =====
    SettingsCard(
        icon = Icons.Default.DataUsage,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "数据使用量",
        subtitle = "移动网络 ${SystemControl.formatBytes(mobileBytes)} · " +
            "全部 ${SystemControl.formatBytes(totalBytes)}（自开机起）"
    )
    Spacer(Modifier.height(8.dp))

    // ===== 系统级功能（v2.13：应用内小窗） =====
    SettingsCard(
        icon = Icons.Default.VpnKey,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "VPN",
        subtitle = "VPN 状态、始终开启配置（应用内小窗）",
        onClick = { openSettingsSection("vpn", "VPN", 500, 480) }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.MobileFriendly,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "移动热点",
        subtitle = "热点状态（应用内小窗）",
        onClick = { openSettingsSection("hotspot", "移动热点", 500, 460) }
    )
}

/** Wi-Fi 网络行（点击连接需系统确认） */
@Composable
private fun WifiNetworkRow(
    scan: ScanResult,
    connected: Boolean,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    val secure = scan.capabilities?.let {
        it.contains("WPA") || it.contains("WEP") || it.contains("PSK")
    } == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .background(theme.windowBackgroundColor)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Wifi, null,
            tint = if (connected) theme.accentColor else theme.secondaryTextColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            scan.SSID,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        if (secure) {
            Icon(
                Icons.Default.Lock, null,
                tint = theme.secondaryTextColor,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        if (connected) {
            Text("已连接", color = theme.accentColor, fontSize = 11.sp)
        }
    }
}

/** 连接新 Wi-Fi（Android 禁止三方直接连接，转系统面板确认） */
private fun connectWifi(context: Context) {
    Toast.makeText(context, "连接新网络需系统确认，已打开 Wi-Fi 面板", Toast.LENGTH_SHORT).show()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        openPanel(context, AndroidSettings.Panel.ACTION_WIFI, "Wi-Fi 面板")
    } else {
        openPanel(context, AndroidSettings.ACTION_WIFI_SETTINGS, "Wi-Fi 设置")
    }
}

private fun openPanel(context: Context, action: String, name: String) {
    val opened = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) Toast.makeText(context, "无法打开$name", Toast.LENGTH_SHORT).show()
}

private fun formatIp(ip: Int): String =
    if (ip == 0) "" else
        "${ip and 0xff}.${(ip ushr 8) and 0xff}.${(ip ushr 16) and 0xff}.${(ip ushr 24) and 0xff}"
