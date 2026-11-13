package com.linbox.shellui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.linbox.apps.containers.ContainerAudio
import com.linbox.apps.containers.ContainerBackend
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerFlags
import com.linbox.apps.containers.ContainerLangs
import com.linbox.apps.containers.ContainerManager
import com.linbox.apps.containers.DisplayMode
import com.linbox.apps.containers.WineInstall
import com.linbox.apps.containers.WineSessionLauncher
import com.linbox.apps.containers.WindowsVersions
import com.linbox.apps.containers.launcher.RootfsLauncher
import com.linbox.core.shell.ShellController
import com.linbox.core.shell.ShellController.Screen
import com.linbox.core.theme.LinShellTheme
import com.linbox.data.db.entity.ShortcutEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v6.0 游戏库（完全重制）：**游戏快捷方式库**，不是容器列表。
 *
 * 用户诉求："游戏库不是容器库，游戏库是添加游戏快捷方式的不是容器" ——
 * 一条游戏快捷方式 = （显示名 + exe 路径 + 所属容器 + 可选卡片图 +
 * 游戏专属启动配置），点击即用所属容器运行该 exe；容器本体管理全部在
 * 「容器」页。
 *
 * 存储：Room shortcuts 表 type=4（label=游戏名 / target=容器名 /
 * launchArgs=exe 路径 / iconAsset=卡片图片路径 / config=启动配置快照）。
 *
 * v6.2（用户："游戏快捷方式创建和启动缺少很多设置(包括分辨率dxvkd3d驱动
 * 等等配置)以及启动参数，而且根本无法正常调用启动"）：
 * - 添加/编辑改为**全屏编辑页**（不再是小弹窗）：基本信息 + 五选项卡
 *  （基本 / 启动参数 / 图形驱动 / CPU核心 / 函数库）—— 图形驱动直接
 *   复用 wineguanli GraphicsDriverTab（Vulkan Turnip/Wrapper/专有包/
 *   系统专有 + Zink/DXVK/WineD3D/FEXCore 全部从 /opt 扫描选择）；
 * - [GameLaunchConfig]：游戏专属启动配置快照（JSON，shortcuts.config 列）。
 *   空 = 跟随容器当前配置；非空 = 启动时以容器为基底合并（会话级，
 *   不回写 container.conf）；
 * - 启动反馈：成功 / 失败都有 toast（旧版成功时无任何提示，"按了没反应"）；
 *   启动参数（uargs）/ 环境变量 / 启动开关全部生效。
 *
 * v6.1 弹窗状态存活：草稿字段全部在 [GameLibraryUiState] 单例 ——
 * 「浏览选择程序 / 选择卡片图片」跳去文件管理器再回来，已填内容与
 * 编辑页原样保留，选择结果经 FilePickBus 待投递信箱回填。
 */

/** 游戏快捷方式（Room shortcuts 表 type=4 的映射）。 */
data class GameShortcut(
    val id: Long,
    val label: String,
    val containerName: String,
    val exe: String,
    val cardImage: String,
    /** v6.2：游戏专属启动配置快照（[GameLaunchConfig] JSON；空 = 跟随容器）。 */
    val config: String = ""
)

object GameShortcuts {
    const val TYPE = 4

    suspend fun list(app: LinBoxApp): List<GameShortcut> =
        app.database.shortcutDao().getAll()
            .filter { it.type == TYPE }
            .map { GameShortcut(it.id, it.label, it.target, it.launchArgs, it.iconAsset, it.config) }

    suspend fun upsert(app: LinBoxApp, s: GameShortcut) {
        val dao = app.database.shortcutDao()
        val entity = ShortcutEntity(
            id = if (s.id > 0) s.id else 0,
            label = s.label,
            iconAsset = s.cardImage,
            type = TYPE,
            target = s.containerName,
            launchArgs = s.exe,
            sortOrder = 0,
            config = s.config
        )
        if (s.id > 0) dao.update(entity) else dao.insert(entity)
    }

    suspend fun delete(app: LinBoxApp, s: GameShortcut) {
        app.database.shortcutDao().deleteById(s.id)
    }
}

/**
 * v6.2：游戏专属启动配置快照（JSON）。
 *
 * - [snapshotOf]：把一份完整 [ContainerData] 的**全部配置字段**（不含
 *   name/created/exe/cardImage 等容器身份字段）序列化为 JSON；
 * - [applyTo]：以容器为基底，JSON 里出现的字段覆盖基底（缺键 = 跟随
 *   容器现值 —— 旧数据 / 部分字段的快照同样可解析）。
 *
 * 语义：游戏快捷方式的 config 为空串 = 完全跟随容器当前配置（容器页
 * 改了本游戏就跟着变）；一旦在游戏编辑页动过任何高级选项，保存的就是
 * 全量快照（此后容器页的修改不影响本游戏 —— 每游戏独立配置）。
 */
object GameLaunchConfig {

    fun snapshotOf(d: ContainerData): String = JSONObject().apply {
        put("backend", d.backend.id)
        put("preset", d.preset.id)
        put("screen", d.screen)
        put("dmode", d.dmode.id)
        put("gallium", d.gallium)
        put("env", d.env)
        put("dxvk", d.dxvk)
        put("dxoverrides", d.dxoverrides)
        put("wine", d.wine)
        put("lang", d.lang)
        put("audio", d.audio.id)
        put("frame", d.frame)
        put("cores", d.cores)
        put("dsound", d.dsound)
        put("dshow", d.dshow)
        put("dpi", d.dpi)
        put("winver", d.winver)
        put("mangohud", d.mangohud)
        put("flags", d.flags)
        put("turnip", d.turnip)
        put("zink", d.zink)
        put("wined3d", d.wined3d)
        put("fexcore", d.fexcore)
        put("wrapper", d.wrapper)
        put("uargs", d.uargs)
    }.toString()

    /** JSON 快照合并进容器基底（缺键 / 空 JSON / 解析失败 = 原样基底）。 */
    fun applyTo(base: ContainerData, json: String?): ContainerData {
        if (json.isNullOrBlank()) return base
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            return base
        }
        return base.copy(
            backend = ContainerBackend.fromId(o.optString("backend", base.backend.id)),
            preset = com.linbox.apps.containers.Box64Preset.fromId(o.optString("preset", base.preset.id)),
            screen = o.optString("screen", base.screen),
            dmode = DisplayMode.fromId(o.optString("dmode", base.dmode.id)),
            gallium = o.optString("gallium", base.gallium),
            env = o.optString("env", base.env),
            dxvk = o.optString("dxvk", base.dxvk),
            dxoverrides = o.optString("dxoverrides", base.dxoverrides),
            wine = o.optString("wine", base.wine),
            lang = o.optString("lang", base.lang),
            audio = ContainerAudio.fromId(o.optString("audio", base.audio.id)),
            frame = o.optString("frame", base.frame),
            cores = o.optString("cores", base.cores),
            dsound = o.optString("dsound", base.dsound),
            dshow = o.optString("dshow", base.dshow),
            dpi = o.optString("dpi", base.dpi),
            winver = o.optString("winver", base.winver),
            mangohud = o.optString("mangohud", base.mangohud),
            flags = o.optString("flags", base.flags),
            turnip = o.optString("turnip", base.turnip),
            zink = o.optString("zink", base.zink),
            wined3d = o.optString("wined3d", base.wined3d),
            fexcore = o.optString("fexcore", base.fexcore),
            wrapper = o.optString("wrapper", base.wrapper),
            uargs = o.optString("uargs", base.uargs)
        )
    }
}

/**
 * v6.1：游戏库跨页面导航存活的 UI 状态（v6.2 编辑页化后沿用）。
 *
 * 弹窗开关 + 草稿字段全部放进本单例（Compose 快照状态，进程内存活）。
 * 文件管理器选完 → ShellController.closePage() 回到游戏库 → 编辑页
 * 原样重现 → FilePickBus 待投递信箱把选择结果回填进草稿。
 */
object GameLibraryUiState {
    /** 添加/编辑编辑页是否打开。 */
    var editorOpen by mutableStateOf(false)
    /** 正在编辑的快捷方式（null = 新建）。 */
    var editing by mutableStateOf<GameShortcut?>(null)
    /** 删除确认弹窗目标。 */
    var deleteFor by mutableStateOf<GameShortcut?>(null)

    // ---- 草稿字段（跨导航存活） ----
    var draftName by mutableStateOf("")
    var draftExe by mutableStateOf("")
    var draftContainer by mutableStateOf("")
    var draftCardImage by mutableStateOf("")
    /** v6.2：游戏专属启动配置快照（JSON；空 = 跟随容器）。 */
    var draftConfig by mutableStateOf("")

    /** 打开「添加游戏」（defaultContainer = 第一个容器名）。 */
    fun openNew(defaultContainer: String) {
        editing = null
        draftName = ""
        draftExe = ""
        draftContainer = defaultContainer
        draftCardImage = ""
        draftConfig = ""
        editorOpen = true
    }

    /** 打开「编辑游戏」。 */
    fun openEdit(s: GameShortcut) {
        editing = s
        draftName = s.label
        draftExe = s.exe
        draftContainer = s.containerName
        draftCardImage = s.cardImage
        draftConfig = s.config
        editorOpen = true
    }

    fun closeEditor() {
        editorOpen = false
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameLibraryScreen() {
    val context = LocalContext.current
    val app = LinBoxApp.get()
    val cs = rememberCoroutineScope()
    // v6.1：色板跟随主题（亮/暗实时切换）
    val palette = GlassPalette.current

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

    /**
     * v6.2 启动：合并游戏专属配置（config 空 = 跟随容器现值）→ 原生链路
     * runExe；成功 / 失败都给出可见反馈（旧版成功时无任何提示）。
     */
    fun runShortcut(s: GameShortcut) {
        val base = containers.firstOrNull { it.name == s.containerName }
        if (base == null) {
            toast(context, "容器「${s.containerName}」不存在，请长按游戏卡片编辑")
            return
        }
        // 同步预检先行（rootfs / wine 可解析），错误立刻可见
        val pre = WineSessionLauncher.precheckRun(base)
        if (pre != null) {
            toast(context, pre)
            return
        }
        cs.launch(Dispatchers.IO) {
            val merged = GameLaunchConfig.applyTo(base, s.config)
            val err = WineSessionLauncher.runExe(context, merged, s.exe)
            withContext(Dispatchers.Main) {
                if (err != null) {
                    toast(context, err)
                } else {
                    toast(context, "已在容器「${base.name}」中启动 · ${s.label}\n首次运行需构建前缀，进度见容器页")
                }
            }
        }
    }

    // ---- v6.2：编辑页打开时整页替换网格视图（草稿在单例，跨导航存活） ----
    if (GameLibraryUiState.editorOpen) {
        GameShortcutEditorPage(
            containers = containers,
            onSave = { s ->
                GameLibraryUiState.closeEditor()
                cs.launch(Dispatchers.IO) {
                    GameShortcuts.upsert(app, s)
                    withContext(Dispatchers.Main) { refresh() }
                }
            },
            onDeleteRequest = {
                GameLibraryUiState.editing?.let { GameLibraryUiState.deleteFor = it }
                GameLibraryUiState.closeEditor()
            }
        )
        return
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
                    onClick = {
                        // v6.1：弹窗状态进单例 —— 去文件管理器选完回来不丢
                        GameLibraryUiState.openNew(containers.firstOrNull()?.name ?: "")
                    },
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
                    placeholder = { Text("搜索游戏名 / exe", fontSize = 12.sp, color = palette.textSecondary) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = palette.textPrimary),
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
                            onLongPress = { GameLibraryUiState.openEdit(s) }
                        )
                    }
                }
            }

            // ===== 5. 底部导航 =====
            ShellBottomBar(current = Screen.LIBRARY, palette = palette)
        }
    }

    // ---- 删除确认 ----
    GameLibraryUiState.deleteFor?.let { s ->
        AlertDialog(
            onDismissRequest = { GameLibraryUiState.deleteFor = null },
            title = { Text("移出游戏库", fontWeight = FontWeight.Bold) },
            text = { Text("把「${s.label}」从游戏库移除？（不影响容器与游戏文件）") },
            confirmButton = {
                TextButton(onClick = {
                    GameLibraryUiState.deleteFor = null
                    cs.launch(Dispatchers.IO) {
                        GameShortcuts.delete(app, s)
                        withContext(Dispatchers.Main) { refresh() }
                    }
                }) { Text("移除", color = LinShellTheme.red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { GameLibraryUiState.deleteFor = null }) { Text("取消") }
            },
            containerColor = LinShellTheme.surface
        )
    }
}

// ===========================================================================
// 添加 / 编辑游戏（v6.2 全屏编辑页：完整启动配置）
// ===========================================================================

/**
 * 游戏快捷方式编辑页（v6.2）：
 * - 📋 基本：游戏名 / exe（浏览）/ 容器 / 卡片图 —— 草稿在单例，
 *   去文件管理器选完回来不丢；
 * - 🚀 启动参数：启动参数（uargs）/ 环境变量 / 高级启动开关 /
 *   分辨率（预设 + 自定义）/ 独立窗口 / 帧率 / Wine 版本 / Windows 版本 / 音频；
 * - 🎮 图形驱动：复用 wineguanli GraphicsDriverTab —— Vulkan 驱动
 *   （Turnip / Wrapper / 专有包 / 系统专有 + 版本）+ Zink(/opt/zink) +
 *   DXVK(/opt/dxvk1 + APK 内置) + WineD3D(/opt/vkord3d) + FEXCore
 *   (/opt/fexcore)，全部从 /opt 扫描选择，➕ 安装 zip/tar；
 * - 🖥️ CPU 核心 / 📚 函数库：同容器编辑页（核心勾选 / DLL 加载序 / DPI /
 *   Windows 版本 / 立即应用）。
 *
 * 配置模型：working = 容器基底 + draftConfig 快照（[GameLaunchConfig]）；
 * 任何高级选项的修改即时写回 draftConfig（跨导航存活）。
 */
@Composable
private fun GameShortcutEditorPage(
    containers: List<ContainerData>,
    onSave: (GameShortcut) -> Unit,
    onDeleteRequest: () -> Unit
) {
    val st = GameLibraryUiState
    val editing = st.editing
    val context = LocalContext.current
    val cs = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    // Wine 版本列表（启动参数页 Wine 版本覆盖用）
    var wines by remember { mutableStateOf<List<WineInstall>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { wines = ContainerManager.listWines() }
    }

    // exe 选择回投（文件管理器）—— 发起页离开期间的投递由
    // FilePickBus 待投递信箱保留，本页重新组合时自动送达
    FilePickListenerEffect(targetApp = "game_library_exe") { path ->
        if (path.endsWith(".exe", true) || path.endsWith(".bat", true) ||
            path.endsWith(".msi", true) || path.endsWith(".cmd", true)
        ) {
            st.draftExe = path
            if (st.draftName.isBlank()) {
                st.draftName = path.substringAfterLast('/').substringBeforeLast('.')
            }
            error = null
        }
    }
    // 卡片图片选择回投
    FilePickListenerEffect(targetApp = "game_library_img") { path ->
        st.draftCardImage = path
    }

    // ---- 工作配置：容器基底 + 游戏专属快照 ----
    val base = containers.firstOrNull { it.name == st.draftContainer }
        ?: containers.firstOrNull()
    val working: ContainerData? = remember(st.draftContainer, st.draftConfig, base) {
        base?.let { GameLaunchConfig.applyTo(it, st.draftConfig) }
    }

    /** 任何高级配置修改 → 即时快照写回单例（跨导航存活）。 */
    fun setConfig(d: ContainerData) {
        st.draftConfig = GameLaunchConfig.snapshotOf(d)
    }

    /** 分辨率/窗口模式联动（与容器编辑 applyResolution 同款语义）。 */
    fun applyResolution(resLabel: String, w: ContainerData) {
        val screen = if (resLabel == "跟随窗口（全屏）") "native" else resLabel
        val independent = w.hasFlag(ContainerFlags.INDEPENDENT)
        val dmode = when {
            independent -> DisplayMode.F
            screen == "native" -> DisplayMode.OFF
            else -> DisplayMode.D
        }
        setConfig(w.copy(screen = screen, dmode = dmode))
    }

    fun save() {
        val n = st.draftName.trim()
        when {
            n.isEmpty() -> { error = "请输入游戏名称"; return }
            st.draftExe.isBlank() -> { error = "请选择程序（exe）"; return }
            st.draftContainer.isBlank() -> { error = "请选择容器"; return }
            else -> onSave(
                GameShortcut(
                    id = editing?.id ?: 0,
                    label = n,
                    containerName = st.draftContainer,
                    exe = st.draftExe.trim(),
                    cardImage = st.draftCardImage,
                    config = st.draftConfig
                )
            )
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        GameLibraryUiState.closeEditor()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(LinShellTheme.bg, Color(0xFF0B0F1C))))
    ) {
        Column(Modifier.fillMaxSize()) {

            // ===== app bar =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(LinShellTheme.card)
                        .clickable { GameLibraryUiState.closeEditor() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                        tint = LinShellTheme.textPrimary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (editing == null) "添加游戏" else "编辑游戏",
                        color = LinShellTheme.textPrimary,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (editing == null) "游戏快捷方式 · 完整启动配置（/opt 驱动 / 启动参数）"
                        else editing.label,
                        color = LinShellTheme.textSecondary, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (editing != null) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(21.dp))
                            .background(LinShellTheme.red.copy(alpha = 0.14f))
                            .clickable { onDeleteRequest() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Delete, "删除",
                            tint = LinShellTheme.red, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // ===== 五选项卡 =====
            val tabs = listOf("📋 基本", "🚀 启动参数", "🎮 图形驱动", "🖥️ CPU", "📚 函数库")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LinShellTheme.card)
                    .padding(3.dp)
            ) {
                tabs.forEachIndexed { i, label ->
                    val on = tab == i
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (on) LinShellTheme.blue.copy(alpha = 0.85f)
                                else Color.Transparent
                            )
                            .clickable { tab = i }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (on) Color.White else LinShellTheme.textSecondary,
                            fontSize = 10.sp, maxLines = 1,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // ===== 选项卡内容 =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (tab) {
                    // ---------- 0 基本 ----------
                    0 -> {
                        EditorSection("基本信息") {
                            EditorTextField(
                                "游戏名称", st.draftName,
                                { st.draftName = it; error = null }, "如 Fallout 3"
                            )
                            EditorTextField(
                                "程序（exe / bat / msi）", st.draftExe,
                                { st.draftExe = it; error = null },
                                "/storage/emulated/0/Download/game/game.exe"
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
                                EditorDropdown(
                                    "用哪个容器运行",
                                    selected = st.draftContainer.ifBlank { "请选择容器" },
                                    options = containers.map { it.name },
                                    onSelect = { st.draftContainer = it }
                                )
                            }

                            // 卡片图片
                            if (st.draftCardImage.isNotBlank()) {
                                Text(
                                    "卡片图：${st.draftCardImage}",
                                    color = LinShellTheme.textSecondary, fontSize = 10.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(onClick = {
                                ShellController.showFiles(pickMode = "image", targetApp = "game_library_img")
                            }) { Text("选择卡片图片（可选）", fontSize = 12.sp) }
                        }

                        EditorSection("启动配置状态") {
                            Text(
                                if (st.draftConfig.isBlank())
                                    "跟随容器配置 —— 容器页修改对本游戏即时生效"
                                else "游戏专属配置（已固定）—— 容器页修改不影响本游戏",
                                color = LinShellTheme.textSecondary, fontSize = 11.sp
                            )
                            if (st.draftConfig.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                OutlinedButton(onClick = {
                                    st.draftConfig = ""
                                }) { Text("恢复跟随容器配置", fontSize = 12.sp) }
                            }
                        }
                    }

                    // ---------- 1 启动参数 ----------
                    1 -> {
                        if (working == null) {
                            EditorNote("还没有容器 —— 先到「容器」页创建后再配置启动参数")
                        } else {
                            val w = working
                            EditorSection("启动参数") {
                                EditorTextField(
                                    "启动参数（追加在程序后）", w.uargs,
                                    { setConfig(w.copy(uargs = it)) }, "-windowed -nosound"
                                )
                                EditorTextField(
                                    "环境变量（K=V 空格分隔）", w.env,
                                    { setConfig(w.copy(env = it)) },
                                    "MESA_GL_VERSION_OVERRIDE=4.6"
                                )
                                Text(
                                    "启动开关",
                                    color = LinShellTheme.textSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                FlagFlow(
                                    all = listOf(
                                        ContainerFlags.DX11 to "强制 DX11",
                                        ContainerFlags.OPENGL to "OpenGL 修复",
                                        ContainerFlags.UNITY to "Unity 修复",
                                        ContainerFlags.F5TASK to "F5 任务栏",
                                        ContainerFlags.FEXMODE to "FEX 模式",
                                        ContainerFlags.WOWBOXMODE to "wowbox64",
                                        ContainerFlags.CLOSESERVICE to "关闭服务",
                                        ContainerFlags.DEBUG to "调试输出"
                                    ),
                                    selected = w.flagSet,
                                    onToggle = { f, on ->
                                        val flags = w.flagSet.toMutableSet()
                                        if (on) flags.add(f) else flags.remove(f)
                                        setConfig(w.copy(flags = ContainerFlags.serialize(flags)))
                                    }
                                )
                            }

                            EditorSection("显示") {
                                EditorDropdown(
                                    "分辨率",
                                    selected = if (w.screen == "native") "跟随窗口（全屏）" else w.screen,
                                    options = listOf("跟随窗口（全屏）") +
                                        ContainerData.SCREEN_CHOICES.filter { it != "native" },
                                    onSelect = { applyResolution(it, w) }
                                )
                                EditorTextField(
                                    "自定义分辨率（如 1280x1024）",
                                    if (w.screen == "native") "" else w.screen,
                                    {
                                        // 仅完整 WxH 形态才应用（避免逐字符输入把半截分辨率写进配置）
                                        if (Regex("^\\d{3,4}[xX]\\d{3,4}$").matches(it.trim())) {
                                            applyResolution(it.trim(), w)
                                        }
                                    },
                                    "1280x1024"
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("独立窗口", color = LinShellTheme.textPrimary, fontSize = 13.sp)
                                        Text(
                                            "注册表虚拟桌面（-f），不支持 proton",
                                            color = LinShellTheme.textSecondary, fontSize = 10.sp
                                        )
                                    }
                                    Switch(
                                        checked = w.hasFlag(ContainerFlags.INDEPENDENT),
                                        onCheckedChange = { on ->
                                            val flags = w.flagSet.toMutableSet()
                                            if (on) flags.add(ContainerFlags.INDEPENDENT)
                                            else flags.remove(ContainerFlags.INDEPENDENT)
                                            val dmode = when {
                                                on -> DisplayMode.F
                                                w.screen == "native" -> DisplayMode.OFF
                                                else -> DisplayMode.D
                                            }
                                            setConfig(w.copy(
                                                flags = ContainerFlags.serialize(flags),
                                                dmode = dmode
                                            ))
                                        }
                                    )
                                }
                                EditorDropdown(
                                    "帧数限制",
                                    selected = if (w.frame == "0") "不限制" else "${w.frame} fps",
                                    options = listOf("不限制", "60 fps", "120 fps"),
                                    onSelect = {
                                        setConfig(w.copy(frame = when (it) {
                                            "60 fps" -> "60"; "120 fps" -> "120"; else -> "0"
                                        }))
                                    }
                                )
                            }

                            EditorSection("兼容性") {
                                EditorDropdown(
                                    "Wine 版本",
                                    selected = w.wine.ifBlank { "跟随容器" },
                                    options = listOf("跟随容器") + wines.map { it.displayLabel },
                                    onSelect = { label ->
                                        setConfig(w.copy(
                                            wine = wines.firstOrNull { it.displayLabel == label }?.name ?: ""
                                        ))
                                    }
                                )
                                EditorDropdown(
                                    "Windows 版本",
                                    selected = WindowsVersions.fromId(w.winver).label,
                                    options = WindowsVersions.ALL.map { it.label },
                                    onSelect = {
                                        setConfig(w.copy(
                                            winver = WindowsVersions.ALL.first { v -> v.label == it }.id
                                        ))
                                    }
                                )
                                EditorDropdown(
                                    "音频",
                                    selected = w.audio.label,
                                    options = ContainerAudio.entries.map { it.label },
                                    onSelect = {
                                        setConfig(w.copy(
                                            audio = ContainerAudio.entries.first { a -> a.label == it }
                                        ))
                                    }
                                )
                            }
                        }
                    }

                    // ---------- 2 图形驱动（/opt）----------
                    2 -> {
                        if (working == null) {
                            EditorNote("还没有容器 —— 先到「容器」页创建后再配置图形驱动")
                        } else {
                            // wineguanli GraphicsDriverTab（Vulkan 驱动 / Zink /
                            // DXVK / WineD3D / FEXCore —— 全部 /opt 扫描选择）
                            androidx.compose.runtime.CompositionLocalProvider(
                                com.linbox.apps.containers.LocalGuanliScale provides 1f,
                                com.linbox.apps.containers.LocalGuanliFontScale provides 1f
                            ) {
                                com.linbox.apps.containers.GraphicsDriverTab(
                                    edit = working,
                                    onChange = { setConfig(it) }
                                )
                            }
                        }
                    }

                    // ---------- 3 CPU 核心 ----------
                    3 -> {
                        if (working == null) {
                            EditorNote("还没有容器 —— 先到「容器」页创建后再配置 CPU 核心")
                        } else {
                            androidx.compose.runtime.CompositionLocalProvider(
                                com.linbox.apps.containers.LocalGuanliScale provides 1f,
                                com.linbox.apps.containers.LocalGuanliFontScale provides 1f
                            ) {
                                com.linbox.apps.containers.CpuCoresTab(
                                    edit = working,
                                    onChange = { setConfig(it) }
                                )
                            }
                        }
                    }

                    // ---------- 4 函数库 ----------
                    4 -> {
                        if (working == null) {
                            EditorNote("还没有容器 —— 先到「容器」页创建后再配置函数库")
                        } else {
                            androidx.compose.runtime.CompositionLocalProvider(
                                com.linbox.apps.containers.LocalGuanliScale provides 1f,
                                com.linbox.apps.containers.LocalGuanliFontScale provides 1f
                            ) {
                                com.linbox.apps.containers.LibraryTab(
                                    edit = working,
                                    onChange = { setConfig(it) },
                                    onApplyNow = {
                                        cs.launch(Dispatchers.IO) {
                                            val err = runCatching {
                                                com.linbox.apps.containers.launcher.WineLibrary
                                                    .applyNow(working)
                                            }.getOrElse { "应用异常：${it.message}" }
                                            withContext(Dispatchers.Main) {
                                                toast(context, err ?: "函数库设置已应用到容器注册表")
                                            }
                                        }
                                    }
                                )
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

        // ===== 底部保存按钮 =====
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xE60D1420))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Button(
                onClick = { save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LinShellTheme.blue)
            ) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (editing == null) "添加到游戏库" else "保存修改",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
            }
        }
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
    // v6.1：色板跟随主题
    val palette = GlassPalette.current
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
            // 游戏专属配置角标（v6.2：一眼可辨哪些游戏固定了专属配置）
            if (shortcut.config.isNotBlank()) {
                Text(
                    "专属配置",
                    color = Color.White, fontSize = 8.sp,
                    modifier = Modifier
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0x990659C8))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
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
            color = GlassPalette.current.textSecondary,
            fontSize = 12.sp, lineHeight = 17.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (hasContainers) {
            Spacer(Modifier.height(10.dp))
            Text(
                "长按游戏卡片可编辑 / 删除",
                color = LinShellTheme.textSecondary, fontSize = 10.sp
            )
        }
    }
}
