package com.linbox.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 沉浸式（隐藏 Android 状态栏 + 导航键）统一入口。
 *
 * v2.8 修复"全屏时上方还有状态栏和功能键"：
 * - 旧版只在 MainActivity.onCreate 里 hide 一次，焦点变化（弹输入法、
 *   WebView 交互、MIUI 系统行为）后系统栏会重新出现且不再隐藏。
 * - 现在每次窗口重新获得焦点时都会重新断言隐藏（onWindowFocusChanged），
 *   浏览器真全屏 / 视频全屏期间也调用，保证状态栏和功能键彻底不可见。
 */
object ImmersiveMode {

    /** 隐藏系统状态栏 + 导航栏（边缘滑动仅瞬时显示）。 */
    fun applyTo(window: Window) {
        // 内容绘制到系统栏下方（全面屏），配合隐藏系统栏实现真全屏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            // 滑动边缘可瞬时呼出系统栏，松手后自动隐藏，不会常驻
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** 便捷重载：从任意 Context 找到宿主 Activity 后应用沉浸式。 */
    fun applyTo(context: Context) {
        findActivity(context)?.let { applyTo(it.window) }
    }

    /**
     * 从（可能是 ContextThemeWrapper 等包装的）Context 中找到宿主 Activity。
     * WebView 的 context / onShowCustomView 传入的 view.context 通常是包装过的。
     */
    fun findActivity(context: Context): Activity? {
        var ctx: Context = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
