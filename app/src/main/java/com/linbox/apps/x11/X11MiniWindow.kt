package com.linbox.apps.x11

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linbox.LinBoxApp
import com.linbox.core.shell.ShellController
import com.termux.x11.LoriePreferences
import com.termux.x11.LorieView
import com.termux.x11.X11InputHub

/**
 * X11MiniWindow —— v6.4：X11 小窗口模式（AnWind 式浮窗，用户：
 * "x11小窗口开启时回主页后悬浮球里就丢失了x11的设置了……而且x11
 * 小窗口化后不能操作后面任何东西，修复它"）。
 *
 * ## 形态
 * 前台 Activity 的 decorView 上挂一块**只占自身边界**的迷你悬浮窗
 * （DacApp v1.21 同款宿主方案，无需悬浮窗权限）：
 * - 标题条（状态 + ⚙ 快捷配置 + ▣ 恢复全屏 + ✕ 关闭会话）；
 * - 画面区：独立 LorieView，经 X11WindowController.connectLorieView
 *   重新取连接 fd 续上渲染（与全屏页重建同一条已验证链路）；
 * - **不遮挡后面界面的操作**：浮窗 View 只覆盖自身像素范围，
 *   边界外的触摸直达下层页面（旧实现全屏捕获层吞掉一切触摸的根治）；
 * - 标题条拖动自由移动；右/下/右下边缘手柄拖动自由缩放（像素级）；
 * - 开窗尺寸按当前 X 屏幕分辨率（.linbox-x11-screen）取长宽比，
 *   宽约 55% 屏宽（4:3 游戏呈 4:3 窗，16:9 呈 16:9 窗）。
 *
 * ## 生命周期
 * - [enable]：悬浮球「小窗口」触发 —— 置 miniMode、建浮窗、回主页；
 *   全屏页 onDispose（X11Surface 小窗分支）断开渲染后 [connect] 重连；
 * - [restore]：▣ / 悬浮球「恢复全屏」—— 先拆浮窗 + detachView(false)，
 *   再 showX11()，全屏页 factory 全新连接（无竞态）；
 * - [close]：✕ —— 与旧版"关闭 X11 窗口"语义一致：终止 wine 会话；
 * - 会话死亡（binder 掉线）时浮窗自动拆除（状态轮询）。
 *
 * ## 输入
 * 迷你 LorieView 自带 SmartTouchBridge（触摸方式/触控板参数即时生效），
 * X11InputHub 的活跃视图指向迷你视图 —— 虚拟手柄 / 悬浮球快捷动作 /
 * 常驻通知入口照常直注 X。手柄覆盖层由 LinBoxShell 全局层渲染
 * （非 X11 页自动启用，经 X11InputHub 转发到迷你视图）。
 */
object X11MiniWindow {

    private const val TAG = "X11MiniWindow"

    /** 状态轮询间隔（ms）：会话死亡自动拆窗 + 标题刷新。 */
    private const val POLL_MS = 1200L

    private val main = Handler(Looper.getMainLooper())

    /** 迷你悬浮窗根视图（decorView 子节点；null = 未开启）。 */
    private var root: FrameLayout? = null
    private var statusText: TextView? = null
    private var lorie: LorieView? = null
    private var bridge: SmartTouchBridge? = null
    private var hostActivity: Activity? = null

    /** 连接重试已发起（防 onDispose/手动重复触发）。 */
    private var connectStarted = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (root == null) return
            val st = X11WindowController.state.value
            statusText?.text = if (st == X11WindowController.State.Connected)
                "X11 · 运行中" else "X11 · 连接中…"
            // 会话死亡（binder 掉线）且未连上 → 自动拆窗（X11 组随
            // sessionActive 同步熄灭，状态轮询兜底 KeepAliveService）
            if (!X11WindowController.isSessionAlive() &&
                st != X11WindowController.State.Connected
            ) {
                Log.i(TAG, "会话已结束，迷你浮窗自动关闭")
                teardown(endedBySession = true)
                X11WindowController.markSessionEnded()
                return
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /** 是否处于小窗模式（浮窗在位；非 Compose 调用方用）。 */
    val isActive: Boolean
        get() = root != null

    /** v6.4：Compose 可观察的活跃标志（悬浮球芯片文案/开关用它重组）。 */
    var active by mutableStateOf(false)
        private set

    /** 是否可进入小窗（有前台 Activity + X 会话存活）。 */
    fun canEnable(): Boolean =
        !isActive && X11WindowController.hasSession() && findActivity() != null

    // ------------------------------------------------------------------
    // 对外入口（全部主线程调用）
    // ------------------------------------------------------------------

    /**
     * 全屏页 → 小窗：悬浮球「小窗口」芯片触发。
     * 建浮窗（画面连接由全屏页 onDispose 的小窗分支发起，见 [connect]），
     * 随后回主页 —— wine 会话全程存活，悬浮球 X11 快捷组持续可见。
     */
    fun enable() {
        if (isActive) return
        val activity = findActivity()
        if (activity == null || !X11WindowController.hasSession()) {
            Log.w(TAG, "无法进入小窗模式（无前台 Activity 或无 X 会话）")
            return
        }
        hostActivity = activity
        connectStarted = false
        X11WindowController.miniMode = true
        if (!buildOverlay(activity)) {
            X11WindowController.miniMode = false
            return
        }
        main.removeCallbacks(pollRunnable)
        main.postDelayed(pollRunnable, POLL_MS)
        ShellController.showHome()
        // 注：keep-screen-on 不在此处加 —— 全屏页 onDispose 会清标志，
        // 改在 connect()（页面已离场）延迟补挂。
        Log.i(TAG, "X11 小窗口模式开启（wine 会话保持运行）")
    }

    /**
     * 小窗 → 全屏页：▣ / 悬浮球「恢复全屏」。
     * 先拆浮窗 + detachView(false)（断渲染、不抑制会话），再跳全屏页
     * 全新连接 —— 与"重新进入 X11 页"同链路，零竞态。
     */
    fun restore() {
        if (!isActive) {
            X11WindowController.miniMode = false
            ShellController.showX11()
            return
        }
        stopKeepScreenOn()
        main.removeCallbacks(pollRunnable)
        teardown(endedBySession = false)
        X11WindowController.detachView(userClosed = false)
        ShellController.showX11()
        Log.i(TAG, "X11 小窗口 → 恢复全屏")
    }

    /**
     * 关闭会话（✕）：与旧版退出 X11 全屏页同语义 —— 终止 wine。
     */
    fun close() {
        if (!isActive) return
        stopKeepScreenOn()
        main.removeCallbacks(pollRunnable)
        teardown(endedBySession = false)
        X11FitClient.stop()
        X11Session.killWine()
        X11WindowController.detachView(userClosed = true)
        X11WindowController.markSessionEnded()
        Log.i(TAG, "X11 小窗口关闭（wine 会话已终止）")
    }

    /**
     * 全屏页 factory 进入时调用：浮窗退场，画面交还全屏页（会话不断）。
     * 必须在全屏页 connectLorieView 之前调用 —— 先拆视图、释放连接，
     * 全屏页 factory 随后重建整条输入/渲染链。
     */
    fun dismissToPage() {
        if (!isActive) return
        stopKeepScreenOn()
        main.removeCallbacks(pollRunnable)
        teardown(endedBySession = false)
        // 页面 factory 将重建连接链；先释放当前渲染供页面全新接管
        X11WindowController.detachView(userClosed = false)
        Log.i(TAG, "迷你浮窗退场（画面交还全屏页）")
    }

    /**
     * 全屏页 onDispose（小窗分支）回调：页面已断开渲染连接，
     * 迷你浮窗接管 —— 重新取 fd 建立连接。
     * 延迟一短拍：等 decorView 完成首次布局（SurfaceView 拿到真实尺寸），
     * 避免 0x0 surface 帧触发无效 sendWindowChange。
     */
    fun connect() {
        if (!isActive || connectStarted) return
        connectStarted = true
        val lv = lorie ?: return
        main.postDelayed({
            if (isActive && lorie === lv) {
                X11WindowController.connectLorieView(lv)
                // 全屏页已离场（其 onDispose 不再清标志）→ 按偏好补挂亮屏
                findActivity()?.let { startKeepScreenOn(it) }
            }
        }, 140)
    }

    // ------------------------------------------------------------------
    // 浮窗搭建（纯 View 体系 —— 零 Compose 依赖，不参与主树生命周期）
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlay(activity: Activity): Boolean {
        val ctx: Context = activity
        val decor = activity.window.decorView as? FrameLayout ?: run {
            Log.w(TAG, "decorView 非 FrameLayout，无法挂载迷你浮窗")
            return false
        }
        val dm = ctx.resources.displayMetrics
        val density = dm.density

        // ---- 开窗尺寸：按当前 X 屏幕分辨率取长宽比（用户："根据分辨率
        //      调整窗口大小"）。4:3 → 4:3 窗；拿不到退 16:9。
        var aspect = 16f / 9f
        X11ResolutionLink.readScreenSize()?.let { s ->
            val m = Regex("^(\\d+)x(\\d+)$").find(s)
            if (m != null) {
                val w = m.groupValues[1].toIntOrNull() ?: 0
                val h = m.groupValues[2].toIntOrNull() ?: 0
                if (w >= 64 && h >= 64) aspect = w.toFloat() / h.toFloat()
            }
        }
        var wPx = (dm.widthPixels * 0.55f).toInt()
            .coerceIn((240 * density).toInt(), (dm.widthPixels * 0.94f).toInt())
        var hPx = (wPx / aspect).toInt()
        val maxH = (dm.heightPixels * 0.58f).toInt()
        if (hPx > maxH) { hPx = maxH; wPx = (hPx * aspect).toInt() }
        val barH = (34 * density).toInt()

        // ---- 画面容器 ----
        val cont = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

        // ---- 迷你 LorieView + 输入接线（对齐全屏页 factory 同款链路） ----
        val lv = LorieView(ctx)
        cont.addView(
            lv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.FILL
            )
        )
        val b = SmartTouchBridge(ctx, lv)
        LoriePreferences.prefs?.let { p ->
            b.setPreferScancodes(p.preferScancodes.get())
            b.reloadTouchPrefs(p)
        }
        SmartTouchBridge.activeBridge = b
        lv.setCallback { surfaceW, surfaceH, screenW, screenH ->
            b.updateTransform(surfaceW, surfaceH, screenW, screenH)
            X11FitClient.screenW = screenW
            X11FitClient.screenH = screenH
            X11ResolutionLink.writeScreenSize(screenW, screenH)
            try {
                lv.screenInfo.handleHostSizeChanged(surfaceW, surfaceH)
                lv.screenInfo.handleClientSizeChanged(screenW, screenH)
            } catch (_: Exception) {}
            val display = lv.display
            val framerate = (display?.refreshRate ?: 60f).toInt()
            val name = if (display == null || display.displayId == android.view.Display.DEFAULT_DISPLAY)
                "Builtin Display" else "External Display"
            LorieView.sendWindowChange(screenW, screenH, framerate, name)
        }
        lv.setOnTouchListener { _, e -> b.onTouch(e) }
        lv.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) false
            else b.sendKey(event) ?: false
        }
        X11ResolutionLink.attachView(lv)
        X11InputHub.get(ctx).setActiveLorieView(lv)

        // ---- 标题条 ----
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color(0xCC101828))
            gravity = Gravity.CENTER_VERTICAL
        }
        fun barBtn(label: String): TextView = TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        }
        val status = TextView(ctx).apply {
            text = "X11 · 连接中…"
            setTextColor(Color(0xFFEAF2FF))
            textSize = 12f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((10 * density).toInt(), 0, (4 * density).toInt(), 0)
        }
        val btnCfg = barBtn("⚙")
        val btnFull = barBtn("▣")
        val btnClose = barBtn("✕").apply { setTextColor(Color(0xFFFFB0A8)) }
        bar.addView(status, LinearLayout.LayoutParams(0, barH, 1f))
        bar.addView(btnCfg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, barH))
        bar.addView(btnFull, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, barH))
        bar.addView(btnClose, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, barH))

        // ---- 根容器（圆角玻璃底） ----
        val r = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color(0x26FFFFFF))
                cornerRadius = 10 * density
                setStroke(Math.max(1, (1 * density).toInt()), Color(0x40FFFFFF))
            }
        }
        r.addView(
            cont,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = barH }
        )
        r.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, barH
            )
        )

        // ---- 边缘缩放手柄（右 / 下 / 右下角）：像素级自由缩放 ----
        val grab = (22 * density).toInt()
        val minW = (180 * density).toInt()
        val minH = (120 * density).toInt()

        fun resizeHandle(gravity: Int) {
            val hv = View(ctx)
            val lp = FrameLayout.LayoutParams(
                if (gravity and Gravity.RIGHT != 0) grab else FrameLayout.LayoutParams.MATCH_PARENT,
                if (gravity and Gravity.BOTTOM != 0) grab else FrameLayout.LayoutParams.MATCH_PARENT,
                gravity
            )
            var downX = 0f
            var downY = 0f
            var baseW = 0
            var baseH = 0
            hv.setOnTouchListener { _, e ->
                val rlp = r.layoutParams as? FrameLayout.LayoutParams
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        baseW = rlp?.width ?: wPx
                        baseH = rlp?.height ?: (hPx + barH)
                        downX = e.rawX
                        downY = e.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (rlp != null) {
                            if (gravity and Gravity.RIGHT != 0)
                                rlp.width = (baseW + (e.rawX - downX).toInt())
                                    .coerceIn(minW, dm.widthPixels)
                            if (gravity and Gravity.BOTTOM != 0)
                                rlp.height = (baseH + (e.rawY - downY).toInt())
                                    .coerceIn(minH + barH, dm.heightPixels)
                            r.layoutParams = rlp
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (rlp != null) { wPx = rlp.width; hPx = rlp.height - barH }
                        true
                    }
                    else -> false
                }
            }
            r.addView(hv, lp)
        }
        resizeHandle(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        resizeHandle(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
        resizeHandle(Gravity.RIGHT or Gravity.BOTTOM)

        // ---- 标题条拖动移动（整窗；按钮区子视图自行消费，不冲突） ----
        var dragDownX = 0f
        var dragDownY = 0f
        var baseL = 0
        var baseT = 0
        bar.setOnTouchListener { _, e ->
            val rlp = r.layoutParams as? FrameLayout.LayoutParams
                ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    baseL = rlp.leftMargin
                    baseT = rlp.topMargin
                    dragDownX = e.rawX
                    dragDownY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    rlp.leftMargin = (baseL + (e.rawX - dragDownX).toInt())
                        .coerceIn(0, (dm.widthPixels - rlp.width).coerceAtLeast(0))
                    rlp.topMargin = (baseT + (e.rawY - dragDownY).toInt())
                        .coerceIn(0, (dm.heightPixels - rlp.height).coerceAtLeast(0))
                    r.layoutParams = rlp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        // ---- 按钮 ----
        btnCfg.setOnClickListener { showQuickConfig(ctx, btnCfg, density) }
        btnFull.setOnClickListener { restore() }
        btnClose.setOnClickListener { close() }

        val lp = FrameLayout.LayoutParams(wPx, hPx + barH)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = (dm.widthPixels - wPx - (12 * density).toInt()).coerceAtLeast(0)
        lp.topMargin = (64 * density).toInt()
        decor.addView(r, lp)

        root = r
        statusText = status
        lorie = lv
        bridge = b
        active = true
        Log.i(TAG, "迷你浮窗已挂载 ${wPx}x${hPx}+bar$barH")
        return true
    }

    /**
     * ⚙ 快捷配置（用户："带快捷配置"）：PopupWindow 小面板 ——
     * 触摸方式循环 / 附加键盘栏 / 保持亮屏 / 分辨率模式循环 / 拉伸铺满。
     * 全部即时生效（SmartTouchBridge.applyTouchPrefs / pokeActiveView）。
     */
    private fun showQuickConfig(ctx: Context, anchor: View, density: Float) {
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color(0xE6141C2C))
                cornerRadius = 12 * density
                setStroke(Math.max(1, (1 * density).toInt()), Color(0x3DFFFFFF))
            }
            setPadding(
                (6 * density).toInt(), (6 * density).toInt(),
                (6 * density).toInt(), (6 * density).toInt()
            )
        }

        fun item(label: () -> String, value: () -> String, onClick: () -> Unit): View =
            TextView(ctx).apply {
                text = "${label()}　${value()}"
                setTextColor(Color(0xFFEAF2FF))
                textSize = 13f
                setPadding(
                    (12 * density).toInt(), (10 * density).toInt(),
                    (12 * density).toInt(), (10 * density).toInt()
                )
                setOnClickListener {
                    onClick()
                    // 立即刷新本条显示并保留面板（可连续调多项）
                    text = "${label()}　${value()}"
                }
            }

        val prefs = LoriePreferences.prefs
        panel.addView(item(
            { "触摸方式" },
            {
                when (prefs?.touchMode?.get()?.toIntOrNull() ?: 2) {
                    1 -> "触控板"; 3 -> "直接触摸"; else -> "模拟触摸屏"
                }
            },
            {
                val next = when (prefs?.touchMode?.get()?.toIntOrNull() ?: 2) {
                    1 -> 3; 3 -> 2; else -> 1
                }
                prefs?.touchMode?.put(next.toString())
                SmartTouchBridge.applyTouchPrefs()
            }
        ))
        panel.addView(item(
            { "附加键盘栏" },
            { if (X11UiPrefs.showAdditionalKbd) "开" else "关" },
            {
                val v = !X11UiPrefs.showAdditionalKbd
                X11UiPrefs.showAdditionalKbd = v
                prefs?.showAdditionalKbd?.put(v)
            }
        ))
        panel.addView(item(
            { "保持亮屏" },
            { if (X11UiPrefs.keepScreenOn) "开" else "关" },
            {
                val v = !X11UiPrefs.keepScreenOn
                X11UiPrefs.keepScreenOn = v
                prefs?.keepScreenOn?.put(v)
                hostActivity?.window?.let { w ->
                    if (v) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        ))
        panel.addView(item(
            { "分辨率" },
            {
                when (X11ResolutionLink.state.value.mode) {
                    "native" -> "跟随窗口"; "scaled" -> "缩放"; else -> "固定"
                }
            },
            {
                // native → scaled → exact 循环（写偏好 + poke 迷你视图即时生效）
                when (X11ResolutionLink.state.value.mode) {
                    "native" -> prefs?.displayResolutionMode?.put("scaled")
                    "scaled" -> prefs?.displayResolutionMode?.put("exact")
                    else -> prefs?.displayResolutionMode?.put("native")
                }
                X11ResolutionLink.pokeActiveView()
            }
        ))
        panel.addView(item(
            { "拉伸铺满" },
            { if (prefs?.displayStretch?.get() == true) "开" else "关" },
            {
                val v = !(prefs?.displayStretch?.get() ?: false)
                prefs?.displayStretch?.put(v)
                X11ResolutionLink.pokeActiveView()
            }
        ))

        val pw = PopupWindow(
            panel,
            (230 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            true
        )
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        try {
            pw.showAsDropDown(anchor, -(190 * density).toInt(), (6 * density).toInt())
        } catch (e: Exception) {
            Log.w(TAG, "快捷配置面板弹出失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 拆除 / 保活 / 工具
    // ------------------------------------------------------------------

    private fun teardown(endedBySession: Boolean) {
        val activity = hostActivity
        val lv = lorie
        val b = bridge
        // 先拆视图（decorView 移除 → SurfaceView 销毁）
        root?.let { (it.parent as? FrameLayout)?.removeView(it) }
        root = null
        statusText = null
        lorie = null
        hostActivity = null
        active = false
        // 桥收尾：释放悬挂手势；全局活跃位仅在"没有别的宿主接管"时清空
        //（restore 路径随后全屏页 factory 会重建并重新注册）
        b?.onDetached()
        if (SmartTouchBridge.activeBridge === b) SmartTouchBridge.activeBridge = null
        // 手柄 → X11 转发目标注销（内部对仍按着的键补发 UP，防卡键）
        if (activity != null) X11InputHub.get(activity).setActiveLorieView(null)
        else if (lv != null) runCatching { X11InputHub.get(LinBoxApp.get()).setActiveLorieView(null) }
        connectStarted = false
        if (!endedBySession) X11WindowController.miniMode = false
    }

    private fun startKeepScreenOn(activity: Activity) {
        if (X11UiPrefs.keepScreenOn)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopKeepScreenOn() {
        hostActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 前台 Activity（LinBoxApp 生命周期回调跟踪；浮窗挂它的 decorView）。 */
    private fun findActivity(): Activity? {
        val act = hostActivity
        if (act != null && !act.isFinishing) return act
        val top = LinBoxApp.topActivity
        return if (top != null && !top.isFinishing) top else null
    }
}
