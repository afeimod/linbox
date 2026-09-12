package com.linbox.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * LocalWinTheme：让任意子 Composable 都能拿到当前主题。
 */
val LocalWinTheme = staticCompositionLocalOf { Themes.Win11 }

/**
 * 主题包裹器：把 WinTheme 注入 CompositionLocal，同时设置 Material3 配色。
 */
@Composable
fun WinThemeScope(
    theme: WinTheme,
    content: @Composable () -> Unit
) {
    val colorScheme = if (theme.isDark) {
        darkColorScheme(
            primary = theme.accentColor,
            background = theme.windowBackgroundColor,
            surface = theme.windowBackgroundColor,
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            primary = theme.accentColor,
            background = theme.windowBackgroundColor,
            surface = theme.windowBackgroundColor,
            onPrimary = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black
        )
    }

    CompositionLocalProvider(LocalWinTheme provides theme) {
        MaterialTheme(colorScheme = colorScheme) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(theme.windowBackgroundColor)
            ) {
                content()
            }
        }
    }
}
