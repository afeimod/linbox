package com.linbox.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Windows 主题枚举：覆盖 95 / XP / 7 / 10 / 11 五代
 *
 * 视觉统一：所有主题采用 Win11 风格的现代化窗口外观
 * （细边框 + 大圆角 + 阴影），但保留各自时代的色调特征。
 * 主题只影响窗口/控件配色 —— 桌面壁纸、任务栏、开始菜单已随
 * 桌面环境一并移除。
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
    /** Win11 Mica 材质 + 大圆角 */
    MICA
}

/**
 * 单个 Windows 主题的完整视觉描述。
 * 所有窗口/控件都从这里读取颜色/形状/字体。
 */
data class WinTheme(
    val variant: WindowsVariant,
    val displayName: String,

    // === 窗口 ===
    val windowChrome: WindowChrome,
    val windowTitleBarColor: Color,
    val windowTitleBarTextColor: Color,
    val windowBackgroundColor: Color,
    val windowBorderColor: Color,
    val windowBorderWidth: Dp,
    val windowCornerSize: Dp,
    val windowTitleBarHeight: Dp,
    val windowControlButtonsOnLeft: Boolean,
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

    // === 整体氛围 ===
    val isDark: Boolean
) {
    val windowShape: Shape
        get() = RoundedCornerShape(windowCornerSize)

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
 * 所有主题采用 Win11 风格基础外观（细边框 + 大圆角 + 阴影），
 * 通过色调和材质细节区分时代特征：
 * - Win95：经典灰色调、Tahoma 字体（保留朴素感）
 * - WinXP：Luna 蓝色色调
 * - Win7：Aero 玻璃半透明蓝色调
 * - Win10：扁平浅色调
 * - Win11：Mica 浅色调
 */
object Themes {

    val Win95 = WinTheme(
        variant = WindowsVariant.WIN95,
        displayName = "Windows 95",
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
        isDark = false
    )

    val WinXP = WinTheme(
        variant = WindowsVariant.WIN_XP,
        displayName = "Windows XP",
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
        isDark = false
    )

    val Win7 = WinTheme(
        variant = WindowsVariant.WIN7,
        displayName = "Windows 7",
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
        isDark = false
    )

    val Win10 = WinTheme(
        variant = WindowsVariant.WIN10,
        displayName = "Windows 10",
        // v2.16.4：Win10 浅色主题（Windows 10 Light 同源色板）：
        // 窗口白底 —— 强调色保留 Win10 标志性蓝 #0078D7
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
        isDark = false
    )

    val Win11 = WinTheme(
        variant = WindowsVariant.WIN11,
        displayName = "Windows 11",
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
        isDark = false
    )

    val all: List<WinTheme> = listOf(Win95, WinXP, Win7, Win10, Win11)
}
