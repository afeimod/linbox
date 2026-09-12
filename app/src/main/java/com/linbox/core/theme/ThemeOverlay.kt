package com.linbox.core.theme

import androidx.compose.ui.graphics.Color
// toArgb 是 Color 的扩展函数（非成员），必须显式 import 才能裸调用
import androidx.compose.ui.graphics.toArgb

/**
 * v2.14 主题覆盖层：个性化设置「颜色 / 字体」对主题的动态覆盖。
 *
 * - [withDarkMode]：把任意 Windows 主题强制转换为深色或浅色变体
 *   （设置→个性化→颜色：「颜色控制所有 Windows 主题深色还是浅色」）
 * - [withAccentColor]：覆盖主题强调色（链接/标题栏徽标等跟随）
 * - [withFontColor]：全局字体颜色（跟随主题 / 白 / 黑）
 *
 * 深浅转换算法：HSV 亮度反转（V' = 1 - V），彩色降饱和，
 * 保持 alpha 不变 —— 对所有 5 代主题通用，无需逐主题手写色板。
 */
object ThemeOverlay {

    /** 单色亮度反转（保持 alpha），用于背景/文字类颜色的深浅互换 */
    fun Color.invertLuminance(): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(toArgb(), hsv)
        val v = (1f - hsv[2]).coerceIn(0f, 1f)
        // 彩色背景反转时降饱和（避免深色模式下出现刺眼的高饱和深色）
        val s = if (hsv[1] > 0.2f) hsv[1] * 0.75f else hsv[1]
        val out = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], s, v)))
        return out.copy(alpha = alpha)
    }

    /**
     * 强制深色 / 浅色变体。
     * @param mode "auto"=跟随主题原样 / "dark"=深色 / "light"=浅色
     */
    fun apply(theme: WinTheme, mode: String): WinTheme {
        val wantDark = when (mode) {
            "dark" -> true
            "light" -> false
            else -> return theme
        }
        if (theme.isDark == wantDark) return theme
        return theme.copy(
            isDark = wantDark,
            // 桌面图标文字：深浅模式下始终用高对比色（浅底黑字 / 深底白字）
            desktopIconTextColor = if (wantDark) Color.White else Color.Black,
            taskbarColor = theme.taskbarColor.invertLuminance(),
            taskbarStartButtonColor = theme.taskbarStartButtonColor,
            taskbarIconColor = if (wantDark) Color.White else Color.Black,
            taskbarClockColor = if (wantDark) Color.White else Color.Black,
            startMenuColor = theme.startMenuColor.invertLuminance(),
            windowTitleBarColor = theme.windowTitleBarColor.invertLuminance(),
            windowTitleBarTextColor = if (wantDark) Color.White else Color.Black,
            windowBackgroundColor = theme.windowBackgroundColor.invertLuminance(),
            windowBorderColor = theme.windowBorderColor.invertLuminance(),
            buttonBackgroundColor = theme.buttonBackgroundColor.invertLuminance(),
            buttonTextColor = if (wantDark) Color.White else Color.Black,
            secondaryTextColor = (if (wantDark) Color.White else Color.Black).copy(alpha = 0.65f),
            dividerColor = theme.dividerColor.invertLuminance(),
            cardBackgroundColor = theme.cardBackgroundColor.invertLuminance()
        )
    }

    /** 覆盖强调色（"default" = 主题自带；否则 "#RRGGBB"） */
    fun applyAccent(theme: WinTheme, accent: String): WinTheme {
        if (accent == "default") return theme
        val color = runCatching {
            Color(android.graphics.Color.parseColor(accent))
        }.getOrNull() ?: return theme
        return theme.copy(
            accentColor = color,
            linkColor = color,
            windowTitleBarIconColor = color
        )
    }

    /** 全局字体颜色（"auto" 跟随主题 / "white" / "black"） */
    fun applyFontColor(theme: WinTheme, mode: String): WinTheme {
        val c = when (mode) {
            "white" -> Color.White
            "black" -> Color.Black
            else -> return theme
        }
        return theme.copy(
            desktopIconTextColor = c,
            taskbarIconColor = c,
            taskbarClockColor = c,
            windowTitleBarTextColor = c,
            buttonTextColor = c,
            secondaryTextColor = c.copy(alpha = 0.7f)
        )
    }
}
