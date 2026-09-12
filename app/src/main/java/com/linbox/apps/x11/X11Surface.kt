package com.linbox.apps.x11

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Display
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.shell.ShellController
import com.linbox.LinBoxApp
import com.termux.x11.LoriePreferences
import com.termux.x11.LorieView
import com.termux.x11.X11InputHub
import com.termux.x11.input.InputStub
import kotlinx.coroutines.launch

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
 *   均无黑边）；返回键先退控制条全屏，再退回终端主页；
 * - 分辨率"跟随窗口"（native）或"固定分辨率"（exact）随时切换；
 * - 控制条提供：键盘 / 游戏全屏（Alt+Enter）/ 虚拟手柄开关 / 设置面板；
 * - 智能鼠标桥（SmartTouchBridge）：触摸→真实鼠标事件，wine 游戏
 *   兼容（双击吸附/按住拖拽/双指右键/滚轮）；
 * - 虚拟手柄：悬浮 GamepadOverlay 按键经 X11InputHub 桥直注 X。
 */
@Composable
fun X11Screen() {
    val context = LocalContext.current
    val connState by X11WindowController.state.collectAsState()
    var lorieViewRef by remember { mutableStateOf<LorieView?>(null) }
    // 控制条全屏开关（真全屏：隐藏控制条，画面独占；返回键先退全屏）
    var trueFs by remember { mutableStateOf(false) }

    val prefs = remember { LoriePreferences.prefs }

    // 智能鼠标桥与手柄层（随 LorieView 实例创建，见 factory）
    var touchBridge by remember { mutableStateOf<SmartTouchBridge?>(null) }
    val resState by X11ResolutionLink.state.collectAsState()

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
            // 手柄 → X11 转发目标一并注销（内部会对仍按着的键补发 UP）
            X11InputHub.get(context).setActiveLorieView(null)
            X11WindowController.detachView(userClosed = true)
        }
    }

    // 返回键：真全屏时先退全屏；否则回终端主页
    BackHandler(enabled = trueFs) { trueFs = false }
    BackHandler(enabled = !trueFs) { ShellController.showTerminal() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ===== 画面区 =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
        }

        // ===== 控制条（连接后显示） =====
        // 真全屏（trueFs）时隐藏控制条：画面独占整屏；返回键先退全屏
        // （BackHandler 在本 Composable 顶部处理），控制条重新出现。
        if (connState == X11WindowController.State.Connected && prefs != null && !trueFs) {
            ControlBar(
                prefs = prefs,
                lorieView = lorieViewRef,
                trueFs = trueFs,
                onToggleFullscreen = { trueFs = !trueFs },
                resState = resState
            )
        }
    }

    // v2.22.4 fix11c：X11 设置面板（长按标题栏 / 常驻通知 "X11 设置" 动作）
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

@Composable
private fun ControlBar(
    prefs: com.termux.x11.Prefs,
    lorieView: LorieView?,
    trueFs: Boolean,
    onToggleFullscreen: () -> Unit,
    resState: X11ResolutionLink.ResolutionState
) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val app = LinBoxApp.get()
    val gamepadEnabled by app.settingsStore.gamepadEnabled.collectAsState(initial = false)

    var modeNative by remember(resState.mode) { mutableStateOf(resState.mode != "exact") }
    var resText by remember(resState.mode) {
        mutableStateOf(
            if (resState.mode == "exact" && resState.exact.isNotEmpty()) resState.exact
            else prefs.displayResolutionExact.get()
        )
    }
    var resError by remember { mutableStateOf(false) }
    val barBg = if (theme.isDark) Color(0xFF1B222B) else Color(0xFFF2F4F7)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .border(0.dp, Color.Transparent)
    ) {
        HorizontalDivider(color = if (theme.isDark) Color(0xFF3A4450) else Color(0xFFDDDDDD))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 分辨率模式切换
            TextButton(
                onClick = {
                    modeNative = true
                    prefs.displayResolutionMode.put("native")
                    X11ResolutionLink.setNative()
                    lorieView?.let { it.regenerate(); it.requestLayout() }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (modeNative) theme.accentColor else theme.windowTitleBarTextColor
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("跟随窗口", fontSize = 11.sp) }

            TextButton(
                onClick = { modeNative = false },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (!modeNative) theme.accentColor else theme.windowTitleBarTextColor
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("固定分辨率", fontSize = 11.sp) }

            if (!modeNative) {
                OutlinedTextField(
                    value = resText,
                    onValueChange = { resText = it; resError = false },
                    enabled = !modeNative,
                    isError = resError,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (theme.isDark) Color.White else Color.Black
                    ),
                    modifier = Modifier
                        .width(110.dp)
                        .height(48.dp),
                    placeholder = { Text("1280x720", fontSize = 10.sp) }
                )
                TextButton(
                    onClick = {
                        val m = Regex("(\\d{2,5})x(\\d{2,5})").find(resText.trim())
                        val (w, h) = m?.destructured ?: run { resError = true; return@TextButton }
                        if (w.toInt() < 160 || h.toInt() < 120) { resError = true; return@TextButton }
                        modeNative = false
                        X11ResolutionLink.setExact(w.toInt(), h.toInt())
                        resError = false
                        lorieView?.let { it.regenerate(); it.requestLayout() }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("应用", fontSize = 11.sp) }
            }

            // 分辨率来源指示（glibc-runner 握手状态一目了然）
            Text(
                text = if (resState.mode == "exact") "X:${resState.exact}"
                       else "X:跟随窗口",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (resState.fromGame) theme.accentColor else theme.windowTitleBarTextColor
            )

            Spacer(Modifier.weight(1f))

            // 软键盘开关
            TextButton(
                onClick = {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    @Suppress("DEPRECATION")
                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("键盘", fontSize = 11.sp) }

            // v2.22.5 fix14：游戏全屏（Alt+Enter）—— 窗口化游戏（居中小窗+
            // 四周黑边）一键切换 wine/DXVK 全屏：X 屏幕经 RandR 自动切成游戏
            // 分辨率，画面铺满无黑边。
            TextButton(
                onClick = { X11InputHub.sendAltEnter() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("游戏全屏", fontSize = 11.sp) }

            // 虚拟手柄开关（X11 游戏用：开启后屏幕出现悬浮手柄，
            // 按键/摇杆经 X11InputHub 直注 X）
            TextButton(
                onClick = {
                    app.applicationScope.launch {
                        app.settingsStore.setGamepadEnabled(!gamepadEnabled)
                    }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (gamepadEnabled) theme.accentColor else theme.windowTitleBarTextColor
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text(if (gamepadEnabled) "隐藏手柄" else "虚拟手柄", fontSize = 11.sp) }

            // X11 设置面板（分辨率/拉伸/剪贴板/握手重放）
            TextButton(
                onClick = { X11SettingsBridge.requestShow() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("X11设置", fontSize = 11.sp) }

            // 全屏切换（真全屏：隐藏控制条，返回键退出）
            TextButton(
                onClick = onToggleFullscreen,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text(if (trueFs) "退出全屏" else "全屏", fontSize = 11.sp) }
        }
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
 */
private class SmartTouchBridge(private val context: Context, private val view: LorieView) {
    private val renderData = com.termux.x11.input.RenderData()
    private val keySender = com.termux.x11.input.InputEventSender(view)

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

    fun sendKey(event: KeyEvent): Boolean = keySender.sendKeyEvent(event)
}
