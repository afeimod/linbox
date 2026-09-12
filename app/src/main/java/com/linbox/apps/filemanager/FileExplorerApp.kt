package com.linbox.apps.filemanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.linbox.LinBoxApp
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppDef
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import com.linbox.core.window.WindowManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val FileExplorerApp = AppDef(
    id = "file_explorer",
    displayName = "文件资源管理器",
    iconAsset = "app:file_explorer",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 920.dp,
    defaultHeight = 620.dp,
    pinnedToTaskbar = true,
    pinnedToDesktop = true
) { scope ->
    FileExplorerContent(scope)
}

/**
 * 文件资源管理器 - Win11 风格
 *
 * - 不再有虚拟 C:\ 演示盘 — 启动后默认进入「此电脑」首页
 * - 「此电脑」首页：5 个快捷文件夹（文档/下载/音乐/图片/视频） + 内部存储驱动器条目（含容量条）
 * - 侧边栏：快速访问（下载/文档/图片/音乐/视频） + 此电脑 > 内部存储
 * - 顶部工具栏：后退/前进/向上/刷新 + 面包屑地址栏 + 视图切换 + 安装 APK
 * - 主体：左侧导航栏 + 文件网格/列表（黄色文件夹图标网格，符合 Win11 视觉）
 * - 需 MANAGE_EXTERNAL_STORAGE 权限；未授权时显示授权提示
 * - 直接读取 /storage/emulated/0，不调用系统文件管理器
 *
 * v2.19 选择模式新增（云音乐接入桌面选择总线）：
 * - pickMode=image：背景图片选择（云音乐主页/歌词秀背景），直达 Pictures，
 *   点击图片文件 → 经 FilePickBus 回传 targetApp="music" 的发起窗口
 * - pickMode=dir：目录选择（云音乐本地扫描目录），从内部存储根浏览，
 *   横幅提供「选定此目录」按钮，点击将当前所在目录绝对路径回传发起窗口
 */
@Composable
private fun FileExplorerContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current

    // 真实存储根：/storage/emulated/0
    val storageRoot = remember { File("/storage/emulated/0/") }

    // "此电脑" 首页标志 —— true 时显示 5 个快捷文件夹 + 驱动器
    var isThisPcHome by remember { mutableStateOf(true) }
    // 当前真实目录（仅在 isThisPcHome = false 时使用）
    var currentRealDir by remember { mutableStateOf<File?>(null) }
    // 后退 / 前进 栈
    var backStack by remember { mutableStateOf<List<File>>(emptyList()) }
    var forwardStack by remember { mutableStateOf<List<File>>(emptyList()) }

    var isGridView by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) } // 手动触发刷新

    // MANAGE_EXTERNAL_STORAGE 权限检查
    var hasAllFilesAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    // ===== v2.14 壁纸选择模式 =====
    // 个性化→壁纸 卡片不再拉起系统文件选择器，而是打开本应用文件资源管理器；
    // pickMode=wallpaper 时：初始直达 Pictures，点击图片 → 设为壁纸 → 关闭窗口。
    //
    // ===== v2.14.10 文本/媒体选择模式 =====
    // 记事本「打开」/ 媒体播放器「打开文件」也不再调用系统文件选择器（SAF）：
    // pickMode=text → 初始直达 Documents，点击文本文件 → 经 FilePickBus 回传给记事本；
    // pickMode=media → 初始直达 Music（无则 Movies/Download），点击音频/视频 → 回传给媒体播放器。
    // 目标窗口已关闭（无监听者）时兜底：直接开新窗口并传 path。
    val app = LinBoxApp.get()
    val pickMode = scope.windowState.launchArgs["pickMode"] ?: ""
    val targetApp = scope.windowState.launchArgs["targetApp"] ?: ""
    val targetWindow = scope.windowState.launchArgs["targetWindow"] ?: ""
    // v2.18 桌面壁纸选择：wallpaper = 图片或视频均可；wallpaper_video = 仅视频
    val wallpaperPick = pickMode == "wallpaper" || pickMode == "wallpaper_video"
    val wallpaperVideoOnlyPick = pickMode == "wallpaper_video"
    // v2.17 锁屏壁纸选择：图片 / 视频
    val lockWallpaperPick = pickMode == "lock_wallpaper"
    val lockWallpaperVideoPick = pickMode == "lock_wallpaper_video"
    val textPick = pickMode == "text"
    val mediaPick = pickMode == "media"
    // v2.19 云音乐选择模式：image = 背景图片；dir = 本地扫描目录（选定当前所在文件夹）
    val imagePick = pickMode == "image"
    val dirPick = pickMode == "dir"
    if (wallpaperVideoOnlyPick) {
        // v2.18 桌面视频壁纸：直达 Movies → DCIM → Download → 内部存储根
        LaunchedEffect(Unit) {
            val dir = listOf("Movies", "DCIM", "Download")
                .map { File(storageRoot, it) }
                .firstOrNull { it.exists() && it.isDirectory } ?: storageRoot
            currentRealDir = dir
            isThisPcHome = false
        }
    } else if (wallpaperPick || lockWallpaperPick) {
        // 选择模式：直接进入 Pictures（不存在则内部存储根）
        LaunchedEffect(Unit) {
            val pics = File(storageRoot, "Pictures")
            if (pics.exists() && pics.isDirectory) {
                currentRealDir = pics
            } else {
                currentRealDir = storageRoot
            }
            isThisPcHome = false
        }
    } else if (lockWallpaperVideoPick) {
        // 锁屏视频壁纸：直达 Movies → DCIM → Download → 内部存储根
        LaunchedEffect(Unit) {
            val dir = listOf("Movies", "DCIM", "Download")
                .map { File(storageRoot, it) }
                .firstOrNull { it.exists() && it.isDirectory } ?: storageRoot
            currentRealDir = dir
            isThisPcHome = false
        }
    } else if (textPick) {
        // 文本选择：直达 Documents（不存在则内部存储根）
        LaunchedEffect(Unit) {
            val docs = File(storageRoot, "Documents")
            currentRealDir = if (docs.exists() && docs.isDirectory) docs else storageRoot
            isThisPcHome = false
        }
    } else if (mediaPick) {
        // 媒体选择：直达 Music → Movies → Download → 内部存储根（第一个存在的）
        LaunchedEffect(Unit) {
            val dir = listOf("Music", "Movies", "Download")
                .map { File(storageRoot, it) }
                .firstOrNull { it.exists() && it.isDirectory } ?: storageRoot
            currentRealDir = dir
            isThisPcHome = false
        }
    } else if (imagePick) {
        // v2.19 图片选择（云音乐背景）：直达 Pictures（不存在则内部存储根）
        LaunchedEffect(Unit) {
            val pics = File(storageRoot, "Pictures")
            currentRealDir = if (pics.exists() && pics.isDirectory) pics else storageRoot
            isThisPcHome = false
        }
    } else if (dirPick) {
        // v2.19 目录选择（云音乐扫描目录）：从内部存储根开始浏览，
        // 用户浏览到目标文件夹后点横幅「选定此目录」回传
        LaunchedEffect(Unit) {
            currentRealDir = storageRoot
            isThisPcHome = false
        }
    }

    /**
     * v2.14：设为壁纸并关闭选择窗口。
     * v2.18：支持视频壁纸（isVideo=true 时写 video:// 前缀，
     * WallpaperLayer 渲染 TextureView + MediaPlayer 动态桌面）。
     *
     * v2.17 修复"选择图片自定义桌面壁纸不生效"：旧版用 rememberCoroutineScope
     * （绑定窗口组合）启动 DataStore 写入后立即 scope.onClose() 关窗，窗口
     * 组合销毁会取消协程，写入随机丢失。改用应用级 applicationScope，
     * 保证写入必定完成后窗口才被移除。
     */
    fun setWallpaperAndClose(file: File, isVideo: Boolean = false) {
        val uri = if (isVideo) "video://${file.absolutePath}" else "file://${file.absolutePath}"
        app.applicationScope.launch {
            app.settingsStore.setCustomWallpaper(uri)
        }
        Toast.makeText(
            context,
            if (isVideo) "已设为视频壁纸" else "已设为壁纸",
            Toast.LENGTH_SHORT
        ).show()
        scope.onClose()
    }

    /** v2.17：设为锁屏独立壁纸（file:// 图片 / video:// 视频，持久化）并关闭选择窗口 */
    fun setLockWallpaperAndClose(file: File, isVideo: Boolean) {
        val uri = if (isVideo) "video://${file.absolutePath}" else "file://${file.absolutePath}"
        app.applicationScope.launch {
            app.settingsStore.setLockWallpaper(uri)
        }
        Toast.makeText(context, "已设为锁屏壁纸", Toast.LENGTH_SHORT).show()
        scope.onClose()
    }

    /** v2.14.10：把选中的文本/媒体文件回传给发起窗口并关闭选择窗口 */
    fun pickAndClose(file: File) {
        val delivered = FilePickBus.deliver(targetApp, targetWindow, file.absolutePath)
        if (!delivered) {
            // 发起窗口已关闭/最小化：直接开新窗口承载该文件（与文件双击打开同路径）
            when (targetApp) {
                "notepad" -> WindowManager.get().open(
                    appId = "notepad",
                    title = file.name,
                    launchMode = LaunchMode.FLOATING,
                    launchArgs = mapOf("path" to file.absolutePath)
                )
                "media_player" -> WindowManager.get().open(
                    appId = "media_player",
                    title = file.name,
                    launchMode = LaunchMode.FLOATING,
                    launchArgs = mapOf("path" to file.absolutePath),
                    initialWidth = 760,
                    initialHeight = 540
                )
                else -> Toast.makeText(context, "发起窗口已关闭，已取消选择", Toast.LENGTH_SHORT).show()
            }
        }
        scope.onClose()
    }

    /** v2.19：目录选择模式 —— 把当前所在目录绝对路径回传给发起窗口并关闭 */
    fun pickDirAndClose(dir: File) {
        val delivered = FilePickBus.deliver(targetApp, targetWindow, dir.absolutePath)
        if (!delivered) {
            Toast.makeText(context, "发起窗口已关闭，已取消选择", Toast.LENGTH_SHORT).show()
        }
        scope.onClose()
    }

    val allFilesPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasAllFilesAccess = Environment.isExternalStorageManager()
    }

    @Suppress("UNUSED_PARAMETER")
    fun requestAllFilesAccess() {
        runCatching {
            // Android 11+ (API 30) 提供按应用授权的入口；旧版本会抛 NoSuchField，进入 fallback
            val action = Settings::class.java
                .getField("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
                .get(null) as String
            val intent = Intent(action).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            allFilesPermissionLauncher.launch(intent)
        }.onFailure {
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                allFilesPermissionLauncher.launch(intent)
            }.onFailure { e ->
                Toast.makeText(context, "无法打开权限设置: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 当前目录的文件列表（只在浏览真实目录时计算）
    val realItems by produceState(initialValue = emptyList<File>(), currentRealDir, tick, isThisPcHome) {
        if (isThisPcHome) {
            value = emptyList()
            return@produceState
        }
        val dir = currentRealDir ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { dir.listFiles()?.toList() ?: emptyList() }.getOrDefault(emptyList())
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    // APK 文件选择器（手动安装）
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) installApk(context, uri)
    }

    // ===== 导航 =====
    fun openFolder(target: File) {
        if (!hasAllFilesAccess) { requestAllFilesAccess(); return }
        if (!target.exists() || !target.isDirectory) {
            Toast.makeText(context, "目录不存在: ${target.absolutePath}", Toast.LENGTH_SHORT).show()
            return
        }
        backStack = backStack + listOfNotNull(currentRealDir)
        forwardStack = emptyList()
        currentRealDir = target
        isThisPcHome = false
    }

    fun goHome() {
        if (!isThisPcHome) {
            backStack = backStack + listOfNotNull(currentRealDir)
            forwardStack = emptyList()
        }
        isThisPcHome = true
        currentRealDir = null
    }

    fun goBack() {
        if (backStack.isEmpty()) return
        forwardStack = forwardStack + listOfNotNull(currentRealDir)
        // 栈元素均为非空路径（入栈统一走 listOfNotNull），无需空分支
        val prev = backStack.last()
        backStack = backStack.dropLast(1)
        isThisPcHome = false
        currentRealDir = prev
    }

    fun goForward() {
        if (forwardStack.isEmpty()) return
        backStack = backStack + listOfNotNull(currentRealDir)
        val next = forwardStack.last()
        forwardStack = forwardStack.dropLast(1)
        isThisPcHome = false
        currentRealDir = next
    }

    fun goUp() {
        if (isThisPcHome) return
        val current = currentRealDir ?: run { goHome(); return }
        // /storage/emulated/0 的父级不算可读目录；退到首页
        val parent = current.parentFile
        if (parent == null || !parent.canRead() || parent.absolutePath == "/storage/emulated") {
            goHome()
        } else {
            backStack = backStack + current
            forwardStack = emptyList()
            currentRealDir = parent
            isThisPcHome = false
        }
    }

    fun refresh() {
        tick++
    }

    // ===== 布局 =====
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {
        // 窄屏（手机竖屏）阈值：窗口宽度 < 600dp 时启用窄屏布局
        val isNarrow = maxWidth < 600.dp
        // 侧边栏开关：null = 跟随窗口宽度（宽屏默认展开，窄屏默认收起）
        var sidebarOverride by remember { mutableStateOf<Boolean?>(null) }
        val showSidebar = sidebarOverride ?: !isNarrow

    Column(modifier = Modifier.fillMaxSize()) {

        // ===== 顶部工具栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(theme.windowTitleBarColor)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 侧边栏开关（窄屏适配：竖屏时可收起侧栏，把空间让给文件区）
            ToolbarIconButton(
                if (showSidebar) Icons.Default.Menu else Icons.AutoMirrored.Filled.MenuOpen,
                "侧边栏", theme
            ) { sidebarOverride = !showSidebar }
            ToolbarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "后退", theme, enabled = backStack.isNotEmpty()) { goBack() }
            ToolbarIconButton(Icons.AutoMirrored.Filled.ArrowForward, "前进", theme, enabled = forwardStack.isNotEmpty()) { goForward() }
            ToolbarIconButton(Icons.Default.KeyboardArrowUp, "向上", theme, enabled = !isThisPcHome) { goUp() }
            ToolbarIconButton(Icons.Default.Refresh, "刷新", theme) { refresh() }

            Spacer(Modifier.width(8.dp))

            // 地址栏（面包屑）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(theme.cardBackgroundColor)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Computer, null,
                        tint = theme.accentColor, modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    val displayPath = if (isThisPcHome) {
                        "此电脑"
                    } else {
                        "此电脑 > 内部存储" +
                                (currentRealDir?.absolutePath?.removePrefix("/storage/emulated/0") ?: "")
                    }
                    Text(
                        text = displayPath,
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // 搜索框（窄屏隐藏，避免挤占地址栏空间）
            if (!isNarrow) {
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.cardBackgroundColor)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = theme.secondaryTextColor, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("搜索", color = theme.secondaryTextColor, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }

            ToolbarIconButton(
                if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                "切换视图", theme
            ) { isGridView = !isGridView }
            ToolbarIconButton(Icons.Default.Add, "安装 APK", theme) {
                apkPickerLauncher.launch(arrayOf("application/vnd.android.package-archive"))
            }
        }

        // ===== v2.14 壁纸选择模式横幅（v2.17：兼容锁屏壁纸/锁屏视频壁纸；
        //       v2.19：新增云音乐 image/dir 选择模式） =====
        if (wallpaperPick || lockWallpaperPick || lockWallpaperVideoPick || textPick || mediaPick || imagePick || dirPick) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.accentColor.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when {
                        wallpaperPick ->
                            if (wallpaperVideoOnlyPick) Icons.Default.PlayCircle
                            else Icons.Default.Image
                        lockWallpaperPick -> Icons.Default.Image
                        lockWallpaperVideoPick -> Icons.Default.PlayCircle
                        textPick -> Icons.Default.Description
                        imagePick -> Icons.Default.Image
                        dirPick -> Icons.Default.FolderOpen
                        else -> Icons.Default.PlayCircle
                    }, null,
                    tint = theme.accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        wallpaperVideoOnlyPick -> "点击任意视频文件，直接设为桌面动态壁纸"
                        wallpaperPick -> "点击任意图片或视频，直接设为桌面壁纸"
                        lockWallpaperPick -> "点击任意图片，直接设为锁屏壁纸"
                        lockWallpaperVideoPick -> "点击任意视频文件，直接设为锁屏动态壁纸"
                        textPick -> "点击任意文本文件，在记事本中打开"
                        imagePick -> "点击任意图片文件，选为播放器自定义背景"
                        dirPick -> "浏览到目标文件夹后，点右侧「选定此目录」回传给云音乐"
                        else -> "点击音频或视频文件进行播放"
                    },
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 12.sp
                )
                if (dirPick) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "选定此目录",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(theme.accentColor)
                            .clickable {
                                val dir = currentRealDir
                                if (dir == null) {
                                    Toast.makeText(context, "请先进入要选择的文件夹", Toast.LENGTH_SHORT).show()
                                } else {
                                    pickDirAndClose(dir)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // ===== 主体：左侧栏 + 文件区 =====
        Row(modifier = Modifier.weight(1f)) {
            // ===== 左侧导航栏（窄屏可收起） =====
            if (showSidebar) {
            Column(
                modifier = Modifier
                    .width(if (isNarrow) 168.dp else 200.dp)
                    .fillMaxHeight()
                    .background(theme.cardBackgroundColor)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                NavSection("快速访问")
                SidebarItem("⬇️ 下载", false, theme) {
                    openFolder(File(storageRoot, "Download"))
                }
                SidebarItem("📄 文档", false, theme) {
                    openFolder(File(storageRoot, "Documents"))
                }
                SidebarItem("🖼️ 图片", false, theme) {
                    openFolder(File(storageRoot, "Pictures"))
                }
                SidebarItem("🎵 音乐", false, theme) {
                    openFolder(File(storageRoot, "Music"))
                }
                SidebarItem("🎬 视频", false, theme) {
                    openFolder(File(storageRoot, "Movies"))
                }
                SidebarItem("📷 相册 (DCIM)", false, theme) {
                    openFolder(File(storageRoot, "DCIM"))
                }

                Spacer(Modifier.height(12.dp))
                NavSection("设备")
                SidebarItem("💻 此电脑", isThisPcHome, theme) { goHome() }
                SidebarItem(
                    "📱 内部存储",
                    !isThisPcHome && currentRealDir?.absolutePath == storageRoot.absolutePath,
                    theme
                ) { openFolder(storageRoot) }
            }
            }

            // ===== 文件区 =====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(12.dp)
            ) {
                if (!hasAllFilesAccess) {
                    PermissionRequiredState(theme) { requestAllFilesAccess() }
                } else if (isThisPcHome) {
                    // ===== 此电脑 首页 =====
                    ThisPcHomeView(
                        storageRoot = storageRoot,
                        theme = theme,
                        onOpenFolder = { openFolder(it) }
                    )
                } else if (realItems.isEmpty()) {
                    EmptyState(theme, "此文件夹为空")
                } else if (isGridView) {
                    RealFileGrid(realItems, theme) { file ->
                        if (file.isDirectory) openFolder(file)
                        else if (wallpaperPick && isImageExtension(file.extension)) {
                            setWallpaperAndClose(file)
                        } else if (wallpaperPick && isVideoExtension(file.extension)) {
                            // v2.18：壁纸模式点击视频 → 设为动态桌面壁纸
                            setWallpaperAndClose(file, isVideo = true)
                        } else if (lockWallpaperPick && isImageExtension(file.extension)) {
                            setLockWallpaperAndClose(file, isVideo = false)
                        } else if (lockWallpaperVideoPick && isVideoExtension(file.extension)) {
                            setLockWallpaperAndClose(file, isVideo = true)
                        } else if (textPick) {
                            if (isTextExtension(file.extension)) pickAndClose(file)
                            else Toast.makeText(context, "请选择文本文件（txt/log/md/json/xml 等）", Toast.LENGTH_SHORT).show()
                        } else if (imagePick) {
                            if (isImageExtension(file.extension)) pickAndClose(file)
                            else Toast.makeText(context, "请选择图片文件（jpg/png/webp/gif 等）", Toast.LENGTH_SHORT).show()
                        } else if (dirPick) {
                            Toast.makeText(context, "目录选择模式：请点上方「选定此目录」回传当前文件夹", Toast.LENGTH_SHORT).show()
                        } else if (mediaPick) {
                            if (isMediaExtension(file.extension)) pickAndClose(file)
                            else Toast.makeText(context, "请选择音频或视频文件", Toast.LENGTH_SHORT).show()
                        } else openRealFile(context, file)
                    }
                } else {
                    RealFileList(realItems, theme) { file ->
                        if (file.isDirectory) openFolder(file)
                        else if (wallpaperPick && isImageExtension(file.extension)) {
                            setWallpaperAndClose(file)
                        } else if (wallpaperPick && isVideoExtension(file.extension)) {
                            setWallpaperAndClose(file, isVideo = true)
                        } else if (lockWallpaperPick && isImageExtension(file.extension)) {
                            setLockWallpaperAndClose(file, isVideo = false)
                        } else if (lockWallpaperVideoPick && isVideoExtension(file.extension)) {
                            setLockWallpaperAndClose(file, isVideo = true)
                        } else if (textPick) {
                            if (isTextExtension(file.extension)) pickAndClose(file)
                            else Toast.makeText(context, "请选择文本文件（txt/log/md/json/xml 等）", Toast.LENGTH_SHORT).show()
                        } else if (imagePick) {
                            if (isImageExtension(file.extension)) pickAndClose(file)
                            else Toast.makeText(context, "请选择图片文件（jpg/png/webp/gif 等）", Toast.LENGTH_SHORT).show()
                        } else if (dirPick) {
                            Toast.makeText(context, "目录选择模式：请点上方「选定此目录」回传当前文件夹", Toast.LENGTH_SHORT).show()
                        } else if (mediaPick) {
                            if (isMediaExtension(file.extension)) pickAndClose(file)
                            else Toast.makeText(context, "请选择音频或视频文件", Toast.LENGTH_SHORT).show()
                        } else openRealFile(context, file)
                    }
                }
            }
        }

        // ===== 底部状态栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(theme.windowTitleBarColor)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isThisPcHome) "此电脑" else "${realItems.size} 个项目",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (isGridView) "网格视图" else "列表视图",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
    }
    }
}

// ============================================================
// 此电脑首页：5 个快捷文件夹 + 内部存储驱动器条目
// ============================================================
@Composable
private fun ThisPcHomeView(
    storageRoot: File,
    theme: com.linbox.core.theme.WinTheme,
    onOpenFolder: (File) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // ===== 文件夹区（窄屏可横向滑动，解决竖屏图标显示不全） =====
        SectionHeader("文件夹 (5)")
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val folders = listOf(
                SpecialFolder("文档", "📄", File(storageRoot, "Documents")),
                SpecialFolder("下载", "⬇️", File(storageRoot, "Download")),
                SpecialFolder("音乐", "🎵", File(storageRoot, "Music")),
                SpecialFolder("图片", "🖼️", File(storageRoot, "Pictures")),
                SpecialFolder("视频", "🎬", File(storageRoot, "Movies"))
            )
            folders.forEach { folder ->
                SpecialFolderCell(folder, theme) { onOpenFolder(folder.file) }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ===== 设备和驱动器区 =====
        SectionHeader("设备和驱动器")
        Spacer(Modifier.height(6.dp))
        DriveCell(
            label = "内部存储 (C:)",
            theme = theme,
            storageRoot = storageRoot,
            onClick = { onOpenFolder(storageRoot) }
        )
    }
}

private data class SpecialFolder(val name: String, val icon: String, val file: File)

@Composable
private fun SectionHeader(title: String) {
    val theme = LocalWinTheme.current
    Text(
        text = title,
        color = if (theme.isDark) Color.White else Color.Black,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SpecialFolderCell(folder: SpecialFolder, theme: com.linbox.core.theme.WinTheme, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        // 文件夹图标（黄色 Win11 风格）
        Box(
            modifier = Modifier
                .size(56.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Folder, null,
                tint = Color(0xFFDCA84A),
                modifier = Modifier.size(56.dp)
            )
            // 在图标中央叠一个 emoji 标识（符合 Win11 的文件夹封面预览风格）
            Text(text = folder.icon, fontSize = 18.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = folder.name,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun DriveCell(
    label: String,
    theme: com.linbox.core.theme.WinTheme,
    storageRoot: File,
    onClick: () -> Unit
) {
    // 计算磁盘容量
    val (totalGB, valAvailGB) = remember(storageRoot.absolutePath) {
        runCatching {
            val stat = StatFs(storageRoot.absolutePath)
            val total = stat.totalBytes
            val avail = stat.availableBytes
            Pair(total / (1024L * 1024L * 1024L), avail / (1024L * 1024L * 1024L))
        }.getOrDefault(Pair(0L, 0L))
    }
    val usedGB = totalGB - valAvailGB
    val usagePct = if (totalGB > 0) (usedGB.toFloat() / totalGB.toFloat()).coerceIn(0f, 1f) else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 驱动器图标
        Icon(
            Icons.Default.Storage, null,
            tint = if (theme.isDark) Color(0xFF9CC4E8) else Color(0xFF0078D4),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            // 容量条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(theme.cardBackgroundColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(usagePct)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(theme.accentColor)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$valAvailGB GB 可用（共 $totalGB GB）",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
    }
}

// ============================================================
// 通用 UI 子组件
// ============================================================

@Composable
private fun NavSection(title: String) {
    val theme = LocalWinTheme.current
    Text(
        title,
        color = theme.secondaryTextColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp, top = 4.dp)
    )
}

@Composable
private fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    theme: com.linbox.core.theme.WinTheme,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, desc,
            tint = if (!enabled) theme.secondaryTextColor.copy(alpha = 0.4f)
                   else if (theme.isDark) Color.White else Color.Black,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun SidebarItem(label: String, active: Boolean, theme: com.linbox.core.theme.WinTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .background(if (active) theme.accentColor.copy(alpha = 0.15f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (active) theme.accentColor else (if (theme.isDark) Color.White else Color.Black),
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyState(theme: com.linbox.core.theme.WinTheme, message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.FolderOpen, null,
                tint = theme.secondaryTextColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(message, color = theme.secondaryTextColor, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PermissionRequiredState(theme: com.linbox.core.theme.WinTheme, onGrant: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(
                Icons.Default.Lock, null,
                tint = theme.accentColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "需要文件访问权限",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "为了让文件资源管理器直接读取 /storage/emulated/0 下的真实文件（图片、音乐、视频、文档等），请授予「所有文件访问权限」。",
                color = theme.secondaryTextColor,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGrant) { Text("授予权限") }
        }
    }
}

// ============================================================
// 真实文件系统的网格/列表渲染
// ============================================================

@Composable
private fun RealFileGrid(items: List<File>, theme: com.linbox.core.theme.WinTheme, onClick: (File) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items, key = { it.absolutePath }) { item ->
            FileGridCell(
                name = item.name,
                isDir = item.isDirectory,
                extension = item.extension,
                sizeText = if (item.isDirectory) "" else formatSize(item.length()),
                imagePath = if (!item.isDirectory && isImageExtension(item.extension)) item.absolutePath else null,
                theme = theme
            ) { onClick(item) }
        }
    }
}

@Composable
private fun RealFileList(items: List<File>, theme: com.linbox.core.theme.WinTheme, onClick: (File) -> Unit) {
    LazyColumn {
        items(items, key = { it.absolutePath }) { item ->
            FileListRow(
                name = item.name, isDir = item.isDirectory,
                extension = item.extension,
                sizeText = if (item.isDirectory) "" else formatSize(item.length()),
                imagePath = if (!item.isDirectory && isImageExtension(item.extension)) item.absolutePath else null,
                theme = theme
            ) { onClick(item) }
        }
    }
}

@Composable
private fun FileGridCell(
    name: String, isDir: Boolean, extension: String, sizeText: String,
    imagePath: String? = null,
    theme: com.linbox.core.theme.WinTheme, onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isDir) {
                Icon(
                    Icons.Default.Folder, null,
                    tint = Color(0xFFDCA84A),
                    modifier = Modifier.size(56.dp)
                )
            } else if (imagePath != null) {
                // 图片文件直接显示缩略图预览
                ImageThumbnail(path = imagePath, size = 56.dp, corner = 6.dp)
            } else {
                Text(text = iconForExtension(extension), fontSize = 36.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (sizeText.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(sizeText, color = theme.secondaryTextColor, fontSize = 9.sp)
        }
    }
}

@Composable
private fun FileListRow(
    name: String, isDir: Boolean, extension: String, sizeText: String,
    imagePath: String? = null,
    theme: com.linbox.core.theme.WinTheme, onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDir) {
            Icon(Icons.Default.Folder, null, tint = Color(0xFFDCA84A), modifier = Modifier.size(18.dp))
        } else if (imagePath != null) {
            ImageThumbnail(path = imagePath, size = 20.dp, corner = 3.dp)
        } else {
            Text(text = iconForExtension(extension), fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (sizeText.isNotEmpty()) {
            Text(sizeText, color = theme.secondaryTextColor, fontSize = 11.sp)
        }
    }
}

/**
 * 图片缩略图（v2.9 新增）：IO 线程采样解码，避免大图 OOM / 主线程卡顿。
 * 解码失败时回退到 emoji 占位。
 */
@Composable
private fun ImageThumbnail(path: String, size: Dp, corner: Dp) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
        )
    } else {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Text(text = "🖼️", fontSize = (size.value * 0.6f).sp)
        }
    }
}

private fun isImageExtension(ext: String): Boolean =
    ext.lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

/** 视频文件扩展名（v2.17：锁屏视频壁纸选择模式过滤用） */
private fun isVideoExtension(ext: String): Boolean =
    ext.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts")

/** 文本文件扩展名（v2.14.10：记事本「打开」选择模式过滤用） */
private fun isTextExtension(ext: String): Boolean = ext.lowercase() in setOf(
    "txt", "log", "md", "json", "xml", "csv", "ini", "cfg", "conf",
    "properties", "yml", "yaml", "html", "htm", "js", "ts", "css", "java", "kt", "py", "sh", "bat"
)

/** 媒体文件扩展名（v2.14.10：媒体播放器「打开文件」选择模式过滤用） */
private fun isMediaExtension(ext: String): Boolean {
    val e = ext.lowercase()
    return e in AUDIO_FILE_EXTS || e in VIDEO_FILE_EXTS
}

/**
 * 打开真实文件：
 * - 图片 → 内置图片查看器
 * - HTML/HTM → 内置浏览器
 * - 文本 → 内置记事本（v2.14.10，不再调系统应用）
 * - 音频/视频 → 内置媒体播放器（v2.9）
 * - APK → 系统安装器（FileProvider）
 * - PDF/其他 → 系统 ACTION_VIEW Intent（FileProvider）
 */
private fun openRealFile(context: Context, file: File) {
    val ext = file.extension.lowercase()
    try {
        when {
            ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp") -> {
                WindowManager.get().open(
                    appId = "image_viewer",
                    title = file.name,
                    launchMode = LaunchMode.FLOATING,
                    launchArgs = mapOf("path" to file.absolutePath)
                )
            }
            // HTML → 浏览器（须先于文本分支判断，html 也在文本扩展名集合内）
            ext == "html" || ext == "htm" -> {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                WindowManager.get().open(
                    appId = "browser", title = "Browser",
                    launchMode = LaunchMode.FLOATING,
                    launchArgs = mapOf("url" to uri.toString())
                )
            }
            // 文本 → 内置记事本（v2.14.10）
            isTextExtension(ext) -> {
                WindowManager.get().open(
                    appId = "notepad",
                    title = file.name,
                    launchMode = LaunchMode.FLOATING,
                    launchArgs = mapOf("path" to file.absolutePath)
                )
            }
            // 音频/视频 → 内置媒体播放器（v2.9）
            ext in AUDIO_FILE_EXTS || ext in VIDEO_FILE_EXTS -> {
                WindowManager.get().open(
                    appId = "media_player",
                    title = file.name,
                    launchMode = LaunchMode.FLOATING,
                    launchArgs = mapOf("path" to file.absolutePath),
                    initialWidth = 760,
                    initialHeight = 540
                )
            }
            ext == "apk" -> {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                installApk(context, uri)
            }
            else -> {
                val mime = mimeForExtension(ext)
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    Toast.makeText(context, "未找到可打开 ${file.extension} 文件的应用", Toast.LENGTH_SHORT).show()
                }
            }
        }
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, "无法获取文件 URI: ${e.message}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "打开文件失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun mimeForExtension(ext: String): String = when (ext) {
    "mp3", "wav", "ogg", "m4a", "flac" -> "audio/*"
    "mp4", "avi", "mov", "mkv", "3gp", "webm" -> "video/*"
    "txt", "log", "md" -> "text/plain"
    "pdf" -> "application/pdf"
    "doc", "docx" -> "application/msword"
    "xls", "xlsx" -> "application/vnd.ms-excel"
    "ppt", "pptx" -> "application/vnd.ms-powerpoint"
    "zip", "rar", "7z" -> "application/zip"
    else -> "*/*"
}

private fun iconForExtension(ext: String): String = when (ext.lowercase()) {
    "apk" -> "📦"
    "html", "htm" -> "🌐"
    "txt" -> "📄"
    "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "🖼️"
    "mp3", "wav", "ogg", "m4a", "flac" -> "🎵"
    "mp4", "avi", "mov", "mkv", "3gp", "webm" -> "📺"
    "pdf" -> "📕"
    "exe", "msi" -> "⚙️"
    "zip", "rar", "7z" -> "🗜️"
    "doc", "docx" -> "📘"
    "xls", "xlsx" -> "📗"
    "ppt", "pptx" -> "📙"
    else -> "📄"
}

/** 音频文件扩展名（与媒体播放器一致） */
private val AUDIO_FILE_EXTS = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "mid", "wma", "opus")

/** 视频文件扩展名（与媒体播放器一致） */
private val VIDEO_FILE_EXTS = setOf("mp4", "avi", "mov", "mkv", "3gp", "webm", "ts", "m4v", "flv")

private fun formatSize(len: Long): String = when {
    len < 1024 -> "$len B"
    len < 1024 * 1024 -> "${len / 1024} KB"
    else -> "${len / (1024 * 1024)} MB"
}

/**
 * 启动系统安装程序安装 APK 文件
 */
private fun installApk(context: Context, uri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "无法启动安装程序: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
