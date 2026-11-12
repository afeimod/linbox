# assets/dxvk — DXVK 资产目录（v3.2.14）

把 DXVK 发行包放到本目录，构建 Wine 前缀时自动解压进容器的 `C:\windows`。

## 放置约定

- 文件名：`dxvk-<版本>.tar.gz`（如 `dxvk-2.3.1.tar.gz`）
- v3.2.23：**源码里放原名 `.tar.gz` 即可，APK 内保持原名原字节** ——
  与本项目同版 aapt2（8.5.2-11315950）全链实测（link -A / noCompress /
  optimize）均不剥层不改名；v3.2.22 的"aapt2 剥 .gz 层"是误诊（那是
  aapt v1 / 三方重打包场景），改名 `.tgz` 的同步任务已撤销。App 侧
  资产扫描按魔数直判真实压缩格式（gzip/xz/zstd/ustar 头），即使 APK
  被三方工具重打包剥了层也能按内容正常解压。
- 兼容后缀：`.tar.gz` / `.tgz` / `.tzst` / `.tar.zst` / `.tar.xz` / `.tar`
- 旧契约 `assets/linbox/dxvk-*.tzst` 仍然有效（两处并存时全部按序应用）

## 支持的包内布局（自动探测）

1. `system32/ + syswow64/` 顶层布局 —— 直接归位（推荐自己重打包用这种）
2. 上游 DXVK 发布包布局 `x64/ + x32/`（或 `x86/`）—— 自动重映射：
   `x64 → system32`，`x32/x86 → syswow64`
3. 单根目录包装（如 `dxvk-2.3.1/x64/...`）—— 自动下钻
4. 裸 `*.dll` 平铺 —— 一律按 64 位落入 `system32`

落位目标：`<WINEPREFIX>/drive_c/windows/system32`（64 位 DLL）与
`<WINEPREFIX>/drive_c/windows/syswow64`（32 位 DLL）。

## 注入时机与开关

- **自动**：前缀（wineboot）构建完成后立即注入（v3.2.14 起在启动链第 8 步）；
  App 启动 / 进入容器管理界面时也会对已构建的前缀补齐（指纹幂等，资产
  更新后自动重铺）。
- **开关**：容器创建 / 编辑对话框 →「图形与 Wine」→「DXVK 图形加速」。
  `dxvk=off` 的容器不注入；默认开启（auto）。
- 注入成功后容器 `container.conf` 的 `dxvk=` 回写为实际版本号（如 `2.3.1`），
  同时写入 `dxoverrides=d3d8,d3d9,d3d10core,d3d11,dxgi=native,builtin`。
- 幂等标记：`<prefix>/linboxmeta/.linbox-dxvk-applied`（资产名+长度+CRC
  指纹，与终端侧 glibc-runner [F5] 双端互认）。

## 配套注册表（无需手动配置）

前缀构建时自动注入 `HKCU\Software\Wine\DllOverrides`（147 项：d3d9/dxgi/
wined3d 等 native 优先，xinput/quartz/dinput builtin 优先）——
native,builtin 语义下未注入 DXVK 的容器自动回退 wine 内建，无副作用。
