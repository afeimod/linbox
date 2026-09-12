package com.linbox

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import com.linbox.core.input.TrackpadRouter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linbox.core.theme.ThemeOverlay
import com.linbox.core.theme.WinThemeScope
import com.linbox.core.desktop.DesktopEnvironment
import com.linbox.data.prefs.SettingsStore
import com.linbox.util.ImmersiveMode
import com.linbox.util.L
import com.linbox.util.L10n
import com.linbox.util.LocalAppLanguage
import com.linbox.util.StorageAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全面屏沉浸式：内容绘制到状态栏/导航栏下方，并隐藏系统栏
        ImmersiveMode.applyTo(window)
        // 默认占用刘海屏：内容延伸绘制到刘海/挖孔区域（API 28+）
        applyCutoutMode(true)

        val app = LinBoxApp.get(this)
        setContent {
            val baseTheme by app.themeManager.activeTheme.collectAsState(
                initial = com.linbox.core.theme.Themes.Win11
            )
            val settingsStore = remember { app.settingsStore }
            val customWallpaper by settingsStore.customWallpaper.collectAsState(initial = null)
            val soundEnabled by settingsStore.soundEnabled.collectAsState(initial = true)
            // 全局 UI 缩放（乘到密度上，同时缩放所有 dp/sp）
            // v2.16：UI_SCALE_BASE = 0.6，100% 档位现在以旧版 60% 的效果渲染，
            // 整体桌面更紧凑；设置里仍显示 100%，各档位等比映射
            val uiScale by settingsStore.uiScale.collectAsState(initial = 1f)
            val orientation by settingsStore.displayOrientation.collectAsState(initial = "auto")
            // 刘海屏占用开关（个性化设置）
            val useCutout by settingsStore.useCutout.collectAsState(initial = true)

            // ===== v2.14 个性化：颜色 / 字体 =====
            // 颜色模式：强制所有 Windows 主题深色或浅色
            val colorMode by settingsStore.appColorMode.collectAsState(initial = "auto")
            // 强调色覆盖
            val accent by settingsStore.appAccent.collectAsState(initial = "default")
            // 字体：缩放（乘到系统 fontScale，全局生效）/ 颜色 / 样式
            val fontScale by settingsStore.fontScale.collectAsState(initial = 1f)
            val fontColor by settingsStore.fontColor.collectAsState(initial = "auto")
            val fontStyle by settingsStore.fontStyle.collectAsState(initial = "default")
            // 显示语言（v2.14：设置→时间和语言→显示语言）
            val language by settingsStore.language.collectAsState(initial = "zh-CN")

            // 同步全局语言状态（Toast / 锁屏等非组合环境取词用）
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
                // v2.14：显示语言
                LocalAppLanguage provides language
            ) {
                WinThemeScope(theme = theme) {
                    DesktopEnvironment(
                        theme = theme,
                        customWallpaperUri = customWallpaper,
                        soundEnabled = soundEnabled
                    )

                    // ===== v2.19 首次启动存储权限引导 =====
                    // 文件管理器/自定义壁纸/视频壁纸都依赖真实存储访问；
                    // 首启弹一次说明对话框，拒绝后不再骚扰（可在系统设置里随时补授）
                    val scope = rememberCoroutineScope()
                    val storageAsked by settingsStore.storagePermAsked.collectAsState(initial = false)
                    var storageGranted by remember {
                        mutableStateOf(StorageAccess.hasAccess(this@MainActivity))
                    }
                    var showStorageDialog by remember { mutableStateOf(false) }

                    // Android 10 及以下：运行时权限对话框；11+：跳系统设置页
                    val permLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        storageGranted = StorageAccess.hasAccess(this@MainActivity)
                        if (storageGranted) {
                            scope.launch { settingsStore.setStoragePermAsked() }
                        }
                    }

                    // 从系统设置页返回（ON_RESUME）后重新判定授权状态
                    DisposableEffectSafely(
                        onRecheck = {
                            storageGranted = StorageAccess.hasAccess(this@MainActivity)
                        }
                    )
                    LaunchedEffect(storageAsked) {
                        if (!storageAsked) {
                            delay(600) // 等桌面首帧稳定后再弹，避免启动竞态
                            if (!StorageAccess.hasAccess(this@MainActivity)) {
                                showStorageDialog = true
                            }
                        }
                    }
                    LaunchedEffect(storageGranted) {
                        if (storageGranted) showStorageDialog = false
                    }

                    if (showStorageDialog && !storageGranted) {
                        AlertDialog(
                            onDismissRequest = {
                                showStorageDialog = false
                                scope.launch { settingsStore.setStoragePermAsked() }
                            },
                            title = { Text(L("存储权限")) },
                            text = {
                                Column {
                                    Text(
                                        L(
                                            "LinBox 需要存储权限才能使用文件管理器、自定义壁纸、视频壁纸等功能，建议授予“访问所有文件”权限。"
                                        )
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showStorageDialog = false
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        val intent = runCatching {
                                            StorageAccess.manageIntent(this@MainActivity)
                                        }.getOrElse { StorageAccess.manageFallbackIntent() }
                                        runCatching { startActivity(intent) }
                                    } else {
                                        permLauncher.launch(StorageAccess.legacyPermissions())
                                    }
                                }) { Text(L("去授权")) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showStorageDialog = false
                                    scope.launch { settingsStore.setStoragePermAsked() }
                                }) { Text(L("稍后")) }
                            }
                        )
                    }
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
     * v2.19：从系统设置页（所有文件访问）返回后重新判定授权状态。
     * 单独的小组合函数：在 setContent 内声明生命周期观察者用。
     */
    @androidx.compose.runtime.Composable
    private fun DisposableEffectSafely(onRecheck: () -> Unit) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    onRecheck()
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
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
        // 清理所有打开的窗口
        com.linbox.core.window.WindowManager.get().closeAll()
        // v2.21.2：应用真正退出（返回键退出/finish）时同步关闭桌面歌词悬浮窗。
        // 任务被划掉的场景由 LyricOverlayService 的 stopWithTask 兜底，此处是双保险。
        if (isFinishing) {
            com.linbox.apps.music.stopDesktopLyricService(applicationContext)
        }
    }
}