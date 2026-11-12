package com.linbox.shellui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerManager
import com.linbox.apps.containers.GuanliSupport
import com.linbox.apps.containers.NewContainerDialog
import com.linbox.apps.containers.RunContainerDialog
import com.linbox.apps.containers.WineSessionLauncher
import com.linbox.apps.containers.launcher.RootfsLauncher
import com.linbox.core.shell.ShellController
import com.linbox.core.shell.ShellController.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 游戏库页（v4.0 按用户图 2 重制）：暗色玻璃主题的容器网格。
 *
 * 结构（对齐图 2）：
 * 1. 顶部标题栏：「游戏库」+ 搜索钮 + 设置钮
 * 2. 「我的游戏 共 N 款」+ 蓝色「添加游戏」按钮（新建容器）
 * 3. 搜索框（点顶栏搜索钮展开 / 主页搜索请求自动展开）
 * 4. 游戏网格（两列封面卡）：自定义卡片图片 / exe 内嵌图标 +
 *    容器名 + wine 版本 + 「打开」按钮；长按菜单（运行 / 安装前缀 /
 *    设置卡片图片 / 设为默认 / 固定到启动器 / 容器管理器）
 * 5. 底部导航：[主页] [游戏库] [(+)容器] [设置] [高级]
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameLibraryScreen() {
    val context = LocalContext.current
    val palette = GlassPalette.Dark

    // ---- 数据状态 ----
    var tick by remember { mutableIntStateOf(0) }
    var containers by remember { mutableStateOf<List<ContainerData>>(emptyList()) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            containers = ContainerManager.list()
        }
    }
    fun refresh() { tick++ }

    // 返回本页自动刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 运行中会话轻量轮询（3s；X11 全屏时让位）
    val anyRunning = RootfsLauncher.activeSessions.isNotEmpty()
    LaunchedEffect(anyRunning) {
        while (anyRunning) {
            kotlinx.coroutines.delay(3000)
            if (com.linbox.core.PerfGovernor.shouldTick()) refresh()
        }
    }

    // ---- 搜索 ----
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // 主页「搜索」请求：进入本页自动展开搜索框（一次性）
    LaunchedEffect(Unit) {
        if (HomeUiState.librarySearchRequest) {
            searchOpen = true
            HomeUiState.librarySearchRequest = false
        }
    }

    val shown = remember(containers, query) {
        if (query.isBlank()) containers
        else containers.filter {
            val exeName = it.exe.substringAfterLast('/').substringAfterLast('\\')
            it.name.contains(query, true) || exeName.contains(query, true)
        }
    }

    // ---- 弹窗 ----
    var showCreate by remember { mutableStateOf(false) }
    var showRunFor by remember { mutableStateOf<ContainerData?>(null) }
    var cardImageFor by remember { mutableStateOf<ContainerData?>(null) }
    var cardImageTarget by remember { mutableStateOf<String?>(null) }

    // 文件管理器 pickMode=image 的回投监听（游戏库在组合期间有效）
    CardImagePickListener { path ->
        val target = cardImageTarget ?: return@CardImagePickListener
        containers.firstOrNull { it.name == target }?.let { c ->
            val err = ContainerManager.update(c.copy(cardImage = path))
            toast(context, err ?: "卡片图片已更新：$path")
        }
        cardImageTarget = null
        refresh()
    }

    fun openGame(c: ContainerData) {
        if (c.exe.isNotBlank()) launchContainer(context, c) { refresh() }
        else showRunFor = c
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom))
            )
    ) {
        Column(Modifier.fillMaxSize()) {

            // ===== 1. 顶部标题栏（图 2：游戏库 + 搜索 + 设置） =====
            GlassTopBar(
                palette = palette,
                title = "游戏库",
                onSearch = { searchOpen = !searchOpen },
                onSettings = { ShellController.showSettings() }
            )

            // ===== 2. 我的游戏 共 N 款 + 添加游戏（图 2） =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "我的游戏 共 ${containers.size} 款",
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(palette.blue, Color(0xFF6BA8FF))))
                        .combinedClickable(onClick = { showCreate = true })
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("添加游戏", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // ===== 3. 搜索框 =====
            if (searchOpen) {
                GlassSearchField(
                    palette = palette,
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索游戏 / 容器…",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }

            // ===== 4. 游戏网格（图 2：封面卡片 + 打开按钮） =====
            if (containers.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎮", fontSize = 46.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "游戏库还是空的",
                            color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点「添加游戏」创建第一个 Wine 容器，\n安装 Windows 游戏后自动识别 exe 图标",
                            color = palette.textSecondary, fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            } else if (shown.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "没有匹配「$query」的游戏",
                        color = palette.textSecondary, fontSize = 13.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    items(shown, key = { it.name }) { c ->
                        LibraryGameCard(
                            container = c,
                            onOpen = { openGame(c) },
                            onCardImage = { cardImageFor = c },
                            onRefresh = { refresh() }
                        )
                    }
                }
            }

            // ===== 5. 底部导航 =====
            ShellBottomBar(current = Screen.LIBRARY, palette = GlassPalette.Dark)
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
        LibraryCardImagePickerDialog(
            container = c,
            onSetTarget = { cardImageTarget = it },
            onDismiss = { cardImageFor = null },
            onPicked = {
                cardImageFor = null
                refresh()
            }
        )
    }
}

// ============================================================
// 游戏卡（图 2 网格项）
// ============================================================

/**
 * 游戏库网格卡：封面（自定义卡片图片 card_image，无则 exe 内嵌图标 +
 * 深色玻璃底）+ 容器名 + wine 版本 + 「打开」按钮 + 长按菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGameCard(
    container: ContainerData,
    onOpen: () -> Unit,
    onCardImage: () -> Unit,
    onRefresh: () -> Unit
) {
    val palette = GlassPalette.Dark
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val isRunning = container.name in RootfsLauncher.activeSessions
    val wineLabel = wineDisplayLabel(container)

    val cardBitmap by produceState<android.graphics.Bitmap?>(null, container.cardImage) {
        value = withContext(Dispatchers.IO) { loadBitmapSafely(container.cardImage) }
    }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.card)
                .border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(10.dp)
        ) {
            // ---- 封面 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.45f)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (cardBitmap != null) Modifier
                        else Modifier.background(
                            Brush.linearGradient(
                                listOf(Color(0xFF232D4D), Color(0xFF1A2140))
                            )
                        )
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
                        ExeIcon(container = container, size = 52.dp)
                    }
                }
                // 运行角标
                if (isRunning) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC2E7D32))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF7CFFA1))
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("运行中", color = Color.White, fontSize = 9.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---- 名称 + 版本 ----
            Text(
                container.name,
                color = palette.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "🍷 $wineLabel",
                color = palette.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            // ---- 打开按钮（图 2） ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isRunning) Brush.horizontalGradient(
                            listOf(Color(0xFFFF5E62), Color(0xFFFF9966))
                        )
                        else Brush.horizontalGradient(listOf(palette.blue, palette.purple))
                    )
                    .combinedClickable(onClick = onOpen)
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isRunning) "运行中 · 查看" else "打开",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // ---- 长按菜单 ----
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("▶ 运行") }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("🔧 安装前缀（wineboot）") }, onClick = {
                menu = false
                val err = WineSessionLauncher.initializePrefix(context, container)
                err?.let { toast(context, it) }
                onRefresh()
            })
            DropdownMenuItem(text = { Text("🖼 设置卡片图片") }, onClick = { menu = false; onCardImage() })
            DropdownMenuItem(text = { Text("⭐ 设为默认容器") }, onClick = {
                menu = false
                val err = ContainerManager.setDefault(container.name)
                err?.let { toast(context, it) }
                onRefresh()
            })
            DropdownMenuItem(text = { Text("📌 固定到启动器") }, onClick = {
                menu = false
                val ok = GuanliSupport.Shortcuts.pinRunContainer(context, container, container.exe)
                toast(context, if (ok) "已固定到启动器" else "创建快捷方式失败")
            })
            DropdownMenuItem(text = { Text("🪟 在容器管理器中打开") }, onClick = {
                menu = false
                openManagerWindow(context, focus = container.name)
            })
        }
    }
}

// ============================================================
// 卡片图片选择弹窗（游戏库版）
// ============================================================

/** 卡片图片选择：文件管理器（pickMode=image）选图写入 card_image。 */
@Composable
private fun LibraryCardImagePickerDialog(
    container: ContainerData,
    onSetTarget: (String) -> Unit,
    onDismiss: () -> Unit,
    onPicked: () -> Unit
) {
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF263238))
                .padding(16.dp)
        ) {
            Text("🖼 设置「${container.name}」卡片图片", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "在文件管理器中选择一张图片作为该游戏卡片的封面；\n选择后可在长按菜单中再次更换。",
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Row {
                androidx.compose.material3.Button(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.Button(onClick = {
                    onSetTarget(container.name)
                    com.linbox.core.window.WindowManager.get().open(
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
