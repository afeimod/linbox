package com.linbox.core.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.input.keyboardAware
import com.linbox.core.theme.TaskbarStyle
import com.linbox.core.theme.WinTheme
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.WindowManager
import com.linbox.util.L
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 任务栏（v2.15 多时代风格重绘）
 *
 * 按当前主题的 [TaskbarStyle] 完整还原五个时代的任务栏：
 * - CLASSIC_95：Win95 灰色立体任务栏 —— 凸起开始按钮 / 凹陷托盘 / 文字任务按钮
 * - LUNA_XP：WinXP Luna 蓝色渐变 —— 绿色圆角开始按钮 / 蓝渐变任务按钮 / 渐变托盘
 * - AERO_7：Win7 Aero 玻璃 —— 开始圆球 / 图标式任务栏 / 辉光激活态
 * - MODERN_10：Win10 浅色扁平 —— 靠左图标 + 搜索框 + 底部下划线激活态
 * - MICA_11：Win11 Mica 悬浮居中 Dock（本应用原有标志性样式，保留）
 *
 * 通用能力（所有风格共享）：
 * - 系统托盘（wifi/音量/电池/时钟）在右端，点击弹出快速设置 / 日历
 * - 时钟样式可自定义（长按时钟）；任务按钮点击 = 前置/最小化切换
 * - 开始按钮切换开始菜单；搜索提交打开浏览器
 */
@Composable
fun Taskbar(
    theme: WinTheme,
    taskbarHeight: Dp,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit = {},
    showSeconds: Boolean = false,
    timeFormat24h: Boolean = true,
    taskbarCentered: Boolean = true,
    modifier: Modifier = Modifier
) {
    var tick by remember { mutableStateOf(0L) }

    // 每秒刷新时钟
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    when (theme.taskbarStyle) {
        TaskbarStyle.CLASSIC_95 -> TaskbarWin95(
            taskbarHeight = taskbarHeight, startMenuOpen = startMenuOpen,
            onStartClick = onStartClick, onOpenCalendar = onOpenCalendar,
            onOpenQuickSettings = onOpenQuickSettings, onOpenClockStyle = onOpenClockStyle,
            tick = tick, timeFormat24h = timeFormat24h, modifier = modifier
        )
        TaskbarStyle.LUNA_XP -> TaskbarWinXp(
            taskbarHeight = taskbarHeight, startMenuOpen = startMenuOpen,
            onStartClick = onStartClick, onOpenCalendar = onOpenCalendar,
            onOpenQuickSettings = onOpenQuickSettings, onOpenClockStyle = onOpenClockStyle,
            tick = tick, timeFormat24h = timeFormat24h, modifier = modifier
        )
        TaskbarStyle.AERO_7 -> TaskbarAero7(
            theme = theme, taskbarHeight = taskbarHeight, startMenuOpen = startMenuOpen,
            onStartClick = onStartClick, onOpenCalendar = onOpenCalendar,
            onOpenQuickSettings = onOpenQuickSettings, onOpenClockStyle = onOpenClockStyle,
            tick = tick, showSeconds = showSeconds, timeFormat24h = timeFormat24h, modifier = modifier
        )
        TaskbarStyle.MODERN_10 -> TaskbarWin10(
            theme = theme, taskbarHeight = taskbarHeight, startMenuOpen = startMenuOpen,
            onStartClick = onStartClick, onOpenCalendar = onOpenCalendar,
            onOpenQuickSettings = onOpenQuickSettings, onOpenClockStyle = onOpenClockStyle,
            tick = tick, showSeconds = showSeconds, timeFormat24h = timeFormat24h, modifier = modifier
        )
        TaskbarStyle.MICA_11 -> TaskbarMica11(
            theme = theme, taskbarHeight = taskbarHeight, startMenuOpen = startMenuOpen,
            onStartClick = onStartClick, onOpenCalendar = onOpenCalendar,
            onOpenQuickSettings = onOpenQuickSettings, onOpenClockStyle = onOpenClockStyle,
            tick = tick, showSeconds = showSeconds, timeFormat24h = timeFormat24h,
            taskbarCentered = taskbarCentered, modifier = modifier
        )
    }
}

// ============================================================
// 共享数据：固定应用 + 运行窗口
// ============================================================

/** 任务栏固定应用（v2.15：加入扫雷） */
private val TASKBAR_PINNED_IDS = setOf("browser", "file_explorer", "settings", "minesweeper")

@Composable
private fun rememberPinnedApps(): List<com.linbox.core.window.AppDef> =
    remember { AppRegistry.taskbarApps().filter { it.id in TASKBAR_PINNED_IDS } }

/** 固定图标点击：无窗口则打开，有窗口则交给任务栏点击逻辑 */
private fun onPinnedClick(app: com.linbox.core.window.AppDef, wm: WindowManager) {
    val existing = wm.windowsForApp(app.id)
    if (existing.isEmpty()) {
        wm.open(
            appId = app.id,
            title = app.displayName,
            launchMode = app.launchMode,
            initialWidth = app.defaultWidth.value.toInt(),
            initialHeight = app.defaultHeight.value.toInt()
        )
    } else {
        wm.taskbarClick(existing.first().id)
    }
}

// ============================================================
// Win95 经典任务栏
// ============================================================

/**
 * Windows 95 任务栏：
 * - 通栏灰色 #C0C0C0，顶部 1px 白色高光线，紧贴屏幕底部（无悬浮）
 * - 开始按钮：立体凸起（2px bevel）+ 四色旗帜 + 加粗"开始"
 * - 快速启动：固定应用小图标（凹陷分隔槽）
 * - 任务按钮：凸起按钮 = 运行窗口（图标 + 标题文字），激活窗口 = 凹陷态
 * - 托盘：凹陷槽 + 小图标 + 黑色 HH:mm 时钟
 */
@Composable
private fun TaskbarWin95(
    taskbarHeight: Dp,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit,
    tick: Long,
    timeFormat24h: Boolean,
    modifier: Modifier = Modifier
) {
    val wm = remember { WindowManager.get() }
    val runningWindows = remember(tick) { wm.windows.filter { it.isVisible } }
    val pinnedApps = rememberPinnedApps()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(taskbarHeight)
            .win95BarBackground()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ===== 开始按钮（凸起）=====
            Win95StartButton(
                height = taskbarHeight - 6.dp,
                pressed = startMenuOpen,
                onClick = onStartClick
            )

            // ===== 快速启动分隔槽 =====
            Win95GrooveDivider()

            // ===== 快速启动（固定应用小图标）=====
            pinnedApps.forEach { app ->
                Win95QuickLaunchIcon(
                    iconAsset = app.iconAsset,
                    size = taskbarHeight - 16.dp,
                    onClick = { onPinnedClick(app, wm) }
                )
            }

            Win95GrooveDivider()

            // ===== 任务按钮（运行窗口，文字按钮）=====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    runningWindows.forEach { w ->
                        val app = AppRegistry.get(w.appId) ?: return@forEach
                        val isActive = wm.topWindow()?.id == w.id
                        Win95TaskButton(
                            iconAsset = app.iconAsset,
                            title = w.title,
                            height = taskbarHeight - 6.dp,
                            active = isActive,
                            onClick = { wm.taskbarClick(w.id) }
                        )
                    }
                }
            }

            // ===== 托盘（凹陷槽）=====
            Win95Tray(
                taskbarHeight = taskbarHeight,
                tick = tick,
                timeFormat24h = timeFormat24h,
                onOpenCalendar = onOpenCalendar,
                onOpenQuickSettings = onOpenQuickSettings,
                onOpenClockStyle = onOpenClockStyle
            )
        }
    }
}

/** Win95 任务栏背景：灰面 + 顶部白色高光线 */
private fun Modifier.win95BarBackground(): Modifier = drawBehind {
    drawRect(Color(0xFFC0C0C0))
    val line = 1.dp.toPx()
    drawRect(Color(0xFFDFDFDF), size = Size(size.width, line))
    drawRect(Color(0xFF808080), size = Size(size.width, line), topLeft = Offset(0f, line))
}

/** Win95 立体凹凸修饰：raised=凸起 / false=凹陷 */
private fun Modifier.win95Bevel(raised: Boolean, face: Color = Color(0xFFC0C0C0)): Modifier =
    drawBehind {
        drawRect(face)
        val b = 1.dp.toPx()
        val w = size.width
        val h = size.height
        val light = Color(0xFFFFFFFF)
        val mid = Color(0xFF808080)
        if (raised) {
            // 凸起：上左白色高光，下右灰色阴影
            drawRect(light, size = Size(w, b))
            drawRect(light, size = Size(b, h))
            drawRect(mid, size = Size(w, b), topLeft = Offset(0f, h - b))
            drawRect(mid, size = Size(b, h), topLeft = Offset(w - b, 0f))
        } else {
            // 凹陷：上左灰色阴影，下右白色高光
            drawRect(mid, size = Size(w, b))
            drawRect(mid, size = Size(b, h))
            drawRect(light, size = Size(w, b), topLeft = Offset(0f, h - b))
            drawRect(light, size = Size(b, h), topLeft = Offset(w - b, 0f))
        }
    }

/** Win95 分隔槽（垂直凹槽） */
@Composable
private fun Win95GrooveDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .win95Bevel(raised = false)
    )
}

/** Win95 开始按钮：凸起按钮 + 四色旗帜 + 加粗"开始" */
@Composable
private fun Win95StartButton(height: Dp, pressed: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(height)
            .win95Bevel(raised = !pressed)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            WindowsFlagLogo(size = 14.dp)
            Text(
                text = L("开始"),
                color = Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 经典四色 Windows 旗帜徽标（95/XP/7 通用小尺寸绘制） */
@Composable
internal fun WindowsFlagLogo(size: Dp, monochrome: Color? = null) {
    val unit = size / 2f - 0.5.dp
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            Box(Modifier.size(unit).clip(RoundedCornerShape(1.dp)).background(monochrome ?: Color(0xFFFF0000)))
            Box(Modifier.size(unit).clip(RoundedCornerShape(1.dp)).background(monochrome ?: Color(0xFF00A000)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            Box(Modifier.size(unit).clip(RoundedCornerShape(1.dp)).background(monochrome ?: Color(0xFF0000FF)))
            Box(Modifier.size(unit).clip(RoundedCornerShape(1.dp)).background(monochrome ?: Color(0xFFFFC000)))
        }
    }
}

/** Win95 快速启动图标（裸图标 + 点击） */
@Composable
private fun Win95QuickLaunchIcon(iconAsset: String, size: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size + 6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        IconPainter(iconAsset, size = size)
    }
}

/** Win95 任务按钮：凸起（运行）/ 凹陷（激活）+ 图标 + 标题 */
@Composable
private fun Win95TaskButton(
    iconAsset: String,
    title: String,
    height: Dp,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(height)
            .widthIn(min = 96.dp, max = 168.dp)
            .win95Bevel(raised = !active)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconPainter(iconAsset, size = 14.dp)
            Text(
                text = title,
                color = Color.Black,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Win95 托盘：凹陷槽 + 系统小图标 + 黑色时钟 */
@Composable
private fun Win95Tray(
    taskbarHeight: Dp,
    tick: Long,
    timeFormat24h: Boolean,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit
) {
    val timePattern = if (timeFormat24h) "HH:mm" else "hh:mm a"
    val timeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(tick))

    Row(
        modifier = Modifier
            .height((taskbarHeight - 12.dp).coerceAtLeast(28.dp))
            .win95Bevel(raised = false)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 系统图标组 → 快速设置
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenQuickSettings
                )
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(Icons.Default.Wifi, null, tint = Color(0xFF404040), modifier = Modifier.size(13.dp))
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color(0xFF404040), modifier = Modifier.size(13.dp))
        }
        // 时钟 → 日历（长按样式）
        Text(
            text = timeStr,
            color = Color.Black,
            fontSize = 12.sp,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onOpenCalendar() },
                        onLongPress = { onOpenClockStyle() }
                    )
                }
                .padding(horizontal = 2.dp)
        )
    }
}

// ============================================================
// WinXP Luna 任务栏
// ============================================================

/** XP Luna 蓝：任务栏渐变 */
private val XP_BAR_GRADIENT = listOf(Color(0xFF3F8CF3), Color(0xFF2A6BE8), Color(0xFF245EDB))
private val XP_TRAY_GRADIENT = listOf(Color(0xFF2E7BEF), Color(0xFF1F5BD6))
private val XP_BTN_GRADIENT = listOf(Color(0xFF4C97F5), Color(0xFF2C6BE0))
private val XP_BTN_ACTIVE = listOf(Color(0xFF6FB0FA), Color(0xFF3F83EE))
private val XP_START_GREEN = listOf(Color(0xFF5BCE54), Color(0xFF3C9A20), Color(0xFF2E7A16))

/**
 * Windows XP Luna 任务栏：
 * - 通栏蓝色渐变，紧贴底部
 * - 绿色渐变圆角开始按钮 + 白色旗帜 + "开始"（白字带阴影）
 * - 快速启动 + 蓝渐变圆角任务按钮（白字），激活窗口按钮更亮
 * - 托盘：左侧圆角的浅蓝渐变面板 + 白色时钟
 */
@Composable
private fun TaskbarWinXp(
    taskbarHeight: Dp,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit,
    tick: Long,
    timeFormat24h: Boolean,
    modifier: Modifier = Modifier
) {
    val wm = remember { WindowManager.get() }
    val runningWindows = remember(tick) { wm.windows.filter { it.isVisible } }
    val pinnedApps = rememberPinnedApps()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(taskbarHeight)
            .background(Brush.verticalGradient(XP_BAR_GRADIENT))
    ) {
        // 顶部高光线
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF6FAEF8))
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ===== 开始按钮（绿色渐变，右侧圆角）=====
            XpStartButton(
                height = taskbarHeight - 6.dp,
                pressed = startMenuOpen,
                onClick = onStartClick
            )

            // ===== 快速启动 =====
            pinnedApps.forEach { app ->
                Box(
                    modifier = Modifier
                        .size(taskbarHeight - 12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onPinnedClick(app, wm) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconPainter(app.iconAsset, size = taskbarHeight - 20.dp)
                }
            }

            // ===== 任务按钮（蓝渐变圆角 + 文字）=====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    runningWindows.forEach { w ->
                        val app = AppRegistry.get(w.appId) ?: return@forEach
                        val isActive = wm.topWindow()?.id == w.id
                        XpTaskButton(
                            iconAsset = app.iconAsset,
                            title = w.title,
                            height = taskbarHeight - 8.dp,
                            active = isActive,
                            onClick = { wm.taskbarClick(w.id) }
                        )
                    }
                }
            }

            // ===== 托盘（左圆角渐变面板）=====
            XpTray(
                taskbarHeight = taskbarHeight,
                tick = tick,
                timeFormat24h = timeFormat24h,
                onOpenCalendar = onOpenCalendar,
                onOpenQuickSettings = onOpenQuickSettings,
                onOpenClockStyle = onOpenClockStyle
            )
        }
    }
}

/** XP 开始按钮：绿色渐变 + 右侧大圆角 + 白旗"开始" */
@Composable
private fun XpStartButton(height: Dp, pressed: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(height)
            .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
            .background(
                if (pressed) Brush.verticalGradient(XP_BTN_ACTIVE)
                else Brush.verticalGradient(XP_START_GREEN)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            WindowsFlagLogo(size = 15.dp, monochrome = Color.White)
            Text(
                text = L("开始"),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = Shadow(color = Color(0xCC0B3D0B), blurRadius = 2f)
                )
            )
        }
    }
}

/** XP 任务按钮：蓝渐变圆角，激活态更亮 */
@Composable
private fun XpTaskButton(
    iconAsset: String,
    title: String,
    height: Dp,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(height)
            .widthIn(min = 110.dp, max = 180.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                Brush.verticalGradient(if (active) XP_BTN_ACTIVE else XP_BTN_GRADIENT)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            IconPainter(iconAsset, size = 15.dp)
            Text(
                text = title,
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = Shadow(color = Color(0x99123A8A), blurRadius = 1.5f)
                )
            )
        }
    }
}

/** XP 托盘：左圆角浅蓝渐变 + 白色图标时钟 */
@Composable
private fun XpTray(
    taskbarHeight: Dp,
    tick: Long,
    timeFormat24h: Boolean,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit
) {
    val timePattern = if (timeFormat24h) "HH:mm" else "hh:mm a"
    val timeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(tick))

    Row(
        modifier = Modifier
            .height(taskbarHeight - 8.dp)
            .clip(RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp))
            .background(Brush.verticalGradient(XP_TRAY_GRADIENT))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenQuickSettings
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.Wifi, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Icon(Icons.Default.BatteryFull, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Text(
            text = timeStr,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onOpenCalendar() },
                        onLongPress = { onOpenClockStyle() }
                    )
                }
        )
    }
}

// ============================================================
// Win7 Aero 任务栏
// ============================================================

/**
 * Windows 7 Aero 任务栏：
 * - 通栏玻璃质感深蓝渐变（半透明）+ 顶部高光线
 * - 圆形开始球（径向渐变蓝 + 白旗 + 光环）
 * - 图标式任务栏（无文字）：固定 + 运行统一图标，激活 = 亮边辉光
 * - 托盘：白色图标 + 时钟
 */
@Composable
private fun TaskbarAero7(
    theme: WinTheme,
    taskbarHeight: Dp,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    modifier: Modifier = Modifier
) {
    val wm = remember { WindowManager.get() }
    val runningWindows = remember(tick) { wm.windows.filter { it.isVisible } }
    val pinnedApps = rememberPinnedApps()
    val pinnedIds = pinnedApps.map { it.id }.toSet()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(taskbarHeight)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC35586E), Color(0xCC22374A), Color(0xD0162A3C))
                )
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x809FCBF0))
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // ===== 开始圆球 =====
            AeroStartOrb(
                size = taskbarHeight + 6.dp,
                pressed = startMenuOpen,
                onClick = onStartClick
            )

            Spacer(Modifier.width(4.dp))

            // ===== 图标式任务栏：固定 + 运行（非固定） =====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    pinnedApps.forEach { app ->
                        val isActive = wm.topWindow()?.appId == app.id
                        AeroTaskIcon(
                            iconAsset = app.iconAsset,
                            size = taskbarHeight - 12.dp,
                            active = isActive,
                            onClick = { onPinnedClick(app, wm) }
                        )
                    }
                    runningWindows.filter { it.appId !in pinnedIds }.forEach { w ->
                        val app = AppRegistry.get(w.appId) ?: return@forEach
                        val isActive = wm.topWindow()?.id == w.id
                        AeroTaskIcon(
                            iconAsset = app.iconAsset,
                            size = taskbarHeight - 12.dp,
                            active = isActive,
                            onClick = { wm.taskbarClick(w.id) }
                        )
                    }
                }
            }

            // ===== 托盘 =====
            ModernEraTray(
                theme = theme,
                height = taskbarHeight,
                tick = tick,
                showSeconds = showSeconds,
                timeFormat24h = timeFormat24h,
                onOpenCalendar = onOpenCalendar,
                onOpenQuickSettings = onOpenQuickSettings,
                onOpenClockStyle = onOpenClockStyle,
                iconTint = Color.White,
                clockColor = Color.White
            )
        }
    }
}

/** Win7 开始圆球：径向渐变蓝球 + 白旗 + 外光环 */
@Composable
private fun AeroStartOrb(size: Dp, pressed: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF6FD0FF), Color(0xFF1E7BE0), Color(0xFF0B3E8F))
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // 光环
        Box(
            Modifier
                .size(size - 5.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .drawBehind {
                    drawCircle(
                        Color(0x66BFE3FF),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
        )
        WindowsFlagLogo(size = size * 0.32f, monochrome = Color.White)
        if (pressed) {
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
            )
        }
    }
}

/** Win7 图标式任务按钮：玻璃底 + 激活辉光边 */
@Composable
private fun AeroTaskIcon(
    iconAsset: String,
    size: Dp,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size + 6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (active) Brush.verticalGradient(
                    listOf(Color(0x59BEE3FF), Color(0x337FB8E8))
                ) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .drawBehind {
                if (active) {
                    drawRoundRect(
                        color = Color(0xCCAFD9FF),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        IconPainter(iconAsset, size = size)
    }
}

// ============================================================
// Win10 任务栏
// ============================================================

/**
 * Windows 10 任务栏：
 * - 通栏近黑（92% 不透明），紧贴底部
 * - 白色单色四格 Windows 徽标开始按钮（靠左）
 * - 搜索框（宽屏显示 / 窄屏退化为图标）
 * - 图标式任务栏：固定 + 运行，运行 = 底部强调色下划线，激活 = 浅色高亮底
 * - 托盘：白色图标 + 两行时钟（时间 + 日期）
 */
@Composable
private fun TaskbarWin10(
    theme: WinTheme,
    taskbarHeight: Dp,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    modifier: Modifier = Modifier
) {
    val wm = remember { WindowManager.get() }
    val runningWindows = remember(tick) { wm.windows.filter { it.isVisible } }
    val pinnedApps = rememberPinnedApps()
    val pinnedIds = pinnedApps.map { it.id }.toSet()

    var searchText by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    BoxWithConstraints(modifier = modifier) {
        val isNarrow = maxWidth < 600.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(taskbarHeight)
                .background(theme.taskbarColor.copy(alpha = theme.taskbarAlpha))
        ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // ===== 开始按钮（白色单色徽标）=====
            Win10StartButton(
                height = taskbarHeight,
                pressed = startMenuOpen,
                onClick = onStartClick
            )

            // ===== 搜索框 =====
            if (!isNarrow) {
                Win10SearchBox(
                    height = taskbarHeight - 12.dp,
                    text = searchText,
                    active = searchActive,
                    onActiveChange = { searchActive = it },
                    onTextChange = { searchText = it },
                    onSubmit = {
                        val q = searchText.trim()
                        if (q.isNotEmpty()) {
                            val target = if (q.contains(".") && !q.contains(" ")) {
                                if (q.startsWith("http")) q else "https://$q"
                            } else {
                                "https://www.bing.com/search?q=" + android.net.Uri.encode(q)
                            }
                            wm.open(
                                appId = "browser",
                                title = "浏览器",
                                launchMode = com.linbox.core.window.LaunchMode.FLOATING,
                                launchArgs = mapOf("url" to target),
                                initialWidth = 980,
                                initialHeight = 640
                            )
                            searchText = ""
                            searchActive = false
                            keyboard?.hide()
                        }
                    }
                )
            }

            Spacer(Modifier.width(4.dp))

            // ===== 任务图标 =====
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                pinnedApps.forEach { app ->
                    val isRunning = wm.windowsForApp(app.id).isNotEmpty()
                    val isActive = isRunning && wm.topWindow()?.appId == app.id
                    Win10TaskIcon(
                        iconAsset = app.iconAsset,
                        size = taskbarHeight - 14.dp,
                        running = isRunning,
                        active = isActive,
                        onClick = { onPinnedClick(app, wm) }
                    )
                }
                runningWindows.filter { it.appId !in pinnedIds }.forEach { w ->
                    val app = AppRegistry.get(w.appId) ?: return@forEach
                    val isActive = wm.topWindow()?.id == w.id
                    Win10TaskIcon(
                        iconAsset = app.iconAsset,
                        size = taskbarHeight - 14.dp,
                        running = true,
                        active = isActive,
                        onClick = { wm.taskbarClick(w.id) }
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ===== 托盘（v2.16.4：跟随主题配色，适配亮色任务栏） =====
            ModernEraTray(
                theme = theme,
                height = taskbarHeight,
                tick = tick,
                showSeconds = showSeconds,
                timeFormat24h = timeFormat24h,
                onOpenCalendar = onOpenCalendar,
                onOpenQuickSettings = onOpenQuickSettings,
                onOpenClockStyle = onOpenClockStyle,
                iconTint = theme.taskbarIconColor,
                clockColor = theme.taskbarClockColor
            )
        }
        }
    }
}

/** Win10 开始按钮：白色单色四格徽标（v2.16.4：亮色任务栏，按压态改深色高亮） */
@Composable
private fun Win10StartButton(height: Dp, pressed: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(height)
            .clip(RoundedCornerShape(4.dp))
            .background(if (pressed) Color(0x22000000) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // 单色四格（Win10 白色徽标：粗体、无间隙斜切）
        val unit = height * 0.14f
        Column(verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                Box(Modifier.size(unit).background(Color.White))
                Box(Modifier.size(unit).background(Color.White))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                Box(Modifier.size(unit).background(Color.White))
                Box(Modifier.size(unit).background(Color.White))
            }
        }
    }
}

/** Win10 搜索框：v2.16.4 浅色样式（白底灰框深色文字，Windows 10 Light 同源） */
@Composable
private fun Win10SearchBox(
    height: Dp,
    text: String,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(if (active) 230.dp else 180.dp)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFFFFFFFF))
            .border(
                1.dp,
                if (active) Color(0xFF0078D7) else Color(0xFFCCCCCC),
                RoundedCornerShape(2.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onActiveChange(true) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = L("搜索"),
            tint = Color(0xFF666666),
            modifier = Modifier.size(14.dp)
        )
        if (active) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFF1F1F1F), fontSize = 12.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .weight(1f)
                    .keyboardAware(
                        value = { text },
                        onValue = onTextChange,
                        singleLine = true,
                        onEnter = onSubmit
                    ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF0078D7))
            )
        } else {
            Text(L("搜索"), color = Color(0xFF767676), fontSize = 12.sp)
        }
    }
}

/** Win10 任务图标：运行 = 底部下划线，激活 = 高亮底 + 下划线（v2.16.4：亮色适配） */
@Composable
private fun Win10TaskIcon(
    iconAsset: String,
    size: Dp,
    running: Boolean,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size + 10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) Color(0x24000000) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        IconPainter(iconAsset, size = size)
        if (running) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .size(width = if (active) 20.dp else 12.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF0078D7))
            )
        }
    }
}

// ============================================================
// Win11 Mica 悬浮任务栏（保留原有样式）
// ============================================================

/**
 * Win11 Mica 悬浮居中 Dock（v2.11 原样式，v2.15 保留并接入新分发）。
 * 布局：开始 → 搜索 → 固定图标 → 运行任务（中间弹性区）→ 系统托盘（最右）
 */
@Composable
private fun TaskbarMica11(
    theme: WinTheme,
    taskbarHeight: Dp,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    taskbarCentered: Boolean = true,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val taskbarColor = theme.taskbarColor.copy(alpha = theme.taskbarAlpha)
        val floatingWidth = maxWidth * 0.96f
        val isNarrow = maxWidth < 600.dp
        val screenMaxWidth = maxWidth

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .width(floatingWidth)
                    .height(taskbarHeight)
                    .shadow(8.dp, RoundedCornerShape(taskbarHeight.value / 2f))
                    .clip(RoundedCornerShape(taskbarHeight.value / 2f))
                    .background(taskbarColor)
            ) {
                CenteredTaskbar(
                    theme = theme,
                    taskbarHeight = taskbarHeight,
                    startMenuOpen = startMenuOpen,
                    onStartClick = onStartClick,
                    onOpenCalendar = onOpenCalendar,
                    onOpenQuickSettings = onOpenQuickSettings,
                    onOpenClockStyle = onOpenClockStyle,
                    tick = tick,
                    showSeconds = showSeconds,
                    timeFormat24h = timeFormat24h,
                    isNarrow = isNarrow,
                    taskbarCentered = taskbarCentered,
                    maxBarWidth = screenMaxWidth
                )
            }
        }
    }
}

@Composable
private fun CenteredTaskbar(
    theme: WinTheme,
    taskbarHeight: Dp,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    isNarrow: Boolean,
    taskbarCentered: Boolean = true,
    maxBarWidth: Dp = 600.dp
) {
    val wm = remember { WindowManager.get() }
    val runningWindows = remember(tick, theme) { wm.windows }

    var searchText by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    // ===== 托盘时钟样式偏好（长按任务栏时间弹出设置） =====
    val app = LinBoxApp.get()
    val clockMode by app.settingsStore.trayClockMode.collectAsState(initial = "digital")
    val clockFontSize by app.settingsStore.trayClockFontSize.collectAsState(initial = 0f)
    val trayShowDate by app.settingsStore.trayShowDate.collectAsState(initial = true)
    val trayLayout by app.settingsStore.trayLayout.collectAsState(initial = "stacked")

    // ===== 布局：开始 → 搜索 → 固定图标 → 运行任务(弹性区) → 系统托盘(最右) =====
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StartButton(theme = theme, height = taskbarHeight, isOpen = startMenuOpen, onClick = onStartClick)

        if (!isNarrow) {
            Spacer(Modifier.width(4.dp))
            PillSearchBar(
                theme = theme,
                height = taskbarHeight,
                text = searchText,
                active = searchActive,
                onActiveChange = { searchActive = it },
                onTextChange = { searchText = it },
                onSubmit = {
                    val q = searchText.trim()
                    if (q.isNotEmpty()) {
                        val target = if (q.contains(".") && !q.contains(" ")) {
                            if (q.startsWith("http")) q else "https://$q"
                        } else {
                            "https://www.bing.com/search?q=" + android.net.Uri.encode(q)
                        }
                        wm.open(
                            appId = "browser",
                            title = "浏览器",
                            launchMode = com.linbox.core.window.LaunchMode.FLOATING,
                            launchArgs = mapOf("url" to target),
                            initialWidth = 980,
                            initialHeight = 640
                        )
                        searchText = ""
                        searchActive = false
                        keyboard?.hide()
                    }
                }
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(theme.taskbarIconColor.copy(alpha = 0.15f))
            )
            Spacer(Modifier.width(4.dp))
        }

        if (taskbarCentered) {
            Spacer(Modifier.weight(1f))
        }

        val pinnedApps = rememberPinnedApps()
        pinnedApps.forEach { app ->
            val isRunning = wm.windowsForApp(app.id).isNotEmpty()
            val isActive = isRunning && wm.topWindow()?.let { it.appId == app.id && it.isVisible } == true
            TaskbarAppIcon(
                iconAsset = app.iconAsset,
                theme = theme,
                height = taskbarHeight,
                isRunning = isRunning,
                isActive = isActive,
                onClick = { onPinnedClick(app, wm) }
            )
        }

        val pinnedIds = pinnedApps.map { it.id }.toSet()
        val runningOnly = runningWindows.filter { it.appId !in pinnedIds }
        Box(
            modifier = if (taskbarCentered) {
                Modifier
                    .widthIn(max = maxBarWidth * 0.34f)
                    .horizontalScroll(rememberScrollState())
            } else {
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (runningOnly.isNotEmpty()) {
                    Spacer(Modifier.width(2.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(theme.taskbarIconColor.copy(alpha = 0.15f))
                    )
                    Spacer(Modifier.width(2.dp))
                    runningOnly.forEach { w ->
                        val appDef = AppRegistry.get(w.appId)
                        if (appDef != null) {
                            TaskbarAppIcon(
                                iconAsset = appDef.iconAsset,
                                theme = theme,
                                height = taskbarHeight,
                                isRunning = true,
                                isActive = w.isVisible && wm.topWindow()?.id == w.id,
                                onClick = { wm.taskbarClick(w.id) }
                            )
                        }
                    }
                }
            }
        }

        if (taskbarCentered) {
            Spacer(Modifier.weight(1f))
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(theme.taskbarIconColor.copy(alpha = 0.15f))
        )
        Spacer(Modifier.width(2.dp))

        SystemTray(
            theme = theme,
            height = taskbarHeight,
            tick = tick,
            showSeconds = showSeconds,
            timeFormat24h = timeFormat24h,
            clockMode = clockMode,
            clockFontSize = clockFontSize,
            showDate = trayShowDate,
            trayLayout = trayLayout,
            onOpenCalendar = onOpenCalendar,
            onOpenQuickSettings = onOpenQuickSettings,
            onOpenClockStyle = onOpenClockStyle
        )
    }
}

/** Win11 开始按钮（四色徽标圆钮） */
@Composable
private fun StartButton(theme: WinTheme, height: Dp, isOpen: Boolean, onClick: () -> Unit) {
    val bgColor = if (isOpen) theme.accentColor.copy(alpha = 0.3f) else Color.Transparent
    Box(
        modifier = Modifier
            .size(height - 10.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        WindowsFlagLogo(size = (height.value * 0.42f).dp)
    }
}

/**
 * 药丸形搜索条（Win11 风格）：未激活时窄，激活时变宽接受输入。
 */
@Composable
private fun PillSearchBar(
    theme: WinTheme,
    height: Dp,
    text: String,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val width = if (active) 220.dp else 80.dp
    Row(
        modifier = Modifier
            .width(width)
            .height(height - 12.dp)
            .clip(RoundedCornerShape(50))
            .background(theme.taskbarIconColor.copy(alpha = if (active) 0.12f else 0.06f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onActiveChange(true)
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = L("搜索"),
            tint = theme.taskbarIconColor,
            modifier = Modifier.size(14.dp)
        )
        if (active) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = theme.taskbarClockColor,
                    fontSize = 12.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .weight(1f)
                    .keyboardAware(
                        value = { text },
                        onValue = onTextChange,
                        singleLine = true,
                        onEnter = onSubmit
                    ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accentColor)
            )
            if (text.isEmpty()) {
                Text(
                    text = L("搜索"),
                    color = theme.taskbarIconColor.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        } else {
            Text(
                text = L("搜索"),
                color = theme.taskbarIconColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

@Composable
private fun TaskbarAppIcon(
    iconAsset: String,
    theme: WinTheme,
    height: Dp,
    isRunning: Boolean = false,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val iconSize = (height.value * 0.46f).dp.coerceIn(16.dp, 34.dp)
    val bgColor = if (isActive) theme.taskbarIconColor.copy(alpha = 0.15f)
                  else if (isRunning) theme.taskbarIconColor.copy(alpha = 0.06f)
                  else Color.Transparent
    Box(
        modifier = Modifier
            .size(height - 10.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        IconPainter(iconAsset, size = iconSize)
        if (isRunning) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .size(width = if (isActive) 12.dp else 4.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(theme.taskbarIconColor.copy(alpha = if (isActive) 1f else 0.6f))
            )
        }
    }
}

// ============================================================
// 通用托盘（Win7/Win10/Win11 共用；颜色可注入）
// ============================================================

/**
 * 现代托盘：wifi/音量/电池 → 快速设置；时钟 → 日历（长按样式设置）。
 * Win7/10 传入白色 tint；Win11 在 SystemTray 中用主题色。
 */
@Composable
private fun ModernEraTray(
    theme: WinTheme,
    height: Dp,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit,
    iconTint: Color,
    clockColor: Color
) {
    val app = LinBoxApp.get()
    val clockMode by app.settingsStore.trayClockMode.collectAsState(initial = "digital")
    val clockFontSize by app.settingsStore.trayClockFontSize.collectAsState(initial = 0f)
    val trayShowDate by app.settingsStore.trayShowDate.collectAsState(initial = true)
    val trayLayout by app.settingsStore.trayLayout.collectAsState(initial = "stacked")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenQuickSettings
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(Icons.Default.Wifi, contentDescription = "WiFi", tint = iconTint, modifier = Modifier.size((height.value * 0.33f).coerceIn(13f, 18f).dp))
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "音量", tint = iconTint, modifier = Modifier.size((height.value * 0.33f).coerceIn(13f, 18f).dp))
            Icon(Icons.Default.BatteryFull, contentDescription = "电池", tint = iconTint, modifier = Modifier.size((height.value * 0.33f).coerceIn(13f, 18f).dp))
        }

        TrayClock(
            theme = theme,
            height = height,
            tick = tick,
            showSeconds = showSeconds,
            timeFormat24h = timeFormat24h,
            mode = clockMode,
            fontSizePref = clockFontSize,
            showDate = trayShowDate,
            layout = trayLayout,
            onOpenCalendar = onOpenCalendar,
            onOpenClockStyle = onOpenClockStyle,
            clockColorOverride = clockColor
        )
    }
}

/**
 * 系统托盘（Win11 风格，浮动 Dock 内使用）
 * - 点击 wifi/音量/电池 图标组 → Quick Settings
 * - 点击时钟 → Calendar；长按时钟 → 托盘时钟样式设置
 */
@Composable
private fun SystemTray(
    theme: WinTheme,
    height: Dp,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    clockMode: String,
    clockFontSize: Float,
    showDate: Boolean,
    trayLayout: String,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit
) {
    val trayIconSize = (height.value * 0.31f).dp.coerceIn(13.dp, 20.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenQuickSettings
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Wifi, contentDescription = "WiFi", tint = theme.taskbarClockColor, modifier = Modifier.size(trayIconSize))
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "音量", tint = theme.taskbarClockColor, modifier = Modifier.size(trayIconSize))
            Icon(Icons.Default.BatteryFull, contentDescription = "电池", tint = theme.taskbarClockColor, modifier = Modifier.size(trayIconSize))
        }

        Spacer(Modifier.width(2.dp))

        TrayClock(
            theme = theme,
            height = height,
            tick = tick,
            showSeconds = showSeconds,
            timeFormat24h = timeFormat24h,
            mode = clockMode,
            fontSizePref = clockFontSize,
            showDate = showDate,
            layout = trayLayout,
            onOpenCalendar = onOpenCalendar,
            onOpenClockStyle = onOpenClockStyle,
            clockColorOverride = null
        )
    }
}

/**
 * 托盘时钟：
 * - 点击 → 日历弹窗；长按 → 样式设置弹窗
 * - 数字/液晶模式：时间 + 日期文本（两行或单行排版）
 * - 表盘模式：Canvas 绘制模拟时钟（时针/分针/秒针）
 * - clockColorOverride：非 Win11 风格任务栏注入自己的时钟颜色
 */
@Composable
private fun TrayClock(
    theme: WinTheme,
    height: Dp,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    mode: String,
    fontSizePref: Float,
    showDate: Boolean,
    layout: String,
    onOpenCalendar: () -> Unit,
    onOpenClockStyle: () -> Unit,
    clockColorOverride: Color? = null
) {
    val clockColor = clockColorOverride ?: theme.taskbarClockColor
    val timePattern = when {
        showSeconds && timeFormat24h -> "HH:mm:ss"
        showSeconds -> "hh:mm:ss a"
        timeFormat24h -> "HH:mm"
        else -> "hh:mm a"
    }
    val timeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(tick))
    val dateStr = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(tick))

    val timeFontSize = if (fontSizePref >= 8f) fontSizePref.sp
    else (height.value * 0.24f).coerceIn(9f, 13f).sp
    val dateFontSize = (timeFontSize.value - 1f).coerceAtLeast(8f).sp

    val gestureModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = { onOpenCalendar() },
            onLongPress = { onOpenClockStyle() }
        )
    }

    when (mode) {
        "clock" -> {
            val clockSize = (height.value * 0.52f).dp.coerceIn(18.dp, 30.dp)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(gestureModifier)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnalogClockCanvas(
                        size = clockSize,
                        tick = tick,
                        showSeconds = showSeconds,
                        color = clockColor,
                        accent = theme.accentColor
                    )
                    if (showDate) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = dateStr,
                            color = clockColor,
                            fontSize = dateFontSize,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        "lcd" -> {
            val lcdColor = if (theme.isDark) Color(0xFF4AF2A1) else Color(0xFF0B7A4B)
            val lcdStyle = TextStyle(
                color = lcdColor,
                fontSize = timeFontSize,
                fontFamily = FontFamily.Monospace,
                shadow = Shadow(color = lcdColor.copy(alpha = 0.75f), blurRadius = 7f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(gestureModifier)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showDate && layout == "inline") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = timeStr, style = lcdStyle)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = dateStr,
                            style = lcdStyle.copy(
                                fontSize = dateFontSize,
                                shadow = Shadow(color = lcdColor.copy(alpha = 0.5f), blurRadius = 5f)
                            )
                        )
                    }
                } else if (showDate) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(text = timeStr, style = lcdStyle)
                        Text(
                            text = dateStr,
                            style = lcdStyle.copy(
                                fontSize = dateFontSize,
                                shadow = Shadow(color = lcdColor.copy(alpha = 0.5f), blurRadius = 5f)
                            )
                        )
                    }
                } else {
                    Text(text = timeStr, style = lcdStyle)
                }
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(gestureModifier)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showDate && layout == "inline") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = timeStr,
                            color = clockColor,
                            fontSize = timeFontSize,
                            fontWeight = FontWeight.Normal
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = dateStr,
                            color = clockColor.copy(alpha = 0.75f),
                            fontSize = dateFontSize
                        )
                    }
                } else if (showDate) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = timeStr,
                            color = clockColor,
                            fontSize = timeFontSize,
                            fontWeight = FontWeight.Normal
                        )
                        Text(
                            text = dateStr,
                            color = clockColor.copy(alpha = 0.75f),
                            fontSize = dateFontSize
                        )
                    }
                } else {
                    Text(
                        text = timeStr,
                        color = clockColor,
                        fontSize = timeFontSize,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * 表盘时钟：Canvas 绘制圆形表盘 + 时针/分针/秒针
 * （internal：TrayClockSettingsFlyout 预览复用）
 */
@Composable
internal fun AnalogClockCanvas(
    size: Dp,
    tick: Long,
    showSeconds: Boolean,
    color: Color,
    accent: Color
) {
    val cal = remember(tick) { Calendar.getInstance() }
    Canvas(modifier = Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(
            color = color.copy(alpha = 0.9f),
            radius = r,
            center = center,
            style = Stroke(width = r * 0.09f)
        )
        for (i in 0 until 12) {
            val angle = (Math.PI * 2 * i / 12.0) - Math.PI / 2
            val outer = Offset(
                (center.x + r * 0.82f * cos(angle)).toFloat(),
                (center.y + r * 0.82f * sin(angle)).toFloat()
            )
            val inner = Offset(
                (center.x + r * 0.66f * cos(angle)).toFloat(),
                (center.y + r * 0.66f * sin(angle)).toFloat()
            )
            drawLine(
                color = color.copy(alpha = if (i % 3 == 0) 0.9f else 0.45f),
                start = inner,
                end = outer,
                strokeWidth = r * (if (i % 3 == 0) 0.09f else 0.05f),
                cap = StrokeCap.Round
            )
        }
        val hourF = (cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f) / 12f
        val hourAngle = (Math.PI * 2 * hourF) - Math.PI / 2
        drawLine(
            color = color,
            start = center,
            end = Offset(
                (center.x + r * 0.42f * cos(hourAngle)).toFloat(),
                (center.y + r * 0.42f * sin(hourAngle)).toFloat()
            ),
            strokeWidth = r * 0.11f,
            cap = StrokeCap.Round
        )
        val minF = (cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f) / 60f
        val minAngle = (Math.PI * 2 * minF) - Math.PI / 2
        drawLine(
            color = color,
            start = center,
            end = Offset(
                (center.x + r * 0.62f * cos(minAngle)).toFloat(),
                (center.y + r * 0.62f * sin(minAngle)).toFloat()
            ),
            strokeWidth = r * 0.08f,
            cap = StrokeCap.Round
        )
        if (showSeconds) {
            val secF = (cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f) / 60f
            val secAngle = (Math.PI * 2 * secF) - Math.PI / 2
            drawLine(
                color = accent,
                start = center,
                end = Offset(
                    (center.x + r * 0.72f * cos(secAngle)).toFloat(),
                    (center.y + r * 0.72f * sin(secAngle)).toFloat()
                ),
                strokeWidth = r * 0.045f,
                cap = StrokeCap.Round
            )
        }
        drawCircle(color = color, radius = r * 0.07f, center = center)
    }
}
