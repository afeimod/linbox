package com.linbox.core.input.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.4.7（用户："虚拟手柄保存和读取配置时候别调用安卓文件管理器啊，
 * 直接保存到 download/linbox/gamepad，保存直接调出输入名字然后确认
 * 或者取消，读取直接读取文件夹下的 vgp 文件"）：
 * `.vgp` 配置读写**去 SAF（安卓文件管理器）**，固定目录
 * `/storage/emulated/0/Download/linbox/gamepad` 直读直写：
 *
 * - **保存**：主菜单「保存配置」→ 输入名字对话框（预填默认名，可清空
 *   重输）→ 确认（写 `download/linbox/gamepad/<名字>.vgp`，重名覆盖）
 *   / 取消。内容 = [GamepadController.toJson()]（版本字段内含）。
 * - **读取**：主菜单「读取配置」→ 列出该目录下全部 `.vgp`（按修改
 *   时间倒序，显示名字+保存时间），点选一条即时装载 + 持久化
 *   DataStore；空目录显示指引文案。
 * - **权限**：App 持 MANAGE_EXTERNAL_STORAGE（所有文件访问），`/storage/
 *   emulated/0/Download` 用 java.io.File 直写（与游戏 exe 外部存储
 *   零拷贝直读同一权限面）。
 * - **结果提示**：[message]（"✅ 已保存 xxx.vgp" / "❌ …"）由宿主
 *   （悬浮球）以 [GamepadIoToast] 气泡展示，2.4s 自动消失。
 *
 * v3.4.6 史：保存/读取先从手柄设置面板顶栏迁入悬浮球主菜单（用户：
 * "读取配置后才可以编辑对应配置"）。`.vgp` = Virtual GamePad 三字母
 * 缩写（用户指定）。
 */
class GamepadProfileIo internal constructor(
    private val dialogState: MutableState<GamepadIoDialog?>,
    private val messageState: MutableState<String?>
) {
    /** 最近一次保存/读取的结果提示（null = 无）。 */
    val message: State<String?> get() = messageState

    /** 调出「输入名字」保存对话框（悬浮球主菜单「保存配置」入口）。 */
    fun saveProfile() {
        dialogState.value = GamepadIoDialog.SAVE
    }

    /** 调出「点选 .vgp」读取对话框（悬浮球主菜单「读取配置」入口）。 */
    fun loadProfile() {
        dialogState.value = GamepadIoDialog.LOAD
    }

    /** 清除结果提示（气泡消失）。 */
    fun clearMessage() {
        messageState.value = null
    }

    /** 关闭当前对话框（取消/完成）。 */
    internal fun dismiss() {
        dialogState.value = null
    }

    companion object {
        /** .vgp 后缀（Virtual GamePad 三字母缩写，用户指定）。 */
        const val EXT = "vgp"

        /** 固定配置目录（用户指定：download/linbox/gamepad）。 */
        internal const val DIR_HINT = "/storage/emulated/0/Download/linbox/gamepad"

        /**
         * 配置目录定位/就绪：优先标准外部存储 Download；不可用（挂载/
         * 权限异常）时退 /sdcard 符号链接路径。目录不存在则建立。
         * 返回 null = 不可写（提示用户检查「所有文件访问」授权）。
         */
        internal fun profileDir(): File? {
            val primary = File("/storage/emulated/0/Download/linbox/gamepad")
            if (primary.isDirectory || primary.mkdirs()) return primary
            val alt = File("/sdcard/Download/linbox/gamepad")
            if (alt.isDirectory || alt.mkdirs()) return alt
            return null
        }

        /** 保存文件名消毒：剥路径分隔/Windows 非法字符，长度截 40。 */
        internal fun sanitizeName(raw: String): String =
            raw.trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .replace(Regex("\\s+"), " ")
                .take(40)
                .trim()

        /** 默认保存名（不含后缀；时间戳避免直接覆盖旧配置）。 */
        internal fun defaultBaseName(): String =
            "linbox-gamepad-${
                SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())
            }"
    }
}

/** 保存/读取对话框的形态。 */
internal enum class GamepadIoDialog { SAVE, LOAD }

/**
 * 悬浮球主菜单用的 .vgp 读写句柄（remember 化：对话框状态与结果提示
 * 一起记忆；文件 IO 经协程作用域在 Dispatchers.IO 落盘/装载，结果写
 * [GamepadProfileIo.message]，对话框 [Dialog] 渲染于本组合点）。
 */
@Composable
fun rememberGamepadProfileIo(onPersistConfig: () -> Unit): GamepadProfileIo {
    val scope = rememberCoroutineScope()
    val message = remember { mutableStateOf<String?>(null) }
    val dialog = remember { mutableStateOf<GamepadIoDialog?>(null) }
    val io = remember { GamepadProfileIo(dialog, message) }

    // v3.4.7：对话框在宿主组合点渲染（Dialog 独立窗口层，悬浮球收起后仍可见）
    dialog.value?.let { kind ->
        key(kind) {
            GamepadIoDialogPanel(kind = kind, io = io, scope = scope, message = message) {
                onPersistConfig()
            }
        }
    }
    // 结果提示 2.4s 自动消失
    LaunchedEffect(io.message.value) {
        if (io.message.value != null) {
            delay(2400)
            io.clearMessage()
        }
    }
    return io
}

/**
 * v3.4.7：保存（输入名字）/读取（点选 .vgp）对话框 —— 深色玻璃面板，
 * 与悬浮球菜单同视觉语系；不调起任何安卓系统文件管理器。
 */
@Composable
private fun GamepadIoDialogPanel(
    kind: GamepadIoDialog,
    io: GamepadProfileIo,
    scope: kotlinx.coroutines.CoroutineScope,
    message: MutableState<String?>,
    onPersistConfig: () -> Unit
) {
    Dialog(
        onDismissRequest = { io.dismiss() },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .width(320.dp)
                .shadow(10.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xF2141A24))
                .padding(16.dp)
        ) {
            when (kind) {
                GamepadIoDialog.SAVE -> SaveNameDialog(io, scope, message)
                GamepadIoDialog.LOAD -> LoadPickDialog(io, scope, message, onPersistConfig)
            }
        }
    }
}

/** 保存：输入名字 → 确认/取消。 */
@Composable
private fun SaveNameDialog(
    io: GamepadProfileIo,
    scope: kotlinx.coroutines.CoroutineScope,
    message: MutableState<String?>
) {
    var name by remember { mutableStateOf(GamepadProfileIo.defaultBaseName()) }
    Column {
        DialogTitle("保存手柄配置")
        Text(
            "保存到 Download/linbox/gamepad（.vgp，重名覆盖）",
            color = Color(0x99B8C7D9),
            fontSize = 11.sp
        )
        Spacer(Modifier.size(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x24FFFFFF))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (name.isEmpty()) {
                Text("输入配置名字", color = Color(0x66B8C7D9), fontSize = 14.sp)
            }
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFFEAF6FF), fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF7FE8DC))
            )
        }
        Spacer(Modifier.size(14.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            DialogButton("取消", neutral = true) { io.dismiss() }
            Spacer(Modifier.width(10.dp))
            DialogButton("确认") {
                val base = GamepadProfileIo.sanitizeName(name)
                    .ifEmpty { GamepadProfileIo.defaultBaseName() }
                val fileName = if (base.endsWith(".${GamepadProfileIo.EXT}")) base
                else "$base.${GamepadProfileIo.EXT}"
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = GamepadProfileIo.profileDir()
                                ?: error("Download 不可写（检查「所有文件访问」授权）")
                            File(dir, fileName).writeText(
                                GamepadController.toJson(), Charsets.UTF_8
                            )
                        }.isSuccess
                    }
                    message.value = if (ok) "✅ 已保存 $fileName"
                    else "❌ 保存失败（Download/linbox/gamepad 不可写）"
                    io.dismiss()
                }
            }
        }
    }
}

/** 读取：点选目录下的 .vgp。 */
@Composable
private fun LoadPickDialog(
    io: GamepadProfileIo,
    scope: kotlinx.coroutines.CoroutineScope,
    message: MutableState<String?>,
    onPersistConfig: () -> Unit
) {
    // 每次打开重新扫描（key 已按对话框实例重置）
    val files = remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            files.value = GamepadProfileIo.profileDir()
                ?.listFiles { f -> f.isFile && f.name.endsWith(".${GamepadProfileIo.EXT}") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }
    }
    Column {
        DialogTitle("读取手柄配置")
        Text(
            "来自 Download/linbox/gamepad（按保存时间倒序）",
            color = Color(0x99B8C7D9),
            fontSize = 11.sp
        )
        Spacer(Modifier.size(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (files.value.isEmpty()) {
                Text(
                    "该文件夹还没有 .vgp 配置。\n先用主菜单「保存配置」存一份，" +
                        "或把 .vgp 文件放进 Download/linbox/gamepad。",
                    color = Color(0x99B8C7D9),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            }
            files.value.forEach { f ->
                val shown = f.name.removeSuffix(".${GamepadProfileIo.EXT}")
                val date = SimpleDateFormat("MM-dd HH:mm", Locale.US)
                    .format(Date(f.lastModified()))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            scope.launch {
                                val json = withContext(Dispatchers.IO) {
                                    runCatching { f.readText(Charsets.UTF_8) }
                                        .getOrDefault("")
                                }
                                val loaded = json.isNotBlank() && runCatching {
                                    GamepadController.loadFromJson(json); true
                                }.getOrDefault(false)
                                message.value = if (loaded) {
                                    // 读入即持久化（跨会话保留）—— 读取后可直接「编辑布局」微调
                                    onPersistConfig()
                                    "✅ 已读取 ${f.name}"
                                } else "❌ 无效 .vgp 文件（${f.name}）"
                                io.dismiss()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0x2E7FE8DC))
                    ) {
                        Text(
                            "VGP",
                            color = Color(0xFF7FE8DC),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(shown, color = Color(0xFFEAF6FF), fontSize = 14.sp, maxLines = 1)
                        Text(date, color = Color(0x66B8C7D9), fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.size(4.dp))
            }
        }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            DialogButton("取消", neutral = true) { io.dismiss() }
        }
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(
        text,
        color = Color(0xFFEAF6FF),
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun DialogButton(
    text: String,
    neutral: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (neutral) Color(0x24FFFFFF) else Color(0x2E7FE8DC))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (neutral) Color(0xCCB8C7D9) else Color(0xFF7FE8DC),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * .vgp 保存/读取结果气泡（挂在悬浮球旁；深色胶囊 + 成功/失败双色）。
 */
@Composable
fun GamepadIoToast(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .shadow(6.dp, RoundedCornerShape(10.dp))
                .background(Color(0xE6101A24))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                message,
                color = if (message.startsWith("✅")) Color(0xFF7FE8DC) else Color(0xFFFFB4B4),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
