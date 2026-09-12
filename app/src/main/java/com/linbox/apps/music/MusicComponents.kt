package com.linbox.apps.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云音乐共享 UI 组件（v2.17）：
 * - [Mc] 固定配色（网易云音乐 PC 版风格：白底 + 红色 #EC4141 主色 + 歌词页深色）
 * - [AsyncCover] 封面图异步加载（内存缓存 + 失败负缓存，HttpURLConnection 实现）
 * - [EqBars] 播放中行的小均衡器动画
 * - [McSearchField] 顶部搜索框（BasicTextField 定制，回车触发搜索）
 * - [McEmpty] 空状态占位
 */

// ==================== 配色 ====================

/** 网易云音乐 PC 版风格固定配色 */
object Mc {
    val red = Color(0xFFEC4141)
    val redDeep = Color(0xFFC73535)
    val bg = Color(0xFFFCFCFD)            // 主内容背景
    val sidebarBg = Color(0xFFF4F5F7)     // 侧栏背景
    val divider = Color(0xFFE8E9EC)
    val textPrimary = Color(0xFF2B2B31)
    val textSecondary = Color(0xFF6C6C74)
    val textTertiary = Color(0xFFA2A2AA)
    val hover = Color(0xFFF1F1F4)
    val selectedRow = Color(0x14EC4141)  // 当前播放行红色 8% 叠加
    val searchFieldBg = Color(0xFFEFEFF1)

    // 歌词页深色系（对应图1 3D歌词秀）
    val lyricBg = Color(0xFF0B0B10)
    val lyricDim = Color(0xFFC9C9D2)
}

// ==================== 自定义背景半透明表面（v2.21.5） ====================

/**
 * v2.21.5：主页自定义背景（纯色/渐变/图片）激活标记，由 App 根部 CompositionLocalProvider 提供。
 * 内容区表面（设置卡片/模式芯片/搜索框/热门词芯片等）据此自动半透明融入背景，
 * 与侧栏（白 72%）/底栏（白 80%）同一视觉语言。
 */
val LocalHomeCustomBg = compositionLocalOf { false }

/**
 * v2.21.5：表面配色 —— 自定义背景激活时给白色/浅灰表面加透明度使其融入背景：
 * 大卡片面板用 0.72（同侧栏），小芯片/输入框用 0.60；未开启自定义背景时返回原色。
 */
fun surfaceColor(base: Color, customBg: Boolean, alpha: Float): Color =
    if (customBg) base.copy(alpha = alpha) else base

// ==================== 时间格式化 ====================

fun fmtTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

// ==================== 封面加载 ====================

/** 封面内存缓存（LRU 简化版），失败 URL 记入负缓存避免反复请求 */
object CoverCache {
    private const val MAX = 128
    private val map = LinkedHashMap<String, Bitmap?>()
    private val failed = HashSet<String>()
    private val lock = Any()

    fun get(url: String): Bitmap? = synchronized(lock) { map[url] }

    fun isResolved(url: String): Boolean = synchronized(lock) { map.containsKey(url) || failed.contains(url) }

    fun put(url: String, bmp: Bitmap?) = synchronized(lock) {
        if (bmp == null) {
            failed.add(url)
        } else {
            if (map.size >= MAX) {
                val it = map.entries.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            map[url] = bmp
        }
    }
}

/** 网络加载位图，超限降采样到 600px 内 */
suspend fun loadBitmap(url: String, maxPx: Int = 600): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            if (conn.responseCode !in 200..299) return@runCatching null
            val bmp = BitmapFactory.decodeStream(conn.inputStream, null, BitmapFactory.Options())
                ?: return@runCatching null
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > maxPx) {
                val scale = maxPx.toFloat() / longest
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

/**
 * 异步封面组件：有图显示封面（Crop 裁剪），无图/加载中显示深色渐变 + 音符占位。
 * @param decorative 为 true 时不画占位音符（歌词页背景用，避免大图标）
 */
@Composable
fun AsyncCover(
    url: String?,
    modifier: Modifier = Modifier,
    decorative: Boolean = false
) {
    var bmp by remember(url) { mutableStateOf(CoverCache.get(url ?: "")) }
    LaunchedEffect(url) {
        if (url.isNullOrEmpty()) {
            bmp = null
        } else if (!CoverCache.isResolved(url)) {
            val loaded = loadBitmap(url)
            CoverCache.put(url, loaded)
            bmp = loaded
        } else {
            bmp = CoverCache.get(url)
        }
    }
    val currentBmp = bmp
    if (currentBmp != null) {
        Image(
            bitmap = currentBmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.background(
                Brush.linearGradient(listOf(Color(0xFF2E2E38), Color(0xFF3D3D4A)))
            ),
            contentAlignment = Alignment.Center
        ) {
            if (!decorative) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color(0xFF71717E),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ==================== 均衡器动画 ====================

/** 播放中行首的小均衡器（3 根跳动红条） */
@Composable
fun EqBars(
    modifier: Modifier = Modifier,
    color: Color = Mc.red
) {
    val inf = rememberInfiniteTransition(label = "eq")
    val a1 = inf.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse),
        label = "a1"
    )
    val a2 = inf.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse),
        label = "a2"
    )
    val a3 = inf.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(490, easing = LinearEasing), RepeatMode.Reverse),
        label = "a3"
    )
    Row(
        modifier = modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(Modifier.size(3.dp, 12.dp * a1.value).background(color))
        Box(Modifier.size(3.dp, 12.dp * a2.value).background(color))
        Box(Modifier.size(3.dp, 12.dp * a3.value).background(color))
    }
}

// ==================== 搜索框 ====================

/** 顶部搜索框：灰底圆角 + 放大镜 + 可清空，回车/搜索键触发 */
@Composable
fun McSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "搜索歌曲、歌手、专辑"
) {
    // v2.21.5：自定义主页背景激活时搜索框半透明融入（浅灰玻璃，深色文字仍可读）
    val fieldBg = surfaceColor(Mc.searchFieldBg, LocalHomeCustomBg.current, 0.60f)
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fieldBg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = Mc.textTertiary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = hint,
                    color = Color(0xFFB9B9C0),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Mc.textPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(Mc.red),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "清空",
                    tint = Mc.textTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ==================== 空状态 ====================

/** 列表空状态占位 */
@Composable
fun McEmpty(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color(0xFFD9D9DF),
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(text = text, color = Mc.textTertiary, fontSize = 13.sp)
    }
}

// ==================== 文本溢出省略 ====================

/** 单行省略文本（列表行标题/歌手共用） */
@Composable
fun EllipsisText(
    text: String,
    fontSize: Int,
    color: Color,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        fontSize = fontSize.sp,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** 滚动跑马灯文本（底栏歌名过长时滚动） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(text: String, fontSize: Int, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = fontSize.sp,
        color = color,
        maxLines = 1,
        modifier = modifier.fillMaxWidth().basicMarquee()
    )
}

// ==================== 背景自定义（v2.18 设置中心） ====================

/**
 * 歌词秀背景渐变预设（深色系，叠加在歌词页底层）。
 * 每项为自上而下的颜色对，用于 Brush.verticalGradient。
 */
val LyricBgGradients: List<List<Color>> = listOf(
    listOf(Color(0xFF1B1B2F), Color(0xFF0B0B10)),   // 深夜蓝紫
    listOf(Color(0xFF2D1B2E), Color(0xFF0B0B10)),   // 暗夜玫瑰
    listOf(Color(0xFF12302B), Color(0xFF0B0B10)),   // 墨绿
    listOf(Color(0xFF33261A), Color(0xFF0B0B10)),   // 咖啡
    listOf(Color(0xFF101C33), Color(0xFF0B0B10)),   // 深海
    listOf(Color(0xFF1F1F1F), Color(0xFF000000))    // 石墨
)

/** 主页背景渐变预设（浅色系，替代默认白底） */
val HomeBgGradients: List<List<Color>> = listOf(
    listOf(Color(0xFFFFF5F5), Color(0xFFFDE8E8)),   // 淡樱红
    listOf(Color(0xFFF3F6FF), Color(0xFFE4EBFF)),   // 雾蓝
    listOf(Color(0xFFF2FBF4), Color(0xFFDFF2E6)),   // 薄荷绿
    listOf(Color(0xFFFFF8EC), Color(0xFFFFEDD3)),   // 奶油橙
    listOf(Color(0xFFF7F3FF), Color(0xFFE8DEFF)),   // 淡紫
    listOf(Color(0xFFF5F6F8), Color(0xFFE6E8EE))    // 岩灰
)

/** 主页背景纯色预设（浅色系） */
val HomeBgColors: List<Color> = listOf(
    Color(0xFFFCFCFD),
    Color(0xFFFFF1F1),
    Color(0xFFF0F5FF),
    Color(0xFFEFFAF2),
    Color(0xFFFFF7EA),
    Color(0xFFF4F2FF),
    Color(0xFF26262E)   // 深色模式感
)

/** 歌词秀背景纯色预设（深色系） */
val LyricBgColors: List<Color> = listOf(
    Color(0xFF0B0B10),
    Color(0xFF191922),
    Color(0xFF1B1B2F),
    Color(0xFF12251F),
    Color(0xFF2B1D1D),
    Color(0xFF000000)
)

/** 读取用户选择的背景位图（content:// URI 或绝对路径），超限降采样，IO 线程 */
suspend fun loadBackgroundBitmap(context: android.content.Context, src: String, maxPx: Int = 1280): Bitmap? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val input = if (src.startsWith("content:")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(src))
            } else {
                java.io.FileInputStream(src)
            }
            input?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            val longest = maxOf(opts.outWidth, opts.outHeight)
            while (longest / sample > maxPx * 2) sample *= 2
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val input2 = if (src.startsWith("content:")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(src))
            } else {
                java.io.FileInputStream(src)
            }
            input2?.use { BitmapFactory.decodeStream(it, null, opts2) }
        }.getOrNull()
    }

/**
 * 自定义背景图组件（v2.18）：根据 content:// URI 或文件路径异步加载并铺满显示。
 * 加载中 / 失败时透明（由调用方叠加兜底底色）。
 */
@Composable
fun BgImage(src: String?, modifier: Modifier = Modifier) {
    if (src.isNullOrEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    var bmp by remember(src) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(src) {
        bmp = loadBackgroundBitmap(context, src)
    }
    val current = bmp
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}
