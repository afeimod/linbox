# Android Bionic Rootfs 开发规范

本文档用于指定 AI agent 开发时的标准。

# 1. 基本信息

- 原理：通过安卓官方的ndk交叉编译工具链编译能在Bionic环境运行的二进制文件
- 项目类型：核心使用shell编写
- 工作目录：`wsDir`（通过 `readlink -f "$0"` 获取脚本所在目录）

# 2. Shell 代码风格

## 2.1 基础规范

- Shebang: `#!/usr/bin/env bash`
- 首行: `set -euo pipefail`
- 缩进: 2 空格（禁止 Tab）
- 行尾: LF
- 字符集: UTF-8
- 变量引用: 统一使用 `${var}` 格式，不使用 `$var`（除非在 `for`/`case` 等简单场景）

## 2.2 命名规范

- **全局变量/函数**: camelCase（如 `targetArch`、`pkgDir`、`build_system`、`make_pkg`）
- **局部变量**: 下划线前缀 `_var`（如 `_targetArch`、`_pkgArchs`、`_patchFiles`）
- **内部关联数组**: 双下划线前后缀 `_name_`（如 `_rgraph_`、`_indeg_`、`_visited_`、`_allPkgs_`、`_buildOrder_`）
- **常量/配置**: 全大写（如 `CCACHE_DIR`、`PKG_CONFIG_LIBDIR`）

## 2.3 错误处理

统一使用 `|| { echo "错误信息" && exit 1; }` 模式：

```bash
cmake .. || configure_err
make -j$(nproc) || compile_err
patch -p1 < "$_patch" || { echo "补丁应用失败=> $_patch" && exit 1; }
```

## 2.4 条件测试

- 统一使用 `[[ ]]` 双中括号
- 快捷短路：`[[ -z "$var" ]] && { echo "错误"; exit 1; }`
- 变量存在性：`${var:-}`（如 `${2:-}`、`${args:-}`）

## 2.5 注释风格

- 中文注释为主
- 使用 `# @section` 标记分段（如 `@declare`、`@license`、`@help`、`@src`、`@patch`、`@package`、`@dep`、`@build`）

# 3. projects/*/build.sh 规范

## 3.1 变量声明顺序（严格遵守）

```bash
# 1. 版本号（urlType=tar 时 url 中引用 ${revision}）
revision="x.y.z"

# 2. 源码地址
url="https://..."

# 3. 源码类型
urlType="git"  # git | tar | 7z | wget | local | others

# 4. 源码子目录（可选）
pkgSrcDir="${srcDir}/..."

# 5. 目标架构
arch="aarch64 x86_64"

# 6. 构建系统
buildSys="cmake"  # cmake | meson | autotools | make | others | debug

# 7. 开源协议（SPDX 标识）
license="MIT"

# 8. 特殊标记（按需）
# doNotMakePackage=1
# doNotApplyPatch=1

# 9. 构建参数
args="..."

# 10. 依赖项
deps="..."

# 11. 自定义函数（放在变量声明之后）
pre_setup() { ... }
custom_configure() { ... }
```

## 3.2 urlType 取值

| 值 | 行为 |
|---|---|
| `git` | `git clone --recursive --depth=1 -b ${revision}` |
| `tar` | `wget` + `tar --strip-components=1` |
| `7z` | `wget` + `7z x` |
| `wget` | 仅下载到 `/tmp/download-src/` |
| `local` | 使用已有 `src/<pjName>` 目录 |
| `others` | 必须定义 `custom_url()` 函数 |

## 3.3 buildSys 取值

| 值 | 说明 |
|---|---|
| `cmake` | 使用 `cmakeBaseArgs`，支持 Ninja/Make |
| `meson` | 使用 `mesonBaseArgs`，自动生成 cross file |
| `autotools` | 使用 `configureBaseArgs`，支持 autogen/autoreconf |
| `make` | 同 autotools |
| `others` | 必须定义 `extra_fuction()`，完全自定义 |
| `debug` | 进入交互 shell 调试环境 |

## 3.4 自定义函数钩子

| 函数 | 触发时机 | 说明 |
|---|---|---|
| `pre_setup()` | configure/setup 之前 | 准备源码、追加参数、设置工具路径 |
| `custom_url()` | `urlType=others` | 自定义源码获取 |
| `custom_patch()` | apply_patches 阶段 | 替代默认 `patch -p1` |
| `custom_configure()` | autotools configure 阶段 | 完全替代默认 configure |
| `install()` | make/meson/cmake install 阶段 | 替代默认安装，传入 `DESTDIR`/`PREFIX` |
| `extra_fuction()` | `buildSys=others` | 完全自定义构建流程 |
| `pre_package()` | 打包前 | 打包前的额外处理 |

**注意**: `extra_fuction` 拼写为 `fuction`（非 function），这是项目约定。

## 3.5 构建行为开关

| 变量 | 作用范围 | 说明 |
|---|---|---|
| `doNotApplyPatch=1` | 全部 | 跳过补丁应用 |
| `doNotUseAutogenSh=1` | autotools | 不执行 autogen.sh |
| `argsToAutogenSh=1` | autotools | 参数传给 autogen.sh |
| `forceAutoreconf=1` | autotools | 强制 `autoreconf -fi` |
| `doNotUseNinja=1` | cmake | 用 make 替代 ninja |
| `doNotRunCMakeInstallOnCmake=1` | cmake | 用 `make install` |
| `doNotMakePackage=1` | 全部 | 无包项目，不生成 tar |

# 4. 变量与环境

## 4.1 核心变量（default.conf）

```bash
ndkURL="..."           # NDK 下载地址（当前 r29）
ndkDir="${wsDir}/ndk"  # NDK 本地路径
srcDir="${wsDir}/src"  # 源码目录
pkgDir="${wsDir}/pkgs" # 打包输出目录
prefix="/data/data/com.linbox/files/rootfs/usr"
rootfsBase="/data/data/com.linbox/files/rootfs"
rootfsHome="/data/data/com.linbox/files/rootfs/home"
appPrefix="/data/data/com.linbox/files/usr"   # App 侧前缀（X11 socket 所在）
api="28"               # NDK API 版本
```

## 4.2 load_env() 导出的环境变量

构建前 `load_env "$targetArch"` 会设置交叉编译环境：

```bash
CC="ccache ${targetArch}-linux-android${api}-clang"
CXX="ccache ${targetArch}-linux-android${api}-clang++"
AR="llvm-ar"
STRIP="llvm-strip"
RANLIB="llvm-ranlib"
LD="ld.lld"
CFLAGS="--sysroot=... -I${prefix}/include -O3 -pipe"
LDFLAGS="--sysroot=... -L${prefix}/lib -s"
```

**注意**: `load_env` 的导出变量会在 `empty.sh` 中 `unset`，不会跨包泄漏。

## 4.3 base-configure-args.conf

```bash
configureBaseArgs=(--enable-shared --disable-static --prefix=${prefix} ...)
mesonBaseArgs=(--buildtype=release --strip --prefix=${prefix} ...)
cmakeBaseArgs=(-DCMAKE_BUILD_TYPE=Release -DCMAKE_SYSTEM_NAME=Android ...)
```

构建系统会自动加载此文件，`args` 附加在其后。

# 5. 补丁机制

## 5.1 补丁文件位置

`projects/<pjName>/*.patch`，按文件名排序应用。

## 5.2 占位符替换（patch-rules.conf）

| 规则 | 替换为 |
|---|---|
| `@TERMUX_PREFIX@` | `${prefix}` |
| `@TERMUX_PREFIX_CLASSICAL@` | `${prefix}` |
| `@ROOTFS_PREFIX@` | `${prefix}` |
| `@ROOTFS_BASE@` | `${rootfsBase}` |
| `@TERMUX_BASE_DIR@` | `${rootfsBase}` |
| `@TERMUX_HOME@` | `${rootfsHome}` |
| `@APP_PREFIX@` | `${appPrefix}`（App 侧 termux 前缀，X11 socket 所在） |

- 规则文件格式为 `@占位符@ 变量名`，由 `expand_patch_placeholders()` 运行时替换，不写死任何路径
- 变量未定义、或替换后仍残留 `@TERMUX_*@`/`@ROOTFS_*@` 占位符时展开失败并中止
- 不要在补丁中做 `__TERMUX__` → `__ANDROID__` 的文本替换

## 5.3 NDK 补丁

`ndk_patches/<version>/*.patch`，应用到 NDK sysroot，由 `apply_ndk_patches()` 处理。

# 6. 打包与协议

## 6.1 打包流程

`package()` 生成 `${pjName}-${revision}-${targetArch}.tar` 到 `pkgs/` 目录。

## 6.2 协议文件安装

`install_license()` 从源码目录自动查找 `LICENSE`/`COPYING`/`NOTICE` 等文件，安装到 `${prefix}/share/licenses/${pjName}/`。

- 优先在 `${pkgSrcDir:-src/${pjName}}` 中查找
- `pkgSrcDir` 指向子目录时回退父目录

## 6.3 自定义协议文件

```bash
agreementTargetFile="src/foo/LICENSE src/foo/COPYING"   # 多文件
agreementTargetFile="src/foo/COPYING*"                  # 通配符
agreementTargetFile="${wsDir}/patches/EXTRA_LICENSE"    # 绝对路径
```

## 6.4 第三方清单

`gen_notices()` 汇总所有 `projects/*/build.sh` 的 `license`/`url`，生成 `THIRD_PARTY_NOTICES`。

# 7. 禁止事项

- 禁止使用 Tab 缩进
- 禁止在 `build.sh` 中硬编码 `${prefix}` 路径（使用变量）
- 禁止在 `build.sh` 之间共享变量（`empty.sh` 负责重置）
- 禁止在补丁中做 `__TERMUX__` → `__ANDROID__` 的文本替换
- 禁止修改 `empty.sh` 中已声明的变量重置列表（新增变量需同步更新）
- 禁止在 `extra_fuction` 之外的自定义函数中调用 `exit 1`（应 `return 1`）
- 禁止在 `build.sh` 中使用 `source` 或 `.` 加载其他 `build.sh`

# 8. 提交信息格式

```
<type>(<scope>): <中文描述>
```

| type | 说明 | 示例 |
|---|---|---|
| `feat` | 新功能（新增软件包、新增构建选项） | `feat(zstd): 新增 zstd 压缩库` |
| `fix` | Bug 修复（补丁修正、编译错误修复） | `fix(pulseaudio): 修复 sles-sink 链接路径` |
| `refactor` | 重构（既不修 Bug 也不加功能） | `refactor(build): 提取公共拓扑排序逻辑` |
| `chore` | 杂项维护（清理无用文件、更新脚本） | `chore: 清理 legacy 项目` |

- scope：包名（如 `ffmpeg`、`pulseaudio`）或 `build`（构建系统变更）
- 简明扼要，中文描述

# 9. 注意事项

- 未经允许不要在宿主机执行编译和构建

- 以软件包最小化为目标，如果非必要不得加入多余软件包

- 此构建系统目标为*构建可以在安卓运行wine的wayland环境*