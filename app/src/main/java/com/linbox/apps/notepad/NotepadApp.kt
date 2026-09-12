package com.linbox.apps.notepad

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.apps.filemanager.FilePickBus
import com.linbox.core.input.keyboardAwareEditor
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppDef
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import com.linbox.core.window.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val NotepadApp = AppDef(
    id = "notepad",
    displayName = "记事本",
    iconAsset = "app:notepad",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 620.dp,
    defaultHeight = 460.dp,
    pinnedToDesktop = true
) { scope ->
    NotepadContent(scope)
}

/**
 * 记事本 - Win11 风格重构
 *
 * v2.14.10 真实文件化（修复"不能打开/编辑/查看/保存文件"）：
 * - 「文件」菜单：新建 / 打开 / 保存 / 另存为 —— 全部真实读写
 *   /storage/emulated/0（无权限时自动回退应用外部私有目录）；
 * - 「打开」拉起【应用内文件资源管理器】文本选择模式（不再弹系统选择器），
 *   选中文本文件经 FilePickBus 回传本窗口加载；
 * - 文件资源管理器双击文本文件同样用记事本打开（launchArgs.path）；
 * - 未保存修改追踪（标题 * 前缀 + 状态栏提示），新建/打开前确认；
 * - 「编辑」菜单：全选 / 清空；「查看」切换自动换行显示；
 * - 编辑区支持虚拟键盘光标级编辑（TextFieldValue，v2.13）。
 */
@Composable
private fun NotepadContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val wm = remember { WindowManager.get() }
    val scope0 = rememberCoroutineScope()

    // ===== 编辑状态 =====
    // v2.13：TextFieldValue 状态，支持虚拟键盘光标级编辑（插入/删除/方向键/全选/复制粘贴）
    var text by remember { mutableStateOf(TextFieldValue("")) }
    /** 当前打开的文件（null = 未命名新文档） */
    var currentFile by remember { mutableStateOf<File?>(null) }
    /** 有未保存的修改 */
    var dirty by remember { mutableStateOf(false) }
    var wrapText by remember { mutableStateOf(true) }
    var fontSize by remember { mutableStateOf(14) }

    // ===== 菜单 / 对话框状态 =====
    var fileMenuOpen by remember { mutableStateOf(false) }
    var editMenuOpen by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveFileName by remember { mutableStateOf("") }
    /** 未保存确认后挂起的动作（新建/打开），保存完成后执行 */
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    /** 公共文档目录（真实存储） */
    val documentsDir = remember { File("/storage/emulated/0/Documents") }

    fun refreshTitle() {
        val name = currentFile?.name ?: "未命名"
        scope.onTitleChange(if (dirty) "*$name - 记事本" else "$name - 记事本")
    }

    /** 读取文件内容到编辑区（IO 线程） */
    fun loadFile(file: File) {
        scope0.launch {
            withContext(Dispatchers.IO) { runCatching { file.readText(Charsets.UTF_8) } }
                .onSuccess { content ->
                    text = TextFieldValue(content)
                    currentFile = file
                    dirty = false
                    refreshTitle()
                }
                .onFailure {
                    Toast.makeText(context, "无法读取文件: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /**
     * 把内容写入目标文件（IO 线程）。
     * 公共目录写入失败（未授予"所有文件"权限等）时自动回退应用外部私有目录。
     * @param content 显式快照 —— 避免异步写期间编辑区状态被后续动作清空导致写入空内容
     */
    fun writeToFile(target: File, content: String, afterSaved: (() -> Unit)? = null) {
        scope0.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    target.parentFile?.mkdirs()
                    target.writeText(content, Charsets.UTF_8)
                    target
                }
            }.onSuccess { saved ->
                currentFile = saved
                dirty = false
                refreshTitle()
                Toast.makeText(context, "已保存: ${saved.absolutePath}", Toast.LENGTH_SHORT).show()
                afterSaved?.invoke()
            }.onFailure { e ->
                // 回退：应用外部私有目录（无需任何权限）
                val fallbackDir = File(context.getExternalFilesDir(null), "Notepad")
                withContext(Dispatchers.IO) {
                    runCatching {
                        fallbackDir.mkdirs()
                        val fallback = File(fallbackDir, target.name)
                        fallback.writeText(content, Charsets.UTF_8)
                        fallback
                    }
                }.onSuccess { saved ->
                    currentFile = saved
                    dirty = false
                    refreshTitle()
                    Toast.makeText(
                        context,
                        "已保存到应用目录: ${saved.absolutePath}（公共目录无权限）",
                        Toast.LENGTH_LONG
                    ).show()
                    afterSaved?.invoke()
                }.onFailure {
                    Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 保存：已有文件原地覆盖，未命名走另存为 */
    fun save() {
        val f = currentFile
        if (f != null) {
            writeToFile(f, text.text)
        } else {
            saveFileName = "新建文本文件.txt"
            showSaveDialog = true
        }
    }

    fun saveAs() {
        saveFileName = currentFile?.name ?: "新建文本文件.txt"
        showSaveDialog = true
    }

    fun newFile() {
        text = TextFieldValue("")
        currentFile = null
        dirty = false
        refreshTitle()
    }

    /** 拉起应用内文件资源管理器（文本选择模式）—— 不再调用系统文件选择器 */
    fun openPicker() {
        wm.open(
            appId = "file_explorer",
            title = "打开文本文件",
            launchMode = AppRegistry.get("file_explorer")?.launchMode ?: LaunchMode.FLOATING,
            launchArgs = mapOf(
                "pickMode" to "text",
                "targetApp" to "notepad",
                "targetWindow" to scope.windowState.id
            ),
            initialWidth = 920,
            initialHeight = 620
        )
    }

    /** 有未保存修改时先确认，再执行动作（新建/打开） */
    fun confirmIfDirty(action: () -> Unit) {
        if (dirty) pendingAction = action else action()
    }

    // ===== 文件选择总线：资源管理器选中文本文件 → 回传本窗口 =====
    DisposableEffect(Unit) {
        val unlisten = FilePickBus.listen("notepad", scope.windowState.id) { path ->
            loadFile(File(path))
        }
        onDispose { unlisten() }
    }

    // ===== 初始文件（文件资源管理器双击文本文件 / 选择模式兜底开新窗口） =====
    val initialPath = scope.windowState.launchArgs["path"]
    LaunchedEffect(initialPath) {
        if (initialPath != null) {
            val f = File(initialPath)
            if (f.exists() && f.isFile) {
                loadFile(f)
            } else {
                Toast.makeText(context, "文件不存在: $initialPath", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 初始标题
    LaunchedEffect(Unit) { refreshTitle() }

    Column(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {

        // ===== 顶部菜单栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(theme.windowTitleBarColor)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 文件菜单（v2.14.10：真实文件操作）
            Box {
                MenuButton("文件", theme) { fileMenuOpen = !fileMenuOpen }
                DropdownMenu(
                    expanded = fileMenuOpen,
                    onDismissRequest = { fileMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("新建") },
                        leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            fileMenuOpen = false
                            confirmIfDirty { newFile() }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("打开...") },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            fileMenuOpen = false
                            confirmIfDirty { openPicker() }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("保存") },
                        leadingIcon = { Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            fileMenuOpen = false
                            save()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("另存为...") },
                        leadingIcon = { Icon(Icons.Default.SaveAlt, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            fileMenuOpen = false
                            saveAs()
                        }
                    )
                }
            }
            // 编辑菜单（v2.14.10：全选/清空）
            Box {
                MenuButton("编辑", theme) { editMenuOpen = !editMenuOpen }
                DropdownMenu(
                    expanded = editMenuOpen,
                    onDismissRequest = { editMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("全选") },
                        leadingIcon = { Icon(Icons.Default.SelectAll, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            editMenuOpen = false
                            text = text.copy(selection = TextRange(0, text.text.length))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("清空") },
                        leadingIcon = { Icon(Icons.Default.Clear, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            editMenuOpen = false
                            text = TextFieldValue("")
                            dirty = true
                            refreshTitle()
                        }
                    )
                }
            }
            MenuButton("查看", theme) { wrapText = !wrapText }
            Spacer(Modifier.weight(1f))
            // 字号控制
            IconButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(8) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.TextDecrease, null, tint = if (theme.isDark) Color.White else Color.Black, modifier = Modifier.size(14.dp))
            }
            Text(
                "${fontSize}sp",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            IconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(36) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.TextIncrease, null, tint = if (theme.isDark) Color.White else Color.Black, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = { confirmIfDirty { newFile() } }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, "新建", tint = if (theme.isDark) Color.White else Color.Black, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = { save() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Save, "保存", tint = if (theme.isDark) Color.White else Color.Black, modifier = Modifier.size(14.dp))
            }
            // 未保存提示（v2.14.10：替代旧版假的"已保存"时间戳）
            Text(
                if (dirty) "未保存" else "已保存",
                color = if (dirty) theme.accentColor else theme.secondaryTextColor,
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        // ===== 编辑区 =====
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(theme.windowBackgroundColor)
                .padding(8.dp)
        ) {
            if (text.text.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    Text(
                        "开始输入文本，或通过「文件 > 打开」选择文件...",
                        color = theme.secondaryTextColor.copy(alpha = 0.5f),
                        fontSize = fontSize.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (!dirty) {
                        dirty = true
                        refreshTitle()
                    }
                },
                textStyle = TextStyle(
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = fontSize.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = (fontSize + 4).sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accentColor),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .keyboardAwareEditor(
                        value = { text },
                        onValue = { text = it },
                        singleLine = false
                    )
                    .padding(4.dp)
            )
        }

        // ===== 底部状态栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(theme.windowTitleBarColor)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // v2.14.10：显示当前文件路径
            Text(
                currentFile?.path ?: "未命名",
                color = theme.secondaryTextColor,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.weight(1f))
            Text(
                "行 ${text.text.count { it == '\n' } + 1}  字符 ${text.text.length}  字 ${text.text.toCharArray().filter { !it.isWhitespace() }.size}",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 10.sp
            )
            Spacer(Modifier.width(12.dp))
            Text("UTF-8", color = theme.secondaryTextColor, fontSize = 10.sp)
            Spacer(Modifier.width(12.dp))
            Text("${fontSize}sp", color = theme.secondaryTextColor, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            if (wrapText) {
                Text("自动换行", color = theme.accentColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    // ===== 另存为对话框 =====
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("另存为") },
            text = {
                Column {
                    Text(
                        "保存位置：${documentsDir.path}",
                        fontSize = 12.sp,
                        color = theme.secondaryTextColor
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveFileName,
                        onValueChange = { saveFileName = it },
                        singleLine = true,
                        label = { Text("文件名") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        var name = saveFileName.trim()
                        if (name.isNotEmpty()) {
                            if (!name.contains('.')) name += ".txt"
                            showSaveDialog = false
                            writeToFile(File(documentsDir, name), text.text, pendingAction)
                            pendingAction = null
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    pendingAction = null
                }) { Text("取消") }
            }
        )
    }

    // ===== 未保存修改确认对话框 =====
    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("未保存的更改") },
            text = { Text("是否保存对「${currentFile?.name ?: "未命名"}」的更改？") },
            confirmButton = {
                TextButton(onClick = {
                    val f = currentFile
                    val content = text.text
                    pendingAction = null
                    if (f != null) {
                        // 已有文件：保存后继续挂起动作（content 快照避免竞态）
                        writeToFile(f, content, action)
                    } else {
                        // 未命名：走另存为，保存完成后执行挂起动作
                        saveFileName = "新建文本文件.txt"
                        showSaveDialog = true
                        pendingAction = action
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingAction = null
                        action()
                    }) { Text("放弃") }
                    TextButton(onClick = { pendingAction = null }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun MenuButton(label: String, theme: com.linbox.core.theme.WinTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp
        )
    }
}
