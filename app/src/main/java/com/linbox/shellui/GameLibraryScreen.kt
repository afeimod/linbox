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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.linbox.LinBoxApp
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerManager
import com.linbox.apps.containers.WineSessionLauncher
import com.linbox.apps.containers.launcher.RootfsLauncher
import com.linbox.core.shell.ShellController
import com.linbox.core.shell.ShellController.Screen
import com.linbox.data.db.entity.ShortcutEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v6.0 游戏库（完全重制）：**游戏快捷方式库**，不是容器列表。
 *
 * 用户诉求："游戏库不是容器库，游戏库是添加游戏快捷方式的不是容器" ——
 * 一条游戏快捷方式 = （显示名 + exe 路径 + 所属容器 + 可选卡片图），
 * 点击即用所属容器运行该 exe；容器本体管理全部在「容器」页。
 *
 * 存储：Room shortcuts 表 type=4（label=游戏名 / target=容器名 /
 * launchArgs=exe 路径 / iconAsset=卡片图片路径）。
 *
 * 交互（winlator 风格）：
 * - 网格卡片：exe 图标 / 自定义卡片图 + 游戏名 + 容器名 + ▶ 运行；
 * - 「添加游戏」：表单弹窗（名称 + 浏览选 exe + 容器下拉 + 卡片图）；
 * - 长按：编辑 / 设置卡片图片 / 删除；
 * - 搜索（顶栏 + 展开式搜索框，主页搜索请求联动）。
 */

/** 游戏快捷方式（Room shortcuts 表 type=4 的映射）。 */
data class GameShortcut(
    val id: Long,
    val label: String,
    val containerName: String,
    val exe: String,
    val cardImage: String
)

object GameShortcuts {
    const val TYPE = 4

    suspend fun list(app: LinBoxApp): List<GameShortcut> =
        app.database.shortcutDao().getAll()
            .filter { it.type == TYPE }
            .map { GameShortcut(it.id, it.label, it.target, it.launchArgs, it.iconAsset) }

    suspend fun upsert(app: LinBoxApp, s: GameShortcut) {
        val dao = app.database.shortcutDao()
        val entity = ShortcutEntity(
            id = if (s.id > 0) s.id else 0,
            label = s.label,
            iconAsset = s.cardImage,
            type = TYPE,
            target = s.containerName,
            launchArgs = s.exe,
            sortOrder = 0
        )
        if (s.id > 0) dao.update(entity) else dao.insert(entity)
    }

    suspend fun delete(app: LinBoxApp, s: GameShortcut) {
        app.database.shortcutDao().deleteById(s.id)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameLibraryScreen() {
    val context = LocalContext.current
    val app = LinBoxApp.get()
    val cs = rememberCoroutineScope()
    val palette = GlassPalette.Dark

    // ---- 数据状态 ----
    var tick by remember { mutableIntStateOf(0) }
    var shortcuts by remember { mutableStateOf<List<GameShortcut>>(emptyList()) }
    var containers by remember { mutableStateOf<List<ContainerData>>(emptyList()) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            shortcuts = GameShortcuts.list(app)
            containers = ContainerManager.list()
        }
    }
    fun refresh() { tick++ }

    // 返回本页自动刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ---- 搜索 ----
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (HomeUiState.librarySearchRequest) {
            searchOpen = true
            HomeUiState.librarySearchRequest = false
        }
    }
    val shown = remember(shortcuts, query) {
        if (query.isBlank()) shortcuts
        else shortcuts.filter {
            it.label.contains(query, true) ||
                it.exe.substringAfterLast('/').contains(query, true)
        }
    }

    // ---- 弹窗 ----
    var addOpen by remember { mutableStateOf(false) }
    var editFor by remember { mutableStateOf<GameShortcut?>(null) }
    var deleteFor by remember { mutableStateOf<GameShortcut?>(null) }

    fun runShortcut(s: GameShortcut) {
        val container = containers.firstOrNull { it.name == s.containerName }
        if (container == null) {
            toast(context, "容器「${s.containerName}」不存在，请先编辑该游戏")
            return
        }
        cs.launch(Dispatchers.IO) {
            val err = WineSessionLauncher.runExe(context, container, s.exe)
            withContext(Dispatchers.Main) { err?.let { toast(context, it) } }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom)))
    ) {
        Column(Modifier.fillMaxSize()) {

            // ===== 1. 顶部标题栏 =====
            GlassTopBar(
                palette = palette,
                title = "游戏库",
                onSearch = { searchOpen = !searchOpen },
                onSettings = { ShellController.showSettings() }
            )

            // ===== 2. 标题行 + 添加游戏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "我的游戏 共 ${shortcuts.size} 款",
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { addOpen = true },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.blue),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加游戏", fontSize = 13.sp)
                }
            }

            // ===== 3. 搜索框 =====
            if (searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("搜索游戏名 / exe", fontSize = 12.sp, color = Color(0x66FFFFFF)) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // ===== 4. 游戏网格 =====
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)
            ) {
                if (shortcuts.isEmpty() && containers.isEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                        EmptyLibraryHint(hasContainers = false)
                    }
                } else if (shown.isEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                        EmptyLibraryHint(
                            hasContainers = true,
                            hint = if (query.isBlank())
                                "还没有游戏快捷方式 —— 点「添加游戏」把 exe 加进游戏库"
                            else "没有匹配「$query」的游戏"
                        )
                    }
                } else {
                    items(shown, key = { it.id }) { s ->
                        GameShortcutCard(
                            shortcut = s,
                            containerLabel = containers
                                .firstOrNull { it.name == s.containerName }?.let { c ->
                                    c.name
                                } ?: s.containerName,
                            running = RootfsLauncher.activeSessions.containsKey(s.containerName),
                            onRun = { runShortcut(s) },
                            onLongPress = { editFor = s }
                        )
                    }
                }
            }

            // ===== 5. 底部导航 =====
            ShellBottomBar(current = Screen.LIBRARY, palette = palette)
        }
    }

    // ---- 添加游戏 ----
    if (addOpen) {
        GameShortcutEditorDialog(
            initial = null,
            containers = containers,
            onDismiss = { addOpen = false },
            onSave = { s ->
                addOpen = false
                cs.launch(Dispatchers.IO) {
                    GameShortcuts.upsert(app, s)
                    withContext(Dispatchers.Main) { refresh() }
                }
            }
        )
    }

    // ---- 编辑 / 删除 ----
    editFor?.let { s ->
        GameShortcutEditorDialog(
            initial = s,
            containers = containers,
            onDismiss = { editFor = null },
            onSave = { updated ->
                editFor = null
                cs.launch(Dispatchers.IO) {
                    GameShortcuts.upsert(app, updated)
                    withContext(Dispatchers.Main) { refresh() }
                }
            },
            onDelete = {
                editFor = null
                deleteFor = s
            }
        )
    }
    deleteFor?.let { s ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("移出游戏库", fontWeight = FontWeight.Bold) },
            text = { Text("把「${s.label}」从游戏库移除？（不影响容器与游戏文件）") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    cs.launch(Dispatchers.IO) {
                        GameShortcuts.delete(app, s)
                        withContext(Dispatchers.Main) { refresh() }
                    }
                }) { Text("移除", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } },
            containerColor = Color(0xFF1A2233)
        )
    }
}

// ===========================================================================
// 游戏卡片
// ===========================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameShortcutCard(
    shortcut: GameShortcut,
    containerLabel: String,
    running: Boolean,
    onRun: () -> Unit,
    onLongPress: () -> Unit
) {
    val palette = GlassPalette.Dark
    val cardBitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, shortcut.cardImage) {
        value = withContext(Dispatchers.IO) { loadBitmapSafely(shortcut.cardImage) }
    }
    val exeIcon by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, shortcut.exe) {
        value = withContext(Dispatchers.IO) { loadExeIcon(shortcut.exe) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onRun, onLongClick = onLongPress)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.45f)
                .clip(RoundedCornerShape(16.dp))
                .then(
                    if (cardBitmap != null) Modifier
                    else Modifier.background(palette.card)
                )
                .then(
                    if (cardBitmap == null) Modifier.border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
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
                            modifier = Modifier.size(46.dp)
                        )
                    } else {
                        Text("🎮", fontSize = 30.sp)
                    }
                }
            }
            // 运行角标
            if (running) {
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(9.dp)
                        .align(Alignment.TopEnd)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFF66BB6A))
                )
            }
            // ▶ 运行钮
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(34.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(palette.blue)
                    .combinedClickable(onClick = onRun, onLongClick = onLongPress),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, "运行", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            shortcut.label,
            color = palette.textPrimary,
            fontSize = 13.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            "$containerLabel · ${shortcut.exe.substringAfterLast('/')}",
            color = palette.textSecondary,
            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyLibraryHint(hasContainers: Boolean, hint: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🎮", fontSize = 44.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            hint ?: "还没有容器 —— 先到「容器」页创建一个，再回来添加游戏",
            color = GlassPalette.Dark.textSecondary,
            fontSize = 12.sp, lineHeight = 17.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (hasContainers) {
            Spacer(Modifier.height(10.dp))
            Text(
                "长按游戏卡片可编辑 / 删除",
                color = Color(0x59FFFFFF), fontSize = 10.sp
            )
        }
    }
}

// ===========================================================================
// 添加 / 编辑游戏弹窗（安卓表单）
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameShortcutEditorDialog(
    initial: GameShortcut?,
    containers: List<ContainerData>,
    onDismiss: () -> Unit,
    onSave: (GameShortcut) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val app = LinBoxApp.get()
    var name by remember { mutableStateOf(initial?.label ?: "") }
    var exe by remember { mutableStateOf(initial?.exe ?: "") }
    var containerName by remember {
        mutableStateOf(initial?.containerName ?: containers.firstOrNull()?.name ?: "")
    }
    var cardImage by remember { mutableStateOf(initial?.cardImage ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    // exe 选择回投（文件管理器）
    FilePickListenerEffect(targetApp = "game_library_exe") { path ->
        if (path.endsWith(".exe", true) || path.endsWith(".bat", true) ||
            path.endsWith(".msi", true) || path.endsWith(".cmd", true)
        ) {
            exe = path
            if (name.isBlank()) {
                name = path.substringAfterLast('/').substringBeforeLast('.')
            }
        }
    }
    // 卡片图片选择回投
    FilePickListenerEffect(targetApp = "game_library_img") { path ->
        cardImage = path
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) "添加游戏" else "编辑游戏",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("游戏名称") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                // exe
                OutlinedTextField(
                    value = exe,
                    onValueChange = { exe = it; error = null },
                    label = { Text("程序（exe / bat / msi）") },
                    placeholder = { Text("/storage/emulated/0/Download/game/game.exe", fontSize = 10.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                OutlinedButton(onClick = {
                    ShellController.showFiles(pickMode = "exe", targetApp = "game_library_exe")
                }) { Text("浏览选择程序", fontSize = 12.sp) }

                // 容器下拉
                if (containers.isEmpty()) {
                    Text(
                        "还没有容器 —— 先到「容器」页创建",
                        color = Color(0xFFF6C454), fontSize = 12.sp
                    )
                } else {
                    Text("用哪个容器运行", color = Color(0x99FFFFFF), fontSize = 12.sp)
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = containerName,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            containers.forEach { c ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(c.name, fontSize = 13.sp)
                                            Text(
                                                c.wine.ifBlank { "默认 Wine" } + " · " + c.backend.label,
                                                fontSize = 10.sp, color = Color(0x66FFFFFF)
                                            )
                                        }
                                    },
                                    onClick = {
                                        containerName = c.name
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 卡片图片
                if (cardImage.isNotBlank()) {
                    Text(
                        "卡片图：$cardImage",
                        color = Color(0x66FFFFFF), fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = {
                    ShellController.showFiles(pickMode = "image", targetApp = "game_library_img")
                }) { Text("选择卡片图片（可选）", fontSize = 12.sp) }

                if (error != null) {
                    Text(error ?: "", color = Color(0xFFFF8A80), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                when {
                    n.isEmpty() -> error = "请输入游戏名称"
                    exe.isBlank() -> error = "请选择程序（exe）"
                    containerName.isBlank() -> error = "请选择容器"
                    else -> onSave(
                        GameShortcut(
                            id = initial?.id ?: 0,
                            label = n,
                            containerName = containerName,
                            exe = exe.trim(),
                            cardImage = cardImage
                        )
                    )
                }
            }) { Text(if (initial == null) "添加" else "保存", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(15.dp),
                            tint = Color(0xFFFF6B6B))
                        Spacer(Modifier.width(3.dp))
                        Text("删除", color = Color(0xFFFF6B6B))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        containerColor = Color(0xFF1A2233)
    )
}
