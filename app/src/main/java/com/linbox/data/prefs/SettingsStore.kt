package com.linbox.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appPrefs by preferencesDataStore(name = "linbox_prefs")

/**
 * 应用通用偏好设置：壁纸、音量、是否启用启动音效等。
 *
 * 视觉重构后扩展：增加了蓝牙、鼠标、键盘、电源、应用默认程序、隐私权限等更多设置项，
 * 覆盖 Win11 风格设置中心的主要分类。
 */
class SettingsStore(private val context: Context) {

    companion object {
        /**
         * v2.16 UI 缩放基准系数：100% 档位的实际渲染效果 = 存储值 × 本系数。
         * 用户反馈旧版 100% 桌面元素过大，现把 100% 档位整体缩小到旧版 60% 的
         * 视觉效果；设置里仍显示 100%，其余百分比按同一系数等比映射
         * （如 180% 档 = 旧版 108%，想要旧版大小可调到 300% = 旧版 180%）。
         */
        const val UI_SCALE_BASE = 0.6f
    }

    object Keys {
        val CUSTOM_WALLPAPER = stringPreferencesKey("custom_wallpaper")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val TASKBAR_AUTOHIDE = booleanPreferencesKey("taskbar_autohide")
        val SHOW_SECONDS = booleanPreferencesKey("show_seconds")
        val ICON_SIZE = floatPreferencesKey("icon_size")
        val DEFAULT_BROWSER_HOME = stringPreferencesKey("default_browser_home")
        // UI 缩放（整体缩放所有 dp/sp，>1 放大，<1 缩小）
        // v2.16：存储值不变（默认 1.0 = 菜单里仍显示 100%），但实际渲染时
        // 乘以 UI_SCALE_BASE（0.6），即 100% 档位的视觉效果相当于旧版的 60%，
        // 整体桌面更紧凑小巧。滑杆范围同步扩展到 0.6..3.0（实际 0.36..1.8）。
        val UI_SCALE = floatPreferencesKey("ui_scale")
        // 显示方向：auto / portrait / landscape
        val DISPLAY_ORIENTATION = stringPreferencesKey("display_orientation")
        // 浏览器桌面/手机模式：desktop / mobile
        val BROWSER_UA_MODE = stringPreferencesKey("browser_ua_mode")

        // === 浏览器 3D 视角（v2.16.2：鼠标视角模式，用于电脑网页游戏） ===
        // 开启后：在网页上按住拖动 = 按住鼠标移动视角（把拖动增量合成
        // mousemove 事件注入页面，游戏的相机/视角跟随旋转），
        // 并非对页面做任何视觉变换
        val BROWSER_3D_ENABLED = booleanPreferencesKey("browser_3d_enabled")
        // 视角灵敏度（0.2..3.0，拖动像素增量的倍率）
        val BROWSER_3D_SENSITIVITY = floatPreferencesKey("browser_3d_sensitivity")

        // === v2.14：个性化（颜色 / 字体 / 任务栏） ===
        // 颜色模式：auto 跟随主题 / light 强制浅色 / dark 强制深色（作用于所有 Windows 主题）
        val APP_COLOR_MODE = stringPreferencesKey("app_color_mode")
        // 强调色覆盖：default 主题自带 / "#RRGGBB" 十六进制色值
        val APP_ACCENT = stringPreferencesKey("app_accent")
        // 全局字体缩放（0.85..1.4，乘到系统 fontScale 上，控制所有文字大小）
        val FONT_SCALE = floatPreferencesKey("font_scale")
        // 全局字体颜色：auto 跟随主题 / white 白色 / black 黑色
        val FONT_COLOR = stringPreferencesKey("font_color")
        // 全局字体样式：default 无衬线 / serif 衬线 / mono 等宽
        val FONT_STYLE = stringPreferencesKey("font_style")
        // 任务栏图标对齐：true 居中（Win11 风格）/ false 靠左（Win7 风格）
        val TASKBAR_CENTERED = booleanPreferencesKey("taskbar_centered")

        // === 刘海屏 / 任务栏 ===
        // 内容是否绘制进刘海屏区域（默认 true，占用刘海屏）
        val USE_CUTOUT = booleanPreferencesKey("use_cutout")
        // 任务栏高度（dp，36..80；0f 表示跟随主题默认）
        val TASKBAR_HEIGHT = floatPreferencesKey("taskbar_height")
        // === 托盘时钟样式（v2.10，长按任务栏时间弹出设置） ===
        // 显示模式：digital 数字 / clock 表盘 / lcd 液晶
        val TRAY_CLOCK_MODE = stringPreferencesKey("tray_clock_mode")
        // 时钟字号（sp，8..18）
        val TRAY_CLOCK_FONT_SIZE = floatPreferencesKey("tray_clock_font_size")
        // 是否显示日期
        val TRAY_SHOW_DATE = booleanPreferencesKey("tray_show_date")
        // 排版：stacked 时间/日期两行 / inline 单行
        val TRAY_LAYOUT = stringPreferencesKey("tray_layout")

        // === 桌面图标（v2.11 右键菜单"排序方式"） ===
        // 排序模式：default 默认（内置应用 + 快捷方式原序）/ name 按名称 / type 按类型
        val DESKTOP_SORT = stringPreferencesKey("desktop_sort")
        // v2.14：桌面图标自定义顺序（长按拖动排序后保存，逗号分隔的 item id 列表）
        val DESKTOP_ICON_ORDER = stringPreferencesKey("desktop_icon_order")

        // === 蓝牙与设备 ===
        val BLUETOOTH_ENABLED = booleanPreferencesKey("bluetooth_enabled")
        val MOUSE_POINTER_SPEED = floatPreferencesKey("mouse_pointer_speed")
        val KEYBOARD_VIBRATION = booleanPreferencesKey("keyboard_vibration")
        val TOUCH_FEEDBACK = booleanPreferencesKey("touch_feedback")

        // === 虚拟鼠标（v2.13：Windows 风格指针） ===
        // 是否显示鼠标指针
        val MOUSE_CURSOR_ENABLED = booleanPreferencesKey("mouse_cursor_enabled")
        // 指针主题：white 经典白 / black 经典黑 / blue 蓝色 / green 高对比绿
        val MOUSE_CURSOR_THEME = stringPreferencesKey("mouse_cursor_theme")
        // 指针大小（dp，16..48）
        val MOUSE_CURSOR_SIZE = floatPreferencesKey("mouse_cursor_size")
        // 图标打开方式：single 单击打开 / double 双击打开（默认 single）
        val MOUSE_CLICK_MODE = stringPreferencesKey("mouse_click_mode")
        // 右键手势：twofinger 双指轻点 / longpress 长按（默认 twofinger）
        val MOUSE_RIGHT_CLICK = stringPreferencesKey("mouse_right_click")
        // v2.18 指针移动方式：touch 指针跟随手指 / trackpad 触控板（滑动相对控制指针）
        val MOUSE_CONTROL_MODE = stringPreferencesKey("mouse_control_mode")
        // v2.19 首次启动存储权限引导：是否已经提示过（避免每次启动都弹）
        val STORAGE_PERM_ASKED = booleanPreferencesKey("storage_perm_asked")

        // === 虚拟键盘（v2.13：全键盘） ===
        // 总开关：off 时文本框回退系统输入法
        val KEYBOARD_MASTER = booleanPreferencesKey("keyboard_master")
        // 显示功能键行（Esc + F1-F12）
        val KEYBOARD_FUNC_ROW = booleanPreferencesKey("keyboard_func_row")
        // 显示小键盘（含方向键）
        val KEYBOARD_NUMPAD = booleanPreferencesKey("keyboard_numpad")
        // 键盘缩放（0.75..1.35）
        val KEYBOARD_SCALE = floatPreferencesKey("keyboard_scale")
        // 键盘主题：light / dark / blue / glass
        val KEYBOARD_THEME = stringPreferencesKey("keyboard_theme")
        // 键盘位置（归一化 0..1，x 居中比例 / y 1.0=贴底）
        val KEYBOARD_POS_X = floatPreferencesKey("keyboard_pos_x")
        val KEYBOARD_POS_Y = floatPreferencesKey("keyboard_pos_y")
        // 允许拖动键盘
        val KEYBOARD_DRAG_ENABLED = booleanPreferencesKey("keyboard_drag_enabled")

        // === 虚拟游戏手柄（v2.15） ===
        // 总开关：游戏时显示手柄覆盖层
        val GAMEPAD_ENABLED = booleanPreferencesKey("gamepad_enabled")
        // 手柄布局配置（JSON：元素类型/标签/映射/位置/大小/方向模式）
        val GAMEPAD_CONFIG = stringPreferencesKey("gamepad_config")

        // === 系统：电源、通知 ===
        val POWER_SAVER = booleanPreferencesKey("power_saver")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val DO_NOT_DISTURB = booleanPreferencesKey("do_not_disturb")

        // === 应用：默认程序 ===
        val DEFAULT_BROWSER = stringPreferencesKey("default_browser")
        val DEFAULT_FILE_MANAGER = stringPreferencesKey("default_file_manager")
        val AUTOSTART_APPS = stringPreferencesKey("autostart_apps")  // 逗号分隔的 app id

        // === 时间与语言 ===
        val TIME_FORMAT_24H = booleanPreferencesKey("time_format_24h")
        val LANGUAGE = stringPreferencesKey("language")

        // === 隐私和安全 ===
        val LOCATION_ENABLED = booleanPreferencesKey("location_enabled")
        val CAMERA_ACCESS = booleanPreferencesKey("camera_access")
        val MICROPHONE_ACCESS = booleanPreferencesKey("microphone_access")
        val DIAGNOSTICS_OPT_IN = booleanPreferencesKey("diagnostics_opt_in")

        // === Windows Update ===
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")  // stable / beta / dev

        // === v2.17 锁屏设置 ===
        // 锁屏总开关：关闭后自动锁屏停用、开始菜单不再显示“锁定”
        val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        // 锁屏独立壁纸：null 跟随桌面壁纸；"file://..." 图片；"video://..." 视频
        val LOCK_WALLPAPER = stringPreferencesKey("lock_wallpaper")
        // 锁屏密码哈希（SHA-256，前缀盐）；空串 = 未设置密码
        val LOCK_PIN_HASH = stringPreferencesKey("lock_pin_hash")
        // 自动锁屏空闲分钟数：0 = 从不，1/5/15/30/60
        val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")

        // === v2.17 桌面设置（原“账户”页桌面化） ===
        // 开始菜单底部显示的用户名 / 头像 emoji（空 = 默认图标）
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_AVATAR = stringPreferencesKey("user_avatar")
    }

    // === 基础设置 ===
    val customWallpaper: Flow<String?> = context.appPrefs.data.map { it[Keys.CUSTOM_WALLPAPER] }
    val soundEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.SOUND_ENABLED] ?: true }
    val taskbarAutohide: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TASKBAR_AUTOHIDE] ?: false }
    val showSeconds: Flow<Boolean> = context.appPrefs.data.map { it[Keys.SHOW_SECONDS] ?: false }
    val iconSize: Flow<Float> = context.appPrefs.data.map { it[Keys.ICON_SIZE] ?: 48f }
    val defaultBrowserHome: Flow<String> = context.appPrefs.data
        .map { it[Keys.DEFAULT_BROWSER_HOME] ?: "https://www.bing.com" }
    val uiScale: Flow<Float> = context.appPrefs.data.map { it[Keys.UI_SCALE] ?: 1.0f }
    val displayOrientation: Flow<String> = context.appPrefs.data
        .map { it[Keys.DISPLAY_ORIENTATION] ?: "auto" }
    val browserUaMode: Flow<String> = context.appPrefs.data
        .map { it[Keys.BROWSER_UA_MODE] ?: "desktop" }

    // === 浏览器 3D 视角（v2.16） ===
    val browser3dEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.BROWSER_3D_ENABLED] ?: false }
    val browser3dSensitivity: Flow<Float> = context.appPrefs.data.map { it[Keys.BROWSER_3D_SENSITIVITY] ?: 1f }

    // === 刘海屏 / 任务栏 ===
    val useCutout: Flow<Boolean> = context.appPrefs.data.map { it[Keys.USE_CUTOUT] ?: true }
    val taskbarHeight: Flow<Float> = context.appPrefs.data.map { it[Keys.TASKBAR_HEIGHT] ?: 0f }

    // === 托盘时钟样式 ===
    val trayClockMode: Flow<String> = context.appPrefs.data.map { it[Keys.TRAY_CLOCK_MODE] ?: "digital" }
    val trayClockFontSize: Flow<Float> = context.appPrefs.data.map { it[Keys.TRAY_CLOCK_FONT_SIZE] ?: 0f }
    val trayShowDate: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TRAY_SHOW_DATE] ?: true }
    val trayLayout: Flow<String> = context.appPrefs.data.map { it[Keys.TRAY_LAYOUT] ?: "stacked" }

    // === 桌面图标 ===
    val desktopSort: Flow<String> = context.appPrefs.data.map { it[Keys.DESKTOP_SORT] ?: "default" }
    // v2.14：自定义图标顺序（空串 = 未自定义，按默认序）
    val desktopIconOrder: Flow<String> = context.appPrefs.data.map { it[Keys.DESKTOP_ICON_ORDER] ?: "" }

    // === v2.14：个性化 ===
    val appColorMode: Flow<String> = context.appPrefs.data.map { it[Keys.APP_COLOR_MODE] ?: "auto" }
    val appAccent: Flow<String> = context.appPrefs.data.map { it[Keys.APP_ACCENT] ?: "default" }
    val fontScale: Flow<Float> = context.appPrefs.data.map { it[Keys.FONT_SCALE] ?: 1.0f }
    val fontColor: Flow<String> = context.appPrefs.data.map { it[Keys.FONT_COLOR] ?: "auto" }
    val fontStyle: Flow<String> = context.appPrefs.data.map { it[Keys.FONT_STYLE] ?: "default" }
    val taskbarCentered: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TASKBAR_CENTERED] ?: true }

    // === 蓝牙与设备 ===
    val bluetoothEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.BLUETOOTH_ENABLED] ?: false }
    val mousePointerSpeed: Flow<Float> = context.appPrefs.data.map { it[Keys.MOUSE_POINTER_SPEED] ?: 1.0f }
    val keyboardVibration: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_VIBRATION] ?: true }
    val touchFeedback: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TOUCH_FEEDBACK] ?: false }

    // === 虚拟鼠标（v2.13） ===
    val mouseCursorEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.MOUSE_CURSOR_ENABLED] ?: true }
    val mouseCursorTheme: Flow<String> = context.appPrefs.data.map { it[Keys.MOUSE_CURSOR_THEME] ?: "white" }
    val mouseCursorSize: Flow<Float> = context.appPrefs.data.map { it[Keys.MOUSE_CURSOR_SIZE] ?: 26f }
    val mouseClickMode: Flow<String> = context.appPrefs.data.map { it[Keys.MOUSE_CLICK_MODE] ?: "single" }
    val mouseRightClick: Flow<String> = context.appPrefs.data.map { it[Keys.MOUSE_RIGHT_CLICK] ?: "twofinger" }
    // v2.18 指针移动方式：touch 跟随手指 / trackpad 触控板（默认 touch）
    val mouseControlMode: Flow<String> = context.appPrefs.data.map { it[Keys.MOUSE_CONTROL_MODE] ?: "touch" }

    // === v2.19 首次启动存储权限引导 ===
    val storagePermAsked: Flow<Boolean> = context.appPrefs.data.map { it[Keys.STORAGE_PERM_ASKED] ?: false }

    // === 虚拟键盘（v2.13） ===
    // v2.13.2：keyboardMaster 默认关闭 —— 大多数场景手机系统输入法更顺手，
    // 需要应用内全键盘的用户在 设置→键盘 里主动开启
    val keyboardMaster: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_MASTER] ?: false }
    val keyboardFuncRow: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_FUNC_ROW] ?: true }
    val keyboardNumpad: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_NUMPAD] ?: true }
    val keyboardScale: Flow<Float> = context.appPrefs.data.map { it[Keys.KEYBOARD_SCALE] ?: 1.0f }
    val keyboardTheme: Flow<String> = context.appPrefs.data.map { it[Keys.KEYBOARD_THEME] ?: "dark" }
    val keyboardPosX: Flow<Float> = context.appPrefs.data.map { it[Keys.KEYBOARD_POS_X] ?: 0.5f }
    val keyboardPosY: Flow<Float> = context.appPrefs.data.map { it[Keys.KEYBOARD_POS_Y] ?: 1.0f }
    val keyboardDragEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_DRAG_ENABLED] ?: true }

    // === 虚拟游戏手柄（v2.15） ===
    val gamepadEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.GAMEPAD_ENABLED] ?: false }
    val gamepadConfig: Flow<String> = context.appPrefs.data.map { it[Keys.GAMEPAD_CONFIG] ?: "" }

    // === 系统 ===
    val powerSaver: Flow<Boolean> = context.appPrefs.data.map { it[Keys.POWER_SAVER] ?: false }
    val brightness: Flow<Float> = context.appPrefs.data.map { it[Keys.BRIGHTNESS] ?: 0.8f }
    val notificationsEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.NOTIFICATIONS_ENABLED] ?: true }
    val doNotDisturb: Flow<Boolean> = context.appPrefs.data.map { it[Keys.DO_NOT_DISTURB] ?: false }

    // === 应用 ===
    val defaultBrowser: Flow<String> = context.appPrefs.data.map { it[Keys.DEFAULT_BROWSER] ?: "browser" }
    val defaultFileManager: Flow<String> = context.appPrefs.data.map { it[Keys.DEFAULT_FILE_MANAGER] ?: "file_explorer" }
    val autostartApps: Flow<String> = context.appPrefs.data.map { it[Keys.AUTOSTART_APPS] ?: "" }

    // === 时间与语言 ===
    val timeFormat24h: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TIME_FORMAT_24H] ?: true }
    val language: Flow<String> = context.appPrefs.data.map { it[Keys.LANGUAGE] ?: "zh-CN" }

    // === 隐私和安全 ===
    val locationEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.LOCATION_ENABLED] ?: false }
    val cameraAccess: Flow<Boolean> = context.appPrefs.data.map { it[Keys.CAMERA_ACCESS] ?: true }
    val microphoneAccess: Flow<Boolean> = context.appPrefs.data.map { it[Keys.MICROPHONE_ACCESS] ?: true }
    val diagnosticsOptIn: Flow<Boolean> = context.appPrefs.data.map { it[Keys.DIAGNOSTICS_OPT_IN] ?: false }

    // === Windows Update ===
    val autoUpdate: Flow<Boolean> = context.appPrefs.data.map { it[Keys.AUTO_UPDATE] ?: true }
    val updateChannel: Flow<String> = context.appPrefs.data.map { it[Keys.UPDATE_CHANNEL] ?: "stable" }

    // === v2.17 锁屏设置 ===
    val lockEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.LOCK_ENABLED] ?: true }
    val lockWallpaper: Flow<String?> = context.appPrefs.data.map { it[Keys.LOCK_WALLPAPER] }
    val lockPinHash: Flow<String> = context.appPrefs.data.map { it[Keys.LOCK_PIN_HASH] ?: "" }
    val autoLockMinutes: Flow<Int> = context.appPrefs.data.map { it[Keys.AUTO_LOCK_MINUTES] ?: 0 }

    // === v2.17 桌面设置 ===
    val userName: Flow<String> = context.appPrefs.data.map { it[Keys.USER_NAME] ?: "LinBox" }
    val userAvatar: Flow<String> = context.appPrefs.data.map { it[Keys.USER_AVATAR] ?: "" }

    // === 基础 setter ===
    suspend fun setCustomWallpaper(uri: String?) {
        context.appPrefs.edit { prefs ->
            if (uri == null) prefs.remove(Keys.CUSTOM_WALLPAPER)
            else prefs[Keys.CUSTOM_WALLPAPER] = uri
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun setTaskbarAutohide(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.TASKBAR_AUTOHIDE] = enabled }
    }

    suspend fun setShowSeconds(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.SHOW_SECONDS] = enabled }
    }

    suspend fun setIconSize(size: Float) {
        context.appPrefs.edit { it[Keys.ICON_SIZE] = size }
    }

    suspend fun setDefaultBrowserHome(url: String) {
        context.appPrefs.edit { it[Keys.DEFAULT_BROWSER_HOME] = url }
    }

    suspend fun setUiScale(scale: Float) {
        // v2.16：上限扩到 3.0（实际效果 1.8，与旧版最大档一致），
        // 下限 0.6（实际效果 0.36，比旧版最小档更紧凑）
        context.appPrefs.edit { it[Keys.UI_SCALE] = scale.coerceIn(0.6f, 3.0f) }
    }

    suspend fun setDisplayOrientation(orientation: String) {
        context.appPrefs.edit { it[Keys.DISPLAY_ORIENTATION] = orientation }
    }

    suspend fun setBrowserUaMode(mode: String) {
        context.appPrefs.edit { it[Keys.BROWSER_UA_MODE] = mode }
    }

    // === 浏览器 3D 视角 setter（v2.16） ===
    suspend fun setBrowser3dEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.BROWSER_3D_ENABLED] = enabled }
    }

    suspend fun setBrowser3dSensitivity(value: Float) {
        context.appPrefs.edit { it[Keys.BROWSER_3D_SENSITIVITY] = value.coerceIn(0.2f, 3f) }
    }

    // === 刘海屏 / 任务栏 setter ===
    suspend fun setUseCutout(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.USE_CUTOUT] = enabled }
    }

    suspend fun setTaskbarHeight(heightDp: Float) {
        context.appPrefs.edit { it[Keys.TASKBAR_HEIGHT] = heightDp.coerceIn(0f, 80f) }
    }

    // === 托盘时钟样式 setter ===
    suspend fun setTrayClockMode(mode: String) {
        context.appPrefs.edit { it[Keys.TRAY_CLOCK_MODE] = mode }
    }

    suspend fun setTrayClockFontSize(sizeSp: Float) {
        // 0 表示"自动"（跟随任务栏高度自适应），有效范围 8..18sp
        val v = if (sizeSp < 8f) 0f else sizeSp.coerceIn(8f, 18f)
        context.appPrefs.edit { it[Keys.TRAY_CLOCK_FONT_SIZE] = v }
    }

    suspend fun setTrayShowDate(show: Boolean) {
        context.appPrefs.edit { it[Keys.TRAY_SHOW_DATE] = show }
    }

    suspend fun setTrayLayout(layout: String) {
        context.appPrefs.edit { it[Keys.TRAY_LAYOUT] = layout }
    }

    // === 桌面图标 setter ===
    suspend fun setDesktopSort(mode: String) {
        val v = if (mode in setOf("default", "name", "type")) mode else "default"
        context.appPrefs.edit { it[Keys.DESKTOP_SORT] = v }
    }

    suspend fun setDesktopIconOrder(order: String) {
        context.appPrefs.edit { it[Keys.DESKTOP_ICON_ORDER] = order }
    }

    // === v2.14：个性化 setter ===
    suspend fun setAppColorMode(mode: String) {
        val v = if (mode in setOf("auto", "light", "dark")) mode else "auto"
        context.appPrefs.edit { it[Keys.APP_COLOR_MODE] = v }
    }

    suspend fun setAppAccent(accent: String) {
        context.appPrefs.edit { it[Keys.APP_ACCENT] = accent }
    }

    suspend fun setFontScale(scale: Float) {
        context.appPrefs.edit { it[Keys.FONT_SCALE] = scale.coerceIn(0.85f, 1.4f) }
    }

    suspend fun setFontColor(color: String) {
        val v = if (color in setOf("auto", "white", "black")) color else "auto"
        context.appPrefs.edit { it[Keys.FONT_COLOR] = v }
    }

    suspend fun setFontStyle(style: String) {
        val v = if (style in setOf("default", "serif", "mono")) style else "default"
        context.appPrefs.edit { it[Keys.FONT_STYLE] = v }
    }

    suspend fun setTaskbarCentered(centered: Boolean) {
        context.appPrefs.edit { it[Keys.TASKBAR_CENTERED] = centered }
    }

    // === 蓝牙与设备 setter ===
    suspend fun setBluetoothEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.BLUETOOTH_ENABLED] = enabled }
    }

    suspend fun setMousePointerSpeed(speed: Float) {
        context.appPrefs.edit { it[Keys.MOUSE_POINTER_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setKeyboardVibration(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_VIBRATION] = enabled }
    }

    suspend fun setTouchFeedback(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.TOUCH_FEEDBACK] = enabled }
    }

    // === 虚拟鼠标 setter（v2.13） ===
    suspend fun setMouseCursorEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.MOUSE_CURSOR_ENABLED] = enabled }
    }

    suspend fun setMouseCursorTheme(theme: String) {
        val v = if (theme in setOf("white", "black", "blue", "green")) theme else "white"
        context.appPrefs.edit { it[Keys.MOUSE_CURSOR_THEME] = v }
    }

    suspend fun setMouseCursorSize(sizeDp: Float) {
        context.appPrefs.edit { it[Keys.MOUSE_CURSOR_SIZE] = sizeDp.coerceIn(16f, 48f) }
    }

    suspend fun setMouseClickMode(mode: String) {
        val v = if (mode in setOf("single", "double")) mode else "single"
        context.appPrefs.edit { it[Keys.MOUSE_CLICK_MODE] = v }
    }

    suspend fun setMouseRightClick(mode: String) {
        val v = if (mode in setOf("twofinger", "longpress")) mode else "twofinger"
        context.appPrefs.edit { it[Keys.MOUSE_RIGHT_CLICK] = v }
    }

    /**
     * v2.18：指针移动方式（touch 跟随手指 / trackpad 触控板）。
     * 切到 trackpad 时按用户约定同时落地"双击打开 + 双指右键"默认习惯；
     * 切回 touch 时恢复"单击打开"（右键手势保持双指轻点）。
     */
    suspend fun setMouseControlMode(mode: String) {
        val v = if (mode == "trackpad") "trackpad" else "touch"
        context.appPrefs.edit { prefs ->
            prefs[Keys.MOUSE_CONTROL_MODE] = v
            if (v == "trackpad") {
                prefs[Keys.MOUSE_CLICK_MODE] = "double"
                prefs[Keys.MOUSE_RIGHT_CLICK] = "twofinger"
            } else {
                prefs[Keys.MOUSE_CLICK_MODE] = "single"
            }
        }
    }

    // === v2.19 存储权限引导 ===
    /** 首启引导已展示过（用户选了"稍后"或已授权），不再重复弹出 */
    suspend fun setStoragePermAsked() {
        context.appPrefs.edit { it[Keys.STORAGE_PERM_ASKED] = true }
    }

    // === 虚拟键盘 setter（v2.13） ===
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
        context.appPrefs.edit { it[Keys.KEYBOARD_SCALE] = scale.coerceIn(0.75f, 1.35f) }
    }

    suspend fun setKeyboardTheme(theme: String) {
        val v = if (theme in setOf("light", "dark", "blue", "glass")) theme else "dark"
        context.appPrefs.edit { it[Keys.KEYBOARD_THEME] = v }
    }

    suspend fun setKeyboardPos(x: Float, y: Float) {
        context.appPrefs.edit {
            it[Keys.KEYBOARD_POS_X] = x.coerceIn(0f, 1f)
            it[Keys.KEYBOARD_POS_Y] = y.coerceIn(0f, 1f)
        }
    }

    suspend fun setKeyboardDragEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_DRAG_ENABLED] = enabled }
    }

    // === 虚拟游戏手柄 setter（v2.15） ===
    suspend fun setGamepadEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.GAMEPAD_ENABLED] = enabled }
    }

    suspend fun setGamepadConfig(json: String) {
        context.appPrefs.edit { it[Keys.GAMEPAD_CONFIG] = json }
    }

    // === 系统 setter ===
    suspend fun setPowerSaver(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.POWER_SAVER] = enabled }
    }

    suspend fun setBrightness(value: Float) {
        // v2.12：下限放宽到 0.05（跟随设置中心/快速面板的真实滑块范围）
        context.appPrefs.edit { it[Keys.BRIGHTNESS] = value.coerceIn(0.05f, 1.0f) }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setDoNotDisturb(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.DO_NOT_DISTURB] = enabled }
    }

    // === 应用 setter ===
    suspend fun setDefaultBrowser(id: String) {
        context.appPrefs.edit { it[Keys.DEFAULT_BROWSER] = id }
    }

    suspend fun setDefaultFileManager(id: String) {
        context.appPrefs.edit { it[Keys.DEFAULT_FILE_MANAGER] = id }
    }

    /** v2.17：开机自动启动的应用列表（逗号分隔的 app id） */
    suspend fun setAutostartApps(ids: List<String>) {
        context.appPrefs.edit { prefs ->
            val joined = ids.filter { it.isNotBlank() }.distinct().joinToString(",")
            if (joined.isEmpty()) prefs.remove(Keys.AUTOSTART_APPS)
            else prefs[Keys.AUTOSTART_APPS] = joined
        }
    }

    // === 时间与语言 setter ===
    suspend fun setTimeFormat24h(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.TIME_FORMAT_24H] = enabled }
    }

    suspend fun setLanguage(lang: String) {
        context.appPrefs.edit { it[Keys.LANGUAGE] = lang }
    }

    // === 隐私和安全 setter ===
    suspend fun setLocationEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.LOCATION_ENABLED] = enabled }
    }

    suspend fun setCameraAccess(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.CAMERA_ACCESS] = enabled }
    }

    suspend fun setMicrophoneAccess(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.MICROPHONE_ACCESS] = enabled }
    }

    suspend fun setDiagnosticsOptIn(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.DIAGNOSTICS_OPT_IN] = enabled }
    }

    // === Windows Update setter ===
    suspend fun setAutoUpdate(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.AUTO_UPDATE] = enabled }
    }

    suspend fun setUpdateChannel(channel: String) {
        context.appPrefs.edit { it[Keys.UPDATE_CHANNEL] = channel }
    }

    // === v2.17 锁屏设置 setter ===
    suspend fun setLockEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.LOCK_ENABLED] = enabled }
    }

    suspend fun setLockWallpaper(uri: String?) {
        context.appPrefs.edit { prefs ->
            if (uri.isNullOrEmpty()) prefs.remove(Keys.LOCK_WALLPAPER)
            else prefs[Keys.LOCK_WALLPAPER] = uri
        }
    }

    suspend fun setLockPinHash(hash: String) {
        context.appPrefs.edit { prefs ->
            if (hash.isBlank()) prefs.remove(Keys.LOCK_PIN_HASH)
            else prefs[Keys.LOCK_PIN_HASH] = hash
        }
    }

    suspend fun setAutoLockMinutes(minutes: Int) {
        // 只接受白名单值，其余视为“从不”
        val v = if (minutes in setOf(0, 1, 5, 15, 30, 60)) minutes else 0
        context.appPrefs.edit { it[Keys.AUTO_LOCK_MINUTES] = v }
    }

    // === v2.17 桌面设置 setter ===
    suspend fun setUserName(name: String) {
        context.appPrefs.edit { prefs ->
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed == "LinBox") prefs.remove(Keys.USER_NAME)
            else prefs[Keys.USER_NAME] = trimmed
        }
    }

    suspend fun setUserAvatar(avatar: String) {
        context.appPrefs.edit { prefs ->
            if (avatar.isBlank()) prefs.remove(Keys.USER_AVATAR)
            else prefs[Keys.USER_AVATAR] = avatar
        }
    }

    /**
     * 清空全部偏好设置（设置中心"重置应用"使用）。
     * 注意：壁纸 URI 的持久化权限无法逐项回收，重置后由系统在重启时自动回收。
     */
    suspend fun clearAll() {
        context.appPrefs.edit { it.clear() }
    }
}
