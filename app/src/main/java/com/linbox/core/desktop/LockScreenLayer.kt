package com.linbox.core.desktop

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.util.L
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * v2.14 锁屏控制器：跨组合共享的锁定状态
 * （设置→个性化→锁屏界面 / 开始菜单电源→锁定 都能触发）。
 */
object LockController {
    var locked by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun lock() {
        locked = true
        // 锁定时重置交互计时，避免解锁后立刻被自动锁屏再次锁上
        AutoLockController.onInteraction()
    }

    fun unlock() {
        locked = false
        AutoLockController.onInteraction()
    }
}

/**
 * v2.17 自动锁屏：跨组合共享的最后交互时间戳。
 * DesktopEnvironment 根层的纯观察者 pointerInput 每次收到指针事件就刷新；
 * 定时协程检查空闲时长达到"自动锁屏"设置值时调用 LockController.lock()。
 */
object AutoLockController {
    var lastInteractionMs by androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())
        private set

    fun onInteraction() {
        lastInteractionMs = System.currentTimeMillis()
    }

    /** 距上次交互的空闲毫秒数 */
    fun idleMs(): Long = System.currentTimeMillis() - lastInteractionMs
}

/**
 * 锁屏界面：
 * - v2.14：全屏壁纸 + 深色遮罩；中央大时钟 + 日期 + 周几（跟随 12/24 小时制设置）；
 *   上滑超过 120dp 或点击任意处解锁；拦截返回键
 * - v2.17：
 *   - 支持独立锁屏壁纸（图片 / 视频，未设置时回退桌面壁纸 → 主题壁纸）；
 *   - 设置了锁屏密码（PIN）后，上滑/点击弹出数字密码面板，验证通过才解锁；
 *   - 连续输错 3 次后提供"忘记密码"兜底入口（清除密码并解锁，避免死锁）。
 */
@Composable
fun LockScreenLayer(
    theme: com.linbox.core.theme.WinTheme,
    lockWallpaperUri: String?,
    desktopWallpaperUri: String?,
    timeFormat24h: Boolean,
    pinHash: String?,
    onClearPin: () -> Unit,
    onUnlock: () -> Unit
) {
    // 上滑手势累计量：向上滑超过阈值触发解锁，同时让内容跟随手指上移（跟手体验）
    var dragUpPx by remember { mutableFloatStateOf(0f) }
    // v2.17：密码面板（声明需在 BackHandler 之前，供其闭包引用）
    var showPinPanel by remember { mutableStateOf(false) }

    val hasPin = !pinHash.isNullOrEmpty()

    // 拦截系统返回键：锁屏状态下不允许返回退出（密码面板开启时先收起面板）
    BackHandler(enabled = true) {
        if (showPinPanel) showPinPanel = false
    }

    // 每秒刷新时钟
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }

    val cal = remember(tick) { Calendar.getInstance() }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    val timeText = if (timeFormat24h) {
        String.format("%02d:%02d", hour, minute)
    } else {
        val h = if (hour % 12 == 0) 12 else hour % 12
        String.format("%d:%02d %s", h, minute, if (hour < 12) "AM" else "PM")
    }
    val dateText = remember(tick) {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val dowZh = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[dow - 1]
        val dowEn = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[dow - 1]
        if (com.linbox.util.L10n.current == "en-US") {
            "$dowEn, $m/$d/$y"
        } else {
            "$y 年 $m 月 $d 日 $dowZh"
        }
    }

    /** 上滑 / 点击的统一解锁入口：设置了密码则弹密码面板 */
    fun requestUnlock() {
        if (hasPin) showPinPanel = true else onUnlock()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 上滑解锁手势：不消费点击（点击由下层 detectTapGestures 处理）
            .pointerInput(hasPin) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        if (!showPinPanel) {
                            change.consume()
                            if (amount < 0) dragUpPx -= amount   // 向上滑为负值，累计正值
                        }
                    },
                    onDragEnd = {
                        if (!showPinPanel) {
                            val threshold = 120.dp.toPx()
                            if (dragUpPx > threshold) requestUnlock() else dragUpPx = 0f
                        }
                    }
                )
            }
            // 点击任意处直接解锁（移动端最顺手的方式）
            // 注意：detectTapGestures 是 PointerInputScope 的扩展函数，
            // Kotlin 不支持对扩展函数做全限定调用，必须 import 后裸调用。
            .pointerInput(hasPin) {
                detectTapGestures(
                    onTap = {
                        if (!showPinPanel) requestUnlock()
                    }
                )
            }
    ) {
        // Dp.toPx() 是 Density 的成员扩展，Composable 作用域内必须经 LocalDensity 转换
        val unlockDenominatorPx = with(LocalDensity.current) { 480.dp.toPx() }

        // ===== 壁纸 + 深色遮罩（上滑时整体跟手上移 + 渐隐） =====
        val unlockProgress = (dragUpPx / unlockDenominatorPx).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -unlockProgress * 180f
                    alpha = 1f - unlockProgress * 0.85f
                }
        ) {
            when {
                // v2.17 锁屏视频壁纸（video:// 本地文件）
                lockWallpaperUri?.startsWith("video://") == true ->
                    LockVideoWallpaper(
                        path = lockWallpaperUri.removePrefix("video://"),
                        modifier = Modifier.fillMaxSize()
                    )
                else -> WallpaperLayer(
                    themeWallpaper = theme.wallpaperAsset,
                    // 锁屏独立图片壁纸 → 桌面自定义壁纸 → 主题默认（WallpaperLayer 内部处理回退）
                    customWallpaperUri = lockWallpaperUri?.takeIf { it.startsWith("file://") }
                        ?: desktopWallpaperUri,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        // 中央时钟 + 日期（跟随上滑位移，产生视差）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(bottom = 96.dp)
                .graphicsLayer { translationY = -unlockProgress * 320f }
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = L("已锁定"),
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(bottom = 18.dp)
            )
            Text(
                text = timeText,
                color = Color.White,
                fontSize = 76.sp,
                fontWeight = FontWeight.Light
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = dateText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
        }

        // 底部解锁提示（跟随上滑位移）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = -unlockProgress * 260f
                    alpha = 1f - unlockProgress
                }
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = L("上滑或点击解锁"),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }

        // ===== v2.17 数字密码面板 =====
        if (showPinPanel) {
            PinUnlockPanel(
                theme = theme,
                pinHash = pinHash,
                onVerified = {
                    showPinPanel = false
                    onUnlock()
                },
                onForgot = {
                    // 连续输错的兜底：清除密码并解锁（设置里可重新设置）
                    onClearPin()
                    showPinPanel = false
                    onUnlock()
                },
                onCancel = {
                    showPinPanel = false
                    dragUpPx = 0f
                }
            )
        }
    }
}

// ============================================================
// v2.17：PIN 数字密码面板
// ============================================================

/**
 * 锁屏密码验证面板：圆点回显 + 3x4 数字键盘。
 * - 输错抖动提示并清空；
 * - 连错 3 次出现"忘记密码"兜底入口（锁屏密码遗忘时避免永久锁死）。
 */
@Composable
private fun PinUnlockPanel(
    theme: com.linbox.core.theme.WinTheme,
    pinHash: String?,
    onVerified: () -> Unit,
    onForgot: () -> Unit,
    onCancel: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var failed by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }

    // 错误抖动动画（左右摆动一轮后复位）
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(error) {
        if (error) {
            runCatching {
                shake.animateTo(1f, androidx.compose.animation.core.tween(50))
                shake.animateTo(-1f, androidx.compose.animation.core.tween(70))
                shake.animateTo(1f, androidx.compose.animation.core.tween(70))
                shake.animateTo(0f, androidx.compose.animation.core.tween(70))
            }
            error = false
        }
    }

    /** 追加一位数字；校验统一由 LaunchedEffect(input) 完成 */
    fun onDigit(d: Char) {
        if (input.length >= 8) return
        input += d
    }

    // 输入变化后即时校验：
    // - 达到 4-8 位时检查是否命中（hash 全等，前缀不会误触发）
    // - 满 8 位仍未命中：报错清空，避免用户停在满位无法继续
    LaunchedEffect(input) {
        if (input.length >= 4) {
            if (hashPin(input) == pinHash) {
                onVerified()
            } else if (input.length >= 8) {
                error = true
                failed++
                input = ""
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                // 吞掉面板上的所有触摸，避免透传到锁屏手势层
                detectTapGestures { }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { translationX = shake.value * 18f }
                .clip(RoundedCornerShape(18.dp))
                .background(theme.startMenuColor.copy(alpha = 0.92f))
                .padding(horizontal = 26.dp, vertical = 24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = if (theme.isDark) Color.White else Color.Black,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = L("输入锁屏密码"),
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))

            // 圆点回显（最多 8 位）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(8) { idx ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (idx < input.length) theme.accentColor
                                else (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.25f)
                            )
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // 3x4 数字键盘
            val rows = listOf(
                listOf('1', '2', '3'),
                listOf('4', '5', '6'),
                listOf('7', '8', '9'),
                listOf('⌫', '0', '✓')
            )
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { key ->
                        Box(
                            modifier = Modifier
                                .size(width = 62.dp, height = 46.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(theme.buttonBackgroundColor)
                                .clickable {
                                    when (key) {
                                        in '0'..'9' -> onDigit(key)
                                        '⌫' -> input = input.dropLast(1)
                                        '✓' -> {
                                            // 手动确认：长度不足 4 位忽略
                                            if (input.length >= 4) {
                                                if (hashPin(input) == pinHash) {
                                                    onVerified()
                                                } else {
                                                    error = true
                                                    failed++
                                                    input = ""
                                                }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (key == '⌫') {
                                Icon(
                                    Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "退格",
                                    tint = if (theme.isDark) Color.White else Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Text(
                                    text = key.toString(),
                                    color = if (theme.isDark) Color.White else Color.Black,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = if (failed >= 3) L("忘记密码？点击此处清除密码并解锁")
                else L("4-8 位数字密码"),
                color = if (failed >= 3) Color(0xFFFF6B6B)
                else (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.45f),
                fontSize = 11.sp,
                modifier = Modifier.clickable(enabled = failed >= 3) { onForgot() }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = L("取消"),
                color = theme.accentColor,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onCancel() }
            )
        }
    }
}

/** v2.17：PIN 哈希（SHA-256 + 固定盐，与锁屏设置页一致） */
internal fun hashPin(pin: String): String = runCatching {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest("linbox_lock:$pin".toByteArray())
        .joinToString("") { String.format("%02x", it) }
}.getOrDefault("")

// ============================================================
// v2.17：锁屏视频壁纸（TextureView + MediaPlayer，cover 铺满 + 静音循环）
// ============================================================

/**
 * 视频壁纸：本地文件路径，静音循环播放。
 *
 * v2.19 重做（修复旧实现两个问题）：
 * 1. 「铺不满屏」：旧版用均匀 graphicsLayer 缩放做 cover，无法兼顾铺满与
 *    不变形；新版按视频宽高比计算【溢出容器】的 View 尺寸（等效
 *    ContentScale.Crop：短边贴齐、长边溢出居中裁切），TextureView 把画面
 *    拉伸到自身尺寸即可既铺满又不变形；
 * 2. 「换视频卡住」：AndroidView 的 factory 不随 path 重跑，换壁纸时
 *    TextureView 被复用 —— 它的 SurfaceTexture 已存在，
 *    onSurfaceTextureAvailable 不再触发，新播放器永远拿不到 Surface，
 *    画面冻结直到重启。新版 key(path) 强制整棵视图按路径重建，
 *    新 TextureView → 新 Surface → 新播放器立即开播；旧播放器由
 *    DisposableEffect(path) 及时释放。
 *
 * v2.18：改为 internal —— 桌面壁纸层（WallpaperLayer）复用同一实现
 * 支持"视频桌面壁纸"（DreamScene 风格）；锁屏视频壁纸同步受益。
 */
@Composable
internal fun LockVideoWallpaper(path: String, modifier: Modifier = Modifier) {
    // 视频宽高（prepare 后可得；未知时先按容器尺寸拉伸铺满）
    var videoW by remember(path) { mutableIntStateOf(0) }
    var videoH by remember(path) { mutableIntStateOf(0) }
    val player = remember(path) { MediaPlayer() }

    // 离开组合（解锁 / 换壁纸 / 换回图片壁纸）时释放播放器
    DisposableEffect(path) {
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }

        // cover 尺寸：等比放大视频直到铺满容器（短边贴齐、长边溢出居中裁掉）
        val viewW: Float
        val viewH: Float
        if (videoW > 0 && videoH > 0) {
            val videoAspect = videoW.toFloat() / videoH.toFloat()
            val boxAspect = containerW / containerH
            if (videoAspect > boxAspect) {
                // 视频更宽：以高为准铺满，宽度溢出
                viewH = containerH
                viewW = containerH * videoAspect
            } else {
                // 视频更高：以宽为准铺满，高度溢出
                viewW = containerW
                viewH = containerW / videoAspect
            }
        } else {
            viewW = containerW
            viewH = containerH
        }

        // key(path)：换视频时整棵 AndroidView 重建（SurfaceTexture 属于旧
        // 播放器，复用 TextureView 会让新播放器拿不到 Surface 而"卡住"）
        key(path) {

        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {
                            runCatching {
                                player.setSurface(Surface(surface))
                                player.setDataSource(path)
                                player.isLooping = true
                                // 静音：壁纸不需要声音
                                player.setVolume(0f, 0f)
                                player.setOnPreparedListener { mp ->
                                    videoW = mp.videoWidth.coerceAtLeast(1)
                                    videoH = mp.videoHeight.coerceAtLeast(1)
                                    mp.isLooping = true
                                    mp.start()
                                }
                                player.setOnErrorListener { _, _, _ -> true }
                                player.prepareAsync()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            // 暂停即可，player 的释放由 DisposableEffect 负责
                            runCatching { player.pause() }
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.Center)
                .size(
                    width = with(density) { viewW.toDp() },
                    height = with(density) { viewH.toDp() }
                )
        )
        } // end key(path)
    }
}
