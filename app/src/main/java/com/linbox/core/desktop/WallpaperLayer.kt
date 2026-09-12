package com.linbox.core.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 壁纸层：优先使用用户自定义壁纸（file:// 或 content:// URI），否则使用主题默认壁纸（assets）。
 *
 * v2.17 修复"选择图片自定义桌面壁纸不生效"：
 * - 旧版用 remember{} 在组合期间同步解码：大图阻塞主线程，且一旦组合重建时机
 *   与 DataStore 写入竞态，容易出现不刷新/黑屏。
 * - 新版改用 produceState + Dispatchers.IO 异步解码，key 变化自动重新解码；
 * - 大图按最长边 2400px 采样（inSampleSize），4K 照片不再 OOM；
 * - 解码失败（文件被移动/删除、URI 权限回收）自动回退主题默认壁纸，
 *   不再退到纯色背景。
 *
 * v2.18 新增视频壁纸：customWallpaperUri 为 video:// 前缀时渲染视频
 * （TextureView + MediaPlayer 静音循环铺满，复用锁屏视频壁纸实现），
 * Windows DreamScene 风格动态桌面。
 */
@Composable
fun WallpaperLayer(
    themeWallpaper: String,
    customWallpaperUri: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // ===== v2.18：视频壁纸分支（video:// 本地文件） =====
    if (customWallpaperUri?.startsWith("video://") == true) {
        val videoPath = customWallpaperUri.removePrefix("video://")
        Box(modifier = modifier.background(Color(0xFF1A1A2E))) {
            LockVideoWallpaper(
                path = videoPath,
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    val painter by produceState<BitmapPainter?>(
        initialValue = null,
        themeWallpaper, customWallpaperUri
    ) {
        value = withContext(Dispatchers.IO) {
            decodeWallpaper(context, themeWallpaper, customWallpaperUri)
        }
    }

    Box(
        modifier = modifier.background(
            Color(0xFF1A1A2E)  // 兜底深蓝灰色
        )
    ) {
        painter?.let { p ->
            Image(
                painter = p,
                contentDescription = "Wallpaper",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 壁纸解码主链路：自定义壁纸 → 失败回退主题 assets 壁纸 */
private fun decodeWallpaper(
    context: Context,
    themeWallpaper: String,
    customWallpaperUri: String?
): BitmapPainter? {
    if (!customWallpaperUri.isNullOrEmpty()) {
        decodeFromUri(context, customWallpaperUri)?.let { return it }
    }
    return runCatching {
        decodeSampled({ context.assets.open(themeWallpaper) })?.asImageBitmap()?.let { BitmapPainter(it) }
    }.getOrNull()
}

/** 按 URI 解码：file:// 直读文件（v2.14 起壁纸存真实路径），兼容 content:// 与裸路径 */
private fun decodeFromUri(context: Context, uriStr: String): BitmapPainter? {
    val opener: (() -> InputStream?)? = when {
        uriStr.startsWith("file://") -> fileOpener(Uri.parse(uriStr).path)
        // 兼容历史上可能写入的裸绝对路径
        uriStr.startsWith("/") -> fileOpener(uriStr)
        // 系统 SAF 选择的历史 content URI（兼容旧数据）
        else -> contentOpener(context, uriStr)
    }
    if (opener == null) return null
    val bitmap = runCatching { decodeSampled(opener) }.getOrNull() ?: return null
    return BitmapPainter(bitmap.asImageBitmap())
}

/** 本地文件打开器：路径为空或文件不存在时返回 null */
private fun fileOpener(path: String?): (() -> InputStream?)? {
    val file = path?.let { File(it) }?.takeIf { it.exists() } ?: return null
    return { file.inputStream() }
}

/** content:// 打开器：打开失败返回 null 而不是抛异常 */
private fun contentOpener(context: Context, uriStr: String): () -> InputStream? {
    val uri = Uri.parse(uriStr)
    return { runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() }
}

/**
 * 大图采样解码：先读 bounds 计算最长边，超过 2400px 时按 2 的幂采样。
 * 注意：decode bounds 会消费流，opener 必须可重复打开（文件/assets/content 都满足）。
 */
private fun decodeSampled(opener: () -> InputStream?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { opener()?.use { BitmapFactory.decodeStream(it, null, bounds) } }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / (sample * 2) >= 2400) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { opener()?.use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()
}
