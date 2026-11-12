package com.linbox.apps.containers.launcher

import android.content.Context
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * v3.2.1（用户问题 4）—— wine 中文/本地化支持（wine.mo 翻译 + CJK 字体）。
 * v3.2.24（用户问题 5）—— 字体落位修正：rootfs 侧一律安装到
 * rootfs/usr/share/fonts 目录（fontconfig 标准扫描路径），不再以
 * 根目录 LinBox/fonts 为主目录。
 *
 * ## 根因（三层）
 *
 * 1. **界面语言（英文环境）**：wine 配方 `--enable-nls` 且 `--prefix=
 *    $prefix/opt/wine` —— wine 的 LOCALEDIR 指向 wine 安装目录内的
 *    `share/locale`。但 wine 包经 `install-wine` 装进**槽位目录**
 *    （/usr/opt/wine-10-18 等，编译期路径是默认槽位 /usr/opt/wine），
 *    槽位换名后编译期 LOCALEDIR 与真实目录脱节；再叠加 CI 镜像缺
 *    msgfmt 时 make install 静默跳过 .mo —— 结果设备上
 *    LANG=zh_CN.UTF-8 也只有英文（wine 自己的 NLS 加载器找不到
 *    zh_CN/wine.mo）。
 *
 *    修复：APK assets 携带已编译的 wine.mo（上游 wine-10.18 的
 *    zh_CN/zh_TW/ja/ko 翻译），启动链幂等安装到**两处**（槽位目录 +
 *    编译期默认路径）—— 无论 wine 按哪条路径解析都命中。已有同名文件
 *    （CI 新包自带）不覆盖。
 *
 * 2. **中文渲染（方块字）—— wine GDI 侧**：前缀没有任何 CJK 字体
 *    —— wine 内置字体只有 Tahoma 等拉丁字面，中文全部渲染为
 *    方块/空白。修复：把 **Android 系统自带的 CJK 字体**
 *    （/system/fonts/NotoSansCJK/MiSans/…，全球可读）复制进前缀
 *    `drive_c/windows/Fonts/`，并写注册表：
 *    - HKLM\…\CurrentVersion\Fonts：注册为 SimSun/NSimSun/SimHei/
 *      Microsoft YaHei（+ ja/ko 容器的本地字体名）；
 *    - HKLM\…\FontSubstitutes：MS Shell Dlg → 中文字体（Windows
 *      中文版的官方替换约定）—— 未指定字体的老程序默认即得中文字形。
 *
 * 3. **中文渲染 —— rootfs/X11 侧（v3.2.24 用户问题 5）**：此前 CJK
 *    字体只进前缀（GDI 侧），rootfs 的 fontconfig 扫描路径
 *    （usr/share/fonts/）里没有中文字体 —— wine 的 freetype 后端与
 *    X11 侧字体名匹配都找不到中文字形。修复：同一份系统 CJK 字体
 *    同步落到 `rootfs/usr/share/fonts/linbox-cjk/`；用户字体投放
 *    目录改为 `rootfs/usr/share/fonts/linbox-user/`（直接就在
 *    fontconfig 扫描路径内，无需再“导入”；rootfs 侧不再依赖根
 *    目录 LinBox/fonts）。旧 `/storage/emulated/0/LinBox/fonts/`
 *    作为兼容源继续合并读取。
 *
 * 全部幂等（前缀内标记文件 + 目标存在检查），每次启动链快速通过。
 */
object WineLocaleSupport {
    private const val TAG = "WineLocaleSupport"

    /**
     * v3.2.24（用户问题 5）：用户字体投放目录 —— **rootfs 内**
     * `usr/share/fonts/linbox-user/`（即
     * /data/data/com.linbox/files/rootfs/usr/share/fonts/linbox-user/，
     * 经 LinBox 文件管理器或终端可直接访问）。放进去的 .ttf/.otf/.ttc
     * 立即处于 fontconfig 扫描路径内；启动链只需把 GDI 侧（前缀
     * Fonts + 注册表）补齐。
     *
     * 兼容：旧版投放目录 `/storage/emulated/0/LinBox/fonts/` 里的字体
     * 仍会被合并导入（不再作为文档化的主目录）。
     */
    private const val USER_FONT_DIR = "usr/share/fonts/linbox-user"

    /** 旧版投放目录（v3.2.23 及之前）：兼容读取，字体合并导入。 */
    private const val LEGACY_USER_FONT_DIR = "/storage/emulated/0/LinBox/fonts"

    /** rootfs 侧 CJK 字体目录（fontconfig 扫描路径内，v3.2.24）。 */
    private val ROOTFS_CJK_FONT_DIR = "usr/share/fonts/linbox-cjk"

    /** 字体扩展名（rootfs 目录候选展开扫描用）。 */
    private val FONT_EXTS = setOf("ttf", "otf", "ttc")

    /** assets 内的 wine.mo（语言目录名 → asset 文件名）。 */
    private val WINE_MO_ASSETS = mapOf(
        "zh_CN" to "linbox/locale/zh_CN.mo",
        "zh_TW" to "linbox/locale/zh_TW.mo",
        "ja" to "linbox/locale/ja.mo",
        "ja_JP" to "linbox/locale/ja.mo",
        "ko" to "linbox/locale/ko.mo",
        "ko_KR" to "linbox/locale/ko.mo"
    )

    /** Android 系统 CJK 字体候选（单字面文件优先 —— .ttc 首字面可能是 JP 变体）。 */
    private val SYS_FONT_CANDIDATES = listOf(
        "/system/fonts/NotoSansSC-Regular.otf",
        "/system/fonts/NotoSansSC-Regular.ttf",
        "/system/fonts/NotoSansCJKsc-Regular.otf",
        "/system/fonts/MiSans-Regular.ttf",
        "/system/fonts/MiSansSC-Regular.ttf",
        "/system/fonts/HarmonyOS_Sans_SC_Regular.ttf",
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/DroidSansFallback.ttf"
    )

    // v3.3.37（用户："hangover 的构建缺失中文环境"）：rootfs-fonts 配方安装的
    // NotoSansSC 纳入候选 —— 设备 /system/fonts 无 CJK 时（TV 盒子 /
    // 部分 ROM）不再直接放弃，rootfs 自带字体兜底。位置与 rootfs-fonts
    // 配方 extra_fuction 的安装路径一一对应（share/fonts/noto/、dejavu/）。
    private val ROOTFS_FONT_CANDIDATES = listOf(
        "usr/share/fonts/noto/NotoSansSC.ttf",
        "usr/share/fonts/noto/NotoSansCJKsc-Regular.otf",
        "usr/share/fonts/linbox-cjk"
    )

    /** 前缀内“字体已装”标记。 */
    private fun fontMarker(prefix: File) = File(prefix, ".linbox-cjk-font")

    /**
     * 启动链入口：翻译安装（无需前缀）+ CJK 字体（需前缀就绪；未就绪时
     * 静默跳过 —— 前缀建成后的下一次启动自动补装）。
     */
    fun ensure(
        context: Context,
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ) {
        runCatching { installWineMo(context, resolved, log) }
            .onFailure { log("警告: wine 翻译安装失败（${it.message}）—— wine 界面可能保持英文") }
        runCatching { installCjkFont(resolved, container, env, log) }
            .onFailure { log("警告: CJK 字体安装失败（${it.message}）—— 中文可能显示为方块") }
        // v3.2.23（用户问题 7）：用户自有字体导入（/storage/emulated/0/
        // LinBox/fonts/ → rootfs fontconfig + 前缀 Fonts 注册表）
        runCatching { importUserFonts(resolved, container, env, log) }
            .onFailure { log("警告: 用户字体导入异常（${it.message}）—— 忽略继续") }
    }

    // ------------------------------------------------------------------
    // 1) wine.mo 翻译安装
    // ------------------------------------------------------------------

    /**
     * 把 assets 里的 wine.mo 安装到 wine 目录的 share/locale —— 槽位目录
     * 与编译期默认路径（/usr/opt/wine）各一份；已存在（CI 新包自带）不覆盖。
     */
    private fun installWineMo(
        context: Context,
        resolved: WineRuntime.Resolved,
        log: (String) -> Unit
    ) {
        val targets = linkedSetOf(resolved.wineDir)
        // 编译期默认槽位路径（--prefix=$prefix/opt/wine）—— 槽位换名时
        // wine 仍按此路径找翻译；不存在则建目录放一份。
        val defaultSlot = File(ContainerManager.WINE_OPT_DIR, "wine")
        if (defaultSlot.isDirectory && defaultSlot.absolutePath != resolved.wineDir.absolutePath) {
            targets.add(defaultSlot)
        }

        val am = context.assets
        var installed = 0
        for ((langDir, asset) in WINE_MO_ASSETS) {
            val targetDirs = targets.map { File(it, "share/locale/$langDir/LC_MESSAGES") }
            if (targetDirs.all { File(it, "wine.mo").isFile }) continue
            val bytes = runCatching { am.open(asset).use { s -> s.readBytes() } }.getOrNull()
                ?: continue
            for (dir in targetDirs) {
                val f = File(dir, "wine.mo")
                if (f.isFile) continue
                runCatching {
                    dir.mkdirs()
                    FileOutputStream(f).use { it.write(bytes) }
                    installed++
                }.onFailure {
                    log("警告: wine.mo 写入 ${f.absolutePath} 失败（${it.message}）")
                }
            }
        }
        if (installed > 0) {
            log("已安装 wine 界面翻译 wine.mo ×$installed（zh_CN/zh_TW/ja/ko —— 配合容器的 LANG 环境变量，wine 界面即为对应语言）")
        }
    }

    // ------------------------------------------------------------------
    // 2) CJK 字体安装（前缀级）
    // ------------------------------------------------------------------

    /**
     * 复制 Android 系统 CJK 字体进前缀并写注册表。前缀未建成
     * （system.reg 不存在）时跳过 —— 由后续启动补装。
     */
    private fun installCjkFont(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit
    ) {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) return   // 前缀未就绪，下次启动再装
        if (fontMarker(prefix).isFile) return

        // 仅中文/日文/韩文容器安装（英文容器无需 CJK 字体）。
        // v3.3.4（用户问题 4 配套）：按启动链【实际生效语言】判定 ——
        // env["LANG"] 由 buildBaseEnv 计算（显式档 > auto 档读
        // /opt/locale.conf > zh_CN.UTF-8 兜底）。旧逻辑只看 container.lang
        // 原始值：lang=auto 且 /opt 生态为中文时被误判为"非中文容器"而
        // 跳过字体安装 → 中文全方块。现在 auto 档也能正确装字体。
        val lang = (env["LANG"] ?: container.lang).trim().lowercase()
        val needJp = lang.startsWith("ja")
        val needKr = lang.startsWith("ko")
        if (!(lang.startsWith("zh") || needJp || needKr)) return

        // v3.3.37：双源候选 —— 设备 /system/fonts 优先，rootfs-fonts 配方
        // 安装的 NotoSansSC（usr/share/fonts/noto/）兜底（无 /system CJK 的
        // 设备此前直接放弃 → 中文方块）。
        // v3.3.38【APK 构建修复】：src 归一为 **File**。旧代码
        //   `String?.firstOrNull{…} ?: File?.firstNotNullOfOrNull{…}`
        // 的 elvis 推断出 Any?，后续 File(src) 五处全部编译报错
        //   （220/231/241 None of the following functions can be called…
        //    + 245 Unresolved reference: it + 246 return is not allowed here
        //    —— 后两条是 runCatching 块类型崩塌的连带假错）。
        // 同时修复：ROOTFS_FONT_CANDIDATES 的第三项 linbox-cjk 是**目录**，
        // 旧代码 takeIf { it.isFile } 对它永远 false —— 目录候选改为展开
        // 扫描首个字体文件。
        val src: File? = SYS_FONT_CANDIDATES.asSequence()
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?: ROOTFS_FONT_CANDIDATES.asSequence()
                .flatMap { rel ->
                    val f = File(ContainerManager.ROOTFS_ROOT, rel)
                    when {
                        f.isFile -> sequenceOf(f)
                        f.isDirectory -> f.listFiles().orEmpty()
                            .filter { it.isFile && it.extension.lowercase() in FONT_EXTS }
                            .sortedBy { it.name }
                            .asSequence()
                        else -> emptySequence()
                    }
                }
                .firstOrNull()
        if (src == null) {
            log("警告: 设备 /system/fonts 与 rootfs share/fonts 均未找到 CJK 字体——中文将显示为方块（rootfs 重建会随 rootfs-fonts 配方安装 NotoSansSC）")
            return
        }

        val fontsDir = File(prefix, "drive_c/windows/Fonts")
        fontsDir.mkdirs()
        val ext = src.extension.ifEmpty { "ttf" }
        val dstName = "linbox-cjk.$ext"
        val dst = File(fontsDir, dstName)
        // v3.2.24（用户问题 5）：rootfs 侧同步 —— 同一份字体落进
        // rootfs/usr/share/fonts/linbox-cjk/（fontconfig 扫描路径内，
        // wine freetype 后端 / X11 侧中文字形来源；目标已存在即跳过）。
        runCatching {
            val rootfsDir = File(ContainerManager.ROOTFS_ROOT, ROOTFS_CJK_FONT_DIR)
            rootfsDir.mkdirs()
            val rootfsCopy = File(rootfsDir, "linbox-cjk.$ext")
            if (!rootfsCopy.isFile) {
                src.inputStream().use { input ->
                    FileOutputStream(rootfsCopy).use { output -> input.copyTo(output) }
                }
                log("已安装 CJK 字体到 rootfs: ${rootfsCopy.absolutePath}（fontconfig 扫描路径内）")
                runCatching {
                    com.linbox.util.SafeFileOps.deleteTree(File(ContainerManager.ROOTFS_ROOT, "var/cache/fontconfig"))
                }
            }
        }.onFailure { log("警告: CJK 字体安装到 rootfs 失败（${it.message}）—— X11 侧中文可能仍为方块") }
        runCatching {
            src.inputStream().use { input ->
                FileOutputStream(dst).use { output -> input.copyTo(output) }
            }
        }.onFailure {
            log("警告: CJK 字体复制失败（${it.message}）")
            return
        }
        log("已安装 CJK 字体: $src → drive_c/windows/Fonts/$dstName")

        // ---- 注册表：v3.3.40【JSON 打入】→ JsonRegistry 直写（零 wine 进程；
        //      旧链路 v2.2.23 是“生成 .reg + 单次 regedit /s”，v3.2 之前更是
        //      逐键 wine reg add —— 9~13 次进程拉起）----
        val prefixRegDir = File(prefix, "drive_c/linbox")
        prefixRegDir.mkdirs()
        val fontsValues = org.json.JSONArray()
        fun font(name: String) {
            fontsValues.put(
                org.json.JSONObject().put("name", "$name (TrueType)")
                    .put("type", "sz").put("data", dstName)
            )
        }
        font("SimSun"); font("NSimSun"); font("SimHei"); font("Microsoft YaHei")
        font("Microsoft YaHei UI"); font("Microsoft JhengHei")     // 繁中
        if (needJp) { font("MS Gothic"); font("MS PGothic"); font("MS UI Gothic") }
        if (needKr) { font("Malgun Gothic"); font("Gulim") }
        val subValues = org.json.JSONArray()
            .put(org.json.JSONObject().put("name", "MS Shell Dlg").put("type", "sz").put("data", "Microsoft YaHei"))
            .put(org.json.JSONObject().put("name", "MS Shell Dlg 2").put("type", "sz").put("data", "Microsoft YaHei"))
            .put(org.json.JSONObject().put("name", "SimSun-ExtB").put("type", "sz").put("data", "SimSun"))
        val edits = org.json.JSONArray()
            .put(
                org.json.JSONObject()
                    .put("hive", "HKLM")
                    .put("key", "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts")
                    .put("values", fontsValues)
            )
            .put(
                org.json.JSONObject()
                    .put("hive", "HKLM")
                    .put("key", "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes")
                    .put("values", subValues)
            )
        runCatching {
            File(prefixRegDir, "cjk-fonts.json").writeText(edits.toString(2))
        }
        val ok = JsonRegistry.apply(
            File(container.prefixPath), resolved, container, env, edits, log
        ) == null
        if (ok) {
            runCatching { fontMarker(prefix).createNewFile() }
            log("CJK 字体注册完成（SimSun/微软雅黑 等已指向 $dstName；MS Shell Dlg 默认字体替换为微软雅黑）")
        } else {
            log("警告: CJK 字体注册表导入未确认成功（下次启动自动重试）")
            // 不写标记 → 下次启动重试
        }
    }

    // ------------------------------------------------------------------
    // 3) v3.2.23（用户问题 7）：用户自有字体导入
    // ------------------------------------------------------------------

    /**
     * 把 /storage/emulated/0/LinBox/fonts/ 下的用户字体（.ttf/.otf/.ttc）
     * 自动导入双端：
     *
     *  1. **rootfs fontconfig 侧**（X11/freetype 渲染）：复制到
     *     rootfs/usr/share/fonts/linbox-user/（系统级目录，任何 uid/
     *     配置都能扫到），指纹变更时同步清 fontconfig 缓存目录；
     *  2. **wine 前缀侧**（GDI 渲染）：复制进 drive_c/windows/Fonts
     *     并按文件名写注册表（REGEDIT4 单次导入，缺什么补什么）。
     *
     * 幂等：rootfs 侧按“文件名:长度”指纹（marker 比对，命中零拷贝）；
     * 前缀侧按目标文件存在性 + 单次 regedit 覆盖导入。目录不存在/
     * 为空静默跳过（无字体时回落内置 CJK 方案）。
     */
    private fun importUserFonts(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit
    ) {
        // v3.2.24（用户问题 5）：主目录 = rootfs/usr/share/fonts/linbox-user
        // （rootfs 内、fontconfig 扫描路径直接可见）；旧
        // /storage/emulated/0/LinBox/fonts 作为兼容源合并扫描。
        val fonts = listOf(
            File(ContainerManager.ROOTFS_ROOT, USER_FONT_DIR),
            File(LEGACY_USER_FONT_DIR)
        ).filter { it.isDirectory }
            .flatMap { dir ->
                dir.listFiles { f ->
                    f.isFile && f.extension.lowercase() in setOf("ttf", "otf", "ttc")
                }.orEmpty().toList()
            }
            .distinctBy { "${it.name}:${it.length()}" }
            .sortedBy { it.name }
        if (fonts.isEmpty()) return

        // ---- 1) rootfs fontconfig 侧（v3.2.24：主目录内文件本就直接
        // 处于扫描路径；此处仅负责把【旧存储目录】的字体并进来 + 指纹
        // 变更时清理已删除的字体与 fontconfig 缓存）----
        val rootfsFontsDir = File(ContainerManager.ROOTFS_ROOT, USER_FONT_DIR)
        val marker = File(rootfsFontsDir, ".linbox-user-fp")
        val fp = fonts.joinToString("|") { "${it.name}:${it.length()}" }
        if (!(marker.isFile && runCatching { marker.readText() }.getOrNull() == fp)) {
            runCatching {
                rootfsFontsDir.mkdirs()
                fonts.forEach {
                    val dst = File(rootfsFontsDir, it.name)
                    // 同文件守卫：主目录内的字体 copyTo 自身会把文件清空
                    //（File.copyTo 无同路径保护，输出流打开即截断）
                    if (it.absolutePath != dst.absolutePath) {
                        it.copyTo(dst, overwrite = true)
                    }
                }
                // 指纹变更：清掉旧字体与 fontconfig 缓存（下次渲染重建）
                rootfsFontsDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name != ".linbox-user-fp" &&
                        fonts.none { it.name == f.name }) f.delete()
                }
                runCatching {
                    com.linbox.util.SafeFileOps.deleteTree(File(ContainerManager.ROOTFS_ROOT, "var/cache/fontconfig"))
                }
                marker.writeText(fp)
                log("已导入用户字体 ×${fonts.size} → rootfs fontconfig（$USER_FONT_DIR → ${rootfsFontsDir.absolutePath}）")
            }.onFailure { log("警告: 用户字体导入 rootfs 失败（${it.message}）—— 忽略继续") }
        }

        // ---- 2) wine 前缀侧（GDI） ----
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) return   // 前缀未就绪，下次启动补
        val fontsDir = File(prefix, "drive_c/windows/Fonts")
        fontsDir.mkdirs()
        val missing = fonts.filter { !File(fontsDir, it.name).isFile }
        runCatching {
            missing.forEach { it.copyTo(File(fontsDir, it.name)) }
        }.onFailure {
            log("警告: 用户字体复制进前缀失败（${it.message}）")
            return
        }
        // 注册表：v3.3.40【JSON 打入】→ JsonRegistry 直写（零 wine 进程）
        // —— 按文件名（去扩展名）注册，wine GDI 按注册表枚举字体
        val regDir = File(prefix, "drive_c/linbox")
        regDir.mkdirs()
        val values = org.json.JSONArray()
        fonts.forEach { f ->
            values.put(
                org.json.JSONObject().put("name", "${f.nameWithoutExtension} (TrueType)")
                    .put("type", "sz").put("data", f.name)
            )
        }
        val edits = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("hive", "HKLM")
                .put("key", "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts")
                .put("values", values)
        )
        runCatching {
            File(regDir, "user-fonts.json").writeText(edits.toString(2))
        }
        val ok = JsonRegistry.apply(
            File(container.prefixPath), resolved, container, env, edits, log
        ) == null
        if (ok) {
            log("用户字体已注册进 wine（×${fonts.size}：${fonts.joinToString(", ") { it.name }}）")
        } else {
            log("警告: 用户字体注册表导入未确认成功（下次启动自动重试）")
        }
    }
}
