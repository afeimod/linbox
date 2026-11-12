# LinBox v3.0 — 原生 rootfs 启动架构（Native Launcher）

> 本文档描述 v3.0 的核心重构：App 对 rootfs 的调用与运行从「终端链路」
> （Termux PTY → bash → linbox-wine 脚本）完全重制为「原生链路」
> （Kotlin 编排 → ProcessBuilder 直接 exec rootfs 内的 wine 二进制）。
> 架构蓝本为 WinFusion 的原生编排模型，**显示方案保留 LinBox 的 X11**
> （termux-x11 / libXlorie），未引入 Weston / Wayland。

## v3.1 增补（实测修复轮）

v3.0 实测暴露出四处"伪原生"残留，v3.1 全部根除：

1. **X 服务拉起去脚本化（X11Support 重写）**：v3.0 的
   `ensureX11Service` 仍以 `$PREFIX/bin/sh $PREFIX/bin/termux-x11 :1`
   启动 X 服务 —— 本质还是"App 调脚本"，且漏设 `XDG_RUNTIME_DIR`
   （socket 落点不确定）、依赖 env 文件链、失败被静默吞掉（用户被迫
   回终端手敲 linbox-x11）。v3.1 直接以 ProcessBuilder 执行
   `/system/bin/app_process … com.termux.x11.CmdEntryPoint`，环境
   （CLASSPATH / LINBOX_X11_NATIVE_DIR / TERMUX_X11_OVERRIDE_PACKAGE /
   TMPDIR=XDG_RUNTIME_DIR / XKB_CONFIG_ROOT）全部由 Kotlin 构建，
   宿主 APK 路径从 `context.applicationInfo` 直取（升级自愈）；
   XKB 键盘数据解压改由新增的 `XkbExtractor`（纯 Kotlin tar.gz 解析）
   承接。陈旧 socket 文件以"本地套接字试连"探活、死文件自动清除
   （防 X 服务 bind 失败与 wine 连死端点）。
2. **CWD 跟随 exe（RootfsLauncher.resolveExeWorkingDir）**：v3.0 把
   wine 的工作目录写死为 rootfs/home —— 游戏按 CWD 相对路径找
   `media\` 等资源全挂（"找不到 media"报错的根因）。v3.1 依 exe
   路径形态解析工作目录：Unix 绝对路径取父目录；Windows 盘符路径经
   dosdevices 盘符链接映射回 Unix；相对路径维持 HOME（与终端一致）。
3. **dosdevices 兜底（WineRegistry.ensureDosdevices）**：启动链幂等
   补建 `z: → /` 与 `c: → drive_c` —— z: 缺失时 `/storage/…` 这类
   宿主路径在 wine 内整体不可达（绝对路径找资源的另一诱因）。
4. **音频孤儿 socket 自愈（PulseAudioManager）**：旧版 `socket 文件
   存在 → 直接复用`，存活校验永远执行不到 —— App 进程死亡留下的
   孤儿 socket 被直接交给 wine（winecfg 音频页无设备、游戏无声）。
   v3.1 复用前先试连验证；活实例无 socket 先 `-k` 再拉。配套：
   `NativeShellExecutor` 清洗键新增 `LD_LIBRARY_PATH`（防继承值污染
   Mesa/驱动符号解析；需要它的子进程全部白名单显式注入）。
   `WineRuntime` 的 `default_wine` 失效回退主槽位 + 错误信息带槽位名。

> 桌面「📦 Wine 容器」快捷方式的接入链路与排障见
> `docs/DESKTOP-CONTAINER.md`。

## v3.3.39 增补（conf 统一 + 启动/安装全链 App 内部化收官）

v3.0/v3.1 已把 wine 运行、X 服务、音频、前缀/注册表全部原生化；v3.3.39
清掉**最后两块非 App 内部的环节**，并完成 conf 体系统一：

1. **conf 统一到 APK 内部端（ApkConfStore，新增）**：cores/env/locale/
   DY/path 等 conf 的权威源迁到 App 私有存储
   `/data/data/com.linbox/files/linbox-conf/`（assets 种子 = 用户 opt.zip
   已知良好值）。旧版 `/opt` 生态包不存在 → conf 整页旁路
   （"未检测到 /opt 生态"）；现在 conf 编辑永远可用。三条数据流：
   - **种子**：assets → App 内部（仅缺失文件，用户数据永不覆盖）；
   - **一次性导入**：/opt 生态首次出现时其 conf 值覆盖进 App 内部
     （仅覆盖仍是种子原文的文件，UI 已改过的保持 App 值）；
   - **写透同步**：UI 每次写入 → App 内部 + /opt/conf 双写（终端链与
     生态脚本读到同一份）。
   启动链 `readOptConfs/readOptLocale` 改**合并读**：App 内部 + /opt
   生态**逐文件 mtime 新者胜** —— 终端侧直接改 /opt/conf 同样生效，
   容器管理与终端两侧的更改全部兼容。cores.conf 的 PRIMARY_CORES
   从此开箱即导出 → `applyTasksetAffinity` 钉大核不再依赖生态包。
2. **installWine 原生化**：旧版是管理链路最后一块 bash 残留（App 侧
   bash + linbox-container CLI install-wine）；现改为 Kotlin 原生
   WineOps 链（URL 下载 + tar 解包 + 默认槽位，tar 二进制 + Java
   回退双路径），与 UI 安装同一条代码路径。
3. **桌面「Wine 容器」→「Bionic 容器」**：命名对齐实际形态（id 保持
   wine-containers，桌面图标位置兼容）。
4. **终端链兜底（linbox-wine，assets 与 bionic-rootfs 两份同步）**：
   load_opt_confs 增补兜底候选 App 内部 linbox-conf/ —— /opt 生态
   全缺时终端链同样读到统一 conf（prefix/dxvk1 等生态资产按既有守卫
   静默跳过）。
5. **path.conf 双写（GuanliSupport.syncPathConfVkIcd）**：驱动选择同步
   VK_ICD_FILENAMES 时同时镜像进 App 内部权威源，防生态包移除后
   stale ICD 值成为唯一来源。

## 一、动机：终端链路的结构性限制

v2.x 中，App 启动 wine 的完整路径是：

```
WineSessionLauncher.spawn()
  → TerminalSession（Termux PTY 库 termux.c：fork + 80×24 伪终端）
    → App 侧 bash -c "exec linbox-container run <name> <exe>"
      → linbox-container（598 行 bash）→ linbox-wine（709 行 bash）
        → isolate_env 环境二次清洗 → audio_setup → ensure_x11
        → pick_backend → exec wine
```

wine 全家的 stdin/stdout/stderr 接在一个伪终端上，App 只能从 pty
transcript 里逐行"抠文本"当日志。该模型在代码注释中留有大量实证缺陷：

| 缺陷 | 证据（旧代码注释/实现） |
|---|---|
| stdio 管道化敏感 | `exec > >(tee …) 2>&1` 曾致 wineboot **SEH 崩溃**（v2.12 文件头"永久禁止"） |
| 环境串扰结构性风险 | `termux.c` clearenv 白名单重建；脚本仍需 `unset LD_PRELOAD`（"劫持 execve，wine/box64 必炸"）、强制重设 DISPLAY（"残留空/错 DISPLAY → 连错 X → 黑屏"）、重建 LD_LIBRARY_PATH（"Termux lib 污染 Mesa 符号解析"） |
| 驱动/显示协商靠 hack | 无 RandR → 注册表 EmulateModeset；分辨率经 `.linbox-x11-res` 文件握手 |
| 音频链路脆弱 | shell 拉起 pulseaudio，错误被 `>/dev/null 2>&1` 吞掉，轮询 + TCP 兜底后仍可能无声 |
| 进程管理文本级 | 停止 = `bash -c linbox-container stop`；清理 = `ps -ef \| grep \| awk \| xargs kill` |
| 就绪时序全靠轮询 | ensure_x11 20×0.5s、connectLorieView 500ms 轮询 fd |
| 日志通道原始 | pty transcript 二次提取，退出码从 waitpid 反查 |

## 二、原生链路（v3.0）

```
ContainerScreens（UI，零改动）
  → WineSessionLauncher（门面，API 兼容 v2.x）
    → RootfsLauncher.launch()（Kotlin 编排链，后台线程池）
        1. WineRuntime.resolve()        wine 目录（容器 wine= → 全局 default_wine= → /usr/opt/wine）
                                        + ELF e_machine 形态探测（aarch64/x86_64）
                                        + 后端选择（box64/arm64ec/hangover/fexcore/native/auto）
        2. WineEnvironment.ensureRuntimeDirs()   rootfs tmp / X11 socket / XDG / 前缀
        3. WineEnvironment.buildBaseEnv()        rootfs 环境白名单一次成型（见下）
        4. X11Support.ensureX11Window()          X 服务自动拉起 + 桌面窗口 + socket 显式等待（≤10s）
        5. WineServerUtils.stop()                清残留 wineserver（仅 freshSession 路径）
        6. GPU/Box64 环境叠加                     GALLIUM_DRIVER / BOX64_DYNAREC_* / ZINK_*
        7. PulseAudioManager.ensureServer()      原生音频守护管理 + PULSE_SERVER
        8. WineRegistry.initializePrefixIfNeeded()  首次 wineboot -i（宿主二进制直连）
        9. WineRegistry.ensureEmulateModeset()   无 RandR 显示模式虚拟化（幂等，marker 文件）
       10. applyDisplayMode()                    dmode=off/d/v/f + screen 握手文件 + -f 清蓝底
       11. WineEnvironment.applyExtraEnv()       用户 env= / dxoverrides（最后叠加，可覆盖一切）
       12. buildCommand() + NativeShellExecutor.exec()
                                            ProcessBuilder 直接 exec：
                                            [box64,] <wineDir>/bin/wine <exe> [args…]
                                            dmode=v 时插 explorer /desktop=LinBox,WxH
                                            wineserver/wineboot 特判直连宿主二进制
```

**环境白名单一次成型**（isolate_env 的 Kotlin 等价，wine 看到的环境与
终端会话完全无关）：

| 变量 | 值（与 linbox-wine v2.3 基线逐字对齐） |
|---|---|
| `PREFIX` / `TMPDIR` / `XDG_RUNTIME_DIR` | `<rootfs>/usr` / `<rootfs>/usr/tmp` |
| `HOME` | `<rootfs>/home` |
| `XDG_CONFIG_HOME` 等 | `<rootfs>/home/.config` 等三件套 |
| `LANG` | 容器 `lang=`（默认 zh_CN.UTF-8；不设 LC_ALL） |
| `LD_LIBRARY_PATH` | 仅 rootfs：usr/lib [:local/lib][:wineDir/lib][:opt/lib][:opt/fex/lib] |
| `PATH` | rootfs bin 优先 + /system/bin + App 前缀兜底 |
| `DISPLAY` | 强制 `:1`（LINBOX_DISPLAY 可覆盖） |
| `XKB_CONFIG_ROOT` / Mesa / GST | rootfs 自带一套（TU_DEBUG 仅 aarch64） |
| `WINEPREFIX` | 容器目录下 `prefix/` |
| `PULSE_SERVER` | unix socket / TCP 兜底 / 空（audio=off） |
| `GALLIUM_DRIVER` 等 / `BOX64_*` / `WINEDLLOVERRIDES` / 用户 `env=` | 按容器配置 |

同时剔除继承环境中的 `LD_PRELOAD`、`TERMUX_*`、旧日志变量 —— 串扰从
源头消失，不再依赖脚本二次清洗。

## 三、新模块一览（app/src/main/java/com/linbox/apps/containers/launcher/）

| 文件 | 职责 | 移植自 |
|---|---|---|
| `NativeShellExecutor.kt` | ProcessBuilder 封装：argv 传递 / 环境白名单 / stdout/stderr 行回调 / 退出回调 / destroyForcibly / 文件重定向；自管 cached daemon IO 线程池（commonPool 会饿死阻塞读） | WinFusion `core/shell/ShellExecutor.java` |
| `WineRuntime.kt` | wine 目录解析、ELF e_machine 架构探测、后端选择与校验、命令包装矩阵（box64/FEX 前缀、wineserver/wineboot 直连） | linbox-wine `resolve_wine_dir / detect_wine_arch / pick_backend / wine_run / run_wine` |
| `WineEnvironment.kt` | 环境白名单构建 + GPU/Box64/音频/用户附加叠加 + 运行时目录兜底 | linbox-wine `isolate_env / apply_gpu_env / apply_box64_env / apply_extra_env` |
| `PulseAudioManager.kt` | 音频守护：陈旧 pid 校验清理（防 "Daemon already running" 秒退）→ `--start --exit-idle-time=-1` → socket 轮询 → TCP 4713 兜底 | linbox-wine `audio_setup` + WinFusion `PulseAudioServer` 健壮性设计 |
| `WineServerUtils.kt` | wineserver `-k`（12s 上限，覆盖 server 内部 10.5s 兜底）/ `-w`（6s）整树收尾与注册表落盘 | WinFusion `feature/launcher/WineServerUtils.java` |
| `WineRegistry.kt` | `wine reg add/delete` 客户端通路：EmulateModeset（无 RandR 关键修复，marker 幂等）、-f 蓝底清理、wineboot -i 初始化 | linbox-wine `ensure_emulate_modeset / clear_wine_blue_desktop / run_wine 前缀初始化` |
| `ProcessCleanup.kt` | /proc 扫描（同 UID + cmdline 特征）精确击杀 wine 孤儿树；供 App 冷启动与关 X11 窗口两处使用 | WinFusion `utils/ProcessCleanup.java`，替代 `ps \| grep \| xargs kill` |
| `X11Support.kt` | X11 显示方案联动：X 服务自动拉起（原生执行器 + 日志文件重定向）、桌面窗口、socket 就绪显式等待 | WineSessionLauncher v2.x 平移 + ensure_x11 等待强化 |
| `WineSession.kt` | 会话模型（执行器 + 日志接收器）；`SessionLogSink`：内存滚动缓冲 + 追加式文件落盘 | 替代 headless TerminalSession + transcript 抠取 |
| `RootfsLauncher.kt` | 启动编排中枢（上节 12 步链）+ 会话/日志登记 + 停止链 + 世代令牌并发控制 | WinFusion `feature/launcher/Launcher.java` 编排模型 |

修改的既有文件：

| 文件 | 变更 |
|---|---|
| `WineSessionLauncher.kt` | 重制为纯门面（API 签名与 v2.x 兼容，全部转发 RootfsLauncher）；删除 PTY/TerminalSession/HeadlessClient 依赖 |
| `LinBoxApp.kt` | onCreate 增加 `ProcessCleanup.cleanup()`（后台线程）—— 冷启动清孤儿，根治"第一次能启动之后起不来" |
| `X11FitClient.kt`（X11Session） | `killWine()` 从 bash 文本管线改为 `ProcessCleanup.killWineProcesses()` |

## 四、关键行为语义

1. **runExe（运行程序）= 全新会话**：先 `wineserver -k/-w` 清残留
   （对齐 WinFusion launch 链与旧版"同名旧会话先回收"）。
2. **runWineCmd（winecfg/regedit/wineboot…）= 附加会话**：不清
   wineserver，附加到运行中的会话（对齐旧 linbox-container cmd 语义，
   不打断正在跑的程序）。
3. **停止容器**：先 destroyForcibly 收 wine 客户端，再 `wineserver -k`
   → `-w`，由 server 自己 `kill_all_processes()` 收掉整棵进程树
   （services.exe / winedevice.exe / explorer / 游戏）并落盘注册表。
4. **音频守护跨会话常驻**（`--exit-idle-time=-1`，与旧脚本一致）；
   启动前校验 pid 真伪，陈旧 pid/socket 清理后才拉起。
5. **并发启动**：同容器并发提交时世代令牌（最新胜出），旧链在 exec 前
   自检失配即放弃；"停止"同样使在途链失效。
6. **日志**：启动链每步决策行（带时间戳）+ wine stdout/stderr 逐行
   实时写入内存环形缓冲（UI"Wine 输出"面板）与会话日志文件
   （`/storage/emulated/0/Download/LinBox/` 或 `<rootfs>/…/containers/logs/`）。
7. **终端与脚本保留**：交互式终端（TermuxSessionController）与 rootfs 侧
   linbox-wine / linbox-container（App 启动时仍覆盖部署）完整保留供高级
   用户使用；App 的 wine 运行**不再依赖**它们。

## 五、与 WinFusion 架构的对齐 / 差异

| 环节 | WinFusion（Wayland） | LinBox v3.0（X11） |
|---|---|---|
| 子进程执行 | ShellExecutor（ProcessBuilder） | NativeShellExecutor（同型移植） |
| 显示 | Weston 编译进 libwinfusion.so 进程内 JNI 运行，wine 以 Wayland 客户端连回 | 保留 termux-x11/libXlorie X server（app_process 拉起），wine 经 DISPLAY=:1 + 构建期补丁过的 libxcb 直连 |
| 音频 | PulseAudio 前台子进程（App 持有句柄） | PulseAudio daemon 模式跨会话常驻 + 陈旧 pid 治理（对齐 LinBox rootfs 构建形态） |
| 进程生命周期 | wineserver -k/-w + /proc 孤儿清扫 | 同型移植（WineServerUtils + ProcessCleanup） |
| 前缀准备 | Configure 链（wineboot/注册表/驱动/DLL） | Kotlin 化的等价步骤（wineboot / EmulateModeset / 显示模式 / 用户环境） |

## 六、验证

新模块经独立编译单元（真实项目源码 + Android framework 最小桩面 +
kotlin-compiler 2.0.21）完成类型级编译验证：launcher 包全部 10 文件 +
WineSessionLauncher 门面 + ContainerModels / ContainerManager 编译零错误
零警告。UI 层（ContainerScreens）对门面的全部调用点（activeSessions.
containsKey / lastLogs / runExe / runWineCmd / stopContainer / installWine）
签名逐一核对兼容。
