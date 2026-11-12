package com.linbox.apps.containers.launcher

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 原生 Wine 会话 —— 替代旧链路的 headless TerminalSession。
 *
 * 一个会话 = 一个 [NativeShellExecutor]（承载 wine 客户端进程）+ 一份
 * 会话日志（内存滚动缓冲 + 可选落盘文件）。生命周期语义：
 *
 *  - **日志**：启动链决策行、子进程 stdout/stderr 逐行、退出码 —— 全部
 *    实时追加（旧的 pty transcript 二次抠取不存在了）；
 *  - **退出**：自然退出（游戏/程序结束）或 [stop]（用户点"停止"）；
 *    会话只负责 wine 客户端进程本身，wineserver 整树收尾由
 *    [WineServerUtils] 在停止链路中完成（对齐上游发行包 的
 *    shellExecutor → stopWineServer 顺序）。
 *
 * @property name 会话键（容器名或 "__install__"）
 */
class WineSession internal constructor(
    val name: String,
    val executor: NativeShellExecutor,
    internal val sink: SessionLogSink
) {

    /** 会话进程是否仍在运行。 */
    val isRunning: Boolean get() = executor.isRunning

    /** 强制结束 wine 客户端进程（不等待）。 */
    fun finishIfRunning() {
        runCatching { executor.stop(true) }
    }

    /** 强制结束并短暂等待（1 秒，对齐上游发行包 shell 收尾节奏）。 */
    fun stopAndWait() {
        runCatching { executor.stopAndWait(true, 1, TimeUnit.SECONDS) }
    }

    /** 追加一行会话日志（stdout/stderr 回调线程调用）。 */
    fun writeLog(line: String) = sink.log(line)

    override fun toString(): String = "WineSession(name=$name, running=$isRunning)"
}

/**
 * 会话日志接收器：内存滚动缓冲（供 UI "Wine 输出"面板）+ 可选文件落盘。
 *
 * 文件写全部为追加写、按行 flush，多线程（stdout/stderr/决策行）经
 * [fileLock] 串行化；目录不可写时静默降级为纯内存（对齐旧版零阻断原则）。
 */
internal class SessionLogSink(
    val key: String,
    val logFile: File?
) {

    private val buffer = StringBuilder()
    private val fileLock = Any()

    // v3.3.40【性能】：缓冲文件写（见 log() 内注释）
    private var writer: java.io.BufferedWriter? = null
    private var pendingLines = 0

    /** 注册到 RootfsLauncher.lastLogs 的内存缓冲（UI 读取）。 */
    val text: StringBuilder get() = buffer

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** 追加一行（自动补换行）。 */
    fun log(line: String) {
        synchronized(buffer) {
            buffer.append(line).append('\n')
            if (buffer.length > MAX_BUFFER) {
                val trimmed = buffer.substring(buffer.length - MAX_BUFFER / 2)
                buffer.setLength(0)
                buffer.append(trimmed)
            }
        }
        logFile?.let { f ->
            synchronized(fileLock) {
                // v3.3.40【性能】：缓冲写 —— 旧版 f.appendText 每行
                // open/write/close（chatty wine stderr 一次会话动辄数千次
                // 文件打开）；现懒开一个 BufferedWriter，32 行或 close()
                // 时落盘（进程意外死亡最多丢尾部 32 行，会话日志的内存
                // 缓冲仍有全量）。
                try {
                    if (writer == null) {
                        writer = java.io.BufferedWriter(
                            java.io.FileWriter(f, true), 8 * 1024
                        )
                    }
                    writer?.let { w -> w.write(line); w.newLine() }
                    pendingLines++
                    if (pendingLines >= 32) flushLocked()
                } catch (_: Exception) {
                    writer = null
                }
            }
        }
    }

    /** 带时间戳的决策行（启动链各步骤）。 */
    fun stamp(line: String) = log("[${stamp()}] $line")

    /** 会话收尾：落盘残余缓冲行（幂等）。 */
    fun close() {
        synchronized(fileLock) {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun flushLocked() {
        runCatching { writer?.flush() }
        pendingLines = 0
    }

    companion object {
        private const val MAX_BUFFER = 16384
    }
}
