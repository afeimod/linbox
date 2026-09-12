package com.linbox.core.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CancelPresentation
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.linbox.LinBoxApp
import com.linbox.core.input.MouseController
import com.linbox.core.input.boundsInWindowCompat
import com.linbox.core.input.keyboardAware
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.theme.Themes
import com.linbox.core.theme.WindowsVariant
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowManager
import com.linbox.data.db.entity.ShortcutEntity
import com.linbox.data.model.DesktopItem
import com.linbox.data.model.DesktopItemType
import com.linbox.util.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 桌面右键菜单数据
 *
 * v2.11：iconItem 非空时弹出"图标右键菜单"（打开/重命名/删除/属性），
 * 为空时弹出"桌面右键菜单"（Win11 标准布局）。
 */
data class DesktopContextMenuData(
    val x: Float,
    val y: Float,
    val iconItem: DesktopItem? = null
)

/**
 * 桌面右键上下文菜单（v2.11 全面重构，Win11 标准布局；v2.16 整体尺寸缩小约一半）。
 *
 * v2.16 尺寸调整：用户反馈菜单太宽太大 —— 宽 216→112dp、行高 30→17dp、
 * 字号 12→9.5sp、图标 14→10dp，整体面积约为旧版一半；
 * 所有尺寸仍为 dp/sp，在全局 UI 缩放（LocalDensity 覆盖）下渲染，
 * 因此与整体缩放比例自动同步缩小和放大。
 *
 * 桌面空白处（双指轻点）：
 * - 查看        → 子菜单：大/中等/小图标（写入 iconSize 设置，与设置中心一致）
 * - 排序方式    → 子菜单：默认 / 按名称 / 按类型（持久化，与设置中心共用 DataStore）
 * - 刷新        → 真正触发桌面图标网格重建（重新加载图标位图）
 * - 新建快捷方式 → 快捷方式创建向导
 * - 切换主题    → 子菜单：直接切换 Win95/XP/7/10/11（与"设置-个性化"同一入口，
 *                修复旧版打开不存在的 "theme" 设置分区导致内容空白的不一致 Bug）
 * - 显示设置    → 设置中心-系统
 * - 个性化      → 设置中心-个性化（修复旧版打开"系统"分区的不一致）
 * - 打开终端 / 任务管理器 / 关闭所有窗口
 *
 * 桌面图标上（双指轻点命中图标）：
 * - 打开 / 重命名（快捷方式）/ 删除（快捷方式）/ 属性
 *
 * 菜单（v2.20 起主窗口内渲染）通过 SubcomposeLayout 先量后摆、自动钳制在
 * 屏幕内，边缘呼出不会溢出；点在菜单外关闭、菜单项随虚拟指针悬停高亮。
 */
@Composable
fun DesktopContextMenu(
    data: DesktopContextMenuData,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val theme = LocalWinTheme.current
    val wm = remember { WindowManager.get() }
    val app = LinBoxApp.get()
    val scope0 = rememberCoroutineScope()
    var showShortcutDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<DesktopItem?>(null) }
    var showPropsDialog by remember { mutableStateOf<DesktopItem?>(null) }

    // ===== v2.20：主窗口内渲染（不再用 focusable Popup）=====
    // focusable Popup 在真实手指落点处于菜单内容之外时收到 ACTION_OUTSIDE
    // 立即 dismiss —— 触控板模式"双指右键出菜单，一滑动菜单就消失"的根因。
    // 改为全屏遮罩 + 菜单面板（SubcomposeLayout 先量后摆、钳制屏内）：
    // - 点在菜单外（注入单击或 touch 模式直触）才关闭；滑动移动虚拟指针
    //   不产生任何 dismiss —— 菜单保持打开，可慢慢滑到目标项上；
    // - 菜单项随虚拟指针悬停高亮（hoveredId），轻点即选择 —— Windows
    //   右键菜单的指针交互语义；
    // - 遮罩消费落点事件，菜单打开期间点不透到桌面图标/窗口。
    var panelBounds by remember { mutableStateOf<Rect?>(null) }
    val itemRects = remember { mutableStateMapOf<String, Rect>() }
    val hoveredId by remember {
        derivedStateOf {
            val pos = MouseController.position
            itemRects.entries.firstOrNull { it.value.contains(pos) }?.key
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var up: PointerInputChange? = null
                    var dragged = false
                    while (up == null) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: continue
                        if (!c.pressed) {
                            up = c
                        } else if ((c.position - down.position).getDistance() >
                            viewConfiguration.touchSlop
                        ) {
                            dragged = true
                        }
                    }
                    // while 循环退出即保证 up 非空（智能转换），无需冗余判空
                    val upChange = up
                    // 点在面板内（含起/落任一点在内）不算"菜单外"，交给菜单项处理
                    val outside = panelBounds?.let { r ->
                        !r.contains(down.position) && !r.contains(upChange.position)
                    } ?: true
                    if (outside && !dragged) {
                        upChange.consume()
                        onDismiss()
                    }
                }
            }
    ) {
        SubcomposeLayout(modifier = Modifier.fillMaxSize()) { constraints ->
            val measurables = subcompose("menu") {
                Column(
                    modifier = Modifier
                        // v2.16：216→112dp（约一半宽），内边距/圆角同步缩小
                        .width(112.dp)
                        .background(theme.windowBackgroundColor, RoundedCornerShape(4.dp))
                        .padding(3.dp)
                        .onGloballyPositioned { panelBounds = it.boundsInWindowCompat() }
                ) {
            if (data.iconItem != null) {
                // ==================== 图标右键菜单 ====================
                val item = data.iconItem
                val isShortcut = item.type != DesktopItemType.BUILTIN_APP
                ContextMenuEntry(
                    id = "open", icon = Icons.AutoMirrored.Filled.Launch, label = L("打开"),
                    hoveredId = hoveredId, itemRects = itemRects
                ) {
                    launchDesktopItem(item, wm)
                    onDismiss()
                }
                if (isShortcut) {
                    ContextMenuDivider()
                    ContextMenuEntry(
                        id = "rename", icon = Icons.Default.DriveFileRenameOutline, label = L("重命名"),
                        hoveredId = hoveredId, itemRects = itemRects
                    ) {
                        showRenameDialog = item
                        onDismiss()
                    }
                    ContextMenuEntry(
                        id = "delete", icon = Icons.Default.Delete, label = L("删除"),
                        hoveredId = hoveredId, itemRects = itemRects
                    ) {
                        val shortcutId = item.id.removePrefix("shortcut_").toLongOrNull()
                        if (shortcutId != null) {
                            scope0.launch {
                                withContext(Dispatchers.IO) {
                                    app.database.shortcutDao().deleteById(shortcutId)
                                }
                            }
                        }
                        onDismiss()
                    }
                }
                ContextMenuDivider()
                ContextMenuEntry(
                    id = "props", icon = Icons.Default.Info, label = L("属性"),
                    hoveredId = hoveredId, itemRects = itemRects
                ) {
                    showPropsDialog = item
                    onDismiss()
                }
            } else {
                // ==================== 桌面右键菜单 ====================
                // —— 当前偏好（与设置中心共享同一 DataStore，保证处处一致） ——
                val iconSize by app.settingsStore.iconSize.collectAsState(initial = 48f)
                val sortMode by app.settingsStore.desktopSort.collectAsState(initial = "default")
                val activeTheme by app.themeManager.activeTheme.collectAsState(initial = Themes.Win11)

                // 展开中的子菜单（"view" / "sort" / "theme" / null）
                var expandedSub by remember { mutableStateOf<String?>(null) }

                // ===== 查看（图标大小） =====
                SubmenuEntry(
                    id = "view",
                    icon = Icons.Default.GridView,
                    label = L("查看"),
                    expanded = expandedSub == "view",
                    hoveredId = hoveredId,
                    itemRects = itemRects,
                    onToggle = { expandedSub = if (expandedSub == "view") null else "view" }
                ) {
                    RadioOption("viewLarge", L("大图标"), iconSize >= 60f, hoveredId, itemRects) {
                        scope0.launch { app.settingsStore.setIconSize(64f) }
                    }
                    RadioOption("viewMedium", L("中等图标"), iconSize in 40f..59f, hoveredId, itemRects) {
                        scope0.launch { app.settingsStore.setIconSize(48f) }
                    }
                    RadioOption("viewSmall", L("小图标"), iconSize < 40f, hoveredId, itemRects) {
                        scope0.launch { app.settingsStore.setIconSize(36f) }
                    }
                }

                // ===== 排序方式 =====
                SubmenuEntry(
                    id = "sort",
                    icon = Icons.Default.SortByAlpha,
                    label = L("排序方式"),
                    expanded = expandedSub == "sort",
                    hoveredId = hoveredId,
                    itemRects = itemRects,
                    onToggle = { expandedSub = if (expandedSub == "sort") null else "sort" }
                ) {
                    RadioOption("sortDefault", L("默认"), sortMode == "default", hoveredId, itemRects) {
                        scope0.launch { app.settingsStore.setDesktopSort("default") }
                    }
                    RadioOption("sortName", L("名称"), sortMode == "name", hoveredId, itemRects) {
                        scope0.launch { app.settingsStore.setDesktopSort("name") }
                    }
                    RadioOption("sortType", L("类型"), sortMode == "type", hoveredId, itemRects) {
                        scope0.launch { app.settingsStore.setDesktopSort("type") }
                    }
                }

                // ===== 刷新（真正重建桌面网格，重载图标位图） =====
                ContextMenuEntry(
                    id = "refresh", icon = Icons.Default.Refresh, label = L("刷新"),
                    hoveredId = hoveredId, itemRects = itemRects
                ) {
                    onRefresh()
                    onDismiss()
                }

                ContextMenuDivider()

                // ===== 新建快捷方式 =====
                ContextMenuEntry(
                    id = "newShortcut", icon = Icons.Default.Link, label = L("新建快捷方式"),
                    hoveredId = hoveredId, itemRects = itemRects
                ) {
                    showShortcutDialog = true
                    onDismiss()
                }

                ContextMenuDivider()

                // ===== 切换主题（直接切换，与设置-个性化同一入口） =====
                SubmenuEntry(
                    id = "theme",
                    icon = Icons.Default.FormatPaint,
                    label = L("切换主题"),
                    expanded = expandedSub == "theme",
                    hoveredId = hoveredId,
                    itemRects = itemRects,
                    onToggle = { expandedSub = if (expandedSub == "theme") null else "theme" }
                ) {
                    WindowsVariant.values().forEach { variant ->
                        RadioOption("theme:${variant.name}", variant.displayName, activeTheme.variant == variant, hoveredId, itemRects) {
                            scope0.launch { app.themeManager.setTheme(variant) }
                        }
                    }
                }

                // ===== 显示设置 / 个性化（跳到设置中心对应分区） =====
                ContextMenuEntry(
                    id = "display", icon = Icons.Default.Monitor, label = L("显示设置"),
                    hoveredId = hoveredId, itemRects = itemRects
                ) {
                    wm.open(
                        appId = "settings",
                        title = "设置",
                        launchMode = LaunchMode.FLOATING,
                        launchArgs = mapOf("section" to "system")
                    )
                    onDismiss()
                }
                ContextMenuEntry(
                    id = "personalize", icon = Icons.Default.Palette, label = L("个性化设置"),
                    hoveredId = hoveredId, itemRects = itemRects
                ) {
                    wm.open(
                        appId = "settings",
                        title = "设置",
                        launchMode = LaunchMode.FLOATING,
                        launchArgs = mapOf("section" to "personalization")
                    )
                    onDismiss()
                }

                ContextMenuDivider()

                // ===== 打开终端 / 任务管理器 / 关闭所有窗口 =====
                ContextMenuEntry(
                    id = "terminal", icon = Icons.Default.Terminal, label = L("打开终端"),
                    hoveredId = hoveredId, itemRects = itemRects
                ) {
                    openApp("terminal", wm)
                    onDismiss()
                }
                ContextMenuEntry(
                    id = "sysinfo", icon = Icons.Default.Analytics, label = L("任务管理器"),
                    hoveredId = hoveredId, itemRects = itemRects
                ) {
                    openApp("sysinfo", wm)
                    onDismiss()
                }
                if (wm.windows.isNotEmpty()) {
                    ContextMenuEntry(
                        id = "closeAll", icon = Icons.Default.CancelPresentation, label = L("关闭所有窗口"),
                        hoveredId = hoveredId, itemRects = itemRects
                    ) {
                        wm.closeAll()
                        onDismiss()
                    }
                }
            }
            }
            }
            val menuPlaceable = measurables.first().measure(
                Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
            )
            // 菜单定位：以指针位置为锚点，自动钳制在屏幕内（原 ClampedMenuPositionProvider
            // 语义：边缘呼出不溢出屏幕）
            var mx = data.x.roundToInt()
            var my = data.y.roundToInt()
            if (mx + menuPlaceable.width > constraints.maxWidth) mx = constraints.maxWidth - menuPlaceable.width
            if (my + menuPlaceable.height > constraints.maxHeight) my = constraints.maxHeight - menuPlaceable.height
            if (mx < 0) mx = 0
            if (my < 0) my = 0
            layout(constraints.maxWidth, constraints.maxHeight) {
                menuPlaceable.place(mx, my)
            }
        }
    }

    // 快捷方式创建对话框
    if (showShortcutDialog) {
        ShortcutCreateDialog(
            onDismiss = { showShortcutDialog = false },
            onCreate = { shortcut ->
                scope0.launch {
                    withContext(Dispatchers.IO) {
                        app.database.shortcutDao().insert(
                            ShortcutEntity(
                                label = shortcut.label,
                                iconAsset = shortcut.iconAsset,
                                type = when (shortcut.type) {
                                    DesktopItemType.SHORTCUT_URL -> 1
                                    DesktopItemType.SHORTCUT_FILE -> 2
                                    DesktopItemType.SHORTCUT_APP -> 3
                                    else -> 1
                                },
                                target = shortcut.target,
                                launchArgs = shortcut.launchArgs,
                                sortOrder = 0
                            )
                        )
                    }
                }
                showShortcutDialog = false
            }
        )
    }

    // 重命名对话框（快捷方式）
    showRenameDialog?.let { item ->
        RenameShortcutDialog(
            item = item,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newLabel ->
                val shortcutId = item.id.removePrefix("shortcut_").toLongOrNull()
                if (shortcutId != null) {
                    scope0.launch {
                        withContext(Dispatchers.IO) {
                            val dao = app.database.shortcutDao()
                            dao.getAll().firstOrNull { it.id == shortcutId }?.let { entity ->
                                dao.update(entity.copy(label = newLabel.trim()))
                            }
                        }
                    }
                }
                showRenameDialog = null
            }
        )
    }

    // 属性对话框
    showPropsDialog?.let { item ->
        ShortcutPropertiesDialog(
            item = item,
            onDismiss = { showPropsDialog = null }
        )
    }
}

/** 从 AppRegistry 启动一个内置应用（右键菜单"打开终端/任务管理器"用） */
private fun openApp(appId: String, wm: WindowManager) {
    val appDef = com.linbox.core.window.AppRegistry.get(appId) ?: return
    wm.open(
        appId = appDef.id,
        title = appDef.displayName,
        launchMode = appDef.launchMode,
        initialWidth = appDef.defaultWidth.value.toInt(),
        initialHeight = appDef.defaultHeight.value.toInt()
    )
}

// ============================================================
// 菜单项组件（v2.20：新增虚拟指针悬停高亮 —— hoveredId 命中即高亮，
// 触控板滑动选择菜单的视觉反馈；bounds 上报至 itemRects 供命中计算）
// ============================================================

/** 普通菜单项：图标 + 文字，指针悬停/按压时高亮，点击执行动作（v2.16 紧凑尺寸：行高 30→17dp、字 12→9.5sp、图标 14→10dp） */
@Composable
private fun ContextMenuEntry(
    id: String,
    icon: ImageVector,
    label: String,
    hoveredId: String?,
    itemRects: SnapshotStateMap<String, Rect>,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = if (theme.isDark) Color.White else Color.Black
    val hovered = hoveredId == id
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(17.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (hovered || pressed) theme.accentColor.copy(alpha = 0.14f) else Color.Transparent)
            .onGloballyPositioned { itemRects[id] = it.boundsInWindowCompat() }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, null,
            tint = if (theme.isDark) Color(0xCCFFFFFF) else Color(0x99000000),
            modifier = Modifier.size(10.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 9.5.sp,
            lineHeight = 11.sp
        )
    }
}

/** 可展开子菜单项：图标 + 文字 + 旋转箭头，指针悬停/按压高亮，点击原地展开选项列表 */
@Composable
private fun SubmenuEntry(
    id: String,
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    hoveredId: String?,
    itemRects: SnapshotStateMap<String, Rect>,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalWinTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = if (theme.isDark) Color.White else Color.Black
    val hovered = hoveredId == id
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(17.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    when {
                        expanded -> theme.accentColor.copy(alpha = 0.10f)
                        hovered || pressed -> theme.accentColor.copy(alpha = 0.14f)
                        else -> Color.Transparent
                    }
                )
                .onGloballyPositioned { itemRects[id] = it.boundsInWindowCompat() }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onToggle
                )
                .padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, null,
                tint = if (theme.isDark) Color(0xCCFFFFFF) else Color(0x99000000),
                modifier = Modifier.size(10.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = fg,
                fontSize = 9.5.sp,
                lineHeight = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ExpandMore, null,
                tint = if (theme.isDark) Color(0x99FFFFFF) else Color(0x66000000),
                modifier = Modifier
                    .size(11.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp)
            ) {
                content()
            }
        }
    }
}

/** 子菜单单选项：勾选标记 + 文字，指针悬停/按压高亮 */
@Composable
private fun RadioOption(
    id: String,
    label: String,
    selected: Boolean,
    hoveredId: String?,
    itemRects: SnapshotStateMap<String, Rect>,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = if (theme.isDark) Color.White else Color.Black
    val hovered = hoveredId == id
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(15.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                when {
                    selected -> theme.accentColor.copy(alpha = 0.12f)
                    hovered || pressed -> theme.accentColor.copy(alpha = 0.14f)
                    else -> Color.Transparent
                }
            )
            .onGloballyPositioned { itemRects[id] = it.boundsInWindowCompat() }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 勾选标记占位（保持文字对齐）
        Box(modifier = Modifier.size(9.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Default.Check, null,
                    tint = theme.accentColor,
                    modifier = Modifier.size(8.dp)
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 9.sp,
            lineHeight = 10.5.sp
        )
    }
}

@Composable
private fun ContextMenuDivider() {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.5.dp)
            .height(1.dp)
            .background(theme.windowBorderColor.copy(alpha = 0.35f))
    )
}

// ============================================================
// 重命名 / 属性对话框
// ============================================================

/** 重命名快捷方式对话框 */
@Composable
private fun RenameShortcutDialog(
    item: DesktopItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val theme = LocalWinTheme.current
    val fg = if (theme.isDark) Color.White else Color.Black
    var text by remember(item.id) { mutableStateOf(item.label) }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .background(theme.windowBackgroundColor, RoundedCornerShape(8.dp))
                .padding(20.dp)
        ) {
            Text(
                "重命名快捷方式",
                color = fg,
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = fg, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accentColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (theme.isDark) Color(0x22FFFFFF) else Color(0x11000000),
                        RoundedCornerShape(6.dp)
                    )
                    .keyboardAware(
                        value = { text },
                        onValue = { text = it },
                        singleLine = true
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { if (text.isNotBlank()) onConfirm(text) },
                    enabled = text.isNotBlank()
                ) { Text("确定", color = theme.accentColor) }
            }
        }
    }
}

/** 快捷方式/应用 属性对话框 */
@Composable
private fun ShortcutPropertiesDialog(
    item: DesktopItem,
    onDismiss: () -> Unit
) {
    val theme = LocalWinTheme.current
    val typeName = when (item.type) {
        DesktopItemType.BUILTIN_APP -> "内置应用"
        DesktopItemType.SHORTCUT_URL -> "网址快捷方式"
        DesktopItemType.SHORTCUT_FILE -> "文件快捷方式"
        DesktopItemType.SHORTCUT_APP -> "应用快捷方式"
    }
    val appDef = com.linbox.core.window.AppRegistry.get(item.target)

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(theme.windowBackgroundColor, RoundedCornerShape(8.dp))
                .padding(20.dp)
        ) {
            Text(
                "${item.label} 属性",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(Modifier.height(14.dp))
            PropertyRow("名称", item.label)
            PropertyRow("类型", typeName)
            if (appDef != null) {
                PropertyRow("目标应用", appDef.displayName)
                PropertyRow("启动模式", if (appDef.launchMode == LaunchMode.FULLSCREEN) "全屏" else "浮动窗口")
            } else if (item.type != DesktopItemType.BUILTIN_APP) {
                PropertyRow("目标", item.target)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("关闭", color = theme.accentColor) }
            }
        }
    }
}

@Composable
private fun PropertyRow(key: String, value: String) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "$key：",
            color = theme.secondaryTextColor,
            fontSize = 12.sp,
            modifier = Modifier.width(76.dp)
        )
        Text(
            text = value,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
