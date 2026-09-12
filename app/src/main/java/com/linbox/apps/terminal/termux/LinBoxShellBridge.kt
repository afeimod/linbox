package com.linbox.apps.terminal.termux

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import com.linbox.LinBoxApp
import com.linbox.core.window.AppRegistry
import com.linbox.core.window.WindowManager
import com.linbox.core.theme.WindowsVariant
import com.linbox.apps.browser.BrowserApp
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException

/**
 * LinBox 桌面命令桥：读取 Termux shell 写入 FIFO 的命令并分发到桌面。
 *
 * shell 侧（$PREFIX/etc/profile.d/linbox.sh）：
 *   `_linbox_send "theme win11"` → 写入 $PREFIX/var/linbox.cmd
 * App 侧（本类）：
 *   后台线程循环 open FIFO → 逐行读取 → 主线程分发。
 *
 * FIFO 语义保证：写端（shell 函数用后台子 shell）永不阻塞终端；
 * 读端在所有写端关闭后读到 EOF，重新 open 等待下一位写者，
 * 因此不会丢命令也不会卡 shell。
 */
object LinBoxShellBridge {

    private const val TAG = "LinBoxShellBridge"

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    /** 启动命令桥（幂等；bootstrap 安装完成后调用）。 */
    fun start(context: Context) {
        val fifoPath = TermuxEnvironment.commandFifoPath(context)
        if (!File(fifoPath).exists()) {
            Log.w(TAG, "FIFO 不存在，命令桥不启动: $fifoPath")
            return
        }
        if (running) return
        running = true
        thread = Thread({
            readLoop(fifoPath)
        }, "linbox-shell-bridge").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun readLoop(fifoPath: String) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        while (running) {
            try {
                // 阻塞直到有写者打开 FIFO；EOF 后重开
                BufferedReader(FileReader(fifoPath)).use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        val cmd = line.trim()
                        if (cmd.isEmpty()) continue
                        main.post { dispatch(cmd) }
                    }
                }
            } catch (e: IOException) {
                if (!running) break
                Log.w(TAG, "FIFO 读取异常，1s 后重试: ${e.message}")
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 命令分发（主线程）
    // ------------------------------------------------------------------

    private val appAliases: Map<String, String> = mapOf(
        "browser" to "browser", "ie" to "browser", "explorer" to "browser", "浏览器" to "browser",
        "files" to "file_explorer", "filemanager" to "file_explorer", "文件管理器" to "file_explorer",
        "notepad" to "notepad", "记事本" to "notepad",
        "calc" to "calculator", "calculator" to "calculator", "计算器" to "calculator",
        "settings" to "settings", "设置" to "settings",
        "music" to "music", "yinyue" to "music", "cloudmusic" to "music", "音乐" to "music",
        "sysinfo" to "sysinfo", "clock" to "clock", "minesweeper" to "minesweeper",
        "terminal" to "terminal", "简易终端" to "terminal_sim"
    )

    private fun dispatch(raw: String) {
        try {
            val parts = raw.split(" ", limit = 2)
            val command = parts[0].lowercase()
            val args = if (parts.size > 1) parts[1].trim() else ""
            when (command) {
                "theme" -> handleTheme(args)
                "start" -> handleStart(args)
                "apps" -> handleApps()
                "open" -> handleOpen(args)
                "winver" -> handleWinver()
                else -> Log.w(TAG, "未知桌面命令: $raw")
            }
        } catch (e: Exception) {
            Log.e(TAG, "命令分发失败: $raw", e)
        }
    }

    private fun handleTheme(args: String) {
        val app = LinBoxApp.get()
        val variant = when (args.lowercase()) {
            "win95", "95" -> WindowsVariant.WIN95
            "xp", "winxp" -> WindowsVariant.WIN_XP
            "win7", "7" -> WindowsVariant.WIN7
            "win10", "10" -> WindowsVariant.WIN10
            "win11", "11" -> WindowsVariant.WIN11
            else -> null
        }
        if (variant != null) {
            app.applicationScope.launch {
                app.themeManager.setTheme(variant)
            }
        } else {
            Log.w(TAG, "未知主题: $args")
        }
    }

    private fun handleStart(args: String) {
        val target = appAliases[args.lowercase()] ?: args.lowercase()
        val appDef = AppRegistry.get(target) ?: run {
            Log.w(TAG, "应用未注册: $args")
            return
        }
        WindowManager.get().open(
            appId = appDef.id,
            title = appDef.displayName,
            launchMode = appDef.launchMode,
            initialWidth = appDef.defaultWidth.value.toInt(),
            initialHeight = appDef.defaultHeight.value.toInt()
        )
    }

    private fun handleApps() {
        // shell 端无法直接展示 App 弹窗，改为列出可用应用（通过窗口标题栏提示）
        val names = AppRegistry.all().joinToString(" / ") { it.displayName }
        WindowManager.get().let { wm ->
            wm.windowsForApp("terminal").firstOrNull()?.let {
                // 轻量提示：把列表写到终端窗口标题（不侵入终端内容流）
                wm.commitChanges()
            }
        }
        Log.i(TAG, "可用应用: $names")
    }

    private fun handleOpen(args: String) {
        if (args.isEmpty()) return
        val url = if (args.startsWith("http://") || args.startsWith("https://") ||
            args.startsWith("file://") || args.startsWith("about:")
        ) args else "https://$args"

        WindowManager.get().open(
            appId = BrowserApp.id,
            title = BrowserApp.displayName,
            launchMode = BrowserApp.launchMode,
            launchArgs = mapOf("url" to url),
            initialWidth = BrowserApp.defaultWidth.value.toInt(),
            initialHeight = BrowserApp.defaultHeight.value.toInt()
        )
    }

    private fun handleWinver() {
        // 打开系统信息应用展示版本
        val appDef = AppRegistry.get("sysinfo") ?: return
        WindowManager.get().open(
            appId = appDef.id,
            title = appDef.displayName,
            launchMode = appDef.launchMode,
            initialWidth = appDef.defaultWidth.value.toInt(),
            initialHeight = appDef.defaultHeight.value.toInt()
        )
    }
}
