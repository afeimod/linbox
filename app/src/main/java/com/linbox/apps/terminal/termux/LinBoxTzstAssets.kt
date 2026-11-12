package com.linbox.apps.terminal.termux

import android.content.Context
import android.system.Os
import com.termux.x11.controller.core.TarCompressorUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * LinBox 内置 tzst 资产自解压（v2.26 · assets 自解压引擎）。
 *
 * ------------------------------------------------------------------
 * 资产契约（APK 内 assets/linbox/ 目录，全部为 tzst 格式 = tar + zstd，
 * 同时兼容 .tar.zst 后缀；CI 构建产物改名后放入即可）：
 *
 *   assets/linbox/rootfs-*.tzst   bionic rootfs（data/data 布局或 usr/ 布局均可）
 *   assets/linbox/mesa-*.tzst     Mesa GL（libGL/EGL/gbm + dri/ + freedreno/turnip ICD）
 *   assets/linbox/box64-*.tzst    box64（usr/local/bin/box64 或 bin/box64 布局）
 *   assets/linbox/turnip-*.tzst   Turnip Vulkan 驱动（usr/lib + share/vulkan/icd.d）
 *   assets/linbox/dxvk-*.tzst     DXVK（顶层 system32/ + syswow64/ 布局；
 *                                 兼容上游 x64/ x32|x86/ 布局自动重映射）
 *
 * 解压目标（与 rootfs 侧 linbox-container / 终端侧 glibc-runner 双端契约一致）：
 *   rootfs 根 : /data/data/com.linbox/files/rootfs
 *     - rootfs / mesa / box64 / turnip 四类资产按成员路径重映射后落位；
 *     - CI 发行包的 data/data/com.linbox/files/rootfs 前缀成员自动剥壳；
 *     - usr/、etc/、home/ 等相对布局直接落入 rootfs 根；
 *     - com.termux 旧路径成员自动改写为 com.linbox（等长重写语义）。
 *
 *   wine 前缀  : <WINEPREFIX>/drive_c/windows
 *     - DXVK 资产【前缀构建后】才解压（system.reg + drive_c/windows 存在
 *       判定前缀已构建；未构建时等待下次调用，绝不预创建）；
 *     - 用户资产为顶层 system32/ + syswow64/ 布局：直接解压进
 *       drive_c/windows，DLL 各就各位（不是 x32/x64 目录样式）；
 *     - 上游 DXVK 发布包（x64/ + x32|x86/）自动重映射：
 *       x64 → system32，x32/x86 → syswow64；
 *     - 裸 *.dll 成员（无目录布局）一律按 64 位落入 system32。
 *
 * 幂等标记（fingerprint = 资产名 + 字节长度 + CRC；资产不变不重复解压）：
 *   rootfs 侧 : <rootfs>/usr/etc/linbox/.tzst-assets-fingerprint
 *               （随 rootfs 重建自动失效 → 换 rootfs 后资产自动重铺）
 *   dxvk 侧   : <prefix>/linboxmeta/.linbox-dxvk-applied
 *               （与 glibc-runner [F5] 消费同一标记名，双端互认）
 *
 * 解压引擎复用 termux-x11 模块的 TarCompressorUtils
 * （Apache Commons Compress + zstd-jni，纯 Java 实现，不依赖设备 tar/zstd）。
 */
object LinBoxTzstAssets {

    private const val TAG = "LinBoxTzstAssets"

    /** APK 内资产目录（rootfs/mesa/box64/turnip/dxvk tzst 契约目录）。 */
    const val ASSET_DIR = "linbox"

    /** v3.2.14（用户问题 3）：DXVK 资产专用目录 —— assets/dxvk/。
     *  用户约定放置 dxvk-<ver>.tar.gz（如 dxvk-2.3.1.tar.gz）；
     *  兼容 .tgz / .tzst / .tar.zst 后缀，也兼容 assets/linbox/ 下的
     *  旧契约 dxvk-*.tzst（两处并存时合并应用）。 */
    const val DXVK_ASSET_DIR = "dxvk"

    /** bionic rootfs 根（与 ContainerManager.ROOTFS_ROOT 逐字一致）。 */
    const val ROOTFS_ROOT = "/data/data/com.linbox/files/rootfs"

    /** glibc 前缀根（glibc-runner 侧 WINEPREFIX 默认值所在）。 */
    private const val GLIBC_PREFIX = "/data/data/com.linbox/files/usr/glibc"

    /** 资产指纹文件（rootfs 侧）。 */
    private fun fingerprintFile(): File =
        File("$ROOTFS_ROOT/usr/etc/linbox/.tzst-assets-fingerprint")

    /** dxvk 待应用状态（Compose/界面可订阅；true = 本次调用已全部就位）。 */
    private val _dxvkReady = MutableStateFlow(false)
    val dxvkReady: StateFlow<Boolean> = _dxvkReady

    // ------------------------------------------------------------------
    // 资产扫描
    // ------------------------------------------------------------------

    /** tzst 资产分类（按文件名关键词识别，容错 .tzst / .tar.zst 两种后缀）。 */
    private enum class Kind(val keyword: String) {
        ROOTFS("rootfs"), MESA("mesa"), BOX64("box64"),
        TURNIP("turnip"), DXVK("dxvk");

        companion object {
            // 关键词命中顺序 = 特异性优先（dxvk/turnip 等具体件名先于
            // rootfs/mesa 等泛名；"linbox-mesa-turnip" 类罕见命名归 turnip）
            private val ORDER = listOf(DXVK, TURNIP, BOX64, MESA, ROOTFS)

            /**
             * 命中规则：文件名含关键词即归类。同时兼容
             *  - 简短命名: rootfs-xxx.tzst / dxvk-2.4.tar.zst
             *  - CI 产物原名: linbox-bionic-rootfs-20260919-aarch64.tzst、
             *    linbox-mesa-25.1-aarch64-bionic.tzst、linbox-box64-v0.2.9.tzst
             */
            fun of(name: String): Kind? {
                val lower = name.lowercase()
                return ORDER.firstOrNull { lower.contains(it.keyword) }
            }
        }
    }

    private fun isTzst(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".tzst") || lower.endsWith(".tar.zst")
    }

    /** 扫描 assets/linbox/ 下的 tzst 资产 → 分类列表（稳定排序）。 */
    private fun scanAssets(context: Context): Map<Kind, List<String>> {
        val result = mutableMapOf<Kind, MutableList<String>>()
        try {
            val names = context.assets.list(ASSET_DIR) ?: return result
            for (name in names) {
                if (!isTzst(name)) continue
                val kind = Kind.of(name) ?: continue
                result.getOrPut(kind) { mutableListOf() }.add(name)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "扫描 ${ASSET_DIR}/ 资产失败: ${e.message}")
        }
        return result
    }

    private fun assetPath(name: String): String = "$ASSET_DIR/$name"

    /** v3.2.14：DXVK 资产描述（相对 APK assets 的完整路径 + 压缩类型）。 */
    data class DxvkAsset(val dir: String, val name: String, val type: TarCompressorUtils.Type) {
        val path: String get() = "$dir/$name"
    }

    /** DXVK 资产压缩类型探测（tar.gz 优先支持 —— 用户约定格式）。
     *  v3.2.15：① 补 .tar.xz/.txz → XZ；② 补纯 .tar → PLAIN。
     *  v3.2.23：本函数只作“后缀启发”；真实类型在 scanDxvkAssets 里
     *  由魔数校验（[sniffType]）最终裁定 —— 三方重打包/本地工具链
     *  可能把 .tar.gz 剥成 .tar 或反向，内容才是唯一可信依据。 */
    private fun dxvkAssetType(name: String): TarCompressorUtils.Type? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TarCompressorUtils.Type.GZIP
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> TarCompressorUtils.Type.XZ
            lower.endsWith(".tzst") || lower.endsWith(".tar.zst") -> TarCompressorUtils.Type.ZSTD
            lower.endsWith(".tar") -> TarCompressorUtils.Type.PLAIN
            else -> null
        }
    }

    /**
     * v3.2.23：魔数直判资产真实压缩类型（后缀不可信时的最终裁定）。
     *
     * 实证背景：与项目同版 aapt2（8.5.2-11315950）link -A / optimize
     * 全链实测原名原字节保留，但用户本地工具链或云端重打包仍可能把
     * gzip 层剥掉（名字同步去掉 .gz）——此时按后缀选 GZIP 解压必炸。
     * 这里只读前 263 字节：
     *   - gzip: 1F 8B
     *   - xz:   FD 37 7A 58 5A 00
     *   - zstd: 28 B5 2F FD
     *   - 纯 tar: 偏移 257 起 "ustar"（POSIX/GNU tar 均满足）
     * 读不出来/都不匹配 → null（调用方回退后缀推断）。
     */
    private fun sniffType(context: Context, path: String): TarCompressorUtils.Type? {
        return try {
            context.assets.open(path).use { ins ->
                val head = ByteArray(263)
                var n = 0
                while (n < head.size) {
                    val r = ins.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                when {
                    n >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() ->
                        TarCompressorUtils.Type.GZIP
                    n >= 6 && head[0] == 0xfd.toByte() && head[1] == 0x37.toByte() &&
                        head[2] == 0x7a.toByte() && head[3] == 0x58.toByte() &&
                        head[4] == 0x5a.toByte() && head[5] == 0x00.toByte() ->
                        TarCompressorUtils.Type.XZ
                    n >= 4 && head[0] == 0x28.toByte() && head[1] == 0xb5.toByte() &&
                        head[2] == 0x2f.toByte() && head[3] == 0xfd.toByte() ->
                        TarCompressorUtils.Type.ZSTD
                    n >= 262 && String(head, 257, 5, Charsets.ISO_8859_1) == "ustar" ->
                        TarCompressorUtils.Type.PLAIN
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 后缀启发 + 魔数裁定的合成判定：魔数优先，后缀兜底。 */
    private fun resolveAssetType(context: Context, dir: String, name: String): TarCompressorUtils.Type? {
        val hinted = dxvkAssetType(name) ?: return null
        return sniffType(context, "$dir/$name") ?: hinted
    }

    /**
     * v3.2.14：扫描全部 DXVK 资产（assets/dxvk/ 新约定 + assets/linbox/dxvk-*.tzst
     * 旧契约），按文件名稳定排序。空列表 = 未内置任何 DXVK。
     */
    fun scanDxvkAssets(context: Context): List<DxvkAsset> {
        val out = mutableListOf<DxvkAsset>()
        try {
            context.assets.list(DXVK_ASSET_DIR)?.forEach { name ->
                resolveAssetType(context, DXVK_ASSET_DIR, name)
                    ?.let { out.add(DxvkAsset(DXVK_ASSET_DIR, name, it)) }
            }
        } catch (_: Exception) {
            // 目录不存在（未内置任何资产）：正常路径，不扫日志
        }
        try {
            // v3.2.15：旧契约目录 assets/linbox/ 也按后缀自适应识别
            // （此前只认 .tzst；现在 dxvk-*.tar.gz/.tar.xz/.tar 同样可用）
            // v3.2.23：类型判定改走魔数裁定（resolveAssetType）
            context.assets.list(ASSET_DIR)?.forEach { name ->
                if (Kind.of(name) == Kind.DXVK) {
                    resolveAssetType(context, ASSET_DIR, name)
                        ?.let { out.add(DxvkAsset(ASSET_DIR, name, it)) }
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedBy { it.name }
    }

    /** 资产指纹（路径+长度+CRC32），资产更新后自动重铺。 */
    private fun assetFingerprint(context: Context, path: String): String {
        var len = 0L
        var crc = 0L
        val buf = ByteArray(64 * 1024)
        try {
            context.assets.open(path).use { input ->
                val crc32 = java.util.zip.CRC32()
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    crc32.update(buf, 0, n)
                    len += n
                }
                crc = crc32.value
            }
        } catch (e: Exception) {
            return "err:${path}:${e.message}"
        }
        return "$path:$len:$crc"
    }

    // ------------------------------------------------------------------
    // 成员路径重映射（CI data/data 布局 → rootfs 根）
    // ------------------------------------------------------------------

    private val rootfsMembersPrefix = "data/data/com.linbox/files/rootfs/"
    private val legacyMembersPrefix = "data/data/com.termux/files/rootfs/"

    /**
     * LinBox 打包约定前缀：rootfs-*.tzst 内部目录为 rootfs/
     * （tar 成员形如 rootfs/usr/...），部署时剥掉这一层直接落
     * /data/data/com.linbox/files/rootfs/。
     */
    private const val PACKED_ROOTFS_DIR_PREFIX = "rootfs/"

    /**
     * tar 成员路径 → rootfs 根内真实落点。
     *  - data/data/com.linbox|com.termux/files/rootfs/xxx → rootfs/xxx
     *    （CI 发行包布局）
     *  - rootfs/xxx → rootfs/xxx（LinBox 打包约定：tzst 内部目录为 rootfs）
     *  - 其余（usr/ etc/ home/ bin/ lib/ ...）→ rootfs/<原路径>
     *    （相对布局直落 rootfs 根）
     * 返回 null 表示目标越界（../ 逃逸防护），由调用方跳过。
     */
    private fun remapToRootfs(entryName: String): File? {
        val name = entryName.trimStart('.', '/')
        val relative = when {
            name.startsWith(rootfsMembersPrefix) -> name.removePrefix(rootfsMembersPrefix)
            name.startsWith(legacyMembersPrefix) -> name.removePrefix(legacyMembersPrefix)
            name.startsWith(PACKED_ROOTFS_DIR_PREFIX) -> name.removePrefix(PACKED_ROOTFS_DIR_PREFIX)
            name == "data/data/com.linbox/files/rootfs" ||
                name == "data/data/com.termux/files/rootfs" -> ""
            name == "rootfs" -> ""
            else -> name
        }
        if (relative.isEmpty()) return null            // 根目录条目自身：跳过
        if (relative.contains("..")) return null       // 路径逃逸防护
        val root = File(ROOTFS_ROOT)
        val f = File(root, relative)
        // 双重保险：规范化后必须仍在 rootfs 根内
        val canonicalRoot = root.canonicalPath + File.separator
        if (!f.canonicalPath.startsWith(canonicalRoot) && f.canonicalPath != root.canonicalPath) {
            return null
        }
        return f
    }

    // ------------------------------------------------------------------
    // bionic 资产铺装（rootfs / mesa / box64 / turnip）
    // ------------------------------------------------------------------

    /**
     * 扫描并解压全部 bionic 侧 tzst 资产到 rootfs。
     * 幂等：指纹未变化的资产跳过；rootfs 被重建（指纹文件消失）后自动重铺。
     * 耗时操作（rootfs 可达数百 MB），必须在后台线程调用。
     *
     * @return 实际执行了解压的资产数（0 = 全部已就位）
     */
    fun installBionicAssetsIfNeeded(context: Context): Int {
        val assets = scanAssets(context)
        val bionic = listOf(Kind.ROOTFS, Kind.MESA, Kind.BOX64, Kind.TURNIP)
        if (bionic.none { assets[it]?.isNotEmpty() == true }) return 0

        val fpFile = fingerprintFile()
        val oldFps = try {
            if (fpFile.isFile) fpFile.readText().lines().filter { it.isNotBlank() }.toSet()
            else emptySet()
        } catch (_: Exception) { emptySet() }

        val newFps = mutableSetOf<String>()
        var applied = 0
        for (kind in bionic) {
            for (name in assets[kind].orEmpty().sorted()) {
                val fp = assetFingerprint(context, assetPath(name))
                newFps.add(fp)
                if (fp in oldFps) continue   // 指纹一致：已铺装，跳过
                val ok = extractToRootfs(context, name, kind)
                if (ok) {
                    applied++
                    android.util.Log.i(TAG, "tzst 资产已铺装: $name → $ROOTFS_ROOT")
                } else {
                    android.util.Log.e(TAG, "tzst 资产解压失败: $name（保留指纹空缺，下次启动重试）")
                    newFps.remove(fp)
                }
            }
        }
        if (applied > 0 || (oldFps != newFps && newFps.isNotEmpty())) {
            try {
                fpFile.parentFile?.mkdirs()
                fpFile.writeText(newFps.sorted().joinToString("\n") + "\n")
                Os.chmod(fpFile.absolutePath, 420)   // 0644
            } catch (e: Exception) {
                android.util.Log.w(TAG, "指纹写入失败: ${e.message}")
            }
        }
        return applied
    }

    private fun extractToRootfs(context: Context, name: String, kind: Kind): Boolean {
        val root = File(ROOTFS_ROOT)
        root.mkdirs()
        return try {
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context, assetPath(name), root
            ) { destination, _ ->
                // TarCompressorUtils 传入 File(destinationDir, entryName)；
                // 成员路径重映射：剥 CI 前缀 / 旧包名改写，越界成员跳过
                val entryRel = destination.path.removePrefix(root.path + "/")
                remapToRootfs(entryRel)
            }
        } catch (t: Throwable) {
            // Throwable 而非 Exception：UnsatisfiedLinkError 等 Error 级故障
            // （如 zstd native 库缺失/不兼容）不得穿透导致进程闪退；
            // 返回 false → 指纹空缺 → 下次启动自动重试
            android.util.Log.e(TAG, "解压异常: $name: ${t.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // DXVK —— 前缀构建后的解压（核心需求）
    // ------------------------------------------------------------------

    /** 前缀已构建判定：system.reg 存在（wineboot 产物，与 glibc-runner 同判据）。 */
    private fun prefixBuilt(prefix: File): Boolean =
        File(prefix, "system.reg").isFile && File(prefix, "drive_c").isDirectory

    /**
     * 向所有已构建的 wine 前缀应用内置 DXVK 资产。
     *
     * "前缀构建后的解压"：本函数只处理 system.reg + drive_c 已就绪的前缀
     * （bionic 容器前缀 + glibc 默认前缀）；未构建的前缀跳过，等下次调用
     * （App 启动 / 进入容器界面）自动补齐。绝不预创建前缀或 drive_c。
     *
     * 落位规则（用户资产 = 顶层 system32/ + syswow64/ 布局，直接解压；
     * 非 x32/x64 目录样式）：
     *   system32 目录 → drive_c/windows/system32/   （64 位 DLL）
     *   syswow64 目录 → drive_c/windows/syswow64/   （32 位 DLL）
     * 兼容上游发布布局自动重映射：
     *   x64 目录      → system32
     *   x32、x86 目录 → syswow64
     *   裸 *.dll      → system32（视为 64 位）
     * ⚠ Kotlin 注释支持嵌套：注释文本里禁止出现 "斜杠+星号" 序列
     *   （会被 lexer 当作子注释开启，导致整段注释永不闭合）。
     *
     * 幂等标记: <prefix>/linboxmeta/.linbox-dxvk-applied（glibc-runner [F5]
     * 同名互认）；标记存在且指纹未变 → 跳过。资产更新（指纹变化）→ 重铺。
     *
     * @return 本次实际应用了 DXVK 的前缀数
     */
    fun applyDxvkWhenPrefixReady(context: Context): Int {
        val dxvkAssets = scanDxvkAssets(context)
        if (dxvkAssets.isEmpty()) {
            _dxvkReady.value = false
            return 0
        }

        // 指纹比对：全部 dxvk 资产的指纹串（单资产场景零开销）
        val combinedFp = dxvkAssets.joinToString("|") { assetFingerprint(context, it.path) }

        var appliedCount = 0
        for (prefix in knownPrefixes()) {
            if (!prefixBuilt(prefix)) continue          // ★ 前缀未构建：等待下次
            // v3.2.14（用户问题 3）：bionic 容器按 container.conf 的 dxvk=
            // 过滤 —— "off" = 用户明确关闭，不注入（glibc 前缀无 conf，
            // 保持历史全量注入行为）
            if (containerDxvkDisabled(prefix)) continue
            // v3.3.28：按容器 backend 推导 wine 形态（arm64ec/hangover =
            // aarch64 wine → system32 用 arm64ec 原生档；box64/fexcore =
            // x86_64 wine，旧行为）—— 与 RootfsLauncher 前缀构建链同源。
            val arch = prefixWineArch(prefix)
            val fp = if (arch.isEmpty()) combinedFp else "$combinedFp@arch=$arch"
            if (applyDxvkAssetsToPrefix(context, prefix, dxvkAssets, fp, arch)) {
                appliedCount++
            }
        }
        _dxvkReady.value = appliedCount == 0
        return appliedCount
    }

    /**
     * v3.3.28：从前缀所属容器的 container.conf 读 backend= 推导 wine 形态。
     * "arm64ec" → aarch64（hangover/native arm64 wine）；其余（box64/
     * fexcore/缺键）→ x86_64。conf 缺失（glibc 前缀）返回空串 = 旧行为。
     */
    private fun prefixWineArch(prefix: File): String {
        val conf = File(prefix.parentFile, "container.conf")
        if (!conf.isFile) return ""
        return try {
            val backend = conf.readLines()
                .firstOrNull { it.trim().startsWith("backend=") }
                ?.substringAfter("=")?.trim()?.lowercase() ?: ""
            if (backend == "arm64ec") "aarch64" else "x86_64"
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * v3.2.14：bionic 容器的 DXVK 开关判定 —— 读容器目录内 container.conf
     * 的 dxvk= 值（轻量自解析，不依赖容器数据类）。
     * "off" = 明确关闭；缺键/其他值（auto/版本戳）= 开启。
     */
    private fun containerDxvkDisabled(prefix: File): Boolean {
        val conf = File(prefix.parentFile, "container.conf")
        if (!conf.isFile) return false
        return try {
            conf.readLines().any { raw ->
                val line = raw.trim()
                line.startsWith("dxvk=") && line.substring(5).trim() == "off"
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * v3.2.14（用户问题 3）：把内置 DXVK 资产定向注入【单个容器前缀】。
     *
     * 由 RootfsLauncher 在 wineboot 构建/启动链末尾调用（用户语义
     * "构建前缀时自动打入 assets 内的 dxvk-<ver>.tar.gz 解压到 C 盘
     * windows"）；也可从容器管理器手动触发。
     *
     * v3.3.21：新增 wineArch 参数 —— DXVK 2.7+（hangover 官方配套包）
     * 发布包内含 x64/x32/aarch64/arm64ec 四套架构目录：
     *   - aarch64（arm64ec/hangover）wine → system32 取 arm64ec/（原生
     *     ARM64EC 代码，x86_64 客体经 thunk 直调，不经 FEX 翻译），
     *     syswow64 取 x32/（32 位客体仍 FES，但 DXVK 2.7.1 比 2.3.1
     *     新两年、d3d9 修复数百项）；
     *   - x86_64（box64）wine → system32 取 x64/，syswow64 取 x32/。
     * 旧版不看 wine 架构一律 x64→system32，arm64ec 容器的 system32
     * 装进 x86_64 DLL（整条链全 FEX 翻译，慢且撞 FEX 边缘 bug ——
     * 用户实测 PAL4 DXVK 闪退而 wined3d（原生 builtin）正常的根因）。
     *
     * @param containerPrefix 容器的 WINEPREFIX 目录（…/containers/<name>/prefix）
     * @param dxvkEnabled     容器配置开关（container.conf 的 dxvk= 非 off）
     * @param wineArch        wine 形态（"aarch64" / "x86_64"；空 = 旧兼容：
     *                        仅 x64/x32 布局时行为与旧版一致）
     * @param log            进度回调（写入前缀日志窗口）
     * @return true = 已应用/无需应用；false = 解压失败（下次自动重试）
     */
    fun applyDxvkToContainer(
        context: Context,
        containerPrefix: File,
        dxvkEnabled: Boolean,
        wineArch: String = "",
        log: (String) -> Unit = {}
    ): Boolean {
        if (!dxvkEnabled) return true
        if (!prefixBuilt(containerPrefix)) return true   // 未构建：留给构建后
        val dxvkAssets = scanDxvkAssets(context)
        if (dxvkAssets.isEmpty()) {
            log("提示: APK 未内置 DXVK 资产（assets/dxvk/dxvk-<ver>.tar.gz），跳过注入")
            return true
        }
        // v3.2.15：资产后缀兼容报告（帮助用户发现构建管线把 .tar.gz 剥成 .tar 的情况）
        val stripped = dxvkAssets.filter { it.name.endsWith(".tar") }
        if (stripped.isNotEmpty()) {
            log("提示: 检测到未压缩 .tar 资产（${stripped.joinToString { it.name }}）—— 构建管线剥掉了压缩层，已按纯 tar 正常解压")
        }
        // v3.3.21：指纹叠加 wineArch —— 容器换架构形态（换 wine）时
        // 自动重铺正确架构的 DLL（旧指纹只含资产内容，换形态不重铺）
        val combinedFp = dxvkAssets.joinToString("|") { assetFingerprint(context, it.path) } +
            "@arch=$wineArch"
        if (!applyDxvkAssetsToPrefix(context, containerPrefix, dxvkAssets, combinedFp, wineArch, log)) {
            log("警告: DXVK 解压失败（下次启动自动重试）")
            return false
        }
        return true
    }

    /** 单个前缀的 DXVK 注入核心（指纹幂等 + 解压 + 版本戳回写）。 */
    private fun applyDxvkAssetsToPrefix(
        context: Context,
        prefix: File,
        dxvkAssets: List<DxvkAsset>,
        combinedFp: String,
        wineArch: String = "",
        log: (String) -> Unit = {}
    ): Boolean {
        val meta = File(prefix, "linboxmeta")
        val mark = File(meta, ".linbox-dxvk-applied")
        if (mark.isFile) {
            val prev = try { mark.readText().trim() } catch (_: Exception) { "" }
            if (prev == combinedFp) return true       // 已应用且资产未变
        }
        val windows = File(prefix, "drive_c/windows")
        var ok = true
        for (asset in dxvkAssets) {
            if (!extractDxvkToWindows(context, asset, windows, wineArch)) ok = false
        }
        if (ok) {
            try {
                meta.mkdirs()
                mark.writeText(combinedFp)
                Os.chmod(mark.absolutePath, 420)
                stampContainerDxvk(prefix, dxvkAssets.last().name)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "dxvk 标记写入失败: ${e.message}")
            }
            android.util.Log.i(TAG, "DXVK 已解压到前缀: ${prefix.path} → drive_c/windows")
            log("DXVK ${dxvkAssets.last().name} 已解压到 C:\\windows（system32/syswow64" +
                (if (wineArch == "aarch64") ", arm64ec 原生档" else "") + "）")
        }
        return ok
    }

    /** 已知的 wine 前缀集合（bionic 容器前缀 + glibc 默认前缀）。 */
    private fun knownPrefixes(): List<File> {
        val out = mutableListOf<File>()
        // bionic 容器前缀: rootfs/home/.linbox/containers/<name>/prefix
        val contRoot = File("$ROOTFS_ROOT/home/.linbox/containers")
        contRoot.listFiles()?.forEach { c ->
            val p = File(c, "prefix")
            if (p.isDirectory) out.add(p)
        }
        // glibc 默认前缀（glibc-runner WINEPREFIX 缺省值）
        out.add(File("$GLIBC_PREFIX/opt/prefix"))
        return out
    }

    /**
     * DXVK tzst → drive_c/windows。
     * 两段式：先解到 linboxmeta/.dxvk-stage 暂存目录，按顶层布局归位后清理。
     * （tar 成员布局在解压前不可知，暂存 + 归位可同时支持
     * system32/syswow64 直解与 x64/x32 重映射，且不污染 windows 目录。）
     */
    private fun extractDxvkToWindows(
        context: Context, asset: DxvkAsset, windows: File, wineArch: String = ""
    ): Boolean {
        val prefix = windows.parentFile?.parentFile ?: return false
        val stage = File(prefix, "linboxmeta/.dxvk-stage")
        try {
            com.linbox.util.SafeFileOps.deleteTree(stage)
            stage.mkdirs()
            val extracted = TarCompressorUtils.extract(
                asset.type, context, asset.path, stage
            )
            if (!extracted) {
                android.util.Log.e(TAG, "DXVK 解压失败: ${asset.name}")
                return false
            }
            return installStagedDxvk(stage, windows, wineArch)
        } catch (t: Throwable) {
            // 同 extractToRootfs：Error 级故障降级为日志，不穿透
            android.util.Log.e(TAG, "DXVK 解压异常: ${asset.name}: ${t.message}")
            return false
        } finally {
            com.linbox.util.SafeFileOps.deleteTree(stage)
        }
    }

    /**
     * v3.3.21：/opt/dxvk1 tar 槽位包 → drive_c/windows（布局感知 + 架构感知）。
     *
     * 旧版启动链对 dxvk tar 槽位直接 extractTarTo(prefixWin) —— 对上游
     * DXVK 发布包（x64/x32/aarch64/arm64ec 目录布局）会把四个架构目录
     * 原样倒进 C:\windows（windows/x64/…、windows/arm64ec/…），DLL 一个
     * 都不在位，等于“装了但没装”。现走与 APK 资产同款的暂存 + 布局
     * 归位（RootfsLauncher 步骤 0 的 DXVK 分支改调本函数）。
     */
    fun installDxvkTarToWindows(
        tarFile: File, windows: File, wineArch: String = "",
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = windows.parentFile?.parentFile ?: return false
        val stage = File(prefix, "linboxmeta/.dxvk-stage-opt")
        try {
            com.linbox.util.SafeFileOps.deleteTree(stage)
            stage.mkdirs()
            val err = com.linbox.apps.containers.GuanliSupport.extractTarTo(
                tarFile.absolutePath, stage
            ) { log(it) }
            if (err != null) {
                log("⚠ DXVK 槽位包解压失败：$err")
                return false
            }
            return installStagedDxvk(stage, windows, wineArch)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "DXVK 槽位包安装异常: ${tarFile.name}: ${t.message}")
            return false
        } finally {
            com.linbox.util.SafeFileOps.deleteTree(stage)
        }
    }

    /** 暂存目录 → drive_c/windows 的布局归位（v3.3.21：架构感知）。 */
    private fun installStagedDxvk(stage: File, windows: File, wineArch: String = ""): Boolean {
        val system32 = File(windows, "system32")
        val syswow64 = File(windows, "syswow64")

        // 布局根：上游发布包常有单根目录包装（如 dxvk-2.4/x64），
        // 沿"仅含单个子目录且无散文件"的链路下钻到真实布局层
        val root = layoutRoot(stage)

        // 顶层布局探测（忽略深层嵌套的 repack 包根目录）
        val topDirs = root.listFiles()?.filter { it.isDirectory }?.map { it.name.lowercase() } ?: emptyList()
        val hasNativeLayout = topDirs.contains("system32") || topDirs.contains("syswow64")
        val hasUpstreamLayout = topDirs.contains("x64") || topDirs.contains("x32") || topDirs.contains("x86")
        // v3.3.21：DXVK 2.7+ 上游多架构布局（hangover 官方 dxvk-v2.7.1
        // 同款：x64/x32/aarch64/arm64ec 四目录）—— 按 wine 形态选档
        val hasMultiArchLayout = topDirs.contains("arm64ec") || topDirs.contains("aarch64")

        // v3.3.28（补全 v3.3.21 未竟逻辑）：wineArch 真正参与选档 ——
        //   arm64ec/hangover（aarch64 形态）wine 的 system32 装 arm64ec/
        //   原生 DLL（x86_64 客体经 ARM64EC thunk 直调，不经 FEX 翻译；
        //   无 arm64ec 档时退 aarch64/，再退 x64/）；syswow64 一律 x32/
        //   （32 位客体仍 FEX，但选最新 DXVK 档）。
        //   box64/fexcore（x86_64 形态）：system32=x64/（旧行为不变）。
        val wantArm64ec = wineArch.equals("aarch64", ignoreCase = true)

        return try {
            when {
                // 多架构布局（x64/x32/aarch64/arm64ec）+ arm64ec 形态：
                // system32 装原生档（v3.3.21 设计语义在此兑现）
                hasMultiArchLayout && wantArm64ec -> {
                    val sys32Src = listOf("arm64ec", "aarch64")
                        .firstOrNull { File(root, it).isDirectory } ?: "x64"
                    copyTree(File(root, sys32Src), system32)
                    listOf("x32", "x86").firstOrNull { File(root, it).isDirectory }
                        ?.let { copyTree(File(root, it), syswow64) }
                    true
                }
                // 用户样式：顶层 system32/ + syswow64/ → 直接归位
                hasNativeLayout -> {
                    File(root, "system32").takeIf { it.isDirectory }
                        ?.let { copyTree(it, system32) }
                    File(root, "syswow64").takeIf { it.isDirectory }
                        ?.let { copyTree(it, syswow64) }
                    // 根级散置 dll（如有）→ system32
                    root.listFiles()?.filter { it.isFile && it.name.lowercase().endsWith(".dll") }
                        ?.forEach { copyFileTo(it, system32) }
                    true
                }
                // 上游样式：x64/ + x32|x86/ → 重映射到 system32 / syswow64
                hasUpstreamLayout -> {
                    listOf("x64").forEach {
                        File(root, it).takeIf { d -> d.isDirectory }?.let { d -> copyTree(d, system32) }
                    }
                    listOf("x32", "x86").forEach {
                        File(root, it).takeIf { d -> d.isDirectory }?.let { d -> copyTree(d, syswow64) }
                    }
                    true
                }
                // 无目录布局：裸 dll 全集 → system32（按 64 位处理）
                else -> {
                    var any = false
                    root.walkTopDown().filter { it.isFile && it.name.lowercase().endsWith(".dll") }
                        .forEach { copyFileTo(it, system32); any = true }
                    any
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "DXVK 归位失败: ${e.message}")
            false
        }
    }

    /**
     * 布局根下钻：目录仅含"一个子目录、零散文件"时进入该子目录，
     * 直到出现文件或多个子目录（system32+syswow64 / x64+x32 即停）。
     */
    private fun layoutRoot(start: File): File {
        var dir = start
        while (true) {
            val children = dir.listFiles() ?: return dir
            val dirs = children.filter { it.isDirectory }
            val hasFiles = children.any { it.isFile }
            if (hasFiles || dirs.size != 1) return dir
            dir = dirs[0]
        }
    }

    /** 容器配置回写 dxvk 版本戳（对齐 linbox-container install-dxvk 的记账）。 */
    private fun stampContainerDxvk(prefix: File, assetName: String) {
        try {
            // dxvk-2.4.tzst / dxvk-2.4-20260919.tar.zst → "2.4"
            val m = Regex("dxvk-([0-9]+(?:\\.[0-9]+)*)").find(assetName) ?: return
            val ver = m.groupValues[1]
            val contDir = prefix.parentFile ?: return
            val conf = File(contDir, "container.conf")
            if (!conf.isFile) return   // glibc 前缀无 container.conf：跳过
            val old = conf.readText()
            val line = "dxvk=$ver"
            val new = if (old.contains(Regex("^dxvk=.*$", RegexOption.MULTILINE))) {
                old.replace(Regex("^dxvk=.*$", RegexOption.MULTILINE), line)
            } else {
                old.trimEnd() + "\n" + line + "\n"
            }
            if (new != old) conf.writeText(new)
            // v3.2.28（用户问题："apk 自带的 DllOverrides 注册表还没清理
            // 干净，导致部分软件还是会报 media 错"）：此处不再回写
            // dxoverrides= —— 旧版这一行经 WINEDLLOVERRIDES 环境变量每次
            // 启动强制 d3d8/d3d9/d3d10core/d3d11/dxgi=native,builtin，
            // 属 APK 自带 DLL 加载序注入；DLL 加载序完全以 /opt/prefix
            // 安装包注册表为准。存量 container.conf 里 APK 写入的那行由
            // WineRegistry.migrateApkBundledDxOverrides 启动链迁移移除。
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // 文件归位工具（保留权限语义：目录 0771，dll 0644 可被 wine 映射）
    // ------------------------------------------------------------------

    private fun copyFileTo(src: File, dstDir: File) {
        dstDir.mkdirs()
        val dst = File(dstDir, src.name)
        src.copyTo(dst, overwrite = true)
        try { Os.chmod(dst.absolutePath, 436) } catch (_: Exception) {}   // 0664
    }

    private fun copyTree(srcDir: File, dstDir: File) {
        dstDir.mkdirs()
        srcDir.listFiles()?.forEach { f ->
            if (f.isDirectory) copyTree(f, File(dstDir, f.name))
            else copyFileTo(f, dstDir)
        }
    }

    // ------------------------------------------------------------------
    // 一键入口
    // ------------------------------------------------------------------

    /**
     * 全量自解压（App 启动 / bootstrap 安装完成后调用，后台线程）：
     * ① rootfs / mesa / box64 / turnip → rootfs（指纹幂等）；
     * ② dxvk → 已构建前缀（未构建的前缀自动等待下次）；
     * ③ 容器脚本（linbox-container / linbox-wine）→ rootfs/usr/bin
     *    （v2.26 隔离版：assets/linbox/scripts 覆盖安装 —— 解压完
     *    rootfs 后立即用 APK 内置最新脚本覆盖，rootfs 构建不再含
     *    linbox-container 项目，旧 rootfs 自带的旧脚本一并被替换）。
     */
    fun installAllIfNeeded(context: Context) {
        // 三段均捕获 Throwable：本方法在 Application.onCreate 的后台协程
        // 调用，任何异常（含 Error）冒泡即等于进程闪退（v2.24.1 修复：
        // UnsatisfiedLinkError 是 Error，旧 catch (Exception) 捕不到）。
        try {
            installBionicAssetsIfNeeded(context)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "bionic tzst 资产铺装失败: ${t.message}")
        }
        try {
            applyDxvkWhenPrefixReady(context)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "dxvk tzst 应用失败: ${t.message}")
        }
        try {
            // v2.26：rootfs 就位后覆盖安装容器脚本（assets/linbox/scripts）
            LinBoxScriptAssets.deployScriptsIfNeeded(context)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "容器脚本覆盖安装失败: ${t.message}")
        }
    }
}
