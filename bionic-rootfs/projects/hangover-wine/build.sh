# 源码版本/URL 可用环境变量覆盖（CI 工作流用下拉选择传入）：
#   LINBOX_HANGOVER_TAG / LINBOX_WINE_URL / LINBOX_WINE_LABEL
# 源码支持三种来源（本配方即 arm64ec 形态配方，任意 wine 树皆可构建）：
#   hangover  AndreRH/wine        tag    hangover-<ver>   （默认）
#   official  wine-mirror/wine    tag    wine-<ver>       （WineHQ 官方源码 GitHub 镜像）
#   proton    ValveSoftware/wine  branch proton_<ver>     （Valve Proton 的 wine 源码树）
# 本目录的 bionic 补丁针对 hangover 树生成；official/proton 树结构相同
# （均为上游 wine 树），个别 hunk 可能因版本漂移失败——pre_setup 对此
# 容错（打印告警继续构建），失败 hunk 落 *.rej 供人工核查。
revision="${LINBOX_HANGOVER_TAG:-11.16}"
url="${LINBOX_WINE_URL:-https://github.com/AndreRH/wine/archive/refs/tags/hangover-${revision}.tar.gz}"
urlType="tar"
arch="aarch64"
buildSys="autotools"
license="LGPL-2.1"
doNotApplyPatch=1

# ============================================================
# LinBox bionic Wine（arm64ec 形态 / X11 / 新 WoW64）
# ------------------------------------------------------------
# 与 wine（x86_64，box64 加载）互补的第二个 Wine 运行时：
#   * 目标架构 aarch64（bionic），PE 侧 arm64ec+aarch64+i386（新 WoW64）
#     —— aarch64 设备原生运行，无需 box64 整机模拟
#   * 显示：仅 X11（--with-x 全套 X 扩展；无 wayland）
#   * 安装路径 $prefix/opt（启动器 WINE_HANGOVER 分支识别）
# llvm-mingw 必须用 20251202+：arm64ec PE 目标需要较新的 llvm/mingw-w64
# （与用户 glibc 流水线 wine-Arm.yml 的验证组合一致）
# ============================================================

args="
  ac_cv_header_linux_userfaultfd_h=no
  ac_cv_path_GRADLE=no
  --prefix=$prefix/opt
  --exec-prefix=$prefix/opt/
  --includedir=$prefix/opt/include
  --libdir=$prefix/opt/lib
  --enable-archs=arm64ec,aarch64,i386
  --enable-nls
  --disable-tests
  --disable-wineandroid-drv
  --with-alsa
  --without-capi
  --without-coreaudio
  --without-cups
  --without-dbus
  --with-ffmpeg
  --with-fontconfig
  --with-freetype
  --without-gettextpo
  --without-gphoto
  --with-gnutls
  --without-gssapi
  --with-gstreamer
  --without-inotify
  --without-krb5
  --without-mingw
  --without-netapi
  --without-opencl
  --with-opengl
  --without-osmesa
  --without-oss
  --without-pcap
  --without-pcsclite
  --with-pthread
  --with-pulse
  --without-sane
  --without-sdl
  --without-udev
  --without-unwind
  --without-usb
  --without-v4l2
  --with-vulkan
  --without-wayland
  --with-x
  --with-xcomposite
  --with-xcursor
  --with-xfixes
  --with-xinerama
  --with-xinput
  --with-xinput2
  --with-xrandr
  --with-xrender
  --with-xshape
  --with-xshm
  --with-xxf86vm
"

# v3.4.7：alsa-plugins（pulse 桥）入列 —— winealsa 在 Android 的
# 唯一现实出口（/dev/snd 不存在；libasound 默认 PCM → pulse 插件
# → rootfs 守护 → sles-sink）。用户实证 "nopulse 用 alsa 但 alsa
# 没补全依赖" 的缺口补全；详见 projects/alsa-plugins/build.sh。
deps="pthread-stub alsa-lib alsa-plugins fontconfig freetype gnutls gstreamer ffmpeg pulseaudio xkeyboard-config libxkbcommon mesa vulkan-headers vulkan-icd-loader xorgproto libxcb xtrans libX11 libXext libXrender libXfixes libXi libXrandr libXcursor libXinerama libXcomposite libXxf86vm libandroid-shmem"

# 静默编译：wine 的 make 会回显每条完整编译/链接命令（ccache 全路径），
# 单包就曾刷出 11.7 万行日志；-s 只关命令回显，编译器/链接器报错照常输出，
# 失败时仍能从 "make: *** [Makefile:...] Error 1" 定位到目标。
makeSilent=1

llvmMingwVersion="22"
llvmMingwDate="20251202"
llvmMingwUrl="https://github.com/mstorsjo/llvm-mingw/releases/download/${llvmMingwDate}/llvm-mingw-${llvmMingwDate}-ucrt-ubuntu-22.04-x86_64.tar.xz"
llvmMingwSha256=""

# v3.1.5：已删除从未使用的 hangoverDebUrl 死代码（官方整包 tar）——
# 仿真器 DLL 改从 pre_package 下载独立的 hangover_11.16_dlls.tar
# （平铺 libarm64ecfex.dll / libwow64fex.dll / wowbox64.dll + dxvk 合集）。

pre_setup() {
  local _hostTools="${wsDir}/host_tools/wine-tools-hangover-${revision}"
  local _llvmMingwDir="${wsDir}/tmp/llvm-mingw-${llvmMingwVersion}"

  echo "=== hangover-wine 构建开始 ==="

  # 0. 源码树兼容性探测（official / proton / 自定义树均非 hangover 形态）
  #    a) git 树可能不含预生成的 configure（proton 分支如此）→ autogen.sh 生成
  #    b) arm64ec 能力探测：旧树（≤ wine 9.x 前后）无
  #       'aarch64|arm64ec' 校验分支 → 降级 --enable-archs=aarch64,i386
  #       （仍为新 WoW64：ARM64 PE 原生 + i386 PE；x86_64 PE 不支持，
  #        跑 x86_64 Windows 程序需 hangover 源或 x86_64 形态）
  #       ※ 实证：official-11.0 / proton_10.0 均支持 arm64ec（configure 0 退出），
  #         三大默认源不会触发降级
  if [[ ! -f "${srcDir}/${pjName}/configure" ]]; then
    if [[ -f "${srcDir}/${pjName}/autogen.sh" ]]; then
      echo "源码树无预生成 configure，运行 autogen.sh ..."
      ( cd "${srcDir}/${pjName}" && ./autogen.sh ) || { echo "autogen 失败"; return 1; }
    else
      echo "错误: 源码树既无 configure 也无 autogen.sh"
      return 1
    fi
  fi
  if grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure.ac" 2>/dev/null || \
     grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure" 2>/dev/null; then
    echo "源码树支持 arm64ec（--enable-archs 校验分支存在），保持 --enable-archs=arm64ec,aarch64,i386"
  else
    echo "⚠️ 源码树不支持 arm64ec（过旧/魔改树），降级 --enable-archs=aarch64,i386"
    echo "   （ARM64 PE 原生 + i386 PE 新 WoW64；x86_64 PE 请改用 hangover 源或 x86_64 形态）"
    args="${args/--enable-archs=arm64ec,aarch64,i386/--enable-archs=aarch64,i386}"
  fi

  # 0b. v3.2.8：wine ≤9.x 树 PE 探测程序补丁（arm64ec configure 检测根治）
  #   【根因】（llvm-mingw-20251202 实测复现，与 CI official-9.7 失败逐字一致）：
  #     lld-link 对 arm64ec 目标强制要求镜像含 load config —— 测试程序
  #     无 _load_config_used 时直接：
  #       lld-link: error: EC version of '_load_config_used' is missing
  #     → configure 误判 "arm64ec PE cross-compiler not found" → 构建中止。
  #     上游 wine 10 起（proton_10.0 树已核对）给 WINE_TRY_PE_CFLAGS 测试
  #     程序补了 `const unsigned int _load_config_used[0x50] = {...};` ——
  #     这就是 hangover-11.16/proton_10.0 能过、official-9.7 不能过的
  #     全部差异。真实构建不受影响（winebuild spec32.c output_load_config
  #     为每个 DLL 生成 load config），仅测试程序落后。
  #   【修法】把上游同款一行补进【已生成的】configure（tarball 自带，
  #     autogen 仅在缺失时跑，改 aclocal.m4 不生效）+ aclocal.m4（防
  #     autoreconf 路径）+ configure.ac 无需动（程序文本在宏里）。
  #     实测：补丁后 arm64ec/aarch64/i386 三目标全过、±--no-default-config
  #     两遍全过、-s/-O3/-pipe/-D__WINE_PE_BUILD 全旗标组合无回归。
  #     wine 10+ 树自带该行自动跳过；幂等。
  patch_pe_test_load_config() {
    local _dir="${srcDir}/${pjName}"
    local _hit=0
    # configure：测试程序独立成行（每处 WINE_TRY_PE_CFLAGS 展开一份，~35 处）
    if [[ -f "${_dir}/configure" ]] && grep -q '^void \*__os_arm64x_dispatch_ret = 0;$' "${_dir}/configure" \
       && ! grep -q '_load_config_used\[0x50\]' "${_dir}/configure"; then
      sed -i 's|^void \*__os_arm64x_dispatch_ret = 0;$|void *__os_arm64x_dispatch_ret = 0;\nconst unsigned int _load_config_used[0x50] = { sizeof(_load_config_used) };|' "${_dir}/configure" \
        && _hit=1
      echo "已补 configure PE 探测程序的 _load_config_used（wine ≤9.x 树，arm64ec lld-link 检测失败根治）"
    fi
    # aclocal.m4：宏内联形态（AC_LANG_SOURCE([[void *... 在行尾）
    if [[ -f "${_dir}/aclocal.m4" ]] \
       && grep -q '^AC_LINK_IFELSE(\[AC_LANG_SOURCE(\[\[void \*__os_arm64x_dispatch_ret = 0;$' "${_dir}/aclocal.m4" \
       && ! grep -q '_load_config_used\[0x50\]' "${_dir}/aclocal.m4"; then
      sed -i 's|^AC_LINK_IFELSE(\[AC_LANG_SOURCE(\[\[void \*__os_arm64x_dispatch_ret = 0;$|AC_LINK_IFELSE([AC_LANG_SOURCE([[void *__os_arm64x_dispatch_ret = 0;\nconst unsigned int _load_config_used[0x50] = { sizeof(_load_config_used) };|' "${_dir}/aclocal.m4" \
        && _hit=1
      echo "已补 aclocal.m4 WINE_TRY_PE_CFLAGS 宏的 _load_config_used（autoreconf 路径同步）"
    fi
    if [[ "${_hit}" == "0" ]] && [[ -f "${_dir}/configure" ]]; then
      if grep -q '_load_config_used\[0x50\]' "${_dir}/configure" 2>/dev/null; then
        echo "源码树 PE 探测程序已含 _load_config_used（wine 10+ 树），跳过"
      else
        echo "ℹ️ 未匹配到旧版 PE 探测程序形态（树结构差异），跳过 —— arm64ec 检测若失败请核查 configure 的 WINE_TRY_PE_CFLAGS 测试"
      fi
    fi
    return 0
  }
  patch_pe_test_load_config

  # 1. 下载并设置 llvm-mingw 工具链
  if [[ ! -d "${_llvmMingwDir}" ]]; then
    echo "下载 llvm-mingw 工具链..."
    mkdir -p "${wsDir}/tmp"
    wget -nv -P "${wsDir}/tmp" "${llvmMingwUrl}" || {
      echo "下载 llvm-mingw 失败"
      return 1
    }
    local _tarFile="${wsDir}/tmp/$(basename "${llvmMingwUrl}")"
    mkdir -p "${_llvmMingwDir}-tmp"
    tar -C "${_llvmMingwDir}-tmp" --strip-component=1 -xf "${_tarFile}"
    mv "${_llvmMingwDir}-tmp" "${_llvmMingwDir}"
    rm -rf "${_llvmMingwDir}-tmp"
  fi
  export PATH="${_llvmMingwDir}/bin:${PATH}"

  load_env_host

  cd "${srcDir}/${pjName}"
  # --forward 跳过已应用的 hunk；失败仅告警不中断（official/proton 树
  # 可能已含等价修正），失败 hunk 落 *.rej。
  for _patch in "${wsDir}/projects/${pjName}"/*.patch.beforehostbuild; do
    if [[ -f "${_patch}" ]]; then
      echo "应用 host build 补丁: $(basename "${_patch}")"
      if ! sed "s%@TERMUX_PREFIX@%${prefix}%g" "${_patch}" | patch --silent --forward -p1; then
        echo "⚠️  host 补丁 $(basename "${_patch}") 有 hunk 未应用（树差异，继续构建）"
      fi
    fi
  done

  # v3.2.4：winebuild 汇编器路径硬校验（防御性升级）。using_cc=1（9999=
  # 11.16 树形态 / 9999b=9.x+proton 树形态，按树形态互斥套用）是 aarch64
  # 交叉构建的硬前提：winebuild 组装 PE .s 汇编必须经 cc_command（带
  # 正确 -target 的交叉 clang）；using_cc=0 时回退宿主裸 clang/as，宿主
  # 是 x86_64 runner，汇编 aarch64 代码必失败。此前补丁全落空只打一行
  # 警告、构建继续 —— 28 分钟后死在编译深处、根因难查（CI run#36
  # official-9.2 arm64ec 排查实录）。此处升级为立即失败 + 明示补丁锚点，
  # 未来树结构漂移 1 秒内报清根因。
  if ! grep -q "int using_cc = 1;" "${srcDir}/${pjName}/tools/winebuild/utils.c" 2>/dev/null; then
    echo "::error::winebuild 汇编器补丁（9999/9999b，using_cc=1）未套用 —— 本源码树形态需新增变体补丁"
    echo "   位置: tools/winebuild/utils.c get_as_command()（9999=11.16 树形态 / 9999b=9.x+proton 树形态）"
    echo "   后果: aarch64 交叉构建的 PE .s 汇编将以宿主 x86_64 clang 执行，构建必失败"
    return 1
  fi
  # winegcc android 三元组变换（9998/9998b/9998c/9998d 按树形态互斥）：
  # 命不中仅告警（x86_64 形态 wine-9.2 曾在全部未命中下构建成功 —— 非硬性
  # 依赖；但 aarch64 交叉下 NDK 裸三元组风险更高，亮出来便于排查）。
  if ! grep -qF 'strmake( "%s24"' "${srcDir}/${pjName}/tools/winegcc/winegcc.c" 2>/dev/null; then
    echo "⚠️  winegcc android 目标补丁（9998 系列全部变体）未命中 —— winegcc 将向 NDK clang 传裸 android 三元组"
    echo "    若后续 unix 侧编译报 NDK 三元组/API level 类错误，需为该树形态新增 9998 系列变体"
  fi

  mkdir -p "${srcDir}/${pjName}/wine-tools-build"
  cd "${srcDir}/${pjName}/wine-tools-build"
  FREETYPE_CFLAGS=-I/usr/include/freetype2 FREETYPE_LIBS=-lfreetype ../configure \
    --enable-win64 \
    --without-x \
    --with-freetype \
    --without-fontconfig \
    --without-gnutls \
    --without-cups \
    --without-pulse \
    --without-alsa \
    --without-gstreamer \
    --without-opengl \
    --without-vulkan \
    --without-wayland \
    --disable-tests || {
      echo "host configure 失败"
      return 1
    }
  make ${makeSilent:+-s} -j$(nproc) __tooldeps__ || {
    echo "host build 失败"
    return 1
  }
  make ${makeSilent:+-s} -j$(nproc) -C nls || {
    echo "host build 失败"
    return 1
  }

  cd ..

  # 3. 主构建: 配置和编译 wine
  echo "=== 主构建: 配置 wine ==="
  load_env "$targetArch"
  # load_env 把 NDK bin 前插到 PATH（且本流程 load_env 会被调用两次：
  # build-rootfs-arch.sh autotools 分支先调一次，pre_setup 内再调一次），
  # 必须在这里把 llvm-mingw 重新前插回最前 —— 否则 make 阶段 winegcc
  # 按 -b 目标名（arm64ec-windows-gcc 等）找不到专用编译器时回退解析
  # 裸 "clang"，PATH 顺序决定命中的是 NDK clang（clang 19，不认识
  # -marm64x）—— arm64ec/aarch64 双 arch 启用 ARM64X 混合构建时
  # makedep 会向 winegcc 传 -marm64x（tools/makedep.c 4687 行），
  # NDK clang 直接 "unknown argument" 报错（CI 实测 aclui.dll 链接中止）。
  # llvm-mingw 的 clang 21 接受 -marm64x 并由 lld-link 产出 ARM64X
  # 混合镜像，且其 bin 目录只有裸 clang/clang++，无裸 gcc/ld/ar 等可
  # 遮蔽系统工具的名字；unix .so 侧全部走 $CC（绝对路径 ccache NDK
  # clang），不受此 PATH 调整影响。
  export PATH="${_llvmMingwDir}/bin:${PATH}"
  cd "${srcDir}/${pjName}"

  # bionic 兼容补丁（路径/互斥锁属性/Socket IPX，与 wine 配方同源）
  # 补丁针对 hangover 树生成；official/proton 树个别 hunk 可能漂移，
  # 失败仅告警不中断，*.rej 留源码树供核查。
  local _patchFail=""
  for _patch in "${wsDir}/projects/${pjName}"/*.patch; do
    if [[ -f "${_patch}" ]] && [[ ! "${_patch}" == *".beforehostbuild" ]]; then
      echo "应用主补丁: $(basename "${_patch}")"
      if ! sed -e "s%@TERMUX_PREFIX@%${prefix}%g" \
        -e "s%@TERMUX_BASE_DIR@%/data/data/com.linbox/files/rootfs%g" \
        "${_patch}" | patch --silent --forward -p1; then
        echo "⚠️  主补丁 $(basename "${_patch}") 有 hunk 未应用（源码树差异，继续构建）"
        _patchFail+=" $(basename "${_patch}")"
      fi
    fi
  done
  if [[ -n "${_patchFail}" ]]; then
    echo "⚠️⚠️ 以下补丁存在失败 hunk:${_patchFail}"
    echo "    构建继续；若运行期异常请检查源码树内 *.rej 文件"
  fi

  # v3.3.20（用户问题 3）：wine.inf 垃圾 AppDefaults 注册表剔除 —— proton 树
  # 的 loader/wine.inf.in 带 31 条 Valve/Steam 游戏兼容 hack（ActOfWar/
  # BlackOps3/DIRT5/ffxvi/RDR2 等的 atiadlxx=builtin、winxp/win7 Version、
  # xinput/d3d8/vulkan-1 覆盖、SpeechSynth 禁用），wineboot 建前缀时全部
  # 打进 user.reg —— 设备上前缀里凭空多出几十个无关游戏的注册表块。
  # 官方 wine-11.0 / hangover-11.16 树的 wine.inf.in【零】AppDefaults 行
  # （已实测核对），剔除后与官方树行为完全一致、无任何功能损失（这些
  # 条目只服务于 Steam 上的具体游戏，Android 上全是死配置）。幂等：
  # 官方/hangover 树无匹配行 = no-op。
  strip_wineinf_appdefaults() {
    local _inf="${srcDir}/${pjName}/loader/wine.inf.in"
    [[ -f "$_inf" ]] || { echo "ℹ️ 无 loader/wine.inf.in（树结构差异），跳过 AppDefaults 剔除"; return 0; }
    if ! grep -q '^HKCU,Software\\Wine\\AppDefaults\\' "$_inf"; then
      echo "wine.inf 无游戏 AppDefaults hack（官方/hangover 树），跳过剔除"
      return 0
    fi
    local _n
    _n="$(grep -c '^HKCU,Software\\Wine\\AppDefaults\\' "$_inf")" || true
    sed -i '/^HKCU,Software\\Wine\\AppDefaults\\/d' "$_inf" \
      && echo "已剔除 wine.inf 的 ${_n} 条游戏 AppDefaults hack（proton 树专属垃圾注册表，官方树为零）"
    # 硬校验：剔除后必须一条不剩（防 sed 特殊字符半途失效静默落空）
    if grep -q '^HKCU,Software\\Wine\\AppDefaults\\' "$_inf"; then
      echo "::error::wine.inf AppDefaults 剔除未生效（仍有残留行）！"
      return 1
    fi
    return 0
  }
  strip_wineinf_appdefaults

  # v3.3.20（用户问题 1）：proton_11.0 树 M_PERTURB bionic 兼容 —— Valve 在
  # unix loader.c 的 start_main_thread 无条件调 mallopt( M_PERTURB, 0xff/0 )
  #（加载期堆内存填充加固）。M_PERTURB 是 glibc malloc.h 专属宏，bionic 任何
  # API 级别都没有 → CI proton_11.0 两架构均阵亡于：
  #   dlls/ntdll/unix/loader.c:2454:14: error: use of undeclared identifier 'M_PERTURB'
  # 修法：两处调用各自包 #ifdef M_PERTURB 守卫（glibc 构建行为逐字节不变；
  # bionic 跳过该加固 —— 它只影响 freed-memory 毒化，无功能语义）。幂等：
  # 旧树/官方树无此调用 = no-op。
  fix_mallopt_perturb_bionic() {
    local _f="${srcDir}/${pjName}/dlls/ntdll/unix/loader.c"
    [[ -f "$_f" ]] || return 0
    if ! grep -q 'mallopt( M_PERTURB' "$_f"; then
      echo "ℹ️ loader.c 无 M_PERTURB 调用（非 proton_11 树），跳过 bionic 兼容守卫"
      return 0
    fi
    if grep -q 'LINBOX_M_PERTURB_COMPAT' "$_f"; then
      echo "M_PERTURB bionic 守卫已注入过，跳过"
      return 0
    fi
    sed -i 's|^    mallopt( M_PERTURB, 0xff );|#ifdef M_PERTURB\n    mallopt( M_PERTURB, 0xff );\n#else\n    /* LINBOX_M_PERTURB_COMPAT: bionic 无 M_PERTURB（glibc malloc.h 专属参数），跳过内存填充加固 */\n#endif|' "$_f"
    sed -i 's|^    mallopt( M_PERTURB, 0 );|#ifdef M_PERTURB\n    mallopt( M_PERTURB, 0 );\n#endif|' "$_f"
    # 硬校验：每个 mallopt( M_PERTURB 调用必须各有 #ifdef 守卫（调用数 == 守卫数）
    local _calls _guards
    _calls="$(grep -c 'mallopt( M_PERTURB' "$_f")" || true
    _guards="$(grep -c '^#ifdef M_PERTURB$' "$_f")" || true
    if [[ "${_calls:-0}" -ne "${_guards:-0}" ]] || [[ "${_guards:-0}" -eq 0 ]]; then
      echo "::error::M_PERTURB bionic 守卫注入未生效（mallopt 调用 ${_calls} 处 / 守卫 ${_guards} 处）！"
      return 1
    fi
    echo "已注入 M_PERTURB bionic 守卫（proton_11 树 glibc 专属 mallopt 参数，bionic 编译根因）"
    return 0
  }
  fix_mallopt_perturb_bionic

  # v3.3.10（用户问题 3，参考用户 wine-wow64-no.yml）：mfplat 的 mf 补丁
  # —— MFCreateDXGIDeviceManager 环境变量 stub（WINE_DO_NOT_CREATE_DXGI_
  # DEVICE_MANAGER=1 → E_NOTIMPL，上游游戏/播放器 mfplat 强建 DX manager
  # 崩溃的标准规避）+ bytestream handler 回退（GStreamer handler 在
  # Android/bionic 上常缺）。按版本分档（用户 yml 同款语义）：
  #   >= 9.10（含 10.x/11.x）→ fix_wine9.2_mfplat.sh MPEG4 档
  #                             （CLSID_MPEG4ByteStreamHandlerPlugin，
  #                              wine_do_not_create_dxgi_manager2.patch
  #                              的等价内容锚点实现）；
  #   <  9.10（9.2 等）      → fix_wine9.2_mfplat.sh AVI 档
  #                             （CLSID_AVIByteStreamHandler，
  #                              afeimod/linbox 原脚本原行为）。
  # 载体统一为内容锚点脚本（sed+python，无行号依赖）：上游 patch 的
  # 行号/hunk 形态随版本漂移，脚本按函数名/CLSID 名匹配，任何
  # 9.x/10.x/11.x 树通吃且幂等。
  # 生效硬校验（与 noexec/locale 同款哲学）：env 检查串必须出现在
  # mfplat/main.c，缺失即失败（防脚本静默落空）。
  if [[ -f dlls/mfplat/main.c ]]; then
    _mf_ver="$(echo "${revision}" | sed -n 's/^[a-zA-Z_-]*\([0-9][0-9]*\.[0-9][0-9]*\).*/\1/p')"
    _mf_major="${_mf_ver%%.*}"
    _mf_minor="${_mf_ver#*.}"
    _mf_ge910=0
    if [[ -n "${_mf_ver}" ]]; then
      if (( ${_mf_major:-0} > 9 )) || (( ${_mf_major:-0} == 9 && ${_mf_minor:-0} >= 10 )); then
        _mf_ge910=1
      fi
    fi
    if (( _mf_ge910 == 1 )); then
      echo "mf 补丁档位: revision=${revision} >= 9.10 → MPEG4（wine_do_not_create_dxgi_manager2 等价实现）"
      bash "${wsDir}/projects/${pjName}/fix_wine9.2_mfplat.sh" MPEG4 \
        || { echo "::error::mfplat 修复脚本执行失败"; return 1; }
    else
      echo "mf 补丁档位: revision=${revision} < 9.10 → AVI（fix_wine9.2_mfplat 原行为）"
      bash "${wsDir}/projects/${pjName}/fix_wine9.2_mfplat.sh" AVI \
        || { echo "::error::mfplat 修复脚本执行失败"; return 1; }
    fi
    # 【硬校验】env stub 必须在链
    if ! grep -q 'WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER' dlls/mfplat/main.c 2>/dev/null; then
      echo "::error::mfplat 补丁未生效（main.c 无 WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER 检查）！"
      echo "   mfplat 强建 DX manager 的崩溃路径将无法规避（WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1 失效）"
      echo "   请核查 projects/hangover-wine/fix_wine9.2_mfplat.sh 的锚点（MFCreateDXGIDeviceManager 签名漂移？）"
      return 1
    fi
    echo "mfplat 补丁已生效（WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1 可 stub DX manager；bytestream handler 已回退内置）"
  else
    echo "ℹ️ 源码树无 dlls/mfplat/main.c，跳过 mf 补丁"
  fi

  # v3.3.7：noexec 回退补丁生效【硬校验】。0014/0014b/0014c 三份按树
  # 形态互斥命中（9.x/10.x/11.x 的 map_file_into_view 结构各不相同，
  # 不匹配的 hunk 干净失败），此处校验最终源码必须带 "LinBox-noexec-
  # fallback" 标记 —— 三份全落空 = 产出又是一个"noexec 上 media
  # 报错"的裸奔包（用户 v3.3.6 实测：x86_64 修好了、arm64ec 的
  # wine-9.2 旧包 0014/0014b 全 hunk 失败照样 media 报错）。
  # 同串也是运行期检测锚点：App 侧 WinePatchDetector grep ntdll.so
  # 里的该串判断存量 wine 包是否含回退。
  if [[ -f dlls/ntdll/unix/virtual.c ]] \
     && ! grep -q 'LinBox-noexec-fallback' dlls/ntdll/unix/virtual.c 2>/dev/null; then
    echo "::error::noexec 回退补丁未生效（virtual.c 无 LinBox-noexec-fallback 标记）！"
    echo "   该 wine 包在 noexec 存储（/storage/emulated/0）上会复现 \"Could not load required media\""
    echo "   三份补丁按树形态互斥命中其一：0014(11.x) / 0014b(10.x) / 0014c(9.x)"
    echo "   请核对源码树版本形态与补丁锚点是否匹配（*.rej 落在源码树内可查）"
    return 1
  fi
  echo "noexec 回退补丁已生效（LinBox-noexec-fallback 标记在 virtual.c）"

  # v3.3.7：locale 补丁生效【硬校验】（arm64ec 中文根因）。x86_64 形态
  # 自 v3.3.2 有 0009（LC_ALL 直读），arm64ec 形态此前一直没带 ——
  # bionic setlocale 恒 "C.UTF-8" → wine 恒英文 + ACP=1252（中文
  # 路径/文件名乱码）。0009b 补 unix_to_win_locale 的 __ANDROID__
  # LC_ALL 分支；同串 "LinBox-locale-lc-all" 供 App 侧检测存量包。
  if [[ -f dlls/ntdll/unix/env.c ]] \
     && ! grep -q 'LinBox-locale-lc-all' dlls/ntdll/unix/env.c 2>/dev/null; then
    echo "::error::locale 补丁未生效（env.c 无 LinBox-locale-lc-all 标记）！"
    echo "   该 wine 包在 bionic 上界面恒英文、ACP 恒 1252（中文环境丢失）"
    echo "   请核查补丁锚点：projects/hangover-wine/0009b-android-locale-lc-all.patch"
    return 1
  fi
  echo "locale 补丁已生效（LinBox-locale-lc-all 标记在 env.c，LC_ALL=zh_CN.UTF-8 → LCID 0x0804 / ACP 936）"

  # v3.2.9：0009 math.c arm64ec 补丁生效校验（wine ≤9.x 树的必然炸点）。
  # 0007 的"失败仅告警"哲学对 math.c 不适用：arm64ec 形态下补丁未生效
  # 时，make 首批 PE 编译（~28 分钟后）必炸于 dlls/crtdll/arm64ec-
  # windows/math.o 的 sqrtss/stmxcsr 内联汇编（CI official-9.7 实证，
  # 0007 全量扫描漏掉 math.c —— 三处 x86 专属代码在 EC 编译趟激活）。
  # wine 10+/hangover-11 树自带全部修复（无 sse2_sqrtf 旧形态标记），
  # 本校验自然通过；x86_64 形态/降级 aarch64,i386 无 EC 编译趟，亦跳过。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/msvcrt/math.c ]] \
     && grep -q 'sse2_sqrtf' dlls/msvcrt/math.c 2>/dev/null; then
    echo "::error::0009-fix-arm64ec-math-msvcrt 补丁未生效（math.c 仍含 sse2_sqrtf 旧形态）！"
    echo "   arm64ec 编译趟的 sqrtss/stmxcsr x86 内联汇编必然报错（28 分钟后于 math.o 阵亡）"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0009-fix-arm64ec-math-msvcrt-wine9x.patch"
    return 1
  fi

  # v3.2.10：0007c string.c 调用点守卫校验（wine 9.6+/9.7 新增的 SSE2 memmove
  # 快路径）。0007（9.2 锚点）只修了 2789 行定义段总守卫；9.6 起 memmove 调用
  # 点自带独立 #ifdef __x86_64__ —— EC 趟定义被排除、调用漏网 →
  # "call to undeclared function 'sse2_memmove'"（CI official-9.7 v3.2.9 实证
  # 阵亡点）。wine-10+ 树上游已统一两处守卫 → 本校验自然通过。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/msvcrt/string.c ]] \
     && grep -q 'sse2_memmove' dlls/msvcrt/string.c 2>/dev/null \
     && grep -A1 '^#ifdef __x86_64__$' dlls/msvcrt/string.c 2>/dev/null | grep -q 'return sse2_memmove'; then
    echo "::error::0007c-fix-arm64ec-x64-asm-wine97 补丁未生效（string.c 的 sse2_memmove 调用点仍为旧 #ifdef __x86_64__ 守卫）！"
    echo "   arm64ec 编译趟必炸于 dlls/crtdll/arm64ec-windows/string.o：call to undeclared function 'sse2_memmove'"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0007c-fix-arm64ec-x64-asm-wine97.patch"
    return 1
  fi

  # v3.2.10：0007c cstub.c EC 排除校验。wine ≤9.x 的 rpcrt4/cstub.c
  # vtbl_thunks x64 THUNK_ENTRY 裸汇编在 EC 编译趟必炸（上游 wine-10 起整段
  # 重写为纯 C）；9.x 树若仍无 arm64ec 分支即未生效。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/rpcrt4/cstub.c ]] \
     && grep -q 'THUNK_ENTRY' dlls/rpcrt4/cstub.c 2>/dev/null \
     && ! grep -q '__arm64ec__' dlls/rpcrt4/cstub.c 2>/dev/null; then
    echo "::error::0007c-fix-arm64ec-x64-asm-wine97 补丁未生效（cstub.c 的 vtbl_thunks 仍无 arm64ec 排除分支）！"
    echo "   arm64ec 编译趟的 x64 thunk 裸汇编（movq/jmp *offset）必然报错"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0007c-fix-arm64ec-x64-asm-wine97.patch"
    return 1
  fi

  # v3.2.11：0007d ndr_stubless call_server_func EC 排除校验（CI
  # official-9.7 v3.2.10 实证阵亡点：arm64ec-windows/ndr_stubless.o 的
  # pushq/movq/.seh_pushreg 连片报错）。9.7 树该函数四分支（i386/x86_64/
  # arm/aarch64）无 EC 排除；wine-9.2 同为裸 `#elif defined __x86_64__`
  # （0007 hunk 2 修，同受本检验保护）。wine-10+/hangover 树 asm 已迁
  # thunks.c（本文件仅 extern 声明，__ASM_GLOBAL_FUNC( call_server_func
  # 无匹配）→ 自然跳过。判据：asm 形态存在 && 无括号守卫仍是裸 x86_64
  #（0007d 生效后变为 `&& !defined __arm64ec__`，$ 锚点不再命中）。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/rpcrt4/ndr_stubless.c ]] \
     && grep -q '__ASM_GLOBAL_FUNC( call_server_func' dlls/rpcrt4/ndr_stubless.c 2>/dev/null \
     && grep -q '#elif defined __x86_64__$' dlls/rpcrt4/ndr_stubless.c 2>/dev/null; then
    echo "::error::0007d-fix-arm64ec-x64-asm-wine97-ndr 补丁未生效（call_server_func 的 x64 分支仍无 arm64ec 排除）！"
    echo "   arm64ec 编译趟必炸于 dlls/rpcrt4/arm64ec-windows/ndr_stubless.o（x64 汇编误激活）"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0007d-fix-arm64ec-x64-asm-wine97-ndr.patch"
    return 1
  fi

  # v3.2.12：0007e wbemprox/mshtml EC 排除校验（CI official-9.7 v3.2.11
  # 实证阵亡点：arm64ec-windows/builtin.o 的 __cpuid 隐式声明；及本地
  # llvm-mingw-20251202 逐文件复现的 ifacewrap.o x64 汇编 21 errors）。
  # wine-9.7 wbemprox do_cpuid / mshtml DEFINE_WRAPPER_FUNC 均为裸
  # `__x86_64__` 分派无 EC 排除 —— EC 趟 __x86_64__ 已定义（clang 21
  # AArch64Targets.cpp 实证）→ __cpuid 未声明 / x64 AT&T 汇编送 ARM64
  # 代码生成器即炸。wine-9.2 两文件形态相同（diff 实证 IDENTICAL）同受
  # 保护；wine-10+/hangover 树形态不同（do_cpuid 已改/ifacewrap 已迁）
  # → 锚点不命中自然跳过。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/wbemprox/builtin.c ]] \
     && grep -q '__cpuid( p, ax )' dlls/wbemprox/builtin.c 2>/dev/null \
     && grep -q '#if defined(__i386__) || defined(__x86_64__)$' dlls/wbemprox/builtin.c 2>/dev/null; then
    echo "::error::0007e-fix-arm64ec-wbemprox-mshtml 补丁未生效（wbemprox do_cpuid 的 x86 分支仍无 arm64ec 排除）！"
    echo "   arm64ec 编译趟必炸于 dlls/wbemprox/arm64ec-windows/builtin.o（__cpuid 隐式声明，CI v3.2.11 实证）"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0007e-fix-arm64ec-wbemprox-mshtml-wine97.patch"
    return 1
  fi
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/mshtml/ifacewrap.c ]] \
     && grep -q 'movq 8(%rcx), %rcx' dlls/mshtml/ifacewrap.c 2>/dev/null \
     && grep -q '#elif defined(__x86_64__)$' dlls/mshtml/ifacewrap.c 2>/dev/null; then
    echo "::error::0007e-fix-arm64ec-wbemprox-mshtml 补丁未生效（ifacewrap 的 DEFINE_WRAPPER_FUNC x64 分支仍无 arm64ec 排除）！"
    echo "   arm64ec 编译趟必炸于 dlls/mshtml/arm64ec-windows/ifacewrap.o（x64 汇编误激活，21 errors 连片）"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0007e-fix-arm64ec-wbemprox-mshtml-wine97.patch"
    return 1
  fi

  # v3.2.14：0007f faudio SIMD 走 NEON 校验（CI official-9.7 v3.2.13 实证
  # 阵亡点：libs/faudio/arm64ec-windows/src/FAudio_internal_simd.o 的
  # mmintrin.h "__v2di → int invalid conversion" 20 errors）。FAudio 的
  # SIMD 分发以裸 __x86_64__/_M_X64 首分支自定 __SSE2__ → EC 趟（clang 21
  # 预定义 __x86_64__）include emmintrin.h，而 AArch64 后端无 x86 向量
  # builtins 即炸。0007f 在分发最前插入 arm64ec 分支（NEON）+ NEON 段
  # 判断补 __arm64ec__；非 EC 目标（x86_64/aarch64/i386）行为逐位不变
  # （gcc -E -dM 分支矩阵验证）。锚点："x86_64 guarantees SSE2" 为
  # wine-9.x faudio 旧形态注释 + 全文无 __arm64ec__ 字样 = 补丁未生效。
  # wine-10+/hangover 树 faudio 形态有变则锚点不命中自然跳过；若上游
  # 自带 arm64ec 处理（含 __arm64ec__ 字样）亦跳过。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f libs/faudio/src/FAudio_internal_simd.c ]] \
     && grep -q 'x86_64 guarantees SSE2' libs/faudio/src/FAudio_internal_simd.c 2>/dev/null \
     && ! grep -q '__arm64ec__' libs/faudio/src/FAudio_internal_simd.c 2>/dev/null; then
    echo "::error::0007f-fix-arm64ec-faudio-simd 补丁未生效（FAudio SIMD 分发仍无 arm64ec 排除）！"
    echo "   arm64ec 编译趟必炸于 libs/faudio/arm64ec-windows/src/FAudio_internal_simd.o（emmintrin.h/mmintrin.h 20 errors，CI v3.2.13 实证）"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0007f-fix-arm64ec-faudio-simd-wine97.patch"
    return 1
  fi

  # v3.2.12：0007 __fastfail x86 分支校验（i386 编译趟阻断点）。0007 的
  # __fastfail hunk 在 v3.2.4~v3.2.11 重排分支时把 x86 分支
  # （#elif __x86_64__ || __i386__）的体误留为 ARM64 代码（x0 寄存器 +
  # brk #0xf003）而丢失 int $0x29 → i386 编译趟的 __fastfail 真实调用者
  # （wbemprox/mshtml/ntdll/wined3d）必炸 "unknown register name 'x0' in
  # asm"（本地 llvm-mingw-20251202 实证）。arm64ec/aarch64 趟走前置首
  # 分支不受影响；此前因编译趟顺序（arm64ec 先于 i386）先炸于 __cpuid/
  # x64 汇编而一直未暴露。v3.2.12 修复 0007 本体：x86 分支恢复 int $0x29
  # （与 wine-11.16 上游形态逐字一致）。本校验不限 arm64ec 形态 ——
  # 降级 aarch64,i386 形态同样有 i386 编译趟，mangled 形态对任何架构
  # 组合都是坏的；hangover-11.16 树原生即为正确形态且 0007 不命中，
  # 本校验自然通过。
  if [[ -f include/winnt.h ]] \
     && grep -A1 '^#elif defined(__x86_64__) || defined(__i386__)$' include/winnt.h 2>/dev/null | grep -q '__asm__("x0")'; then
    echo "::error::0007 __fastfail 的 x86 分支仍为 ARM64 代码（x0/brk）—— i386 编译趟必炸！"
    echo "   0007-fix-arm64ec-x64-asm-wine92.patch 为 v3.2.12 前旧版：x86 分支丢失 int \$0x29（修复须内联于 0007 本体，不能追加到 0007e —— CI locale 排序会把 0007e 排在 0007 前）"
    echo "   请核查源码树补丁：projects/hangover-wine/0007-fix-arm64ec-x64-asm-wine92.patch"
    return 1
  fi

  # v3.2.10：0010 HODLL 生效校验（设备实证 wine-proton10 arm64ec 根因）。
  # vanilla wow64/syscall.c get_cpu_dll_name 按注册表 HKLM\Wow64\x86 选 CPU
  # dll，wine.inf 默认写入 xtajit.dll —— Linux 不存在 → 32 位 wow64 进程
  # 必死于 load_64bit_module c0000135 → wineboot 的 Wow64Install 段不执行
  # → C:\windows\syswow64 整目录无内容、x86 程序全不可用。0010 移植
  # hangover 的 HODLL 环境变量机制后本校验自然通过；hangover-11.16 自带
  # 机制（无 xtajit 字样）亦跳过。
  # v3.3.20（CI proton_11.0 实证误报修复）：wine 11 起 get_cpu_dll_name
  # 已把 I386-on-ARM64 的默认 CPU dll 硬编码为 libwow64fex.dll（hangover
  # 机制上游化），xtajit64.dll 字样仅存于"选错 dll"的告警分支 —— 0010 的
  # 目的（默认不找 xtajit）已由上游达成，补丁不适用、校验也不应触发。
  # 判据追加：树已默认 libwow64fex（I386 分支三目 ARM64→libwow64fex）即视为
  # 已修复形态，放行。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/wow64/syscall.c ]] \
     && grep -q 'xtajit' dlls/wow64/syscall.c 2>/dev/null \
     && ! grep -q 'HODLL' dlls/wow64/syscall.c 2>/dev/null \
     && ! grep -q 'libwow64fex' dlls/wow64/syscall.c 2>/dev/null; then
    echo "::error::0010-fix-arm64ec-hodll-wow64 补丁未生效（wow64/syscall.c 仍按注册表找 xtajit.dll，无 HODLL 机制）！"
    echo "   32 位 wow64 进程必死于 load_64bit_module c0000135（找不到 CPU 仿真 dll）"
    echo "   后果：wineboot Wow64Install 段不执行 → syswow64 目录为空、x86 程序无法运行"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0010-fix-arm64ec-hodll-wow64.patch"
    return 1
  fi

  # v3.2.10：0011 HODLL64 生效校验（x64-in-EC 仿真装载）。vanilla wine-10/11
  # 的 ntdll/loader.c load_arm64ec_module 按注册表 HKLM\Wow64\amd64 选 x64
  # 仿真器，默认 xtajit64.dll —— Linux 不存在 → EC 进程（x64 exe/纯 x64 dll）
  # 必死于 NtTerminateProcess。wine-9.x 无该机制（无 xtajit64 字样）自然跳过
  # （9.x 形态不支持 x64-in-EC，为已知限制）；hangover-11.16 自带机制亦跳过。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/ntdll/loader.c ]] \
     && grep -q 'xtajit64' dlls/ntdll/loader.c 2>/dev/null \
     && ! grep -q 'HODLL64' dlls/ntdll/loader.c 2>/dev/null; then
    echo "::error::0011-fix-arm64ec-hodll64-suspend 补丁未生效（loader.c 仍按注册表找 xtajit64.dll，无 HODLL64 机制）！"
    echo "   EC 进程装载 x64 仿真器必失败（NtTerminateProcess），x64 exe/纯 x64 dll 无法运行"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0011-fix-arm64ec-hodll64-suspend-wine10.patch"
    return 1
  fi

  # v3.2.25：0013 rpcrt4 EC 符号 thunk 校验（CI (13) 实证阵亡点：
  # dlls/rpcrt4/aarch64-windows/rpcrt4.dll 链接失败 —— lld-link
  # "undefined symbol: stubless_thunks/vtbl_thunks (EC symbol)"）。
  # 0007c 的空桩只求编译通过，但 C 侧取址引用要求符号带 EC 入口
  # 跳板元数据，裸 asm .globl 符号满足不了 → 链接必炸。0013 把
  # EC 趟改为独立 naked 函数 + 函数指针数组（链接器自动合成入口
  # 跳板）。判据：0007c 生效后（cstub.c 含 __arm64ec__ 字样）而
  # 0013 未生效（cstub.c 无 NdrProxyForwardingFunction 宏标记）。
  # wine-10+/hangover 树 cstub.c 已整段重写（无 THUNK_ENTRY asm 宏）
  # → 两判据不同时成立，自然跳过；x86_64/降级形态无 EC 趟亦跳过。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/rpcrt4/cstub.c ]] \
     && grep -q '__arm64ec__' dlls/rpcrt4/cstub.c 2>/dev/null \
     && ! grep -q 'NdrProxyForwardingFunction' dlls/rpcrt4/cstub.c 2>/dev/null; then
    echo "::error::0013-fix-arm64ec-rpcrt4-thunks 补丁未生效（cstub.c 仍为 0007c 空桩形态，无 naked 函数 thunk）！"
    echo "   arm64ec 链接必炸于 dlls/rpcrt4/aarch64-windows/rpcrt4.dll：undefined symbol: vtbl_thunks (EC symbol)（CI (13) 实证）"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0013-fix-arm64ec-rpcrt4-thunks-wine97.patch"
    return 1
  fi
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/rpcrt4/cproxy.c ]] \
     && grep -q '__ASM_GLOBAL_FUNC( stubless_thunks' dlls/rpcrt4/cproxy.c 2>/dev/null \
     && ! grep -q '__arm64ec__' dlls/rpcrt4/cproxy.c 2>/dev/null; then
    echo "::error::0013-fix-arm64ec-rpcrt4-thunks 补丁未生效（cproxy.c 的 stubless_thunks 仍为裸 asm 定义，无 EC 分支）！"
    echo "   arm64ec 链接必炸于 dlls/rpcrt4/aarch64-windows/rpcrt4.dll：undefined symbol: stubless_thunks (EC symbol)（CI (13) 实证）"
    echo "   请核查源码树形态与补丁锚点：projects/hangover-wine/0013-fix-arm64ec-rpcrt4-thunks-wine97.patch"
    return 1
  fi

  # bionic POSIX shm 兼容注入（幂等）：
  # bionic 任何 API 级别都没有 shm_open/shm_unlink（bionic 官方文档
  # status.md 列为 "Missing functions ... explicitly disallowed by
  # SELinux"），而 proton 树（official <=9.x 旧树同理）的 esync/fsync
  # 无条件调用它们 —— dlls/ntdll/unix/{esync,fsync}.c 与
  # server/{esync,fsync}.c 共 4 个文件：头文件无声明，clang 15+ 的
  # -Wimplicit-function-declaration 直接判错（CI run#14 两个 flavor
  # 均折在此处）；libc 亦无符号，须链 libandroid-shmem（其补丁已实现
  # memfd_create 优先、ashmem 兜底，LDFLAGS 已带 -landroid-shmem）。
  # 这里把原型注入调用文件；wine >=10 官方树/hangover-11 树已删除
  # esync/fsync（合并重构进 sync.c，无 shm 调用），文件缺失自动跳过。
  inject_shm_compat() {
    local _f
    for _f in dlls/ntdll/unix/esync.c dlls/ntdll/unix/fsync.c \
              server/esync.c server/fsync.c; do
      [[ -f "$_f" ]] || continue
      if grep -q "LINBOX_SHM_COMPAT" "$_f"; then
        echo "shm 原型已注入过，跳过 => $_f"
        continue
      fi
      cat > ".linbox-shm-compat.h" <<'SHMDECL'
/* LINBOX_SHM_COMPAT: bionic 无 POSIX shm_open/shm_unlink
   （SELinux 策略禁止，任何 API 级别均无）。实现由 libandroid-shmem
   提供（memfd_create 优先，ashmem 兜底），链接经 -landroid-shmem
   解析；此块仅补原型，避免隐式函数声明被判错。 */
#include <sys/types.h>
extern int shm_open(const char *name, int oflag, mode_t mode);
extern int shm_unlink(const char *name);
SHMDECL
      # 插入位置：紧跟 #include "config.h" 之后 —— wine 的 makedep
      # （tools/makedep.c get_dependencies()）强制校验 config.h 必须是
      # 源文件第一个 include，否则 config.status 生成 Makefile 阶段直接
      # fatal_error "config.h must be included before other headers"
      # （proton_10.0 CI 实测：dlls/ntdll/unix/esync.c:32 中止）。
      # 置顶前置注入会把它顶掉第一位 —— 绝对不可。无 config.h 的文件
      # （老树个别 util）退回置顶注入。
      if grep -q '#include "config.h"' "$_f"; then
        sed -i '/#include "config.h"/r .linbox-shm-compat.h' "$_f" \
          && echo "已注入 shm_open/shm_unlink 原型（config.h 之后）=> $_f"
      else
        cat ".linbox-shm-compat.h" "$_f" > "$_f.linbox-tmp" \
          && mv "$_f.linbox-tmp" "$_f" \
          && echo "已注入 shm_open/shm_unlink 原型（置顶，无 config.h）=> $_f"
      fi
    done
    rm -f ".linbox-shm-compat.h"
  }
  inject_shm_compat

  # ffs bionic 兼容（v3.1.5）：glibc 的 <string.h> 在 _DEFAULT_SOURCE 下
  # 附带声明 ffs()，bionic 的 ffs 只在 <strings.h>（POSIX 规定位置）——
  # 依赖 glibc 隐式声明的树直接判 -Wimplicit-function-declaration 错。
  # CI 实测：hangover-10.18 树 dlls/ntdll/unix/unix_private.h 的
  # ldt_bitmap 路径（660 行 ffs(...)）折在 loadorder.o；11.x/proton 树
  # 无 ffs 用法或已含 strings.h，自动跳过。幂等。
  inject_ffs_compat() {
    local _f="dlls/ntdll/unix/unix_private.h"
    [[ -f "$_f" ]] || return 0
    grep -q "ffs *(" "$_f" || return 0
    if grep -q "LINBOX_FFS_COMPAT" "$_f"; then
      echo "ffs 兼容已注入过，跳过 => $_f"
      return 0
    fi
    if grep -q "#include <strings.h>" "$_f"; then
      echo "unix_private.h 已含 strings.h（ffs 已声明），跳过"
      return 0
    fi
    # 头文件（.c 才有 config.h 首位约束）：插到第一个 #include 之前
    sed -i '0,/^#include/s|^#include|#include <strings.h> /* LINBOX_FFS_COMPAT: bionic 的 ffs 只在 strings.h */\n#include|' "$_f" \
      && echo "已注入 #include <strings.h>（ffs 声明）=> $_f"
  }
  inject_ffs_compat

  # fsync futex_waitv 探测 Android 守卫（v3.1.4，proton 树专属；官方/
  # hangover-11 树已删 esync/fsync，文件缺失自动跳过）：
  #   proton 的 do_fsync()（dlls/ntdll/unix/fsync.c 与 server/fsync.c 各
  #   一份，代码相同）在读 WINEFSYNC 环境变量【之前】无条件执行
  #     syscall( __NR_futex_waitv, ... )
  #   做能力探测。Android 应用沙箱的 seccomp-bpf 对不在白名单内的
  #   syscall 号直接 SECCOMP_RET_TRAP → SIGSYS（信号 31）击杀进程，
  #   errno 永远不会被设置 —— 探测永远走不到 ENOSYS 分支，进程先死
  #   （设备实测：arm64ec 形态 proton-10.0 wineboot -i 退出码 159 =
  #   128+31，前缀建不出来、syswow64 全空，wineserver 同款代码同样
  #   阵亡；WINEFSYNC=0 也无效，因为探测在读环境变量之前）。修法：
  #   bionic 目标（__ANDROID__）硬禁用 fsync —— Android 上即便内核
  #   支持 futex_waitv，seccomp 也封了它，硬禁用是唯一正确行为。
  #   桌面 Linux 构建不受影响。幂等：标记已存在则跳过。
  disable_fsync_probe() {
    local _f
    for _f in dlls/ntdll/unix/fsync.c server/fsync.c; do
      [[ -f "$_f" ]] || continue
      if grep -q "LINBOX_FIX_FSYNC_PROBE" "$_f"; then
        echo "fsync 探测 Android 守卫已注入过，跳过 => $_f"
        continue
      fi
      if ! grep -q 'static int do_fsync_cached = -1;' "$_f"; then
        echo "⚠️ $_f 无 do_fsync_cached 锚点（树结构差异），跳过守卫注入"
        echo "    若运行期 wineboot 以退出码 159（SIGSYS）阵亡，请核查此文件"
        continue
      fi
      cat > ".linbox-fsync-guard.h" <<'FSYSGUARD'
#if defined(__ANDROID__)
    /* LINBOX_FIX_FSYNC_PROBE: Android app seccomp kills out-of-allowlist
     * syscalls with SIGSYS (signal 31, exit code 128+31=159) *before* errno
     * is ever set, so the futex_waitv capability probe below never reaches
     * its ENOSYS branch. Proton calls the probe unconditionally BEFORE
     * reading WINEFSYNC, so even WINEFSYNC=0 cannot prevent the kill.
     * Hard-disable fsync on bionic targets. Desktop Linux unaffected. */
    return 0;
#endif
FSYSGUARD
      sed -i '/static int do_fsync_cached = -1;/r .linbox-fsync-guard.h' "$_f" \
        && echo "已注入 fsync 探测 Android 守卫（SIGSYS 预防）=> $_f"
      rm -f ".linbox-fsync-guard.h"
    done
  }
  disable_fsync_probe

  # v3.2.0：esync Android 硬禁用 —— proton10 arm64ec wineboot 无输出挂死
  # 的头号嫌疑。设备日志实证：ntdll 已成功加载（"linbox: bionic heap
  # tagging disabled" 出现 = __wine_main 在跑）后，esync 初始化路径打印
  # "_r_debug not found in ld.so"（proton/上游参考 系 wine 在 Android 上的
  # 同款输出；上游参考 紧跟 "esync: shm initialized" 能走通，bionic 下
  # 无 _r_debug 导出、无 /dev/shm，该段 DSO 枚举/共享内存代码走不通，
  # 之后进程再无任何输出 = 挂死）。esync 在 Android 沙箱收益为零
  # （上游参考/上游项目 默认关闭），与 fsync 守卫同款处理：__ANDROID__
  # 目标 do_esync 直接 return 0；运行期 WINEESYNC=0 环境兜底保留。
  # wine >=10 官方树/hangover-11 树 esync 并入 sync.c（无独立 esync.c），
  # 文件缺失自动跳过；幂等。
  disable_esync_android() {
    local _f
    for _f in dlls/ntdll/unix/sync.c dlls/ntdll/unix/esync.c \
              server/sync.c server/esync.c; do
      [[ -f "$_f" ]] || continue
      if grep -q "LINBOX_FIX_ESYNC_ANDROID" "$_f"; then
        echo "esync Android 守卫已注入过，跳过 => $_f"
        continue
      fi
      if ! grep -q "do_esync_cached = -1" "$_f"; then
        echo "⚠️ $_f 无 do_esync_cached 锚点（树结构差异），跳过 esync 守卫注入"
        echo "    若运行期 wineboot 挂死在 esync 初始化，请核查此文件"
        continue
      fi
      cat > ".linbox-esync-guard.h" <<'ESYNCGUARD'
#if defined(__ANDROID__)
    /* LINBOX_FIX_ESYNC_ANDROID: bionic has no _r_debug export and no
     * /dev/shm; proton's esync DSO-enumeration/shm init path hangs the
     * process silently on Android (device log: "_r_debug not found in
     * ld.so" then no output at all). esync gives zero benefit in the
     * Android sandbox (上游参考/上游项目 ship it disabled). Hard-disable
     * on bionic targets; WINEESYNC=0 runtime fallback stays. */
    return 0;
#endif
ESYNCGUARD
      sed -i '/do_esync_cached = -1/r .linbox-esync-guard.h' "$_f" \
        && echo "已注入 esync Android 守卫（挂死预防）=> $_f"
      rm -f ".linbox-esync-guard.h"
    done
  }
  disable_esync_android

  # v3.2.8：userfaultfd Android 硬禁用 —— proton_10.0 / wine-10.0 树
  # arm64ec wineboot 退出码 159（SIGSYS）的【真正根因】（fsync 探测只是
  # 前一轮的误诊方向：构建守卫早已生效，159 依旧）。
  # [证据链]（proton_10.0 源码逐行核实 + 设备日志时序对齐）：
  #   * wine 10 系树的 dlls/ntdll/unix/virtual.c 在 virtual_init() 里
  #     无条件执行（除非 WINE_DISABLE_KERNEL_WRITEWATCH=1）：
  #       kernel_writewatch_init() → syscall( __NR_userfaultfd,
  #       O_CLOEXEC|O_NONBLOCK|UFFD_USER_MODE_ONLY )   // aarch64 号 374
  #   * Android 应用沙箱 seccomp 白名单不含 userfaultfd → SECCOMP_RET_TRAP
  #     → SIGSYS(31) → 退出码 159（128+31）。进程死在 virtual_init，
  #     即 "linbox: bionic heap tagging disabled" 打印后、wineserver 握手
  #     前 —— 与设备日志"打印一行后秒退 159"逐毫秒吻合；
  #   * wine --version 不走 virtual_init（check_command_line 提前 exit(0)）
  #     —— 加载器冒烟测试通过、真实 wineboot 阵亡的完整解释；
  #   * hangover-11.16（wine 11）上游已带 `!defined(__ANDROID__)` 编译期
  #     排除（virtual.c:76）+ configure 探测 linux/userfaultfd.h（本配方
  #     ac_cv_header_linux_userfaultfd_h=no 双保险）—— 同设备可用；
  #     wine 10/proton_10.0 树既无编译期守卫也无 configure 探测，本配方
  #     此前传的 ac_cv_header_linux_userfaultfd_h=no 对它【完全无效】
  #     （configure.ac 压根没有该项检查，virtual.c 无条件 include）。
  # [修法] 对无编译期守卫的树（wine 10 系/proton_10.0）注入 __ANDROID__
  #   早退守卫（对齐 wine 11 上游行为；写监视回退用户态实现，Android 沙箱
  #   无损失）；wine 11+ 树自带守卫自动跳过。幂等。
  # [运行期兜底] WINE_DISABLE_KERNEL_WRITEWATCH=1（wine 10 树自带该变量，
  #   virtual_init 读取）已由 App 侧 WineEnvironment 与 linbox-wine 脚本
  #   默认导出 —— 旧包免重编即修。
  disable_userfaultfd_android() {
    local _f="dlls/ntdll/unix/virtual.c"
    [[ -f "$_f" ]] || { echo "ℹ️ 无 $_f（树结构差异），跳过 userfaultfd 守卫"; return 0; }
    if grep -q "LINBOX_FIX_UFFD_ANDROID" "$_f"; then
      echo "userfaultfd Android 守卫已注入过，跳过 => $_f"
      return 0
    fi
    # wine 11+ 树：编译期已排除（上游自带 __ANDROID__ 守卫），零改动
    if grep -q '!defined(__ANDROID__)' "$_f" && grep -q 'userfaultfd' "$_f"; then
      echo "源码树已带 userfaultfd __ANDROID__ 编译期守卫（wine 11+），跳过 => $_f"
      return 0
    fi
    # wine 10 系树：kernel_writewatch_init 内的裸 syscall 必须存在
    if ! grep -q 'syscall( __NR_userfaultfd' "$_f"; then
      echo "ℹ️ $_f 无 userfaultfd 调用（旧树/已重构），跳过守卫注入"
      return 0
    fi
    # 首选锚点：函数体首行声明（wine 10 树结构稳定）—— 其后插入早退守卫
    if grep -q 'struct uffdio_api uffdio_api;' "$_f"; then
      cat > ".linbox-uffd-guard.h" <<'UFFDGUARD'
#if defined(__ANDROID__)
    /* LINBOX_FIX_UFFD_ANDROID: userfaultfd (aarch64 nr 374) is not in the
     * Android app seccomp allowlist -> SECCOMP_RET_TRAP -> SIGSYS (signal
     * 31) kills the process with exit code 159 during virtual_init(),
     * right after "bionic heap tagging disabled" is printed and before the
     * wineserver handshake. Wine 11+ guards this with !defined(__ANDROID__)
     * at compile time; wine 10 / proton_10.0 trees do not. Write watches
     * fall back to the userspace implementation - zero loss on Android. */
    uffd_fd = -1;
    return;
#endif
UFFDGUARD
      sed -i '/struct uffdio_api uffdio_api;/r .linbox-uffd-guard.h' "$_f" \
        && echo "已注入 userfaultfd Android 守卫（159/SIGSYS 根治）=> $_f"
      rm -f ".linbox-uffd-guard.h"
    else
      # 锚点漂移兜底：整行改写 syscall 行为 uffd_fd=-1（软路径；.* 吞掉
      # 管道符等特殊字符，# 分隔符避开模式内的 | 与 /）
      sed -i 's#^    uffd_fd = syscall( __NR_userfaultfd.*#    uffd_fd = -1; /* LINBOX_FIX_UFFD_ANDROID: userfaultfd blocked by Android app seccomp (SIGSYS/159) */#' "$_f"
      if grep -q "LINBOX_FIX_UFFD_ANDROID" "$_f"; then
        echo "已改写 userfaultfd 调用为软路径（159/SIGSYS 根治，锚点漂移路径）=> $_f"
      else
        echo "⚠️ userfaultfd 守卫未命中锚点（树结构差异）—— 若运行期仍 159 请核查 $_f"
      fi
    fi
  }
  disable_userfaultfd_android

  # v3.2.3：wine-preloader Android 全面禁用 —— proton 两形态前缀构建
  # 失败（arm64ec 阵亡 / x86_64 静默阵亡）的根治之一（另一根因 = Valve
  # 树 start_main_thread 无条件调 fsync_init → do_fsync 的 futex_waitv
  # 裸探测 → seccomp SIGSYS(159)，disable_fsync_probe 已守卫；App 侧
  # WineProbePatch.kt 对已装旧包就地改写探测立即数，免重编）。
  # [证据链]（2026-10-30 设备实测日志 + proton_10.0/hangover 两树源码
  # 逐行核对 —— 本轮勘误）：
  #   * proton_10.0 arm64ec：日志 "_r_debug not found in ld.so"（出处
  #     loader/preloader.c:1516 —— 预加载器 GDB 集成的**非致命**告警，
  #     与 esync 无关，v3.2.0 的 esync 归因是误诊）+ "bionic heap
  #     tagging disabled" ×2 = __wine_main 先直跑一遍、再经 wine-preloader
  #     重执行一遍；此后再无任何输出 —— 第二遍的死点在 start_main_thread
  #     的 fsync_init()（Valve 专属、无条件调用；探测被守卫前 = SIGSYS
  #     159）或预加载器交接后的进程状态，两者均在本轮修复面内。
  #   * proton_10.0 x86_64 + box64：零输出阵亡且**无** "_r_debug" 告警
  #     —— 死在预加载器进入前后（box64 仿真手写 ELF 加载器跑不通）；
  #     或同样死于 fsync 探测（heap-tagging 打印是 aarch64-only，x86_64
  #     的静默属正常，无法从日志区分 —— 两处都修掉）。
  #   * hangover-10.18/11.16（同设备实测可用）：经核对**同样有** pre_exec
  #     重执行分支与 wine-preloader（此前"上游已移除"的说法有误，
  #     hangover 的 check_command_line→reexec_loader 一样走预加载器）
  #     —— hangover 可用而 proton 阵亡的结构性差异在：Valve 树的
  #     fsync_init/esync_init 无条件调用（hangover/官方树根本没有）与
  #     x86_64 形态的 box64 仿真。预加载器本体在 bionic aarch64 原生
  #     执行下可用；禁用它属"消除整条风险链"而非修复其自身。
  # [修法] 停用整条预加载器链（等价 HAVE_WINE_PRELOADER 未定义的上游
  #   官方路径；地址空间保留由 virtual_init 的无预加载器分支自行完成，
  #   Apple 平台同款语义）：
  #   1) pre_exec() → 0：首个 wine 进程不再重执行；
  #   2) preloader_exec() 排除 __ANDROID__：子进程（wineboot/services/
  #      explorer…）直接 exec wine loader —— 即原代码 execv 失败后的
  #      官方兜底分支 execv(argv[1], argv+1)；
  #   3) pre_package 剔除 bin/*-preloader 入口；App 侧 WineLayoutRepair
  #      对已装旧包同步停用（改名 .linbox-disabled，免重装、可逆）。
  # 幂等；锚点缺失（树结构漂移）时告警跳过不中断。
  disable_preloader_android() {
    local _f="dlls/ntdll/unix/loader.c"
    [[ -f "$_f" ]] || { echo "⚠️ 无 $_f（树结构差异），跳过预加载器禁用"; return 0; }
    if grep -q "LINBOX_FIX_PRELOADER_ANDROID" "$_f"; then
      echo "预加载器 Android 禁用已注入过，跳过 => $_f"
      return 0
    fi
    # 1) 首个进程：pre_exec 返回 0（x86_64/aarch64 Linux 均走 #else
    #    通用分支；i386/arm32 的 check_vmsplit 分支与 Apple 分支的
    #    return 1 注释不同，不会误伤）
    if grep -q 'return 1;  /\* we have a preloader \*/' "$_f"; then
      sed -i 's|return 1;  /\* we have a preloader \*/|return 0;  /* LINBOX_FIX_PRELOADER_ANDROID: preloader→linker64 handoff hangs bionic (arm64ec silent hang) and box64 (x86_64 silent death); align with hangover/wine-11 no-preloader behavior — virtual_init reserves address space itself */|' "$_f" \
        && echo "已禁用首进程 preloader 再执行（pre_exec→0）=> $_f"
    else
      echo "ℹ️ $_f 无 pre_exec 锚点（wine 10.18+/11 树已移除再执行分支，无需处理）"
    fi
    # 2) 子进程：preloader_exec 函数体内 #ifdef HAVE_WINE_PRELOADER
    #    改为 Android 排除 —— 函数退化为直接 exec loader
    sed -i '/^static void preloader_exec/,/^}/ s|^#ifdef HAVE_WINE_PRELOADER$|#if defined(HAVE_WINE_PRELOADER) \&\& !defined(__ANDROID__) /* LINBOX_FIX_PRELOADER_ANDROID: children exec the wine loader directly (official execv-failure fallback path); see pre_exec note */|' "$_f"
    if grep -q "LINBOX_FIX_PRELOADER_ANDROID" "$_f"; then
      echo "已禁用子进程 preloader（preloader_exec 直连 loader）=> $_f"
    else
      echo "⚠️ preloader_exec 锚点未命中（树结构差异）—— 子进程仍将经 wine-preloader，请核查 $_f"
    fi
  }
  disable_preloader_android

  # arm64ec PE 导出名校验清理（wdscore，proton_10.0 树实测崩溃点）：
  # proton_10.0 的 dlls/wdscore/wdscore.spec 带 66 个 x86 32 位 MSVC 修饰名
  # @ stub 导出（CDynamicArray 模板成员，thiscall 标记 QAE/QBE 等）。ARM64EC
  # /ARM64X 目标的每个函数导出名都必须能被 x86-64 仿真侧按 x86-64 修饰法
  # 解析，LLVM 对无法解析的名字直接判死 —— llvm-mingw-22（CI 同款 20251202
  # 工具链）本地实测复现：
  #   lld-link: error: Invalid ARM64EC function name
  #            '??0?$CDynamicArray@GPAG@QAE@I@Z'
  #   （CI proton_10.0 实测 dlls/wdscore/aarch64-windows/wdscore.dll 链接中止；
  #     winebuild 生成导入库的 llvm-dlltool -m arm64ec 走同一校验，同样炸）
  # 处理与上游 wine 完全对齐：b8de2fa524 "wdscore: Don't export C++ symbols."
  # 删光这些 C++ 修饰名导出（hangover-11.x/official-11 树已带该提交，
  # proton_10.0 分叉早于此 —— 删除后本树 wdscore.spec 与上游 master 逐字节
  # 一致）。stub 本无任何实现，删除零功能损失；i386-windows 同树构建虽共用
  # 此 spec，但上游已同样全局删除，行为一致。其余 dll 的修饰名导出（msvcp*/
  # msvcr* 等）已经 llvm-dlltool -m arm64ec 全量逐名校验，全部合法无需处理
  # （drmclien 的 QAE 名实测可过校验，上游亦未清理，保持不动）。x86_64 形态
  # （i386/x86_64 PE）无 ARM64EC 校验，完全不受影响 —— 故仅本配方调用。
  # 按行删除天然幂等；树无此文件（未来上游重构）自动跳过。
  sanitize_arm64ec_spec() {
    local _f _n
    for _f in dlls/wdscore/wdscore.spec; do
      [[ -f "$_f" ]] || continue
      _n=$(grep -c '^@ stub ?' "$_f") || true
      if [[ "${_n:-0}" -eq 0 ]]; then
        echo "wdscore 无 C++ 修饰名 stub 导出，跳过"
        continue
      fi
      sed -i '/^@ stub ?/d' "$_f" \
        && echo "已删除 ${_n} 个 C++ 32位修饰名 stub 导出 => $_f（对齐上游 b8de2fa524）"
    done
  }
  sanitize_arm64ec_spec

  # winedmo ffmpeg>=7 兼容改造（proton 树专属，官方/hangover 树无此文件）：
  # proton 的 dlls/winedmo/libavcodec/pcm_byte_order_reverse_bsf.c 是从
  # ffmpeg 内部源码拷贝的自定义 BSF，依赖 <7 的内部 ABI
  # （AVBSFContext.internal / ff_bsf_get_packet / AVBitStreamFilter 内部
  # 回调字段）。rootfs 的 ffmpeg 为 n9.0，公共头只剩 name/codec_ids/
  # priv_class 三字段，CI proton_10.0 实测 5 个编译错误直接炸掉
  # winedmo（--with-ffmpeg 的唯一消费者）。
  # 处理：把 BSF 文件整体重写为纯公共 API 的字节序反转 helper，并在
  # unix_demuxer.c 中改在 demuxer_read 收包后做反转（见
  # 0004-fix-winedmo-ffmpeg9.patch.proton；.patch.proton 后缀避开主补丁
  # 循环的 *.patch 通配，由这里条件应用）。功能等价：BE PCM → LE +
  # par_out 报 LE codec id（media type 构建读 filter->par_out）。
  if [[ -f dlls/winedmo/libavcodec/pcm_byte_order_reverse_bsf.c ]] \
     && grep -q "ctx->internal" dlls/winedmo/libavcodec/pcm_byte_order_reverse_bsf.c; then
    if grep -q "pcm_reverse_bytes" dlls/winedmo/unix_demuxer.c 2>/dev/null; then
      echo "winedmo ffmpeg>=7 兼容改造已应用过，跳过"
    else
      echo "应用 winedmo ffmpeg>=7 兼容改造（proton 自定义 BSF 使用 ffmpeg 内部 ABI）"
      cat > dlls/winedmo/libavcodec/pcm_byte_order_reverse_bsf.c <<'PCMBSF'
/*
 * PCM byte order conversion helper (big-endian -> little-endian)
 *
 * 原版为 proton 从 ffmpeg 内部源码拷贝的自定义 BSF，依赖 ffmpeg <7 的
 * 内部 ABI（AVBSFContext.internal / ff_bsf_get_packet / 内部回调字段）。
 * ffmpeg >= 7 将其全部私有化，外部树无法再自定义 BSF —— 改为纯公共
 * API 实现：unix_demuxer.c 在 demuxer_read 收包后调
 * pcm_byte_order_reverse_packet 做同样的逐采样字节序反转，并经
 * filter->par_out 向下游报 LE codec id（media type 构建入口）。
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 */

#if 0
#pragma makedep unix
#endif

#include "config.h"
#include "unix_private.h"

#ifdef HAVE_FFMPEG

/* 需要字节序反转的编解码器的每采样字节数，其余返回 0。
 * 仅处理大端变体：WAVEFORMATEX 无端序信息，容器里的大端 PCM
 * 统一转成小端交给 Windows 侧。 */
unsigned int pcm_byte_order_bytes_per_sample( enum AVCodecID codec_id )
{
    switch (codec_id)
    {
    case AV_CODEC_ID_PCM_S16BE: return 2;
    case AV_CODEC_ID_PCM_S24BE: return 3;
    case AV_CODEC_ID_PCM_S32BE: return 4;
    case AV_CODEC_ID_PCM_S64BE: return 8;
    case AV_CODEC_ID_PCM_F32BE: return 4;
    case AV_CODEC_ID_PCM_F64BE: return 8;
    default: return 0;
    }
}

/* 大端 codec id 的小端等价物（供下游 media type 映射使用）。 */
enum AVCodecID pcm_byte_order_reverse_codec_id( enum AVCodecID codec_id )
{
    switch (codec_id)
    {
    case AV_CODEC_ID_PCM_S16BE: return AV_CODEC_ID_PCM_S16LE;
    case AV_CODEC_ID_PCM_S24BE: return AV_CODEC_ID_PCM_S24LE;
    case AV_CODEC_ID_PCM_S32BE: return AV_CODEC_ID_PCM_S32LE;
    case AV_CODEC_ID_PCM_S64BE: return AV_CODEC_ID_PCM_S64LE;
    case AV_CODEC_ID_PCM_F32BE: return AV_CODEC_ID_PCM_F32LE;
    case AV_CODEC_ID_PCM_F64BE: return AV_CODEC_ID_PCM_F64LE;
    default: return codec_id;
    }
}

/* 原地反转包内每个采样的字节序。 */
void pcm_byte_order_reverse_packet( AVPacket *pkt, unsigned int bytes_per_sample )
{
    unsigned int i, half;
    uint8_t *buf, *end, tmp;

    if (!pkt->data || !bytes_per_sample) return;
    half = bytes_per_sample / 2u;
    buf = pkt->data;
    end = buf + pkt->size - (pkt->size % bytes_per_sample);

    while (buf < end)
    {
        for (i = 0; i < half; ++i)
        {
            tmp = buf[i];
            buf[i] = buf[bytes_per_sample - i - 1];
            buf[bytes_per_sample - i - 1] = tmp;
        }
        buf += bytes_per_sample;
    }
}

#endif /* HAVE_FFMPEG */
PCMBSF
      sed -i 's|^extern const AVBitStreamFilter ff_pcm_byte_order_reverse_bsf;|unsigned int pcm_byte_order_bytes_per_sample( enum AVCodecID codec_id );\nenum AVCodecID pcm_byte_order_reverse_codec_id( enum AVCodecID codec_id );\nvoid pcm_byte_order_reverse_packet( AVPacket *pkt, unsigned int bytes_per_sample );|' \
        dlls/winedmo/unix_private.h \
        && echo "已重写 winedmo 自定义 BSF 为公共 API helper"
      if patch --silent --forward -p1 < "${wsDir}/projects/${pjName}/0004-fix-winedmo-ffmpeg9.patch.proton"; then
        echo "已应用 winedmo demuxer 兼容补丁"
      else
        echo "⚠️ winedmo demuxer 兼容补丁有 hunk 未应用（树差异，继续构建）"
      fi
    fi
  fi

  # wineboot xstate aarch64 空桩补齐（proton 树专属，条件应用）：
  # proton_10.0 的 programs/wineboot/wineboot.c 中 initialize_xstate_features
  # 只在 x86 分支（#if __i386__||__x86_64__）与 #else 分支有定义，
  # #elif defined(__aarch64__) 分支漏掉空桩，而 "initialize_xstate_features( data )"
  # 的调用无条件 —— arm64ec 形态编译 aarch64-windows/arm64ec-windows PE 时
  # wineboot.o 直接报 -Wimplicit-function-declaration（CI proton_10.0 实测，
  # hangover-11.16 树无此代码不受影响）。修法与 #else 分支一致：补空桩。
  # 检测条件：文件存在调用点 且 aarch64 分支内无定义（幂等）。
  # .patch.proton 后缀避开主补丁循环的 *.patch 通配 —— hangover 树
  # wineboot.c 结构相近（同为 #elif __aarch64__ + read_tsc_frequency），
  # 普通应用会 fuzz 误匹配，必须仅在 proton 类树上精确套用。
  if [[ -f programs/wineboot/wineboot.c ]] \
     && grep -q "initialize_xstate_features( data )" programs/wineboot/wineboot.c; then
    if ! sed -n '/#elif defined(__aarch64__)/,/^#else/p' programs/wineboot/wineboot.c \
         | grep -q "initialize_xstate_features"; then
      echo "应用 wineboot aarch64 xstate 空桩补丁（proton 树漏定义）"
      if patch --silent --forward -p1 --fuzz=0 \
           < "${wsDir}/projects/${pjName}/0005-fix-wineboot-aarch64-xstate.patch.proton"; then
        echo "已补齐 wineboot aarch64 分支 initialize_xstate_features 空桩"
      else
        echo "⚠️ wineboot xstate 补丁未应用（树结构差异，继续构建）"
        echo "    若后续 aarch64 PE 编译报 initialize_xstate_features 隐式声明，"
        echo "    请核查 programs/wineboot/wineboot.c 的分支结构"
      fi
    else
      echo "wineboot aarch64 xstate 空桩已存在，跳过"
    fi
  fi



  # v3.3.9：proton_9.0 树 arm64ec 四连修复（CI wine-build-log-arm64ec
  # 实测链条：crtdll file.o x64 内联汇编 → amd_ags_x64 同类 → 0011 断裂态
  # → d3dx11_42 导入库解析）。全部 .patch.proton 后缀避开主补丁循环
  # （official/hangover 树无对应代码，普通应用必然失败产生告警噪音），
  # 按内容特征条件套用 + --fuzz=0 逐字锚点，proton_10.0+（已自带修复）/
  # 其他树自动跳过。

  # a) msvcrt/file.c 的 _isatty x64 汇编包装（Valve 私有 HACK cf08808609）
  #    在 EC 编译趟激活 x86 AT&T 汇编（llvm-mingw arm64ec 同时预定义
  #    __arm64ec__ 和 __x86_64__）。修法与 proton_10.0 官方一致：
  #    #ifdef __x86_64__ → && !defined(__arm64ec__)，EC 回落通用 C 实现。
  #    file.c 经 PARENTSRC 供 crtdll/msvcrt/msvcr70~120/ucrtbase 共用，
  #    不修则 EC 趟首个阵亡点就是 crtdll/arm64ec-windows/file.o。
  if [[ -f dlls/msvcrt/file.c ]] \
     && grep -q '__ASM_GLOBAL_FUNC( _isatty' dlls/msvcrt/file.c; then
    if sed -n '/_isatty (MSVCRT.@)/,/__ASM_GLOBAL_FUNC( _isatty/p' dlls/msvcrt/file.c \
         | grep -q '__arm64ec__'; then
      echo "msvcrt _isatty 已带 arm64ec 守卫（proton_10.0+ 形态），跳过"
    else
      echo "应用 msvcrt _isatty arm64ec 守卫（proton 树 x64 汇编包装，EC 趟必炸点）"
      if patch --silent --forward -p1 --fuzz=0 \
           < "${wsDir}/projects/${pjName}/0015-fix-arm64ec-isatty-proton.patch.proton"; then
        echo "已修复 _isatty EC 编译（proton_10.0 同款守卫）"
      else
        echo "⚠️ _isatty 补丁未应用（树结构差异，继续构建）"
        echo "    若后续 arm64ec PE 编译报 file.c 内联汇编错误，请核查该补丁锚点"
      fi
    fi
  fi

  # b) amd_ags_x64（proton 私有模块，官方树无）：9 个 x64 汇编跳板 +
  #    spec 13 个 -arch=win64 条目。wine-9.0 makedep 无 arm64ec_x64
  #    pragma（proton_10.0 靠它编 ARM64X 混合镜像），EC/aarch64 趟编不
  #    出实现又按 spec 引用 → 双炸。修法：spec arch 收窄为 x86_64（x86_64
  #    形态行为逐字节不变）+ 块守卫加 !__arm64ec__（arch 中性导出
  #    CreateDevice/Init 等均在纯 C 区，不受影响）。
  if [[ -f dlls/amd_ags_x64/amd_ags_x64.spec ]] \
     && grep -q -- '-arch=win64 agsDriverExtensions_BeginUAVOverlap' dlls/amd_ags_x64/amd_ags_x64.spec; then
    echo "应用 amd_ags_x64 arm64ec 修复（proton_9.0 树 x64 跳板收窄为 x86_64 专用）"
    if patch --silent --forward -p1 --fuzz=0 \
         < "${wsDir}/projects/${pjName}/0016-fix-arm64ec-amd-ags-proton.patch.proton"; then
      echo "已修复 amd_ags_x64（EC/aarch64 趟不再编译 x64 汇编跳板）"
    else
      echo "⚠️ amd_ags_x64 补丁未应用（树结构差异，继续构建）"
      echo "    若后续 arm64ec PE 编译报 amd_ags_x64_main.c 汇编错误，请核查该补丁锚点"
    fi
  elif [[ -f dlls/amd_ags_x64/amd_ags_x64.spec ]]; then
    echo "ℹ️ amd_ags_x64 已是修复后形态（或 proton_10.0+ ARM64X 形态），跳过"
  fi

  # c) 0011 挂起管道断裂态补全（wine-9.0 形态树专属）。0011 的锚点只覆盖
  #    wine-9.7/10 形态，在 9.0/9.2/proton_9.0 上部分落空 —— 且有两种
  #    断裂形态（本地六树实测）：
  #    形态一（proton_9.0/wine-9.0）：loader.c 的 GET_PTR 已落位但
  #      pWow64SuspendLocalThread 声明缺失 → EC 编译趟未声明标识符直接炸；
  #    形态二（wine-9.2）：loader.c 声明已落位（0011 hunk2 命中），但
  #      signal_arm64.c 的 aarch64 版 RtlWow64SuspendThread 实现与
  #      wow64/syscall.c 的 Wow64SuspendLocalThread 函数体缺失，而
  #      ntdll.spec/wow64.spec 的导出已落位 → aarch64 ntdll 与 wow64.dll
  #      链接必炸（导出未定义符号）。
  #    门控 = 双断裂态检测（任一命中即补全）：ntdll.spec 已导出但
  #    aarch64 实现缺失；或 wow64.spec 已转真导出但函数体缺失。
  #    hangover-11.16 / wine-9.7 / proton_10.0（实现俱在）不触发。
  #    --fuzz=0 逐字锚点 + 补丁内 loader/misc hunk 用最小上下文
  #    （wine-9.0/9.2 两形态通吃），杜绝跨树 fuzz 误插（重复定义）。
  #    硬校验四要素：loader 声明 + aarch64 实现 + EC 直通桩已有 +
  #    wow64 转发函数，缺一即亮 ::error。
  _susp_broken=0
  if grep -q 'RtlWow64SuspendThread' dlls/ntdll/ntdll.spec 2>/dev/null \
     && ! grep -q 'NTSTATUS WINAPI RtlWow64SuspendThread' dlls/ntdll/signal_arm64.c 2>/dev/null; then
    _susp_broken=1
  fi
  if grep -q '^@ stdcall Wow64SuspendLocalThread' dlls/wow64/wow64.spec 2>/dev/null \
     && ! grep -q 'NTSTATUS WINAPI Wow64SuspendLocalThread' dlls/wow64/syscall.c 2>/dev/null; then
    _susp_broken=1
  fi
  if [[ "${_susp_broken}" == "1" ]]; then
    echo "检测到 0011 挂起管道断裂态（spec 导出已落位而实现缺失）—— 补全 wine-9.0 形态缺失片段"
    if patch --silent --forward -p1 --fuzz=0 \
         < "${wsDir}/projects/${pjName}/0018-fix-arm64ec-suspend-complete-wine90.patch.proton"; then
      echo "已补全挂起管道（loader 声明 + aarch64/EC 实现 + wow64 转发）"
    else
      echo "⚠️ 挂起管道补全补丁部分 hunk 未应用（已存在片段的正常跳过；继续构建）"
    fi
    if ! grep -q '(WINAPI \*pWow64SuspendLocalThread)( HANDLE thread, ULONG \*count )' dlls/ntdll/loader.c 2>/dev/null \
       || ! grep -q 'NTSTATUS WINAPI RtlWow64SuspendThread' dlls/ntdll/signal_arm64.c 2>/dev/null \
       || ! grep -q 'NTSTATUS WINAPI RtlWow64SuspendThread' dlls/ntdll/signal_arm64ec.c 2>/dev/null \
       || ! grep -q 'NTSTATUS WINAPI Wow64SuspendLocalThread' dlls/wow64/syscall.c 2>/dev/null; then
      echo "::error::挂起管道补全未生效（声明/实现仍有缺失）！"
      echo "   后果: EC 编译趟 use of undeclared identifier / aarch64 ntdll、wow64 链接导出未定义"
      echo "   请核查 projects/${pjName}/0018-fix-arm64ec-suspend-complete-wine90.patch.proton 锚点"
      return 1
    fi
    echo "挂起管道四要素校验通过（loader 声明/aarch64 实现/EC 直通/wow64 转发）"
  else
    echo "ℹ️ 挂起管道无需补全（非断裂态或已完整），跳过"
  fi

  # d) d3dx11_42 转发导入修复（与 projects/wine 同源）：proton_9.0 的
  #    IMPORTS = d3dcompiler d3dx11_43 无法解析到导入库（d3dx11_43 的
  #    IMPORTLIB 是 d3dx11）→ i386/arm64ec PE 链接报
  #    lld-link: could not open 'd3dx11_43.lib'。硬校验防静默落空。
  if [[ -f dlls/d3dx11_42/Makefile.in ]] \
     && grep -q '^IMPORTS = d3dcompiler d3dx11_43$' dlls/d3dx11_42/Makefile.in; then
    echo "应用 d3dx11_42 转发导入修复（proton_9.0 树：IMPORTS 改用 d3dx11 导入库）"
    if patch --silent --forward -p1 --fuzz=0 \
         < "${wsDir}/projects/${pjName}/0017-fix-d3dx11-42-importlib-proton.patch.proton"; then
      echo "已修复 d3dx11_42 导入（链接将使用 wine 自产 libd3dx11.a，与 proton_10.0 官方形态一致）"
    else
      echo "⚠️ d3dx11_42 导入修复补丁未应用（树差异，继续构建）"
    fi
    if grep -q '^IMPORTS = d3dcompiler d3dx11_43$' dlls/d3dx11_42/Makefile.in; then
      echo "::error::d3dx11_42 导入修复未生效（Makefile.in 仍引用 d3dx11_43）！"
      echo "   后果: i386/arm64ec PE 链接必报 lld-link: could not open 'd3dx11_43.lib'"
      echo "   请核查 projects/${pjName}/0017-fix-d3dx11-42-importlib-proton.patch.proton 锚点"
      return 1
    fi
  else
    echo "ℹ️ d3dx11_42 无需导入修复（非 proton_9.0 转发形态或已自带），跳过"
  fi

  # e) v3.3.17：proton_9.0 arm64ec 第三批 EC 炸点（CI wine-build-log-arm64ec (1).zip
  #    实证链条终点）：0007/0015/0016 修完后主 make 仍阵亡于
  #    dlls/kernel32/arm64ec-windows/virtual.o（lstrcpyA 的 pushq/movq/
  #    .seh_pushreg 连片 15 errors）；本地全树静态扫描另定位 wineboot 两处
  #    （do_xgetbv x64 汇编 + __cpuidex/__cpuid x86 内建 —— 0005 的 xstate
  #    空桩在 9.0 树 hunk 失败，本补丁一并根治）。全部 .patch.proton 后缀
  #    避开主补丁循环，按内容特征条件套用 + --fuzz=0 逐字锚点；
  #    proton_10.0+/hangover 树（已自带修复）自动跳过。
  if [[ -f dlls/kernel32/virtual.c ]] \
     && grep -q 'lstrcpyA_impl' dlls/kernel32/virtual.c 2>/dev/null; then
    if grep -q '__arm64ec__' dlls/kernel32/virtual.c 2>/dev/null; then
      echo "kernel32 lstrcpyA 已带 arm64ec 守卫（proton_10.0+ 形态），跳过"
    else
      echo "应用 kernel32 lstrcpyA arm64ec 修复（proton_9.0 树 EC 趟必炸点，CI 实证 virtual.o）"
      if patch --silent --forward -p1 --fuzz=0 \
           < "${wsDir}/projects/${pjName}/0019-fix-arm64ec-lstrcpya-wineboot-proton.patch.proton"; then
        echo "已修复 lstrcpyA EC 编译（EC 走 #else 通用 C 实现，与 wine-9.2 上游形态一致）"
      else
        echo "⚠️ lstrcpyA/wineboot EC 补丁有 hunk 未应用（树差异，继续构建）"
        echo "    若 arm64ec PE 编译报 virtual.o / wineboot.o 汇编或 __cpuid 隐式声明错误，请核查该补丁锚点"
      fi
    fi
  fi
  # wineboot 部分（与 virtual.c 同补丁文件，分开门控：proton_9.0 两文件特征独立）
  if [[ -f programs/wineboot/wineboot.c ]] \
     && grep -q 'do_xgetbv' programs/wineboot/wineboot.c 2>/dev/null \
     && ! grep -q '__arm64ec__' programs/wineboot/wineboot.c 2>/dev/null; then
    echo "应用 wineboot xstate/cpuid arm64ec 修复（proton_9.0 树 __cpuidex/__cpuid x86 内建 EC 趟炸点）"
    if patch --silent --forward -p1 --fuzz=0 \
         < "${wsDir}/projects/${pjName}/0019-fix-arm64ec-lstrcpya-wineboot-proton.patch.proton"; then
      echo "已修复 wineboot EC 编译（EC 走 #else 空桩，wine-10 上游同款形态）"
    else
      echo "⚠️ wineboot EC 补丁未应用（可能 virtual.c 部分已应用而 wineboot 锚点漂移，继续构建）"
      echo "    若 arm64ec PE 编译报 wineboot.o 的 __cpuid/__xgetbv 隐式声明或汇编错误，请核查该补丁锚点"
    fi
  fi
  # 【硬校验】两处 EC 排除必须落位（--enable-archs=arm64ec 时）—— 防锚点漂移静默落空
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/kernel32/virtual.c ]] \
     && grep -q 'lstrcpyA_impl' dlls/kernel32/virtual.c 2>/dev/null \
     && ! grep -q '__arm64ec__' dlls/kernel32/virtual.c 2>/dev/null; then
    echo "::error::0019 补丁未生效（kernel32/virtual.c 的 lstrcpyA x64 汇编仍无 arm64ec 排除）！"
    echo "   arm64ec 编译趟必炸于 dlls/kernel32/arm64ec-windows/virtual.o（pushq/.seh_pushreg 连片 15 errors，CI 实证）"
    echo "   请核查源码树形态与补丁锚点：projects/${pjName}/0019-fix-arm64ec-lstrcpya-wineboot-proton.patch.proton"
    return 1
  fi
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f programs/wineboot/wineboot.c ]] \
     && grep -q 'do_xgetbv' programs/wineboot/wineboot.c 2>/dev/null \
     && ! grep -q '__arm64ec__' programs/wineboot/wineboot.c 2>/dev/null; then
    echo "::error::0019 补丁未生效（wineboot.c 的 do_xgetbv x64 汇编/__cpuid 内建仍无 arm64ec 排除）！"
    echo "   arm64ec 编译趟必炸于 programs/wineboot/arm64ec-windows/wineboot.o（xgetbv/shlq 汇编 + __cpuidex 隐式声明）"
    echo "   请核查源码树形态与补丁锚点：projects/${pjName}/0019-fix-arm64ec-lstrcpya-wineboot-proton.patch.proton"
    return 1
  fi

  # f) v3.3.18：proton_9.0 arm64ec 第四批炸点 —— ntdll 专属 syscall 导出缺
  #    EC 裸跳板（CI wine-build-log-arm64ec (2).zip 实证链条终点）。0019 修完
  #    kernel32/virtual.o 后主 make 推进至
  #    dlls/ntdll/arm64ec-windows/signal_arm64ec.o 阵亡：
  #      signal_arm64ec.c:1629: error: use of undeclared identifier
  #        '__wine_needs_override_large_address_aware'
  #      ntsyscalls.h:481: SYSCALL_ENTRY( 0x00e9, __wine_needs_override_large_address_aware, 0 )
  #    【机制】proton_9.0 的 ntdll.spec 尾部有 Valve 追加的三个非 Nt 的
  #    -syscall 条目（__wine_dbg_ftrace / __wine_set_unix_env /
  #    __wine_needs_override_large_address_aware，unix 侧实现俱在），autogen 的
  #    tools/make_specfiles 把它们收进生成的 ntsyscalls.h；x86_64/i386/aarch64
  #    PE 趟由 signal_*.c 的 __ASM_GLOBAL_FUNC 汇编展开自动定义（Valve 不构建
  #    EC 从未暴露），而 arm64ec PE 趟的 signal_arm64ec.c 是逐一手写裸跳板 ——
  #    只写了上游自带的两个 wine_*_file_name，Valve 追加的三个没有跳板：
  #    needs_override 无任何 EC 趟可见声明（仅 unix_private.h）→ 表展开直接
  #    炸；dbg_ftrace/set_unix_env 靠 debug.h/winternl.h 声明过编译但 EC 链接
  #    同缺定义。proton_10.0+ 已删这三个 spec 条目（与官方树一致）→ 门控
  #    不触发；wine-9.7/hangover/official 树无这些条目同理。
  if [[ -f dlls/ntdll/ntdll.spec ]] \
     && grep -q '^@ stdcall -syscall __wine_needs_override_large_address_aware()' dlls/ntdll/ntdll.spec 2>/dev/null \
     && ! grep -q 'SYSCALL_API __wine_needs_override_large_address_aware' dlls/ntdll/signal_arm64ec.c 2>/dev/null; then
    echo "应用 ntdll 专属 syscall EC 跳板补齐（proton_9.0 第四批炸点，CI 实证 signal_arm64ec.o）"
    if patch --silent --forward -p1 --fuzz=0 \
         < "${wsDir}/projects/${pjName}/0020-fix-arm64ec-syscall-thunks-proton9.patch.proton"; then
      echo "已补齐三个 EC 裸跳板（__wine_dbg_ftrace / __wine_set_unix_env / __wine_needs_override_large_address_aware）"
    else
      echo "⚠️ syscall EC 跳板补丁有 hunk 未应用（树差异，继续构建）"
      echo "    若 arm64ec PE 编译报 signal_arm64ec.o undeclared identifier / EC 链接报未定义符号，请核查该补丁锚点"
    fi
  fi
  # 【硬校验】spec 有该 syscall 导出而 EC 跳板仍缺失 —— 防锚点漂移静默落空
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && grep -q '^@ stdcall -syscall __wine_needs_override_large_address_aware()' dlls/ntdll/ntdll.spec 2>/dev/null \
     && ! grep -q 'SYSCALL_API __wine_needs_override_large_address_aware' dlls/ntdll/signal_arm64ec.c 2>/dev/null; then
    echo "::error::0020 补丁未生效（signal_arm64ec.c 仍缺 __wine_needs_override_large_address_aware EC 跳板）！"
    echo "   arm64ec 编译趟必炸于 dlls/ntdll/arm64ec-windows/signal_arm64ec.o（undeclared identifier，CI 实证）；"
    echo "   修完编译错误后 EC 链接还会缺 __wine_dbg_ftrace / __wine_set_unix_env 定义"
    echo "   请核查源码树形态与补丁锚点：projects/${pjName}/0020-fix-arm64ec-syscall-thunks-proton9.patch.proton"
    return 1
  fi
  # 同向排查：EC 趟跳板齐备性兜底校验（spec 里全部非 Nt 的 -syscall 条目都
  # 必须在 signal_arm64ec.c 有跳板或头文件可见声明 —— 防后续 Valve 树再追加
  # v3.4.8：wine-10.x / proton_10.0+ 树跳板形态升级误杀修复 —— 上游把
  #    signal_arm64ec.c 的 200+ 逐条手写裸跳板（NTSTATUS SYSCALL_API <sym>(...)
  #    { __ASM_SYSCALL_FUNC( __id_<sym> ); }）整体重构为表驱动宏：
  #      #define DEFINE_SYSCALL_(ret,name,args) \
  #          ret __attribute__((naked, hybrid_patchable)) name args \
  #          { __ASM_SYSCALL_FUNC( __id_##name, name ); }
  #      DEFINE_SYSCALL(wine_nt_to_unix_file_name, (...))
  #      DEFINE_WRAPPED_SYSCALL(NtAllocateVirtualMemory, (...))
  #    宏展开即完整 EC 裸跳板定义（枚举 id 与 arm64ec_syscalls[] 表同源，
  #    均取自 ntdll.spec 生成的 ntsyscalls.h 的 ALL_SYSCALLS64 —— spec 收录
  #    的 -syscall 条目必被宏覆盖，不存在"表有条目而跳板缺失"）。旧校验只
  #    认 "SYSCALL_API <sym>" 字面量与 extern 声明两种形态 → 对 proton_10.0 /
  #    official-10.x 的 DEFINE_SYSCALL(wine_nt_to_unix_file_name,...) /
  #    DEFINE_SYSCALL(wine_unix_to_nt_file_name,...) 误报"无跳板且无可见
  #    声明"整包误杀（CI wine-build-log-arm64ec (14).zip 实证：pre_setup
  #    阵亡于两条 wine_*_file_name，而该树跳板实为宏形态齐备）。三种合法
  #    形态全识别：proton_9.0 手写 SYSCALL_API、wine-10+ 宏
  #    DEFINE_SYSCALL/DEFINE_WRAPPED_SYSCALL、头文件 extern 声明。
  _ec_syscall_missing=0
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] && [[ -f dlls/ntdll/ntdll.spec ]]; then
    while IFS= read -r _spec_line; do
      _sym=$(echo "${_spec_line}" | sed -n 's/^@ stdcall -syscall[^\n]* \([A-Za-z_][A-Za-z0-9_]*\)(.*/\1/p')
      [[ -z "${_sym}" ]] && continue
      if ! grep -q "SYSCALL_API ${_sym}" dlls/ntdll/signal_arm64ec.c 2>/dev/null \
         && ! grep -qE "DEFINE_SYSCALL\(${_sym},|DEFINE_WRAPPED_SYSCALL\(${_sym}," dlls/ntdll/signal_arm64ec.c 2>/dev/null \
         && ! grep -rq "extern.*${_sym}\b" dlls/ntdll/signal_arm64ec.c include/winternl.h include/wine/debug.h 2>/dev/null; then
        echo "::error::EC 跳板兜底校验失败：ntdll.spec 的 -syscall 条目 ${_sym} 在 signal_arm64ec.c 无跳板且无可见声明！"
        _ec_syscall_missing=1
      fi
    done < <(grep '^@ stdcall -syscall ' dlls/ntdll/ntdll.spec 2>/dev/null | grep -v ' Nt\| Zw')
    [[ "${_ec_syscall_missing}" == "1" ]] && return 1
  fi
  # h) v3.3.19：0013 rpcrt4 EC thunks 生效硬校验（CI wine-build-log-arm64ec.zip
  #    实证 cstub.o / cproxy.o 阵亡：v3.2.25 版 THUNK_ENTRY 宏对 cpsf.h 的
  #    表达式参数（32*(block)+n）做 ##num 粘贴只取首 token，"NdrProxyForwarding
  #    Function32 * (1) + 0(void)" 连片解析错误；且 call_stubless_func 尾调
  #    的 NdrpClientCall2 是 wine-9.7 符号，proton_9.0 叫 ndr_client_call，
  #    链接必炸）。0013 由主补丁循环应用，此处校验 v3.2.26 修复版确实落位
  #    （EC_THUNK 字面量枚举是其独有标记）。wine-10+ 树 cstub/cproxy 已重写
  #    （rpcrt4 无 ALL_THUNK_ENTRIES asm 形态）→ 门控不触发，静默跳过。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/rpcrt4/cstub.c ]] \
     && grep -q 'ALL_THUNK_ENTRIES' dlls/rpcrt4/cstub.c 2>/dev/null \
     && ! grep -q 'EC_THUNK(' dlls/rpcrt4/cstub.c 2>/dev/null; then
    echo "::error::0013 补丁未生效或仍为 v3.2.25 旧版（rpcrt4/cstub.c 的 EC 趟缺 EC_THUNK 字面量枚举）！"
    echo "   arm64ec 编译趟必炸于 dlls/rpcrt4/arm64ec-windows/cstub.o / cproxy.o（##num 表达式粘贴连片解析错误，CI 实证）"
    echo "   请核查源码树形态与补丁锚点：projects/${pjName}/0013-fix-arm64ec-rpcrt4-thunks-wine97.patch"
    return 1
  fi
  # 同向：cproxy 的 EC callee 必须与本树的非 static NDR 客户端符号一致
  # 【树差异】wine-9.7 的非 static 符号是 NdrpClientCall2（ndr_client_call
  # 在 9.7 为 static，跨 TU 不可见）；proton_9.0/wine-9.0 相反（非 static
  # 的是 ndr_client_call）。两符号 4 参签名同形（stub_desc, format,
  # stack_top, fpu_stack），EC 汇编的 x0-x3 传参对两者通用。0013 v3.2.26
  # 按 proton_9.0 形态产出（当前 CI 主战场），wine-9.7 树由此处原地换名。
  # 【树探测】cproxy.c 各原生分支本就调用本树符号：p9 aarch64 =
  # bl __ASM_NAME("ndr_client_call")；w97 aarch64 = bl NdrpClientCall2。
  if [[ "${args}" == *"--enable-archs=arm64ec,"* ]] \
     && [[ -f dlls/rpcrt4/cproxy.c ]] \
     && grep -q 'EC_THUNK(' dlls/rpcrt4/cproxy.c 2>/dev/null; then
    if grep -q 'ASM_NAME("ndr_client_call")' dlls/rpcrt4/cproxy.c 2>/dev/null; then
      _ec_callee=ndr_client_call
    elif grep -q 'bl NdrpClientCall2' dlls/rpcrt4/cproxy.c 2>/dev/null; then
      _ec_callee=NdrpClientCall2
    else
      _ec_callee=""
    fi
    if [[ -n "${_ec_callee}" ]]; then
      if ! grep -q 'bl \\"#'${_ec_callee}'\\"' dlls/rpcrt4/cproxy.c 2>/dev/null; then
        # EC callee 与树不符 → 原地换名（bl \"#符号\" 形态仅 EC 分支使用，
        # 各原生分支用 __ASM_NAME / 裸符号形态，不受本 sed 影响）
        sed -i 's|bl \\"#[A-Za-z_]*\\"|bl \\"#'${_ec_callee}'\\"|' dlls/rpcrt4/cproxy.c
        echo "0013 EC callee 已适配本树（${_ec_callee}）"
      fi
      # 【硬校验】换名后必须命中 —— 防树探测/换名任一环节静默落空
      if ! grep -q 'bl \\"#'${_ec_callee}'\\"' dlls/rpcrt4/cproxy.c 2>/dev/null; then
        echo "::error::0013 EC callee 适配失败（期望 ${_ec_callee}）！"
        echo "   cproxy.c 的 call_stubless_func 尾调符号与本树不符，arm64ec 链接必炸 undefined symbol"
        echo "   请核查：projects/${pjName}/0013-fix-arm64ec-rpcrt4-thunks-wine97.patch（v3.2.26）"
        return 1
      fi
    fi
  fi
  CFLAGS="${CFLAGS/-Oz/}"
  CXXFLAGS="${CXXFLAGS/-Oz/}"
  CPPFLAGS="${CPPFLAGS/-Oz/}"
  CFLAGS="${CFLAGS/-fstack-protector-strong/}"
  CXXFLAGS="${CXXFLAGS/-fstack-protector-strong/}"
  CPPFLAGS="${CPPFLAGS/-fstack-protector-strong/}"
  LDFLAGS="${LDFLAGS/-Wl,-z,relro,-z,now/}"
  # -landroid-shmem：winex11.drv 的 MIT-SHM 路径引用 libandroid_shmget/shmat/
  # shmctl/shmdt（prefix/include/sys/shm.h 由 libandroid-shmem 提供，宏把标准
  # shm 接口映射到 libandroid_ 前缀符号）。LDFLAGS 会进每个 unix .so 的链接行
  # （lld 默认 as-needed，未用到该库的模块不会引入依赖），缺失时 winex11.so
  # 链接报 "undefined symbol: libandroid_shmget" 直接失败。
  export LDFLAGS+=" -Wl,--rosegment -landroid-shmem"

  # 注意：这里不 export {i386,x86_64,aarch64,arm64ec}_CC 覆盖为 NDK clang。
  # 这些变量是 PE 交叉编译器（非 "linux 部分"；unix .so 用的是 $CC），
  # 且 configure.ac 815-816 行 with_mingw 以 AS_VAR_SET 无条件覆盖环境
  # 传入的 ${arch}_CC（CI 实测：即便 export 了 NDK clang，探测仍走
  # llvm-mingw，记录 target=arm64ec-windows）——这几个 export 是死代码，
  # 留着只会误导排查方向。真正决定 make 期编译器的是 PATH 顺序（见上）。
  export CROSSCFLAGS="-O3 -pipe"
  export CROSSLDFLAGS="-s"

  args+=" --with-wine-tools=wine-tools-build --with-mingw=${_llvmMingwDir}/bin/clang"
}

pre_package() {
  # hangover-wine 安装在 $prefix/opt（非 opt/wine），此处仅补标识文件供
  # linbox-container / 用户识别该运行时形态；kind 依源码树实际能力落戳
  # （arm64ec = 完整新 WoW64；arm64 = 极旧树降级形态，三大默认源不触发）
  local _wineDir="${destDir}${prefix}/opt"
  local _isArm64ec=0
  if [[ -d "${_wineDir}" ]]; then
    local _kind="arm64-wow64-x11"
    if grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure.ac" 2>/dev/null || \
       grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure" 2>/dev/null; then
      _isArm64ec=1
      if grep -q "arm64x" "${srcDir}/${pjName}/tools/makedep.c" 2>/dev/null; then
        _kind="arm64ec-wow64-x11"
      else
        # v3.2.4：wine <10 树（official 9.x 等）—— configure 已接受 arm64ec
        # （archs 校验分支存在），但 makedep 无 ARM64X 混合生成：arm64ec PE
        # 独立产出 arm64ec-windows/ 目录，不并入 aarch64-windows/（其 DLL
        # 为纯 aarch64）。功能上仍是完整 arm64ec 形态（_isArm64ec=1，FEX
        # 注入照常），但下游 ARM64X 混合校验必须跳过（对纯 aarch64 DLL
        # 校验 CHPE 元数据必失败，1.5 小时构建后误杀整包）。
        _kind="arm64ec-split-wow64-x11"
        echo "提示: 源码树无 ARM64X 混合（wine <10）—— arm64ec PE 独立 arm64ec-windows/ 目录"
      fi
    fi
    cat > "${_wineDir}/.linbox-wine-info" << EOF
kind=${_kind}
tag=${LINBOX_WINE_LABEL:-hangover-${revision}}
backend=native
EOF
  fi

  # v3.2.1（用户问题 4）：界面翻译可见性 —— make install 在 msgfmt 缺失时
  # 会静默跳过 .mo 安装（wine --enable-nls，LOCALEDIR = 安装目录内
  # share/locale）。此处把 zh_CN 翻译是否存在直接亮在 CI 日志；缺失时
  # App 侧 WineLocaleSupport 会从 APK assets 兜底安装（zh_CN/zh_TW/ja/ko），
  # 新包自带则不覆盖。
  if [[ -f "${_wineDir}/share/locale/zh_CN/LC_MESSAGES/wine.mo" ]]; then
    echo "wine 界面翻译已随包安装（zh_CN/wine.mo 存在）"
  else
    echo "提示: ${_wineDir}/share/locale/zh_CN/LC_MESSAGES/wine.mo 缺失（CI 缺 msgfmt 时 make install 静默跳过）—— App 侧 WineLocaleSupport 将从 APK assets 兜底安装"
  fi

  # FEX/box64 仿真器 DLL 注入（v3.1.4）：arm64ec 形态跑 x86_64/i386
  # Windows 程序靠仿真器 DLL（Hangover 架构：只仿真应用本身，win32
  # syscall 原生直达）—— libarm64ecfex.dll（x86_64 仿真，HODLL64）+
  # libwow64fex.dll（i386 仿真，HODLL）+ wowbox64.dll（box64 PE，i386
  # 备选）。此前配方定义了 hangoverDebUrl 却从未提取，三个 dll 全缺
  # （设备实测：前缀建好后 x86_64 程序起不来）。
  # 来源：Hangover 官方 release 的 hangover_<ver>_dlls.tar（平铺 PE
  # dll），FEX PE 模块自包含（纯 CPU 仿真，syscall 翻译在 wine 侧），
  # 与 wine 版本解耦 —— 固定取 11.16 已知良版本（ARM64EC/xtajit64
  # 接口是 Windows-on-ARM 标准 ABI）。安装位置 lib/wine/
  # aarch64-windows/（wineboot 建前缀时自动拷入 drive_c/windows/
  # system32）。rootfs 构建与 wine CI 包两条产线共用本注入。
  if [[ "${_isArm64ec}" == "1" ]]; then
    local _peDir="${_wineDir}/lib/wine/aarch64-windows"
    if [[ -f "${_peDir}/libarm64ecfex.dll" ]]; then
      echo "FEX 仿真器 DLL 已存在（${_peDir}），跳过注入"
    else
      local _fexUrl="https://github.com/AndreRH/hangover/releases/download/hangover-11.16/hangover_11.16_dlls.tar"
      local _fexTmp="${wsDir}/tmp/hangover-11.16-dlls"
      # v3.1.5：必须 mkdir -p "${_fexTmp}" 本体 —— 此前只建了父目录
      # ${wsDir}/tmp，wget -O 写入 .../hangover-11.16-dlls/dlls.tar.tmp
      # 直接 "No such file or directory"（CI 实测 rootfs + wine 两条产线
      # 全部折在此处，且 pre_package 返回值被忽略 → 残废包静默流出）。
      mkdir -p "${_peDir}" "${_fexTmp}"
      if [[ ! -f "${_fexTmp}/dlls.tar" ]]; then
        echo "下载 Hangover 官方仿真器 DLL 包（libarm64ecfex / libwow64fex / wowbox64）..."
        # --tries/--timeout：GitHub release 走 CDN 重定向，偶发 503/断流
        # 自动重试；失败 return 1（build-rootfs-arch.sh v3.1.5 起会中止构建）
        wget -nv --tries=3 --timeout=60 -O "${_fexTmp}/dlls.tar.tmp" "${_fexUrl}" || {
          echo "错误: 仿真器 DLL 包下载失败: ${_fexUrl}"
          return 1
        }
        mv "${_fexTmp}/dlls.tar.tmp" "${_fexTmp}/dlls.tar"
      fi
      tar -xf "${_fexTmp}/dlls.tar" -C "${_fexTmp}" 2>/dev/null || {
        echo "错误: 仿真器 DLL 包解包失败"
        return 1
      }
      local _dll _hit=0
      for _dll in libarm64ecfex.dll libwow64fex.dll wowbox64.dll; do
        if [[ -f "${_fexTmp}/${_dll}" ]]; then
          cp -p "${_fexTmp}/${_dll}" "${_peDir}/${_dll}"
          _hit=$((_hit + 1))
          echo "已注入仿真器 DLL → lib/wine/aarch64-windows/${_dll}"
        fi
      done
      [[ "${_hit}" -ge 2 ]] || {
        echo "错误: 仿真器 DLL 注入异常（仅 ${_hit}/3 个），x86_64/i386 程序将无法运行"
        return 1
      }
      # v3.2.4：split 布局（wine <10，无 ARM64X 合并）双目录注入 —— EC 进程
      # 的 DLL 搜索首选 arm64ec-windows/（独立目录），仿真器 DLL 必须同时
      # 落两处（App 侧探测点仍是 aarch64-windows/libarm64ecfex.dll，不受影
      # 响）。目录不存在（ARM64X 树）自然跳过，零行为变化。
      local _ecDir="${_wineDir}/lib/wine/arm64ec-windows"
      if [[ -d "${_ecDir}" ]] && [[ ! -f "${_ecDir}/libarm64ecfex.dll" ]]; then
        for _dll in libarm64ecfex.dll libwow64fex.dll wowbox64.dll; do
          [[ -f "${_fexTmp}/${_dll}" ]] && cp -p "${_fexTmp}/${_dll}" "${_ecDir}/${_dll}"
        done
        echo "已同步注入仿真器 DLL → lib/wine/arm64ec-windows/（split 布局双目录）"
      fi
      echo "仿真器 DLL 注入完成（${_hit} 个）—— 运行期默认：HODLL64=libarm64ecfex.dll HODLL=libwow64fex.dll"
    fi
  fi

  # v3.2.0：wine 10.17+ 新安装布局适配（二次修复，v3.1.6 物化副本是错的）——
  # 上游把 loader 装进 lib/wine/<arch>-unix/（与 ntdll.so 同目录），loader
  # 的 main() 优先 try_dlopen(/proc/self/exe)：按【自身真实所在目录】找
  # dir/ntdll.so（loader/main.c： realpath_dirname → build_path(dir,
  # "ntdll.so")）。v3.1.6 曾把 lib 侧 loader【复制】成 bin/wine —— 副本
  # 的 /proc/self/exe 在 bin/，dlopen 找 bin/ntdll.so 必失败（设备实测
  # hangover-10.18 两形态："could not load ntdll.so: library .../bin/
  # ntdll.so not found"）。正确做法：bin/wine 用【相对符号链接】指回
  # lib loader —— /proc/self/exe 经内核解析符号链接回到 lib 目录，
  # ntdll.so 就在旁边；argv[0] 保留 bin/… 路径，loader 的
  # check_command_line() 按 argv[0] basename 分发不受影响（wine 官方
  # 符号链接机制同款语义）。App 侧 WineLayoutRepair.kt 对已装旧包自愈。
  if [[ -x "${_wineDir}/lib/wine/aarch64-unix/wine" ]]; then
    mkdir -p "${_wineDir}/bin"
    if [[ "$(readlink "${_wineDir}/bin/wine" 2>/dev/null)" != "../lib/wine/aarch64-unix/wine" ]]; then
      rm -f "${_wineDir}/bin/wine"
      ln -s "../lib/wine/aarch64-unix/wine" "${_wineDir}/bin/wine" \
        && echo "已建立 bin/wine → ../lib/wine/aarch64-unix/wine 符号链接（wine 10.17+ lib 布局；副本会让 loader 找 bin/ntdll.so 必败）"
    fi
    # v3.2.3：剔除 wine-preloader 入口 —— preloader_exec() 找不到入口时走
    # 官方兕底 execv(argv[1], argv+1) 直连 loader（HAVE_WINE_PRELOADER 未
    # 定义时的上游行为）。arm64ec 形态下首进程重执行链实测可达第二遍
    # __wine_main（设备日志 heap-tagging ×2），其后的静默阵亡以 fsync
    # futex_waitv 探测 SIGSYS(159) 为首嫌（disable_fsync_probe 已守卫），
    # 预加载器整链停用把这一风险类一并消除。App 侧 WineLayoutRepair.kt
    # 对已装旧包同步停用（免重装）。
    rm -f "${_wineDir}/bin/wine-preloader" "${_wineDir}/bin/wine64-preloader" \
           "${_wineDir}/bin/wine-preloader.linbox-disabled" \
           "${_wineDir}/bin/wine64-preloader.linbox-disabled"
  fi

  # bin 工具规整（v3.1.4；v3.2.0 符号链接化）：wine ≤9.18 / proton 树把
  # bin 工具装成 shell 脚本（exec wine <名>），wine 9.19+ 装成指向
  # wine loader 的符号链接。两种形态统一规整：
  #  - 10.17+ lib 布局（lib 侧 loader 存在）→ 符号链接到 bin/wine（两跳
  #    链接经 /proc/self/exe 全解析回 lib loader，argv[0] basename 分发
  #    不变）—— 绝不能复制（副本又会在 bin/ 找 ntdll.so）；
  #  - 旧布局（bin/wine 真实 ELF）→ 维持 v3.1.4 的副本策略（旧 loader
  #    按 bindir/../lib/wine/<arch>-unix/ 找 ntdll.so，副本位置无碍）。
  # 真实 ELF（wine/wineserver/winepath 等）不动。
  local _binDir="${_wineDir}/bin"
  local _libLayout=0
  [[ -x "${_wineDir}/lib/wine/aarch64-unix/wine" || -x "${_wineDir}/lib/wine/x86_64-unix/wine" ]] && _libLayout=1
  if [[ -d "${_binDir}" && -e "${_binDir}/wine" ]]; then
    local _tool _f
    for _tool in wineboot winecfg reg regedit regsvr32 msiexec rundll32; do
      _f="${_binDir}/${_tool}"
      [[ -e "${_f}" || -L "${_f}" ]] || continue
      if [[ "${_libLayout}" == "1" ]]; then
        if [[ "$(readlink "${_f}" 2>/dev/null)" != "wine" ]]; then
          rm -f "${_f}"
          ln -s "wine" "${_f}" 2>/dev/null || true
          echo "已规整 bin/${_tool}（→ wine 符号链接，10.17+ 布局）"
        fi
      elif [[ -L "${_f}" ]]; then
        rm -f "${_f}"
        cp -p "${_binDir}/wine" "${_f}" && chmod 755 "${_f}" 2>/dev/null || true
        echo "已规整 bin/${_tool}（符号链接 → wine loader 副本，旧布局）"
      elif head -c 2 "${_f}" 2>/dev/null | grep -q '#!'; then
        rm -f "${_f}"
        cp -p "${_binDir}/wine" "${_f}" && chmod 755 "${_f}" 2>/dev/null || true
        echo "已规整 bin/${_tool}（shell 脚本 → wine loader 副本，旧布局）"
      fi
    done
  fi
}
