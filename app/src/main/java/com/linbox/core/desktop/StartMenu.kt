package com.linbox.core.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.input.keyboardAware
import com.linbox.core.theme.WinTheme
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.WindowManager
import com.linbox.util.L
import com.linbox.util.L10n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 开始菜单：Win11 真实风格
 *
 * v2.17 新增：
 * - 读取安卓手机应用：三个分页「已固定 / 手机应用 / 系统应用」，
 *   "手机应用"列出用户安装的第三方应用，"系统应用"单独分页展示系统内置应用；
 * - 点击手机应用直接通过显式 Component 拉起；
 * - 搜索同时覆盖内置应用 + 手机全部应用；
 * - 底部用户名/头像读取"桌面设置"，点击打开设置中心的桌面设置页；
 * - 电源菜单的"锁定"跟随锁屏总开关（锁屏关闭时隐藏）。
 */
@Composable
fun StartMenu(
    theme: WinTheme,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wm = remember { WindowManager.get() }
    val context = LocalContext.current
    val app = remember { LinBoxApp.get() }
    val allApps = remember { AppRegistry.all() }
    var searchText by remember { mutableStateOf("") }

    // ===== v2.17 分页：0=已固定（内置） 1=手机应用 2=系统应用 =====
    var tab by remember { mutableStateOf(0) }

    // ===== v2.17 桌面设置：用户名 / 头像 / 锁屏开关 =====
    val userName by app.settingsStore.userName.collectAsState(initial = "LinBox")
    val userAvatar by app.settingsStore.userAvatar.collectAsState(initial = "")
    val lockEnabled by app.settingsStore.lockEnabled.collectAsState(initial = true)

    // ===== v2.17 安卓手机应用（IO 线程异步加载，含图标预解码） =====
    val androidApps by produceState<List<AndroidApps.AppInfo>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { AndroidApps.load(context) }.getOrNull() ?: emptyList()
        }
    }
    val phoneApps = androidApps?.filter { !it.isSystem } ?: emptyList()
    val systemApps = androidApps?.filter { it.isSystem } ?: emptyList()

    // 搜索提交（v2.13：上移声明，供 IME 搜索回调与虚拟键盘 onEnter 共用）
    val submitSearch: () -> Unit = {
        val q = searchText.trim()
        if (q.isNotEmpty()) {
            val target = if (q.contains(".") && !q.contains(" ")) {
                if (q.startsWith("http")) q else "https://$q"
            } else {
                "https://www.bing.com/search?q=" + android.net.Uri.encode(q)
            }
            wm.open(
                appId = "browser",
                title = "浏览器",
                launchMode = com.linbox.core.window.LaunchMode.FLOATING,
                launchArgs = mapOf("url" to target),
                initialWidth = 980,
                initialHeight = 640
            )
            onDismiss()
        }
    }

    // 拉起安卓应用并关闭开始菜单
    val launchAndroid: (AndroidApps.AppInfo) -> Unit = { info ->
        if (AndroidApps.launch(context, info)) onDismiss()
    }

    Box(
        modifier = modifier
            .width(theme.startMenuWidth)
            .heightIn(min = 460.dp, max = 580.dp)
            .shadow(12.dp, theme.startMenuShape)
            .clip(theme.startMenuShape)
            .background(theme.startMenuColor.copy(alpha = theme.startMenuAlpha))
            .border(1.dp, theme.windowBorderColor, theme.startMenuShape)
            // v2.18 修复：应用列表无法滚动 —— 旧版这里用 awaitPointerEventScope
            // 无差别消费全部指针事件（含滑动 move），阻断了对 LazyVerticalGrid
            // 的滚动判定。改为 detectTapGestures 空回调：只消费轻点/双击/长按
            //（防止点击菜单空白处时触发背景遮罩的关闭），滑动不再被消费，
            // 应用网格恢复正常滚动。
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { },
                    onDoubleTap = { },
                    onLongPress = { }
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ===== 顶部搜索栏 =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(theme.windowBackgroundColor)
                        .border(1.dp, theme.windowBorderColor.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = L("搜索"),
                        tint = (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = if (theme.isDark) Color.White else Color.Black,
                            fontSize = 13.sp
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                        modifier = Modifier
                            .weight(1f)
                            .keyboardAware(
                                value = { searchText },
                                onValue = { searchText = it },
                                singleLine = true,
                                onEnter = submitSearch
                            ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accentColor)
                    )
                    if (searchText.isEmpty()) {
                        Text(
                            text = L("搜索应用和 Web"),
                            color = (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // ===== v2.17 分页标签（原「已固定 + 所有应用」行） =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StartMenuTab(L("已固定"), tab == 0, theme) { tab = 0 }
                StartMenuTab(L("手机应用"), tab == 1, theme) { tab = 1 }
                StartMenuTab(L("系统应用"), tab == 2, theme) { tab = 2 }
            }

            Spacer(Modifier.height(4.dp))

            // ===== 应用网格 =====
            val query = searchText.trim()
            val searching = query.isNotEmpty()

            // 搜索模式：覆盖内置应用 + 手机全部应用（用户 + 系统）
            val builtinMatches = if (searching)
                allApps.filter { it.displayName.contains(query, ignoreCase = true) } else emptyList()
            val androidMatches = if (searching)
                (androidApps ?: emptyList()).filter { it.label.contains(query, ignoreCase = true) }
                else emptyList()

            when {
                // ===== 搜索结果：内置 + 安卓混合 =====
                searching -> {
                    if (builtinMatches.isEmpty() && androidMatches.isEmpty()) {
                        EmptyMenuHint(theme, "${L("没有找到相关应用")}\n$query", Modifier.weight(1f))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(builtinMatches, key = { "bi:${it.id}" }) { appDef ->
                                BuiltInTile(appDef, theme, wm, onDismiss)
                            }
                            items(androidMatches, key = { "an:${it.pkg}/${it.activity}" }) { info ->
                                AndroidTile(info, theme, launchAndroid)
                            }
                        }
                    }
                }
                // ===== 已固定：内置应用 =====
                tab == 0 -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(allApps, key = { "bi:${it.id}" }) { appDef ->
                            BuiltInTile(appDef, theme, wm, onDismiss)
                        }
                    }
                }
                // ===== 手机应用 / 系统应用 =====
                else -> {
                    val list = if (tab == 1) phoneApps else systemApps
                    when {
                        // 加载中
                        androidApps == null -> LoadingMenuHint(theme, Modifier.weight(1f))
                        // 列表为空
                        list.isEmpty() -> EmptyMenuHint(
                            theme,
                            if (tab == 1) L("没有读取到用户安装的应用") else L("没有读取到系统应用"),
                            Modifier.weight(1f)
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(list, key = { "an:${it.pkg}/${it.activity}" }) { info ->
                                AndroidTile(info, theme, launchAndroid)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ===== 底部：用户头像 + 用户名（左）+ 电源按钮（右） =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.windowBackgroundColor.copy(alpha = 0.5f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // v2.17：点击打开设置中心的"桌面设置"（用户名/头像在此维护）
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            wm.open(
                                appId = "settings",
                                title = L10n.t("设置"),
                                launchMode = com.linbox.core.window.LaunchMode.FLOATING,
                                launchArgs = mapOf("section" to "desktop"),
                                initialWidth = 880,
                                initialHeight = 600
                            )
                            onDismiss()
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (userAvatar.isNotBlank()) {
                        Text(
                            text = userAvatar,
                            fontSize = 20.sp,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.AccountCircle,
                            contentDescription = L("用户"),
                            tint = if (theme.isDark) Color.White else Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = userName,
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // v2.14：电源按钮弹出菜单（锁定 / 关闭所有窗口）；v2.17 锁定跟随锁屏开关
                var powerMenuOpen by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(theme.buttonBackgroundColor)
                        .clickable { powerMenuOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = L("电源"),
                        tint = if (theme.isDark) Color.White else Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    DropdownMenu(
                        expanded = powerMenuOpen,
                        onDismissRequest = { powerMenuOpen = false }
                    ) {
                        if (lockEnabled) {
                            DropdownMenuItem(
                                text = { Text(L("锁定")) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Lock,
                                        null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    powerMenuOpen = false
                                    onDismiss()
                                    LockController.lock()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(L("关闭所有窗口")) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.PowerSettingsNew,
                                    null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            onClick = {
                                powerMenuOpen = false
                                wm.closeAll()
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// v2.17：开始菜单分页 / 网格瓦片 / 空态辅助组件
// ============================================================

/** 分页标签（已固定 / 手机应用 / 系统应用） */
@Composable
private fun StartMenuTab(label: String, active: Boolean, theme: WinTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (active) theme.accentColor.copy(alpha = 0.16f) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = if (active) theme.accentColor
            else (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.75f),
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 内置应用瓦片 */
@Composable
private fun BuiltInTile(
    app: com.linbox.core.window.AppDef,
    theme: WinTheme,
    wm: WindowManager,
    onDismiss: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                wm.open(
                    appId = app.id,
                    title = app.displayName,
                    launchMode = app.launchMode,
                    initialWidth = app.defaultWidth.value.toInt(),
                    initialHeight = app.defaultHeight.value.toInt()
                )
                onDismiss()
            }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(theme.accentColor.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            IconPainter(app.iconAsset, size = 28.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.displayName,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** 安卓手机应用瓦片 */
@Composable
private fun AndroidTile(
    info: AndroidApps.AppInfo,
    theme: WinTheme,
    onLaunch: (AndroidApps.AppInfo) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onLaunch(info) }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            AndroidAppIcon(packageName = info.pkg, icon = info.icon, size = 32.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = info.label,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** 网格空态提示（modifier 由调用方提供，用于 ColumnScope.weight） */
@Composable
private fun EmptyMenuHint(theme: WinTheme, text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.55f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

/** 安卓应用加载中提示（modifier 由调用方提供，用于 ColumnScope.weight） */
@Composable
private fun LoadingMenuHint(theme: WinTheme, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = theme.accentColor,
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = L("正在读取手机应用…"),
            color = (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.6f),
            fontSize = 12.sp
        )
    }
}
