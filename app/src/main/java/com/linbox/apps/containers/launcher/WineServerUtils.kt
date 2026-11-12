package com.linbox.apps.containers.launcher

import android.util.Log
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.concurrent.TimeUnit

/**
 * Wine 服务进程生命周期工具（移植 上游发行包 WineServerUtils，适配
 * LinBox 的容器/多版本 wine 布局）。
 *
 * wineserver 会把注册表缓存在内存中并异步回写 user.reg 等文件；同时它
 * 是容器内全部 Windows 进程（services.exe / winedevice.exe / rpcss.exe /
 * explorer 桌面 / 游戏进程）的"会话锚点"。因此：
 *
 *  - **启动前必须清**：上次被系统回收时残留的 wineserver 会让新 wine
 *    客户端连上旧会话 —— 现象是"第一次能启动，之后再也起不来"；
 *  - **停止时必须真杀**：只 destroy wine 客户端进程不等于停容器，
 *    `-k` 让 server 自己走 kill_all_processes() 收掉整棵进程树；
 *  - **超时语义**：`-k` 只是"把退出请求递到 server"，server 收尾
 *    （杀进程 + flush 注册表）正常 2 秒出头；其内部兜底（锁不放时
 *    补 SIGKILL）累计约 10.5 秒 —— 外层等待必须覆盖它（12 秒），
 *    否则真卡死时再没人硬杀，残留留到下一次启动。`-w` 只等锁放开
 *    （确认旧会话真没了），6 秒足够。
 *
 * ## v3.3.1：陈旧/冻结 wineserver 硬清理（proton arm64ec 前缀构建
 * ## "无任何输出挂死"的根治）
 *
 * wineserver 守护在 [server 侧 open_master_socket] 里 `setsid()` 脱离
 * 会话树后自管生命周期 —— 它**不在 App 会话执行器的进程组里**：
 * App 停止会话 / 会话被杀 / Android 冻结（cgroup freezer 对后台应用
 * 全组生效）时，这个守护以两种形态残留：
 *
 *  1. **冻结/挂死（活进程、socket 仍在监听、但永不 accept/应答）** ——
 *     新 wine 客户端 `connect()` 成功后阻塞在握手 `receive_fd()` 的
 *     `recvmsg()` 上：**无任何输出、无退出码、system.reg 永不落盘**，
 *     这正是 proton arm64ec 前缀构建挂死的直接载体（会话日志特征：
 *     客户端打出 `linbox: bionic heap tagging disabled` 后戛然而止，
 *     连 wineserver 侧的 heap-tagging 打印都不出现 —— 因为 socket 已
 *     存在，客户端根本不会再拉起新 server）；
 *  2. **已死但 socket 文件残留** —— 客户端要空转 6 次重试（约 5.5s）
 *     才报错，虽可见但拖慢每次启动。
 *
 * `wineserver -k` 救不了形态 1（-k 客户端自己也会阻塞在同一个 socket
 * 上直到超时被杀，冻结的守护毫发无损）—— 必须按 pid 直接 SIGKILL。
 *
 * [hardRecoverStaleServer] 的识别规则（仅 cwd 判据，限本应用 UID ——
 * /proc 他 UID 条目直接不可读）：
 *  - cmdline 含 `wineserver` **且** `/proc/<pid>/cwd` 位于
 *    `$WINEPREFIX/.wineserver/` 之下。wineserver 守护启动时 chdir 进
 *    server 目录（上游 create_server_dir）且永不离开 —— 这是「属于本
 *    前缀」的权威特征，与哪个 wine 槽位/形态无关。
 *    【绝不能加「cmdline 含 wine 目录」的兜底判据】同 wine 安装可同时
 *    跑多个容器（各自前缀各自 server），那会误杀别的前缀的活 server；
 *    -k/-w 客户端等瞬态进程 cwd 不在 server 目录，天然不命中。
 *
 * 清理后删除 `$WINEPREFIX/.wineserver`（socket/lock/core 残留）：新
 * server 启动时上游 acquire_lock 会自行重建，删除只是让这一步确定性
 * 发生而非依赖重试。幂等；前缀全新（无 .wineserver 目录）时零成本。
 */
object WineServerUtils {

    private const val TAG = "WineServerUtils"

    private const val KILL_TIMEOUT_SECONDS = 12L
    private const val WAIT_TIMEOUT_SECONDS = 6L

    /** 残留进程击杀后等待 SIGKILL 落地的时间（cgroup 冻结态进程由内核解冻处理）。 */
    private const val KILL_SETTLE_MS = 300L

    /** v3.2.23 暖启动健康探测超时：真实 wine 客户端（cmd /c exit）完成握手
     *  的时限。健康 server 上 cmd 秒级退出；冻结/挂死的 server 会让客户端
     *  永久阻塞 —— 超时即判不健康，退回全量清理路径。 */
    private const val HEALTH_PROBE_TIMEOUT_SECONDS = 6L

    /**
     * v3.3.40：wineserver 存活性快查（供 [JsonRegistry] 等零进程路径判断
     * 是否需要先收尾 —— server 在跑时它的注册表内存缓存会异步回写，
     * 直接改 user.reg/system.reg 文件必须先停服务；不在跑则零动作）。
     *
     * 判据与 [findStaleWineServers] 同源（cmdline 含 wineserver 且 cwd 在
     * $WINEPREFIX/.wineserver/ 下 —— 仅本前缀维度，不会误判其他容器）。
     */
    fun isServerRunning(prefixPath: String): Boolean =
        findStaleWineServers(prefixPath).isNotEmpty()

    /**
     * 结束指定容器的 wine 会话并等待退出（注册表随之落盘）。
     *
     * v3.3.1：内部升级为三段式 —— `-k`/`-w` 优雅停止（健康 server：注册表
     * 落盘 + kill_all_processes 收进程树）之后追加 [hardRecoverStaleServer]
     * 硬清理（-k 杀不掉的冻结/挂死守护按 pid SIGKILL + 清残留 socket/lock）。
     * 原 API 保持不变，全部既有调用方自动获得硬清理能力。
     *
     * @param resolved  wine 运行时定位结果（可为 null —— 依已保存的
     *                  会话信息重新解析；两处调用方语义见下）
     * @param container 容器配置（提供 WINEPREFIX 与 wine= 槽位）
     * @param env       会话环境（作为 wineserver 的运行环境）
     */
    fun stop(resolved: WineRuntime.Resolved?, container: ContainerData, env: Map<String, String>) {
        stopInternal(resolved, container, env) {}
    }

    /**
     * 会话启动前的 wineserver 状态清洁（v3.3.1）。
     *
     * 与 [stop] 同一三段式实现，另把每一步决策行回报给调用方（进会话日志
     * / 前缀日志，用户可见）。典型耗时：健康残留 ~2s（-k 收尾）、冻结残留
     * ~12s（-k 超时后硬杀）、无残留 <0.5s。
     *
     * v3.2.23（用户问题 6）新增两段快速路径（[warmStart]=true 时）：
     *  - 无任何存活 server 且无 socket 目录 → 直接返回（免 -k/-w 进程
     *    拉起，正常冷启动场景零开销）；
     *  - 有存活 server 且健康探测通过（真实 wine 客户端 `cmd /c exit`
     *    秒级完成握手）→ **暖启动复用**：跳过 -k/-w 与硬清理，存活
     *    server 的内存注册表缓存、后台服务（rpcss/explorer 桌面等）
     *    全部保留 —— arm64ec 上冷启动 server + 重读注册表是 exe 二次
     *    启动明显慢于 x86_64 的主要来源；顺带修复“运行 exe 会把同容器
     *    正在跑的 X11 桌面会话连根杀掉”。探测超时（冻结/挂死）则退回
     *    原三段式全量清理 —— v3.3.1 的挂死根治语义不变。
     *
     * @param warmStart 仅在“无运行中会话且非前缀构建”时由调用方开启
     *        （避免改变 runExe 替换旧会话 / wineboot 冷启动语义）。
     */
    fun ensureCleanBeforeStart(
        resolved: WineRuntime.Resolved?,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {},
        warmStart: Boolean = false
    ) {
        if (warmStart) {
            val prefix = container.prefixPath
            val live = findStaleWineServers(prefix)
            val serverRoot = File(prefix, ".wineserver")
            if (live.isEmpty() && !serverRoot.isDirectory) {
                log("wineserver 快速路径：无残留进程/目录，跳过 -k/-w")
                return
            }
            if (live.isNotEmpty()) {
                if (probeServerHealth(resolved, container, env)) {
                    log("暖启动：wineserver 存活且健康（pid=${live.first().pid}）→ 复用，跳过清理（注册表缓存/后台进程保留）")
                    return
                }
                log("wineserver 存活但健康探测超时（疑似冻结/挂死）→ 转全量清理")
            }
        }
        stopInternal(resolved, container, env, log)
    }

    /**
     * 真实客户端握手探测：拉起 `wine cmd /c exit`（后台启动，不建会话）
     * 在 [HEALTH_PROBE_TIMEOUT_SECONDS] 内完成即认为 server 健康。
     *
     * 为什么不用进程状态启发式（R/S 判定）：冻结在 accept/recvmsg 上的
     * server 在 /proc 里同样呈现 S 态，无法区分；只有让真实客户端走一遍
     * socket 握手才能实锤。探测客户端本身无害（cmd 即退，无副作用），
     * 探测超时则强停并判不健康。
     */
    private fun probeServerHealth(
        resolved: WineRuntime.Resolved?,
        container: ContainerData,
        env: Map<String, String>
    ): Boolean {
        val active = resolved ?: return false
        return try {
            val command = NativeShellExecutor.create()
                .putEnv(env)
                .putEnv("WINEPREFIX", container.prefixPath)
                .putEnv("WINEDEBUG", "-all")
            command.setCommand(
                *WineRuntime.wineCommand(active).toTypedArray(),
                "cmd", "/c", "exit"
            )
            command.exec()
            val finished = command.waitFor(HEALTH_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { command.stopAndWait(true, 5, TimeUnit.SECONDS) }
                false
            } else {
                command.waitFor() == 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun stopInternal(
        resolved: WineRuntime.Resolved?,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit
    ) {
        val active = resolved ?: runCatching { WineRuntime.resolve(container) }.getOrNull()
        if (active == null) {
            Log.e(TAG, "wineserver cannot be located for container=${container.name}; " +
                    "stale wine processes may be left behind.")
            // v3.3.1：wine 运行时解析失败（wine 被卸载等）也要硬清理 ——
            // 硬清理的 cwd 匹配不依赖 wine 目录，仅按 $WINEPREFIX 维度扫
            hardRecoverStaleServer(container, log)
            return
        }
        val wineServerBin = WineRuntime.hostToolBin(active, "wineserver")
        if (!wineServerBin.isFile) {
            Log.e(TAG, "wineserver binary not found at $wineServerBin.")
            // 二进制缺失（wine 被卸载等）→ 直接硬清理残留（按前缀维度扫）
            hardRecoverStaleServer(container, log)
            return
        }

        val prefix = container.prefixPath
        for (option in listOf("-k", "-w")) {
            val command = NativeShellExecutor.create()
                .putEnv(env)
                .putEnv("WINEPREFIX", prefix)
            try {
                command.setCommand(
                    *WineRuntime.hostCommand(active, wineServerBin).toTypedArray(),
                    option
                )
                command.exec()
                val timeout = if (option == "-k") KILL_TIMEOUT_SECONDS else WAIT_TIMEOUT_SECONDS
                if (!command.waitFor(timeout, TimeUnit.SECONDS)) {
                    command.stop(true)
                    // v3.3.1：-k 超时 = server 冻结/挂死的高置信信号（健康
                    // server 收尾 2s 出头）—— 决策行进日志，随后由
                    // hardRecoverStaleServer 按 pid 硬杀
                    log("wineserver $option 无响应（${timeout}s 超时）—— 守护疑似冻结/挂死，转硬清理")
                    Log.w(TAG, "wineserver $option timed out after ${timeout}s.")
                }
            } catch (e: Exception) {
                command.stop(true)
                Log.w(TAG, "Failed to run wineserver $option.", e)
            }
        }

        hardRecoverStaleServer(container, log)
    }

    // ------------------------------------------------------------------
    // v3.3.1：陈旧/冻结 wineserver 硬清理
    // ------------------------------------------------------------------

    /** 残留 wineserver 进程描述（pid + cmdline + cwd）。 */
    private data class StaleServer(val pid: Int, val cmdline: String, val cwd: String?)

    /**
     * 按 /proc 扫描并 SIGKILL 残留（冻结/挂死）的 wineserver 守护，随后
     * 清除 `$WINEPREFIX/.wineserver` 残留 socket/lock。
     *
     * 幂等、不抛出；无 `.wineserver` 目录（全新前缀）时直接返回 false，
     * 正常启动零开销。返回 true 表示本次确有清理动作（决策行已回报 log）。
     */
    internal fun hardRecoverStaleServer(
        container: ContainerData,
        log: (String) -> Unit
    ): Boolean {
        return try {
            hardRecoverImpl(container, log)
        } catch (e: Exception) {
            Log.w(TAG, "hardRecoverStaleServer failed for ${container.name}.", e)
            false
        }
    }

    private fun hardRecoverImpl(
        container: ContainerData,
        log: (String) -> Unit
    ): Boolean {
        val serverRoot = File(container.prefixPath, ".wineserver")
        if (!serverRoot.isDirectory) return false

        val stale = findStaleWineServers(container.prefixPath)
        val socketExists = serverRoot.listFiles()
            ?.any { dir -> dir.isDirectory && File(dir, "socket").exists() } ?: false

        if (stale.isEmpty() && !socketExists) return false

        if (stale.isNotEmpty()) {
            for (p in stale) {
                log("检测到残留 wineserver（pid=${p.pid} cmd=${p.cmdline.take(80)}" +
                    (p.cwd?.let { " cwd=$it" } ?: "") + "）—— " +
                    "冻结/挂死的守护会让新客户端永久阻塞在握手上（前缀构建无输出挂死的直接载体），SIGKILL 清理")
                runCatching { android.os.Process.killProcess(p.pid) }
            }
            // 等 SIGKILL 落地（冻结态进程由内核解冻后处理 KILL）
            runCatching { Thread.sleep(KILL_SETTLE_MS) }
        } else {
            log("检测到陈旧 wineserver socket（无存活进程）—— 清理 ${serverRoot.absolutePath}")
        }
        runCatching { com.linbox.util.SafeFileOps.deleteTree(serverRoot) }
        if (stale.isNotEmpty()) {
            log("已硬清理残留 wineserver ×${stale.size} 并移除 ${serverRoot.absolutePath}（新 server 启动时自行重建）")
        }
        return true
    }

    /**
     * 扫描 /proc 找出本前缀的残留 wineserver 进程。
     *
     * 规则见类 KDoc（仅 cwd 判据）。他 UID 的 /proc 条目读取即失败，
     * 天然过滤，无越权。
     */
    private fun findStaleWineServers(prefixPath: String): List<StaleServer> {
        val selfPid = android.os.Process.myPid()
        val serverDirPrefix = File(prefixPath, ".wineserver").absolutePath + "/"
        val out = ArrayList<StaleServer>()
        val entries = runCatching {
            File("/proc").listFiles { _, name -> name.toIntOrNull() != null }
        }.getOrNull() ?: return out

        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == selfPid) continue
            val cmdline = readCmdline(pid) ?: continue
            if (cmdline.isEmpty() || !cmdline.contains("wineserver")) continue

            val cwd = runCatching { android.system.Os.readlink("/proc/$pid/cwd") }.getOrNull()
            if (cwd == null || !cwd.startsWith(serverDirPrefix)) continue
            out.add(StaleServer(pid, cmdline, cwd))
        }
        return out
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

    /**
     * 静默兜底：容器配置缺失（wine 已被卸载等）时，仅按 rootfs 主槽位
     * 尝试停服务。失败只记日志，绝不抛出 —— 清理失败不能阻断启动。
     */
    fun stopBestEffort(containerName: String) {
        runCatching {
            val container = com.linbox.apps.containers.ContainerManager.get(containerName)
                ?: return
            val env = WineEnvironment.buildBaseEnv(
                container,
                File(ContainerManager.WINE_OPT_DIR, "wine")
            )
            stop(null, container, env)
        }
    }
}
