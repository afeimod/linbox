package com.linbox.core.desktop

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.linbox.LinBoxApp
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.WindowManager
import com.linbox.data.model.DesktopItem
import com.linbox.data.model.DesktopItemType
import com.linbox.data.model.Shortcut
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 桌面图标网格：靠左上角、**先竖向填充再横向换列**排列（Windows 桌面真实样式）。
 *
 * 与之前用 FlowRow 不同：Windows 桌面图标是按列组织的——从上到下铺满一列后再
 * 跳到右边下一列继续从上到下，而不是从左到右铺满一行后再换行。
 * 这样在窄屏（手机竖屏）下也能合理利用屏幕高度。
 *
 * 数据来源：
 * 1. AppRegistry 中 pinnedToDesktop=true 的内置应用
 * 2. 数据库中保存的快捷方式
 *
 * v2.11：
 * - sortMode：右键菜单"排序方式"（default 默认 / name 按名称 / type 按类型，
 *   与设置中心共用 DataStore 持久化）；
 * - iconBounds：上报每个图标的屏幕边界，供双指右键手势命中检测
 *   （命中图标 → 图标菜单，否则 → 桌面菜单）。
 *
 * v2.13：
 * - clickMode：图标打开方式（single 单击打开 / double 双击打开，
 *   与鼠标设置共用 DataStore 持久化）。
 *
 * v2.14：
 * - 长按拖动排序：长按图标 400ms 触发（震动反馈），图标跟随手指，
 *   松手时与落点最近的图标交换位置，顺序持久化到 DataStore（desktop_icon_order）。
 *   仅 sortMode = default（默认排序）时启用；按名称/类型排序时拖动禁用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopIconGrid(
    sortMode: String = "default",
    iconBounds: SnapshotStateMap<String, Pair<DesktopItem, Rect>>? = null,
    clickMode: String = "double"
) {
    val app = LinBoxApp.get()
    val shortcuts by app.database.shortcutDao().observeAll().collectAsState(initial = emptyList())
    val builtinApps = remember { AppRegistry.desktopApps() }
    val iconSize by app.settingsStore.iconSize.collectAsState(initial = 48f)
    // v2.14：自定义图标顺序（长按拖动后持久化）
    val iconOrder by app.settingsStore.desktopIconOrder.collectAsState(initial = "")
    val scope = rememberCoroutineScope()

    // 合并桌面项
    val items: List<DesktopItem> = remember(shortcuts, builtinApps) {
        val builtin = builtinApps.mapIndexed { idx, app ->
            DesktopItem(
                id = "app_${app.id}",
                label = app.displayName,
                iconAsset = app.iconAsset,
                type = DesktopItemType.BUILTIN_APP,
                target = app.id,
                sortOrder = idx
            )
        }
        val shortcutItems = shortcuts.map { entity ->
            DesktopItem(
                id = "shortcut_${entity.id}",
                label = entity.label,
                iconAsset = entity.iconAsset,
                type = when (entity.type) {
                    1 -> DesktopItemType.SHORTCUT_URL
                    2 -> DesktopItemType.SHORTCUT_FILE
                    3 -> DesktopItemType.SHORTCUT_APP
                    else -> DesktopItemType.SHORTCUT_URL
                },
                target = entity.target,
                sortOrder = entity.sortOrder
            )
        }
        builtin + shortcutItems
    }

    // 排序（v2.11 右键菜单"排序方式"；v2.14 default 模式优先应用拖动自定义顺序）
    val sortedItems = remember(items, sortMode, iconOrder) {
        when (sortMode) {
            "name" -> items.sortedWith(compareBy({ it.label }, { it.id }))
            "type" -> items.sortedWith(compareBy({ it.type }, { it.label }))
            else -> {
                if (iconOrder.isNotBlank()) {
                    val orderIds = iconOrder.split(",").filter { it.isNotBlank() }
                    val pos = orderIds.withIndex().associate { (i, id) -> id to i }
                    // 已记录顺序的图标在前（按记录序），新出现的图标追加在后（保持默认序）
                    items.sortedBy { pos[it.id] ?: Int.MAX_VALUE }
                } else {
                    items   // 默认：内置应用 + 快捷方式原序
                }
            }
        }
    }

    // 同步命中注册表：移除已从桌面消失的图标（删除快捷方式后）
    if (iconBounds != null) {
        LaunchedEffect(items) {
            val ids = items.map { it.id }.toSet()
            iconBounds.keys.toList().forEach { key ->
                if (key !in ids) iconBounds.remove(key)
            }
        }
    }

    // 使用 FlowColumn：先竖向（从上到下）填充，超出屏幕高度后自动换到下一列
    // （从左到右），完整还原 Windows 桌面图标的排列方式。
    // 外层 horizontalScroll 让超出屏幕右边界的列可水平滚动访问。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState())
    ) {
        FlowColumn(
            modifier = Modifier
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sortedItems.forEach { item ->
                DesktopIcon(
                    item = item,
                    iconSize = iconSize,
                    boundsRegistry = iconBounds,
                    clickMode = clickMode,
                    dragEnabled = sortMode == "default",
                    onMoveItem = { fromId, toId ->
                        // 基于当前显示顺序重建 id 序列，把 fromId 移到 toId 的位置
                        val ids = sortedItems.map { it.id }
                        val f = ids.indexOf(fromId)
                        val t = ids.indexOf(toId)
                        if (f >= 0 && t >= 0 && f != t) {
                            val m = ids.toMutableList()
                            m.removeAt(f)
                            m.add(t, fromId)
                            scope.launch { app.settingsStore.setDesktopIconOrder(m.joinToString(",")) }
                        }
                    }
                )
            }
        }
    }
}

/**
 * 启动一个桌面项（图标双击 / 右键菜单"打开"共用同一入口，保证行为一致）。
 */
fun launchDesktopItem(item: DesktopItem, wm: WindowManager) {
    when (item.type) {
        DesktopItemType.BUILTIN_APP -> {
            AppRegistry.get(item.target)?.let { a ->
                wm.open(
                    appId = a.id,
                    title = a.displayName,
                    launchMode = a.launchMode,
                    initialWidth = a.defaultWidth.value.toInt(),
                    initialHeight = a.defaultHeight.value.toInt()
                )
            }
        }
        DesktopItemType.SHORTCUT_URL,
        DesktopItemType.SHORTCUT_FILE -> {
            // 通过浏览器打开
            wm.open(
                appId = "browser",
                title = "Browser",
                launchMode = AppRegistry.get("browser")?.launchMode
                    ?: com.linbox.core.window.LaunchMode.FLOATING,
                launchArgs = mapOf(
                    "url" to item.target,
                    "type" to item.type.name
                )
            )
        }
        DesktopItemType.SHORTCUT_APP -> {
            AppRegistry.get(item.target)?.let { a ->
                wm.open(
                    appId = a.id,
                    title = a.displayName,
                    launchMode = a.launchMode,
                    initialWidth = a.defaultWidth.value.toInt(),
                    initialHeight = a.defaultHeight.value.toInt()
                )
            }
        }
    }
}

@Composable
private fun DesktopIcon(
    item: DesktopItem,
    iconSize: Float,
    boundsRegistry: SnapshotStateMap<String, Pair<DesktopItem, Rect>>? = null,
    clickMode: String = "double",
    dragEnabled: Boolean = false,
    onMoveItem: (String, String) -> Unit = { _, _ -> }
) {
    val theme = LocalWinTheme.current
    val wm = remember { WindowManager.get() }
    val iconPx = iconSize.dp
    val cellWidth = (iconSize + 24).dp
    val haptic = LocalHapticFeedback.current

    // v2.14 长按拖动状态：拖动中偏移（px），松手归零
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val dragging = dragOffset != Offset.Zero

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(cellWidth)
            // 拖动中置顶 + 轻微放大，视觉上"拎起来"
            .zIndex(if (dragging) 10f else 0f)
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                val s = if (dragging) 1.08f else 1f
                scaleX = s
                scaleY = s
                alpha = if (dragging) 0.85f else 1f
            }
            // 上报图标边界（根坐标）：供双指右键手势命中检测 + 拖动落点计算；
            // 值未变化时不重复写入，避免无谓的快照失效。
            // 注意：上报的是【布局位置】（不含拖动偏移），落点计算时手动叠加偏移。
            .onGloballyPositioned { coords ->
                if (boundsRegistry != null) {
                    val rect = coords.boundsInRoot()
                    val prev = boundsRegistry[item.id]
                    if (prev == null || prev.first != item || prev.second != rect) {
                        boundsRegistry[item.id] = item to rect
                    }
                }
            }
            // v2.14：长按 400ms 进入拖动 → 图标跟随手指 → 松手与最近图标换位
            .pointerInput(item.id, dragEnabled) {
                if (!dragEnabled) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                    },
                    onDragEnd = {
                        // 落点 = 布局位置 + 累计偏移
                        val myRect = boundsRegistry?.get(item.id)?.second
                        if (myRect != null && dragOffset != Offset.Zero) {
                            val dropCenter = Offset(
                                myRect.center.x + dragOffset.x,
                                myRect.center.y + dragOffset.y
                            )
                            // 找到中心距离落点最近的其他图标（拖动自身偏移不参与）
                            val target = boundsRegistry.entries
                                .filter { it.key != item.id }
                                .minByOrNull { entry ->
                                    val c = entry.value.second.center
                                    (c.x - dropCenter.x) * (c.x - dropCenter.x) +
                                        (c.y - dropCenter.y) * (c.y - dropCenter.y)
                                }
                            if (target != null) {
                                onMoveItem(item.id, target.key)
                            }
                        }
                        dragOffset = Offset.Zero
                    },
                    onDragCancel = { dragOffset = Offset.Zero }
                )
            }
            // v2.13：clickMode 进入手势 key —— 单击模式立即启动，双击模式保持 v2.11 行为
            .pointerInput(item.id, clickMode) {
                if (clickMode == "single") {
                    detectTapGestures(
                        onTap = {
                            // 单击启动（与右键菜单"打开"共用同一入口）
                            launchDesktopItem(item, wm)
                        }
                    )
                } else {
                    detectTapGestures(
                        onDoubleTap = {
                            // 双击启动（与右键菜单"打开"共用同一入口）
                            launchDesktopItem(item, wm)
                        }
                    )
                }
            }
            .padding(4.dp)
    ) {
        // 图标
        IconPainter(item.iconAsset, size = iconPx)

        Spacer(Modifier.height(2.dp))

        // 文字
        Text(
            text = item.label,
            color = theme.desktopIconTextColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(cellWidth)
        )
    }
}

/**
 * 统一图标加载：支持 assets 路径 / "emoji:🚀" / 内置 drawable。
 *
 * v2.15 主题图标：图标引用先经 [com.linbox.core.theme.ThemeIcons.resolve]
 * 解析为当前 Windows 主题的专属图标（icons/win95|winxp|win10|win11/<id>.png），
 * 无专属图标时回退通用路径 —— 切换主题时桌面/任务栏/开始菜单图标即时换肤。
 */
@Composable
fun IconPainter(asset: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val theme = LocalWinTheme.current
    // 主题图标解析（assets.list 有缓存，重组开销可忽略）
    val resolvedAsset = remember(asset, theme.variant) {
        com.linbox.core.theme.ThemeIcons.resolve(context, asset, theme.variant)
    }

    if (resolvedAsset.startsWith("emoji:")) {
        val emoji = resolvedAsset.removePrefix("emoji:")
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = (size.value * 0.7f).sp)
        }
        return
    }

    val painter = remember(resolvedAsset) {
        runCatching {
            context.assets.open(resolvedAsset).use {
                BitmapPainter(BitmapFactory.decodeStream(it).asImageBitmap())
            }
        }.getOrNull()
    }

    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    } else {
        // 兜底：使用 emoji 占位
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(size)
                .background(theme.accentColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("?", fontSize = 14.sp, color = theme.accentColor)
        }
    }
}
