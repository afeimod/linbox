package com.linbox.apps.terminal

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.linbox.LinBoxApp
import com.linbox.apps.terminal.termux.LinBoxShellBridge
import com.linbox.apps.terminal.termux.ExtraKeysModifierState
import com.linbox.apps.terminal.termux.TermuxBootstrapInstaller
import com.linbox.apps.terminal.termux.TermuxSessionController
import com.linbox.core.input.gamepad.GamepadController
import com.linbox.termux.terminal.KeyHandler
import com.linbox.termux.view.TerminalView
import com.linbox.core.shell.ShellController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 终端主页（真实 Termux 移植版）—— LinBox 的主屏：
 *
 * - App 打开即全屏终端，首次自动安装官方 bootstrap（内置离线 aarch64
 *   归档，安装时同长度重写 com.termux → com.linbox 路径前缀）；
 * - 会话为真实 login shell（bash），pkg/apt 可用；
 * - 视图为 termux 官方 TerminalView（Apache-2.0 移植）；
 * - 快捷键栏为 Termux 原版双排布局（ESC/-/HOME/↑/END/PGUP +
 *   ⇤/CTRL/ALT/←/↓/→/PGDN），方向键短按单击、长按连续重复；
 *   v2.24 起整条快捷键栏无背板（键体半透明玻璃片，与终端融为一体）；
 * - 双指捏合缩放字号（TerminalView 内置 ScaleGestureDetector）;
 * - 终端背景可自定义：设置→终端背景（纯色 / 自定义图片 / 透明度）;
 * - v2.24：顶部工具栏收编为透明玻璃悬浮球（可拖动、可展开玻璃菜单，
 *   含新会话/键盘/粘贴/字号/符号层/虚拟手柄/X11/设置）；终端里执行
 *   `linbox-x11` 仍会自动跳转（见 X11WindowController 广播）；
 * - 会话随 App 进程存活，退出 App 或系统杀死进程后重建为全新 shell。
 */
@Composable
fun TerminalScreen() {
    // v4.0：终端是「高级」页的子页面，返回键回主页（游戏容器主页）
    androidx.activity.compose.BackHandler(enabled = true) {
        ShellController.showHome()
    }
    TerminalContent()
}

@Composable
private fun TerminalContent() {
    val context = LocalContext.current
    val installState by TermuxBootstrapInstaller.state.collectAsState()

    // 打开终端即触发按需安装（已安装则秒过），成功后启动桌面命令桥
    LaunchedEffect(Unit) {
        // 存量安装增量迁移：修订号落后时自动升级增强组件（免清数据）——
        // 重写 linbox.sh（修复旧版语法错误）、部署 dpkg 包装器与
        // linbox-reprefix，并全量清理 libapt-pkg 等二进制里的 com.termux 残留
        withContext(Dispatchers.IO) {
            TermuxBootstrapInstaller.migrateIfNeeded(context)
        }
        TermuxBootstrapInstaller.installIfNeeded(
            context,
            onDone = { LinBoxShellBridge.start(context) },
            onError = { /* 状态流负责 UI 呈现 */ }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C0C))
    ) {
        when (val st = installState) {
            is TermuxBootstrapInstaller.InstallState.Installing -> {
                BootstrapInstallingUI(st)
            }
            is TermuxBootstrapInstaller.InstallState.Failed -> {
                BootstrapFailedUI(st)
            }
            else -> {
                val extrasReady by TermuxBootstrapInstaller.extrasReady.collectAsState()
                if ((TermuxBootstrapInstaller.isInstalled(context) ||
                    installState is TermuxBootstrapInstaller.InstallState.Installed) && extrasReady
                ) {
                    RealTerminalArea()
                } else {
                    BootstrapPendingUI()
                }
            }
        }
    }
}

// ====================================================================
// 安装引导 UI
// ====================================================================

@Composable
private fun BootstrapInstallingUI(state: TermuxBootstrapInstaller.InstallState.Installing) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = Color(0xFF00E676),
            strokeWidth = 3.dp,
            modifier = Modifier.size(40.dp)
        )
        Text(
            "正在安装 Termux 环境",
            color = Color(0xFF00E676),
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            "首次使用需要解压官方 bootstrap 并重写路径前缀\n（约 30-60 秒，仅需一次）",
            color = Color(0xFF9E9E9E),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        LinearProgressIndicator(
            progress = { state.progress },
            color = Color(0xFF00E676),
            trackColor = Color(0xFF1E1E1E),
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(6.dp)
        )
        Text(
            state.message,
            color = Color(0xFF616161),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun BootstrapPendingUI() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "正在准备 Termux 环境…",
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun BootstrapFailedUI(state: TermuxBootstrapInstaller.InstallState.Failed) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Bootstrap 安装失败", color = Color(0xFFFF5252), fontSize = 15.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(
            state.message,
            color = Color(0xFFBDBDBD),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TermuxTextButton("重试", accent = true) {
                TermuxBootstrapInstaller.installIfNeeded(
                    context,
                    onDone = { LinBoxShellBridge.start(context) },
                    onError = {}
                )
            }
        }
        Text(
            "提示：设备架构 ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}，" +
                    "离线包内置 aarch64（arm64-v8a）",
            color = Color(0xFF616161),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ====================================================================
// 真实终端区域
// ====================================================================

/** 应用级会话持有者：多终端窗口共享同一会话。 */
object TermuxTerminalHolder {
    val modifiers = ExtraKeysModifierState()

    var sessionFinished by mutableStateOf(false)
        private set

    /** 会话代数：新建会话时递增，驱动 Compose 重新取控制器并重挂视图。 */
    var revision by mutableStateOf(0)
        private set

    private var controller: TermuxSessionController? = null

    fun obtainController(context: android.content.Context): TermuxSessionController {
        if (controller == null || controller?.session == null) {
            controller = TermuxSessionController(context.applicationContext, modifiers).apply {
                createSession()
                onSessionFinished = { sessionFinished = true }
            }
            sessionFinished = false
            revision++
            // v2.27：会话创建即拉起前台常驻服务（状态栏常驻通知防后台误杀）
            TerminalKeepAliveService.start(context.applicationContext)
        }
        return controller!!
    }

    fun newSession(context: android.content.Context): TermuxSessionController {
        controller?.let { old ->
            old.session?.finishIfRunning()
            old.cleanup()
        }
        controller = TermuxSessionController(context.applicationContext, modifiers).apply {
            createSession()
            onSessionFinished = { sessionFinished = true }
        }
        sessionFinished = false
        revision++
        // v2.27：新会话同样拉起前台常驻服务
        TerminalKeepAliveService.start(context.applicationContext)
        return controller!!
    }

    /** 是否存在存活终端会话（TerminalKeepAliveService 巡检用）。 */
    fun hasLiveSession(): Boolean = controller?.session?.isRunning() == true
}

// ====================================================================
// 终端快捷动作桥（v6.2）
// ====================================================================

/**
 * v6.2 终端快捷动作桥（用户："终端界面的悬浮球没有集中到虚拟手柄
 * 悬浮球里，要和 x11 那样多排集合进去"）。
 *
 * 原终端独立玻璃悬浮球（GlassFab，v2.24）已移除 —— 其全部功能
 * （新会话 / 键盘 / 粘贴 / 字号± / 符号层 / X11 / DAC / 设置 / 回主页）
 * 并入**虚拟手柄悬浮球**的「终端快捷」组（多排紧凑网格，与 X11 快捷组
 * 同款；见 GamepadFloatingBall）。X11 / DAC / 设置 / 回主页四项不依赖
 * 终端会话，由悬浮球直接调 ShellController；会话相关动作经本桥投递：
 *
 * - RealTerminalArea 组合时 [register] 登记动作回调（终端页在场 =
 *   [available]=true）；离开组合 [release] 注销；
 * - 悬浮球的终端组仅在校验可用时点亮对应芯片，会话不在场时点击无副作用；
 * - 符号层点亮态（[symbolLayerActive]）随组合周期同步。
 */
object TerminalQuickBus {
    /** 终端会话是否在场（RealTerminalArea 组合中 = true）。 */
    var available by mutableStateOf(false)
        private set

    /** 符号层是否激活（悬浮球芯片点亮态）。 */
    var symbolLayerActive by mutableStateOf(false)
        private set

    var newSession: (() -> Unit)? = null
    var toggleKeyboard: (() -> Unit)? = null
    var paste: (() -> Unit)? = null
    var fontDecrease: (() -> Unit)? = null
    var fontIncrease: (() -> Unit)? = null
    var toggleSymbolLayer: (() -> Unit)? = null

    fun register(
        symbolLayerActive: Boolean,
        onNewSession: () -> Unit,
        onKeyboardToggle: () -> Unit,
        onPaste: () -> Unit,
        onFontDecrease: () -> Unit,
        onFontIncrease: () -> Unit,
        onLayerToggle: () -> Unit
    ) {
        this.symbolLayerActive = symbolLayerActive
        newSession = onNewSession
        toggleKeyboard = onKeyboardToggle
        paste = onPaste
        fontDecrease = onFontDecrease
        fontIncrease = onFontIncrease
        toggleSymbolLayer = onLayerToggle
        available = true
    }

    fun release() {
        available = false
        newSession = null
        toggleKeyboard = null
        paste = null
        fontDecrease = null
        fontIncrease = null
        toggleSymbolLayer = null
    }
}

// ------------------------------------------------------------------
// 终端背景（设置→终端背景：纯色 / 自定义图片）
// ------------------------------------------------------------------

private const val BACKGROUND_IMAGE_FILE = "terminal_bg.jpg"

/** 终端背景解析结果：纯色或图片（图片模式下 TerminalView 透明）。 */
private class TerminalBackgroundState(
    val color: Color,
    val bitmap: android.graphics.Bitmap?,
    /** 背景透明度 0..1（0=不透明，1=完全透明；文字不受影响） */
    val transparency: Float
) {
    /** TerminalView 背景色：图片模式透明（露出下层图片 + 暗化叠加）；
     *  纯色模式按透明度淡化（露出的下层为黑底，视觉上即背景透明度）。 */
    val viewBackgroundArgb: Int = when {
        bitmap != null -> android.graphics.Color.TRANSPARENT
        else -> color.copy(alpha = (1f - transparency).coerceIn(0f, 1f)).toArgb()
    }
}

@Composable
private fun rememberTerminalBackground(): TerminalBackgroundState {
    val app = LinBoxApp.get()
    val context = LocalContext.current
    val colorKey by app.settingsStore.terminalBgColor.collectAsState(initial = "default")
    val imageEnabled by app.settingsStore.terminalBgImage.collectAsState(initial = false)
    val transparency by app.settingsStore.terminalBgTransparency.collectAsState(initial = 0f)
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(imageEnabled) {
        bitmap = if (imageEnabled) withContext(Dispatchers.IO) {
            decodeSampledBackground(java.io.File(context.filesDir, BACKGROUND_IMAGE_FILE))
        } else null
    }

    val color = remember(colorKey) {
        if (colorKey == "default") Color(0xFF0C0C0C)
        else try {
            Color(android.graphics.Color.parseColor(colorKey))
        } catch (_: Exception) {
            Color(0xFF0C0C0C)
        }
    }
    return TerminalBackgroundState(color, bitmap, transparency)
}

/** 大图降采样解码（1440×2560 上限），避免全尺寸位图内存峰值。 */
private fun decodeSampledBackground(file: java.io.File): android.graphics.Bitmap? = try {
    if (!file.isFile) null else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outHeight / sample > 2560 || bounds.outWidth / sample > 1440) sample *= 2
        android.graphics.BitmapFactory.decodeFile(
            file.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
} catch (_: Exception) {
    null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RealTerminalArea() {
    val context = LocalContext.current
    // revision 变化（新建会话）→ 重新取控制器并重挂视图
    val controller = remember(TermuxTerminalHolder.revision) {
        TermuxTerminalHolder.obtainController(context)
    }
    var symbolLayer by remember { mutableStateOf(false) }
    val viewRef = remember { mutableStateOf<TerminalView?>(null) }

    // 控制器/视图就绪后重新挂载（视图在 factory 中创建）
    LaunchedEffect(controller) {
        viewRef.value?.let { controller.attach(it) }
    }

    // 终端背景（纯色 / 自定义图片，图片时终端视图透明；透明度全局可调）
    val bg = rememberTerminalBackground()
    var areaSize by remember { mutableStateOf(IntSize.Zero) }

    // v2.25 键盘区透明根改：背景图从“仅终端区”提升为整屏底层 ——
    // 终端视图（图片模式透明）与快捷键栏（半透明玻璃键体）都能透出
    // 同一张背景图，上下融为一体；透明度仍由设置里的滑杆统一控制
    Box(
        modifier = Modifier
            .fillMaxSize()
            // v2.24：底层统一黑底 —— 背景透明度调节时颜色/图片向黑底淡化，
            // 文字保持不透明，呈现“背景透出去”的效果
            .background(Color.Black)
    ) {
        // ---------- 整屏背景图 + 暗化叠加（终端/键盘共同透出） ----------
        if (bg.bitmap != null) {
            // 图片自身按透明度淡化（1=完全透明 → 只剩黑底）
            Image(
                bitmap = bg.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = (1f - bg.transparency).coerceIn(0f, 1f)
            )
            // 暗化叠加：保证终端文字与键帽文字在任意图片上可读
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f * (1f - bg.transparency))))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // v2.23 IME 遮挡修复：沉浸式（setDecorFitsSystemWindows(false)）下
                // API 30+ 系统忽略 adjustResize，键盘弹出时必须靠 ime() inset 主动
                // 让位——整列（终端/快捷键栏）抬到输入法上方；快捷键栏无
                // 输入法时贴屏幕底边、有输入法时贴输入法上沿（对齐官方 Termux）。
                // API 30 以下 adjustResize 生效且 ime() inset 报 0，两者不叠加。
                .imePadding()
        ) {
        // ---------- 终端视图（图片背景时透明，透出整屏底层图片） ----------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { areaSize = it.size }
        ) {
            AndroidView(
                factory = { ctx ->
                    TermuxTerminalFactory.create(ctx, controller, bg.viewBackgroundArgb).also { view ->
                        viewRef.value = view
                    }
                },
                update = { view -> view.setBackgroundColor(bg.viewBackgroundArgb) },
                onRelease = { view ->
                    if (viewRef.value === view) viewRef.value = null
                    controller.detach(view)
                },
                modifier = Modifier.fillMaxSize()
            )

            // 会话结束覆盖层
            if (TermuxTerminalHolder.sessionFinished) {
                SessionEndedOverlay(
                    onNewSession = {
                        TermuxTerminalHolder.newSession(context)
                    }
                )
            }

            // ---------- v6.2：终端快捷动作桥 ----------
            // 原独立玻璃悬浮球（GlassFab）已移除 —— 全部功能并入虚拟手柄
            // 悬浮球的「终端快捷」组（多排紧凑网格，与 X11 快捷组同款）。
            // 会话相关动作（新会话/键盘/粘贴/字号/符号层）经 TerminalQuickBus
            // 投递；X11 / DAC / 设置 / 回主页由悬浮球直调 ShellController。
            DisposableEffect(controller, symbolLayer) {
                TerminalQuickBus.register(
                    symbolLayerActive = symbolLayer,
                    onNewSession = { TermuxTerminalHolder.newSession(context) },
                    onKeyboardToggle = { toggleSoftKeyboard(context, viewRef.value) },
                    onPaste = {
                        val text = clipboardText(context)
                        if (text != null) viewRef.value?.mEmulator?.paste(text)
                    },
                    onFontDecrease = { controller.changeFontSize(-2) },
                    onFontIncrease = { controller.changeFontSize(+2) },
                    onLayerToggle = { symbolLayer = !symbolLayer }
                )
                onDispose { TerminalQuickBus.release() }
            }
        }

        // ---------- 快捷键栏（两排，透明底：背景图整屏铺放后从此透出） ----------
        TermuxExtraKeysBar(
            controller = controller,
            symbolLayer = symbolLayer
        )
        }
    }
}

/** TerminalView 工厂：视图创建 + 控制器接线（背景色由调用方传入，图片背景时透明）。 */
private object TermuxTerminalFactory {
    fun create(
        context: android.content.Context,
        controller: TermuxSessionController,
        backgroundArgb: Int
    ): TerminalView {
        val view = TerminalView(context, null)
        view.setBackgroundColor(backgroundArgb)
        controller.attach(view)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return view
    }
}

@Composable
private fun SessionEndedOverlay(onNewSession: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0C0C0C)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "[ 会话已结束 ]",
                color = Color(0xFF9E9E9E),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            TermuxTextButton("＋ 新建会话", accent = true, onClick = onNewSession)
        }
    }
}


// ====================================================================
// 快捷键栏（Termux ExtraKeys 原版双排布局）
// ====================================================================

/** 方向键长按连发的重复间隔（ms）——短按单击，按住约 400ms 后连续移动。 */
private const val KEY_REPEAT_INTERVAL_MS = 60L

/** 常用键名 → KeyEvent 键码（对齐官方 PRIMARY_KEY_CODES_FOR_STRINGS）。 */
private val KEY_CODE_MAP: Map<String, Int> = mapOf(
    "ESC" to KeyEvent.KEYCODE_ESCAPE,
    "TAB" to KeyEvent.KEYCODE_TAB,
    "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
    "END" to KeyEvent.KEYCODE_MOVE_END,
    "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
    "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
    "INS" to KeyEvent.KEYCODE_INSERT,
    "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
    "BKSP" to KeyEvent.KEYCODE_DEL,
    "UP" to KeyEvent.KEYCODE_DPAD_UP,
    "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
    "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
    "ENTER" to KeyEvent.KEYCODE_ENTER
)

/** 把一次按键注入 TerminalView（对齐官方 TerminalExtraKeys 的发送协议）。 */
private fun sendKeyToTerminal(view: TerminalView?, key: String, modifiers: ExtraKeysModifierState) {
    val v = view ?: return
    val keyCode = KEY_CODE_MAP[key]
    val ctrl = modifiers.ctrl.isEngaged
    val alt = modifiers.alt.isEngaged
    val shift = modifiers.shift.isEngaged
    val fn = modifiers.fn.isEngaged

    if (keyCode != null && !fn) {
        // v2.24 方向键修复：直接走 handleKeyCode（Termux 官方同源协议）——
        // 由 KeyHandler 查表后把 ESC 序列写入会话。旧版合成 ACTION_UP KeyEvent
        // 再过 onKeyDown，路径长且在某些 ROM/组合下被 isSystem/IME 分支截走，
        // 导致方向键等系统键不起作用。
        var keyMod = 0
        if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (shift) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        v.handleKeyCode(keyCode, keyMod)
    } else if (keyCode != null) {
        // FN 激活时保留官方 kcm 回退语义（fn+键 → kcm fallback，如 fn+↑ = PGUP）
        var meta = 0
        if (ctrl) meta = meta or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        if (alt) meta = meta or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        if (shift) meta = meta or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        if (fn) meta = meta or KeyEvent.META_FUNCTION_ON
        v.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta))
    } else {
        key.codePoints().forEach { cp -> v.inputCodePoint(cp, ctrl, alt) }
    }
    modifiers.consumeOneShot()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TermuxExtraKeysBar(
    controller: TermuxSessionController,
    symbolLayer: Boolean
) {
    val view = controller.terminalView
    val modifiers = TermuxTerminalHolder.modifiers

    // v2.24 透明快捷键栏：去掉整条背板色，键体改为半透明白玻璃片 ——
    // 视觉上与终端融为一体（用户要求“底部键盘不要背景”）
    Column(modifier = Modifier.fillMaxWidth()) {
        // 第一排（对齐官方 Termux）：ESC / — HOME ↑ END PGUP
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ExtraKey("ESC") { key -> sendKeyToTerminal(view, key, modifiers) }
            ExtraKey("/") { key -> sendKeyToTerminal(view, key, modifiers) }
            // 显示长横线、发送连字符（对齐 Termux 默认键位）
            ExtraKey("—") { sendKeyToTerminal(view, "-", modifiers) }
            ExtraKey("HOME") { key -> sendKeyToTerminal(view, key, modifiers) }
            RepeatableKey("↑", key = "UP", view = view, modifiers = modifiers)
            ExtraKey("END") { key -> sendKeyToTerminal(view, key, modifiers) }
            ExtraKey("PGUP") { key -> sendKeyToTerminal(view, key, modifiers) }
        }

        // 第二排（对齐官方 Termux）：⇤(TAB) CTRL ALT ← ↓ → PGDN
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ExtraKey("⇤") { sendKeyToTerminal(view, "TAB", modifiers) }
            ModifierKey("CTRL", modifiers.ctrl)
            ModifierKey("ALT", modifiers.alt)
            RepeatableKey("←", key = "LEFT", view = view, modifiers = modifiers)
            RepeatableKey("↓", key = "DOWN", view = view, modifiers = modifiers)
            RepeatableKey("→", key = "RIGHT", view = view, modifiers = modifiers)
            ExtraKey("PGDN") { key -> sendKeyToTerminal(view, key, modifiers) }
        }

        // 第三排（可选）：符号键层（工具栏 SYM 切换）
        if (symbolLayer) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 3.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val symbols = listOf(
                    "|", "\\", "\"", "'", ":", ";", "$", "@", "#", "%", "&", "*",
                    "!", "?", "+", "_", "=", "<", ">", "(", ")", "[", "]", "{", "}", "`", "^", "~", "/"
                )
                for (s in symbols) {
                    // 滚动行内不用 weight（无限宽度约束下 weight 无意义），改用固定宽度
                    ExtraKey(s, fixedWidth = true) { key -> sendKeyToTerminal(view, key, modifiers) }
                }
            }
        }
    }
}

/**
 * 方向键（可长按连发）：
 * - 短按 = 单击一次（按下立即响应，无点击延迟）；
 * - 按住超过系统长按阈值后自动连续重复（对齐 Termux/系统键盘的
 *   按住移动节奏），松手立即停止。
 */
@Composable
private fun RowScope.RepeatableKey(
    label: String,
    key: String,
    view: TerminalView?,
    modifiers: ExtraKeysModifierState
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 连发循环必须运行在指针事件作用域之外：awaitEachGesture 的接收者
    // AwaitPointerEventScope 不是 CoroutineScope，其内部只允许挂起等待指针
    // 事件（delay/launch 会破坏指针采样线程），因此用组合级协程域承载。
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .background(Color(0x2EFFFFFF), RoundedCornerShape(5.dp))
            // v2.25 方向键二次根修：pointerInput 的手势协程只在 key 变化时
            // 重启。旧版 key 只有键名 —— 首次组合时 view 还是 null
            // （AndroidView 工厂尚未执行），闭包永久捕获 null，此后
            // terminalView 变为非 null 也不会重启，方向键按下永远静默丢弃
            // （这也是 v2.24 修复后“普通键正常、唯独方向键仍无效”的原因：
            // 普通键走 clickable，闭包随重组重建；方向键走 pointerInput）。
            // 把 view 并入 key：视图挂载瞬间手势协程随最新视图重启。
            .pointerInput(key, view) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    haptic.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                    )
                    // 短按：按下立即生效
                    sendKeyToTerminal(view, key, modifiers)
                    // 长按：超过系统长按阈值后进入连续重复，松手即停
                    val repeatJob = scope.launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                        )
                        while (isActive) {
                            delay(KEY_REPEAT_INTERVAL_MS)
                            sendKeyToTerminal(view, key, modifiers)
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            event.changes.forEach { it.consume() }
                        }
                    } finally {
                        repeatJob.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color(0xFFE0E0E0),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** 注意：weight 修饰符仅存在于 Row/Column 作用域，因此本组件声明为 RowScope 扩展，
 *  与官方 Compose 库对 Row 子项的做法一致。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ExtraKey(
    label: String,
    modifierLabel: Boolean = false,
    fixedWidth: Boolean = false,
    onTap: (String) -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .then(if (fixedWidth) Modifier.width(44.dp) else Modifier.weight(1f))
            .height(38.dp)
            .background(
                if (modifierLabel) Color(0x661B5E20) else Color(0x2EFFFFFF),
                RoundedCornerShape(5.dp)
            )
            .combinedClickable(
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onTap(label)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (modifierLabel) Color(0xFF00E676) else Color(0xFFE0E0E0),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** 修饰键按钮：点击 = 单次激活；长按 = 锁定。（RowScope 扩展，见 ExtraKey 注释） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ModifierKey(label: String, modifier: com.linbox.apps.terminal.termux.StickyModifier) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val bg = when {
        modifier.locked -> Color(0x9900600F)
        modifier.active -> Color(0x661B5E20)
        else -> Color(0x2EFFFFFF)
    }
    val fg = when {
        modifier.locked || modifier.active -> Color(0xFF69F0AE)
        else -> Color(0xFFE0E0E0)
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .background(bg, RoundedCornerShape(5.dp))
            .combinedClickable(
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    modifier.toggle()
                },
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    modifier.toggleLock()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (modifier.locked) "$label*" else label,
            color = fg,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (modifier.isEngaged) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ====================================================================
// 小工具
// ====================================================================

@Composable
private fun TermuxTextButton(
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                if (accent) Color(0xFF1B5E20) else Color(0xFF242424),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (accent) Color(0xFF00E676) else Color(0xFFE0E0E0),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun toggleSoftKeyboard(context: android.content.Context, view: TerminalView?) {
    if (view == null) return
    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
    // toggleSoftInput 已弃用（API 33）：改用 WindowInsetsCompat 判断 IME 可见性，
    // 再显式收起/拉起 —— 行为与官方 Termux 的键盘切换一致。
    val imeVisible = ViewCompat.getRootWindowInsets(view)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    if (imeVisible) {
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    } else {
        imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    view.requestFocus()
}

private fun clipboardText(context: android.content.Context): String? {
    return try {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (e: Exception) {
        null
    }
}
