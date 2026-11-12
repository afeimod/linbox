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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.window.IconPainter
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
 * 挂载于浮动窗口层之上、虚拟键盘/手柄层之下。
 *
 * v5.0（安卓化重制）：弹窗改为安卓 Material 对话框形态 ——
 * - 居中显示（不再自由拖动），同刻多弹窗小级联错开；
 * - 半透明遮罩暗化背景，点击遮罩关闭最顶层普通弹窗；
 * - topEnd 规格（前缀构建日志等进度观察窗）仍固定右上角，
 *   不加遮罩阻断（构建全程可自由点击 X11 画面）。
 */
@Composable
fun WinDialogHost(workAreaModifier: Modifier = Modifier) {
    val dialogs = WinDialogManager.dialogs
    if (dialogs.isEmpty()) return

    BoxWithConstraints(modifier = workAreaModifier) {
        val density = LocalDensity.current
        val workW = constraints.maxWidth
        val workH = constraints.maxHeight

        // v5.0：安卓式遮罩 —— 存在普通弹窗时暗化背景；点击遮罩关闭
        // 最顶层普通弹窗（topEnd 进度窗不拦截，也不被遮罩暗化逻辑关闭）
        val dismissible = dialogs.filter { !it.topEnd }.maxByOrNull { it.zIndex }
        if (dismissible != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(com.linbox.core.theme.LinShellTheme.scrim)
                    .pointerInput(dismissible.id) {
                        detectTapGestures { dismissible.onDismiss() }
                    }
            )
        }

        dialogs.sortedBy { it.zIndex }.forEachIndexed { idx, spec ->
            key(spec.id) {
                WinDialogFrame(spec, workW, workH, idx)
            }
        }
    }
}

/**
 * 单个弹窗的框架（v5.0 安卓化）：居中 + 28dp 大圆角 + 暗色玻璃底 +
 * 安卓风标题行（图标 + 标题居左 + 圆形关闭钮居右）。
 * topEnd 规格固定右上角；普通规格同刻多弹窗级联错开 24dp。
 */
@Composable
private fun BoxScope.WinDialogFrame(
    spec: WinDialogManager.Spec,
    workW: Int,
    workH: Int,
    cascadeIndex: Int
) {
    val density = LocalDensity.current

    // 宽度钳制：不超过工作区（扣两侧 16dp 边距），不小于 280dp
    val frameWidth = with(density) {
        val maxW = (workW - 32.dp.roundToPx()).toDp()
        spec.width.coerceIn(280.dp, maxW)
    }
    val frameWpx = with(density) { frameWidth.roundToPx() }

    // 内容高度上限：工作区高的 82%（超出由弹窗内容自滚动）
    val maxContentH = with(density) { (workH * 0.82f).coerceAtLeast(0f).toDp() }

    // v3.2.2：弹窗几何上报 —— WindowFocusHook 按几何直判落点。
    DisposableEffect(spec.id) {
        onDispose { WindowFocusHook.reportDialog(spec.id, null) }
    }

    val shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    val cascade = cascadeIndex * 24

    Box(
        modifier = Modifier
            .align(if (spec.topEnd) Alignment.TopEnd else Alignment.Center)
            .offset {
                if (spec.topEnd) {
                    IntOffset(
                        -with(density) { 12.dp.roundToPx() },
                        with(density) { 12.dp.roundToPx() } + cascade
                    )
                } else {
                    IntOffset(cascade, cascade)
                }
            }
            // 点击弹窗任意处 → 置前（Initial 阶段观察，不消费事件）
            .pointerInput(spec.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    WinDialogManager.focus(spec.id)
                }
            }
            .layout { measurable, constraints ->
                val w = frameWpx.coerceAtMost(constraints.maxWidth)
                val placeable = measurable.measure(Constraints.fixedWidth(w))
                layout(w, placeable.height) { placeable.place(0, 0) }
            }
            .shadow(18.dp, shape, clip = false)
            .clip(shape)
            .background(com.linbox.core.theme.LinShellTheme.surface)
            .border(1.dp, com.linbox.core.theme.LinShellTheme.surfaceBorder, shape)
            .onGloballyPositioned { coords ->
                WindowFocusHook.reportDialog(spec.id, WindowFocusHook.boundsOf(coords))
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ===== 安卓风标题行 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧应用图标（28dp 圆角底）
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                        .background(com.linbox.core.theme.LinShellTheme.blue.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    val iconAsset = spec.iconAsset
                    if (iconAsset != null) {
                        IconPainter(iconAsset, size = 18.dp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(com.linbox.core.theme.LinShellTheme.blue)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = spec.title,
                    color = com.linbox.core.theme.LinShellTheme.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 圆形关闭钮（Material 安卓风）
                DialogControlButton(icon = Icons.Default.Close) {
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

/** v5.0：弹窗关闭钮（Material 圆形，36dp 触摸目标）。 */
@Composable
private fun DialogControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(0x14FFFFFF))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = com.linbox.core.theme.LinShellTheme.textSecondary,
            modifier = Modifier.size(16.dp)
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
