package com.linbox.apps.containers.launcher

import android.content.Context
import com.linbox.apps.containers.Box64Preset
import com.linbox.apps.containers.ContainerLangs
import com.linbox.apps.containers.ContainerData
import com.linbox.apps.containers.ContainerManager
import java.io.File

/**
 * rootfs 环境白名单构建器 —— 原生启动的"隔离"核心。
 *
 * 完整移植 linbox-wine 脚本的 isolate_env / apply_gpu_env / apply_box64_env /
 * apply_extra_env 四段，但实现方式彻底改变：**不再把 Termux 环境传进 pty
 * 再让 bash 脚本逐个 unset/export 清洗**，而是由 App 端一次性构建出
 * rootfs 视角的完整白名单，经 [NativeShellExecutor] 的 ProcessBuilder
 * environment() 直接写入子进程。wine 看到的环境与终端会话完全无关，
 * 串扰问题（LD_PRELOAD 劫持、Termux lib 污染 Mesa 符号解析、残留
 * DISPLAY 连错 X 导致黑屏）从结构上根除。
 *
 * 变量清单与脚本 v2.3 基线逐字对齐（见各函数内注释）。
 */
object WineEnvironment {

    private val RFS_ROOT = ContainerManager.ROOTFS_ROOT
    private val RFS_USR = "$RFS_ROOT/usr"
    private val APP_PREFIX = ContainerManager.APP_PREFIX

    /**
     * v3.3.30：系统库直通目录（rootfs/usr/lib/linbox-syslibs）。
     *
     * 根因（用户 2026-11-09 18:25 会话日志实证）：rootfs 的
     * libandroid-stub 代理桩 libbinder_ndk.so（pulseaudio 依赖
     * libOpenSLES 代理而随包进 rootfs；构建无 -Wl,-soname → 无
     * DT_SONAME；App targetSdk>=23 时 bionic 不做 basename 回退 →
     * soinfo soname 为空）在 LD_LIBRARY_PATH 里先于 /system/lib64 ——
     * wine 进程 dlopen wrapper 栈 ICD 时，系统 libnativewindow.so 的
     * verneed 子依赖按 soname "libbinder_ndk.so" 匹配到桩（soname 空）
     * → bionic VersionTracker::init_verneed 报 "cannot find
     * libbinder_ndk.so from verneed[2] in DT_NEEDED list for
     * /system/lib64/libnativewindow.so" → ICD 被忽略 → "Found no
     * drivers!" → DXVK 报 VK_KHR_surface not supported。
     *
     * 修法：直通目录内放指向 /system/lib64 真身的 symlink 并前插
     * LD_LIBRARY_PATH（真身带正确 soname + verdef，verneed 闭环）；
     * rootfs 桩原位保留（pulseaudio 的 OpenSLES 代理链不受影响）。
     * linbox-wine 脚本 ensure_ndk_deps() 同款；本函数保证 App 直传
     * env 的链路（X11 / 预检 / 未来直 exec）也正确。
     *
     * @return 直通目录路径；非 aarch64 或系统库缺失时 null（零动作）。
     */
    private fun ensureSyslibPassthrough(): String? {
        if (!File("/system/lib64/libbinder_ndk.so").isFile) return null
        val dir = File("$RFS_USR/lib/linbox-syslibs")
        val names = listOf(
            "libbinder_ndk.so", "libnativewindow.so", "libhardware.so",
            "libsync.so", "libcutils.so"
        )
        var made = 0
        if (dir.isDirectory || dir.mkdirs()) {
            for (n in names) {
                val target = File("/system/lib64/$n")
                if (!target.isFile) continue
                val link = File(dir, n)
                // v3.3.40【性能】：链接就位且指向不变 → 零动作（旧版每次
                // 启动 delete + createSymbolicLink 重做 5 个系统库链接 ——
                // 文件系统写放大；OTA 换系统分区内容不改变链接目标路径，
                // 指向比对已覆盖真正的失效场景）
                val linkPath = link.toPath()
                val targetPath = java.nio.file.Paths.get("/system/lib64/$n")
                val healthy = java.nio.file.Files.isSymbolicLink(linkPath) &&
                    runCatching {
                        java.nio.file.Files.readSymbolicLink(linkPath) == targetPath
                    }.getOrDefault(false)
                if (healthy) { made++; continue }
                runCatching {
                    if (link.exists() || link.isSymlinkSafe()) link.delete()
                    java.nio.file.Files.createSymbolicLink(linkPath, targetPath)
                    made++
                }
            }
        }
        return if (made > 0) dir.absolutePath else null
    }

    /** File 符号链接存在性判断（不跟随目标；ls -l 语义）。 */
    private fun File.isSymlinkSafe(): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(toPath())
    }.getOrDefault(false)

    /** 容器运行的家目录（rootfs home，与脚本一致）。 */
    val HOME_DIR = File("$RFS_ROOT/home")

    /** XDG_RUNTIME_DIR / TMPDIR（rootfs 自管 tmp）。 */
    val RUNTIME_DIR = File("$RFS_USR/tmp")

    // ------------------------------------------------------------------
    // 1) 基础环境（isolate_env 移植）
    // ------------------------------------------------------------------

    /**
     * 构建 rootfs 基础环境（不含 WINEPREFIX / 音频 / GPU / box64 ——
     * 那些由 [RootfsLauncher] 在链路对应步骤叠加）。
     */
    fun buildBaseEnv(container: ContainerData, wineDir: File): Map<String, String> {
        val env = LinkedHashMap<String, String>()

        // --- 前缀 / 临时目录 / 家目录 → rootfs 自己的一套（isolate_env §2） ---
        env["PREFIX"] = RFS_USR
        env["TMPDIR"] = RUNTIME_DIR.absolutePath
        env["HOME"] = HOME_DIR.absolutePath
        env["XDG_RUNTIME_DIR"] = RUNTIME_DIR.absolutePath
        env["XDG_CONFIG_HOME"] = "$RFS_ROOT/home/.config"
        env["XDG_CACHE_HOME"] = "$RFS_ROOT/home/.cache"
        env["XDG_DATA_HOME"] = "$RFS_ROOT/home/.local/share"

        // --- 语言（§3）：容器显式语言 > auto 档读 /opt/locale.conf > zh_CN.UTF-8
        //     （v3.3.4：默认档 = 简体中文；跟随 /opt/locale.conf 仅在显式
        //      选择 auto 档时生效，作为选项保留）
        //     （v3.3.2 恢复 container.lang —— v3.2.29 曾移除语言选择项，
        //     用户反馈"语言设置你给去掉了？那个更改后可以更改启动配置
        //     语言"）。startwine.sh 参考链路 `export LC_ALL="$(cat
        //     /opt/locale.conf)"`：auto 档同语义取 rootfs/opt/locale.conf
        //     首行；显式档（简体中文/繁體/English/日本語/한국어）LANG 与
        //     LC_ALL 同用容器值。注意：bionic 无 locale 数据，libc 的
        //     setlocale 对 zh_CN.UTF-8 失效 —— wine 界面语言由 ntdll 的
        //     locale 解析决定，0009-android-locale-lc-all 补丁（用户
        //     wine 10.5 参考树同款）让 __ANDROID__ 下直接读 LC_ALL，
        //     本变量的值从此真正决定 wine 的 LCID/界面语言/ACP。
        //     conf/*.conf 若显式 export LANG/LC_ALL，由 applyOptConfEnv
        //     通用逻辑再覆盖；容器 env= 仍最高。
        // v3.3.4（用户问题 4："默认不要跟随 opt/locale，默认简体中文即可，
        // 跟随 opt/locale 作为一个选项"）：三档判定 ——
        //   显式语言档（简体中文/English/…）→ 直接用它；
        //   auto（显式选择"跟随 /opt/locale.conf"）→ 读生态包 locale.conf，
        //     无则兜底 zh_CN.UTF-8；
        //   其余（缺 lang= 键的旧容器 / 非法值）→ 一律 zh_CN.UTF-8，
        //     不再隐式跟随 /opt/locale.conf。
        val lang = when {
            container.lang == ContainerLangs.AUTO -> readOptLocale(wineDir) ?: "zh_CN.UTF-8"
            container.lang in ContainerLangs.CHOICES -> container.lang
            else -> "zh_CN.UTF-8"
        }
        env["LANG"] = lang
        env["LC_ALL"] = lang

        // --- 动态库搜索路径 → rootfs + Android 系统（§4） ---
        // v3.3.28：末位补 /system/lib64 —— 上游参考（cmod_bionic
        // BionicProgramLauncherComponent）同款：`LD_LIBRARY_PATH =
        // rootDir/usr/lib : /system/lib64`。wine 进程 dlopen 的链路里
        // 有一批库只在 Android 系统目录：wrapper 驱动栈的
        // libvulkan_wrapper.so DT_NEEDED libnativewindow.so、bionic turnip
        // 的 libhardware.so / libsync.so / libcutils.so —— Vulkan loader
        // 经 ICD json 的 library_path dlopen 驱动时按 LD_LIBRARY_PATH
        // 搜索，缺 /system/lib64 时这些库解析不到 → ICD 静默加载失败 →
        // DXVK 报 "VK_KHR_surface not supported"。
        // v3.3.30：首位前插“系统库直通”目录 —— 根因修复：rootfs 的
        // libandroid-stub 代理桩 libbinder_ndk.so（无 DT_SONAME，
        // targetSdk≥23 时 bionic 不做 basename 回退 → soname 为空）
        // 在 rootfs/usr/lib 里遮蔽 /system/lib64 真身，系统
        // libnativewindow.so 的 verneed 子依赖按 soname 匹配落空 →
        // bionic 报 "cannot find libbinder_ndk.so from verneed[2]" →
        // wrapper/yuzu 驱动 ICD dlopen 失败（用户 2026-11-09 日志实证）。
        // 直通目录放指向真身的 symlink，让真身（带 soname+verdef）
        // 赢得解析；linbox-wine 脚本 ensure_ndk_deps 同款（脚本侧才是
        // 运行时权威，此处保证 App 直传 env 的链路也正确）。
        val syslibsDir = ensureSyslibPassthrough()
        val lp = buildList {
            if (syslibsDir != null) add(syslibsDir)
            add("$RFS_USR/lib")
            if (File("$RFS_USR/local/lib").isDirectory) add("$RFS_USR/local/lib")
            if (File(wineDir, "lib").isDirectory) add("${wineDir.absolutePath}/lib")
            if (File("$RFS_USR/opt/lib").isDirectory) add("$RFS_USR/opt/lib")
            if (File("$RFS_USR/opt/fex/lib").isDirectory) add("$RFS_USR/opt/fex/lib")
            if (File("/system/lib64").isDirectory) add("/system/lib64")
        }
        env["LD_LIBRARY_PATH"] = lp.joinToString(":")

        // --- PATH → rootfs 优先，App 前缀仅兜底（§5；curl/zstd 等下载工具） ---
        env["PATH"] =
            "$RFS_USR/bin:$RFS_USR/local/bin:/system/bin:/system/xbin:$APP_PREFIX/bin"

        // --- X11：立即强制 :1（§6；不沿用继承值——残留空/错 DISPLAY 会让
        //     wine 连错地方 → 黑屏。LINBOX_DISPLAY 保留为覆盖口） ---
        val override = System.getenv("LINBOX_DISPLAY")
        env["DISPLAY"] = if (!override.isNullOrBlank()) override else ":1"

        // --- XKB / Mesa 基础（§7；rootfs 自带的一套） ---
        env["XKB_CONFIG_ROOT"] = "$RFS_USR/share/X11/xkb"
        env["MESA_SHADER_CACHE_DISABLE"] = "false"
        env["MESA_SHADER_CACHE_MAX_SIZE"] = "512MB"
        env["mesa_glthread"] = "true"
        // v3.2.13（用户问题 4：所有 wine 游戏画面移动一抖一抖）：撤销
        // aarch64 无条件注入的 TU_DEBUG=noconform,sysmem。
        //   * sysmem 强制 Turnip 把 buffer 对象落在系统内存（UW 内存路径
        //     绕过）—— GPU 访存带宽骤降，游戏滚屏/镜头移动持续掉帧、
        //     顿挫感明显，是"全游戏都卡"的系统性放大器；
        //   * noconform（关闭 Vulkan 一致性绕行）历史上用于规避旧 Turnip
        //     在 AHB 路径的崩溃 —— 仅在使用 Turnip（zink/驱动内 Vulkan）
        //     时才有意义，经典 freedreno GL 路径完全不读它。
        // 现改为：基础环境不再注入任何 TU_*；zink 档（applyGpuEnv，Turnip
        // 真正参与时）只补 noconform（稳定性兜底），sysmem 一律不设。
        // 用户 env= 仍可整体覆盖。

        // --- GStreamer（§8；插件路径显式指向 rootfs，注册表缓存 fork 在
        //     Android 上偶发卡死，直接关掉） ---
        if (File("$RFS_USR/lib/gstreamer-1.0").isDirectory) {
            env["GST_PLUGIN_SYSTEM_PATH"] = "$RFS_USR/lib/gstreamer-1.0"
            env["GST_REGISTRY_FORK"] = "false"
        }
        return env
    }

    // ------------------------------------------------------------------
    // 2) GPU / Mesa（apply_gpu_env 移植）
    // ------------------------------------------------------------------

    /** 依容器 gallium 配置注入 Mesa 渲染器变量。 */
    fun applyGpuEnv(env: MutableMap<String, String>, container: ContainerData) {
        // v3.1.6：两处修正 ——
        //   1. "llvmpipe" 一律归一为 "softpipe"（rootfs 的 aarch64/x86_64
        //      mesa 交叉构建均未包含 llvmpipe——不带 LLVM；GALLIUM_DRIVER
        //      指到未构建的驱动名 = GL 初始化直接失败，等宽于"无 GL"，
        //      wined3d 枚举不到任何适配器 → "无兼容 Direct3D 设备"）；
        //   2. auto 档不再盲选 freedreno：freedreno（GLES/DRM 渲染节点）
        //      依赖 /dev/dri 可访问，Android 应用沙箱在相当多设备/ROM 上
        //      无权打开 → GL 整体初始化失败。auto 现在先探 /dev/dri
        //      渲染节点可达性，不可达回退 softpipe + LIBGL_ALWAYS_SOFTWARE
        //      （D3D 样例/茶壶类程序从此必出画面，慢但活）。
        // v3.2.13（用户问题 4，画面抖动专项）：
        //   * zink 档 TU_DEBUG 只保留 noconform（崩溃规避），不再带 sysmem
        //     （系统内存强制 —— GPU 带宽腰斩，滚屏/移动持续掉帧的直接
        //     元凶，见 buildBaseEnv §7 注释）；
        //   * softpipe 兜底档补 vblank_mode=0：软渲染帧率本就低于屏幕
        //     刷新率，GLX 交换再等 vblank 只会引入 0~1 帧的随机延迟，
        //     表现为"移动一抖一抖"；关闭后按最快节奏上屏，节奏均匀。
        val raw = if (container.gallium.equals("auto", true) || container.gallium.isBlank())
            "auto" else container.gallium.trim().lowercase()
        val gallium = when (raw) {
            "llvmpipe" -> "softpipe"
            // v3.4.1【A1 修正·分层探测】（回滚 v3.4.0 的“一刀切 zink”）：
            // 用户实测 v3.4.0 “性能更差了” —— 有 DRM msm 渲染节点权限的
            // 设备上，v3.4.0 前的 freedreno 原生 GL（免 Vulkan 翻译层）本就
            // 更快，一刀切 zink 反而降速。分层语义（每层都只比旧版更强）：
            //   ① dri+msm（Adreno DRM 可达）→ freedreno 原生 GL
            //     （v3.4.0 前的原生速度，dri 设备回归）；
            //   ② kgsl / Vulkan ICD 可达 → zink+turnip（无 dri 权限设备的
            //     GPU 加速主路 —— v3.4.0 A1 的本意：旧版这种情况回落
            //     softpipe 纯软渲染，“不到 proot 一半”的灾难级根源）；
            //   ③ 泛 dri → freedreno（v3.4.0 前旧语义兜底）；
            //   ④ softpipe 兜底。
            "auto" -> when {
                isAarch64() && hasMsmDrmAccess() -> "freedreno"
                isAarch64() && hasVulkanIcd() -> "zink"
                isAarch64() && hasDrmAccess() -> "freedreno"
                else -> "softpipe"
            }
            else -> raw
        }
        when (gallium) {
            "zink" -> {
                env["GALLIUM_DRIVER"] = "zink"
                env["ZINK_DESCRIPTORS"] = "lazy"
                env["ZINK_DEBUG"] = "compact"
                // Turnip 崩溃规避仅此一项（sysmem 已撤销，见上）
                env.putIfAbsent("TU_DEBUG", "noconform")
                // v3.4.1【回滚 v3.4.0】：恢复 LIBGL_KOPPER_DISABLE=true
                //   （putIfAbsent —— 用户 env= 仍可覆盖改开）。v3.4.0 曾撤销
                //   此变量，猜想 kopper 的 GLX 直呈现更快 —— 但 LinBox 的
                //   X 服务（termux-x11/libXlorie 软渲染 X server）没有 DRI3
                //   呈现通路，kopper 自建 swapchain 每帧反而多一次回读慢
                //   路径（上游参考 XServerDisplayActivity 同款禁用语义，
                //   用户参考源码保持一致）。GL 命令流依旧全速经 Vulkan →
                //   GPU，只是不走 kopper 的 X 呈现支路。
                env.putIfAbsent("LIBGL_KOPPER_DISABLE", "true")
            }
            "freedreno" -> {
                env["GALLIUM_DRIVER"] = "freedreno"
            }
            else -> {
                // softpipe（含旧 llvmpipe 归一值）—— 软件渲染节奏配套
                env["GALLIUM_DRIVER"] = "softpipe"
                env["LIBGL_ALWAYS_SOFTWARE"] = "1"
                env.putIfAbsent("vblank_mode", "0")
            }
        }
    }

    /** v3.1.6：/dev/dri 渲染节点可达性探测（freedreno/zink 的 auto 档依据）。 */
    private fun hasDrmAccess(): Boolean = try {
        File("/dev/dri/renderD128").canRead() ||
            File("/dev/dri/card0").canRead() ||
            File("/dev/kgsl-3d0").canRead()
    } catch (_: Exception) {
        false
    }

    /**
     * v3.4.0 A1：Vulkan ICD 可达性探测 —— rootfs 自带 Mesa Turnip 的
     * freedreno_icd.aarch64.json（或任意已装用户槽位 ICD）存在即视为
     * Vulkan 可用。kgsl 路径（/dev/kgsl-3d0）无需 /dev/dri 权限，Adreno
     * 设备上 zink+turnip 开箱即达；Mali 设备 rootfs ICD 虽在但 turnip
     * 建不出设备 —— auto 档会在运行失败时由用户显式改 freedreno/softpipe。
     */
    /**
     * v3.4.1 A1：Adreno DRM 渲染节点探测（auto 档 freedreno 优先的依据）。
     *
     * /sys/class/drm 下 renderD 开头节点的 device/driver 符号链接名为
     * msm 或 adreno = Adreno DRM 可达 —— freedreno 原生 GL 免 Vulkan
     * 翻译层，GL 游戏通常快于 zink；有 dri 权限的设备（root / 宽松 ROM）
     * 由 auto 档回归 v3.4.0 前的原生速度。读不到 sysfs（无 DRM 设备 /
     * 节点）返回 false → auto 走 zink/softpipe 分支。
     */
    private fun hasMsmDrmAccess(): Boolean = try {
        val drm = File("/sys/class/drm")
        drm.listFiles { f -> f.name.startsWith("renderD") }?.any { node ->
            runCatching {
                val name = File(node, "device/driver").canonicalFile.name
                name == "msm" || name == "adreno"
            }.getOrDefault(false)
        } == true
    } catch (_: Exception) {
        false
    }

    private fun hasVulkanIcd(): Boolean = try {
        val icdRoot = File("$RFS_USR/share/vulkan/icd.d")
        (icdRoot.isDirectory &&
            icdRoot.listFiles { f -> f.name.endsWith(".json") }?.isNotEmpty() == true) ||
            hasDrmAccess()
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------
    // 3) Box64（apply_box64_env 移植 + v3.1.4 自包含依赖库路径）
    // ------------------------------------------------------------------

    /**
     * box64 / fexcore 后端的 DYNAREC 与运行库变量。
     *
     * v3.1.4：BOX64_LD_LIBRARY_PATH 前插 wine 发行包自带的 x86_64-bionic
     * 依赖库目录（`<wineDir>/lib/x86_64-bionic`，CI 打包步骤 8.5a 注入）——
     * aarch64 rootfs 只有 aarch64 库，x86_64 形态 wine 的 unix .so（及其
     * DT_NEEDED：libpulse/libasound/libX11/libandroid-shmem/gstreamer…）
     * 由 box64 仿真加载，库文件只能从这个私有目录找，缺它 wine 直接
     * "Symbol ... not found" 起不来。gstreamer 插件路径同步指向包内
     * x86_64 版（rootfs 里的插件是 aarch64，x86_64 libgstreamer 加载不了）。
     */
    fun applyBox64Env(
        env: MutableMap<String, String>,
        container: ContainerData,
        wineDir: File,
        // v3.4.3：预设 JSON 装载需要 Context（assets）；null = 跳过 JSON
        // 走旧内联表（兼容旧调用点）。
        context: Context? = null,
        log: (String) -> Unit = {}
    ) {
        // v3.2.24（用户问题 3，对齐 startwine.sh / startfex.sh）：
        //   export BOX64_PATH=$WINE_PATH/bin
        //   export BOX64_LD_LIBRARY_PATH=$WINEPATH/lib/wine/i386-unix:$WINEPATH/lib/wine/x86_64-unix
        // v3.3.38【性能】：BOX64_LOG 默认回 0。v3.2.13 由 0 改 1 是
        // wineboot 崩溃排查期的诊断遗留 —— LOG=1 时 box64 对每个
        // wrap/emulate 库加载都打 stderr，wine 每次拉起子进程
        // （wineserver 握手、regedit、游戏内 CreateProcess）都重刷一遍，
        // 日志管道 IO + 格式化白白吃启动 CPU；崩溃根因（RELR/SAFEFLAGS）
        // 已由构建补丁与档位定稿根治，error 级可诊断性由会话日志兜底。
        // 排障需要时 opt conf / 容器 env= 设 BOX64_LOG=1 即可（此处
        // putIfAbsent，conf 在链路后段可覆盖）。
        env.putIfAbsent("BOX64_LOG", "0")
        env["BOX64_NOBANNER"] = "1"
        env["BOX64_DYNAREC_TRACE"] = "0"
        // v3.2.13：BOX64_ENVFILE —— 让 rootfs 的 /usr/etc/box64.box64rc
        // 真正生效。上游 box64（v0.4.4 env.c InitializeEnvFiles）只读
        // /etc/box64.box64rc 或 /data/data/com.termux/... 两个内置路径，
        // rootfs 内 etc/box64.box64rc（com.linbox 路径）天然读不到 =
        // 系统级调优开关一直是死文件。BOX64_ENVFILE 是上游官方入口
        // （InitializeEnvFiles 首优先级），存在即注入；用户 env= 可覆盖。
        val box64rc = File("$RFS_USR/etc/box64.box64rc")
        if (box64rc.isFile && !container.env.contains("BOX64_ENVFILE=")) {
            env["BOX64_ENVFILE"] = box64rc.absolutePath
        }
        // v3.2.24（用户问题 3）：BOX64_PATH —— wine 槽位自身的 bin 目录。
        // 未注入前：wine（box64 仿真）内 exec 子进程（wineserver/wineboot/
        // 安装器 exe 再拉起的 x86_64 子进程）时，box64 只能在 PATH 里找
        // x86_64 目标；而 $WINE_DIR/bin 不在 PATH —— 参考脚本
        // startwine.sh/startfex.sh 的同款导出，这是参考链路能跑、
        // LinBox 链路部分场景子进程拉不起的差异点之一。
        // 注入后：box64 对仿真进程的 exec 直接在 wine 自己的 bin 里找，
        // 与上游同类项目的 BOX64_PATH 用法一致。仅目录存在时写入。
        val wineBinDir = File(wineDir, "bin")
        if (wineBinDir.isDirectory) {
            env["BOX64_PATH"] = wineBinDir.absolutePath
        }
        // v3.2.24（用户问题 3）：BOX64_LD_LIBRARY_PATH 前插 wine 槽位
        // 自身的 unix 库目录（lib/wine/i386-unix + x86_64-unix，存在才加）。
        // startwine.sh：export BOX64_LD_LIBRARY_PATH=…/i386-unix:…/x86_64-unix ——
        // wine 的 unix 侧 .so（ntdll.so 背后的 unix 库、各 *.so 驱动）由
        // box64 仿真加载时，优先在 wine 自己的 lib/wine/<arch>-unix 里
        // 解析；旧链路只给了 x86_64-bionic 闭包目录，wine 自身 unix 库
        // 目录缺位（对齐参考脚本的第二差异点）。顺序：wine unix 目录 →
        // x86_64-bionic 闭包（下方）→ glibc 目录（下方）。
        val wineUnixLibDirs = listOf(
            File(wineDir, "lib/wine/i386-unix"),
            File(wineDir, "lib/wine/x86_64-unix")
        ).filter { it.isDirectory }
        if (wineUnixLibDirs.isNotEmpty()) {
            val existing0 = env["BOX64_LD_LIBRARY_PATH"]
            val prefix0 = wineUnixLibDirs.joinToString(":") { it.absolutePath }
            env["BOX64_LD_LIBRARY_PATH"] = if (existing0.isNullOrBlank()) prefix0
            else "$prefix0:$existing0"
        }
        // wine 包自带的 x86_64-bionic 依赖闭包（存在才追加；旧版包无此目录）
        val bionicLibDir = File(wineDir, "lib/x86_64-bionic")
        val bionicGstDir = File(bionicLibDir, "gstreamer-1.0")
        if (bionicLibDir.isDirectory) {
            env["BOX64_LD_LIBRARY_PATH"] = bionicLibDir.absolutePath +
                (env["BOX64_LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
            if (bionicGstDir.isDirectory) {
                // x86_64 gstreamer 只认 x86_64 插件；rootfs 的 aarch64 插件目录对它无效
                env["GST_PLUGIN_SYSTEM_PATH"] = bionicGstDir.absolutePath
            }
        }
        // x86_64 glibc 运行库（bionic-rootfs 的 glibc-x86_64 配方安装）：
        // 本 wine 是 bionic 链接不需要它；但容器内跑常规 glibc x86_64
        // Linux 程序时 box64 要在这些目录找 libc/ld-linux，缺它直接报
        // "cannot open shared object file"。仅目录存在时追加。
        val glibcDir = File("$RFS_USR/lib/x86_64-linux-gnu")
        if (glibcDir.isDirectory) {
            val existing = env["BOX64_LD_LIBRARY_PATH"]
            env["BOX64_LD_LIBRARY_PATH"] = if (existing.isNullOrBlank()) {
                glibcDir.absolutePath
            } else {
                "${glibcDir.absolutePath}:$existing"
            }
        }
        // v3.3.37（用户：“性能问题根本没有解决，甚至还不如 proot”）：
        // 四档全面对齐上游参考 Box86_64PresetManager.java:23-95 参考值
        // （同代 Adreno 硬件 + 同代 box64 实战验证的档位表）：
        //   * PERFORMANCE：BIGBLOCK 1→3、FORWARD 128→512、X87DOUBLE 1→0、
        //     +MMAP32=1 —— 与参考实现逐字一致，这是 32 位游戏 dynarec
        //     吞吐差距的主供应商（旧值“性能差异微小”的判断源自 v3.3.21
        //     随机崩溃时代的保守化，当年崩溃真因另在 RELR/地址布局）；
        //   * SAFEFLAGS 保持全档 0：用户设备实测（Adreno 740 / Android 15）
        //     1/2 触发 ntdll 早期 SIGSEGV（v3.3.21 定稿，不随参考值回改）；
        //   * 新增恒定项：BOX64_DYNAREC=1（显式）、BOX64_X11GLX=1（参考
        //     实现同款：GLX 经 box64 桥接到宿主 X11 —— 缺它 GLX 走死路）、
        //     BOX64_MMAP32 仅性能档（参考同款，配合 WRAPPER_DISABLE_PLACED
        //     由 wrapper 栈侧自行消费）。
        env.putIfAbsent("BOX64_DYNAREC", "1")
        env.putIfAbsent("BOX64_X11GLX", "1")
        // v3.4.3【box64 预设 JSON 对齐】：四档 BOX64_* 改由 assets
        // builtin/box64_presets.json 派生（用户提供的 assets-ck 资产，
        // 上游发行包 LaunchPlan.buildEnv:237-242 经 Box64PresetManager
        // 展开的同一张表 —— x86_64 wine 线的调用与性能对齐）。旧表每档
        // 9 项手抄值；JSON 表 11 项补齐 BOX64_AVX 与 BOX64_UNITYPLAYER
        //（稳定/兼容档 1，进阶/性能档 0；容器 UNITY 勾选的 applyTuningEnv
        // 在链路后段直接赋值，仍最高优）。两处 LinBox 定稿覆写保留：
        //  - SAFEFLAGS 恒 0（v3.3.21 用户设备定稿：Adreno 740 /
        //    Android 15 实测 1/2 触发 ntdll 早期 SIGSEGV —— JSON 档位值
        //    2/2/2/1 不适用本机；flags 保守度对性能影响微小，稳定性优先）；
        //  - PERFORMANCE 档补 BOX64_MMAP32=1（上游同款）。
        // 资产缺席回落下方旧内联表（打包错误兜底，行为不劣于 v3.4.2）。
        val bvars = presetEnvVars(
            context, "builtin/box64_presets.json", log
        )[container.preset.name.lowercase()]
        if (!bvars.isNullOrEmpty()) {
            bvars.forEach { (k, v) -> env.putIfAbsent(k, v) }
            env["BOX64_DYNAREC_SAFEFLAGS"] = "0"
            if (container.preset == Box64Preset.PERFORMANCE) {
                env.putIfAbsent("BOX64_MMAP32", "1")
            }
            // 上游发行包 x86_64 线同款（LaunchPlan.buildEnv:236）：优先仿真
            // 库，避免 rootfs/系统原生同名库混载不可预期
            env.putIfAbsent("BOX64_PREFER_EMULATED", "1")
            log(
                "box64 预设 JSON·${container.preset.name.lowercase()} 档生效：" +
                    "${bvars.size} 项 BOX64_* 完整注入（+MMAP32/PREFER_EMULATED；" +
                    "SAFEFLAGS=0 设备定稿覆写；opt conf / 容器 env= 仍可逐项覆盖）"
            )
        } else when (container.preset) {
            Box64Preset.STABILITY -> {
                env["BOX64_DYNAREC_SAFEFLAGS"] = "0"
                env["BOX64_DYNAREC_FASTNAN"] = "0"
                env["BOX64_DYNAREC_FASTROUND"] = "0"
                env["BOX64_DYNAREC_X87DOUBLE"] = "1"
                env["BOX64_DYNAREC_BIGBLOCK"] = "0"
                env["BOX64_DYNAREC_STRONGMEM"] = "2"
                env["BOX64_DYNAREC_FORWARD"] = "128"
                env["BOX64_DYNAREC_CALLRET"] = "0"
                env["BOX64_DYNAREC_WAIT"] = "0"
            }
            Box64Preset.COMPATIBILITY -> {
                env["BOX64_DYNAREC_BIGBLOCK"] = "0"
                env["BOX64_DYNAREC_SAFEFLAGS"] = "0"
                env["BOX64_DYNAREC_FASTNAN"] = "0"
                env["BOX64_DYNAREC_FASTROUND"] = "0"
                env["BOX64_DYNAREC_X87DOUBLE"] = "1"
                env["BOX64_DYNAREC_STRONGMEM"] = "1"
                env["BOX64_DYNAREC_FORWARD"] = "128"
                env["BOX64_DYNAREC_CALLRET"] = "0"
                env["BOX64_DYNAREC_WAIT"] = "1"
            }
            Box64Preset.INTERMEDIATE -> {
                env["BOX64_DYNAREC_BIGBLOCK"] = "1"
                env["BOX64_DYNAREC_SAFEFLAGS"] = "0"
                env["BOX64_DYNAREC_FASTNAN"] = "1"
                env["BOX64_DYNAREC_FASTROUND"] = "0"
                env["BOX64_DYNAREC_X87DOUBLE"] = "1"
                env["BOX64_DYNAREC_STRONGMEM"] = "0"
                env["BOX64_DYNAREC_FORWARD"] = "128"
                env["BOX64_DYNAREC_CALLRET"] = "1"
                env["BOX64_DYNAREC_WAIT"] = "1"
            }
            Box64Preset.PERFORMANCE -> {
                // v3.3.37：上游参考 PERFORMANCE 档逐字对齐（仅 SAFEFLAGS
                // 按用户设备实测保持 0）。
                env["BOX64_DYNAREC_BIGBLOCK"] = "3"
                env["BOX64_DYNAREC_SAFEFLAGS"] = "0"
                env["BOX64_DYNAREC_FASTNAN"] = "1"
                env["BOX64_DYNAREC_FASTROUND"] = "1"
                env["BOX64_DYNAREC_X87DOUBLE"] = "0"
                env["BOX64_DYNAREC_STRONGMEM"] = "0"
                env["BOX64_DYNAREC_FORWARD"] = "512"
                env["BOX64_DYNAREC_CALLRET"] = "1"
                env["BOX64_DYNAREC_WAIT"] = "1"
                env["BOX64_MMAP32"] = "1"
            }
        }
    }

    // ------------------------------------------------------------------
    // 3b) Android 安全默认 + arm64ec 仿真器 DLL（v3.1.4）
    // ------------------------------------------------------------------

    /**
     * v3.3.37：全局性能基线（上游参考 DefaultVersion.ENV_VARS 容器
     * 出厂调优串的全量移植，box64 / arm64ec 两条线共用）。
     *
     * 用户原话：“性能问题根本没有解决，甚至还不如 proot 容器运行 wine
     * 的性能，完全解析参考源码的性能调用以及所有配置”。参考实现
     * （上游参考 core/DefaultVersion.java:41-44 + XServerDisplayActivity
     * :1663-1675/:2431-2441 + DXVKConfigDialog:152-194）注入而我们缺的：
     *  * MESA 侧（闪屏/掉帧双杀）：
     *    - MESA_VK_WSI_PRESENT_MODE=mailbox —— 交换链呈现模式走 mailbox
     *      （防撕裂 + 低延迟；参考实现无条件注入，正是“仙剑四一直闪屏”
     *      类问题的标准对策）；
     *    - MESA_NO_ERROR=1 / MESA_DEBUG=silent —— GL 错误检查关、
     *      调试输出静默（驱动级开销削减）；
     *    - ZINK_DESCRIPTORS=lazy / ZINK_DEBUG=compact —— zink 描述符惰性
     *      分配 + 紧凑位掩码（zink 档额外收益，zink 联动处已设，此处
     *      putIfAbsent 兜底防覆盖链被 conf 顶掉后裸奔）。
     *  * DXVK 侧（arm64ec 仙剑四 DXVK 路径的关键配套）：
     *    - DXVK_DISABLE_TIMELINE_SEMAPHORES=1（Android timeline semaphore
     *      兼容 + 开销削减，参考默认串原样）；
     *    - DXVK_ASYNC=1 + DXVK_GPLASYNCCACHE=1 —— GPL 异步管线编译，
     *      老 D3D9 游戏卡顿的主削减项（参考 dxwrapper 默认 async=1）；
     *    - DXVK_STATE_CACHE_PATH → rootfs home/.cache（状态缓存持久化，
     *      重启游戏不再全量重编译着色器）；
     *    - DXVK_LOG_LEVEL=none / DXVK_HUD=0 —— 零日志开销。
     *  * wine 行为（BionicProgramLauncherComponent:294-308 同款）：
     *    - WINE_GST_NO_GL=1（gstreamer 解码路径免 GL 争用）；
     *    - GST_PLUGIN_FEATURE_RANK 提升视频解码器优先级；
     *    - WINE_NO_DUPLICATE_EXPLORER=1 / WINE_DISABLE_FULLSCREEN_HACK=1。
     *  * WINEDEBUG=-all：wine fixme/err 洪流是旧游戏肉眼级帧率损耗
     *    （每帧数百行 stderr 落日志管道）—— 会话日志已有进程级输出
     *    兜底，wine 自身通道全静默。
     *
     * 优先级：全部 putIfAbsent —— opt conf（applyOptConfEnv 在后）与
     * 容器 env=（applyExtraEnv 在最后）均可逐项覆盖。
     */
    fun applyPerfBaselineEnv(env: MutableMap<String, String>, log: (String) -> Unit = {}) {
        // Mesa / 呈现
        env.putIfAbsent("MESA_VK_WSI_PRESENT_MODE", "mailbox")
        env.putIfAbsent("MESA_NO_ERROR", "1")
        env.putIfAbsent("MESA_DEBUG", "silent")
        env.putIfAbsent("ZINK_DESCRIPTORS", "lazy")
        env.putIfAbsent("ZINK_DEBUG", "compact")
        // DXVK
        env.putIfAbsent("DXVK_DISABLE_TIMELINE_SEMAPHORES", "1")
        env.putIfAbsent("DXVK_ASYNC", "1")
        env.putIfAbsent("DXVK_GPLASYNCCACHE", "1")
        env.putIfAbsent("DXVK_LOG_LEVEL", "none")
        env.putIfAbsent("DXVK_HUD", "0")
        val cacheDir = File("$RFS_ROOT/home/.cache/dxvk-state")
        runCatching { cacheDir.mkdirs() }
        env.putIfAbsent("DXVK_STATE_CACHE_PATH", cacheDir.absolutePath)
        // wine 行为
        env.putIfAbsent("WINEDEBUG", "-all")
        env.putIfAbsent("WINE_GST_NO_GL", "1")
        env.putIfAbsent("WINE_NO_DUPLICATE_EXPLORER", "1")
        env.putIfAbsent("WINE_DISABLE_FULLSCREEN_HACK", "1")
        env.putIfAbsent(
            "GST_PLUGIN_FEATURE_RANK",
            "ximagesink:3000,avdec_wmav1:512,avdec_wmav2:512,avdec_wmapro:512"
        )
        log(
            "性能基线（容器调优串）：mailbox 呈现 + GL NO_ERROR + " +
                "DXVK 异步编译/状态缓存 + WINEDEBUG=-all（opt conf / 容器 env= 可逐项覆盖）"
        )
    }

    /**
     * v3.3.37：FEX 运行时配置文件（上游参考 FEXCoreManager.java:110-118/
     * 241-243 同款机制 —— libwow64fex/libarm64ecfex 从
     * `$HOME/.fex-emu/Config.json` 读配置，这是参考实现【实证有效】的
     * 通道；FEX_* 环境变量在部分 FEX 构建上不生效，env 保留作次级兜底）。
     *
     * 按容器预设写（上游参考 四档语义）：
     *  * PERFORMANCE：TSO 全关（Fastest）+ X87ReducedPrecision=1（x87 64 位
     *    精算提速）+ Multiblock=1（超块内分支直通）—— 32 位游戏三件套；
     *  * INTERMEDIATE：TSO 全关 + Multiblock=1（x87 保持 80 位精算）；
     *  * COMPATIBILITY：TSOEnabled=1（标量 TSO 开，向量/REP-MOVS 保持关）；
     *  * STABILITY：TSOEnabled=1 + HalfBarrierTSOEnabled=1（最强 TSO 语义）。
     * + FEX_DISKCACHE=1（JIT 磁盘缓存，二次启动免重编译 —— 参考实现
     *   Proton11 容器默认）。
     *
     * HOME = rootfs/home（wine 进程家目录，全容器共享）；仅 arm64ec 线
     * 调用（HODLL 注入的 FEX DLL 才是消费者）。幂等：内容无变化不重写。
     */
    fun ensureFexConfigFile(
        env: MutableMap<String, String>,
        container: ContainerData,
        log: (String) -> Unit = {},
        context: Context? = null
    ) {
        // v3.4.3【根据 JSON 正确打】：presetDefaults 改由 assets
        // builtin/fex_presets.json 派生（用户提供的 assets-ck 资产，
        // 上游发行包 FEXPresetManager 同一张表 —— 四档各 15 项，含旧表
        // 缺失的 SMALLTSCSCALE/MAXINST 等）。资产缺席（打包错误）回落
        // 下方旧四档内联表，行为不劣于 v3.4.2。
        val presetVars = presetEnvVars(
            context, "builtin/fex_presets.json", log
        )[container.preset.name.lowercase()].orEmpty()
        // FEX 配置页 env 变量名 → Config.json 键（上游参考 FEXCoreManager
        // 实证键位；未映射的 FEX_*（HOSTFEATURES/SMC_CHECKS/MONOHACKS/
        // HIDEHYPERVISORBIT）仅 env 通道）。v3.4.3 +SmallTSCScale/
        // +VolatileMetadata（JSON 表有值，Config 键为 FEX 官方命名）。
        val fexTabMap = mapOf(
            "FEX_TSOENABLED" to "TSOEnabled",
            "FEX_VECTORTSOENABLED" to "VectorTSOEnabled",
            "FEX_HALFBARRIERTSOENABLED" to "HalfBarrierTSOEnabled",
            "FEX_MEMCPYSETTSOENABLED" to "MemcpySetTSOEnabled",
            "FEX_X87REDUCEDPRECISION" to "X87ReducedPrecision",
            "FEX_MULTIBLOCK" to "Multiblock",
            "FEX_DISABLEL2CACHE" to "DisableL2Cache",
            "FEX_DYNAMICL1CACHE" to "DynamicL1Cache",
            "FEX_MAXINST" to "MaxInst",
            "FEX_SMALLTSCSCALE" to "SmallTSCScale",
            "FEX_VOLATILEMETADATA" to "VolatileMetadata"
        )
        val presetDefaults = LinkedHashMap<String, String>()
        fexTabMap.forEach { (envName, cfgKey) ->
            presetVars[envName]?.let { presetDefaults[cfgKey] = it }
        }
        if (presetDefaults.isEmpty()) presetDefaults.putAll(
            when (container.preset) {
                Box64Preset.STABILITY -> mapOf(
                    "TSOEnabled" to "1", "HalfBarrierTSOEnabled" to "1",
                    "VectorTSOEnabled" to "0", "MemcpySetTSOEnabled" to "0",
                    "X87ReducedPrecision" to "0", "Multiblock" to "0"
                )
                Box64Preset.COMPATIBILITY -> mapOf(
                    "TSOEnabled" to "1", "HalfBarrierTSOEnabled" to "0",
                    "VectorTSOEnabled" to "0", "MemcpySetTSOEnabled" to "0",
                    "X87ReducedPrecision" to "0", "Multiblock" to "1"
                )
                Box64Preset.INTERMEDIATE -> mapOf(
                    "TSOEnabled" to "0", "HalfBarrierTSOEnabled" to "0",
                    "VectorTSOEnabled" to "0", "MemcpySetTSOEnabled" to "0",
                    "X87ReducedPrecision" to "0", "Multiblock" to "1"
                )
                Box64Preset.PERFORMANCE -> mapOf(
                    "TSOEnabled" to "0", "HalfBarrierTSOEnabled" to "0",
                    "VectorTSOEnabled" to "0", "MemcpySetTSOEnabled" to "0",
                    "X87ReducedPrecision" to "1", "Multiblock" to "1",
                    "DisableL2Cache" to "0", "DynamicL1Cache" to "0"
                )
            }
        )
        val active = runCatching { FexConfSupport.activeValues() }.getOrDefault(emptyMap())
        val conf = LinkedHashMap<String, String>(presetDefaults)
        fexTabMap.forEach { (envName, cfgKey) ->
            active[envName]?.takeIf { it.isNotBlank() }?.let { conf[cfgKey] = it }
        }
        val json = "{\"Config\":{" + conf.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" } + "}}"
        runCatching {
            val fexDir = File("${env["HOME"] ?: "$RFS_ROOT/home"}/.fex-emu")
            fexDir.mkdirs()
            val f = File(fexDir, "Config.json")
            if (!f.isFile || f.readText() != json) f.writeText(json)
            env.putIfAbsent("FEX_DISKCACHE", "1")
            val tabInUse = active.isNotEmpty()
            log(
                "FEX 配置: ~/.fex-emu/Config.json 已就绪（" +
                    (if (tabInUse) "FEX 配置页 fex_active.conf ${active.size} 项已并入" else "容器预设档四档语义") +
                    "；FEX_DISKCACHE=1；env 通道 FEX_* 同值双保险）"
            )
        }.onFailure { log("⚠ FEX 配置写入失败：${it.message}（不影响启动，env 侧档位仍生效）") }
    }

    /**
     * v3.3.37：DXVK 真激活（arm64ec 仙剑四“DXVK 直接失败”的核心修复）。
     *
     * 根因：v3.2.27 按用户要求移除了 APK 侧全部 DllOverrides（当时 WineLibrary
     * 无条件写表污染加载序）—— 但 wine 的默认 DLL 加载序是 builtin 优先，
     * DXVK DLL 解压进 drive_c/windows 后【从未被加载】：游戏拿到的仍是
     * 内置 wined3d。参考实现（上游参考 WineUtils.java:141-149 +
     * Container.java:27 默认 dxwrapper="dxvk"）的做法：**装 DXVK 即同步写
     * D3D/DXGI 全家 native,builtin**。
     *
     * 本函数在 dxvk 槽位激活（dxvk != off，含 auto 内置档）时经
     * WINEDLLOVERRIDES 注入（会话级、可逆、不碰注册表；与既有的
     * winedbg=d 合并；容器 dxoverrides 仍最高优）：
     *   d3d8/d3d9/d3d10/d3d10_1/d3d10core/d3d11/d3d12/d3d12core/
     *   ddraw/dxgi = native,builtin
     * 选 off（wined3d 兜底）时零注入 —— wine 默认加载序即 wined3d。
     */
    fun applyDxvkEnv(env: MutableMap<String, String>, container: ContainerData, log: (String) -> Unit = {}) {
        val dxvkActive = container.dxvk.isNotBlank() && container.dxvk != "off"
        if (!dxvkActive) {
            log("DXVK 未激活（dxvk=off → wined3d 内置路径，零覆盖注入）")
            return
        }
        val override = "d3d8,d3d9,d3d10,d3d10_1,d3d10core,d3d11,d3d12,d3d12core,ddraw,dxgi=native,builtin"
        val existing = env["WINEDLLOVERRIDES"]
        env["WINEDLLOVERRIDES"] = if (existing.isNullOrBlank()) override else "$override;$existing"
        log("DXVK 激活：D3D/DXGI 全家 native,builtin（v3.2.27 移除后 wine 默认序从未真正加载 DXVK —— 本行恢复其激活语义）")
    }

    /**
     * Android 沙箱兼容默认（v3.1.4）+ arm64ec 仿真器 DLL 选择。
     *
     * ① WINEESYNC/WINEFSYNC 默认 0：
     *    - fsync 的 futex_waitv 能力探测在 Android 上被 seccomp 以
     *      SIGSYS 击杀（构建侧已加 __ANDROID__ 守卫根治；此处环境默认
     *      再兜一层——旧版 wine 包没打守卫补丁时也能活）；
     *    - esync 依赖 /dev/shm 与 eventfd，Android 沙箱行为不可预期；
     *    - 两者对游戏兼容性收益为零（上游同类项目同款默认）。
     *    用户可在容器 env= 里显式覆盖（applyExtraEnv 在链路最后叠加）。
     *
     * ①b WINELOADERNOEXEC=1（v3.2.3，用户问题「proton 的 wine 都无法
     *    构建前缀」的运行期缓解；proton_10.0 loader.c:2690 源码核实）：
     *    - wine ≤10 树的 `__wine_main` 首遍逻辑是
     *      `if (!getenv("WINELOADERNOEXEC")) { check_command_line();
     *      if (pre_exec()) putenv("WINELOADERNOEXEC=1") → 经
     *      wine-preloader 重新执行一遍自身 }` —— 置 1 = 首进程直接跳过
     *      重执行（地址空间保留由 virtual_init 的无预加载器分支自完）。
     *    - 代价：该变量与 check_command_line（--help/--version 分发）
     *      同一守卫块 —— [WineRegistry.loaderSmokeTest] 已在测试前剥离，
     *      不受影响。
     *    - 子进程不经该变量（preloader_exec 不检查它）—— 子进程的
     *      预加载器风险由 [WineLayoutRepair] 停用 bin 目录下的
     *      `*-preloader` 文件根治（ENOENT 官方兜底直连 loader）；
     *      本变量让首进程先不走弯路。
     *
     * ①c（v3.2.8 二次勘误，恢复 v3.2.3 误删的变量）：重新导出
     *    WINE_DISABLE_KERNEL_WRITEWATCH=1。v3.2.3 的"勘误"声称全 wine
     *    源码均无此变量而将其移除 —— 该结论【只对 hangover-11.16 成立】
     *    （wine 11 删除了该变量、改为编译期 `!defined(__ANDROID__)` 排除）；
     *    proton_10.0 / wine-10.0 树的 virtual.c:3847 实测存在：
     *      if (!((env_var = getenv( "WINE_DISABLE_KERNEL_WRITEWATCH" )) && atoi( env_var )))
     *          kernel_writewatch_init();
     *    而 kernel_writewatch_init() 内的裸 syscall(__NR_userfaultfd)
     *    （aarch64 nr 374）不在 Android 应用 seccomp 白名单 → SIGSYS(31)
     *    → 退出码 159 —— 正是 proton arm64ec "打印一行 heap-tagging 后
     *    秒退 159"的真正根因（fsync 探测守卫早已生效，159 依旧）。
     *    本变量让 wine 10 系旧包【免重装免重编】跳过该探测；wine 11 树
     *    不读它，零影响。用户 env= 可覆盖（putIfAbsent 语义）。
     *
     * ② HODLL64/HODLL（仅 arm64ec 形态）—— v3.4.2【对齐参考源码】：
     *    【默认不注入任何 HODLL/HODLL64】。参考源码（上游发行包
     *    LaunchPlan.buildEnv:222-228）里 HODLL 是【用户容器设置项】
     *    （libwow64fex.dll / wowbox64.dll 二选一），从不按探测结果默认
     *    注入，也从【不设置 HODLL64】。LinBox 的 0010/0011 补丁后 wine
     *    无 HODLL 时的内部缺省就是 libwow64fex/libarm64ecfex
     *    （hangover 树自带自己的缺省）—— App 侧默认注入是纯冗余，
     *    而且有害：v3.3.34 的"默认 libwow64fex"、v3.4.0 A2 的
     *    "默认 wowbox64"（把 32 位游戏从 FEX 强切到 box64-in-wow64）
     *    都在用户未选择时改写了 32 位模拟层 —— 用户实测
     *    "性能更差了"的直接嫌疑源（本轮两轮反馈撤销）。
     *    现在注入恰是三处（fex 运行默认直接 wine exe，选择才前置）：
     *    ① 用户显式 env=/启动参数 HODLL=/HODLL64=（大小写归一，最高优先）；
     *    ② 勾选 FEX模式（用户在 UI 的 HODLL 选择）→ HODLL=libwow64fex.dll
     *      （dll 缺席则跳过 —— 注入不存在的 dll 会让 32 位进程死于
     *      c0000135，不如交给 wine 内部缺省）；
     *    ③ 勾选 wowbox64模式（v3.4.2 用户：「wowbox64 模式别取消，加入
     *      选项，部分 32 游戏需要」）→ HODLL=wowbox64.dll（box64-in-wow64
     *      32 位模拟层，dll 缺席同样跳过）。上游发行包 的 HODLL
     *      取值恰是这两个二选一（LaunchPlan.buildEnv:224-225：
     *      0=libwow64fex.dll，1=wowbox64.dll —— SettingKeys
     *      ContainerFEXHodll）—— LinBox 拆成两个勾选档，同勾时
     *      FEX模式 在前。
     *    - 【v3.3.34 大小写归一保留】wine 读【大写】HODLL/HODLL64 ——
     *      用户在 env= 或启动参数写小写 hodll= 的值经 extractEnvTokenIgnoreCase
     *      规范成大写键导出，不再"导出了但 wine 读不到"。
     *    【v3.2.10】official/proton 树已由 0010/0011 补丁移植了 HODLL/
     *    HODLL64 读取机制（对齐 hangover-11.16）：此前 vanilla 树根本不
     *    读这两个变量，xtajit(.64).dll 在 Linux 不存在 → 32 位 wow64 进程
     *    死于 c0000135（syswow64 无法生成）、EC 进程装载 x64 仿真器失败。
     *    本函数注入的变量【必须】在 wineboot 前缀构建会话保留（Wow64Install
     *    段靠 32 位 rundll32 执行，见 RootfsLauncher v3.2.10 注）。
     */
    fun applyWineCompatEnv(
        env: MutableMap<String, String>,
        arch: WineRuntime.Arch,
        wineDir: File,
        userEnv: String = "",
        // v3.4.2：FEX模式勾选 → 注入 HODLL=libwow64fex.dll；默认不注入
        //（wine 内部缺省接管），撤销 v3.4.0 A2 的「默认 wowbox64」（用户
        // 实测性能回退的直接嫌疑源）。
        // wowbox64模式勾选 → HODLL=wowbox64.dll（用户：「wowbox64 模式别
        // 取消，加入选项，部分 32 游戏需要」—— 上游发行包 ContainerFEXHodll
        // 的另一个取值，两个 UI 档同参考源码的一对取值）。
        fexMode: Boolean = false,
        wowboxMode: Boolean = false
    ) {
        // v3.3.37：WINEESYNC 0→1（对齐上游参考 XServerDisplayActivity:1748-1751
        // —— 未设即强制 1；同为 bionic 直跑环境，esync 是线程密集型 32 位
        // D3D9 游戏的主帧率供应商。WINEFSYNC 保持 0：futex_waitv 会被
        // Android seccomp 以 SIGSYS 击杀。fd 上限不够时 env= WINEESYNC=0
        // 一键回退（putIfAbsent 语义）。）
        env.putIfAbsent("WINEESYNC", "1")
        env.putIfAbsent("WINEFSYNC", "0")
        // v3.2.3：预加载器首进程规避（详见 KDoc ①b；子进程风险由
        // WineLayoutRepair 停用 bin/*-preloader + WineProbePatch 改写
        // futex_waitv 探测兕底，两者均免重编作用于旧包）
        env.putIfAbsent("WINELOADERNOEXEC", "1")
        // v3.2.8：userfaultfd 写监视探测的 Android seccomp SIGSYS(159)
        // 根治（详见 KDoc ①c）—— wine 10 系树（proton_10.0/wine-10.0）
        // 在 virtual_init 读取本变量，置 1 即跳过 kernel_writewatch_init
        // 的裸 syscall(__NR_userfaultfd)。wine 11 树不读此变量（编译期已
        // 排除），导出无副作用。
        env.putIfAbsent("WINE_DISABLE_KERNEL_WRITEWATCH", "1")
        // v3.3.35：winedbg 崩溃挂死修复（设备日志 wine-10-20261109 实证：
        // 仙剑四页错误 → wine 拉起 syswow64\winedbg.exe —— 调试器本身
        // 也是 32 位 wow64 进程、再经 FEX 转译，崩溃路径被拖长且弹调试
        // 窗口，用户视角"游戏卡死"而非"游戏崩溃"）。winedbg=d 置
        // disabled：未处理异常时 wine 打印异常行后进程直接退出。
        // applyExtraEnv 的 dxoverrides 前插 —— 用户在最前面写
        // winedbg=n,b 可恢复调试器。
        env.putIfAbsent("WINEDLLOVERRIDES", "winedbg=d")
        if (arch != WineRuntime.Arch.AARCH64) return
        // v3.4.2【HODLL 对齐参考源码·撤销全部默认注入】（用户两轮反馈：
        // 「把默认的 HODLL=libwow64fex.dll 去掉，fex 运行默认直接 wine exe
        //  的，只有我选择了 HODLL 才在前面加」；上游发行包
        // LaunchPlan.buildEnv:222-228 —— HODLL 是【用户容器设置项】，
        // 从不默认注入、从不设 HODLL64）：
        // ① 默认【不注入】HODLL/HODLL64 —— 0010/0011 补丁后 wine 内部缺省
        //    即 libwow64fex/libarm64ecfex（hangover 树自带缺省），App 侧
        //    注入纯冗余；v3.4.0 A2 的「默认 wowbox64」把 32 位游戏从 FEX
        //    强切 box64-in-wow64 —— 用户实测「性能更差了」的直接嫌疑源。
        // ② 大小写归一（v3.3.34 保留）：wine 的 get_cpu_dll_name /
        //    load_arm64ec_module 读【大写】HODLL/HODLL64 —— 用户写小写
        //    hodll= 的 token 经 extractEnvTokenIgnoreCase 规范成大写键导出，
        //    不再「导出了但 wine 读不到」。
        // ③ 恰三处注入（优先级：用户显式 > FEX模式 > wowbox64模式）：
        //    a. 用户显式 env=/启动参数 HODLL=/HODLL64=（最高优）；
        //    b. 勾选 FEX模式 → HODLL=libwow64fex.dll（dll 缺席跳过 —— 注入
        //       不存在的 dll 会让 32 位进程死于 c0000135，不如交给内部缺省）；
        //    c. 勾选 wowbox64模式（用户：「wowbox64 模式别取消，加入选项，
        //       部分 32 游戏需要」）→ HODLL=wowbox64.dll（box64-in-wow64
        //       32 位模拟层，dll 缺席同样跳过）。上游发行包 的 HODLL
        //       取值恰是这两个二选一（LaunchPlan.buildEnv:224-225 /
        //       WineConfigure.fexDllsInstalled:356-358：0=libwow64fex.dll，
        //       1=wowbox64.dll —— SettingKeys.ContainerFEXHodll）—— LinBox
        //       拆成两个勾选档，同勾时 FEX模式 在前。
        val userHodll64 = extractEnvTokenIgnoreCase(userEnv, "HODLL64")
        val userHodll = extractEnvTokenIgnoreCase(userEnv, "HODLL")
        userHodll64?.let { env["HODLL64"] = it }
        if (userHodll != null) {
            env["HODLL"] = userHodll
        } else if (fexMode && probeEmulatorDll(wineDir, "libwow64fex.dll")) {
            env["HODLL"] = "libwow64fex.dll"
        } else if (wowboxMode && probeEmulatorDll(wineDir, "wowbox64.dll")) {
            env["HODLL"] = "wowbox64.dll"
        } else if (!fexMode && !wowboxMode &&
            !probeEmulatorDll(wineDir, "libwow64fex.dll") &&
            probeEmulatorDll(wineDir, "wowbox64.dll")
        ) {
            // v3.4.7【32 位闪退兜底】（用户 2026-11-11：“有些 32 游戏不用
            // wowbox64 却闪退了”）：两档都未勾选时此前完全靠 wine 内部缺省
            // —— 而 0010/0011 补丁树的内部缺省就是 libwow64fex.dll；FEX
            // 栈未随包发行（fexcore 未安装）时该 dll 在包内缺席 → 32 位
            // wow64 进程装载器找不到 x86 仿真 dll → 死于 c0000135 秒退。
            // 本兜底只在“libwow64fex 缺席 + wowbox64 在场”时注入后者
            //（纯可用性判断，不改用户勾选语义；用户勾选/env= 仍最高优，
            // libwow64fex 在场时维持 wine 内部缺省不受影响）。
            env["HODLL"] = "wowbox64.dll"
        }
    }

    /**
     * v3.3.34：env 字符串里大小写不敏感地取 KEY=VALUE 的值。
     * 键边界精确匹配（HODLL 不会命中 HODLL64 的 token）；无则 null。
     * 支持空白切分的多 token（容器 env= 与启动参数提取共用一套语义）。
     */
    internal fun extractEnvTokenIgnoreCase(userEnv: String, key: String): String? {
        val re = Regex("(?i)^${Regex.escape(key)}=(.+)$")
        return userEnv.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { token -> re.matchEntire(token)?.groupValues?.get(1) }
    }

    /**
     * v3.3.34：仿真器 PE DLL 探测（多 wine 布局直通）。
     * aarch64-windows（hangover-wine 平铺主位）→ arm64ec-windows →
     * x86_64-windows（部分发行把 EC 仿真器 DLL 放在 x86_64 目录）。
     */
    private fun probeEmulatorDll(wineDir: File, name: String): Boolean =
        listOf("lib/wine/aarch64-windows", "lib/wine/arm64ec-windows", "lib/wine/x86_64-windows")
            .any { File(File(wineDir, it), name).isFile }

    // ------------------------------------------------------------------
    // v3.4.3：assets builtin 预设 JSON（assets-ck 资产，上游同款）
    // ------------------------------------------------------------------

    /** v3.4.3：预设资产解析缓存（assets 随 APK 打包，进程内不变）。 */
    private val presetJsonCache = HashMap<String, Map<String, Map<String, String>>>()

    /**
     * v3.4.3【预设 JSON 装载】：assets builtin 预设表（用户提供的 assets-ck
     * 资产包，与参考源码 上游同款）→ 按档位的环境变量集合。
     *
     *  - "builtin/fex_presets.json"：四档（stability/compatibility/
     *    intermediate/performance）各 15 项 FEX_* —— 上游发行包
     *    LaunchPlan.buildEnv:226-228 经 FEXPresetManager.getEnvVars
     *    展开的同一张表（含 LinBox 旧表缺失的 TSOENABLED / SMC_CHECKS /
     *    VOLATILEMETADATA / SMALLTSCSCALE / MAXINST / HOSTFEATURES /
     *    MONOHACKS / HIDEHYPERVISORBIT —— 其中 performance 档
     *    TSOENABLED=0 + SMC_CHECKS=none + VOLATILEMETADATA=0 是 FEX
     *    转译吞吐的主旋钮，正是「arm64ec 性能问题大概率在这里」的缺口）；
     *  - "builtin/box64_presets.json"：四档各 11 项 BOX64_*（含旧表缺的
     *    AVX / UNITYPLAYER；上游发行包 LaunchPlan.buildEnv:237-242 同款）。
     *
     * context 为 null 或资产缺失/坏 JSON（= 打包错误）时返回空表 ——
     * 调用方回落旧内联表，行为不劣于 v3.4.2。
     */
    internal fun presetEnvVars(
        context: Context?,
        assetPath: String,
        log: (String) -> Unit = {}
    ): Map<String, Map<String, String>> {
        if (context == null) return emptyMap()
        synchronized(presetJsonCache) { presetJsonCache[assetPath]?.let { return it } }
        val parsed = runCatching {
            context.assets.open(assetPath).bufferedReader().use { r ->
                val out = LinkedHashMap<String, Map<String, String>>()
                val arr = org.json.JSONArray(r.readText())
                for (i in 0 until arr.length()) {
                    val preset = arr.optJSONObject(i) ?: continue
                    val name = preset.optString("name").lowercase()
                    val items = LinkedHashMap<String, String>()
                    preset.optJSONArray("items")?.let { a ->
                        for (j in 0 until a.length()) {
                            val item = a.optJSONObject(j) ?: continue
                            val k = item.optString("key")
                            if (k.isNotBlank()) items[k] = item.optString("value")
                        }
                    }
                    if (name.isNotBlank()) out[name] = items
                }
                out
            }
        }.getOrElse {
            log("⚠ 预设资产解析失败：$assetPath（${it.message}）—— 回落旧内联档位表")
            emptyMap()
        }
        synchronized(presetJsonCache) { presetJsonCache[assetPath] = parsed }
        return parsed
    }

    /**
     * v3.3.35：性能档位在 arm64ec 后端真正生效（用户："我的项目所有性能
     * 都没有提升，全在最低性能"）。v3.4.3【预设 JSON 注入】全面重写：
     * 四档 FEX_* 改由 assets builtin/fex_presets.json 派生（用户提供的
     * assets-ck 资产包，与上游发行包 FEXPresetManager /
     * LaunchPlan.buildEnv:226-228 展开的同一张表）—— 旧表每档仅 2-3 项，
     * 缺的正是主性能旋钮：
     *  - performance 档 TSOENABLED=0（标量 TSO 关）、SMC_CHECKS=none
     *    （自修改代码检测关）、VOLATILEMETADATA=0、HALFBARRIERTSO=0 ——
     *    FEX 转译吞吐的主供应商；旧表只开了 L2/L1 cache，主旋钮全空转；
     *  - 全档补齐 SMALLTSCSCALE=1 / MAXINST=5000 / MONOHACKS /
     *    HOSTFEATURES=off / HIDEHYPERVISORBIT=0；
     *  - 旧表 STABILITY/COMPATIBILITY 档 VECTORTSO=1 的「反向 TSO 兜底」
     *    与 JSON 稳定档语义冲突（JSON 全档 VECTORTSO=0，稳定档靠
     *    TSOENABLED=1 + HALFBARRIER=1 + SMC=mtrack 兜底）—— 按 JSON；
     *  - X87ReducedPrecision JSON 全档 0（上游注明 may result in
     *    rendering bugs，上游发行包 四档全不开）—— 旧表性能档 =1 撤销。
     *
     * 层级：opt conf（applyOptConfEnv）与容器 env=（applyExtraEnv 直接
     * 赋值）在其后执行 —— conf/用户显式 FEX_*= 仍可覆盖本预设值；
     * putIfAbsent 仅防同函数内重复。资产缺席回落旧最小表（打包错误兜底）。
     */
    fun applyFexPresetEnv(
        env: MutableMap<String, String>,
        container: ContainerData,
        log: (String) -> Unit = {},
        context: Context? = null
    ) {
        val presetName = container.preset.name.lowercase()
        val vars = presetEnvVars(context, "builtin/fex_presets.json", log)[presetName]
        if (vars.isNullOrEmpty()) {
            when (container.preset) {
                Box64Preset.STABILITY -> {
                    env.putIfAbsent("FEX_VECTORTSOENABLED", "1")
                    env.putIfAbsent("FEX_MEMCPYSETTSOENABLED", "1")
                }
                Box64Preset.COMPATIBILITY -> env.putIfAbsent("FEX_VECTORTSOENABLED", "1")
                Box64Preset.INTERMEDIATE -> env.putIfAbsent("FEX_DISABLEL2CACHE", "0")
                Box64Preset.PERFORMANCE -> {
                    env.putIfAbsent("FEX_DISABLEL2CACHE", "0")
                    env.putIfAbsent("FEX_DYNAMICL1CACHE", "0")
                }
            }
            log("⚠ FEX 预设 JSON 缺席（builtin/fex_presets.json）—— arm64ec 线回落旧最小表（打包错误，请反馈）")
            return
        }
        vars.forEach { (k, v) -> env.putIfAbsent(k, v) }
        log(
            "性能档 arm64ec 线生效（FEX 预设 JSON·$presetName 档）：${vars.size} 项 FEX_* 完整注入" +
                "（TSOENABLED/SMC_CHECKS/VOLATILEMETADATA/SMALLTSCSCALE 等主旋钮在内；" +
                "opt conf / 容器 env= 仍可逐项覆盖）"
        )
    }

    // ------------------------------------------------------------------
    // 4) 音频 / 用户附加环境（audio_setup / apply_extra_env 的注入端）
    // ------------------------------------------------------------------

    /**
     * v3.2.15（wineguanli.py 重制）：容器调优环境注入。
     *
     *  - frame != "0" → DXVK_FRAME_RATE（wineguanli.py 帧数限制同款变量）
     *  - flags 含 unity → BOX64_UNITYPLAYER=1（box64 官方 Unity 兼容开关，
     *    对应 wineguanli.py「Unity 单线程渲染」勾选）
     *  - mangohud 非空 → MANGOHUD=1 + MANGOHUD_CONFIG（rootfs 内置 MangoHud
     *    时生效；对应 wineguanli.py 的 MangoHud 配置）
     *  - cores 非空 → PRIMARY_CORES / SECONDARY_CORES（wineguanli.py 的
     *    /opt/conf/cores.conf 同款语义；LinBox 原生链不做亲和性绑定，
     *    变量供用户自带的 /opt 生态脚本与 box64 配置消费）
     *
     * 放在 applyExtraEnv 之前 —— 用户 env= 仍可覆盖一切。
     */
    fun applyTuningEnv(env: MutableMap<String, String>, container: ContainerData) {
        val frame = container.frame.trim()
        if (frame.isNotEmpty() && frame != "0") {
            env["DXVK_FRAME_RATE"] = frame
        }
        if (container.hasFlag(com.linbox.apps.containers.ContainerFlags.UNITY)) {
            env["BOX64_UNITYPLAYER"] = "1"
        }
        val mh = container.mangohud.trim()
        if (mh.isNotEmpty()) {
            env["MANGOHUD"] = "1"
            env["MANGOHUD_CONFIG"] = mh
        }
        val cores = container.cores.trim()
        if (cores.isNotEmpty()) {
            env["PRIMARY_CORES"] = cores
            val total = Runtime.getRuntime().availableProcessors()
            val primary = parseCoreSpec(cores).filter { it in 0 until total }.toSortedSet()
            val secondary = (0 until total).filter { it !in primary }
            env["SECONDARY_CORES"] = formatCoreSpec(secondary)
        }
    }

    /** "0-3" / "0,1,2" → [0,1,2,3]（wineguanli.py parse_core_string 移植）。 */
    internal fun parseCoreSpec(spec: String): List<Int> {
        val out = mutableListOf<Int>()
        spec.split(',', ';').forEach { part ->
            val p = part.trim()
            if (p.isEmpty()) return@forEach
            if (p.contains('-')) {
                val range = p.split('-', limit = 2)
                val s = range.getOrNull(0)?.trim()?.toIntOrNull()
                val e = range.getOrNull(1)?.trim()?.toIntOrNull()
                if (s != null && e != null && s <= e) {
                    out.addAll(s..e)
                }
            } else {
                p.toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    /**
     * v3.4.0【性能大修】：大核簇自动探测（CPU 亲和默认开启的依据）。
     *
     * 读 /sys/devices/system/cpu/cpu<N>/cpufreq/cpuinfo_max_freq 分簇，
     * max_freq 最高的一簇 = 大核（高通/联发科旗舰大小核布局通用启发式，
     * 上游性能模式同款思路）。三重安全边界：
     *  - 任一 cpu 的 sysfs 条目缺失/不可读 → 无法可靠比较 → 返回空
     *    （保持系统默认调度，零绑定零风险）；
     *  - 单簇 SoC（全部同频）→ 绑定无收益且丢核 → 返回空；
     *  - big 簇即全部核心（≤1 核设备）→ 返回空。
     */
    fun autoPrimaryCores(): List<Int> {
        val total = Runtime.getRuntime().availableProcessors()
        if (total <= 1) return emptyList()
        val freqs = (0 until total).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLongOrNull()?.let { cpu to it }
            }.getOrNull()
        }
        if (freqs.size < total) return emptyList()
        val maxFreq = freqs.maxOf { it.second }
        val big = freqs.filter { it.second >= maxFreq }.map { it.first }
        return if (big.size >= total) emptyList() else big.sorted()
    }

    // ------------------------------------------------------------------
    // v3.3.33：仙剑四（PAL4）兼容 —— dxvk_appfix 原生链移植
    // ------------------------------------------------------------------

    /**
     * v3.3.33（用户："dxvk 运行仙剑四还是不行"）：把 linbox-wine v3.3.30 的
     * 仙剑四 DXVK 兼容补丁移植到 App 原生链。
     *
     * 背景：仙剑奇侠传四（PAL4，2007，CEGUI + D3D9 + mss32）在 arm64ec 新
     * WoW64 下进主菜单后游戏代码 page fault（+0x2941b5 读野指针）—— 老游戏
     * 依赖 D3D9 的 x87 浮点语义（NaN/denorm 传播），GPU 侧返回值异常 →
     * CEGUI 布局计算出野指针。DXVK 的 d3d9.floatEmulation=Strict 是这类
     * 2000 年代国产/日产 DX9 游戏崩溃的标准解。
     *
     * v3.3.30 只在终端脚本链（linbox-wine dxvk_appfix_conf）实现，App 原生
     * 启动链从未生效 —— 用户 2026-11-09 21:57 会话日志实证（DXVK 读的是
     * /opt/dxvk.conf 原文件、日志无"DXVK 兼容补丁"行、崩溃地址不变）。
     * 本函数补齐原生链：命中已知问题游戏 → 合成 conf（/opt/dxvk.conf 全量
     * + 追加覆盖）写入前缀，DXVK_CONFIG_FILE 指向它 —— 仅命中启动生效，
     * 其它游戏零影响（与脚本版逐字同语义）。
     *
     * @return true = 命中仙剑四（调用方据此做单核亲和等附加兼容动作）
     */
    fun applyPal4Compat(
        env: MutableMap<String, String>,
        args: List<String>,
        container: ContainerData,
        log: (String) -> Unit,
        arm64ec: Boolean = false
    ): Boolean {
        val exe = args.firstOrNull { it.endsWith(".exe", ignoreCase = true) }
            ?: args.firstOrNull { it.endsWith(".EXE") }
            ?: return false
        val lower = exe.lowercase()
        val isPal4 = lower.contains("仙剑") || lower.contains("pal4")
        if (!isPal4) return false
        return runCatching {
            val prefix = env["WINEPREFIX"]?.takeIf { it.isNotBlank() } ?: container.prefixPath
            val base = File("${com.linbox.apps.containers.GuanliSupport.OPT_ROOT}/dxvk.conf")
            val out = File(prefix, ".linbox-dxvk-appfix.conf")
            val text = buildString {
                if (base.isFile) append(base.readText())
                if (isNotEmpty() && !endsWith("\n")) append('\n')
                append("\n# LinBox appfix（仙剑奇侠传四：D3D9 浮点语义 + 呈现节流，v3.3.33 原生链移植 / v3.3.38 增补）\n")
                append("d3d9.floatEmulation = Strict\n")
                // v3.3.38【闪屏】（用户："运行仙剑四这类一直闪屏"）：wine-10
                // 会话日志实证 Present mode: VK_PRESENT_MODE_IMMEDIATE_KHR
                // （dxvk.conf 的 presentInterval=-1 + syncInterval=-1 所致）
                // —— IMMEDIATE 无消隐同步，lorie 合成器读到半刷新帧即上屏，
                // 表现为持续闪屏/撕裂。PAL4 为 2007 年 60fps 级 RPG，垂直
                // 同步零感知代价：presentInterval=1 → FIFO 呈现，闪屏根治。
                // （追加在 base 之后，DXVK 配置解析后值覆盖前值，稳赢 -1。）
                append("d3d9.presentInterval = 1\n")
            }
            out.parentFile?.mkdirs()
            out.writeText(text)
            env["DXVK_CONFIG_FILE"] = out.absolutePath
            log("DXVK 兼容补丁: ${File(exe).name} → d3d9.floatEmulation=Strict + presentInterval=1（浮点语义 + 闪屏 FIFO 呈现；仅本次启动生效）")

            // v3.3.38【arm64ec 崩溃根治】仙剑四在 arm64ec 线的第二个死因：
            // FEX 侧 x87 精度。wine-10 会话日志（2026-11-09 21:57）崩溃现场
            // = d3d9 回调进游戏代码 +0x2941b5 `cmpl $3,0x10(%eax)`，
            // EAX=0x052ae3c7（错位垃圾指针）—— 2007 引擎大量经 x87 80 位
            // 中间精度做定点/指针换算，PERFORMANCE 档的
            // X87ReducedPrecision=1（64 位精算提速）恰好喂出这类错位值。
            // floatEmulation=Strict 只管 DXVK 侧 D3D9 数学的语义，
            // 游戏本体 x87 指令由 FES 解释 —— 必须同步把 X87 精度拉回
            // 80 位全精度。经 ~/.fex-emu/Config.json（实证通道，
            // ensureFexConfigFile 同款）；下一次非仙剑四会话启动时
            // ensureFexConfigFile 会按容器预设自动改回，无跨容器污染。
            if (arm64ec) {
                val fexConf = File("${env["HOME"] ?: "$RFS_ROOT/home"}/.fex-emu/Config.json")
                if (fexConf.isFile) {
                    val original = fexConf.readText()
                    val rewritten = original
                        .replace("\"X87ReducedPrecision\":\"1\"", "\"X87ReducedPrecision\":\"0\"")
                    if (rewritten != original) {
                        fexConf.writeText(rewritten)
                        log("FEX 兼容补丁: X87ReducedPrecision=1→0（仙剑四引擎依赖 x87 80 位中间精度 —— PERFORMANCE 档的 64 位精算是野指针供应商；其他容器下次启动自动恢复预设）")
                    }
                }
            }
            log("仙剑四附加建议：该游戏 2007 引擎存在多核竞态崩溃（社区共识），CPU核心 页选单核最稳；未配置 cores 时启动链将自动单核")
            true
        }.getOrElse {
            log("⚠ 仙剑四 appfix 注入异常：${it.message}（不影响启动）")
            false
        }
    }

    /** [0,1,2,3] → "0-3"（wineguanli.py format_cores 移植：连续段折叠）。 */
    internal fun formatCoreSpec(cores: List<Int>): String {
        if (cores.isEmpty()) return ""
        val sorted = cores.distinct().sorted()
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var end = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] == end + 1) {
                end = sorted[i]
            } else {
                parts.add(if (start != end) "$start-$end" else "$start")
                // v3.2.16 修复：Kotlin 赋值不是表达式，链式赋值 start = end = x 非法，拆开
                end = sorted[i]
                start = sorted[i]
            }
        }
        parts.add(if (start != end) "$start-$end" else "$start")
        return parts.joinToString(",")
    }

    /** 音频关闭（audio=off）：清空 PULSE_SERVER，wine 走无声。 */
    fun applyAudioOff(env: MutableMap<String, String>) {
        env["PULSE_SERVER"] = ""
    }

    // ------------------------------------------------------------------
    // 5) /opt 生态配置（v3.2.25 引入，v3.2.26 修正路径探测 + 去掉 wine_path.conf）
    // ------------------------------------------------------------------

    /**
     * /opt 生态根目录探测（v3.2.26 修正：wine 槽位目录也纳入候选）。
     *
     * 候选顺序（用户指定主路径优先）：
     *  1. rootfs/opt —— 用户明确的路径（/data/data/com.linbox/files/rootfs/opt）
     *  2. wine 槽位目录（resolved.wineDir，如 rootfs/usr/opt/wine-proton10-x86）
     *     —— 发行包风格包安装时把 conf/prefix/locale.conf 一并打进
     *     wine 目录树的场景（App 的 wine 版本管理以 --strip-components=1
     *     解包，包内与 bin/ 平级的 conf/、prefix/ 就落在这里）
     *  3. rootfs/opt/opt —— v3.2.28：生态包根是 opt/ 时用户解到了
     *     rootfs/opt 下，多套一层（原始解包动作不改用户文件，按套层读）
     *  4. 槽位内 opt/ 子目录 —— v3.2.28：包根是 opt/ 的生态包经
     *     --strip-components=1 装进槽位后，conf/prefix/locale.conf 落在
     *     槽位 opt/ 子目录（与 bin/ 平级的是 wine9.2-x64/ 这类 wine 树）
     *  5. rootfs/usr/opt —— 旧版布局兑底
     *
     * 判定条件：conf 目录 / locale.conf / prefix 目录 / dxvk1 目录任一
     * 存在。无则 null（全部 /opt 功能自动旁路）。
     *
     * @param wineDir 当前 wine 槽位目录（无解析结果时可传 null）
     */
    fun optRoot(wineDir: File? = null): File? =
        listOfNotNull(
            File("$RFS_ROOT/opt"),
            wineDir,
            wineDir?.let { File(it, "opt") },
            File("$RFS_ROOT/opt/opt"),
            File("$RFS_USR/opt")
        ).firstOrNull { optHasMarkers(it) }

    /** /opt 生态标记物（任一存在即认定该候选是生态根）。 */
    private fun optHasMarkers(root: File): Boolean =
        File(root, "conf").isDirectory ||
            File(root, "locale.conf").isFile ||
            File(root, "prefix").isDirectory ||
            File(root, "dxvk1").isDirectory

    /**
     * /opt 生态探测结果描述（会话日志可见，回答“路径对不对”一目了然）。
     * 返回形如 "rootfs/opt ✓（conf 5 项, prefix ✓, locale.conf ✓）" 的
     * 逐候选明细，供启动链写进会话日志。
     *
     * v3.2.28：候选目录**存在但没有标记物**时，列出目录内实际内容
     * （≤8 项，目录带 / 后缀）—— 用户把生态包整包/压缩包放错层时，
     * 日志直接显示“里面到底有什么”，不再只有一个无解释的 ✗。
     */
    fun optProbeReport(wineDir: File? = null): String {
        val candidates = listOfNotNull(
            "rootfs/opt" to File("$RFS_ROOT/opt"),
            wineDir?.let { "wine槽位 ${it.name}" to it },
            "rootfs/opt/opt" to File("$RFS_ROOT/opt/opt"),
            wineDir?.let { "wine槽位/${it.name}/opt" to File(it, "opt") },
            "rootfs/usr/opt" to File("$RFS_USR/opt")
        )
        return candidates.joinToString("；") { (label, root) ->
            if (optHasMarkers(root)) {
                val bits = mutableListOf<String>()
                File(root, "conf").takeIf { it.isDirectory }?.let {
                    bits.add("conf ${it.listFiles { f -> f.name.endsWith(".conf") }?.size ?: 0} 项")
                }
                File(root, "prefix").takeIf { it.isDirectory }?.let { bits.add("prefix ✓") }
                File(root, "locale.conf").takeIf { it.isFile }?.let { bits.add("locale.conf ✓") }
                File(root, "dxvk1").takeIf { it.isDirectory }?.let { bits.add("dxvk1 ✓") }
                "$label ✓（${bits.joinToString(", ")}）"
            } else if (root.isDirectory) {
                val listing = root.listFiles()
                    ?.sortedBy { it.name }
                    ?.take(8)
                    ?.joinToString(" ") { f -> if (f.isDirectory) "${f.name}/" else f.name }
                    .orEmpty()
                if (listing.isEmpty()) "$label ✗（目录存在但为空）"
                else "$label ✗（无 conf/locale.conf/prefix/dxvk1 —— 实际内容: $listing）"
            } else "$label ✗（目录不存在）"
        }
    }

    /**
     * 解析 conf 全部 KEY=VALUE（与 linbox-wine 脚本 load_opt_confs 同源
     * 语义）—— v3.3.39 起委托 [ApkConfStore.mergedConfMap]（统一源）：
     *  - App 内部 linbox-conf/conf/（权威源：assets 种子 + UI 编辑 +
     *    生态一次性导入 + 写透同步）与 /opt 生态 conf/（rootfs/opt、
     *    wine 槽位 opt/ 等全部候选，[optRoot]）**逐文件 mtime 新者胜**；
     *  - 固定顺序（startfex.sh 同款）：env / cores / path /
     *    dynarec_fixmpeg / dynarec_active；其余 *.conf 按名兜底，排除
     *    wine_path.conf（槽位自管）与 custom.conf（DY 取值定义表，
     *    逗号候选值不是真值，sh source 语义下不加载）；
     *  - 仅提取 `KEY=VALUE` / `export KEY=VALUE` 行（行内注释/引号剥离），
     *    不执行 —— 配置文件无法注入任意代码，也碰不到容器隔离变量；
     *  - **同名键后写覆盖**（v3.2.29 修复保留：sh 逐文件 source 时后
     *    载入的文件覆盖先载入的）。
     *
     * @return 键值映射；双源全无 .conf 时为空 map。
     */
    fun readOptConfs(wineDir: File? = null): Map<String, String> =
        ApkConfStore.mergedConfMap(wineDir)

    /**
     * 单行 KEY=VALUE 解析（v3.2.29）：export 前缀剥离 → 键名校验 →
     * **行内注释剥离**（env.conf 的 `export WINEDLLOVERRIDES="dxgi=n,b"  # 如果使用DXVK`
     * 旧版把注释全部吞进值里）→ 成对引号剥离。
     */
    internal fun parseConfLine(raw: String): Pair<String, String>? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        line = line.removePrefix("export ").trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val key = line.substring(0, eq).trim()
        if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
        return key to stripInlineComment(line.substring(eq + 1).trim())
    }

    /** 值的行内注释剥离（引号区段外的 ` #…` 截断）+ 成对引号剥离。 */
    internal fun stripInlineComment(value: String): String {
        val out = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (quote == null && (c == '"' || c == '\'')) {
                quote = c; out.append(c); i++; continue
            }
            if (quote != null) {
                if (c == quote) quote = null
                out.append(c); i++; continue
            }
            if (c == '#' && (i == 0 || value[i - 1].isWhitespace())) break
            out.append(c); i++
        }
        var v = out.toString().trim()
        if (v.length >= 2 &&
            ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))
        ) {
            v = v.substring(1, v.length - 1).trim()
        }
        return v
    }

    /**
     * sh `$VAR` / `${VAR}` 引用展开（v3.2.29）：path.conf 的
     * `export LD_LIBRARY_PATH=…:$LD_LIBRARY_PATH` 旧版原样透传字符串
     * `$LD_LIBRARY_PATH`，wine 拿到的是字面美元符。按 [scope]（当前
     * 已构建的 env + conf 已解析值）展开；scope 中不存在的引用保持
     * 字面（比 sh 的静默展开为空更可诊断）。
     */
    internal fun expandShellVars(value: String, scope: Map<String, String>): String {
        if (!value.contains('$')) return value
        val sb = StringBuilder(value.length + 32)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '$') { sb.append(c); i++; continue }
            if (i + 1 >= value.length) { sb.append(c); break }
            val braced = value[i + 1] == '{'
            val nameStart = if (braced) i + 2 else i + 1
            var j = nameStart
            while (j < value.length && (value[j].isLetterOrDigit() || value[j] == '_')) j++
            val name = value.substring(nameStart, j)
            if (name.isEmpty()) { sb.append(c); i++; continue }
            val closeOk = !braced || (j < value.length && value[j] == '}')
            if (!closeOk) { sb.append(c); i++; continue }
            sb.append(scope[name] ?: "$$name")
            i = if (braced) j + 1 else j
        }
        return sb.toString()
    }

    /**
     * v3.3.2：MIDI SoundFont 自动探测（配合 0010-midimap-fluidsynth
     * wine 补丁 —— midimap 内置 "Wine Software Synth" 软合成端口）。
     *
     * rootfs 预置 .sf2 音色库（FluidR3_GM 等）时自动设置
     * WINE_MIDI_SOUNDFONT，midimap 的 unix 侧据此 dlopen libfluidsynth
     * 并加载音色（缺失时补丁侧静默回落 —— 无内置合成端口，行为与
     * 未打补丁一致，不影响构建与启动）。容器 env= 仍可覆盖。
     */
    fun applyMidiSoundfontEnv(env: MutableMap<String, String>, log: (String) -> Unit = {}) {
        if (!env["WINE_MIDI_SOUNDFONT"].isNullOrBlank()) return
        // 用户 env/conf 已显式指定 MIDI_SF2_PATH（生态包惯例：
        // env.conf 的 MIDI_SF2_PATH=/…/opt/midi_server/sndfnt.sf2）→ 直接映射
        env["MIDI_SF2_PATH"]?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            if (f.isFile && f.length() > 0) {
                env["WINE_MIDI_SOUNDFONT"] = f.absolutePath
                log("MIDI 音色库: ${f.name}（来自 MIDI_SF2_PATH）→ WINE_MIDI_SOUNDFONT")
                return
            }
        }
        val dirs = listOf(
            "$RFS_ROOT/usr/share/soundfonts",
            "$RFS_ROOT/usr/local/share/soundfonts",
            "$RFS_ROOT/usr/share/sounds/sf2",
            "$RFS_ROOT/opt/soundfonts",
            "$RFS_ROOT/opt/midi_server"
        )
        val sf2 = dirs.asSequence()
            .map { File(it) }
            .filter { it.isDirectory }
            .flatMap { it.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".sf2") }?.asSequence() ?: emptySequence() }
            .firstOrNull { it.length() > 1024 * 1024 } ?: return
        env["WINE_MIDI_SOUNDFONT"] = sf2.absolutePath
        log("MIDI 音色库: ${sf2.name} → WINE_MIDI_SOUNDFONT（midimap 内置软合成可用）")
    }

    /**
     * locale.conf → 语言（startwine.sh：export LC_ALL="$(cat /opt/locale.conf)"）。
     *
     * v3.3.39：统一到 APK 内部 —— 委托 [ApkConfStore.mergedLocale]
     * （App 内部 linbox-conf/locale.conf 与 /opt 生态根 locale.conf
     * 逐文件 mtime 新者胜；无任何源 → null）。
     *
     * @return 合法 locale 字符串；无文件/内容非法为 null。
     */
    fun readOptLocale(wineDir: File? = null): String? =
        ApkConfStore.mergedLocale(wineDir)

    /**
     * 应用 /opt 生态环境（conf **source 语义** + locale.conf）。
     *
     * v3.2.27（用户原话："启动 wine 依旧没有读取 opt 下的任何 conf 配置，
     * 你根本没有 env 或者 export，更没有执行 sh 里那种. /路径"）：
     * 旧实现的 BOX64_*、WINEESYNC 等白名单太窄 —— env.conf 里的
     * MESA_*、GALLIUM_*、自定义调优变量全部被丢弃，等于没读。现按
     * startwine.sh 逐个 source /opt/conf 下全部 *.conf 的语义执行：
     *  - **全部 KEY=VALUE 都 export** 给 wine 进程（逐项日志可见），
     *    仅 App 链路生存所需的关键变量（WINEPREFIX/加载器/X11/动态库
     *    路径等，见 [CONF_BLOCKED_KEYS]）不透传并大声说明；
     *  - PATH 特殊处理：合并而非覆盖 —— conf PATH 中 env PATH 缺失的
     *    目录前置补入（等价 sh 的 `export PATH=/usr/bin:$PATH` 追加语义）；
     *  - GLIBC_BIN 兜底导出（sh：`export GLIBC_BIN=/usr/local/bin`）——
     *    指向 rootfs 的 usr/local/bin，供 rootfs 脚本定位 box64/wineserver；
     *  * 容器 env=（applyExtraEnv，链路最后）仍可覆盖一切；
     *  * locale.conf → LANG/LC_ALL 已上移 buildBaseEnv（v3.2.29：语言
     *    唯一来源 /opt/locale.conf；conf 目录各 .conf 显式 export 的
     *    LANG/LC_ALL 走下方通用覆盖逻辑）；
     *  * cores.conf 的 PRIMARY_CORES/SECONDARY_CORES 走通用导出；
     *    applyTuningEnv 在其后，容器 cores 配置始终优先。
     */
    fun applyOptConfEnv(
        env: MutableMap<String, String>,
        container: ContainerData,
        wineDir: File? = null,
        log: (String) -> Unit = {},
        // v3.3.40【性能】：调用方（RootfsLauncher）可传入启动链已算好的
        // conf 快照 —— probeLine + source 不再把全部 conf 文件读两遍。
        precomputedConf: Map<String, String>? = null
    ) {
        // v3.3.39：conf 统一到 APK 内部 —— 未装 /opt 生态包不再旁路：
        // App 内部 linbox-conf/（assets 种子 + UI 编辑 + 生态一次性导入）
        // 永远是权威源；/opt 生态 conf 仅作为合并第二源（逐文件 mtime
        // 新者胜 —— 终端侧直接改 /opt/conf 同样生效）。cores.conf 的
        // PRIMARY_CORES 从此开箱即导出（applyTasksetAffinity 钉大核不再
        // 依赖生态包存在）。
        ApkConfStore.ensure(null)
        log(ApkConfStore.probeLine(wineDir))
        val conf = precomputedConf ?: readOptConfs(wineDir)
        // locale.conf → LANG/LC_ALL 已上移 buildBaseEnv（v3.2.29：语言
        // 唯一来源 locale.conf（现统一源）；conf/*.conf 若显式 export LANG/
        // LC_ALL，走下方通用 conf 覆盖逻辑，容器 env= 仍最高优先）。
        if (conf.isEmpty()) {
            log("conf 源: 双源均无可解析的 KEY=VALUE 配置（App 内部 + /opt 生态）")
            return
        }
        val userEnv = container.env
        // v3.3.18（用户实报：“选择 wrapper 驱动依旧是 turnip，是不是和我的
        // conf 配置有关？”—— 正是）：Vulkan ICD 选择保护。启动链顺序是
        // 6a 注入容器所选驱动的 VK_ICD_FILENAMES → 6c source opt/conf ——
        // 用户 path.conf 常见硬编码 turnip 的 freedreno ICD（如
        // VK_ICD_FILENAMES=…/freedreno_icd.aarch64.json），旧版此处无差别
        // env[k]=v 覆盖 → 选了 wrapper 也会被顶回 turnip。现在容器选中
        // wrapper / turnip 槽位时，VK_ICD_FILENAMES / VK_DRIVER_FILES 两个
        // ICD 指定键【不得被 conf 覆盖】（VK_DRIVER_FILES 是新 loader 的
        // 优先键，留着它同样能压过注入值）。conf 与选择的同步由
        // GuanliSupport.syncPathConfVkIcd 负责（UI 选择/运行时激活均落
        // conf），此处仅防护手工编辑/多容器等不同步的残余情形。未选槽位
        //（Mesa 内置档）时 conf 值照常透传（用户 conf 自定 ICD 仍生效）。
        val vkSlotActive = (container.wrapper.isNotBlank() && container.wrapper != "off") ||
            (container.turnip.isNotBlank() && container.turnip != "off")
        val vkGuarded = mutableListOf<String>()
        val applied = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        // $VAR 展开作用域：当前 env 优先，conf 自身已解析值作第二来源
        // （sh 逐文件 source 时先载入的变量对后载入的行可见 —— 同一语义）
        val expandScope = LinkedHashMap<String, String>(env)
        conf.forEach { (k, v) -> expandScope.putIfAbsent(k, v) }
        conf.forEach { (k, rawV) ->
            val v = expandShellVars(rawV, expandScope)
            when {
                // 容器 env= 已显式设置的键跳过（最高优先）
                userEnv.contains("$k=") -> {
                    log("opt conf: $k 被容器 env= 显式配置覆盖，保留容器值")
                }
                k in CONF_BLOCKED_KEYS -> blocked.add(k)
                vkSlotActive && (k == "VK_ICD_FILENAMES" || k == "VK_DRIVER_FILES") ->
                    vkGuarded.add(k)
                k == "PATH" -> {
                    // 合并而非覆盖：conf PATH 里 env PATH 缺失的目录前置补入
                    // （sh 的 `export PATH=/usr/bin:$PATH` 同款追加语义）
                    val cur = env["PATH"] ?: ""
                    val have = cur.split(':').filter { it.isNotBlank() }.toSet()
                    val add = v.split(':').filter { it.isNotBlank() && it !in have }
                    if (add.isNotEmpty()) {
                        env["PATH"] = (add + cur).joinToString(":")
                        applied.add("PATH+=${add.joinToString(":")}")
                    }
                }
                else -> {
                    env[k] = v
                    applied.add("$k=$v")
                }
            }
        }
        if (blocked.isNotEmpty()) {
            log("opt 生态: ${blocked.joinToString(", ")} 属 App 链路关键变量（WINEPREFIX/加载器/X11/动态库路径），conf 值不透传")
        }
        if (vkGuarded.isNotEmpty()) {
            log(
                "opt 生态: ${vkGuarded.joinToString(", ")} 被容器 Vulkan 驱动选择接管" +
                    "（wrapper=${container.wrapper.ifBlank { "—" }} / turnip=${container.turnip.ifBlank { "—" }}）" +
                    "，conf 值不透传 —— 启动链已注入所选驱动 ICD（选了 wrapper 不再被 path.conf 顶回 turnip）"
            )
        }
        // wine_path.conf 已按用户要求剔除：wine 目录由 LinBox 槽位体系管理
        log(
            if (applied.isEmpty()) "opt 生态: conf ${conf.size} 项解析后无新增导出（全部被容器 env= 覆盖或为关键变量）"
            else "opt 生态: conf ${conf.size} 项已 source（export ${applied.size} 项: ${applied.joinToString(", ").take(400)}）"
        )
        // GLIBC_BIN（惯例：export GLIBC_BIN=/usr/local/bin）——
        // 指向 rootfs 的 usr/local/bin（box64/wineserver 包装所在地），
        // conf/env 均未设置且目录存在时兜底导出，供 rootfs 脚本消费。
        if (!env.containsKey("GLIBC_BIN") && !userEnv.contains("GLIBC_BIN=")) {
            val glibcBin = File("$RFS_USR/local/bin")
            if (glibcBin.isDirectory) {
                env["GLIBC_BIN"] = glibcBin.absolutePath
                log("opt 生态: export GLIBC_BIN=${glibcBin.absolutePath}（glibc 链路惯例）")
            }
        }
    }

    /**
     * conf 值不得覆盖 App 链路关键变量（wine/box64 进程生存所需）。
     * source 语义的黑名单：WINEPREFIX/加载器与 X11 会话/动态库搜索路径
     * （LD_* 会破坏 aarch64 宿主二进制加载）/BOX64_*_PATH（App 按 wine
     * 槽位精心构造，覆盖即断链）。其余变量全部透传。
     */
    private val CONF_BLOCKED_KEYS = setOf(
        "WINEPREFIX", "WINE", "WINELOADER", "WINESERVER", "WINEDLLPATH", "WINEDLLDIR",
        "WINELOADERNOEXEC", "WINEBOOT",
        "DISPLAY", "XAUTHORITY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR",
        "HOME", "PWD", "SHELL", "USER", "LOGNAME", "TERM",
        "TMPDIR", "TEMP", "TMP", "PULSE_SERVER",
        "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_DEBUG",
        "BOX64_PATH", "BOX64_LD_LIBRARY_PATH"
    )

    /**
     * 用户附加环境 + DLL 覆盖（apply_extra_env 移植）。
     *
     * env= 为空格分隔的 K=V 列表，原样透传 —— 语义与脚本的
     * `export $_extra`（词切分后逐个 export）一致；放在构建链最后，
     * 用户可覆盖任何上游变量（含 BOX64_* / GALLIUM_DRIVER / WINEDEBUG）。
     */
    fun applyExtraEnv(env: MutableMap<String, String>, container: ContainerData) {
        val extra = container.env.trim()
        if (extra.isNotEmpty()) {
            extra.split(Regex("\\s+")).forEach { token ->
                val eq = token.indexOf('=')
                if (eq > 0) {
                    env[token.substring(0, eq)] = token.substring(eq + 1)
                }
            }
        }
        val ovr = container.dxoverrides.trim()
        if (ovr.isNotEmpty()) {
            val existing = env["WINEDLLOVERRIDES"]
            env["WINEDLLOVERRIDES"] = if (existing.isNullOrBlank()) {
                ovr
            } else {
                "$ovr:$existing"
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun isAarch64(): Boolean {
        val arch = System.getProperty("os.arch", "aarch64").lowercase()
        return arch == "aarch64" || arch == "arm64"
    }

    /** 兜底目录创建（脚本 mkdir -p 矩阵的移植）。 */
    fun ensureRuntimeDirs(container: ContainerData) {
        runCatching {
            // wineserver 运行时目录（wine 把 /tmp 硬编码为 $RFS_USR/tmp，
            // 启动时要在其下创建 .wine-<uid>）
            RUNTIME_DIR.mkdirs()
            // App 侧 X11 socket 目录（wine 客户端经它连内置 X 服务）
            File(ContainerManager.X11_SOCK_DIR).mkdirs()
            // pulse unix socket 目录
            File(RUNTIME_DIR, "pulse").mkdirs()
            // XDG 家目录
            File("$RFS_ROOT/home/.config").mkdirs()
            File("$RFS_ROOT/home/.cache").mkdirs()
            File("$RFS_ROOT/home/.local/share").mkdirs()
            // 容器前缀与日志
            File(container.prefixPath).mkdirs()
            File("${ContainerManager.CONTAINERS_ROOT}/logs").mkdirs()
        }
    }
}
