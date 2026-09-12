package com.linbox.apps.x11

import android.os.Build
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.termux.x11.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v2.22.4 fix11c：X11 设置面板。
 *
 * v2.25 重做 —— 用户需求："悬浮球也要显示在 x11 界面，x11 的设置也放进
 * 悬浮球，不要在 x11 底边了，并完善 x11 应该有的所有设置（参考
 * termux-x11 源码实现）"。面板入口从底边控制条改为 X11 悬浮球菜单
 * （X11Overlay.X11GlassFab），条目对齐 termux-x11 的 LoriePreferences /
 * Prefs.java 全量偏好中"嵌入桌面路径真正生效"的子集：
 *
 * - 【显示】分辨率模式（跟随窗口 native / 缩放 scaled / 固定 exact）、
 *   缩放比例（displayScale，LorieView.getDimensionsFromSettings 消费）、
 *   固定分辨率预设/自定义、拉伸铺满（displayStretch）；
 * - 【窗口】全屏（fullscreen）、保持亮屏（keepScreenOn → FLAG_KEEP_SCREEN_ON）、
 *   屏幕方向（forceOrientation → requestedOrientation）、隐藏刘海
 *   （hideCutout → layoutInDisplayCutoutMode）；
 * - 【键盘】附加键盘栏（showAdditionalKbd → X11 界面底部 ESC/方向键行，
 *   经 X11InputHub 直注 X）、首选扫描码（preferScancodes）、
 *   强制字符输入（enforceCharBasedInput，LorieView 原生消费）；
 * - 【触摸】触摸方式（touchMode：触控板/模拟触摸屏/直接触摸，
 *   SmartTouchBridge 输入策略实时切换，真实生效）、触控板缩放
 *   （scaleTouchpad）、轻点拖拽（tapToMove）；
 * - 【剪贴板】双向同步（clipboardEnable）；
 * - 【操作】重新应用游戏分辨率握手、游戏全屏（Alt+Enter）。
 *
 * 窗口类偏好（全屏/方向/亮屏/刘海/附加键盘栏）经由 [X11UiPrefs] 可观察
 * 镜像驱动 X11Screen 的副作用；分辨率/拉伸/剪贴板等渲染偏好写入
 * LoriePreferences 后经 X11ResolutionLink.pokeActiveView() 即时生效。
 */
object X11SettingsBridge {
    private val _show = MutableStateFlow(false)

    /** 面板显示标志（粘性：通知入口先于窗口打开时依然生效）。 */
    val show: StateFlow<Boolean> = _show

    fun requestShow() { _show.value = true }
    fun dismiss() { _show.value = false }
}

/**
 * v2.25：LoriePreferences 的 Compose 可观察镜像。
 *
 * SharedPreferences 不是可观察存储，而全屏/方向/亮屏/刘海/附加键盘栏
 * 需要驱动 Compose 副作用（窗口标志、requestedOrientation、键行显隐），
 * 故设置面板写入 LoriePreferences 的同时同步本对象；X11Screen 进入时
 * [initIfNeed] 一次性从真实偏好回填（保留用户在 termux-x11 兼容模式
 * 里的既有选择）。
 */
object X11UiPrefs {
    private var initialized = false

    /** 真全屏（隐藏一切覆盖层，画面独占；返回键退出）。 */
    var fullscreen by mutableStateOf(false)

    /** 屏幕方向：auto / portrait / landscape（reverse 归并为对应主方向）。 */
    var orientation by mutableStateOf("auto")

    /** 保持亮屏。 */
    var keepScreenOn by mutableStateOf(true)

    /** 占用刘海区（false = 刘海区留黑）。 */
    var hideCutout by mutableStateOf(true)

    /** 附加键盘栏（X11 画面底部 ESC/TAB/方向键行）。 */
    var showAdditionalKbd by mutableStateOf(false)

    /** 从 LoriePreferences 一次性同步（仅首次）。 */
    fun initIfNeed(prefs: Prefs) {
        if (initialized) return
        initialized = true
        fullscreen = prefs.fullscreen.get()
        orientation = normalizeOrientation(prefs.forceOrientation.get())
        keepScreenOn = prefs.keepScreenOn.get()
        hideCutout = prefs.hideCutout.get()
        showAdditionalKbd = prefs.showAdditionalKbd.get()
    }

    /** forceOrientation 的五档（含 reverse）归并为三档。 */
    fun normalizeOrientation(v: String): String = when (v) {
        "portrait", "reverse portrait" -> "portrait"
        "landscape", "reverse landscape" -> "landscape"
        else -> "auto"
    }
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

    var modeNative by remember(resState.mode) { mutableStateOf(resState.mode != "exact" && resState.mode != "scaled") }
    var modeScaled by remember(resState.mode) { mutableStateOf(resState.mode == "scaled") }
    var resText by remember(resState.mode) {
        mutableStateOf(
            if (resState.mode == "exact" && resState.exact.isNotEmpty()) resState.exact
            else prefs.displayResolutionExact.get()
        )
    }
    var scalePercent by remember(resState.mode) {
        mutableStateOf(prefs.displayScale.get().coerceIn(50, 200).toFloat())
    }
    var stretch by remember(resState.mode) { mutableStateOf(prefs.displayStretch.get()) }
    var clipboard by remember { mutableStateOf(prefs.clipboardEnable.get()) }
    var scancodes by remember { mutableStateOf(prefs.preferScancodes.get()) }
    var charInput by remember { mutableStateOf(prefs.enforceCharBasedInput.get()) }
    // 触摸方式与触控板参数（termux-x11 touchMode/scaleTouchpad/tapToMove）
    var touchMode by remember { mutableStateOf(prefs.touchMode.get().toIntOrNull() ?: 2) }
    var padScale by remember { mutableStateOf(prefs.scaleTouchpad.get()) }
    var tapMove by remember { mutableStateOf(prefs.tapToMove.get()) }
    var resError by remember { mutableStateOf(false) }

    val labelColor = theme.windowTitleBarTextColor
    val accent = theme.accentColor
    val dividerColor = if (theme.isDark) Color(0xFF3A4450) else Color(0xFFDDDDDD)
    val hintColor = Color(0xFF8A97A3)

    AlertDialog(
        onDismissRequest = { X11SettingsBridge.dismiss() },
        // 收窄对话框 + 内容可滚动：横屏不再溢出（v2.22.5 fix12 语义保留）
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
            Column(
                modifier = Modifier
                    .widthIn(max = 306.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {

                // ---- 状态行 ----
                Text(
                    text = when {
                        resState.mode == "exact" ->
                            "当前 X 屏幕：${resState.exact}（" + if (resState.fromGame) "游戏握手）" else "手动）"
                        resState.mode == "scaled" -> "当前 X 屏幕：缩放 ${prefs.displayScale.get()}%"
                        else -> "当前 X 屏幕：跟随窗口（随 X11 窗口尺寸变化）"
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (resState.fromGame) accent else labelColor
                )

                HorizontalDivider(color = dividerColor)

                // ================= 显示 =================
                SectionTitle("显示", labelColor)
                Text("分辨率模式", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = labelColor)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            modeNative = true; modeScaled = false
                            X11ResolutionLink.setNative()
                        },
                        colors = modeColors(modeNative, accent, labelColor),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) { Text("跟随窗口", fontSize = 11.sp) }

                    TextButton(
                        onClick = {
                            modeNative = false; modeScaled = true
                            X11ResolutionLink.setScale(scalePercent.toInt())
                        },
                        colors = modeColors(modeScaled, accent, labelColor),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) { Text("缩放", fontSize = 11.sp) }

                    TextButton(
                        onClick = { modeNative = false; modeScaled = false },
                        colors = modeColors(!modeNative && !modeScaled, accent, labelColor),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) { Text("固定分辨率", fontSize = 11.sp) }
                }

                if (modeScaled) {
                    // ---- 缩放比例（termux-x11 displayScale：X 屏幕 = 窗口×100/scale） ----
                    Text(
                        "缩放比例 ${scalePercent.toInt()}%（>100% 界面元素放大，<100% 桌面内容更多）",
                        fontSize = 10.sp, color = hintColor
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = scalePercent,
                            onValueChange = { scalePercent = it },
                            valueRange = 50f..200f,
                            steps = 29,
                            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                            modifier = Modifier.weight(1f).height(26.dp)
                        )
                        TextButton(
                            onClick = { X11ResolutionLink.setScale(scalePercent.toInt()) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("应用", fontSize = 11.sp, color = accent) }
                    }
                }

                if (!modeNative && !modeScaled) {
                    // ---- 预设分辨率（FlowRow 自动换行，不再横向裁切） ----
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

                // ---- 拉伸铺满 ----
                SettingSwitch(
                    title = "拉伸铺满（无黑边）",
                    desc = "画面拉伸至整个 X11 窗口（宽高比不同时允许轻微变形）",
                    checked = stretch,
                    enabled = !modeNative,
                    onChecked = {
                        stretch = it
                        prefs.displayStretch.put(it)
                        X11ResolutionLink.pokeActiveView()
                    },
                    labelColor = labelColor, accent = accent,
                    hintColor = hintColor
                )

                HorizontalDivider(color = dividerColor)

                // ================= 窗口 =================
                SectionTitle("窗口", labelColor)

                // ---- 全屏（真全屏：画面独占，返回键退出） ----
                SettingSwitch(
                    title = "全屏显示",
                    desc = "隐藏悬浮球等一切覆盖层，画面独占整屏；返回键退出全屏",
                    checked = X11UiPrefs.fullscreen,
                    enabled = true,
                    onChecked = {
                        X11UiPrefs.fullscreen = it
                        prefs.fullscreen.put(it)
                    },
                    labelColor = labelColor, accent = accent,
                    hintColor = hintColor
                )

                // ---- 保持亮屏（termux-x11 keepScreenOn） ----
                SettingSwitch(
                    title = "保持亮屏",
                    desc = "X11 桌面运行时屏幕不自动熄灭（离开 X11 自动恢复）",
                    checked = X11UiPrefs.keepScreenOn,
                    enabled = true,
                    onChecked = {
                        X11UiPrefs.keepScreenOn = it
                        prefs.keepScreenOn.put(it)
                    },
                    labelColor = labelColor, accent = accent,
                    hintColor = hintColor
                )

                // ---- 屏幕方向（termux-x11 forceOrientation） ----
                Text("屏幕方向", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = labelColor)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("auto" to "跟随系统", "portrait" to "竖屏", "landscape" to "横屏").forEach { (v, label) ->
                        TextButton(
                            onClick = {
                                X11UiPrefs.orientation = v
                                prefs.forceOrientation.put(v)
                            },
                            colors = modeColors(X11UiPrefs.orientation == v, accent, labelColor),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) { Text(label, fontSize = 11.sp) }
                    }
                }

                // ---- 刘海区（termux-x11 hideCutout：true = 内容延伸进刘海区遮住刘海） ----
                SettingSwitch(
                    title = "占用刘海屏",
                    desc = "画面延伸到刘海/挖孔区域（关闭后刘海区留黑）",
                    checked = X11UiPrefs.hideCutout,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                    onChecked = {
                        X11UiPrefs.hideCutout = it
                        prefs.hideCutout.put(it)
                    },
                    labelColor = labelColor, accent = accent,
                    hintColor = hintColor
                )

                HorizontalDivider(color = dividerColor)

                // ================= 键盘 =================
                SectionTitle("键盘", labelColor)

                // ---- 附加键盘栏（termux-x11 showAdditionalKbd） ----
                SettingSwitch(
                    title = "附加键盘栏",
                    desc = "画面底部显示 ESC / TAB / CTRL / 方向键等实体快捷键行（点按直注 X）",
                    checked = X11UiPrefs.showAdditionalKbd,
                    enabled = true,
                    onChecked = {
                        X11UiPrefs.showAdditionalKbd = it
                        prefs.showAdditionalKbd.put(it)
                    },
                    labelColor = labelColor, accent = accent,
                    hintColor = hintColor
                )

                // ---- 首选扫描码（termux-x11 preferScancodes） ----
                SettingSwitch(
                    title = "首选扫描码（外接键盘）",
                    desc = "物理键盘按键按扫描码上报，游戏/远程桌面兼容性更好",
                    checked = scancodes,
                    enabled = true,
                    onChecked = {
                        scancodes = it
                        prefs.preferScancodes.put(it)
                        X11ResolutionLink.pokeActiveView()
                    },
                    labelColor = labelColor, accent = accent,
                    hintColor = hintColor
                )

                // ---- 强制字符输入（termux-x11 enforceCharBasedInput，LorieView 消费） ----
                SettingSwitch(
                    title = "强制字符输入",
                    desc = "输入法按字符而非按键上报，修复部分中文输入法丢键",
                    checked = charInput,
                    enabled = true,
                    onChecked = {
                        charInput = it
                        prefs.enforceCharBasedInput.put(it)
                        X11ResolutionLink.pokeActiveView()
                    },
                    labelColor = labelColor, accent = accent,
                    hintColor = hintColor
                )

                HorizontalDivider(color = dividerColor)

                // ================= 触摸（termux-x11 touchMode） =================
                SectionTitle("触摸", labelColor)

                // ---- 触摸方式（1=触控板 2=模拟触摸屏 3=直接触摸） ----
                Text("触摸方式", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = labelColor)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        2 to "模拟触摸",  // Simulated touchscreen（原默认行为）
                        1 to "触控板",    // Trackpad
                        3 to "直接触摸"   // Direct touch
                    ).forEach { (v, label) ->
                        TextButton(
                            onClick = {
                                touchMode = v
                                prefs.touchMode.put(v.toString())
                                // 即时下发到活跃 SmartTouchBridge —— 输入策略真实切换
                                SmartTouchBridge.applyTouchPrefs()
                            },
                            colors = modeColors(touchMode == v, accent, labelColor),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) { Text(label, fontSize = 11.sp) }
                    }
                }
                Text(
                    text = when (touchMode) {
                        1 -> "触控板：手指相对移动虚拟光标；轻点=左键单击，双指轻点=右键，双指滑动=滚轮"
                        3 -> "直接触摸：触摸按 X 触摸事件直注（多点触摸），触摸类游戏/应用真实可用"
                        else -> "模拟触摸屏：手指位置即鼠标位置，按下/拖拽/双击吸附/双指右键/滚轮"
                    },
                    fontSize = 9.sp,
                    color = hintColor,
                    lineHeight = 12.sp
                )

                // ---- 触控板专属参数（仅触控板方式显示，切换后即时生效） ----
                if (touchMode == 1) {
                    SettingSwitch(
                        title = "触控板缩放",
                        desc = "光标位移按画面拉伸比例放大（关闭 = 手指 1:1 位移，对齐上游 scaleTouchpad）",
                        checked = padScale,
                        enabled = true,
                        onChecked = {
                            padScale = it
                            prefs.scaleTouchpad.put(it)
                            SmartTouchBridge.applyTouchPrefs()
                        },
                        labelColor = labelColor, accent = accent,
                        hintColor = hintColor
                    )
                    SettingSwitch(
                        title = "轻点拖拽",
                        desc = "轻点=按下左键，移动=拖拽，再轻点=释放（对齐上游 tapToMove）",
                        checked = tapMove,
                        enabled = true,
                        onChecked = {
                            tapMove = it
                            prefs.tapToMove.put(it)
                            SmartTouchBridge.applyTouchPrefs()
                        },
                        labelColor = labelColor, accent = accent,
                        hintColor = hintColor
                    )
                }

                HorizontalDivider(color = dividerColor)

                // ================= 剪贴板 =================
                SectionTitle("剪贴板", labelColor)
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
                    labelColor = labelColor, accent = accent,
                    hintColor = hintColor
                )

                HorizontalDivider(color = dividerColor)

                // ================= 操作 =================
                SectionTitle("操作", labelColor)

                // ---- 重放游戏分辨率握手 ----
                TextButton(
                    onClick = { X11ResolutionLink.applyFromFile() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("重新应用游戏分辨率（glibc-runner -d）", fontSize = 11.sp, color = accent)
                }
                // ---- 游戏全屏（Alt+Enter）----
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
private fun modeColors(selected: Boolean, accent: Color, normal: Color) =
    ButtonDefaults.textButtonColors(contentColor = if (selected) accent else normal)

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.background(
            color.copy(alpha = 0.08f),
            RoundedCornerShape(4.dp)
        ).padding(horizontal = 6.dp, vertical = 2.dp)
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
