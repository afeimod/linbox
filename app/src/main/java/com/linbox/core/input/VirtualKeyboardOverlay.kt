package com.linbox.core.input

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.input.boundsInWindowCompat
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 虚拟键盘覆盖层（v2.13）：Windows 风格全键盘。
 *
 * - 完整布局：功能键行（Esc + F1-F12，可开关）+ 主键区（Tab/Caps/Shift/Ctrl/Win/Alt
 *   修饰键 + 方向键）+ 数字小键盘（可开关，含方向键列）
 * - 修饰键：Shift（单击一次性 / 双击大写锁定）、Caps、Ctrl（A/C/X/V 组合）、
 *   Win（呼出开始菜单）、Alt
 * - 主题：浅色 / 深色 / 蓝色 / 玻璃 / 透明 五套（v2.24 新增全透明背板）
 * - 大小：0.75..1.35 缩放 —— 三种调节方式：设置滑杆 / 工具栏 -+ / 双指捏合（v2.13.2）；
 *   位置：默认底部居中，可拖动（拖动开关可关）
 * - 键盘振动 / 触摸反馈设置真实生效（触觉 + 按压缩放高亮动画）
 * - 键体用 pointerInput 而非 clickable —— 不与编辑框抢焦点
 */
@Composable
fun VirtualKeyboardOverlay() {
    val app = LinBoxApp.get()
    // v2.13.2：虚拟键盘默认关闭（大多数场景用手机输入法更顺手，需在设置中主动开启）
    val master by app.settingsStore.keyboardMaster.collectAsState(initial = false)
    val funcRow by app.settingsStore.keyboardFuncRow.collectAsState(initial = true)
    val numpad by app.settingsStore.keyboardNumpad.collectAsState(initial = true)
    val scale by app.settingsStore.keyboardScale.collectAsState(initial = 1.0f)
    val themeId by app.settingsStore.keyboardTheme.collectAsState(initial = "dark")
    val posX by app.settingsStore.keyboardPosX.collectAsState(initial = 0.5f)
    val posY by app.settingsStore.keyboardPosY.collectAsState(initial = 1.0f)
    val dragEnabled by app.settingsStore.keyboardDragEnabled.collectAsState(initial = true)
    val vibrate by app.settingsStore.keyboardVibration.collectAsState(initial = true)
    val touchFeedback by app.settingsStore.touchFeedback.collectAsState(initial = false)

    // v2.20：键盘面板登记为触控板直通区（真实手指直接按键可用）；
    // 覆盖层隐藏/离开组合时注销，防陈旧矩形误吞桌面触摸
    DisposableEffect(Unit) {
        onDispose { TrackpadRouter.registerPassthrough("vkPanel", null) }
    }

    if (!master) return
    if (!VirtualKeyboardController.visible) return
    VirtualKeyboardController.target ?: return

    val theme = remember(themeId) { kbThemeOf(themeId) }
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ===== 修饰键状态 =====
    var shiftState by remember { mutableStateOf(0) }   // 0=关 1=一次性 2=锁定
    var capsOn by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var winKey by remember { mutableStateOf(false) }

    // ===== 键处理 =====
    fun onKey(k: KbKey) {
        val t = VirtualKeyboardController.target ?: return
        when (k.action) {
            KbAction.Char -> {
                if (ctrl) {
                    // Ctrl 组合键
                    when (k.label.lowercase()) {
                        "a" -> t.selectAll()
                        "c" -> t.copy(clipboard)
                        "x" -> t.cut(clipboard)
                        "v" -> t.paste(clipboard)
                    }
                    ctrl = false
                } else {
                    val useShifted = if (k.shifted == null) false
                    else if (k.label.length == 1 && k.label[0].isLetter())
                        (shiftState > 0) != capsOn
                    else shiftState > 0
                    t.insertText(if (useShifted) k.shifted!! else k.label)
                    if (shiftState == 1) shiftState = 0
                }
            }
            KbAction.Space -> { t.insertText(" "); if (shiftState == 1) shiftState = 0 }
            KbAction.Backspace -> t.backspace()
            KbAction.Delete -> t.deleteForward()
            KbAction.Tab -> t.insertText("\t")
            KbAction.Enter -> {
                if (t.singleLine) t.onEnter?.invoke()
                else t.insertText("\n")
            }
            KbAction.Shift -> shiftState = (shiftState + 1) % 3
            KbAction.Caps -> capsOn = !capsOn
            KbAction.Ctrl -> ctrl = !ctrl
            KbAction.Alt -> alt = !alt
            KbAction.Win -> {
                winKey = false
                VirtualKeyboardController.onWinKey?.invoke()
            }
            KbAction.Esc -> VirtualKeyboardController.hide()
            KbAction.Up, KbAction.Left -> t.moveCursor(-1)
            KbAction.Down, KbAction.Right -> t.moveCursor(1)
            KbAction.Home -> t.toStart()
            KbAction.End -> t.toEnd()
            KbAction.None -> { /* F 功能键等：无绑定，仅触觉反馈 */ }
        }
    }

    fun tapFeedback() {
        if (vibrate) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // ===== 尺寸/位置 =====
    var kbSize by remember { mutableStateOf(IntSize.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var pos by remember { mutableStateOf(Offset(0.5f, 1f)) }
    var dragging by remember { mutableStateOf(false) }
    // 双指捏合缩放（v2.13.2）：累计增量跨过阈值才落盘，避免捏合过程高频写 DataStore
    var pinchZoom by remember { mutableStateOf(1f) }
    val liveScale by rememberUpdatedState(scale)
    // 位置设置变化时同步（首次组合 + 设置页"重置位置"）
    LaunchedEffect(posX, posY) { if (!dragging) pos = Offset(posX, posY) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWpx = with(density) { maxWidth.toPx() }
        val screenHpx = with(density) { maxHeight.toPx() }
        val keyH = (42 * scale).dp
        val funcH = (32 * scale).dp
        val gap = (3 * scale).dp

        // v2.13.2 布局降级：可用宽度不足（竖屏）时收缩区域，保主键区可用
        val showNumpad = numpad && maxWidth >= (620 * scale).dp
        val compactArrows = maxWidth < (560 * scale).dp

        val freeW = (screenWpx - kbSize.width).coerceAtLeast(0f)
        val freeH = (screenHpx - kbSize.height).coerceAtLeast(0f)
        val x = (freeW * pos.x + dragOffset.x).coerceIn(0f, freeW)
        val y = (freeH * pos.y + dragOffset.y).coerceIn(0f, freeH)

        // 位置持久化：重新从 state 读取当前值计算（避免拖动闭包捕获过期坐标）
        fun persistPos() {
            val fw = (screenWpx - kbSize.width).coerceAtLeast(0f)
            val fh = (screenHpx - kbSize.height).coerceAtLeast(0f)
            val cx = (fw * pos.x + dragOffset.x).coerceIn(0f, fw)
            val cy = (fh * pos.y + dragOffset.y).coerceIn(0f, fh)
            val nx = if (fw > 0f) (cx / fw).coerceIn(0f, 1f) else pos.x
            val ny = if (fh > 0f) (cy / fh).coerceIn(0f, 1f) else pos.y
            pos = Offset(nx, ny)
            dragOffset = Offset.Zero
            scope.launch { app.settingsStore.setKeyboardPos(nx, ny) }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .onGloballyPositioned { kbSize = it.size }
                .onGloballyPositioned {
                    // v2.20：面板窗口坐标登记到触控板路由器（直通区）
                    TrackpadRouter.registerPassthrough("vkPanel", it.boundsInWindowCompat())
                }
                // 双指捏合缩放（v2.13.2）：放在键体修饰符之前 —— 单指事件仍由子级键优先消费
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (abs(zoom - 1f) > 0.001f) {
                            pinchZoom *= zoom
                            if (abs(pinchZoom - 1f) >= 0.06f) {
                                val target = (liveScale * pinchZoom).coerceIn(0.75f, 1.35f)
                                scope.launch { app.settingsStore.setKeyboardScale(target) }
                                pinchZoom = 1f
                            }
                        }
                    }
                }
                .clip(RoundedCornerShape((10 * scale).dp))
                .background(theme.bg)
                .padding((5 * scale).dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                // ===== 工具栏：拖动 / 功能键行 / 小键盘 / 主题 / 隐藏 =====
                Row(
                    modifier = Modifier.fillMaxWidth().height((24 * scale).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((6 * scale).dp)
                ) {
                    ToolbarChip(
                        "☰", theme, scale,
                        onTap = { /* 提示：按住拖动 */ },
                        onDrag = { change, amount ->
                            if (dragEnabled) {
                                change.consume()
                                dragOffset += amount
                            }
                        },
                        onDragEnd = { if (dragEnabled) { dragging = false; persistPos() } },
                        onDragStart = { if (dragEnabled) dragging = true }
                    )
                    ToolbarChip("Fn", theme, scale, on = funcRow, onTap = {
                        scope.launch { app.settingsStore.setKeyboardFuncRow(!funcRow) }
                    })
                    ToolbarChip("小键盘", theme, scale, on = numpad, onTap = {
                        scope.launch { app.settingsStore.setKeyboardNumpad(!numpad) }
                    })
                    ToolbarChip("-", theme, scale, onTap = {
                        scope.launch { app.settingsStore.setKeyboardScale(scale - 0.1f) }
                    })
                    BasicText(
                        text = "${(scale * 100).roundToInt()}%",
                        style = TextStyle(
                            color = theme.toolbarText,
                            fontSize = (9.5f * scale).sp
                        ),
                        maxLines = 1
                    )
                    ToolbarChip("+", theme, scale, onTap = {
                        scope.launch { app.settingsStore.setKeyboardScale(scale + 0.1f) }
                    })
                    ToolbarChip("主题", theme, scale, onTap = {
                        val next = when (themeId) {
                            "light" -> "dark"; "dark" -> "blue"; "blue" -> "glass"
                            "glass" -> "transparent"
                            else -> "light"
                        }
                        scope.launch { app.settingsStore.setKeyboardTheme(next) }
                    })
                    Spacer(Modifier.weight(1f))
                    ToolbarChip("▾", theme, scale, onTap = { VirtualKeyboardController.hide() })
                }

                // ===== 功能键行 =====
                if (funcRow) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(funcH),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        KeyButton(
                            "Esc", 1f, theme, keyH = funcH, scale = scale,
                            special = true, touchFeedback = touchFeedback,
                            onTapKey = { tapFeedback() },
                            onClick = { onKey(SPECIAL_KEYS.first { it.label == "Esc" }) },
                            modifier = Modifier.weight(1.1f)
                        )
                        (1..12).forEach { i ->
                            KeyButton(
                                "F$i", 1f, theme, keyH = funcH, scale = scale,
                                special = true, touchFeedback = touchFeedback,
                                onTapKey = { tapFeedback() },
                                onClick = { onKey(FN_KEY) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // ===== 主区 + 小键盘 + 方向键 =====
                // v2.13.2：垂直 Bottom 对齐 —— 方向键组按内容高度沉底（Windows 习惯），
                // 同时杜绝 v2.13.1 方向键列垂直 weight 拉伸占满屏幕的问题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // 主键区
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        MAIN_ROWS.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().height(keyH),
                                horizontalArrangement = Arrangement.spacedBy(gap)
                            ) {
                                row.forEach { k ->
                                    KeyButton(
                                        k.label, k.weight, theme, keyH = keyH, scale = scale,
                                        special = k.special,
                                        active = when {
                                            k.action == KbAction.Shift && shiftState > 0 -> true
                                            k.action == KbAction.Caps && capsOn -> true
                                            k.action == KbAction.Ctrl && ctrl -> true
                                            k.action == KbAction.Alt && alt -> true
                                            k.action == KbAction.Win && winKey -> true
                                            else -> false
                                        },
                                        showShifted = k.action == KbAction.Char && shiftState > 0,
                                        shiftedLabel = k.shifted,
                                        touchFeedback = touchFeedback,
                                        onTapKey = { tapFeedback() },
                                        onClick = { onKey(k) },
                                        modifier = Modifier.weight(k.weight)
                                    )
                                }
                            }
                        }
                    }

                    // 小键盘（v2.13.2：窄屏强制隐藏，避免主键区被挤到不可用）
                    if (showNumpad) {
                        Column(
                            modifier = Modifier.width((176 * scale).dp),
                            verticalArrangement = Arrangement.spacedBy(gap)
                        ) {
                            NUMPAD_ROWS.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(keyH),
                                    horizontalArrangement = Arrangement.spacedBy(gap)
                                ) {
                                    row.forEach { k ->
                                        KeyButton(
                                            k.label, k.weight, theme, keyH = keyH, scale = scale,
                                            special = k.special, touchFeedback = touchFeedback,
                                            onTapKey = { tapFeedback() },
                                            onClick = { onKey(k) },
                                            modifier = Modifier.weight(k.weight)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 方向键区（v2.13.2 重写：独立于小键盘开关 —— Windows 关小键盘后
                    // 方向键仍在）。
                    // ⚠️ 布局红线：本区所有键固定 height(keyH)，严禁垂直 weight ——
                    // 这条链的 maxHeight 是"屏幕剩余高度"（有界但巨大），weight 默认
                    // fill=true 会把键强制拉伸平分屏幕高度，整个键盘被撑到全屏
                    // （v2.13.1 "上下左右键占满屏幕" 的根因）。
                    if (compactArrows) {
                        // 窄屏降级：单列竖排，每键固定高
                        Column(
                            modifier = Modifier.width((44 * scale).dp),
                            verticalArrangement = Arrangement.spacedBy(gap)
                        ) {
                            listOf(
                                "↑" to KbAction.Up, "↓" to KbAction.Down,
                                "←" to KbAction.Left, "→" to KbAction.Right
                            ).forEach { (label, action) ->
                                KeyButton(
                                    label, 1f, theme, keyH = keyH, scale = scale,
                                    special = true, touchFeedback = touchFeedback,
                                    onTapKey = { tapFeedback() },
                                    onClick = { onKey(KbKey(label, null, 1f, true, action)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        // 标准布局：↑ 居中一行 + ←↓→ 一行，随 Row 整体底部对齐
                        Column(
                            modifier = Modifier.width((136 * scale).dp),
                            verticalArrangement = Arrangement.spacedBy(gap)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(keyH),
                                horizontalArrangement = Arrangement.spacedBy(gap)
                            ) {
                                Spacer(Modifier.weight(1f))
                                KeyButton(
                                    "↑", 1f, theme, keyH = keyH, scale = scale,
                                    special = true, touchFeedback = touchFeedback,
                                    onTapKey = { tapFeedback() },
                                    onClick = { onKey(KbKey("↑", null, 1f, true, KbAction.Up)) },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.weight(1f))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().height(keyH),
                                horizontalArrangement = Arrangement.spacedBy(gap)
                            ) {
                                KeyButton(
                                    "←", 1f, theme, keyH = keyH, scale = scale,
                                    special = true, touchFeedback = touchFeedback,
                                    onTapKey = { tapFeedback() },
                                    onClick = { onKey(KbKey("←", null, 1f, true, KbAction.Left)) },
                                    modifier = Modifier.weight(1f)
                                )
                                KeyButton(
                                    "↓", 1f, theme, keyH = keyH, scale = scale,
                                    special = true, touchFeedback = touchFeedback,
                                    onTapKey = { tapFeedback() },
                                    onClick = { onKey(KbKey("↓", null, 1f, true, KbAction.Down)) },
                                    modifier = Modifier.weight(1f)
                                )
                                KeyButton(
                                    "→", 1f, theme, keyH = keyH, scale = scale,
                                    special = true, touchFeedback = touchFeedback,
                                    onTapKey = { tapFeedback() },
                                    onClick = { onKey(KbKey("→", null, 1f, true, KbAction.Right)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 键定义
// ============================================================

private enum class KbAction {
    Char, Shift, Caps, Ctrl, Alt, Win, Backspace, Delete, Tab, Enter,
    Space, Esc, Up, Down, Left, Right, Home, End, None
}

private data class KbKey(
    val label: String,
    val shifted: String? = null,
    val weight: Float = 1f,
    val special: Boolean = false,
    val action: KbAction = KbAction.None
)

private fun ch(c: String, s: String) = KbKey(c, s, 1f, false, KbAction.Char)
private fun sp(label: String, action: KbAction, weight: Float = 1f) =
    KbKey(label, null, weight, true, action)
private fun num(c: String) = KbKey(c, null, 1f, false, KbAction.Char)

private val FN_KEY = KbKey("Fn", null, 1f, true, KbAction.None)

private val SPECIAL_KEYS = listOf(
    sp("Esc", KbAction.Esc),
    sp("Tab", KbAction.Tab),
    sp("Caps", KbAction.Caps),
    sp("Shift", KbAction.Shift),
    sp("Ctrl", KbAction.Ctrl),
    sp("Alt", KbAction.Alt),
    sp("Win", KbAction.Win),
    sp("Bksp", KbAction.Backspace),
    sp("Enter", KbAction.Enter)
)

private val MAIN_ROWS = listOf(
    listOf(
        ch("`", "~"), ch("1", "!"), ch("2", "@"), ch("3", "#"), ch("4", "$"),
        ch("5", "%"), ch("6", "^"), ch("7", "&"), ch("8", "*"), ch("9", "("),
        ch("0", ")"), ch("-", "_"), ch("=", "+"),
        sp("Bksp", KbAction.Backspace, 2f)
    ),
    listOf(
        sp("Tab", KbAction.Tab, 1.5f),
        ch("q", "Q"), ch("w", "W"), ch("e", "E"), ch("r", "R"), ch("t", "T"),
        ch("y", "Y"), ch("u", "U"), ch("i", "I"), ch("o", "O"), ch("p", "P"),
        ch("[", "{"), ch("]", "}"), ch("\\", "|")
    ),
    listOf(
        sp("Caps", KbAction.Caps, 1.8f),
        ch("a", "A"), ch("s", "S"), ch("d", "D"), ch("f", "F"), ch("g", "G"),
        ch("h", "H"), ch("j", "J"), ch("k", "K"), ch("l", "L"),
        ch(";", ":"), ch("'", "\""),
        sp("Enter", KbAction.Enter, 2.2f)
    ),
    listOf(
        sp("Shift", KbAction.Shift, 2.4f),
        ch("z", "Z"), ch("x", "X"), ch("c", "C"), ch("v", "V"), ch("b", "B"),
        ch("n", "N"), ch("m", "M"),
        ch(",", "<"), ch(".", ">"), ch("/", "?"),
        sp("Shift", KbAction.Shift, 2.4f)
    ),
    listOf(
        sp("Ctrl", KbAction.Ctrl, 1.4f),
        sp("Win", KbAction.Win, 1.4f),
        sp("Alt", KbAction.Alt, 1.4f),
        sp("空格", KbAction.Space, 6f),
        sp("Alt", KbAction.Alt, 1.4f),
        sp("Ctrl", KbAction.Ctrl, 1.4f)
    )
)

private val NUMPAD_ROWS = listOf(
    listOf(sp("NL", KbAction.None), num("/"), num("*"), num("-")),
    listOf(num("7"), num("8"), num("9"), num("+")),
    listOf(num("4"), num("5"), num("6"), num("-")),
    listOf(num("1"), num("2"), num("3"), sp("Ent", KbAction.Enter)),
    listOf(KbKey("0", null, 2f, false, KbAction.Char), num("."), num("="))
)

// ============================================================
// 主题
// ============================================================

internal data class KbTheme(
    val bg: Color,
    val keyBg: Color,
    val keyPressed: Color,
    val keyActive: Color,
    val specialBg: Color,
    val keyText: Color,
    val specialText: Color,
    val toolbarText: Color
)

internal fun kbThemeOf(id: String): KbTheme = when (id) {
    "light" -> KbTheme(
        bg = Color(0xFFE9E9EF), keyBg = Color.White, keyPressed = Color(0xFFD9D9E2),
        keyActive = Color(0xFFB7D0F5), specialBg = Color(0xFFDCDCE4),
        keyText = Color(0xFF1B1B1F), specialText = Color(0xFF3A3A44),
        toolbarText = Color(0xFF5A5A66)
    )
    "blue" -> KbTheme(
        bg = Color(0xFF1E2C42), keyBg = Color(0xFF2E4162), keyPressed = Color(0xFF3A5178),
        keyActive = Color(0xFF24507E), specialBg = Color(0xFF27395A),
        keyText = Color(0xFFE8EEF8), specialText = Color(0xFFB9C8E0),
        toolbarText = Color(0xFF8FA6C8)
    )
    "glass" -> KbTheme(
        bg = Color(0xCC181820), keyBg = Color(0x2EFFFFFF), keyPressed = Color(0x55FFFFFF),
        keyActive = Color(0x802C7BE5), specialBg = Color(0x1FFFFFFF),
        keyText = Color.White, specialText = Color(0xFFDDDDDD),
        toolbarText = Color(0xFFBBBBBB)
    )
    "transparent" -> KbTheme( // v2.24：全透明背板（无背景，仅键体留半透明玻璃片）
        bg = Color(0x00000000), keyBg = Color(0x2EFFFFFF), keyPressed = Color(0x61FFFFFF),
        keyActive = Color(0x802C7BE5), specialBg = Color(0x1FFFFFFF),
        keyText = Color.White, specialText = Color(0xFFDDDDDD),
        toolbarText = Color(0xFFBBBBBB)
    )
    else -> KbTheme( // dark
        bg = Color(0xFF26262C), keyBg = Color(0xFF3B3B44), keyPressed = Color(0xFF4E4E59),
        keyActive = Color(0xFF2E5077), specialBg = Color(0xFF30303A),
        keyText = Color(0xFFF0F0F3), specialText = Color(0xFFC8C8D0),
        toolbarText = Color(0xFF9A9AA6)
    )
}

// ============================================================
// 键体 / 工具栏组件
// ============================================================

/**
 * 键体。用 pointerInput（detectTapGestures）实现点击 —— 不请求焦点，
 * 不会抢走编辑框的焦点（clickable 会）。
 */
@Composable
private fun KeyButton(
    label: String,
    weight: Float,
    theme: KbTheme,
    keyH: Dp,
    scale: Float,
    special: Boolean,
    active: Boolean = false,
    showShifted: Boolean = false,
    shiftedLabel: String? = null,
    touchFeedback: Boolean,
    onTapKey: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (weight <= 0f) return // 占位键不渲染
    var pressed by remember { mutableStateOf(false) }
    // rememberUpdatedState 消除陈旧闭包：pointerInput 不因回调变化而重启
    val currentOnTapKey by rememberUpdatedState(onTapKey)
    val currentOnClick by rememberUpdatedState(onClick)
    val scaleAnim by animateFloatAsState(
        targetValue = if (pressed && touchFeedback) 0.88f else 1f,
        animationSpec = tween(70), label = "keyScale"
    )
    val bg by animateColorAsState(
        targetValue = when {
            active -> theme.keyActive
            pressed -> theme.keyPressed
            special -> theme.specialBg
            else -> theme.keyBg
        },
        animationSpec = tween(70), label = "keyBg"
    )
    val display = if (showShifted && shiftedLabel != null) shiftedLabel else label
    Box(
        modifier = modifier
            .height(keyH)
            .scale(scaleAnim)
            .clip(RoundedCornerShape((5 * scale).dp))
            .background(bg)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try { awaitRelease() } finally { pressed = false }
                    },
                    onTap = {
                        currentOnTapKey()
                        currentOnClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = display,
            style = TextStyle(
                color = if (special || active) theme.specialText else theme.keyText,
                fontSize = ((if (special) 10.5f else 13f) * scale).sp,
                fontWeight = if (special) FontWeight.Medium else FontWeight.Normal
            ),
            maxLines = 1
        )
    }
}

/** 工具栏小按钮（拖动手柄支持拖动） */
@Composable
private fun ToolbarChip(
    label: String,
    theme: KbTheme,
    scale: Float,
    on: Boolean = false,
    onTap: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (androidx.compose.ui.input.pointer.PointerInputChange, Offset) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {}
) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val bg by animateColorAsState(
        targetValue = when {
            on -> theme.keyActive
            pressed -> theme.keyPressed
            else -> theme.specialBg
        },
        animationSpec = tween(70), label = "chipBg"
    )
    Box(
        modifier = Modifier
            .height((20 * scale).dp)
            .clip(RoundedCornerShape((4 * scale).dp))
            .background(bg)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try { awaitRelease() } finally { pressed = false }
                    },
                    onTap = { currentOnTap() }
                )
            }
            .pointerInput(label) {
                detectDragGestures(
                    onDragStart = { currentOnDragStart() },
                    onDrag = { c, a -> currentOnDrag(c, a) },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() }
                )
            }
            .padding(horizontal = (7 * scale).dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (on) theme.keyText else theme.toolbarText,
                fontSize = (9.5f * scale).sp
            ),
            maxLines = 1
        )
    }
}
