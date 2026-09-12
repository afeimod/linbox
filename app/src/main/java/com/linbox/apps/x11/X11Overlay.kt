package com.linbox.apps.x11

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.input.gamepad.GamepadController
import com.termux.x11.X11InputHub
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * v2.25：X11 界面悬浮层 —— 玻璃悬浮球 + 附加键盘栏。
 *
 * 用户需求："悬浮球也要显示在 x11 界面，x11 的设置也放进去，不要在
 * x11 底边了"。原底边控制条（分辨率/键盘/游戏全屏/手柄/X11设置/全屏）
 * 全部收编进与终端同款的玻璃悬浮球（GlassFab 视觉规格一致）：
 *
 * - 可拖动、呼吸动画、玻璃胶囊菜单错峰浮现；位置与终端悬浮球共享
 *   （同一归一化坐标，跨界面保持同一角落）；
 * - "全屏"项进入真全屏（画面独占，球体与键行隐藏，返回键退出）；
 * - 附加键盘栏（termux-x11 showAdditionalKbd 对应物）：ESC/TAB/CTRL/
 *   ALT/方向键等玻璃键帽，点按经 X11InputHub 直注 X（无需焦点）。
 */

/** 悬浮球菜单项数据（onClick 放在末位以支持尾随 lambda 写法）。 */
private data class X11FabItem(
    val label: String,
    val glyph: String,
    val active: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit
)

@Composable
fun X11GlassFab(
    areaPx: IntSize,
    gamepadEnabled: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
    onBackToTerminal: () -> Unit
) {
    val app = LinBoxApp.get()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    // 悬浮球位置（归一化 0..1，与终端悬浮球共享）+ 拖动偏移
    val fabPosX by app.settingsStore.fabPosX.collectAsState(initial = 0.85f)
    val fabPosY by app.settingsStore.fabPosY.collectAsState(initial = 0.85f)
    var expanded by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }

    val fabSizePx = with(density) { 54.dp.toPx() }
    val itemHPx = with(density) { 38.dp.toPx() }
    val gapPx = with(density) { 6.dp.toPx() }
    val menuWPx = with(density) { 118.dp.toPx() }
    val maxX = (areaPx.width - fabSizePx).coerceAtLeast(0f)
    val maxY = (areaPx.height - fabSizePx).coerceAtLeast(0f)

    val x = (maxX * fabPosX + dragOffset.x).coerceIn(0f, maxX)
    val y = (maxY * fabPosY + dragOffset.y).coerceIn(0f, maxY)
    // 球体在下半屏 → 菜单向上展开；上半屏 → 向下展开
    val expandUp = y > maxY * 0.45f

    fun persistPos() {
        if (maxX <= 0f || maxY <= 0f) return
        val cx = (maxX * fabPosX + dragOffset.x).coerceIn(0f, maxX)
        val cy = (maxY * fabPosY + dragOffset.y).coerceIn(0f, maxY)
        dragOffset = Offset.Zero
        val nx = (cx / maxX).coerceIn(0f, 1f)
        val ny = (cy / maxY).coerceIn(0f, 1f)
        scope.launch { app.settingsStore.setFabPos(nx, ny) }
    }

    val items = remember(
        gamepadEnabled, fullscreen,
        onToggleFullscreen, onOpenSettings, onBackToTerminal
    ) {
        listOf(
            X11FabItem("键盘", "⌨") { toggleX11Ime(context) },
            X11FabItem("游戏全屏", "FS") { X11InputHub.sendAltEnter() },
            X11FabItem("虚拟手柄", "柄", active = gamepadEnabled,
                onClick = { scope.launch { app.settingsStore.setGamepadEnabled(!gamepadEnabled) } },
                onLongClick = { GamepadController.settingsOpen = true }),
            // v2.26：原虚拟手柄右上角迷你工具条 ⚙ 设置收编进悬浮球
            X11FabItem("手柄设置", "🎮") {
                GamepadController.releaseAllKeys()
                GamepadController.settingsOpen = true
            },
            X11FabItem(if (fullscreen) "退出全屏" else "全屏", "□") { onToggleFullscreen() },
            X11FabItem("X11 设置", "⚙") { onOpenSettings() },
            X11FabItem("回终端", "⌂") { onBackToTerminal() }
        )
    }

    // 呼吸动画（与终端悬浮球同规格）
    val pulse by rememberInfiniteTransition(label = "x11FabPulse").animateFloat(
        initialValue = 0.95f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "x11FabPulseScale"
    )
    val expand by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "x11FabExpand"
    )
    val menuHPx = items.size * (itemHPx + gapPx)

    Box(Modifier.fillMaxSize()) {
        // 展开时的半透明遮罩：点击任意处收起（折叠时不存在，不拦截画面触摸）
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { expanded = false } }
            )
        }

        // ===== 玻璃胶囊菜单 =====
        Column(
            modifier = Modifier
                .offset {
                    val menuLeft = (x + fabSizePx / 2f - menuWPx / 2f)
                        .coerceIn(0f, (areaPx.width - menuWPx).coerceAtLeast(0f))
                    val menuTop = if (expandUp) y - menuHPx * expand - with(density) { 10.dp.toPx() }
                    else y + fabSizePx + with(density) { 10.dp.toPx() }
                    IntOffset(menuLeft.roundToInt(), menuTop.roundToInt())
                }
                .width(118.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEachIndexed { i, item ->
                val progress = ((expand * 1.4f) - i * 0.06f).coerceIn(0f, 1f)
                X11FabMenuItem(item = item, progress = progress)
            }
        }

        // ===== 玻璃球本体 =====
        val ballBorder = Brush.linearGradient(
            listOf(
                if (expanded) Color(0xB369F0AE) else Color(0x8CFFFFFF),
                if (expanded) Color(0x3300E676) else Color(0x1FFFFFFF)
            )
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(54.dp)
                .graphicsLayer {
                    if (!dragging) {
                        scaleX = pulse
                        scaleY = pulse
                    }
                }
                .shadow(
                    elevation = if (expanded) 18.dp else 10.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color(0xFF00E676),
                    spotColor = Color(0xFF00E676)
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0x66FFFFFF), Color(0x26FFFFFF), Color(0x0FFFFFFF)),
                        start = Offset.Zero,
                        end = Offset.Infinite
                    )
                )
                .border(1.2.dp, ballBorder, CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x3300E676), Color(0x0000E676))
                    )
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount
                        },
                        onDragEnd = { dragging = false; persistPos() },
                        onDragCancel = { dragging = false; persistPos() }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        expanded = !expanded
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "X",
                color = Color(0xFF69F0AE),
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 玻璃胶囊菜单项：progress 0..1 控制浮现（透明度 + 缩放），未浮现完成前不可点。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun X11FabMenuItem(item: X11FabItem, progress: Float) {
    val scale = 0.7f + 0.3f * progress
    val clickable = if (progress > 0.2f) {
        if (item.onLongClick != null) {
            Modifier.combinedClickable(
                onClick = { item.onClick() },
                onLongClick = { item.onLongClick!!() }
            )
        } else Modifier.combinedClickable(onClick = { item.onClick() })
    } else Modifier
    Row(
        modifier = Modifier
            .graphicsLayer {
                alpha = progress
                scaleX = scale
                scaleY = scale
            }
            .height(38.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(
                if (item.active) Brush.linearGradient(listOf(Color(0x9600C853), Color(0x5C00E676)))
                else Brush.linearGradient(listOf(Color(0x3DFFFFFF), Color(0x1AFFFFFF)))
            )
            .border(
                0.8.dp,
                if (item.active) Brush.linearGradient(listOf(Color(0xAA69F0AE), Color(0x3300E676)))
                else Brush.linearGradient(listOf(Color(0x59FFFFFF), Color(0x1FFFFFFF))),
                RoundedCornerShape(19.dp)
            )
            .then(clickable)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            item.glyph,
            color = if (item.active) Color(0xFFB9F6CA) else Color(0xFF69F0AE),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            item.label,
            color = Color.White.copy(alpha = 0.93f),
            fontSize = 12.sp
        )
        if (item.active) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFB9F6CA))
            )
        }
    }
}

/**
 * X11 附加键盘栏（termux-x11 showAdditionalKbd 对应物）：
 * 画面底部一排玻璃键帽，点按经 X11InputHub 直注 X server ——
 * 不依赖焦点，wine/桌面环境均立即生效；CTRL/ALT 为粘滞修饰
 * （点亮后作用于下一个按键，用后自动释放）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun X11ExtraKeysBar() {
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        X11KeyChip("CTRL", active = ctrl) { ctrl = !ctrl }
        X11KeyChip("ALT", active = alt) { alt = !alt }
        listOf(
            "ESC" to KeyEvent.KEYCODE_ESCAPE,
            "TAB" to KeyEvent.KEYCODE_TAB,
            "↑" to KeyEvent.KEYCODE_DPAD_UP,
            "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
            "←" to KeyEvent.KEYCODE_DPAD_LEFT,
            "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
            "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
            "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
            "END" to KeyEvent.KEYCODE_MOVE_END,
            "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
            "回车" to KeyEvent.KEYCODE_ENTER
        ).forEach { (label, keyCode) ->
            X11KeyChip(label, active = false) {
                // 粘滞修饰：先按下 → 目标键按下/抬起 → 修饰抬起并释放粘滞态
                if (ctrl) X11InputHub.forwardKey(KeyEvent.KEYCODE_CTRL_LEFT, true)
                if (alt) X11InputHub.forwardKey(KeyEvent.KEYCODE_ALT_LEFT, true)
                X11InputHub.forwardKey(keyCode, true)
                X11InputHub.forwardKey(keyCode, false)
                if (ctrl) { X11InputHub.forwardKey(KeyEvent.KEYCODE_CTRL_LEFT, false); ctrl = false }
                if (alt) { X11InputHub.forwardKey(KeyEvent.KEYCODE_ALT_LEFT, false); alt = false }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun X11KeyChip(label: String, active: Boolean, onTap: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(36.dp)
            .background(
                if (active) Color(0x661B5E20) else Color(0x3DFFFFFF),
                RoundedCornerShape(6.dp)
            )
            .border(0.8.dp, Color(0x59FFFFFF), RoundedCornerShape(6.dp))
            .combinedClickable(onClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onTap()
            }),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color(0xFF69F0AE) else Color(0xFFE0E0E0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** 切换 X11 软键盘（对齐原控制条行为：IMM 强制切换）。 */
private fun toggleX11Ime(context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    @Suppress("DEPRECATION")
    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
}
