package com.linbox.apps.containers.launcher

import com.linbox.apps.containers.ContainerManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v3.2.28（用户问题 1/2："wine 没有读取 rootfs/opt/conf 里任何一个配置
 * 文件、locale.conf 也没读取，wine 语言总是英文"、"构建前缀在解包和
 * 注册时丢失"）：/opt 生态包**自动安装 + 自愈**。
 *
 * ## 背景
 *
 * startwine.sh 参考链路里 conf/locale.conf/prefix/dxvk1/apps 全部位于
 * rootfs 内 /opt（= /data/data/com.linbox/files/rootfs/opt，用户明确
 * 指定）。App 的 conf 读取（applyOptConfEnv）、locale 语言（LC_ALL）、
 * 前缀构建解包+注册（bootstrapOptPrefix）全部以 [WineEnvironment.optRoot]
 * 探测为开关 —— 探测失败时三件事一起旁路（设备日志实证：
 * "opt 生态: 未检测到（rootfs/opt ✗ …）"）。
 *
 * 探测代码本身逐条核对无误，设备侧 ✗ 的常见成因是**生态内容没有以
 * "解开的状态"落到 rootfs/opt**：
 *  - 用户把整个生态 tar 包（conf/prefix/locale.conf/… 打包）原样放进
 *    rootfs/opt，等 App 解开 —— 旧版没人解；
 *  - 包根是 opt/ 的包解进了 rootfs/opt，内容多套一层 opt/；
 *  - 包根是 opt/ 的包经 wine 槽位安装（--strip-components=1）落进
 *    槽位 opt/ 子目录。
 *
 * ## 动作（幂等，仅在探测失败时执行）
 *
 *  1. rootfs/opt 顶层的生态 tar 包自动解包：`tar -tf` 看根布局 ——
 *     条目以 opt/ 开头 → 解到 rootfs（包根即 opt）；否则解到 rootfs/opt。
 *     包内若在根级带 bin/wine（纯 wine 树）→ 不动（那是 wine 包，
 *     走槽位安装，避免整棵 wine 树灌进 /opt）。成功后按包名落标记，
 *     失败下次启动重试。
 *  2. 套层与槽位 opt/ 由 [WineEnvironment.optRoot] 的扩展候选直接
 *     读取（不搬用户文件）。
 *
 * 之后重新探测并大声输出结果 —— conf/locale.conf/prefix 三件事
 * （source 导出、LC_ALL 语言、前缀解包注册）随之恢复。
 */
object OptEcosystemSupport {

    private const val RFS_ROOT = ContainerManager.ROOTFS_ROOT

    /** 已解包生态包标记目录（按包名记文件，幂等）。 */
    private val extractedMarkerDir: File get() = File(RFS_ROOT, "opt/.linbox-opt-pkgs")

    private val TAR_CANDIDATES = listOf(
        File("$RFS_ROOT/usr/bin/tar"),
        File("${ContainerManager.APP_PREFIX}/bin/tar")
    )

    private val ARCHIVE_SUFFIXES = listOf(
        ".tar.xz", ".txz", ".tar.gz", ".tgz", ".tar.zst", ".tzst",
        ".tar.bz2", ".tbz2", ".tar"
    )

    /**
     * 启动链入口：探测健康直接返回；否则尝试解包 rootfs/opt 下的
     * 生态 tar 包并重新探测。每一步都有日志（前缀构建窗口可见）。
     *
     * @param wineDir 当前 wine 槽位目录（透传给探测）
     * @return 最终探测到的 /opt 根（null = 仍未检测到）
     */
    fun ensure(wineDir: File?, log: (String) -> Unit = {}): File? {
        WineEnvironment.optRoot(wineDir)?.let { return it }

        // ① rootfs/opt 顶层生态 tar 包自动解包（幂等：按包名记标记）
        val optDir = File(RFS_ROOT, "opt")
        val pending = optDir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && ARCHIVE_SUFFIXES.any { s -> f.name.lowercase().endsWith(s) } }
            .orEmpty()
            .filter { !markerExists(it) }
        for (pkg in pending) {
            extractOptPackage(pkg, log)
        }

        // ② 重新探测并汇报
        val root = WineEnvironment.optRoot(wineDir)
        if (root != null) {
            log("opt 生态: 自愈后检测到 → $root（conf/locale.conf/prefix 恢复消费）")
        } else {
            log("opt 生态: 未检测到（探测: ${WineEnvironment.optProbeReport(wineDir)}）")
            log("→ 期望位置: $RFS_ROOT/opt/{conf,locale.conf,prefix,dxvk1,apps}；")
            log("→ 若生态内容还是压缩包，请放到 $RFS_ROOT/opt/ 下（如 opt.tar.xz），下次启动自动解包")
        }
        return root
    }

    private fun markerExists(pkg: File): Boolean =
        File(extractedMarkerDir, pkg.name).isFile

    /**
     * 解包单个生态 tar 包。根布局自适应（v3.2.28）：
     *  - 包根级带 bin/wine（含"单顶层目录/bin/wine"形态）→ 纯 wine 程序包，
     *    不动（走 wine 版本管理槽位安装，避免整棵 wine 树灌进 /opt）；
     *  - 条目全以 opt/ 开头 → 包根即 opt，解到 rootfs 根；
     *  - 其余单顶层目录包装（linbox-opt/… 等）→ 剥一层解到 rootfs/opt；
     *  - conf/prefix/locale.conf 等多顶层条目 → 直接解到 rootfs/opt。
     * 输出重定向到临时文件（大包列表动辄数十万行，管道会撑爆）。
     */
    private fun extractOptPackage(pkg: File, log: (String) -> Unit) {
        val tarBin = TAR_CANDIDATES.firstOrNull { it.canExecute() } ?: run {
            log("opt 生态包: 跳过 ${pkg.name}（rootfs 内无 tar 可用）")
            return
        }
        runCatching {
            val tmp = File.createTempFile("linbox-opt-", ".log", File(RFS_ROOT, "usr/tmp").apply { mkdirs() })
            // —— 看根布局（全量条目落文件再判定，防管道截断）——
            val listed = runTar(tarBin, listOf("-tf", pkg.absolutePath), tmp, 120_000L)
            if (listed == null) {
                log("opt 生态包: ${pkg.name} 根布局探测超时/失败，跳过（下次启动重试）")
                return
            }
            val entries = listed.lineSequence()
                .map { it.trim().trimStart('.', '/') }
                .filter { it.isNotEmpty() }
                .toList()
            if (entries.isEmpty()) {
                log("opt 生态包: ${pkg.name} 无法读取内容（非 tar 或损坏），跳过")
                return
            }
            // 纯 wine 程序包判定：根级或单顶层目录下的 bin/wine（生态包内
            // 的 wine 在 opt/<slot>/bin/wine，两级以上，不会命中）
            val wineTree = Regex("^(?:[^/]+/)?bin/wine(?:64)?$")
            if (entries.any { wineTree.matches(it) }) {
                log("opt 生态包: ${pkg.name} 是 wine 程序包（根级 bin/wine）—— 不解入 /opt，请用 wine 版本管理安装")
                markExtracted(pkg)
                return
            }
            val topDirs = entries.map { it.substringBefore('/', "") }.filter { it.isNotEmpty() }.toSet()
            val (dest, strip, note) = when {
                topDirs == setOf("opt") -> Triple(File(RFS_ROOT), 0, "包根 opt/ → rootfs")
                topDirs.size == 1 -> Triple(File(RFS_ROOT, "opt"), 1, "单顶层目录 ${topDirs.first()}/ 剥壳 → rootfs/opt")
                else -> Triple(File(RFS_ROOT, "opt"), 0, "生态内容直装 → rootfs/opt")
            }
            dest.mkdirs()
            log("opt 生态包: 解包 ${pkg.name} → ${dest.absolutePath}（$note）……")
            val args = mutableListOf("-xf", pkg.absolutePath)
            if (strip > 0) args.add("--strip-components=$strip")
            args.addAll(listOf("-C", dest.absolutePath))
            val extracted = runTar(tarBin, args, tmp, 600_000L)
            if (extracted == null) {
                log("opt 生态包: ${pkg.name} 解包超时/失败（600s），下次启动重试")
                return
            }
            markExtracted(pkg)
            log("opt 生态包: ${pkg.name} 解包完成")
            tmp.delete()
        }.onFailure { log("opt 生态包: ${pkg.name} 处理异常: ${it.message}（下次启动重试）") }
    }

    /** 执行 tar：输出落临时文件（无管道死锁），@return 输出文本；超时/失败 null。 */
    private fun runTar(tarBin: File, args: List<String>, outFile: File, timeoutMs: Long): String? {
        return try {
            val p = ProcessBuilder(listOf(tarBin.absolutePath) + args)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(outFile))
                .start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0) return null
            runCatching { outFile.readText() }.getOrDefault("")
        } catch (_: Exception) {
            null
        }
    }

    private fun markExtracted(pkg: File) {
        runCatching {
            extractedMarkerDir.mkdirs()
            File(extractedMarkerDir, pkg.name).writeText("extracted=${System.currentTimeMillis()}\n")
        }
    }
}
