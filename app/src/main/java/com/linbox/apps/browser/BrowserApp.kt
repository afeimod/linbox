package com.linbox.apps.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import com.linbox.core.input.keyboardAware
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.linbox.LinBoxApp
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import com.linbox.core.window.AppDef
import com.linbox.core.window.WindowManager
import com.linbox.data.db.entity.BookmarkEntity
import com.linbox.data.model.DesktopItemType
import com.linbox.util.ImmersiveMode
import kotlinx.coroutines.launch

/**
 * 浏览器应用定义。
 *
 * v2.8 功能清单：
 * - 多标签页（TabManager，标签专属 WebView 常驻缓存，切换不重建）
 * - 前进/后退/刷新/主页
 * - 地址栏（自动补全 https://）
 * - 本地 HTML 文件读取（通过 SAF 选择文件）
 * - 书签管理（持久化到 Room）
 * - 历史记录（持久化到 Room）
 * - 首页快捷导航（百度/B站/GitHub/必应等）
 * - window.open / target=_blank / 表单弹窗 → 真实新标签
 * - 真全屏（隐藏状态栏/导航键/任务栏/标签栏/工具栏，左上角悬浮按钮或返回键恢复窗口；
 *   v2.16 已移除旧版“双击页面退出全屏”手势，避免与网页游戏双击操作冲突）
 * - HTML5 视频全屏（DecorView 覆盖层，满屏播放）
 * - v2.16.3 浏览器设置：3D 视角旋转（鼠标视角模式，参照 GameBox 重做）——
 *   开启后先点击游戏画面（触发模拟 Pointer Lock 锁定），再按住拖动即可
 *   旋转游戏视角：拖动增量合成 mousemove(movementX/Y) 注入页面，
 *   由游戏自身旋转相机/视角（用于电脑网页游戏），页面不做任何视觉变换
 */
val BrowserApp = AppDef(
    id = "browser",
    displayName = "浏览器",
    iconAsset = "app:browser",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 980.dp,
    defaultHeight = 640.dp,
    pinnedToTaskbar = true,
    pinnedToDesktop = true
) { scope ->
    BrowserContent(scope)
}

@Composable
private fun BrowserContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val app = LinBoxApp.get()
    val scope0 = rememberCoroutineScope()
    val wm = remember { WindowManager.get() }
    // 监听 WindowManager 变化（用于 isTrueFullscreen 状态切换时工具栏图标立即更新）
    var wmRevision by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { wm.observe { wmRevision++ } }
    val isFullscreen = remember(wmRevision) { scope.windowState.isTrueFullscreen }

    val launchUrl = scope.windowState.launchArgs["url"]
    val launchType = scope.windowState.launchArgs["type"]

    // ===== 会话（TabManager 挂在 BrowserSessions 注册表，跨最小化存活）=====
    val tabManager = remember(scope.windowState.id) {
        BrowserSessions.getOrCreate(scope.windowState.id)
    }
    // 窗口真正关闭时销毁全部 WebView（修复"关了浏览器还在响"复发）；
    // 注意不能挂在 onDispose 上 —— 最小化也会触发 onDispose
    DisposableEffect(scope.windowState.id) {
        scope.windowState.onClose = { BrowserSessions.destroy(scope.windowState.id) }
        onDispose { }
    }

    // 桌面/手机模式（持久化，切换后立即重载当前页生效）
    val uaMode by app.settingsStore.browserUaMode.collectAsState(initial = "desktop")
    SideEffect { tabManager.uaMode = uaMode }
    fun toggleUaMode() {
        val next = if (uaMode == "desktop") "mobile" else "desktop"
        scope0.launch { app.settingsStore.setBrowserUaMode(next) }
    }

    // ===== v2.16.3：浏览器设置 —— 3D 视角旋转（鼠标视角模式，参照 GameBox 重做） =====
    // 开启后：先点击游戏画面（模拟 Pointer Lock 锁定），再按住拖动 = 转视角
    //（ZoomPinchLayout 旁路观察拖动 → View3dController 累积增量 → 页面 rAF
    // 取走并派发 mousemove(movementX/Y)），游戏自身完成视角旋转；
    // 灵敏度可调，全部持久化到 DataStore。
    val view3dEnabled by app.settingsStore.browser3dEnabled.collectAsState(initial = false)
    val view3dSensitivity by app.settingsStore.browser3dSensitivity.collectAsState(initial = 1f)
    var showView3dSettings by remember { mutableStateOf(false) }
    // 状态同步到控制器（手势层/JS 桥直接读，避免每帧穿过 Compose）；
    // 关闭时同时清空残留增量，避免下次开启时视角跳变
    // v2.19：开关切换时对已加载页面补注入/停止 rAF pull 循环 ——
    // onPageFinished 只覆盖之后的导航，且普通页面不该常驻每帧 JNI
    SideEffect {
        val wasEnabled = View3dController.enabled
        View3dController.enabled = view3dEnabled
        View3dController.sensitivity = view3dSensitivity
        if (!view3dEnabled) {
            View3dController.reset()
            tabManager.tabs.forEach { t ->
                t.webView?.let { wv ->
                    runCatching {
                        wv.evaluateJavascript(
                            "try{window.__linboxLookStop&&window.__linboxLookStop()}catch(e){}", null
                        )
                    }
                }
            }
        } else if (!wasEnabled) {
            tabManager.tabs.forEach { t ->
                t.webView?.let { wv ->
                    runCatching {
                        wv.evaluateJavascript(View3dController.LOOK_SETUP_SCRIPT, null)
                    }
                }
            }
        }
    }

    // 用户设置的主页（默认 LinBox 速度页 linbox://home）
    val defaultHome by app.settingsStore.defaultBrowserHome.collectAsState(initial = "linbox://home")

    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // 启动时打开初始标签（会话已存在标签时跳过 —— 最小化恢复不重开）
    LaunchedEffect(Unit) {
        if (tabManager.tabs.isEmpty()) {
            val initialUrl = when {
                launchUrl.isNullOrEmpty() -> defaultHome
                launchType == DesktopItemType.SHORTCUT_FILE.name && launchUrl.startsWith("content://") -> launchUrl
                else -> normalizeUrl(launchUrl)
            }
            tabManager.openTab(initialUrl)
        }
    }

    // 进入真全屏时重新断言隐藏系统状态栏/导航键（部分系统会重新显示系统栏）
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            ImmersiveMode.applyTo(context)
        }
    }
    // 视频全屏（DecorView 覆盖层）显示期间同样隐藏系统栏
    val inVideoFullscreen = tabManager.customView != null
    LaunchedEffect(inVideoFullscreen) {
        if (inVideoFullscreen) {
            ImmersiveMode.applyTo(context)
        }
    }

    // 真全屏时的返回键 → 退出全屏恢复窗口
    //（v2.16：双击页面退出已移除，改用左上角悬浮按钮，见下方 FullscreenExitButton）
    BackHandler(enabled = isFullscreen) {
        wm.toggleTrueFullscreen(scope.windowState.id)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
    ) {
        Column(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {

            // ===== 标签栏（真全屏时隐藏，F11 风格纯页面） =====
            if (!isFullscreen) {
                TabBar(
                    tabs = tabManager.tabs,
                    activeTabId = tabManager.activeTabId,
                    onTabClick = { id -> tabManager.switchTo(id) },
                    onCloseTab = { id -> tabManager.closeTab(id) },
                    onNewTab = { tabManager.openTab("linbox://home") }
                )
            }

            // ===== 工具栏（真全屏时隐藏；点左上角悬浮按钮或按返回键恢复窗口） =====
            if (!isFullscreen) {
                Toolbar(
                    address = tabManager.addressInput,
                    onAddressChange = { tabManager.addressInput = it },
                    onBack = { tabManager.getTab(tabManager.activeTabId)?.goBack() },
                    onForward = { tabManager.getTab(tabManager.activeTabId)?.goForward() },
                    onRefresh = { tabManager.getTab(tabManager.activeTabId)?.refresh() },
                    onHome = {
                        tabManager.getTab(tabManager.activeTabId)?.loadUrl(defaultHome)
                        tabManager.addressInput = if (defaultHome == "linbox://home") "" else defaultHome
                    },
                    onGo = {
                        val url = normalizeUrl(tabManager.addressInput)
                        val tab = tabManager.getTab(tabManager.activeTabId)
                        if (tab != null && url.isNotEmpty()) {
                            // v2.16.2：tab.loadUrl 立即加载（已有 WebView 时不再
                            // 等 update 块重组触发，修复"点前往无反应"；历史记录
                            // 由引擎在 onPageStarted 统一写入）
                            tab.loadUrl(url)
                            tabManager.addressInput = url
                        }
                    },
                    onBookmark = {
                        val url = tabManager.addressInput
                        if (url.isNotEmpty()) {
                            scope0.launch {
                                val dao = app.database.bookmarkDao()
                                val existing = dao.findByUrl(url)
                                if (existing == null) {
                                    dao.insert(BookmarkEntity(title = url, url = url))
                                } else {
                                    dao.delete(existing)
                                }
                            }
                        }
                    },
                    onShowBookmarks = { showBookmarks = !showBookmarks },
                    onShowHistory = { showHistory = !showHistory },
                    uaMode = uaMode,
                    onToggleUaMode = { toggleUaMode() },
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = { wm.toggleTrueFullscreen(scope.windowState.id) },
                    onOpenView3d = { showView3dSettings = true }
                )
            }

            // ===== 内容区 =====
            // v2.16：移除旧版“双击页面退出全屏”手势（与网页游戏的双击操作冲突、
            // 误触率高），退出全屏改用左上角悬浮按钮（见下方 FullscreenExitButton）
            // 或系统返回键。
            // v2.16.2：旧版"整页 graphicsLayer 透视旋转"已移除（3D 视角旋转
            // 重做为鼠标视角模式：拖动注入鼠标事件由游戏自身转视角，见
            // View3dController；v2.16.3 参照 GameBox 重做为模拟 Pointer Lock
            // + mousemove(movementX/Y) 派发，触摸旁路不拦截）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val activeTab = tabManager.getTab(tabManager.activeTabId)
                if (activeTab != null) {
                    val url = activeTab.url
                    if (url == "linbox://home" || url.isEmpty()) {
                        BrowserHomePage(
                            onNavigate = { target ->
                                val finalUrl = normalizeUrl(target)
                                val tab = tabManager.getTab(tabManager.activeTabId)
                                if (tab != null) {
                                    // home → 真实 URL：tab.webView 为空时 factory 创建并延迟加载；
                                    // 已有 WebView 时 update 块负责 loadUrl
                                    tab.url = finalUrl
                                    tab.title = finalUrl
                                    tabManager.addressInput = finalUrl
                                }
                            }
                        )
                    } else {
                        // key(activeTab.id)：每个标签独立的组合位置 → 各自的 AndroidView。
                        // WebView 本身常驻缓存（切换标签不销毁），切回时原样恢复。
                        // v2.14.3：+ renderEpoch —— 渲染进程崩溃重建 WebView 时强制
                        // 重组，factory 走"无缓存"分支创建新实例并自动重载。
                        key(activeTab.id, activeTab.renderEpoch) {
                            WebViewContainer(
                                tab = activeTab,
                                tabManager = tabManager,
                                uaMode = uaMode,
                                onRetry = { tabManager.getTab(tabManager.activeTabId)?.refresh() }
                            )
                        }
                    }
                }
            }

            // ===== 书签/历史侧边面板 =====
            if (showBookmarks) {
                BookmarksPanel(
                    onSelect = { url ->
                        tabManager.addressInput = url
                        tabManager.getTab(tabManager.activeTabId)?.loadUrl(url)
                        showBookmarks = false
                    },
                    onClose = { showBookmarks = false }
                )
            }
            if (showHistory) {
                HistoryPanel(
                    onSelect = { url ->
                        tabManager.addressInput = url
                        tabManager.getTab(tabManager.activeTabId)?.loadUrl(url)
                        showHistory = false
                    },
                    onClose = { showHistory = false }
                )
            }

            // ===== v2.16.2：浏览器设置 —— 3D 视角旋转（鼠标视角模式）面板 =====
            if (showView3dSettings) {
                View3dSettingsPanel(
                    enabled = view3dEnabled,
                    sensitivity = view3dSensitivity,
                    onEnabled = { v -> scope0.launch { app.settingsStore.setBrowser3dEnabled(v) } },
                    onSensitivity = { v -> scope0.launch { app.settingsStore.setBrowser3dSensitivity(v) } },
                    onClose = { showView3dSettings = false }
                )
            }
        } // end Column

        // ===== v2.16：真全屏时的悬浮退出按钮（左上角小圆钮） =====
        // 旧版“双击页面退出全屏”已移除（与网页游戏双击操作冲突），
        // 改由本按钮或系统返回键退出全屏。
        // 视频全屏（DecorView 覆盖层）期间本按钮被系统覆盖层遮挡，
        // 此时用返回键退出视频全屏。
        if (isFullscreen) {
            FullscreenExitButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 10.dp),
                onExit = { wm.toggleTrueFullscreen(scope.windowState.id) }
            )
        }
    } // end Box (browser root)
}

/**
 * WebView 容器：渲染网页。
 *
 * v2.8 关键设计：
 * - WebView 由 [BrowserEngine.ensureWebView] 按【标签】缓存 —— 切换标签只是
 *   attach/detach；onRelease 仅在标签关闭（destroyPending）时销毁。
 * - 首次加载通过 view.post{} 推迟到 attach/layout 之后执行，修复
 *   "页面加载完成但 WebView 永不绘制（黑屏）"的首帧竞态。
 * - client 由引擎一次性创建，UI 回调经 TabManager.ui 路由到当前组合。
 * - HTML5 视频全屏时，返回键退出全屏。
 */
@Composable
private fun WebViewContainer(
    tab: BrowserTab,
    tabManager: TabManager,
    uaMode: String,
    onRetry: () -> Unit = {}
) {
    val theme = LocalWinTheme.current
    var loadProgress by remember { mutableStateOf(if (tab.webView == null) 0 else 100) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 注册 UI 回调（带属主令牌：切换标签时只有仍持有路由的一方才清理，
    // 避免新标签的注册被旧标签的 onDispose 误清）
    val uiToken = remember { Any() }
    DisposableEffect(tab.id) {
        tabManager.ui = object : TabUiCallbacks {
            override fun onUrlChanged(url: String) {
                tabManager.addressInput = url
            }

            override fun onUrlSync(url: String) {
                tabManager.addressInput = url
            }

            override fun onTitleChanged(title: String) {
                // 标题经 tab.title 反映到标签栏
            }

            override fun onProgressChanged(progress: Int) {
                loadProgress = progress
            }

            override fun onError(message: String?) {
                errorMessage = message
            }
        }
        tabManager.uiOwner = uiToken
        onDispose {
            if (tabManager.uiOwner === uiToken) {
                tabManager.ui = null
                tabManager.uiOwner = null
            }
        }
    }

    // 视频全屏（DecorView 覆盖层）期间：返回键退出视频全屏
    val inVideoFullscreen = tabManager.customView != null
    BackHandler(enabled = inVideoFullscreen) {
        tabManager.hideCustomView()
    }

    Box(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {
        AndroidView(
            factory = { ctx ->
                // v2.14.7：AndroidView 承载 ZoomPinchLayout（手势仲裁容器），
                // WebView 作为其子 View —— 双指缩放 30%~100% 缩小域由容器
                // 拦截手势并动态改写 viewport meta 实现（v2.14.9：
                // width=原宽/z + 钳制 scale=窗口宽/画布宽，整页等比变小且
                // 任何比例下精确铺满窗口宽 —— 桌面 Chrome Ctrl+减号 同源
                // 语义，无 letterbox 灰边；v2.14.7/8 的 body.zoom 方案
                // 因两侧灰边被用户否决）。100% 以上仍由
                // 原生捏合处理，默认排版与 v2.14.4 完全一致（零注入）
                val zoomLayout = ZoomPinchLayout(ctx)
                val wv = BrowserEngine.ensureWebView(ctx, tab, tabManager)
                zoomLayout.setWebView(wv, tab.viewZoom)
                // v2.15：注册为虚拟手柄输入目标（显示中的标签即玩家所在页面）
                com.linbox.core.input.gamepad.GamepadController.attachWebView(wv)
                // 缩放值写回标签（切标签恢复 / 导航重置的同步点）
                zoomLayout.onZoomChanged = { z -> tab.viewZoom = z }
                // v2.14.4：标记已被 AndroidView 认领（弹窗临时挂载场景下，
                // closeTab 据此判断 onRelease 会不会来，防泄漏/遮挡）
                tab.claimedByUi = true
                // 首次加载：推迟到 attach/layout 之后（view.post），
                // 避免 Chromium 以 0x0 surface 开始加载导致首帧黑屏
                if (!tab.hasLoadedOnce) {
                    val target = tab.url
                    if (target.isNotEmpty() && !target.startsWith("linbox://") && !target.startsWith("about:")) {
                        tab.hasLoadedOnce = true
                        tab.lastRequestedUrl = target
                        wv.post {
                            if (tab.webView === wv) {
                                wv.loadUrl(target)
                            }
                        }
                    }
                }
                zoomLayout
            },
            update = { zoomLayout ->
                // 重新绑定（tab.webView 在 onRelease 后可能被置空）。
                // setWebView 内部含换绑逻辑（旧容器剥离/重复绑定安全），
                // 并按 tab.viewZoom 恢复缩放（导航重置后同步回 100%）
                val webview = tab.webView
                if (webview != null) {
                    zoomLayout.setWebView(webview, tab.viewZoom)
                    zoomLayout.onZoomChanged = { z -> tab.viewZoom = z }
                    // v2.15：确保当前显示的 WebView 持有手柄输入目标
                    com.linbox.core.input.gamepad.GamepadController.attachWebView(webview)
                } else {
                    return@AndroidView
                }
                // UA 模式切换（当前激活标签实时生效）：设置后立即重载
                val desiredUa = BrowserEngine.desiredUa(uaMode)
                if (webview.settings.userAgentString != desiredUa) {
                    try {
                        webview.settings.userAgentString = desiredUa
                        webview.reload()
                    } catch (_: Exception) {
                    }
                }
                // 地址栏/首页/书签导航：目标 URL 变化且与最近请求不同时才加载，
                // 且同样通过 post{} 确保视图已 attach
                val target = tab.url
                if (target.isNotEmpty() && !target.startsWith("linbox://") && target != tab.lastRequestedUrl) {
                    errorMessage = null
                    tab.hasLoadedOnce = true
                    tab.lastRequestedUrl = target
                    webview.post {
                        if (tab.webView === webview) {
                            webview.loadUrl(target)
                        }
                    }
                }
            },
            onRelease = { zoomLayout ->
                // 解除容器对 WebView 的引用（WebView 生命周期由引擎管理）
                zoomLayout.detachWebView()
                // 虚拟手柄：解除输入目标注册（v2.15）
                com.linbox.core.input.gamepad.GamepadController.detachWebView(tab.webView)
                // 仅当标签被关闭时销毁 WebView；
                // 普通切换标签保留（缓存复用，切回即恢复原状态）
                // v2.15.3：closeTab/destroyAll 不再提前置空 tab.webView，
                // 此处真正读取引用执行销毁，销毁后置空防重复
                if (tab.destroyPending) {
                    tab.webView?.let {
                        BrowserEngine.destroyWebView(it)
                        tab.webView = null
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 虚拟手柄：把当前显示的 WebView 注册为输入目标（v2.15）
        // 兜底：factory/attach 时序之外，任重组时重读 tab.webView 作为 key 刷新注册
        LaunchedEffect(tab.id, tab.webView) {
            tab.webView?.let {
                com.linbox.core.input.gamepad.GamepadController.attachWebView(it)
            }
        }

        // v2.19：左缘悬浮滚动条（指针悬停显示，点击跳转 / 拖动滚动）
        BrowserScrollbarOverlay(tab = tab)

        // 加载进度条
        if (loadProgress in 1 until 100) {
            LinearProgressIndicator(
                progress = { loadProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .height(2.dp),
                color = theme.accentColor,
                trackColor = theme.accentColor.copy(alpha = 0.2f)
            )
        }

        // 错误提示层
        errorMessage?.let { msg ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("😕", fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "页面加载失败",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = msg,
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text("重试")
                }
            }
        }
    }
}

/**
 * 浏览器首页：快捷导航 + 搜索框 + 历史/书签入口
 */
@Composable
private fun BrowserHomePage(onNavigate: (String) -> Unit) {
    val theme = LocalWinTheme.current
    var searchText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))

        // Logo
        Text(
            text = "🌐 LinBox Browser",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(24.dp))

        // 搜索框
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(44.dp)
                .background(theme.buttonBackgroundColor, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = searchText,
                onValueChange = { searchText = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 14.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    val q = searchText.trim()
                    if (q.isNotEmpty()) {
                        // 直接把用户输入交给 onNavigate，由 normalizeUrl 判断：
                        // - 含 . 的视为网址 (例如 baidu.com → https://baidu.com)
                        // - 否则视为搜索关键词 (例如 你好 → Bing 搜索)
                        onNavigate(q)
                    }
                }),
                modifier = Modifier
                    .weight(1f)
                    .keyboardAware(
                        value = { searchText },
                        onValue = { searchText = it },
                        singleLine = true,
                        onEnter = {
                            val q = searchText.trim()
                            if (q.isNotEmpty()) onNavigate(q)
                        }
                    )
            )
            IconButton(onClick = {
                val q = searchText.trim()
                if (q.isNotEmpty()) {
                    // 与 IME 搜索回调保持一致：交给 onNavigate + normalizeUrl 判断
                    onNavigate(q)
                }
            }) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
        }

        Spacer(Modifier.height(32.dp))

        // 快捷导航
        val quickLinks = listOf(
            QuickLink("必应", "https://www.bing.com", "🔍"),
            QuickLink("百度", "https://www.baidu.com", "🅑"),
            QuickLink("B站", "https://www.bilibili.com", "📺"),
            QuickLink("GitHub", "https://github.com", "🐙"),
            QuickLink("知乎", "https://www.zhihu.com", "💡"),
            QuickLink("微博", "https://weibo.com", "🐦"),
            QuickLink("YouTube", "https://www.youtube.com", "▶️"),
            QuickLink("Wikipedia", "https://www.wikipedia.org", "📚")
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(quickLinks) { link ->
                QuickLinkItem(link = link, onClick = { onNavigate(link.url) })
            }
        }
    }
}

private data class QuickLink(val name: String, val url: String, val emoji: String)

@Composable
private fun QuickLinkItem(link: QuickLink, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
            .width(72.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(theme.accentColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(link.emoji, fontSize = 24.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = link.name,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

/**
 * 标签栏
 */
@Composable
private fun TabBar(
    tabs: List<BrowserTab>,
    activeTabId: String?,
    onTabClick: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit
) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(theme.windowTitleBarColor.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(tabs, key = { it.id }) { tab ->
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .background(
                            if (tab.id == activeTabId) theme.windowBackgroundColor
                            else theme.buttonBackgroundColor.copy(alpha = 0.5f),
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { onTabClick(tab.id) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tab.title.take(15),
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 11.sp,
                        modifier = Modifier.width(100.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = if (theme.isDark) Color.White else Color.Black,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onCloseTab(tab.id) }
                    )
                }
            }
        }
        IconButton(onClick = onNewTab, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = "New Tab",
                tint = if (theme.isDark) Color.White else Color.Black
            )
        }
    }
}

/**
 * 工具栏
 */
@Composable
private fun Toolbar(
    address: String,
    onAddressChange: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    onGo: () -> Unit,
    onBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    uaMode: String = "desktop",
    onToggleUaMode: () -> Unit = {},
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onOpenView3d: () -> Unit = {}
) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(theme.windowBackgroundColor)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        IconButton(onClick = onForward, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        IconButton(onClick = onHome, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Home, contentDescription = "Home",
                tint = if (theme.isDark) Color.White else Color.Black)
        }

        // 地址栏
        Box(
            modifier = Modifier
                .weight(1f)
                .height(30.dp)
                .background(theme.buttonBackgroundColor, RoundedCornerShape(15.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (address.isEmpty()) {
                Text(
                    "搜索或输入网址",
                    color = theme.buttonTextColor.copy(alpha = 0.4f),
                    fontSize = 12.sp
                )
            }
            BasicTextField(
                value = address,
                onValueChange = onAddressChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .keyboardAware(
                        value = { address },
                        onValue = onAddressChange,
                        singleLine = true,
                        onEnter = onGo
                    )
            )
        }

        // 显眼 Go/搜索按钮：带主题背景色，确保用户能发现"如何前往"
        Box(
            modifier = Modifier
                .height(30.dp)
                .background(theme.accentColor, RoundedCornerShape(15.dp))
                .clickable(onClick = onGo)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "前往",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "前往",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        IconButton(onClick = onBookmark, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.StarBorder, contentDescription = "Bookmark",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        IconButton(onClick = onShowBookmarks, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.BookmarkBorder, contentDescription = "Bookmarks",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        IconButton(onClick = onShowHistory, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.History, contentDescription = "History",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        // v2.16：浏览器设置（3D 视角旋转）
        IconButton(onClick = onOpenView3d, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default._3dRotation,
                contentDescription = "浏览器设置（3D 视角）",
                tint = if (theme.isDark) Color.White else Color.Black
            )
        }
        // 桌面/手机模式切换
        IconButton(onClick = onToggleUaMode, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (uaMode == "desktop") Icons.Default.Computer else Icons.Default.Smartphone,
                contentDescription = if (uaMode == "desktop") "桌面模式" else "手机模式",
                tint = if (theme.isDark) Color.White else Color.Black
            )
        }
        // 真全屏切换（F11 风格）：隐藏状态栏/导航键/任务栏/标签栏/工具栏，浏览器占满整屏；
        // v2.16：全屏中点左上角悬浮按钮或按返回键恢复窗口（双击退出已移除）
        IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                tint = if (theme.isDark) Color.White else Color.Black
            )
        }
    }
}

/**
 * 书签面板
 */
@Composable
private fun BookmarksPanel(onSelect: (String) -> Unit, onClose: () -> Unit) {
    val app = LinBoxApp.get()
    val bookmarks by app.database.bookmarkDao().observeAll().collectAsState(initial = emptyList())
    val theme = LocalWinTheme.current

    PopupPanel(title = "书签", onClose = onClose) {
        if (bookmarks.isEmpty()) {
            Text("暂无书签", color = if (theme.isDark) Color.White else Color.Black)
        } else {
            bookmarks.forEach { bm ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(bm.url) }
                        .padding(8.dp)
                ) {
                    Text("⭐", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(bm.title, color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, maxLines = 1)
                        Text(bm.url, color = theme.buttonTextColor.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryPanel(onSelect: (String) -> Unit, onClose: () -> Unit) {
    val app = LinBoxApp.get()
    val history by app.database.historyDao().observeRecent(50).collectAsState(initial = emptyList())
    val theme = LocalWinTheme.current

    PopupPanel(title = "历史记录", onClose = onClose) {
        if (history.isEmpty()) {
            Text("暂无历史", color = if (theme.isDark) Color.White else Color.Black)
        } else {
            history.forEach { h ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(h.url) }
                        .padding(8.dp)
                ) {
                    Text("🕘", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(h.title, color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, maxLines = 1)
                        Text(h.url, color = theme.buttonTextColor.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * v2.16.3：浏览器设置面板 —— 3D 视角旋转（鼠标视角模式，参照 GameBox 重做）。
 *
 * 开启后：先点击游戏画面（脚本已 hook requestPointerLock，点击即模拟
 * 锁定成功），再按住拖动即可旋转游戏视角 —— 拖动增量经 View3dController
 * 合成 mousemove(movementX/Y) 注入页面，由游戏自身完成相机/视角旋转
 * （上下拖 = 俯仰、左右拖 = 偏航）。页面不做任何视觉变换；触摸事件
 * 照常传递页面（tap/click 保留），页面滚动/拖选由注入 CSS 抑制。
 * 设置持久化到 DataStore。
 */
@Composable
private fun View3dSettingsPanel(
    enabled: Boolean,
    sensitivity: Float,
    onEnabled: (Boolean) -> Unit,
    onSensitivity: (Float) -> Unit,
    onClose: () -> Unit
) {
    val theme = LocalWinTheme.current
    val fg = if (theme.isDark) Color.White else Color.Black
    PopupPanel(title = "浏览器设置 · 3D 视角", onClose = onClose) {
        Text(
            "鼠标视角模式：开启后，先【点击一下游戏画面】，再按住拖动即可旋转游戏视角" +
                "（上/下 = 抬头低头，左/右 = 左右转向），用于电脑网页 FPS/3D 游戏。" +
                "点击用于锁定视角，拖动用于转动视角；拖动期间页面不会滚动，" +
                "浏览普通网页时可临时关闭本开关。",
            color = theme.secondaryTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用 3D 视角旋转（鼠标视角）", color = fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { onEnabled(it) })
        }
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        // 灵敏度：拖动像素增量的倍率（0.2x 慢微调 .. 3.0x 甩头快转）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "视角灵敏度",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "×" + String.format("%.1f", sensitivity),
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
        Slider(
            value = sensitivity,
            onValueChange = onSensitivity,
            valueRange = 0.2f..3f,
            enabled = enabled
        )
        Text(
            "提示：若视角不转，请先在游戏画面上点一下再拖动（游戏需要" +
                "\"锁定\"后才响应视角移动）；灵敏度可在 0.2×–3.0× 间调节；" +
                "双指捏合缩放在本模式下仍可用（先抬手再捏合）。",
            color = theme.secondaryTextColor,
            fontSize = 10.sp
        )
    }
}

/**
 * v2.16：真全屏时的悬浮退出按钮（左上角小圆钮，半透明不遮挡页面主体）。
 * 取代旧版"双击页面退出全屏"手势 —— 双击与网页游戏操作冲突。
 */
@Composable
private fun FullscreenExitButton(
    modifier: Modifier = Modifier,
    onExit: () -> Unit
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onExit),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.FullscreenExit,
            contentDescription = "退出全屏",
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun PopupPanel(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalWinTheme.current
    val scrollState = androidx.compose.foundation.rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(onClick = onClose)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
                .size(width = 320.dp, height = 360.dp)
                .background(theme.windowBackgroundColor, RoundedCornerShape(8.dp))
                .border(1.dp, theme.windowBorderColor, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close",
                            tint = if (theme.isDark) Color.White else Color.Black)
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    content = content
                )
            }
        }
    }
}

private fun normalizeUrl(input: String): String {
    if (input.isEmpty()) return ""
    if (input.startsWith("http://") || input.startsWith("https://") || input.startsWith("content://")) return input
    if (input.startsWith("linbox://")) return input
    if (input.contains(".") && !input.contains(" ")) {
        // v2.14.1：一律默认 https，保证加密浏览；
        // 内网地址（IP / localhost / .local / 带端口）若不支持 https，
        // 由 BrowserEngine 在 SSL 握手 / 连接失败时自动回退 http 重试一次
        // （兼容路由器、NAS 等纯 http 本地服务），不再默认 http。
        // 显式输入 http:// 前缀则原样尊重用户选择。
        return "https://$input"
    }
    return "https://www.bing.com/search?q=" + android.net.Uri.encode(input)
}
