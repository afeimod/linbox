package com.linbox.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appPrefs by preferencesDataStore(name = "linbox_prefs")

/**
 * 应用偏好设置（精简版）：只保留终端 + X11 应用所需的配置项。
 *
 * 分类：
 * - 显示：UI 缩放 / 方向 / 刘海屏
 * - 个性化：颜色模式 / 强调色 / 字体缩放·颜色·样式 / 语言
 * - 输入：虚拟鼠标指针、触控板、虚拟键盘、虚拟游戏手柄
 *
 * 原桌面环境相关键（壁纸/任务栏/开始菜单/锁屏/浏览器等）已随桌面
 * 一并移除；旧键留在 DataStore 文件中不再读取，无副作用。
 */
class SettingsStore(private val context: Context) {

    companion object {
        /**
         * UI 缩放基准系数：100% 档位的实际渲染效果 = 存储值 × 本系数。
         * 用户反馈旧版 100% 元素过大，现把 100% 档位整体缩小到旧版 60% 的
         * 视觉效果；设置里仍显示 100%，其余百分比按同一系数等比映射。
         */
        const val UI_SCALE_BASE = 0.6f
    }

    object Keys {
        // UI 缩放（整体缩放所有 dp/sp，>1 放大，<1 缩小）
        val UI_SCALE = floatPreferencesKey("ui_scale")
        // 显示方向：auto / portrait / landscape
        val DISPLAY_ORIENTATION = stringPreferencesKey("display_orientation")
        // 刘海屏占用
        val USE_CUTOUT = booleanPreferencesKey("use_cutout")

        // 个性化：颜色 / 字体 / 语言
        val APP_COLOR_MODE = stringPreferencesKey("app_color_mode")
        val APP_ACCENT = stringPreferencesKey("app_accent")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val FONT_COLOR = stringPreferencesKey("font_color")
        val FONT_STYLE = stringPreferencesKey("font_style")
        val LANGUAGE = stringPreferencesKey("language")

        // 终端背景：纯色（default / #RRGGBB）+ 自定义图片开关 + 透明度
        // （图片本体存 filesDir/terminal_bg.jpg，选图时拷贝持久化）
        val TERMINAL_BG_COLOR = stringPreferencesKey("terminal_bg_color")
        val TERMINAL_BG_IMAGE = booleanPreferencesKey("terminal_bg_image")
        // 背景透明度 0..1（0=不透明，1=完全透明露黑底，文字不受影响）
        val TERMINAL_BG_TRANSPARENCY = floatPreferencesKey("terminal_bg_transparency")

        // 悬浮球位置（归一化 0..1，相对终端区域）
        val FAB_POS_X = floatPreferencesKey("fab_pos_x")
        val FAB_POS_Y = floatPreferencesKey("fab_pos_y")

        // 输入：鼠标指针 / 触控板
        val MOUSE_POINTER_SPEED = floatPreferencesKey("mouse_pointer_speed")
        val MOUSE_CURSOR_ENABLED = booleanPreferencesKey("mouse_cursor_enabled")
        val MOUSE_CURSOR_THEME = stringPreferencesKey("mouse_cursor_theme")
        val MOUSE_CURSOR_SIZE = floatPreferencesKey("mouse_cursor_size")
        val MOUSE_RIGHT_CLICK = stringPreferencesKey("mouse_right_click")
        val MOUSE_CONTROL_MODE = stringPreferencesKey("mouse_control_mode")

        // 输入：虚拟键盘
        val KEYBOARD_VIBRATION = booleanPreferencesKey("keyboard_vibration")
        val TOUCH_FEEDBACK = booleanPreferencesKey("touch_feedback")
        val KEYBOARD_MASTER = booleanPreferencesKey("keyboard_master")
        val KEYBOARD_FUNC_ROW = booleanPreferencesKey("keyboard_func_row")
        val KEYBOARD_NUMPAD = booleanPreferencesKey("keyboard_numpad")
        val KEYBOARD_SCALE = floatPreferencesKey("keyboard_scale")
        val KEYBOARD_THEME = stringPreferencesKey("keyboard_theme")
        val KEYBOARD_POS_X = floatPreferencesKey("keyboard_pos_x")
        val KEYBOARD_POS_Y = floatPreferencesKey("keyboard_pos_y")
        val KEYBOARD_DRAG_ENABLED = booleanPreferencesKey("keyboard_drag_enabled")

        // 输入：虚拟游戏手柄
        val GAMEPAD_ENABLED = booleanPreferencesKey("gamepad_enabled")
        val GAMEPAD_CONFIG = stringPreferencesKey("gamepad_config")
    }

    // ===== 显示 =====
    val uiScale: Flow<Float> = context.appPrefs.data.map { it[Keys.UI_SCALE] ?: 1.0f }
    val displayOrientation: Flow<String> = context.appPrefs.data
        .map { it[Keys.DISPLAY_ORIENTATION] ?: "auto" }
    val useCutout: Flow<Boolean> = context.appPrefs.data.map { it[Keys.USE_CUTOUT] ?: true }

    // ===== 个性化 =====
    val appColorMode: Flow<String> = context.appPrefs.data.map { it[Keys.APP_COLOR_MODE] ?: "auto" }
    val appAccent: Flow<String> = context.appPrefs.data.map { it[Keys.APP_ACCENT] ?: "default" }
    val fontScale: Flow<Float> = context.appPrefs.data.map { it[Keys.FONT_SCALE] ?: 1.0f }
    val fontColor: Flow<String> = context.appPrefs.data.map { it[Keys.FONT_COLOR] ?: "auto" }
    val fontStyle: Flow<String> = context.appPrefs.data.map { it[Keys.FONT_STYLE] ?: "default" }
    val language: Flow<String> = context.appPrefs.data.map { it[Keys.LANGUAGE] ?: "zh-CN" }

    // ===== 终端背景 =====
    val terminalBgColor: Flow<String> = context.appPrefs.data.map { it[Keys.TERMINAL_BG_COLOR] ?: "default" }
    val terminalBgImage: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TERMINAL_BG_IMAGE] ?: false }
    val terminalBgTransparency: Flow<Float> = context.appPrefs.data.map { it[Keys.TERMINAL_BG_TRANSPARENCY] ?: 0f }

    // ===== 悬浮球位置 =====
    val fabPosX: Flow<Float> = context.appPrefs.data.map { it[Keys.FAB_POS_X] ?: 0.85f }
    val fabPosY: Flow<Float> = context.appPrefs.data.map { it[Keys.FAB_POS_Y] ?: 0.85f }

    // ===== 输入：鼠标 / 触控板 =====
    val mousePointerSpeed: Flow<Float> = context.appPrefs.data.map { it[Keys.MOUSE_POINTER_SPEED] ?: 1.0f }
    val mouseCursorEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.MOUSE_CURSOR_ENABLED] ?: true }
    val mouseCursorTheme: Flow<String> = context.appPrefs.data.map { it[Keys.MOUSE_CURSOR_THEME] ?: "white" }
    val mouseCursorSize: Flow<Float> = context.appPrefs.data.map { it[Keys.MOUSE_CURSOR_SIZE] ?: 26f }
    val mouseRightClick: Flow<String> = context.appPrefs.data.map { it[Keys.MOUSE_RIGHT_CLICK] ?: "twofinger" }
    val mouseControlMode: Flow<String> = context.appPrefs.data.map { it[Keys.MOUSE_CONTROL_MODE] ?: "touch" }

    // ===== 输入：虚拟键盘 =====
    val keyboardVibration: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_VIBRATION] ?: true }
    val touchFeedback: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TOUCH_FEEDBACK] ?: false }
    val keyboardMaster: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_MASTER] ?: false }
    val keyboardFuncRow: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_FUNC_ROW] ?: true }
    val keyboardNumpad: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_NUMPAD] ?: true }
    val keyboardScale: Flow<Float> = context.appPrefs.data.map { it[Keys.KEYBOARD_SCALE] ?: 1.0f }
    val keyboardTheme: Flow<String> = context.appPrefs.data.map { it[Keys.KEYBOARD_THEME] ?: "dark" }
    val keyboardPosX: Flow<Float> = context.appPrefs.data.map { it[Keys.KEYBOARD_POS_X] ?: 0.5f }
    val keyboardPosY: Flow<Float> = context.appPrefs.data.map { it[Keys.KEYBOARD_POS_Y] ?: 1.0f }
    val keyboardDragEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_DRAG_ENABLED] ?: true }

    // ===== 输入：虚拟游戏手柄 =====
    val gamepadEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.GAMEPAD_ENABLED] ?: false }
    val gamepadConfig: Flow<String> = context.appPrefs.data.map { it[Keys.GAMEPAD_CONFIG] ?: "" }

    // ===== 显示 =====
    suspend fun setUiScale(scale: Float) {
        context.appPrefs.edit { it[Keys.UI_SCALE] = scale }
    }

    suspend fun setDisplayOrientation(orientation: String) {
        context.appPrefs.edit { it[Keys.DISPLAY_ORIENTATION] = orientation }
    }

    suspend fun setUseCutout(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.USE_CUTOUT] = enabled }
    }

    // ===== 个性化 =====
    suspend fun setAppColorMode(mode: String) {
        context.appPrefs.edit { it[Keys.APP_COLOR_MODE] = mode }
    }

    suspend fun setAppAccent(accent: String) {
        context.appPrefs.edit { it[Keys.APP_ACCENT] = accent }
    }

    suspend fun setFontScale(scale: Float) {
        context.appPrefs.edit { it[Keys.FONT_SCALE] = scale }
    }

    suspend fun setFontColor(color: String) {
        context.appPrefs.edit { it[Keys.FONT_COLOR] = color }
    }

    suspend fun setFontStyle(style: String) {
        context.appPrefs.edit { it[Keys.FONT_STYLE] = style }
    }

    suspend fun setLanguage(lang: String) {
        context.appPrefs.edit { it[Keys.LANGUAGE] = lang }
    }

    // ===== 终端背景 =====
    suspend fun setTerminalBgColor(color: String) {
        context.appPrefs.edit { it[Keys.TERMINAL_BG_COLOR] = color }
    }

    suspend fun setTerminalBgImage(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.TERMINAL_BG_IMAGE] = enabled }
    }

    suspend fun setTerminalBgTransparency(value: Float) {
        context.appPrefs.edit { it[Keys.TERMINAL_BG_TRANSPARENCY] = value.coerceIn(0f, 1f) }
    }

    // ===== 悬浮球位置 =====
    suspend fun setFabPos(x: Float, y: Float) {
        context.appPrefs.edit {
            it[Keys.FAB_POS_X] = x.coerceIn(0f, 1f)
            it[Keys.FAB_POS_Y] = y.coerceIn(0f, 1f)
        }
    }

    // ===== 输入：鼠标 / 触控板 =====
    suspend fun setMousePointerSpeed(speed: Float) {
        context.appPrefs.edit { it[Keys.MOUSE_POINTER_SPEED] = speed }
    }

    suspend fun setMouseCursorEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.MOUSE_CURSOR_ENABLED] = enabled }
    }

    suspend fun setMouseCursorTheme(theme: String) {
        context.appPrefs.edit { it[Keys.MOUSE_CURSOR_THEME] = theme }
    }

    suspend fun setMouseCursorSize(sizeDp: Float) {
        context.appPrefs.edit { it[Keys.MOUSE_CURSOR_SIZE] = sizeDp }
    }

    suspend fun setMouseRightClick(mode: String) {
        context.appPrefs.edit { it[Keys.MOUSE_RIGHT_CLICK] = mode }
    }

    suspend fun setMouseControlMode(mode: String) {
        context.appPrefs.edit { it[Keys.MOUSE_CONTROL_MODE] = mode }
    }

    // ===== 输入：虚拟键盘 =====
    suspend fun setKeyboardVibration(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_VIBRATION] = enabled }
    }

    suspend fun setTouchFeedback(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.TOUCH_FEEDBACK] = enabled }
    }

    suspend fun setKeyboardMaster(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_MASTER] = enabled }
    }

    suspend fun setKeyboardFuncRow(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_FUNC_ROW] = enabled }
    }

    suspend fun setKeyboardNumpad(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_NUMPAD] = enabled }
    }

    suspend fun setKeyboardScale(scale: Float) {
        context.appPrefs.edit { it[Keys.KEYBOARD_SCALE] = scale }
    }

    suspend fun setKeyboardTheme(theme: String) {
        context.appPrefs.edit { it[Keys.KEYBOARD_THEME] = theme }
    }

    suspend fun setKeyboardPos(x: Float, y: Float) {
        context.appPrefs.edit {
            it[Keys.KEYBOARD_POS_X] = x
            it[Keys.KEYBOARD_POS_Y] = y
        }
    }

    suspend fun setKeyboardDragEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_DRAG_ENABLED] = enabled }
    }

    // ===== 输入：虚拟游戏手柄 =====
    suspend fun setGamepadEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.GAMEPAD_ENABLED] = enabled }
    }

    suspend fun setGamepadConfig(json: String) {
        context.appPrefs.edit { it[Keys.GAMEPAD_CONFIG] = json }
    }
}
