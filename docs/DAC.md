# LinBox DAC 架构（Direct Android Compositing）

## 1. 总体数据流

```
┌────────────────────── LinBox 终端（Termux 环境，wine 进程）──────────────────────┐
│                                                                                │
│  EXE(game.exe)                                                                 │
│    └─ d3d11/d3d9                                                               │
│        └─ DXVK ──(Vulkan)──▶ winevulkan.dll（thunks）                           │
│                                 │  wine 9.x: WSI 全部由图形驱动实现               │
│                                 ▼                                              │
│                       winedac.drv / vulkan.c                                   │
│                         ├ vkCreateWin32SurfaceKHR  → 记录 hwnd                  │
│                         ├ vkCreateSwapchainKHR     → N 张 AHardwareBuffer       │
│                         │     （vkCreateImage + AHB 导入内存 + dedicated）       │
│                         ├ vkAcquireNextImageKHR    → 空闲图像 + 立即 signal      │
│                         └ vkQueuePresentKHR        → present 信号量 → sync fd    │
│                                 │                                              │
│  GDI 窗口: window_surface(CPU 位图) → 软件合成器(按 z 序) → 桌面 AHB 环形缓冲      │
│                                 │                                              │
└─────────────────────────────────┼── unix socket($PREFIX/tmp/linbox-dac.sock) ──┘
                                  │ 帧 + fd(SCM_RIGHTS)
┌───────────────────────── LinBox App 进程（com.linbox）─────────────────────────┐
│                                 ▼                                              │
│                linbox_dac_bridge.c（JNI，DacView 的 Surface）                   │
│                  ├ AHardwareBuffer_recvHandleFromUnixSocket 导入               │
│                  ├ ASurfaceControl_createFromWindow(SurfaceView)               │
│                  ├ ASurfaceTransaction.setBuffer(ahb, acquireFence)            │
│                  │     + setDamageRegion / setPosition / setMatrix / setZ      │
│                  └ OnComplete → release fence → 回执 wine（槽位复用背压）        │
│                                 ▼                                              │
│                    ★ SurfaceFlinger 直接合成 ★                                  │
│        桌面层（GDI, z=0）+ DXVK 层（z=10, 定位/缩放随窗口矩形）                    │
└───────────────────────────────────────────────────────────────────────────────┘
```

## 2. 为什么"跳过 X11"成立

X11 在旧链路承担两个职责：**呈现**（X server 扫描输出/合成）与 **输入**。
DAC 拆掉了前者：

- 呈现：swapchain 图像与桌面合成结果都是 gralloc/AHardwareBuffer，
  由 SurfaceFlinger 在显示控制器上合成 —— 不存在任何 X 协议环节；
- 输入：LinBox 输入覆盖层（触摸/虚拟键鼠/手柄）经 bridge socket 直接
  注入 winedac.drv，驱动用 `__wine_send_input` 走 wine 标准输入栈。

wineboot/wineserver/注册表/驱动加载机制完全不变 —— `Graphics=dac`
只是把 `winex11.drv` 换成 `winedac.drv`。

## 3. Vulkan WSI 仿真细节（vulkan.c）

### 3.1 交换链图像 = AHardwareBuffer

```
vkCreateSwapchainKHR(create_info)
  for i in 0..N-1:                                  # N = clamp(minImageCount, 3, 8)
    AHardwareBuffer_allocate(w, h, AHB_FMT,          # 平台层：bionic 直连 / sidecar
        GPU_COLOR_OUTPUT|GPU_SAMPLED|GPU_FRAMEBUFFER)
    vkCreateImage(tiling=OPTIMAL, usage, fmt)
    vkAllocateMemory(pNext = VkImportAndroidHardwareBufferInfoANDROID{ahb}
                            + VkMemoryDedicatedAllocateInfo{image})
    vkBindImageMemory2
    → 桥帧 VK_IMAGE + sendHandleToUnixSocket         # bridge 端 recvHandle 导入
```

渲染零拷贝：DXVK 直接绘制到 AHB 背后的 gralloc 内存；bridge 只是把它
attach 到 SurfaceControl。

### 3.2 present 同步链（fence 三段式）

```
DXVK: vkQueueSubmit(..., signal=presentSem)  ──▶ vkQueuePresentKHR(wait=presentSem)
winedac: 创建 SYNC_FD 可导出信号量 fwd
         提交转发链: queue.wait(presentSem) → signal(fwd)
         vkGetSemaphoreFdKHR(fwd, SYNC_FD) → acquire fence fd
         VK_PRESENT{surface_id, idx} + fd ──▶ bridge
bridge: ASurfaceTransaction_setBuffer(ahb, acquireFence)   # SF 等 GPU 完成
        OnComplete → previousReleaseFenceFd ──▶ VK_RELEASED + fd ──▶ wine
winedac: release fence 导入临时信号量 → submit(wait=releaseSem, signal=appAcquireSem)
         图像回池（pending 位清零 + cond 广播）
```

该链满足 Vulkan/BLAST 双方语义：应用信号量被 present 引擎消费；SF 在
GPU 写完后才读 buffer；buffer 释放后 acquire 才放行下一帧。

### 3.3 dmabuf 兜底（glibc Wine）

glibc 构建的 Turnip/lavapipe 无 AHB 扩展时，交换链图像改走
`VK_KHR_external_memory_fd`（OPAQUE_FD，LINEAR tiling，行距可查），
bridge 用 EGLImage + GL 全屏 blit 呈现。一次 GPU 拷贝，功能完整。

## 4. 桌面合成器（window.c）

- 每顶层窗口一个 `window_surface`（CPU 位图，wine 标准约定，
  surface 矩形=可见区窗口相对坐标 32 对齐，同 winex11）；
- `flush()` → 脏区转屏幕坐标 → `dac_desktop_present()`；
- `composite_windows()`：从 `GetWindow(GW_HWNDLAST)` 自顶向下枚举
  顶层窗口（即自底向上绘制次序），把与脏区相交的可见窗口位图
  `memcpy` 进桌面 AHB 槽位（行序自动适配 top-down/bottom-up）；
- 桌面 AHB 环形缓冲 3 槽，`slot_pending` 位图背压：bridge OnComplete
  回执 `RELEASE` 后槽位复用，防撕裂/防覆盖未完成 present。

## 5. 双 Wine 形态（platform.c / dac_allocd.c）

| | Bionic Wine | glibc Wine（grun） |
|---|---|---|
| AHB 分配 | 进程内 `dlopen(libandroid.so)` | 侧车 `dac_allocd`（bionic 可执行） |
| DXVK ICD | turnip(AHB) / lavapipe | turnip-glibc(dmabuf) / lavapipe |
| 桌面 CPU 写 | `AHardwareBuffer_lock` | raw fd `mmap`（sidecar 附 raw fd） |
| 呈现 | SF 直合（零拷贝） | dmabuf EGL blit（一拷） |
| 识别 | `dlopen` 成功 | 侧车 socket 握手成功 |

## 6. 安全与权限模型

- socket 位于 `$PREFIX/tmp`（com.linbox 数据目录内），仅同 UID 可达；
- gralloc/binder 调用以 app UID 身份进行（untrusted_app 域允许）；
- bridge 不持有任何额外权限；ASurfaceControl 父层来自应用自己的
  SurfaceView，无悬浮窗权限需求；
- targetSdk 28 约束不受影响（全部在已授权域内）。
