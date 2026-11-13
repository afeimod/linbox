package com.linbox.core.input.gamepad

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.input.boundsInWindowCompat
import com.linbox.core.shell.ShellController
import com.linbox.core.theme.KeepBaseDensity
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.roundToInt

/**
 * v3.2.23（用户问题 4）：虚拟手柄常驻悬浮球。
 *
 * 替代旧迷你工具条（⚙✎✕ 三按钮，已移除）—— 手柄的所有控制收拢到一个
 * 炫彩半透明圆球（默认右上角，可拖到任意边缘）：
 *
 * - **单击**：展开/收起控制菜单（显示/收起手柄、手柄设置、编辑布局、
 *   保存/读取 .vgp 配置、关闭手柄）；
 * - **拖动**：移动悬浮球位置（松手持久化进 gamepad_config v3 的
 *   ballX/ballY）；
 * - 菜单展开时点菜单外任意处收起（一次牺牲点击，不打扰底下窗口语义）。
 *
 * **v3.4.6（用户："保存和读取配置应该在悬浮球主菜单位置啊，不应该放
 * 在编辑界面啊，因为读取配置后才可以编辑对应配置啊"）**：
 * - 「保存配置 / 读取配置（.vgp）」从手柄设置面板顶栏（v3.4.5 位置，
 * 编辑界面内）迁入**主菜单**——先读取配置、再编辑对应配置，层级与
 * 实际流程一致（读写逻辑见 [rememberGamepadProfileIo]）；
 * - 菜单列表重制（用户："换个好看好点击的方式"）：带标题头的圆角卡
 * 片、每项配彩色圆底**矢量自绘图标**（Canvas 画线，零字体字形依赖）、
 * 行高 ≥50dp 大触摸目标、分组细分隔线、危险项（关闭手柄）独立分区。
 *
 * 命中登记（缺一不可，否则球/菜单的触摸会被各输入层误吞或误穿透）： * - `GamepadController.registerToolbarHit`（球本体）：X11 智能触摸桥
 *   （SmartTouchBridge.filterPadPointers）按 isOverPadUi 剥离落在球上的
 *   指针 —— 不登记则按住球的手指会被当成屏幕手势，菜单点不到；
 * - `GamepadController.registerMenuHit`（展开菜单）：同上；
 * - `TrackpadRouter.registerPassthrough("gpBall", …)`：触控板模式下
 *   真实手指直触球/菜单（不当成手势）；
 * - `WindowFocusHook.raiseAt` / `TrackpadRouter.onMotionEvent` 侧按
 *   isOverPadUi 直判（v3.2.23），不依赖这里的登记时机。
 *
 * 生命周期：gamepadEnabled=false 时整体退场（含球）——「关闭手柄」即
 * 完全退出，设置页/通知快捷开关可重新开启。
 */
@Composable
fun GamepadFloatingBall(onPersistConfig: (() -> Unit)? = null) {
    // v5.0（用户："悬浮球不要更改大小，现在就行"）：悬浮球以系统基础密度
    // 渲染 —— 全局 UI 缩放（150% 档）只作用于管理界面，球体 54dp 恒定。
    KeepBaseDensity {
        GamepadFloatingBallContent(onPersistConfig)
    }
}

@Composable
private fun GamepadFloatingBallContent(onPersistConfig: (() -> Unit)?) {
    val app = LinBoxApp.get()
    val scope = rememberCoroutineScope()
    // v3.4.7：调用方（LinBoxShell）不传回调时，缺省走标准持久化
    // （与 GamepadOverlay.persistConfig 同源：写 gamepad_config v3）。
    val persistConfig: () -> Unit = onPersistConfig ?: {
        scope.launch { app.settingsStore.setGamepadConfig(GamepadController.toJson()) }
    }
    val profileIo = rememberGamepadProfileIo(persistConfig)

    // v5.0：手柄总开关（球菜单“开启手柄”需感知当前态）
    val gamepadEnabled by app.settingsStore.gamepadEnabled.collectAsState(initial = false)
    // v5.0：X11 组仅在 X11 屏显示（原 X11GlassFab 功能全部并入本球）
    val inX11 = ShellController.screen == ShellController.Screen.X11
    // v6.2：终端组仅在终端屏显示 —— 原终端独立玻璃悬浮球（GlassFab）
    // 的全部功能并入本球（用户：“终端界面的悬浮球没有集中到虚拟手柄
    // 悬浮球里，要和 x11 那样多排集合进去”）
    val inTerminal = ShellController.screen == ShellController.Screen.TERMINAL

    var expanded by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // v5.0：触摸方式快捷循环（模拟→触控板→直触，同原 X11 悬浮球）
    var touchModeState by remember {
        mutableStateOf(
            com.termux.x11.LoriePreferences.prefs?.touchMode?.get()?.toIntOrNull() ?: 2
        )
    }
    // v5.0：键盘切换需要 Context（组合层取，回调内使用）
    val context = androidx.compose.ui.platform.LocalContext.current

    // 闭包内保持最新引用（拖动结束时读取基准位置）
    val baseX by rememberUpdatedState(GamepadController.ballPosX)
    val baseY by rememberUpdatedState(GamepadController.ballPosY)

    // v6.2（用户：“悬浮球以及调出的菜单也太大了，全都降低50%”）：
    // 球体 54→28dp；菜单宽 232→190dp + 全部改紧凑多排网格芯片
    val ballSize = 28.dp
    val menuWidth = 190.dp

    // 按压反馈
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = tween(120), label = "gpBallPress"
    )
    // 展开/收起时球心的强调色
    val coreColor by animateColorAsState(
        targetValue = if (expanded) Color(0xFFFFF7AD) else Color(0xB3FFFFFF),
        animationSpec = tween(180), label = "gpBallCore"
    )

    // 退场清理：注销球/菜单命中与直通区（防陈旧矩形吞掉后续正常触摸）
    DisposableEffect(Unit) {
        onDispose {
            TrackpadRouter.registerPassthrough("gpBall", null)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val freeWpx = with(density) { (maxWidth - ballSize).toPx() }
        val freeHpx = with(density) { (maxHeight - ballSize).toPx() }
        val menuWpx = with(density) { menuWidth.toPx() }
        val ballPx = with(density) { ballSize.toPx() }

        // 球左上角（px）：基准（归一化）+ 拖动瞬态，钳制在屏内
        fun clampedPos(): Offset {
            val x = freeWpx * baseX + dragOffset.x
            val y = freeHpx * baseY + dragOffset.y
            return Offset(x.coerceIn(0f, freeWpx), y.coerceIn(0f, freeHpx))
        }

        val pos = clampedPos()

        // ==============================================================
        // v3.4.5（用户：菜单位置不对 + 撞进右侧按键弧区）：菜单定位重写。
        // 旧版：菜单中心对球心，仅 coerce 屏内 —— 球在右上角时菜单贴死
        // 屏右缘（圆角被截断，截图实证），且向下展开正撞进 M/3/U 按键
        // 弧区（截图实证 y:145-435 压满按键列）。
        // 新版三步：
        //  1) 水平【向内展开】：球在右半屏 → 菜单右缘对球右缘（向左），
        //     左半屏 → 菜单左缘对球左缘（向右），永不贴边截断；
        //  2) 垂直两候选（球下 / 球上）：矩形级碰撞检测 —— 候选菜单矩形
        //     四角+中心共 5 点任一落在手柄元素命中区内则判碰撞（点级采样
        //     对圆钮足够）；默认侧碰撞 → 翻另一侧，另一侧也碰撞 → 保持
        //     默认（用户拖球后可让位）；
        //  3) 碰撞检测仅在菜单展开时计算一次（expanded 状态翻转处）。
        // ==============================================================
        fun menuOverlapsElements(left: Float, top: Float, w: Float, h: Float): Boolean {
            if (!GamepadController.hasElementHits()) return false
            val right = left + w
            val bottom = top + h
            val pts = arrayOf(
                left to top, right to top, left to bottom, right to bottom,
                (left + right) / 2f to (top + bottom) / 2f
            )
            return pts.any { (x, y) -> GamepadController.isOverPadElement(x, y) }
        }

        val menuHpx = menuHeightEstimate(density, inX11 || inTerminal)
        val menuPad = with(density) { 10.dp.toPx() }
        val screenWpxTotal = with(density) { maxWidth.toPx() }
        val screenHpxTotal = with(density) { maxHeight.toPx() }

        // 1) 水平向内展开（左/右半屏）
        val menuLeft = if (pos.x + ballPx / 2f < screenWpxTotal / 2f) {
            // 球在左半屏：菜单左缘对球左缘（向右展开），右缘不越屏
            pos.x.coerceIn(0f, (screenWpxTotal - menuWpx).coerceAtLeast(0f))
        } else {
            // 球在右半屏：菜单右缘对球右缘（向左展开），左缘不越屏
            (pos.x + ballPx - menuWpx).coerceIn(0f, (screenWpxTotal - menuWpx).coerceAtLeast(0f))
        }

        // 2) 垂直避让：默认上半屏向下弹 / 下半屏向上弹，碰撞则翻侧
        val ballCenterY = pos.y + ballPx / 2f
        val belowTop = pos.y + ballPx + menuPad
        val aboveTop = pos.y - menuPad - menuHpx
        val preferBelow = ballCenterY < screenHpxTotal / 2f
        val menuTop = when {
            preferBelow && menuOverlapsElements(menuLeft, belowTop, menuWpx, menuHpx) &&
                !menuOverlapsElements(menuLeft, aboveTop, menuWpx, menuHpx) && aboveTop >= 0f -> aboveTop
            !preferBelow && menuOverlapsElements(menuLeft, aboveTop, menuWpx, menuHpx) &&
                !menuOverlapsElements(menuLeft, belowTop, menuWpx, menuHpx) &&
                belowTop + menuHpx <= screenHpxTotal -> belowTop
            else -> if (preferBelow) belowTop else aboveTop
        }

        // ===== v3.2.24（用户问题 2）：点菜单外任意处收起的透明捕获层 =====
        // 【z 序修复】必须声明在菜单与悬浮球【之前】：Compose 的 Box 子
        // 件按声明顺序绘制 z 序（后声明者在上）。v3.2.23 把它放在最后 →
        // 全屏捕获层盖在菜单上方，四个菜单项的点击全部被它拦截 →
        // “悬浮球四个按键没有一个能用”（点击只触发收起，onClick 永不执行）。
        // 现在顺序：捕获层（最底）→ 菜单（中）→ 悬浮球（最顶，展开时
        // 仍可拖动/点击）。
        if (expanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { expanded = false }
                    }
            )
        }

        // ===== 展开的控制菜单（捕获层之上，点击直达菜单项） =====
        // v6.0：展开/收起加缩放+淡入淡出弹性动画（用户："调出菜单没有任何
        // 动态感"）；菜单高度钳制在屏内（可滚动）—— 横屏再矮也完整可见
        // 可交互（用户："横屏直接看不全也无法交互"）。
        val menuMaxH = with(density) { (maxHeight - 16.dp).toPx() }
        val menuTopClamped = menuTop.coerceIn(
            4f,
            (screenHpxTotal - menuHpx.coerceAtMost(menuMaxH) - 4f).coerceAtLeast(4f)
        )
        // v6.0：菜单收起即清命中矩形（防收起后原地 X11 触摸被陈旧矩形剥离）
        LaunchedEffect(expanded) {
            if (!expanded) GamepadController.clearMenuHit()
        }
        // 菜单高度上限（Dp）——提前取出：AnimatedVisibility 内容 lambda 的
        // 隐式接收者链里 maxHeight 无法隐式解析，必须在此层取值
        val menuMaxHeightDp = maxHeight - 16.dp
        Box(
            modifier = Modifier
                .offset { IntOffset(menuLeft.roundToInt(), menuTopClamped.roundToInt()) }
                .width(menuWidth)
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(140)) + scaleIn(
                    initialScale = 0.82f,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.75f, 0.1f),
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f)
                ),
                exit = fadeOut(tween(110)) + scaleOut(
                    targetScale = 0.9f,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.75f, 0.1f),
                    animationSpec = tween(110, easing = FastOutSlowInEasing)
                )
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = menuMaxHeightDp)
                        .shadow(6.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xF2162434))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                        // 菜单登记：X11 触摸剥离 + 触控板直通（见类 KDoc）
                        .onGloballyPositioned {
                            val b = it.boundsInWindowCompat()
                            GamepadController.registerMenuHit(b.left, b.top, b.right, b.bottom)
                            TrackpadRouter.registerPassthrough("gpBall", b)
                        }
                ) {
                // ---- 标题头：名称 + 组件数（v6.2 紧凑化） ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "虚拟手柄",
                        color = Color(0xFFEAF6FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${GamepadController.elements.size} 组件",
                        color = Color(0x99B8C7D9),
                        fontSize = 9.5.sp
                    )
                }
                BallMenuSectionDivider()

                // ---- v6.2：主功能组改紧凑三列网格芯片（用户："虚拟手柄
                //      的菜单也和 x11 那样"——与 X11 快捷组同款多排集合） ----
                BallChipGrid(
                    listOf(
                        BallChipItem(BallIconKind.GAMEPAD, when {
                            !gamepadEnabled -> "开启手柄"
                            GamepadController.visible -> "收起手柄"
                            else -> "显示手柄"
                        }) {
                            if (!gamepadEnabled) {
                                // v6.0：visible 与总开关同步落盘 —— 全局加载只发生一次，
                                // 此处不持久化的话下次启动仍是旧 padVisible
                                GamepadController.visible = true
                                persistConfig()
                                scope.launch { app.settingsStore.setGamepadEnabled(true) }
                            } else {
                                GamepadController.releaseAllKeys()
                                GamepadController.visible = !GamepadController.visible
                                persistConfig()
                            }
                            expanded = false
                        },
                        BallChipItem(BallIconKind.SETTINGS, "手柄设置") {
                            GamepadController.releaseAllKeys()
                            GamepadController.settingsOpen = true
                            expanded = false
                        },
                        BallChipItem(BallIconKind.EDIT, "编辑布局") {
                            GamepadController.releaseAllKeys()
                            GamepadController.editMode = !GamepadController.editMode
                            if (!GamepadController.editMode) GamepadController.selectElement(null)
                            expanded = false
                        },
                        // v3.4.6：配置文件（主菜单位置 —— 先读取，再编辑）
                        BallChipItem(BallIconKind.SAVE, "保存配置") {
                            GamepadController.releaseAllKeys()
                            profileIo.saveProfile()
                            expanded = false
                        },
                        BallChipItem(BallIconKind.FOLDER, "读取配置") {
                            GamepadController.releaseAllKeys()
                            profileIo.loadProfile()
                            expanded = false
                        },
                        BallChipItem(BallIconKind.CLOSE, "关闭手柄", danger = true) {
                            GamepadController.releaseAllKeys()
                            GamepadController.editMode = false
                            expanded = false
                            scope.launch { app.settingsStore.setGamepadEnabled(false) }
                        }
                    )
                )

                // =====================================================
                // v6.2：终端快捷组（用户："终端界面的悬浮球没有集中到虚拟
                // 手柄悬浮球里，要和 x11 那样多排集合进去"）—— 原终端
                // 玻璃悬浮球（GlassFab）的全部功能并入本球，仅在终端屏
                // 显示；会话相关动作经 TerminalQuickBus 投递。
                // =====================================================
                if (inTerminal) {
                    BallMenuSectionDivider()
                    Text(
                        "终端快捷",
                        color = Color(0x80B8C7D9),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                    BallChipGrid(
                        listOf(
                            BallChipItem(BallIconKind.ADD, "新会话",
                                enabled = com.linbox.apps.terminal.TerminalQuickBus.newSession != null) {
                                com.linbox.apps.terminal.TerminalQuickBus.newSession?.invoke()
                                expanded = false
                            },
                            BallChipItem(BallIconKind.KEYBOARD, "键盘",
                                enabled = com.linbox.apps.terminal.TerminalQuickBus.toggleKeyboard != null) {
                                com.linbox.apps.terminal.TerminalQuickBus.toggleKeyboard?.invoke()
                                expanded = false
                            },
                            BallChipItem(BallIconKind.PASTE, "粘贴",
                                enabled = com.linbox.apps.terminal.TerminalQuickBus.paste != null) {
                                com.linbox.apps.terminal.TerminalQuickBus.paste?.invoke()
                                expanded = false
                            },
                            BallChipItem(BallIconKind.FONT_MINUS, "字号－",
                                enabled = com.linbox.apps.terminal.TerminalQuickBus.fontDecrease != null) {
                                com.linbox.apps.terminal.TerminalQuickBus.fontDecrease?.invoke()
                                expanded = false
                            },
                            BallChipItem(BallIconKind.FONT_PLUS, "字号＋",
                                enabled = com.linbox.apps.terminal.TerminalQuickBus.fontIncrease != null) {
                                com.linbox.apps.terminal.TerminalQuickBus.fontIncrease?.invoke()
                                expanded = false
                            },
                            BallChipItem(
                                BallIconKind.SYMBOL, "符号层",
                                active = com.linbox.apps.terminal.TerminalQuickBus.symbolLayerActive,
                                enabled = com.linbox.apps.terminal.TerminalQuickBus.toggleSymbolLayer != null
                            ) {
                                com.linbox.apps.terminal.TerminalQuickBus.toggleSymbolLayer?.invoke()
                                expanded = false
                            },
                            BallChipItem(BallIconKind.X11, "X11 桌面") {
                                ShellController.showX11()
                                expanded = false
                            },
                            BallChipItem(BallIconKind.DISPLAY, "DAC 显示") {
                                ShellController.showDac()
                                expanded = false
                            },
                            BallChipItem(BallIconKind.GEAR, "设置") {
                                ShellController.showSettings()
                                expanded = false
                            },
                            BallChipItem(BallIconKind.HOME, "回主页") {
                                ShellController.showHome()
                                expanded = false
                            }
                        )
                    )
                }

                // =====================================================
                // v5.0：X11 组（用户：“把 x11 设置的悬浮球功能放在虚拟手柄
                // 悬浮球里” —— 原 X11GlassFab 的全部功能并入本球，仅 X11
                // 屏显示；修复双悬浮球并存不统一的问题）。
                // =====================================================
                if (inX11) {
                    BallMenuSectionDivider()
                    Text(
                        "X11 快捷",
                        color = Color(0x80B8C7D9),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                    BallChipGrid(
                        listOf(
                            BallChipItem(BallIconKind.KEYBOARD, "键盘") {
                                val imm = context.getSystemService(
                                    android.content.Context.INPUT_METHOD_SERVICE
                                ) as android.view.inputmethod.InputMethodManager
                                @Suppress("DEPRECATION")
                                imm.toggleSoftInput(
                                    android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0
                                )
                                expanded = false
                            },
                            BallChipItem(
                                icon = BallIconKind.TOUCH,
                                label = when (touchModeState) { 1 -> "触控板"; 3 -> "直触"; else -> "模拟" }
                            ) {
                                val lp = com.termux.x11.LoriePreferences.prefs
                                if (lp != null) {
                                    val next = when (touchModeState) { 1 -> 3; 3 -> 2; else -> 1 }
                                    touchModeState = next
                                    lp.touchMode.put(next.toString())
                                    com.linbox.apps.x11.SmartTouchBridge.applyTouchPrefs()
                                }
                                expanded = false
                            },
                            BallChipItem(BallIconKind.CYCLE, "切窗") {
                                com.linbox.apps.x11.X11WindowOps.cycleTopWindow()
                                expanded = false
                            },
                            BallChipItem(
                                BallIconKind.FULLSCREEN,
                                if (com.linbox.apps.x11.X11UiPrefs.fullscreen) "退出全屏" else "全屏"
                            ) {
                                val v = !com.linbox.apps.x11.X11UiPrefs.fullscreen
                                com.linbox.apps.x11.X11UiPrefs.fullscreen = v
                                com.termux.x11.LoriePreferences.prefs?.fullscreen?.put(v)
                                expanded = false
                            },
                            BallChipItem(BallIconKind.FSGAME, "游戏全屏") {
                                com.termux.x11.X11InputHub.sendAltEnter()
                                expanded = false
                            },
                            BallChipItem(BallIconKind.GEAR, "X11 设置") {
                                com.linbox.apps.x11.X11SettingsBridge.requestShow()
                                expanded = false
                            },
                            BallChipItem(BallIconKind.HOME, "回主页") {
                                ShellController.showHome()
                                expanded = false
                            }
                        )
                    )
                }
                }
            }
        }

        // ===== 悬浮球本体（v6.2：28dp，矢量手柄图标替代 emoji） =====
        Box(
            modifier = Modifier
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(ballSize)
                .scale(pressScale)
                .shadow(4.dp, CircleShape)
                // 炫彩半透明：径向渐变球体 + 旋转扫光
                .clip(CircleShape)
                .drawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0x66FF5F9E), Color(0x667C5CFF),
                                Color(0x662FC8FF), Color(0x4D12B8A0)
                            ),
                            center = Offset(r * 0.7f, r * 0.35f),
                            radius = r * 1.35f
                        )
                    )
                    // 高光
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x8CFFFFFF), Color.Transparent),
                            center = Offset(r * 0.68f, r * 0.32f),
                            radius = r * 0.55f
                        )
                    )
                    // 球心指示（展开时点亮）
                    drawCircle(color = coreColor, radius = r * 0.16f)
                }
                // 命中登记（X11 触摸剥离 + 触控板直通）：菜单未展开时球本体
                // 占用 gpBall 直通槽位；展开后由菜单登记（面积大、覆盖球心）
                .onGloballyPositioned {
                    val b = it.boundsInWindowCompat()
                    GamepadController.registerToolbarHit(b.left, b.top, b.right, b.bottom)
                    if (!expanded) {
                        TrackpadRouter.registerPassthrough("gpBall", b)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            try { awaitRelease() } finally { pressed = false }
                        },
                        onTap = { expanded = !expanded }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { expanded = false },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount
                        },
                        onDragEnd = {
                            // 归一化新基准并持久化（钳制 0..1）
                            val nx = ((freeWpx * baseX + dragOffset.x) / freeWpx)
                                .coerceIn(0f, 1f)
                            val ny = ((freeHpx * baseY + dragOffset.y) / freeHpx)
                                .coerceIn(0f, 1f)
                            GamepadController.ballPosX = nx
                            GamepadController.ballPosY = ny
                            dragOffset = Offset.Zero
                            persistConfig()
                        },
                        onDragCancel = { dragOffset = Offset.Zero }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // v6.2：emoji 22sp → 矢量手柄图标 16dp（随球体缩小 50%，
            // 任何字体下渲染一致）
            BallMenuIcon(BallIconKind.GAMEPAD, Color(0xFFEAF6FF))
        }

        // ===== v3.4.6：.vgp 保存/读取结果气泡（挂在球旁，2.4s 自动消失；
        // 纯展示层 —— 无 pointerInput，不拦截底层触摸） =====
        val ioMsg = profileIo.message.value
        if (ioMsg != null) {
            val toastHalfW = with(density) { 105.dp.toPx() }
            val toastX = (pos.x + ballPx / 2f - toastHalfW)
                .coerceIn(0f, (screenWpxTotal - toastHalfW * 2f).coerceAtLeast(0f))
            val toastBelowY = pos.y + ballPx + 8f
            val toastY = if (toastBelowY + with(density) { 40.dp.toPx() } <= screenHpxTotal) {
                toastBelowY
            } else {
                (pos.y - 8f - with(density) { 40.dp.toPx() }).coerceAtLeast(0f)
            }
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopStart
            ) {
                GamepadIoToast(
                    ioMsg,
                    Modifier
                        .offset { IntOffset(toastX.roundToInt(), toastY.roundToInt()) }
                        .width(210.dp)
                )
            }
        }
    }
}

/** 菜单分组之间的细分隔线（v3.4.6）。 */
@Composable
private fun BallMenuSectionDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(Color(0x14FFFFFF))
    )
}

/**
 * v6.2：菜单网格芯片项（icon + label + 可选点亮/危险/可用态）。
 */
private class BallChipItem(
    val icon: BallIconKind,
    val label: String,
    val active: Boolean = false,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * v6.2：三列多排紧凑芯片网格（用户："要和 x11 那样多排集合进去"）。
 * 主功能组 / 终端快捷组 / X11 快捷组共用同一网格形态。
 */
@Composable
private fun BallChipGrid(items: List<BallChipItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                rowItems.forEach { item ->
                    X11QuickChip(
                        icon = item.icon,
                        label = item.label,
                        active = item.active,
                        danger = item.danger,
                        enabled = item.enabled,
                        modifier = Modifier.weight(1f)
                    ) { item.onClick() }
                }
                // 补齐占位（3 列对齐）
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * v6.2 紧凑网格芯片（原 X11 快捷芯片缩小 50%：图标圆底 32→22dp、
 * 内边距 8→5dp、字号 10.5→9sp）；新增点亮（active）/ 危险（danger）/ 禁用
 * （enabled，终端会话不在场时置灰）三态。
 */
@Composable
private fun X11QuickChip(
    icon: BallIconKind,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val tint = icon.tint(danger)
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = tween(110), label = "ballChip"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(11.dp))
            .background(
                when {
                    !enabled -> Color(0x0AFFFFFF)
                    pressed -> Color(0x1FFFFFFF)
                    active -> Color(0x2E2DD4BF)
                    else -> Color(0x14FFFFFF)
                }
            )
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try { awaitRelease() } finally { pressed = false }
                    },
                    onTap = { if (enabled) onClick() }
                )
            }
            .padding(vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !enabled -> Color(0x12FFFFFF)
                        else -> tint.copy(alpha = 0.16f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            BallMenuIcon(icon, if (enabled) tint else Color(0x66B8C7D9))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = when {
                !enabled -> Color(0x59B8C7D9)
                danger -> Color(0xFFFFB4B4)
                else -> Color(0xFFDCE8F5)
            },
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

/** 菜单图标类型（v3.4.6；v5.0 增 X11 组七枚；v6.2 增终端组七枚）。 */
private enum class BallIconKind {
    GAMEPAD, SETTINGS, EDIT, SAVE, FOLDER, CLOSE,
    KEYBOARD, TOUCH, FSGAME, CYCLE, FULLSCREEN, GEAR, HOME,
    ADD, PASTE, FONT_MINUS, FONT_PLUS, SYMBOL, X11, DISPLAY
}

/** 图标配色（危险项统一红系；v6.2 增终端组七枚）。 */
private fun BallIconKind.tint(danger: Boolean): Color = if (danger) Color(0xFFFF8A80) else when (this) {
    BallIconKind.GAMEPAD -> Color(0xFF2DD4BF)
    BallIconKind.SETTINGS -> Color(0xFF9AD8F5)
    BallIconKind.EDIT -> Color(0xFFFFD479)
    BallIconKind.SAVE -> Color(0xFF7FE8DC)
    BallIconKind.FOLDER -> Color(0xFF9AD8F5)
    BallIconKind.CLOSE -> Color(0xFFFF8A80)
    BallIconKind.KEYBOARD -> Color(0xFF8FD6FF)
    BallIconKind.TOUCH -> Color(0xFFFFB86C)
    BallIconKind.FSGAME -> Color(0xFF7CE87C)
    BallIconKind.CYCLE -> Color(0xFFC7A6FF)
    BallIconKind.FULLSCREEN -> Color(0xFF6BD4FF)
    BallIconKind.GEAR -> Color(0xFFB8C7D9)
    BallIconKind.HOME -> Color(0xFFF2A6C4)
    BallIconKind.ADD -> Color(0xFF7CE87C)
    BallIconKind.PASTE -> Color(0xFFFFD479)
    BallIconKind.FONT_MINUS -> Color(0xFF9AD8F5)
    BallIconKind.FONT_PLUS -> Color(0xFF9AD8F5)
    BallIconKind.SYMBOL -> Color(0xFFC7A6FF)
    BallIconKind.X11 -> Color(0xFF6BD4FF)
    BallIconKind.DISPLAY -> Color(0xFF7FE8DC)
}

/**
 * 线性图标（1.8 单位圆头笔画，Canvas 自绘 —— 零字体字形依赖；
 * v6.2 默认 16dp —— 随菜单/球体整体缩小 50%）。
 */
@Composable
private fun BallMenuIcon(kind: BallIconKind, tint: Color, iconSize: Dp = 16.dp) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val u = size.width / 22f
        val s = Stroke(width = 1.8f * u, cap = StrokeCap.Round)
        when (kind) {
            // 手柄：圆角机身 + 左右摇杆圆点 + 中央十字
            BallIconKind.GAMEPAD -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3.2f * u, 6.8f * u),
                    size = Size(15.6f * u, 8.6f * u),
                    cornerRadius = CornerRadius(4.2f * u, 4.2f * u),
                    style = s
                )
                drawCircle(color = tint, radius = 1.15f * u, center = Offset(7.6f * u, 11.1f * u))
                drawCircle(color = tint, radius = 1.15f * u, center = Offset(14.4f * u, 11.1f * u))
                drawLine(tint, Offset(11f * u, 9.6f * u), Offset(11f * u, 12.6f * u), s.width)
                drawLine(tint, Offset(9.5f * u, 11.1f * u), Offset(12.5f * u, 11.1f * u), s.width)
            }
            // 齿轮：外圈 + 中心孔 + 4 短齿辐
            BallIconKind.SETTINGS -> {
                drawCircle(color = tint, radius = 3.1f * u, center = Offset(11f * u, 11f * u), style = s)
                drawCircle(color = tint, radius = 1.0f * u, center = Offset(11f * u, 11f * u))
                val r1 = 4.6f * u
                val r2 = 6.4f * u
                for (a in 0..3) {
                    val ang = Math.toRadians((45 + 90 * a).toDouble())
                    val c = kotlin.math.cos(ang).toFloat()
                    val sn = kotlin.math.sin(ang).toFloat()
                    drawLine(
                        tint,
                        Offset(11f * u + r1 * c, 11f * u + r1 * sn),
                        Offset(11f * u + r2 * c, 11f * u + r2 * sn),
                        s.width
                    )
                }
            }
            // 铅笔：斜杆 + 笔尖两线
            BallIconKind.EDIT -> {
                drawLine(tint, Offset(6.4f * u, 15.6f * u), Offset(15.2f * u, 6.8f * u), s.width)
                drawLine(tint, Offset(5.6f * u, 12.4f * u), Offset(9.6f * u, 16.4f * u), s.width)
                drawLine(tint, Offset(5.6f * u, 12.4f * u), Offset(5.6f * u, 16.4f * u), s.width)
                drawLine(tint, Offset(5.6f * u, 16.4f * u), Offset(9.6f * u, 16.4f * u), s.width)
            }
            // 软盘：外框 + 顶标贴 + 底读写窗
            BallIconKind.SAVE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(4.2f * u, 4.2f * u),
                    size = Size(13.6f * u, 13.6f * u),
                    cornerRadius = CornerRadius(1.6f * u, 1.6f * u),
                    style = s
                )
                drawLine(tint, Offset(8.2f * u, 4.6f * u), Offset(8.2f * u, 8.6f * u), s.width)
                drawLine(tint, Offset(13.2f * u, 4.6f * u), Offset(13.2f * u, 8.6f * u), s.width)
                drawLine(tint, Offset(8.2f * u, 8.6f * u), Offset(13.2f * u, 8.6f * u), s.width)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(7.8f * u, 11.4f * u),
                    size = Size(6.4f * u, 6.0f * u),
                    cornerRadius = CornerRadius(1.0f * u, 1.0f * u),
                    style = s
                )
            }
            // 文件夹：经典折角轮廓
            BallIconKind.FOLDER -> {
                val p = Path().apply {
                    moveTo(3.4f * u, 6.4f * u)
                    lineTo(9.2f * u, 6.4f * u)
                    lineTo(11.2f * u, 8.6f * u)
                    lineTo(18.6f * u, 8.6f * u)
                    lineTo(18.6f * u, 16.0f * u)
                    lineTo(3.4f * u, 16.0f * u)
                    close()
                }
                drawPath(p, color = tint, style = s)
            }
            // 关闭：两斜线
            BallIconKind.CLOSE -> {
                drawLine(tint, Offset(7.2f * u, 7.2f * u), Offset(14.8f * u, 14.8f * u), s.width)
                drawLine(tint, Offset(14.8f * u, 7.2f * u), Offset(7.2f * u, 14.8f * u), s.width)
            }
            // v5.0 X11 组：键盘（外框 + 三排键点）
            BallIconKind.KEYBOARD -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3.0f * u, 6.2f * u),
                    size = Size(16.0f * u, 9.6f * u),
                    cornerRadius = CornerRadius(1.4f * u, 1.4f * u),
                    style = s
                )
                for (row in 0..2) {
                    for (col in 0..4) {
                        drawCircle(
                            color = tint,
                            radius = 0.55f * u,
                            center = Offset((5.2f + col * 2.9f) * u, (8.6f + row * 2.4f) * u)
                        )
                    }
                }
            }
            // 触摸方式：手指点击波纹（指尖 + 两圈波）
            BallIconKind.TOUCH -> {
                drawCircle(color = tint, radius = 1.0f * u, center = Offset(11f * u, 7.4f * u))
                drawCircle(color = tint, radius = 3.2f * u, center = Offset(11f * u, 7.4f * u), style = s)
                drawCircle(color = tint, radius = 5.6f * u, center = Offset(11f * u, 7.4f * u), style = s)
                drawLine(tint, Offset(11f * u, 8.4f * u), Offset(11f * u, 15.8f * u), s.width)
            }
            // 游戏全屏：四角括号 + 中心箭头
            BallIconKind.FSGAME -> {
                drawLine(tint, Offset(4.0f * u, 7.0f * u), Offset(4.0f * u, 4.4f * u), s.width)
                drawLine(tint, Offset(4.0f * u, 4.4f * u), Offset(7.4f * u, 4.4f * u), s.width)
                drawLine(tint, Offset(18.0f * u, 15.0f * u), Offset(18.0f * u, 17.6f * u), s.width)
                drawLine(tint, Offset(18.0f * u, 17.6f * u), Offset(14.6f * u, 17.6f * u), s.width)
                drawLine(tint, Offset(7.2f * u, 14.8f * u), Offset(14.8f * u, 7.2f * u), s.width)
                drawLine(tint, Offset(14.8f * u, 7.2f * u), Offset(14.8f * u, 11.4f * u), s.width)
                drawLine(tint, Offset(14.8f * u, 7.2f * u), Offset(10.6f * u, 7.2f * u), s.width)
            }
            // 切窗：两层叠框
            BallIconKind.CYCLE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(4.0f * u, 6.4f * u),
                    size = Size(10.4f * u, 9.6f * u),
                    cornerRadius = CornerRadius(1.2f * u, 1.2f * u),
                    style = s
                )
                drawLine(tint, Offset(8.0f * u, 9.2f * u), Offset(8.0f * u, 7.4f * u), s.width)
                drawLine(tint, Offset(8.0f * u, 7.4f * u), Offset(10.6f * u, 7.4f * u), s.width)
                drawLine(tint, Offset(8.0f * u, 7.4f * u), Offset(9.4f * u, 6.0f * u), s.width)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(8.6f * u, 10.8f * u),
                    size = Size(9.4f * u, 8.8f * u),
                    cornerRadius = CornerRadius(1.2f * u, 1.2f * u),
                    style = s
                )
            }
            // 全屏：四角外扩箭头
            BallIconKind.FULLSCREEN -> {
                drawLine(tint, Offset(4.4f * u, 8.4f * u), Offset(4.4f * u, 4.4f * u), s.width)
                drawLine(tint, Offset(4.4f * u, 4.4f * u), Offset(8.4f * u, 4.4f * u), s.width)
                drawLine(tint, Offset(17.6f * u, 13.6f * u), Offset(17.6f * u, 17.6f * u), s.width)
                drawLine(tint, Offset(17.6f * u, 17.6f * u), Offset(13.6f * u, 17.6f * u), s.width)
                drawLine(tint, Offset(17.6f * u, 8.4f * u), Offset(17.6f * u, 4.4f * u), s.width)
                drawLine(tint, Offset(17.6f * u, 4.4f * u), Offset(13.6f * u, 4.4f * u), s.width)
                drawLine(tint, Offset(4.4f * u, 13.6f * u), Offset(4.4f * u, 17.6f * u), s.width)
                drawLine(tint, Offset(4.4f * u, 17.6f * u), Offset(8.4f * u, 17.6f * u), s.width)
            }
            // X11 设置：滑杆（两条横杆 + 圆钮）
            BallIconKind.GEAR -> {
                drawLine(tint, Offset(4.2f * u, 8.2f * u), Offset(17.8f * u, 8.2f * u), s.width)
                drawCircle(color = tint, radius = 1.8f * u, center = Offset(13.4f * u, 8.2f * u))
                drawLine(tint, Offset(4.2f * u, 14.2f * u), Offset(17.8f * u, 14.2f * u), s.width)
                drawCircle(color = tint, radius = 1.8f * u, center = Offset(8.6f * u, 14.2f * u))
            }
            // 回主页：屋顶 + 墙体
            BallIconKind.HOME -> {
                drawLine(tint, Offset(4.4f * u, 10.6f * u), Offset(11f * u, 5.4f * u), s.width)
                drawLine(tint, Offset(11f * u, 5.4f * u), Offset(17.6f * u, 10.6f * u), s.width)
                drawLine(tint, Offset(6.2f * u, 9.4f * u), Offset(6.2f * u, 16.4f * u), s.width)
                drawLine(tint, Offset(15.8f * u, 9.4f * u), Offset(15.8f * u, 16.4f * u), s.width)
                drawLine(tint, Offset(6.2f * u, 16.4f * u), Offset(15.8f * u, 16.4f * u), s.width)
                drawLine(tint, Offset(9.8f * u, 16.4f * u), Offset(9.8f * u, 12.4f * u), s.width)
                drawLine(tint, Offset(9.8f * u, 12.4f * u), Offset(12.2f * u, 12.4f * u), s.width)
                drawLine(tint, Offset(12.2f * u, 12.4f * u), Offset(12.2f * u, 16.4f * u), s.width)
            }
            // ---- v6.2 终端组七枚 ----
            // 新会话：圆 + 中央十字加号
            BallIconKind.ADD -> {
                drawCircle(color = tint, radius = 7.4f * u, center = Offset(11f * u, 11f * u), style = s)
                drawLine(tint, Offset(11f * u, 7.6f * u), Offset(11f * u, 14.4f * u), s.width)
                drawLine(tint, Offset(7.6f * u, 11f * u), Offset(14.4f * u, 11f * u), s.width)
            }
            // 粘贴：剪贴板（板体 + 顶部夹扣）
            BallIconKind.PASTE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(5.2f * u, 5.6f * u),
                    size = Size(11.6f * u, 12.4f * u),
                    cornerRadius = CornerRadius(1.4f * u, 1.4f * u),
                    style = s
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(8.4f * u, 3.8f * u),
                    size = Size(5.2f * u, 3.2f * u),
                    cornerRadius = CornerRadius(1.0f * u, 1.0f * u),
                    style = s
                )
                drawLine(tint, Offset(8.2f * u, 12.2f * u), Offset(13.8f * u, 12.2f * u), s.width)
                drawLine(tint, Offset(8.2f * u, 14.6f * u), Offset(13.8f * u, 14.6f * u), s.width)
            }
            // 字号－：字母 A + 底部减号
            BallIconKind.FONT_MINUS -> {
                drawLine(tint, Offset(6.0f * u, 13.2f * u), Offset(10.0f * u, 5.2f * u), s.width)
                drawLine(tint, Offset(10.0f * u, 5.2f * u), Offset(14.0f * u, 13.2f * u), s.width)
                drawLine(tint, Offset(7.6f * u, 10.2f * u), Offset(12.4f * u, 10.2f * u), s.width)
                drawLine(tint, Offset(5.6f * u, 16.8f * u), Offset(16.4f * u, 16.8f * u), s.width)
            }
            // 字号＋：字母 A + 底部加号
            BallIconKind.FONT_PLUS -> {
                drawLine(tint, Offset(5.4f * u, 11.6f * u), Offset(9.0f * u, 4.6f * u), s.width)
                drawLine(tint, Offset(9.0f * u, 4.6f * u), Offset(12.6f * u, 11.6f * u), s.width)
                drawLine(tint, Offset(6.8f * u, 9.0f * u), Offset(11.2f * u, 9.0f * u), s.width)
                drawLine(tint, Offset(13.4f * u, 14.6f * u), Offset(18.2f * u, 14.6f * u), s.width)
                drawLine(tint, Offset(15.8f * u, 13.0f * u), Offset(15.8f * u, 16.2f * u), s.width)
            }
            // 符号层：并排两符号（◆ + ＃）
            BallIconKind.SYMBOL -> {
                drawLine(tint, Offset(4.4f * u, 7.4f * u), Offset(10.4f * u, 7.4f * u), s.width)
                drawLine(tint, Offset(5.6f * u, 4.4f * u), Offset(5.6f * u, 10.4f * u), s.width)
                drawLine(tint, Offset(9.2f * u, 4.4f * u), Offset(9.2f * u, 10.4f * u), s.width)
                drawLine(tint, Offset(13.2f * u, 13.4f * u), Offset(18.0f * u, 13.4f * u), s.width)
                drawLine(tint, Offset(13.2f * u, 16.6f * u), Offset(18.0f * u, 16.6f * u), s.width)
                drawLine(tint, Offset(14.2f * u, 11.4f * u), Offset(14.2f * u, 18.6f * u), s.width)
                drawLine(tint, Offset(17.0f * u, 11.4f * u), Offset(17.0f * u, 18.6f * u), s.width)
            }
            // X11 桌面：斜杠 + 字符 X 形（终端窗口风）
            BallIconKind.X11 -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3.6f * u, 4.6f * u),
                    size = Size(14.8f * u, 12.8f * u),
                    cornerRadius = CornerRadius(1.6f * u, 1.6f * u),
                    style = s
                )
                drawLine(tint, Offset(3.6f * u, 8.0f * u), Offset(18.4f * u, 8.0f * u), s.width)
                drawLine(tint, Offset(8.0f * u, 10.4f * u), Offset(14.0f * u, 16.0f * u), s.width)
                drawLine(tint, Offset(14.0f * u, 10.4f * u), Offset(8.0f * u, 16.0f * u), s.width)
            }
            // DAC 显示：显示器（屏幕 + 底座）
            BallIconKind.DISPLAY -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3.4f * u, 4.6f * u),
                    size = Size(15.2f * u, 10.4f * u),
                    cornerRadius = CornerRadius(1.6f * u, 1.6f * u),
                    style = s
                )
                drawLine(tint, Offset(8.4f * u, 15.0f * u), Offset(13.6f * u, 15.0f * u), s.width)
                drawLine(tint, Offset(11f * u, 15.0f * u), Offset(11f * u, 17.4f * u), s.width)
                drawLine(tint, Offset(7.2f * u, 17.4f * u), Offset(14.8f * u, 17.4f * u), s.width)
            }
        }
    }
}

/**
 * 菜单高度估算（v6.2 紧凑化后）：
 * 基础组 = 标题头 24 + 主功能网格 2 行×44 + 分隔 ≈ 150dp；
 * X11 组 = 标签 + 3 行×44 + 分隔 ≈ +160dp；
 * 终端组 = 标签 + 4 行×44 + 分隔 ≈ +205dp（三组不同屏，互斥）。
 * 仅用于"菜单向上/向下弹"的启发式选择与碰撞检测 —— 菜单本体
 * heightIn(max=屏高-16) 可滚动，横屏矮屏下也完整可见（v6.0 修复）。
 */
private fun menuHeightEstimate(
    density: androidx.compose.ui.unit.Density,
    withExtraGroup: Boolean = false
): Float = with(density) {
    (if (withExtraGroup) 370.dp else 150.dp).toPx()
}
