package com.linbox.core.window

import android.util.Log
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import com.linbox.core.input.TrackpadRouter

/**
 * v3.2.2（用户视频实锤）—— View 层"点击置前"兜底：不依赖 Compose 命中测试。
 *
 * ## 背景（三轮修复无效的根因复盘）
 *
 * 用户视频（2026-10-30）实测：桌面同时开『浏览器』『媒体播放器』『扫雷』
 * 三个 **LinBox 内置 Compose 窗口**，反复点击后面窗口的可见部分 —— 窗口
 * **从不置前、也完全无法操作**，某次点击甚至穿透窗口落到了桌面图标上
 * （弹出了扫雷）。此前两轮修复：
 *
 * - v3.1.7：WindowChrome 根节点挂 `pointerInput + awaitFirstDown(Initial)`
 *   观察器调 `wm.focus` —— Compose 命中测试链路上的某一环在设备上没有
 *   兑现（观察器未触发），至今无日志可证其曾生效；
 * - v3.2.1：`X11WindowOps`（X 协议层 click-to-raise）—— 只覆盖 X11 桌面
 *   窗口里的 **wine 会话**，根本管不到 Compose 桌面自己的内置应用窗口
 *   （视频里的浏览器/媒体播放器/扫雷全部是后者）—— **修错了层**。
 *
 * 而视频第 15~16 秒"任务栏点击最小化/还原"正常 → WindowManager →
 * WindowHost 的重组链路是好的；坏的只是"触摸到不了窗口"这一段。
 *
 * ## 方案：View 层几何直判
 *
 * Android 体系里 `Activity.dispatchTouchEvent` 是**所有真实触摸的必经
 * 入口**（先于 Compose、先于任何 View、先于 TrackpadRouter 拦截）；
 * 触控板模式的合成点击则在 `TrackpadController.tapClick`（指针处）与
 * `TrackpadRouter.endSession`（手指轻点处）——在这三处直接按**窗口几何**
 * 判定落点、调用 `WindowManager.focus` 置前：
 *
 * 1. **几何来源**：WindowChrome / WinDialogFrame 用 `onGloballyPositioned`
 *   把各自的窗口坐标 Rect 上报到本对象（同桌面图标的 iconBounds 模式），
 *   拖动/缩放/最大化时随布局实时更新，离开组合即注销；
 * 2. **置前规则**（Windows 语义）：落点处 zIndex 最高的**可见**窗口若不是
 *   当前顶层 → focus 置前；落点在弹窗上 → 不置前（弹窗层本就在窗口层
 *   之上）；落点在任务栏/桌面空白 → 无窗口命中，零动作；
 * 3. **双模式覆盖**：
 *    - 触摸模式：MainActivity 入口，手指按下即置前；
 *    - 触控板模式：合成点击落在**指针处**（tapClick / 长按拖拽起点），
 *      同时**手指轻点的屏幕位置**若落在非顶层窗口可见部分也一并置前
 *      （用户直觉"点谁谁置前"，与指针处点击互不冲突）。
 *
 * 绝不消费事件 —— 只读取坐标做置前判断，触摸照常走原分发链路。
 * v3.1.7 挂在 WindowChrome 上的 Compose 观察器已随之移除：View 层 hook
 * 在每条路径上都更早触发、语义完全覆盖，且保留两者会在触控板模式下
 * 互相打架（手指轻点置前的窗口被指针处注入点击命中的旧顶层再顶回去）。
 */
object WindowFocusHook {

    private const val TAG = "WindowFocusHook"

    /** 应用窗口几何（窗口坐标 px）：WindowChrome.onGloballyPositioned 上报。 */
    private val windowBounds = java.util.concurrent.ConcurrentHashMap<String, Rect>()

    /** 弹窗几何（窗口坐标 px）：WinDialogFrame 上报 —— 落点在弹窗上不置前底下窗口。 */
    private val dialogBounds = java.util.concurrent.ConcurrentHashMap<String, Rect>()

    // ------------------------------------------------------------------
    // 几何上报（Compose 侧）
    // ------------------------------------------------------------------

    /** 应用窗口上报/注销几何（rect = null 表示注销 —— 窗口关闭/最小化离开组合）。 */
    fun reportWindow(id: String, rect: Rect?) {
        if (rect == null) windowBounds.remove(id) else windowBounds[id] = rect
    }

    /** 弹窗上报/注销几何。 */
    fun reportDialog(id: String, rect: Rect?) {
        if (rect == null) dialogBounds.remove(id) else dialogBounds[id] = rect
    }

    /**
     * Compose 1.6.x 无 LayoutCoordinates.boundsInWindow 成员（降级为顶层扩展，
     * 扩展体内不可隐式命中）—— 与 TrackpadRouter.boundsInWindowCompat 同源：
     * localToWindow(Offset.Zero) + size 手动构造。
     */
    fun boundsOf(coords: LayoutCoordinates): Rect {
        val pos = coords.localToWindow(Offset.Zero)
        val s = coords.size
        return Rect(pos.x, pos.y, pos.x + s.width, pos.y + s.height)
    }

    // ------------------------------------------------------------------
    // 入口一：MainActivity.dispatchTouchEvent（真实手指，必经之路）
    // ------------------------------------------------------------------

    /**
     * 真实手指按下 → 落点处置前。挂在 TrackpadRouter **之前**（触控板模式
     * 下真实手指会被路由器整流拦截，之后的代码看不到）。
     *
     * **仅触摸模式**在此置前（Windows 语义：按下即激活）。触控板模式下
     * 手指是"板面"不是屏幕位置 —— 滑动也以 ACTION_DOWN 开头，若在 DOWN
     * 置前会让"滑指针"过程中窗口乱跳；触控板模式的手指置前改由路由器的
     * **轻点判定**（endSession：< 320ms 且未移动）触发，滑动/双指/长按均
     * 不置前。
     *
     * 绝不消费事件（返回值仅签名友好，恒 false）。
     */
    fun onMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return false
        if (TrackpadRouter.enabled) return false   // 触控板模式：由轻点判定入口置前
        raiseAt(ev.x, ev.y)
        return false
    }

    // ------------------------------------------------------------------
    // 入口二：任意坐标处置前（触控板合成点击 / 手指轻点复用）
    // ------------------------------------------------------------------

    /**
     * 把 (x,y)（窗口坐标 px）处 zIndex 最高的可见窗口置前（若非当前顶层）。
     * 落点在弹窗上 → 零动作；无窗口命中 → 零动作。
     */
    fun raiseAt(x: Float, y: Float) {
        runCatching {
            val p = Offset(x, y)
            // 弹窗层在窗口层之上：点弹窗 = 操作弹窗，不置前底下的应用窗口
            if (dialogBounds.values.any { it.contains(p) }) return
            // 浮层直通区（虚拟键盘面板 / 手柄工具条 / 手柄设置窗）：敲键盘
            // 按键不应把键盘底下的窗口顶到前面
            if (TrackpadRouter.isPassthrough(x, y)) return
            // v3.2.23（用户问题 4）：手柄 UI（元素/悬浮球/展开菜单）直判 ——
            // 不依赖直通区登记时机（悬浮球在窗口树另一分支布局，登记有
            // 时序差）；按手柄按键绝不应把手柄底下的窗口顶到前面。
            if (com.linbox.core.input.gamepad.GamepadController.isOverPadUi(x, y)) return

            val wm = WindowManager.get()
            val windows = wm.windows.filter { it.isVisible }
            if (windows.size < 2) return   // 单窗口/零窗口无需置前

            val target = windows
                .sortedBy { it.zIndex }
                .lastOrNull { w -> windowBounds[w.id]?.contains(p) == true }
                ?: return
            val top = windows.maxByOrNull { it.zIndex }
            if (top == null || top.id == target.id) return
            wm.focus(target.id)
            Log.i(TAG, "View层点击置前: 「${target.title}」(${x.toInt()},${y.toInt()}) " +
                "← 此前顶层「${top.title}」")
        }
    }
}
