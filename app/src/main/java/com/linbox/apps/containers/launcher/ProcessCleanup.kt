package com.linbox.apps.containers.launcher

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * 孤儿进程清扫器（移植 上游发行包 ProcessCleanup，适配 LinBox 进程特征）。
 *
 * 旧的文本级清理（`ps -ef | grep -E 'wineserver|wine-preloader|\.exe' |
 * awk | xargs kill`）有三重缺陷：依赖 App 前缀的 bash/ps 工具链存在、
 * grep 自身会误匹配、无法校验 UID。本类改为直接扫 `/proc`：数字目录
 * + 同 UID + cmdline 特征匹配，`android.os.Process.killProcess` 精确击杀。
 *
 * 两个使用场景：
 *  1. **App 冷启动**（[cleanup]）：杀掉上次被系统回收时遗留的整棵
 *     wine 孤儿树 —— 否则下一次启动的新 wine 客户端会连上残留的
 *     wineserver 会话（"只有第一次能启动"的根因）；
 *  2. **关闭 X11 桌面窗口**（[killWineProcesses]）：替代
 *     X11Session 的 bash 文本清理。
 *
 * 注意：PulseAudio 守护**不在**击杀之列 —— 它是跨会话常驻设计
 * （--exit-idle-time=-1），陈旧 pid 文件由 [PulseAudioManager] 在
 * 每次启动前校验清理。
 */
object ProcessCleanup {

    private const val TAG = "ProcessCleanup"

    /** App 冷启动清扫入口。 */
    fun cleanup() {
        killWineProcesses()
    }

    /**
     * 扫描 /proc 击杀同 UID 下的 wine 孤儿进程（wineserver / wineboot /
     * wine-preloader / 各类 .exe / box64 拉起的 wine）。
     *
     * @return 击杀的进程数（日志观测用）
     */
    fun killWineProcesses(): Int {
        var killed = 0
        try {
            val selfPid = android.os.Process.myPid()
            val selfUid = android.os.Process.myUid()
            val procDir = File("/proc")
            val entries = procDir.listFiles { _, name ->
                name.toIntOrNull() != null
            } ?: return 0

            for (entry in entries) {
                val pid = entry.name.toIntOrNull() ?: continue
                if (pid == selfPid) continue
                if (!isSameUid(pid, selfUid)) continue

                val cmdline = readCmdline(pid) ?: continue
                if (cmdline.isEmpty()) continue
                if (!isWineProcess(cmdline)) continue

                Log.w(TAG, "Killing orphan wine process: pid=$pid cmd=$cmdline")
                runCatching { android.os.Process.killProcess(pid) }
                killed++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan /proc for orphan wine processes.", e)
        }
        return killed
    }

    /**
     * wine 进程特征匹配。
     *
     * rootfs 侧 wine 家族的 cmdline 形态：
     *  - `/…/opt/wine/bin/wineserver --nosignal` 等
     *  - `/…/box64 /…/wine …`（x86_64 主力路径）
     *  - `C:\windows\system32\services.exe` 类（wine-preloader 承载，
     *    cmdline 为 Windows 路径，特征是 `.exe` 结尾段）
     *  - `wineboot -i` / `explorer.exe /desktop=…`
     */
    private fun isWineProcess(cmdline: String): Boolean {
        val lower = cmdline.lowercase()
        val wineMarkers = listOf(
            "wineserver", "wineboot", "wine-preloader", "wine64-preloader",
            "winepath", "msiexec", "regsvr32"
        )
        if (wineMarkers.any { lower.contains(it) }) return true
        // box64 只在其目标是 wine 时杀（box64 也可能跑别的 ——
        // 本应用内只用于 wine，判定放宽为路径含 wine）
        if (lower.contains("box64") && lower.contains("wine")) return true
        // 各类 Windows 进程（explorer/services/winedevice/…以及游戏 exe）
        return lower.contains("wine") && lower.contains(".exe") ||
                lower.endsWith(".exe") ||
                lower.contains("explorer.exe") ||
                lower.contains("services.exe") ||
                lower.contains("winedevice.exe") ||
                lower.contains("plugplay.exe") ||
                lower.contains("rpcss.exe") ||
                lower.contains("svchost.exe")
    }

    private fun isSameUid(pid: Int, selfUid: Int): Boolean {
        try {
            BufferedReader(FileReader("/proc/$pid/status")).use { reader ->
                var line: String
                while (reader.readLine().also { line = it ?: return false } != null) {
                    if (line.startsWith("Uid:")) {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            return parts[1].toIntOrNull() == selfUid
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 进程可能已退出
        }
        return false
    }

    private fun readCmdline(pid: Int): String? {
        return try {
            BufferedReader(FileReader("/proc/$pid/cmdline")).use { reader ->
                val sb = StringBuilder()
                var ch: Int
                while (reader.read().also { ch = it } != -1) {
                    if (ch == 0) break
                    sb.append(ch.toChar())
                }
                sb.toString()
            }
        } catch (_: Exception) {
            null
        }
    }
}
