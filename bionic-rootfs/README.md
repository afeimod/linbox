# LinBox Bionic Rootfs（X11 版）

基于 Android NDK 交叉编译、针对 **Bionic (Android libc)** 的 rootfs 构建系统，
供 [LinBox](https://github.com/afeimod/LinBox)（`com.linbox`）使用。

> 本项目改编自 android-bionic-rootfs，主要改动：
> 1. 全部路径/标识改为 LinBox（`com.linbox`，rootfs 前缀
>    `/data/data/com.linbox/files/rootfs/usr`）
> 2. **显示协议由 Wayland/Weston 改为 X11**：
>    - 删除 `projects/wayland`、`projects/wayland-protocols`
>    - mesa 改为 `platforms=x11` + `glx=dri`（DRI3/GLX/EGL over X11）
>    - wine（hangover-wine）改为 `--with-x`（xrender/xrandr/xi/xcursor/
>      xinerama/xcomposite/xxf86vm/…）
>    - Vulkan Loader 开启 XCB/XLIB WSI；gstreamer 开启 x11 插件
>    - libxkbcommon 开启 `enable-x11`
>    - 新增完整 X11 客户端库栈：xorgproto、libxau、libxdmcp、xcb-proto、
>      libxcb、xtrans、libX11、libXext、libXrender、libXfixes、libXdamage、
>      libXrandr、libXi、libXinerama、libXcursor、libXcomposite、
>      libXxf86vm、libxshmfence、libXv
> 3. X11 unix socket 路径补丁：LinBox 内置 X 服务（libXlorie / termux-x11）
>    在 **App 侧前缀** `$APP_PREFIX/tmp/.X11-unix/Xn`
>    （`/data/data/com.linbox/files/usr/tmp/.X11-unix/X1`，默认显示号 `:1`）
>    监听，xtrans / libxcb 已补丁对齐该路径（见 `patch-rules.conf`
>    的 `@APP_PREFIX@` 规则与 `projects/libxcb/build.sh`）。
> 4. **已同步参考构建系统（rootfsbc）的基础设施**（不含 wayland）：
>    - NDK 升级 r28b → **r29**（ndk_patches/r29 全套补丁随仓）
>    - `utils/log.sh`（info/warn/err/ok 彩色日志）与 `utils/notices.sh`
>      （THIRD_PARTY_NOTICES 在导出阶段生成）
>    - `patch-rules.sed` → `patch-rules.conf`：占位符替换由 bash 层
>      `expand_patch_placeholders()` 完成（防 patsub_replacement 误展开、
>      未定义变量/残留占位符即中止），并保留 LinBox 的 `@APP_PREFIX@`
>    - 源码复用：`prepare_src()` 按 revision/补丁指纹判断，未变化时跳过
>      重新获取与打补丁；NDK 补丁状态键带指纹，配置变化自动重下
>    - `--has-prefix`（复用已有 prefix）、`--update-ndk`、动作类选项
>      先登记后执行（`--arch` 写在 `--build` 后同样生效）
>    - 新增包：`fluidsynth`（MIDI 合成）、`libtheora`（Theora 编解码，
>      gstreamer theora 插件同步启用）、`libresolv`（resolv_wrapper，
>      配合 CMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH 的交叉探测修复）
>    - `make-rootfs.sh`：同名包多 revision 去重、usr-merge 软链接、
>      `--lite` 精简模式、gen_notices；保留 LinBox 的磁盘预检、逐包失败
>      汇总与绝对符号链接重写（xkeyboard-config 断链修复）
>    - mesa：0011 TLS 符号补丁改为幂等应用（双架构源码复用下二次应用
>      不再失败），X11 平台其余 shim（NDK 平台私有头）保持不变

## rootfs 运行时布局

```
/data/data/com.linbox/files/rootfs/     <- rootfs 根
├── usr/                                <- ${prefix}（本构建系统安装前缀）
├── home/                               <- ${rootfsHome}
└── tmp/                                <- rootfs 临时目录
```

App 侧 X11 socket：`/data/data/com.linbox/files/usr/tmp/.X11-unix/X1`

## 构建

```bash
./host-deps.sh                          # 安装宿主依赖（debian/ubuntu/arch）
./build-rootfs-arch.sh --arch aarch64 --build all   # 构建所有包
./build-rootfs-arch.sh --arch x86_64  --build all
./make-rootfs.sh --export /path/to/rootfs --arch aarch64   # 组装 rootfs
```

CI 一键构建见 LinBox 仓库 `.github/workflows/build-bionic-rootfs.yml`
（Actions → "Build LinBox Bionic Rootfs (X11)" → Run workflow）。

## build.sh 变量顺序规范

所有 `projects/*/build.sh` 应遵循以下变量顺序：

```bash
# 1. 版本信息（url 依赖此变量）
revision="x.y.z"

# 2. 源码地址
url="https://..."

# 3. 源码类型
urlType="git"  # git | tar | 7z | wget | local | others

# 4. 源码子目录（可选，源码不在 src/<pjName> 顶层时指定）
pkgSrcDir="${srcDir}/..."

# 5. 目标架构
arch="aarch64 x86_64"

# 6. 构建系统
buildSys="cmake"  # cmake | meson | autotools | make | others | debug

# 7. 开源协议 (SPDX 标识)
license="MIT"

# 8. 特殊标记（按需设置）
# doNotMakePackage=1

# 9. 构建参数（二选一）
args="..."
# fullCustomArgs="..."

# 10. 依赖项
deps="..."

# 11. 自定义函数（如有）
pre_setup() { ... }
custom_configure() { ... }
custom_url() { ... }
extra_fuction() { ... }
install() { ... }
```

## 主脚本常用参数

```
./build-rootfs-arch.sh --build <pjName|all>       构建指定包或所有包
./build-rootfs-arch.sh --rebuild <pjName|all>     仅重建指定包（依赖不解构）
./build-rootfs-arch.sh --rebuild-deps <pjName>    重建指定包及其所有依赖
./build-rootfs-arch.sh --arch <archs>             指定目标架构 (aarch64 x86_64|all)
./build-rootfs-arch.sh --has-src                  src 下已有对应源码时跳过代码获取与打补丁
./build-rootfs-arch.sh --load-env [arch]          加载环境变量用于调试
./build-rootfs-arch.sh --clean-ccache             清理 ccache 缓存
./build-rootfs-arch.sh --update-host-deps         更新主机依赖
```

## rootfs 完整性组件（语言 / 字体 / box64 glibc）

| 配方 | 内容 | 说明 |
|---|---|---|
| `projects/rootfs-locale` | `usr/lib/locale/locale-archive` | 构建机 localedef（`--prefix`）编入 en_US/zh_CN/zh_TW/ja_JP/ko_KR/de_DE/fr_FR/es_ES/ru_RU/pt_BR；容器内 glibc 程序（含 box64 加载的 x86_64 程序）setlocale 可用 |
| `projects/rootfs-fonts` | `usr/share/fonts/{dejavu,noto}` | DejaVu 2.37 全量 + Noto Sans SC（可变字体）；fontconfig `--with-default-fonts` 即指向该目录 |
| `projects/glibc-aarch64` | `usr/lib/aarch64-linux-gnu/`（+ `usr/lib/ld-linux-aarch64.so.1` 便捷链接，仅 aarch64 rootfs） | aarch64 glibc 运行层：私有加载器 `ld-linux-aarch64.so.1` + `libc.so.6` 等（Ubuntu noble，Packages.gz 索引自愈解析版本）。rootfs 内 glibc 链接的 aarch64 二进制显式经该加载器启动；glibc 形态 box64（build-box64.yml 产物）的 PT_INTERP 也指向它。bionic 加载器（linker64）永不搜索该目录，与 rootfs 自身 bionic 库零串扰 |
| `projects/glibc-x86_64` | `usr/lib/x86_64-linux-gnu/`（仅 aarch64 rootfs） | Ubuntu noble 的 libc6/libgcc-s1/libstdc++6/zlib1g 平铺安装，供 box64 运行常规 glibc x86_64 Linux 程序；linbox-wine 检测到该目录时自动导出 `BOX64_LD_LIBRARY_PATH` |
| `projects/box64` | `usr/local/bin/box64` + `usr/bin/box64` 链接 + `usr/lib/box64-x86_64-linux-gnu/libc++_shared.so` + `usr/etc/box64.box64rc` | x86_64 仿真核心（NDK bionic 链接，与 rootfsbc 参考配方同参数：ANDROID/BAD_SIGNAL/ARM_DYNAREC）。deps 挂 glibc-aarch64 / glibc-x86_64，`--build box64` 即产出完整文件集（deps 为运行时配套闭包，非编译期依赖） |

注意：
- `glibc-x86_64` 的 deb 版本可用 `LINBOX_GLIBC_VER` / `LINBOX_GCC_VER` /
  `LINBOX_ZLIB_VER` 覆盖（Ubuntu pool 旧版本会被回收，构建失败时改版本号即可）。
- `glibc-aarch64` 默认从 noble 三 pocket（updates/security/release）的
  Packages.gz 索引解析最新版本（pool 清理旧修订时自愈，无需改脚本）；
  需要钉死时用 `LINBOX_GLIBC_VER` / `LINBOX_GCC_VER` / `LINBOX_ZLIB_VER`，
  包 revision 用 `LINBOX_GLIBC_AARCH64_REV` 覆盖（默认 2.39，缓存于
  pkgs/ 的旧 tar 强制刷新时配 `--rebuild glibc-aarch64`）。
- GStreamer 配方必须开启 `gst-plugins-base:gl`：wine（尤其 Proton 树）的
  configure 用 pkg-config 检查 `gstreamer-gl-1.0`，`gl=disabled` 时该 .pc
  不生成，wine 报 "gstreamer-1.0 base plugins development files not found"。

## 补丁机制

`apply_patches`（项目补丁）与 `apply_ndk_patches`（NDK sysroot 补丁）共用根目录
[patch-rules.conf](./patch-rules.conf) 中的规则，应用前对补丁内容做占位符替换：

| 规则 | 替换为 |
|---|---|
| `@TERMUX_PREFIX@` / `@ROOTFS_PREFIX@` | `${prefix}`（rootfs 安装前缀） |
| `@TERMUX_HOME@` | `${rootfsHome}` |
| `@APP_PREFIX@` | `${appPrefix}`（App 侧 termux 前缀，X11 socket 所在） |

**关于 `__TERMUX__` 宏**：`__TERMUX__` / `__TERMUX_PREFIX__` 是编译期宏，由 NDK sysroot
补丁（`ndk_patches/*/sys-cdefs.h.patch`）定义，不要在 `patch-rules.conf` 中做文本替换。

## 开源协议处理

每个 `projects/*/build.sh` 声明 `license` 字段（SPDX 标识）。打包时
`install_license()` 从源码目录查找协议文件并安装到
`${prefix}/share/licenses/${pjName}/`；`gen_notices()` 汇总生成
`${prefix}/share/licenses/THIRD_PARTY_NOTICES`。

## 鸣谢

[termux-packages](https://github.com/termux/termux-packages)
