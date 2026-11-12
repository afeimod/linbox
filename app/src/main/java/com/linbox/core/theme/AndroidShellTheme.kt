package com.linbox.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * v5.0（安卓化重制）：统一壳层主题 + 基础密度工具。
 *
 * v6.1（用户："主页亮色但其他地方全是暗色，不统一；设置里加入主题设置
 * 并支持自定义背景，亮色全亮色，暗色全暗色，并根据暗色自动整理字体颜色
 * 保证清晰"）：
 * - [ShellThemeState]：壳层主题全局状态（亮/暗 + 自定义背景路径），
 *   启动时由 SettingsStore 灌入（LinBoxShell），设置页实时改写；
 * - [LinShellTheme] 从静态 object 改为 **动态取色**（getter 读
 *   ShellThemeState 的 Compose 快照状态）—— 全部既有调用点
 *   （设置 / 高级 / 容器管理 / 弹窗 / 窗口标题栏）零改动即自动换肤，
 *   状态读取发生在组合期 → 模式切换即时重组生效；
 * - [LinBoxMaterialTheme]：按模式包一层 Material3 colorScheme，
 *   AlertDialog / OutlinedTextField / DropdownMenu 等 M3 组件的
 *   文字与容器色自动适配（暗色白字 / 亮色黑字，清晰可读）。
 *
 * ## 基础密度工具（v5.0 原有）
 * - [LocalBaseDensity]：MainActivity 在应用 uiScale 的
 *   CompositionLocalProvider 内提供（值 = 未乘 uiScale 的系统密度）；
 * - [KeepBaseDensity]：悬浮球层 / 手柄覆盖层包裹自身 —— 内部所有
 *   dp/sp 恢复系统基础密度（球与手柄元素尺寸恒定，用户可在手柄
 *   设置里单独调节元素大小）。
 */
val LocalBaseDensity = staticCompositionLocalOf<Density?> { null }

/**
 * 壳层主题全局状态（v6.1）。
 *
 * - [dark]：true = 暗色（全壳层暗色玻璃），false = 亮色（全壳层浅色玻璃）；
 * - [customBgPath]：自定义背景图绝对路径（空串 = 未启用）。启用时各页面
 *   的背景色/渐变自动降为半透明蒙版，背景图透出 —— 亮色蒙白、暗色蒙深，
 *   文字对比度由对应色板的字色保证；
 * - [bgVersion]：背景图内容版本号（每次更换 +1，驱动重新解码）。
 */
object ShellThemeState {
    var dark by mutableStateOf(true)
    var customBgPath by mutableStateOf("")
    var bgVersion by mutableIntStateOf(0)
}

/**
 * v5.0 统一壳层色板（安卓暗色玻璃风）→ **v6.1 动态化**。
 *
 * 全部取值随 [ShellThemeState] 实时切换：
 * - 暗色 = 原 v5.0 深夜蓝玻璃；亮色 = 与主页 GlassPalette.Light 同源
 *   的浅色玻璃（柔和蓝白 + 深藏青文字）；
 * - 自定义背景启用时，bg / bgDeep 降为高不透明度蒙版（≈90%），
 *   既透出背景图又保证文字可读。
 *
 * 既有调用点（`LinShellTheme.bg` 等）签名不变，自动获得换肤能力。
 */
object LinShellTheme {

    private val darkBg = Color(0xFF0F1424)
    private val darkBgDeep = Color(0xFF0A0E1B)
    private val lightBg = Color(0xFFF2F5FC)
    private val lightBgDeep = Color(0xFFE4EBFA)

    /** 页面背景（纯色；自定义背景启用时为深/浅蒙版）。 */
    val bg: Color
        get() = when {
            ShellThemeState.customBgPath.isNotBlank() ->
                if (ShellThemeState.dark) Color(0xE60F1424) else Color(0xE6F2F5FC)
            ShellThemeState.dark -> darkBg
            else -> lightBg
        }

    /** 页面背景渐变底端。 */
    val bgDeep: Color
        get() = when {
            ShellThemeState.customBgPath.isNotBlank() ->
                if (ShellThemeState.dark) Color(0xE60A0E1B) else Color(0xE6E4EBFA)
            ShellThemeState.dark -> darkBgDeep
            else -> lightBgDeep
        }

    /** 玻璃卡底（半透明）。 */
    val card: Color
        get() = if (ShellThemeState.dark) Color(0x0FFFFFFF) else Color(0xB8FFFFFF)

    /** 玻璃卡描边。 */
    val cardBorder: Color
        get() = if (ShellThemeState.dark) Color(0x1FFFFFFF) else Color(0x331C2340)

    /** 高一级 surface（弹窗 / 窗口底，接近不透明）。 */
    val surface: Color
        get() = if (ShellThemeState.dark) Color(0xFF161D33) else Color(0xFFF7F9FE)

    /** surface 描边。 */
    val surfaceBorder: Color
        get() = if (ShellThemeState.dark) Color(0x24FFFFFF) else Color(0x241C2340)

    /** 主文本（暗色 = 白 / 亮色 = 深藏青）。 */
    val textPrimary: Color
        get() = if (ShellThemeState.dark) Color.White else Color(0xFF1C2340)

    /** 次级文本。 */
    val textSecondary: Color
        get() = if (ShellThemeState.dark) Color(0x99FFFFFF) else Color(0xFF5A6480)

    /** 品牌蓝（按钮 / 选中态 / 强调）。 */
    val blue = Color(0xFF4E7CFF)

    /** 品牌紫（渐变右端）。 */
    val purple = Color(0xFF8A5CFF)

    /** 成功绿。 */
    val green = Color(0xFF34C759)

    /** 危险红（删除类按钮）。 */
    val red = Color(0xFFFF5A5A)

    /** 遮罩（弹窗 / 菜单背后的暗化层）。 */
    val scrim: Color
        get() = if (ShellThemeState.dark) Color(0x99000000) else Color(0x59000000)
}

/**
 * 壳层页面的 Material3 主题（v6.1）。
 *
 * 包住全屏页内容（LinBoxShell 的 when(screen) 块）：AlertDialog /
 * OutlinedTextField / ExposedDropdownMenu / DropdownMenu 等 M3 组件
 * 的默认容器色与文字色全部随亮暗模式切换 —— "亮色全亮色，暗色全暗色，
 * 字体颜色自动适配清晰"。
 */
@Composable
fun LinBoxMaterialTheme(content: @Composable () -> Unit) {
    val scheme = if (ShellThemeState.dark) {
        darkColorScheme(
            primary = LinShellTheme.blue,
            onPrimary = Color.White,
            secondary = LinShellTheme.purple,
            background = LinShellTheme.bg,
            onBackground = LinShellTheme.textPrimary,
            surface = LinShellTheme.surface,
            onSurface = LinShellTheme.textPrimary,
            onSurfaceVariant = LinShellTheme.textSecondary,
            outline = LinShellTheme.textSecondary,
            scrim = LinShellTheme.scrim
        )
    } else {
        lightColorScheme(
            primary = LinShellTheme.blue,
            onPrimary = Color.White,
            secondary = LinShellTheme.purple,
            background = LinShellTheme.bg,
            onBackground = LinShellTheme.textPrimary,
            surface = LinShellTheme.surface,
            onSurface = LinShellTheme.textPrimary,
            onSurfaceVariant = LinShellTheme.textSecondary,
            outline = Color(0xFF8A93AC),
            scrim = LinShellTheme.scrim
        )
    }
    MaterialTheme(colorScheme = scheme) { content() }
}

/**
 * 以系统基础密度渲染内容（悬浮球 / 手柄元素等"自带尺寸语义"的层）。
 *
 * 必须在 MainActivity 的 uiScale Provider **内部**使用才能拿到基础密度；
 * 拿不到（预览 / 测试环境）时原样渲染，行为退化无副作用。
 */
@Composable
fun KeepBaseDensity(content: @Composable () -> Unit) {
    val base = LocalBaseDensity.current
    if (base != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides base) {
            content()
        }
    } else {
        content()
    }
}
