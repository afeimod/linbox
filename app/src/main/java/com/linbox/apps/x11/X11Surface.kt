package com.linbox.apps.x11

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Display
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.shell.ShellController
import com.linbox.LinBoxApp
import com.termux.x11.LoriePreferences
import com.termux.x11.LorieView
import com.termux.x11.Prefs
import com.termux.x11.X11InputHub
import com.termux.x11.input.InputStub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // 智能鼠标桥与手柄层（随 LorieView 实例创建，见 factory）
    var touchBridge by remember { mutableStateOf<SmartTouchBridge?>(null) }

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
            // 手柄 → X11 转发目标一并注销（内部会对仍按着的键补发 UP）
            X11InputHub.get(context).setActiveLorieView(null)
            X11WindowController.detachView(userClosed = true)
        }
    }

    // 返回键：真全屏时先退全屏；否则回终端主页
    BackHandler(enabled = trueFs) { setFullscreen(false) }
    BackHandler(enabled = !trueFs) { ShellController.showTerminal() }

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
            .background(Color.Black)
    ) {
        // ===== 画面区（悬浮球 / 附加键盘栏 / 等待页全部悬浮其上） =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { areaSize = it.size }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // 根容器：LorieView 铺满窗口（native 跟随窗口 / exact+stretch
                    // 拉伸铺满，均无黑边）。
                    // v2.22.5 fix12：Gravity.CENTER → FILL —— 画面靠左上锚定
                    // （用户反馈"黑边没有靠左"）；FILL = TOP|START|BOTTOM|END。
                    val root = FrameLayout(ctx)

                    val lv = LorieView(ctx)
                    root.addView(lv, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.FILL
                    ))

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
                }
            )

            // ===== 等待页（未连接时覆盖） =====
            if (connState != X11WindowController.State.Connected) {
                WaitingPanel(connState)
            }

            // ===== v2.25 附加键盘栏（showAdditionalKbd；连接后显示，真全屏时隐藏，
            //      imePadding 使其贴在输入法上沿） =====
            if (connState == X11WindowController.State.Connected &&
                X11UiPrefs.showAdditionalKbd && !trueFs
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .imePadding(),
                    contentAlignment = Alignment.BottomStart
                ) {
                    X11ExtraKeysBar()
                }
            }

            // ===== v2.25 玻璃悬浮球（原底边控制条全部功能收编于此；
            //      真全屏时隐藏 —— 画面独占，返回键退出） =====
            if (!trueFs) {
                X11GlassFab(
                    areaPx = areaSize,
                    gamepadEnabled = gamepadEnabled,
                    fullscreen = trueFs,
                    onToggleFullscreen = { setFullscreen(!trueFs) },
                    onOpenSettings = { X11SettingsBridge.requestShow() },
                    onBackToTerminal = { ShellController.showTerminal() }
                )
            }
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
 */
internal class SmartTouchBridge(private val context: Context, private val view: LorieView) {
    private val renderData = com.termux.x11.input.RenderData()
    private val keySender = com.termux.x11.input.InputEventSender(view)

    companion object {
        // 触摸方式取值（对齐 termux-x11 touchscreenInputModesValues）
        const val TOUCH_TRACKPAD = 1
        const val TOUCH_SIMULATED = 2
        const val TOUCH_DIRECT = 3

        /** 活跃桥：设置面板修改触摸偏好后经 [applyTouchPrefs] 即时下发。 */
        @Volatile var activeBridge: SmartTouchBridge? = null

        /** 把 LoriePreferences 的触摸偏好（方式/触控板缩放/轻点拖拽）下发到活跃桥。 */
        fun applyTouchPrefs() {
            val p = LoriePreferences.prefs ?: return
            activeBridge?.reloadTouchPrefs(p)
        }
    }

    // ---- 触摸偏好（termux-x11：touchMode / scaleTouchpad / tapToMove） ----
    private var touchMode = TOUCH_SIMULATED
    private var scaleTouchpad = true
    private var tapToMove = false

    // 触控板虚拟光标（X 屏幕坐标；-1 = 未初始化，首次触摸置于屏幕中心）
    private var padX = -1
    private var padY = -1

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
            padX = -1; padY = -1
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
        view.sendMouseEvent(x.toFloat(), y.toFloat(), InputStub.BUTTON_UNDEFINED, false, false)
    }

    private fun sendButton(button: Int, down: Boolean) {
        // (0,0)+relative = 纯按键事件（在当前指针位置按下/抬起），
        // 与 InputDeviceManager 的注入路径完全一致。
        view.sendMouseEvent(0f, 0f, button, down, true)
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
        // 触摸方式分发（termux-x11 touchMode；X11 设置面板切换后即时生效）
        return when (touchMode) {
            TOUCH_TRACKPAD -> onTouchTrackpad(event)
            TOUCH_DIRECT -> onTouchDirect(event)
            else -> onTouchSimulated(event)
        }
    }

    /**
     * 模式 2 —— 模拟触摸屏（termux-x11 "Simulated touchscreen"）：
     * 手指位置即鼠标位置（按下即左键、拖拽跟随、双击吸附、双指右键/滚轮）。
     */
    private fun onTouchSimulated(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.ONE
                downX = event.x; downY = event.y
                curX = downX; curY = downY
                downAt = android.os.SystemClock.uptimeMillis()
                moved = false
                val p = snapPoint(downX, downY)
                sendMoveX(p[0].toInt(), p[1].toInt())
                sendButton(InputStub.BUTTON_LEFT, true)
                pressedLeft = true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.ONE && event.pointerCount >= 2) {
                    mode = Mode.TWO
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
                        curX = event.x; curY = event.y
                        if (!moved) {
                            val dx = curX - downX
                            val dy = curY - downY
                            moved = dx * dx + dy * dy > slopPx * slopPx
                        }
                        val p = toXpx(curX, curY)
                        sendMoveX(p[0].toInt(), p[1].toInt())
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
                                view.sendMouseWheelEvent(0f, steps * wheelStep)
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
                    if (!twoMoved && android.os.SystemClock.uptimeMillis() - twoStartAt < 320L) {
                        val p = toXpx(curX, curY)
                        sendMoveX(p[0].toInt(), p[1].toInt())
                        sendButton(InputStub.BUTTON_RIGHT, true)
                        sendButton(InputStub.BUTTON_RIGHT, false)
                    }
                    mode = Mode.TWO_DONE
                }
            }

            MotionEvent.ACTION_UP -> {
                if (pressedLeft) {
                    sendButton(InputStub.BUTTON_LEFT, false)
                    pressedLeft = false
                }
                if (mode == Mode.ONE) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!moved && now - downAt <= tapMaxMs) {
                        // 完整轻点 → 记录落点（X 坐标）供双击吸附
                        val p = snapPoint(downX, downY)
                        lastTapX = p[0]; lastTapY = p[1]; lastTapAt = now
                    } else {
                        lastTapAt = 0L
                    }
                }
                mode = Mode.IDLE
            }

            MotionEvent.ACTION_CANCEL -> {
                if (pressedLeft) {
                    sendButton(InputStub.BUTTON_LEFT, false)
                    pressedLeft = false
                }
                mode = Mode.IDLE
            }
        }
        return true
    }

    /**
     * 模式 3 —— 直接触摸（termux-x11 "Direct touch"）：
     * 原始多点触摸不经手势转换，按 XI2 Touch 事件直注 X（坐标经
     * renderData 视图→X 屏幕变换）—— 触摸类游戏/应用获得真实多点触摸，
     * X server 同时向老程序模拟鼠标（xinput 保真，等效上游 NullInputStrategy）。
     */
    private fun onTouchDirect(event: MotionEvent): Boolean {
        keySender.sendTouchEvent(event, renderData)
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
                mode = Mode.ONE
                downX = event.x; downY = event.y
                curX = downX; curY = downY
                downAt = android.os.SystemClock.uptimeMillis()
                moved = false
                if (padX < 0 || padY < 0) { padX = screenW / 2; padY = screenH / 2 }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.ONE && event.pointerCount >= 2) {
                    mode = Mode.TWO
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
                        // 相对位移 → X 屏幕（scaleTouchpad：按拉伸比例放大，对齐上游）
                        val mulX = if (scaleTouchpad) scaleX else 1f
                        val mulY = if (scaleTouchpad) scaleY else 1f
                        padX = (padX + dx * mulX).toInt().coerceIn(0, (screenW - 1).coerceAtLeast(0))
                        padY = (padY + dy * mulY).toInt().coerceIn(0, (screenH - 1).coerceAtLeast(0))
                        sendMoveX(padX, padY)
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
                                view.sendMouseWheelEvent(0f, steps * wheelStep)
                                scrollAccum -= steps * wheelStep
                            }
                        }
                    }
                    else -> {}
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TWO) {
                    // 双指轻点（未滑动、时间短）→ 右键单击（落在虚拟光标处）
                    if (!twoMoved && android.os.SystemClock.uptimeMillis() - twoStartAt < 320L) {
                        sendMoveX(padX, padY)
                        sendButton(InputStub.BUTTON_RIGHT, true)
                        sendButton(InputStub.BUTTON_RIGHT, false)
                    }
                    mode = Mode.TWO_DONE
                }
            }

            MotionEvent.ACTION_UP -> {
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
