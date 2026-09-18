package com.linbox.apps.dac

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * DacScreen — 「DAC 显示器」全屏页面（v1.18）
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
 * - wine wrapper / `linbox-dac` 脚本发 DAC_START 广播 → DacReceiver →
 *   DacApp.requestStart 自动跳转本页（am 可用时仍是等效路径）。
 *
 * 渲染与交互：
 * - 画面为 DacView（SurfaceView）：winedac.drv 经 unix socket 直送
 *   AHardwareBuffer，SurfaceFlinger 合成（不依赖 X11）；
 * - 触摸即鼠标、虚拟键盘/手柄经 LinBox 输入覆盖层桥入（DacInput）；
 * - wine 侧窗口标题 / 连接状态由 DacApp.showTitle 显示在画面顶部；
 * - 返回键或右上角 ✕ 回终端主页；离开页面自动停显（wine 侧
 *   winedac.drv 持续重连，重进本页自动接上，无需重启 wine）。
 */
@Composable
fun DacScreen() {
    val nativeReady = remember { DacNative.available }

    // 返回键：回终端主页（wine 会话不受影响，winedac 会持续重连）
    BackHandler(enabled = true) { ShellController.showTerminal() }

    // 离开页面：停显并解除宿主容器（恢复 DacApp 兜底模式语义）
    DisposableEffect(Unit) {
        onDispose {
            DacApp.stopDisplay()
            DacApp.attachHost(null)
        }
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
            // liblinbox_dac_bridge.so 不可用（API < 29 等）：给出人话提示
            Text(
                text = "DAC 显示器不可用\n\n" +
                    "原生桥组件（liblinbox_dac_bridge.so）未能加载，\n" +
                    "本设备可能低于 Android 10（API 29）。\n" +
                    "请改用 X11 桌面（linbox-x11 / 悬浮球 X11）。",
                color = Color(0xFF9E9E9E),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // ===== 右上角悬浮控制条：状态点 + 关闭（不遮挡画面主体触摸） =====
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
