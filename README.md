# LinBox

> Android 平台的 **Wine 容器管理应用**：以 bionic rootfs + Wine + Box64/FEX 为核心，
> 直接性的容器管理界面（容器卡片 / 选中容器信息 / 一键运行），并保留 Termux 终端、
> 内置 X11 显示端、浏览器与 DAC 直合显示栈。

## 项目简介

LinBox 是一个用 **Kotlin + Jetpack Compose** 编写的 Android 应用，由
[AnWind](https://github.com/afeimod/AnWind) 全量移植改造而来（同根项目，
包名 `com.linbox`）：

- **主页 = Wine 容器管理**：选中容器信息卡（容器名 / wine 版本 / 后端 /
  Windows 版本 / 分辨率 / 运行状态）+ 容器卡片列表（支持自定义卡片图片
  与 exe 内嵌图标），点 ▶ 一键运行，点 ⚙ 进入容器配置；
- **容器全部相关设置**：每容器五选项卡（基本 / 图形驱动 / CPU 核心 /
  函数库 / 更多配置）+ 全局高级设置（DY / FEX / Box64 / Wine 安装 /
  MangoHud / 生态配置）；
- **底部导航 [容器] [高级]**：高级页汇集终端、浏览器、容器高级设置、
  容器体检、文件管理器、X11 / DAC 显示器、应用设置；
- **窗口化容器界面**：容器管理器 / exe 选择器 / 浏览器 / 文件管理器
  以可拖拽缩放的浮动窗口形式展示；
- **bionic rootfs 全量体系**：rootfs 以 `.tzst` 放在 `assets/linbox/`，
  启动自动解压到 `/data/data/com.linbox/files/rootfs`（tzst 内部目录为
  `rootfs/`，亦兼容 CI data/data 布局与相对布局）；
- **完整构建链**：`bionic-rootfs/` 70+ 包配方（rootfs / wine / mesa /
  box64 / X11 栈 / glibc 层）+ 四条 CI 工作流；
- **真实 Termux 终端 + 内置 X11（:13）+ DAC 直合显示**：linbox 自有栈
  保留，叠加 AnWind 的 X11 性能优化（X server 前台主进程化、点击置前、
  窗口轮转、PerfGovernor 让位）。

## 核心特性

- **容器管理** — 创建 / 克隆 / 重命名 / 删除 / 设默认容器 / 容器体检
  （rootfs/X11/wine/box64/FEX 探活）；容器与终端 CLI（`linbox-container`）
  完全共享数据；
- **多 Wine 版本管理** — `/usr/opt/wine*` 多槽位，本地 tar / URL 下载
  安装，每容器可选 wine 版本；
- **多种运行后端** — ARM64EC 原生 / Box64 / Hangover / FEXCore / Native
  自动或手动选择；Box64 DYNAREC 四档预设；
- **图形驱动** — Turnip / Freedreno / Zink / Softpipe 分层探测，
  DXVK 自动注入（assets/dxvk），Vulkan Wrapper 驱动栈（AdrenoTools），
  `/opt` 生态包（驱动 tar / 前缀模板 / 时区 / locale）；
- **X11 显示（wine 容器）** — 容器启动链自动 `app_process` 拉起 X server
  （:1），前台服务先行钉住 cgroup（性能关键），广播自动上屏；
- **浏览器** — 多标签 / 书签 / 历史（Room），桌面 UA，file:// 与 http(s)，
  Flash（Ruffle，含 4399 预载），网页 3D 视角，双指缩放；
- **exe 内置图标识别** — PE 内嵌图标提取（PeIcon：RT_GROUP_ICON →
  RT_ICON → PNG/DIB 全 bpp），容器卡片 / 文件管理器缩略图直接显示；
- **卡片自定义图片** — 容器卡片背景可选本地图片（container.conf 的
  `card_image` 键，文件管理器选择回填）；
- **输入全家桶** — 虚拟键盘 / 鼠标 / 触控板 / 游戏手柄（悬浮球常驻 +
  .vgp 布局存取）；
- **离线 bootstrap 安装** — 内置 aarch64 离线归档，安装期同长度重写
  `com.termux → com.linbox` 路径前缀。

## 架构

```
app/src/main/java/com/linbox/
├── LinBoxApp.kt / MainActivity.kt     # 入口：Room DB + 容器应用注册 + tzst 铺装 + 孤儿清扫
├── shellui/                            # 新壳层：容器主页 + 高级页 + 底部导航
│   ├── ContainerHomeScreen.kt          #   主页：选中容器信息卡 + 容器卡片列表
│   ├── AdvancedScreen.kt               #   高级页：终端/浏览器/容器设置/体检/文件/X11/DAC
│   └── ShellBottomBar.kt               #   底部导航 [容器] [高级]
├── core/shell/                         # ShellController 页面路由（HOME/ADVANCED/TERMINAL/X11/DAC/SETTINGS）
├── core/window/                        # 浮动窗口系统（拖拽/缩放/最小化/全屏 + WinDialog 弹窗栈）
├── apps/containers/                    # 【AnWind 移植】容器管理
│   ├── ContainerManager/Models         #   容器 CRUD + container.conf 编解码（30+ 键，含 card_image）
│   ├── ContainerScreens.kt             #   wineguanli.py 全量 Compose 移植（五选项卡/高级页/全部弹窗）
│   ├── WineSessionLauncher.kt          #   门面 → RootfsLauncher 13 步原生启动链
│   └── launcher/                       #   RootfsLauncher / WineEnvironment / WineRegistry / WineServerUtils
│                                       #   X11Support / PulseAudioManager / NativeShellExecutor /
│                                       #   WrapperDriverSupport / WineLibrary / WineLocaleSupport …
├── apps/browser/                       # 【AnWind 移植】浏览器（多标签/书签/Flash/3D 视角）
├── apps/filemanager/                   # 【AnWind 移植】文件管理器 + PeIcon（exe 图标）+ FilePickBus
├── apps/terminal/                      # linbox 自有 Termux 终端 + LinBoxTzstAssets（rootfs 自解压）
│                                       #   + LinBoxScriptAssets（rootfs 侧脚本部署）
├── apps/x11/                           # linbox 自有 X11 显示端 + X11WindowOps（点击置前/切窗）
├── apps/dac/                           # linbox 自有 DAC 直合显示栈（winedac.drv）
├── apps/settings/                      # 应用设置（显示/终端背景/输入/手柄/开发者/关于）
├── core/input/                         # 虚拟键盘/鼠标/触控板/手柄（含悬浮球 GamepadFloatingBall）
├── core/theme/                         # Windows 五代窗口主题
├── data/                               # Room（书签/历史/快捷方式）+ SettingsStore
└── termux/                             # termux-app 移植（终端模拟器 + 视图，Apache-2.0）
```

```
仓库根目录
├── bionic-rootfs/       # 【AnWind 移植】bionic rootfs 构建体系（70+ 包配方）
│   ├── projects/        #   wine / hangover-wine / mesa / box64 / pulseaudio / X11 全家 / glibc 层 …
│   ├── ndk_patches/     #   NDK r28b/r29 sysroot 补丁
│   ├── build-rootfs-arch.sh / make-rootfs.sh   # 逐包构建 + 组装导出
│   └── default.conf     #   prefix=/data/data/com.linbox/files/rootfs/usr
├── wine/ mesa/ dxvk/    # linbox 自有 DAC 显示栈构建（winedac.drv / turnip / dxvk）
├── termux-x11/          # X server 显示端（lorie + Xwayland 预编译库）
├── scripts/             # 【AnWind 移植】kt_struct_check / msgfmt 等开发工具
└── docs/                # 容器/原生启动链/GPU 驱动/TERMUX/X11/DAC 文档
```

## rootfs 生命周期

```
【构建】 bionic-rootfs/build-rootfs-arch.sh --arch aarch64 --build all
         → make-rootfs.sh 组装 → CI (build-bionic-rootfs.yml) tar.gz
【打包】 rootfs tar.gz 重压缩为 zstd → assets/linbox/rootfs-<date>-aarch64.tzst
         （tzst 内部目录 rootfs/，或 CI 的 data/data/com.linbox/files/rootfs/ 布局）
【安装】 App 启动后台线程 LinBoxTzstAssets.installAllIfNeeded：
         指纹幂等（usr/etc/linbox/.tzst-assets-fingerprint）→
         TarCompressorUtils 解压 → remapToRootfs 前缀剥壳 → /data/data/com.linbox/files/rootfs
【配套】 mesa-*.tzst / box64-*.tzst / turnip-*.tzst 同通道自动解压；
         wrapper-stack.tzst（Vulkan 驱动栈）按需安装；
         dxvk-*.tar.gz 仅对已构建前缀注入 drive_c/windows；
         linbox/scripts（linbox-container / linbox-wine CLI）每次启动覆盖部署到 rootfs/usr/bin
【Wine】 不进 assets —— 容器管理器「Wine 安装」页本地 tar / URL 安装到 /usr/opt/<槽位>
```

## 构建

```bash
# APK（需要 JDK 17 + Android SDK 34 + NDK 26.3.11579264）
./gradlew assembleRelease

# bionic rootfs（CI：.github/workflows/build-bionic-rootfs.yml）
cd bionic-rootfs && ./host-deps.sh && ./build-rootfs-arch.sh --arch aarch64 --build all

# wine / mesa / box64（CI：build-wine-bionic.yml / build-mesa-bionic.yml / build-box64-bionic.yml）
```

约束：`targetSdk` 锁 28（Android 10+ SELinux 禁止 exec 数据目录二进制，
Termux 同款硬约束）；minSdk 24；Kotlin 1.9.25 + Compose BOM 2024.06。

## 文档

- [docs/CONTAINERS.md](docs/CONTAINERS.md) — 容器创建 / 启动 / 前缀构建全流程
- [docs/NATIVE-LAUNCHER.md](docs/NATIVE-LAUNCHER.md) — 原生启动链（去终端化）架构
- [docs/SYSTEM-GPU-DRIVERS.md](docs/SYSTEM-GPU-DRIVERS.md) — 系统 GPU 驱动 / Vulkan
- [docs/WINEGUANLI-OPT-FOLDERS.md](docs/WINEGUANLI-OPT-FOLDERS.md) — /opt 生态目录
- [docs/TERMUX.md](docs/TERMUX.md) — Termux 终端移植
- [docs/X11.md](docs/X11.md) — X11 显示端
- [docs/DAC.md](docs/DAC.md) — DAC 直合显示栈

## 许可

Termux 移植部分（`com.linbox.termux`、`apps/terminal`）遵循 Apache-2.0
（见 [docs/LICENSE-APACHE-2.0.txt](docs/LICENSE-APACHE-2.0.txt)）。
