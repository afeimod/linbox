package com.linbox.shellui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v4.0 壳层设计系统（按用户提供的图片重制主页 / 游戏库）。
 *
 * 两种玻璃拟态（Glassmorphism）配色：
 * - [GlassLight]：主页（图 1）——浅色玻璃：柔和蓝白渐变背景 + 半透明白卡。
 * - [GlassDark]：游戏库（图 2）——深色主题：深夜蓝背景 + 微白玻璃卡。
 *
 * 组件：[GlassTopBar]（标题 + 搜索 + 设置圆形钮）、[GlassSearchField]、
 * [XboxControllerArt]（白色 Xbox 手柄 Canvas 图形，主页主卡用）。
 */

/** 玻璃拟态配色令牌。 */
data class GlassPalette(
    /** 页面背景渐变（顶 → 底）。 */
    val bgTop: Color,
    val bgBottom: Color,
    /** 玻璃卡底色（半透明）。 */
    val card: Color,
    /** 玻璃卡描边。 */
    val cardBorder: Color,
    /** 主文本。 */
    val textPrimary: Color,
    /** 次级文本。 */
    val textSecondary: Color,
    /** 品牌蓝（按钮 / 选中态）。 */
    val blue: Color,
    /** 品牌紫（渐变按钮右端）。 */
    val purple: Color,
    /** 顶栏圆形按钮底色。 */
    val topBarButtonBg: Color,
    /** 顶栏圆形钮图标色。 */
    val topBarButtonIcon: Color
) {
    val buttonGradient: Brush
        get() = Brush.horizontalGradient(listOf(blue, purple))

    companion object {
        /** 浅色玻璃（主页，图 1）。 */
        val Light = GlassPalette(
            bgTop = Color(0xFFF2F5FC),
            bgBottom = Color(0xFFE4EBFA),
            card = Color.White.copy(alpha = 0.72f),
            cardBorder = Color.White.copy(alpha = 0.85f),
            textPrimary = Color(0xFF1C2340),
            textSecondary = Color(0xFF5A6480),
            blue = Color(0xFF4E7CFF),
            purple = Color(0xFF8A5CFF),
            topBarButtonBg = Color.White.copy(alpha = 0.80f),
            topBarButtonIcon = Color(0xFF2A3352)
        )

        /** 深色玻璃（游戏库，图 2）。 */
        val Dark = GlassPalette(
            bgTop = Color(0xFF101528),
            bgBottom = Color(0xFF0B0F1E),
            card = Color.White.copy(alpha = 0.06f),
            cardBorder = Color.White.copy(alpha = 0.12f),
            textPrimary = Color.White,
            textSecondary = Color.White.copy(alpha = 0.60f),
            blue = Color(0xFF4E7CFF),
            purple = Color(0xFF8A5CFF),
            topBarButtonBg = Color.White.copy(alpha = 0.10f),
            topBarButtonIcon = Color.White
        )
    }
}

/**
 * 玻璃顶栏：标题 +（可选）副标题 + 搜索钮 + 设置钮（图 1 / 图 2 布局）。
 */
@Composable
fun GlassTopBar(
    palette: GlassPalette,
    title: String,
    subtitle: String? = null,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = palette.textPrimary,
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold
        )
        if (subtitle != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                subtitle,
                color = palette.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        GlassCircleButton(palette, "🔍", onSearch)
        Spacer(Modifier.width(8.dp))
        GlassCircleButton(palette, "⚙", onSettings)
    }
}

/** 顶栏圆形玻璃钮。 */
@Composable
fun GlassCircleButton(
    palette: GlassPalette,
    emoji: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 36.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.topBarButtonBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = size.value * 0.46f.sp, color = palette.topBarButtonIcon)
    }
}

/**
 * 玻璃搜索框（圆角全宽，放大镜 + 占位词；输入即过滤）。
 */
@Composable
fun GlassSearchField(
    palette: GlassPalette,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = palette.textPrimary,
            fontSize = 14.sp
        ),
        cursorBrush = Brush.verticalGradient(listOf(palette.blue, palette.purple)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔍", fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = palette.textSecondary, fontSize = 14.sp)
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "✕",
                        color = palette.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onValueChange("") }
                            .padding(4.dp)
                    )
                }
            }
        }
    )
}

/**
 * 白色 Xbox 手柄图形（Canvas 绘制，主页「游戏模式」主卡右侧装饰）。
 *
 * 造型：左右两个握把圆 + 中央连接体（圆角矩形）+ 十字键 + 四彩功能键 +
 * 双摇杆 + 中央导航键；白色主体带轻微描边，风格对齐图 1。
 */
@Composable
fun XboxControllerArt(
    modifier: Modifier = Modifier,
    bodyColor: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 布局比例（以画布为 1x1）
        val gripR = h * 0.30f              // 握把圆半径
        val leftC = Offset(w * 0.30f, h * 0.62f)
        val rightC = Offset(w * 0.70f, h * 0.62f)
        val bodyRect = RectF(w * 0.28f, h * 0.30f, w * 0.72f, h * 0.86f)

        // ---- 主体（白） ----
        drawCircle(bodyColor, gripR, leftC, style = Fill)
        drawCircle(bodyColor, gripR, rightC, style = Fill)
        drawRoundRect(
            color = bodyColor,
            topLeft = Offset(bodyRect.left, bodyRect.top),
            size = Size(bodyRect.width, bodyRect.height),
            cornerRadius = CornerRadius(bodyRect.width * 0.42f, bodyRect.width * 0.42f),
            style = Fill
        )
        // 顶部中央肩部
        drawRoundRect(
            color = bodyColor,
            topLeft = Offset(w * 0.34f, h * 0.16f),
            size = Size(w * 0.32f, h * 0.30f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            style = Fill
        )

        val line = bodyColor.copy(alpha = 0.9f)

        // ---- 左握把：十字键（背景色镂空风） ----
        val dpC = Offset(w * 0.245f, h * 0.60f)
        val arm = h * 0.085f
        val thick = h * 0.045f
        drawRoundRect(line, Offset(dpC.x - arm, dpC.y - thick / 2), Size(arm * 2, thick), CornerRadius(thick / 2, thick / 2), style = Stroke(width = 2f))
        drawRoundRect(line, Offset(dpC.x - thick / 2, dpC.y - arm), Size(thick, arm * 2), CornerRadius(thick / 2, thick / 2), style = Stroke(width = 2f))

        // ---- 右握把：四彩功能键（Y 黄 / X 蓝 / B 红 / A 绿） ----
        val btnC = Offset(w * 0.755f, h * 0.60f)
        val btnR = h * 0.036f
        val btnOff = h * 0.088f
        drawCircle(Color(0xFFFFD54F), btnR, Offset(btnC.x, btnC.y - btnOff))       // Y
        drawCircle(Color(0xFF64B5F6), btnR, Offset(btnC.x - btnOff, btnC.y))        // X
        drawCircle(Color(0xFFEF5350), btnR, Offset(btnC.x + btnOff, btnC.y))        // B
        drawCircle(Color(0xFF66BB6A), btnR, Offset(btnC.x, btnC.y + btnOff))        // A

        // ---- 双摇杆 ----
        val stickR = h * 0.105f
        drawCircle(bodyColor, stickR, Offset(w * 0.415f, h * 0.70f), style = Stroke(width = h * 0.028f))
        drawCircle(bodyColor, stickR, Offset(w * 0.585f, h * 0.70f), style = Stroke(width = h * 0.028f))

        // ---- 中央导航键 ----
        drawCircle(bodyColor, h * 0.052f, Offset(w * 0.50f, h * 0.345f), style = Stroke(width = h * 0.022f))
    }
}

/** 简易矩形（内部用）。 */
private data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
