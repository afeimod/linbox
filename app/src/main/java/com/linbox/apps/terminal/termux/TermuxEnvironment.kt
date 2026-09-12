package com.linbox.apps.terminal.termux

import android.content.Context
import android.os.Build
import java.io.File

/**
 * LinBox Termux 移植版的环境定义。
 *
 * 对应上游 termux-shared 的 TermuxConstants / TermuxShellUtils
 * （GPLv3），本文件为面向 LinBox 的独立精简实现：
 * 所有路径均由 LinBox 自己的 applicationId 派生。
 *
 * 关键事实：
 * - 官方 bootstrap 里的二进制/脚本把 `/data/data/com.termux/files/usr`
 *   硬编码进了编译产物（ELF 的 .rodata 与脚本 shebang）。
 * - LinBox 的 applicationId 为 `com.linbox`，与 `com.termux` 恰好
 *   同为 10 字符，因此安装期的**同长度字节重写**（见
 *   [TermuxBootstrapInstaller]）在文本与 ELF 上都完全安全——
 *   等效于"按 com.linbox 的路径重新编译"了整个根文件系统。
 */
object TermuxEnvironment {

    /** 官方 bootstrap 中被硬编码的旧前缀（末尾不带分隔符）。 */
    const val LEGACY_TERMUX_APP_PACKAGE = "com.termux"
    const val LEGACY_TERMUX_FILES_PREFIX = "/data/data/com.termux/files"

    /** LinBox 自身的新前缀（末尾不带分隔符）。与旧前缀**等长**。 */
    const val LINBOX_APP_PACKAGE = "com.linbox"
    const val LINBOX_FILES_PREFIX = "/data/data/com.linbox/files"

    /** bootstrap 归档文件名（assets/termux/ 下）。 */
    const val BOOTSTRAP_ASSET_DIR = "termux"
    const val BOOTSTRAP_ASSET_PREFIX = "$BOOTSTRAP_ASSET_DIR/bootstrap-"

    /**
     * bootstrap 来源（供文档与"关于"信息展示）。
     * fix9.9：SYMLINKS.txt 补 libexpat.so.1 / libgpg-error.so.0 两条
     * soname 条目（bootstrap 原包把 libexpat.so / libgpg-error.so 以
     * dev 名入库且无 soname 链接，导致 dbus-daemon 等按 DT_NEEDED
     * 查找 libexpat.so.1 必然失败）。
     * fix9.10：归档内新增 xkb/x11-xkb.tar.gz 成员（XKB 键盘数据随
     * bootstrap 分发——独立 assets/termux/x11-xkb.tar.gz 在用户 CI
     * 仓库管线中会整文件丢失，fresh 安装必失败截图实锤；而 bootstrap
     * *.zip 从未丢过）——SHA-256 随之更新。
     */
    const val TERMUX_APP_VERSION = "0.118.0"
    const val BOOTSTRAP_BUILD_VERSION = "2022.01.07-r1"
    const val BOOTSTRAP_SOURCE_URL =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-$BOOTSTRAP_BUILD_VERSION/bootstrap-%s.zip"
    const val BOOTSTRAP_AARCH64_SHA256 =
        "34949b5f70b5b030c472d8a8af54121f0cb06388d212c6bf7f87b8eefe6f935f"

    // ------------------------------------------------------------------
    // 运行期路径（全部由 Context 派生，避免硬编码二次引入）
    // ------------------------------------------------------------------

    fun filesDir(context: Context): File = File(context.applicationInfo.dataDir, "files")

    fun prefixDir(context: Context): File = File(filesDir(context), "usr")

    fun prefixPath(context: Context): String = prefixDir(context).absolutePath

    fun stagingPrefixDir(context: Context): File = File(filesDir(context), "usr-staging")

    fun binDir(context: Context): File = File(prefixDir(context), "bin")

    fun etcDir(context: Context): File = File(prefixDir(context), "etc")

    fun varDir(context: Context): File = File(prefixDir(context), "var")

    fun tmpDir(context: Context): File = File(prefixDir(context), "tmp")

    fun homeDir(context: Context): File = File(filesDir(context), "home")

    fun homePath(context: Context): String = homeDir(context).absolutePath

    /** shell → App 命令桥的 FIFO 路径。 */
    fun commandFifoPath(context: Context): String =
        File(varDir(context), "linbox.cmd").absolutePath

    // ------------------------------------------------------------------
    // 架构映射
    // ------------------------------------------------------------------

    /**
     * 设备主 ABI → bootstrap 归档架构名。
     * 返回 null 表示本机构架未打包（当前离线包仅含 aarch64）。
     */
    fun deviceBootstrapArch(): String? {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when (primaryAbi) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> null
        }
    }

    /** 本 APK 是否内置了当前设备架构的 bootstrap。 */
    fun isArchSupportedOffline(): Boolean = deviceBootstrapArch() != null

    // ------------------------------------------------------------------
    // 子进程环境变量（对应上游 TermuxShellUtils.buildEnvironment）
    // ------------------------------------------------------------------

    /**
     * 构建 Termux 子进程环境。保持与官方一致的变量集合，
     * 但 PREFIX/HOME/PATH 指向 LinBox 自己的目录。
     */
    fun buildEnvironment(context: Context, isFailSafe: Boolean): List<String> {
        homeDir(context).mkdirs()
        val prefix = prefixPath(context)
        val bin = "$prefix/bin"
        val workingDirectory = homePath(context)

        val env = mutableListOf<String>()
        env.add("TERMUX_VERSION=$TERMUX_APP_VERSION")
        env.add("LINBOX_VERSION=${versionName(context)}")
        env.add("TERM=xterm-256color")
        env.add("COLORTERM=truecolor")
        env.add("HOME=${homePath(context)}")
        env.add("PREFIX=$prefix")

        // 对齐上游 Termux（TermuxShellUtils.buildEnvironment 必设项）：
        // 子进程必须能解析 $PREFIX/lib 下的动态库。缺失时仅自带 DT_RPATH
        // 的二进制可完成链接（老版 bootstrap 二进制可用）；新版官方包改用
        // DT_RUNPATH（bionic 对主执行文件不认），pkg upgrade 换入的新
        // dpkg 真身即报 CANNOT LINK EXECUTABLE ".../dpkg.real": library
        // "libmd.so" not found，整个安装事务失败（2026-09 pkgfix.log 事故）。
        // /system 二进制走系统 linker 命名空间，不受该变量影响，与上游一致。
        env.add("LD_LIBRARY_PATH=$prefix/lib")

        env.add("BOOTCLASSPATH=${System.getenv("BOOTCLASSPATH") ?: ""}")
        env.add("ANDROID_ROOT=${System.getenv("ANDROID_ROOT") ?: "/system"}")
        env.add("ANDROID_DATA=${System.getenv("ANDROID_DATA") ?: "/data"}")
        env.add("EXTERNAL_STORAGE=${System.getenv("EXTERNAL_STORAGE") ?: "/storage/emulated/legacy"}")

        // Android 10+ 必需（ART 运行时根路径）
        addIfPresent(env, "ANDROID_ART_ROOT")
        addIfPresent(env, "DEX2OATBOOTCLASSPATH")
        addIfPresent(env, "ANDROID_I18N_ROOT")
        addIfPresent(env, "ANDROID_RUNTIME_ROOT")
        addIfPresent(env, "ANDROID_TZDATA_ROOT")

        if (isFailSafe) {
            // failsafe 会话保留系统 PATH，保证系统工具可用
            env.add("PATH=${System.getenv("PATH") ?: "/system/bin:/system/xbin"}")
        } else {
            env.add("LANG=en_US.UTF-8")
            env.add("PATH=$bin")
            env.add("PWD=$workingDirectory")
            env.add("TMPDIR=$prefix/tmp")
        }
        return env
    }

    private fun addIfPresent(env: MutableList<String>, name: String) {
        System.getenv(name)?.let { env.add("$name=$it") }
    }

    /** 本应用版本名（运行期从 PackageManager 读取，供 winver 等展示）。 */
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        "unknown"
    }

    /**
     * 选择可执行文件的实际启动方式（对应上游 setupProcessArgs）：
     * - ELF → 直接执行
     * - shebang 为 /usr/... 或 /bin/... → 换成 $PREFIX/bin/ 下的同名解释器
     * - 无 shebang 的脚本 → 用 $PREFIX/bin/sh 执行
     */
    fun setupProcessArgs(context: Context, fileToExecute: String, arguments: Array<String>): Array<String> {
        var interpreter: String? = null
        try {
            val file = File(fileToExecute)
            if (file.isFile) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(256)
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 4) {
                        when {
                            buffer[0] == 0x7F.toByte() && buffer[1] == 'E'.code.toByte() &&
                                buffer[2] == 'L'.code.toByte() && buffer[3] == 'F'.code.toByte() -> {
                                // ELF，直接执行
                            }
                            buffer[0] == '#'.code.toByte() && buffer[1] == '!'.code.toByte() -> {
                                val builder = StringBuilder()
                                var i = 2
                                while (i < bytesRead) {
                                    // Byte.toChar() 已弃用（符号扩展语义）；
                                    // 显式走 toInt().toChar() 保持逐字节解析 shebang 的行为
                                    val c = buffer[i].toInt().toChar()
                                    if (c == ' ' || c == '\n') {
                                        if (builder.isNotEmpty()) {
                                            val executable = builder.toString()
                                            if (executable.startsWith("/usr") || executable.startsWith("/bin")) {
                                                val binary = executable.substring(executable.lastIndexOf('/') + 1)
                                                interpreter = "${binDir(context).absolutePath}/$binary"
                                            }
                                            break
                                        }
                                    } else {
                                        builder.append(c)
                                    }
                                    i++
                                }
                            }
                            else -> interpreter = "${binDir(context).absolutePath}/sh"
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 读取失败按原样执行
        }

        val result = mutableListOf<String>()
        interpreter?.let { result.add(it) }
        result.add(fileToExecute)
        result.addAll(arguments)
        return result.toTypedArray()
    }
}
