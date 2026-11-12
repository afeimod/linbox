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
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
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
    // v3.3.8（用户问题 2）：改名「此电脑」+ 电脑图标（原「文件资源管理器」）
    displayName = "此电脑",
    iconAsset = "app:this_pc",
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
    // v3.2.13（用户问题 2）：容器「运行 exe」窗口的文件选择模式 —— 点击
    // exe/bat/msi/cmd/com 文件把【完整路径】经 FilePickBus 回传给发起窗口
    // 的程序路径输入框（直达 Download，无则内部存储根）
    val exePick = pickMode == "exe"
    // v3.3.17（用户问题：装 wrapper 驱动提示选 txt/log）：驱动包选择模式 ——
    // 容器管理「图形驱动 ➕ 安装驱动」专用。旧版复用 pickMode="text"（记事本
    // 文本选择：直达 Documents、按文本扩展名过滤）→ 提示“请选择文本文件
    // （txt/log/md…）”牛头不对马嘴。现改为独立模式：直达 Download，点击
    // zip/tar/tar.gz/tar.xz/tar.bz2 驱动包回传（WrapperDriverSupport.install
    // 支持的全部包形态）。
    val driverPick = pickMode == "driver"
    // v3.2.14（用户问题 2）：自定义启动动画选择 —— 点击视频文件直接设为
    // app 启动动画（持久化到 SettingsStore；直达 Movies → Download）
    val bootVideoPick = pickMode == "boot_video"
    if (exePick) {
        LaunchedEffect(Unit) {
            val dir = listOf("Download", "LinBox").map { File(storageRoot, it) }
                .firstOrNull { it.exists() && it.isDirectory } ?: storageRoot
            currentRealDir = dir
            isThisPcHome = false
        }
    } else if (driverPick) {
        // v3.3.17：驱动包选择 —— 直达 Download（无则内部存储根），与 exe 选择同款起点
        LaunchedEffect(Unit) {
            val dir = listOf("Download").map { File(storageRoot, it) }
                .firstOrNull { it.exists() && it.isDirectory } ?: storageRoot
            currentRealDir = dir
            isThisPcHome = false
        }
    } else if (wallpaperVideoOnlyPick) {
        // v2.18 桌面视频壁纸：直达 Movies → DCIM → Download → 内部存储根
        LaunchedEffect(Unit) {
            val dir = listOf("Movies", "DCIM", "Download")
                .map { File(storageRoot, it) }
                .firstOrNull { it.exists() && it.isDirectory } ?: storageRoot
            currentRealDir = dir
            isThisPcHome = false
        }
    } else if (bootVideoPick) {
        // v3.2.14 启动动画选择：直达 Movies → Download → 内部存储根
        LaunchedEffect(Unit) {
            val dir = listOf("Movies", "Download")
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

    /**
     * v3.2.14：设为自定义启动动画（file:// 绝对路径，持久化到 SettingsStore）
     * 并关闭选择窗口。应用级 applicationScope 保证写入完成后窗口才被移除
     * （同 setWallpaperAndClose 的 v2.17 修复理由：窗口组合销毁会取消协程）。
     */
    fun setBootVideoAndClose(file: File) {
        app.applicationScope.launch {
            app.settingsStore.setCustomBootAnimation("file://${file.absolutePath}")
        }
        Toast.makeText(context, "已设为启动动画（下次启动生效）", Toast.LENGTH_SHORT).show()
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
        // v3.3.8：C 盘（/data/data/com.linbox/files 应用内部存储）无需
        // 所有文件权限即可浏览；其余外部路径维持权限门禁
        val isAppInternalPath = target.absolutePath.startsWith("/data/data/com.linbox/")
        if (!hasAllFilesAccess && !isAppInternalPath) { requestAllFilesAccess(); return }
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
        // v3.3.8：C/D/E 盘根目录的上一级 = 此电脑首页（盘符是顶级节点；
        // 也避免从 C 盘退进 /data/data/com.linbox 应用沙箱内部目录）
        val driveRoots = setOf(
            "/data/data/com.linbox/files",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/exagear"
        )
        if (current.absolutePath in driveRoots) { goHome(); return }
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

    /**
     * v3.3.8：面包屑路径 → 盘符映射（C/D/E + 外部存储兼容）。
     * C 盘（/data/data/com.linbox/files）独立成段；D/E 盘显示专属段；
     * 其余 /storage/emulated/0 下路径沿用「内部存储」。
     */
    // ===== v3.3.40：右键 / 长按上下文菜单（用户需求 4 —— 移动/复制/重命名/
    //       解压等常用操作；真鼠标右键 + 触屏长按双通道） =====
    val cs = rememberCoroutineScope()
    val ctxMenu = remember { FileMenuState() }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameText by remember { mutableStateOf("") }
    var propsTarget by remember { mutableStateOf<File?>(null) }
    var propsData by remember { mutableStateOf<FileOps.Props?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    // 选择模式（壁纸/文本/exe 等）不弹菜单 —— 不干扰既有选择语义
    val menuAllowed = pickMode.isEmpty()

    fun closeCtx() { ctxMenu.close() }

    /** 打开单项菜单（真右键带精确位置 / 长按用近似左上角）。 */
    fun openFileMenu(file: File, offset: DpOffset) {
        ctxMenu.open(file, offset)
    }

    fun runOp(op: () -> String?) {
        cs.launch { runFileOp(context, op) { refresh() } }
    }

    /** 单项菜单条目（Win7 资源管理器同款集合）。 */
    fun fileEntries(file: File): List<FileMenuEntry> {
        val dir = file.isDirectory
        val ext = file.extension.lowercase()
        val canExtract = !dir && FileOps.isArchive(ext)
        val unsupportedArchive = !dir && FileOps.isUnsupportedArchive(ext)
        return buildList {
            add(FileMenuEntry("打开", "📂") {
                closeCtx()
                if (dir) openFolder(file)
                else openRealFile(context, file)
            })
            add(FileMenuEntry(separator = true))
            add(FileMenuEntry("剪切", "✂️") {
                closeCtx()
                FileOps.Clipboard.set(FileOps.Clipboard.Mode.CUT, listOf(file))
                toastResult(context, "已剪切：${file.name}（在目标文件夹右键粘贴）")
            })
            add(FileMenuEntry("复制", "📋") {
                closeCtx()
                FileOps.Clipboard.set(FileOps.Clipboard.Mode.COPY, listOf(file))
                toastResult(context, "已复制：${file.name}（在目标文件夹右键粘贴）")
            })
            if (dir) {
                add(FileMenuEntry(
                    "粘贴", "📥",
                    enabled = FileOps.Clipboard.hasContent()
                ) {
                    closeCtx()
                    val cur = currentRealDir
                    if (cur == null || !cur.isDirectory) {
                        toastResult(context, "请先进入一个文件夹再粘贴")
                        return@FileMenuEntry
                    }
                    val (mode, files) = FileOps.Clipboard.pending() ?: return@FileMenuEntry
                    runOp { FileOps.copyIn(files, cur, move = mode == FileOps.Clipboard.Mode.CUT) }
                })
            }
            add(FileMenuEntry("重命名", "✏️") {
                closeCtx()
                renameTarget = file
                renameText = file.name
            })
            add(FileMenuEntry("删除", "🗑️") {
                closeCtx()
                deleteTarget = file
            })
            if (canExtract || unsupportedArchive) {
                add(FileMenuEntry(separator = true))
                add(
                    FileMenuEntry(
                        "解压到此处", "📦",
                        enabled = canExtract
                    ) {
                        closeCtx()
                        val parent = file.parentFile ?: return@FileMenuEntry
                        // 解到以压缩包名命名的子文件夹（Windows 资源管理器同款）
                        val dest = File(parent, file.nameWithoutExtension)
                        runCatching { dest.mkdirs() }
                        toastResult(context, "开始解压：${file.name}（大文件请稍候）")
                        runOp {
                            val err = FileOps.extract(file, dest)
                            if (err == null) "解压完成 ✓ → ${dest.name}/" else err
                        }
                    }
                )
            }
            if (!dir) {
                add(FileMenuEntry(separator = true))
                add(FileMenuEntry("添加到压缩文件（.zip）", "🗜️") {
                    closeCtx()
                    toastResult(context, "开始压缩：${file.name}.zip")
                    runOp {
                        val err = FileOps.zipTo(file)
                        if (err == null) "压缩完成 ✓" else err
                    }
                })
            }
            add(FileMenuEntry(separator = true))
            add(FileMenuEntry("属性", "ℹ️") {
                closeCtx()
                cs.launch {
                    val data = withContext(Dispatchers.IO) { FileOps.props(file) }
                    propsData = data
                    propsTarget = file
                }
            })
        }
    }

    /** 空白区菜单条目（新建 / 粘贴 / 刷新）。 */
    fun bgEntries(): List<FileMenuEntry> = buildList {
        add(FileMenuEntry("新建文件夹", "📁") {
            closeCtx()
            val cur = currentRealDir
            if (cur == null || !cur.isDirectory) {
                toastResult(context, "请先进入一个文件夹")
                return@FileMenuEntry
            }
            runOp {
                val made = FileOps.mkdir(File(cur, "新建文件夹"))
                if (made != null) null else "新建文件夹失败（权限）"
            }
        })
        add(FileMenuEntry("新建文本文档", "📄") {
            closeCtx()
            val cur = currentRealDir
            if (cur == null || !cur.isDirectory) {
                toastResult(context, "请先进入一个文件夹")
                return@FileMenuEntry
            }
            runOp {
                val made = FileOps.newTextFile(cur)
                if (made != null) null else "新建文本文档失败（权限）"
            }
        })
        add(FileMenuEntry(separator = true))
        add(FileMenuEntry("粘贴", "📥", enabled = FileOps.Clipboard.hasContent()) {
            closeCtx()
            val cur = currentRealDir
            if (cur == null || !cur.isDirectory) {
                toastResult(context, "请先进入一个文件夹再粘贴")
                return@FileMenuEntry
            }
            val (mode, files) = FileOps.Clipboard.pending() ?: return@FileMenuEntry
            runOp { FileOps.copyIn(files, cur, move = mode == FileOps.Clipboard.Mode.CUT) }
        })
        add(FileMenuEntry("刷新", "🔄") {
            closeCtx()
            refresh()
        })
    }

    /** 单项点击路由（网格/列表共用 —— 旧版两分支重复 40 行同逻辑）。 */
    val onFileOpen: (File) -> Unit = { file ->
        if (file.isDirectory) openFolder(file)
        else if (wallpaperPick && isImageExtension(file.extension)) {
            setWallpaperAndClose(file)
        } else if (wallpaperPick && isVideoExtension(file.extension)) {
            setWallpaperAndClose(file, isVideo = true)
        } else if (lockWallpaperPick && isImageExtension(file.extension)) {
            setLockWallpaperAndClose(file, isVideo = false)
        } else if (lockWallpaperVideoPick && isVideoExtension(file.extension)) {
            setLockWallpaperAndClose(file, isVideo = true)
        } else if (driverPick) {
            if (isDriverPackageFile(file.name)) pickAndClose(file)
            else Toast.makeText(context, "请选择驱动包（zip / tar / tar.gz / tar.xz / tar.bz2 / tzst）", Toast.LENGTH_SHORT).show()
        } else if (textPick) {
            if (isTextExtension(file.extension)) pickAndClose(file)
            else Toast.makeText(context, "请选择文本文件（txt/log/md/json/xml 等）", Toast.LENGTH_SHORT).show()
        } else if (imagePick) {
            if (isImageExtension(file.extension)) pickAndClose(file)
            else Toast.makeText(context, "请选择图片文件（jpg/png/webp/gif 等）", Toast.LENGTH_SHORT).show()
        } else if (dirPick) {
            Toast.makeText(context, "目录选择模式：请点上方「选定此目录」回传当前文件夹", Toast.LENGTH_SHORT).show()
        } else if (exePick) {
            if (isExecutableExtension(file.extension)) pickAndClose(file)
            else Toast.makeText(context, "请选择程序文件（exe/bat/msi/cmd/com）", Toast.LENGTH_SHORT).show()
        } else if (bootVideoPick) {
            if (isVideoExtension(file.extension)) setBootVideoAndClose(file)
            else Toast.makeText(context, "请选择视频文件（mp4/webm/mkv 等）", Toast.LENGTH_SHORT).show()
        } else if (mediaPick) {
            if (isMediaExtension(file.extension)) pickAndClose(file)
            else Toast.makeText(context, "请选择音频或视频文件", Toast.LENGTH_SHORT).show()
        } else openRealFile(context, file)
    }

    fun breadcrumbOf(dir: File?): String {
        if (dir == null || isThisPcHome) return "此电脑"
        val p = dir.absolutePath
        val internal = "/data/data/com.linbox/files"
        val download = "/storage/emulated/0/Download"
        val exagear = "/storage/emulated/0/exagear"
        return when {
            p == internal -> "此电脑 > 本地磁盘 (C:)"
            p.startsWith("$internal/") -> "此电脑 > 本地磁盘 (C:) > " + p.removePrefix("$internal/")
            p == download -> "此电脑 > 下载 (D:)"
            p.startsWith("$download/") -> "此电脑 > 下载 (D:) > " + p.removePrefix("$download/")
            p == exagear -> "此电脑 > exagear (E:)"
            p.startsWith("$exagear/") -> "此电脑 > exagear (E:) > " + p.removePrefix("$exagear/")
            else -> "此电脑 > 内部存储" + p.removePrefix("/storage/emulated/0")
        }
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
                    // v3.3.8：面包屑按盘符映射（C/D/E/内部存储）
                    val displayPath = breadcrumbOf(currentRealDir)
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
        if (wallpaperPick || lockWallpaperPick || lockWallpaperVideoPick || textPick || mediaPick || imagePick || dirPick || exePick || bootVideoPick || driverPick) {
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
                        exePick -> Icons.Default.Apps
                        bootVideoPick -> Icons.Default.Movie
                        driverPick -> Icons.Default.Memory
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
                        exePick -> "点击 exe / bat / msi / cmd / com 程序，回填到「运行」窗口的程序路径"
                        bootVideoPick -> "点击任意视频文件，设为 App 启动动画（下次启动生效）"
                        driverPick -> "点击驱动包（zip / tar / tar.gz / tar.xz / tar.bz2 / tzst），安装到独立驱动库"
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
            // ===== 左侧导航栏（窄屏可收起；v3.3.8：200→172dp + 行距收紧） =====
            if (showSidebar) {
            Column(
                modifier = Modifier
                    .width(if (isNarrow) 150.dp else 172.dp)
                    .fillMaxHeight()
                    .background(theme.cardBackgroundColor)
                    .padding(6.dp)
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

                Spacer(Modifier.height(8.dp))
                NavSection("设备")
                SidebarItem("💻 此电脑", isThisPcHome, theme) { goHome() }
                // v3.3.8：C/D/E 三盘直达
                val internalFiles = remember { File("/data/data/com.linbox/files") }
                val driveD = remember { File("/storage/emulated/0/Download") }
                val driveE = remember { File("/storage/emulated/0/exagear") }
                SidebarItem(
                    "💾 本地磁盘 (C:)",
                    !isThisPcHome && currentRealDir?.absolutePath == internalFiles.absolutePath,
                    theme
                ) { openFolder(internalFiles) }
                SidebarItem(
                    "⬇️ 下载 (D:)",
                    !isThisPcHome && currentRealDir?.absolutePath == driveD.absolutePath,
                    theme
                ) {
                    if (hasAllFilesAccess) openFolder(driveD) else requestAllFilesAccess()
                }
                SidebarItem(
                    "🎮 exagear (E:)",
                    !isThisPcHome && currentRealDir?.absolutePath == driveE.absolutePath,
                    theme
                ) {
                    if (!hasAllFilesAccess) { requestAllFilesAccess() } else {
                        if (!driveE.isDirectory) runCatching { driveE.mkdirs() }
                        openFolder(driveE)
                    }
                }
                SidebarItem(
                    "📱 内部存储",
                    !isThisPcHome && currentRealDir?.absolutePath == storageRoot.absolutePath,
                    theme
                ) {
                    if (hasAllFilesAccess) openFolder(storageRoot) else requestAllFilesAccess()
                }
            }
            }

            // ===== 文件区（v3.3.40：空白区右键 → 新建/粘贴/刷新菜单） =====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(8.dp)
                    .fileAreaGestures { off ->
                        if (menuAllowed) ctxMenu.open(null, off, background = true)
                    }
            ) {
                // v3.3.40：空白区菜单锚（1dp 隐形 Box —— DropdownMenu 锚定用）
                Box(modifier = Modifier.size(1.dp)) {
                    FileCtxMenu(
                        expanded = ctxMenu.background,
                        onDismiss = { closeCtx() },
                        offset = ctxMenu.offset,
                        entries = bgEntries(),
                        theme = theme
                    )
                }
                // v3.3.8：权限门禁细化 —— C 盘（应用内部存储）与首页无需所有
                // 文件权限；浏览外部存储路径（/storage/emulated/0 下）且未授权
                // 时才拦截（首页内 D/E 盘点击时经 onRequestPermission 兜底）
                val inInternalStore = currentRealDir?.absolutePath
                    ?.startsWith("/data/data/com.linbox/files") == true
                if (!hasAllFilesAccess && !inInternalStore && !isThisPcHome) {
                    PermissionRequiredState(theme) { requestAllFilesAccess() }
                } else if (isThisPcHome) {
                    // ===== 此电脑 首页 =====
                    ThisPcHomeView(
                        storageRoot = storageRoot,
                        theme = theme,
                        hasAllFilesAccess = hasAllFilesAccess,
                        onRequestPermission = { requestAllFilesAccess() },
                        onOpenFolder = { openFolder(it) }
                    )
                } else if (realItems.isEmpty()) {
                    EmptyState(theme, "此文件夹为空")
                } else if (isGridView) {
                    RealFileGrid(
                        realItems, theme,
                        menuAllowed = menuAllowed,
                        menuState = ctxMenu,
                        entriesFor = { f -> fileEntries(f) },
                        onClick = onFileOpen
                    )
                } else {
                    RealFileList(
                        realItems, theme,
                        menuAllowed = menuAllowed,
                        menuState = ctxMenu,
                        entriesFor = { f -> fileEntries(f) },
                        onClick = onFileOpen
                    )
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

        // ===== v3.3.40：右键菜单操作对话框层（重命名 / 属性 / 删除确认） =====
        renameTarget?.let { target ->
            Win7MiniDialog(
                title = "重命名",
                theme = theme,
                onDismiss = { renameTarget = null }
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        "新名称（保持扩展名：${target.extension.ifEmpty { "无" } }）",
                        fontSize = 11.sp, color = theme.secondaryTextColor
                    )
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "取消", fontSize = 12.sp, color = theme.secondaryTextColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { renameTarget = null }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "确定", fontSize = 12.sp, color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(theme.accentColor)
                                .clickable {
                                    val f = target
                                    renameTarget = null
                                    runOp { FileOps.rename(f, renameText) }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        propsTarget?.let { _ ->
            propsData?.let { d ->
                Win7MiniDialog(
                    title = "属性 — ${d.name}",
                    theme = theme,
                    onDismiss = { propsTarget = null; propsData = null },
                    width = 340.dp
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        PropRow("名称", d.name, theme)
                        PropRow("类型", d.type, theme)
                        PropRow("位置", d.location, theme)
                        PropRow("大小", d.sizeText, theme)
                        PropRow("修改时间", d.modified, theme)
                        PropRow("属性", if (d.writable) "可读写" else "只读", theme)
                    }
                }
            }
        }

        deleteTarget?.let { target ->
            Win7MiniDialog(
                title = "删除",
                theme = theme,
                onDismiss = { deleteTarget = null }
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        if (target.isDirectory) "确定删除文件夹「${target.name}」及其全部内容？"
                        else "确定删除「${target.name}」？",
                        fontSize = 13.sp,
                        color = if (theme.isDark) Color.White else Color.Black
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "取消", fontSize = 12.sp, color = theme.secondaryTextColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { deleteTarget = null }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "删除", fontSize = 12.sp, color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFE81123))
                                .clickable {
                                    val f = target
                                    deleteTarget = null
                                    runOp { FileOps.delete(f) }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// 此电脑首页：5 个快捷文件夹 + C/D/E 三个驱动器
//
// v3.3.8（用户问题 2）：
//  - C: → /data/data/com.linbox/files（应用内部存储，无需权限）；
//  - D: → /storage/emulated/0/Download（外部下载目录）；
//  - E: → /storage/emulated/0/exagear（外部 exagear 目录，不存在时提示）；
//  - 快捷文件夹/驱动器行整体缩小（图标 56→40、间距收紧），
//    "图片视频等占用太大"的紧凑诉求。
// ============================================================
@Composable
private fun ThisPcHomeView(
    storageRoot: File,
    theme: com.linbox.core.theme.WinTheme,
    hasAllFilesAccess: Boolean,
    onRequestPermission: () -> Unit,
    onOpenFolder: (File) -> Unit
) {
    val scrollState = rememberScrollState()

    // v3.3.8：C/D/E 盘定义（C = 应用内部存储；D/E = 外部存储子目录）
    val internalFiles = remember { File("/data/data/com.linbox/files") }
    val driveD = remember { File("/storage/emulated/0/Download") }
    val driveE = remember { File("/storage/emulated/0/exagear") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // ===== 文件夹区（窄屏可横向滑动，解决竖屏图标显示不全；v3.3.8 缩小） =====
        SectionHeader("文件夹 (5)")
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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

        Spacer(Modifier.height(14.dp))

        // ===== 设备和驱动器区（v3.3.8：C/D/E 三盘） =====
        SectionHeader("设备和驱动器")
        Spacer(Modifier.height(4.dp))
        // C: 应用内部存储（无需所有文件权限，始终可浏览）
        DriveCell(
            label = "本地磁盘 (C:)",
            subtitle = "内部存储 · 应用数据",
            theme = theme,
            root = internalFiles,
            onClick = { onOpenFolder(internalFiles) }
        )
        // D: 外部 Download（需要所有文件权限）
        DriveCell(
            label = "下载 (D:)",
            subtitle = "/storage/emulated/0/Download",
            theme = theme,
            root = driveD,
            onClick = {
                if (hasAllFilesAccess) onOpenFolder(driveD) else onRequestPermission()
            }
        )
        // E: 外部 exagear（需要所有文件权限；不存在时提示）
        DriveCell(
            label = "exagear (E:)",
            subtitle = "/storage/emulated/0/exagear",
            theme = theme,
            root = driveE,
            onClick = {
                if (!hasAllFilesAccess) { onRequestPermission(); return@DriveCell }
                if (!driveE.isDirectory) {
                    runCatching { driveE.mkdirs() }
                }
                onOpenFolder(driveE)
            }
        )
    }
}

private data class SpecialFolder(val name: String, val icon: String, val file: File)

@Composable
private fun SectionHeader(title: String) {
    val theme = LocalWinTheme.current
    // v3.4.5（Win7 1:1）：组标题 = 蓝色文字 + 右侧延伸分组细线
    //（Win7 资源管理器“文件夹 (5)/设备和驱动器”同款；Win7Simu 截图
    // 基准：字色 #1E6EB8，线为同色 1px 渐隐）
    val win7ish = theme.variant == com.linbox.core.theme.WindowsVariant.WIN7
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = title,
            color = if (win7ish) Color(0xFF1E6EB8) else
                if (theme.isDark) Color.White else Color.Black,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    if (win7ish) Brush.horizontalGradient(
                        listOf(Color(0x991E6EB8), Color(0x331E6EB8), Color.Transparent)
                    ) else Brush.horizontalGradient(
                        listOf(
                            if (theme.isDark) Color(0x33FFFFFF) else Color(0x33808080),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
private fun SpecialFolderCell(folder: SpecialFolder, theme: com.linbox.core.theme.WinTheme, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // v3.3.8：96→76dp（"图片视频等占用太大"→ 全部往上缩小）
            .width(76.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(5.dp)
    ) {
        // v3.4.5（Win7 1:1）：Win7 拟物文件夹（背片+前片+高光线，自绘
        // Canvas）替代 Material 扁平 Folder 图标；emoji 改叠前片中央
        // 小字（Win7 快捷文件夹的封面预览位）
        Box(
            modifier = Modifier
                .size(42.dp),
            contentAlignment = Alignment.Center
        ) {
            com.linbox.core.desktop.Win7Icons.Folder(size = 42.dp)
            Text(text = folder.icon, fontSize = 12.sp)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = folder.name,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/**
 * 驱动器行（v3.3.8：C/D/E 通用版）。
 * @param label 显示名（如 "本地磁盘 (C:)"）
 * @param subtitle 副标题（路径说明）
 * @param root 盘符根目录（容量统计与存在性检查；目录不存在时容量行
 *        显示提示，仍可点击进入）
 */
@Composable
private fun DriveCell(
    label: String,
    subtitle: String,
    theme: com.linbox.core.theme.WinTheme,
    root: File,
    onClick: () -> Unit
) {
    val exists = remember(root.absolutePath) { root.isDirectory }
    // 计算磁盘容量（目录不存在 → 0）
    val (totalGB, availGB) = remember(root.absolutePath, exists) {
        if (!exists) Pair(0L, 0L)
        else runCatching {
            val stat = StatFs(root.absolutePath)
            Pair(stat.totalBytes / (1024L * 1024L * 1024L), stat.availableBytes / (1024L * 1024L * 1024L))
        }.getOrDefault(Pair(0L, 0L))
    }
    val usedGB = totalGB - availGB
    val usagePct = if (totalGB > 0) (usedGB.toFloat() / totalGB.toFloat()).coerceIn(0f, 1f) else 0f

    val win7ish = theme.variant == com.linbox.core.theme.WindowsVariant.WIN7
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // v3.4.5（Win7 1:1）：3D 拟物硬盘（金属盘体+高光+LED+标签条，
        // Win7Icons 自绘）替代 Material Storage 扁平图标；非 Win7 主题
        // 维持旧观感（Material 图标可继续代表现代主题的简约）。
        if (win7ish) {
            com.linbox.core.desktop.Win7Icons.HardDisk(size = 42.dp)
        } else {
            Icon(
                Icons.Default.Storage, null,
                tint = if (theme.isDark) Color(0xFF9CC4E8) else Color(0xFF0078D4),
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = subtitle,
                    color = theme.secondaryTextColor,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(4.dp))
            if (exists) {
                // v3.4.5（Win7 1:1）：容量条 = 凹槽（浅灰槽 + 1px 内描边）
                // + 蓝渐变填充（#4BC0F0→#1B7FC4，满色阶上端亮）——
                // Win7 磁盘条的凹槽质感；非 Win7 主题保持纯色薄条。
                if (win7ish) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFE9EEF3))
                            .border(1.dp, Color(0xFF9AA9B6))
                            .padding(1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(usagePct)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF4BC0F0), Color(0xFF1B7FC4))
                                    )
                                )
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
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
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "$availGB GB 可用（共 $totalGB GB）",
                    color = theme.secondaryTextColor,
                    fontSize = 10.sp
                )
            } else {
                Text(
                    text = "目录不存在（点击进入时自动创建）",
                    color = theme.secondaryTextColor,
                    fontSize = 10.sp
                )
            }
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
        // v3.3.8：紧凑化（11→10sp，间距收紧）
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp, top = 2.dp)
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
            // v3.3.8：紧凑化（8→5dp，「图片视频等分类占用太大」→ 往上缩小）
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            color = if (active) theme.accentColor else (if (theme.isDark) Color.White else Color.Black),
            fontSize = 11.sp,
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
private fun RealFileGrid(
    items: List<File>,
    theme: com.linbox.core.theme.WinTheme,
    // v3.3.40：右键/长按菜单（用户需求 4）—— menuAllowed=false（选择模式）时零挂载
    menuAllowed: Boolean = false,
    menuState: FileMenuState? = null,
    entriesFor: ((File) -> List<FileMenuEntry>)? = null,
    onClick: (File) -> Unit
) {
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
                // v3.3.31：exe/dll/ico 等展示文件内嵌真实图标（PE 资源 RT_GROUP_ICON）
                exePath = if (!item.isDirectory && isPeIconExtension(item.extension)) item.absolutePath else null,
                theme = theme,
                menuAllowed = menuAllowed,
                menuFile = item,
                menuState = menuState,
                entriesFor = entriesFor
            ) { onClick(item) }
        }
    }
}

@Composable
private fun RealFileList(
    items: List<File>,
    theme: com.linbox.core.theme.WinTheme,
    menuAllowed: Boolean = false,
    menuState: FileMenuState? = null,
    entriesFor: ((File) -> List<FileMenuEntry>)? = null,
    onClick: (File) -> Unit
) {
    LazyColumn {
        items(items, key = { it.absolutePath }) { item ->
            FileListRow(
                name = item.name, isDir = item.isDirectory,
                extension = item.extension,
                sizeText = if (item.isDirectory) "" else formatSize(item.length()),
                imagePath = if (!item.isDirectory && isImageExtension(item.extension)) item.absolutePath else null,
                // v3.3.31：exe/dll/ico 等展示文件内嵌真实图标
                exePath = if (!item.isDirectory && isPeIconExtension(item.extension)) item.absolutePath else null,
                theme = theme,
                menuAllowed = menuAllowed,
                menuFile = item,
                menuState = menuState,
                entriesFor = entriesFor
            ) { onClick(item) }
        }
    }
}

@Composable
private fun FileGridCell(
    name: String, isDir: Boolean, extension: String, sizeText: String,
    imagePath: String? = null,
    exePath: String? = null,
    theme: com.linbox.core.theme.WinTheme,
    // v3.3.40：右键/长按菜单（真鼠标右键 + 触屏长按双通道）
    menuAllowed: Boolean = false,
    menuFile: File? = null,
    menuState: FileMenuState? = null,
    entriesFor: ((File) -> List<FileMenuEntry>)? = null,
    onClick: () -> Unit
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .let { m ->
                    if (menuAllowed && menuState != null && menuFile != null) {
                        m.fileItemGestures(
                            onClick = onClick
                        ) { off -> menuState.open(menuFile, off) }
                    } else {
                        m.clickable(onClick = onClick)
                    }
                }
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
            } else if (exePath != null) {
                // v3.3.31：Windows 可执行文件展示【内嵌真实图标】
                //（PE 资源 RT_GROUP_ICON → RT_ICON，与资源管理器里看到的一致），
                // 解析失败回退分类矢量图标
                ExeIconThumbnail(path = exePath, ext = extension, size = 56.dp)
            } else {
                // v3.3.30：内置矢量分类图标 + 扩展名角标（替代 emoji ——
                // 设备字体差异下 emoji 渲染成同一副面孔，用户“固定图标”反馈）
                FileTypeIcon(extension, cellSize = 56.dp, showBadge = true)
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
        // v3.3.40：锚定在本项上的右键菜单（DropdownMenu 随宿主 Box 定位）
        if (menuAllowed && menuFile != null && menuState != null && entriesFor != null) {
            FileCtxMenu(
                expanded = menuState.menuFor == menuFile && !menuState.background,
                onDismiss = { menuState.close() },
                offset = menuState.offset,
                entries = entriesFor(menuFile),
                theme = theme
            )
        }
    }
}

@Composable
private fun FileListRow(
    name: String, isDir: Boolean, extension: String, sizeText: String,
    imagePath: String? = null,
    exePath: String? = null,
    theme: com.linbox.core.theme.WinTheme,
    // v3.3.40：右键/长按菜单（真鼠标右键 + 触屏长按双通道）
    menuAllowed: Boolean = false,
    menuFile: File? = null,
    menuState: FileMenuState? = null,
    entriesFor: ((File) -> List<FileMenuEntry>)? = null,
    onClick: () -> Unit
) {
    Box(Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .let { m ->
                if (menuAllowed && menuState != null && menuFile != null) {
                    m.fileItemGestures(
                        onClick = onClick
                    ) { off -> menuState.open(menuFile, off) }
                } else {
                    m.clickable(onClick = onClick)
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDir) {
            Icon(Icons.Default.Folder, null, tint = Color(0xFFDCA84A), modifier = Modifier.size(18.dp))
        } else if (imagePath != null) {
            ImageThumbnail(path = imagePath, size = 20.dp, corner = 3.dp)
        } else if (exePath != null) {
            // v3.3.31：exe 内嵌真实图标（列表行）
            ExeIconThumbnail(path = exePath, ext = extension, size = 20.dp)
        } else {
            // v3.3.30：内置矢量分类图标（列表行窄，不带角标）
            FileTypeIcon(extension, cellSize = 20.dp, showBadge = false)
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
    // v3.3.40：锚定在本项上的右键菜单
    if (menuAllowed && menuFile != null && menuState != null && entriesFor != null) {
        FileCtxMenu(
            expanded = menuState.menuFor == menuFile && !menuState.background,
            onDismiss = { menuState.close() },
            offset = menuState.offset,
            entries = entriesFor(menuFile),
            theme = theme
        )
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
        // v3.3.30：解码失败回退矢量图片图标（不再用 emoji）
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Image, null,
                tint = Color(0xFFE91E63),
                modifier = Modifier.size(size * 0.8f)
            )
        }
    }
}

/**
 * v3.3.31（用户需求："读取每个 exe 内部自带的图标"）：Windows PE 内嵌真实
 * 图标缩略图 —— PeIcon（纯 Java 解析器）提取 RT_GROUP_ICON → RT_ICON
 * （PNG / DIB 1-32bpp 全支持），内存 LruCache 按「路径|长度|mtime」记账，
 * IO 线程解析（单个 exe 毫秒级），失败/解析中回退分类矢量图标。
 */
@Composable
private fun ExeIconThumbnail(path: String, ext: String, size: Dp) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            PeIconCache.load(path)
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 12f))
        )
    } else {
        // 解析中 / 无内嵌图标：回退分类矢量图标（保持旧观感，不闪跳）
        FileTypeIcon(ext, cellSize = size, showBadge = false)
    }
}

/** v3.3.31：exe 内嵌图标的内存缓存（进程级；key = 路径|长度|mtime）。 */
private object PeIconCache {
    private val cache = android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) // 12MB（按字节记账）

    fun load(path: String): Bitmap? {
        val f = File(path)
        if (!f.isFile) return null
        val key = "$path|${f.length()}|${f.lastModified()}"
        synchronized(cache) { cache.get(key) }?.let { return it }
        // 目标边长 128（网格 56dp xxhdpi 之上仍有余量；文件过大时宁可小图不 OOM）
        val icon = runCatching {
            if (path.substringAfterLast('.', "").equals("ico", true)) {
                PeIcon.extractIco(path, 128)
            } else {
                PeIcon.extract(path, 128)
            }
        }.getOrNull() ?: return null
        val bmp = runCatching {
            if (icon.png != null) {
                BitmapFactory.decodeByteArray(icon.png, 0, icon.png.size)
            } else if (icon.argb != null && icon.width > 0 && icon.height > 0) {
                Bitmap.createBitmap(icon.argb, icon.width, icon.height, Bitmap.Config.ARGB_8888)
            } else null
        }.getOrNull() ?: return null
        // 超大图缩到 256 内（控制缓存占用）
        val scaled = if (bmp.width > 256 || bmp.height > 256) {
            val scale = 256f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1), true
            )
        } else bmp
        synchronized(cache) { cache.put(key, scaled) }
        return scaled
    }
}

/** v3.3.31：可提取内嵌图标的文件扩展名（PE 家族 + 独立 .ico）。 */
private fun isPeIconExtension(ext: String): Boolean =
    ext.lowercase() in setOf("exe", "dll", "scr", "com", "cpl", "ocx", "msc", "ico")

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

/** Windows 可执行/脚本文件扩展名（v3.2.13：容器运行选择 + 双击调起容器选择框）。 */
private fun isExecutableExtension(ext: String): Boolean =
    ext.lowercase() in setOf("exe", "msi", "bat", "cmd", "com", "scr", "lnk")

/**
 * 驱动包文件名（v3.3.17：容器管理「➕ 安装驱动」选择模式过滤用）。
 * 按完整文件名匹配（file.extension 对 foo.tar.gz 只给 "gz"，无法判双后缀）；
 * 与 WrapperDriverSupport.install 支持的包形态一一对应（zip + 四种 tar 压缩）。
 */
private fun isDriverPackageFile(name: String): Boolean {
    val n = name.lowercase()
    // v3.3.21：补 .tzst / .tar.zst（wrapper-gamenative.tzst 类 上游实现
    // 生态包，tar+zstd；解压链已支持 zstd-jni）
    return n.endsWith(".zip") || n.endsWith(".tar") || n.endsWith(".tar.gz") ||
        n.endsWith(".tar.xz") || n.endsWith(".tar.bz2") ||
        n.endsWith(".tzst") || n.endsWith(".tar.zst")
}

/**
 * 打开真实文件：
 * - 图片 → 内置图片查看器
 * - HTML/HTM → 内置浏览器
 * - 文本 → 内置记事本（v2.14.10，不再调系统应用）
 * - 音频/视频 → 内置媒体播放器（v2.9）
 * - Windows 程序（exe/msi/bat/cmd/com）→ 【选择容器运行】窗口
 *   （v3.2.13 用户问题 3：选择对应容器 + 分辨率/窗口设置 + 启动参数）
 * - APK → 系统安装器（FileProvider）
 * - PDF/其他 → 系统 ACTION_VIEW Intent（FileProvider）
 */

// ============================================================
// v3.3.40：右键菜单配套对话框（Win7 视觉：居中卡片 + 遮罩；主题色随主题）
// ============================================================

@Composable
private fun Win7MiniDialog(
    title: String,
    theme: com.linbox.core.theme.WinTheme,
    onDismiss: () -> Unit,
    width: Dp = 300.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(6.dp))
                .background(if (theme.isDark) Color(0xFF252525) else Color(0xFFF7F7F7))
                .border(1.dp, theme.accentColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
        ) {
            // 标题条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.accentColor.copy(alpha = if (theme.isDark) 0.55f else 0.9f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun PropRow(label: String, value: String, theme: com.linbox.core.theme.WinTheme) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = theme.secondaryTextColor,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            fontSize = 12.sp,
            color = if (theme.isDark) Color.White else Color.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * LinBox 裁剪兼容：图片/记事本/媒体内置查看器未随窗口系统注册
 * （仿 Windows 小应用不移植）时，回退系统 VIEW Intent。
 */
private fun openWindowOrSystem(
    context: Context,
    appId: String,
    title: String,
    args: Map<String, String>,
    file: File,
    mime: String?
) {
    if (com.linbox.core.window.AppRegistry.get(appId) != null) {
        WindowManager.get().open(
            appId = appId,
            title = title,
            launchMode = LaunchMode.FLOATING,
            launchArgs = args
        )
        return
    }
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime ?: mimeForExtension(file.extension.lowercase()))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "未找到可打开 ${file.extension} 文件的应用", Toast.LENGTH_SHORT).show()
    }
}

private fun openRealFile(context: Context, file: File) {
    val ext = file.extension.lowercase()
    try {
        when {
            // v3.2.13：Windows 程序 —— 旧版落到系统 VIEW Intent，必然
            // “未找到可打开 .exe 文件的应用”；现调出容器选择框（容器/
            // 分辨率/显示模式/启动参数一次选完，Unix 绝对路径直达）
            isExecutableExtension(ext) -> {
                WindowManager.get().open(
                    appId = "exe_run_chooser",
                    title = "运行 ${file.name}",
                    launchMode = LaunchMode.FLOATING,
                    launchArgs = mapOf("path" to file.absolutePath),
                    initialWidth = 560,
                    initialHeight = 620
                )
            }
            ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp") -> {
                openWindowOrSystem(
                    context, "image_viewer", file.name,
                    mapOf("path" to file.absolutePath), file, "image/*"
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
            // 文本 → 内置记事本（v2.14.10；LinBox 未注册时回退系统查看器）
            isTextExtension(ext) -> {
                openWindowOrSystem(
                    context, "notepad", file.name,
                    mapOf("path" to file.absolutePath), file, "text/*"
                )
            }
            // 音频/视频 → 内置媒体播放器（v2.9；LinBox 未注册时回退系统播放器）
            ext in AUDIO_FILE_EXTS || ext in VIDEO_FILE_EXTS -> {
                openWindowOrSystem(
                    context, "media_player", file.name,
                    mapOf("path" to file.absolutePath), file, null
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

// v3.3.29：Windows 程序/脚本全家桶图标识别（用户需求：exe cmd 等）。
//   此前 exe/msi 有图标，cmd/bat/com/scr/lnk/dll/ini/reg 等全落默认 📄，
// v3.3.30：文件类型矢量图标体系（替代上方 emoji 方案 —— 用户实测
// “识别 exe 等内置图标你就显示个固定图标”：emoji 在设备字体上渲染
// 千篇一律且不可辨识）。现按 Windows 语义分档 → Material 内置矢量图标
// + 专属色 + 扩展名角标（Win11 徽记风格）：
//   可执行本体（exe/com/scr）→ 蓝系应用图标；安装包 msi → 安装箱；
//   脚本（bat/cmd/ps1）→ 终端；快捷方式 lnk → 链接；DLL/SYS → 齿轮；
//   配置/注册表 → 调节器；镜像 iso → 唱片；压缩包 → 拉链文件夹等。
private data class FileIconSpec(val icon: ImageVector, val tint: Color, val label: String?)

private fun fileIconSpecFor(extRaw: String): FileIconSpec {
    val ext = extRaw.lowercase()
    return when (ext) {
        // 可执行 / 安装器 —— 蓝系“应用”
        "exe", "scr", "com" -> FileIconSpec(Icons.Filled.Apps, Color(0xFF2B7DE9), ext.uppercase())
        "msi" -> FileIconSpec(Icons.Filled.Inventory2, Color(0xFF2B7DE9), "MSI")
        // 脚本 / 终端
        "cmd", "bat", "ps1", "sh", "vbs" ->
            FileIconSpec(Icons.Filled.Terminal, Color(0xFF607D8B), ext.uppercase())
        // 系统组件（dll/sys/drv/so）—— 灰齿轮
        "dll", "sys", "drv", "so", "ocx", "cpl" ->
            FileIconSpec(Icons.Filled.Settings, Color(0xFF78909C), ext.uppercase())
        // 快捷方式
        "lnk", "url" -> FileIconSpec(Icons.Filled.Link, Color(0xFF5C6BC0), ext.uppercase())
        // 配置 / 注册表
        "ini", "cfg", "conf", "properties", "reg", "inf" ->
            FileIconSpec(Icons.Filled.Tune, Color(0xFF546E7A), ext.uppercase())
        // 纯文本
        "txt", "log", "md", "nfo", "srt", "ass" ->
            FileIconSpec(Icons.Filled.Description, Color(0xFF90A4AE), ext.uppercase())
        // 文档 / 表格 / 幻灯
        "doc", "docx", "rtf", "wps" -> FileIconSpec(Icons.Filled.Description, Color(0xFF1E88E5), "DOC")
        "xls", "xlsx", "csv", "et", "ets" -> FileIconSpec(Icons.Filled.TableChart, Color(0xFF43A047), "XLS")
        "ppt", "pptx", "dps" -> FileIconSpec(Icons.Filled.Slideshow, Color(0xFFFB8C00), "PPT")
        "pdf" -> FileIconSpec(Icons.Filled.PictureAsPdf, Color(0xFFE53935), "PDF")
        // 压缩包
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tzst", "cab" ->
            FileIconSpec(Icons.Filled.FolderZip, Color(0xFF8D6E63), ext.uppercase())
        // 安卓包
        "apk", "apks", "xapk", "aab" -> FileIconSpec(Icons.Filled.Android, Color(0xFF3DDC84), "APK")
        // 网页 / 数据
        "html", "htm" -> FileIconSpec(Icons.Filled.Language, Color(0xFF039BE5), "HTML")
        "json", "xml", "yml", "yaml" -> FileIconSpec(Icons.Filled.DataObject, Color(0xFF8E24AA), ext.uppercase())
        // 镜像
        "iso", "img", "mds", "vhd" -> FileIconSpec(Icons.Filled.Album, Color(0xFF7E57C2), "ISO")
        // 字体
        "ttf", "otf", "ttc", "fon" -> FileIconSpec(Icons.Filled.TextFields, Color(0xFF6D4C41), "TTF")
        // 音频
        "mp3", "wav", "flac", "ogg", "m4a", "wma", "aac", "mid", "midi", "opus" ->
            FileIconSpec(Icons.Filled.Audiotrack, Color(0xFF00897B), ext.uppercase())
        // 视频
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "m4v", "webm", "3gp", "rmvb", "mpg", "mpeg" ->
            FileIconSpec(Icons.Filled.Movie, Color(0xFF7B1FA2), ext.uppercase())
        // 图片（缩略图解码失败时的回退也走这里）
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico" ->
            FileIconSpec(Icons.Filled.Image, Color(0xFFE91E63), ext.uppercase())
        else -> {
            // 未知类型：通用文件图标；有扩展名时带角标
            val label = ext.take(4).uppercase().ifBlank { null }
            FileIconSpec(Icons.Filled.InsertDriveFile, Color(0xFF90A4AE), label)
        }
    }
}

/**
 * 文件类型图标（v3.3.30）：图标居中 + 底部扩展名角标（网格视图）；
 * 列表视图纯图标。尺寸随 cellSize 缩放。
 */
@Composable
private fun FileTypeIcon(ext: String, cellSize: Dp, showBadge: Boolean) {
    val spec = fileIconSpecFor(ext)
    Box(modifier = Modifier.size(cellSize), contentAlignment = Alignment.Center) {
        Icon(
            spec.icon, null,
            tint = spec.tint,
            modifier = Modifier.size(cellSize * 0.74f)
        )
        if (showBadge && spec.label != null) {
            Text(
                text = spec.label,
                color = Color.White,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(2.dp))
                    .background(spec.tint.copy(alpha = 0.95f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
    }
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
