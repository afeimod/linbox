package com.linbox.apps.filemanager

import android.webkit.MimeTypeMap
import com.linbox.util.SafeFileOps
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * v3.3.40：文件管理器常用操作（用户需求 4 —— 右键/长按菜单的执行层）。
 *
 *  - 剪切 / 复制 / 粘贴（[FileClipboard] 单例承载；粘贴 = [SafeFileOps]
 *    copyTree + 剪切源删除；重名自动追加 “ - 副本 N” —— Windows 同款）；
 *  - 重命名（同目录内改名，保留扩展名语义由 UI 层引导）；
 *  - 删除（[SafeFileOps].deleteTree —— 不跟随符号链接的删除走廊）；
 *  - 解压（zip / tar / tar.gz / tar.xz / tar.bz2 / 7z —— Commons Compress；
 *    rar 显示“暂不支持”）；
 *  - 压缩为 ZIP（文件或整个目录 → 同名 .zip）；
 *  - 属性（类型/大小/项数/修改时间/路径/只读）；
 *  - 新建文件夹 / 新建文本文档。
 *
 * 全部方法在调用方线程执行（UI 侧 Dispatchers.IO）；返回 null = 成功，
 * 非 null = 用户可读错误消息。
 */
object FileOps {

    // ------------------------------------------------------------------
    // 剪贴板（cut / copy）
    // ------------------------------------------------------------------

    /** 全局文件剪贴板（Cut = 粘贴后删源；Copy = 纯复制）。 */
    object Clipboard {
        enum class Mode { CUT, COPY }

        @Volatile
        private var mode: Mode? = null

        @Volatile
        private var paths: List<File> = emptyList()

        fun set(mode: Mode, files: List<File>) {
            this.mode = mode
            this.paths = files.filter { it.exists() }
        }

        fun clear() {
            mode = null
            paths = emptyList()
        }

        /** 有内容可粘贴（逐项复核存在性 —— 源被删后自动视为空）。 */
        fun hasContent(): Boolean = mode != null && paths.any { it.exists() }

        fun pending(): Pair<Mode, List<File>>? {
            val m = mode ?: return null
            val live = paths.filter { it.exists() }
            if (live.isEmpty()) return null
            return m to live
        }
    }

    // ------------------------------------------------------------------
    // 复制 / 移动 / 重命名 / 删除
    // ------------------------------------------------------------------

    /** 目标重名兜底：name.ext → “name - 副本.ext” / “name - 副本 2.ext”… */
    private fun uniqueTarget(dst: File): File {
        if (!dst.exists()) return dst
        val parent = dst.parentFile ?: return dst
        val base = dst.nameWithoutExtension
        val ext = dst.extension.let { if (it.isEmpty()) "" else ".$it" }
        var candidate = File(parent, "$base - 副本$ext")
        var n = 2
        while (candidate.exists()) {
            candidate = File(parent, "$base - 副本 $n$ext")
            n++
        }
        return candidate
    }

    /** 复制单/多项到目录（重名自动改名）。@return null 成功 / 错误消息。 */
    fun copyIn(files: List<File>, destDir: File, move: Boolean): String? {
        if (!destDir.isDirectory) return "目标目录不存在：${destDir.absolutePath}"
        for (src in files) {
            if (src.isDirectory && src.absolutePath == destDir.absolutePath) {
                return "不能把「${src.name}」复制到它自己里面"
            }
            // 防把父目录复制进自己的子目录（无限递归）
            if (src.isDirectory && destDir.absolutePath.startsWith(src.absolutePath + File.separator)) {
                return "不能把「${src.name}」复制到它自己的子目录里"
            }
        }
        var firstError: String? = null
        for (src in files) {
            val dst = uniqueTarget(File(destDir, src.name))
            val ok = if (src.isDirectory) SafeFileOps.copyTree(src, dst) else runCatching {
                src.copyTo(dst, overwrite = false)
                true
            }.getOrElse { false }
            if (!ok) {
                firstError = firstError ?: "复制 ${src.name} 失败（空间不足/权限）"
                continue
            }
            if (move && !SafeFileOps.deleteTree(src)) {
                // 剪切：源删除失败不回滚目标（半完成如实报错）
                firstError = firstError ?: "移动 ${src.name}：源删除失败（被占用？）"
            }
        }
        return firstError
    }

    /** 重命名（同目录）。@return null 成功 / 错误消息。 */
    fun rename(file: File, newName: String): String? {
        val name = newName.trim()
        if (name.isEmpty()) return "名称不能为空"
        if (name.contains('/') || name.contains('\\')) return "名称不能包含 / 或 \\"
        if (name == file.name) return null
        val dst = File(file.parentFile ?: return "无法定位父目录", name)
        if (dst.exists()) return "已存在同名项：$name"
        return if (file.renameTo(dst)) null else "重命名失败（跨挂载点/被占用）"
    }

    /** 删除（目录整树，[SafeFileOps] 不跟随符号链接）。@return null / 错误。 */
    fun delete(file: File): String? =
        if (SafeFileOps.deleteTree(file)) null else "删除 ${file.name} 失败（被占用/权限）"

    /** 新建文件夹（重名自动 “ (2)”). @return 创建的目录 / null。 */
    fun mkdir(dir: File): File? {
        var candidate = dir
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir.parentFile ?: return null, "${dir.name} ($n)")
            n++
        }
        return if (candidate.mkdirs()) candidate else null
    }

    /** 新建文本文档（重名自动加序号）。@return 创建的文件 / null。 */
    fun newTextFile(parent: File): File? {
        var candidate = File(parent, "新建文本文档.txt")
        var n = 2
        while (candidate.exists()) {
            candidate = File(parent, "新建文本文档 ($n).txt")
            n++
        }
        return runCatching {
            if (candidate.createNewFile()) candidate else null
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 解压 / 压缩
    // ------------------------------------------------------------------

    /** 可解压的压缩扩展名（rar 不支持 —— 菜单项灰化/提示用）。 */
    fun isArchive(ext: String): Boolean =
        ext.lowercase() in setOf("zip", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "7z")

    fun isUnsupportedArchive(ext: String): Boolean =
        ext.lowercase() in setOf("rar", "cab", "iso")

    /**
     * 解压到目标目录（zip / tar / tar.gz / tar.xz / tar.bz2 / 7z）。
     * 防路径穿越（entry 名 …/ 或绝对路径一律拒绝）。
     * @return null 成功 / 错误消息。
     */
    fun extract(archive: File, destDir: File): String? {
        if (!archive.isFile) return "压缩包不存在"
        return try {
            destDir.mkdirs()
            val ext = archive.extension.lowercase()
            val full = archive.name.lowercase()
            val err: String? = when {
                ext == "zip" -> extractZip(archive, destDir)
                ext == "7z" -> extract7z(archive, destDir)
                ext == "tar" -> extractTar(archive, destDir, null)
                (ext == "gz" || ext == "tgz") && full.endsWith(".tar.gz") ||
                    full.endsWith(".tgz") -> extractTar(archive, destDir, "gz")
                (ext == "xz" || ext == "txz") && full.endsWith(".tar.xz") ||
                    full.endsWith(".txz") -> extractTar(archive, destDir, "xz")
                (ext == "bz2" || ext == "tbz2") && full.endsWith(".tar.bz2") ||
                    full.endsWith(".tbz2") -> extractTar(archive, destDir, "bz2")
                else -> "暂不支持该压缩格式（支持 zip / tar / tar.gz / tar.xz / tar.bz2 / 7z）"
            }
            err
        } catch (e: Exception) {
            "解压失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** entry 路径安全检查（防 Zip-Slip 路径穿越）。 */
    private fun safeChild(root: File, name: String): File? {
        if (name.isNullOrBlank() || name.contains("..")) return null
        val f = File(root, name)
        if (!f.absolutePath.startsWith(root.absolutePath + File.separator) &&
            f.absolutePath != root.absolutePath
        ) return null
        return f
    }

    private fun copyStream(input: java.io.InputStream, out: File): Boolean {
        out.parentFile?.mkdirs()
        return out.outputStream().buffered().use { o ->
            input.copyTo(o)
            true
        }
    }

    private fun extractZip(archive: File, destDir: File): String? {
        var entries = 0
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val out = safeChild(destDir, e.name) ?: return "压缩包内含非法路径：${e.name}"
                    if (!copyStream(zin, out)) return "写入失败：${out.name}"
                    entries++
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        return if (entries > 0) null else "压缩包为空或已损坏"
    }

    private fun extractTar(archive: File, destDir: File, compression: String?): String? {
        val raw = BufferedInputStream(FileInputStream(archive))
        val input = when (compression) {
            "gz" -> TarArchiveInputStream(GzipCompressorInputStream(raw))
            "xz" -> TarArchiveInputStream(XZCompressorInputStream(raw))
            "bz2" -> TarArchiveInputStream(BZip2CompressorInputStream(raw))
            else -> TarArchiveInputStream(raw)
        }
        var entries = 0
        input.use { tin ->
            var e: TarArchiveEntry? = tin.nextTarEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val out = safeChild(destDir, e.name) ?: return "压缩包内含非法路径：${e.name}"
                    out.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(out)).use { o ->
                        tin.copyTo(o)
                    }
                    entries++
                } else {
                    safeChild(destDir, e.name)?.mkdirs()
                }
                e = tin.nextTarEntry
            }
        }
        return if (entries > 0) null else "压缩包为空或已损坏"
    }

    private fun extract7z(archive: File, destDir: File): String? {
        SevenZFile(archive).use { z ->
            var e = z.nextEntry
            if (e == null) return "压缩包为空或已损坏"
            while (e != null) {
                if (!e.isDirectory) {
                    val out = safeChild(destDir, e.name) ?: return "压缩包内含非法路径：${e.name}"
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).buffered().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (true) {
                            n = z.read(buf, 0, buf.size)
                            if (n < 0) break
                            o.write(buf, 0, n)
                        }
                    }
                }
                e = z.nextEntry
            }
        }
        return null
    }

    /**
     * 压缩为 ZIP（文件或目录整树 → 同目录下 <原名>.zip）。
     * @return null 成功 / 错误消息。
     */
    fun zipTo(src: File): String? {
        if (!src.exists()) return "源不存在"
        val out = uniqueTarget(File(src.parentFile ?: return "无法定位父目录", "${src.name}.zip"))
        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
                zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
                fun add(file: File, entryName: String) {
                    if (file.isDirectory) {
                        val kids = file.listFiles() ?: return
                        kids.sortedBy { it.name }.forEach { add(it, "$entryName/${it.name}") }
                    } else {
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                if (src.isDirectory) {
                    // 目录：包含顶层目录名（Windows 资源管理器同款语义）
                    add(src, src.name)
                } else {
                    add(src, src.name)
                }
            }
            null
        } catch (e: Exception) {
            "压缩失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------------
    // 属性
    // ------------------------------------------------------------------

    /** 属性面板数据。 */
    data class Props(
        val name: String,
        val type: String,
        val location: String,
        val size: Long,
        val sizeText: String,
        val itemCount: Int,
        val modified: String,
        val writable: Boolean
    )

    /** 计算属性（目录大小/项数走 IO 遍历，调用方 Dispatchers.IO）。 */
    fun props(file: File): Props {
        var size = 0L
        var count = 0
        if (file.isDirectory) {
            runCatching {
                file.walkTopDown().forEach { f ->
                    if (f.isFile) { size += f.length(); count++ }
                }
            }
        } else {
            size = file.length()
        }
        val ext = file.extension.lowercase()
        val type = if (file.isDirectory) "文件夹" else when {
            ext.isNotEmpty() -> {
                val mime = runCatching {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                }.getOrNull()
                if (mime != null) "$mime（.${ext}）" else ".${ext} 文件"
            }
            else -> "文件"
        }
        return Props(
            name = file.name,
            type = type,
            location = file.parentFile?.absolutePath ?: "—",
            size = size,
            sizeText = formatSizeBytes(size) + if (file.isDirectory) "（$count 个文件）" else "",
            itemCount = count,
            modified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(file.lastModified())),
            writable = file.canWrite()
        )
    }

    private fun formatSizeBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        b >= 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "$b 字节"
    }
}
