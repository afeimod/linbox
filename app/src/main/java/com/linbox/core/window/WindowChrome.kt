package com.linbox.core.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.linbox.core.theme.LocalWinTheme
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 调整大小时的窗口几何信息（全部为像素，与手势事件单位一致）。
 */
private data class ResizeFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/**
 * 单个窗口的 chrome（标题栏 + 内容区）。
 *
 * v2.11 拖拽/缩放流畅性重构：
 * - 旧版 onResize 直接修改 WindowState 但不触发刷新：缩放过程中画面完全不动，
 *   松手时窗口"跳变"到目标尺寸；且 dx(px) 与 state.width(dp) 单位混用，
 *   在高密度屏幕上缩放速度异常（约 2.75 倍）。
 * - 新版：缩放期间几何信息保存在本地 Compose 状态（ResizeFrame），
 *   通过 .offset{} / .layout{} 在布局阶段读取（deferred read），
 *   每帧只触发本窗口的重测量/重放置，不触发内容重组，实时跟手；
 *   松手时一次性提交（px → dp 正确换算）给 WindowManager。
 * - 拖动位置同样在 offset{} 内实时钳制在工作区内，消除旧版"松手后跳回"。
 *
 * 视觉：
 * - 圆角阴影（Win11 风格 drop shadow + rounded corners）
 * - 标题栏支持拖拽移动、双击最大化
 * - 8 方向调整大小：4 个边缘 + 4 个角落（触控热区 10dp / 18dp）
 *
 * @param workAreaWidth  工作区最大宽（像素）—— 由 WindowHost 通过 BoxWithConstraints 提供
 * @param workAreaHeight 工作区最大高
 */
@Composable
fun WindowChrome(
    state: WindowState,
    workAreaWidth: Int,
    workAreaHeight: Int,
    content: @Composable BoxScope.() -> Unit
) {
    val theme = LocalWinTheme.current
    val wm = remember { WindowManager.get() }
    // v2.22.4 fix11c：标题栏长按 → 应用自定义入口（X11 设置面板）
    val chromeContext = LocalContext.current
    val appDef = remember(state.appId) { AppRegistry.get(state.appId) }

    // 根据是否最大化决定尺寸/位置（FULLSCREEN 模式也占满工作区；真全屏占满整屏）
    val isMaximized = state.isMaximized || state.launchMode == LaunchMode.FULLSCREEN
    val isTrueFs = state.isTrueFullscreen

    // v2.9 单位统一 + 窗口尺寸钳制（修复竖屏"图标显示不全"）：
    // WindowChrome 始终用 dp 渲染，state.width/height 存 dp 数值。
    val density = LocalDensity.current
    val workAreaWidthDp = with(density) { workAreaWidth.toDp() }
    val workAreaHeightDp = with(density) { workAreaHeight.toDp() }
    val floatingW = state.width.dp.coerceAtMost(workAreaWidthDp)
    val floatingH = state.height.dp.coerceAtMost(workAreaHeightDp)

    val finalW = if (isMaximized || isTrueFs) workAreaWidthDp else floatingW
    val finalH = if (isMaximized || isTrueFs) workAreaHeightDp else floatingH

    // 位置也钳制到工作区内（px 空间，与拖拽偏移单位一致），保证窗口不被拖出屏幕
    val finalWpx = with(density) { finalW.roundToPx() }
    val finalHpx = with(density) { finalH.roundToPx() }
    val baseX = if (isMaximized || isTrueFs) 0
                else state.x.coerceIn(0, (workAreaWidth - finalWpx).coerceAtLeast(0))
    val baseY = if (isMaximized || isTrueFs) 0
                else state.y.coerceIn(0, (workAreaHeight - finalHpx).coerceAtLeast(0))

    // ===== 拖动（移动）：本地偏移，松手一次性提交 =====
    // dragOffset 只在 .offset{} 的 placement 阶段读取 —— 拖动期间零重组，只重放置，跟手流畅。
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // ===== 缩放：本地实时几何（px）=====
    // resizeBase：手势开始时的窗口帧（px）；resizeDelta：累计手势位移（px）。
    // liveFrame 由 derivedStateOf 从三者推导，仅在 .offset{} / .layout{} 中读取：
    // 缩放期间每帧只重测量/重放置本窗口（不重组内容），实时跟手。
    var activeResizeEdge by remember { mutableStateOf<ResizeEdge?>(null) }
    var resizeBase by remember { mutableStateOf<ResizeFrame?>(null) }
    var resizeDelta by remember { mutableStateOf(Offset.Zero) }

    // 最小尺寸（dp → px）：手机竖屏上 220x140dp 足够放标题栏和基本内容
    val minResizeWpx = with(density) { 220.dp.roundToPx() }
    val minResizeHpx = with(density) { 140.dp.roundToPx() }

    // 注意：remember 需以 workArea/min 尺寸为键 —— 旋转/多窗口/密度变化后
    // 工作区尺寸改变时重建 derivedStateOf，避免捕获过期常量。
    // （resizeBase/activeResizeEdge/resizeDelta 是稳定 MutableState 引用，不受影响）
    val liveFrame = remember(workAreaWidth, workAreaHeight, minResizeWpx, minResizeHpx) {
        derivedStateOf {
            val base = resizeBase ?: return@derivedStateOf null
            val edge = activeResizeEdge ?: return@derivedStateOf null
            computeResizedFrame(base, edge, resizeDelta, workAreaWidth, workAreaHeight, minResizeWpx, minResizeHpx)
        }
    }

    // 最大化状态下不显示阴影，因为窗口已经贴边
    val shadowElevation = if (isMaximized || isTrueFs) 0.dp else theme.windowShadowElevation

    // pointerInput(state.id) 协程只按窗口 id 重启：这里用 rememberUpdatedState
    // 保证拖拽结束提交时读到最新组合的工作区尺寸（旋转/多窗口/分屏后仍正确）
    val latestWorkArea by rememberUpdatedState(workAreaWidth to workAreaHeight)

    // v3.2.2：窗口几何上报 —— WindowFocusHook（View 层点击置前）按几何直判
    // 落点。拖动/缩放/最大化时随布局实时更新；窗口关闭/最小化离开组合时
    // 注销（DisposableEffect）。与桌面图标 iconBounds 同模式。
    DisposableEffect(state.id) {
        onDispose { WindowFocusHook.reportWindow(state.id, null) }
    }

    Box(
        modifier = Modifier
            // v3.2.2：任意点击置前已上移到 View 层（WindowFocusHook）——
            // v3.1.7 此处的 pointerInput+awaitFirstDown(Initial) 观察器在
            // 设备实测（用户视频 2026-10-30）从未兑现过置前，且保留它会
            // 与 View 层 hook 互相打架（触控板模式下手指轻点置前的窗口
            // 会被随后指针处注入点击命中的旧顶层观察器再顶回去）。View
            // 层 hook 在 Activity.dispatchTouchEvent / TrackpadController
            // 注入之前触发，语义完全覆盖并早于本层，且 Compose 层命中
            // 测试的任何不确定性都与它无关。窗口几何在本链尾部上报。
            // 位置：拖动 + 缩放都在 placement 阶段计算，实时钳制在工作区内
            .offset {
                val lf = liveFrame.value
                val curW = lf?.width ?: finalWpx
                val curH = lf?.height ?: finalHpx
                val curBaseX = lf?.x ?: baseX
                val curBaseY = lf?.y ?: baseY
                val maxX = (workAreaWidth - curW).coerceAtLeast(0)
                val maxY = (workAreaHeight - curH).coerceAtLeast(0)
                val x = (curBaseX + dragOffset.x.roundToInt()).coerceIn(0, maxX)
                val y = (curBaseY + dragOffset.y.roundToInt()).coerceIn(0, maxY)
                IntOffset(x, y)
            }
            // 尺寸：缩放期间在 measure 阶段读取 liveFrame，实时改变窗口大小（不触发重组）
            .layout { measurable, constraints ->
                val lf = liveFrame.value
                val w = (lf?.width ?: finalWpx).coerceIn(0, constraints.maxWidth)
                val h = (lf?.height ?: finalHpx).coerceIn(0, constraints.maxHeight)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .shadow(shadowElevation, theme.windowShape, clip = false)
            .clip(theme.windowShape)
            .background(theme.windowBackgroundColor)
            .border(if (isTrueFs) 0.dp else theme.windowBorderWidth, theme.windowBorderColor, theme.windowShape)
            // v3.2.2：链尾上报最终布局位置（含 offset/layout 修饰符效果）——
            // 窗口坐标 Rect，与 MainActivity.dispatchTouchEvent 的 MotionEvent
            // 同一坐标系。
            .onGloballyPositioned { coords ->
                WindowFocusHook.reportWindow(state.id, WindowFocusHook.boundsOf(coords))
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 标题栏（真全屏时隐藏） =====
            if (!isTrueFs) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(theme.windowTitleBarHeight)
                    // v3.3.40（用户需求 5）：Win7 Aero 玻璃渐变标题栏
                    //（截图同款：顶部高光 → 浅蓝玻璃 → 底部过渡；其余主题
                    // 保持原纯色标题栏）
                    .background(
                        if (theme.windowChrome == com.linbox.core.theme.WindowChrome.AERO && !theme.isDark)
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(
                                    Color(0xFFFCFDFF),
                                    theme.windowTitleBarColor,
                                    Color(0xFFD6E6F6).copy(alpha = 0.95f)
                                )
                            )
                        else androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(theme.windowTitleBarColor, theme.windowTitleBarColor)
                        )
                    )
                    .pointerInput(state.id) {
                        detectDragGestures(
                            onDragStart = {
                                wm.focus(state.id)
                                dragOffset = Offset.Zero
                            },
                            onDragEnd = {
                                // 拖拽结束：提交钳制后的最终位置。
                                // 关键：必须读取 state.x/state.y 的最新值（事件时刻），
                                // 且用 state.width 换算当前 px —— pointerInput(state.id) 的
                                // lambda 不会随后续重组更新，捕获组合期局部变量会拿到旧值。
                                if (dragOffset != Offset.Zero) {
                                    val (waW, waH) = latestWorkArea
                                    val wPx = with(density) { state.width.dp.roundToPx() }
                                    val hPx = with(density) { state.height.dp.roundToPx() }
                                    val maxX = (waW - wPx).coerceAtLeast(0)
                                    val maxY = (waH - hPx).coerceAtLeast(0)
                                    val nx = (state.x + dragOffset.x.roundToInt()).coerceIn(0, maxX)
                                    val ny = (state.y + dragOffset.y.roundToInt()).coerceIn(0, maxY)
                                    wm.setAbsolutePosition(state.id, nx, ny)
                                    wm.commitChanges()
                                }
                                dragOffset = Offset.Zero
                            },
                            onDragCancel = { dragOffset = Offset.Zero }
                        ) { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        }
                    }
                    .pointerInput(state.id) {
                        detectTapGestures(
                            onDoubleTap = {
                                // 仅 FLOATING 模式支持最大化
                                if (state.launchMode == LaunchMode.FLOATING) {
                                    wm.toggleMaximize(state.id)
                                }
                            }
                            // v2.22.5 fix12：长按不再走 detectTapGestures
                            // （系统默认 400ms 太灵敏，拖动窗口时易误触菜单）
                        )
                    }
                    // v2.22.5 fix12：标题栏长按 3 秒才触发应用入口
                    // （X11 桌面长按 = X11 设置面板）。
                    // 取消条件（满足任一即不弹菜单，保证不影响拖动窗口）：
                    //   3 秒内抬起 / 被拖动手势消费 / 移出触控 slop。
                    .pointerInput(state.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // withTimeoutOrNull 超时返回 null = 按住满 3 秒
                            // 未抬起/未拖动 → 长按成立；否则返回非 null。
                            val result = withTimeoutOrNull(3000L) {
                                var cancelled = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes
                                        .firstOrNull { it.id == down.id }
                                    if (change == null || change.changedToUp() ||
                                        change.isConsumed
                                    ) {
                                        cancelled = true
                                        break
                                    }
                                    val moved =
                                        (change.position - down.position).getDistance()
                                    if (moved > viewConfiguration.touchSlop) {
                                        cancelled = true
                                        break
                                    }
                                }
                                if (cancelled) "cancelled" else "waiting"
                            }
                            if (result == null) {
                                appDef?.onTitleBarLongPress?.invoke(chromeContext)
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧应用图标（v2.15：主题化真实应用图标，替换旧色块占位）
                Box(
                    modifier = Modifier
                        .size(theme.windowTitleBarHeight)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (appDef != null) {
                        com.linbox.core.desktop.IconPainter(appDef.iconAsset, size = 16.dp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(theme.windowTitleBarIconColor)
                        )
                    }
                }

                Text(
                    text = state.title,
                    color = theme.windowTitleBarTextColor,
                    fontSize = theme.fontSizeBody,
                    fontWeight = theme.fontWeightTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 标题栏右侧控制按钮组
                if (state.launchMode == LaunchMode.FLOATING) {
                    // v3.4.6（用户：“隐藏状态栏的按钮怎么跑到显示界面了，
                    // 应该在菜单按钮旁边才对”）：工具条开关按钮，紧邻菜单
                    // 按钮左侧。AppDef.onToolbarToggleButton 定义即显示
                    // （X11 桌面用它显示/隐藏底部控制条）。旧入口是 v3.4.5
                    // 画面顶部中央的悬浮玻璃片 —— 既遮挡游戏画面，⌃/⌄
                    // 字符又不在 Android 字体内（渲染成口口方块）。激活态
                    // 在组合期间读取（X11UiPrefs.toolbarHidden 可观察），
                    // 切换即时翻转载项箭头方向。
                    appDef?.onToolbarToggleButton?.let { toggleAction ->
                        val toolbarHidden = appDef.toolbarToggleActive()
                        ControlButton(
                            icon = if (toolbarHidden) Icons.Default.KeyboardArrowDown
                            else Icons.Default.KeyboardArrowUp,
                            aeroSym = if (toolbarHidden) CaptionSym.CHEVRON_DOWN
                            else CaptionSym.CHEVRON_UP,
                            theme = theme
                        ) { toggleAction(chromeContext) }
                    }
                    // v3.1.7：应用菜单按钮（最小化左边）—— AppDef.onMenuButton
                    // 定义即显示（X11 桌面用它调出 X11 设置菜单；此前只能
                    // 长按标题栏 3 秒，难发现且易误触）。
                    appDef?.onMenuButton?.let { menuAction ->
                        ControlButton(
                            icon = Icons.Default.Menu,
                            aeroSym = CaptionSym.MENU,
                            theme = theme
                        ) {
                            menuAction(chromeContext)
                        }
                    }
                    ControlButton(icon = Icons.Default.Minimize, aeroSym = CaptionSym.MIN, theme = theme) { wm.minimize(state.id) }
                    ControlButton(
                        icon = if (state.isMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        aeroSym = CaptionSym.MAX,
                        theme = theme
                    ) { wm.toggleMaximize(state.id) }
                }
                ControlButton(icon = Icons.Default.Close, aeroSym = CaptionSym.CLOSE, theme = theme, isClose = true) { wm.close(state.id) }
            }
            } // end if (!isTrueFs) — 真全屏时不渲染标题栏

            // ===== 内容区 =====
            Box(
                modifier = Modifier.fillMaxSize(),
                content = content
            )
        }

        // ===== 8 方向调整大小手柄（覆盖在窗口最上层，仅在 FLOATING 模式且未最大化且未真全屏时） =====
        if (state.launchMode == LaunchMode.FLOATING && !state.isMaximized && !isTrueFs) {
            ResizeHandles(
                state = state,
                onResizeStart = { edge ->
                    activeResizeEdge = edge
                    resizeDelta = Offset.Zero
                    // 捕获手势开始时的窗口帧（px）：
                    // state.x/y 本来就是 px；state.width/height 是 dp，换算成 px。
                    // state 引用与 density 引用都是稳定的，不存在过期捕获问题。
                    resizeBase = ResizeFrame(
                        x = state.x,
                        y = state.y,
                        width = with(density) { state.width.dp.roundToPx() },
                        height = with(density) { state.height.dp.roundToPx() }
                    )
                },
                onResize = { _, dx, dy ->
                    // 只累计位移：实时几何由 liveFrame（derivedStateOf）推导，
                    // 渲染端在布局阶段读取，避免每帧全局重组导致卡顿。
                    resizeDelta += Offset(dx, dy)
                },
                onResizeEnd = {
                    // 事件时刻直接从当前状态推导最终帧（不经过 liveFrame 对象引用，
                    // 避免任何潜在的过期闭包）
                    val base = resizeBase
                    val edge = activeResizeEdge
                    if (base != null && edge != null) {
                        val frame = computeResizedFrame(
                            base, edge, resizeDelta,
                            workAreaWidth, workAreaHeight, minResizeWpx, minResizeHpx
                        )
                        // 一次性提交：px → dp 正确换算（旧版 px 直接当 dp 用，
                        // 高密度屏上窗口会以数倍速度膨胀 —— "缩放不流畅"根因之一）
                        with(density) {
                            wm.setAbsoluteFrame(
                                state.id,
                                xPx = frame.x,
                                yPx = frame.y,
                                widthDp = frame.width.toDp().value.roundToInt(),
                                heightDp = frame.height.toDp().value.roundToInt()
                            )
                        }
                        wm.commitChanges()
                    }
                    activeResizeEdge = null
                    resizeBase = null
                    resizeDelta = Offset.Zero
                }
            )
        }
    }
}

/**
 * 根据手势累计位移计算缩放后的窗口帧（全部 px，含边界钳制）：
 * - 遵守最小尺寸（minW/minH）
 * - 左/上边不越出工作区左/上缘
 * - 右/下边不越出工作区右/下缘
 * - 若初始状态本身小于最小尺寸（异常持久化数据），自动纠正到最小尺寸
 */
private fun computeResizedFrame(
    base: ResizeFrame,
    edge: ResizeEdge,
    delta: Offset,
    workAreaWidth: Int,
    workAreaHeight: Int,
    minW: Int,
    minH: Int
): ResizeFrame {
    var x = base.x
    var y = base.y
    var w = base.width
    var h = base.height

    if (edge.affectsLeft) {
        // 左边右移（dx>0）收缩宽度、左移（dx<0）扩展宽度
        val lo = -x                              // 不能越过工作区左缘
        val hi = w - minW                        // 不能低于最小宽度
        val dx = if (lo > hi) hi else delta.x.roundToInt().coerceIn(lo, hi)
        x += dx
        w -= dx
    }
    if (edge.affectsRight) {
        val lo = minW - w                        // 保证最小宽度
        val hi = workAreaWidth - (x + w)         // 不能越过工作区右缘
        val dx = if (lo > hi) lo else delta.x.roundToInt().coerceIn(lo, hi)
        w += dx
    }
    if (edge.affectsTop) {
        val lo = -y                              // 不能越过工作区上缘
        val hi = h - minH                        // 不能低于最小高度
        val dy = if (lo > hi) hi else delta.y.roundToInt().coerceIn(lo, hi)
        y += dy
        h -= dy
    }
    if (edge.affectsBottom) {
        val lo = minH - h                        // 保证最小高度
        val hi = workAreaHeight - (y + h)        // 不能越过工作区下缘
        val dy = if (lo > hi) lo else delta.y.roundToInt().coerceIn(lo, hi)
        h += dy
    }
    return ResizeFrame(x, y, w, h)
}

/**
 * 8 方向调整大小手柄：4 个边缘 + 4 个角落
 * - 边缘：宽/高 10dp 的长条，悬停在窗口边缘（触控热区比鼠标时代更大）
 * - 角落：18x18dp 的小方块，悬停在窗口角落
 *
 * 内部用 rememberUpdatedState 包装回调：pointerInput(state.id) 的协程只在
 * 窗口 id 变化时重启，否则会捕获首次组合时的回调（过期闭包），
 * 旋转/多窗口尺寸变化后使用旧的工作区参数。
 */
@Composable
private fun BoxScope.ResizeHandles(
    state: WindowState,
    onResizeStart: (ResizeEdge) -> Unit,
    onResize: (ResizeEdge, Float, Float) -> Unit,
    onResizeEnd: () -> Unit
) {
    val handleThickness = 10.dp
    val cornerSize = 18.dp

    // 始终引用最新组合的回调，消除 pointerInput 过期闭包
    val currentOnResizeStart by rememberUpdatedState(onResizeStart)
    val currentOnResize by rememberUpdatedState(onResize)
    val currentOnResizeEnd by rememberUpdatedState(onResizeEnd)

    // 上边
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(handleThickness)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { currentOnResizeStart(ResizeEdge.TOP) },
                    onDragEnd = { currentOnResizeEnd() },
                    onDragCancel = { currentOnResizeEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnResize(ResizeEdge.TOP, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 下边
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(handleThickness)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { currentOnResizeStart(ResizeEdge.BOTTOM) },
                    onDragEnd = { currentOnResizeEnd() },
                    onDragCancel = { currentOnResizeEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnResize(ResizeEdge.BOTTOM, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 左边
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .width(handleThickness)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { currentOnResizeStart(ResizeEdge.LEFT) },
                    onDragEnd = { currentOnResizeEnd() },
                    onDragCancel = { currentOnResizeEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnResize(ResizeEdge.LEFT, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 右边
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(handleThickness)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { currentOnResizeStart(ResizeEdge.RIGHT) },
                    onDragEnd = { currentOnResizeEnd() },
                    onDragCancel = { currentOnResizeEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnResize(ResizeEdge.RIGHT, dragAmount.x, dragAmount.y)
                }
            }
    )

    // 4 个角落（更大、更易抓取）
    // 左上
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .size(cornerSize)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { currentOnResizeStart(ResizeEdge.TOP_LEFT) },
                    onDragEnd = { currentOnResizeEnd() },
                    onDragCancel = { currentOnResizeEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnResize(ResizeEdge.TOP_LEFT, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 右上
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .size(cornerSize)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { currentOnResizeStart(ResizeEdge.TOP_RIGHT) },
                    onDragEnd = { currentOnResizeEnd() },
                    onDragCancel = { currentOnResizeEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnResize(ResizeEdge.TOP_RIGHT, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 左下
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .size(cornerSize)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { currentOnResizeStart(ResizeEdge.BOTTOM_LEFT) },
                    onDragEnd = { currentOnResizeEnd() },
                    onDragCancel = { currentOnResizeEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnResize(ResizeEdge.BOTTOM_LEFT, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 右下
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(cornerSize)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { currentOnResizeStart(ResizeEdge.BOTTOM_RIGHT) },
                    onDragEnd = { currentOnResizeEnd() },
                    onDragCancel = { currentOnResizeEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnResize(ResizeEdge.BOTTOM_RIGHT, dragAmount.x, dragAmount.y)
                }
            }
    )
}


@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    theme: com.linbox.core.theme.WinTheme,
    isClose: Boolean = false,
    aeroSym: CaptionSym? = null,
    onClick: () -> Unit
) {
    // v3.4.5（Win7 1:1 重制，用户："win7 主题写的太烂"）：
    // Win7 Aero 真实形态 —— 细线符号 + 透明玻璃底，**默认态无任何颜色**
    //（旧版"红色关闭钮"是 macOS 皮，图一失真主因）；按下态才显色：
    // 关闭 = 红玻璃渐变，最小/最大 = 蓝玻璃渐变 + 描边。
    // 符号自绘（[Win7CaptionSymbol]）：– / 空心方框 / ✕ 全部 1.4dp 细线
    //（Material 粗图标 → 图一"方块粗箭头"失真根除）。宽 29dp 贴角组。
    // 非 Aero 主题维持旧形态（46dp + Material 图标）。
    //
    // v3.4.6（用户："win7 的窗口控制标志都变成口口四方块了"）：
    // Aero 符号不再靠 icon.name 字符串猜测 —— 旧逻辑非 "Minimize" 一律
    // 画空心方块 → 菜单按钮 ☰ 也被画成口。调用方显式传 [aeroSym]；
    // 未传时仅对历史调用点保留 name 推断兑底（Menu → ☰）。
    val aero = theme.windowChrome == com.linbox.core.theme.WindowChrome.AERO && !theme.isDark
    val sym = aeroSym ?: when {
        isClose -> CaptionSym.CLOSE
        icon.name == "Minimize" -> CaptionSym.MIN
        icon.name == "Menu" -> CaptionSym.MENU
        else -> CaptionSym.MAX
    }
    if (!aero) {
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = theme.windowTitleBarHeight)
                .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isClose) Color(0xFF9E2A21) else Color(0xFF16324E),
                modifier = Modifier.size(12.dp)
            )
        }
        return
    }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressedState by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(width = 29.dp, height = theme.windowTitleBarHeight)
            .background(
                when {
                    !pressedState -> androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0x14FFFFFF), Color(0x0A000000))
                    )
                    isClose -> androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFFF8BBA4), Color(0xFFDE6F5A))
                    )
                    else -> androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFFEAF6FD), Color(0xFFBFD9F2))
                    )
                }
            )
            .then(
                if (pressedState)
                    Modifier.border(
                        1.dp,
                        if (isClose) Color(0xFFB4472F) else Color(0xFF8FB3D9),
                        RectangleShape
                    )
                else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Win7CaptionSymbol(
            symbol = sym,
            color = if (pressedState && isClose) Color.White else Color(0xFF1F3F5F)
        )
    }
}

/** Aero 标题按钮细线符号类型（v3.4.5；v3.4.6 增 ☰ 菜单/载项箭头）。 */
private enum class CaptionSym { MIN, MAX, CLOSE, MENU, CHEVRON_UP, CHEVRON_DOWN }

/**
 * Win7 细线符号：–（下沿短横）/ 空心方框 / ✕ 两斜线 / ☰ 三横（菜单，
 * v3.4.6 —— 旧逻辑把它画成空心方块，即用户看到的"口"）/ 载项箭头
 * （工具条开关，替代字体外的 ⌃/⌄ 文本字符）。全部 ~1.4dp 细线，
 * Canvas 自绘 —— 不依赖任何字体字形，永不出现口口方块。
 */
@Composable
private fun Win7CaptionSymbol(symbol: CaptionSym, color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(width = 14.dp, height = 12.dp)) {
        val u = size.width / 14f
        when (symbol) {
            CaptionSym.MIN ->
                drawLine(
                    color, Offset(2.2f * u, 8.6f * u), Offset(11.8f * u, 8.6f * u),
                    strokeWidth = 1.6f * u
                )
            CaptionSym.MAX ->
                drawRect(
                    color,
                    topLeft = Offset(2.2f * u, 2.4f * u),
                    size = androidx.compose.ui.geometry.Size(9.6f * u, 7.6f * u),
                    style = Stroke(width = 1.3f * u)
                )
            CaptionSym.CLOSE -> {
                drawLine(
                    color, Offset(3.4f * u, 3.0f * u), Offset(10.6f * u, 9.2f * u),
                    strokeWidth = 1.4f * u
                )
                drawLine(
                    color, Offset(10.6f * u, 3.0f * u), Offset(3.4f * u, 9.2f * u),
                    strokeWidth = 1.4f * u
                )
            }
            // ☰ 菜单：三段等长细横线（Win7 细线风格的汉堡符号）
            CaptionSym.MENU -> {
                drawLine(
                    color, Offset(2.6f * u, 3.1f * u), Offset(11.4f * u, 3.1f * u),
                    strokeWidth = 1.4f * u
                )
                drawLine(
                    color, Offset(2.6f * u, 6.0f * u), Offset(11.4f * u, 6.0f * u),
                    strokeWidth = 1.4f * u
                )
                drawLine(
                    color, Offset(2.6f * u, 8.9f * u), Offset(11.4f * u, 8.9f * u),
                    strokeWidth = 1.4f * u
                )
            }
            // 载项箭头（工具条开关）：控制条显示中 → 朝上（点击收起）；
            // 已隐藏 → 朝下（点击展开）
            CaptionSym.CHEVRON_UP -> {
                drawLine(
                    color, Offset(3.6f * u, 7.8f * u), Offset(7.0f * u, 4.4f * u),
                    strokeWidth = 1.6f * u, cap = StrokeCap.Round
                )
                drawLine(
                    color, Offset(7.0f * u, 4.4f * u), Offset(10.4f * u, 7.8f * u),
                    strokeWidth = 1.6f * u, cap = StrokeCap.Round
                )
            }
            CaptionSym.CHEVRON_DOWN -> {
                drawLine(
                    color, Offset(3.6f * u, 4.2f * u), Offset(7.0f * u, 7.6f * u),
                    strokeWidth = 1.6f * u, cap = StrokeCap.Round
                )
                drawLine(
                    color, Offset(7.0f * u, 7.6f * u), Offset(10.4f * u, 4.2f * u),
                    strokeWidth = 1.6f * u, cap = StrokeCap.Round
                )
            }
        }
    }
}
