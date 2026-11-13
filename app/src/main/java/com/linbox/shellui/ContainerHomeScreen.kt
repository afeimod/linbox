package com.linbox.shellui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerManager
import com.linbox.apps.containers.Box64Preset
import com.linbox.apps.containers.WineSessionLauncher
import com.linbox.apps.containers.WindowsVersions
import com.linbox.apps.containers.launcher.RootfsLauncher
import com.linbox.apps.filemanager.PeIcon
import com.linbox.core.PerfGovernor
import com.linbox.core.input.gamepad.GamepadController
import com.linbox.core.media.ScreenCaptureManager
import com.linbox.core.shell.ShellController
import com.linbox.core.shell.ShellController.Screen
import com.linbox.core.theme.LinShellTheme
import com.linbox.core.window.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LinBox 主页（v4.0 按用户图片重制）：**Linbox Bionic** 玻璃拟态主页。
 *
 * 结构（v6.0 按用户最新指令重排）：
 * 1. 顶部标题栏：应用名 + 容器概览 + 搜索钮（跳游戏库并聚焦搜索）+ 设置钮
 * 2. 当前容器预览卡（最上面，用户："容器在首页最上面有个当前容器预览"）：
 *    图标 + 容器名 + wine/后端/分辨率 + ●运行态 + [启动/停止][编辑]；
 *    无容器时 = 空白大加号卡（用户："空白处有个加号"）直进新建表单
 * 2.5 前缀构建进度卡：动态进度条 + 显示/隐藏日志开关（默认隐藏）
 * 3. 四大功能入口：按键映射 / 性能增强 / 截图录屏 / 手柄连接
 * 4. 游戏库预览行：**游戏快捷方式**横滑卡片（v6.0：游戏库是快捷方式库，
 *    不是容器列表），点「全部」进游戏库页
 * 5. 底部导航：[主页] [游戏库] [(+)容器] [设置] [高级]（中央＋：有容器
 *    进容器列表，无容器直进新建）
 */
@Composable
fun ContainerHomeScreen() {
    val context = LocalContext.current
    val palette = GlassPalette.current

    // ---- 数据状态 ----
    val app = com.linbox.LinBoxApp.get()
    val cs = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var containers by remember { mutableStateOf<List<ContainerData>>(emptyList()) }
    var rootfsReady by remember { mutableStateOf(true) }
    var wineCount by remember { mutableIntStateOf(0) }
    // v6.0：游戏库预览行 = 游戏快捷方式（不是容器）
    var shortcuts by remember { mutableStateOf<List<GameShortcut>>(emptyList()) }

    // 首页选中容器（跨页面保留；空 = 跟随默认容器）
    var selected by remember { mutableStateOf(HomeUiState.selectedContainer) }
    LaunchedEffect(selected) { HomeUiState.selectedContainer = selected }

    // 列表加载（tick 驱动）
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            containers = ContainerManager.list()
            rootfsReady = ContainerManager.rootfsReady()
            wineCount = ContainerManager.listWines().size
            shortcuts = GameShortcuts.list(app)
        }
    }
    fun refresh() { tick++ }

    // 返回本页时自动刷新（从窗口/终端回来容器集合可能已变）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 会话运行中每 3s 轻量刷新（● 运行态 / 前缀构建进度）；
    // X11 真全屏时让位（PerfGovernor，AnWind 性能优化移植）
    val anyRunning = RootfsLauncher.activeSessions.isNotEmpty()
    LaunchedEffect(anyRunning) {
        while (anyRunning) {
            delay(3000)
            if (PerfGovernor.shouldTick()) refresh()
        }
    }

    // 当前选中容器（未显式选择时跟随默认容器 / 第一个）
    val sel: ContainerData? = containers.firstOrNull { it.name == selected }
        ?: containers.firstOrNull { it.name == ContainerManager.defaultName() }
        ?: containers.firstOrNull()

    // ---- 游戏模式（主卡开关；开启=性能档运行 + 保持屏幕常亮） ----
    var gameMode by remember { mutableStateOf(HomeUiState.gameMode) }
    LaunchedEffect(gameMode) {
        HomeUiState.gameMode = gameMode
        val act = context as? Activity
        if (gameMode) {
            act?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            act?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---- 搜索（跳游戏库页并聚焦搜索框） ----
    fun goSearch() {
        HomeUiState.librarySearchRequest = true
        ShellController.showLibrary()
    }

    // ---- 弹窗 ----
    var showCapture by remember { mutableStateOf(false) }
    var cardImageFor by remember { mutableStateOf<ContainerData?>(null) }
    // 卡片图片选择目标（文件管理器回投后写入该容器的 card_image）
    var cardImageTarget by remember { mutableStateOf<String?>(null) }
    // 前缀构建日志开关（默认隐藏，用户："默认不显示日志"）
    var prefixLogOpen by remember { mutableStateOf(false) }
    val prefixBuilding by RootfsLauncher.prefixBuildFlow.collectAsState()

    // 文件管理器 pickMode=image 的回投监听（首页在组合期间有效）
    CardImagePickListener { path ->
        val target = cardImageTarget ?: return@CardImagePickListener
        containers.firstOrNull { it.name == target }?.let { c ->
            val err = ContainerManager.update(c.copy(cardImage = path))
            toast(context, err ?: "卡片图片已更新：$path")
        }
        cardImageTarget = null
        refresh()
    }

    // ---- 运行入口（游戏模式 = 性能档预设覆盖） ----
    fun runGame(c: ContainerData) {
        val effective = if (gameMode) c.copy(preset = Box64Preset.PERFORMANCE) else c
        if (c.exe.isNotBlank()) {
            launchContainer(context, effective) { refresh() }
        } else {
            // v6.0：无默认程序 → 进容器编辑页设置 exe（winlator 式引导）
            toast(context, "先为容器设置默认程序（exe）")
            openManagerWindow(context, focus = c.name)
        }
    }

    // v6.4（用户：“主页的下方游戏库点击游戏图标启动没有按照游戏库内
    // 游戏的对应配置启动”）：主页快捷方式启动改走与游戏库页同一条合并
    // 链路 —— GameLaunchConfig.applyTo（游戏专属增量覆盖：分辨率/驱动/
    // 环境变量…）+ precheckRun 预检 + runExe(merged)。旧版直接
    // launchContainer 用容器原始配置，游戏编辑页里设置的分辨率等
    // 全部被忽略（即“没按对应配置启动”的根因）。
    fun runShortcutHome(s: GameShortcut) {
        val base = containers.firstOrNull { it.name == s.containerName }
        if (base == null) {
            toast(context, "容器「${s.containerName}」不存在，请长按游戏卡片编辑")
            return
        }
        val merged = GameLaunchConfig.applyTo(base, s.config)
        val pre = WineSessionLauncher.precheckRun(merged)
        if (pre != null) {
            toast(context, pre)
            return
        }
        Thread {
            val err = WineSessionLauncher.runExe(context, merged, s.exe)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                err?.let { toast(context, it) }
                refresh()
            }
        }.start()
    }

    // v6.4：主页快捷方式长按菜单 → 删除确认目标
    var deleteShortcut by remember { mutableStateOf<GameShortcut?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom))
            )
    ) {
        Column(Modifier.fillMaxSize()) {

            // ===== 1. 顶部标题栏（图 1：标题 + 搜索 + 设置） =====
            GlassTopBar(
                palette = palette,
                title = "Linbox Bionic",
                subtitle = if (rootfsReady)
                    if (containers.isEmpty()) "就绪 · 点 + 创建容器"
                    else "${containers.size} 容器 · ${wineCount} Wine"
                else "rootfs 未就位",
                onSearch = { goSearch() },
                onSettings = { ShellController.showSettings() }
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {

                // ===== 2. 当前容器预览卡（用户："容器在首页最上面有个当前容器预览"） =====
                item(key = "hero") {
                    CurrentContainerPreviewCard(
                        container = sel,
                        gameMode = gameMode,
                        onToggleGameMode = { gameMode = !gameMode },
                        onLaunch = { sel?.let { runGame(it) } },
                        onStop = {
                            sel?.let {
                                val err = WineSessionLauncher.stopContainer(context, it.name)
                                err?.let { msg -> toast(context, msg) }
                                refresh()
                            }
                        },
                        onConfig = { sel?.let { openManagerWindow(context, focus = it.name) } },
                        onCardImage = { sel?.let { cardImageFor = it } },
                        onCreate = { openManagerWindow(context) }
                    )
                }

                // ===== 2.5 前缀构建进度卡（动态进度条 + 显示/隐藏日志，默认隐藏） =====
                prefixBuilding?.let { name ->
                    item(key = "prefix-$name") {
                        PrefixBuildCard(
                            containerName = name,
                            expandedLog = prefixLogOpen,
                            onToggleLog = { prefixLogOpen = !prefixLogOpen }
                        )
                    }
                }

                // ===== 3. rootfs 未就位横幅 =====
                if (!rootfsReady) {
                    item(key = "rootfs") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFFFF8E1))
                                .border(1.dp, Color(0xFFE6C860), RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                "⚠️ Bionic rootfs 未就位",
                                color = Color(0xFF9A7B1A), fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "将 rootfs 打包为 .tzst 放入 APK assets/linbox/（文件名 rootfs-*.tzst），" +
                                    "重启应用即自动解压到 /data/data/com.linbox/files/rootfs；" +
                                    "或从 CI Release 导入 rootfs / wine 包后重试。终端侧可用 " +
                                    "linbox-container install-rootfs <tar> 手动导入。",
                                color = Color(0xFF8D7735), fontSize = 11.sp, lineHeight = 15.sp
                            )
                        }
                    }
                }

                // ===== 4. 四大功能入口（图 1：按键映射/性能增强/截图录屏/手柄连接） =====
                item(key = "functions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(palette.card)
                            .border(1.dp, palette.cardBorder, RoundedCornerShape(18.dp))
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FunctionEntry("🕹", "按键映射") {
                            // 虚拟手柄按键布局编辑器（.vgp 布局 = 按键映射）
                            GamepadController.visible = true
                            GamepadController.settingsOpen = true
                        }
                        FunctionEntry("🚀", "性能增强") {
                            // 容器高级设置（Box64 DYNAREC 档位 / MangoHud 等）
                            openManagerWindow(context, page = "advanced")
                        }
                        FunctionEntry("📸", "截图录屏") { showCapture = true }
                        FunctionEntry("🎮", "手柄连接") {
                            // 打开虚拟手柄（悬浮球常驻，X11 会话注入 X）
                            GamepadController.visible = !GamepadController.visible
                            toast(
                                context,
                                if (GamepadController.visible) "虚拟手柄已连接（悬浮球可收起）" else "虚拟手柄已断开"
                            )
                        }
                    }
                }

                // ===== 5. 游戏库预览（v6.0：游戏快捷方式横滑行，不是容器） =====
                item(key = "library-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "游戏库",
                            color = palette.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "共 ${shortcuts.size} 款游戏",
                            color = palette.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "全部 ›",
                            color = palette.blue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { ShellController.showLibrary() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (shortcuts.isEmpty()) {
                    item(key = "empty-library") {
                        // 空白处引导：还没有游戏快捷方式
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(110.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🎮", fontSize = 34.sp)
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    "还没有游戏 —— 到游戏库「添加游戏」",
                                    color = palette.textSecondary, fontSize = 12.sp
                                )
                            }
                        }
                    }
                } else {
                    item(key = "library-row") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(shortcuts, key = { it.id }) { g ->
                                ShortcutCoverCard(
                                    shortcut = g,
                                    running = RootfsLauncher.activeSessions.containsKey(g.containerName),
                                    onRun = { runShortcutHome(g) },
                                    onEdit = {
                                        // 跳游戏库页打开编辑器（编辑器状态在
                                        // GameLibraryUiState 单例，跨页存活）
                                        ShellController.showLibrary()
                                        GameLibraryUiState.openEdit(g)
                                    },
                                    onDelete = { deleteShortcut = g }
                                )
                            }
                        }
                    }
                }

                // 底部呼吸空间
                item(key = "spacer") { Spacer(Modifier.height(4.dp)) }
            }

            // ===== 6. 底部导航（中央＋：有容器进列表 / 无容器直进新建） =====
            ShellBottomBar(
                current = Screen.HOME,
                palette = GlassPalette.current,
                hasContainers = containers.isNotEmpty()
            )
        }
    }

    // ---- 弹窗层 ----
    // v6.0：新建容器走「容器」页编辑表单（winlator 式）；运行程序走容器页
    // 长按面板 —— 首页不再持有这两类弹窗。
    cardImageFor?.let { c ->
        CardImagePickerDialog(
            container = c,
            onSetTarget = { cardImageTarget = it },
            onDismiss = { cardImageFor = null },
            onPicked = {
                cardImageFor = null
                refresh()
            }
        )
    }
    if (showCapture) {
        CaptureDialog(onDismiss = { showCapture = false })
    }

    // v6.4：主页快捷方式删除确认（与游戏库页同款语义）
    deleteShortcut?.let { g ->
        AlertDialog(
            onDismissRequest = { deleteShortcut = null },
            title = { Text("移出游戏库", fontWeight = FontWeight.Bold) },
            text = { Text("把「${g.label}」从游戏库移除？（不影响容器与游戏文件）") },
            confirmButton = {
                TextButton(onClick = {
                    deleteShortcut = null
                    cs.launch(Dispatchers.IO) {
                        GameShortcuts.delete(app, g)
                        withContext(Dispatchers.Main) { refresh() }
                    }
                }) { Text("移除", color = LinShellTheme.red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteShortcut = null }) { Text("取消") }
            },
            containerColor = LinShellTheme.surface
        )
    }
}

// ============================================================
// 游戏模式主卡（Hero）
// ============================================================

/**
 * 当前容器预览卡（v6.0，用户："容器在首页最上面有个当前容器预览"）：
 * - 有容器：图标 + 容器名 + wine/后端/分辨率 摘要 + ●运行态 +
 *   [启动/停止] [编辑]；长按可设置卡片背景图；游戏模式胶囊开关；
 * - 无容器：空白加号卡（用户："空白处有个加号"）→ 直接进新建容器表单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CurrentContainerPreviewCard(
    container: ContainerData?,
    gameMode: Boolean,
    onToggleGameMode: () -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onConfig: () -> Unit,
    onCardImage: () -> Unit,
    onCreate: () -> Unit
) {
    val palette = GlassPalette.current

    // ---- 无容器：空白加号 ----
    if (container == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(palette.card)
                .border(1.dp, palette.cardBorder, RoundedCornerShape(20.dp))
                .clickable(onClick = onCreate)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF4E7CFF), Color(0xFF8A5CFF)))),
                contentAlignment = Alignment.Center
            ) { Text("＋", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "创建第一个容器",
                    color = palette.textPrimary,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Wine 容器 · 创建后自动构建 Windows 前缀",
                    color = palette.textSecondary, fontSize = 12.sp
                )
            }
        }
        return
    }

    // ---- 有容器：预览卡 ----
    val isRunning = container.name in RootfsLauncher.activeSessions
    val wineLabel = wineDisplayLabel(container)

    val cardBitmap by produceState<Bitmap?>(null, container.cardImage) {
        value = withContext(Dispatchers.IO) { loadBitmapSafely(container.cardImage) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onConfig, onLongClick = onCardImage)
    ) {
        // 背景：自定义图片或蓝紫渐变
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            if (cardBitmap != null) {
                Image(
                    bitmap = cardBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(Modifier.fillMaxSize().background(Color(0xFF1B2A5E).copy(alpha = 0.55f)))
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF3D5BFF), Color(0xFF7A4DFF)))
                        )
                )
                Box(
                    Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .align(Alignment.TopEnd)
                        .offset(x = 30.dp, y = (-24).dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左：exe 图标（或容器图标）
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    ExeIcon(container = container, size = 44.dp)
                }
                Spacer(Modifier.width(13.dp))

                // 中：容器信息
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            container.name,
                            color = Color.White,
                            fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(7.dp))
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) Color(0xFF7CFFA1) else Color(0xFFE0E4F2))
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "🍷 $wineLabel · ${container.backend.label} · ${screenLabel(container)}",
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(7.dp))
                    // 游戏模式胶囊（性能档 + 常亮）
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (gameMode) Color(0xFF7C4DFF).copy(alpha = 0.35f)
                                else Color.White.copy(alpha = 0.16f)
                            )
                            .clickable(onClick = onToggleGameMode)
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (gameMode) "🚀" else "💤", fontSize = 10.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (gameMode) "游戏模式" else "省电模式",
                            color = Color.White, fontSize = 10.5.sp
                        )
                    }
                }

                // 右：启动/停止 + 编辑
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRunning) Color(0xFFE5484D)
                                else Color.White.copy(alpha = 0.22f)
                            )
                            .clickable { if (isRunning) onStop() else onLaunch() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (isRunning) "⏹" else "▶",
                            color = Color.White, fontSize = 19.sp
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .clickable(onClick = onConfig),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚙", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/**
 * 游戏快捷方式封面卡（主页横滑行，v6.0：游戏库 = 快捷方式不是容器）。
 *
 * v6.4：长按弹上下文菜单（启动 / 编辑 / 删除 / 取消，与游戏库页同款
 * 交互）；专属配置角标与游戏库页一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShortcutCoverCard(
    shortcut: GameShortcut,
    running: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = GlassPalette.current
    val cardBitmap by produceState<Bitmap?>(null, shortcut.cardImage) {
        value = withContext(Dispatchers.IO) { loadBitmapSafely(shortcut.cardImage) }
    }
    val exeIcon by produceState<Bitmap?>(null, shortcut.exe) {
        value = withContext(Dispatchers.IO) { loadExeIcon(shortcut.exe) }
    }
    // v6.4：长按上下文菜单
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(118.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onRun, onLongClick = { menuOpen = true })
    ) {
        Box(
            modifier = Modifier
                .size(width = 118.dp, height = 80.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (cardBitmap != null) Modifier
                    else Modifier.background(palette.card)
                )
                .then(
                    if (cardBitmap == null) Modifier.border(1.dp, palette.cardBorder, RoundedCornerShape(14.dp))
                    else Modifier
                )
        ) {
            if (cardBitmap != null) {
                Image(
                    bitmap = cardBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (exeIcon != null) {
                        Image(
                            bitmap = exeIcon!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(38.dp)
                        )
                    } else {
                        Text("🎮", fontSize = 26.sp)
                    }
                }
            }
            // v6.4：游戏专属配置角标（与游戏库页一致，一眼可辨）
            if (shortcut.config.isNotBlank()) {
                Text(
                    "专属配置",
                    color = Color.White, fontSize = 8.sp,
                    modifier = Modifier
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0x990659C8))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            // 运行角标
            if (running) {
                Box(
                    modifier = Modifier
                        .padding(5.dp)
                        .size(8.dp)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(Color(0xFF66BB6A))
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            shortcut.label,
            color = palette.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            shortcut.containerName,
            color = palette.textSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    // ===== v6.4：长按上下文菜单（启动 / 编辑 / 删除 / 取消）=====
    if (menuOpen) {
        androidx.compose.ui.window.Popup(
            alignment = Alignment.Center,
            onDismissRequest = { menuOpen = false },
            properties = androidx.compose.ui.window.PopupProperties(
                focusable = true,
                dismissOnClickOutside = true
            )
        ) {
            Column(
                modifier = Modifier
                    .width(196.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(LinShellTheme.surface)
                    .border(1.dp, LinShellTheme.surfaceBorder, RoundedCornerShape(14.dp))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    shortcut.label,
                    color = LinShellTheme.textSecondary,
                    fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                ShortcutMenuItem("▶  启动", LinShellTheme.blue) { menuOpen = false; onRun() }
                ShortcutMenuItem("✏️  编辑", LinShellTheme.textPrimary) { menuOpen = false; onEdit() }
                ShortcutMenuItem("🗑  删除", LinShellTheme.red) { menuOpen = false; onDelete() }
                ShortcutMenuItem("✕  取消", LinShellTheme.textSecondary) { menuOpen = false }
            }
        }
    }
}

/** v6.4：主页快捷方式长按菜单项（与游戏库页 GameCardMenuItem 同款）。 */
@Composable
private fun ShortcutMenuItem(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        label,
        color = tint,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    )
}

// ============================================================
// 四大功能入口
// ============================================================

/** 功能入口（圆形玻璃钮 + 标签，图 1 中部四入口）。 */
@Composable
private fun FunctionEntry(emoji: String, label: String, onClick: () -> Unit) {
    val palette = GlassPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF4E7CFF), Color(0xFF8A5CFF))))
                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 22.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = palette.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ============================================================
// 游戏封面卡（主页预览行）
// ============================================================

/**
 * 游戏封面卡：自定义卡片图片背景（card_image）或浅蓝玻璃底 +
 * exe 内嵌图标（PeIcon）；下方容器名 + wine 版本；▶ 打开。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GameCoverCard(
    container: ContainerData,
    selected: Boolean,
    onClick: () -> Unit,
    onOpen: () -> Unit
) {
    val palette = GlassPalette.current
    val wineLabel = wineDisplayLabel(container)
    val cardBitmap by produceState<Bitmap?>(null, container.cardImage) {
        value = withContext(Dispatchers.IO) { loadBitmapSafely(container.cardImage) }
    }

    Column(
        modifier = Modifier
            .width(118.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onOpen)
    ) {
        Box(
            modifier = Modifier
                .size(width = 118.dp, height = 80.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (cardBitmap != null) Modifier
                    else Modifier.background(palette.card)
                )
                .then(
                    if (selected) Modifier.border(2.dp, palette.blue, RoundedCornerShape(14.dp))
                    else if (cardBitmap == null) Modifier.border(1.dp, palette.cardBorder, RoundedCornerShape(14.dp))
                    else Modifier
                )
        ) {
            if (cardBitmap != null) {
                Image(
                    bitmap = cardBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 玻璃底 + exe 图标居中
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExeIcon(container = container, size = 40.dp)
                }
            }
            // 运行角标
            if (container.name in RootfsLauncher.activeSessions) {
                Box(
                    modifier = Modifier
                        .padding(5.dp)
                        .size(8.dp)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(Color(0xFF66BB6A))
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            container.name,
            color = palette.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            wineLabel,
            color = palette.textSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================
// 截图录屏弹窗（四大功能入口之一）
// ============================================================

/** 截图 / 录屏玻璃弹窗：立即截图 / 开始·停止录屏 + 保存位置说明。 */
@Composable
private fun CaptureDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val recording = ScreenCaptureManager.recording
    val elapsed = ScreenCaptureManager.elapsedSec
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(com.linbox.core.theme.LinShellTheme.surface)
                .padding(18.dp)
        ) {
            Text("📸 截图 · 录屏", color = com.linbox.core.theme.LinShellTheme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "基于系统整屏捕获（X11 游戏画面一并录制）。\n截图 → Pictures/LinBox · 录屏 → Movies/LinBox",
                color = com.linbox.core.theme.LinShellTheme.textSecondary, fontSize = 11.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(14.dp))
            Row {
                Button(onClick = {
                    onDismiss()
                    ScreenCaptureManager.requestScreenshot(context as android.app.Activity)
                }) { Text("📸 立即截图") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = {
                    if (recording) {
                        ScreenCaptureManager.stopRecording(context)
                    } else {
                        onDismiss()
                        ScreenCaptureManager.requestRecording(context as android.app.Activity)
                    }
                }) {
                    Text(
                        if (recording) "⏹ 停止录屏 (${elapsed / 60}:${"%02d".format(elapsed % 60)})"
                        else "⏺ 开始录屏"
                    )
                }
            }
            if (recording) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "● 录屏进行中，可最小化本弹窗继续游戏",
                    color = Color(0xFFFF8A80), fontSize = 11.sp
                )
            }
        }
    }
}

// ============================================================
// 共享状态与工具（主页 / 游戏库共用）
// ============================================================

/** 首页跨页面状态。 */
internal object HomeUiState {
    var selectedContainer: String? by mutableStateOf(null)

    /** 游戏模式（性能档运行 + 屏幕常亮）。 */
    var gameMode: Boolean = true

    /** 游戏库页自动聚焦搜索框的一次性请求。 */
    var librarySearchRequest: Boolean by mutableStateOf(false)
}

/** 卡片图片选择弹窗：经 FilePickBus 拉起文件管理器（pickMode=image）
 * 选择本地图片写入 container.conf 的 card_image。 */
@Composable
private fun CardImagePickerDialog(
    container: ContainerData,
    onSetTarget: (String) -> Unit,
    onDismiss: () -> Unit,
    onPicked: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(com.linbox.core.theme.LinShellTheme.surface)
                .padding(16.dp)
        ) {
            Text("🖼 设置「${container.name}」卡片图片", color = com.linbox.core.theme.LinShellTheme.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "在文件管理器中选择一张图片作为该容器卡片的背景；\n选择后可在游戏库长按菜单中再次更换。",
                color = com.linbox.core.theme.LinShellTheme.textSecondary, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = {
                    onSetTarget(container.name)
                    com.linbox.core.shell.ShellController.showFiles(
                        pickMode = "image",
                        targetApp = "home_card_image",
                        targetWindow = ""
                    )
                    onDismiss()
                }) { Text("选择图片") }
            }
        }
    }
}

/** 首页级的卡片图片选择监听（组合即注册，离开即注销）。 */
@Composable
internal fun CardImagePickListener(onPicked: (String) -> Unit) {
    val currentOnPicked by rememberUpdatedState(onPicked)
    DisposableEffect(Unit) {
        val unregister = com.linbox.apps.filemanager.FilePickBus.listen(
            targetApp = "home_card_image",
            targetWindow = ""
        ) { path -> currentOnPicked(path) }
        onDispose { unregister() }
    }
}

/**
 * exe 内嵌图标（PeIcon）：有 exe 时提取 PE 内嵌图标（缓存），失败回退 📦。
 */
@Composable
internal fun ExeIcon(container: ContainerData, size: androidx.compose.ui.unit.Dp) {
    val bitmap by produceState<Bitmap?>(null, container.exe) {
        value = withContext(Dispatchers.IO) { loadExeIcon(container.exe) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Text("📦", fontSize = (size.value * 0.5f).sp)
        }
    }
}

/** 进程级 exe 图标缓存（路径|长度|mtime 键控，与文件管理器同策略）。 */
private object HomePeIconCache {
    private val cache = android.util.LruCache<String, Bitmap>(8 * 1024 * 1024)

    fun load(path: String): Bitmap? {
        val f = File(path)
        if (!f.isFile) return null
        val key = "$path|${f.length()}|${f.lastModified()}"
        synchronized(cache) { cache.get(key) }?.let { return it }
        val icon = runCatching {
            if (path.substringAfterLast('.', "").equals("ico", true)) {
                PeIcon.extractIco(path, 128)
            } else {
                PeIcon.extract(path, 128)
            }
        }.getOrNull() ?: return null
        val bmp = runCatching {
            if (icon.png != null) {
                BitmapFactory.decodeByteArray(icon.png, 0, icon.png.size)
            } else if (icon.argb != null && icon.width > 0 && icon.height > 0) {
                Bitmap.createBitmap(icon.argb, icon.width, icon.height, Bitmap.Config.ARGB_8888)
            } else null
        }.getOrNull() ?: return null
        synchronized(cache) { cache.put(key, bmp) }
        return bmp
    }
}

internal fun loadExeIcon(exePath: String): Bitmap? =
    if (exePath.isBlank()) null
    else runCatching { HomePeIconCache.load(exePath) }.getOrNull()

/** IO 线程解码卡片背景图（异常/超限返回 null 回退主题卡片）。 */
internal fun loadBitmapSafely(path: String?): Bitmap? {
    if (path.isNullOrBlank()) return null
    return runCatching {
        val f = File(path)
        if (!f.isFile || f.length() > 24 * 1024 * 1024) return@runCatching null
        // 降采样：目标最大边 1024（卡片显示足够，防大图 OOM）
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        val maxSide = maxOf(opts.outWidth, opts.outHeight)
        while (maxSide / sample > 1024) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

/** wine 版本展示（容器 wine= 优先，回退全局默认 wine 槽位）。 */
internal fun wineDisplayLabel(container: ContainerData): String {
    val wines = ContainerManager.listWines()
    val w = if (container.wine.isNotBlank())
        wines.firstOrNull { it.name == container.wine }
    else wines.firstOrNull { it.name == ContainerManager.defaultWineName() }
    val label = w?.displayLabel ?: container.wine.ifBlank { ContainerManager.defaultWineName() }
    return label.removePrefix("wine-").ifBlank { "默认 Wine" }
}

/** 分辨率展示（native=跟随窗口/全屏）。 */
internal fun screenLabel(container: ContainerData): String =
    if (container.screen == "native") "全屏" else container.screen

/** 运行容器（exe 已配置；错误 Toast）。 */
internal fun launchContainer(
    context: Context,
    c: ContainerData,
    exeOverride: String? = null,
    args: String? = null,
    onDone: () -> Unit
) {
    val exe = exeOverride ?: c.exe
    val a = args ?: c.uargs
    Thread {
        val err = WineSessionLauncher.runExe(context, c, exe, a)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            err?.let { toast(context, it) }
            onDone()
        }
    }.start()
}

/** 打开容器管理器（v5.0 改全屏页：focus=选中容器；page=advanced/doctor 深链）。 */
internal fun openManagerWindow(context: Context, focus: String? = null, page: String? = null) {
    // v5.0（用户：“容器创建和管理……改成安卓交互”）：不再以浮动窗口打开，
    // 直接导航到容器管理器全屏页（底部导航常驻，安卓 app bar）。
    ShellController.openContainers(focus = focus, page = page)
}

internal fun toast(context: Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
}
