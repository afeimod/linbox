package com.linbox.apps.music

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 应用级播放引擎持有者（v2.22.3/fix9.11）：
 *
 * 修复反馈：“音乐播放器最小化后就强制关闭音乐了，而且桌面歌词也被关闭了”。
 * 旧实现引擎随窗口组合创建/销毁（MusicContent 内 remember +
 * DisposableEffect.dispose），而桌面窗口系统最小化即不渲染
 * （WindowHost 只渲染可见窗口）→ 组合销毁 → engine.dispose() 停播 +
 * 歌词服务被拉停。
 *
 * 现引擎升级为进程级单例：最小化只销毁窗口 UI，播放、进度记忆与
 * 桌面歌词总线（DesktopLyricBus）由引擎在后台 scope 继续推进；
 * 显式关闭最后一个播放器窗口时才经 [shutdown] 停止（保留
 * v2.21.2 语义“关闭播放器即停止音乐与桌面歌词”）。
 */
object MusicEngineHolder {

    @Volatile
    private var instance: MusicEngine? = null

    fun obtain(context: Context): MusicEngine =
        instance ?: synchronized(this) {
            instance ?: MusicEngine(context.applicationContext).also { instance = it }
        }

    fun peek(): MusicEngine? = instance

    /** 停止播放并释放引擎（显式关闭最后一个播放器窗口时调用）。 */
    fun shutdown() {
        synchronized(this) {
            instance?.dispose()
            instance = null
        }
    }
}

/**
 * 云音乐播放引擎（v2.17；v2.22.3 引擎级歌词与总线推进）：
 * - 基于 android.media.MediaPlayer 的流媒体播放（酷我解析直链 / 本地 URI）
 * - 播放代号（gen）防竞态：连续切歌时旧任务的异步回调全部静默丢弃
 *   （与项目中媒体播放器 v2.10 的 playGen 方案一致）
 * - 播放队列 + 三种模式：顺序播放 / 单曲循环 / 随机播放
 * - 500ms 步进的进度上报（Compose State，UI 直接读取）
 * - v2.22.3（fix9.11）：歌词拉取与 DesktopLyricBus 推进收编到引擎 ——
 *   播放器窗口最小化（组合销毁）后歌词照常推进、桌面歌词悬浮窗不中断
 */
class MusicEngine(context: Context) {

    private val appContext = context.applicationContext
    val store = MusicStore(appContext)

    // ==================== UI 可观察状态 ====================

    /** 当前歌曲（点击后立即置位，链接解析中 UI 即可反馈） */
    var currentSong by mutableStateOf<SongInfo?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isPreparing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    /** 播放失败提示（UI 展示 Toast / 内联提示后调用 clearError） */
    var playError by mutableStateOf<String?>(null)
        private set

    /** 播放模式：顺序 / 单曲循环 / 随机 */
    var playMode by mutableStateOf(store.loadPlayMode())
        private set

    // ==================== 歌词状态（v2.22.3 引擎级，UI 直读） ====================

    /** 当前曲歌词文档（引擎自动拉取；含深度下载兑底） */
    var lyricDoc by mutableStateOf<LyricsDoc?>(null)
        private set
    var lyricLoading by mutableStateOf(false)
        private set

    /** 歌词拉取代号：切歌/强制刷新防竞态（与播放 gen 同思路） */
    private var lyricGen = 0

    private val volumeState = mutableStateOf(0.8f)

    /** 音量 0..1 */
    var volume: Float
        get() = volumeState.value
        set(value) {
            val v = value.coerceIn(0f, 1f)
            volumeState.value = v
            runCatching { player.setVolume(v, v) }
        }

    /** 当前播放队列与索引（供上一曲/下一曲可用性判断） */
    val queue = mutableStateListOf<SongInfo>()
    var queueIndex by mutableStateOf(-1)
        private set

    /**
     * 实时进度（v2.21）：直读 MediaPlayer 原生位置，供 KTV 逐字填充等高频场景；
     * 暂停/读取失败时回退到 500ms 步进的 positionMs 状态
     */
    fun rawPositionMs(): Long = runCatching {
        if (isPlaying) player.currentPosition.toLong() else positionMs
    }.getOrDefault(positionMs)

    // ==================== 内部 ====================

    private val player = MediaPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    /** 播放代号：每次发起加载自增，旧回调直接丢弃 */
    private var gen = 0

    /** 当前正在 prepare 的代号（onPrepared/onError 回调时与 gen 比对防竞态） */
    private var pendingGen = 0

    /** v2.21.2：上次会话已装入队列但尚未起播；首次点播放时先加载并跳转到记忆进度 */
    private var restorePending = false

    /** v2.21.2：prepare 完成后待跳转的进度（恢复播放用，普通播放为 0） */
    private var seekOnPreparedMs = 0L

    /** v2.21.2：会话落盘节流起点（elapsedRealtime ms） */
    private var lastSessionSaveAt = 0L

    init {
        player.setOnCompletionListener { onCompleted() }
        player.setOnPreparedListener { mp ->
            // 仅当仍是当前这次加载时才生效（快速切歌时旧回调丢弃）
            if (isPreparing && pendingGen == gen) {
                isPreparing = false
                durationMs = runCatching { mp.duration.toLong() }.getOrDefault(durationMs)
                // v2.21.2：恢复播放 —— prepared 后先跳转到记忆进度再起播
                if (seekOnPreparedMs > 0L) {
                    val target = seekOnPreparedMs.coerceIn(0L, (durationMs - 1000L).coerceAtLeast(0L))
                    seekOnPreparedMs = 0L
                    runCatching { mp.seekTo(target.toInt()) }
                    positionMs = target
                }
                runCatching { mp.start() }
                isPlaying = true
                startTicker()
                currentSong?.let { store.pushRecent(it) }
            }
        }
        player.setOnErrorListener { _, what, extra ->
            if (pendingGen == gen && gen > 0) {
                isPreparing = false
                isPlaying = false
                playError = "播放出错 (code=$what/$extra)"
            }
            true
        }
        // v2.21.2：启动即装回上次退出前的播放会话（暂停待播，点播放从记忆进度继续）
        restoreSession()
    }

    // ==================== 播放入口 ====================

    /**
     * 播放指定歌曲。
     * @param list 非空时替换整个播放队列（点击列表行 / 播放全部）
     */
    fun play(song: SongInfo, list: List<SongInfo>? = null) {
        if (list != null) {
            queue.clear()
            queue.addAll(list)
            queueIndex = list.indexOfFirst { it.key == song.key }.takeIf { it >= 0 } ?: 0
        } else {
            val exist = queue.indexOfFirst { it.key == song.key }
            if (exist < 0) {
                queue.add(song)
                queueIndex = queue.size - 1
            } else {
                queueIndex = exist
            }
        }
        loadAndPlay(song)
    }

    /** 播放全部 */
    fun playAll(list: List<SongInfo>) {
        if (list.isEmpty()) return
        play(list[0], list)
    }

    /** 播放/暂停切换 */
    fun toggle() {
        val song = currentSong ?: return
        // v2.21.2：恢复会话的首次播放 —— 按普通流程加载源，prepared 后自动 seek 到记忆进度
        if (restorePending) {
            restorePending = false
            loadAndPlay(song, resumeMs = seekOnPreparedMs)
            return
        }
        runCatching {
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
                ticker?.cancel()
                saveSession(force = true)
                // v2.22.3：暂停时同步总线（悬浮窗 KTV 冻结在当前进度）
                pushLyricBus()
            } else {
                player.start()
                isPlaying = true
                startTicker()
            }
        }
    }

    /** 跳转播放位置 */
    fun seekTo(ms: Long) {
        val safe = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        runCatching {
            player.seekTo(safe.toInt())
            positionMs = safe
        }
        saveSession()
        // v2.22.3：跳转后同步总线（悬浮窗跟随进度/歌词行）
        pushLyricBus()
    }

    /** 上一曲（用户点击，循环队列） */
    fun prev() {
        if (queue.isEmpty()) return
        val i = if (queueIndex <= 0) queue.size - 1 else queueIndex - 1
        queueIndex = i
        loadAndPlay(queue[i])
    }

    /** 下一曲；auto=true 表示自动连播（顺序模式播完即停） */
    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (auto && playMode == MusicStore.MODE_ORDER) {
            if (queueIndex + 1 >= queue.size) {
                // 顺序播放到队尾：停止
                isPlaying = false
                positionMs = durationMs
                return
            }
        }
        val i = when {
            playMode == MusicStore.MODE_SHUFFLE && queue.size > 1 -> {
                var r = queueIndex
                while (r == queueIndex) r = (0 until queue.size).random()
                r
            }
            queueIndex + 1 >= queue.size -> if (auto) return else 0
            else -> queueIndex + 1
        }
        queueIndex = i
        loadAndPlay(queue[i])
    }

    /** 切换播放模式并持久化 */
    fun cycleMode() {
        playMode = (playMode + 1) % 3
        store.savePlayMode(playMode)
    }

    fun clearError() {
        playError = null
    }

    // ==================== 歌词拉取与桌面歌词总线（v2.22.3/fix9.11） ====================
    // 旧实现由播放器窗口组合内的 LaunchedEffect 驱动：最小化即组合销毁，
    // 总线停止更新 → 桌面歌词冻结/关闭。现收编到引擎（独立 scope），
    // 窗口是否存在不再影响歌词与悬浮窗。

    /**
     * 拉取当前曲歌词：缓存优先；未命中走深度下载兑底（扩展关键词 +
     * 全词源宽松匹配，命中即写缓存并提示）。force=true 跳过缓存
     * （手动下载歌词后刷新用）。
     */
    fun refreshLyrics(force: Boolean = false) {
        val song = currentSong ?: return
        val myGen = ++lyricGen
        lyricDoc = null
        lyricLoading = true
        scope.launch {
            val enginePref = store.loadMusicSettings().lyricEngine
            var doc = fetchLyrics(store, song, force = force, engine = enginePref)
            if (doc == null) {
                doc = fetchLyricsDeep(store, song, force = true, engine = enginePref)
                if (doc != null) {
                    runCatching {
                        Toast.makeText(appContext, "已自动下载歌词：${song.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (myGen != lyricGen) return@launch
            lyricDoc = doc
            lyricLoading = false
            pushLyricBus()
        }
    }

    /** 切歌后触发（非强制，缓存命中秒回） */
    private fun refreshLyricsForCurrent() {
        if (currentSong == null) {
            lyricDoc = null
            lyricLoading = false
            return
        }
        refreshLyrics(force = false)
    }

    /**
     * 播放状态 → 桌面歌词总线：歌词行/KTV 进度/歌名/播放态一次写入，
     * 悬浮窗服务 200ms 轮询本总线。引擎在播放 ticker 中周期调用，
     * 最小化/无窗口后依然推进。
     */
    fun pushLyricBus() {
        val doc = lyricDoc
        val song = currentSong
        DesktopLyricBus.songName = song?.name.orEmpty()
        DesktopLyricBus.playing = isPlaying
        if (doc == null || song == null) {
            DesktopLyricBus.lines = emptyList()
            DesktopLyricBus.index = -1
            DesktopLyricBus.lineStartMs = 0L
            DesktopLyricBus.lineEndMs = 0L
        } else {
            DesktopLyricBus.lines = doc.lines
            val idx = doc.indexAt(positionMs)
            DesktopLyricBus.index = idx
            if (idx >= 0) {
                DesktopLyricBus.lineStartMs = doc.lines.getOrNull(idx)?.timeMs ?: 0L
                DesktopLyricBus.lineEndMs = doc.lines.getOrNull(idx + 1)?.timeMs
                    ?: durationMs
            } else {
                DesktopLyricBus.lineStartMs = 0L
                DesktopLyricBus.lineEndMs = 0L
            }
        }
        DesktopLyricBus.positionMs = positionMs
        DesktopLyricBus.posUpdatedAt = SystemClock.uptimeMillis()
    }

    // ==================== 内部实现 ====================

    private fun loadAndPlay(song: SongInfo, resumeMs: Long = -1L) {
        gen++
        val myGen = gen
        currentSong = song
        isPlaying = false
        isPreparing = true
        playError = null
        positionMs = if (resumeMs > 0L) resumeMs else 0L
        durationMs = song.durationMs
        seekOnPreparedMs = resumeMs.coerceAtLeast(0L)
        restorePending = false
        ticker?.cancel()
        // v2.21.2：切歌即记录新会话（进度从当前起点算起）
        saveSession(force = true)
        // v2.22.3：切歌即刷新总线（新歌名/重置歌词行）+ 后台拉取新曲歌词
        pushLyricBus()
        refreshLyricsForCurrent()

        scope.launch {
            // 解析播放源优先级：本地 URI > 已下载文件 > 在线解析直链
            val source: String? = when {
                song.isLocal -> song.localUri
                song.downloadedPath != null && java.io.File(song.downloadedPath).isFile ->
                    Uri.fromFile(java.io.File(song.downloadedPath)).toString()
                else -> KuwoMusicApi.getPlayUrl(song.id).getOrNull()
            }
            if (myGen != gen) return@launch
            if (source.isNullOrEmpty()) {
                isPreparing = false
                playError = if (song.isLocal) "本地文件无法访问" else "获取播放链接失败，请稍后重试"
                return@launch
            }
            try {
                player.reset()
                // 音频属性需在 idle 态设置（reset 之后、setDataSource 之前）；
                // setAudioStreamType 已弃用，改用等价 AudioAttributes（USAGE_MEDIA）
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                player.setDataSource(appContext, Uri.parse(source))
                pendingGen = myGen
                player.prepareAsync()
            } catch (e: Exception) {
                if (myGen == gen) {
                    isPreparing = false
                    playError = "加载失败: ${e.message ?: "未知错误"}"
                }
            }
        }
    }

    /** 播放完成：按模式自动接续 */
    private fun onCompleted() {
        isPlaying = false
        when {
            playMode == MusicStore.MODE_LOOP_ONE -> {
                currentSong?.let { seekTo(0); runCatching { player.start() }; isPlaying = true; startTicker() }
            }
            else -> next(auto = true)
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && isPlaying) {
                positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(positionMs)
                // v2.21.2：播放中定期记忆进度（saveSession 内部节流）
                saveSession()
                // v2.22.3：播放中周期推进桌面歌词总线（引擎级，不依赖窗口）
                pushLyricBus()
                delay(300)
            }
        }
    }

    /**
     * 停止并释放引擎（v2.22.3 起仅由 MusicEngineHolder.shutdown 在
     * 「显式关闭最后一个播放器窗口」时调用；最小化不再触发释放）。
     */
    fun dispose() {
        ticker?.cancel()
        // v2.21.2：退出前强制落盘最终进度（记忆关闭前正在播放的音乐）
        saveSession(force = true)
        scope.cancel()
        runCatching {
            if (player.isPlaying) player.stop()
        }
        runCatching { player.release() }
    }

    // ==================== 播放会话记忆（v2.21.2） ====================

    /**
     * 装回上次退出前的播放会话：队列 + 当前曲 + 进度。
     * 只装入状态不起播 —— 底部播放条立即显示上次的歌，点播放从记忆进度继续。
     */
    private fun restoreSession() {
        val s = store.loadLastSession() ?: return
        if (s.songs.isEmpty()) return
        queue.clear()
        queue.addAll(s.songs)
        val idx = s.index.coerceIn(0, s.songs.size - 1)
        val song = s.songs[idx]
        queueIndex = idx
        currentSong = song
        durationMs = song.durationMs
        val pos = s.positionMs.coerceIn(0L, song.durationMs.coerceAtLeast(0L))
        positionMs = pos
        restorePending = true
        seekOnPreparedMs = pos
        // v2.22.3：装回会话后即拉取歌词（重开窗口/重建设置前歌词页可用）
        refreshLyricsForCurrent()
    }

    /**
     * 把当前播放状态写入会话记忆（队列 + 当前曲下标 + 进度）。
     * 默认 4 秒节流，force = true 时立即落盘（切歌/暂停/退出）。
     */
    fun saveSession(force: Boolean = false) {
        val song = currentSong ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastSessionSaveAt < 4000L) return
        lastSessionSaveAt = now
        val idx = if (queueIndex in queue.indices) queueIndex
        else queue.indexOfFirst { it.key == song.key }.coerceAtLeast(0)
        store.saveLastSession(queue.toList(), idx, positionMs)
    }
}
