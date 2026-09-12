package com.linbox.apps.music

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * 云音乐数据层（v2.18）：
 * - [SongInfo] 统一歌曲模型（在线 / 本地）
 * - [LrcParser] LRC 歌词解析（移植自 linboxyy.py LrcParser，支持一行多时间标签）
 * - [LyricsDoc] 歌词文档（原文 + 按时间合并的翻译行）
 * - [MusicStore] 收藏 / 最近播放 / 播放模式 / 播放器设置持久化（JSON 文件，filesDir 内）
 * - [MusicSettings] 播放器设置模型（v2.18：歌词秀背景 / 3D 倾斜 / 主页背景 / 扫描目录 / 词源引擎）
 * - 歌词缓存（cacheDir/music_lyrics）与四级词源回退获取 [fetchLyrics]
 */

// ==================== 歌曲模型 ====================

/** 统一歌曲模型：在线（source=kuwo）或本地（source=local，uri 非空） */
data class SongInfo(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val picUrl: String,
    val source: String = SOURCE_KUWO,
    /** 本地歌曲的 content:// 或 file:// URI */
    val localUri: String? = null,
    /** 已下载到本地的文件路径（下载完成后回填，仅在线歌有） */
    val downloadedPath: String? = null
) {
    /** 唯一键：收藏/最近播放/歌词缓存去重 */
    val key: String
        get() = if (source == SOURCE_LOCAL) "local_$localUri" else "${source}_$id"

    val isLocal: Boolean get() = source == SOURCE_LOCAL

    fun toOnlineSong(): KuwoMusicApi.Song? =
        if (source == SOURCE_KUWO) {
            KuwoMusicApi.Song(id, name, artist, album, durationMs, picUrl)
        } else null

    companion object {
        const val SOURCE_KUWO = "kuwo"
        const val SOURCE_LOCAL = "local"

        fun fromKuwoSong(s: KuwoMusicApi.Song) = SongInfo(
            id = s.id, name = s.name, artist = s.artist, album = s.album,
            durationMs = s.durationMs, picUrl = s.pic, source = SOURCE_KUWO
        )
    }
}

// ==================== LRC 解析 ====================

/** 一行歌词：时间戳 + 原文 + 翻译（可为空） */
data class LyricLine(val timeMs: Long, val text: String, val translation: String? = null)

/** 歌词文档：解析后的行列表 + 来源描述 */
data class LyricsDoc(
    val lines: List<LyricLine>,
    /** "kuwo" / "netease" / "local"（本地 .lrc 文件） */
    val source: String
) {
    /** 二分查找当前播放行索引（timeMs <= pos 的最后一行），无歌词返回 -1 */
    fun indexAt(posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= posMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

/**
 * LRC 歌词解析器 —— 移植自 linboxyy.py 的 LrcParser 类：
 * - 支持一行多个时间标签 [mm:ss.xx][mm:ss.xx]文本
 * - 支持两位/三位毫秒
 * - 解析结果按时间升序排列
 */
object LrcParser {

    private val TIME_TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
    private val ALL_TAGS = Pattern.compile("\\[\\d+:\\d+(?:[.:]\\d+)?]")

    /** 逐字时间标签（增强格式 LRC 常见：<00:12.34>字） */
    private val WORD_TAGS = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")

    /** 解析 LRC 文本为 (时间ms, 文本) 列表 */
    fun parse(content: String): List<Pair<Long, String>> {
        val timestamps = mutableListOf<Long>()
        val lyrics = mutableListOf<String>()

        for (rawLine in content.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val matcher = TIME_TAG.matcher(line)
            val tags = mutableListOf<Long>()
            while (matcher.find()) {
                val minutes = matcher.group(1)?.toLongOrNull() ?: continue
                val seconds = matcher.group(2)?.toLongOrNull() ?: continue
                val fracStr = matcher.group(3) ?: "0"
                // 两位毫秒按 x10，三位及以上直接截取前三位
                val ms = if (fracStr.length <= 2) {
                    fracStr.padEnd(2, '0').toLongOrNull() ?: 0L
                } else {
                    fracStr.take(3).toLongOrNull() ?: 0L
                }
                tags.add(minutes * 60_000L + seconds * 1000L + ms)
            }
            if (tags.isEmpty()) continue

            // 移除所有时间标签与逐字标签得到纯文本
            val text = WORD_TAGS.matcher(ALL_TAGS.matcher(line).replaceAll("")).replaceAll("").trim()
            for (t in tags) {
                timestamps.add(t)
                lyrics.add(text)
            }
        }

        // 按时间排序（稳定排序，与 python 版行为一致）
        val idx = timestamps.indices.sortedBy { timestamps[it] }
        return idx.map { timestamps[it] to lyrics[it] }
    }

    /**
     * 解析原文 + 翻译两份 LRC，按时间戳合并为 LyricsDoc。
     * 翻译行匹配原行时间戳（容差 60ms）。
     */
    fun merge(mainContent: String, translationContent: String?, source: String): LyricsDoc {
        val main = parse(mainContent)
        val transMap = HashMap<Long, String>()
        if (!translationContent.isNullOrBlank()) {
            for ((t, text) in parse(translationContent)) {
                if (text.isNotBlank()) transMap[t] = text
            }
        }
        val lines = main.map { (t, text) ->
            val tr = transMap[t]
                ?: if (transMap.isNotEmpty()) {
                    // 容差查找最近翻译（±60ms）
                    var best: Pair<Long, String>? = null
                    for ((tt, txt) in transMap) {
                        if (Math.abs(tt - t) <= 60) {
                            if (best == null || Math.abs(tt - t) < Math.abs(best.first - t)) {
                                best = tt to txt
                            }
                        }
                    }
                    best?.second
                } else null
            LyricLine(timeMs = t, text = text, translation = tr)
        }
        return LyricsDoc(lines = lines, source = source)
    }

    /** LyricsDoc → 可保存的 LRC 文本（翻译写入 [tt:...] 不标准，直接原文行） */
    fun toLrcText(doc: LyricsDoc): String = buildString {
        for (line in doc.lines) {
            val m = line.timeMs / 60000
            val s = (line.timeMs % 60000) / 1000
            val ms = line.timeMs % 1000
            append("[%02d:%02d.%03d]%s\n".format(m, s, ms, line.text))
        }
    }
}

// ==================== 播放器设置 ====================

/**
 * 播放器设置（v2.18 新增，设置中心持久化）：
 * - 歌词秀：背景（封面模糊/纯色/渐变/自定义图片）、3D 倾斜强度与上限、歌词墙视角、
 *   左右字体差、高亮颜色、KTV 渐进样式、当前行字号、行切换动画、高亮发光、翻译显示、
 *   自定义封面/光盘图片（v2.21）
 * - 桌面歌词（v2.21）：悬浮窗开关、两行/全屏双模式、字体颜色、背景不透明度、字号
 * - 主页：背景（默认/纯色/渐变/自定义图片）与图片压暗
 * - 本地扫描：全库扫描 / 仅扫描指定目录（目录为绝对路径，依赖“所有文件访问”权限）
 * - 词源引擎：智能回退 / 指定优先词源（酷我、网易云、QQ 音乐、LRCLIB）
 */
data class MusicSettings(
    // ---- 歌词秀背景 ----
    val lyricBgMode: Int = BG_COVER,
    val lyricBgColor: Int = 0xFF191922.toInt(),
    val lyricBgGradient: Int = 0,
    val lyricBgImage: String? = null,
    /** 封面模糊模式的模糊半径（dp），0 为不模糊（v2.20 可调；v2.21 默认接近清晰） */
    val coverBlur: Float = 3f,
    /** 封面模糊/自定义图片模式的背景压暗强度 0..0.95（v2.20 可调；v2.21 默认接近最亮） */
    val lyricBgDim: Float = 0.10f,
    // ---- 3D 歌词 ----
    /** 立体纵深强度：远行缩小/变暗/朝消失点漂移的幅度（v2.20.3 语义，0 为平面） */
    val tilt3d: Float = 14f,
    /** [已弃用 v2.20.3] 旧版每行最大倾角，仅保留兼容旧 settings.json，不再有 UI */
    val tilt3dMax: Float = 44f,
    /** 整面歌词墙绕 Y 轴视角（度），0 为正对 */
    val wallRotateY: Float = -14f,
    /** 整面歌词墙绕 X 轴俯仰角（度，正 = 顶部向后倒），v2.20.3 新增 */
    val wallTiltX: Float = 16f,
    /** 左右字体差（%，0-45）：行内逐字字号渐变，行首最小、行尾最大 = 左小右大（v2.21.1 起，0 为关闭） */
    val lineYaw3d: Float = 16f,
    /** 当前行高亮颜色（ARGB），KTV 已唱部分同色（v2.21 新增） */
    val highlightColor: Int = 0xFFFFFFFF.toInt(),
    /** KTV 渐进显示：当前行按播放进度逐字填色（v2.21 新增） */
    val ktvMode: Boolean = false,
    /** 当前行字号（sp），非当前行按比例缩小 */
    val lyricFontSize: Int = 22,
    // ---- 桌面歌词（v2.21 新增） ----
    /** 桌面歌词总开关（需「显示在应用上层/其他应用上层」权限） */
    val desktopLyricOn: Boolean = false,
    /** 桌面歌词模式：false = 两行模式（对照参考图4），true = 桌面全屏歌词横幅 */
    val desktopLyricFullscreen: Boolean = false,
    /** 桌面歌词字体颜色（ARGB） */
    val desktopLyricColor: Int = 0xFFFFFFFF.toInt(),
    /** 桌面歌词背景不透明度 0..1（0 为全透明仅剩描边字；v2.21.1 默认全透明） */
    val desktopLyricBgAlpha: Float = 0f,
    /** 桌面歌词字号（sp），两行模式两行同字号；全屏模式非当前行按 0.7 倍缩小 */
    val desktopLyricSize: Float = 22f,
    /** 桌面歌词 KTV 逐字变色（v2.21.1 新增）：当前行按播放进度从左向右扫色 */
    val desktopLyricKtv: Boolean = true,
    /** 桌面全屏歌词显示行数（v2.21.2 起上限 15 行，默认 4 行，围绕当前行取词） */
    val desktopLyricLines: Int = 4,
    /** 歌词秀自定义封面图片（content:// 或绝对路径；空 = 使用歌曲专辑封面，v2.21） */
    val coverImage: String? = null,
    /** 歌词秀自定义光盘盘面图片（空 = 与封面同图，v2.21） */
    val discImage: String? = null,
    /** 行切换平滑动画 */
    val lyricDynamic: Boolean = true,
    /** 当前行高亮发光 */
    val lyricGlow: Boolean = true,
    /** 显示翻译行 */
    val showTranslation: Boolean = true,
    /** 词源引擎：auto / kuwo / netease / qq / lrclib */
    val lyricEngine: String = ENGINE_AUTO,
    // ---- 主页背景 ----
    val homeBgMode: Int = HOME_BG_DEFAULT,
    val homeBgColor: Int = 0xFFFCFCFD.toInt(),
    val homeBgGradient: Int = 0,
    val homeBgImage: String? = null,
    /** 自定义图片时的压暗系数 0..0.8 */
    val homeImageDim: Float = 0.25f,
    // ---- 本地扫描 ----
    val scanMode: Int = SCAN_ALL,
    /** 指定目录扫描的绝对路径列表 */
    val scanDirs: List<String> = emptyList()
) {
    @Suppress("unused")
    companion object {
        // 歌词秀背景模式
        const val BG_COVER = 0      // 封面模糊铺底（默认）
        const val BG_SOLID = 1      // 纯色
        const val BG_GRADIENT = 2   // 渐变预设
        const val BG_IMAGE = 3      // 自定义图片

        // 主页背景模式
        const val HOME_BG_DEFAULT = 0

        // 本地扫描模式
        const val SCAN_ALL = 0      // 全库（MediaStore）
        const val SCAN_DIRS = 1     // 仅指定目录

        // 词源引擎
        const val ENGINE_AUTO = "auto"
        const val ENGINE_KUWO = "kuwo"
        const val ENGINE_NETEASE = "netease"
        const val ENGINE_QQ = "qq"
        const val ENGINE_LRCLIB = "lrclib"

        /** 词源显示名（设置页 / 词源标记共用） */
        val ENGINE_LABELS = linkedMapOf(
            ENGINE_AUTO to "智能回退（推荐）",
            ENGINE_KUWO to "酷我音乐",
            ENGINE_NETEASE to "网易云",
            ENGINE_QQ to "QQ 音乐",
            ENGINE_LRCLIB to "LRCLIB"
        )
    }
}

// ==================== 本地持久化 ====================

/**
 * 桌面歌词悬浮窗锁定状态与位置记忆（v2.21.3）。
 * 独立于 [MusicSettings] 存储于 desklyric.json：位置由悬浮窗服务在拖动结束/锁定时写入，
 * 设置页只写 locked 开关 —— 避免两处保存互相覆盖。
 * 坐标为 px；-1 = 未记录（使用内建默认位置）。
 */
data class LyricOverlayState(
    val locked: Boolean = false,
    /** 两行模式窗口 y（窗口通栏全屏宽，仅垂直拖动） */
    val pairY: Int = -1,
    /** 全屏模式横幅 x 偏移（CENTER 重力下的拖动偏移） */
    val fsX: Int = -1,
    /** 全屏模式横幅 y 偏移 */
    val fsY: Int = -1
)

/**
 * 收藏 / 最近播放 / 播放模式持久化。
 * 存储位置：context.filesDir/music/（应用私有，无需存储权限）。
 */
class MusicStore(private val context: Context) {

    private val dir: File by lazy {
        File(context.filesDir, "music").apply { mkdirs() }
    }
    private val favoritesFile: File by lazy { File(dir, "favorites.json") }
    private val recentFile: File by lazy { File(dir, "recent.json") }
    private val settingsFile: File by lazy { File(dir, "settings.json") }

    private val lock = Any()

    // ---------- 收藏 ----------

    fun loadFavorites(): MutableList<SongInfo> = loadSongs(favoritesFile)

    fun saveFavorites(list: List<SongInfo>) = saveSongs(favoritesFile, list)

    fun isFavorite(song: SongInfo): Boolean = synchronized(lock) {
        loadSongs(favoritesFile).any { it.key == song.key }
    }

    fun toggleFavorite(song: SongInfo): Boolean {
        synchronized(lock) {
            val list = loadSongs(favoritesFile)
            val removed = list.removeAll { it.key == song.key }
            if (!removed) list.add(0, song)
            saveSongs(favoritesFile, list)
            return !removed
        }
    }

    // ---------- 最近播放 ----------

    fun loadRecent(): MutableList<SongInfo> = loadSongs(recentFile)

    fun pushRecent(song: SongInfo) {
        synchronized(lock) {
            val list = loadSongs(recentFile)
            list.removeAll { it.key == song.key }
            list.add(0, song)
            while (list.size > 100) list.removeAt(list.size - 1)
            saveSongs(recentFile, list)
        }
    }

    // ---------- 设置（播放模式 + 播放器设置中心） ----------

    private fun readSettingsJson(): JSONObject =
        runCatching { JSONObject(settingsFile.readText()) }.getOrDefault(JSONObject())

    private fun writeSettingsJson(o: JSONObject) {
        runCatching { settingsFile.writeText(o.toString()) }
    }

    fun loadPlayMode(): Int = readSettingsJson().optInt("playMode", MODE_ORDER)

    fun savePlayMode(mode: Int) {
        writeSettingsJson(readSettingsJson().put("playMode", mode))
    }

    /** 读取播放器设置中心（缺失字段全部回退默认值） */
    fun loadMusicSettings(): MusicSettings {
        val o = readSettingsJson()
        val dirs = mutableListOf<String>()
        runCatching {
            val arr = o.optJSONArray("scanDirs")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotEmpty()) dirs.add(s)
                }
            }
        }
        return MusicSettings(
            lyricBgMode = o.optInt("lyricBgMode", MusicSettings.BG_COVER),
            lyricBgColor = o.optInt("lyricBgColor", 0xFF191922.toInt()),
            lyricBgGradient = o.optInt("lyricBgGradient", 0),
            lyricBgImage = o.optString("lyricBgImage", "").takeIf { it.isNotEmpty() },
            coverBlur = o.optDouble("coverBlur", 3.0).toFloat().coerceIn(0f, 60f),
            lyricBgDim = o.optDouble("lyricBgDim", 0.10).toFloat().coerceIn(0f, 0.95f),
            tilt3d = o.optDouble("tilt3d", 14.0).toFloat().coerceIn(0f, 45f),
            tilt3dMax = o.optDouble("tilt3dMax", 44.0).toFloat().coerceIn(0f, 90f),
            wallRotateY = o.optDouble("wallRotateY", -14.0).toFloat().coerceIn(-60f, 60f),
            wallTiltX = o.optDouble("wallTiltX", 16.0).toFloat().coerceIn(-45f, 45f),
            lineYaw3d = o.optDouble("lineYaw3d", 16.0).toFloat().coerceIn(0f, 45f),
            highlightColor = o.optInt("highlightColor", 0xFFFFFFFF.toInt()),
            ktvMode = o.optBoolean("ktvMode", false),
            lyricFontSize = o.optInt("lyricFontSize", 22).coerceIn(12, 60),
            desktopLyricOn = o.optBoolean("desktopLyricOn", false),
            desktopLyricFullscreen = o.optBoolean("desktopLyricFullscreen", false),
            desktopLyricColor = o.optInt("desktopLyricColor", 0xFFFFFFFF.toInt()),
            desktopLyricBgAlpha = run {
                // v2.21.1：旧默认 0.35 一次性迁移为全透明；用户手动调过的其它值保留
                val bg = o.optDouble("desktopLyricBgAlpha", 0.0).toFloat().coerceIn(0f, 1f)
                if (bg == 0.35f) 0f else bg
            },
            desktopLyricSize = o.optDouble("desktopLyricSize", 22.0).toFloat().coerceIn(14f, 40f),
            desktopLyricKtv = o.optBoolean("desktopLyricKtv", true),
            desktopLyricLines = o.optInt("desktopLyricLines", 4).coerceIn(1, 15),
            coverImage = o.optString("coverImage", "").takeIf { it.isNotEmpty() },
            discImage = o.optString("discImage", "").takeIf { it.isNotEmpty() },
            lyricDynamic = o.optBoolean("lyricDynamic", true),
            lyricGlow = o.optBoolean("lyricGlow", true),
            showTranslation = o.optBoolean("showTranslation", true),
            lyricEngine = o.optString("lyricEngine", MusicSettings.ENGINE_AUTO),
            homeBgMode = o.optInt("homeBgMode", MusicSettings.HOME_BG_DEFAULT),
            homeBgColor = o.optInt("homeBgColor", 0xFFFCFCFD.toInt()),
            homeBgGradient = o.optInt("homeBgGradient", 0),
            homeBgImage = o.optString("homeBgImage", "").takeIf { it.isNotEmpty() },
            homeImageDim = o.optDouble("homeImageDim", 0.25).toFloat().coerceIn(0f, 0.95f),
            scanMode = o.optInt("scanMode", MusicSettings.SCAN_ALL),
            scanDirs = dirs
        )
    }

    /** 保存播放器设置中心（保留 playMode） */
    fun saveMusicSettings(s: MusicSettings) {
        runCatching {
            val dirs = JSONArray()
            for (d in s.scanDirs) dirs.put(d)
            writeSettingsJson(
                readSettingsJson()
                    .put("lyricBgMode", s.lyricBgMode)
                    .put("lyricBgColor", s.lyricBgColor)
                    .put("lyricBgGradient", s.lyricBgGradient)
                    .put("lyricBgImage", s.lyricBgImage ?: "")
                    .put("coverBlur", s.coverBlur.toDouble())
                    .put("lyricBgDim", s.lyricBgDim.toDouble())
                    .put("tilt3d", s.tilt3d.toDouble())
                    .put("tilt3dMax", s.tilt3dMax.toDouble())
                    .put("wallRotateY", s.wallRotateY.toDouble())
                    .put("wallTiltX", s.wallTiltX.toDouble())
                    .put("lineYaw3d", s.lineYaw3d.toDouble())
                    .put("highlightColor", s.highlightColor)
                    .put("ktvMode", s.ktvMode)
                    .put("lyricFontSize", s.lyricFontSize)
                    .put("desktopLyricOn", s.desktopLyricOn)
                    .put("desktopLyricFullscreen", s.desktopLyricFullscreen)
                    .put("desktopLyricColor", s.desktopLyricColor)
                    .put("desktopLyricBgAlpha", s.desktopLyricBgAlpha.toDouble())
                    .put("desktopLyricSize", s.desktopLyricSize.toDouble())
                    .put("desktopLyricKtv", s.desktopLyricKtv)
                    .put("desktopLyricLines", s.desktopLyricLines)
                    .put("coverImage", s.coverImage ?: "")
                    .put("discImage", s.discImage ?: "")
                    .put("lyricDynamic", s.lyricDynamic)
                    .put("lyricGlow", s.lyricGlow)
                    .put("showTranslation", s.showTranslation)
                    .put("lyricEngine", s.lyricEngine)
                    .put("homeBgMode", s.homeBgMode)
                    .put("homeBgColor", s.homeBgColor)
                    .put("homeBgGradient", s.homeBgGradient)
                    .put("homeBgImage", s.homeBgImage ?: "")
                    .put("homeImageDim", s.homeImageDim.toDouble())
                    .put("scanMode", s.scanMode)
                    .put("scanDirs", dirs)
            )
        }
    }

    // ---------- 歌词缓存 ----------

    private val lyricCacheDir: File by lazy {
        File(context.cacheDir, "music_lyrics").apply { mkdirs() }
    }

    fun cachedLyrics(song: SongInfo): LyricsDoc? {
        val main = File(lyricCacheDir, "${song.key}.lrc")
        if (!main.isFile) return null
        val trans = File(lyricCacheDir, "${song.key}.tr.lrc").takeIf { it.isFile }?.readText()
        return runCatching {
            LrcParser.merge(main.readText(), trans, "cache")
        }.getOrNull()
    }

    fun cacheLyrics(song: SongInfo, mainText: String, transText: String?) {
        runCatching {
            File(lyricCacheDir, "${song.key}.lrc").writeText(mainText)
            if (transText != null) {
                File(lyricCacheDir, "${song.key}.tr.lrc").writeText(transText)
            }
        }
    }

    // ---------- 上次播放会话（v2.21.2：记忆关闭播放器前正在播放的音乐/队列/进度） ----------

    private val sessionFile: File by lazy { File(dir, "lastsession.json") }

    /** 上次会话快照：播放队列 + 当前曲下标 + 进度 */
    data class LastSession(val songs: List<SongInfo>, val index: Int, val positionMs: Long)

    fun loadLastSession(): LastSession? {
        if (!sessionFile.isFile) return null
        return runCatching {
            val o = JSONObject(sessionFile.readText())
            val arr = o.optJSONArray("songs") ?: return@runCatching null
            val list = mutableListOf<SongInfo>()
            for (i in 0 until arr.length()) {
                val so = arr.optJSONObject(i) ?: continue
                list.add(songFromJson(so))
            }
            if (list.isEmpty()) null
            else LastSession(list, o.optInt("index", 0), o.optLong("positionMs", 0L))
        }.getOrNull()
    }

    fun saveLastSession(songs: List<SongInfo>, index: Int, positionMs: Long) {
        runCatching {
            val arr = JSONArray()
            for (s in songs.take(300)) arr.put(songToJson(s))
            sessionFile.writeText(
                JSONObject()
                    .put("songs", arr)
                    .put("index", index)
                    .put("positionMs", positionMs)
                    .toString()
            )
        }
    }

    fun clearLastSession() {
        runCatching { sessionFile.delete() }
    }

    // ---------- 桌面歌词锁定与位置（v2.21.3，独立文件避免与设置中心互相覆盖） ----------

    private val overlayStateFile: File by lazy { File(dir, "desklyric.json") }

    fun loadLyricOverlayState(): LyricOverlayState {
        if (!overlayStateFile.isFile) return LyricOverlayState()
        return runCatching {
            val o = JSONObject(overlayStateFile.readText())
            LyricOverlayState(
                locked = o.optBoolean("locked", false),
                pairY = o.optInt("pairY", -1),
                fsX = o.optInt("fsX", -1),
                fsY = o.optInt("fsY", -1)
            )
        }.getOrDefault(LyricOverlayState())
    }

    fun saveLyricOverlayState(s: LyricOverlayState) {
        runCatching {
            overlayStateFile.writeText(
                JSONObject()
                    .put("locked", s.locked)
                    .put("pairY", s.pairY)
                    .put("fsX", s.fsX)
                    .put("fsY", s.fsY)
                    .toString()
            )
        }
    }

    // ---------- 序列化 ----------

    private fun songToJson(s: SongInfo): JSONObject =
        JSONObject()
            .put("id", s.id)
            .put("name", s.name)
            .put("artist", s.artist)
            .put("album", s.album)
            .put("durationMs", s.durationMs)
            .put("picUrl", s.picUrl)
            .put("source", s.source)
            .put("localUri", s.localUri ?: "")
            .put("downloadedPath", s.downloadedPath ?: "")

    private fun songFromJson(o: JSONObject): SongInfo =
        SongInfo(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            artist = o.optString("artist", ""),
            album = o.optString("album", ""),
            durationMs = o.optLong("durationMs", 0L),
            picUrl = o.optString("picUrl", ""),
            source = o.optString("source", SongInfo.SOURCE_KUWO),
            localUri = o.optString("localUri", "").takeIf { it.isNotEmpty() },
            downloadedPath = o.optString("downloadedPath", "").takeIf { it.isNotEmpty() }
        )

    private fun loadSongs(file: File): MutableList<SongInfo> {
        if (!file.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<SongInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(songFromJson(o))
            }
            list
        }.getOrDefault(mutableListOf())
    }

    private fun saveSongs(file: File, list: List<SongInfo>) {
        runCatching {
            val arr = JSONArray()
            for (s in list) arr.put(songToJson(s))
            file.writeText(arr.toString())
        }
    }

    companion object {
        /** 播放模式 */
        const val MODE_ORDER = 0
        const val MODE_LOOP_ONE = 1
        const val MODE_SHUFFLE = 2
    }
}

// ==================== 歌词获取编排 ====================

/** 歌词搜索查询候选：title 必有，artist 可空；keyword 为拼接搜索词 */
data class LyricQuery(val title: String, val artist: String = "") {
    val keyword: String get() = "$title $artist".trim()
}

/**
 * 构造歌词搜索候选列表（v2.20 核心增强，任一词源按序尝试直到命中）：
 * - 在线歌：[歌名+歌手, 歌名, 清理后歌名+歌手, 清理后歌名] —— 括号注释
 *   （Live/翻自/Remix 等）干扰搜索时自动回退纯歌名
 * - 本地歌：先按"歌手 - 歌名"约定拆分文件名，候选含
 *   [歌名+歌手, 歌名, 拆分变体两种角色顺序, 原始文件名]，
 *   兼容 "周杰伦-晴天"（无空格）、"01.晴天"（序号前缀）等常见命名
 */
fun buildLyricQueries(song: SongInfo): List<LyricQuery> {
    val artistHint = song.artist.takeIf { it.isNotBlank() && it != "未知歌手" } ?: ""
    val clean = cleanLocalName(song.name)
    val queries = mutableListOf<LyricQuery>()

    if (!song.isLocal) {
        val name = song.name.trim()
        if (artistHint.isNotEmpty()) queries.add(LyricQuery(name, artistHint))
        queries.add(LyricQuery(name))
        if (clean.isNotBlank() && clean != name) {
            if (artistHint.isNotEmpty()) queries.add(LyricQuery(clean, artistHint))
            queries.add(LyricQuery(clean))
        }
    } else {
        if (artistHint.isNotEmpty()) queries.add(LyricQuery(clean, artistHint))
        queries.add(LyricQuery(clean))
        if (artistHint.isEmpty()) {
            // 扫描时未能拆出歌手：尝试直接从清理后的歌名中拆 "歌手 - 歌名"
            val m = Regex("^(.{1,40}?)\\s*[-\u2013\u2014]\\s*(.+)$").find(clean)
            if (m != null) {
                val a = m.groupValues[1].trim()
                val t = m.groupValues[2].trim()
                if (a.isNotEmpty() && t.isNotEmpty()) {
                    queries.add(LyricQuery(t, a))  // 常规：歌手 - 歌名
                    queries.add(LyricQuery(a, t))  // 反向：歌名 - 歌手
                }
            }
        }
        queries.add(LyricQuery(song.name.trim()))
    }
    return queries.filter { it.title.isNotBlank() }.distinctBy { it.keyword }
}

/**
 * 获取当前歌曲歌词（带磁盘缓存；v2.20 大幅提高命中率）：
 * - 四级词源回退链：酷我 → 网易云 → QQ 音乐 → LRCLIB（指定词源则优先）
 * - 每个词源依次尝试 [buildLyricQueries] 的多组关键词候选，任一命中即止
 * - 酷我：在线歌按 musicId 直查 + 关键词搜索曲库兜底（本地歌也能命中）
 * - 网易云 / QQ / LRCLIB：多候选结果按歌名吻合度择优，避免搜到翻唱/伴奏
 *
 * @param force 跳过缓存强制刷新
 * @param engine 词源偏好：auto 按默认顺序；指定词源时该源优先，其余按默认顺序兜底
 */
suspend fun fetchLyrics(
    store: MusicStore,
    song: SongInfo,
    force: Boolean = false,
    engine: String = MusicSettings.ENGINE_AUTO
): LyricsDoc? {
    if (!force) {
        store.cachedLyrics(song)?.let { return it }
    }

    val queries = buildLyricQueries(song)
    if (queries.isEmpty()) return null

    for (eng in engineOrder(engine)) {
        var pair: Pair<String, String?>? = null
        for ((qi, q) in queries.withIndex()) {
            pair = runCatching {
                when (eng) {
                    "kuwo" -> fetchKuwoSmart(song, q, tryById = qi == 0)
                    "netease" -> KuwoMusicApi.getNeteaseLyric(q.keyword, q.title).getOrNull()
                    "qq" -> LyricEngines.getQqLyric(q.keyword, q.title).getOrNull()
                    "lrclib" -> LyricEngines.getLrclibLyric(q.title, q.artist)
                        .getOrNull()?.let { it to null }
                    else -> null
                }
            }.getOrNull()
            if (pair != null && pair.first.isNotBlank()) break
        }

        if (pair != null && pair.first.isNotBlank()) {
            val (main, trans) = pair
            val doc = LrcParser.merge(main, trans, eng)
            store.cacheLyrics(song, main, trans)
            return doc
        }
    }
    return null
}

/** 词源尝试顺序：指定词源优先，其余按默认顺序（酷我→网易→QQ→LRCLIB）兜底 */
private fun engineOrder(engine: String): List<String> {
    val default = listOf("kuwo", "netease", "qq", "lrclib")
    if (engine in default) {
        return listOf(engine) + default.filter { it != engine }
    }
    return default
}

/**
 * 深度歌词下载（v2.21.4）：搜索/在线歌曲在 [fetchLyrics] 未命中时的自动兜底。
 * 在常规候选之上追加扩展关键词（拆括号副题/Live 标注、拆 feat 合唱、拆连字符副题），
 * 全词源按 [engineOrder] 再跑一遍（酷我跳过 byId 直查避免重复；各词源无吻合时自动取首条），
 * 任一命中即写缓存并返回 —— 「播放无歌词歌曲时自动下载对应歌词」的落地实现。
 */
suspend fun fetchLyricsDeep(
    store: MusicStore,
    song: SongInfo,
    force: Boolean = true,
    engine: String = MusicSettings.ENGINE_AUTO
): LyricsDoc? {
    if (!force) {
        store.cachedLyrics(song)?.let { return it }
    }
    val queries = buildLyricQueries(song).toMutableList()
    val artistHint = song.artist.takeIf { it.isNotBlank() && it != "未知歌手" } ?: ""
    for (variant in deepTitleVariants(song.name)) {
        if (artistHint.isNotEmpty()) queries.add(LyricQuery(variant, artistHint))
        queries.add(LyricQuery(variant))
    }
    val candidates = queries.filter { it.title.isNotBlank() }.distinctBy { it.keyword }
    if (candidates.isEmpty()) return null

    for (eng in engineOrder(engine)) {
        var pair: Pair<String, String?>? = null
        for (q in candidates) {
            pair = runCatching {
                when (eng) {
                    "kuwo" -> fetchKuwoSmart(song, q, tryById = false)
                    "netease" -> KuwoMusicApi.getNeteaseLyric(q.keyword, q.title).getOrNull()
                    "qq" -> LyricEngines.getQqLyric(q.keyword, q.title).getOrNull()
                    "lrclib" -> LyricEngines.getLrclibLyric(q.title, q.artist)
                        .getOrNull()?.let { it to null }
                    else -> null
                }
            }.getOrNull()
            if (pair != null && pair.first.isNotBlank()) break
        }
        if (pair != null && pair.first.isNotBlank()) {
            val (main, trans) = pair
            val doc = LrcParser.merge(main, trans, eng)
            store.cacheLyrics(song, main, trans)
            return doc
        }
    }
    return null
}

/**
 * 深度候选歌名变体（v2.21.4）：
 * 1) 拆括号注释："歌名 (Live)" → "歌名"（括号内容 ≥2 字时也单列候选）
 * 2) 拆 feat/with 合唱："歌名 feat. XXX" → "歌名"
 * 3) 拆连字符副题："歌名 - 副题" → 两段分别作为候选
 * 返回去重后的变体列表（不含原歌名，每项长度 ≥ 2）
 */
private fun deepTitleVariants(title: String): List<String> {
    val out = LinkedHashSet<String>()
    val t = title.trim()
    if (t.length < 3) return out.toList()
    Regex("[(\\[]([^)\\]]*)[)\\]]").findAll(t).forEach { m ->
        val inner = m.groupValues[1].trim()
        val outer = t.replace(m.value, " ").replace(Regex("\\s{2,}"), " ").trim()
        if (outer.isNotEmpty() && outer != t) out.add(outer)
        if (inner.length >= 2 && inner.length < t.length) out.add(inner)
    }
    Regex("(?i)\\s+(feat\\.|ft\\.|feat|with)\\s+.*$").find(t)?.let { m ->
        val outer = t.substring(0, m.range.first).trim()
        if (outer.length >= 2) out.add(outer)
    }
    val hyphen = t.split(Regex("\\s+[-\u2013\u2014]\\s+"), limit = 2)
    if (hyphen.size == 2) {
        hyphen.forEach { seg ->
            val s = seg.trim()
            if (s.length >= 2) out.add(s)
        }
    }
    out.remove(t)
    return out.filter { it.length >= 2 }
}

/**
 * 酷我歌词（v2.20 升级，本地歌也能命中酷我曲库）：
 * - [tryById] 且为在线歌：按 musicId 直接取逐行歌词（最精准）
 * - 否则按关键词搜索酷我曲库（歌名模糊择优）→ 取首条匹配的歌词
 */
private suspend fun fetchKuwoSmart(song: SongInfo, q: LyricQuery, tryById: Boolean): Pair<String, String?>? {
    if (tryById && !song.isLocal) {
        kuwoLyricFromId(song.id)?.let { return it }
    }
    val kw = q.keyword
    if (kw.isBlank()) return null
    val list = KuwoMusicApi.search(kw, count = 8).getOrNull().orEmpty()
    val hit = list.firstOrNull { looseNameMatch(it.name, q.title) } ?: list.firstOrNull()
        ?: return null
    return kuwoLyricFromId(hit.id)
}

/** 酷我逐行歌词 → LRC 文本（失败返回 null） */
private suspend fun kuwoLyricFromId(musicId: String): Pair<String, String?>? {
    val lines = KuwoMusicApi.getKuwoLyric(musicId).getOrNull().takeUnless { it.isNullOrEmpty() }
        ?: return null
    val text = buildString {
        for ((t, txt) in lines) {
            val m = t / 60000
            val s = (t % 60000) / 1000
            val ms = t % 1000
            append("[%02d:%02d.%03d]%s\n".format(m, s, ms, txt))
        }
    }
    return text to null
}

// ==================== 歌名模糊匹配（v2.20，供各词源择优） ====================

/** 归一化：保留字母与数字（含 CJK），忽略空格/标点/全半角差异与大小写 */
internal fun normalizeMatchText(s: String): String = buildString {
    for (ch in s.lowercase()) if (ch.isLetterOrDigit()) append(ch)
}

/** 歌名模糊吻合：归一化后的双向包含关系（"晴天" 吻合 "晴天 (Live)"） */
internal fun looseNameMatch(candidate: String, target: String): Boolean {
    val c = normalizeMatchText(candidate)
    val t = normalizeMatchText(target)
    if (c.isEmpty() || t.isEmpty()) return false
    return c.contains(t) || t.contains(c)
}

/**
 * 在搜索结果数组中选出歌名与 [titleHint] 最吻合的下标（v2.20）。
 * 无提示/无吻合时回退 0（保持旧版行为）；数组为空返回 -1。
 * @param nameKeys 结果项里可能的歌名字段名（网易云 name / QQ songname 等）
 */
internal fun bestMatchIndex(arr: JSONArray, titleHint: String?, nameKeys: List<String>): Int {
    if (arr.length() == 0) return -1
    if (titleHint.isNullOrBlank()) return 0
    val target = normalizeMatchText(titleHint)
    if (target.isEmpty()) return 0
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        for (k in nameKeys) {
            val name = normalizeMatchText(o.optString(k, ""))
            if (name.isNotEmpty() && (name.contains(target) || target.contains(name))) return i
        }
    }
    return 0
}

/**
 * 清理文件名/歌名为可搜索歌名（v2.20 增强）：
 * 去扩展名、去括号注释（Live/翻自/Explicit 等）、去开头曲目序号（"01." / "01_" / "01 - "）、
 * 收敛多余空白并去掉首尾残留分隔符
 */
fun cleanLocalName(name: String): String =
    name
        .replace(Regex("\\.(mp3|flac|wav|m4a|ogg|aac|opus|wma|ape)$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
        .replace(Regex("^\\d{1,4}\\s*[._]\\s*"), " ")
        .replace(Regex("^\\d{1,4}\\s+-\\s+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .trim('-', '_', '\u2013', '\u2014', '\u00b7', '~', ' ')

// ==================== 下载目录 ====================

/**
 * 歌曲下载目录：优先公共 Music/LinBoxMusic（配合 MANAGE_EXTERNAL_STORAGE），
 * 不可写时回退应用专属外部目录 getExternalFilesDir(Music)/LinBoxMusic。
 */
fun musicDownloadDir(context: Context): File {
    val candidates = listOf(
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                ?.let { File(it, "LinBoxMusic") }
        }.getOrNull(),
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { File(it, "LinBoxMusic") },
        File(context.filesDir, "music_downloads")
    )
    for (dir in candidates) {
        if (dir == null) continue
        if (dir.isDirectory && dir.canWrite()) return dir
        if (!dir.exists()) {
            if (dir.mkdirs() || (dir.isDirectory && dir.canWrite())) return dir
        }
    }
    return File(context.filesDir, "music_downloads").apply { mkdirs() }
}

/** 清理文件名中的非法字符 */
fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "未命名" }
