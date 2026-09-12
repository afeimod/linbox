package com.linbox.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Windows 主题枚举：覆盖 95 / XP / 7 / 10 / 11 五代
 *
 * 视觉重构：所有主题统一采用 Win11 风格的现代化外观
 * （细边框 + 大圆角 + 阴影 + 居中任务栏 + Mica 半透明材质），
 * 但保留各自时代的色调特征与历史质感。
 */
enum class WindowsVariant {
    WIN95, WIN_XP, WIN7, WIN10, WIN11;

    val displayName: String
        get() = when (this) {
            WIN95  -> "Windows 95"
            WIN_XP -> "Windows XP"
            WIN7   -> "Windows 7"
            WIN10  -> "Windows 10"
            WIN11  -> "Windows 11"
        }

    companion object {
        fun fromName(name: String?): WindowsVariant =
            entries.firstOrNull { it.name == name } ?: WIN11
    }
}

/**
 * 任务栏布局风格
 */
enum class TaskbarAlignment { LEFT, CENTER }

/**
 * 任务栏视觉风格（v2.15）：按时代完整重绘，五大主题各自还原。
 *
 * - CLASSIC_95：Win95 灰色立体凹凸任务栏（凸起开始按钮 + 凹陷托盘 + 文字任务按钮）
 * - LUNA_XP：WinXP Luna 蓝色渐变任务栏（绿色圆角开始按钮 + 蓝渐变任务按钮）
 * - AERO_7：Win7 Aero 玻璃任务栏（开始圆球 + 图标式任务栏 + 辉光激活态）
 * - MODERN_10：Win10 浅色扁平任务栏（靠左图标 + 搜索框 + 底部下划线激活态）
 * - MICA_11：Win11 Mica 悬浮居中 Dock（本应用标志性现代样式）
 */
enum class TaskbarStyle { CLASSIC_95, LUNA_XP, AERO_7, MODERN_10, MICA_11 }

/**
 * 窗口边框风格 - 现代化重构后统一为细边框 + 大圆角
 */
enum class WindowChrome {
    /** Win95 经典灰色调 + 现代圆角 */
    CLASSIC,
    /** XP Luna 蓝色调 + 现代圆角 */
    LUNA,
    /** Win7 Aero 半透明蓝色调 + 现代圆角 */
    AERO,
    /** Win10 深色扁平 + 现代圆角 */
    FLAT,
    /** Win11 Mica 材质 + 大圆角 + 居中任务栏 */
    MICA
}

/**
 * 单个 Windows 主题的完整视觉描述。
 * 所有 UI 组件都从这里读取颜色/形状/字体。
 *
 * 视觉重构说明：所有主题统一采用 Win11 风格基础外观，
 * 通过颜色/字体/材质细节区分各时代特征。
 */
data class WinTheme(
    val variant: WindowsVariant,
    val displayName: String,

    // === 桌面 ===
    val wallpaperAsset: String,          // assets 中壁纸路径
    val desktopIconTextColor: Color,
    val desktopIconTextShadow: Boolean,

    // === 任务栏 ===
    val taskbarAlignment: TaskbarAlignment,
    val taskbarColor: Color,
    val taskbarAlpha: Float,             // 0f..1f，半透明效果
    val taskbarHeight: Dp,
    val taskbarStartButtonColor: Color,
    val taskbarIconColor: Color,
    val taskbarClockColor: Color,
    val startButtonLabel: String?,       // null=不显示文字（Win10/11 风格）
    /** v2.15 任务栏视觉风格：决定任务栏按哪个时代绘制 */
    val taskbarStyle: TaskbarStyle,

    // === 开始菜单 ===
    val startMenuWidth: Dp,
    val startMenuColor: Color,
    val startMenuAlpha: Float,
    val startMenuShape: Shape,

    // === 窗口 ===
    val windowChrome: WindowChrome,
    val windowTitleBarColor: Color,
    val windowTitleBarTextColor: Color,
    val windowBackgroundColor: Color,
    val windowBorderColor: Color,
    val windowBorderWidth: Dp,
    val windowCornerSize: Dp,
    val windowTitleBarHeight: Dp,
    val windowControlButtonsOnLeft: Boolean,   // Win95/XP/7 在右，无所谓；这里保留兼容字段
    /** 窗口阴影半径 - 现代化所有窗口都有阴影 */
    val windowShadowElevation: Dp,
    /** 标题栏图标颜色（Win11 风格的彩色徽标） */
    val windowTitleBarIconColor: Color,

    // === 控件 / 按钮 ===
    val buttonBackgroundColor: Color,
    val buttonTextColor: Color,
    val buttonCornerSize: Dp,
    val accentColor: Color,
    /** 链接/可点击文字颜色 */
    val linkColor: Color,
    /** 二级文本/描述文字颜色 */
    val secondaryTextColor: Color,
    /** 分隔线颜色 */
    val dividerColor: Color,
    /** 卡片背景色 */
    val cardBackgroundColor: Color,

    // === 字体 ===
    val fontFamily: FontFamily,
    val fontSizeSmall: androidx.compose.ui.unit.TextUnit,
    val fontSizeBody: androidx.compose.ui.unit.TextUnit,
    val fontSizeTitle: androidx.compose.ui.unit.TextUnit,
    val fontWeightTitle: FontWeight,

    // === 启动音效（assets 路径，可为空） ===
    val startupSoundAsset: String?,

    // === 整体氛围 ===
    val isDark: Boolean
) {
    val windowShape: Shape
        get() = when (windowChrome) {
            WindowChrome.CLASSIC -> RoundedCornerShape(windowCornerSize)
            WindowChrome.LUNA   -> RoundedCornerShape(windowCornerSize)
            WindowChrome.AERO   -> RoundedCornerShape(windowCornerSize)
            WindowChrome.FLAT   -> RoundedCornerShape(windowCornerSize)
            WindowChrome.MICA   -> RoundedCornerShape(windowCornerSize)
        }

    val buttonShape: Shape
        get() = RoundedCornerShape(buttonCornerSize)

    /** 控件圆角形状（用于卡片/列表项） */
    val cardShape: Shape
        get() = RoundedCornerShape(8.dp)

    /** 标题栏控制按钮形状（Win11 风格的圆角矩形） */
    val controlButtonShape: Shape
        get() = RoundedCornerShape(0.dp)  // Win11 控件按钮其实是直角的

    companion object {
        val Default = Themes.Win11
    }
}

/**
 * 主题工厂：5 个 Windows 主题的具体定义。
 *
 * 视觉重构：所有主题采用 Win11 风格基础外观（细边框 + 大圆角 + 阴影 + 居中任务栏），
 * 通过色调和材质细节区分时代特征：
 * - Win95：经典灰色调、Tahoma 字体（保留朴素感）
 * - WinXP：Luna 蓝色色调（去掉了过时的渐变标题栏）
 * - Win7：Aero 玻璃半透明蓝色调
 * - Win10：扁平浅色调（v2.16.4：由深色改亮色，与其他时代一致）
 * - Win11：Mica 浅色调（最贴近视频参考样式）
 */
object Themes {

    val Win95 = WinTheme(
        variant = WindowsVariant.WIN95,
        displayName = "Windows 95",
        wallpaperAsset = "wallpapers/win95.png",
        desktopIconTextColor = Color.White,
        desktopIconTextShadow = true,
        taskbarAlignment = TaskbarAlignment.LEFT,
        taskbarColor = Color(0xFFC0C0C0),
        taskbarAlpha = 1f,
        taskbarHeight = 44.dp,
        taskbarStartButtonColor = Color(0xFFC0C0C0),
        taskbarIconColor = Color.Black,
        taskbarClockColor = Color.Black,
        startButtonLabel = "开始",
        taskbarStyle = TaskbarStyle.CLASSIC_95,
        startMenuWidth = 360.dp,
        startMenuColor = Color(0xFFC0C0C0),
        startMenuAlpha = 0.97f,
        startMenuShape = RoundedCornerShape(8.dp),
        windowChrome = WindowChrome.CLASSIC,
        windowTitleBarColor = Color(0xFFE8E8E8),    // 浅灰标题栏，配合深色文字（Win11 风格）
        windowTitleBarTextColor = Color(0xFF1F1F1F),
        windowBackgroundColor = Color(0xFFF5F5F5),
        windowBorderColor = Color(0xFFB0B0B0),
        windowBorderWidth = 1.dp,
        windowCornerSize = 8.dp,
        windowTitleBarHeight = 36.dp,
        windowControlButtonsOnLeft = false,
        windowShadowElevation = 12.dp,
        windowTitleBarIconColor = Color(0xFF000080),
        buttonBackgroundColor = Color(0xFFE0E0E0),
        buttonTextColor = Color.Black,
        buttonCornerSize = 4.dp,
        accentColor = Color(0xFF000080),
        linkColor = Color(0xFF0066CC),
        secondaryTextColor = Color(0xFF616161),
        dividerColor = Color(0xFFE0E0E0),
        cardBackgroundColor = Color(0xFFEBEBEB),
        fontFamily = FontFamily.SansSerif,
        fontSizeSmall = 11.sp,
        fontSizeBody = 13.sp,
        fontSizeTitle = 16.sp,
        fontWeightTitle = FontWeight.SemiBold,
        startupSoundAsset = "sounds/win95.mp3",
        isDark = false
    )

    val WinXP = WinTheme(
        variant = WindowsVariant.WIN_XP,
        displayName = "Windows XP",
        wallpaperAsset = "wallpapers/winxp_bliss.jpg",
        desktopIconTextColor = Color.White,
        desktopIconTextShadow = true,
        taskbarAlignment = TaskbarAlignment.LEFT,
        taskbarColor = Color(0xFF245EDB),
        taskbarAlpha = 1f,
        taskbarHeight = 44.dp,
        taskbarStartButtonColor = Color(0xFF3C9A20),
        taskbarIconColor = Color.White,
        taskbarClockColor = Color.White,
        startButtonLabel = "开始",
        taskbarStyle = TaskbarStyle.LUNA_XP,
        startMenuWidth = 380.dp,
        startMenuColor = Color(0xFFF6F8FB),
        startMenuAlpha = 0.98f,
        startMenuShape = RoundedCornerShape(8.dp),
        windowChrome = WindowChrome.LUNA,
        windowTitleBarColor = Color(0xFFE8F0FE),   // 浅蓝色标题栏（Win11 风格）
        windowTitleBarTextColor = Color(0xFF0A3F8F),
        windowBackgroundColor = Color(0xFFFAFCFE),
        windowBorderColor = Color(0xFFB8CCE8),
        windowBorderWidth = 1.dp,
        windowCornerSize = 8.dp,
        windowTitleBarHeight = 36.dp,
        windowControlButtonsOnLeft = false,
        windowShadowElevation = 14.dp,
        windowTitleBarIconColor = Color(0xFF0A6E3F),
        buttonBackgroundColor = Color(0xFFE1EBF8),
        buttonTextColor = Color(0xFF0A3F8F),
        buttonCornerSize = 6.dp,
        accentColor = Color(0xFF0A6E3F),   // XP 经典绿色作为强调色
        linkColor = Color(0xFF0066CC),
        secondaryTextColor = Color(0xFF6E7B8B),
        dividerColor = Color(0xFFD5DDE8),
        cardBackgroundColor = Color(0xFFEDF2F8),
        fontFamily = FontFamily.SansSerif,
        fontSizeSmall = 12.sp,
        fontSizeBody = 13.sp,
        fontSizeTitle = 16.sp,
        fontWeightTitle = FontWeight.SemiBold,
        startupSoundAsset = "sounds/winxp.mp3",
        isDark = false
    )

    val Win7 = WinTheme(
        variant = WindowsVariant.WIN7,
        displayName = "Windows 7",
        wallpaperAsset = "wallpapers/win7.jpg",
        desktopIconTextColor = Color.White,
        desktopIconTextShadow = true,
        taskbarAlignment = TaskbarAlignment.LEFT,
        taskbarColor = Color(0xFF2A3B4D),
        taskbarAlpha = 0.88f,
        taskbarHeight = 46.dp,
        taskbarStartButtonColor = Color(0xFF1BA1E2),
        taskbarIconColor = Color.White,
        taskbarClockColor = Color.White,
        startButtonLabel = null,
        taskbarStyle = TaskbarStyle.AERO_7,
        startMenuWidth = 400.dp,
        startMenuColor = Color(0xFFF0F5FA),
        startMenuAlpha = 0.97f,
        startMenuShape = RoundedCornerShape(10.dp),
        windowChrome = WindowChrome.AERO,
        windowTitleBarColor = Color(0xFFE5EFF8),   // Aero 浅蓝玻璃
        windowTitleBarTextColor = Color(0xFF1F3F5F),
        windowBackgroundColor = Color(0xFFFAFCFE),
        windowBorderColor = Color(0xFFA4C0DE),
        windowBorderWidth = 1.dp,
        windowCornerSize = 8.dp,
        windowTitleBarHeight = 36.dp,
        windowControlButtonsOnLeft = false,
        windowShadowElevation = 16.dp,
        windowTitleBarIconColor = Color(0xFF1BA1E2),
        buttonBackgroundColor = Color(0xFFDDE9F3),
        buttonTextColor = Color(0xFF1F3F5F),
        buttonCornerSize = 6.dp,
        accentColor = Color(0xFF1BA1E2),
        linkColor = Color(0xFF0066CC),
        secondaryTextColor = Color(0xFF5F7A95),
        dividerColor = Color(0xFFCCD8E5),
        cardBackgroundColor = Color(0xFFE8EFF6),
        fontFamily = FontFamily.SansSerif,
        fontSizeSmall = 12.sp,
        fontSizeBody = 13.sp,
        fontSizeTitle = 16.sp,
        fontWeightTitle = FontWeight.SemiBold,
        startupSoundAsset = "sounds/win7.mp3",
        isDark = false
    )

    val Win10 = WinTheme(
        variant = WindowsVariant.WIN10,
        displayName = "Windows 10",
        wallpaperAsset = "wallpapers/win10.jpg",
        desktopIconTextColor = Color.White,
        desktopIconTextShadow = true,
        taskbarAlignment = TaskbarAlignment.LEFT,
        // v2.16.4：Win10 浅色主题（Windows 10 Light 同源色板）：
        // 任务栏 #EEEEEE、开始菜单 #F2F2F2、窗口白底 —— 强调色保留
        // Win10 标志性蓝 #0078D7，设计语言（扁平、靠左任务栏、
        // 搜索框、下划线激活态）不变，仅明度与其他时代看齐
        taskbarColor = Color(0xFFEEEEEE),
        taskbarAlpha = 0.96f,
        taskbarHeight = 46.dp,
        taskbarStartButtonColor = Color(0xFF0078D7),
        taskbarIconColor = Color(0xFF1F1F1F),
        taskbarClockColor = Color(0xFF1F1F1F),
        startButtonLabel = null,
        taskbarStyle = TaskbarStyle.MODERN_10,
        startMenuWidth = 400.dp,
        startMenuColor = Color(0xFFF2F2F2),
        startMenuAlpha = 0.98f,
        startMenuShape = RoundedCornerShape(8.dp),
        windowChrome = WindowChrome.FLAT,
        windowTitleBarColor = Color(0xFFF5F5F5),
        windowTitleBarTextColor = Color(0xFF1F1F1F),
        windowBackgroundColor = Color(0xFFFAFAFA),
        windowBorderColor = Color(0xFFDDDDDD),
        windowBorderWidth = 1.dp,
        windowCornerSize = 8.dp,
        windowTitleBarHeight = 36.dp,
        windowControlButtonsOnLeft = false,
        windowShadowElevation = 14.dp,
        windowTitleBarIconColor = Color(0xFF0078D7),
        buttonBackgroundColor = Color(0xFFE9E9E9),
        buttonTextColor = Color(0xFF1F1F1F),
        buttonCornerSize = 4.dp,
        accentColor = Color(0xFF0078D7),
        linkColor = Color(0xFF0067C0),
        secondaryTextColor = Color(0xFF616161),
        dividerColor = Color(0xFFE0E0E0),
        cardBackgroundColor = Color(0xFFF0F0F0),
        fontFamily = FontFamily.SansSerif,
        fontSizeSmall = 12.sp,
        fontSizeBody = 13.sp,
        fontSizeTitle = 16.sp,
        fontWeightTitle = FontWeight.SemiBold,
        startupSoundAsset = "sounds/win10.mp3",
        isDark = false
    )

    val Win11 = WinTheme(
        variant = WindowsVariant.WIN11,
        displayName = "Windows 11",
        wallpaperAsset = "wallpapers/win11_bloom.jpg",
        desktopIconTextColor = Color.White,
        desktopIconTextShadow = true,
        taskbarAlignment = TaskbarAlignment.CENTER,
        taskbarColor = Color(0xFFEFEFEF),  // Mica 雾白
        taskbarAlpha = 0.78f,
        taskbarHeight = 48.dp,
        taskbarStartButtonColor = Color(0xFF0067C0),
        taskbarIconColor = Color(0xFF1F1F1F),
        taskbarClockColor = Color(0xFF1F1F1F),
        startButtonLabel = null,
        taskbarStyle = TaskbarStyle.MICA_11,
        startMenuWidth = 440.dp,
        startMenuColor = Color(0xFFF8F8F8),
        startMenuAlpha = 0.96f,
        startMenuShape = RoundedCornerShape(12.dp),
        windowChrome = WindowChrome.MICA,
        windowTitleBarColor = Color(0xFFF5F5F5),   // Mica 浅色
        windowTitleBarTextColor = Color(0xFF1F1F1F),
        windowBackgroundColor = Color(0xFFFAFAFA),
        windowBorderColor = Color(0xFFE5E5E5),
        windowBorderWidth = 1.dp,
        windowCornerSize = 8.dp,
        windowTitleBarHeight = 36.dp,
        windowControlButtonsOnLeft = false,
        windowShadowElevation = 16.dp,
        windowTitleBarIconColor = Color(0xFF0067C0),
        buttonBackgroundColor = Color(0xFFE9E9E9),
        buttonTextColor = Color(0xFF1F1F1F),
        buttonCornerSize = 4.dp,
        accentColor = Color(0xFF0067C0),
        linkColor = Color(0xFF0067C0),
        secondaryTextColor = Color(0xFF616161),
        dividerColor = Color(0xFFE5E5E5),
        cardBackgroundColor = Color(0xFFF0F0F0),
        fontFamily = FontFamily.SansSerif,
        fontSizeSmall = 12.sp,
        fontSizeBody = 13.sp,
        fontSizeTitle = 16.sp,
        fontWeightTitle = FontWeight.SemiBold,
        startupSoundAsset = "sounds/win11.mp3",
        isDark = false
    )

    val all: List<WinTheme> = listOf(Win95, WinXP, Win7, Win10, Win11)
}
