package com.linbox.apps.browser

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.linbox.LinBoxApp
import com.linbox.core.input.MouseController
import com.linbox.core.theme.LocalWinTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v2.19 浏览器悬浮滚动条（用户需求：鼠标移到浏览器左缘自动显示滑动条，
 * 支持点击跳转与拖动滚动）。
 *
 * ## 行为
 * - 悬停显示：虚拟指针进入内容区【左缘 24dp 条带】且页面可滚动时显示
 *   （触控板 / 触屏鼠标模式都生效；touch 纯触屏模式指针不动则不显示）；
 * - 轻点轨道：滑块中心跳到点击位置（注入点击 / 真实点击都触发）；
 * - 拖动滑块：连续滚动页面 —— touch 鼠标模式直接拖；触控板模式用
 *   "按住不动后拖动"注入的合成拖拽流（本层是标准 Compose 手势，
 *   合成流与真实流一视同仁）；
 * - 拖动期间强制显示，松手且指针离开条带后自动隐藏。
 *
 * ## 实现要点
 * - 滚动状态：View.OnScrollChangeListener + 250ms 兜底轮询（懒加载页面
 *   scrollRange 会变，不一定伴随 scroll 事件）；度量经 [ExposedWebView]
 *   公开（View 的 compute* 系列为 protected）；
 * - 命中范围：交互层只挂在左缘条带（24dp 宽），条带外零干扰 —— 网页
 *   点击/滚动行为与没有滚动条时完全一致；
 * - 滚动操作用 webView.scrollBy / scrollTo（WebView 内容滚动即 View 滚动）。
 */
@Composable
fun BrowserScrollbarOverlay(
    tab: BrowserTab,
    modifier: Modifier = Modifier
) {
    val app = remember { LinBoxApp.get() }
    val theme = LocalWinTheme.current
    val density = LocalDensity.current

    val cursorEnabled by app.settingsStore.mouseCursorEnabled.collectAsState(initial = true)
    val controlMode by app.settingsStore.mouseControlMode.collectAsState(initial = "touch")
    val cursorVisible = cursorEnabled || controlMode == "trackpad"

    val wv = tab.webView
    var scrollY by remember { mutableIntStateOf(0) }
    var scrollRange by remember { mutableIntStateOf(1) }
    var scrollExtent by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var selfHeightPx by remember { mutableIntStateOf(0) }

    // 滚动位置即时监听
    DisposableEffect(wv) {
        val listener = View.OnScrollChangeListener { _, _, y, _, _ -> scrollY = y }
        wv?.setOnScrollChangeListener(listener)
        onDispose { wv?.setOnScrollChangeListener(null) }
    }

    // 兜底轮询：懒加载页面 scrollRange/Extent 随内容变化，不一定伴随 scroll 事件
    LaunchedEffect(wv) {
        while (true) {
            val webView = wv
            if (webView is ExposedWebView) {
                scrollY = webView.scrollY
                scrollRange = runCatching { webView.verticalScrollRange() }.getOrDefault(scrollRange)
                scrollExtent = runCatching { webView.verticalScrollExtent() }.getOrDefault(scrollExtent)
            }
            delay(250)
        }
    }

    val scrollable = wv != null && scrollRange > scrollExtent + 8
    if (!cursorVisible || !scrollable || wv == null) return

    val stripPx = with(density) { 24.dp.toPx() }
    val cursorPos = MouseController.position
    val local = cursorPos - rootOffset
    val inStrip = local.x >= 0f && local.x <= stripPx &&
        local.y >= 0f && local.y <= selfHeightPx.toFloat()
    if (!inStrip && !dragging) return

    // 几何：滑块高度 = 视口占比（最小 44dp），位置 = scrollY / 可滚动量
    val denom = (scrollRange - scrollExtent).coerceAtLeast(1)
    val trackHPx = selfHeightPx.toFloat().coerceAtLeast(1f)
    val thumbHPx = (trackHPx * scrollExtent / scrollRange.coerceAtLeast(1))
        .coerceAtLeast(with(density) { 44.dp.toPx() })
        .coerceAtMost(trackHPx)
    val travel = (trackHPx - thumbHPx).coerceAtLeast(1f)
    val thumbY = (scrollY.toFloat() / denom * travel).coerceIn(0f, travel)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootOffset = it.positionInRoot()
                selfHeightPx = it.size.height
            }
    ) {
        // 轨道（纯视觉，不可命中 —— 命中层在下方条带）
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 3.dp)
                .width(8.dp)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
        )
        // 滑块（纯视觉）
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(with(density) { 3.dp.toPx() }.toInt(), thumbY.roundToInt()) }
                .size(width = 8.dp, height = with(density) { thumbHPx.toDp() })
                .background(
                    theme.accentColor.copy(alpha = if (dragging) 0.85f else 0.55f),
                    RoundedCornerShape(4.dp)
                )
        )
        // 交互层：仅左缘条带可命中（条带外零干扰）
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(with(density) { stripPx.toDp() })
                .pointerInput(wv, denom, travel, thumbHPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var moved = false
                        var lastY = down.position.y
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // 轻点轨道：滑块中心跳到点击位置
                                if (!moved) {
                                    val target = (((change.position.y - thumbHPx / 2f) / travel)
                                        .coerceIn(0f, 1f) * denom).toInt()
                                    runCatching { wv.scrollTo(0, target) }
                                }
                                dragging = false
                                break
                            }
                            val dy = change.position.y - lastY
                            lastY = change.position.y
                            if (!moved &&
                                abs(change.position.y - down.position.y) >
                                viewConfiguration.touchSlop / 3f
                            ) {
                                moved = true
                                dragging = true
                            }
                            if (moved) {
                                change.consume()
                                // 拖动滑块：往下拖 = 页面往下滚（scrollY 增大）
                                runCatching { wv.scrollBy(0, (dy / travel * denom).toInt()) }
                            }
                        }
                    }
                }
        )
    }
}
