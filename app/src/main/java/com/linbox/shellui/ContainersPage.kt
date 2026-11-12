package com.linbox.shellui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerManager
import com.linbox.apps.containers.ContainerBackend
import com.linbox.apps.containers.Box64Preset
import com.linbox.apps.containers.ContainerFlags
import com.linbox.apps.containers.ContainerLangs
import com.linbox.apps.containers.DisplayMode
import com.linbox.apps.containers.GalliumDriver
import com.linbox.apps.containers.WindowsVersions
import com.linbox.apps.containers.WineSessionLauncher
import com.linbox.apps.containers.launcher.RootfsLauncher
import com.linbox.core.shell.ShellController
import com.linbox.core.theme.LinShellTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v6.0 容器管理（完全重制，winlator 交互风格）。
 *
 * 用户诉求："完全重制容器管理的ui逻辑，替换成纯安卓交互式的，可以模仿
 * winlator添加容器和编辑容器的方式"：
 *
 * - **容器列表页**（[ContainersScreen]）：安卓 app bar + 容器卡片列表
 *   （名称 / wine / 后端 / 分辨率 / 运行态徽章），右下角 FAB ＋ 新建；
 *   空状态大加号；点击卡片进编辑页，长按弹操作面板（启动 / 运行程序 /
 *   安装前缀 / 快捷方式 / 重命名 / 设为默认 / 删除）。
 * - **容器编辑页**（[ContainerEditorScreen]）：winlator 同款"表单页"——
 *   分组卡片 + 安卓下拉（ExposedDropdownMenuBox）/ 文本框 / 开关，
 *   底部大按钮保存（新建时顺带构建前缀）。新建与编辑共用同一表单。
 * - **容器高级设置页**（[ContainerAdvancedScreen]）：DY / FEX / Box /
 *   Wine 安装 / MangoHud / 生态配置 —— 复用原有选项卡（功能零删减），
 *   安卓页承载。
 *
 * 旧的 wineguanli 左右分栏 + 五选项卡 + 浮窗工具栏全部弃用。
 */

/** 容器管理页内部导航状态（列表 / 编辑 / 高级）。 */
object ContainerUiState {
    /** 正在编辑的容器名（null = 列表页或新建页）。 */
    var editingName by mutableStateOf<String?>(null)

    /** 是否为"新建容器"模式。 */
    var creating by mutableStateOf(false)

    /** 是否显示容器高级设置页。 */
    var advancedOpen by mutableStateOf(false)

    fun openList() {
        editingName = null
        creating = false
        advancedOpen = false
    }

    fun openEditor(name: String?) {
        editingName = name
        creating = name == null
        advancedOpen = false
    }

    fun openAdvanced() {
        advancedOpen = true
    }
}

@Composable
fun ContainersScreen() {
    when {
        ContainerUiState.advancedOpen -> ContainerAdvancedScreen()
        ContainerUiState.creating || ContainerUiState.editingName != null ->
            ContainerEditorScreen()
        else -> ContainerListScreen()
    }
}

// ===========================================================================
// 容器列表页
// ===========================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContainerListScreen() {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    // ---- 深链（focus 选中并进编辑 / page=advanced·doctor / autorun） ----
    val launchArgs = remember { ShellController.ContainerNav.consume() }

    var tick by remember { mutableIntStateOf(0) }
    var containers by remember { mutableStateOf<List<ContainerData>>(emptyList()) }
    var rootfsReady by remember { mutableStateOf(true) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            containers = ContainerManager.list()
            rootfsReady = ContainerManager.rootfsReady()
        }
    }
    fun refresh() { tick++ }

    // 返回自动刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 前缀构建进行中 → 轻量轮询
    val prefixBuilding by RootfsLauncher.prefixBuildFlow.collectAsState()
    val anyRunning = prefixBuilding != null || RootfsLauncher.activeSessions.isNotEmpty()
    LaunchedEffect(anyRunning) {
        while (anyRunning) {
            delay(2500)
            if (com.linbox.core.PerfGovernor.shouldTick()) refresh()
        }
    }

    // ---- 弹窗与面板 ----
    var actionsFor by remember { mutableStateOf<ContainerData?>(null) }     // 长按操作面板
    var renameFor by remember { mutableStateOf<ContainerData?>(null) }
    var confirmRemove by remember { mutableStateOf<ContainerData?>(null) }
    var runFor by remember { mutableStateOf<ContainerData?>(null) }         // 运行程序（exe+参数）
    var showDoctor by remember { mutableStateOf(false) }
    var showPrefixLog by remember { mutableStateOf(false) }

    // ---- 深链消费 ----
    LaunchedEffect(launchArgs, containers.isNotEmpty()) {
        val focus = launchArgs["focus"]
        if (!focus.isNullOrBlank() && containers.any { it.name == focus }) {
            ContainerUiState.openEditor(focus)
            return@LaunchedEffect
        }
        when (launchArgs["page"]) {
            "advanced" -> ContainerUiState.openAdvanced()
            "doctor" -> showDoctor = true
        }
        val autorun = launchArgs["autorun"]
        if (!autorun.isNullOrBlank() && containers.isNotEmpty()) {
            val c = containers.firstOrNull { it.name == autorun } ?: return@LaunchedEffect
            if (c.exe.isNotBlank()) {
                cs.launch(Dispatchers.IO) {
                    val err = WineSessionLauncher.runExe(context, c, c.exe)
                    withContext(Dispatchers.Main) {
                        err?.let { toast(context, it) }
                    }
                }
            } else {
                runFor = c
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        ShellController.closeContainers()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(LinShellTheme.bg, Color(0xFF0B0F1C)))
            )
    ) {
        Column(Modifier.fillMaxSize()) {

            // ===== 安卓 app bar =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(LinShellTheme.card)
                        .clickable { ShellController.closeContainers() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "返回",
                        tint = LinShellTheme.textPrimary, modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "容器",
                        color = LinShellTheme.textPrimary,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        buildString {
                            append("${containers.size} 个容器")
                            if (!rootfsReady) append(" · rootfs 未就位")
                        },
                        color = LinShellTheme.textSecondary, fontSize = 11.sp
                    )
                }
                // 容器体检
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(LinShellTheme.card)
                        .clickable { showDoctor = true },
                    contentAlignment = Alignment.Center
                ) { Text("🩺", fontSize = 17.sp) }
                Spacer(Modifier.width(8.dp))
                // 高级设置
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(LinShellTheme.card)
                        .clickable { ContainerUiState.openAdvanced() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Settings, "高级设置",
                        tint = LinShellTheme.textPrimary, modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ===== 列表 =====
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 前缀构建进度（置顶）
                prefixBuilding?.let { name ->
                    item(key = "prefix-$name") {
                        PrefixBuildCard(
                            containerName = name,
                            expandedLog = showPrefixLog,
                            onToggleLog = { showPrefixLog = !showPrefixLog }
                        )
                    }
                }

                if (containers.isEmpty()) {
                    item(key = "empty") {
                        // 空状态：空白处大加号（用户："空白处有个加号"）
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .border(
                                        2.dp, LinShellTheme.blue.copy(alpha = 0.55f),
                                        RoundedCornerShape(28.dp)
                                    )
                                    .background(LinShellTheme.blue.copy(alpha = 0.10f))
                                    .clickable { ContainerUiState.openEditor(null) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Add, "创建容器",
                                    tint = LinShellTheme.blue,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "还没有容器",
                                color = LinShellTheme.textPrimary,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "点上面的 ＋ 创建第一个 Wine 容器\n或用右下角按钮",
                                color = LinShellTheme.textSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                } else {
                    items(containers, key = { it.name }) { c ->
                        val running = RootfsLauncher.activeSessions.containsKey(c.name)
                        ContainerCard(
                            container = c,
                            isDefault = ContainerManager.isDefault(c.name),
                            running = running,
                            onClick = { ContainerUiState.openEditor(c.name) },
                            onLongPress = { actionsFor = c },
                            onQuickRun = {
                                if (c.exe.isNotBlank()) {
                                    cs.launch(Dispatchers.IO) {
                                        val err = WineSessionLauncher.runExe(context, c, c.exe)
                                        withContext(Dispatchers.Main) { err?.let { toast(context, it) } }
                                    }
                                } else runFor = c
                            }
                        )
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }

        // ===== FAB ＋ 新建容器（winlator 风格右下角） =====
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp)
                .size(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(listOf(LinShellTheme.blue, LinShellTheme.purple))
                )
                .clickable { ContainerUiState.openEditor(null) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, "新建容器", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }

    // ---- 长按操作面板 ----
    actionsFor?.let { c ->
        ContainerActionsSheet(
            container = c,
            onDismiss = { actionsFor = null },
            onRun = {
                actionsFor = null
                if (c.exe.isNotBlank()) {
                    cs.launch(Dispatchers.IO) {
                        val err = WineSessionLauncher.runExe(context, c, c.exe)
                        withContext(Dispatchers.Main) { err?.let { toast(context, it) } }
                    }
                } else runFor = c
            },
            onRunProgram = { actionsFor = null; runFor = c },
            onInstallPrefix = {
                actionsFor = null
                cs.launch(Dispatchers.IO) {
                    val err = WineSessionLauncher.initializePrefix(context, c)
                    withContext(Dispatchers.Main) {
                        toast(context, err ?: "前缀构建已开始：${c.name}")
                    }
                }
            },
            onEdit = { actionsFor = null; ContainerUiState.openEditor(c.name) },
            onRename = { actionsFor = null; renameFor = c },
            onSetDefault = {
                actionsFor = null
                ContainerManager.setDefault(c.name)
                refresh()
            },
            onDelete = { actionsFor = null; confirmRemove = c }
        )
    }

    // ---- 运行程序对话框 ----
    runFor?.let { c ->
        RunProgramDialog(
            container = c,
            onRun = { exe, args ->
                runFor = null
                cs.launch(Dispatchers.IO) {
                    val err = WineSessionLauncher.runExe(context, c, exe, args)
                    withContext(Dispatchers.Main) { err?.let { toast(context, it) } }
                }
            },
            onDismiss = { runFor = null }
        )
    }

    // ---- 重命名 ----
    renameFor?.let { c ->
        RenameSheet(
            container = c,
            onDone = { newName ->
                renameFor = null
                if (newName != null && newName != c.name) {
                    cs.launch(Dispatchers.IO) {
                        val err = ContainerManager.rename(c.name, newName)
                        withContext(Dispatchers.Main) {
                            err?.let { toast(context, it) } ?: refresh()
                        }
                    }
                }
            }
        )
    }

    // ---- 删除确认 ----
    confirmRemove?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("删除容器", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将删除容器「${c.name}」及其 Wine 前缀（C 盘数据、注册表、已装程序），" +
                        "此操作不可恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    cs.launch(Dispatchers.IO) {
                        RootfsLauncher.clearPrefixLog(c.name)
                        val err = ContainerManager.delete(c.name)
                        withContext(Dispatchers.Main) {
                            err?.let { toast(context, it) } ?: run {
                                toast(context, "已删除 ${c.name}")
                                refresh()
                            }
                        }
                    }
                }) { Text("删除", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("取消") }
            },
            containerColor = Color(0xFF1A2233)
        )
    }

    // ---- 容器体检 ----
    if (showDoctor) {
        com.linbox.apps.containers.DoctorDialog(onDismiss = { showDoctor = false })
    }
}

/** winlator 风格容器卡片。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContainerCard(
    container: ContainerData,
    isDefault: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onQuickRun: () -> Unit
) {
    val wineLabel = container.wine.ifBlank { "默认 Wine" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LinShellTheme.card)
            .border(1.dp, LinShellTheme.cardBorder, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.linearGradient(listOf(LinShellTheme.blue, LinShellTheme.purple))
                ),
            contentAlignment = Alignment.Center
        ) { Text("📦", fontSize = 21.sp) }
        Spacer(Modifier.width(12.dp))

        // 信息
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    container.name,
                    color = LinShellTheme.textPrimary,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isDefault) {
                    Spacer(Modifier.width(6.dp))
                    Text("默认", color = LinShellTheme.blue, fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(LinShellTheme.blue.copy(alpha = 0.16f))
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
                if (running) {
                    Spacer(Modifier.width(6.dp))
                    Text("● 运行中", color = Color(0xFF4ADE80), fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x264ADE80))
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    append(wineLabel)
                    append(" · ")
                    append(container.backend.label)
                    append(" · ")
                    append(if (container.screen == "native") "跟随窗口" else container.screen)
                },
                color = LinShellTheme.textSecondary,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }

        // 快速启动
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(LinShellTheme.blue.copy(alpha = 0.16f))
                .clickable(onClick = onQuickRun),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PlayArrow, "启动", tint = LinShellTheme.blue,
                modifier = Modifier.size(22.dp))
        }
    }
}

// ===========================================================================
// 长按操作面板（安卓底部弹层）
// ===========================================================================

/** 长按操作面板：底部圆角卡 + 半透明遮罩，展开动画。 */
@Composable
private fun ContainerActionsSheet(
    container: ContainerData,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
    onRunProgram: () -> Unit,
    onInstallPrefix: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF2172030))
                .border(1.dp, LinShellTheme.cardBorder, RoundedCornerShape(22.dp))
                .clickable(enabled = false) { /* 拦截穿透 */ }
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                container.name,
                color = LinShellTheme.textPrimary,
                fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "选择操作",
                color = LinShellTheme.textSecondary, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            SheetAction("▶", "启动容器", LinShellTheme.blue, onRun)
            SheetAction("🗂", "运行程序（选 exe）", LinShellTheme.blue, onRunProgram)
            SheetAction("🧱", "安装 / 重建前缀", Color(0xFFF6C454), onInstallPrefix)
            SheetAction("✏", "编辑容器", LinShellTheme.purple, onEdit)
            SheetAction("🏷", "重命名", Color(0xFF9AD8F5), onRename)
            SheetAction("⭐", "设为默认容器", Color(0xFFF6C454), onSetDefault)
            // 快捷方式（系统启动器固定）
            SheetAction("📌", "固定到启动器", Color(0xFF8FD6FF)) {
                val ok = com.linbox.apps.containers.GuanliSupport.Shortcuts
                    .pinRunContainer(context, container, container.exe)
                if (!ok) toast(context, "本设备/启动器不支持固定快捷方式")
                onDismiss()
            }
            SheetAction("🗑", "删除容器", Color(0xFFFF6B6B), onDelete)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SheetAction(emoji: String, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 14.sp) }
        Spacer(Modifier.width(12.dp))
        Text(label, color = LinShellTheme.textPrimary, fontSize = 14.sp)
    }
}

// ===========================================================================
// 运行程序对话框（exe + 参数）
// ===========================================================================

@Composable
private fun RunProgramDialog(
    container: ContainerData,
    onRun: (exe: String, args: String) -> Unit,
    onDismiss: () -> Unit
) {
    var exe by remember { mutableStateOf(container.exe) }
    var args by remember { mutableStateOf(container.uargs) }
    var savingAsDefault by remember { mutableStateOf(false) }
    val cs = rememberCoroutineScope()

    // 文件管理器选择 exe 的回投
    FilePickListenerEffect(targetApp = "containers_run") { path ->
        if (path.endsWith(".exe", true) || path.endsWith(".bat", true) ||
            path.endsWith(".msi", true) || path.endsWith(".cmd", true)
        ) exe = path
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行程序 · ${container.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = exe,
                    onValueChange = { exe = it },
                    label = { Text("程序路径（exe/bat/msi）") },
                    placeholder = { Text("/storage/emulated/0/Download/game/game.exe") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                OutlinedButton(onClick = {
                    ShellController.showFiles(pickMode = "exe", targetApp = "containers_run")
                }) { Text("浏览选择程序", fontSize = 12.sp) }
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text("命令行参数（可空）") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = savingAsDefault,
                        onCheckedChange = { savingAsDefault = it }
                    )
                    Text("保存为容器的默认程序", fontSize = 12.sp,
                        color = LinShellTheme.textSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = exe.isNotBlank(),
                onClick = {
                    if (savingAsDefault && exe != container.exe) {
                        cs.launch(Dispatchers.IO) {
                            ContainerManager.update(container.copy(exe = exe, uargs = args))
                        }
                    }
                    onRun(exe, args)
                }
            ) { Text("运行", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Color(0xFF1A2233)
    )
}



// ===========================================================================
// 重命名
// ===========================================================================

@Composable
private fun RenameSheet(
    container: ContainerData,
    onDone: (String?) -> Unit
) {
    var name by remember { mutableStateOf(container.name) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("重命名容器", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                label = { Text("新名称") }
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name != container.name,
                onClick = { onDone(name.trim()) }
            ) { Text("确定", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("取消") } },
        containerColor = Color(0xFF1A2233)
    )
}

// ===========================================================================
// 前缀构建进度卡（动态进度条 + 显示/隐藏日志开关，默认隐藏）
// ===========================================================================

/**
 * 前缀构建进度卡（用户："容器构建前缀时加入动态进程条，默认不显示日志，
 * 进程条旁边加个显示和隐藏日志"）：
 * - 动态进度：不确定进度条（wineboot 无百分比语义）+ 渐变流动动画 +
 *   阶段文本（根据日志关键词推断阶段）+ 已耗时；
 * - 日志：默认隐藏，进度条旁按钮切换显示/隐藏；
 * - 构建完成自动消失（prefixBuildFlow → null）。
 */
@Composable
internal fun PrefixBuildCard(
    containerName: String,
    expandedLog: Boolean,
    onToggleLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val log by produceState(RootfsLauncher.prefixLogOf(containerName), containerName) {
        while (true) {
            delay(600)
            value = RootfsLauncher.prefixLogOf(containerName)
        }
    }
    val buildingNow = RootfsLauncher.prefixBuildFlow.value == containerName
    val elapsed by produceState(0L, containerName) {
        val start = System.currentTimeMillis()
        while (true) {
            delay(1000)
            value = (System.currentTimeMillis() - start) / 1000
        }
    }

    // 阶段推断（按日志关键词，从后往前找）
    val stage = remember(log) { inferPrefixStage(log) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF16233A))
            .border(1.dp, LinShellTheme.blue.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
                color = LinShellTheme.blue
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (buildingNow) "正在构建前缀 · $containerName"
                    else "前缀构建中 · $containerName",
                    color = LinShellTheme.textPrimary,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "$stage · ${elapsed / 60}:${"%02d".format(elapsed % 60)}",
                    color = LinShellTheme.textSecondary, fontSize = 11.sp
                )
            }
            // 显示/隐藏日志开关（默认隐藏）
            Text(
                if (expandedLog) "隐藏日志 ▴" else "显示日志 ▾",
                color = LinShellTheme.blue, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(LinShellTheme.blue.copy(alpha = 0.14f))
                    .clickable(onClick = onToggleLog)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // 动态进度条：渐变流动
        val transition = rememberInfiniteTransition(label = "prefixBar")
        val shift by transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "shift"
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x22FFFFFF))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction = 0.34f + 0.10f * kotlin.math.sin(shift * Math.PI.toFloat() * 2).coerceAtLeast(0f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(LinShellTheme.blue, LinShellTheme.purple, LinShellTheme.blue)
                        )
                    )
            )
        }

        // 日志区（AnimatedVisibility 展开动画；默认隐藏）
        AnimatedVisibility(
            visible = expandedLog,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(160))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE60D1420))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                SelectionContainer {
                    Text(
                        log.ifBlank { "（暂无输出，wineboot 启动中…）" },
                        color = Color(0xFF9FE29F),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

/** 从前缀构建日志推断当前阶段（关键词从新到旧匹配）。 */
private fun inferPrefixStage(log: String): String {
    val l = log.lineSequence().toList()
    for (i in l.indices.reversed()) {
        val line = l[i]
        return when {
            line.contains("wine: configuration", true) ||
                line.contains("prefix has been updated", true) -> "即将完成"
            line.contains("wineboot", true) -> "wineboot 初始化"
            line.contains("reg", true) -> "写入注册表"
            line.contains("dxvk", true) -> "解压 DXVK"
            line.contains("extract", true) || line.contains("tar", true) -> "解压组件"
            line.isNotBlank() -> "构建中"
            else -> continue
        }
    }
    return "准备环境"
}

/** [com.linbox.apps.filemanager.FilePickBus] 的 Compose 封装：组合期内监听文件选择回投。 */
@Composable
internal fun FilePickListenerEffect(targetApp: String, onPick: (String) -> Unit) {
    val current by rememberUpdatedState(onPick)
    DisposableEffect(targetApp) {
        val un = com.linbox.apps.filemanager.FilePickBus.listen(targetApp, "") { current(it) }
        onDispose { un() }
    }
}\n
// ===========================================================================
// 容器编辑页（winlator 同款表单：新建 / 编辑共用）
// ===========================================================================

/**
 * winlator 风格容器编辑页：
 * - 新建：名称 + 常用配置（Wine 版本 / 屏幕尺寸 / 显示模式 / 后端 /
 *   预设 / 图形驱动…），底部「创建容器」大按钮；
 * - 编辑：同表单回填现值，底部「保存修改」；另提供 启动 / 安装前缀 /
 *   更多动作；
 * - 保存 = ContainerManager.create/update（container.conf 与终端 CLI 同源）。
 */
@Composable
private fun ContainerEditorScreen() {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()
    val isNew = ContainerUiState.creating
    val editingName = ContainerUiState.editingName

    // ---- 数据加载 ----
    var original by remember { mutableStateOf<ContainerData?>(null) }
    var wines by remember { mutableStateOf(listOf<com.linbox.apps.containers.WineInstall>()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(editingName) {
        withContext(Dispatchers.IO) {
            original = editingName?.let { ContainerManager.get(it) }
            wines = ContainerManager.listWines()
        }
        loading = false
    }

    // ---- 表单状态（回填现值） ----
    var name by remember(original) { mutableStateOf(original?.name ?: "") }
    var wine by remember(original) { mutableStateOf(original?.wine ?: "") }
    var screen by remember(original) { mutableStateOf(original?.screen ?: "native") }
    var dmode by remember(original) { mutableStateOf(original?.dmode ?: DisplayMode.OFF) }
    var backend by remember(original) { mutableStateOf(original?.backend ?: ContainerBackend.AUTO) }
    var preset by remember(original) { mutableStateOf(original?.preset ?: Box64Preset.PERFORMANCE) }
    var gallium by remember(original) { mutableStateOf(original?.gallium ?: GalliumDriver.AUTO.id) }
    var dxvk by remember(original) { mutableStateOf(original?.dxvk ?: ContainerData.DXVK_AUTO) }
    var lang by remember(original) { mutableStateOf(original?.lang ?: ContainerLangs.DEFAULT) }
    var frame by remember(original) { mutableStateOf(original?.frame ?: "0") }
    var winver by remember(original) { mutableStateOf(original?.winver ?: WindowsVersions.DEFAULT_ID) }
    var audio by remember(original) { mutableStateOf(original?.audio ?: com.linbox.apps.containers.ContainerAudio.PULSE) }
    var cores by remember(original) { mutableStateOf(original?.cores ?: "") }
    var dpi by remember(original) { mutableStateOf(original?.dpi ?: ContainerData.DPI_DEFAULT) }
    var exe by remember(original) { mutableStateOf(original?.exe ?: "") }
    var uargs by remember(original) { mutableStateOf(original?.uargs ?: "") }
    var env by remember(original) { mutableStateOf(original?.env ?: "") }
    var flags by remember(original) { mutableStateOf(original?.flagSet ?: emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // exe 选择回投
    FilePickListenerEffect(targetApp = "container_editor") { path ->
        if (path.endsWith(".exe", true) || path.endsWith(".bat", true) ||
            path.endsWith(".msi", true) || path.endsWith(".cmd", true)
        ) exe = path
    }

    fun buildData(): ContainerData? {
        val n = name.trim()
        if (n.isEmpty()) { error = "请输入容器名称"; return null }
        if (n.contains('/') || n.contains('\\')) { error = "名称不能包含 / 或 \\"; return null }
        val base = original ?: ContainerData(name = n)
        return base.copy(
            name = n,
            wine = wine,
            screen = screen,
            dmode = dmode,
            backend = backend,
            preset = preset,
            gallium = gallium,
            dxvk = dxvk,
            lang = lang,
            frame = frame,
            winver = winver,
            audio = audio,
            cores = cores,
            dpi = dpi,
            exe = exe,
            uargs = uargs,
            env = env,
            flags = ContainerFlags.serialize(flags)
        )
    }

    fun save() {
        val data = buildData() ?: return
        saving = true
        cs.launch(Dispatchers.IO) {
            // 编辑模式保留原创建时间（buildData 从 original 拷贝已含，此处显式）
            val toSave = data.copy(created = original?.created ?: data.created)
            val err = if (isNew) ContainerManager.create(toSave)
            else ContainerManager.update(toSave)
            withContext(Dispatchers.Main) {
                saving = false
                if (err != null) {
                    error = err
                } else {
                    toast(context, if (isNew) "容器已创建：${toSave.name}" else "已保存")
                    if (isNew) {
                        // 新建后立即构建前缀（winlator 行为：创建即 wineboot）
                        WineSessionLauncher.initializePrefix(context, toSave)
                    }
                    ContainerUiState.openList()
                }
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        ContainerUiState.openList()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(LinShellTheme.bg, Color(0xFF0B0F1C))))
    ) {
        Column(Modifier.fillMaxSize()) {

            // ===== 安卓 app bar =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(LinShellTheme.card)
                        .clickable { ContainerUiState.openList() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                        tint = LinShellTheme.textPrimary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isNew) "新建容器" else "编辑容器",
                        color = LinShellTheme.textPrimary,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isNew) "Wine 容器 · 创建后自动构建前缀"
                        else original?.name ?: "",
                        color = LinShellTheme.textSecondary, fontSize = 11.sp
                    )
                }
                if (!isNew) {
                    // 运行快捷钮
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(LinShellTheme.blue.copy(alpha = 0.16f))
                            .clickable {
                                val data = buildData() ?: return@clickable
                                if (data.exe.isNotBlank()) {
                                    cs.launch(Dispatchers.IO) {
                                        val err = WineSessionLauncher.runExe(context, data, data.exe)
                                        withContext(Dispatchers.Main) { err?.let { toast(context, it) } }
                                    }
                                } else {
                                    toast(context, "先在下方设置默认程序（exe）")
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, "启动",
                            tint = LinShellTheme.blue, modifier = Modifier.size(22.dp))
                    }
                }
            }

            // ===== 表单 =====
            if (loading) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LinShellTheme.blue)
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EditorSection("基本信息") {
                        EditorTextField("容器名称", name, { name = it; error = null }, "如 Fallout3")
                        EditorDropdown(
                            "Wine 版本",
                            selected = wine.ifBlank { "默认（/usr/opt/wine）" },
                            options = listOf("默认（/usr/opt/wine）") + wines.map { it.displayLabel },
                            onSelect = { label ->
                                wine = wines.firstOrNull { it.displayLabel == label }?.name ?: ""
                            }
                        )
                        if (wines.isEmpty()) {
                            EditorNote("未发现已安装 wine —— 可在「高级设置 → Wine 安装」先装")
                        }
                        EditorDropdown(
                            "屏幕尺寸",
                            selected = if (screen == "native") "跟随窗口（全屏）" else screen,
                            options = listOf("跟随窗口（全屏）") +
                                ContainerData.SCREEN_CHOICES.filter { it != "native" },
                            onSelect = {
                                screen = if (it == "跟随窗口（全屏）") "native" else it
                            }
                        )
                        EditorDropdown(
                            "显示模式",
                            selected = dmode.label,
                            options = DisplayMode.entries.map { it.label },
                            onSelect = { dmode = DisplayMode.entries.first { m -> m.label == it } }
                        )
                    }

                    EditorSection("兼容性") {
                        EditorDropdown(
                            "CPU 后端",
                            selected = backend.label,
                            options = ContainerBackend.entries.map { it.label },
                            onSelect = { backend = ContainerBackend.entries.first { b -> b.label == it } },
                            note = backend.desc
                        )
                        EditorDropdown(
                            "Box64 预设",
                            selected = preset.label,
                            options = Box64Preset.entries.map { it.label },
                            onSelect = { preset = Box64Preset.entries.first { p -> p.label == it } },
                            note = preset.desc
                        )
                        EditorDropdown(
                            "Windows 版本",
                            selected = WindowsVersions.fromId(winver).label,
                            options = WindowsVersions.ALL.map { it.label },
                            onSelect = { winver = WindowsVersions.ALL.first { v -> v.label == it }.id }
                        )
                        EditorDropdown(
                            "语言",
                            selected = ContainerLangs.displayOf(lang),
                            options = ContainerLangs.DISPLAY,
                            onSelect = { lang = ContainerLangs.valueOf(it) }
                        )
                    }

                    EditorSection("图形") {
                        EditorDropdown(
                            "渲染器",
                            selected = GalliumDriver.fromId(gallium).label,
                            options = GalliumDriver.entries.map { it.label },
                            onSelect = { gallium = GalliumDriver.entries.first { g -> g.label == it }.id }
                        )
                        EditorDropdown(
                            "DXVK",
                            selected = if (dxvk == ContainerData.DXVK_OFF) "关闭" else "开启（自动注入）",
                            options = listOf("开启（自动注入）", "关闭"),
                            onSelect = {
                                dxvk = if (it == "关闭") ContainerData.DXVK_OFF else ContainerData.DXVK_AUTO
                            },
                            note = "开启 = 前缀构建时自动解压 assets 内置 DXVK 到 C:\\windows"
                        )
                        EditorDropdown(
                            "帧数限制",
                            selected = if (frame == "0") "不限制" else "$frame fps",
                            options = listOf("不限制", "60 fps", "120 fps"),
                            onSelect = { frame = when (it) { "60 fps" -> "60"; "120 fps" -> "120"; else -> "0" } }
                        )
                    }

                    EditorSection("声音") {
                        EditorDropdown(
                            "音频",
                            selected = audio.label,
                            options = com.linbox.apps.containers.ContainerAudio.entries.map { it.label },
                            onSelect = { audio = com.linbox.apps.containers.ContainerAudio.entries.first { a -> a.label == it } }
                        )
                    }

                    EditorSection("高级") {
                        EditorTextField("CPU 核心（如 0-3，空 = 全核心）", cores, { cores = it }, "0-3")
                        EditorDropdown(
                            "DPI 缩放",
                            selected = if (dpi == "96") "默认 96" else dpi,
                            options = listOf("默认 96", "120", "144", "168", "192"),
                            onSelect = { dpi = if (it == "默认 96") "96" else it }
                        )
                        // 默认程序
                        Column {
                            EditorTextField("默认程序（exe 路径）", exe, { exe = it },
                                "/storage/emulated/0/Download/game/game.exe")
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                ShellController.showFiles(pickMode = "exe", targetApp = "container_editor")
                            }) { Text("浏览选择程序", fontSize = 12.sp) }
                        }
                        EditorTextField("启动参数（追加在程序后）", uargs, { uargs = it }, "-windowed")
                        EditorTextField(
                            "环境变量（K=V 空格分隔）", env, { env = it },
                            "MESA_GL_VERSION_OVERRIDE=4.6"
                        )
                        // 高级启动开关
                        Text(
                            "启动开关",
                            color = LinShellTheme.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        FlagFlow(
                            all = listOf(
                                ContainerFlags.INDEPENDENT to "独立窗口",
                                ContainerFlags.OPENGL to "OpenGL 修复",
                                ContainerFlags.UNITY to "Unity 修复",
                                ContainerFlags.DX11 to "强制 DX11",
                                ContainerFlags.F5TASK to "F5 任务栏",
                                ContainerFlags.CLOSESERVICE to "关闭服务",
                                ContainerFlags.DEBUG to "调试输出"
                            ),
                            selected = flags,
                            onToggle = { f, on ->
                                flags = if (on) flags + f else flags - f
                            }
                        )
                    }

                    if (!isNew) {
                        EditorSection("更多动作") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val data = buildData() ?: return@OutlinedButton
                                    cs.launch(Dispatchers.IO) {
                                        val err = WineSessionLauncher.initializePrefix(context, data)
                                        withContext(Dispatchers.Main) {
                                            toast(context, err ?: "前缀构建已开始")
                                        }
                                    }
                                }, modifier = Modifier.weight(1f)) {
                                    Text("安装/重建前缀", fontSize = 12.sp)
                                }
                                OutlinedButton(onClick = {
                                    ContainerUiState.openAdvanced()
                                }, modifier = Modifier.weight(1f)) {
                                    Text("高级设置", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    if (error != null) {
                        Text(error ?: "", color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(84.dp))
                }
            }
        }

        // ===== 底部保存按钮（winlator 样式大按钮） =====
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xE60D1420))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Button(
                onClick = { save() },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LinShellTheme.blue
                )
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp, color = Color.White
                    )
                } else {
                    Text(
                        if (isNew) "创建容器" else "保存修改",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ===========================================================================
// 编辑页组件（winlator 表单风格）
// ===========================================================================

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LinShellTheme.card)
            .border(1.dp, LinShellTheme.cardBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            title,
            color = LinShellTheme.blue,
            fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun EditorTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = ""
) {
    Column {
        Text(label, color = LinShellTheme.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, fontSize = 11.sp, color = Color(0x59FFFFFF)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
    }
}

@Composable
private fun EditorDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    note: String? = null
) {
    Column {
        Text(label, color = LinShellTheme.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selected,
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
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt, fontSize = 13.sp) },
                        onClick = {
                            onSelect(opt)
                            expanded = false
                        }
                    )
                }
            }
        }
        if (note != null) {
            Spacer(Modifier.height(3.dp))
            Text(note, color = Color(0x66FFFFFF), fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun EditorNote(text: String) {
    Text(text, color = Color(0x66FFFFFF), fontSize = 10.sp, lineHeight = 13.sp)
}

/** 启动开关流式多选芯片。 */
@Composable
private fun FlagFlow(
    all: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        all.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { (flag, label) ->
                    val on = flag in selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (on) LinShellTheme.blue.copy(alpha = 0.22f)
                                else Color(0x14FFFFFF)
                            )
                            .border(
                                1.dp,
                                if (on) LinShellTheme.blue else Color(0x22FFFFFF),
                                RoundedCornerShape(9.dp)
                            )
                            .clickable { onToggle(flag, !on) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (on) Color.White else Color(0x99FFFFFF),
                            fontSize = 11.sp, maxLines = 1
                        )
                    }
                }
                // 补齐占位（3 列对齐）
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ===========================================================================
// 容器高级设置页（安卓页承载原有选项卡：DY / FEX / Box / Wine 安装 /
// MangoHud / 生态配置 —— 功能零删减）
// ===========================================================================

@Composable
private fun ContainerAdvancedScreen() {
    var tick by remember { mutableIntStateOf(0) }
    var containers by remember { mutableStateOf<List<ContainerData>>(emptyList()) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { containers = ContainerManager.list() }
    }
    fun refresh() { tick++ }
    val sel = containers.firstOrNull { it.name == ContainerUiState.editingName }
        ?: containers.firstOrNull()
    var mangoFor by remember { mutableStateOf<ContainerData?>(null) }

    androidx.activity.compose.BackHandler(enabled = true) {
        ContainerUiState.advancedOpen = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LinShellTheme.bg)
    ) {
        // ===== 安卓 app bar =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(LinShellTheme.card)
                    .clickable { ContainerUiState.advancedOpen = false },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = LinShellTheme.textPrimary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "容器高级设置",
                    color = LinShellTheme.textPrimary,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "DY · FEX · Box64 · Wine 安装 · MangoHud · 生态",
                    color = LinShellTheme.textSecondary, fontSize = 11.sp
                )
            }
        }

        // ===== 选项卡内容（滚动页 + 分组卡片） =====
        var tab by remember { mutableIntStateOf(0) }
        val tabs = listOf("⚡ DY", "🧬 FEX", "📦 Box", "🍷 Wine", "📊 Hud", "🌐 生态")

        // winlator 风格分段选择
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x14FFFFFF))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            tabs.forEachIndexed { i, label ->
                val on = tab == i
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (on) LinShellTheme.blue else Color.Transparent)
                        .clickable { tab = i }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (on) Color.White else Color(0x99FFFFFF),
                        fontSize = 11.sp, maxLines = 1,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // 选项卡沿用的 gdp/gsp 缩放（窄屏适配）
        val scale = 1f
        androidx.compose.runtime.CompositionLocalProvider(
            com.linbox.apps.containers.LocalGuanliScale provides scale,
            com.linbox.apps.containers.LocalGuanliFontScale provides 1f
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (tab) {
                    0 -> com.linbox.apps.containers.DyConfigTab(sel = sel, onSaved = { refresh() })
                    1 -> com.linbox.apps.containers.FexConfigTab(onSaved = { refresh() })
                    2 -> com.linbox.apps.containers.BoxConfigTab()
                    3 -> com.linbox.apps.containers.WineInstallTab(onRefresh = { refresh() })
                    4 -> com.linbox.apps.containers.MangoHudTab(
                        containers = containers,
                        sel = sel,
                        onMango = { mangoFor = it },
                        onSetMango = { c, cfg ->
                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                ContainerManager.update(c.copy(mangohud = cfg))
                                withContext(Dispatchers.Main) { refresh() }
                            }
                        }
                    )
                    5 -> com.linbox.apps.containers.OptEcoConfigTab()
                }
                Spacer(Modifier.height(60.dp))
            }
        }
    }
}
