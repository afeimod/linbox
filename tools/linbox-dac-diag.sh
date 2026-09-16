#!/system/bin/sh
# ============================================================
# linbox-dac-diag.sh — LinBox DAC 显示链路一键诊断（设备端）
#
# 用法（LinBox 终端内）：
#   sh linbox-dac-diag.sh            # 只诊断，不改动任何文件
#   sh linbox-dac-diag.sh --fix      # 允许修复 APK glibc 目录里损坏的 libc.so
#
# 逐环检查：wine tarball → APK DAC 模块 → APK glibc → am/广播 → socket
# 结尾给出结论与下一步指令。全程只读（--fix 除外）。
# ============================================================

FIX=0
[ "$1" = "--fix" ] && FIX=1

PASS=0; FAIL=0; WARN=0
ok()   { echo "  ✓ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✗ $1"; FAIL=$((FAIL+1)); }
warn() { echo "  ⚠ $1"; WARN=$((WARN+1)); }

# ELF 魔数 + 架构检测（无需 file 命令）
# 用法: elf_arch <文件>  → 输出 aarch64|x86_64|text|other|missing
elf_arch() {
    _f="$1"
    [ -f "$_f" ] || { echo missing; return; }
    _magic=$(head -c 4 "$_f" | od -An -tx1 | tr -d ' \n')
    [ "$_magic" = "7f454c46" ] || { echo text; return; }
    _mach=$(od -An -tx1 -j18 -N2 "$_f" | tr -d ' \n')
    case "$_mach" in
        b700) echo aarch64 ;;
        3e00) echo x86_64 ;;
        *)    echo other ;;
    esac
}

echo "=============================================="
echo " LinBox DAC 链路诊断"
echo "=============================================="

# ---------- 1. PREFIX ----------
echo "[1] PREFIX 检测"
PREFIX="${PREFIX:-}"
if [ -z "$PREFIX" ]; then
    for _p in /data/user/*/com.linbox/files/usr /data/data/com.linbox/files/usr; do
        [ -d "$_p" ] && PREFIX="$_p" && break
    done
fi
[ -n "$PREFIX" ] || PREFIX=/data/user/0/com.linbox/files/usr
echo "  PREFIX=$PREFIX"
[ -d "$PREFIX" ] && ok "PREFIX 存在" || bad "PREFIX 目录不存在——LinBox 未安装/未装 bootstrap"
TMPDIR="$PREFIX/tmp"; export TMPDIR
mkdir -p "$TMPDIR" 2>/dev/null || true

# ---------- 2. wine tarball ----------
echo "[2] wine tarball 自举结构（$HOME/wine-dac-9.2-x86_64）"
WT="$HOME/wine-dac-9.2-x86_64"
HAVE_NEW=0
if [ -d "$WT/bin" ]; then
    [ -f "$WT/bin/wine.real" ] && ok "bin/wine.real 存在（自举 wrapper 已生成）" \
        || bad "bin/wine.real 缺失——旧版 tarball（wrapper 生成前），请整体解压新版"
    if [ -x "$WT/bin/box64" ]; then
        ok "bin/box64 自带（v1.12+ 自举 tarball）"
        HAVE_NEW=1
        _la=$(elf_arch "$WT/bin/box64")
        [ "$_la" = "aarch64" ] && ok "bin/box64 为 AArch64 ELF" \
            || bad "bin/box64 架构异常（$_la）——重新下载/解压 tarball"
        if [ -f "$WT/sysroot-arm/lib/ld-linux-aarch64.so.1" ]; then
            ok "sysroot-arm 私有 loader 存在"
        else
            bad "sysroot-arm/lib/ld-linux-aarch64.so.1 缺失——解压不完整"
        fi
    else
        warn "bin/box64 不存在 —— 这是 v1.12 之前的旧 tarball，会回退用 APK 自带"
        warn "  box64 → 踩 APK glibc 目录的坏 libc.so → invalid ELF header"
        warn "  处置：push 最新 wine/build_wine_dac.sh 后重跑 CI，下载新 tarball"
    fi
    [ -d "$WT/sysroot/lib" ] && ok "sysroot/lib（x86_64 glibc 闭包）存在" \
        || bad "sysroot/lib 缺失——解压不完整"
    _wd=$(ls "$WT"/lib/wine/*/winedac.so 2>/dev/null | head -1)
    [ -n "$_wd" ] && ok "winedac.drv 已装入（$_wd）" \
        || warn "winedac.so 未找到——tarball 需用最新 build_wine_dac.sh 重打"
else
    bad "$WT/bin 不存在——tarball 未解压：tar -xJf wine-dac-*.tar.xz -C \$HOME"
fi

# ---------- 3. APK DAC 模块 marker ----------
echo "[3] APK DAC 模块 marker（新 APK 装好并重进 LinBox 后出现）"
if [ -f "$PREFIX/bin/linbox-dac" ]; then
    ok "$PREFIX/bin/linbox-dac 存在（新 APK 已部署 DAC 脚本）"
else
    warn "$PREFIX/bin/linbox-dac 不存在 —— 两种可能：APK 为旧版（无 DAC 模块），"
    warn "  或新 APK 已装但 bootstrap 未重新解压（重进 LinBox / 重装后再试）"
fi
if [ -f "$PREFIX/bin/dac_allocd" ] || [ -f "$PREFIX/bin/libdac_allocd.so" ]; then
    ok "dac_allocd 侧车已部署"
else
    warn "dac_allocd 未部署（仅影响 glibc wine 的 AHB 代理，GDI 显示不受影响）"
fi

# ---------- 4. APK glibc 目录体检 ----------
echo "[4] APK 自带 glibc 目录（box64 回退路径的依赖源）"
GL="$PREFIX/glibc/lib"
if [ -d "$GL" ]; then
    _so=$(elf_arch "$GL/libc.so")
    _so6=$(elf_arch "$GL/libc.so.6")
    case "$_so" in
        aarch64) ok "libc.so 为 AArch64 ELF（正常）" ;;
        missing) bad "libc.so 不存在" ;;
        text)    warn "libc.so 是文本（ld 链接脚本，gpkg glibc 的常态）→ 运行时加载必报 invalid ELF header"
                 if [ "$_so6" = "aarch64" ]; then
                     if [ "$FIX" = 1 ]; then
                         cp -f "$GL/libc.so.6" "$GL/libc.so" 2>/dev/null \
                             && ok "已用 libc.so.6 覆盖修复 libc.so（备份未保留，可重装恢复）" \
                             || bad "libc.so 修复失败（权限？）"
                     else
                         warn "  可执行 sh $0 --fix 用 libc.so.6 覆盖修复（不影响正常使用）"
                     fi
                 else
                     warn "  且 libc.so.6 也非有效 AArch64 ELF（$_so6）——APK glibc 目录整体不可用"
                 fi ;;
        *)       warn "libc.so 架构异常（$_so）——wrong-arch ELF 同样报 invalid ELF header" ;;
    esac
    case "$_so6" in
        aarch64) ok "libc.so.6 为 AArch64 ELF" ;;
        text)    warn "libc.so.6 也是文本——glibc 目录损坏" ;;
        missing) warn "libc.so.6 不存在" ;;
        *)       warn "libc.so.6 架构为 $_so6" ;;
    esac
else
    warn "$GL 不存在（glibc-runner 未安装？仅影响回退路径）"
fi

# ---------- 5. am 命令 ----------
echo "[5] am 命令（广播 DAC_START 的载体）"
if command -v am >/dev/null 2>&1; then
    ok "am 可用：$(command -v am)"
else
    bad "终端无 am 命令——wrapper 无法自动拉起 DAC 窗口（需补 bootstrap 的 termux-tools）"
fi

# ---------- 6. 广播链路实测 ----------
echo "[6] 广播链路实测（显式 -p com.linbox；会真的拉出 DAC 窗口）"
SOCK="$TMPDIR/linbox-dac.sock"
if [ -S "$SOCK" ]; then
    ok "socket 已存在：$SOCK（DAC 窗口已在运行）"
else
    if command -v am >/dev/null 2>&1; then
        rm -f "$SOCK" 2>/dev/null
        # 先探针 STATUS（结果看 logcat），再真发 START
        am broadcast -p com.linbox -a com.linbox.action.DAC_STATUS >/dev/null 2>&1
        am broadcast -p com.linbox -a com.linbox.action.DAC_START \
            --ei width 1280 --ei height 720 >/dev/null 2>&1
        echo "  ... 等待 DAC 窗口建 socket（最多 10s）..."
        _i=0
        while [ ! -S "$SOCK" ] && [ "$_i" -lt 10 ]; do sleep 1; _i=$((_i+1)); done
        if [ -S "$SOCK" ]; then
            ok "DAC 窗口已就绪（socket 已建立）——广播→Receiver→DacView→bridge 全链通"
        else
            bad "10s 内 socket 未出现——广播未被处理或 DacView 建窗失败"
            echo "  排查："
            echo "  a) 发广播时 LinBox 必须在前台（画面要挂在它的 Activity 上）"
            echo "  b) logcat -d -s LinBoxDAC   ← 看有没有 DAC_START / DacApp 日志"
            echo "     - 完全无日志   → 广播没送达（APK 旧 / receiver 未注册）"
            echo "     - 有 DAC_START → 看 DacView/DacApp 后续报错"
        fi
    else
        warn "跳过（无 am 命令）"
    fi
fi

# ---------- 7. logcat ----------
echo "[7] LinBoxDAC 日志（最近 20 行）"
if command -v logcat >/dev/null 2>&1; then
    logcat -d -s LinBoxDAC 2>/dev/null | tail -20 || echo "  （无日志）"
else
    echo "  （终端无 logcat，跳过）"
fi

# ---------- 结论 ----------
echo "=============================================="
echo " 结论：PASS=$PASS FAIL=$FAIL WARN=$WARN"
if [ "$FAIL" -eq 0 ] && [ -S "$SOCK" ]; then
    echo " 链路健康。下一步：\$HOME/wine-dac-9.2-x86_64/bin/wine explorer /desktop=dac,1280x720 taskmgr"
elif [ "$HAVE_NEW" = 0 ]; then
    echo " 关键断点：wine tarball 为旧版（无自带 box64）——必须用最新 build_wine_dac.sh"
    echo " 重跑 CI 并重新解压 tarball，再执行上面命令。"
else
    echo " 按上方 ✗/⚠ 逐项处理后再试。"
fi
echo " 关闭 DAC 窗口：am broadcast -p com.linbox -a com.linbox.action.DAC_STOP"
echo "=============================================="
