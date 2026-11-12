package com.linbox.apps.containers.launcher

import android.content.Context
import android.net.LocalSocketAddress
import android.net.LocalSocket
import android.os.SystemClock
import com.linbox.apps.containers.ContainerManager
import com.linbox.apps.x11.X11WindowController
import java.io.File

/**
 * X11 显示方案联动（原生启动链的显示环节 —— LinBox 与 上游发行包 的
 * 核心差异点：**上游发行包 用进程内 Weston/Wayland，LinBox 保留
 * termux-x11/libXlorie 的 X11 方案**）。
 *
 * ## v3.1：X 服务拉起完全去脚本化（本文件的核心变更）
 *
 * 旧实现仍以 `$PREFIX/bin/sh $PREFIX/bin/termux-x11 :1` 启动 X 服务 ——
 * 本质上还是"App 调 shell 脚本"，且存在三处断点：
 *  1. **缺 `XDG_RUNTIME_DIR`**：linbox-wine 的 ensure_x11 显式同时设置
 *     XDG_RUNTIME_DIR 与 TMPDIR（socket 落在 App 侧 tmp/.X11-unix 的
 *     前提），旧 Kotlin 代码只设了 TMPDIR —— socket 位置不确定，
 *     awaitSocketReady 等的是 A、X 服务把 socket 建在 B；
 *  2. 依赖 `bin/termux-x11` 脚本 + `etc/linbox-x11.env` 文件链（任何
 *     一环缺失/被覆盖即静默失败，用户被迫回到终端手敲 linbox-x11）；
 *  3. 失败被 `catch(_: Exception)` 吞掉，日志里一个字都没有。
 *
 * 新实现与脚本做**完全相同的事**，但全部由 Kotlin 完成：
 *
 * ```
 * /system/bin/app_process -Xnoimage-dex2oat / \
 *     --nice-name=linboxx11 :1 com.termux.x11.CmdEntryPoint :1
 * ```
 *
 *  - `CLASSPATH` = 宿主 APK 路径（`context.applicationInfo.sourceDir`
 *    直取 —— 不再经 env 文件间接读取，APK 升级天然自愈）；
 *  - `LINBOX_X11_NATIVE_DIR` = `applicationInfo.nativeLibraryDir`
 *    （libXlorie.so 加载位置，同源直取）；
 *  - `TERMUX_X11_OVERRIDE_PACKAGE=com.linbox`（X 服务渲染广播只回
 *    LinBox 自己 —— X11WindowController 收到后自动弹桌面 X11 窗口，
 *    **这就是"画面自动显示、无需终端"的通路**）；
 *  - `TMPDIR` 与 `XDG_RUNTIME_DIR` 一并指向 App 侧 tmp；
 *  - `XKB_CONFIG_ROOT`：XKB 键盘数据由 [XkbExtractor] 原生解压
 *    （脚本 tar -xzf 的职责承接，X 服务初始化必需，缺失即退出）。
 *
 * 陈旧 socket 文件（X 进程死后残留）会让新 X 服务 bind 失败
 * （EADDRINUSE），因此拉起前先以 [isSocketListening] 探活、
 * 死文件直接清除；X 进程存活探测扫 /proc 的 cmdline。
 */
object X11Support {

    /** X 服务自动拉起的去抖窗口（冷启动 socket 未就绪期间防重复）。 */
    private const val SERVICE_START_DEBOUNCE_MS = 8000L

    /** X socket 就绪等待（对齐 linbox-wine ensure_x11：20 × 0.5s）。 */
    private const val SOCKET_WAIT_ROUNDS = 20
    private const val SOCKET_WAIT_INTERVAL_MS = 500L

    /** X 服务渲染广播目标（CmdEntryPoint.createIntent 读取）。 */
    private const val XSERVER_PACKAGE = "com.linbox"

    /** app_process 入口（Android 标准路径，脚本同款）。 */
    private const val APP_PROCESS = "/system/bin/app_process"

    /** X 服务主类（lorie 合成器 + Xwayland，编译进宿主 APK）。 */
    private const val XSERVER_CLASS = "com.termux.x11.CmdEntryPoint"

    @Volatile
    private var lastServiceStartAt = 0L

    /** 当前 DISPLAY（:1，LINBOX_DISPLAY 可覆盖）。 */
    fun display(): String {
        val override = System.getenv("LINBOX_DISPLAY")
        return if (!override.isNullOrBlank()) override else ":1"
    }

    /** DISPLAY 对应的 socket 文件（X11_SOCK_DIR/X<n>）。 */
    private fun socketFor(display: String): File {
        var num = display.removePrefix(":").substringBefore('.')
        if (num.isEmpty() || num.any { !it.isDigit() }) num = "1"
        return File(ContainerManager.X11_SOCK_DIR, "X$num")
    }

    // ------------------------------------------------------------------
    // 存活探测
    // ------------------------------------------------------------------

    /**
     * socket 文件是否真实可连（有进程在监听）。
     *
     * 文件存在 ≠ 服务在跑：X 进程死后 socket 文件会残留，直接复用会让
     * wine 连死端点（黑屏）。经 Android 本地套接字试连一次（连接成功
     * 即刻关闭，对 X 服务而言只是一次零载荷的 client connect/disconnect，
     * 无副作用）。
     */
    fun isSocketListening(sock: File): Boolean {
        if (!sock.exists()) return false
        return try {
            val socket = LocalSocket()
            try {
                socket.connect(
                    LocalSocketAddress(
                        sock.absolutePath,
                        LocalSocketAddress.Namespace.FILESYSTEM
                    )
                )
                true
            } finally {
                runCatching { socket.close() }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * linboxx11 X 服务进程是否存活（扫 /proc cmdline，nice-name 特征）。
     *
     * v3.0（LinBox 移植修复）：**按显示号精确匹配** —— linbox 自有终端
     * X11（`linbox-x11 :13`）与容器 X 服务（X11Support :1）的
     * nice-name 同为 "linboxx11 <display>"，仅靠 "linboxx11" 子串会把
     * 终端侧 X 服务误判为容器 X 服务冷启动中（导致 :1 永不拉起、
     * awaitSocketReady 超时无画面）。
     */
    fun x11ProcessAlive(display: String = display()): Boolean = try {
        File("/proc").listFiles { f -> f.name.toIntOrNull() != null }
            .orEmpty().any { proc ->
                runCatching {
                    val cmdline = File(proc, "cmdline")
                    cmdline.isFile && cmdlineMatchesDisplay(cmdline.readText(), display)
                }.getOrDefault(false)
            }
    } catch (_: Exception) {
        false
    }

    /** cmdline 是否为指定显示号的 linboxx11 进程（argv0 改写/原始 argv 双形态）。 */
    private fun cmdlineMatchesDisplay(cmdlineText: String, disp: String): Boolean {
        val parts = cmdlineText.split('\u0000', ' ').filter { it.isNotBlank() }
        // 形态 A：SetArgV0 改写后 argv0 = "linboxx11"，下一个参数为显示号
        val idx = parts.indexOfFirst { it == "linboxx11" }
        if (idx >= 0) return idx + 1 < parts.size && parts[idx + 1] == disp
        // 形态 B：原始 app_process argv（--nice-name=linboxx11 :N）
        return cmdlineText.contains("--nice-name=linboxx11 $disp\u0000") ||
            cmdlineText.endsWith("--nice-name=linboxx11 $disp")
    }

    /**
     * 当前是否存在可用的 X11 socket（App 侧）。
     *
     * v3.1 起要求"文件存在且可连"（陈旧 socket 不再误判为就绪 ——
     * doctor 与启动链共用本判定，旧版在 X 进程死亡后会给出假 OK）。
     */
    fun x11SocketReady(): Boolean {
        val dir = File(ContainerManager.X11_SOCK_DIR)
        if (!dir.isDirectory) return false
        val socks = dir.listFiles { f -> f.name.startsWith("X") } ?: return false
        return socks.any { isSocketListening(it) }
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 确保 X11 桌面窗口就位：自动拉起 X 服务（若未运行）+ 打开桌面窗口。
     *
     * 拉起失败不阻断流程（等待页持续重试，doctor 可诊断），但会把
     * 决策行写入会话日志（不再静默）。
     */
    @JvmOverloads
    fun ensureX11Window(context: Context, log: (String) -> Unit = {}) {
        try {
            ensureX11Service(context, log)
        } catch (e: Exception) {
            log("警告: X 服务拉起异常：${e.message ?: e.javaClass.simpleName}")
        }
        try {
            X11WindowController.openWindow(context)
        } catch (_: Exception) {
        }
    }

    /**
     * 原生拉起内置 X 服务（显示号默认 :1）—— v3.1 完全去脚本化。
     *
     * 判定顺序：
     *  1. socket 存在且可连 → 服务在跑，直接复用；
     *  2. socket 文件存在但连不上（陈旧残留）→ 删除（否则 X 服务
     *     bind 报 Address already in use）；
     *  3. linboxx11 进程存活（正在冷启动）→ 不重复拉起，交给
     *     [awaitSocketReady] 等它；
     *  4. 去抖后直接 exec app_process（无 sh / 无脚本 / 无 PTY）。
     */
    @JvmOverloads
    fun ensureX11Service(context: Context, log: (String) -> Unit = {}) {
        val now = SystemClock.elapsedRealtime()
        val disp = display()
        val sock = socketFor(disp)

        if (isSocketListening(sock)) return
        if (sock.exists()) {
            runCatching { sock.delete() }
            log("清理陈旧 X socket（文件残留但 X 服务不在监听）: $sock")
        }
        if (x11ProcessAlive(disp)) return   // 本显示号冷启动进行中，等待即可

        if (now - lastServiceStartAt < SERVICE_START_DEBOUNCE_MS) return
        lastServiceStartAt = now

        // ---- v3.4.0【性能大修】：X server 前台主进程化（第一铲）----
        //      裸 app_process 是 App 的 phantom 子进程：Android 12+ 按
        //      "被 AMS 跟踪的进程"调整 cgroup/冻结，它不在名单 —— 但
        //      fork 瞬间继承父进程所处的前台/后台 cgroup。旧链路把
        //      KeepAliveService 留到 X 窗口广播（ACTION_START）之后才起，
        //      冷启动窗口期 App 可能已因切后台被降组 → X server 生在
        //      background cgroup 里（top-app 限流差一个数量级 ——
        //      "bionic 不到 proot 一半"的重要级供应商）。
        //      现在在 exec app_process【之前】先把前台服务立起来，把 App
        //      钉回前台态，fork 继承 top-app 组；后续 wine/box64 同样受益。
        runCatching { com.linbox.apps.x11.X11KeepAliveService.start(context) }
            .onFailure { log("警告: X11 前台常驻服务提前启动失败（继续拉起 X 服务）: ${it.message}") }

        // ---- XKB 键盘数据就位（X 服务初始化必需；脚本 tar -xzf 的
        //      职责由 XkbExtractor 原生承接） ----
        val xkbRoot = ensureXkbData(log)

        // ---- 宿主组件定位（直取 Context，不经 etc/linbox-x11.env） ----
        val apkPath = context.applicationInfo?.sourceDir
            ?: run { log("X 服务拉起失败：无法定位宿主 APK"); return }
        val nativeDir = context.applicationInfo?.nativeLibraryDir
        if (!File(apkPath).canRead()) {
            log("X 服务拉起失败：宿主 APK 不可读（$apkPath）")
            return
        }
        if (!File(APP_PROCESS).isFile) {
            log("X 服务拉起失败：$APP_PROCESS 不存在（非标准 Android 系统）")
            return
        }

        // ---- 运行时目录（socket 目录必须先在，X 服务要在这里 bind） ----
        val tmpDir = File("${ContainerManager.APP_PREFIX}/tmp")
        runCatching { tmpDir.mkdirs() }
        runCatching { File(ContainerManager.X11_SOCK_DIR).mkdirs() }

        val logFile = File(tmpDir, "linbox-x11-autostart.log")
        try {
            NativeShellExecutor.create()
                .setCommand(
                    APP_PROCESS,
                    "-Xnoimage-dex2oat",
                    "/",
                    "--nice-name=linboxx11 $disp",
                    XSERVER_CLASS,
                    disp
                )
                // 输出全部落日志文件（不用管道：X 服务常驻进程写满
                // 管道缓冲会卡死）
                .redirectOutputTo(logFile)
                // app_process 不需要任何库搜索路径（脚本 unset 同款；
                // LD_PRELOAD/LD_LIBRARY_PATH 已由 NativeShellExecutor 清洗）
                .putEnv(
                    mapOf(
                        "CLASSPATH" to apkPath,
                        "TERMUX_X11_OVERRIDE_PACKAGE" to XSERVER_PACKAGE,
                        "TMPDIR" to tmpDir.absolutePath,
                        // ★ v3.1 修复：XDG_RUNTIME_DIR 必须与 TMPDIR 同指
                        // App 侧 tmp（旧版缺失 —— lorie 的 socket/运行时
                        // 布局依据之一，缺失时 socket 位置不确定）
                        "XDG_RUNTIME_DIR" to tmpDir.absolutePath,
                        "HOME" to "${ContainerManager.APP_PREFIX}/home",
                        "PATH" to "/system/bin"
                    )
                )
                .apply {
                    if (!nativeDir.isNullOrBlank()) {
                        putEnv("LINBOX_X11_NATIVE_DIR", nativeDir)
                    }
                    xkbRoot?.let { putEnv("XKB_CONFIG_ROOT", it.absolutePath) }
                }
                .exec()
            log(
                "已原生拉起 X 服务（app_process 直启 $disp，不经 sh/脚本；" +
                    "排障日志: $logFile）"
            )
            // X 进程起来后即向 com.linbox 周期广播 ACTION_START，
            // X11WindowController 收到即自动弹出桌面 X11 窗口 —— 画面
            // 链路不再依赖终端。
        } catch (e: Exception) {
            log("X 服务拉起失败：${e.message ?: e.javaClass.simpleName}（排障: cat $logFile）")
        }
    }

    /**
     * 等待 X socket 就绪（wine 启动前的显式同步点）。
     *
     * v3.1 起每轮以可连性为准（文件存在但连不上 = 陈旧残留），
     * 超时后清掉死文件，避免下次启动被误判为就绪。
     *
     * @return true 就绪；false 超时（打警告，不阻断 —— 与脚本行为一致）
     */
    fun awaitSocketReady(display: String, log: (String) -> Unit = {}): Boolean {
        val sock = socketFor(display)
        if (isSocketListening(sock)) return true
        repeat(SOCKET_WAIT_ROUNDS) {
            if (isSocketListening(sock)) return true
            runCatching { Thread.sleep(SOCKET_WAIT_INTERVAL_MS) }
        }
        if (isSocketListening(sock)) return true
        if (sock.exists()) {
            runCatching { sock.delete() }
            log("已清理未就绪的 X socket 死文件: $sock")
        }
        log(
            "警告: X 服务拉起后 socket 未就绪（$sock）——画面可能无法显示；" +
                "排障: cat ${ContainerManager.APP_PREFIX}/tmp/linbox-x11-autostart.log"
        )
        return false
    }

    // ------------------------------------------------------------------
    // XKB 键盘数据
    // ------------------------------------------------------------------

    /**
     * 确保 XKB 数据就位并返回 XKB_CONFIG_ROOT。
     *
     * 优先级（对齐 bin/termux-x11 脚本）：
     *  1. `etc/linbox/x11/xkb/rules` 已存在 → 直接用；
     *  2. `etc/linbox/x11/xkb.tar.gz`（bootstrap 部署）→ [XkbExtractor]
     *     原生解压；
     *  3. `share/X11/xkb`（pkg xkeyboard-config 安装）→ 兜底。
     */
    private fun ensureXkbData(log: (String) -> Unit): File? {
        val xkbDir = File("${ContainerManager.APP_PREFIX}/etc/linbox/x11/xkb")
        if (File(xkbDir, "rules").isDirectory) return xkbDir

        val tarball = File("${ContainerManager.APP_PREFIX}/etc/linbox/x11/xkb.tar.gz")
        if (tarball.isFile) {
            if (XkbExtractor.extract(tarball, xkbDir, log) &&
                File(xkbDir, "rules").isDirectory
            ) {
                log("XKB 键盘数据已解压就位（$xkbDir）")
                return xkbDir
            }
            log("警告: XKB 数据解压异常（$tarball）")
        }
        val shared = File("${ContainerManager.APP_PREFIX}/share/X11/xkb")
        if (File(shared, "rules").isDirectory) return shared

        log("警告: XKB 键盘数据缺失 —— X 服务可能无法初始化（打开一次主界面重新部署，或 pkg install xkeyboard-config）")
        return null
    }
}
