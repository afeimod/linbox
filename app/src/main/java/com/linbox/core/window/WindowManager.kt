package com.linbox.core.window

import android.util.Log
import java.util.UUID

/**
 * 应用启动模式：
 * - FULLSCREEN: 占满整个工作区（任务栏上方），适合浏览器/文件管理器
 * - FLOATING: 可拖拽/缩放/最小化的浮动窗口，适合记事本/计算器/设置
 */
enum class LaunchMode { FULLSCREEN, FLOATING }

/**
 * 调整大小的方向：8 个边缘 + 4 个角落 = 12 个区域（但实际只取 8 个）
 * - LEFT/RIGHT/TOP/BOTTOM: 单边调整
 * - TOP_LEFT/TOP_RIGHT/BOTTOM_LEFT/BOTTOM_RIGHT: 角落同时调整两个方向
 */
enum class ResizeEdge {
    LEFT, RIGHT, TOP, BOTTOM,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

    /** 是否影响左边（即 x 坐标变化） */
    val affectsLeft: Boolean
        get() = this == LEFT || this == TOP_LEFT || this == BOTTOM_LEFT

    /** 是否影响右边（即 width 变化） */
    val affectsRight: Boolean
        get() = this == RIGHT || this == TOP_RIGHT || this == BOTTOM_RIGHT

    /** 是否影响顶边（即 y 坐标变化） */
    val affectsTop: Boolean
        get() = this == TOP || this == TOP_LEFT || this == TOP_RIGHT

    /** 是否影响底边（即 height 变化） */
    val affectsBottom: Boolean
        get() = this == BOTTOM || this == BOTTOM_LEFT || this == BOTTOM_RIGHT
}

/**
 * 窗口状态：单个打开的窗口
 *
 * 视觉重构后：
 * - 支持拖拽移动（dragOffset 提交一次性）
 * - 支持 8 方向调整大小
 * - 支持最小化/最大化
 * - 支持调整 z-index 焦点
 */
data class WindowState(
    val id: String = UUID.randomUUID().toString(),
    val appId: String,
    var title: String,
    val launchMode: LaunchMode,
    // 浮动窗口位置/大小（FULLSCREEN 模式忽略）
    var x: Int = 60,
    var y: Int = 80,
    var width: Int = 720,
    var height: Int = 520,
    var isMinimized: Boolean = false,
    var isMaximized: Boolean = false,
    // 真正的全屏（F11 风格）：隐藏标题栏 + 隐藏任务栏 + 占满整个屏幕
    var isTrueFullscreen: Boolean = false,
    // 保存最大化前的位置，用于还原
    var prevX: Int = x,
    var prevY: Int = y,
    var prevWidth: Int = width,
    var prevHeight: Int = height,
    var zIndex: Int = 0,
    // 应用专属数据（如浏览器要打开的URL、文件管理器要打开的路径）
    val launchArgs: Map<String, String> = emptyMap(),
    // 关闭时回调清理资源
    var onClose: (() -> Unit)? = null
) {
    /** 当前窗口是否可见（未最小化） */
    val isVisible: Boolean get() = !isMinimized
}

/**
 * 窗口管理器：单例，管理所有打开的窗口。
 *
 * 视觉重构：
 * - 全部窗口存在 MutableList 中，按 zIndex 排序
 * - 顶部窗口 zIndex 最大
 * - 任务栏点击最小化/还原
 * - 同一个 appId 可重复打开（允许多窗口）
 *
 * v2.11 拖拽/缩放流畅性重构：
 * - 拖动/缩放期间 WindowChrome 在本地 Compose 状态中实时预览几何信息，
 *   手势结束时才通过 setAbsolutePosition / setAbsoluteFrame 一次性提交到此处；
 * - 因此不再提供逐帧 resize 接口（旧版每帧改 state 但不刷新，缩放不跟手）。
 * - state.x / state.y 单位为像素；state.width / state.height 单位为 dp 数值。
 */
class WindowManager {

    private val _windows = mutableListOf<WindowState>()
    val windows: List<WindowState> get() = _windows.toList()

    private var listeners = mutableListOf<() -> Unit>()

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    /** 打开一个新窗口。 */
    fun open(
        appId: String,
        title: String,
        launchMode: LaunchMode,
        launchArgs: Map<String, String> = emptyMap(),
        initialX: Int = 80 + (_windows.size * 30) % 200,
        initialY: Int = 80 + (_windows.size * 30) % 150,
        initialWidth: Int = 720,
        initialHeight: Int = 520
    ): String {
        val z = (_windows.maxOfOrNull { it.zIndex } ?: 0) + 1
        val state = WindowState(
            appId = appId,
            title = title,
            launchMode = launchMode,
            x = initialX,
            y = initialY,
            width = initialWidth,
            height = initialHeight,
            prevX = initialX, prevY = initialY,
            prevWidth = initialWidth, prevHeight = initialHeight,
            zIndex = z,
            launchArgs = launchArgs
        )
        _windows.add(state)
        notifyChanged()
        return state.id
    }

    /**
     * v3.2.23（用户问题 3）：打开或恢复。
     *
     * 该应用存在**最小化**窗口时优先恢复（focus 置顶 + 解除最小化），
     * 否则照常开新窗。此前桌面图标双击 / 开始菜单磁贴一律 wm.open，
     * 最小化后的窗口永无回魂路径（任务栏四种主题又把它滤掉了），
     * 只会叠开重复窗口 —— 用户观感即"最小化=关闭"。
     *
     * 仅拦截"最小化"态：可见窗口存在时保持旧行为（开新窗，多开语义
     * 不变）。
     */
    fun openOrRestore(
        appId: String,
        title: String,
        launchMode: LaunchMode,
        launchArgs: Map<String, String> = emptyMap(),
        initialX: Int = 80 + (_windows.size * 30) % 200,
        initialY: Int = 80 + (_windows.size * 30) % 150,
        initialWidth: Int = 720,
        initialHeight: Int = 520
    ): String {
        val minimized = _windows.firstOrNull { it.appId == appId && it.isMinimized }
        if (minimized != null) {
            focus(minimized.id)
            return minimized.id
        }
        return open(
            appId, title, launchMode, launchArgs,
            initialX, initialY, initialWidth, initialHeight
        )
    }

    fun close(windowId: String) {
        val idx = _windows.indexOfFirst { it.id == windowId }
        if (idx >= 0) {
            _windows[idx].onClose?.invoke()
            _windows.removeAt(idx)
            notifyChanged()
        }
    }

    fun focus(windowId: String) {
        val maxZ = _windows.maxOfOrNull { it.zIndex } ?: 0
        val w = _windows.firstOrNull { it.id == windowId } ?: return
        // v3.1.7：已是顶层且可见 → 无操作。此前无条件 notifyChanged，
        // WindowChrome 的"任意点击置前"观察器（每次按下都调 focus）会
        // 在点击顶层窗口时触发整层无效重组。
        if (w.zIndex == maxZ && !w.isMinimized) return
        w.zIndex = maxZ + 1
        w.isMinimized = false
        notifyChanged()
        // v3.2.2：置前诊断日志（设备排障关键 —— logcat 过滤 WindowManager/
        // WindowFocusHook 即可确认置前链路是否兑现）
        Log.i("WindowManager", "窗口置前: 「${w.title}」 z=${w.zIndex}（${_windows.size} 个窗口）")
    }

    fun minimize(windowId: String) {
        _windows.firstOrNull { it.id == windowId }?.let {
            it.isMinimized = true
            notifyChanged()
        }
    }

    /** v3.4.5（Win7 1:1）：显示桌面 —— 全部窗口最小化（任务栏最右端 Aero 条）。 */
    fun minimizeAll() {
        var touched = false
        _windows.forEach {
            if (!it.isMinimized) { it.isMinimized = true; touched = true }
        }
        if (touched) notifyChanged()
    }

    fun toggleMaximize(windowId: String) {
        _windows.firstOrNull { it.id == windowId }?.let { w ->
            if (w.isMaximized) {
                w.x = w.prevX; w.y = w.prevY
                w.width = w.prevWidth; w.height = w.prevHeight
                w.isMaximized = false
            } else {
                w.prevX = w.x; w.prevY = w.y
                w.prevWidth = w.width; w.prevHeight = w.height
                w.isMaximized = true
            }
            notifyChanged()
        }
    }

    /**
     * 切换真正的全屏（F11 风格）：
     * - 隐藏窗口标题栏与调整大小手柄
     * - 让 DesktopEnvironment 隐藏任务栏并把浮动窗口层占满整屏
     * 不影响 isMaximized —— 退出全屏后回到原状态
     */
    fun toggleTrueFullscreen(windowId: String) {
        _windows.firstOrNull { it.id == windowId }?.let { w ->
            w.isTrueFullscreen = !w.isTrueFullscreen
            notifyChanged()
        }
    }

    /** 是否有窗口正处于真全屏状态（用于 DesktopEnvironment 隐藏任务栏） */
    fun anyTrueFullscreen(): Boolean =
        _windows.any { it.isVisible && it.isTrueFullscreen }

    /**
     * 设置窗口的绝对位置（拖拽时直接设置，避免累加误差）
     * 配合 WindowChrome 的本地 dragOffset 使用：松手时把"窗口原位置 + dragOffset"作为绝对位置提交。
     */
    fun setAbsolutePosition(windowId: String, x: Int, y: Int) {
        _windows.firstOrNull { it.id == windowId && !it.isMaximized }?.let {
            it.x = x
            it.y = y
        }
    }

    /**
     * 拖拽/缩放结束后调用，触发 UI 刷新
     */
    fun commitChanges() {
        notifyChanged()
    }

    /**
     * 设置窗口的绝对尺寸（缩放结束一次性提交，v2.11）。
     *
     * 坐标单位：xPx/yPx 为像素（与手势事件一致）；
     * widthDp/heightDp 为 dp 数值（与 WindowChrome 渲染一致）。
     * 边界钳制（工作区范围、最小尺寸）由 WindowChrome 在手势端完成，
     * 此处仅做最小尺寸兜底，防止异常数据。
     */
    fun setAbsoluteFrame(
        windowId: String,
        xPx: Int,
        yPx: Int,
        widthDp: Int,
        heightDp: Int
    ) {
        _windows.firstOrNull { it.id == windowId && !it.isMaximized }?.let {
            it.x = xPx.coerceAtLeast(0)
            it.y = yPx.coerceAtLeast(0)
            it.width = widthDp.coerceAtLeast(200)
            it.height = heightDp.coerceAtLeast(140)
        }
    }

    /**
     * v2.17：程序化调整窗口尺寸（如扫雷切换难度后棋盘变大，窗口自动扩展）。
     *
     * - 尺寸下限与手动缩放一致（200x140dp）；
     * - 同步更新 prevWidth/prevHeight，最大化还原后同样生效；
     * - 不改变窗口位置；超出工作区的部分由 WindowChrome 渲染时自动钳制。
     */
    fun resizeWindow(windowId: String, widthDp: Int, heightDp: Int) {
        _windows.firstOrNull { it.id == windowId }?.let { w ->
            val nw = widthDp.coerceAtLeast(200)
            val nh = heightDp.coerceAtLeast(140)
            w.width = nw
            w.height = nh
            w.prevWidth = nw
            w.prevHeight = nh
            notifyChanged()
        }
    }

    /** 任务栏点击某窗口的行为：可见则最小化，最小化则还原+聚焦 */
    fun taskbarClick(windowId: String) {
        val w = _windows.firstOrNull { it.id == windowId } ?: return
        if (w.isMinimized) {
            focus(windowId)
        } else {
            // 如果是顶部窗口，最小化；否则聚焦
            val topZ = _windows.maxOfOrNull { it.zIndex } ?: 0
            if (w.zIndex == topZ) {
                minimize(windowId)
            } else {
                focus(windowId)
            }
        }
    }

    fun topWindow(): WindowState? = _windows
        .filter { it.isVisible }
        .maxByOrNull { it.zIndex }

    fun windowsForApp(appId: String): List<WindowState> =
        _windows.filter { it.appId == appId }

    fun closeAll() {
        _windows.forEach { it.onClose?.invoke() }
        _windows.clear()
        notifyChanged()
    }

    companion object {
        // 全局单例（应用生命周期）
        @Volatile private var INSTANCE: WindowManager? = null
        fun get(): WindowManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: WindowManager().also { INSTANCE = it }
        }
    }
}
