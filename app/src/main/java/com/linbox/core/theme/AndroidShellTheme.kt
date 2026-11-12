package com.linbox.core.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * v5.0（安卓化重制）：统一壳层主题 + 基础密度工具。
 *
 * ## 背景（用户需求）
 * 1. "所有界面主题不统一" —— 设置页 / 高级页 / 容器管理器 / 弹窗 /
 *    浮动窗口此前各自为政（WinTheme 亮色 / Qt 灰白 / 深色玻璃混用），
 *    统一为与游戏库一致的 **暗色玻璃安卓风**；
 * 2. "放大到 150" —— 全局 UI 缩放基准已由 0.6 提到 0.9
 *    （SettingsStore.UI_SCALE_BASE），所有档位等比放大；
 * 3. "悬浮球不要更改大小 / 手柄按键 UI 不要随整体缩放" —— 悬浮球与
 *    手柄元素有独立尺寸语义（54dp 球 / 元素自带 sizeDp），必须以
 *    **未缩放的系统基础密度** 渲染，不受全局 uiScale 影响。
 *
 * ## 用法
 * - [LocalBaseDensity]：MainActivity 在应用 uiScale 的
 *   CompositionLocalProvider 内提供（值 = 未乘 uiScale 的系统密度）；
 * - [KeepBaseDensity]：悬浮球层 / 手柄覆盖层包裹自身 —— 内部所有
 *   dp/sp 恢复系统基础密度（球与手柄元素尺寸恒定，用户可在手柄
 *   设置里单独调节元素大小）。
 */
val LocalBaseDensity = staticCompositionLocalOf<Density?> { null }

/**
 * v5.0 统一壳层色板（安卓暗色玻璃风，与游戏库 GlassPalette.Dark 同源）。
 *
 * 设置页 / 高级页 / 容器管理页 / WinDialog 弹窗 / 浮动窗口标题栏全部
 * 引用本对象 —— 一处调色全局换肤，杜绝"主题不统一"。
 */
object LinShellTheme {
    /** 页面背景（纯色，深夜蓝黑）。 */
    val bg = androidx.compose.ui.graphics.Color(0xFF0F1424)
    /** 页面背景渐变底端。 */
    val bgDeep = androidx.compose.ui.graphics.Color(0xFF0A0E1B)
    /** 玻璃卡底（半透明白）。 */
    val card = androidx.compose.ui.graphics.Color(0x0FFFFFFF)
    /** 玻璃卡描边。 */
    val cardBorder = androidx.compose.ui.graphics.Color(0x1FFFFFFF)
    /** 高一级 surface（弹窗 / 窗口底，接近不透明）。 */
    val surface = androidx.compose.ui.graphics.Color(0xFF161D33)
    /** surface 描边。 */
    val surfaceBorder = androidx.compose.ui.graphics.Color(0x24FFFFFF)
    /** 主文本。 */
    val textPrimary = androidx.compose.ui.graphics.Color.White
    /** 次级文本。 */
    val textSecondary = androidx.compose.ui.graphics.Color(0x99FFFFFF)
    /** 品牌蓝（按钮 / 选中态 / 强调）。 */
    val blue = androidx.compose.ui.graphics.Color(0xFF4E7CFF)
    /** 品牌紫（渐变右端）。 */
    val purple = androidx.compose.ui.graphics.Color(0xFF8A5CFF)
    /** 成功绿。 */
    val green = androidx.compose.ui.graphics.Color(0xFF34C759)
    /** 危险红（删除类按钮）。 */
    val red = androidx.compose.ui.graphics.Color(0xFFFF5A5A)
    /** 遮罩（弹窗 / 菜单背后的暗化层）。 */
    val scrim = androidx.compose.ui.graphics.Color(0x99000000)
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
        CompositionLocalProvider(LocalDensity provides base) {
            content()
        }
    } else {
        content()
    }
}
