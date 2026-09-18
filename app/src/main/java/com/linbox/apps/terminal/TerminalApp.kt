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

            // ---------- 透明悬浮球（原顶部工具栏功能全部收编于此） ----------
            GlassFab(
                areaPx = areaSize,
                symbolLayerActive = symbolLayer,
                onNewSession = { TermuxTerminalHolder.newSession(context) },
                onKeyboardToggle = { toggleSoftKeyboard(context, viewRef.value) },
                onPaste = {
                    val text = clipboardText(context) ?: return@GlassFab
                    viewRef.value?.mEmulator?.paste(text)
                },
                onFontDecrease = { controller.changeFontSize(-2) },
                onFontIncrease = { controller.changeFontSize(+2) },
                onLayerToggle = { symbolLayer = !symbolLayer },
                onOpenX11 = { ShellController.showX11() },
                onOpenDac = { ShellController.showDac() },
                onOpenSettings = { ShellController.showSettings() }
            )
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
// 透明悬浮球（v2.24）
// ====================================================================
//
// 原顶部工具栏（新会话/键盘/粘贴/字号/符号层/X11/设置）全部收编进
// 一个玻璃质感的可拖动悬浮球：
// - 玻璃拟态：多层半透明白渐变 + 顶部高光 + 细描边 + 终端绿辉光投影，
//   附带缓慢的呼吸缩放，悬浮但不"死板"；
// - 单击展开玻璃胶囊菜单（自球体上/下侧扇出，逐项错峰浮现），
//   菜单打开时点击任意空白处收起；
// - 长按拖动改变位置，松手持久化（归一化坐标，跨分辨率/方向）；
// - "虚拟手柄"项实时反映手柄开关状态（点亮态），单击切换；
//   长按直接打开手柄布局设置悬浮窗。

/** 悬浮球菜单项数据（onClick 放在末位以支持尾随 lambda 写法）。 */
private data class FabItem(
    val label: String,
    val glyph: String,
    val active: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit
)

@Composable
private fun GlassFab(
    areaPx: IntSize,
    symbolLayerActive: Boolean,
    onNewSession: () -> Unit,
    onKeyboardToggle: () -> Unit,
    onPaste: () -> Unit,
    onFontDecrease: () -> Unit,
    onFontIncrease: () -> Unit,
    onLayerToggle: () -> Unit,
    onOpenX11: () -> Unit,
    onOpenDac: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val app = LinBoxApp.get()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 悬浮球位置（归一化 0..1）+ 拖动偏移
    val fabPosX by app.settingsStore.fabPosX.collectAsState(initial = 0.85f)
    val fabPosY by app.settingsStore.fabPosY.collectAsState(initial = 0.85f)
    val gamepadEnabled by app.settingsStore.gamepadEnabled.collectAsState(initial = false)

    var expanded by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }

    val fabSizePx = with(density) { 54.dp.toPx() }
    val itemHPx = with(density) { 38.dp.toPx() }
    val gapPx = with(density) { 6.dp.toPx() }
    val menuWPx = with(density) { 118.dp.toPx() }
    val maxX = (areaPx.width - fabSizePx).coerceAtLeast(0f)
    val maxY = (areaPx.height - fabSizePx).coerceAtLeast(0f)

    val x = (maxX * fabPosX + dragOffset.x).coerceIn(0f, maxX)
    val y = (maxY * fabPosY + dragOffset.y).coerceIn(0f, maxY)
    // 球体在下半屏 → 菜单向上展开；上半屏 → 向下展开
    val expandUp = y > maxY * 0.45f

    fun persistPos() {
        if (maxX <= 0f || maxY <= 0f) return
        val cx = (maxX * fabPosX + dragOffset.x).coerceIn(0f, maxX)
        val cy = (maxY * fabPosY + dragOffset.y).coerceIn(0f, maxY)
        dragOffset = Offset.Zero
        val nx = (cx / maxX).coerceIn(0f, 1f)
        val ny = (cy / maxY).coerceIn(0f, 1f)
        scope.launch { app.settingsStore.setFabPos(nx, ny) }
    }

    val items = remember(
        symbolLayerActive, gamepadEnabled, onNewSession, onKeyboardToggle,
        onPaste, onFontDecrease, onFontIncrease, onLayerToggle, onOpenX11, onOpenDac, onOpenSettings
    ) {
        listOf(
            FabItem("新会话", "＋") { onNewSession() },
            FabItem("键盘", "⌨") { onKeyboardToggle() },
            FabItem("粘贴", "贴") { onPaste() },
            FabItem("字号－", "A-") { onFontDecrease() },
            FabItem("字号＋", "A+") { onFontIncrease() },
            FabItem("符号层", "SY", active = symbolLayerActive) { onLayerToggle() },
            FabItem("虚拟手柄", "柄", active = gamepadEnabled,
                onClick = { scope.launch { app.settingsStore.setGamepadEnabled(!gamepadEnabled) } },
                onLongClick = { GamepadController.settingsOpen = true }),
            // v2.26：原虚拟手柄右上角迷你工具条 ⚙ 设置收编进悬浮球 ——
            // GamepadSettingsWindow 在 LinBoxShell 顶层组合，任意界面单击即弹
            FabItem("手柄设置", "🎮") {
                GamepadController.releaseAllKeys()
                GamepadController.settingsOpen = true
            },
            FabItem("X11 桌面", "X") { onOpenX11() },
            // v1.18：全屏 DAC 显示器页（winedac.drv 画面直出安卓屏，不依赖 X11；
            // wine wrapper 提示的"点开「DAC 显示器」应用"即此入口）
            FabItem("DAC 显示器", "D") { onOpenDac() },
            FabItem("设置", "⚙") { onOpenSettings() }
        )
    }

    // 呼吸动画（轻微缩放，赋予"活"的质感）
    val pulse by rememberInfiniteTransition(label = "fabPulse").animateFloat(
        initialValue = 0.95f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "fabPulseScale"
    )
    // 展开进度 0..1
    val expand by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "fabExpand"
    )
    val menuHPx = items.size * (itemHPx + gapPx)

    Box(Modifier.fillMaxSize()) {
        // 展开时的半透明遮罩：点击任意处收起（折叠时不存在，不拦截终端触摸）
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { expanded = false } }
            )
        }

        // ===== 玻璃胶囊菜单 =====
        Column(
            modifier = Modifier
                .offset {
                    val menuLeft = (x + fabSizePx / 2f - menuWPx / 2f)
                        .coerceIn(0f, (areaPx.width - menuWPx).coerceAtLeast(0f))
                    val menuTop = if (expandUp) y - menuHPx * expand - with(density) { 10.dp.toPx() }
                    else y + fabSizePx + with(density) { 10.dp.toPx() }
                    IntOffset(menuLeft.roundToInt(), menuTop.roundToInt())
                }
                .width(118.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEachIndexed { i, item ->
                // 错峰浮现：后一项比前一项晚 ~6% 进度
                val progress = ((expand * 1.4f) - i * 0.06f).coerceIn(0f, 1f)
                FabMenuItem(
                    item = item,
                    progress = progress
                )
            }
        }

        // ===== 玻璃球本体 =====
        val ballBorder = Brush.linearGradient(
            listOf(
                if (expanded) Color(0xB369F0AE) else Color(0x8CFFFFFF),
                if (expanded) Color(0x3300E676) else Color(0x1FFFFFFF)
            )
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(54.dp)
                .graphicsLayer {
                    if (!dragging) {
                        scaleX = pulse
                        scaleY = pulse
                    }
                }
                .shadow(
                    elevation = if (expanded) 18.dp else 10.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color(0xFF00E676),
                    spotColor = Color(0xFF00E676)
                )
                .clip(CircleShape)
                .background(
                    // 玻璃球面：左上高光 → 右下近透明的斜向白渐变
                    Brush.linearGradient(
                        listOf(Color(0x66FFFFFF), Color(0x26FFFFFF), Color(0x0FFFFFFF)),
                        start = Offset.Zero,
                        end = Offset.Infinite
                    )
                )
                .border(1.2.dp, ballBorder, CircleShape)
                // 终端绿内辉光
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x3300E676), Color(0x0000E676))
                    )
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount
                        },
                        onDragEnd = { dragging = false; persistPos() },
                        onDragCancel = { dragging = false; persistPos() }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { expanded = !expanded })
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "❯_",
                color = Color(0xFF69F0AE),
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 玻璃胶囊菜单项：progress 0..1 控制浮现（透明度 + 缩放），未浮现完成前不可点。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FabMenuItem(item: FabItem, progress: Float) {
    val scale = 0.7f + 0.3f * progress
    val clickable = if (progress > 0.2f) {
        if (item.onLongClick != null) {
            Modifier.combinedClickable(
                onClick = { item.onClick() },
                onLongClick = { item.onLongClick!!() }
            )
        } else Modifier.clickable(onClick = { item.onClick() })
    } else Modifier
    Row(
        modifier = Modifier
            .graphicsLayer {
                alpha = progress
                scaleX = scale
                scaleY = scale
            }
            .height(38.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(
                if (item.active) Brush.linearGradient(listOf(Color(0x9600C853), Color(0x5C00E676)))
                else Brush.linearGradient(listOf(Color(0x3DFFFFFF), Color(0x1AFFFFFF)))
            )
            .border(
                0.8.dp,
                if (item.active) Brush.linearGradient(listOf(Color(0xAA69F0AE), Color(0x3300E676)))
                else Brush.linearGradient(listOf(Color(0x59FFFFFF), Color(0x1FFFFFFF))),
                RoundedCornerShape(19.dp)
            )
            .then(clickable)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            item.glyph,
            color = if (item.active) Color(0xFFB9F6CA) else Color(0xFF69F0AE),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            item.label,
            color = Color.White.copy(alpha = 0.93f),
            fontSize = 12.sp
        )
        if (item.active) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFB9F6CA))
            )
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
