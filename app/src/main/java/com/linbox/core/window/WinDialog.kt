package com.linbox.core.window

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.desktop.IconPainter
import com.linbox.core.theme.LocalWinTheme
import kotlin.math.roundToInt

/**
 * v3.1.7：Windows 风格可移动小窗口弹窗系统。
 *
 * 用户需求：容器创建等弹窗不再是"大大的死窗口"（平台 AlertDialog 居中
 * 大卡、不可移动、与桌面窗口体系割裂），而是像桌面上其他软件窗口一样
 * —— 有标题栏、可拖动、尺寸合理、好看且方便。
 *
 * 架构（对齐 WindowManager/WindowHost 的既有分层）：
 * - [WinDialogManager]：全局单例，快照状态驱动的对话框栈（同刻多弹窗，
 *   各自独立 z-index，后弹的在上、点谁谁置前）；
 * - [WinDialogHost]：由 DesktopEnvironment 挂在浮动窗口层之上（任务栏
 *   之下）渲染弹窗层 —— 弹窗永远盖在应用窗口之上，但不遮挡任务栏；
 * - [WinDialog]：声明式 API —— 组合期间注册进管理器、离开组合自动
 *   移除（谁显示谁销毁，与既有 `if (showCreate) XxxDialog()` 调用点
 *   完全兼容，弹窗内容仍在原调用方的组合上下文中书写）。
 *
 * 视觉与交互与 WindowChrome 一致（同一 WinTheme：标题栏/圆角/阴影/
 * 边框/关闭按钮），标题栏拖动采用 v2.11 的"本地偏移 + 松手一次性提交"
 * 方案（拖动期间零重组、只重放置，跟手流畅）。
 */
object WinDialogManager {

    /** 单个弹窗的规格（快照字段驱动 UI 刷新）。 */
    class Spec(
        val id: String,
        title0: String,
        iconAsset0: String?,
        width0: Dp
    ) {
        var title by mutableStateOf(title0)
        var iconAsset by mutableStateOf(iconAsset0)
        var width by mutableStateOf(width0)
        var onDismiss by mutableStateOf({})
        var content by mutableStateOf<@Composable () -> Unit>({})
        // v3.2.29：首帧定位策略（false = 居中级联；true = 右上角级联 ——
        // 前缀构建日志这类“后台进度观察窗”不再盖住屏幕中央的 X11 桌面/
        // wine 提示框，用户可在构建全程自由点击 X11 窗口）。
        var topEnd by mutableStateOf(false)
        // 弹窗几何（px）。x<0 = 尚未定位（Host 首帧居中 + 级联）
        var x by mutableStateOf(-1)
        var y by mutableStateOf(-1)
        var zIndex by mutableStateOf(0)
    }

    private val _dialogs = mutableStateListOf<Spec>()
    val dialogs: List<Spec> get() = _dialogs

    private var zCounter = 0
    private var cascadeCount = 0

    /** 打开（或聚焦）一个弹窗；返回规格以便 WinDialog 回写动态字段。 */
    fun show(
        id: String,
        title: String,
        iconAsset: String? = null,
        width: Dp = 420.dp,
        topEnd: Boolean = false
    ): Spec {
        val existing = _dialogs.firstOrNull { it.id == id }
        if (existing != null) {
            existing.title = title
            existing.topEnd = topEnd
            existing.zIndex = ++zCounter
            return existing
        }
        val spec = Spec(id, title, iconAsset, width)
        spec.topEnd = topEnd
        spec.zIndex = ++zCounter
        cascadeCount++
        _dialogs.add(spec)
        return spec
    }

    fun dismiss(id: String) {
        _dialogs.removeAll { it.id == id }
        if (_dialogs.isEmpty()) cascadeCount = 0
    }

    fun dismissAll() {
        _dialogs.clear()
        cascadeCount = 0
    }

    /** 弹窗置前（已是顶层则无操作，避免无效重组）。 */
    fun focus(id: String) {
        val top = _dialogs.maxOfOrNull { it.zIndex } ?: 0
        val spec = _dialogs.firstOrNull { it.id == id } ?: return
        if (spec.zIndex == top) return
        spec.zIndex = ++zCounter
    }

    /** 新弹窗的级联偏移（打开序号，用于错开摆放）。 */
    fun cascadeIndex(): Int = cascadeCount
}

/**
 * 声明式弹窗 API：组合期间显示，离开组合自动关闭。
 *
 * @param title      标题栏文字（动态变化自动同步）
 * @param onDismiss  关闭回调（标题栏 X 与内容关闭按钮都会调用）
 * @param width      弹窗宽度（自动钳制到工作区内）
 * @param iconAsset  可选应用图标（assets 路径；null = 通用弹窗图标）
 * @param content    弹窗内容（在弹窗层组合中调用，主题上下文一致）
 */
@Composable
fun WinDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 420.dp,
    iconAsset: String? = null,
    topEnd: Boolean = false,
    content: @Composable () -> Unit
) {
    val id = remember { "dlg-${java.util.UUID.randomUUID()}" }
    // rememberUpdatedState：DisposableEffect(Unit) 的回调不随后续重组重启，
    // 必须经由最新状态引用读取，否则捕获到过期闭包（标题/回调变了弹窗不知）。
    val currentTitle by rememberUpdatedState(title)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentWidth by rememberUpdatedState(width)
    val currentIcon by rememberUpdatedState(iconAsset)
    val currentTopEnd by rememberUpdatedState(topEnd)
    val currentContent by rememberUpdatedState<@Composable () -> Unit>(content)

    DisposableEffect(Unit) {
        WinDialogManager.show(id, title, iconAsset, width, topEnd)
        onDispose { WinDialogManager.dismiss(id) }
    }
    // 每次重组把最新字段回写进规格（快照字段驱动弹窗层刷新）
    SideEffect {
        WinDialogManager.dialogs.firstOrNull { it.id == id }?.let { spec ->
            spec.title = currentTitle
            spec.onDismiss = currentOnDismiss
            spec.width = currentWidth
            spec.iconAsset = currentIcon
            spec.topEnd = currentTopEnd
            spec.content = currentContent
        }
    }
}

/**
 * 弹窗层宿主：渲染所有弹窗（按 zIndex 排序，大者在上）。
 * 挂载于浮动窗口层之上、开始菜单/任务栏之下。
 */
@Composable
fun WinDialogHost(workAreaModifier: Modifier = Modifier) {
    val dialogs = WinDialogManager.dialogs
    if (dialogs.isEmpty()) return

    BoxWithConstraints(modifier = workAreaModifier) {
        val density = LocalDensity.current
        val workW = constraints.maxWidth
        val workH = constraints.maxHeight

        // 首帧定位：居中 + 顶部 12% 起步 + 级联错开（同刻多弹窗不重叠）；
        // v3.2.29：topEnd 规格 → 右上角起步（进度观察窗不盖 X11 桌面）
        dialogs.forEach { spec ->
            if (spec.x < 0 || spec.y < 0) {
                val wpx = with(density) { spec.width.roundToPx() }
                val cascade = (WinDialogManager.cascadeIndex() - 1).coerceAtLeast(0)
                if (spec.topEnd) {
                    spec.x = (workW - wpx - with(density) { 12.dp.roundToPx() })
                        .coerceIn(0, (workW - wpx).coerceAtLeast(0))
                    spec.y = with(density) { 12.dp.roundToPx() } + cascade * 28
                } else {
                    spec.x = ((workW - wpx) / 2 + cascade * 32).coerceIn(0, (workW - wpx).coerceAtLeast(0))
                    spec.y = (workH / 8 + cascade * 28).coerceIn(0, (workH / 2).coerceAtLeast(0))
                }
            }
        }

        dialogs.sortedBy { it.zIndex }.forEach { spec ->
            key(spec.id) {
                WinDialogFrame(spec, workW, workH)
            }
        }
    }
}

/**
 * 单个弹窗的框架：标题栏（图标 + 标题 + 关闭）+ 内容区。
 * 标题栏可拖动；任意按下即置前；位置钳制在工作区内。
 */
@Composable
private fun BoxScope.WinDialogFrame(
    spec: WinDialogManager.Spec,
    workW: Int,
    workH: Int
) {
    val theme = LocalWinTheme.current
    val density = LocalDensity.current

    // 宽度钳制：不超过工作区（扣两侧 8dp 边距），不小于 240dp
    val frameWidth = with(density) {
        val maxW = (workW - 16.dp.roundToPx()).toDp()
        spec.width.coerceIn(240.dp, maxW)
    }
    val frameWpx = with(density) { frameWidth.roundToPx() }

    // ===== 拖动（本地偏移，松手一次性提交 —— 与 WindowChrome 同方案）=====
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // 内容高度上限：工作区高的 82%（超出由弹窗内容自滚动）
    val maxContentH = with(density) { (workH * 0.82f).coerceAtLeast(0f).toDp() }

    // v3.2.2：弹窗几何上报 —— WindowFocusHook 按几何直判落点：点弹窗 =
    // 操作弹窗，不置前底下的应用窗口；弹窗关闭离开组合时注销。
    DisposableEffect(spec.id) {
        onDispose { WindowFocusHook.reportDialog(spec.id, null) }
    }

    Box(
        modifier = Modifier
            // 点击弹窗任意处 → 置前（Initial 阶段观察，不消费事件，
            // 标题栏拖动/内容手势照常进行）
            .pointerInput(spec.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    WinDialogManager.focus(spec.id)
                }
            }
            .offset {
                val maxX = (workW - frameWpx).coerceAtLeast(0)
                val maxY = (workH - 120).coerceAtLeast(0)
                val x = (spec.x + dragOffset.x.roundToInt()).coerceIn(0, maxX)
                val y = (spec.y + dragOffset.y.roundToInt()).coerceIn(0, maxY)
                IntOffset(x, y)
            }
            .layout { measurable, constraints ->
                // 修复：原写法把第二个参数（传入约束）命名为 _ 丢弃后，
                // 函数体内却引用 constraints —— WinDialogFrame 的接收者是
                // BoxScope 而非 BoxWithConstraintsScope，作用域内并无该标识符，
                // 编译报 Unresolved reference。改为显式命名 constraints 即可。
                val w = frameWpx.coerceAtMost(constraints.maxWidth)
                val placeable = measurable.measure(Constraints.fixedWidth(w))
                layout(w, placeable.height) { placeable.place(0, 0) }
            }
            .shadow(theme.windowShadowElevation, theme.windowShape, clip = false)
            .clip(theme.windowShape)
            .background(theme.windowBackgroundColor)
            .border(theme.windowBorderWidth, theme.windowBorderColor, theme.windowShape)
            // v3.2.2：链尾上报最终布局位置（窗口坐标 Rect）
            .onGloballyPositioned { coords ->
                WindowFocusHook.reportDialog(spec.id, WindowFocusHook.boundsOf(coords))
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ===== 标题栏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(theme.windowTitleBarHeight)
                    .background(theme.windowTitleBarColor)
                    .pointerInput(spec.id) {
                        detectDragGestures(
                            onDragStart = {
                                WinDialogManager.focus(spec.id)
                                dragOffset = Offset.Zero
                            },
                            onDragEnd = {
                                if (dragOffset != Offset.Zero) {
                                    val ny = (spec.y + dragOffset.y.roundToInt())
                                        .coerceIn(0, (workH - 120).coerceAtLeast(0))
                                    val nx = (spec.x + dragOffset.x.roundToInt())
                                        .coerceIn(0, (workW - frameWpx).coerceAtLeast(0))
                                    spec.x = nx
                                    spec.y = ny
                                }
                                dragOffset = Offset.Zero
                            },
                            onDragCancel = { dragOffset = Offset.Zero }
                        ) { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        }
                    }
                    .pointerInput(spec.id) {
                        detectTapGestures(onTap = { WinDialogManager.focus(spec.id) })
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧图标
                Box(
                    modifier = Modifier
                        .size(theme.windowTitleBarHeight)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 修复：spec.iconAsset 是 mutableStateOf 委托的可变属性，
                    // 判空后直接传参会被并发修改打断，Kotlin 拒绝 smart cast。
                    // 先拷贝到局部 val，判空与使用都基于同一不可变快照。
                    val iconAsset = spec.iconAsset
                    if (iconAsset != null) {
                        IconPainter(iconAsset, size = 16.dp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                .background(theme.windowTitleBarIconColor)
                        )
                    }
                }

                Text(
                    text = spec.title,
                    color = theme.windowTitleBarTextColor,
                    fontSize = theme.fontSizeBody,
                    fontWeight = theme.fontWeightTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 关闭按钮（红 X，与窗口 chrome 一致）
                DialogControlButton(icon = Icons.Default.Close, theme = theme, isClose = true) {
                    spec.onDismiss()
                }
            }

            // ===== 内容区 =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentH)
            ) {
                spec.content.invoke()
            }
        }
    }
}

/** 弹窗标题栏控制按钮（外观与 WindowChrome.ControlButton 一致）。 */
@Composable
private fun DialogControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    theme: com.linbox.core.theme.WinTheme,
    isClose: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = theme.windowTitleBarHeight)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isClose) Color(0xFFE81123) else theme.windowTitleBarTextColor,
            modifier = Modifier.size(12.dp)
        )
    }
}

/**
 * 通用确认弹窗（原 AlertDialog 确认样式的直接替换）：
 * 标题 + 消息 + 右下 [确认]/[取消] 按钮组。danger=true 时确认按钮红色。
 *
 * @param uiScale v3.2.21：内容收紧系数（容器管理等面反馈“弹窗框框太大”），
 *  1f = 原样（其他调用方默认）；<1 时宽度与内容密度同步收缩。
 */
@Composable
fun WinConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    dismissText: String? = "取消",
    danger: Boolean = false,
    uiScale: Float = 1f,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val base = LocalDensity.current
    val dens = if (uiScale != 1f) Density(base.density * uiScale, base.fontScale) else base
    WinDialog(
        title = title,
        onDismiss = onDismiss,
        width = 400.dp * uiScale
    ) {
        CompositionLocalProvider(LocalDensity provides dens) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = message,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (dismissText != null) {
                    TextButton(onClick = onDismiss) {
                        Text(dismissText)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = onConfirm,
                    colors = if (danger) {
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE81123)
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) { Text(confirmText) }
            }
        }
        }
    }
}
