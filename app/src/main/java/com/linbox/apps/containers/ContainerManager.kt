package com.linbox.apps.containers

import java.io.File

/**
 * LinBox Wine 容器 — 管理器（App 侧）。
 *
 * 直接读写 rootfs 内的 container.conf / 目录结构，与终端里的
 * linbox-container CLI 共享同一份数据：App 里新建的容器，
 * 终端 `linbox-container list` 立即可见，反之亦然。
 *
 * 路径契约（与 bionic-rootfs/default.conf、linbox-container 一致）：
 *   rootfs 根   : /data/data/com.linbox/files/rootfs
 *   App 前缀    : /data/data/com.linbox/files/usr
 *   容器根      : <rootfs>/home/.linbox/containers
 *   全局配置    : <rootfs>/usr/etc/linbox/container.conf（default_container=<name>）
 *   X11 socket  : <App 前缀>/tmp/.X11-unix/Xn（显示方案为 X11，无 Weston）
 */
object ContainerManager {

    // ------------------------------------------------------------------
    // 路径常量（与 rootfs 侧脚本逐字对齐）
    // ------------------------------------------------------------------

    /** bionic rootfs 根目录。 */
    const val ROOTFS_ROOT = "/data/data/com.linbox/files/rootfs"

    /** App 自身前缀（termux-x11 X server、bash 所在）。 */
    const val APP_PREFIX = "/data/data/com.linbox/files/usr"

    /** 容器存储根目录。 */
    const val CONTAINERS_ROOT = "$ROOTFS_ROOT/home/.linbox/containers"

    /** 全局容器配置（default_container=...）。 */
    const val GLOBAL_CONF = "$ROOTFS_ROOT/usr/etc/linbox/container.conf"

    /** rootfs 侧容器管理 CLI（Kotlin 端经 bash 调用完成运行/安装类操作）。 */
    const val CLI = "$ROOTFS_ROOT/usr/bin/linbox-container"

    /** rootfs 侧 Wine 启动器。 */
    const val LAUNCHER = "$ROOTFS_ROOT/usr/bin/linbox-wine"

    /** App 侧 X11 socket 目录（显示方案为 X11）。 */
    const val X11_SOCK_DIR = "$APP_PREFIX/tmp/.X11-unix"

    /** rootfs 内 wine 多版本安装根（/usr/opt/，槽位 wine / wine-<名>）。 */
    const val WINE_OPT_DIR = "$ROOTFS_ROOT/usr/opt"

    /**
     * 容器名约束（v3.3.4，用户问题 3："创建容器竟然不支持中文文本名"）：
     * 任意 Unicode 字母/数字开头（含中文），后续字母/数字/点/下划线/连字符。
     * \p{L}（字母）覆盖 CJK 统一表意文字，\p{N}（数字）覆盖全角数字 ——
     * "游戏测试"、"856"、"wine9.2-x64" 均合法；路径穿越字符
     * （/ \ : 空格 等）依旧被拒。
     */
    private val NAME_RE = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,63}$")

    // ------------------------------------------------------------------
    // 容器枚举与读取
    // ------------------------------------------------------------------

    /** 列出全部容器（按名称排序；跳过 logs 等非容器目录）。 */
    fun list(): List<ContainerData> {
        val root = File(CONTAINERS_ROOT)
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory && f.name != "logs" }
            .orEmpty()
            .mapNotNull { ContainerData.fromConf(File(it, "container.conf")) }
            .sortedBy { it.name }
    }

    /** 按名取容器；不存在返回 null。 */
    fun get(name: String): ContainerData? =
        ContainerData.fromConf(File(File(CONTAINERS_ROOT, name), "container.conf"))

    /** 容器是否存在（目录 + conf 双条件）。 */
    fun exists(name: String): Boolean =
        get(name) != null

    /** 容器名合法性校验，返回 null 表示合法，否则返回错误信息。 */
    fun validateName(name: String): String? = when {
        name.isBlank() -> "容器名不能为空"
        !NAME_RE.matches(name) -> "容器名仅允许字母/数字/中文/点/下划线/连字符（字母、数字或中文开头，最长 64 字符）"
        exists(name) -> "同名容器已存在"
        else -> null
    }

    // ------------------------------------------------------------------
    // 容器创建 / 删除 / 克隆 / 更新
    // ------------------------------------------------------------------

    /**
     * 新建容器：目录 + WINEPREFIX + container.conf（与 CLI create 等效）。
     * @return null 成功；否则错误信息。
     */
    fun create(data: ContainerData): String? {
        validateName(data.name)?.let { return it }
        val dir = File(CONTAINERS_ROOT, data.name)
        return try {
            File(dir, "prefix").mkdirs()   // WINEPREFIX
            File(dir, "drive_c").mkdirs()  // 兼容旧布局占位
            File(dir, "container.conf").writeText(data.toConfText())
            null
        } catch (e: Exception) {
            "创建失败：${e.message}"
        }
    }

    /**
     * 删除容器（整目录递归删除）。@return null 成功；否则错误信息。
     *
     * v3.2.11（用户问题 1）：删除路径双重防御。历史缺陷链：conf 的 name=
     * 若为空值 → 空名容器 → File(CONTAINERS_ROOT, "") 解析为容器根本身 →
     * deleteRecursively() 把全部容器连根删光。fromConf 已改为目录名优先
     * 消除空名源头；这里再拒一切非法名并校验目标必须是容器根的
     * **直接子目录**（canonical 路径双保险，含 "."/".."/分隔符/符号链接逃逸）。
     *
     * v3.2.26（数据丢失事故根治）：改用 SafeFileOps.deleteTree ——
     * Kotlin 1.9.25 的 deleteRecursively 会穿透符号链接删除目标内容
     * （本机实证复现），而 v3.2.24 起前缀 dosdevices 里有 d:→Download、
     * e:→exagear、z:→/ 三个指向 rootfs 之外的链接，删容器即穿越链接
     * 删用户数据（用户实测 Download/exagear 被删光）。deleteTree 不带
     * FOLLOW_LINKS，符号链接只删本身不删目标。
     */
    fun remove(name: String): String? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') ||
            name == "." || name == "..")
            return "拒绝删除：容器名非法（\"$name\"）"
        val dir = File(CONTAINERS_ROOT, name)
        val rootCanonical = runCatching { File(CONTAINERS_ROOT).canonicalPath }
            .getOrDefault("")
        val dirCanonical = runCatching { dir.canonicalPath }.getOrDefault("")
        if (dirCanonical.isBlank() || dirCanonical == rootCanonical ||
            File(dirCanonical).parentFile?.canonicalPath != rootCanonical)
            return "拒绝删除：\"$name\" 不是容器根下的直接子目录（路径越界防御）"
        if (!dir.isDirectory) return "容器不存在：$name"
        return try {
            // 先尝试终止容器内 wine 进程，避免文件占用
            WineSessionLauncher.stopContainerQuietly(name)
            if (!com.linbox.util.SafeFileOps.deleteTree(dir))
                return "删除未完成（部分文件被占用/权限不足，可重试）"
            // v3.3.6：清理本容器的历史 noexec 执行区残留（rootfs/
            // home/.linbox/games/<容器>，v3.2.23~v3.3.4 整目录导入布局）
            // —— 它在容器目录之外，不随删容器走，不清就是白吃内部存储的
            // 孤儿副本（v3.3.6 起零拷贝直读不再产生任何执行区；v3.3.5 的
            // <容器>/games/ 镜像区本就在容器目录内，随 deleteTree 一并清除）。
            runCatching {
                val legacy = File(File(ContainerManager.ROOTFS_ROOT, "home/.linbox/games"), name)
                if (legacy.exists()) com.linbox.util.SafeFileOps.deleteTree(legacy)
            }
            if (defaultName() == name) setDefault("")
            null
        } catch (e: Exception) {
            "删除失败：${e.message}"
        }
    }

    /**
     * 克隆容器（整目录复制 + 改写 conf 内 name=）。
     *
     * v3.2.26：改用 SafeFileOps.copyTree —— copyRecursively 同样会穿透
     * 符号链接把 dosdevices d:/e:/z: 指向的外部目录（Download/exagear/…）
     * 整个深拷进新容器；copyTree 遇链接重建同指向链接，不穿越。
     * @return null 成功；否则错误信息。
     */
    fun clone(src: String, dst: String): String? {
        validateName(dst)?.let { return it }
        val srcDir = File(CONTAINERS_ROOT, src)
        if (!srcDir.isDirectory) return "源容器不存在：$src"
        return try {
            val dstDir = File(CONTAINERS_ROOT, dst)
            // v3.3.6：排除 games/（v3.3.5 遗留的 PE 镜像执行区 —— 派生
            // 数据，v3.3.6 起零拷贝直读不再产生；旧容器里的遗留目录也不
            // 值得复制进克隆，首次启动会自动清掉）。
            if (!com.linbox.util.SafeFileOps.copyTree(srcDir, dstDir, excludeTopLevel = setOf("games")))
                return "克隆未完成（部分文件复制失败，可删除后重试）"
            val conf = File(dstDir, "container.conf")
            ContainerData.fromConf(conf)?.let { conf.writeText(it.copy(name = dst).toConfText()) }
            null
        } catch (e: Exception) {
            "克隆失败：${e.message}"
        }
    }

    /** 保存容器配置（保留原 created 时间戳）。@return null 成功；否则错误信息。 */
    fun update(data: ContainerData): String? {
        val conf = File(File(CONTAINERS_ROOT, data.name), "container.conf")
        if (!conf.isFile) return "容器不存在：${data.name}"
        return try {
            val created = ContainerData.fromConf(conf)?.created ?: data.created
            conf.writeText(data.copy(created = created).toConfText())
            null
        } catch (e: Exception) {
            "保存失败：${e.message}"
        }
    }

    /** 修改单个/多个键（k=v 语义，与 CLI set 等效）。@return null 成功；否则错误信息。 */
    fun setKeys(name: String, keys: Map<String, String>): String? {
        val data = get(name) ?: return "容器不存在：$name"
        var d = data
        keys["backend"]?.let { d = d.copy(backend = ContainerBackend.fromId(it)) }
        keys["preset"]?.let { d = d.copy(preset = Box64Preset.fromId(it)) }
        keys["screen"]?.let { d = d.copy(screen = it) }
        keys["dmode"]?.let { d = d.copy(dmode = DisplayMode.fromId(it)) }
        keys["gallium"]?.let { d = d.copy(gallium = it) }
        keys["env"]?.let { d = d.copy(env = it) }
        keys["wine"]?.let { d = d.copy(wine = it) }
        keys["lang"]?.let { d = d.copy(lang = it) }
        keys["audio"]?.let { d = d.copy(audio = ContainerAudio.fromId(it)) }
        return update(d)
    }

    /**
     * v3.2.18（wineguanli.py rename_container）：重命名容器 = 目录改名 +
     * conf 内 name= 归一。运行中的容器先停止。
     * @return null 成功；否则错误信息。
     */
    fun rename(old: String, new: String): String? {
        validateName(new)?.let { return it }
        if (old == new) return null
        val oldDir = File(CONTAINERS_ROOT, old)
        if (!oldDir.isDirectory) return "容器不存在：$old"
        val newDir = File(CONTAINERS_ROOT, new)
        if (newDir.exists()) return "同名容器已存在：$new"
        return try {
            WineSessionLauncher.stopContainerQuietly(old)
            if (!oldDir.renameTo(newDir)) return "重命名失败（IO 错误）"
            // v3.3.6：旧版导入区同步改名（仅剩存量清理意义；v3.3.6 起
            // 零拷贝直读，新容器不再有任何执行区）。
            runCatching {
                val legacyRoot = File(ContainerManager.ROOTFS_ROOT, "home/.linbox/games")
                val legacyOld = File(legacyRoot, old)
                if (legacyOld.isDirectory) legacyOld.renameTo(File(legacyRoot, new))
            }
            val conf = File(newDir, "container.conf")
            ContainerData.fromConf(conf)?.let { conf.writeText(it.copy(name = new).toConfText()) }
            if (defaultName() == old) setDefault(new)
            null
        } catch (e: Exception) {
            "重命名失败：${e.message}"
        }
    }

    // ------------------------------------------------------------------
    // Wine 多版本枚举（v2.26：/usr/opt/wine* 槽位，App 端选择器数据源）
    // ------------------------------------------------------------------

    /**
     * 扫描 rootfs 内已安装的 wine 版本（/usr/opt/ 下含 bin/wine 的目录）。
     *
     * 命名约定与 linbox-container install-wine 对齐：
     *   wine        主槽位（旧版兼容，无 default_wine 指针时的兑底）
     *   wine-<名>   多版本槽位（--name 指定）
     * 每个槽位的 .linbox-wine-info 记录 name/version/installed。
     */
    fun listWines(): List<WineInstall> {
        val opt = File(WINE_OPT_DIR)
        if (!opt.isDirectory) return emptyList()
        return opt.listFiles { f ->
            f.isDirectory && f.name.startsWith("wine") && File(f, "bin/wine").isFile
        }.orEmpty()
            .map { dir ->
                // v3.2.24（用户问题 3）：真正的读取 rootfs/opt 槽位信息 ——
                // CI 构建脚本（wine/hangover-wine 配方 pre_package）实际写入
                // .linbox-wine-info 的键是 kind= / tag= / backend=，而旧代码
                // 只读 version= / installed=（从未有人写入 → 选择器里版本
                // 恒空白）。现在按 version→tag→kind 回退链取版本展示，
                // kind（x86_64-wow64-x11 / arm64ec-…）作为形态说明一并读出。
                WineInstall(
                    name = dir.name,
                    dir = dir.absolutePath,
                    version = readWineInfo(dir, "version")
                        .ifEmpty { readWineInfo(dir, "tag") }
                        .ifEmpty { readWineInfo(dir, "kind") },
                    installed = readWineInfo(dir, "installed"),
                    kind = readWineInfo(dir, "kind")
                )
            }
            .sortedBy { it.name }
    }

    /** 全局默认 wine 槽位名（default_wine=；空 = 用主槽位 wine）。 */
    fun defaultWineName(): String {
        val f = File(GLOBAL_CONF)
        if (!f.isFile) return ""
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("default_wine=")) {
                return line.removePrefix("default_wine=").trim()
            }
        }
        return ""
    }

    private fun readWineInfo(dir: File, key: String): String {
        val f = File(dir, ".linbox-wine-info")
        if (!f.isFile) return ""
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("$key=")) return line.removePrefix("$key=").trim()
        }
        return ""
    }

    // ------------------------------------------------------------------
    // 默认容器（全局配置 default_container=<name>）
    // ------------------------------------------------------------------

    /** 读取默认容器名（无则空串）。 */
    fun defaultName(): String {
        val f = File(GLOBAL_CONF)
        if (!f.isFile) return ""
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("default_container=")) {
                return line.removePrefix("default_container=").trim()
            }
        }
        return ""
    }

    /** 设置默认容器（写全局 conf；空串清除）。@return null 成功；否则错误信息。 */
    fun setDefault(name: String): String? {
        if (name.isNotEmpty() && !exists(name)) return "容器不存在：$name"
        return try {
            val f = File(GLOBAL_CONF)
            f.parentFile?.mkdirs()
            val others = if (f.isFile) f.readLines().filter {
                val t = it.trim()
                t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("default_container=")
            } else emptyList()
            val body = others + if (name.isEmpty()) emptyList() else listOf("default_container=$name")
            f.writeText(
                buildString {
                    appendLine("# LinBox Wine 容器全局配置（linbox-container / App 端共用）")
                    if (body.isEmpty()) appendLine("# （暂无默认容器）")
                    body.forEach { appendLine(it) }
                }
            )
            null
        } catch (e: Exception) {
            "写入全局配置失败：${e.message}"
        }
    }

    /** 容器是否为默认容器。 */
    fun isDefault(name: String): Boolean =
        defaultName() == name

    // ------------------------------------------------------------------
    // 体检（与 CLI doctor 同源的本地快速检查，不做进程探测）
    // ------------------------------------------------------------------

    /**
     * App 端快速体检：X11 socket / rootfs / CLI / wine / box64 / FEX /
     * 容器数量。深度体检（音频、进程）用终端 `linbox-container doctor`。
     */
    fun doctor(): List<DoctorCheck> {
        val checks = mutableListOf<DoctorCheck>()

        // 1) rootfs 就位
        val rfs = File("$ROOTFS_ROOT/usr/bin")
        checks += DoctorCheck(
            ok = rfs.isDirectory,
            title = "bionic rootfs",
            detail = if (rfs.isDirectory) "$ROOTFS_ROOT/usr/bin 就绪"
                     else "未找到 $ROOTFS_ROOT/usr/bin，请先在 App 内导入 rootfs"
        )

        // 2) X11 socket（显示方案：X11）
        val sockDir = File(X11_SOCK_DIR)
        val sock = sockDir.listFiles { f -> f.name.startsWith("X") && f.name != "X0" }
        val hasSock = sock != null && sock.isNotEmpty()
        checks += DoctorCheck(
            ok = hasSock,
            title = "X11 服务（X11 socket）",
            detail = if (hasSock)
                    "检测到 ${X11_SOCK_DIR}/${sock.first().name}，DISPLAY=:${
                        sock.first().name.removePrefix("X")
                    }"
                else "未发现 X11 socket（$X11_SOCK_DIR/Xn）。请先打开 X11 桌面窗口或运行 linbox-x11 :1"
        )

        // 3) 容器 CLI（v2.26 起由 APK assets/linbox/scripts 启动时覆盖部署）
        val cli = File(CLI)
        checks += DoctorCheck(
            ok = cli.isFile && cli.canExecute(),
            title = "容器管理 CLI",
            detail = if (cli.isFile) "linbox-container / linbox-wine 已就绪（APK 内置脚本自动覆盖部署）"
                     else "脚本尚未部署（rootfs 未导入）。导入 rootfs 后打开一次主界面，APK 会自动覆盖安装"
        )

        // 4) wine 主程序（多版本：bionic 双形态 / 普通 Wine / Proton）
        //    v3.3.7：逐槽位补丁体检 —— noexec 回退（LinBox-noexec-fallback，
        //    arm64ec 原生执行在 /storage 上跑游戏的硬前提）与 locale 修复
        //    （LinBox-locale-lc-all，中文界面 + ACP=936 的硬前提）。存量
        //    旧包缺补丁时给出可照做的重装指引（启动链 3.5 步也会提示）。
        val wines = listWines()
        val wine = File("$ROOTFS_ROOT/usr/opt/wine/bin/wine")
        val patchInfo = wines.mapNotNull { w ->
            val st = com.linbox.apps.containers.launcher.WinePatchDetector.stateOf(File(w.dir))
            val missing = mutableListOf<String>()
            if (!st.noexecFallback) missing.add("noexec回退")
            if (!st.localeLcAll) missing.add("中文locale")
            if (missing.isEmpty()) null else "${w.name}（缺${missing.joinToString("+")}）"
        }
        checks += DoctorCheck(
            ok = wine.isFile || wines.isNotEmpty(),
            title = "Wine 运行时（多版本）",
            detail = when {
                wine.isFile && wines.size <= 1 -> "/usr/opt/wine 就绪"
                wines.isNotEmpty() -> buildString {
                    append("已装 ${wines.size} 个版本（${wines.joinToString("、") { it.name }}），默认：${defaultWineName().ifEmpty { "wine" }}")
                    if (patchInfo.isNotEmpty()) {
                        append("；⚠ 旧包缺补丁：")
                        append(patchInfo.joinToString("、"))
                        append("——请用新 CI（build-wine-bionic）重装对应形态，否则 arm64ec 在外部存储上报 media 错误/界面恒英文")
                    }
                }
                else -> "未安装 wine：终端 linbox-container install-wine <类别>（wine-catalog 查看 bionic/普通Wine/Proton 在线构建），或 App 端 Wine 选择器下方分类一键安装"
            }
        )

        // 5) box64
        val box64 = listOf(
            "$ROOTFS_ROOT/usr/local/bin/box64",
            "$ROOTFS_ROOT/usr/bin/box64",
            "$APP_PREFIX/bin/box64"
        ).map(::File).firstOrNull { it.isFile && it.canExecute() }
        // v3.2.13（用户问题 5）：文件存在 ≠ 能启动。旧体检只查 canExecute，
        // "构建根本不对、加载即死"的 box64 一样全绿（设备实测启动零日志）。
        // 现在真实 exec `box64 --version`（5 秒超时）拿运行结论：
        //  - 输出含版本串 → ✓（版本号进详情，与静态信息并列）；
        //  - 非零退出 / 无输出 / 超时 → ✗，退出码 + 前几行输出直接给用户，
        //    "box64 无法启动"从猜测变成可截图反馈的证据。
        var box64Detail = box64?.let { "box64 就绪：${it.absolutePath}" }
            ?: "未安装 box64（容器仍可用 hangover/fexcore/native 后端）"
        var box64Ok = box64 != null
        if (box64 != null) {
            try {
                val proc = ProcessBuilder(box64.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()
                // waitFor 限时（阻塞读不可取消，先等退出再读流）：
                // 5 秒不退出 = 挂死，按"无法运行"处理并强杀
                val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                val out = if (exited) {
                    runCatching { proc.inputStream.readBytes().decodeToString() }
                        .getOrDefault("")
                } else ""
                runCatching { proc.destroyForcibly() }
                val exit = if (exited) proc.exitValue() else -1
                val trimmed = out.trim().lineSequence().take(3).joinToString(" / ")
                if (exit == 0 && trimmed.contains("box64", ignoreCase = true)) {
                    box64Detail = "就绪且可运行：$trimmed（${box64.absolutePath}）"
                } else {
                    box64Ok = false
                    box64Detail = "文件存在但无法运行（" + (if (exited) "退出码 $exit" else "5 秒未退出（挂死）") +
                        (if (trimmed.isNotEmpty()) "，输出：$trimmed" else "，无任何输出 —— 动态链接失败或加载即崩") +
                        "）。多为构建/安装损坏：请重跑 build-box64-bionic 工作流并重装，" +
                        "终端执行 `${box64.absolutePath} --version` 与 `logcat | grep -i linker` 取证反馈"
                }
            } catch (e: Exception) {
                box64Ok = false
                box64Detail = "运行探活失败：${e.message ?: e.javaClass.simpleName}（${box64.absolutePath}）"
            }
        }
        checks += DoctorCheck(
            ok = box64Ok,
            title = "Box64 后端（运行探活）",
            detail = box64Detail
        )

        // 6) FEX（可选）
        val fex = File("$ROOTFS_ROOT/usr/opt/fex/bin/FEXInterpreter")
        checks += DoctorCheck(
            ok = fex.isFile,
            title = "FEXCore 后端（可选）",
            detail = if (fex.isFile) "FEXInterpreter 就绪"
                     else "未安装 FEX-Emu（bionic 无上游构建，可忽略）"
        )

        // 7) 容器数量
        val n = list().size
        checks += DoctorCheck(
            ok = n > 0,
            title = "容器",
            detail = if (n > 0) "共 $n 个容器，默认：${defaultName().ifEmpty { "未设置" }}"
                     else "尚无容器，先新建一个"
        )

        return checks
    }

    /** rootfs 是否已导入（多个入口共用的前置判断）。 */
    fun rootfsReady(): Boolean = File(CLI).isFile
}
