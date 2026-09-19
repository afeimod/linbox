package com.linbox.apps.dac

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * DacApp — DAC 显示生命周期管理（应用单例）
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 职责：
 *  1. 开关 DAC 显示（复用 LinBox 页面体系：DacScreen 页内全屏；
 *     v1.21 起支持最小化 —— decorView 迷你悬浮窗，回终端不断流）
 *  2. 驱动 DacView 连接 JNI bridge
 *  3. 桥接 LinBox 输入系统（DacInput）
 *  4. socket 路径管理（$PREFIX/tmp/linbox-dac.sock）
 *
 * v1.21 宿主形态（hostMode）：
 *  - PAGE   画面在 DacScreen 页面容器内（全屏，右上角 ─/✕）；
 *  - MINI   画面在 decorView 迷你悬浮窗内（回终端运行 wine，小窗不断流；
 *           reparent 触发 Surface 重建 → DacView.surfaceChanged →
 *           nativeSetSurface 无缝重绑，socket 连接与 buffer 全保留）；
 *  - DETACHED 无宿主（已停止）。
 *
 * 任意启动顺序均可用（wine 侧 v1.21 重连看门狗每 2 秒自动重试）：
 *  - 先点「DAC 显示器」再跑 wine（经典两步）；
 *  - 先跑 wine（wrapper DAC_START 自动拉起）→ 终端上方直接出迷你窗，
 *    不再强制跳页打断输入；点小窗标题条/▣ 还原全屏。
 */
object DacApp : DacNative.TitleSink {

    private const val TAG = "LinBoxDAC"

    var instance: DacApp? = null
        private set

    /** 宿主形态（v1.21） */
    private enum class HostMode { DETACHED, PAGE, MINI }
    private var hostMode = HostMode.DETACHED

    private var rootView: FrameLayout? = null   // 页面宿主容器（DacScreen 提供）
    private var dacView: DacView? = null
    private var titleView: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    // 迷你悬浮窗（decorView 上，最小化时承载 DacView）
    private var miniRoot: FrameLayout? = null
    private var miniContent: FrameLayout? = null
    private var miniStatus: TextView? = null

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

    /** v1.21：是否处于最小化悬浮窗模式（画面仍在运行） */
    val isMinimized: Boolean
        get() = hostMode == HostMode.MINI

    /**
     * DAC 显示统一入口（v1.21 路由增强）：DacReceiver 广播与悬浮球按钮共用。
     * - 已有画面（页面或迷你窗）→ 复用并同步尺寸（页面时顺带迁回）；
     * - 终端页触发（wrapper DAC_START 自动拉起）→ 迷你悬浮窗，
     *   不再强制跳页打断输入（v1.21）；
     * - 其余（如 X11 页）→ 跳转全屏「DAC 显示器」页面。
     */
    fun requestStart(width: Int, height: Int) {
        requestedSize = width to height
        if (dacView != null || rootView != null) {
            startDisplay(width, height)
        } else if (com.linbox.core.shell.ShellController.screen ==
            com.linbox.core.shell.ShellController.Screen.TERMINAL
        ) {
            startMini(width, height)
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
            // v1.19：带真实失败原因（dlopen 原文 + 设备 API），不再只有一句话
            Log.w(TAG, "liblinbox_dac_bridge.so 不可用，DAC 显示禁用 " +
                "(api=${DacNative.deviceApi}, err=${DacNative.loadError})")
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
                            // v1.21：经 requestStart 路由（终端页 → 迷你悬浮窗）
                            pendingStart?.let { (w, h) ->
                                pendingStart = null
                                requestStart(w, h)
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
     * 打开 DAC 显示窗口（页面容器路径；命令行 am broadcast 或壳层按钮触发）。
     * v1.21：已有画面时同步尺寸；画面在迷你悬浮窗则迁回页面（还原语义）。
     */
    fun startDisplay(width: Int, height: Int) {
        desktopW = width
        desktopH = height

        main.post {
            if (dacView != null) {
                // 已在运行：同步尺寸；最小化中且页面已挂载 → 迁回页面
                dacView?.setDesktopSize(width, height)
                if (hostMode == HostMode.MINI && rootView != null) {
                    dacView?.attachTo(rootView!!)
                    teardownMiniOverlay()
                    hostMode = HostMode.PAGE
                    showTitle(if (isRunning) DacNative.backendName(backend) else "DAC 等待 wine ...")
                    Log.i(TAG, "DAC restored to page ${width}x${height}")
                }
                return@post
            }
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
                hostMode = HostMode.PAGE
            } catch (e: Exception) {
                pendingStart = width to height
                Log.w(TAG, "DAC 兕底容器暂不可用（等 Activity 恢复后自动拉起）：${e.message}")
            }
        }
    }

    /**
     * v1.21：迷你悬浮窗启动 —— 终端上方小窗显示，不打断终端输入。
     * wrapper 的 DAC_START 自动拉起走此路径；画面逻辑与 startDisplay 一致
     * （socket 监听立即就绪，wine 何时启动都能连上）。
     */
    fun startMini(width: Int, height: Int) {
        desktopW = width
        desktopH = height

        main.post {
            if (dacView != null) {
                // 已有画面：确保在悬浮窗形态
                if (hostMode == HostMode.PAGE) minimize()
                return@post
            }
            val content = ensureMiniOverlay() ?: run {
                // 无前台 Activity（理论上不发生：终端页可见时 Activity 必在前台）
                com.linbox.core.shell.ShellController.showDac()
                return@post
            }
            pendingStart = null
            dacView = DacView(content).apply {
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
                        Log.i(TAG, "DAC mini started ${width}x${height} backend=$backend")
                        showTitle(DacNative.backendName(backend))
                        notifyReady()
                    } else {
                        showTitle("DAC 等待 wine ...")
                        notifyReady()
                    }
                }
            }
            hostMode = HostMode.MINI
            Log.i(TAG, "DAC mini overlay start requested ${width}x${height}")
        }
    }

    /**
     * v1.21：最小化 —— 画面迁到 decorView 迷你悬浮窗继续显示，回到终端。
     * 与 ✕（停止显示）不同：最小化保持 socket 监听与画面运行，wine 随时
     * 连上就能在小窗里看到画面；点小窗标题条或 [▣] 还原全屏。
     * 必须同步执行（reparent 先于页面销毁，onPageGone 才能识别 MINI 不停显）。
     */
    fun minimize() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { minimize() }
            return
        }
        val view = dacView
        if (view == null) {
            // 无画面（开屏失败/库不可用/已停止）：退回终端（旧返回键语义）
            com.linbox.core.shell.ShellController.showTerminal()
            return
        }
        if (hostMode == HostMode.MINI) {
            com.linbox.core.shell.ShellController.showTerminal()
            return
        }
        val content = ensureMiniOverlay() ?: run {
            // 悬浮窗建不出来（无 Activity）：退回旧语义 —— 停显回终端
            com.linbox.core.shell.ShellController.showTerminal()
            return
        }
        view.attachTo(content)
        hostMode = HostMode.MINI
        showTitle(if (isRunning) DacNative.backendName(backend) else "DAC 等待 wine ...")
        com.linbox.core.shell.ShellController.showTerminal()
        Log.i(TAG, "DAC minimized to floating overlay (display keeps running)")
    }

    /** v1.21：从迷你悬浮窗还原全屏页面（跳转 DacScreen，组合时自动迁回） */
    fun restore() {
        if (hostMode == HostMode.MINI) {
            com.linbox.core.shell.ShellController.showDac()
            // DacScreen 组合后 factory → attachHost + startDisplay → 迁回页面
        }
    }

    fun stopDisplay() {
        main.post {
            pendingStart = null
            teardownMiniOverlay()
            dacView?.release()
            dacView = null
            isRunning = false
            backend = DacNative.BACKEND_NONE
            hostMode = HostMode.DETACHED
            Log.i(TAG, "DAC display stopped")
        }
    }

    /**
     * v1.21：DacScreen 离开组合（最小化/返回/切页）时由 onDispose 调用。
     * 仅页面模式停显；最小化模式（画面在悬浮窗）继续运行 ——
     * 这正是 minimize() 必须同步置 MINI 的原因。
     */
    fun onPageGone() {
        main.post {
            rootView = null
            if (hostMode == HostMode.PAGE) {
                dacView?.release()
                dacView = null
                isRunning = false
                backend = DacNative.BACKEND_NONE
                hostMode = HostMode.DETACHED
                Log.i(TAG, "DAC page closed, display stopped")
            }
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

    // ---------- 迷你悬浮窗（v1.21） ----------

    /**
     * 在前台 Activity 的 decorView 上建迷你悬浮窗（约 45% 屏宽、16:9）：
     * 顶部标题条（状态 + 点击还原）+ 画面容器。挂在应用自身窗口内，
     * 无需任何悬浮窗权限。已存在则直接复用。
     *
     * @return 画面容器（DacView 宿主）；拿不到 decorView 时返回 null
     */
    private fun ensureMiniOverlay(): FrameLayout? {
        miniContent?.let { return it }
        val activity = currentActivity ?: return null
        val decor = activity.window.decorView as? FrameLayout ?: return null
        val ctx = activity
        val dm = ctx.resources.displayMetrics
        val density = dm.density
        val wPx = (dm.widthPixels * 0.45f).toInt()
            .coerceIn((260 * density).toInt(), (620 * density).toInt())
        val hPx = wPx * 9 / 16
        val barH = (34 * density).toInt()
        val pad = (10 * density).toInt()

        val content = FrameLayout(ctx)
        content.setBackgroundColor(Color.BLACK)

        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(0xCC141414.toInt())
        bar.gravity = Gravity.CENTER_VERTICAL

        fun barButton(text: String): TextView = TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(pad, 0, pad, 0)
        }

        val status = TextView(ctx).apply {
            text = "DAC"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(pad, 0, pad, 0)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val btnRestore = barButton("▣")
        val btnClose = barButton("✕")
        bar.addView(status, LinearLayout.LayoutParams(0, barH, 1f))
        bar.addView(btnRestore, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, barH))
        bar.addView(btnClose, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, barH))

        val root = FrameLayout(ctx)
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = barH }
        )
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barH
            )
        )

        // 点标题条/▣ 还原全屏；✕ 停止显示
        val restoreIt = { restore() }
        bar.setOnClickListener(restoreIt)
        btnRestore.setOnClickListener(restoreIt)
        btnClose.setOnClickListener { stopDisplay() }

        val lp = FrameLayout.LayoutParams(wPx, hPx + barH)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = (56 * density).toInt()
        lp.rightMargin = (10 * density).toInt()
        decor.addView(root, lp)

        miniRoot = root
        miniContent = content
        miniStatus = status
        Log.i(TAG, "mini overlay attached ${wPx}x${hPx}+bar$barH")
        return content
    }

    private fun teardownMiniOverlay() {
        miniRoot?.let { root ->
            (root.parent as? FrameLayout)?.removeView(root)
        }
        miniRoot = null
        miniContent = null
        miniStatus = null
    }

    /**
     * TitleSink 实现：wine 侧窗口标题 → DAC 浮窗标题。
     * JNI 回调线程到达，统一切主线程更新 UI，并转发给 DacInput.titleListener
     * 二级监听（供壳层镜像窗口标题等外部接线，无监听时为空操作）。
     */
    override fun onTitle(title: String) {
        Log.d(TAG, "wine title: $title")
        main.post {
            titleView?.text = title
            miniStatus?.text = title
        }
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
        miniStatus?.text = text
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
