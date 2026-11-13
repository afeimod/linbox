package com.linbox.apps.x11

/**
 * X11MiniWindow —— v6.6 起退役。
 *
 * 小窗口功能自 v6.5 起由 **X11FloatWindow / X11DisplayLayer**（X11Surface.kt，
 * 挂在 LinBoxShell 的全局显示层）统一实现：窗口大小按会话分辨率自适应、
 * 标题栏拖动 + 边缘八向缩放、快捷配置条、一键全屏恢复、窗口外触摸穿透
 * （底层界面照常操作）、会话期间悬浮球 X11 组常驻。
 *
 * 早期 v6.4 的 X11MiniWindow（decorView 迷你浮窗方案）基于旧源码基线，
 * 与 v6.5 显示层架构冲突，已整体移除。本文件保留为空壳仅为兼容可能残留
 * 的旧引用 —— 可直接删除本文件。
 */
@Deprecated(
    "小窗口已由 X11FloatWindow / X11DisplayLayer（v6.5+）统一实现",
    level = DeprecationLevel.WARNING
)
object X11MiniWindow {

    /** 兼容旧引用：恒为 false（小窗口由 X11FloatWindow.enabled 表达）。 */
    val isActive: Boolean
        get() = X11FloatWindow.enabled

    /** 兼容旧引用：是否处于小窗模式（委托 X11FloatWindow）。 */
    val active: Boolean
        get() = X11FloatWindow.enabled

    /** 兼容旧引用：开启小窗（委托 X11FloatWindow.setEnabled）。 */
    fun enable() {
        X11FloatWindow.setEnabled(com.linbox.LinBoxApp.get(), true)
    }

    /** 兼容旧引用：恢复全屏（回到 X11 全屏页）。 */
    fun restore() {
        com.linbox.core.shell.ShellController.showX11()
    }

    /** 兼容旧引用：关闭会话（与浮窗 ✕ 同语义）。 */
    fun close() {
        X11WindowController.closeSession()
    }
}
