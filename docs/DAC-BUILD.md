# LinBox DAC 构建与部署

## 0. 前置认知

DAC 由三部分组成，可独立构建：

| 部件 | 构建环境 | 产物 |
|------|---------|------|
| winedac.drv | Linux 主机（交叉）或 termux 原生 | `winedac.drv`（PE）+ `winedac.so`（unix lib），随 wine 安装树 |
| app 桥 + 侧车 | Android NDK r26+（并入 LinBox APK） | `liblinbox_dac_bridge.so` + `libdac_allocd.so`（可执行） |
| Turnip/DXVK | 主机交叉 / mingw | `libvulkan_freedreno.so`、`d3d11.dll` 等 |

## 1. 构建 Wine（含 winedac.drv）

### 1a. 推荐：复用 LinBox 现有构建管线

LinBox 的 `path/build_wine.sh`（Kron4ek 模板）已能出 wine；只需两步注入：

```bash
# 1) 拷入驱动源码
cp -r wine/dlls/winedac.drv <wine-src>/dlls/
# 2) 注册进构建系统
(cd <wine-src> && patch -Np1 < wine/patches/0001-configure-ac-add-winedac.drv.patch)
# 之后照常：dlls/winevulkan/make_vulkan && autoreconf -f && configure && make install
# 或直接用打包好的 wine/build_wine_dac.sh（自动完成以上步骤）
```

> wineandroid 模式说明：winedac.drv 与 wineandroid.drv 同构（PE+unixlib），
> 若你的 wine 构建已含 `--with-android` 亦可共存，Graphics 注册表二选一。

### 1b. termux 原生（bionic wine）变体

```bash
pkg install clang meson
# termux clang 直接编 wine（Mobox 方案），补丁参考 path/termux-wine-fix.patch
# winedac.drv 无需任何修改 —— 它只依赖 win32u/user32 内部接口 + dlopen
```

### 1c. glibc 变体（glibc-runner）

```bash
TARGET=glibc-aarch64 ./wine/build_wine_dac.sh
# 产物 wine-dac-amd64/ 整树拷入 LinBox 终端，grun 运行
```

## 2. 集成进 LinBox APK（tools/merge-into-repo.sh，推荐）

源码树已按 linbox 仓库结构组织。**一条命令完成集成**（幂等，可重复执行）：

```bash
./tools/merge-into-repo.sh /path/to/linbox      # --dry-run 先预览变更
```

脚本自动完成：
1. 复制新增文件：`app/src/main/cpp/dac/`、`apps/dac/` Kotlin、
   `assets/termux/scripts/linbox-dac*`、`wine/`、`dxvk/`、`mesa/`、
   `docs/`、`.github/workflows/`（4 个 Action 构建流）；
2. 幂等补丁宿主文件：
   - `AndroidManifest.xml` 注册 `DacReceiver`；
   - `app/build.gradle.kts` 文件尾追加 **Gradle Exec 编译块**（同
     linbox-reprefix 风格 —— AGP 每模块仅允许一个 ndkBuild，已被
     termux Android.mk 占用，故 DAC 走 Exec 方案，全 ABI 产出
     `liblinbox_dac_bridge.so` + `libdac_allocd.so` 并挂 preBuild 依赖）；
   - `LinBoxApp.onCreate()` 初始化 `DacApp.appContext / DacApp.init(null)`；
   - `TermuxBootstrapInstaller` 把 `libdac_allocd.so` 拷到
     `$PREFIX/bin/dac_allocd`（chmod 0700）。

之后 `git commit + push`，在 Actions 页面运行 4 个 workflow 即可：
`LinBox DAC APK` / `LinBox DAC Wine (winedac.drv)` / `LinBox DAC DXVK` /
`LinBox DAC Turnip ICD`。

### 手动编译（调试用）

```bash
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang++ \
    -shared -fPIC -O2 -o liblinbox_dac_bridge.so linbox_dac_bridge.cpp \
    -llog -landroid -lEGL -lGLESv2
$NDK/.../aarch64-linux-android26-clang -O2 -o libdac_allocd.so dac_allocd.c \
    -landroid -llog
```

> 桥为 .cpp（clang++）：NDK r26 surface_control.h 含 C++ 签名且无
> __cplusplus 分流，必须按 C++ 编译。桥以 API 29 编译（ASurfaceControl 直链）；API 26–28 设备上加载失败时
> `DacNative.available` 自动禁用 DAC，不影响 App 其他功能。

## 3. Vulkan ICD（DXVK GPU 路径）

```bash
# Adreno（推荐）：AHB 能力 ICD
MESA_VERSION=24.0.9 ./mesa/build-turnip-android.sh
# 按脚本输出部署到 $PREFIX/share/vulkan/icd.d/ + $PREFIX/lib/
# 环境变量（linbox-dac 会透传）：
#   export VK_ICD_FILENAMES=$PREFIX/share/vulkan/icd.d/freedreno_icd.aarch64.json

# CPU 兜底（任何设备可跑）：lavapipe
# 同一脚本改 -Dgallium-drivers=softpipe,llvmpipe -Dvulkan-drivers=swrast
```

## 4. DXVK 部署

```bash
./dxvk/build-dxvk.sh ~/.wine
wine regedit /tmp/dxvk.reg   # d3d9/d3d10core/d3d11/dxgi → native
```

DXVK 本体**无需补丁**：swapchain 在 wine 层由 winedac.drv 仿真。

## 5. 运行验证

```bash
linbox-dac doctor      # 全绿后继续
linbox-dac reg         # Graphics=dac（脚本自动做）
# 1) 纯 GDI 验证：
linbox-dac cmd         # 应看到 wine cmd 窗口出现在 LinBox DAC 视图
# 2) DXVK 验证：
linbox-dac dxvk-test.exe   # 或任意 D3D11 demo；日志 $PREFIX/tmp/linbox-dac.log
# 调试：
WINEDEBUG=+dac WINEDEBUG=+vulkan linbox-dac game.exe 2>&1 | grep winedac
logcat -s LinBoxDAC
```

## 6. 故障速查

| 症状 | 排查 |
|------|------|
| `connect $SOCK failed` | DAC 窗口未开：`am broadcast -a com.linbox.action.DAC_START --ei width 1280 --ei height 720` |
| 桌面黑屏但连接成功 | `logcat -s LinBoxDAC` 看 backend；API<29 走降级属正常但需 SurfaceView 有效 |
| DXVK 报 VK_ERROR_INITIALIZATION_FAILED | ICD 无 AHB/dmabuf 扩展：换 turnip-android 构建或 lavapipe |
| 画面卡第一帧不更新 | acquire 信号量链断开：开 `WINEDEBUG=+vulkan` 看转发提交错误 |
| glibc wine 桌面打不开 | 侧车未起：`linbox-dac` 会自动拉起；手动 `TMPDIR=$PREFIX/tmp dac_allocd &` |
| 前缀切换后 Graphics 丢失 | `linbox-dac reg` 重写（幂等） |
