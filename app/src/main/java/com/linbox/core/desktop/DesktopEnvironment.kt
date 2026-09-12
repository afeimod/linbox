package com.linbox.core.desktop

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.linbox.LinBoxApp
import com.linbox.core.input.MouseController
import com.linbox.core.input.MouseCursorOverlay
import com.linbox.core.input.TrackpadGate
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.input.VirtualKeyboardController
import com.linbox.core.input.VirtualKeyboardOverlay
import com.linbox.core.input.gamepad.GamepadOverlay
import com.linbox.core.input.gamepad.GamepadSettingsWindow
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.theme.WinTheme
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.WindowHost
import com.linbox.core.window.WindowManager
import com.linbox.data.model.DesktopItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 桌面环境：壁纸 + 桌面图标网格 + 任务栏 + 开始菜单 + 浮动窗口。
 *
 * 视觉重构后：
 * - 任务栏浮在底部（留 4dp 空隙）
 * - 开始菜单居中浮在任务栏上方
 * - 浮动窗口层与任务栏/开始菜单层分离
 *
 * 这是应用启动后唯一的顶层 Composable。
 */
@Composable
fun DesktopEnvironment(
    theme: WinTheme,
    customWallpaperUri: String?,
    soundEnabled: Boolean
) {
    val context = LocalContext.current
    val wm = remember { WindowManager.get() }
    val app = LinBoxApp.get()
    val density = LocalDensity.current
    val composeScope = rememberCoroutineScope()

    // 显示设置
    val taskbarAutohide by app.settingsStore.taskbarAutohide.collectAsState(initial = false)
    val showSeconds by app.settingsStore.showSeconds.collectAsState(initial = false)
    val timeFormat24h by app.settingsStore.timeFormat24h.collectAsState(initial = true)
    // 任务栏高度（v2.9 可调节）：>=36 时用自定义高度，否则跟随主题默认
    val taskbarHeightPref by app.settingsStore.taskbarHeight.collectAsState(initial = 0f)

    // v2.13 虚拟鼠标：指针显示开关 / 右键手势 / 图标单击双击
    val mouseCursorEnabled by app.settingsStore.mouseCursorEnabled.collectAsState(initial = true)
    val mouseRightClick by app.settingsStore.mouseRightClick.collectAsState(initial = "twofinger")
    val mouseClickMode by app.settingsStore.mouseClickMode.collectAsState(initial = "single")
    // v2.18 指针移动方式：touch 跟随手指 / trackpad 触控板
    val mouseControlMode by app.settingsStore.mouseControlMode.collectAsState(initial = "touch")

    // 任务栏自动隐藏：只在与阈值交界处翻转，避免每次指针移动都触发整体重组
    var taskbarShown by remember { mutableStateOf(true) }
    var bottomThresholdPx by remember { mutableStateOf(0f) }

    // v2.14：任务栏图标对齐（居中 Win11 / 靠左经典）
    val taskbarCentered by app.settingsStore.taskbarCentered.collectAsState(initial = true)

    // ===== v2.17 锁屏设置：开关 / 锁屏壁纸 / PIN / 自动锁屏定时 =====
    val lockEnabled by app.settingsStore.lockEnabled.collectAsState(initial = true)
    val lockWallpaper by app.settingsStore.lockWallpaper.collectAsState(initial = null)
    val lockPinHash by app.settingsStore.lockPinHash.collectAsState(initial = "")
    val autoLockMinutes by app.settingsStore.autoLockMinutes.collectAsState(initial = 0)

    // 监听 WindowManager 变化：用于检测是否有窗口进入真全屏（F11），
    // 真全屏时隐藏任务栏 + 让浮动窗口层占满整屏
    var wmRevision by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { wm.observe { wmRevision++ } }
    val anyTrueFullscreen = remember(wmRevision) { wm.anyTrueFullscreen() }

    // 启动音效
    LaunchedEffect(theme.variant) {
        if (soundEnabled) {
            theme.startupSoundAsset?.let { playStartupSound(context, it) }
        }
    }

    // ===== v2.17 自动锁屏定时：空闲达到设定分钟数且锁屏开启时锁定 =====
    LaunchedEffect(lockEnabled, autoLockMinutes) {
        if (!lockEnabled || autoLockMinutes <= 0) return@LaunchedEffect
        val timeoutMs = autoLockMinutes * 60_000L
        while (true) {
            if (!LockController.locked && AutoLockController.idleMs() >= timeoutMs) {
                LockController.lock()
            }
            delay(5_000)
        }
    }

    // ===== v2.17 开机自启动应用：读取 AUTOSTART_APPS 一次性打开 =====
    // AutoStartRunner.launched 防止 Activity 重建时重复拉起
    LaunchedEffect(Unit) {
        val ids = runCatching { app.settingsStore.autostartApps.first() }.getOrDefault("")
        if (ids.isNotBlank() && !AutoStartRunner.launched) {
            AutoStartRunner.launched = true
            ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { id ->
                AppRegistry.get(id)?.let { def ->
                    wm.open(
                        appId = def.id,
                        title = def.displayName,
                        launchMode = def.launchMode,
                        initialWidth = def.defaultWidth.value.toInt(),
                        initialHeight = def.defaultHeight.value.toInt()
                    )
                }
            }
        }
    }

    // 上下文菜单状态
    var contextMenu by remember { mutableStateOf<DesktopContextMenuData?>(null) }
    var startMenuOpen by remember { mutableStateOf(false) }
    // 系统托盘弹窗：calendar / quickSettings / null
    var trayPopup by remember { mutableStateOf<TrayPopup?>(null) }

    // v2.13 虚拟键盘：Win 徽标键 = 切换开始菜单（必须在 startMenuOpen 声明之后）
    SideEffect {
        VirtualKeyboardController.onWinKey = { startMenuOpen = !startMenuOpen }
    }

    // v2.11 右键菜单配套状态：
    // - refreshTick："刷新"计数器，key(refreshTick) 重建图标网格（重载图标位图）；
    // - desktopSort：排序模式（与设置中心共用 DataStore）；
    // - iconBounds：每个图标的屏幕边界注册表，双指右键时命中检测，
    //   命中图标 → 图标菜单（打开/重命名/删除/属性），空白处 → 桌面菜单。
    var refreshTick by remember { mutableStateOf(0) }
    val desktopSort by app.settingsStore.desktopSort.collectAsState(initial = "default")
    val iconBounds = remember { mutableStateMapOf<String, Pair<DesktopItem, Rect>>() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 仅在开启自动隐藏时监听指针位置（不消费事件），用于底部边缘呼出任务栏
            // v2.20：trackpad 模式下真实手指事件被 View 层路由器拦截（不再进入
            // Compose 管线），事件泵会永远挂起 —— 改为直接订阅指针位置流；
            // touch 模式保持原有事件泵（跟随手指位置）
            .pointerInput(taskbarAutohide, mouseControlMode) {
                if (!taskbarAutohide) return@pointerInput
                if (mouseControlMode == "trackpad") {
                    snapshotFlow { MouseController.position.y }.collect { y ->
                        val visible = y >= bottomThresholdPx
                        if (visible != taskbarShown) taskbarShown = visible
                    }
                } else {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val y = event.changes.firstOrNull()?.position?.y ?: continue
                            val visible = y >= bottomThresholdPx
                            if (visible != taskbarShown) taskbarShown = visible
                        }
                    }
                }
            }
            // v2.13 虚拟鼠标指针跟踪（touch 模式）：纯观察者（从不消费事件），
            // 指针贴手指移动，快速轻点时触发点击涟漪。
            // v2.20：trackpad 模式下真实手指被 View 层 TrackpadRouter 拦截
            // （不再进入 Compose 管线），本观察者收不到事件；指针由路由器
            // 以相对增量驱动。
            .pointerInput(mouseCursorEnabled, mouseControlMode) {
                if (mouseCursorEnabled && mouseControlMode != "trackpad") {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedChange = event.changes.lastOrNull { it.pressed }
                            val change = pressedChange ?: event.changes.lastOrNull()
                            if (change != null) {
                                MouseController.update(change.position.x, change.position.y)
                            }
                            MouseController.press(event.changes.any { it.pressed })
                        }
                    }
                }
            }
            // ===== v2.17 自动锁屏交互监听：纯观察者（从不消费事件），
            // 任何指针事件都刷新 AutoLockController 的最后交互时间 =====
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.isNotEmpty()) {
                            AutoLockController.onInteraction()
                        }
                    }
                }
            }
    ) {
        val fullHeight = maxHeight
        // v2.9：任务栏高度可调（个性化设置 36..80dp），未设置时跟随主题
        val taskbarHeight = if (taskbarHeightPref >= 36f) taskbarHeightPref.dp else theme.taskbarHeight
        // v2.15：Win11 悬浮 Dock 上下各留 4dp 空隙；其余时代任务栏通栏贴底（只减去任务栏高度）
        // 真全屏（F11）时浮动窗口层占满整屏，隐藏任务栏
        val taskbarFloating = theme.taskbarStyle == com.linbox.core.theme.TaskbarStyle.MICA_11
        val workAreaHeight = if (anyTrueFullscreen) fullHeight
        else if (taskbarFloating) fullHeight - taskbarHeight - 8.dp
        else fullHeight - taskbarHeight

        // 计算底部边缘呼出阈值（屏幕高度 - 28dp），供指针监听协程读取；
        // 同时初始化虚拟鼠标指针位置（首次进入桌面时置于屏幕中部偏上）
        SideEffect {
            bottomThresholdPx = with(density) { fullHeight.toPx() } - with(density) { 28.dp.toPx() }
            // v2.19：触控板模式强制需要指针（指针就是鼠标本体）
            if (mouseCursorEnabled || mouseControlMode == "trackpad") {
                MouseController.initialize(
                    with(density) { maxWidth.toPx() } * 0.4f,
                    with(density) { fullHeight.toPx() } * 0.32f
                )
            }
        }

        // 任务栏可见性：真全屏时彻底隐藏；否则按自动隐藏策略
        val taskbarVisible = !anyTrueFullscreen && (!taskbarAutohide || taskbarShown || startMenuOpen)
        val taskbarOffsetY = if (taskbarVisible) 0 else with(density) { taskbarHeight.toPx() }.toInt()

        // v2.19.4/v2.20：桌面右键菜单回调（双指轻点 / 长按右键命中）。
        // 本层位于根 (0,0)，局部坐标与图标上报的 boundsInRoot 根坐标一致，
        // 可直接命中检测。触控板模式下由 View 层 TrackpadRouter 回调
        // （onContextMenu）；touch 模式下仍由 desktopGestures 触发。
        // v2.19.5：remember 化 —— 捕获的 startMenuOpen/contextMenu/iconBounds
        // 均为 remember 的 State 对象，写入路径稳定；lambda 实例不再随重组
        // 变化（旧版每次重组产生新实例，作为 pointerInput key 曾导致触控板
        // 门禁协程被反复取消重建）。
        val openContextMenu: (Offset) -> Unit = remember {
            { offset ->
                val hit = iconBounds.entries.firstOrNull { it.value.second.contains(offset) }
                startMenuOpen = false
                contextMenu = DesktopContextMenuData(
                    x = offset.x,
                    y = offset.y,
                    iconItem = hit?.value?.first
                )
            }
        }

        // v2.20：View 层触控板路由器接线（原 Compose 门禁职责上移）。
        // - onContextMenu：双指轻点 / 长按右键 → openContextMenu（触控板模式）
        // - onUserInteraction：被拦截的真实手指事件也要刷新自动锁屏空闲计时
        //   （原 AutoLock 观察者在 trackpad 模式下收不到真实事件）
        // - enabled / longPressRightClick / density：随设置同步
        DisposableEffect(openContextMenu) {
            TrackpadRouter.onContextMenu = openContextMenu
            TrackpadRouter.onUserInteraction = { AutoLockController.onInteraction() }
            onDispose {
                TrackpadRouter.onContextMenu = null
                TrackpadRouter.onUserInteraction = null
            }
        }
        SideEffect {
            val newEnabled = mouseControlMode == "trackpad"
            // 退出触控板模式时撤销进行中的手势（释放未完成的注入拖拽流）
            if (TrackpadRouter.enabled && !newEnabled) TrackpadRouter.onDisabled()
            TrackpadRouter.enabled = newEnabled
            TrackpadRouter.longPressRightClick = mouseRightClick == "longpress"
            TrackpadRouter.density = density.density
        }

        // ===== 触控板容器（祖先层）：包裹壁纸～右键菜单 =====
        // v2.20：手势仲裁已上移到 View 层 TrackpadRouter（MainActivity.
        // dispatchTouchEvent 入口）——真实手指不再进入 Compose 管线，本层
        // 退化为纯容器，仅保留既有包裹结构与签名。onTwoFingerTap 参数为
        // 签名兼容保留（回调已直连 TrackpadRouter.onContextMenu）。
        TrackpadGate(
            modifier = Modifier.fillMaxSize(),
            onTwoFingerTap = openContextMenu
        ) {

        // ===== 1. 壁纸层 =====
        WallpaperLayer(
            themeWallpaper = theme.wallpaperAsset,
            customWallpaperUri = customWallpaperUri,
            modifier = Modifier.fillMaxSize()
        )

        // ===== 2. 桌面图标层（占据任务栏上方） =====
        // v2.11 手势：双指轻点 = 右键菜单（命中图标 → 图标菜单，否则 → 桌面菜单）；单指轻点 = 关闭菜单
        // v2.13：右键手势可设（双指轻点 / 长按），图标打开方式可设（单击 / 双击）
        // v2.19.4：openContextMenu 上移至 TrackpadGate 之前（触控板双指轻点直连回调）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(workAreaHeight)
                .desktopGestures(
                    onTap = {
                        // 点击空白处关闭开始菜单和右键菜单
                        startMenuOpen = false
                        contextMenu = null
                    },
                    onTwoFingerTap = openContextMenu,
                    enableTwoFinger = mouseRightClick == "twofinger",
                    // v2.19：触控板模式长按 = 拖拽（门禁层），不再触发右键
                    enableLongPress = mouseRightClick == "longpress" &&
                        mouseControlMode != "trackpad",
                    onLongPress = openContextMenu,
                    // v2.19：触控板模式下本层只处理注入的合成流（真实手指
                    // 已被门禁拦截），注入的单指轻点/双指轻点经此还原为
                    // 桌面点击 / 右键菜单
                    trackpadMode = mouseControlMode == "trackpad"
                )
        ) {
            // key(refreshTick)：右键菜单"刷新"时重建网格，重新加载图标位图
            key(refreshTick) {
                DesktopIconGrid(
                    sortMode = desktopSort,
                    iconBounds = iconBounds,
                    clickMode = mouseClickMode
                )
            }
        }

        // ===== 3. 浮动窗口层（覆盖在桌面图标之上，任务栏之下） =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(workAreaHeight)
        ) {
            WindowHost()
        }

        // ===== 4. 开始菜单层（位于任务栏上方） =====
        // v2.15.2：95/XP/7/10 时代开始菜单贴左下角（紧挨左下角开始按钮）；
        // 仅 Win11（MICA_11 悬浮 Dock）保持居中弹出。
        if (startMenuOpen) {
            // 背景遮罩：点击关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { startMenuOpen = false })
                    }
            )
            val isModern11 = theme.taskbarStyle == com.linbox.core.theme.TaskbarStyle.MICA_11
            StartMenu(
                theme = theme,
                onDismiss = { startMenuOpen = false },
                modifier = if (isModern11) {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = taskbarHeight + 12.dp)
                } else {
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 4.dp, bottom = taskbarHeight + 4.dp)
                }
            )
        }

        // ===== 5. 任务栏（底部浮起） =====
        Taskbar(
            theme = theme,
            taskbarHeight = taskbarHeight,
            startMenuOpen = startMenuOpen,
            onStartClick = { startMenuOpen = !startMenuOpen },
            onOpenCalendar = { trayPopup = TrayPopup.CALENDAR },
            onOpenQuickSettings = { trayPopup = TrayPopup.QUICK_SETTINGS },
            onOpenClockStyle = { trayPopup = TrayPopup.CLOCK_STYLE },
            showSeconds = showSeconds,
            timeFormat24h = timeFormat24h,
            taskbarCentered = taskbarCentered,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(taskbarHeight)
                .offset { IntOffset(0, taskbarOffsetY) }
        )

        // ===== 5.5. 系统托盘弹窗（Calendar / QuickSettings / ClockStyle）=====
        // v2.11：托盘固定在任务栏最右侧，弹窗相应从右下角弹出；
        // heightIn 限制弹窗总高度不超过（屏幕高度 - 任务栏），小屏幕上不再溢出屏幕顶部。
        val maxPopupHeight = (fullHeight - taskbarHeight - 16.dp).coerceAtLeast(240.dp)
        trayPopup?.let { popup ->
            // 背景遮罩：点击关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { trayPopup = null })
                    }
            )
            when (popup) {
                TrayPopup.CALENDAR -> CalendarFlyout(
                    theme = theme,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = taskbarHeight + 8.dp)
                        .heightIn(max = maxPopupHeight)
                )
                TrayPopup.QUICK_SETTINGS -> QuickSettingsPanel(
                    theme = theme,
                    onOpenSettings = {
                        trayPopup = null
                        wm.open(
                            appId = "settings",
                            title = "设置",
                            launchMode = com.linbox.core.window.LaunchMode.FLOATING,
                            initialWidth = 720,
                            initialHeight = 520
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = taskbarHeight + 8.dp)
                        .heightIn(max = maxPopupHeight)
                )
                TrayPopup.CLOCK_STYLE -> TrayClockSettingsFlyout(
                    theme = theme,
                    onDismiss = { trayPopup = null },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = taskbarHeight + 8.dp)
                        .heightIn(max = maxPopupHeight)
                )
            }
        }

        // ===== 6. 右键上下文菜单 =====
        contextMenu?.let { data ->
            DesktopContextMenu(
                data = data,
                onDismiss = { contextMenu = null },
                onRefresh = { refreshTick++ },
                modifier = Modifier
                    .align(Alignment.TopStart)
            )
        }

        } // end TrackpadGate

        // ===== 7. 虚拟键盘层（v2.13：全键盘，可拖动） =====
        VirtualKeyboardOverlay()

        // ===== 7.5. 虚拟游戏手柄层（v2.15：摇杆/十字键/按钮，悬浮设置窗） =====
        GamepadOverlay()
        GamepadSettingsWindow()

        // ===== 8. 虚拟鼠标指针层（v2.13：Windows 风格指针 + 点击涟漪，最顶层） =====
        MouseCursorOverlay()

        // ===== 9. 锁屏层（v2.14：设置→个性化→锁屏界面 / 开始菜单电源→锁定） =====
        // 放在键盘/鼠标层之上，拦截一切交互，只允许上滑或点击解锁
        // v2.17：独立锁屏壁纸（图片/视频） + PIN 密码验证 + 自动锁屏（见上方定时协程）
        if (LockController.locked) {
            LockScreenLayer(
                theme = theme,
                lockWallpaperUri = lockWallpaper,
                desktopWallpaperUri = customWallpaperUri,
                timeFormat24h = timeFormat24h,
                pinHash = lockPinHash.ifBlank { null },
                onClearPin = {
                    // 忘记密码兜底：应用级作用域写库（窗口/组合销毁不影响）
                    composeScope.launch { app.settingsStore.setLockPinHash("") }
                },
                onUnlock = { LockController.unlock() }
            )
        }
    }
}

/**
 * v2.17：开机自启动防重复标记（Activity 重建时不重复拉起应用）。
 */
private object AutoStartRunner {
    @Volatile
    var launched = false
}

/**
 * 播放启动音效。
 * 因为 assets/sounds 中的 mp3 文件不一定存在，需 try/catch。
 */
private fun playStartupSound(context: Context, assetPath: String) {
    try {
        val mp = MediaPlayer()
        // openFd 失败时抛异常（返回值非空），由下方 catch 统一处理
        val afd = context.assets.openFd(assetPath)
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        mp.setOnPreparedListener { it.start() }
        mp.setOnCompletionListener { it.release() }
        mp.setOnErrorListener { _, _, _ -> true }
        mp.prepareAsync()
    } catch (_: IOException) {
        // 资源缺失时静默跳过
    } catch (_: Exception) {
        // 其他异常同样跳过
    }
}

/**
 * 系统托盘弹窗类型：
 * - CALENDAR：点击时钟后显示的 Win11 风格日历
 * - QUICK_SETTINGS：点击 wifi/电池/音量后显示的 Win11 风格快速设置面板
 * - CLOCK_STYLE：长按时钟弹出的托盘时钟样式设置（v2.10）
 */
private enum class TrayPopup { CALENDAR, QUICK_SETTINGS, CLOCK_STYLE }

/**
 * 桌面手势（v2.10，v2.13 扩展）：
 * - 单指轻点：关闭开始菜单/右键菜单（不消费事件，不影响子组件手势）
 * - 双指轻点：两指几乎同时按下、位移小于 slop、持续 < 800ms，
 *   在双指中点触发桌面右键菜单（右键手势设为"双指"时启用）
 * - 长按（v2.13）：单指按下不动超过系统长按阈值（右键手势设为"长按"时启用），
 *   在按压位置触发右键菜单
 *
 * 实现为纯观察者（从不消费事件），与图标双击启动、图标区横向滚动完全兼容；
 * 双指即使其中一指落在图标上，本层也能收到全部两根指针的事件（祖先命中路径）。
 */
private fun Modifier.desktopGestures(
    onTap: (Offset) -> Unit,
    onTwoFingerTap: (Offset) -> Unit,
    enableTwoFinger: Boolean,
    enableLongPress: Boolean,
    onLongPress: (Offset) -> Unit,
    trackpadMode: Boolean = false
): Modifier = pointerInput(enableTwoFinger, enableLongPress, trackpadMode) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        // v2.19 触控板模式：真实手指流已被 TrackpadGate 消费（本层收到的
        // 是“已消费”事件）—— 跳过，只处理触控板注入的合成流。
        // 判定改用 type == PointerType.Touch：Compose 的 MotionEventAdapter 会把
        // 注入的原始 id=99 重映射为自增小数字，id<99 不可靠（详见 TrackpadLayer
        // 的 INJECTED_POINTER_ID 注释）；注入流 toolType=MOUSE → type=Mouse。
        if (trackpadMode && first.type == PointerType.Touch) {
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.none { it.pressed }) break
            }
            return@awaitEachGesture
        }
        val startTime = first.uptimeMillis
        val startPositions = mutableMapOf(first.id to first.position)
        var maxPointers = 1
        var moved = false
        var expired = false
        var longPressFired = false
        var twoFingerCentroid: Offset? = null
        // 注意：Compose ViewConfiguration 没有 pointerSlop 公开属性（v2.10.0 编译错误根因）。
        // PointerInputScope 本身实现 Density，直接用 dp 容差换算像素最稳妥；
        // 双指轻点手势两指天然抖动大，18dp 比系统 touchSlop(约8dp) 更宽容。
        val slop = 18.dp.toPx()

        while (true) {
            val event = awaitPointerEvent()
            // 记录新按下的手指；检测任一手指位移超过 slop
            event.changes.forEach { c ->
                if (c.pressed) {
                    val start = startPositions[c.id]
                    if (start == null) {
                        startPositions[c.id] = c.position
                    } else if ((c.position - start).getDistance() > slop) {
                        moved = true
                    }
                }
            }
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount > maxPointers) maxPointers = pressedCount
            // 记录双指中点（首次达到 2 指时）
            if (pressedCount == 2 && twoFingerCentroid == null) {
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    twoFingerCentroid = Offset(
                        (pressed[0].position.x + pressed[1].position.x) / 2f,
                        (pressed[0].position.y + pressed[1].position.y) / 2f
                    )
                }
            }
            val elapsed = event.changes.maxOf { it.uptimeMillis } - startTime
            // 长按右键（v2.13）：单指、未移动、未过期、超过系统长按阈值，只触发一次
            if (enableLongPress && !longPressFired && !moved && !expired &&
                maxPointers == 1 && elapsed > viewConfiguration.longPressTimeoutMillis
            ) {
                longPressFired = true
                onLongPress(first.position)
            }
            if (pressedCount == 0) {
                // 全部手指抬起：判定轻点 / 双指轻点
                val duration = event.changes.maxOf { it.uptimeMillis } - startTime
                if (!moved && !expired && !longPressFired) {
                    if (enableTwoFinger && maxPointers == 2 && twoFingerCentroid != null &&
                        duration < viewConfiguration.longPressTimeoutMillis * 2
                    ) {
                        onTwoFingerTap(twoFingerCentroid)
                    } else if (maxPointers == 1 &&
                        duration < viewConfiguration.doubleTapTimeoutMillis
                    ) {
                        onTap(first.position)
                    }
                }
                break
            }
            // 按压超过 800ms：不再是"轻点"，等待抬手后结束（不触发轻点回调）
            if (elapsed > 800L) {
                expired = true
            }
        }
    }
}
