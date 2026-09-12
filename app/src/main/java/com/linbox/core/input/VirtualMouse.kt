package com.linbox.core.input

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.linbox.LinBoxApp
import kotlin.math.roundToInt

/**
 * 虚拟鼠标指针（v2.13）：Windows 经典箭头样式的屏幕指针。
 *
 * - 位置：由 DesktopEnvironment 根节点以"纯观察者"方式跟踪全部触摸事件更新，
 *   指针贴着手指移动，抬手后停留在原地（与真实桌面一致）。
 * - 点击动画：快速轻点时在指针热点处播放一次扩散涟漪。
 * - 主题/大小：白(经典) / 黑 / 蓝 / 绿 四种主题，16..48dp 缩放。
 * - 单击/双击打开、双指/长按右键等行为设置见 SettingsStore：
 *   mouseClickMode / mouseRightClick（由 DesktopIconGrid / DesktopEnvironment 消费）。
 */
object MouseController {

    /** 指针当前位置（屏幕像素，根坐标系）。初始隐藏在屏幕外。 */
    var position by mutableStateOf(Offset(-5000f, -5000f))
        private set

    /** 当前是否有手指按在屏幕上 */
    var pressed by mutableStateOf(false)
        private set

    /** 本次点击涟漪计数器（每 +1 触发一次动画） */
    var flashTick by mutableStateOf(0)
        private set

    /** 是否已初始化过位置（首次进入桌面时放到屏幕中央偏上） */
    var initialized by mutableStateOf(false)
        private set

    private var pressStartMs = 0L
    private var pressStartPos = Offset.Zero
    private var lastDownHadPress = false

    /** 首次进入桌面：指针放到屏幕 40% / 32% 处，避免看不见 */
    fun initialize(x: Float, y: Float) {
        if (!initialized) {
            position = Offset(x, y)
            initialized = true
        }
    }

    /** 指针移动到指定屏幕坐标（触摸跟踪调用） */
    fun update(x: Float, y: Float) {
        position = Offset(x, y)
    }

    /**
     * 按压状态变化。
     * 抬起时若判定为"快速轻点"（<300ms 且位移 < 24px）则触发点击涟漪。
     *
     * 命名注意：不能叫 setPressed —— var pressed 属性的 setter 已生成
     * 同签名的 setPressed(Z)V，再写 fun setPressed 会报 JVM 平台声明冲突
     * （v2.13 CI 第三轮修复点）。
     */
    fun press(down: Boolean) {
        if (down) {
            if (!lastDownHadPress) {
                pressStartMs = System.currentTimeMillis()
                pressStartPos = position
                lastDownHadPress = true
            }
            pressed = true
        } else {
            if (lastDownHadPress) {
                val quick = System.currentTimeMillis() - pressStartMs < 300L
                val still = (position - pressStartPos).getDistance() < 24f
                if (quick && still) flashTick++
                lastDownHadPress = false
            }
            pressed = false
        }
    }

    /** 手动触发一次点击涟漪（鼠标设置页测试区域使用） */
    fun flash() {
        flashTick++
    }
}

// ============================================================
// 指针主题
// ============================================================

/** Windows 风格指针主题：填充色 + 描边色 */
data class MouseCursorTheme(val fill: Color, val stroke: Color, val label: String)

internal fun cursorThemeOf(id: String): MouseCursorTheme = when (id) {
    "black" -> MouseCursorTheme(Color(0xFF1A1A1A), Color.White, "经典黑")
    "blue" -> MouseCursorTheme(Color(0xFF2C7BE5), Color.White, "蓝色")
    "green" -> MouseCursorTheme(Color(0xFF00B74F), Color(0xFF0B3D22), "高对比绿")
    else -> MouseCursorTheme(Color.White, Color(0xFF1A1A1A), "经典白")
}

/**
 * Windows 经典箭头归一化轮廓（宽 0.68 x 高 1.0），
 * 热点在 (0,0)（箭头尖端），与 Windows 鼠标一致。
 */
private val ARROW_POINTS = listOf(
    0.000f to 0.000f,
    0.000f to 0.861f,
    0.242f to 0.655f,
    0.398f to 1.000f,
    0.527f to 0.938f,
    0.371f to 0.596f,
    0.685f to 0.596f
)

/** 构造 Windows 风格箭头 Path（归一化坐标缩放到 heightPx 高） */
internal fun arrowPath(heightPx: Float): Path = Path().apply {
    val w = heightPx * 0.68f
    ARROW_POINTS.forEachIndexed { i, (nx, ny) ->
        val x = nx * w
        val y = ny * heightPx
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** 在 DrawScope 中绘制指针：填充 + 描边（Windows 白底黑边风格） */
internal fun DrawScope.drawCursor(path: Path, theme: MouseCursorTheme, strokePx: Float) {
    drawPath(path, theme.fill)
    drawPath(path, theme.stroke, style = Stroke(width = strokePx))
}

// ============================================================
// 指针覆盖层（DesktopEnvironment 顶层挂载，最上层）
// ============================================================

/**
 * 全屏鼠标指针覆盖层：不拦截任何触摸（自身无 pointerInput），
 * 仅按 MouseController.position 渲染指针 + 点击涟漪。
 */
@Composable
fun MouseCursorOverlay() {
    val app = LinBoxApp.get()
    val enabled by app.settingsStore.mouseCursorEnabled.collectAsState(initial = true)
    // v2.19：触控板模式下指针就是鼠标本体，强制显示
    val controlMode by app.settingsStore.mouseControlMode.collectAsState(initial = "touch")
    val themeId by app.settingsStore.mouseCursorTheme.collectAsState(initial = "white")
    val sizeDp by app.settingsStore.mouseCursorSize.collectAsState(initial = 26f)

    if ((!enabled && controlMode != "trackpad") || !MouseController.initialized) return

    val theme = remember(themeId) { cursorThemeOf(themeId) }
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.dp.toPx() }

    // 按压时指针轻微缩小（模拟"按下去"的手感）
    val pressScale by animateFloatAsState(
        targetValue = if (MouseController.pressed) 0.82f else 1f,
        animationSpec = tween(90),
        label = "cursorPress"
    )

    // 点击涟漪动画
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(MouseController.flashTick) {
        if (MouseController.flashTick > 0) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(420))
        }
    }
    val rippleAlpha = (1f - ripple.value) * 0.5f
    val rippleRadius = sizePx * (0.3f + ripple.value * 1.15f)

    // 画布略大于指针本身，为涟漪留空间；箭头热点放在画布中心
    val canvasDp = (sizeDp * 1.6f).dp

    // 无 pointerInput —— 纯渲染层，不挡任何交互
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset {
                    // 让画布中心对准指针位置 → 箭头热点(画布中心)即触摸点
                    IntOffset(
                        MouseController.position.x.roundToInt() - with(density) { canvasDp.toPx().toInt() } / 2,
                        MouseController.position.y.roundToInt() - with(density) { canvasDp.toPx().toInt() } / 2
                    )
                }
                .size(canvasDp)
        ) {
            Canvas(modifier = Modifier.size(canvasDp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                // 涟漪
                if (rippleAlpha > 0.01f) {
                    drawCircle(
                        color = theme.stroke.copy(alpha = rippleAlpha),
                        radius = rippleRadius,
                        center = center
                    )
                }
                // 箭头：热点(0,0)平移到画布中心（上半段中点，视觉居中）
                val h = sizePx * pressScale
                val path = arrowPath(h)
                path.translate(Offset(center.x, center.y - h / 2f))
                val strokeW = (sizePx * 0.055f).coerceAtLeast(2f)
                drawPath(path, theme.fill)
                drawPath(path, theme.stroke, style = Stroke(width = strokeW))
            }
        }
    }
}

/**
 * 指针静态预览（鼠标设置页主题选择 / 大小实时预览用）。
 * 非交互纯渲染。
 */
@Composable
fun MouseCursorPreview(themeId: String, sizeDp: Float, modifier: Modifier = Modifier) {
    val theme = remember(themeId) { cursorThemeOf(themeId) }
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.dp.toPx() }
    val boxSize = (sizeDp * 1.5f).dp
    Box(modifier = modifier.size(boxSize)) {
        Canvas(modifier = Modifier.size(boxSize)) {
            val h = sizePx
            val path = arrowPath(h)
            path.translate(Offset((size.width - h * 0.68f) / 2f, (size.height - h) / 2f))
            drawPath(path, theme.fill)
            drawPath(path, theme.stroke, style = Stroke(width = (sizePx * 0.05f).coerceAtLeast(2f)))
        }
    }
}
