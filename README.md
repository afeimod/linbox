# LinBox

> 一个 Android 平台的 Windows 风格桌面模拟器，支持 Win95 / XP / 7 / 10 / 11 五代主题切换，内置浏览器、文件管理器、**真实 Termux 终端**等 10 个应用，支持自定义快捷方式。

## 项目简介

LinBox 是一个用 **Kotlin + Jetpack Compose** 编写的 Android 桌面启动器（Launcher），将手机桌面改造为 Windows 操作系统的视觉体验。整个项目作为独立 GitHub 仓库交付，可克隆、编译、二次开发。

## 演示图

* 简单演示图

![donate](https://github.com/afeimod/LinBox/blob/main/Screenshot_2026-09-06-15-57-23-134_com.linbox.jpg?raw=true)


### 核心特性

- **5 套 Windows 主题切换** — Windows 95 / XP / 7 / 10 / 11，每套主题都有独立的任务栏样式、窗口边框、配色方案和壁纸，切换后整个 UI 焕然一新
- **真实 Termux 终端（v2.22）** — 完整移植官方 Termux（termux-app v0.118.0）：真实 PTY + login shell、官方 bootstrap 根文件系统（apt/dpkg/pkg 全家桶）、pkg 安装真实软件包（python/git/openssh…）、两排快捷键栏、LinBox 桌面联动命令（theme/start/open），详见 [docs/TERMUX.md](docs/TERMUX.md)
- **混合窗口模型** — 主要应用（浏览器/文件管理器）全屏运行，辅助应用（记事本/计算器/设置）以可拖拽、可缩放、可最小化/最大化的浮动窗口运行
- **完整浏览器** — 基于 WebView 重新实现，支持多标签页、前进/后退/刷新、地址栏、书签、历史记录、首页快捷导航，**支持通过 SAF 读取本地 HTML 文件并渲染**
- **快捷方式系统** — 长按桌面空白处弹出右键菜单 → 新建快捷方式，支持网页 URL / 本地 HTML 文件 / 应用 三种类型，可自定义名称和 emoji 图标
- **9+1 个内置应用** — 浏览器、文件资源管理器、设置、记事本、计算器、系统信息、图片查看器、时钟、终端（真实 Termux + 简易终端并存）
- **可作为默认 Launcher** — 在 Manifest 中注册了 `HOME` category，可选择设为系统桌面
- **持久化存储** — 使用 Room 数据库保存快捷方式/书签/历史记录，DataStore 保存偏好设置

### 设为默认 Launcher（可选）

应用在 Manifest 中已注册 `HOME` category。安装后：

- 按下 Home 键 → 系统弹出 Launcher 选择器 → 选择 `LinBox`
- 或在 `设置 → 应用 → 默认应用 → 桌面` 中选择 `LinBox`

## 启动音效（可选）

由于版权原因，项目不附带微软原始启动音效。如需启用：

1. 合法获取各 Windows 版本启动音效 mp3
2. 重命名并放入 `app/src/main/assets/sounds/`：
   - `win95.mp3`
   - `winxp.mp3`
   - `win7.mp3`
   - `win10.mp3`
   - `win11.mp3`
3. 重新构建

音效文件缺失时应用静默跳过，不影响功能。

## 已知限制

- **WebView 命令式操作**：当前 `goBack()` / `goForward()` / `refresh()` 通过 WebView 实例引用调用，配置变更后可能需要重新绑定（多数场景下正常工作）
- **浮动窗口拖拽**：使用 `detectDragGestures` 直接更新坐标，未实现边缘吸附
- **文件管理器虚拟 FS**：当前为硬编码模拟结构，未持久化用户创建的文件
- **启动音效**：需用户自行放入 mp3 文件
- **图标渲染**：使用 emoji 作为应用图标兜底，正式发布建议替换为矢量图标

## 许可证

MIT License — 见 [LICENSE](LICENSE)

## 捐赠支持

* 想捐钱我喝杯热水（¥0.01 起捐）

![donate](https://github.com/afeimod/NesStation/blob/main/IMG_20260906_153806.jpg?raw=true)

![donate](https://github.com/afeimod/NesStation/blob/main/IMG_20260906_153816.jpg?raw=true)


## 致谢

- Jetpack Compose 团队提供的现代声明式 UI 框架
- Android Room 团队的数据持久化方案
- 所有 Windows 版本的设计师，他们的工作启发了本项目
- ruffle针对flash的优化

---

**注意**：本项目是独立的 Windows 风格桌面模拟器，不包含任何微软公司的代码、资源或商标。所有视觉元素均为程序化生成的原创作品，仅用于学习和致敬。
