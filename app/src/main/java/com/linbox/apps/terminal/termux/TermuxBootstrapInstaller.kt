package com.linbox.apps.terminal.termux

import android.content.Context
import android.system.Os
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * LinBox Termux 移植版的 Bootstrap 安装器。
 *
 * 职责（对应上游 TermuxInstaller 的功能，代码为面向 LinBox 的独立实现）：
 *
 * 1. 从 APK assets 读取官方 bootstrap（termux-packages 官方构建产物，
 *    SHA-256 校验）；
 * 2. 解压到 `$filesDir/usr-staging`；
 * 3. **同长度路径重写**：把 bootstrap 中所有硬编码的 `com.termux`
 *    字节串改写为 `com.linbox`（裸包名模式，覆盖任意出现位置：
 *    `/data/data/com.termux/...` 路径、`com.termux/cache` 缓存路径、
 *    intent 组件名等）——包括 227 个 ELF 二进制内部（.rodata/.dynstr
 *    的编译期路径）、572 个文本文件（shebang / apt 配置 / dpkg 数据库）
 *    以及 SYMLINKS.txt。由于 `com.linbox` 与 `com.termux` 逐字节等长，
 *    替换不改变任何文件长度与 ELF 结构，等效于"以 LinBox 的包名与
 *    路径重新编译"了整个根文件系统；
 * 4. 按 Termux 官方规则设置可执行权限（bin/、libexec/、apt 辅助程序）；
 * 5. 创建官方 SYMLINKS.txt 中声明的符号链接；
 * 6. 原子重命名 staging → `$filesDir/usr`；
 * 7. 安装 LinBox 专属增强（installLinBoxExtras）：profile.d/linbox.sh
 *    （theme/start 等桌面命令注入真实 bash）、命令 FIFO、motd；
 * 8. 安装包工具链（installPackageToolchain）：原生 linbox-reprefix
 *    重写工具 + dpkg 包装器 + linbox-debfix 重打包器 + linbox-mirror
 *    源体检工具 + linbox-glibc 一键脚本——官方源的 deb
 *    按 com.termux 前缀构建（tar 成员路径即绝对路径），安装前自动
 *    重打包、装完再增量重写，保证 pkg/apt 装的软件开箱即用；
 * 9. 存量安装增量迁移（migrateIfNeeded）：按修订号检测旧版本安装，
 *    免清数据升级增强组件并全量重写既有文件。
 */
object TermuxBootstrapInstaller {

    /**
     * 0700 的十进制值（Kotlin 不支持八进制字面量）：
     * 目录/可执行文件的属主读写执行权限，对齐官方 TermuxInstaller。
     */
    private const val PERMISSION_0700 = 448
    private const val PERMISSION_0644 = 420
    private const val PERMISSION_0600 = 384

    /**
     * 增强组件修订号：linbox.sh / 工具链 / motd / apt 源修复内容变更时 +1。
     * 已安装的 bootstrap 检测到修订号落后时会自动增量迁移（免清数据）。
     * rev 3：新增 linbox-mirror（pool 级源体检/切源）+ 存量安装的
     * sources.list 自动修复（老版 pkg 轮换到的 packages-cf 镜像对
     * pool 目录的 .deb 一律 403，导致 pkg update 与 linbox-glibc 全部失败）。
     * rev 4：修复 dpkg 包自升级击杀 bin/dpkg 包装器的致命缺陷（fix7）。
     * 旧版包装器只部署在 bin/dpkg 一处，dpkg 包升级时 deb 内的真身 ELF
     * 会覆盖它，此后所有 deb 绕过 linbox-debfix 重打包，pkg update /
     * pkg install 全线报 "unable to stat './data/data/com.termux':
     * Permission denied"。rev 4 起包装器本体改驻 libexec/linbox/dpkg
     * （无任何包会覆盖），apt 经 apt.conf.d/99linbox 的 Dir::Bin::dpkg
     * 固定走包装器；bin/dpkg 保留副本并由包装器/会话启动自愈；
     * linbox-debfix 同步刷新 dpkg.real 并修复盖章污染。
     * rev 5：新增 dpkg path-exclude 双保险（fix8）。官方 deb 中偶有
     * 未经 linbox-debfix 重写的 /data/data/com.termux 成员（典型如裸
     * 目录条目 ./data/data/com.termux，无尾斜杠），dpkg lstat 无权访问
     * 报 Permission denied；写入 etc/dpkg/dpkg.cfg.d/99-linbox-fix 与
     * etc/apt/apt.conf.d/99-linbox-fix（path-exclude + force-confold）
     * 让 dpkg 跳过这些成员而非报错，并消除配置文件交互提示。
     * rev 6：linbox-debfix v3（fix8.1）。实测 dpkg 的 filter 检查发生在
     * lstat 之后，path-exclude 对 EACCES 场景无效——官方前缀成员必须
     * 在落盘前消灭。debfix v3 把全部静默失败路径改为 fail-loud + 不盖章
     * 可重试，并新增目录改名降级保底（reprefix 失效时 unpack 仍可通过）
     * 与重建成品验收（dpkg-deb -c 复查无 com.termux 成员才替换）。
     * rev 7：linbox-debfix v4（fix8.2）记账免疫 + apt 缓存切断。
     * 实测定性：v2 时代静默失败盖章的官方 deb 一直留在 apt 下载缓存
     * 中（mtime 恒定），apt 硬链接复用时记账命中直接跳过重写——
     * 同一批包永远失败且无任何提示。v4 在记账命中时也快检成员
     * （dpkg-deb -c + grep -m1，官方 deb 的 com.termux 条目在 tar
     * 前部秒停），残留即强制重跑；同时迁移时清 apt archives 缓存，
     * 双保险切断污染闭环。
     * rev 8：reprefix v5 + debfix v5（fix8.3）引擎级收口。
     * reprefix：只读权限文件（0444/0555）内容改写曾因 EACCES 被静默
     * 跳过，现临时加写位重试后还原；树内改名失败不再静默（--tree
     * 非零退出，debfix 走降级路径）；新增 --verify 复检与 --version
     * 指纹。debfix：验收/记账 grep 收紧为任意 com.termux 成员；
     * 重写后 --verify 复检残留并大声告警；linbox-debfix version
     * 可在设备端一键核验部署状态（会话启动也会打印部署指纹行）。
     * rev 9：debfix v6 + 包装器 v3（fix8.4）检测层根治。
     * rev 8 上机实测：pcre 完整重写成功（count=7），同批 13 包全部
     * 静默失败且零警告——唯一自洽分支 = 记账命中后的 dpkg-deb -c
     * 快检（-c 的成员列表依赖外部 tar，列表为空/失败时 grep 误判
     * 干净→脏 deb 永久静默跳过）。v6 改用字节级扫描（dpkg-deb
     * --fsys-tarfile 内建解压不依赖外部 tar + grep 扫描 com.termux
     * 字节，"无法验证"一律按脏处理），成品验收增加树路径残余硬门；
     * 包装器在 dpkg 失败且确有修复发生时自动重试一次，同一命令内
     * 自愈。迁移照例清 debfix 记账与 apt archives 缓存。
     * rev 10：可观测层 + 独立第二路径（fix8.5）。rev 9 上机复测：v6 摘要
     * 行照常打印（libcrypt/pcre），但同批 13 包依旧零输出失败（其中
     * command-not-found_3.5.0-10 为全新版本，不可能有记账——v6 对全新
     * 官方 deb 必然打印摘要行）。对策：① 文件日志 pkgfix.log：包装器
     * 每次调用的参数全量与 debfix 每个决策（含静默跳过）全部落盘，
     * cat 即可定位（终端丢行不再影响诊断）；② Pre-Install-Pkgs 钩子
     * （本函数随 apt.conf 一并写入）：apt 调 dpkg 前把全部 deb 路径经
     * stdin 喂给 linbox-debfix，绕过包装器循环；③ 包装器自检：不信任
     * 记账，字节级复查每个 deb 参数，残留即可见地再修；④ apt 缓存
     * 预扫描：包装器每次调用先把缓存中的官方 deb 重写为净品；
     * ⑤ 会话启动一次性 linbox-reprefix --full 全量重写 dpkg 数据库
     * （info 目录下的 *.list、status 历史残留 com.termux 路径）。
     * rev 11：dpkg 真身晋升安全门 + 动态库解析保障（fix8.6）。
     * 事故定性（pkgfix.log 2026-09-06）：debfix 在 Pre-Install 钩子
     * 里把重写树中的新 dpkg 1.22.6-5 直接换入 dpkg.real，而新版
     * 官方包改用 DT_RUNPATH（bionic 对主执行文件不认）且 App 会话
     * 环境缺 LD_LIBRARY_PATH（上游 Termux 必设，LinBox 遗漏），
     * 新真身首次链接即 CANNOT LINK EXECUTABLE ... libmd.so not
     * found，事务内所有后续 dpkg 调用全部 rc=1（pkg upgrade 整体
     * 失败、无任何包装上）。对策：① TermuxEnvironment.build-
     * Environment 补设 LD_LIBRARY_PATH=$PREFIX/lib（对齐上游，
     * /system 二进制走系统命名空间不受影响）；② 包装器无条件兜底
     * 导出同一变量，覆盖 failsafe/adb 等绕过 App 环境的调用方；
     * ③ sync_dpkg_real 与包装器晋升路径均先跑 --version 自检，
     * 不可运行则暂存 dpkg.real.pending、保留旧真身继续干活，待
     * 依赖（如随事务解包的 libmd）就绪后自动晋升——dpkg 永远
     * 不会再被换入的真身打死。
     * rev 12：内置 X11 桌面客户端（v2.22.2）—— 部署 termux-x11 /
     * linbox-x11 / linbox-x11-stop 三个终端命令与宿主组件定位文件
     * etc/linbox-x11.env（终端侧 linbox-x11 经 app_process 拉起
     * CmdEntryPoint X server，App 端全屏 X11 桌面自动弹出）。
     * rev 13：X11 客户端防覆盖——脚本主副本落盘 etc/linbox/x11/；
     * debfix v6.3 对 termux-x11-nightly 包强制替换 bin/termux-x11
     * 为内置客户端（官方包会覆盖脚本且其类名经重写后不存在），
     * linbox-x11 启动前也按标记自愈。
     * rev 17（fix9.6）：X11 显示端改桌面窗口模式（广播 → 桌面弹窗 →
     * fd 连接渲染，全屏 Activity 转兼容兜底）；linbox-x11/termux-x11
     * 脚本文案同步（窗口模式说明 + 手动会话 dbus-launch 提示）。
     * rev 18（fix9.8）：会话依赖链接自检——linbox-x11 启动会话前对
     * dbus-daemon/xfwm4/xfce4-panel/xfdesktop 逐个探测动态链接，缺库
     * （CANNOT LINK EXECUTABLE，实证案例：dbus 在而 libexpat.so.1 缺）
     * 时直接给出 pkg install/reinstall 精确修复命令并暂停自动会话，
     * 不再只吐 dbus-launch 的 "EOF in dbus-launch ..." 费解尾错；
     * 兜底导出 LD_LIBRARY_PATH=$PREFIX/lib；doctor 桌面依赖区同步
     * 增加链接自检。
     * rev 19（fix9.9）：pkg/apt 自身也可能无法链接（用户实测 pkg
     * install 报 CANNOT LINK liblz4.so.1 → 包管理器死循环无法自救）：
     * ① 根因一（结构性）：bootstrap 原包把 libexpat.so / libgpg-error.so
     *   以 dev 名入库且 SYMLINKS.txt 无 soname 条目，而 dpkg status
     *   已登记 libexpat installed → 依赖方永远不会再解包它们；
     *   对策：SYMLINKS.txt 补条目重打包（SHA 同步更新，新装治本）；
     * ② 根因二（事故丢失）：dpkg 事故可能整文件丢 liblz4.so.1；
     *   对策：installRescueLibs 从 bootstrap 提取 liblz4/libexpat/
     *   libgpg-error 真身到 etc/linbox/rescue（离线救援库）；
     * ③ 新脚本 bin/linbox-pkgfix：离线补 soname 链接 + 救援库兑底
     *   + 逐二进制实跑验证（不依赖 apt/pkg/网络，幂等）；
     * ④ linbox-x11 rev19：预检失败先自动跑 linbox-pkgfix 再重测。
     * rev 20（fix9.10）：fresh 安装全灭修复（用户截图实锤：9.7~9.9
     * 首次启动终端报 "Bootstrap 安装失败 / termux/x11-xkb.tar.gz"）：
     * ① 根因：XKB 数据曾是独立 assets/termux/x11-xkb.tar.gz，该资产
     *   在用户 CI 仓库管线中整文件丢失（git 提交/检出环节，*.zip 从未
     *   丢过），installX11Client 单点依赖它 → 收尾一步抛
     *   FileNotFoundException → 安装失败 + revision 不落盘 → 每次启动
     *   迁移重跑再失败（死循环）；
     *   对策：XKB 数据改随 bootstrap 归档分发（xkb/ 成员，SHA 同步）+
     *   installXkbData 三级容错（bootstrap 内 → asset → 存量保留），
     *   任何来源缺失仅告警绝不阻断安装；
     * ② 救援链补强：installRescueLibs 条目名归一化 + 逐文件日志；
     *   新增公开 ensureRescueLibs（LinBoxApp 每次启动调用，与迁移
     *   路径互为备份）；linbox-pkgfix v1.1 新增宿主 APK 内嵌 bootstrap
     *   提取（纯 shell 自救，不依赖打开主界面/迁移完成）。
     * rev 21（fix9.11）：linbox-glibc 安装必败修复（用户实测：第 2 步
     *   glibc-runner 事务报 E: Sub-process linbox-debfix returned an
     *   error code (1)，pkgfix 全通过也装不上）：
     *   根因（真实 glibc_2.44 包本地复现实锤）：gpkg（termux-pacman）
     *   构建的 DEBIAN/conffiles 存在尾逗号条目（/data/data/com.termux/
     *   .../etc/gai.conf,——pacman 转换残渣），dpkg-deb -b 硬校验
     *   「conffile 必须存在于包内」→ glibc 重打包直接拒绝 → apt
     *   Pre-Install-Pkgs 钩子 rc=1 → 整个事务中止。对策：
     *   linbox-debfix v6.4 —— ① dpkg-deb -b 前规范化 conffiles
     *   （去尾逗号/空白、剔除树中不存在的条目）；② -b stderr 落盘，
     *   失败时回显 dpkg-deb 真实报错首行（此前被 /dev/null 吞掉）。
     */
    // rev22（v2.22.3 fix10）：部署增强版 glibc-runner 主副本
    // （z 盘悬空自愈 + -d 分辨率握手协议），存量安装随迁移自动就位
    // rev23（v2.22.4 fix11c）：bin/glibc-runner 改为强制覆盖 —— rev22 只在
    // bin 缺失时才部署，存量 glibc 安装里旧官方脚本永不被替换，-d 分辨率
    // 握手因此从未生效（用户实锤：控制条一直显示"X: 跟随窗口"）
    // rev24（v2.22.5 fix13）：fix12 教训 —— [R2]（-dWxH 移除 explorer 虚拟
    // 桌面）改了脚本却没升 revision（仍=23），fix11c 存量用户 extras 已是
    // 23 → 迁移跳过 → 设备上永远跑旧脚本 → 用户实测"-d 后仍是 wine 蓝色
    // 桌面"。今后凡改动 assets/termux/scripts/ 下任何脚本，必须同步升
    // revision！
    // rev25（v2.22.5 fix14）：提示文案更新（游戏全屏 Alt+Enter 引导）
    // rev26（v2.22.6 fix19）：glibc-runner v3.6-linbox1 —— [R4] -v/--vd
    //   wine 虚拟桌面选项（轩辕剑4 类游戏必需）；[R5] -F/--fitwin 撑满
    //   标记（握手值 "WxH fitwin"）；[R6] -d 默认贴合拉伸（App 侧
    //   X11FitClient 策略反转配套）。存量设备随迁移拿到新脚本。
    // rev27（v2.22.7 fix20）：glibc-runner v3.7-linbox1 —— Wine 声音
    //   管线修复：[A1] pulse 服务端宿主侧加固启动（bionic 隔离子壳 +
    //   /data/user/N/ 路径改写 + TCP 4713 就绪等待）；[A2] glibc 音频
    //   客户端库自动补装（libpulse-glibc/alsa-lib-glibc/grep-glibc/
    //   sed-glibc）；[A5] HKCU Drivers=alsa 钉死自愈；[A6] bionic 工具
    //   安全化（sed/pgrep/taskset/date/head）。
    // rev28 = fix21：声音偏小/“被压住”——[V1] pulse sink 音量归一（默认
    //   100%，GLR_AUDIO_VOLUME=50~300 可调）；[V2] --fix-audio 诊断增强
    //   （sink 状态/静音/音量 + 活动流）；[V3] 启动横幅显示生效音量。
    // rev29 = fix22：前缀构建 + Z 盘 + zink——[B1] 修复“无 .wine 前缀时
    //   wineboot 构建失败”（旧版在 wineboot 前预创建 dosdevices 空壳 →
    //   wine 永不创建 drive_c/c: → 前缀报废，每次启动刷 "could not open
    //   working directory C:\windows\system32"；现改走 startonwine 语义
    //   的三重验收自动构建，损坏前缀自动备份重建）；[B2] Z 盘指向
    //   /data/data/com.linbox/files/usr（DRIVE_Z 可改，旧 z:→/ 自动迁移）；
    //   [B3] CWD 盘符映射守卫；[K1] -z/--zink mesa zink 渲染（Adreno/
    //   Turnip，缺件自动补装 mesa-glibc + vulkan 组件）。
    // rev30 = fix23：glibc-runner v3.10-linbox1 + linbox-x11 rev22——
    //   [K3] zink 全链检测（libGL.so.1 由 libglvnd-glibc 独立提供！旧
    //   [K1] 只验 dri/zink_dri.so → 游戏 dlopen libGL.so.1 失败；现五件
    //   齐检 + 四包补装 + --fix-zink 链路诊断 + __GLX_VENDOR_LIBRARY_NAME
    //   条件导出）；[F4] 前缀构建自动应用 repack 资产（fix-fonts.tar.xz/
    //   user.reg/system.reg/dxvk-*.tar.gz → drive_c/windows，标记幂等，
    //   已建前缀首启自动补齐）；[P1] linbox-x11 会话级 pulseaudio 一次
    //   启动（TCP 4713，游戏零开销复用，免手工 pulseaudio --start）；
    //   [S1] tar/cp/7z/date/ls|head 全部 bionic 安全化。
    // rev31 = fix24：glibc-runner v3.11-linbox1——repack 资产直读
    //   startonwine 固定目录 + linboxmeta：[F5] 字体解压(fix-fonts.tar.xz/
    //   marlett.ttf)、注册表导入(user.reg → system.reg → fix-services.reg，
    //   经 wine regedit 原样导入、不再嗅探文件头)、DXVK(opt/dxvk1/ 唯一
    //   来源直接 tar 解压，移除 x64/x32 布局映射)全部按 startonwine v2.6
    //   原样直读 /data/data/com.linbox/files/usr/glibc/opt/prefix(不再做
    //   前缀根/prefix.bak.* 多位置搜索)；[M1] 前缀幂等标记目录
    //   moboxmeta → linboxmeta。
    // rev32 = fix25：glibc-runner v3.12-linbox1——[R7] 新增 -f[WxH]/
    //   --force-desktop[=WxH]（startwine v3.2 同款 -f）。
    // rev33 = fix25 修订（v3.12-linbox2）：-f 语义按用户反馈纠正为
    //   startwine -f 原样两个动作——① explorer /desktop=shell,WxH 直
    //   传参数开窗口显示（-d 不传 explorer 参数无法开窗口）；② 每次
    //   启动前 wine reg delete 清除 HKCU\Software\Wine\Explorer 的
    //   Desktop 值与 Explorer\Desktops 键（Mobox 迁移/winecfg 勾选/
    //   repack user.reg 资产持久化的 wine 背景桌面 → 蓝底+任务栏包住
    //   游戏、与 explorer 桌面叠成双窗口），每次 -f 都执行不做记账。
    //   linbox1 曾实现为"reg add 开桌面、不传 explorer 参数"，与语义
    //   相反，已纠正；旧版遗留注册表键与 .linbox-vd-on 记账自动清除。
    //   裸 -f 默认 800x600；握手分辨率=-f 尺寸；短选项 -f 由 --findlib
    //   让给 --force-desktop（--findlib 保留长选项）。
    // rev34 = fix25 定稿（v3.12-linbox3）：用户实测截图确认 explorer
    //   直传参数与注册表方式创建的是同一个蓝底 wine 桌面窗口（截图一
    //   -f 蓝底仍在；截图二 -d 画面直出无桌面）——linbox3 把 -f 改为
    //   与 -d [R2] 完全同款画面直出：resolution 置空不传任何 explorer
    //   /desktop 参数，游戏窗口直接渲染在项目 X11 桌面窗口；保留每次
    //   -f 启动前 reg delete 清注册表桌面（-d [R3] 同款手段、"强制"
    //   不记账）；握手分辨率=-f 尺寸。旧版遗留注册表键与
    //   .linbox-vd-on 记账在 -f/非 -f 启动时均自动清除。
    // rev35 = fix25 填满对齐（v3.12-linbox4）：用户实测反馈 -f 仍像
    //   旧版 -d 一样"填不满"——linbox4 ① 裸 -f 默认 800x600→1280x720
    //   （与 -d 同款默认）；② -f 握手值固定携带 fitwin 标记（自动叠加
    //   -F 撑满）→ App 侧 X11FitClient 把游戏窗口客户区撑到整个 X 屏幕。
    //   【实测推翻：DirectDraw 固定分辨率老游戏"接受" resize 后仍按原
    //   分辨率绘制（fix19 注释记载的假稳定：-d1024x768 只画 868x652），
    //   右/下黑区烧在 X 画面内部，显示层拉伸后黑边依旧——用户复测
    //   "一样的黑边"】
    // rev36 = fix25 铺满回归（v3.12-linbox5）：-f 握手回归与 -d 完全
    //   同源（写 "WxH"，不再携带 fitwin）→ App 侧走 fix19 默认"贴合"
    //   策略：游戏窗口平移到 (0,0) + X 屏幕缩成游戏客户区 + 显示层
    //   拉伸铺满整个桌面窗口——与用户实测有效的 -d 填满效果逐位一致，
    //   对任意窗口化游戏（含 DirectDraw 固定分辨率老游戏）可靠无黑边；
    //   "撑满"降回 -F/--fitwin 显式可选（现代游戏 -f -F 叠加）。
    // rev37 = fix27 状态加固（v3.12-linbox6）：用户实测反馈 rev36 上 -d
    //   也出现黑块 —— rev35→36 对 -d 零代码差异（脚本/App 侧均未动 -d
    //   路径），定性为前缀内残留状态：linbox1 时代 -f 曾 reg add 注册表
    //   虚拟桌面，被 wine 持久化进 user.reg，而 [R3] 对 -d 的清理是一次
    //   性（.linbox-vd-clean 标记早已盖章），残留永不清理 → 游戏被关进
    //   缩水 wine 桌面、四周黑块。linbox6：① [R3] 升级为每次启动前清理
    //   （与 -f [R7] 同款）；② 新增 --x11-diag 诊断命令（extras 修订/
    //   握手文件/user.reg Explorer 残留/logcat X11 日志，纯读取不启
    //   wine），后续问题凭输出精确定位、不再盲修。
    // rev38 = fix28 级联根治（v3.12-linbox7）：用户深采样 logcat 铁证 ——
    //   wine-9.2 的 -d/-f 都能铺满、proton 的 -d/-f 都右/下黑边。根因：
    //   proton 游戏自带全屏管理器，X 屏每变化一次就把窗口重设为新 root
    //   的 ~96%（1280x720→窗口 1232x693，恰 ×0.9625），旧贴合策略见
    //   "窗口≠X 屏"就缩 X 屏 → 缩屏/缩窗互相驱动级联 2-3 轮（1232x693
    //   →1186x667→1129x634）画面越缩越小；wine-9.2 无此反应故两模式
    //   均好。App 侧 fix28（X11FitClient 决策环，握手协议零变化）：稳定
    //   门（窗口尺寸连续两轮一致才动 root）+ 缩屏预算（每次握手 applyFit
    //   ≤2 次）+ 对抗窗钉满（贴合后 4s 内窗口再缩水 → 窗口撑回覆盖整
    //   个 root，root 不动）+ 对抗上限（钉满 4 轮仍被改回 → 放手）+
    //   缩屏下限（≥握手面积 50%）。脚本侧同步：--x11-diag logcat 采样
    //   加深至 -t 20000（旧 -t 240 抓不到完整决策链）。
    private const val EXTRAS_REVISION = 43

    /** 安装状态（Compose 界面订阅渲染）。 */
    sealed class InstallState {
        object NotInstalled : InstallState()
        data class Installing(val progress: Float, val message: String) : InstallState()
        object Installed : InstallState()
        data class Failed(val message: String) : InstallState()
    }

    private val _state = MutableStateFlow<InstallState>(InstallState.NotInstalled)
    val state: StateFlow<InstallState> = _state

    /**
     * 增强组件（linbox.sh / dpkg 包装器 / 重写工具）是否就绪。
     * 存量迁移在后台进行，完成前终端区域显示准备界面，避免首帧
     * 会话读到尚未重写的旧配置。
     */
    private val _extrasReady = MutableStateFlow(false)
    val extrasReady: StateFlow<Boolean> = _extrasReady

    private val installLock = Any()

    /** 全部普通文件入口计数（用于进度估算）。 */
    private val rewriteStats = intArrayOf(0)

    /**
     * bootstrap 是否已安装完成（$PREFIX 存在且含 bin/sh 可执行）。
     */
    fun isInstalled(context: Context): Boolean {
        val sh = File(TermuxEnvironment.binDir(context), "sh")
        return sh.isFile && sh.canExecute()
    }

    /**
     * 入口：按需安装 bootstrap。已安装则直接回调。
     * 结果通过 [state] 与回调双通道通知。
     */
    fun installIfNeeded(
        context: Context,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        synchronized(installLock) {
            if (isInstalled(context)) {
                _state.value = InstallState.Installed
                onDone()
                return
            }
            if (_state.value is InstallState.Installing) return

            _state.value = InstallState.Installing(0f, "准备安装…")
        }

        val appContext = context.applicationContext
        Thread({
            try {
                installInternal(appContext)
                _state.value = InstallState.Installed
                android.util.Log.i(TAG, "Termux bootstrap installed successfully " +
                        "(rewritten files: ${rewriteStats[0]})")
                onDone()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Bootstrap install failed", e)
                _state.value = InstallState.Failed(e.message ?: e.javaClass.simpleName)
                onError(e.message ?: e.javaClass.simpleName)
            }
        }, "termux-bootstrap-installer").start()
    }

    // ------------------------------------------------------------------
    // 核心安装流程
    // ------------------------------------------------------------------

    private fun installInternal(context: Context) {
        val arch = TermuxEnvironment.deviceBootstrapArch()
            ?: throw IllegalStateException(
                "此设备架构（${android.os.Build.SUPPORTED_ABIS.firstOrNull()}）没有对应的离线 bootstrap。" +
                        "当前离线包仅内置 aarch64（arm64-v8a）。"
            )

        val staging = TermuxEnvironment.stagingPrefixDir(context)
        val prefix = TermuxEnvironment.prefixDir(context)

        // 1. 清理旧的 staging / 残缺 prefix
        _state.value = InstallState.Installing(0.02f, "清理旧安装…")
        deleteRecursive(staging)
        deleteRecursive(prefix)
        TermuxEnvironment.filesDir(context).mkdirs()
        staging.mkdirs()
        Os.chmod(staging.absolutePath, PERMISSION_0700)

        // 2. 从 assets 拷贝 bootstrap 到 cache 并校验 SHA-256
        _state.value = InstallState.Installing(0.05f, "读取 bootstrap 归档…")
        val bootstrapFile = File(context.cacheDir, "termux-bootstrap-${arch}.zip")
        copyAssetToFile(context, arch, bootstrapFile)
        verifyChecksum(bootstrapFile, arch)

        // 3. 预扫描：总字节数（进度分母）
        val (entryList, totalUncompressed) = ZipFile(bootstrapFile).use { zip ->
            val collected = mutableListOf<ZipEntry>()
            val en = zip.entries()
            while (en.hasMoreElements()) collected.add(en.nextElement())
            collected to collected.sumOf { it.size }
        }

        // 4. 解压 + 路径重写 + 权限
        _state.value = InstallState.Installing(0.08f, "解压并重写路径…")
        var writtenBytes = 0L
        val symlinks = mutableListOf<Pair<String, String>>() // (target, linkPath)

        ZipFile(bootstrapFile).use { zip ->
            val entries = entryList
            var processed = 0
            for (entry in entries) {
                processed++
                val name = entry.name
                if (name == "SYMLINKS.txt") {
                    // 符号链接表：整表走包名等长重写（覆盖 target 中任意旧包名出现）
                    val (rewritten, _) = rewriteLegacyPaths(zip.getInputStream(entry).readBytes())
                    val text = rewritten.decodeToString()
                    for (rawLine in text.lineSequence()) {
                        val line = rawLine.trim()
                        if (line.isEmpty()) continue
                        val parts = line.split("←")
                        if (parts.size != 2) continue
                        val target = parts[0]
                        val linkPath = File(staging, parts[1]).absolutePath
                        File(linkPath).parentFile?.mkdirs()
                        symlinks.add(target to linkPath)
                    }
                    writtenBytes += entry.size
                    continue
                }

                val targetFile = File(staging, name)
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                    continue
                }
                targetFile.parentFile?.mkdirs()

                val raw = zip.getInputStream(entry).readBytes()
                // **核心**：同长度字节重写 com.termux → com.linbox
                val (rewritten, count) = rewriteLegacyPaths(raw)
                if (count > 0) rewriteStats[0]++
                FileOutputStream(targetFile).use { it.write(rewritten) }

                // 官方权限规则：bin / libexec / apt 辅助程序可执行
                if (name.startsWith("bin/") || name.startsWith("libexec") ||
                    name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")
                ) {
                    Os.chmod(targetFile.absolutePath, PERMISSION_0700)
                }

                writtenBytes += entry.size
                if (processed % 64 == 0) {
                    val frac = 0.08f + 0.82f * (writtenBytes.toFloat() / totalUncompressed.toFloat())
                    _state.value = InstallState.Installing(
                        frac.coerceAtMost(0.9f),
                        "解压并重写路径… $processed/${entries.size}"
                    )
                }
            }
        }

        if (symlinks.isEmpty()) throw IllegalStateException("bootstrap 中缺少 SYMLINKS.txt")

        // 5. 创建符号链接
        _state.value = InstallState.Installing(0.92f, "创建符号链接…")
        for ((target, linkPath) in symlinks) {
            val link = File(linkPath)
            if (link.exists()) link.delete()
            try {
                Os.symlink(target, linkPath)
            } catch (e: Exception) {
                // 个别符号链接失败不致命（如目标为可选组件）
                android.util.Log.w(TAG, "symlink 创建失败: $linkPath -> $target (${e.message})")
            }
        }

        // 6. 原子重命名 staging → prefix
        _state.value = InstallState.Installing(0.96f, "完成安装…")
        if (!staging.renameTo(prefix)) {
            throw IllegalStateException("移动 staging 目录到 prefix 失败")
        }
        Os.chmod(prefix.absolutePath, PERMISSION_0700)

        // home / tmp
        val home = TermuxEnvironment.homeDir(context)
        home.mkdirs()
        val tmp = TermuxEnvironment.tmpDir(context)
        tmp.mkdirs()

        // 6.5 apt/dpkg 运行期目录（libapt-pkg 编译的缓存路径在 App cache 下）
        File(context.cacheDir, "apt/archives/partial").mkdirs()
        File(prefix, "var/lib/apt/lists/partial").mkdirs()
        File(prefix, "var/cache/apt/archives/partial").mkdirs()
        File(prefix, "var/lib/linbox/debfix").mkdirs()

        // 7. LinBox 专属增强 + 包工具链
        _state.value = InstallState.Installing(0.98f, "配置 LinBox 集成…")
        installLinBoxExtras(context)
        installPackageToolchain(context)
        revisionFile(context).writeText("$EXTRAS_REVISION\n")
        _extrasReady.value = true

        // 8. 清理缓存归档
        bootstrapFile.delete()
    }

    // ------------------------------------------------------------------
    // 路径重写引擎
    // ------------------------------------------------------------------

    /**
     * 旧/新包名，作为字节模式（等长）。裸包名匹配可覆盖任意出现位置：
     * /data/data/com.termux/... 路径、/data/data/com.termux/cache 缓存
     * 路径（files/ 之外，旧版仅重写 files/ 前缀时被遗漏——正是
     * "E: Archives directory ... Permission denied" 的根因）、
     * am/intent 组件名 com.termux/... 等。
     */
    private val legacyPathBytes =
        TermuxEnvironment.LEGACY_TERMUX_APP_PACKAGE.toByteArray(Charsets.UTF_8)
    private val linboxPathBytes =
        TermuxEnvironment.LINBOX_APP_PACKAGE.toByteArray(Charsets.UTF_8)

    /**
     * 同长度字节重写：把 [legacyPathBytes] 的所有出现替换为
     * [linboxPathBytes]。两者等长时为纯原地替换（文件长度、ELF
     * 段偏移、任何二进制结构都不受影响）；若不等长（防御未来包名
     * 变更），退化为文本 String 替换，并要求新前缀不长于旧前缀。
     *
     * @return (重写后的字节数组, 替换次数)
     */
    private fun rewriteLegacyPaths(raw: ByteArray): Pair<ByteArray, Int> {
        if (legacyPathBytes.size == linboxPathBytes.size) {
            // 快速路径：等长原地替换（文本与二进制通用）
            var count = 0
            val first = legacyPathBytes[0]
            var i = 0
            outer@ while (i <= raw.size - legacyPathBytes.size) {
                if (raw[i] == first && matchesAt(raw, i)) {
                    System.arraycopy(linboxPathBytes, 0, raw, i, linboxPathBytes.size)
                    count++
                    i += legacyPathBytes.size
                    continue@outer
                }
                i++
            }
            return raw to count
        }

        // 防御路径：前缀不等长（当前 LinBox 不会走到这里）
        if (linboxPathBytes.size > legacyPathBytes.size) {
            throw IllegalStateException(
                "新前缀比旧前缀长，无法对二进制做安全重写 " +
                        "(${TermuxEnvironment.LINBOX_FILES_PREFIX} vs ${TermuxEnvironment.LEGACY_TERMUX_FILES_PREFIX})"
            )
        }
        val text = raw.toString(Charsets.UTF_8)
        val replaced = text.replace(
            TermuxEnvironment.LEGACY_TERMUX_APP_PACKAGE,
            TermuxEnvironment.LINBOX_APP_PACKAGE
        )
        return replaced.toByteArray(Charsets.UTF_8) to
                (text.length - replaced.length).coerceAtLeast(0)
    }

    private fun matchesAt(data: ByteArray, offset: Int): Boolean {
        for (k in legacyPathBytes.indices) {
            if (data[offset + k] != legacyPathBytes[k]) return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // LinBox 集成增强
    // ------------------------------------------------------------------

    /**
     * 安装 LinBox 专属组件：
     * - `$PREFIX/etc/profile.d/linbox.sh`：把 theme/start/open 等桌面
     *   命令注入每个真实 bash 会话（通过 FIFO 回传 App）；
     * - `$PREFIX/var/linbox.cmd`：命令 FIFO；
     * - `$PREFIX/etc/motd`：LinBox 版欢迎信息。
     */
    private fun installLinBoxExtras(context: Context) {
        val profileDir = File(TermuxEnvironment.etcDir(context), "profile.d")
        profileDir.mkdirs()

        // 桌面命令集成：linbox.sh（assets 定稿文件，$# 参数检查已验证）
        copyAssetScript(
            context, "termux/scripts/linbox.sh",
            File(profileDir, "linbox.sh"), executable = false
        )
        // LinBox 版 motd（含 pkg / linbox-glibc 用速）
        copyAssetScript(
            context, "termux/scripts/motd",
            File(TermuxEnvironment.etcDir(context), "motd"), executable = false
        )

        // 命令 FIFO
        val fifoPath = TermuxEnvironment.commandFifoPath(context)
        try {
            com.linbox.termux.terminal.TermuxBridge.createFifo(fifoPath)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "FIFO 创建失败（桌面命令桥不可用）: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 包工具链：官方 deb 的前缀重打包 + 安装后增量重写
    // ------------------------------------------------------------------

    /**
     * 安装包工具链（全新安装与存量迁移共用）。
     *
     * 背景：官方仓库的 deb 按 /data/data/com.termux 前缀构建，tar 成员
     * 路径本身就是绝对路径 data/data/com.termux/...，而 dpkg 以
     * instdir=/ 按成员路径落盘——不重写就会写进别的应用的数据目录。
     * 所以：
     * - 原生 linbox-reprefix（liblinbox_reprefix.so，等长重写引擎）；
     * - dpkg 包装器：参数中的 *.deb 先经 linbox-debfix 重打包为
     *   com.linbox 前缀，dpkg.real 执行后再做增量重写（安全网）；
     * - dpkg path-exclude 双保险（fix8，writeDpkgPathExclude）：跳过
     *   官方 deb 中漏经重写的 com.termux 成员，消除 Permission denied；
     * - linbox-glibc：官方 gpkg 流程（glibc-repo + glibc-runner）。
     */
    private fun installPackageToolchain(context: Context) {
        val prefix = TermuxEnvironment.prefixDir(context)

        // (1) 原生重写工具（以 lib*.so 命名才会被 AGP 打包进 APK）
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val src = File(nativeDir, "liblinbox_reprefix.so")
        if (src.isFile) {
            val dst = File(prefix, "bin/linbox-reprefix")
            src.copyTo(dst, overwrite = true)
            Os.chmod(dst.absolutePath, PERMISSION_0700)
        } else {
            android.util.Log.w(TAG, "liblinbox_reprefix.so 缺失，安装后自动重写不可用")
        }


        // (1.5) LinBox DAC 侧车守护进程（merge-into-repo.sh 注入）：
        //       glibc Wine 模式下 AHardwareBuffer 代理（arm64 bionic 进程）
        val dacAllocdSrc = File(nativeDir, "libdac_allocd.so")
        if (dacAllocdSrc.isFile) {
            val dacAllocdDst = File(prefix, "bin/dac_allocd")
            dacAllocdSrc.copyTo(dacAllocdDst, overwrite = true)
            Os.chmod(dacAllocdDst.absolutePath, PERMISSION_0700)
        }


        // (1.6) LinBox DAC 显示脚本（merge-into-repo.sh 注入）：
        //       jniLibs 中以 lib*.so 分发的 shell 脚本 → 还原为 $PREFIX/bin/linbox-dac*
        mapOf(
            "liblinbox_dac.so" to "linbox-dac",
            "liblinbox_dac_doctor.so" to "linbox-dac-doctor",
            "liblinbox_dac_reg.so" to "linbox-dac-reg",
            "liblinbox_dac_unreg.so" to "linbox-dac-unreg",
            "liblinbox_dac_stop.so" to "linbox-dac-stop"
        ).forEach { (lib, name) ->
            val dacScriptSrc = File(nativeDir, lib)
            if (dacScriptSrc.isFile) {
                val dacScriptDst = File(prefix, "bin/$name")
                dacScriptSrc.copyTo(dacScriptDst, overwrite = true)
                Os.chmod(dacScriptDst.absolutePath, PERMISSION_0700)
            }
        }

        // (2) dpkg 包装器三层布局（fix7，对 dpkg 包自升级免疫）：
        //     libexec/linbox/dpkg       包装器本体（apt 经 Dir::Bin::dpkg
        //                               固定调用；libexec/linbox 不属于
        //                               任何软件包，升级永不覆盖）；
        //     libexec/linbox/dpkg.real  dpkg 真身（linbox-debfix 在 dpkg
        //                               包自升级时验证可运行后同步，
        //                               fix8.6 起不可运行则暂存 pending）；
        //     bin/dpkg                  包装器副本（用户直接调用入口；
        //                               被 dpkg 升级覆盖后由包装器与
        //                               linbox.sh 会话启动自愈恢复）。
        val realDpkg = File(prefix, "libexec/linbox/dpkg.real")
        realDpkg.parentFile?.mkdirs()
        val dpkg = File(prefix, "bin/dpkg")
        if (dpkg.isFile && !isOurWrapper(dpkg)) {
            // bin/dpkg 是真身 ELF：可能是全新 bootstrap，也可能是被
            // dpkg 包升级覆盖后的新真身——提升为 dpkg.real（覆盖旧版），
            // 保证 dpkg.real 与系统内 dpkg 版本一致
            if (!dpkg.renameTo(realDpkg)) {
                dpkg.copyTo(realDpkg, overwrite = true)
                dpkg.delete()
            }
            // dpkg.real 不在任何 .list 清单里，显式补丁一次（存量迁移场景）
            runReprefix(context, listOf("--file", realDpkg.absolutePath, "--quiet"))
        }
        copyAssetScript(
            context, "termux/scripts/linbox-dpkg",
            File(prefix, "libexec/linbox/dpkg"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/linbox-dpkg",
            File(prefix, "bin/dpkg"), executable = true
        )
        writeAptDpkgPin(context)
        writeDpkgPathExclude(context)

        // (3) deb 重打包器 + glibc 一键脚本 + 源体检工具
        // 迁移场景同时清理旧版 debfix 的盖章缓存（v2 已修复盖章污染，
        // 清掉历史盖章让缓存中的 deb 重新走一遍完整处理，幂等无害）
        File(prefix, "var/lib/linbox/debfix").deleteRecursively()
        File(prefix, "var/lib/linbox/debfix").mkdirs()
        copyAssetScript(
            context, "termux/scripts/linbox-debfix",
            File(prefix, "bin/linbox-debfix"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/linbox-glibc",
            File(prefix, "bin/linbox-glibc"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/linbox-mirror",
            File(prefix, "bin/linbox-mirror"), executable = true
        )
        // rev19：pkg/apt 动态库链接离线自愈（fix9.9）——bootstrap
        // 缺陷/事故丢库导致 pkg 全灭时的自救工具，部署于安装与迁移
        copyAssetScript(
            context, "termux/scripts/linbox-pkgfix",
            File(prefix, "bin/linbox-pkgfix"), executable = true
        )
        // rev41（v2.28）：tar.xz 导入器——解压任意 Termux rootfs/bootstrap
        // tar.xz 并复用原生重写引擎强制改写全部内嵌 com.termux 路径
        // （文件内容/目录名/符号链接目标），与 pkg 安装时同一套引擎与策略
        copyAssetScript(
            context, "termux/scripts/linbox-tarxz",
            File(prefix, "bin/linbox-tarxz"), executable = true
        )
        // v2.22.3 fix10：增强版 glibc-runner（z 盘修复 + -d 分辨率握手）
        // ① 部署主副本 etc/linbox/glibc-runner（pkg 安装/升级 glibc-runner
        //    包会用官方脚本覆盖 bin/glibc-runner，主副本用于恢复）；
        // ② bin/glibc-runner：v2.22.4 fix11c 起无条件覆盖部署 —— rev22 的
        //    "仅缺失时部署"导致存量安装永远用旧官方脚本（无 -d 握手/z 盘
        //    修复，用户实测黑边+小窗），被替换的旧官方版本份为
        //    bin/glibc-runner.pkg.bak 一次（可随时手工回滚）；
        // ③ linbox-glibc 安装完成后同样做覆盖恢复（见 linbox-glibc 尾部）。
        runCatching {
            val grBin = File(prefix, "bin/glibc-runner")
            val grMaster = File(prefix, "etc/linbox/glibc-runner")
            copyAssetScript(
                context, "termux/scripts/glibc-runner",
                grMaster, executable = true
            )
            if (grBin.exists()) {
                val bak = File(prefix, "bin/glibc-runner.pkg.bak")
                if (!bak.exists()) runCatching { grBin.copyTo(bak, overwrite = false) }
            }
            copyAssetScript(
                context, "termux/scripts/glibc-runner",
                grBin, executable = true
            )
        }
        installRescueLibs(context)

        // (4) 存量安装的 apt 源修复（全新安装时 bootstrap 已内置好源，此处无操作）
        fixAptSources(context)

        // (5) 清 apt 下载缓存（rev 7）：debfix 静默失败时代盖过章的官方
        // deb 若留在缓存中，apt 会原样硬链接复用（mtime 不变→记账命中→
        // 跳过重写）。清掉强制重新下载，与 debfix v4 记账免疫双保险。
        try {
            val archives = File(context.cacheDir, "apt/archives")
            archives.listFiles { f -> f.isFile && f.name.endsWith(".deb") }?.forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "清理 apt 缓存失败: ${e.message}")
        }

        // (6) v2.22.2 内置 X11 桌面客户端（终端侧命令 + 宿主定位文件）
        installX11Client(context)
    }

    /**
     * 部署内置 X11 桌面的终端侧组件（rev 16，全新安装与存量迁移共用）：
     * - bin/termux-x11：X server 客户端（app_process 拉起宿主 APK 内的
     *   com.termux.x11.CmdEntryPoint，即 lorie 合成器 + Xwayland）；
     * - bin/linbox-x11：一键启动（服务 + 自动尝试 xfce4 等桌面会话）；
     * - bin/linbox-x11-stop：停止服务（nice-name 精确匹配，不影响
     *   官方 Termux:X11 应用的进程）；
     * - etc/linbox-x11.env：宿主 APK 路径与 nativeLibraryDir（APK 内的
     *   libXlorie.so 在 useLegacyPackaging=true 下被压缩，CmdEntryPoint
     *   需从解压目录加载）。APK 路径随每次升级变化，[refreshX11Env]
     *   在应用启动时自愈。
     */
    private fun installX11Client(context: Context) {
        val prefix = TermuxEnvironment.prefixDir(context)
        copyAssetScript(
            context, "termux/scripts/termux-x11",
            File(prefix, "bin/termux-x11"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/linbox-x11",
            File(prefix, "bin/linbox-x11"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/linbox-x11-stop",
            File(prefix, "bin/linbox-x11-stop"), executable = true
        )
        // 主副本（rev13 防覆盖自愈源）：`pkg install termux-x11-nightly`
        // 会用官方客户端覆盖 bin/termux-x11（重写后引用的类名不存在，
        // 启动即崩）。debfix v6.3 替换 deb 内客户端、linbox-x11 启动
        // 前自检恢复，都从这里取审定版本。
        val masterDir = File(prefix, "etc/linbox/x11")
        masterDir.mkdirs()
        copyAssetScript(
            context, "termux/scripts/termux-x11",
            File(masterDir, "termux-x11"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/linbox-x11",
            File(masterDir, "linbox-x11"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/linbox-x11-stop",
            File(masterDir, "linbox-x11-stop"), executable = true
        )
        // XKB 键盘数据（X server 初始化必需，缺失时 native start() 直接
        // 退出——"$XKB_CONFIG_ROOT is not set"）：客户端首次启动自动解压
        // 为 xkb/ 并导出 XKB_CONFIG_ROOT，使 X 服务零 pkg 依赖。
        // fix9.10 三级容错部署（旧版为单点资产拷贝——而独立 *.tar.gz
        // 资产在用户 CI 仓库管线中会整文件丢失，fresh 安装 9.7~9.9 全部
        // 死于"Bootstrap 安装失败 / termux/x11-xkb.tar.gz"，截图实锤；
        // 任何来源缺失都不得阻断 bootstrap 安装，终端主体必须可用）。
        installXkbData(context, masterDir)
        refreshX11Env(context)
    }

    /**
     * XKB 键盘数据三级容错部署（fix9.10）。旧版单点依赖独立资产
     * termux/x11-xkb.tar.gz，该资产在用户 CI 仓库管线中曾整文件丢失，
     * 令 fresh 安装在收尾一步全盘报废（FileNotFoundException 即裸资产
     * 名）。三级来源按序兜底，全部缺失仅告警（X11 会话由 linbox-x11
     * 给 pkg install xkeyboard-config 补救指引，终端主体不受影响）：
     *  ① $PREFIX/xkb/x11-xkb.tar.gz —— bootstrap 归档内置成员
     *    （fix9.10 起新增，*.zip 载体从未在管线中丢失；fresh 安装时
     *    本次解包的天然副产品）；
     *  ② assets/termux/x11-xkb.tar.gz —— 旧独立资产（保留随包兼容）；
     *  ③ 既有 etc/linbox/x11/xkb.tar.gz —— 存量安装直接保留（幂等）。
     */
    private fun installXkbData(context: Context, masterDir: File) {
        val dest = File(masterDir, "xkb.tar.gz")
        // ③ 存量副本有效 → 直接保留（迁移路径零拷贝快速通道）
        if (dest.isFile && dest.length() > 1024) return
        masterDir.mkdirs()
        // ① bootstrap 归档内置（fix9.10 主来源）
        val inBootstrap = File(TermuxEnvironment.prefixDir(context), "xkb/x11-xkb.tar.gz")
        if (inBootstrap.isFile && inBootstrap.length() > 1024) {
            inBootstrap.copyTo(dest, overwrite = true)
            return
        }
        // ② 旧独立资产（兼容旧包/旧管线仍可用的场景）
        try {
            copyAssetScript(context, "termux/x11-xkb.tar.gz", dest, executable = false)
        } catch (e: Exception) {
            // 绝不阻断 bootstrap 安装：终端可用性 > X11 桌面
            android.util.Log.w(
                TAG, "XKB 数据三级来源均缺失（终端不受影响，X11 会话启动时" +
                        "将由 linbox-x11 给出 pkg install xkeyboard-config 指引）: ${e.message}"
            )
        }
    }

    /**
     * rev19（fix9.9）：部署离线救援库 —— 从 bootstrap 归档流式提取
     * liblz4 / libexpat / libgpg-error 真身到 etc/linbox/rescue/，
     * 供 bin/linbox-pkgfix 在真身整文件丢失（dpkg 事故遗留）时兑底
     * 复制。幂等：三文件齐全则直接返回；失败仅告警（非致命——多数
     * 场景真身都在，只需 soname 链接修复）。
     * fix9.10 加固：条目名归一化匹配（容忍历史/未来归档的 "./" 与
     * "usr/" 前缀变体），逐文件日志使 "0/3 静默零提取" 类异常在
     * logcat 可见，不再无感空转。
     */
    private fun installRescueLibs(context: Context) {
        val rescueDir = File(
            File(TermuxEnvironment.prefixDir(context), "etc/linbox"),
            "rescue"
        )
        val wanted = listOf(
            "lib/liblz4.so.1.9.3",
            "lib/libexpat.so",
            "lib/libgpg-error.so"
        )
        if (wanted.all { File(rescueDir, it.substringAfterLast('/')).isFile }) return
        rescueDir.mkdirs()
        try {
            val assetName = "${TermuxEnvironment.BOOTSTRAP_ASSET_DIR}/bootstrap-aarch64.zip"
            context.assets.open(assetName).use { input ->
                java.util.zip.ZipInputStream(input).use { zin ->
                    while (true) {
                        val entry: ZipEntry = zin.nextEntry ?: break
                        val norm = entry.name.removePrefix("./").removePrefix("usr/")
                        if (norm in wanted) {
                            val out = File(rescueDir, norm.substringAfterLast('/'))
                            out.outputStream().use { zin.copyTo(it, 64 * 1024) }
                            Os.chmod(out.absolutePath, PERMISSION_0644)
                            android.util.Log.i(
                                TAG,
                                "rescue lib extracted: ${out.name} (${out.length()}B)"
                            )
                        }
                        zin.closeEntry()
                    }
                }
            }
            val done = wanted.count { File(rescueDir, it.substringAfterLast('/')).isFile }
            android.util.Log.i(TAG, "rescue libs deployed: $done/${wanted.size} -> ${rescueDir.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "救援库部署失败（非致命）: ${e.message}")
        }
    }

    /**
     * fix9.10：救援库就位保障 —— 公开供 LinBoxApp 每次启动调用。
     * 与迁移路径（installPackageToolchain → installRescueLibs）互为
     * 备份：迁移异常中断（如 9.7~9.9 时代 xkb 资产缺失引发的安装
     * 失败）或尚未触发时，打开主界面一次仍可部署救援库。幂等：
     * 三真身齐全即时返回；未装 bootstrap 静默跳过。
     */
    fun ensureRescueLibs(context: Context) {
        if (!isInstalled(context)) return
        installRescueLibs(context)
    }

    /**
     * 刷新 etc/linbox-x11.env（宿主 APK 路径 / native 库目录）。
     * 公开给 LinBoxApp 在每次应用启动时调用（APK 升级后路径变化自愈），
     * bootstrap 未安装时静默跳过。
     */
    fun refreshX11Env(context: Context) {
        if (!isInstalled(context)) return
        try {
            val envFile = File(TermuxEnvironment.prefixDir(context), "etc/linbox-x11.env")
            envFile.parentFile?.mkdirs()
            val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""
            envFile.writeText(
                "# 由 LinBox 自动生成（内置 X11 客户端定位宿主组件，勿手工编辑）\n" +
                "LINBOX_APK_PATH=${context.packageCodePath}\n" +
                "LINBOX_NATIVE_DIR=$nativeDir\n"
            )
            Os.chmod(envFile.absolutePath, PERMISSION_0600)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "X11 客户端环境文件写入失败: ${e.message}")
        }
    }

    /**
     * apt 钉扎（fix7）：写入 etc/apt/apt.conf.d/99linbox，把 apt/pkg
     * 的 dpkg 子进程固定到 libexec/linbox/dpkg 包装器。
     *
     * 背景：dpkg 包自升级会用 deb 内的真身 ELF 覆盖 bin/dpkg（包装器
     * 旧部署点），此后 apt 直接调用裸 dpkg，官方 deb 的
     * data/data/com.termux tar 成员按绝对路径落盘，全部安装报
     * "unable to stat './data/data/com.termux': Permission denied"。
     * apt.conf.d/99linbox 不属于任何软件包，apt/dpkg 升级都不会覆盖；
     * apt 读取 apt.conf.d 时后读的文件优先生效，99_ 前缀保证排序最后。
     */
    private fun writeAptDpkgPin(context: Context) {
        val confDir = File(TermuxEnvironment.etcDir(context), "apt/apt.conf.d")
        confDir.mkdirs()
        val wrapper = File(TermuxEnvironment.prefixDir(context), "libexec/linbox/dpkg")
        val content = buildString {
            appendLine("// LinBox: apt/pkg 固定经由 dpkg 包装器（官方 deb 前缀重打包）。")
            appendLine("// dpkg 包自升级会用新真身覆盖 bin/dpkg（包装器副本），")
            appendLine("// 本文件保证 apt 永远走 libexec/linbox/dpkg，不受影响。")
            appendLine("Dir::Bin::dpkg \"${wrapper.absolutePath}\";")
        }
        try {
            File(confDir, "99linbox").writeText(content)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "写入 apt.conf.d/99linbox 失败: ${e.message}")
        }
    }

    /**
     * dpkg 兜底配置（fix8）：写入 etc/dpkg/dpkg.cfg.d/99-linbox-fix 与
     * etc/apt/apt.conf.d/99-linbox-fix（path-exclude + force-confold）。
     *
     * 背景：官方 deb 中偶有未经 linbox-debfix 重写的
     * /data/data/com.termux 成员（典型如裸目录条目
     * ./data/data/com.termux，无尾斜杠），dpkg 以 instdir=/ 按成员路径
     * lstat 时对官方包名前缀无权访问，报 "unable to stat ... Permission
     * denied"。path-exclude 让 dpkg 直接跳过这些成员（它们本就不该落
     * 入本应用沙箱，跳过无副作用）；force-confold 自动保留本地配置
     * 文件，消除 bash.bashrc / profile 升级时的 Y/I/N/O/D/Z 交互。
     *
     * 双保险：dpkg.cfg.d 由 dpkg 真身直接读取；apt.conf.d 令 apt 在
     * 命令行补同样参数，即使 dpkg.cfg.d 意外丢失也兜得住。
     *
     * ⚠ 两份配置含 com.termux 字面量，【不能】打进 bootstrap zip——
     * 解压时 rewriteLegacyPaths 会把 com.termux 改写为 com.linbox 使
     * 配置失效，只能在运行时写入。同样内容的三路写入互为兜底：
     * 本函数（全新安装 + 存量迁移 rev 5）、profile.d/linbox.sh
     * （每次会话启动）、linbox-dpkg 包装器（每次 dpkg 调用，缺失才写）。
     */
    private fun writeDpkgPathExclude(context: Context) {
        val dpkgCfg = File(TermuxEnvironment.etcDir(context), "dpkg/dpkg.cfg.d/99-linbox-fix")
        val aptCfg = File(TermuxEnvironment.etcDir(context), "apt/apt.conf.d/99-linbox-fix")
        try {
            dpkgCfg.parentFile?.mkdirs()
            dpkgCfg.writeText(buildString {
                appendLine("# LinBox (com.linbox) dpkg 兜底修复（fix8.5）")
                appendLine("# 跳过官方 deb 中漏经重写的 /data/data/com.termux 成员")
                appendLine("# （如裸目录条目），否则 dpkg 落盘时报 Permission denied。")
                appendLine("path-exclude=/data/data/com.termux")
                appendLine("path-exclude=/data/data/com.termux/*")
                appendLine("force-confold")
            })
            aptCfg.parentFile?.mkdirs()
            aptCfg.writeText(buildString {
                appendLine("// LinBox (com.linbox) dpkg 兜底修复（fix8.5）——双保险")
                appendLine("// 即使 etc/dpkg/dpkg.cfg.d/99-linbox-fix 丢失，apt 调起的")
                appendLine("// dpkg 也带同样的 path-exclude / force-confold。")
                appendLine("DPkg::Options:: \"--path-exclude=/data/data/com.termux\";")
                appendLine("DPkg::Options:: \"--path-exclude=/data/data/com.termux/*\";")
                appendLine("DPkg::Options:: \"--force-confold\";")
                // fix8.5：apt 级独立第二路径——dpkg 前把全部 deb 交给 debfix 重写
                appendLine("DPkg::Pre-Install-Pkgs:: \"/data/data/com.linbox/files/usr/bin/linbox-debfix\";")
            })
        } catch (e: Exception) {
            android.util.Log.w(TAG, "写入 dpkg path-exclude 配置失败: ${e.message}")
        }
    }

    /**
     * 首选镜像仓库根（中科院 ISCAS）。
     *
     * 选它有三个原因：pool 级下载稳定；对国内网络速度快；
     * 域名以 .cn 结尾，老版 termux-tools 的 pkg select_mirror 见到
     * .cn 源会直接跳过轮换，避免再次被加权随机切到坏镜像。
     *
     * fix9.12：原首选 TUNA（连同 BFSU）自 2026-09 起 dists/InRelease
     * 对 apt 请求一律 403 Forbidden（用户多台设备实测），而 ISCAS
     * 经 Release 头 + 真实 .deb 分段下载双验证通过，故首选根切换
     * 到 ISCAS，TUNA/BFSU 加入坏源重写清单（与 bin/linbox-mirror
     * 的 rewrite_file 保持同一模式集合）。
     */
    private const val PREFERRED_MIRROR_ROOT =
        "https://mirror.iscas.ac.cn/termux/apt"

    /**
     * 存量安装的 apt 源修复（纯文本替换、不联网）。
     *
     * 背景：老版 termux-tools 的 pkg select_mirror 只测 dists/Release
     * 就把源加权轮换到 packages-cf.termux.org（Cloudflare），该镜像
     * dists 可读、pool 目录的 .deb 却一律 403 Forbidden——apt update 正常、
     * 所有包下载全部失败，pkg update 与 linbox-glibc 因此报错。
     *
     * 这里把 sources.list 中已知的坏源/老源/轮换源统一重写到
     * [PREFERRED_MIRROR_ROOT]（.cn 域名同时让轮换永久跳过本源）。
     * fix9.12 起清单含 TUNA/BFSU（2026-09 起 dists 403，存量安装
     * 升级新 APK 时由增量迁移自动改写到 ISCAS）。
     * pool 级验证与 sources.list.d 附加源（gpkg）同步由
     * bin/linbox-mirror 负责（linbox-glibc 安装前自动调用）。
     */
    private fun fixAptSources(context: Context) {
        val list = File(TermuxEnvironment.prefixDir(context), "etc/apt/sources.list")
        if (!list.isFile) return
        val old = try {
            list.readText()
        } catch (_: Exception) {
            return
        }
        val root = PREFERRED_MIRROR_ROOT
        val mainSuffix = "$root/termux-main"
        val new = old
            .replace(Regex("https?://mirrors\\.tuna\\.tsinghua\\.edu\\.cn/termux/apt"), root)
            .replace(Regex("https?://mirrors\\.bfsu\\.edu\\.cn/termux/apt"), root)
            .replace(Regex("https?://packages-cf\\.termux\\.org/apt"), root)
            .replace(Regex("https?://packages\\.termux\\.org/apt"), root)
            .replace(Regex("https?://packages\\.termux\\.dev/apt"), root)
            .replace(Regex("https?://deb\\.kcubeterm\\.me/termux-main"), mainSuffix)
            .replace(Regex("https?://termux\\.mentality\\.rip/termux-main"), mainSuffix)
            .replace(Regex("https?://termux\\.librehat\\.com/apt/termux-main"), mainSuffix)
            .replace(Regex("https?://grimler\\.se/termux-packages-24"), mainSuffix)
        if (new != old) {
            try {
                list.writeText(new)
                android.util.Log.i(TAG, "apt 源已修复到首选镜像（原为坏镜像/轮换源）")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "apt 源修复写入失败: ${e.message}")
            }
        }
    }

    /** bin/dpkg 是否已是本安装器写入的包装器（防重复移动真身）。
     *  读取头部 512 字节匹配包装器标记（旧版仅 128 字节，会因脚本
     *  头注释较长而漏判，导致把包装器误当真身移走）。 */
    private fun isOurWrapper(f: File): Boolean {
        val head = f.inputStream().use { input ->
            val buf = ByteArray(512)
            val n = input.read(buf)
            if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
        }
        return head.contains("linbox-dpkg") || head.contains("dpkg.real")
    }

    /**
     * 存量安装的增量迁移（免清数据）：bootstrap 已装但增强组件修订号
     * 落后时，重写 linbox.sh / motd（修复旧版本生成文件的语法问题）、
     * 部署 dpkg 包装器与重写工具，并对既有前缀做一次全量重写——清理
     * libapt-pkg.so 里 /data/data/com.termux/cache 等旧版仅重写 files/
     * 前缀时遗漏的路径（正是 "E: Archives directory ... Permission
     * denied" 报错的根因）。幂等：完成后写入修订号。
     */
    fun migrateIfNeeded(context: Context) {
        if (!isInstalled(context)) return
        if (isRevisionCurrent(context)) {
            _extrasReady.value = true
            return
        }
        synchronized(installLock) {
            if (isRevisionCurrent(context)) {
                _extrasReady.value = true
                return
            }
            try {
                // rev13 顺序修正：先跑全量重写，再部署审定脚本。旧顺序
                // （先部署后重写）下，若用户装过官方 termux-x11-nightly，
                // bin/termux-x11 属包清单成员，--full 的内容改写会把刚
                // 部署的内置客户端里 com.termux.x11.CmdEntryPoint 类名
                // 改成不存在的 com.linbox.x11.*——X11 启动必崩（用户实测）。
                // 先重写存量树（历史残留/被覆盖脚本一并清理），随后部署的
                // 脚本不再被任何重写波及（部署内容本就按 com.linbox 编写，
                // 其中 com.termux.x11.CmdEntryPoint 是宿主 APK 真实类名，
                // 属合法保留字串）。
                runReprefix(context, listOf("--full"))
                installPackageToolchain(context)
                installLinBoxExtras(context)
                revisionFile(context).writeText("$EXTRAS_REVISION\n")
                android.util.Log.i(TAG, "Termux extras migrated to revision $EXTRAS_REVISION")
            } catch (e: Exception) {
                // 迁移失败不永久阻塞终端（降级为旧行为，bootstrap 本体完好）
                android.util.Log.e(TAG, "extras migration failed", e)
            } finally {
                _extrasReady.value = true
            }
        }
    }

    private fun isRevisionCurrent(context: Context): Boolean {
        val rev = revisionFile(context)
        return rev.isFile && rev.readText().trim() == EXTRAS_REVISION.toString()
    }

    private fun revisionFile(context: Context): File =
        File(TermuxEnvironment.prefixDir(context), "var/lib/linbox/install-revision")

    /** 执行原生重写工具（失败不抛出——重写是尽力而为的安全网）。 */
    private fun runReprefix(context: Context, args: List<String>) {
        try {
            val bin = File(TermuxEnvironment.binDir(context), "linbox-reprefix")
            if (!bin.isFile || !bin.canExecute()) return
            val process = ProcessBuilder(listOf(bin.absolutePath) + args).start()
            process.inputStream.use { it.readBytes() }
            process.errorStream.use { it.readBytes() }
            process.waitFor()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "linbox-reprefix 执行失败: ${e.message}")
        }
    }

    /** 从 assets 拷贝脚本并按需赋予执行权限（内容为发布时审定的定稿）。 */
    private fun copyAssetScript(
        context: Context,
        assetName: String,
        dest: File,
        executable: Boolean
    ) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (executable) Os.chmod(dest.absolutePath, PERMISSION_0700)
    }

    // ------------------------------------------------------------------
    // 工具函数
    // ------------------------------------------------------------------

    private fun copyAssetToFile(context: Context, arch: String, dest: File) {
        val assetName = "${TermuxEnvironment.BOOTSTRAP_ASSET_PREFIX}$arch.zip"
        context.assets.open(assetName).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
    }

    private fun verifyChecksum(bootstrapFile: File, arch: String) {
        val expected = when (arch) {
            "aarch64" -> TermuxEnvironment.BOOTSTRAP_AARCH64_SHA256
            else -> null // 其他架构如后续加入，填入官方 SHA-256
        } ?: return

        val digest = MessageDigest.getInstance("SHA-256")
        bootstrapFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw IllegalStateException(
                "bootstrap SHA-256 校验失败！\n期望: $expected\n实际: $actual\n" +
                        "归档可能损坏或被篡改，安装已中止。"
            )
        }
    }

    private fun deleteRecursive(file: File) {
        // 符号链接一律只删链接本身，绝不递归进入目标（lstat 语义）
        if (!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
            val children = file.listFiles() ?: return
            for (child in children) deleteRecursive(child)
        }
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    private const val TAG = "TermuxBootstrapInstaller"
}
