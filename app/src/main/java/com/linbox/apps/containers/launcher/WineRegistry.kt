package com.linbox.apps.containers.launcher

import android.content.Context
import com.linbox.apps.containers.ContainerAudio
import com.linbox.apps.containers.ContainerBackend
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Wine 注册表操作（经 wine reg 客户端执行 —— 与脚本 ensure_emulate_modeset /
 * clear_wine_blue_desktop 同一通路，注册表一致性由 wineserver 保证）。
 *
 * **EmulateModeset（v2.3 核心修复，必须保留）**：
 * LinBox 的 X 服务（termux-x11/libXlorie）不支持 RandR 动态模式切换
 * （分辨率由 .linbox-x11-res 握手文件调整），而 wine 10+ 的显示栈在
 * explorer 初始化显示设置时经 x11drv 调 RandR 设模式 →
 * NtUserChangeDisplaySettings 返回 -2 → 模式表未写入注册表 → 游戏
 * NtUserEnumDisplaySettings 连续报 "display mode not found" → D3D
 * 设备创建失败弹错（所有游戏全挂）。wine 上游为无 RandR 环境提供的
 * 官方开关 EmulateModeset 开启后，模式切换在 win32u 内虚拟化成功。
 * 幂等写入，以 WINEPREFIX 下的标记文件避免每次启动重复起 wine reg。
 */
object WineRegistry {

    /** EmulateModeset 已写入的标记文件（容器前缀内）。 */
    private fun marker(prefix: File) = File(prefix, ".linbox-emulate-modeset")

    /**
     * 确保注册表已写入 EmulateModeset=Y。
     *
     * v3.3.40【JSON 打入】：改经 [JsonRegistry] 直接文本合并进 user.reg ——
     * 旧链路（拉起 `wine reg add` 客户端，每次 1~3s 进程开销）全部移除；
     * wineserver 在跑时先收尾（内存缓存落盘，防回写覆盖直写）。
     *
     * @return true 表示已就位（含标记命中）；false 表示本次写入失败
     *         （下次启动自动重试，不阻断容器启动）。
     */
    fun ensureEmulateModeset(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) return true   // 前缀未建：留给 wineboot 后的下次启动
        if (marker(prefix).isFile) return true
        log("写入 EmulateModeset=Y（显示模式虚拟化——修复游戏枚举不到显示模式/设备创建失败）……")
        return try {
            val edits = org.json.JSONArray().put(
                org.json.JSONObject()
                    .put("hive", "HKCU")
                    .put("key", "Software\\Wine\\X11 Driver")
                    .put("values", org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("name", "EmulateModeset")
                            .put("type", "sz")
                            .put("data", "Y")
                    ))
            )
            val err = JsonRegistry.apply(prefix, resolved, container, env, edits, log)
            if (err == null) {
                runCatching { marker(prefix).createNewFile() }
                true
            } else {
                log("警告: EmulateModeset 写入失败（$err；下次启动自动重试）")
                false
            }
        } catch (e: Exception) {
            log("警告: EmulateModeset 写入异常：${e.message}")
            false
        }
    }

    /**
     * v3.3.9（用户问题 3）：独立窗口模式（-f）的虚拟桌面**注册表**控制
     * （startwine.sh / startfex.sh 的 apply_virtual_desktop_settings 移植）。
     *
     * -f 与 -d 的机制差异（对齐脚本语义）：
     *  - -d（固定分辨率）→ 命令行 `explorer /desktop=shell,WxH`（每次
     *    启动包一层，见 RootfsLauncher.buildCommand）；
     *  - -f（独立窗口）→ **注册表**：HKCU\Software\Wine\Explorer
     *    "Desktop"="Default" + HKCU\Software\Wine\Explorer\Desktops
     *    "Default"="WxH" —— wine 桌面常驻，命令行不包 explorer。
     *
     * enable=false → 删两键（脚本 apply_virtual_desktop_settings 关闭
     * 分支 + -f 清 -d 注册表防双窗口的同款动作；也是旧版"清蓝底"）。
     *
     * v3.3.40【JSON 打入】：改经 [JsonRegistry] 直接文本合并 —— 旧链路每次
     * 状态变化拉起 2 个 `wine reg` 客户端（2~6s）；新链路零进程。
     *
     * 幂等标记：<prefix>/drive_c/linbox/.linbox-vd-state（"on:WxH" /
     * "off"）—— 状态未变时跳过。标记缺失且目标为关闭时视为干净
     * （新前缀无桌面键），零动作。
     */
    fun setVirtualDesktopRegistry(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        enable: Boolean,
        resolution: String,
        log: (String) -> Unit = {}
    ) {
        runCatching {
            val prefix = File(container.prefixPath)
            if (!File(prefix, "system.reg").isFile) return   // 前缀未就绪（下次启动补）
            val mark = File(prefix, "drive_c/linbox/.linbox-vd-state")
            val want = if (enable) "on:$resolution" else "off"
            val prev = runCatching { mark.readText().trim() }.getOrDefault("")
            if (prev == want) return                          // 已就位
            if (!enable && prev.isEmpty()) return             // 从未开过 → 视为干净
            val edits = org.json.JSONArray()
            if (enable) {
                edits.put(
                    org.json.JSONObject()
                        .put("hive", "HKCU")
                        .put("key", "Software\\Wine\\Explorer")
                        .put("values", org.json.JSONArray().put(
                            org.json.JSONObject().put("name", "Desktop")
                                .put("type", "sz").put("data", "Default")
                        ))
                )
                edits.put(
                    org.json.JSONObject()
                        .put("hive", "HKCU")
                        .put("key", "Software\\Wine\\Explorer\\Desktops")
                        .put("values", org.json.JSONArray().put(
                            org.json.JSONObject().put("name", "Default")
                                .put("type", "sz").put("data", resolution)
                        ))
                )
            } else {
                edits.put(
                    org.json.JSONObject()
                        .put("hive", "HKCU")
                        .put("key", "Software\\Wine\\Explorer")
                        .put("values", org.json.JSONArray().put(
                            org.json.JSONObject().put("name", "Desktop")
                                .put("type", "delete")
                        ))
                )
                edits.put(
                    org.json.JSONObject()
                        .put("hive", "HKCU")
                        .put("key", "Software\\Wine\\Explorer\\Desktops")
                        .put("delete", true)
                )
            }
            val err = JsonRegistry.apply(prefix, resolved, container, env, edits, log)
            if (err != null) {
                log("⚠ 虚拟桌面注册表 JSON 直写失败：$err（下次启动重试）")
                return
            }
            runCatching {
                mark.parentFile?.mkdirs()
                mark.writeText(want)
            }
            log(
                if (enable) "独立窗口(-f)：虚拟桌面注册表已写入（Explorer Desktops Default=$resolution，JSON 直写）"
                else "虚拟桌面注册表已清除（关闭 wine 桌面/清蓝底，JSON 直写）"
            )
        }.onFailure { log("⚠ 虚拟桌面注册表写入异常：${it.message}（下次启动重试）") }
    }

    /**
     * 确保前缀的 dosdevices 盘符映射完整（v3.1 新增；v3.2.24 扩展；
     * v3.3.5 z: 直链改内部 rootfs）。
     *
     * wineboot 建前缀时会创建 c: → ../drive_c 与 z: → /，但个别精简
     * 构建 / 异常中断的前缀会缺 z: —— 之后：
     *  - 用户从 App 传入的 Unix 绝对路径（rootfs 内部路径）wine 自动
     *    按 Z: 盘换算，z: 缺失即 rootfs 文件系统在 wine 内不可达；
     *  - 游戏按绝对路径（Z:\usr\…）找资源时报 "找不到 media"。
     *
     * v3.2.24（用户问题 3，对齐 startwine.sh / startfex.sh 的盘符矩阵）：
     *  - d: → /storage/emulated/0/Download（存在时）：参考链路的
     *    D 盘 —— 用户下载的 exe/游戏/资源的主入口，wine 安装器与游戏
     *    打开/另存对话框默认可达；目录不存在（无外部存储权限机型）
     *    时跳过，不留死链。
     *  - e: → /storage/emulated/0/exagear（存在时）：参考链路的 E 盘，
     *    老用户从 exagear 目录迁移数据时的习惯路径。
     *
     * v3.3.5（用户红线指令）：**z: 直链内部 rootfs，绝不再指向宿主根**
     * —— 旧 z:→宿主根 经 /storage 即触达外部存储，任何一次沿链接的误删都是
     * 灾难性的（“删容器删掉 /storage/emulated/0 下文件”的爆炸半径源头）；
     * wine 需要的 HOME、/opt、/usr 等本来就在 rootfs 内，外部存储统一走
     * d:/e: 直读盘。已存在的旧链接一律重指；用户自建目录不碰。
     *
     * 幂等：仅在链接缺失/指向错误时补建或重指（c: 缺失时指向
     * 前缀内 drive_c，与 wine 默认布局一致；d:/e: 仅在目标目录存在且
     * 盘符缺失时建 —— 不覆盖用户已有映射）。前缀尚未初始化（无
     * dosdevices 目录）时跳过 —— 归 wineboot 管。
     */
    fun ensureDosdevices(container: ContainerData, log: (String) -> Unit = {}) {
        runCatching {
            val prefix = File(container.prefixPath)
            val dd = File(prefix, "dosdevices")
            if (!dd.isDirectory) return

            val z = File(dd, "z:")
            // v3.3.5：z: → 内部 rootfs（用户红线：z 盘绝不直链
            // /storage/emulated/0，否则删容器可能波及外部存储文件）。
            val zTarget = ContainerManager.ROOTFS_ROOT
            val zCur = runCatching { android.system.Os.readlink(z.absolutePath) }.getOrNull()
            when {
                zCur == zTarget -> Unit   // 已直链内部 rootfs
                zCur != null -> runCatching {   // 旧链接（含 wineboot 默认的 z:→/）：重指
                    z.delete()
                    android.system.Os.symlink(zTarget, z.absolutePath)
                    log("已重指 dosdevices z: → 内部 rootfs（旧: $zCur —— 外部存储只经 d:/e: 直读盘触达，杜绝删容器误伤 /storage）")
                }.onFailure { log("警告: z: 盘重指失败（${it.message}）") }
                !z.exists() -> runCatching {
                    android.system.Os.symlink(zTarget, z.absolutePath)
                    log("已补 dosdevices z: → 内部 rootfs（HOME、/opt 等 rootfs 路径经 Z: 可达；外部存储走 d:/e: 直读盘）")
                }.onFailure { log("警告: z: 盘补建失败（${it.message}）") }
                else -> log("提示: dosdevices z: 为用户自建目录/文件，保持不动")
            }

            val c = File(dd, "c:")
            if (!c.exists()) {
                val driveC = File(prefix, "drive_c")
                driveC.mkdirs()
                try {
                    android.system.Os.symlink("../drive_c", c.absolutePath)
                    log("已补 dosdevices c: → ../drive_c")
                } catch (e: Exception) {
                    log("警告: c: 盘补建失败（${e.message}）")
                }
            }

            // v3.2.24（用户问题 3）：d:/e: 盘符（对齐 startwine.sh 的
            // ln -sf /storage/emulated/0/Download …dosdevices/d: 等）。
            // v3.3.5：这两个盘是外部存储的**直读入口**（z: 改链内部
            // rootfs 后，wine 侧访问 /storage/emulated/0 下游戏文件的
            // 通道；noexec 直读执行模式的数据件同样经它们可达）。
            // 仅目标目录存在且盘符缺失时补建 —— 不覆盖用户已有映射
            //（用户可能把 d: 指到别处），目标不存在时跳过不留死链。
            // 删除容器只删链接本身（SafeFileOps 删除走廊 + 不跟随链接
            // 双保险，绝无可能波及 /storage/emulated/0 下文件）。
            listOf(
                "d:" to "/storage/emulated/0/Download",
                "e:" to "/storage/emulated/0/exagear"
            ).forEach { (drive, targetPath) ->
                val target = File(targetPath)
                val link = File(dd, drive)
                if (!link.exists() && target.isDirectory) {
                    try {
                        android.system.Os.symlink(targetPath, link.absolutePath)
                        log("已补 dosdevices $drive → $targetPath（盘符矩阵惯例）")
                    } catch (e: Exception) {
                        log("警告: $drive 盘补建失败（${e.message}）")
                    }
                }
            }

            // v3.2.28：x: 盘 → 前缀内 drive_x
            // （mkdir -p $WINEPREFIX/drive_x; ln -sf …dosdevices/x: ——
            // 安装包脚本/快捷方式按 x: 引用前缀内资源时可达）。
            runCatching {
                val driveX = File(prefix, "drive_x")
                if (!driveX.isDirectory) driveX.mkdirs()
                val link = File(dd, "x:")
                if (!link.exists()) {
                    android.system.Os.symlink(driveX.absolutePath, link.absolutePath)
                    log("已补 dosdevices x: → $driveX（前缀资源盘惯例）")
                }
            }.onFailure { log("警告: x: 盘补建失败（${it.message}）") }

            // v3.3.6（用户指令）：Z: 盘（内部 rootfs）补齐外部存储访问入口
            ensureRootfsStorageEntries(log)
        }
    }

    /**
     * v3.3.6（用户指令：「Z 盘如果缺少 /storage 或者 sdcard 等访问入口，
     * 参考上游同类项目的 rootfs 正确逻辑补齐」）。
     *
     * 勘察结论：上游参考实现的 z: 直链其 rootfs 根、外部存储只经 d:/e: 盘符
     * 触达，rootfs 内**没有** /storage、/sdcard 入口；LinBox 按用户指令
     * 补齐 —— rootfs 根下建两条符号链接：
     *  - storage → /storage            （Z:\storage\emulated\0\… 与 Android
     *                                    原生路径逐段一致，经它可达外部
     *                                    存储**任意**目录，不止 Download/exagear）
     *  - sdcard  → /storage/emulated/0 （经典 sdcard 别名：Z:\sdcard\Download\…）
     * d:/e: 盘只覆盖 Download/exagear 两个目录；游戏放在外部存储其他目录
     * 时，其 Unix 路径无法换算成盘符路径（wine 只认 dosdevices 可映射路径）
     * —— 这两条入口正是它们进 wine 的通道（RootfsLauncher 会把此类路径
     * 改写为 Z:\storage\… 形态再启动）。
     *
     * 删除安全性（用户红线）：链接本体在 rootfs 内部 —— SafeFileOps
     * deleteTree（Files.walkFileTree 不带 FOLLOW_LINKS）删 rootfs/容器时
     * 只删链接本身，**绝不穿透波及 /storage/emulated/0 下任何文件**；
     * z: 盘直链内部 rootfs 的语义不变（外部存储的主通道仍是 d:/e:）。
     *
     * 幂等：链接缺失时补建、指错时重指；已存在的非链接内容（用户自建
     * 目录/文件）一律不碰。
     */
    private fun ensureRootfsStorageEntries(log: (String) -> Unit) {
        runCatching {
            val root = File(ContainerManager.ROOTFS_ROOT)
            if (!root.isDirectory) return
            listOf(
                "storage" to "/storage",
                "sdcard" to "/storage/emulated/0"
            ).forEach { (name, target) ->
                val link = File(root, name)
                val isLink = java.nio.file.Files.isSymbolicLink(link.toPath())
                val cur = if (isLink) {
                    runCatching { android.system.Os.readlink(link.absolutePath) }.getOrNull()
                } else null
                when {
                    cur == target -> Unit   // 已就位
                    isLink -> runCatching {   // 旧符号链接：重指
                        java.nio.file.Files.deleteIfExists(link.toPath())
                        android.system.Os.symlink(target, link.absolutePath)
                        log("已重指 rootfs/$name → $target（Z:\\$name 外部存储访问入口）")
                    }.onFailure { log("警告: rootfs/$name 重指失败（${it.message}）") }
                    !link.exists() -> runCatching {
                        android.system.Os.symlink(target, link.absolutePath)
                        log("已补 rootfs/$name → $target（Z:\\$name 外部存储访问入口，v3.3.6）")
                    }.onFailure { log("警告: rootfs/$name 补建失败（${it.message}）") }
                    else -> log("提示: rootfs/$name 已存在且非链接（用户数据），保持不动")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // v3.2.11（用户问题 3）：wine 内建窗口现代化外观
    // ------------------------------------------------------------------

    /** 现代化外观已应用的标记文件（容器前缀内；失败不落标记，下次自动重试）。 */
    private fun modernUiMarker(prefix: File) = File(prefix, ".linbox-modern-ui")

    /**
     * wine 创建的所有窗口现代化（一次成型，幂等标记）。
     *
     * 作用域：wine 自绘窗口/对话框/菜单 —— wineboot 进度框、winecfg、
     * regedit、notepad、explorer 虚拟桌面、文件公共对话框、消息框，以及
     * 老式 Win32 程序的控件。（游戏/现代应用自绘皮肤不在此列。）
     *
     * 五件套（对齐现代 Windows 观感）：
     *  1) **ClearType 字体平滑** —— wine 默认 FontSmoothing=0，锯齿文本
     *     观感停留在 Win95；置 FontSmoothing=2 + Type=2（RGB 子像素）
     *     + Gamma=1000，文本立变现代；
     *  2) **拖动显示窗口内容** —— wine 默认 DragFullWindows=0，拖动只画
     *     一片虚线外框，非常 DOS；置 1；
     *  3) **视觉样式主题** —— 激活 wine 9.x+/10 自带的 light.msstyles
     *     扁平现代主题（ThemeActive=1）；旧前缀无 light 时回退
     *     aero.msstyles（Win7 风）；两者皆无则跳过主题键（尊重树形态）；
     *  4) **现代配色** —— 选区/热跟踪用 Windows 10/11 强调蓝
     *     （0 120 215 / 0 102 204），桌面兜底色柔和深蓝灰；
     *  5) **渐变壁纸** —— 程序化生成 24bit BMP（深空蓝→湖蓝渐变 + 右上
     *     辉光）写入 drive_c/linbox/，注册到 Control Panel\Desktop。
     *     explorer 虚拟桌面（dmode=v / 前缀构建）经 wine 自带壁纸管线
     *     绘制（BMP 为 wine 唯一保真格式）；渲染不出时桌面显示第 4 项
     *     兜底色，无副作用。
     *
     * v3.3.40【JSON 打入】：整包键值改为 JSON 描述，经 [JsonRegistry]
     * 直接合并进 user.reg —— 零 wine 进程拉起（旧链路：生成 .reg +
     * `wine regedit /s` 客户端，1~3s）。
     * @return true 表示已就位（含标记命中）。
     */
    fun ensureModernLook(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) return true   // 前缀未建：留给 wineboot 后的下次启动
        if (modernUiMarker(prefix).isFile) return true
        log("应用 wine 窗口现代化外观（ClearType / 拖动内容 / light 主题 / 现代配色 / 壁纸，JSON 直写）……")
        return try {
            val linboxDir = File(prefix, "drive_c/linbox")
            linboxDir.mkdirs()

            // ---- 第 5 件：程序化渐变壁纸（24bit BMP） ----
            val wallpaperWin = if (generateModernWallpaper(File(linboxDir, "wallpaper.bmp"))) {
                "C:\\linbox\\wallpaper.bmp"
            } else ""

            // ---- 第 3 件：主题文件探测（light 优先，aero 兑底；大小写不敏感——
            //      Linux 文件系统区分大小写，注册表 DllName 必须与实际目录/文件名
            //      逐字一致才能被 uxtheme 打开）----
            val theme = findModernTheme(prefix)

            // ---- 汇总为 JSON 编辑（hive/key/values → JsonRegistry 直接合并） ----
            val edits = org.json.JSONArray()
            val desktop = org.json.JSONObject()
                .put("hive", "HKCU")
                .put("key", "Control Panel\\Desktop")
                .put(
                    "values", org.json.JSONArray()
                        .put(org.json.JSONObject().put("name", "FontSmoothing").put("type", "sz").put("data", "2"))
                        .put(org.json.JSONObject().put("name", "FontSmoothingType").put("type", "dword").put("data", 2))
                        .put(org.json.JSONObject().put("name", "FontSmoothingGamma").put("type", "dword").put("data", 1000))
                        .put(org.json.JSONObject().put("name", "FontSmoothingOrientation").put("type", "dword").put("data", 1))
                        .put(org.json.JSONObject().put("name", "DragFullWindows").put("type", "sz").put("data", "1"))
                )
            if (wallpaperWin.isNotEmpty()) {
                desktop.getJSONArray("values")
                    .put(org.json.JSONObject().put("name", "Wallpaper").put("type", "sz").put("data", wallpaperWin))
                    .put(org.json.JSONObject().put("name", "WallpaperStyle").put("type", "sz").put("data", "2"))
                    .put(org.json.JSONObject().put("name", "TileWallpaper").put("type", "sz").put("data", "0"))
            }
            edits.put(desktop)
            edits.put(
                org.json.JSONObject()
                    .put("hive", "HKCU")
                    .put("key", "Control Panel\\Colors")
                    .put(
                        "values", org.json.JSONArray()
                            .put(org.json.JSONObject().put("name", "Background").put("type", "sz").put("data", "47 54 68"))
                            .put(org.json.JSONObject().put("name", "Highlight").put("type", "sz").put("data", "0 120 215"))
                            .put(org.json.JSONObject().put("name", "HotTrackingColor").put("type", "sz").put("data", "0 102 204"))
                    )
            )
            if (theme != null) {
                edits.put(
                    org.json.JSONObject()
                        .put("hive", "HKCU")
                        .put("key", "Software\\Microsoft\\Windows\\CurrentVersion\\ThemeManager")
                        .put(
                            "values", org.json.JSONArray()
                                .put(org.json.JSONObject().put("name", "ThemeActive").put("type", "sz").put("data", "1"))
                                .put(org.json.JSONObject().put("name", "DllName").put("type", "sz").put("data", theme.second))
                                .put(org.json.JSONObject().put("name", "ColorName").put("type", "sz").put("data", theme.third))
                                .put(org.json.JSONObject().put("name", "SizeName").put("type", "sz").put("data", "NormalSize"))
                        )
                )
            }
            // JSON 文本留档（drive_c/linbox/modern-ui.json，调试/审计可读）
            runCatching {
                File(linboxDir, "modern-ui.json").writeText(edits.toString(2))
            }

            val err = JsonRegistry.apply(prefix, resolved, container, env, edits, log)
            if (err == null) {
                runCatching { modernUiMarker(prefix).writeText(buildString {
                    appendLine("applied=1")
                    appendLine("wallpaper=${wallpaperWin.isNotEmpty()}")
                    appendLine("theme=${theme?.first ?: "none"}")
                }) }
                log("wine 窗口现代化外观已生效（主题=${theme?.first ?: "未启用（前缀无内置主题文件）"}，壁纸=${if (wallpaperWin.isNotEmpty()) "已安装" else "生成失败跳过"}；JSON 直写零进程）")
                true
            } else {
                log("警告: 现代化外观 JSON 直写失败（$err；下次启动自动重试）")
                false
            }
        } catch (e: Exception) {
            log("警告: 现代化外观应用异常：${e.message}（下次启动自动重试）")
            false
        }
    }

    // ------------------------------------------------------------------
    // v3.2.29（用户指令「把 apk 自带的都去掉，完全使用 conf 配置文件」）：
    // APK 不携带、不注入、也不清洗任何 DllOverrides —— v3.2.14~v3.2.28
    // 的内置 DllOverrides 表、purgeLegacyDllOverrides 清洗机制、
    // migrateApkBundledDxOverrides 迁移机制（及三个标记/扫描辅助）
    // 全部移除。前缀注册表的唯一来源是用户 /opt 生态
    // （bootstrapOptPrefix 导入 /opt/prefix/{user,system,fix-services}.reg）；
    // DLL 加载序如需临时覆盖，用容器 env= 的 WINEDLLOVERRIDES。
    // ------------------------------------------------------------------
    /**
     * 探测前缀内可用的现代视觉主题（大小写不敏感；Linux 文件系统区分大小写，
     * 注册表 DllName 必须与实际目录/文件名逐字一致才能被 uxtheme 打开）。
     * light（wine 9.x+/10 扁平现代主题）优先，aero（Win7 风）兑底。
     * @return (label, Windows 路径, ColorName)；无可用主题返回 null。
     */
    private fun findModernTheme(prefix: File): Triple<String, String, String>? {
        return runCatching {
            val windows = File(prefix, "drive_c/windows")
            if (!windows.isDirectory) return@runCatching null
            val res = windows.listFiles().orEmpty()
                .firstOrNull { it.isDirectory && it.name.equals("resources", true) }
            val themes = (res ?: windows).listFiles().orEmpty()
                .firstOrNull { it.isDirectory && it.name.equals("themes", true) }
                ?: return@runCatching null
            for ((label, color) in listOf("light" to "Blue", "aero" to "NormalColor")) {
                val dir = themes.listFiles().orEmpty()
                    .firstOrNull { it.isDirectory && it.name.equals(label, true) } ?: continue
                val f = dir.listFiles().orEmpty()
                    .firstOrNull { it.isFile && it.name.equals("$label.msstyles", true) } ?: continue
                // 从 msstyles 文件回溯到 windows 目录，用实际名拼 Windows 路径
                // （保真大小写，避免注册表值指向不存在的路径）
                val parts = ArrayDeque<String>()
                var node: File? = f
                while (node != null && node != windows) {
                    parts.addFirst(node.name)
                    node = node.parentFile
                }
                if (parts.isEmpty()) continue
                return@runCatching Triple(label, "C:\\windows\\" + parts.joinToString("\\"), color)
            }
            null
        }.getOrNull()
    }

    /**
     * 生成现代化渐变壁纸（24bit BMP，1600×900，bottom-up，BI_RGB）。
     * 深空蓝（16,26,44）→ 湖蓝（38,74,110）垂直渐变，右上角叠加青蓝辉光，
     * 底部一条微光地平线 —— 无外部依赖、纯像素数学，写入前缀一次。
     * @return true 写入成功。
     */
    private fun generateModernWallpaper(out: File): Boolean {
        return try {
            val w = 1600
            val h = 900
            val rowBytes = w * 3
            val pad = (4 - (rowBytes % 4)) % 4
            val imageSize = (rowBytes + pad) * h
            val bos = java.io.ByteArrayOutputStream(54 + imageSize)
            fun le32(v: Int) { bos.write(v and 0xff); bos.write((v shr 8) and 0xff); bos.write((v shr 16) and 0xff); bos.write((v shr 24) and 0xff) }
            fun le16(v: Int) { bos.write(v and 0xff); bos.write((v shr 8) and 0xff) }
            // BITMAPFILEHEADER
            bos.write('B'.code); bos.write('M'.code)
            le32(54 + imageSize); le32(0); le32(54)
            // BITMAPINFOHEADER
            le32(40); le32(w); le32(h); le16(1); le16(24); le32(0)
            le32(imageSize); le32(2835); le32(2835); le32(0); le32(0)
            // 像素（BMP 自底向上）
            val glowX = w * 0.76f; val glowY = h * 0.24f; val glowR = h * 0.92f
            fun cap(v: Float) = v.coerceIn(0f, 255f).toInt()
            for (y in h - 1 downTo 0) {
                val ty = y.toFloat() / (h - 1)
                for (x in 0 until w) {
                    var r = 16f + (38f - 16f) * ty
                    var g = 26f + (74f - 26f) * ty
                    var b = 44f + (110f - 44f) * ty
                    // 右上辉光
                    val dx = x - glowX; val dy = y - glowY
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (dist < glowR) {
                        val f = (1f - dist / glowR); val glow = f * f * f
                        r += glow * 26f; g += glow * 64f; b += glow * 92f
                    }
                    // 底部地平线微光
                    val hz = ty * ty * 14f
                    r += hz * 0.3f; g += hz * 0.6f; b += hz
                    bos.write(cap(b)); bos.write(cap(g)); bos.write(cap(r))   // BGR
                }
                repeat(pad) { bos.write(0) }
            }
            out.writeBytes(bos.toByteArray())
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 首次运行自动初始化前缀（run_wine 的 wineboot -i 分支；v3.1.4 修正）：
     * 前缀无 system.reg 时同步执行 wineboot -i。
     *
     * v3.1.4 两处修正：
     * ① 经 wine loader 执行（`[box64] bin/wine wineboot -i`）——旧版直连
     *   bin/wineboot，而 wine ≤9.18/proton 树该文件是 shell 脚本（box64
     *   读不到 ELF 头、脚本原生 exec 绕开 box64 → ENOEXEC），wine 9.19+
     *   是符号链接，均不可靠；loader 的 check_command_line() 按 argv[0]
     *   basename 分发到 wineboot.exe，官方机制。
     * ② 校验退出码并失败重试一次 —— 旧版无条件报"完成"（设备实测
     *   wineboot 阵亡仍打"完成"，EmulateModeset 随之连锁失败）。
     *
     * v3.2.1（用户问题 5）新增三道防线：
     * ③ wineboot 前先跑【加载器冒烟测试】（`wine --version`，60 秒限时）——
     *   proton x86_64 "直接退出没任何日志"、arm64ec "无输出挂死" 这类
     *   静默阵亡，根因在 loader/早期初始化而非 wineboot 逻辑；冒烟测试
     *   把它们拦在前级并给出可操作结论（退出码 159=SIGSYS/fsync 探测、
     *   127/126=可执行体缺失、超时=loader 挂死）。
     * ④ 无输出看门狗：4 分钟零输出且前缀未落盘时提前抓取 /proc 挂死
     *   现场（不必等到 15 分钟超时；mono/gecko 提示框等待用户确认属
     *   正常场景，看门狗只取证不杀进程）。
     * ⑤ 退出码解读表直接写进前缀日志。
     *
     * v3.2.1（用户问题 3）：[vdResolution] —— 容器配置的分辨率；wineboot 的
     * explorer 虚拟桌面与握手分辨率一致（无则维持旧“当前屏幕”语义）。
     *
     * @return true 表示初始化完成（或已有前缀）。
     */
    fun initializePrefixIfNeeded(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        context: Context? = null,
        vdResolution: String = "",
        log: (String) -> Unit = {}
    ): Boolean {
        if (File(container.prefixPath, "system.reg").isFile) return true
        log("首次使用，初始化 wine 前缀: ${container.prefixPath}")
        log("提示: 构建过程中的 mono/gecko 等安装提示框将显示在『X11 桌面』窗口（explorer 虚拟桌面）内，请在该窗口确认")
        return try {
            // v3.2.1 ③：加载器冒烟测试 —— 静默阵亡拦在前级并给出结论
            if (!loaderSmokeTest(resolved, container, env, log)) {
                log("→ 加载器冒烟测试未通过，已放弃 wineboot（结论见上；请先解决该问题再重建容器）")
                return false
            }
            // v3.1.4：统一经 wine loader（box64 形态自动包装），不再直连
            // bin/wineboot 宿主文件（脚本/符号链接形态均不可靠）。
            // v3.2.0（用户诉求 4）：包 explorer /desktop 虚拟桌面 —— 无 WM
            // 裸窗口下 mono/gecko 提示框堆在 (0,0) 黑屏角落（用户实测“x11
            // 没有任何画面、提示框看不到、无法继续构建”）；虚拟桌面铺满 X
            // 屏幕，子窗口由 wine 自管，提示框可见可点。
            // v3.2.1：分辨率优先用容器配置值（与握手一致）。
            val vdRes = vdResolution.ifEmpty { RootfsLauncher.currentXScreenResolution() }
            val cmdPrefix = WineRuntime.wineCommand(resolved) +
                listOf("explorer", "/desktop=LinBox,$vdRes")
            // v3.1.6：每次尝试限时 —— wineboot 正常 1~3 分钟（arm64ec
            // 原生 / box64 仿真最慢也 <10 分钟）；超时强杀并如实报错。
            // 设备实测 proton10 arm64ec wineboot 无任何输出永久挂死
            // （会话无退出码），旧版 waitFor() 无限等 → 启动链卡死、
            // UI 无反馈。超时杀掉后错误行进前缀日志窗口，问题可见。
            // v3.2.0：10 → 15 分钟（用户需在 X11 窗口内确认安装提示框，
            // 人为交互时间不可控）。
            val TIMEOUT_MIN = 15L
            // v3.2.1 ④：无输出看门狗 —— 4 分钟零输出且前缀未落盘时提前取证
            val WATCHDOG_MIN = 4L
            var lastCode = -1
            for (attempt in 1..2) {
                var gotOutput = false
                val watchdog = Thread({
                    try { Thread.sleep(WATCHDOG_MIN * 60_000L) } catch (_: InterruptedException) { return@Thread }
                    if (!gotOutput && !File(container.prefixPath, "system.reg").isFile) {
                        log("wineboot 已 ${WATCHDOG_MIN} 分钟无任何输出且 system.reg 未落盘 ——")
                        log("（mono/gecko 安装提示框等待确认属正常；否则疑似挂死）提前抓取进程现场：")
                        dumpWineHangSnapshot(resolved, container, log)
                    }
                }, "linbox-prefix-watchdog").apply { isDaemon = true; start() }
                // v3.3.3（用户问题 3，startfex.sh 同款）：-i → -u —— 参考链
                // rm -rf 前缀后 wineboot -u 安装（前缀缺失时 -u 一样初始化），
                // 且附 WINEDLLOVERRIDES="mscoree=disabled"（参考同款）压掉
                // mono 安装器弹窗 —— 与下面 explorer 虚拟桌面配合，提示框
                // 不再阻塞构建。
                val executor = NativeShellExecutor.create()
                    .setCommand(*cmdPrefix.toTypedArray(), "wineboot", "-u")
                    .putEnv(env)
                    .putEnv("WINEDLLOVERRIDES", "mscoree=disabled")
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                    .onStdOut { gotOutput = true; log(it) }
                    .onStdErr { gotOutput = true; log(it) }
                    .exec()
                val finished = runCatching {
                    executor.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)
                }.getOrDefault(false)
                watchdog.interrupt()
                if (!finished) {
                    // v3.2.0：超时先抓挂死现场（进程 state/wchan）再杀 ——
                    // 无输出挂死从此有内核级证据（R=死循环嫌疑、
                    // S+wchan=futex=等锁死锁、D=IO 阻塞）。
                    dumpWineHangSnapshot(resolved, container, log)
                    runCatching { executor.stopAndWait(true, 10, TimeUnit.SECONDS) }
                    lastCode = -1
                    log("wineboot 第 ${attempt} 次超时（>${TIMEOUT_MIN} 分钟无退出），已强制结束" +
                        if (attempt == 1) "，清残留后重试一次…" else "")
                    log("→ 典型原因：残留/冻结的 wineserver 使客户端阻塞在握手（v3.3.1 已自动硬清理）/ 提示框等待确认 / 早期初始化崩溃；请把本窗口日志反馈给开发者")
                } else {
                    lastCode = runCatching { executor.waitFor() }.getOrDefault(-1)
                }
                // v3.2.0：成败以 system.reg 落盘为准 —— explorer 虚拟桌面
                // 不保证透传 wineboot 退出码，system.reg 是前缀创建完成
                // 的权威信号（官方 wineboot 成功必落盘）。
                if (File(container.prefixPath, "system.reg").isFile) break
                if (lastCode == 0) break
                if (lastCode != 0 && finished) {
                    log("wineboot -i 第 ${attempt} 次退出码 $lastCode（前缀 system.reg 未落盘）" +
                        if (attempt == 1) "，重试一次…" else "")
                    // v3.2.1 ⑤：退出码解读（信号位 = 128+信号号）
                    when (lastCode) {
                        159 -> log("→ 159 = SIGSYS：fsync 的 futex_waitv 能力探测被 Android seccomp 击杀 —— 旧包无 __ANDROID__ 守卫，请用 v3.1.4+ CI 重编")
                        139 -> log("→ 139 = SIGSEGV：loader/早期初始化段错误（贴日志给开发者）")
                        13 -> log("→ 13 = SIGPIPE：X 连接断开（X 服务未起/中途退出）")
                        126, 127 -> log("→ $lastCode = 可执行体无法启动（box64/wine 路径或权限问题）")
                    }
                }
                if (attempt == 1) {
                    // v3.3.1：重试前硬清 wineserver（旧版仅 -k/-w —— 冻结/挂死的
                    // 守护 -k 杀不掉，重试必再挂在同一处；决策行进前缀日志）
                    runCatching {
                        WineServerUtils.ensureCleanBeforeStart(resolved, container, env, log = { log(it) })
                    }
                }
            }
            if (lastCode == 0 || File(container.prefixPath, "system.reg").isFile) {
                log("wineboot -i 完成（前缀 system.reg 已落盘）")
                // v3.4.1【注册表大修】：wineboot 后立即注册表卫生 ——
                // ① 段级清除 wine.inf（proton 树 31 条 Steam 游戏 hack）
                //   打进 user.reg/system.reg 的全部 [Software\Wine\AppDefaults\*]
                //   段（白名单保护：App 侧用户手动 per-exe 配置不误删）；
                // ② 注入全局 [Software\Wine\DllOverrides]
                //   （amstream/atl100/d3d8~d3d12core=native,builtin，
                //   上游同款 —— 详见 ensureGlobalDllOverrides）。
                // postPrefixBootChain 每次启动链同样幂等重跑 —— v3.3.20
                // 只在首建前缀清一次 + v3.3.5 purge 一次性标记挡住存量段
                // 的缺陷由此根治（wineboot -u 重写入的 hack 不再留存）。
                runCatching { sanitizeRegistryHygiene(context, resolved, container, env, log) }
                    .onFailure { log("⚠ 注册表卫生异常：${it.message}（不影响使用；postPrefixBootChain 会再试）") }
                true
            } else {
                log("wineboot -i 失败（退出码 $lastCode，159=SIGSYS/旧包fsync、1=加载失败、-1=超时强杀）")
                log("→ 请用 v3.1.4+ 构建的 wine 包（fsync 守卫 + 自包含依赖库）后重建容器")
                false
            }
        } catch (e: Exception) {
            log("wineboot 初始化异常：${e.message}")
            false
        }
    }

    /**
     * v3.3.20（用户问题 3）→ v3.4.1【注册表大修】：清除前缀里的游戏
     * AppDefaults 注册表块（兼容壳，内部委托 [stripAppDefaultsSections]
     * —— 全面卫生见 [sanitizeRegistryHygiene]，本函数仅供旧调用点兼容）。
     *
     * 【根因】Valve proton 树的 loader/wine.inf.in 带 31 条 Steam 游戏兼容
     * hack（ActOfWar/BlackOps3/DIRT5/ffxvi/RDR2/Spider-Man 等的
     * atiadlxx=builtin、winxp/win7 Version、xinput/d3d8/vulkan-1 覆盖、
     * SpeechSynth 禁用），wineboot 建前缀/升级前缀时全部写入 user.reg 的
     * `[Software\\Wine\\AppDefaults\\<exe>]` 块 —— Android 上全是无关死配置
     * （官方 wine-11.0 / hangover-11.16 树零 AppDefaults 行，已实测核对）。
     *
     * @return 清除的块数（0 = 本就干净）
     */
    fun sanitizeAppDefaults(prefix: File, log: (String) -> Unit = {}): Int {
        return stripAppDefaultsSections(File(prefix, "user.reg"), emptySet(), log)
    }

    /**
     * v3.4.1【注册表大修】：段级剥除 [Software\\Wine\\AppDefaults\\<exe>…] 全部
     * 子段（DllOverrides / Version / Direct3D 等 —— 含段头、#time、值行与
     * hex 续行，直到下一个 '[' 段头）。
     *
     * 与 v3.3.20 版差异（用户实测反馈“构建前缀注册表又乱打”）：
     *  * user.reg 与 system.reg 都清（proton wine.inf 的 HKCU/HKLM 两侧游戏
     *    hack 均会落盘；生态包 /opt/prefix/user.reg 打入的 per-exe 加载序
     *    同在打击范围）；
     *  * [protectedExes] 白名单（管理器 per-exe 配置账本
     *    drive_c/linbox/appdefaults.json）—— 用户手动配置的 exe 子树原样
     *    保留，其余全部剥除；
     *  * 幂等零标记 —— 干净文件零动作，每次启动链重跑（v3.3.20 的
     *    “首建前缀清一次 + 换 wine 才再清”挡不住 wineboot -u 重导入的
     *    缺陷由此根治，配合源头 wine.inf 剥除双保险）。
     *
     * @return 剥除的段数（0 = 本就干净）。
     */
    private fun stripAppDefaultsSections(
        file: File,
        protectedExes: Set<String>,
        log: (String) -> Unit = {}
    ): Int {
        if (!file.isFile) return 0
        val lines = runCatching { file.readLines() }.getOrNull() ?: return 0
        val headerPrefix = "[Software\\\\Wine\\\\AppDefaults"
        val out = ArrayList<String>(lines.size)
        var skipping = false
        var removed = 0
        for (line in lines) {
            if (line.startsWith("[")) {
                if (line.startsWith(headerPrefix)) {
                    // 段头：[Software\\Wine\\AppDefaults\\<exe>\\<子键>] 或裸
                    // [Software\\Wine\\AppDefaults] —— exe 名取路径首段，
                    // 白名单内整棵子树保留（用户 per-exe 配置不误删）
                    val idx = line.indexOf("AppDefaults")
                    val tail = if (idx >= 0) line.substring(idx + "AppDefaults".length) else ""
                    val exe = if (tail.startsWith("\\\\"))
                        tail.substring(2).substringBefore("\\\\").substringBefore(']').trim().lowercase()
                    else ""
                    skipping = exe !in protectedExes
                    if (skipping) removed++ else out.add(line)
                } else {
                    skipping = false
                    out.add(line)
                }
            } else if (!skipping) {
                out.add(line)
            }
        }
        if (removed == 0) return 0
        // 先写临时文件再原子替换 —— wine 进程可能并发持有注册表文件
        val tmp = File(file.parentFile, ".${file.name}.linbox-cleanup")
        runCatching {
            tmp.writeText(out.joinToString("\n") + "\n")
            if (!tmp.renameTo(file)) {
                // rename 失败（跨句柄占用等）回退直接写
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            tmp.delete()
            log("⚠ ${file.name} AppDefaults 段剥除写回失败：${it.message}")
            return 0
        }
        return removed
    }

    /**
     * v3.4.1【注册表大修】：管理器 per-exe 配置账本（白名单）读取。
     *
     * 新格式（累计式，GuanliSupport.applyDllOverrides v3.4.1 起维护）：
     *   { "game.exe": { "d3d9": "native,builtin" } }
     * 旧格式（v3.3.x 单条审计）：
     *   [ {hive, key: "Software\Wine\AppDefaults\<exe>\DllOverrides", values} ]
     *
     * @return exe 名（小写）→ dll 加载序映射；读取失败返回空表（全部剥除）。
     */
    private fun readAppDefaultsLedger(prefix: File): Map<String, Map<String, String>> {
        val f = File(prefix, "drive_c/linbox/appdefaults.json")
        val text = runCatching { f.readText() }.getOrNull() ?: return emptyMap()
        return runCatching {
            val trimmed = text.trim()
            when {
                trimmed.startsWith("{") -> {
                    val obj = org.json.JSONObject(trimmed)
                    val out = LinkedHashMap<String, Map<String, String>>()
                    for (exe in obj.keys()) {
                        val m = obj.optJSONObject(exe) ?: continue
                        val dlls = LinkedHashMap<String, String>()
                        for (dll in m.keys()) dlls[dll] = m.optString(dll)
                        if (dlls.isNotEmpty()) out[exe.lowercase()] = dlls
                    }
                    out
                }
                trimmed.startsWith("[") -> {
                    val arr = org.json.JSONArray(trimmed)
                    val out = LinkedHashMap<String, Map<String, String>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val exe = Regex("AppDefaults\\\\(.+?)\\\\DllOverrides")
                            .find(o.optString("key"))?.groupValues?.get(1) ?: continue
                        val dlls = LinkedHashMap<String, String>()
                        val values = o.optJSONArray("values") ?: continue
                        for (j in 0 until values.length()) {
                            val v = values.optJSONObject(j) ?: continue
                            if (v.optString("type") == "sz") {
                                dlls[v.optString("name")] = v.optString("data")
                            }
                        }
                        if (dlls.isNotEmpty()) out[exe.lowercase()] = dlls
                    }
                    out
                }
                else -> emptyMap()
            }
        }.getOrElse { emptyMap() }
    }

    /**
     * v3.4.1【注册表大修·源头】：剥除 wine 包 wine.inf 的 AppDefaults 行
     * （Valve proton 树 loader/wine.inf.in 的 31 条 Steam 游戏兼容 hack：
     * Pentiment/rayne1/rayne2/RDR2/RelicCardinal/RiftApart 等 —— wineboot
     * -i/-u 每次都会重导入，只清前缀永远追不上；此前“构建期剔除”仅治
     * 新包，存量安装包由本函数在启动链就地根治）。
     *
     *  * 匹配口径：HKCU/HKM 起始 + 行含 "AppDefaults" —— 官方 wine-11.0 /
     *    hangover-11.16 树零 AppDefaults 行（已实测核对），wine.inf 里出现
     *    该词的 HKCU/HKM 行必为游戏 hack（INF 引号串的单/双反斜杠转义
     *    两种形态均覆盖）；
     *  * 幂等零标记 —— 干净 wine.inf 无匹配即零动作不写盘；
     *  * wine 目录只读等故障只记日志不阻断（前缀侧每次启动段剥除兜底）。
     *
     * @return 剥除的行数（0 = 本就干净 / 无 wine.inf）。
     */
    private fun sanitizeWineInf(resolved: WineRuntime.Resolved, log: (String) -> Unit): Int {
        val candidates = listOf(
            File(resolved.wineDir, "share/wine/wine.inf"),
            File(resolved.wineDir, "files/share/wine/wine.inf"),
            File(resolved.wineDir, "share/default/wine.inf")
        )
        var total = 0
        for (inf in candidates) {
            if (!inf.isFile) continue
            val text = runCatching { inf.readText() }.getOrNull() ?: continue
            val sb = StringBuilder(text.length)
            var removed = 0
            text.lineSequence().forEach { line ->
                val t = line.trimStart()
                val isHack = (t.startsWith("HKCU,") || t.startsWith("HKLM,")) &&
                    t.contains("AppDefaults")
                if (isHack) removed++ else sb.append(line).append('\n')
            }
            if (removed > 0) {
                runCatching {
                    val tmp = File(inf.parentFile, ".${inf.name}.linbox-strip")
                    tmp.writeText(sb.toString())
                    if (!tmp.renameTo(inf)) {
                        inf.writeText(sb.toString())
                        tmp.delete()
                    }
                    total += removed
                    log(
                        "已剥除 ${inf.name} 的 $removed 行 AppDefaults 游戏 hack" +
                            "（源头根治：wineboot -u 不再重导入 —— per-exe 块从此绝根）"
                    )
                }.onFailure { log("⚠ ${inf.name} 源头剥除失败：${it.message}（前缀侧每次启动仍会段剥除兜底）") }
            }
        }
        return total
    }

    /**
     * v3.4.1【注册表大修·注入】：全局 [Software\\Wine\\DllOverrides] 幂等落位
     * —— 上游发行包 DirectXWrapperConfigure + WinComponentsConfigure 同款语义
     * （参考源码与资产由用户提供）：
     *
     *  * assets builtin/wineprefix/wine_dll_overrides.json：
     *    - default 段原样（dinput/dinput8/xinput1_1~1_4/xinput9_1_0/
     *      xinputuap = "builtin,native"）；
     *    - direct3d.dx8_11（d3d8/d3d9/d3d10/d3d10_1/d3d10core/d3d11/dxgi/
     *      ddraw）与 direct3d.dx12（d3d12/d3d12core）固定 "native,builtin"
     *      （由前缀内物理 dll 决定实际生效的实现，缺原生 dll 时自动回退
     *      builtin —— 永远安全）；
     *  * assets builtin/wineprefix/wincomponents/wincomponents.json：全部组件
     *    dlnames（amstream/atl100/d3dcompiler_33~47/d3dx9_24~43/xactengine…/
     *    wmvcore 等约 150 项）固定 "native,builtin"（LinBox 无组件开关 UI ——
     *    上游发行包 默认全原生同款；restoreBuiltinDlls 只影响切回 builtin 的
     *    语义，此处不参与）；
     *  * 经 [JsonRegistry] 直写 user.reg（零 wine 进程拉起；只在值变化时
     *    落盘；wineserver 在跑则先收尾落盘）。
     *
     * 用户实测对照（正确形态，区别于前缀里的 per-exe 垃圾）：
     *   [Software\\Wine\\DllOverrides]
     *   "amstream"="native,builtin"
     *   "atl100"="native,builtin"
     *   "d3d10"="native,builtin" …
     *
     * @return true 成功（含“无需变更”）。
     */
    fun ensureGlobalDllOverrides(
        context: Context?,
        resolved: WineRuntime.Resolved?,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        if (context == null) {
            log("⚠ 全局 DllOverrides 注入跳过：无 Context（APK 资产不可读；postPrefixBootChain 会再试）")
            return false
        }
        if (!File(container.prefixPath, "user.reg").isFile) return true
        val overrides = LinkedHashMap<String, String>()
        try {
            context.assets.open("builtin/wineprefix/wine_dll_overrides.json")
                .bufferedReader().use { r ->
                    val obj = org.json.JSONObject(r.readText())
                    obj.optJSONObject("default")?.let { d ->
                        for (k in d.keys()) overrides[k] = d.optString(k)
                    }
                    val d3d = obj.optJSONObject("direct3d")
                    d3d?.optJSONArray("dx8_11")?.let { a ->
                        for (i in 0 until a.length()) overrides.put(a.optString(i), "native,builtin")
                    }
                    d3d?.optJSONArray("dx12")?.let { a ->
                        for (i in 0 until a.length()) overrides.put(a.optString(i), "native,builtin")
                    }
                }
            context.assets.open("builtin/wineprefix/wincomponents/wincomponents.json")
                .bufferedReader().use { r ->
                    val obj = org.json.JSONObject(r.readText())
                    for (identifier in obj.keys()) {
                        obj.optJSONObject(identifier)?.optJSONArray("dlnames")?.let { a ->
                            for (i in 0 until a.length()) overrides.put(a.optString(i), "native,builtin")
                        }
                    }
                }
        } catch (e: Exception) {
            log("⚠ 全局 DllOverrides 资产解析失败：${e.message}")
            return false
        }
        if (overrides.isEmpty()) return true
        val values = org.json.JSONArray()
        overrides.forEach { (dll, order) ->
            values.put(
                org.json.JSONObject()
                    .put("name", dll)
                    .put("type", "sz")
                    .put("data", order)
            )
        }
        val edits = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("hive", "HKCU")
                .put("key", "Software\\\\Wine\\\\DllOverrides")
                .put("values", values)
        )
        val err = JsonRegistry.apply(
            File(container.prefixPath), resolved, container, env, edits
        )
        if (err != null) {
            log("⚠ 全局 DllOverrides 注入失败：$err（下次启动自动重试）")
            return false
        }
        log(
            "全局 [Software\\\\Wine\\\\DllOverrides] 已落位（${overrides.size} 项：dinput/xinput=builtin,native，" +
                "direct3d(dx8_11/dx12)+全 wincomponents=native,builtin —— 原生 dll 缺席自动回退 builtin）"
        )
        return true
    }

    /**
     * v3.4.1【注册表大修·卫生总入口】：每次启动链幂等重跑
     * （首建 wineboot 后 / 每次会话启动前 / postPrefixBootChain 三处接线）。
     *
     * 顺序：
     *  0. wineserver 收尾（注册表内存缓存落盘 —— 否则 server 异步回写会把
     *     段剥除/JSON 直写整体覆盖，v3.3.20 清过仍被回写“打回原形”的
     *     隐藏缺陷就在这）；
     *  1. wine.inf 源头剥除（存量 proton 包的 31 条游戏 hack 就地根治，
     *     wineboot -u 不再重导入）；
     *  2. user.reg + system.reg 存量 AppDefaults 段剥除（白名单 = 管理器
     *     per-exe 账本）；
     *  3. 全局 [Software\\Wine\\DllOverrides] 注入（见
     *     [ensureGlobalDllOverrides]）。
     *
     * @return true 卫生完成（含“本就干净”）。
     */
    fun sanitizeRegistryHygiene(
        context: Context?,
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "user.reg").isFile && !File(prefix, "system.reg").isFile) return true
        return runCatching {
            // 0) wineserver 一致性
            if (WineServerUtils.isServerRunning(container.prefixPath)) {
                log("注册表卫生：收尾 wineserver（内存缓存落盘，防回写覆盖直写）……")
                runCatching { WineServerUtils.stop(resolved, container, env) }
                    .onFailure { log("⚠ wineserver 收尾失败：${it.message}（继续，尽力而为）") }
            }
            // ① 源头：wine.inf 剥除
            runCatching { sanitizeWineInf(resolved, log) }
                .onFailure { log("⚠ wine.inf 源头剥除异常：${it.message}（不影响启动）") }
            // ② 存量段：user.reg + system.reg
            val protected = readAppDefaultsLedger(prefix).keys.map { it.lowercase() }.toSet()
            var removed = 0
            listOf("user.reg", "system.reg").forEach { name ->
                removed += stripAppDefaultsSections(File(prefix, name), protected, log)
            }
            if (removed > 0) {
                val keep = if (protected.isEmpty()) "（无）"
                else "（保留：${protected.joinToString(",")}）"
                log(
                    "已剥除前缀 $removed 个 AppDefaults per-exe 段（user.reg/system.reg；wine.inf 与" +
                        "生态包打入的游戏加载序垃圾，恢复 wine 全局默认加载序；管理器 per-exe 配置$keep 不受影响）"
                )
            }
            // ③ 全局 DllOverrides 注入
            ensureGlobalDllOverrides(context, resolved, container, env, log)
            // ③b v3.4.7：音频驱动显式落位（[Software\\Wine\\Drivers] Audio
            // = pulse / alsa / 空）。不写时 wine 自动探测（pulse 优先），
            // 前缀里残留的旧值（winecfg 手改/生态包写入）会让驱动选择
            // 漂移 —— 显式写与容器 audio= 档一致，pulse 无声时可切
            // audio=alsa 桥档做驱动层排除（用户“保证正常使用 pulse 或者
            // nopulse 使用 alsa”诉求）。
            ensureAudioDriver(context, resolved, container, env, log)
            true
        }.getOrElse {
            log("⚠ 注册表卫生异常：${it.message}")
            false
        }
    }

    /**
     * v3.4.7：容器 audio= 档 → wine 音频驱动注册表显式落位。
     *
     * HKCU\Software\Wine\Drivers 的 "Audio" 值（winecfg「音频」页同一
     * 键）：
     *  - PULSE → "pulse"（只用 winepulse —— aarch64 原生 libpulse 直连
     *    rootfs 守护；winecfg 里“明明有音频端”但游戏无声时排除驱动漂移）；
     *  - ALSA  → "alsa"（只用 winealsa → libasound 默认 PCM → rootfs
     *    alsa-plugins pulse 桥（/etc/asound.conf）→ 同一守护 → sles-sink）；
     *  - OFF  → ""（空 = 不加载任何音频驱动，完全无声）。
     *
     * v3.4.8（用户 2026-11-12："我选择了 alsa 还是用的 pulse"）：
     * **winecfg 手改优先** —— 旧逻辑每次启动按容器档强制覆写，用户在
     * winecfg「音频」页选的驱动下次启动就被拉回。现在先读注册表现值
     * （[readUserRegSz]），现值与容器档映射不一致 = 用户在 winecfg 手改
     * → 容器档跟随注册表（ContainerManager.update 持久化，不在回写），
     * 后续会话即按新档走（守护/env 注册表一致）；键不存在或与档一致
     * 才落位（幂等）。
     *
     * JsonRegistry 直写 hive（与 [ensureGlobalDllOverrides] 同一通路，
     * 幂等；首建前缀 user.reg 缺席时跳过 —— postPrefixBootChain 会再试）。
     */
    fun ensureAudioDriver(
        context: Context?,
        resolved: WineRuntime.Resolved?,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        if (context == null) return false
        if (!File(container.prefixPath, "user.reg").isFile) return true
        val (value, human) = when (container.audio) {
            ContainerAudio.ALSA -> "alsa" to "winealsa（经 alsa-plugins pulse 桥 → 守护 → sles-sink）"
            ContainerAudio.OFF -> "" to "空（不加载音频驱动）"
            else -> "pulse" to "winepulse（原生 libpulse → 守护 → sles-sink）"
        }
        // v3.4.8：winecfg 手改检测（现值 ≠ 容器档映射 → 容器档跟随）
        val current = readUserRegSz(container.prefixPath, "Software\\\\Wine\\\\Drivers", "Audio")
        if (current != null && current != value) {
            val follow = when (current.lowercase().trim()) {
                "alsa" -> ContainerAudio.ALSA
                "pulse" -> ContainerAudio.PULSE
                "" -> ContainerAudio.OFF
                else -> null // 多驱动组合（"pulse,alsa" 等逗号串）：保留注册表原样
            }
            if (follow == null) {
                log("音频: winecfg 手改 Audio=\"$current\"（多驱动组合）—— 保留注册表值，容器档 ${container.audio.id} 不动")
                return true
            }
            if (follow != container.audio) {
                val err = ContainerManager.update(container.copy(audio = follow))
                log("音频: 检测到 winecfg 手改 Audio=\"${if (current.isEmpty()) "（空=禁用）" else current}\" —— 容器档跟随为 ${follow.id}" +
                    if (err == null) "（已持久化；原 ${container.audio.id}）" else "（⚠ 持久化失败：$err；本次会话仍按 $follow 生效）")
            }
            // 注册表用户值已在位 —— 不覆盖回写
            return true
        }
        val edits = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("hive", "HKCU")
                .put("key", "Software\\\\Wine\\\\Drivers")
                .put("values", org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("name", "Audio")
                        .put("type", "sz")
                        .put("data", value)
                ))
        )
        val err = JsonRegistry.apply(
            File(container.prefixPath), resolved, container, env, edits
        )
        if (err != null) {
            log("⚠ 音频驱动键写入失败：$err（下次启动自动重试）")
            return false
        }
        log("音频驱动已落位 [Software\\\\Wine\\\\Drivers] Audio=${if (value.isEmpty()) "（空=禁用）" else value} —— $human")
        return true
    }

    /**
     * v3.4.8：轻量注册表 sz 读（user.reg 文本直析，不起 wine 进程）。
     *
     * wine reg 格式：段头 `[Software\\\\Wine\\\\Drivers] 123456789`，
     * 值行 `"Audio"="pulse"`（字符串值；dword/hex 返回 null）。
     * 找不到键/段返回 null（区别于空串值 = 显式禁用）。
     */
    private fun readUserRegSz(
        prefixPath: String,
        keyPath: String,
        valueName: String
    ): String? = runCatching {
        val f = File(prefixPath, "user.reg")
        if (!f.isFile) return null
        val section = "[$keyPath]"
        val inSection: (String) -> Boolean = { it.startsWith(section) }
        val lines = f.readLines()
        var idx = lines.indexOfFirst(inSection)
        if (idx < 0) return null
        idx++
        while (idx < lines.size && !lines[idx].startsWith("[")) {
            val m = Regex("^\\\"${Regex.escape(valueName)}\\\"=\\\"(.*)\\\"$").find(lines[idx])
            if (m != null) return m.groupValues[1]
            idx++
        }
        null
    }.getOrNull()


    /**
     * v3.2.1 ③：wine 加载器冒烟测试（`wine --version`，限时 60 秒）。
     *
     * 动机（设备实测两份日志）：proton x86_64 前缀构建"直接退出没任何日志"、
     * arm64ec“无任何输出挂死”—— 这类静默阵亡发生在 loader/早期初始化，
     * 与 wineboot 逻辑无关；wineboot 包裹 explorer 虚拟桌面后，被信号
     * 杀死的进程连 stderr 缓冲都来不及刷（explorer 不透传退出码），
     * 前缀日志窗口从此“什么都没有”。冒烟测试直连 loader：
     *
     * - 退出码 0 + 版本号 → 一切就绪，顺带把版本写进前缀日志；
     * - 159（SIGSYS）→ fsync 探测被 seccomp 击杀（旧包，无 __ANDROID__ 守卫）；
     * - 秒退无输出 → loader 阶段阵亡（v3.2.3 定位：wine-preloader 预加载器
     *   在 bionic/box64 下交接失败；旧包缺 x86_64-bionic 闭包时 box64 的
     *   "Symbol … not found" stderr 也可能被信号丢失）；
     * - 超时 → loader 本身挂死（arm64ec：预加载器交接后的第二遍
     *   __wine_main 挂在 virtual_init/早期初始化 —— v3.2.3 构建侧
     *   disable_preloader_android 根治）。
     *
     * 注意：--version 在 wine ≤10 树由 check_command_line 处理，位于
     * 预加载器再执行**之前** —— 冒烟测试通过不代表预加载器路径健康
     * （v3.2.3 设备日志实证：proton arm64ec --version 可过、wineboot
     * 挂死）。它只覆盖 loader/ntdll 加载层的其它故障（符号缺失、
     * SIGSYS、路径错误）。
     *
     * 结果按（wine 目录 + 容器）记忆：同一运行时只测一次，不拖慢正常启动。
     */
    private val smokeTested = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * v3.2.3：internal —— 手动 wineboot 会话（RootfsLauncher 8b 步）复用
     * 同一测试与缓存，不再私有。
     */
    internal fun loaderSmokeTest(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit
    ): Boolean {
        val cacheKey = "${resolved.wineDir.absolutePath}|${resolved.backend.id}|${container.name}"
        smokeTested[cacheKey]?.let { return it }
        // v3.3.40【性能】：跨重启持久标记（内容 = wine 主程序 mtime）——
        // 旧版缓存仅进程内，每次 App 重启后首个 wineboot 会话都要重拉
        // `wine --version` 子进程（1s 级）；二进制未换（mtime 一致）即免测。
        val sm = File(resolved.wineDir, ".linbox-smoketest-ok")
        val wantMtime = runCatching { resolved.wineBinary.lastModified() }.getOrDefault(-1L)
        if (sm.isFile && wantMtime > 0) {
            val recorded = runCatching { sm.readText().trim().toLongOrNull() }.getOrNull()
            if (recorded == wantMtime) {
                smokeTested[cacheKey] = true
                log("加载器冒烟测试: 持久标记命中（wine 主程序未变化，免重测）")
                return true
            }
        }
        log("加载器冒烟测试: ${WineRuntime.wineCommand(resolved).joinToString(" ")} --version …")
        // v3.2.3：剥离 WINELOADERNOEXEC —— wine ≤10 树的 --version 分发
        // （check_command_line）与该变量同一守卫块，不剥离会把
        // "--version" 当程序名误报失败。同时这不失为更严格的检验：
        // 直连 loader 的原始链路被真实地测到。
        val testEnv = LinkedHashMap(env)
        testEnv.remove("WINELOADERNOEXEC")
        val output = StringBuilder()
        return try {
            val executor = NativeShellExecutor.create()
                .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(), "--version")
                .putEnv(testEnv)
                .putEnv("WINEPREFIX", container.prefixPath)
                .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                .onStdOut { synchronized(output) { output.append(it).append('\n') } }
                .onStdErr { synchronized(output) { output.append(it).append('\n') } }
                .exec()
            val finished = runCatching {
                executor.waitFor(60, TimeUnit.SECONDS)
            }.getOrDefault(false)
            val code = runCatching { executor.waitFor() }.getOrDefault(-1)
            val text = synchronized(output) { output.toString().trim() }
            val ok = finished && code == 0 && text.isNotEmpty()
            if (ok) {
                log("加载器正常: ${text.lineSequence().first().take(60)}")
            } else if (!finished) {
                runCatching { executor.stopAndWait(true, 5, TimeUnit.SECONDS) }
                log("加载器冒烟测试超时（60 秒无退出）—— loader 本身挂死，非 wineboot 逻辑问题")
                log("→ 高度可疑：wine ≤10 树（proton_8/9/10 等）的 wine-preloader 预加载器交接（v3.2.3 已在启动链停用 bin/*-preloader，若仍挂请发回下方现场）；hangover-10.18+/wine-11 树无此路径")
                dumpWineHangSnapshot(resolved, container, log)
            } else {
                log("加载器冒烟测试失败: 退出码 $code，输出：${if (text.isEmpty()) "（无任何输出）" else text.take(400)}")
                when (code) {
                    159 -> log("→ 159 = SIGSYS：fsync futex_waitv 探测被 seccomp 击杀（旧包无 __ANDROID__ 守卫；启动链 1.5 步的探测改写通常已免重编根治，若仍出现请反馈）")
                    139 -> log("→ 139 = SIGSEGV：loader 段错误")
                    126, 127 -> log("→ $code = 可执行体无法启动（路径/权限）")
                    else -> {
                        if (text.isEmpty()) {
                            log("→ 秒退且无输出：box64/wine loader 阶段阵亡 —— 两类高发：①旧包缺 lib/x86_64-bionic 依赖闭包（box64 \"Symbol shm_open not found\"，stderr 常被丢失）；②wine-preloader 预加载器在 box64 仿真下跑不通（v3.2.3 已在启动链停用）。仍需重编时请用 v3.2.3+ CI（闭包 + 探测守卫 + 预加载器禁用）")
                        }
                    }
                }
            }
            // 只缓存成功结果：失败不缓存（用户重装/重编 wine 包后下次自动重测）
            if (ok) {
                smokeTested[cacheKey] = true
                if (wantMtime > 0) {
                    runCatching { sm.writeText(wantMtime.toString()) }
                }
            }
            ok
        } catch (e: Exception) {
            log("加载器冒烟测试异常: ${e.message}")
            // 异常不代表 loader 坏（可能是执行器问题）—— 放行，让 wineboot 自己给证据
            true
        }
    }

    /**
     * v3.2.0：wineboot 超时挂死现场抓取 —— 扫 /proc 找 wine 进程树
     * （cmdline 含 wine 目录或 WINEPREFIX 特征），把每个进程的调度状态
     * （R 运行/S 睡眠/D 不可中断IO/Z 僵尸）与各线程的内核等待点（wchan）
     * 写进前缀日志。proton10 arm64ec“无任何输出永久挂死”下一轮就有
     * 内核级证据面（R=死循环嫌疑、S+wchan=futex=等锁死锁、D=IO 阻塞）。
     *
     * v3.2.3：internal —— 手动 wineboot 会话（RootfsLauncher 的前缀构建
     * 流）的看门狗同样需要抓现场，不再私有。
     */
    internal fun dumpWineHangSnapshot(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        log: (String) -> Unit
    ) {
        runCatching {
            // wine 的 PE 子进程（wineboot/services/explorer…）cmdline 是
            // Windows 风格（不含 unix 路径），需按 exe 名一并匹配；
            // /proc 下非本 UID 进程读取即失败，自动被过滤，无越权风险。
            val markers = listOf(
                resolved.wineDir.absolutePath,
                container.prefixPath,
                "wineboot", "wineserver", "explorer.exe",
                "services.exe", "winedevice.exe", "plugplay.exe", "start.exe"
            )
            val procs = File("/proc").listFiles { f -> f.name.toIntOrNull() != null }
                .orEmpty().mapNotNull { proc ->
                    val cmdline = runCatching {
                        File(proc, "cmdline").readText().replace('\u0000', ' ').trim()
                    }.getOrNull() ?: return@mapNotNull null
                    if (markers.any { cmdline.contains(it) }) proc to cmdline else null
                }
            // v3.3.1：wineserver 运行目录状态（socket/lock 残留 = 握手阻塞
            // 判定的另一面证据：socket 在而守护无响应）—— 先于进程清单打印：
            // 无任何 wine 进程但 socket 仍在 = 死 server 残留；两者俱全而
            // 客户端无输出 = 冻结/挂死 server 使握手阻塞
            val serverRoot = File(container.prefixPath, ".wineserver")
            if (serverRoot.isDirectory) {
                val subdirs = serverRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
                log("wineserver 目录: ${serverRoot.absolutePath}（${subdirs.size} 个 server 会话目录）")
                for (dir in subdirs) {
                    val sock = File(dir, "socket").exists()
                    val lock = File(dir, "lock").exists()
                    log("    ${dir.name}: socket=${if (sock) "存在" else "无"} lock=${if (lock) "存在" else "无"}")
                }
            } else {
                log("wineserver 目录: 不存在（$serverRoot —— 客户端将拉起全新 server）")
            }
            if (procs.isEmpty()) {
                log("挂死现场: 未找到 wine 相关进程（可能已退出/被杀）")
                return
            }
            log("挂死现场（${procs.size} 个 wine 相关进程；R=运行 S=睡眠 D=IO阻塞 Z=僵尸）:")
            for ((proc, cmdline) in procs) {
                val state = runCatching {
                    File(proc, "stat").readText().split(" ").getOrNull(2) ?: "?"
                }.getOrDefault("?")
                log("  pid=${proc.name} state=$state cmd=${cmdline.take(120)}")
                // v3.3.1：wineserver 守护的 cwd（$WINEPREFIX/.wineserver/…）
                // —— 判定其归属前缀与是否残留的权威证据
                if (cmdline.contains("wineserver")) {
                    val cwd = runCatching { android.system.Os.readlink("/proc/${proc.name}/cwd") }.getOrNull()
                    if (cwd != null) log("    wineserver cwd=$cwd")
                }
                val tasks = runCatching {
                    File(proc, "task").listFiles { f -> f.name.toIntOrNull() != null }
                }.getOrNull() ?: continue
                for (task in tasks.take(24)) {
                    val tid = task.name
                    val tState = runCatching {
                        File(task, "stat").readText().split(" ").getOrNull(2) ?: "?"
                    }.getOrDefault("?")
                    val wchan = runCatching {
                        File(task, "wchan").readText().trim().ifEmpty { "?" }
                    }.getOrDefault("?")
                    log("    tid=$tid state=$tState wchan=$wchan")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // /opt/prefix 前缀预置资产注入（v3.2.25，startwine.sh 301-369 行移植）
    // ------------------------------------------------------------------

    /** opt/prefix 注入完成标记（幂等）。 */
    private fun optPrefixMarker(prefix: File) = File(prefix, ".linbox-opt-prefix")

    /**
     * v3.3.3：/opt/prefix 注册表 bionic regedit 兼容性规范化（用户问题 4：
     * “opt 生态包里的 reg 注册表总是报错，是不是格式不适合 bionic”），
     *
     * wine regedit 对导入文件的要求（reg_import.c 同源规则）：
     *  - 头行必须是 "WINE REGISTRY Version 2"（wine 原生）/"REGEDIT4"/
     *    "Windows Registry Editor Version 5.00" 之一；生态包手工维护的
     *    .reg 常缺头行 → regedit 逐键报错（GUI 弹窗即由此来）；
     *  - Windows regedit 导出的 5.00 文件常为 UTF-16LE+BOM，生态包二次
     *    保存可能丢 BOM / 换成带 BOM 的 UTF-8 → 解析失败；
     *  - CRLF 兼容，但 UTF-16 无 BOM 时首键会被吞。
     *
     * 规范化（不改 /opt 源文件 —— 规范化副本落前缀 .linbox-regtmp 再导入）：
     *  1. UTF-16LE(BOM) → UTF-8；UTF-8 BOM 剥离；
     *  2. CRLF/CR → LF；
     *  3. 缺头行时按首个键行的反斜杠形态补头：含 "\\"（双反斜杠转义）
     *     → "WINE REGISTRY Version 2"；否则 → "Windows Registry Editor
     *     Version 5.00"（hex(2)/hex(7) 值类型全兼容）；
     *  4. 已合法 → 不生成副本（返回 null，调用方原样导入）。
     *
     * @return 规范化副本路径；无需改动/读取失败时 null（调用方回退原文件）。
     */
    private fun normalizeRegForBionic(src: File, workDir: File, log: (String) -> Unit): File? {
        return runCatching {
            val raw = src.readBytes()
            if (raw.isEmpty()) return null
            val actions = mutableListOf<String>()
            val body: String = when {
                raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte() -> {
                    actions.add("UTF-16LE→UTF-8")
                    String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
                }
                raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte() -> {
                    actions.add("剥离 UTF-8 BOM")
                    String(raw, 3, raw.size - 3, Charsets.UTF_8)
                }
                else -> String(raw, Charsets.UTF_8)
            }
            val unified = body.replace("\r\n", "\n").replace('\r', '\n')
            if (unified != body) actions.add("CRLF→LF")
            // v3.3.5（用户诉求：「arm64ec wine 构建时还是有 AppDefaults 的
            // DllOverrides 注册表打入，不应该有的」）：生态包 user.reg 为
            // 若干游戏预置的 AppDefaults\\<exe>\\DllOverrides 段（设备截图
            // 实证：s2_mp64_ship.exe / s2_sp64_ship.exe / ShadowOfWar.exe /
            // Spider-Man.exe / starwarssquadrons.exe 的 atiadlxx=builtin /
            // amd_ags_x64=disabled、TDUSC.exe / WRCG.exe 的
            // xinput1_3=native,builtin）是加载序污染源 —— 错误的原生/
            // 内建 DLL 选择直接诱发“很多游戏 media 错误”。导入前整段
            // 剥除（AppDefaults 里非 DllOverrides 的键原样保留）；DLL
            // 加载序的唯一权威 = wine 默认 + 用户显式 WINEDLLOVERRIDES。
            val text = stripAppDefaultsDllOverrides(unified)?.let { (stripped, sections) ->
                actions.add("剥除 AppDefaults\\\\DllOverrides $sections 段")
                stripped
            } ?: unified
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val hasHeader = firstLine.startsWith("WINE REGISTRY") ||
                firstLine.equals("REGEDIT4", ignoreCase = true) ||
                firstLine.startsWith("Windows Registry Editor")
            val finalText = if (hasHeader) text else {
                // 首个键行的反斜杠形态：wine 原生格式键路径双反斜杠转义
                //（[Software\\Wine\\...]），Windows 5.00 格式单反斜杠。
                val firstKey = text.lineSequence()
                    .firstOrNull { it.trim().startsWith("[") }?.trim().orEmpty()
                val wineStyle = firstKey.contains("\\\\")
                actions.add(
                    if (wineStyle) "补 WINE REGISTRY Version 2 头行"
                    else "补 Windows Registry Editor Version 5.00 头行"
                )
                (if (wineStyle) "WINE REGISTRY Version 2\n\n" else "Windows Registry Editor Version 5.00\n\n") + text
            }
            if (actions.isEmpty()) return null
            workDir.mkdirs()
            val out = File(workDir, src.name)
            out.writeText(finalText)
            log("→ 规范化: ${src.name}（${actions.joinToString("、")}）—— 以副本导入，/opt 源文件未改")
            out
        }.getOrNull()
    }

    /**
     * 剥除 reg 文本中的 [Software\\Wine\\AppDefaults\\<exe>\\DllOverrides]
     * 段（含段头与段体，直到下一个 '[' 节头或文件结束；hex 多行续行
     * 不以 '[' 开头，天然归入段体）。AppDefaults 的其它子键（Version /
     * Direct3D 等）不动。
     * @return null（无命中，原文本即最终文本）或 (剥后文本, 剥除段数)。
     */
    internal fun stripAppDefaultsDllOverrides(text: String): Pair<String, Int>? {
        if (!text.contains("AppDefaults")) return null
        val sb = StringBuilder(text.length)
        var skipping = false
        var removed = 0
        text.lineSequence().forEach { line ->
            if (line.startsWith("[")) {
                val path = Regex("^\\[([^\\]]*)\\]").find(line)?.groupValues?.get(1).orEmpty()
                skipping = path.contains("AppDefaults") && path.endsWith("DllOverrides")
                if (skipping) removed++
            }
            if (!skipping) sb.append(line).append('\n')
        }
        return if (removed > 0) sb.toString() to removed else null
    }

    // ------------------------------------------------------------------
    // v3.3.5（用户诉求：arm64ec 前缀不应存在 AppDefaults DllOverrides）
    // ------------------------------------------------------------------

    /** AppDefaults 清洗已完成的标记（容器前缀内，幂等）。 */
    private fun appDefaultsPurgeMarker(prefix: File) = File(prefix, ".linbox-appdefaults-purged")

    /**
     * 清洗旧前缀里已被生态包 user.reg 打入的 AppDefaults\\<exe>\\DllOverrides
     * 存量键值 —— 导入侧剥段（normalizeRegForBionic → strip…）只管未来，
     * 存量用 wine regedit 的 `"值"=-` 删除语法清掉，恢复 wine 默认 DLL
     * 加载序（空键壳无加载语义，保留无害）。解析前缀 user.reg 文本枚举
     * 段与值名（含 `@=` 默认值），生成删除注册表经 regedit /S 导入 ——
     * 与 v3.2.27 旧键清洗同一通路，wineserver 一致性有保障。幂等标记；
     * 无命中直接落标记。
     */
    fun purgeAppDefaultsDllOverrides(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = File(container.prefixPath)
        if (appDefaultsPurgeMarker(prefix).isFile) return true
        val userReg = File(prefix, "user.reg")
        if (!userReg.isFile) return true
        return try {
            val sections = LinkedHashMap<String, MutableList<String>>()
            var cur: String? = null
            userReg.readLines().forEach { line ->
                if (line.startsWith("[")) {
                    val path = Regex("^\\[([^\\]]*)\\]").find(line)?.groupValues?.get(1).orEmpty()
                    cur = if (path.contains("AppDefaults") && path.endsWith("DllOverrides")) path else null
                    cur?.let { sections.getOrPut(it) { mutableListOf() } }
                } else {
                    val key = cur ?: return@forEach
                    if (line.isBlank()) return@forEach
                    val names = sections.getOrPut(key) { mutableListOf() }
                    val vm = Regex("^\"(.*)\"=").find(line)
                    when {
                        vm != null -> names.add(vm.groupValues[1])
                        line.startsWith("@=") -> names.add("@")
                    }
                }
            }
            if (sections.isEmpty()) {
                runCatching { appDefaultsPurgeMarker(prefix).createNewFile() }
                log("AppDefaults DllOverrides 清洗：无需清洗（user.reg 无游戏级 DLL 加载序）")
                return true
            }
            val totalValues = sections.values.sumOf { it.size }
            log("清洗 AppDefaults DllOverrides（${sections.keys.size} 个游戏键 / $totalValues 个值 —— 恢复 wine 默认加载序）……")
            // v3.3.40【JSON 打入】：删除值改为 JSON 描述经 [JsonRegistry] 直
            // 接从 user.reg 文本移除 —— 旧链路生成删除语义 .reg 再拉起
            // `wine regedit /S`（1~3s）；新链路零 wine 进程。
            val edits = org.json.JSONArray()
            sections.forEach { (key, names) ->
                val values = org.json.JSONArray()
                names.forEach { n ->
                    values.put(
                        org.json.JSONObject()
                            .put("name", n)
                            .put("type", "delete")
                    )
                }
                edits.put(
                    org.json.JSONObject()
                        .put("hive", "HKCU")
                        .put("key", key.replace("\\\\", "\\"))
                        .put("values", values)
                )
            }
            val err = JsonRegistry.apply(
                File(container.prefixPath), resolved, container, env,
                edits, log
            )
            if (err == null) {
                runCatching { appDefaultsPurgeMarker(prefix).createNewFile() }
                log("清洗 AppDefaults DllOverrides 完成 ✓（JSON 直删；键壳保留无碍；/opt/prefix 导入侧此后自动剥段）")
                true
            } else {
                log("警告: AppDefaults 清洗 JSON 直删失败（$err；下次启动重试）")
                false
            }
        } catch (e: Exception) {
            log("警告: AppDefaults 清洗异常: ${e.message}（下次启动重试）")
            false
        }
    }

    /**
     * 发行包风格 wine 发行包的前缀预置资产注入（用户问题：
     * "构建前缀也没有解压 rootfs/opt/prefix/ 下的文件和注册表注册"）。
     *
     * 参考链路（startwine.sh 前缀构建段）：
     *  1. drive_c.tar.xz / directx.tar.xz → drive_c/
     *  2. start 目录 → ProgramData/Microsoft/Windows/Start Menu（开始菜单）
     *  3. marlett.ttf → windows/Fonts
     *  4. user.reg / system.reg / fix-services.reg → wine regedit 导入
     *  5. fix-fonts.tar.xz → windows/
     *  6. opt/dxvk1 下的 dxvk 归档 → windows/（virgl-enabled 标记存在时跳过）
     *  7. .update-timestamp=disable（参考脚本同款防重跑语义）
     *
     * 约定：
     *  - 仅当 opt/prefix 目录存在时生效（未安装 /opt 生态包零动作）；
     *  - 幂等（.linbox-opt-prefix 标记）；任一步失败仅记录、不阻断启动
     *    （与参考脚本容错语义一致；失败步骤下次重建容器时重试）；
     *  - 归档双后缀候选（.tar.xz/.txz/.tar）+ 魔数嗅探（tar.gz 强解压
     *    防御的运行期语义，与 LinBoxTzstAssets 同源思路）；
     *  - 与终端侧 linbox-wine bootstrap_opt_prefix 同源双保险 —— 两端
     *    各自幂等，先到先应用（标记互认）。
     *
     * v3.2.26（用户问题："wineboot -i 结束后后续的解压以及注册表全都没
     * 有显示"）：全部早退分支改为**大声日志** —— 前缀未就绪 / 未检测到
     * /opt 生态 / 已注入，每一步都在前缀构建窗口可见；探测候选加入 wine
     * 槽位目录（rootfs/opt → 槽位 → rootfs/usr/opt），包内 conf/prefix
     * 与 bin/ 平级落进槽位的布局也能命中。
     *
     * @return true 表示无需注入（无 /opt/prefix / 已注入 / 前缀未就绪）。
     */
    fun bootstrapOptPrefix(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) {
            // 大声：这一步就是"wineboot 之后什么都不显示"的隐形早退之一
            log("跳过 /opt/prefix 预置资产注入：前缀未就绪（system.reg 缺失 —— wineboot 未成功）")
            val freeMb = runCatching { prefix.usableSpace / 1024 / 1024 }.getOrDefault(-1L)
            if (freeMb in 0..64) {
                log("→ 疑似根因：内部存储空间仅剩 ${freeMb}MB（wineboot 需写入上千个文件），请先清理空间再重建容器")
            }
            return true
        }
        if (optPrefixMarker(prefix).isFile) {
            // v3.3.3（用户日志实证：标记命中后每次启动零日志，用户误读为
            // “构建前缀在 wineboot 后就结束了/注册表没打”）—— 幂等跳过
            // 也要大声说出来了。
            log("/opt/prefix 已注入过（.linbox-opt-prefix 标记命中）—— 跳过重复注入")
            log("（如需重打注册表/资产：删除前缀内 .linbox-opt-prefix 标记或重建前缀）")
            return true
        }
        // v3.2.28：注入前先跑 /opt 生态自愈（rootfs/opt 顶层生态 tar 包
        // 自动解包 + 套层/槽位 opt/ 候选）—— 生态包没解开时这里是最后一道补救
        OptEcosystemSupport.ensure(resolved.wineDir, log)
        val root = WineEnvironment.optRoot(resolved.wineDir)
        if (root == null) {
            // 大声：探测了哪些路径、各有没有内容，一目了然（用户核对路径用）
            log("跳过 /opt/prefix 预置资产注入：未检测到 /opt 生态")
            log("→ 探测明细: ${WineEnvironment.optProbeReport(resolved.wineDir)}")
            log("→ 期望位置: /data/data/com.linbox/files/rootfs/opt/{conf,locale.conf,prefix,dxvk1} 或 wine 槽位目录内同名布局")
            return true
        }
        val pd = File(root, "prefix")
        if (!pd.isDirectory) {
            log("跳过 /opt/prefix 预置资产注入：$root 有 /opt 生态但无 prefix/ 目录（仅应用 conf/locale.conf）")
            return true
        }

        log("检测到 /opt/prefix 预置资产（${pd.absolutePath}），开始注入……")
        val driveC = File(prefix, "drive_c")
        val windows = File(driveC, "windows")

        // v3.3.4（用户问题 5："arm64ec架构的要跳过两个xz的解压，否则无法
        // 运行的"）：参考链路事实 —— startfex.sh（arm64ec/FEX 路径）没有
        // drive_c.tar.xz / directx.tar.xz 两步解压（只有 startwine.sh 的
        // x86_64 路径有）；x86 预置包解进 arm64 前缀会把容器搞到无法运行。
        // install.bat（Physx/7-zip/VC++ x86 运行库）同理跳过。
        val arm64ec = resolved.arch == WineRuntime.Arch.AARCH64 ||
            container.backend == ContainerBackend.ARM64EC

        // ① drive_c / directx 归档（arm64ec 跳过）
        if (arm64ec) {
            log("arm64ec 架构：跳过 drive_c.tar.xz / directx.tar.xz 预置解压（x86 预置包会导致 arm64 前缀无法运行）")
        } else {
            extractOptArchive(pd, "drive_c", driveC, log)
            extractOptArchive(pd, "directx", driveC, log)
        }

        // ② 开始菜单快捷方式
        val startDir = File(pd, "start")
        if (startDir.isDirectory) {
            val menu = File(driveC, "ProgramData/Microsoft/Windows/Start Menu")
            runCatching {
                menu.mkdirs()
                startDir.let { src ->
                    com.linbox.util.SafeFileOps.copyTree(src, menu).also {
                        if (!it) log("警告: 开始菜单复制未完成（部分文件失败）")
                    }
                }
                log("开始菜单快捷方式 → ${menu.absolutePath}")
            }.onFailure { log("警告: 开始菜单复制失败: ${it.message}") }
        }

        // ③ marlett 字体
        File(pd, "marlett.ttf").takeIf { it.isFile }?.let { src ->
            runCatching {
                File(windows, "Fonts").mkdirs()
                src.copyTo(File(windows, "Fonts/marlett.ttf"), overwrite = true)
                log("marlett.ttf → windows/Fonts")
            }.onFailure { log("警告: marlett.ttf 复制失败: ${it.message}") }
        }

        // ④ 注册表导入（v3.3.40【JSON 打入】）：
        //     a. prefix/*.json —— 生态包/用户侧资产直接 [JsonRegistry] 文本
        //        合并（零 wine 进程，“注册表打入用 JSON” 的外部入口）；
        //     b. 存量 .reg（user.reg/system.reg/fix-services.reg）—— 规范化
        //        后【三合一】单次 `wine regedit /S` 导入（旧链路 3 次
        //        regedit 进程拉起 → 1 次；外部 .reg 文本为生态包资产，
        //        保持 regedit 通道由 wineserver 保证合并语义）。
        val regTmpDir = File(prefix, ".linbox-regtmp")
        val regNames = listOf("user.reg", "system.reg", "fix-services.reg")
        // ④a JSON 直写通道
        runCatching {
            val jsonFiles = pd.listFiles { f ->
                f.isFile && f.name.endsWith(".json")
            }.orEmpty()
            for (jf in jsonFiles) {
                log("导入注册表(JSON): ${jf.name}")
                val err = JsonRegistry.applyJsonFile(
                    jf, prefix, resolved, container, env, log
                )
                if (err != null) log("警告: ${jf.name} JSON 直写失败（$err；继续其余步骤）")
                else log("导入注册表(JSON): ${jf.name} ✓（零 wine 进程）")
            }
        }.onFailure { log("警告: prefix/*.json 导入异常: ${it.message}（继续）") }
        // ④b .reg 批量通道（三合一单次 regedit）
        run {
            val bodies = StringBuilder()
            val imported = mutableListOf<String>()
            for (name in regNames) {
                val reg = File(pd, name)
                if (!reg.isFile) continue
                val normalized = normalizeRegForBionic(reg, regTmpDir, log) ?: reg
                runCatching {
                    val text = normalized.readText()
                    // 剥头行/头部空行后拼接（REGEDIT4/5.00 头只保留首个；
                    // 首个文件的头随首体进入）
                    var t = text
                    val first = t.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                    val hasHeader = first.startsWith("WINE REGISTRY") ||
                        first.equals("REGEDIT4", true) ||
                        first.startsWith("Windows Registry Editor")
                    if (hasHeader) {
                        t = t.substringAfter('\n')
                        while (t.startsWith("\n") || t.startsWith("\r")) t = t.substring(1)
                    }
                    if (bodies.isNotEmpty()) bodies.append('\n')
                    bodies.append(t)
                    imported.add(name)
                }.onFailure { log("警告: ${name} 读取失败: ${it.message}") }
            }
            if (imported.isNotEmpty()) {
                log("导入注册表: ${imported.joinToString(" + ")}（三合一单次 regedit）")
                runCatching {
                    val merged = File(regTmpDir, "opt-prefix-merged.reg")
                    regTmpDir.mkdirs()
                    merged.writeText(
                        "WINE REGISTRY Version 2\n\n" + bodies.toString()
                    )
                    val cmd = NativeShellExecutor.create()
                        .setCommand(
                            *WineRuntime.wineCommand(resolved).toTypedArray(),
                            "regedit", "/S", merged.absolutePath
                        )
                        .putEnv(env)
                        .putEnv("WINEPREFIX", container.prefixPath)
                        .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                        .exec()
                    val code = cmd.waitFor()
                    if (code != 0) log("警告: regedit 批量导入退出码 $code（继续其余步骤）")
                    else log("导入注册表: ${imported.joinToString(" + ")} ✓")
                }.onFailure { log("警告: regedit 批量导入异常: ${it.message}") }
            }
        }
        // ⑤c（提前）：注册表写完先收尾 wineserver（JSON 直写通道同样需要 ——
        //      wineboot 后 server 可能仍在，内存缓存回写会覆盖文件写入）
        runCatching {
            if (WineServerUtils.isServerRunning(container.prefixPath)) {
                log("注册表注入完成，收尾 wineserver（内存注册表缓存落盘）……")
                WineServerUtils.stop(resolved, container, env)
            }
        }

        // ⑤ fix-fonts 归档 → windows/
        extractOptArchive(pd, "fix-fonts", windows, log)

        // ⑤b v3.2.28（startwine.sh 同款）：安装包自带运行库批处理
        //     （Physx 9.10 / 7-zip / VC++ 可再发行包 —— /opt/apps/install.bat，
        //     640x480 虚拟桌面静默跑，完后 wineserver -k 收尾落盘）。
        //     安装包不存在该文件时零动作；失败只记日志不阻断（与参考
        //     脚本容错语义一致）。
        val installBat = File(root, "apps/install.bat")
        if (installBat.isFile && arm64ec) {
            // v3.3.4：arm64ec 跳过 install.bat（全是 x86 运行库安装器）
            log("arm64ec 架构：跳过 install.bat 运行库安装（x86 运行库不适合 arm64 前缀）")
        } else if (installBat.isFile) {
            log("安装运行库（install.bat：Physx/7-zip/VC++，640x480 虚拟桌面）……")
            runCatching {
                val cmd = NativeShellExecutor.create()
                    .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(),
                                "explorer", "/desktop=shell,640x480",
                                "start", "/unix", "/opt/apps/install.bat")
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                    .exec()
                val finished = runCatching { cmd.waitFor(600, TimeUnit.SECONDS) }.getOrDefault(false)
                if (!finished) {
                    runCatching { cmd.stopAndWait(true, 10, TimeUnit.SECONDS) }
                    log("警告: install.bat 执行超时（600s），已终止（继续其余步骤）")
                } else {
                    log("install.bat 执行结束（退出码 ${runCatching { cmd.waitFor() }.getOrDefault(-1)}）")
                }
                // 参考脚本同款：运行库装完立即收尾 wineserver，注册表落盘
                val serverBin = WineRuntime.hostToolBin(resolved, "wineserver")
                NativeShellExecutor.create()
                    .setCommand(serverBin.absolutePath, "-k")
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                    .exec()
                    .waitFor(15, TimeUnit.SECONDS)
            }.onFailure { log("警告: install.bat 执行异常: ${it.message}（继续）") }
        }

        // ⑥ dxvk（virgl 启用时跳过 —— 参考脚本同款条件）
        if (!File(root, "virgl/virgl-enabled").exists()) {
            val dxvkDir = File(root, "dxvk1")
            val archive = dxvkDir.takeIf { it.isDirectory }
                ?.listFiles { f ->
                    f.isFile && (
                        f.name.endsWith(".tar.gz") || f.name.endsWith(".tgz") ||
                            f.name.endsWith(".tar.xz") || f.name.endsWith(".txz") ||
                            f.name.endsWith(".tar")
                        )
                }
                ?.maxByOrNull { it.name }
            if (archive != null) {
                log("安装 dxvk: ${archive.name}")
                extractOptArchive(dxvkDir, archive.name, windows, log, explicit = archive.name)
            }
        }

        // ⑦ v3.3.40：收尾 wineserver 落盘注册表 —— 有存活 server 才动作
        //    （参考脚本 wineserver -w/-k 同款语义；旧版盲拉 2 个 wineserver
        //    进程，server 不在跑时纯浪费启动时间）。
        runCatching {
            if (WineServerUtils.isServerRunning(container.prefixPath)) {
                val serverBin = WineRuntime.hostToolBin(resolved, "wineserver")
                NativeShellExecutor.create()
                    .setCommand(serverBin.absolutePath, "-w")
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                    .exec()
                    .waitFor(20, TimeUnit.SECONDS)
                NativeShellExecutor.create()
                    .setCommand(serverBin.absolutePath, "-k")
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                    .exec()
                    .waitFor(10, TimeUnit.SECONDS)
            }
        }
        // ⑤c（原块）已并入 ④：JSON 直写通道的收尾在那儿完成

        runCatching { optPrefixMarker(prefix).createNewFile() }
        runCatching { File(prefix, ".update-timestamp").writeText("disable") }
        log("/opt/prefix 预置资产注入完成")
        return true
    }

    /**
     * opt/prefix 归档解压：按双后缀候选（.tar.xz/.txz/.tar）+ 魔数嗅探
     * 选解压器（gzip/xz/zstd/plain tar），经 TarCompressorUtils 落盘。
     */
    private fun extractOptArchive(
        dir: File,
        base: String,
        dest: File,
        log: (String) -> Unit,
        explicit: String? = null
    ) {
        val candidates = if (explicit != null) {
            listOf(File(dir, explicit))
        } else {
            listOf(".tar.xz", ".txz", ".tar").map { File(dir, "$base$it") }
        }
        val archive = candidates.firstOrNull { it.isFile } ?: return
        runCatching {
            val type = sniffTarType(archive)
                ?: run {
                    log("警告: ${archive.name} 魔数无法识别，跳过")
                    return
                }
            dest.mkdirs()
            val ok = com.termux.x11.controller.core.TarCompressorUtils.extract(type, archive, dest)
            if (ok) {
                log("解压 ${archive.name} → ${dest.absolutePath}")
            } else {
                log("警告: ${archive.name} 解压失败（下次重建容器时重试）")
            }
        }.onFailure { log("警告: ${archive.name} 解压异常: ${it.message}") }
    }

    /** 归档魔数嗅探（与 LinBoxTzstAssets.sniffType 同源）。 */
    private fun sniffTarType(file: File): com.termux.x11.controller.core.TarCompressorUtils.Type? {
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(6)
                val n = raf.read(head)
                val b = head.toList()
                when {
                    n >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte() ->
                        com.termux.x11.controller.core.TarCompressorUtils.Type.GZIP
                    n >= 6 && b[0] == 0xfd.toByte() && b[1] == 0x37.toByte() &&
                        b[2] == 0x7a.toByte() && b[3] == 0x58.toByte() &&
                        b[4] == 0x5a.toByte() && b[5] == 0x00.toByte() ->
                        com.termux.x11.controller.core.TarCompressorUtils.Type.XZ
                    n >= 4 && b[0] == 0x28.toByte() && b[1] == 0xb5.toByte() &&
                        b[2] == 0x2f.toByte() && b[3] == 0xfd.toByte() ->
                        com.termux.x11.controller.core.TarCompressorUtils.Type.ZSTD
                    else -> {
                        // 纯 tar：ustar 魔数在 257 偏移
                        raf.seek(257)
                        val magic = ByteArray(5)
                        val m = raf.read(magic)
                        if (m == 5 && String(magic) == "ustar") {
                            com.termux.x11.controller.core.TarCompressorUtils.Type.PLAIN
                        } else null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
