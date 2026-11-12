package com.linbox.core.input.gamepad

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.input.boundsInWindowCompat
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
 * 命中登记（缺一不可，否则球/菜单的触摸会被各输入层误吞或误穿透）：
 * - `GamepadController.registerToolbarHit`（球本体）：X11 智能触摸桥
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
fun GamepadFloatingBall(onPersistConfig: () -> Unit) {
    val app = LinBoxApp.get()
    val scope = rememberCoroutineScope()
    val profileIo = rememberGamepadProfileIo(onPersistConfig)

    var expanded by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // 闭包内保持最新引用（拖动结束时读取基准位置）
    val baseX by rememberUpdatedState(GamepadController.ballPosX)
    val baseY by rememberUpdatedState(GamepadController.ballPosY)

    val ballSize = 54.dp
    val menuWidth = 232.dp

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

        val menuHpx = menuHeightEstimate(density)
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

        // ===== 展开的控制菜单（在捕获层之上，点击直达菜单项） =====
        if (expanded) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(menuLeft.roundToInt(), menuTop.roundToInt()) }
                    .width(menuWidth)
                    .shadow(10.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF2162434))
                    .padding(vertical = 6.dp)
                    // 菜单登记：X11 触摸剥离 + 触控板直通（见类 KDoc）
                    .onGloballyPositioned {
                        val b = it.boundsInWindowCompat()
                        GamepadController.registerMenuHit(b.left, b.top, b.right, b.bottom)
                        TrackpadRouter.registerPassthrough("gpBall", b)
                    }
            ) {
                // ---- 标题头（v3.4.6）：名称 + 组件数，一眼可辨 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "虚拟手柄",
                        color = Color(0xFFEAF6FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${GamepadController.elements.size} 组件",
                        color = Color(0x99B8C7D9),
                        fontSize = 11.sp
                    )
                }
                BallMenuSectionDivider()

                BallMenuItem(BallIconKind.GAMEPAD, if (GamepadController.visible) "收起手柄" else "显示手柄") {
                    GamepadController.releaseAllKeys()
                    GamepadController.visible = !GamepadController.visible
                    onPersistConfig()
                    expanded = false
                }
                BallMenuItem(BallIconKind.SETTINGS, "手柄设置") {
                    GamepadController.releaseAllKeys()
                    GamepadController.settingsOpen = true
                    expanded = false
                }
                BallMenuItem(BallIconKind.EDIT, "编辑布局") {
                    GamepadController.releaseAllKeys()
                    GamepadController.editMode = !GamepadController.editMode
                    if (!GamepadController.editMode) GamepadController.selectElement(null)
                    expanded = false
                }
                BallMenuSectionDivider()

                // ---- v3.4.6：配置文件（主菜单位置 —— 先读取，再编辑） ----
                BallMenuItem(BallIconKind.SAVE, "保存配置", hint = ".vgp") {
                    GamepadController.releaseAllKeys()
                    profileIo.saveProfile()
                    expanded = false
                }
                BallMenuItem(BallIconKind.FOLDER, "读取配置", hint = ".vgp") {
                    GamepadController.releaseAllKeys()
                    profileIo.loadProfile()
                    expanded = false
                }
                BallMenuSectionDivider()

                BallMenuItem(BallIconKind.CLOSE, "关闭手柄", danger = true) {
                    GamepadController.releaseAllKeys()
                    GamepadController.editMode = false
                    expanded = false
                    scope.launch { app.settingsStore.setGamepadEnabled(false) }
                }
            }
        }

        // ===== 悬浮球本体 =====
        Box(
            modifier = Modifier
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(ballSize)
                .scale(pressScale)
                .shadow(8.dp, CircleShape)
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
                            onPersistConfig()
                        },
                        onDragCancel = { dragOffset = Offset.Zero }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "🎮",
                fontSize = 22.sp,
                color = Color.White
            )
        }

        // ===== v3.4.6：.vgp 保存/读取结果气泡（挂在球旁，2.4s 自动消失；
        // 纯展示层 —— 无 pointerInput，不拦截底层触摸） =====
        val ioMsg = profileIo.message.value
        if (ioMsg != null) {
            val toastHalfW = with(density) { 130.dp.toPx() }
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
                        .width(260.dp)
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
 * 菜单项行（v3.4.6 重制）：≥50dp 触摸目标 + 彩色圆底矢量图标 + 标题 +
 * 可选 .vgp 后缀提示 + 按压反馈。图标全部 Canvas 自绘（[BallMenuIcon]）
 * —— 不用 emoji / 文本字符，任何字体下渲染一致。
 */
@Composable
private fun BallMenuItem(
    icon: BallIconKind,
    label: String,
    hint: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val tint = icon.tint(danger)
    val badgeBg by animateColorAsState(
        targetValue = if (pressed) tint.copy(alpha = 0.38f) else tint.copy(alpha = 0.16f),
        animationSpec = tween(120), label = "gpBadge"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    pressed -> Color(0x1FFFFFFF)
                    danger -> Color(0x14FF6B6B)
                    else -> Color.Transparent
                }
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try { awaitRelease() } finally { pressed = false }
                    },
                    onTap = { onClick() }
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        // 触摸目标保障：图标圆底 36dp + 行内边距 → 行高 ≥52dp
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(badgeBg),
            contentAlignment = Alignment.Center
        ) {
            BallMenuIcon(icon, tint)
        }
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (danger) Color(0xFFFFB4B4) else Color(0xFFF2F6FF)
        )
        if (hint != null) {
            Spacer(Modifier.weight(1f))
            Text(
                hint,
                fontSize = 11.sp,
                color = Color(0x80B8C7D9)
            )
        }
    }
}

/** 菜单图标类型（v3.4.6）。 */
private enum class BallIconKind { GAMEPAD, SETTINGS, EDIT, SAVE, FOLDER, CLOSE }

/** 图标配色（危险项统一红系）。 */
private fun BallIconKind.tint(danger: Boolean): Color = if (danger) Color(0xFFFF8A80) else when (this) {
    BallIconKind.GAMEPAD -> Color(0xFF2DD4BF)
    BallIconKind.SETTINGS -> Color(0xFF9AD8F5)
    BallIconKind.EDIT -> Color(0xFFFFD479)
    BallIconKind.SAVE -> Color(0xFF7FE8DC)
    BallIconKind.FOLDER -> Color(0xFF9AD8F5)
    BallIconKind.CLOSE -> Color(0xFFFF8A80)
}

/**
 * 22dp 线性图标（1.8dp 圆头笔画，Canvas 自绘 —— 零字体字形依赖）：
 * 手柄 / 齿轮 / 铅笔 / 软盘 / 文件夹 / 关闭叉。
 */
@Composable
private fun BallMenuIcon(kind: BallIconKind, tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
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
        }
    }
}

/**
 * 菜单高度估算（v3.4.6：标题头 + 6 项 + 3 分隔线 + 边距 ≈ 360dp），
 * 用于下半屏时菜单翻到球上方 / 与手柄元素的碰撞检测。
 */
private fun menuHeightEstimate(density: androidx.compose.ui.unit.Density): Float =
    with(density) { 360.dp.toPx() }
