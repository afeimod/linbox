package com.linbox.apps.dac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DacReceiver — 终端命令行入口（am broadcast 触发）
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 用法（LinBox 终端内）：
 * ```
 * am broadcast -a com.linbox.action.DAC_START \
 *     --ei width 1280 --ei height 720
 * am broadcast -a com.linbox.action.DAC_STOP
 * am broadcast -a com.linbox.action.DAC_STATUS
 * ```
 * `linbox-dac` 脚本与 wine 自举 wrapper（bin/wine）自动封装上述调用。
 *
 * v1.18：DAC_START 改经 DacApp.requestStart 统一路由 —— 优先跳转
 * 全屏「DAC 显示器」页面（带关闭按钮、可回终端），不再裸挂
 * decorView 覆盖层；DAC_STOP 时若正停在 DAC 页面则退回终端主页。
 */
class DacReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LinBoxDAC"
        const val ACTION_DAC_START = "com.linbox.action.DAC_START"
        const val ACTION_DAC_STOP = "com.linbox.action.DAC_STOP"
        const val ACTION_DAC_STATUS = "com.linbox.action.DAC_STATUS"

        /** bridge 就绪广播（linbox-dac 脚本轮询等待用） */
        const val ACTION_DAC_READY = "com.linbox.action.DAC_READY"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_READY = "ready"
        val DAC_BROADCAST_PERMISSION: String? = null  // 同 UID 终端直发，无需权限（const 不允许可空，用 val）
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DAC_START -> {
                val w = intent.getIntExtra(EXTRA_WIDTH, 1280)
                val h = intent.getIntExtra(EXTRA_HEIGHT, 720)
                Log.i(TAG, "DAC_START ${w}x${h}")
                DacApp.requestStart(w, h)
            }
            ACTION_DAC_STOP -> {
                Log.i(TAG, "DAC_STOP")
                DacApp.instance?.stopDisplay()
                // 正停在 DAC 页面（画面已停、黑屏无内容）→ 退回终端主页
                if (com.linbox.core.shell.ShellController.screen ==
                    com.linbox.core.shell.ShellController.Screen.DAC
                ) {
                    com.linbox.core.shell.ShellController.showTerminal()
                }
            }
            ACTION_DAC_STATUS -> {
                val ready = DacApp.instance?.isRunning ?: false
                val backend = DacApp.instance?.backend ?: DacNative.BACKEND_NONE
                Log.i(TAG, "DAC_STATUS ready=$ready backend=$backend")
                setResultData("ready=$ready backend=$backend")
                // 同步回执给脚本：发就绪广播
                val ack = Intent(ACTION_DAC_READY)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_READY, ready)
                    .putExtra(EXTRA_BACKEND, backend)
                context.sendBroadcast(ack)
            }
        }
    }
}
