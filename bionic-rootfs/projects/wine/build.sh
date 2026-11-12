revision="${LINBOX_WINE_TAG:-hangover-11.16}"
url="${LINBOX_WINE_URL:-https://github.com/AndreRH/wine/archive/refs/tags/hangover-11.16.tar.gz}"
urlType="tar"
arch="x86_64"
buildSys="autotools"
license="LGPL-2.1"
doNotApplyPatch=1

# ============================================================
# LinBox bionic Wine（x86_64 / X11 / WoW64）
# ------------------------------------------------------------
# 与 hangover-wine（aarch64 + qemu）互补的第二个 Wine 运行时：
#   * 目标架构 x86_64（bionic），PE 侧 i386+x86_64（新 WoW64）
#   * aarch64 设备上由 box64 加载运行（上游项目 同款模型：
#     `box64 $PREFIX/opt/wine/bin/wine ...`）
#   * x86_64 设备上原生运行，无需模拟器
#   * 显示：仅 X11（--with-x 全套 X 扩展；无 wayland）
#     X 服务为 LinBox 内置 termux-x11/libXlorie（App 侧
#     tmp/.X11-unix/Xn，默认 :1），rootfs 内 libxcb/xtrans
#     已按 @APP_PREFIX@ 打好 socket 路径补丁
#   * 音频：PulseAudio（PULSE_SERVER=127.0.0.1:4713，由
#     linbox-x11 会话拉起；rootfs 亦自带 pulseaudio）
# 源码支持三种来源（CI 下拉选择，亦可任意环境变量覆盖）：
#   hangover  AndreRH/wine        tag    hangover-<ver>   （默认，与 arm64ec 配方同源）
#   official  wine-mirror/wine    tag    wine-<ver>       （WineHQ 官方源码 GitHub 镜像）
#   proton    ValveSoftware/wine  branch proton_<ver>     （Valve Proton 的 wine 源码树）
# 本目录的 bionic 补丁针对 hangover 树生成；official/proton 树结构相同
# （均为上游 wine 树），个别 hunk 可能因版本漂移失败——pre_setup 对此
# 容错（打印告警继续构建），失败 hunk 落 *.rej 供人工核查。
# 可用环境变量覆盖：
#   LINBOX_WINE_URL   源码 tarball 直链（三种来源皆用 GitHub archive）
#   LINBOX_WINE_TAG   版本标识（进包名 wine-<TAG>-x86_64.tar）
#   LINBOX_WINE_LABEL 运行时标识（写入 .linbox-wine-info 的 tag=，缺省用 TAG）
# ============================================================

args="
  ac_cv_header_linux_userfaultfd_h=no
  ac_cv_path_GRADLE=no
  --prefix=$prefix/opt/wine
  --exec-prefix=$prefix/opt/wine
  --libdir=$prefix/opt/wine/lib
  --includedir=$prefix/opt/wine/include
  --enable-archs=i386,x86_64
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
  --x-libraries=$prefix/lib
  --x-includes=$prefix/include
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

# 构建依赖全部来自 rootfs 既有配方（双架构可用，缺什么 build 系统
# 依据 toposort 自动补齐）：
# ※ gstreamer 必须先开启（--with-gstreamer）：wine 的 winegstreamer
#   后端依赖它做 WMF/编解码转发，proton 树还额外要求 gstreamer-gl-1.0
#   （由 gst-plugins-base gl=enabled 提供，见 gstreamer 配方注释）。
# ※ alsa：与 arm64ec 形态保持一致（winealsa unixlib 参与构建；
#   CI 产物校验两形态都要求 winealsa.so 存在）。
# ※ v3.4.7 新增 alsa-plugins（pulse 桥 + /etc/asound.conf 全局默认）：
#   winealsa 在 Android 的唯一现实出口 —— /dev/snd 不存在，winealsa
#   → libasound → pulse 插件 → rootfs 守护 → sles-sink（用户实证
#   "nopulse 用 alsa 但 alsa 没补全依赖"的缺口补全）。
deps="pthread-stub alsa-lib alsa-plugins fontconfig freetype gnutls gstreamer ffmpeg pulseaudio mesa vulkan-headers vulkan-icd-loader xkeyboard-config libxkbcommon xorgproto libxcb xtrans libX11 libXext libXrender libXfixes libXi libXrandr libXcursor libXinerama libXcomposite libXxf86vm libandroid-shmem"

# 静默编译：wine 的 make 会回显每条完整编译/链接命令（ccache 全路径），
# 单包就曾刷出 11.7 万行日志；-s 只关命令回显，编译器/链接器报错照常输出，
# 失败时仍能从 "make: *** [Makefile:...] Error 1" 定位到目标。
makeSilent=1

# PE 交叉编译器：llvm-mingw（与 hangover-wine 同版本， proven）
llvmMingwVersion="21"
llvmMingwDate="20250319"
llvmMingwUrl="https://github.com/mstorsjo/llvm-mingw/releases/download/${llvmMingwDate}/llvm-mingw-${llvmMingwDate}-ucrt-ubuntu-20.04-x86_64.tar.xz"
llvmMingwSha256="ab2a1489416fa82b3e85e88cb877053ee8a591993408caf076737d8de5ae72ca"

pre_setup() {
  local _hostTools="${wsDir}/host_tools/wine-tools-${revision}"
  local _llvmMingwDir="${wsDir}/tmp/llvm-mingw-${llvmMingwVersion}"

  echo "=== LinBox bionic wine（x86_64/X11/WoW64）构建开始 ==="

  # 0. 源码树兼容性：git 树可能不含预生成 configure（如 proton 分支）→ autogen 生成
  if [[ ! -f "${srcDir}/${pjName}/configure" ]]; then
    if [[ -f "${srcDir}/${pjName}/autogen.sh" ]]; then
      echo "源码树无预生成 configure，运行 autogen.sh ..."
      ( cd "${srcDir}/${pjName}" && ./autogen.sh ) || { echo "autogen 失败"; return 1; }
    else
      echo "错误: 源码树既无 configure 也无 autogen.sh"
      return 1
    fi
  fi

  # 1. 下载并设置 llvm-mingw 工具链（i686/x86_64 PE 目标）
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

  # 2. host 构建补丁（winegcc/winebuild 目标修正，bionic 树必需）
  #    --forward 跳过已应用的 hunk；失败仅告警不中断（official/proton 树
  #    可能已含等价修正），失败 hunk 落 *.rej。
  for _patch in "${wsDir}/projects/${pjName}"/*.patch.beforehostbuild; do
    if [[ -f "${_patch}" ]]; then
      echo "应用 host build 补丁: $(basename "${_patch}")"
      if ! sed "s%@TERMUX_PREFIX@%${prefix}%g" "${_patch}" | patch --silent --forward -p1; then
        echo "⚠️  host 补丁 $(basename "${_patch}") 有 hunk 未应用（树差异，继续构建）"
      fi
    fi
  done

  # 3. 宿主 wine 工具链（winebuild/wrc/widl/nls 皆为构建机本地产物）
  #    --enable-win64 --without-x：工具与 X11/bionic 无关，最小化依赖
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
    echo "host nls build 失败"
    return 1
  }

  cd ..

  # 4. 主构建：bionic 交叉编译（Unix 侧 x86_64-android，PE 侧 i386/x86_64）
  echo "=== 主构建: 配置 wine（--with-x X11，无 wayland） ==="
  load_env "$targetArch"
  cd "${srcDir}/${pjName}"

  # bionic 兼容补丁（路径/互斥锁属性/Socket IPX，与 hangover-wine 同源）
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

  # v3.3.20（用户问题 3）：wine.inf 垃圾 AppDefaults 注册表剔除（与
  # hangover-wine 配方同款）—— proton 树的 loader/wine.inf.in 带 31 条
  # Valve/Steam 游戏兼容 hack（ActOfWar/BlackOps3/DIRT5/ffxvi/RDR2 等的
  # atiadlxx=builtin、winxp/win7 Version、xinput/d3d8/vulkan-1 覆盖、
  # SpeechSynth 禁用），wineboot 建前缀时全部打进 user.reg。官方
  # wine-11.0 / hangover-11.16 树零 AppDefaults 行（实测核对），剔除后
  # 与官方树一致、无功能损失。幂等：官方/hangover 树无匹配行 = no-op。
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
    if grep -q '^HKCU,Software\\Wine\\AppDefaults\\' "$_inf"; then
      echo "::error::wine.inf AppDefaults 剔除未生效（仍有残留行）！"
      return 1
    fi
    return 0
  }
  strip_wineinf_appdefaults

  # v3.3.20（用户问题 1）：proton_11.0 树 M_PERTURB bionic 兼容（与
  # hangover-wine 配方同款）。CI proton_11.0 x86_64 阵亡于：
  #   dlls/ntdll/unix/loader.c:2454:14: error: use of undeclared identifier 'M_PERTURB'
  # M_PERTURB 为 glibc malloc.h 专属，bionic 无 → 两处 mallopt 调用各包
  # #ifdef M_PERTURB 守卫（glibc 行为不变，bionic 跳过内存填充加固）。
  # 幂等：旧树/官方树无此调用 = no-op。
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
  # —— 与 hangover-wine 配方同款（MFCreateDXGIDeviceManager 的
  # WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1 stub + bytestream handler
  # 回退），按版本分档（内容锚点脚本，任何树通吃且幂等）：
  #   >= 9.10（含 10.x/11.x）→ fix_wine9.2_mfplat.sh MPEG4 档
  #                             （CLSID_MPEG4ByteStreamHandlerPlugin，
  #                              wine_do_not_create_dxgi_manager2.patch
  #                              的等价实现）；
  #   <  9.10（9.2 等）      → fix_wine9.2_mfplat.sh AVI 档
  #                             （CLSID_AVIByteStreamHandler）。
  # revision 剥前缀解析：hangover-11.16 / official-9.2 / proton-10.0 /
  # custom-10.18 / 裸 9.2 均可（CI 三源 + custom 通用）。
  # 硬校验：env 检查串必须在 dlls/mfplat/main.c，防脚本静默落空。
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
    if ! grep -q 'WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER' dlls/mfplat/main.c 2>/dev/null; then
      echo "::error::mfplat 补丁未生效（main.c 无 WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER 检查）！"
      echo "   请核查 projects/wine/fix_wine9.2_mfplat.sh 的锚点（MFCreateDXGIDeviceManager 签名漂移？）"
      return 1
    fi
    echo "mfplat 补丁已生效（WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1 可 stub DX manager；bytestream handler 已回退内置）"
  else
    echo "ℹ️ 源码树无 dlls/mfplat/main.c，跳过 mf 补丁"
  fi

  # v3.3.7：noexec 回退 + locale 补丁生效【硬校验】（与 hangover-wine
  # 同款）。0011/0011b/0011c 三份按树形态互斥命中（9.x/10.x/11.x 的
  # map_file_into_view 结构各不相同），校验最终源码必须带
  # "LinBox-noexec-fallback" 标记 —— 全落空 = 产出 noexec 上 media
  # 报错的裸奔包。locale 侧 0009 自 v3.3.2 存在，此处兜底校验
  # "LinBox-locale-lc-all" 标记（v3.3.7 起带运行期检测锚点）。
  if [[ -f dlls/ntdll/unix/virtual.c ]] \
     && ! grep -q 'LinBox-noexec-fallback' dlls/ntdll/unix/virtual.c 2>/dev/null; then
    echo "::error::noexec 回退补丁未生效（virtual.c 无 LinBox-noexec-fallback 标记）！"
    echo "   该 wine 包在 noexec 存储（/storage/emulated/0）上会复现 \"Could not load required media\""
    echo "   三份补丁按树形态互斥命中其一：0011(9.x) / 0011b(10.x) / 0011c(11.x)"
    echo "   请核对源码树版本形态与补丁锚点是否匹配（*.rej 落在源码树内可查）"
    return 1
  fi
  echo "noexec 回退补丁已生效（LinBox-noexec-fallback 标记在 virtual.c）"
  if [[ -f dlls/ntdll/unix/env.c ]] \
     && ! grep -q 'LinBox-locale-lc-all' dlls/ntdll/unix/env.c 2>/dev/null; then
    echo "::error::locale 补丁未生效（env.c 无 LinBox-locale-lc-all 标记）！"
    echo "   该 wine 包在 bionic 上界面恒英文、ACP 恒 1252（中文环境丢失）"
    echo "   请核查补丁锚点：projects/wine/0009-android-locale-lc-all.patch"
    return 1
  fi
  echo "locale 补丁已生效（LinBox-locale-lc-all 标记在 env.c，LC_ALL=zh_CN.UTF-8 → LCID 0x0804 / ACP 936）"

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

  # v3.2.24（用户问题 4）：wine ≤9.x 树 -mabi=ms 剥除 —— x86_64 形态
  # wine-9.7 CI 实证炸点根治：
  #   clang: error: unsupported option '-mabi=' for target
  #          'x86_64-unknown-linux-android28'
  #   make: *** [Makefile:...: dlls/dinput/data_formats.o] Error 1
  # 【根因】wine 9.x configure.ac（MSVCRTFLAGS 段）对 HOST_ARCH=x86_64
  # 的非 mingw 宿主**无条件**执行
  #     MSVCRTFLAGS="$MSVCRTFLAGS -mabi=ms"
  # —— if test -z "$PE_ARCHS" 只包裹“能力检测 + 报错”，flag 本身照加
  # （上游假设 x86_64 unix 编译器是 GCC；GCC 支持 -mabi=ms，clang 全系
  # 不支持该选项）。我们 --enable-archs=i386,x86_64 + --with-mingw →
  # PE_ARCHS 非空 → 检测被跳过 → flag 静默进入 MSVCRTFLAGS → make
  # install 阶段编译 unix 侧 implib 对象（#pragma makedep implib 的
  # data_formats.c 等 13 个文件，makedep.c 仅对 arch=0 且 use_msvcrt
  # 的目标附加 msvcrt_flags）时传给 NDK clang → 必炸。
  # 【修法】configure 运行【前】sed 掉 configure 里这行赋值（tarball 自
  # 带 configure；pre_setup 先于框架 ./configure 执行，时机正确）。
  # 【安全性】MSVCRTFLAGS 只作用于 unix 侧 arch=0 的模块对象编译
  # （makedep.c 3233 行，arch>0 的 PE 侧不吃它）；受影响的 13 个
  # implib 文件全部是纯数据/入口表（dinput/data_formats.c 无任何函数
  # 定义，msvcrt 系列 CRT 数学/入口对象在 unix .a 中无人调用），
  # sysv/ms ABI 差异只影响函数调用约定 —— 数据零影响。wine 10+ 树
  # 的 configure 无此行（上游已随 arm64ec 导出重构一并移除），
  # sed 不命中即 no-op，幂等且无副作用。
  strip_mabi_ms_for_clang() {
    local _cfg="${srcDir}/${pjName}/configure"
    [[ -f "$_cfg" ]] || return 0
    if grep -q 'MSVCRTFLAGS="\$MSVCRTFLAGS -mabi=ms"' "$_cfg"; then
      sed -i 's/MSVCRTFLAGS="\$MSVCRTFLAGS -mabi=ms"/MSVCRTFLAGS="\$MSVCRTFLAGS"/' "$_cfg" \
        && echo "已剥除 wine ≤9.x configure 的 MSVCRTFLAGS -mabi=ms（NDK clang 不支持；unix 侧 implib 对象为纯数据，ABI 无影响）"
    else
      echo "ℹ️ configure 无 -mabi=ms 赋值行（wine 10+ 树），跳过剥除"
    fi
    # aclocal.m4 同步（autoreconf 路径防回归；缺文件/无匹配均 no-op）
    local _acm="${srcDir}/${pjName}/aclocal.m4"
    if [[ -f "$_acm" ]] && grep -q 'MSVCRTFLAGS="\$MSVCRTFLAGS -mabi=ms"' "$_acm"; then
      sed -i 's/MSVCRTFLAGS="\$MSVCRTFLAGS -mabi=ms"/MSVCRTFLAGS="\$MSVCRTFLAGS"/' "$_acm" \
        && echo "已剥除 aclocal.m4 的同款 -mabi=ms 赋值（autoreconf 路径）"
    fi
    return 0
  }
  strip_mabi_ms_for_clang

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
  # CI 实测：hangover-10.18 树 x86_64 形态 dlls/ntdll/unix/unix_private.h
  # 的 ldt_bitmap 路径（660 行 ffs(...)）折在 loadorder.o；11.x/proton
  # 树无 ffs 用法或已含 strings.h，自动跳过。幂等。
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
  #   （设备实测：wineboot -i 退出码 159 = 128+31，前缀建不出来，
  #   wineserver 同款代码同样阵亡；WINEFSYNC=0 也无效，因为探测在
  #   读环境变量之前）。修法：bionic 目标（__ANDROID__）硬禁用 fsync
  #   —— 在 Android 上即便内核支持 futex_waitv，seccomp 也封了它，
  #   硬禁用是唯一正确行为。桌面 Linux 构建不受影响。幂等：标记
  #   已存在则跳过。
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

  # v3.2.8：userfaultfd Android 硬禁用 —— wine 10 系树（proton_10.0/
  # official-10.x）virtual_init() 无条件调 kernel_writewatch_init() →
  # 裸 syscall(__NR_userfaultfd)（x86_64 nr 323 / aarch64 nr 374）。
  # Android 应用 seccomp 白名单不含 userfaultfd → SIGSYS(31) → 退出码
  # 159 —— x86_64 形态下 wine 经 box64 仿真执行，该 libc 调用照常落到
  # 真内核（box64 不拦截 libc syscall）→ 整个 box64 进程被击杀。
  # wine 10 树运行期开关 WINE_DISABLE_KERNEL_WRITEWATCH=1 已由 App 侧
  # （WineEnvironment）与 linbox-wine 脚本默认导出兜底旧包；此处构建期
  # 注入 __ANDROID__ 早退守卫根治新包（wine 11 树自带编译期守卫自动跳过；
  # wine ≤9.x 无此代码自动跳过）。与 hangover-wine 配方同款，幂等。
  disable_userfaultfd_android() {
    local _f="dlls/ntdll/unix/virtual.c"
    [[ -f "$_f" ]] || { echo "ℹ️ 无 $_f（树结构差异），跳过 userfaultfd 守卫"; return 0; }
    if grep -q "LINBOX_FIX_UFFD_ANDROID" "$_f"; then
      echo "userfaultfd Android 守卫已注入过，跳过 => $_f"
      return 0
    fi
    if grep -q '!defined(__ANDROID__)' "$_f" && grep -q 'userfaultfd' "$_f"; then
      echo "源码树已带 userfaultfd __ANDROID__ 编译期守卫（wine 11+），跳过 => $_f"
      return 0
    fi
    if ! grep -q 'syscall( __NR_userfaultfd' "$_f"; then
      echo "ℹ️ $_f 无 userfaultfd 调用（wine ≤9.x/已重构），跳过守卫注入"
      return 0
    fi
    if grep -q 'struct uffdio_api uffdio_api;' "$_f"; then
      cat > ".linbox-uffd-guard.h" <<'UFFDGUARD'
#if defined(__ANDROID__)
    /* LINBOX_FIX_UFFD_ANDROID: userfaultfd (x86_64 nr 323 / aarch64 nr 374)
     * is not in the Android app seccomp allowlist -> SECCOMP_RET_TRAP ->
     * SIGSYS (signal 31) kills the process with exit code 159 during
     * virtual_init(). Wine 11+ guards this with !defined(__ANDROID__) at
     * compile time; wine 10 trees do not. Under box64 emulation (x86_64
     * flavor) the libc syscall still reaches the real kernel and kills the
     * whole box64 process. Write watches fall back to the userspace
     * implementation - zero loss on Android. */
    uffd_fd = -1;
    return;
#endif
UFFDGUARD
      sed -i '/struct uffdio_api uffdio_api;/r .linbox-uffd-guard.h' "$_f" \
        && echo "已注入 userfaultfd Android 守卫（159/SIGSYS 根治）=> $_f"
      rm -f ".linbox-uffd-guard.h"
    else
      sed -i 's#^    uffd_fd = syscall( __NR_userfaultfd.*#    uffd_fd = -1; /* LINBOX_FIX_UFFD_ANDROID: userfaultfd blocked by Android app seccomp (SIGSYS/159) */#' "$_f"
      if grep -q "LINBOX_FIX_UFFD_ANDROID" "$_f"; then
        echo "已改写 userfaultfd 调用为软路径（159/SIGSYS 根治，锚点漂移路径）=> $_f"
      else
        echo "⚠️ userfaultfd 守卫未命中锚点（树结构差异）—— 若运行期仍 159 请核查 $_f"
      fi
    fi
  }
  disable_userfaultfd_android

  # v3.2.3：wine-preloader Android 全面禁用 —— proton x86_64 + box64
  # 前缀构建"零输出静默阵亡"的根治之一（另一根因 = Valve 树 start_main_thread
  # 无条件调 fsync_init → do_fsync 的 futex_waitv 裸探测 → seccomp
  # SIGSYS(159)，已由 disable_fsync_probe 守卫；App 侧 WineProbePatch.kt
  # 对已装旧包就地改写探测立即数，免重编）。
  # 本形态（x86_64）的关键点：bin/wine 经 box64 仿真执行，__wine_main 的
  # pre_exec() → execv(wine-preloader) 后预加载器用裸 syscall 自行映射
  # bin/wine 及其 ELF 解释器再跳入 —— box64 仿真下该交接跑不通（设备日志
  # 连预加载器的 "_r_debug not found in ld.so" 告警都没有 = 死在进入前后），
  # 进程无任何输出阵亡。禁用后首进程与子进程全部直接 exec wine loader
  # （box64 对 execve 的 x86_64 再入即 上游项目 模型常规路径）。
  # pre_package 侧同步剔除 bin/*-preloader 入口（旧包由 App 侧停用兜底）。
  # 幂等；锚点缺失（树结构漂移）时告警跳过不中断。
  disable_preloader_android() {
    local _f="dlls/ntdll/unix/loader.c"
    [[ -f "$_f" ]] || { echo "⚠️ 无 $_f（树结构差异），跳过预加载器禁用"; return 0; }
    if grep -q "LINBOX_FIX_PRELOADER_ANDROID" "$_f"; then
      echo "预加载器 Android 禁用已注入过，跳过 => $_f"
      return 0
    fi
    # 1) 首个进程：pre_exec 返回 0（x86_64 Linux 走 #else 通用分支）
    if grep -q 'return 1;  /\* we have a preloader \*/' "$_f"; then
      sed -i 's|return 1;  /\* we have a preloader \*/|return 0;  /* LINBOX_FIX_PRELOADER_ANDROID: preloader handoff cannot run under box64 emulation (x86_64 silent death) nor bionic linker64 (arm64ec silent hang); align with hangover/wine-11 no-preloader behavior — virtual_init reserves address space itself */|' "$_f" \
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


  # v3.3.9：proton_9.0 树 d3dx11_42 转发导入修复（x86_64 形态 CI 实测炸点：
  # lld-link: could not open 'd3dx11_43.lib' —— makedep 的 IMPORTS 解析回退
  # -ld3dx11_43，PE 链接器找不到；Valve 官方构建靠 mingw-w64 系统 d3dx
  # 导入库，本构建无）。修法与 proton_10.0 官方一致（IMPORTS 引用改为
  # d3dx11_43 的 IMPORTLIB 名 d3dx11，链接改走 wine 自产
  # dlls/d3dx11_43/<arch>-windows/libd3dx11.a 显式路径）。.patch.proton
  # 后缀避开主补丁循环；仅当 Makefile.in 呈 proton_9.0 转发形态
  # （IMPORTS = d3dcompiler d3dx11_43）时套用 —— official/hangover 树
  # 是 PARENTSRC 完整构建形态、proton_10.0+ 已自带修复，均不命中。
  # --fuzz=0：锚点逐字命中，杜绝跨树误插。硬校验：应用后必须不含
  # d3dx11_43 导入引用（防 hunk 漂移静默落空）。
  if [[ -f dlls/d3dx11_42/Makefile.in ]] \
     && grep -q '^IMPORTS = d3dcompiler d3dx11_43$' dlls/d3dx11_42/Makefile.in; then
    echo "应用 d3dx11_42 转发导入修复（proton_9.0 树：IMPORTS 改用 d3dx11 导入库）"
    if patch --silent --forward -p1 --fuzz=0 \
         < "${wsDir}/projects/${pjName}/0012-fix-d3dx11-42-importlib-proton.patch.proton"; then
      echo "已修复 d3dx11_42 导入（链接将使用 wine 自产 libd3dx11.a，与 proton_10.0 官方形态一致）"
    else
      echo "⚠️ d3dx11_42 导入修复补丁未应用（树差异，继续构建）"
    fi
    if grep -q '^IMPORTS = d3dcompiler d3dx11_43$' dlls/d3dx11_42/Makefile.in; then
      echo "::error::d3dx11_42 导入修复未生效（Makefile.in 仍引用 d3dx11_43）！"
      echo "   后果: i386/x86_64 PE 链接必报 lld-link: could not open 'd3dx11_43.lib'"
      echo "   请核查 projects/${pjName}/0012-fix-d3dx11-42-importlib-proton.patch.proton 锚点"
      return 1
    fi
  else
    echo "ℹ️ d3dx11_42 无需导入修复（非 proton_9.0 转发形态或已自带），跳过"
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

  # v3.2.27（用户问题 4 收尾）：PE 交叉编译器一律指向 llvm-mingw 的
  # 包装器 clang（自带 --target=i*86-w64-mingw32 / x86_64-w64-mingw32）。
  # 旧版 export i386_CC="$CC"（NDK clang）在 wine ≤9.x 旧 configure 下
  # 会被当 PE 编译器使用（env 预置使 AC_CHECK_PROGS 探测被跳过）：
  # NDK clang 目标是 *-linux-android，编不出 PE 对象。wine 10+/hangover
  # 树的 configure 由 --with-mingw=<dir>/clang 统一覆盖本导出（无害）；
  # wine 9.x 树（旧交叉逻辑）则实际消费这两个变量 —— 指向 llvm-mingw
  # 包装器后 i386/x86_64 PE 侧才能真正按 mingw 目标编译。
  export i386_CC="${_llvmMingwDir}/bin/i686-w64-mingw32-clang"
  export x86_64_CC="${_llvmMingwDir}/bin/x86_64-w64-mingw32-clang"

  export CROSSCFLAGS="-O3 -pipe"
  export CROSSLDFLAGS="-s"

  args+=" --with-wine-tools=wine-tools-build --with-mingw=${_llvmMingwDir}/bin/clang"
}

pre_package() {
  # 注意：不可定义 install() —— build 系统一旦发现 install() 就会
  # 跳过 `make install`（hangover-wine 亦如此，靠 configure 的
  # --prefix=$prefix/opt/wine + DESTDIR 落盘）。此处仅补版本戳，
  # 供 linbox-container 识别 wine 运行时。
  local _wineDir="${destDir}${prefix}/opt/wine"
  if [[ -d "${_wineDir}" ]]; then
    cat > "${_wineDir}/.linbox-wine-info" << EOF
kind=x86_64-wow64-x11
tag=${LINBOX_WINE_LABEL:-${revision}}
backend=box64|native
EOF
  else
    echo "警告: 未找到 wine 安装目录 ${_wineDir}，可能 make install 失败"
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

  # v3.2.0：wine 10.17+ 新安装布局适配（二次修复，v3.1.6 物化副本是错的）——
  # 上游把 loader 装进 lib/wine/x86_64-unix/（与 ntdll.so 同目录），
  # loader 的 main() 优先 try_dlopen(/proc/self/exe)：按【自身真实所在
  # 目录】找 dir/ntdll.so（loader/main.c：realpath_dirname →
  # build_path(dir,"ntdll.so")）。v3.1.6 把 lib 侧 loader【复制】成
  # bin/wine —— 副本的 /proc/self/exe 在 bin/，box64 形态设备实测直接
  # 报 "could not load ntdll.so: Cannot dlopen(.../bin/ntdll.so...)"
  # （hangover-10.18 x86_64 形态两份日志实锤）。正确做法：bin/wine 用
  # 【相对符号链接】指回 lib loader —— /proc/self/exe 经内核解析符号
  # 链接回到 lib 目录，ntdll.so 就在旁边；argv[0] 保留 bin/… 路径，
  # loader 的 check_command_line() 按 argv[0] basename 分发不受影响。
  # App 侧 WineLayoutRepair.kt 对已装旧包自愈（免重装）。
  if [[ -x "${_wineDir}/lib/wine/x86_64-unix/wine" ]]; then
    mkdir -p "${_wineDir}/bin"
    if [[ "$(readlink "${_wineDir}/bin/wine" 2>/dev/null)" != "../lib/wine/x86_64-unix/wine" ]]; then
      rm -f "${_wineDir}/bin/wine"
      ln -s "../lib/wine/x86_64-unix/wine" "${_wineDir}/bin/wine" \
        && echo "已建立 bin/wine → ../lib/wine/x86_64-unix/wine 符号链接（wine 10.17+ lib 布局；副本会让 loader 找 bin/ntdll.so 必败）"
    fi
    # v3.2.3：剔除 wine-preloader 入口（bin/ 真实 ELF 与符号链接一并）——
    # preloader_exec() 找不到入口时走官方兕底 execv(argv[1], argv+1) 直连
    # loader（HAVE_WINE_PRELOADER 未定义时的上游行为）；box64 仿真下预加载
    # 器交接是 x86_64 前缀构建"零输出静默阵亡"的高发点（设备日志无
    # "_r_debug not found in ld.so" = 死在预加载器进入前后）。App 侧
    # WineLayoutRepair.kt 对已装旧包同步停用（免重装）。
    rm -f "${_wineDir}/bin/wine-preloader" "${_wineDir}/bin/wine64-preloader" \
           "${_wineDir}/bin/wine-preloader.linbox-disabled" \
           "${_wineDir}/bin/wine64-preloader.linbox-disabled"
  fi

  # bin 工具规整（v3.1.4；v3.2.0 符号链接化）：
  #  - 10.17+ lib 布局（lib 侧 loader 存在）→ 符号链接到 bin/wine ——
  #    绝不能复制（副本又会在 bin/ 找 ntdll.so）；两跳链接经
  #    /proc/self/exe 全解析回 lib loader，argv[0] basename 分发不变；
  #  - 旧布局（bin/wine 真实 ELF）→ 维持 v3.1.4 副本策略（脚本/符号
  #    链接在 box64 直跑下不可靠，见下；旧 loader 按
  #    bindir/../lib/wine/<arch>-unix/ 找 ntdll.so，副本位置无碍）。
  # 真实 ELF（wine/wineserver/winepath 等）不动。
  local _binDir="${_wineDir}/bin"
  local _libLayout=0
  [[ -x "${_wineDir}/lib/wine/x86_64-unix/wine" || -x "${_wineDir}/lib/wine/aarch64-unix/wine" ]] && _libLayout=1
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

