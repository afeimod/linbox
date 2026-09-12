package com.linbox.apps.x11

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.linbox.core.theme.LocalWinTheme
import com.termux.x11.LoriePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v2.22.4 fix11c：X11 设置面板。
 *
 * 用户需求："x11 长按状态栏的设置等" —— 两个入口：
 * 1. X11 界面控制条"X11设置"按钮（X11Screen.ControlBar）；
 * 2. 常驻通知（X11KeepAliveService）的 "X11 设置" 动作 —— 顺带把 App
 *    拉回前台并跳转 X11 界面。
 *
 * 面板集中了 X11 显示端真正生效的关键偏好：
 * - 分辨率模式（跟随窗口 / 固定分辨率）与常用预设/自定义分辨率；
 * - 拉伸铺满（exact 模式下 LorieView 铺满窗口，杜绝信箱黑边）；
 * - 剪贴板双向同步；
 * - "重新应用游戏分辨率" —— glibc-runner -d 写入的握手文件重放
 *   （错过后不必重跑游戏启动命令）。
 *
 * v2.22.5 fix12：横屏适配重做 —— 用户反馈"菜单要小点然后加滑动，
 * 不然横屏显示不全"。整卡收窄（最大 330dp）+ 内容可滚动 +
 * 预设分辨率 FlowRow 自动换行（旧版 Row 会把 1920x1080 裁出屏幕外），
 * 字号/间距全面收紧，横屏下不再溢出。
 */
object X11SettingsBridge {
    private val _show = MutableStateFlow(false)

    /** 面板显示标志（粘性：通知入口先于窗口打开时依然生效）。 */
    val show: StateFlow<Boolean> = _show

    fun requestShow() { _show.value = true }
    fun dismiss() { _show.value = false }
}

private val RES_PRESETS = listOf("640x480", "800x600", "1280x720", "1600x900", "1920x1080")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun X11SettingsDialog() {
    val show by X11SettingsBridge.show.collectAsState()
    if (!show) return

    val theme = LocalWinTheme.current
    val prefs = remember { LoriePreferences.prefs } ?: run {
        X11SettingsBridge.dismiss()
        return
    }
    val resState by X11ResolutionLink.state.collectAsState()

    var modeNative by remember(resState.mode) { mutableStateOf(resState.mode != "exact") }
    var resText by remember(resState.mode) {
        mutableStateOf(
            if (resState.mode == "exact" && resState.exact.isNotEmpty()) resState.exact
            else prefs.displayResolutionExact.get()
        )
    }
    var stretch by remember(resState.mode) { mutableStateOf(prefs.displayStretch.get()) }
    var clipboard by remember { mutableStateOf(prefs.clipboardEnable.get()) }
    var resError by remember { mutableStateOf(false) }

    val labelColor = theme.windowTitleBarTextColor
    val accent = theme.accentColor
    val dividerColor = if (theme.isDark) Color(0xFF3A4450) else Color(0xFFDDDDDD)
    val hintColor = Color(0xFF8A97A3)

    AlertDialog(
        onDismissRequest = { X11SettingsBridge.dismiss() },
        // fix12：收窄对话框（旧版按平台默认宽度在横屏上铺得太大，
        // 右侧预设按钮被屏幕边缘裁掉）。
        modifier = Modifier.widthIn(max = 330.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        confirmButton = {
            TextButton(onClick = { X11SettingsBridge.dismiss() }) {
                Text("完成", fontSize = 12.sp)
            }
        },
        title = {
            Text("X11 设置", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = if (theme.isDark) Color.White else Color.Black)
        },
        text = {
            // fix12：内容可滚动 —— 横屏高度不足时上下滑动，不再被截断。
            Column(
                modifier = Modifier
                    .widthIn(max = 306.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {

                // ---- 状态行 ----
                Text(
                    text = if (resState.mode == "exact")
                        "当前 X 屏幕：${resState.exact}（" + if (resState.fromGame) "游戏握手）" else "手动）"
                    else "当前 X 屏幕：跟随窗口（随 X11 窗口尺寸变化）",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (resState.fromGame) accent else labelColor
                )

                HorizontalDivider(color = dividerColor)

                // ---- 分辨率模式 ----
                Text("分辨率模式", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = labelColor)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            modeNative = true
                            X11ResolutionLink.setNative()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (modeNative) accent else labelColor
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("跟随窗口", fontSize = 11.sp) }

                    TextButton(
                        onClick = { modeNative = false },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (!modeNative) accent else labelColor
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("固定分辨率", fontSize = 11.sp) }
                }

                if (!modeNative) {
                    // ---- 预设分辨率（fix12：FlowRow 自动换行，不再横向裁切） ----
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        RES_PRESETS.forEach { preset ->
                            TextButton(
                                onClick = { resText = preset; resError = false },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (resText == preset) accent else labelColor
                                ),
                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                            ) { Text(preset, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = resText,
                            onValueChange = { resText = it; resError = false },
                            isError = resError,
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (theme.isDark) Color.White else Color.Black
                            ),
                            modifier = Modifier.width(108.dp).height(46.dp),
                            placeholder = { Text("1280x720", fontSize = 9.sp) }
                        )
                        TextButton(
                            onClick = {
                                val m = Regex("(\\d{2,5})x(\\d{2,5})").find(resText.trim())
                                val (w, h) = m?.destructured ?: run { resError = true; return@TextButton }
                                if (w.toInt() < 160 || h.toInt() < 120) { resError = true; return@TextButton }
                                X11ResolutionLink.setExact(w.toInt(), h.toInt())
                                resError = false
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("应用", fontSize = 11.sp) }
                    }
                }

                HorizontalDivider(color = dividerColor)

                // ---- 拉伸铺满 ----
                SettingSwitch(
                    title = "拉伸铺满（无黑边）",
                    desc = "固定分辨率时画面拉伸至整个 X11 窗口（宽高比不同时允许轻微变形）",
                    checked = stretch,
                    enabled = !modeNative,
                    onChecked = {
                        stretch = it
                        prefs.displayStretch.put(it)
                        X11ResolutionLink.pokeActiveView()
                    },
                    labelColor = labelColor, accent = accent, dark = theme.isDark,
                    hintColor = hintColor
                )

                // ---- 剪贴板同步 ----
                SettingSwitch(
                    title = "剪贴板双向同步",
                    desc = "Android 与 X11 应用共享剪贴板（复制粘贴互通）",
                    checked = clipboard,
                    enabled = true,
                    onChecked = {
                        clipboard = it
                        prefs.clipboardEnable.put(it)
                        X11ResolutionLink.pokeActiveView()
                    },
                    labelColor = labelColor, accent = accent, dark = theme.isDark,
                    hintColor = hintColor
                )

                HorizontalDivider(color = dividerColor)

                // ---- 重放游戏分辨率握手 ----
                TextButton(
                    onClick = { X11ResolutionLink.applyFromFile() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("重新应用游戏分辨率（glibc-runner -d）", fontSize = 11.sp, color = accent)
                }
                // ---- v2.22.5 fix14：游戏全屏（Alt+Enter）----
                TextButton(
                    onClick = { com.termux.x11.X11InputHub.sendAltEnter() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("游戏全屏（Alt+Enter，窗口游戏切全屏铺满）", fontSize = 11.sp, color = accent)
                }
                Text(
                    text = "提示：-d1280x720 game.exe 会把 X 屏幕设为 1280x720；窗口化游戏点「游戏全屏」（Alt+Enter）切全屏后，X 屏幕自动跟随游戏分辨率，画面铺满无黑边；" +
                          "无 -d 时 X 屏幕跟随窗口。会话常驻：通知栏 \"X11 运行中\" 防止后台被杀。",
                    fontSize = 9.sp,
                    color = hintColor,
                    lineHeight = 13.sp
                )
            }
        }
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
    labelColor: Color,
    accent: Color,
    dark: Boolean,
    hintColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (enabled) labelColor else labelColor.copy(alpha = 0.5f))
            Text(desc, fontSize = 9.sp, color = hintColor, lineHeight = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = accent),
            modifier = Modifier.height(22.dp)
        )
    }
}
