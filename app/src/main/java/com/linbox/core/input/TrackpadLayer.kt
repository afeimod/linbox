package com.linbox.core.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * 注入合成事件在【原生 View 层】使用的指针 id 基线。
 *
 * ⚠️ 判别注入流与真实手指的两个维度：
 * - 原生 View 层（[com.linbox.MainActivity.dispatchTouchEvent] 等）：
 *   拿到的是未经映射的原始 MotionEvent，直接用
 *   `pointerId >= INJECTED_POINTER_ID` 判定。
 * - Compose 层：Compose 1.6.8 的 MotionEventAdapter 维护
 *   `motionEventToComposePointerIdMap`，把原始 pointerId 映射为内部自增 id
 *   （永不回落），id 维度无法区分注入与真实 —— 一律改用 [PointerType]：
 *   真实手指 toolType=FINGER → `PointerInputChange.type == PointerType.Touch`；
 *   注入合成（点击/拖拽）toolType=MOUSE → `type == PointerType.Mouse`。
 *
 * v2.20 起触控板模式下真实手指在 MainActivity.dispatchTouchEvent 被
 * [TrackpadRouter] 拦截（根本不进 Compose 管线），Compose 层看到的只有
 * 清一色的注入流 —— 过去的交织竞态（processCancel 风暴 / 指针 id 重映射 /
 * 陈旧命中路径累积 → "触控板随机失灵、恢复普通触摸、鼠标冻结"）从
 * 传输层根除。详见 [TrackpadRouter] 头注释。
 */
internal const val INJECTED_POINTER_ID = 99

/**
 * 触控板容器（v2.20）。
 *
 * v2.19.x 时代本层承载手势门禁（pointerInput + Initial pass 无差别消费）；
 * v2.20 手势仲裁整体上移到 View 层 [TrackpadRouter]
 * （入口：MainActivity.dispatchTouchEvent），真实手指不再进入 Compose
 * 管线，本层退化为纯容器，仅保留包裹结构（桌面内容层）与签名兼容。
 *
 * 手势集不变：
 * - 单指滑动 = 移动指针；轻点 = 单击；按住不动 ≥ 320ms = 拖拽（或"长按
 *   右键"设置下呼出菜单）；双指滑动 = 滚轮；双指轻点 = 右键菜单。
 * - 右键菜单在主窗口内渲染：双指轻点直接回调 onContextMenu，菜单打开后
 *   滑动/点按不会再触发 Popup 的 ACTION_OUTSIDE dismiss —— "双指右键
 *   出菜单无法滑动选择"修复点。
 */
@Suppress("UNUSED_PARAMETER") // onTwoFingerTap 为签名兼容保留（见下方注释）
@Composable
fun TrackpadGate(
    modifier: Modifier = Modifier,
    onTwoFingerTap: ((Offset) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    // onTwoFingerTap 仅为签名兼容保留；回调接线原由桌面环境（已移除）
    // 直连 TrackpadRouter.onContextMenu（View 层仲裁输出）。
    Box(modifier = modifier) {
        content()
    }
}

/**
 * 触控板注入器：把指针动作合成为真实 MotionEvent / 滚轮事件派发给目标窗口。
 * 所有触摸型注入使用 id ≥ [INJECTED_POINTER_ID] 的合成指针（View 层路由器
 * 与手势层据此放行注入流、拦截真实手指，二者零耦合）。
 *
 * 相当于 termux-x11 架构中的 InputEventSender（动作的独立接收端）。
 */
object TrackpadController {

    /** 指针移动灵敏度（手指像素 → 指针像素倍率） */
    const val SENSITIVITY = 1.6f

    /** 当前指针是否被触控板按住（视觉按压效果） */
    var pressing: Boolean
        get() = MouseController.pressed
        private set(value) {
            MouseController.press(value)
        }

    /** 轻点板面 = 指针处一次完整单击（视觉按压与注入绑定为原子动作） */
    fun tapClick(view: View, x: Float, y: Float) {
        pressing = true
        injectClick(view, x, y)
        pressing = false
    }

    /**
     * 在指定 View 上注入一次完整点击（DOWN → UP，间隔 60ms 事件时间）。
     *
     * ⚠️ 必须通过 view.post { ... } 延迟到下一个 looper 迭代再派发：
     * injectClick 从手势机内部被调用时，调用栈可能仍在 View 输入派发
     * （dispatchTouchEvent）中，直接同步 dispatchTouchEvent 是重入调用，
     * 会被 Compose PointerInputEventProcessor 的忙碌保护静默丢弃。
     * view.post 把派发推迟到下一个 looper 迭代，点击流直达目标控件。
     */
    fun injectClick(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        view.post {
            try {
                dispatchTouch(view, MotionEvent.ACTION_DOWN, now, 0L, x, y)
                dispatchTouch(view, MotionEvent.ACTION_UP, now, 60L, x, y)
            } catch (_: Exception) {
            }
        }
    }

    // ===== 拖拽流：DOWN（长按超时）→ MOVE…（跟随指针）→ UP（抬手） =====
    private var dragDownTime = 0L

    fun injectDragDown(view: View, x: Float, y: Float) {
        dragDownTime = SystemClock.uptimeMillis()
        val dt = dragDownTime
        view.post {
            runCatching { dispatchTouch(view, MotionEvent.ACTION_DOWN, dt, 0L, x, y) }
        }
    }

    fun injectDragMove(view: View, x: Float, y: Float) {
        if (dragDownTime == 0L) return
        val dt = dragDownTime
        val elapsed = SystemClock.uptimeMillis() - dragDownTime
        view.post {
            runCatching {
                dispatchTouch(view, MotionEvent.ACTION_MOVE, dt, elapsed, x, y)
            }
        }
    }

    fun injectDragUp(view: View, x: Float, y: Float) {
        if (dragDownTime == 0L) return
        val dt = dragDownTime
        val elapsed = SystemClock.uptimeMillis() - dragDownTime
        dragDownTime = 0L
        view.post {
            runCatching {
                dispatchTouch(view, MotionEvent.ACTION_UP, dt, elapsed, x, y)
            }
        }
    }

    /**
     * 注入一次滚轮事件（ACTION_SCROLL + AXIS_VSCROLL/HSCROLL）。
     * dx/dy 为手指双指滑动增量（像素）：双指上滑（dy<0）= 向下翻页，
     * 与触控板自然方向一致。每 60px 折算 1 个滚轮 tick。
     *
     * 派发目标：指针正下方的最深子 View（WebView 等原生 View 自带滚轮
     * 处理，直达最稳）；不是 WebView 则回根 View 走 Compose 滚动管线。
     */
    fun injectScroll(view: View, x: Float, y: Float, dx: Float, dy: Float) {
        if (abs(dx) < 0.4f && abs(dy) < 0.4f) return
        val now = SystemClock.uptimeMillis()
        // 滚轮事件同样携带合成指针 id（View 层路由器按 id < 99 拦截真实指针）
        val p = MotionEvent.PointerProperties().apply {
            id = INJECTED_POINTER_ID
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val c = MotionEvent.PointerCoords().apply {
            // this. 前缀必须显式：函数参数 x/y 会遮蔽接收者同名成员
            this.x = x; this.y = y; this.pressure = 1f; this.size = 1f
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx / 60f)
            setAxisValue(MotionEvent.AXIS_VSCROLL, dy / 60f)
        }
        view.post {
            try {
                val ev = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_SCROLL,
                    1, arrayOf(p), arrayOf(c),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
                )
                val target = deepestViewAt(view, x, y)
                if (target is WebView) {
                    // 坐标换算到目标 View 局部
                    val rootLoc = IntArray(2); view.getLocationInWindow(rootLoc)
                    val tgtLoc = IntArray(2); target.getLocationInWindow(tgtLoc)
                    ev.offsetLocation(
                        -(tgtLoc[0] - rootLoc[0]).toFloat(),
                        -(tgtLoc[1] - rootLoc[1]).toFloat()
                    )
                    target.dispatchGenericMotionEvent(ev)
                } else {
                    // 滚轮属通用（generic）motion 事件：Compose 滚动组件按鼠标
                    // 滚轮路径响应
                    view.dispatchGenericMotionEvent(ev)
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 单指触摸注入（合成指针 id = INJECTED_POINTER_ID，toolType = MOUSE）。
     * toolType 用 MOUSE：Compose 的 PointerInputChange.type = PointerType.Mouse，
     * Compose 层手势据此（type == PointerType.Touch）与真实手指区分。
     * v2.20 起注入流与真实手指在传输层已互斥（真实手指被 TrackpadRouter
     * 拦截），注入流在 Compose 管线中独占，不再有设备切换竞态。
     */
    private fun dispatchTouch(view: View, action: Int, downTime: Long, eventDelta: Long, x: Float, y: Float) {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = INJECTED_POINTER_ID
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; this.pressure = 1f; this.size = 1f
            }
        )
        dispatchTouchAt(
            view,
            MotionEvent.obtain(
                downTime, downTime + eventDelta, action, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            ),
            x, y
        )
    }

    /**
     * 把事件派发给包含 (x,y) 的【最顶层窗口】。
     * Compose 的 Popup / Dialog 渲染在独立窗口里，只向主窗口注入时点不到
     * 对话框内容 —— 这里反射枚举本进程全部窗口根（WindowManagerGlobal.mViews，
     * 后加入者 z 序更高），命中最顶层的一个派发（坐标同步换算）；反射失败
     * 回退主窗口。真实多窗口路由语义与系统一致：点在弹窗上 → 弹窗处理；
     * 点在弹窗外 → 主窗口处理。
     */
    private fun dispatchTouchAt(view: View, event: MotionEvent, x: Float, y: Float) {
        try {
            val mainLoc = IntArray(2); view.getLocationOnScreen(mainLoc)
            val roots = allWindowRoots(view)
            var i = roots.size - 1
            while (i >= 0) {
                val root = roots[i]
                val loc = IntArray(2); root.getLocationOnScreen(loc)
                val lx = x + mainLoc[0] - loc[0]
                val ly = y + mainLoc[1] - loc[1]
                if (lx >= 0f && ly >= 0f && lx < root.width && ly < root.height) {
                    event.offsetLocation(lx - event.x, ly - event.y)
                    root.dispatchTouchEvent(event)
                    return
                }
                i--
            }
        } catch (_: Exception) {
        }
        view.dispatchTouchEvent(event)
    }

    /** 本进程全部已 attach 的窗口根 View（主窗口 + Popup/Dialog 子窗口） */
    private fun allWindowRoots(fallback: View): List<View> {
        return try {
            val global = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getInstance").invoke(null)
            val views = global.javaClass.getDeclaredField("mViews")
                .apply { isAccessible = true }
                .get(global) as? List<*>
            val list = views?.filterIsInstance<View>()
                ?.filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
                .orEmpty()
            if (list.isEmpty()) listOf(fallback) else list
        } catch (_: Exception) {
            listOf(fallback)
        }
    }

    /**
     * 找到根 View 坐标系下 (x,y) 处的最深可见子 View（供滚轮直达 WebView）。
     * 仅按 left/top/scroll/translation 平移换算（本应用视图树无旋转/缩放）。
     */
    private fun deepestViewAt(root: View, x: Float, y: Float): View? {
        var current: View = root
        var cx = x
        var cy = y
        while (current is ViewGroup) {
            val g = current
            var child: View? = null
            var i = g.childCount - 1
            while (i >= 0) {
                val c = g.getChildAt(i)
                if (c.visibility == View.VISIBLE && c.width > 0 && c.height > 0) {
                    val lx = cx - c.left + g.scrollX - c.translationX
                    val ly = cy - c.top + g.scrollY - c.translationY
                    if (lx >= 0f && ly >= 0f && lx < c.width && ly < c.height) {
                        child = c
                        cx = lx
                        cy = ly
                        break
                    }
                }
                i--
            }
            if (child == null) break
            current = child
        }
        return if (current === root) null else current
    }
}
