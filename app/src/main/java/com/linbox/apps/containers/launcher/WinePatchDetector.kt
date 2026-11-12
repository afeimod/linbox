package com.linbox.apps.containers.launcher

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * v3.3.7 —— wine 包补丁状态探测器（bionic-rootfs 配方补丁的运行期对账）。
 *
 * ## 背景（用户 v3.3.6 实测）
 *
 * x86_64 wine（box64 翻译执行）在零拷贝直读下正常；**arm64ec wine**
 * 却"还是 media 报错 + 丢失中文环境" —— 根因不在 App 侧启动链，而在
 * 存量 wine 包本身缺两类配方补丁：
 *
 *  1. **noexec 回退补丁**（bionic-rootfs `projects/wine/0011*` 与
 *     `projects/hangover-wine/0014*`）：wine 的 `map_file_into_view`
 *     在 noexec 挂载（/storage/emulated/0 恒 noexec）上对带执行位的
 *     共享映射硬失败 → 游戏 "Could not load required media"。arm64ec
 *     原生执行形态必须有它；hangover-wine 配方 v3.3.6 前只有 10.x/
 *     11.x 两份，**wine-9.x 存量包全 hunk 失败 = 裸奔**（v3.3.7 补
 *     0014c）。x86_64 形态靠 box64 翻译执行侥幸不依赖执行位。
 *  2. **locale 补丁**（`projects/wine/0009` 与 v3.3.7 新增的
 *     `projects/hangover-wine/0009b`）：bionic setlocale 恒返回
 *     "C.UTF-8" → wine 恒英文界面 + ACP=1252（中文路径/文件名乱码，
 *     即用户所说"arm64ec 丢失了中文环境"）。x86_64 形态 v3.3.2 起
 *     带补丁所以正常 —— arm64ec 形态此前一直没带。
 *
 * ## 检测机制
 *
 * v3.3.7 起全部相关补丁把唯一标记串编进 WARN 格式串（`wine/debug.h`
 * 的 WARN 宏把格式串无条件编进 ntdll.so —— 运行期才按调试级别过滤）：
 *  - noexec 回退 → `LinBox-noexec-fallback`
 *  - locale 修复 → `LinBox-locale-lc-all`
 *
 * 本对象按 wine 槽位目录递归扫描 lib/wine 下的 unix 侧 .so（ntdll 所在），
 * 结果以「目录 + 最新 mtime」为键缓存 —— 每个槽位每次安装变更只扫一次
 * （ntdll.so 数 MB，全量字节扫描毫秒级）。
 */
object WinePatchDetector {

    /** noexec 回退补丁标记（bionic-rootfs 0011/0014 系列补丁的 WARN 串）。 */
    const val MARKER_NOEXEC = "LinBox-noexec-fallback"

    /** locale 补丁标记（0009/0009b 的 WARN 串）。 */
    const val MARKER_LOCALE = "LinBox-locale-lc-all"

    /** 一个 wine 槽位的补丁状态。 */
    data class PatchState(
        val noexecFallback: Boolean,
        val localeLcAll: Boolean,
        /** 扫描过的 ntdll unix 库（空 = 没找到可扫描目标）。 */
        val scannedTargets: List<String>
    )

    /** 缓存键 = wineDir 绝对路径；值 = (mtimeKey, state)。 */
    private val cache = ConcurrentHashMap<String, Pair<String, PatchState>>()

    /**
     * 探测 wine 槽位的补丁状态（带缓存）。
     *
     * @param wineDir wine 安装目录（/usr/opt/<槽位>）
     */
    fun stateOf(wineDir: File): PatchState {
        val dir = wineDir.takeIf { it.isDirectory } ?: return PatchState(false, false, emptyList())
        // 缓存键：目录 + lib/wine 树最新 mtime —— 重装/换包自动失效
        val libsRoot = File(dir, "lib/wine")
        val mtimeKey = runCatching {
            var newest = 0L
            libsRoot.walkTopDown()
                .filter { it.isFile }
                .take(2048)   // 防极端大树上耗时
                .forEach { f -> val t = f.lastModified(); if (t > newest) newest = t }
            newest
        }.getOrDefault(0L)
        val key = "$mtimeKey"
        cache[dir.absolutePath]?.let { (k, st) ->
            if (k == key) return st
        }

        val st = scan(dir)
        cache[dir.absolutePath] = key to st
        return st
    }

    /** 便捷判定：含 noexec 回退补丁。 */
    fun hasNoexecFallback(wineDir: File): Boolean = stateOf(wineDir).noexecFallback

    /** 便捷判定：含 locale 补丁。 */
    fun hasLocalePatch(wineDir: File): Boolean = stateOf(wineDir).localeLcAll

    /**
     * 全量扫描 wine 目录下的 ntdll unix 库。
     *
     * 目标布局（wine 官方安装树，两种形态）：
     *  - `<dir>/lib/wine/<arch>-unix/ntdll.so`（aarch64-unix / x86_64-vunix / i386-unix…）
     *  - 老布局 `<dir>/lib/wine/ntdll.so`
     * bin/wine 本体（预加载器）不含 virtual.c/env.c 的 unix 侧代码，扫它
     * 无意义 —— 只扫 .so。找不到任何目标时返回 false/false（诚实：无法
     * 判定按"未打补丁"处理，让指引链路兜底）。
     */
    private fun scan(wineDir: File): PatchState {
        val targets = mutableListOf<File>()
        val libWine = File(wineDir, "lib/wine")
        if (libWine.isDirectory) {
            libWine.walkTopDown()
                .filter { it.isFile && it.name.startsWith("ntdll") && it.name.endsWith(".so") }
                .take(8)
                .forEach { targets.add(it) }
        }
        if (targets.isEmpty()) {
            // 兜底：整棵 lib/ 下的 ntdll*.so（防非标准布局）
            val lib = File(wineDir, "lib")
            if (lib.isDirectory) {
                lib.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("ntdll") && it.name.endsWith(".so") }
                    .take(8)
                    .forEach { targets.add(it) }
            }
        }
        if (targets.isEmpty()) return PatchState(false, false, emptyList())

        var noexec = false
        var locale = false
        for (t in targets) {
            runCatching {
                t.inputStream().use { s ->
                    val bytes = s.readBytes()
                    if (!noexec && containsAscii(bytes, MARKER_NOEXEC)) noexec = true
                    if (!locale && containsAscii(bytes, MARKER_LOCALE)) locale = true
                }
            }
            if (noexec && locale) break
        }
        return PatchState(noexec, locale, targets.map { it.absolutePath })
    }

    /** 字节数组 ASCII 子串查找（标记串纯 ASCII，无需解码）。 */
    private fun containsAscii(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        if (n.isEmpty() || haystack.size < n.size) return false
        val first = n[0]
        val limit = haystack.size - n.size
        var i = 0
        while (i <= limit) {
            if (haystack[i] == first) {
                var j = 1
                while (j < n.size && haystack[i + j] == n[j]) j++
                if (j == n.size) return true
            }
            i++
        }
        return false
    }

    // ------------------------------------------------------------------
    // 指引文案（启动链 / 体检共用 —— 用户能直接照做的动作）
    // ------------------------------------------------------------------

    /** noexec 补丁缺失时的指引行（供会话日志逐行输出）。 */
    fun noexecGuidanceLines(wineDir: File): List<String> = listOf(
        "当前 wine 包（${wineDir.name}）缺 noexec 回退补丁（0011/0014 系列）——",
        "  arm64ec/hangover 原生执行在 /storage/emulated/0（noexec）上会报 \"Could not load required media\"",
        "  x86_64+box64 形态不依赖执行位可正常运行，属已知差异",
        "→ 解决：GitHub 仓库 Actions → build-wine-bionic → Run workflow（形态选 arm64ec）→ 重装新 wine 包",
        "→ 新包含 LinBox-noexec-fallback 标记；本提示即自动消失（构建含硬校验，不会再产出裸奔包）"
    )

    /** locale 补丁缺失时的指引行（供会话日志逐行输出）。 */
    fun localeGuidanceLines(wineDir: File): List<String> = listOf(
        "当前 wine 包（${wineDir.name}）缺中文 locale 补丁（0009/0009b）——",
        "  bionic 下 wine 界面恒英文、ACP=1252（游戏内中文路径/文件名会乱码）",
        "→ 解决：GitHub 仓库 Actions → build-wine-bionic → Run workflow → 重装新 wine 包",
        "→ 新包含 LinBox-locale-lc-all 标记；LANG/LC_ALL=zh_CN.UTF-8 即得中文界面 + ACP=936"
    )
}
