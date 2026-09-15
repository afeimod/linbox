#!/usr/bin/env bash
# ============================================================
# merge-into-repo.sh — 将 LinBox DAC 源码并入 linbox 仓库（幂等）
# ============================================================
# 用法：
#   ./merge-into-repo.sh [linbox 仓库路径]     # 默认 ../（本包解压在仓库同级）
#   ./merge-into-repo.sh --dry-run [仓库路径]  # 只打印将做的变更
#
# 行为：
#   1) 复制纯新增文件（DAC cpp/Kotlin/脚本/wine 驱动/构建脚本/docs/workflows）
#      ※ 源码包与目标仓库为同一目录时（脚本已随仓库分发，CI 内 ./tools/merge-into-repo.sh .）
#        整体跳过复制 —— DAC 已在项目内，只做下面的补丁校验
#   2) 幂等补丁六个宿主文件（已注入则自动跳过）：
#      - app/src/main/AndroidManifest.xml            注册 DacReceiver
#      - app/build.gradle.kts                        追加 DAC 原生编译块 + 脚本分发块
#      - app/src/main/java/com/linbox/LinBoxApp.kt   初始化 DacApp
#      - app/.../termux/TermuxBootstrapInstaller.kt  拷 dac_allocd + 5 个脚本进 $PREFIX/bin
#   3) 打印 Action 构建与真机验证步骤
# ============================================================
set -e

SRC="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd -P)"   # 本包根（物理路径）
DRY=0
if [ "$1" = "--dry-run" ]; then DRY=1; shift; fi
REPO="${1:-$(cd "$SRC/.." && pwd)}"
[ -d "$REPO" ] || { echo "✗ 目标仓库目录不存在：$REPO"; exit 1; }
REPO="$(cd "$REPO" && pwd -P)"   # 规范化：兼容 "."、相对路径与符号链接

# 源码包即仓库本身（脚本已随仓库提交、CI 内以 "." 指向自身）：
# 此时所有"新增文件"都已在位，复制阶段整体跳过，避免 cp 自我复制报
# "are the same file" 而中断。补丁阶段照常执行（幂等）。
SAME_REPO=0
if [ "$SRC" = "$REPO" ]; then SAME_REPO=1; fi

echo "源码包: $SRC"
echo "目标仓库: $REPO"
[ -f "$REPO/app/build.gradle.kts" ] || { echo "✗ 目标不是 linbox 仓库（缺 app/build.gradle.kts）"; exit 1; }
[ $DRY = 1 ] && echo "（dry-run 模式）"

# ------------------------------------------------------------
# 1) 纯新增文件（源码包 = 仓库本身时整体跳过）
# ------------------------------------------------------------
if [ $SAME_REPO = 1 ]; then
    echo ">> 源码包与目标仓库为同一目录：DAC 已在项目内，跳过文件复制"
else
echo ">> 复制新增文件 ..."
copy_tree() {  # copy_tree <src-rel> <dst-rel>
    local s="$SRC/$1" d="$REPO/$2"
    [ -e "$s" ] || { echo "  ⚠ 缺少 $1（跳过）"; return; }
    # 目录级同位防护：即使 SRC≠REPO，单个子树也可能指向同一位置
    if [ -d "$d" ] && [ "$(cd "$s" && pwd -P)" = "$(cd "$d" && pwd -P)" ]; then
        echo "  = $2/（同一目录，跳过）"; return
    fi
    if [ $DRY = 0 ]; then mkdir -p "$d"; cp -r "$s"/. "$d"/; fi
    echo "  - $2/"
}
copy_tree "app/src/main/cpp/dac"                    "app/src/main/cpp/dac"
copy_tree "app/src/main/java/com/linbox/apps/dac"   "app/src/main/java/com/linbox/apps/dac"
copy_tree "app/src/main/assets/termux/scripts"      "app/src/main/assets/termux/scripts"
copy_tree "wine"                                    "wine"
copy_tree "dxvk"                                    "dxvk"
copy_tree "mesa"                                    "mesa"
copy_tree "docs"                                    "docs"
copy_tree ".github/workflows"                       ".github/workflows"
if [ -f "$SRC/README-DAC.md" ]; then
    if [ "$SRC/README-DAC.md" -ef "$REPO/README-DAC.md" ]; then
        echo "  = README-DAC.md（同一文件，跳过）"
    else
        [ $DRY = 0 ] && cp "$SRC/README-DAC.md" "$REPO/README-DAC.md"
        echo "  - README-DAC.md"
    fi
fi
if [ $DRY = 0 ]; then
    mkdir -p "$REPO/tools"
    if [ "$SRC/tools/merge-into-repo.sh" -ef "$REPO/tools/merge-into-repo.sh" ]; then
        echo "  = tools/merge-into-repo.sh（同一文件，跳过）"
    else
        cp "$SRC/tools/merge-into-repo.sh" "$REPO/tools/" || true
    fi
fi
fi   # end SAME_REPO

# ------------------------------------------------------------
# 2) 幂等补丁
# ------------------------------------------------------------
# insert_before <文件> <锚点子串> <插入文件> <幂等标记>
insert_before() {
    local f="$1" anchor="$2" patch="$3" marker="$4"
    if grep -qF "$marker" "$f"; then echo "  = $f（已注入，跳过）"; return; fi
    local n; n=$(awk -v a="$anchor" 'index($0,a){print NR; exit}' "$f")
    if [ -z "$n" ]; then
        echo "  ⚠ $f 未找到锚点『$anchor』——请手动集成（见 docs/DAC-BUILD.md）"; return
    fi
    [ $DRY = 0 ] && awk -v n="$n" '
        { if (NR==n) { while ((getline line < PAT) > 0) print line; print "" }
          print }' PAT="$patch" "$f" > "$f.tmp" && mv "$f.tmp" "$f"
    echo "  - $f（锚点前注入）"
}

# insert_after_all <文件> <锚点子串> <插入文件> <幂等标记>
insert_after_all() {
    local f="$1" anchor="$2" patch="$3" marker="$4"
    if grep -qF "$marker" "$f"; then echo "  = $f（已注入，跳过）"; return; fi
    if ! grep -qF "$anchor" "$f"; then
        echo "  ⚠ $f 未找到锚点『$anchor』——请手动集成（见 docs/DAC-BUILD.md）"; return
    fi
    [ $DRY = 0 ] && awk -v a="$anchor" '
        { print
          if (index($0,a)) { while ((getline line < PAT) > 0) print line; print "" } }
    ' PAT="$patch" "$f" > "$f.tmp" && mv "$f.tmp" "$f"
    echo "  - $f（锚点后注入）"
}

# append_once <文件> <追加文件> <幂等标记>   （追加到文件尾，顶层作用域）
append_once() {
    local f="$1" patch="$2" marker="$3"
    if grep -qF "$marker" "$f"; then echo "  = $f（已注入，跳过）"; return; fi
    [ $DRY = 0 ] && cat "$patch" >> "$f" && echo "" >> "$f"
    echo "  - $f（文件尾追加）"
}

echo ">> 补丁 AndroidManifest.xml ..."
P=$(mktemp)
cat > "$P" <<'XML'
        <!-- LinBox DAC：终端 am broadcast 触发的显示入口（merge-into-repo.sh 注入） -->
        <receiver
            android:name="com.linbox.apps.dac.DacReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="com.linbox.action.DAC_START" />
                <action android:name="com.linbox.action.DAC_STOP" />
                <action android:name="com.linbox.action.DAC_STATUS" />
            </intent-filter>
        </receiver>
XML
insert_before "$REPO/app/src/main/AndroidManifest.xml" "</application>" "$P" "DacReceiver"

echo ">> 补丁 app/build.gradle.kts（DAC 原生编译块）..."
P2=$(mktemp)
cat > "$P2" <<'KTS'
// ============================================================
// LinBox DAC —— 原生桥编译（tools/merge-into-repo.sh 幂等追加块）
// 与上方 linbox-reprefix 同风格：Gradle Exec 调 NDK clang 逐 ABI 编译。
// ⚠️ bridge 源为 .cpp（clang++ 编译）：NDK r26 的 surface_control.h 内
// setGeometry/setBuffer 等签名含 C++ 引用/默认参数且无 __cplusplus 分流，
// 纯 C 模式无法解析；JNI 符号由 bridge.h 的 extern "C" 守护保持不修饰。
//   liblinbox_dac_bridge.so  JNI 桥（app 进程内：SF 直合/AHB Canvas/dmabuf EGL）
//   libdac_allocd.so         AHB 侧车守护进程（可执行伪装 so，由
//                            TermuxBootstrapInstaller 拷到 $PREFIX/bin/dac_allocd）
// 说明：AGP 每模块仅允许一个 externalNativeBuild.ndkBuild（已被 termux
// Android.mk 占用），故与 reprefix 一样走 Exec 方案。
// ============================================================
val dacAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7a-linux-androideabi",
    "x86" to "i686-linux-android",
    "x86_64" to "x86_64-linux-android"
)
val dacApiBridge = 29   // ASurfaceControl/ASurfaceTransaction（API29 直合；低版本 DacNative.available 兜底禁用）
val dacApiAllocd = 26   // AHardwareBuffer_allocate（API26+）
val dacSource = file("src/main/cpp/dac")
val dacOut = layout.buildDirectory.dir("dac")

afterEvaluate {
    val ndkDir = android.ndkDirectory
    val osName = System.getProperty("os.name").lowercase()
    val dacHostTag = when {
        osName.contains("windows") -> "windows-x86_64"
        osName.contains("mac") || osName.contains("darwin") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val clangBin = File(ndkDir, "toolchains/llvm/prebuilt/$dacHostTag/bin")

    val buildDacAll = tasks.register("buildDacNatives") {
        group = "build"
        description = "编译 LinBox DAC 原生桥（liblinbox_dac_bridge.so + libdac_allocd.so，全 ABI）"
    }

    dacAbis.forEach { (abi, triple) ->
        val abiName = abi.split("-").joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
        val outDir = dacOut.get().dir(abi).asFile
        val outBridge = File(outDir, "liblinbox_dac_bridge.so")
        val outAllocd = File(outDir, "libdac_allocd.so")
        val bridgeTask = tasks.register<Exec>("buildDacBridge$abiName") {
            group = "build"
            inputs.file(File(dacSource, "linbox_dac_bridge.cpp"))
            outputs.file(outBridge)
            doFirst {
                outDir.mkdirs()
                val clang = File(clangBin, "${triple}$dacApiBridge-clang++")
                if (!clang.exists()) throw GradleException("找不到 NDK clang++：${clang.absolutePath}")
            }
            commandLine(
                File(clangBin, "${triple}$dacApiBridge-clang++").absolutePath,
                "-shared", "-fPIC", "-O2", "-Wall", "-Wno-unused-parameter",
                "-o", outBridge.absolutePath,
                File(dacSource, "linbox_dac_bridge.cpp").absolutePath,
                "-llog", "-landroid", "-lEGL", "-lGLESv2"
            )
        }
        val allocdTask = tasks.register<Exec>("buildDacAllocd$abiName") {
            group = "build"
            inputs.file(File(dacSource, "dac_allocd.c"))
            outputs.file(outAllocd)
            doFirst {
                outDir.mkdirs()
                val clang = File(clangBin, "${triple}$dacApiAllocd-clang")
                if (!clang.exists()) throw GradleException("找不到 NDK clang：${clang.absolutePath}")
            }
            commandLine(
                File(clangBin, "${triple}$dacApiAllocd-clang").absolutePath,
                "-O2", "-Wall", "-Wno-unused-parameter",
                "-o", outAllocd.absolutePath,
                File(dacSource, "dac_allocd.c").absolutePath,
                "-landroid", "-llog"
            )
        }
        buildDacAll.configure { dependsOn(bridgeTask, allocdTask) }
    }

    tasks.named("preBuild") { dependsOn(buildDacAll) }
    tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLib") }
        .configureEach { dependsOn(buildDacAll) }

    android.sourceSets.getByName("main") {
        jniLibs.srcDir(dacOut.get())
    }
}
KTS
append_once "$REPO/app/build.gradle.kts" "$P2" "LinBox DAC —— 原生桥编译"

echo ">> 补丁 LinBoxApp.kt（DacApp 初始化）..."
P3=$(mktemp)
cat > "$P3" <<'KTD'

        // LinBox DAC（tools/merge-into-repo.sh 注入）：注入上下文并初始化。
        // 命令行链路：linbox-dac → am broadcast DAC_START → DacReceiver → DacApp
        com.linbox.apps.dac.DacApp.appContext = this
        com.linbox.apps.dac.DacApp.init(null)   // null = 兜底全屏覆盖层（挂前台 Activity decorView）
KTD
insert_after_all "$REPO/app/src/main/java/com/linbox/LinBoxApp.kt" "settingsStore = SettingsStore(this)" "$P3" "com.linbox.apps.dac.DacApp.appContext"

echo ">> 补丁 TermuxBootstrapInstaller.kt（dac_allocd 拷贝）..."
P4=$(mktemp)
cat > "$P4" <<'KTD'

        // (1.5) LinBox DAC 侧车守护进程（merge-into-repo.sh 注入）：
        //       glibc Wine 模式下 AHardwareBuffer 代理（arm64 bionic 进程）
        val dacAllocdSrc = File(nativeDir, "libdac_allocd.so")
        if (dacAllocdSrc.isFile) {
            val dacAllocdDst = File(prefix, "bin/dac_allocd")
            dacAllocdSrc.copyTo(dacAllocdDst, overwrite = true)
            Os.chmod(dacAllocdDst.absolutePath, PERMISSION_0700)
        }
KTD
insert_before "$REPO/app/src/main/java/com/linbox/apps/terminal/termux/TermuxBootstrapInstaller.kt" "// (2) dpkg 包装器三层布局" "$P4" "libdac_allocd.so"

echo ">> 补丁 app/build.gradle.kts（DAC 显示脚本分发块）..."
P5=$(mktemp)
cat > "$P5" <<'KTS'

// ============================================================
// LinBox DAC —— 显示脚本分发（tools/merge-into-repo.sh 幂等追加块）
// ============================================================
// 将 assets/termux/scripts 的 linbox-dac* shell 脚本以 lib*.so 名义并入
// jniLibs（复用 DAC 原生库管线，全 ABI 目录各放一份，防 assets 提取逻辑
// 不确定性），安装后由 TermuxBootstrapInstaller 还原为 $PREFIX/bin/linbox-dac*。
// keepDebugSymbols：防 release strip 对非 ELF 文件报错（AGP ≥ 7.3）。
// 依赖：上方「LinBox DAC —— 原生桥编译」块定义的 dacAbis / dacOut。
// ============================================================
android {
    packaging {
        jniLibs {
            keepDebugSymbols.add("**/liblinbox_dac*.so")
        }
    }
}

tasks.register("copyDacDisplayScripts") {
    group = "build"
    description = "LinBox DAC shell 脚本以 lib*.so 并入 jniLibs（各 ABI 目录）"
    val dacScriptDir = file("src/main/assets/termux/scripts")
    inputs.dir(dacScriptDir)
    outputs.upToDateWhen { false }
    doLast {
        dacAbis.keys.map { dacOut.get().dir(it).asFile }.forEach { d ->
            d.mkdirs()
            dacScriptDir.listFiles()?.filter { it.name.startsWith("linbox-dac") }?.forEach { f ->
                // 注意：这里不能用全限定 java.io.File —— Kotlin DSL 脚本里
                // 该写法会被隐式接收者遮蔽（Unresolved reference: io）。
                // File 由 Kotlin 默认导入（java.io.*）解析，无遮蔽问题。
                File(d, "lib" + f.name.replace("-", "_") + ".so").writeBytes(f.readBytes())
            }
        }
    }
}
tasks.named("preBuild") { dependsOn("copyDacDisplayScripts") }
KTS
append_once "$REPO/app/build.gradle.kts" "$P5" "LinBox DAC —— 显示脚本分发"

echo ">> 补丁 TermuxBootstrapInstaller.kt（DAC 脚本还原 $PREFIX/bin）..."
P6=$(mktemp)
cat > "$P6" <<'KTD'

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
KTD
insert_before "$REPO/app/src/main/java/com/linbox/apps/terminal/termux/TermuxBootstrapInstaller.kt" "// (2) dpkg 包装器三层布局" "$P6" "liblinbox_dac.so"

rm -f "$P" "$P2" "$P3" "$P4" "$P5" "$P6"

# ------------------------------------------------------------
# 3) 汇总
# ------------------------------------------------------------
echo ""
echo "============================================================"
echo " 集成完成（dry-run=$DRY）。下一步："
echo "============================================================"
if [ $SAME_REPO = 1 ]; then
    echo " · 源码包即仓库本身：本次未复制文件，仅校验/注入了宿主文件补丁"
    echo "   （若本次有补丁注入，请提交 Manifest/gradle/LinBoxApp/BootstrapInstaller 的改动）"
fi
echo " 1. 提交推送（DAC 自此成为项目源码的一部分，此后任何构建自动包含）："
echo "      cd $REPO && git add -A && git commit -m 'LinBox DAC v1.8' && git push"
echo " 2. 构建（三选一，产物相同，无需再跑本脚本）："
echo "      - 本地：Android Studio Run / ./gradlew assembleRelease"
echo "      - 你已有的 CI：什么都不用改（DAC 挂在 preBuild，正常构建即含）"
echo "      - 仓库 Actions「LinBox 构建（含 DAC 集成）」：push 自动出 APK；"
echo "        也可手动选组件 wine / dxvk / turnip / all 构建配套产物"
echo " 3. 真机：安装 APK → 终端部署 wine-dac tarball →"
echo "      linbox-dac game.exe    # auto：winedac.drv 已装走 DAC 独立显示器，"
echo "                             #        否则复用已有 X11（DISPLAY/内置 :13/termux-x11）"
echo "      linbox-dac doctor      # 逐项体检"
echo " 详细文档：README-DAC.md / docs/DAC-BUILD.md"
echo "============================================================"
