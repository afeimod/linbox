package com.linbox.apps.x11

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.linbox.core.window.AppDef
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope

/**
 * 内置 X11 图形（显示号 :13，主屏启动器/终端命令入口）。
 *
 * 画面直接渲染在浮动窗口内（不默认拉起独立全屏 Activity）：
 * - 窗口可自由拖拽/8 向缩放/最大化，控制条一键真全屏（返回键退出）；
 * - 分辨率"跟随窗口"（native）或"固定分辨率"（exact）随时切换；
 * - X11 自带键盘栏默认关闭，控制条"键盘"按钮唤起系统输入法；
 * - 终端 `linbox-x11 :13` 启动服务后本窗口自动弹出并连接
 *   （见 X11WindowController：广播 → 开窗 → 取 fd → LorieView 渲染）；
 * - 等待连接页提供"兼容全屏模式"按钮，可拉起独立全屏 Activity
 *   （com.termux.x11.MainActivity，保留作排障兜底）。
 */
val X11App = AppDef(
    id = "x11",
    displayName = "X11 图形",
    iconAsset = "icons/x11.png",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 640.dp,
    defaultHeight = 480.dp,
    // v2.22.4 fix11c：长按标题栏打开 X11 设置面板（分辨率/拉伸/剪贴板/握手重放）
    onTitleBarLongPress = { _ -> X11SettingsBridge.requestShow() }
) { scope ->
    X11Surface(scope)
}
