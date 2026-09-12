package com.linbox.apps.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.input.MouseCursorPreview
import com.linbox.core.input.keyboardAware
import com.linbox.core.input.keyboardAwareEditor
import com.linbox.core.theme.LocalWinTheme
import com.linbox.util.L
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 输入设置页（v2.13）：鼠标设置 / 键盘设置。
 * 由"蓝牙和设备"分区进入（应用内子页，返回上级分区）。
 */

/** 子页头部：返回按钮 + 标题 */
@Composable
internal fun SubPageHeader(title: String, onBack: (() -> Unit)?) {
    val theme = LocalWinTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
    Spacer(Modifier.height(16.dp))
}

/** 分段选择器（单击/双击、双指/长按等二选一） */
@Composable
internal fun SegmentedControl(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.windowBackgroundColor)
            .border(1.dp, theme.cardBackgroundColor, RoundedCornerShape(6.dp))
    ) {
        options.forEach { (id, label) ->
            val active = id == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) theme.accentColor.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) theme.accentColor else theme.secondaryTextColor,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

/** 设置页内的卡片容器（含标题） */
@Composable
internal fun SettingsBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalWinTheme.current
    Text(
        title,
        color = if (theme.isDark) Color.White else Color.Black,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
    )
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp),
        content = content
    )
    Spacer(Modifier.height(10.dp))
}

// ============================================================
// 鼠标设置
// ============================================================

@Composable
internal fun MouseSettingsPage(onBack: () -> Unit) {
    val app = LinBoxApp.get()
    val theme = LocalWinTheme.current
    val scope = rememberCoroutineScope()

    val cursorEnabled by app.settingsStore.mouseCursorEnabled.collectAsState(initial = true)
    val cursorTheme by app.settingsStore.mouseCursorTheme.collectAsState(initial = "white")
    val cursorSize by app.settingsStore.mouseCursorSize.collectAsState(initial = 26f)
    val clickMode by app.settingsStore.mouseClickMode.collectAsState(initial = "single")
    val rightClick by app.settingsStore.mouseRightClick.collectAsState(initial = "twofinger")
    val speed by app.settingsStore.mousePointerSpeed.collectAsState(initial = 1.0f)
    // v2.18 指针移动方式：touch 跟随手指 / trackpad 触控板
    val controlMode by app.settingsStore.mouseControlMode.collectAsState(initial = "touch")

    SubPageHeader("鼠标设置", onBack)

    // 注意：宿主（SettingsApp 右侧内容区）已是 verticalScroll 滚动容器，
    // 子页禁止再套 verticalScroll —— 嵌套会被以无限高度约束测量并抛
    // IllegalStateException（v2.13.1 修复的运行时崩溃），滚动由宿主统一负责。
    Column(modifier = Modifier.fillMaxWidth()) {

        // ===== 指针显示 =====
        SettingsCard(
            icon = Icons.Default.Mouse,
            iconBackgroundColor = Color(0xFF00B294),
            title = "显示鼠标指针",
            subtitle = "Windows 风格箭头指针，跟随手指移动，轻点显示涟漪",
            trailingContent = {
                ToggleSwitch(cursorEnabled) { v ->
                    scope.launch { app.settingsStore.setMouseCursorEnabled(v) }
                }
            }
        )
        Spacer(Modifier.height(10.dp))

        // ===== v2.18 移动方式（触控 / 触控板） =====
        SettingsBlock("移动方式") {
            SegmentedControl(
                options = listOf("touch" to "触控", "trackpad" to "触控板"),
                selected = controlMode
            ) { mode -> scope.launch { app.settingsStore.setMouseControlMode(mode) } }
            Spacer(Modifier.height(6.dp))
            Text(
                if (controlMode == "trackpad")
                    L("整个屏幕当作触控板：滑动移动指针，轻点 = 单击，快速连点两下 = 双击，按住不动后拖动 = 按住拖拽，双指轻点 = 右键，双指滑动 = 滚动页面")
                else
                    L("指针跟随手指移动（默认，触屏习惯）"),
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
            if (controlMode == "trackpad") {
                Spacer(Modifier.height(4.dp))
                Text(
                    "触控板模式已自动设为：双击打开 + 双指右键",
                    color = theme.accentColor,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ===== 指针主题 =====
        SettingsBlock("指针主题") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf(
                    "white" to "经典白", "black" to "经典黑",
                    "blue" to "蓝色", "green" to "高对比绿"
                ).forEach { (id, label) ->
                    val active = id == cursorTheme
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) theme.accentColor.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { scope.launch { app.settingsStore.setMouseCursorTheme(id) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        MouseCursorPreview(themeId = id, sizeDp = 26f)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            label,
                            color = if (active) theme.accentColor else theme.secondaryTextColor,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // ===== 指针大小 =====
        SettingsBlock("指针大小（${cursorSize.roundToInt()}dp）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = cursorSize,
                    onValueChange = { v ->
                        scope.launch { app.settingsStore.setMouseCursorSize(v) }
                    },
                    valueRange = 16f..48f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                MouseCursorPreview(themeId = cursorTheme, sizeDp = cursorSize)
            }
        }

        // ===== 打开方式 =====
        SettingsBlock("图标打开方式") {
            SegmentedControl(
                options = listOf("single" to "单击打开", "double" to "双击打开"),
                selected = clickMode
            ) { mode -> scope.launch { app.settingsStore.setMouseClickMode(mode) } }
            Spacer(Modifier.height(6.dp))
            Text(
                if (clickMode == "single") "单击桌面图标立即打开（触屏推荐，Windows 平板默认）"
                else "双击桌面图标打开（经典桌面鼠标习惯；触控板模式默认此项）",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }

        // ===== 右键手势 =====
        SettingsBlock("右键手势") {
            SegmentedControl(
                options = listOf("twofinger" to "双指轻点", "longpress" to "长按"),
                selected = rightClick
            ) { mode -> scope.launch { app.settingsStore.setMouseRightClick(mode) } }
            Spacer(Modifier.height(6.dp))
            Text(
                if (rightClick == "twofinger") "双指轻点桌面/图标弹出右键菜单（默认）"
                else "单指按住约 0.5 秒弹出右键菜单",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }

        // ===== 光标速度 =====
        SettingsBlock("光标速度（${(speed * 100).roundToInt()}%）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = speed,
                    onValueChange = { v ->
                        scope.launch { app.settingsStore.setMousePointerSpeed(v) }
                    },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "影响外接蓝牙鼠标的指针移动速度",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }

        // ===== 测试区域 =====
        SettingsBlock("点击测试") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(theme.windowBackgroundColor)
                    .border(
                        1.dp,
                        theme.secondaryTextColor.copy(alpha = 0.25f),
                        RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "在此区域轻点或拖动，观察指针与点击涟漪效果",
                    color = theme.secondaryTextColor,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ============================================================
// 键盘设置
// ============================================================

@Composable
internal fun KeyboardSettingsPage(onBack: () -> Unit) {
    val app = LinBoxApp.get()
    val theme = LocalWinTheme.current
    val scope = rememberCoroutineScope()

    val master by app.settingsStore.keyboardMaster.collectAsState(initial = false)
    val funcRow by app.settingsStore.keyboardFuncRow.collectAsState(initial = true)
    val numpad by app.settingsStore.keyboardNumpad.collectAsState(initial = true)
    val scale by app.settingsStore.keyboardScale.collectAsState(initial = 1.0f)
    val themeId by app.settingsStore.keyboardTheme.collectAsState(initial = "dark")
    val dragEnabled by app.settingsStore.keyboardDragEnabled.collectAsState(initial = true)
    val vibrate by app.settingsStore.keyboardVibration.collectAsState(initial = true)
    val touchFeedback by app.settingsStore.touchFeedback.collectAsState(initial = false)

    SubPageHeader("键盘设置", onBack)

    // 同 MouseSettingsPage：不可加 verticalScroll（宿主已有滚动层，嵌套必崩）。
    Column(modifier = Modifier.fillMaxWidth()) {

        // ===== 总开关 =====
        SettingsCard(
            icon = Icons.Default.Keyboard,
            iconBackgroundColor = Color(0xFF8764B8),
            title = "使用 LinBox 虚拟键盘",
            subtitle = if (master) "开启：文本框聚焦时呼出应用内全键盘"
            else "关闭（默认）：文本框使用手机系统输入法",
            trailingContent = {
                ToggleSwitch(master) { v ->
                    scope.launch { app.settingsStore.setKeyboardMaster(v) }
                }
            }
        )
        Spacer(Modifier.height(10.dp))

        // ===== 布局 =====
        SettingsBlock("键盘布局") {
            Text(
                "显示功能键行（Esc + F1-F12）",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            ToggleSwitch(funcRow) { v ->
                scope.launch { app.settingsStore.setKeyboardFuncRow(v) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "显示小键盘（数字键 + 方向键）",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            ToggleSwitch(numpad) { v ->
                scope.launch { app.settingsStore.setKeyboardNumpad(v) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "两者全开 = 104 键全键盘；键盘顶部工具栏也可快速切换",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }

        // ===== 大小 =====
        SettingsBlock("键盘大小（${(scale * 100).roundToInt()}%）") {
            Slider(
                value = scale,
                onValueChange = { v ->
                    scope.launch { app.settingsStore.setKeyboardScale(v) }
                },
                valueRange = 0.75f..1.35f
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "也可用键盘工具栏 - / + 按钮或双指捏合直接调节（75% - 135%）",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }

        // ===== 主题 =====
        SettingsBlock("键盘主题") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf(
                    "light" to "浅色", "dark" to "深色",
                    "blue" to "蓝色", "glass" to "玻璃"
                ).forEach { (id, label) ->
                    val active = id == themeId
                    val previewBg = when (id) {
                        "light" -> Color(0xFFE9E9EF)
                        "blue" -> Color(0xFF1E2C42)
                        "glass" -> Color(0xCC181820)
                        else -> Color(0xFF26262C)
                    }
                    val keyBg = when (id) {
                        "light" -> Color.White
                        "blue" -> Color(0xFF2E4162)
                        "glass" -> Color(0x2EFFFFFF)
                        else -> Color(0xFF3B3B44)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) theme.accentColor.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { scope.launch { app.settingsStore.setKeyboardTheme(id) } }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(3) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp, 16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(keyBg)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(58.dp)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(previewBg)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            label,
                            color = if (active) theme.accentColor else theme.secondaryTextColor,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // ===== 位置与拖动 =====
        SettingsBlock("位置与拖动") {
            Text(
                "允许拖动键盘（按住键盘顶部工具栏拖动）",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            ToggleSwitch(dragEnabled) { v ->
                scope.launch { app.settingsStore.setKeyboardDragEnabled(v) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "位置会自动记忆；重置后回到屏幕底部居中",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = { scope.launch { app.settingsStore.setKeyboardPos(0.5f, 1.0f) } },
                shape = RoundedCornerShape(6.dp)
            ) { Text("重置键盘位置", fontSize = 12.sp) }
        }

        // ===== 反馈 =====
        SettingsCard(
            icon = Icons.Default.Vibration,
            iconBackgroundColor = Color(0xFFCA5010),
            title = "键盘振动",
            subtitle = "按键时触觉反馈（真实生效）",
            trailingContent = {
                ToggleSwitch(vibrate) { v ->
                    scope.launch { app.settingsStore.setKeyboardVibration(v) }
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        SettingsCard(
            icon = Icons.Default.TouchApp,
            iconBackgroundColor = Color(0xFF00B7C3),
            title = "触摸反馈",
            subtitle = "按键按压动画与高亮（真实生效）",
            trailingContent = {
                ToggleSwitch(touchFeedback) { v ->
                    scope.launch { app.settingsStore.setTouchFeedback(v) }
                }
            }
        )
        Spacer(Modifier.height(10.dp))

        // ===== 测试输入 =====
        if (master) {
            SettingsBlock("输入测试") {
                var singleLineText by remember { mutableStateOf("") }
                var multiText by remember { mutableStateOf(TextFieldValue("")) }
                Text(
                    "单行（点击聚焦呼出键盘，Enter 无动作）",
                    color = theme.secondaryTextColor,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value = singleLineText,
                    onValueChange = { singleLineText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.windowBackgroundColor)
                        .keyboardAware(
                            value = { singleLineText },
                            onValue = { singleLineText = it },
                            singleLine = true
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "多行（支持光标移动 / Shift / Ctrl+A/C/X/V）",
                    color = theme.secondaryTextColor,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value = multiText,
                    onValueChange = { multiText = it },
                    textStyle = TextStyle(
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.windowBackgroundColor)
                        .keyboardAwareEditor(
                            value = { multiText },
                            onValue = { multiText = it },
                            singleLine = false
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }
        }
    }
}
