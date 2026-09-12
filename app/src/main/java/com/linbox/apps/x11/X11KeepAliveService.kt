package com.linbox.apps.x11

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * v2.22.4 fix11c：X11 会话前台常驻服务（"x11 要加常驻，防止被杀"）。
 *
 * 背景：X server 是终端侧 app_process（CmdEntryPoint，经 linbox-x11 拉起），
 * 显示端在本 App 进程（LorieView 渲染）。App 退到后台后：
 * - Android 12+ 会冻结/杀死缓存 App 进程 → 画面断连；
 * - X server 进程虽独立，但它是"被本 App 持有 binder"的被绑定方，
 *   优先级随客户端走 —— 客户端（本 App）一旦被冻结/杀掉，X server
 *   很快跟着被杀（终端 shell 也会随 App 死亡而 SIGHUP 整棵进程树）。
 *
 * 机制：
 * - 检测到 X 会话（ACTION_START 广播）即启动本前台服务，常驻通知
 *   "X11 运行中" 把 App 进程钉在前台（specialUse 类型，targetSdk 28
 *   语义下系统不冻结前台服务所在进程）；
 * - 每 5 秒核查 X server binder 存活（linbox-x11-stop / 进程被杀都会
 *   置 binder dead）→ 会话结束自动停止自身、撤掉常驻通知；
 * - 通知动作："X11 设置"（打开 X11 窗口 + 弹设置面板）、点击正文回到
 *   LinBox 桌面。长按通知 = 系统级通知设置入口。
 */
class X11KeepAliveService : Service() {

    companion object {
        private const val TAG = "X11KeepAlive"
        private const val CHANNEL_ID = "x11_keepalive"
        private const val NOTIF_ID = 7894
        const val ACTION_OPEN_SETTINGS = "com.linbox.x11.KEEPALIVE_OPEN_SETTINGS"
        const val ACTION_OPEN_WINDOW = "com.linbox.x11.KEEPALIVE_OPEN_WINDOW"

        @Volatile private var running = false

        /** 检测到/进入 X 会话时调用（幂等；后台启动受限场景静默降级）。 */
        fun start(context: Context) {
            if (running) return
            val intent = Intent(context, X11KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                // 后台启动限制（Android 12+ 罕见路径）：X 窗口在前台时广播
                // 必然来自可见 App，不会走到这里；保守降级不炸会话。
                Log.w(TAG, "启动常驻服务被系统限制（不影响 X11 功能本身）", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, X11KeepAliveService::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 会话存活巡检：X server binder 死亡（linbox-x11-stop/被杀）→ 自停。 */
    private val sessionPoll = object : Runnable {
        override fun run() {
            if (!X11WindowController.isSessionAlive()) {
                Log.i(TAG, "X 会话已结束，常驻服务自动退出")
                stopSelf()
                return
            }
            mainHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        startForegroundWithNotification()
        mainHandler.postDelayed(sessionPoll, 5000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OPEN_SETTINGS -> {
                bringAppToFront()
                X11WindowController.openWindow(this)
                X11SettingsBridge.requestShow()
            }
            ACTION_OPEN_WINDOW -> {
                bringAppToFront()
                X11WindowController.openWindow(this)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacks(sessionPoll)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 回到 LinBox 桌面（launcher intent；singleTask 不会重复建栈）。 */
    private fun bringAppToFront() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "拉起主界面失败", e)
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "X11 运行状态", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = PendingIntent.getActivity(
            this, 0, launch,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val settingsPi = PendingIntent.getService(
            this, 1, Intent(this, X11KeepAliveService::class.java).setAction(ACTION_OPEN_SETTINGS),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val iconId = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_view
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
                      else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification: Notification = builder
            .setSmallIcon(iconId)
            .setContentTitle("X11 运行中")
            .setContentText("X11 桌面会话常驻保护 · 点按回到 LinBox")
            .setContentIntent(contentPi)
            .addAction(0, "X11 设置", settingsPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
