package com.linbox.apps.containers.launcher

import android.content.Context
import android.os.Build
import com.linbox.apps.containers.ContainerAudio
import com.linbox.apps.containers.ContainerBackend
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerManager
import com.linbox.apps.containers.DisplayMode
import com.linbox.apps.containers.GuanliSupport
// v3.1.7 无 WM 补偿：wine 虚拟桌面几何取当前 X 屏幕尺寸（实时值 → 落盘值 → 默认）
import com.linbox.apps.x11.X11FitClient
import com.linbox.apps.x11.X11ResolutionLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 原生 rootfs 启动编排器 —— LinBox 原生启动架构的中枢。
 *
 * 对齐上游发行包 Launcher 的编排模型（resolve → 目录检查 → 显示就绪 →
 * 清残留会话 → 环境构建 → 前缀/注册表准备 → exec），但**显示环节是
 * X11**（[X11Support]：App 内嵌 termux-x11/libXlorie），不是 Weston/
 * Wayland —— 这是 LinBox 与 上游发行包 的方案差异，按需求保留 X11。
 *
 * 与旧「终端链路」的本质区别：wine 是 [NativeShellExecutor] 直接 exec
 * 的原生子进程，**不再存在 bash / linbox-wine 脚本 / PTY / 终端模拟器**
 * 任何一环。脚本侧的职责全部由本类的 Kotlin 步骤等价承接：
 *
 *  ```
 *   resolveContainer / resolveWineDir     → [WineRuntime.resolve]
 *   isolate_env                           → [WineEnvironment.buildBaseEnv] + GPU/Box64/音频叠加
 *   audio_setup                           → [PulseAudioManager.ensureServer]
 *   ensure_x11                            → [X11Support.ensureX11Window] + [X11Support.awaitSocketReady]
 *   首次 wineboot -i                       → [WineRegistry.initializePrefixIfNeeded]
 *   ensure_emulate_modeset                → [WineRegistry.ensureEmulateModeset]
 *   apply_display_mode / -f 清蓝底          → [applyDisplayMode]
 *   run_wine                              → [buildCommand] + NativeShellExecutor.exec
 *  ```
 *
 * 启动前清 wineserver 残留（对齐上游发行包 launch 链）：上次会话的
 * server 若还在，新客户端会连上旧会话 —— "第一次能启动，之后再也起
 * 不来"的根因；注册表内存缓存也是它，不清则后续注册表写入被无视。
 */
object RootfsLauncher {

    private const val TAG = "RootfsLauncher"

    /** 允许的最大并发会话数（超龄会话先回收）。 */
    private const val MAX_SESSIONS = 8

    /** 真实会话表：容器名（或 "__install__"）→ 会话。 */
    private val sessions = LinkedHashMap<String, WineSession>()

    /**
     * 启动链进行中的容器 → 世代令牌（同容器并发提交时最新令牌胜出，
     * 旧链在 exec 前自检令牌失配即放弃 —— 替代旧版"同名旧会话先回收"语义）。
     */
    private val pendingLaunches =
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * UI 会话视图：真实会话 + 启动中的容器（后者值为 null 占位，
     * containsKey 语义成立 —— 与旧版"spawn 即登记"的运行状态即时性一致）。
     */
    val activeSessions: Map<String, WineSession?>
        get() = synchronized(sessions) {
            val view = LinkedHashMap<String, WineSession?>(sessions)
            for (k in pendingLaunches.keys) view.putIfAbsent(k, null)
            view
        }

    /** 每容器最近一次启动的日志（简易环形，UI 查看）。 */
    val lastLogs = LinkedHashMap<String, StringBuilder>()

    // ------------------------------------------------------------------
    // v3.1.6 前缀构建日志（仅 wineboot -i 阶段；用户诉求：构建前缀日志显示）
    // ------------------------------------------------------------------

    /** 前缀构建进行中的容器名（null = 无）；UI 据此自动弹出前缀日志窗口。 */
    private val _prefixBuildFlow = MutableStateFlow<String?>(null)
    val prefixBuildFlow: StateFlow<String?> get() = _prefixBuildFlow

    /** 前缀构建专用日志通道（容器名 → 累积文本，与主会话日志分离）。 */
    private val prefixLogLock = Any()
    private val prefixLogs = LinkedHashMap<String, StringBuilder>()

    internal fun prefixLogLine(key: String, line: String) {
        synchronized(prefixLogLock) {
            prefixLogs.getOrPut(key) { StringBuilder() }.append(line).append('\n')
        }
    }

    /** 读取容器的前缀构建日志（无则空串）。 */
    fun prefixLogOf(key: String): String = synchronized(prefixLogLock) {
        prefixLogs[key]?.toString() ?: ""
    }

    /** 清除容器的前缀构建日志（容器删除时）。 */
    fun clearPrefixLog(key: String) = synchronized(prefixLogLock) { prefixLogs.remove(key) }

    /**
     * 启动链线程池：cached + daemon。链内含 X11 就绪等待 / wineserver
     * 收尾（最长 18s）/ 首次 wineboot（分钟级）—— 绝不能占主线程，
     * 也不能用会饿死阻塞任务的 commonPool。
     */
    private val LAUNCH_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "linbox-launcher").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------
    // 对外入口（由 WineSessionLauncher 门面转发）
    // ------------------------------------------------------------------

    /**
     * 在指定容器中运行 exe。
     *
     * 同步预检（rootfs 就绪 / wine 可解析）立即给出错误；通过后异步
     * 执行完整启动链，日志进 lastLogs。
     *
     * @param exe  Windows 程序路径（C: 相对或绝对）
     * @param args 传给 exe 的参数（空白切分，与旧 bash 词切分语义一致）
     */
    fun runExe(context: Context, container: ContainerData, exe: String, args: String): String? {
        precheckRun(container)?.let { return it }
        val base = buildList {
            add(exe)
            args.trim().takeIf { it.isNotEmpty() }?.let {
                addAll(it.split(Regex("\\s+")))
            }
        }
        // v3.3.9（用户问题 2）：高级启动选项按 startfex.sh / startwine.sh
        // 语义**追加在 exe 之后**（additional_args）：强制DX11 → -dx11；
        // OpenGL → --rendering-driver opengl3；Unity → -force-gfx-direct；
        // 自定义参数（uargs）同位追加。与 base 内已有参数去重。
        val wineArgs = base + launchAdditionalArgs(container, base)
        return submitLaunch(context, container, wineArgs, freshSession = true)
    }

    /**
     * v3.3.9：startfex.sh / startwine.sh 的 additional_args 段移植 ——
     * 选项开启时在 exe 参数末尾追加（顺序与脚本一致：unity → opengl →
     * dx11 → 用户自定义）。仅对 runExe（游戏/程序会话）生效，
     * wineboot / wineserver 等宿主工具命令不追加。
     */
    private fun launchAdditionalArgs(container: ContainerData, base: List<String>): List<String> =
        buildList {
            if (container.hasFlag(com.linbox.apps.containers.ContainerFlags.UNITY) &&
                base.none { it == "-force-gfx-direct" }
            ) add("-force-gfx-direct")
            if (container.hasFlag(com.linbox.apps.containers.ContainerFlags.OPENGL) &&
                base.none { it == "--rendering-driver" }
            ) { add("--rendering-driver"); add("opengl3") }
            if (container.hasFlag(com.linbox.apps.containers.ContainerFlags.DX11) &&
                base.none { it == "-dx11" }
            ) add("-dx11")
            container.uargs.trim().takeIf { it.isNotEmpty() }?.let {
                addAll(it.split(Regex("\\s+")).filter { t -> t.isNotBlank() })
            }
        }

    /**
     * v3.3.8：运行前同步预检（rootfs 就绪 / wine 目录可解析）—— UI 按钮
     * 先行给出错误（不启动链）；与 runExe 的同步段同一套检查。
     */
    fun precheckRun(container: ContainerData): String? {
        if (!ContainerManager.rootfsReady()) {
            return "rootfs 未就绪：请先导入包含 linbox-container 的 bionic rootfs"
        }
        return try {
            WineRuntime.resolve(container)
            null
        } catch (e: Exception) {
            e.message ?: "wine 运行时解析失败：${e.javaClass.simpleName}"
        }
    }

    /**
     * 运行 wine 子命令（wineboot -i / winecfg / regedit / cmd /
     * wineserver …）。参数空白切分后作为 wine argv 传入。
     */
    fun runWineCmd(context: Context, container: ContainerData, wineCmd: String): String? {
        if (!ContainerManager.rootfsReady()) {
            return "rootfs 未就绪：请先导入包含 linbox-container 的 bionic rootfs"
        }
        val wineArgs = wineCmd.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (wineArgs.isEmpty()) return "空的 wine 命令"
        // 附加语义：不打断运行中的会话（对齐旧脚本 linbox-container cmd）
        return submitLaunch(context, container, wineArgs, freshSession = false)
    }

    /**
     * 通过原生 WineOps 安装 wine 发行包（v3.3.39：彻底去 bash ——
     * 旧版经 App 侧 bash + linbox-container CLI，是管理链路最后一块
     * 「终端启动」残留；现改为 Kotlin 原生：URL → [GuanliSupport.WineOps.download]
     * 下载 / 本地路径直用 → [GuanliSupport.WineOps.installFromTar] 解包
     * （tar 二进制 + Java 回退双路径）→ 可选设默认槽位。进度实时流式
     * 进 lastLogs["__install__"]，与 UI 的「WineOps 安装」同一条代码路径）。
     */
    fun installWine(
        context: Context,
        src: String,
        slotName: String?,
        mkDefault: Boolean
    ): String? {
        if (!ContainerManager.rootfsReady()) {
            return "rootfs 未就绪：请先导入包含 linbox-container 的 bionic rootfs"
        }
        val key = "__install__"
        val sink = SessionLogSink(key, newSessionLog(key))
        sink.log("[LinBox] 安装 wine（原生链）：$src" + (slotName?.let { "（槽位 $it）" } ?: ""))
        lastLogs[key] = sink.text

        // 安装链在独立线程执行（下载/解包分钟级）；不登记 wine 会话
        //（安装不是 wine 进程 —— UI 状态由 lastLogs["__install__"] 流驱动）
        Thread({
            try {
                val slot = slotName?.trim().orEmpty()
                // 1) 源：URL 下载 / 本地文件直用
                val local: File = if (src.trim().startsWith("http://") || src.trim().startsWith("https://")) {
                    sink.log("[LinBox] 下载中：${src.trim()}")
                    try {
                        GuanliSupport.WineOps.download(src.trim()) { sink.log(it) }
                    } catch (e: Exception) {
                        sink.stamp("下载失败：${e.message ?: e.javaClass.simpleName}")
                        return@Thread
                    }
                } else {
                    File(src.trim()).also { if (!it.isFile) {
                        sink.stamp("文件不存在：${src.trim()}")
                        return@Thread
                    } }
                }
                // 2) arch：x86_64（盒装 wine 发行包默认；linbox-container 同款默认）
                val arch = "x86_64"
                sink.log("[LinBox] 解包安装 → ${ContainerManager.WINE_OPT_DIR}/$slot")
                val err = GuanliSupport.WineOps.installFromTar(local.absolutePath, slot, arch)
                if (err != null) {
                    sink.stamp("安装失败：$err")
                    return@Thread
                }
                // 3) 可选设默认槽位（写入全局 container.conf，与 CLI 同格式）；
                //    槽位名推导与 installFromTar 逐字镜像（sanitize 作用于
                //    显式槽位与派生名两分支），保证 default_wine 指向真实目录。
                val finalSlot = slot.ifBlank {
                    local.nameWithoutExtension.let { n ->
                        n.removeSuffix(".tar").removePrefix("wine-").ifEmpty { "wine" }.let { "wine-$it" }
                    }
                }.replace(Regex("[^A-Za-z0-9._-]"), "-")
                if (mkDefault) {
                    val derr = GuanliSupport.WineOps.setDefaultWine(finalSlot)
                    if (derr != null) sink.stamp("⚠ 设默认槽位失败：$derr")
                    else sink.stamp("已设默认 wine 槽位：$finalSlot")
                }
                sink.stamp("安装完成：$finalSlot（${ContainerManager.WINE_OPT_DIR}/$finalSlot）")
            } catch (e: Exception) {
                sink.stamp("安装异常：${e.message ?: e.javaClass.simpleName}")
            }
        }, "linbox-install-wine").apply { isDaemon = true; start() }
        return null
    }

    /**
     * 停止容器内全部 wine 进程。
     *
     * 顺序（对齐上游发行包 clean()）：先收 wine 客户端会话，再
     * wineserver -k / -w 让 server 自己 kill_all_processes() 收掉
     * 整棵进程树（services.exe / winedevice.exe / explorer / 游戏）。
     */
    fun stopContainer(context: Context?, name: String): String? {
        // 启动链进行中：摘牌（链尾 exec 前的令牌自检会放弃登记）
        pendingLaunches.remove(name)
        synchronized(sessions) { sessions.remove(name) }?.let { it.stopAndWait() }
        lastLogs.remove(name)
        if (_prefixBuildFlow.value == name) _prefixBuildFlow.value = null
        clearPrefixLog(name)
        if (!ContainerManager.rootfsReady()) return null
        return try {
            val container = ContainerManager.get(name)
            if (container != null) {
                val resolved = runCatching { WineRuntime.resolve(container) }.getOrNull()
                val env = WineEnvironment.buildBaseEnv(
                    container,
                    resolved?.wineDir ?: File(ContainerManager.WINE_OPT_DIR, "wine")
                )
                WineServerUtils.stop(resolved, container, env)
            }
            null
        } catch (e: Exception) {
            "停止失败：${e.message}"
        }
    }

    /** 静默停止（容器删除等内部路径调用；不产生任何 UI 反馈）。 */
    fun stopContainerQuietly(name: String) {
        runCatching { stopContainer(null, name) }
    }

    // ------------------------------------------------------------------
    // v3.3.29：tabtip.exe 会话级压制（App 原生链路）
    // ------------------------------------------------------------------

    /** 压制线程单例闸（多容器/多会话并发时只跑一个扫杀线程）。 */
    private val tabtipSuppressing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * v3.3.29：wine 会话存活期间的 tabtip.exe 扫杀线程。
     *
     * 根因（用户设备日志 wine-10arm-20261109-120811 实证）：opt 生态前缀
     * 注入的 tabtip.exe（触摸键盘辅助进程）在会话 boot 后被拉起，对游戏
     * 窗口做 UI Automation / MSAA 查询（oleacc:find_class_data +
     * uiautomation:uia_get_providers_for_hwnd），查询经 WM_GETOBJECT
     * 同步消息送达 32 位游戏的窗口过程 —— 仙剑四等 2000 年代 MFC 老游戏
     * 处理该消息时解引用野指针崩溃（page fault on read access）。
     * v3.3.28 的会话启动前一次性拦截扑空（tabtip 尚未拉起）。
     *
     * 机制：每 3s 扫 /proc 数字目录的 cmdline，发现 tabtip.exe 即 kill；
     * wineserver 消失（容器会话结束）自止；全局单例闸防多线程叠加。
     * LinBox 有自己的输入链路（安卓 IME → wine），tabtip 纯属冗余。
     */
    private fun startTabtipSuppression() {
        if (!tabtipSuppressing.compareAndSet(false, true)) return
        Thread({
            try {
                while (true) {
                    var serverAlive = false
                    val procs = File("/proc").listFiles { f -> f.name.all { it.isDigit() } }
                    if (procs.isNullOrEmpty()) break
                    for (p in procs) {
                        val cmd = runCatching {
                            File(p, "cmdline").readBytes().toString(Charsets.ISO_8859_1)
                        }.getOrNull() ?: continue
                        if (cmd.contains("wineserver")) {
                            serverAlive = true
                        } else if (cmd.contains("tabtip.exe")) {
                            // 同 UID 子进程（wine 会话树），可直接发信号
                            runCatching { android.os.Process.sendSignal(p.name.toInt(), 9) }
                        }
                    }
                    if (!serverAlive) break
                    // v3.3.40【性能】：3s → 10s —— 每 3s 全量扫 /proc（读每个
                    // 进程的 cmdline）在长会话里是持续的系统调用负载；
                    // tabtip 拉起后 10s 内被杀同样可靠（它只在启动早期出现）。
                    Thread.sleep(10000)
                }
            } catch (_: InterruptedException) {
            } catch (_: Throwable) {
            } finally {
                tabtipSuppressing.set(false)
            }
        }, "linbox-tabtip-suppress").apply { isDaemon = true; start() }
    }

    /** 当前是否存在可用的 X11 socket（App 侧）。 */
    fun x11SocketReady(): Boolean = X11Support.x11SocketReady()

    /** X11 桌面窗口联动（外部诊断/手动路径用）。 */
    fun ensureX11Window(context: Context) = X11Support.ensureX11Window(context)

    /**
     * 解析 exe 的工作目录（v3.1 新增）。
     *
     * 游戏几乎都按 CWD 相对路径找资源（DX9/DXUT 样例的 media\、各引擎
     * 的 data\ 等）。旧版把 CWD 写死为 rootfs/home，用户从 App 运行
     * /storage/…/CubeMap.exe 时 media 永远找不到 —— 终端时代用户会先
     * cd 到游戏目录再跑 wine，App 侧一直没有等价物，这就是回归。
     *
     * 规则：
     *  - Unix 绝对路径（/storage/…）→ exe 父目录（目录不存在则兑底 HOME）；
     *  - Windows 盘符路径（Z:\… / C:\…）→ 经 dosdevices 盘符链接映射回
     *    Unix 后取父目录（z: → /，c: → drive_c）；
     *  - 相对路径 / 子命令（winecfg 等）→ null（维持 HOME，与终端一致）。
     */
    internal fun resolveExeWorkingDir(exe: String?, container: ContainerData): File? {
        if (exe == null) return null
        val p = exe.trim()
        if (p.isEmpty()) return null
        if (p.startsWith("/")) {
            return File(p).parentFile?.takeIf { it.isDirectory }
        }
        val winPath = Regex("^([A-Za-z]):[\\\\/](.*)$").find(p) ?: return null
        val drive = winPath.groupValues[1].lowercase() + ":"
        val rest = winPath.groupValues[2].replace('\\', '/')
        val base = File(File(container.prefixPath, "dosdevices"), drive)
        if (!base.isDirectory) return null
        if (rest.isBlank()) return base
        return File(base, rest).parentFile?.takeIf { it.isDirectory } ?: base
    }

    // ------------------------------------------------------------------
    // v3.3.6（用户定稿）：noexec 存储**零拷贝**直读执行 —— 游戏 exe 按
    // 原路径直接启动，不再有任何执行区/镜像机制（v3.2.23 整目录导入、
    // v3.3.5 PE 件落地两代机制全部移除；对齐上游同类项目 ——
    // 两者都直接从 /storage/emulated/0 跑游戏，没有 PE 落地机制）
    // ------------------------------------------------------------------

    /**
     * 判断路径是否落在 noexec 挂载上。
     *
     * 读 /proc/self/mounts（本进程视角，含 sdcardfs/FUSE 项），取**最长
     * 前缀**挂载点的挂载选项判定 —— /storage/emulated/0 的 sdcardfs
     * 在 Android 上恒为 noexec，应用无法 remount。挂载点里的空格转义
     * （\040 等）按 kernel 语义还原。
     *
     * noexec 的确切语义（v3.3.6 内核实验 + wine 源码逐行核实，勘误
     * v3.3.5 注释）：拦 execve() 与**文件映射**的执行位 —— 带执行位
     * mmap 直接 EPERM，先 mmap RW 再 mprotect(+EXEC) 也 EACCES；
     * **匿名内存不受限**（匿名页加执行位照常允许）；普通读写完全不受
     * 影响。因此 wine 加载 noexec 上的 PE 镜像有一条官方回退路：带执行
     * 位 mmap 失败 → 改用匿名页 + pread() 读入文件内容 + 再加执行位
     * —— 这正是上游参考实现直跑外部存储游戏的机制（其 wine-9.2-custom
     * 删掉了共享映射硬失败，bionic-rootfs 补丁 0011/0014 系列已把同款
     * 回退补进全部构建形态）。
     */
    internal fun isNoexecPath(path: String): Boolean {
        return try {
            val target = File(path).absolutePath
            var bestLen = -1
            var noexec = false
            File("/proc/self/mounts").readLines().forEach { line ->
                val parts = line.split(' ')
                if (parts.size >= 4) {
                    val mnt = unescapeMountPath(parts[1])
                    val isPrefix = target == mnt || target.startsWith("$mnt/")
                    if (isPrefix && mnt.length > bestLen) {
                        bestLen = mnt.length
                        noexec = parts[3].split(',').any { it == "noexec" }
                    }
                }
            }
            bestLen >= 0 && noexec
        } catch (_: Exception) {
            false
        }
    }

    /** /proc/self/mounts 挂载点转义还原（\040 空格 \011 制表 \134 反斜杠）。 */
    private fun unescapeMountPath(s: String): String =
        s.replace("\\040", " ").replace("\\011", "\t").replace("\\134", "\\")

    /**
     * /storage、/sdcard 下的 Unix 路径 → Z: 盘 DOS 路径（v3.3.6）。
     *
     * wine 只能启动能换算成 dosdevices 盘符的路径：d:/e: 只覆盖
     * Download/exagear，游戏放在外部存储其他目录时 Unix 路径无法映射。
     * Z: 盘直链内部 rootfs（ensureDosdevices），rootfs 内的 /storage 与
     * /sdcard 入口（WineRegistry.ensureRootfsStorageEntries）让
     * Z:\storage\emulated\0\… 与 Z:\sdcard\… 直达外部存储同名路径 ——
     * 本函数把没被任何盘符覆盖的 /storage|/sdcard Unix 路径改写成
     * Z: 形态（指向同一份文件，零拷贝）。
     *
     * @return DOS 路径（如 Z:\storage\emulated\0\Games\a.exe）；已能被
     *         现有盘符覆盖（d:/e: 直链路径）、入口未就绪或不是
     *         /storage|/sdcard 路径时返回 null（维持原样交 wine 处理）。
     */
    internal fun normalizeStorageExePath(
        unixPath: String,
        container: ContainerData,
        log: (String) -> Unit
    ): String? {
        if (!unixPath.startsWith("/storage/") && !unixPath.startsWith("/sdcard/")) return null

        // 已被现有盘符覆盖 → 交给 wine 原生换算（d:/e: 直链路径原样可用）
        val dd = File(File(container.prefixPath), "dosdevices")
        if (dd.isDirectory) {
            dd.listFiles().orEmpty().forEach { drive ->
                if (drive.name.matches(Regex("^[a-z]:$"))) {
                    val target = runCatching { android.system.Os.readlink(drive.absolutePath) }.getOrNull()
                    if (target != null && target.startsWith("/") &&
                        (unixPath == target || unixPath.startsWith("$target/"))
                    ) return null
                }
            }
        }

        // Z: 盘与 rootfs 入口就绪才改写（入口由 WineRegistry 在前缀链路建好）
        val zLink = File(dd, "z:")
        val zTarget = runCatching { android.system.Os.readlink(zLink.absolutePath) }.getOrNull()
        if (zTarget != ContainerManager.ROOTFS_ROOT) return null
        val prefix = if (unixPath.startsWith("/storage/")) {
            if (!File(ContainerManager.ROOTFS_ROOT, "storage").exists()) return null
            "/storage"
        } else {
            if (!File(ContainerManager.ROOTFS_ROOT, "sdcard").exists()) return null
            "/sdcard"
        }
        val dos = "Z:" + unixPath.removePrefix(prefix).replace('/', '\\')
        log("外部存储路径改写: $unixPath → $dos（Z: 盘 rootfs 入口直达，零拷贝）")
        return dos
    }

    /**
     * v3.3.6 定稿（用户原话：「真·完全零拷贝…上游同类项目都是
     * 直接从 /storage/emulated/0 跑游戏」—— v3.2.23 整目录导入与
     * v3.3.5 PE 件落地两代机制全部废除）。
     *
     * exe 位于外部存储（noexec）时**按原路径直接启动**，不复制任何文件：
     *  - wine 经 d:/e: 盘（dosdevices 软链）与 Z: 盘 rootfs 入口
     *    （Z:\storage、Z:\sdcard）直读游戏文件 —— 读取不受 noexec 影响；
     *  - PE 可执行段由 wine 的匿名内存回退加载（带执行位 mmap 失败 →
     *    匿名页 + pread + 再加执行位；见 [isNoexecPath] 注释与
     *    bionic-rootfs 的 wine 补丁 0011/0014 系列）；
     *  - **旧两代机制残留一次性清理**（v3.2.23~v3.3.4 整目录导入区
     *    rootfs/home/.linbox/games/<容器>/、v3.3.5 PE 镜像区
     *    <容器>/games/）—— 首次启动清掉、立刻释放内部存储，此后不再
     *    产生（walkTopDown 统计只用于日志报数）；
     *  - **安卓直读权限预检** —— 进程读不到源目录时大声指引授权（无
     *    外部存储读权限正是“很多游戏 media 错误”的源头之一）。
     */
    private fun prepareNoexecDirectRead(
        wineArgs: List<String>,
        container: ContainerData,
        log: (String) -> Unit
    ): List<String> {
        val exePath = wineArgs.firstOrNull()?.trim() ?: return wineArgs
        if (!exePath.startsWith("/")) return wineArgs
        val exeFile = File(exePath)
        if (!exeFile.isFile) return wineArgs
        val onExternalStorage = exePath.startsWith("/storage/") || exePath.startsWith("/sdcard/")
        if (!onExternalStorage) return wineArgs

        val srcDir = exeFile.parentFile ?: return wineArgs

        // ① Unix 路径 → Z: 盘 DOS 形态（没被 d:/e: 覆盖的外部存储路径）
        val effectiveArgs = normalizeStorageExePath(exePath, container, log)?.let { dos ->
            listOf(dos) + wineArgs.drop(1)
        } ?: wineArgs

        runCatching {
            // ② 安卓直读权限预检：进程读不到源目录 → wine 同样打不开游戏
            //    文件（media/资源错误的第一根源）。大声指引，不阻断启动。
            if (!srcDir.canRead()) {
                log("警告: 无外部存储读权限（$srcDir 不可读）—— 直读模式需要「所有文件访问」权限")
                log("→ Android 设置 → 应用 → LinBox → 权限 → 允许管理所有文件，授权后重试")
                log("→ 无权限时 wine 打不开游戏文件（media/资源错误正由此而来）")
            } else {
                log("外部存储零拷贝直读: $srcDir（exe/dll/数据件全部原地直读，无任何复制；noexec=${isNoexecPath(exePath)}）")
            }

            // ③ 旧两代执行区残留一次性清理（仅清容器/导入区目录本身，
            //    SafeFileOps.deleteTree 不跟随符号链接，绝不波及
            //    /storage/emulated/0 原件；父目录空了顺手回收）。
            val legacyDirs = listOf(
                File(File(File(ContainerManager.ROOTFS_ROOT, "home/.linbox/games"), container.name), srcDir.name),
                File(File(File(ContainerManager.CONTAINERS_ROOT, container.name), "games"), srcDir.name)
            )
            legacyDirs.forEach { legacy ->
                if (legacy.exists()) {
                    val bytes = runCatching {
                        legacy.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    }.getOrDefault(0L)
                    if (com.linbox.util.SafeFileOps.deleteTree(legacy)) {
                        log("已清理旧版执行区残留 ${legacy.path}（释放约 ${bytes / 1024 / 1024}MB）")
                        runCatching {
                            legacy.parentFile
                                ?.takeIf { it.isDirectory && it.list().orEmpty().isEmpty() }
                                ?.delete()
                        }
                    }
                }
            }

            // ④ 原 exe 直接启动；wine 缺 noexec 回退补丁（3.5 步已检出并
            //    给过完整指引）时老包若仍报 noexec/media 错误，指引用新
            //    CI 重装 wine 包（含 LinBox-noexec-fallback 标记的包零拷贝
            //    直读即可用，本提示自动消失）。
            log("→ 以原路径启动: $exeFile")
            log("→ 若游戏报 noexec/media 错误：请用含 0011/0014 系列补丁的新 CI 重装 wine 包（匿名内存回退；arm64ec 原生执行必需）")
        }.onFailure {
            log("警告: 直读预检异常（${it.message ?: it.javaClass.simpleName}）—— 仍按原路径启动")
        }
        return effectiveArgs
    }

    // ------------------------------------------------------------------
    // 启动链
    // ------------------------------------------------------------------

    /**
     * 提交启动链：同步快速预检（立即反馈错误），异步执行完整链路。
     */
    private fun submitLaunch(
        context: Context,
        container: ContainerData,
        wineArgs: List<String>,
        freshSession: Boolean
    ): String? {
        // ---- 同步预检：wine 可解析（文件级检查，毫秒级） ----
        val precheck = try {
            WineRuntime.resolve(container)
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        if (precheck != null) return precheck

        val token = System.nanoTime()
        pendingLaunches[container.name] = token
        LAUNCH_EXECUTOR.execute {
            launch(context.applicationContext, container, wineArgs, token, freshSession)
        }
        return null
    }

    /**
     * 完整启动链（后台线程执行）。任何步骤失败 → 决策行进会话日志，
     * 会话登记撤销；绝不抛出（链路自身兜底）。
     */
    private fun launch(
        context: Context,
        container: ContainerData,
        wineArgs: List<String>,
        token: Long,
        freshSession: Boolean
    ) {
        val key = container.name
        val sink = SessionLogSink(key, newSessionLog(key))

        // v3.3.34（用户："启动参数加 hodll=libwow64fex.dll 好像没有真正
        // 运行"）：启动参数里的 hodll=/hodll64= token（大小写不敏感）不是
        // 游戏 argv —— 旧链路原样追加在 exe 之后传给游戏，wine 根本看不见，
        // 游戏还会收到一个莫名的命令行参数。此处拦截提取为环境变量（规范
        // 大写键，经 applyWineCompatEnv 的 userEnv 路径注入，优先级与 env=
        // 显式写入完全一致），从 argv 移除并写入会话日志 —— 用户在自定义
        // 启动参数 / 运行对话框写 hodll=libwow64fex.dll 即真实生效、且可见。
        val hodllTokenRe = Regex("(?i)^(hodll64|hodll)=\\S+$")
        val argEnvTokens = wineArgs.filter { hodllTokenRe.matchEntire(it) != null }
        val argv = if (argEnvTokens.isEmpty()) {
            wineArgs
        } else {
            argEnvTokens.forEach { sink.stamp("启动参数 $it → 提升为环境变量（不再传给游戏 argv）") }
            wineArgs.filter { hodllTokenRe.matchEntire(it) == null }
        }

        // ---- 会话头（对齐旧版纯日志格式） ----
        sink.log(
            buildString {
                append("===== LinBox wine 会话（原生链路） =====\n")
                append("容器: $key\n")
                append("参数: ${argv.joinToString(" ")}\n")
                if (argEnvTokens.isNotEmpty()) {
                    append("环境注入（启动参数提取）: ${argEnvTokens.joinToString(" ")}\n")
                }
                append("App: ${appVersionLine(context)}\n")
                append("设备: Android ${Build.VERSION.RELEASE} " +
                    "(SDK ${Build.VERSION.SDK_INT}, ${Build.MODEL})\n")
            }
        )
        lastLogs[key] = sink.text

        try {
            // 0) v3.3.39：conf 统一源就位（assets 种子 + 生态一次性导入）——
            //    App 内部 linbox-conf/ 成为权威源，后续 applyOptConfEnv
            //    合并读（App 内部 + /opt 生态，mtime 新者胜）。
            ApkConfStore.ensure(context)

            // 0) v3.3.10：运行会话先解压选中的 /opt 驱动包（tar -xf →
            //    各自正确的目标））：
            //      - turnip(/opt/tz)、zink(/opt/zink) → rootfs/usr/lib
            //        （path.conf 驱动位置：data/data/com.linbox/files/rootfs/usr/lib）；
            //      - dxvk(/opt/dxvk1)、wined3d(/opt/vkord3d)、fexcore(/opt/fexcore
            //        独立目录，逻辑同 dxvk) → 对应容器 C 盘 windows
            //        （prefix/drive_c/windows）；
            //      - wrapper(/opt/wrapper，用户驱动包为 rootfs 布局
            //        usr/lib+usr/share) → 按包结构直接落位 rootfs/usr/
            //        （同名 Mesa so 备份、卸载可恢复，自生成 Vulkan
            //        ICD 清单 + zink 联动，WrapperDriverSupport.install）；
            //      - box64/box86(/opt/box) → 高级设置 Box 配置页手动应用
            //        （rootfs/usr/local/bin），不在此自动解压。
            //    旧版全部解到 rootfs/usr/local 是错的。解压失败不阻断启动。
            if (freshSession) {
                val prefixWin = File(container.prefixPath, "drive_c/windows")
                // v3.3.16：turnip 从“直接解压覆盖 rootfs/usr/lib”改为走
                // 新驱动模型（独立目录 + ICD，与 wrapper 并列共存）——
                // 仅保留 zink/dxvk/wined3d/fexcore 的直接解压（非 Vulkan
                // ICD 体系，保持 v3.3.9 行为）
                listOf(
                    Triple("Zink", container.zink, File(ContainerManager.ROOTFS_ROOT, "usr/lib")),
                    Triple("WineD3D", container.wined3d, prefixWin),
                    Triple("DXVK", container.dxvk, prefixWin),
                    Triple("FEXCore", container.fexcore, prefixWin)
                ).forEach { (label, slot, dest) ->
                    if (slot.isNotBlank() && slot != "off" && slot != ContainerData.DXVK_AUTO) {
                        val dir = when (label) {
                            "Zink" -> "zink"
                            "WineD3D" -> "vkord3d"
                            "FEXCore" -> "fexcore"   // v3.3.10：独立目录（非 dxvk1）
                            else -> "dxvk1"            // DXVK
                        }
                        val tar = GuanliSupport.scanOpt(dir).firstOrNull { it.display == slot }
                        if (tar == null) {
                            sink.stamp("⚠ 驱动槽位 $dir/$slot 未找到对应 tar 包（继续启动）")
                        } else {
                            runCatching { dest.mkdirs() }
                            val err = GuanliSupport.extractTarTo(tar.path, dest) { sink.stamp(it) }
                            if (err != null) {
                                sink.stamp("⚠ 驱动 ${tar.display} 解压失败：$err（继续启动）")
                            } else {
                                sink.stamp("驱动包 ${tar.display}（$label）已解压 → ${dest.absolutePath}")
                                // dxvk tar 槽位接管时作废 APK 内置注入指纹 ——
                                // 切回 auto 档时强制重铺，防 stale DLL 残留
                                if (label == "DXVK") {
                                    runCatching {
                                        File(File(container.prefixPath), "linboxmeta/.linbox-dxvk-applied").delete()
                                    }
                                }
                            }
                        }
                    }
                }

                // 0.5) v3.3.16（上游实现 DriverPackages 模型）：Vulkan 驱动
                //      （wrapper / 用户 turnip tar）装到【独立目录】
                //      usr/share/linbox-wrapper/drivers/<slot>/（自带 ICD，
                //      rootfs 任何文件不被覆盖，多版本共存）。
                //      槽位未安装而 /opt 内有对应 tar 时自动安装（升级免
                //      重选的兼容入口；UI ➕ 安装是主入口）。
                //      环境变量注入（VK_ICD_FILENAMES + zink 联动）在 6a。
                listOf(
                    "wrapper" to container.wrapper,
                    "tz" to container.turnip,
                    // v3.3.37：专有驱动包（yuzu/高通）独立目录 /opt/yuzu ——
                    // 与 /opt/tz 分列（用户："安装应该放在对应的目录进行选择"）；
                    // 槽位字段同为 turnip（激活链 6a / apply_vk_driver 零改动）。
                    "yuzu" to container.turnip
                ).forEach { (optDir, slot) ->
                    if (slot.isNotBlank() && slot != "off" && WrapperDriverSupport.icdFor(slot) == null) {
                        val tar = GuanliSupport.scanOpt(optDir).firstOrNull { it.display == slot }
                        if (tar == null) {
                            sink.stamp("⚠ Vulkan 驱动槽位 /opt/$optDir/$slot 未找到对应包且未安装（继续启动，走 Mesa 内置）")
                        } else {
                            val err = runCatching { WrapperDriverSupport.install(tar.path) { sink.stamp(it) } }
                                .getOrElse { "安装异常：${it.message}" }
                            if (err != null) {
                                sink.stamp("⚠ Vulkan 驱动 ${tar.display} 安装失败：$err（继续启动，走 Mesa 内置）")
                            }
                        }
                    }
                }
            }

            // 1) 解析 wine 运行时（目录 / ELF 形态 / 后端）
            val resolved = WineRuntime.resolve(container)
            sink.stamp(
                "wine=${resolved.wineDir.name} 形态=${resolved.arch.id} " +
                    "后端=${resolved.backend.id}"
            )

            // 1.5) v3.2.0 wine 10.17+ loader 布局自愈：hangover/official 10.17+
            //      上游把 loader 装进 lib/wine/<arch>-unix/，v3.1.6 曾物化成
            //      bin/wine 副本 —— 副本按自身位置找 bin/ntdll.so 必失败
            //      （设备实测 wine-10-18 两形态 "could not load ntdll.so"）。
            //      此处就地换回相对符号链接，旧包免重装即修复。
            //      v3.2.3 起同时停用 bin/*-preloader（直连 loader 官方兕底）
            //      并改写 futex_waitv 探测（proton 旧包 159 静默阵亡根治）。
            WineLayoutRepair.ensure(resolved.wineDir) { sink.stamp(it) }
            WineProbePatch.apply(resolved.wineDir) { sink.stamp(it) }

            // 2) 运行时目录兜底（rootfs tmp / X11 socket / XDG / 前缀 / logs）
            WineEnvironment.ensureRuntimeDirs(container)

            // 2.5) v3.4.1【注册表大修】：本位置（env 未建）不再做注册表卫生
            //      —— 统一移到 3 步 env 就绪后执行
            //      （WineRegistry.sanitizeRegistryHygiene 需要 env 收尾
            //      wineserver）。旧 v3.3.20 “标记文件 + 换 wine 才清一次”
            //      与 v3.3.5 一次性 purge 标记挡不住 wineboot -u 重导入的
            //      缺陷由此根治（用户实测：v3.4.0 前缀里照样躺着
            //      Pentiment/rayne1/RDR2/RiftApart 等 31 个 per-exe 块）。

            // 3) 基础环境（隔离白名单）+ WINEPREFIX
            val env = LinkedHashMap(WineEnvironment.buildBaseEnv(container, resolved.wineDir))
            env["WINEPREFIX"] = container.prefixPath

            // 3.6) v3.4.1【注册表卫生·每次启动幂等重跑】
            //      ① wine.inf 源头剥除（proton 树 31 条游戏 hack 就地根治，
            //        wineboot -u 不再重导入）；
            //      ② user.reg + system.reg 存量 AppDefaults 段剥除（白名单 =
            //        管理器 per-exe 账本，用户配置不误删）；
            //      ③ 全局 [Software\\Wine\\DllOverrides] 注入（上游同款：
            //        dinput/xinput=builtin,native，direct3d + 全 wincomponents
            //        =native,builtin）。
            //      详见 [WineRegistry.sanitizeRegistryHygiene]。
            runCatching {
                WineRegistry.sanitizeRegistryHygiene(context, resolved, container, env) { sink.stamp(it) }
            }.onFailure {
                sink.stamp("⚠ 注册表卫生跳过：${it.message}（不影响启动；postPrefixBootChain 会再试）")
            }

            // 3.5) v3.3.7 wine 补丁状态检测（noexec 回退 / locale）—— 存量
            //      wine 包缺配方补丁时给出【可照做】的重装指引，不再让用户
            //      对着 "Could not load required media" / 英文界面猜原因：
            //       - arm64ec/hangover 原生执行 + 缺 noexec 回退 → media 报错
            //         （x86_64+box64 翻译执行不依赖执行位，静默跳过不打扰）；
            //       - 缺 locale 补丁 + 容器语言为中日韩 → 界面恒英文、ACP=1252。
            //      检测机制：grep wine 包 ntdll unix .so 里的补丁标记串
            //      （LinBox-noexec-fallback / LinBox-locale-lc-all，v3.3.7 起
            //      全部相关补丁 WARN 格式串内嵌），结果按槽位 mtime 缓存。
            val patchState = WinePatchDetector.stateOf(resolved.wineDir)
            val nativeExecBackend = resolved.backend == ContainerBackend.ARM64EC ||
                resolved.backend == ContainerBackend.HANGOVER
            if (nativeExecBackend && !patchState.noexecFallback) {
                sink.stamp("⚠ wine 包缺 noexec 回退补丁（对 arm64ec 原生执行是硬伤）")
                WinePatchDetector.noexecGuidanceLines(resolved.wineDir).forEach { sink.stamp(it) }
            } else if (patchState.noexecFallback) {
                sink.stamp("wine 包含 noexec 回退补丁（LinBox-noexec-fallback ✓，外部存储零拷贝直读可用）")
            }
            val effectiveLang = (env["LANG"] ?: "").trim().lowercase()
            if (!patchState.localeLcAll &&
                (effectiveLang.startsWith("zh") || effectiveLang.startsWith("ja") || effectiveLang.startsWith("ko"))
            ) {
                sink.stamp("⚠ wine 包缺中文 locale 补丁（LC_ALL 直读未编入）")
                WinePatchDetector.localeGuidanceLines(resolved.wineDir).forEach { sink.stamp(it) }
            } else if (patchState.localeLcAll) {
                sink.stamp("wine 包含 locale 补丁（LinBox-locale-lc-all ✓，LC_ALL 直接决定 wine 语言/代码页）")
            }

            // 4) X11 就绪：打开桌面窗口 + 原生拉起 X 服务 + 显式等 socket
            //    （v3.1：X 服务由 app_process 直启，不经 sh/脚本；决策行
            //    进会话日志，失败不再静默）
            X11Support.ensureX11Window(context) { sink.stamp(it) }
            X11Support.awaitSocketReady(env["DISPLAY"] ?: ":1") { sink.stamp(it) }

            // 5) 清残留 wineserver（v3.3.1 语义升级）：
            //    - freshSession（runExe）：照旧必清（替换旧会话语义不变）；
            //    - 前缀构建会话（wineboot）：**无条件硬清** —— 挂死/冻结的
            //      旧 wineserver 守护（setsid 脱离会话树 + Android cgroup
            //      freezer 冻结）会让新客户端连上旧 socket 后永久阻塞在
            //      握手 receive_fd 上：无输出、无退出码、system.reg 永不
            //      落盘 —— proton arm64ec 前缀构建"无任何输出挂死"的
            //      直接载体。此前 freshSession=false 的工具会话完全不清理
            //      = 挂死一次后每次重试都挂在同一处；
            //    - 其余工具命令：容器无运行中会话时才清理（保持"不打断
            //      运行中会话"的附加语义）。
            //    清理由 WineServerUtils 三段式完成（-k/-w 优雅 + /proc 扫描
            //    SIGKILL 冻结守护 + 残留 socket/lock 清除），决策行进会话日志。
            val isPrefixCmd = wineArgs.firstOrNull() == "wineboot"
            val hasRunningSession = synchronized(sessions) { sessions.containsKey(key) }
            if (freshSession || isPrefixCmd || !hasRunningSession) {
                // v3.2.23（用户问题 6）：无运行中会话且非前缀构建时开
                // 暖启动 —— 存活且健康的 wineserver（真实客户端 cmd /c
                // exit 探测通过）直接复用：arm64ec 冷启动 server + 重读
                // 注册表是二次启动明显偏慢的主因；同时避免把同容器仍在
                // 跑的 X11 桌面/后台进程连根杀掉。探测超时（冻结/挂死）
                // 自动退回三段式全量清理，v3.3.1 挂死根治语义不变。
                WineServerUtils.ensureCleanBeforeStart(
                    resolved, container, env, { sink.stamp(it) },
                    warmStart = !isPrefixCmd && !hasRunningSession
                )
            }

            // 6) GPU / Box64 环境叠加
            WineEnvironment.applyGpuEnv(env, container)

            // 6a) v3.3.16（上游实现 模型）：Vulkan 驱动激活 —— 容器 wrapper /
            //     turnip（用户 tar）槽位中任一选中时，VK_ICD_FILENAMES 指向
            //     其独立目录的自生成 ICD 清单，并把驱动目录前插
            //     LD_LIBRARY_PATH（附属 .so 如 libc++_shared.so 的解析位）；
            //     GL 侧：容器 gallium 为 auto/空/zink 时联动 zink（GL over
            //     Vulkan 吃驱动加速，上游实现 XServerDisplayActivity 同款
            //     MESA_LOADER_DRIVER_OVERRIDE=zink + GALLIUM_DRIVER=zink），
            //     用户显式选了 freedreno/softpipe 等则尊重选择。
            //     都未选 = Mesa 内置 Turnip（BUNDLED，loader 默认搜索，不注入）。
            val activeVkSlot = when {
                container.wrapper.isNotBlank() && container.wrapper != "off" ->
                    "wrapper:${container.wrapper}"
                container.turnip.isNotBlank() && container.turnip != "off" ->
                    "turnip:${container.turnip}"
                else -> null
            }
            if (activeVkSlot != null) {
                val slotName = activeVkSlot.substringAfter(':')
                // v3.3.32：系统专有驱动档（wrapper="system"）—— 先确保 wrapper 栈就位
                //（内置 wrapper-stack.tzst 自动安装，5.5MB 一次性）；栈不可用时回退 Mesa。
                if (slotName == WrapperDriverSupport.SYSTEM_SLOT) {
                    val stackOk = runCatching {
                        WrapperDriverSupport.ensureWrapperStackAvailable(context) { sink.stamp(it) }
                    }.getOrDefault(false)
                    if (!stackOk) {
                        sink.stamp("⚠ 系统驱动：wrapper 栈不可用（资产缺失/安装失败），回退 Mesa 内置 Turnip")
                    }
                }
                // v3.3.19：激活计划（上游实现 语义：ELF 平台识别 + wrapper 栈
                // 路由 + 依赖闭环预检）。用户实测（2026-11-08 会话日志）：选了
                // wrapper-22 后游戏报 "Required Vulkan extension VK_KHR_surface
                // not supported" —— ICD 的 .so 在 bionic 原生 wine 进程里
                // dlopen 失败（glibc 构建 / 缺依赖），loader 静默跳过 → 零 ICD
                // → DXVK 空手而归。旧逻辑照注入不误 → 游戏必黑屏且无任何
                // 线索；新逻辑：不可用的槽位显式回退 Mesa 内置 Turnip，原因
                // 逐条写进会话日志（glibc / 非 ELF / 缺哪个依赖，全部点名）。
                val act0 = WrapperDriverSupport.activationFor(slotName)
                // v3.3.32：android 平台驱动（高通/联发科专有 yuzu 包，无 X11 WSI）
                // 若无 wrapper 栈可路由 → 自动安装内置 wrapper 栈后重算一次
                //（用户此前“装了 v849/q615.50 类包选了必黑屏”的闭环链路）。
                var act = act0
                if (act != null && !act.wrapperStack && !act.x11wsi) {
                    val stackOk = runCatching {
                        WrapperDriverSupport.ensureWrapperStackAvailable(context) { sink.stamp(it) }
                    }.getOrDefault(false)
                    if (stackOk) {
                        act = WrapperDriverSupport.activationFor(slotName)
                        if (act?.wrapperStack == true) {
                            sink.stamp("已自动安装内置 wrapper 栈：专有驱动 ${slotName} 经 adrenotools 路由（X11 WSI 由 wrapper 提供，v3.3.32）")
                        }
                    }
                }
                // 回退时 path.conf 一并回写 Mesa 内置（防 conf 里残留旧 ICD
                // 误导 glibc/startwine 链路）
                val fallbackStockConf = {
                    val stockIcd = WrapperDriverSupport.bundledTurnipIcd()
                    if (stockIcd != null) {
                        runCatching {
                            GuanliSupport.syncPathConfVkIcd(stockIcd.absolutePath, resolved.wineDir) { sink.stamp(it) }
                        }.onFailure { sink.stamp("⚠ path.conf 回写异常：${it.message}（不影响本次会话）") }
                    }
                }
                when {
                    act == null -> sink.stamp(
                        if (slotName == WrapperDriverSupport.SYSTEM_SLOT)
                            "⚠ 系统驱动档不可用：wrapper 栈未就位（内置资产安装失败？），回退 Mesa 内置 Turnip"
                        else
                            "⚠ Vulkan 驱动 $slotName 的 ICD 清单未就位（安装失败或包内无 libvulkan*.so），回退 Mesa 内置 Turnip"
                    )
                    act.platform == WrapperDriverSupport.DriverPlatform.GLIBC -> {
                        sink.stamp("⚠ Vulkan 驱动 $slotName 是 glibc 构建（DT_NEEDED 含 libc.so.6），bionic 原生 wine 无法 dlopen —— 回退 Mesa 内置 Turnip")
                        sink.stamp("   出路：① 改装 bionic 构建的 turnip 驱动包（LinBox CI 产物 linbox-turnip-driver-*-bionic.tar.gz）② 或装含 libvulkan_wrapper.so 的 wrapper 栈包经 adrenotools 路由")
                        fallbackStockConf()
                    }
                    act.platform == WrapperDriverSupport.DriverPlatform.UNKNOWN -> {
                        sink.stamp("⚠ Vulkan 驱动 $slotName 无法识别 ABI（ICD 目标非有效 ARM64 ELF，文件损坏？）—— 回退 Mesa 内置 Turnip")
                        fallbackStockConf()
                    }
                    act.missingDeps.isNotEmpty() -> {
                        sink.stamp("⚠ Vulkan 驱动 $slotName 依赖未闭环：${act.missingDeps.joinToString("、")}（驱动目录/rootfs/usr/lib/系统库均未找到）—— 回退 Mesa 内置 Turnip")
                        sink.stamp("   出路：换完整驱动包，或把缺的 .so 一并放进驱动包重新安装（wrapper 栈常见缺 libandroid-sysvshm.so）")
                        fallbackStockConf()
                    }
                    else -> {
                        env["VK_ICD_FILENAMES"] = act.icd.absolutePath
                        // v3.3.19：loader 侧错误级诊断 —— ICD dlopen 失败等原因
                        // 直接进会话日志（驱动类问题不再"无声"）
                        env["VK_LOADER_DEBUG"] = "error"
                        // 驱动目录前插 LD_LIBRARY_PATH（附属 .so 解析；wine 的
                        // LD_LIBRARY_PATH 首段是 rootfs/usr/lib，驱动目录更优先）
                        val driverDir = act.driverDir
                        // v3.3.35：act 是 var（上方 wrapper 重路由可能重赋值），
                        // act.adrenotoolsDriverDir 为不稳定表达式、无法 smart cast
                        //（CI compileReleaseKotlin 实报）—— 先落局部 val 再判空。
                        val adrenoDriverDir = act.adrenotoolsDriverDir
                        val ldParts = buildList {
                            add(driverDir.absolutePath)
                            // v3.3.20：路由模式下 turnip 本体目录也前插 —— 其
                            // 附属 .so（如有）按目录优先级解析
                            if (adrenoDriverDir != null && adrenoDriverDir != driverDir)
                                add(adrenoDriverDir.absolutePath)
                            env["LD_LIBRARY_PATH"]?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                        env["LD_LIBRARY_PATH"] = ldParts.joinToString(":")
                        if (act.wrapperStack) {
                            // wrapper 栈模式（上游实现 setDriverById + wrapper_icd 同款）：
                            // ICD→libvulkan_wrapper.so，turnip 经 ADRENOTOOLS_*
                            // 注入，X11 WSI 由 wrapper 的 xcb 侧提供
                            //
                            // v3.3.29：NDK 依赖前置解析 —— 依赖链
                            // libvulkan_wrapper.so → libnativewindow.so →
                            // libbinder_ndk.so 的 verneed 校验兜底（设备日志
                            // wine-10arm-20261109-121003 实证：缺它 ICD 整体
                            // 被忽略 → Found no drivers）。详见
                            // WrapperDriverSupport.ensureNdkDeps 注释。
                            val ndkLinked = WrapperDriverSupport.ensureNdkDeps()
                            if (ndkLinked > 0) {
                                sink.stamp("NDK 依赖前置解析：rootfs/usr/lib 新建 $ndkLinked 个系统库软链（libbinder_ndk 链 verneed 校验兜底）")
                            }
                            // v3.3.20（用户实测反馈修复）：ADRENOTOOLS_HOOKS_PATH
                            // 此前从未注入 —— wrapper 生态的 libvulkan_wrapper.so
                            // （用户提供的 wrapper-22 等）从 env 读四件套，没有
                            // HOOKS_PATH 时 adrenotools 初始化报
                            // "Couldn't preload the hook implementation"、
                            // hook 链（libmain_hook.so → libhook_impl.so）无法
                            // 建立 → wrapper dlopen 后零 ICD 生效（DXVK 报
                            // VK_KHR_surface not supported 黑屏，即用户
                            // "切换 wrapper 依旧无法使用"的运行期根因）。
                            // hooks 目录 = wrapper 栈 slot 目录（libmain_hook.so
                            // 等随包装在同一目录）。
                            act.adrenotoolsHooksDir?.let { hooks ->
                                env["ADRENOTOOLS_HOOKS_PATH"] = hooks.absolutePath
                            }
                            // adrenotools 临时目录（patched .so 落盘位）：wrapper
                            // 内部拼 <driverPath>/temp，必须预创建（上游实现
                            // vulkan.c 的 mkdir(tmpdir) 同款语义）
                            act.adrenotoolsTempDir?.let { tmp ->
                                runCatching { tmp.mkdirs() }
                                    .onFailure {
                                        sink.stamp("⚠ adrenotools 临时目录创建失败：${it.message}")
                                    }
                            }
                            // v3.3.35：同上 —— var 接收者的属性判空后直接使用
                            // 无法 smart cast，先落局部 val。
                            val adrenoDriverName = act.adrenotoolsDriverName
                            val adrenoDriverDir = act.adrenotoolsDriverDir
                            if (adrenoDriverName != null && adrenoDriverDir != null) {
                                // v3.3.28：尾斜杠对齐上游参考（AdrenotoolsManager
                                // setDriverById 的 driverPath 拼法 `dir + "/"`）——
                                // 部分 wrapper 栈按 driverPath 直接拼接子路径
                                // （temp/、driver 库名），无尾斜杠时拼出
                                // "dirtemp" 类错误路径。
                                env["ADRENOTOOLS_DRIVER_PATH"] = adrenoDriverDir.absolutePath.let {
                                    if (it.endsWith("/")) it else "$it/"
                                }
                                env["ADRENOTOOLS_DRIVER_NAME"] = adrenoDriverName
                                sink.stamp(
                                    "wrapper 栈激活（${
                                        if (act.routedFromWrapperSlot != null) "android 平台驱动，经 ${act.routedFromWrapperSlot} 的 wrapper 栈路由"
                                        else "本槽位 wrapper 栈"
                                    }）：adrenotools 加载 $adrenoDriverName，X11 WSI 由 wrapper 提供" +
                                        (act.adrenotoolsHooksDir?.let { "（hooks=${it.name}/）" } ?: "")
                                )
                            } else {
                                // v3.3.32：系统专有驱动档（wrapper="system"）——
                                // 不注入 ADRENOTOOLS_DRIVER_*，libvulkan_wrapper.so
                                // 自身回退加载 /system/lib64/libvulkan.so（设备厂商
                                // 专有驱动：高通 Adreno 系统驱动 / 联发科 Mali 同通路）
                                val isSystem = slotName == WrapperDriverSupport.SYSTEM_SLOT
                                sink.stamp(
                                    (if (isSystem) "系统专有驱动激活（高通/联发科）：" else "wrapper 栈激活：") +
                                        "ICD→libvulkan_wrapper.so（${
                                            if (isSystem) "wrapper 回退系统 /system/lib64/libvulkan.so —— 设备厂商专有驱动"
                                            else "未配对 turnip 驱动本体，wrapper 回退系统 GPU 驱动"
                                        }）" +
                                        (act.adrenotoolsHooksDir?.let { "（hooks=${it.name}/）" } ?: "")
                                )
                            }
                            sink.stamp("Vulkan 驱动激活（$activeVkSlot）：VK_ICD_FILENAMES → ${act.icd.absolutePath}")
                        } else {
                            sink.stamp(
                                "Vulkan 驱动激活（$activeVkSlot）：VK_ICD_FILENAMES → ${act.icd.absolutePath}" +
                                    "（bionic ✓，X11 WSI ${if (act.x11wsi) "✓" else "✗ android 平台构建"}）"
                            )
                            if (!act.x11wsi) {
                                sink.stamp("⚠ 该驱动为 android 平台构建（无 X11 WSI）且无 wrapper 栈可路由：DXVK 交换链可能创建失败")
                                sink.stamp("   出路：装一个含 libvulkan_wrapper.so 的 wrapper 栈包（自动路由）或换 X11 平台构建包")
                            }
                        }
                        // v3.3.18（用户需求："选择 wrapper 时同时更改 conf 里的
                        // path.conf，选择 turnip 时也要改，三个选项都要改路径"）：
                        // 激活成功即落 conf —— path.conf 的 VK_ICD_FILENAMES 行
                        // 原位改写为本驱动 ICD（wineguanli.py 参考行为；UI 选择时
                        // GraphicsDriverTab 已写过，此处兜底 /opt tar 运行时自动
                        // 安装、conf 被手工改回等路径），conf 与容器选择永远一致。
                        runCatching {
                            GuanliSupport.syncPathConfVkIcd(act.icd.absolutePath, resolved.wineDir) { sink.stamp(it) }
                        }.getOrElse { sink.stamp("⚠ path.conf 同步异常：${it.message}（不影响本次会话）") }
                        if (WrapperDriverSupport.shouldForceZink(container.gallium)) {
                            env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
                            env["GALLIUM_DRIVER"] = "zink"
                            env.putIfAbsent("ZINK_DESCRIPTORS", "lazy")
                            env.putIfAbsent("ZINK_DEBUG", "compact")
                            env.putIfAbsent("TU_DEBUG", "noconform")
                            sink.stamp("GL 联动 zink：GALLIUM_DRIVER=zink（GL over Vulkan → 用户驱动）")
                        } else {
                            sink.stamp("GL 保持 ${container.gallium}（用户显式选择，仅 Vulkan 侧走用户驱动）")
                        }
                        sink.stamp("（切回 Mesa 内置 Turnip：图形驱动页选回默认即可，零文件读写）")
                    }
                }
            }

            if (resolved.backend == ContainerBackend.BOX64 ||
                resolved.backend == ContainerBackend.FEXCORE
            ) {
                // v3.1.4：wineDir 参数 —— wine 包自带的 x86_64-bionic 依赖闭包
                // 目录进 BOX64_LD_LIBRARY_PATH（缺它 box64 加载 wine unix .so
                // 直接 "Symbol ... not found"）；v3.4.3：+context/log ——
                // 四档 BOX64_* 改由 assets builtin/box64_presets.json 派生
                //（assets-ck 资产，上游同款表，含 AVX/UNITYPLAYER）。
                WineEnvironment.applyBox64Env(
                    env, container, resolved.wineDir,
                    context = context, log = { sink.stamp(it) }
                )
                // v3.3.21（用户定稿）：前缀构建会话不再强制 SAFEFLAGS=2 ——
                //   用户实测（Adreno 740 / Android 15，box64 0.4.2-0.4.4）：
                //   SAFEFLAGS=1 / 2 反而触发 ntdll 早期初始化 SIGSEGV，
                //   仅 0 稳定（v3.2.17 的反向归因已证伪）。wineboot 是容器
                //   可用性的关键一次性路径，取实测稳定值 0；用户容器 env=
                //   的 applyExtraEnv 仍在链路最后，可覆盖本值。
                if (isPrefixCmd) {
                    env["BOX64_DYNAREC_SAFEFLAGS"] = "0"
                }
                // v3.2.0：闭包缺失诊断（设备实测 proton10-x86_64 旧包仍报
                // "Symbol shm_open not found"）—— 旧包没有 x86_64-bionic 目录，
                // 环境变量救不了缺文件，把“请重装 v3.1.4+ 构建的 wine 包”
                // 直接写进会话日志，不再让用户对着 box64 报错猜原因。
                if (!File(resolved.wineDir, "lib/x86_64-bionic").isDirectory) {
                    sink.stamp(
                        "警告: 该 wine 包缺少 lib/x86_64-bionic 依赖闭包（v3.1.4 之前构建的旧包）——" +
                            "box64 加载 unix .so 时将报 Symbol shm_open not found；" +
                            "请用新 CI 重跑 build-wine-bionic（x86_64 形态）并重装该包"
                    )
                }
            }

            // 6b) Android 安全默认（v3.1.4）：WINEESYNC/WINEFSYNC=0 等
            //     沙箱守卫 + HODLL 显式注入（v3.4.2 对齐上游发行包
            //     LaunchPlan）：默认【不】注入 HODLL/HODLL64 —— wine 内部
            //     缺省（0010/0011 补丁：libwow64fex/libarm64ecfex）接管
            //     默认路径；仅用户 env=/启动参数显式值，或勾选 FEX模式 →
            //     HODLL=libwow64fex.dll / wowbox64模式 → HODLL=wowbox64.dll
            //    （启动参数提取的 token 追加在 userEnv 尾部参与同一套解析）
            WineEnvironment.applyWineCompatEnv(
                env, resolved.arch, resolved.wineDir,
                container.env + if (argEnvTokens.isEmpty()) "" else " " + argEnvTokens.joinToString(" "),
                fexMode = container.hasFlag(com.linbox.apps.containers.ContainerFlags.FEXMODE),
                wowboxMode = container.hasFlag(com.linbox.apps.containers.ContainerFlags.WOWBOXMODE)
            )

            // v3.3.35：arm64ec 线性能档位 —— BOX64_DYNAREC_* 在此线全空转
            //（aarch64 wine 原生 exec 不经 box64），预设改映射 FEX_ 前缀
            // 环境变量（libwow64fex/libarm64ecfex 配置层最高优先级）。
            // opt conf 与容器 env= 在其后执行，仍可覆盖。
            // v3.3.37：+ ensureFexConfigFile —— 参考 上游参考 FEXCoreManager
            // 的实证通道：libwow64fex/libarm64ecfex 读 $HOME/.fex-emu/
            // Config.json（FEX_* env 在部分构建上不生效，双通道保险）。
            if (resolved.backend == ContainerBackend.ARM64EC) {
                // v3.4.3：预设 JSON 注入（assets builtin/fex_presets.json ——
                // 用户提供的 assets-ck 资产，上游发行包 FEXPresetManager
                // 同一张表；旧表每档仅 2-3 项，performance 档的 TSOENABLED=0/
                // SMC_CHECKS=none/VOLATILEMETADATA=0 主旋钮全缺）。
                WineEnvironment.applyFexPresetEnv(env, container, { sink.stamp(it) }, context)
                WineEnvironment.ensureFexConfigFile(env, container, { sink.stamp(it) }, context)
            }

            // v3.3.37：全局性能基线（上游参考 容器出厂调优串移植，两线
            // 共用：mailbox 呈现 / GL NO_ERROR / DXVK 异步编译与状态缓存 /
            // WINEDEBUG=-all / esync 等）—— 全部 putIfAbsent，opt conf 与
            // 容器 env= 在其后仍可逐项覆盖。
            WineEnvironment.applyPerfBaselineEnv(env) { sink.stamp(it) }

            // v3.3.37：DXVK 真激活 —— v3.2.27 撤销全部 DllOverrides 后，
            // wine 默认加载序（builtin 优先）从未真正加载过解压进
            // system32/syswow64 的 DXVK DLL；dxvk 槽位激活时注入
            // D3D/DXGI 全家 native,builtin（上游参考 WineUtils 同款），
            // 与 winedbg=d 合并，容器 dxoverrides/env= 仍最高优。
            WineEnvironment.applyDxvkEnv(env, container) { sink.stamp(it) }

            // 6c) v3.2.25（用户问题：wine 启动没读 rootfs/opt/conf 与
            //     locale.conf）：/opt 生态环境（startwine.sh/startfex.sh
            //     参考链路）—— v3.2.27 升级为 **source 语义**：
            //     opt/conf/*.conf 的全部 KEY=VALUE 都 export 给 wine
            //     进程（仅 WINEPREFIX/加载器/X11/LD_*/BOX64_*_PATH 等
            //     App 生存关键变量不透传；PATH 合并追加），+ opt/locale.conf
            //     （LC_ALL/LANG，wine 按 LC_ALL 决定 ANSI 代码页）+
            //     GLIBC_BIN 兜底导出。容器 env=（applyExtraEnv，链路最后）
            //     仍可覆盖一切。
            //     v3.2.28：探测前先跑 /opt 生态自愈（rootfs/opt 顶层生态
            //     tar 包自动解包 + 套层/槽位 opt/ 候选）—— 修复"生态包放了
            //     但 conf/locale.conf/前缀解包注册全旁路"的设备侧根因。
            OptEcosystemSupport.ensure(resolved.wineDir) { sink.stamp(it) }
            // v3.3.40【性能】：conf 只读一遍 —— probeLine 诊断 + source
            // 共用同一份快照（旧版 applyOptConfEnv 内部把全部 conf 读两遍：
            // probeLine 一次 + readOptConfs 一次）。
            val confSnapshot = ApkConfStore.mergedConfMap(resolved.wineDir)
            WineEnvironment.applyOptConfEnv(
                env, container, resolved.wineDir,
                { sink.stamp(it) }, confSnapshot
            )
            // v3.3.2：MIDI 音色库探测（配合 wine 0010-midimap-fluidsynth
            // 补丁；rootfs 有 .sf2 时设 WINE_MIDI_SOUNDFONT，无则零动作）
            WineEnvironment.applyMidiSoundfontEnv(env) { sink.stamp(it) }
            // v3.3.2：home 顶层乱码文件隔离（用户反馈 home 出现
            // "pE@ঈ@8@@@hh…" 类内存转储文件 —— 历史会话 CWD=home 时
            // wine/box64 崩溃转储落盘；会话 CWD 已全部改到 rootfs/usr/tmp，
            // 存量乱码文件移入 .linbox/quarantine 供人工甄别，不自动删除）
            quarantineHomeGarbageFiles { sink.stamp(it) }

            // v3.2.10（用户问题：syswow64 无法生成）→ v3.4.2 改写：
            // 【v3.1.6 误判复盘】当时以为"前缀创建只执行 aarch64 PE"，把
            // proton10 arm64ec 前缀构建挂死归嫌疑于仿真器早期加载而剥离了
            // HODLL64/HODLL。实际两条都不成立：
            //   1. wineboot -u 会为所有受支持架构派生 rundll32 执行 wine.inf
            //      的 PreInstall/DefaultInstall/【Wow64Install】段 —— i386 段
            //      由 32 位 syswow64\rundll32.exe 运行（设备日志实证：
            //      "00dc:err:environ:init_peb starting ...syswow64\rundll32.exe
            //       in experimental wow64 mode"后紧跟
            //      "err:wow:load_64bit_module failed to load dll c0000135"）。
            //      32 位进程 wow64 初始化需要 CPU 仿真 dll；vanilla 树找
            //      xtajit.dll → 进程即死 → Wow64Install 段从未执行 →
            //      C:\windows\syswow64 整个目录没有内容。
            //   2. v3.1.6 时代的 proton10（vanilla wine-10）根本没有读取
            //      HODLL64/HODLL 的代码，注入与否对当时的挂死毫无影响（挂死
            //      根因是 bionic 堆标记/预加载器/esync，已由 v3.2.x 修复）。
            // 【现在·v3.4.2】0010/0011 补丁已把 HODLL/HODLL64 机制连同
            // 内部缺省值（HODLL 未设 → libwow64fex；HODLL64 未设 →
            // libarm64ecfex）移植进 official/proton 树（对齐
            // hangover-11.16）—— 前缀构建会话【无需 App 侧注入】，
            // 32 位 rundll32 由 wine 内部缺省仿真器支撑，syswow64 正常
            // 生成；勾选 FEX模式/wowbox64模式或显式 HODLL=/HODLL64=
            // 时以用户值为准（6b 步 applyWineCompatEnv 已注入同一份 env，
            // wineboot/游戏会话共用，无需分支重复）。
            if (wineArgs.firstOrNull() == "wineboot") {
                sink.stamp(
                    "前缀构建会话：32 位 rundll32 由 wine 内部缺省仿真器支撑" +
                        "（0010/0011 补丁缺省 libwow64fex/libarm64ecfex；勾选 FEX模式" +
                        "/wowbox64模式或显式 HODLL=/HODLL64= 时以用户值为准）" +
                        "—— syswow64 正常生成"
                )
                // v3.2.0（用户诉求 4）：前缀构建画面指引 —— 构建已包 explorer
                // 虚拟桌面（见 buildCommand），mono/gecko 等安装提示框从此在
                // 『X11 桌面』窗口内可见可点，不再是无 WM 裸窗口堆在 (0,0) 黑屏角落。
                sink.stamp(
                    "前缀构建画面输出在『X11 桌面』窗口（explorer 虚拟桌面）——" +
                        "mono/gecko 等安装提示框请在该窗口中确认，构建日志同时在本窗口滚动"
                )
            }

            // 7) 音频（audio=off → 无声；pulse → 原生守护管理 + PULSE_SERVER；
            //    alsa → v3.4.7 桥档：守护同源 + 注册表 Audio=alsa 走 winealsa）
            when (container.audio) {
                ContainerAudio.OFF -> {
                    WineEnvironment.applyAudioOff(env)
                    sink.stamp("音频=关闭")
                }
                ContainerAudio.ALSA -> {
                    // v3.4.7（用户："保证正常使用 pulse 或者 nopulse 使用 alsa"）：
                    // winealsa 在 Android 的唯一现实出口 = rootfs alsa-plugins
                    // pulse 桥（/etc/asound.conf 全局默认 PCM）→ 同一守护 →
                    // sles-sink。守护必须同源拉起（alsa 桥依赖它）。
                    val server = PulseAudioManager.ensureServer { sink.stamp(it) }
                    env["PULSE_SERVER"] = server
                    sink.stamp("音频=ALSA→pulse桥($server)（winealsa 经 /etc/asound.conf → alsa-plugins → 守护）")
                }
                else -> {
                    val server = PulseAudioManager.ensureServer { sink.stamp(it) }
                    env["PULSE_SERVER"] = server
                    sink.stamp("音频=PulseAudio($server)")
                }
            }

            // 7.5) v3.2.1（用户问题 3）：显示模式握手提前到前缀初始化之前 ——
            //      握手写 .linbox-x11-res → X11ResolutionLink 即时把 X 屏幕
            //      切到目标分辨率；随后 wineboot / 应用的 explorer 虚拟桌面
            //      以同一分辨率创建。旧顺序（前缀构建后才握手）下 VD 取到的
            //      是握手生效前的旧屏幕尺寸（跟随窗口值），随后 X11FitClient
            //      的“贴合”又把 X 屏缩回 VD 尺寸 —— 配置的分辨率被吞掉，
            //      表现为“分辨率选择无效果、依旧跟随窗口”。
            val (dmode, resolution) = applyDisplayMode(container)

            // 7.6) v3.2.1（用户问题 4）：wine 本地化支持 —— wine.mo 界面翻译
            //      （安装到 wine 目录 share/locale 双路径：槽位 + 编译期默认）+
            //      CJK 字体（Android 系统字体 → 前缀 drive_c/windows/Fonts +
            //      注册表 SimSun/微软雅黑/MS Shell Dlg 替换）。翻译先于前缀构
            //      建安装（wineboot 的 mono/gecko 提示框直接是中文）；字体需
            //      前缀就绪，未就绪时由后续启动自动补装（幂等）。
            WineLocaleSupport.ensure(context, resolved, container, env) { sink.stamp(it) }

            // 8) 首次运行初始化前缀（wineboot -i，宿主二进制直连）
            // 9) EmulateModeset 注册表（无 RandR X 环境的显示模式虚拟化，
            //    游戏显示模式枚举/D3D 设备创建的关键修复；幂等）
            // wineserver / wineboot 直连命令跳过 8/9（对齐脚本 run_wine
            // 特殊分支：宿主工具不经 wine 客户端准备流程）
            // v3.1.6：wineboot -i 会话与首次前缀初始化统一登记前缀构建
            // 进度流 + 专用日志通道（UI 自动弹出「构建 Wine 前缀」窗口，
            // 逐行显示 wineboot 输出 —— proton arm64ec 无输出挂死这类
            // 问题从此有直接证据面）。
            val isHostToolCommand = wineArgs.firstOrNull() == "wineserver" ||
                wineArgs.firstOrNull() == "wineboot"
            val isPrefixSession = wineArgs.firstOrNull() == "wineboot"
            if (isPrefixSession) _prefixBuildFlow.value = key
            if (!isHostToolCommand) {
                try {
                    // v3.2.26（用户实测“可用 0MB”仍继续构建）：前缀构建前
                    // 存储空间预警 —— wineboot 要写入上千个文件，/opt/prefix
                    // 资产解压还要更多；空间不足时 wineboot 静默失败，后续
                    // 解压/注册表步骤全部无声跳过（正是“wineboot 结束后
                    // 什么都不显示”的设备侧根因之一）。提前大声警告。
                    runCatching {
                        val freeMb = File(container.prefixPath).usableSpace / 1024 / 1024
                        if (freeMb < 512) {
                            sink.stamp("警告: 内部存储空间仅剩 ${freeMb}MB —— 前缀构建（wineboot 写入上千文件）与 /opt/prefix 资产解压很可能因磁盘满而失败")
                            sink.stamp("→ 建议：先清理内部存储（如旧容器/不再使用的游戏），空间充足后重建容器")
                        }
                    }
                    // v3.2.1：把配置分辨率传给前缀构建 —— wineboot 的 explorer
                    // 虚拟桌面与握手分辨率一致（无分辨率时维持旧“当前屏幕”语义）
                    WineRegistry.initializePrefixIfNeeded(
                        resolved, container, env,
                        context = context,
                        vdResolution = resolution
                    ) { line ->
                        sink.stamp(line)
                        prefixLogLine(key, line)
                    }
                    // v3.2.27：wineboot 之后的统一前缀构建链（提取为
                    // postPrefixBootChain —— 与 wineboot -i 会话的 onExit
                    // 后续链同源，两条入口不再各写一份）。
                    postPrefixBootChain(context, resolved, container, env, key, sink)
                } finally {
                    _prefixBuildFlow.value = null
                }
            }

            // 8b) v3.2.3：手动 wineboot 会话补齐加载器冒烟测试 —— 此前该
            //     路径（runWineCmd "wineboot -i"）不走 initializePrefixIfNeeded，
            //     无冒烟、无看门狗，proton 两形态 loader 阵亡时会话日志
            //     戛然而止无从排查。结论直接写进会话日志与前缀日志双通道。
            //     同一运行时仅首次实测（WineRegistry 侧按 wine 目录缓存）。
            if (isPrefixSession) {
                if (!WineRegistry.loaderSmokeTest(resolved, container, env) { line ->
                        sink.stamp(line)
                        prefixLogLine(key, line)
                    }) {
                    sink.stamp("→ 加载器冒烟测试未通过，已放弃本次 wineboot（结论见上；请先解决该问题再重试）")
                    prefixLogLine(key, "加载器冒烟测试未通过，wineboot 未执行")
                    _prefixBuildFlow.value = null
                    return
                }
            }

            // 10) v3.3.9（用户问题 3）：独立窗口（-f）虚拟桌面 = **注册表**
            //     控制（startwine.sh apply_virtual_desktop_settings 移植）：
            //     -f + 分辨率 → 写 Explorer Desktops 注册表（wine 桌面常驻，
            //     带wine界面）；其余模式（含全屏）→ 删两键（防旧 -f 残留
            //     双窗口 + 清蓝底）。需前缀就绪；分辨率握手已在 7.5 提前完成。
            if (!isHostToolCommand) {
                val vdOn = dmode == DisplayMode.F.id && resolution.isNotEmpty()
                WineRegistry.setVirtualDesktopRegistry(
                    resolved, container, env,
                    enable = vdOn, resolution = resolution
                ) { sink.stamp(it) }
            }
            val vdNote = when (dmode) {
                DisplayMode.F.id -> "虚拟桌面=注册表(-f)"
                DisplayMode.D.id, DisplayMode.V.id -> "虚拟桌面=explorer 包桌"
                else -> "无 wine 桌面（真全屏）"
            }
            if (resolution.isNotEmpty()) {
                sink.stamp("显示模式 dmode=$dmode 分辨率=$resolution（X11 握手已写入；$vdNote）")
            } else if (dmode != DisplayMode.OFF.id) {
                // v3.2.1（用户问题 3）：选了显示模式但没选分辨率（native）时
                // 握手什么也不改 —— 如实告知，不再让用户对着“无效果”猜。
                sink.stamp("显示模式 dmode=$dmode 但未配置分辨率（跟随窗口）——请在容器设置选择虚拟分辨率")
            }

            // 11) v3.2.15：容器调优环境（帧率/Unity/MangoHud/核心集），
            //     用户附加环境最后叠加 —— 可覆盖一切上游变量
            WineEnvironment.applyTuningEnv(env, container)
            WineEnvironment.applyExtraEnv(env, container)

            // 12) 构建命令并 exec（wine / wineserver / wineboot 特判）
            // 令牌自检：链期间被"停止"或被更新的启动请求取代 → 放弃 exec
            if (pendingLaunches[key] != token) {
                sink.stamp("启动已取消（会话在启动链中被停止或取代），不再拉起 wine")
                return
            }
            // v3.3.6（用户定稿：真·零拷贝）：exe 位于 /storage/emulated 等
            // 外部存储时**按原路径直接启动**，不复制任何文件 —— wine 经
            // d:/e: 盘与 Z: 盘 rootfs 入口（Z:\storage、Z:\sdcard）直读游戏
            // 文件；PE 可执行段由 wine 的匿名内存回退加载（wine 补丁
            // 0011/0014 系列）。旧两代执行区残留在此一次性清理释放。
            val effectiveArgs = prepareNoexecDirectRead(argv, container) { sink.stamp(it) }

            // v3.3.33（"dxvk 运行仙剑四还是不行"修复）：dxvk_appfix 移植到
            // App 原生链 —— v3.3.30 的仙剑四 d3d9.floatEmulation=Strict 补丁
            // 此前只存在于终端脚本链（linbox-wine dxvk_appfix_conf），原生链
            // 从未生效（用户 2026-11-09 21:57 会话日志实证：DXVK 读的是
            // /opt/dxvk.conf 原文件、无"DXVK 兼容补丁"行，游戏代码 +0x2941b5
            // page fault 依旧）。两条链路自此对齐。
            // v3.3.38：+ arm64ec 参数（仙剑四 FEX x87 80 位精度联动 ——
            // 游戏本体 x87 指令由 FEX 解释，PERFORMANCE 档
            // X87ReducedPrecision=1 是野指针崩溃的第二供应商）+ 闪屏
            // presentInterval=1（IMMEDIATE 呈现撕裂根治）。
            val pal4Hit = runCatching {
                WineEnvironment.applyPal4Compat(
                    env, effectiveArgs, container, { sink.stamp(it) },
                    arm64ec = resolved.backend == ContainerBackend.ARM64EC
                )
            }.getOrDefault(false)

            val command0 = buildCommand(resolved, effectiveArgs, dmode, resolution)
            // v3.3.33：CPU 亲和性原生链真正生效（taskset 前缀包装）——
            // 旧行为只导出 PRIMARY_CORES 供 /opt 生态脚本消费，原生链
            // 从不绑核；仙剑四命中且未配置 cores 时自动单核（该游戏
            // 2007 引擎多核竞态崩溃为社区共识问题）。
            val command = applyTasksetAffinity(command0, env, pal4Hit) { sink.stamp(it) }
            // v3.1：工作目录跟随 exe 所在目录（Unix 路径直取父目录 /
            // Windows 盘符路径经 dosdevices 映射）—— DX9 等大量游戏按
            // CWD 相对路径找 media\ 等资源，旧版写死 HOME 导致
            // "找不到 media" 报错（CubeMap.exe 实证）
            val workingDir = resolveExeWorkingDir(effectiveArgs.firstOrNull(), container)
                ?: WineEnvironment.HOME_DIR
            if (workingDir != WineEnvironment.HOME_DIR) {
                sink.stamp("工作目录=exe 所在目录 $workingDir（media 等相对路径可解析）")
            }
            sink.stamp(
                "启动: ${command.joinToString(" ")}\n" +
                    "环境: DISPLAY=${env["DISPLAY"]} WINEPREFIX=${env["WINEPREFIX"]} " +
                    "HOME=${env["HOME"]} LANG=${env["LANG"]}"
            )

            // v3.2.3：前缀构建会话看门狗信号量 —— onStdOut/onStdErr/onExit
            // 回调置位（供下方 4 分钟无输出看门狗判定；普通会话开销为零）。
            val gotSessionOutput = java.util.concurrent.atomic.AtomicBoolean(false)
            val sessionEnded = java.util.concurrent.atomic.AtomicBoolean(false)
            val executor = NativeShellExecutor.create()
                .setCommand(*command.toTypedArray())
                .putEnv(env)
                .setWorkingDir(workingDir)
                .onStdOut {
                    gotSessionOutput.set(true)
                    sink.log(it)
                    if (isPrefixSession) prefixLogLine(key, it)
                }
                .onStdErr {
                    gotSessionOutput.set(true)
                    sink.log(it)
                    if (isPrefixSession) prefixLogLine(key, it)
                }
                .onExit { code ->
                    sessionEnded.set(true)
                    // v3.2.27（用户问题：“wineboot i 结束后依旧是结束构建前缀了，
                    // 啥也没有继续，下次启动软件才进行解压和注册表”）：
                    // wineboot 进程退出后**继续**前缀构建链 —— /opt/prefix
                    // 资产解压 + 安装包注册表导入 + dosdevices + DXVK + 函数库
                    // 设置，全部在本会话（前缀构建窗口）内完成并逐步显示日志，
                    // 对齐 startwine.sh 301-369 行（wineboot -u 之后的 tar 解压 /
                    // regedit 导入 / dosdevices / fix-fonts / dxvk 一条龙）。
                    // v3.3.4（用户问题 5）：顺序调整 —— ① wineboot 会话先收尾
                    // 摘牌（onSessionExit）；② 解压与注册链（postPrefixBootChain
                    // taskmgrAtEnd=true）随后执行，全部完成后在链尾启动
                    // taskmgr 并登记为新会话（旧顺序 onSessionExit 在后会把
                    // 链尾登记的 taskmgr 会话摘掉）。
                    onSessionExit(key, code, sink)
                    if (isPrefixSession) {
                        runCatching {
                            postPrefixBootChain(
                                context, resolved, container, env, key, sink,
                                taskmgrAtEnd = true
                            )
                        }.onFailure {
                            sink.stamp("警告: wineboot 后续链异常: ${it.message}（下次启动自动重试）")
                            prefixLogLine(key, "wineboot 后续链异常: ${it.message}")
                        }
                        sink.stamp("前缀构建完成（wineboot → 解压 → 注册表 → taskmgr）")
                        prefixLogLine(key, "前缀构建完成")
                        _prefixBuildFlow.value = null
                    }
                }
                .exec()

            // v3.2.3：手动 wineboot 会话的无输出看门狗（WineRegistry 侧
            // initializePrefixIfNeeded 同款语义）—— 4 分钟零输出且
            // system.reg 未落盘时自动抓取 /proc 进程现场（R/S/D/Z +
            // 各线程 wchan），只取证不杀进程（mono/gecko 提示框等待
            // 用户确认属正常场景）。
            // v3.3.1：+8 分钟仍无输出 → 判定挂死并**自动恢复**（终止会话 +
            // 硬清理 wineserver）—— 此前该路径只取证不处置，proton
            // arm64ec 挂死时用户只能干等 15 分钟超时或手动强停，且强停
            // 后冻结的 wineserver 守护依然残留（-k 杀不掉），下次重试
            // 继续挂死。自动恢复把"挂死一次 = 永远挂死"的死循环拆掉。
            if (isPrefixSession) {
                val prefixMarker = File(container.prefixPath, "system.reg")
                Thread({
                    try { Thread.sleep(4 * 60_000L) } catch (_: InterruptedException) { return@Thread }
                    if (!sessionEnded.get() && !gotSessionOutput.get() && !prefixMarker.isFile) {
                        sink.stamp("wineboot 会话已 4 分钟无任何输出且 system.reg 未落盘 —— 疑似挂死，抓取进程现场：")
                        WineRegistry.dumpWineHangSnapshot(resolved, container) { sink.stamp(it) }
                        // v3.3.1：再等 4 分钟（mono/gecko 提示框等待确认的
                        // 合法窗口已给足）→ 仍无输出且前缀未落盘即判定挂死
                        try { Thread.sleep(4 * 60_000L) } catch (_: InterruptedException) { return@Thread }
                        if (!sessionEnded.get() && !gotSessionOutput.get() && !prefixMarker.isFile) {
                            sink.stamp("wineboot 会话累计 8 分钟无输出且前缀未落盘 —— 判定挂死，自动恢复：终止会话 + 硬清理 wineserver")
                            runCatching { executor.stopAndWait(true, 10, TimeUnit.SECONDS) }
                            runCatching {
                                WineServerUtils.ensureCleanBeforeStart(resolved, container, env, log = { sink.stamp(it) })
                            }
                            sink.stamp("→ 已自动恢复（挂死现场已记录于上方日志）；请重新构建前缀")
                            sink.stamp("→ 若再次挂死：请把本窗口完整日志（含进程现场与清理决策行）反馈给开发者")
                            _prefixBuildFlow.value = null
                        }
                    }
                }, "linbox-prefix-session-watchdog").apply { isDaemon = true; start() }
            }

            registerSession(key, WineSession(key, executor, sink))

            // v3.3.29：tabtip.exe 会话级压制（App 原生链路）。
            //   v3.3.28 只在终端脚本链路拦截且时机在会话启动前 —— 扑空
            //   （tabtip 由 wine 会话启动链路在 boot 后拉起；设备日志
            //   wine-10arm-20261109-120811 崩溃快照里 00000110 tabtip.exe
            //   仍在运行、其线程 0114 正在发 oleacc/uiautomation 查询，
            //   查询经 WM_GETOBJECT 同步送达 32 位游戏窗口过程，仙剑四等
            //   MFC 老游戏处理该消息解引用野指针崩溃）。
            //   此处为 wine 会话存活期间的后台扫杀（每 3s，wineserver 消失
            //   自止，全局单例防叠加；App 自身进程 cmdline 无 tabtip 字样）。
            startTabtipSuppression()

            // 13) v3.3.9（用户问题 2）：F5任务管理器随容器启动
            //     （startfex.sh / startwine.sh 的 `-5,--f5taskmgr` 同款：
            //     主程序拉起后附带启动 /opt/apps/f5taskmgr.exe；包不
            //     存在时跳过并提示，失败不影响主会话）。
            if (freshSession && container.hasFlag(com.linbox.apps.containers.ContainerFlags.F5TASK)) {
                val f5 = File("${GuanliSupport.OPT_ROOT}/apps/f5taskmgr.exe")
                if (f5.isFile) {
                    runCatching {
                        NativeShellExecutor.create()
                            .setCommand(
                                *(WineRuntime.wineCommand(resolved) + listOf(f5.absolutePath)).toTypedArray()
                            )
                            .putEnv(env)
                            .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                            .exec()
                    }.onSuccess {
                        sink.stamp("F5任务管理器已随容器启动（${f5.path}）")
                    }.onFailure {
                        sink.stamp("⚠ F5任务管理器启动失败：${it.message}（不影响主程序）")
                    }
                } else {
                    sink.stamp("⚠ F5任务管理器未找到（${f5.path}），跳过")
                }
            }
        } catch (e: Exception) {
            sink.stamp("启动失败：${e.message ?: e.javaClass.simpleName}")
            unregisterSession(key)
            // 日志保留供 UI 排障（lastLogs 已在链头登记）
        } finally {
            // 仅当令牌仍归本链所有时才清除（新链已覆盖时不误伤）
            pendingLaunches.remove(key, token)
        }
    }

    /**
     * v3.2.27：wineboot 之后的统一前缀构建链（两条入口共用）：
     *  - 游戏会话（非宿主工具命令）：initializePrefixIfNeeded 同步跑完
     *    wineboot 后接着执行；
     *  - 手动 wineboot 会话：进程退出后在 onExit 回调里接着执行 ——
     *    修复“wineboot 结束后啥也不继续，下次启动才解压/导注册表”。
     *
     * v3.3.4（用户问题 5："wineboot i 后运行 taskmgr 不是你那样啊，是先
     * 进行解压和注册再启动 taskmgr 啊，而且乱七八糟的，特别是会报无法
     * 访问的错误"）：**撤销 v3.2.29 的 taskmgr 前置保活** —— 旧版在解压/
     * 注册表导入之前就把 taskmgr 拉起来：
     *  - taskmgr 在半成品前缀上启动，与 regedit 导入并发争注册表，
     *    弹"无法访问"错误框；
     *  - 构建画面与 taskmgr 窗口混在一起（"乱七八糟"）；
     *  - 参考链路事实核查：startwine.sh / startfex.sh 里 wineboot 之后是
     *    【tar 解压 → cp 快捷方式 → regedit 三连 → fix-fonts → dxvk →
     *    wineserver -k】，每步都是独立 wine 客户端（server 按需拉起，
     *    并不需要保活进程）；`startwine taskmgr` 的 taskmgr 是构建
     *    【全部完成之后】才运行的最终命令（wineguanli.py 安装前缀 =
     *    `startwine taskmgr`，同义）。新顺序与之逐字对齐。
     *
     * 步骤（对齐 startwine.sh 前缀构建段 301-369 行，每步日志可见）：
     *  1. bootstrapOptPrefix —— /opt/prefix 预置资产（arm64ec 跳过
     *     drive_c/directx 两个 xz 预置包，见 WineRegistry v3.3.4）+
     *     start 开始菜单 + marlett.ttf + user/system/fix-services
     *     注册表 + fix-fonts + dxvk1；
     *  2. ensureDosdevices —— d:/e:/z: 盘符链接兑底；
     *  3. ensureEmulateModeset —— 无 RandR 环境的显示模式注册表；
     *  4. ensureModernLook —— 窗口现代化（ClearType/主题/壁纸）；
     *  5. applyDxvkToContainer —— APK 内置 DXVK（容器 dxvk=off 跳过）；
     *  6. WineLibrary.ensureApplied —— DPI/Windows 版本（无 DLL 加载序）。
     *  7.（可选 [taskmgrAtEnd]）解压与注册【全部完成之后】启动
     *     taskmgr（`startwine taskmgr` 同款：作为构建成功、容器就绪的
     *     可视信号保持运行，并登记为容器会话）。启动形态：
     *     - arm64ec → `wine taskmgr`；x86_64 → `box64 wine taskmgr`
     *       （wineCommand 自动包装）；FEX 勾选 → env 带
     *       HODLL=libwow64fex.dll；wowbox64 勾选 → HODLL=wowbox64.dll
     *      （startfex.sh 同款取值对；未勾选时不带 —— wine 内部缺省）。
     */
    /**
     * v3.3.2：rootfs/home 顶层的乱码文件移入隔离区（不删除）。
     *
     * 识别规则（宁可漏不可误伤）：文件名含 U+FFFD（非法字节解码残留）、
     * 含南亚/东南亚文字块（孟加拉/泰米尔/缅/高棉等 —— 用户实测乱码
     * "pE@ঈ@8@@@hh肨肕ll@…" 的特征，正常中文/英文文件名不含）、含 CJK
     * 扩展 B 区（𫀁 类平面四字节字符）或文件名超长（>96 字符）。
     * 命中即移动到 rootfs/home/.linbox/quarantine/（保留原名前缀 +
     * 时间戳），可人工甄别后删除或移回。
     */
    private fun quarantineHomeGarbageFiles(log: (String) -> Unit) {
        val home = WineEnvironment.HOME_DIR
        val quarantine = File(home, ".linbox/quarantine")
        runCatching {
            val suspectBlocks = setOf(
                Character.UnicodeBlock.BENGALI, Character.UnicodeBlock.TAMIL,
                Character.UnicodeBlock.TELUGU, Character.UnicodeBlock.KANNADA,
                Character.UnicodeBlock.MALAYALAM, Character.UnicodeBlock.GUJARATI,
                Character.UnicodeBlock.GURMUKHI, Character.UnicodeBlock.SINHALA,
                Character.UnicodeBlock.MYANMAR, Character.UnicodeBlock.KHMER,
                Character.UnicodeBlock.LAO, Character.UnicodeBlock.THAANA,
                Character.UnicodeBlock.TIBETAN, Character.UnicodeBlock.MONGOLIAN,
                Character.UnicodeBlock.ETHIOPIC, Character.UnicodeBlock.CHEROKEE,
                Character.UnicodeBlock.OGHAM, Character.UnicodeBlock.RUNIC,
                Character.UnicodeBlock.YI_SYLLABLES,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
            )
            var moved = 0
            home.listFiles { f -> f.isFile }?.forEach { f ->
                val name = f.name
                val suspicious = name.contains('\uFFFD') || name.length > 96 || name.any {
                    val b = Character.UnicodeBlock.of(it)
                    b != null && b in suspectBlocks
                }
                if (suspicious) {
                    quarantine.mkdirs()
                    val stamp = System.currentTimeMillis()
                    val safe = name.take(48).replace(Regex("[\\x00-\\x1f]"), "?")
                    if (f.renameTo(File(quarantine, "$stamp-$safe"))) moved++
                }
            }
            if (moved > 0) log("home 清理: $moved 个乱码文件移入 home/.linbox/quarantine（可人工甄别后删除）")
        }
    }

    private fun postPrefixBootChain(
        context: Context,
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        key: String,
        sink: SessionLogSink,
        // v3.3.4：true = 链尾启动 taskmgr（手动"安装前缀"会话）；
        // false = 不启动（游戏会话 —— 游戏本身就是随后的启动目标）
        taskmgrAtEnd: Boolean = false
    ) {
        val prefixLog: (String) -> Unit = { line ->
            sink.stamp(line)
            prefixLogLine(key, line)
        }
        // ---- (-1) v3.3.2：wineboot 失败自动重跑一次（system.reg 缺失时）----
        // 用户设备日志（wine-9.2-20261104-200746）实证：wineboot -i 因
        // err:virtual:map_fixed_area out of memory（39 位地址空间挤占）+
        // 服务自启失败等次生故障退出后 system.reg 未生成 → /opt/prefix
        // 的三个注册表（user.reg/system.reg/fix-services.reg）注入被跳过
        // → 游戏 DLL 加载序无安装包配置 → "media 错误"依旧。wineboot 可
        // 重入，重跑一次成本低（分钟级一次性路径）；仍失败才放行，日志
        // 大声可见。
        val systemReg = File(container.prefixPath, "system.reg")
        if (!systemReg.isFile) {
            prefixLog("前缀未就绪（system.reg 缺失）→ 自动重跑一次 wineboot -u 修复……")
            var rbCode = -1
            runCatching {
                // v3.3.3：-i → -u（startfex.sh 同款）+ 压 mono 弹窗。
                val rbCmd = WineRuntime.wineCommand(resolved) + listOf("wineboot", "-u")
                val rb = NativeShellExecutor.create()
                    .setCommand(*rbCmd.toTypedArray())
                    .putEnv(env)
                    .putEnv("WINEDLLOVERRIDES", "mscoree=disabled")
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                    .exec()
                val finished = runCatching { rb.waitFor(300, TimeUnit.SECONDS) }.getOrDefault(false)
                if (!finished) {
                    runCatching { rb.stopAndWait(true, 10, TimeUnit.SECONDS) }
                    prefixLog("警告: wineboot 重试超时（300s），已终止 —— 前缀构建链继续（失败步骤下次启动自动重试）")
                } else {
                    rbCode = runCatching { rb.exitValue() }.getOrDefault(-1)
                    prefixLog(
                        "wineboot 重试结束（退出码=$rbCode，" +
                            "system.reg ${if (systemReg.isFile) "已生成 ✓" else "仍缺失 ✗"}）"
                    )
                }
            }.onFailure { prefixLog("警告: wineboot 重试异常: ${it.message}") }
            // v3.3.33（用户："box64-0.3.8 运行 wine 还是不行"）：重试仍段错误
            //（139，ntdll 早期初始化 R15 高 32 位丢失型随机崩溃 —— v3.3.21
            // 注释记载的已知问题，地址空间布局依赖、SAFEFLAGS 无关）时，
            // 再以 BOX64_DYNAREC=0（纯解释器，确定性执行）兜底跑一次 ——
            // 前缀构建是一次性分钟级路径，解释器慢一点可接受，换来的是
            // "布局彩票"型崩溃不再让前缀永远建不起来。仅 box64 后端生效。
            if (!systemReg.isFile && rbCode == 139 &&
                resolved.backend == ContainerBackend.BOX64
            ) {
                prefixLog("wineboot 重试段错误（139，box64 dynarec 地址空间布局型随机崩溃）→ 以 BOX64_DYNAREC=0（解释器模式）再兜底一次……")
                runCatching {
                    val rb2 = NativeShellExecutor.create()
                        .setCommand(*(WineRuntime.wineCommand(resolved) + listOf("wineboot", "-u")).toTypedArray())
                        .putEnv(env)
                        .putEnv("WINEDLLOVERRIDES", "mscoree=disabled")
                        .putEnv("WINEPREFIX", container.prefixPath)
                        .putEnv("BOX64_DYNAREC", "0")
                        .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                        .exec()
                    val finished2 = runCatching { rb2.waitFor(600, TimeUnit.SECONDS) }.getOrDefault(false)
                    if (!finished2) {
                        runCatching { rb2.stopAndWait(true, 10, TimeUnit.SECONDS) }
                        prefixLog("警告: 解释器模式 wineboot 兜底超时（600s），已终止 —— 下次启动自动重试")
                    } else {
                        prefixLog(
                            "解释器模式兜底结束（退出码=${runCatching { rb2.exitValue() }.getOrDefault(-1)}，" +
                                "system.reg ${if (systemReg.isFile) "已生成 ✓" else "仍缺失 ✗"}）" +
                                (if (!systemReg.isFile) " —— 建议换用新版 box64（LinBox CI 已构建 v0.4.4+，仓库 Projects/box64）" else "")
                        )
                    }
                }.onFailure { prefixLog("警告: 解释器模式兜底异常: ${it.message}") }
            }
        }
        // v3.3.4：taskmgr 前置保活已移除（用户问题 5 —— 参考链路里解压与
        // 注册表的每一步都是独立 wine 客户端，wineserver 按需拉起，无需
        // 保活；taskmgr 提前跑反而与 regedit 争注册表弹"无法访问"）。
        // 解压与注册先行、taskmgr 链尾启动，见函数 KDoc。
        runCatching {
            WineRegistry.bootstrapOptPrefix(resolved, container, env, prefixLog)
        }.onFailure {
            sink.stamp("警告: /opt/prefix 预置资产注入异常: ${it.message}（下次启动自动重试）")
        }
        // v3.3.5 → v3.4.1【注册表大修】：旧一次性 purge（标记挡住存量段）
        // 升级为全量注册表卫生（幂等每次重跑）：wineserver 收尾落盘 →
        // wine.inf 源头剥除 → user.reg/system.reg AppDefaults 段剥除
        // （管理器 per-exe 账本白名单）→ 全局 [Software\\Wine\\DllOverrides]
        // 注入（上游同款）。/opt/prefix 导入侧同步剥段
        // （normalizeRegForBionic）—— 双保险。
        runCatching {
            WineRegistry.sanitizeRegistryHygiene(context, resolved, container, env, prefixLog)
        }.onFailure {
            sink.stamp("警告: 注册表卫生异常: ${it.message}（下次启动自动重试）")
        }
        run {
            // v3.1：dosdevices 兑底 —— 缺失时某些精简 wine 构建不建盘符，
            // 游戏按绝对路径找资源文件会全挂（media 找不到的另一诱因）。
            // v3.3.5：z: 直链内部 rootfs（不再触达宿主 /），d:/e: 直链
            // Download/exagear 承担外部存储直读（详见 ensureDosdevices）。
            WineRegistry.ensureDosdevices(container) { sink.stamp(it) }
            WineRegistry.ensureEmulateModeset(resolved, container, env) { sink.stamp(it) }
            // v3.2.11（用户问题 3）：wine 创建的窗口现代化 —— ClearType
            // 字体平滑 / 拖动显示窗口内容 / light 视觉主题。游戏自绘皮肤
            // 不受影响，失败不阻断启动（下次自动重试）。
            WineRegistry.ensureModernLook(resolved, container, env) { sink.stamp(it) }
            // v3.2.14（用户问题 3）：构建前缀时自动打入 APK 内置 DXVK
            // （assets/dxvk/dxvk-<ver>.tar.gz → C:\windows 的 system32/
            // syswow64，容器 dxvk=off 时跳过；幂等指纹，失败不阻断）。
            // v3.3.9（用户问题 1）：dxvk 选了 /opt/dxvk1 tar 槽位时 APK
            // 内置注入让位 —— tar 已在启动链步骤 0 解压到同一落点
            // （容器 C 盘 windows），双重注入会互相覆盖。
            val dxvkTarSlot = container.dxvk != ContainerData.DXVK_AUTO &&
                container.dxvk != ContainerData.DXVK_OFF &&
                GuanliSupport.scanOpt("dxvk1").any { it.display == container.dxvk }
            runCatching {
                com.linbox.apps.terminal.termux.LinBoxTzstAssets.applyDxvkToContainer(
                    context,
                    File(container.prefixPath),
                    dxvkEnabled = container.dxvk != ContainerData.DXVK_OFF && !dxvkTarSlot,
                    // v3.3.28：wineArch 真正传入（v3.3.21 加了参数但从未传值）
                    // —— arm64ec/hangover 后端 = aarch64 wine，system32 装
                    // arm64ec 原生档 DXVK（多架构包时），x86_64 客体免 FEX。
                    wineArch = if (resolved.backend == ContainerBackend.ARM64EC) "aarch64" else "x86_64"
                ) { sink.stamp(it) }
            }.onFailure {
                sink.stamp("警告: DXVK 注入异常: ${it.message}（下次启动自动重试）")
            }
            // v3.2.15（wineguanli.py 重制）：函数库设置注册表落地
            // （仅 DPI/Windows 版本，无 DLL 加载序；指纹幂等）。
            runCatching {
                WineLibrary.ensureApplied(resolved, container, env) { sink.stamp(it) }
            }.onFailure {
                sink.stamp("警告: 函数库设置注入异常: ${it.message}（下次启动自动重试）")
            }
        }
        // ---- 7) 链尾 taskmgr（v3.3.4 用户问题 5："先进行解压和注册再启动
        //      taskmgr"）：解压/注册表/函数库全部就位后才启动 ——
        //      `startwine taskmgr` 同款，作为"前缀构建完成、容器就绪"的
        //      可视信号保持运行（登记为容器会话，UI 显示运行中/可停止）。
        if (taskmgrAtEnd) {
            val tmCmd = WineRuntime.wineCommand(resolved) + listOf("taskmgr")
            prefixLog(
                "解压与注册全部完成 → 启动 taskmgr（${tmCmd.joinToString(" ")}" +
                    (env["HODLL"]?.let { "，HODLL=$it（用户选择：FEX模式/wowbox64模式/显式注入）" } ?: "") + "）—— 前缀就绪，容器可正常使用"
            )
            runCatching {
                val tmExecutor = NativeShellExecutor.create()
                    .setCommand(*tmCmd.toTypedArray())
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                    .onExit { code ->
                        sink.log("\n[taskmgr 退出码: $code] 前缀构建会话结束 " +
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                        unregisterSession(key)
                    }
                    .exec()
                // 立即退出（加载失败等）时不登记会话，避免"运行中"状态滞留
                if (tmExecutor.isRunning) {
                    registerSession(key, WineSession(key, tmExecutor, sink))
                } else {
                    prefixLog("taskmgr 已随即退出（容器资产与注册表不受影响，可直接运行程序）")
                }
            }.onFailure {
                prefixLog("警告: taskmgr 启动失败（${it.message}）—— 前缀资产与注册表已全部就位，不影响使用")
            }
        }
    }

    /**
     * 显示模式握手（apply_display_mode 移植）。
     *
     * 握手文件 `$APP_PREFIX/tmp/.linbox-x11-res`（App 侧
     * X11ResolutionLink FileObserver 消费）：d/v/f + 合法 WxH → 写
     * 分辨率；off / native / 非法值 → 写 native（跟随窗口）。
     *
     * v3.3.9（用户问题 3）：-f 不再用当前屏幕尺寸兑底 —— 选了分辨率才
     * 锁屏；-f 无分辨率 = 裸窗口 + 清虚拟桌面注册表（startwine.sh
     * `-f` 不带分辨率 → force_resolution 为空 + 删 -d 注册表的同款）。
     *
     * @return (dmode, 生效分辨率) —— 分辨率为空表示跟随窗口
     */
    private fun applyDisplayMode(container: ContainerData): Pair<String, String> {
        val dmode = container.dmode.id
        val screen = container.screen.trim()
        val resolution = if (Regex("^\\d+x\\d+$").matches(screen)) screen else ""
        val resFile = File("${ContainerManager.APP_PREFIX}/tmp/.linbox-x11-res")
        runCatching {
            resFile.parentFile?.mkdirs()
            if ((dmode == "d" || dmode == "v" || dmode == "f") && resolution.isNotEmpty()) {
                resFile.writeText(resolution)
            } else {
                resFile.writeText("native")
            }
        }
        return dmode to resolution
    }

    /**
     * 构建最终命令（run_wine 移植 + v3.1.4 wineboot 修正）。
     *
     * - `wineserver` 是真实 Unix ELF 宿主程序 → 直接执行宿主二进制
     *   （x86_64 形态经 box64/FEX 包装）；
     * - `wineboot`：wine ≤9.18/proton 树的 bin/wineboot 是 shell 脚本
     *   （box64 读不到 ELF 头，脚本原生 exec 又绕开 box64 → ENOEXEC），
     *   wine 9.19+ 是指向 loader 的符号链接 → 统一改走 wine loader：
     *   `[box64] bin/wine wineboot …`（loader 的 check_command_line 按
     *   argv[0] basename 分发到同名 .exe，官方机制，任何树都成立）；
     *
     * v3.3.9（用户问题 3：分辨率回归修复）：选了分辨率就要带 wine 界面
     * （虚拟桌面），只有全屏才是裸窗口 —— 对齐 startfex.sh / startwine.sh
     * 的 resolution 段：
     *   - dmode=d / v + WxH → `wine explorer /desktop=shell,WxH <args>`
     *     （脚本 modern-wine 路径：`resolution=("explorer" "/desktop=shell,WxH")`
     *     直接传递，wine 界面可见）；
     *   - dmode=f + WxH → 裸命令行，虚拟桌面由**注册表**控制
     *     （Explorer Desktops，见 WineRegistry.setVirtualDesktopRegistry
     *     —— startwine.sh apply_virtual_desktop_settings 同款，不是命令行
     *     传参那条路）；
     *   - dmode=off（全屏）→ 裸窗口直出（无 wine 界面）；游戏全屏时
     *     wine 经 RandR 把 X 屏幕切成游戏分辨率铺满；
     *   - wineboot（前缀构建）→ 仍包 explorer VD（mono/gecko 弹窗
     *     可见可点，v3.2.0 行为保留）。
     * 无 WM 下裸窗口堆 (0,0) 的场景由 X11FitClient 的钉满承接。
     */
    private fun buildCommand(
        resolved: WineRuntime.Resolved,
        wineArgs: List<String>,
        dmode: String,
        resolution: String
    ): List<String> {
        val first = wineArgs.firstOrNull()
        if (first == "wineserver") {
            val bin = WineRuntime.hostToolBin(resolved, first)
            return WineRuntime.hostCommand(resolved, bin) + wineArgs.drop(1)
        }
        val cmd = WineRuntime.wineCommand(resolved)
        if (first == "wineboot") {
            // v3.1.4：经 wine loader 执行（argv[0] basename 分发），
            // 不再直连 bin/wineboot（脚本/符号链接形态均不可靠）。
            // v3.2.0（用户诉求 4）：包 explorer /desktop 虚拟桌面 —— 无 WM 裸
            // 窗口下 wineboot 的 mono/gecko 提示框全部堆在 (0,0)（用户实测
            // “x11 没有显示任何画面/提示框看不到，无法继续构建”）；虚拟桌面
            // 铺满 X 屏幕自带桌面底色，子窗口由 wine 自管（可见/可点/可置前），
            // 构建画面从此直观可见。退出码语义：explorer 可能不透传 wineboot
            // 退出码，成败以 WINEPREFIX/system.reg 落盘为准（WineRegistry
            // 同步适配）。
            val vdRes = currentXScreenResolution()
            return cmd + listOf("explorer", "/desktop=LinBox,$vdRes") + wineArgs
        }
        // v3.3.9：选了分辨率的 -d / -v → 命令行包 explorer 虚拟桌面
        // （startwine.sh resolution=("explorer" "/desktop=shell,WxH") 同款）
        if ((dmode == "d" || dmode == "v") && resolution.isNotEmpty()) {
            return cmd + listOf("explorer", "/desktop=shell,$resolution") + wineArgs
        }
        // 其余裸窗口直出：off=真全屏（无 wine 界面）/ f=注册表虚拟桌面
        // （命令行不包 —— Explorer Desktops 注册表已在前置步骤写入）。
        // explorer 自引用防重包（用户手动传 explorer /desktop 时透传）。
        return cmd + wineArgs
    }

    // ------------------------------------------------------------------
    // v3.3.33：CPU 亲和性原生链（taskset 前缀）
    // ------------------------------------------------------------------

    /** taskset 二进制的运行时探测缓存（null = 未探测）。 */
    private var tasksetProbe: Pair<String, Boolean>? = null // (路径, 支持 -c 列表语法)

    /**
     * v3.3.33：给最终启动命令加 taskset 前缀，把 wine 及全部子进程钉到
     * PRIMARY_CORES（容器 CPU核心 页 cores= 配置）指定的核心集 —— 与
     * linbox-wine 的 apply_taskset_cores 同语义，但作用于 App 原生 exec 链
     *（旧版只导出变量不绑核）。仙剑四命中且未配置 cores 时自动单核。
     *
     * 安全性：taskset 二进制不存在 / 语法探测失败 → 原样返回命令（零影响）；
     * 语法探测（-c 列表式 vs 十六进制掩码式）用 /system/bin/true 一次探明并
     * 静态缓存，避免不同 toybox/util-linux 实现差异把启动搞挂。
     */
    private fun applyTasksetAffinity(
        command: List<String>,
        env: Map<String, String>,
        pal4AutoSingleCore: Boolean,
        log: (String) -> Unit
    ): List<String> {
        var cores = env["PRIMARY_CORES"]?.trim().orEmpty()
        if (cores.isEmpty() && pal4AutoSingleCore) {
            cores = "0"
            log("仙剑四兼容：未配置 cores，本次自动单核（多核竞态崩溃规避，CPU核心 页可改回）")
        }
        // v3.4.0【性能大修】：CPU 亲和默认开启 —— cores/cores.conf 均未
        // 配置时自动探测大核簇（cpufreq max_freq 最高组，上游性能模式
        // 同款思路）并钉核：wine 默认跑在调度器随手分的小核上，是
        // “bionic 不到 proot 一半”的重要级供应商；探测失败（无 sysfs/
        // 单簇 SoC）保持系统调度零风险。容器 CPU核心 页显式配置仍最高优。
        if (cores.isEmpty()) {
            val autoBig = WineEnvironment.autoPrimaryCores()
            if (autoBig.isNotEmpty()) {
                cores = autoBig.joinToString(",")
                log("CPU 亲和默认开启：未配置 cores，自动钉大核簇 cpu$cores（/sys cpufreq 探测；容器「CPU核心」页可改回）")
            }
        }
        if (cores.isEmpty()) return command
        val total = Runtime.getRuntime().availableProcessors()
        val parsed = WineEnvironment.parseCoreSpec(cores)
            .filter { it in 0 until total }.distinct().sorted()
        if (parsed.isEmpty()) {
            log("PRIMARY_CORES=$cores 无有效核心号（设备共 $total 核），跳过核绑定")
            return command
        }
        val probe = probeTaskset()
        if (probe == null) {
            log("PRIMARY_CORES=$cores 但未找到可用 taskset（rootfs/system 均无），跳过核绑定 —— 保持系统调度")
            return command
        }
        val (tasksetBin, supportsCList) = probe
        val finalCmd = if (supportsCList) {
            listOf(tasksetBin, "-c", parsed.joinToString(",")) + command
        } else {
            var mask = 0L
            parsed.forEach { mask = mask or (1L shl it) }
            listOf(tasksetBin, java.lang.Long.toHexString(mask)) + command
        }
        log(
            "核绑定: taskset ${if (supportsCList) "-c ${parsed.joinToString(",")}" else "mask=${WineEnvironment.formatCoreSpec(parsed)}"}" +
                "（cores=$cores，wine 及全部子进程继承；taskset=$tasksetBin）"
        )
        return finalCmd
    }

    /** 探测可用 taskset（rootfs → /system/bin；含 -c 语法探明），结果静态缓存。 */
    private fun probeTaskset(): Pair<String, Boolean>? {
        tasksetProbe?.let { return it }
        val candidates = listOf(
            File("${ContainerManager.ROOTFS_ROOT}/usr/bin/taskset"),
            File("${ContainerManager.ROOTFS_ROOT}/usr/local/bin/taskset"),
            File("/system/bin/taskset")
        )
        val bin = candidates.firstOrNull { it.isFile && it.canExecute() } ?: return null
        // -c（CPU 列表）语法探测：toybox 新版支持，老 util-linux 只认十六进制掩码。
        // 用 /system/bin/true（toybox 自带）做牺牲进程，一次探明。
        val trueBin = listOf(File("/system/bin/true"), File("/system/bin/id"))
            .firstOrNull { it.isFile && it.canExecute() }
        var supportsC = false
        if (trueBin != null) {
            supportsC = runCatching {
                val p = ProcessBuilder(bin.absolutePath, "-c", "0", trueBin.absolutePath)
                    .start()
                val ok = p.waitFor(3, TimeUnit.SECONDS)
                ok && p.exitValue() == 0
            }.getOrDefault(false)
        }
        val result = bin.absolutePath to supportsC
        tasksetProbe = result
        return result
    }

    /**
     * 当前 X 屏幕尺寸（用于"跟随窗口"模式的 wine 虚拟桌面几何）：
     * X11FitClient 实时值 → 落盘值（跨会话/启动链早于视图回调）→ 默认。
     * v3.2.0：internal —— WineRegistry 的前缀构建（wineboot 包虚拟桌面）
     * 复用同一分辨率源。
     */
    internal fun currentXScreenResolution(): String {
        val sw = X11FitClient.screenW
        val sh = X11FitClient.screenH
        if (sw > 0 && sh > 0) return "${sw}x${sh}"
        X11ResolutionLink.readScreenSize()?.let { return it }
        return X11ResolutionLink.DEFAULT_RES
    }

    // ------------------------------------------------------------------
    // 会话登记与收尾
    // ------------------------------------------------------------------

    private fun registerSession(key: String, session: WineSession) {
        synchronized(sessions) {
            sessions.remove(key)?.finishIfRunning()
            sessions[key] = session
            trimSessionsLocked()
        }
    }

    private fun unregisterSession(key: String) {
        val removed = synchronized(sessions) { sessions.remove(key) }
        // v3.3.40【性能】：会话日志改为缓冲写（SessionLogSink.close 落盘
        // 残余行 —— 旧版每行 open/close 文件，chatty wine stderr 时上千次
        // 文件打开/关闭）。
        removed?.sink?.close()
    }

    /** 回收超量会话（对齐旧版 trimSessions）。 */
    private fun trimSessionsLocked() {
        while (sessions.size > MAX_SESSIONS) {
            val eldest = sessions.entries.firstOrNull() ?: break
            eldest.value.finishIfRunning()
            sessions.remove(eldest.key)
            eldest.value.sink.close()
        }
    }

    private fun onSessionExit(key: String, code: Int, sink: SessionLogSink) {
        sink.log(
            "\n[进程退出码: $code] 会话结束 " +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n"
        )
        unregisterSession(key)
    }

    // ------------------------------------------------------------------
    // 日志文件（沿用旧版候选目录策略）
    // ------------------------------------------------------------------

    /** 新建会话日志文件（公共 Download → 容器 logs；全失败返回 null）。 */
    internal fun newSessionLog(containerName: String): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val safe = containerName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(40).ifEmpty { "default" }
        for (dir in listOf(
            File("/storage/emulated/0/Download/LinBox"),
            File("${ContainerManager.CONTAINERS_ROOT}/logs")
        )) {
            try {
                if (!dir.isDirectory) dir.mkdirs()
                if (!dir.isDirectory || !dir.canWrite()) continue
                val f = File(dir, "wine-$safe-$stamp.log")
                if (!f.createNewFile() && !f.isFile) continue
                return f
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun appVersionLine(context: Context?): String = try {
        val ctx = context ?: com.linbox.LinBoxApp.get()
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${ctx.packageName} ${pi.versionName ?: "?"}"
    } catch (_: Exception) {
        "?"
    }
}
