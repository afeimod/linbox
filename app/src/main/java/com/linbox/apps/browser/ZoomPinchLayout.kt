package com.linbox.apps.browser

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.linbox.core.input.INJECTED_POINTER_ID
import com.linbox.core.input.gamepad.GamepadController
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v2.14.9：双指缩放仲裁容器 —— 弥补 Chromium 无法缩小到 100% 以下的引擎级限制。
 *
 * ## 方案演进（为什么是"动态 viewport 宽画布"）
 * - v2.14.5 加载期宽画布 viewport：改变默认排版（用户否决：4399 首页按超宽
 *   画布排版整页压扁、初始即 30% 全览）；
 * - v2.14.6 WebView View 变换（scaleX/Y）：内容不随变换矩阵缩放、仅被缩小后
 *   的边界裁切（否决；强制离屏层在 Compose 嵌套下灰屏，v2.8 已录屏证实）；
 * - v2.14.7 JS 注入 document.body.style.zoom：缩放生效但内容钉死左上角、
 *   右侧大片空白（否决）。根因：zoom 只缩渲染不改 window.innerWidth ——
 *   H5 游戏画布按 JS innerWidth 定尺寸，坐标系扩宽后画布不重排；
 * - v2.14.8 body.zoom + 钉宽居中：对称 letterbox —— 用户实测否决（截图
 *   实证：游戏缩成中间窄条、两侧大片灰白 WebView 底色，"没有整体缩放、
 *   网页没有全屏显示"）。结构性缺陷：满宽页面等比缩小后，空出的区域
 *   只能露出 WebView 底色 —— 钉左（v2.14.7）还是居中（v2.14.8）只是
 *   决定灰边长在哪，无法消灭灰边；
 * - v2.14.9（当前）：只在【缩小域激活期间】动态改写 viewport meta：
 *   width = 原排版宽 / z，同时 minimum-scale = maximum-scale =
 *   接管时页面 scale × z。语义与桌面 Chrome Ctrl+减号 完全一致 ——
 *   排版视口按 1/z 变宽，页面 scale 被钳到"窗口宽/画布宽"= z 相对比例：
 *   * 整页所有内容等比变小（整体缩放）；
 *   * 画布宽 = 窗口宽 / z × z = 窗口宽 —— 任何比例下都【精确铺满窗口宽】，
 *     letterbox 在物理上不可能出现；
 *   * window.innerWidth/innerHeight 原生同步变宽 → H5 游戏的 resize 监听
 *     拿到真实新尺寸自动重排画布（v2.14.7/8 的"画布按 innerWidth 定尺寸
 *     不重排"根因被原生消除，无需伪造任何 API）；
 *   * position:fixed 游戏全屏层以视口为包含块自然跟随（无需 v2.14.8 的
 *     identity transform 包含块 hack）；
 *   * 命中测试/滚动/Chromium 合成全部原生路径。
 *   回 100% 时逐字还原原 meta（自建的整段移除），页面 scale 被 Chromium
 *   按"窗口宽/画布宽"地板自动钳回初始满宽态。100% 默认态零注入、零
 *   viewport 改写，排版与旧版完全一致 —— v2.14.5 改默认排版的教训不重蹈：
 *   注入只发生在用户主动捏合缩小之后，且随回 100%/导航即时撤销。
 *
 * ## 两段式缩放域
 * - 【100% 以上】放大域：原生 Chromium 双指捏合（排版重排/文字回流），
 *   本类完全不干预，手势透传给 WebView；
 * - 【30% ~ 100%】缩小域：本类拦截手势流，把指距增量映射为 z 并节流改写
 *   viewport meta（每步 = 一次 meta 改写 + 整页重排，桌面 Chrome 缩放的
 *   同款代价，由 80ms 节流兜底）。
 *
 * ## 手势仲裁（onInterceptTouchEvent）——与 v2.14.6~8 相同的已验证逻辑
 * 双指落下后先【观察】不拦截（第二指落下瞬间不抢占，避免断流缩小态下
 * 运行的双拇指游戏）：
 * - 100% 态、指距拉大（放大意图）→ 永久放行本次手势，原生捏合处理；
 * - 100% 态、指距缩小且页面 scale 仍在变化 → 原生正在缩小（固定宽画布
 *   页地板<100% 的场景），放行；监测的是【最近】响应 —— 原生从 250% 缩到
 *   自身地板后 scale 冻结，同一次手势内无缝接管进缩小域；
 * - 100% 态、指距缩小 ≥15% 且页面 scale 连续 2 帧无变化（原生已在地板上
 *   无法响应）→ 拦截接管，进入缩小域；
 * - 已处于缩小域（viewZoom<1）时，指距任一方向实质变化（≥5%）即接管
 *   （张开回 100%，收缩到 30%）；静止双指不接管（游戏不受扰）。
 *   缩小域内 meta 已把 minimum-scale=maximum-scale 钳死，原生捏合天然
 *   失效，不存在"页面 scale × 注入缩放"双系统叠加的混乱态。
 * 单指点击/滚动/长按全程不拦截，原样透传。
 *
 * ## 注入节流
 * evaluateJavascript 是跨 JNI 异步调用，逐帧注入（60+/s）会排队堆积
 * （GameBox 在 mousemove 分发上的教训）。捏合过程中按【间隔 ≥80ms 且
 * 增量 ≥0.02】节流；手势结束/换绑恢复时强制精确应用一次。
 *
 * ## 附带行为
 * - 缩放状态存于 [BrowserTab.viewZoom]：切标签恢复（重绑后重注入 meta）、
 *   导航（onPageStarted）重置回 100%（新文档天然无注入，同文档锚点导航
 *   由引擎的 RESET_SCRIPT 还原原 meta）；
 * - 缩放值域 [MIN_ZOOM=0.3, 1.0]，达 100% 时按快照还原 meta 并清 __az；
 * - 窗口尺寸变化（旋转/拖拽调整窗口）会使注入的 width=原宽/z 与新窗口
 *   失配（画布不再精确铺满）→ onSizeChanged 直接重置回 100%，用户重新
 *   捏合即可按新尺寸缩放；
 * - 极端缩小 + 超宽原画布时 width 可能触及 Chromium 画布宽上限（约
 *   10000px），此时画布被钳制、页面变为可横向滚动的宽页（桌面 Chrome
 *   同款行为），不再有灰边。
 */
class ZoomPinchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 缩小地板（用户需求：支持缩到 30%） */
    var minViewZoom: Float = MIN_ZOOM

    /** 缩放变化回调（同步写回 BrowserTab.viewZoom） */
    var onZoomChanged: ((Float) -> Unit)? = null

    private var webView: WebView? = null

    // ===== v2.16.3：鼠标视角（3D 视角旋转）旁路观察 =====
    // 参照 GameBox：不拦截触摸（页面照常收到 tap/click/touch，游戏自身
    // 触摸逻辑不受影响），把单指拖动增量喂给 View3dController；拖动时
    // 页面滚动/拖选由注入的 CSS（body overflow:hidden + canvas
    // touch-action:none）抑制。
    // v2.19.2：采集点自 onInterceptTouchEvent 移至 dispatchTouchEvent
    // 入口的 feedLookFromEvent（不依赖触摸目标存在，且在剥离后的
    // 自由指干净流上计算，与手柄彻底解耦）。
    /** 本手势是否处于视角累计中（超 slop 后启动，多指/抬手结束） */
    private var lookActive = false
    /** 上帧采样点（view 坐标） */
    private var lookLastX = 0f
    private var lookLastY = 0f
    /** 触摸 slop（超过才开始累计，避免点击时的微动转视角） */
    private val lookSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ===== v2.16.4：手柄共存 —— 从事件流剥离"手柄指针" =====
    // 根因（compose-ui 1.6.8 PointerInteropFilter.dispatchToView）：
    // "任一 PointerInputChange 被消费"就停止向本容器派发，且派发的也是
    // 全指针原始 MotionEvent —— 虚拟手柄元素按住时必然消费自己的指针，
    // 导致：① 手柄移动时网页收不到另一根手指的拖动/点击（3D 视角旋转
    // 失灵的根因）；② 即使派发成功，手柄幽灵指针也会扰乱 Chromium 手势
    // 判定与本类多指仲裁。
    // 修复：在本 View 入口把"落点在手柄元素内"的指针（按 pointerId 记忆）
    // 从 MotionEvent 中剥离，WebView 收到与"手柄不存在"完全一致的干净流
    // （等效 GameBox 的原生 View 分指：手柄按钮和 WebView 各收各的指针）。
    // 手柄指针的 DOWN 落在手柄元素上（不同命中子树）不会进入本视图，
    // 只有其 MOVE/UP 会随自由指针的事件捎带进来 —— 一并剥离。
    /** 已判定为手柄指针的 pointerId（仅 UI 线程） */
    private val padPointerIds = HashSet<Int>()
    /** 已判定为自由指针（非手柄）的 pointerId（仅 UI 线程） */
    private val freePointerIds = HashSet<Int>()
    /** 本 View 在窗口内的位置缓存（避免每个事件分配） */
    private val windowLoc = IntArray(2)
    /**
     * v2.16.5：是否已向 WebView 派发未结束的触摸流（DOWN 后未 UP/CANCEL）。
     * 背景（用户实测）：旋转手先按下 → 手柄可用；松开旋转手后，手柄仍按着
     * 时再滑动 → 失效。根因：此时新手指到达本容器的事件是 ACTION_POINTER_DOWN
     * （窗口流里手柄手指还按着），而本容器上一条 Chromium 流已经 UP 结束、
     * ViewGroup 的 mFirstTouchTarget 已清空 —— ViewGroup 规则是"无 DOWN 直接触
     * 目标的 POINTER_DOWN/MOVE 一律丢弃（intercepted=true 直落自身 onTouchEvent）"，
     * onInterceptTouchEvent 不会被调用 → 视角增量丢失、WebView 也收不到触摸。
     * 修复：无活跃流时把剥离后的 POINTER_DOWN 升级为 ACTION_DOWN 开新流
     * （等效 GameBox 原生 View 每指独立起流）。
     *
     * v2.19.2 补充（"必须先点一下屏幕才能转视角"根因之一）：
     * 分类记忆（padPointerIds/freePointerIds）原来只在 raw ACTION_DOWN/UP/CANCEL
     * 清空 —— 手柄手指按住期间，自由指事件全是 POINTER_DOWN/POINTER_UP，永不
     * 清理；且手柄指针的 UP 走手柄命中路径、不经过本容器。Android 会复用已
     * 抬起指针的 id：复用 id 的新自由指继承陈旧"手柄指"分类 → 事件被剥离吞掉
     * （页面什么都收不到、视角不动），直到一次裸点击（raw ACTION_DOWN）触发
     * 清空 —— 与用户实测完全吻合；反向残留则让手柄幽灵指针泄漏进 WebView 流。
     * 修复见 dispatchTouchEvent 内的分类修剪（retainAll 仍按着的指针）。
     *
     * v2.19.3 补充（"视角旋转还是不能和手柄一起用"根因之三 —— 捏合误接管）：
     * onInterceptTouchEvent/onTouchEvent 收到的是【未剥离的全指针原始事件】，
     * 摇杆/按钮按住时其幽灵变化也随窗口流捎带进来。原仲裁用 pointerCount≥2
     * 判捏合、spanOf(ev) 算指距 —— "摇杆 + 单根旋转指"被当成双指捏合，
     * 旋转中两指距离一变（±5%/15% 阈值）就 beginPinch 接管：WebView 收
     * ACTION_CANCEL 断流、MODE_PINCH 阻断 feedLookFromEvent 采集，且接管
     * 还会误改页面缩放。表现为旋转与手柄同时使用时随机失灵（越用越容易
     * 触发，因为接管过一次页面进了缩小域，阈值收紧到 ±5%）。修复：捏合
     * 全链路（观察进入/MOVE 判定/重基线/接管 seed/缩放过程）改用
     * freeIndicesOf/freeSpanOf 只统计自由指，自由指不足 2 根一律不接管。
     */
    private var streamActive = false

    // ===== 手势状态机 =====
    private var mode = MODE_IDLE
    /** 本次手势是否已判定为原生域（放大/原生可响应的缩小） */
    private var passThrough = false
    /** 观察期基线指距 */
    private var baseSpan = 0f
    /** 观察域：true = 已处于注入缩小域（viewZoom<1），指距任一方向
     *  实质变化即接管（双向）；false = 100% 态，缩小方向才可能接管 */
    private var observeViewDomain = false
    /** 最近一次采样的页面 scale（判断原生是否仍在响应） */
    private var lastScale = 0f
    /** 指距缩小超阈值且页面 scale 连续无变化的采样数 */
    private var deadSamples = 0
    /** 接管瞬间的指距与缩放（增量计算基准） */
    private var seedSpan = 0f
    private var seedZoom = 1f
    /** 指数集变化（PINCH 中途抬指/加指）后待重置基准 */
    private var rebasePending = false

    /** 当前缩放比例（与 tab.viewZoom 同步） */
    private var viewZoom = 1f

    // ===== JS 注入节流状态 =====
    /** 上次实际注入 viewport meta 的时间戳（uptimeMillis） */
    private var lastJsApplyAt = 0L
    /** 上次实际注入的缩放值 */
    private var lastJsZoom = 1f

    /**
     * 绑定（或换绑）本容器承载的 WebView。重复调用安全：
     * 换绑时先把 WebView 从旧容器剥离（弹窗临时挂载/标签切换认领场景）。
     * restoreZoom 同步到内部状态（手势仲裁据此判断当前域），<100% 时
     * 立即重注入 viewport meta（切标签恢复缩放态，快照 __az 随页面存续，
     * 原 meta/原画布宽/接管 scale 均从快照复用，无需重新测量）。
     */
    fun setWebView(target: WebView?, restoreZoom: Float = 1f) {
        viewZoom = restoreZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (webView === target) {
            // 同绑定：AndroidView 的 update 块每次重组都会走到这里，
            // 仅在缩放值确有变化时才注入（如导航重置 0.5→1），避免
            // 无意义的 JNI 调用churn
            if (target != null && abs(viewZoom - lastJsZoom) > 0.001f) {
                applyZoom(target, viewZoom, force = true)
            }
            return
        }
        webView?.let { old ->
            if (old.parent === this) removeView(old)
        }
        webView = target
        if (target != null) {
            (target.parent as? ViewGroup)?.removeView(target)
            addView(
                target,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            applyZoom(target, viewZoom, force = true)
        }
    }

    /** 组合销毁（onRelease）时解除引用，防泄漏；WebView 生命周期由引擎管理 */
    fun detachWebView() {
        // v2.14.10：先把 WebView 从本容器剥离（彻底脱离视图树），
        // 避免销毁后的 WebView 仍挂在已废弃容器上阻碍 GC
        webView?.let { if (it.parent === this) removeView(it) }
        webView = null
    }

    /** 当前缩放比例（供恢复/展示用） */
    fun currentZoom(): Float = viewZoom

    /**
     * v2.14.9：窗口尺寸变化（旋转/LinBox 窗口拖拽调整）时，注入中的
     * width=原宽/z 已与新窗口失配（画布不再精确铺满、比例漂移）——
     * 直接重置回 100%（还原 meta，Chromium 钳回初始满宽态），
     * 用户重新捏合即按新尺寸获得精确铺满的缩放。
     * oldw/oldh>0 守卫：首次布局（0→实际尺寸）不触发。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh) &&
            viewZoom < 1f && webView != null
        ) {
            viewZoom = 1f
            webView?.let { wv ->
                applyZoom(wv, 1f, force = true)
                onZoomChanged?.invoke(1f)
            }
        }
    }

    /**
     * v2.16.4：手柄指针剥离入口。
     * - 手柄未显示（无命中矩形）→ 原样直通，零行为变化；
     * - 事件不含手柄指针 → 原样直通（含双指捏合等既有手势）；
     * - 含手柄指针 → 按上表规则重映射 action 并剥离后派发给 WebView；
     *   无自由指针时吞掉事件（不派发，Chromium 不感知手柄指针）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!GamepadController.hasElementHits()) {
            // v2.19.2：视角采集移到任何事件必经的 dispatchTouchEvent 入口
            feedLookFromEvent(ev)
            return super.dispatchTouchEvent(ev)
        }
        // 指针分类生命周期：窗口级 ACTION_DOWN = 全新手势流（此刻不可能有
        // 任何指针按着，否则窗口 action 会是 POINTER_DOWN）→ 记忆的旧分类
        // 全部过期（手柄手指的 UP 不经过本视图，其 pointerId 之后会被新
        // 手指复用，不清理会把新自由指误判成手柄指而吞掉整个点击）；
        // UP/CANCEL = 流结束，同样清空。v2.16.5 补上 DOWN 清理。
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                padPointerIds.clear()
                freePointerIds.clear()
            }
        }
        // v2.19.2：分类记忆修剪（"必须先点一下屏幕"根因一，见 streamActive 注释）。
        // 手柄指针只要还按着，其幽灵 change 必随自由指事件捎带进来（Compose
        // 事件携带全窗口指针），因此"不在本事件按着"的 id 一律视为已抬起遗忘，
        // 复用 id 的新指针从源头不可能误判。 liftedIdx = POINTER_UP 的抬起指。
        if (padPointerIds.isNotEmpty() || freePointerIds.isNotEmpty()) {
            val live = HashSet<Int>(ev.pointerCount)
            if (ev.actionMasked != MotionEvent.ACTION_UP &&
                ev.actionMasked != MotionEvent.ACTION_CANCEL
            ) {
                val liftedIdx = if (ev.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    ev.actionIndex
                } else -1
                for (i in 0 until ev.pointerCount) {
                    if (i != liftedIdx) live.add(ev.getPointerId(i))
                }
            }
            padPointerIds.retainAll(live)
            freePointerIds.retainAll(live)
        }
        // 收集本事件中手柄/自由指针的下标（新出现的 pointerId 按落点判定，
        // 判定结果按 id 记忆 —— 手指拖进/拖出手柄区域不改变归类）
        getLocationInWindow(windowLoc)
        var containsPad = false
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            if (!padPointerIds.contains(id) && !freePointerIds.contains(id)) {
                // v2.19.4：触控板注入指针（id ≥ 99，点击落在指针位置）一律
                // 归类为自由指 —— 虚拟鼠标点击的是“指针下方的内容”，即使
                // 该位置恰好被手柄元素覆盖也不能被剥离吞掉（否则指针悬停
                // 在摇杆/按钮区域时触控板点击全部失效）。手柄元素只由真实
                // 手指直接按压触发。
                val overPad = if (id >= INJECTED_POINTER_ID) {
                    false
                } else {
                    GamepadController.isOverPadElement(
                        windowLoc[0] + ev.getX(i), windowLoc[1] + ev.getY(i)
                    )
                }
                if (overPad) padPointerIds.add(id) else freePointerIds.add(id)
            }
            if (!containsPad && padPointerIds.contains(id)) containsPad = true
        }
        if (!containsPad) {
            // 直通路径也要维护流状态（后续剥离路径的 POINTER_DOWN→DOWN
            // 升级判断依赖它）：DOWN 起流、UP/CANCEL 收流
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> streamActive = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> streamActive = false
            }
            // v2.19.2：视角采集（直通流 = 无手柄指针参与的原始流）
            feedLookFromEvent(ev)
            return super.dispatchTouchEvent(ev)
        }

        // ===== 剥离路径：按 action 重映射 =====
        val freeIndices = ArrayList<Int>(ev.pointerCount)
        for (i in 0 until ev.pointerCount) {
            if (!padPointerIds.contains(ev.getPointerId(i))) freeIndices.add(i)
        }
        if (freeIndices.isEmpty()) {
            // 只剩手柄指针：无 Chromium 流可维护，吞掉。
            // 自由流（如有）此刻已无自由指针按着，同步收流，
            // 防 streamActive 悬挂导致后续自由指 DOWN 被误判为已有活跃流。
            streamActive = false
            return true
        }
        val cleanedAction: Int
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 新落指针是手柄指针 → 对 Chromium 等效无新指针：维持自由指流 MOVE；
                // 新落指针是自由指针：
                //   有活跃流 → POINTER_DOWN（actionIndex 换算到剥离后数组）；
                //   无活跃流 → v2.16.5 关键修复：升级为 ACTION_DOWN 开新流。
                //   （上一条流已 UP、ViewGroup 无触摸目标，POINTER_DOWN 是
                //   死流：不进拦截器、不下发 WebView —— "松开旋转手后，手柄
                //   按住时再滑动失效"的根因）
                val newId = ev.getPointerId(ev.actionIndex)
                cleanedAction = if (padPointerIds.contains(newId)) {
                    MotionEvent.ACTION_MOVE
                } else if (streamActive) {
                    actionOf(
                        MotionEvent.ACTION_POINTER_DOWN,
                        freeIndices.indexOf(ev.actionIndex)
                    )
                } else {
                    MotionEvent.ACTION_DOWN
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = ev.getPointerId(ev.actionIndex)
                if (padPointerIds.contains(liftedId)) {
                    // 抬起的是手柄指针：Chromium 流无变化 → MOVE
                    cleanedAction = MotionEvent.ACTION_MOVE
                } else {
                    // 抬起的是自由指针：仍有剩余 → POINTER_UP；否则收尾 UP
                    cleanedAction = if (freeIndices.size > 1) {
                        actionOf(MotionEvent.ACTION_POINTER_UP, freeIndices.indexOf(ev.actionIndex))
                    } else {
                        MotionEvent.ACTION_UP
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (padPointerIds.contains(ev.getPointerId(ev.actionIndex))) {
                    // UP 指针是手柄指针（自由指针早已经 POINTER_UP 收尾）→ 吞掉；
                    // 防御性收流（正常时序下流已结束，此处兜底防状态悬挂）
                    streamActive = false
                    return true
                }
                cleanedAction = ev.actionMasked
            }
            else -> cleanedAction = ev.actionMasked  // DOWN/MOVE 原样
        }
        // v2.16.5：流状态簿记（与剥离后事件的 action 一致）：DOWN 起流、
        // UP/CANCEL 收流（掩码取低 8 位，抹掉 POINTER_*/INDEX 高位）
        when (cleanedAction and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> streamActive = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> streamActive = false
        }
        val cleaned = buildStrippedEvent(ev, freeIndices, cleanedAction)
        // v2.19.2：视角采集（剥离后的自由指干净流 —— 与手柄彻底解耦）
        feedLookFromEvent(cleaned)
        return try {
            super.dispatchTouchEvent(cleaned)
        } finally {
            cleaned.recycle()
        }
    }

    /**
     * v2.19.2：3D 视角（鼠标视角模式）增量采集 —— 自 dispatchTouchEvent
     * 入口调用（原在 onInterceptTouchEvent：依赖 WebView 触摸目标存在，
     * interop 过滤器 stopDispatching / 无目标 MOVE 等场景下永不触发，
     * "必须先点一下屏幕才能转视角"的根因之二）。
     *
     * 语义与原实现一致：旁路观察不拦截（页面照常收到 tap/click/touch），
     * 单指拖动超 slop 后把增量喂给 View3dController 合成
     * mousemove(movementX/Y)；多指（捏合仲裁优先）暂停，抬回单指可继续；
     * 捏合接管态（MODE_PINCH）不采集。传入剥离后的自由指干净流时同样适用。
     */
    private fun feedLookFromEvent(ev: MotionEvent) {
        if (!View3dController.enabled) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lookActive = false
                lookLastX = ev.x
                lookLastY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_PINCH) return
                if (ev.pointerCount >= 2) {
                    // 多指：暂停视角（捏合缩放仲裁优先）
                    lookActive = false
                } else if (lookActive) {
                    val dx = ev.x - lookLastX
                    val dy = ev.y - lookLastY
                    lookLastX = ev.x
                    lookLastY = ev.y
                    View3dController.accumulate(dx, dy)
                } else if (mode == MODE_IDLE && !passThrough) {
                    val dxTotal = ev.x - lookLastX
                    val dyTotal = ev.y - lookLastY
                    if (dxTotal * dxTotal + dyTotal * dyTotal > lookSlop * lookSlop) {
                        lookActive = true
                        lookLastX = ev.x
                        lookLastY = ev.y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lookActive = false
            }
        }
    }

    /** 组装带 actionIndex 的 pointer 事件（index 高位在 ACTION_ 上） */
    private fun actionOf(base: Int, index: Int): Int =
        (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) or base

    /**
     * 用原事件的自由指针子集构造剥离后的 MotionEvent。
     * PointerCoords 拷贝全部轴（x/y/pressure/toolType…）；历史批次不拷贝
     * （Chromium 对丢历史兼容良好，仅轨迹采样略粗）。
     * downTime 修正：POINTER_DOWN 升级为 ACTION_DOWN 时（自由指在摇杆按下
     * 之后才落下），原始 downTime 是摇杆的按下时刻 —— 若照搬，Chromium
     * 收到的首个 DOWN 的 downTime 会远早于 eventTime，可能被判为异常而忽略
     * 这条触摸流。此时用 eventTime 作为 downTime（该自由指此刻才真正落下）。
     */
    private fun buildStrippedEvent(
        ev: MotionEvent,
        freeIndices: List<Int>,
        action: Int
    ): MotionEvent {
        val n = freeIndices.size
        val props = Array(n) { MotionEvent.PointerProperties() }
        val coords = Array(n) { MotionEvent.PointerCoords() }
        for (j in 0 until n) {
            ev.getPointerProperties(freeIndices[j], props[j])
            ev.getPointerCoords(freeIndices[j], coords[j])
        }
        val downTime = if (action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_DOWN) {
            ev.eventTime
        } else {
            ev.downTime
        }
        return MotionEvent.obtain(
            downTime, ev.eventTime, action, n, props, coords,
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision,
            ev.deviceId, ev.edgeFlags, ev.source, ev.flags
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = MODE_IDLE
                passThrough = false
                deadSamples = 0
                baseSpan = 0f
                lastScale = 0f
                observeViewDomain = false
                // v2.19.2：视角旁路重置已移入 feedLookFromEvent（dispatchTouchEvent）
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // v2.19.3：只有 ≥2 根“自由指”才进入捏合观察 —— 手柄指针
                // （摇杆/按钮按住时捎带进来的幽灵变化）不算捏合指，
                // “摇杆 + 单根旋转指”不再被误判成双指捏合（原 pointerCount≥2
                // 判定是视角旋转与手柄冲突、随机 beginPinch 断流的根因）。
                if (freeIndicesOf(ev).size >= 2) {
                    if (passThrough) return false
                    // 不在第二指落下瞬间拦截 —— 缩小态下运行的双拇指游戏
                    // （两指静止/同步移动）不应被断流；观察指距实际变化后再接管
                    mode = MODE_OBSERVE
                    observeViewDomain = viewZoom < 1f
                    baseSpan = freeSpanOf(ev)
                    lastScale = webView?.scale ?: 0f
                    deadSamples = 0
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // v2.19.2：视角旁路采集已移入 feedLookFromEvent（在任何事件
                // 必经的 dispatchTouchEvent 入口、剥离后的干净流上计算）。
                // 原实现在此采集有两个缺陷：① 依赖 WebView 触摸目标存在，
                // interop 过滤器 stopDispatching 后 MOVE 无目标 → 永不触发；
                // ② 手柄指针剥离发生在 dispatchTouchEvent，此处收到的事件
                // 仍可能含幽灵指针，基线被污染。本方法只负责双指捏合仲裁。
                if (mode == MODE_OBSERVE && !passThrough) {
                    // v2.19.3：改用自由指指距（幽灵指针剔除）。自由指不足
                    // 2 根 = 本事件无捏合可能 → 退回 IDLE，绝不拦截断流。
                    val span = freeSpanOf(ev)
                    if (span <= 0f) {
                        mode = MODE_IDLE
                        return false
                    }
                    if (observeViewDomain) {
                        // 已在缩小域：指距任一方向实质变化即接管（放大向回
                        // 100%，缩小向 30%）。meta 已把 scale 钳死在
                        // minimum=maximum，原生不会响应，直接接管无双系统叠加
                        if (span < baseSpan * 0.95f || span > baseSpan * 1.05f) {
                            return beginPinch(ev)
                        }
                        return false
                    }
                    val cur = webView?.scale ?: 0f
                    // 页面 scale 是否仍在变化（原生捏合在响应中）。
                    // 监控"最近"而非"手势开始以来"的响应：原生从 250% 缩到
                    // 100% 地板后 scale 冻结，同一次手势可无缝接管进 30%。
                    val scaleMoved =
                        lastScale > 0f && cur > 0f && abs(cur - lastScale) > lastScale * 0.002f
                    lastScale = cur
                    if (span > baseSpan * 1.05f) {
                        // 放大意图 → 原生捏合域，本次手势不再介入
                        passThrough = true
                        mode = MODE_IDLE
                    } else if (span < baseSpan * 0.85f) {
                        if (scaleMoved) {
                            // 固定宽画布页（地板<100%）：原生正在缩小，放行
                            deadSamples = 0
                        } else {
                            deadSamples++
                            // 连续 2 帧指距缩小而页面 scale 纹丝不动
                            // = 原生已到地板 → 接管进入缩小域
                            if (deadSamples >= 2) return beginPinch(ev)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // v2.19.3：剩余“自由指”（排除抬起指、剔除幽灵指针）< 2 才收捏合
                val liftedIdx = ev.actionIndex
                val remaining = freeIndicesOf(ev, liftedIdx).size
                if (remaining < 2) {
                    mode = MODE_IDLE
                } else if (mode == MODE_OBSERVE) {
                    // 指数变化（3 指余 2）：重基线，不丢失后续合法捏合
                    baseSpan = freeSpanOf(ev, liftedIdx)
                    lastScale = webView?.scale ?: 0f
                    deadSamples = 0
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == MODE_PINCH) {
                    // 手势结束：强制精确应用最终 zoom 值（过程值是节流的）
                    finishPinch()
                }
                mode = MODE_IDLE
                passThrough = false
                deadSamples = 0
                observeViewDomain = false
                lookActive = false
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // v2.16.3：视角拖动为旁路观察（v2.19.2 起采集点移至
        // dispatchTouchEvent 的 feedLookFromEvent，不再依赖拦截器），
        // 触摸事件照常给 WebView，页面 tap/click/touch 全保留。
        // 本方法只处理被拦截接管的捏合缩放流（MODE_PINCH）。
        if (mode != MODE_PINCH) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // v2.19.3：捏合过程同样只看自由指指距（幽灵指针剔除），
                // 手柄按住时缩放值不再被幽灵指距污染；不足 2 根自由指跳过。
                val span = freeSpanOf(ev)
                if (span > 0f) {
                    if (rebasePending) {
                        // 指数集变化后的首个 MOVE：重置增量基准，防止
                        //（新/旧）指数集的 span 跳变引起缩放跳变
                        rebasePending = false
                        seedSpan = span
                        seedZoom = viewZoom
                    } else if (seedSpan > 0f) {
                        setZoom(seedZoom * (span / seedSpan))
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // PINCH 中途加指：下一帧重置基准
                rebasePending = true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // v2.19.3：剩余自由指不足 2 才算手势实质结束
                if (freeIndicesOf(ev, ev.actionIndex).size < 2) {
                    // 抬指后不足双指：手势实质结束，精确应用最终值
                    finishPinch()
                    mode = MODE_IDLE
                } else {
                    // 三指抬一指后捏合继续：下一帧重置基准
                    rebasePending = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishPinch()
                mode = MODE_IDLE
                rebasePending = false
            }
        }
        return true
    }

    /**
     * 从观察/空闲态切入缩小域（此刻 WebView 收到 ACTION_CANCEL）
     *
     * v2.19.3 注意：本方法只允许在【真实双自由指捏合】时调用 ——
     * 手柄幽灵指针参与的伪双指（摇杆 + 单根旋转指）绝不可走到这里，
     * 否则 WebView 被断流（CANCEL）、MODE_PINCH 阻断视角采集，
     * 表现为“3D 视角旋转和手柄不能同时用”。入口判定见
     * onInterceptTouchEvent 的 freeIndicesOf/freeSpanOf。
     */
    private fun beginPinch(ev: MotionEvent): Boolean {
        mode = MODE_PINCH
        // v2.19.3：基准必须与后续 onTouchEvent 的自由指指距同源
        //（freeSpanOf），否则手柄幽灵指针会让首个缩放步进跳变。
        seedSpan = freeSpanOf(ev)
        seedZoom = viewZoom
        rebasePending = false
        // 接管即应用一次（消除从原生地板到缩小域第一跳的延迟感）
        lastJsApplyAt = 0L
        return true
    }

    /** 手势结束：强制精确注入最终 zoom（过程值经过节流） */
    private fun finishPinch() {
        webView?.let { wv ->
            applyZoom(wv, viewZoom, force = true)
            onZoomChanged?.invoke(viewZoom)
        }
    }

    /** 捏合过程中设定目标缩放（节流改写 viewport meta 并回调写回标签） */
    private fun setZoom(zoom: Float) {
        viewZoom = zoom.coerceIn(minViewZoom, 1f)
        val now = android.os.SystemClock.uptimeMillis()
        val settled = abs(viewZoom - lastJsZoom) >= 0.02f
        if (now - lastJsApplyAt >= JS_INTERVAL_MS && settled) {
            webView?.let { wv ->
                applyZoom(wv, viewZoom, force = false)
                onZoomChanged?.invoke(viewZoom)
            }
        }
    }

    /** 任意两指间最大距离（span） */
    private fun spanOf(ev: MotionEvent): Float {
        val n = ev.pointerCount
        if (n < 2) return 0f
        var max = 0f
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dx = ev.getX(i) - ev.getX(j)
                val dy = ev.getY(i) - ev.getY(j)
                val d = sqrt(dx * dx + dy * dy)
                if (d > max) max = d
            }
        }
        return if (max > 1f) max else 1f
    }

    // ===== v2.19.3：捏合仲裁的“自由指”感知（手柄幽灵指针剔除） =====
    // 背景：摇杆/按钮按住时，其指针变化随窗口多点流捎带进本容器
    // （onInterceptTouchEvent/onTouchEvent 收到的是未剥离的全指针原始
    // 事件）。“摇杆 + 单根旋转指”被 pointerCount≥2 误判成双指捏合，
    // 旋转拖动中两指距离一变即 beginPinch 接管 → WebView 收 CANCEL、
    // MODE_PINCH 阻断视角采集 —— 3D 视角旋转与手柄同时使用时随机失灵。
    // 修复：捏合全链路只统计自由指（padPointerIds 在 dispatchTouchEvent
    // 分类，先于 super.dispatchTouchEvent 内部的拦截器回调，此处可用）。

    /** 当前事件中“自由指”（非手柄指针）的下标列表（可排除指定下标） */
    private fun freeIndicesOf(ev: MotionEvent, excludeIdx: Int = -1): List<Int> {
        val out = ArrayList<Int>(ev.pointerCount)
        for (i in 0 until ev.pointerCount) {
            if (i == excludeIdx) continue
            if (!padPointerIds.contains(ev.getPointerId(i))) out.add(i)
        }
        return out
    }

    /** 自由指两两最大指距；自由指不足 2 根返回 0f（本事件无捏合可能） */
    private fun freeSpanOf(ev: MotionEvent, excludeIdx: Int = -1): Float {
        val idx = freeIndicesOf(ev, excludeIdx)
        if (idx.size < 2) return 0f
        var max = 0f
        for (a in idx.indices) {
            for (b in a + 1 until idx.size) {
                val dx = ev.getX(idx[a]) - ev.getX(idx[b])
                val dy = ev.getY(idx[a]) - ev.getY(idx[b])
                val d = sqrt(dx * dx + dy * dy)
                if (d > max) max = d
            }
        }
        return if (max > 1f) max else 1f
    }

    /**
     * 把缩放注入到页面（动态改写 viewport meta：width=原宽/z + 钳制
     * scale）。force=true 精确注入当前值（手势结束/换绑恢复）；否则受
     * [JS_INTERVAL_MS] 节流。100% 时按快照还原原 meta（不盲清，页面
     * 自设的 viewport 逐字还原）。
     */
    private fun applyZoom(wv: WebView, zoom: Float, force: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force) {
            if (now - lastJsApplyAt < JS_INTERVAL_MS) return
            if (abs(zoom - lastJsZoom) < 0.02f) return
        }
        lastJsApplyAt = now
        lastJsZoom = zoom
        wv.evaluateJavascript(zoomScript(zoom), null)
    }

    companion object {
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 1.0f

        /** viewport meta 注入最小间隔（ms）：防 JNI 排队堆积 */
        private const val JS_INTERVAL_MS = 80L

        /** 手势状态机 */
        private const val MODE_IDLE = 0        // 无双指手势
        private const val MODE_OBSERVE = 1     // 双指观察中（未拦截，原生优先）
        private const val MODE_PINCH = 2       // 已拦截，缩小域接管中

        /**
         * 缩小域注入脚本（静态入口：引擎导航重置也用 [RESET_SCRIPT]）。
         * v2.14.9 动态 viewport 宽画布 —— 桌面 Chrome Ctrl+减号 同源语义：
         * - 首次接管快照（window.__az）：原 viewport meta（可能不存在）、
         *   原 content 字符串、原排版宽 cw（此刻尚未注入，clientWidth 仍是
         *   页面原生值）、接管瞬间页面 scale s0（= 原生地板 = 窗口宽/cw）；
         * - 每次注入：width = cw/z（排版视口按 1/z 变宽 → 整页等比变小，
         *   所有固定 px 元素统一缩小 = 整体缩放），minimum-scale =
         *   maximum-scale = s0*z（把页面 scale 钳到"窗口宽/画布宽"——
         *   画布宽 × scale = 窗口宽，任何比例下精确铺满窗口宽，letterbox
         *   物理上不可能出现）；
         * - innerWidth/innerHeight 由 Chromium 原生随排版视口变宽，H5 游戏
         *   的 resize 监听拿到真实新尺寸自动重排画布/重算布局，fixed 全屏层
         *   以视口为包含块自然铺满 —— 无需伪造 API、无需包含块 hack；
         * - 幂等可重入（捏合节流逐次重注入、切标签恢复重注入）：快照缓存于
         *   __az，重复注入只改写 width/scale 数值，无重复测量开销；
         * - 回 100%/导航重置走 [RESET_SCRIPT]（还原原 meta、清 __az）。
         */
        fun zoomScript(zoom: Float): String {
            return if (zoom >= 0.999f) RESET_SCRIPT
            else "(function(){try{" +
                "var d=document,z=$zoom;" +
                "if(!d.documentElement)return;" +
                "var S=window.__az;" +
                "if(!S){" +
                // 首次接管：此刻 meta 尚未被改写，layout/scale 均为页面
                // 原生值 —— 快照必须在此刻完成（之后 clientWidth 是注入
                // 后的宽画布值，不可再作基准）
                "var m=d.querySelector('meta[name=viewport]');" +
                "var cw=(d.documentElement&&d.documentElement.clientWidth)||window.innerWidth;" +
                "var s0=(window.visualViewport&&window.visualViewport.scale)||1;" +
                "if(!(s0>0)||!isFinite(s0))s0=1;" +
                "if(!m){" +
                // 页面无 viewport meta（VIEWPORT_FIT_SCRIPT 失效的非 http 页
                // 等）：自建一个，还原时整段移除，页面状态零残留
                "m=d.createElement('meta');m.setAttribute('name','viewport');" +
                "(d.head||d.documentElement).appendChild(m);" +
                "S={meta:m,content:null,cw:cw,s0:s0,created:true};" +
                "}else{" +
                "S={meta:m,content:m.getAttribute('content'),cw:cw,s0:s0,created:false};" +
                "}" +
                "window.__az=S;" +
                "}" +
                "if(!(S.cw>0))return;" +
                // 目标：画布宽 = cw/z（等比变小且铺满），页面 scale = s0*z
                //（= 窗口宽/画布宽，Chromium 按新约束钳制生效）
                "var w=Math.round(S.cw/z);" +
                "var sc=S.s0*z;" +
                "S.meta.setAttribute('content'," +
                "'width='+w+',minimum-scale='+sc+',maximum-scale='+sc+',user-scalable=yes');" +
                "}catch(e){}})()"
        }

        /**
         * 重置缩放脚本：按快照还原 viewport meta（自建的整段移除、原存在
         * 的逐字还原 content），并清 __az。还原后画布宽回到原生值，
         * Chromium 按"窗口宽/画布宽"地板把当前 scale 钳回初始满宽态
         * （device-width 页回 100%，固定宽 PC 页回全览态）。
         * 引擎 onPageStarted 导航重置用（新文档天然干净；同文档锚点导航
         * 由此完整还原页面自设 viewport）。
         */
        const val RESET_SCRIPT: String =
            "(function(){try{" +
                "var d=document,S=window.__az;" +
                "if(S&&S.meta){" +
                "if(S.created){if(S.meta.parentNode)S.meta.parentNode.removeChild(S.meta);}" +
                "else if(S.content==null)S.meta.removeAttribute('content');" +
                "else S.meta.setAttribute('content',S.content);" +
                "}" +
                "window.__az=null;" +
                "}catch(e){}})()"
    }
}
