#!/system/bin/sh
# ============================================================
# fix-wine64.sh — v1.21.3 设备端即时修复（无需重装 wine tarball）
# ============================================================
# 症状：在 LinBox 终端跑
#   $HOME/wine-dac-9.2-x86_64/bin/wine64 explorer /desktop=dac,1280x720 taskmgr
# 报  wine: could not exec the wine loader
#
# 根因（v1.21.3 源码级定位）：
#   bin/wine64 是 wine 的 launcher ELF（loader/main.c 编译）。它 dlopen
#   ntdll.so 后进入 __wine_main；首次运行（无 WINELOADERNOEXEC）会
#   loader_exec() → execv(wine64-preloader)，失败再 execv(自身)，两步都
#   要求「内核能直接 exec x86_64 ELF」——安卓没有 binfmt_misc 必败；
#   box64 的 execve 自重启又因自带 box64 的 PT_INTERP 指向安卓不存在的
#   /lib/ld-linux-aarch64.so.1 而 ENOENT → 最终 fatal_error。
#   （若你的目录里 wine64 来自旧版 tarball 遗留、新版解压未删除，
#     则该文件更是从未被 wrapper 化的裸 ELF，必现此错。）
#
# 修法：把 bin/wine64 换成自举 wrapper（与 v1.21.3 CI 产物同款逻辑）：
#   ① WINELOADERNOEXEC=1 —— wine 在 box64 内 in-process 启动，
#     不再 exec 任何 preloader/loader（沙箱 wine9.2 + box64 实测全链可用）
#   ② 优先 preloader 形态启动 box64（box64 原生识别，等价真 preloader 语义）
#   ③ 参数守卫（--version/--help/无参数）
# 可重复执行（幂等）；原文件备份为 wine64.bak-<时间戳>。
# ============================================================

die() { echo "[fix-wine64] ✗ $*" >&2; exit 1; }
log() { echo "[fix-wine64] $*"; }

HOME_DIR=${HOME:-/data/user/0/com.linbox/files/home}
BND="$HOME_DIR/wine-dac-9.2-x86_64"
DIR="$BND/bin"
[ -d "$DIR" ] || die "未找到 $DIR —— 请确认 wine-dac tarball 已解压到 \$HOME"

# ---- 1. 判断当前 wine64 形态 ----
CUR="$DIR/wine64"
[ -e "$CUR" ] || CUR=""
NEW_REAL=""
[ -f "$DIR/wine64.real" ] && NEW_REAL="$DIR/wine64.real"
[ -n "$NEW_REAL" ] || [ -f "$DIR/wine.real" ] && [ -n "$(ls "$DIR/wine.real" 2>/dev/null)" ] && NEW_REAL="${NEW_REAL:-$DIR/wine.real}"
[ -n "$NEW_REAL" ] || NEW_REAL="$DIR/wine.real"

if [ -n "$CUR" ]; then
    MAGIC=$(head -c 4 "$CUR" 2>/dev/null | od -An -tx1 | tr -d ' \n')
    if [ "$MAGIC" != "7f454c46" ]; then
        log "bin/wine64 已是脚本（可能已修复过），仅刷新内容。"
    else
        TS=$(date +%Y%m%d-%H%M%S)
        mv "$CUR" "$CUR.bak-$TS" || die "无法备份 $CUR"
        log "原 launcher ELF 已备份 → wine64.bak-$TS"
    fi
fi

# ---- 2. 写入自举 wrapper ----
cat > "$CUR" <<'WRAPPER'
#!/system/bin/sh
# LinBox-DAC wine64 自举 wrapper（fix-wine64.sh 生成，v1.21.3 逻辑）
DIR=$(CDPATH= cd "$(dirname "$0")" && pwd -P)
ROOT=$(dirname "$DIR")

# 真身选择：wine64.real（旧式构建）→ wine.real（wow64 构建）
if [ -f "$DIR/wine64.real" ]; then REAL="$DIR/wine64.real"; else REAL="$DIR/wine.real"; fi
# preloader 形态：与真身位宽配套
case "$REAL" in
    *wine64.real) [ -f "$DIR/wine64-preloader" ] && PRELOADER="$DIR/wine64-preloader" ;;
    *wine.real)   [ -f "$DIR/wine-preloader" ]   && PRELOADER="$DIR/wine-preloader" ;;
esac

SYSLIB="$ROOT/sysroot/lib"
die() { echo "[wine-dac] ✗ $*" >&2; exit 1; }
[ -f "$REAL" ] || die "缺 $REAL（tarball 解压不完整？）"
[ -d "$SYSLIB" ] || die "缺 $SYSLIB（tarball 解压不完整？）"

: "${PREFIX:=}"
if [ -z "$PREFIX" ]; then
    for _p in /data/user/*/com.linbox/files/usr /data/data/com.linbox/files/usr; do
        [ -d "$_p" ] && PREFIX="$_p" && break
    done
fi
[ -n "$PREFIX" ] || PREFIX=/data/user/0/com.linbox/files/usr
export PREFIX
TMPDIR="$PREFIX/tmp"
export TMPDIR
mkdir -p "$TMPDIR" 2>/dev/null || true

# 参数守卫（WINELOADERNOEXEC=1 下 wine 不再自处理 --version/--help）
case "${1:-}" in
    --version|-v) echo "wine-9.2 (LinBox DAC)"; exit 0 ;;
    --help|-h)    echo "Usage: wine64 PROGRAM [ARGUMENTS...]"; exit 0 ;;
    "")           echo "Usage: wine64 PROGRAM [ARGUMENTS...]"; exit 1 ;;
esac

# 访客侧库搜索路径（box64 内部加载器专用）
export BOX64_LD_LIBRARY_PATH="$SYSLIB:$ROOT/lib/wine/x86_64-unix:$ROOT/lib/wine${BOX64_LD_LIBRARY_PATH:+:$BOX64_LD_LIBRARY_PATH}"
unset LD_LIBRARY_PATH LD_PRELOAD
GLIBC_TUNABLES="${GLIBC_TUNABLES:+$GLIBC_TUNABLES:}glibc.pthread.rseq=0"
export GLIBC_TUNABLES
# ★ 关键：wine in-process 启动，绕开安卓无法 exec x86_64 ELF 的死锁
WINELOADERNOEXEC=1
export WINELOADERNOEXEC
export WINEDEBUG="${WINEDEBUG:-fixme-all}"
if [ -z "${WINEDLLOVERRIDES:-}" ] && [ "${LINBOX_DAC_MONO_PROMPT:-0}" != 1 ]; then
    WINEDLLOVERRIDES="mscoree,mshtml="
    export WINEDLLOVERRIDES
fi
if [ -z "${WINEPREFIX:-}" ]; then
    WINEPREFIX="$HOME/.wine"
    export WINEPREFIX
fi

# box64 选择：自带（私有 glibc）→ PATH → APK
SYSARM="$ROOT/sysroot-arm/lib"
LDP=""; LOADER=""; BOX64=""
if [ -n "$BOX64_BIN" ] && [ -x "$BOX64_BIN" ]; then
    BOX64="$BOX64_BIN"
elif [ -x "$DIR/box64" ]; then
    BOX64="$DIR/box64"
    if [ -x "$SYSARM/ld-linux-aarch64.so.1" ]; then
        LOADER="$SYSARM/ld-linux-aarch64.so.1"
        LDP="$SYSARM"
    fi
elif command -v box64 >/dev/null 2>&1; then
    BOX64="$(command -v box64)"
elif [ -x "$PREFIX/bin/box64" ]; then
    BOX64="$PREFIX/bin/box64"
fi
[ -n "$BOX64" ] || die "未找到 box64"

# DAC 自动拉起（与 CI wrapper 同款：先广播再等 socket）
if [ "${LINBOX_DAC_AUTO:-1}" = 1 ] && [ ! -S "$PREFIX/tmp/linbox-dac.sock" ]; then
    SIZE="${LINBOX_DAC_SIZE:-1280x720}"
    DW=${SIZE%x*}; DH=${SIZE#*x}
    if command -v am >/dev/null 2>&1; then
        am broadcast -p com.linbox -a com.linbox.action.DAC_START \
            --ei width "$DW" --ei height "$DH" >/dev/null 2>&1 || true
    fi
    i=0
    while [ ! -S "$PREFIX/tmp/linbox-dac.sock" ] && [ "$i" -lt 15 ]; do
        sleep 1; i=$((i+1))
    done
    [ -S "$PREFIX/tmp/linbox-dac.sock" ] || \
        echo "[wine-dac] ⚠ DAC 窗口未就绪——不用管顺序，winedac 每 2 秒自动重连（v1.21）" >&2
fi

if [ -n "$PRELOADER" ]; then
    if [ -n "$LOADER" ]; then
        exec "$LOADER" --library-path "$LDP" "$BOX64" "$PRELOADER" "$REAL" "$@"
    fi
    exec "$BOX64" "$PRELOADER" "$REAL" "$@"
fi
if [ -n "$LOADER" ]; then
    exec "$LOADER" --library-path "$LDP" "$BOX64" "$REAL" "$@"
fi
exec "$BOX64" "$REAL" "$@"
WRAPPER
chmod +x "$CUR" || die "无法写入 $CUR（目录只读？）"

# ---- 3. 自检 ----
V=$("$CUR" --version 2>/dev/null) || V=$(sh "$CUR" --version 2>/dev/null)
[ -n "$V" ] && log "自检通过：wine64 --version → $V" || die "自检失败：请把 bin/wine64 --version 的输出发回来"
log "完成。现在可以直接跑："
log "  \$HOME/wine-dac-9.2-x86_64/bin/wine64 explorer /desktop=dac,1280x720 taskmgr"
log "  （或继续用 bin/wine —— 两者等价）"
