wsDir="$(dirname $(readlink -f "$0"))"

archPkgs=(
  git
  wget
  7zip
  base-devel
  ccache
  cmake
  meson
  ninja
  bzip2
  xz
  zstd
  lzip
  lzop
  ncompress
  lz4
  unzip

  gobject-introspection

  gperf

  glslang

  python-pip

  bison
  flex

  pkgconf
  gettext
  freetype2

  glib2
  glib2-devel
)

debianPkgs=(
  build-essential
  git
  wget
  p7zip-full
  ccache
  cmake
  ninja-build
  bzip2
  xz-utils
  zstd
  lzip
  lzop
  ncompress
  lz4
  unzip
  gobject-introspection
  gperf
  glslang-tools
  python3-pip
  python3-yaml
  bison
  flex
  libtool
  autoconf
  automake
  pkgconf
  gettext
  # Ubuntu 24.04(noble) 起 autopoint 被拆为独立二进制包（同一 gettext 源码包
  # 产出，但 gettext 包内不再含 /usr/bin/autopoint）——只装 gettext 的话
  # autopoint 依然缺失。fontconfig 等包的 autogen.sh 无条件检查 autopoint
  # （且其 configure.ac 带 AM_GNU_GETTEXT，autoreconf -i 也会真调用它），
  # 缺它会在 fontconfig 处报 "You must have autopoint installed" 直接失败。
  autopoint
  libfreetype-dev
  locales
  # v3.2.27（用户问题：参考 yml 的 BASE_DEPS —— language-pack-zh-hans
  # 提供构建机中文翻译数据；Debian 无此包名，下方探测式安装）
  language-pack-zh-hans
  # 注意：glib 开发工具（glib-mkenums/gdbus-codegen/gio-querymodules）所在
  # 包名不写死在这里——它在不同发行版上包名不同，见下方安装前的动态探测。
  python3-setuptools
  python3-dev
  python3-mako
)

if [[ -f /etc/os-release ]]; then
  . /etc/os-release
  # ID 直接匹配；衍生发行版（manjaro/cachyos/endeavouros 等）按 ID_LIKE 兜底
  distro="${ID}"
  [[ "${ID_LIKE:-}" == *arch* ]] && distro="arch"
  [[ "${ID_LIKE:-}" == *debian* ]] && distro="debian"

  case "${distro}" in
  arch)
    echo "当前为archlinux"
    echo "需要安装 ${archPkgs[@]}"
    pacman-key --init
    pacman -Syyu --noconfirm || exit 1
    pacman -S --noconfirm --needed ${archPkgs[@]} || exit 1
    pip install PyYAML --break-system-packages || python3 -m pip install PyYAML --break-system-packages || true
    # v3.2.27：zh_CN locale 一并生成（对齐参考 yml 的中文语言环境步骤）
    sed -i 's/^#[[:space:]]*\(en_US.UTF-8\)/\1/;s/^#[[:space:]]*\(zh_CN.UTF-8\)/\1/' /etc/locale.gen && locale-gen
    ;;
  debian|ubuntu)
    echo "当前为 $ID"
    echo "交互式将取消,自动完成安装"
    export DEBIAN_FRONTEND=noninteractive
    apt update || exit 1
    # 不做全量 apt upgrade：CI runner 上会下载 300MB+ 更新并触发
    # firefox→snap 转换（连带下载 snapd/core24/mesa-2404/bare 等 snap），
    # 既拖慢构建又可能在受限环境失败；runner 镜像已预置可用工具链，
    # 本脚本只需补装下面的构建依赖即可。

    # glib 开发工具（glib-mkenums / gdbus-codegen / gio-querymodules）
    # 所在包名随发行版而异，写死任何一个都会在别的发行版上
    # "Unable to locate package" 直接失败：
    #   - Ubuntu 22.04/24.04(noble): libglib2.0-dev-bin（工具都在里面）
    #   - Debian trixie 及更新(glib>=2.84): 拆分为 libgio-2.0-dev-bin
    #     （此时 libglib2.0-dev-bin 只剩 gi-* 工具）
    # 因此改为安装前动态探测：存在哪个装哪个，两个都在就都装。
    GLIB_DEV_BIN_PKGS=()
    for cand in libglib2.0-dev-bin libgio-2.0-dev-bin; do
      if apt-cache show "${cand}" >/dev/null 2>&1; then
        GLIB_DEV_BIN_PKGS+=("${cand}")
        echo "检测到 glib 开发工具包: ${cand}"
      fi
    done
    if [[ ${#GLIB_DEV_BIN_PKGS[@]} -eq 0 ]]; then
      echo "错误: 当前 apt 源中既无 libglib2.0-dev-bin 也无 libgio-2.0-dev-bin!"
      exit 1
    fi

    echo "需要安装 ${debianPkgs[@]} ${GLIB_DEV_BIN_PKGS[@]}"
    apt install -y ${debianPkgs[@]} ${GLIB_DEV_BIN_PKGS[@]} || exit 1
    # meson 通过 pip 安装较新版本（GitHub Actions 的 ubuntu-24.04 自带
    # meson 1.3.x，部分包如新版 mesa/xorgproto 需要更高版本）
    # 注意：
    # 1. ubuntu-24.04 无 `pip` 命令（只有 python3 -m pip），旧脚本用
    #    `pip` 会静默失败 → meson 未安装 → 工作流里 `meson --version` 报 127
    # 2. 绝不能 `--upgrade pip`：apt 安装的 pip 无 RECORD 文件，升级时
    #    卸载会报 "Cannot uninstall pip ... RECORD file not found" 而整条
    #    命令失败，连带 mako 也装不上（mesa 构建必需 mako）
    # 3. 必须用 --ignore-installed：直接装新副本、不卸载 apt 管理的
    #    PyYAML/meson/mako，绕开 "Cannot uninstall PyYAML 6.0.1,
    #    RECORD file not found. Hint: The package was installed by debian."
    python3 -m pip install --break-system-packages --ignore-installed mako PyYAML \
      || { echo "mako/PyYAML 安装失败!" && exit 1; }
    python3 -m pip install --break-system-packages --ignore-installed meson ninja \
      || python3 -m pip install --ignore-installed meson ninja \
      || { echo "meson/ninja 安装失败!" && exit 1; }
    hash -r
    command -v meson >/dev/null 2>&1 || export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"
    hash -r
    command -v meson >/dev/null 2>&1 || { echo "meson 仍不可用!" && exit 1; }
    echo "meson 版本: $(meson --version)"
    echo "ninja 版本: $(ninja --version)"
    # mesa 的 meson 构建在配置与编译期都会 import mako，必须显式验证
    python3 -c "import mako" 2>/dev/null \
      || { echo "python3 mako 模块不可用!（mesa 构建必需）" && exit 1; }
    echo "mako 版本: $(python3 -c 'import mako; print(mako.__version__)')"
    command -v libtoolize >/dev/null 2>&1 \
      || { echo "libtoolize 不可用!（libexpat 等包 autoreconf 必需）" && exit 1; }
    libtoolize --version | head -1
    # libtool 二进制兜底：部分上游 autogen.sh（如 flac，虽然当前已改走 CMake）
    # 会 `command -v libtool`。此前出现过 libtoolize 可用但 libtool 缺失、
    # 构建 40 分钟到后面的包才失败的坑，这里显式检查并尝试修复。
    if ! command -v libtool >/dev/null 2>&1; then
      echo "警告: libtool 二进制缺失，尝试显式重装..."
      apt-get install -y --reinstall libtool || true
      command -v libtool >/dev/null 2>&1 \
        || echo "警告: libtool 仍不可用（当前配方链已不依赖；若新增 autogen 包需注意）"
    fi
    command -v libtool >/dev/null 2>&1 && libtool --version | head -1 || true
    # v3.2.27（用户问题："locales language-pack-zh-hans 这两个依赖没有，
    # 所以一直没有中文"，对齐参考 yml 的"设置中文语言环境"步骤）：
    # language-pack-zh-hans 是 Ubuntu 专属包名（Debian 无此包），
    # 探测存在才装，避免非 Ubuntu 环境直接失败；随后生成 zh_CN.UTF-8
    # locale —— wine 的 --enable-nls 构建（msgfmt 编译 po/zh_CN.po →
    # share/locale/zh_CN/LC_MESSAGES/wine.mo）在中文 locale 环境下
    # 产出完整翻译，rootfs-locale 的 localedef 也依赖 locales 包
    # （已在 debianPkgs）。任一步失败不阻断（仅影响中文）。
    if apt-cache show language-pack-zh-hans >/dev/null 2>&1; then
      apt install -y language-pack-zh-hans \
        || echo "警告: language-pack-zh-hans 安装失败（继续，仅影响构建机中文翻译数据）"
    else
      echo "提示: 当前源无 language-pack-zh-hans（非 Ubuntu？）—— 跳过"
    fi
    locale-gen zh_CN.UTF-8 2>/dev/null \
      || { sed -i 's/^#[[:space:]]*\(zh_CN.UTF-8\)/\1/' /etc/locale.gen && locale-gen; } \
      || echo "警告: locale-gen zh_CN.UTF-8 失败（继续）"
    update-locale LANG=zh_CN.UTF-8 2>/dev/null || true
    echo "构建机 locale 就绪: $(locale -a 2>/dev/null | grep -i zh_cn | head -1 || echo 'zh_CN 未生成')"
    sed -i 's/^#[[:space:]]*\(en_US.UTF-8\)/\1/' /etc/locale.gen && locale-gen
    ;;
  *)
    echo "不支持的发行版 => ${ID:-未知}"
    exit 1
    ;;
  esac

  # ============================================================
  # bionic-box64 走 NDK clang（与 rootfs 其它包同链），宿主无需
  # 任何交叉 glibc 工具链 / patchelf —— v3.2.5 的 gcc-aarch64-
  # linux-gnu + patchelf 段已随 glibc 方案一并移除。
  # ============================================================
  echo "工具链就绪: NDK 交叉编译（bionic），宿主仅通用构建工具"
else
  echo "不支持的发行版 => ${ID:-未知}"
  exit 1
fi
