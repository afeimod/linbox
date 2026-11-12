package com.linbox.apps.containers

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppDef
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v3.2.13（用户问题 3）：文件管理器双击 exe/bat/msi/cmd/com 调出的
 * 【选择容器运行】窗口。
 *
 * 入口：FileExplorerApp.openRealFile 的 Windows 可执行文件分支 ——
 * 旧版落到"系统 ACTION_VIEW Intent"，必然"未找到可打开 .exe 文件的应用"；
 * 现改为打开本窗口（launchArgs["path"] = 完整 Unix 路径）。
 *
 * 内容（用户指定四要素）：
 *  - 选择对应的容器运行：容器单选列表（名称 + 后端/wine/运行态摘要，
 *    默认容器预选）；
 *  - 分辨率设置：虚拟分辨率药丸组（默认回填所选容器的配置，可覆盖）；
 *  - 窗口设置：显示模式药丸组（跟随窗口 / 固定分辨率 / 虚拟桌面 / 独立窗口）；
 *  - 启动参数：自由文本（透传给程序）。
 *
 * 运行：以 container.copy(dmode=…, screen=…) 的【会话级覆盖】启动
 * （不回写 container.conf —— 单次运行参数不该污染容器持久配置），
 * 经 WineSessionLauncher.runExe 原生链路（X11 自动拉起、前缀自动初始化）。
 * exe 路径直接传 Unix 绝对路径（rootfsLoader 的 resolveExeWorkingDir
 * 已支持 /storage/… 且 CWD 切到 exe 所在目录，media 等相对路径可解析）。
 */
val ExeRunChooserApp = AppDef(
    id = "exe_run_chooser",
    displayName = "选择容器运行",
    iconAsset = "app:file_explorer",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 560.dp,
    defaultHeight = 620.dp,
    pinnedToTaskbar = false,
    pinnedToDesktop = false
) { scope ->
    ExeRunChooserContent(scope)
}

@Composable
internal fun ExeRunChooserContent(scope: WindowContentScope) {
    val context = LocalContext.current
    val theme = LocalWinTheme.current
    val exePath = scope.windowState.launchArgs["path"] ?: ""

    var containers by remember { mutableStateOf<List<ContainerData>?>(null) }
    var selected by remember { mutableStateOf<ContainerData?>(null) }
    var screen by remember { mutableStateOf<String?>(null) }     // null = 跟随容器配置
    var dmode by remember { mutableStateOf<DisplayMode?>(null) } // null = 跟随容器配置
    var args by remember { mutableStateOf("") }

    // 容器列表 + 默认容器预选（IO 线程加载）
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { ContainerManager.list() }
        containers = list
        if (selected == null) {
            selected = list.firstOrNull { ContainerManager.isDefault(it.name) } ?: list.firstOrNull()
        }
    }

    // 分辨率/显示模式跟随所选容器（切换容器时回填其配置）
    LaunchedEffect(selected?.name) {
        selected?.let {
            screen = it.screen
            dmode = it.dmode
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.windowBackgroundColor)
            .padding(12.dp)
    ) {
        // ---- 目标程序 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📦", fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text("运行 Windows 程序", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.windowTitleBarTextColor)
                Text(
                    exePath.ifEmpty { "（未指定程序路径）" },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = theme.secondaryTextColor,
                    maxLines = 2
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        val loaded = containers
        if (loaded == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                        color = theme.accentColor
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("正在读取容器…", fontSize = 12.sp, color = theme.secondaryTextColor)
                }
            }
        } else if (loaded.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无 Bionic 容器", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.windowTitleBarTextColor)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "请先打开「Bionic 容器」管理器新建一个容器，再回来运行本程序",
                        fontSize = 11.sp, color = theme.secondaryTextColor
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---- 容器选择 ----
                ChooserSection("选择容器", "${loaded.size} 个可用") {
                    loaded.forEach { c ->
                        val sel = selected?.name == c.name
                        val running = WineSessionLauncher.activeSessions.containsKey(c.name)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (sel) theme.accentColor.copy(alpha = 0.12f)
                                    else theme.cardBackgroundColor
                                )
                                .border(
                                    1.dp,
                                    if (sel) theme.accentColor.copy(alpha = 0.6f) else theme.windowBorderColor.copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selected = c }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 单选指示
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .border(
                                        1.5.dp,
                                        if (sel) theme.accentColor else theme.secondaryTextColor,
                                        RoundedCornerShape(7.dp)
                                    )
                                    .padding(3.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (sel) theme.accentColor else Color.Transparent)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        c.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.windowTitleBarTextColor
                                    )
                                    if (ContainerManager.isDefault(c.name)) {
                                        Spacer(Modifier.width(4.dp))
                                        Text("★", fontSize = 11.sp, color = theme.accentColor)
                                    }
                                    if (running) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("● 运行中", fontSize = 10.sp, color = theme.accentColor)
                                    }
                                }
                                Text(
                                    "${c.backend.label} · ${c.dmode.label}" +
                                        (if (c.dmode != DisplayMode.OFF && c.screen != "native") " ${c.screen}" else "") +
                                        " · wine ${c.wine.ifEmpty { "默认" }}",
                                    fontSize = 10.sp,
                                    color = theme.secondaryTextColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // ---- 显示设置（分辨率 + 窗口模式） ----
                ChooserSection("显示设置", "覆盖本次运行") {
                    ChooserField("显示模式", dmode?.desc) {
                        ChooserPills(
                            options = DisplayMode.entries.map { it.id to it.label },
                            selected = dmode?.id ?: DisplayMode.OFF.id
                        ) { dmode = DisplayMode.fromId(it) }
                    }
                    ChooserField(
                        "分辨率",
                        "显示模式选\"跟随窗口\"时分辨率不生效"
                    ) {
                        ChooserPills(
                            options = ContainerData.SCREEN_CHOICES.map { it to if (it == "native") "跟随窗口" else it },
                            selected = screen ?: "native"
                        ) { v ->
                            screen = v
                            // 与容器编辑一致的联动：非 native 分辨率 + 跟随模式 → 自动固定分辨率
                            if (v != "native" && dmode == DisplayMode.OFF) dmode = DisplayMode.D
                        }
                    }
                }

                // ---- 启动参数 ----
                ChooserSection("启动参数", "可空") {
                    OutlinedTextField(
                        value = args,
                        onValueChange = { args = it },
                        label = { Text("传给程序的命令行参数") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ---- 底部运行按钮 ----
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val canRun = selected != null && exePath.isNotEmpty()
                if (!canRun) {
                    Text(
                        if (exePath.isEmpty()) "程序路径缺失" else "请选择容器",
                        fontSize = 11.sp, color = theme.secondaryTextColor
                    )
                    Spacer(Modifier.width(10.dp))
                }
                OutlinedButton(
                    onClick = { scope.onClose() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("取消", fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = canRun,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accentColor),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    onClick = {
                        val container = selected ?: return@Button
                        // 会话级覆盖：不回写 container.conf（单次运行参数不污染持久配置）
                        val overridden = container.copy(
                            dmode = dmode ?: container.dmode,
                            screen = screen ?: container.screen
                        )
                        val err = WineSessionLauncher.runExe(
                            context, overridden, exePath, args.trim()
                        )
                        if (err == null) {
                            Toast.makeText(context, "已在容器「${container.name}」中启动", Toast.LENGTH_SHORT).show()
                            scope.onClose()
                        } else {
                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text("运行", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 内部组件（与 ContainerScreens 的 WinSectionCard/WinField/WinOptionPills
// 同视觉语言；独立文件私有实现避免跨文件可见性耦合）
// ---------------------------------------------------------------------------

@Composable
private fun ChooserSection(
    title: String,
    subtitle: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val theme = LocalWinTheme.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 13.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(theme.accentColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.windowTitleBarTextColor)
            Spacer(Modifier.weight(1f))
            if (subtitle != null) {
                Text(subtitle, fontSize = 10.sp, color = theme.secondaryTextColor)
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(theme.cardBackgroundColor)
                .border(0.5.dp, theme.windowBorderColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun ChooserField(
    label: String,
    hint: String? = null,
    content: @Composable () -> Unit
) {
    val theme = LocalWinTheme.current
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.windowTitleBarTextColor)
        if (hint != null) {
            Text(hint, fontSize = 10.sp, color = theme.secondaryTextColor)
        }
        Spacer(Modifier.height(5.dp))
        content()
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChooserPills(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val theme = LocalWinTheme.current
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (sel) theme.accentColor else theme.windowBackgroundColor
                    )
                    .border(
                        0.5.dp,
                        if (sel) theme.accentColor else theme.windowBorderColor.copy(alpha = 0.7f),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    color = if (sel) Color.White
                    else if (theme.isDark) Color.White else Color.Black,
                    maxLines = 1
                )
            }
        }
    }
}
