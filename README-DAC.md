# LinBox DAC — Direct Android Compositing（DXVK → AHardwareBuffer → SurfaceFlinger）

> 为 LinBox 单独开发的 **Wine 直通显示路径**：跳过内置 X11 服务器，
> DXVK 渲染结果经 AHardwareBuffer 直交 SurfaceFlinger 合成显示。

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

## 快速开始（GitHub Actions 构建，推荐）

```bash
# 1. 解压本包，将源码并入 linbox 仓库（幂等，可重复执行）
unzip linbox-dac-source-v1.1.zip -d dac && cd dac
./tools/merge-into-repo.sh /path/to/linbox     # --dry-run 可先预览
# 2. 提交推送
cd /path/to/linbox && git add -A && git commit -m "LinBox DAC v1.1" && git push
# 3. GitHub → Actions 页面手动运行：
#    ① LinBox DAC APK                → 集成 DAC 的 LinBox APK
#    ② LinBox DAC Wine (winedac.drv) → wine-dac tarball（box64/grun 运行）
#    ③ LinBox DAC DXVK               → d3d→Vulkan DLL
#    ④ LinBox DAC Turnip ICD         → Adreno AHB Vulkan ICD（可选，GPU 直合）
```

四个 workflow 均为 `workflow_dispatch` 手动触发，产物在 run 页面 Artifacts 下载。

## 终端使用

```bash
# 终端里（wine + DXVK 就绪后，详见 docs/DAC-BUILD.md）
linbox-dac doctor                 # 体检
linbox-dac game.exe               # 启动（自动开窗 + 注册驱动 + 虚拟桌面模式）
linbox-dac -s 1920x1080 game.exe  # 指定桌面分辨率
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
| `.github/workflows/` | 4 个 Action 构建流（APK / Wine / DXVK / Turnip） |
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
