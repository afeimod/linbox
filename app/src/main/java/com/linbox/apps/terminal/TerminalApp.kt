package com.linbox.apps.terminal

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.linbox.termux.view.TerminalView
import com.linbox.core.shell.ShellController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端主页（真实 Termux 移植版）—— LinBox 的主屏：
 *
 * - App 打开即全屏终端，首次自动安装官方 bootstrap（内置离线 aarch64
 *   归档，安装时同长度重写 com.termux → com.linbox 路径前缀）；
 * - 会话为真实 login shell（bash），pkg/apt 可用；
 * - 视图为 termux 官方 TerminalView（Apache-2.0 移植）；
 * - 快捷键栏为 Termux 原版双排布局（ESC/-/HOME/↑/END/PGUP +
 *   ⇤/CTRL/ALT/←/↓/→/PGDN），方向键短按单击、长按连续重复；
 * - 双指捏合缩放字号（TerminalView 内置 ScaleGestureDetector）;
 * - 终端背景可自定义：设置→终端背景（纯色 / 自定义图片）;
 * - 工具栏一键跳转 X11 图形界面 / 设置页；终端里执行 `linbox-x11`
 *   也会自动跳转（见 X11WindowController 广播）；
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
        return controller!!
    }
}

// ------------------------------------------------------------------
// 终端背景（设置→终端背景：纯色 / 自定义图片）
// ------------------------------------------------------------------

private const val BACKGROUND_IMAGE_FILE = "terminal_bg.jpg"

/** 终端背景解析结果：纯色或图片（图片模式下 TerminalView 透明）。 */
private class TerminalBackgroundState(val color: Color, val bitmap: android.graphics.Bitmap?) {
    /** TerminalView 背景色：图片模式透明（露出下层图片 + 暗化叠加）。 */
    val viewBackgroundArgb: Int =
        if (bitmap != null) android.graphics.Color.TRANSPARENT else color.toArgb()
}

@Composable
private fun rememberTerminalBackground(): TerminalBackgroundState {
    val app = LinBoxApp.get()
    val context = LocalContext.current
    val colorKey by app.settingsStore.terminalBgColor.collectAsState(initial = "default")
    val imageEnabled by app.settingsStore.terminalBgImage.collectAsState(initial = false)
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
    return TerminalBackgroundState(color, bitmap)
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

    // 终端背景（纯色 / 自定义图片，图片时终端视图透明）
    val bg = rememberTerminalBackground()

    Column(
        modifier = Modifier
            .fillMaxSize()
            // v2.23 IME 遮挡修复：沉浸式（setDecorFitsSystemWindows(false)）下
            // API 30+ 系统忽略 adjustResize，键盘弹出时必须靠 ime() inset 主动
            // 让位——整列（工具栏/终端/快捷键栏）抬到输入法上方；快捷键栏无
            // 输入法时贴屏幕底边、有输入法时贴输入法上沿（对齐官方 Termux）。
            // API 30 以下 adjustResize 生效且 ime() inset 报 0，两者不叠加。
            .imePadding()
            .background(if (bg.bitmap != null) Color.Black else bg.color)
    ) {
        // ---------- 工具栏 ----------
        TerminalToolbar(
            onNewSession = {
                TermuxTerminalHolder.newSession(context)
            },
            onKeyboardToggle = { toggleSoftKeyboard(context, viewRef.value) },
            onPaste = {
                val text = clipboardText(context) ?: return@TerminalToolbar
                viewRef.value?.mEmulator?.paste(text)
            },
            onFontDecrease = { controller.changeFontSize(-2) },
            onFontIncrease = { controller.changeFontSize(+2) },
            onLayerToggle = { symbolLayer = !symbolLayer },
            symbolLayerActive = symbolLayer,
            onOpenX11 = { ShellController.showX11() },
            onOpenSettings = { ShellController.showSettings() }
        )

        // ---------- 终端视图（图片背景时透明 + 底层图片与暗化叠加） ----------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (bg.bitmap != null) {
                Image(
                    bitmap = bg.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // 暗化叠加：保证终端文字在任意图片上可读
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)))
            }
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
        }

        // ---------- 快捷键栏（两排） ----------
        TermuxExtraKeysBar(
            controller = controller,
            symbolLayer = symbolLayer
        )
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
// 工具栏
// ====================================================================

@Composable
private fun TerminalToolbar(
    onNewSession: () -> Unit,
    onKeyboardToggle: () -> Unit,
    onPaste: () -> Unit,
    onFontDecrease: () -> Unit,
    onFontIncrease: () -> Unit,
    onLayerToggle: () -> Unit,
    symbolLayerActive: Boolean,
    onOpenX11: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "TERMUX",
            color = Color(0xFF00E676),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, end = 2.dp)
        )
        ToolbarButton("新会话", onClick = onNewSession)
        ToolbarButton("键盘", onClick = onKeyboardToggle)
        ToolbarButton("粘贴", onClick = onPaste)
        Spacer(Modifier.weight(1f))
        ToolbarButton("A－", onClick = onFontDecrease)
        ToolbarButton("A＋", onClick = onFontIncrease)
        ToolbarButton(
            if (symbolLayerActive) "SYM•" else "SYM",
            onClick = onLayerToggle,
            highlighted = symbolLayerActive
        )
        ToolbarButton("X11", onClick = onOpenX11, highlighted = true)
        ToolbarButton("设置", onClick = onOpenSettings)
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    Box(
        modifier = Modifier
            .background(
                if (highlighted) Color(0xFF1B5E20) else Color(0xFF212121),
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (highlighted) Color(0xFF00E676) else Color(0xFFBDBDBD),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
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

    if (keyCode != null) {
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616))
    ) {
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
    Box(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .background(Color(0xFF242424), RoundedCornerShape(5.dp))
            .pointerInput(key) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    haptic.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                    )
                    // 短按：按下立即生效
                    sendKeyToTerminal(view, key, modifiers)
                    // 长按：超过系统长按阈值后进入连续重复，松手即停
                    val repeatJob = launch {
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
                if (modifierLabel) Color(0xFF1B5E20) else Color(0xFF242424),
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
        modifier.locked -> Color(0xFF00600F)
        modifier.active -> Color(0xFF1B5E20)
        else -> Color(0xFF242424)
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
