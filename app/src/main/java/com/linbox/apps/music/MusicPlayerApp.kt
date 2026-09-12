package com.linbox.apps.music

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.apps.filemanager.FilePickBus
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.AppDef
import com.linbox.core.window.WindowContentScope
import com.linbox.core.window.WindowManager
import java.io.File
import kotlinx.coroutines.launch

/**
 * 云音乐（v2.17 新增应用；v2.19 设置体验完善）：
 * - 界面对照网易云音乐 PC 版（需求图2）：左侧导航栏 + 中部歌曲列表 + 底部播放条
 * - 在线音乐与歌词数据源移植自用户提供的 linboxyy.py（酷我搜索/播放链接/歌词 + 网易云歌词兜底）
 * - 歌词秀为 3D 透视样式（需求图1），见 [Lyrics3DPage]
 * - 支持搜索播放、我喜欢、最近播放、本地音乐（MediaStore）、歌曲/歌词下载
 *
 * v2.19 变更：
 * - 背景图片/扫描目录选择改由桌面【文件资源管理器】窗口完成（FilePickBus 回传），
 *   不再拉起手机自带的 SAF 文件选择器
 * - 主页自定义背景全局生效：侧栏/底栏/内容区表面（设置卡片、芯片、搜索框等）半透明融入（不再只显示在内容区）
 * - 3D 歌词设置（倾斜/视角/发光等）改为快照状态直读，改动立即生效
 */
val MusicPlayerApp = AppDef(
    id = "music",
    displayName = "云音乐",
    iconAsset = "app:music",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 920.dp,
    defaultHeight = 600.dp,
    pinnedToDesktop = true
) { scope ->
    MusicContent(scope)
}

/** 页面枚举 */
private enum class Page { SEARCH, FAVORITES, RECENT, LOCAL, DOWNLOADS, SETTINGS }

/** 下载任务（字段为 Compose State，进度条自动刷新） */
class DownloadItem(val song: SongInfo) {
    var progress by mutableStateOf(0f)
    var done by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var file by mutableStateOf<File?>(null)
}

@Composable
private fun MusicContent(scope: WindowContentScope) {
    val context = LocalContext.current
    // v2.22.3（fix9.11）：应用级单例引擎 —— 修复“播放器最小化后音乐被强制关闭、
    // 桌面歌词一并消失”。桌面窗口系统最小化即不渲染（WindowHost 只渲染可见
    // 窗口），旧实现在组合销毁时 engine.dispose() 停播。现引擎进程级常驻，
    // 最小化只销毁窗口 UI；显式关闭最后一个播放器窗口时才经下方
    // windowState.onClose 停止。
    val engine = remember(context) { MusicEngineHolder.obtain(context) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val uiScope = rememberCoroutineScope()

    // ===== 页面状态 =====
    var page by remember { mutableStateOf(Page.SEARCH) }
    var showLyrics by remember { mutableStateOf(false) }

    // ===== 搜索状态（页面间切换保留） =====
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SongInfo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchPage by remember { mutableStateOf(0) }

    // ===== 歌单状态 =====
    var favorites by remember { mutableStateOf(engine.store.loadFavorites()) }
    var recent by remember { mutableStateOf(engine.store.loadRecent()) }
    val favKeys = remember(favorites) { favorites.map { it.key }.toSet() }

    fun refreshLibrary() {
        favorites = engine.store.loadFavorites()
        recent = engine.store.loadRecent()
    }

    // ===== 播放器设置中心（v2.18） =====
    var musicSettings by remember { mutableStateOf(engine.store.loadMusicSettings()) }
    fun updateSettings(s: MusicSettings) {
        musicSettings = s
        engine.store.saveMusicSettings(s)
    }

    // ===== v2.21.3 桌面歌词锁定开关（状态存 desklyric.json，与位置同文件） =====
    var lyricOverlayState by remember { mutableStateOf(engine.store.loadLyricOverlayState()) }
    fun updateLyricLock(locked: Boolean) {
        lyricOverlayState = lyricOverlayState.copy(locked = locked)
        engine.store.saveLyricOverlayState(lyricOverlayState)
        // 服务已运行则经 onStartCommand 重读锁定状态并重建窗口；未运行则拉起
        if (musicSettings.desktopLyricOn) startDesktopLyricService(context)
    }

    // ===== 本地音乐 =====
    var localSongs by remember { mutableStateOf<List<SongInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    fun scanLocal() {
        scanning = true
        uiScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val found = queryLocalSongs(context, musicSettings.scanMode, musicSettings.scanDirs)
            localSongs = found
            scanning = false
        }
    }

    // 设置变更后自动重扫本地曲库（仅指定目录模式）
    LaunchedEffect(musicSettings.scanMode, musicSettings.scanDirs.size) {
        if (musicSettings.scanMode == MusicSettings.SCAN_DIRS) scanLocal()
    }

    // ===== v2.19 桌面文件资源管理器选择（替代系统 SAF 选择器） =====
    // 背景图片/扫描目录不再拉起手机自带文件管理器，改为打开 LinBox 桌面自带的
    // 【文件资源管理器】选择窗口（pickMode=image/dir），用户选中后经 FilePickBus
    // 精确回传本窗口。pendingPick 记录本次选择用途（图片归属/目录），回传时按用途分发。
    var pendingPick by remember { mutableStateOf<String?>(null) }

    fun openDesktopPicker(kind: String, title: String, mode: String) {
        pendingPick = kind
        WindowManager.get().open(
            appId = "file_explorer",
            title = title,
            launchMode = AppRegistry.get("file_explorer")?.launchMode ?: LaunchMode.FLOATING,
            launchArgs = mapOf(
                "pickMode" to mode,
                "targetApp" to "music",
                "targetWindow" to scope.windowState.id
            ),
            initialWidth = 920,
            initialHeight = 620
        )
    }

    // 文件资源管理器选中文件/目录后回传本窗口（DisposableEffect 内注册一次，
    // lambda 经委托读到的都是最新 musicSettings / pendingPick）
    DisposableEffect(Unit) {
        val unlisten = FilePickBus.listen("music", scope.windowState.id) { path ->
            when (pendingPick) {
                "homeImage" -> updateSettings(
                    musicSettings.copy(homeBgImage = path, homeBgMode = MusicSettings.BG_IMAGE)
                )
                "lyricImage" -> updateSettings(
                    musicSettings.copy(lyricBgImage = path, lyricBgMode = MusicSettings.BG_IMAGE)
                )
                "coverImage" -> updateSettings(
                    musicSettings.copy(coverImage = path)
                )
                "discImage" -> updateSettings(
                    musicSettings.copy(discImage = path)
                )
                "folder" -> {
                    if (!musicSettings.scanDirs.contains(path)) {
                        updateSettings(musicSettings.copy(scanDirs = musicSettings.scanDirs + path))
                    }
                }
            }
            pendingPick = null
        }
        onDispose { unlisten() }
    }

    // ===== 下载 =====
    val downloads = remember { mutableStateListOf<DownloadItem>() }

    fun startDownload(song: SongInfo) {
        if (song.isLocal) {
            toast("本地歌曲无需下载")
            return
        }
        if (downloads.any { it.song.key == song.key && !it.failed }) {
            toast("该歌曲已在下载列表")
            return
        }
        // 清理同曲的失败任务（重试场景）
        downloads.removeAll { it.song.key == song.key && it.failed }
        val item = DownloadItem(song)
        downloads.add(0, item)
        uiScope.launch {
            // 1) 解析直链
            val urlRes = KuwoMusicApi.getPlayUrl(song.id)
            if (urlRes.isFailure) {
                item.failed = true
                item.error = urlRes.exceptionOrNull()?.message ?: "获取链接失败"
                return@launch
            }
            // 2) 下载音频
            val dir = musicDownloadDir(context)
            val file = File(dir, sanitizeFileName("${song.artist} - ${song.name}") + ".mp3")
            val dl = KuwoMusicApi.downloadFile(urlRes.getOrThrow(), file) { done, total ->
                if (total > 0) item.progress = (done.toFloat() / total).coerceIn(0f, 1f)
            }
            if (dl.isFailure) {
                item.failed = true
                item.error = dl.exceptionOrNull()?.message ?: "下载失败"
                runCatching { file.delete() }
                return@launch
            }
            item.done = true
            item.progress = 1f
            item.file = file
            // 3) 同步下载歌词到同目录
            val lyric = fetchLyrics(engine.store, song, engine = musicSettings.lyricEngine)
            if (lyric != null) {
                runCatching {
                    File(dir, file.nameWithoutExtension + ".lrc").writeText(LrcParser.toLrcText(lyric))
                }
            }
            toast("下载完成：${file.name}")
            refreshLibrary()
        }
    }

    // v2.21.4：歌词手动下载/自动下载后刷新当前曲歌词显示（tick 作为 LaunchedEffect key）
    var lyricRefreshTick by remember { mutableStateOf(0) }

    fun downloadLyricFile(song: SongInfo?) {
        if (song == null) {
            toast("当前没有播放中的歌曲")
            return
        }
        uiScope.launch {
            val lyric = fetchLyrics(engine.store, song, force = true, engine = musicSettings.lyricEngine)
            if (lyric == null) {
                toast("未找到该歌曲的歌词")
                return@launch
            }
            val dir = musicDownloadDir(context)
            val file = File(dir, sanitizeFileName("${song.artist} - ${song.name}") + ".lrc")
            runCatching { file.writeText(LrcParser.toLrcText(lyric)) }
            // v2.21.4：若下载的是正在播放的歌曲，刷新其歌词显示
            if (song.key == engine.currentSong?.key) lyricRefreshTick++
            toast("歌词已保存：${file.absolutePath}")
        }
    }

    // ===== 歌词获取（v2.22.3 收编到引擎） =====
    // 切歌/装回会话时引擎自动后台拉取（缓存优先 + 深度下载兑底），
    // UI 直读 engine.lyricDoc / engine.lyricLoading；窗口最小化后
    // 歌词照常推进、桌面歌词不中断。lyricRefreshTick：手动/搜索页
    // 下载歌词后强制跳过缓存刷新当前曲（v2.21.4）
    LaunchedEffect(lyricRefreshTick) {
        if (lyricRefreshTick > 0) engine.refreshLyrics(force = true)
    }

    // ===== v2.21 桌面歌词偏好推送（v2.22.3 保留） =====
    // 引擎已负责把播放进度/歌词行推进到 DesktopLyricBus（引擎级，最小化后照常）；
    // 此处仅负责“偏好变化 → 推送镜像 + 拉起/关闭悬浮窗服务”：
    // 开启时 start（服务已运行则触发 onStartCommand → 按需重建窗口），
    // 关闭时 stop；颜色/透明度/字号/KTV 开关由服务 200ms 轮询总线自动生效
    LaunchedEffect(
        musicSettings.desktopLyricOn,
        musicSettings.desktopLyricFullscreen,
        musicSettings.desktopLyricColor,
        musicSettings.desktopLyricBgAlpha,
        musicSettings.desktopLyricSize,
        musicSettings.desktopLyricKtv,
        musicSettings.desktopLyricLines
    ) {
        DesktopLyricBus.applySettings(musicSettings)
        if (musicSettings.desktopLyricOn) {
            startDesktopLyricService(context)
        } else {
            stopDesktopLyricService(context)
        }
    }

    // v2.22.3（fix9.11）：窗口关闭/最小化语义分离 ——
    //   最小化：组合销毁但不做任何清理（引擎单例常驻，音乐与桌面歌词照常）；
    //   显式关闭：WindowManager.close 先触发 windowState.onClose 再摘除窗口，
    //   此刻 windowsForApp("music") 仍含正在关闭的窗口 —— 仅当它是最后一个
    //   播放器窗口时停止引擎并关闭桌面歌词（保留 v2.21.2 语义：关闭播放器
    //   = 结束音乐与桌面歌词；多窗口时等其他窗口也关了才停）。
    scope.windowState.onClose = {
        if (WindowManager.get().windowsForApp("music").size <= 1) {
            MusicEngineHolder.shutdown()
            DesktopLyricBus.songName = ""
            DesktopLyricBus.lines = emptyList()
            DesktopLyricBus.index = -1
            DesktopLyricBus.playing = false
            stopDesktopLyricService(context)
        }
    }

    // ===== 窗口标题跟随歌曲 =====
    LaunchedEffect(engine.currentSong?.key) {
        val song = engine.currentSong
        scope.onTitleChange(if (song != null) "云音乐 · ${song.name} - ${song.artist}" else "云音乐")
    }

    // ===== 播放错误提示 =====
    LaunchedEffect(engine.playError) {
        engine.playError?.let {
            toast(it)
            engine.clearError()
        }
    }

    // 返回键：歌词页优先退出
    BackHandler(enabled = showLyrics) { showLyrics = false }

    // ===== 布局：[侧栏 | 主区(歌词页覆盖)] + 底栏（v2.18 背景可自定义，
    // v2.19 起全局生效：侧栏/底栏半透明融入自定义背景） =====
    val homeCustomBg = musicSettings.homeBgMode != MusicSettings.HOME_BG_DEFAULT
    val homeBgModifier = when (musicSettings.homeBgMode) {
        MusicSettings.BG_SOLID -> Modifier.background(Color(musicSettings.homeBgColor))
        MusicSettings.BG_GRADIENT -> {
            val pair = HomeBgGradients.getOrElse(musicSettings.homeBgGradient) { HomeBgGradients[0] }
            Modifier.background(Brush.verticalGradient(pair))
        }
        MusicSettings.BG_IMAGE -> Modifier.background(Color.Transparent)
        else -> Modifier.background(Mc.bg)
    }
    // v2.21.5：向全内容区提供自定义背景激活标记 —— 设置卡片/选项芯片/输入框/搜索框等自动半透明融入
    CompositionLocalProvider(LocalHomeCustomBg provides homeCustomBg) {
    Box(Modifier.fillMaxSize()) {
        // 自定义图片背景 + 压暗层（仅图片模式）
        if (musicSettings.homeBgMode == MusicSettings.BG_IMAGE) {
            BgImage(musicSettings.homeBgImage, Modifier.matchParentSize())
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = musicSettings.homeImageDim))
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .then(homeBgModifier)
        ) {
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(page = page, onPageChange = { page = it }, customBg = homeCustomBg)
                Box(Modifier.weight(1f)) {
                    when (page) {
                        Page.SEARCH -> SearchPage(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = { kw ->
                                searching = true
                                searchError = null
                                uiScope.launch {
                                    val r = KuwoMusicApi.search(kw, 0, 20)
                                    if (r.isSuccess) {
                                        searchResults = r.getOrThrow().map { SongInfo.fromKuwoSong(it) }
                                        searchPage = 0
                                        if (searchResults.isEmpty()) searchError = "未找到相关歌曲"
                                    } else {
                                        searchResults = emptyList()
                                        searchError = r.exceptionOrNull()?.message ?: "搜索失败"
                                    }
                                    searching = false
                                }
                            },
                            results = searchResults,
                            searching = searching,
                            error = searchError,
                            onLoadMore = {
                                if (!searching && searchResults.isNotEmpty()) {
                                    val nextPage = searchPage + 1
                                    searching = true
                                    uiScope.launch {
                                        val r = KuwoMusicApi.search(query, nextPage, 20)
                                        if (r.isSuccess) {
                                            searchResults = searchResults + r.getOrThrow().map { SongInfo.fromKuwoSong(it) }
                                            searchPage = nextPage
                                        }
                                        searching = false
                                    }
                                }
                            },
                            favKeys = favKeys,
                            currentKey = engine.currentSong?.key,
                            isPlaying = engine.isPlaying,
                            onPlay = { song -> engine.play(song, searchResults) },
                            onToggleFav = { song ->
                                val added = engine.store.toggleFavorite(song)
                                refreshLibrary()
                                toast(if (added) "已加入我喜欢" else "已取消喜欢")
                            },
                            onDownload = { song -> startDownload(song) },
                            // v2.21.4：搜索结果直接下载该曲歌词（.lrc 存下载目录，无需播放）
                            onDownloadLyric = { song -> downloadLyricFile(song) },
                            downloadOf = { key -> downloads.firstOrNull { it.song.key == key } }
                        )
                        Page.FAVORITES -> LibraryPage(
                            title = "我喜欢",
                            songs = favorites,
                            favKeys = favKeys,
                            currentKey = engine.currentSong?.key,
                            isPlaying = engine.isPlaying,
                            onPlay = { song -> engine.play(song, favorites) },
                            onPlayAll = { engine.playAll(favorites) },
                            onToggleFav = { song ->
                                engine.store.toggleFavorite(song)
                                refreshLibrary()
                            },
                            onDownload = { song -> startDownload(song) },
                            downloadOf = { key -> downloads.firstOrNull { it.song.key == key } }
                        )
                        Page.RECENT -> LibraryPage(
                            title = "最近播放",
                            songs = recent,
                            favKeys = favKeys,
                            currentKey = engine.currentSong?.key,
                            isPlaying = engine.isPlaying,
                            onPlay = { song -> engine.play(song, recent) },
                            onPlayAll = { engine.playAll(recent) },
                            onToggleFav = { song ->
                                engine.store.toggleFavorite(song)
                                refreshLibrary()
                            },
                            onDownload = { song -> startDownload(song) },
                            downloadOf = { key -> downloads.firstOrNull { it.song.key == key } }
                        )
                        Page.LOCAL -> LocalPage(
                            songs = localSongs,
                            scanning = scanning,
                            onScan = { scanLocal() },
                            favKeys = favKeys,
                            currentKey = engine.currentSong?.key,
                            isPlaying = engine.isPlaying,
                            onPlay = { song -> engine.play(song, localSongs) },
                            onPlayAll = { engine.playAll(localSongs) },
                            onToggleFav = { song ->
                                engine.store.toggleFavorite(song)
                                refreshLibrary()
                            }
                        )
                        Page.DOWNLOADS -> DownloadsPage(
                            items = downloads,
                            saveDir = musicDownloadDir(context).absolutePath,
                            onRetry = { item -> startDownload(item.song) }
                        )
                        Page.SETTINGS -> SettingsPage(
                            settings = musicSettings,
                            onChange = { updateSettings(it) },
                            onPickLyricImage = { openDesktopPicker("lyricImage", "选择歌词秀背景图片", "image") },
                            onPickHomeImage = { openDesktopPicker("homeImage", "选择主页背景图片", "image") },
                            onPickCoverImage = { openDesktopPicker("coverImage", "选择歌词秀封面图片", "image") },
                            onPickDiscImage = { openDesktopPicker("discImage", "选择歌词秀光盘图片", "image") },
                            onPickFolder = { openDesktopPicker("folder", "选择要扫描的音乐文件夹", "dir") },
                            onRescan = { scanLocal() },
                            lyricLocked = lyricOverlayState.locked,
                            onLyricLockChange = { updateLyricLock(it) }
                        )
                    }
                }
            }

            // ===== 3D 歌词秀覆盖层 =====
            // 注意：AnimatedVisibility 是 ColumnScope 扩展，此处外层为 Box(内含 Column)，
            // 必须用显式 Column 提供最近的 ColumnScope 接收者，否则编译报
            // "can't be called in this context by implicit receiver"
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = showLyrics,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Lyrics3DPage(
                        song = engine.currentSong,
                        positionMs = engine.positionMs,
                        durationMs = engine.durationMs,
                        isPlaying = engine.isPlaying,
                        isPreparing = engine.isPreparing,
                        lyric = engine.lyricDoc,
                        lyricLoading = engine.lyricLoading,
                        playMode = engine.playMode,
                        settingsProvider = { musicSettings },
                        positionProvider = { engine.rawPositionMs() },
                        onSeek = { engine.seekTo(it) },
                        onToggle = { engine.toggle() },
                        onNext = { engine.next() },
                        onPrev = { engine.prev() },
                        onCycleMode = { engine.cycleMode() },
                        onDownloadLyric = { downloadLyricFile(engine.currentSong) },
                        onClose = { showLyrics = false },
                        // v2.21.3：真全屏（F11 风格，隐藏标题栏/任务栏占满整屏）；返回键退出
                        isTrueFullscreen = scope.windowState.isTrueFullscreen,
                        onToggleFullscreen = {
                            WindowManager.get().toggleTrueFullscreen(scope.windowState.id)
                        }
                    )
                }
            }
        }

        // v2.21.3：歌词秀真全屏时系统返回键退出全屏（恢复原窗口）
        BackHandler(enabled = showLyrics && scope.windowState.isTrueFullscreen) {
            WindowManager.get().toggleTrueFullscreen(scope.windowState.id)
        }

        // ===== 底部播放条 =====
        // v2.21.4：3D 歌词页打开时隐藏底栏 —— 歌词页自带完整控制区（进度/播放/切歌），
        // 底栏叠在其上属于遮挡 bug（全屏下方不应出现任何内容）
        if (!showLyrics) {
            PlayerBar(
                engine = engine,
                isFav = engine.currentSong?.let { favKeys.contains(it.key) } ?: false,
                customBg = homeCustomBg,
                onToggleFav = {
                    engine.currentSong?.let { song ->
                        engine.store.toggleFavorite(song)
                        refreshLibrary()
                    }
                },
                lyricsOpen = showLyrics,
                onToggleLyrics = { showLyrics = !showLyrics }
            )
        }
    }
    } // 关闭自定义背景布局 Box
    } // v2.21.5：CompositionLocalProvider(LocalHomeCustomBg)
}

/** mutableIntStateOf 兼容助手已移除：统一使用 mutableStateOf */

// ==================== 左侧导航栏 ====================

@Composable
private fun Sidebar(
    page: Page,
    onPageChange: (Page) -> Unit,
    customBg: Boolean
) {
    Column(
        Modifier
            .width(170.dp)
            .fillMaxSize()
            // v2.19：自定义主页背景时侧栏半透明白，让背景全局透出（含菜单区）
            .background(if (customBg) Color.White.copy(alpha = 0.72f) else Mc.sidebarBg)
            .padding(vertical = 14.dp, horizontal = 10.dp)
    ) {
        // Logo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Mc.red),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "LinBox 云音乐",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Mc.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(18.dp))
        NavSectionLabel("在线音乐")
        NavItem("搜索音乐", Icons.Filled.Search, page == Page.SEARCH) { onPageChange(Page.SEARCH) }
        Spacer(Modifier.height(14.dp))
        NavSectionLabel("我的音乐")
        NavItem("我喜欢", Icons.Filled.Favorite, page == Page.FAVORITES) { onPageChange(Page.FAVORITES) }
        NavItem("最近播放", Icons.Filled.History, page == Page.RECENT) { onPageChange(Page.RECENT) }
        NavItem("本地音乐", Icons.Filled.LibraryMusic, page == Page.LOCAL) { onPageChange(Page.LOCAL) }
        NavItem("下载管理", Icons.Filled.Download, page == Page.DOWNLOADS) { onPageChange(Page.DOWNLOADS) }

        Spacer(Modifier.weight(1f))
        NavItem("设置", Icons.Filled.Settings, page == Page.SETTINGS) { onPageChange(Page.SETTINGS) }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "音源：酷我 / 网易云 / QQ / LRCLIB\nLinBox v2.19",
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = Mc.textTertiary
        )
    }
}

@Composable
private fun NavSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = Mc.textTertiary,
        modifier = Modifier.padding(start = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun NavItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0x1AEC4141) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Mc.red else Mc.textSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (selected) Mc.red else Mc.textPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ==================== 底部播放条（对照图2 底栏） ====================

@Composable
private fun PlayerBar(
    engine: MusicEngine,
    isFav: Boolean,
    customBg: Boolean,
    onToggleFav: () -> Unit,
    lyricsOpen: Boolean,
    onToggleLyrics: () -> Unit
) {
    val song = engine.currentSong
    var userSeeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableStateOf(0f) }

    Column(
        Modifier
            .fillMaxWidth()
            // v2.19：自定义主页背景时底栏半透明白，让背景全局透出（含控制区）
            .background(if (customBg) Color.White.copy(alpha = 0.80f) else Color.White)
            .padding(top = 6.dp)
    ) {
        // 进度条（细线，红色）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fmtTime(if (userSeeking) seekPos.toLong() else engine.positionMs),
                fontSize = 10.sp,
                color = Mc.textTertiary
            )
            Slider(
                value = if (userSeeking) seekPos else {
                    engine.positionMs.coerceAtMost(engine.durationMs).toFloat()
                },
                valueRange = 0f..engine.durationMs.coerceAtLeast(1L).toFloat(),
                onValueChange = {
                    userSeeking = true
                    seekPos = it
                },
                onValueChangeFinished = {
                    engine.seekTo(seekPos.toLong())
                    userSeeking = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Mc.red,
                    activeTrackColor = Mc.red,
                    inactiveTrackColor = Color(0xFFE5E5E8)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(16.dp)
                    .padding(horizontal = 6.dp)
            )
            Text(
                text = fmtTime(engine.durationMs),
                fontSize = 10.sp,
                color = Mc.textTertiary
            )
        }

        // 主体：封面+歌名 | 控制键 | 词/模式/音量
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面（点击打开歌词秀）
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onToggleLyrics)
            ) {
                AsyncCover(url = song?.picUrl, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.width(10.dp))
            // 歌名 + 歌手
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = song?.name ?: "未在播放",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Mc.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song?.let { "${it.artist} · ${it.album.ifBlank { "未知专辑" }}" } ?: "云音乐，发现好音乐",
                    fontSize = 10.sp,
                    color = Mc.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 收藏
            Icon(
                imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "喜欢",
                tint = if (isFav) Mc.red else Mc.textSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onToggleFav)
            )
            Spacer(Modifier.width(14.dp))

            // 上一首 / 播放 / 下一首
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = "上一首",
                tint = Mc.textPrimary,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { engine.prev() }
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Mc.red)
                    .clickable { engine.toggle() },
                contentAlignment = Alignment.Center
            ) {
                if (engine.isPreparing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = if (engine.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (engine.isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "下一首",
                tint = Mc.textPrimary,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { engine.next() }
            )
            Spacer(Modifier.width(14.dp))

            // 词 按钮（打开/关闭 3D 歌词秀）
            Box(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (lyricsOpen) Mc.red else Color.Transparent)
                    .clickable(onClick = onToggleLyrics)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "词",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (lyricsOpen) Color.White else Mc.textSecondary
                )
            }
            Spacer(Modifier.width(12.dp))

            // 播放模式
            Icon(
                imageVector = when (engine.playMode) {
                    MusicStore.MODE_LOOP_ONE -> Icons.Filled.RepeatOne
                    MusicStore.MODE_SHUFFLE -> Icons.Filled.Shuffle
                    else -> Icons.Filled.Repeat
                },
                contentDescription = "播放模式",
                tint = Mc.textSecondary,
                modifier = Modifier
                    .size(17.dp)
                    .clickable { engine.cycleMode() }
            )
            Spacer(Modifier.width(12.dp))

            // 音量
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "音量",
                tint = Mc.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Slider(
                value = engine.volume,
                valueRange = 0f..1f,
                onValueChange = { engine.volume = it },
                colors = SliderDefaults.colors(
                    thumbColor = Mc.textSecondary,
                    activeTrackColor = Mc.textSecondary,
                    inactiveTrackColor = Color(0xFFE5E5E8)
                ),
                modifier = Modifier
                    .width(76.dp)
                    .height(16.dp)
            )
        }
    }
}
