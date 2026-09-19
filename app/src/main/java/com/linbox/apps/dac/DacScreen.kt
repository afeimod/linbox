package com.linbox.apps.dac

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.linbox.core.shell.ShellController

/**
 * DacScreen — 「DAC 显示器」全屏页面（v1.21）
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 此前 DAC 画面只有两条不可靠路径：wine wrapper 的 am 广播自动拉起
 * （受安卓限制常 Aborted），或旧版 decorView 兜底覆盖层（无任何按钮，
 * 盖住终端无法退出）—— 文档与 wrapper 日志里"点开「DAC 显示器」应用"
 * 的指引根本没有入口。本页面补上这个入口：
 *
 * 进入方式：
 * - 终端悬浮球菜单 → 「DAC 显示器」（ShellController.showDac()）；
 * - 终端命令桥 `linbox open dac`（LinBoxShellBridge）；
 * - 迷你悬浮窗标题条/▣（最小化后还原，v1.21）；
 * - wine wrapper / `linbox-dac` 脚本发 DAC_START 广播 → DacReceiver →
 *   DacApp.requestStart（v1.21：终端页触发改为迷你悬浮窗，不跳页）。
 *
 * 渲染与交互：
 * - 画面为 DacView（SurfaceView）：winedac.drv 经 unix socket 直送
 *   AHardwareBuffer，SurfaceFlinger 合成（不依赖 X11）；
 * - 触摸即鼠标、虚拟键盘/手柄经 LinBox 输入覆盖层桥入（DacInput）；
 * - wine 侧窗口标题 / 连接状态由 DacApp.showTitle 显示在画面顶部；
 * - 返回键或右上角 [─] 最小化：回终端，画面在悬浮小窗继续运行（v1.21）；
 * - 右上角 [✕] 关闭：停止显示；
 * - 任意顺序均可用：wine 侧 v1.21 重连看门狗每 2 秒自动接上
 *   （先 wine 后 DAC / 先 DAC 后 wine / 中途关掉 DAC 均可）。
 */
@Composable
fun DacScreen() {
    val nativeReady = remember { DacNative.available }

    // 返回键：最小化 —— 回终端且 DAC 继续在悬浮小窗运行（v1.21）
    BackHandler(enabled = true) { DacApp.minimize() }

    // 离开页面：页面模式停显；最小化模式（画面已迁到悬浮窗）继续运行
    DisposableEffect(Unit) {
        onDispose { DacApp.onPageGone() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (nativeReady) {
            // ===== DAC 画面宿主：DacApp 在此容器内创建 DacView =====
            // attachHost 与 startDisplay 都经 main.post 排队，顺序有保证：
            // 容器先挂载，随后按 requestedSize（DAC_START 广播或默认 1280x720）开屏
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        // android.graphics.Color.BLACK（全限定避免与 Compose Color 混淆）
                        setBackgroundColor(android.graphics.Color.BLACK)
                        DacApp.attachHost(this)
                        val (w, h) = DacApp.requestedSize
                        post { DacApp.startDisplay(w, h) }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // liblinbox_dac_bridge.so 不可用：按真实原因诊断（v1.19）
            // - API < 26：无 AHardwareBuffer，DAC 协议无法工作（真不支持）；
            // - API ≥ 26 但加载失败：多为 APK 未包含本机 ABI 的 so 或
            //   符号缺失（dlopen 原文直接展示，便于远程排查）。
            val api = DacNative.deviceApi
            val err = DacNative.loadError ?: "未知原因"
            val reason = if (api < 26) {
                "本设备为 Android " + android.os.Build.VERSION.RELEASE +
                    "（API " + api + "），低于 DAC 所需的 Android 8.0（API 26）：" +
                    "系统缺少 AHardwareBuffer，无法接收 wine 侧画面帧。\n" +
                    "请改用 X11 桌面（linbox-x11 / 悬浮球 X11）。"
            } else {
                "本设备 API " + api + "（≥26，具备 DAC 硬件条件），库加载失败原因：\n" +
                    err + "\n\n" +
                    "常见处理：重新安装完整 APK（勿拆分/精简 ABI）；" +
                    "若仍失败请连 adb 抓 LinBoxDAC 日志。"
            }
            Text(
                text = "DAC 显示器不可用\n\n" + reason,
                color = Color(0xFF9E9E9E),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // ===== 右上角悬浮控制条：最小化 + 关闭（不遮挡画面主体触摸） =====
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [─] 最小化（v1.21）：回终端，画面在悬浮小窗继续运行
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color(0xB3141414), CircleShape)
                    .border(1.dp, Color(0x33FFFFFF), CircleShape)
                    .clickable { DacApp.minimize() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "─",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // [✕] 关闭：停止显示并回终端（区别于最小化）
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color(0xB3141414), CircleShape)
                    .border(1.dp, Color(0x33FFFFFF), CircleShape)
                    .clickable { ShellController.showTerminal() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 左上角页面标识（半透明，不挡 DacApp 顶部标题：标题居中渲染）
        Text(
            text = "DAC 显示器",
            color = Color(0x66FFFFFF),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 16.dp, start = 14.dp)
                .background(Color(0x66000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
