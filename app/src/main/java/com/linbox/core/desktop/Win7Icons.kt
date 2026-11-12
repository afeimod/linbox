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
 * v3.4.5（Win7 1:1）：Win7 拟物图标（自绘 Canvas）。
 *
 * 背景：Windows 风格界面里，Material 扁平图标（Folder / Storage）与
 * Win7 拟物观感严重冲突 —— 文件管理器的"图片/视频/文档"等特殊文件夹
 * 与桌面"硬盘"入口都要求 3D 拟物质感。因此这里用 Canvas 自绘：
 *
 *  - [Folder]   —— 背片 + 前片 + 高光线的 Win7 黄色文件夹（3/4 视角）；
 *                 调用方在其正中叠一枚 emoji（Win7 快捷文件夹的封面预览位）。
 *  - [HardDisk] —— 金属盘体 + 顶部高光面 + 电源 LED + 标签条的 3D 硬盘。
 *
 * 之前这两个图标被引用为 `com.linbox.core.desktop.Win7Icons.*`，但该包
 * 从未落地（仓库里没有 core/desktop 目录），导致 :app 编译期
 * "Unresolved reference: desktop"。本文件补齐该包。
 *
 * 用法：`com.linbox.core.desktop.Win7Icons.Folder(size = 42.dp)`
 */
object Win7Icons {

    /** Win7 拟物文件夹（背片 + 前片 + 高光线）。 */
    @Composable
    fun Folder(size: Dp = 40.dp, modifier: Modifier = Modifier) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // 落影（右下偏移，柔化）
            drawRoundRect(
                color = Color(0x22000000),
                topLeft = Offset(w * 0.08f, h * 0.30f),
                size = Size(w * 0.86f, h * 0.56f),
                cornerRadius = CornerRadius(w * 0.06f)
            )

            // 背片（较暗的琥珀色）—— 顶部带标签页
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFF0A62E), Color(0xFFC87C12))
                ),
                topLeft = Offset(w * 0.10f, h * 0.24f),
                size = Size(w * 0.82f, h * 0.54f),
                cornerRadius = CornerRadius(w * 0.05f)
            )
            // 标签页（背片左上凸起）
            drawRoundRect(
                color = Color(0xFFE29A25),
                topLeft = Offset(w * 0.12f, h * 0.16f),
                size = Size(w * 0.34f, h * 0.14f),
                cornerRadius = CornerRadius(w * 0.045f)
            )

            // 前片（更亮的金黄，3/4 视角：上宽下略窄）
            val front = Path().apply {
                moveTo(w * 0.04f, h * 0.40f)
                lineTo(w * 0.96f, h * 0.40f)
                lineTo(w * 0.90f, h * 0.86f)
                lineTo(w * 0.10f, h * 0.86f)
                close()
            }
            drawPath(
                path = front,
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFFFD45C), Color(0xFFF0A21E))
                )
            )
            // 前片上沿高光
            drawPath(
                path = Path().apply {
                    moveTo(w * 0.06f, h * 0.425f)
                    lineTo(w * 0.94f, h * 0.425f)
                },
                color = Color(0x99FFFFFF),
                style = Stroke(width = h * 0.022f)
            )
            // 前片底部暗边（立体感）
            drawPath(
                path = Path().apply {
                    moveTo(w * 0.11f, h * 0.845f)
                    lineTo(w * 0.89f, h * 0.845f)
                },
                color = Color(0x33935C08),
                style = Stroke(width = h * 0.02f)
            )
            // 中央留白（调用方在此叠 emoji 封面缩略图）
            drawRoundRect(
                color = Color(0x22FFFFFF),
                topLeft = Offset(w * 0.26f, h * 0.50f),
                size = Size(w * 0.48f, h * 0.28f),
                cornerRadius = CornerRadius(w * 0.03f)
            )
        }
    }

    /** Win7 拟物 3D 硬盘（金属盘体 + 顶面高光 + 电源 LED + 标签条）。 */
    @Composable
    fun HardDisk(size: Dp = 40.dp, modifier: Modifier = Modifier) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // 落影
            drawRoundRect(
                color = Color(0x22000000),
                topLeft = Offset(w * 0.08f, h * 0.74f),
                size = Size(w * 0.84f, h * 0.14f),
                cornerRadius = CornerRadius(w * 0.05f)
            )

            // 顶面（受光的斜面）
            drawPath(
                path = Path().apply {
                    moveTo(w * 0.08f, h * 0.44f)
                    lineTo(w * 0.20f, h * 0.26f)
                    lineTo(w * 0.94f, h * 0.26f)
                    lineTo(w * 0.92f, h * 0.44f)
                    close()
                },
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFFDFEFF), Color(0xFFDCE1E8))
                )
            )

            // 盘体正面（金属渐变）
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFE9EDF2), Color(0xFF9AA3AE))
                ),
                topLeft = Offset(w * 0.08f, h * 0.44f),
                size = Size(w * 0.84f, h * 0.34f),
                cornerRadius = CornerRadius(w * 0.035f)
            )
            // 正面顶部高光
            drawLine(
                color = Color(0xCCFFFFFF),
                start = Offset(w * 0.12f, h * 0.465f),
                end = Offset(w * 0.88f, h * 0.465f),
                strokeWidth = h * 0.022f
            )

            // 电源 LED（绿点 + 光晕）
            drawCircle(
                color = Color(0x8855E06A),
                radius = w * 0.075f,
                center = Offset(w * 0.20f, h * 0.66f)
            )
            drawCircle(
                color = Color(0xFF59E36B),
                radius = w * 0.038f,
                center = Offset(w * 0.20f, h * 0.66f)
            )

            // 标签条（硬盘铭牌）
            drawRoundRect(
                color = Color(0xE6FFFFFF),
                topLeft = Offset(w * 0.40f, h * 0.60f),
                size = Size(w * 0.40f, h * 0.10f),
                cornerRadius = CornerRadius(w * 0.02f)
            )
            drawRoundRect(
                color = Color(0x55000000),
                topLeft = Offset(w * 0.43f, h * 0.635f),
                size = Size(w * 0.26f, h * 0.02f),
                cornerRadius = CornerRadius(w * 0.01f)
            )
        }
    }
}
