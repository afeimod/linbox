package com.linbox.apps.music

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.apps.music.MusicStore as Store
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 3D 歌词秀（v2.17 图1；v2.20.3 真透视歌词墙；v2.21 行内左右字体差 + KTV 渐进 + 自定义封面/光盘）
 * - 深色沉浸背景：封面大图模糊铺底 + 黑色渐变压暗（v2.21 默认接近清晰/最亮）
 * - 左侧：圆角封面卡片 + 右后方探出的旋转 CD（v2.21 封面/光盘均可自定义图片）
 * - 右侧：真 3D 透视歌词墙 —— 整面墙绕 X 轴俯仰 + 绕 Y 轴偏航；每行叠加
 *   「左右字体差」行内逐字字号渐变（行首小行尾大，v2.21.1 重做，替换行级 rotationY）；
 *   当前行支持高亮颜色与 KTV 渐进填色（按播放进度从左向右扫开）
 * - 左上角《歌名》— 歌手标题，右下角模式/上一首/播放/下一首/歌词下载控制
 *
 * v2.19 设置即时生效机制保留：组合期在自身作用域直读快照 State（settingsProvider()），
 * 滑条一变直接失效重组/重绘，不依赖参数链传递。
 */
@Composable
fun Lyrics3DPage(
    song: SongInfo?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isPreparing: Boolean,
    lyric: LyricsDoc?,
    lyricLoading: Boolean,
    playMode: Int,
    /** v2.19：设置提供者，每次调用返回最新 MusicSettings（快照读，即时生效） */
    settingsProvider: () -> MusicSettings,
    /** v2.21：实时进度提供者（直读 MediaPlayer，供 KTV 逐字填充平滑扫色） */
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onCycleMode: () -> Unit,
    onDownloadLyric: () -> Unit,
    onClose: () -> Unit,
    /** v2.21.3：所在窗口是否处于真全屏（控制按钮图标切换） */
    isTrueFullscreen: Boolean = false,
    /** v2.21.3：全屏按钮回调 —— 切换窗口真全屏，返回键恢复 */
    onToggleFullscreen: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 组合期读取：背景模式等参与重组的设置（v2.19：所在作用域直读快照 State）
    val settings = settingsProvider()

    Box(modifier.fillMaxSize().background(Mc.lyricBg)) {
        // ===== 背景（v2.18 可自定义）：封面模糊 / 纯色 / 渐变 / 自定义图片 =====
        when (settings.lyricBgMode) {
            MusicSettings.BG_SOLID -> {
                Box(Modifier.matchParentSize().background(Color(settings.lyricBgColor)))
            }
            MusicSettings.BG_GRADIENT -> {
                val pair = LyricBgGradients.getOrElse(settings.lyricBgGradient) { LyricBgGradients[0] }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.verticalGradient(pair))
                )
            }
            MusicSettings.BG_IMAGE -> {
                BgImage(settings.lyricBgImage, Modifier.matchParentSize())
            }
            else -> {
                // 封面模糊铺底（默认，对应图1）—— v2.20 模糊半径可调（0 = 清晰不模糊）
                AsyncCover(
                    url = song?.picUrl,
                    modifier = Modifier
                        .matchParentSize()
                        .then(
                            if (settings.coverBlur > 0.5f) Modifier.blur(settings.coverBlur.dp)
                            else Modifier
                        )
                        .background(Mc.lyricBg)
                )
            }
        }
        // 深色渐变压暗（图片/封面模式下加重且 v2.20 强度可调，纯色/渐变模式轻微提 readability）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        if (settings.lyricBgMode == MusicSettings.BG_SOLID ||
                            settings.lyricBgMode == MusicSettings.BG_GRADIENT
                        ) {
                            listOf(Color(0x33000000), Color(0x55000000), Color(0x77000000))
                        } else {
                            // v2.20：压暗强度可调（默认 0.85 与旧版视觉一致）
                            val d = settings.lyricBgDim
                            listOf(
                                Color.Black.copy(alpha = (d - 0.15f).coerceAtLeast(0f)),
                                Color.Black.copy(alpha = d),
                                Color.Black.copy(alpha = (d + 0.13f).coerceAtMost(0.96f))
                            )
                        }
                    )
                )
        )

        // ===== 内容 =====
        Column(Modifier.fillMaxSize()) {
            // ---- 顶部标题栏：返回 + 《歌名》 + 歌手 + 歌词下载 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 14.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "《${song?.name ?: "未在播放"}》",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song?.artist?.takeIf { it.isNotBlank() } ?: "—",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 歌词来源标记
                if (lyric != null) {
                    Text(
                        text = when (lyric.source) {
                            "netease" -> "词源 网易云"
                            "kuwo" -> "词源 酷我"
                            "qq" -> "词源 QQ音乐"
                            "lrclib" -> "词源 LRCLIB"
                            else -> "已缓存"
                        },
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDownloadLyric) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "下载歌词",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                // v2.21.3：全屏/退出全屏（真全屏隐藏标题栏与任务栏，返回键恢复）
                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (isTrueFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isTrueFullscreen) "退出全屏" else "全屏",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ---- 主体：左封面+CD / 右 3D 歌词墙 ----
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左：封面 + 旋转CD（占 38%）
                Box(
                    Modifier
                        .fillMaxHeight()
                        .weight(0.38f),
                    contentAlignment = Alignment.Center
                ) {
                    CoverWithDisc(
                        coverUrl = song?.picUrl,
                        customCover = settings.coverImage,
                        customDisc = settings.discImage,
                        isPlaying = isPlaying
                    )
                }

                // 右：3D 歌词墙（占 62%）
                Box(Modifier.weight(0.62f).fillMaxHeight()) {
                    if (lyric != null && lyric.lines.isNotEmpty()) {
                        LyricsWall(
                            doc = lyric,
                            positionMs = positionMs,
                            settingsProvider = settingsProvider,
                            positionProvider = positionProvider,
                            onSeek = onSeek,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (lyricLoading) {
                                CircularProgressIndicator(
                                    color = Color.White.copy(alpha = 0.6f),
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            Text(
                                text = if (lyricLoading) "正在获取歌词…" else "暂无歌词，请欣赏",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // ---- 底部控制条：模式/上一首/播放/下一首 + 进度 ----
            BottomControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                isPreparing = isPreparing,
                playMode = playMode,
                onSeek = onSeek,
                onToggle = onToggle,
                onNext = onNext,
                onPrev = onPrev,
                onCycleMode = onCycleMode
            )
        }
    }
}

// ==================== 封面 + 旋转 CD ====================

@Composable
private fun CoverWithDisc(
    coverUrl: String?,
    customCover: String?,
    customDisc: String?,
    isPlaying: Boolean
) {
    val cdAngle = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        // 播放时持续旋转（8s/圈），暂停时停在当前角度
        if (isPlaying) {
            while (true) {
                cdAngle.animateTo(
                    cdAngle.value + 360f,
                    tween(8000, easing = LinearEasing)
                )
                if (cdAngle.value >= Float.MAX_VALUE / 4f) break
            }
        }
    }

    // v2.21：盘面图优先级 —— 自定义光盘图片 > 自定义封面图片 > 歌曲专辑封面 > 银色回退
    // v2.20.3：与 AsyncCover 共用 CoverCache（同 URL 只下载一次）
    val context = LocalContext.current
    val discSrc = customDisc ?: customCover
    var discBmp by remember(coverUrl, discSrc) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverUrl, discSrc) {
        if (!discSrc.isNullOrEmpty()) {
            discBmp = loadBackgroundBitmap(context, discSrc, 600)
        } else if (coverUrl.isNullOrEmpty()) {
            discBmp = null
        } else if (!CoverCache.isResolved(coverUrl)) {
            val loaded = loadBitmap(coverUrl)
            CoverCache.put(coverUrl, loaded)
            discBmp = loaded
        } else {
            discBmp = CoverCache.get(coverUrl)
        }
    }

    Box(contentAlignment = Alignment.Center) {
        // CD 光盘：在封面右后方，盘面探出约 2/3 半径（对照参考图3）
        DiscCanvas(
            angle = cdAngle.value,
            cover = discBmp,
            modifier = Modifier
                .size(178.dp)
                .offset(x = 66.dp)
        )
        // 封面卡片（CD 左侧，压在光盘上）：v2.21 自定义封面图片优先
        if (!customCover.isNullOrEmpty()) {
            BgImage(
                customCover,
                Modifier
                    .size(190.dp)
                    .shadow(18.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            AsyncCover(
                url = coverUrl,
                modifier = Modifier
                    .size(190.dp)
                    .shadow(18.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

/**
 * CD 光盘绘制（v2.20.3）：有封面时盘面铺封面图（圆形裁剪）+ 同心唱片暗纹 +
 * 扫掠高光 + 半透明中心标贴与中孔；无封面（未加载/无图）回退银色反光盘面。
 */
@Composable
private fun DiscCanvas(angle: Float, cover: Bitmap?, modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer { rotationZ = angle }) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        if (cover != null) {
            // 封面居中裁剪铺满盘面（参考图3：盘面即封面图案，随 CD 一起旋转）
            val srcMin = minOf(cover.width, cover.height)
            val srcOff = IntOffset((cover.width - srcMin) / 2, (cover.height - srcMin) / 2)
            val dstR = (2 * r).roundToInt().coerceAtLeast(1)
            val discRect = Rect(center.x - r, center.y - r, center.x + r, center.y + r)
            clipPath(Path().apply { addOval(discRect) }) {
                drawImage(
                    image = cover.asImageBitmap(),
                    srcOffset = srcOff,
                    srcSize = IntSize(srcMin, srcMin),
                    dstOffset = IntOffset((center.x - r).roundToInt(), (center.y - r).roundToInt()),
                    dstSize = IntSize(dstR, dstR)
                )
                // 唱片纹理：同心暗纹（半透明，不遮封面主色）
                for (i in 1..6) {
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.10f),
                        radius = r * (0.94f - i * 0.10f),
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }
                // 扫掠高光模拟盘面反光
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.White.copy(alpha = 0.26f), Color.Transparent,
                            Color.White.copy(alpha = 0.14f), Color.Transparent,
                            Color.Transparent, Color.White.copy(alpha = 0.26f)
                        ),
                        center
                    ),
                    radius = r,
                    center = center
                )
                // 中心标贴：半透明深色（封面隐约可见）+ 细环 + 中孔
                drawCircle(color = Color.Black.copy(alpha = 0.38f), radius = r * 0.30f, center = center)
                drawCircle(
                    color = Color.White.copy(alpha = 0.35f),
                    radius = r * 0.30f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawCircle(color = Color(0xFF0B0B10), radius = r * 0.055f, center = center)
            }
        } else {
            // 无封面回退：银色扫掠渐变盘面
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFFE9E9EF), Color(0xFF9C9CAC), Color(0xFFEDEDF3),
                        Color(0xFF80808F), Color(0xFFE2E2EA), Color(0xFF9A9AAA),
                        Color(0xFFE9E9EF)
                    ),
                    center
                ),
                radius = r,
                center = center
            )
            for (i in 1..4) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.06f),
                    radius = r * (0.92f - i * 0.13f),
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            drawCircle(color = Color(0xFF23232B), radius = r * 0.30f, center = center)
            drawCircle(
                color = Color(0xFFEC4141).copy(alpha = 0.9f),
                radius = r * 0.20f,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(color = Color(0xFF0B0B10), radius = r * 0.055f, center = center)
        }
        // 外缘描边（两种分支共用）
        drawCircle(
            color = Color.White.copy(alpha = 0.28f),
            radius = r,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

// ==================== 3D 歌词墙 ====================

/**
 * 3D 透视歌词墙（v2.20.3 重构；v2.21 行内左右字体差 + KTV 渐进）：
 * - 整面墙真透视：绕 X 轴俯仰（wallTiltX，默认 16° 顶部向后倒）+ 绕 Y 轴偏航
 *   （wallRotateY，默认 -14°）+ 透视相机拉近到 500*density —— 远行自然变小、
 *   行距自然收拢，朝右上角消失点汇聚，倾斜/视角滑条一动就有明显视觉反馈
 * - v2.21.1「左右字体差」：行内逐字字号渐变（lineYaw3d，%）—— 行首字符最小、
 *   行尾字符最大线性插值，所见即所得的左小右大（v2.21 的行级 rotationY 透视
 *   在窄行+远相机下肉眼不可见，已废弃）
 * - 每行仅按纵深强度做轻量额外缩小/变暗 + 朝消失点方向的横向漂移；
 *   行切换通过 animateFloatAsState 平滑过渡（可关闭）
 * - 当前行高亮颜色可调；开启 KTV 模式后按播放进度从左向右渐进出色（clipRect 扫掠）
 * - 设置在组合期直读快照 State，滑条一变直接重组生效（v2.19 机制）
 */
@Composable
private fun LyricsWall(
    doc: LyricsDoc,
    positionMs: Long,
    settingsProvider: () -> MusicSettings,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val idx = remember(positionMs, doc) { doc.indexAt(positionMs) }
    val listState = rememberLazyListState()

    // 当前行滚动到视口中部
    LaunchedEffect(idx, doc) {
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
        }
    }

    // 组合期直读：俯仰/偏航/纵深任一滑条变化 → 本作用域重组 → 图层参数更新
    val wallSettings = settingsProvider()

    BoxWithConstraints(modifier) {
        val padV = maxHeight * 0.34f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = padV, bottom = padV),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 真透视：俯仰 + 偏航 + 拉近的相机（500*density，旧值 1000 透视过弱
                    // 是“视角没变化”的主因）；裁剪发生在图层内容内部，旋转后超出
                    // 原边界的部分不会被 LazyColumn 裁掉，梯形透视完整可见
                    rotationX = wallSettings.wallTiltX
                    rotationY = wallSettings.wallRotateY
                    cameraDistance = 500f * density
                }
        ) {
            itemsIndexed(doc.lines, key = { i, _ -> i }) { i, line ->
                LyricLineItem(
                    line = line,
                    distance = i - idx,
                    lineEndMs = doc.lines.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 6000L),
                    settingsProvider = settingsProvider,
                    positionProvider = positionProvider,
                    onClick = { onSeek(line.timeMs) }
                )
            }
        }
    }
}

/**
 * v2.21.1 左右字体差：逐字字号渐变（替换 v2.21 行级 rotationY —— 窄行+远相机下不可见）。
 * 行首字符最小、行尾字符最大线性插值；diff = 最大差比例（0..0.45，来自 lineYaw3d/100）。
 * diff 为 0 或单字符时原样返回；代理对（emoji 等）合并为一个跨度避免拆散字形。
 */
private fun ltrSizedText(text: String, baseSp: Float, diff: Float): AnnotatedString {
    if (diff <= 0.005f || text.length < 2) return AnnotatedString(text)
    val n = text.length
    return buildAnnotatedString {
        append(text)
        var i = 0
        while (i < n) {
            var j = i + 1
            if (Character.isHighSurrogate(text[i]) && j < n && Character.isLowSurrogate(text[j])) j++
            val t = (i + j - 1).toFloat() / (n - 1).coerceAtLeast(1)
            addStyle(SpanStyle(fontSize = (baseSp * (1f + diff * (t - 0.5f) * 2f)).sp), i, j)
            i = j
        }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    distance: Int,
    lineEndMs: Long,
    settingsProvider: () -> MusicSettings,
    positionProvider: () -> Long,
    onClick: () -> Unit
) {
    // 组合期读取（v2.19）：字号/发光/翻译/动画开关 —— 所在作用域直读快照 State
    val settings = settingsProvider()
    // 距离做动画：切换当前行时整面墙平滑流动（可在设置中关闭）
    val animDist by animateFloatAsState(
        targetValue = distance.toFloat(),
        animationSpec = if (settings.lyricDynamic) tween(280) else snap(),
        label = "lyrDist"
    )
    val absDist = kotlin.math.abs(animDist)
    val active = distance == 0

    // v2.20.3：整墙透视已负责“远小近大”的主体效果，每行只做轻量额外收敛，
    // 彻底去掉每行 rotationX（旧版把矮行自转 = 行被压扁的“挤压感”元凶）
    val scale = if (active) 1.12f else (1f - (absDist * 0.045f)).coerceIn(0.62f, 1f)
    val lineAlpha = if (active) 1f else (1f - absDist * 0.13f).coerceIn(0.12f, 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
            .graphicsLayer {
                // 纵深强度 tilt3d（0-45）：控制远行额外缩小/变暗与消失点漂移幅度，
                // 图层块内直读快照 State，滑条一变立即重绘（v2.19 机制）
                val s = settingsProvider()
                val k = (absDist * s.tilt3d / 120f).coerceIn(0f, 1f)
                val sc = scale * (1f - 0.30f * k)
                scaleX = sc
                scaleY = sc
                alpha = lineAlpha * (1f - 0.25f * k)
                // 朝消失点漂移（对照参考图3）：上方行向右上、下方行向左下
                //（漂移量与纵深强度联动；0°/纵深 0 时完全复原平面模式）
                translationX = (-animDist * s.tilt3d * 0.6f * density)
                    .coerceIn(-140f * density, 140f * density)
                // v2.21.1：行级 rotationY 透视已移除（窄行+远相机下肉眼不可见），
                // 左右字体差改为逐字字号渐变，见 ltrSizedText()
            }
    ) {
        if (active && settings.ktvMode) {
            // v2.21 KTV 渐进样式：未唱灰字打底，已唱高亮色按播放进度从左向右扫开
            KtvSweepText(
                text = line.text.ifBlank { "···" },
                fontSizeSp = settings.lyricFontSize,
                diff = settings.lineYaw3d / 100f,
                fillColor = Color(settings.highlightColor),
                glow = settings.lyricGlow,
                lineStartMs = line.timeMs,
                lineEndMs = lineEndMs,
                positionProvider = positionProvider
            )
        } else {
            Text(
                text = ltrSizedText(
                    line.text.ifBlank { "···" },
                    (if (active) settings.lyricFontSize else settings.lyricFontSize * 0.77f).toFloat(),
                    settings.lineYaw3d / 100f
                ),
                fontSize = if (active) settings.lyricFontSize.sp
                else (settings.lyricFontSize * 0.77f).roundToInt().sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                // v2.21：当前行颜色可自定义（默认白）
                color = if (active) Color(settings.highlightColor) else Color(0xFFD5D5DE),
                style = if (active && settings.lyricGlow) {
                    TextStyle(
                        shadow = Shadow(
                            color = Color(settings.highlightColor).copy(alpha = 0.75f),
                            blurRadius = 22f
                        )
                    )
                } else {
                    TextStyle.Default
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (settings.showTranslation && !line.translation.isNullOrBlank()) {
            Text(
                text = line.translation,
                fontSize = if (active) 13.sp else 11.sp,
                color = Color.White.copy(alpha = if (active) 0.6f else 0.35f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * KTV 渐进填充文本（v2.21）：底层未唱灰字 + 上层高亮色已唱文字，
 * clipRect 按行内播放进度从左向右扫开。
 * 50ms 节拍直读 positionProvider（MusicEngine.rawPositionMs()，MediaPlayer 原生位置，
 * 非 300ms 状态 tick），扫色平滑；状态读发生在绘制阶段，不触发重组。
 */
@Composable
private fun KtvSweepText(
    text: String,
    fontSizeSp: Int,
    diff: Float,
    fillColor: Color,
    glow: Boolean,
    lineStartMs: Long,
    lineEndMs: Long,
    positionProvider: () -> Long
) {
    var frac by remember(lineStartMs, lineEndMs) { mutableStateOf(0f) }
    LaunchedEffect(lineStartMs, lineEndMs) {
        val span = (lineEndMs - lineStartMs).coerceAtLeast(1L)
        while (true) {
            frac = ((positionProvider() - lineStartMs).toFloat() / span).coerceIn(0f, 1f)
            delay(50)
        }
    }
    val glowStyle = if (glow) {
        TextStyle(shadow = Shadow(color = fillColor.copy(alpha = 0.75f), blurRadius = 22f))
    } else {
        TextStyle.Default
    }
    // v2.21.1：逐字字号渐变（左右字体差），与扫色裁剪叠加；diff 变化时重建
    val sized = remember(text, diff) { ltrSizedText(text, fontSizeSp.toFloat(), diff) }
    Box {
        Text(
            text = sized,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.34f),
            style = glowStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = sized,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = fillColor,
            style = glowStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.drawWithContent {
                clipRect(right = size.width * frac) { this@drawWithContent.drawContent() }
            }
        )
    }
}

// ==================== 底部控制条 ====================

@Composable
private fun BottomControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isPreparing: Boolean,
    playMode: Int,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onCycleMode: () -> Unit
) {
    var userSeeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableStateOf(0f) }
    val maxPos = durationMs.coerceAtLeast(1L).toFloat()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 10.dp)
    ) {
        // 按钮行
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 播放模式
            IconButton(onClick = onCycleMode) {
                Icon(
                    imageVector = when (playMode) {
                        Store.MODE_LOOP_ONE -> Icons.Filled.RepeatOne
                        Store.MODE_SHUFFLE -> Icons.Filled.Shuffle
                        else -> Icons.Filled.Repeat
                    },
                    contentDescription = "播放模式",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            // 上一首
            IconButton(onClick = onPrev) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "上一首",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            // 播放/暂停（圆环按钮，对应图1 左下）
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                if (isPreparing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = onNext) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "下一首",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            // 进度时间
            Text(
                text = "${fmtTime(positionMs)} / ${fmtTime(durationMs)}",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
        }

        // 细进度条
        Slider(
            value = if (userSeeking) seekPos else positionMs.coerceAtMost(durationMs).toFloat(),
            valueRange = 0f..maxPos,
            onValueChange = {
                userSeeking = true
                seekPos = it
            },
            onValueChangeFinished = {
                onSeek(seekPos.toLong())
                userSeeking = false
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.9f),
                inactiveTrackColor = Color.White.copy(alpha = 0.22f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
        )
    }
}
