package com.linbox.apps.x11

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linbox.core.shell.ShellController
import com.termux.x11.ICmdEntryInterface
import com.termux.x11.LoriePreferences
import com.termux.x11.LorieView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v6.5：X11 全局显示层状态（供壳层其它覆盖层感知，避免循环依赖）。
 *
 * - [layerActive]：X11DisplayLayer 正在组合（全屏页或小窗浮窗任一形态）。
 *   全局手柄覆盖层（GamepadOverlay）据此让位 —— 小窗模式下 X11 画面
 *   不占整屏，但手柄仍由 X11TouchSplitLayout 内的 Compose 宿主全屏
 *   渲染（View 层分流），全局层必须退场防双渲染。
 * - [fullscreenActive]：当前处于全屏形态（X11 页或浮窗一键全屏）。
 *   PerfGovernor 据此静止主界面周期刷新（把大核让给 wine/box64）。
 */
object X11DisplayState {
    /** X11 显示层正在组合（全屏或小窗）。 */
    var layerActive by mutableStateOf(false)

    /** X11 显示层处于全屏形态（游戏会话独占，主界面刷新静止）。 */
    var fullscreenActive by mutableStateOf(false)
}

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

        // v2.22.4 fix11c：会话在手 → 前台常驻保活（App 进程不被冻结/杀，
        // X server 作为被绑定方优先级随之提升；会话结束后服务自停）。
        X11KeepAliveService.start(context.applicationContext)

        if (_state.value != State.Connected) _state.value = State.Waiting

        if (isNewSession || _state.value == State.Waiting) {
            // v6.5 小窗模式：不跳页 —— 全局显示层（X11DisplayLayer，挂在
            // LinBoxShell）随会话状态在当前界面前方亮出浮窗；用户可在
            // 主页/游戏库等任意页面边看画面边操作底层界面（浮窗外触摸
            // 全部穿透）。小窗关闭（默认）时才自动跳转 X11 全屏页。
            X11FloatWindow.initIfNeed(context.applicationContext)
            if (!X11FloatWindow.enabled) {
                showX11Screen(context.applicationContext)
            }
        }
    }

    /** 主动入口（常驻服务通知按钮等）：跳转 X11 全屏界面。 */
    fun openWindow(context: Context) {
        // 主动打开时不设会话抑制 —— 用户明确想要进入界面。
        suppressedBinder = null
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
     * v6.5：用户关闭小窗 —— 终止显示（配合 X11Session.killWine 结束会话）。
     *
     * 断开渲染连接（X server 端 renderer client 释放，恢复每秒广播）、
     * 抑制本会话自动弹窗、状态归 Idle（X11DisplayLayer 随之退场）。
     */
    fun closeSession() {
        connectGeneration++
        suppressedBinder = sessionBinder
        if (LorieView.connected())
            LorieView.connect(-1)
        _state.value = State.Idle
    }

    /**
     * 视图分离/退出 X11 界面：断开渲染连接（X server 端 renderer client
     * 释放，恢复每秒广播以便下次快速重连）并使重试循环失效。
     * [userClosed]=true 时抑制当前会话的自动跳转。
     */
    fun detachView(userClosed: Boolean) {
        connectGeneration++
        if (userClosed) {
            suppressedBinder = sessionBinder
        }
        if (LorieView.connected())
            LorieView.connect(-1)
        if (_state.value != State.Idle)
            _state.value = if (sessionBinder != null) State.Waiting else State.Idle
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
