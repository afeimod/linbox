package com.linbox.apps.containers.launcher

import com.linbox.apps.containers.ContainerManager
import com.linbox.apps.containers.GuanliSupport
import java.io.File
import java.util.zip.ZipInputStream

/**
 * v3.3.19a（2026-11-08 热修，CI 实证）：v3.3.19 的 hasX11Wsi KDoc 注释里
 * 通配符连写把块注释提前闭合，残余文字落入类体 → kspReleaseKotlin 连报
 * 12 条 "Expecting member declaration"（207 行各 token 列号逐一对应）。
 * 已改写注释措辞，语义零改动。
 *
 * v3.3.19（用户实测反馈："wrapper 无法正常使用运行游戏"，会话日志实证
 * DXVK 报 "Required Vulkan extension VK_KHR_surface not supported"——
 * ICD 根本没被 dlopen 成功）：补齐 上游实现 DriverPackages.java 的
 * 【运行时可用性】语义 —— 装得进 ≠ 用得了。
 *
 * ## 本次根因（参考源码对照结论）
 *
 * 参考源码 上游实现（github.com/上游实现-Emu/上游实现）的
 * DriverPackages.install 在装包时干三件事，旧 v3.3.16 只干了一件：
 *
 *  | 上游实现 DriverPackages.install | v3.3.16 LinBox | v3.3.19 |
 *  |---|---|---|
 *  | 解包归一化到独立目录 + meta.json | ✓ | ✓ |
 *  | **identify()：ELF DT_NEEDED 识别 ABI（libc.so=bionic / libc.so.6=glibc），平台不符直接拒装** | ✗ 缺失 | ✓ 移植 |
 *  | 平台分拣：LINUX(glibc) 驱动进 glibc 容器直挂 ICD；ANDROID(bionic) 驱动经 wrapper 栈（libvulkan_wrapper.so 作 ICD + ADRENOTOOLS_DRIVER_PATH/NAME 注入，X11 WSI 由 wrapper 的 xcb 侧提供） | ✗ 只有裸 ICD 直挂 | ✓ wrapper 栈识别 + 路由 |
 *
 * 用户装的 wrapper-22 大概率是 **glibc 构建**（互联网流转的 turnip 绝大多数
 * 是 glibc：mesa 官方 deb/termux-glibc/wineguanli 生态包）。bionic 原生
 * wine 进程 dlopen glibc .so 时 DT_NEEDED 的 libc.so.6 无处可寻 → loader
 * 静默跳过 ICD → vkEnumerateInstanceExtensionProperties 空手而归 →
 * DXVK 只丢一句 "VK_KHR_surface not supported"（正是用户日志）。
 *
 * ## v3.3.19 新增语义（全部对齐参考源码）
 *
 * 1. **install() ABI 门禁**（identify() 全语义移植）：glibc 包直接拒装，
 *    Toast 给出可照做的出路（bionic X11 turnip 包 / 含 wrapper 栈的包）；
 *    非 arm64 ELF / 无法识别 ABI 同样拒装 —— 不再产出"选了必黑屏"的槽位；
 * 2. **wrapper 栈包形态**：包内含 libvulkan_wrapper.so（上游实现
 *    wrapper.tzst 布局：wrapper + libadrenotools.so + hooks）→ ICD 指向
 *    wrapper；同包带 turnip（libvulkan_*.so）则记为 adrenotools 驱动本体
 *    （ADRENOTOOLS_DRIVER_PATH/NAME，上游实现 setDriverById 同款键）；
 *    纯 wrapper 栈包（无 turnip）也可装 —— 作为共享栈给其他槽位路由；
 * 3. **android 平台驱动自动路由**：裸 turnip 为 bionic 但无 X11 WSI
 *    （DT_NEEDED 无 libxcb* —— yuzu/Adrenotools 型 android 平台构建）时，
 *    若已装任何 wrapper 栈槽位 → ICD 换用该 wrapper 栈、turnip 走
 *    ADRENOTOOLS_*（X11 WSI 由 wrapper 提供），DXVK 交换链才能创建；
 * 4. **依赖闭环预检**：DT_NEEDED 逐项对【驱动目录 / rootfs usr/lib /
 *    usr/local/lib / /system/lib64】解析（版本号后缀按无版本别名兼容，
 *    libdrm.so.2 → libdrm.so）；缺依赖的槽位激活时回退 Mesa 内置并
 *    【点名缺什么】（如 wrapper 栈缺 libandroid-sysvshm.so）；
 * 5. **激活诊断**：激活成功时注入 VK_LOADER_DEBUG=error —— loader 侧
 *    dlopen/ICD 错误直接进会话日志，驱动类问题不再"无声"。
 *
 * ## v3.3.16 既有模型（保持不变）
 *
 *  * 每个驱动一个【独立目录】usr/share/linbox-wrapper/drivers/<slot>/：
 *    libvulkan_*.so（+ wrapper 栈 .so）+ meta.json + icd.json（自生成，
 *    library_path 宿主绝对路径，不信任包内 json —— 上游实现 同款）；
 *  * turnip（Mesa 内置）= BUNDLED 档：rootfs 自带
 *    usr/share/vulkan/icd.d/freedreno_icd.aarch64.json，loader 默认搜索；
 *  * 多版本共存；切换 = 换 VK_ICD_FILENAMES 指向，零文件读写；
 *  * yuzu 式安装：➕ 选 zip/tar(.gz/.xz/.bz2) → 解析落位 → 立即出现在列表。
 *
 * ## 支持的包形态（全部归一到独立目录）
 *
 *  1. rootfs 布局包：usr/lib 下全部 .so + usr/share/vulkan 的 json；
 *  2. 上游实现 meta.json 包：根级 meta.json 的 libraryName 指明驱动；
 *  3. yuzu 裸包：根级 libvulkan*.so（+ 同目录附属 .so）；
 *  4. 【v3.3.19】wrapper 栈包：含 libvulkan_wrapper.so（可带可不带
 *     turnip 本体；上游实现 wrapper.tzst 重打包为 tar.gz/zip 即可）。
 *
 * ## bionic 驱动包从哪来（写给用户的路标）
 *
 *  * **首选**：LinBox CI `build-mesa-bionic` 新产物
 *    `linbox-turnip-driver-<mesa版本>-bionic.tar.gz`（与 rootfs 同一配方、
 *    同一 bionic/X11 平台构建，装完即用，零 ABI 风险）；
 *  * 上游实现-Emu/Drivers 的 adrenotools turnip zip（bionic/android 平台）
 *    —— 需同时装一个 wrapper 栈包供路由（见上 3）；
 *  * glibc 系（mesa 官方包 / wineguanli 生态 / termux-glibc）—— 一律拒装。
 */
object WrapperDriverSupport {

    /** 驱动安装根目录（rootfs 内；上游实现 对应 contents/linux-drivers/）。 */
    const val DRIVER_ROOT_REL = "usr/share/linbox-wrapper/drivers"

    /** 每个驱动 slot 目录内的 ICD 清单文件名（上游实现 DRIVER_ICD 同位）。 */
    const val ICD_NAME = "icd.json"

    /** 每个驱动 slot 目录内的元数据文件名（上游实现 meta.json 同名）。 */
    const val META_NAME = "meta.json"

    /** wrapper 栈标志库（作 ICD 目标；上游实现 wrapper.tzst 同名）。 */
    const val WRAPPER_SO = "libvulkan_wrapper.so"

    /** adrenotools 驱动加载库（wrapper 栈包内随行，上游实现 同名）。 */
    const val ADRENOTOOLS_SO = "libadrenotools.so"

    /**
     * v3.3.32（用户需求："wrapper 和 yuzu 类驱动只能用 turnip 开源驱动，
     * 用不了高通或者天环的系统驱动，比如高通 615.50 或者 849 等"）：
     *
     * 【系统专有驱动档】的 sentinel 值 —— 容器 wrapper="system" 时激活
     * wrapper 栈但不注入 ADRENOTOOLS_DRIVER_*（libvulkan_wrapper.so 自身
     * 回退加载 /system/lib64/libvulkan.so = 设备厂商专有驱动，高通
     * Adreno / 联发科 Mali 同一通路，上游参考 的 "System" 选项同款）。
     *
     * yuzu 格式专有驱动包（v819.2 / v849 / q615.50 等，包内
     * vulkan.ad*.so / libvulkan_adreno.so + not* 改名依赖）安装后走
     * 既有 android 平台驱动路由（无 X11 WSI → wrapper 栈 + ADRENOTOOLS_*）。
     */
    const val SYSTEM_SLOT = "system"

    /**
     * v3.3.32：内置 wrapper 栈的 slot 名（APK 资产 assets/linbox/wrapper-stack.tzst
     * 首次用到时自动安装到本 slot，5.5MB，上游参考 wrapper.tzst 同源二进制）。
     */
    const val BUNDLED_WRAPPER_SLOT = "wrapper-stack"

    /** v3.3.32：内置 wrapper 栈的 APK 资产路径。 */
    const val BUNDLED_WRAPPER_ASSET = "linbox/wrapper-stack.tzst"

    private val RFS_ROOT get() = ContainerManager.ROOTFS_ROOT

    private fun rfs(rel: String) = File(RFS_ROOT, rel)

    /** 驱动安装根目录。 */
    val driverRoot: File get() = rfs(DRIVER_ROOT_REL)

    // ------------------------------------------------------------------
    // v3.3.29：NDK 依赖前置解析（wrapper 栈 libbinder_ndk 链兜底）
    // ------------------------------------------------------------------

    /** wrapper 栈加载链涉及的系统 NDK 库（bionic verneed 校验的软链兜底集）。 */
    private val NDK_DEP_LIBS = listOf(
        "libbinder_ndk.so", "libnativewindow.so", "libandroid.so",
        "libmediandk.so", "libOpenSLES.so"
    )

    /**
     * v3.3.29：wrapper 栈 NDK 依赖前置解析。
     *
     * 根因（用户设备日志 wine-10arm-20261109-121003 实证）：
     * wrapper ICD → libvulkan_wrapper.so →（DT_NEEDED）libnativewindow.so →
     * （DT_NEEDED + verneed）libbinder_ndk.so。bionic linker 的
     * VersionTracker::init_verneed 校验要求 verneed 引用的库以 soname 出现在
     * libnativewindow.so 的已加载子依赖中；设备 rootfs 无 libbinder_ndk.so、
     * 进程搜索路径又解析不到时，报
     * "cannot find libbinder_ndk.so from verneed[2] in DT_NEEDED list" →
     * Vulkan Loader 忽略整个 ICD → "Found no drivers" → DXVK 全灭。
     *
     * 兜底：rootfs/usr/lib 缺哪个 NDK 库就软链哪个（目标 /system/lib64）。
     * bionic 按 realpath 的 basename 兜底 soname → 软链不破坏 soname 匹配；
     * rootfs/usr/lib 在 LD_LIBRARY_PATH 首位 → soname 搜索首选命中，使
     * libbinder_ndk.so 成为 libnativewindow.so 的子依赖，verneed 通过。
     * 已有实体文件（rootfs CI 的 libandroid-stub 平台代理等）跳过不动。
     *
     * @return 实际新建软链数量（0 = 全部已就位或系统目录无对应库）
     */
    fun ensureNdkDeps(): Int {
        val rfsLib = rfs("usr/lib")
        val sysLib = File("/system/lib64")
        if (!sysLib.isDirectory || !rfsLib.isDirectory) return 0
        var linked = 0
        for (name in NDK_DEP_LIBS) {
            val dst = File(rfsLib, name)
            if (dst.exists()) continue
            val src = File(sysLib, name)
            if (!src.exists()) continue
            runCatching {
                // 清可能残留的悬链（exists() 对悬链为 false 但路径被占用）
                dst.delete()
                android.system.Os.symlink(src.absolutePath, dst.absolutePath)
                linked++
            }
        }
        return linked
    }

    // ------------------------------------------------------------------
    // v3.3.19：ELF 识别（上游实现 DriverPackages.identify 全语义移植）
    // ------------------------------------------------------------------

    /** 驱动 ABI 平台：DT_NEEDED 里的 C 库决定（libc.so=bionic / libc.so.6=glibc）。 */
    enum class DriverPlatform(val label: String) {
        BIONIC("bionic"), GLIBC("glibc"), UNKNOWN("unknown");

        companion object {
            fun of(value: String?): DriverPlatform =
                values().firstOrNull { it.label.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN
        }
    }

    /** ELF 解析结果：ABI 平台 + 全量 DT_NEEDED（依赖预检 / X11 WSI 启发式用）。 */
    data class ElfInfo(val platform: DriverPlatform, val needed: List<String>)

    /**
     * 解析 ARM64 ELF 共享库（上游实现 DriverPackages.identify 同款结构校验，
     * 额外带出全量 DT_NEEDED）。任何结构异常返回 null —— 调用方给可读错误。
     * 校验链：ELF 魔数 / 64 位 / 小端 / ET_DYN / EM_AARCH64(183) →
     * 程序头表（PT_LOAD + PT_DYNAMIC）→ 动态段（DT_STRTAB/DT_NEEDED）→
     * 依赖名（libc.so / libc.so.6 判定平台，两者同现或同缺 = 无法识别）。
     */
    fun identifyElf(elf: ByteArray): ElfInfo? {
        if (elf.size < 64) return null
        return runCatching {
            fun u16(o: Int) =
                (elf[o].toInt() and 0xff) or ((elf[o + 1].toInt() and 0xff) shl 8)

            fun u64(o: Int): Long {
                var v = 0L
                for (i in 7 downTo 0) v = (v shl 8) or (elf[o + i].toLong() and 0xffL)
                return v
            }

            fun i32(o: Int): Int {
                var v = 0
                for (i in 3 downTo 0) v = (v shl 8) or (elf[o + i].toInt() and 0xff)
                return v
            }

            // ELF 魔数 / 64 位 / 小端 / 共享库 / AArch64
            // v3.3.21（用户实测反馈根因）：旧版 `i32(0) != 0x7f454c46` 恒
            // false —— i32 按小端组值（\x7fELF 四字节 LE = 0x464c457f），
            // 0x7f454c46 是大端序常量，魔数比对【永远不成立】→ identifyElf
            // 对任何文件都返回 null → v3.3.19 起所有驱动包（wrapper-22 /
            // yuzu zip / adpkg zip / turnip tar）一律被"不是有效的 ARM64
            // ELF"拒装（用户四类包全实测复现）。改为逐字节魔数比对。
            if (elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() ||
                elf[2] != 'L'.code.toByte() || elf[3] != 'F'.code.toByte()
            ) return@runCatching null
            if (elf[4].toInt() != 2 || elf[5].toInt() != 1) return@runCatching null
            if (u16(16) != 3 || u16(18) != 183) return@runCatching null

            val phoff = u64(32)
            val phentsize = u16(54)
            val phnum = u16(56)
            if (phentsize < 56 || phnum == 0 || phnum > 4096) return@runCatching null
            if (phoff < 0 || phoff + phnum.toLong() * phentsize.toLong() > elf.size)
                return@runCatching null

            data class Load(val vaddr: Long, val off: Long, val size: Long)
            val loads = mutableListOf<Load>()
            var dynOff = -1L
            var dynSize = 0L
            for (i in 0 until phnum) {
                val ph = (phoff + i.toLong() * phentsize.toLong()).toInt()
                when (i32(ph)) {
                    1 -> loads.add(Load(u64(ph + 0x10), u64(ph + 8), u64(ph + 0x20)))
                    2 -> { dynOff = u64(ph + 8); dynSize = u64(ph + 0x20) }
                }
            }
            if (dynOff < 0 || dynSize <= 0 || dynOff + dynSize > elf.size)
                return@runCatching null

            var strtabVaddr = -1L
            val neededIdx = mutableListOf<Long>()
            var o = dynOff.toInt()
            val dynEnd = (dynOff + dynSize).toInt()
            while (o + 16 <= dynEnd) {
                val tag = u64(o)
                if (tag == 0L) break
                val value = u64(o + 8)
                if (tag == 5L) strtabVaddr = value
                if (tag == 1L) neededIdx.add(value)
                o += 16
            }
            if (strtabVaddr < 0) return@runCatching null
            val strOff = loads.firstOrNull {
                strtabVaddr >= it.vaddr && strtabVaddr - it.vaddr < it.size
            }?.let { it.off + (strtabVaddr - it.vaddr) } ?: return@runCatching null

            val needed = neededIdx.mapNotNull { idx ->
                val s = (strOff + idx).toInt()
                if (s < 0 || s >= elf.size) return@mapNotNull null
                var e = s
                while (e < elf.size && elf[e] != 0.toByte() && e - s < 256) e++
                if (e >= elf.size || elf[e] != 0.toByte()) return@mapNotNull null
                String(elf, s, e - s, Charsets.UTF_8)
            }
            val bionic = needed.any { it == "libc.so" }
            val glibc = needed.any { it == "libc.so.6" }
            val platform = when {
                bionic && !glibc -> DriverPlatform.BIONIC
                glibc && !bionic -> DriverPlatform.GLIBC
                else -> DriverPlatform.UNKNOWN
            }
            ElfInfo(platform, needed)
        }.getOrNull()
    }

    /** X11 WSI 启发式：DT_NEEDED 含 libxcb 系或 libX11 系 = X11 平台构建（可直挂 ICD）。 */
    private fun hasX11Wsi(needed: List<String>): Boolean =
        needed.any { it == "libxcb.so" || it.startsWith("libxcb-") || it.startsWith("libX11") }

    /** 依赖解析搜索位（顺序即优先级）：驱动目录 → rootfs → Android 系统 → 厂商分区。 */
    private fun depSearchDirs(vararg driverDirs: File): List<File> =
        driverDirs.toList() + listOf(
            rfs("usr/lib"), rfs("usr/local/lib"), File("/system/lib64"), File("/system/lib"),
            // v3.3.32：厂商分区 —— 高通专有驱动（v849/q615.50 等）DT_NEEDED 含
            // vendor.qti.hardware.display.mapper@*.so / android.hardware.graphics.*
            // / libhidlbase.so 等 HIDL 库，只存在于 /vendor（/odm、/system_ext
            // 亦是候选位）。adrenotools 隔离命名空间加载时能制到这些路径，
            // 预检与之一致才不会误判“依赖未闭环”而回退 Mesa。
            File("/vendor/lib64"), File("/vendor/lib64/hw"),
            File("/odm/lib64"), File("/system_ext/lib64")
        )

    /** DT_NEEDED 依赖是否可解析（版本号后缀按无版本别名兼容：libdrm.so.2 → libdrm.so）。 */
    private fun resolveDep(name: String, dirs: List<File>): Boolean {
        if (dirs.any { File(it, name).isFile }) return true
        val base = name.replace(Regex("\\.\\d+(\\.\\d+)*$"), "")
        return base != name && dirs.any { File(it, base).isFile }
    }

    /** 一组 .so 的全部未解析依赖（点名用；去重保持出现顺序）。 */
    private fun missingDeps(libs: List<Pair<File, List<String>>>, dirs: List<File>): List<String> {
        val seen = LinkedHashSet<String>()
        libs.forEach { (_, needed) ->
            needed.forEach { n -> if (!resolveDep(n, dirs)) seen.add(n) }
        }
        return seen.toList()
    }

    // ------------------------------------------------------------------
    // 已装驱动查询
    // ------------------------------------------------------------------

    /** 已安装驱动的元数据快照（UI 版本列表 / 激活探测共用）。 */
    data class DriverEntry(
        /** slot 目录名（稳定 id）。 */
        val slot: String,
        /** 驱动库名（ICD 目标：wrapper 栈包=libvulkan_wrapper.so，其余=libvulkan_*.so）。 */
        val libraryName: String,
        /** 原包文件名（显示用）。 */
        val sourceFile: String,
        /** slot 目录。 */
        val dir: File,
        /** slot 的 ICD 清单（存在且 library 指向真实文件才非空）。 */
        val icd: File?,
        /** v3.3.19：ABI 平台（meta 记录；激活时实时重解析为准）。 */
        val platform: DriverPlatform = DriverPlatform.UNKNOWN,
        /** v3.3.19：是否 X11 WSI 构建（meta 记录，显示用）。 */
        val x11wsi: Boolean = false,
        /** v3.3.19：是否 wrapper 栈槽位（含 libvulkan_wrapper.so）。 */
        val wrapperStack: Boolean = false
    )

    /** slot（包显示名）→ 安全目录名（上游实现 safeName 同语义）。 */
    fun safeSlotName(display: String): String =
        display.trim()
            .replace(Regex("\\.(zip|tar|tar\\.(gz|xz|bz2|zst)|tzst)$"), "")
            .replace(Regex("[^A-Za-z0-9._+-]"), "-")
            .trim('-', '.')
            .ifEmpty { "driver" }
            .take(64)

    /** 列出已安装驱动（slot 目录含 meta.json + 可用 ICD 才入列；按名排序）。 */
    fun listDrivers(): List<DriverEntry> {
        migrateLegacy()
        val root = driverRoot
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .mapNotNull { dir ->
                val meta = File(dir, META_NAME)
                if (!meta.isFile) return@mapNotNull null
                val text = runCatching { meta.readText() }.getOrDefault("")
                val lib = Regex("\"libraryName\"\\s*:\\s*\"([^\"]+)\"")
                    .find(text)?.groupValues?.get(1) ?: return@mapNotNull null
                val src = Regex("\"sourceFile\"\\s*:\\s*\"([^\"]+)\"")
                    .find(text)?.groupValues?.get(1) ?: dir.name
                DriverEntry(
                    slot = dir.name,
                    libraryName = lib,
                    sourceFile = src,
                    dir = dir,
                    icd = icdFor(dir.name),
                    platform = DriverPlatform.of(
                        Regex("\"platform\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    ),
                    x11wsi = Regex("\"x11wsi\"\\s*:\\s*true").containsMatchIn(text),
                    wrapperStack = Regex("\"wrapperStack\"\\s*:\\s*true").containsMatchIn(text) ||
                        File(dir, WRAPPER_SO).isFile
                )
            }
            .sortedBy { it.slot.lowercase() }
    }

    /**
     * v3.3.37：专有驱动库名判定（yuzu/高通/联发科专有包 —— vulkan.adXXXX /
     * vulkan.purple / libvulkan_adreno 命名族；turnip 本体固定
     * libvulkan_freedreno.so）。UI 的"专有驱动包（/opt/yuzu）"档与
     * "Turnip 驱动包（/opt/tz）"档按此分列（用户：安装应放在对应目录
     * 进行选择，不再全混在 opt/tz 一个入口）。
     */
    fun isProprietaryLib(libraryName: String): Boolean {
        val lib = libraryName.trim().lowercase()
        if (lib.isEmpty() || lib == "libvulkan_freedreno.so" || lib == "libvulkan_wrapper.so") return false
        // vulkan.ad07xx.so / vulkan.purple.so / libvulkan_adreno.so /
        // libvulkan.qualcomm 等厂商命名族
        return lib.startsWith("vulkan.") || lib.contains("adreno") || lib.contains("qualcomm") ||
            lib.contains("mediatek") || lib.contains("mali")
    }

    /** 槽位级专有判定（meta.json 的 libraryName 为准；未知槽位按 false）。 */
    fun isProprietarySlot(slot: String): Boolean =
        listDrivers().firstOrNull { it.slot == slot && !it.wrapperStack }
            ?.let { isProprietaryLib(it.libraryName) } ?: false

    /**
     * slot 的 ICD 清单（存在且 library_path 指向真实 .so 才算数）。
     * RootfsLauncher / conf 同步的探测入口。
     */
    fun icdFor(slot: String): File? {
        val json = File(File(driverRoot, safeSlotName(slot)), ICD_NAME)
        if (!json.isFile) return null
        val lib = parseIcdLibraryPath(json) ?: return null
        // library_path 为宿主绝对路径（自生成保证）；相对路径按 slot 目录解析
        val libFile = if (lib.startsWith("/")) File(lib) else File(json.parentFile, lib)
        return if (libFile.isFile) json else null
    }

    /**
     * 【turnip BUNDLED 档】rootfs 自带 Mesa Turnip 的 ICD
     * （不存在时返回 null —— 表示"不注入 VK_ICD_FILENAMES，走 loader
     * 默认搜索"，rootfs 的 vulkan-icd-loader 配置了标准路径）。
     */
    fun bundledTurnipIcd(): File? {
        val icd = rfs("usr/share/vulkan/icd.d/freedreno_icd.aarch64.json")
        return if (icd.isFile) icd else null
    }

    /**
     * wrapper/用户 turnip 激活时 GL 侧是否应联动 zink：
     * 容器 gallium 为 auto/空/zink 时 true（GL over Vulkan 吃驱动加速），
     * 用户显式选 freedreno/softpipe 等 GL 驱动时 false（尊重选择）。
     */
    fun shouldForceZink(gallium: String): Boolean {
        val g = gallium.trim().lowercase()
        return g.isBlank() || g == "auto" || g == "zink"
    }

    // ------------------------------------------------------------------
    // v3.3.19：激活计划（RootfsLauncher 6a 使用；对齐 上游实现
    // XServerDisplayActivity 的 ICD + ADRENOTOOLS_* 注入语义）
    // ------------------------------------------------------------------

    /**
     * 一个槽位的完整激活计划：
     *  * glibc / 非 ELF / 缺依赖 → [usable]=false，调用方回退 Mesa 内置并落日志；
     *  * wrapper 栈槽位 → [icd] 指向 wrapper，同包 turnip 走 adrenotools；
     *  * android 平台裸 turnip（无 X11 WSI）且存在其他 wrapper 栈槽位 →
     *    自动路由：[icd] 换成该 wrapper 栈，本槽 turnip 经
     *    [adrenotoolsDriverDir]/[adrenotoolsDriverName] 注入。
     */
    data class Activation(
        /** slot 名。 */
        val slot: String,
        /** 注入 VK_ICD_FILENAMES 的 ICD 清单（wrapper 路由下指向 wrapper 栈）。 */
        val icd: File,
        /** ICD 目标 .so 名（诊断显示）。 */
        val icdLibraryName: String,
        /** ICD 目标所在目录（LD_LIBRARY_PATH 前插位）。 */
        val driverDir: File,
        /** 是否 wrapper 栈模式（ICD 目标 = libvulkan_wrapper.so）。 */
        val wrapperStack: Boolean,
        /** ICD 目标 .so 实时识别的 ABI 平台（UNKNOWN = 非 ELF/解析失败）。 */
        val platform: DriverPlatform,
        /** 最终链路是否具备 X11 WSI（wrapper 模式恒 true）。 */
        val x11wsi: Boolean,
        /** adrenotools 驱动目录（wrapper 模式下非空）。 */
        val adrenotoolsDriverDir: File?,
        /** adrenotools 驱动库名（wrapper 模式下非空）。 */
        val adrenotoolsDriverName: String?,
        /** 被路由到的 wrapper 栈 slot（本槽直挂时 null）。 */
        val routedFromWrapperSlot: String?,
        /**
         * v3.3.20：wrapper 栈的 hook 库目录（ADRENOTOOLS_HOOKS_PATH 指向）。
         *
         * wrapper 生态的 libvulkan_wrapper.so（用户提供的 wrapper-22 等）经
         * env 读取四件套：ADRENOTOOLS_DRIVER_PATH / DRIVER_NAME /
         * HOOKS_PATH / REDIRECT_DIR，其中 HOOKS_PATH 是 libmain_hook.so /
         * libhook_impl.so 所在目录 —— 没有它 adrenotools 初始化必报
         * "Couldn't preload the hook implementation"，wrapper dlopen 后
         * 零 ICD 生效（即用户“切换 wrapper 依旧无法使用”的运行期根因）。
         * wrapper 栈模式下 = wrapper slot 目录；直挂模式 null。
         */
        val adrenotoolsHooksDir: File? = null,
        /**
         * v3.3.20：adrenotools 临时目录（patched .so 落盘位）。
         *
         * wrapper 内部拼 `<driverPath>/temp`（上游实现 vulkan.c 同款），
         * 目录必须【存在且可写】；激活时由 RootfsLauncher 创建。
         */
        val adrenotoolsTempDir: File? = null,
        /** 未闭环依赖（点名用；非空 = 不可用）。 */
        val missingDeps: List<String>,
        /** 平台/依赖预检全通过，可注入。 */
        val usable: Boolean
    )

    /** 计算槽位激活计划（null = 槽位未安装 / ICD 未就位）。 */
    fun activationFor(slot: String): Activation? {
        // v3.3.32：系统专有驱动档（高通/联发科）—— wrapper 栈系统回退模式，
        // 不注入 ADRENOTOOLS_DRIVER_*（libvulkan_wrapper.so 自行加载
        // /system/lib64/libvulkan.so）。栈本体须已安装（调用方先
        // ensureWrapperStackAvailable；未安装返回 null 并由调用方提示）。
        if (slot == SYSTEM_SLOT) return systemActivationFor()
        val icd = icdFor(slot) ?: return null
        val dir = icd.parentFile ?: return null
        val lib = parseIcdLibraryPath(icd) ?: return null
        val libFile = if (lib.startsWith("/")) File(lib) else File(dir, lib)
        val elf = runCatching { identifyElf(libFile.readBytes()) }.getOrNull()

        val wrapperStack = libFile.name == WRAPPER_SO
        var useIcd = icd
        var useDir = dir
        var useWrapper = wrapperStack
        var routedFrom: String? = null
        var adrenoDir: File? = null
        var adrenoName: String? = null

        if (wrapperStack) {
            // 本槽即 wrapper 栈：adrenotools 驱动 = 同包 turnip（meta.driverLibrary）
            val driverLib = Regex("\"driverLibrary\"\\s*:\\s*\"([^\"]+)\"")
                .find(runCatching { File(dir, META_NAME).readText() }.getOrDefault(""))
                ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            if (driverLib != null && File(dir, driverLib).isFile) {
                adrenoDir = dir
                adrenoName = driverLib
            }
        } else if (elf?.platform == DriverPlatform.BIONIC && !hasX11Wsi(elf.needed)) {
            // android 平台裸 turnip（yuzu/Adrenotools 型）：直挂无 X11 WSI，
            // DXVK 交换链必失败 —— 按 上游实现 参考逻辑找 wrapper 栈路由
            val ws = listDrivers().firstOrNull {
                it.slot != slot && it.wrapperStack && it.icd != null
            }
            if (ws != null && ws.icd != null) {
                useIcd = ws.icd!!
                useDir = ws.dir
                useWrapper = true
                routedFrom = ws.slot
                adrenoDir = dir
                adrenoName = libFile.name
            }
        }

        // 依赖闭环预检：ICD 目标 +（wrapper 模式）libadrenotools + turnip 本体
        val checkLibs = mutableListOf<Pair<File, List<String>>>()
        elf?.let { checkLibs.add(libFile to it.needed) }
        if (useWrapper) {
            val useLib = parseIcdLibraryPath(useIcd)?.let {
                if (it.startsWith("/")) File(it) else File(useIcd.parentFile, it)
            }
            if (useLib != null && useLib.isFile && useLib != libFile) {
                runCatching { identifyElf(useLib.readBytes()) }.getOrNull()
                    ?.let { checkLibs.add(useLib to it.needed) }
            }
            File(useDir, ADRENOTOOLS_SO).takeIf { it.isFile }?.let { at ->
                runCatching { identifyElf(at.readBytes()) }.getOrNull()
                    ?.let { checkLibs.add(at to it.needed) }
            }
            if (adrenoDir != null && adrenoName != null) {
                val t = File(adrenoDir, adrenoName)
                if (t.isFile) {
                    runCatching { identifyElf(t.readBytes()) }.getOrNull()
                        ?.let { checkLibs.add(t to it.needed) }
                }
            }
        }
        val missing = missingDeps(checkLibs, depSearchDirs(dir, useDir))

        val platform = elf?.platform ?: DriverPlatform.UNKNOWN
        val usable = platform == DriverPlatform.BIONIC && missing.isEmpty()
        return Activation(
            slot = slot,
            icd = useIcd,
            icdLibraryName = parseIcdLibraryPath(useIcd)?.substringAfterLast('/') ?: "",
            driverDir = useDir,
            wrapperStack = useWrapper,
            platform = platform,
            x11wsi = useWrapper || (elf?.let { hasX11Wsi(it.needed) } == true),
            adrenotoolsDriverDir = adrenoDir,
            adrenotoolsDriverName = adrenoName,
            routedFromWrapperSlot = routedFrom,
            adrenotoolsHooksDir = if (useWrapper) useDir else null,
            adrenotoolsTempDir = if (useWrapper && adrenoDir != null) File(adrenoDir, "temp") else null,
            missingDeps = missing,
            usable = usable
        )
    }

    /**
     * v3.3.32：系统专有驱动档的激活计划 —— 任一已装 wrapper 栈槽位作 ICD，
     * adrenotools 驱动位为空（= wrapper 回退系统 /system/lib64/libvulkan.so，
     * 即厂商专有驱动：高通 Adreno 系（615.50/849 等系统驱动）、联发科 Mali
     * 系同通路）。无可用 wrapper 栈时返回 null（调用方提示先装栈）。
     */
    private fun systemActivationFor(): Activation? {
        val ws = listDrivers().firstOrNull { it.wrapperStack && it.icd != null }
            ?: return null
        val useIcd = ws.icd ?: return null
        val useDir = ws.dir
        val useLib = parseIcdLibraryPath(useIcd)
            ?.let { if (it.startsWith("/")) File(it) else File(useIcd.parentFile, it) }
            ?: return null
        val elf = runCatching { identifyElf(useLib.readBytes()) }.getOrNull()
        val checkLibs = mutableListOf<Pair<File, List<String>>>()
        elf?.let { checkLibs.add(useLib to it.needed) }
        File(useDir, ADRENOTOOLS_SO).takeIf { it.isFile }?.let { at ->
            runCatching { identifyElf(at.readBytes()) }.getOrNull()
                ?.let { checkLibs.add(at to it.needed) }
        }
        val missing = missingDeps(checkLibs, depSearchDirs(useDir))
        return Activation(
            slot = SYSTEM_SLOT,
            icd = useIcd,
            icdLibraryName = WRAPPER_SO,
            driverDir = useDir,
            wrapperStack = true,
            platform = elf?.platform ?: DriverPlatform.BIONIC,
            x11wsi = true,
            adrenotoolsDriverDir = null,
            adrenotoolsDriverName = null,
            routedFromWrapperSlot = ws.slot,
            adrenotoolsHooksDir = useDir,
            adrenotoolsTempDir = null,
            missingDeps = missing,
            usable = elf?.platform == DriverPlatform.BIONIC && missing.isEmpty()
        )
    }

    /**
     * v3.3.32：安装 APK 内置 wrapper 栈（assets/linbox/wrapper-stack.tzst，
     * 上游参考 wrapper.tzst 同源二进制：libvulkan_wrapper.so + libadrenotools.so
     * + libmain_hook/libhook_impl/libfile_redirect_hook/libgsl_alloc_hook 四钩子）。
     * 幂等：BUNDLED_WRAPPER_SLOT 已含 wrapper .so 且 ICD 就位即跳过。
     *
     * @return null 成功；否则用户可读的错误信息
     */
    fun ensureBundledWrapperStack(
        context: android.content.Context,
        log: (String) -> Unit = {}
    ): String? {
        val slotDir = File(driverRoot, BUNDLED_WRAPPER_SLOT)
        if (File(slotDir, WRAPPER_SO).isFile && icdFor(BUNDLED_WRAPPER_SLOT) != null) {
            return null // 已就位（幂等）
        }
        // 临时文件名 = BUNDLED_WRAPPER_SLOT + .tzst —— install() 以包名推
        // slot 目录名（safeSlotName 剥后缀），保证落位与幂等检查同名
        val tmp = File(rfs("usr/tmp"), "$BUNDLED_WRAPPER_SLOT.tzst")
        return runCatching {
            runCatching { tmp.parentFile?.mkdirs() }
            context.assets.open(BUNDLED_WRAPPER_ASSET).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val err = install(tmp.absolutePath) { log(it) }
            tmp.delete()
            if (err == null) {
                log("✅ 内置 wrapper 栈已安装（slot=$BUNDLED_WRAPPER_SLOT —— 高通/联发科系统专有驱动与 yuzu 专有驱动包的通路，v3.3.32）")
            }
            err
        }.getOrElse { "内置 wrapper 栈安装失败：${it.message}" }
    }

    /**
     * v3.3.32：确保 wrapper 栈可用（已装任意栈槽位即满足；否则装内置栈）。
     * @return true = 当前存在可用 wrapper 栈槽位
     */
    fun ensureWrapperStackAvailable(
        context: android.content.Context,
        log: (String) -> Unit = {}
    ): Boolean {
        if (listDrivers().any { it.wrapperStack && it.icd != null }) return true
        val err = ensureBundledWrapperStack(context, log)
        return err == null && listDrivers().any { it.wrapperStack && it.icd != null }
    }

    // ------------------------------------------------------------------
    // 安装（yuzu 式：zip / tar 包 → 独立目录；v3.3.19 加 ABI 门禁）
    // ------------------------------------------------------------------

    /**
     * 安装一个驱动包到独立 slot 目录（上游实现 DriverPackages.install
     * 同语义：staging 解包 → 【v3.3.19 ELF ABI 识别】→ 校验 → 落位 →
     * 生成 ICD + meta）。
     *
     * @param srcPath 包文件路径（.zip / .tar / .tar.gz / .tar.xz / .tar.bz2）
     * @param log 进度输出
     * @return null 成功；否则用户可读的错误信息
     */
    fun install(srcPath: String, log: (String) -> Unit = {}): String? {
        migrateLegacy()
        val src = File(srcPath)
        if (!src.isFile) return "文件不存在：$srcPath"
        val slot = safeSlotName(src.name)

        // staging 解包（rootfs tmp 下，与安装目标同文件系统，rename 原子）
        val staging = File(rfs("usr/tmp"), "linbox-driver-install-$slot")
        runCatching {
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
        }.onFailure { return "staging 准备失败：${it.message}" }

        val err = when {
            src.name.endsWith(".zip", true) -> unzipTo(src, staging)
            else -> GuanliSupport.extractTarTo(src.absolutePath, staging) { log(it) }
        }
        if (err != null) {
            runCatching { staging.deleteRecursively() }
            return err
        }

        // 归一化解析包形态 → 待收集文件列表 + 驱动 so 名（v3.3.19：区分
        // wrapper 栈 so 与 turnip 驱动 so —— 含 libvulkan_wrapper.so 的包按
        // wrapper 栈模式激活：ICD 指向 wrapper，turnip 走 ADRENOTOOLS_*）
        val plan = planInstall(staging)
            ?: run {
                runCatching { staging.deleteRecursively() }
                return "包内未找到 libvulkan*.so 驱动或 wrapper 栈（支持 rootfs 布局 / " +
                    "meta.json / yuzu 裸包 / wrapper 栈四种形态）"
            }

        // ---- v3.3.19：ELF ABI 识别（上游实现 DriverPackages.identify 移植）----
        // glibc 构建在 bionic 原生链路 dlopen 必失败（libc.so.6 无处可寻）：
        // 安装期直接拒绝并给出路，不再产出"选了必黑屏、DXVK 只丢一句
        // VK_KHR_surface not supported"的槽位。primary = ICD 目标
        // （wrapper 栈包先验 wrapper 本体，直挂包验 turnip 本体）。
        val primarySoName = plan.wrapperSoName ?: plan.driverSoName
        val primaryFile = plan.files.first { it.second == primarySoName }.first
        val primaryElf = runCatching { identifyElf(primaryFile.readBytes()) }.getOrNull()
            ?: run {
                runCatching { staging.deleteRecursively() }
                return "驱动库 $primarySoName 不是有效的 ARM64 ELF 共享库" +
                    "（可能损坏 / 非 arm64 / 未 strip 异常）—— 拒绝安装"
            }
        if (primaryElf.platform == DriverPlatform.GLIBC) {
            runCatching { staging.deleteRecursively() }
            return "驱动是 glibc 构建（依赖 libc.so.6），LinBox bionic 原生 wine 进程" +
                "无法加载 —— 安装被拒绝。\n" +
                "出路：① 用 bionic 构建的 turnip 驱动包（X11 平台，LinBox CI 产物 " +
                "linbox-turnip-driver-*-bionic.tar.gz 可直接装）② 或装含 " +
                "libvulkan_wrapper.so 的 wrapper 栈包（bionic 驱动经 adrenotools 路由）" +
                "③ glibc 驱动仅供 glibc 链路（startwine / opt 生态）使用，不适用本 App 原生容器"
        }
        if (primaryElf.platform == DriverPlatform.UNKNOWN) {
            runCatching { staging.deleteRecursively() }
            return "无法识别驱动 ABI（依赖表中既无 libc.so 也无 libc.so.6）—— 仅支持" +
                "bionic 构建的驱动包，安装被拒绝"
        }
        // wrapper 栈包内的 turnip 本体同样必须是 bionic（wrapper 包里
        // primary=wrapper，turnip 在此另验；直挂包 primary 即 turnip，不重读）
        var driverElf: ElfInfo? = null
        if (plan.wrapperSoName != null && plan.driverSoName != null && plan.driverSoName != primarySoName) {
            val dFile = plan.files.first { it.second == plan.driverSoName }.first
            driverElf = runCatching { identifyElf(dFile.readBytes()) }.getOrNull()
            if (driverElf == null || driverElf.platform != DriverPlatform.BIONIC) {
                runCatching { staging.deleteRecursively() }
                return "wrapper 栈包内的 ${plan.driverSoName} 不是 bionic 构建的 ARM64 " +
                    "ELF（${driverElf?.platform?.label ?: "解析失败"}）—— 拒绝安装"
            }
        }
        val x11wsi = hasX11Wsi((driverElf ?: primaryElf).needed)
        val icdTarget = plan.wrapperSoName ?: plan.driverSoName!!

        // 目标目录（已存在 = 同名重装：先删旧目录，driver 独立目录无共享
        // 文件，直接删除不影响其他 slot 与 Mesa）
        val dest = File(driverRoot, slot)
        runCatching {
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
        }.onFailure {
            runCatching { staging.deleteRecursively() }
            return "目标目录创建失败：${it.message}"
        }

        // 落位：驱动 + wrapper 栈 + 附属 .so（及 share 侧文件）平铺进 slot 目录
        var installed = 0
        runCatching {
            plan.files.forEach { (file, name) ->
                val out = File(dest, name)
                file.copyTo(out, overwrite = true)
                installed++
            }
        }.onFailure {
            runCatching { dest.deleteRecursively(); staging.deleteRecursively() }
            return "驱动落位失败：${it.message}"
        }
        runCatching { staging.deleteRecursively() }

        // 自生成 ICD（上游实现 同款：宿主绝对路径，不信任包内 json）
        //  * wrapper 栈包 → ICD 指向 libvulkan_wrapper.so（X11 WSI 由 wrapper 提供）
        //  * 直挂包 → ICD 指向 turnip 本体
        val libAbs = File(dest, icdTarget).absolutePath
        val icdJson = buildString {
            appendLine("{")
            appendLine("    \"file_format_version\": \"1.0.0\",")
            appendLine("    \"ICD\": {")
            appendLine("        \"api_version\": \"1.4.0\",")
            appendLine("        \"library_path\": \"$libAbs\"")
            appendLine("    }")
            appendLine("}")
        }
        // meta.json（上游实现 同款字段 + v3.3.19 平台/WSI/wrapper 记录）
        val metaJson = buildString {
            appendLine("{")
            appendLine("    \"name\": \"$slot\",")
            appendLine("    \"libraryName\": \"$icdTarget\",")
            appendLine("    \"driverLibrary\": \"${plan.driverSoName ?: ""}\",")
            appendLine("    \"platform\": \"${DriverPlatform.BIONIC.label}\",")
            appendLine("    \"x11wsi\": $x11wsi,")
            appendLine("    \"wrapperStack\": ${plan.wrapperSoName != null},")
            appendLine("    \"sourceFile\": \"${src.name}\",")
            appendLine("    \"installedAt\": ${System.currentTimeMillis()}")
            appendLine("}")
        }
        val writeErr = runCatching {
            File(dest, ICD_NAME).writeText(icdJson)
            File(dest, META_NAME).writeText(metaJson)
        }.exceptionOrNull()?.let { "ICD/meta 写入失败：${it.message}" }
        if (writeErr != null) {
            runCatching { dest.deleteRecursively() }
            return writeErr
        }

        // ---- v3.3.19：依赖闭环预检（点名缺什么，装包即知）----
        // 逐来源 .so 各用各的 DT_NEEDED：primary（driver 或 wrapper）/
        // wrapper 栈包内的 turnip 本体 / libadrenotools.so
        val checkLibs = mutableListOf<Pair<File, List<String>>>()
        File(dest, primarySoName!!).takeIf { it.isFile }
            ?.let { checkLibs.add(it to primaryElf.needed) }
        if (plan.wrapperSoName != null && plan.driverSoName != null && driverElf != null) {
            File(dest, plan.driverSoName!!).takeIf { it.isFile }
                ?.let { checkLibs.add(it to driverElf.needed) }
        }
        File(dest, ADRENOTOOLS_SO).takeIf { it.isFile }?.let { at ->
            runCatching { identifyElf(at.readBytes()) }.getOrNull()
                ?.let { checkLibs.add(at to it.needed) }
        }
        val missing = missingDeps(checkLibs, depSearchDirs(dest))
        log("✅ 驱动 $slot 已安装：$installed 个文件 → ${dest.absolutePath}（bionic / " +
            (if (plan.wrapperSoName != null) "wrapper 栈模式" else if (x11wsi) "直挂 X11 WSI ✓" else "android 平台构建（建议配 wrapper 栈）") +
            "）")
        log("Vulkan ICD → ${File(dest, ICD_NAME).absolutePath}（容器选它即生效）")
        if (missing.isNotEmpty()) {
            log("⚠ 依赖未闭环：${missing.joinToString("、")}（驱动目录/rootfs/usr/lib/系统库均未找到）")
            log("  把缺的 .so 一并放进驱动包重新安装即可（wrapper 栈常见缺 libandroid-sysvshm.so）")
        }
        return null
    }

    /** 包形态解析结果：files = staging 文件 → slot 内文件名；driverSo / wrapperSo。 */
    private data class InstallPlan(
        val files: List<Pair<File, String>>,
        /** turnip 驱动本体（libvulkan_*，排除 wrapper；纯 wrapper 栈包为 null）。 */
        val driverSoName: String?,
        /** wrapper 栈标志库（libvulkan_wrapper.so；无则 null）。 */
        val wrapperSoName: String?
    )

    /**
     * 解析四种包形态（对 root / 剥壳后 base 各做一次 —— rootfs 布局包
     * 的顶层目录就是 usr，不能被"剥壳"逻辑剥掉）。
     */
    private fun planInstall(root: File): InstallPlan? {
        planRootfsLayout(root)?.let { return it }

        // 剥壳：唯一顶层目录且根级无文件 → 下钻一层再判（usr 是布局本体不剥）
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        val rootFiles = root.listFiles { f -> f.isFile }.orEmpty()
        if (dirs.size == 1 && rootFiles.isEmpty() && dirs[0].name != "usr") {
            val base = dirs[0]
            planRootfsLayout(base)?.let { return it }
            return planFlatLayout(base)
        }
        return planFlatLayout(root)
    }

    /**
     * 从 lib 目录文件里挑 turnip 驱动本体。
     *
     * v3.3.20（用户实测反馈修复）：识别面扩大 —— 旧版只认 `libvulkan*.so`，
     * 而 yuzu 生态/上游实现 测试包存在 `vulkan.<name>.so` 命名（用户提供的
     * turnip_mrpurple_T28-toasted.adpkg.zip 的 libraryName 就是
     * `vulkan.purple.so`；上游实现 DriverPackagesTest 也用 `vulkan.adreno.so`）。
     * 现在 `libvulkan*.so` 与 `vulkan.*.so` 双形态都认（wrapper 除外）。
     */
    private fun pickDriverSo(names: List<String>): String? =
        names.firstOrNull {
            (it.startsWith("libvulkan") || it.startsWith("vulkan.")) &&
                it.endsWith(".so") && it != WRAPPER_SO
        }

    /** A) rootfs 布局包：usr/lib 的全部 .so + usr/share/vulkan 的 json。 */
    private fun planRootfsLayout(base: File): InstallPlan? {
        val usrDir = listOf(File(base, "usr"), File(base, "usr/local"))
            .firstOrNull { it.isDirectory } ?: return null
        val libDir = listOf(File(usrDir, "lib"), File(usrDir, "lib64"))
            .firstOrNull { it.isDirectory } ?: return null
        val files = mutableListOf<Pair<File, String>>()
        var wrapperSo: String? = null
        libDir.listFiles { f -> f.isFile && f.name.endsWith(".so") }
            .orEmpty().forEach { f ->
                files.add(f to f.name)
                if (f.name == WRAPPER_SO) wrapperSo = f.name
            }
        val driverSo = pickDriverSo(files.map { it.second })
        if (driverSo == null && wrapperSo == null) return null
        // usr/share/vulkan/icd.d/*.json 一并收进 slot（仅参考，激活用自生成 ICD）
        File(usrDir, "share/vulkan/icd.d").takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.forEach { files.add(it to it.name) }
        return InstallPlan(files, driverSo, wrapperSo)
    }

    /** B/C/D) 扁平包：上游实现 meta.json / yuzu 裸包 / wrapper 栈裸包。 */
    private fun planFlatLayout(base: File): InstallPlan? {
        val soNames = base.listFiles { f -> f.isFile && f.name.endsWith(".so") }
            .orEmpty().map { it.name }
        val wrapperSo = soNames.firstOrNull { it == WRAPPER_SO }
        // B) 上游实现 meta.json：libraryName 指定的驱动优先
        runCatching {
            val meta = File(base, META_NAME)
            if (meta.isFile) {
                val m = Regex("\"libraryName\"\\s*:\\s*\"([^\"]+)\"")
                    .find(meta.readText())?.groupValues?.get(1)
                if (!m.isNullOrBlank() && File(base, m).isFile) {
                    // v3.3.20（用户实测反馈修复）：致命 bug —— 旧版首条目
                    // `File(m)` 按【进程 CWD】解析而非 staging 目录，meta.json
                    // 包（yuzu zip / adpkg zip）必在 copyTo 抛 FileNotFoundException
                    // → 全部报“驱动落位失败”，即用户“无法安装 yuzu 类驱动”
                    // 的直接根因。改为 File(base, m)。
                    val files = mutableListOf(File(base, m).let { it to it.name })
                    base.listFiles { f -> f.isFile && f.name.endsWith(".so") }
                        .orEmpty().forEach { f -> if (f.name != m) files.add(f to f.name) }
                    val driverSo = if (m == WRAPPER_SO) pickDriverSo(soNames) else m
                    if (driverSo != null || wrapperSo != null) {
                        return InstallPlan(files, driverSo, wrapperSo)
                    }
                }
            }
        }
        // C/D) yuzu 裸包 / wrapper 栈裸包：根级 libvulkan*.so + 附属 .so
        val driverSo = pickDriverSo(soNames)
        if (driverSo == null && wrapperSo == null) return null
        val files = mutableListOf<Pair<File, String>>()
        base.listFiles { f -> f.isFile && f.name.endsWith(".so") }
            .orEmpty().forEach { files.add(it to it.name) }
        return InstallPlan(files, driverSo, wrapperSo)
    }

    // ------------------------------------------------------------------
    // 卸载（独立目录整体删除，不碰其他 slot 与 Mesa）
    // ------------------------------------------------------------------

    /** 卸载一个 slot。@return null 成功。 */
    fun uninstall(slot: String, log: (String) -> Unit = {}): String? {
        val dir = File(driverRoot, safeSlotName(slot))
        if (!dir.isDirectory) return "驱动未安装：$slot"
        // 路径越界防护（dir 必须直接位于 driverRoot 之下）
        if (dir.parentFile?.canonicalPath != driverRoot.canonicalPath)
            return "拒绝卸载：路径越界"
        return runCatching {
            if (!dir.deleteRecursively()) return "删除未完成（部分文件被占用，可重试）"
            log("驱动 $slot 已卸载（${dir.absolutePath}）")
            null
        }.getOrElse { "卸载异常：${it.message}" }
    }

    // ------------------------------------------------------------------
    // 旧版（v3.3.15 覆盖式安装）迁移
    // ------------------------------------------------------------------

    /**
     * v3.3.15 及以前的覆盖式安装会在 usr/share/linbox-wrapper/ 留下
     * <slot>.manifest + backup/<slot>/（被覆盖 Mesa 文件的备份）。
     * 首次访问时按清单恢复 rootfs 原文件并清掉旧痕迹（一次性，幂等）。
     */
    private fun migrateLegacy() {
        runCatching {
            val legacyRoot = rfs("usr/share/linbox-wrapper")
            if (!legacyRoot.isDirectory) return
            val manifests = legacyRoot.listFiles { f -> f.isFile && f.name.endsWith(".manifest") }
                .orEmpty()
            manifests.forEach { mf ->
                val slot = mf.name.removeSuffix(".manifest")
                val lines = mf.readLines().filter { it.isNotBlank() }
                val backedUp = lines
                    .filter { it.startsWith("backup\t") }
                    .map { it.removePrefix("backup\t").trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                lines.forEach { line ->
                    val kind = line.substringBefore('\t')
                    val rel = line.substringAfter('\t', "").trim()
                    if (rel.isBlank()) return@forEach
                    when (kind) {
                        "file" -> if (rel !in backedUp) rfs(rel).delete()
                        "backup" -> {
                            val bak = File(File(legacyRoot, "backup/$slot"), rel)
                            if (bak.isFile) {
                                val dest = rfs(rel)
                                dest.parentFile?.mkdirs()
                                bak.copyTo(dest, overwrite = true)
                            }
                        }
                    }
                }
                mf.delete()
            }
            // 旧 state / backup / ICD 残留清理
            File(legacyRoot, "state").delete()
            File(legacyRoot, "backup").takeIf { it.isDirectory }?.deleteRecursively()
            legacyRoot.listFiles { f -> f.isFile && f.name.startsWith("linbox-wrapper-") && f.name.endsWith(".json") }
                ?.forEach { it.delete() }
            // ICD 目录里旧的自生成清单
            rfs("usr/share/vulkan/icd.d").takeIf { it.isDirectory }
                ?.listFiles { f -> f.isFile && f.name.startsWith("linbox-wrapper-") }
                ?.forEach { it.delete() }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 解析 ICD json 的 ICD.library_path（宽容解析，任何失败返回 null）。 */
    private fun parseIcdLibraryPath(json: File): String? = runCatching {
        val text = json.readText()
        if (!text.contains("\"ICD\"")) return@runCatching null
        Regex("\"library_path\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
    }.getOrNull()

    /** zip 解压（yuzu 驱动包是 zip 格式；路径安全校验防 zip-slip）。 */
    private fun unzipTo(zip: File, dest: File): String? = runCatching {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val canonicalDest = dest.canonicalPath + File.separator
            while (entry != null) {
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(canonicalDest)) {
                    return@runCatching "zip 内路径越界：${entry.name}"
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        null
    }.getOrElse { "zip 解压失败：${it.message}" }
}
