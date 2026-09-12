package com.linbox.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * v2.14 应用内双语（中文 / English）。
 *
 * 设计：key = 中文原文，en 字典 = 中文 → 英文。
 * - [L]（Composable）：读取 [LocalAppLanguage]，en-US 模式下查字典，查不到回退中文原文
 * - [L10n.t]（非 Composable，如 Toast / WebView 回调）：读取全局同步的 [L10n.current]
 *
 * 覆盖范围：设置中心导航/分区/主要卡片、个性化页、时间与语言页、应用页、
 * 开始菜单、桌面右键菜单、任务栏、锁屏等高频界面；
 * 未收录词条（各内置应用内部文案）回退中文，后续版本逐步补充。
 */
object L10n {

    /** 全局语言状态（供非 Composable 环境读取；由 MainActivity 每次重组同步） */
    @Volatile
    var current: String = "zh-CN"

    /** 非组合环境的取词（Toast / 回调里用） */
    fun t(text: String): String =
        if (current == "en-US") en[text] ?: text else text

    val en: Map<String, String> = mapOf(
        // ===== 通用 =====
        "设置" to "Settings",
        "确定" to "OK",
        "取消" to "Cancel",
        "保存" to "Save",
        "重置" to "Reset",
        "开启" to "On",
        "关闭" to "Off",
        "已开启" to "On",
        "已关闭" to "Off",
        "搜索" to "Search",
        "查找设置" to "Find a setting",

        // ===== 设置中心：左侧导航分区 =====
        "系统" to "System",
        "蓝牙和设备" to "Bluetooth & devices",
        "蓝牙和其他设备" to "Bluetooth & other devices",
        "网络和 Internet" to "Network & internet",
        "个性化" to "Personalization",
        "应用" to "Apps",
        "账户" to "Accounts",
        "时间和语言" to "Time & language",
        "时间与语言" to "Time & language",
        "游戏" to "Gaming",
        "辅助功能" to "Accessibility",
        "隐私和安全" to "Privacy & security",
        "隐私和安全性" to "Privacy & security",
        "Windows 更新" to "Windows Update",
        "关于" to "About",

        // ===== 设置中心：分区标题/副标题 =====
        "显示、声音、通知、电源" to "Sound, display, notifications, power",
        "声音、显示、通知、电源" to "Sound, display, notifications, power",
        "设备管理、蓝牙、打印机" to "Devices, Bluetooth, printers",
        "Wi-Fi、移动网络、飞行模式、数据使用量" to "Wi-Fi, mobile networks, airplane mode, data usage",
        "背景、颜色、主题、锁屏界面" to "Background, colors, themes, lock screen",
        "已安装的应用、默认应用、可选功能" to "Installed apps, defaults, optional features",
        "您的账户、登录选项" to "Your accounts, sign-in options",
        "账户信息、登录选项、邮箱、同步" to "Accounts, sign-in, email, sync",
        "语音、区域、日期" to "Speech, region, date",
        "日期和时间、语言区域、输入" to "Date & time, language & region, typing",
        "Xbox Game Bar、游戏模式、屏幕捕获" to "Xbox Game Bar, game mode, capture",
        "视觉、听觉、交互辅助" to "Vision, hearing, interaction",
        "Windows 安全中心、权限" to "Windows Security, permissions",
        "位置、相机、麦克风、诊断" to "Location, camera, microphone, diagnostics",
        "更新、备份、恢复" to "Update, backup, recovery",
        "获取最新更新、安全补丁、新功能" to "Latest updates, security patches, new features",
        "LinBox 系统信息" to "About LinBox",

        // ===== 个性化 =====
        "主题" to "Themes",
        "背景" to "Background",
        "深浅模式对所有 Windows 主题生效" to "Color mode applies to all Windows themes",
        "自动隐藏任务栏" to "Auto-hide the taskbar",
        "壁纸" to "Wallpaper",
        "从文件资源管理器选择图片" to "Pick an image from File Explorer",
        "当前壁纸：自定义" to "Current: custom",
        "当前：主题默认" to "Current: theme default",
        "恢复默认壁纸" to "Restore default wallpaper",
        "使用主题自带的默认壁纸" to "Use the theme's built-in wallpaper",
        "设为桌面壁纸" to "Set as desktop wallpaper",
        "已设为壁纸" to "Wallpaper updated",
        "颜色" to "Colors",
        "深浅模式" to "Color mode",
        "跟随主题" to "Follow theme",
        "浅色" to "Light",
        "深色" to "Dark",
        "强调色" to "Accent color",
        "主题自带" to "Theme default",
        "锁屏界面" to "Lock screen",
        "点击立即锁定，上滑解锁" to "Tap to lock now, swipe up to unlock",
        "立即锁定" to "Lock now",
        "字体" to "Fonts",
        "字体大小" to "Font size",
        "字体颜色" to "Font color",
        "字体样式" to "Font style",
        "跟随主题色" to "Follow theme",
        "白色" to "White",
        "黑色" to "Black",
        "无衬线（默认）" to "Sans-serif (default)",
        "衬线" to "Serif",
        "等宽" to "Monospace",
        "预览：LinBox Windows 桌面体验 AaBbCc 123" to "Preview: LinBox desktop AaBbCc 123",
        "居中" to "Centered",
        "左对齐" to "Left-aligned",
        "Win11 居中风格" to "Win11 centered style",
        "经典靠左风格" to "Classic left-aligned",

        // ===== 时间与语言 =====
        "语言和区域" to "Language & region",
        "显示语言" to "Display language",
        "中文" to "中文",
        "English" to "English",
        "语言已切换" to "Language changed",
        "已切换为 English，主要界面即时生效" to "Switched to English; main UI updates instantly",
        "已切换为中文，主要界面即时生效" to "已切换为中文，主要界面即时生效",
        "切换后设置中心、开始菜单、任务栏等主要界面即时生效" to "Settings, Start menu, taskbar and other main UI switch instantly",
        "输入" to "Typing",
        "键盘、字典、自动更正（打开系统输入法设置）" to "Keyboard, dictionary, autocorrect (system settings)",

        // ===== 应用 =====
        // （v2.14.3 清理：旧渲染模式设置相关词条已随 v2.14 灰屏 hack 一并移除）
        "已安装应用" to "Installed apps",
        "浏览器 UA 模式" to "Browser UA mode",
        "桌面模式" to "Desktop mode",
        "手机模式" to "Mobile mode",

        // ===== 开始菜单 =====
        "所有应用" to "All apps",
        "搜索应用和 Web" to "Search apps and web",
        "电源" to "Power",
        "锁定" to "Lock",
        "关闭所有窗口" to "Close all windows",
        "用户" to "User",
        "已固定" to "Pinned",

        // ===== 桌面右键菜单 =====
        "打开" to "Open",
        "重命名" to "Rename",
        "删除" to "Delete",
        "属性" to "Properties",
        "查看" to "View",
        "大图标" to "Large icons",
        "中等图标" to "Medium icons",
        "小图标" to "Small icons",
        "排序方式" to "Sort by",
        "默认" to "Default",
        "名称" to "Name",
        "类型" to "Type",
        "刷新" to "Refresh",
        "新建快捷方式" to "New shortcut",
        "切换主题" to "Switch theme",
        "显示设置" to "Display settings",
        "个性化设置" to "Personalize",
        "打开终端" to "Open terminal",
        "任务管理器" to "Task Manager",

        // ===== 锁屏 =====
        "上滑或点击解锁" to "Swipe up or tap to unlock",
        "已锁定" to "Locked",

        // ===== 桌面图标拖动 =====
        "拖动排序" to "Drag to reorder",

        // ===== v2.17 开始菜单：手机应用 =====
        "已固定" to "Pinned",
        "手机应用" to "Phone apps",
        "系统应用" to "System apps",
        "正在读取手机应用…" to "Loading phone apps…",
        "没有读取到用户安装的应用" to "No user-installed apps found",
        "没有读取到系统应用" to "No system apps found",
        "没有找到相关应用" to "No matching apps",

        // ===== v2.17 锁屏设置 =====
        "锁屏设置" to "Lock screen settings",
        "锁屏" to "Lock screen",
        "已开启 · 可从开始菜单锁定或按定时自动锁定" to "On · Lock from Start menu or auto-lock on schedule",
        "已关闭 · 自动锁屏与锁定入口停用" to "Off · Auto-lock and lock entry disabled",
        "锁屏壁纸" to "Lock screen wallpaper",
        "当前" to "Current",
        "未设置（跟随桌面壁纸）" to "Not set (follows desktop wallpaper)",
        "视频：" to "Video: ",
        "图片：" to "Image: ",
        "选择后锁屏界面使用独立壁纸；视频将静音循环播放" to "The lock screen uses its own wallpaper; videos play muted in a loop",
        "选择图片" to "Choose image",
        "选择视频" to "Choose video",
        "恢复默认" to "Restore default",
        "选择锁屏图片" to "Choose lock image",
        "选择锁屏视频" to "Choose lock video",
        "锁屏密码" to "Lock screen password",
        "已设置锁屏密码（4-8 位数字）" to "Password set (4-8 digits)",
        "未设置 · 解锁无需密码" to "Not set · Unlock without password",
        "修改密码" to "Change password",
        "设置密码" to "Set password",
        "移除密码" to "Remove password",
        "已移除锁屏密码" to "Lock screen password removed",
        "输入密码（4-8 位数字）" to "Enter password (4-8 digits)",
        "确认密码" to "Confirm password",
        "密码至少 4 位数字" to "Password needs at least 4 digits",
        "两次输入的密码不一致" to "Passwords do not match",
        "锁屏密码已设置" to "Lock screen password set",
        "自动锁屏" to "Auto-lock",
        "桌面无操作达到设定时间后自动锁定" to "Lock automatically after the desktop is idle for the set time",
        "从不" to "Never",
        "1 分钟" to "1 minute",
        "5 分钟" to "5 minutes",
        "15 分钟" to "15 minutes",
        "30 分钟" to "30 minutes",
        "1 小时" to "1 hour",
        "立即锁定" to "Lock now",
        "模拟 Win + L，上滑或点击解锁（设置了密码则需验证）" to "Like Win + L. Swipe up or tap to unlock (password required if set)",
        "输入锁屏密码" to "Enter lock screen password",
        "忘记密码？点击此处清除密码并解锁" to "Forgot password? Tap here to clear it and unlock",
        "4-8 位数字密码" to "4-8 digit password",
        "取消" to "Cancel",

        // ===== v2.17 桌面设置 =====
        "桌面设置" to "Desktop settings",
        "账户、桌面图标、自启动、桌面行为" to "Account, icons, startup apps, desktop behavior",
        "本地账户 · 管理员权限" to "Local account · Administrator",
        "头像" to "Avatar",
        "用户名" to "Username",
        "已保存" to "Saved",
        "桌面图标" to "Desktop icons",
        "排序方式" to "Sort by",
        "按名称" to "By name",
        "按类型" to "By type",
        "重置图标顺序" to "Reset icon order",
        "清除拖动排序，恢复默认排列" to "Clear drag order and restore default layout",
        "已重置图标顺序" to "Icon order reset",
        "图标大小" to "Icon size",
        "桌面行为" to "Desktop behavior",
        "图标打开方式" to "Open icons with",
        "单击打开" to "Single click",
        "双击打开" to "Double click",
        "自启动应用" to "Startup apps",
        "桌面启动时不自动打开任何应用" to "No apps open automatically on startup",
        "已选 %s 个 · 桌面启动时自动打开" to "%s selected · opened automatically on startup",
        "选择桌面启动时自动打开的应用" to "Choose apps to open automatically on startup",

        // ===== v2.17 辅助功能 =====
        "视觉" to "Vision",
        "交互" to "Interaction",
        "听觉与反馈" to "Hearing & feedback",
        "字体大小" to "Font size",
        "UI 缩放" to "UI scale",
        "鼠标指针大小" to "Pointer size",
        "指针颜色" to "Pointer color",
        "经典白" to "Classic white",
        "经典黑" to "Classic black",
        "蓝色" to "Blue",
        "高对比绿" to "High-contrast green",
        "右键手势" to "Right-click gesture",
        "双指轻点" to "Two-finger tap",
        "长按" to "Long press",
        "虚拟键盘按键振动" to "Virtual keyboard vibration",
        "按键触摸反馈动画" to "Key touch feedback animation",

        // ===== v2.17 隐私和安全 =====
        "锁屏、浏览数据、应用权限、诊断" to "Lock screen, browsing data, app permissions, diagnostics",
        "锁屏与密码" to "Lock screen & password",
        "锁屏壁纸、锁屏密码、自动锁定定时" to "Lock wallpaper, password, auto-lock timer",
        "清除浏览历史" to "Clear browsing history",
        "删除浏览器的访问历史记录（书签保留）" to "Delete browser history (bookmarks are kept)",

        // ===== v2.18 视频桌面壁纸 / 触控板模式 =====
        "从文件资源管理器选择图片或视频" to "Pick an image or video from File Explorer",
        "视频壁纸" to "Video wallpaper",
        "选择视频作为动态桌面壁纸（静音循环，更耗电）" to "Use a video as live desktop wallpaper (muted loop, uses more battery)",
        "移动方式" to "Pointer movement",
        "触控" to "Touch",
        "触控板" to "Trackpad",
        "整个屏幕当作触控板：滑动移动指针，轻点 = 单击，快速连点两下 = 双击，按住不动后拖动 = 按住拖拽，双指轻点 = 右键，双指滑动 = 滚动页面" to
            "Use the whole screen as a trackpad: slide to move the pointer, tap = click, quick double tap = double-click, press & hold then drag = drag, two-finger tap = right-click, two-finger slide = scroll",
        "指针跟随手指移动（默认，触屏习惯）" to "Pointer follows your finger (default, touch habit)",
        "触控板模式已自动设为：双击打开 + 双指右键" to "Trackpad mode defaults applied: double-click to open + two-finger right-click",
        "双击桌面图标打开（经典桌面鼠标习惯；触控板模式默认此项）" to "Double-click desktop icons to open (classic desktop habit; trackpad default)",

        // ===== v2.19 首次启动存储权限引导 =====
        "存储权限" to "Storage permission",
        "LinBox 需要存储权限才能使用文件管理器、自定义壁纸、视频壁纸等功能，建议授予“访问所有文件”权限。" to
            "LinBox needs storage access for File Explorer, custom & video wallpapers and more. All-files access is recommended.",
        "去授权" to "Grant access",
        "稍后" to "Later"
    )
}

/** 当前应用语言（"zh-CN" / "en-US"），由 MainActivity 顶层提供 */
val LocalAppLanguage = staticCompositionLocalOf { "zh-CN" }

/** Composable 取词：en-US 时查字典，缺省回退中文原文 */
@Composable
fun L(text: String): String {
    val lang = LocalAppLanguage.current
    return if (lang == "en-US") L10n.en[text] ?: text else text
}
