package com.linbox.core.media

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * v6.10 录屏悬浮标（用户："录屏没有出现悬浮标显示录屏时间和停止录屏的标志"）。
 *
 * 录制期间常驻屏幕的可拖动胶囊：红点呼吸灯 + "REC mm:ss" 计时 + ■ 停止按钮。
 * 点击 ■ → [ScreenCaptureManager.stopRecording] 落盘保存。
 *
 * ## 附带的"0 秒假视频"根治
 *
 * AUTO_MIRROR 虚拟屏只在**屏幕内容变化**时向编码器推帧 —— LinBox 主页/
 * 静态桌面完全静止时整段录制可能一帧都没有，MediaRecorder.stop() 因
 * "无帧"抛异常 → 旧版被吞掉 → 落盘无 moov atom 的 0 秒坏 MP4。
 * 本悬浮标的秒数文本每秒变化 → 本应用窗口每秒重组出帧 → 显示合成器
 * 产生新帧 → 虚拟屏持续收到帧 → 录制时长与真实时间一致（游戏画面
 * 本身连续变化，此机制保证的是"静态界面也录得下"）。
 *
 * 渲染位置：LinBoxShell 全局层（X11 游戏全屏 / 浏览器 / 任意页面均可见，
 * 位于手柄层之上、虚拟鼠标指针之下）。
 */
@Composable
fun RecordingBadgeOverlay() {
    if (!ScreenCaptureManager.recording) return

    val density = LocalDensity.current
    val rootView = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // 拖动位置（px，相对窗口左上角）；默认顶部居中
    var pos by remember { mutableStateOf(Offset(Float.MIN_VALUE, 24f)) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // 首帧定位：顶部居中（rootView 宽度布局完成后可用）
    val badgeW = with(density) { 118.dp.toPx() }
    val badgeH = with(density) { 30.dp.toPx() }
    if (pos.x == Float.MIN_VALUE && rootView.width > 0) {
        pos = Offset((rootView.width - badgeW) / 2f, 18f)
    }

    // 红点呼吸灯（1.2s 循环 0.35~1.0 透明度）
    val pulse by rememberInfiniteTransition(label = "rec-pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600), repeatMode = RepeatMode.Reverse
        ),
        label = "rec-pulse-alpha"
    )

    val mm = ScreenCaptureManager.elapsedSec / 60
    val ss = ScreenCaptureManager.elapsedSec % 60
    val timeText = "%02d:%02d".format(mm, ss)

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .offset {
                    val p = if (pos.x == Float.MIN_VALUE)
                        Offset((rootView.width - badgeW).coerceAtLeast(0f) / 2f, 18f)
                    else pos
                    IntOffset(
                        (p.x + dragOffset.x)
                            .coerceIn(0f, (rootView.width - badgeW).coerceAtLeast(0f)),
                        (p.y + dragOffset.y)
                            .coerceIn(0f, (rootView.height - badgeH).coerceAtLeast(0f))
                    )
                }
                .background(Color(0xE6161616), RoundedCornerShape(15.dp))
                .border(1.dp, Color(0x59FF5252), RoundedCornerShape(15.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount
                        },
                        onDragEnd = {
                            pos = Offset(pos.x + dragOffset.x, pos.y + dragOffset.y)
                            dragOffset = Offset.Zero
                        },
                        onDragCancel = { dragOffset = Offset.Zero }
                    )
                }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // 红点呼吸灯
            Box(
                Modifier
                    .size(9.dp)
                    .alpha(pulse)
                    .background(Color(0xFFFF3B30), CircleShape)
            )
            Text(
                "REC $timeText",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
            // ■ 停止按钮（红底方块）
            Box(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(18.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectTapGestures {
                            ScreenCaptureManager.stopRecording(context)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(Color.White, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}
