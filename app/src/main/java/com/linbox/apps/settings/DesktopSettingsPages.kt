package com.linbox.apps.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.desktop.LockController
import com.linbox.core.desktop.hashPin
import com.linbox.core.input.keyboardAware
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowManager
import com.linbox.util.L
import com.linbox.util.L10n
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ============================================================
// v2.17 锁屏设置页（设置 → 个性化 → 锁屏界面 打开的独立小窗）
// ============================================================

/**
 * 锁屏设置：
 * - 锁屏开关（关闭后自动锁屏停用、开始菜单不显示"锁定"）
 * - 锁屏独立壁纸：支持图片与视频（经应用内文件资源管理器选择）
 * - 锁屏密码（4-8 位数字 PIN，SHA-256 哈希存储）
 * - 自动锁屏定时（从不 / 1 / 5 / 15 / 30 / 60 分钟）
 * - 立即锁定
 */
@Composable
internal fun LockSettingsPage() {
    val theme = LocalWinTheme.current
    val app = LinBoxApp.get()
    val context = LocalContext.current
    val scope0 = rememberCoroutineScope()
    val wm = remember { WindowManager.get() }

    val lockEnabled by app.settingsStore.lockEnabled.collectAsState(initial = true)
    val lockWallpaper by app.settingsStore.lockWallpaper.collectAsState(initial = null)
    val pinHash by app.settingsStore.lockPinHash.collectAsState(initial = "")
    val autoLockMinutes by app.settingsStore.autoLockMinutes.collectAsState(initial = 0)

    // 密码设置交互状态
    var pinEditing by remember { mutableStateOf(false) }
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    val pinSet = pinHash.isNotBlank()

    SubPageHeader(L("锁屏设置"), null)

    // ===== 锁屏开关 =====
    SettingsCard(
        icon = Icons.Default.Lock,
        iconBackgroundColor = Color(0xFF0078D7),
        title = L("锁屏"),
        subtitle = if (lockEnabled) L("已开启 · 可从开始菜单锁定或按定时自动锁定")
        else L("已关闭 · 自动锁屏与锁定入口停用"),
        trailingContent = {
            ToggleSwitch(lockEnabled) { v ->
                scope0.launch { app.settingsStore.setLockEnabled(v) }
            }
        }
    )
    Spacer(Modifier.height(10.dp))

    // ===== 锁屏壁纸（图片 / 视频） =====
    SettingsBlock(L("锁屏壁纸")) {
        val current = when {
            lockWallpaper == null -> L("未设置（跟随桌面壁纸）")
            lockWallpaper!!.startsWith("video://") -> L("视频：") + lockWallpaper!!.removePrefix("video://")
                .substringAfterLast('/')
            lockWallpaper!!.startsWith("file://") -> L("图片：") + lockWallpaper!!.removePrefix("file://")
                .substringAfterLast('/')
            else -> lockWallpaper ?: ""
        }
        Text(
            text = L("当前") + "：$current",
            color = theme.secondaryTextColor,
            fontSize = 11.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = L("选择后锁屏界面使用独立壁纸；视频将静音循环播放"),
            color = theme.secondaryTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    wm.open(
                        appId = "file_explorer",
                        title = L10n.t("选择锁屏图片"),
                        launchMode = AppRegistry.get("file_explorer")?.launchMode
                            ?: LaunchMode.FLOATING,
                        launchArgs = mapOf("pickMode" to "lock_wallpaper"),
                        initialWidth = 920,
                        initialHeight = 620
                    )
                },
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(L("选择图片"), fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    wm.open(
                        appId = "file_explorer",
                        title = L10n.t("选择锁屏视频"),
                        launchMode = AppRegistry.get("file_explorer")?.launchMode
                            ?: LaunchMode.FLOATING,
                        launchArgs = mapOf("pickMode" to "lock_wallpaper_video"),
                        initialWidth = 920,
                        initialHeight = 620
                    )
                },
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(Icons.Default.PlayCircle, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(L("选择视频"), fontSize = 12.sp)
            }
            if (lockWallpaper != null) {
                OutlinedButton(
                    onClick = { scope0.launch { app.settingsStore.setLockWallpaper(null) } },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(L("恢复默认"), fontSize = 12.sp)
                }
            }
        }
    }

    // ===== 锁屏密码 =====
    SettingsBlock(L("锁屏密码")) {
        Text(
            text = if (pinSet) L("已设置锁屏密码（4-8 位数字）") else L("未设置 · 解锁无需密码"),
            color = theme.secondaryTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        if (!pinEditing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        pinEditing = true
                        pin1 = ""
                        pin2 = ""
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (pinSet) L("修改密码") else L("设置密码"), fontSize = 12.sp)
                }
                if (pinSet) {
                    OutlinedButton(
                        onClick = {
                            scope0.launch { app.settingsStore.setLockPinHash("") }
                            Toast.makeText(context, L10n.t("已移除锁屏密码"), Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(L("移除密码"), fontSize = 12.sp)
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = pin1,
                onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pin1 = it },
                label = { Text(L("输入密码（4-8 位数字）"), fontSize = 11.sp) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier
                    .fillMaxWidth()
                    .keyboardAware(
                        value = { pin1 },
                        onValue = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pin1 = it },
                        singleLine = true
                    ),
                shape = RoundedCornerShape(6.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pin2,
                onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pin2 = it },
                label = { Text(L("确认密码"), fontSize = 11.sp) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier
                    .fillMaxWidth()
                    .keyboardAware(
                        value = { pin2 },
                        onValue = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pin2 = it },
                        singleLine = true
                    ),
                shape = RoundedCornerShape(6.dp)
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        when {
                            pin1.length < 4 ->
                                Toast.makeText(context, L10n.t("密码至少 4 位数字"), Toast.LENGTH_SHORT).show()
                            pin1 != pin2 ->
                                Toast.makeText(context, L10n.t("两次输入的密码不一致"), Toast.LENGTH_SHORT).show()
                            else -> {
                                scope0.launch { app.settingsStore.setLockPinHash(hashPin(pin1)) }
                                pinEditing = false
                                pin1 = ""
                                pin2 = ""
                                Toast.makeText(context, L10n.t("锁屏密码已设置"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp)
                ) { Text(L("保存"), fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { pinEditing = false },
                    shape = RoundedCornerShape(6.dp)
                ) { Text(L("取消"), fontSize = 12.sp) }
            }
        }
    }

    // ===== 自动锁屏 =====
    SettingsBlock(L("自动锁屏")) {
        Text(
            text = L("桌面无操作达到设定时间后自动锁定"),
            color = theme.secondaryTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedOption(L("从不"), autoLockMinutes == 0) {
                scope0.launch { app.settingsStore.setAutoLockMinutes(0) }
            }
            SegmentedOption(L("1 分钟"), autoLockMinutes == 1) {
                scope0.launch { app.settingsStore.setAutoLockMinutes(1) }
            }
            SegmentedOption(L("5 分钟"), autoLockMinutes == 5) {
                scope0.launch { app.settingsStore.setAutoLockMinutes(5) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedOption(L("15 分钟"), autoLockMinutes == 15) {
                scope0.launch { app.settingsStore.setAutoLockMinutes(15) }
            }
            SegmentedOption(L("30 分钟"), autoLockMinutes == 30) {
                scope0.launch { app.settingsStore.setAutoLockMinutes(30) }
            }
            SegmentedOption(L("1 小时"), autoLockMinutes == 60) {
                scope0.launch { app.settingsStore.setAutoLockMinutes(60) }
            }
        }
    }
    Spacer(Modifier.height(4.dp))

    // ===== 立即锁定 =====
    SettingsCard(
        icon = Icons.Default.Lock,
        iconBackgroundColor = Color(0xFF00B294),
        title = L("立即锁定"),
        subtitle = L("模拟 Win + L，上滑或点击解锁（设置了密码则需验证）"),
        onClick = { LockController.lock() }
    )
}

// ============================================================
// v2.17 桌面设置（设置中心左导航"桌面设置"，替代旧"账户"页）
// ============================================================

/**
 * 桌面设置：把旧"账户"页（跳转手机系统设置）替换为桌面自身的真实设置：
 * - 账户：开始菜单显示的用户名 / 头像（emoji）
 * - 桌面图标：排序方式 / 重置自定义顺序 / 图标大小
 * - 桌面行为：图标打开方式（单击 / 双击）
 * - 自启动：选择桌面启动时自动打开的内置应用
 */
@Composable
internal fun DesktopSettingsSection() {
    val theme = LocalWinTheme.current
    val app = LinBoxApp.get()
    val context = LocalContext.current
    val scope0 = rememberCoroutineScope()

    val userName by app.settingsStore.userName.collectAsState(initial = "LinBox")
    val userAvatar by app.settingsStore.userAvatar.collectAsState(initial = "")
    val desktopSort by app.settingsStore.desktopSort.collectAsState(initial = "default")
    val iconSize by app.settingsStore.iconSize.collectAsState(initial = 48f)
    val clickMode by app.settingsStore.mouseClickMode.collectAsState(initial = "single")
    val autostart by app.settingsStore.autostartApps.collectAsState(initial = "")

    var nameInput by remember(userName) { mutableStateOf(userName) }
    var showAutostartPicker by remember { mutableStateOf(false) }
    val autostartSet = remember(autostart) {
        autostart.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    SectionHeader(L("桌面设置"), L("账户、桌面图标、自启动、桌面行为"))

    // ===== 账户（用户名 / 头像，作用于开始菜单） =====
    SettingsBlock(L("账户")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (userAvatar.isNotBlank()) {
                Text(text = userAvatar, fontSize = 26.sp)
            } else {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = theme.accentColor,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = userName,
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = L("本地账户 · 管理员权限"),
                    color = theme.secondaryTextColor,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = L("头像"),
            color = theme.secondaryTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // "" = 默认图标
            AvatarOption(
                emoji = "",
                selected = userAvatar.isBlank(),
                theme = theme
            ) { scope0.launch { app.settingsStore.setUserAvatar("") } }
            listOf("👤", "🐧", "🦊", "🐼", "🤖", "👻", "🐱", "🎃").forEach { emoji ->
                AvatarOption(
                    emoji = emoji,
                    selected = userAvatar == emoji,
                    theme = theme
                ) { scope0.launch { app.settingsStore.setUserAvatar(emoji) } }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = L("用户名"),
            color = theme.secondaryTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .keyboardAware(
                    value = { nameInput },
                    onValue = { nameInput = it },
                    singleLine = true
                ),
            shape = RoundedCornerShape(6.dp),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope0.launch {
                        app.settingsStore.setUserName(nameInput)
                        Toast.makeText(context, L10n.t("已保存"), Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(6.dp)
            ) { Text(L("保存"), fontSize = 12.sp) }
        }
    }

    // ===== 桌面图标 =====
    SettingsBlock(L("桌面图标")) {
        Text(
            text = L("排序方式"),
            color = theme.secondaryTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedOption(L("默认"), desktopSort == "default") {
                scope0.launch { app.settingsStore.setDesktopSort("default") }
            }
            SegmentedOption(L("按名称"), desktopSort == "name") {
                scope0.launch { app.settingsStore.setDesktopSort("name") }
            }
            SegmentedOption(L("按类型"), desktopSort == "type") {
                scope0.launch { app.settingsStore.setDesktopSort("type") }
            }
        }
        Spacer(Modifier.height(10.dp))
        SettingsCard(
            icon = Icons.Default.Refresh,
            iconBackgroundColor = Color(0xFF00B294),
            title = L("重置图标顺序"),
            subtitle = L("清除拖动排序，恢复默认排列"),
            onClick = {
                scope0.launch {
                    app.settingsStore.setDesktopIconOrder("")
                    Toast.makeText(context, L10n.t("已重置图标顺序"), Toast.LENGTH_SHORT).show()
                }
            }
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = L("图标大小"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${iconSize.roundToInt()}dp",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
        androidx.compose.material3.Slider(
            value = iconSize,
            onValueChange = { scope0.launch { app.settingsStore.setIconSize(it) } },
            valueRange = 28f..72f
        )
    }

    // ===== 桌面行为 =====
    SettingsBlock(L("桌面行为")) {
        Text(
            text = L("图标打开方式"),
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
    }

    // ===== 自启动应用 =====
    SettingsCard(
        icon = Icons.Default.SmartButton,
        iconBackgroundColor = Color(0xFF8764B8),
        title = L("自启动应用"),
        subtitle = if (autostartSet.isEmpty()) L("桌面启动时不自动打开任何应用")
        else L("已选 ${autostartSet.size} 个 · 桌面启动时自动打开"),
        onClick = { showAutostartPicker = !showAutostartPicker }
    )
    if (showAutostartPicker) {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(theme.cardBackgroundColor)
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = L("选择桌面启动时自动打开的应用"),
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                AppRegistry.all().sortedBy { it.displayName }.forEach { appDef ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = if (appDef.id in autostartSet)
                                    autostartSet - appDef.id
                                else
                                    autostartSet + appDef.id
                                scope0.launch { app.settingsStore.setAutostartApps(next.toList()) }
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = appDef.id in autostartSet,
                            onCheckedChange = {
                                val next = if (it) autostartSet + appDef.id
                                else autostartSet - appDef.id
                                scope0.launch { app.settingsStore.setAutostartApps(next.toList()) }
                            }
                        )
                        Text(
                            text = appDef.displayName,
                            color = if (theme.isDark) Color.White else Color.Black,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = appDef.id,
                            color = theme.secondaryTextColor,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

/** 头像 emoji 选项（圆形按钮，"" 表示默认账户图标） */
@Composable
private fun AvatarOption(
    emoji: String,
    selected: Boolean,
    theme: com.linbox.core.theme.WinTheme,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(
                if (selected) theme.accentColor.copy(alpha = 0.18f)
                else theme.windowBackgroundColor
            )
            .border(
                width = 1.dp,
                color = if (selected) theme.accentColor else theme.dividerColor,
                shape = RoundedCornerShape(17.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (emoji.isBlank()) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = theme.accentColor,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(text = emoji, fontSize = 16.sp)
        }
    }
}
