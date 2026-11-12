# wineguanli.py 完全重制 — /opt 文件夹清单（v3.2.18）

按你提供的 wineguanli.py（6357 行 PyQt5）**完全重制**了桌面「Wine 容器」应用：
工具栏 + 左右分栏 + 五选项卡 + 高级设置页（DY 配置/Box 配置/Wine 安装/MANGOHUD）
+ 更多设置对话框（CPU 核心/函数库/环境变量/高级功能/Direct3D）+ 新建容器/
MANGOHUD/多选/快捷方式对话框 —— wineguanli.py 的每一个界面在 LinBox 里都有
对应物。**所有宽度可用**：界面按窗口宽度等比缩放（1024dp 基准），窄屏自动
缩小、放不下时横向滑动，不再有"窄屏回落旧卡片"的分支。

## 一、LinBox 已原生实现（无需 /opt）

| wineguanli.py 功能 | LinBox 实现（v3.2.18） |
| --- | --- |
| 主窗口工具栏（☰ 菜单/标题/创建容器/快捷方式） | GuanliToolbar（QToolBar 同款深色 #2c3e50） |
| 容器列表（双击运行/右键菜单/切换语言） | 左列列表：点击选中、长按弹菜单（运行/安装前缀/设置/切换语言/删除/重命名/快捷方式） |
| 新建容器对话框（名称/架构/Wine版本/分辨率/语言/帧数限制） | NewContainerDialog 逐字段移植，创建后询问是否立即装前缀 |
| 基本设置选项卡 | 容器名称/🏗️架构/Wine版本/🖥️分辨率(可自定义)/🌐语言/⏱️帧数限制/窗口模式/EXE文件(浏览回填)/高级启动选项七勾选 |
| 图形驱动选项卡 | Turnip(/opt/tz)、Zink(/opt/zink)、DXVK(/opt/dxvk1)、WineD3D(/opt/vkord3d)、FEXCore(/opt/fexcore)、Wrapper(/opt/wrapper) 下拉自动扫描 tar 包；运行前自动解压选中包 |
| CPU 核心选项卡 | 真实核心数勾选网格 + 主/副核心信息 + 说明（cores=，PRIMARY_CORES/SECONDARY_CORES 注入） |
| 函数库选项卡 | dsound/dshow 下拉 + DPI 滑块与五预设 + Windows 版本 + 说明 + 💾立即应用（regedit 写前缀） |
| 更多配置选项卡 | 说明 + ⚙️打开更多设置 + 当前配置状态 + 🔄刷新 |
| DY 配置（DynarecEditor） | 高级设置页 ⚡DY配置：兼容/性能/平衡三预设（9 变量逐字移植）+ 变量列表增删 + 应用（容器 env=） |
| Box 配置 | 高级设置页 📦Box配置：/opt/box、/opt/box86 扫描 + box64 -v 探活 + 应用（解压 /usr/local） |
| Wine 安装 | 高级设置页 🍷Wine安装：本地文件/网络链接安装到 /usr/opt 槽位 + 列表/设默认/删除 |
| MANGOHUD 配置对话框 | 📊MANGOHUD配置：八项指标/横屏/字体大小(10-36)/位置(四角)/背景透明度/无显示模式 → mangohud= |
| 环境变量选项卡 | MESA版本/GLSL版本/VKD3D特性下拉 + MESA扩展多选 + MANGOHUD开关 + DXVK HUD多选 + TU_DEBUG多选（全落 env=） |
| 高级功能（CNC DDraw） | 应用/清除 → wine AppDefaults 注册表（等效复制 DLL 方案，不动用户文件） |
| Direct3D 选项卡 | 强制D3D9/D3D11 + 清除 + 目标EXE → AppDefaults\<exe>\DllOverrides |
| 快捷方式 | ShortcutManager 固定到 Android 启动器；点按直达容器（已配 exe 时自动运行） |
| 高级启动选项（7 勾选） | flags= csv（closeservice/debug/ib/fexmode/f5task/unity/opengl/independent） |
| 独立窗口模式 | 独立窗口勾选 + 分辨率 → dmode=F（-f）；非独立 + WxH → dmode=D（-d） |

## 二、需要你准备的 /opt 文件夹（可选：驱动/box tar 包生态）

放进 rootfs（`/data/data/com.linbox/files/rootfs/opt/...`）即可在图形驱动/
Box 配置页被扫描到（下拉自动出现），运行时自动解压：

```
/opt/
├── box/                       # box64 tar 包（*.tar.gz/xz/bz2 → 解到 /usr/local/bin）
├── box86/                     # box86 tar 包（同上）
├── tz/                        # Turnip 驱动 tar 包（Mesa 型 → 解到 /usr/lib）
├── zink/                      # Zink 驱动 tar 包（同上）
├── dxvk1/                     # DXVK tar 包（tar 槽位；APK 内置 assets/dxvk 是默认注入源）
├── vkord3d/                   # WineD3D tar 包
├── fexcore/                   # FEXCore tar 包（FEX 仿真器 PE DLL；逻辑同 DXVK → 容器 C 盘 windows）
└── wrapper/                   # Wrapper 驱动 tar 包（yuzu/WinNative 同型预编译 Turnip：
│                              #   含 libvulkan_*.so → 解到 usr/lib/linbox-wrapper/<名>/
│                              #   + 专属 Vulkan ICD 清单，VK_ICD_FILENAMES 切换，
│                              #   与 rootfs 自带 Mesa Turnip 共存可切）
```

LinBox 原生容器前缀在 `rootfs/home/.linbox/containers/<名>/prefix`；
你自带的 startwine 类脚本生态（/opt/conf 等）与原生容器并存互不影响。

## 三、container.conf 键速查（v3.2.18）

```
frame=0|60|120        帧数限制（DXVK_FRAME_RATE）
cores=0-3             主核心集（PRIMARY_CORES/SECONDARY_CORES）
dsound=builtin,native DLL 加载序（user.reg）
dshow=builtin,native  quartz DLL 加载序（user.reg）
dpi=96                LogPixels（user.reg）
winver=win10          win10/win81/win8/win7/vista/winxp/win2000/win98/win95
mangohud=             MANGOHUD_CONFIG 串（空=关）
flags=unity,opengl    高级开关 csv：unity/opengl/f5task/ib/debug/independent/closeservice/fexmode
turnip=               /opt/tz 选中的包名（空=默认，off=不使用）
zink=                 /opt/zink 包名（同上）
wined3d=              /opt/vkord3d 包名（同上）
fexcore=              /opt/fexcore 包名（FEX 仿真器 PE DLL → 容器 C 盘 windows）
wrapper=              /opt/wrapper 包名（预编译 Turnip：ICD 清单 + VK_ICD_FILENAMES 切换；空/off=Mesa）
exe=                  默认运行的程序（wineguanli custom_exe_path；空=运行时再选）
```

## 四、与 wineguanli.py 的落地差异（数据层）

- wineguanli 写全局 `/opt/conf/*.conf`；LinBox 落到**容器级 container.conf**
  （env=/mangohud=/cores=…），启动链注入 —— 同等能力，且可逐容器差异化。
- DY 配置三预设的 9 个 BOX64_DYNAREC_* 变量与 wineguanli **逐字一致**；
  额外变量可自由添加（应用时合并进容器 env=）。
- 「架构」下拉映射 x86_64→box64 后端、arm64ec→arm64ec 后端；完整后端集
  （auto/hangover/fexcore/native）在 更多设置 → 🧩LinBox 原生 页。

## v3.3.39：conf 统一到 APK 内部端

cores.conf / env.conf / dynarec_active.conf / custom.conf / path.conf /
locale.conf 等**配置文件**的权威源已迁到 App 私有存储
`/data/data/com.linbox/files/linbox-conf/`（assets 内置缺省 = 用户 opt.zip
已知良好值；rootfs 重导入不清空）。/opt 生态包的 conf 降级为**合并第二源**：

- App UI（容器管理）改 conf → 写 App 内部 + 写透同步 /opt/conf；
- /opt 生态首次出现 → 现值一次性导入 App 内部（UI 已改过的文件不动）；
- 终端侧直接改 /opt/conf → 启动链按**逐文件 mtime 新者胜**生效；
- 未装 /opt 生态包 → conf 编辑照常可用（旧版"未检测到 /opt 生态"整页
  旁路的行为废除），PRIMARY_CORES 大核绑定开箱即生效。

`/opt` 生态包的**资产目录**（box/tz/zink/dxvk1/vkord3d/fexcore/wrapper/
prefix）职责不变 —— 驱动 tar 扫描与解压仍从这里读取。
