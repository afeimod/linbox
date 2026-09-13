package com.linbox

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import com.linbox.core.input.TrackpadRouter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import com.linbox.core.theme.ThemeOverlay
import com.linbox.core.theme.WinThemeScope
import com.linbox.core.shell.LinBoxShell
import com.linbox.data.prefs.SettingsStore
import com.linbox.util.ImmersiveMode
import com.linbox.util.L10n
import com.linbox.util.LocalAppLanguage

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全面屏沉浸式：内容绘制到状态栏/导航栏下方，并隐藏系统栏
        ImmersiveMode.applyTo(window)
        // 默认占用刘海屏：内容延伸绘制到刘海/挖孔区域（API 28+）
        applyCutoutMode(true)

        val app = LinBoxApp.get(this)
        setContent {
            // ===== v2.29 首次启动存储权限申请 =====
            // 原版本从不主动申请（且 Manifest 带了 maxSdkVersion 上限，
            // Android 10+ 上权限根本不存在）——终端读写 /sdcard、导入
            // rootfs、下载文件等全部失败。此处启动即申请；被拒且为
            // Android 11+ 时引导到系统「所有文件访问」页（终极兜底）。
            var showAllFilesDialog by remember { mutableStateOf(false) }
            val storageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val ok = grants[android.Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
                    grants[android.Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
                if (!ok && Build.VERSION.SDK_INT >= 30) showAllFilesDialog = true
            }
            LaunchedEffect(Unit) {
                val need = buildList {
                    if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED
                    ) add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED
                    ) add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                if (need.isNotEmpty()) storageLauncher.launch(need.toTypedArray())
            }
            if (showAllFilesDialog) {
                AlertDialog(
                    onDismissRequest = { showAllFilesDialog = false },
                    title = { Text("需要存储权限") },
                    text = {
                        Text(
                            "终端无法读取 /sdcard：下载、导入 rootfs、访问下载目录等功能都会失败。" +
                                "请授予 LinBox「所有文件访问」权限后重试。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showAllFilesDialog = false
                            try {
                                startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:$packageName")
                                    )
                                )
                            } catch (_: Exception) {
                                try {
                                    startActivity(
                                        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        }) { Text("去授权") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAllFilesDialog = false }) { Text("暂不") }
                    }
                )
            }

            val baseTheme by app.themeManager.activeTheme.collectAsState(
                initial = com.linbox.core.theme.Themes.Win11
            )
            val settingsStore = remember { app.settingsStore }
            // 全局 UI 缩放（乘到密度上，同时缩放所有 dp/sp）
            // v2.16：UI_SCALE_BASE = 0.6，100% 档位现在以旧版 60% 的效果渲染，
            // 整体更紧凑；设置里仍显示 100%，各档位等比映射
            val uiScale by settingsStore.uiScale.collectAsState(initial = 1f)
            val orientation by settingsStore.displayOrientation.collectAsState(initial = "auto")
            // 刘海屏占用开关（显示设置）
            val useCutout by settingsStore.useCutout.collectAsState(initial = true)

            // ===== 个性化：颜色 / 字体 =====
            // 颜色模式：强制所有 Windows 主题深色或浅色
            val colorMode by settingsStore.appColorMode.collectAsState(initial = "auto")
            // 强调色覆盖
            val accent by settingsStore.appAccent.collectAsState(initial = "default")
            // 字体：缩放（乘到系统 fontScale，全局生效）/ 颜色 / 样式
            val fontScale by settingsStore.fontScale.collectAsState(initial = 1f)
            val fontColor by settingsStore.fontColor.collectAsState(initial = "auto")
            val fontStyle by settingsStore.fontStyle.collectAsState(initial = "default")
            // 显示语言（设置→时间和语言→显示语言）
            val language by settingsStore.language.collectAsState(initial = "zh-CN")

            // 同步全局语言状态（Toast 等非组合环境取词用）
            SideEffect { L10n.current = language }

            // 主题链：基础主题 → 深浅模式覆盖 → 强调色覆盖 → 字体颜色覆盖
            val theme = remember(baseTheme, colorMode, accent, fontColor) {
                baseTheme
                    .let { ThemeOverlay.apply(it, colorMode) }
                    .let { ThemeOverlay.applyAccent(it, accent) }
                    .let { ThemeOverlay.applyFontColor(it, fontColor) }
            }

            // 刘海屏模式切换：SHORT_EDGES = 内容延伸到刘海区；DEFAULT = 不占用刘海区
            LaunchedEffect(useCutout) { applyCutoutMode(useCutout) }

            // 应用显示方向设置：
            // - portrait / landscape: 锁定方向
            // - auto: FULL_SENSOR，跟随重力传感器旋转，不受系统旋转锁定影响
            LaunchedEffect(orientation) {
                requestedOrientation = when (orientation) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
            }

            // 通过覆盖 LocalDensity 实现全局 UI 缩放 + 字体大小缩放（v2.14）
            // v2.16：密度额外乘 SettingsStore.UI_SCALE_BASE（0.6），
            // 100% 档位的实际渲染效果 = 旧版 60%，视觉整体缩小
            val baseDensity = LocalDensity.current
            val baseTextStyle = androidx.compose.material3.LocalTextStyle.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density * uiScale * SettingsStore.UI_SCALE_BASE,
                    fontScale = baseDensity.fontScale * fontScale
                ),
                // v2.14：全局字体样式（衬线/等宽）—— 未显式指定样式的 Text 全部跟随；
                // default 模式下 fontFamily 保持原样（零影响）
                androidx.compose.material3.LocalTextStyle provides baseTextStyle.copy(
                    fontFamily = when (fontStyle) {
                        "serif" -> FontFamily.Serif
                        "mono" -> FontFamily.Monospace
                        else -> baseTextStyle.fontFamily
                    }
                ),
                // 显示语言
                LocalAppLanguage provides language
            ) {
                WinThemeScope(theme = theme) {
                    LinBoxShell()
                }
            }
        }
    }

    /**
     * v2.20 触控板模式事件仲裁入口。
     *
     * trackpad 模式下真实手指事件先经 [TrackpadRouter] 处理：命中则直接
     * 返回 true（事件不再进入 View/Compose 管线，手势机输出指针移动与
     * 合成点击/拖拽/滚轮注入）；注入流（id ≥ 99）与虚拟键盘/手柄等
     * 直通区事件返回 false，照常走 super 分发。
     *
     * 这是“触控板用一段时间后随机失灵、恢复普通触摸、鼠标冻结”的根修：
     * 真实手指（TOOL_TYPE_FINGER）与注入流（TOOL_TYPE_MOUSE）从此不再
     * 交织进同一个 AndroidComposeView 管线，Compose 的设备切换取消
     * （processCancel）与指针 id 重映射风暴不再发生。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (TrackpadRouter.onMotionEvent(this, ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 沉浸式防失效（v2.8 修复“全屏时上方还有状态栏和功能键”）：
     * MIUI / 部分系统在窗口焦点变化（弹输入法、视频全屏切换等）后会重新显示系统栏，
     * 每次重新获得焦点时重新断言隐藏，保证状态栏 + 功能键（导航键）不再常驻。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ImmersiveMode.applyTo(window)
        }
    }

    /**
     * 刘海屏绘制模式（v2.9）：
     * - useCutout = true  → SHORT_EDGES：内容延伸绘制到刘海/挖孔区域（默认，占用刘海屏）
     * - useCutout = false → DEFAULT：刘海区不绘制内容，状态栏区域留黑
     * 仅 API 28+ 生效；旧设备无刘海屏概念，调用无副作用。
     */
    private fun applyCutoutMode(useCutout: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val mode = if (useCutout) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = mode
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}