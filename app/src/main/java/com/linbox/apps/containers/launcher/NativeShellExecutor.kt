package com.linbox.apps.containers.launcher

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 原生子进程执行器 —— LinBox 原生 rootfs 启动架构的基石。
 *
 * 参照上游发行包 的 ShellExecutor（Java）移植为 Kotlin：所有 rootfs 内的
 * 二进制（wine / wineserver / wineboot / pulseaudio / box64 …）都由本类经
 * [ProcessBuilder] 直接 exec，**不经任何终端模拟器、不经 bash 脚本、
 * 不经 PTY**。子进程的 stdin/stdout/stderr 是普通的管道（或按需重定向
 * 到文件），环境变量由 App 端一次性构建为白名单（见 [WineEnvironment]）。
 *
 * 相对旧「终端链路」（Termux PTY → bash -c → linbox-wine 脚本）的收益：
 *  1. 环境零串扰 —— App 直接以 rootfs 视角构建环境，不再需要脚本二次清洗
 *     （LD_PRELOAD / TERMUX_* / 错误 DISPLAY 等"终端遗产"从源头消失）；
 *  2. 结构化日志 —— stdout/stderr 逐行回调实时落盘，不再从 80×24 pty
 *     transcript 里二次抠文本（历史教训：给 wine 会话接 bash 管道曾触发
 *     wineboot SEH 崩溃；ProcessBuilder 管道是 上游发行包 生产验证过的
 *     标准通路，两者机制完全不同）；
 *  3. 生命周期可控 —— 持有 [Process] 句柄，destroy/destroyForcibly/
 *     waitFor 具备进程语义，配合 [WineServerUtils] 完成整棵 wine 树的
 *     干净收尾，不再依赖 `ps -ef | grep | xargs kill` 文本级清理。
 *
 * 线程模型（对齐上游发行包 的教训）：读输出 / 等退出的任务全部跑在
 * 自管 cached daemon 线程池上 —— 绝不能用 ForkJoinPool.commonPool()
 * （阻塞的 readLine() 不会触发补偿建线程，多个常驻子进程会互相饿死，
 * 表现为"容器在跑但日志一个字都没有"）。
 */
class NativeShellExecutor private constructor() {

    private val environment = LinkedHashMap<String, String>()
    private val command = ArrayList<String>()
    private var workingDir: File? = null
    private var redirectFile: File? = null

    @Volatile
    private var process: Process? = null
    private var stdOutFuture: CompletableFuture<Void>? = null
    private var stdErrFuture: CompletableFuture<Void>? = null
    private var execFuture: CompletableFuture<Int>? = null

    private var stdOutConsumer: ((String) -> Unit)? = null
    private var stdErrConsumer: ((String) -> Unit)? = null
    private var exitConsumer: ((Int) -> Unit)? = null

    /** 设定命令（argv 数组，逐元素传递，不经 shell 解析）。 */
    fun setCommand(vararg cmd: String): NativeShellExecutor = apply {
        command.clear()
        cmd.filterTo(command) { it.isNotBlank() }
    }

    /** 追加单个参数（构建器场景）。 */
    fun appendArg(arg: String): NativeShellExecutor = apply {
        if (arg.isNotBlank()) command.add(arg)
    }

    fun getCommand(): List<String> = ArrayList(command)

    /** 覆盖式注入环境变量（在调用方构建的白名单之上叠加）。 */
    fun putEnv(env: Map<String, String>): NativeShellExecutor = apply {
        environment.putAll(env)
    }

    fun putEnv(name: String, value: String): NativeShellExecutor = apply {
        environment[name] = value
    }

    fun removeEnv(name: String): NativeShellExecutor = apply {
        environment.remove(name)
    }

    fun getEnv(name: String): String? = environment[name]

    fun setWorkingDir(dir: File?): NativeShellExecutor = apply {
        workingDir = dir
    }

    /**
     * 把 stdout/stderr 合并重定向到文件（长驻进程用，如 X 服务）。
     * 设置后不再走行回调；仍保留退出回调。
     */
    fun redirectOutputTo(file: File): NativeShellExecutor = apply {
        redirectFile = file
    }

    fun onStdOut(consumer: ((String) -> Unit)?): NativeShellExecutor = apply {
        stdOutConsumer = consumer
    }

    fun onStdErr(consumer: ((String) -> Unit)?): NativeShellExecutor = apply {
        stdErrConsumer = consumer
    }

    fun onExit(consumer: ((Int) -> Unit)?): NativeShellExecutor = apply {
        exitConsumer = consumer
    }

    /**
     * 启动子进程。ProcessBuilder 的 environment() 从当前 App 进程复制，
     * 随后以 [environment] 覆盖 —— 调用方（[WineEnvironment]）负责给出
     * rootfs 白名单并清洗继承的有害键。
     */
    @Throws(IOException::class)
    fun exec(): NativeShellExecutor {
        check(process == null) { "Process is already running." }
        val builder = ProcessBuilder()
        val builderEnv = builder.environment()
        // 清洗继承环境里的危险/串扰键（App 进程正常启动时本就没有它们，
        // 但从被 hook 的环境（LD_PRELOAD 注入器等）拉起时可能出现）
        for (key in SCRUBBED_KEYS) builderEnv.remove(key)
        builderEnv.putAll(environment)
        builder.command(command)
        builder.directory(workingDir)
        redirectFile?.let { file ->
            file.parentFile?.mkdirs()
            builder.redirectErrorStream(true)
            builder.redirectOutput(file)
        }
        val p = builder.start()
        process = p
        if (redirectFile == null) {
            stdOutFuture = startStdReader(
                p.inputStream, stdOutConsumer, "stdout"
            )
            stdErrFuture = startStdReader(
                p.errorStream, stdErrConsumer, "stderr"
            )
        }
        execFuture = startExitFuture(p)
        return this
    }

    val isRunning: Boolean get() = process?.isAlive == true

    /** 向子进程 stdin 写一行（自动补换行）；未运行/失败返回 false。 */
    fun writeStdin(line: String): Boolean {
        val p = process ?: return false
        if (!p.isAlive) return false
        return try {
            val stream = p.outputStream
            stream.write((line + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
            true
        } catch (e: IOException) {
            Log.d(TAG, "stdin write failed.", e)
            false
        }
    }

    fun stop(force: Boolean) {
        process?.let { if (force) it.destroyForcibly() else it.destroy() }
    }

    @Throws(InterruptedException::class)
    fun stopAndWait(force: Boolean, timeout: Long, unit: TimeUnit): Boolean {
        val p = process ?: return true
        if (force) p.destroyForcibly() else p.destroy()
        return p.waitFor(timeout, unit)
    }

    @Throws(InterruptedException::class)
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val p = process ?: return true
        return p.waitFor(timeout, unit)
    }

    /** 同步等待退出码（一次性命令：wineserver -k / wine reg add 等）。 */
    @Throws(InterruptedException::class)
    fun waitFor(): Int {
        val future = execFuture ?: error("Process is not running.")
        return future.join()
    }

    /**
     * 立即返回退出码（v3.3.2-r2 hotfix）。
     *
     * 语义对齐 java.lang.Process.exitValue：进程仍在运行时抛
     * IllegalThreadStateException（调用方通常先 [waitFor] 拿到 true 再取码）。
     * 注意退出码从 [execFuture] 取而不是 [process] —— exec 完成时 IO 线程
     * 会把 process 句柄收尾置空，此时只有 Future 里存着真实退出码；
     * 若 Future 尚差一步完成，join() 只会阻塞几微秒等它落定，无死锁面。
     *
     * @throws IllegalThreadStateException 进程未启动 / 仍在运行
     */
    fun exitValue(): Int {
        val p = process
        if (p != null && p.isAlive) {
            throw IllegalThreadStateException("Process is still running.")
        }
        return execFuture?.join()
            ?: throw IllegalThreadStateException("Process was never started.")
    }

    private fun startStdReader(
        stream: java.io.InputStream,
        consumer: ((String) -> Unit)?,
        label: String
    ): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            try {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        consumer?.invoke(line)
                    }
                }
            } catch (_: InterruptedIOException) {
                Log.d(TAG, "$label read interrupted due to process destroy.")
            } catch (e: IOException) {
                Log.d(TAG, "$label read exception.", e)
            }
        }, IO_EXECUTOR)

    private fun startExitFuture(p: Process): CompletableFuture<Int> =
        CompletableFuture.supplyAsync({
            try {
                val code = p.waitFor()
                stdOutFuture = null
                stdErrFuture = null
                process = null
                code
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
        }, IO_EXECUTOR).handle { code, err ->
            val exit = when {
                err != null -> {
                    Log.d(TAG, "exec exception.", err)
                    0
                }
                else -> code
            }
            exitConsumer?.invoke(exit)
            exit
        }

    companion object {
        private const val TAG = "NativeShell"

        /**
         * 从 App 进程继承环境时必须剔除的键：
         * - LD_PRELOAD：劫持 execve，wine/box64 必炸（终端时代的头号杀手）；
         * - LD_LIBRARY_PATH：继承值会污染符号解析（Mesa/驱动/x11drv）。
         *   需要它的子进程（wine/pulse/box64 目标）一律由调用方经
         *   [putEnv] 显式构建白名单 —— X 服务（app_process）更是明确
         *   要求零库环境（脚本侧 unset 同款，v3.1）；
         * - TERMUX_*：Termux 会话标识，防 rootfs 内脚本误判前缀；
         * - 旧会话日志变量：新链路不再依赖脚本侧日志。
         */
        private val SCRUBBED_KEYS = listOf(
            "LD_PRELOAD",
            "LD_LIBRARY_PATH",
            "TERMUX_VERSION", "TERMUX_APP_PACKAGE", "TERMUX_PREFIX",
            "TERMUX_MAIN_PACKAGE_FILENAME",
            "TERMUX_APP__TERMUX__FILES_PREFIX",
            "LINBOX_SESSION_LOG"
        )

        /**
         * 自管 IO 线程池：cached + daemon。常驻子进程（wine、pulseaudio）
         * 各占 2 个读线程 + 1 个等待线程，commonPool 会被饿死（见类注释）。
         */
        private val IO_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "linbox-native-io").apply { isDaemon = true }
        }

        /** 工厂入口。 */
        fun create(): NativeShellExecutor = NativeShellExecutor()
    }
}
