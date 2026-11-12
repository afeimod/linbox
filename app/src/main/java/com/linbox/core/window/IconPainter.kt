package com.linbox.core.window

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.theme.ThemeIcons

/**
 * 统一图标加载：支持 assets 路径 / "emoji:🚀" / 内置 drawable。
 *
 * v2.15 主题图标：图标引用先经 [ThemeIcons.resolve] 解析为当前
 * Windows 主题的专属图标（icons/win95|winxp|win10|win11/<id>.png），
 * 无专属图标时回退通用路径 —— 切换主题时窗口标题栏图标即时换肤。
 */
@Composable
fun IconPainter(asset: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val theme = LocalWinTheme.current
    // 主题图标解析（assets.list 有缓存，重组开销可忽略）
    val resolvedAsset = remember(asset, theme.variant) {
        ThemeIcons.resolve(context, asset, theme.variant)
    }

    if (resolvedAsset.startsWith("emoji:")) {
        val emoji = resolvedAsset.removePrefix("emoji:")
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = (size.value * 0.7f).sp)
        }
        return
    }

    val painter = remember(resolvedAsset) {
        runCatching {
            context.assets.open(resolvedAsset).use {
                androidx.compose.ui.graphics.painter.BitmapPainter(
                    BitmapFactory.decodeStream(it).asImageBitmap()
                )
            }
        }.getOrNull()
    }

    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    } else {
        // 兜底：使用 emoji 占位
        Box(
            modifier = Modifier
                .size(size)
                .background(theme.accentColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("?", fontSize = 14.sp, color = theme.accentColor, fontWeight = FontWeight.Bold)
        }
    }
}
