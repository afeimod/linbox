package com.linbox.apps.containers.launcher

import android.system.Os
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 纯 Kotlin tar.gz 解压器（XKB 键盘数据专用）。
 *
 * 背景：X 服务（CmdEntryPoint/libXlorie）初始化时必须拿到
 * XKB_CONFIG_ROOT（rules/、keycodes/…）；旧链路里这段数据由
 * `bin/termux-x11` 脚本内的 `tar -xzf` 解压。原生链路（X11Support
 * 直接 app_process 拉起 X 服务）不再经任何 shell，解压职责由本类承接。
 *
 * 实现为最小可靠集：ustar/GNU tar 均兼容的 512 字节头解析，支持
 * 普通文件（'0'/'\0'）、目录（'5'）、符号链接（'2'，经
 * [Os.symlink]）与 GNU 长文件名（'L'）。xkb 数据包实测仅含
 * 文件与目录（assets/termux/x11-xkb.tar.gz，318 个成员），但按
 * 上述完整特征集防御 bootstrap 归档里自带的变体。
 *
 * 不支持（遇到即跳过并记日志，不影响其余成员）：硬链接、GNU
 * LongLink、PAX 扩展头 —— xkb 数据包不含这些形态。
 */
internal object XkbExtractor {

    private const val BLOCK = 512

    /**
     * 解压 xkb 数据包到目标目录（自动识别 gzip / 纯 tar）。
     *
     * v3.2.23：魔数自适应 —— 文件名不再可信（三方重打包可能剥掉
     * gzip 层），入口先读头部：gzip 魔数（1F 8B）→ gzip 解包；
     * 否则直接按纯 tar 流解析（偏移 257 有 ustar 字样则必为纯
     * tar；无字样也按纯 tar 容错，坏头会在首块判出并报错，
     * 不会误写文件）。xkb 数据约定为 tar.gz，xz 场景不存在。
     *
     * @param tarGz   压缩包（如 etc/linbox/x11/xkb.tar.gz）
     * @param destDir 目标目录（自动创建；成员按包内相对路径落盘）
     * @param log     警告输出（跳过的成员等）
     * @return true 全部成功；false 有致命错误（IO 异常/坏头）
     */
    fun extract(tarGz: File, destDir: File, log: (String) -> Unit = {}): Boolean {
        return try {
            tarGz.inputStream().buffered().use { raw ->
                // 不重开流、不依赖 PushbackInputStream 大小限制：先读
                // 头 6 字节判型，再拼回（mark/reset 缓冲 64KB 足够容下
                // 判型头部与后续 tar 头块）。
                raw.mark(64 * 1024)
                val head = ByteArray(6)
                var n = 0
                while (n < head.size) {
                    val r = raw.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                raw.reset()
                val input: InputStream = when {
                    n >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() ->
                        GZIPInputStream(raw)
                    else -> raw   // 纯 tar（或未知压缩）直接按 tar 流解析
                }
                extractTar(input, destDir, log)
            }
        } catch (e: Exception) {
            log("XKB 解压失败：${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun extractTar(input: InputStream, destDir: File, log: (String) -> Unit): Boolean {
        destDir.mkdirs()
        var pendingLongName: String? = null
        while (true) {
            val header = ByteArray(BLOCK)
            val read = readFully(input, header)
            when (read) {
                0 -> return true              // 干净 EOF
                BLOCK -> Unit                  // 正常头
                else -> throw EOFException("损坏的 tar：头部不完整（$read 字节）")
            }
            // 全零头 = 归档结束
            if (header.all { it.toInt() == 0 }) return true

            val name = pendingLongName ?: readString(header, 0, 100).trimEnd('\u0000')
            pendingLongName = null
            val prefix = readString(header, 345, 155).trimEnd('\u0000')
            val size = parseOctal(header, 124, 12)
            val typeFlag = header[156].toInt() and 0xFF
            val linkName = readString(header, 157, 100).trimEnd('\u0000')

            // 路径归一：去 ./ 前缀与绝对前缀，拒绝 .. 逃逸
            val rawPath = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val safePath = rawPath.removePrefix("./").removePrefix("/")
            if (safePath.isEmpty() || safePath == "." ||
                safePath.split('/').any { it == ".." }
            ) {
                skipData(input, size)
                continue
            }
            val target = File(destDir, safePath)

            when (typeFlag) {
                'L'.code -> {
                    // GNU LongName：数据块即下一个条目的真实名字。
                    // 修复①：size 来自 parseOctal 返回 Long，ByteArray 构造器
                    // 需要 Int（编译报 85:42 Long→Int）；名字不可能超过 4MB，
                    // 超限/负值视为坏包：跳过数据、回退普通头内名字，
                    // 防止 toInt() 溢出成负数抛 NegativeArraySizeException。
                    // 修复②：删除本分支内的 skipPadding —— when 块之后已对
                    // 所有分支统一补一次填充；此前两处都执行会把 LongName
                    // 条目的填充跳两遍，多吞 (512 - size%512) 字节，正好吃掉
                    // 下一个条目的头部，导致整包解析错位。
                    if (size < 0 || size > 4L * 1024 * 1024) {
                        log("跳过异常 GNU LongName 条目（size=$size）")
                        skipData(input, size)
                        pendingLongName = null
                    } else {
                        val data = ByteArray(size.toInt())
                        readFully(input, data)
                        pendingLongName = data.toString(Charsets.UTF_8).trimEnd('\u0000')
                    }
                }
                '5'.code, 'd'.code -> {
                    target.mkdirs()
                    skipData(input, size)   // 目录尺寸应为 0，防御性跳过
                }
                '2'.code -> {
                    target.parentFile?.mkdirs()
                    runCatching { target.delete() }
                    try {
                        Os.symlink(linkName, target.absolutePath)
                    } catch (e: Exception) {
                        log("跳过符号链接 $safePath → $linkName（${e.message}）")
                    }
                    skipData(input, size)
                }
                '0'.code, 0, '7'.code -> {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        var remaining = size
                        val buf = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) throw EOFException("损坏的 tar：$safePath 数据不完整")
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    runCatching { Os.chmod(target.absolutePath, 436) } // 0664
                }
                else -> {
                    // 未知类型（PAX 'x'/'g'、硬链接 '1' 等）：跳过数据
                    log("跳过 tar 成员 $safePath（类型 '${typeFlag.toChar()}'）")
                    skipData(input, size)
                }
            }
            skipPadding(input, size)
        }
    }

    private fun readString(b: ByteArray, off: Int, len: Int): String {
        val end = minOf(off + len, b.size)
        var last = off
        while (last < end && b[last] != 0.toByte()) last++
        return String(b, off, last - off, Charsets.UTF_8)
    }

    /** 解析八进制字段（前导空格/零忽略；全空 = 0）。 */
    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        var i = off
        val end = off + len
        while (i < end && (b[i] == ' '.code.toByte() || b[i] == '0'.code.toByte())) i++
        var value = 0L
        while (i < end && b[i] in '0'.code.toByte()..'7'.code.toByte()) {
            value = (value shl 3) or (b[i] - '0'.code.toByte()).toLong()
            i++
        }
        return value
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var done = 0
        while (done < buf.size) {
            val n = input.read(buf, done, buf.size - done)
            if (n < 0) return done
            done += n
        }
        return done
    }

    private fun skipData(input: InputStream, size: Long) {
        var remaining = size
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n <= 0) {
                if (input.read() < 0) return
                remaining -= 1
            } else remaining -= n
        }
    }

    /** 数据块后的 512 对齐填充。 */
    private fun skipPadding(input: InputStream, size: Long) {
        val rem = (size % BLOCK).toInt()
        if (rem != 0) skipData(input, (BLOCK - rem).toLong())
    }
}
