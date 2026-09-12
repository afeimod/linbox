package com.linbox.data.model

import com.linbox.core.window.LaunchMode

/**
 * 桌面项类型：内置应用 / 自定义快捷方式
 */
enum class DesktopItemType { BUILTIN_APP, SHORTCUT_URL, SHORTCUT_FILE, SHORTCUT_APP }

/**
 * 桌面图标数据：可来自内置 AppRegistry，也可来自用户自定义快捷方式。
 */
data class DesktopItem(
    val id: String,
    val label: String,
    val iconAsset: String,           // assets 路径或 "emoji:🚀"
    val type: DesktopItemType,
    val target: String,              // appId / URL / 文件路径
    val launchArgs: Map<String, String> = emptyMap(),
    val sortOrder: Int = 0
)

/**
 * 快捷方式实体（与 Room entity 对应，但解耦）
 */
data class Shortcut(
    val id: Long = 0,
    val label: String,
    val iconAsset: String,
    val type: DesktopItemType,
    val target: String,
    val launchArgs: String = "",     // JSON
    val sortOrder: Int = 0
) {
    fun toDesktopItem(): DesktopItem = DesktopItem(
        id = "shortcut_$id",
        label = label,
        iconAsset = iconAsset,
        type = type,
        target = target,
        sortOrder = sortOrder
    )
}

/**
 * 应用信息（用于快捷方式选择"指向哪个应用"）
 */
data class AppInfo(
    val appId: String,
    val displayName: String,
    val iconAsset: String,
    val launchMode: LaunchMode
)
