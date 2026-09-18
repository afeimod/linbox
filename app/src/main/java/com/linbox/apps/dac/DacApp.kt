package com.linbox.apps.dac

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/**
 * DacApp — DAC 显示生命周期管理（应用单例）
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 职责：
 *  1. 开关 DAC 浮动窗口（复用 LinBox 浮动窗口体系：宿主容器由
 *     LinBoxShell 提供；未接线时以独立全屏覆盖层兜底）
 *  2. 驱动 DacView 连接 JNI bridge
 *  3. 桥接 LinBox 输入系统（DacInput）
 *  4. socket 路径管理（$PREFIX/tmp/linbox-dac.sock）
 *
  * LinBoxShell 接线（可选增强）：DacApp.init(shellHostContainer)
 */
object DacApp : DacNative.TitleSink {

    private const val TAG = "LinBoxDAC"

    var instance: DacApp? = null
        private set

    private var rootView: FrameLayout? = null
    private var dacView: DacView? = null
    private var titleView: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    var isRunning = false
        private set
    var backend = DacNative.BACKEND_NONE
        private set
    private var desktopW = 1280
    private var desktopH = 720

    /**
     * 最近一次请求的虚拟桌面尺寸（v1.18）：DAC_START 广播携带的 WxH，
     * 或页面默认 1280x720。「DAC 显示器」页面组合时按此尺寸开屏。
     */
    var requestedSize: Pair<Int, Int> = 1280 to 720
        private set

    /**
     * DAC 显示统一入口（v1.18）：DacReceiver 广播与悬浮球按钮共用。
     * - 「DAC 显示器」页面已挂载宿主容器 → 直接在页面内开屏（含尺寸同步）；
     * - 否则跳转全屏「DAC 显示器」页面（页面组合时自动 startDisplay），
     *   不再裸挂 decorView 兜底覆盖层 —— 旧路径无关闭按钮、遮挡终端、
     *   且 am 广播到达时往往无前台 Activity（这正是「点开 App」成为
     *   唯一手动路径的原因）。
     */
    fun requestStart(width: Int, height: Int) {
        requestedSize = width to height
        if (rootView != null) {
            startDisplay(width, height)
        } else {
            com.linbox.core.shell.ShellController.showDac()
        }
    }

    /**
     * 「DAC 显示器」页面挂载/解除宿主容器（v1.18，主线程调用）。
     * 解除传 null：恢复兼容模式（startDisplay 走 decorView 兜底）。
     * 经 main.post 排队：保证先于同一轮组合内随后的 startDisplay 生效。
     */
    fun attachHost(container: FrameLayout?) {
        main.post { rootView = container }
    }

    /**
     * 无前台 Activity 时的挂起请求（v1.13）：如从 adb / 后台触发 DAC_START，
     * 兕底容器拿不到 decorView；旧实现直接抛异常（主线程崩溅风险），
     * 现改为记录请求，待下一个 Activity resume 时自动拉起。
     */
    private var pendingStart: Pair<Int, Int>? = null

    /**
     * 兜底覆盖层需要 Activity 的 decorView：LinBoxApp 以 Application 上下文
     * 初始化（未接 LinBoxShell 浮窗体系），此处经 ActivityLifecycleCallbacks
     * 捕获当前前台 Activity，startDisplay 时用它取 decorView。
     * 主线程访问（startDisplay/stopDisplay 均经 main.post）。
     */
    private var currentActivity: android.app.Activity? = null

    /** 由 LinBoxApp 启动时调用一次 */
    fun init(hostContainer: FrameLayout?) {
        instance = this
        if (!DacNative.available) {
            Log.w(TAG, "liblinbox_dac_bridge.so 不可用，DAC 显示禁用")
            return
        }
        DacNative.nativeSetTitleSink(this)
        if (hostContainer != null) {
            // 宿主容器来自 LinBoxShell 浮动窗口系统（可选接线）
            rootView = hostContainer
        } else {
            // 兜底模式：捕获前台 Activity 供 ensureContainer 挂 decorView
            // （ActivityLifecycleCallbacks 为 Java 接口，7 个抽象方法须全部实现）
            (appContext as? android.app.Application)
                ?.registerActivityLifecycleCallbacks(
                    object : android.app.Application.ActivityLifecycleCallbacks {
                        override fun onActivityCreated(
                            a: android.app.Activity,
                            savedInstanceState: android.os.Bundle?
                        ) {
                        }

                        override fun onActivityStarted(a: android.app.Activity) {}

                        override fun onActivityResumed(a: android.app.Activity) {
                            currentActivity = a
                            // 挂起的 DAC 启动请求（无 Activity 时收到广播）→ 现在可以拉起
                            pendingStart?.let { (w, h) ->
                                pendingStart = null
                                startDisplay(w, h)
                            }
                        }

                        override fun onActivityPaused(a: android.app.Activity) {
                            if (currentActivity === a) currentActivity = null
                        }

                        override fun onActivityStopped(a: android.app.Activity) {}

                        override fun onActivitySaveInstanceState(
                            a: android.app.Activity,
                            outState: android.os.Bundle
                        ) {
                        }

                        override fun onActivityDestroyed(a: android.app.Activity) {
                            if (currentActivity === a) currentActivity = null
                        }
                    }
                )
        }
        Log.i(TAG, "DacApp initialized")
    }

    /**
     * 打开 DAC 显示窗口（命令行 am broadcast 或壳层按钮触发）
     */
    fun startDisplay(width: Int, height: Int) {
        if (isRunning) {
            // 已在运行：仅同步尺寸
            if (width != desktopW || height != desktopH) {
                desktopW = width
                desktopH = height
                dacView?.setDesktopSize(width, height)
            }
            return
        }
        desktopW = width
        desktopH = height

        main.post {
            try {
                val container = ensureContainer()
                pendingStart = null
                dacView?.release()
                dacView = DacView(container).apply {
                    setDesktopSize(width, height)
                    postWhenSurfaceReady {
                        val path = socketPath()
                        backend = try {
                            connect(path)
                        } catch (e: Exception) {
                            Log.e(TAG, "connect failed", e)
                            DacNative.BACKEND_NONE
                        }
                        isRunning = backend != DacNative.BACKEND_NONE
                        if (isRunning) {
                            Log.i(TAG, "DAC display started ${width}x${height} backend=$backend")
                            showTitle(DacNative.backendName(backend))
                            notifyReady()
                        } else {
                            showTitle("DAC 连接失败（等待 wine ...）")
                            // 后台连接重试：wine 侧稍后启动时仍可连上
                            notifyReady()
                        }
                    }
                }
            } catch (e: Exception) {
                pendingStart = width to height
                Log.w(TAG, "DAC 兕底容器暂不可用（等 Activity 恢复后自动拉起）：${e.message}")
            }
        }
    }

    fun stopDisplay() {
        main.post {
            pendingStart = null
            dacView?.release()
            dacView = null
            isRunning = false
            backend = DacNative.BACKEND_NONE
            Log.i(TAG, "DAC display stopped")
        }
    }

    /* 输入接线说明：
     *  - 触摸鼠标：DacView 自带（无需接线）
     *  - 键盘：DacView OnKeyListener 自带；LinBox 虚拟键盘条按键经
     *    LinBoxShell 注入 DAC 窗口焦点后自动路由（KeyEvent 直通）
     *  - 手柄：GamepadController 的键盘/鼠标动作映射回调 → DacInput
     */

    // ---------- 内部 ----------

    private fun ensureContainer(): FrameLayout {
        rootView?.let { return it }
        // 兜底：挂到前台 Activity 的 decorView（未接入 LinBoxShell 时）
        val activity = currentActivity
            ?: error("DAC 兜底容器需要前台 Activity（无 Activity 生命周期回调时请接 LinBoxShell 宿主容器）")
        val container = FrameLayout(activity)
        val decor = activity.window.decorView as? FrameLayout
            ?: error("decorView 非 FrameLayout，无法挂载 DAC 覆盖层")
        decor.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rootView = container
        return container
    }

    /**
     * TitleSink 实现：wine 侧窗口标题 → DAC 浮窗标题。
     * JNI 回调线程到达，统一切主线程更新 UI，并转发给 DacInput.titleListener
     * 二级监听（供壳层镜像窗口标题等外部接线，无监听时为空操作）。
     */
    override fun onTitle(title: String) {
        Log.d(TAG, "wine title: $title")
        main.post { titleView?.text = title }
        DacInput.titleListener?.invoke(title)
    }

    private fun showTitle(text: String) {
        if (titleView?.parent == null && rootView != null) {
            titleView = TextView(appContext).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(16, 8, 16, 8)
            }
            rootView?.addView(
                titleView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                )
            )
        }
        titleView?.text = text
    }

    fun socketPath(): String {
        // 与终端侧 $PREFIX 一致：/data/data/com.linbox/files/usr/tmp
        val tmp = File("/data/data/${appContext?.packageName ?: "com.linbox"}/files/usr/tmp")
        if (!tmp.exists()) tmp.mkdirs()
        return File(tmp, "linbox-dac.sock").absolutePath
    }

    private fun notifyReady() {
        val ctx = appContext ?: return
        val intent = android.content.Intent(DacReceiver.ACTION_DAC_READY)
            .setPackage(ctx.packageName)
            .putExtra(DacReceiver.EXTRA_READY, isRunning)
            .putExtra(DacReceiver.EXTRA_BACKEND, backend)
        ctx.sendBroadcast(intent)
    }

    /** 应用上下文（由 LinBoxApp 注入） */
    @Volatile
    var appContext: android.content.Context? = null

    fun shutdown() {
        stopDisplay()
        DacNative.nativeSetTitleSink(null)
    }
}
