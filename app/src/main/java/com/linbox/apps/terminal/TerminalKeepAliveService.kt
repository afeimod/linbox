package com.linbox.apps.terminal

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
import android.os.PowerManager
import android.util.Log
import com.linbox.LinBoxApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * v2.27：终端会话前台常驻服务（"终端和 x11 界面需要常驻状态栏避免被杀"）。
 *
 * 背景：终端 shell 是本 App 进程 fork 的子进程（PTY 主端也在本进程）。
 * App 退到后台后，Android 会冻结/杀死缓存进程 → shell 及其整棵子进程树
 * （apt 下载、编译、wine/X11 会话）全部随宿主死亡（表现为进程莫名消失、
 * 长任务跑到一半被杀）。X11 侧已有 [com.linbox.apps.x11.X11KeepAliveService]，
 * 本服务把同等保护覆盖到终端会话。
 *
 * 机制（与 X11 服务同模式）：
 * - 终端会话创建时启动本前台服务，常驻通知"终端运行中"把 App 进程钉在
 *   前台（specialUse 类型；targetSdk 28 语义下前台服务所在进程不参与
 *   缓存冻结/回收）；
 * - 每 5 秒核查会话存活（exit / 被杀都会置 not running）→ 会话结束自动
 *   停止自身、撤掉常驻通知；新会话（悬浮球/通知动作）会重新拉起；
 * - 通知动作"新建会话"：会话已结束时直接重建 shell 并回到 LinBox；
 * - 开发者选项"CPU 保持唤醒"开启后（ACTION_REFRESH_WAKELOCK 或启动时
 *   从 DataStore 读取），持有 PARTIAL_WAKE_LOCK，后台长任务不休眠。
 */
class TerminalKeepAliveService : Service() {

    companion object {
        private const val TAG = "TerminalKeepAlive"
        private const val CHANNEL_ID = "terminal_keepalive"
        private const val NOTIF_ID = 7895
        const val ACTION_NEW_SESSION = "com.linbox.terminal.KEEPALIVE_NEW_SESSION"
        const val ACTION_REFRESH_WAKELOCK = "com.linbox.terminal.KEEPALIVE_REFRESH_WAKELOCK"

        @Volatile private var running = false

        /** 开发者选项"CPU 保持唤醒"期望值（服务存活期间由设置页/启动读取刷新）。 */
        @Volatile private var wakeLockDesired = false

        /** 终端会话创建时调用（幂等；后台启动受限场景静默降级）。 */
        fun start(context: Context) {
            if (running) return
            val intent = Intent(context, TerminalKeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                // 后台启动限制（Android 12+ 罕见路径）：会话创建必然发生在
                // 可见 App 内，不会走到这里；保守降级不炸会话创建。
                Log.w(TAG, "启动终端常驻服务被系统限制（不影响终端功能本身）", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalKeepAliveService::class.java))
        }

        /** 开发者选项切换"CPU 保持唤醒"时调用：刷新期望值并通知存活服务。 */
        fun setCpuAwake(context: Context, enabled: Boolean) {
            wakeLockDesired = enabled
            if (!running) return
            val intent = Intent(context, TerminalKeepAliveService::class.java)
                .setAction(ACTION_REFRESH_WAKELOCK)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) {
                // 服务已在前台时此路径不会失败；忽略
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 会话存活巡检：shell 退出（exit/被杀）→ 自停；新会话会重新拉起。 */
    private val sessionPoll = object : Runnable {
        override fun run() {
            if (!TermuxTerminalHolder.hasLiveSession()) {
                Log.i(TAG, "终端会话已结束，常驻服务自动退出")
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
        // 启动时同步一次"CPU 保持唤醒"偏好（DataStore 异步读，放后台线程）
        thread(name = "keepalive-pref") {
            val enabled = try {
                runBlocking { LinBoxApp.get().settingsStore.devKeepCpuAwake.first() }
            } catch (e: Exception) {
                Log.w(TAG, "读取 CPU 唤醒偏好失败: ${e.message}")
                false
            }
            wakeLockDesired = enabled
            mainHandler.post { updateWakeLock() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_NEW_SESSION -> {
                bringAppToFront()
                if (!TermuxTerminalHolder.hasLiveSession()) {
                    TermuxTerminalHolder.newSession(applicationContext)
                }
            }
            ACTION_REFRESH_WAKELOCK -> updateWakeLock()
        }
        // START_STICKY：系统回收后尽量重建（重建后会话若已死由巡检自停）
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacks(sessionPoll)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 回到 LinBox 主界面（launcher intent；singleTask 不会重复建栈）。 */
    private fun bringAppToFront() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "拉起主界面失败", e)
        }
    }

    private fun updateWakeLock() {
        if (wakeLockDesired) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linbox:terminal_keepalive")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                Log.i(TAG, "CPU 保持唤醒已开启（partial wake lock）")
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "唤醒锁释放异常: ${e.message}")
        }
        wakeLock = null
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "终端运行状态", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = PendingIntent.getActivity(
            this, 0, launch,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val newSessionPi = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalKeepAliveService::class.java).setAction(ACTION_NEW_SESSION),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val iconId = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_manage
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
                      else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification: Notification = builder
            .setSmallIcon(iconId)
            .setContentTitle("终端运行中")
            .setContentText("终端会话常驻保护 · 点按回到 LinBox")
            .setContentIntent(contentPi)
            .addAction(0, "新建会话", newSessionPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
