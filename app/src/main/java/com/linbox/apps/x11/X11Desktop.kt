package com.linbox.apps.x11

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.termux.x11.CmdEntryPoint
import com.termux.x11.MainActivity

/**
 * v2.22.2 内置 X11 桌面入口。
 *
 * 显示端是独立的全屏 Activity（com.termux.x11.MainActivity，随
 * :termux-x11 模块打进本 APK），不嵌入终端界面：
 *
 * - 终端调用：`linbox-x11 :13` 以宿主 APK 为 CLASSPATH 经 app_process
 *   启动 CmdEntryPoint（X server：lorie 合成器 + Xwayland in-process），
 *   客户端进程每秒广播 [CmdEntryPoint.ACTION_START]（附带连接 fd 的
 *   binder）直到被取用；
 * - 本类在 LinBoxApp 的动态接收器里收到广播后拉起全屏 X11 桌面，
 *   桌面 Activity 自己的动态接收器会在 1 秒内的重播里拿到 binder，
 *   取出 X 连接 fd 交给 LorieView 渲染（X 屏幕尺寸随视图自动调节）；
 * - 兼容入口：X11 等待页"打开兼容全屏模式"按钮直接拉起本 Activity，
 *   服务未启动时显示等待连接页。
 *
 * 与官方 Termux:X11 应用互不冲突：广播 setPackage 只指向 com.linbox，
 * X11 socket 落在 com.linbox 自身的 tmp 目录，客户端进程 nice-name
 * 独立（linboxx11）。
 */
object X11Desktop {

    private const val TAG = "X11Desktop"
    private const val LAUNCH_DEBOUNCE_MS = 2000L

    private var lastLaunchAt = 0L

    /** 直接打开 X11 桌面（桌面图标 / 终端广播共用）。 */
    fun open(context: Context) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "打开 X11 桌面失败", e)
        }
    }

    /**
     * 终端侧 ACTION_START 广播入口（LinBoxApp 注册的接收器回调）。
     * 客户端每秒重播一次直到连接被取用，这里做去抖后拉起桌面；
     * binder 由 X11 桌面 Activity 自己的接收器从重播中获取。
     */
    fun openFromBroadcast(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < LAUNCH_DEBOUNCE_MS) return
        lastLaunchAt = now
        open(context)
    }
}
