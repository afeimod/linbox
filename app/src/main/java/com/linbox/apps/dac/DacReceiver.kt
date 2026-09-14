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
 * `linbox-dac` 脚本自动封装上述调用。
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
                DacApp.instance?.startDisplay(w, h)
            }
            ACTION_DAC_STOP -> {
                Log.i(TAG, "DAC_STOP")
                DacApp.instance?.stopDisplay()
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
