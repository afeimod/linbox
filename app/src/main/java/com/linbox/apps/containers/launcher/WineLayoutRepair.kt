package com.linbox.apps.containers.launcher

import android.system.Os
import java.io.File
import java.nio.file.Files

/**
 * wine 10.17+ 安装布局自愈（v3.2.0）—— hangover/official 10.17+ 前缀构建
 * "could not load ntdll.so: .../bin/ntdll.so not found" 的设备侧根治。
 *
 * ## 根因（wine 10.18 loader/main.c 实证）
 *
 * wine 10.17 起上游改了安装布局：loader 本体装进 `lib/wine/<arch>-unix/`
 * （与 ntdll.so **同目录**），`bin/` 不再创建 wine 本体（只剩指向 "wine"
 * 的悬空符号链接）。loader 的 `main()` 优先经 `try_dlopen( /proc/self/exe )`
 * 定位 ntdll.so —— **按 loader 自身真实所在目录** 找 `dir/ntdll.so`
 * （`realpath_dirname` → `build_path(dir, "ntdll.so")`；/proc/self/exe 由
 * 内核解析全部符号链接，永远指向真实文件）。
 *
 * v3.1.6 的"loader 物化"把 lib 侧 loader **复制**成 `bin/wine` —— 副本的
 * /proc/self/exe 在 bin/，dlopen 找 `bin/ntdll.so` 必失败（设备实测
 * wine-10-18 两形态均报 "library .../bin/ntdll.so not found" /
 * "Cannot dlopen(.../bin/ntdll.so...)"）。bin 工具被规整成 bin/wine 的
 * 副本，同样全炸。
 *
 * ## 修复
 *
 * `bin/wine`（与被规整过的 bin 工具）改为**相对符号链接**指回 lib 侧
 * loader：/proc/self/exe 解析符号链接回到 lib 目录，ntdll.so 就在旁边；
 * argv[0] 仍保留 bin/… 路径，loader 的 check_command_line() 按 argv[0]
 * basename 分发（wine 官方符号链接机制同款语义）。
 *
 * 本对象在每次容器启动链第 1.5 步运行（幂等）：
 *  - 旧布局（lib 侧无 loader，bin/wine 本就是真实 ELF）完全不受影响；
 *  - 已是正确符号链接的包零改动；
 *  - v3.1.6 物化包（bin/wine 为副本）/ 半损包就地换回符号链接 ——
 *    **设备上的旧包无需重装/重编即被治好**。
 *
 * 构建侧（bionic-rootfs 两配方 pre_package）已同步改为符号链接物化，
 * 新包生而正确；本自愈兜历史存量。
 *
 * ## v3.2.3 新增：wine-preloader 停用（bin/ 入口改名为 *.linbox-disabled）
 *
 * wine ≤10 树（proton_8/9/10、official ≤10.17）的 `__wine_main` 首遍会经
 * `pre_exec() → loader_exec() → preloader_exec()` 把自己重新执行一遍
 * wine-preloader（预加载器：手写 ELF 加载器，裸 syscall 自映射 bin/wine
 * 及其 ELF 解释器后跳进动态链接器）；子进程同样经 preloader_exec 走它。
 * 设备实测：该交接在 box64 仿真下静默阵亡（proton x86_64 前缀构建零输出，
 * 连预加载器的 "_r_debug not found in ld.so" 告警都来不及打）。
 *
 * 停用方式取上游自己的兜底：`preloader_exec()` 在 wine-preloader 文件缺失
 * （execv → ENOENT）时自动退回 `execv( argv[1], argv + 1 )` 直连 loader
 * （proton_10.0 / hangover 两树源码逐行核实，即 HAVE_WINE_PRELOADER 未
 * 定义时的官方行为；地址空间保留由 virtual_init 的无预加载器分支自完）。
 * 因此把 bin/ 下的 wine-preloader / wine64-preloader 改名即等价于"构建时
 * 未启用预加载器"—— 首进程与全部子进程一并切换，可逆（改回名字即恢复）。
 *
 * 配套：WINELOADERNOEXEC=1（WineEnvironment ①b）让首进程直接跳过重执行
 * 分支；构建侧 disable_preloader_android（两配方）让新包代码层不再引用
 * 预加载器；linbox-wine 脚本同步反转（不再重建链接）。
 */
object WineLayoutRepair {

    /** 与 loader 分发相关的 bin 工具（wine 官方同款符号链接组）。 */
    private val BIN_TOOLS = listOf(
        "wineboot", "winecfg", "reg", "regedit", "regsvr32", "msiexec", "rundll32"
    )

    /** v3.2.3：需停用的预加载器入口名（preloader_exec 按 loader 名尾缀二选一）。 */
    private val PRELOADER_NAMES = listOf("wine-preloader", "wine64-preloader")

    /** bin/wine 期望的符号链接目标（相对路径，相对 bin/ 目录）。 */
    private const val WINE_LINK_TARGET = "../lib/wine/%s/wine"

    /**
     * 确保 wine 目录的 loader 布局可用（wine 10.17+ 布局检测 + 自愈）。
     *
     * @param wineDir wine 安装目录（/usr/opt/<槽位>）
     * @param log     决策行回调（进会话日志）
     * @return true 表示布局就绪（旧布局 / 已正确 / 已修复）
     */
    fun ensure(wineDir: File, log: (String) -> Unit = {}): Boolean {
        return try {
            ensureImpl(wineDir, log)
        } catch (e: Exception) {
            log(
                "警告: wine 布局自愈异常（${e.message ?: e.javaClass.simpleName}）——" +
                    "若 wine 报 could not load ntdll.so 请重装该 wine 包"
            )
            true // 自愈失败不阻断启动链（让 wine 自己报出确切错误）
        }
    }

    private fun ensureImpl(wineDir: File, log: (String) -> Unit): Boolean {
        val binDir = File(wineDir, "bin")

        // ---- 0) wine-preloader 停用（v3.2.3，先行于布局检测）----
        // 预加载器停用标记（步骤 0 与后续步骤共用）
        var repaired0 = false
        // 必须对**所有布局**生效：旧布局（proton_10.0 / wine ≤9.x，
        // bin/wine 为真实 ELF）恰恰是预加载器风险的主力包型 —— 若放在
        // 下方 lib 布局检测之后，旧布局的提前 return 会跳过本步。
        // preloader_exec() 找不到 bin/wine-preloader 时走官方兕底
        // `execv(argv[1], argv+1)` 直连 loader —— 改名即停用，可逆、幂等；
        // 旧布局（bin 下真实 ELF）与新布局（符号链接）统一处理；
        // lib 侧本体不动（无任何查找路径指向它，仅占位无害）。
        if (binDir.isDirectory) {
            for (preloaderName in PRELOADER_NAMES) {
                val active = File(binDir, preloaderName)
                if (active.exists() || Files.isSymbolicLink(active.toPath())) {
                    val disabled = File(binDir, "$preloaderName.linbox-disabled")
                    runCatching {
                        if (disabled.exists() || Files.isSymbolicLink(disabled.toPath())) {
                            disabled.delete()
                        }
                        Os.rename(active.absolutePath, disabled.absolutePath)
                        repaired0 = true
                        log(
                            "已停用 bin/$preloaderName（v3.2.3：Android 直连 loader —— " +
                                "preloader_exec 的 ENOENT 官方兕底路径；box64 仿真交接静默阵亡一并消除）"
                        )
                    }.onFailure {
                        log("警告: 停用 bin/$preloaderName 失败（${it.message}）")
                    }
                }
            }
        }

        // ---- 1) 检测 wine 10.17+ lib 布局：lib/wine/<arch>-unix/wine 存在 ----
        val libDir = File(wineDir, "lib/wine")
        if (!libDir.isDirectory) return true
        val archDir = libDir.listFiles { f -> f.isDirectory && f.name.endsWith("-unix") }
            ?.firstOrNull { File(it, "wine").isFile } ?: return true
        val libLoader = File(archDir, "wine")
        val libLoaderAbs = libLoader.absolutePath

        if (!binDir.isDirectory) {
            // lib 布局而 bin 目录整个缺失：异常包，补目录并建链接
            runCatching { binDir.mkdirs() }
        }

        var repaired = repaired0

        // ---- 2) bin/wine → ../lib/wine/<arch>-unix/wine ----
        val binWine = File(binDir, "wine")
        val expectedTarget = "../lib/wine/${archDir.name}/wine"
        if (!isCorrectSymlink(binWine, expectedTarget, libLoaderAbs)) {
            // 说明：只要 lib 侧 loader 存在，bin/wine 的权威形态就是符号链接。
            // 真实副本（v3.1.6 物化产物）/ 悬空链接 / 缺失 一律重建。
            runCatching { binWine.delete() }
            runCatching {
                Os.symlink(expectedTarget, binWine.absolutePath)
                repaired = true
                log(
                    "已自愈 bin/wine → $expectedTarget（wine 10.17+ loader 布局；" +
                        "旧物化副本会让 loader 找 bin/ntdll.so 必失败）"
                )
            }.onFailure {
                log("警告: bin/wine 符号链接重建失败（${it.message}）")
            }
        }

        // ---- 4) bin 工具 → wine（两跳符号链接，argv[0] basename 分发不受影响）----
        for (tool in BIN_TOOLS) {
            val f = File(binDir, tool)
            if (!f.exists() && !Files.isSymbolicLink(f.toPath())) continue
            if (isCorrectSymlink(f, "wine", null)) continue
            runCatching { f.delete() }
            runCatching {
                Os.symlink("wine", f.absolutePath)
                repaired = true
            }.onFailure {
                log("警告: bin/$tool 符号链接重建失败（${it.message}）")
            }
        }
        if (repaired) {
            log("wine 布局自愈完成（bin/wine 及工具已指回 lib 侧 loader，ntdll.so 可定位）")
        }
        return true
    }

    /**
     * 链接是否已是指定目标的符号链接。
     *
     * @param link           链接文件
     * @param expectedTarget 期望的相对目标字符串（readlink 精确比对）
     * @param canonicalPoint 可选的规范路径锚点（bin/wine 与 lib loader 互查用；
     *                       目标字符串形态各异时兜底比对真实指向）
     */
    private fun isCorrectSymlink(
        link: File,
        expectedTarget: String,
        canonicalPoint: String?
    ): Boolean {
        if (!Files.isSymbolicLink(link.toPath())) return false
        val actual = runCatching { Os.readlink(link.absolutePath) }.getOrNull() ?: return false
        if (actual == expectedTarget) return true
        if (canonicalPoint != null) {
            val canon = runCatching { link.canonicalPath }.getOrNull()
            if (canon == canonicalPoint) return true
        }
        return false
    }
}
