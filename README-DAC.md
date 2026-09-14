# LinBox DAC — 集成进 LinBox 项目的 Wine 显示链路（非独立应用）

> **定位：并入项目的显示模块，不是独立构建。** merge 一次提交后，DAC 就是仓库源码
> 的一部分，你现有的构建（Android Studio / gradlew / 你自己的 CI）无需任何改动
> 即自动包含，无需再跑 merge、无需专用 workflow。
>
> 显示路径（`linbox-dac` auto 自动选择，DAC 优先）：
> ① **DAC 独立显示器**（首选）：winedac.drv → AHardwareBuffer → SurfaceFlinger
>    直合，零 X11（需部署 Actions 产出的 wine-dac tarball）；
> ② **复用已有 X11**：环境里已有的 X server（已设 DISPLAY、LinBox 内置
>    `:13` lorie/Xwayland、termux-x11）直接复用，脚本不会重复安装任何东西；
> ③ VNC 兕底。

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

## 快速开始（一次集成，永久生效）

```bash
# 1. 解压本包，把源码并入 linbox 仓库（幂等，可重复执行）
unzip linbox-dac-source-v1.5.zip -d dac && cd dac
./tools/merge-into-repo.sh /path/to/linbox     # --dry-run 可先预览
# 2. 提交推送 —— 从此 DAC 就是项目源码的一部分
cd /path/to/linbox && git add -A && git commit -m "LinBox DAC v1.5" && git push
# 3. 正常构建即可（三选一，产物相同）：
#    ① 本地：Android Studio Run / ./gradlew assembleRelease
#    ② 你已有的 CI：什么都不用改
#    ③ push 触发仓库 Actions「LinBox 构建（含 DAC 集成）」→ 自动出 APK
```

集成后无需再跑 merge，也没有「单独的 DAC 构建」——唯一新增的
`.github/workflows/linbox-build.yml` 只是构建兜底（固定 NDK/JDK 版本 + 自动
签名，push 即自动出 APK）；也可手动选 wine / dxvk / turnip 组件构建配套
产物（winedac.drv tarball / DXVK DLL / Adreno ICD）。若你已有自己的 CI，
这个文件可直接删除，不影响集成。

APK 安装后，5 个 `linbox-dac*` 脚本随 bootstrap 自动落到 `$PREFIX/bin`
（经 jniLibs `lib*.so` 管线分发，无需手动拷贝）。

## 终端使用（显示路径自动选择，DAC 优先）

```bash
# ── DAC 独立显示器（首选）：部署 wine-dac tarball 后自动启用 ──
linbox-dac doctor                 # 体检（逐项显示哪条路径可用）
linbox-dac game.exe               # auto：检测到 winedac.drv → 独立显示器直合
linbox-dac --display dac game.exe # 强制 DAC

# ── X11：仅复用环境里已有的 X server，不装任何东西 ──
linbox-dac game.exe               # auto：DISPLAY 已设 / 内置 :13 在跑 → 直接复用
linbox-dac --display x11 game.exe # 强制走已有 X11；皆无且有 termux-x11 才新起 :0
linbox-dac setup-x11              # 自检：已有环境直接通过，不会下载
```

## 源码结构（可直接并入 linbox 仓库）

| 路径 | 内容 |
|------|------|
| `wine/dlls/winedac.drv/` | **新 Wine 图形驱动**（~2600 行 C）：Vulkan WSI 仿真 / 桌面合成 / 输入 / 平台适配 |
| `wine/patches/` | wine 9.2 configure.ac 补丁（注册驱动） |
| `wine/build_wine_dac.sh` | 带 DAC 驱动的 wine 构建脚本 |
| `app/src/main/cpp/dac/` | App 侧 JNI bridge + bionic 侧车守护进程 + Android.mk |
| `app/src/main/java/com/linbox/apps/dac/` | DacApp / DacView / DacReceiver / DacInput / DacNative |
| `app/src/main/assets/termux/scripts/` | `linbox-dac` CLI（启动/体检/注册/停止） |
| `mesa/build-turnip-android.sh` | platforms=android 的 Turnip（AHB Vulkan ICD）构建脚本 |
| `dxvk/build-dxvk.sh` | DXVK 构建 + prefix 部署（无需打补丁） |
| `.github/workflows/linbox-build.yml` | 唯一构建入口（push 自动出 APK；wine/dxvk/turnip 为可选组件） |
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
