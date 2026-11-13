package com.linbox.apps.containers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.apps.containers.launcher.OptConfSupport
import com.linbox.apps.containers.launcher.RootfsLauncher
import com.linbox.apps.containers.launcher.WineEnvironment
import com.linbox.apps.containers.launcher.WrapperDriverSupport
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.WinConfirmDialog
import com.linbox.core.window.WinDialog
import com.linbox.core.window.WindowContentScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LinBox Bionic 容器 — 管理界面（v3.2.18 · wineguanli.py 完整重制；
 * v3.3.39 更名「Wine 容器」→「Bionic 容器」）。
 *
 * 本文件是 wineguanli.py（PyQt5 容器管理器，6357 行）的 Compose 逐面
 * 移植：主窗口（工具栏 + 左右分栏 + 五选项卡 + 底部操作行）、高级设置
 * 页（DY 配置 / Box 配置 / Wine 安装 / MANGOHUD）、更多设置对话框
 * （CPU 核心 / 函数库 / 环境变量 / 高级功能 / Direct3D + LinBox 原生）、
 * 新建容器对话框、MANGOHUD 配置对话框、多选对话框、快捷方式。
 *
 * ## 全宽度适配（wineguanli.py 的 QSplitter 可以实现所有宽度，本重制
 * 做同样承诺 —— 用户 v3.2.17 反馈："没有那么宽也用，直接缩小 ui，
 * 加入滑动"）：
 *  - 布局恒为左右分栏（左列 280dp + 右列 ≥712dp），不再有"窄屏回落
 *    旧卡片"的分支 —— 旧实现 maxWidth<700dp 走旧 UI，手机上永远看不到
 *    重制界面，正是用户"UI 没变"的根源；
 *  - 缩放系数 scale = 可用宽度 / 1024（wineguanli 主窗口 setMinimum
 *    Size(1024,600) 的等比适配），夹在 0.6..1.0：横屏平板 1:1，横屏
 *    手机 ~0.8，竖屏手机 0.6；
 *  - 内容最小宽度 = 1024 × scale：可用宽度放得下就恰好铺满（不滚动），
 *    放不下（竖屏手机）→ 外层横向滑动（horizontalScroll）+ 顶部提示；
 *  - 全部新组件经 [gdp]/[gsp] 读 [LocalGuanliScale]，间距字号等比缩小。
 *
 * ## 字段 → container.conf 的移植映射（对照 wineguanli.py）
 *  - 基本设置：架构(x86_64/arm64ec → backend=box64/arm64ec)、Wine 版本
 *    (wine=)、分辨率(全屏→screen=native / WxH→screen=WxH)、语言(lang=)、
 *    帧数限制(frame=)、独立窗口+分辨率(dmode=f/d/off)、EXE 文件(exe=)、
 *    高级启动选项勾选(flags= csv) + 自定义启动参数(uargs=)
 *  - 图形驱动：Turnip/Zink/WineD3D(turnip=/zink=/wined3d=，/opt/tz、
 *    /opt/zink、/opt/vkord3d 扫描)、DXVK(dxvk=，默认=auto / 不使用=off /
 *    /opt/dxvk1 槽位)
 *    v3.3.37：Vulkan 驱动分列 —— turnip 包（/opt/tz）与 yuzu/高通专有
 *    包（/opt/yuzu）各自独立档位独立选择，系统驱动独立档（wrapper=system）
 *  - CPU 核心：cores=（wineguanli 写 /opt/conf/cores.conf，LinBox 写
 *    容器键 + PRIMARY_CORES/SECONDARY_CORES 环境注入，语义一致）
 *  - 函数库：dsound=/dshow=/dpi=/winver=（注册表落地见 WineLibrary）
 *  - DY 配置（v3.3.4 重写）：/opt/conf/custom.conf 定义表 +
 *    dynarec_active.conf 当前值（wineguanli DynarecEditor 同款全局
 *    语义）；应用时同步清理容器 env= 里的旧 BOX64_* 覆盖
 *  - 环境变量（v3.3.4）：MESA_GL_VERSION_OVERRIDE/MESA_GLSL_VERSION_OVERRIDE/
 *    VKD3D_FEATURE_LEVEL/MESA_EXTENSION_OVERRIDE/DXVK_HUD/TU_DEBUG/
 *    MANGOHUD/MANGOHUD_CONFIG —— 读写 /opt/conf/env.conf（参考
 *    save_advanced_settings 同款；旧版误写 MESA_GL_VERSION 等 Mesa 不读
 *    的变量名 + 只落容器 env=，是"怎么改都没反应"的双重根因）
 *  - Direct3D / CNC DDraw：HKCU AppDefaults\<exe>\DllOverrides（wine 原生
 *    按程序覆盖机制，等效 wineguanli 的"复制 DLL 到 exe 目录"）
 */

// ===========================================================================
// 缩放系统：所有 wineguanli 面的尺寸经 LocalGuanliScale 等比缩放
// ===========================================================================

/** 当前 wineguanli 面的尺寸缩放系数（1f = wineguanli 桌面原始尺寸）。 */
val LocalGuanliScale = staticCompositionLocalOf { 1f }

/**
 * v3.2.23（用户问题 5）：字号缩放系数 —— 与尺寸缩放分离。
 *
 * 背景：主窗默认尺寸 1060→900dp（用户反馈“太长太宽，拉小”），若仍按
 * 单一 scale = maxWidth/1024 缩，字号会连带缩到 88% —— 与“字体保持
 * 默认”诉求相悖。分离后：
 *  - gdp（尺寸）：基准仍是 1024 —— 窄窗时布局尺寸等比缩，防溢出；
 *  - gsp（字号）：基准 900 —— 默认 900dp 窗字号 = 旧 1060dp 窗完全
 *    一致（都是 1f，叠加 GuanliUiK 密度收紧后即用户当前看到的字号），
 *    仅当窗口拖到比 900 还窄时字号才同步缩（窄窗适配语义保留）。
 * 弹窗体经 [GuanliDialogScale] 同步拿到两个系数。
 */
val LocalGuanliFontScale = staticCompositionLocalOf { 1f }

/** 字号缩放基准（主窗默认宽）：与 ContainerApp.defaultWidth 对齐。 */
const val GuanliFontBaseDp = 800f

/** dp × 尺寸缩放。 */
@Composable
fun gdp(d: Dp): Dp = (d.value * LocalGuanliScale.current).dp

/** sp × 字号缩放（v3.2.23 起独立于尺寸缩放，见 LocalGuanliFontScale）。 */
@Composable
fun gsp(s: TextUnit): TextUnit = (s.value * LocalGuanliFontScale.current).sp

/**
 * v3.2.21 全局收紧系数：用户反馈“整体 UI 太大，内部字体以及框框都太大，
 * 很不舒服，甚至一些窗口的框框非常大”（图二创建新容器弹窗）——
 * Guanli 面所有内容（含 Material 组件与弹窗）统一缩至 80%。
 *
 * 实现分工（不叠加）：
 *  - gdp/gsp 自定义控件：LocalGuanliScale 原有适配不变，主窗根部的
 *    LocalDensity 覆盖（density × K）把 dp/sp→px 换算收紧 20%；
 *  - Material 组件（OutlinedTextField/Button/TabRow/Checkbox 等，不走
 *    gdp/gsp）：同一 LocalDensity 覆盖同步收紧 20%；
 *  - 弹窗（WinDialog 内容在弹窗层组合，继承不到本窗密度/缩放）：
 *    调用点 width × K + 内容包 [GuanliDialogScale]（v3.2.20 之前弹窗层
 *    拿到的是 scale=1 + 原始密度，所以图二弹窗比主窗还大一圈 ——
 *    56dp 的 Material 输入框 + 未缩放字号双重叠加）。
 * 只调这一个常量即可继续微调整面尺寸。
 */
const val GuanliUiK = 0.72f

/**
 * v3.3.4（用户问题 2："wine容器界面字体太小"）：字号补偿系数。
 *
 * 背景：v3.2.21/v3.3.3 两轮"整体收紧"（1.0→0.8→0.68）把布局与字号
 * 一起缩小 —— 框体密度对齐了参考截图，但 13sp 标题实际渲染只剩
 * ~8.8sp，用户实测"字体太小"。本系数只放大字号、不动框体：
 *  - LocalDensity 覆盖改为 Density(density × GuanliUiK, fontScale ×
 *    GuanliFontBoost) —— dp 换算维持紧凑（0.72），sp 换算补回
 *    （0.72 × 1.35 ≈ 0.97 ≈ 设计原尺寸）；
 *  - 两处密度覆盖同步（主窗根部 + [GuanliDialogScale] 弹窗层），
 *    Material 组件与 gdp/gsp 自定义控件同步受益。
 */
const val GuanliFontBoost = 1.35f

/**
 * 弹窗内容收紧：弹窗内容在 WinDialogHost 子树组合（另一棵组合树），
 * 组合局部变量按树位置解析，继承不到容器窗口的 LocalDensity/LocalGuanliScale
 * —— 在弹窗内容根部自建同幅缩放。
 * @param winScale 调用方窗口的 LocalGuanliScale（尺寸缩放）
 * @param winFontScale 调用方窗口的 LocalGuanliFontScale（字号缩放，
 *        v3.2.23 起与尺寸分离；缺省退回 winScale 保持旧语义）
 */
@Composable
fun GuanliDialogScale(winScale: Float, winFontScale: Float = winScale, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        // v3.3.4：fontScale 侧叠加 GuanliFontBoost —— 弹窗字号与主窗同步放大
        LocalDensity provides Density(base.density * GuanliUiK, base.fontScale * GuanliFontBoost),
        LocalGuanliScale provides winScale,
        LocalGuanliFontScale provides winFontScale
    ) { content() }
}

/** wineguanli.py Qt 样式表的配色（QToolBar/QGroupBox/QPushButton 原色）。 */
private object GuanliColors {
    // v5.0（安卓化）：全部换成 LinShellTheme 同源暗色系 —— 管理器不再
    // 是"老 Windows 窗口里的 Qt 亮色面板"，与壳层全局主题统一。
    val toolbar = Color(0xFF161D33)        // 工具栏/导航底（LinShellTheme.surface）
    val toolbarHover = Color(0xFF1F2841)   // 工具栏按钮 hover
    val accent = Color(0xFF4E7CFF)         // 主蓝（LinShellTheme.blue）
    val accentDark = Color(0xFF3D66E0)
    val accentPressed = Color(0xFF2F51B8)
    val green = Color(0xFF34C759)          // ▶ 运行
    val greenDeep = Color(0xFF28A745)      // 💾 立即应用/保存配置
    val greenDeepHover = Color(0xFF218838)
    val red = Color(0xFFFF5A5A)            // 删除类
    val redFab = Color(0xFFFF5A5A)         // DY 删除变量
    val blueFab = Color(0xFF4E7CFF)        // "选择…"类按钮
    val grey = Color(0xFF8A93A8)           // 重置默认
    val info = Color(0x99FFFFFF)           // 说明文字（次级文本）
    val panelBg = Color(0xFF0F1424)        // 页面底（LinShellTheme.bg）
    val listSel = Color(0x334E7CFF)        // 列表选中（蓝色 20%）
    val rowAlt = Color(0x14FFFFFF)         // 信息底（微白）
}

// ===========================================================================
// 入口（WindowContentScope 契约不变）
// ===========================================================================

@Composable
private fun themeBg() = com.linbox.core.theme.LinShellTheme.bg
@Composable
private fun themeCard() = com.linbox.core.theme.LinShellTheme.surface
@Composable
private fun themeAccent() = com.linbox.core.theme.LinShellTheme.blue
@Composable
private fun themeSec() = com.linbox.core.theme.LinShellTheme.textSecondary
@Composable
private fun themeFg() = com.linbox.core.theme.LinShellTheme.textPrimary

@Composable
fun ContainerListContent(scope: WindowContentScope) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    var tick by remember { mutableIntStateOf(0) }                 // 刷新触发器
    var containers by remember { mutableStateOf<List<ContainerData>>(emptyList()) }
    var rootfsReady by remember { mutableStateOf(true) }
    var wines by remember { mutableStateOf<List<WineInstall>>(emptyList()) }

    // wineguanli.py：QStackedWidget 两个视图（容器管理 / 高级设置）
    var advancedView by remember { mutableStateOf(false) }

    // 弹窗路由（同屏最多一个主弹窗 + 若干子选择器）
    var showCreate by remember { mutableStateOf(false) }
    var showDoctor by remember { mutableStateOf(false) }
    var showRunFor by remember { mutableStateOf<ContainerData?>(null) }
    var showLogFor by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<ContainerData?>(null) }
    var renameFor by remember { mutableStateOf<ContainerData?>(null) }
    var moreFor by remember { mutableStateOf<ContainerData?>(null) }       // 更多设置
    var mangoFor by remember { mutableStateOf<ContainerData?>(null) }      // MANGOHUD 配置
    var shortcutFor by remember { mutableStateOf<ContainerData?>(null) }   // 创建快捷方式
    var multiSelect by remember { mutableStateOf<MultiSelectRequest?>(null) }

    // v3.1.6 前缀构建日志：构建进行中自动弹出；也可从面板手动打开
    var prefixLogFor by remember { mutableStateOf<String?>(null) }
    val prefixBuilding by RootfsLauncher.prefixBuildFlow.collectAsState()
    LaunchedEffect(prefixBuilding) {
        prefixBuilding?.let { prefixLogFor = it }
    }

    // 列表加载（tick 驱动刷新）
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            containers = ContainerManager.list()
            rootfsReady = ContainerManager.rootfsReady()
            wines = ContainerManager.listWines()
            com.linbox.apps.terminal.termux.LinBoxTzstAssets
                .applyDxvkWhenPrefixReady(context)
        }
    }

    fun refresh() { tick++ }

    // 当前选中容器（右侧面板数据源；wineguanli.py container_list 选中项）
    var selectedName by remember { mutableStateOf<String?>(null) }
    val sel = containers.firstOrNull { it.name == selectedName } ?: containers.firstOrNull()

    // v3.2.18：桌面快捷方式自动运行（launchArgs.autorun = 容器名；
    // ShortcutDialog 固定的快捷方式经 MainActivity 带名打开本窗口）
    val autorun = scope.windowState.launchArgs["autorun"]
    var autorunDone by remember { mutableStateOf(false) }
    LaunchedEffect(autorun, containers.isNotEmpty()) {
        if (autorunDone || autorun.isNullOrBlank() || containers.isEmpty()) return@LaunchedEffect
        val c = containers.firstOrNull { it.name == autorun } ?: return@LaunchedEffect
        autorunDone = true
        selectedName = autorun
        if (c.exe.isNotBlank()) {
            // v3.3.8：驱动解压已统一进启动链步骤 0（此处直接启动）
            cs.launch(Dispatchers.IO) {
                val err = WineSessionLauncher.runExe(context, c, c.exe)
                withContext(Dispatchers.Main) {
                    err?.let {
                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            showRunFor = c
        }
    }

    fun refreshAndKeep(name: String?) {
        selectedName = name
        tick++
    }

    // ---------- LinBox 首页/高级页深链（launchArgs） ----------
    // focus = 容器名：打开本窗口时选中指定容器（首页卡片"配置"按钮）
    val focusArg = scope.windowState.launchArgs["focus"]
    LaunchedEffect(focusArg, containers.isNotEmpty()) {
        if (!focusArg.isNullOrBlank() && containers.any { it.name == focusArg }) {
            selectedName = focusArg
        }
    }
    // page = "advanced" / "doctor"：直接进入高级设置视图 / 弹出容器体检
    val pageArg = scope.windowState.launchArgs["page"]
    LaunchedEffect(pageArg, containers.isNotEmpty()) {
        when (pageArg) {
            "advanced" -> advancedView = true
            "doctor" -> showDoctor = true
        }
    }

    // ---------- 运行类动作 ----------
    // v3.3.8（用户问题 4）：/opt 驱动包解压统一移入 RootfsLauncher 启动链
    // 步骤 0（freshSession 时执行）—— 所有运行入口（底部按钮/运行对话框/
    // autorun/ExeRunChooser）自动覆盖，UI 不再逐处包裹（也避免双解压）。
    fun runContainer(cont: ContainerData) {
        if (cont.exe.isNotBlank()) {
            cs.launch(Dispatchers.IO) {
                val err = WineSessionLauncher.runExe(context, cont, cont.exe)
                withContext(Dispatchers.Main) {
                    err?.let {
                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
            }
        } else {
            showRunFor = cont
        }
    }

    fun winebootContainer(cont: ContainerData) {
        cs.launch(Dispatchers.IO) {
            val err = WineSessionLauncher.initializePrefix(context, cont)
            withContext(Dispatchers.Main) {
                err?.let {
                    android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    // ---------- 全宽度适配（见文件头说明） ----------
    // v3.2.21 全局收紧（用户反馈“整体 UI 太大，内部字体以及框框都太大”）：
    // ① 主窗：根级 LocalDensity 覆盖（density × GuanliUiK）——gdp/gsp 自定义
    //    控件与 Material 组件（不走 gdp/gsp）同步缩小 20%，不叠加
    //    （dp/sp 数值不变，仅 dp/sp→px 换算收紧）；
    // ② 弹窗：WinDialog 内容在弹窗层组合，继承不到这里的密度/缩放 ——
    //    winScale 状态提升 + 根级 LocalGuanliScale 提供给弹窗体，
    //    各弹窗内容包 GuanliDialogScale + width×K（见各弹窗定义）。
    var winScale by remember { mutableStateOf(1f) }
    var winFontScale by remember { mutableStateOf(1f) }
    val baseDens = LocalDensity.current
    CompositionLocalProvider(
        LocalGuanliScale provides winScale,
        LocalGuanliFontScale provides winFontScale
    ) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(themeBg())
    ) {
        val scale = (maxWidth.value / 1024f).coerceIn(0.6f, 1f)
        // v3.2.23（用户问题 5）：尺寸/字号双轨 —— 布局尺寸按 1024 基准缩
        //（防溢出），字号按 900 基准缩（默认窗 900dp 下与旧 1060dp 窗字号
        // 一致，满足“窗口拉小、字体保持默认”）。
        val fontScale = (maxWidth.value / GuanliFontBaseDp).coerceIn(0.6f, 1f)
        // 组合期回写：本 Box 在弹窗调用之前组合（同函数代码顺序），
        // 弹窗体经根级 provider 读到当前窗口的适配缩放
        winScale = scale
        winFontScale = fontScale
        val minW = 1024.dp * scale
        // v3.2.20 修复：maxWidth 在 Column/CompositionLocalProvider 嵌套 lambda
        // 里不能经隐式接收者解析（CI 实报 337:39 "'val maxWidth: Dp' can't be
        // called in this context by implicit receiver"）—— 在 BoxWithConstraints
        // 作用域内捕获为局部 val，嵌套 lambda 按闭包捕获继续可用。
        val availW = maxWidth

        CompositionLocalProvider(
            LocalGuanliScale provides scale,
            LocalGuanliFontScale provides fontScale,
            // v3.3.4（用户问题 2："界面字体太小"）：fontScale 侧叠加
            // GuanliFontBoost（1.35）—— dp/sp 双轨后框体保持紧凑
            //（×0.72），字号补回设计尺寸（×0.72×1.35≈0.97）。
            LocalDensity provides Density(baseDens.density * GuanliUiK, baseDens.fontScale * GuanliFontBoost)
        ) {
            Column(Modifier.fillMaxSize()) {
                // ---------------- 工具栏（wineguanli.py QToolBar 同款） ----------------
                GuanliToolbar(
                    advancedView = advancedView,
                    onSwitchView = { advancedView = it },
                    onCloseWindow = { scope.onClose() },
                    onDoctor = { showDoctor = true },
                    onCreate = { showCreate = true },
                    onShortcuts = { shortcutFor = sel },
                    canShortcuts = sel != null
                )

                if (!rootfsReady) {
                    RootfsMissingHint()
                }

                // ---------------- 主内容（两视图切换） ----------------
                if (!advancedView) {
                    GuanliContainerPage(
                        containers = containers,
                        wines = wines,
                        sel = sel,
                        running = { name -> WineSessionLauncher.activeSessions.containsKey(name) },
                        onSelect = { selectedName = it },
                        onRefresh = { refresh() },
                        onRun = { runContainer(it) },
                        onWineboot = { winebootContainer(it) },
                        onStop = { c ->
                            cs.launch(Dispatchers.IO) {
                                WineSessionLauncher.stopContainer(context, c.name)
                                withContext(Dispatchers.Main) { refresh() }
                            }
                        },
                        onShowLog = { showLogFor = it.name },
                        onPrefixLog = { prefixLogFor = it.name },
                        onRemove = { confirmRemove = it },
                        onRename = { renameFor = it },
                        onMore = { moreFor = it },
                        onMango = { mangoFor = it },
                        onShortcut = { shortcutFor = it },
                        onSetDefault = { c ->
                            cs.launch(Dispatchers.IO) {
                                ContainerManager.setDefault(c.name)
                                withContext(Dispatchers.Main) { refresh() }
                            }
                        },
                        minWidth = minW,
                        needHScroll = availW < minW,
                        onCreate = { showCreate = true }
                    )
                } else {
                    GuanliAdvancedPage(
                        containers = containers,
                        sel = sel,
                        onRefresh = { refresh() },
                        onMango = { mangoFor = it },
                        multiSelect = { req -> multiSelect = req }
                    )
                }
            }
        }
    }

    // ---------------- 弹窗层 ----------------
    // 创建后待安装前缀的容器（等列表刷新到包含它再触发，避免时序竞争）
    var pendingWineboot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingWineboot, containers) {
        val target = pendingWineboot ?: return@LaunchedEffect
        val c = containers.firstOrNull { it.name == target } ?: return@LaunchedEffect
        pendingWineboot = null
        // wineguanli.py：创建成功后询问"是否立即安装Wine前缀？"（LinBox 直接触发）
        winebootContainer(c)
    }
    if (showCreate) {
        NewContainerDialog(
            wines = wines,
            onDismiss = { showCreate = false },
            onCreated = { name ->
                showCreate = false
                refreshAndKeep(name)
                pendingWineboot = name
            }
        )
    }
    showRunFor?.let { c ->
        RunContainerDialog(
            container = c,
            // v3.3.8：驱动解压已统一进启动链步骤 0（此处直接启动）
            onRunExe = { exe, args ->
                cs.launch(Dispatchers.IO) {
                    val err = WineSessionLauncher.runExe(context, c, exe, args)
                    withContext(Dispatchers.Main) {
                        err?.let {
                            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                        }
                        refresh()
                    }
                }
            },
            onDismiss = { showRunFor = null },
            onLaunched = { showRunFor = null; refresh() }
        )
    }
    renameFor?.let { c ->
        RenameContainerDialog(
            container = c,
            onDismiss = { renameFor = null },
            onRenamed = { old, new ->
                renameFor = null
                cs.launch(Dispatchers.IO) {
                    val err = ContainerManager.rename(old, new)
                    withContext(Dispatchers.Main) {
                        err?.let {
                            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                        }
                        refreshAndKeep(if (err == null) new else old)
                    }
                }
            }
        )
    }
    moreFor?.let { c ->
        MoreSettingsDialog(
            container = c,
            onDismiss = { moreFor = null },
            onSaved = { moreFor = null; refresh() },
            onOpenMango = { moreFor = null; mangoFor = c }
        )
    }
    mangoFor?.let { c ->
        MangoHudConfigDialog(
            container = c,
            onDismiss = { mangoFor = null },
            onSaved = { mangoFor = null; refresh() }
        )
    }
    shortcutFor?.let { c ->
        ShortcutDialog(
            container = c,
            onDismiss = { shortcutFor = null }
        )
    }
    multiSelect?.let { req ->
        MultiSelectDialog(
            title = req.title,
            options = req.options,
            initial = req.selected,
            onDismiss = { multiSelect = null },
            onOk = {
                req.onOk(it)
                multiSelect = null
            }
        )
    }
    confirmRemove?.let { c ->
        WinConfirmDialog(
            title = "删除容器",
            message = "确定删除容器 \"${c.name}\"？其 WINEPREFIX 与已安装程序将一并删除，不可恢复。",
            confirmText = "删除",
            danger = true,
            uiScale = GuanliUiK,
            onConfirm = {
                confirmRemove = null
                cs.launch(Dispatchers.IO) {
                    ContainerManager.remove(c.name)
                    withContext(Dispatchers.Main) { refreshAndKeep(null) }
                }
            },
            onDismiss = { confirmRemove = null }
        )
    }
    if (showDoctor) {
        DoctorDialog(onDismiss = { showDoctor = false })
    }
    showLogFor?.let { key ->
        LogDialog(
            title = "Wine 输出 · $key",
            log = WineSessionLauncher.lastLogs[key]?.toString() ?: "（暂无输出）",
            onDismiss = { showLogFor = null }
        )
    }
    prefixLogFor?.let { name ->
        PrefixBuildLogDialog(
            containerName = name,
            running = prefixBuilding == name,
            onDismiss = { prefixLogFor = null }
        )
    }
    } // 根级 LocalGuanliScale provider（v3.2.21：弹窗体读 winScale 用）
}

/** 多选对话框请求（延迟打开，选中结果回投）。 */
data class MultiSelectRequest(
    val title: String,
    val options: List<String>,
    val selected: List<String>,
    val onOk: (List<String>) -> Unit
)

// ===========================================================================
// 工具栏（wineguanli.py create_toolbar：☰ 菜单 + 标题 + 创建/快捷方式）
// ===========================================================================

@Composable
private fun GuanliToolbar(
    advancedView: Boolean,
    onSwitchView: (Boolean) -> Unit,
    onCloseWindow: () -> Unit,
    onDoctor: () -> Unit,
    onCreate: () -> Unit,
    onShortcuts: () -> Unit,
    canShortcuts: Boolean
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(GuanliColors.toolbar)
            .padding(horizontal = gdp(10.dp), vertical = gdp(6.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ☰ 菜单（QToolButton InstantPopup）
        Box {
            Text(
                "☰",
                color = Color.White,
                fontSize = gsp(20.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(gdp(4.dp)))
                    .border(gdp(0.5.dp), GuanliColors.toolbarHover, RoundedCornerShape(gdp(4.dp)))
                    .clickable { menuOpen = true }
                    .padding(horizontal = gdp(12.dp), vertical = gdp(4.dp))
            )
            GDropdownMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                GDropdownMenuItem("🏠 容器管理") { menuOpen = false; onSwitchView(false) }
                GDropdownMenuItem("⚙️ 高级设置") { menuOpen = false; onSwitchView(true) }
                GDropdownMenuDivider()
                GDropdownMenuItem("🩺 容器体检") { menuOpen = false; onDoctor() }
                GDropdownMenuDivider()
                GDropdownMenuItem("🚪 退出") { menuOpen = false; onCloseWindow() }
            }
        }
        Spacer(Modifier.width(gdp(10.dp)))
        Text(
            if (advancedView) "⚙️ 高级设置" else "🏠 容器管理",
            color = Color.White,
            fontSize = gsp(20.sp),
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        GTextButton("➕ 创建容器", onEnabled = onCreate)
        Spacer(Modifier.width(gdp(8.dp)))
        GTextButton(
            "📄 快捷方式",
            onEnabled = if (canShortcuts) onShortcuts else null
        )
    }
}

/** 工具栏文字按钮（Qt 白字透明底 + hover 深色块）。 */
@Composable
private fun GTextButton(label: String, onEnabled: (() -> Unit)?) {
    Text(
        label,
        color = if (onEnabled != null) Color.White else Color.White.copy(alpha = 0.4f),
        fontSize = gsp(14.sp),
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(gdp(4.dp)))
            .background(Color.Transparent)
            .border(gdp(0.5.dp), GuanliColors.toolbarHover, RoundedCornerShape(gdp(4.dp)))
            .clickable(enabled = onEnabled != null) { onEnabled?.invoke() }
            .padding(horizontal = gdp(12.dp), vertical = gdp(7.dp))
    )
}

// ===========================================================================
// 容器管理页（wineguanli.py create_container_page：QSplitter 左右分栏）
// ===========================================================================

@Composable
private fun GuanliContainerPage(
    containers: List<ContainerData>,
    wines: List<WineInstall>,
    sel: ContainerData?,
    running: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onRun: (ContainerData) -> Unit,
    onWineboot: (ContainerData) -> Unit,
    onStop: (ContainerData) -> Unit,
    onShowLog: (ContainerData) -> Unit,
    onPrefixLog: (ContainerData) -> Unit,
    onRemove: (ContainerData) -> Unit,
    onRename: (ContainerData) -> Unit,
    onMore: (ContainerData) -> Unit,
    onMango: (ContainerData) -> Unit,
    onShortcut: (ContainerData) -> Unit,
    onSetDefault: (ContainerData) -> Unit,
    onCreate: () -> Unit,
    minWidth: Dp,
    needHScroll: Boolean
) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxSize()
                .then(if (needHScroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
        ) {
            // ---------------- 左侧面板（v3.3.8 加宽 320dp：容器名不再截断） ----------------
            Column(
                Modifier
                    .width(gdp(320.dp))
                    .fillMaxHeight()
                    .padding(gdp(10.dp))
            ) {
                Text(
                    "📦 已创建的容器",
                    fontSize = gsp(20.sp),
                    fontWeight = FontWeight.Bold,
                    color = GuanliColors.toolbar
                )
                Spacer(Modifier.height(gdp(8.dp)))
                LeftContainerListBox(
                    modifier = Modifier.weight(1f),
                    containers = containers,
                    selectedName = sel?.name,
                    running = running,
                    isDefault = { ContainerManager.isDefault(it) },
                    onSelect = onSelect,
                    onRun = onRun,
                    onWineboot = onWineboot,
                    onRename = onRename,
                    onRemove = onRemove,
                    onShortcut = onShortcut,
                )
                Spacer(Modifier.height(gdp(8.dp)))
                // wineguanli.py create_left_panel：新建/删除/重命名 + 创建快捷方式
                Row(horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
                    GSmallButton("➕ 新建", GuanliColors.accent, Modifier.weight(1f)) { onCreate() }
                    GSmallButton(
                        "🗑️ 删除", GuanliColors.red, Modifier.weight(1f),
                        enabled = sel != null
                    ) { sel?.let(onRemove) }
                    GSmallButton(
                        "✏️ 重命名", GuanliColors.grey, Modifier.weight(1f),
                        enabled = sel != null
                    ) { sel?.let(onRename) }
                }
                Spacer(Modifier.height(gdp(6.dp)))
                GSmallButton(
                    "📄 创建快捷方式", GuanliColors.accentDark, Modifier.fillMaxWidth(),
                    enabled = sel != null
                ) { sel?.let(onShortcut) }
            }

            Spacer(Modifier.width(gdp(8.dp)))

            // ---------------- 右侧面板（wineguanli create_right_panel） ----------------
            // v3.3.8：左面板 280→320 后右侧 716→696（总宽仍 ~1024 基准）
            if (sel != null) {
                RightConfigPanel(
                    container = sel,
                    wines = wines,
                    running = running(sel.name),
                    modifier = if (needHScroll) Modifier.width(gdp(696.dp)) else Modifier.weight(1f),
                    onSaved = onRefresh,
                    onRun = { onRun(sel) },
                    onWineboot = { onWineboot(sel) },
                    onStop = { onStop(sel) },
                    onShowLog = { onShowLog(sel) },
                    onPrefixLog = { onPrefixLog(sel) },
                    onMore = { onMore(sel) },
                    onMango = { onMango(sel) },
                    onSetDefault = { onSetDefault(sel) }
                )
            } else {
                Column(
                    Modifier
                        .then(if (needHScroll) Modifier.width(gdp(696.dp)) else Modifier.weight(1f))
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🍷", fontSize = gsp(40.sp))
                    Spacer(Modifier.height(gdp(8.dp)))
                    Text("未选择容器", fontSize = gsp(16.sp), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(gdp(4.dp)))
                    Text(
                        "在左侧选择或新建一个容器（wineguanli.py 同款左右分栏）",
                        fontSize = gsp(12.sp), color = GuanliColors.info
                    )
                }
            }
        }
        // 窄屏提示横幅（可横滑）
        if (needHScroll) {
            Text(
                "↔ 屏幕比容器管理器窄：界面已按 ${"%.0f".format((LocalGuanliScale.current * 100))}% 缩放，左右滑动查看完整面板",
                fontSize = gsp(10.sp),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = gdp(4.dp))
                    .clip(RoundedCornerShape(gdp(8.dp)))
                    .background(GuanliColors.toolbar.copy(alpha = 0.85f))
                    .padding(horizontal = gdp(10.dp), vertical = gdp(4.dp))
            )
        }
    }
}

// ===========================================================================
// 左侧容器列表（wineguanli.py container_list：双击运行 + 右键菜单）
// ===========================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LeftContainerListBox(
    containers: List<ContainerData>,
    selectedName: String?,
    running: (String) -> Boolean,
    isDefault: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onRun: (ContainerData) -> Unit,
    onWineboot: (ContainerData) -> Unit,
    onRename: (ContainerData) -> Unit,
    onRemove: (ContainerData) -> Unit,
    onShortcut: (ContainerData) -> Unit,
    // v3.2.20 修复：weight() 只在 Row/Column 内容作用域内可用（CI 实报 709:14
    // "Expression 'weight' cannot be invoked as a function"），本函数体没有
    // ColumnScope 接收者 —— 占满剩余高度的 weight 交由调用点（在 Column 内）
    // 经 modifier 注入，本函数只负责默认不占权重。
    modifier: Modifier = Modifier
) {
    var menuFor by remember { mutableStateOf<ContainerData?>(null) }
    LazyColumn(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(gdp(8.dp)))
            .border(gdp(0.5.dp), themeSec().copy(alpha = 0.3f), RoundedCornerShape(gdp(8.dp))),
        verticalArrangement = Arrangement.spacedBy(gdp(4.dp)),
        contentPadding = PaddingValues(gdp(6.dp))
    ) {
        items(containers, key = { it.name }) { c ->
            val isSel = c.name == selectedName
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(gdp(6.dp)))
                    .background(
                        if (isSel) GuanliColors.accent.copy(alpha = 0.18f)
                        else themeCard()
                    )
                    .border(
                        gdp(0.5.dp),
                        if (isSel) GuanliColors.accent else themeSec().copy(alpha = 0.25f),
                        RoundedCornerShape(gdp(6.dp))
                    )
                    .combinedClickable(
                        onClick = { onSelect(c.name) },
                        onLongClick = { menuFor = c }
                    )
                    .padding(horizontal = gdp(10.dp), vertical = gdp(8.dp))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📦 ${c.name}",
                        fontSize = gsp(14.sp),
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        color = themeFg(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        // v3.3.8（用户问题 1）：去掉 fill=false —— 容器名占满
                        // 除角标外的全部宽度，长名不再截断
                        modifier = Modifier.weight(1f)
                    )
                    if (isDefault(c.name)) {
                        Text(" ★", fontSize = gsp(12.sp), color = GuanliColors.accent)
                    }
                    if (running(c.name)) {
                        Text(" ●", fontSize = gsp(10.sp), color = Color(0xFF4CAF50))
                    }
                }
                Spacer(Modifier.height(gdp(2.dp)))
                Text(
                    buildString {
                        append("[")
                        append(if (c.screen == "native") "全屏" else c.screen)
                        append("]")
                        if (c.wine.isNotBlank()) append(" 🍷${c.wine.removePrefix("wine-")}")
                        append(" ${WindowsVersions.fromId(c.winver).label}")
                    },
                    fontSize = gsp(10.sp),
                    color = themeSec(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (containers.isEmpty()) {
            item {
                Text(
                    "暂无容器。点击上方\"➕ 创建容器\"创建第一个 Bionic 容器。",
                    fontSize = gsp(12.sp), color = themeSec(),
                    modifier = Modifier.padding(vertical = gdp(20.dp))
                )
            }
        }
    }

    // 长按菜单（wineguanli.py show_container_context_menu 同款条目；
    // 语言在编辑页/新建对话框的"语言"下拉里改 —— v3.3.2 恢复，显式值
    // 直接决定启动配置的 LANG/LC_ALL）
    menuFor?.let { c ->
        GDropdownMenu(expanded = true, onDismiss = { menuFor = null }) {
            GDropdownMenuItem("▶️ 运行容器") { menuFor = null; onRun(c) }
            GDropdownMenuItem("🔧 安装前缀") { menuFor = null; onWineboot(c) }
            GDropdownMenuItem("⚙️ 设置（选中并在右侧编辑）") { menuFor = null; onSelect(c.name) }
            GDropdownMenuDivider()
            GDropdownMenuItem("🗑️ 删除") { menuFor = null; onRemove(c) }
            GDropdownMenuDivider()
            GDropdownMenuItem("✏️ 重命名") { menuFor = null; onRename(c) }
            GDropdownMenuItem("📄 创建快捷方式") { menuFor = null; onShortcut(c) }
        }
    }
}

// ===========================================================================
// 右侧配置面板（wineguanli.py create_right_panel：标题 + 5 选项卡 + 底部按钮）
// ===========================================================================

@Composable
private fun RightConfigPanel(
    container: ContainerData,
    wines: List<WineInstall>,
    running: Boolean,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
    onRun: () -> Unit,
    onWineboot: () -> Unit,
    onStop: () -> Unit,
    onShowLog: () -> Unit,
    onPrefixLog: () -> Unit,
    onMore: () -> Unit,
    onMango: () -> Unit,
    onSetDefault: () -> Unit
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    // 工作副本（切换容器时随 key 重建）
    var edit by remember(container.name) { mutableStateOf(container) }
    var tab by remember(container.name) { mutableStateOf(0) }
    var saveErr by remember { mutableStateOf<String?>(null) }
    val dirty = edit != container

    Column(
        modifier
            .fillMaxHeight()
            .padding(end = gdp(10.dp), top = gdp(10.dp), bottom = gdp(10.dp))
    ) {
        // ---- 标题行（wineguanli container_title：容器名 + 运行中/默认角标） ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (edit.name.isBlank()) "未选择容器" else edit.name,
                fontSize = gsp(20.sp), fontWeight = FontWeight.Bold, color = themeFg(),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (running) {
                Spacer(Modifier.width(gdp(8.dp)))
                Text("● 运行中", fontSize = gsp(11.sp), color = Color(0xFF4CAF50))
            }
            if (ContainerManager.isDefault(container.name)) {
                Spacer(Modifier.width(gdp(6.dp)))
                Text("★ 默认", fontSize = gsp(11.sp), color = GuanliColors.accent)
            }
            if (running) {
                Spacer(Modifier.width(gdp(8.dp)))
                GSmallButton("■ 停止", GuanliColors.red, Modifier) { onStop() }
            }
            Spacer(Modifier.weight(1f))
            GSmallButton("⧉ 设为默认", GuanliColors.grey, Modifier) { onSetDefault() }
            GSmallButton("📋 会话日志", GuanliColors.grey, Modifier) { onShowLog() }
            GSmallButton("🧱 前缀日志", GuanliColors.grey, Modifier) { onPrefixLog() }
        }
        if (dirty) {
            Text(
                "● 有未保存的更改（点底部\"保存配置\"生效）",
                fontSize = gsp(10.sp), color = GuanliColors.red
            )
        }
        if (saveErr != null) {
            Text(saveErr ?: "", fontSize = gsp(11.sp), color = GuanliColors.red)
        }

        Spacer(Modifier.height(gdp(6.dp)))

        // ---- 五选项卡（wineguanli.py 顺序与文案） ----
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = gdp(0.dp),
            containerColor = themeCard(),
            contentColor = themeFg()
        ) {
            listOf("📋 基本设置", "🎮 图形驱动", "🖥️ CPU核心", "📚 函数库", "🔧 更多配置")
                .forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, fontSize = gsp(12.sp), maxLines = 1) }
                    )
                }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = gdp(10.dp)),
            verticalArrangement = Arrangement.spacedBy(gdp(10.dp))
        ) {
            when (tab) {
                // ================= 0 📋 基本设置（create_basic_tab） =================
                0 -> BasicSettingsTab(
                    edit = edit,
                    wines = wines,
                    onChange = { edit = it }
                )
                // ================= 1 🎮 图形驱动（create_graphics_tab） =================
                1 -> GraphicsDriverTab(
                    edit = edit,
                    onChange = { edit = it }
                )
                // ================= 2 🖥️ CPU核心（create_cpu_tab_for_container） =================
                2 -> CpuCoresTab(
                    edit = edit,
                    onChange = { edit = it }
                )
                // ================= 3 📚 函数库（create_library_tab_for_container） =================
                3 -> LibraryTab(
                    edit = edit,
                    onChange = { edit = it },
                    onApplyNow = {
                        cs.launch(Dispatchers.IO) {
                            val err = com.linbox.apps.containers.launcher.WineLibrary.applyNow(edit)
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context, err ?: "函数库设置已应用到注册表",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
                // ================= 4 🔧 更多配置（create_advanced_tab） =================
                4 -> MoreConfigTab(edit = edit, onOpenMore = onMore, onOpenMango = onMango)
            }
            Spacer(Modifier.height(gdp(12.dp)))
        }

        // ---- 底部按钮行（wineguanli.py：▶️运行 / 🔧安装前缀 / 💾保存配置） ----
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(gdp(8.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GSmallButton("▶️ 运行", GuanliColors.green, Modifier) { onRun() }
            GSmallButton("🔧 安装前缀", GuanliColors.accent, Modifier) { onWineboot() }
            GSmallButton(
                "💾 保存配置", GuanliColors.accent, Modifier,
                enabled = dirty
            ) {
                cs.launch(Dispatchers.IO) {
                    val err = ContainerManager.update(edit)
                    withContext(Dispatchers.Main) {
                        if (err == null) {
                            saveErr = null
                            android.widget.Toast.makeText(
                                context, "配置已保存", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            onSaved()
                        } else {
                            saveErr = err
                        }
                    }
                }
            }
            Spacer(Modifier.width(gdp(4.dp)))
            Text(
                "wineguanli.py 同款布局 · LinBox 原生启动链",
                fontSize = gsp(10.sp), color = themeSec()
            )
        }
    }
}

// ===========================================================================
// 选项卡 0：基本设置（wineguanli.py create_basic_tab 逐组移植）
// ===========================================================================

@Composable
internal fun BasicSettingsTab(
    edit: ContainerData,
    wines: List<WineInstall>,
    onChange: (ContainerData) -> Unit
) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    // v3.3.28（用户实测 bug：分辨率回退）：FilePickBus 监听器由
    // DisposableEffect(edit.name) 注册 —— key 不变时闭包永不重建，捕获的
    // 是【首次组合时的 edit 参数快照】。用户改分辨率 → 保存 → 浏览选
    // exe 时，回调执行 onChange(旧edit.copy(exe=…)) → 分辨率被打回面板
    // 打开时的旧值 → 再保存即回退（与用户报告 100% 吻合）。
    // rememberUpdatedState 让闭包始终读到最新 edit —— Compose 官方范式。
    val currentEdit by androidx.compose.runtime.rememberUpdatedState(edit)

    // wineguanli.py：QFormLayout 基本配置组
    GGroup("基本配置") {
        GField("容器名称:") {
            Text(edit.name, fontSize = gsp(14.sp), fontWeight = FontWeight.Bold, color = themeFg())
        }
        GField("🏗️ 架构:") {
            GComboBox(
                options = listOf("x86_64", "arm64ec"),
                selected = if (edit.backend == ContainerBackend.ARM64EC) "arm64ec" else "x86_64",
                onSelect = { arch ->
                    onChange(
                        edit.copy(
                            backend = if (arch == "arm64ec") ContainerBackend.ARM64EC
                            else ContainerBackend.BOX64
                        )
                    )
                }
            )
        }
        GField("🍷 Wine版本:") {
            GComboBox(
                options = listOf("（默认）") + wines.map { it.displayLabel },
                selected = if (edit.wine.isBlank()) "（默认）" else wines.firstOrNull { it.name == edit.wine }?.displayLabel ?: edit.wine,
                onSelect = { label ->
                    val slot = wines.firstOrNull { it.displayLabel == label }?.name ?: ""
                    onChange(edit.copy(wine = if (label == "（默认）") "" else slot))
                }
            )
        }
        GField("🖥️ 分辨率:") {
            // wineguanli：可编辑下拉（全屏 + 8 档 + 自定义输入）
            val resOptions = listOf("全屏", "800x600", "1024x768", "1280x720", "1366x768", "1600x900", "1920x1080", "2560x1440")
            val cur = if (edit.screen == "native") "全屏" else edit.screen
            GComboBox(
                options = resOptions,
                selected = if (cur in resOptions) cur else resOptions.first(),
                onSelect = { v ->
                    applyResolution(edit, v, onChange, edit.flagSet.contains(ContainerFlags.INDEPENDENT))
                },
                customLabel = if (cur !in resOptions) cur else null
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = cur,
                    onValueChange = { v ->
                        applyResolution(edit, v, onChange, edit.flagSet.contains(ContainerFlags.INDEPENDENT))
                    },
                    label = { Text("自定义（如 1280x1024）", fontSize = gsp(10.sp)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        GField("🌐 语言:") {
            // v3.3.2 恢复（用户："语言设置你给去掉了？那个更改后可以更改
            // 启动配置语言"）：显式档直接作为 LANG/LC_ALL（配合 wine
            // 0009 补丁在 bionic 上生效），auto 档跟随 /opt/locale.conf。
            GComboBox(
                options = ContainerLangs.DISPLAY,
                selected = ContainerLangs.displayOf(edit.lang),
                onSelect = { v -> onChange(edit.copy(lang = ContainerLangs.valueOf(v))) }
            )
        }
        GField("⏱️ 帧数限制:") {
            GComboBox(
                options = listOf("默认(不限制)", "60", "120"),
                selected = when (edit.frame) { "60" -> "60"; "120" -> "120"; else -> "默认(不限制)" },
                onSelect = { v ->
                    onChange(edit.copy(frame = when (v) { "60" -> "60"; "120" -> "120"; else -> "0" }))
                }
            )
        }
    }

    // v3.3.3（用户问题 5）：/opt 生态配置读取与写回 —— 容器编辑此前只动
    // container.conf，/opt/conf 的生效值既看不到也改不了（用户："容器编辑
    // 所有东西都没有读取 opt 下的相关配置，也无法更改选项来改变 opt 下的
    // 配置"）。本组直接读写生态包 conf（wineguanli.py 同款 replace-or-
    // append 语义，路径与启动链 source 同源 —— 改的就是启动用的那份），
    // 下次启动 wine 即生效。
    // v3.3.8（用户问题 1）：本组只保留 locale —— cores.conf / env.conf
    // 移入「高级设置 → 🌐 生态配置」页（右侧不再拥挤，改读改写能力不变）。
    GGroup("🌐 语言（App 内部 conf · locale.conf，全局）") {
        val optScope = rememberCoroutineScope()
        val optCtx = LocalContext.current
        var optRootLabel by remember { mutableStateOf("探测中…") }
        var optLocale by remember { mutableStateOf("") }
        var optDetected by remember { mutableStateOf(false) }

        fun reloadOpt() {
            val r = OptConfSupport.root()
            optDetected = r != null
            // v3.3.39：conf 统一到 APK 内部 —— 恒非空；装了 /opt 生态时
            // UI 保存会写透同步过去（终端链/生态脚本读到同一份值）。
            optRootLabel = r?.absolutePath ?: "App 内部 conf 目录不可用（linbox-conf）"
            optLocale = OptConfSupport.locale() ?: ""
        }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { reloadOpt() }
        }
        fun saveOpt(op: () -> String?) {
            optScope.launch {
                val err = withContext(Dispatchers.IO) { op() }
                withContext(Dispatchers.IO) { reloadOpt() }
                android.widget.Toast.makeText(
                    optCtx, err ?: "已写入 App 内部 conf（locale.conf，下次启动 wine 生效；装了 /opt 生态会同步过去）",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        GNote(optRootLabel)
        if (optDetected) {
            GField("🌐 locale.conf（语言，跟随档的 LANG/LC_ALL 来源）:") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = optLocale,
                        onValueChange = { optLocale = it },
                        placeholder = { Text("如 zh_CN.UTF-8", fontSize = gsp(10.sp)) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(gdp(6.dp)))
                    GSmallButton("💾", GuanliColors.greenDeep, Modifier) {
                        saveOpt { OptConfSupport.writeLocale(optLocale) }
                    }
                }
            }
            GNote("cores.conf / env.conf 已移至「高级设置 → 🌐 生态配置」页")
        }
    }

    // wineguanli.py：窗口模式组
    GGroup("窗口模式") {
        val independent = edit.flagSet.contains(ContainerFlags.INDEPENDENT)
        GCheckRow("🪟 独立窗口模式 (不支持proton)", independent) { on ->
            // v3.3.9 修复（用户问题 3：勾选不了独立窗口）：旧版先 onChange 改
            // flags、再用【旧 edit】调 applyResolution → 第二次 onChange 把
            // 第一次写入的 flags 覆盖回去，勾选永远不生效。改为单次
            // onChange 合并 flags + screen + dmode。
            val flags = edit.flagSet.toMutableSet()
            if (on) flags.add(ContainerFlags.INDEPENDENT) else flags.remove(ContainerFlags.INDEPENDENT)
            val screen = if (edit.screen == "native") "native" else edit.screen
            val dmode = when {
                on -> DisplayMode.F
                screen == "native" -> DisplayMode.OFF
                else -> DisplayMode.D
            }
            onChange(
                edit.copy(
                    flags = ContainerFlags.serialize(flags),
                    screen = screen,
                    dmode = dmode
                )
            )
        }
        GNote("💡 独立窗口: -f（注册表虚拟桌面，带 wine 界面）；非独立: -d（命令行虚拟桌面）；分辨率选全屏 = 无 wine 界面真全屏")
    }

    // wineguanli.py：EXE 文件组（留空则运行时再选）
    GGroup("EXE文件 (留空则运行时选择)") {
        DisposableEffect(edit.name) {
            val unregister = com.linbox.apps.filemanager.FilePickBus.listen(
                targetApp = "containers_basic_${edit.name}",
                targetWindow = ""
            ) { path ->
                onChange(currentEdit.copy(exe = path))
                picking = false
            }
            onDispose { unregister() }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = edit.exe,
                onValueChange = { onChange(edit.copy(exe = it)) },
                placeholder = { Text("选择要运行的EXE文件，留空则运行时选择…", fontSize = gsp(10.sp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(gdp(8.dp)))
            GSmallButton("📂 浏览", GuanliColors.blueFab, Modifier) {
                picking = true
                com.linbox.core.shell.ShellController.showFiles(
                        pickMode = "exe",
                        targetApp = "containers_basic_${edit.name}",
                        targetWindow = ""
                    )
            }
        }
        if (picking) {
            GNote("已在文件资源管理器中选择…（点击 exe 文件自动回填）")
        }
    }

    // wineguanli.py：高级启动选项组（startfex.sh / startwine.sh 选项同款）
    GGroup("高级启动选项") {
        GCheckFlow(
            items = listOf(
                ContainerFlags.DX11 to "强制DX11启动 (-dx11)",
                ContainerFlags.OPENGL to "OpenGL驱动 (opengl3)",
                ContainerFlags.UNITY to "Unity单线程渲染 (-force-gfx-direct)",
                ContainerFlags.F5TASK to "F5任务管理器 (随容器启动)",
                ContainerFlags.FEXMODE to "FEX模式(勾选才注入HODLL=libwow64fex.dll, 默认不注入)",
                ContainerFlags.WOWBOXMODE to "wowbox64模式(勾选才注入HODLL=wowbox64.dll, 部分32位游戏)",
                ContainerFlags.CLOSESERVICE to "关闭service服务",
                ContainerFlags.DEBUG to "调试日志"
            ),
            checked = { it in edit.flagSet },
            onToggle = { flag, on ->
                val flags = edit.flagSet.toMutableSet()
                if (on) flags.add(flag) else flags.remove(flag)
                onChange(edit.copy(flags = ContainerFlags.serialize(flags)))
            }
        )
        // v3.3.9（用户问题 2）：自定义启动参数框（追加在 exe 之后，
        // startfex.sh additional_args 同位；空白切分）
        GField("🧾 自定义启动参数（追加在 exe 之后）") {
            OutlinedTextField(
                value = edit.uargs,
                onValueChange = { onChange(edit.copy(uargs = it)) },
                placeholder = { Text("如 -windowed -nosound …（空白切分，追加在程序参数末尾）", fontSize = gsp(10.sp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                modifier = Modifier.fillMaxWidth()
            )
        }
        GNote(
            "启动参数一律追加在 exe 之后：强制DX11 → -dx11；" +
                "OpenGL → --rendering-driver opengl3；Unity单线程 → -force-gfx-direct；" +
                "F5任务管理器 → 随容器启动 /opt/apps/f5taskmgr.exe；" +
                "FEX模式 → 勾选才注入 HODLL=libwow64fex.dll；wowbox64模式 → 勾选才注入 HODLL=wowbox64.dll" +
                "（部分 32 位游戏需要 box64-in-wow64；默认都不注入，wine 内部缺省即 FEX；两档同勾时 FEX模式 优先；" +
                "HODLL 二选一取值）；" +
                "自定义参数里写 hodll=… / hodll64=…（大小写均可）会自动提升为环境变量不再传给游戏（优先于两个勾选档）；" +
                "调试日志 → LinBox 会话日志本就常显（标记槽位）"
        )
    }
}

/**
 * wineguanli.py 的分辨率/窗口模式 → LinBox screen/dmode 联动：
 *  独立窗口 + 全屏   → screen=native, dmode=F（-f）
 *  独立窗口 + WxH    → screen=WxH,   dmode=F（-f WxH）
 *  非独立 + WxH      → screen=WxH,   dmode=D（-d WxH）
 *  非独立 + 全屏     → screen=native, dmode=OFF
 */
private fun applyResolution(
    edit: ContainerData,
    resLabel: String,
    onChange: (ContainerData) -> Unit,
    independent: Boolean
) {
    val screen = if (resLabel == "全屏") "native" else resLabel
    val dmode = when {
        independent -> DisplayMode.F
        screen == "native" -> DisplayMode.OFF
        else -> DisplayMode.D
    }
    onChange(edit.copy(screen = screen, dmode = dmode))
}

// ===========================================================================
// 选项卡 1：图形驱动（wineguanli.py create_graphics_tab：四个 /opt 下拉）
// ===========================================================================

@Composable
internal fun GraphicsDriverTab(
    edit: ContainerData,
    onChange: (ContainerData) -> Unit
) {
    // wineguanli.py load_driver_versions：默认/不使用 + 目录内 tar 包
    var zinkTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var dxvkTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var wined3dTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var fexcoreTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    // v3.3.16（上游实现 DriverPackages 模型）：Vulkan 驱动 = 类型
    //（turnip / wrapper）+ 版本（独立目录多驱动共存）+ ➕ yuzu 式安装
    // v3.3.17（用户问题：wrapper 选不了/无列表）：wrapper 版本列表改双源 ——
    // 已装独立目录驱动 + rootfs/opt/wrapper tar 包（wineguanli.py 参考行为：
    // 下拉自动扫描 /opt/wrapper，选中后运行前自动解压安装）。
    var installedDrivers by remember { mutableStateOf<List<WrapperDriverSupport.DriverEntry>>(emptyList()) }
    var tzTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    // v3.3.37：专有驱动包（yuzu/高通）独立目录 /opt/yuzu —— 与 /opt/tz
    //（turnip）分列选择（用户："wrapper和yuzu类驱动包括系统驱动安装
    // 怎么全跑到turnip(opt/tz)那里了，安装应该放在对应的目录进行选择"）。
    var yuzuTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var wrapperTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var driversTick by remember { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    LaunchedEffect(Unit, driversTick) {
        withContext(Dispatchers.IO) {
            zinkTars = GuanliSupport.scanOpt("zink")
            dxvkTars = GuanliSupport.scanOpt("dxvk1")
            wined3dTars = GuanliSupport.scanOpt("vkord3d")
            fexcoreTars = GuanliSupport.scanOpt("fexcore")
            tzTars = GuanliSupport.scanOpt("tz")
            yuzuTars = GuanliSupport.scanOpt("yuzu")
            // v3.3.17：wrapper 同步扫描 /opt/wrapper（参考 wineguanli.py：
            // 图形驱动页的 Wrapper 下拉自动扫描 tar 包；RootfsLauncher 0.5 在
            // 运行时对未安装的槽位自动安装）
            wrapperTars = GuanliSupport.scanOpt("wrapper")
            installedDrivers = WrapperDriverSupport.listDrivers()
        }
    }

    // v3.3.18（用户需求："选择 wrapper 时同时更改 conf 里的 path.conf，
    // 选择 turnip 时也要改，三个选项都要改路径"）：Vulkan 驱动选择 →
    // 同步改写 opt/conf/path.conf 的 VK_ICD_FILENAMES 行（wineguanli.py
    // 参考行为：图形驱动页的驱动选择直接落到 conf，选择即生效、conf 与
    // 容器选择永远一致）：
    //  - Turnip（Mesa 内置）→ rootfs 自带 freedreno ICD 路径；
    //  - Wrapper 驱动 / Turnip 驱动包槽位 → 独立驱动库 drivers/<slot>/icd.json；
    //  - 槽位尚未安装（/opt tar 运行时自动装）→ 跳过（不写悬空路径 ——
    //    loader 打不开 ICD 会致 Vulkan 不可用；RootfsLauncher 6a 激活链
    //    安装后补写）。
    // key 变化即触发：三个类型选项 / 版本下拉 / ➕ 安装后选择 / 🗑 卸载，
    // 全部经由 edit.wrapper / edit.turnip 落位，无一旁路。
    LaunchedEffect(edit.wrapper, edit.turnip) {
        withContext(Dispatchers.IO) {
            val wrapperActive = edit.wrapper.isNotBlank() && edit.wrapper != "off"
            val tzActive = edit.turnip.isNotBlank() && edit.turnip != "off"
            val wineDir = ContainerManager.listWines()
                .firstOrNull { it.name == edit.wine }?.let { File(it.dir) }
            when {
                // v3.3.32：系统驱动档 —— 先装内置 wrapper 栈再同步其 ICD
                edit.wrapper == WrapperDriverSupport.SYSTEM_SLOT -> {
                    runCatching {
                        WrapperDriverSupport.ensureWrapperStackAvailable(context) { }
                    }
                    WrapperDriverSupport.activationFor(WrapperDriverSupport.SYSTEM_SLOT)?.icd
                        ?.absolutePath?.let { icd ->
                            GuanliSupport.syncPathConfVkIcd(icd, wineDir)
                        }
                }
                wrapperActive || tzActive -> {
                    val slot = if (wrapperActive) edit.wrapper else edit.turnip
                    // 已装槽位才写（ICD 真实存在）；tar-only 槽位等运行时装好后补写
                    WrapperDriverSupport.icdFor(slot)?.absolutePath?.let { icd ->
                        GuanliSupport.syncPathConfVkIcd(icd, wineDir)
                    }
                }
                // Turnip（Mesa 内置）/ 默认 —— 写 rootfs 自带 freedreno ICD
                //（rootfs 无该文件时注释掉该行，loader 默认搜索同效果）
                else -> GuanliSupport.syncPathConfVkIcd(
                    WrapperDriverSupport.bundledTurnipIcd()?.absolutePath, wineDir
                )
            }
        }
    }

    // yuzu 式安装：文件管理器选 zip/tar 包 → 直接装进驱动库（独立目录，
    // 装完立即出现在版本列表）—— 上游实现 promptInstallDriverFromFile 同款
    DisposableEffect(driversTick) {
        val unregister = com.linbox.apps.filemanager.FilePickBus.listen(
            targetApp = "containers_driverinstall",
            targetWindow = ""
        ) { path ->
            if (path.isNotBlank()) {
                installing = true
                cs.launch(Dispatchers.IO) {
                    val err = runCatching {
                        WrapperDriverSupport.install(path) { }
                    }.getOrElse { "安装异常：${it.message}" }
                    withContext(Dispatchers.Main) {
                        installing = false
                        driversTick++
                        android.widget.Toast.makeText(
                            context,
                            err ?: "✅ 驱动已安装（$path）—— 在版本列表中选择即可启用",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        onDispose { unregister() }
    }

    val dirNote = "目录内未发现 tar 包。把驱动 tar.gz 放进 rootfs/opt/<目录> 后重进本页即可选择"

    // ------------------------------------------------------------------
    // Vulkan 驱动（v3.3.16：类型 + 版本，上游实现 同款并列选择）
    // ------------------------------------------------------------------
    GGroup("Vulkan 驱动（turnip / wrapper / 系统专有）") {
        // 类型：从容器数据推导（wrapper 优先，其次 turnip 槽位，否则 Mesa 内置）。
        // v3.3.37：专有包（yuzu/高通）档 —— 纯状态推导（已装槽位的 meta
        // libraryName + /opt/yuzu tar 名匹配），不做磁盘扫描（组合线程安全）。
        val vkType = when {
            edit.wrapper == WrapperDriverSupport.SYSTEM_SLOT -> "system"
            edit.wrapper.isNotBlank() && edit.wrapper != "off" -> "wrapper"
            edit.turnip.isNotBlank() && edit.turnip != "off" -> when {
                installedDrivers.firstOrNull { it.slot == edit.turnip && !it.wrapperStack }
                    ?.let { WrapperDriverSupport.isProprietaryLib(it.libraryName) } == true -> "yuzu"
                yuzuTars.any { it.display == edit.turnip } -> "yuzu"
                else -> "tz"
            }
            else -> "bundled"
        }
        GComboBox(
            options = listOf(
                "Turnip（Mesa 内置，推荐）",
                "Wrapper 驱动（已安装）",
                // v3.3.37：turnip 包与 yuzu/高通专有包【分列】—— 各自独立
                // 目录 /opt/tz、/opt/yuzu，各自版本下拉，不再混在一个入口。
                "Turnip 驱动包（/opt/tz）",
                "专有驱动包（yuzu / 高通，/opt/yuzu）",
                // v3.3.32（用户需求）：高通/联发科【系统专有】驱动档 ——
                // 高通 615.50 / 849 等系统驱动与 yuzu 专有驱动包的通路
                "系统驱动（高通/联发科专有）"
            ),
            selected = when (vkType) {
                "system" -> "系统驱动（高通/联发科专有）"
                "wrapper" -> "Wrapper 驱动（已安装）"
                "yuzu" -> "专有驱动包（yuzu / 高通，/opt/yuzu）"
                "tz" -> "Turnip 驱动包（/opt/tz）"
                else -> "Turnip（Mesa 内置，推荐）"
            },
            onSelect = { label ->
                when {
                    label.startsWith("Turnip（Mesa") ->
                        onChange(edit.copy(wrapper = "", turnip = ""))
                    label.startsWith("系统驱动") ->
                        // v3.3.32：系统专有驱动档 —— sentinel 值 wrapper="system"，
                        // 激活时经内置 wrapper 栈回退加载 /system/lib64/libvulkan.so
                        //（设备厂商专有驱动：高通 Adreno / 联发科 Mali 同通路）
                        onChange(edit.copy(wrapper = WrapperDriverSupport.SYSTEM_SLOT, turnip = ""))
                    label.startsWith("Wrapper") ->
                        // v3.3.17：类型切到 wrapper 时预选首个可用版本 ——
                        // 优先已装驱动，其次 /opt/wrapper tar 包（旧版只看已装列表，
                        // 未装且 /opt 无包时选中后下方空空如也 → “强制选择也没列表”）。
                        // v3.3.21：只预选 wrapper 栈槽位（含 libvulkan_wrapper.so）——
                        // 旧版 firstOrNull 会把 turnip 包顶进 Wrapper 位（用户
                        // 实测截图：Wrapper 位显示“turnip-26.2.1 / 库：
                        // libvulkan_freedreno.so”，选上必无效）
                        onChange(edit.copy(
                            wrapper = installedDrivers.firstOrNull { it.wrapperStack }?.slot
                                ?: wrapperTars.firstOrNull()?.display ?: "",
                            turnip = ""
                        ))
                    // v3.3.37：专有驱动包（yuzu/高通）独立分支 —— 预选首个
                    // 专有槽位（vulkan.ad* 命名族），其次 /opt/yuzu tar 包。
                    label.startsWith("专有驱动包") ->
                        onChange(edit.copy(
                            turnip = installedDrivers.firstOrNull {
                                !it.wrapperStack && WrapperDriverSupport.isProprietaryLib(it.libraryName)
                            }?.slot ?: yuzuTars.firstOrNull()?.display ?: "",
                            wrapper = ""
                        ))
                    else ->
                        // v3.3.20：切到 turnip 类型时预选首个可用版本 —— 优先
                        // 已装 turnip 槽位（➕ zip 安装），其次 /opt/tz tar 包
                        //（与 wrapper 分支同款双源预选）；v3.3.37 起只认
                        // turnip 本体（libvulkan_freedreno），专有包在“专有
                        // 驱动包”分支预选。
                        onChange(edit.copy(
                            turnip = installedDrivers.firstOrNull {
                                !it.wrapperStack && !WrapperDriverSupport.isProprietaryLib(it.libraryName)
                            }?.slot ?: tzTars.firstOrNull()?.display ?: "",
                            wrapper = ""
                        ))
                }
            }
        )
        when (vkType) {
            // v3.3.32：系统专有驱动档 —— 零包零配置：wrapper 栈（内置资产，
            // 选中时自动安装）回退加载设备厂商专有驱动（/system/lib64/libvulkan.so）
            "system" -> {
                val stackInstalled = installedDrivers.any { it.wrapperStack }
                GNote(
                    (if (stackInstalled) "✓ wrapper 栈就绪（${
                        installedDrivers.firstOrNull { it.wrapperStack }?.slot ?: ""
                    }）\n" else "wrapper 栈将在首次启动时自动从 APK 内置资产安装（5.5MB 一次性）\n") +
                        "系统专有驱动档：VK_ICD_FILENAMES → 内置 wrapper 栈（libvulkan_wrapper.so），\n" +
                        "不注入 ADRENOTOOLS_DRIVER_* —— wrapper 自行回退加载\n" +
                        "/system/lib64/libvulkan.so = 设备厂商专有 Vulkan 驱动\n" +
                        "  * 高通 Adreno：系统驱动（615.50 / 512.x / 849 系列等）\n" +
                        "  * 联发科 Mali：天珑系列系统驱动同通路\n" +
                        "X11 WSI 由 wrapper 的 xcb 侧桥接（DXVK 交换链可建）；\n" +
                        "性能/兼容性取决于设备厂商驱动版本 —— 不如 turnip 稳时可切回"
                )
            }
            "wrapper" -> {
                // v3.3.17：双源版本列表 —— 已装独立目录驱动（v3.3.16 模型，
                // 即选即用）+ /opt/wrapper tar 包（wineguanli.py 参考行为：下拉
                // 自动扫描，选中后 RootfsLauncher 0.5 在运行会话自动安装）。
                // 同名去重：包安装后的 slot 名 == 包显示名，tar 项隐藏。
                val wrapperOpts = buildList {
                    add("默认（切回 Mesa 内置）" to "")
                    // v3.3.21：Wrapper 下拉只列 wrapper 栈槽位（含
                    // libvulkan_wrapper.so 的包，如 wrapper-22）—— 旧版把
                    // turnip 类包也列进来，选中后 ICD 指向裸 turnip，
                    // wrapper 注入链路完全没走（用户实测“选了 wrapper
                    // 依旧无效”的 UI 根因之一）
                    installedDrivers.filter { it.wrapperStack }
                        .forEach { add(it.slot to it.slot) }
                    wrapperTars.filter { t -> installedDrivers.none { it.slot == t.display } }
                        .forEach { add("${it.display}（/opt/wrapper 包，运行时自动安装）" to it.display) }
                }
                if (wrapperOpts.size == 1) {
                    GNote(
                        "尚未安装任何 wrapper 驱动，rootfs/opt/wrapper 内也未发现 tar 包 ——\n" +
                            "点下方 ➕ 从 zip/tar 包安装（社区驱动包 / wrapper 包），\n" +
                            "或把驱动包放进 rootfs/opt/wrapper 后重进本页即可选择"
                    )
                    // v3.3.32：一键安装 APK 内置 wrapper 栈（无需自己找包）——
                    // 高通/联发科专有驱动与 yuzu 专有驱动包的公共通路
                    GSmallButton(
                        if (installing) "⏳ 安装中…" else "📦 安装内置 wrapper 栈（专有驱动通路）",
                        GuanliColors.blueFab, Modifier.fillMaxWidth()
                    ) {
                        installing = true
                        cs.launch(Dispatchers.IO) {
                            val err = runCatching {
                                WrapperDriverSupport.ensureBundledWrapperStack(context) { }
                            }.getOrElse { "安装异常：${it.message}" }
                            withContext(Dispatchers.Main) {
                                installing = false
                                driversTick++
                                android.widget.Toast.makeText(
                                    context,
                                    err ?: "✅ 内置 wrapper 栈已安装 —— 高通/联发科系统驱动与 yuzu 专有驱动包的通路就绪",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } else {
                    GComboBox(
                        options = wrapperOpts.map { it.first },
                        selected = wrapperOpts.firstOrNull { it.second == edit.wrapper }?.first
                            ?: edit.wrapper.ifBlank { "默认（切回 Mesa 内置）" },
                        onSelect = { label ->
                            wrapperOpts.firstOrNull { it.first == label }?.second
                                ?.let { onChange(edit.copy(wrapper = it, turnip = "")) }
                        }
                    )
                    installedDrivers.firstOrNull { it.slot == edit.wrapper }?.let { d ->
                        GNote("库：${d.libraryName}（来源包 ${d.sourceFile}，目录 ${d.dir.name}/）")
                    }
                    if (edit.wrapper.isNotBlank() && installedDrivers.none { it.slot == edit.wrapper }
                        && wrapperTars.any { it.display == edit.wrapper }) {
                        GNote("${edit.wrapper}：运行时自动从 /opt/wrapper 安装到独立驱动库")
                    }
                }
            }
            "tz" -> {
                // v3.3.20（用户实测反馈修复）：已装 turnip 槽位（➕ zip 安装的
                // 包）此前【选不了】—— 本下拉只扫 /opt/tz 的 tar，
                // 装完的独立目录槽位根本不在列表里（即"装了也没法用"的 UI 根因）。
                // 现在与 wrapper 分支同款双源：已装槽位优先 + /opt/tz tar 包。
                // v3.3.37：只列 turnip 本体（libvulkan_freedreno 命名族）——
                // yuzu/高通专有包（vulkan.ad* 命名族）分列到"专有驱动包"档。
                val tzOpts = buildList {
                    add("默认（切回 Mesa 内置）" to "")
                    installedDrivers.filter { !it.wrapperStack && !WrapperDriverSupport.isProprietaryLib(it.libraryName) }
                        .forEach { add(it.slot to it.slot) }
                    tzTars.filter { t -> installedDrivers.none { it.slot == t.display } }
                        .forEach { add("${it.display}（/opt/tz 包，运行时自动安装）" to it.display) }
                }
                if (tzOpts.size == 1) {
                    DriverCombo(
                        tars = tzTars, value = edit.turnip,
                        dirNote = "rootfs/opt/tz 内未发现 tar 包；也可直接用 ➕ 安装（推荐，装进独立驱动库）",
                        onSelect = { onChange(edit.copy(turnip = it, wrapper = "")) }
                    )
                } else {
                    GComboBox(
                        options = tzOpts.map { it.first },
                        selected = tzOpts.firstOrNull { it.second == edit.turnip }?.first
                            ?: edit.turnip.ifBlank { "默认（切回 Mesa 内置）" },
                        onSelect = { label ->
                            tzOpts.firstOrNull { it.first == label }?.second
                                ?.let { onChange(edit.copy(turnip = it, wrapper = "")) }
                        }
                    )
                    installedDrivers.firstOrNull { it.slot == edit.turnip && !it.wrapperStack }?.let { d ->
                        GNote("库：${d.libraryName}（来源包 ${d.sourceFile}，目录 ${d.dir.name}/）")
                    }
                    if (edit.turnip.isNotBlank() && installedDrivers.none { it.slot == edit.turnip }
                        && tzTars.any { it.display == edit.turnip }) {
                        GNote("${edit.turnip}：运行时自动从 /opt/tz 安装到独立驱动库")
                    }
                }
            }
            // v3.3.37：yuzu/高通专有驱动包独立档（用户："安装应该放在对应的
            // 目录进行选择"）—— 版本列表 = 已装专有槽位（vulkan.ad* 命名族）
            // + /opt/yuzu tar 包；激活语义与 turnip 槽位同轨（android 平台
            // 构建自动经 wrapper 栈路由，RootfsLauncher 6a / 脚本 apply_vk_driver
            // 已有逻辑零改动）。
            "yuzu" -> {
                val yuzuOpts = buildList {
                    add("默认（切回 Mesa 内置）" to "")
                    installedDrivers.filter { !it.wrapperStack && WrapperDriverSupport.isProprietaryLib(it.libraryName) }
                        .forEach { add(it.slot to it.slot) }
                    yuzuTars.filter { t -> installedDrivers.none { it.slot == t.display } }
                        .forEach { add("${it.display}（/opt/yuzu 包，运行时自动安装）" to it.display) }
                }
                if (yuzuOpts.size == 1) {
                    DriverCombo(
                        tars = yuzuTars, value = edit.turnip,
                        dirNote = "rootfs/opt/yuzu 内未发现 tar 包；把 yuzu 格式驱动 zip/tar 放进去或直接用 ➕ 安装（v819.2 / v849 / q615.50 等，运行时自动经 wrapper 栈路由）",
                        onSelect = { onChange(edit.copy(turnip = it, wrapper = "")) }
                    )
                } else {
                    GComboBox(
                        options = yuzuOpts.map { it.first },
                        selected = yuzuOpts.firstOrNull { it.second == edit.turnip }?.first
                            ?: edit.turnip.ifBlank { "默认（切回 Mesa 内置）" },
                        onSelect = { label ->
                            yuzuOpts.firstOrNull { it.first == label }?.second
                                ?.let { onChange(edit.copy(turnip = it, wrapper = "")) }
                        }
                    )
                    installedDrivers.firstOrNull { it.slot == edit.turnip && !it.wrapperStack }?.let { d ->
                        GNote("库：${d.libraryName}（来源包 ${d.sourceFile}，目录 ${d.dir.name}/）—— android 平台构建将自动经 wrapper 栈路由（X11 WSI 由 wrapper 提供）")
                    }
                    if (edit.turnip.isNotBlank() && installedDrivers.none { it.slot == edit.turnip }
                        && yuzuTars.any { it.display == edit.turnip }) {
                        GNote("${edit.turnip}：运行时自动从 /opt/yuzu 安装到独立驱动库")
                    }
                }
            }
            else -> GNote("rootfs 自带 Mesa Turnip（freedreno ICD，msm/kgsl 双 KMD）—— 与 wrapper 并列共存，零冲突")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
            GSmallButton(
                if (installing) "⏳ 安装中…" else "➕ 安装驱动（zip / tar）",
                GuanliColors.blueFab, Modifier.weight(1f)
            ) {
                com.linbox.core.shell.ShellController.showFiles(
                    // v3.3.17：driver = 驱动包专用选择模式（zip/tar 过滤 +
                    // 直达 Download）。旧版误传 "text"（记事本文本选择）→
                    // 提示选 txt/log。
                    pickMode = "driver",
                    targetApp = "containers_driverinstall",
                    targetWindow = ""
                )
            }
            // v3.3.20：卸载按钮对 wrapper 与 turnip 槽位都开放（旧版仅 wrapper）
            val activeVkSlot = when {
                edit.wrapper.isNotBlank() && edit.wrapper != "off" -> edit.wrapper
                edit.turnip.isNotBlank() && edit.turnip != "off" -> edit.turnip
                else -> ""
            }
            if (activeVkSlot.isNotBlank() && installedDrivers.any { it.slot == activeVkSlot }) {
                GSmallButton("🗑 卸载", GuanliColors.red, Modifier.weight(1f)) {
                    cs.launch(Dispatchers.IO) {
                        val err = runCatching {
                            WrapperDriverSupport.uninstall(activeVkSlot) { }
                        }.getOrElse { "卸载异常：${it.message}" }
                        withContext(Dispatchers.Main) {
                            if (err == null) onChange(edit.copy(wrapper = "", turnip = ""))
                            driversTick++
                            android.widget.Toast.makeText(
                                context, err ?: "✅ 已卸载（切回 Mesa 内置 Turnip）",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
        GNote(
            "v3.3.16 新机制：每个驱动装在独立目录"
                + "（usr/share/linbox-wrapper/drivers/<名>/），rootfs 任何文件不被覆盖 ——"
                + " turnip 与 wrapper 并列选择、多版本共存、切换零文件读写；"
                + " 支持 rootfs 布局包 / meta.json 包 / 社区裸包（zip 或 tar）\n"
                + "v3.3.18：三个选项切换时自动同步改写 rootfs/opt/conf/path.conf 的"
                + " VK_ICD_FILENAMES 行（选择即落 conf，其余 conf 内容一字不动）——"
                + " 运行会话日志的「path.conf 已同步」行可核对实际写入值\n"
                + "v3.3.32：『系统驱动（高通/联发科专有）』档零包零配置 —— 内置 wrapper 栈"
                + "（APK 资产，首次选中自动安装）回退加载设备厂商专有驱动；"
                + "专有驱动社区格式包（v819.2 / v849 / q615.50 等）➕ 安装后"
                + "自动经 wrapper 栈路由（专有驱动无 X11 WSI，直挂必黑屏）"
        )
    }
    GGroup("Zink 驱动") {
        DriverCombo(
            tars = zinkTars, value = edit.zink,
            dirNote = dirNote,
            onSelect = { onChange(edit.copy(zink = it)) }
        )
    }
    GGroup("DXVK") {
        DriverCombo(
            tars = dxvkTars,
            value = if (edit.dxvk == ContainerData.DXVK_AUTO) "" else if (edit.dxvk == ContainerData.DXVK_OFF) "off" else edit.dxvk,
            dirNote = "默认=APK 内置资产自动注入；不使用=关闭注入；tar 槽位（rootfs/opt/dxvk1）运行前自动解压到容器 C 盘 windows（v3.3.9）",
            onSelect = { v ->
                onChange(
                    edit.copy(
                        dxvk = when (v) {
                            "" -> ContainerData.DXVK_AUTO
                            "off" -> ContainerData.DXVK_OFF
                            else -> v
                        }
                    )
                )
            }
        )
    }
    GGroup("WineD3D") {
        DriverCombo(
            tars = wined3dTars, value = edit.wined3d,
            dirNote = dirNote,
            onSelect = { onChange(edit.copy(wined3d = it)) }
        )
    }
    // v3.3.10（用户问题 1）：FEXCore 选项框 —— /opt/fexcore 独立目录，
    // 逻辑同 DXVK/WineD3D，解压到对应容器 C 盘 windows
    GGroup("FEXCore") {
        DriverCombo(
            tars = fexcoreTars, value = edit.fexcore,
            dirNote = dirNote,
            onSelect = { onChange(edit.copy(fexcore = it)) }
        )
    }
    GNote(
        "图形驱动槽位：Vulkan 驱动（turnip/wrapper，上方新机制）、Zink(/opt/zink)、DXVK(/opt/dxvk1)、" +
            "WineD3D(/opt/vkord3d)、FEXCore(/opt/fexcore)。运行容器时：" +
            "Vulkan 驱动 → VK_ICD_FILENAMES 指向独立目录 ICD（含用户 turnip 包，" +
            "GL 自动联动 zink 吃驱动加速）；Zink → rootfs/usr/lib；" +
            "DXVK/WineD3D/FEXCore → 对应容器 C 盘 windows"
    )
}

/** 驱动下拉：默认 / 不使用 + /opt 内 tar 包列表（wineguanli load_driver_versions）。 */
@Composable
private fun DriverCombo(
    tars: List<GuanliSupport.OptTar>,
    value: String,
    dirNote: String,
    onSelect: (String) -> Unit
) {
    val options = buildList {
        add("默认" to "")
        add("不使用" to "off")
        tars.forEach { add(it.display to it.display) }
    }
    val selectedLabel = options.firstOrNull { it.second == value }?.first ?: "默认"
    GComboBox(
        options = options.map { it.first },
        selected = selectedLabel,
        onSelect = { label -> options.firstOrNull { it.first == label }?.second?.let(onSelect) }
    )
    if (tars.isEmpty()) {
        GNote(dirNote)
    }
}

// ===========================================================================
// 选项卡 2：CPU 核心（wineguanli.py create_cpu_tab_for_container）
// ===========================================================================

@Composable
internal fun CpuCoresTab(
    edit: ContainerData,
    onChange: (ContainerData) -> Unit
) {
    val total = Runtime.getRuntime().availableProcessors()
    val primary = WineEnvironment.parseCoreSpec(edit.cores)
        .filter { it in 0 until total }.toSortedSet()
    val secondary = (0 until total).filter { it !in primary }

    GGroup("CPU核心分配（勾选为主核心）") {
        GCheckFlow(
            items = (0 until total).map { it.toString() to "核心$it" },
            checked = { it.toIntOrNull() in primary },
            onToggle = { name, on ->
                val idx = name.toIntOrNull() ?: return@GCheckFlow
                val s = primary.toMutableSet()
                if (on) s.add(idx) else s.remove(idx)
                onChange(edit.copy(cores = WineEnvironment.formatCoreSpec(s.sorted())))
            }
        )
        Spacer(Modifier.height(gdp(4.dp)))
        // wineguanli.py：核心配置信息组（主核心/副核心）
        Text(
            "主核心: ${WineEnvironment.formatCoreSpec(primary.toList()).ifEmpty { "无" }}\n" +
                "副核心: ${WineEnvironment.formatCoreSpec(secondary).ifEmpty { "无" }}",
            fontSize = gsp(12.sp), color = themeFg(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(gdp(5.dp)))
                .background(GuanliColors.rowAlt)
                .padding(gdp(10.dp))
        )
    }
    GGroup("说明") {
        GNote(
            "• 勾选的核心将分配给Wine/Windows应用程序使用\n" +
                "• 未勾选的核心分配给系统和后台任务使用\n" +
                "• 配置保存到容器（cores=），启动链以 PRIMARY_CORES/SECONDARY_CORES 注入\n" +
                "• 设备共 $total 核心（wineguanli.py 固定 8 核视图，这里展示真实核心数）"
        )
    }
}

// ===========================================================================
// 选项卡 3：函数库（wineguanli.py create_library_tab_for_container）
// ===========================================================================

@Composable
internal fun LibraryTab(
    edit: ContainerData,
    onChange: (ContainerData) -> Unit,
    onApplyNow: () -> Unit
) {
    // v3.3.8（用户问题 4）：DLL 加载序恢复显示 —— winefuanli.py 同款
    // dsound（DirectSound）/ dshow（DirectShow = quartz）三档下拉；
    // 「跟随安装包」= 不写注册表（v3.2.27 默认行为，完全由
    // rootfs/opt/prefix 安装包注册表决定），显式档才写 user.reg
    // DllOverrides（wineguanli.py 同款语义，保存/立即应用时落地）。
    GGroup("DLL 加载序（DirectSound / DirectShow）") {
        GField("🔊 DirectSound (dsound):") {
            GComboBox(
                options = listOf("跟随安装包", "原生优先 (builtin,native)", "库优先 (native,builtin)"),
                selected = when (edit.dsound) {
                    "builtin,native" -> "原生优先 (builtin,native)"
                    "native,builtin" -> "库优先 (native,builtin)"
                    else -> "跟随安装包"
                },
                onSelect = { v ->
                    onChange(edit.copy(dsound = when (v) {
                        "原生优先 (builtin,native)" -> "builtin,native"
                        "库优先 (native,builtin)" -> "native,builtin"
                        else -> "default"
                    }))
                }
            )
        }
        GField("🎬 DirectShow (quartz):") {
            GComboBox(
                options = listOf("跟随安装包", "原生优先 (builtin,native)", "库优先 (native,builtin)"),
                selected = when (edit.dshow) {
                    "builtin,native" -> "原生优先 (builtin,native)"
                    "native,builtin" -> "库优先 (native,builtin)"
                    else -> "跟随安装包"
                },
                onSelect = { v ->
                    onChange(edit.copy(dshow = when (v) {
                        "原生优先 (builtin,native)" -> "builtin,native"
                        "库优先 (native,builtin)" -> "native,builtin"
                        else -> "default"
                    }))
                }
            )
        }
        GNote(
            "跟随安装包 = 不写注册表，完全按 rootfs/opt/prefix 安装包的 user.reg；\n" +
                "显式档写入 HKCU\\Software\\Wine\\DllOverrides（保存后落地，重启容器生效）"
        )
    }
    GGroup("DPI设置 (屏幕缩放)") {
        val dpi = edit.dpi.toIntOrNull() ?: 96
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DPI值:", fontSize = gsp(13.sp), color = themeFg())
            Spacer(Modifier.width(gdp(8.dp)))
            Text(
                "$dpi (${dpi * 100 / 96}%)",
                fontSize = gsp(13.sp), fontWeight = FontWeight.Bold, color = GuanliColors.blueFab
            )
        }
        Slider(
            value = dpi.toFloat(),
            onValueChange = { onChange(edit.copy(dpi = it.toInt().coerceIn(60, 240).toString())) },
            valueRange = 60f..240f,
            steps = (240 - 60) / 20 - 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
            listOf("72 (75%)" to 72, "96 (100%)" to 96, "120 (125%)" to 120, "144 (150%)" to 144, "192 (200%)" to 192)
                .forEach { (label, v) ->
                    GSmallButton(label, Color(0xFF243049), Modifier, fg = themeFg()) {
                        onChange(edit.copy(dpi = v.toString()))
                    }
                }
        }
    }
    GGroup("Windows版本切换") {
        GComboBox(
            options = WindowsVersions.ALL.map { it.label },
            selected = WindowsVersions.fromId(edit.winver).label,
            onSelect = { label ->
                WindowsVersions.ALL.firstOrNull { it.label == label }?.let {
                    onChange(edit.copy(winver = it.id))
                }
            }
        )
    }
    GGroup("说明") {
        GNote(
            "1. DLL 加载序（dsound/quartz）：跟随安装包 = 完全按 rootfs/opt/prefix\n" +
                "   安装包注册表（v3.2.27 默认）；显式档写入 DllOverrides\n" +
                "2. DPI设置控制Windows应用程序的显示缩放比例\n" +
                "3. Windows版本设置会修改system.reg中的Windows版本信息\n" +
                "4. 配置保存到容器（dsound/dshow/dpi/winver），启动链自动写入注册表"
        )
    }
    // wineguanli.py：💾 立即应用设置
    GSmallButton("💾 立即应用设置", GuanliColors.greenDeep, Modifier.fillMaxWidth()) { onApplyNow() }
    GNote("立即应用 = 保存后当场写入前缀注册表（前缀未构建时由启动链自动补）")
}

// ===========================================================================
// 选项卡 4：更多配置（wineguanli.py create_advanced_tab：说明 + 打开按钮 + 状态）
// ===========================================================================

@Composable
internal fun MoreConfigTab(
    edit: ContainerData,
    onOpenMore: () -> Unit,
    onOpenMango: () -> Unit
) {
    var statusTick by remember { mutableIntStateOf(0) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "更多设置说明：\n\n点击下方按钮打开完整的更多设置对话框\n包含：CPU核心分配、函数库设置、DPI设置、\nWindows版本切换、环境变量配置等功能",
            fontSize = gsp(12.sp), color = themeFg(), lineHeight = gsp(18.sp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(gdp(5.dp)))
                .background(GuanliColors.rowAlt)
                .border(gdp(0.5.dp), themeSec().copy(alpha = 0.2f), RoundedCornerShape(gdp(5.dp)))
                .padding(gdp(16.dp))
        )
        Spacer(Modifier.height(gdp(10.dp)))
        GSmallButton("⚙️ 打开更多设置", GuanliColors.accent, Modifier.fillMaxWidth().height(gdp(48.dp))) {
            onOpenMore()
        }
        Spacer(Modifier.height(gdp(12.dp)))
    }
    // 当前配置状态（wineguanli.py refresh_advanced_config_status）
    GGroup("当前配置状态") {
        val envList = GuanliSupport.parseEnv(edit.env)
        Text(
            buildString {
                appendLine("架构: ${edit.backend.label}　Wine: ${edit.wine.ifEmpty { "默认" }}")
                appendLine("分辨率: ${if (edit.screen == "native") "全屏" else edit.screen}（${edit.dmode.label}）")
                appendLine("CPU主核心: ${edit.cores.ifEmpty { "全部（不隔离）" }}")
                appendLine("DPI=${edit.dpi}（dsound/quartz 加载序随 /opt/prefix 安装包）")
                appendLine("Windows: ${WindowsVersions.fromId(edit.winver).label}")
                appendLine("MANGOHUD: ${edit.mangohud.ifEmpty { "未配置" }}")
                appendLine("env 变量数: ${envList.size}" + if (envList.isEmpty()) "" else "（${envList.joinToString(", ") { it.first }}）")
            },
            fontSize = gsp(11.sp), color = themeSec(), lineHeight = gsp(16.sp)
        )
        if (edit.mangohud.isBlank()) {
            Spacer(Modifier.height(gdp(4.dp)))
            GSmallButton("📊 配置 MANGOHUD", GuanliColors.blueFab, Modifier) { onOpenMango() }
        }
        Spacer(Modifier.height(gdp(4.dp)))
        GSmallButton("🔄 刷新配置状态", GuanliColors.grey, Modifier) { statusTick++ }
    }
}

// ===========================================================================
// 高级设置页（wineguanli.py create_advanced_widget：4 选项卡）
// ⚡ DY配置 / 📦 Box配置 / 🍷 Wine安装 / 📊 MANGOHUD配置
//
// v3.3.7（用户问题 1："高级设置只有个生效容器，还占用特别大，缺失
// wineguanli.py 里的所有高级设置内容"）结构性修复：
//  - 旧版页头"生效容器"选择器没传 width → GComboBox 默认 fillMaxWidth
//    占满整行（"占用特别大"的实体），后面的说明文字被挤到 0 宽不可见；
//  - sel==null（无容器/列表未加载）时 return@Column 直接掐掉全部四个
//    选项卡 —— 但 wineguanli.py 的高级设置页（create_advanced_widget）
//    **根本没有"生效容器"这个概念也没有任何门禁**：DY/Box/Wine安装
//    是 /opt 全局配置，MANGOHUD 才需要选容器 —— 无容器时打开本页
//    看到的就只剩那一条选择器（"只有个生效容器"）；
//  - 现按参考逐面对齐：删页头选择器与门禁，四个选项卡恒渲染；容器
//    选择器收进 MANGOHUD 选项卡内部（唯一需要它的地方，紧凑定宽）。
// ===========================================================================

@Composable
private fun GuanliAdvancedPage(
    containers: List<ContainerData>,
    sel: ContainerData?,
    onRefresh: () -> Unit,
    onMango: (ContainerData) -> Unit,
    multiSelect: (MultiSelectRequest) -> Unit
) {
    val cs = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = gdp(0.dp),
            containerColor = themeCard(),
            contentColor = themeFg()
        ) {
            // v3.3.8（用户问题 1）：新增「🌐 生态配置」—— 容器编辑页 opt 组
            // 只留 locale，cores.conf / env.conf 全部移入此处（读写能力不变）
            // v3.3.40（用户需求 1）：新增「🧬 FEX配置」—— assets-ck 的
            // fex_env_vars/fex_presets 接入（放在 DY 配置旁边；15 个 FEX_*
            // 官方变量全量开放编辑，写入 fex_active.conf 启动链 source）。
            listOf("⚡ DY配置", "🧬 FEX配置", "📦 Box配置", "🍷 Wine安装", "📊 MANGOHUD配置", "🌐 生态配置")
                .forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, fontSize = gsp(12.sp), maxLines = 1) }
                    )
                }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(gdp(12.dp)),
            verticalArrangement = Arrangement.spacedBy(gdp(10.dp))
        ) {
            when (tab) {
                0 -> DyConfigTab(sel = sel, onSaved = onRefresh)
                1 -> FexConfigTab(onSaved = onRefresh)
                2 -> BoxConfigTab()
                3 -> WineInstallTab(onRefresh = onRefresh)
                4 -> MangoHudTab(
                    containers = containers,
                    sel = sel,
                    onMango = onMango,
                    onSetMango = { c, cfg ->
                        cs.launch(Dispatchers.IO) {
                            ContainerManager.update(c.copy(mangohud = cfg))
                            withContext(Dispatchers.Main) { onRefresh() }
                        }
                    }
                )
                5 -> OptEcoConfigTab()
            }
        }
    }
}

// ===========================================================================
// 🌐 生态配置（v3.3.8 新增）：/opt/conf 的 cores.conf / env.conf 编辑页
//
// 用户问题 1："右侧 opt 配置菜单那里保留 locale 即可，其他的全在高级
// 设置里，保证高级设置里正常读取和更改即可" —— 容器编辑页的
// /opt 生态组瘦身为仅 locale，本页承接 cores.conf（CPU 核心绑定，
// PRIMARY/SECONDARY 双键）与 env.conf（通用环境变量 KEY=VALUE）。
// 读写仍经 [OptConfSupport]（路径与启动链 readOptConfs source 完全
// 同源 —— 这里改的就是启动用的那份），保存后下次启动 wine 生效。
// ===========================================================================

@Composable
internal fun OptEcoConfigTab() {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf(false) }
    var rootLabel by remember { mutableStateOf("探测中…") }
    var cores by remember { mutableStateOf("") }
    var envText by remember { mutableStateOf("") }
    var busyMsg by remember { mutableStateOf<String?>(null) }

    fun reload() {
        loaded = true
        val r = OptConfSupport.root()
        detected = r != null
        // v3.3.39：conf 统一到 APK 内部（linbox-conf/）—— 恒非空，
        // 不再依赖 /opt 生态包；保存后写透同步到 /opt/conf。
        rootLabel = r?.absolutePath ?: "App 内部 conf 目录不可用（linbox-conf）"
        cores = OptConfSupport.primaryCores() ?: ""
        envText = OptConfSupport.envEntries().joinToString("\n") { (k, v) -> "$k=$v" }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { reload() }
    }

    if (!loaded) {
        Box(Modifier.fillMaxWidth().padding(gdp(24.dp)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(gdp(26.dp)), strokeWidth = gdp(2.5.dp))
        }
        return
    }

    GGroup("conf 生态状态（App 内部 · 唯一权威）") {
        GNote(rootLabel + "\n本页读写 App 内部 linbox-conf 的 cores.conf 与 env.conf —— v3.3.40 起启动链只读这一份（不再读 /opt/conf，释放启动时间）；保存后写透同步到 /opt 生态（终端链同值）")
    }

    if (!detected) {
        GGroup("cores.conf / env.conf（暂不可用）") {
            GNote("App 内部 conf 目录不可用（/data/data/com.linbox/files/linbox-conf）—— 请检查内部存储权限/空间后重进本页")
        }
        return
    }

    // ---- cores.conf：PRIMARY_CORES / SECONDARY_CORES 双键（保存时同步推导） ----
    GGroup("🧩 cores.conf（CPU 核心绑定，全局）") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cores,
                onValueChange = { cores = it },
                placeholder = { Text("如 0-3 / 0,4,5（空 = 全核心）", fontSize = gsp(10.sp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(gdp(6.dp)))
            GSmallButton("💾 保存", GuanliColors.greenDeep, Modifier) {
                cs.launch {
                    val total = Runtime.getRuntime().availableProcessors()
                    val primary = com.linbox.apps.containers.launcher.WineEnvironment.parseCoreSpec(cores)
                        .filter { it in 0 until total }.toSortedSet()
                    val secondary = (0 until total).filter { it !in primary }
                    val err = withContext(Dispatchers.IO) {
                        OptConfSupport.writeCores(
                            com.linbox.apps.containers.launcher.WineEnvironment.formatCoreSpec(primary.toList()),
                            com.linbox.apps.containers.launcher.WineEnvironment.formatCoreSpec(secondary)
                        )
                    }
                    withContext(Dispatchers.IO) { reload() }
                    busyMsg = err
                    android.widget.Toast.makeText(
                        context, err ?: "cores.conf 已写入（PRIMARY+SECONDARY 双键）",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        GNote("wineguanli save_advanced_settings 同款：保存时同时写 PRIMARY_CORES 与 SECONDARY_CORES 两行")
    }

    // ---- env.conf：通用 KEY=VALUE（每行一条，空值/删行 = 移除该键） ----
    GGroup("🧪 env.conf 环境变量（全局，每行 KEY=VALUE）") {
        OutlinedTextField(
            value = envText,
            onValueChange = { envText = it },
            placeholder = { Text("MESA_GL_VERSION_OVERRIDE=4.6\nDXVK_FRAME_RATE=60 …", fontSize = gsp(10.sp)) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(11.sp)),
            modifier = Modifier.fillMaxWidth().height(gdp(120.dp))
        )
        Spacer(Modifier.height(gdp(4.dp)))
        Row {
            GSmallButton("💾 保存全部", GuanliColors.greenDeep, Modifier) {
                cs.launch {
                    val err = withContext(Dispatchers.IO) {
                        // 解析编辑框 → 逐键写（replace-or-append）；
                        // 被删除的行同步从 env.conf 移除。
                        val pairs = envText.lines().mapNotNull { l ->
                            val t = l.trim()
                            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
                            val eq = t.indexOf('=')
                            if (eq <= 0) return@mapNotNull null
                            t.substring(0, eq).trim() to t.substring(eq + 1).trim()
                        }
                        var firstErr: String? = null
                        val oldKeys = OptConfSupport.envEntries().map { it.first }.toSet()
                        val newKeys = pairs.map { it.first }.toSet()
                        (oldKeys - newKeys).forEach { OptConfSupport.removeEnvKey(it) }
                        for (p in pairs) {
                            val e = OptConfSupport.writeEnvKey(p.first, p.second)
                            if (e != null) { firstErr = e; return@withContext firstErr }
                        }
                        firstErr
                    }
                    withContext(Dispatchers.IO) { reload() }
                    busyMsg = err
                    android.widget.Toast.makeText(
                        context, err ?: "env.conf 已写入（下次启动 wine 生效）",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            Spacer(Modifier.width(gdp(8.dp)))
            GSmallButton("↻ 重读", GuanliColors.accent, Modifier) {
                cs.launch { withContext(Dispatchers.IO) { reload() } }
            }
        }
        if (busyMsg != null) {
            Text(busyMsg ?: "", fontSize = gsp(11.sp), color = GuanliColors.red)
        }
        GNote("对应 wineguanli 环境变量页（MESA_*/VKD3D_*/MANGOHUD*/DXVK_HUD/TU_DEBUG 等）；容器 env= 优先级仍最高")
    }
}

// ===========================================================================
// ⚡ DY 配置（v3.3.4 重写：wineguanli.py DynarecEditor 逐面移植）
//
// 用户问题 2："好多放在高级设置里的东西你全放在了一个框框里了，不好选啊，
// 根据参考py来，比如box选项，dy配置选项等" —— 旧版把 BOX64_* 变量拍平成
// 容器 env= 的纯文本行（KEY = VALUE + 删除按钮），既没有按定义表渲染控件，
// 也没读写 /opt/conf。现按参考 DynarecEditor 逐面重制：
//  - 数据源：/opt/conf/custom.conf（变量定义表 export VAR=候选值,…）+
//    /opt/conf/dynarec_active.conf（当前生效值）—— 就是启动链
//    readOptConfs source 的那两份，改的即生效；
//  - 控件：0/1 变量 → "启用"勾选；多值变量 → 候选值下拉（不好选→好选）；
//  - 预设：🔒 兼容模式 / 🚀 性能模式 / ⚖️ 平衡模式（GuanliSupport.DY_PRESETS）；
//  - 应用：整文件重写 dynarec_active.conf（apply_config 同款），并清理
//    各容器 env= 里旧版写入的 BOX64_*/BOX86_* 覆盖（不清理则容器级
//    残留压着新配置 → 又是"怎么改都没反应"）。
// ===========================================================================

@Composable
internal fun DyConfigTab(
    // v3.3.7：DY 配置是 /opt/conf 全局项（wineguanli DynarecEditor 无容器概念），
    // sel 仅用于提示文案 —— 可空（无容器时本页照常可用）
    sel: ContainerData?,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    // 定义表（custom.conf 顺序）与当前值 —— 快照状态，改值即刷新
    var loaded by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(false) }
    var vars by remember { mutableStateOf<List<Pair<String, List<String>>>>(emptyList()) }
    var current by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var presetSel by remember { mutableStateOf("自定义") }
    var selectedVar by remember { mutableStateOf<String?>(null) }
    var newVar by remember { mutableStateOf("") }
    var newVals by remember { mutableStateOf("") }
    var busyMsg by remember { mutableStateOf<String?>(null) }

    // 装配（wineguanli load_config：custom.conf 缺失自建默认表；active 缺值/越值回落首候选）
    fun reload() {
        loaded = true
        if (OptConfSupport.root() == null) {
            available = false
            vars = emptyList()
            current = emptyMap()
            return
        }
        OptConfSupport.ensureDyCustomConf()
        val v = OptConfSupport.dyVariables()
        val active = OptConfSupport.dyActive()
        val cur = LinkedHashMap<String, String>()
        v.forEach { (name, values) ->
            cur[name] = active[name]?.takeIf { it in values } ?: values.first()
        }
        available = true
        vars = v.entries.map { it.key to it.value }
        current = cur
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { reload() }
    }

    if (!loaded) {
        Box(Modifier.fillMaxWidth().padding(gdp(24.dp)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(gdp(26.dp)), strokeWidth = gdp(2.5.dp))
        }
        return
    }
    if (!available) {
        GGroup("DY 配置（Box64/Box86 动态重编译）") {
            GNote(
                "App 内部 conf 目录不可用（/data/data/com.linbox/files/linbox-conf）——\n" +
                    "DY 配置读写其中的 custom.conf 与 dynarec_active.conf，\n" +
                    "请检查内部存储权限/空间后重进本页"
            )
        }
        return
    }

    fun setVar(name: String, value: String) {
        current = current.toMutableMap().apply { put(name, value) }
        presetSel = "自定义"
    }

    fun applyPreset(name: String) {
        GuanliSupport.DY_PRESETS[name]?.let { preset ->
            current = current.toMutableMap().apply {
                preset.forEach { (k, v) -> if (containsKey(k)) put(k, v) }
            }
        }
    }

    // ---- 预设配置（wineguanli DynarecEditor 预设组） ----
    GGroup("预设配置（全局 App 内部 conf，所有容器共用）") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("预设:", fontSize = gsp(13.sp), color = themeFg())
            Spacer(Modifier.width(gdp(8.dp)))
            GComboBox(
                options = listOf("自定义") + GuanliSupport.DY_PRESETS.keys.toList(),
                selected = presetSel,
                onSelect = { name ->
                    presetSel = name
                    applyPreset(name)   // wineguanli apply_preset：预设值整体替换
                },
                width = 168.dp
            )
            Spacer(Modifier.weight(1f))
            GSmallButton("重置默认", GuanliColors.grey, Modifier) {
                cs.launch(Dispatchers.IO) {
                    val err = OptConfSupport.resetDyCustomConf()
                    withContext(Dispatchers.Main) {
                        busyMsg = err
                        if (err == null) {
                            // wineguanli reset_default：恢复定义表 + 回到兼容模式
                            reload()
                            applyPreset("🔒 兼容模式")
                            presetSel = "🔒 兼容模式"
                            android.widget.Toast.makeText(
                                context, "已恢复默认定义表并回到兼容模式（点『应用配置』生效）",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
        GNote("预设值来自 wineguanli.py DynarecEditor（兼容/性能/平衡各 9 项 BOX64_DYNAREC_* 变量）")
    }

    // ---- 变量列表（wineguanli update_list：横向行 = 变量名 + 当前值 + 控件） ----
    GGroup("变量列表（点击行选中；0/1 变量勾选启用，多值变量下拉选择）") {
        vars.forEach { (name, values) ->
            val cur = current[name] ?: values.first()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(gdp(4.dp)))
                    .background(
                        if (selectedVar == name) GuanliColors.accent.copy(alpha = 0.14f)
                        else GuanliColors.rowAlt
                    )
                    .border(
                        gdp(0.5.dp),
                        if (selectedVar == name) GuanliColors.accent else themeSec().copy(alpha = 0.12f),
                        RoundedCornerShape(gdp(4.dp))
                    )
                    .clickable { selectedVar = if (selectedVar == name) null else name }
                    .padding(horizontal = gdp(10.dp), vertical = gdp(6.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 变量名（v3.3.8 用户问题 5：weight(1f) 自适应占满剩余宽度 ——
                // 固定 230dp 在窄窗/缩放下截断长名；右侧控件同步收窄）
                Text(
                    name, fontSize = gsp(12.sp), fontWeight = FontWeight.Bold, color = themeFg(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true)
                )
                // 当前值（wineguanli："当前: X"；v3.3.8 收窄 88→58dp，字号同步）
                Text(
                    "$cur", fontSize = gsp(10.sp), color = themeSec(),
                    maxLines = 1,
                    modifier = Modifier.width(gdp(26.dp)),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
                // 控件：{0,1} → 启用勾选；多值 → 候选下拉（v3.3.8：150→96dp）
                if (values.toSet() == setOf("0", "1")) {
                    GCheckRow("启用", cur == "1") { on ->
                        setVar(name, if (on) "1" else "0")
                    }
                } else {
                    GComboBox(
                        options = values,
                        selected = cur,
                        onSelect = { v -> setVar(name, v) },
                        width = 96.dp
                    )
                }
                if (selectedVar == name) {
                    Spacer(Modifier.width(gdp(8.dp)))
                    GSmallButton("删除变量", GuanliColors.redFab, Modifier) {
                        cs.launch(Dispatchers.IO) {
                            val err = OptConfSupport.removeDyVariable(name)
                            withContext(Dispatchers.Main) {
                                busyMsg = err
                                if (err == null) {
                                    reload()
                                    selectedVar = null
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(gdp(6.dp)))
        // 添加变量（wineguanli add_variable：变量名 + 逗号分隔候选值）
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
            OutlinedTextField(
                value = newVar,
                onValueChange = { newVar = it },
                label = { Text("变量名（大写/数字/_）", fontSize = gsp(10.sp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = newVals,
                onValueChange = { newVals = it },
                label = { Text("候选值（如 0,1,2）", fontSize = gsp(10.sp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                modifier = Modifier.weight(1f)
            )
            GSmallButton("添加变量", Color(0xFF4CAF50), Modifier) {
                val k = newVar.trim().uppercase()
                val vals = newVals.trim()
                if (Regex("^[A-Z0-9_]+$").matches(k) &&
                    vals.split(',').all { Regex("^\\d+$").matches(it.trim()) }
                ) {
                    cs.launch(Dispatchers.IO) {
                        val err = OptConfSupport.appendDyVariable(k, vals)
                        withContext(Dispatchers.Main) {
                            busyMsg = err
                            if (err == null) {
                                reload()
                                newVar = ""; newVals = ""
                            }
                        }
                    }
                } else {
                    busyMsg = "变量名仅允许大写字母/数字/下划线，候选值仅允许数字（逗号分隔）"
                }
            }
        }
        if (busyMsg != null) {
            Text(busyMsg ?: "", fontSize = gsp(11.sp), color = GuanliColors.red)
        }
    }

    // ---- 应用配置（wineguanli apply_config：整文件重写 dynarec_active.conf） ----
    GSmallButton("✅ 应用配置", GuanliColors.blueFab, Modifier.fillMaxWidth().height(gdp(44.dp))) {
        cs.launch(Dispatchers.IO) {
            val ordered = vars.mapNotNull { (k, _) -> current[k]?.let { k to it } }
            var err = OptConfSupport.writeDynarecActive(ordered)
            // 清理各容器 env= 里旧版 DY 页写入的 BOX64_*/BOX86_* 覆盖
            //（容器 env= 最后叠加、优先级最高 —— 不清理则新配置被压着不生效）
            var cleaned = 0
            if (err == null) {
                val dyKeys = vars.map { it.first }.toSet()
                ContainerManager.list().forEach { c ->
                    val has = GuanliSupport.parseEnv(c.env).any { it.first in dyKeys }
                    if (has) {
                        val e2 = ContainerManager.update(
                            c.copy(env = GuanliSupport.removeEnvKeys(c.env, dyKeys))
                        )
                        if (e2 == null) cleaned++ else err = "容器 ${c.name} 清理旧 DY 覆盖失败：$e2"
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (err == null) {
                    android.widget.Toast.makeText(
                        context,
                        "DY 配置已写入 App 内部 dynarec_active.conf（启动链 source 生效；已写透 /opt 生态）" +
                            (if (cleaned > 0) "（并清理 $cleaned 个容器的旧覆盖）" else ""),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    onSaved()
                } else {
                    busyMsg = err
                }
            }
        }
    }
    GNote(
        "写入 App 内部 linbox-conf/dynarec_active.conf（wineguanli.py 同款，启动链 source 生效，" +
            "优先级高于 Box64 预设；各容器 env= 里的旧 DY 覆盖已随应用自动清理；写透 /opt 保留" +
            (sel?.let { "；当前容器 ${it.name}" } ?: "") + "）"
    )
}

// ===========================================================================
// 🧬 FEX 配置（v3.3.40：用户需求 1 —— assets-ck 的 fex_env_vars/fex_presets
// 接入，“放在 dy 配置旁边”）
//
// 上游发行包（同源项目）assets 内置 `builtin/fex_env_vars.json`（变量定义
// 表：name/values/toggleSwitch/editText/defaultValue）+ `fex_presets.json`
//（stability/compatibility/intermediate/performance 四档）—— LinBox 的
// assets-ck 一直带着这两份资产却没有任何代码消费。本页把它补全：
//  - 定义表逐行渲染控件（0/1 变量 → 启用勾选；多值变量 → 候选下拉；
//    editText 变量（FEX_MAXINST）→ 编辑框）—— 上游发行包
//    FEXPresetEditDialogFragment 同款交互，wineguanli DY 配置同款布局；
//  - 四档预设一键装载（Assets 内置，不可删改）+ 「自定义」态；
//  - 应用 = 整文件重写 App 内部 fex_active.conf（启动链
//    applyOptConfEnv source 全部 *.conf 时自动 export —— 与 DY 配置
//    完全同一条生效链路；写透同步 /opt 生态由 ApkConfStore.syncOut）；
//  - 优先级：FEX 配置页（conf，全局）> 容器预设档（applyFexPresetEnv，
//    putIfAbsent 在链路前段）> 变量默认值；容器 env= 仍最高（用户显式
//    FEX_*= 不被 conf 覆盖）；
//  - 双通道：[WineEnvironment.ensureFexConfigFile] 会把本页值同步映射进
//    $HOME/.fex-emu/Config.json（libwow64fex 的实证通道）。
//  - arm64ec 后端（FEX 转译 x86_64/i386 PE）是这些变量的消费者；box64
//    后端下 FEX_* 变量被忽略（写上也无害）。
// ===========================================================================

@Composable
internal fun FexConfigTab(onSaved: () -> Unit) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    // 定义表（assets 只读）+ 当前值快照 —— 改值即刷新
    var loaded by remember { mutableStateOf(false) }
    var defs by remember { mutableStateOf<List<com.linbox.apps.containers.launcher.FexConfSupport.FexVarDef>>(emptyList()) }
    var presets by remember { mutableStateOf<List<Pair<String, List<Pair<String, String>>>>>(emptyList()) }
    var current by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var presetSel by remember { mutableStateOf("自定义") }
    var busyMsg by remember { mutableStateOf<String?>(null) }

    // 装配：active 值 → 与四档预设比对（全等 = 选中该档；缺文件 = 预装
    // 兼容档做初始展示，不落盘 —— 点「应用配置」才写）
    fun reload() {
        loaded = true
        defs = com.linbox.apps.containers.launcher.FexConfSupport.loadVarDefs(context)
        presets = com.linbox.apps.containers.launcher.FexConfSupport.loadPresets(context)
            .entries.map { it.key to it.value }
        val active = com.linbox.apps.containers.launcher.FexConfSupport.activeValues()
        val matched = presets.firstOrNull { (_, items) ->
            defs.all { d ->
                val want = items.firstOrNull { it.first == d.name }?.second ?: d.defaultValue
                active[d.name] == want || (active[d.name] == null && want == d.defaultValue)
            }
        }?.first
        presetSel = matched?.let { com.linbox.apps.containers.launcher.FexConfSupport.PRESET_LABELS[it] ?: it } ?: "自定义"
        current = defs.associate { d ->
            d.name to (active[d.name]
                ?: presets.firstOrNull { it.first == matched }?.second
                    ?.firstOrNull { it.first == d.name }?.second
                ?: d.defaultValue)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { reload() }
    }

    if (!loaded) {
        Box(Modifier.fillMaxWidth().padding(gdp(24.dp)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(gdp(26.dp)), strokeWidth = gdp(2.5.dp))
        }
        return
    }

    fun setVar(name: String, value: String) {
        current = current.toMutableMap().apply { put(name, value) }
        presetSel = "自定义"
    }

    fun applyPreset(id: String) {
        presets.firstOrNull { it.first == id }?.second?.let { items ->
            current = current.toMutableMap().apply {
                items.forEach { (k, v) -> if (containsKey(k)) put(k, v) }
            }
        }
    }

    // ---- 预设（assets 四档；wineguanli DY 页同款交互） ----
    GGroup("FEX 预设（assets 内置四档，全局所有容器共用）") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("预设:", fontSize = gsp(13.sp), color = themeFg())
            Spacer(Modifier.width(gdp(8.dp)))
            GComboBox(
                options = listOf("自定义") + presets.map { (id, _) ->
                    com.linbox.apps.containers.launcher.FexConfSupport.PRESET_LABELS[id] ?: id
                },
                selected = presetSel,
                onSelect = { label ->
                    presetSel = label
                    val id = presets.firstOrNull { (pid, _) ->
                        (com.linbox.apps.containers.launcher.FexConfSupport.PRESET_LABELS[pid] ?: pid) == label
                    }?.first
                    if (id != null) applyPreset(id)
                },
                width = 168.dp
            )
            Spacer(Modifier.weight(1f))
            GSmallButton("重置默认", GuanliColors.grey, Modifier) {
                cs.launch(Dispatchers.IO) {
                    // 恢复 = 删除 fex_active.conf（启动链回落容器预设档/默认值）
                    val err = com.linbox.apps.containers.launcher.OptConfSupport.removeFexActive()
                    withContext(Dispatchers.Main) {
                        busyMsg = err
                        if (err == null) {
                            reload()
                            android.widget.Toast.makeText(
                                context, "已恢复默认（fex_active.conf 已删除；点『应用配置』前预设档生效）",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
        GNote("预设值来自 assets/builtin/fex_presets.json（内置四档）；匹配当前生效值自动选中对应档")
    }

    // ---- 变量列表（fex_env_vars.json 定义表全量） ----
    GGroup("FEX 变量（15 项官方参数；0/1 变量勾选启用，多值变量下拉，数字变量编辑）") {
        defs.forEach { d ->
            val cur = current[d.name] ?: d.defaultValue
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(gdp(4.dp)))
                    .background(GuanliColors.rowAlt)
                    .border(gdp(0.5.dp), themeSec().copy(alpha = 0.12f), RoundedCornerShape(gdp(4.dp)))
                    .padding(horizontal = gdp(10.dp), vertical = gdp(6.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    d.name, fontSize = gsp(12.sp), fontWeight = FontWeight.Bold, color = themeFg(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true)
                )
                if (d.values.toSet() == setOf("0", "1")) {
                    GCheckRow("启用", cur == "1") { on ->
                        setVar(d.name, if (on) "1" else "0")
                    }
                } else if (d.values.size > 1) {
                    GComboBox(
                        options = d.values,
                        selected = cur,
                        onSelect = { v -> setVar(d.name, v) },
                        width = 96.dp
                    )
                } else {
                    OutlinedTextField(
                        value = cur,
                        onValueChange = { v -> setVar(d.name, v.filter { it.isDigit() }.take(6)) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(11.sp)),
                        modifier = Modifier.width(gdp(84.dp)).height(gdp(40.dp))
                    )
                }
            }
        }
        GNote(
            "定义表 = assets/builtin/fex_env_vars.json（FEX_TSOENABLED / FEX_MULTIBLOCK / FEX_MAXINST / " +
                "FEX_HOSTFEATURES / FEX_SMC_CHECKS 等 15 项）"
        )
    }

    // ---- 应用配置（整文件重写 fex_active.conf） ----
    GSmallButton("✅ 应用配置", GuanliColors.blueFab, Modifier.fillMaxWidth().height(gdp(44.dp))) {
        cs.launch(Dispatchers.IO) {
            val ordered = defs.mapNotNull { d -> current[d.name]?.let { d.name to it } }
            val err = com.linbox.apps.containers.launcher.FexConfSupport.writeActive(ordered)
            withContext(Dispatchers.Main) {
                if (err == null) {
                    android.widget.Toast.makeText(
                        context,
                        "FEX 配置已写入 App 内部 fex_active.conf（下次启动 wine 生效；含 .fex-emu/Config.json 双通道）",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    onSaved()
                } else {
                    busyMsg = err
                }
            }
        }
    }
    if (busyMsg != null) {
        Text(busyMsg ?: "", fontSize = gsp(11.sp), color = GuanliColors.red)
    }
    GNote(
        "写入 App 内部 linbox-conf/fex_active.conf（启动链 source 生效，优先级高于容器预设档、低于容器 env=；" +
            "arm64ec 后端消费 —— box64 后端忽略 FEX_* 无害）"
    )
}

// ===========================================================================
// 📦 Box 配置（wineguanli.py create_box_tab：版本下拉 + 当前信息 + 应用）
// ===========================================================================

@Composable
internal fun BoxConfigTab() {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    var box64Tars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var box64Sel by remember { mutableStateOf("") }
    var box64Info by remember { mutableStateOf("Box64: 检测中…") }

    fun refreshInfo() {
        cs.launch(Dispatchers.IO) {
            val b64 = GuanliSupport.boxVersion("box64")
            withContext(Dispatchers.Main) { box64Info = b64 }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            box64Tars = GuanliSupport.scanOpt("box")
        }
        refreshInfo()
    }

    GGroup("Box64 版本") {
        GComboBox(
            options = listOf("默认") + box64Tars.map { it.display },
            selected = box64Sel.ifEmpty { "默认" },
            onSelect = { box64Sel = if (it == "默认") "" else it }
        )
        if (box64Tars.isEmpty()) GNote("rootfs/opt/box 内未发现 tar 包")
        GNote("v3.3.16：选 v0.4.2 及以上版本可打全量补丁（内建 shm + Android RELR）；v0.4.0/v0.4.1 结构不兼容，CI 会自动跳过补丁")
    }
    GGroup("当前Box信息") {
        Text(box64Info, fontSize = gsp(12.sp), color = themeFg())
        Spacer(Modifier.height(gdp(4.dp)))
        GSmallButton("🔄 刷新信息", GuanliColors.grey, Modifier) { refreshInfo() }
    }
    GSmallButton("✅ 应用Box配置", GuanliColors.greenDeep, Modifier.fillMaxWidth()) {
        cs.launch(Dispatchers.IO) {
            val errs = mutableListOf<String>()
            // v3.3.9（用户问题 1）：box 落点 = rootfs/usr/local/bin（旧版解到
            // /usr/local 是错的）。
            // v3.3.16：box86 选项移除（aarch64 设备用不到）；解压加
            // --unlink-first（运行中的 box64 不阻塞覆盖，Text file busy 根治）
            val boxBin = File("${ContainerManager.ROOTFS_ROOT}/usr/local/bin")
            if (box64Sel.isNotBlank()) {
                runCatching { boxBin.mkdirs() }
                GuanliSupport.scanOpt("box").firstOrNull { it.display == box64Sel }?.let { tar ->
                    GuanliSupport.extractTarTo(tar.path, boxBin)?.let { errs.add(it) }
                }
            }
            withContext(Dispatchers.Main) {
                if (errs.isEmpty()) {
                    android.widget.Toast.makeText(
                        context, "✅ Box配置已应用（解压到 rootfs/usr/local/bin）",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    refreshInfo()
                } else {
                    android.widget.Toast.makeText(
                        context, errs.joinToString("\n"), android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}

// ===========================================================================
// 🍷 Wine 安装（wineguanli.py create_wine_install_tab：本地/URL + 版本管理）
// ===========================================================================

@Composable
internal fun WineInstallTab(onRefresh: () -> Unit) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    var arch by remember { mutableStateOf("x86_64") }
    var fromUrl by remember { mutableStateOf(false) }
    var filePath by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var slotName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wines by remember { mutableStateOf<List<WineInstall>>(emptyList()) }
    var winesTick by remember { mutableIntStateOf(0) }
    var selSlot by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    fun reload() {
        cs.launch(Dispatchers.IO) {
            val w = ContainerManager.listWines()
            withContext(Dispatchers.Main) { wines = w }
        }
    }
    LaunchedEffect(winesTick) { reload() }

    fun doInstall(srcPath: String) {
        busy = true
        cs.launch(Dispatchers.IO) {
            val err = GuanliSupport.WineOps.installFromTar(srcPath, slotName, arch)
            withContext(Dispatchers.Main) {
                busy = false
                android.widget.Toast.makeText(
                    context, err ?: "✅ Wine 安装完成（/usr/opt）", android.widget.Toast.LENGTH_LONG
                ).show()
                if (err == null) { slotName = ""; filePath = ""; url = ""; winesTick++ }
            }
        }
    }

    GGroup("选择安装架构") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
            // v3.2.22：紧凑定宽下拉（选项仅两项，fillMaxWidth 会把同行提示挤没）
            GComboBox(options = listOf("x86_64", "arm64ec"), selected = arch, onSelect = { arch = it }, width = 150.dp)
            Text("(arm64ec 装到 wine-<名> 槽位)", fontSize = gsp(10.sp), color = GuanliColors.info)
        }
    }
    GGroup("选择安装方式") {
        GRadioRow("📁 从本地文件安装", !fromUrl) { fromUrl = false }
        GRadioRow("🌐 从网络链接安装", fromUrl) { fromUrl = true }
    }
    if (!fromUrl) {
        GGroup("选择Wine安装文件") {
            DisposableEffect(Unit) {
                val unregister = com.linbox.apps.filemanager.FilePickBus.listen(
                    targetApp = "containers_wineinstall",
                    targetWindow = ""
                ) { path ->
                    filePath = path
                    picking = false
                }
                onDispose { unregister() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = filePath,
                    onValueChange = { filePath = it },
                    placeholder = { Text("选择或输入文件路径…", fontSize = gsp(10.sp)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(gdp(8.dp)))
                GSmallButton("📂 浏览…", GuanliColors.blueFab, Modifier) {
                    picking = true
                    com.linbox.core.shell.ShellController.showFiles(
                        pickMode = "text",
                        targetApp = "containers_wineinstall",
                        targetWindow = ""
                    )
                }
            }
            GNote("支持: .tar .tar.gz .tar.xz（wine 目录树，bin/wine 在顶层）")
        }
    } else {
        GGroup("输入下载链接") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("https://example.com/wine.tar.gz", fontSize = gsp(10.sp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    GGroup("槽位名（可选）") {
        OutlinedTextField(
            value = slotName,
            onValueChange = { slotName = it },
            label = { Text("如 wine-9.20（默认取包名）", fontSize = gsp(10.sp)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
            modifier = Modifier.fillMaxWidth()
        )
    }
    GSmallButton(
        if (busy) "⏳ 安装中…" else "🚀 开始安装Wine",
        GuanliColors.accent, Modifier.fillMaxWidth().height(gdp(44.dp)),
        enabled = !busy
    ) {
        if (!fromUrl && filePath.isNotBlank()) {
            doInstall(filePath)
        } else if (fromUrl && url.isNotBlank()) {
            busy = true
            cs.launch(Dispatchers.IO) {
                try {
                    val f = GuanliSupport.WineOps.download(url) { }
                    withContext(Dispatchers.Main) {
                        doInstall(f.absolutePath)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        busy = false
                        android.widget.Toast.makeText(
                            context, "下载失败：${e.message}", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } else {
            android.widget.Toast.makeText(
                context, if (fromUrl) "请输入下载链接" else "请选择文件", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    GGroup("🍷 已安装的Wine版本（/usr/opt）") {
        if (wines.isEmpty()) {
            Text("（未发现 wine 槽位）", fontSize = gsp(12.sp), color = GuanliColors.info)
        }
        wines.forEach { w ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(gdp(4.dp)))
                    .background(if (selSlot == w.name) GuanliColors.listSel else GuanliColors.rowAlt)
                    .clickable { selSlot = w.name }
                    .padding(horizontal = gdp(10.dp), vertical = gdp(7.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(w.displayLabel, fontSize = gsp(12.sp), color = themeFg(), modifier = Modifier.weight(1f))
                if (ContainerManager.defaultWineName() == w.name) {
                    Text("默认", fontSize = gsp(10.sp), color = GuanliColors.accent)
                }
            }
        }
        Spacer(Modifier.height(gdp(6.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
            GSmallButton("🔄 刷新列表", GuanliColors.grey, Modifier.weight(1f)) { reload() }
            GSmallButton(
                "⭐ 设为默认", GuanliColors.accent, Modifier.weight(1f),
                enabled = selSlot.isNotBlank()
            ) {
                cs.launch(Dispatchers.IO) {
                    val err = GuanliSupport.WineOps.setDefaultWine(selSlot)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context, err ?: "默认 wine 槽位 = $selSlot", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        reload()
                    }
                }
            }
            GSmallButton(
                "🗑️ 删除选中版本", GuanliColors.red, Modifier.weight(1f),
                enabled = selSlot.isNotBlank()
            ) {
                cs.launch(Dispatchers.IO) {
                    val err = GuanliSupport.WineOps.deleteWine(selSlot)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context, err ?: "已删除 $selSlot", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        selSlot = ""
                        reload()
                        onRefresh()
                    }
                }
            }
        }
    }
}

// ===========================================================================
// 📊 MANGOHUD 配置（wineguanli.py create_mangohud_tab）
// ===========================================================================

@Composable
internal fun MangoHudTab(
    // v3.3.7：容器选择器收进本选项卡（wineguanli create_mangohud_tab 本就
    // 只依赖全局 MANGOHUD_CONFIG 语义；LinBox 的配置按容器存储，故在此
    // 用紧凑定宽下拉选择 —— 不再占据整页页头）
    containers: List<ContainerData>,
    sel: ContainerData?,
    onMango: (ContainerData) -> Unit,
    onSetMango: (ContainerData, String) -> Unit
) {
    GNote(
        "MANGOHUD配置需要在环境变量中设置 MANGOHUD_CONFIG 变量\n" +
            "（LinBox：写入容器 mangohud=，启动时注入 MANGOHUD=1 + MANGOHUD_CONFIG）"
    )
    if (containers.isEmpty()) {
        GGroup("MANGOHUD配置") {
            GNote("尚无容器 —— 先在『容器管理』页创建容器后，回到此处配置 MANGOHUD")
        }
        return
    }
    // 紧凑选择器：跟随主页选中项，也可在本页内切换
    var mangoSelName by remember { mutableStateOf(sel?.name ?: containers.first().name) }
    val mangoSel = containers.firstOrNull { it.name == mangoSelName } ?: containers.first()
    GGroup("MANGOHUD配置（容器：${mangoSel.name}）") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("生效容器:", fontSize = gsp(12.sp), color = themeFg())
            Spacer(Modifier.width(gdp(8.dp)))
            GComboBox(
                options = containers.map { it.name },
                selected = mangoSelName,
                onSelect = { mangoSelName = it },
                width = 200.dp
            )
        }
        Spacer(Modifier.height(gdp(6.dp)))
        GSmallButton("⚙️ 配置MANGOHUD", GuanliColors.blueFab, Modifier.fillMaxWidth()) {
            onMango(mangoSel)
        }
        Spacer(Modifier.height(gdp(6.dp)))
        Text(
            "当前配置: ${mangoSel.mangohud.ifEmpty { "未设置" }}",
            fontSize = gsp(13.sp), color = themeFg(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(gdp(5.dp)))
                .background(GuanliColors.rowAlt)
                .border(gdp(0.5.dp), themeSec().copy(alpha = 0.2f), RoundedCornerShape(gdp(5.dp)))
                .padding(gdp(10.dp))
        )
        if (mangoSel.mangohud.isNotBlank()) {
            Spacer(Modifier.height(gdp(6.dp)))
            GSmallButton("关闭 MANGOHUD", GuanliColors.grey, Modifier) {
                onSetMango(mangoSel, "")
            }
        }
    }
}

// ===========================================================================
// 更多设置对话框（wineguanli.py AdvancedSettingsDialog：650×950，5 选项卡
// + LinBox 原生扩展页 —— 不丢任何 LinBox 既有能力）
// ===========================================================================

@Composable
internal fun MoreSettingsDialog(
    container: ContainerData,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    // v3.3.4：环境变量页 MANGOHUD 组的"配置MANGOHUD"入口（打开详细配置对话框）
    onOpenMango: () -> Unit = {}
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var edit by remember(container.name) { mutableStateOf(container) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    // v3.3.4（用户问题 1："更多设置根本没有读取和更改配置文件，怎么改都没
    // 反应"）：/opt/conf 生态配置快照 —— wineguanli.py
    // AdvancedSettingsDialog.load_current_settings 同款读取（env.conf +
    // cores.conf）。环境变量页显示值 = 容器 env= 覆盖 > env.conf 当前值；
    // 保存时按参考 save_advanced_settings 语义写回 env.conf（空值删行）+
    // cores.conf 双键 + 容器 env= 同步清理（旧容器级残留覆盖不同步清理的
    // 话，改动永远被 container.conf 压着 → "没反应"）。
    var envConfWork by remember(container.name) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var optCores by remember(container.name) { mutableStateOf("") }
    var hasOptEco by remember(container.name) { mutableStateOf(false) }
    LaunchedEffect(container.name) {
        withContext(Dispatchers.IO) {
            hasOptEco = OptConfSupport.root() != null
            envConfWork = OptConfSupport.envEntries().toMap()
            optCores = OptConfSupport.primaryCores() ?: ""
        }
    }
    // 多选对话框请求（MESA扩展/DXVK HUD/TU_DEBUG）
    var multiSelect by remember { mutableStateOf<MultiSelectRequest?>(null) }
    multiSelect?.let { req ->
        MultiSelectDialog(
            title = req.title,
            options = req.options,
            initial = req.selected,
            onDismiss = { multiSelect = null },
            onOk = {
                req.onOk(it)
                multiSelect = null
            }
        )
    }

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "更多设置 · ${container.name}",
        onDismiss = onDismiss,
        // v3.3.4：620 → 680（字号补偿后三列下拉不换行）
        width = 680.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(Modifier.fillMaxWidth()) {
            ScrollableTabRow(
                selectedTabIndex = tab,
                edgePadding = gdp(0.dp),
                containerColor = themeCard(),
                contentColor = themeFg()
            ) {
                listOf(
                    "CPU核心", "函数库", "环境变量", "高级功能", "Direct3D", "🧩 LinBox 原生"
                ).forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, fontSize = gsp(11.sp), maxLines = 1) }
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp)),
                verticalArrangement = Arrangement.spacedBy(gdp(10.dp))
            ) {
                when (tab) {
                    0 -> {
                        // wineguanli.py create_cpu_tab（与容器 CPU 页同构）
                        GGroup("CPU核心分配（勾选为主核心）") {
                            val total = Runtime.getRuntime().availableProcessors()
                            // v3.3.4（用户问题 1）：容器 cores= 为空时回落显示
                            // /opt/conf/cores.conf 的 PRIMARY_CORES（参考
                            // load_current_settings 直接读 cores.conf 的语义）
                            val spec = edit.cores.ifBlank { optCores }
                            val primary = WineEnvironment.parseCoreSpec(spec)
                                .filter { it in 0 until total }.toSortedSet()
                            GCheckFlow(
                                items = (0 until total).map { it.toString() to "核心$it" },
                                checked = { it.toIntOrNull() in primary },
                                onToggle = { name, on ->
                                    val idx = name.toIntOrNull() ?: return@GCheckFlow
                                    val s = primary.toMutableSet()
                                    if (on) s.add(idx) else s.remove(idx)
                                    edit = edit.copy(cores = WineEnvironment.formatCoreSpec(s.sorted()))
                                }
                            )
                            Text(
                                "主核心: ${WineEnvironment.formatCoreSpec(primary.toList()).ifEmpty { "无" }}",
                                fontSize = gsp(12.sp), color = themeFg(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(gdp(5.dp)))
                                    .background(GuanliColors.rowAlt)
                                    .padding(gdp(8.dp))
                            )
                            if (edit.cores.isBlank() && optCores.isNotBlank()) {
                                GNote("容器未单独配置 —— 当前显示 /opt/conf/cores.conf 的 PRIMARY_CORES=\"$optCores\"")
                            }
                            // v3.3.4：保存时同步写 /opt/conf/cores.conf（参考
                            // save_advanced_settings 的 PRIMARY/SECONDARY 双键）
                            GNote("保存时同步写入 /opt/conf/cores.conf（PRIMARY_CORES/SECONDARY_CORES，/opt 生态脚本与启动链共用）")
                        }
                    }
                    1 -> {
                        // wineguanli.py create_library_tab（对话框版，全局语义 → 容器）
                        GGroup("函数库设置") {
                            // v3.3.8（用户问题 4）：DLL 加载序恢复显示（dsound/quartz
                            // 三档；「跟随安装包」不写注册表，显式档落地 DllOverrides）
                            GField("DirectSound (dsound) 加载序") {
                                GComboBox(
                                    options = listOf("跟随安装包", "原生优先 (builtin,native)", "库优先 (native,builtin)"),
                                    selected = when (edit.dsound) {
                                        "builtin,native" -> "原生优先 (builtin,native)"
                                        "native,builtin" -> "库优先 (native,builtin)"
                                        else -> "跟随安装包"
                                    },
                                    onSelect = { v ->
                                        edit = edit.copy(dsound = when (v) {
                                            "原生优先 (builtin,native)" -> "builtin,native"
                                            "库优先 (native,builtin)" -> "native,builtin"
                                            else -> "default"
                                        })
                                    }
                                )
                            }
                            GField("DirectShow (quartz) 加载序") {
                                GComboBox(
                                    options = listOf("跟随安装包", "原生优先 (builtin,native)", "库优先 (native,builtin)"),
                                    selected = when (edit.dshow) {
                                        "builtin,native" -> "原生优先 (builtin,native)"
                                        "native,builtin" -> "库优先 (native,builtin)"
                                        else -> "跟随安装包"
                                    },
                                    onSelect = { v ->
                                        edit = edit.copy(dshow = when (v) {
                                            "原生优先 (builtin,native)" -> "builtin,native"
                                            "库优先 (native,builtin)" -> "native,builtin"
                                            else -> "default"
                                        })
                                    }
                                )
                            }
                            GField("DPI (LogPixels)") {
                                val dpi = edit.dpi.toIntOrNull() ?: 96
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "$dpi (${dpi * 100 / 96}%)",
                                        fontSize = gsp(13.sp), fontWeight = FontWeight.Bold,
                                        color = GuanliColors.blueFab
                                    )
                                    Spacer(Modifier.weight(1f))
                                }
                                Slider(
                                    value = dpi.toFloat(),
                                    onValueChange = {
                                        edit = edit.copy(dpi = it.toInt().coerceIn(60, 240).toString())
                                    },
                                    valueRange = 60f..240f,
                                    steps = (240 - 60) / 20 - 1
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
                                    listOf(72, 96, 120, 144, 192).forEach { v ->
                                        GSmallButton("$v", Color(0xFF243049), Modifier, fg = themeFg()) {
                                            edit = edit.copy(dpi = v.toString())
                                        }
                                    }
                                }
                            }
                            GField("Windows 版本") {
                                GComboBox(
                                    options = WindowsVersions.ALL.map { it.label },
                                    selected = WindowsVersions.fromId(edit.winver).label,
                                    onSelect = { label ->
                                        WindowsVersions.ALL.firstOrNull { it.label == label }
                                            ?.let { edit = edit.copy(winver = it.id) }
                                    }
                                )
                            }
                            GSmallButton("💾 立即应用设置", GuanliColors.greenDeep, Modifier.fillMaxWidth()) {
                                cs.launch(Dispatchers.IO) {
                                    ContainerManager.update(edit)
                                    val err = com.linbox.apps.containers.launcher.WineLibrary.applyNow(edit)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(
                                            context, err ?: "函数库设置已应用到注册表",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                    2 -> EnvVarsTab(
                        edit = edit,
                        envConf = envConfWork,
                        hasOptEco = hasOptEco,
                        onEnvConfChange = { k, v ->
                            envConfWork = envConfWork.toMutableMap().apply {
                                if (v == null) remove(k) else put(k, v)
                            }
                        },
                        onChange = { edit = it },
                        openMulti = { multiSelect = it },
                        onOpenMango = onOpenMango
                    )
                    3 -> AdvancedFuncTab(container = edit)
                    4 -> D3dTab(container = edit)
                    5 -> NativeTab(edit = edit, onChange = { edit = it }, onSaved = onSaved)
                }
                Spacer(Modifier.height(gdp(8.dp)))
            }
            if (saveMsg != null) {
                Text(
                    saveMsg ?: "", fontSize = gsp(11.sp), color = GuanliColors.red,
                    modifier = Modifier.padding(horizontal = gdp(14.dp))
                )
            }
            // wineguanli.py：保存设置 / 取消
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(gdp(8.dp)))
                Button(
                    onClick = {
                        cs.launch(Dispatchers.IO) {
                            // v3.3.4（用户问题 1）：三路落盘 ——
                            // ① 容器副本 container.conf（cores/dpi/winver 等），
                            //    env= 里环境变量页管理的键（含旧版误写的
                            //    MESA_GL_VERSION/MESA_GLSL_VERSION）一并清理 ——
                            //    容器 env= 优先级最高，不清理则 /opt 改动永远被压着；
                            // ② /opt/conf/env.conf（wineguanli.py
                            //    save_advanced_settings 同款：非空 replace-or-append，
                            //    选"（默认）"= 删行）；
                            // ③ /opt/conf/cores.conf 双键（容器 cores 非空时）。
                            // 无 /opt 生态时 ② 回落为合并进容器 env=（启动链
                            // applyExtraEnv 注入），设置照样生效。
                            val cleanedEnv = GuanliSupport.removeEnvKeys(
                                edit.env,
                                GuanliSupport.ENV_TAB_KEYS + GuanliSupport.ENV_TAB_LEGACY_KEYS
                            )
                            var err = ContainerManager.update(edit.copy(env = cleanedEnv))
                            if (err == null) {
                                if (hasOptEco) {
                                    for (k in GuanliSupport.ENV_TAB_KEYS) {
                                        val v = envConfWork[k]
                                        val e = if (v.isNullOrBlank()) OptConfSupport.removeEnvKey(k)
                                                else OptConfSupport.writeEnvKey(k, v)
                                        if (e != null) { err = "env.conf 写入失败：$e"; break }
                                    }
                                    if (err == null && edit.cores.isNotBlank()) {
                                        val total = Runtime.getRuntime().availableProcessors()
                                        val primary = WineEnvironment.parseCoreSpec(edit.cores)
                                            .filter { it in 0 until total }.toSortedSet()
                                        val secondary = (0 until total).filter { it !in primary }
                                        OptConfSupport.writeCores(
                                            WineEnvironment.formatCoreSpec(primary.toList()),
                                            WineEnvironment.formatCoreSpec(secondary)
                                        )?.let { err = "cores.conf 写入失败：$it" }
                                    }
                                } else {
                                    var env = cleanedEnv
                                    for (k in GuanliSupport.ENV_TAB_KEYS) {
                                        val v = envConfWork[k]
                                        env = if (v.isNullOrBlank()) GuanliSupport.removeEnvKeys(env, listOf(k))
                                              else GuanliSupport.mergeEnv(env, mapOf(k to v))
                                    }
                                    err = ContainerManager.update(edit.copy(env = env))
                                }
                            }
                            withContext(Dispatchers.Main) {
                                if (err == null) {
                                    android.widget.Toast.makeText(
                                        context,
                                        if (hasOptEco) "设置已保存（container.conf + App 内部 conf，已同步 /opt 生态）"
                                        else "设置已保存（container.conf + App 内部 conf）",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    onSaved()
                                } else {
                                    saveMsg = err
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("保存设置") }
            }
        }
            }
    }
}

// ===========================================================================
// 环境变量选项卡（wineguanli.py create_env_tab：MESA/MANGOHUD/DXVK/TU_DEBUG）
//
// v3.3.4（用户问题 1："更多设置根本没有读取和更改配置文件，怎么改都没反应"）：
//  - 读取：显示值 = 容器 env= 覆盖 > /opt/conf/env.conf 当前值（参考
//    load_current_settings 直接读 env.conf 的语义 + 容器级覆盖可见）；
//  - 写回：保存时按 save_advanced_settings 语义写 /opt/conf/env.conf
//    （非空 replace-or-append，选"（默认）"= 删行）；无 /opt 生态时由
//    保存逻辑回落合并进容器 env=（启动链 applyExtraEnv 注入）；
//  - 键名修正：MESA 两个键改为 Mesa 官方的 MESA_GL_VERSION_OVERRIDE /
//    MESA_GLSL_VERSION_OVERRIDE（旧版误用 MESA_GL_VERSION/MESA_GLSL_VERSION，
//    Mesa 根本不读这两个变量 —— "怎么改都没反应"的直接根因）。
// ===========================================================================

@Composable
private fun EnvVarsTab(
    edit: ContainerData,
    envConf: Map<String, String>,
    hasOptEco: Boolean,
    onEnvConfChange: (String, String?) -> Unit,
    onChange: (ContainerData) -> Unit,
    openMulti: (MultiSelectRequest) -> Unit,
    onOpenMango: () -> Unit
) {
    // 生效值：容器 env= 优先，其次 env.conf —— 两个配置源都真实读取
    fun effective(key: String): String? =
        GuanliSupport.envValue(edit.env, key) ?: envConf[key]

    // 修改一个键：写 env.conf 工作副本 + 从容器 env= 移除同名键
    //（容器 env= 优先级最高 —— 不同步移除的话改动永远不生效）
    fun setVar(key: String, value: String?) {
        onEnvConfChange(key, value)
        onChange(edit.copy(env = GuanliSupport.removeEnvKeys(edit.env, listOf(key))))
    }

    // ---- MESA配置（wineguanli：MESA版本/GLSL版本/VKD3D特性 三下拉） ----
    GGroup("MESA配置") {
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(10.dp))) {
            Column(Modifier.weight(1f)) {
                Text("MESA版本:", fontSize = gsp(11.sp), color = themeFg())
                GComboBox(
                    options = listOf("（默认）", "2.1", "3.1", "3.3", "3.3COMPAT", "4.6", "4.6COMPAT"),
                    selected = effective("MESA_GL_VERSION_OVERRIDE") ?: "（默认）",
                    onSelect = { v -> setVar("MESA_GL_VERSION_OVERRIDE", if (v == "（默认）") null else v) }
                )
            }
            Column(Modifier.weight(1f)) {
                Text("GLSL版本:", fontSize = gsp(11.sp), color = themeFg())
                GComboBox(
                    options = listOf("（默认）", "210", "310", "330", "430", "460"),
                    selected = effective("MESA_GLSL_VERSION_OVERRIDE") ?: "（默认）",
                    onSelect = { v -> setVar("MESA_GLSL_VERSION_OVERRIDE", if (v == "（默认）") null else v) }
                )
            }
            Column(Modifier.weight(1f)) {
                Text("VKD3D特性:", fontSize = gsp(11.sp), color = themeFg())
                GComboBox(
                    options = listOf("（默认）", "12_1", "12_2"),
                    selected = effective("VKD3D_FEATURE_LEVEL") ?: "（默认）",
                    onSelect = { v -> setVar("VKD3D_FEATURE_LEVEL", if (v == "（默认）") null else v) }
                )
            }
        }
        if (!hasOptEco) {
            GNote("未检测到 /opt 生态 —— 保存时这些值将写入容器 env=（启动链注入生效）")
        }
    }
    // ---- MESA扩展配置（wineguanli select_mesa_extensions 三选项多选） ----
    GGroup("MESA扩展配置") {
        val cur = effective("MESA_EXTENSION_OVERRIDE")
            ?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
        GSmallButton("点击选择MESA扩展", GuanliColors.blueFab, Modifier.fillMaxWidth()) {
            openMulti(
                MultiSelectRequest(
                    title = "MESA扩展配置",
                    options = listOf(
                        "-GL_EXT_texture_sRGB_decode",
                        "GL_EXT_polygon_offset_clamp",
                        "-GL_EXT_texture_compression_s3tc"
                    ),
                    selected = cur,
                    onOk = { sel -> setVar("MESA_EXTENSION_OVERRIDE", sel.joinToString(" ").ifEmpty { null }) }
                )
            )
        }
        GNote(cur.joinToString(" ").ifEmpty { "未选择" })
    }
    // ---- MANGOHUD（wineguanli：启用勾选 + MANGOHUD/MANGOHUD_CONFIG 双变量） ----
    GGroup("MANGOHUD配置") {
        val enabled = edit.mangohud.isNotBlank() || effective("MANGOHUD") == "1"
        GCheckRow("启用MANGOHUD", enabled) { on ->
            if (on) {
                onEnvConfChange("MANGOHUD", "1")
                // 无任何现成配置时给参考默认四项（wineguanli 同款）
                if (effective("MANGOHUD_CONFIG").isNullOrBlank()) {
                    onEnvConfChange("MANGOHUD_CONFIG", "fps,frametime,cpu_stats,gpu_stats")
                }
            } else {
                onEnvConfChange("MANGOHUD", "0")
                // 容器级 mangohud 字段优先级更高（applyTuningEnv 后置叠加），
                // 不同步清空会出现"关不掉"—— 一并清理
                onChange(edit.copy(mangohud = ""))
            }
        }
        val curCfg = edit.mangohud.ifBlank { envConf["MANGOHUD_CONFIG"].orEmpty() }
        GNote("当前配置: ${curCfg.ifEmpty { "未配置" }}")
        GSmallButton("📊 配置MANGOHUD（详细）", GuanliColors.blueFab, Modifier.fillMaxWidth()) { onOpenMango() }
    }
    // ---- DXVK HUD（wineguanli select_dxvk_items） ----
    GGroup("DXVK HUD配置") {
        val cur = effective("DXVK_HUD")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        GSmallButton("选择DXVK监控项", GuanliColors.blueFab, Modifier.fillMaxWidth()) {
            openMulti(
                MultiSelectRequest(
                    title = "DXVK监控项",
                    options = listOf("devinfo", "fps", "gpuload", "api", "version"),
                    selected = cur,
                    onOk = { sel -> setVar("DXVK_HUD", sel.joinToString(",").ifEmpty { null }) }
                )
            )
        }
        GNote(cur.joinToString(",").ifEmpty { "未选择" })
    }
    // ---- TU_DEBUG（wineguanli select_tu_debug） ----
    GGroup("TU_DEBUG配置") {
        val cur = effective("TU_DEBUG")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        GSmallButton("选择TU_DEBUG选项", GuanliColors.blueFab, Modifier.fillMaxWidth()) {
            openMulti(
                MultiSelectRequest(
                    title = "TU_DEBUG选项",
                    options = listOf("nofsdt", "gmem", "sysmem", "noconform"),
                    selected = cur,
                    onOk = { sel -> setVar("TU_DEBUG", sel.joinToString(",").ifEmpty { null }) }
                )
            )
        }
        GNote(cur.joinToString(",").ifEmpty { "未选择" })
    }
    // ---- 保存去向说明 + env.conf 其他变量可见（"读取配置文件"的直观证据） ----
    GNote(
        if (hasOptEco)
            "保存写入 /opt/conf/env.conf（全局，下次启动 wine 生效；容器 env= 同名键已自动清理）"
        else
            "未检测到 /opt 生态：保存写入容器 env=（启动链 applyExtraEnv 注入生效）"
    )
    val others = envConf.keys.filter { it !in GuanliSupport.ENV_TAB_KEYS }
    if (others.isNotEmpty()) {
        GNote("env.conf 其他变量（本页不管理）：" + others.joinToString("，") { "$it=${envConf[it]}" }.take(220))
    }
}

// ===========================================================================
// 高级功能选项卡（wineguanli.py create_adv_tab：CNC DDraw）
// ===========================================================================

@Composable
private fun AdvancedFuncTab(container: ContainerData) {
    val context = LocalContext.current
    var exeName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    GGroup("CNC DDraw配置") {
        OutlinedTextField(
            value = exeName,
            onValueChange = { exeName = it },
            label = { Text("目标游戏 exe 文件名（如 game.exe）", fontSize = gsp(10.sp)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
            GSmallButton("应用CNC配置", GuanliColors.blueFab, Modifier.weight(1f), enabled = !busy) {
                busy = true
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    val err = GuanliSupport.AppDefaults.cncDdraw(container, exeName.trim())
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        busy = false
                        android.widget.Toast.makeText(
                            context, err ?: "CNC DDraw 配置已写入（ddraw/d3d9/dxgi 原生优先）",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            GSmallButton("清除CNC配置", GuanliColors.redFab, Modifier.weight(1f), enabled = !busy) {
                busy = true
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    val err = GuanliSupport.AppDefaults.clear(container, exeName.trim())
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        busy = false
                        android.widget.Toast.makeText(
                            context, err ?: "已清除", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        GNote(
            "说明：\n1. CNC DDraw配置用于改进老游戏的图形渲染\n" +
                "2. 通过 wine AppDefaults 注册表为该 exe 设置 ddraw/d3d9/dxgi 原生优先\n" +
                "3. cnc-ddraw.dll 由用户放入游戏目录（版权原因不随 APK 分发）"
        )
    }
}

// ===========================================================================
// Direct3D 选项卡（wineguanli.py create_d3d_tab：强制 D3D9/D3D11 + 清除）
// ===========================================================================

@Composable
private fun D3dTab(container: ContainerData) {
    val context = LocalContext.current
    var exeName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun run(action: suspend () -> String?) {
        busy = true
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val err = action()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                busy = false
                android.widget.Toast.makeText(
                    context, err ?: "操作完成", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    GGroup("Direct3D 9配置") {
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
            GSmallButton("强制D3D9模式", GuanliColors.blueFab, Modifier.weight(1f), enabled = !busy) {
                run { GuanliSupport.AppDefaults.forceD3d(container, exeName.trim()) }
            }
            GSmallButton("清除D3D9配置", GuanliColors.redFab, Modifier.weight(1f), enabled = !busy) {
                run { GuanliSupport.AppDefaults.clear(container, exeName.trim()) }
            }
        }
    }
    GGroup("Direct3D 11配置") {
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
            GSmallButton("强制D3D11模式", GuanliColors.blueFab, Modifier.weight(1f), enabled = !busy) {
                run { GuanliSupport.AppDefaults.forceD3d11(container, exeName.trim()) }
            }
            GSmallButton("清除D3D11配置", GuanliColors.redFab, Modifier.weight(1f), enabled = !busy) {
                run { GuanliSupport.AppDefaults.clear(container, exeName.trim()) }
            }
        }
    }
    GGroup("目标EXE文件") {
        OutlinedTextField(
            value = exeName,
            onValueChange = { exeName = it },
            placeholder = { Text("输入EXE文件名以应用配置（如 game.exe）", fontSize = gsp(10.sp)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
            modifier = Modifier.fillMaxWidth()
        )
    }
    GNote(
        "说明：\n1. 强制D3D9模式：将游戏强制使用Direct3D 9渲染\n" +
            "2. 强制D3D11模式：将游戏强制使用Direct3D 11渲染\n" +
            "3. 清除配置：删除对应 exe 的覆盖项\n" +
            "4. 经 wine AppDefaults 注册表实现（等效 wineguanli 的复制 DLL 方案，不动用户文件）"
    )
}

// ===========================================================================
// 🧩 LinBox 原生选项卡（wineguanli 之外 LinBox 特有能力，避免功能回退）
// ===========================================================================

@Composable
private fun NativeTab(
    edit: ContainerData,
    onChange: (ContainerData) -> Unit,
    onSaved: () -> Unit
) {
    GGroup("CPU 翻译后端（LinBox 原生）") {
        GCheckFlow(
            items = ContainerBackend.entries.map { it.id to it.label },
            checked = { it == edit.backend.id },
            onToggle = { id, _ -> onChange(edit.copy(backend = ContainerBackend.fromId(id))) }
        )
        GNote("wineguanli 的架构下拉映射 x86_64→box64 / arm64ec→arm64ec；此处为完整后端集（auto/hangover/fexcore/native）")
    }
    if (edit.backend == ContainerBackend.BOX64 || edit.backend == ContainerBackend.AUTO) {
        GGroup("Box64 预设（LinBox 精简两档）") {
            GCheckFlow(
                items = Box64Preset.entries.map { it.id to it.label },
                checked = { it == edit.preset.id },
                onToggle = { id, _ -> onChange(edit.copy(preset = Box64Preset.fromId(id))) }
            )
            GNote("细粒度 BOX64_DYNAREC_* 请用高级设置 → ⚡DY配置（wineguanli 三预设 + 自定义变量）")
        }
    }
    GGroup("Mesa 渲染器 / DXVK 注入 / 音频（LinBox 原生）") {
        GField("Mesa 渲染器") {
            GCheckFlow(
                items = GalliumDriver.entries.map { it.id to it.label },
                checked = { it == edit.gallium },
                onToggle = { id, _ -> onChange(edit.copy(gallium = id)) }
            )
        }
        GField("DXVK 图形加速（APK 内置资产）") {
            GCheckFlow(
                items = listOf(
                    ContainerData.DXVK_AUTO to "开启",
                    ContainerData.DXVK_OFF to "关闭"
                ),
                checked = { it == if (edit.dxvk == ContainerData.DXVK_OFF) ContainerData.DXVK_OFF else ContainerData.DXVK_AUTO },
                onToggle = { v, _ -> onChange(edit.copy(dxvk = v)) }
            )
        }
        GField("音频") {
            GCheckFlow(
                items = ContainerAudio.entries.map { it.id to it.label },
                checked = { it == edit.audio.id },
                onToggle = { id, _ -> onChange(edit.copy(audio = ContainerAudio.fromId(id))) }
            )
        }
    }
    GNote("保存 = 写入 container.conf（与 wineguanli 字段同一文件；LinBox 启动链接键消费）")
}

// ===========================================================================
// MANGOHUD 配置对话框（wineguanli.py MangohudConfigDialog 完整移植）
// ===========================================================================

@Composable
internal fun MangoHudConfigDialog(
    container: ContainerData,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    val (initToggles, initNoDisplay, _) = GuanliSupport.MangoHud.parseConfig(container.mangohud)
    val mFont = Regex("font_size=(\\d+)").find(container.mangohud)?.groupValues?.get(1)?.toIntOrNull() ?: 24
    val mPos = Regex("position=([a-z-]+)").find(container.mangohud)?.groupValues?.get(1) ?: "top-left"
    val mAlpha = Regex("background_alpha=([0-9.]+)").find(container.mangohud)?.groupValues?.get(1)?.toFloatOrNull()
        ?.let { (it * 100).toInt() } ?: 50

    var toggles by remember { mutableStateOf(initToggles) }
    var noDisplay by remember { mutableStateOf(initNoDisplay) }
    var fontSize by remember { mutableStateOf(mFont) }
    var position by remember { mutableStateOf(mPos) }
    var alpha by remember { mutableStateOf(mAlpha) }

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "配置 MANGOHUD · ${container.name}",
        onDismiss = onDismiss,
        width = 520.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp))
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
        ) {
            // 基本性能指标（wineguanli 八勾选）
            GGroup("基本性能指标") {
                GuanliSupport.MangoHud.TOGGLES.filter { it.first != "horizontal" && it.first != "no_display" }
                    .chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gdp(10.dp))) {
                            rowItems.forEach { (key, label) ->
                                GCheckRow(
                                    label, key in toggles,
                                    Modifier.weight(1f)
                                ) { on ->
                                    toggles = if (on) toggles + key else toggles - key
                                }
                            }
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                GCheckRow("横屏显示", "horizontal" in toggles) { on ->
                    toggles = if (on) toggles + "horizontal" else toggles - "horizontal"
                }
                GCheckRow("无显示模式", noDisplay) { noDisplay = it }
            }
            GGroup("布局设置") {
                GField("字体大小（10-36）") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$fontSize", fontSize = gsp(13.sp), fontWeight = FontWeight.Bold, color = GuanliColors.blueFab)
                        Spacer(Modifier.width(gdp(10.dp)))
                        Slider(
                            value = fontSize.toFloat(),
                            onValueChange = { fontSize = it.toInt().coerceIn(10, 36) },
                            valueRange = 10f..36f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                GField("显示位置") {
                    Row(horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
                        GuanliSupport.MangoHud.POSITIONS.forEach { p ->
                            GSmallButton(
                                p, if (position == p) GuanliColors.blueFab else Color(0xFF243049),
                                Modifier, fg = if (position == p) Color.White else themeFg()
                            ) { position = p }
                        }
                    }
                }
                GField("背景透明度（0-100）") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$alpha", fontSize = gsp(13.sp), fontWeight = FontWeight.Bold, color = GuanliColors.blueFab)
                        Spacer(Modifier.width(gdp(10.dp)))
                        Slider(
                            value = alpha.toFloat(),
                            onValueChange = { alpha = it.toInt().coerceIn(0, 100) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(
                onClick = {
                    val config = GuanliSupport.MangoHud.buildConfig(toggles, fontSize, position, alpha, noDisplay)
                    cs.launch(Dispatchers.IO) {
                        ContainerManager.update(container.copy(mangohud = config))
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                context, "MANGOHUD配置已保存", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            onSaved()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) { Text("保存") }
        }
            }
    }
}

// ===========================================================================
// 多选对话框（wineguanli.py MultiSelectDialog）
// ===========================================================================

@Composable
private fun MultiSelectDialog(
    title: String,
    options: List<String>,
    initial: List<String>,
    onDismiss: () -> Unit,
    onOk: (List<String>) -> Unit
) {
    var selected by remember { mutableStateOf(initial.toSet()) }
    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = title,
        onDismiss = onDismiss,
        width = 420.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp))
        ) {
            options.forEach { opt ->
                GCheckRow(opt, opt in selected, Modifier.fillMaxWidth()) { on ->
                    selected = if (on) selected + opt else selected - opt
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(onClick = { onOk(selected.toList()) }) { Text("确定") }
        }
            }
    }
}

// ===========================================================================
// 新建容器对话框（wineguanli.py show_new_container_dialog 逐字段移植）
// ===========================================================================

@Composable
internal fun NewContainerDialog(
    wines: List<WineInstall>,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var arch by remember { mutableStateOf("x86_64") }
    var wine by remember { mutableStateOf("") }
    // v3.4.1【撤销 v3.4.0 A3 720p】：新建容器默认回归"全屏"（native 全
    // 物理分辨率）。用户明确指令："主桌面分辨率不要动" —— X11 运行时性能
    // 靠 X server 前台主进程化 + 桌面 launcher 式让位解决（见
    // DesktopPerfGovernor / X11Support），不降桌面分辨率。
    var res by remember { mutableStateOf("全屏") }
    var lang by remember { mutableStateOf(ContainerLangs.DEFAULT) }
    var frame by remember { mutableStateOf("默认(不限制)") }
    var error by remember { mutableStateOf<String?>(null) }

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "创建新容器",
        onDismiss = onDismiss,
        // v3.2.22：总尺寸收紧（用户反馈“总大小也要降低点”）——
        // 520→430dp（×K 后 344dp），内边距 14/10→12/8、行距 8→6
        width = 430.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(12.dp), vertical = gdp(8.dp))
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(gdp(6.dp))
        ) {
            GField("容器名称:") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    isError = error != null,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(13.sp)),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) Text(error ?: "", color = GuanliColors.red, fontSize = gsp(11.sp))
            }
            // v3.2.22：架构选择去重组框（GGroup 边框+上下 24dp 内衬是弹窗
            // “大得离谱”的次要来源），改 GField 轻量行 + 紧凑定宽下拉
            // （150dp 容得下 arm64ec + 箭头；原 fillMaxWidth 在 Row 里抢占整行）
            GField("架构:") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
                    GComboBox(options = listOf("x86_64", "arm64ec"), selected = arch, onSelect = { arch = it }, width = 150.dp)
                    Text("(arm64ec使用fexwine)", fontSize = gsp(10.sp), color = GuanliColors.info)
                }
            }
            GField("Wine版本:") {
                GComboBox(
                    options = listOf("（默认）") + wines.map { it.displayLabel },
                    selected = if (wine.isBlank()) "（默认）" else wines.firstOrNull { it.name == wine }?.displayLabel ?: wine,
                    onSelect = { label ->
                        wine = if (label == "（默认）") "" else wines.firstOrNull { it.displayLabel == label }?.name ?: ""
                    }
                )
                if (wines.isEmpty()) GNote("未发现已安装 wine（/usr/opt）—— 高级设置 → Wine安装 可先装")
            }
            GField("分辨率:") {
                GComboBox(
                    options = listOf("全屏", "800x600", "1024x768", "1280x720", "1366x768", "1600x900", "1920x1080", "2560x1440"),
                    selected = res,
                    onSelect = { res = it }
                )
            }
            GField("语言:") {
                // v3.3.2 恢复：新建容器即可选语言，保存进容器 lang=，
                // 启动链写入 LANG/LC_ALL。v3.3.4：默认 = 简体中文
                //（跟随 /opt/locale.conf 降为可选项）。
                GComboBox(
                    options = ContainerLangs.DISPLAY,
                    selected = ContainerLangs.displayOf(lang),
                    onSelect = { lang = ContainerLangs.valueOf(it) }
                )
            }
            GField("帧数限制:") {
                GComboBox(
                    options = listOf("默认(不限制)", "60", "120"),
                    selected = frame,
                    onSelect = { frame = it }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(
                onClick = {
                    val n = name.trim()
                    if (n.isEmpty()) { error = "请输入容器名称"; return@Button }
                    val backend = if (arch == "arm64ec") ContainerBackend.ARM64EC else ContainerBackend.BOX64
                    val data = ContainerData(
                        name = n,
                        backend = backend,
                        wine = wine,
                        screen = if (res == "全屏") "native" else res,
                        dmode = if (res == "全屏") DisplayMode.OFF else DisplayMode.D,
                        lang = lang,
                        frame = when (frame) { "60" -> "60"; "120" -> "120"; else -> "0" }
                    )
                    val err = ContainerManager.create(data)
                    if (err == null) onCreated(n) else error = err
                },
                colors = ButtonDefaults.buttonColors(containerColor = GuanliColors.accent)
            ) { Text("确定") }
        }
            }
    }
}

// ===========================================================================
// 重命名容器（wineguanli.py rename_container）
// ===========================================================================

@Composable
private fun RenameContainerDialog(
    container: ContainerData,
    onDismiss: () -> Unit,
    onRenamed: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(container.name) }
    var error by remember { mutableStateOf<String?>(null) }
    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "重命名容器 · ${container.name}",
        onDismiss = onDismiss,
        width = 440.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp)),
            verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("新容器名") },
                isError = error != null,
                singleLine = true
            )
            if (error != null) Text(error ?: "", color = GuanliColors.red, fontSize = gsp(11.sp))
            GNote("重命名 = 目录改名，WINEPREFIX 路径同步变化（运行中的容器先停止）")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(onClick = {
                val n = name.trim()
                if (n == container.name) { onDismiss() } else onRenamed(container.name, n)
            }) { Text("重命名") }
        }
            }
    }
}

// ===========================================================================
// 快捷方式对话框（wineguanli.py ShortcutDialog 的 Android 版）
// ===========================================================================

@Composable
private fun ShortcutDialog(
    container: ContainerData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(container.name) }
    var exe by remember { mutableStateOf(container.exe) }
    var msg by remember { mutableStateOf<String?>(null) }

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "创建桌面快捷方式",
        onDismiss = onDismiss,
        width = 480.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp)),
            verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
        ) {
            GField("快捷方式名称：") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            GField("容器：") {
                Text(container.name, fontSize = gsp(13.sp), fontWeight = FontWeight.Bold, color = themeFg())
            }
            GField("启动程序（空 = 打开容器窗口再选）：") {
                OutlinedTextField(
                    value = exe, onValueChange = { exe = it },
                    placeholder = { Text("C:\\windows\\notepad.exe", fontSize = gsp(10.sp)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            GNote(
                "固定到 Android 启动器（ShortcutManager，系统弹确认框）。\n" +
                    "点按快捷方式会打开 LinBox 容器窗口；容器 exe 已配置时直接运行。"
            )
            if (msg != null) Text(msg ?: "", color = GuanliColors.red, fontSize = gsp(11.sp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(
                onClick = {
                    if (exe.isNotBlank()) {
                        // 把 exe 记进容器（快捷方式与"▶ 运行"共用）
                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                            ContainerManager.update(container.copy(exe = exe))
                        }
                    }
                    val ok = GuanliSupport.Shortcuts.pinRunContainer(context, container, exe)
                    msg = if (ok) null else "本设备/启动器不支持固定快捷方式（系统会话管理器未授权）"
                    if (ok) onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = GuanliColors.accent)
            ) { Text("创建") }
        }
            }
    }
}

// ===========================================================================
// 运行 exe（保留 v3.2.13 的文件选择回填；默认填容器 exe）
// ===========================================================================

@Composable
internal fun RunContainerDialog(
    container: ContainerData,
    // v3.3.8：带预检的运行入口（驱动解压已统一进 RootfsLauncher 启动链步骤 0）
    onRunExe: (exe: String, args: String) -> Unit,
    onDismiss: () -> Unit,
    onLaunched: () -> Unit
) {
    val context = LocalContext.current
    var exe by remember(container.name) {
        mutableStateOf(container.exe.ifBlank { "C:\\windows\\notepad.exe" })
    }
    var args by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    DisposableEffect(container.name) {
        val unregister = com.linbox.apps.filemanager.FilePickBus.listen(
            targetApp = "containers_run_${container.name}",
            targetWindow = ""
        ) { path ->
            exe = path
            picking = false
        }
        onDispose { unregister() }
    }

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "运行 · ${container.name}",
        onDismiss = onDismiss,
        width = 560.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp))
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
        ) {
            GField("程序路径", "Windows 路径（C:\\…）或容器内绝对路径（/storage/…）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = exe, onValueChange = { exe = it; msg = null },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(gdp(8.dp)))
                    GSmallButton(if (picking) "选择中…" else "浏览…", GuanliColors.blueFab, Modifier) {
                        picking = true
                        com.linbox.core.shell.ShellController.showFiles(
                        pickMode = "exe",
                        targetApp = "containers_run_${container.name}",
                        targetWindow = ""
                    )
                    }
                }
            }
            GField("启动参数", "传给程序的命令行参数（可空）") {
                OutlinedTextField(
                    value = args, onValueChange = { args = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            GNote("点击\"运行\"将自动打开 X11 桌面窗口并经 box64/FEXCore 启动 Wine。")
            if (msg != null) {
                Text(msg ?: "", color = GuanliColors.red, fontSize = gsp(12.sp))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(
                onClick = {
                    // v3.3.8：经外层 onRunExe（带驱动解压）启动；同步错误预检
                    // 仍走 runExe 的返回值语义（外层 Toast 展示）
                    val pre = WineSessionLauncher.precheckRun(container)
                    if (pre != null) { msg = pre } else {
                        onRunExe(exe.trim(), args.trim())
                        onLaunched()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GuanliColors.green)
            ) { Text("运行") }
        }
            }
    }
}

// ===========================================================================
// wineguanli 风格共享组件（QGroupBox / QComboBox / QPushButton / QCheckBox）
// 全部经 gdp/gsp 缩放，任意窗口宽度下等比适配
// ===========================================================================

/** QGroupBox：圆角描边 + 左上角蓝色标题片（Qt 样式表同款视觉）。 */
@Composable
private fun GGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(gdp(8.dp)))
            .background(themeCard())
            .border(gdp(1.dp), GuanliColors.accent.copy(alpha = 0.45f), RoundedCornerShape(gdp(8.dp)))
            .padding(top = gdp(14.dp), bottom = gdp(10.dp), start = gdp(12.dp), end = gdp(12.dp)),
        verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
    ) {
        Text(
            title,
            fontSize = gsp(13.sp), fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(gdp(4.dp)))
                .background(GuanliColors.accent)
                .padding(horizontal = gdp(10.dp), vertical = gdp(2.dp))
        )
        content()
    }
}

/** 字段行：标签在上（QFormLayout 行 / QLabel）。 */
@Composable
private fun GField(
    label: String,
    // v3.2.20 修复：2906/2930 两处调用为三参形态 GField(标签, 说明) { … }，
    // 原二参签名报 "Too many arguments" + "String 但 () -> Unit" ——
    // 补可选 note 参数（Qt 表单的 field 说明小字），旧二参调用不受影响。
    note: String? = null,
    content: @Composable () -> Unit
) {
    Column {
        Text(label, fontSize = gsp(12.sp), fontWeight = FontWeight.Medium, color = themeFg())
        if (note != null) GNote(note)
        else Spacer(Modifier.height(gdp(4.dp)))
        content()
    }
}

/** 说明小字（Qt note 样式 #7f8c8d）。 */
@Composable
private fun GNote(text: String) {
    Text(
        text,
        fontSize = gsp(10.sp), color = GuanliColors.info, lineHeight = gsp(14.sp)
    )
}

/** Qt 风格彩色按钮（QPushButton：圆角 6dp、粗体、白字、min-width）。 */
@Composable
private fun GSmallButton(
    label: String,
    bg: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fg: Color = Color.White,
    onClick: () -> Unit
) {
    Text(
        label,
        color = if (enabled) fg else fg.copy(alpha = 0.45f),
        fontSize = gsp(12.sp), fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(gdp(6.dp)))
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = gdp(12.dp), vertical = gdp(7.dp))
    )
}

/** QCheckBox 行。 */
@Composable
private fun GCheckRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(gdp(4.dp)))
            .clickable { onChange(!checked) }
            .padding(horizontal = gdp(2.dp), vertical = gdp(2.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onChange(it) },
            modifier = Modifier
                .requiredSize(gdp(18.dp))
                .padding(end = 0.dp)
        )
        Spacer(Modifier.width(gdp(6.dp)))
        Text(label, fontSize = gsp(12.sp), color = themeFg())
    }
}

/** 勾选药丸流（核心/开关组，FlowRow 自动换行）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GCheckFlow(
    items: List<Pair<String, String>>,
    checked: (String) -> Boolean,
    onToggle: (String, Boolean) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(gdp(6.dp)),
        verticalArrangement = Arrangement.spacedBy(gdp(6.dp))
    ) {
        items.forEach { (value, label) ->
            val on = checked(value)
            Row(
                Modifier
                    .clip(RoundedCornerShape(gdp(14.dp)))
                    .background(if (on) GuanliColors.accent.copy(alpha = 0.18f) else themeCard())
                    .border(
                        gdp(0.5.dp),
                        if (on) GuanliColors.accent else themeSec().copy(alpha = 0.3f),
                        RoundedCornerShape(gdp(14.dp))
                    )
                    .clickable { onToggle(value, !on) }
                    .padding(horizontal = gdp(10.dp), vertical = gdp(5.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (on) "✓ " else "", fontSize = gsp(11.sp), color = GuanliColors.accent)
                Text(
                    label, fontSize = gsp(11.sp),
                    color = if (on) GuanliColors.accent else themeFg(),
                    maxLines = 1
                )
            }
        }
    }
}

/** QRadioButton 行。 */
@Composable
private fun GRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(gdp(4.dp)))
            .clickable { onClick() }
            .padding(vertical = gdp(2.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(gdp(4.dp)))
        Text(label, fontSize = gsp(12.sp), color = themeFg())
    }
}

/**
 * QComboBox 等价物：显示框 + 下拉菜单。
 * @param customLabel 选中值不在选项里时显示的自定义文本（可编辑下拉的当前值）
 * @param width 紧凑宽度（v3.2.22：不传 = 占满整行，表单字段用；
 * 传入则按 gdp(width) 定宽 —— 选项少的短下拉（架构选择 x86_64/arm64ec
 * 两项）在 Row 里与提示文字并排时用，避免 fillMaxWidth 把同行
 * 后续元素挤没、下拉框拉得比弹窗还宽（用户实测“大得离谱”））
 */
@Composable
private fun GComboBox(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    customLabel: String? = null,
    width: Dp? = null
) {
    var open by remember { mutableStateOf(false) }
    val display = customLabel
        ?: options.firstOrNull { it == selected } ?: selected
    Box {
        Row(
            Modifier
                .then(if (width != null) Modifier.width(gdp(width)) else Modifier.fillMaxWidth())
                .clip(RoundedCornerShape(gdp(4.dp)))
                .background(themeCard())
                .border(gdp(1.dp), GuanliColors.accent, RoundedCornerShape(gdp(4.dp)))
                .clickable { open = true }
                .padding(horizontal = gdp(10.dp), vertical = gdp(8.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                display, fontSize = gsp(13.sp), color = themeFg(),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(" ▾", fontSize = gsp(12.sp), color = GuanliColors.accent)
        }
        GDropdownMenu(expanded = open, onDismiss = { open = false }) {
            options.forEach { opt ->
                GDropdownMenuItem(opt) {
                    open = false
                    onSelect(opt)
                }
            }
        }
    }
}

/** 下拉菜单容器（material3 DropdownMenu 包一层）。 */
@Composable
private fun GDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    // v3.2.20 修复：material3 1.2.1（compose-bom 2024.06.00）的 DropdownMenu
    // 尚无 containerColor 参数（CI 实报 "Cannot find a parameter with this
    // name: containerColor"）—— 菜单底色改为包一层同色 Column 实现，
    // 菜单尺寸本就包裹内容（IntrinsicSize.Min），视觉等效。
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(IntrinsicSize.Min)
    ) {
        Column(Modifier.background(themeCard(), RoundedCornerShape(gdp(4.dp)))) {
            content()
        }
    }
}

/** 下拉菜单项（QMenu::item 样式：选中蓝底白字）。 */
@Composable
private fun GDropdownMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = gsp(12.sp), color = themeFg()) },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = gdp(14.dp), vertical = gdp(4.dp))
    )
}

@Composable
private fun GDropdownMenuDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = themeSec().copy(alpha = 0.25f),
        modifier = Modifier.padding(vertical = gdp(2.dp))
    )
}

// ---------------------------------------------------------------------------
// rootfs 缺失提示（保留）
// ---------------------------------------------------------------------------

@Composable
private fun RootfsMissingHint() {
    Card(
        colors = CardDefaults.cardColors(containerColor = themeCard()),
        shape = RoundedCornerShape(gdp(8.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gdp(12.dp), vertical = gdp(4.dp))
    ) {
        Column(Modifier.padding(gdp(12.dp))) {
            Text("未检测到 bionic rootfs", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(gdp(4.dp)))
            Text(
                "未检测到 bionic rootfs。若本 APK 内置了 rootfs 资产（assets/linbox/rootfs-*.tzst），" +
                    "启动时会自动解压到 /data/data/com.linbox/files/rootfs（请稍候重进）；" +
                    "也可在终端用 linbox-tarxz 导入 build-bionic-rootfs.yml 构建的 rootfs 包（内含 linbox-container CLI）。",
                fontSize = gsp(11.sp), color = themeSec()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 体检（保留 v3.2.13：box64 运行探活）
// ---------------------------------------------------------------------------

@Composable
internal fun DoctorDialog(onDismiss: () -> Unit) {
    var checks by remember { mutableStateOf<List<DoctorCheck>?>(null) }
    LaunchedEffect(Unit) {
        checks = withContext(Dispatchers.IO) { ContainerManager.doctor() }
    }
    val loaded = checks

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "LinBox Bionic 容器体检",
        onDismiss = onDismiss,
        width = 560.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        if (loaded == null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gdp(14.dp), vertical = gdp(20.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(gdp(28.dp)),
                    strokeWidth = gdp(2.5.dp),
                    color = themeAccent()
                )
                Spacer(Modifier.height(gdp(10.dp)))
                Text("正在体检（box64 运行探活最长 5 秒）…", fontSize = gsp(12.sp), color = themeSec())
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp)),
                verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
            ) {
                items(loaded.size) { i ->
                    val c = loaded[i]
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (c.ok) "✓" else "✗",
                            color = if (c.ok) themeAccent() else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(gdp(8.dp)))
                        Column {
                            Text(c.title, fontWeight = FontWeight.Bold, fontSize = gsp(13.sp))
                            Text(c.detail, fontSize = gsp(11.sp), color = themeSec())
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onDismiss) { Text("好的") }
        }
            }
    }
}

// ---------------------------------------------------------------------------
// 日志（保留）
// ---------------------------------------------------------------------------

@Composable
private fun LogDialog(title: String, log: String, onDismiss: () -> Unit) {
    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = title,
        onDismiss = onDismiss,
        width = 620.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp))
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                log.takeLast(20000),
                fontSize = gsp(11.sp),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = themeSec(),
                lineHeight = gsp(15.sp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onDismiss) { Text("关闭") }
        }
            }
    }
}

// ---------------------------------------------------------------------------
// v3.1.6 前缀构建日志（保留）
// ---------------------------------------------------------------------------

@Composable
private fun PrefixBuildLogDialog(
    containerName: String,
    running: Boolean,
    onDismiss: () -> Unit
) {
    var text by remember(containerName) { mutableStateOf(RootfsLauncher.prefixLogOf(containerName)) }
    LaunchedEffect(containerName) {
        while (true) {
            text = RootfsLauncher.prefixLogOf(containerName)
            kotlinx.coroutines.delay(400)
        }
    }
    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    // v3.2.29：topEnd —— 日志窗靠右上角级联放置，不再盖住屏幕中央的
    // X11 桌面；构建全程（mono/gecko 提示框等）可直接点击 X11 窗口，
    // 修复“构建前缀日志显示后 X11 窗口无法点击”。
    WinDialog(
        title = "构建 Wine 前缀 · $containerName" + if (running) "（进行中…）" else "（已结束）",
        onDismiss = onDismiss,
        width = 520.dp * GuanliUiK,
        topEnd = true
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp))
                .verticalScroll(rememberScrollState())
        ) {
            GNote("💡 日志窗已靠右上角放置，不遮挡『X11 桌面』——构建期间的提示框请直接在 X11 桌面窗口中确认")
            Text(
                text.ifEmpty { "（暂无输出——wineboot 启动后此处逐行显示；若长时间空白说明 wine 挂在启动早期）" },
                fontSize = gsp(11.sp),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = themeSec(),
                lineHeight = gsp(15.sp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onDismiss) { Text("关闭") }
        }
            }
    }
}
