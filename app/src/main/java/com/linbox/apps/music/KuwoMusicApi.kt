package com.linbox.apps.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 酷我音乐在线 API（v2.17 新增，云音乐应用数据源）。
 *
 * 移植自用户提供的 linboxyy.py（聆音音乐播放器）中的 KuwoMusicAPI 类：
 * - 搜索：http://www.kuwo.cn/search/searchMusicBykeyWord（需 Cookie kw_token + Referer）
 * - 播放链接：多个第三方解析 API 依次尝试（kw.php / cenguigui），失败回退备用镜像
 * - 歌词：https://m.kuwo.cn/newh5/singles/songinfoandlrc（返回逐行时间戳+文本）
 * - 网易云歌词兜底：music.163.com/api/search/get 搜索 → api/song/lyric 取词（含翻译）
 *
 * 全部通过 HttpURLConnection + org.json 实现，不引入任何第三方依赖。
 */
object KuwoMusicApi {

    // ==================== 常量（与 linboxyy.py 保持一致） ====================

    private const val SEARCH_URL = "http://www.kuwo.cn/search/searchMusicBykeyWord"
    private const val SEARCH_FALLBACK_URL = "https://search.kuwo.cn/r.s"
    private const val LYRIC_URL = "https://m.kuwo.cn/newh5/singles/songinfoandlrc"

    /** 第三方播放链接解析 API（顺序尝试，与 linboxyy.py 一致并补充标准音质备选） */
    private val PLAY_URL_APIS = listOf(
        "https://musicapi.haitangw.net/music/kw.php?id=%s&level=exhigh&type=json",
        "https://kw-api.cenguigui.cn/?id=%s&type=song&level=exhigh&format=json",
        "http://music.nxinxz.com/kw.php?id=%s&level=standard&type=json",
        "https://kw-api.cenguigui.cn/?id=%s&type=song&level=standard&format=json",
        "https://musicapi.haitangw.net/music/kw.php?id=%s&level=standard&type=json"
    )

    internal val UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    internal val UA_PHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)"

    // ==================== 数据模型 ====================

    /** 搜索结果中的一首歌曲 */
    data class Song(
        val id: String,
        val name: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val pic: String
    )

    // ==================== 搜索 ====================

    /**
     * 关键词搜索歌曲（移植自 linboxyy.py KuwoMusicAPI.search）。
     * @param page 页码（从 0 开始）
     * @param count 每页数量
     * @return Result.success(歌曲列表) / Result.failure(异常，message 为用户可读错误)
     */
    suspend fun search(keyword: String, page: Int = 0, count: Int = 20): Result<List<Song>> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@withContext Result.success(emptyList())

            val query = "vipver=1&client=kt&ft=music&cluster=0&strategy=2012&encoding=utf8" +
                "&rformat=json&mobi=1&issubtitle=1&show_copyright_off=1" +
                "&pn=$page&rn=$count&all=" + enc(kw)

            val headers = mapOf(
                "User-Agent" to UA_PC,
                "Referer" to "https://www.kuwo.cn/",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "Cookie" to "kw_token=abcdefghijklmnopqrst"
            )

            // 主端点 → 备用端点，两处返回结构一致（abslist）
            var lastError: Exception? = null
            for (base in listOf(SEARCH_URL, SEARCH_FALLBACK_URL)) {
                try {
                    val conn = openFollowing("$base?$query", headers)
                    val body = readText(conn)
                    val songs = parseSearchResponse(body)
                    if (songs.isNotEmpty()) return@withContext Result.success(songs)
                    lastError = IOException("接口未返回歌曲数据")
                } catch (e: Exception) {
                    lastError = e
                }
            }
            Result.failure(lastError ?: IOException("搜索失败，请检查网络"))
        }

    /** 解析搜索响应：abslist 数组 → Song 列表（兼容 MUSICRID/NAME/ARTIST 等多种字段名） */
    private fun parseSearchResponse(body: String): List<Song> {
        val root = JSONObject(body)
        var abslist: JSONArray? = root.optJSONArray("abslist")
        if (abslist == null) {
            abslist = root.optJSONObject("data")?.optJSONArray("abslist")
        }
        if (abslist == null) return emptyList()

        val songs = mutableListOf<Song>()
        for (i in 0 until abslist.length()) {
            val item = abslist.optJSONObject(i) ?: continue
            val rid = item.strOr("MUSICRID") ?: item.strOr("musicrid") ?: continue
            val songId = rid.replace("MUSIC_", "").replace("music_", "").replace("MUSIC-", "")
            if (songId.isEmpty() || !songId.all { it.isDigit() }) continue

            val name = item.strOr("NAME") ?: item.strOr("name") ?: item.strOr("songName") ?: ""
            if (name.isEmpty()) continue

            val artist = item.strOr("ARTIST") ?: item.strOr("artist") ?: ""
            val album = item.strOr("ALBUM") ?: item.strOr("album") ?: ""
            val durationSec = item.optLong("DURATION", item.optLong("duration", 0L))
            val pic = item.strOr("MPIC") ?: item.strOr("pic") ?: item.strOr("hts_MVPIC") ?: ""

            songs.add(
                Song(
                    id = songId,
                    name = name,
                    artist = artist.replace("&nbsp;", " ").trim(),
                    album = album,
                    durationMs = if (durationSec > 0) durationSec * 1000L else 0L,
                    pic = pic
                )
            )
        }
        return songs
    }

    // ==================== 播放链接 ====================

    /**
     * 获取播放直链（移植自 linboxyy.py KuwoMusicAPI.get_play_url）。
     * 依次尝试多个第三方解析 API，提取 url / data.url / data 数组等字段。
     */
    suspend fun getPlayUrl(musicId: String): Result<String> = withContext(Dispatchers.IO) {
        val headers = mapOf(
            "User-Agent" to UA_PC,
            "Referer" to "https://www.kuwo.cn/"
        )
        for (api in PLAY_URL_APIS) {
            val url = String.format(api, musicId)
            try {
                val conn = openFollowing(url, headers, readTimeoutMs = 10_000)
                val body = readText(conn)
                val direct = extractPlayUrl(JSONObject(body))
                if (direct != null && direct.startsWith("http")) {
                    return@withContext Result.success(direct)
                }
            } catch (_: Exception) {
                // 单个 API 失败，继续尝试下一个
            }
        }
        Result.failure(IOException("无法获取播放链接，请稍后重试"))
    }

    /** 从各解析 API 的 JSON 中鲁棒地提取播放直链 */
    private fun extractPlayUrl(root: JSONObject): String? {
        // 顶层 url / data 为字符串
        root.strOr("url")?.let { if (it.startsWith("http")) return it }
        root.strOr("data")?.let { if (it.startsWith("http")) return it }

        // url 为数组
        root.optJSONArray("url")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.startsWith("http")) return s
            }
        }

        // data 为对象：data.url / data.URL
        val dataObj = root.optJSONObject("data")
        if (dataObj != null) {
            dataObj.strOr("url")?.let { if (it.startsWith("http")) return it }
            dataObj.strOr("URL")?.let { if (it.startsWith("http")) return it }
            dataObj.optJSONArray("url")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.startsWith("http")) return s
                }
            }
        }

        // data 为数组：data[0].url
        val dataArray = root.optJSONArray("data")
        if (dataArray != null && dataArray.length() > 0) {
            val first = dataArray.optJSONObject(0)
            first?.strOr("url")?.let { if (it.startsWith("http")) return it }
            first?.strOr("URL")?.let { if (it.startsWith("http")) return it }
        }
        return null
    }

    // ==================== 歌词（酷我） ====================

    /**
     * 获取酷我歌词（移植自 linboxyy.py KuwoMusicAPI.get_lyric）。
     * 返回 [时间戳ms, 歌词文本] 列表，按时间升序。
     */
    suspend fun getKuwoLyric(musicId: String): Result<List<Pair<Long, String>>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$LYRIC_URL?musicId=$musicId"
                val conn = openFollowing(
                    url,
                    mapOf(
                        "User-Agent" to UA_PHONE,
                        "Referer" to "https://m.kuwo.cn/yinyue/$musicId",
                        "Accept" to "application/json, text/plain, */*"
                    ),
                    readTimeoutMs = 10_000
                )
                val root = JSONObject(readText(conn))
                if (root.optInt("code", 0) != 200) {
                    return@withContext Result.failure(IOException("未找到歌词"))
                }
                val lrcList = root.optJSONObject("data")?.optJSONArray("lrclist")
                if (lrcList == null || lrcList.length() == 0) {
                    return@withContext Result.failure(IOException("该歌曲暂无歌词"))
                }
                val lines = mutableListOf<Pair<Long, String>>()
                for (i in 0 until lrcList.length()) {
                    val obj = lrcList.optJSONObject(i) ?: continue
                    val timeMs = obj.optLong("time", 0L)
                    val text = obj.strOr("lineLyric") ?: obj.strOr("text") ?: ""
                    lines.add(timeMs to text)
                }
                lines.sortBy { it.first }
                Result.success(lines)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ==================== 歌词（网易云兜底，移植自 linboxyy.py LyricDownloader） ====================

    /**
     * 网易云歌词兜底：按关键词搜索歌曲 → 取歌词（原文 + 翻译）。
     * v2.20：搜索扩到 8 条候选，优先取歌名与 [titleHint] 吻合的结果，
     * 避免旧版盲取第一条时命中同名的翻唱/伴奏/纯音乐。
     * @return Pair(原文LRC文本, 翻译LRC文本可空)，或失败
     */
    suspend fun getNeteaseLyric(
        keyword: String,
        titleHint: String? = null
    ): Result<Pair<String, String?>> =
        withContext(Dispatchers.IO) {
            try {
                val headers = mapOf(
                    "User-Agent" to UA_PC,
                    "Referer" to "https://music.163.com/"
                )
                // 1) 搜索歌曲拿 ID（limit=8，多候选择优）
                val searchUrl = "https://music.163.com/api/search/get/?s=${enc(keyword)}&type=1&limit=8"
                val searchConn = openFollowing(searchUrl, headers, readTimeoutMs = 10_000)
                val searchRoot = JSONObject(readText(searchConn))
                if (searchRoot.optInt("code", 0) != 200) {
                    return@withContext Result.failure(IOException("网易云搜索失败"))
                }
                val songs = searchRoot.optJSONObject("result")?.optJSONArray("songs")
                val songId = if (songs != null && songs.length() > 0) {
                    val best = bestMatchIndex(songs, titleHint, listOf("name"))
                    songs.optJSONObject(best)?.optLong("id", -1L) ?: -1L
                } else -1L
                if (songId <= 0) return@withContext Result.failure(IOException("未找到匹配歌曲"))

                // 2) 拉取歌词（原文 lv + 逐字 kv + 翻译 tv）
                val lyricUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=1"
                val lyricConn = openFollowing(lyricUrl, headers, readTimeoutMs = 10_000)
                val lyricRoot = JSONObject(readText(lyricConn))
                val main = lyricRoot.optJSONObject("lrc")?.strOr("lyric")
                if (main.isNullOrBlank()) return@withContext Result.failure(IOException("该歌曲暂无歌词"))
                val translation = lyricRoot.optJSONObject("tlyric")?.strOr("lyric")
                    ?.takeIf { it.isNotBlank() }
                Result.success(main to translation)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ==================== 文件下载 ====================

    /**
     * 流式下载文件（移植自 linboxyy.py KuwoMusicAPI.download_file）。
     * @param onProgress (已下载字节, 总字节)，在 IO 线程回调
     */
    suspend fun downloadFile(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val conn = openFollowing(
                url,
                mapOf("User-Agent" to UA_PC),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 60_000
            )
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            var downloaded = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) onProgress(downloaded, total)
                    }
                }
            }
            if (tmp.renameTo(dest)) {
                Result.success(dest)
            } else {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
                Result.success(dest)
            }
        } catch (e: Exception) {
            Result.failure(IOException("下载失败: ${e.message}", e))
        }
    }

    // ==================== HTTP 工具 ====================

    /**
     * 打开带手动重定向跟随的 GET 连接。
     * HttpURLConnection 默认不跟随跨协议（http→https）重定向，部分解析 API 会
     * 返回 30x，因此手动跟随（最多 5 跳）。
     */
    internal fun openFollowing(
        urlStr: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 15_000
    ): HttpURLConnection {
        var current = urlStr
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty() || redirects >= 5) {
                    throw IOException("重定向次数过多")
                }
                current = URL(URL(current), loc).toString()
                redirects++
                continue
            }
            return conn
        }
    }

    /** 读取响应体文本（2xx 否则抛错），统一 UTF-8 */
    internal fun readText(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw IOException("HTTP $code")
        return text
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20")

    internal fun encodeQuery(s: String): String = enc(s)

    // ==================== JSON 扩展 ====================

    /** 读取字符串字段，缺失或 null 返回 null（区别于 optString 的 ""） */
    internal fun JSONObject.strOr(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        return if (v.isEmpty()) null else v
    }
}
