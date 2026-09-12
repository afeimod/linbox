package com.linbox.core.theme

import android.content.Context

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
 * - "app:<appId>"     → 解析为当前主题图标（推荐，所有内置应用使用）
 * - "icons/x.png"     → 老路径（快捷方式数据库中持久化的历史值）：
 *                        basename 命中主题目录时同样升级为主题图标
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
        "browser" to "icons/browser.png",
        "file_explorer" to "icons/file_explorer.png",
        "settings" to "icons/settings.png",
        "notepad" to "icons/notepad.png",
        "image_viewer" to "icons/image_viewer.png",
        "media_player" to "icons/music.png",
        "music" to "icons/music_online.png",
        "minesweeper" to "icons/minesweeper_48.png",
        "clock" to "emoji:🕐",
        "terminal" to "emoji:💻",
        "calculator" to "emoji:🧮",
        "sysinfo" to "emoji:📊"
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
