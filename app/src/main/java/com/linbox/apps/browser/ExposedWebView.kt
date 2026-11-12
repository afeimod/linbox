package com.linbox.apps.browser

import android.content.Context
import android.webkit.WebView

/**
 * v2.19：WebView 滚动度量公开化。
 *
 * computeVerticalScrollRange / computeVerticalScrollExtent 是 View 的
 * protected 方法，浏览器左缘悬浮滚动条需要读取它们计算滑块尺寸/可滚动判定
 * —— 用公开子类包装一层即可（其余能力与 WebView 完全一致）。
 */
class ExposedWebView(context: Context) : WebView(context) {
    /** 页面总高度（px，含不可见部分） */
    fun verticalScrollRange(): Int = computeVerticalScrollRange()

    /** 当前视口高度（px） */
    fun verticalScrollExtent(): Int = computeVerticalScrollExtent()
}
