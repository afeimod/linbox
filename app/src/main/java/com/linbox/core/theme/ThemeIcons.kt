package com.linbox.core.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * 主题图标解析器（v2.15）：每个 Windows 主题使用自己的图标包。
 *
 * 图标资产布局：
 * - assets/icons/win95/<appId>.png  ← Chicago95 图标包（像素风）
 * - assets/icons/winxp/<appId>.png  ← Windows XP 图标包
 * - assets/icons/win10/<appId>.png  ← Win10 现代扁平图标
 * - assets/icons/win11/<appId>.png  ← Windows-Eleven 图标包
 * - assets/icons/<name>.png         ← 通用兜底图标（Win7 等无专属包的主题使用）
 *
 * 图标引用形式：
 * - "app:<appId>"     → 解析为当前主题图标（内置应用使用）
 * - "icons/x.png"     → 直接引用通用图标（如 icons/x11.png）
 * - "emoji:🚀"        → emoji 渲染，不参与主题解析
 *
 * 解析链：主题专属 → 通用兜底 → 原样返回（由 IconPainter 兜底）。
 */
object ThemeIcons {

    /** 主题 → assets/icons 下的子目录（null = 无专属图标包，用通用兜底） */
    private val themeDirs: Map<WindowsVariant, String?> = mapOf(
        WindowsVariant.WIN95 to "win95",
        WindowsVariant.WIN_XP to "winxp",
        WindowsVariant.WIN7 to null,
        WindowsVariant.WIN10 to "win10",
        WindowsVariant.WIN11 to "win11"
    )

    /** 无主题专属图标时的通用兜底（app id → 通用 assets 路径 / emoji） */
    private val genericFallbacks: Map<String, String> = mapOf(
        "terminal" to "emoji:💻",
        "terminal_sim" to "emoji:⌨️",
        "settings" to "icons/settings.png"
    )

    /** 目录清单缓存：context.assets.list() 有 IO，按目录缓存一次 */
    @Volatile
    private var dirCache: MutableMap<String, Set<String>>? = null

    private fun filesIn(context: Context, dir: String): Set<String> {
        val cache = dirCache ?: synchronized(this) {
            dirCache ?: mutableMapOf<String, Set<String>>().also { dirCache = it }
        }
        return cache.getOrPut(dir) {
            runCatching { context.assets.list(dir)?.toSet() ?: emptySet() }.getOrDefault(emptySet())
        }
    }

    /**
     * 解析图标引用为实际 assets 路径（或 emoji 引用）。
     *
     * @param asset   图标引用（"app:x" / "icons/x.png" / "emoji:.." / 其他）
     * @param variant 当前 Windows 主题
     */
    fun resolve(context: Context, asset: String, variant: WindowsVariant): String {
        if (asset.startsWith("emoji:")) return asset

        // 提取图标 ID
        val id: String = when {
            asset.startsWith("app:") -> asset.removePrefix("app:")
            asset.startsWith("icons/") && asset.endsWith(".png") ->
                asset.removePrefix("icons/").removeSuffix(".png")
            else -> return asset
        }

        // 1) 主题专属图标
        val dir = themeDirs[variant]
        if (dir != null && filesIn(context, "icons/$dir").contains("$id.png")) {
            return "icons/$dir/$id.png"
        }

        // 2) 通用兜底（Win7 / 主题目录缺该图标时）
        if (asset.startsWith("app:")) {
            genericFallbacks[id]?.let { return it }
        }

        // 3) 原样返回（老路径直接用）
        return asset
    }
}

/**
 * 统一图标加载：支持 assets 路径 / "emoji:💻"。
 *
 * 图标引用先经 [ThemeIcons.resolve] 解析为当前 Windows 主题的专属图标
 * （icons/win95|winxp|win10|win11/<id>.png），无专属图标时回退通用路径。
 * 原实现位于桌面图标网格（已随桌面环境移除），现服务于窗口标题栏与
 * 主屏启动器。
 */
@Composable
fun IconPainter(asset: String, size: Dp) {
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
                BitmapPainter(BitmapFactory.decodeStream(it).asImageBitmap())
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
        // 兜底：使用 "?" 占位
        Box(
            modifier = Modifier
                .size(size)
                .background(theme.accentColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("?", fontSize = 14.sp, color = theme.accentColor)
        }
    }
}
