package com.linbox.apps.x11

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.x11.LoriePreferences
import com.termux.x11.LorieView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * v2.22.3 fix10：glibc-runner → X11 窗口 的"分辨率握手"桥。
 * v2.22.6 fix19 —— 新增 windowStretch 撑满请求标记（-F/--fitwin）。
 *
 * 背景（用户问题 1）：X 屏幕默认"跟随窗口"（native），X 屏幕尺寸 =
 * Android 浮动窗口的像素尺寸 —— 游戏全屏渲染出来只有窗口那么小
 * （截图里 DXVK Buffer size 552x415、画面缩在左上角）。
 * `glibc-runner -d1280x720` 的分辨率停留在终端侧，从未到达 X server。
 *
 * 协议：
 * - 终端侧 glibc-runner（wine 启动时）把目标分辨率写进
 *   `$PREFIX/tmp/.linbox-x11-res`：
 *     · `-d1280x720`            → 文件内容 "1280x720"
 *     · `-d`（全屏）            → 文件内容 "1280x720"（全屏默认桌面分辨率）
 *     · `-d… -F/--fitwin`       → 文件内容 "1280x720 fitwin"
 *       （请求撑满：游戏窗口被 resize 到 X 屏幕，现代游戏原生分辨率渲染）
 *     · 不带 -d（窗口模式）      → 文件内容 "native"（跟随窗口）
 *   linbox-x11（桌面会话启动）同样写 "native"，回到跟随窗口。
 *   fix19 起 -d 默认为"贴合拉伸"：游戏窗口只需平移到 (0,0)，X 屏幕
 *   由 X11FitClient 自适应缩成游戏客户区尺寸，显示层拉伸铺满 ——
 *   任意窗口化游戏（含 DirectDraw 固定分辨率老游戏）均无黑边。
 *   fix19 新增 -v/--vd（wine 虚拟桌面）：轩剑类游戏必须运行在
 *   explorer /desktop 虚拟桌面内才不报错 —— VD 窗口尺寸=握手分辨率，
 *   恰好铺满 X 屏幕，X11FitClient 零动作。
 * - 本桥用 FileObserver 监听该文件，解析后写入 X11 偏好
 *   （displayResolutionMode=exact/native + displayResolutionExact），
 *   再对活跃的 LorieView regenerate+requestLayout —— X server 收到
 *   sendWindowChange 后把 RandR 屏幕调成目标分辨率，wine 的虚拟桌面
 *   /全屏游戏即以真实分辨率渲染，Android 侧拉伸铺满显示。
 *
 * 优先级：glibc-runner 写入的值即时生效；用户仍可在 X11 窗口控制条
 * 手动切"跟随窗口/固定分辨率"（下次 glibc-runner -d 会再次接管）。
 */
object X11ResolutionLink {
    private const val TAG = "X11ResolutionLink"
    const val DEFAULT_RES = "1280x720"

    /**
     * fix18：当前 exact 分辨率是否来自 -d 握手/用户手动固定（而非 applyFit
     * 贴合产生）。fix19 起仅作会话来源记录（控制条显示/日志），不再决定
     * 铺满策略 —— 策略选择见 windowStretch。
     */
    @Volatile var exactFromRunner: Boolean = false

    /**
     * fix19：是否请求"撑满"（把游戏窗口主动 resize 到整个 X 屏幕）。
     * 默认 false —— 默认策略是"贴合"：X 屏幕缩成游戏客户区尺寸，
     * 显示层拉伸铺满（对任意窗口化游戏可靠无黑边）。
     * 撑满仅对"能跟随 WM_SIZE 重绘的现代游戏"有意义（原生分辨率渲染，
     * 比 Upscale 清晰）；老游戏（DirectDraw 固定分辨率）接受 resize 后
     * 仍在原分辨率区域绘制 → X 层看到"窗口==屏幕"假稳定、右/下黑边
     * （用户实测：轩剑类 -d1024x768 只画 868x652）。
     * 终端侧 glibc-runner -F/--fitwin 时握手值携带 "fitwin" 标记 → true；
     * native 值 → false。applyFit 不改动（贴合不升级为撑满）。
     */
    @Volatile var windowStretch: Boolean = false

    /** $PREFIX/tmp 目录（与终端侧约定，App 自身数据目录，无需存储权限） */
    private const val PREFIX = "/data/data/com.linbox/files/usr"
    private const val RES_DIR = "$PREFIX/tmp"
    private const val RES_FILE = "$RES_DIR/.linbox-x11-res"

    /** 当前生效的分辨率来源描述（供控制条显示）："1280x720（游戏）" / "跟随窗口"。 */
    data class ResolutionState(val mode: String, val exact: String, val fromGame: Boolean)

    private val _state = MutableStateFlow(ResolutionState("native", DEFAULT_RES, false))
    val state: StateFlow<ResolutionState> = _state

    private val mainHandler = Handler(Looper.getMainLooper())
    private var observer: FileObserver? = null
    /** 活跃的 LorieView（X11Surface factory 创建时注册）。 */
    @Volatile private var activeView: LorieView? = null
    /** 上一次 Toast 提示的握手值（去重，避免 linbox-x11/glibc-runner 交替写文件时刷屏）。 */
    private var lastToastKey: String? = null

    fun attachView(view: LorieView?) { activeView = view }

    /** 当前活跃的 LorieView（设置面板等外部消费者）。 */
    fun currentView(): LorieView? = activeView

    /**
     * v2.22.4 fix11c：把偏好变更（拉伸/剪贴板等）推给活跃视图 ——
     * reloadPreferences 同步剪贴板开关等运行时状态，regenerate+requestLayout
     * 让分辨率/拉伸设置立即重测重生效。
     */
    fun pokeActiveView() {
        mainHandler.post {
            val v = activeView ?: return@post
            try {
                LoriePreferences.prefs?.let { v.reloadPreferences(it) }
                v.regenerate()
                v.requestLayout()
            } catch (e: Exception) {
                Log.w(TAG, "pokeActiveView: 视图已分离", e)
            }
        }
    }

    /** app 启动时调用：开始监听终端侧写入。 */
    fun start(context: Context) {
        if (observer != null) return
        val dir = File(RES_DIR)
        if (!dir.exists()) dir.mkdirs()

        observer = object : FileObserver(RES_DIR, CLOSE_WRITE or MOVED_TO or CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path != ".linbox-x11-res") return
                if (event != CLOSE_WRITE && event != MOVED_TO) return
                applyFromFile()
            }
        }
        try {
            observer?.startWatching()
            Log.i(TAG, "开始监听终端分辨率协议: $RES_FILE")
        } catch (e: Exception) {
            Log.e(TAG, "FileObserver 启动失败", e)
        }
    }

    /** 读取协议文件并应用（X11 窗口连接建立时也调用一次，兜底时序问题）。 */
    fun applyFromFile() {
        val value = try { File(RES_FILE).readText().trim() } catch (e: Exception) { return }
        if (value.isEmpty()) return
        apply(value)
    }

    /**
     * 应用一个分辨率值："WxH" → exact；"native" → 跟随窗口；
     * "fullscreen" → 全屏默认 1280x720。
     * fix19：值可携带 "fitwin" 标记（如 "1024x768 fitwin"，glibc-runner
     * -F/--fitwin 产生）→ 同步置位 windowStretch；其余情况归 false。
     * 旧版 App 解析带标记值：regex 照样提取 WxH，行为不变（向后兼容）。
     */
    fun apply(valueRaw: String) {
        val value = valueRaw.trim().lowercase()
        val wantStretch = value.contains("fitwin")
        // fix28：解析出的握手宽高（native 为 0 = 未知，供缩屏下限跳过）
        var hsW = 0; var hsH = 0
        val (mode, exact, fromGame) = when {
            value == "native" -> Triple("native", "", false)
            value == "fullscreen" -> {
                // DEFAULT_RES = "1280x720"；握手登记用宽高与之保持一致
                hsW = 1280; hsH = 720
                Triple("exact", DEFAULT_RES, true)
            }
            else -> {
                val m = Regex("(\\d{2,5})\\s*x\\s*(\\d{2,5})").find(value)
                    ?: run { Log.w(TAG, "无法解析分辨率: '$valueRaw'"); return }
                val w = m.groupValues[1].toInt()
                val h = m.groupValues[2].toInt()
                if (w < 160 || h < 120 || w > 7680 || h > 4320) {
                    Log.w(TAG, "分辨率越界: ${w}x${h}")
                    return
                }
                hsW = w; hsH = h
                Triple("exact", "${w}x${h}", true)
            }
        }

        // fix18：登记会话策略标记（native → false，exact（含手动）→ true）。
        // 置于 prefs 判空之前：即便 LorieView 偏好尚未就绪，-d 握手语义也
        // 先行登记，待偏好就绪重放 apply() 时保持一致。
        exactFromRunner = mode == "exact"
        // fix19：撑满请求（-F/--fitwin）仅对 exact 会话有意义；native 归 false。
        windowStretch = mode == "exact" && wantStretch
        // fix28：新握手 = 新自适应会话 —— X11FitClient 的贴合缩屏预算/
        // 对抗计数全部清零，并以握手分辨率作为缩屏下限基准。
        // glibc-runner -d/-f 每次启动都重写握手文件 → 每局游戏都拿到
        // 全新预算；不修改 applyFit（贴合应用本身不重置，防自环）。
        X11FitClient.resetSession(hsW, hsH)

        val prefs = LoriePreferences.prefs ?: return
        prefs.displayResolutionMode.put(mode)
        // v2.22.3 fix11b：固定分辨率时开启"拉伸铺满"——X 屏幕保持 -d 指定
        // 分辨率（游戏拿到真实全屏渲染分辨率），显示层把画面拉伸铺满浮动
        // 窗口（LorieView stretch 模式：surface=窗口尺寸 + X 屏幕独立缩放
        // 渲染），彻底消除信箱黑边。
        // v2.22.5 fix13：跟随窗口（native）模式同样开启 stretch —— X 屏幕初
        // 始时=窗口尺寸（1:1 无变形），但游戏全屏时 wine 会经 RandR 把 X 屏
        // 幕切成游戏真实分辨率（libXlorie 支持 ProcRRSetScreenConfig），此时
        // X 屏幕≠窗口，stretch 让游戏画面拉伸铺满整个窗口（用户需求"跟随窗
        // 口也支持拉伸全屏"），不再出现居中小窗+四周黑边。
        prefs.displayStretch.put(true)
        if (mode == "exact") prefs.displayResolutionExact.put(exact)

        _state.value = ResolutionState(mode, exact, fromGame)
        Log.i(TAG, "X 屏幕分辨率 → $mode ${if (mode == "exact") exact else ""}" +
            (if (windowStretch) "（撑满模式）" else "（贴合拉伸）") +
            "（来自 ${if (fromGame) "glibc-runner" else "桌面会话"}）")

        // v2.22.4 fix11c：游戏握手即时反馈 —— 用户在终端执行 glibc-runner -d
        // 后能立刻看到 X11 是否接收到分辨率（之前握手失败只有黑边一个症状，
        // 无从判断是终端侧没写还是 App 侧没应用）。
        val toastKey = "$mode:$exact:$fromGame"
        if (fromGame && toastKey != lastToastKey && activeView != null) {
            lastToastKey = toastKey
            mainHandler.post {
                val ctx = activeView?.context?.applicationContext ?: return@post
                try {
                    android.widget.Toast.makeText(
                        ctx,
                        if (mode == "exact") "X11 分辨率 → $exact（glibc-runner 握手成功）"
                        else "X11 分辨率 → 跟随窗口",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) {}
            }
        }

        mainHandler.post {
            activeView?.let { v ->
                try {
                    v.regenerate()
                    v.requestLayout()
                } catch (e: Exception) {
                    Log.w(TAG, "应用分辨率时视图已分离", e)
                }
            }
        }
    }

    /** 供控制条手动切换回"跟随窗口"。 */
    fun setNative() = apply("native")

    /** 供控制条手动设置固定分辨率。 */
    fun setExact(w: Int, h: Int) = apply("${w}x${h}")

    /**
     * v2.25：手动切换"缩放"模式（LoriePreferences 原生 scaled 档）——
     * X 屏幕 = Android 窗口 × 100/scale：scale >100 时 X 分辨率低于窗口
     * （界面元素放大，适合手机小屏触控），<100 时高于窗口（元素缩小，
     * 桌面内容更多）。与 native/exact 一样经由 pokeActiveView 即时生效；
     * 下次 glibc-runner 握手（-d/native）仍会按协议接管。
     */
    fun setScale(percent: Int) {
        val prefs = LoriePreferences.prefs ?: return
        val p = percent.coerceIn(50, 200)
        prefs.displayResolutionMode.put("scaled")
        // IntPreference 无 put(Int)（上游仅提供 get），经 PrefsProto.putInt
        // 写入 —— 与 IntPreference.get() 读取同一 SharedPreferences，链路一致。
        prefs.putInt("displayScale", p)
        exactFromRunner = false
        windowStretch = false
        _state.value = ResolutionState("scaled", "", false)
        Log.i(TAG, "X 屏幕分辨率 → 缩放模式 ${p}%")
        pokeActiveView()
    }

    /**
     * v2.22.5 fix15：X11FitClient 自适应专用 —— 把 X 屏幕静默调整为游戏
     * 客户区尺寸（不发 Toast、不写握手文件，避免游戏窗口/启动器切换时
     * 提示刷屏）。stretch 保持开启，显示层把"游戏客户区=X 屏幕"拉伸铺满
     * Android 窗口，四边黑边消失。
     * fix28：本函数不再被无条件调用 —— X11FitClient 决策环加稳定门/
     * 缩屏预算/对抗钉满（详见 X11FitClient fix28 注释），proton 会话的
     * "缩屏→游戏再缩窗"收缩级联被结构性禁止。
     */
    fun applyFit(w: Int, h: Int) {
        if (w < 160 || h < 120 || w > 7680 || h > 4320) return
        val prefs = LoriePreferences.prefs ?: return
        prefs.displayResolutionMode.put("exact")
        prefs.displayStretch.put(true)
        prefs.displayResolutionExact.put("${w}x${h}")
        _state.value = ResolutionState("exact", "${w}x${h}", true)
        // fix18：不修改 exactFromRunner —— 贴合产生的 exact 维持原策略，
        // 避免 -d 会话因一次贴合永久降级。
        X11FitClient.screenW = w
        X11FitClient.screenH = h
        // fix19：不修改 windowStretch —— 贴合产生的 exact 维持当前策略，
        // -d/-F 会话因一次贴合永久降级/升级都不会发生。
        Log.i(TAG, "X 屏幕分辨率 → exact ${w}x${h}（游戏窗口自适应贴合）")
        mainHandler.post {
            activeView?.let { v ->
                try {
                    v.regenerate()
                    v.requestLayout()
                } catch (e: Exception) {
                    Log.w(TAG, "自适应应用时视图已分离", e)
                }
            }
        }
    }
}
