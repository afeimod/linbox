revision="${LINBOX_BOX64_REF:-v0.4.4}"
url="https://github.com/ptitSeb/box64.git"
urlType="git"
arch="aarch64"
buildSys="cmake"
license="MIT"

# ============================================================
# v3.3.34（用户："我是让你修复 box64-0.3.8 也能正常使用，不要让我
# 升级 0.4.4 —— 0.3.8 对 32 位兼容速度比较快"）：补丁体系重构，
# 让 v0.3.2..v0.4.4+ 全版本线都能正确吃到补丁。
# ------------------------------------------------------------
# 【事故回放（v3.3.16 的错误前提）】旧版把补丁基线钉在 v0.4.2：
#   revision < 0.4.2 → doNotApplyPatch=1 → 选 v0.3.8 构建出的产物
#   是【完全未打补丁】的 vanilla box64：
#     * DT_ANDROID_RELR 不识别 → bionic wine ntdll .data 自指针保持
#       裸地址 → wineboot 一进 mmap_add_reserved_area 即 SIGSEGV
#       （用户"box64-0.3.8 运行 wine 还是不行"的直接根因）；
#     * 无内建 SysV shm（bionic 宿主上 wine 的 X11/MIT-SHM 链路断）；
#     * 静态程序退出桥 bug（exit code 128 / stdout 不刷出）。
# 【本轮沙箱实测（v0.3.8 树逐 hunk 验证）】
#   * 0001 原样携带的 elfparser.c hunk 是 v0.4.2+ 的 12 空格缩进形态，
#     在 v0.3.8 的 16 空格深嵌套 switch 上必然 FAILED —— 整文件被
#     dry-run 门禁否决，其余 hunk（shm/static-exit/CMake）全陪葬；
#   * 更危险的是 0002 在默认 fuzz=2 下会对 v0.3.8 "成功"误落位：
#     实测 case 0x6fffe000 被插到 case DT_VERDEFNUM 之下 —— 语义全错。
# 【重构方案（三层）】
#   1) 0001 拆分：elfparser.c/elfparser32.c 的 RELR hunk 移出，归
#      0002（v0.4.2+ 12 空格）/0003（v0.3.2..v0.4.1 16 空格，本轮
#      新增、按 v0.3.8 原树锚定）/0004（elfparser32.c 全版本）——
#      0001 只剩与缩进无关的核心 hunk，全版本通吃；
#   2) custom_patch()（框架原生钩子）：本项目所有补丁一律
#      patch --fuzz=0 严格 dry-run 门禁，fuzzy 误落位从机制上杜绝
#      （0002/0003 按精确布局自选择：两个必中其一，0004 全中）；
#   3) 版本门废除：不再以 <0.4.2 否决整包 —— 每个补丁文件按自身
#      锚点独立成立/跳过（v0.3.0/v0.3.1 无 elfparser32.c 的老树，
#      0004 自然跳过；0006 只认 v0.3.0 的 Run(emu,0) 形态）。
# 其余防线不变：install() 的 _patch_in_tree 源码树探测与全套
# 冒烟断言（shm 符号 / .dynsym 导出 / DYNAREC 核心 / NOLOADADDR）。
# ============================================================
# 【历史教训链（v3.2.13→v3.2.21，保留备考）】冒烟断言曾三次误报：
#   v2.13 查 functions_list.txt（GO 型符号不进缓存）、v2.14 查符号表
#   （GO 包装符号从不进 .symtab）、v2.15 查注册字符串（方向对但当时
#   shm 链路真断）；v3.2.21 八符号 ANW_EXPORT 默认可见性 + .dynsym
#   硬校验才闭环。详见 0001 头部说明。
# ============================================================

# v3.3.34：custom_patch —— --fuzz=0 严格按文件门禁应用本项目补丁。
# 框架 apply_patches() 检测到本函数即完全走此路径（不再用默认 fuzz=2
# 的整包 dry-run）。逐文件独立成立/跳过，语义与框架一致：
#   patchesApplied / patchesSkipped 全局变量照常登记（install() 的
#   条件化冒烟依赖它们），占位符经 expand_patch_placeholders 展开。
custom_patch() {
  local _patchFiles
  local _srcDir="${pkgSrcDir:-${srcDir}/${pjName}}"

  [[ -z $pjName ]] && { echo "错误: pjName 未定义！" >&2; return 1; }
  _patchFiles=($(ls "${wsDir}"/projects/"${pjName}"/*.patch 2>/dev/null | LC_ALL=C sort))
  [[ ! -f "${_patchFiles[0]}" ]] && _patchFiles=()
  [[ ${#_patchFiles[@]} -eq 0 ]] && { echo "box64: 无需应用任何补丁" >&2; return 0; }

  [[ -f "${wsDir}/patch-rules.conf" ]] || { echo "错误: 缺少补丁替换规则文件" >&2; return 1; }

  patchesApplied="${patchesApplied:-}"
  patchesSkipped="${patchesSkipped:-}"
  cd "${_srcDir}" || return 1

  local _patch _expanded _fuzzmode
  for _patch in "${_patchFiles[@]}"; do
    if ! _expanded="$(expand_patch_placeholders < "$_patch")"; then
      echo "错误: 补丁占位符展开失败=> $_patch" >&2
      cd - >/dev/null; return 1
    fi
    # 【fuzz 策略（v3.3.34 三次修正，两树实测）】
    #  * 除 0001 外全部 --fuzz=0 严格门禁：
    #    - RELR 布局孪生（0002/0003/0004）：默认 fuzz=2 会把 0002 的
    #      v0.4.2 形态 hunk "成功"误插到 v0.3.8 的 case DT_VERDEFNUM
    #      之下（沙箱实测语义级损坏）；严格匹配让 0002/0003 按精确缩进
    #      布局自选择（12 空格 vs 16 空格深嵌套），必中其一。
    #    - static-exit / clang-gate 孪生（0005/0006/0007）：【本轮发现
    #      现网事故】默认 fuzz 下 0005/0006 在 0001 之后上下文已变，
    #      会"成功"把整段注释+SetRDX 盲落到 core.c 文件末尾（函数体
    #      外的裸语句 = 编译错误）—— 用原始 HEAD 的 0001 在 v0.4.4
    #      复现成立，即现网 box64 构建自 0005/0006 引入起对所有版本
    #      都埋着这颗雷；fuzz=0 下它们按设计各认各的锚点、正确跳过。
    #  * 0001 例外保留默认 fuzz：其 CMake clang-gate hunk 与 core.c
    #    SetRDX hunk 在 v0.4.4 上依赖 fuzz 跨版本落位（上游把
    #    if(!box64_hasinterp) 插进上下文中间、CMake NOGIT 段行漂移；
    #    fuzz=0 实测误杀整文件）。
    case "$(basename "${_patch}")" in
      0001-*) _fuzzmode="" ;;
      *)      _fuzzmode="--fuzz=0" ;;
    esac
    if printf '%s\n' "${_expanded}" | patch -p1 ${_fuzzmode} --dry-run >/dev/null 2>&1; then
      echo "box64: 测试通过（fuzz=${_fuzzmode:-默认}）=> ${_patch}" >&2
      printf '%s\n' "${_expanded}" | patch --no-backup-if-mismatch ${_fuzzmode} -N -p1 \
        || { echo "错误: 补丁应用失败=> $_patch" >&2; cd - >/dev/null; return 1; }
      patchesApplied+=" $(basename "${_patch}")"
    else
      # 该补丁与本源码树形态不匹配（如 0002 遇 v0.3.8 / 0003 遇 v0.4.4
      # / 0006 遇非 v0.3.0 树）—— 按设计跳过，交由同位的孪生补丁接管。
      echo "box64: 补丁不适用于当前源码布局（按设计跳过）=> ${_patch}" >&2
      patchesSkipped+=" $(basename "${_patch}")"
    fi
  done

  cd - >/dev/null
  echo "box64: 补丁统计 —— 应用: [${patchesApplied}] / 跳过: [${patchesSkipped}]" >&2
  # v3.3.34 硬保障：核心补丁 0001（shm/static-exit）必须在链 —— 它与
  # 源码布局无关，任何版本线都应命中；缺失说明源码树异常漂移，宁可
  # 构建失败也不带病发布（v3.2.20 曾以警告带病发布，历史不再重演）。
  case " ${patchesApplied} " in
    *" 0001-linbox-android-shmem-native.patch "*)
      ;;
    *)
      echo "错误: 核心补丁 0001（内建 SysV shm + 静态退出修复）未生效 —— elfparser 拆分后它与布局无关，任何 v0.3.2+ 树都应命中；请核查源码树形态" >&2
      return 1
      ;;
  esac
  # RELR 保障：elfparser.c 的孪生补丁 0002/0003 必中其一（wineboot
  # SIGSEGV 根治线），elfparser32.c 由 0004 覆盖（v0.3.0/v0.3.1 无该
  # 文件的老树除外 —— 那些树没有 32 位加载器，跳过即正确）。
  case " ${patchesApplied} " in
    *" 0002-linbox-android-relr64.patch "*|*" 0003-linbox-android-relr-legacy.patch "*)
      ;;
    *)
      echo "错误: elfparser.c 的 Android RELR 补丁（0002/0003）一个都没命中 —— bionic wine 将因 DT_ANDROID_RELR 跳过而 SIGSEGV，请核查源码树形态" >&2
      return 1
      ;;
  esac
  return 0
}

# ============================================================
# LinBox bionic-box64（aarch64 / NDK bionic 链接）
# ------------------------------------------------------------
# 桌面 wine 容器的 x86_64 仿真核心（usr/local/bin/box64）。
# 与 rootfsbc 参考配方（project_legacy/box64）同链构建：NDK clang
# （api=28）+ android.toolchain.cmake（由框架 cmakeBaseArgs 注入），
# 产物为 bionic ELF（PT_INTERP=/system/bin/linker64），与 rootfs 内
# wine / mesa / pulseaudio 等全部二进制的运行形态完全一致。
#
# 【双形态生态】LinBox 同时支持两种 box64 发行形态，rootfs 两种都
# 能承载（本配方的 deps 保证配套运行层随 rootfs 一次到位）：
#   * bionic 形态（本配方，build-box64-bionic.yml 产出
#     linbox-box64-<ver>-aarch64-bionic.tar.gz）：NDK bionic 链接，
#     原生 exec，宿主侧零 glibc 依赖；
#   * glibc 形态（build-box64.yml 产出 linbox-box64-*-aarch64-
#     glibc.tar.gz，上游参考/上游项目 同款 -DWINLATOR_GLIBC +
#     patchelf）：PT_INTERP/DT_RPATH 钉到 rootfs 私有加载器
#     /data/data/com.linbox/files/rootfs/usr/lib/aarch64-linux-gnu/
#     ld-linux-aarch64.so.1 —— 该加载器与整套 aarch64 glibc 运行库
#     由 glibc-aarch64 配方安装进 rootfs（本包 deps 之一）。
#
# 【deps 说明】glibc-aarch64 / glibc-x86_64 都不是编译期依赖
# （bionic box64 链接 NDK 系统库），而是【运行时配套闭包】——
# 挂在 deps 上保证构建顺序与完整性：
#   * glibc-aarch64 → usr/lib/aarch64-linux-gnu/（私有加载器
#     ld-linux-aarch64.so.1 + libc.so.6 等）：glibc 形态 box64 与
#     glibc 链接的 aarch64 二进制在 rootfs 内的运行层；
#   * glibc-x86_64 → usr/lib/x86_64-linux-gnu/（ld-linux-x86-64.so.2
#     + libc.so.6 等）：被仿真的 x86_64 程序（含 x86_64 wine unix
#     侧）的运行库，box64 经 BOX64_LD_LIBRARY_PATH 查找。
# 因此 `--build box64`（独立构建工作流）与 `--build all`（rootfs
# 全量构建）都会先产出这两层再产出 box64 —— "box64 完整文件集"
# （仿真器本体 + 双 glibc 运行层 + 上游 ANDROID 配套库 + rc + 链接）
# 一次到位，不再出现"只有单个 box64 执行文件"的残缺产物。
#
# 【上游 ANDROID 模式】（CMakeLists.txt option(ANDROID)）：
#   - add_definitions(-DANDROID)；BAD_SIGNAL 自动 ON（上游对
#     Android 信号栈差异的规避 —— wine ntdll unix 侧依赖）
#   - wrappedandroidshmem.c：标准 wrappedlib 壳（对 libandroid-shmem.so
#     的纯 GO 型运行时 dlsym 映射，仅服务显式 dlopen 该库的程序）
#     —— rootfs 无需 libandroid-shmem
#   - v3.2.18 补丁 0001-linbox-android-shmem-native.patch【补齐缺的东西】：
#     上游 v0.4.4/main 实证（本仓沙箱全量复现）：box64 对 x86_64 程序的
#     shm 链路在 Android 上【两条都是断的】：
#       a) 动态程序：wrappedlibc 的 GO(shmget 等) 桥接 dlsym 宿主 libc ——
#          glibc 宿主能解析（x86_64 CI 冒烟通过的原因），bionic 宿主
#          根本没有 shm 符号 → "function not found"（用户 CI 日志
#          "符号表缺 shmget" 指的就是这个缺口）；
#       b) 静态程序：x64Syscall_linux 的 switch 无 case 29/30/31/67
#          → ENOSYS（沙箱实测 vanilla v0.4.4 跑静态 shm 冒烟：
#          "shmget: Function not implemented"）；
#       c) Android 内核普遍无 CONFIG_SYSVIPC，直接转发内核也不通。
#     补丁三处联动：src/libtools/android_shmem.c（memfd_create 后端的
#     内建 SysV shm 实现，含 /dev/ashmem 兼容回退；导出 shmget/shmat/
#     shmctl/shmdt + my_* 别名）+ wrappedlibc_private.h（shm 四符号在
#     ANDROID 下 GO→GOM，桥接到 my_* 内建实现）+ x64syscall.c（主
#     switch 补 case 29/30/31/67，ANDROID 下转发内建实现、glibc 宿主
#     直用 libc —— 静态程序也修好）。glibc 形态构建不受影响（文件在
#     非 ANDROID 下编译为空、private 头走 GO 原分支）。
#   - v3.2.20 补丁新增（wineboot SIGSEGV 根因修复，全形态生效）：
#     NDK r29 lld 默认给 bionic 链接的 .so 发 Android 标签 RELR 打包
#     重定位（DT_ANDROID_RELR=0x6fffe000/RELRSZ/RELRENT，编码同标准
#     RELR 仅标签号不同），box64 v0.4.4 的 elfparser 只认标准 DT_RELR
#     系列 → 打包的 R_X86_64_RELATIVE 全部静默跳过 → bionic wine
#     x86_64-unix/ntdll.so 的 .data 自指针（virtual.c 的
#     reserved_areas = LIST_INIT(reserved_areas)）保持链期裸地址，
#     wineboot 一进 mmap_add_reserved_area 即 SIGSEGV（设备日志
#     ntdll.so+0x770a8 读 0x928e8，R15=0x928d8 裸值 vs RAX=
#     0x3f000928d8 正确重定位值 —— 用 bionic-wine-official-9.2-x86_64
#     发布包的 ntdll.so 逐字节核实：.rela.dyn 仅 7 项，其余全在
#     .relr.dyn/SHT_ANDROID_RELR 的 14 个打包项里）。补丁给
#     elfparser.c/elfparser32.c 的 DT_RELR/RELRSZ/RELRENT case 加
#     0x6fffe000/0x6fffe001/0x6fffe003 别名；沙箱实测旧 box64 加载
#     该 ntdll.so 无 "Applying N Relocation(s) without Addend" 行
#     （重定位被跳过），打补丁后 14 项全应用。宿主冒烟新增
#     [ANDROID RELR] 回归（gcc 标准标签 + python 翻 Android 标签，
#     经 box64 实跑断言 —— 见 build-box64-bionic.yml）。
#   - 链接 bionic 系统库 c / m / dl，无外部运行时依赖
#   - Release 优化限制为 -O1（上游对 Android dynarec 稳定性的
#     选择，CMakeLists "limit the optimization level" hack，
#     会覆盖框架 cflags 的 -O3，遵上游）
#   - ARM64 / ARM_DYNAREC 由 toolchain 的 CMAKE_SYSTEM_PROCESSOR=
#     aarch64 自动使能，跑 x86_64 wine 的动态二进制翻译
#   - 上游安装规则（install 段）在 ANDROID 下会把
#     x64android/libc++_shared.so 装进 lib/box64-x86_64-linux-gnu/、
#     system/box64.box64rc 装进 etc/ —— 本配方自定义 install()
#     按同路径落位（$prefix 为 rootfs 绝对前缀，不能用上游的 / 与
#     /usr 默认 DESTINATION）
#
# 【ASM 编译器钉死】load_env 导出 ASM=NDK yasm（仅支持 x86 的
# 汇编器），CMake 的 ASM 探测会命中它 —— arm64_prolog.S 直接
# "yasm: FATAL: unrecognized object format unwind-tables"（v3.2.4
# CI 实报根因；旧配方的 -DCMAKE_ASM_FLAGS= 空参数治不了编译器
# 选择问题）。显式把 ASM 编译器钉到 NDK clang（与 C 同一工具链，
# 集成汇编器原生支持 .S）。
#
# 【产物完整集】缺一不可（install() 逐项硬校验）：
#   local/bin/box64                               仿真器本体
#   bin/box64 → ../local/bin/box64                PATH 入口链接
#     （rootfs 的 profile 只把 $prefix/bin 放进 PATH，usr-merge 后
#     即 rootfs/usr/bin —— 终端直接敲 box64 / linbox-wine 的
#     find_box64 第二探测位均命中该链接）
#   lib/box64-x86_64-linux-gnu/libc++_shared.so  x86_64 C++ 标准库
#     （上游 x64android/ 预制产物：被仿真的 x86_64 程序 dlopen
#     libc++_shared.so 时经 BOX64_LD_LIBRARY_PATH 从该目录解析；
#     wine 的 glibc 系 x86_64 运行库另由 glibc-x86_64 配方安装在
#     lib/x86_64-linux-gnu，两者职责不同、互不替代）
#   etc/box64.box64rc                             系统级调优开关
# ============================================================

# 与 rootfsbc 参考配方（project_legacy/box64）同参数集：
# ANDROID + BAD_SIGNAL + ARM_DYNAREC；BAD_SIGNAL 在 ANDROID=ON 时
# 上游本就自动置 ON（CMakeLists if(ANDROID) set(BAD_SIGNAL ON)），
# 显式写出以对齐参考语义。NO_CONF_INSTALL / NO_LIB_INSTALL 不
# 保留 —— 两者正是产物只剩单个 box64 文件的原因（配套的
# x64android/libc++_shared.so 与 system/box64.box64rc 被 install
# 规则跳过），由自定义 install() 补齐完整集。
#
# 【v3.2.13 · NOLOADADDR=ON】（用户实测"bionic box64 启动时没有任何日志、
# 无法启动"的排查修复之一，与 上游参考 / 上游项目 两条已验证的 Android
# bionic 部署链对齐）：
#   上游默认（NOLOADADDR=OFF）经 lld `--image-base=0x34800000` 把 box64
#   钉在低地址 —— 这是桌面 glibc ld.so 的手法。Android 的 linker64 对 PIE
#   不遵守 image-base（ASLR 随机化加载基址），该链接参数在设备上零收益；
#   且 0x34800000 低地址段在 Android 应用进程布局里与 zygote/heap 常驻区
#   相邻，不同 ROM 的 mmap_min_addr / 布局差异下存在加载即死的风险面。
#   上游参考（WINLATOR_GLIBC 链）与 上游项目（NDK bionic 链）的 box64
#   构建【均显式 -DNOLOADADDR=ON】，本配方对齐 —— box64 运行时按
#   /proc/self/maps 自适应任意基址（Termux 同款形态）。
# v3.3.35 勘误（box64-build-log-aarch64(5).zip 实证）：v3.3.34 的
# -DBOX32=ON 是误诊。arm64ec hangover-wine 架构下 32 位 Windows PE
# 由 wine 进程内部 wow64 处理（libwow64fex.dll FEX 转译 x86→arm64），
# unix 侧 wine 是纯 64 位 arm64 ELF —— box32（Linux i386 ELF 加载器）
# 不在任何 Windows 游戏的执行路径上。BOX32=ON 唯一的实际效果是引入
# src/libtools/myalign32.c 的 #include <obstack.h>：obstack 是 glibc
# 专有扩展（bionic/NDK 从未提供，box64 树内 obstack.c 同样是消费者
# 而非实现），aarch64 构建在 51% 处 fatal error: 'obstack.h' file not
# found（日志 1849 行）—— 0.3.8/0.4.4 两版本同样中招，与补丁线无关。
# 用户"0.3.8 对 32 兼容速度快"的正解在 wine 侧（HODLL=libwow64fex.dll
# + libarm64fex.dll 注入，见 WineEnvironment.kt）而非 box32。上游 option
# 原文即 "experimental, do not use"，保持 OFF。
# v3.3.36（box64-build-log-aarch64(6).zip 实证）：交付构建本身已全绿
# （0.3.8 100% Built target box64 + install() 冒烟 1/2、2/2 通过），
# "还是构建报错"的真凶在 CI 工作流 build-box64-bionic.yml 步骤 5.7
# 源码级冒烟：它只给冒烟树打 0001，却用"0001 命中"门控 RELR 断言 ——
# v3.3.34 拆分后 elfparser 修复在 0002/0003，冒烟树必然缺失 →
# relr_ok() 解引用未重定位裸指针 SIGSEGV（本仓沙箱 v0.3.8 树完整复现
# Signal 11）；尾部"补丁在源"再 grep 0001 里的 6fffe000（拆分后必不
# 在）双保险全炸 —— 对全部 ref 回归，0.3.8 只是首个踩中的。修复在
# 两个工作流（bionic + glibc 形态）：冒烟/克隆步改为与 custom_patch()
# 同款逐补丁门禁（0001 默认 fuzz / 其余 --fuzz=0），断言按落地补丁组
# 分别激活（0001→静态 shm；0002/0003→RELR）。glibc 工作流另修 CWD
# bug（cd box64 后 patch -d box64 解析为 box64/box64，v3.2.19 起补丁
# 从未真正应用）。本配方 custom_patch() 无需改动。
args="-DANDROID=ON -DBAD_SIGNAL=ON -DARM_DYNAREC=ON -DNOLOADADDR=ON"

# 运行时配套闭包（见头部【deps 说明】：非编译期依赖，是保证
# "完整文件集"一次到位的构建顺序声明）
deps="glibc-aarch64 glibc-x86_64"

# 用 make 而非 ninja：与 rootfs 其余 cmake 包同一验证路径
doNotUseNinja=1

pre_setup() {
  # ---- wrapper 生成确定性（注释勘误 + 操作保留） ----
  # functions_list.txt 只是 rebuild_wrappers.py 的变更缓存对比文件；
  # 删除它强制 add_custom_command 必跑，wrapper 按本机 python 确定性
  # 再生成（无 mtime 竞态）。注意：本框架 cmake 不传 -DCI=ON，生成
  # 规则（if(NOT CI)）存在，删除后可重建；CI=ON 的独立冒烟构建
  # 使用提交版生成物，不受此操作影响。
  if [[ -f "${srcDir}/${pjName}/src/wrapped/generated/functions_list.txt" ]]; then
    echo "box64: 删除预生成的 wrapper 清单，强制确定性再生成"
    rm -f "${srcDir}/${pjName}/src/wrapped/generated/functions_list.txt"
  fi

  # 钉死 ASM 编译器为 NDK clang（v3.2.4 yasm 事故根治）。
  # 双保险：-D 进 CMake cache（探测最高优先级）+ 覆盖 env ASM
  # （load_env 导出的 ASM 是 NDK yasm，仅支持 x86，是探测的
  # 次优先来源；empty.sh 会在包间 unset，无跨包泄漏）。
  # NDK toolchain file 若自行设置 ASM 也只会设成同款 clang。
  local _clang="${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/bin/${targetArch}-linux-android${api}-clang"
  if [[ ! -x "${_clang}" ]]; then
    # 兜底：NDK 尚未解压就位时用 PATH 名（构建系统在 configure 前
    # 保证 NDK 就绪；aarch64 本地构建场景同样适用）
    _clang="${targetArch}-linux-android${api}-clang"
  fi
  args+=" -DCMAKE_ASM_COMPILER=${_clang}"
  export ASM="${_clang}"
}

install() {
  local _src="${srcDir}/${pjName}"
  local _bin="${destDir}${prefix}/local/bin/box64"
  local _strip="${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

  # ---- 1. box64 本体 ----
  if [[ ! -f "${_src}/build/box64" ]]; then
    echo "错误: 未找到构建产物 ${_src}/build/box64"
    return 1
  fi
  mkdir -p "${destDir}${prefix}/local/bin/"
  cp -p "${_src}/build/box64" "${_bin}"

  # ---- 2. x86_64 C++ 标准库（上游 ANDROID 模式配套，勿漏） ----
  if [[ ! -f "${_src}/x64android/libc++_shared.so" ]]; then
    echo "错误: 未找到上游 x64android/libc++_shared.so（${_src}）"
    return 1
  fi
  mkdir -p "${destDir}${prefix}/lib/box64-x86_64-linux-gnu/"
  cp -p "${_src}/x64android/libc++_shared.so" \
    "${destDir}${prefix}/lib/box64-x86_64-linux-gnu/"

  # ---- 3. 系统级 box64 rc ----
  mkdir -p "${destDir}${prefix}/etc/"
  cp -p "${_src}/system/box64.box64rc" "${destDir}${prefix}/etc/"

  # ---- 4. PATH 入口链接：usr/bin/box64 → ../local/bin/box64 ----
  # 相对链接在 $prefix（rootfs 绝对前缀）与 tar 解包后均成立；
  # linbox-container 的 find_box64 第二探测位即 $RFS_USR/bin/box64
  mkdir -p "${destDir}${prefix}/bin/"
  ln -sfn ../local/bin/box64 "${destDir}${prefix}/bin/box64"

  # ---- 5. strip（发布体积；llvm-strip 是 NDK 自带） ----
  if [[ -x "${_strip}" ]]; then
    "${_strip}" "${_bin}" || true
  fi

  # ---- 6. 硬校验：aarch64 ELF + bionic PT_INTERP，任何一项不对直接判失败 ----
  local _machine _interp
  _machine="$(readelf -h "${_bin}" 2>/dev/null | awk '/^ *Machine:/{print $2; exit}')"
  if [[ "${_machine}" != "AArch64" ]]; then
    echo "错误: box64 架构校验失败（期望 AArch64，实际 ${_machine:-未知}）"
    return 1
  fi
  _interp="$(readelf -l "${_bin}" 2>/dev/null \
    | sed -n 's/.*Requesting program interpreter: \(.*\)\]/\1/p' | head -n 1)"
  if [[ "${_interp}" != "/system/bin/linker64" ]]; then
    echo "错误: 本配方产物应为 bionic 形态（NDK 链接），PT_INTERP 校验失败"
    echo "  期望: /system/bin/linker64"
    echo "  实际: ${_interp:-（无 PT_INTERP）}"
    echo "  注: glibc 形态（PT_INTERP=ld-linux-aarch64.so.1）是 build-box64.yml"
    echo "       独立工作流的产物，不应出现在本配方链里 —— 查构建参数/工具链"
    return 1
  fi
  # 入口链接自检（相对目标存在）
  if [[ ! -e "${destDir}${prefix}/bin/box64" ]]; then
    echo "错误: ${prefix}/bin/box64 入口链接失效"
    return 1
  fi

  # ---- 6.5 v3.2.18 · 冒烟校验（内建 shm 实现 + DYNAREC + NOLOADADDR） ----
  # 【历史教训链，勿再绕回弯路】
  #   v3.2.13 校验 functions_list.txt 含 shmget → 误报（纯 GO 型符号
  #     不进该缓存文件）；
  #   v3.2.14 校验符号表含 shmget/shmat/shmctl → 误报（动态构建的
  #     GO 型包装符号从不进 box64 符号表，上游 v0.4.4 与 main 实证）；
  #   v3.2.15 改查 getDB/FreeDynablock + 编译单元 + 注册字符串 → 校验
  #     本身正确，但用户点破的“缺东西”是真命题：shm 链路在 bionic 上
  #     两条全断（见头部【v3.2.18 补丁】段），符号表里没有 shm 符号
  #     只是症状；
  #   v3.2.18 用补丁把缺口真正补上：ANDROID 构建导出【真实的】
  #     shmget/shmat/shmctl/shmdt（memfd 后端实现，非包装壳），符号表
  #     直查由此【恢复为合法校验】——缺符号 = 补丁没打上/没编进，
  #     正是本轮要挡住的事故面。
  #   v3.2.21 补上真正缺的最后一环（用户 CI 日志“shm 符号未进动态导出表”
  #     警告的根因）：上游 CMakeLists 全局 -fvisibility=hidden ——
  #     HIDDEN 符号永不进 .dynsym（link.txt 里明明有 --export-dynamic：
  #     隐藏全局被降级为局部符号，nm 显示小写 t，符号表直查照样绿 =>
  #     v3.2.18~v3.2.20 的冒烟[1/2] 一直是假阳性）；且 my_* 别名无任何
  #     静态引用，被 NDK lld 默认 --gc-sections 整段回收。运行时桥接
  #     dlsym(dlopen(NULL), "my_shmget")（library.c getSymbolInSymbolMaps）
  #     双杀全断。修复：八符号强制默认可见性（visibility("default")，
  #     同上游 EXPORT 宏手法 my_XShmCreateImage），默认可见 +
  #     --export-dynamic => 进 .dynsym 且成 GC 根，一石二鸟。
  #     .dynsym 校验由此从【警告】升级为【硬错误】—— v3.2.20 曾以警告
  #     带病发布，今后缺导出直接失败。
  # 校验组（全部在构建树产物上查，strip 不影响）
  #   a) shm 四符号（v3.2.18 内建实现的真实导出）
  #   b) DYNAREC 核心真符号：getDB / FreeDynablock（dynablock.c 真函数
  #      的导出符号；v0.4.4 与最新 main 均稳定存在）
  #   c) wrappedandroidshmem.c 编译单元在链 + libandroid-shmem.so
  #      注册字符串（.rodata，strip 不受影响）
  #   d) NOLOADADDR 落地：链接命令不得残留 --image-base / -no-pie
  #      （v3.2.13 加 -DNOLOADADDR=ON 的配套自检，防上游 CMake 行为漂移）
  #
  # v3.3.10（box64 构建修复）：shm 断言组【仅在 0001 补丁实际在链时执行】。
  # 补丁在链的判据 = 源码树存在补丁引入的 src/libtools/android_shmem.c
  # 且含 ANW_EXPORT 特征（源码复用分支 patchesApplied 变量为空、不能作
  # 依据；查源码树特征对"重新获取+跳过补丁/复用旧树+已打补丁"两种
  # 路径都成立）。补丁被跳过（<0.4.4 旧版 / master 漂移）时：
  #   * shm 四符号 / .dynsym 导出 / 注册字符串三项跳过（必然缺，
  #     误判失败毫无意义），改打一行大字警告 —— 后果见头部说明；
  #   * DYNAREC 核心（getDB/FreeDynablock）与 NOLOADADDR 断言照常执行
  #     （不依赖补丁，架构完整性仍受保护）。
  local _patch_in_tree=0
  if [[ -f "${_src}/src/libtools/android_shmem.c" ]] \
     && grep -q 'ANW_EXPORT' "${_src}/src/libtools/android_shmem.c" 2>/dev/null; then
    _patch_in_tree=1
  fi
  if [[ "${_patch_in_tree}" == "0" ]]; then
    echo "=============================================================="
    echo "⚠️⚠️ 0001-linbox-android-shmem-native.patch 未在链（源码树异常"
    echo "    漂移 —— v3.3.34 拆分后该补丁与源码布局无关，v0.3.2+ 任何"
    echo "    版本线都应命中，custom_patch 门禁也应因此失败中止）——"
    echo "    本产物为【未打补丁】形态："
    echo "    * bionic 宿主上 shm 链路两条全断（静态程序 ENOSYS / 动态程序"
    echo "      function not found）—— wine 需要 SysV shm 的路径会失败；"
    echo "    * NDK lld 的 Android 标签 RELR 重定位（DT_ANDROID_RELR）不识别"
    echo "      —— bionic wine ntdll 的 .data 自指针保持裸地址，wineboot 有"
    echo "      SIGSEGV 风险。"
    echo "    如需完整特性：核查源码树与 custom_patch 的 fuzz=0 严格门禁日志。"
    echo "=============================================================="
  fi
  local _sym_ok=1
  for _sym in shmget shmat shmctl shmdt getDB FreeDynablock; do
    if [[ "${_sym}" == shm* && "${_patch_in_tree}" == "0" ]]; then
      continue   # 补丁不在链：shm 符号必缺，跳过（见上）
    fi
    if ! nm "${_src}/build/box64" 2>/dev/null | grep -qw "${_sym}" \
       && ! readelf -Ws "${_src}/build/box64" 2>/dev/null | grep -qw "${_sym}"; then
      echo "错误: box64 符号表缺 ${_sym}（v3.2.18 内建 shm 实现 / DYNAREC 核心未链接 —— 0001 补丁没生效？）"
      _sym_ok=0
    fi
  done
  if [[ "${_patch_in_tree}" == "1" ]] \
     && ! nm -D "${_src}/build/box64" 2>/dev/null | grep -qwE "shmget|my_shmget"; then
    echo "错误: shm 符号未进动态导出表（.dynsym），wrapped-libc 桥接将找不到 my_*"
    echo "  （v3.2.21 根因：上游 -fvisibility=hidden 隐藏符号永不入 .dynsym，且 my_* 无"
    echo "   静态引用会被 --gc-sections 回收 —— 查 0001 补丁 ANW_EXPORT 可见性修复是否在链）"
    _sym_ok=0
  fi
  local _shmem_ok=1
  if [[ "${_patch_in_tree}" == "1" ]] \
     && [[ ! -f "${_src}/build/CMakeFiles/mainobj.dir/src/wrapped/wrappedandroidshmem.c.o" ]]; then
    echo "错误: wrappedandroidshmem.c 编译单元不在链（-DANDROID=ON 未生效？）"
    _shmem_ok=0
  fi
  if ! strings "${_src}/build/box64" 2>/dev/null | grep -q "libandroid-shmem\.so"; then
    # 上游 ANDROID 模式自带 wrappedandroidshmem 注册（不依赖 0001 补丁），照常硬校验
    echo "错误: 产物未注册 libandroid-shmem.so 包装（ANDROID 编译单元缺失）"
    _shmem_ok=0
  fi
  [[ "${_sym_ok}" == "1" && "${_shmem_ok}" == "1" ]] \
    && echo "冒烟[1/2]: $(
      if [[ "${_patch_in_tree}" == "1" ]]; then
        echo "内建 SysV shm（v3.2.21 默认可见性导出：shmget/shmat/shmctl/shmdt + my_* 均已入 .dynsym）+ "
      else
        echo "（0001 补丁未在链，shm 断言跳过；见上方警告） "
      fi
    )DYNAREC 核心（getDB/FreeDynablock）✓" \
    || return 1
  if grep -q "image-base\|no-pie" "${_src}/build/CMakeFiles/box64.dir/link.txt" 2>/dev/null; then
    echo "错误: 链接命令残留 --image-base/-no-pie（NOLOADADDR 未生效）"
    grep -h "image-base\|no-pie" "${_src}/build/CMakeFiles/box64.dir/link.txt"
    return 1
  fi
  echo "冒烟[2/2]: 无 --image-base/-no-pie（NOLOADADDR 已生效）✓"

  # ---- 7. 配套运行层就位报告（deps 保证已装进 prefix，纯诊断） ----
  [[ -e "${prefix}/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" ]] \
    && echo "配套 aarch64 glibc 层: ${prefix}/lib/aarch64-linux-gnu/（glibc 形态 box64 / glibc 二进制运行层）" \
    || echo "警告: prefix 缺 aarch64-linux-gnu（glibc-aarch64 未先行安装？）"
  [[ -e "${prefix}/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2" ]] \
    && echo "配套 x86_64 glibc 层: ${prefix}/lib/x86_64-linux-gnu/（被仿真程序运行库）" \
    || echo "警告: prefix 缺 x86_64-linux-gnu（glibc-x86_64 未先行安装？）"

  echo "bionic-box64 安装完成:"
  echo "  ${_bin}（PT_INTERP => /system/bin/linker64）"
  echo "  ${prefix}/bin/box64 → ../local/bin/box64（PATH 入口）"
  echo "  ${prefix}/lib/box64-x86_64-linux-gnu/libc++_shared.so"
  echo "  ${prefix}/etc/box64.box64rc"
}
