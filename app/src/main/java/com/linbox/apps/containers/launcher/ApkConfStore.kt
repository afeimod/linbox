package com.linbox.apps.containers.launcher

import android.content.Context
import java.io.File

/**
 * v3.3.39：APK 内部 conf 中心 ——「把 conf 的所有配置都统一到 APK 内部端」。
 *
 * ## 定位
 *
 * v3.3.38 之前，cores.conf / env.conf / dynarec_active.conf / locale.conf /
 * path.conf / custom.conf 等生态配置**全部住在 rootfs /opt 生态包里**
 * （[WineEnvironment.optRoot] 探测）：没装生态包 → 「未检测到 /opt 生态」，
 * 容器管理的 conf 编辑页整页不可用，启动链 conf 全部旁路 —— 配置能力
 * 被一个可选的 rootfs 资产绑架。
 *
 * v3.3.39 起本类成为 conf 的**权威源（authority）**：
 *
 * ```
 * /data/data/com.linbox/files/linbox-conf/
 *   ├── conf/                      ← 全部 *.conf（与 /opt 生态 conf/ 同构）
 *   │     env.conf  cores.conf  path.conf
 *   │     dynarec_fixmpeg.conf  dynarec_active.conf
 *   │     custom.conf  virgl.conf  …
 *   └── locale.conf                ← 语言（与 /opt 根的 locale.conf 同构）
 * ```
 *
 * App 私有存储：**rootfs 重导入/删除不清空**，APK 升级不丢失；conf 编辑
 * 永远可用（不再依赖生态包存在）。
 *
 * ## 三条数据流
 *
 * 1. **种子（seed）**：assets/linbox/conf/ 内置缺省 —— v3.3.39 种子取自
 *    用户提供的 opt.zip 已知良好值（PRIMARY_CORES="4-7" 大核优先、
 *    STRONGMEM=0、AVX=2、mailbox 呈现等）。目标文件已存在则跳过
 *    （用户数据永远不被资产覆盖）。
 *
 * 2. **一次性导入（import-once）**：生态包**首次**出现在 rootfs/opt 时
 *    （或 wine 槽位 opt/），其 conf 值覆盖进 App 内部 —— 仅覆盖仍是
 *    「原始种子内容」的文件（用户已改过的 App 内部文件不动），
 *    [IMPORT_MARKER] 落盘后不再执行。这保证存量用户的 /opt 现值
 *    （容器管理/终端侧的历史更改）无缝成为起点。
 *
 * 3. **写透同步（sync-out）**：App 内部每次写入成功后，best-effort 镜像
 *    到 /opt 生态 conf（生态不存在则静默跳过）—— 终端链（linbox-wine
 *    load_opt_confs）与生态脚本读到的永远是同一份值。
 *
 * ## 启动链合并读（[mergedConfMap] / [mergedLocale]）
 *
 * [WineEnvironment.readOptConfs] / [WineEnvironment.readOptLocale] 委托
 * 本类：**逐文件 mtime 新者胜** ——
 *  - App 内部改（UI 保存）→ App 文件新 → App 值生效，且已同步 /opt；
 *  - 终端侧直接改 /opt/conf（生态脚本、vi）→ /opt 文件新 → /opt 值生效；
 *  - 双向都「后写的赢」，与 sh 逐文件 source 的直观语义一致。
 * 文件顺序仍为 env → cores → path → dynarec_fixmpeg → dynarec_active，
 * 其余 *.conf 按名兜底（wine_path.conf / custom.conf 依旧排除），
 * 同名键后写覆盖 —— 与 v3.2.29 起的 readOptConfs 语义逐字对齐。
 *
 * ## 线程模型
 *
 * 全部方法在调用方线程执行（UI 侧 Dispatchers.IO；启动链在
 * linbox-launcher 线程）；原子落盘（tmp + rename，跨设备回退 copyTo）。
 *
 * ## 性能闭环（用户主诉「性能问题最大关键」的 conf 侧）
 *
 * cores.conf 的 PRIMARY_CORES="4-7" 经 applyOptConfEnv 导出后由
 * RootfsLauncher.applyTasksetAffinity 真正钉核 —— 统一后无需生态包，
 * **开箱即绑大核**；dynarec_active.conf 的 STRONGMEM=0 / BIGBLOCK=3 /
 * AVX=2 同样开箱生效，不再被「未检测到 /opt 生态」整页旁路。
 */
object ApkConfStore {

    /** APK 内部 conf 根（App 私有存储；与 rootfs 完全解耦）。 */
    const val CONF_ROOT = "/data/data/com.linbox/files/linbox-conf"

    /** conf 文件目录（布局与 /opt 生态 conf/ 同构）。 */
    const val CONF_DIR = "$CONF_ROOT/conf"

    /** APK assets 内置缺省目录（v3.3.39 种子 = 用户 opt.zip 已知良好值）。 */
    private const val ASSET_DIR = "linbox/conf"

    /** 生态一次性导入标记（内容 = 导入时间戳；存在即不再导入）。 */
    private val IMPORT_MARKER: File get() = File(CONF_ROOT, ".opt-imported")

    /** assets 里预置的 conf 清单（locale.conf 在根，其余在 conf/）。 */
    private val ASSET_CONFS = listOf(
        "env.conf", "cores.conf", "path.conf",
        "dynarec_fixmpeg.conf", "dynarec_active.conf",
        "custom.conf", "virgl.conf"
    )

    /** 固定加载顺序（readOptConfs / linbox-wine load_opt_confs 同源）。 */
    internal val FIXED_ORDER = listOf(
        "env.conf", "cores.conf", "path.conf",
        "dynarec_fixmpeg.conf", "dynarec_active.conf"
    )

    /** 非环境文件（wine_path.conf 槽位自管 / custom.conf DY 取值定义表）。 */
    internal val SKIP = setOf("wine_path.conf", "custom.conf")

    /** ensure 结果缓存（失败可重试）。 */
    @Volatile
    private var ensured = false

    // ------------------------------------------------------------------
    // ensure：种子 + 一次性生态导入（幂等）
    // ------------------------------------------------------------------

    /**
     * 确保 App 内部 conf 就位（目录 + 缺省种子 + 一次性生态导入）。
     * 幂等；结果缓存，失败下轮重试。启动链每次会话头调用（成本 ≈ 几次
     * stat），UI 读写入口也调用（无 Context 时经 LinBoxApp 取）。
     * synchronized —— 首轮种子/导入与并发会话的读不会互踩 tmp 文件。
     *
     * @return true = conf 目录可用（种子/导入尽力而为，失败不阻断）
     */
    fun ensure(context: Context?): Boolean {
        if (ensured) return true
        synchronized(this) {
            if (ensured) return true
            val ctx = context ?: runCatching { com.linbox.LinBoxApp.get() }.getOrNull()
            val dir = File(CONF_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return false
            // 1) 种子：目标缺失才落（用户数据永不覆盖）
            if (ctx != null) {
                runCatching { seedAssets(ctx) }
                    .onFailure { android.util.Log.w(TAG, "conf 种子失败: ${it.message}") }
            }
            // 2) 一次性生态导入（生态首次出现时；用户改过的 App 文件不动）
            runCatching { importFromEcosystemOnce() }
                .onFailure { android.util.Log.w(TAG, "生态 conf 导入失败: ${it.message}") }
            // 3) v3.4.6 一次性迁移：旧版 env.conf 残留的激活态
            //    `export BOX64_NOPULSE=0`（设备实测会话日志实证导出该项）
            //    —— 种子策略"目标存在即跳过"使其永不刷新。wowbox64/
            //    arm64ec 线由此被 box64 的 pulse 包装干扰（部分游戏无声）。
            runCatching { migrateLegacyNopulse() }
                .onFailure { android.util.Log.w(TAG, "conf 迁移失败: ${it.message}") }
            ensured = true
            return true
        }
    }

    /**
     * v3.4.6 一次性迁移（外科手术式，只动一行）：
     *
     * 旧版 env.conf 种子带激活态 `export BOX64_NOPULSE=0`，本版种子已改为
     * 注释态（wowbox64/arm64ec 线由 linbox-wine 按后端自动注入
     * BOX64_NOPULSE=1，box64 后端维持缺省 0）。设备上的旧 conf 因
     * "目标存在即跳过"的种子策略从未刷新 —— 本迁移把【仍为激活态的该行】
     * 注释掉并附说明；改动前先备份 `env.conf.pre-v346.bak`。
     *
     * - 用户自己写过的其它行一概不动（不是整文件替换）；
     * - 用户如果本来就注释过/删过该行 → 无激活态 → 零改动；
     * - 已备份过（bak 存在）→ 不重复备份（幂等）。
     */
    private fun migrateLegacyNopulse() {
        val env = confFile("env.conf")
        if (!env.isFile) return
        val lines = runCatching { env.readLines() }.getOrNull() ?: return
        val idx = lines.indexOfFirst { it.trim() == "export BOX64_NOPULSE=0" }
        if (idx < 0) return
        val bak = File(env.parentFile, "env.conf.pre-v346.bak")
        if (!bak.isFile) {
            runCatching { bak.writeText(lines.joinToString("\n") + "\n") }
        }
        val note = "#export BOX64_NOPULSE=0  # v3.4.6 注释：box64 后端缺省即 0；" +
            "arm64ec/wowbox64 线由 linbox-wine 自动置 1（避免与 winepulse 原生 libpulse 冲突）"
        val migrated = lines.toMutableList()
        migrated[idx] = note
        atomicWrite(env, migrated.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" })
        android.util.Log.i(
            TAG,
            "env.conf 迁移：注释旧版激活态 export BOX64_NOPULSE=0（备份 env.conf.pre-v346.bak）"
        )
    }

    /** assets 缺省落盘（仅缺失文件；locale.conf 在根目录）。 */
    private fun seedAssets(context: Context) {
        for (name in ASSET_CONFS) {
            val target = File(CONF_DIR, name)
            if (target.isFile) continue
            copyAsset(context, "$ASSET_DIR/$name", target)
        }
        val loc = File(CONF_ROOT, "locale.conf")
        if (!loc.isFile) copyAsset(context, "$ASSET_DIR/locale.conf", loc)
    }

    private fun copyAsset(context: Context, assetPath: String, target: File) {
        val bytes = runCatching { context.assets.open(assetPath).use { it.readBytes() } }
            .getOrNull() ?: return
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".seed-tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * 一次性生态导入：/opt 生态（[WineEnvironment.optRoot] 全部候选）conf
     * 现值覆盖 App 内部 —— 仅覆盖**仍是种子原文**的文件（用户已在 UI
     * 改过的文件保持 App 值）；导入完成后落标记，永不重复。
     *
     * 生态尚未出现（rootfs 未导入 / 无生态包）→ 不落标记，下次 ensure
     * 再探（生态包晚装的场景同样能导入）。
     */
    private fun importFromEcosystemOnce() {
        if (IMPORT_MARKER.isFile) return
        val eco = WineEnvironment.optRoot(null) ?: return
        val ecoConf = File(eco, "conf")
        var imported = 0
        for (name in ASSET_CONFS) {
            val src = File(ecoConf, name)
            val dst = File(CONF_DIR, name)
            if (!src.isFile) continue
            // 仍是种子原文（或不存在）才导入 —— 已被 UI 改过的文件不动
            if (dst.isFile && !isPristine(dst)) continue
            runCatching {
                val tmp = File(dst.parentFile, "$name.import-tmp")
                tmp.writeBytes(src.readBytes())
                if (!tmp.renameTo(dst)) {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                }
                imported++
            }
        }
        // locale.conf（生态根下）
        val srcLoc = File(eco, "locale.conf")
        val dstLoc = File(CONF_ROOT, "locale.conf")
        if (srcLoc.isFile && (!dstLoc.isFile || isPristine(dstLoc))) {
            runCatching {
                val tmp = File(dstLoc.parentFile, "locale.conf.import-tmp")
                tmp.writeBytes(srcLoc.readBytes())
                if (!tmp.renameTo(dstLoc)) {
                    tmp.copyTo(dstLoc, overwrite = true)
                    tmp.delete()
                }
                imported++
            }
        }
        IMPORT_MARKER.parentFile?.mkdirs()
        IMPORT_MARKER.writeText("${System.currentTimeMillis()}\n")
        if (imported > 0) {
            android.util.Log.i(TAG, "生态 conf 一次性导入: $imported 个文件 ← ${eco.absolutePath}")
        }
    }

    /** 文件内容与 assets 种子原文一致（未被用户改过）。 */
    private fun isPristine(f: File): Boolean {
        val ctx = runCatching { com.linbox.LinBoxApp.get() }.getOrNull() ?: return false
        val rel = if (f.name == "locale.conf") "$ASSET_DIR/locale.conf" else "$ASSET_DIR/${f.name}"
        val asset = runCatching { ctx.assets.open(rel).use { it.readBytes() } }.getOrNull()
            ?: return false
        return asset.contentEquals(runCatching { f.readBytes() }.getOrNull())
    }

    // ------------------------------------------------------------------
    // 读写 API（OptConfSupport 后端；语义与旧 /opt 版逐字兼容）
    // ------------------------------------------------------------------

    /** conf 文件句柄（ensure 后必在）。 */
    internal fun confFile(name: String): File = File(CONF_DIR, name)

    /** locale.conf 句柄（根目录）。 */
    internal fun localeFile(): File = File(CONF_ROOT, "locale.conf")

    /** 解析 `KEY=VALUE` / `export KEY=VALUE` 行（复用启动链同源解析器）。 */
    internal fun parseConfLines(f: File): List<Pair<String, String>> =
        runCatching {
            f.readLines().mapNotNull { WineEnvironment.parseConfLine(it) }
        }.getOrDefault(emptyList())

    /** env.conf 条目（文件顺序）。 */
    fun envEntries(): List<Pair<String, String>> {
        val f = confFile("env.conf")
        return if (f.isFile) parseConfLines(f) else emptyList()
    }

    /** cores.conf 的 PRIMARY_CORES。 */
    fun primaryCores(): String? =
        parseConfLines(confFile("cores.conf")).firstOrNull { it.first == "PRIMARY_CORES" }?.second

    /** locale.conf 值（校验语义同 WineEnvironment.readOptLocale）。 */
    fun locale(): String? {
        val f = localeFile()
        if (!f.isFile) return null
        val raw = runCatching { f.readLines().firstOrNull() }.getOrNull() ?: return null
        val value = raw.trim().trim('"', '\'')
        if (value.isEmpty()) return null
        return value.takeIf {
            it.matches(Regex("[A-Za-z]{2,3}(_[A-Za-z]{2,4})?(\\.[A-Za-z0-9-]+)?(@[A-Za-z0-9-]+)?"))
        }
    }

    /** custom.conf 变量定义（VAR → 候选值列表）。 */
    fun dyVariables(): LinkedHashMap<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        val f = confFile("custom.conf")
        if (!f.isFile) return out
        parseConfLines(f).forEach { (k, v) ->
            val values = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (values.isNotEmpty() && k !in out) out[k] = values
        }
        return out
    }

    /** dynarec_active.conf 当前值（VAR → 值）。 */
    fun dyActive(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val f = confFile("dynarec_active.conf")
        if (!f.isFile) return out
        parseConfLines(f).forEach { (k, v) -> out[k] = v }
        return out
    }

    /**
     * 行级转换 + 原子回写 + 写透同步（UI 写入的后端）。
     * @return null 成功；非 null 错误消息。
     */
    internal fun rewriteConf(name: String, transform: (MutableList<String>) -> Unit): String? {
        if (!ensure(null)) return "App 内部 conf 目录不可用（$CONF_DIR）"
        return try {
            val f = confFile(name)
            val lines = if (f.isFile) f.readLines().toMutableList() else mutableListOf()
            transform(lines)
            atomicWrite(f, lines.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" })
            syncOut(name)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** locale.conf 覆写 + 同步。 */
    fun writeLocale(value: String): String? {
        if (!ensure(null)) return "App 内部 conf 目录不可用"
        return try {
            atomicWrite(localeFile(), value.trim() + "\n")
            syncOut("locale.conf")
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** 整文件重写 dynarec_active.conf（wineguanli apply_config 同款）+ 同步。 */
    fun writeDynarecActive(values: List<Pair<String, String>>): String? {
        if (!ensure(null)) return "App 内部 conf 目录不可用"
        val allowed = dyVariables().keys
        return try {
            val body = values.filter { it.first in allowed }
                .joinToString("\n") { (k, v) -> "export $k=$v" }
            atomicWrite(confFile("dynarec_active.conf"), if (body.isEmpty()) "" else "$body\n")
            syncOut("dynarec_active.conf")
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** custom.conf 缺省定义表落盘（缺失时）+ 同步。@return null 成功。 */
    fun ensureDyCustomConf(defaultText: String): String? {
        val f = confFile("custom.conf")
        if (f.isFile) return null
        if (!ensure(null)) return "App 内部 conf 目录不可用"
        return try {
            atomicWrite(f, defaultText)
            syncOut("custom.conf")
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------------
    // 写透同步（App 内部 → /opt 生态 conf；best-effort）
    // ------------------------------------------------------------------

    /**
     * 把 App 内部 conf 文件镜像到 /opt 生态（生态不存在静默跳过）。
     * locale.conf 落生态根，其余落生态 conf/。终端链（linbox-wine）与
     * 生态脚本由此读到与 App 完全一致的值。
     */
    internal fun syncOut(name: String) {
        val eco = WineEnvironment.optRoot(null) ?: return
        val src = if (name == "locale.conf") localeFile() else confFile(name)
        if (!src.isFile) return
        runCatching {
            val dst = if (name == "locale.conf") File(eco, name) else File(File(eco, "conf"), name)
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, "$name.linbox-sync-tmp")
            tmp.writeBytes(src.readBytes())
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            android.util.Log.w(TAG, "conf 同步 /opt 失败（$name）: ${it.message}")
        }
    }

    // ------------------------------------------------------------------
    // 启动链合并读（WineEnvironment.readOptConfs / readOptLocale 委托）
    // ------------------------------------------------------------------

    /**
     * v3.3.40【去 /opt/conf 依赖 · 性能】：合并读改为【只读 App 内部
     * linbox-conf 目录】（权威源；启动链零 /opt 探测、零 mtime 比对 —— 旧版
     * 每次启动 optRoot 五候选探测 + 双源逐文件 stat/读两遍）。
     * 终端侧的 /opt 生态仍可改值：写通道 [syncOut] 恒双向（UI 写 → /opt
     * 镜像），生态首装的存量值由 [importFromEcosystemOnce] 一次性导入。
     * 文件内逐行 KEY=VALUE，同名键后写覆盖（sh source 语义不变）。
     *
     * @param wineDir 保留形参（兼容旧调用点；不再参与读取）
     * @return 键值映射（无 conf → 空 map）
     */
    fun mergedConfMap(wineDir: File?): Map<String, String> {
        ensure(null)
        val names = LinkedHashMap<String, Unit>()
        FIXED_ORDER.forEach { names[it] = Unit }
        File(CONF_DIR).listFiles { f -> f.isFile && f.name.endsWith(".conf") }
            ?.sortedBy { it.name }
            ?.forEach { if (it.name !in SKIP) names.putIfAbsent(it.name, Unit) }

        val out = LinkedHashMap<String, String>()
        for (name in names.keys) {
            val f = File(CONF_DIR, name)
            if (!f.isFile) continue
            parseConfLines(f).forEach { (k, v) -> out[k] = v }
        }
        return out
    }

    /**
     * locale.conf 读（v3.3.40：只读 App 内部；校验同 readOptLocale）。
     */
    fun mergedLocale(wineDir: File?): String? {
        ensure(null)
        val f = localeFile()
        if (!f.isFile) return null
        val raw = runCatching { f.readLines().firstOrNull() }.getOrNull() ?: return null
        val value = raw.trim().trim('"', '\'')
        if (value.isEmpty()) return null
        return value.takeIf {
            it.matches(Regex("[A-Za-z]{2,3}(_[A-Za-z]{2,4})?(\\.[A-Za-z0-9-]+)?(@[A-Za-z0-9-]+)?"))
        }
    }

    /**
     * 启动链诊断行（v3.3.40：不再触发第二次 mergedConfMap 全量读 ——
     * 旧版 probeLine + applyOptConfEnv 每次启动把全部 conf 读两遍）。
     */
    fun probeLine(wineDir: File?): String {
        ensure(null)
        val apkDir = File(CONF_DIR)
        val apkCount = apkDir.listFiles { f -> f.isFile && f.name.endsWith(".conf") }?.size ?: 0
        return "conf 源: App 内部 $apkDir（$apkCount 文件，唯一权威；不读 /opt —— 释放启动时间；写透同步 /opt 保留）"
    }

    /** 双源按 mtime 取新（仅存在一个时取那一个；都不存在 null）。 */
    private fun pickByFreshness(apk: File?, eco: File?): File? {
        val a = apk?.takeIf { it.isFile }
        val e = eco?.takeIf { it.isFile }
        return when {
            a != null && e != null -> if (e.lastModified() > a.lastModified()) e else a
            else -> a ?: e
        }
    }

    /** 原子落盘（tmp + rename；跨设备 rename 失败回退 copyTo）。 */
    private fun atomicWrite(f: File, content: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".linbox-tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    private const val TAG = "ApkConfStore"
}
