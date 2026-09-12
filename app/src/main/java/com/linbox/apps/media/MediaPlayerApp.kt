package com.linbox.apps.media

import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.linbox.apps.filemanager.FilePickBus
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppDef
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import com.linbox.core.window.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

val MediaPlayerApp = AppDef(
    id = "media_player",
    displayName = "媒体播放器",
    iconAsset = "app:media_player",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 760.dp,
    defaultHeight = 540.dp,
    pinnedToDesktop = true
) { scope ->
    MediaPlayerContent(scope)
}

/**
 * 媒体播放器（v2.9 新增）：
 * - 音乐播放：MediaPlayer + 播放列表（上一曲/下一曲/自动连播）
 * - 视频播放：VideoView + 自定义控制栏（播放/暂停/进度拖拽）
 * - 媒体库：扫描 Music / Movies / Download / DCIM 目录下的音频与视频文件
 * - 打开文件：应用内文件资源管理器选择音频/视频（v2.14.10 起不再
 *   拉起手机系统文件管理器/SAF，与桌面环境体验一致）
 * - 入口：桌面图标、文件资源管理器（点击音频/视频文件）、开始菜单
 */
private val AUDIO_EXTS = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "mid", "wma", "opus")
private val VIDEO_EXTS = setOf("mp4", "avi", "mov", "mkv", "3gp", "webm", "ts", "m4v", "flv")

private fun isVideoName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS

private fun isMediaFile(file: File): Boolean {
    if (!file.isFile) return false
    val ext = file.extension.lowercase()
    return ext in AUDIO_EXTS || ext in VIDEO_EXTS
}

/** 单个可播放媒体（本地文件或 SAF content URI） */
private data class MediaSource(
    val uri: Uri,
    val displayName: String,
    val isVideo: Boolean
)

@Composable
private fun MediaPlayerContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val wm = remember { WindowManager.get() }

    // ===== 视频真全屏状态（v2.10：隐藏顶部栏，占满整屏） =====
    var wmRevision by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { wm.observe { wmRevision++ } }
    val isTrueFs = remember(wmRevision) { scope.windowState.isTrueFullscreen }
    BackHandler(enabled = isTrueFs) { wm.toggleTrueFullscreen(scope.windowState.id) }

    // ===== 状态 =====
    var library by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
    var scanTick by remember { mutableStateOf(0) }
    var current by remember { mutableStateOf<MediaSource?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var userSeeking by remember { mutableStateOf(false) }

    // 播放代号（v2.10）：每次新播放自增，旧源的异步回调（含被中断的 prepare 错误）
    // 一律静默丢弃 —— 修复“已正常播放却提示音频播放失败”的误报
    val playGen = remember { mutableStateOf(0) }

    // 音频播放器（音频走 MediaPlayer，视频走 VideoView）
    val audioPlayer = remember { MediaPlayer() }
    val videoViewRef = remember { mutableStateOf<VideoView?>(null) }
    // 记录 VideoView 当前已装载的 URI，避免重组时重复 setVideoURI 导致从头播放
    var videoLoadedUri by remember { mutableStateOf<Uri?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                videoViewRef.value?.stopPlayback()
                if (audioPlayer.isPlaying) audioPlayer.stop()
                audioPlayer.release()
            }
        }
    }

    // ===== 媒体库扫描 =====
    LaunchedEffect(scanTick) {
        library = withContext(Dispatchers.IO) {
            val root = File("/storage/emulated/0/")
            val dirs = listOf("Music", "Movies", "Download", "DCIM")
            dirs.flatMap { dir ->
                val d = File(root, dir)
                if (d.isDirectory) {
                    runCatching {
                        d.listFiles()?.filter { it.isFile && isMediaFile(it) } ?: emptyList()
                    }.getOrDefault(emptyList())
                } else emptyList()
            }.map { MediaSource(Uri.fromFile(it), it.name, isVideoName(it.name)) }
                .sortedWith(compareByDescending<MediaSource> { it.isVideo }.thenBy { it.displayName.lowercase() })
        }
    }

    // ===== 停止 / 播放控制 =====
    fun stopAll() {
        // 使旧源的异步回调全部失效（v2.10 误报修复关键）
        playGen.value++
        runCatching {
            // 先解绑监听器，再停止/重置：避免被中断的 prepare 异步报错触发错误回调
            audioPlayer.setOnErrorListener(null)
            audioPlayer.setOnCompletionListener(null)
            audioPlayer.setOnPreparedListener(null)
            videoViewRef.value?.stopPlayback()
            if (audioPlayer.isPlaying) audioPlayer.stop()
            audioPlayer.reset()
        }
        videoLoadedUri = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }

    fun playSource(src: MediaSource) {
        stopAll()
        current = src
        scope.onTitleChange("媒体播放器 - ${src.displayName}")
        if (src.isVideo) {
            // 视频：由 AndroidView 的 update 块装载 URI（VideoView 尚未创建时等创建后装载）
            videoLoadedUri = null
            isPlaying = true
        } else {
            // 音频：带代号的装载，陈旧回调静默丢弃；瞬时错误自动重试一次（v2.10）
            val gen = ++playGen.value
            var retried = false
            fun startAudio() {
                audioPlayer.reset()
                audioPlayer.setDataSource(context, src.uri)
                audioPlayer.setOnPreparedListener { mp ->
                    if (gen != playGen.value) return@setOnPreparedListener
                    durationMs = mp.duration.toLong()
                    mp.start()
                    isPlaying = true
                }
                audioPlayer.setOnCompletionListener {
                    if (gen != playGen.value) return@setOnCompletionListener
                    isPlaying = false
                    positionMs = it.duration.toLong()
                }
                audioPlayer.setOnErrorListener { _, what, extra ->
                    when {
                        // 旧源的陈旧错误（已被新播放取代/中断）：静默吞掉，不提示
                        gen != playGen.value -> Unit
                        // 本地文件首载常见瞬时错误：自动重试一次
                        !retried -> {
                            retried = true
                            runCatching { startAudio() }.onFailure {
                                isPlaying = false
                                Toast.makeText(context, "音频播放失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                        // 部分设备在播放临近结束时回调 error：视为自然播完
                        durationMs > 0 && positionMs >= durationMs - 2000 -> {
                            isPlaying = false
                            positionMs = durationMs
                        }
                        else -> {
                            Toast.makeText(context, "音频播放失败 ($what/$extra)", Toast.LENGTH_SHORT).show()
                            isPlaying = false
                        }
                    }
                    true
                }
                audioPlayer.prepareAsync()
            }
            runCatching { startAudio() }
                .onFailure {
                    Toast.makeText(context, "无法播放: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun togglePlayPause() {
        val src = current ?: return
        runCatching {
            if (src.isVideo) {
                val vv = videoViewRef.value ?: return
                if (vv.isPlaying) {
                    vv.pause(); isPlaying = false
                } else {
                    vv.start(); isPlaying = true
                }
            } else {
                if (audioPlayer.isPlaying) {
                    audioPlayer.pause(); isPlaying = false
                } else {
                    audioPlayer.start(); isPlaying = true
                }
            }
        }
    }

    fun seekTo(ms: Long) {
        val src = current ?: return
        runCatching {
            if (src.isVideo) videoViewRef.value?.seekTo(ms.toInt())
            else audioPlayer.seekTo(ms.toInt())
            positionMs = ms
        }
    }

    // ===== 播放列表导航（媒体库内上一曲/下一曲） =====
    fun playNext() {
        val src = current ?: return
        val idx = library.indexOfFirst { it.uri == src.uri }
        if (library.isNotEmpty()) {
            val next = if (idx >= 0 && idx < library.size - 1) library[idx + 1] else library.first()
            playSource(next)
        }
    }

    fun playPrev() {
        val src = current ?: return
        val idx = library.indexOfFirst { it.uri == src.uri }
        if (library.isNotEmpty()) {
            val prev = if (idx > 0) library[idx - 1] else library.last()
            playSource(prev)
        }
    }

    // ===== 打开文件（v2.14.10：改用应用内文件资源管理器，不再拉起系统文件选择器） =====
    // 「打开文件」拉起文件资源管理器媒体选择模式（初始直达 Music）；
    // 用户点选音频/视频后经 FilePickBus 回传本窗口直接播放；
    // 本窗口已关闭/最小化时由资源管理器兜底开新窗口（launchArgs.path）。
    fun openMediaPicker() {
        wm.open(
            appId = "file_explorer",
            title = "选择媒体文件",
            launchMode = AppRegistry.get("file_explorer")?.launchMode ?: LaunchMode.FLOATING,
            launchArgs = mapOf(
                "pickMode" to "media",
                "targetApp" to "media_player",
                "targetWindow" to scope.windowState.id
            ),
            initialWidth = 920,
            initialHeight = 620
        )
    }

    // 文件选择总线：资源管理器选中媒体文件 → 回传本窗口播放
    DisposableEffect(Unit) {
        val unlisten = FilePickBus.listen("media_player", scope.windowState.id) { path ->
            val f = File(path)
            if (f.exists() && f.isFile) {
                playSource(MediaSource(Uri.fromFile(f), f.name, isVideoName(f.name)))
            } else {
                Toast.makeText(context, "文件不存在: $path", Toast.LENGTH_SHORT).show()
            }
        }
        onDispose { unlisten() }
    }

    // 初始文件（文件资源管理器点击音频/视频打开）
    val initialPath = scope.windowState.launchArgs["path"]
    LaunchedEffect(initialPath) {
        if (initialPath != null && current == null) {
            val f = File(initialPath)
            if (f.exists()) {
                playSource(MediaSource(Uri.fromFile(f), f.name, isVideoName(f.name)))
            } else {
                Toast.makeText(context, "文件不存在: $initialPath", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 进度轮询（音频 + 视频） =====
    LaunchedEffect(current, isPlaying) {
        while (isActive) {
            val src = current
            if (src != null && !userSeeking) {
                runCatching {
                    positionMs = if (src.isVideo) {
                        videoViewRef.value?.currentPosition?.toLong() ?: 0L
                    } else {
                        if (audioPlayer.isPlaying) audioPlayer.currentPosition.toLong() else positionMs
                    }
                    if (durationMs <= 0) {
                        durationMs = if (src.isVideo) {
                            videoViewRef.value?.duration?.toLong()?.takeIf { it > 0 } ?: 0L
                        } else {
                            audioPlayer.duration.toLong().takeIf { it > 0 } ?: 0L
                        }
                    }
                }
            }
            delay(400)
        }
    }

    // ===== 布局 =====
    Column(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {

        // ===== 顶部栏（真全屏时隐藏，v2.10） =====
        if (!isTrueFs) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(theme.windowTitleBarColor)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.PlayCircle, null,
                    tint = theme.accentColor, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "媒体播放器",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                if (current != null) {
                    TextButton(onClick = {
                        stopAll()
                        current = null
                        scope.onTitleChange("媒体播放器")
                    }) {
                        Icon(Icons.Default.LibraryMusic, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("媒体库", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { openMediaPicker() }) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("打开文件", fontSize = 12.sp)
                }
            }

            HorizontalDivider(color = theme.dividerColor, thickness = 0.5.dp)
        }

        // ===== 主区域 =====
        val src = current
        if (src == null) {
            MediaLibraryView(
                library = library,
                theme = theme,
                onPlay = { playSource(it) },
                onRefresh = { scanTick++ }
            )
        } else if (src.isVideo) {
            VideoPlayerView(
                source = src,
                videoViewRef = videoViewRef,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                isFullscreen = isTrueFs,
                theme = theme,
                onLoaded = { uri -> videoLoadedUri = uri },
                alreadyLoaded = videoLoadedUri == src.uri,
                onToggle = { togglePlayPause() },
                onSeek = { seekTo(it) },
                onSeekStart = { userSeeking = true },
                onSeekEnd = { userSeeking = false },
                onPrev = { playPrev() },
                onNext = { playNext() },
                onCompleted = { playNext() },
                onToggleFullscreen = { wm.toggleTrueFullscreen(scope.windowState.id) }
            )
        } else {
            AudioPlayerView(
                source = src,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                theme = theme,
                onToggle = { togglePlayPause() },
                onSeek = { seekTo(it) },
                onSeekStart = { userSeeking = true },
                onSeekEnd = { userSeeking = false },
                onPrev = { playPrev() },
                onNext = { playNext() }
            )
        }
    }
}

// ============================================================
// 媒体库视图
// ============================================================

@Composable
private fun MediaLibraryView(
    library: List<MediaSource>,
    theme: com.linbox.core.theme.WinTheme,
    onPlay: (MediaSource) -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "媒体库 (${library.size})",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("重新扫描", fontSize = 12.sp)
            }
        }
        Text(
            "自动扫描 Music / Movies / Download / DCIM 目录",
            color = theme.secondaryTextColor,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(4.dp))

        if (library.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.LibraryMusic, null,
                        tint = theme.secondaryTextColor,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("未找到媒体文件", color = theme.secondaryTextColor, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点击右上角「打开文件」，从文件资源管理器选择音频或视频",
                        color = theme.secondaryTextColor,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(library, key = { it.uri.toString() }) { item ->
                    MediaLibraryRow(item, theme) { onPlay(item) }
                }
            }
        }
    }
}

@Composable
private fun MediaLibraryRow(
    item: MediaSource,
    theme: com.linbox.core.theme.WinTheme,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (item.isVideo) Color(0xFF0078D4).copy(alpha = 0.12f)
                    else theme.accentColor.copy(alpha = 0.12f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (item.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                null,
                tint = if (item.isVideo) Color(0xFF0078D4) else theme.accentColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.displayName,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (item.isVideo) "视频" else "音频",
                color = theme.secondaryTextColor,
                fontSize = 10.sp
            )
        }
        Icon(
            Icons.Default.PlayArrow, null,
            tint = theme.secondaryTextColor,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ============================================================
// 音频播放视图
// ============================================================

@Composable
private fun AudioPlayerView(
    source: MediaSource,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    theme: com.linbox.core.theme.WinTheme,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 唱片图标
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(theme.accentColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MusicNote, null,
                tint = theme.accentColor,
                modifier = Modifier.size(56.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            source.displayName,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text("音频", color = theme.secondaryTextColor, fontSize = 11.sp)

        Spacer(Modifier.height(20.dp))

        // 进度条
        MediaProgressSlider(
            positionMs = positionMs,
            durationMs = durationMs,
            theme = theme,
            onSeek = onSeek,
            onSeekStart = onSeekStart,
            onSeekEnd = onSeekEnd
        )

        Spacer(Modifier.height(12.dp))

        // 控制按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            IconButton(onClick = onPrev) {
                Icon(
                    Icons.Default.SkipPrevious, "上一曲",
                    tint = if (theme.isDark) Color.White else Color.Black
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(theme.accentColor)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "播放/暂停",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    Icons.Default.SkipNext, "下一曲",
                    tint = if (theme.isDark) Color.White else Color.Black
                )
            }
        }
    }
}

// ============================================================
// 视频播放视图
// ============================================================

@Composable
private fun VideoPlayerView(
    source: MediaSource,
    videoViewRef: androidx.compose.runtime.MutableState<VideoView?>,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    isFullscreen: Boolean,
    theme: com.linbox.core.theme.WinTheme,
    onLoaded: (Uri) -> Unit,
    alreadyLoaded: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCompleted: () -> Unit,
    onToggleFullscreen: () -> Unit
) {
    // 当前已装载 URI + 单次重试标记（v2.10：本地视频首载常见瞬时错误 1/-2147483648，
    // 重试装载一次即可正常播放）
    val loadedUri = remember { mutableStateOf<Uri?>(null) }
    val retriedFor = remember { mutableStateOf<Uri?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 视频画面区：单击 播放/暂停（控制栏为兄弟节点，不会误触）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onToggle() })
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        videoViewRef.value = this
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            this.start()
                        }
                        setOnCompletionListener { onCompleted() }
                        setOnErrorListener { _, what, extra ->
                            val uri = loadedUri.value
                            if (uri != null && retriedFor.value != uri) {
                                // 首次错误：自动重试装载一次（多数瞬时错误重试即可播放）
                                retriedFor.value = uri
                                this.setVideoURI(uri)
                            } else {
                                Toast.makeText(ctx, "视频播放失败 ($what/$extra)", Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                    }
                },
                update = { vv ->
                    if (!alreadyLoaded) {
                        loadedUri.value = source.uri
                        vv.setVideoURI(source.uri)
                        onLoaded(source.uri)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ===== 底部控制栏（半透明覆盖） =====
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            MediaProgressSlider(
                positionMs = positionMs,
                durationMs = durationMs,
                theme = theme,
                onSeek = onSeek,
                onSeekStart = onSeekStart,
                onSeekEnd = onSeekEnd,
                lightOnDark = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrev) {
                    Icon(Icons.Default.SkipPrevious, "上一个", tint = Color.White)
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "播放/暂停", tint = Color.White
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, "下一个", tint = Color.White)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    source.displayName,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // ===== 全屏按钮（v2.10）：窗口占满整屏（隐藏任务栏/标题栏），返回键退出 =====
                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        if (isFullscreen) "退出全屏" else "全屏",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * 通用进度条（音频/视频共用，v2.10 重写）：
 * - 拖动期间只在本地更新拖动位置（不连续 seek），抬手时才执行一次 seek
 *   —— 修复旧版 onSeek 值计算错误（毫秒×时长导致快进失效）与拖动卡顿
 */
@Composable
private fun MediaProgressSlider(
    positionMs: Long,
    durationMs: Long,
    theme: com.linbox.core.theme.WinTheme,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    lightOnDark: Boolean = false
) {
    val sliderColor = if (lightOnDark) Color.White else theme.accentColor
    val textColor = if (lightOnDark) Color.White else theme.secondaryTextColor
    val dur = if (durationMs > 0) durationMs else 1L

    // 拖动中的本地位置；null = 未拖动，跟随播放进度
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val currentValue = dragValue ?: positionMs.coerceIn(0L, dur).toFloat()

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = currentValue.coerceIn(0f, dur.toFloat()),
            onValueChange = {
                dragValue = it
                onSeekStart()
            },
            onValueChangeFinished = {
                // 抬手：一次性 seek 到拖动目标位置
                dragValue?.let { v -> onSeek(v.toLong()) }
                dragValue = null
                onSeekEnd()
            },
            valueRange = 0f..dur.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = sliderColor,
                activeTrackColor = sliderColor
            )
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(formatTime(currentValue.toLong()), color = textColor, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(formatTime(durationMs), color = textColor, fontSize = 10.sp)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
