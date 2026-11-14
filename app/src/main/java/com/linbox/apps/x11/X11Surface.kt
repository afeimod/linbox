package com.linbox.apps.x11

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.linbox.apps.containers.launcher.RootfsLauncher
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.input.gamepad.GamepadController
import com.linbox.core.input.gamepad.GamepadOverlayContent
import com.linbox.core.shell.ShellController
import com.linbox.LinBoxApp
import com.linbox.shellui.prefixStageOf
import com.termux.x11.LoriePreferences
import com.linbox.core.input.VirtualKeyboardController
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import com.termux.x11.LorieView
import com.termux.x11.Prefs
import com.termux.x11.X11InputHub
import com.termux.x11.input.InputStub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG_X11 = "X11Surface"

/**
 * v2.22.2 fix9.6：X11 图形界面（全屏页，ShellController.Screen.X11）。
 *
 * 进入方式：
 * - 终端主页工具栏点"X11"按钮（ShellController.showX11()）；
 * - 终端执行 `linbox-x11` → X server 广播 → X11WindowController
 *   自动跳转本页（同一会话用户主动退出后不反复拉入）。
 *
 * 渲染与交互：
 * - 画面渲染在 LorieView（native 跟随窗口 / exact+stretch 拉伸铺满，
 *   均无黑边）；返回键先退真全屏，再退回终端主页；
 * - 分辨率"跟随窗口"（native）/"缩放"（scaled）/"固定分辨率"（exact）
 *   随时切换；
 * - v2.25：底边控制条已移除 —— 原功能（键盘/游戏全屏/虚拟手柄/全屏/
 *   X11 设置/回终端）全部收编进玻璃悬浮球（X11Overlay.X11GlassFab），
 *   X11 设置面板对齐 termux-x11 LoriePreferences 完善为全量条目；
 * - 触摸方式（termux-x11 touchMode，X11 设置面板切换即时生效，v6.8 按
 *   linbox-winemu/termux-x11 TouchInputHandler 完整对齐）:
 *   触控板（相对移动/轻点/长按拖拽/双指右键/三指中键/滚轮）、
 *   模拟触摸（轻点=定位点击/长按=按住拖拽/滑动不动光标/双指右键/
 *   三指中键/滚轮/双击吸附）、直接触摸（XI2 多点触摸直注）——
 *   SmartTouchBridge 输入策略切换；
 * - 虚拟手柄：悬浮 GamepadOverlay 按键经 X11InputHub 桥直注 X；
 * - 附加键盘栏（showAdditionalKbd）：画面底部 ESC/方向键玻璃键行，
 *   点按直注 X。
 */
@Composable
fun X11DisplayLayer() {
    val context = LocalContext.current
    val connState by X11WindowController.state.collectAsState()
    var lorieViewRef by remember { mutableStateOf<LorieView?>(null) }
    val prefs = remember { LoriePreferences.prefs }

    // v2.25：LoriePreferences（不可观察）→ X11UiPrefs（可观察镜像），
    // 供全屏/方向/亮屏/刘海/附加键盘栏驱动副作用
    LaunchedEffect(prefs) { prefs?.let { X11UiPrefs.initIfNeed(it) } }

    // ===== v6.5：X11 全局显示层（取代原 X11 全屏页）=====
    // 会话进行中，画面以两种形态显示在所有界面（主页/游戏库/容器/终端…）
    // 的前方：
    // - 全屏形态：进入 X11 页（ShellController.Screen.X11）或浮窗"全屏"
    //   按钮一键全屏（localFullscreen，返回键恢复小窗）；
    // - 小窗形态：可自由拖动/八向缩放的浮窗（AnWind 同款），窗口大小按
    //   会话分辨率自适应，浮窗外触摸全部穿透 —— 底层界面照常操作。
    // 小窗开关关闭时维持旧行为：会话广播自动跳转 X11 全屏页。
    val onX11Page = ShellController.screen == ShellController.Screen.X11
    val floatEnabled = X11FloatWindow.enabled
    val sessionActive = connState != X11WindowController.State.Idle
    if (!onX11Page && !(floatEnabled && sessionActive)) {
        // 层退场：清全局感知标志（全局手柄层恢复渲染 / PerfGovernor 恢复刷新）
        SideEffect {
            X11DisplayState.layerActive = false
            X11DisplayState.fullscreenActive = false
        }
        return
    }

    // 浮窗"全屏"按钮的一键全屏（返回键恢复小窗；进入 X11 页时自动复位）
    var localFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(onX11Page) { if (onX11Page) localFullscreen = false }
    val fullscreenLayout = onX11Page || localFullscreen
    val windowLayout = !fullscreenLayout

    // 全局感知标志同步（GamepadOverlay 让位 / PerfGovernor 静止主界面刷新）
    SideEffect {
        X11DisplayState.layerActive = true
        X11DisplayState.fullscreenActive = fullscreenLayout
    }

    // 真全屏：画面独占（悬浮球/键行隐藏，返回键退出）；仅全屏形态下生效
    val trueFs = fullscreenLayout && X11UiPrefs.fullscreen
    fun setFullscreen(v: Boolean) {
        X11UiPrefs.fullscreen = v
        prefs?.fullscreen?.put(v)
    }

    // 悬浮球区域尺寸 + 手柄开关状态（球体菜单显示点亮态）
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    val app = LinBoxApp.get()
    val gamepadEnabled by app.settingsStore.gamepadEnabled.collectAsState(initial = false)
    // 面板关闭时机：把不可观察偏好同步进触摸桥（首选扫描码）
    val settingsShowing by X11SettingsBridge.show.collectAsState()

    // ===== 小窗模式状态（持久化在 linbox_x11_float；Prefs.java 为脚本
    //      生成物不能手改，故独立存本应用私有 prefs）=====
    LaunchedEffect(Unit) { X11FloatWindow.initIfNeed(context) }
    val density = LocalDensity.current
    val floatWin = windowLayout

    // 分流根容器引用（帧同步用；factory 创建后写入）
    var lorieRootRef by remember { mutableStateOf<X11TouchSplitLayout?>(null) }

    // 小窗几何自适应：画面区尺寸就绪/旋转/开启时钳入画面区（首次给居中 72% 默认窗）
    LaunchedEffect(floatWin, areaSize) {
        if (floatWin && areaSize != IntSize.Zero) {
            X11FloatWindow.ensureFrame(
                areaSize.width, areaSize.height,
                with(density) { X11FloatChrome.MIN_W.roundToPx() },
                with(density) { X11FloatChrome.MIN_H.roundToPx() }
            )
        }
    }

    // ===== v6.5：小窗按分辨率自适应（AnWind 语义）=====
    // 会话分辨率握手（glibc-runner -d / 手动固定 exact）变化时，窗口外框
    // 按该分辨率的宽高比缩放到工作区 88% 预算内并居中 —— 窗口大小随
    // 分辨率调整，画面不变形；用户手动缩放后保持，直到下一次分辨率变化。
    val resState by X11ResolutionLink.state.collectAsState()
    LaunchedEffect(resState.mode, resState.exact, windowLayout, areaSize) {
        if (!windowLayout || areaSize == IntSize.Zero) return@LaunchedEffect
        if (resState.mode != "exact" || resState.exact.isEmpty()) return@LaunchedEffect
        val m = Regex("(\\d{2,5})x(\\d{2,5})").find(resState.exact) ?: return@LaunchedEffect
        X11FloatWindow.fitToResolution(
            context,
            m.groupValues[1].toInt(), m.groupValues[2].toInt(),
            areaSize.width, areaSize.height,
            with(density) { X11FloatChrome.MIN_W.roundToPx() },
            with(density) { X11FloatChrome.MIN_H.roundToPx() }
        )
    }

    // 小窗帧 → View 层 LorieView 内容帧同步（v6.7：AndroidView 本体已
    // 收缩到窗口外框 —— lorieFrame 改为窗口内局部坐标：边框内、标题栏/
    // 快捷条之下；关闭时清空回铺满）。key 含 lorieRootRef：factory 创建
    // 根容器（晚于首次组合）后也要重启同步；快捷条显隐即时重排窗内画面。
    LaunchedEffect(
        lorieRootRef, floatWin, X11FloatWindow.x, X11FloatWindow.y,
        X11FloatWindow.w, X11FloatWindow.h, X11FloatWindow.maximized,
        X11FloatWindow.quickBarShown, areaSize
    ) {
        val root = lorieRootRef ?: return@LaunchedEffect
        if (!floatWin || areaSize == IntSize.Zero) {
            root.lorieFrame = null
            return@LaunchedEffect
        }
        val f = X11FloatWindow.effectiveFrame(areaSize.width, areaSize.height)
        val bd = with(density) { 1.dp.roundToPx() }
        val topPx = with(density) {
            (X11FloatChrome.TITLE_H +
                (if (X11FloatWindow.quickBarShown) X11FloatChrome.QUICKBAR_H else 0.dp)
            ).roundToPx()
        }
        root.lorieFrame = Rect(
            bd, topPx + bd,
            (f.width() - bd).coerceAtLeast(bd + 1),
            (f.height() - bd).coerceAtLeast(topPx + bd + 1)
        )
    }

    // 智能鼠标桥与手柄层（随 LorieView 实例创建，见 factory）
    var touchBridge by remember { mutableStateOf<SmartTouchBridge?>(null) }

    // v2.32：手柄 Compose 宿主引用（onDispose 时销毁组合防泄漏）+
    // LocalLifecycleOwner（factory 非 Composable 作用域内预取）
    val lifecycleOwner = LocalLifecycleOwner.current
    var gamepadHostRef by remember { mutableStateOf<ComposeView?>(null) }
    // v6.9：悬浮球 decor 宿主引用（onDispose 一并销毁；见 factory 内说明）
    var ballHostRef by remember { mutableStateOf<ComposeView?>(null) }

    // 连接建立即应用分辨率协议文件（兜底时序：glibc-runner 写文件可能
    // 早于/晚于窗口打开；FileObserver 只覆盖窗口已打开的情况）。
    LaunchedEffect(connState) {
        if (connState == X11WindowController.State.Connected) {
            X11ResolutionLink.applyFromFile()
        }
    }

    // v2.22.5 fix15/fix17：游戏窗口自适应铺满 —— X11FitClient 直连 X server
    // socket，把窗口化游戏平移铺满 X 屏幕（客户区 = 屏幕），根治"四周黑边
    // 烧在画面内部"。fix17 起固定分辨率与跟随窗口会话均启用（跟随窗口会话
    // fit 时 X 屏幕会被定为游戏客户区，控制条如实显示）；桌面环境（xfdesktop
    // 等大面积窗口）FitClient 内部自动跳过；窗口关闭/断开时自动停止。
    LaunchedEffect(connState) {
        val active = connState == X11WindowController.State.Connected
        if (active) X11FitClient.start() else X11FitClient.stop()
    }

    // 离开本页（返回终端）时断开渲染连接、结束 wine 并抑制本会话自动
    // 跳转；重新进入时 factory 重建 LorieView 并自动重连。
    // v2.22.5 fix15：会话结束语义保持与旧版"关闭 X11 窗口"一致 ——
    // 退出时终止 wine 会话（用户反馈"关闭 x11 窗口没有关闭 wine"）。
    DisposableEffect(Unit) {
        onDispose {
            X11Session.killWine()
            X11FitClient.stop()
            lorieViewRef = null
            X11ResolutionLink.attachView(null)
            // 触摸桥注册一并注销（防泄漏 + 防设置面板下发到已分离视图）
            SmartTouchBridge.activeBridge = null
            // v2.31：桥分离时清理悬挂冲刷与受跟踪指针（防迟到事件误发）
            touchBridge?.onDetached()
            // v2.32.2：手柄 Compose 宿主组合显式销毁（AndroidView 内嵌
            // ComposeView 的组合不随 View detach 自动释放，防泄漏）；
            // v6.7：宿主挂 decorView —— Compose 不再代管其移除，必须手动
            // 从装饰视图摘除（否则会话结束后残留全屏层吞触摸）
            gamepadHostRef?.let { host ->
                host.disposeComposition()
                (host.parent as? ViewGroup)?.removeView(host)
            }
            gamepadHostRef = null
            // v6.9：悬浮球 decor 宿主一并销毁（主树悬浮球/手柄设置窗恢复渲染）
            ballHostRef?.let { host ->
                host.disposeComposition()
                (host.parent as? ViewGroup)?.removeView(host)
            }
            ballHostRef = null
            X11DisplayState.ballHostActive = false
            // 手柄 → X11 转发目标一并注销（内部会对仍按着的键补发 UP）
            X11InputHub.get(context).setActiveLorieView(null)
            X11WindowController.detachView(userClosed = true)
        }
    }

    // 返回键：真全屏时先退全屏；全屏形态下回主页（X11 页）或恢复小窗
    //（浮窗一键全屏）；小窗形态不拦截 —— 返回键交给底层页面。
    BackHandler(enabled = trueFs) { setFullscreen(false) }
    BackHandler(enabled = !trueFs && fullscreenLayout) {
        if (onX11Page) ShellController.showHome() else localFullscreen = false
    }

    // ------------------------------------------------------------------
    // v2.25 窗口效果（termux-x11 keepScreenOn / forceOrientation / hideCutout）
    // 均应用在宿主 Activity 窗口上，离开 X11 界面时统一还原全局设置。
    // v6.5：方向 / 刘海仅【全屏形态】生效 —— 浮窗是叠在当前界面上的
    // 附属窗口，不应劫持宿主的方向与刘海（底层页面按应用全局设置显示）。
    // keepScreenOn 随会话保持（X11 运行中亮屏合理）。
    // ------------------------------------------------------------------
    val activity = context as? android.app.Activity

    // 按应用全局设置还原方向/刘海（DataStore 异步读，主线程落窗）
    fun restoreGlobalDisplay() {
        app.applicationScope.launch {
            val restoreCutout = app.settingsStore.useCutout.first()
            val restoreOri = app.settingsStore.displayOrientation.first()
            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val win = activity?.window
                    win?.attributes = win?.attributes?.apply {
                        layoutInDisplayCutoutMode = if (restoreCutout)
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        else
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }
                }
                activity?.requestedOrientation = when (restoreOri) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
            }
        }
    }

    // 保持亮屏
    DisposableEffect(X11UiPrefs.keepScreenOn) {
        val win = activity?.window
        if (X11UiPrefs.keepScreenOn) {
            win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 屏幕方向（面板切换即时生效；v6.5 仅全屏形态，浮窗交还底层页面）
    LaunchedEffect(X11UiPrefs.orientation, fullscreenLayout) {
        if (!fullscreenLayout) {
            restoreGlobalDisplay()
            return@LaunchedEffect
        }
        activity?.requestedOrientation = when (X11UiPrefs.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    // 刘海区：进入时应用；开关切换时重新应用（Keyed 重启，无竞态）；
    // v6.5 仅全屏形态生效（浮窗形态还原全局设置）
    DisposableEffect(fullscreenLayout) {
        fun applyCutout(occupy: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val win = activity?.window ?: return
                win.attributes = win.attributes.apply {
                    layoutInDisplayCutoutMode = if (occupy)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    else
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        if (fullscreenLayout) {
            applyCutout(X11UiPrefs.hideCutout)
        } else {
            restoreGlobalDisplay()
        }
        onDispose { }
    }
    // 刘海开关变化（设置面板切换后）重新应用
    LaunchedEffect(X11UiPrefs.hideCutout, fullscreenLayout) {
        if (!fullscreenLayout) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val win = activity?.window ?: return@LaunchedEffect
            win.attributes = win.attributes.apply {
                layoutInDisplayCutoutMode = if (X11UiPrefs.hideCutout)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }

    // 面板关闭后把"首选扫描码"同步进触摸桥（SharedPreferences 不可观察，
    // 以面板关闭为应用时机）
    LaunchedEffect(settingsShowing) {
        if (!settingsShowing) {
            prefs?.let { touchBridge?.setPreferScancodes(it.preferScancodes.get()) }
            // 触摸方式等偏好兜底下发（面板内每次改动已即时下发）
            SmartTouchBridge.applyTouchPrefs()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // v6.5：浮窗形态作为全局显示层挂在所有界面前方 —— 根容器必须
            // 全透明，让底层的页面对象透出（浮窗本体只有 chrome 边框 +
            // View 层 LorieView 帧在窗内）；全屏形态仍纯黑（对比度优先）
            .background(if (floatWin) Color.Transparent else Color.Black)
    ) {
        // ===== 画面区（悬浮球 / 附加键盘栏 / 等待页全部悬浮其上） =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { areaSize = it.size }
        ) {
            // v6.7（用户：“x11窗口化后，不能点击窗口外的按钮”）：浮窗形态
            // 下 AndroidView 本体收缩到窗口外框 —— 窗口外的触摸完全不进入
            // 本显示层（Compose 命中测试按节点边界判定，窗口外根本不会命中
            // AndroidView 的 interop 节点），底层页面按钮照常点击，不再依赖
            // “View 返回 false → interop 放行”的链路。全屏形态仍铺满整屏。
            AndroidView(
                modifier = if (floatWin) Modifier
                    .offset {
                        val f = X11FloatWindow.effectiveFrame(
                            areaSize.width, areaSize.height
                        )
                        IntOffset(f.left, f.top)
                    }
                    .layout { m, ctr ->
                        val f = X11FloatWindow.effectiveFrame(
                            ctr.maxWidth, ctr.maxHeight
                        )
                        val w = f.width().coerceIn(1, ctr.maxWidth)
                        val h = f.height().coerceIn(1, ctr.maxHeight)
                        val p = m.measure(Constraints.fixed(w, h))
                        layout(w, h) { p.place(0, 0) }
                    }
                    else Modifier.fillMaxSize(),
                factory = { ctx ->
                    // 根容器（X11TouchSplitLayout）：v6.7 起仅承载 LorieView
                    // 动态布局（小窗帧 / 拉伸居中）；手柄分流已上移 decorView
                    // 由 View 体系原生完成，类内分流逻辑保持休眠。
                    // LorieView 铺满窗口（native 跟随窗口 / exact+stretch
                    // 拉伸铺满，均无黑边）。
                    // v2.22.5 fix12：Gravity.CENTER → FILL —— 画面靠左上锚定
                    // （用户反馈"黑边没有靠左"）；FILL = TOP|START|BOTTOM|END。
                    val root = X11TouchSplitLayout(ctx)

                    val lv = LorieView(ctx)
                    root.addView(lv, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.FILL
                    ))

                    // v2.32：手柄 Compose 宿主（GamepadOverlayContent 渲染）。
                    // v6.7：宿主从 X11TouchSplitLayout 迁移到 Activity
                    // decorView 顶层（Compose 内容视图之上）—— v6.7 起
                    // AndroidView 本体在浮窗形态收缩到窗口外框，手柄层若留
                    // 在根容器内会被窗口裁切且归一化坐标错位；挂 decor 后：
                    // - 手柄元素恒全屏渲染（全屏/浮窗两形态一致，不被窗口
                    //   裁切，且置顶于一切界面元素之上）；
                    // - 触摸分流由 View 体系天然完成：decorView 逐子视图
                    //   （后加者在前）试探 —— 手柄宿主的 Compose 树命中元素
                    //   即消费，空区域返回 false 自动落回 Compose 内容视图
                    //   （页面 / 窗口内 LorieView），不再依赖 Compose interop
                    //   语义与手写逐指分流；多指同按由 MotionEvent
                    //   splitting 原生支持。
                    // 嵌入 ComposeView 需显式挂 lifecycle/registry owner
                    // （Activity 同时实现两者）。
                    val gamepadHost = ComposeView(ctx).apply {
                        setViewTreeLifecycleOwner(lifecycleOwner)
                        (lifecycleOwner as? SavedStateRegistryOwner)?.let {
                            setViewTreeSavedStateRegistryOwner(it)
                        }
                        // v5.0：手柄元素以基础密度渲染 —— 自带 sizeDp 尺寸
                        // 语义（手柄设置里单独调），不随全局 UI 缩放。
                        setContent {
                            com.linbox.core.theme.KeepBaseDensity {
                                GamepadOverlayContent()
                            }
                        }
                    }
                    val decorParent = (ctx as? android.app.Activity)
                        ?.window?.decorView as? ViewGroup
                    if (decorParent != null) {
                        decorParent.addView(
                            gamepadHost,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    } else {
                        // 兜底：拿不到 Activity decor（极端宿主）时退回根容器
                        root.addView(gamepadHost, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.FILL
                        ))
                    }
                    gamepadHost.visibility = View.GONE
                    gamepadHostRef = gamepadHost

                    // v6.9（用户："悬浮球所有菜单应该显示在虚拟手柄按键前端才
                    // 对"）：悬浮球/手柄设置窗第二宿主 —— 挂在 gamepadHost 之后
                    //（decorView 后加子视图绘制在上、事件先派发）：球与全部菜单
                    // 恒在虚拟手柄按键之上，菜单不再被按键遮挡。主树（LinBoxShell）
                    // 的悬浮球/设置窗在 ballHostActive 时让位（GamepadOverlay 同款
                    // gating），会话期间只此一处，无双重渲染。兜底路径（无 decor）
                    // 不建宿主，主树版本照常渲染。
                    if (decorParent != null) {
                        val ballHost = ComposeView(ctx).apply {
                            setViewTreeLifecycleOwner(lifecycleOwner)
                            (lifecycleOwner as? SavedStateRegistryOwner)?.let {
                                setViewTreeSavedStateRegistryOwner(it)
                            }
                            setContent {
                                com.linbox.core.input.gamepad.GamepadFloatingBall()
                                com.linbox.core.input.gamepad.GamepadSettingsWindow()
                            }
                        }
                        decorParent.addView(
                            ballHost,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        ballHostRef = ballHost
                        X11DisplayState.ballHostActive = true
                    }

                    // 分流接线：屏幕子事件直达 LorieView（桥），手柄命中区
                    // 判定复用 GamepadController（元素 ∪ 工具条，窗口坐标）
                    // v6.4：LorieView 动态布局（小窗帧/非拉伸居中）后不在
                    // (0,0) —— 直投前把根容器坐标换算成 lv 本地坐标，投完
                    // 还原（同一事件对象可能还要给手柄宿主继续用）
                    root.lorieChild = lv
                    root.screenRouter = { ev ->
                        val ox = lv.left
                        val oy = lv.top
                        if (ox == 0 && oy == 0) lv.dispatchTouchEvent(ev)
                        else {
                            ev.offsetLocation(-ox.toFloat(), -oy.toFloat())
                            val r = lv.dispatchTouchEvent(ev)
                            ev.offsetLocation(ox.toFloat(), oy.toFloat())
                            r
                        }
                    }
                    root.padHitTest = { x, y -> GamepadController.isOverPadUi(x, y) }
                    // v6.7：padHost 不再接线（宿主已上挂 decorView，View 体系
                    // 原生分流；padSplitActive 恒 false，以上 router/hitTest
                    // 仅作历史路径保留，不会被激活）
                    lorieRootRef = root

                    val bridge = SmartTouchBridge(ctx, lv)
                    // v2.25：同步"首选扫描码"偏好（设置面板切换后面板关闭时机再同步）
                    // v2.26：触摸方式/触控板参数一并初始化，并注册活跃桥 ——
                    // 设置面板改触摸偏好后经 SmartTouchBridge.applyTouchPrefs 即时下发
                    LoriePreferences.prefs?.let { p ->
                        bridge.setPreferScancodes(p.preferScancodes.get())
                        bridge.reloadTouchPrefs(p)
                    }
                    SmartTouchBridge.activeBridge = bridge
                    touchBridge = bridge

                    lv.setCallback { surfaceW, surfaceH, screenW, screenH ->
                        bridge.updateTransform(surfaceW, surfaceH, screenW, screenH)
                        // fix15：自适应客户端同步当前 X 屏幕尺寸（已铺满则跳过判断）
                        X11FitClient.screenW = screenW
                        X11FitClient.screenH = screenH
                        // v3.1.7（AnWind 移植）：当前 X 屏幕尺寸落盘
                        // （.linbox-x11-screen）—— 容器启动链构建"跟随窗口"
                        // 模式的 wine 虚拟桌面命令时读取（RootfsLauncher
                        // currentXScreenResolution），无 WM 的 X 服务上
                        // 虚拟桌面按当前 X 屏幕尺寸创建才能铺满又跟随。
                        X11ResolutionLink.writeScreenSize(screenW, screenH)
                        // 同步 Winlator 侧 xserver 状态（screenInfo 消费方依赖）。
                        try {
                            lv.screenInfo.handleHostSizeChanged(surfaceW, surfaceH)
                            lv.screenInfo.handleClientSizeChanged(screenW, screenH)
                        } catch (_: Exception) {}
                        val display = lv.display
                        val framerate = (display?.refreshRate ?: 60f).toInt()
                        val name = if (display == null || display.displayId == Display.DEFAULT_DISPLAY)
                            "Builtin Display" else "External Display"
                        LorieView.sendWindowChange(screenW, screenH, framerate, name)
                    }
                    lv.setOnTouchListener { _, e -> bridge.onTouch(e) }
                    // 按键直注 X（返回键不拦截，交给桌面处理全屏/关窗）
                    lv.setOnKeyListener { _, keyCode, event ->
                        if (keyCode == KeyEvent.KEYCODE_BACK) false
                        else bridge.sendKey(event) ?: false
                    }

                    lorieViewRef = lv
                    X11ResolutionLink.attachView(lv)
                    // 桌面虚拟手柄 → X11 直注通道（GamepadController 经
                    // X11InputHub 静态桥把按键/鼠标事件发到本视图）。
                    X11InputHub.get(ctx).setActiveLorieView(lv)

                    X11WindowController.connectLorieView(lv)
                    root
                },
                update = { root ->
                    // v6.7：手柄宿主已迁至 decorView 顶层，View 体系原生完成
                    // 手柄/画面分流 —— 根容器分流器保持休眠（padSplitActive
                    // 恒 false，dispatchTouchEvent 走 FrameLayout 直通），
                    // 桥侧剥离同步关闭（手柄指针在 decor 层即被消费，不会
                    // 抵达 LorieView，无需记账）。
                    root.padSplitActive = false
                    touchBridge?.splitActive = false
                    // v2.32.2：手柄关闭即清命中矩形 —— gamepadHost 仅 GONE
                    // 不销毁组合（onDispose 不触发），残留矩形会让兜底剥离
                    // 路径把撞上旧布局位置的屏幕 tap 误吞（点击失灵来源之一）
                    if (!gamepadEnabled) {
                        GamepadController.clearElementHits()
                        GamepadController.clearToolbarHit()
                    }
                    gamepadHostRef?.visibility =
                        if (gamepadEnabled) View.VISIBLE else View.GONE
                }
            )

            // ===== 等待页（未连接时覆盖；v6.5 仅全屏形态 —— 浮窗形态下
            //      全屏等待页会盖住底层页面，连接等待改为窗内提示） =====
            if (fullscreenLayout && connState != X11WindowController.State.Connected) {
                WaitingPanel(connState)
            }

            // ===== v6.4：前缀构建进度悬浮卡（用户："容器创建时构建前缀的
            //      进度条没有显示在 x11 界面"）—— 前缀构建期间用户往往已
            //      被拉进 X11 页看 wine 桌面，壳层页的进度卡/日志窗全被
            //      全屏画面盖住。此处按 RootfsLauncher.prefixBuildFlow 驱动：
            //      可拖动、日志可显隐、可收起为小徽标，不拦截 X11 操作。 =====
            // v6.10（用户：“构建前缀的进度条偶尔被挡在 x11 界面后面
            //      显示不出来”）：本卡已移至 Box 末位渲染（同层最顶，
            //      不再被小窗 chrome / 附加键盘栏盖住），并去掉 !trueFs
            //      门控 —— 真全屏形态原先连本卡一起隐藏，而真全屏下壳层页
            //      进度卡又不可达，进度彻底消失（“偶尔看不见”主场景）。
            //      前缀构建是分钟级一次性事件，进度可见性优先于“画面
            //      独占”纯净性；不需要时可收起为小徽标。

            // ===== v6.5：小窗模式窗口 chrome（标题栏拖动 / 双击最大化 /
            //      边缘八向缩放 / 快捷配置条 / 全屏恢复 / 关闭结束会话；
            //      画面本体是 View 层 LorieView 动态布局帧，chrome 只画
            //      边框与标题栏，内容区透出画面；未连接时窗内显示连接提示，
            //      窗口出现即可拖到顺手位置等画面亮起） =====
            if (floatWin) {
                X11FloatWindowChrome(
                    areaW = areaSize.width,
                    areaH = areaSize.height,
                    connected = connState == X11WindowController.State.Connected,
                    onExit = {
                        // v6.5：✕ = 结束本次会话（与 AnWind 关窗一致）——
                        // closeSession 状态归 Idle → 显示层退场（onDispose
                        // 终止 wine 会话 + detachView 抑制本会话重播）
                        X11WindowController.closeSession()
                    },
                    onFullscreen = {
                        // v6.5：全屏按钮恢复全屏（返回键恢复小窗）
                        localFullscreen = true
                    }
                )
            }

            // ===== v2.25 附加键盘栏（showAdditionalKbd；连接后显示，真全屏时隐藏，
            //      imePadding 使其贴在输入法上沿；
            //      v5.0：基础密度渲染 —— 输入辅助层不随全局 UI 缩放） =====
            if (connState == X11WindowController.State.Connected &&
                X11UiPrefs.showAdditionalKbd && !trueFs
            ) {
                com.linbox.core.theme.KeepBaseDensity {
                Box(
                    Modifier
                        .matchParentSize()
                        .imePadding(),
                    contentAlignment = Alignment.BottomStart
                ) {
                    X11ExtraKeysBar()
                }
                }
            }

            // ===== v6.10：前缀构建进度悬浮卡（从 Box 前段移入末位 ——
            //      Compose 同层后渲染者在上：小窗 chrome / 附加键盘栏均
            //      不能再盖住它；全形态（全屏/真全屏/小窗）均渲染，
            //      prefixBuildFlow 为空时自绘为零。 =====
            X11PrefixBuildOverlay(
                areaW = areaSize.width,
                areaH = areaSize.height
            )

            // ===== v5.0：原 X11 玻璃悬浮球已移除 —— 全部功能并入虚拟手柄
            //      悬浮球（GamepadFloatingBall，常驻 LinBoxShell 层，尺寸不随
            //      全局缩放）。此处不再渲染第二个球，修复双悬浮球不统一。 =====
        }
    }

    // v6.6：X11SettingsDialog 移至 LinBoxShell 全局层 —— 会话存活期间
    //（全屏页 / 浮窗小窗 / 后台运行）任意页面均可经悬浮球 / 常驻通知
    // 打开设置；显示层内部不再调用，防双重弹窗。
}

@Composable
private fun WaitingPanel(state: X11WindowController.State) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (state == X11WindowController.State.Waiting) "正在连接 X 服务…"
            else "未发现 X 服务",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "在终端执行以下命令启动 X 服务（本界面将自动亮起）：",
            color = Color(0xFFB8C4CE),
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        val steps = listOf(
            "linbox-x11 :13                  # 启动 X 服务并自动跳转本界面",
            "env DISPLAY=:13 xfce4-session   # 未自动起会话时手动执行",
            "glibc-runner -d1280x720 game.exe  # 游戏按指定分辨率全屏渲染",
            "linbox-x11 doctor              # 连不上时一键体检"
        )
        steps.forEach { line ->
            Text(
                text = line,
                color = Color(0xFF9FE29F),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF14231A), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { X11Desktop.open(context) }) {
            Text("打开兼容全屏模式", fontSize = 12.sp)
        }
        Text(
            text = "兼容模式 = 独立全屏 Activity（排查渲染异常时使用）",
            color = Color(0xFF8A97A3),
            fontSize = 10.sp
        )
    }
}


/**
 * 智能鼠标桥（fix10）：Android 触摸 → X 真实鼠标事件。
 *
 * 为什么不再直注原生 X 触摸（旧 DirectTouchBridge / sendTouchEvent）：
 * - wine（尤其 explorer/游戏菜单）大量场景只响应鼠标按下/抬起；
 *   XI2 触摸直注在 wine 下表现为"点不开文件夹/文件"；
 * - 双击要求两次点击落点足够近 —— 手指两次落点天然有偏差；
 * - 拖拽（移动 wine 窗口、拉滚动条）需要"按下期间持续移动"。
 *
 * 手势映射：
 * - 单指按下      → 绝对移动到指尖（双击吸附，见下）+ 左键按下
 * - 单指移动      → 跟随移动（= 按住拖拽；轻点不产生位移）
 * - 单指抬起      → 左键抬起（快速轻点 = 完整左键单击）
 * - 双击          → 两次轻点 <450ms 且落点接近 → 第二次按下自动吸附到
 *                   第一次的 X 坐标 → wine 稳定识别 WM_LBUTTONDBLCLK
 * - 双指轻点      → 右键单击
 * - 双指滑动      → 滚轮（上下滑动 = 滚动内容）
 * - 长按拖动      → 左键按住拖拽（天然支持，无需特殊处理）
 *
 * 坐标变换：视图像素 ×(X屏幕/视图) = X 坐标；固定分辨率模式下
 * LorieView 信箱缩放，比例恒等映射，无偏移。
 *
 * v2.26：触摸方式真实落地（termux-x11 touchMode）—— 上述手势映射属于
 * "模拟触摸屏"方式；触控板/直接触摸见 onTouchTrackpad / onTouchDirect。
 * LinBox 的 X11 触摸事件全部经本桥进入 X（上游 TouchInputHandler 未接线），
 * 偏好变更经 X11 设置面板 → applyTouchPrefs 即时切换输入策略。
 *
 * v6.8（用户："根据我的linbox-winemu项目修复linbox的x11三个触摸方式"）：
 * 三方式语义与 linbox-winemu（termux-x11 TouchInputHandler +
 * InputStrategyInterface）逐项对齐 —— 模拟触摸重写为"轻点=定位点击、
 * 长按=按住拖拽、滑动不动光标、双指右键/三指中键/双指滚轮"；触控板
 * 补长按拖拽（tapToMove 关时）与三指中键；直接触摸保持 XI2 直注
 * （上游 NullInputStrategy）。拖拽/移动仍走相对增量合并器（v2.30
 * wine XWarpPointer 兼容 + v2.33.1 主线程零 socket I/O 语义保留）。
 *
 * v2.30：wine 游戏视角乱飘/卡死修复 —— 触控板模式改为真实相对运动
 * （sendCursorMove relative=true，上游 TrackpadInputStrategy 同款），
 * 删除虚拟光标 padX/padY 绝对发送；模拟触摸屏按住拖拽改发相对增量
 * （按下瞬间仍绝对定位到指尖）。wine FPS 每帧 XWarpPointer 回窗口中心
 * 后不再被绝对坐标强拽 → 游戏读到的是真实相对增量，视角正常旋转。
 *
 * v2.30.1：滑动严重掉帧修复 —— 相对增量合并器（motion coalescer）。
 * 原生 sendMouseEvent 每次调用都会经 X server 输入线程解析并把事件
 * 投递给 wine（反汇编实证：一次 24 字节包 socket 写入 → 输入线程
 * GetPointerEvents → 客户端 socket 投递）；拖拽时 Android 触摸采样率
 * 120~240Hz，wine 游戏本身已打满 CPU，逐事件唤醒/派发直接抢占渲染
 * 时间片 → 一滑动就严重掉帧。合并器把 MOVE 增量累加进 pending，
 * 以 16ms 最小间隔节流冲刷（≈62.5Hz，不高于显示有效刷新率、高于
 * 游戏 fps）：增量总量精确守恒（逐帧小增量之和 = 合并后单条增量），
 * 视角旋转/桌面光标手感不变，X server+wine 每秒事件处理量降
 * 50%~75%。手势结束/模式切换立即冲刷或清空，不丢不串。
 *
 * v2.31：1) 手柄指针剥离 —— 虚拟手柄元素自 v2.16.4 起不消费自身
 *   指针（终端侧由 WebView 过滤器消费兜底），X11 侧 LorieView 是裸
 *   interop View，未消费的手柄指针会原样漏进本桥：按住手柄按钮/
 *   摇杆时另一根手指滑动，桥看到 2 指流 → 误判双指滚轮 → 无法滑动
 *   转视角。onTouch 入口按 DOWN 落点跟踪"屏幕手势指针"，构造只含
 *   这些指针的子集事件交给手势逻辑，手柄指针对桥不可见；手柄元素
 *   照常经 Compose 命中路径自收事件，两边各收各的指针。
 *   2) 冲刷线程化 —— 合并器冲刷从主线程移到专用后台线程（16ms 节流
 *   不变）：sendMouseEvent 是 @FastNative socket 直写，wine 打满 CPU
 *   时 X server 输入线程消费变慢，主线程直写会被 socket 缓冲顶住
 *   （UI 线程卡顿 = 残余掉帧）；冲刷入后台线程后主线程触摸路径零
 *   socket I/O，手势结束仍同步冲刷保证"移动→抬键"时序。
 */
internal class SmartTouchBridge(private val context: Context, private val view: LorieView) {
    private val renderData = com.termux.x11.input.RenderData()
    private val keySender = com.termux.x11.input.InputEventSender(view)

    companion object {
        // 触摸方式取值（对齐 termux-x11 touchscreenInputModesValues）
        const val TOUCH_TRACKPAD = 1
        const val TOUCH_SIMULATED = 2
        const val TOUCH_DIRECT = 3

        /**
         * 相对增量最小冲刷间隔（ms）：≈62.5Hz。PC 鼠标 125Hz 档的一半、
         * 不低于主流手机游戏在 wine 下的实际帧率，显示端 ≤60Hz 时逐帧
         * 对齐 —— 手感无损，事件量减半以上。
         */
        private const val MOTION_FLUSH_INTERVAL_MS = 16L

        /** XI_TouchEnd（对齐 InputEventSender 私有常量，公开 SDK 无此符号） */
        private const val XI_TOUCH_END = 20

        /**
         * v2.31：相对增量冲刷专用线程 —— sendMouseEvent 是 @FastNative
         * 的 socket 直写，wine 游戏打满 CPU 时 X server 输入线程消费变慢，
         * 主线程直写可能被 socket 缓冲顶住（UI 线程卡顿 = 掉帧）。冲刷
         * 移入独立线程后，主线程触摸路径零 socket I/O（手势结束的同步
         * 兜底冲刷除外，单次可忽略）。进程级单例，桥实例重建共享复用。
         */
        private val flushThread by lazy {
            HandlerThread("linbox-x11-motion").apply { start() }
        }

        /** 活跃桥：设置面板修改触摸偏好后经 [applyTouchPrefs] 即时下发。 */
        @Volatile var activeBridge: SmartTouchBridge? = null

        /** 把 LoriePreferences 的触摸偏好（方式/触控板缩放/轻点拖拽）下发到活跃桥。 */
        fun applyTouchPrefs() {
            val p = LoriePreferences.prefs ?: return
            activeBridge?.reloadTouchPrefs(p)
        }

        /**
         * v2.33.1：桥外输入注入（虚拟手柄鼠标键/滚轮等）统一投递到桥的
         * 专用输入线程 —— 与触摸/按键/滚轮 FIFO 保序，且 X 输入 socket
         * 回到"单写者"模型（此前手柄键主线程直写、桥内 motion 后台写，
         * 两个线程并发写同一 socket：wine 满载时主线程写被缓冲顶住的
         * 概率与触摸流量成正比，是滑屏掉帧的次要阻塞源）。
         * 无活跃桥（终端/浏览器界面）时同步执行原路径（无 X11 目标时
         * X11InputHub 内部安全返回 false，行为与旧版一致）。
         */
        fun postX11Input(block: () -> Unit) {
            val b = activeBridge
            if (b != null) b.postSend(block) else block()
        }
    }

    // 触摸偏好（termux-x11：touchMode / scaleTouchpad / tapToMove）
    private var touchMode = TOUCH_SIMULATED
    private var scaleTouchpad = true
    private var tapToMove = false

    /**
     * v7.0 官方对齐：当前按住的鼠标键（InputStub.BUTTON_*，0 = 未按）。
     * 取代旧 pressedLeft/padPressed 双状态 —— 官方 TrackpadInputStrategy /
     * SimulatedTouchInputStrategy 各自只有一个 mHeldButton。
     */
    private var heldButton = 0

    /**
     * v7.0 官方 tapToMove（轻点拖拽）：第一击轻点后挂起的延迟单击。
     * 官方 GestureListener.onTap：tapToMove 开启时左键轻点【不立即点击】，
     * 而是 sendEmptyMessageDelayed(BUTTON_LEFT, getDoubleTapTimeout())；
     * 窗口内第二击按下（onDoubleTapEvent ACTION_DOWN）则撤消息改为按下
     * 左键进入拖拽，窗口内没有第二击才补发普通单击。旧实现“第一击立即
     * 按下并保持”→ 点一下就变成长按（用户实测），本字段即官方延迟消息。
     */
    private var tapPending = false
    private val tapPendingRunnable = Runnable {
        if (!tapPending) return@Runnable
        tapPending = false
        // 窗口内无第二击 → 补发普通单击（官方 mGestureListenerHandler 同款）
        sendButton(InputStub.BUTTON_LEFT, true)
        sendButton(InputStub.BUTTON_LEFT, false)
    }

    /** 官方双击窗口：ViewConfiguration.getDoubleTapTimeout()（默认 300ms） */
    private val doubleTapTimeoutMs: Long =
        android.view.ViewConfiguration.getDoubleTapTimeout().toLong()

    /**
     * v7.0 官方 TapGestureDetector 对齐：逐指初始位置（pointerId → [x,y]）。
     * 任一指滑出 slop 即取消轻点/长按候选（trackMoveEvent 同款）—— 取代
     * 旧 twoMoved 的 0.5px 抖动阈值（双指轻点右键几乎必被误取消）。
     */
    private val pointerStarts = HashMap<Int, FloatArray>()

    /** 本手势出现过的最大手指数（官方 mPointerCount：决定轻点/长按的鼠标键） */
    private var maxPointers = 1

    /**
     * 从偏好同步触摸方式与触控板参数（真实生效：本桥是 LinBox X11 的
     * 触摸事件入口，等效上游 TouchInputHandler.reloadPreferences）。
     * 方式切换时释放悬挂的按压/手势状态，防止旧模式的左键"按住不放"。
     */
    fun reloadTouchPrefs(p: Prefs) {
        val newMode = p.touchMode.get().toIntOrNull() ?: TOUCH_SIMULATED
        if (newMode != touchMode) {
            // v6.8：切换方式时解除长按定时（旧模式的悬挂手势全部收尾）
            disarmLongPress()
            if (heldButton != 0) { sendButton(heldButton, false); heldButton = 0 }
            cancelTapPending()
            mode = Mode.IDLE
            // v2.33：冲掉直接触摸可能遗留的 X 侧活跃触摸（0..9）——否则触摸
            // 仿真按钮 1 永久卡按，此后一切左键按下被 dix 丢弃（点击全失灵）；
            // 未激活的 TouchEnd 在 X 侧被丢弃，零副作用。
            closeAllTouches()
            synchronized(dtLock) { dtCount = 0; dtDirty = false }
            flushHandler.removeCallbacks(dtFlushRunnable); dtFlushPending = false
            // v2.30.1：切换方式时丢弃未冲刷的相对增量，防止旧模式残余在新模式生效
            dropPendingMoves()
        }
        touchMode = newMode
        scaleTouchpad = p.scaleTouchpad.get()
        tapToMove = p.tapToMove.get()
    }

    // 视图→X 坐标变换参数
    private var surfaceW = 0
    private var surfaceH = 0
    private var screenW = 0
    private var screenH = 0
    private var scaleX = 1f
    private var scaleY = 1f

    // 手势状态
    private var mode = Mode.IDLE
    private var downX = 0f; private var downY = 0f
    private var downAt = 0L
    // v6.10：DOWN 事件的 MotionEvent 时间戳（eventTime）—— 轻点/长按仲裁的
    // 唯一权威时间轴（见 ACTION_UP 处理，不受主线程调度抖动影响）
    private var downEventTime = 0L
    private var moved = false
    // v6.9：本手势长按是否已到点 —— 轻点/长按唯一分界（原 260ms 轻点窗 +
    // 400ms 长按之间存在 260~400ms 死区：既不轻点也不长按；更慢的轻点
    // 直接进入长按拖拽 → 用户实测"轻点时变成了长按"）
    private var longPressFired = false
    private var curX = 0f; private var curY = 0f
    // v2.33.1：本手势 tap 落点（X 坐标）—— 按下与释放共用同一条 absolute
    // 坐标（释放零位移）；双击吸附记录同一落点（吸附语义对齐按下瞬间）
    private var tapX = 0f; private var tapY = 0f

    // 双指（滚轮质心增量；v7.0 起轻点取消改用逐指 slop 判定 pointerStarts）
    private var lastTwoY = 0f
    private var scrollAccum = 0f

    // 双击吸附（X 坐标）
    private var lastTapX = -1f; private var lastTapY = -1f
    private var lastTapAt = 0L

    // v6.10（用户：“短按点击还是变成了长按”）：slop 回归上游 1.0 倍 ——
    // TapGestureDetector 用 getScaledTouchSlop() 原值判轻点 candidacy，
    // 此前 ×1.5 令略有漂移的轻点（正常按压手指抖动 10~40px）全部判
    // moved=true → 模拟触摸“轻点无任何反应”、触控板“点了不动光标动”。
    private val slopPx = ViewConfiguration.get(context).scaledTouchSlop
    private val dblTapMaxMs = 450L
    private val wheelStep = 40f

    // ---- v6.8（winemu/termux-x11 对齐）：长按检测 ----
    // 上游 TapGestureDetector → GestureListener.onLongPress 语义：按下后
    // ViewConfiguration.getLongPressTimeout()（默认 400ms，随系统设置）内
    // 未滑出 slop → 长按成立：
    // - 模拟触摸：光标先绝对定位到指尖，再按下左键进入拖拽（拖拽期间
    //   发相对增量，v2.30 wine warp 兼容语义保留）；
    // - 触控板：tapToMove 关闭时长按 = 左键按住 + 相对拖拽（上游
    //   TrackpadInputStrategy.onPressAndHold 同款）；tapToMove 开启时
    //   上游返回 false（不进入拖拽）。
    // 手势结束/滑出 slop/落下第二指即解除定时，主线程专用。
    private val mainHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { onLongPressFired() }
    // v6.10：长按阈值加 400ms 下限 —— 部分机型无障碍“触摸与按住延迟”设为
    // “短”（~300ms）时 getLongPressTimeout() 偏短，正常速度的短按也会被
    // 判成长按；wine 场景统一 400ms 下限（与事件时间轴仲裁共用同一常量）。
    private val longPressTimeoutMs: Long =
        ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(400L)

    /** 长按定时器是否挂着（DOWN 挂起，MOVE 出 slop / 多指 / UP / CANCEL 解除）。 */
    private fun armLongPress() {
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.postDelayed(longPressRunnable, longPressTimeoutMs)
    }

    private fun disarmLongPress() {
        mainHandler.removeCallbacks(longPressRunnable)
    }

    /** v7.0：撤销挂起的 tapToMove 延迟单击（手势异常收尾/模式切换/双击拖拽接管时） */
    private fun cancelTapPending() {
        tapPending = false
        mainHandler.removeCallbacks(tapPendingRunnable)
    }

    /** v7.0：挂起官方 tapToMove 延迟单击（doubleTapTimeout 内无第二击才补发） */
    private fun armTapPending() {
        cancelTapPending()
        tapPending = true
        mainHandler.postDelayed(tapPendingRunnable, doubleTapTimeoutMs)
    }

    /**
     * v7.0 官方 mouseButtonFromPointerCount：1=左 2=右 3=中，其余未定义。
     */
    private fun buttonForPointerCount(count: Int): Int = when (count) {
        1 -> InputStub.BUTTON_LEFT
        2 -> InputStub.BUTTON_RIGHT
        3 -> InputStub.BUTTON_MIDDLE
        else -> InputStub.BUTTON_UNDEFINED
    }

    /**
     * v7.0：任一受跟踪指针自初始位置滑出 slop（官方 trackMoveEvent 同款）。
     * 返回 true 时调用方应取消轻点/长按候选（moved=true + disarm）。
     */
    private fun anyPointerBeyondSlop(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            val s = pointerStarts[id] ?: continue
            val dx = event.getX(i) - s[0]
            val dy = event.getY(i) - s[1]
            if (dx * dx + dy * dy > slopPx * slopPx) return true
        }
        return false
    }

    /** 长按到点（主线程）：仍处于未滑动状态才触发（官方 mTapCancelled 闸门）。 */
    private fun onLongPressFired() {
        if (moved || longPressFired) return
        if (mode != Mode.ONE && mode != Mode.TWO) return
        if (pointerStarts.isEmpty()) return
        longPressFired = true
        // v7.0 官方对齐：长按的鼠标键由最大手指数决定（双指长按 = 右键按住，
        // 三指长按 = 中键按住 —— GestureListener.onLongPress +
        // mouseButtonFromPointerCount 同款）
        val button = buttonForPointerCount(maxPointers)
        if (button == InputStub.BUTTON_UNDEFINED) return
        when (touchMode) {
            TOUCH_SIMULATED -> {
                // 官方：光标先绝对定位到首指落点（moveCursorToScreenPoint +
                // onPressAndHold 两条消息；旧实现把坐标塞进按键消息——被
                // X server 忽略，拖拽起点错位），再按下鼠标键；随后 MOVE
                // 走相对增量拖拽（v2.30 wine warp 兼容语义保留）。
                val p = toXpx(downX, downY)
                sendAbsPress(p[0], p[1], button)
                heldButton = button
                // 拖拽即时生效：增量基准重置到当前指尖（不吃 slop 死区）
                downX = curX; downY = curY
                moved = true
            }
            TOUCH_TRACKPAD -> {
                // 官方：tapToMove 开启时左键长按不进入拖拽（onPressAndHold
                // 返回 false）；其余（含双指右键/三指中键长按）按住进入拖拽
                if (button == InputStub.BUTTON_LEFT && tapToMove) return
                if (heldButton == 0) {
                    sendButton(button, true)
                    heldButton = button
                }
                // v7.0（用户：“不开轻点拖拽需要长按很久才生效，而且长按
                // 拖动丢失”）：长按到点即进入拖拽并置 moved —— 旧实现保持
                // moved=false，拖拽要等手指再滑出 slop 才开始出增量，
                // 400ms 后还要多滑 8dp 才看得见拖动（“很久才生效”）；
                // 误滑出 slop 的手势长按定时早被取消（“拖动丢失”）。
                // 到点即拖 + 基线重置：长按一生效拖拽立即跟随手指。
                moved = true
            }
        }
    }

    // ---- v2.30.1 相对增量合并器（motion coalescer）/ v2.31 后台线程冲刷 ----
    // MOVE 事件只累加 pending（零 JNI），由专用后台线程 Handler 以
    // MOTION_FLUSH_INTERVAL_MS 最小间隔冲刷（一条合并后的 relative
    // sendCursorMove）。pending 由 pendLock 保护：冲刷线程与主线程
    // （手势结束的同步兜底冲刷）并发排水，总量精确守恒不重不漏。
    private val pendLock = Any()
    private var pendX = 0f
    private var pendY = 0f
    @Volatile private var flushPending = false
    private val flushHandler = Handler(flushThread.looper)
    private val flushRunnable = Runnable { flushPendingMoves() }

    /**
     * v2.33.1（滑屏掉帧根修 —— 主线程解耦）：所有 X 输入 socket 写统一
     * 投递到专用输入线程（与 motion 冲刷同线程 FIFO 保序）。
     *
     * 背景：v2.30.1 起三轮"事件量"优化（合并器/快照/清理风暴根治）均未
     * 消除滑屏掉帧 —— 证明瓶颈不是事件量，而是阻塞源：手势边界冲刷
     * （flushPendingMoves/flushDirectMoves 的同步调用）、tap 按键、双指
     * 滚轮、TouchEnd 收尾全部在主线程直写 X socket。sendMouseEvent/
     * sendTouchEvent 是 @FastNative 的 socket 直写 —— wine/桌面满载时
     * X server 输入线程消费变慢，socket 缓冲顶住后主线程 write 阻塞，
     * Choreographer 跳帧 = 掉帧（用户场景："只要滑动屏幕就严重掉帧"）。
     *
     * 修复后主线程触摸路径 100% 零 socket I/O：DOWN/UP/MOVE/滚轮/触摸
     * 边界只入队（Handler.post 微秒级），真实写全部发生在输入线程；
     * FIFO 顺序保证按下→移动→抬起时序与主线程手势时序一致（LorieView
     * native 侧线程安全，单写者模型下无并发问题）。输入线程被 X server
     * 短暂拖慢时事件排队但不丢（总量守恒），UI 帧率与 X 负载彻底解耦。
     */
    private fun postSend(block: () -> Unit) {
        flushHandler.post(block)
    }

    /** v2.33.1：主线程手势收尾改为异步冲刷（FIFO 保证先于后续按钮释放执行） */
    private fun postFlushMoves() {
        flushHandler.post { flushPendingMoves() }
    }

    private fun accumulateMove(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        synchronized(pendLock) {
            pendX += dx
            pendY += dy
        }
        if (!flushPending) {
            flushPending = true
            flushHandler.postDelayed(flushRunnable, MOTION_FLUSH_INTERVAL_MS)
        }
    }

    /** 输入线程执行（冲刷定时器/手势收尾的异步冲刷任务）；排空增量总量精确守恒。 */
    private fun flushPendingMoves() {
        flushHandler.removeCallbacks(flushRunnable)
        flushPending = false
        val dx: Float
        val dy: Float
        synchronized(pendLock) {
            dx = pendX
            dy = pendY
            pendX = 0f; pendY = 0f
        }
        if (dx == 0f && dy == 0f) return
        if (!LorieView.connected()) return
        keySender.sendCursorMove(dx, dy, true)
    }

    /** 取消未冲刷的增量（手势系统取消/模式切换时，避免迟到冲刷发出意外移动）。 */
    private fun dropPendingMoves() {
        flushHandler.removeCallbacks(flushRunnable)
        flushPending = false
        synchronized(pendLock) { pendX = 0f; pendY = 0f }
    }

    // ---- v2.31 手柄指针剥离（pad pointer stripping）----
    // 虚拟手柄元素（摇杆/十字键/按钮）自 v2.16.4 起不消费自己的指针
    // 事件 —— 终端浏览器侧由 WebView 过滤器消费兜底；X11 侧 LorieView
    // 是裸 interop View，未消费的手柄指针会原样漏进本桥：按住手柄
    // （开火/摇杆）时另一根手指滑动屏幕，桥看到 2 指流 → 误判双指
    // 滚轮 → 视角不旋转（用户实测症状）。这里按 pointerId 跟踪"屏幕
    // 手势指针"（DOWN 落点不在手柄元素命中矩形内的指针），构造只含
    // 这些指针的干净 MotionEvent 交给手势逻辑；手柄指针对桥完全不可
    // 见，手柄元素照常经 Compose 命中路径自收事件，两边各收各的指针。
    // 坐标系：命中矩形登记的是 positionInWindow 窗口坐标，onTouch 里把
    // 事件坐标加上 LorieView 的窗口偏移后比对（全屏铺满时偏移为 0）。
    private val trackedPadIds = ArrayList<Int>(4)
    private val stripProps = Array(10) { MotionEvent.PointerProperties() }
    private val stripCoords = Array(10) { MotionEvent.PointerCoords() }
    private val stripIdx = IntArray(10)
    private val viewWinPos = IntArray(2) // LorieView 相对窗口原点偏移（视图坐标→窗口坐标）
    // v2.32：偏移缓存 —— v2.31 起每个事件都调 getLocationInWindow（遍历
    // 视图树 + 拿布局锁），120Hz 触摸下有可测开销。布局位置不变期间直接
    // 复用缓存；位置变化（旋转/布局）由监听器置脏。
    @Volatile private var viewWinPosDirty = true
    private val viewWinPosWatcher = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        viewWinPosDirty = true
    }.also { view.addOnLayoutChangeListener(it) }

    /** 桥与视图分离（离开 X11 界面）时清理手势痕迹与悬挂冲刷。 */
    fun onDetached() {
        dropPendingMoves()
        disarmLongPress()
        // v7.0：撤销挂起的 tapToMove 延迟单击（桥已分离，不再补发点击）
        cancelTapPending()
        synchronized(dtLock) { dtCount = 0; dtDirty = false }
        flushHandler.removeCallbacks(dtFlushRunnable)
        dtFlushPending = false
        // v2.33：分离前补发孤儿按钮释放 + 冲掉可能遗留的 X 侧活跃触摸
        //（下一会话重建桥时 healOrphanButtons 会再次自愈，双保险）
        // v2.33.1：入队输入线程（FIFO 在任何排队中的旧事件之后收尾）
        if (LorieView.connected()) {
            postSend {
                view.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, false, true)
                view.sendMouseEvent(0f, 0f, InputStub.BUTTON_MIDDLE, false, true)
                view.sendMouseEvent(0f, 0f, InputStub.BUTTON_RIGHT, false, true)
                for (id in 0..9) view.sendTouchEvent(XI_TOUCH_END, id, 0, 0)
            }
        }
        trackedPadIds.clear()
        view.removeOnLayoutChangeListener(viewWinPosWatcher)
    }

    /**
     * 剥离"落在手柄元素内"的指针。返回 null = 本事件与桥无关（全部
     * 指针都属于手柄或无受跟踪指针）；返回原事件 = 无需剥离（零分配
     * 直通）；否则构造只含受跟踪指针的子集事件（action 相应重映射：
     * 桥内首指的按下降级为 DOWN、手柄指针的按下/抬起降级为 MOVE）。
     */
    // v2.32.2：分流激活标志（X11Screen update 块与容器 padSplitActive
    // 同源同步）。激活期间容器已把屏幕流剥成纯屏幕指针、并按流视角
    // 归一了 DOWN/UP —— 本层 trackedPadIds 记账与之双层重复，且存在
    // 记账时序差：POINTER_UP 时记账被掏空，随后同指的系统级 UP 到达
    // 时 hadTracked=false 被吞 → 状态机收不到 UP（左键卡按/tap 检测
    // 不触发，用户实测“点击屏幕失灵”）。激活期间恒等直通零开销；
    // 记账剥离仅保留为容器直通路径（手柄未开/命中区未布局窗口）兜底。
    @Volatile var splitActive = false

    /** 桥侧剥离入口；分流激活且有命中矩形时直通（与容器第 4 守卫条件
     *  精确对齐：无矩形的未布局窗口内仍走记账兜底，手柄指针不漏状态机） */
    private fun filterPadPointers(event: MotionEvent): MotionEvent? {
        if (splitActive &&
            (GamepadController.hasElementHits() || GamepadController.hasToolbarHit())
        ) return event
        if (!GamepadController.hasElementHits()) {
            trackedPadIds.clear()
            return event
        }
        val action = event.actionMasked
        val aIdx = event.actionIndex
        val hadTracked = trackedPadIds.isNotEmpty()
        var forward = -1 // 转发的 action；-1 = 丢弃本事件
        var includeActionPointer = false // POINTER_UP 子集需包含抬起指针（Android 语义）

        // 视图坐标→窗口坐标（命中矩形登记的是 positionInWindow 窗口坐标；
        // LorieView 全屏铺满时偏移为 0，非铺满布局下也能正确对齐）。
        // v2.32：偏移走缓存（布局不变期间零查询），位置变化时监听器置脏。
        if (viewWinPosDirty) {
            view.getLocationInWindow(viewWinPos)
            viewWinPosDirty = false
        }
        val winX = viewWinPos[0].toFloat()
        val winY = viewWinPos[1].toFloat()

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                trackedPadIds.clear()
                if (!GamepadController.isOverPadElement(event.x + winX, event.y + winY))
                    trackedPadIds.add(event.getPointerId(0))
                if (trackedPadIds.isNotEmpty()) forward = MotionEvent.ACTION_DOWN
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                if (!GamepadController.isOverPadElement(event.getX(i) + winX, event.getY(i) + winY))
                    trackedPadIds.add(event.getPointerId(i))
                forward = when {
                    trackedPadIds.isEmpty() -> -1
                    hadTracked -> MotionEvent.ACTION_POINTER_DOWN
                    else -> MotionEvent.ACTION_DOWN // 桥视角：这是手势的第一根手指
                }
            }
            MotionEvent.ACTION_MOVE ->
                if (trackedPadIds.isNotEmpty()) forward = MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_POINTER_UP -> {
                // indexOf+removeAt：规避 ArrayList<Int>.remove 的索引/按值重载歧义
                val li = trackedPadIds.indexOf(event.getPointerId(aIdx))
                val lifted = li >= 0
                if (lifted) trackedPadIds.removeAt(li)
                when {
                    lifted && trackedPadIds.isNotEmpty() -> {
                        forward = MotionEvent.ACTION_POINTER_UP
                        includeActionPointer = true
                    }
                    lifted -> {
                        forward = MotionEvent.ACTION_UP // 桥内最后一根手指抬起
                        includeActionPointer = true
                    }
                    else -> if (hadTracked) forward = MotionEvent.ACTION_MOVE
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                // v2.32.2：恒转发（原 hadTracked=false 时吞掉）。记账在
                // POINTER_UP 时已被掏空，同指随后的系统级 UP 必然
                // hadTracked=false —— 吞掉会让手势状态机收不到 UP
                // （左键卡按/tap 检测不触发）。状态机在 IDLE 下收到
                // 多余 UP/CANCEL 仅归位状态，无副作用。
                forward = action
        }

        if (forward < 0) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                trackedPadIds.clear()
            return null
        }

        // 子集 = 全部受跟踪指针（POINTER_UP 额外包含抬起的指针）
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (trackedPadIds.contains(event.getPointerId(i))) {
                stripIdx[count++] = i
                if (count == stripIdx.size) break
            }
        }
        if (includeActionPointer && count < stripIdx.size &&
            event.actionIndex < event.pointerCount && !trackedPadIds.contains(event.getPointerId(aIdx))
        ) stripIdx[count++] = event.actionIndex
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            trackedPadIds.clear()
        if (count == 0) return null
        if (count == event.pointerCount && forward == action) return event // 无需剥离

        var fa = forward
        if (forward == MotionEvent.ACTION_POINTER_DOWN || forward == MotionEvent.ACTION_POINTER_UP) {
            var pos = 0
            for (j in 0 until count) if (stripIdx[j] == aIdx) { pos = j; break }
            fa = forward or (pos shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        for (j in 0 until count) {
            event.getPointerProperties(stripIdx[j], stripProps[j])
            event.getPointerCoords(stripIdx[j], stripCoords[j])
        }
        return MotionEvent.obtain(
            event.downTime, event.eventTime, fa, count,
            stripProps, stripCoords, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
            event.source, event.flags
        )
    }

    private enum class Mode { IDLE, ONE, TWO, TWO_DONE }

    fun updateTransform(surfaceW: Int, surfaceH: Int, screenW: Int, screenH: Int) {
        this.surfaceW = surfaceW
        this.surfaceH = surfaceH
        this.screenW = screenW
        this.screenH = screenH
        scaleX = if (surfaceW > 0) screenW.toFloat() / surfaceW else 1f
        scaleY = if (surfaceH > 0) screenH.toFloat() / surfaceH else 1f
        renderData.imageWidth = surfaceW
        renderData.imageHeight = surfaceH
        renderData.screenWidth = screenW
        renderData.screenHeight = screenH
        renderData.scale.set(scaleX, scaleY)
    }

    private fun toXpx(vx: Float, vy: Float): FloatArray {
        val x = (vx * scaleX).toInt().coerceIn(0, (screenW - 1).coerceAtLeast(0))
        val y = (vy * scaleY).toInt().coerceIn(0, (screenH - 1).coerceAtLeast(0))
        return floatArrayOf(x.toFloat(), y.toFloat())
    }

    private fun sendMoveX(x: Int, y: Int) {
        // v2.33.1：入队输入线程（绝对定位 motion，主线程零 socket I/O）
        val xf = x.toFloat()
        val yf = y.toFloat()
        postSend {
            if (LorieView.connected())
                view.sendMouseEvent(xf, yf, InputStub.BUTTON_UNDEFINED, false, false)
        }
    }

    private fun sendButton(button: Int, down: Boolean) {
        // (0,0)+relative = 纯按键事件（在当前指针位置按下/抬起），
        // 与 InputDeviceManager 的注入路径完全一致。
        // v2.33.1：入队输入线程（与 motion 冲刷 FIFO 保序，主线程零阻塞）
        postSend {
            if (LorieView.connected())
                view.sendMouseEvent(0f, 0f, button, down, true)
        }
    }

    // ---- v7.0 官方两消息点击协议（“定位 + 点击”必须拆两条消息） ----
    // Linbox-Tx11-aar cmdentrypoint.c 的 EVENT_MOUSE 分发实证：
    //   detail != 0（按键）→ QueuePointerEvents(ButtonPress/Release, ..., 
    //                          POINTER_RELATIVE, NULL) —— x/y 完全被忽略！
    //   detail == 0（移动）→ POINTER_ABSOLUTE | POINTER_SCREEN 绝对 MotionNotify
    // 上游 TouchInputHandler.moveCursorToScreenPoint（sendCursorMove 绝对）
    // + InputEventSender.sendMouseClick（(0,0)+按键）正是这两步。旧实现把
    // 坐标塞进按键消息 → 坐标被 X server 丢弃 → 模拟触摸轻点/长按全部落在
    // 旧光标位置（用户实测“模拟触摸完全无效果”的直接根因）。

    /** 官方 moveCursorToScreenPoint：光标绝对定位到 (px,py)（X 屏幕坐标） */
    private fun sendAbsMove(px: Float, py: Float) {
        postSend {
            if (LorieView.connected())
                view.sendMouseEvent(px, py, InputStub.BUTTON_UNDEFINED, false, false)
        }
    }

    /** 官方 onTap 语义：先绝对定位，再在指针处完整单击 */
    private fun sendAbsClick(px: Float, py: Float, button: Int) {
        postSend {
            if (LorieView.connected()) {
                view.sendMouseEvent(px, py, InputStub.BUTTON_UNDEFINED, false, false)
                view.sendMouseEvent(0f, 0f, button, true, false)
                view.sendMouseEvent(0f, 0f, button, false, false)
            }
        }
    }

    /** 官方 onPressAndHold 语义：先绝对定位，再按下（拖拽跟随，抬手释放） */
    private fun sendAbsPress(px: Float, py: Float, button: Int) {
        postSend {
            if (LorieView.connected()) {
                view.sendMouseEvent(px, py, InputStub.BUTTON_UNDEFINED, false, false)
                view.sendMouseEvent(0f, 0f, button, true, false)
            }
        }
    }

    // ---- v2.33 点击可靠性自愈（用户实测 v2.32.2：左右键/点击全失灵）----
    // 病灶模型：X 侧 lorieMouse/lalieTouch 按钮状态一旦卡在"按下"
    //（直接触摸遗留活跃触摸的仿真按钮、任一层丢失的 UP、分流漏记账），
    // dix 对重复 ButtonPress 直接丢弃、孤儿 ButtonRelease 直接丢弃
    // —— 此后一切左键/右键（触摸点击与手柄鼠标键同路）全部失灵，
    // 而相对运动不受影响（滑动照常、视角照转），与用户症状逐字吻合。
    // 自愈三件套全部基于"未按下的释放被 dix 安全丢弃"这一语义，零副作用：
    // 1) 首次输入前补发 1/2/3 键孤儿释放（healOrphanButtons）
    // 2) 新手势 DOWN 前强制归位桥内悬挂按压（resyncStuckPress）
    // 3) 切换触摸方式时补发 XI_TouchEnd(0..9) 冲掉直接触摸遗留的
    //    活跃触摸（closeAllTouches，解除触摸仿真按钮的永久卡按）

    // 诊断计数（X11 设置面板"输入诊断"只读展示；UI 线程专用）
    var diagDowns = 0; private set
    var diagUps = 0; private set
    var diagResyncs = 0; private set
    var diagTaps = 0; private set
    var diagHeals = 0; private set

    /** 首次输入前的孤儿按钮释放（healDone 后零开销） */
    private var healDone = false
    private fun healOrphanButtons() {
        if (healDone) return
        if (!LorieView.connected()) return
        healDone = true
        diagHeals++
        // v2.33.1：入队输入线程（FIFO 先于本手势的按下事件执行）
        postSend {
            view.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, false, true)
            view.sendMouseEvent(0f, 0f, InputStub.BUTTON_MIDDLE, false, true)
            view.sendMouseEvent(0f, 0f, InputStub.BUTTON_RIGHT, false, true)
        }
    }

    /** 新手势起点归位悬挂按压：卡键自愈 + 重复按下防抖（X 侧会被丢弃的按下不再发出） */
    private fun resyncStuckPress() {
        if (heldButton != 0) {
            sendButton(heldButton, false)
            heldButton = 0
            diagResyncs++
        }
    }

    /** 冲掉 0..9 号触摸的 X 侧活跃状态（解除触摸仿真按钮 1 的永久卡按） */
    private fun closeAllTouches() {
        // v2.33.1：入队输入线程（与后续触摸事件 FIFO 保序）
        postSend {
            if (LorieView.connected()) {
                for (id in 0..9) view.sendTouchEvent(XI_TOUCH_END, id, 0, 0)
            }
        }
    }

    /** 输入诊断文本（X11 设置面板"输入诊断"只读展示） */
    fun diagText(): String = buildString {
        append("触摸方式=")
        append(
            when (touchMode) {
                TOUCH_TRACKPAD -> "触控板"
                TOUCH_DIRECT -> "直接触摸"
                else -> "模拟触摸"
            }
        )
        append("  X连接="); append(if (LorieView.connected()) "是" else "否")
        append('\n')
        append("DOWN="); append(diagDowns)
        append("  UP="); append(diagUps)
        append("  轻点="); append(diagTaps)
        append('\n')
        append("卡键归位="); append(diagResyncs)
        append("  自愈="); append(diagHeals)
        append("  桥按压态: 按键="); append(
            when (heldButton) {
                InputStub.BUTTON_LEFT -> "左键"
                InputStub.BUTTON_MIDDLE -> "中键"
                InputStub.BUTTON_RIGHT -> "右键"
                else -> "无"
            }
        )
        append(" 轻点拖拽待击="); append(if (tapPending) "是" else "否")
    }

    /** 双击吸附：若处于双击窗口内且落点接近上次轻点，返回上次落点。 */
    private fun snapPoint(vx: Float, vy: Float): FloatArray {
        val now = android.os.SystemClock.uptimeMillis()
        val px = toXpx(vx, vy)
        if (lastTapAt > 0 && now - lastTapAt <= dblTapMaxMs) {
            val dx = px[0] - lastTapX
            val dy = px[1] - lastTapY
            val slopX = slopPx * scaleX
            val slopY = slopPx * scaleY
            if (dx * dx + dy * dy <= slopX * slopX + slopY * slopY) {
                return floatArrayOf(lastTapX, lastTapY)
            }
        }
        return px
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (!LorieView.connected() || screenW <= 0 || screenH <= 0) return true
        // v2.33：首次输入前补发孤儿按钮释放（卡键自愈；未卡则被 dix 丢弃）
        healOrphanButtons()
        // v6.10：注入/鼠标流直通（上游 TouchInputHandler 的 mHMListener 同款路由）。
        // 壳层触控板（TrackpadController）注入的合成事件（pointerId>=99 /
        // TOOL_TYPE_MOUSE）与真外接鼠标事件不进手势状态机，按“位置+按键”
        // 1:1 直注 X —— 修复此前“壳层触控板注入流被二次当手势”的双层叠加
        //（注入 DOWN+UP 被桥再判轻点/长按，拖拽注入被桥换成纯光标移动等）。
        if (isMouseLikeEvent(event)) return onMousePassthrough(event)
        // v2.31：剥离手柄元素指针（手柄未显示时零开销直通）
        val ev = filterPadPointers(event) ?: return true
        try {
            // 触摸方式分发（termux-x11 touchMode；X11 设置面板切换后即时生效）
            return when (touchMode) {
                TOUCH_TRACKPAD -> onTouchTrackpad(ev)
                TOUCH_DIRECT -> onTouchDirect(ev)
                else -> onTouchSimulated(ev)
            }
        } finally {
            if (ev !== event) ev.recycle()
        }
    }

    // ---- v6.10：鼠标/注入流直通 ----

    /** 注入合成流（壳层触控板，指针 id ≥ 99）或鼠标事件（真外接鼠标）
     *  判定 —— 与上游 TouchInputHandler.handleTouchEvent 的
     *  TOOL_TYPE_MOUSE / SOURCE_MOUSE 分流同款。 */
    private fun isMouseLikeEvent(e: MotionEvent): Boolean {
        if ((e.source and android.view.InputDevice.SOURCE_MOUSE) ==
            android.view.InputDevice.SOURCE_MOUSE
        ) return true
        if ((e.source and android.view.InputDevice.SOURCE_MOUSE_RELATIVE) ==
            android.view.InputDevice.SOURCE_MOUSE_RELATIVE
        ) return true
        for (i in 0 until e.pointerCount) {
            if (e.getPointerId(i) >= com.linbox.core.input.INJECTED_POINTER_ID) return true
            if (e.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) return true
        }
        return false
    }

    /** 直通流当前按着的键（0 = 无）；释放时按同键抬起。 */
    private var hmPressedButton = 0

    /** 鼠标/注入流 1:1 直注 X：DOWN=定位+按下，MOVE=定位，UP=释放。 */
    private fun onMousePassthrough(event: MotionEvent): Boolean {
        val p = toXpx(event.x, event.y)
        val px = p[0]
        val py = p[1]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val btn = when {
                    (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ->
                        InputStub.BUTTON_RIGHT
                    (event.buttonState and MotionEvent.BUTTON_TERTIARY) != 0 ->
                        InputStub.BUTTON_MIDDLE
                    else -> InputStub.BUTTON_LEFT
                }
                hmPressedButton = btn
                diagDowns++
                // v7.0 官方两消息协议：按键消息的坐标会被 X server 忽略，
                // 必须先绝对定位（壳层触控板拖拽注入的落点从此精确到位，
                // 不再依赖光标恰好已停在注入位置）
                postSend {
                    if (LorieView.connected()) {
                        view.sendMouseEvent(px, py, InputStub.BUTTON_UNDEFINED, false, false)
                        view.sendMouseEvent(0f, 0f, btn, true, false)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> postSend {
                if (LorieView.connected())
                    view.sendMouseEvent(px, py, InputStub.BUTTON_UNDEFINED, false, false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val btn = if (hmPressedButton != 0) hmPressedButton else InputStub.BUTTON_LEFT
                hmPressedButton = 0
                diagUps++
                postSend {
                    if (LorieView.connected())
                        view.sendMouseEvent(0f, 0f, btn, false, false)
                }
            }
        }
        return true
    }

    /**
     * 模式 2 —— 模拟触摸屏（termux-x11 "Simulated touchscreen"，v7.0 按
     * 官方 TouchInputHandler + SimulatedTouchInputStrategy 逐项对齐重写）：
     *
     * - 按下：不按任何键（上游 SimulatedTouch.onMotionEvent 在 DOWN 不动作）；
     * - 滑动（未长按）：光标不跟随（上游 onScroll 仅 mIsDragging 时
     *   moveCursorToScreenPoint）；
     * - 轻点（任一指未滑出 slop 且长按未到点，末指抬起判定）：光标绝对
     *   定位到首指落点 + 点击（上游 onTap → moveCursorToScreenPoint →
     *   sendMouseClick —— v7.0 起拆成两条消息：定位的坐标在按键消息里
     *   会被 X server 丢弃，这正是旧版“模拟触摸完全无效果”的根因）；
     *   双击吸附保留（wine WM_LBUTTONDBLCLK 识别增强）；
     * - 长按（超时未滑动）：定位 + 按下进拖拽（官方 onPressAndHold），
     *   到点即拖（基线重置，无 slop 死区）；拖拽期间发相对增量
     *   （v2.30 wine FPS XWarpPointer 兼容语义保留），抬手释放；
     * - 轻点/长按的鼠标键由最大手指数决定（官方 mouseButtonFromPointerCount：
     *   1=左 2=右 3=中）——双指轻点=右键、三指轻点=中键、双指长按=右键
     *   拖拽，均在末指抬起时收尾；双指滑动 = 滚轮。
     */
    private fun onTouchSimulated(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // v2.33：新手势起点归位悬挂按压（丢失 UP 的卡键自愈）
                resyncStuckPress()
                mode = Mode.ONE
                downX = event.x; downY = event.y
                curX = downX; curY = downY
                downAt = android.os.SystemClock.uptimeMillis()
                downEventTime = event.eventTime
                moved = false
                longPressFired = false
                maxPointers = 1
                pointerStarts.clear()
                pointerStarts[event.getPointerId(0)] = floatArrayOf(event.x, event.y)
                diagDowns++
                // 长按检测挂起（到点未滑动 → 定位 + 按下进拖拽；官方多指
                // 长按不因落下第二指解除 —— 双指长按 = 右键按住）
                armLongPress()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // v7.0 官方 TapGestureDetector：登记新指初始位置，不解除长按
                val idx = event.actionIndex
                pointerStarts[event.getPointerId(idx)] =
                    floatArrayOf(event.getX(idx), event.getY(idx))
                maxPointers = maxOf(maxPointers, event.pointerCount)
                if (mode == Mode.ONE && event.pointerCount >= 2) {
                    mode = Mode.TWO
                    // v2.30.1：拖拽被双指手势接管前，立即冲刷残余增量（不丢尾段）
                    postFlushMoves()
                    scrollAccum = 0f
                    lastTwoY = (event.getY(0) + event.getY(1)) / 2f
                    // 官方：拖拽中落下第二指左键保持按住（onMotionEvent 只在
                    // 最终 UP 释放）—— 滚轮与拖拽可并存（拖选 + 滚动浏览）
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // v7.0：官方 trackMoveEvent —— 任一指滑出 slop 即取消
                // 轻点/长按候选（取代旧 twoMoved 的 0.5px 抖动阈值）
                if (!moved && anyPointerBeyondSlop(event)) {
                    moved = true
                    disarmLongPress()
                }
                when (mode) {
                    Mode.ONE -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        curX = event.x; curY = event.y
                        // 长按拖拽期间发相对增量（v2.30 wine warp 兼容）；
                        // 未拖拽（heldButton==0）时不发送任何移动 ——
                        // 上游语义：手指移动本身不动光标，不产生误点击。
                        // 注：拖拽中 downX 每帧滚动为最新位置，moved 置位后
                        // 恒为 true，慢速拖拽增量不丢（slop 只在未拖拽时判定）。
                        if (moved && heldButton != 0) {
                            accumulateMove(dx * scaleX, dy * scaleY)
                            downX = event.x; downY = event.y
                        }
                    }
                    Mode.TWO -> {
                        if (event.pointerCount >= 2) {
                            val avgY = (event.getY(0) + event.getY(1)) / 2f
                            val dy = (lastTwoY - avgY) * scaleY // 手指上滑 → 正值 → 内容下滚
                            lastTwoY = avgY
                            scrollAccum += dy
                            if (kotlin.math.abs(scrollAccum) >= wheelStep) {
                                val steps = (scrollAccum / wheelStep).toInt()
                                // v2.33.1：滚轮入队输入线程（主线程零 socket I/O）
                                val delta = steps * wheelStep
                                postSend {
                                    if (LorieView.connected())
                                        view.sendMouseWheelEvent(0f, delta)
                                }
                                scrollAccum -= steps * wheelStep
                            }
                        }
                    }
                    else -> {}
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // v7.0 官方 trackUpEvent + cancelLongTouchNotification：仅移除
                // 抬起指的记账并解除长按定时；轻点判定统一在末指 UP
                pointerStarts.remove(event.getPointerId(event.actionIndex))
                disarmLongPress()
                // 事件里 pointerCount 含抬起指：==2 即抬起后仅剩 1 指 →
                // 停止滚轮（3→2 时继续滚）
                if (event.pointerCount <= 2 && mode == Mode.TWO) mode = Mode.TWO_DONE
            }

            MotionEvent.ACTION_UP -> {
                // v2.30.1：手势结束先冲刷残余增量（不丢拖拽尾段）再抬键
                // v2.33.1：冲刷异步化（FIFO 保证先于按钮释放写入 X）
                diagUps++
                disarmLongPress()
                pointerStarts.clear()
                // v6.10：轻点/长按仲裁用 MotionEvent 事件时间轴（wine 打满
                // CPU 时主线程调度抖动可能令长按定时器先于排队的 UP 触发，
                // 墙钟判据不可靠）。真实按压时长 < 长按阈值 = 轻点候选。
                val tapDurMs = if (downEventTime > 0) event.eventTime - downEventTime else 0L
                val tapLike = !moved && tapDurMs < longPressTimeoutMs
                downEventTime = 0L
                // 官方顺序：先释放按住的键（strategy.onMotionEvent 在
                // tap 检测器之前），再判定轻点 —— 长按路径的“按下+释放”
                // 本身就是一次完整单击，不再补发 tap。
                if (heldButton != 0) {
                    postFlushMoves()
                    sendButton(heldButton, false)
                    heldButton = 0
                } else if (tapLike && !longPressFired) {
                    // v7.0 官方 onTap：按键由最大手指数决定；光标绝对定位到
                    // 首指落点 + 点击（两条消息 —— 按键消息的坐标会被丢弃）
                    val button = buttonForPointerCount(maxPointers)
                    if (button != InputStub.BUTTON_UNDEFINED) {
                        diagTaps++
                        if (button == InputStub.BUTTON_LEFT) {
                            // 左键轻点：双击吸附对齐按下瞬间落点 + 点击置前
                            val p = snapPoint(downX, downY)
                            val px = p[0]
                            val py = p[1]
                            tapX = px; tapY = py
                            X11WindowOps.raiseAt(px.toInt(), py.toInt())
                            sendAbsClick(px, py, InputStub.BUTTON_LEFT)
                            // 完整轻点 → 记录落点（X 坐标）供双击吸附
                            lastTapX = tapX; lastTapY = tapY
                            lastTapAt = android.os.SystemClock.uptimeMillis()
                        } else {
                            // 双指右键 / 三指中键轻点（官方 mouseButtonFromPointerCount）
                            val p = toXpx(downX, downY)
                            sendAbsClick(p[0], p[1], button)
                            lastTapAt = 0L
                        }
                    }
                } else {
                    lastTapAt = 0L
                }
                mode = Mode.IDLE
            }

            MotionEvent.ACTION_CANCEL -> {
                // v2.30.1：系统取消手势 → 丢弃未冲刷增量（不发出意外移动）
                dropPendingMoves()
                disarmLongPress()
                cancelTapPending()
                downEventTime = 0L
                pointerStarts.clear()
                if (heldButton != 0) {
                    // CANCEL：当前位置抬键（不 warp；tap 未发生不产生点击）
                    sendButton(heldButton, false)
                    heldButton = 0
                }
                mode = Mode.IDLE
            }
        }
        return true
    }

    // ---- v2.32.2 直接触摸（TOUCH_DIRECT）MOVE 合并器 ----
    // 原实现每 MOVE 事件逐指针直发 XI_TouchUpdate（120~240Hz 触摸下
    // 事件洪泛，X server 输入线程被逐事件唤醒，wine 满载时直接抢占
    // 渲染时间片 → 滑屏掉帧）。改为“最新全量快照 + 16ms 节流”：MOVE
    // 只更新快照（零 JNI），后台线程按最小间隔把快照构造成一条
    // ACTION_MOVE 直发（触摸屏语义：中间采样可丢，只消费最新位置，
    // 绝对坐标快照天然幂等）。DOWN/POINTER_DOWN/POINTER_UP/UP/CANCEL
    // 是 touch 序列边界，必须即时直发不可合并。快照由 dtLock 保护
    // （主线程手势收尾与冲刷线程并发排水，与相对合并器同款模式）。
    private val dtLock = Any()
    private val dtIds = IntArray(10)
    private val dtProps = Array(10) { MotionEvent.PointerProperties() }
    private val dtCoords = Array(10) { MotionEvent.PointerCoords() }
    /** UP/CANCEL 收尾用的残留槽位快照（锁内拷贝、锁外发 TouchEnd） */
    private val dtCloseIds = IntArray(10)
    private var dtCount = 0
    private var dtDirty = false
    private var dtDownTime = 0L
    private var dtDeviceId = 0
    private var dtSource = 0
    @Volatile private var dtFlushPending = false
    private val dtFlushRunnable = Runnable { flushDirectMoves() }

    private fun flushDirectMoves() {
        flushHandler.removeCallbacks(dtFlushRunnable)
        dtFlushPending = false
        var snap: MotionEvent? = null
        synchronized(dtLock) {
            if (!dtDirty || dtCount == 0) return
            dtDirty = false
            snap = MotionEvent.obtain(
                dtDownTime, android.os.SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE, dtCount, dtProps, dtCoords,
                0, 0, 1f, 1f, dtDeviceId, 0, dtSource, 0
            )
        }
        try {
            if (LorieView.connected()) keySender.sendTouchEvent(snap!!, renderData)
        } finally {
            snap!!.recycle()
        }
    }

    /**
     * v2.33.1：direct 模式触摸序列边界（DOWN/POINTER_DOWN/POINTER_UP/
     * UP/CANCEL）入队输入线程发送。系统 MotionEvent 在 dispatch 返回后
     * 会被 ViewRootImpl 回收复用，不能跨线程持有 —— obtain 一份副本入队，
     * 输入线程用完回收（每手势 2~3 次小分配，远低于优化前逐 MOVE 直发的
     * 开销；MOVE 走既有零分配快照合并器不受影响）。
     */
    private fun postTouchEvent(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        postSend {
            try {
                if (LorieView.connected()) keySender.sendTouchEvent(copy, renderData)
            } finally {
                copy.recycle()
            }
        }
    }

    /**
     * 模式 3 —— 直接触摸（termux-x11 "Direct touch"）：
     * 原始多点触摸不经手势转换，按 XI2 Touch 事件直注 X（坐标经
     * renderData 视图→X 屏幕变换）—— 触摸类游戏/应用获得真实多点触摸，
     * X server 同时向老程序模拟鼠标（xinput 保真，等效上游 NullInputStrategy）。
     * v2.32.2：MOVE 经快照合并器 16ms 节流（滑屏掉帧修复）；序列边界
     * （DOWN/POINTER_DOWN/POINTER_UP/UP/CANCEL）入队输入线程异步发送
     * （v2.33.1：主线程零 socket I/O，FIFO 保序）。
     */
    private fun onTouchDirect(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 新手势起点：重置快照并记录构造参数（downTime/deviceId/source）
                flushHandler.removeCallbacks(dtFlushRunnable); dtFlushPending = false
                synchronized(dtLock) {
                    dtCount = 0; dtDirty = false
                    dtDownTime = event.downTime
                    dtDeviceId = event.deviceId
                    dtSource = event.source
                }
                // v2.33.1：入队输入线程（主线程零 socket I/O）
                postTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                // 快照更新（零 JNI）：按 pointerId 槽位覆盖最新属性/坐标
                synchronized(dtLock) {
                    val n = event.pointerCount.coerceAtMost(dtIds.size)
                    for (i in 0 until n) {
                        val id = event.getPointerId(i)
                        var slot = dtIds.indexOf(id)
                        if (slot < 0 || slot >= dtCount) {
                            if (dtCount >= dtIds.size) continue
                            slot = dtCount++
                            dtIds[slot] = id
                        }
                        event.getPointerProperties(i, dtProps[slot])
                        event.getPointerCoords(i, dtCoords[slot])
                    }
                    dtDirty = true
                }
                if (!dtFlushPending) {
                    dtFlushPending = true
                    flushHandler.postDelayed(dtFlushRunnable, MOTION_FLUSH_INTERVAL_MS)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 该指 touch 序列结束：丢弃未冲刷快照（可能含已抬指旧
                // 坐标）并把该指移出快照槽（交换压缩），再直发 TouchEnd
                flushHandler.removeCallbacks(dtFlushRunnable); dtFlushPending = false
                synchronized(dtLock) {
                    dtDirty = false
                    val si = dtIds.indexOf(event.getPointerId(event.actionIndex))
                    if (si >= 0 && si < dtCount) {
                        for (j in si until dtCount - 1) {
                            dtIds[j] = dtIds[j + 1]
                            val tp = dtProps[j]; dtProps[j] = dtProps[j + 1]; dtProps[j + 1] = tp
                            val tc = dtCoords[j]; dtCoords[j] = dtCoords[j + 1]; dtCoords[j + 1] = tc
                        }
                        dtCount--
                    }
                }
                // v2.33.1：入队输入线程（FIFO 先于该指 TouchEnd 语义保留：
                // 子事件原样发送，X 侧按 actionIndex 结束对应指）
                postTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // UP 前先冲刷最新位置（不丢拖拽尾段）；CANCEL 只清态
                diagUps++
                // v2.33.1：冲刷异步化（FIFO 先于本 UP 入队发送）
                if (event.actionMasked == MotionEvent.ACTION_UP) postSend { flushDirectMoves() }
                flushHandler.removeCallbacks(dtFlushRunnable); dtFlushPending = false
                var leftover = 0
                synchronized(dtLock) {
                    // v2.33：残留触摸槽位记入 leftover（多指异常直发 UP 时，
                    // 其余手指的 X 侧触摸不能悬挂 —— 悬挂触摸的仿真按钮 1
                    // 会永久卡按，此后一切左键点击全失灵）
                    for (i in 0 until dtCount) dtCloseIds[i] = dtIds[i]
                    leftover = dtCount
                    dtCount = 0; dtDirty = false
                }
                postTouchEvent(event)
                // 原始 UP/CANCEL 已按 actionIndex 结束对应指；其余槽位 +
                // 重复的抬起指补发 TouchEnd（X 侧丢弃未激活的 TouchEnd，
                // 零副作用）—— 残留触摸的仿真按钮 1 会永久卡按，点击全失灵
                // v2.33.1：TouchEnd 收尾入队（FIFO 在本 UP 之后执行）
                for (i in 0 until leftover) {
                    val id = dtCloseIds[i]
                    postSend {
                        if (LorieView.connected()) view.sendTouchEvent(XI_TOUCH_END, id, 0, 0)
                    }
                }
            }
            else -> postTouchEvent(event) // POINTER_DOWN 等
        }
        return true
    }

    /**
     * 模式 1 —— 触控板（termux-x11 "Trackpad"，v7.0 按官方
     * TouchInputHandler + TrackpadInputStrategy 逐项对齐重写）：
     *
     * - 单指滑动 = 光标相对移动（scaleTouchpad 开启时按视图→X 拉伸比例
     *   放大；v2.30 wine warp 兼容相对增量保留）；
     * - 轻点（任一指未滑出 slop 且长按未到点，末指抬起判定，按键由最大
     *   手指数决定）：tapToMove 关闭 = 立即单击；开启 = 官方延迟单击
     *   （doubleTapTimeout 内无第二击才补发，第二击按下则进入拖拽）；
     * - 长按 = 按住拖拽（tapToMove 开启时官方不进入拖拽）；到点即拖，
     *   增量即时跟随（无 slop 死区）；
     * - 双指轻点 = 右键；三指轻点 = 中键；双指长按 = 右键按住拖拽；
     * - 双指滑动 = 滚轮。
     */
    private fun onTouchTrackpad(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // v2.33：新手势起点归位悬挂按压（丢失 UP 的卡键自愈）
                resyncStuckPress()
                mode = Mode.ONE
                downX = event.x; downY = event.y
                curX = downX; curY = downY
                downAt = android.os.SystemClock.uptimeMillis()
                downEventTime = event.eventTime
                moved = false
                longPressFired = false
                maxPointers = 1
                pointerStarts.clear()
                pointerStarts[event.getPointerId(0)] = floatArrayOf(event.x, event.y)
                diagDowns++
                if (tapToMove && tapPending) {
                    // v7.0 官方 tapToMove 双击拖拽（onDoubleTapEvent
                    // ACTION_DOWN 同款）：撤销挂起单击，按下左键进入拖拽；
                    // 本击期间滑动 = 拖拽，抬手释放 —— “轻点拖拽”从此是
                    // “双击并按住拖动”，单点不再误变长按（用户实测）
                    cancelTapPending()
                    if (heldButton == 0) {
                        sendButton(InputStub.BUTTON_LEFT, true)
                        heldButton = InputStub.BUTTON_LEFT
                    }
                    // 拖拽即时生效：不吃 slop 死区（官方 mIsDragging=true）
                    moved = true
                } else {
                    // 长按检测挂起（tapToMove 关闭时长按 = 左键按住拖拽）
                    armLongPress()
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // v7.0 官方 TapGestureDetector：登记新指初始位置；
                // 官方多指长按不因落下第二指解除（双指长按 = 右键按住）
                val idx = event.actionIndex
                pointerStarts[event.getPointerId(idx)] =
                    floatArrayOf(event.getX(idx), event.getY(idx))
                maxPointers = maxOf(maxPointers, event.pointerCount)
                if (event.pointerCount == 2 && mode == Mode.ONE) {
                    mode = Mode.TWO
                    // v2.30.1：拖拽被双指手势接管前，立即冲刷残余增量（不丢尾段）
                    postFlushMoves()
                    scrollAccum = 0f
                    lastTwoY = (event.getY(0) + event.getY(1)) / 2f
                    // 官方：拖拽中落下第二指左键保持按住（TrackpadInputStrategy
                    // .onMotionEvent 只在最终 UP 释放）—— 与真触控板“按住
                    // 拖动中双指滚动”一致
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // v7.0：官方 trackMoveEvent —— 任一指滑出 slop 即取消
                // 轻点/长按候选（取代旧 0.5px 抖动阈值；拖拽中已 moved 不受影响）
                if (!moved && anyPointerBeyondSlop(event)) {
                    moved = true
                    disarmLongPress()
                }
                if (event.pointerCount >= 2 && mode == Mode.TWO) {
                    // ---- 双指：滑动 = 滚轮（质心增量，首两指） ----
                    val avgY = (event.getY(0) + event.getY(1)) / 2f
                    val dy = (lastTwoY - avgY) * scaleY // 双指上滑 → 滚轮下滚
                    lastTwoY = avgY
                    scrollAccum += dy
                    if (kotlin.math.abs(scrollAccum) >= wheelStep) {
                        val steps = (scrollAccum / wheelStep).toInt()
                        // v2.33.1：滚轮入队输入线程（主线程零 socket I/O）
                        val delta = steps * wheelStep
                        postSend {
                            if (LorieView.connected())
                                view.sendMouseWheelEvent(0f, delta)
                        }
                        scrollAccum -= steps * wheelStep
                    }
                } else if (event.pointerCount == 1) {
                    // ---- 单指：光标相对移动（v2.30 wine warp 兼容） ----
                    // v2.30 修复（wine 游戏视角乱飘/卡死）：发真实相对运动
                    // （sendMouseEvent relative=true → libXlorie XI2 raw
                    // relative motion，上游 TrackpadInputStrategy 同款）。
                    // 旧实现维护虚拟光标 padX/padY 发绝对坐标：wine FPS 按住
                    // 转视角时游戏每帧 XWarpPointer 回窗口中心，绝对事件把
                    // 指针强拽到 padX/padY → 游戏读到巨大跳变（视角猛转），
                    // 光标 clamp 在屏幕边缘后不再变化（视角卡死"固定视角"）。
                    // 相对增量不受 warp 影响：桌面光标照常移动，游戏视角
                    // 真实旋转。轻点/右键落在当前 X 指针位置（相对模式无
                    // 虚拟光标）。
                    // scaleTouchpad：位移按视图→X 拉伸比例放大（对齐上游）。
                    // v2.30.1：改为累加进合并器，16ms 节流冲刷 —— 消除
                    // wine 游戏滑动掉帧；总量精确守恒，手感不变。
                    // v7.0：长按/双击拖拽期间同样走此分支 —— 按住的左键 +
                    // 相对移动 = 拖拽（官方 mIsDragging 时 onScroll 同款）。
                    val dx = event.x - curX
                    val dy = event.y - curY
                    curX = event.x; curY = event.y
                    if (moved) {
                        val mulX = if (scaleTouchpad) scaleX else 1f
                        val mulY = if (scaleTouchpad) scaleY else 1f
                        accumulateMove(dx * mulX, dy * mulY)
                    }
                } else {
                    // 多指但未成滚轮（TWO_DONE 后余指滑动）：官方 pointerCount != 1
                    // 时不移动光标 —— 保持位置同步
                    curX = event.x; curY = event.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // v7.0 官方 trackUpEvent + cancelLongTouchNotification：仅移除
                // 抬起指的记账并解除长按定时（任一指抬起即不再触发多指长按；
                // 轻点判定统一在末指 UP）
                pointerStarts.remove(event.getPointerId(event.actionIndex))
                disarmLongPress()
                // 事件里 pointerCount 含抬起指：==2 即抬起后仅剩 1 指 →
                // 停止滚轮；官方余指单指滑动继续移动光标（回落单指分支）
                if (event.pointerCount <= 2 && mode == Mode.TWO) mode = Mode.ONE
            }

            MotionEvent.ACTION_UP -> {
                // v2.30.1：手势结束先冲刷残余增量（不丢拖拽尾段）
                // v2.33.1：冲刷异步化（FIFO 先于 tap 抬键写入 X）
                diagUps++
                disarmLongPress()
                pointerStarts.clear()
                if (moved) postFlushMoves()
                // v6.10：轻点/长按仲裁用 MotionEvent 事件时间轴 —— 主线程被
                // wine 打满时调度抖动会令长按定时器先于排队的 UP 触发
                //（longPressFired 误置、左键已按下），短按被误判为长按。
                val tapDurMs = if (downEventTime > 0) event.eventTime - downEventTime else 0L
                val tapLike = !moved && tapDurMs < longPressTimeoutMs
                downEventTime = 0L
                // 官方顺序：先释放按住的键（strategy.onMotionEvent 在 tap
                // 检测器之前），再判定轻点 —— 长按路径的“按下+释放”
                // 本身就是一次完整单击，不重复注入 tap。
                if (heldButton != 0) {
                    sendButton(heldButton, false)
                    heldButton = 0
                }
                if (tapLike && !longPressFired) {
                    // v7.0 官方 onTap：按键由最大手指数决定（1=左 2=右 3=中）
                    val button = buttonForPointerCount(maxPointers)
                    if (button != InputStub.BUTTON_UNDEFINED) {
                        diagTaps++
                        if (button == InputStub.BUTTON_LEFT && tapToMove) {
                            // v7.0 官方 tapToMove：左键轻点不立即点击，挂起
                            // 延迟单击（doubleTapTimeout 内无第二击才补发）。
                            // 旧实现“立即按下并保持”→ 点一下变成长按。
                            armTapPending()
                        } else {
                            // 左键（tapToMove 关）/双指右键/三指中键：立即单击
                            //（官方 onTap → sendMouseClick(button, true)）
                            sendButton(button, true)
                            sendButton(button, false)
                        }
                    }
                }
                mode = Mode.IDLE
            }

            MotionEvent.ACTION_CANCEL -> {
                // v2.30.1：系统取消手势 → 丢弃未冲刷增量（不发出意外移动）
                dropPendingMoves()
                disarmLongPress()
                cancelTapPending()
                downEventTime = 0L
                pointerStarts.clear()
                if (heldButton != 0) {
                    sendButton(heldButton, false)
                    heldButton = 0
                }
                mode = Mode.IDLE
            }
        }
        return true
    }

    fun sendKey(event: KeyEvent): Boolean = keySender.sendKeyEvent(event)

    /** v2.25：同步 termux-x11 "首选扫描码"偏好到按键发送器（即时生效）。 */
    fun setPreferScancodes(v: Boolean) {
        keySender.preferScancodes = v
    }
}

/**
 * v2.32：X11 触摸分流容器 —— View 层逐指分流（虚拟手柄 ↔ 屏幕滑动/视角
 * 旋转共存的机制性根治）。
 *
 * 机制背景：v2.32 以前手柄 UI 在 Compose 顶层覆盖（LinBoxShell），X11 下
 * 依赖"手柄元素不消费 → 指针漏进 LorieView interop → 桥内剥离"实现共存。
 * 该路径依赖 Compose 命中路径恰好锁定在 LorieView 分支：屏幕先按下时正常
 * （桥剥离手柄指针）；**手柄先按下时命中路径锁定手柄分支，之后任何屏幕
 * 指针（ACTION_POINTER_DOWN）都到不了 LorieView** → 按住手柄/摇杆时无法
 * 滑动屏幕转视角（用户实测：先滑屏再点手柄可以、先点手柄再滑屏不行、
 * 松开同时按不行 —— 与"第一指 DOWN 决定手势流归属"的行为逐字吻合）。
 *
 * 本容器把手柄 UI（ComposeView 宿主）与 LorieView 放进同一 FrameLayout，
 * 在 dispatchTouchEvent 入口按每个指针的落点分类记账：
 * - 落点在手柄 UI 内（[GamepadController.isOverPadUi]：元素 ∪ 迷你工具条）
 *   = 手柄指针 → 手柄流（gamepadHost 自收，摇杆/按键/工具条自理）
 * - 否则 = 屏幕指针 → 屏幕流（[LorieView.dispatchTouchEvent] 直达桥）
 * 两条流同时活跃时逐流拆分派发（splitFor：MotionEvent.split(int) 在公开
 * SDK 不可见，按其内部语义手写等价实现 —— 自动重映射 action：新指落在
 * 另一流时本流子事件自动降级 MOVE），与按下顺序完全无关。抬起（POINTER_UP/UP/CANCEL）按抬起指针的归属精确派发到
 * 所在流的宿主，双宿主各自收尾自己的手势。桥侧 filterPadPointers 保留为
 * 兜底：手柄关闭/命中区未布局的直通路径、以及宿主不可用的退路中，手柄
 * 指针漏进 LorieView 时仍被剥离（v2.31 语义不变）。
 *
 * 性能：手柄未显示（padSplitActive=false 或命中区未布局）→ super 直通零
 * 开销；纯屏幕流直达 LorieView（不经手柄 Compose 树的命中/派发链，主
 * Compose 树也不再包含手柄节点）；纯手柄流直达手柄宿主、不经桥的手势
 * 状态机；仅混合流（按住手柄同时滑动屏幕）产生两次 split 拷贝（对齐
 * Android 系统内部 split-motion 机制的开销量级）。
 */
internal class X11TouchSplitLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 分流激活（手柄开关；X11Screen 的 AndroidView.update 块驱动） */
    var padSplitActive: Boolean = false

    /** v6.4：LorieView 子视图引用（动态布局/分流坐标换算用；factory 接线） */
    var lorieChild: View? = null

    /**
     * v6.4 小窗模式：LorieView 内容帧（px，相对本容器；null = 非小窗布局）。
     * 非空时 LorieView 被测量并布局到帧内（exact 分辨率下按宽高比适配
     * 尺寸居中于帧）；置空后回到铺满（拉伸）或居中（非拉伸）布局。
     */
    var lorieFrame: Rect? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** 屏幕指针路由：→ LorieView.dispatchTouchEvent（→ SmartTouchBridge.onTouch） */
    var screenRouter: ((MotionEvent) -> Boolean)? = null

    /** 手柄侧命中判定（窗口坐标 → GamepadController.isOverPadUi） */
    var padHitTest: ((Float, Float) -> Boolean)? = null

    /** 手柄宿主（ComposeView；手柄流的接收者，factory 接线） */
    var padHost: View? = null

    /** 本容器相对窗口原点偏移（X11 全屏布局恒 0；位置变化时重算缓存） */
    private var winOffX = 0f
    private var winOffY = 0f

    /** 手柄流指针 id 记账（仅 UI 线程；indexOf+removeAt 规避 remove 重载歧义） */
    private val padIds = ArrayList<Int>(4)

    /** 屏幕流指针 id 记账（仅 UI 线程） */
    private val screenIds = ArrayList<Int>(4)

    init {
        // 容器在窗口内的位置变化（旋转/布局）时重算偏移缓存
        addOnLayoutChangeListener { _, l, t, _, oldL, oldT, _, _, _ ->
            if (l != oldL || t != oldT) {
                val loc = IntArray(2)
                getLocationInWindow(loc)
                winOffX = loc[0].toFloat()
                winOffY = loc[1].toFloat()
            }
        }
    }

    /**
     * v6.4（用户：“x11界面取消拉伸全屏时横屏画面却跑到左方了，应该居中
     * 才对，竖屏在上方不用动”）：显示层拉伸状态 —— 与 LorieView.onMeasure
     * 同源判定（stretch 开或 native/scaled = surface 跟视图 = 铺满）。
     */
    private fun isStretchActive(): Boolean {
        val prefs = LoriePreferences.prefs ?: return true
        return prefs.displayStretch.get() ||
            "native" == prefs.displayResolutionMode.get() ||
            "scaled" == prefs.displayResolutionMode.get()
    }

    /**
     * v6.4：LorieView 动态布局 —— super（FrameLayout FILL 默认分支锚定左上）
     * 之后按模式重摆：
     * - 小窗帧（lorieFrame 非空）：测量到帧尺寸，布局进帧（exact 模式下
     *   LorieView 自测的宽高比适配尺寸居中于帧）；
     * - 拉伸铺满：占满整个容器（v2.22.5 fix12 语义保留）；
     * - 非拉伸（exact/custom 固定分辨率）：横屏两轴居中 / 竖屏水平居中+
     *   顶部对齐（用户指定：竖屏在上方不用动）。
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val lv = lorieChild ?: return
        val pw = width - paddingLeft - paddingRight
        val ph = height - paddingTop - paddingBottom
        if (pw <= 0 || ph <= 0) return

        val f = lorieFrame
        if (f != null) {
            // ---- 小窗：内容帧 ----
            val fx = f.left.coerceIn(paddingLeft, right - left)
            val fy = f.top.coerceIn(paddingTop, bottom - top)
            val fw = f.width().coerceAtMost(pw)
            val fh = f.height().coerceAtMost(ph)
            lv.measure(
                View.MeasureSpec.makeMeasureSpec(fw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(fh, View.MeasureSpec.EXACTLY)
            )
            val w = lv.measuredWidth.coerceAtMost(fw)
            val h = lv.measuredHeight.coerceAtMost(fh)
            val x = fx + (fw - w) / 2
            val y = fy + (fh - h) / 2
            lv.layout(x, y, x + w, y + h)
            return
        }

        if (isStretchActive()) {
            // ---- 拉伸铺满（fix12：画面铺满容器无黑边） ----
            if (lv.measuredWidth != pw || lv.measuredHeight != ph) {
                lv.measure(
                    View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY)
                )
            }
            lv.layout(paddingLeft, paddingTop, paddingLeft + pw, paddingTop + ph)
        } else {
            // ---- 非拉伸：宽高比适配尺寸重摆（v6.4 居中修正） ----
            if (lv.measuredWidth > pw || lv.measuredHeight > ph) {
                // 旋转/分辨率切换后的陈旧测量 —— 按新容器尺寸重测
                lv.measure(
                    View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY)
                )
            }
            val w = lv.measuredWidth.coerceAtMost(pw)
            val h = lv.measuredHeight.coerceAtMost(ph)
            val x = paddingLeft + (pw - w) / 2
            val y = if (pw > ph) paddingTop + (ph - h) / 2 else paddingTop
            lv.layout(x, y, x + w, y + h)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val router = screenRouter
        val hitTest = padHitTest
        val host = padHost
        val hostLive = host != null && host.visibility == View.VISIBLE
        if (!padSplitActive || router == null || hitTest == null ||
            (!GamepadController.hasElementHits() && !GamepadController.hasToolbarHit())
        ) {
            // 手柄未显示/未布局完成：零开销直通（行为与旧版完全一致）
            return super.dispatchTouchEvent(event)
        }

        // ---- 逐指记账：DOWN 落点定流，此后该指针保持其流直到抬起 ----
        //（中途滑过手柄区/屏幕区不改流 —— 手势语义稳定，对齐 v2.31 跟踪规则）
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                padIds.clear()
                screenIds.clear()
                classify(event, 0, hitTest)
            }
            MotionEvent.ACTION_POINTER_DOWN -> classify(event, event.actionIndex, hitTest)
            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起指所在流的宿主必须收到本 POINTER_UP（结束该指手势）。
                // 关键顺序：先按"记账仍含抬起指"的集合拆分派发（split 保留
                // POINTER_UP 语义），再移除记账 —— 若先移除再派发，屏幕流
                // 子事件会被 split 降级 MOVE，双指滚轮状态机（Mode.TWO 等
                // POINTER_UP 收尾）会卡住；手柄侧则收不到按钮抬起（卡键）。
                val liftedId = event.getPointerId(event.actionIndex)
                val fromPad = padIds.contains(liftedId)
                val fromScreen = screenIds.contains(liftedId)
                // v2.33：漏记账不吞 —— 分流中途激活（手势已开始才开手柄）
                // 等时序下抬起指可能不在任何流里，交回正常分发兜底，
                // 严防任何事件被容器静默丢弃（吞 UP = 卡键/点击失灵）。
                if (!fromPad && !fromScreen) {
                    removePointer(liftedId)
                    return super.dispatchTouchEvent(event)
                }
                if (fromScreen) {
                    splitFor(event, screenIds)?.let { sub ->
                        router.invoke(sub)
                        sub.recycle()
                    }
                }
                if (fromPad && hostLive) {
                    splitFor(event, padIds)?.let { sub ->
                        host!!.dispatchTouchEvent(sub)
                        sub.recycle()
                    }
                }
                removePointer(liftedId)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // UP/CANCEL 双宿主收尾：手柄侧按 pointer id 结束手势（摇杆
                // finally 释放方向/按钮补发释放），屏幕侧由 filterPadPointers
                // 剥成纯屏幕流收尾。清空记账前先取归属，按实际参与过的流
                // 精确双派（完整原事件，各宿主自己匹配/剥离指针）。
                val hadPad = padIds.isNotEmpty()
                val hadScreen = screenIds.isNotEmpty()
                padIds.clear()
                screenIds.clear()
                // v2.33：两侧均无记账（中途激活/漏记账）→ 正常分发兜底不吞
                if (!hadPad && !hadScreen) return super.dispatchTouchEvent(event)
                if (hadPad && hostLive) host!!.dispatchTouchEvent(event)
                if (hadScreen) router.invoke(event)
                return true
            }
        }
        // 安全阀：异常流（极端时序漏收 UP）防记账错乱
        if (padIds.size > 8 || screenIds.size > 8) {
            padIds.clear()
            screenIds.clear()
        }

        return when {
            padIds.isEmpty() && screenIds.isEmpty() ->
                // v2.33：记账空（异常时序被安全阀清空等）→ 交回正常分发，
                // 不得静默丢弃任何事件（吞事件 = 点击/抬起失灵）
                super.dispatchTouchEvent(event)
            padIds.isEmpty() ->
                // 纯屏幕流：直达 LorieView（绕过手柄树，零 Compose 开销）
                router.invoke(event)
            screenIds.isEmpty() ->
                // 纯手柄流：直达手柄宿主（摇杆/按键/工具条自理；不经桥，
                // 零剥离开销。宿主不可用时退回 FrameLayout 正常分发）
                if (hostLive) host!!.dispatchTouchEvent(event)
                else super.dispatchTouchEvent(event)
            else -> {
                // 混合流（按住手柄同时滑动屏幕）：逐流拆分派发。
                // splitFor（手写 split 等价）处理 action 重映射：如"手柄先
                // 按、屏幕后按"时手柄流的子事件由 POINTER_DOWN 降级 MOVE、
                // 屏幕流保持 POINTER_DOWN —— 两流各自为完整手势。
                var handled = false
                splitFor(event, padIds)?.let { sub ->
                    if (hostLive) handled = host!!.dispatchTouchEvent(sub)
                    sub.recycle()
                }
                splitFor(event, screenIds)?.let { sub ->
                    handled = router.invoke(sub) || handled
                    sub.recycle()
                }
                handled
            }
        }
    }

    /** 新指针分类入流：落点在手柄 UI 内（含容器→窗口偏移）= 手柄流；
     *  v6.4：LorieView 未铺满容器（小窗帧 / 非拉伸宽高比适配）时，
     *  画面外落点不入任何流 —— 点"桌面"/黑边不注入 X。 */
    private fun classify(event: MotionEvent, idx: Int, hitTest: (Float, Float) -> Boolean) {
        val id = event.getPointerId(idx)
        if (hitTest(event.getX(idx) + winOffX, event.getY(idx) + winOffY)) {
            padIds.add(id)
            return
        }
        val lv = lorieChild
        if (lv != null &&
            (lv.left > 0 || lv.top > 0 || lv.right < width || lv.bottom < height)
        ) {
            // lv 未铺满：以 lv 实际边界判定（瞬态布局期 lv 尺寸过期时
            // 边界宽松 —— 宁可注入不吞，避免误杀有效触点）
            val x = event.getX(idx)
            val y = event.getY(idx)
            if (x < lv.left || x > lv.right || y < lv.top || y > lv.bottom) {
                return // 画面外 —— 不入流（交正常分发兜底）
            }
        }
        screenIds.add(id)
    }

    private fun removePointer(id: Int) {
        val pi = padIds.indexOf(id)
        if (pi >= 0) {
            padIds.removeAt(pi)
            return
        }
        val si = screenIds.indexOf(id)
        if (si >= 0) screenIds.removeAt(si)
    }

    // ---- 子事件拆分（MotionEvent.split 语义的手写等价实现）----
    // MotionEvent.split(int) 在公开 SDK 不可见（CI compileReleaseKotlin
    // "Unresolved reference" 实证），按 Android 内部 split 逻辑等价复刻：
    // 提取保留指针子集（保持事件内相对顺序）+ POINTER_DOWN/UP 的 action
    // 重映射 —— 关联指针在保留集则保持动作并重映射 actionIndex，否则
    // 降级 MOVE；DOWN/UP/CANCEL 原样保留（调用点仅 MOVE/POINTER_DOWN/
    // POINTER_UP 到此）。属性/坐标数组全复用，零每事件分配。

    /** 子事件属性数组（dispatchTouchEvent 仅 UI 线程访问，无需同步） */
    private val splitProps = Array(10) { MotionEvent.PointerProperties() }

    /** 子事件坐标数组 */
    private val splitCoords = Array(10) { MotionEvent.PointerCoords() }

    /** 保留指针的原始索引暂存（与 splitProps/splitCoords 一一对应） */
    private val splitIdx = IntArray(10)

    /** 构造只保留 ids 中指针的子事件；ids 与事件无交集时返回 null */
    private fun splitFor(event: MotionEvent, ids: List<Int>): MotionEvent? {
        var count = 0
        var actionPos = -1
        val aIdx = event.actionIndex
        for (i in 0 until event.pointerCount) {
            if (!ids.contains(event.getPointerId(i))) continue
            if (count >= splitIdx.size) break
            if (i == aIdx) actionPos = count
            splitIdx[count++] = i
        }
        if (count == 0) return null

        var action = event.actionMasked
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action = if (actionPos >= 0)
                action or (actionPos shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else MotionEvent.ACTION_MOVE
        }
        // v2.32.2 流视角 action 归一化：分流后每条流都是宿主眼中的"完整
        // 手势"——流内第一指按下即 DOWN、最后一指抬起即 UP。手柄按住时
        // 屏幕流首个事件原生是 POINTER_DOWN：状态机（Mode.ONE 只在 DOWN
        // 建立）全程忽略 → tap/按键失灵；末指抬起原生是 POINTER_UP：UP
        // 检测不触发 → 左键卡按。子集只剩一根指针时按单指手势归一，
        // 多指流（双指滚轮/按钮组合）保持原生 POINTER_ 语义。
        if (count == 1) {
            val masked = action and MotionEvent.ACTION_MASK
            if (masked == MotionEvent.ACTION_POINTER_DOWN) action = MotionEvent.ACTION_DOWN
            else if (masked == MotionEvent.ACTION_POINTER_UP) action = MotionEvent.ACTION_UP
        }
        for (j in 0 until count) {
            event.getPointerProperties(splitIdx[j], splitProps[j])
            event.getPointerCoords(splitIdx[j], splitCoords[j])
        }
        return MotionEvent.obtain(
            event.downTime, event.eventTime, action, count,
            splitProps, splitCoords, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
            event.source, event.flags
        )
    }
}

// =============================================================================
// v6.4：X11 小窗模式（自由窗口）—— 状态 / 窗口 chrome / 八向缩放
//（用户："x11界面加入小窗设置，实现x11可以自由拖动的类似windows窗口那种，
//  可以参考 AnWind 的实现"）
// =============================================================================

/** 小窗 chrome 常量。 */
private object X11FloatChrome {
    /** 标题栏高度。 */
    val TITLE_H = 34.dp
    /** v6.5：快捷配置条高度（AnWind ControlBar 对齐物）。 */
    val QUICKBAR_H = 34.dp
    /** 窗口圆角。 */
    val RADIUS = 10.dp
    /** 最小窗口尺寸（含标题栏）。 */
    val MIN_W = 240.dp
    val MIN_H = 190.dp
}

/**
 * 小窗模式全局状态（v6.4）。
 *
 * 帧坐标语义：**窗口外框**（px，含标题栏），原点 = X11 画面区左上。
 * LorieView 的内容帧由 X11Screen 换算（外框去标题栏/边框）后写入
 * [X11TouchSplitLayout.lorieFrame]。
 *
 * 持久化：`linbox_x11_float` SharedPreferences（开关 + 帧几何 + 最大化）。
 * Prefs.java 是脚本生成物不能手改，故独立存本应用私有 prefs。
 */
object X11FloatWindow {
    /** 小窗模式开关（false = 画面铺满整屏的既有行为）。 */
    var enabled by mutableStateOf(false)

    /** 窗口外框（px，含标题栏）：左上角 + 尺寸。 */
    var x by mutableIntStateOf(0)
    var y by mutableIntStateOf(0)
    var w by mutableIntStateOf(0)
    var h by mutableIntStateOf(0)

    /** 最大化（占满画面区，标题栏保留）。 */
    var maximized by mutableStateOf(false)

    /** 最大化前的外框（还原用）。 */
    private var restoreX = 0
    private var restoreY = 0
    private var restoreW = 0
    private var restoreH = 0

    private const val PREFS_NAME = "linbox_x11_float"
    private var initialized = false

    fun initIfNeed(context: Context) {
        if (initialized) return
        initialized = true
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled = sp.getBoolean("enabled", false)
        x = sp.getInt("x", 0)
        y = sp.getInt("y", 0)
        w = sp.getInt("w", 0)
        h = sp.getInt("h", 0)
        maximized = sp.getBoolean("maximized", false)
        quickBarShown = sp.getBoolean("quickBar", true)
    }

    private fun persist(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean("enabled", enabled)
            .putInt("x", x).putInt("y", y).putInt("w", w).putInt("h", h)
            .putBoolean("maximized", maximized)
            .putBoolean("quickBar", quickBarShown)
            .apply()
    }

    fun setEnabled(context: Context, v: Boolean) {
        enabled = v
        persist(context)
    }

    fun setMaximized(context: Context, v: Boolean) {
        if (v && !maximized) {
            restoreX = x; restoreY = y; restoreW = w; restoreH = h
        }
        maximized = v
        if (!v && restoreW > 0) {
            x = restoreX; y = restoreY; w = restoreW; h = restoreH
        }
        persist(context)
    }

    /** 提交窗口外框（px）。 */
    fun setFrame(context: Context, nx: Int, ny: Int, nw: Int, nh: Int) {
        x = nx; y = ny; w = nw; h = nh
        persist(context)
    }

    /** 首次开启 / 画面区尺寸就绪 / 旋转后：外框钳制进画面区；无几何时给居中 72% 默认窗。 */
    fun ensureFrame(areaW: Int, areaH: Int, minWpx: Int, minHpx: Int) {
        if (areaW <= 0 || areaH <= 0) return
        if (w <= 0 || h <= 0) {
            w = (areaW * 0.72f).toInt().coerceIn(minWpx, areaW)
            h = (areaH * 0.72f).toInt().coerceIn(minHpx, areaH)
            x = (areaW - w) / 2
            y = (areaH - h) / 2
            return
        }
        w = w.coerceIn(minWpx, areaW)
        h = h.coerceIn(minHpx, areaH)
        x = x.coerceIn(0, (areaW - w).coerceAtLeast(0))
        y = y.coerceIn(0, (areaH - h).coerceAtLeast(0))
    }

    /**
     * v6.5：窗口按会话分辨率自适应（AnWind 语义，用户："根据分辨率调整
     * 窗口大小，就像anwind那种"）。
     *
     * 会话分辨率握手（glibc-runner -d / 手动固定 exact）到达时调用：外框
     * 按该分辨率的宽高比缩放到工作区 88% 预算内并居中 —— 窗口大小随
     * 分辨率调整，画面不变形。用户手动缩放后保持（宽高比未变的手重排
     * 跳过），直到下一次分辨率比例变化才重新适配。
     */
    fun fitToResolution(
        context: Context, resW: Int, resH: Int,
        areaW: Int, areaH: Int, minWpx: Int, minHpx: Int
    ) {
        if (resW <= 0 || resH <= 0 || areaW <= 0 || areaH <= 0) return
        if (maximized) return
        // 已有几何且宽高比基本一致（<2% 偏差）= 用户已按此比例布局，保持
        if (w > 0 && h > 0) {
            val cur = w.toFloat() / h
            val want = resW.toFloat() / resH
            if (kotlin.math.abs(cur - want) / want < 0.02f) return
        }
        // 88% 工作区预算内按分辨率宽高比适配
        val budgetW = (areaW * 0.88f).toInt()
        val budgetH = (areaH * 0.88f).toInt()
        var nw = budgetW
        var nh = (nw.toFloat() * resH / resW).toInt()
        if (nh > budgetH) {
            nh = budgetH
            nw = (nh.toFloat() * resW / resH).toInt()
        }
        w = nw.coerceIn(minWpx, areaW)
        h = nh.coerceIn(minHpx, areaH)
        x = (areaW - w) / 2
        y = (areaH - h) / 2
        persist(context)
    }

    /** v6.5：快捷配置条显示状态（快捷配置随帧一起持久化）。 */
    var quickBarShown by mutableStateOf(true)

    /** 快捷配置条显隐切换。 */
    fun toggleQuickBar(context: Context) {
        quickBarShown = !quickBarShown
        persist(context)
    }

    /** 当前生效外框（最大化 = 满幅）。 */
    fun effectiveFrame(areaW: Int, areaH: Int): Rect =
        if (maximized) Rect(0, 0, areaW, areaH)
        else Rect(x, y, x + w, y + h)
}

/**
 * v6.9（用户："x11小窗口模式时关闭按钮特别难点到"）：标题栏按钮专用点击
 * 修饰符 —— 双重修复：
 * 1) 触摸目标放大：最小高 30dp + 最小宽（✕ 等小按钮原本仅约 20×22dp，
 *    指尖命中率极低）；
 * 2) 手势抢占根治：父容器标题栏挂有 detectDragGestures（拖动窗口）与
 *    detectTapGestures（双击最大化），普通 clickable 在手指微移超 touch
 *    slop 时即被父级拖拽检测夺走 → 点击取消。本修饰符在按钮自身持续
 *    消费按住期间的事件（子级先于父级收事件）→ 父级拖拽/双击检测因事件
 *    已消费即刻取消 —— 落点在按钮内必定完成点击；滑动超 24dp 视为滑出
 *    按钮取消点击（父级拖拽本手势已死，抬指即结束）。
 */
private fun Modifier.chromeButton(minWidth: Dp, onClick: () -> Unit): Modifier =
    this
        .heightIn(min = 30.dp)
        .widthIn(min = minWidth)
        .pointerInput(onClick) {
            val slop = 24.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val sx = down.position.x
                val sy = down.position.y
                val pid = down.id
                var alive = true
                while (alive) {
                    val event = awaitPointerEvent()
                    // Compose 的 PointerEventType 并无 Cancel 常量（仅 Press/
                    // Release/Move/Drag/Scroll/Enter/Exit）。系统取消
                    // (ACTION_CANCEL)与父级手势抢占在 Compose 中都以"变更已被
                    // 消费"的形式送达 —— 与官方 waitForUpOrCancellation 同款
                    // 判据：任一变更已被消费即放弃本次手势等待，不触发点击。
                    if (event.changes.any { it.isConsumed }) break
                    val c = event.changes.firstOrNull { it.id == pid } ?: continue
                    val dx = c.position.x - sx
                    val dy = c.position.y - sy
                    val beyond = dx * dx + dy * dy > slop * slop
                    when {
                        !c.pressed -> {
                            // 抬起：未滑出才点击（消费本事件，防父级双击误判）
                            if (!beyond) {
                                c.consume()
                                onClick()
                            }
                            alive = false
                        }
                        beyond -> {
                            // 滑出按钮：取消点击，残余事件放行
                            alive = false
                        }
                        else -> {
                            // 按住期间持续消费 —— 父级拖拽检测要求事件未消费
                            c.consume()
                        }
                    }
                }
            }
        }

/** 八向缩放边。 */
private enum class ResizeEdge(val l: Boolean, val r: Boolean, val t: Boolean, val b: Boolean) {
    L(l = true,  r = false, t = false, b = false),
    R(l = false, r = true,  t = false, b = false),
    T(l = false, r = false, t = true,  b = false),
    B(l = false, r = false, t = false, b = true),
    LT(l = true,  r = false, t = true,  b = false),
    RT(l = false, r = true,  t = true,  b = false),
    LB(l = true,  r = false, t = false, b = true),
    RB(l = false, r = true,  t = false, b = true)
}

/**
 * 小窗模式窗口 chrome：标题栏（拖动移动 / 双击最大化 / 最大化还原 /
 * 全屏恢复 / ✕ 关闭）+ v6.5 快捷配置条（AnWind ControlBar 对齐：
 * 跟随窗口 / 固定分辨率 + 分辨率输入应用 + X 指示 + 切窗 / 键盘 /
 * 游戏全屏）+ 边缘八向缩放手柄。内容区为透明洞 —— 画面本体是 View 层
 * LorieView 动态布局帧（X11TouchSplitLayout.lorieFrame），chrome 只画
 * 边框与标题栏；未连接时窗内显示连接提示。
 *
 * 交互对齐 AnWind WindowChrome：移动实时跟手（仅位置变化，无 surface 重建）；
 * 缩放手势期间 chrome 实时预览，松手一次性提交（避免 surface 逐帧重建）。
 */
@Composable
private fun X11FloatWindowChrome(
    areaW: Int,
    areaH: Int,
    connected: Boolean,
    onExit: () -> Unit,
    onFullscreen: () -> Unit
) {
    if (areaW <= 0 || areaH <= 0) return
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val minWpx = with(density) { X11FloatChrome.MIN_W.roundToPx() }
    val minHpx = with(density) { X11FloatChrome.MIN_H.roundToPx() }

    // 缩放手势的实时帧（px；null = 非缩放中，渲染已提交帧）
    var liveResize by remember { mutableStateOf<Rect?>(null) }

    fun committedFrame(): Rect = X11FloatWindow.effectiveFrame(areaW, areaH)
    fun currentFrame(): Rect = liveResize ?: committedFrame()

    fun applyResize(edge: ResizeEdge, dx: Float, dy: Float) {
        val base = liveResize ?: committedFrame()
        var l = base.left
        var t = base.top
        var r = base.right
        var b = base.bottom
        if (edge.l) l = (l + dx.roundToInt()).coerceIn(0, (r - minWpx).coerceAtLeast(0))
        if (edge.r) r = (r + dx.roundToInt()).coerceIn((l + minWpx).coerceAtMost(areaW), areaW)
        if (edge.t) t = (t + dy.roundToInt()).coerceIn(0, (b - minHpx).coerceAtLeast(0))
        if (edge.b) b = (b + dy.roundToInt()).coerceIn((t + minHpx).coerceAtMost(areaH), areaH)
        liveResize = Rect(l, t, r, b)
    }

    fun commitResize() {
        val f = liveResize ?: return
        X11FloatWindow.setFrame(context, f.left, f.top, f.width(), f.height())
        liveResize = null
    }

    Box(
        modifier = Modifier
            .offset { val f = currentFrame(); IntOffset(f.left, f.top) }
            .layout { m, ctr ->
                val f = currentFrame()
                val w = f.width().coerceIn(1, ctr.maxWidth)
                val h = f.height().coerceIn(1, ctr.maxHeight)
                val p = m.measure(Constraints.fixed(w, h))
                layout(w, h) { p.place(0, 0) }
            }
            .border(1.5.dp, theme.windowBorderColor, RoundedCornerShape(X11FloatChrome.RADIUS))
            .clip(RoundedCornerShape(X11FloatChrome.RADIUS))
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- 标题栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(X11FloatChrome.TITLE_H)
                    .background(theme.windowTitleBarColor)
                    .pointerInput(Unit) {
                        detectDragGestures { change, amt ->
                            change.consume()
                            if (!X11FloatWindow.maximized && liveResize == null) {
                                val nx = (X11FloatWindow.x + amt.x.roundToInt())
                                    .coerceIn(0, (areaW - X11FloatWindow.w).coerceAtLeast(0))
                                val ny = (X11FloatWindow.y + amt.y.roundToInt())
                                    .coerceIn(0, (areaH - X11FloatWindow.h).coerceAtLeast(0))
                                X11FloatWindow.setFrame(context, nx, ny, X11FloatWindow.w, X11FloatWindow.h)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                X11FloatWindow.setMaximized(context, !X11FloatWindow.maximized)
                            }
                        )
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "X11 桌面",
                    color = theme.windowTitleBarTextColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                // v6.5：快捷配置条收起 / 展开（AnWind 标题栏工具条开关同款）
                Text(
                    if (X11FloatWindow.quickBarShown) "▴" else "▾",
                    color = theme.windowTitleBarTextColor,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .chromeButton(minWidth = 34.dp) { X11FloatWindow.toggleQuickBar(context) }
                        .padding(horizontal = 7.dp, vertical = 5.dp)
                )
                // 全屏（v6.5：点击全屏按钮恢复全屏，返回键恢复小窗）
                Text(
                    "全屏",
                    color = theme.windowTitleBarTextColor,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .chromeButton(minWidth = 44.dp) { onFullscreen() }
                        .padding(horizontal = 7.dp, vertical = 5.dp)
                )
                // 最大化 / 还原
                Text(
                    if (X11FloatWindow.maximized) "还原" else "最大化",
                    color = theme.windowTitleBarTextColor,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .chromeButton(minWidth = 52.dp) { X11FloatWindow.setMaximized(context, !X11FloatWindow.maximized) }
                        .padding(horizontal = 7.dp, vertical = 5.dp)
                )
                // 关闭（结束会话）
                Text(
                    "✕",
                    color = theme.windowTitleBarTextColor,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .chromeButton(minWidth = 40.dp) { onExit() }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
            // ---- v6.5 快捷配置条（AnWind ControlBar 对齐；可收起） ----
            if (X11FloatWindow.quickBarShown) {
                X11QuickConfigBar()
            }
            // ---- 内容区：透明洞（View 层 LorieView 在此透出） ----
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!connected) {
                    // 未连接：窗内等待提示（窗口出现即可拖到顺手位置等画面亮起）
                    Text(
                        "X 服务连接中…",
                        color = theme.windowTitleBarTextColor,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0x66000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // ---- 八向缩放手柄（最大化时隐藏） ----
        if (!X11FloatWindow.maximized) {
            ResizeEdge.values().forEach { edge ->
                X11ResizeHandle(
                    edge = edge,
                    onDragStart = { liveResize = committedFrame() },
                    onDrag = { dx, dy -> applyResize(edge, dx, dy) },
                    onDragEnd = { commitResize() },
                    onDragCancel = { liveResize = null }
                )
            }
        }
    }
}

/**
 * v6.5：小窗快捷配置条 —— AnWind X11 ControlBar 对齐（用户："还有相关
 * 快捷配置，anwind都有参考"）：
 * - 分辨率模式：跟随窗口（native）/ 固定分辨率（exact）即时切换；
 * - exact 模式：WxH 输入 + 应用（160x120 下限，与 AnWind 同款校验）；
 * - X 指示：当前 X 屏幕分辨率来源（跟随/固定/缩放；游戏握手 = 品牌色）；
 * - 切窗：无 WM 的 X 屏上多 wine 窗口轮转置前（XRaiseWindow）；
 * - 键盘：壳层虚拟键盘开关；
 * - 游戏全屏：Alt+Enter —— 窗口化游戏一键切换 wine/DXVK 全屏。
 * 分辨率切换经 X11ResolutionLink（内部 pokeActiveView → regenerate）。
 */
@Composable
private fun X11QuickConfigBar() {
    val theme = LocalWinTheme.current
    val prefs = remember { LoriePreferences.prefs }
    val resState by X11ResolutionLink.state.collectAsState()

    val modeNative = resState.mode != "exact" && resState.mode != "scaled"
    // 本地选择态：点「固定分辨率」先展开输入框，点「应用」才真正切换
    //（resState 变化时复位 —— 与 AnWind ControlBar 同款交互）
    var nativeSel by remember(resState.mode) { mutableStateOf(modeNative) }
    var resText by remember(resState.mode) {
        mutableStateOf(
            if (resState.mode == "exact" && resState.exact.isNotEmpty()) resState.exact
            else prefs?.displayResolutionExact?.get() ?: "1280x720"
        )
    }
    var resError by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(X11FloatChrome.QUICKBAR_H)
            .background(theme.windowTitleBarColor.copy(alpha = 0.86f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        QcTextButton(
            text = "跟随窗口",
            active = modeNative,
            onClick = {
                nativeSel = true
                prefs?.displayResolutionMode?.put("native")
                X11ResolutionLink.setNative()
            }
        )
        QcTextButton(
            text = "固定分辨率",
            active = !nativeSel,
            onClick = { nativeSel = false }
        )
        if (!nativeSel) {
            QcResInput(
                value = resText,
                isError = resError,
                onValueChange = { resText = it; resError = false }
            )
            QcTextButton(
                text = "应用",
                active = false,
                onClick = {
                    val m = Regex("(\\d{2,5})x(\\d{2,5})").find(resText.trim())
                    val (w, h) = m?.destructured ?: run { resError = true; return@QcTextButton }
                    if (w.toInt() < 160 || h.toInt() < 120) { resError = true; return@QcTextButton }
                    nativeSel = false
                    prefs?.displayResolutionExact?.put("${w}x${h}")
                    X11ResolutionLink.setExact(w.toInt(), h.toInt())
                }
            )
        }
        // X 分辨率来源指示（glibc-runner -d 握手状态一目了然）
        Text(
            text = when {
                resState.mode == "exact" -> "X:${resState.exact}"
                resState.mode == "scaled" -> "X:缩放"
                else -> "X:跟随窗口"
            },
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = if (resState.fromGame) theme.accentColor else theme.windowTitleBarTextColor
        )
        QcTextButton(
            text = "切窗",
            active = false,
            onClick = { X11WindowOps.cycleTopWindow() }
        )
        QcTextButton(
            text = "键盘",
            active = VirtualKeyboardController.visible,
            onClick = { VirtualKeyboardController.toggle() }
        )
        QcTextButton(
            text = "游戏全屏",
            active = false,
            onClick = { com.termux.x11.X11InputHub.sendAltEnter() }
        )
    }
}

/** 快捷条文本按钮（紧凑，选中 = 品牌色）。 */
@Composable
private fun QcTextButton(text: String, active: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Text(
        text,
        color = if (active) theme.accentColor else theme.windowTitleBarTextColor,
        fontSize = 10.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 5.dp)
    )
}

/** 快捷条分辨率输入框（紧凑等宽，WxH）。 */
@Composable
private fun QcResInput(
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit
) {
    val theme = LocalWinTheme.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = theme.windowTitleBarTextColor
        ),
        cursorBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(theme.accentColor, theme.accentColor)
        ),
        modifier = Modifier
            .width(84.dp)
            .background(Color(0x33000000), RoundedCornerShape(6.dp))
            .border(
                1.dp,
                if (isError) Color(0xFFFF5A5A) else Color(0x22FFFFFF),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 5.dp)
    )
}

/** 单个缩放手柄（不可见触控条/角块，命中即拖）。 */
@Composable
private fun BoxScope.X11ResizeHandle(
    edge: ResizeEdge,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val strip = 14.dp
    val corner = 22.dp
    val mod = when (edge) {
        ResizeEdge.L  -> Modifier.align(Alignment.CenterStart).fillMaxHeight().width(strip)
        ResizeEdge.R  -> Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(strip)
        ResizeEdge.T  -> Modifier.align(Alignment.TopCenter).fillMaxWidth().height(strip)
        ResizeEdge.B  -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(strip)
        ResizeEdge.LT -> Modifier.align(Alignment.TopStart).size(corner)
        ResizeEdge.RT -> Modifier.align(Alignment.TopEnd).size(corner)
        ResizeEdge.LB -> Modifier.align(Alignment.BottomStart).size(corner)
        ResizeEdge.RB -> Modifier.align(Alignment.BottomEnd).size(corner)
    }
    Box(
        mod.pointerInput(edge) {
            detectDragGestures(
                onDragStart = { onDragStart() },
                onDragEnd = { onDragEnd() },
                onDragCancel = { onDragCancel() }
            ) { change, amt ->
                change.consume()
                onDrag(amt.x, amt.y)
            }
        }
    )
}

// =============================================================================
// v6.4：前缀构建进度悬浮卡（X11 界面内）
//（用户："容器创建时构建前缀的进度条没有显示在x11界面，应该显示在x11界面，
//  并支持拖动，支持日志显示和隐藏，不妨碍x11的操作"）
// =============================================================================

/**
 * 前缀构建进度悬浮卡：
 * - 数据源与壳层 PrefixBuildCard 同源（RootfsLauncher.prefixBuildFlow +
 *   prefixLogOf 600ms 轮询 + 关键词阶段推断）；
 * - 可拖动（标题行 / 收起徽标拖动，钳制在画面区内）；
 * - 日志默认隐藏，"显示日志 ▾" 展开（等宽字体，最高 180dp 可滚动）；
 * - 可收起为小徽标（点徽标展开），不拦截 X11 其余区域操作；
 * - 构建结束（flow → null）自动消失；真全屏时不显示。
 */
@Composable
private fun X11PrefixBuildOverlay(areaW: Int, areaH: Int) {
    val building by RootfsLauncher.prefixBuildFlow.collectAsState()
    val name = building ?: return
    if (areaW <= 0 || areaH <= 0) return
    val theme = LocalWinTheme.current
    val density = LocalDensity.current

    val log by produceState(RootfsLauncher.prefixLogOf(name), name) {
        while (true) {
            delay(600)
            value = RootfsLauncher.prefixLogOf(name)
        }
    }
    val elapsed by produceState(0L, name) {
        val start = System.currentTimeMillis()
        while (true) {
            delay(1000)
            value = (System.currentTimeMillis() - start) / 1000
        }
    }
    val stage = remember(log) { prefixStageOf(log) }

    // 悬浮位置（px；Int.MIN_VALUE = 首帧前，按 10dp 边距落左上角）
    var ox by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var oy by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var mySize by remember { mutableStateOf(IntSize.Zero) }
    var expanded by remember { mutableStateOf(true) }
    var logOpen by remember { mutableStateOf(false) }

    fun moveBy(dx: Float, dy: Float) {
        val nx = if (ox == Int.MIN_VALUE) 0 else ox + dx.roundToInt()
        val ny = if (oy == Int.MIN_VALUE) 0 else oy + dy.roundToInt()
        ox = nx.coerceIn(0, (areaW - mySize.width).coerceAtLeast(0))
        oy = ny.coerceIn(0, (areaH - mySize.height).coerceAtLeast(0))
    }

    val margin = with(density) { 10.dp.roundToPx() }

    if (expanded) {
        Column(
            modifier = Modifier
                .offset {
                    if (ox == Int.MIN_VALUE) IntOffset(margin, margin) else IntOffset(ox, oy)
                }
                .onGloballyPositioned {
                    mySize = it.size
                    if (ox == Int.MIN_VALUE) { ox = margin; oy = margin }
                }
                .width(286.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(theme.windowBackgroundColor.copy(alpha = 0.96f))
                .border(1.dp, theme.windowBorderColor, RoundedCornerShape(14.dp))
        ) {
            // ---- 标题行（拖动区 + 开关） ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.windowTitleBarColor)
                    .pointerInput(Unit) {
                        detectDragGestures { change, amt ->
                            change.consume()
                            moveBy(amt.x, amt.y)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = theme.accentColor
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "正在构建前缀 · $name",
                        color = theme.windowTitleBarTextColor,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "$stage · ${elapsed / 60}:${"%02d".format(elapsed % 60)}",
                        color = theme.windowTitleBarTextColor.copy(alpha = 0.75f),
                        fontSize = 9.sp
                    )
                }
                Text(
                    if (logOpen) "隐藏日志 ▴" else "显示日志 ▾",
                    color = theme.accentColor, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { logOpen = !logOpen }
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "收起",
                    color = theme.windowTitleBarTextColor, fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = false }
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                )
            }

            // ---- 流动进度条 ----
            val transition = rememberInfiniteTransition(label = "x11prefix")
            val shift by transition.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(1200, easing = LinearEasing), RepeatMode.Restart
                ),
                label = "shift"
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(theme.windowTitleBarTextColor.copy(alpha = 0.14f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(
                            fraction = 0.34f + 0.10f * kotlin.math.sin(
                                shift * Math.PI.toFloat() * 2
                            ).coerceAtLeast(0f)
                        )
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(theme.accentColor, theme.accentColor.copy(alpha = 0.55f))
                            )
                        )
                )
            }

            // ---- 日志（默认隐藏） ----
            AnimatedVisibility(
                visible = logOpen,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(160))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 8.dp)
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (theme.isDark) Color(0xE6050810) else Color(0xFFF2F4F8))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    Text(
                        log.takeLast(16000),
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (theme.isDark) Color(0xCC8FA3B8) else Color(0xFF3A4450)
                    )
                }
            }
        }
    } else {
        // ---- 收起徽标（拖动 + 点按展开） ----
        Row(
            modifier = Modifier
                .offset {
                    if (ox == Int.MIN_VALUE) IntOffset(margin, margin) else IntOffset(ox, oy)
                }
                .onGloballyPositioned {
                    mySize = it.size
                    if (ox == Int.MIN_VALUE) { ox = margin; oy = margin }
                }
                .clip(RoundedCornerShape(999.dp))
                .background(theme.windowBackgroundColor.copy(alpha = 0.95f))
                .border(1.dp, theme.windowBorderColor, RoundedCornerShape(999.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, amt ->
                        change.consume()
                        moveBy(amt.x, amt.y)
                    }
                }
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.6.dp,
                color = theme.accentColor
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "前缀 $stage",
                color = theme.windowTitleBarTextColor,
                fontSize = 10.sp, maxLines = 1
            )
        }
    }
}
