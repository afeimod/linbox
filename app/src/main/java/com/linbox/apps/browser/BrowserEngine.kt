package com.linbox.apps.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Environment
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.linbox.LinBoxApp
import com.linbox.data.db.entity.HistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 浏览器内核引擎：WebView 的创建 / 配置 / 销毁，以及 WebViewClient、WebChromeClient。
 *
 * v2.8 重构要点：
 * 1. 移除 setLayerType(LAYER_TYPE_HARDWARE) —— WebView 默认已硬件加速，
 *    强制离屏硬件层在 MIUI / 部分 GPU 驱动上会导致"页面加载完成但首帧黑屏"。
 *    （录屏证实：DOM 已加载、标题已更新，但 WebView 永不绘制。）
 * 2. onCreateWindow 不再用"临时 WebView + 5 秒销毁"拦截 —— 那样：
 *    - 表单 POST / target=_blank 提交（百度"百度一下"）不回调
 *      shouldOverrideUrlLoading，导致点击完全没有反应；
 *    - 销毁临时 WebView 触发 cr_AwContents "Application attempted to call on
 *      a destroyed WebView"。
 *    现在：弹出的 WebView 直接作为新标签的【真实 WebView】通过 transport 交给
 *    Chromium，导航（GET/POST/JS 重定向）在真实标签中完成，全场景兼容。
 *
 * v2.14.2 WebView 优化（完整移植 afeimod/gamehtml 容器方案，移除 v2.14 自研
 * 灰屏像素检测 + 软件渲染切换 hack —— 灰屏是 WebView 自身渲染管线的时序问题，
 * gamehtml 的解法是强制重绘管线而非切换渲染器）：
 * 1. 强制重绘：onPageCommitVisible/onPageFinished invalidate + requestLayout +
 *    延迟 300ms 二次重绘 + document.body.offsetHeight 强制布局 —— 解决
 *    "页面加载完成（有声音/标题）但 Surface 不上屏"的灰屏问题。
 * 2. View Transitions API 补丁：WebView 对 startViewTransition 支持不完善，
 *    SPA 站点会出现回调不执行/页面卡住，polyfill 确保回调同步执行。
 * 3. Flash 游戏兼容（4399 等）：伪造 navigator.plugins 让页面创建 Flash 元素 +
 *    Ruffle polyfill（jsdelivr CDN）替换 <object>/<embed> 为 Canvas 播放器 +
 *    SWF 请求原生拦截（CORS 头 + Cookie 转发 + 防盗链 Referer）。
 * 4. PC 页面 viewport 自适配（无 viewport meta 的页面按 1200px 基准缩放）。
 * 5. 禁用 WebView 算法暗色（MIUI 强制深色不介入网页渲染）。
 *
 * v2.14.3 灰屏根因修复（弹窗时序 + 渲染层 + 渲染进程兜底，录屏/日志定位）：
 * 用户录屏：4399 首页正常，点击游戏（window.open 弹窗）瞬间整页浅灰，
 * 有声音无画面、无加载圈，持续 8s+ 无任何重绘。日志：弹窗 WebView
 * 以 0,0-0,0 创建（脱离视图树加载），被销毁时 1080x868 且 invalidated+dirty
 * ——帧在产、永不上屏，合成器/图层层面卡死。
 * 1. attach 后强制重绘梯子（本次核心）：onCreateWindow 的弹窗 WebView 经
 *    transport 交接后 Chromium 【立即在脱离视图树状态开始加载】，
 *    onPageCommitVisible/onPageFinished 全部在 attach 前触发完（invalidate
 *    对未 attach 视图无效），attach 后无人再触发重绘 → 永久灰屏。gamehtml
 *    是 Activity 根视图（setContentView 先 attach 后 loadUrl）且
 *    setSupportMultipleWindows(false) 无弹窗，根本不存在该时序；这里用
 *    addOnAttachStateChangeListener 在 attach 时刻补齐同样的重绘管线。
 *    切换标签/窗口最小化恢复的再 attach 同样被覆盖。
 * 2. 撤销 v2.14.2 照搬的 setLayerType(LAYER_TYPE_HARDWARE)：官方文档明确
 *    WebView 不支持强制硬件层（离屏缓冲由内核自管）；gamehtml 用在 Activity
 *    根视图上能活，但 LinBox 的 WebView 嵌在 Compose AndroidView 多窗口环境，
 *    v2.8 已用录屏证实强制硬件层导致"页面加载完成但永不绘制"。恢复默认
 *    LAYER_TYPE_NONE + 窗口级硬件加速（Manifest hardwareAccelerated=true）。
 * 3. onRenderProcessGone 兜底（WebView 自身问题）：不接管会被系统杀进程。
 *    渲染进程崩溃/OOM 后丢弃死亡 WebView，标签重置未加载态并 renderEpoch+1
 *    触发 key() 重组重建新 WebView 自动重载页面。
 *
 * v2.14.4 灰屏第二根因修复（网络层证据：ssl_client_socket_impl handshake
 * failed net_error -100 + miui_contents loadinfo script_count=13/
 * resource_count=0 + 音频进程活着）—— 页面主文档到达、JS 在跑、音频在播，
 * 但 CDN 上的引擎/子资源 TLS 被掐断（国内网络对 jsdelivr/unpkg 的典型干扰）：
 * 1. Ruffle 引擎全量本地化（本次核心）：5 个文件打包进 assets，经虚拟域
 *    https://linbox.local/ruffle/ 由 shouldInterceptRequest 供给，零 CDN 依赖。
 *    旧版从 jsdelivr 动态加载 —— TLS 被 RST 时 Flash 游戏区永远空白（灰屏
 *    有声音），加载器链上再叠 npmmirror/jsdelivr 两级网络后备。
 * 2. 弹窗 WebView 立即挂载（补齐 v2.14.3 梯子的源头缺口）：onCreateWindow
 *    在 transport 交接【前】就把弹窗 WebView 临时挂到 opener 的视图容器并
 *    手动 measure/layout 成 opener 尺寸 —— Chromium 交接后立即在真实 surface
 *    上开始加载（不再是 0,0-0,0 脱离视图树状态），首帧生产不再落空；Compose
 *    激活新标签时 factory 剥离临时父容器认领。AndroidViewHolder 只测量/布局
 *    自己的 typedView，第二个子 View 必须手动 layout。
 * 3. WebGL 像素管线守卫（HTML5 游戏灰屏兜底）：document-start 探测
 *    clear→readPixels 是否真的产出像素；损坏（上下文创建成功但像素读回全零，
 *    嵌入式合成环境的典型症状：游戏跑着、有声音、画面灰）时让
 *    getContext('webgl') 返回 null —— Ruffle 等引擎自动回退 canvas2d 渲染，
 *    页面自身也有 2D 回退路径，不再卡死在坏掉的 GPU 合成上。
 */
object BrowserEngine {

    /** 桌面版 Chrome UA（不含 Mobile/Android，让服务器返回 PC 版页面） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 系统默认（移动版）UA —— 首次创建 WebView 时捕获，供手机模式恢复 */
    @Volatile
    private var defaultMobileUa: String? = null

    private const val TAG_DESTROYED = "linbox_webview_destroyed"

    fun desiredUa(uaMode: String): String =
        if (uaMode == "desktop") DESKTOP_UA else (defaultMobileUa ?: DESKTOP_UA)

    /**
     * 获取（必要时创建）标签专属 WebView。
     *
     * - 已有缓存：直接复用（切换标签回来时保留页面状态/滚动位置/视频进度），
     *   并静默同步 UA 设置。
     * - 没有缓存：完整配置并绑定 client。
     */
    fun ensureWebView(context: Context, tab: BrowserTab, manager: TabManager): WebView {
        val existing = tab.webView
        if (existing != null) {
            // v2.14.4：弹窗 WebView 在 onCreateWindow 时被临时挂在 opener 的
            // 视图容器里（保证 transport 交接后立即在真实 surface 上加载，
            // 不再以 0,0-0,0 脱离视图树状态加载导致首帧生产落空）。
            // AndroidView 认领前必须剥离旧父容器，否则 holder.addView 抛
            // "The specified child already has a parent"。
            (existing.parent as? ViewGroup)?.removeView(existing)
            applyUa(existing, manager.uaMode)
            return existing
        }
        val wv = ExposedWebView(context)
        if (defaultMobileUa == null) {
            defaultMobileUa = wv.settings.userAgentString
        }
        configure(wv, tab, manager)
        tab.webView = wv
        return wv
    }

    private fun applyUa(wv: WebView, uaMode: String) {
        val desired = desiredUa(uaMode)
        if (wv.settings.userAgentString != desired) {
            try {
                wv.settings.userAgentString = desired
            } catch (_: Exception) {
            }
        }
    }

    private fun configure(wv: WebView, tab: BrowserTab, manager: TabManager) {
        val ctx = wv.context
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // 多窗口模式：window.open / target=_blank → onCreateWindow
            // （弹窗作为真实新标签处理，见 TabChromeClient.onCreateWindow）
            setSupportMultipleWindows(true)
            defaultTextEncodingName = "UTF-8"
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            setGeolocationEnabled(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            textZoom = 100
            userAgentString = desiredUa(manager.uaMode)
        }

        // v2.14.3：撤销 v2.14.2 照搬的 setLayerType(LAYER_TYPE_HARDWARE)。
        // Android 官方文档明确 WebView 不支持 LAYER_TYPE_HARDWARE（内部离屏
        // 缓冲由 Chromium 自管）；gamehtml 用在 Activity 根视图上能工作，但
        // LinBox 的 WebView 嵌在 Compose AndroidView 多窗口环境里，v2.8 已用
        // 录屏证实强制硬件层会导致“页面加载完成但永不绘制”。本次灰屏日志中
        // 被销毁的弹窗 WebView 连续 4s 处于 invalidated+dirty 状态——帧在产、
        // 永不上屏，正是离屏层合成失效的表征。默认 LAYER_TYPE_NONE + 窗口级
        // 硬件加速（Manifest hardwareAccelerated=true）才是 WebView 的正确配置。

        // v2.14.3（核心修复）：attach 后强制重绘梯子。
        // 弹窗 WebView 经 onCreateWindow transport 交接后，Chromium 立即在
        // 【脱离视图树】状态开始加载（日志佐证：创建时 0,0-0,0）——
        // onPageCommitVisible/onPageFinished 的强制重绘全部在 attach 前触发完，
        // 对未 attach 的视图 invalidate 无效；等下一帧 Compose 把它上屏后，
        // 再没有任何代码路径触发重绘 → 首帧永不上屏（灰屏、有声音无画面）。
        // gamehtml 是 Activity 根容器（setContentView 先 attach、后 loadUrl），
        // 页面事件都落在 attached 状态，所以它的重绘管线在那边有效；这里在
        // attach 时刻补齐同一套管线：立即 + 200ms + 600ms 三级梯子，兼容
        // 注入脚本（Ruffle 等）执行晚于首帧的情况。切换标签/窗口最小化恢复的
        // 再 attach 也一并覆盖。
        wv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post {
                    if (v.tag != TAG_DESTROYED) {
                        v.requestLayout()
                        v.invalidate()
                    }
                }
                v.postDelayed({
                    if (v.tag != TAG_DESTROYED) v.invalidate()
                }, 200)
                v.postDelayed({
                    if (v.tag != TAG_DESTROYED) v.invalidate()
                }, 600)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })

        // v2.14.2：禁用算法暗色 —— MIUI 强制深色会介入 WebView 渲染管线，
        // 配合 GPU 合成问题放大灰屏概率（灰屏日志出现 ForceDarkHelperStubImpl 初始化）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { wv.settings.isAlgorithmicDarkeningAllowed = false }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            runCatching { wv.settings.forceDark = WebSettings.FORCE_DARK_OFF }
        }

        // v2.19：渲染进程优先级拉满 —— 多窗口/多标签场景下 Chromium 可能
        // 因可见性判定降级渲染优先级导致前台滚动卡顿；deferred=false 保持
        // 常驻优先级（配合 largeHeap，多标签内存压力可控）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            runCatching {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
        }

        // 启用 WebView 内部数据库 (WebStorage) 自动管理
        WebStorage.getInstance()
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)
        cookieManager.flush()

        // 文件下载监听（很多站点提供 APK/ZIP/图片下载）
        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        contentDisposition?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                            ?.substringAfter("filename=", "linbox_download")
                            ?.trim('"') ?: "linbox_download"
                    )
                    setTitle(contentDisposition ?: "下载")
                    if (mimetype == "application/vnd.android.package-archive") {
                        allowScanningByMediaScanner()
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    } else {
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    }
                }
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(ctx, "开始下载到 Downloads/", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        wv.webViewClient = TabWebViewClient(ctx, tab, manager)
        wv.webChromeClient = TabChromeClient(ctx, tab, manager)

        // v2.16.3：鼠标视角（3D 视角旋转）JS 桥 —— 页面脚本经
        // window.__linboxLookBridge.pull() 每帧取走拖动增量并派发
        // 合成 mousemove(movementX/Y) 事件（详见 View3dController，
        // 参照 GameBox：模拟 Pointer Lock + __cameraRotate 派发）。
        // 桥只暴露只读 pull，无安全面。
        runCatching { wv.addJavascriptInterface(View3dController.bridge, "__linboxLookBridge") }
    }

    /**
     * 安全销毁 WebView（幂等）：
     * JS 暂停全部媒体 → onPause 挂起渲染 → stopLoading 中止加载 →
     * 脱离视图树 → destroy 释放原生资源。
     *
     * v2.14.10：destroyWebView 曾在部分设备上"关了还在响" —— 一方面调用方
     * 提前置空 tab.webView 导致销毁链断掉（见 TabManager 修复），另一方面
     * 部分 WebView 版本 onPause() 不会暂停 HTML5 媒体、destroy() 异步生效
     * 期间音频继续。现在销毁前先注入 JS 主动 pause 页面上所有
     * audio/video 元素并取消语音合成，保证立刻静音。
     */
    fun destroyWebView(wv: WebView) {
        if (wv.tag == TAG_DESTROYED) return
        try {
            wv.tag = TAG_DESTROYED
        } catch (_: Exception) {
        }
        // 1) 立即静音：JS 主动暂停所有媒体（部分版本 onPause 不暂停媒体）
        try {
            wv.evaluateJavascript(
                "(function(){try{var m=document.querySelectorAll('audio,video');" +
                    "for(var i=0;i<m.length;i++){try{m[i].pause();m[i].muted=true;}catch(e){}}" +
                    "if(window.speechSynthesis){try{window.speechSynthesis.cancel();}catch(e){}}" +
                    "try{if(document.body&&document.body.pause)document.body.pause();}catch(e){}}catch(e){}})()",
                null
            )
        } catch (_: Exception) {
        }
        // 2) 挂起 WebView 的额外处理（JS 定时器/布局/媒体管线）
        try {
            wv.onPause()
        } catch (_: Exception) {
        }
        try {
            wv.stopLoading()
        } catch (_: Exception) {
        }
        // 3) 脱离视图树并销毁原生资源
        (wv.parent as? ViewGroup)?.removeView(wv)
        try {
            wv.removeAllViews()
        } catch (_: Exception) {
        }
        try {
            wv.destroy()
        } catch (_: Exception) {
            // 某些设备上 destroy() 可能抛 IllegalStateException，吞掉即可
        }
    }

    /** 记录历史（Room）。所有主导航统一在 onPageStarted 记录，天然去重于 update 块。 */
    internal fun recordHistory(url: String?) {
        if (url.isNullOrEmpty() || url == "about:blank" || url.startsWith("data:")) return
        engineScope.launch {
            try {
                LinBoxApp.get().database.historyDao()
                    .insert(HistoryEntity(title = url, url = url))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * v2.14.1：是否为内网/本地地址（IP / localhost / *.local / 带端口）。
     * 这类地址（路由器、NAS、本地开发服务）大多只有 http 或自签证书，
     * 是唯一允许 https→http 自动回退的范围；公网域名绝不回退（安全底线）。
     */
    internal fun isLanishUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val bare = url.removePrefix("https://").substringBefore("/").substringBefore("?")
        val host = bare.substringBefore(":")
        val hasPort = bare.contains(":")
        return Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$").matches(host) ||
            host == "localhost" || host.endsWith(".local") || hasPort
    }

    // ====================================================================
    // WebViewClient：每个标签一次性创建，通过 manager 路由 UI 回调
    // ====================================================================
    private class TabWebViewClient(
        private val ctx: Context,
        private val tab: BrowserTab,
        private val manager: TabManager
    ) : WebViewClient() {

        private fun isActive() = manager.activeTabId == tab.id

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val newUrl = request.url.toString()
            // 处理特殊 scheme：intent://, weixin://, alipays://, mailto: 等
            val scheme = request.url.scheme ?: "http"
            if (scheme !in setOf("http", "https", "content", "file", "about", "javascript", "data")) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(ctx, "未安装可打开 $scheme 应用的程序", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            // v2.14.2（gamehtml）：直接导航到 .swf 文件 → 不当页面加载，
            // 用 Ruffle 全屏播放器接管
            if (newUrl.endsWith(".swf", ignoreCase = true)) {
                playSwfFullscreen(view, newUrl)
                return true
            }
            // 在当前 WebView 内继续加载：登记 lastRequestedUrl 防 update 块重复加载
            tab.lastRequestedUrl = newUrl
            tab.url = newUrl
            if (isActive()) {
                manager.ui?.onUrlChanged(newUrl)
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != null) {
                // v2.14.1：同一 https 地址被重新导航（重试/地址栏回车/书签重开）时，
                // 重置自动回退标记，允许再次尝试 https 而非直接报错
                if (url == tab.httpsFallbackUrl) tab.httpsFallbackUrl = null
                // 登记 lastRequestedUrl：页面内部导航（含弹窗首跳）后
                // update 块不会重复 loadUrl 把页面拉回旧地址
                tab.lastRequestedUrl = url
                if (url != tab.url) {
                    tab.url = url
                    if (isActive()) {
                        manager.ui?.onUrlSync(url)
                    }
                }
                // 后退/前进/刷新不写入历史
                val suppress = tab.suppressNextHistory
                tab.suppressNextHistory = false
                if (!suppress) {
                    recordHistory(url)
                }
            }
            view?.let {
                tab.canGoBack = it.canGoBack()
                tab.canGoForward = it.canGoForward()
            }
            if (isActive()) {
                manager.ui?.onError(null)
            }

            // ===== v2.14.2（gamehtml）页面级注入：在页面自身 JS 之前执行 =====
            if (url != null && (url.startsWith("http") || url.startsWith("file:"))) {
                // 0) v2.14.4 WebGL 像素管线守卫：检测到坏上下文时让 webgl
                //    getContext 返回 null，Ruffle/HTML5 游戏自动回退 canvas2d
                view?.evaluateJavascript(WEBGL_GUARD_SCRIPT, null)
                // 1) View Transitions API 补丁（所有页面通用，防 SPA 跳转卡死）
                view?.evaluateJavascript(VIEW_TRANSITION_PATCH_SCRIPT, null)
                // 2) PC 页面 viewport 自适配（页面自带 viewport 则不动）。
                //    v2.14.6：恢复 v2.14.4 原版默认排版（用户要求“默认缩放
                //    还用之前的”）。缩小到 30% 的能力改由 ZoomPinchLayout
                //    在【捏合缩小期间】动态改写 viewport meta 实现
                //    （v2.14.9：width=原宽/z + 钳制 scale，页面始终精确
                //    铺满窗口宽）；默认态本脚本不改画布宽、不改默认缩放
                view?.evaluateJavascript(VIEWPORT_FIT_SCRIPT, null)
                // 3) Flash 兼容：伪造插件让 4399 等页面创建 <object> Flash 元素（零网络开销）
                view?.evaluateJavascript(FLASH_FAKE_SUPPORT_SCRIPT, null)
                //    懒加载探测器：任意页面出现 Flash 元素时动态加载 Ruffle 引擎
                view?.evaluateJavascript(FLASH_DOM_DETECT_SCRIPT, null)
                // 4) 4399 系页面：直接预加载 Ruffle（游戏页主体就是 Flash）
                if (url.contains("4399.com")) {
                    view?.evaluateJavascript(REFERER_SPOOF_SCRIPT, null)
                    view?.evaluateJavascript(RUFFLE_LOADER_SCRIPT, null)
                }
            }

            // v2.14.7：导航重置缩小域缩放（30%~100%）—— 每次新页面默认回到
            // 100% 排版（v2.14.5 教训：默认视觉必须与旧版一致）。新文档天然
            // 无注入；同文档锚点导航（onPageStarted 亦触发）由 RESET_SCRIPT
            // 按快照还原原 viewport meta（v2.14.9：缩小域改为动态改写
            // meta width=原宽/z + 钳制 scale，页面始终精确铺满窗口宽，
            // 无 letterbox）。View 变换方案（v2.14.6）已被实测
            // 否决：内容不随变换缩放、仅被缩小后的边界裁切
            if (tab.viewZoom != 1f) {
                tab.viewZoom = 1f
                view?.evaluateJavascript(ZoomPinchLayout.RESET_SCRIPT, null)
            }
        }

        /**
         * v2.14.2（gamehtml）：首次可见帧强制重绘。
         * 部分设备上首帧提交后合成器未拉起新帧，invalidate 确保当前帧上屏。
         * v2.14.5 曾在此注入 viewport 缩放脚本（宽画布方案），v2.14.6 撤销
         * —— 该方案破坏无 viewport PC 页的默认排版；v2.14.6 的 View 变换
         * 缩放亦被 v2.14.7 实测否决（内容不跟随、仅被边界裁切），缩小能力
         * 现由 ZoomPinchLayout 在捏合期间动态改写 viewport meta 实现
         * （v2.14.9），默认态与页面渲染管线无关。
         */
        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            view?.invalidate()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.let {
                tab.canGoBack = it.canGoBack()
                tab.canGoForward = it.canGoForward()
            }

            // ===== v2.16.3 鼠标视角（3D 视角旋转）：onPageFinished 注入 =====
            // 参照 GameBox（GAMEBOX：onPageFinished 注入 CAMERA_ROTATION_SCRIPT，
            // 注释“导航后会丢失，需重新注入”）：onPageStarted 时 DOM 尚未就绪，
            // CSS/element 查询会失败；onPageFinished 时 document.head/canvas
            // 已可用，hook requestPointerLock 也能在用户点击画布前就位。
            // 脚本幂等（window.__linboxLook 防重复），SPA 锚点导航重复触发安全。
            // v2.19：只在 3D 视角模式开启时注入 —— 脚本的 rAF pull 循环每帧
            // 跨 JNI 一次，重型门户页（qqgame 等）上徒增渲染压力；关闭时
            // 普通网页全程零注入零开销（开启时由 BrowserContent 补注入）
            if (View3dController.enabled &&
                url != null && (url.startsWith("http") || url.startsWith("file:"))
            ) {
                view?.evaluateJavascript(View3dController.LOOK_SETUP_SCRIPT, null)
            }

            // v2.14.5 曾在此注入 viewport 缩放脚本（SPA 兜底），v2.14.6 撤销，
            // v2.14.9 缩小域改为捏合期间动态改写 viewport meta（理由同
            // onPageCommitVisible 注释）

            // ===== v2.14.2（gamehtml）强制重绘管线 —— 灰屏的真正解法 =====
            // 部分网页（View Transitions SPA / 重 Canvas 页面）加载完成后渲染
            // 管线未正确触发重绘，页面“卡住”直到切后台再回来才显示。
            // invalidate + requestLayout 强制 WebView 重新绘制当前帧。
            view?.invalidate()
            view?.requestLayout()
            // 延迟二次重绘：等注入脚本执行完后再触发一次
            val weak = WeakReference(view)
            view?.postDelayed({
                val wv = weak.get() ?: return@postDelayed
                if (wv.tag == TAG_DESTROYED) return@postDelayed
                wv.invalidate()
                wv.evaluateJavascript(
                    "try{void document.body&&document.body.offsetHeight;}catch(e){}", null
                )
            }, 300)
        }

        /**
         * v2.14.3：渲染进程崩溃/OOM 兜底（WebView 自身的问题）。
         * 不 override 该回调时，Chromium 会直接把【整个应用进程】杀掉。
         * 处理：丢弃死亡 WebView，标签重置为未加载态，renderEpoch+1 触发
         * key() 重组 → factory 重建新 WebView → 自动重载崩溃前页面。
         * 4399 等 Ruffle/WASM 重型页面内存压力大，该路径真实可达。
         */
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?
        ): Boolean {
            val dead = view ?: return true
            if (dead.tag == TAG_DESTROYED) return true
            try {
                // 先更新状态再销毁：epoch 变化在下一帧驱动 factory 重建
                tab.webView = null
                tab.hasLoadedOnce = false
                tab.lastRequestedUrl = null
                // v2.14.7：新 WebView 从默认 100% 开始，不继承旧缩放态
                //（新文档天然无注入）
                tab.viewZoom = 1f
                tab.renderEpoch += 1
                // 若该标签正处视频全屏，同步收起死掉的全屏层
                if (manager.activeTabId == tab.id) {
                    manager.hideCustomView()
                }
                BrowserEngine.destroyWebView(dead)
                if (manager.activeTabId == tab.id) {
                    runCatching {
                        Toast.makeText(ctx, "页面渲染进程异常，已自动重新加载", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
            }
            // true = 应用已接管，系统不再杀进程
            return true
        }

        // ====================================================================
        // v2.14.1 https→http 自动回退（一次性）
        // ====================================================================
        // 场景：地址栏一律默认 https（保证加密浏览），但路由器/NAS 等
        // 内网服务只有 http。当内网地址 https 连接失败（ERROR_CONNECT /
        // ERROR_FAILED_SSL_HANDSHAKE）或 SSL 证书校验失败（自签/无证书）时，
        // 自动改用 http 重试一次：既保证默认 https，又兼容 http 服务。
        // 公网域名不回退 —— 无效证书必须显式拒绝（安全）。
        private fun tryHttpsFallback(view: WebView?, failingUrl: String): Boolean {
            if (!isLanishUrl(failingUrl)) return false
            if (tab.httpsFallbackUrl == failingUrl) return false
            tab.httpsFallbackUrl = failingUrl
            val httpUrl = "http://" + failingUrl.removePrefix("https://")
            tab.url = httpUrl
            tab.lastRequestedUrl = httpUrl
            if (manager.activeTabId == tab.id) {
                manager.ui?.onUrlSync(httpUrl)
            }
            runCatching {
                Toast.makeText(ctx, "该地址不支持 HTTPS，已自动改用 HTTP 加载", Toast.LENGTH_SHORT).show()
            }
            view?.let { wv ->
                if (wv.tag != TAG_DESTROYED) {
                    wv.post { if (tab.webView === wv) wv.loadUrl(httpUrl) }
                }
            }
            return true
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            // 仅主框架错误才处理；忽略 -1（未知/取消）与 DNS 解析失败（WebView 会自行重试）
            if (request?.isForMainFrame == true) {
                val code = error?.errorCode ?: -1
                // v2.14.1：内网地址 https 连接/SSL 握手失败 → 自动回退 http 重试一次
                if (code == WebViewClient.ERROR_CONNECT ||
                    code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
                ) {
                    if (tryHttpsFallback(view, request.url.toString())) return
                }
                if (isActive() && code != -1 && code != WebViewClient.ERROR_HOST_LOOKUP) {
                    manager.ui?.onError(error?.description?.toString() ?: "加载失败")
                }
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            val failingUrl = error?.url ?: ""
            // v2.14.1：内网地址（路由器/NAS 自签证书）https 握手失败 →
            // 取消本次 https 加载并改用 http 重试一次。
            // 仅当失败资源就是主框架页面时才回退，避免子资源证书问题把整页导航走。
            if (failingUrl.isNotEmpty() && failingUrl == view?.url &&
                tryHttpsFallback(view, failingUrl)
            ) {
                handler?.cancel()
                return
            }
            // 公网站点：不自动信任无效证书（安全底线），保持默认取消行为
            super.onReceivedSslError(view, handler, error)
            // 仅主框架证书失败才提示（子资源失败沿用默认取消行为，不打扰用户）
            if (isActive() && failingUrl.isNotEmpty() && failingUrl == view?.url) {
                manager.ui?.onError("SSL 证书校验失败，已阻止不安全的连接")
            }
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            // v2.14.4：Ruffle 引擎本地化 —— 虚拟域全部从 assets 供给，
            // 彻底摆脱 jsdelivr/unpkg 在国内网络的 TLS 掐断（日志证据：
            // ssl_client_socket_impl handshake failed net_error -100，
            // 页面 JS 在跑、音频在播但引擎子资源全挂 → 灰屏有声音）
            if (request.url.host == RUFFLE_VIRTUAL_HOST) {
                return serveRuffleAsset(request.url.path)
            }
            // 支持本地 content:// URI 的 HTML 文件读取
            val uri = request.url
            if (uri.scheme == "content") {
                return try {
                    val mime = ctx.contentResolver.getType(uri) ?: "text/html"
                    val stream = ctx.contentResolver.openInputStream(uri)
                    WebResourceResponse(mime, "UTF-8", stream)
                } catch (_: Exception) {
                    null
                }
            }
            // v2.14.2（gamehtml interceptSwf）：拦截远程 SWF 请求。
            // Ruffle 以 XHR/fetch 从页面所在域跨域拉取 SWF，必须原生下载并附加
            // CORS 头 + Cookie 转发 + 防盗链 Referer，否则引擎报跨域/403。
            // 只拦截真正的 SWF 资源请求（含 4399 的 dw-XX 命名模式），不影响普通浏览。
            val url = uri.toString()
            val isSwfRequest = url.endsWith(".swf", ignoreCase = true) ||
                url.contains(".swf?", ignoreCase = true) ||
                (url.contains("4399.com") &&
                    (url.contains("/dw-") || url.contains("flash_tm3") || url.contains("flash20")))
            if (isSwfRequest) {
                return try {
                    interceptSwf(url, request)
                } catch (_: Exception) {
                    null
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        /**
         * v2.14.2（gamehtml playSwfScript）：用 Ruffle 全屏播放器打开 SWF。
         * 先注入引擎加载器（幂等），就绪后创建 fixed 满屏播放器接管页面。
         */
        private fun playSwfFullscreen(view: WebView?, swfUrl: String) {
            if (view == null || view.tag == TAG_DESTROYED) return
            val base = try {
                val u = java.net.URI(swfUrl)
                "${u.scheme}://${u.host}/"
            } catch (_: Exception) {
                null
            }
            // 对象字面量插值：base 非空时展开为 , base: 'xxx'（注意是对象属性而非函数实参）
            val baseArg = base?.let { ", base: '$it'" } ?: ""
            val js = RUFFLE_LOADER_SCRIPT + "\n" + """
                (function(){
                  function go(){
                    try {
                      var ruffle = window.RufflePlayer.newest();
                      if (!ruffle) return;
                      var player = ruffle.createPlayer();
                      player.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;z-index:9999;background:#000;';
                      document.body.appendChild(player);
                      var opt = { url: '$swfUrl'$baseArg };
                      player.ruffle().load(opt);
                    } catch(e) { console.error('[SWF] play error:', e); }
                  }
                  if (window.__ruffleLoaded) { go(); return; }
                  document.addEventListener('ruffleReady', function(){ go(); }, { once: true });
                })();
            """.trimIndent()
            view.evaluateJavascript(js, null)
        }

        // ====================================================================
        // v2.14.2（gamehtml interceptSwf）：SWF 原生下载 + CORS 头 + Cookie 转发
        // ====================================================================
        private val swfCache = ConcurrentHashMap<String, ByteArray>()

        /** 统一 CORS 响应头：所有 SWF 拦截响应（成功/失败/预检）都带这些头 */
        private val swfCorsHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Content-Type" to "application/x-shockwave-flash",
            "Cache-Control" to "no-cache"
        )

        /** 本地 Ruffle 资产响应头（CORS + 不缓存，便于版本升级后立即生效） */
        private val ruffleAssetHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-cache"
        )

        /**
         * v2.14.4：从 assets/ruffle/ 供给引擎文件。
         * ruffle.js 加载器会按浏览器特性从 publicPath 拉取 core.ruffle.*.js
         * 与 *.wasm（fetch 跨域 → 必须带 CORS 头；wasm 流式编译 → MIME 必须
         * application/wasm）。文件不存在时返回 404（而非 null），让注入的
         * 加载器链切换到下一个 CDN 后备源。
         *
         * v2.14.5（关键修复）：请求路径形如 /ruffle/<file>（RUFFLE_LOCAL_BASE
         * 以 /ruffle/ 结尾），旧实现未剥离目录前缀，name 含 '/' 被穿越防护
         * 全量拒绝 → 本地引擎资产 100% 返回 404 → 链式加载器退回 CDN →
         * 国内网络 TLS 掐断 → Ruffle 预加载器进度条永远卡住（用户实测
         * "一直进度条加载"）。与 GameBox 的 interceptAsset 对齐：剥离虚拟
         * 目录前缀后按文件名映射 assets/ruffle/<file>，同时保留穿越防护。
         */
        private fun serveRuffleAsset(path: String?): WebResourceResponse {
            fun notFound() = WebResourceResponse(
                "text/plain", "UTF-8", 404, "Not Found",
                ruffleAssetHeaders, ByteArrayInputStream(ByteArray(0))
            )
            if (path.isNullOrEmpty()) return notFound()
            var name = path.substringBefore("?").removePrefix("/")
            // 剥离虚拟目录前缀：/ruffle/ruffle.js → ruffle.js
            if (name.startsWith("$RUFFLE_ASSET_DIR/")) {
                name = name.removePrefix("$RUFFLE_ASSET_DIR/")
            }
            // 路径穿越防护：只允许 assets/ruffle/ 下的直接文件名
            if (name.isEmpty() || name.contains("..") || name.contains('/')) return notFound()
            return try {
                val stream = ctx.assets.open("$RUFFLE_ASSET_DIR/$name")
                val mime = when {
                    name.endsWith(".wasm", true) -> "application/wasm"
                    name.endsWith(".js", true) -> "text/javascript"
                    name.endsWith(".css", true) -> "text/css"
                    name.endsWith(".ttf", true) -> "font/ttf"       // v2.14.5：SimHei 字体
                    name.endsWith(".woff", true) -> "font/woff"
                    name.endsWith(".woff2", true) -> "font/woff2"
                    name.endsWith(".otf", true) -> "font/otf"
                    else -> "application/octet-stream"
                }
                WebResourceResponse(mime, null, stream).apply {
                    responseHeaders = ruffleAssetHeaders
                }
            } catch (_: Exception) {
                notFound()
            }
        }

        /** 信任所有 SSL 证书的 SSLSocketFactory（仅用于 SWF 下载兼容老 CDN） */
        @Volatile
        private var sslFactory: javax.net.ssl.SSLSocketFactory? = null

        private fun trustAllSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
            return sslFactory ?: synchronized(this) {
                sslFactory ?: try {
                    val tm = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf(tm), SecureRandom())
                    sslContext.socketFactory
                } catch (_: Exception) {
                    HttpsURLConnection.getDefaultSSLSocketFactory()
                }.also { sslFactory = it }
            }
        }

        /** 原生下载 SWF，返回带 CORS 头的响应（缓存 + 重试 + SSL 兼容 + 请求头转发） */
        private fun interceptSwf(url: String, request: WebResourceRequest?): WebResourceResponse? {
            // 0. CORS 预检（OPTIONS）：直接返回 200 + CORS 头，不下载文件
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && request?.method == "OPTIONS") {
                return WebResourceResponse(
                    "text/plain", "UTF-8", 200, "OK",
                    swfCorsHeaders, ByteArrayInputStream(ByteArray(0))
                )
            }

            // 1. 尝试 URL 列表：不强制 HTTP→HTTPS 升级（部分 4399 CDN 不支持 HTTPS）；
            //    HTTP 先试原始 HTTP，失败再试 HTTPS；HTTPS 直接用
            val tryUrls = when {
                url.startsWith("https://") -> listOf(url)
                url.startsWith("http://") -> listOf(url, "https://" + url.substring(7))
                else -> listOf(url)
            }

            // 2. 缓存命中直接返回（Ruffle 可能同时发起多个相同 SWF 请求）
            for (u in tryUrls) {
                swfCache[u]?.let { cached ->
                    return WebResourceResponse(
                        "application/x-shockwave-flash", null, 200, "OK",
                        swfCorsHeaders, ByteArrayInputStream(cached)
                    )
                }
            }

            // 3. 逐个 URL 尝试下载（每个最多重试 3 次，服务端 5xx 退避重试）
            for (swfUrl in tryUrls) {
                for (attempt in 1..3) {
                    try {
                        val conn = java.net.URL(swfUrl).openConnection() as java.net.HttpURLConnection
                        if (conn is HttpsURLConnection) {
                            conn.sslSocketFactory = trustAllSslSocketFactory()
                            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
                        }
                        conn.connectTimeout = 10000
                        conn.readTimeout = 20000
                        conn.requestMethod = "GET"
                        conn.instanceFollowRedirects = true

                        // 转发原始请求头（排除自行设置的/条件请求的/CORS 相关的）
                        request?.requestHeaders?.forEach { (key, value) ->
                            val lk = key.lowercase()
                            if (lk !in setOf(
                                    "cookie", "referer", "range", "if-modified-since", "if-none-match",
                                    "accept-encoding", "origin", "access-control-request-method",
                                    "access-control-request-headers", "host", "content-length"
                                )
                            ) {
                                conn.setRequestProperty(key, value)
                            }
                        }
                        if (request?.requestHeaders?.none { it.key.equals("User-Agent", true) } != false) {
                            conn.setRequestProperty("User-Agent", DESKTOP_UA)
                        }
                        conn.setRequestProperty("Accept", "*/*")
                        // 不请求 gzip，避免手动解压
                        conn.setRequestProperty("Accept-Encoding", "identity")

                        // 转发 Cookie（防防盗链/登录态丢失）
                        try {
                            val cookies = CookieManager.getInstance().getCookie(swfUrl)
                            if (!cookies.isNullOrEmpty()) {
                                conn.setRequestProperty("Cookie", cookies)
                            }
                        } catch (_: Exception) {
                        }

                        // 防盗链 Referer
                        if (swfUrl.contains("4399.com")) {
                            conn.setRequestProperty("Referer", "https://www.4399.com/")
                        } else {
                            try {
                                val u = java.net.URI(swfUrl)
                                conn.setRequestProperty("Referer", "${u.scheme}://${u.host}/")
                            } catch (_: Exception) {
                                conn.setRequestProperty("Referer", swfUrl)
                            }
                        }

                        conn.connect()
                        val responseCode = conn.responseCode
                        if (responseCode in 200..299) {
                            val data = conn.inputStream.readBytes()
                            if (swfCache.size >= 10) swfCache.clear()
                            swfCache[swfUrl] = data
                            return WebResourceResponse(
                                "application/x-shockwave-flash", null,
                                200, "OK", swfCorsHeaders, ByteArrayInputStream(data)
                            )
                        } else if (responseCode in 500..599 && attempt < 3) {
                            Thread.sleep(500L * attempt)
                            continue
                        } else {
                            break // 换下一个 URL（4xx/其他非 5xx 错误不重试）
                        }
                    } catch (_: Exception) {
                        if (attempt < 3) Thread.sleep(500L * attempt)
                    }
                }
                // 重试期间其他线程可能已成功下载
                swfCache[swfUrl]?.let { cached ->
                    return WebResourceResponse(
                        "application/x-shockwave-flash", null, 200, "OK",
                        swfCorsHeaders, ByteArrayInputStream(cached)
                    )
                }
            }

            // 4. 全部失败：返回 502 + CORS 头（而非 null）——null 会让 WebView 自行
            //    重发原始请求，页面侧因 Mixed Content/CORS 被阻断且无从感知。
            return WebResourceResponse(
                "application/x-shockwave-flash", null, 502, "Bad Gateway",
                swfCorsHeaders, ByteArrayInputStream(ByteArray(0))
            )
        }

        companion object {
            // ================================================================
            // v2.14.4：Ruffle 引擎本地化（虚拟域 → assets 拦截，零 CDN 依赖）
            // ================================================================
            /** 虚拟域：shouldInterceptRequest 按 host 命中后从 assets 供给 */
            private const val RUFFLE_VIRTUAL_HOST = "linbox.local"

            /** assets 内引擎目录（ruffle.js + 2×core.js + 2×wasm） */
            private const val RUFFLE_ASSET_DIR = "ruffle"

            /** 本地虚拟域基址（publicPath + script src 同源，核心/wasm 相对解析） */
            private const val RUFFLE_LOCAL_BASE = "https://$RUFFLE_VIRTUAL_HOST/ruffle/"

            /** 网络后备源（本地 404/拦截失效时逐级尝试；npmmirror 国内可达性最好）。
             *  v2.14.7：0.3.0 → 0.5.0 —— 0.3.0（2022）Avm2/AS3 支持不成熟，
             *  4399 新游戏（AS3）引擎能加载但无法执行 → 预加载进度条永远
             *  卡住；后备源同步升级避免降级到坏版本 */
            private const val RUFFLE_CDN_MIRROR = "https://registry.npmmirror.com/@ruffle-rs/ruffle/0.5.0/files/"
            private const val RUFFLE_CDN_JSDELIVR = "https://cdn.jsdelivr.net/npm/@ruffle-rs/ruffle@0.5.0/"

            /**
             * WebGL 像素管线守卫（v2.14.4，HTML5 游戏灰屏兜底）：
             * 嵌入式合成环境下存在"上下文创建成功、绘制调用不报错、但像素
             * 读回全零"的坏 GPU 路径 —— 游戏照跑（有声音）画面永远空白。
             * document-start 同步 clear→readPixels 探测；损坏时让
             * getContext('webgl') 返回 null，Ruffle/页面自动回退 canvas2d。
             */
            private const val WEBGL_GUARD_SCRIPT = """
                (function(){
                  if (window.__webglGuard) return;
                  window.__webglGuard = true;
                  var broken = false;
                  try {
                    var c = document.createElement('canvas');
                    var gl = c.getContext('webgl') || c.getContext('experimental-webgl');
                    if (gl) {
                      gl.clearColor(1.0, 0.0, 0.0, 1.0);
                      gl.clear(gl.COLOR_BUFFER_BIT);
                      var px = new Uint8Array(4);
                      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
                      broken = !(px[0] > 200);
                      try { gl.getExtension('WEBGL_lose_context').loseContext(); } catch(e) {}
                    }
                  } catch(e) { broken = true; }
                  if (broken) {
                    try {
                      var orig = HTMLCanvasElement.prototype.getContext;
                      HTMLCanvasElement.prototype.getContext = function(type){
                        if (type && /webgl/i.test(String(type))) return null;
                        return orig.apply(this, arguments);
                      };
                      window.__webglDisabled = true;
                    } catch(e) {}
                  }
                })();
            """

            /**
             * View Transitions API 补丁（gamehtml，所有页面通用）。
             * WebView 对 startViewTransition 支持不完善：回调不执行导致 SPA 无法
             * 跳转、finished Promise 抛 AbortError。polyfill 确保回调同步执行，
             * 并强制触发一帧重绘（解决 polyfill 执行后页面"卡住"）。
             */
            private const val VIEW_TRANSITION_PATCH_SCRIPT = """
                (function(){
                  if (window.__vtPatch) return;
                  window.__vtPatch = true;
                  var vtPolyfill = function(callback) {
                    var result;
                    try { result = callback ? callback() : undefined; } catch(e) { result = Promise.reject(e); }
                    var p = (result && typeof result.then === 'function') ? result : Promise.resolve();
                    requestAnimationFrame(function() {
                      try { void document.body && document.body.offsetHeight; } catch(e) {}
                    });
                    var finished = p.then(undefined, function(err) {
                      if (err && err.name === 'AbortError') return;
                      throw err;
                    });
                    return { finished: finished, ready: Promise.resolve(),
                             updateCallbackDone: p, skipTransition: function() {}, types: [] };
                  };
                  try {
                    Object.defineProperty(Document.prototype, 'startViewTransition', {
                      value: vtPolyfill, writable: true, configurable: true });
                  } catch(e) {}
                  try { document.startViewTransition = vtPolyfill; } catch(e) {}
                  if (!window.__vtRejectionHook) {
                    window.__vtRejectionHook = true;
                    window.addEventListener('unhandledrejection', function(event) {
                      if (event.reason && event.reason.name === 'AbortError') {
                        var msg = event.reason.message || String(event.reason);
                        if (msg.indexOf('Transition') >= 0 || msg.indexOf('skipped') >= 0 || msg === 'AbortError') {
                          event.preventDefault();
                        }
                      }
                    });
                  }
                })();
            """

            /**
             * PC 页面 viewport 自适配（gamehtml VIEWPORT_FIT_SCRIPT，v2.14.6 恢复）。
             *
             * v2.14.5 曾用 ZOOM_VIEWPORT_SCRIPT（宽画布 + minimum-scale=0.3）
             * 尝试打开 30% 缩小域，实测失败并撤销，根因记录如下：
             * 1) Chromium 页面最小缩放 = max(meta minimum-scale, 窗口宽/画布宽)，
             *    而画布宽就是排版宽度 —— viewport 层面"缩到 100% 以下"与
             *    "排版不变"物理上不可能兼得：画布一放宽，媒体查询/流式布局/
             *    initial 全览全部跟着变；
             * 2) 实测症状与用户反馈逐条对应：4399 PC 首页按超宽画布排版整页
             *    压扁（排版有问题）、初始即 30% 全览（默认缩放被改）、初始
             *    已是最小值（不能继续缩小）、全览态无滚动余量（往左拉不过去）；
             * 3) 结论：30%~100% 缩小域改由 ZoomPinchLayout 在捏合期间动态
             *    改写 viewport meta 实现（v2.14.9：width=原宽/z + 钳制
             *    scale = 窗口宽/画布宽，整页等比变小且任何比例下精确铺满
             *    窗口宽 —— 桌面 Chrome Ctrl+减号 同源语义；v2.14.7/8 的
             *    body.zoom 方案因 letterbox 灰边被用户否决），本脚本只负责
             *    默认排版 —— 与 v2.14.4 逐字一致；
             *    ≥100% 放大继续由原生捏合处理（排版重排/文字回流）。
             *
             * 本脚本语义（v2.14.2 起不变）：仅对【无 viewport meta】的 PC 页
             * 注入 width=device-width + sw/1200 自适配 initial-scale（device-width
             * 画布下 initial 被 Chromium 钳到地板 100%，窄屏 sw/1200 不生效但
             * 无害），页面自带 viewport 则完全不动。必须在页面自身 JS 之前
             * 执行（onPageStarted），零网络开销。
             */
            private const val VIEWPORT_FIT_SCRIPT = """
                (function(){
                  try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (meta) return;
                    var head = document.head || document.documentElement;
                    if (!head) return;
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    var sw = window.screen.width || 360;
                    var scale = Math.max(0.25, Math.min(1, sw / 1200));
                    meta.content = 'width=device-width, initial-scale=' + scale +
                        ', minimum-scale=0.01, maximum-scale=10.0, user-scalable=yes';
                    head.appendChild(meta);
                  } catch(e) {}
                })();
            """

            /**
             * 伪造 Flash 插件支持（gamehtml FLASH_FAKE_SUPPORT_SCRIPT，完整移植）。
             * 让 4399 等页面检测到浏览器"有 Flash 插件"，从而创建 <object> 元素；
             * 之后 Ruffle polyfill 把 <object>/<embed> 替换为 Canvas 播放器。
             * 必须在页面 JS 执行前注入（onPageStarted），零网络开销。
             */
            private const val FLASH_FAKE_SUPPORT_SCRIPT = """
                (function(){
                  if (window.__flashFaked) return;
                  window.__flashFaked = true;
                  try {
                    var fakePlugin = {
                      name: 'Shockwave Flash',
                      filename: 'libflashplayer.so',
                      description: 'Shockwave Flash 32.0 r0',
                      length: 1,
                      0: { type: 'application/x-shockwave-flash', suffixes: 'swf', description: 'Shockwave Flash' }
                    };
                    fakePlugin.namedItem = function(n) { return (n === 'Shockwave Flash') ? fakePlugin : null; };
                    fakePlugin.item = function(i) { return i === 0 ? fakePlugin : null; };
                    fakePlugin.refresh = function() {};
                    var plugins = navigator.plugins || {};
                    if (plugins.namedItem) { fakePlugin.namedItem = function(n) { return (n === 'Shockwave Flash') ? fakePlugin : plugins.namedItem.call(plugins, n); }; }
                    if (plugins.item) { fakePlugin.item = function(i) { return i === 0 ? fakePlugin : plugins.item.call(plugins, i); }; }
                    Object.defineProperty(navigator, 'plugins', {
                      get: function() {
                        var p = plugins;
                        if (!p['Shockwave Flash']) {
                          try { p['Shockwave Flash'] = fakePlugin; p[0] = fakePlugin; } catch(e) {}
                        }
                        p.length = Math.max(p.length || 0, 1);
                        return p;
                      },
                      configurable: true
                    });
                    var fakeMime = { type: 'application/x-shockwave-flash', suffixes: 'swf', description: 'Shockwave Flash', enabledPlugin: fakePlugin };
                    var mimes = navigator.mimeTypes || {};
                    Object.defineProperty(navigator, 'mimeTypes', {
                      get: function() {
                        if (!mimes['application/x-shockwave-flash']) {
                          try { mimes['application/x-shockwave-flash'] = fakeMime; } catch(e) {}
                        }
                        return mimes;
                      },
                      configurable: true
                    });
                    window.ActiveXObject = function(name) {
                      if (name && /ShockwaveFlash/i.test(name)) return { SetVariable: function(){} };
                      throw new Error('Not supported');
                    };
                  } catch(e) {}
                })();
            """

            /**
             * Ruffle 引擎链式加载器（v2.14.4 重写：本地 assets 优先，
             * npmmirror / jsdelivr 网络后备；v2.14.5 移植 GameBox 字体修复；
             * v2.14.7 引擎资产整体升级）：注入为 window.__linboxLoadRuffle()，
             * 幂等（加载中/已加载直接返回），每次尝试同步更新 publicPath
             *（core/wasm 相对它解析）。旧版固定 jsdelivr —— 国内网络 TLS
             * 掐断时 Flash 游戏区永远空白。
             *
             * v2.14.7（关键修复：本地加载卡进度条）：旧本地资产 = 官方
             * 0.3.0 正式版（MD5 逐一比对确认），Avm2/AS3 支持不成熟 ——
             * 4399 新游戏（AS3）引擎加载成功但无法执行，预加载进度条永远
             * 卡住（用户实测“一直进度条”）。本次整体替换为 GameBox 同源
             * nightly 构建（用户在 GameBox 上实测可用）；simhei.ttf 两边
             * MD5 一致保持不变；CDN 后备同步升级 0.5.0。
             *
             * 字体修复（GameBox RuffleInjector.fontConfigScript 同款）：
             * Flash 游戏多用设备字体（中文 SWF 无内嵌字形），Ruffle 默认
             * 只带拉丁字形 → 中文显示方块。fontSources 指向本地 simhei.ttf
             * （虚拟域供给，与引擎源无关恒本地），defaultFonts 把七类
             * 设备字体全映射 SimHei（v2.14.7 补 chineseSimplified，与
             * GameBox swf 版对齐），中文正常渲染。
             */
            private const val RUFFLE_CHAIN_SCRIPT = """
                (function(){
                  if (window.__linboxLoadRuffle) return;
                  var BASE = {
                    "polyfills": true,
                    "autoplay": "on",
                    "unmuteOverlay": "visible",
                    "letterbox": "fullscreen",
                    "upgradeToHttps": false,
                    "allowScriptAccess": true,
                    "scale": "showAll",
                    "quality": "high",
                    "allowFullscreen": true,
                    "splashScreen": true,
                    "preloader": true,
                    "logLevel": "warn",
                    "maxExecutionDuration": {"secs": 15, "nanos": 0},
                    "fontSources": ["$RUFFLE_LOCAL_BASE" + "simhei.ttf"],
                    "defaultFonts": {
                      "sans": ["SimHei"],
                      "serif": ["SimHei"],
                      "typewriter": ["SimHei"],
                      "japaneseGothic": ["SimHei"],
                      "japaneseGothicMono": ["SimHei"],
                      "japaneseMincho": ["SimHei"],
                      "chineseSimplified": ["SimHei"]
                    }
                  };
                  var SOURCES = [
                    "$RUFFLE_LOCAL_BASE",
                    "$RUFFLE_CDN_MIRROR",
                    "$RUFFLE_CDN_JSDELIVR"
                  ];
                  window.__linboxLoadRuffle = function(){
                    if (window.__ruffleLoaded || window.__ruffleLoading) return;
                    window.__ruffleLoading = true;
                    function attempt(i){
                      if (i >= SOURCES.length) {
                        window.__ruffleLoading = false;
                        console.error('[Ruffle] local assets and all CDN fallbacks failed');
                        return;
                      }
                      window.RufflePlayer = window.RufflePlayer || {};
                      var cfg = {};
                      for (var k in BASE) cfg[k] = BASE[k];
                      cfg.publicPath = SOURCES[i];
                      window.RufflePlayer.config = cfg;
                      var s = document.createElement('script');
                      s.src = SOURCES[i] + 'ruffle.js';
                      s.async = true;
                      s.onload = function(){
                        window.__ruffleLoaded = true;
                        window.__ruffleLoading = false;
                        try {
                          var r = window.RufflePlayer.newest();
                          if (r && r.init) r.init();
                        } catch(e) {}
                        window.__playSwf = function(url, base){
                          try {
                            var ruffle = window.RufflePlayer.newest();
                            var player = ruffle.createPlayer();
                            player.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;z-index:9999;background:#000;';
                            document.body.appendChild(player);
                            var opt = { url: url };
                            if (base) opt.base = base;
                            player.ruffle().load(opt);
                          } catch(e) { console.error('[SWF] play error:', e); }
                        };
                        document.dispatchEvent(new CustomEvent('ruffleReady'));
                      };
                      s.onerror = function(){
                        try { s.parentNode && s.parentNode.removeChild(s); } catch(e) {}
                        attempt(i + 1);
                      };
                      (document.head || document.documentElement).appendChild(s);
                    }
                    attempt(0);
                  };
                })();
            """

            /**
             * Ruffle 引擎加载入口（gamehtml RuffleInjector fullInjection 演化）：
             * 链式加载器 + 立即触发。4399 域名页 onPageStarted 直载兜底用。
             */
            private const val RUFFLE_LOADER_SCRIPT = RUFFLE_CHAIN_SCRIPT + """
                (function(){
                  window.__linboxLoadRuffle();
                })();
            """

            /**
             * Flash 元素懒加载探测器（LinBox 按 gamehtml 思路实现，v2.14.4
             * 接入链式加载器）：
             * DOMContentLoaded + MutationObserver 监测 —— 页面一旦出现
             * <object>/<embed> Flash 元素（含 swfobject.embedSWF 动态创建的），
             * 立即经 __linboxLoadRuffle（本地 assets 优先、CDN 后备）拉起引擎。
             * 4399 域名页另有 onPageStarted 直载兜底。
             */
            private const val FLASH_DOM_DETECT_SCRIPT = RUFFLE_CHAIN_SCRIPT + """
                (function(){
                  if (window.__flashDomDetect) return;
                  window.__flashDomDetect = true;
                  var SEL = 'object[type="application/x-shockwave-flash"],embed[type="application/x-shockwave-flash"],object[data$=".swf" i],embed[src$=".swf" i],object[classid*="D27CDB6E" i]';
                  function check(){
                    try {
                      if (document.querySelector(SEL) && window.__linboxLoadRuffle) {
                        window.__linboxLoadRuffle();
                      }
                    } catch(e) {}
                  }
                  if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', check);
                  } else { check(); }
                  if (window.MutationObserver) {
                    try {
                      var mo = new MutationObserver(function(){ check(); });
                      mo.observe(document.documentElement || document.body, {childList: true, subtree: true});
                    } catch(e) {}
                  }
                })();
            """

            /**
             * 4399 防盗链：伪造 document.referrer（gamehtml REFERER_SPOOF_SCRIPT）。
             */
            private const val REFERER_SPOOF_SCRIPT = """
                (function(){
                  try {
                    Object.defineProperty(document, 'referrer', {
                      get: function() { return 'https://www.4399.com/'; },
                      configurable: true
                    });
                  } catch(e) {}
                })();
            """
        }
    }

    // ====================================================================
    // WebChromeClient：多标签弹窗 + JS 弹窗 + 视频全屏 + 文件选择
    // ====================================================================
    private class TabChromeClient(
        private val ctx: Context,
        private val tab: BrowserTab,
        private val manager: TabManager
    ) : WebChromeClient() {

        private fun isActive() = manager.activeTabId == tab.id

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (isActive()) {
                manager.ui?.onProgressChanged(newProgress)
            }
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (!title.isNullOrEmpty()) {
                tab.title = title
                if (isActive()) {
                    manager.ui?.onTitleChanged(title)
                }
            }
        }

        /**
         * window.open / target=_blank 链接 / 新窗口表单提交（百度"百度一下"）统一入口。
         *
         * v2.8：弹出的 WebView 直接作为【新标签的真实 WebView】：
         * 1) 创建 WebView 并完整配置（client 绑定到新 tab）
         * 2) 登记为新标签并激活 —— Compose 随即将其上屏
         * 3) 通过 transport 交给 Chromium —— 表单 POST / JS 重定向等
         *    一切导航在真实标签中自然完成（旧版临时 WebView 拦截不到 POST，
         *    导致"点击百度一下没反应"）
         * 4) 页面加载后由其自身 client 同步 tab.url / 标题 / 地址栏
         */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            if (resultMsg == null) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popupWebView = WebView(ctx)
            val popupTab = manager.openTab("about:blank", popupWebView)
            configure(popupWebView, popupTab, manager)
            // v2.14.4（核心）：transport 交接【前】立即把弹窗 WebView 挂进真实
            // 视图树 —— Chromium 收到 transport 后马上开始加载；若此刻脱离视图
            // 树（ContentCatcher 日志：创建时 0,0-0,0），所有首帧生产落空，
            // attach 梯子（v2.14.3）只是事后补救。挂到 opener 的容器（真实
            // attached 视图树），首帧在真实 surface 上产出；openTab 已激活新
            // 标签，Compose 下一帧重组时 factory 会剥离临时父容器认领。
            // v2.14.6：opener 的父容器现在是 ZoomPinchLayout（普通 FrameLayout，
            // 会自动布局子 View），手动 measure/layout 仍保留 —— transport
            // 交接后 Chromium 立即加载，下一帧布局 pass 之前就要有正确尺寸。
            try {
                val holder = view?.parent as? ViewGroup
                if (holder != null && view.width > 0 && view.height > 0) {
                    holder.addView(
                        popupWebView,
                        ViewGroup.LayoutParams(view.width, view.height)
                    )
                    popupWebView.measure(
                        View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY)
                    )
                    popupWebView.layout(0, 0, view.width, view.height)
                }
            } catch (_: Exception) {
                // opener 脱离视图树/尺寸为 0 的边缘场景：跳过临时挂载，
                // v2.14.3 的 attach 重绘梯子仍然兜底
            }
            transport.webView = popupWebView
            resultMsg.sendToTarget()
            return true
        }

        /** window.close()：JS 自动关闭的弹窗标签 → 关闭对应标签 */
        override fun onCloseWindow(window: WebView?) {
            if (tab.isPopup) {
                manager.closeTab(tab.id)
            }
        }

        // 自动确认 JS 弹窗，避免网页交互卡住
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            result?.confirm()
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            result?.confirm()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean {
            result?.confirm(defaultValue)
            return true
        }

        /**
         * HTML5 全屏（视频站点播放器的全屏按钮）：
         * 把页面提交的自定义视图挂到 DecorView 最顶层 → 盖过任务栏/状态栏，
         * 真正满屏播放（v2.8 修复，旧版仅调 super() 等于什么都不做）。
         */
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) {
                callback?.onCustomViewHidden()
                return
            }
            manager.showCustomView(view, callback)
        }

        override fun onShowCustomView(
            view: View?,
            requestedOrientation: Int,
            callback: CustomViewCallback?
        ) {
            // 带方向的变体同样处理（忽略页面请求的方向，遵循用户显示设置）
            onShowCustomView(view, callback)
        }

        override fun onHideCustomView() {
            manager.hideCustomView()
        }

        // 文件上传回调（input[type=file]）
        private var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null

        // v2.14.2（gamehtml GameWebChromeClient）：游戏/网页所需的运行时权限直接授予
        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
            try {
                request?.grant(request.resources)
            } catch (_: Exception) {
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: android.webkit.GeolocationPermissions.Callback?
        ) {
            callback?.invoke(origin, true, false)
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            this.filePathCallback = filePathCallback
            try {
                val intent = fileChooserParams?.createIntent()
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    (ctx as? android.app.Activity)?.startActivityForResult(intent, 0)
                }
            } catch (_: Exception) {
            }
            return true
        }
    }
}
