package com.linbox.core.theme

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 主题管理器：持久化用户选择的主题，提供 Flow 给 UI 订阅。
 *
 * 使用 DataStore 而非 SharedPreferences，以便 Compose 自动 recompose。
 */
private val Context.themeDataStore by preferencesDataStore(name = "linbox_theme_prefs")

class ThemeManager(private val context: Context) {

    private val key = stringPreferencesKey("active_theme_variant")

    val activeTheme: Flow<WinTheme> = context.themeDataStore.data
        .map { prefs: Preferences ->
            val name = prefs[key]
            val variant = WindowsVariant.fromName(name)
            variantToTheme(variant)
        }

    suspend fun setTheme(variant: WindowsVariant) {
        context.themeDataStore.edit { it[key] = variant.name }
    }

    companion object {
        fun variantToTheme(variant: WindowsVariant): WinTheme = when (variant) {
            WindowsVariant.WIN95  -> Themes.Win95
            WindowsVariant.WIN_XP -> Themes.WinXP
            WindowsVariant.WIN7   -> Themes.Win7
            WindowsVariant.WIN10  -> Themes.Win10
            WindowsVariant.WIN11  -> Themes.Win11
        }
    }
}
