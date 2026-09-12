package com.linbox.apps.browser

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 浏览器标签数据。
 *
 * v2.8 关键改动：每个标签持有【独占且常驻】的 WebView 实例。
 * 切换标签只是 attach / detach，不再销毁重建 —— 修复：
 * - 切标签卡顿（旧版每次切换都要重新创建 WebView = 重新拉起渲染进程）
 * - 切回原标签黑屏 / 重新加载（旧版 onRelease 直接 destroy，切回后全新 WebView
 *   在 factory 里同步 loadUrl，首帧竞态导致永不绘制）
 * - "Application attempted to call on a destroyed WebView"（旧版临时弹窗 WebView 5s 后销毁）
 */
class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    initialUrl: String,
    var title: String = "新标签",
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    /** 该标签独占的 WebView（由 [BrowserEngine] 创建，跨标签切换存活） */
    var webView: WebView? = null,
    /** 最近一次请求 WebView 加载的 URL（防止 update 块重复 loadUrl，跨重组存活） */
    var lastRequestedUrl: String? = null,
    /** 是否已发起过首次加载 */
    var hasLoadedOnce: Boolean = false,
    /** 关闭标记：AndroidView onRelease 时据此判断是"关闭标签"还是"仅切换走" */
    var destroyPending: Boolean = false,
    /** 是否为 window.open / target=_blank 弹出的标签 */
    var isPopup: Boolean = false,

    /**
     * v2.14.4：WebView 是否已被 AndroidView factory 认领（至少上屏过一次）。
     * 弹窗 WebView 在 onCreateWindow 时会临时挂到 opener 的容器里保证
     * 立即可见（parent 非空），但若从未被 Compose 认领就被 closeTab，
     * onRelease 永远不会触发 → 泄漏且遮挡 opener。用此标记区分：
     * 未认领的 WebView 即使 parent 非空也直接剥离销毁。
     */
    var claimedByUi: Boolean = false,
    /** 抑制下一次导航的历史记录（后退/前进/刷新不写入历史） */
    var suppressNextHistory: Boolean = false,
    /**
     * v2.14.1：已自动回退过 http 的 https 地址（防回退循环）。
     * 内网地址 https 失败 → 自动改 http 重试一次并记录；同一地址被
     * 重新导航（重试/地址栏回车）时在 onPageStarted 重置，给再次机会。
     */
    var httpsFallbackUrl: String? = null,
    /**
     * v2.14.7：双指缩小域（0.3~1.0）当前比例，1f=默认 100%。
     * 缩小域由 ZoomPinchLayout 在【捏合缩小期间】动态改写 viewport meta
     * 实现（v2.14.9）：width=原排版宽/z + minimum=maximum=接管时 scale×z，
     * 排版视口按 1/z 变宽、页面 scale 钳到"窗口宽/画布宽" —— 整页等比
     * 变小且任何比例下精确铺满窗口宽（无 letterbox；v2.14.7/8 的
     * body.zoom 方案因两侧灰白边被用户否决）。innerWidth/innerHeight
     * 原生变宽，H5 游戏 resize 监听自动重排画布；fixed 全屏层自然跟随。
     * 按标签存放（切标签回来恢复，快照 __az 随页面存续），onPageStarted
     * 导航时重置回 1f（新文档天然无注入；锚点导航由 RESET_SCRIPT
     * 还原原 meta）。普通字段即可：不驱动 Compose 重组，只由手势层读写。
     */
    var viewZoom: Float = 1f
) {
    /**
     * 当前页面 URL（v2.16.2：改为快照状态 mutableStateOf）。
     *
     * 修复"地址栏输入带 scheme 的 URL（addressInput 不变 → 无重组）点前往
     * 无反应"：旧版 url 是普通字段，写入不触发重组，内容区"首页/网页"
     * 分支与 WebViewContainer 的 update 块都感知不到变化。可观察后：
     * 首页 → 网页的内容区分支能立即切换，WebViewContainer 重组也能
     * 触发 update 块的 loadUrl。注意：委托属性必须在类体（构造参数
     * 不支持 by 委托）。
     */
    var url by mutableStateOf(initialUrl)

    /**
     * v2.14.3：WebView 重建纪元（渲染进程崩溃 onRenderProcessGone 时 +1）。
     * 作为 key() 的一部分：变化 → AndroidView 重组 → factory 重建新
     * WebView 并自动重载 tab.url。快照状态存放，保证 key 能感知变化。
     */
    var renderEpoch by mutableStateOf(0)
}

/**
 * 当前正在显示的标签的 UI 回调。
 * WebView 的 client 是【一次性创建、跨重组存活】的，不能直接捕获 Compose 状态，
 * 因此通过该接口路由到当前组合中的 WebViewContainer。
 */
interface TabUiCallbacks {
    /** 用户主动导航（点击链接/地址栏）→ 同步地址栏 */
    fun onUrlChanged(url: String)

    /** 内部导航（后退/前进/弹窗首跳）→ 仅同步地址栏 */
    fun onUrlSync(url: String)

    fun onTitleChanged(title: String)

    fun onProgressChanged(progress: Int)

    /** null 表示清除错误提示 */
    fun onError(message: String?)
}

/**
 * 标签管理器 —— 同时承载浏览器会话状态（标签列表 / 激活标签 / 地址栏 / 视频全屏视图）。
 *
 * 状态全部用 compose 快照状态存放，会话对象由 [BrowserSessions] 按窗口 id 缓存：
 * 窗口最小化（临时离开组合）不丢标签；窗口真正关闭时才销毁全部 WebView。
 */
class TabManager {

    private val _tabs = mutableStateListOf<BrowserTab>()
    val tabs: List<BrowserTab> get() = _tabs

    /** 当前激活标签 id */
    var activeTabId by mutableStateOf<String?>(null)

    /** 地址栏内容 */
    var addressInput by mutableStateOf("")

    /** HTML5 视频全屏视图（onShowCustomView 注入，非空表示正在全屏播放） */
    var customView by mutableStateOf<View?>(null)
    var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** 当前激活标签的 WebViewContainer 注册的 UI 回调（带属主令牌防串扰） */
    internal var ui: TabUiCallbacks? = null
    internal var uiOwner: Any? = null

    /** 当前 UA 模式（"desktop" / "mobile"），由 BrowserContent 同步，供弹窗 WebView 创建时使用 */
    internal var uaMode: String = "desktop"

    fun getTab(id: String?): BrowserTab? = _tabs.firstOrNull { it.id == id }

    fun activeTab(): BrowserTab? = getTab(activeTabId)

    /**
     * 新建标签并激活。
     *
     * @param existingWebView 非空表示这是 onCreateWindow 弹出的真实 WebView：
     *   导航由 Chromium 直接在其中进行（POST 表单 / JS 重定向全兼容），
     *   这里只登记元数据，不做首载。
     */
    fun openTab(initialUrl: String, existingWebView: WebView? = null): BrowserTab {
        val tab = BrowserTab(
            initialUrl = initialUrl,
            title = when {
                initialUrl == "linbox://home" || initialUrl.isEmpty() -> "新标签"
                initialUrl == "about:blank" -> "新标签"
                else -> initialUrl
            },
            webView = existingWebView
        )
        if (existingWebView != null) {
            tab.isPopup = true
            // Chromium 即将在此 WebView 中导航，禁止 factory/update 再次 loadUrl
            tab.hasLoadedOnce = true
            tab.lastRequestedUrl = "about:blank"
        }
        _tabs.add(tab)
        activeTabId = tab.id
        addressInput = if (initialUrl == "linbox://home" || initialUrl.isEmpty() || initialUrl == "about:blank") "" else initialUrl
        return tab
    }

    /** 切换到指定标签 */
    fun switchTo(id: String) {
        val tab = getTab(id) ?: return
        activeTabId = id
        addressInput = when {
            tab.url.isEmpty() || tab.url == "linbox://home" -> ""
            tab.url == "about:blank" -> ""
            else -> tab.url
        }
    }

    /**
     * 关闭标签并销毁其 WebView。
     *
     * - 后台标签（WebView 已 detach）：立即销毁。
     * - 当前显示的标签（WebView 仍 attached）：标记 destroyPending，
     *   重组移除 AndroidView 时由 onRelease 统一销毁（避免对 attached WebView
     *   调 destroy 引发 "Application attempted to call on a destroyed WebView"）。
     *
     * v2.14.10 关键修复（"关了标签声音还在"）：
     * - attached 场景【不再提前置空 tab.webView】—— 旧版先置空再等 onRelease，
     *   而 onRelease 里 `tab.webView?.let { destroyWebView }` 读到 null 直接跳过，
     *   该 WebView 永远不会被销毁，渲染进程与 HTML5 音频无限存活。
     *   现在保留引用给 onRelease 销毁，并加 2s 兜底定时器（onRelease 因
     *   极端重组时序未触发的保险；destroyWebView 幂等，双销毁安全）。
     */
    fun closeTab(id: String) {
        val tab = getTab(id) ?: return
        tab.destroyPending = true
        _tabs.remove(tab)
        val wv = tab.webView
        if (wv != null && (wv.parent == null || !tab.claimedByUi)) {
            // 已脱离视图树（后台标签）/ 从未上屏的弹窗 → 直接销毁
            tab.webView = null
            BrowserEngine.destroyWebView(wv)
        } else if (wv != null) {
            // attached 且已被 UI 认领 → onRelease 销毁；2s 兜底防漏
            Handler(Looper.getMainLooper()).postDelayed({
                tab.webView?.let { BrowserEngine.destroyWebView(it) }
                tab.webView = null
            }, 2000)
        }
        if (activeTabId == id) {
            val next = _tabs.lastOrNull() ?: _tabs.firstOrNull()
            activeTabId = next?.id
            addressInput = when (next?.url) {
                null, "", "linbox://home", "about:blank" -> ""
                else -> next.url
            }
        }
        // 若该标签正在视频全屏，同步收起覆盖层
        hideCustomView()
    }

    /**
     * 销毁全部标签与 WebView（浏览器窗口关闭时调用）。
     *
     * v2.14.10：attached 的 WebView【保留 tab.webView 引用】交给 onRelease
     * 立即销毁（旧版提前置空导致 onRelease 拿到 null 只能等 1.5s 兜底，
     * 关窗后音频还会响一阵）；1.5s 兜底清理仍保留（幂等，双销毁安全）。
     */
    fun destroyAll() {
        hideCustomView()
        val all = _tabs.toList()
        _tabs.clear()
        activeTabId = null
        ui = null
        uiOwner = null
        val pendingDestroy = mutableListOf<WebView>()
        all.forEach { tab ->
            tab.destroyPending = true
            val wv = tab.webView
            if (wv == null || wv.parent != null) {
                // attached → 保留引用，onRelease 读取并销毁
                if (wv != null) pendingDestroy.add(wv)
            } else {
                // 后台标签（已脱离视图树）→ 立即销毁
                tab.webView = null
                BrowserEngine.destroyWebView(wv)
            }
        }
        if (pendingDestroy.isNotEmpty()) {
            Handler(Looper.getMainLooper()).postDelayed({
                pendingDestroy.forEach { BrowserEngine.destroyWebView(it) }
            }, 1500)
        }
    }

    // ===== HTML5 视频全屏（onShowCustomView / onHideCustomView）=====

    /**
     * 显示视频全屏：把自定义视图挂到 Activity DecorView 最顶层，
     * 盖过应用任务栏与所有窗口 → 真正的满屏播放（v2.8 修复）。
     */
    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback?) {
        val activity = com.linbox.util.ImmersiveMode.findActivity(view.context)
        if (activity == null) {
            callback?.onCustomViewHidden()
            return
        }
        // 已有全屏视图时先收起（站点连续切换全屏）
        if (customView != null) hideCustomView()
        customView = view
        customViewCallback = callback
        val decor = activity.window.decorView as? ViewGroup
        if (decor == null) {
            customView = null
            customViewCallback = null
            callback?.onCustomViewHidden()
            return
        }
        decor.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // 隐藏系统状态栏 + 导航键，真满屏
        com.linbox.util.ImmersiveMode.applyTo(activity)
    }

    /** 退出视频全屏：移除覆盖层并通知页面 */
    fun hideCustomView() {
        val v = customView ?: return
        customView = null
        (v.parent as? ViewGroup)?.removeView(v)
        val cb = customViewCallback
        customViewCallback = null
        try {
            cb?.onCustomViewHidden()
        } catch (_: Exception) {
        }
    }
}

/**
 * 浏览器会话注册表：以窗口 id 缓存 TabManager。
 *
 * 修复：旧版 TabManager 用 remember{} 挂在组合里，窗口最小化（WindowState.isVisible
 * 为 false 时 WindowHost 不再组合该窗口）会直接丢掉全部标签。
 * 现在会话常驻注册表，最小化后恢复窗口标签原样还原；窗口真正关闭时销毁。
 */
object BrowserSessions {
    private val sessions = ConcurrentHashMap<String, TabManager>()

    fun getOrCreate(windowId: String): TabManager =
        sessions.getOrPut(windowId) { TabManager() }

    fun destroy(windowId: String) {
        sessions.remove(windowId)?.destroyAll()
    }
}

/**
 * WebView 操作扩展：通过 command API 触发。
 *
 * v2.16.2：导航改为【立即加载】——旧版只更新 tab.url 等 WebViewContainer
 * 的 update 块重组触发，但地址栏输入"带 scheme 的完整 URL / 与上次相同
 * 的 URL"时 addressInput 状态不变 → 可能无重组 → 点前往无反应。
 * 现在已有 WebView 时直接 loadUrl 并登记 lastRequestedUrl（防 update 块
 * 重复加载）；无 WebView（如仍在首页）时只登记 url，由 factory 首载 /
 * 内容区分支切换（url 已可观察）完成。
 */
fun BrowserTab.loadUrl(url: String) {
    this.url = url
    this.title = url
    val wv = webView
    if (wv != null && !url.startsWith("linbox://")) {
        hasLoadedOnce = true
        lastRequestedUrl = url
        runCatching { wv.loadUrl(url) }
    }
}

fun BrowserTab.goBack() {
    webView?.let {
        if (it.canGoBack()) {
            // 后退不写入历史记录
            suppressNextHistory = true
            it.goBack()
        }
    }
}

fun BrowserTab.goForward() {
    webView?.let {
        if (it.canGoForward()) {
            // 前进不写入历史记录
            suppressNextHistory = true
            it.goForward()
        }
    }
}

fun BrowserTab.refresh() {
    webView?.let {
        // 刷新不写入历史记录
        suppressNextHistory = true
        it.reload()
    }
}
