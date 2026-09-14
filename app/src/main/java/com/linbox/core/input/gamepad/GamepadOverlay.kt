package com.linbox.core.input.gamepad

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.input.boundsInWindowCompat
import com.linbox.core.shell.ShellController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 虚拟手柄覆盖层（v2.15）：现代化绘制 + 全屏元素独立交互。
 *
 * - 摇杆：玻璃外环 + 径向渐变摇杆头 + 8 向指示灯，跟随手指，
 *   死区 + 8 向扇区判定（含斜向），方向事件实时派发
 * - 十字键：圆角十字 + 方向箭头，按触点位置判定 8 向（含斜向）
 * - 按钮：径向渐变圆钮（A绿/B红/X蓝/Y黄/其他青），按压缩放 + 辉光环
 * - 编辑模式：元素可拖动重新布局（松手持久化），显示删除角标
 * - 迷你工具条：⚙ 设置 / ✎ 编辑 / ✕ 隐藏
 * - 多点触控：每个元素独立 pointerInput，摇杆+按钮可同时操作
 *
 * v2.32：X11 界面的手柄改由 X11TouchSplitLayout 容器内的独立 Compose
 * 宿主（ComposeView）渲染 —— View 层逐指分流，手柄指针与屏幕指针
 * 从事件源头分离，本层（LinBoxShell 顶层覆盖）在 X11 模式下不再渲染，
 * 消除"手柄先按下时 Compose 命中路径锁定手柄分支、屏幕指针到不了
 * LorieView"的机制性冲突。终端/设置页仍由本层渲染（WebView 兜底）。
 */
@Composable
fun GamepadOverlay() {
    val app = LinBoxApp.get()
    val gamepadEnabled by app.settingsStore.gamepadEnabled.collectAsState(initial = false)
    // v2.32：X11 界面由 X11TouchSplitLayout 内的 Compose 宿主渲染，本层跳过
    if (!gamepadEnabled || ShellController.screen == ShellController.Screen.X11) return
    GamepadOverlayContent()
}

/**
 * 手柄覆盖层主体（v2.32 自 GamepadOverlay 拆出，两处复用）：
 * - LinBoxShell 顶层覆盖（终端/设置页）
 * - X11TouchSplitLayout 内 ComposeView 宿主（X11 界面，配合 View 层分流）
 * 副作用（布局加载/隐藏时释放按键+命中矩形）随本组合创建与销毁。
 */
@Composable
fun GamepadOverlayContent() {
    val app = LinBoxApp.get()
    val gamepadEnabled by app.settingsStore.gamepadEnabled.collectAsState(initial = false)
    if (!gamepadEnabled) return

    // 首次组合：加载持久化布局
    LaunchedEffect(Unit) {
        val json = app.settingsStore.gamepadConfig.first()
        GamepadController.loadFromJson(json.ifBlank { null })
    }

    // v2.15.3：手柄隐藏/离开组合时释放全部按下的键（防联键：不留任何"按着"的键）
    // v2.16.4：同时清空元素命中矩形（防陈旧矩形令浏览器侧误剥离正常触摸）
    // v2.20：同时注销工具条直通区
    // v2.32：同时清空工具条命中矩形（X11 分流判定区随组合销毁）
    DisposableEffect(gamepadEnabled) {
        onDispose {
            GamepadController.releaseAllKeys()
            GamepadController.clearElementHits()
            GamepadController.clearToolbarHit()
            TrackpadRouter.registerPassthrough("gpToolbar", null)
        }
    }

    val scope = rememberCoroutineScope()

    fun persistConfig() {
        scope.launch { app.settingsStore.setGamepadConfig(GamepadController.toJson()) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWpx = with(LocalDensity.current) { maxWidth.toPx() }
        val screenHpx = with(LocalDensity.current) { maxHeight.toPx() }

        // ===== 手柄元素 =====
        GamepadController.elements.forEach { element ->
            key(element.id) {
                val centerX = screenWpx * element.posX
                val centerY = screenHpx * element.posY

                GamepadElementHost(
                    element = element,
                    centerXpx = centerX,
                    centerYpx = centerY,
                    screenWpx = screenWpx,
                    screenHpx = screenHpx,
                    onMove = { nx, ny ->
                        GamepadController.updateElements(
                            GamepadController.elements.map {
                                if (it.id == element.id) it.copy(posX = nx, posY = ny) else it
                            }
                        )
                        persistConfig()
                    },
                    onDelete = {
                        GamepadController.updateElements(
                            GamepadController.elements.filter { it.id != element.id }
                        )
                        persistConfig()
                    }
                ) { editBlocked ->
                    when (element.type) {
                        GamepadController.ElementType.JOYSTICK -> JoystickContent(
                            element = element,
                            inputEnabled = !editBlocked
                        )
                        GamepadController.ElementType.DPAD -> DpadContent(
                            element = element,
                            inputEnabled = !editBlocked
                        )
                        GamepadController.ElementType.BUTTON -> PadButtonContent(
                            element = element,
                            inputEnabled = !editBlocked
                        )
                    }
                }
            }
        }

        // ===== 迷你工具条（右上角） =====
        GamepadMiniToolbar(
            onSettings = {
                GamepadController.releaseAllKeys()
                GamepadController.settingsOpen = true
            },
            onToggleEdit = {
                GamepadController.releaseAllKeys()
                GamepadController.editMode = !GamepadController.editMode
                if (!GamepadController.editMode) GamepadController.selectElement(null)
            },
            onHide = {
                GamepadController.releaseAllKeys()
                GamepadController.editMode = false
                scope.launch { app.settingsStore.setGamepadEnabled(false) }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
                // v2.20：工具条登记为触控板直通区（真实手指可直接点 ⚙ ✎ ✕）
                // v2.32：同时登记为 X11 触摸分流的手柄侧命中区（⚙✎✕ 落点
                // 归手柄流，否则工具条指针被路由去 LorieView 点不到）
                .onGloballyPositioned {
                    val b = it.boundsInWindowCompat()
                    TrackpadRouter.registerPassthrough("gpToolbar", b)
                    GamepadController.registerToolbarHit(b.left, b.top, b.right, b.bottom)
                }
        )
    }
}

// ============================================================
// 元素宿主：编辑模式拖动 + 删除角标
// ============================================================

/**
 * 手柄元素宿主：
 * - 编辑模式：整体可拖动（松手持久化新位置），右上角显示删除角标，内部游戏输入禁用
 * - 游戏模式：内容自理输入，宿主不拦截事件
 */
@Composable
private fun GamepadElementHost(
    element: GamepadController.PadElement,
    centerXpx: Float,
    centerYpx: Float,
    screenWpx: Float,
    screenHpx: Float,
    onMove: (Float, Float) -> Unit,
    onDelete: () -> Unit,
    content: @Composable (editBlocked: Boolean) -> Unit
) {
    val density = LocalDensity.current
    val sizePx = with(density) { element.sizeDp.dp.toPx() }
    val radius = sizePx / 2f
    val editMode = GamepadController.editMode

    var editDrag by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (centerXpx - radius + editDrag.x).roundToInt(),
                    (centerYpx - radius + editDrag.y).roundToInt()
                )
            }
            .size(element.sizeDp.dp)
            // v2.16.4：把元素命中矩形（窗口坐标）登记给 GamepadController，
            // interop 容器据此把"落在手柄元素上"的指针从 MotionEvent 流中
            // 剥离，让页面拖动/点击与手柄操作互不干扰
            .onGloballyPositioned { coords ->
                val p = coords.positionInWindow()
                val s = coords.size
                GamepadController.registerElementHit(
                    element.id, p.x, p.y, p.x + s.width, p.y + s.height
                )
            }
            .then(
                if (editMode) {
                    // key 必须含 posX/posY：否则手势闭包捕获的是首次组合时的旧基准点，
                    // 拖动一次后元素会跳回旧位置附近（“乱跑”的根因）
                    Modifier.pointerInput(element.id, element.posX, element.posY) {
                        detectDragGestures(
                            onDrag = { change, amount ->
                                change.consume()
                                editDrag += amount
                            },
                            onDragEnd = {
                                val nx = ((centerXpx + editDrag.x) / screenWpx).coerceIn(0.04f, 0.96f)
                                val ny = ((centerYpx + editDrag.y) / screenHpx).coerceIn(0.04f, 0.96f)
                                editDrag = Offset.Zero
                                onMove(nx, ny)
                            },
                            onDragCancel = { editDrag = Offset.Zero }
                        )
                    }
                } else Modifier
            )
    ) {
        // 注：composable 函数类型调用不支持具名参数（Kotlin 2.0 起报错），
        // 这里的 editBlocked 必须按位置传递
        content(editMode)

        // 编辑模式删除角标
        if (editMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xE6EF4444))
                    .pointerInput(element.id) {
                        detectTapGestures(onTap = { onDelete() })
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ============================================================
// 迷你工具条
// ============================================================

@Composable
private fun GamepadMiniToolbar(
    onSettings: () -> Unit,
    onToggleEdit: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val editMode = GamepadController.editMode
    Row(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xD91E2836))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MiniToolButton("⚙") { onSettings() }
        MiniToolButton(
            label = if (editMode) "✓" else "✎",
            active = editMode
        ) { onToggleEdit() }
        MiniToolButton("✕") { onHide() }
    }
}

@Composable
private fun MiniToolButton(label: String, active: Boolean = false, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(30.dp)
            .scale(if (pressed) 0.88f else 1f)
            .clip(CircleShape)
            .background(
                when {
                    active -> Color(0x662DD4BF)
                    pressed -> Color(0x332DD4BF)
                    else -> Color(0x1AFFFFFF)
                }
            )
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try { awaitRelease() } finally { pressed = false }
                    },
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFFE2F5FF), fontSize = 13.sp)
    }
}

// ============================================================
// 摇杆
// ============================================================

/**
 * 摇杆内容：外环（玻璃质感 + 青色描边）+ 8 向指示灯 + 渐变摇杆头。
 * 摇杆头跟随手指（限制在外环内），死区 25%，8 向扇区判定（含斜向）。
 */
@Composable
private fun JoystickContent(
    element: GamepadController.PadElement,
    inputEnabled: Boolean
) {
    val density = LocalDensity.current
    val sizeDp = element.sizeDp.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val radius = sizePx / 2f
    val haptic = LocalHapticFeedback.current

    // 摇杆头偏移（px）
    var stickOffset by remember { mutableStateOf(Offset.Zero) }
    // 当前按下方向
    var activeDirs by remember { mutableStateOf(setOf<Char>()) }

    // 元素引用更新时保持手势 lambda 最新
    val currentElement by rememberUpdatedState(element)

    fun dirsFromOffset(off: Offset): Set<Char> {
        val mag = off.getDistance()
        if (mag < radius * 0.25f) return emptySet()
        val dirs = mutableSetOf<Char>()
        val v = off.y / mag
        val h = off.x / mag
        if (v < -0.38) dirs.add('U')
        if (v > 0.38) dirs.add('D')
        if (h < -0.38) dirs.add('L')
        if (h > 0.38) dirs.add('R')
        return dirs
    }

    fun updateDirs(newDirs: Set<Char>) {
        if (newDirs == activeDirs) return
        (activeDirs - newDirs).forEach { GamepadController.onDirection(it, false, currentElement.dirMode) }
        (newDirs - activeDirs).forEach {
            GamepadController.onDirection(it, true, currentElement.dirMode)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        activeDirs = newDirs
    }

    Box(modifier = Modifier.size(sizeDp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (inputEnabled) Modifier.pointerInput(currentElement.id, currentElement.dirMode) {
                        // v2.16.4：重写为 awaitEachGesture 且不再消费事件。
                        // 根因：摇杆按住时 consume() 会让 Compose interop 过滤器
                        // （PointerInteropFilter）判定"有指针变化被消费"而停止向
                        // 内嵌原生 View 派发事件 —— 手柄移动时页面收不到另一根
                        // 手指的拖动。去消费后由 interop 容器侧剥离手柄指针，
                        // 两边各收各的指针。
                        // requireUnconsumed=false：网页先按下（过滤器消费全部变化）
                        // 后再按摇杆也能响应。摇杆头直接跟随触点位置（相对中心限幅）。
                        // finally 兜底：手势被取消（如切编辑模式）时也清空方向，
                        // 杜绝协程被取消导致的"方向键卡住"（v2.15.3）
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            try {
                                fun applyPointer(pos: Offset) {
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    var stick = pos - center
                                    val maxStick = radius * 0.72f
                                    val mag = stick.getDistance()
                                    if (mag > maxStick && mag > 0f) {
                                        stick = stick / mag * maxStick
                                    }
                                    stickOffset = stick
                                    updateDirs(dirsFromOffset(stick))
                                }
                                applyPointer(down.position)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: continue
                                    if (!change.pressed) break
                                    applyPointer(change.position)
                                }
                            } finally {
                                stickOffset = Offset.Zero
                                updateDirs(emptySet())
                            }
                        }
                    } else Modifier
                )
        ) {
            JoystickCanvas(
                sizeDp = sizeDp,
                stickOffset = stickOffset,
                activeDirs = activeDirs,
                dirModeLabel = if (currentElement.dirMode == GamepadController.DirMode.WASD) "WASD" else "←↑→↓"
            )
        }
    }
}

/** 摇杆绘制：外环 + 主4向+斜4向指示灯 + 渐变摇杆头 */
@Composable
private fun JoystickCanvas(
    sizeDp: Dp,
    stickOffset: Offset,
    activeDirs: Set<Char>,
    dirModeLabel: String
) {
    Box(modifier = Modifier.size(sizeDp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)

            // ===== 外环底（玻璃质感）=====
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x2EFFFFFF), Color(0x141E2836)),
                    center = c,
                    radius = r
                ),
                radius = r,
                center = c
            )
            // 外环青色描边
            drawCircle(
                color = Color(0x802DD4BF),
                radius = r,
                center = c,
                style = Stroke(width = r * 0.055f)
            )
            // 内环细描边
            drawCircle(
                color = Color(0x40FFFFFF),
                radius = r * 0.88f,
                center = c,
                style = Stroke(width = r * 0.02f)
            )

            // ===== 指示灯：主 4 向（基数位）+ 斜 4 向（斜向需两主向同时激活）=====
            // 主 4 向
            listOf(
                'U' to -90f, 'R' to 0f, 'D' to 90f, 'L' to 180f
            ).forEach { (dirChar, angleDeg) ->
                val angle = Math.toRadians(angleDeg.toDouble())
                val dotR = r * 0.78f
                val pos = Offset(
                    (c.x + dotR * cos(angle)).toFloat(),
                    (c.y + dotR * sin(angle)).toFloat()
                )
                val active = dirChar in activeDirs
                if (active) drawCircle(Color(0x552DD4BF), radius = r * 0.1f, center = pos)
                drawCircle(
                    color = if (active) Color(0xFF2DD4BF) else Color(0x30FFFFFF),
                    radius = r * (if (active) 0.055f else 0.038f),
                    center = pos
                )
            }
            // 斜 4 向（UR/RD/DL/LU）
            listOf(
                ('U' to 'R') to -45f,
                ('R' to 'D') to 45f,
                ('D' to 'L') to 135f,
                ('L' to 'U') to 225f
            ).forEach { (pair, angleDeg) ->
                val angle = Math.toRadians(angleDeg.toDouble())
                val dotR = r * 0.78f
                val pos = Offset(
                    (c.x + dotR * cos(angle)).toFloat(),
                    (c.y + dotR * sin(angle)).toFloat()
                )
                val active = pair.first in activeDirs && pair.second in activeDirs
                drawCircle(
                    color = if (active) Color(0xFF2DD4BF) else Color(0x26FFFFFF),
                    radius = r * (if (active) 0.045f else 0.03f),
                    center = pos
                )
            }

            // ===== 摇杆头（径向渐变球 + 高光）=====
            val stickR = r * 0.42f
            val stickCenter = c + stickOffset
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFF0FDFF), Color(0xFF7DD3FC), Color(0xFF2563EB)),
                    center = Offset(stickCenter.x - stickR * 0.3f, stickCenter.y - stickR * 0.3f),
                    radius = stickR * 1.4f
                ),
                radius = stickR,
                center = stickCenter
            )
            drawCircle(
                color = Color(0xAA0EA5E9),
                radius = stickR,
                center = stickCenter,
                style = Stroke(width = stickR * 0.08f)
            )
            drawCircle(
                color = Color(0xCCFFFFFF),
                radius = stickR * 0.18f,
                center = Offset(stickCenter.x - stickR * 0.35f, stickCenter.y - stickR * 0.35f)
            )
        }

        // 方向模式标签
        Text(
            text = dirModeLabel,
            color = Color(0xB3E2F5FF),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
        )
    }
}

// ============================================================
// 十字键
// ============================================================

/** 十字键内容：圆角十字 + 箭头，触点相对中心判定 8 向（含斜向） */
@Composable
private fun DpadContent(
    element: GamepadController.PadElement,
    inputEnabled: Boolean
) {
    val density = LocalDensity.current
    val sizeDp = element.sizeDp.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val radius = sizePx / 2f
    val haptic = LocalHapticFeedback.current

    var activeDirs by remember { mutableStateOf(setOf<Char>()) }
    val currentElement by rememberUpdatedState(element)

    fun dirsFromTouch(local: Offset): Set<Char> {
        val off = local - Offset(radius, radius)
        val mag = off.getDistance()
        if (mag < radius * 0.18f) return emptySet()
        val dirs = mutableSetOf<Char>()
        val v = off.y / mag
        val h = off.x / mag
        if (v < -0.38) dirs.add('U')
        if (v > 0.38) dirs.add('D')
        if (h < -0.38) dirs.add('L')
        if (h > 0.38) dirs.add('R')
        return dirs
    }

    fun updateDirs(newDirs: Set<Char>) {
        if (newDirs == activeDirs) return
        (activeDirs - newDirs).forEach { GamepadController.onDirection(it, false, currentElement.dirMode) }
        (newDirs - activeDirs).forEach {
            GamepadController.onDirection(it, true, currentElement.dirMode)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        activeDirs = newDirs
    }

    Box(modifier = Modifier.size(sizeDp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (inputEnabled) Modifier.pointerInput(currentElement.id, currentElement.dirMode) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            try {
                                updateDirs(dirsFromTouch(down.position))
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressChange = event.changes.lastOrNull { it.pressed }
                                    if (pressChange == null) break
                                    updateDirs(dirsFromTouch(pressChange.position))
                                    // v2.16.4：不再 consume() —— 消费会让 interop 过滤器
                                    // 停止向 WebView 派发事件（手柄移动时视角旋转失灵根因）；
                                    // 本元素的命中子树独占该指针，不消费也不会泄漏给下层
                                }
                            } finally {
                                // 任何退出路径（含协程取消）都清空方向 → 发出全部 UP（v2.15.3）
                                updateDirs(emptySet())
                            }
                        }
                    } else Modifier
                )
        ) {
            DpadCanvas(sizeDp = sizeDp, activeDirs = activeDirs)
        }
        // 方向模式标签
        Text(
            text = if (currentElement.dirMode == GamepadController.DirMode.WASD) "WASD" else "←↑→↓",
            color = Color(0xB3E2F5FF),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
        )
    }
}

/** 十字键绘制：细长圆角十字 + 玻璃底托 + 四向箭头 + 激活高亮 */
@Composable
private fun DpadCanvas(sizeDp: Dp, activeDirs: Set<Char>) {
    Canvas(modifier = Modifier.size(sizeDp)) {
        val s = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val arm = s * 0.155f      // 十字臂宽的一半（细臂：全宽约 31%）
        val len = s * 0.50f       // 十字臂长（从中心到端点，满伸至边缘）

        // ===== 玻璃底托（圆面 + 青色描边，现代化质感）=====
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x2EFFFFFF), Color(0x141E2836)),
                center = c,
                radius = len
            ),
            radius = len,
            center = c
        )
        drawCircle(
            color = Color(0x4D2DD4BF),
            radius = len,
            center = c,
            style = Stroke(width = s * 0.014f)
        )

        // ===== 十字身体（细臂，圆角端点）=====
        // 横臂
        drawRoundRect(
            color = Color(0x38FFFFFF),
            topLeft = Offset(c.x - len, c.y - arm),
            size = Size(len * 2, arm * 2),
            cornerRadius = CornerRadius(arm * 0.72f)
        )
        // 竖臂
        drawRoundRect(
            color = Color(0x38FFFFFF),
            topLeft = Offset(c.x - arm, c.y - len),
            size = Size(arm * 2, len * 2),
            cornerRadius = CornerRadius(arm * 0.72f)
        )
        // 描边
        drawRoundRect(
            color = Color(0x732DD4BF),
            topLeft = Offset(c.x - len, c.y - arm),
            size = Size(len * 2, arm * 2),
            cornerRadius = CornerRadius(arm * 0.72f),
            style = Stroke(width = s * 0.016f)
        )
        drawRoundRect(
            color = Color(0x732DD4BF),
            topLeft = Offset(c.x - arm, c.y - len),
            size = Size(arm * 2, len * 2),
            cornerRadius = CornerRadius(arm * 0.72f),
            style = Stroke(width = s * 0.016f)
        )

        // ===== 中心圆 =====
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x3DFFFFFF), Color(0x1F1E2836)),
                center = c,
                radius = arm * 0.92f
            ),
            radius = arm * 0.92f,
            center = c
        )
        drawCircle(
            color = Color(0x592DD4BF),
            radius = arm * 0.92f,
            center = c,
            style = Stroke(width = s * 0.012f)
        )

        // ===== 四向箭头 + 激活高亮 =====
        listOf(
            'U' to -90f, 'D' to 90f, 'L' to 180f, 'R' to 0f
        ).forEach { (char, angleDeg) ->
            val active = char in activeDirs
            val angle = Math.toRadians(angleDeg.toDouble())
            val dist = len * 0.74f
            val tipLen = s * 0.062f
            val baseHalf = s * 0.034f
            val dirVec = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            val tip = Offset(c.x + dirVec.x * (dist + tipLen), c.y + dirVec.y * (dist + tipLen))
            val baseCenter = Offset(c.x + dirVec.x * dist, c.y + dirVec.y * dist)
            val perp = Offset(-dirVec.y, dirVec.x)
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(baseCenter.x + perp.x * baseHalf, baseCenter.y + perp.y * baseHalf)
                lineTo(baseCenter.x - perp.x * baseHalf, baseCenter.y - perp.y * baseHalf)
                close()
            }
            drawPath(path, color = if (active) Color(0xFF2DD4BF) else Color(0xE6FFFFFF))

            if (active) {
                drawRoundRect(
                    color = Color(0x4D2DD4BF),
                    topLeft = when (char) {
                        'U' -> Offset(c.x - arm, c.y - len)
                        'D' -> Offset(c.x - arm, c.y + arm)
                        'L' -> Offset(c.x - len, c.y - arm)
                        else -> Offset(c.x + arm, c.y - arm)
                    },
                    size = when (char) {
                        'U', 'D' -> Size(arm * 2, len - arm)
                        else -> Size(len - arm, arm * 2)
                    },
                    cornerRadius = CornerRadius(arm * 0.5f)
                )
            }
        }
    }
}

// ============================================================
// 按钮
// ============================================================

/**
 * 手柄按钮：径向渐变圆钮 + 辉光环 + 按压缩放。
 * 颜色：A绿 / B红 / X蓝 / Y黄 / L青 / R紫 / 其他青。
 * 表面文字 = 映射后的按键名（J/K/L/U/I/O/Space…），不再显示 A/B/X/Y（v2.15.3）。
 * 手势（v2.15.3 防联键）：抬手/取消必发 UP（finally 兜底）；
 * 手指滑出按钮（含 25% 容差）立即抬起、滑回可再按下。
 */
@Composable
private fun PadButtonContent(
    element: GamepadController.PadElement,
    inputEnabled: Boolean
) {
    val sizeDp = element.sizeDp.dp
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val currentElement by rememberUpdatedState(element)

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = tween(80),
        label = "padBtnScale"
    )
    val colors = buttonColors(currentElement.label)

    // 按钮表面显示映射后的按键名；鼠标动作显示短标签
    val displayText = if (currentElement.action.kind == "mouse") {
        when (currentElement.action.keyCode) {
            GamepadController.PadAction.MOUSE_LEFT -> "左键"
            GamepadController.PadAction.MOUSE_RIGHT -> "右键"
            GamepadController.PadAction.MOUSE_MIDDLE -> "中键"
            GamepadController.PadAction.MOUSE_SCROLL_UP -> "滚↑"
            GamepadController.PadAction.MOUSE_SCROLL_DOWN -> "滚↓"
            else -> "鼠标"
        }
    } else currentElement.action.label
    val displayFontSize = when {
        displayText.length <= 2 -> sizeDp.value * 0.32f
        displayText.length == 3 -> sizeDp.value * 0.26f
        displayText.length == 4 -> sizeDp.value * 0.21f
        else -> sizeDp.value * 0.17f
    }.coerceIn(9f, 22f).sp

    Box(
        modifier = Modifier
            .size(sizeDp)
            .scale(pressScale)
            .shadow(6.dp, CircleShape, ambientColor = Color(0x662DD4BF))
            .clip(CircleShape)
            .background(Brush.radialGradient(colors))
            .then(
                if (inputEnabled) Modifier.pointerInput(currentElement.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        fun inside(p: Offset) =
                            p.x >= -w * 0.25f && p.x <= w * 1.25f &&
                            p.y >= -h * 0.25f && p.y <= h * 1.25f
                        var active = true
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        GamepadController.onButtonPress(currentElement)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                if (!change.pressed) break
                                val nowInside = inside(change.position)
                                if (active && !nowInside) {
                                    // 手指滑出按钮 → 立即抬起
                                    GamepadController.onButtonRelease(currentElement)
                                    active = false
                                    pressed = false
                                } else if (!active && nowInside) {
                                    // 滑回按钮 → 重新按下
                                    GamepadController.onButtonPress(currentElement)
                                    active = true
                                    pressed = true
                                }
                                // v2.16.4：不再 consume()（理由同摇杆/十字键：消费会
                                // 令 interop 过滤器停止向 WebView 派发事件）
                            }
                        } finally {
                            // 无论正常抬手、滑出后抬手还是手势被取消，UP 必发
                            GamepadController.onButtonRelease(currentElement)
                            pressed = false
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // 辉光外环 + 顶部高光弧
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                color = Color(0x59FFFFFF),
                radius = r,
                center = Offset(r, r),
                style = Stroke(width = r * 0.09f)
            )
            if (pressed) {
                drawCircle(
                    color = Color(0x552DD4BF),
                    radius = r * 1.02f,
                    center = Offset(r, r),
                    style = Stroke(width = r * 0.16f)
                )
            }
            drawArc(
                color = Color(0x40FFFFFF),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(r * 0.16f, r * 0.16f),
                size = Size(r * 1.68f, r * 1.68f),
                style = Stroke(width = r * 0.07f, cap = StrokeCap.Round)
            )
        }
        Text(
            text = displayText,
            color = Color.White,
            fontSize = displayFontSize,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

/** 按钮配色：A绿 / B红 / X蓝 / Y黄 / L青 / R紫 / 其他青 */
private fun buttonColors(label: String): List<Color> = when (label.uppercase()) {
    "A" -> listOf(Color(0xFF86EFAC), Color(0xFF22C55E), Color(0xFF15803D))
    "B" -> listOf(Color(0xFFFCA5A5), Color(0xFFEF4444), Color(0xFFB91C1C))
    "X" -> listOf(Color(0xFF93C5FD), Color(0xFF3B82F6), Color(0xFF1D4ED8))
    "Y" -> listOf(Color(0xFFFDE68A), Color(0xFFEAB308), Color(0xFFA16207))
    "L1", "L2" -> listOf(Color(0xFFA5F3FC), Color(0xFF06B6D4), Color(0xFF0E7490))
    "R1", "R2" -> listOf(Color(0xFFC4B5FD), Color(0xFF8B5CF6), Color(0xFF6D28D9))
    else -> listOf(Color(0xFFA5F3FC), Color(0xFF2DD4BF), Color(0xFF0F766E))
}
