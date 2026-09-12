package com.linbox.apps.terminal.termux

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linbox.termux.terminal.TerminalEmulator
import com.linbox.termux.terminal.TerminalSession
import com.linbox.termux.terminal.TerminalSessionClient
import com.linbox.termux.view.TerminalView
import com.linbox.termux.view.TerminalViewClient

/**
 * 单个修饰键（CTRL/ALT/SHIFT/FN）的状态。
 *
 * 对齐官方 ExtraKeys 的 SpecialButton 语义：
 * - 点击：进入激活态，作用到**下一个**按键后自动释放（粘滞一次性）；
 * - 长按：进入锁定态，持续作用直到再次点击。
 * 使用 Compose 状态以便快捷键栏即时重绘。
 */
class StickyModifier {
    var active by mutableStateOf(false)
    var locked by mutableStateOf(false)

    val isEngaged: Boolean get() = active || locked

    fun toggle() {
        if (locked) { locked = false; active = false }
        else if (active) active = false
        else active = true
    }

    fun toggleLock() {
        if (locked) { locked = false } else { locked = true; active = true }
    }

    /** 读后自动释放（未锁定时）—— 供 TerminalView 轮询消费。 */
    fun readAndConsume(): Boolean {
        val result = isEngaged
        if (active && !locked) active = false
        return result
    }

    fun reset() {
        active = false; locked = false
    }
}

/**
 * 快捷键栏与 TerminalView 之间的修饰键状态集合。
 */
class ExtraKeysModifierState {
    val ctrl = StickyModifier()
    val alt = StickyModifier()
    val shift = StickyModifier()
    val fn = StickyModifier()

    /** 发送普通按键后清理未锁定的修饰。 */
    fun consumeOneShot() {
        if (!ctrl.locked) ctrl.active = false
        if (!alt.locked) alt.active = false
        if (!shift.locked) shift.active = false
        if (!fn.locked) fn.active = false
    }

    fun reset() {
        ctrl.reset(); alt.reset(); shift.reset(); fn.reset()
    }
}

/**
 * Termux 会话控制器：管理单个真实 Termux 会话的创建、视图接线与回调路由。
 *
 * - [TerminalSessionClient]：会话输出 → 刷新视图（主线程）
 * - [TerminalViewClient]：视图输入/手势 → 快捷键修饰状态、软键盘、缩放
 *
 * 会话由 [TermuxTerminalHolder] 持有，多终端窗口共享；最后一个终端
 * 窗口关闭时结束会话（fix9.11），进程退出时随进程结束。
 */
class TermuxSessionController(
    private val context: Context,
    private val modifiers: ExtraKeysModifierState
) : TerminalSessionClient, TerminalViewClient {

    companion object {
        private const val TAG = "TermuxSessionController"
        private const val MIN_FONT_SIZE = 8
        private const val MAX_FONT_SIZE = 40
    }

    // v2.24 fix：改为 Compose 可观察状态。
    // 旧版为普通属性：首次组合时快捷键栏（TermuxExtraKeysBar）读取到的
    // terminalView 为 null（AndroidView 工厂在组合之后才创建视图并 attach），
    // 且后续赋值不触发重组 —— 方向键/快捷键注入的对象一直是 null，
    // 表现为“方向键不起作用”；改用 mutableStateOf 后 attach/detach 会
    // 立即驱动快捷键栏重组并拿到最新视图。
    var terminalView: TerminalView? by mutableStateOf(null)
        private set

    var session: TerminalSession? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 会话结束回调（UI 层显示"会话已结束"覆盖层）。 */
    var onSessionFinished: (() -> Unit)? = null

    /** 标题变化回调（bash 端可经 OSC 0/2 序列改标题）。 */
    var onTitleChanged: ((String) -> Unit)? = null

    private var fontSize = 30 // TerminalView 默认像素字号（约 30px），随 pinch 缩放

    // ------------------------------------------------------------------
    // 会话生命周期
    // ------------------------------------------------------------------

    /**
     * 创建真实 Termux 登录会话（$PREFIX/bin/login → bash -l）。
     */
    fun createSession(): TerminalSession {
        val prefix = TermuxEnvironment.prefixPath(context)
        val executable = "$prefix/bin/login"
        val cwd = TermuxEnvironment.homePath(context)

        val env = TermuxEnvironment.buildEnvironment(context, isFailSafe = false)
        val args = arrayOf("-login") // argv0 带前导 '-'：login shell 语义（对齐官方）

        val processArgs = TermuxEnvironment.setupProcessArgs(context, executable, args)
        val realExecutable = processArgs[0]
        val realArgs = processArgs.drop(1).toTypedArray()

        val newSession = TerminalSession(
            realExecutable, cwd, realArgs, env.toTypedArray(),
            TerminalTermuxDefaults.TRANSCRIPT_ROWS, this
        )
        session = newSession
        return newSession
    }

    /**
     * 把会话挂到视图（视图首次布局时由 updateSize 完成模拟器初始化）。
     */
    fun attach(view: TerminalView) {
        terminalView = view
        view.setTerminalViewClient(this)
        view.setTextSize(fontSize)
        session?.let { view.attachSession(it) }
        view.requestFocus()
    }

    fun detach(view: TerminalView) {
        if (terminalView === view) terminalView = null
    }

    /** 工具栏字体调节（pinch 缩放同路径）。 */
    fun changeFontSize(delta: Int) {
        fontSize = (fontSize + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        terminalView?.setTextSize(fontSize)
    }

    /** 会话退出后清理引用。 */
    fun cleanup() {
        session = null
    }

    // ------------------------------------------------------------------
    // TerminalSessionClient（会话 → 视图）
    // ------------------------------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession) {
        val view = terminalView ?: return
        if (session === changedSession) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                view.onScreenUpdated()
            } else {
                mainHandler.post { view.onScreenUpdated() }
            }
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        if (session === changedSession) {
            val title = changedSession.getTitle() ?: return
            if (title.isNotBlank()) onTitleChanged?.invoke(title)
        }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (session === finishedSession) {
            mainHandler.post {
                // 结束后再做一次最终刷新，让 "exit" 输出完整可见
                terminalView?.onScreenUpdated()
                onSessionFinished?.invoke()
            }
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        mainHandler.post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("terminal", text)
                )
            } catch (e: Exception) {
                Log.w(TAG, "剪贴板写入失败: ${e.message}")
            }
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        mainHandler.post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                val clip = clipboard.primaryClip ?: return@post
                val text = clip.getItemAt(0)?.coerceToText(context) ?: return@post
                if (text.isNotEmpty()) {
                    terminalView?.mEmulator?.paste(text.toString())
                }
            } catch (e: Exception) {
                Log.w(TAG, "粘贴失败: ${e.message}")
            }
        }
    }

    override fun onBell(session: TerminalSession) {
        // 静默（避免打扰）；如需震动/提示音可在此扩展
    }

    override fun onColorsChanged(changedSession: TerminalSession) {
        onTextChanged(changedSession)
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // 光标可见性变化：TerminalView 自行处理重绘
    }

    override fun getTerminalCursorStyle(): Int =
        TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

    // ------------------------------------------------------------------
    // TerminalViewClient（视图 → 输入控制）
    // ------------------------------------------------------------------

    /** pinch 缩放步进改字号（对齐官方 Termux 行为）。 */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val increase = scale > 1f
            fontSize = (fontSize + if (increase) 2 else -2).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
            terminalView?.setTextSize(fontSize)
            return 1.0f
        }
        return scale
    }

    /** 单击终端：唤起软键盘（鼠标追踪/外接鼠标场景除外）。 */
    override fun onSingleTapUp(e: MotionEvent) {
        val view = terminalView ?: return
        val emulator = view.mEmulator ?: return
        if (!emulator.isMouseTrackingActive && !e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, 0)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {
        // 文本选择模式切换：无需处理
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    // 快捷键栏修饰状态注入（对齐官方 ExtraKeys 机制：读后自动释放）
    override fun readControlKey(): Boolean = modifiers.ctrl.readAndConsume()
    override fun readAltKey(): Boolean = modifiers.alt.readAndConsume()
    override fun readShiftKey(): Boolean = modifiers.shift.readAndConsume()
    override fun readFnKey(): Boolean = modifiers.fn.readAndConsume()

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {
        // 模拟器初始化完成（视图尺寸确定后）
    }

    // ------------------------------------------------------------------
    // 日志桥
    // ------------------------------------------------------------------

    // 注意：Java 接口要求返回 void/Unit，而 Log.x 返回 Int，
    // 表达式体会把返回类型推断成 Int 导致 override 不匹配，必须用块体。
    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception?) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception?) {
        Log.e(tag, "", e)
    }
}

/** 移植层使用的默认常量。 */
internal object TerminalTermuxDefaults {
    /** 回滚缓冲区行数（官方 Termux 默认 200）。 */
    const val TRANSCRIPT_ROWS = 200
}
