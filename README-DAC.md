# LinBox DAC — Wine 独立显示器（不依赖 X11 的原生安卓显示）

> 为 LinBox 开发的 **Wine 显示双路径**：
> ① **DAC 独立显示器（首选）**：APK 内置的原生安卓显示器 —— winedac.drv 把 wine
>    的画面经 AHardwareBuffer 直交 SurfaceFlinger 合成，**不需要 X11**；
> ② **X11 兜底**：已自带 termux-x11 时自动可用（App 不负责安装 X11）。
>
> `linbox-dac` 启动器自动按已部署情况回退：dac → x11 → vnc。
> v1.11 起 wine-dac tarball **自带自举 wrapper + glibc sysroot**，安卓真机
> 直接执行，无需任何手工修 loader；bin/wine 还会自动拉起 DAC 显示器窗口。

## 这是什么

LinBox 现有 Wine 图形链路：`wine → winex11.drv → X11(:13, lorie+Xwayland) → LorieView`。
本模块提供第二条并行链路：

```
EXE (d3d9/10/11) ─▶ DXVK ─▶ winevulkan ─▶ winedac.drv (Vulkan WSI 仿真)
                                              │
                              vkCreateSwapchainKHR：每个交换链图像 =
                              AHardwareBuffer（VK_ANDROID_external_memory 导入）
                                              │
                              vkQueuePresentKHR：present 信号量转 sync fd
                                              │  unix socket (SCM_RIGHTS)
                                              ▼
                    LinBox App 进程内 bridge（JNI）
                              AHardwareBuffer_recvHandleFromUnixSocket
                                              │
                              ASurfaceControl + ASurfaceTransaction.setBuffer(ahb, fence)
                                              ▼
                         ** SurfaceFlinger 直接合成（零拷贝）**
```

Wine 桌面 UI（GDI 窗口/菜单/对话框）同样直通：每窗口 CPU 位图 → 软件合成器
按 z 序混入桌面 AHardwareBuffer → 同一 bridge 呈现。

**DAC 显示器就在 APK 里**（DacView 原生 Surface + DacReceiver 接收开屏命令 +
dac_allocd 侧车），不是独立的 wine；wine-dac tarball 只是"画面的生产者"。

## 快速开始（GitHub Actions 构建，推荐）

```bash
# 1. 解压本包，将源码并入 linbox 仓库（幂等，可重复执行；已在仓库内则自动跳过复制）
unzip linbox-dac-source-v1.11.zip -d dac && cd dac
./tools/merge-into-repo.sh /path/to/linbox     # --dry-run 可先预览
# 2. 提交推送
cd /path/to/linbox && git add -A && git commit -m "LinBox DAC v1.11" && git push
# 3. GitHub → Actions → "LinBox 构建（含 DAC 集成）"手动运行（component 选组件）：
#    apk   → 集成 DAC 的 LinBox APK（push 也自动跑）
#    wine  → wine-dac tarball（DAC 显示器的 wine 侧；跑普通 Windows 程序必须选
#            wine_target=x86_64-linux，aarch64-glibc 无法运行 x86/x86_64 PE）
#    dxvk  → d3d→Vulkan DLL（游戏需要）
#    turnip→ Adreno AHB Vulkan ICD（可选，GPU 直合）
```

APK 安装后，`linbox-dac*` 脚本随 bootstrap 自动落到 `$PREFIX/bin`
（经 jniLibs `lib*.so` 管线分发，无需手动拷贝），同时装入 DAC 显示器组件
（DacView / DacReceiver / dac_allocd）。

## 真机三步出画面（v1.11 自举 tarball）

```bash
# ① 装 APK（含 DAC 显示器）；② 解压 wine tarball；③ 直接跑：
tar -xJf wine-dac-9.2-x86_64.tar.xz -C $HOME
$HOME/wine-dac-9.2-x86_64/bin/wine explorer /desktop=dac,1280x720 taskmgr
```

`bin/wine` 是自举 wrapper，自动完成：
1. **box64 启动**（x86_64 目标）：自动找设备端 box64（PATH → `$PREFIX/bin`），
   以 tarball 内 sysroot/lib 的 glibc 闭包加载 wine.real（glibc-runner/Mobox 同款）；
2. **拉起 DAC 显示器**：若 APK 的 DAC 窗口未开，自动广播
   `com.linbox.action.DAC_START`（默认 1280x720，可用 `LINBOX_DAC_SIZE=WxH` 改）；
3. **拉起 dac_allocd 侧车**（glibc wine 的 AHardwareBuffer 分配代理）；
4. exec 真身 `wine.real`（构建时默认图形驱动已补丁为 dac，免注册表直连 DAC）。

环境变量：`LINBOX_DAC_SIZE=1920x1080` 改分辨率；`LINBOX_DAC_AUTO=0` 关自动拉起。

## 真机报错对照

| 报错 | 原因 | 解法 |
|------|------|------|
| `cannot execute binary file: Exec format error` | x86_64 glibc ELF，安卓 CPU 不能直接执行，需 box64 转译 | 用 v1.11+ tarball（bin/wine 已是 wrapper）；确认设备有 box64（LinBox 自带/你仓库 build-box64.yml 产物） |
| `cannot execute: required file not found` | aarch64 glibc ELF 的动态链接器 `/lib/ld-linux-aarch64.so.1` 在安卓不存在 | 用 v1.11+ tarball（自带 sysroot loader）；且 aarch64 目标本就不能跑 x86/x86_64 PE，请改用 x86_64-linux 目标 + box64 |
| `box64: error while loading shared libraries: .../usr/glibc/lib/libc.so: invalid ELF header` | APK 自带 box64 的解释器被 patchelf 固定到 APK 的 glibc 目录，其 `libc.so` 是无效 ELF（ld 链接脚本文本/坏符号链）；旧 wrapper 导出的 x86_64 `LD_LIBRARY_PATH` 还会二次污染其原生加载器 | 用 v1.12+ tarball（自带 aarch64 box64 + 私有 glibc 闭包 + 私有 loader 直启，全程不碰 APK 的 glibc 目录；wrapper 启动前清空原生 LD_LIBRARY_PATH） |
| `未找到 box64` | 设备无 box64（v1.12+ tarball 自带，出现此错说明 tarball 解压不完整或为旧包） | 重下 v1.12+ tarball 完整解压；或把 box64 放入 `$PREFIX/bin` / PATH |
| `DAC 窗口未就绪` | APK 不含 DAC 模块或未安装新 APK | 安装 Actions 构建的含 DAC APK（LinBox-release-*，Run #19 起 CI 产出均含 DAC）；启动 wine 时保持 LinBox 在前台 |

## 终端使用（两条显示路径）

```bash
# ── 路径 A：DAC 独立显示器（部署 wine-dac tarball 后，首选）──
tar -xJf wine-dac-9.2-x86_64.tar.xz -C $HOME
$HOME/wine-dac-9.2-x86_64/bin/wine explorer /desktop=dac,1280x720 game.exe
#   或经启动器（自动 dac→x11→vnc 回退）：
linbox-dac --display dac game.exe
linbox-dac doctor                 # 体检（逐项显示哪条路径可用）

# ── 路径 B：termux-x11（你已自带 X11 时作为兜底）──
linbox-dac --display x11 game.exe # 跑 exe，画面在你的 X11 里
```

## 源码结构（可直接并入 linbox 仓库）

| 路径 | 内容 |
|------|------|
| `wine/dlls/winedac.drv/` | **新 Wine 图形驱动**（~2600 行 C）：Vulkan WSI 仿真 / 桌面合成 / 输入 / 平台适配 |
| `wine/patches/` | wine 9.2 configure.ac 补丁（注册驱动） |
| `wine/build_wine_dac.sh` | 带 DAC 驱动的 wine 构建脚本（v1.11：默认驱动补丁 + 自举打包） |
| `app/src/main/cpp/dac/` | App 侧 JNI bridge + bionic 侧车守护进程 + Android.mk |
| `app/src/main/java/com/linbox/apps/dac/` | DacApp / DacView / DacReceiver / DacInput / DacNative |
| `app/src/main/assets/termux/scripts/` | `linbox-dac` CLI（启动/体检/注册/停止） |
| `mesa/build-turnip-android.sh` | platforms=android 的 Turnip（AHB Vulkan ICD）构建脚本 |
| `dxvk/build-dxvk.sh` | DXVK 构建 + prefix 部署（无需打补丁） |
| `.github/workflows/` | 统一构建流 linbox-build.yml（apk / wine / dxvk / turnip / all） |
| `tools/merge-into-repo.sh` | 幂等集成脚本（复制文件 + 补丁 Manifest/Gradle/LinBoxApp/Installer） |
| `docs/` | 架构 / 协议 / 构建文档 |

## 关键设计决策

1. **wine 9.2 原生机制**：wine 9.x 的 `winevulkan` 把全部 WSI 委托给图形驱动
   （`include/wine/vulkan_driver.h`），因此 winedac.drv 能完整实现无窗口系统
   的 swapchain 仿真 —— DXVK 零改动。
2. **wineandroid.drv 兼容**：驱动结构（PE/unix 分离、user_driver_funcs、
   键盘映射表）与 wineandroid.drv 同源；但桥接层换成 unix socket（termux
   环境无 Java/Activity，wineandroid 的 JNI 桥不可用，协议思想保留）。
3. **双 Wine 形态**：Bionic Wine 进程内 dlopen libandroid 直连；glibc Wine
   （glibc-runner）经 bionic 侧车 `dac_allocd` 代理分配（或退 dmabuf+EGL）。
4. **三级呈现**：API 29+ SurfaceFlinger 直合（零拷贝）；API 26-28 AHB
   Canvas 降级；glibc wine dmabuf EGL blit 兜底。
5. **设备端自举（v1.11）**：安卓不能裸 exec glibc ELF —— x86_64 报
   `Exec format error`（需 box64 转译）、aarch64 报 `required file not found`
   （缺 ld-linux）。tarball 内置 sysroot/lib（ldd glibc 闭包）+ 自举
   wrapper + 默认驱动补丁（explorer/desktop.c：mac,x11 → dac），
   bin/wine 直跑即达 DAC。

## 文档

- [docs/DAC.md](docs/DAC.md) — 架构与数据流
- [docs/DAC-PROTOCOL.md](docs/DAC-PROTOCOL.md) — socket 线协议规范
- [docs/DAC-BUILD.md](docs/DAC-BUILD.md) — 构建 / 集成 / 部署全流程

## 已知边界（v1）

- 虚拟桌面模式（`wine explorer /desktop=dac,WxH`）由脚本强制，多窗口 z 序
  由 wine+合成器处理；每窗口独立 SF 图层留作 v2。
- OpenGL（wgl）未实现，GL 游戏需 DXVK 不覆盖的场景暂走 lavapipe/zink 后续。
- 鼠标相对模式（FPS 类 first-person 捕获）在 v1 以绝对坐标模拟，v2 加 raw input。
- 桥以 NDK API 29 编译（ASurfaceControl 直链）：API 26–28 设备上
  `System.loadLibrary` 失败后 DAC 自动禁用（`DacNative.available` 兜底），
  不影响 App 其他功能；AHB Canvas 降级的 dlsym 变体留作 v2。
- x86_64 wine + box64 的 DAC GPU 直合（DXVK）需要 x86_64 glibc Vulkan ICD
  （turnip x86_64 / lavapipe），当前仓库 turnip 为 aarch64-bionic 目标 ——
  GDI 程序（explorer/taskmgr 等）走 CPU 位图路径不受影响。
