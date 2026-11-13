package com.linbox.apps.x11

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.linbox.core.shell.ShellController
import com.termux.x11.ICmdEntryInterface
import com.termux.x11.LoriePreferences
import com.termux.x11.LorieView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v2.22.2 fix9.6：X11 显示端连接中枢。
 *
 * 终端侧 `linbox-x11` 启动的 X server 进程（CmdEntryPoint，app_process）
 * 每秒广播一次 ACTION_START，附带自己 binder（ICmdEntryInterface）。本对象：
 *
 * 1. 接收广播（LinBoxApp 注册的动态接收器回调），取出 binder 暂存；
 * 2. 自动跳转到全屏 X11 界面（ShellController.Screen.X11，不再弹
 *    浮动窗口/独立 Activity）；
 * 3. 页面内 LorieView 就绪后调 [connectLorieView]：反复尝试
 *    service.xConnection 取连接 fd → LorieView.connect(fd)
 *    → native 渲染器接管，把 X server 帧画进 Surface；
 * 4. 会话抑制：用户主动退出 X11 界面后，同一 X server 进程的后续
 *    重播不再自动跳转（以 binder 身份区分会话）；新的 X server
 *    进程 = 新 binder = 新会话，重新自动跳转。
 *
 * v6.4（用户：“x11小窗口开启时回主页后悬浮球里就丢失了x11的设置了，
 * 只要有x11运行就应该保持x11设置存在”）：
 * - [sessionActive]：可观察的“X11 会话存活”标志 —— 悬浮球 X11 快捷组
 *   不再只看当前页是否是 X11，而是会话存活就常驻（全屏页 / 小窗模式均算）；
 * - [miniMode]：小窗模式标志 —— 开启期间广播重播不再拉人回全屏页，
 *   画面由 X11MiniWindow 的迷你浮窗承接（decorView 悬浮窗，不挡后面
 *   界面操作）；restore() 时置回 false 恢复自动跳转。
 *
 * 与 com.termux.x11.MainActivity（兼容用全屏 Activity）互不抢占：广播
 * 只在本对象与该 Activity 的动态接收器之间并行分发，谁先取用 fd 谁渲染。
 */
object X11WindowController {

    private const val TAG = "X11WindowController"
    private const val OPEN_DEBOUNCE_MS = 1500L

    /** 连接状态（窗口内容据此显示等待页/画面）。 */
    sealed interface State {
        /** 尚未收到任何 X server 广播。 */
        data object Idle : State
        /** 已发现 X server，等待窗口视图就绪并取用连接 fd。 */
        data object Waiting : State
        /** LorieView 已接管连接，X 画面渲染中。 */
        data object Connected : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** v6.4：X11 会话是否存活（悬浮球 X11 组常驻依据，可观察）。 */
    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive

    private val _miniMode = MutableStateFlow(false)

    /** v6.4：小窗模式（画面由 X11MiniWindow 迷你浮窗承接，不自动跳全屏页）。 */
    var miniMode: Boolean
        get() = _miniMode.value
        set(value) { _miniMode.value = value }

    @Volatile private var service: ICmdEntryInterface? = null
    /** 当前活跃 X server 会话（binder 身份，每进程唯一）。 */
    @Volatile private var sessionBinder: IBinder? = null
    /** 用户手动关闭窗口时的会话 —— 该会话的重播不再自动开窗。 */
    @Volatile private var suppressedBinder: IBinder? = null
    private var lastOpenAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    /** 连接尝试代数：视图分离/换会话后使旧重试循环失效。 */
    @Volatile private var connectGeneration = 0

    /**
     * LinBoxApp 动态接收器入口：暂存 binder 并跳转 X11 全屏界面。
     * [intent] 为 CmdEntryPoint.ACTION_START 广播（bundle 里带 binder）。
     */
    fun onBroadcastReceived(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra(null) ?: return
        val binder = bundle.getBinder(null) ?: return

        // 同一 X server 会话且用户已主动退出界面 → 尊重用户选择，不反复拉入。
        if (binder === suppressedBinder && binder === sessionBinder) return

        val isNewSession = binder !== sessionBinder
        sessionBinder = binder
        suppressedBinder = null
        try {
            service = ICmdEntryInterface.Stub.asInterface(binder)
        } catch (e: Exception) {
            Log.e(TAG, "提取 X 连接 binder 失败", e)
            return
        }

        // v6.4：会话在手 → 存活标志点亮（悬浮球 X11 组常驻）。
        _sessionActive.value = true

        // v2.22.4 fix11c：会话在手 → 前台常驻保活（App 进程不被冻结/杀，
        // X server 作为被绑定方优先级随之提升；会话结束后服务自停）。
        X11KeepAliveService.start(context.applicationContext)

        if (_state.value != State.Connected) _state.value = State.Waiting

        // v6.4：小窗模式（画面在迷你浮窗）—— 广播重播不拉人回全屏页，
        // 用户在其他界面自由操作，X11 设置仍可从悬浮球进入。
        if (miniMode) return

        if (isNewSession || _state.value == State.Waiting)
            showX11Screen(context.applicationContext)
    }

    /** 主动入口（常驻服务通知按钮等）：跳转 X11 全屏界面。 */
    fun openWindow(context: Context) {
        // 主动打开时不设会话抑制 —— 用户明确想要进入界面。
        suppressedBinder = null
        // v6.4：从通知/悬浮球主动进入全屏 = 退出小窗模式（画面迁回全屏页）。
        miniMode = false
        showX11Screen(context.applicationContext)
    }

    private fun showX11Screen(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_DEBOUNCE_MS) return
        lastOpenAt = now
        ShellController.showX11()
        Log.i(TAG, "已跳转 LinBox X11 界面")
    }

    /**
     * 窗口内 LorieView 就绪后调用：启动连接尝试循环。
     * 循环持有 generation，视图分离（[detachView]）或新会话后自动失效。
     */
    fun connectLorieView(view: LorieView) {
        val gen = ++connectGeneration

        fun attempt() {
            if (gen != connectGeneration) return // 视图已分离/被新尝试取代

            if (LorieView.connected()) {
                // 已有渲染连接（例如会话期间窗口重建）：把新视图的 Surface
                // 交给 native 渲染器（对齐 MainActivity 重连路径）。
                view.triggerCallback()
                _state.value = State.Connected
                return
            }

            val svc = service
            if (svc == null) {
                // X server 尚未广播到达（或未启动）——保持等待，循环持续。
                _state.value = State.Waiting
                mainHandler.postDelayed(::attempt, 500)
                return
            }

            // 会话已死（linbox-x11-stop 或进程被杀）：不再空转，回 Idle。
            if (!runCatching { svc.asBinder().isBinderAlive }.getOrDefault(false)) {
                if (sessionBinder === svc.asBinder()) {
                    sessionBinder = null
                    service = null
                }
                _sessionActive.value = false
                _state.value = State.Idle
                return
            }

            try {
                val pfd: ParcelFileDescriptor? = svc.xConnection
                if (pfd == null) {
                    // X server 侧尚未就绪（例如刚启动）——稍后重试。
                    mainHandler.postDelayed(::attempt, 500)
                    return
                }
                val fd = pfd.detachFd()
                LorieView.connect(fd)
                // 对齐 MainActivity 连接成功路径：交 Surface + 应用偏好
                // （剪贴板同步/扫描码/输入设备刷新，内部均已判空安全）。
                view.triggerCallback()
                LoriePreferences.prefs?.let { view.reloadPreferences(it) }
                _state.value = State.Connected
                Log.i(TAG, "X 连接已建立（fd=$fd）")
            } catch (e: Exception) {
                Log.e(TAG, "取用 X 连接失败，稍后重试", e)
                mainHandler.postDelayed(::attempt, 1000)
            }
        }

        mainHandler.post(::attempt)
    }

    /**
     * 视图分离/退出 X11 界面：断开渲染连接（X server 端 renderer client
     * 释放，恢复每秒广播以便下次快速重连）并使重试循环失效。
     * [userClosed]=true 时抑制当前会话的自动跳转。
     *
     * v6.4：[userClosed]=false 且会话仍在（小窗模式切换 / 迷你浮窗接管）
     * 时保持 sessionActive=true —— 悬浮球 X11 快捷组持续可见，
     * mini 浮窗随后经 connectLorieView 重新取 fd 续上画面。
     */
    fun detachView(userClosed: Boolean) {
        connectGeneration++
        if (userClosed) {
            suppressedBinder = sessionBinder
            // 用户主动关闭全屏页且未开小窗 → 会话视为结束（X11 组退场）。
            if (!miniMode) _sessionActive.value = false
        } else {
            // v6.4：会话延续（小窗接管 / 恢复全屏）—— 解除抑制让广播
            // 重播正常流动（维持 sessionActive 刷新），且明确点亮存活标志
            //（否则从“关闭后又重进”的抑制会话切小窗时，广播早退会把
            // 悬浮球 X11 组意外熄灭）。
            suppressedBinder = null
            if (sessionBinder != null) _sessionActive.value = true
        }
        if (LorieView.connected())
            LorieView.connect(-1)
        if (_state.value != State.Idle)
            _state.value = if (sessionBinder != null) State.Waiting else State.Idle
    }

    /** v6.4：会话彻底结束（迷你浮窗关闭/会话死亡）时外部满血复位。 */
    fun markSessionEnded() {
        _sessionActive.value = false
        miniMode = false
    }

    /** 当前是否有活跃会话（供等待页展示提示）。 */
    fun hasSession(): Boolean = sessionBinder != null

    /**
     * v2.22.4 fix11c：X server 会话是否真实存活（binder 层面）。
     * 供常驻服务巡检：linbox-x11-stop / 进程被杀后 binder 死亡 → 服务自停。
     */
    fun isSessionAlive(): Boolean {
        val svc = service ?: return false
        return runCatching { svc.asBinder().isBinderAlive }.getOrDefault(false)
    }
}
