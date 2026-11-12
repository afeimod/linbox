# assets/linbox/ — 内置 tzst 资产目录（自解压契约）

本目录存放 LinBox 的**离线 tzst 资产**（tar + zstd，后缀 `.tzst` 或 `.tar.zst`）。
APK 启动 / bootstrap 安装完成后由 `LinBoxTzstAssets` **自动自解压**，无需手工导入。

## 命名契约（文件名含关键词即归类，其余部分任意）

| 文件名含关键词 | 用途 | 解压目标 | 时机 |
|-----------|------|---------|------|
| `rootfs` | bionic rootfs | `/data/data/com.linbox/files/rootfs` | 启动即解压（指纹幂等） |
| `mesa`   | Mesa GL（libGL/EGL/gbm + dri/ + ICD） | 同上（usr/ 布局叠加） | 启动即解压（指纹幂等） |
| `box64`  | box64（usr/local/bin/box64） | 同上 | 启动即解压（指纹幂等） |
| `turnip` | Turnip Vulkan 驱动（usr/lib + icd.d） | 同上 | 启动即解压（指纹幂等） |
| `dxvk`   | DXVK DLL | `<wine前缀>/drive_c/windows` | **前缀构建后**自动解压 |

命名同时兼容简短式（`dxvk-2.4.tzst`、`rootfs-main.tzst`）与 CI 产物原名
（`linbox-bionic-rootfs-20260919-aarch64.tzst`、`linbox-mesa-25.1-aarch64-bionic.tzst`、
`linbox-box64-v0.2.9-aarch64-bionic.tzst`）。wine 发行包不在此自解压范围
（仍用 `linbox-container install-wine <tzst|tar.gz>` 安装）。

## 归档内部布局（remapToRootfs 剥壳规则）

tar 成员路径按下列顺序剥壳后落位 `/data/data/com.linbox/files/rootfs/`：

| 内部布局 | 示例成员 | 落点 |
|---------|---------|------|
| **rootfs/ 内部目录（推荐打包格式）** | `rootfs/usr/bin/…` | `rootfs/usr/bin/…` |
| CI 发行包 data/data 布局 | `data/data/com.linbox/files/rootfs/usr/bin/…` | `rootfs/usr/bin/…` |
| 旧 CI 布局（等长重写互认） | `data/data/com.termux/files/rootfs/usr/bin/…` | `rootfs/usr/bin/…` |
| 相对布局 | `usr/bin/…` | `rootfs/usr/bin/…` |

打包示例（把 CI 的 tar.gz 转为 tzst，内部目录为 rootfs）：

```bash
mkdir -p /tmp/pk/rootfs
tar -xzf linbox-bionic-rootfs-*-aarch64.tar.gz -C /tmp/pk/rootfs --strip-components=4 \
  --wildcards 'data/data/*/files/rootfs/*'   # 或按实际布局 strip
tar -C /tmp/pk --zstd -cf rootfs-main-aarch64.tzst rootfs
# 放入 app/src/main/assets/linbox/ 即随 APK 分发，安装后自动解压
```

## 特殊资产：wrapper-stack.tzst（v3.3.32，不自动解压）

**`wrapper-stack.tzst`（5.5MB）**：Vulkan wrapper 栈（上游参考 `wrapper.tzst`
同源二进制：`libvulkan_wrapper.so` + `libadrenotools.so` + 四个钩子库）——
高通/联发科【专有】系统驱动与 yuzu 格式专有驱动包（v819.2 / v849 / q615.50
等）的唯一可用通路。文件名**不含上述关键词**，不进 LinBoxTzstAssets 自解压；
由 `WrapperDriverSupport.ensureBundledWrapperStack` 在首次需要时
（图形驱动页选『系统驱动』/ 专有驱动包路由）安装到
`rootfs/usr/share/linbox-wrapper/drivers/wrapper-stack/`（含自生成 ICD 与
meta，走 `WrapperDriverSupport.install` 全套 ABI 门禁）。来源与许可见包内
`ORIGIN.md` 及 `docs/SYSTEM-GPU-DRIVERS.md`。

## 归档成员路径布局（两种均支持）

1. **CI 发行包布局**（data/data 开头，build-bionic-rootfs / build-mesa-bionic /
   build-box64-bionic 产物原样可用）：

       data/data/com.linbox/files/rootfs/usr/...
       data/data/com.linbox/files/rootfs/usr/local/bin/box64

   成员前缀自动剥壳后落入 rootfs 根。

2. **相对布局**（usr/、etc/、home/ 开头）：

       usr/lib/libGL.so.1
       usr/local/bin/box64

   直接落入 rootfs 根。

## DXVK 资产布局（顶层目录决定落位）

- **system32/ + syswow64/**（本项目约定样式，推荐）：
  直接归位 → `drive_c/windows/system32`（64 位 DLL）与
  `drive_c/windows/syswow64`（32 位 DLL）。
- x64/ + x32|x86/（上游 DXVK 发布包）：自动重映射，落位同上。
- 裸 *.dll：按 64 位归入 system32。

## 幂等与更新

- 每个资产按「文件名 + 长度 + CRC32」指纹记账
  （`rootfs/usr/etc/linbox/.tzst-assets-fingerprint`）；
  资产内容不变时不重复解压；更新资产（换名/换内容）后自动重铺。
- DXVK 每个前缀按 `linboxmeta/.linbox-dxvk-applied` 记账（与终端侧
  glibc-runner 双端互认），并回写 container.conf 的 `dxvk=` 版本戳。
- wine 前缀未构建（无 system.reg）时 DXVK **不会**预解压；
  前缀由 wineboot 构建完成后的首次 App 启动 / 进入容器界面即自动补齐。

## scripts/ — 容器管理脚本（覆盖安装，v2.26 起，v2.27 扩展）

本目录存放**纯文本 shell 脚本**（非 tzst），由
`LinBoxScriptAssets.deployScriptsIfNeeded` 覆盖安装到
`/data/data/com.linbox/files/rootfs/usr/bin/`（chmod 0755）：

| 脚本 | 作用 | 部署时机 |
|------|------|---------|
| `linbox-container` | 容器管理 CLI v2.1（start/shell/create/set/install-wine 多版本+类别/wine-catalog/wine-list/wine-default/wine-remove/dxvk/vkd3d/doctor） | App 启动 + 每次容器会话启动前 |
| `linbox-wine` | 容器 Wine 启动器 v2.1（环境隔离 + X 服务自启 + ELF 架构自适应后端） | 同上 |
| `wine` / `winecfg` / `wineboot` / `regedit` | wine 包装器（exec linbox-wine；目标为 ELF 本体时不覆盖） | 同上 |

要点：

- **不再进入 rootfs 构建**（bionic-rootfs/projects/linbox-container 已移除）；
  rootfs tzst 解压完成后由 APK 覆盖安装 —— 旧 rootfs 自带的旧脚本一并被替换，
  用户无需重导 rootfs。
- **环境隔离**：linbox-wine 运行容器前全量重建环境 —— PREFIX/HOME/TMPDIR/
  XDG_\*/LD_LIBRARY_PATH/LANG/LC_ALL 全部指向 rootfs 自身，清洗终端侧注入的
  LD_PRELOAD/TERMUX_\* 等残留；音频用 rootfs 自带 PulseAudio（unix socket
  隔离，module-sles-sink 直连 Android 音频），与 Termux 侧 TCP 4713 无关。
- **语言（v2.27）**：LANG 与 LC_ALL 同时导出（默认 zh_CN.UTF-8）—— bionic
  只认 C locale，wine 在 setlocale 返回 C 时仅读 LC_ALL（不读 LANG），
  这是此前 wine 界面切不了中文的根因；中文渲染由部署到 rootfs 的
  `usr/etc/fonts/local.conf`（引入 /system/fonts 的 Noto CJK）提供字体。
- **显示（v2.27）**：DISPLAY 强制 `:1`（LINBOX_DISPLAY 可覆盖）；linbox-wine
  / linbox-container start 在 X socket 缺失时自动后台拉起内置 termux-x11
  （socket 落 App 侧 tmp/.X11-unix，拉起进程用 App 侧 XDG_RUNTIME_DIR，
  不被容器隔离污染）—— 终端直接 `wine notepad` 即可出画面，不再需要
  先手敲 `linbox-x11 :1 && env DISPLAY=:1`。
- **终端会话注入**：App 侧 `$PREFIX/etc/profile.d/linbox-container.sh`
  每次部署刷新 —— 终端 PATH 自动含 rootfs bin（容器 CLI/wine 包装器
  直接可用）且 DISPLAY=:1。
- **显示模式**：dmode=off/d/v/f 对齐 glibc-runner 的 -d/-v/-f 分辨率握手协议
  （`$PREFIX/tmp/.linbox-x11-res`）。
- **Wine 多版本（bionic 双形态 + 普通 Wine + Proton）**：
  - `install-wine <类别>`：bionic-x86_64 / bionic-arm64ec / wine-x86_64 /
    wine-arm64 / proton —— 自动从 GitHub Releases（LINBOX_RELEASES_REPO 可
    指向 fork）取最新资产，直连失败自动走加速镜像（LINBOX_GH_PROXY 可自定义）；
    仍可直接给本地包或完整 URL。
  - bionic 双形态同一流水线（build-wine-bionic.yml）三种源码源任选：
    hangover（默认）/ official（WineHQ 官方源码）/ proton（Valve 源码树），
    产物名带 kind 前缀（linbox-wine-official-11.0-*-bionic.tar.gz 等），
    多源多版本共存于 wine-list。
  - `wine-catalog [类别]`：查看在线构建目录。
  - 后端按槽位 wine 二进制的 ELF 头（e_machine）自动判定：aarch64 形态
    → arm64ec 原生运行（new WoW64），x86_64 形态 → box64/native；
    解析顺序：容器 wine= → 全局 default_wine= → /usr/opt/wine。
  - 槽位 .linbox-wine-info 记录 version/arch/flavor/source。
