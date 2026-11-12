package com.linbox.apps.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.theme.WinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v3.3.40：文件管理器右键菜单（用户需求 4 —— “支持右键功能（以及长按出
 * 右键），包括移动复制重命名解压等常用功能”）。
 *
 * 触发方式（双通道，Windows 语义）：
 *  - **真鼠标右键**（蓝牙/OTG 鼠标的副键按下）—— 每个文件项的
 *    pointerInput 检测 `isSecondaryPressed`，精确到按下位置；
 *  - **长按**（触屏）—— combinedClickable 的 onLongClick —— 菜单在
 *    项目左上角展开（手机/平板场景）。
 *
 * 呈现：Material3 [DropdownMenu]（锚定在文件项自身的 Box 上，本应用
 * 开始菜单/容器管理已有同款通路）+ Win7 视觉（浅灰底 / 方角 / 1px 描边 /
 * 悬停高亮蓝 / 左侧 16sp 图标列）。
 *
 * 条目执行层在 [FileOps]（剪切/复制/粘贴/重命名/删除/解压/压缩/属性/
 * 新建），全部 IO 线程执行、结果 Toast、完成自动刷新列表。
 */

/** 菜单条目（[separator] = 分隔线）。 */
data class FileMenuEntry(
    val label: String = "",
    val icon: String = "",
    val enabled: Boolean = true,
    val separator: Boolean = false,
    val onClick: () -> Unit = {}
)

/** 菜单会话状态（宿主持有；null = 关闭）。 */
internal class FileMenuState {
    var menuFor by mutableStateOf<File?>(null)
    var offset by mutableStateOf(DpOffset.Zero)
    var background by mutableStateOf(false)

    fun open(file: File?, offset: DpOffset, background: Boolean = false) {
        menuFor = file
        this.offset = offset
        this.background = background
    }

    fun close() {
        menuFor = null
        background = false
    }
}

/**
 * 文件项的右键 + 长按手势修饰符。
 *
 * @param onContextMenu 右键/长按回调（参数 = 本项内的按下位置，菜单锚定用）
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.fileItemGestures(
    onClick: () -> Unit,
    onContextMenu: (DpOffset) -> Unit
): Modifier = this
    .combinedClickable(
        onClick = onClick,
        // 长按出右键（触屏）—— 位置不可知，用 (8,8) 近似左上角
        onLongClick = { onContextMenu(DpOffset(8.dp, 8.dp)) }
    )
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            if (down.type == PointerType.Mouse &&
                currentEvent.buttons.isSecondaryPressed
            ) {
                // 消费副键按下：父层（空白区菜单）不再收到，避免双层菜单
                down.consume()
                onContextMenu(DpOffset(down.position.x.dp, down.position.y.dp))
            }
        }
    }

/**
 * 空白区右键（新建文件夹 / 新建文本文档 / 粘贴 / 刷新）。
 * 挂在文件区 Box 上；子项已消费的副键事件不触发。
 */
internal fun Modifier.fileAreaGestures(
    onContextMenu: (DpOffset) -> Unit
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        if (down.type == PointerType.Mouse &&
            currentEvent.buttons.isSecondaryPressed && !down.isConsumed
        ) {
            onContextMenu(DpOffset(down.position.x.dp, down.position.y.dp))
        }
    }
}

/**
 * Win7 视觉上下文菜单（DropdownMenu 锚定宿主 Box）。
 */
@Composable
internal fun FileCtxMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    offset: DpOffset,
    entries: List<FileMenuEntry>,
    theme: WinTheme,
    width: androidx.compose.ui.unit.Dp = 216.dp
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset
    ) {
        Column(
            Modifier
                .width(width)
                .background(if (theme.isDark) Color(0xFF2B2B2B) else Color(0xFFF7F7F7))
                .padding(vertical = 2.dp)
        ) {
            entries.forEach { e ->
                if (e.separator) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .height(1.dp)
                            .background(
                                if (theme.isDark) Color(0xFF454545) else Color(0xFFD8D8D8)
                            )
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp))
                            .clickable(enabled = e.enabled) { e.onClick() }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        // 左侧图标列（Win7 菜单图标列 24dp）
                        Box(Modifier.width(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            if (e.icon.isNotEmpty()) {
                                Text(
                                    e.icon,
                                    fontSize = 14.sp,
                                    color = if (e.enabled) theme.accentColor else theme.secondaryTextColor
                                )
                            }
                        }
                        Text(
                            e.label,
                            fontSize = 13.sp,
                            color = when {
                                !e.enabled -> theme.secondaryTextColor
                                theme.isDark -> Color.White
                                else -> Color.Black
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 快速 Toast（文件操作结果回执）。 */
internal fun toastResult(context: android.content.Context, msg: String, long: Boolean = false) {
    android.widget.Toast.makeText(
        context, msg,
        if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
    ).show()
}

/** IO 操作 + 结果 Toast + 完成回调（文件操作统一执行器）。 */
internal suspend fun runFileOp(
    context: android.content.Context,
    op: () -> String?,
    onDone: () -> Unit
) {
    val err = withContext(Dispatchers.IO) { runCatching { op() }.getOrElse { it.message ?: "操作异常" } }
    withContext(Dispatchers.Main) {
        toastResult(context, err ?: "操作完成 ✓")
        onDone()
    }
}
