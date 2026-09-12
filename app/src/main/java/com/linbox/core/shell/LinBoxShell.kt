package com.linbox.core.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.LinBoxApp
import com.linbox.core.input.MouseController
import com.linbox.core.input.MouseCursorOverlay
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.input.VirtualKeyboardOverlay
import com.linbox.core.input.gamepad.GamepadOverlay
import com.linbox.core.input.gamepad.GamepadSettingsWindow
import com.linbox.core.theme.IconPainter
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.WindowHost
import com.linbox.core.window.WindowManager

/**
 * LinBox 最小壳层：主屏启动器 + 浮动窗口 + 输入覆盖层。
 *
 * 取代原桌面环境（壁纸/图标网格/任务栏/开始菜单/锁屏均已移除），
 * 只保留本项目需要的两件事：
 * - 终端（Termux 移植版 + 简易终端备用）
 * - X11 图形（显示号 :13，终端 `linbox-x11` 命令调起，窗口自动弹出）
 *
 * 结构（自底向上）：
 * 1. 主屏启动器（应用卡片，窗口全部关闭/最小化时可见）
 * 2. WindowHost（浮动窗口层，承载终端/X11/设置窗口）
 * 3. VirtualKeyboardOverlay（虚拟键盘，可拖动）
 * 4. GamepadOverlay + GamepadSettingsWindow（虚拟游戏手柄）
 * 5. MouseCursorOverlay（虚拟鼠标指针，最顶层）
 *
 * 启动时自动打开一次终端窗口（AutoStartRunner 防重建重复拉起）。
 */
@Composable
fun LinBoxShell() {
    val wm = remember { WindowManager.get() }
    val app = LinBoxApp.get()
    val density = LocalDensity.current

    // 输入设置（触控板模式 / 虚拟鼠标指针 / 右键手势）
    val mouseCursorEnabled by app.settingsStore.mouseCursorEnabled.collectAsState(initial = true)
    val mouseControlMode by app.settingsStore.mouseControlMode.collectAsState(initial = "touch")
    val mouseRightClick by app.settingsStore.mouseRightClick.collectAsState(initial = "twofinger")

    // 窗口列表变化订阅（驱动启动器可见性切换）
    var wmRevision by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { wm.observe { wmRevision++ } }
    val hasOpenWindow = remember(wmRevision) { wm.windows.any { it.isVisible } }

    // View 层触控板路由器接线（职责自原桌面环境平移）：
    // 桌面右键菜单已随桌面移除，onContextMenu 不再注入；
    // 触控板模式的手势仲裁仍由 MainActivity.dispatchTouchEvent → TrackpadRouter 完成。
    SideEffect {
        val newEnabled = mouseControlMode == "trackpad"
        if (TrackpadRouter.enabled && !newEnabled) TrackpadRouter.onDisabled()
        TrackpadRouter.enabled = newEnabled
        TrackpadRouter.longPressRightClick = mouseRightClick == "longpress"
        TrackpadRouter.density = density.density
    }

    // 虚拟鼠标指针初始化（触控板模式下指针即鼠标本体，强制初始化；
    // 具体位置由窗口内的虚拟鼠标 / 注入流驱动）
    SideEffect {
        if (mouseCursorEnabled || mouseControlMode == "trackpad") {
            MouseController.initialize(0f, 0f)
        }
    }

    // ===== 启动自动打开终端（Activity 重建不重复拉起） =====
    LaunchedEffect(Unit) {
        if (!AutoStartRunner.launched) {
            AutoStartRunner.launched = true
            AppRegistry.get("terminal")?.let { def ->
                wm.open(
                    appId = def.id,
                    title = def.displayName,
                    launchMode = def.launchMode,
                    initialWidth = def.defaultWidth.value.toInt(),
                    initialHeight = def.defaultHeight.value.toInt()
                )
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ===== 1. 主屏启动器（无可见窗口时显示） =====
        if (!hasOpenWindow) {
            HomeLauncher()
        }

        // ===== 2. 浮动窗口层（终端 / X11 / 设置） =====
        WindowHost()

        // ===== 3. 虚拟键盘层（可拖动全键盘） =====
        VirtualKeyboardOverlay()

        // ===== 4. 虚拟游戏手柄层（摇杆/十字键/按钮，悬浮设置窗） =====
        GamepadOverlay()
        GamepadSettingsWindow()

        // ===== 5. 虚拟鼠标指针层（最顶层） =====
        MouseCursorOverlay()
    }
}

/**
 * 主屏启动器：LinBox 标识 + 内置应用卡片。
 * 点击卡片经 WindowManager 打开对应窗口（与原桌面/开始菜单同一启动链路）。
 */
@Composable
private fun HomeLauncher() {
    val wm = remember { WindowManager.get() }
    // 固定展示顺序：终端、简易终端、X11、设置
    val apps = remember {
        listOf("terminal", "terminal_sim", "x11", "settings").mapNotNull { AppRegistry.get(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f, fill = false))

        // 标识区
        Text(
            text = "LinBox",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Termux 终端 + X11 图形（显示号 :13）",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp, bottom = 36.dp)
        )

        // 应用卡片（两列网格）
        apps.chunked(2).forEach { rowApps ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                rowApps.forEach { appDef ->
                    LauncherCard(
                        label = appDef.displayName,
                        iconAsset = appDef.iconAsset,
                        onClick = {
                            wm.open(
                                appId = appDef.id,
                                title = appDef.displayName,
                                launchMode = appDef.launchMode,
                                initialWidth = appDef.defaultWidth.value.toInt(),
                                initialHeight = appDef.defaultHeight.value.toInt()
                            )
                        }
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun LauncherCard(
    label: String,
    iconAsset: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(vertical = 24.dp)
    ) {
        IconPainter(asset = iconAsset, size = 48.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}

/**
 * 启动自动拉起标记（Activity 重建时不重复打开终端窗口）。
 */
private object AutoStartRunner {
    @Volatile
    var launched = false
}
