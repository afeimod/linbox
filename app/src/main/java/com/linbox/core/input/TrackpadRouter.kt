package com.linbox.core.input

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates

/**
 * Compose 1.6.x 移除了 [LayoutCoordinates.boundsInWindow]（positionInWindow 等
 * 也降级为 androidx.compose.ui.layout 包下的顶层扩展函数，在 LayoutCoordinates
 * 扩展体内不能再靠隐式 this 命中）。此扩展用 localToWindow(Offset.Zero) +
 * size 手动构造等效 Rect。
 */
internal fun LayoutCoordinates.boundsInWindowCompat(): Rect {
    val pos = localToWindow(Offset.Zero)
    val s = size
    return Rect(pos.x, pos.y, pos.x + s.width, pos.y + s.height)
}

/**
 * 触控板路由器（v2.20）—— 手势仲裁自 Compose 门禁层【上移到 View 层】。
 *
 * ## 为什么要上移（v2.19.0~2.19.5 "随机丢失触控板 / 长按失效" 的真正根因）
 *
 * 旧方案把门禁放在 Compose（pointerInput + PointerEventPass.Initial），真实
 * 手指事件与注入的合成事件（toolType=MOUSE）【交织流过同一个
 * AndroidComposeView 管线】。Compose 1.6.8 的
 * AndroidComposeView.handleMotionEvent 用「source/toolType 是否变化」判定
 * 设备切换（hasChangedDevices），并在上一事件仍处于按压态（DOWN/MOVE）时
 * 直接调用 pointerInputEventProcessor.processCancel()：
 *
 * 1. 【长按拖拽自毁】长按定时器到点注入 dragDown（MOUSE）→ 下一帧真实
 *    MOVE（FINGER）到达 → 判定设备切换 + 上一事件是按压态 → processCancel
 *    → 门禁收到合成取消（所有指 pressed=false）→ endGesture → 立刻注入
 *    dragUp。拖拽从诞生到死亡不足一帧 —— 表现为"没有长按逻辑"。
 * 2. 【指针 id 重映射风暴】 MotionEventAdapter.clearOnDeviceChange 在每次
 *    设备切换时清空 motionEventToComposePointerIdMap → 仍按着的真实手指
 *    被映射成全新 compose id（changedToDown=true）→ 各手势节点看到"凭空
 *    多出的新按下"，陈旧命中路径在 HitPathTracker 里永久泄漏、随点击次数
 *    累积 → 随机出现手势僵死/事件错乱，门禁协程饿死 → "触控板逻辑丢失、
 *    恢复触摸点击、鼠标不动"。
 * 3. 【菜单即触即关】focusable Popup 收到 ACTION_OUTSIDE 即 dismiss ——
 *    右键菜单已在主窗口内渲染规避（见 TrackpadLayer 头注释）。
 *
 * ## v2.20 架构（对齐 termux-x11 的 TouchInputHandler）
 *
 * - 真实手指事件在 [com.linbox.MainActivity.dispatchTouchEvent] 被
 *   [onMotionEvent] 拦截（返回 true，不再调用 super）——【真实手指根本
 *   不进 Compose 管线】；注入流（id ≥ [INJECTED_POINTER_ID]）原样放行。
 *   Compose 管线里从此只有清一色的 MOUSE 注入流：无设备切换、无
 *   processCancel、无 id 重映射 —— 上述整类问题从传输层根除。
 * - 手势状态机为同步实现（dispatchTouchEvent 本就运行在主线程），
 *   长按用 Handler 定时器 —— 只可能在两次事件之间触发，天然无竞态。
 * - 输出端不变：指针移动走 [MouseController]，点击/拖拽/滚轮注入走
 *   [TrackpadController]（View 层注入器，逐窗口路由 + interop 旁路）。
 * - 虚拟键盘面板、手柄元素/工具条/设置面板等"必须直触"的 UI 通过
 *   [registerPassthrough] 登记直通区，落在区内的真实手指原样放行。
 *
 * ## 手势集（与 v2.19.5 一致）
 * - 单指滑动：指针相对移动（× [TrackpadController.SENSITIVITY]）
 * - 轻点（< 320ms 且各指位移 < 24dp slop）：指针处注入单击
 * - 按住不动 ≥ 320ms：默认进入拖拽（注入 DOWN，滑动跟随 MOVE，抬手 UP）；
 *   右键手势设为"长按"时改为在指针位置呼出右键菜单（[onContextMenu]）
 * - 双指滑动（超 16dp slop）：指针处注入滚轮
 * - 双指轻点（< 450ms、未滚动、未超 slop）：回调 [onContextMenu]
 */
object TrackpadRouter {

    // ===== 配置（由壳层 LinBoxShell 的 SideEffect 同步） =====

    /** 触控板模式总开关（settingsStore.mouseControlMode == "trackpad"） */
    @Volatile
    var enabled: Boolean = false

    /** 右键手势 = 长按时为 true：长按呼出右键菜单而非进入拖拽 */
    @Volatile
    var longPressRightClick: Boolean = false

    /** 指针移动灵敏度换算用的密度（与 Compose LocalDensity 同源，含 UI 缩放） */
    @Volatile
    var density: Float = 3f

    /** 双指轻点 / 长按右键 → 呼出右键菜单（原桌面直连；现壳层不使用，保留钩子） */
    var onContextMenu: ((Offset) -> Unit)? = null

    /** 被拦截的真实手指事件的通知（原桌面接自动锁屏；现壳层不使用） */
    var onUserInteraction: (() -> Unit)? = null

    // ===== 直通区注册表（窗口坐标 px；虚拟键盘面板 / 手柄工具条等登记） =====

    private val passthroughRects = HashMap<String, Rect>()

    /** 登记/注销一块"真实手指直通"区域（rect = null 表示注销） */
    fun registerPassthrough(key: String, rect: Rect?) {
        if (rect == null) passthroughRects.remove(key) else passthroughRects[key] = rect
    }

    private fun isPassthrough(x: Float, y: Float): Boolean {
        for (r in passthroughRects.values) {
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true
        }
        return false
    }

    /**
     * 模式切换退出触控板时调用（壳层 LinBoxShell 同步 enabled 前调用）：
     * 撤销进行中的手势会话，释放未完成的注入拖拽流（防"幽灵长按"）。
     */
    fun onDisabled() {
        cancelSession()
    }

    // ===== 手势会话状态 =====

    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private var inGesture = false
    private var maxPointers = 1
    private var moved = false
    private var scrollMode = false
    private var dragMode = false
    private var longPressFired = false
    private var gestureStartMs = 0L
    private var lastCentroid = Offset.Zero
    private val startPositions = HashMap<Int, Offset>() // raw pointer id → 起点
    private val lastPositions = HashMap<Int, Offset>()  // raw pointer id → 上帧位置

    private val touchSlopPx: Float get() = 24f * density
    private val scrollSlopPx: Float get() = 16f * density
    private val longPressMs = 320L
    private val twoFingerTapMs = 450L

    // ===== 入口（MainActivity.dispatchTouchEvent 调用） =====

    /**
     * 触控板模式下的真实手指事件仲裁。返回 true = 已消费（不进 Compose）。
     * 注入流（id ≥ 99）与直通区内的事件一律返回 false 原样放行。
     */
    fun onMotionEvent(activity: Activity, ev: MotionEvent): Boolean {
        if (!enabled) return false

        // 注入流（合成点击/拖拽/滚轮，指针 id ≥ 99）原样放行 —— Compose 管线
        // 里只有这一种事件流，绝无与真实手指交织的可能
        for (i in 0 until ev.pointerCount) {
            if (ev.getPointerId(i) >= INJECTED_POINTER_ID) return false
        }

        // 直通区（虚拟键盘面板 / 手柄元素 / 工具条 / 设置面板）：真实手指直触
        if (isPassthrough(ev.x, ev.y)) return false

        if (view == null || view!!.rootView !== view) {
            view = activity.window.decorView
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(ev)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(ev)
            MotionEvent.ACTION_MOVE -> onMove(ev)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(ev)
            MotionEvent.ACTION_UP -> endSession(ev.eventTime)
            MotionEvent.ACTION_CANCEL -> cancelSession()
            else -> return false
        }

        onUserInteraction?.invoke()
        return true
    }

    // ===== 事件分派 =====

    private fun onDown(ev: MotionEvent) {
        inGesture = true
        maxPointers = 1
        moved = false
        scrollMode = false
        dragMode = false
        startPositions.clear()
        lastPositions.clear()
        gestureStartMs = ev.eventTime
        val id = ev.getPointerId(0)
        val p = Offset(ev.x, ev.y)
        startPositions[id] = p
        lastPositions[id] = p
        lastCentroid = p
        scheduleLongPress()
    }

    private fun onPointerDown(ev: MotionEvent) {
        if (!inGesture) {
            // 异常态兜底（理论上下发序总是 DOWN 在前）：按全新会话处理
            onDown(ev)
        }
        val id = ev.getPointerId(ev.actionIndex)
        val p = Offset(ev.getX(ev.actionIndex), ev.getY(ev.actionIndex))
        startPositions[id] = p
        lastPositions[id] = p
        if (ev.pointerCount > maxPointers) {
            maxPointers = ev.pointerCount
            if (maxPointers >= 2) {
                cancelLongPress()
                lastCentroid = centroidOf(ev)
            }
        }
    }

    private fun onMove(ev: MotionEvent) {
        if (!inGesture) return

        // 登记本事件中新出现的指针（同帧多指/换指场景）
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            val p = Offset(ev.getX(i), ev.getY(i))
            startPositions.putIfAbsent(id, p)
            lastPositions.putIfAbsent(id, p)
        }
        if (ev.pointerCount > maxPointers) {
            maxPointers = ev.pointerCount
            if (maxPointers >= 2) cancelLongPress()
        }

        // 逐指 slop 检测（任一指超出起点 24dp 即判"已移动"）
        if (!moved) {
            for (i in 0 until ev.pointerCount) {
                val start = startPositions[ev.getPointerId(i)] ?: continue
                if ((Offset(ev.getX(i), ev.getY(i)) - start).getDistance() > touchSlopPx) {
                    moved = true
                    cancelLongPress()
                    break
                }
            }
        }

        if (maxPointers >= 2 && ev.pointerCount >= 2) {
            // ---- 双指：滑动 = 滚轮（质心增量） ----
            val centroid = centroidOf(ev)
            val d = centroid - lastCentroid
            if (!scrollMode) {
                if (d.getDistance() > scrollSlopPx) {
                    scrollMode = true
                    moved = true
                    cancelLongPress()
                }
            } else if (d.getDistance() > 0f) {
                val v = view ?: return
                val cursor = MouseController.position
                TrackpadController.injectScroll(v, cursor.x, cursor.y, d.x, d.y)
            }
            lastCentroid = centroid
        } else {
            // ---- 单指：移动指针；拖拽态同时注入 MOVE ----
            for (i in 0 until ev.pointerCount) {
                val id = ev.getPointerId(i)
                val last = lastPositions[id] ?: continue
                val p = Offset(ev.getX(i), ev.getY(i))
                lastPositions[id] = p
                moveCursorBy(p - last)
                if (dragMode) {
                    val v = view ?: return
                    val cursor = MouseController.position
                    TrackpadController.injectDragMove(v, cursor.x, cursor.y)
                }
                break // 只跟随第一个仍登记的指针（首见指已登记、零跳变）
            }
        }
    }

    private fun onPointerUp(ev: MotionEvent) {
        val id = ev.getPointerId(ev.actionIndex)
        startPositions.remove(id)
        lastPositions.remove(id)
        // 剩余指针继续跟随；maxPointers 保持（余指抬手不再判轻点）
    }

    /** 会话正常收尾：按累计状态分类（拖拽释放 / 单击 / 双指右键） */
    private fun endSession(nowMs: Long) {
        val v = view
        cancelLongPress()
        if (v != null) {
            val duration = nowMs - gestureStartMs
            val cursor = MouseController.position
            when {
                dragMode -> {
                    dragMode = false
                    MouseController.press(false)
                    TrackpadController.injectDragUp(v, cursor.x, cursor.y)
                }
                maxPointers == 1 && !moved && !longPressFired && duration < longPressMs ->
                    TrackpadController.tapClick(v, cursor.x, cursor.y)
                maxPointers == 2 && !scrollMode && !moved && duration < twoFingerTapMs ->
                    onContextMenu?.invoke(cursor)
            }
        }
        resetSession()
    }

    /** 异常收尾（ACTION_CANCEL / 系统打断）：释放注入中的拖拽流，会话作废 */
    private fun cancelSession() {
        val v = view
        cancelLongPress()
        if (dragMode && v != null) {
            dragMode = false
            MouseController.press(false)
            val cursor = MouseController.position
            TrackpadController.injectDragUp(v, cursor.x, cursor.y)
        }
        resetSession()
    }

    private fun resetSession() {
        inGesture = false
        maxPointers = 1
        moved = false
        scrollMode = false
        dragMode = false
        longPressFired = false
        startPositions.clear()
        lastPositions.clear()
    }

    // ===== 长按定时器（主线程 Handler，与事件派发天然串行互斥） =====

    private val longPressRunnable = Runnable {
        if (!enabled || !inGesture || dragMode || scrollMode || moved) return@Runnable
        if (maxPointers != 1 || startPositions.size != 1) return@Runnable
        if (longPressRightClick) {
            // 右键手势 = 长按：指针位置呼出右键菜单；会话保持（手指仍按着时
            // 继续驱动指针滑到菜单项上），longPressFired 屏蔽抬手误判轻点
            longPressFired = true
            val cursor = MouseController.position
            onContextMenu?.invoke(cursor)
        } else {
            // 默认：长按 = 按住拖拽（注入 DOWN；之后滑动注入 MOVE，抬手注入 UP）
            dragMode = true
            moved = true
            MouseController.press(true)
            val v = view ?: return@Runnable
            val cursor = MouseController.position
            TrackpadController.injectDragDown(v, cursor.x, cursor.y)
        }
    }

    private fun scheduleLongPress() {
        handler.removeCallbacks(longPressRunnable)
        handler.postDelayed(longPressRunnable, longPressMs)
    }

    private fun cancelLongPress() {
        handler.removeCallbacks(longPressRunnable)
    }

    // ===== 工具 =====

    /** 指针相对移动（灵敏度放大 + clamp 屏内） */
    private fun moveCursorBy(delta: Offset) {
        if (delta.getDistance() <= 0.01f) return
        val v = view ?: return
        val cur = MouseController.position
        val nx = (cur.x + delta.x * TrackpadController.SENSITIVITY)
            .coerceIn(0f, v.width.toFloat())
        val ny = (cur.y + delta.y * TrackpadController.SENSITIVITY)
            .coerceIn(0f, v.height.toFloat())
        MouseController.update(nx, ny)
    }

    /** 本事件全部指针的质心（滚轮增量基准） */
    private fun centroidOf(ev: MotionEvent): Offset {
        var x = 0f
        var y = 0f
        for (i in 0 until ev.pointerCount) {
            x += ev.getX(i)
            y += ev.getY(i)
        }
        return Offset(x / ev.pointerCount, y / ev.pointerCount)
    }
}
