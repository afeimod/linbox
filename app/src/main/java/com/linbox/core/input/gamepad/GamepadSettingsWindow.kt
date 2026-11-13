package com.linbox.core.input.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.input.boundsInWindowCompat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 虚拟手柄悬浮设置窗（v2.15.2；v6.2 居中紧凑重制）：
 *
 * v6.2（用户："虚拟按键编辑界面也太大了，而且要居中显示，不要这种排列，
 * 保证横屏竖屏都能显示完整"）：
 * - **居中显示**：窗口默认屏幕正中（旧版固定右侧 CenterEnd）；
 * - **紧凑重排**：元素列表从大行卡改为三列小芯片网格（点选展开编辑），
 *   整窗宽 260-320dp、内边距收紧，高度上限 = 屏高-24dp + 内部滚动 ——
 *   横屏矮屏 / 竖屏窄屏都完整可见；
 * - 标题栏仍可拖动整窗（限制在屏幕范围内）；
 * - 添加/删除元素：按钮、摇杆、十字键（新元素默认尺寸同步 v6.2 缩小档）；
 * - 位置与大小调节：X/Y 归一化滑杆 + 尺寸滑杆（20-200dp）；
 * - 完整映射选择器：全部键盘按键 + 鼠标功能；
 * - 摇杆/十字键方向模式：方向键 ←↑→↓ ↔ WASD 一键切换；
 * - 预设布局：经典手柄 / 简单布局；所有修改即时持久化。
 */
@Composable
fun GamepadSettingsWindow(modifier: Modifier = Modifier) {
    if (!GamepadController.settingsOpen) return

    // v2.20：设置面板登记为触控板直通区（真实手指可直接调节滑杆）；
    // 关闭/离开组合时注销
    DisposableEffect(Unit) {
        onDispose { TrackpadRouter.registerPassthrough("gpSettings", null) }
    }

    val app = LinBoxApp.get()
    val scope = rememberCoroutineScope()
    val elements = GamepadController.elements
    val selectedId = GamepadController.selectedElementId
    val selected = elements.firstOrNull { it.id == selectedId }

    // 映射选择器打开状态（选中分组）
    var showActionPicker by remember { mutableStateOf(false) }

    fun persist() {
        scope.launch { app.settingsStore.setGamepadConfig(GamepadController.toJson()) }
    }

    fun updateElement(id: String, transform: (GamepadController.PadElement) -> GamepadController.PadElement) {
        GamepadController.updateElements(elements.map { if (it.id == id) transform(it) else it })
        persist()
    }

    fun addElement(type: GamepadController.ElementType) {
        // v6.3：新元素默认尺寸同步重校档（按钮 42 / 摇杆 100 / 十字键 80）
        val newElement = when (type) {
            GamepadController.ElementType.BUTTON -> GamepadController.PadElement(
                type = type, label = "A",
                action = GamepadController.PadAction.key(android.view.KeyEvent.KEYCODE_SPACE, "Space"),
                posX = 0.5f, posY = 0.5f, sizeDp = 42f
            )
            GamepadController.ElementType.JOYSTICK -> GamepadController.PadElement(
                type = type, label = "摇杆",
                posX = 0.2f, posY = 0.5f, sizeDp = 100f
            )
            GamepadController.ElementType.DPAD -> GamepadController.PadElement(
                type = type, label = "十字键",
                posX = 0.8f, posY = 0.5f, sizeDp = 80f
            )
        }
        GamepadController.updateElements(elements + newElement)
        GamepadController.selectElement(newElement.id)
        persist()
    }

    fun deleteElement(id: String) {
        GamepadController.updateElements(elements.filter { it.id != id })
        if (selectedId == id) GamepadController.selectElement(null)
        persist()
    }

    fun applyPreset(which: String) {
        GamepadController.updateElements(
            if (which == "simple") GamepadController.simpleLayout() else GamepadController.classicLayout()
        )
        GamepadController.selectElement(null)
        persist()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWpx = with(density) { maxWidth.toPx() }
        val screenHpx = with(density) { maxHeight.toPx() }

        // ===== 悬浮窗拖动位移（标题栏拖拽；v6.2 居中基准，钳制在屏内） =====
        var winOffset by remember { mutableStateOf(Offset.Zero) }
        fun applyWindowDrag(delta: Offset) {
            val maxX = (screenWpx / 2f - with(density) { 30.dp.toPx() }).coerceAtLeast(0f)
            val maxY = (screenHpx / 2f - with(density) { 30.dp.toPx() }).coerceAtLeast(0f)
            winOffset = Offset(
                (winOffset.x + delta.x).coerceIn(-maxX, maxX),
                (winOffset.y + delta.y).coerceIn(-maxY, maxY)
            )
        }

        // ===== v6.5：非拦截背景暗化 =====
        //（旧版全屏遮罩 clickable 拦截全部触摸 —— 手柄元素点选编辑没法做。
        // 现在只画轻量暗化不拦截：点手柄元素 = 选中编辑（Overlay 侧处理），
        // 点空白处正常穿透；关闭设置窗用标题栏 ✕）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x22000000))
        )

        // ===== 设置卡片（v6.2：屏幕居中，紧凑 260-320dp，高度受限可滚动） =====
        Column(
            modifier = modifier
                .align(Alignment.Center)
                .offset { IntOffset(winOffset.x.roundToInt(), winOffset.y.roundToInt()) }
                .widthIn(min = 260.dp, max = 320.dp)
                .heightIn(max = maxHeight - 24.dp)
                // v2.20：登记为触控板直通区
                .onGloballyPositioned {
                    TrackpadRouter.registerPassthrough("gpSettings", it.boundsInWindowCompat())
                }
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF0162030))
                .border(1.dp, Color(0x592DD4BF), RoundedCornerShape(16.dp))
        ) {
            GpSettingsHeader(
                onDismiss = { GamepadController.settingsOpen = false },
                onDrag = { delta -> applyWindowDrag(delta) }
            )

            // ===== 编辑模式（v2.26 收编进设置窗；v6.3 紧凑行再收紧） =====
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("编辑模式", color = Color(0xFFE2F5FF), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text("拖动元素定位，角标删除", color = Color(0xFF6E8FA5), fontSize = 8.sp, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = GamepadController.editMode,
                    onCheckedChange = { on ->
                        GamepadController.releaseAllKeys()
                        GamepadController.editMode = on
                        if (!on) GamepadController.selectElement(null)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2DD4BF))
                )
            }

            // v6.5：点选手柄元素后自动滚到编辑器（编辑器在列表下方，
            // 小窗不滚动就看不见 —— 点了元素却"没反应"）；稍等一拍让
            // 编辑器先组合完成（maxValue 更新后再滚，防滚不到位）
            val editorScroll = rememberScrollState()
            LaunchedEffect(selectedId) {
                if (selectedId != null) {
                    kotlinx.coroutines.delay(150)
                    editorScroll.animateScrollTo(editorScroll.maxValue)
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(editorScroll)
                    .padding(horizontal = 10.dp)
            ) {
                // ===== 预设布局 =====
                GpSectionLabel("预设布局")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GpChip("经典手柄", false) { applyPreset("classic") }
                    GpChip("简单布局", false) { applyPreset("simple") }
                    Spacer(Modifier.weight(1f))
                }
                GpHint("提示：点击手柄上的按钮即可编辑对应按键；编辑模式下可拖动定位、角标删除")

                // ===== 元素列表（v6.2：三列小芯片网格 —— 旧版大行卡太占空间） =====
                GpSectionLabel("元素（${elements.size}）")
                elements.chunked(3).forEach { rowElements ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowElements.forEach { el ->
                            GpElementChip(
                                element = el,
                                selected = el.id == selectedId,
                                onClick = {
                                    GamepadController.selectElement(if (selectedId == el.id) null else el.id)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowElements.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // ===== 添加元素 =====
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GpChip("＋ 按钮", false) { addElement(GamepadController.ElementType.BUTTON) }
                    GpChip("＋ 摇杆", false) { addElement(GamepadController.ElementType.JOYSTICK) }
                    GpChip("＋ 十字键", false) { addElement(GamepadController.ElementType.DPAD) }
                }

                // ===== 选中元素编辑器 =====
                if (selected != null) {
                    Spacer(Modifier.height(10.dp))
                    GpSectionLabel("编辑：${typeLabel(selected)} \"${selected.label}\"")

                    // 按钮标签快捷选择（决定按钮配色；按钮表面显示映射按键名）
                    if (selected.type == GamepadController.ElementType.BUTTON) {
                        GpSubLabel("按钮标签（配色用，表面显示映射按键）")
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("A", "B", "X", "Y", "L1", "R1", "L2", "R2", "≡", "＋", "－", "Menu")
                                .forEach { lb ->
                                    GpChip(
                                        lb,
                                        active = selected.label == lb,
                                        compact = true
                                    ) { updateElement(selected.id) { it.copy(label = lb) } }
                                }
                        }
                    }

                    // 按钮映射（完整键鼠清单）
                    if (selected.type == GamepadController.ElementType.BUTTON) {
                        GpSubLabel("按键映射（全部键盘 + 鼠标功能）")
                        GpMappingButton(
                            text = selected.action.label,
                            onClick = { showActionPicker = true }
                        )
                    }

                    // 摇杆/十字键方向模式
                    if (selected.type != GamepadController.ElementType.BUTTON) {
                        GpSubLabel("方向模式（${if (selected.type == GamepadController.ElementType.JOYSTICK) "摇杆" else "十字键"} 上下左右输出）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GpChip(
                                "←↑→↓ 方向键",
                                active = selected.dirMode == GamepadController.DirMode.ARROWS
                            ) {
                                updateElement(selected.id) { it.copy(dirMode = GamepadController.DirMode.ARROWS) }
                            }
                            GpChip(
                                "WASD",
                                active = selected.dirMode == GamepadController.DirMode.WASD
                            ) {
                                updateElement(selected.id) { it.copy(dirMode = GamepadController.DirMode.WASD) }
                            }
                        }
                    }

                    // 位置调节
                    GpSubLabel("位置 X（${(selected.posX * 100).roundToInt()}%）")
                    GpSlider(
                        value = selected.posX,
                        onValueChange = { v ->
                            updateElement(selected.id) { it.copy(posX = v) }
                        }
                    )
                    GpSubLabel("位置 Y（${(selected.posY * 100).roundToInt()}%）")
                    GpSlider(
                        value = selected.posY,
                        onValueChange = { v ->
                            updateElement(selected.id) { it.copy(posY = v) }
                        }
                    )

                    // 大小调节（v6.2：范围 20-200dp，匹配缩小后的默认尺寸）
                    GpSubLabel("大小（${selected.sizeDp.roundToInt()}dp）")
                    GpSlider(
                        value = selected.sizeDp,
                        valueRange = 20f..200f,
                        onValueChange = { v ->
                            updateElement(selected.id) { it.copy(sizeDp = v) }
                        }
                    )

                    // 删除元素（v6.2：从行内移到编辑器底部）
                    Spacer(Modifier.height(4.dp))
                    Row {
                        GpDangerButton("🗑 删除此元素") { deleteElement(selected.id) }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.height(14.dp))
            }
        }

        // ===== 映射选择器（全键盘 + 鼠标）=====
        if (showActionPicker && selected != null) {
            ActionPickerSheet(
                onPick = { action ->
                    updateElement(selected.id) { it.copy(action = action) }
                    showActionPicker = false
                },
                onDismiss = { showActionPicker = false }
            )
        }
    }
}

// ============================================================
// 组件
// ============================================================

@Composable
private fun GpSettingsHeader(onDismiss: () -> Unit, onDrag: (Offset) -> Unit) {
    // v6.3：标题栏收紧（内边距 8/7→6/5，字号 13→11，关闭钮 22→18）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x332DD4BF))
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount)
                }
            }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 拖动手柄提示
        Text("⠿", color = Color(0x66E2F5FF), fontSize = 10.sp)
        Spacer(Modifier.width(5.dp))
        Text("🎮", fontSize = 11.sp)
        Spacer(Modifier.width(5.dp))
        Text(
            "虚拟手柄设置",
            color = Color(0xFFE2F5FF),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            "拖动标题栏可移动",
            color = Color(0x66E2F5FF),
            fontSize = 8.sp,
            maxLines = 1
        )
        Spacer(Modifier.width(5.dp))
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0x1FFFFFFF))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Text("×", color = Color(0xFFE2F5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GpSectionLabel(text: String) {
    Text(
        text,
        color = Color(0xFF2DD4BF),
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, bottom = 3.dp)
    )
}

@Composable
private fun GpSubLabel(text: String) {
    Text(
        text,
        color = Color(0xFF9FC4D8),
        fontSize = 8.5.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 1.dp)
    )
}

@Composable
private fun GpHint(text: String) {
    Text(
        text,
        color = Color(0xFF6E8FA5),
        fontSize = 8.sp,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun GpChip(label: String, active: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    // v6.3：内部框与字体整体缩小（用户："按键编辑界面整体字体和内部框
    // 也要缩小，整体可以不变"）—— 芯片内边距 13/7→9/4（紧凑 9/4→7/3），
    // 字号 11→9.5，窗口整体尺寸不变
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (active) Color(0x4D2DD4BF) else Color(0x1FFFFFFF)
            )
            .border(
                1.dp,
                if (active) Color(0xFF2DD4BF) else Color(0x26FFFFFF),
                RoundedCornerShape(7.dp)
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 7.dp else 9.dp,
                vertical = if (compact) 3.dp else 4.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color(0xFF5EEAD4) else Color(0xFFCFE4F0),
            fontSize = 9.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/** v6.2：危险动作按钮（删除元素）；v6.3 内框缩小。 */
@Composable
private fun GpDangerButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0x33EF4444))
            .border(1.dp, Color(0x66EF4444), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color(0xFFFCA5A5),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/**
 * v6.2：元素小芯片（三列网格单元，替代旧版大行卡 —— 用户："不要这种排列"）。
 * 类型图标 + 标签纵向紧凑排布；选中态青色描边点亮。
 */
@Composable
private fun GpElementChip(
    element: GamepadController.PadElement,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // v6.3：内框缩小 —— 图标 14→11、标签 9.5→8.5、内边距 6→4
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0x332DD4BF) else Color(0x14FFFFFF))
            .border(
                1.dp,
                if (selected) Color(0xFF2DD4BF) else Color(0x26FFFFFF),
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Text(
            when (element.type) {
                GamepadController.ElementType.BUTTON -> "●"
                GamepadController.ElementType.JOYSTICK -> "◎"
                GamepadController.ElementType.DPAD -> "✛"
            },
            color = if (selected) Color(0xFF5EEAD4) else Color(0xFF9FC4D8),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(1.dp))
        Text(
            element.label.ifBlank { typeLabel(element) },
            color = if (selected) Color(0xFFE2F5FF) else Color(0xFFCFE4F0),
            fontSize = 8.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GpMappingButton(text: String, onClick: () -> Unit) {
    // v6.3：内框缩小（12/9→9/6，字号 11/13→9.5/11）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0x1FFFFFFF))
            .border(1.dp, Color(0x402DD4BF), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "映射到",
            color = Color(0xFF9FC4D8),
            fontSize = 9.5.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            text,
            color = Color(0xFF5EEAD4),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(5.dp))
        Text("▾", color = Color(0xFF5EEAD4), fontSize = 9.5.sp)
    }
}

@Composable
private fun GpSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0.04f..0.96f
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = Color(0xFF2DD4BF),
            activeTrackColor = Color(0xFF2DD4BF),
            inactiveTrackColor = Color(0x33FFFFFF)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun typeLabel(element: GamepadController.PadElement): String = when (element.type) {
    GamepadController.ElementType.BUTTON -> "按钮"
    GamepadController.ElementType.JOYSTICK -> "摇杆"
    GamepadController.ElementType.DPAD -> "十字键"
}

// ============================================================
// 映射选择器（完整键盘 + 鼠标）
// ============================================================

/**
 * 映射选择器：分八个分组（字母/数字/功能键/控制键/方向键/WASD/符号/鼠标），
 * Tab 切换分组，网格点选即完成映射。
 */
@Composable
private fun ActionPickerSheet(
    onPick: (GamepadController.PadAction) -> Unit,
    onDismiss: () -> Unit
) {
    var activeGroup by remember { mutableStateOf(0) }
    val groups = GamepadController.ACTION_GROUPS

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF0162030))
                .border(1.dp, Color(0x592DD4BF), RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {} // 阻止点击穿透关闭
                )
                .padding(12.dp)
        ) {
            Text(
                "选择映射（全部键盘按键 + 鼠标功能）",
                color = Color(0xFFE2F5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))

            // 分组 Tab
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                groups.forEachIndexed { i, (name, _) ->
                    GpChip(name, active = i == activeGroup, compact = true) { activeGroup = i }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 按键网格
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                val actions = groups[activeGroup].second
                // 5 列网格
                actions.chunked(5).forEach { rowActions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowActions.forEach { action ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color(0x1FFFFFFF))
                                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(7.dp))
                                    .clickable { onPick(action) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = action.label,
                                    color = Color(0xFFE2F5FF),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                        repeat(5 - rowActions.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
