revision="2026-v1"
urlType="others"
arch="aarch64 x86_64"
buildSys="others"
license="OFL-1.1"
pkgSrcDir="${wsDir}/projects/rootfs-fonts"

# ============================================================
# rootfs 系统字体（usr/share/fonts）
# ------------------------------------------------------------
# 此前 rootfs 无任何系统字体：fontconfig 扫描不到字体（wine 只能
# 用自带的 share/wine/fonts，其他 Linux 程序直接无字可渲染）。
# 这里补两套常用字体：
#   * DejaVu 2.37 全量 TTF   —— 西文/等宽基础字体（自由许可）
#   * Noto Sans SC 可变字体 —— 中日韩简体中文覆盖（Google Fonts）
# 安装位置 $prefix/share/fonts，fontconfig 的 --with-default-fonts
# 即指向该目录（fontconfig 配方一致），无需额外配置即可被扫描。
# 版本可用环境变量覆盖：
#   LINBOX_DEJAVU_URL / LINBOX_NOTO_URL
# ============================================================

DEJAVU_URL="${LINBOX_DEJAVU_URL:-https://github.com/dejavu-fonts/dejavu-fonts/releases/download/version_2_37/dejavu-fonts-ttf-2.37.tar.bz2}"
NOTO_URL="${LINBOX_NOTO_URL:-https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf}"
# 备用源：noto-cjk 仓库静态 Regular（体积较大但路径稳定）
NOTO_BACKUP_URL="https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf"

custom_url() {
  local _tarFile

  # ---- DejaVu（tarball，get_src 已建好 $pjName 目录）----
  echo "下载 DejaVu 字体包..."
  rm -rf /tmp/download-src
  mkdir -p /tmp/download-src
  if wget -nv --timeout=60 --tries=2 --waitretry=3 -P /tmp/download-src/ "${DEJAVU_URL}"; then
    _tarFile="/tmp/download-src/$(ls /tmp/download-src/)"
    tar -xjf "${_tarFile}" -C "${pjName}" --strip-components=1 \
      || { echo "DejaVu 解压失败" && exit 1; }
    rm -rf /tmp/download-src
  else
    echo "DejaVu 下载失败!" && exit 1
  fi

  # ---- Noto Sans SC（单文件可变字体）----
  echo "下载 Noto Sans SC 字体..."
  mkdir -p "${pjName}/noto"
  if wget -nv --timeout=120 --tries=2 --waitretry=3 \
       -O "${pjName}/noto/NotoSansSC.ttf" "${NOTO_URL}"; then
    # GitHub raw 对过大文件/LFS 异常会返回小体积文本，做个体积兜底
    if [[ "$(stat -c%s "${pjName}/noto/NotoSansSC.ttf" 2>/dev/null || echo 0)" -lt 1048576 ]]; then
      echo "Noto Sans SC 主源文件异常（<1MB），切换备用源..."
      wget -nv --timeout=120 --tries=2 --waitretry=3 \
        -O "${pjName}/noto/NotoSansSC.ttf" "${NOTO_BACKUP_URL}" \
        || { echo "Noto Sans SC 备用源下载失败!" && exit 1; }
    fi
  else
    echo "Noto Sans SC 主源失败，切换备用源..."
    wget -nv --timeout=120 --tries=2 --waitretry=3 \
      -O "${pjName}/noto/NotoSansSC.ttf" "${NOTO_BACKUP_URL}" \
      || { echo "Noto Sans SC 下载失败!" && exit 1; }
  fi
  echo "字体源码就绪: $(du -sh "${pjName}" | cut -f1)"
}

extra_fuction() {
  local _fontDir="${destDir}${prefix}/share/fonts"
  local _f

  # ---- DejaVu → share/fonts/dejavu ----
  mkdir -p "${_fontDir}/dejavu"
  if ls "${srcDir}/${pjName}"/ttf/*.ttf >/dev/null 2>&1; then
    cp -a "${srcDir}/${pjName}"/ttf/*.ttf "${_fontDir}/dejavu/"
    # DejaVu 许可证（tarball 根目录）随字体一并安装
    for _f in LICENSE COPYRIGHT; do
      [[ -f "${srcDir}/${pjName}/${_f}" ]] && \
        cp -a "${srcDir}/${pjName}/${_f}" "${_fontDir}/dejavu/"
    done
  else
    echo "错误: 未找到 DejaVu TTF 文件" && exit 1
  fi

  # ---- Noto Sans SC → share/fonts/noto ----
  mkdir -p "${_fontDir}/noto"
  cp -a "${srcDir}/${pjName}/noto/NotoSansSC.ttf" "${_fontDir}/noto/"

  # ---- OFL 许可证一并安装（fontconfig 元数据完整性）----
  if [[ -f "${srcDir}/${pjName}/noto/NotoSansSC.ttf" ]]; then
    cp -a "${srcDir}/${pjName}/LICENSE.txt" "${_fontDir}/noto/" 2>/dev/null || true
  fi

  echo "字体安装完成:"
  echo "  dejavu: $(ls "${_fontDir}/dejavu" | wc -l) 个文件"
  echo "  noto:   $(ls "${_fontDir}/noto" | wc -l) 个文件"
}
