package com.linbox.apps.containers

import java.io.File

/**
 * LinBox Wine 容器 — 数据模型与 container.conf 编解码（App 侧）。
 *
 * 存储布局（与 rootfs 侧 linbox-container CLI 完全同源，双端互通）：
 *   容器根目录 : /data/data/com.linbox/files/rootfs/home/.linbox/containers
 *   单个容器   : <name>/container.conf + <name>/prefix (WINEPREFIX)
 *   全局配置   : /data/data/com.linbox/files/rootfs/usr/etc/linbox/container.conf
 *
 * conf 为 key=value 行格式（# 注释），键集与 CLI write_new_conf 一一对应：
 *   name / backend / preset / screen / dmode / gallium / env / dxvk /
 *   vkd3d / dxoverrides / wine / lang / audio / created
 *
 * v3.2.15（wineguanli.py 重制）新增键：
 *   frame（帧率限制 DXVK_FRAME_RATE）/ cores（主核心集）/ dsound /
 *   dshow（DLL 加载序）/ dpi（LogPixels）/ winver（Windows 版本）/
 *   mangohud（MANGOHUD_CONFIG）/ flags（高级启动开关 csv）/
 *   turnip / zink / wined3d（驱动版本槽位，对接 /opt 生态）
 * v3.2.18（wineguanli.py 完全重制）新增键：
 *   exe（默认运行的程序路径，wineguanli custom_exe_path 等价物）
 *
 * 显示方案（重要）：本方案为 X11 —— X 服务为 LinBox 内置 termux-x11/
 * libXlorie，unix socket 位于 App 侧 $PREFIX/tmp/.X11-unix/Xn（默认 :1）。
 * rootfs 内 libX11/xtrans/libxcb 已按该 socket 路径打补丁，容器内程序
 * 直连 App 侧 X 服务，无 Wayland/Weston 参与。
 */

// ---------------------------------------------------------------------------
// CPU 翻译后端（与 linbox-wine 的后端选择逻辑一致）
// ---------------------------------------------------------------------------

/**
 * 容器使用的 CPU 翻译后端。
 *
 * @property id    写入 container.conf 的 backend= 值
 * @property label UI 展示名
 * @property desc  一句话说明（UI 副标题）
 */
enum class ContainerBackend(
    val id: String,
    val label: String,
    val desc: String
) {
    /** 按设备架构与已装组件自动挑选（aarch64: arm64ec → box64 → hangover；x86_64: native）。 */
    AUTO("auto", "自动选择", "自动挑选可用后端（推荐）"),

    /** aarch64 Wine 原生运行（bionic-arm64ec 构建，new WoW64，无需模拟器）。 */
    ARM64EC("arm64ec", "ARM64EC 原生", "aarch64 Wine 原生运行（new WoW64，无模拟开销）"),

    /** box64 + x86_64 Wine（构建系统 wine 包 = x86_64 新 WoW64，主力路径）。 */
    BOX64("box64", "Box64", "box64 运行 x86_64 Wine，性能最佳"),

    /** aarch64 Wine + 内建 QEMU 模拟（hangover 构建，兼容性兜底）。 */
    HANGOVER("hangover", "Hangover", "aarch64 Wine + QEMU，兼容兜底"),

    /** FEX-Emu (FEXInterpreter) + x86_64 Wine（需 rootfs 内已安装 FEX）。 */
    FEXCORE("fexcore", "FEXCore", "FEX-Emu 运行 x86_64 Wine（需已安装）"),

    /** x86_64 设备直接运行，不经翻译层。 */
    NATIVE("native", "原生 x86_64", "x86_64 设备直连，无翻译层");

    companion object {
        fun fromId(id: String?): ContainerBackend =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

// ---------------------------------------------------------------------------
// Box64 动态重编译预设（v3.3.16 对齐 上游实现 Box64PresetManager 四档）
// ---------------------------------------------------------------------------

/**
 * Box64 DYNAREC 预设（上游实现 Box64PresetManager.getEnvVars 同款四档，
 * 变量值逐字对齐；标签按 LinBox 语言习惯命名）。
 *
 * STABILITY      : 最稳档（wineboot/旧程序崩溃时选）—— 全安全开关
 * COMPATIBILITY  : 兼容优先 —— 强内存序 + 安全 flags
 * INTERMEDIATE   : 进阶（上游实现 原名）—— 大块 + CALLRET 优化开
 * PERFORMANCE    : 性能优先 —— BIGBLOCK=3 + 全快路径，适合多数游戏
 *
 * 旧容器数据迁移：原两档 id（performance/compatibility）保留不变，
 * fromId 对未知 id 的回退仍为 PERFORMANCE（与旧行为一致）。
 */
enum class Box64Preset(
    val id: String,
    val label: String,
    val desc: String
) {
    // v3.3.28：描述文案对齐 v3.3.21 实际注入值 —— 四档 SAFEFLAGS 全部
    // 为 0（用户实测定稿：box64 0.4.2~0.4.4 任何版本 SAFEFLAGS=0 均正常，
    // 1/2 反而触发 ntdll 早期初始化 SIGSEGV；旧文案写 SAFEFLAGS=2 已失真）。
    // v3.3.35：arm64ec 线同档生效（WineEnvironment.applyFexPresetEnv ——
    // BOX64_DYNAREC_* 仅 box64 线消费，arm64ec 线此前四档全空转）；
    // 描述补 FEX 侧注入值，PERFORMANCE 的旧文案 BIGBLOCK=3 一并修正
    //（v3.3.21 起 box64 侧实际 BIGBLOCK=1=上游默认+全快路径开关）。
    STABILITY("stability", "稳定优先", "box64:STRONGMEM=2；FEX:向量/REP-MOVS全TSO，最稳档"),
    COMPATIBILITY("compatibility", "兼容优先", "box64:STRONGMEM=1；FEX:向量TSO，更稳定"),
    INTERMEDIATE("intermediate", "进阶", "box64:BIGBLOCK=2；FEX:L2缓存启用，速度与兼容平衡"),
    PERFORMANCE("performance", "性能优先", "box64:全快路径；FEX:L1/L2全量+x87提速，适合多数游戏");

    companion object {
        fun fromId(id: String?): Box64Preset =
            entries.firstOrNull { it.id == id } ?: PERFORMANCE
    }
}

// ---------------------------------------------------------------------------
// Mesa/Gallium 渲染器选项（对齐 linbox-wine 的 GALLIUM_DRIVER 注入）
// ---------------------------------------------------------------------------

/**
 * 渲染器选择。
 *
 * AUTO       : 分层探测（v3.4.1）—— dri+msm（Adreno DRM 可达）→ freedreno
 *              原生 GL；kgsl/Vulkan ICD 可达 → zink+turnip（无 dri 权限
 *              设备的 GPU 加速主路）；均不可达 → softpipe 兜底
 * ZINK       : OpenGL-on-Vulkan（配 DXVK/VKD3D 时推荐）
 * FREEDRENO  : Adreno 驱动（msm/kgsl）
 * SOFTPIPE   : 软渲染兜底（无 GPU 加速时；rootfs 的 aarch64/x86_64
 *              mesa 均未构建 llvmpipe —— 交叉构建不带 LLVM，
 *              GALLIUM_DRIVER=llvmpipe 是无效驱动名，v3.1.6 改为
 *              实际存在的 softpipe；旧容器的 "llvmpipe" 值自动归一）
 */
enum class GalliumDriver(val id: String, val label: String) {
    AUTO("auto", "自动"),
    ZINK("zink", "Zink (Vulkan)"),
    FREEDRENO("freedreno", "Freedreno (Adreno)"),
    SOFTPIPE("softpipe", "Softpipe (软渲染)");

    companion object {
        fun fromId(id: String?): GalliumDriver = when (id?.trim()?.lowercase()) {
            // v3.1.6：旧值归一（llvmpipe 未构建，一律归 softpipe）
            "llvmpipe", "softpipe" -> SOFTPIPE
            "zink" -> ZINK
            "freedreno" -> FREEDRENO
            "auto", "", null -> AUTO
            else -> AUTO
        }
    }
}

// ---------------------------------------------------------------------------
// 显示模式（对齐终端 glibc-runner 的 -d/-v/-f，v2.26 新增）
// ---------------------------------------------------------------------------

/**
 * 容器显示模式（container.conf 的 dmode= 键）。
 *
 * OFF  : 跟随 X11 窗口（native，桌面会话默认）
 * D    : 固定 X 屏幕分辨率（startwine -d：命令行 explorer /desktop=shell,WxH 虚拟桌面）
 * V    : wine 虚拟桌面（-v：explorer /desktop，DDraw 老游戏必需）
 * F    : 独立窗口（-f：注册表虚拟桌面 user.reg Explorer Desktops；无分辨率=裸窗口+清蓝底）
 */
enum class DisplayMode(val id: String, val label: String, val desc: String) {
    OFF("off", "跟随窗口", "X 屏幕跟随窗口尺寸（桌面/办公）"),
    D("d", "固定分辨率 (-d)", "explorer /desktop=shell,WxH 命令行虚拟桌面（带 wine 界面）"),
    V("v", "虚拟桌面 (-v)", "wine explorer /desktop，DDraw 老游戏兼容"),
    F("f", "独立窗口 (-f)", "注册表虚拟桌面（user.reg Explorer Desktops，带 wine 界面；无分辨率=裸窗口）");

    companion object {
        fun fromId(id: String?): DisplayMode =
            entries.firstOrNull { it.id == id } ?: OFF
    }
}

// ---------------------------------------------------------------------------
// 容器语言选项（container.conf 的 lang= 键，v2.26 新增）
// ---------------------------------------------------------------------------

/** 容器语言（wine 代码页/界面语言由它决定；与 Termux 会话语言完全隔离）。
 *
 * v3.3.2（用户反馈"语言设置你给去掉了？那个更改后可以更改启动配置语言"）：
 * 语言下拉恢复 —— v3.2.29 曾整体移除（当时 wine 语言改由 /opt/locale.conf
 * 独家决定，容器编辑页只剩只读说明）。现按 startwine.sh/wineguanli.py 语义
 * 恢复为可选项：显式选择 → LANG/LC_ALL 直接用它（winelib 0009 补丁修复
 * bionic setlocale 失效后 LC_ALL 真正决定 wine 界面语言）；"跟随
 * /opt/locale.conf"（auto）→ 读生态包 locale.conf，无则兜底 zh_CN.UTF-8。
 */
object ContainerLangs {
    /** auto："跟随 /opt/locale.conf"档（生态包未装时兜底 zh_CN.UTF-8）。
     *  v3.3.4：跟随档从默认改为可选项（默认 = 简体中文）。 */
    const val AUTO = "auto"

    // v3.3.4（用户问题 4）：简体中文提到首位（新默认档），跟随档紧随其后
    val CHOICES: List<String> = listOf(
        "zh_CN.UTF-8", AUTO, "en_US.UTF-8", "zh_TW.UTF-8",
        "ja_JP.UTF-8", "ko_KR.UTF-8"
    )
    val DISPLAY: List<String> = listOf(
        "简体中文", "跟随 /opt/locale.conf", "English", "繁體中文", "日本語", "한국어"
    )
    // v3.3.4（用户问题 4）：默认 = 简体中文，不再跟随 /opt/locale.conf；
    // 跟随档（auto）降为可选项 —— 显式选了才读生态包 locale.conf。
    const val DEFAULT = "zh_CN.UTF-8"

    /** 存储值 → 下拉显示名（未知值回落默认档简体中文）。 */
    fun displayOf(value: String): String =
        DISPLAY.getOrElse(CHOICES.indexOf(value)) { DISPLAY.first() }

    /** 下拉显示名 → 存储值（未知项回落简体中文）。 */
    fun valueOf(display: String): String =
        CHOICES.getOrElse(DISPLAY.indexOf(display)) { DEFAULT }
}

// ---------------------------------------------------------------------------
// 容器音频模式（container.conf 的 audio= 键，v2.26 新增）
// ---------------------------------------------------------------------------

/**
 * 容器音频模式（音频由 rootfs 自带 PulseAudio 承载，与 Termux 侧隔离）。
 *
 * v3.4.7 新增 [ALSA] 档（用户 2026-11-11："保证正常使用 pulse 或者
 * nopulse 使用 alsa，查看 rootfs 是不是没有相关编译构建"）：wine 侧
 * 注册表 [Software\\Wine\\Drivers] Audio=alsa → 只用 winealsa →
 * libasound 默认 PCM（rootfs /etc/asound.conf 全局指向 alsa-plugins
 * pulse 桥）→ 同一 rootfs 守护 → module-sles-sink。pulse 驱动无声的
 * 游戏换此档排除驱动层；守护与 PULSE_SERVER 与 pulse 档同源（alsa
 * 桥需要同一守护在跑）。
 */
enum class ContainerAudio(val id: String, val label: String, val desc: String) {
    PULSE("pulse", "PulseAudio", "rootfs 自带 PulseAudio（unix socket 隔离，推荐）"),
    ALSA("alsa", "ALSA（pulse 桥）", "winealsa 经 alsa-plugins pulse 桥接入同一守护（兼容备选：pulse 驱动无声时换用）"),
    OFF("off", "禁用", "关闭容器音频（不出声也不拉起服务）");

    companion object {
        fun fromId(id: String?): ContainerAudio =
            entries.firstOrNull { it.id == id } ?: PULSE
    }
}

// ---------------------------------------------------------------------------
// v3.2.15：Windows 版本表（wineguanli.py update_windows_version_in_system_reg
// 的版本映射表移植 —— system.reg 的 CurrentVersion/ProductName/CurrentBuild）
// ---------------------------------------------------------------------------

/** 一个 Windows 版本的注册表三件套。 */
data class WindowsVersion(
    val id: String,
    val label: String,
    val currentVersion: String,
    val productName: String,
    val currentBuild: String
)

object WindowsVersions {
    val ALL: List<WindowsVersion> = listOf(
        WindowsVersion("win10", "Windows 10", "10.0", "Windows 10", "19045"),
        WindowsVersion("win81", "Windows 8.1", "6.3", "Windows 8.1", "9600"),
        WindowsVersion("win8", "Windows 8", "6.2", "Windows 8", "9200"),
        WindowsVersion("win7", "Windows 7", "6.1", "Windows 7", "7601"),
        WindowsVersion("vista", "Windows Vista", "6.0", "Windows Vista", "6002"),
        WindowsVersion("winxp", "Windows XP", "5.1", "Windows XP", "2600"),
        WindowsVersion("win2000", "Windows 2000", "5.0", "Windows 2000", "2195"),
        WindowsVersion("win98", "Windows 98", "4.1", "Windows 98", "2222"),
        WindowsVersion("win95", "Windows 95", "4.0", "Windows 95", "950")
    )
    val DEFAULT_ID = "win10"
    fun fromId(id: String?): WindowsVersion =
        ALL.firstOrNull { it.id == id?.trim()?.lowercase() } ?: ALL.first()
}

// ---------------------------------------------------------------------------
// v3.2.15：高级启动开关（wineguanli.py 基本设置勾选组的 LinBox 语义映射）
// ---------------------------------------------------------------------------

/**
 * 高级启动开关（container.conf 的 flags= csv 子集）。
 *
 * 语义映射（wineguanli.py → LinBox 原生启动链）：
 * - UNITY（Unity 单线程渲染）→ env BOX64_UNITYPLAYER=1（box64 官方开关）
 * - OPENGL（OpenGL 驱动）→ 保持记录；不强制 DXVK 时 wine 自走 wined3d/OpenGL
 * - F5TASK / IB / DEBUG / INDEPENDENT / CLOSESERVICE → 保留槽位，供用户
 *   自带的 /opt 生态脚本（startwine 类）读取 container.conf 时消费。
 */
object ContainerFlags {
    const val UNITY = "unity"
    const val OPENGL = "opengl"
    const val F5TASK = "f5task"
    const val IB = "ib"
    const val DEBUG = "debug"
    const val INDEPENDENT = "independent"
    const val CLOSESERVICE = "closeservice"

    /** v3.3.9（用户问题 2）：强制 DX11 —— startfex.sh/startwine.sh 的
     * `-x,--dx11` 同款：在 exe 之后追加 `-dx11` 参数（旧版误把 DEBUG
     * 槽位标成“强制DX11启动”，从未追加过任何参数）。 */
    const val DX11 = "dx11"

    /** FEX 模式（wineguanli.py 高级启动选项）。v3.4.2 语义（对齐参考
     *  源码 上游发行包 LaunchPlan：HODLL 是用户设置项，不默认注入）：
     *  勾选 → 注入 HODLL=libwow64fex.dll（dll 缺席则跳过）；不勾选 →
     *  不注入，由 wine 内部缺省（0010/0011 补丁：libwow64fex/
     *  libarm64ecfex）接管 —— fex 运行默认直接 wine exe。用户 env=/
     *  启动参数的显式 HODLL=/hodll= 值仍最高优先（大小写归一为大写键）。 */
    const val FEXMODE = "fexmode"

    /** wowbox64 模式。v3.4.2（用户：「wowbox64 模式别取消，加入选项，
     *  部分 32 游戏需要」）：FEXMODE 的姊妹档 —— 勾选 → 注入
     *  HODLL=wowbox64.dll（box64-in-wow64 32 位模拟层；dll 缺席则跳过）。
     *  上游发行包 的 HODLL 取值恰是这两个二选一
     *  （LaunchPlan.buildEnv:224-225 / WineConfigure.fexDllsInstalled:
     *  356-358：0=libwow64fex.dll，1=wowbox64.dll —— SettingKeys
     *  ContainerFEXHodll）。默认不注入同 FEXMODE；与 FEXMODE 同时勾选
     *  时 FEXMODE 优先（libwow64fex 在前）；显式 HODLL= 仍最高优先。 */
    const val WOWBOXMODE = "wowboxmode"

    /** 全部合法开关（UI 勾选组展示顺序）。 */
    val ALL = listOf(UNITY, OPENGL, F5TASK, IB, DEBUG, INDEPENDENT, CLOSESERVICE, FEXMODE, WOWBOXMODE, DX11)

    fun parse(csv: String?): Set<String> =
        csv?.split(',', ';').orEmpty()
            .map { it.trim().lowercase() }
            .filter { it in ALL }.toSet()

    fun serialize(flags: Set<String>): String =
        ALL.filter { it in flags }.joinToString(",")
}

// ---------------------------------------------------------------------------
// 容器数据模型
// ---------------------------------------------------------------------------

/**
 * 单个 Wine 容器的全部配置。
 *
 * 字段与 container.conf 的键一一对应；缺省值与 CLI create 相同，
 * 保证 App 侧新建的容器能被终端里的 linbox-container / linbox-wine
 * 直接读取，反之亦然。
 */
data class ContainerData(
    /** 容器名（目录名，同名字段冗余存储便于人工查看）。 */
    val name: String = "",
    /** CPU 翻译后端。 */
    val backend: ContainerBackend = ContainerBackend.AUTO,
    /** Box64 DYNAREC 预设（backend 为 box64 时生效）。 */
    val preset: Box64Preset = Box64Preset.PERFORMANCE,
    /** 虚拟分辨率："native"（跟随 X11 桌面）或 "宽x高"。 */
    val screen: String = DEFAULT_SCREEN,
    /** 显示模式（对齐 glibc-runner -d/-v/-f；screen=WxH 时生效）。 */
    val dmode: DisplayMode = DisplayMode.OFF,
    /** Mesa 渲染器强制项，"auto" 表示不干预。 */
    val gallium: String = DEFAULT_GALLIUM,
    /** 附加环境变量（空格分隔 K=V 列表，原样透传 linbox-wine）。 */
    val env: String = "",
    /**
     * DXVK 注入开关与版本戳（v3.2.14 语义）：
     * "off" = 明确关闭（不注入）；"auto" = 开启（前缀构建时自动解压
     * APK 内置 assets/dxvk/dxvk-<ver>.tar.gz 到 C:\\windows）；
     * 具体版本号（如 "2.3.1"）= 已注入的版本戳（注入器回写，仍视为开启）。
     */
    val dxvk: String = DXVK_AUTO,
    /** VKD3D-Proton 版本戳（"off" = 未安装）。 */
    val vkd3d: String = "off",
    /** win32.drv 替换（DLL 覆盖列表，DXVK/VKD3D 安装器会自动改写）。 */
    val dxoverrides: String = "",
    /** 指定 wine 版本目录名（空 = 用全局 default_wine → /usr/opt/wine）。 */
    val wine: String = "",
    /** 容器语言（v3.3.4：默认简体中文 zh_CN.UTF-8；"跟随 /opt/locale.conf"
     *  （auto）降为可选项，仅在显式选择时读取生态包 locale.conf）。 */
    val lang: String = ContainerLangs.DEFAULT,
    /** 容器音频模式（rootfs 自带 PulseAudio / 禁用）。 */
    val audio: ContainerAudio = ContainerAudio.PULSE,
    /** 创建时间（仅记录，人类可读）。 */
    val created: String = "",
    // ---- v3.2.15（wineguanli.py 重制）新增字段 ----
    /** 帧率限制（DXVK_FRAME_RATE）："0"=不限 / "60" / "120"。 */
    val frame: String = FRAME_DEFAULT,
    /** 主核心集（如 "0-3" 或 "0,1,2,3"）；空 = 不隔离，全核心。 */
    val cores: String = "",
    /** DirectSound DLL 加载序（user.reg dsound）。v3.3.8 三档：
     *  "default" = 跟随 /opt/prefix 安装包（不写注册表）；
     *  "builtin,native" / "native,builtin" = 显式覆盖（写入 DllOverrides）。 */
    val dsound: String = DLL_DEFAULT,
    /** DirectShow(quartz) DLL 加载序（user.reg quartz）。三档同 [dsound]。 */
    val dshow: String = DLL_DEFAULT,
    /** DPI（user.reg LogPixels），60..240。 */
    val dpi: String = DPI_DEFAULT,
    /** Windows 版本（system.reg 三件套）：见 [WindowsVersions]。 */
    val winver: String = WindowsVersions.DEFAULT_ID,
    /** MANGOHUD_CONFIG 串（fps,cpu_stats,...）；空 = MangoHud 关。 */
    val mangohud: String = "",
    /** 高级启动开关 csv（见 [ContainerFlags]）。 */
    val flags: String = "",
    /** Turnip 驱动版本槽位（对接 /opt/tz 包名，用户生态自行消费）。 */
    val turnip: String = "",
    /** Zink 驱动版本槽位（对接 /opt/zink）。 */
    val zink: String = "",
    /** WineD3D 版本槽位（对接 /opt/vkord3d）。 */
    val wined3d: String = "",
    /** v3.3.10：FEXCore 版本槽位（对接 /opt/fexcore 独立目录；逻辑同
     * DXVK —— 解压到对应容器 C 盘 windows，装 FEX 仿真器 PE DLL
     * libarm64ecfex.dll / libwow64fex.dll 等升级版）。 */
    val fexcore: String = "",
    /** v6.9（用户："加入一个vkd3d12的选项，用于选择d3d12的版本"）：
     * VKD3D12（D3D12→Vulkan）版本槽位（对接 /opt/vkd3d12；与 dxvk
     * 同链路 —— 运行前自动解压 tar 到对应容器 C 盘 windows）。 */
    val vkd3d12: String = "",
    /** v3.3.15：Wrapper 驱动版本槽位（对接 /opt/wrapper —— 用户驱动包
     * 为 rootfs 布局 usr/lib+usr/share 的预编译 Turnip/Adreno tar；按包
     * 结构直接落位 rootfs/usr/，同名 Mesa so 自动备份、卸载可恢复，
     * 自生成 Vulkan ICD 清单（宿主绝对路径）经 VK_ICD_FILENAMES 切换，
     * GL 默认联动 zink 吃 wrapper 加速，off 自动卸载恢复 Mesa）。 */
    val wrapper: String = "",
    /** v3.3.9（用户问题 2）：自定义启动参数（startfex.sh `-a/--args` 同款
     * 定位 —— 追加在 exe 参数末尾，空白切分；区别：-a 是 wine 前置参数，
     * 本字段是程序参数）。 */
    val uargs: String = "",
    /** v3.2.18：默认运行的程序（wineguanli.py custom_exe_path；空 = 运行时再选）。 */
    val exe: String = "",
    /** LinBox 移植新增：容器卡片自定义背景图（本地图片绝对路径；
     * 空 = 使用默认主题卡片）。首页容器卡片与容器管理器列表卡片的
     * 封面图，经文件管理器 pickMode=image 选择后回填。 */
    val cardImage: String = ""
) {
    /** WINEPREFIX 绝对路径（容器目录内 prefix/）。 */
    val prefixPath: String get() = "${ContainerManager.CONTAINERS_ROOT}/$name/prefix"

    /** 容器目录绝对路径。 */
    val containerDir: String get() = "${ContainerManager.CONTAINERS_ROOT}/$name"

    /** v3.2.15：flags csv → 开关集合。 */
    val flagSet: Set<String> get() = ContainerFlags.parse(flags)

    /** v3.2.15：是否启用指定开关。 */
    fun hasFlag(flag: String): Boolean = flag in flagSet

    /**
     * 以键值对形式导出（顺序与 CLI write_new_conf 一致），
     * 供 conf 序列化使用。
     */
    fun toSortedPairs(): List<Pair<String, String>> = listOf(
        "name" to name,
        "backend" to backend.id,
        "preset" to preset.id,
        "screen" to screen,
        "dmode" to dmode.id,
        "gallium" to gallium,
        "env" to env,
        "dxvk" to dxvk,
        "vkd3d" to vkd3d,
        "dxoverrides" to dxoverrides,
        "wine" to wine,
        "lang" to lang,
        "audio" to audio.id,
        "created" to created,
        // v3.2.15：wineguanli.py 重制新增键（未知键的老 CLI 忽略即可）
        "frame" to frame,
        "cores" to cores,
        "dsound" to dsound,
        "dshow" to dshow,
        "dpi" to dpi,
        "winver" to winver,
        "mangohud" to mangohud,
        "flags" to flags,
        "turnip" to turnip,
        "zink" to zink,
        "wined3d" to wined3d,
        "fexcore" to fexcore,
        "vkd3d12" to vkd3d12,
        "wrapper" to wrapper,
        "uargs" to uargs,
        "exe" to exe,
        "card_image" to cardImage
    )

    /** 序列化为 container.conf 文本（# 头注释 + key=value）。 */
    fun toConfText(): String = buildString {
        appendLine("# LinBox Wine 容器配置（App 端 / linbox-container 共用）")
        appendLine("# 键集与 linbox-container CLI 完全一致，勿随意手改")
        toSortedPairs().forEach { (k, v) -> appendLine("$k=$v") }
    }

    companion object {
        // v3.4.1【撤销 v3.4.0 A3 720p —— 用户明确指令】：新容器默认分辨率
        // 回归 native（全屏全物理分辨率）。用户原话：“主桌面分辨率不要动”
        // —— X11 运行时的性能压力不靠降桌面分辨率解决，靠 X server 前台
        // 主进程化（X11KeepAliveService 先于 app_process exec 启动，fork
        // 继承 top-app cgroup）+ 主桌面 launcher 式让位（DesktopPerfGovernor：
        // X11 真全屏期间任务栏/托盘/锁屏 1Hz 重组与自动锁屏轮询全部静止）。
        // v3.4.0 曾默认 1280x720 —— 视觉糊化 + 用户实测体验更差，撤销。
        const val DEFAULT_SCREEN = "native"
        // v3.4.1【A1 修正】：默认图形档回归 auto（v3.4.0 曾强制 zink ——
        // 有 DRM msm 渲染节点的设备上原本 freedreno 原生 GL 更快，一刀切
        // zink 反而降速，即用户实测“性能更差了”的供应商之一）。auto 语义
        // 升级为分层探测（见 WineEnvironment.applyGpuEnv v3.4.1）：
        //   dri+msm（Adreno DRM 可达）→ freedreno 原生 GL；
        //   kgsl / Vulkan ICD 可达    → zink+turnip（无 dri 权限设备的
        //                               GPU 加速主路 —— v3.4.0 A1 的本意）；
        //   均不可达                  → softpipe 兜底。
        const val DEFAULT_GALLIUM = "auto"

        /** v3.2.15：帧率限制/DPI 缺省值。 */
        const val FRAME_DEFAULT = "0"
        const val DPI_DEFAULT = "96"

        /** v3.3.8：DLL 加载序缺省档（跟随安装包，不写注册表）。 */
        const val DLL_DEFAULT = "default"
        val DLL_CHOICES: List<String> = listOf(DLL_DEFAULT, "builtin,native", "native,builtin")
        val FRAME_CHOICES: List<String> = listOf("0", "60", "120")
        val DPI_CHOICES: List<Int> = listOf(72, 96, 120, 144, 192)

        /** v3.2.14：DXVK 注入开关的开启值（用户需求：构建前缀时自动打入
         *  assets/dxvk 内的 dxvk-2.3.1.tar.gz）。 */
        const val DXVK_AUTO = "auto"
        const val DXVK_OFF = "off"

        /** 常用虚拟分辨率（"native" 表示跟随 X11 桌面窗口）。 */
        val SCREEN_CHOICES: List<String> = listOf(
            "native", "640x480", "800x600", "1024x768",
            "1280x720", "1280x800", "1366x768",
            "1600x900", "1920x1080", "2560x1440"
        )

        /** 从 container.conf 文件解析；文件不存在返回 null。 */
        fun fromConf(confFile: File): ContainerData? {
            if (!confFile.isFile) return null
            val kv = HashMap<String, String>()
            confFile.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEach
                kv[line.substring(0, eq)] = line.substring(eq + 1)
            }
            // v3.2.11（用户问题 1）：容器身份以【目录名】为准，conf 内 name= 仅是
            // 便于人工查看的冗余字段。旧逻辑 kv["name"] ?: 目录名 在 name= 为空值
            // 或与目录不一致时会产生空名/重名容器：空名容器在删除时
            // File(CONTAINERS_ROOT, "") 解析为容器根本身 → deleteRecursively()
            // 连根删光全部容器（用户实测“点三个点删一个容器结果所有容器都没了”）；
            // 重名容器则直接崩 LazyColumn 的 key 去重断言。目录名永远非空且唯一，
            // 从根上消除两类事故（update() 下次保存时会把 conf 内 name= 归一）。
            val dirName = confFile.parentFile?.name.orEmpty()
            return ContainerData(
                name = dirName.ifBlank { kv["name"].orEmpty() },
                backend = ContainerBackend.fromId(kv["backend"]),
                preset = Box64Preset.fromId(kv["preset"]),
                screen = kv["screen"] ?: DEFAULT_SCREEN,
                dmode = DisplayMode.fromId(kv["dmode"]),
                gallium = kv["gallium"] ?: DEFAULT_GALLIUM,
                env = kv["env"] ?: "",
                // v3.2.14：无键 = 未配置 → 默认开启（auto）；旧容器显式
                // dxvk=off 的保持关闭（用户明确选择）
                dxvk = kv["dxvk"] ?: DXVK_AUTO,
                vkd3d = kv["vkd3d"] ?: "off",
                dxoverrides = kv["dxoverrides"] ?: "",
                wine = kv["wine"] ?: "",
                lang = kv["lang"] ?: ContainerLangs.DEFAULT,
                audio = ContainerAudio.fromId(kv["audio"]),
                created = kv["created"] ?: "",
                // v3.2.15：wineguanli.py 重制键（缺键/非法值 → 缺省）
                frame = kv["frame"]?.trim()?.takeIf { it in FRAME_CHOICES } ?: FRAME_DEFAULT,
                cores = kv["cores"] ?: "",
                // v3.3.8：DLL 三档（default=跟随安装包；旧值 builtin,native
                // 为 v3.2.15-3.2.27 时代的默认 —— 归到跟随档，避免老容器
                // 无声写入注册表覆盖安装包）
                dsound = kv["dsound"]?.takeIf { it in DLL_CHOICES } ?: DLL_DEFAULT,
                dshow = kv["dshow"]?.takeIf { it in DLL_CHOICES } ?: DLL_DEFAULT,
                dpi = kv["dpi"]?.trim()?.toIntOrNull()?.coerceIn(60, 240)?.toString() ?: DPI_DEFAULT,
                winver = kv["winver"]?.takeIf { v -> WindowsVersions.ALL.any { it.id == v } } ?: WindowsVersions.DEFAULT_ID,
                mangohud = kv["mangohud"] ?: "",
                flags = kv["flags"] ?: "",
                turnip = kv["turnip"] ?: "",
                zink = kv["zink"] ?: "",
                wined3d = kv["wined3d"] ?: "",
                fexcore = kv["fexcore"] ?: "",
                vkd3d12 = kv["vkd3d12"] ?: "",
                wrapper = kv["wrapper"] ?: "",
                uargs = kv["uargs"] ?: "",
                exe = kv["exe"] ?: "",
                cardImage = kv["card_image"] ?: ""
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 体检结果模型（doctor）
// ---------------------------------------------------------------------------

/** 一条体检结论。 */
data class DoctorCheck(
    val ok: Boolean,
    val title: String,
    val detail: String
)

// ---------------------------------------------------------------------------
// Wine 多版本安装项（v2.26：/usr/opt/<wine|wine-名> 槽位）
// ---------------------------------------------------------------------------

/**
 * 一个已安装的 wine 版本槽位（ContainerManager.listWines 产出）。
 *
 * @property name      槽位名（目录名：wine 或 wine-9.8 等）
 * @property dir       绝对路径（/usr/opt/<name>）
 * v3.2.24：新增 kind 字段（.linbox-wine-info 的 kind=：
 * x86_64-wow64-x11 / arm64ec-split-wow64-x11 等，CI 配方实际写入的
 * 形态标识；选择器里作为副标题展示，用户能分清装的是哪个形态）。
 *
 * @property version   .linbox-wine-info 的 version→tag→kind 回退链
 * @property installed 安装时间戳
 * @property kind      构建形态标识（CI 配方 pre_package 写入）
 */
data class WineInstall(
    val name: String,
    val dir: String,
    val version: String = "",
    val installed: String = "",
    val kind: String = ""
) {
    /** 选择器展示文本（含版本号）。 */
    val displayLabel: String
        get() = if (version.isNotEmpty()) "$name（$version）" else name
}
