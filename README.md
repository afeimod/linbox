# LinBox

> 一个 Android 平台的 **Termux 终端 + X11 图形** 一体化应用：单个 APK 里同时拥有真实 Termux 终端（apt/pkg 全家桶）和内置 X11 显示端（显示号 `:13`），窗口化运行，支持虚拟键盘 / 虚拟鼠标 / 虚拟游戏手柄。

## 项目简介

LinBox 是一个用 **Kotlin + Jetpack Compose** 编写的 Android 应用：没有桌面环境、没有启动器外壳，只做两件核心事情 —— **终端** 与 **X11 图形**，并为其配备完整的输入支持（虚拟键盘 / 鼠标 / 触控板 / 游戏手柄）。整个项目作为独立 GitHub 仓库交付，可克隆、编译、二次开发。

## 演示图

* 简单演示图

![demo](https://github.com/afeimod/LinBox/blob/main/Screenshot_2026-09-06-15-57-23-134_com.linbox.jpg?raw=true)


### 核心特性

- **真实 Termux 终端** — 完整移植官方 Termux（termux-app v0.118.0）：真实 PTY + login shell、官方 bootstrap 根文件系统（apt/dpkg/pkg 全家桶）、pkg 安装真实软件包（python/git/openssh…）、两排快捷键栏 + 可切换符号层，详见 [docs/TERMUX.md](docs/TERMUX.md)
- **内置 X11 图形（显示号 :13）** — 内置 X server（lorie 合成器 + Xwayland），终端 `linbox-x11 :13` 一条命令即可把图形化 Linux 桌面（xfce4 / openbox 等）或游戏投射为应用内的一个窗口；分辨率"跟随窗口"或"固定分辨率"随时切换，详见 [docs/X11.md](docs/X11.md)
- **浮动窗口模型** — 终端 / X11 / 设置以可拖拽、可缩放、可最小化/最大化的浮动窗口运行，控制条一键真全屏
- **虚拟键盘** — 应用内全键盘（可拖动、功能键行 + 小键盘、大小可调），文本框聚焦自动呼出，映射真实按键事件
- **虚拟鼠标 / 触控板** — Windows 风格指针跟随手指，或整屏触控板模式（双指右键、滚轮、拖拽手势）
- **虚拟游戏手柄** — 屏幕虚拟摇杆 / 十字键 / 按钮，可自定义布局与按键映射（键盘 + 鼠标动作），X11 游戏专用，布局持久化
- **5 套窗口配色主题** — Windows 95 / XP / 7 / 10 / 11 窗口风格切换（仅影响窗口与控件配色）
- **离线 bootstrap 安装** — 内置 aarch64 离线归档，安装时同长度重写 `com.termux → com.linbox` 路径前缀，无需联网即可获得完整终端环境

### 架构

| 模块 | 说明 |
|------|------|
| `app` | 主应用（com.linbox）：终端、X11 显示端、输入覆盖层、最小壳层 |
| `termux-x11` | X server 显示端（com.termux.x11，lorie + Xwayland 预编译库） |
| `termux-x11-stub` | 隐藏 API 编译桩（compileOnly） |

```
app/src/main/java/com/linbox/
├── LinBoxApp.kt / MainActivity.kt   # 应用入口 + 最小壳层宿主
├── apps/terminal/                   # Termux 移植终端（TerminalApp + bootstrap 安装器）
├── apps/x11/                        # X11 显示端（窗口渲染 / 控制器 / 设置 / 保活服务）
├── apps/settings/                   # 设置（显示 / 主题 / 输入 / 手柄 / 关于）
├── core/shell/LinBoxShell.kt        # 主屏启动器 + 窗口 + 输入覆盖层
├── core/window/                     # 浮动窗口系统（拖拽 / 缩放 / 最小化 / 全屏）
├── core/input/                      # 虚拟鼠标 / 键盘 / 触控板 / 游戏手柄
├── core/theme/                      # 窗口配色主题
├── data/prefs/                      # DataStore 偏好
├── termux/                          # termux-app 移植（终端模拟器 + 视图，Apache-2.0）
└── util/                            # L10n / 沉浸式工具
```

## 构建

- Android Studio 打开根目录，或使用 GitHub Actions（`.github/workflows/build.yml`，**仅手动触发** workflow_dispatch，可在 Actions 页选择 build_type / JDK / Gradle 版本）
- NDK 26.3.11579264（CI 自动安装）
- `./gradlew assembleDebug` / `assembleRelease`

## X11 快速上手

```bash
pkg install x11-repo && pkg install xfce4 dbus   # 首次
linbox-x11 :13                                    # 启动 X server + xfce4 会话
```

服务启动后 LinBox 内会自动弹出 X11 窗口并连接渲染；也可以在主屏启动器点击「X11 图形」先开窗口等待连接。

## 已知限制

- **浮动窗口拖拽**：使用 `detectDragGestures` 直接更新坐标，未实现边缘吸附
- **bootstrap 归档**：离线包仅含 aarch64；其他架构打开终端时会收到明确提示
- **图标渲染**：部分应用图标使用 emoji 兜底，正式发布建议替换为矢量图标

## 许可证

MIT License — 见 [LICENSE](LICENSE)

## 致谢

- [termux-app](https://github.com/termux/termux-app)（Apache-2.0）— 终端模拟器与 PTY 移植来源
- [termux-x11](https://github.com/termux/termux-x11) — X server 显示端来源
- Jetpack Compose 团队提供的现代声明式 UI 框架

---

**注意**：本项目不含任何微软公司的代码、资源或商标。窗口主题仅以配色致敬各 Windows 时代的视觉风格，仅用于学习和致敬。
