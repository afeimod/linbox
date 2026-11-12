# 高通 / 联发科专有系统 Vulkan 驱动支持（v3.3.32）

> 用户需求原文："wrapper 和 yuzu 类驱动只能用 turnip 开源驱动，用不了高通或者
> 天玑的系统驱动，比如高通 615.50 或者 849 等，可以参考
> https://github.com/WinlatorXR/WinlatorXR.git"

## 一、为什么专有驱动以前用不了

LinBox 的 wine 是 **bionic 原生进程**，Vulkan 走 Khronos loader + `VK_ICD_FILENAMES`。
三条硬障碍让专有驱动无法直挂：

1. **无 Khronos ICD 接口**：高通/联发科专有驱动是 Android HAL 模块（`hwvulkan.h`
   的 HMI 接口）或只经 Android Vulkan loader（`/system/lib64/libvulkan.so`）暴露，
   不导出 `vk_icdNegotiateLoaderICDInterfaceVersion` / `vk_icdGetInstanceProcAddr`，
   Khronos loader 无法按 ICD 清单直接 dlopen；
2. **无 X11 WSI**：专有驱动只支持 `VK_KHR_android_surface`（DT_NEEDED 无
   libxcb），wine/DXVK 需要 `VK_KHR_xlib_surface` 建 swapchain —— 直挂必报
   "Required Vulkan extension VK_KHR_surface not supported" 黑屏；
3. **linker namespace 隔离**：vendor 驱动与 HIDL 依赖（`vendor.qti.hardware.
   display.mapper@*.so` 等）在 sphal 命名空间，普通 dlopen 不可达。

**解法（WinlatorXR 同款）= wrapper 栈**：

```
wine → winevulkan → Khronos loader → VK_ICD_FILENAMES
       → wrapper-stack/icd.json → libvulkan_wrapper.so（Mesa 系，X11 WSI 由其 xcb 侧提供）
            ├─ ADRENOTOOLS_DRIVER_PATH/NAME 已设 → libadrenotools.so 以
            │   android_dlopen_ext 隔离命名空间 + 文件重定向加载【yuzu 专有驱动包】
            │   （v819.2 / v849 / q615.50 / turnip_mrpurple 等）
            └─ 未设 → 回退 dlopen /system/lib64/libvulkan.so
                → Android loader → 设备厂商专有驱动（高通 Adreno 系统驱动
                  615.50/512.x/849 系列，联发科天玑 Mali 系同通路）
```

## 二、两种用法

### 用法 A：系统驱动（零包零配置）

容器编辑 → **图形驱动** → 类型选 **『系统驱动（高通/联发科专有）』**。

- 首次选中/启动时自动从 APK 内置资产安装 wrapper 栈（`wrapper-stack.tzst`
  5.5MB，一次性，rootfs `usr/share/linbox-wrapper/drivers/wrapper-stack/`）；
- 容器 conf 落 `wrapper=system`；激活链不注入 `ADRENOTOOLS_DRIVER_*`，
  wrapper 自行回退加载 `/system/lib64/libvulkan.so` = **手机出厂的厂商专有
  驱动**（高通 Adreno / 联发科 Mali 同一通路）；
- 会话日志可见：`系统专有驱动激活（高通/联发科）：ICD→libvulkan_wrapper.so
  （wrapper 回退系统 /system/lib64/libvulkan.so —— 设备厂商专有驱动）`。

### 用法 B：yuzu 格式专有驱动包（615.50 / 849 / v819.2 等）

容器编辑 → **图形驱动** → **➕ 安装驱动（zip / tar）** 选包 → 装进独立驱动库 →
类型选 **『驱动包（turnip / 高通专有，/opt/tz）』** 并选中新槽位。

- 支持的包形态：根级 `meta.json`（`libraryName` 指向驱动 .so）+ 驱动 .so
  （`vulkan.ad0849.so` / `libvulkan_adreno.so` / `vulkan.purple.so` 等命名均认）
  + 同目录附属 .so（`notgsl.so` / `notllvm-*.so` / `libdmabufheap.so` 等
  yuzu 生态改名依赖，随包整体落位）；
- 激活时自动路由：专有驱动为 android 平台构建（无 X11 WSI）→ ICD 换成
  wrapper 栈、驱动本体经 `ADRENOTOOLS_DRIVER_PATH/NAME/HOOKS_PATH` 注入
  adrenotools 隔离命名空间加载 —— **wrapper 栈未装时自动装内置栈再路由**；
- 驱动包来源示例：WinlatorXR `assets/graphics_driver/adrenotools-v819.2/
  v849/v863.1_Quest3_Pico4Ultra.tzst`（重打包为 zip/tar 即可装）、
  yuzu/Skyline 生态 AdrenoTools 驱动仓库（The412Banner / Mr-Purple 等）。

### 换回开源驱动

图形驱动页选回 **Turnip（Mesa 内置，推荐）** 或任一 bionic X11 turnip 槽位即可，
零文件读写。

## 三、实现落点（源码索引）

| 环节 | 文件 |
|---|---|
| 系统驱动 sentinel / 内置栈安装 / 激活计划 | `app/src/main/java/com/linbox/apps/containers/launcher/WrapperDriverSupport.kt`（`SYSTEM_SLOT` / `BUNDLED_WRAPPER_SLOT` / `ensureBundledWrapperStack` / `ensureWrapperStackAvailable` / `systemActivationFor`；依赖预检含 `/vendor/lib64` 等厂商分区） |
| 原生链激活（6a 段） | `launcher/RootfsLauncher.kt`（system 档自动装栈 + android 驱动自动路由） |
| 驱动页 UI | `apps/containers/ContainerScreens.kt`（GraphicsDriverTab：系统驱动档 / 一键安装内置栈按钮） |
| 终端链（linbox-wine） | `assets/linbox/scripts/linbox-wine`（`apply_vk_driver` 的 system 分支；`bionic-rootfs/projects/linbox-container/` 同源副本已同步） |
| 内置资产 | `assets/linbox/wrapper-stack.tzst`（含 `ORIGIN.md` 出处与许可说明） |

## 四、依赖与许可说明

- wrapper 栈六个 .so 取自 **WinlatorXR**
  （github.com/WinlatorXR/WinlatorXR）`assets/graphics_driver/wrapper.tzst`
  —— 上游为 Winlator/WinNative 生态的 Mesa Turnip fork（MIT）+
  bylaws/libadrenotools（隔离命名空间加载器）+ 钩子库；WinlatorXR 仓库
  声明其新增部分 GPLv3、上游 MIT。随源码/二进制再分发时请保留
  `wrapper-stack.tzst` 内 `ORIGIN.md` 与上游链接；
- 依赖闭环：`libzstd.so.1`/`libz.so`（rootfs zlib/zstd 配方，版本别名兼容）、
  `libandroid-shmem.so`、`libc++_shared.so`、`libxcb*`/`libdrm`（rootfs X11 栈）、
  `libnativewindow.so`→`libbinder_ndk.so`（ensureNdkDeps 软链兜底）、
  HIDL/mapper 系（`/vendor/lib64`，v3.3.32 起入预检搜索位）。

## 五、已知边界

- 专有系统驱动的 Vulkan 版本/扩展集取决于设备厂商（Android 15 + Adreno 740
  实测可用性以会话日志 `VK_LOADER_DEBUG=error` 与 DXVK 输出为准）；
- zink（GL over Vulkan）在专有驱动上的兼容性弱于 turnip，GL 侧游戏建议
  保持 gallium=freedreno 或切回 turnip；
- "系统驱动"档在终端（linbox-wine）侧需先在 App 图形驱动页选过一次
  （终端无法读 APK 资产，栈装好后终端即共用）。
