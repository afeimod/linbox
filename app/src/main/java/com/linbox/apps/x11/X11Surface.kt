package com.linbox.apps.x11

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Display
import android.view.View
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.linbox.apps.containers.launcher.RootfsLauncher
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.theme.LinShellTheme
import com.linbox.core.input.gamepad.GamepadController
import com.linbox.core.input.gamepad.GamepadOverlayContent
import com.linbox.core.shell.ShellController
import com.linbox.LinBoxApp
import com.linbox.shellui.prefixStageOf
import com.termux.x11.LoriePreferences
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
 * - 触摸方式（termux-x11 touchMode，X11 设置面板切换即时生效）:
 *   触控板（虚拟光标）/ 模拟触摸屏（双击吸附/按住拖拽/双指右键/滚轮）/
 *   直接触摸（XI2 多点触摸直注）—— SmartTouchBridge 输入策略切换；
 * - 虚拟手柄：悬浮 GamepadOverlay 按键经 X11InputHub 桥直注 X；
 * - 附加键盘栏（showAdditionalKbd）：画面底部 ESC/方向键玻璃键行，
 *   点按直注 X。
 */
@Composable
fun X11Screen() {
    val context = LocalContext.current
    val connState by X11WindowController.state.collectAsState()
    var lorieViewRef by remember { mutableStateOf<LorieView?>(null) }
    val prefs = remember { LoriePreferences.prefs }

    // v2.25：LoriePreferences（不可观察）→ X11UiPrefs（可观察镜像），
    // 供全屏/方向/亮屏/刘海/附加键盘栏驱动副作用
    LaunchedEffect(prefs) { prefs?.let { X11UiPrefs.initIfNeed(it) } }

    // 真全屏：画面独占（悬浮球/键行隐藏，返回键退出）；悬浮球与设置面板驱动
    val trueFs = X11UiPrefs.fullscreen
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

    // ===== v6.4：小窗模式（自由窗口，AnWind WindowChrome 同款交互）=====
    // 状态持久化在 linbox_x11_float（Prefs.java 为脚本生成物不能手改）
    LaunchedEffect(Unit) { X11FloatWindow.initIfNeed(context) }
    val density = LocalDensity.current
    val floatWin = X11FloatWindow.enabled && !trueFs

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

    // 小窗帧 → View 层 LorieView 内容帧同步（外框去标题栏/边框；关闭时清空回铺满）。
    // key 含 lorieRootRef：factory 创建根容器（晚于首次组合）后也要重启同步。
    LaunchedEffect(
        lorieRootRef, floatWin, X11FloatWindow.x, X11FloatWindow.y,
        X11FloatWindow.w, X11FloatWindow.h, X11FloatWindow.maximized, areaSize
    ) {
        val root = lorieRootRef ?: return@LaunchedEffect
        if (!floatWin || areaSize == IntSize.Zero) {
            root.lorieFrame = null
            return@LaunchedEffect
        }
        val f = X11FloatWindow.effectiveFrame(areaSize.width, areaSize.height)
        val bd = with(density) { 1.dp.roundToPx() }
        val titleHpx = with(density) { X11FloatChrome.TITLE_H.roundToPx() }
        root.lorieFrame = Rect(
            f.left + bd, f.top + titleHpx + bd,
            f.right - bd, f.bottom - bd
        )
    }

    // 智能鼠标桥与手柄层（随 LorieView 实例创建，见 factory）
    var touchBridge by remember { mutableStateOf<SmartTouchBridge?>(null) }

    // v2.32：手柄 Compose 宿主引用（onDispose 时销毁组合防泄漏）+
    // LocalLifecycleOwner（factory 非 Composable 作用域内预取）
    val lifecycleOwner = LocalLifecycleOwner.current
    var gamepadHostRef by remember { mutableStateOf<ComposeView?>(null) }

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
            // v2.32：手柄 Compose 宿主组合显式销毁（AndroidView 内嵌
            // ComposeView 的组合不随 View detach 自动释放，防泄漏）
            gamepadHostRef?.disposeComposition()
            gamepadHostRef = null
            // 手柄 → X11 转发目标一并注销（内部会对仍按着的键补发 UP）
            X11InputHub.get(context).setActiveLorieView(null)
            X11WindowController.detachView(userClosed = true)
        }
    }

    // 返回键：真全屏时先退全屏；否则回终端主页
    BackHandler(enabled = trueFs) { setFullscreen(false) }
    BackHandler(enabled = !trueFs) { ShellController.showHome() }

    // ------------------------------------------------------------------
    // v2.25 窗口效果（termux-x11 keepScreenOn / forceOrientation / hideCutout）
    // 均应用在宿主 Activity 窗口上，离开 X11 界面时统一还原全局设置。
    // ------------------------------------------------------------------
    val activity = context as? android.app.Activity

    // 保持亮屏
    DisposableEffect(X11UiPrefs.keepScreenOn) {
        val win = activity?.window
        if (X11UiPrefs.keepScreenOn) {
            win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 屏幕方向（面板切换即时生效）
    LaunchedEffect(X11UiPrefs.orientation) {
        activity?.requestedOrientation = when (X11UiPrefs.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    // 刘海区：进入时应用；开关切换时重新应用（Keyed 重启，无竞态）
    DisposableEffect(Unit) {
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
        applyCutout(X11UiPrefs.hideCutout)
        onDispose {
            // 离开 X11：按应用全局设置还原（DataStore 异步读，主线程落窗）
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
    }
    // 刘海开关变化（设置面板切换后）重新应用
    LaunchedEffect(X11UiPrefs.hideCutout) {
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
            // v6.4：小窗模式下画面区变成“桌面” —— 背景随全局亮暗主题；
            // 常规模式保持纯黑（游戏画面对比度优先）
            .background(if (floatWin) LinShellTheme.bg else Color.Black)
    ) {
        // ===== 画面区（悬浮球 / 附加键盘栏 / 等待页全部悬浮其上） =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { areaSize = it.size }
        ) {
            // v6.4：小窗“桌面”背景层 —— 窗口外的落点在此被消费（不注入 X，
            // 也不穿透到页面下层）；仅小窗模式渲染，置于 View 层之下
            if (floatWin) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(LinShellTheme.bg)
                        .pointerInput(Unit) { detectTapGestures { } }
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // 根容器：v2.32 起为触摸分流容器（X11TouchSplitLayout）——
                    // 手柄显示时逐指分流：手柄指针 → 手柄宿主（gamepadHost），
                    // 屏幕指针 → LorieView 直达桥，与按下顺序无关（View 层
                    // 硬分离，不再依赖 Compose 命中路径锁定顺序）；手柄未
                    // 显示时零开销直通。
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

                    // v2.32：手柄 Compose 宿主（GamepadOverlayContent 渲染于
                    // LorieView 同层 Z 顶）。View 层分流的手柄侧接收者：摇杆/
                    // 十字键/按钮/工具条的事件经 split 子事件直达此树，不受
                    // Compose 主树命中竞争影响。嵌入 ComposeView 需显式挂
                    // lifecycle/registry owner（Activity 同时实现两者）。
                    // v6.4：小窗模式下仍铺满全屏 —— 手柄按钮不应被窗口裁切。
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
                    root.addView(gamepadHost, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.FILL
                    ))
                    gamepadHost.visibility = View.GONE
                    gamepadHostRef = gamepadHost

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
                    root.padHost = gamepadHost
                    root.padSplitActive = false // update 块按手柄开关驱动
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
                    // v2.32：手柄开关 → 分流激活 + 宿主可见性。命中矩形由
                    // 元素布局后登记（onGloballyPositioned），分流器内部实时
                    // 判定可用性 —— 未布局完成时自动退回直通（旧路径兜底），
                    // 无需在此跟踪命中状态。
                    root.padSplitActive = gamepadEnabled
                    // v2.32.2：桥侧剥离随分流激活而直通（记账职责移交容器，
                    // 防双层记账时序差吞 UP —— 详见 SmartTouchBridge.splitActive）
                    touchBridge?.splitActive = gamepadEnabled
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

            // ===== 等待页（未连接时覆盖） =====
            if (connState != X11WindowController.State.Connected) {
                WaitingPanel(connState)
            }

            // ===== v6.4：前缀构建进度悬浮卡（用户："容器创建时构建前缀的
            //      进度条没有显示在 x11 界面"）—— 前缀构建期间用户往往已
            //      被拉进 X11 页看 wine 桌面，壳层页的进度卡/日志窗全被
            //      全屏画面盖住。此处按 RootfsLauncher.prefixBuildFlow 驱动：
            //      可拖动、日志可显隐、可收起为小徽标，不拦截 X11 操作。 =====
            if (!trueFs) {
                X11PrefixBuildOverlay(
                    areaW = areaSize.width,
                    areaH = areaSize.height
                )
            }

            // ===== v6.4：小窗模式窗口 chrome（标题栏拖动 / 双击最大化 /
            //      边缘八向缩放 / 关闭回主页；画面本体是 View 层 LorieView
            //      动态布局帧，chrome 只画边框与标题栏，内容区透出画面） =====
            if (floatWin && connState == X11WindowController.State.Connected) {
                X11FloatWindowChrome(
                    areaW = areaSize.width,
                    areaH = areaSize.height,
                    onExit = { ShellController.showHome() }
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

            // ===== v5.0：原 X11 玻璃悬浮球已移除 —— 全部功能并入虚拟手柄
            //      悬浮球（GamepadFloatingBall，常驻 LinBoxShell 层，尺寸不随
            //      全局缩放）。此处不再渲染第二个球，修复双悬浮球不统一。 =====
        }
    }

    // v2.22.4 fix11c：X11 设置面板（悬浮球菜单 / 常驻通知 "X11 设置" 动作）
    X11SettingsDialog()
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

    /** 轻点拖拽（tapToMove）：左键已按下、等待"再轻点"释放。 */
    private var padPressed = false

    /**
     * 从偏好同步触摸方式与触控板参数（真实生效：本桥是 LinBox X11 的
     * 触摸事件入口，等效上游 TouchInputHandler.reloadPreferences）。
     * 方式切换时释放悬挂的按压/手势状态，防止旧模式的左键"按住不放"。
     */
    fun reloadTouchPrefs(p: Prefs) {
        val newMode = p.touchMode.get().toIntOrNull() ?: TOUCH_SIMULATED
        if (newMode != touchMode) {
            if (pressedLeft) { sendButton(InputStub.BUTTON_LEFT, false); pressedLeft = false }
            if (padPressed) { sendButton(InputStub.BUTTON_LEFT, false); padPressed = false }
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
    private var pressedLeft = false
    private var downX = 0f; private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var curX = 0f; private var curY = 0f
    // v2.33.1：本手势 tap 落点（X 坐标）—— 按下与释放共用同一条 absolute
    // 坐标（释放零位移）；双击吸附记录同一落点（吸附语义对齐按下瞬间）
    private var tapX = 0f; private var tapY = 0f

    // 双指
    private var twoStartAt = 0L
    private var twoMoved = false
    private var lastTwoY = 0f
    private var scrollAccum = 0f

    // 双击吸附（X 坐标）
    private var lastTapX = -1f; private var lastTapY = -1f
    private var lastTapAt = 0L

    private val slopPx = (ViewConfiguration.get(context).scaledTouchSlop * 1.5f)
    private val tapMaxMs = 260L
    private val dblTapMaxMs = 450L
    private val wheelStep = 40f

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
        if (pressedLeft) {
            sendButton(InputStub.BUTTON_LEFT, false)
            pressedLeft = false
            diagResyncs++
        }
        if (padPressed) {
            sendButton(InputStub.BUTTON_LEFT, false)
            padPressed = false
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
        append("  桥按压态: 左键="); append(if (pressedLeft) "按下" else "释放")
        append(" 轻点拖拽="); append(if (padPressed) "按下" else "释放")
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

    /**
     * 模式 2 —— 模拟触摸屏（termux-x11 "Simulated touchscreen"）：
     * 手指位置即鼠标位置（按下即左键、拖拽跟随、双击吸附、双指右键/滚轮）。
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
                moved = false
                diagDowns++
                val p = snapPoint(downX, downY)
                // v2.33.1（tap 失灵根修）：定位 + 左键按下合并为单条
                // absolute 按钮消息（对齐上游 TouchInputHandler simulated
                // touchscreen 同款 sendMouseEvent(x, y, LEFT, true, false)）。
                // 旧实现"absolute 移动（BUTTON_UNDEFINED）+ (0,0) relative
                // 按钮"是混合序列 —— 手柄鼠标键（纯 relative 按钮）与滑屏
                // （纯 relative 移动）均正常、唯独 tap 失灵（用户实测），
                // 病灶即此混合序列；上游单消息语义经全球 wine 用户验证。
                // 局部 val 捕获快照（post 执行时 tapX/tapY 已可被新手势改写）。
                val px = p[0]
                val py = p[1]
                tapX = px; tapY = py
                // v3.0（AnWind 移植）：点击置前 —— 把包含落点的最顶层可见
                // 根子窗提到最前（无 WM 的 X 屏上多 wine 会话堆叠时的
                // click-to-raise；fire-and-forget，不阻塞触摸线程）。
                X11WindowOps.raiseAt(px.toInt(), py.toInt())
                postSend {
                    if (LorieView.connected())
                        view.sendMouseEvent(px, py, InputStub.BUTTON_LEFT, true, false)
                }
                pressedLeft = true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.ONE && event.pointerCount >= 2) {
                    mode = Mode.TWO
                    // v2.30.1：拖拽被双指手势接管前，立即冲刷残余增量（不丢尾段）
                    // v2.33.1：冲刷异步化（FIFO 先于后续事件）
                    postFlushMoves()
                    twoStartAt = android.os.SystemClock.uptimeMillis()
                    twoMoved = false
                    scrollAccum = 0f
                    lastTwoY = (event.getY(0) + event.getY(1)) / 2f
                    if (pressedLeft) {
                        sendButton(InputStub.BUTTON_LEFT, false)
                        pressedLeft = false
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.ONE -> {
                        // v2.30 修复（wine 游戏视角乱飘/卡死）：拖拽期间改发
                        // 相对增量（relative=true → libXlorie XI2 raw relative
                        // motion）。旧实现逐帧发绝对坐标：wine FPS 按住转视角时
                        // 游戏每帧 XWarpPointer 回窗口中心，我们的绝对事件把指针
                        // 强拽到指尖位置 → 游戏读到巨大跳变（视角猛转）；指针被
                        // clamp 在屏幕边缘后不再变化（视角卡死"固定视角"）。
                        // 相对增量不受 warp 影响：桌面拖拽路径与手指一致，
                        // 游戏视角真实旋转。按下瞬间已随单消息 absolute 定位
                        // 到指尖；超出手抖阈值后逐帧跟随。
                        // v2.30.1：改为累加进合并器，16ms 节流冲刷 —— 事件量
                        // 减半以上，消除 wine 游戏滑动掉帧；总量精确守恒。
                        if (!moved) {
                            val ddx = event.x - downX
                            val ddy = event.y - downY
                            moved = ddx * ddx + ddy * ddy > slopPx * slopPx
                            if (moved)
                                accumulateMove(ddx * scaleX, ddy * scaleY)
                        } else {
                            accumulateMove((event.x - curX) * scaleX, (event.y - curY) * scaleY)
                        }
                        curX = event.x; curY = event.y
                    }
                    Mode.TWO -> {
                        if (event.pointerCount >= 2) {
                            val avgY = (event.getY(0) + event.getY(1)) / 2f
                            val dy = (lastTwoY - avgY) * scaleY // 手指上滑 → 正值 → 内容下滚
                            lastTwoY = avgY
                            if (kotlin.math.abs(dy) > 0.5f) twoMoved = true
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
                if (mode == Mode.TWO) {
                    // 双指轻点（未滑动、时间短）→ 右键单击
                    // v2.33.1：同样改单条 absolute 按钮消息（与单指 tap 同语义）
                    if (!twoMoved && android.os.SystemClock.uptimeMillis() - twoStartAt < 320L) {
                        val p = toXpx(curX, curY)
                        val px = p[0]
                        val py = p[1]
                        postSend {
                            if (LorieView.connected()) {
                                view.sendMouseEvent(px, py, InputStub.BUTTON_RIGHT, true, false)
                                view.sendMouseEvent(px, py, InputStub.BUTTON_RIGHT, false, false)
                            }
                        }
                    }
                    mode = Mode.TWO_DONE
                }
            }

            MotionEvent.ACTION_UP -> {
                // v2.30.1：手势结束先冲刷残余增量（不丢拖拽尾段）再抬键
                // v2.33.1：冲刷异步化（FIFO 保证先于按钮释放写入 X）
                diagUps++
                if (pressedLeft) {
                    postFlushMoves()
                    if (!moved && mode == Mode.ONE) {
                        // tap 释放：absolute 同位置（与按下点一致，零位移、零 warp）
                        // 局部 val 快照（与按下消息同规则：post 执行时读快照）
                        val rx = tapX
                        val ry = tapY
                        postSend {
                            if (LorieView.connected())
                                view.sendMouseEvent(rx, ry, InputStub.BUTTON_LEFT, false, false)
                        }
                    } else {
                        // 拖拽/双指后释放：当前指针位置抬键（relative 0,0，
                        // 不回 warp —— 拖拽中游戏 warp 循环不受扰动）
                        sendButton(InputStub.BUTTON_LEFT, false)
                    }
                    pressedLeft = false
                }
                if (mode == Mode.ONE) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!moved && now - downAt <= tapMaxMs) {
                        diagTaps++
                        // 完整轻点 → 记录落点（X 坐标）供双击吸附（同按下瞬间落点）
                        lastTapX = tapX; lastTapY = tapY; lastTapAt = now
                    } else {
                        lastTapAt = 0L
                    }
                }
                mode = Mode.IDLE
            }

            MotionEvent.ACTION_CANCEL -> {
                // v2.30.1：系统取消手势 → 丢弃未冲刷增量（不发出意外移动）
                dropPendingMoves()
                if (pressedLeft) {
                    // CANCEL：当前位置抬键（不 warp；tap 未发生不产生点击）
                    sendButton(InputStub.BUTTON_LEFT, false)
                    pressedLeft = false
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
     * 模式 1 —— 触控板（termux-x11 "Trackpad"）：
     * 手指滑动 = 虚拟光标相对移动（scaleTouchpad 开启时位移按视图→X
     * 屏幕拉伸比例放大，与上游 TouchInputHandler 一致）；
     * 轻点 = 左键单击；tapToMove（轻点拖拽）= 轻点按下左键、移动拖拽、
     * 再轻点释放；双指轻点 = 右键单击；双指滑动 = 滚轮。
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
                moved = false
                diagDowns++
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.ONE && event.pointerCount >= 2) {
                    mode = Mode.TWO
                    // v2.30.1：拖拽被双指手势接管前，立即冲刷残余增量（不丢尾段）
                    // v2.33.1：冲刷异步化（FIFO 先于后续事件）
                    postFlushMoves()
                    twoStartAt = android.os.SystemClock.uptimeMillis()
                    twoMoved = false
                    scrollAccum = 0f
                    lastTwoY = (event.getY(0) + event.getY(1)) / 2f
                    // 拖拽中落下第二根手指 → 结束拖拽（真实触控板习惯）
                    if (padPressed) { sendButton(InputStub.BUTTON_LEFT, false); padPressed = false }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.ONE -> {
                        val dx = event.x - curX
                        val dy = event.y - curY
                        curX = event.x; curY = event.y
                        if (!moved) {
                            val ddx = curX - downX
                            val ddy = curY - downY
                            moved = ddx * ddx + ddy * ddy > slopPx * slopPx
                        }
                        // v2.30 修复（wine 游戏视角乱飘/卡死）：发真实相对运动
                        // （sendMouseEvent relative=true → libXlorie XI2 raw
                        // relative motion，上游 TrackpadInputStrategy 同款）。
                        // 旧实现维护虚拟光标 padX/padY 发绝对坐标：wine FPS 按住
                        // 转视角时游戏每帧 XWarpPointer 回窗口中心，绝对事件把
                        // 指针强拽到 padX/padY → 游戏读到巨大跳变（视角猛转），
                        // 光标 clamp 在屏幕边缘后不再变化（视角卡死"固定视角"）。
                        // 相对增量不受 warp 影响：桌面光标照常移动，游戏视角
                        // 真实旋转。slop 内丢弃（防手抖）；轻点/右键落在当前
                        // X 指针位置（相对模式无虚拟光标）。
                        // scaleTouchpad：位移按视图→X 拉伸比例放大（对齐上游）。
                        // v2.30.1：改为累加进合并器，16ms 节流冲刷 —— 消除
                        // wine 游戏滑动掉帧；总量精确守恒，手感不变。
                        if (moved) {
                            val mulX = if (scaleTouchpad) scaleX else 1f
                            val mulY = if (scaleTouchpad) scaleY else 1f
                            accumulateMove(dx * mulX, dy * mulY)
                        }
                    }
                    Mode.TWO -> {
                        if (event.pointerCount >= 2) {
                            val avgY = (event.getY(0) + event.getY(1)) / 2f
                            val dy = (lastTwoY - avgY) * scaleY // 双指上滑 → 滚轮下滚
                            lastTwoY = avgY
                            if (kotlin.math.abs(dy) > 0.5f) twoMoved = true
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
                if (mode == Mode.TWO) {
                    // 双指轻点（未滑动、时间短）→ 右键单击（当前 X 指针位置）
                    if (!twoMoved && android.os.SystemClock.uptimeMillis() - twoStartAt < 320L) {
                        sendButton(InputStub.BUTTON_RIGHT, true)
                        sendButton(InputStub.BUTTON_RIGHT, false)
                    }
                    mode = Mode.TWO_DONE
                }
            }

            MotionEvent.ACTION_UP -> {
                // v2.30.1：手势结束先冲刷残余增量（不丢拖拽尾段）
                // v2.33.1：冲刷异步化（FIFO 先于 tap 抬键写入 X）
                diagUps++
                if (moved) postFlushMoves()
                if (mode == Mode.ONE) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!moved && now - downAt <= tapMaxMs) {
                        if (tapToMove) {
                            // 轻点拖拽：轻点=按下（随后移动即拖拽），再轻点=释放
                            if (padPressed) {
                                sendButton(InputStub.BUTTON_LEFT, false)
                                padPressed = false
                            } else {
                                sendButton(InputStub.BUTTON_LEFT, true)
                                padPressed = true
                            }
                        } else {
                            // 轻点 = 左键单击
                            sendButton(InputStub.BUTTON_LEFT, true)
                            sendButton(InputStub.BUTTON_LEFT, false)
                        }
                    }
                }
                mode = Mode.IDLE
            }

            MotionEvent.ACTION_CANCEL -> {
                // v2.30.1：系统取消手势 → 丢弃未冲刷增量（不发出意外移动）
                dropPendingMoves()
                if (padPressed) { sendButton(InputStub.BUTTON_LEFT, false); padPressed = false }
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
    }

    private fun persist(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean("enabled", enabled)
            .putInt("x", x).putInt("y", y).putInt("w", w).putInt("h", h)
            .putBoolean("maximized", maximized)
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

    /** 当前生效外框（最大化 = 满幅）。 */
    fun effectiveFrame(areaW: Int, areaH: Int): Rect =
        if (maximized) Rect(0, 0, areaW, areaH)
        else Rect(x, y, x + w, y + h)
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
 * 小窗模式窗口 chrome：标题栏（拖动移动 / 双击最大化 / 最大化还原 / ✕ 关闭）
 * + 边缘八向缩放手柄。内容区为透明洞 —— 画面本体是 View 层 LorieView
 * 动态布局帧（X11TouchSplitLayout.lorieFrame），chrome 只画边框与标题栏。
 *
 * 交互对齐 AnWind WindowChrome：移动实时跟手（仅位置变化，无 surface 重建）；
 * 缩放手势期间 chrome 实时预览，松手一次性提交（避免 surface 逐帧重建）。
 */
@Composable
private fun X11FloatWindowChrome(
    areaW: Int,
    areaH: Int,
    onExit: () -> Unit
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
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "X11 桌面",
                    color = theme.windowTitleBarTextColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                // 最大化 / 还原
                Text(
                    if (X11FloatWindow.maximized) "还原" else "最大化",
                    color = theme.windowTitleBarTextColor,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { X11FloatWindow.setMaximized(context, !X11FloatWindow.maximized) }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                )
                Spacer(Modifier.width(4.dp))
                // 关闭（退回主页）
                Text(
                    "✕",
                    color = theme.windowTitleBarTextColor,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onExit() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            // ---- 内容区：透明洞（View 层 LorieView 在此透出） ----
            Spacer(Modifier.weight(1f))
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
