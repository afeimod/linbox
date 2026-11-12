# LinBox Wine 容器系统 — 接入与全流程说明

> 本文档说明桌面快捷方式（"📦 Wine 容器"）到 rootfs 原生启动链的完整接入，
> 覆盖四个关键环节：**创建容器 / 启动容器 / 构建前缀 / wine 读取位置**。
> 原生启动链（去终端化）的架构细节见 `docs/NATIVE-LAUNCHER.md`。

## 〇、v3.1 修复清单（本轮实测问题对应）

| 实测症状 | 根因 | 修复（代码位置） |
| --- | --- | --- |
| 仍需终端跑 `linbox-x11` 才有画面 | X 服务拉起仍经 `sh bin/termux-x11` 脚本，且缺 `XDG_RUNTIME_DIR`（socket 位置不定）、失败被静默吞掉 | `X11Support` 重写：直接 `app_process … com.termux.x11.CmdEntryPoint` 拉起，环境全由 Kotlin 构建（CLASSPATH/nativeDir 从 Context 直取），决策行进会话日志 |
| winecfg 拿不到音频 | `PulseAudioManager` 在函数开头 `socket 存在→直接返回`，孤儿 socket 被直接交给 wine（死端点） | 复用前先本地套接字试连验证，连不上即清理重拉；活实例无 socket 先 `-k` 再拉 |
| `CubeMap.exe 找不到 media` | wine 的 CWD 写死为 rootfs/home，DX9 等按 CWD 相对路径找 `media\` 永远找不到 | `RootfsLauncher.resolveExeWorkingDir`：Unix 路径取 exe 父目录、Windows 盘符路径经 dosdevices 映射，CWD 跟随 exe |
| 游戏内 Z:\storage 路径失效 | 前缀缺 `dosdevices/z:` 时宿主路径在 wine 内不可达 | 新增 `WineRegistry.ensureDosdevices`：每次启动幂等补建 `z:→/`、`c:→drive_c` |
| 改名/卸载 wine 后容器全灭 | `default_wine` 指向的槽位失效时报错且无提示槽位名 | `WineRuntime`：default_wine 失效时回退主槽位；错误信息带具体槽位名 |
| 环境串扰残留 | 继承的 `LD_LIBRARY_PATH` 可能污染 Mesa/驱动符号解析 | `NativeShellExecutor` 清洗键新增 `LD_LIBRARY_PATH`（需要它的子进程全部白名单显式注入） |

## 一、桌面快捷方式的注册与渲染

```
LinBoxApp.onCreate
→ AppBootstrap.registerAll()
→ AppRegistry.register(ContainersApp)          # apps/containers/ContainerApp.kt
→ 桌面经 AppRegistry.desktopApps() 渲染图标    # pinnedToDesktop = true
→ 点击图标 → LaunchMode.FLOATING 浮窗（560×620）
→ ContainerListContent(scope)                  # apps/containers/ContainerScreens.kt
```

`ContainerApp.kt` 的注册参数：

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `id` | `wine-containers` | 全局唯一标识 |
| `displayName` | `Wine 容器` | 桌面图标名 |
| `iconAsset` | `emoji:📦` | emoji 图标，免新增资源 |
| `pinnedToDesktop` | `true` | **桌面快捷方式**（每次启动都在桌面出现） |
| `pinnedToTaskbar` | `true` | 任务栏固定 |
| `launchMode` | `FLOATING` | 浮动窗口，可与 X11 桌面窗口并排 |

UI 层（`ContainerScreens.kt`）对启动链的调用**全部经过
`WineSessionLauncher` 门面**，方法签名与 v2.x 逐字兼容：

| UI 动作 | 门面调用 | 最终去向 |
| --- | --- | --- |
| 运行 exe | `runExe(ctx, container, exe, args)` | `RootfsLauncher.runExe`（全新会话） |
| winecfg / regedit / wineboot -i | `runWineCmd(...)` | `RootfsLauncher.runWineCmd`（附加会话） |
| 停止 | `stopContainer(ctx, name)` | destroyForcibly + `wineserver -k/-w` |
| 列表"运行中"标记 | `activeSessions.containsKey(name)` | 会话表 + 启动中占位 |
| 查看输出 | `lastLogs[name]` | 原生链决策行 + wine 实时输出 |
| 一键装 wine | `installWine(ctx, 类别)` | 原生执行器跑 CLI install-wine |

## 二、创建容器（桌面 → rootfs 存储）

桌面"新建容器"弹窗 → `ContainerManager.create(ContainerData)`：

1. 校验容器名（`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`，防路径穿越）；
2. 在 rootfs 内落盘目录结构（与终端 CLI `linbox-container create` 等效）：

```
<data>/com.linbox/files/rootfs/home/.linbox/containers/<name>/
├── container.conf     # 键集与 CLI 逐字对齐（name/backend/preset/screen/
│                      #   dmode/gallium/env/dxvk/vkd3d/dxoverrides/
│                      #   wine/lang/audio/created）
├── prefix/            # WINEPREFIX（首次运行时由 wineboot -i 填充）
└── drive_c/           # 兼容旧布局占位
```

3. **双端同源**：App 写的 `container.conf` 终端 CLI 直接可读，终端建的
   容器 App 列表直接可见；全局默认容器存
   `<rootfs>/usr/etc/linbox/container.conf` 的 `default_container=`，双向生效。

克隆 = 整目录 `copyRecursively` + 改写 conf 的 `name=`（前缀一并复制，
免二次 wineboot）；删除 = 先静默停会话（`stopContainerQuietly`，防文件
占用）再递归删除，若删的是默认容器则同步清除全局指针。

## 三、启动容器（桌面"运行" → 原生 12 步链）

`RootfsLauncher.launch()` 在后台线程池执行（绝不占主线程）：

```
 1. WineRuntime.resolve()             wine 目录 + ELF 形态 + 后端（见下节）
 2. WineEnvironment.ensureRuntimeDirs rootfs tmp / X11 socket 目录 / XDG / 前缀
 3. WineEnvironment.buildBaseEnv      环境白名单一次成型（PREFIX/HOME/TMPDIR/
                                       XDG_*/LD_LIBRARY_PATH/LANG/DISPLAY=:1…）
    + WINEPREFIX=<容器目录>/prefix
 4. X11Support.ensureX11Window        原生拉起内置 X 服务（v3.1：app_process
                                       直启，不经 sh/脚本）+ 打开 X11 桌面窗口
    X11Support.awaitSocketReady       显式等 socket 可连（≤10s，防"X 未就绪
                                       先连"黑屏；死 socket 文件会被清理）
 5. WineServerUtils.stop              清残留 wineserver（仅 freshSession 路径，
                                       "第一次能启动之后起不来"的根治步骤）
 6. GPU/Box64 环境叠加                GALLIUM_DRIVER / BOX64_DYNAREC_* / ZINK_*
 7. PulseAudioManager.ensureServer    原生音频守护 + PULSE_SERVER（audio=off 跳过；
                                       v3.1：复用前试连验证，孤儿 socket 自愈）
 8. WineRegistry.initializePrefixIfNeeded   首次自动 wineboot -i（见下节）
    WineRegistry.ensureDosdevices     v3.1：dosdevices 盘符兜底（z:→/、c:→drive_c）
 9. WineRegistry.ensureEmulateModeset       无 RandR 显示模式虚拟化（幂等）
10. applyDisplayMode()                dmode=off/d/v/f + .linbox-x11-res 握手
11. WineEnvironment.applyExtraEnv     用户 env= / dxoverrides（最后叠加可覆盖）
12. buildCommand() + NativeShellExecutor.exec   ProcessBuilder 直接 exec
    resolveExeWorkingDir()            v3.1：CWD 跟随 exe 所在目录（media 等
                                       相对路径可解析；无法解析时维持 HOME）
```

第 12 步的 exec 目标（`WineRuntime.wineCommand`）：

| 后端 | 实际执行 |
| --- | --- |
| box64 | `[box64, <wineDir>/bin/wine, <exe>, …]` |
| fexcore | `[FEXInterpreter, <wineDir>/bin/wine, …]` |
| arm64ec / native | `[<wineDir>/bin/wine, …]` |
| hangover | `[/usr/opt/bin/wine, …]`（rootfs 内置旧版兜底） |

`wineserver` / `wineboot` 子命令走宿主二进制直连（同目录取
`bin/wineserver` / `bin/wineboot`，x86_64 形态仍经 box64/FEX 包装），
不经过 wine 客户端准备流程 —— 对齐旧脚本 `run_wine` 特判。

**会话语义**：
- `runExe` = 全新会话（先 `wineserver -k/-w` 清残留再启动）；
- `runWineCmd`（winecfg/regedit/wineboot…）= 附加会话（不打断运行中的程序）；
- 停止 = 先 `destroyForcibly` 收 wine 客户端，再 `wineserver -k` → `-w`
  让 server 自己 `kill_all_processes()` 收整棵树并落盘注册表；
- 并发启动同容器时世代令牌最新胜出，"停止"使在途链失效（exec 前自检）。

## 四、构建前缀（首次自动 wineboot -i）

前缀 = 容器目录下 `prefix/`（WINEPREFIX）。构建时机与通路：

1. **自动（推荐）**：第 8 步 `initializePrefixIfNeeded` —— 检测
   `prefix/system.reg` 不存在时，同步执行 `wineboot -i`（宿主二进制
   直连，分钟级，日志实时进"Wine 输出"面板）。此后 `EmulateModeset=Y`
   注册表写入（无 RandR 环境显示模式虚拟化的关键修复，marker 文件幂等）。
2. **手动**：容器卡片菜单"初始化前缀 (wineboot -i)" → `runWineCmd`
   附加会话 —— 前缀已在时重建（wineboot 自动增量更新）。
3. **DXVK 补充**：进入容器界面时 `LinBoxTzstAssets.applyDxvkWhenPrefixReady`
   自动把 APK 内置 dxvk 资产解压进已构建的前缀（未构建则跳过）。

前缀构建后：`prefix/drive_c/windows/system32/…`、`system.reg`、
`user.reg`、`userdef.reg` 齐备，winecfg/regedit/安装程序即可正常工作。

## 五、wine 读取位置（解析顺序与路径契约）

**这是原生链的第一步（`WineRuntime.resolve`），三级解析、双端同源：**

```
容器 container.conf 的 wine=<值>
  ├─ 绝对路径（/ 开头）      → 直接用该目录
  └─ 槽位名                  → <rootfs>/usr/opt/<槽位名>
       ↓（wine= 为空时）
全局 <rootfs>/usr/etc/linbox/container.conf 的 default_wine=<值>
  ├─ 槽位名（CLI install-wine --default 写入的就是槽位名）
  └─ 绝对路径（Kotlin 端比脚本更宽容，直接用）
       ↓（default_wine 为空时）
主槽位兜底                   → <rootfs>/usr/opt/wine
```

路径常量（`ContainerManager`，与 rootfs 侧脚本逐字对齐）：

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `ROOTFS_ROOT` | `/data/data/com.linbox/files/rootfs` | bionic rootfs 根 |
| `WINE_OPT_DIR` | `<rootfs>/usr/opt` | wine 多版本槽位根 |
| 主槽位 | `<rootfs>/usr/opt/wine/bin/wine` | 无指针时的兜底 |
| 多版本槽位 | `<rootfs>/usr/opt/wine-<名>/bin/wine` | `install-wine --name` 安装 |
| 旧版 hangover | `<rootfs>/usr/opt/bin/wine` | rootfs 打包内置 aarch64 兜底 |
| FEX 解释器 | `<rootfs>/usr/opt/fex/bin/FEXInterpreter` | fexcore 后端用 |

**解析后的三重校验**（全部通过才进入启动链）：
1. 目录存在（`isDirectory`）；
2. `bin/wine` 主二进制存在（`isFile`）；
3. ELF 头 `e_machine`（偏移 18，小端 2 字节）探测形态 ——
   `B7 00` = aarch64（arm64ec 构建）、`3E 00` = x86_64（经 box64/FEX）——
   并据此 + 设备架构 + 组件可用性推导后端（auto 时）。

**同一 Resolved 贯穿全会话**：`wine` / `wineserver` / `wineboot` /
`wine reg`（EmulateModeset、蓝底清理）全部取自**同一 wine 槽位目录**，
不会出现主程序用 A 槽而 wineserver 用 B 槽的错位。

**wine 槽位由谁管理**：
- 终端：`linbox-container install-wine <tar.gz|--name <槽位>|--default>`、
  `wine-list` / `wine-default` / `wine-remove`；
- App：容器管理窗的"Wine 版本（/usr/opt 槽位）"选择器 + 分类一键在线
  安装（`RootfsLauncher.installWine` → 原生执行器跑 CLI，不经 PTY，
  进度进 `lastLogs["__install__"]`）。
- 每槽位 `.linbox-wine-info` 记录 version/installed，App 选择器与终端
  `wine-list` 同源读取。

## 六、X11 画面链路（v3.1 重点：不再依赖终端）

从 App 运行 exe/winecfg 时，X 服务由启动链自动拉起，**用户无需先在终端
跑 `linbox-x11`**：

```
RootfsLauncher.launch() 第 4 步
→ X11Support.ensureX11Service()
   ├─ socket 可连？→ 服务已在跑，直接复用
   ├─ socket 文件残留但连不上？→ 删除（否则 bind 报 Address already in use）
   ├─ linboxx11 进程存活？→ 冷启动进行中，等待
   └─ XkbExtractor 解压 XKB 键盘数据（脚本 tar -xzf 的原生替代）
      ProcessBuilder 直接 exec：
      /system/bin/app_process -Xnoimage-dex2oat / \
          --nice-name=linboxx11 :1 com.termux.x11.CmdEntryPoint :1
      环境：CLASSPATH=<宿主APK> LINBOX_X11_NATIVE_DIR=<nativeLibraryDir>
            TERMUX_X11_OVERRIDE_PACKAGE=com.linbox
            TMPDIR=XDG_RUNTIME_DIR=<App前缀>/tmp（socket 落点保证）
            XKB_CONFIG_ROOT=<etc/linbox/x11/xkb>
→ X 服务起来后每秒向 com.linbox 广播 ACTION_START（自带 binder）
→ LinBoxApp 动态接收器 → X11WindowController.onBroadcastReceived
→ LinBox 桌面内自动弹出 "X11 桌面" 浮动窗口
→ LorieView 取 fd 接管渲染 —— wine 的画面出现在窗口里
```

与终端方式（`linbox-x11` → `bin/termux-x11` 脚本 → app_process）**最终
命令与环境完全同源**，但中间不再有 sh / 脚本 / env 文件任何一环；宿主
APK 路径直取 `context.applicationInfo.sourceDir`，APK 升级后自愈。

## 七、排障速查

| 症状 | 检查点 |
| --- | --- |
| 桌面无图标 | `AppBootstrap.registerAll()` 是否注册 `ContainersApp` |
| 新建容器报错 | 容器名规则 / rootfs 已导入（`rootfsReady()` = CLI 文件存在） |
| 运行报"未找到 wine" | 错误信息已带具体槽位名；`linbox-container wine-list` 或 App 选择器确认槽位 |
| 首启黑屏 / 无 X11 窗口 | 会话日志看 X 服务决策行；`cat <App前缀>/tmp/linbox-x11-autostart.log` |
| winecfg 音频页无设备 | 会话日志看 PulseAudio 决策行（陈旧 socket 清理 / 重拉 / TCP 兜底） |
| 游戏报"找不到 media" | 确认以 exe 完整路径启动（CWD 会跟随 exe）；日志看"工作目录=exe 所在目录"行 |
| Z:\ 路径不可达 | 会话日志看"已补 dosdevices z:"；`ls <前缀>/dosdevices/` |
| 二次启动失败 | 会话日志看 `wineserver -k/-w` 步骤；冷启动孤儿清扫是否执行 |
| 显示模式枚举失败 | EmulateModeset marker：`<前缀>/.linbox-emulate-modeset` |
