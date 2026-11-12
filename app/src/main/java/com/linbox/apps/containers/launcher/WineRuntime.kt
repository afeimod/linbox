package com.linbox.apps.containers.launcher

import com.linbox.apps.containers.ContainerBackend
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerManager
import java.io.File
import java.io.RandomAccessFile

/**
 * Wine 运行时解析 —— 原生启动链的第一步。
 *
 * 职责（完整移植 linbox-wine 脚本的 resolve_wine_dir / detect_wine_arch /
 * pick_backend / auto_backend 四段逻辑，语义逐条对齐）：
 *  1. 依容器 `wine=` → 全局 `default_wine=` → `/usr/opt/wine` 解析 wine 目录；
 *  2. 读 wine 二进制 ELF 头的 e_machine（偏移 18，2 字节小端）判定形态：
 *     aarch64（bionic-arm64ec 构建，new WoW64 原生跑）或 x86_64（经
 *     box64 / FEX / native 跑）；
 *  3. 依容器 backend 配置 + 设备架构 + 组件可用性推导最终后端。
 *
 * App 端从 [ContainerManager.listWines] 同源读取槽位信息，与终端 CLI
 * （linbox-container wine-list）看到的数据完全一致。
 */
object WineRuntime {

    /** wine 二进制 ELF 形态。 */
    enum class Arch(val id: String) {
        AARCH64("aarch64"),
        X86_64("x86_64"),
        UNKNOWN("unknown")
    }

    /**
     * 一次启动所需的全部运行时定位结果。
     *
     * @property wineDir       wine 安装目录（/usr/opt/<槽位>）
     * @property arch          wine 二进制 ELF 形态
     * @property backend       最终选定的执行后端
     * @property wineBinary    wine 主二进制
     * @property wineServerBin wineserver 二进制（同目录）
     * @property wineBootBin   wineboot 二进制（同目录）
     */
    data class Resolved(
        val wineDir: File,
        val arch: Arch,
        val backend: ContainerBackend,
        val wineBinary: File,
        val wineServerBin: File,
        val wineBootBin: File
    )

    /** rootfs 侧路径常量的本地别名（与 ContainerManager 逐字对齐）。 */
    private val RFS_ROOT = ContainerManager.ROOTFS_ROOT
    private val RFS_USR = "$RFS_ROOT/usr"
    private val WINE_OPT = ContainerManager.WINE_OPT_DIR

    /** 旧版 hangover wine 兜底路径（rootfs 打包时内置的 aarch64 构建）。 */
    val HANGOVER_BIN = File("$RFS_USR/opt/bin/wine")

    /** FEX-Emu 解释器路径。 */
    val FEX_BIN = File("$RFS_USR/opt/fex/bin/FEXInterpreter")

    /** box64 候选路径（rootfs local → rootfs bin → App 前缀）。 */
    fun locateBox64(): File? = listOf(
        File("$RFS_USR/local/bin/box64"),
        File("$RFS_USR/bin/box64"),
        File("${ContainerManager.APP_PREFIX}/bin/box64")
    ).firstOrNull { it.isFile && it.canExecute() }

    /**
     * v3.3.40【性能】：resolve 结果缓存 —— 一次启动链此前要做 3 次
     * resolve（precheckRun → submitLaunch 预检 → launch），每次重复
     * readGlobalConfKey 全文件读取 + ELF 头随机读 + box64 三候选探测。
     * 缓存键 = 容器名 + wine 槽位 + 后端；槽位目录/主程序失效即自愈。
     * [invalidateCache] 在 wine 安装/删除/换默认时显式清（同槽位重装
     * 可能换 ELF 形态）。
     */
    private val resolveCache =
        java.util.concurrent.ConcurrentHashMap<String, Resolved>()

    /** wine 槽位变化（安装/删除/默认切换）后调用。 */
    fun invalidateCache() {
        resolveCache.clear()
        globalConfSnapshot = null
    }

    fun resolve(container: ContainerData): Resolved {
        val cacheKey = "${container.name}|${container.wine.trim()}|${container.backend.id}"
        resolveCache[cacheKey]?.let { cached ->
            if (cached.wineDir.isDirectory && cached.wineBinary.isFile) {
                return cached
            }
            resolveCache.remove(cacheKey)
        }
        return resolveUncached(container).also { resolveCache[cacheKey] = it }
    }

    private fun resolveUncached(container: ContainerData): Resolved {
        // ---- 1) wine 目录（容器 wine= → 全局 default_wine= → /usr/opt/wine） ----
        val wineDir = resolveWineDir(container)
            ?: throw IllegalStateException(buildString {
                append("未找到 wine：")
                val w = container.wine.trim()
                if (w.isNotEmpty()) {
                    append("容器 wine=『$w』不存在（/usr/opt/$w 或绝对路径无效）")
                } else {
                    val g = readGlobalConfKey("default_wine")
                    if (g.isNotBlank()) append("全局 default_wine=『$g』不存在且无主槽位")
                    else append("主槽位 /usr/opt/wine 不存在")
                }
                append("。请先安装（App 端 Wine 选择器一键安装，或终端 linbox-container install-wine）")
            })
        // v3.2.0：loader 布局自愈先行 —— 悬空 bin/wine 的 10.17+ 原始包
        // 过不了下方文件检查；物化副本包虽能过但必炸 ntdll。两种都在
        // 此就地治好（幂等；启动链 1.5 步会再跑一次并打日志，此处静默）。
        runCatching { WineLayoutRepair.ensure(wineDir) }
        // v3.2.3：futex_waitv 探测改写（proton 旧包 SIGSYS(159) 静默阵亡
        // 的设备侧根治，免重编）—— 同样幂等 + 静默，1.5 步打日志。
        runCatching { WineProbePatch.apply(wineDir) }
        val wineBinary = File(wineDir, "bin/wine")
        if (!wineBinary.isFile) {
            throw IllegalStateException("wine 主程序缺失：${wineBinary.absolutePath}")
        }

        // ---- 2) ELF 形态探测 ----
        val arch = detectElfArch(wineBinary)

        // ---- 3) 后端选择 ----
        val backend = pickBackend(container, arch, wineBinary)

        return Resolved(
            wineDir = wineDir,
            arch = arch,
            backend = backend,
            wineBinary = wineBinary,
            wineServerBin = File(wineDir, "bin/wineserver"),
            wineBootBin = File(wineDir, "bin/wineboot")
        )
    }

    /**
     * 解析 wine 安装目录。容器 `wine=` 支持目录名（/usr/opt/<名>）或
     * 绝对路径；空则全局 default_wine（同样兼容目录名与绝对路径 ——
     * 比脚本更宽容，CLI 写入的槽位名行为不变），再空则主槽位 /usr/opt/wine。
     *
     * v3.1 容错：default_wine 指向的槽位失效（被卸载/改名）时回退主槽位
     * wine（存在才回退）—— 与 CLI 的兑底语义一致，避免“改了个名就全
     * 容器起不来”。容器显式 wine= 无效时仍报错（用户明确指定的东西
     * 缺失就该报，静默换目标会跑错版本）。
     */
    private fun resolveWineDir(container: ContainerData): File? {
        val explicit = container.wine.trim()
        if (explicit.isNotEmpty()) {
            val dir = if (explicit.startsWith("/")) File(explicit)
                      else File(WINE_OPT, explicit)
            return dir.takeIf { it.isDirectory }
        }
        val global = readGlobalConfKey("default_wine")
        if (global.isBlank()) {
            return File(WINE_OPT, "wine").takeIf { it.isDirectory }
                // v3.2.24（用户问题 3）：主槽位缺失时扫描全部 wine* 槽位
                // 取第一个（listWines 同源逻辑：目录名 wine 开头且含
                // bin/wine）—— rootfs/usr/opt 里装了什么就真正用什么，
                // 不再"只认死名 /usr/opt/wine，装了 wine-9.7 也说没装"。
                // v3.2.26（用户要求）：剔除 wine_path.conf 的 WINE_PATH
                // 解析 —— wine 目录一律由 LinBox 槽位体系（容器 wine= /
                // default_wine / 槽位扫描）管理，不读安装包内声明。
                ?: ContainerManager.listWines().firstOrNull()?.let { File(it.dir) }
        }
        val globalDir = if (global.startsWith("/")) File(global)
                        else File(WINE_OPT, global)
        if (globalDir.isDirectory) return globalDir
        // default_wine 失效 → 回退主槽位（存在才回退，否则返回原目标
        // 让上层报出具体槽位名）
        val main = File(WINE_OPT, "wine")
        if (main.isDirectory) return main
        return globalDir
    }

    /** 全局 container.conf 快照（mtime 键；v3.3.40【性能】：resolve×3 不再各读全文件一遍）。 */
    @Volatile
    internal var globalConfSnapshot: Pair<Long, List<String>>? = null

    /** 读全局 container.conf 的指定键（与 CLI conf_read 同源）。 */
    private fun readGlobalConfKey(key: String): String {
        val f = File(ContainerManager.GLOBAL_CONF)
        if (!f.isFile) return ""
        val mtime = runCatching { f.lastModified() }.getOrDefault(0L)
        val lines = globalConfSnapshot?.takeIf { it.first == mtime }?.second
            ?: runCatching { f.readLines() }.getOrDefault(emptyList())
                .also { globalConfSnapshot = mtime to it }
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("$key=")) return line.removePrefix("$key=").trim()
        }
        return ""
    }

    /**
     * 读 ELF 头 e_machine（偏移 18，2 字节小端）。
     * 对齐脚本 detect_wine_arch：b7 00 = aarch64；3e 00 = x86_64。
     */
    fun detectElfArch(file: File): Arch {
        if (!file.isFile) return Arch.UNKNOWN
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 20) return Arch.UNKNOWN
                raf.seek(18)
                val b0 = raf.read()
                val b1 = raf.read()
                when {
                    b0 == 0xB7 && b1 == 0x00 -> Arch.AARCH64
                    b0 == 0x3E && b1 == 0x00 -> Arch.X86_64
                    else -> Arch.UNKNOWN
                }
            }
        } catch (_: Exception) {
            Arch.UNKNOWN
        }
    }

    /** 设备架构（aarch64 / x86_64）。 */
    private val deviceArch: String =
        System.getProperty("os.arch", "aarch64").lowercase()

    /**
     * 后端选择（pick_backend + auto_backend 合并移植）。
     *
     * 显式 backend= 时逐项校验组件就位；auto 时按设备架构 + wine ELF
     * 形态 + 组件可用性推导：aarch64 设备 + aarch64 wine → arm64ec，
     * x86_64 wine → box64（有 box64 时）；兜底 hangover 旧路径。
     */
    private fun pickBackend(
        container: ContainerData,
        arch: Arch,
        wineBinary: File
    ): ContainerBackend {
        var backend = container.backend
        if (backend == ContainerBackend.AUTO) {
            val auto = autoBackend(arch)
            if (auto == null) {
                throw IllegalStateException(
                    "没有可用后端：既无 x86_64 wine + box64，也无 arm64ec/hangover wine。" +
                        "请先安装 wine 与 box64（App 端安装器或 linbox-container doctor）"
                )
            }
            backend = auto
        }
        when (backend) {
            ContainerBackend.BOX64 -> {
                if (locateBox64() == null) {
                    throw IllegalStateException("box64 未安装（linbox-container doctor 查看详情）")
                }
                if (!wineBinary.isFile) {
                    throw IllegalStateException("x86_64 wine 未安装：请先安装 wine")
                }
                if (arch == Arch.AARCH64) {
                    throw IllegalStateException(
                        "所选 wine 是 aarch64/arm64ec 形态，box64 后端不适用——backend 改 arm64ec 或 auto"
                    )
                }
            }
            ContainerBackend.ARM64EC -> {
                if (!wineBinary.isFile) {
                    throw IllegalStateException("arm64ec wine 未安装：请安装 bionic-arm64ec 构建")
                }
                if (arch != Arch.AARCH64) {
                    throw IllegalStateException(
                        "所选 wine 不是 aarch64 形态（当前: ${arch.id}）——arm64ec 后端需要 bionic-arm64ec 构建"
                    )
                }
            }
            ContainerBackend.HANGOVER -> {
                if (!HANGOVER_BIN.isFile) {
                    throw IllegalStateException(
                        "hangover wine 不在 ${HANGOVER_BIN.absolutePath}——" +
                            "建议安装 CI 构建的新版 wine（bionic-arm64ec）"
                    )
                }
            }
            ContainerBackend.FEXCORE -> {
                if (!FEX_BIN.isFile) {
                    throw IllegalStateException("FEXInterpreter 不在 ${FEX_BIN.absolutePath}（FEX-Emu 未安装）")
                }
                if (!wineBinary.isFile) {
                    throw IllegalStateException("x86_64 wine 未安装：请先安装 wine")
                }
            }
            ContainerBackend.NATIVE -> {
                if (!wineBinary.isFile) {
                    throw IllegalStateException("x86_64 wine 未安装")
                }
            }
            ContainerBackend.AUTO -> {
                // 上面 auto 分支已处理，不会走到
            }
        }
        return backend
    }

    private fun autoBackend(arch: Arch): ContainerBackend? {
        return when (deviceArch) {
            "x86_64", "amd64" -> {
                // 必须校验 wine ELF 形态：x86_64 设备上若误装了 arm64ec
                // 构建，直接 exec aarch64 二进制会得到 "Exec format error"
                if (arch == Arch.X86_64 &&
                    (File("$WINE_OPT/wine/bin/wine").isFile || File(WINE_OPT).isDirectory)
                ) ContainerBackend.NATIVE else null
            }
            else -> {
                // aarch64 / arm64 设备
                // （resolve() 已保证 wineDir 存在，此处 wineOk 恒真，
                // 保留检查仅为防御性冗余）
                val wineOk = File("$WINE_OPT/wine/bin/wine").isFile || File(WINE_OPT).isDirectory
                if (wineOk) {
                    when (arch) {
                        Arch.AARCH64 -> ContainerBackend.ARM64EC
                        Arch.X86_64 -> if (locateBox64() != null) ContainerBackend.BOX64 else null
                        Arch.UNKNOWN -> null
                    }
                } else if (HANGOVER_BIN.isFile) {
                    ContainerBackend.HANGOVER
                } else null
            }
        }
    }

    // ------------------------------------------------------------------
    // 命令构建（wine_run / run_wine 的 exec 目标矩阵，Kotlin 化）
    // ------------------------------------------------------------------

    /**
     * 按后端包装「宿主二进制」argv 前缀。
     *
     * - box64   → [box64, <binary>, ...]
     * - fexcore → [FEXInterpreter, <binary>, ...]
     * - native / arm64ec / hangover → [<binary>, ...]（hangover 用自己的 aarch64 路径）
     *
     * wineserver / wineboot 是 Unix 宿主程序，不走 wine 主程序包装，
     * 但 x86_64 形态仍需经 box64/FEX 执行 —— 与脚本 run_wine 的特判一致。
     */
    fun hostCommand(resolved: Resolved, binary: File): List<String> {
        return when (resolved.backend) {
            ContainerBackend.BOX64 -> {
                val box64 = locateBox64()
                    ?: throw IllegalStateException("box64 未安装")
                listOf(box64.absolutePath, binary.absolutePath)
            }
            ContainerBackend.FEXCORE -> listOf(FEX_BIN.absolutePath, binary.absolutePath)
            else -> listOf(binary.absolutePath)
        }
    }

    /** 主 wine 命令前缀（run_wine 的一般分支）。 */
    fun wineCommand(resolved: Resolved): List<String> = when (resolved.backend) {
        ContainerBackend.BOX64 -> {
            val box64 = locateBox64()
                ?: throw IllegalStateException("box64 未安装")
            listOf(box64.absolutePath, resolved.wineBinary.absolutePath)
        }
        ContainerBackend.FEXCORE -> listOf(FEX_BIN.absolutePath, resolved.wineBinary.absolutePath)
        ContainerBackend.HANGOVER -> listOf(HANGOVER_BIN.absolutePath)
        else -> listOf(resolved.wineBinary.absolutePath)
    }

    /** wineserver / wineboot 宿主二进制（依后端取对应目录）。 */
    fun hostToolBin(resolved: Resolved, tool: String): File {
        val dir = if (resolved.backend == ContainerBackend.HANGOVER) {
            HANGOVER_BIN.parentFile ?: File("$RFS_USR/opt/bin")
        } else {
            resolved.wineBinary.parentFile ?: File(resolved.wineDir, "bin")
        }
        return File(dir, tool)
    }
}
