package com.linbox.apps.music

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v2.21 当前行高亮 / 桌面歌词字体颜色预设（白 + 网易云红 + 经典卡拉OK七色）
 */
val LyricAccentColors = listOf(
    Color(0xFFFFFFFF),
    Color(0xFFEC4141),
    Color(0xFFFFC53D),
    Color(0xFF4DD0E1),
    Color(0xFF66BB6A),
    Color(0xFFBA68C8),
    Color(0xFFFF8A50),
    Color(0xFFF06292)
)

/**
 * 播放器设置中心（v2.18 新增，v2.21 扩充）：
 * - 外观：主页背景（默认/纯色/渐变/自定义图片 + 压暗）—— 全局生效
 *   （侧栏/底栏半透明融合），图片/文件夹选择均拉起 LinBox 桌面文件资源管理器
 * - 歌词秀：背景自定义、封面/光盘/背景三类图片均可自定义、封面模糊度与压暗可调、
 *   3D 倾斜参数（大范围）、逐字左右字体差、当前行高亮颜色、KTV 渐进样式、字号、
 *   行切换动画、高亮发光、翻译显示
 * - 桌面歌词：开关（引导悬浮窗权限）、两行/全屏双模式、字体颜色、背景不透明度、
 *   字号、KTV 逐字变色、全屏歌词行数 1-15（两种模式背景宽度均随歌词自适应；
 *   v2.21.2 起播放器关闭时悬浮窗随之关闭）
 * - 词源：智能回退 / 酷我 / 网易云 / QQ 音乐 / LRCLIB 优先级
 * - 本地扫描：全库 / 仅指定目录（目录列表 + 文件夹选择 + 手动输入）
 * 所有修改即时持久化（onChange → saveMusicSettings），滑条拖动过程中实时生效。
 */
@Composable
fun SettingsPage(
    settings: MusicSettings,
    onChange: (MusicSettings) -> Unit,
    onPickLyricImage: () -> Unit,
    onPickHomeImage: () -> Unit,
    onPickCoverImage: () -> Unit,
    onPickDiscImage: () -> Unit,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
    /** v2.21.3：桌面歌词锁定状态（存 desklyric.json，与位置同文件） */
    lyricLocked: Boolean = false,
    /** v2.21.3：锁定开关回调 —— 写入后唤醒服务重建窗口 */
    onLyricLockChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SettingsSection("主页背景") {
            ModeChipsRow(
                options = listOf(
                    MusicSettings.HOME_BG_DEFAULT to "默认",
                    MusicSettings.BG_SOLID to "纯色",
                    MusicSettings.BG_GRADIENT to "渐变",
                    MusicSettings.BG_IMAGE to "图片"
                ),
                selected = settings.homeBgMode,
                onSelect = { onChange(settings.copy(homeBgMode = it)) }
            )
            when (settings.homeBgMode) {
                MusicSettings.BG_SOLID -> ColorSwatchRow(
                    colors = HomeBgColors,
                    selected = settings.homeBgColor,
                    onSelect = { onChange(settings.copy(homeBgColor = it)) }
                )
                MusicSettings.BG_GRADIENT -> GradientSwatchRow(
                    gradients = HomeBgGradients,
                    selected = settings.homeBgGradient,
                    onSelect = { onChange(settings.copy(homeBgGradient = it)) }
                )
                MusicSettings.BG_IMAGE -> {
                    ImagePickRow(
                        label = "主页背景图片",
                        picked = settings.homeBgImage != null,
                        onPick = onPickHomeImage,
                        onClear = { onChange(settings.copy(homeBgImage = null)) }
                    )
                    SettingSlider(
                        title = "图片压暗",
                        display = "${(settings.homeImageDim * 100).toInt()}%",
                        value = settings.homeImageDim,
                        range = 0f..0.95f,
                        steps = 18,
                        onChange = { onChange(settings.copy(homeImageDim = it)) }
                    )
                }
            }
            Caption("背景全局生效：侧栏、底栏与设置卡片/选项/搜索框自动半透明融入；选深色时文字仍为深色，建议搭配浅色图片或低压暗")
        }

        SettingsSection("歌词秀背景") {
            ModeChipsRow(
                options = listOf(
                    MusicSettings.BG_COVER to "封面模糊",
                    MusicSettings.BG_SOLID to "纯色",
                    MusicSettings.BG_GRADIENT to "渐变",
                    MusicSettings.BG_IMAGE to "图片"
                ),
                selected = settings.lyricBgMode,
                onSelect = { onChange(settings.copy(lyricBgMode = it)) }
            )
            when (settings.lyricBgMode) {
                MusicSettings.BG_COVER -> {
                    // v2.20：封面模糊度与背景压暗强度均可调
                    SettingSlider(
                        title = "封面模糊度",
                        display = "${settings.coverBlur.toInt()}dp",
                        value = settings.coverBlur,
                        range = 0f..60f,
                        steps = 59,
                        onChange = { onChange(settings.copy(coverBlur = it)) }
                    )
                    SettingSlider(
                        title = "背景压暗",
                        display = "${(settings.lyricBgDim * 100).toInt()}%",
                        value = settings.lyricBgDim,
                        range = 0f..0.95f,
                        steps = 18,
                        onChange = { onChange(settings.copy(lyricBgDim = it)) }
                    )
                }
                MusicSettings.BG_SOLID -> ColorSwatchRow(
                    colors = LyricBgColors,
                    selected = settings.lyricBgColor,
                    onSelect = { onChange(settings.copy(lyricBgColor = it)) }
                )
                MusicSettings.BG_GRADIENT -> GradientSwatchRow(
                    gradients = LyricBgGradients,
                    selected = settings.lyricBgGradient,
                    onSelect = { onChange(settings.copy(lyricBgGradient = it)) }
                )
                MusicSettings.BG_IMAGE -> {
                    ImagePickRow(
                        label = "歌词秀背景图片",
                        picked = settings.lyricBgImage != null,
                        onPick = onPickLyricImage,
                        onClear = { onChange(settings.copy(lyricBgImage = null)) }
                    )
                    ImagePickRow(
                        label = "封面图片",
                        picked = settings.coverImage != null,
                        onPick = onPickCoverImage,
                        onClear = { onChange(settings.copy(coverImage = null)) }
                    )
                    ImagePickRow(
                        label = "光盘图片",
                        picked = settings.discImage != null,
                        onPick = onPickDiscImage,
                        onClear = { onChange(settings.copy(discImage = null)) }
                    )
                    SettingSlider(
                        title = "背景压暗",
                        display = "${(settings.lyricBgDim * 100).toInt()}%",
                        value = settings.lyricBgDim,
                        range = 0f..0.95f,
                        steps = 18,
                        onChange = { onChange(settings.copy(lyricBgDim = it)) }
                    )
                }
            }
            Caption("三类图片均可独立自定义：背景铺满歌词页、封面替换左侧卡片、光盘替换旋转碟片盘面；光盘留空时与封面同图，都留空则用歌曲专辑图")
        }

        SettingsSection("3D 歌词") {
            SettingSlider(
                title = "墙面俯仰（绕 X 轴）",
                display = "${settings.wallTiltX.toInt()}°",
                value = settings.wallTiltX,
                range = -45f..45f,
                steps = 89,
                onChange = { onChange(settings.copy(wallTiltX = it)) }
            )
            SettingSlider(
                title = "歌词墙视角（绕 Y 轴）",
                display = "${settings.wallRotateY.toInt()}°",
                value = settings.wallRotateY,
                range = -60f..60f,
                steps = 119,
                onChange = { onChange(settings.copy(wallRotateY = it)) }
            )
            SettingSlider(
                title = "立体纵深强度",
                display = "${settings.tilt3d.toInt()}",
                value = settings.tilt3d,
                range = 0f..45f,
                steps = 44,
                onChange = { onChange(settings.copy(tilt3d = it)) }
            )
            SettingSlider(
                title = "左右字体差（左小右大）",
                display = "${settings.lineYaw3d.toInt()}%",
                value = settings.lineYaw3d,
                range = 0f..45f,
                steps = 44,
                onChange = { onChange(settings.copy(lineYaw3d = it)) }
            )
            Text(
                text = "当前行高亮颜色",
                fontSize = 12.sp,
                color = Mc.textSecondary,
                modifier = Modifier.padding(top = 10.dp)
            )
            ColorSwatchRow(
                colors = LyricAccentColors,
                selected = settings.highlightColor,
                onSelect = { onChange(settings.copy(highlightColor = it)) }
            )
            SettingSwitch(
                title = "KTV 渐进显示",
                desc = "当前行按播放进度从左向右逐字填色（卡拉OK样式，未唱部分半透明灰）",
                checked = settings.ktvMode,
                onChange = { onChange(settings.copy(ktvMode = it)) }
            )
            SettingSlider(
                title = "当前行字号",
                display = "${settings.lyricFontSize}sp",
                value = settings.lyricFontSize.toFloat(),
                range = 12f..60f,
                steps = 47,
                onChange = { onChange(settings.copy(lyricFontSize = it.toInt())) }
            )
            SettingSwitch(
                title = "行切换动画",
                desc = "关闭后歌词行瞬时切换（低性能设备建议关闭）",
                checked = settings.lyricDynamic,
                onChange = { onChange(settings.copy(lyricDynamic = it)) }
            )
            SettingSwitch(
                title = "当前行高亮发光",
                desc = "当前歌词行白色辉光效果",
                checked = settings.lyricGlow,
                onChange = { onChange(settings.copy(lyricGlow = it)) }
            )
            SettingSwitch(
                title = "显示翻译",
                desc = "在歌词行下方显示翻译行（词源提供时）",
                checked = settings.showTranslation,
                onChange = { onChange(settings.copy(showTranslation = it)) }
            )
            Caption("真透视歌词墙：整面墙俯仰/偏航 + 纵深收敛 + 行内逐字左右字体差（行首小行尾大）；俯仰 0° + 视角 0° + 纵深 0 + 字体差 0 即为平面滚动歌词")
        }

        SettingsSection("桌面歌词") {
            SettingSwitch(
                title = "开启桌面歌词",
                desc = "手机桌面悬浮歌词（需授予「显示在应用上层」权限；颜色/透明度/字号拖动即时生效）",
                checked = settings.desktopLyricOn,
                onChange = { enabled ->
                    if (enabled && !Settings.canDrawOverlays(context)) {
                        Toast.makeText(
                            context,
                            "请先授予 LinBox「显示在应用上层」权限，返回后再打开此开关",
                            Toast.LENGTH_LONG
                        ).show()
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"
                                    )).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    } else {
                        onChange(settings.copy(desktopLyricOn = enabled))
                    }
                }
            )
            if (settings.desktopLyricOn) {
                Spacer(Modifier.height(8.dp))
                ModeChipsRow(
                    options = listOf(0 to "两行模式（参考图）", 1 to "桌面全屏歌词"),
                    selected = if (settings.desktopLyricFullscreen) 1 else 0,
                    onSelect = { onChange(settings.copy(desktopLyricFullscreen = it == 1)) }
                )
                Text(
                    text = "字体颜色",
                    fontSize = 12.sp,
                    color = Mc.textSecondary,
                    modifier = Modifier.padding(top = 10.dp)
                )
                ColorSwatchRow(
                    colors = LyricAccentColors,
                    selected = settings.desktopLyricColor,
                    onSelect = { onChange(settings.copy(desktopLyricColor = it)) }
                )
                SettingSlider(
                    title = "背景不透明度",
                    display = "${(settings.desktopLyricBgAlpha * 100).toInt()}%",
                    value = settings.desktopLyricBgAlpha,
                    range = 0f..1f,
                    steps = 19,
                    onChange = { onChange(settings.copy(desktopLyricBgAlpha = it)) }
                )
                SettingSlider(
                    title = "字号",
                    display = "${settings.desktopLyricSize.toInt()}sp",
                    value = settings.desktopLyricSize,
                    range = 14f..40f,
                    steps = 25,
                    onChange = { onChange(settings.copy(desktopLyricSize = it)) }
                )
                SettingSwitch(
                    title = "KTV 逐字变色",
                    desc = "当前行按播放进度从左向右逐字扫色，未唱部分半透明白（两种模式均生效）",
                    checked = settings.desktopLyricKtv,
                    onChange = { onChange(settings.copy(desktopLyricKtv = it)) }
                )
                // v2.21.3：锁定/解锁 —— 锁定后触摸穿透不挡其他应用、记住位置
                SettingSwitch(
                    title = "锁定桌面歌词",
                    desc = "锁定后悬浮窗不拦截触摸（不挡其他应用操作）、记住当前位置；" +
                        "解锁可点通知栏「解锁桌面歌词」或本开关",
                    checked = lyricLocked,
                    onChange = { onLyricLockChange(it) }
                )
                SettingSlider(
                    title = "全屏歌词行数",
                    display = "${settings.desktopLyricLines} 行",
                    value = settings.desktopLyricLines.toFloat(),
                    range = 1f..15f,
                    steps = 14,
                    onChange = { onChange(settings.copy(desktopLyricLines = it.toInt())) }
                )
            }
            Caption(
                "两行模式：通栏悬浮窗 —— 当前行靠屏幕左缘、下一行靠屏幕右缘（留有边距），字号相同，" +
                    "按住可上下拖动，当前行播完两行同时轮换；全屏模式：行数可设的歌词横幅居中。" +
                    "两种模式背景均随歌词自适应宽度，不固定宽；未锁定时右下角有「锁定」按钮，" +
                    "锁定后触摸穿透且记住位置；黑描边字体保证任意壁纸上可读；关闭开关立即消失"
            )
        }

        SettingsSection("歌词词源") {
            for ((id, label) in MusicSettings.ENGINE_LABELS) {
                EngineRadioRow(
                    label = label,
                    selected = settings.lyricEngine == id,
                    onClick = { onChange(settings.copy(lyricEngine = id)) }
                )
            }
            Caption(
                "智能回退顺序：酷我 → 网易云 → QQ 音乐 → LRCLIB；" +
                    "指定词源时该源优先尝试，其余自动兜底，大幅提高冷门歌/外文歌命中率"
            )
        }

        SettingsSection("本地扫描") {
            ModeChipsRow(
                options = listOf(
                    MusicSettings.SCAN_ALL to "扫描全库",
                    MusicSettings.SCAN_DIRS to "仅指定目录"
                ),
                selected = settings.scanMode,
                onSelect = { onChange(settings.copy(scanMode = it)) }
            )
            if (settings.scanMode == MusicSettings.SCAN_DIRS) {
                Spacer(Modifier.height(8.dp))
                if (settings.scanDirs.isEmpty()) {
                    Caption("尚未添加目录：点击下方按钮选择，或直接输入路径（如 /storage/emulated/0/Music）")
                }
                for (dir in settings.scanDirs) {
                    DirRow(dir) {
                        onChange(settings.copy(scanDirs = settings.scanDirs - dir))
                        onRescan()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = Mc.red,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "在文件资源管理器中选择文件夹",
                        fontSize = 13.sp,
                        color = Mc.red,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onPickFolder)
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    )
                }
                ManualDirInput(
                    onAdd = { path ->
                        val p = path.trim().trimEnd('/')
                        if (p.isNotEmpty() && !settings.scanDirs.contains(p)) {
                            onChange(settings.copy(scanDirs = settings.scanDirs + p))
                            onRescan()
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "重新扫描本地音乐",
                    fontSize = 13.sp,
                    color = Mc.red,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRescan)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }
            Caption(
                "指定目录模式只扫描你选择的文件夹，避免把铃声/语音等无关音频扫进曲库；" +
                    "点击选择会拉起桌面【文件资源管理器】，浏览到目标文件夹后点「选定此目录」回传"
            )
        }

        SettingsSection("关于") {
            Caption("音源：酷我（搜索 / 播放 / 下载） · 词源：酷我 / 网易云 / QQ 音乐 / LRCLIB")
            Caption("LinBox 云音乐 v2.21.5 · 界面对照网易云音乐 PC 版 · 3D 歌词秀 · 桌面歌词")
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ==================== 区块与通用控件 ====================

/** 设置区块：标题 + 内容卡片 */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Mc.textPrimary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
    // v2.21.5：自定义主页背景激活时区块卡片半透明融入（白 72%，同侧栏）
    val cardBg = surfaceColor(Color.White, LocalHomeCustomBg.current, 0.72f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        content()
    }
}

/** 说明性小字 */
@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        lineHeight = 15.sp,
        color = Mc.textTertiary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
    )
}

/** 模式选择 chips（横排，红底选中） */
@Composable
private fun ModeChipsRow(
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // v2.21.5：自定义主页背景激活时未选中芯片半透明融入（选中态保持实心红保证对比）
        val chipBg = surfaceColor(Mc.searchFieldBg, LocalHomeCustomBg.current, 0.60f)
        for ((value, label) in options) {
            val isSel = value == selected
            Text(
                text = label,
                fontSize = 12.sp,
                color = if (isSel) Color.White else Mc.textSecondary,
                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSel) Mc.red else chipBg)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

/** 纯色色板（圆形色块，选中描边） */
@Composable
private fun ColorSwatchRow(colors: List<Color>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 10.dp)
    ) {
        for (c in colors) {
            val isSel = c.toArgb() == selected
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(
                        width = if (isSel) 2.dp else 1.dp,
                        color = if (isSel) Mc.red else Mc.divider,
                        shape = CircleShape
                    )
                    .clickable { onSelect(c.toArgb()) }
            )
        }
    }
}

/** 渐变预设色板（胶囊色块，选中描边） */
@Composable
private fun GradientSwatchRow(
    gradients: List<List<Color>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 10.dp)
    ) {
        gradients.forEachIndexed { i, pair ->
            val isSel = i == selected
            Box(
                Modifier
                    .size(width = 38.dp, height = 26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Brush.verticalGradient(pair))
                    .border(
                        width = if (isSel) 2.dp else 1.dp,
                        color = if (isSel) Mc.red else Mc.divider,
                        shape = RoundedCornerShape(13.dp)
                    )
                    .clickable { onSelect(i) }
            )
        }
    }
}

/** 图片选择行（选择/清除 + 状态说明） */
@Composable
private fun ImagePickRow(
    label: String,
    picked: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp)
    ) {
        Icon(
            Icons.Filled.Image,
            contentDescription = null,
            tint = Mc.red,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (picked) "已选择$label（点击可更换）" else "点击打开文件资源管理器选择$label",
            fontSize = 13.sp,
            color = Mc.textPrimary,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onPick)
                .padding(vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (picked) {
            IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "清除",
                    tint = Mc.textTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** 滑条设置行（v2.19：拖动过程中实时 onChange 立即生效并持久化，松手再补一次收尾提交） */
@Composable
private fun SettingSlider(
    title: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    var dragging by remember(value) { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(value) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            color = Mc.textPrimary,
            modifier = Modifier.width(150.dp)
        )
        Slider(
            value = if (dragging) dragValue else value.coerceIn(range.start, range.endInclusive),
            valueRange = range,
            steps = steps,
            onValueChange = {
                dragging = true
                dragValue = it
                // v2.19：拖动实时提交 —— 预览立即生效，且即使 onValueChangeFinished
                // 在个别机型上不回调，最终值也已由最后一次 onValueChange 兼底
                onChange(it)
            },
            onValueChangeFinished = {
                dragging = false
                onChange(dragValue)
            },
            colors = SliderDefaults.colors(
                thumbColor = Mc.red,
                activeTrackColor = Mc.red,
                // v2.21.5：未激活轨道随背景半透明（激活轨道保持实心红）
                inactiveTrackColor = surfaceColor(Color(0xFFE5E5E8), LocalHomeCustomBg.current, 0.70f)
            ),
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
        )
        Text(
            text = display,
            fontSize = 11.sp,
            color = Mc.textSecondary,
            modifier = Modifier.width(58.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Right
        )
    }
}

/** 开关设置行 */
@Composable
private fun SettingSwitch(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, fontSize = 13.sp, color = Mc.textPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Mc.red,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFC9C9CF)
                ),
                modifier = Modifier.height(24.dp)
            )
        }
        Text(text = desc, fontSize = 10.sp, color = Mc.textTertiary)
    }
}

/** 词源单选行 */
@Composable
private fun EngineRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp)
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) Mc.red else Mc.divider, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Mc.red))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text = label, fontSize = 13.sp, color = if (selected) Mc.red else Mc.textPrimary)
    }
}

/** 已添加目录行 */
@Composable
private fun DirRow(path: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = Mc.textSecondary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = path,
            fontSize = 11.sp,
            color = Mc.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Filled.Close,
            contentDescription = "移除目录",
            tint = Mc.textTertiary,
            modifier = Modifier
                .size(14.dp)
                .clickable(onClick = onRemove)
        )
    }
}

/** 手动输入目录路径 */
@Composable
private fun ManualDirInput(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    // v2.21.5：自定义主页背景激活时输入框容器半透明融入
    val fieldBg = surfaceColor(Mc.searchFieldBg, LocalHomeCustomBg.current, 0.60f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = {
                Text("/storage/emulated/0/Music", fontSize = 11.sp, color = Mc.textTertiary)
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                color = Mc.textPrimary
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = fieldBg,
                unfocusedContainerColor = fieldBg,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
        )
        IconButton(onClick = {
            if (text.isNotBlank()) {
                onAdd(text)
                text = ""
            }
        }) {
            Icon(Icons.Filled.Add, contentDescription = "添加目录", tint = Mc.red)
        }
    }
}
