package com.linbox.util

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * v3.2.26（数据丢失事故根治）：符号链接安全的文件树操作。
 *
 * 事故复盘（用户实测）：删除容器把 /storage/emulated/0/Download 与
 * /storage/emulated/0/exagear 整目录内容删光。根因：Kotlin 1.9.25 的
 * [File.deleteRecursively]（FileTreeWalk）会**穿透符号链接递归删除目标
 * 目录内容**（已在本机用 kotlin-stdlib 1.9.25 实证复现：容器目录内
 * dosdevices/d: → 外部目录，deleteRecursively 后外部目录里的文件全部
 * 消失）。v3.2.24 起容器前缀的 dosdevices 里有 d:→Download、e:→exagear、
 * z:→/ 三个指向 rootfs 之外的符号链接 —— 删容器即穿越链接删用户数据。
 *
 * 本工具用 [Files.walkFileTree]（不带 FOLLOW_LINKS）：符号链接本身按
 * "文件"访问、只删链接不删目标；目录后序删除。全部替换工程内对可能含
 * 符号链接的树（容器前缀 / wine 槽位 / rootfs 暂存区）的
 * deleteRecursively / copyRecursively 调用。
 */
object SafeFileOps {

    /**
     * v3.3.5（用户红线：「一定要保证删除容器不会删除 /storage/emulated/0
     * 下任何文件」）：删除走廊硬性限定在应用私有数据根内 —— 字面路径
     * （normalize 只归一 ./.. 前缀，**不解析符号链接**）不在
     * /data/data/com.linbox 之下的目标一律拒绝。walkFileTree 本就不
     * 跟随符号链接（链接只删本身），叠加本守卫后，即使未来某处再引入
     * “沿链接递归删除”的回归，外部存储也绝无被波及的可能 —— 双保险。
     * （与 ContainerManager.ROOTFS_ROOT 同源 —— 应用私有数据根。）
     */
    private const val DELETION_SANDBOX = "/data/data/com.linbox"

    /**
     * 符号链接安全的递归删除（v3.3.5 起叠加删除走廊硬守卫）。
     *
     * @return true = 目标已不存在（删除完成或原本就不存在）；
     *         false = 尽力删除后仍有残留（权限不足等，单点失败不中断
     *         整体，与 deleteRecursively 的"尽力"语义一致但绝不穿越链接）。
     */
    fun deleteTree(target: File): Boolean {
        // v3.3.5：删除走廊守卫 —— 字面路径不在应用私有数据根内的删除
        // 一律拒绝（/storage、/sdcard、/data/media 等外部路径零波及）。
        val literal = runCatching { target.absoluteFile.normalize().path }.getOrDefault(target.path)
        if (!literal.startsWith("$DELETION_SANDBOX/")) {
            return false
        }
        val path = target.toPath()
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)
        ) return true
        try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // 符号链接走这里（不带 FOLLOW_LINKS）：只删链接本身
                    runCatching { Files.deleteIfExists(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.deleteIfExists(dir) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            // best effort：与旧行为一致，失败由返回值反映
        }
        return !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    /**
     * 符号链接安全的递归复制（容器克隆用）。
     *
     * [File.copyRecursively] 同样会穿越符号链接把目标内容深拷进来
     * （克隆容器时会把 Download/exagear 整个拷进新容器）—— 本实现
     * 遇到符号链接时在目标位置**重建同指向的链接**（dosdevices 的
     * d:/e:/z: 等盘符链接原样保留），普通文件/目录正常复制。
     *
     * @return true 成功；false 任一文件复制失败（与 copyRecursively 语义
     *         对齐：整体尽力，失败以返回值上报）。
     */
    fun copyTree(src: File, dst: File, excludeTopLevel: Set<String> = emptySet()): Boolean {
        var ok = true
        val srcPath = src.toPath()
        val dstPath = dst.toPath()
        try {
            Files.walkFileTree(srcPath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // v3.3.5：顶层排除（如容器克隆排除 games/ 直读执行区
                    // —— 派生数据不随克隆复制，首次启动自动重建）。
                    val rel = srcPath.relativize(dir).toString()
                    if (rel.isNotEmpty() && excludeTopLevel.isNotEmpty() &&
                        rel.split('/').first() in excludeTopLevel) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    val target = dstPath.resolve(srcPath.relativize(dir).toString())
                    runCatching { Files.createDirectories(target) }
                        .onFailure { ok = false }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val target = dstPath.resolve(srcPath.relativize(file).toString())
                    if (attrs.isSymbolicLink) {
                        // 重建链接（绝对/相对指向原样保留），绝不穿越
                        runCatching {
                            Files.deleteIfExists(target)
                            Files.createSymbolicLink(target, Files.readSymbolicLink(file))
                        }.onFailure { ok = false }
                    } else {
                        runCatching {
                            Files.createDirectories(target.parent)
                            Files.copy(file, target)
                        }.onFailure { ok = false }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    ok = false
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
            ok = false
        }
        return ok
    }
}
