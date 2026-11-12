package com.linbox.apps.containers.launcher

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * futex_waitv 探测改写（v3.2.3）—— proton 系 wine 包 Android 前缀构建
 * "静默阵亡"的设备侧根治（**免重编/免重装**）。
 *
 * ## 根因（proton_10.0 源码逐行核实 + 设备日志对齐）
 *
 * Valve 的 wine 树给 `start_main_thread()`（dlls/ntdll/unix/loader.c）加了
 * **无条件**的 `fsync_init(); esync_init();` 两行（官方/hangover 树没有）。
 * `fsync_init()`（dlls/ntdll/unix/fsync.c）先调 `do_fsync()` —— 而 proton 的
 * `do_fsync()` 在读 WINEFSYNC 环境变量**之前**就执行能力探测：
 *
 * ```c
 * syscall( __NR_futex_waitv, NULL, 0, 0, NULL, 0 );   // ← 无条件裸探测
 * do_fsync_cached = getenv("WINEFSYNC") && atoi(...) && errno != ENOSYS ...
 * ```
 *
 * Android 应用沙箱的 seccomp-bpf 对白名单外的 syscall 号直接
 * SECCOMP_RET_TRAP → SIGSYS（信号 31）**整进程击杀**，errno 永远不会被
 * 设置 —— 探测永远走不到 ENOSYS 分支。WINEFSYNC=0 救不了（探测在前），
 * WINEESYNC=0 也救不了 do_esync 之外的这条直呼路径。设备日志特征：
 * arm64ec 形态打到 `linbox: bionic heap tagging disabled`（__wine_main
 * 第二遍开头）之后戛然而止 —— virtual_init/init_environment/server 握手
 * 全程无输出，死在 fsync_init 的探测上，退出码 159（128+31）。
 * wineserver（server/fsync.c 同款 do_fsync）在处理 create_fsync 请求时
 * 同样阵亡 → 客户端阻塞在应答上 = 另一种"无输出挂死"。
 *
 * 构建侧 v3.1.4 起有 `disable_fsync_probe`（do_fsync 注入 `__ANDROID__`
 * return 0），但设备上存量旧 proton 包没打守卫 —— 本对象在启动链 1.5 步
 * （WineLayoutRepair 之后）对**已安装包**做同款就地修补：把 ELF 机器码里
 * "装载 syscall 号 449 到第一参数"的立即数改写成 getpid（aarch64=172 /
 * x86_64=39）。getpid 在任何 seccomp 策略下都合法、必定成功、不动 errno，
 * 探测结果照旧走 WINEFSYNC=0 → 关（fsync 本就不可能在 Android 用）。
 *
 * ## 字节模式（本机 gcc 实测 + 编码手工核对，上下文双重校验防误伤）
 *
 * - x86_64：`mov edi,449` = `BF C1 01 00 00`（gcc -O2 实测输出），
 *   周边是变参清零（xor esi/edx/ecx/r8d/r9d/eax）+ call/jmp —— 校验
 *   ±24 字节内 ≥3 处清零指令、+5..+40 内有 E8/E9 调用；改写为
 *   `mov edi,39`（仅后 4 字节立即数）。
 * - aarch64：`movz x0/w0,#449` = `20 38 80 D2` / `20 38 80 52`，周边是
 *   `mov xN,xzr`（`AA 1F 03 E0+E`，32 位变体 `2A 1F 03 E0+E`）×5 + `bl`
 *   （第 4 字节 0x94..0x97）；改写为 `movz x0/w0,#172`（仅前 2 字节：
 *   `20 38` → `80 15`）。
 *
 * 别的 futex_waitv 调用点（futex_wait_multiple 等）参数是指针/计数，
 * 无清零序列 → 上下文校验不过 → 不动。hangover/official 树没有 fsync.c
 * → 无模式 → 零改动。幂等：改写后 449 模式消失，二次运行自然空扫描。
 */
object WineProbePatch {

    /** futex_waitv 的 syscall 号（x86_64 与 aarch64 相同）。 */
    private const val NR_FUTEX_WAITV = 449

    /** 各架构 getpid 号（探测改写目标：任何 seccomp 策略下都合法）。 */
    private const val NR_GETPID_AARCH64 = 172
    private const val NR_GETPID_X86_64 = 39

    /** 已扫描记录（canonicalPath|size → 站点数），避免每次启动重扫大文件。 */
    private val scanned = ConcurrentHashMap<String, Int>()

    // ---- 字节模式（int 数组避免 Kotlin Byte 有符号比较噪音） ----
    private val MOVZ_X0_449 = intArrayOf(0x20, 0x38, 0x80, 0xD2) // movz x0, #449
    private val MOVZ_W0_449 = intArrayOf(0x20, 0x38, 0x80, 0x52) // movz w0, #449
    private val MOV_EDI_449 = intArrayOf(0xBF, 0xC1, 0x01, 0x00, 0x00) // mov edi, 449

    /** movz x0/w0, #172（getpid）与 449 形态仅前两字节不同。 */
    private val GETPID_IMM_HEAD = byteArrayOf(0x80.toByte(), 0x15)

    /** mov edi, 39（getpid）—— 覆盖原 449 的 4 字节立即数。 */
    private val GETPID_IMM_X86 = byteArrayOf(0x27, 0x00, 0x00, 0x00)

    /**
     * 对 wine 目录做探测改写（幂等、不阻断启动链）。
     *
     * 目标文件：`lib/wine` 目录下各 `*-unix` 形态里的 `ntdll.so`
     * （fsync_init/do_fsync 所在）与 wineserver（旧布局 bin/ 真实 ELF；
     * 新布局符号链接解析回 lib 侧真实文件）。
     *
     * @param wineDir wine 安装目录（/usr/opt/<槽位>）
     * @param log     决策行回调（只在真实发生改写时输出，正常启动零噪音）
     * @return true（恒真 —— 修补失败也不拦 wine，让 wine 自己给证据）
     */
    fun apply(wineDir: File, log: (String) -> Unit = {}): Boolean {
        return try {
            applyImpl(wineDir, log)
        } catch (e: Exception) {
            log(
                "警告: futex_waitv 探测改写异常（${e.message ?: e.javaClass.simpleName}）" +
                    "—— 不影响本次启动；旧 proton 包若仍以 159（SIGSYS）阵亡请反馈"
            )
            true
        }
    }

    private fun applyImpl(wineDir: File, log: (String) -> Unit): Boolean {
        val targets = LinkedHashSet<String>()
        val libWine = File(wineDir, "lib/wine")
        if (libWine.isDirectory) {
            libWine.listFiles { f -> f.isDirectory && f.name.endsWith("-unix") }
                .orEmpty().forEach { dir ->
                    File(dir, "ntdll.so").takeIf { it.isFile }?.let { addCanonical(targets, it) }
                    File(dir, "wineserver").takeIf { it.isFile }?.let { addCanonical(targets, it) }
                }
        }
        // wineserver：旧布局 bin/ 下是真实 ELF；新布局 bin/wineserver 为符号链接
        // （两跳 → wine loader 之外的真实文件）—— canonicalFile 全部解析到位。
        File(wineDir, "bin/wineserver").takeIf { it.isFile }?.let { addCanonical(targets, it) }

        var files = 0
        var sites = 0
        for (path in targets) {
            val f = File(path)
            if (!f.isFile || f.length() < 64L) continue
            val key = "$path|${f.length()}"
            if (scanned.containsKey(key)) continue
            val patched = runCatching { patchFile(f) }.getOrDefault(0)
            scanned[key] = patched
            if (patched > 0) {
                files++
                sites += patched
                log(
                    "已改写 ${f.name}: futex_waitv 探测 ×$patched（syscall 号 $NR_FUTEX_WAITV→getpid，" +
                        "就地免疫 Android seccomp SIGSYS；WINEFSYNC=0 下探测结果本就为关）"
                )
            }
        }
        if (files > 0) {
            log(
                "proton 旧包探测改写完成（$files 个文件 / $sites 处）—— 前缀构建的" +
                    "159 静默阵亡根因已消除，无需重编 wine 包"
            )
        }
        return true
    }

    private fun addCanonical(set: MutableSet<String>, f: File) {
        runCatching { set.add(f.canonicalFile.absolutePath) }
    }

    /**
     * 单文件扫描 + 改写。返回改写站点数（0 = 干净/非目标架构/无模式）。
     * 只在确有改写时落盘（RandomAccessFile 定点写立即数，其余字节零触碰）。
     */
    private fun patchFile(file: File): Int {
        val data = file.readBytes()
        if (data.size < 64) return 0
        if (data[0] != 0x7F.toByte() || data[1] != 'E'.code.toByte() ||
            data[2] != 'L'.code.toByte() || data[3] != 'F'.code.toByte()
        ) return 0
        val elfClass = data[4].toInt() and 0xFF
        val machine = (data[18].toInt() and 0xFF) or ((data[19].toInt() and 0xFF) shl 8)
        val patches: List<Pair<Int, ByteArray>> = when {
            elfClass == 2 && machine == 183 -> scanAarch64(data) // EM_AARCH64
            elfClass == 2 && machine == 62 -> scanX86_64(data)   // EM_X86_64
            else -> emptyList() // 32 位/其它架构：本仓库形态不含，跳过
        }
        if (patches.isEmpty()) return 0
        RandomAccessFile(file, "rw").use { raf ->
            for ((off, bytes) in patches) {
                raf.seek(off.toLong())
                raf.write(bytes)
            }
        }
        return patches.size
    }

    // ------------------------------------------------------------------
    // aarch64：movz x0/w0,#449 + 变参清零（mov xN,xzr ×N）+ bl
    // ------------------------------------------------------------------

    private fun scanAarch64(data: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        for (pat in listOf(MOVZ_X0_449, MOVZ_W0_449)) {
            var off = indexOf(data, pat, 0)
            while (off >= 0) {
                if (aarch64ContextOk(data, off)) {
                    // movz x0/w0,#172 与 #449 仅低 16 位立即数不同（imm16 在
                    // 指令字节 1..2，改写前两字节 20 38 → 80 15 即可）。
                    out.add(off to GETPID_IMM_HEAD)
                }
                off = indexOf(data, pat, off + 4)
            }
        }
        return out
    }

    /** 探测调用的上下文：后续 ~56 字节内 ≥3 个 mov xN,xzr（64/32 位变体）且出现 bl。 */
    private fun aarch64ContextOk(data: ByteArray, off: Int): Boolean {
        var zeroMoves = 0
        var hasCall = false
        val limit = minOf(data.size - 4, off + 60)
        var k = off + 4
        while (k <= limit) {
            val b0 = data[k].toInt() and 0xFF
            val b1 = data[k + 1].toInt() and 0xFF
            val b2 = data[k + 2].toInt() and 0xFF
            val b3 = data[k + 3].toInt() and 0xFF
            if (b0 in 0xE0..0xE7 && b1 == 0x03 && b2 == 0x1F && (b3 == 0xAA || b3 == 0x2A)) {
                zeroMoves++ // mov xN,xzr / mov wN,wzr
            }
            if (b3 in 0x94..0x97) hasCall = true // bl imm26
            k += 4
        }
        return zeroMoves >= 3 && hasCall
    }

    // ------------------------------------------------------------------
    // x86_64：mov edi,449 + 变参清零（xor r32）+ call/jmp
    // ------------------------------------------------------------------

    private fun scanX86_64(data: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var off = indexOf(data, MOV_EDI_449, 0)
        while (off >= 0) {
            if (x86ContextOk(data, off)) {
                out.add((off + 1) to GETPID_IMM_X86) // 仅覆盖 4 字节立即数
            }
            off = indexOf(data, MOV_EDI_449, off + 1)
        }
        return out
    }

    /** 探测调用的上下文：±24 字节内 ≥3 处变参清零、+5..+40 内有 call(E8)/jmp(E9)。 */
    private fun x86ContextOk(data: ByteArray, off: Int): Boolean {
        var zeros = 0
        val lo = maxOf(0, off - 24)
        val hi = minOf(data.size - 3, off + 24)
        var k = lo
        while (k <= hi) {
            val b0 = data[k].toInt() and 0xFF
            val b1 = data[k + 1].toInt() and 0xFF
            if (b0 == 0x31 && (b1 == 0xF6 || b1 == 0xD2 || b1 == 0xC9 || b1 == 0xC0)) {
                zeros++
                k += 2
                continue
            }
            if (b0 == 0x45 && b1 == 0x31) {
                val b2 = data[k + 2].toInt() and 0xFF
                if (b2 == 0xC0 || b2 == 0xC9) {
                    zeros++
                    k += 3
                    continue
                }
            }
            k++
        }
        if (zeros < 3) return false
        val callHi = minOf(data.size - 1, off + 40)
        var c = off + 5
        while (c < callHi) {
            val b = data[c].toInt() and 0xFF
            if (b == 0xE8 || b == 0xE9) return true
            c++
        }
        return false
    }

    /** 无符号字节序列查找（朴素匹配，文件量级 MB、模式 4-5 字节足够快）。 */
    private fun indexOf(data: ByteArray, pat: IntArray, from: Int): Int {
        val n = data.size
        val m = pat.size
        if (m == 0 || n < m) return -1
        var i = maxOf(0, from)
        val last = n - m
        outer@ while (i <= last) {
            for (j in 0 until m) {
                if ((data[i + j].toInt() and 0xFF) != pat[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }
}
