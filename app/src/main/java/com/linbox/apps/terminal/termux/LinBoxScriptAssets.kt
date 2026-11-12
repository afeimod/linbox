package com.linbox.apps.terminal.termux

import android.content.Context
import android.system.Os
import java.io.File

/**
 * LinBox 容器脚本资产部署（v2.26 · assets → rootfs 覆盖安装）。
 *
 * ------------------------------------------------------------------
 * 背景（v2.26 隔离版改造）：
 *   linbox-container / linbox-wine 两个容器管理脚本原先由
 *   bionic-rootfs 构建系统（projects/linbox-container）打进 rootfs
 *   发行包 —— 换 rootfs 才能更新脚本，且 rootfs 构建与 App 逻辑耦合。
 *
 *   现改为：脚本直接放 APK 内 assets/linbox/scripts/，App 每次启动
 *   与每次容器会话启动时【覆盖安装】到 rootfs/usr/bin/ —— rootfs
 *   解压完即被覆盖为 APK 内置的最新版本，旧 rootfs 自带的旧脚本
 *   同样被替换，用户无需重导 rootfs。
 *
 * 资产契约（APK 内 assets/linbox/scripts/ 目录，纯文本脚本）：
 *   linbox-container   容器管理 CLI（start/shell/create/list/set/install-wine/
 *                      wine-catalog/wine-list/wine-default/wine-remove/
 *                      dxvk/vkd3d/doctor）
 *   linbox-wine        容器 Wine 启动器 v2.1（环境隔离版：
 *                      PREFIX/HOME/TMPDIR/LD_LIBRARY_PATH/LANG/LC_ALL/
 *                      音频全部指向 rootfs 自身；DISPLAY 强制 :1；
 *                      X 服务未运行自动拉起；dmode=off/d/v/f；
 *                      后端按 wine 二进制 ELF 架构自动判定
 *                      arm64ec/box64/native/hangover）
 *   wine/winecfg/wineboot/regedit
 *                      wine 包装器（exec linbox-wine）—— 终端直接敲
 *                      wine <程序> 即得隔离环境 + 中文 + 显示，
 *                      不再需要 linbox-x11 :1 && env DISPLAY=:1
 *
 * 覆盖安装目标（与 ContainerManager.ROOTFS_ROOT 一致）：
 *   /data/data/com.linbox/files/rootfs/usr/bin/<脚本名>（chmod 0755）
 *
 * 附带兜底（rootfs 未含 linbox-container 项目时补齐）：
 *   usr/etc/linbox/container.conf        全局配置（default_container/
 *                                        default_wine 指针，缺失才创建）
 *   usr/etc/profile.d/linbox-container.sh rootfs bin 并入 PATH
 *   usr/etc/profile.d/linbox-display.sh   DISPLAY=:1 / XDG_RUNTIME_DIR
 *   usr/etc/fonts/local.conf              引入 /system/fonts（CJK 字体，
 *                                        wine 中文渲染必需，缺失才创建）
 *   App 侧 $PREFIX/etc/profile.d/linbox-container.sh：每个终端会话
 *                                        注入 rootfs bin + DISPLAY=:1
 *   home/.linbox/containers/logs         容器与日志目录
 *
 * 幂等策略：脚本每次启动无条件覆盖（APK 内版本即最新版本，体量仅
 * 数十 KB）；配置文件与目录仅在缺失时创建，绝不覆盖用户数据。
 */
object LinBoxScriptAssets {

    private const val TAG = "LinBoxScriptAssets"

    /** APK 内脚本资产目录。 */
    const val SCRIPT_ASSET_DIR = "linbox/scripts"

    /** bionic rootfs 根（与 ContainerManager.ROOTFS_ROOT 逐字一致）。 */
    const val ROOTFS_ROOT = "/data/data/com.linbox/files/rootfs"

    /** 需要部署到 rootfs/usr/bin 的主脚本清单（每次启动无条件覆盖）。 */
    private val SCRIPTS = listOf("linbox-container", "linbox-wine")

    /**
     * wine 包装器清单（exec linbox-wine）：仅当目标不是 ELF 二进制时覆盖 ——
     * 防 rootfs 自带真实 wine 本体时误杀；缺失或旧脚本则正常覆盖。
     */
    private val WRAPPERS = listOf("wine", "winecfg", "wineboot", "regedit")

    /**
     * 部署容器脚本到 rootfs（覆盖安装）。
     *
     * 调用时机：
     *  1. App 启动（LinBoxTzstAssets.installAllIfNeeded 尾部，rootfs
     *     tzst 资产解压完成后 —— 满足"解压完 rootfs 后覆盖过去"）；
     *  2. 每次容器会话启动前（WineSessionLauncher.spawn，保证脚本
     *     与 APK 版本始终一致）。
     *
     * rootfs 未导入时静默跳过（返回 0），bootstrap 安装后自动生效。
     *
     * @return 实际写入的脚本数（0 = rootfs 未就绪或无资产）
     */
    fun deployScriptsIfNeeded(context: Context): Int {
        val binDir = File("$ROOTFS_ROOT/usr/bin")
        // rootfs 未导入（usr/bin 不存在）→ 跳过；rootfs 解压完成后本处即生效
        if (!binDir.isDirectory) return 0

        var written = 0
        val names = try {
            context.assets.list(SCRIPT_ASSET_DIR) ?: emptyArray()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "扫描 $SCRIPT_ASSET_DIR/ 失败: ${e.message}")
            return 0
        }

        for (name in names) {
            // 只部署白名单脚本，避免 assets 里出现意外文件被误装
            if (name in SCRIPTS) {
                if (copyScript(context, "$SCRIPT_ASSET_DIR/$name", File(binDir, name))) written++
            } else if (name in WRAPPERS) {
                val target = File(binDir, name)
                // 目标是真实 ELF 二进制（如 rootfs 自带 wine 本体）则不覆盖
                if (target.isFile && isElf(target)) {
                    android.util.Log.i(TAG, "跳过 $name：目标为 ELF 二进制（不覆盖真实 wine 本体）")
                    continue
                }
                if (copyScript(context, "$SCRIPT_ASSET_DIR/$name", target)) written++
            }
        }

        ensureSupportFiles(context)
        if (written > 0) {
            android.util.Log.i(TAG, "容器脚本已覆盖安装: $written 个 → $binDir")
        }
        return written
    }

    /** 从 assets 复制单个脚本并 chmod 0755。 */
    private fun copyScript(context: Context, assetPath: String, target: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                val tmp = File(target.absolutePath + ".tmp")
                tmp.outputStream().use { output -> input.copyTo(output) }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    // rename 失败（跨文件系统等）退回直接写入
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            Os.chmod(target.absolutePath, 493)   // 0755
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "脚本部署失败: $assetPath → ${target.path}: ${e.message}")
            false
        }
    }

    /** 判断文件是否为 ELF 二进制（magic: 7F 45 4C 46）。 */
    private fun isElf(f: File): Boolean = try {
        f.inputStream().use { s ->
            val m = ByteArray(4)
            var n = 0
            while (n < 4) {
                val r = s.read(m, n, 4 - n)
                if (r < 0) break
                n += r
            }
            n == 4 && m[0] == 0x7F.toByte() && m[1] == 'E'.code.toByte() &&
                m[2] == 'L'.code.toByte() && m[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 兜底补齐脚本运行所需的配置与目录：
     *  - 全局容器配置（缺失才创建，保留 default_container/default_wine 指针）
     *  - rootfs profile.d：linbox-display.sh（DISPLAY=:1 / XDG_RUNTIME_DIR，
     *    容器 shell 环境；覆盖写入 —— 内容随 APK 版本更新）
     *  - rootfs etc/fonts/local.conf：引入 /system/fonts（Noto CJK ——
     *    wine 中文渲染必需；rootfs 无字体包，缺失才创建，不覆盖用户自定义）
     *  - App 侧 $PREFIX/etc/profile.d/linbox-container.sh：每个终端会话
     *    自动获得 rootfs bin（linbox-container/wine/winecfg 直接可用）与
     *    DISPLAY=:1（覆盖写入，内容随 APK 版本更新）
     *  - 容器存储与日志目录
     */
    private fun ensureSupportFiles(context: Context) {
        try {
            // 全局容器配置（容器 CLI 与 App 端 ContainerManager 共用）
            val conf = File("$ROOTFS_ROOT/usr/etc/linbox/container.conf")
            if (!conf.isFile) {
                conf.parentFile?.mkdirs()
                conf.writeText(
                    "# LinBox Wine 容器全局配置（linbox-container / App 端共用）\n" +
                        "# default_container=<容器名>\n" +
                        "# default_wine=<wine 槽位名（/usr/opt/<名>）>\n"
                )
                Os.chmod(conf.absolutePath, 420)   // 0644
            }

            // profile.d：rootfs bin 并入 PATH（终端直接敲 linbox-container）
            val prof = File("$ROOTFS_ROOT/usr/etc/profile.d/linbox-container.sh")
            if (!prof.isFile) {
                prof.parentFile?.mkdirs()
                prof.writeText(
                    "# LinBox Wine 容器管理（由 APK assets 自动部署）\n" +
                        "case \":${'$'}PATH:\" in\n" +
                        "  *\":$ROOTFS_ROOT/usr/bin:\"*) ;;\n" +
                        "  *) export PATH=\"$ROOTFS_ROOT/usr/bin:${'$'}{PATH}\" ;;\n" +
                        "esac\n"
                )
                Os.chmod(prof.absolutePath, 420)
            }

            // profile.d：容器 shell 的显示/运行时环境（v2.27）
            // 兜底值（:-），不覆盖已有设置；DISPLAY 立即 :1 消除黑屏
            val disp = File("$ROOTFS_ROOT/usr/etc/profile.d/linbox-display.sh")
            disp.parentFile?.mkdirs()
            disp.writeText(
                "# LinBox 容器显示/运行时环境（由 APK assets 自动部署，勿手改）\n" +
                    "export DISPLAY=\"${'$'}{LINBOX_DISPLAY:-:1}\"\n" +
                    "export XDG_RUNTIME_DIR=\"${'$'}{XDG_RUNTIME_DIR:-/data/data/com.linbox/files/usr/tmp}\"\n"
            )
            Os.chmod(disp.absolutePath, 420)

            // fontconfig：引入 Android 系统字体（Noto CJK —— wine 中文
            // 渲染必需；rootfs 无字体包，Android 自带 CJK 字体零成本复用）
            val fc = File("$ROOTFS_ROOT/usr/etc/fonts/local.conf")
            if (!fc.isFile) {
                fc.parentFile?.mkdirs()
                fc.writeText(
                    "<?xml version=\"1.0\"?>\n" +
                        "<!DOCTYPE fontconfig SYSTEM \"fonts.dtd\">\n" +
                        "<!-- LinBox: 引入 /system/fonts（Noto CJK 等，中文渲染必需；由 APK 自动部署，已存在则不覆盖） -->\n" +
                        "<fontconfig>\n" +
                        "  <dir>/system/fonts</dir>\n" +
                        "</fontconfig>\n"
                )
                Os.chmod(fc.absolutePath, 420)
            }

            // App 侧 profile.d：每个 LinBox 终端会话自动获得
            //   * rootfs bin 并入 PATH（linbox-container / linbox-wine /
            //     wine / winecfg / wineboot / regedit 直接可用）
            //   * DISPLAY=:1（终端即面向内置 X 服务，配合脚本自启消除黑屏）
            val appPrefix = TermuxEnvironment.prefixPath(context)
            val appProf = File("$appPrefix/etc/profile.d/linbox-container.sh")
            appProf.parentFile?.mkdirs()
            appProf.writeText(
                "# LinBox 容器 CLI + X11 显示（由 APK assets 自动部署，勿手改）\n" +
                    "case \":${'$'}PATH:\" in\n" +
                    "  *\":$ROOTFS_ROOT/usr/bin:\"*) ;;\n" +
                    "  *) export PATH=\"$ROOTFS_ROOT/usr/bin:${'$'}{PATH}\" ;;\n" +
                    "esac\n" +
                    "export DISPLAY=\"${'$'}{LINBOX_DISPLAY:-:1}\"\n" +
                    "export XDG_RUNTIME_DIR=\"${'$'}{XDG_RUNTIME_DIR:-$appPrefix/tmp}\"\n"
            )
            Os.chmod(appProf.absolutePath, 420)

            // 容器存储与日志目录（rootfs home 下）
            File("$ROOTFS_ROOT/home/.linbox/containers/logs").mkdirs()
            File("$ROOTFS_ROOT/usr/tmp").mkdirs()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "容器支撑文件补齐失败: ${e.message}")
        }
    }
}
