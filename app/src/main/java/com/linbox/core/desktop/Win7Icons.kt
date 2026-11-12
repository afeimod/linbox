package com.linbox.core.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Win7 拟物图标集（v3.4.5 引入，v3.4.7 补齐缺失实现）。
 *
 * 文件管理器（FileExplorerApp）在 Win7 主题下的文件夹 / 硬盘图标：
 * 替代 Material 扁平图标，用 Canvas 自绘 Win7 风格拟物造型
 * （背片+前片+高光 / 金属盘体+高光+LED+标签条），零位图资源、
 * 任意尺寸无损缩放。
 *
 * 用法：`Win7Icons.Folder(size = 42.dp)` / `Win7Icons.HardDisk(size = 42.dp)`
 */
object Win7Icons {

    /**
     * Win7 拟物文件夹：深色背片（含索引标签）+ 浅色前片 + 顶部高光线。
     * 配色取自 Win7 Aero 资源管理器的琥珀黄文件夹。
     */
    @Composable
    fun Folder(size: Dp = 42.dp) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            // ---- 1. 背片（含上侧索引标签）----
            val backTop = 0.10f * h
            val backBottom = 0.88f * h
            val tabPath = Path().apply {
                moveTo(0.05f * w, 0.34f * h)
                lineTo(0.05f * w, backTop + 0.06f * h)
                // 圆角过渡到标签顶
                quadraticBezierTo(
                    0.05f * w, backTop,
                    0.11f * w, backTop
                )
                lineTo(0.38f * w, backTop)
                // 标签斜坡下沿
                lineTo(0.46f * w, 0.34f * h)
                close()
            }
            drawPath(
                path = tabPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFB98F5E), Color(0xFFA87F50)),
                    startY = backTop,
                    endY = 0.34f * h
                )
            )
            // 背片主体
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFC89A66), Color(0xFFB08350)),
                    startY = backTop,
                    endY = backBottom
                ),
                topLeft = Offset(0.05f * w, backTop),
                size = Size(0.90f * w, backBottom - backTop),
                cornerRadius = CornerRadius(0.05f * w, 0.05f * w)
            )

            // ---- 2. 前片（浅色，盖住背片下部）----
            val frontTop = 0.32f * h
            val frontBottom = 0.94f * h
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFBE3B4),  // 顶高光
                        Color(0xFFF3CF92),
                        Color(0xFFE2B274)   // 底渐暗
                    ),
                    startY = frontTop,
                    endY = frontBottom
                ),
                topLeft = Offset(0.02f * w, frontTop),
                size = Size(0.96f * w, frontBottom - frontTop),
                cornerRadius = CornerRadius(0.05f * w, 0.05f * w)
            )
            // 前片描边（浅浮雕轮廓）
            drawRoundRect(
                color = Color(0x66A87F50),
                topLeft = Offset(0.02f * w, frontTop),
                size = Size(0.96f * w, frontBottom - frontTop),
                cornerRadius = CornerRadius(0.05f * w, 0.05f * w),
                style = Stroke(width = 0.012f * w)
            )

            // ---- 3. 高光线（前片顶部 1px 拟物高光）----
            drawLine(
                color = Color(0xB3FFFFFF),
                start = Offset(0.08f * w, frontTop + 0.05f * h),
                end = Offset(0.92f * w, frontTop + 0.05f * h),
                strokeWidth = 0.015f * w
            )
        }
    }

    /**
     * Win7 拟物移动硬盘：金属盘体（垂直拉丝渐变）+ 顶部高光条 +
     * 绿色 LED（双层辉光）+ 底部标签条。
     */
    @Composable
    fun HardDisk(size: Dp = 42.dp) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            // ---- 1. 盘体（金属垂直渐变）----
            val bodyLeft = 0.10f * w
            val bodyTop = 0.20f * h
            val bodyRight = 0.90f * w
            val bodyBottom = 0.86f * h
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFEDF0F4),  // 顶面高光
                        Color(0xFFC9CED6),
                        Color(0xFF9FA6B0),  // 中腰暗带（拉丝折面）
                        Color(0xFFD9DDE3)   // 底面反光
                    ),
                    startY = bodyTop,
                    endY = bodyBottom
                ),
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyRight - bodyLeft, bodyBottom - bodyTop),
                cornerRadius = CornerRadius(0.06f * w, 0.06f * w)
            )
            // 盘体轮廓
            drawRoundRect(
                color = Color(0x80747B85),
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyRight - bodyLeft, bodyBottom - bodyTop),
                cornerRadius = CornerRadius(0.06f * w, 0.06f * w),
                style = Stroke(width = 0.014f * w)
            )

            // ---- 2. 顶部高光条（Aero 玻璃反光）----
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0x73FFFFFF), Color(0x00FFFFFF)),
                    startY = bodyTop,
                    endY = bodyTop + 0.22f * (bodyBottom - bodyTop)
                ),
                topLeft = Offset(bodyLeft + 0.03f * w, bodyTop + 0.02f * h),
                size = Size(bodyRight - bodyLeft - 0.06f * w, 0.20f * (bodyBottom - bodyTop)),
                cornerRadius = CornerRadius(0.04f * w, 0.04f * w)
            )

            // ---- 3. 标签条（下侧深色内嵌条）----
            val labelTop = bodyTop + 0.60f * (bodyBottom - bodyTop)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFF6E747E), Color(0xFF8A9099))
                ),
                topLeft = Offset(bodyLeft + 0.08f * w, labelTop),
                size = Size(bodyRight - bodyLeft - 0.16f * w, 0.16f * (bodyBottom - bodyTop)),
                cornerRadius = CornerRadius(0.02f * w, 0.02f * w)
            )
            // 标签条刻字（两道短槽线，示意型号铭牌）
            drawLine(
                color = Color(0x99FFFFFF),
                start = Offset(bodyLeft + 0.14f * w, labelTop + 0.05f * (bodyBottom - bodyTop)),
                end = Offset(bodyLeft + 0.50f * w, labelTop + 0.05f * (bodyBottom - bodyTop)),
                strokeWidth = 0.012f * w
            )
            drawLine(
                color = Color(0x80FFFFFF),
                start = Offset(bodyLeft + 0.14f * w, labelTop + 0.10f * (bodyBottom - bodyTop)),
                end = Offset(bodyLeft + 0.38f * w, labelTop + 0.10f * (bodyBottom - bodyTop)),
                strokeWidth = 0.010f * w
            )

            // ---- 4. LED（绿色双层辉光，工作指示灯）----
            val ledX = bodyRight - 0.10f * w
            val ledY = labelTop + 0.08f * (bodyBottom - bodyTop)
            // 外层辉光
            drawCircle(
                color = Color(0x4D7CE87C),
                radius = 0.055f * w,
                center = Offset(ledX, ledY)
            )
            // 灯芯
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFD6FFD3), Color(0xFF54C25A)),
                    center = Offset(ledX, ledY),
                    radius = 0.032f * w
                ),
                radius = 0.032f * w,
                center = Offset(ledX, ledY)
            )
        }
    }
}
