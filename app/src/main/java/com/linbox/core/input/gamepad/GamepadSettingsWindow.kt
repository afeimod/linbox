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
 * 虚拟手柄悬浮设置窗（v2.15.2）：
 *
 * - 标题栏可拖动整窗重新定位（限制在屏幕范围内）
 * - 添加/删除元素：按钮、摇杆、十字键
 * - 位置与大小调节：X/Y 归一化滑杆 + 尺寸滑杆（摇杆/十字键/按钮全部支持）
 * - 完整映射选择器：全部键盘按键（字母/数字/功能键/控制键/方向/WASD/符号）+ 鼠标功能
 * - 摇杆/十字键方向模式：方向键 ←↑→↓ ↔ WASD 一键切换
 * - 预设布局：经典手柄 / 简单布局；所有修改即时持久化（JSON → DataStore）
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
        val newElement = when (type) {
            GamepadController.ElementType.BUTTON -> GamepadController.PadElement(
                type = type, label = "A",
                action = GamepadController.PadAction.key(android.view.KeyEvent.KEYCODE_SPACE, "Space"),
                posX = 0.5f, posY = 0.5f, sizeDp = 60f
            )
            GamepadController.ElementType.JOYSTICK -> GamepadController.PadElement(
                type = type, label = "摇杆",
                posX = 0.2f, posY = 0.5f, sizeDp = 150f
            )
            GamepadController.ElementType.DPAD -> GamepadController.PadElement(
                type = type, label = "十字键",
                posX = 0.8f, posY = 0.5f, sizeDp = 120f
            )
        }
        GamepadController.updateElements(elements + newElement)
        GamepadController.selectElement(newElement.id)
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

        // ===== 悬浮窗拖动位移（标题栏拖拽，限制在屏幕内）=====
        var winOffset by remember { mutableStateOf(Offset.Zero) }
        fun applyWindowDrag(delta: Offset) {
            val maxX = screenWpx * 0.62f   // 右锚点，向左最多拉到屏宽 25% 处仍可见
            val minX = -screenWpx * 0.75f
            val maxY = screenHpx * 0.55f
            val minY = -screenHpx * 0.65f
            winOffset = Offset(
                (winOffset.x + delta.x).coerceIn(minX, maxX),
                (winOffset.y + delta.y).coerceIn(minY, maxY)
            )
        }

        // ===== 背景遮罩：点击关闭 =====
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x33000000))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { GamepadController.settingsOpen = false }
        )

        // ===== 设置卡片（右侧居中，标题栏可拖动，限制高度可滚动） =====
        Column(
            modifier = modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp)
                .offset { IntOffset(winOffset.x.roundToInt(), winOffset.y.roundToInt()) }
                .widthIn(min = 320.dp, max = 360.dp)
                .heightIn(max = maxHeight - 24.dp)
                // v2.20：登记为触控板直通区
                .onGloballyPositioned {
                    TrackpadRouter.registerPassthrough("gpSettings", it.boundsInWindowCompat())
                }
                .shadow(14.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF0162030))
                .border(1.dp, Color(0x592DD4BF), RoundedCornerShape(16.dp))
        ) {
            GpSettingsHeader(
                onDismiss = { GamepadController.settingsOpen = false },
                onDrag = { delta -> applyWindowDrag(delta) }
            )

            // ===== 编辑模式（v2.26：原右上角迷你工具条 ✎ 收编进设置窗，
            //      悬浮球「手柄设置」进来也能直接切换编辑） =====
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("编辑模式", color = Color(0xFFE2F5FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("开启后直接拖动手柄元素，元素右上角角标删除", color = Color(0xFF6E8FA5), fontSize = 10.sp)
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

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                // ===== 预设布局 =====
                GpSectionLabel("预设布局")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GpChip("经典手柄", false) { applyPreset("classic") }
                    GpChip("简单布局", false) { applyPreset("simple") }
                    Spacer(Modifier.weight(1f))
                }
                GpHint("提示：点 ✎ 进入编辑模式后可直接拖动元素，点元素角标删除")

                // ===== 元素列表 =====
                GpSectionLabel("元素（${elements.size}）")
                elements.forEach { el ->
                    GpElementRow(
                        element = el,
                        selected = el.id == selectedId,
                        onClick = {
                            GamepadController.selectElement(if (selectedId == el.id) null else el.id)
                        },
                        onDelete = {
                            GamepadController.updateElements(elements.filter { it.id != el.id })
                            if (selectedId == el.id) GamepadController.selectElement(null)
                            persist()
                        }
                    )
                }

                // ===== 添加元素 =====
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GpChip("＋ 按钮", false) { addElement(GamepadController.ElementType.BUTTON) }
                    GpChip("＋ 摇杆", false) { addElement(GamepadController.ElementType.JOYSTICK) }
                    GpChip("＋ 十字键", false) { addElement(GamepadController.ElementType.DPAD) }
                }

                // ===== 选中元素编辑器 =====
                if (selected != null) {
                    Spacer(Modifier.height(12.dp))
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

                    // 大小调节
                    GpSubLabel("大小（${selected.sizeDp.roundToInt()}dp）")
                    GpSlider(
                        value = selected.sizeDp,
                        valueRange = 40f..220f,
                        onValueChange = { v ->
                            updateElement(selected.id) { it.copy(sizeDp = v) }
                        }
                    )

                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.height(16.dp))
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
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 拖动手柄提示
        Text("⠿", color = Color(0x66E2F5FF), fontSize = 14.sp)
        Spacer(Modifier.width(7.dp))
        Text("🎮", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            "虚拟手柄设置",
            color = Color(0xFFE2F5FF),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(0x1FFFFFFF))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Text("×", color = Color(0xFFE2F5FF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GpSectionLabel(text: String) {
    Text(
        text,
        color = Color(0xFF2DD4BF),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun GpSubLabel(text: String) {
    Text(
        text,
        color = Color(0xFF9FC4D8),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun GpHint(text: String) {
    Text(
        text,
        color = Color(0xFF6E8FA5),
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun GpChip(label: String, active: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) Color(0x4D2DD4BF) else Color(0x1FFFFFFF)
            )
            .border(
                1.dp,
                if (active) Color(0xFF2DD4BF) else Color(0x26FFFFFF),
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 5.dp else 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color(0xFF5EEAD4) else Color(0xFFCFE4F0),
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun GpMappingButton(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x1FFFFFFF))
            .border(1.dp, Color(0x402DD4BF), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "映射到",
            color = Color(0xFF9FC4D8),
            fontSize = 11.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            text,
            color = Color(0xFF5EEAD4),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        Text("▾", color = Color(0xFF5EEAD4), fontSize = 11.sp)
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

@Composable
private fun GpElementRow(
    element: GamepadController.PadElement,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0x332DD4BF) else Color(0x14FFFFFF))
            .border(
                1.dp,
                if (selected) Color(0x662DD4BF) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 类型图标
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(0x262DD4BF)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when (element.type) {
                    GamepadController.ElementType.BUTTON -> "●"
                    GamepadController.ElementType.JOYSTICK -> "◎"
                    GamepadController.ElementType.DPAD -> "✛"
                },
                color = Color(0xFF5EEAD4),
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${typeLabel(element)} · ${element.label}",
                color = Color(0xFFE2F5FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (element.type) {
                    GamepadController.ElementType.BUTTON ->
                        "映射：${element.action.label} · ${(element.posX * 100).roundToInt()}%,${(element.posY * 100).roundToInt()}% · ${element.sizeDp.roundToInt()}dp"
                    else ->
                        "${if (element.dirMode == GamepadController.DirMode.WASD) "WASD" else "方向键"}模式 · ${(element.posX * 100).roundToInt()}%,${(element.posY * 100).roundToInt()}% · ${element.sizeDp.roundToInt()}dp"
                },
                color = Color(0xFF6E8FA5),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 删除
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(0x33EF4444))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Text("×", color = Color(0xFFFCA5A5), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
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
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Color(0xF0162030))
                .border(1.dp, Color(0x592DD4BF), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {} // 阻止点击穿透关闭
                )
                .padding(14.dp)
        ) {
            Text(
                "选择映射（全部键盘按键 + 鼠标功能）",
                color = Color(0xFFE2F5FF),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            // 分组 Tab
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                groups.forEachIndexed { i, (name, _) ->
                    GpChip(name, active = i == activeGroup, compact = true) { activeGroup = i }
                }
            }
            Spacer(Modifier.height(10.dp))

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
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x1FFFFFFF))
                                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(8.dp))
                                    .clickable { onPick(action) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = action.label,
                                    color = Color(0xFFE2F5FF),
                                    fontSize = 12.sp,
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
