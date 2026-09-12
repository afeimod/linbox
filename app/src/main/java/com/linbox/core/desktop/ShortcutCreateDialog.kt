package com.linbox.core.desktop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.linbox.core.input.keyboardAware
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppRegistry
import com.linbox.data.model.DesktopItemType
import com.linbox.data.model.Shortcut
import com.linbox.data.model.AppInfo

/**
 * 快捷方式创建对话框。
 *
 * 支持 3 种类型：
 * 1. URL 快捷方式 - 直接输入网址
 * 2. 本地文件快捷方式 - 通过 SAF 选择 .html 文件
 * 3. 应用快捷方式 - 从内置应用列表选择
 *
 * 用户可自定义名称和图标 emoji。
 */
@Composable
fun ShortcutCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (Shortcut) -> Unit
) {
    val theme = LocalWinTheme.current
    var selectedType by remember { mutableStateOf(DesktopItemType.SHORTCUT_URL) }
    var label by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var iconEmoji by remember { mutableStateOf("🔗") }

    // 文件选择器
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            target = uri.toString()
            if (label.isBlank()) label = "本地HTML"
        }
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Box(
            modifier = Modifier
                .size(width = 360.dp, height = 480.dp)
                .background(theme.windowBackgroundColor, RoundedCornerShape(8.dp))
                .border(1.dp, theme.windowBorderColor, RoundedCornerShape(8.dp))
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题
                Text(
                    text = "新建快捷方式",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(20.dp))

                // 类型选择
                Text("类型", color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeChip("网址", selectedType == DesktopItemType.SHORTCUT_URL) {
                        selectedType = DesktopItemType.SHORTCUT_URL
                        iconEmoji = "🔗"
                    }
                    TypeChip("本地HTML", selectedType == DesktopItemType.SHORTCUT_FILE) {
                        selectedType = DesktopItemType.SHORTCUT_FILE
                        iconEmoji = "📄"
                    }
                    TypeChip("应用", selectedType == DesktopItemType.SHORTCUT_APP) {
                        selectedType = DesktopItemType.SHORTCUT_APP
                        iconEmoji = "📱"
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 名称
                Text("名称", color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                InputField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = "我的快捷方式"
                )

                Spacer(Modifier.height(16.dp))

                // 目标
                Text(
                    text = when (selectedType) {
                        DesktopItemType.SHORTCUT_URL -> "网址 URL"
                        DesktopItemType.SHORTCUT_FILE -> "本地 HTML 文件"
                        DesktopItemType.SHORTCUT_APP -> "选择应用"
                        else -> "目标"
                    },
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(4.dp))

                when (selectedType) {
                    DesktopItemType.SHORTCUT_URL -> {
                        InputField(
                            value = target,
                            onValueChange = { target = it },
                            placeholder = "https://www.example.com"
                        )
                    }
                    DesktopItemType.SHORTCUT_FILE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (target.isEmpty()) "未选择文件" else target.takeLast(40),
                                color = if (theme.isDark) Color.White else Color.Black,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { filePicker.launch(arrayOf("text/html", "text/plain", "*/*")) }
                            ) {
                                Text("选择文件", fontSize = 12.sp)
                            }
                        }
                    }
                    DesktopItemType.SHORTCUT_APP -> {
                        // 应用选择列表
                        val apps = remember { AppRegistry.all() }
                        val scrollState = androidx.compose.foundation.rememberScrollState()
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(scrollState)
                        ) {
                            apps.forEach { app ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            target = app.id
                                            if (label.isBlank()) label = app.displayName
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconPainter(app.iconAsset, size = 24.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = app.displayName,
                                        color = if (theme.isDark) Color.White else Color.Black,
                                        fontSize = 12.sp
                                    )
                                    Spacer(Modifier.weight(1f))
                                    if (target == app.id) {
                                        Text("✓", color = theme.accentColor)
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }

                Spacer(Modifier.height(16.dp))

                // 图标选择
                Text("图标", color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("🔗", "📄", "📱", "🌐", "🎮", "📁", "🎵", "📷").forEach { emoji ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (iconEmoji == emoji) theme.accentColor.copy(alpha = 0.3f) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { iconEmoji = emoji },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emoji, fontSize = 18.sp)
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = label.isNotBlank() && target.isNotBlank(),
                        onClick = {
                            onCreate(
                                Shortcut(
                                    label = label,
                                    iconAsset = "emoji:$iconEmoji",
                                    type = selectedType,
                                    target = target
                                )
                            )
                        }
                    ) { Text("创建") }
                }
            }
        }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .background(
                if (selected) theme.accentColor else theme.buttonBackgroundColor,
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else (if (theme.isDark) Color.White else Color.Black),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(theme.buttonBackgroundColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = theme.buttonTextColor.copy(alpha = 0.4f), fontSize = 13.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 13.sp
            ),
            modifier = Modifier
                .fillMaxWidth()
                .keyboardAware(
                    value = { value },
                    onValue = onValueChange,
                    singleLine = true
                )
        )
    }
}
