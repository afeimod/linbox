package com.linbox.apps.containers

import android.content.Context
import android.content.Intent
import android.os.Build
import com.linbox.apps.containers.launcher.NativeShellExecutor
import com.linbox.apps.containers.launcher.WineEnvironment
import com.linbox.apps.containers.launcher.WineRuntime
import com.linbox.apps.containers.launcher.JsonRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * v3.2.18（wineguanli.py 完全重制）：wineguanli.py 的"高级设置"数据与
 * 操作层移植（Qt 桌面端是全局 /opt/conf 目录下的 .conf 文件，LinBox 原生链路
 * 对应物是容器级 container.conf + rootfs /opt 生态目录）。
 *
 * 移植对应关系（逐条对应 wineguanli.py 类/方法）：
 *  - DynarecEditor.presets        → [DY_PRESETS]（兼容/性能/平衡三预设，
 *    9 个 BOX64_DYNAREC_* 变量逐字照搬）+ 容器 env= 合并落地
 *  - load_driver_versions 等      → [OptEcosystem.scan]（/opt/tz、/opt/zink、
 *    /opt/dxvk1、/opt/vkord3d、/opt/box、/opt/box86 的 tar 扫描）+
 *    [OptEcosystem.extract]（tar 解压进 rootfs）
 *  - install_from_file / url      → [WineOps.installFromTar] /
 *    [WineOps.download] + installFromTar
 *  - delete_wine_version          → [WineOps.deleteWine]
 *  - refresh_box_info             → [OptEcosystem.boxVersion]
 *  - force_d3d9/11 + apply_cnc    → [AppDefaults.forceD3d] / [AppDefaults.cncDdraw]
 *    （wineguanli 复制 DLL 到 exe 目录；LinBox 用 wine 原生
 *    HKCU\AppDefaults\<exe>\DllOverrides 注册表机制实现同一效果，
 *    不需要在用户文件里铺文件）
 *  - MangohudConfigDialog         → [MangoHud.buildConfig]
 *  - ShortcutDialog               → [Shortcuts.pinRunContainer]
 */
object GuanliSupport {

    // ------------------------------------------------------------------
    // DY 配置（wineguanli.py DynarecEditor.presets 逐字移植）
    // ------------------------------------------------------------------

    /** 一个 DY 预设 = 一组 BOX64_* 环境变量（顺序保持 wineguanli 原序）。 */
    val DY_PRESETS: LinkedHashMap<String, LinkedHashMap<String, String>> = linkedMapOf(
        "🔒 兼容模式" to linkedMapOf(
            // v3.3.21（用户实测）：SAFEFLAGS=2 在本机（Adreno 740 / Android 15，
            // box64 0.4.2-0.4.4）触发 ntdll 早期 SIGSEGV —— 与 wineguanli.py
            // 参考值不同，但设备实测优先，全档取 0
            "BOX64_DYNAREC_SAFEFLAGS" to "0",
            "BOX64_DYNAREC_BIGBLOCK" to "0",
            "BOX64_DYNAREC_CALLRET" to "0",
            "BOX64_DYNAREC_WAIT" to "1",
            "BOX64_DYNAREC_FASTNAN" to "1",
            "BOX64_DYNAREC_X87DOUBLE" to "1",
            "BOX64_DYNAREC_WEAKBARRIER" to "0",
            "BOX64_AVX" to "0",
            "BOX64_DYNAREC_FORWARD" to "256"
        ),
        "🚀 性能模式" to linkedMapOf(
            "BOX64_DYNAREC_SAFEFLAGS" to "0",
            "BOX64_DYNAREC_BIGBLOCK" to "3",
            "BOX64_DYNAREC_CALLRET" to "1",
            "BOX64_DYNAREC_WAIT" to "1",
            "BOX64_DYNAREC_FASTNAN" to "1",
            "BOX64_DYNAREC_X87DOUBLE" to "0",
            "BOX64_DYNAREC_WEAKBARRIER" to "1",
            "BOX64_AVX" to "2",
            "BOX64_DYNAREC_FORWARD" to "1024"
        ),
        "⚖️ 平衡模式" to linkedMapOf(
            // v3.3.21（用户实测）：同上，SAFEFLAGS=1 亦崩，取 0
            "BOX64_DYNAREC_SAFEFLAGS" to "0",
            "BOX64_DYNAREC_BIGBLOCK" to "1",
            "BOX64_DYNAREC_CALLRET" to "0",
            "BOX64_DYNAREC_WAIT" to "1",
            "BOX64_DYNAREC_FASTNAN" to "1",
            "BOX64_DYNAREC_X87DOUBLE" to "0",
            "BOX64_DYNAREC_WEAKBARRIER" to "1",
            "BOX64_AVX" to "0",
            "BOX64_DYNAREC_FORWARD" to "512"
        )
    )

    // ------------------------------------------------------------------
    // 容器 env= 的 K=V 列表化读写（DY 配置 / 环境变量两视图共用同一存储）
    // ------------------------------------------------------------------

    /**
     * env=（空格分隔 K=V）→ 有序列表。
     * 值带空格时用引号包裹（与 linbox-wine 的解析规则一致：K="V V"）。
     */
    fun parseEnv(env: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val re = Regex("([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|\\S+)")
        re.findAll(env).forEach { m ->
            val v = m.groupValues[2].removeSurrounding("\"")
            out.add(m.groupValues[1] to v)
        }
        return out
    }

    /** 列表 → env= 串（值含空白时加引号）。 */
    fun serializeEnv(pairs: List<Pair<String, String>>): String =
        pairs.joinToString(" ") { (k, v) ->
            if (v.contains(' ')) "$k=\"$v\"" else "$k=$v"
        }

    /**
     * 把一组变量合并进容器 env=（同名替换、其余保留、顺序稳定）。
     * wineguanli 的 DY/环境变量配置写 /opt/conf/env.conf（全局）；
     * LinBox 的等价物是容器级 env=（applyExtraEnv 在启动链最后叠加，
     * 可覆盖一切预设）。
     */
    fun mergeEnv(current: String, vars: Map<String, String>): String {
        val list = parseEnv(current).toMutableList()
        vars.forEach { (k, v) ->
            val idx = list.indexOfFirst { it.first == k }
            if (idx >= 0) list[idx] = k to v else list.add(k to v)
        }
        return serializeEnv(list)
    }

    /** 从容器 env= 读单个变量（无则 null）。 */
    fun envValue(env: String, key: String): String? =
        parseEnv(env).firstOrNull { it.first == key }?.second

    /**
     * v3.3.4（用户问题 1）：更多设置·环境变量页管理的键集 —— 与
     * wineguanli.py AdvancedSettingsDialog.get_settings 的 env 集一致。
     * 注意 MESA 两个键是 *_OVERRIDE（Mesa 官方变量名）—— 旧版误用
     * MESA_GL_VERSION / MESA_GLSL_VERSION（Mesa 根本不读，"怎么改都没
     * 反应"的直接根因之一），保存时一并从容器 env= 清除。
     */
    val ENV_TAB_KEYS: List<String> = listOf(
        "MESA_GL_VERSION_OVERRIDE", "MESA_GLSL_VERSION_OVERRIDE", "VKD3D_FEATURE_LEVEL",
        "MESA_EXTENSION_OVERRIDE", "MANGOHUD", "MANGOHUD_CONFIG", "DXVK_HUD", "TU_DEBUG",
        // v3.3.10：Wrapper 驱动切换键（VK_ICD_FILENAMES；VK_DRIVER_FILES 是
        // 新 loader 的别名，一并开放给高级用户）
        "VK_ICD_FILENAMES", "VK_DRIVER_FILES"
    )

    /** 旧版误写的 MESA 键名（保存时清理，防止残留覆盖）。 */
    val ENV_TAB_LEGACY_KEYS: List<String> = listOf("MESA_GL_VERSION", "MESA_GLSL_VERSION")

    /** 从 env= 串里剔除一组键（返回清理后的串）。 */
    fun removeEnvKeys(env: String, keys: Collection<String>): String =
        serializeEnv(parseEnv(env).filter { it.first !in keys })

    // ------------------------------------------------------------------
    // /opt 生态（wineguanli.py load_driver_versions / load_box_versions）
    // ------------------------------------------------------------------

    /** rootfs 内 /opt（wineguanli 的 /opt 在 rootfs 里）。 */
    val OPT_ROOT = "${ContainerManager.ROOTFS_ROOT}/opt"

    /** 驱动/box tar 包条目（display = 去掉 tar 后缀的文件名）。 */
    data class OptTar(val display: String, val fileName: String, val path: String)

    /**
     * 扫描 /opt/<sub> 下的 .tar.gz / .tar.xz / .tar.bz2（wineguanli
     * load_driver_versions 同规则：显示名去后缀，按名排序）。
     * 目录不存在返回空表（UI 显示"默认/不使用"两项 + 提示）。
     */
    // v3.3.40【性能】：scanOpt 结果 10s TTL 缓存 —— 一次 freshSession 启动
    // 链 + 图形驱动页装载会各扫 7 个 /opt 子目录（zink/dxvk1/vkord3d/
    // fexcore/wrapper/tz/yuzu），UI 页每次进tab又扫；目录 listFiles 在大
    // 目录上不便宜。缓存后同进程内重复扫描归零；10s 过期保证新装 tar 包
    // 仍能被及时发现。
    private val scanOptCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<OptTar>>>()

    fun scanOpt(sub: String): List<OptTar> {
        val now = System.currentTimeMillis()
        scanOptCache[sub]?.let { (t, cached) ->
            if (now - t < 10_000L) return cached
        }
        val dir = File(OPT_ROOT, sub)
        val out = if (!dir.isDirectory) emptyList() else
            dir.listFiles { f ->
                f.isFile && (f.name.endsWith(".tar.gz") || f.name.endsWith(".tar.xz") || f.name.endsWith(".tar.bz2"))
            }.orEmpty()
                .map { f ->
                    val disp = f.name.replace(Regex("\\.tar\\.(gz|xz|bz2)$"), "")
                    OptTar(disp, f.name, f.absolutePath)
                }
                .sortedBy { it.display }
        scanOptCache[sub] = now to out
        return out
    }

    /**
     * v3.3.18（用户需求："选择 wrapper 时同时更改 conf 里的 path.conf，
     * 选择 turnip 时也要改，三个选项都要改路径"）：把 /opt 生态
     * conf/path.conf 的 VK_ICD_FILENAMES 行改写为指定 ICD 路径
     * （wineguanli.py 参考行为 —— 图形驱动页的驱动选择直接落到 conf，
     * conf 与容器选择永远一致）。
     *
     *  - 写入目标 = 当前【激活的 /opt 生态根】（与 WineEnvironment.optRoot
     *    同序探测，rootfs/opt 优先、其次 wine 槽位内同名布局）；无任何生态
     *    时新建 rootfs/opt/conf/path.conf（此前无生态可被旁路，安全）；
     *  - 已有行（含 # 注释形态）【原位替换】，绝不重写整个文件 —— conf 的
     *    其余内容（LD_LIBRARY_PATH / LIBGL_DRIVERS_PATH / BOX64_* 等）
     *    一字不动；
     *  - 无该行时插到 mesa 配置段（LIBGL_DRIVERS_PATH / #mesa 标记后），
     *    再无则文末追加；
     *  - icdPath 为 null = 注释掉该行（Vulkan loader 走默认搜索，同样命中
     *    rootfs 内置 Mesa Turnip）。
     *
     * 三个选项的映射（GraphicsDriverTab 选择时 + RootfsLauncher 6a 激活时
     * 双写）：
     *  - Turnip（Mesa 内置）→ rootfs 自带 freedreno ICD；
     *  - Wrapper 驱动 → 独立驱动库 drivers/&lt;slot&gt;/icd.json；
     *  - Turnip 驱动包（/opt/tz）→ 同独立驱动库 icd.json。
     *
     * @return null 成功（或安全跳过）；否则用户可读错误
     */
    fun syncPathConfVkIcd(
        icdPath: String?,
        wineDir: File? = null,
        log: (String) -> Unit = {}
    ): String? = runCatching {
        // VK_ICD_FILENAMES 行的改写规则（原位替换 / 无行则按锚点插入）
        fun rewriteVk(lines: List<String>, newLine: String): List<String> {
            val vkLine = Regex("^\\s*#?\\s*export\\s+VK_ICD_FILENAMES\\s*=")
            val idx = lines.indexOfFirst { vkLine.containsMatchIn(it) }
            if (idx >= 0) return lines.toMutableList().also { it[idx] = newLine }
            val mut = lines.toMutableList()
            // 插入锚点：优先 LIBGL_DRIVERS_PATH 行后（VK 行与驱动路径行
            // 相邻，归组最自然）；无则 #mesa 段标记后；再无则文末追加
            val libGl = mut.indexOfFirst { it.contains("LIBGL_DRIVERS_PATH") }
            val mesa = mut.indexOfFirst { it.contains("#mesa") }
            val anchor = when {
                libGl >= 0 -> libGl
                mesa >= 0 -> mesa
                else -> -1
            }
            if (anchor >= 0) {
                mut.add(anchor + 1, newLine)
            } else {
                mut.add("")
                mut.add("# Vulkan ICD（v3.3.18 图形驱动页管理）")
                mut.add(newLine)
            }
            return mut
        }
        val newLine = if (icdPath.isNullOrBlank()) {
            "#export VK_ICD_FILENAMES=（v3.3.18 图形驱动页管理：当前未选驱动，Vulkan loader 默认搜索）"
        } else {
            "export VK_ICD_FILENAMES=$icdPath"
        }
        val root = WineEnvironment.optRoot(wineDir)
            ?: run {
                // 无 rootfs 侧生态：若任何 wine 槽位自带生态（本容器槽位已随
                // wineDir 探测过），不新建 rootfs/opt/conf —— 防把探测序 #1
                // 翻给自己、旁路该槽位生态；运行时激活链会对正确根写入。
                // v6.10（用户：“turnip 驱动包选择时没有更改 path.conf”）
                // 此前这里直接裸返回 —— 但 v3.3.40 起启动链 conf 只读 App
                // 内部那份（ApkConfStore.mergedConfMap），裸跳过会令快照里
                // VK_ICD_FILENAMES 一直是旧值。现补写 App 内部镜像（启动链
                // 生效）；/opt 侧仍由运行时激活链写正确根。
                if (ContainerManager.listWines().any { w ->
                        WineEnvironment.optRoot(File(w.dir)) != null
                    }
                ) {
                    log("path.conf 同步：/opt 生态位于 wine 槽位内 —— 补写 App 内部镜像（启动链 conf 源）")
                    runCatching {
                        com.linbox.apps.containers.launcher.ApkConfStore.ensure(null)
                        val apkPath =
                            com.linbox.apps.containers.launcher.ApkConfStore.confFile("path.conf")
                        apkPath.parentFile?.mkdirs()
                        val base = if (apkPath.isFile) apkPath.readLines() else emptyList()
                        val out = rewriteVk(base, newLine)
                        val outText = out.joinToString("\n") + "\n"
                        if (base.joinToString("\n") + "\n" != outText) {
                            val tmp = File(apkPath.parentFile, "path.conf.linbox-tmp")
                            tmp.writeText(outText)
                            if (!tmp.renameTo(apkPath)) {
                                tmp.copyTo(apkPath, overwrite = true)
                                tmp.delete()
                            }
                        }
                    }
                    return@runCatching null
                }
                File(ContainerManager.ROOTFS_ROOT, "opt")
            }
        val confDir = File(root, "conf")
        if (!confDir.isDirectory) confDir.mkdirs()
        val pathConf = File(confDir, "path.conf")
        val lines = if (pathConf.isFile) pathConf.readLines() else emptyList()
        val out = rewriteVk(lines, newLine)
        val outText = out.joinToString("\n") + "\n"
        // v3.3.40【性能】：内容无变化跳过写（旧版每次启动无条件重写
        // path.conf + 读回镜像 —— ICD 值稳定时纯写放大）。
        if (outText == lines.joinToString("\n") + "\n") {
            log("path.conf 已是目标状态（VK_ICD_FILENAMES 未变化，跳过重写）")
        } else {
            pathConf.writeText(outText)
        }
        log(
            "path.conf 已同步：VK_ICD_FILENAMES → " +
                (if (icdPath.isNullOrBlank()) "（注释：loader 默认搜索）" else icdPath) +
                "（${pathConf.absolutePath}）"
        )
        // v3.3.39：conf 统一到 APK 内部 —— path.conf 同步镜像进 App 内部
        // 权威源（同内容双写；防生态包移除后 App 侧残留 stale ICD 值成为
        // 唯一来源 —— VK guard 只在槽位激活时兜底，Mesa 内置档下 stale
        // VK_ICD_FILENAMES 会让 loader 找不到驱动）。
        runCatching {
            com.linbox.apps.containers.launcher.ApkConfStore.ensure(null)
            val apkPath = com.linbox.apps.containers.launcher.ApkConfStore.confFile("path.conf")
            apkPath.parentFile?.mkdirs()
            // v3.3.40【性能】：内容一致跳过（同上）
            if (!apkPath.isFile || apkPath.readText() != outText) {
                val tmp = File(apkPath.parentFile, "path.conf.linbox-tmp")
                tmp.writeText(outText)
                if (!tmp.renameTo(apkPath)) {
                    tmp.copyTo(apkPath, overwrite = true)
                    tmp.delete()
                }
            }
        }
        null
    }.getOrElse { "path.conf 同步失败：${it.message}" }

    /**
     * 解压 tar 包到指定目录（wineguanli extract_package / extract_box_file
     * 同一动作）。
     *
     * v3.3.16（用户实报两起解压失败，截图实证）双根因修复：
     *
     * ① "tar: xz: Cannot exec: Permission denied"（box64-0.4.0-1.tar.xz）：
     *    rootfs 的 GNU tar 遇 .tar.xz 会 exec PATH 里的 xz 程序 ——
     *    ProcessBuilder 默认继承 App 环境（PATH=/system/bin:…，不含
     *    rootfs），xz 要么找不到、要么命中无执行权限的文件。修复：
     *    给 tar 进程显式注入 PATH（rootfs usr/bin + usr/local/bin 打头），
     *    并对 rootfs 的 tar/xz/gzip/bzip2 做一次 chmod +x（解包时权限位
     *    丢失的兜底）；.tar.xz 而 rootfs 无 xz 时给出可操作的错误提示。
     *
     * ② "tar: box64: Cannot open: Text file busy"（box64 正被 wine 会话
     *    使用时覆盖解压）：tar --overwrite 对已存在文件走 O_TRUNC，对
     *    运行中的 ELF 直接 ETXTBSY。修复：--unlink-first —— 先 unlink
     *    再写新文件；运行中的旧 box64 继续持有旧 inode 不受影响（Unix
     *    语义），新会话即用新二进制。注意：--unlink-first 与 --overwrite
     *    在 GNU tar 互斥（组合直接 exit 2，沙盒实测），单独用前者即可。
     *
     * v3.3.9 落点分流（此前全部解到 /usr/local 是错的）：
     *  - turnip / zink → rootfs /usr/lib（path.conf 驱动位置）；
     *  - dxvk / wined3d / fexcore → 对应容器 prefix/drive_c/windows；
     *  - box64 / box86 → rootfs /usr/local/bin（高级设置 Box 配置页）。
     *
     * v3.3.17（用户实报：turnip 选 tar.xz 驱动包无法解压）：rootfs 配方
     * 清单里【根本没有 xz 项目】（有 bzip2/zstd，无 xz）—— usr/bin/xz
     * 不存在，native tar 遇 .tar.xz 必然失败，v3.3.16 的提前报错就是
     * 用户遇到的实际路径。修复：新增【纯 Java 解压回退】
     * [extractTarJavaCompat]（Commons Compress，App 内置依赖，不依赖
     * 设备侧任何解压程序）：.tar.xz 且 rootfs 无 xz → 直接走 Java；
     * native tar 进程失败（exit != 0）→ 再回退 Java 一次，两次都败才
     * 报双错误。
     *
     * @return null 成功；否则错误信息。
     */
    fun extractTarTo(tarPath: String, destDir: File, log: (String) -> Unit = {}): String? {
        val f = File(tarPath)
        if (!f.isFile) return "包不存在：$tarPath"
        val rfsUsrBin = File("${ContainerManager.ROOTFS_ROOT}/usr/bin")
        val rfsUsrLocalBin = File("${ContainerManager.ROOTFS_ROOT}/usr/local/bin")
        val tarBin = listOf(
            File(rfsUsrBin, "tar"),
            File("${ContainerManager.APP_PREFIX}/bin/tar")
        ).firstOrNull { it.canExecute() } ?: return "rootfs 内未找到 tar（rootfs 未导入？）"

        // ① tar 调用的压缩程序（xz/gzip/bzip2）在 rootfs 内 —— PATH 必须
        //    显式注入（App 进程的 PATH 不含 rootfs），且权限位兜底
        val helperBins = listOf("xz", "gzip", "bzip2", "zstd").map { File(rfsUsrBin, it) } +
            listOf(File(rfsUsrBin, "busybox"))
        helperBins.forEach { hb ->
            if (hb.isFile && !hb.canExecute()) runCatching { hb.setExecutable(true, false) }
        }
        if (f.name.endsWith(".tar.xz", true) && !File(rfsUsrBin, "xz").canExecute()) {
            // v3.3.17：rootfs 无 xz 时不再报错 —— 直接纯 Java 解压
            //（rootfs 配方不含 xz 项目，这是 tar.xz 包的常态而非异常）
            val jerr = extractTarJavaCompat(f, destDir, 0, log)
            if (jerr == null) {
                log("已解压 ${f.name} → ${destDir.absolutePath}（纯 Java XZ：rootfs 无 xz，v3.3.17）")
                return null
            }
            return jerr
        }
        // v3.3.21：tzst / tar.zst 同款直通 —— rootfs 无 zstd 时 native tar
        // 必失败，直接走 Java（zstd-jni 解压，wrapper-gamenative.tzst 类包）
        if ((f.name.endsWith(".tzst", true) || f.name.endsWith(".tar.zst", true)) &&
            !File(rfsUsrBin, "zstd").canExecute()
        ) {
            val jerr = extractTarJavaCompat(f, destDir, 0, log)
            if (jerr == null) {
                log("已解压 ${f.name} → ${destDir.absolutePath}（纯 Java Zstd：rootfs 无 zstd，v3.3.21）")
                return null
            }
            return jerr
        }

        val path = listOf(rfsUsrBin, rfsUsrLocalBin, File("/system/bin"))
            .joinToString(":") { it.absolutePath }
        return try {
            val pb = ProcessBuilder(
                tarBin.absolutePath, "-xf", f.absolutePath,
                "-C", destDir.absolutePath, "--unlink-first"
            ).redirectErrorStream(true)
            pb.environment()["PATH"] = path
            val p = pb.start()
            val ok = p.waitFor(120, TimeUnit.SECONDS)
            val out = runCatching { p.inputStream.readBytes().decodeToString() }.getOrDefault("")
            if (!ok) { p.destroyForcibly(); return "解压超时（120s）" }
            if (p.exitValue() != 0) {
                // v3.3.17：native 失败（xz exec 失败等环境故障）→ Java 回退一次
                val jerr = extractTarJavaCompat(f, destDir, 0, log)
                if (jerr == null) {
                    log("已解压 ${f.name} → ${destDir.absolutePath}（native tar 失败后 Java 回退成功：${out.take(120)}）")
                    return null
                }
                "解压失败：${out.take(300)}（Java 回退亦失败：$jerr）"
            }
            else { log("已解压 ${f.name} → ${destDir.absolutePath}"); null }
        } catch (e: Exception) {
            "解压异常：${e.message}"
        }
    }

    /**
     * v3.3.17：纯 Java tar 解压回退（Commons Compress，App 内置依赖，
     * 不依赖设备侧 tar/xz/gzip —— rootfs 配方不含 xz 项目，native 路径
     * 对 .tar.xz 必然失败）。魔数自适应 xz / gzip / bzip2 / 纯 tar；
     * 逐条目【先删后写】（Unix unlink 语义：运行中的 ELF 不 ETXTBSY、
     * 被 mmap 的 so 不被 O_TRUNC 致使用方 SIGBUS）；保留 tar 原始权限位；
     * 拒绝 .. 路径逃逸；支持 strip-components 语义（wine 安装包顶层目录
     * 剥离）。
     *
     * @return null 成功；否则错误信息。
     */
    private fun extractTarJavaCompat(
        src: File, destDir: File, stripComponents: Int, log: (String) -> Unit
    ): String? {
        return try {
            destDir.mkdirs()
            src.inputStream().buffered().use { raw ->
                // 魔数判型（不信任文件名：xz=FD 37 7A 58 5A 00，gzip=1F 8B，
                // bzip2=42 5A 68，zstd=28 B5 2F FD，其余按纯 tar）。
                // v3.3.21：补 zstd —— 用户实测 wrapper-gamenative.tzst
                //（tar+zstd，上游实现 生态常见格式）旧版被当纯 tar 读 →
                // 解压必失败。zstd-jni 经 termux-x11 模块在 classpath
                //（TarCompressorUtils 同源），ZstdCompressorInputStream 可用。
                raw.mark(64 * 1024)
                val head = ByteArray(6)
                var n = 0
                while (n < head.size) {
                    val r = raw.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                raw.reset()
                val input: java.io.InputStream = when {
                    n >= 6 && head[0] == 0xFD.toByte() && head[1] == 0x37.toByte() &&
                        head[2] == 0x7A.toByte() && head[3] == 0x58.toByte() &&
                        head[4] == 0x5A.toByte() && head[5] == 0x00.toByte() ->
                        org.apache.commons.compress.compressors.xz.XZCompressorInputStream(raw)
                    n >= 4 && head[0] == 0x28.toByte() && head[1] == 0xB5.toByte() &&
                        head[2] == 0x2F.toByte() && head[3] == 0xFD.toByte() ->
                        org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream(raw)
                    n >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() ->
                        org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(raw)
                    n >= 3 && head[0] == 0x42.toByte() && head[1] == 0x5A.toByte() &&
                        head[2] == 0x68.toByte() ->
                        org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(raw)
                    else -> raw
                }
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(input).use { tar ->
                    val canonicalDest = destDir.canonicalPath + File.separator
                    while (true) {
                        // commons-compress 1.20：getNextEntry() 返回基类 ArchiveEntry
                        //（协变 TarArchiveEntry 是 1.21+），isSymbolicLink/linkName/mode
                        // 是 TarArchiveEntry 特有 —— 必须用 getNextTarEntry()（沙盒实证）
                        val entry = tar.nextTarEntry ?: break
                        var name = entry.name.removePrefix("./").removePrefix("/")
                        if (stripComponents > 0) {
                            val parts = name.split('/')
                            if (parts.size <= stripComponents) continue
                            name = parts.drop(stripComponents).joinToString("/")
                        }
                        if (name.isNotEmpty() && !name.split('/').any { it == ".." }) {
                            val target = File(destDir, name)
                            if (!target.canonicalPath.startsWith(canonicalDest)) {
                                log("跳过越界成员 ${entry.name}")
                            } else if (entry.isDirectory) {
                                target.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                target.parentFile?.mkdirs()
                                runCatching { target.delete() }
                                runCatching {
                                    android.system.Os.symlink(entry.linkName, target.absolutePath)
                                }.onFailure { log("跳过符号链接 $name（${it.message}）") }
                            } else {
                                target.parentFile?.mkdirs()
                                // unlink-first：写前先删（防 ETXTBSY / mmap SIGBUS）
                                target.delete()
                                target.outputStream().use { tar.copyTo(it) }
                                runCatching {
                                    android.system.Os.chmod(target.absolutePath, entry.mode and 0xFFF)
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            "Java 解压失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** box64/box86 版本探活（wineguanli refresh_box_info：box64 -v 首行）。 */
    fun boxVersion(exeName: String): String {
        val bin = listOf(
            File("${ContainerManager.ROOTFS_ROOT}/usr/local/bin/$exeName"),
            File("${ContainerManager.ROOTFS_ROOT}/usr/bin/$exeName")
        ).firstOrNull { it.isFile && it.canExecute() }
            ?: return "$exeName: 未检测到"
        return try {
            val p = ProcessBuilder(bin.absolutePath, "-v").redirectErrorStream(true).start()
            val ok = p.waitFor(5, TimeUnit.SECONDS)
            val out = runCatching { p.inputStream.readBytes().decodeToString() }.getOrDefault("")
            runCatching { p.destroyForcibly() }
            if (ok) "$exeName: ${out.lineSequence().firstOrNull { it.isNotBlank() } ?: "（无输出）"}"
            else "$exeName: 获取版本超时"
        } catch (e: Exception) {
            "$exeName: 检测时出错（${e.message}）"
        }
    }

    // ------------------------------------------------------------------
    // Wine 安装/删除（wineguanli.py install_from_file / install_from_url /
    // delete_wine_version；LinBox 落点 /usr/opt/wine-<名> 槽位）
    // ------------------------------------------------------------------

    object WineOps {

        /** 从本地 tar 包安装：解压到 /usr/opt/<槽位名> + 写 .linbox-wine-info。 */
        fun installFromTar(tarPath: String, slotName: String, arch: String): String? {
            // v3.3.40：同槽位重装可能换 ELF 形态 —— resolve 缓存失效
            runCatching { WineRuntime.invalidateCache() }
            val f = File(tarPath)
            if (!f.isFile) return "文件不存在：$tarPath"
            val name = slotName.trim().ifEmpty {
                f.nameWithoutExtension.let { n ->
                    n.removeSuffix(".tar").removePrefix("wine-").ifEmpty { "wine" }.let { "wine-$it" }
                }
            }.replace(Regex("[^A-Za-z0-9._-]"), "-")
            val tarBin = listOf(
                File("${ContainerManager.ROOTFS_ROOT}/usr/bin/tar"),
                File("${ContainerManager.APP_PREFIX}/bin/tar")
            ).firstOrNull { it.canExecute() } ?: return "rootfs 内未找到 tar（rootfs 未导入？）"
            val dest = File(ContainerManager.WINE_OPT_DIR, name)
            if (dest.exists()) return "槽位已存在：$name（先删除旧版本）"
            // v3.3.16：与 extractTarTo 同款 —— PATH 注入（rootfs usr/bin
            // 打头）+ 压缩程序 chmod 兜底 + --unlink-first（ETXTBSY 根治）
            val rfsUsrBin = File("${ContainerManager.ROOTFS_ROOT}/usr/bin")
            listOf("xz", "gzip", "bzip2", "zstd").map { File(rfsUsrBin, it) }.forEach { hb ->
                if (hb.isFile && !hb.canExecute()) runCatching { hb.setExecutable(true, false) }
            }
            // v3.3.17：rootfs 无 xz 时 .tar.xz 不再报错 —— 纯 Java 解压
            //（strip=1 剥顶层 wine 目录，与 native --strip-components=1 同语义）
            if (f.name.endsWith(".tar.xz", true) && !File(rfsUsrBin, "xz").canExecute()) {
                val jerr = extractTarJavaCompat(f, dest, 1) { }
                if (jerr != null) return jerr
                return finishWineInstall(dest, name, arch)
            }
            return try {
                dest.mkdirs()
                val pb = ProcessBuilder(
                    tarBin.absolutePath, "-xf", f.absolutePath, "-C", dest.absolutePath,
                    "--strip-components=1", "--unlink-first"
                ).redirectErrorStream(true)
                pb.environment()["PATH"] = listOf(
                    rfsUsrBin, File("${ContainerManager.ROOTFS_ROOT}/usr/local/bin"),
                    File("/system/bin")
                ).joinToString(":") { it.absolutePath }
                val p = pb.start()
                val ok = p.waitFor(300, TimeUnit.SECONDS)
                val out = runCatching { p.inputStream.readBytes().decodeToString() }.getOrDefault("")
                if (!ok) {
                    p.destroyForcibly(); com.linbox.util.SafeFileOps.deleteTree(dest)
                    return "解压超时（300s），已回滚"
                }
                if (p.exitValue() != 0) {
                    // v3.3.17：native 失败（xz exec 失败等）→ Java 回退一次
                    //（strip=1）再走布局检查，两次都败才报错
                    val jerr = extractTarJavaCompat(f, dest, 1) { }
                    if (jerr == null) {
                        return finishWineInstall(dest, name, arch)
                    }
                    com.linbox.util.SafeFileOps.deleteTree(dest)
                    "安装失败（tar 退出码 ${p.exitValue()}：${out.take(200)}；Java 回退亦失败：$jerr）—— 请确认包是 wine 目录树（bin/wine 在顶层）"
                } else if (!File(dest, "bin/wine").isFile && !File(dest, "bin/wine64").isFile) {
                    com.linbox.util.SafeFileOps.deleteTree(dest)
                    "包布局不对：解压后未见 bin/wine（应选 wine 目录树的 tar）"
                } else {
                    return finishWineInstall(dest, name, arch)
                }
            } catch (e: Exception) {
                com.linbox.util.SafeFileOps.deleteTree(dest)
                "安装异常：${e.message}"
            }
        }

        /** v3.3.17：安装收尾（布局检查 + .linbox-wine-info），native 与 Java 解压路径共用。 */
        private fun finishWineInstall(dest: File, name: String, arch: String): String? {
            if (!File(dest, "bin/wine").isFile && !File(dest, "bin/wine64").isFile) {
                com.linbox.util.SafeFileOps.deleteTree(dest)
                return "包布局不对：解压后未见 bin/wine（应选 wine 目录树的 tar）"
            }
            File(dest, ".linbox-wine-info").writeText(
                buildString {
                    appendLine("name=$name")
                    appendLine("arch=$arch")
                    appendLine("installed=${System.currentTimeMillis()}")
                }
            )
            return null
        }

        /** 下载 URL 到缓存文件（wineguanli install_from_url）。@return 文件或错误。 */
        fun download(url: String, log: (String) -> Unit = {}): File {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                val dir = File("/data/data/com.linbox/files/linbox-download").apply { mkdirs() }
                val file = File(
                    dir,
                    "wine_${System.currentTimeMillis()}_${url.substringAfterLast('/').take(60).ifEmpty { "download.tar.gz" }}"
                )
                file.outputStream().use { out ->
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total % (1024 * 1024) < buf.size) log("已下载 ${total / 1024 / 1024} MB…")
                        }
                    }
                }
                return file
            } finally {
                conn.disconnect()
            }
        }

        /** 删除 wine 槽位。@return null 成功。 */
        fun deleteWine(slotName: String): String? {
            runCatching { WineRuntime.invalidateCache() }
            val dir = File(ContainerManager.WINE_OPT_DIR, slotName)
            if (!dir.isDirectory) return "槽位不存在：$slotName"
            if (dir.parentFile?.canonicalPath != File(ContainerManager.WINE_OPT_DIR).canonicalPath)
                return "拒绝删除：路径越界"
            return try {
                if (!com.linbox.util.SafeFileOps.deleteTree(dir))
                    return "删除未完成（部分文件被占用，可重试）"
                null
            } catch (e: Exception) {
                "删除失败：${e.message}"
            }
        }

        /** 设置全局默认 wine 槽位（default_wine=）。@return null 成功。 */
        fun setDefaultWine(slotName: String): String? {
            runCatching { WineRuntime.invalidateCache() }
            val f = File(ContainerManager.GLOBAL_CONF)
            return try {
                f.parentFile?.mkdirs()
                val others = if (f.isFile) f.readLines().filter {
                    val t = it.trim()
                    t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("default_wine=")
                } else emptyList()
                f.writeText(
                    buildString {
                        appendLine("# LinBox Wine 容器全局配置（linbox-container / App 端共用）")
                        (others + "default_wine=$slotName").forEach { appendLine(it) }
                    }
                )
                null
            } catch (e: Exception) {
                "写入失败：${e.message}"
            }
        }
    }

    // ------------------------------------------------------------------
    // Direct3D / CNC-DDraw（wineguanli.py force_d3d9/11、apply_cnc_ddraw）
    // ------------------------------------------------------------------

    object AppDefaults {

        /**
         * 对指定 exe 写 AppDefaults DllOverrides（wine 原生的"按程序覆盖
         * DLL 加载序"机制 —— wineguanli 用复制 DLL 实现，LinBox 用注册表
         * 实现同一效果，不动用户文件）。
         * @param dlls 例如 {"d3d9" to "native,builtin"}；空表 = 清除该 exe 的全部覆盖
         * @return null 成功。
         */
        fun applyDllOverrides(container: ContainerData, exeName: String, dlls: Map<String, String>): String? {
            val prefix = File(container.prefixPath)
            if (!File(prefix, "system.reg").isFile) return "容器前缀尚未构建（先运行 wineboot 或启动一次容器）"
            if (exeName.isBlank() || !exeName.contains('.')) return "请填写完整的 exe 文件名（如 game.exe）"
            val resolved = runCatching { WineRuntime.resolve(container) }.getOrNull()
                ?: return "wine 运行时解析失败（wine 未安装？）"
            val env = WineEnvironment.buildBaseEnv(container, resolved.wineDir).toMutableMap()
            env["WINEPREFIX"] = container.prefixPath
            // v3.3.40【JSON 打入】：AppDefaults 的 DLL 加载序覆盖改为 JSON
            // 描述经 [JsonRegistry] 直接合并进 user.reg（旧链路：生成
            // appdefaults.reg + `wine regedit /s` 客户端，1~3s）；空表 =
            // 清除该 exe 的全部覆盖（逐值 JSON delete）。
            val keyPath = "Software\\Wine\\AppDefaults\\${exeName.replace("\\", "\\\\")}\\DllOverrides"
            val values = org.json.JSONArray()
            if (dlls.isEmpty()) {
                listOf("d3d9", "d3d11", "dxgi", "ddraw").forEach {
                    values.put(org.json.JSONObject().put("name", it).put("type", "delete"))
                }
            } else {
                dlls.forEach { (dll, order) ->
                    values.put(
                        org.json.JSONObject().put("name", dll)
                            .put("type", "sz").put("data", order)
                    )
                }
            }
            val edits = org.json.JSONArray().put(
                org.json.JSONObject()
                    .put("hive", "HKCU")
                    .put("key", keyPath)
                    .put("values", values)
            )
            return try {
                val err = JsonRegistry.apply(
                    File(container.prefixPath), resolved, container, env, edits
                )
                err ?: run {
                    // v3.4.1【注册表大修】：appdefaults.json 账本改为累计式
                    // （exe → {dll → 加载序}）—— WineRegistry.sanitizeRegistryHygiene
                    // 的 AppDefaults 段剥除白名单读取此文件，用户手动配置的
                    // per-exe 子树不被存量段剥除误删；dlls 空（清除配置）=
                    // 账本同步摘除该 exe（旧单条审计格式由读取侧兼容）。
                    runCatching {
                        val dir = File(container.prefixPath, "drive_c/linbox")
                        dir.mkdirs()
                        val ledgerFile = File(dir, "appdefaults.json")
                        val ledger = LinkedHashMap<String, org.json.JSONObject>()
                        if (ledgerFile.isFile) {
                            runCatching {
                                val text = ledgerFile.readText().trim()
                                if (text.startsWith("{")) {
                                    val obj = org.json.JSONObject(text)
                                    for (exe in obj.keys()) {
                                        obj.optJSONObject(exe)?.let { ledger[exe] = it }
                                    }
                                }
                            }
                        }
                        if (dlls.isEmpty()) ledger.remove(exeName)
                        else {
                            val inner = org.json.JSONObject()
                            dlls.forEach { (dll, order) -> inner.put(dll, order) }
                            ledger[exeName] = inner
                        }
                        val obj = org.json.JSONObject()
                        ledger.forEach { (exe, inner) -> obj.put(exe, inner) }
                        ledgerFile.writeText(obj.toString(2))
                    }
                    null
                }
            } catch (e: Exception) {
                "注册表 JSON 直写异常：${e.message}"
            }
        }

        /** 强制 D3D9（wineguanli force_d3d9）。 */
        fun forceD3d(container: ContainerData, exeName: String) =
            applyDllOverrides(container, exeName, mapOf("d3d9" to "native,builtin"))

        /** 强制 D3D11（wineguanli force_d3d11）。 */
        fun forceD3d11(container: ContainerData, exeName: String) =
            applyDllOverrides(container, exeName, mapOf("d3d11" to "native,builtin"))

        /** CNC DDraw（wineguanli apply_cnc_ddraw：ddraw 原生优先 + 关联项）。 */
        fun cncDdraw(container: ContainerData, exeName: String) =
            applyDllOverrides(
                container, exeName, mapOf(
                    "ddraw" to "native,builtin",
                    "d3d9" to "native,builtin",
                    "dxgi" to "native,builtin"
                )
            )

        /** 清除某 exe 的全部覆盖（wineguanli clear_d3d_config / clear_cnc_config）。 */
        fun clear(container: ContainerData, exeName: String) =
            applyDllOverrides(container, exeName, emptyMap())
    }

    // ------------------------------------------------------------------
    // MangoHud 配置串（wineguanli.py MangohudConfigDialog.get_config_string）
    // ------------------------------------------------------------------

    object MangoHud {

        /** wineguanli 的全部可勾选项（key → 中文标签）。 */
        val TOGGLES: List<Pair<String, String>> = listOf(
            "fps" to "FPS", "frametime" to "帧时间",
            "cpu_stats" to "CPU使用率", "gpu_stats" to "GPU使用率",
            "ram" to "内存使用", "vram" to "显存使用",
            "cpu_temp" to "CPU温度", "gpu_temp" to "GPU温度",
            "horizontal" to "横屏显示", "no_display" to "无显示模式"
        )

        val POSITIONS = listOf("top-left", "top-right", "bottom-left", "bottom-right")

        /**
         * 组配置串（wineguanli get_config_string 逐字段等价）：
         * 勾选项 + horizontal + font_size=<n> + position=<p> +
         * background_alpha=<0.xx> + no_display。
         */
        fun buildConfig(
            enabled: Set<String>,
            fontSize: Int,
            position: String,
            backgroundAlpha: Int,
            noDisplay: Boolean
        ): String = buildList {
            TOGGLES.forEach { (k, _) -> if (k in enabled && k != "horizontal" && k != "no_display") add(k) }
            if ("horizontal" in enabled) add("horizontal")
            add("font_size=$fontSize")
            add("position=$position")
            add("background_alpha=%.2f".format(backgroundAlpha / 100.0))
            if (noDisplay || "no_display" in enabled) add("no_display")
        }.joinToString(",")

        /** 从配置串恢复勾选态（MangoHudConfigDialog.load_current_config 等价）。 */
        fun parseConfig(config: String): Triple<Set<String>, Boolean, Boolean> {
            val set = mutableSetOf<String>()
            config.split(',').map { it.trim() }.forEach {
                if (it.isNotEmpty() && !it.contains('=')) set.add(it)
            }
            val noDisplay = "no_display" in set
            if (noDisplay) set.remove("no_display")
            return Triple(set, noDisplay, set.isNotEmpty() || config.isNotBlank())
        }
    }

    // ------------------------------------------------------------------
    // 桌面快捷方式（wineguanli.py ShortcutDialog 的 Android 等价物）
    // ------------------------------------------------------------------

    object Shortcuts {

        /** Android 主界面启动器固定的 extra：打开容器窗口并运行指定容器。 */
        const val EXTRA_RUN_CONTAINER = "linbox_run_container"

        /**
         * 请求把"运行容器"快捷方式固定到 Android 启动器
         * （ShortcutManager.requestPinShortcut，系统会弹确认框；
         * 部分启动器不支持固定，返回 false）。
         */
        fun pinRunContainer(context: Context, container: ContainerData, exePath: String): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            return try {
                val mgr = context.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return false
                if (!mgr.isRequestPinShortcutSupported) return false
                val intent = Intent(context, Class.forName("com.linbox.MainActivity")).apply {
                    action = Intent.ACTION_MAIN
                    putExtra(EXTRA_RUN_CONTAINER, container.name)
                    if (exePath.isNotBlank()) putExtra("linbox_run_exe", exePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // v3.3.4：容器名支持中文 —— ShortcutManager 的 id 只接受
                // 安全字符集，中文名消毒为 ASCII（shortLabel/longLabel 保留
                // 原名，桌面图标显示不受影响）
                val safeId = container.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(40).ifEmpty { "container" }
                val info = android.content.pm.ShortcutInfo.Builder(context, "linbox-container-$safeId")
                    .setShortLabel(container.name.take(10))
                    .setLongLabel("运行 ${container.name}")
                    .setIcon(
                        android.graphics.drawable.Icon.createWithResource(context, context.applicationInfo.icon)
                    )
                    .setIntent(intent)
                    .build()
                mgr.requestPinShortcut(info, null)
            } catch (e: Exception) {
                false
            }
        }
    }
}
