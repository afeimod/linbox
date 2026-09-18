# v1.17 修复包 — 安卓 seccomp（Bad system call）根因修复 + CI 加固

## 本包解决什么

真机报错定性（两处独立问题）：

1. **`Bad system call`（致命）**
   tarball 的 `sysroot-arm` 用的是 Ubuntu noble 的 vanilla glibc 2.39。
   它的 loader/libc 启动即调用 `statx` / `rseq` / `clone3`，而安卓 App 域
   seccomp 白名单只放行 bionic 使用的系统调用 → 内核直接 SIGSYS 击杀，
   wine 零输出瞬间死。**任何重试、换装顺序都无法绕过，必须换 glibc。**
   修复：CI 改用 termux gpkg 的 **"GNU libc for Android"**（Mobox /
   glibc-runner 同源补丁：disable-clone3、fstatat 弃 statx、rseq 去注册、
   faccessat2 降级、fakesyscall 兜底层），与设备端 glibc-runner 环境同一套
   glibc。wrapper 同时加 `GLIBC_TUNABLES=glibc.pthread.rseq=0` 双保险与
   启动自检探针（再出 seccomp 问题会当场打印人话指引）。

2. **`Aborted` + `am broadcast 失败`（DAC 窗口拉不起来）**
   设备端 `am`（app_process）原生崩溃，多与 ROM/环境有关；wrapper 已增强：
   打印 am 退出码、提示 `logcat -d -b crash | tail -30` 看崩溃栈。
   **绕过方案（现在就能用）：直接在 LinBox 里点开「DAC 显示器」应用**，
   wine 的 winedac 会持续重连，窗口就绪自动接上。

3. **CI wine/dxvk 双挂**
   wine job：apt 加固（重试×3 + 清列表），保留 gcc-14 交叉兜底；
   新增 gpkg glibc 下载步骤 + 产物强校验（sysroot 必须是 Android 版，
   否则 job 直接红）。
   dxvk job：apt/pip 重试加固；build-dxvk.sh 的 clone/submodule 加重试。

## 怎么用

```bash
# 方式 A：脚本一键覆盖（推荐）
unzip linbox-dac-source-v1.17.zip && cd linbox-dac-source-v1.17
./tools/apply-v1.17.sh /path/to/linbox

# 方式 B：手动覆盖 3 个文件
#   .github/workflows/linbox-build.yml
#   wine/build_wine_dac.sh
#   dxvk/build-dxvk.sh
```

然后 commit + push，再手动 dispatch（Actions → LinBox 构建 → Run workflow →
component 选 **all**）。

push 只自动构建 APK；**wine/dxvk 必须手动 dispatch 才会构建**。

## 设备端换装（CI 全绿后）

```sh
# 1. 卸载旧 APK（CI 签名每次随机，无法覆盖安装；卸载会清 $HOME）
# 2. 安装新 APK（LinBox-release-N）
# 3. 重进 LinBox 终端：
rm -rf $HOME/wine-dac-9.2-x86_64        # 清掉旧 tarball
tar -xJf wine-dac-9.2-x86_64.tar.xz -C $HOME
ls $HOME/wine-dac-9.2-x86_64/bin/box64   # 必须存在
ls $HOME/wine-dac-9.2-x86_64/sysroot-arm/lib/libc.so.6
strings $HOME/wine-dac-9.2-x86_64/sysroot-arm/lib/libc.so.6 | grep -m1 "GNU C Library"
#   ↑ 应显示 "GNU C Library (GNU libc for Android...) stable release version 2.44-0"

# 4. LinBox 保持前台，跑：
$HOME/wine-dac-9.2-x86_64/bin/wine explorer /desktop=dac,1280x720 taskmgr
```

## 如果 DAC 窗口仍未就绪

- 先点开 LinBox 里的「DAC 显示器」应用再跑 wine（绕过 am）；
- `logcat -d -b crash | tail -30` 看 am 崩溃栈；
- `getprop ro.build.version.release` 报一下安卓版本（诊断 seccomp 严格度）。
