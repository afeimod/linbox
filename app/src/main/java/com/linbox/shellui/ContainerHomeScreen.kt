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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.linbox.apps.containers.RunContainerDialog
import com.linbox.apps.containers.NewContainerDialog
import com.linbox.apps.containers.WineSessionLauncher
import com.linbox.apps.containers.WindowsVersions
import com.linbox.apps.containers.launcher.RootfsLauncher
import com.linbox.apps.filemanager.PeIcon
import com.linbox.core.PerfGovernor
import com.linbox.core.input.gamepad.GamepadController
import com.linbox.core.media.ScreenCaptureManager
import com.linbox.core.shell.ShellController
import com.linbox.core.shell.ShellController.Screen
import com.linbox.core.window.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LinBox 主页（v4.0 按用户图片重制）：**Wine 游戏容器** 玻璃拟态主页。
 *
 * 结构（对齐图 1）：
 * 1. 顶部标题栏：应用名 + 容器概览 + 搜索钮（跳游戏库并聚焦搜索）+ 设置钮
 * 2. 游戏模式主卡（Hero）：「游戏模式已开启」+「容器运行中」标签 +
 *    白色 Xbox 手柄图形 + 蓝紫渐变「启动游戏」按钮；
 *    主卡同时识别显示选中容器名 / wine 版本（用户需求 3），
 *    背景支持容器自定义卡片图片（card_image）
 * 3. 四大功能入口：按键映射 / 性能增强 / 截图录屏 / 手柄连接
 * 4. 游戏库预览行：容器（游戏）封面横滑卡片，点「全部」进游戏库页
 * 5. 底部导航：[主页] [游戏库] [(+)容器] [设置] [高级]
 */
@Composable
fun ContainerHomeScreen() {
    val context = LocalContext.current
    val palette = GlassPalette.Light

    // ---- 数据状态 ----
    var tick by remember { mutableIntStateOf(0) }
    var containers by remember { mutableStateOf<List<ContainerData>>(emptyList()) }
    var rootfsReady by remember { mutableStateOf(true) }
    var wineCount by remember { mutableIntStateOf(0) }

    // 首页选中容器（跨页面保留；空 = 跟随默认容器）
    var selected by remember { mutableStateOf(HomeUiState.selectedContainer) }
    LaunchedEffect(selected) { HomeUiState.selectedContainer = selected }

    // 列表加载（tick 驱动）
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            containers = ContainerManager.list()
            rootfsReady = ContainerManager.rootfsReady()
            wineCount = ContainerManager.listWines().size
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
    var showCreate by remember { mutableStateOf(false) }
    var showRunFor by remember { mutableStateOf<ContainerData?>(null) }
    var showCapture by remember { mutableStateOf(false) }
    var cardImageFor by remember { mutableStateOf<ContainerData?>(null) }
    // 卡片图片选择目标（文件管理器回投后写入该容器的 card_image）
    var cardImageTarget by remember { mutableStateOf<String?>(null) }

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
            showRunFor = c
        }
    }

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
                title = "Wine 游戏容器",
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

                // ===== 2. 游戏模式主卡（Hero） =====
                item(key = "hero") {
                    HeroGameCard(
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
                        onConfig = { sel?.let { openManagerWindow(context, focus = it.name) } }
                    )
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

                // ===== 5. 游戏库预览（图 1：封面横滑行） =====
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
                            "共 ${containers.size} 款",
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

                if (containers.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🎮", fontSize = 40.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "还没有游戏容器",
                                    color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "点底部 ＋ 或「添加游戏」创建第一个 Wine 容器",
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
                            items(containers, key = { it.name }) { c ->
                                GameCoverCard(
                                    container = c,
                                    selected = c.name == sel?.name,
                                    onClick = { selected = c.name },
                                    onOpen = { runGame(c) }
                                )
                            }
                        }
                    }
                }

                // 底部呼吸空间
                item(key = "spacer") { Spacer(Modifier.height(4.dp)) }
            }

            // ===== 6. 底部导航 =====
            ShellBottomBar(current = Screen.HOME, palette = GlassPalette.Light)
        }
    }

    // ---- 弹窗层 ----
    if (showCreate) {
        val wines = remember(showCreate) { ContainerManager.listWines() }
        NewContainerDialog(
            wines = wines,
            onDismiss = { showCreate = false },
            onCreated = { name ->
                showCreate = false
                selected = name
                refresh()
                // 新建后打开管理器窗口（自动触发 wineboot 前缀构建）
                openManagerWindow(context, focus = name)
            }
        )
    }
    showRunFor?.let { c ->
        RunContainerDialog(
            container = c,
            onRunExe = { exe, args ->
                launchContainer(context, c, exe, args) { refresh() }
            },
            onDismiss = { showRunFor = null },
            onLaunched = { showRunFor = null }
        )
    }
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
}

// ============================================================
// 游戏模式主卡（Hero）
// ============================================================

/**
 * 游戏模式主卡（图 1 主卡片）：
 * - 左侧：状态标签（容器运行中/空闲）+「游戏模式已开启」标题 +
 *   选中容器名 / wine 版本 / Windows 版本 + 蓝紫渐变「启动游戏」按钮；
 * - 右侧：白色 Xbox 手柄图形；
 * - 背景：容器自定义卡片图片（card_image）或蓝紫渐变；
 * - 游戏模式标签可点击开关（开启 = 性能档预设 + 屏幕常亮）。
 */
@Composable
private fun HeroGameCard(
    container: ContainerData?,
    gameMode: Boolean,
    onToggleGameMode: () -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onConfig: () -> Unit
) {
    val palette = GlassPalette.Light
    val isRunning = container != null && container.name in RootfsLauncher.activeSessions
    val wineLabel = container?.let { wineDisplayLabel(it) } ?: "未安装"
    val winLabel = container
        ?.let { WindowsVersions.ALL.firstOrNull { w -> w.id == it.winver }?.label ?: it.winver }
        ?: "—"

    // 自定义卡片背景（cardImage）
    val cardBitmap by produceState<Bitmap?>(null, container?.cardImage) {
        value = withContext(Dispatchers.IO) { loadBitmapSafely(container?.cardImage) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(22.dp))
    ) {
        // 背景：自定义图片或蓝紫渐变
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
                        Brush.linearGradient(
                            listOf(Color(0xFF3D5BFF), Color(0xFF7A4DFF))
                        )
                    )
            )
            // 装饰光斑（offset 出血，被卡片圆角裁切）
            Box(
                Modifier
                    .size(130.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .align(Alignment.TopEnd)
                    .offset(x = 34.dp, y = (-26).dp)
            )
            Box(
                Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .align(Alignment.BottomStart)
                    .offset(x = (-28).dp, y = 18.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- 左：文案 + 按钮 ----
            Column(Modifier.weight(1.2f)) {
                // 状态标签行：容器运行中 / 游戏模式开关
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 运行状态标签
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.22f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) Color(0xFF7CFFA1) else Color(0xFFE0E4F2))
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (isRunning) "容器运行中" else "容器待机",
                            color = Color.White, fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // 游戏模式开关标签（点击切换）
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (gameMode) Color(0xFF7C4DFF).copy(alpha = 0.35f)
                                else Color.White.copy(alpha = 0.16f)
                            )
                            .clickable(onClick = onToggleGameMode)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (gameMode) "🚀" else "💤", fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (gameMode) "游戏模式" else "省电模式",
                            color = Color.White, fontSize = 11.sp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    if (gameMode) "游戏模式已开启" else "游戏模式已关闭",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                // 选中容器识别信息（用户需求 3：容器名 + wine 版本等）
                Text(
                    container?.let {
                        "🍷 ${it.name} · $wineLabel · $winLabel"
                    } ?: "🍷 尚未创建容器",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 启动游戏（蓝紫渐变按钮，图 1）
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (isRunning) Brush.horizontalGradient(
                                    listOf(Color(0xFFFF5E62), Color(0xFFFF9966))
                                )
                                else Brush.horizontalGradient(
                                    listOf(Color(0xFF4E7CFF), Color(0xFF8A5CFF))
                                )
                            )
                            .clickable { if (isRunning) onStop() else onLaunch() }
                            .padding(horizontal = 26.dp, vertical = 10.dp)
                    ) {
                        Text(
                            if (isRunning) "停止运行" else "启动游戏",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    // 容器配置小钮
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.20f))
                            .clickable(enabled = container != null, onClick = onConfig),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚙", color = Color.White, fontSize = 16.sp)
                    }
                }
            }

            // ---- 右：白色 Xbox 手柄图形（图 1） ----
            XboxControllerArt(
                modifier = Modifier
                    .weight(0.9f)
                    .height(110.dp)
                    .padding(end = 6.dp)
            )
        }
    }
}

// ============================================================
// 四大功能入口
// ============================================================

/** 功能入口（圆形玻璃钮 + 标签，图 1 中部四入口）。 */
@Composable
private fun FunctionEntry(emoji: String, label: String, onClick: () -> Unit) {
    val palette = GlassPalette.Light
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
    val palette = GlassPalette.Light
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
                .background(Color(0xFF182038))
                .padding(18.dp)
        ) {
            Text("📸 截图 · 录屏", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "基于系统整屏捕获（X11 游戏画面一并录制）。\n截图 → Pictures/LinBox · 录屏 → Movies/LinBox",
                color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, lineHeight = 15.sp
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
                .background(Color(0xFF263238))
                .padding(16.dp)
        ) {
            Text("🖼 设置「${container.name}」卡片图片", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "在文件管理器中选择一张图片作为该容器卡片的背景；\n选择后可在游戏库长按菜单中再次更换。",
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = {
                    onSetTarget(container.name)
                    WindowManager.get().open(
                        appId = "file_explorer",
                        title = "选择卡片图片",
                        launchMode = com.linbox.core.window.LaunchMode.FLOATING,
                        launchArgs = mapOf(
                            "pickMode" to "image",
                            "targetApp" to "home_card_image",
                            "targetWindow" to ""
                        )
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

/** 打开容器管理器窗口（focus=选中容器；page=advanced/doctor 深链）。 */
internal fun openManagerWindow(context: Context, focus: String? = null, page: String? = null) {
    val args = buildMap {
        focus?.let { put("focus", it) }
        page?.let { put("page", it) }
    }
    WindowManager.get().openOrRestore(
        appId = "wine-containers",
        title = "Bionic 容器",
        launchMode = com.linbox.core.window.LaunchMode.FLOATING,
        launchArgs = args
    )
}

internal fun toast(context: Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
}
