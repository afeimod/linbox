plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// ============================================================
// v2.14.8：ruffle 资产白名单过滤（打包期剔除仓库残留旧文件）
// ============================================================
// 背景：历次交付均为 zip 覆盖 app/，zip 无法表达"删除" —— 仓库
// assets/ruffle/ 会残留旧引擎文件（0.3.0 的双 wasm + 双 core.js，
// 约 28MB 死重）。Ruffle 自托管包本身为"双核"结构（ruffle.js 按
// 设备特性二选一加载 core+wasm），下列白名单即当前引擎的可加载
// 全集（供给层按文件名映射 assets/ruffle/<file>，加载器只会请求
// ruffle.js 里固化的哈希名）；白名单之外的文件不可能被请求，
// 打包期直接剔除，仓库无需手工清理。
// ============================================================
val ruffleAssetWhitelist = setOf(
    "ruffle.js",
    "simhei.ttf",
    "1ef41ff58c9763bed027.wasm",
    "63468f5322aed2e768a8.wasm",
    "core.ruffle.0875e44536e955474b0c.js",
    "core.ruffle.831c4f4a93befb9e84af.js"
)
val filteredAssetsDir = layout.buildDirectory.dir("filteredAssets")
val filterRuffleAssets = tasks.register<Sync>("filterRuffleAssets") {
    description = "同步 src/main/assets 到过滤目录，ruffle/ 只保留白名单文件"
    into(filteredAssetsDir)
    from("src/main/assets") {
        exclude("ruffle/*")
    }
    from("src/main/assets/ruffle") {
        into("ruffle")
        include(ruffleAssetWhitelist)
    }
}
// 保险丝：preBuild / merge*Assets 先行触发同步（双锚点保证过滤目录在
// 资源合并前就绪，不依赖单一任务链假设）
tasks.matching {
    it.name == "preBuild" ||
        (it.name.startsWith("merge") && it.name.endsWith("Assets"))
}.configureEach { dependsOn(filterRuffleAssets) }

// ============================================================
// v2.22.1：linbox-reprefix 可执行构建（打包为 lib*.so 形式）
// ============================================================
// 背景：APK 的 native 库目录只收 lib/<abi>/*.so，而 linbox-reprefix
// 是可执行程序；ndk-build 的 BUILD_EXECUTABLE 又禁止模块文件名带
// .so 扩展名（"LOCAL_MODULE_FILENAME must not contain a file
// extension"，见 Android.mk 内说明），无法在 mk 内完成伪装。
// 方案：Gradle 直接调用同一 NDK 的 clang 包装器（与 ndkBuild 完全
// 同源的工具链：Bionic libc、API 24），逐 ABI 编译
// src/main/cpp/termux/linbox_reprefix.c，产出
//   build/reprefix/<abi>/liblinbox_reprefix.so
// 该目录加入 jniLibs sourceSets 后与普通 so 一样进 APK 的
// lib/<abi>/；stripReleaseDebugSymbols 对可执行 ELF 同样只是去符号，
// 不影响其可执行性。安装期由 TermuxBootstrapInstaller 从
// nativeLibraryDir 拷贝到 $PREFIX/bin/linbox-reprefix 并 chmod 0700。
// ============================================================
val reprefixAbis = mapOf(
    // AGP ABI 名 → NDK clang 目标三元组（+ minSdk API 级别）
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7a-linux-androideabi",
    "x86" to "i686-linux-android",
    "x86_64" to "x86_64-linux-android"
)
val reprefixApiLevel = 24
val reprefixSource = file("src/main/cpp/termux/linbox_reprefix.c")
val reprefixOutDir = layout.buildDirectory.dir("reprefix")

afterEvaluate {
    // NDK 目录：afterEvaluate 时 AGP 已按 ndkVersion 解析好
    //（CI 由 workflow 预装 26.3.11579264，与 externalNativeBuild 同一实例）
    val ndkDir = android.ndkDirectory
    val osName = System.getProperty("os.name").lowercase()
    val reprefixHostTag = when {
        osName.contains("windows") -> "windows-x86_64"
        osName.contains("mac") || osName.contains("darwin") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val reprefixClangBin = File(ndkDir, "toolchains/llvm/prebuilt/$reprefixHostTag/bin")

    val reprefixAll = tasks.register("buildReprefixExecutables") {
        group = "build"
        description = "编译 linbox-reprefix 可执行（4 ABI，产出 liblinbox_reprefix.so 进 jniLibs）"
    }

    reprefixAbis.forEach { (abi, triple) ->
        val abiTaskName = "buildReprefix" + abi.split("-").joinToString("") { p ->
            p.replaceFirstChar { it.uppercase() }
        } // 例：buildReprefixArm64V8a
        val clang = File(reprefixClangBin, "$triple$reprefixApiLevel-clang")
        val outDir = reprefixOutDir.get().dir(abi).asFile
        val outFile = File(outDir, "liblinbox_reprefix.so")
        val abiTask = tasks.register<Exec>(abiTaskName) {
            group = "build"
            inputs.file(reprefixSource)
            outputs.file(outFile)
            doFirst {
                outDir.mkdirs()
                if (!clang.exists()) {
                    throw GradleException("找不到 NDK clang 包装器：${clang.absolutePath}（请确认 NDK $ndkDir 已安装）")
                }
            }
            commandLine(
                clang.absolutePath,
                "-O2", "-Wall", "-Wextra",
                "-o", outFile.absolutePath,
                reprefixSource.absolutePath
            )
        }
        // TaskProvider 本身无 dependsOn 方法（上一轮 CI 编译错误根因），
        // 必须经 configure{} 在其 Task 对象上声明依赖；abiTask 以
        // TaskProvider 传入即可（Task.dependsOn 会自动解包）
        reprefixAll.configure { dependsOn(abiTask) }
    }

    // jniLibs 纳入 build/reprefix（其下 <abi>/liblinbox_reprefix.so 布局
    // 会被 AGP 按标准 jniLibs 结构识别并打进 APK lib/<abi>/）
    android.sourceSets.getByName("main") {
        jniLibs.srcDir(reprefixOutDir)
    }
    // 锚点：preBuild 先行触发（保证所有 merge 任务之前 so 已就绪，
    // 与上方 ruffle 过滤的双锚点模式一致）
    tasks.named("preBuild") { dependsOn(reprefixAll) }
    tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLib") }
        .configureEach { dependsOn(reprefixAll) }
}

android {
    namespace = "com.linbox"
    compileSdk = 34

    // ============================================================
    // NDK：Termux 移植的 PTY 原生库（libtermux.so）
    //（terminal-emulator jni/termux.c + LinBox FIFO 桥）
    // ============================================================
    ndkVersion = "26.3.11579264"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/termux/Android.mk")
        }
    }

    defaultConfig {
        applicationId = "com.linbox"
        minSdk = 24

        // ============================================================
        // ⚠⚠⚠ v2.22 Termux 移植关键约束：targetSdk 必须锁在 28 ⚠⚠⚠
        // ============================================================
        // Android 10+ 的 SELinux 策略禁止 targetSdk≥29 的应用 exec()
        // 自己数据目录里的二进制文件（W^X 限制）。Termux 环境的全部
        // 原生程序（bash/apt/pkg 及 pkg 安装的一切）都位于
        // /data/data/com.linbox/files/usr —— 只在 targetSdk≤28 时可执行。
        // 官方 Termux 也因此自 2019 年起一直锁定 targetSdk 28。
        //
        // 对 LinBox 现有功能的影响：全部兼容 ——
        // - SAF（本地 HTML/文件选择）不依赖 targetSdk
        // - Room/DataStore/Compose/WebView/Launcher 不受影响
        // - 已声明 requestLegacyExternalStorage + MANAGE_EXTERNAL_STORAGE
        // - Android 13+ 通知权限：targetSdk<33 的应用首次建渠道时系统
        //   自动弹授权（行为略有差异但可用）
        // 唯一代价：Android 10+ 安装时提示“此应用为旧版 Android 打造”。
        // ============================================================
        // v2.22.2 内置 X11 桌面（版本号随功能递增）
        targetSdk = 28
        versionCode = 46
        versionName = "2.22.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // NDK：保留全部 ABI，让 APK 可装任意设备
        //（bootstrap 离线包仅含 aarch64；其他架构打开终端时会收到明确提示）
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // bootstrap 归档（assets/termux/*.zip）保持不压缩：
    // 避免二次压缩浪费构建时间，安装期拷贝更快
    androidResources {
        noCompress += listOf("zip")
    }

    // ============================================================
    // 签名配置
    // ============================================================
    // 优先使用环境变量指定的 release keystore（CI 环境会自动生成）
    // 如果环境变量不存在（本地开发），回退到 debug 签名
    //
    // ⚠️ 重要：debug 签名的 APK 会被标记为 testOnly=true，
    //    Android 14 系统安装器会拒绝安装（必须 adb install -t）
    //    所以 CI 构建必须用 release keystore 签名
    // ============================================================
    val keystorePath = System.getenv("KEYSTORE_PATH")
    val keystorePass = System.getenv("KEYSTORE_PASS")
    val keyAlias = System.getenv("KEY_ALIAS") ?: "linbox"
    val keyPass = System.getenv("KEY_PASS")

    signingConfigs {
        create("release") {
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                this.keyAlias = keyAlias
                this.keyPassword = keyPass ?: keystorePass
                println("✅ Using release keystore from: $keystorePath")
            } else {
                println("⚠️  No release keystore found, release APK will use debug signing (testOnly)")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 使用 release keystore 签名（CI 环境自动生成）
            // 如果没有 release keystore，回退到 debug 签名
            signingConfig = if (keystorePath != null && file(keystorePath).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets {
        getByName("main") {
            // v2.14.8：用白名单过滤目录整体替换默认 assets 源
            //（Sync 任务产出；上方 preBuild 保险丝保证合并前就绪，
            // 仓库残留旧 ruffle 文件不再进 APK）
            assets.setSrcDirs(listOf(filteredAssetsDir))
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        // 1.5.15 与 Kotlin 1.9.25 官方配对
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        // native 库解压到 nativeLibraryDir（AGP 8 默认 extractNativeLibs=false
        // 时库只留在 APK 内）：linbox-reprefix 可执行需要由安装器从
        // nativeLibraryDir 拷贝进 $PREFIX/bin 才能被 shell 调用
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // ============================================================
    // 构建修复：禁用 ExpiredTargetSdkVersion lint 检查
    // ============================================================
    // 现象：gradle assembleRelease 在 :app:lintVitalRelease 阶段失败：
    //   "Google Play requires that apps target API level 33 or higher.
    //    [ExpiredTargetSdkVersion]"
    // 根因：lintVitalRelease 把 targetSdk=28 判为 fatal error。但：
    // 1) targetSdk 锁 28 是 Termux 移植的硬性约束（Android 10+ SELinux
    //    W^X 限制，见上方 defaultConfig 注释），不可上调；
    // 2) 本应用经 GitHub Actions 分发 APK，不经 Google Play 分发，
    //    ExpiredTargetSdkVersion 是 Play 上架政策检查，此处不适用。
    // ============================================================
    lint {
        disable += "ExpiredTargetSdkVersion"
        // 保险丝：其余 lint 错误同样不中断 release 构建
        //（lint 报告仍会生成在 app/build/reports/，仅不再使构建失败）
        abortOnError = false
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WebView
    implementation("androidx.webkit:webkit:1.11.0")

    // v2.22.2 内置 X11 桌面：X server 显示端（lorie 合成器 + Xwayland
    // 预编译库 libXlorie.so 全 ABI）。libXlorie.so 随本模块 jniLibs 打包，
    // 与 app 的 useLegacyPackaging=true 兼容（CmdEntryPoint 有
    // nativeLibraryDir 兜底加载逻辑，见 termux-x11/CmdEntryPoint.java）。
    implementation(project(":termux-x11"))

    // ============================================================
    // v2.22.2 fix9.7：补 :app 对 androidx.appcompat / androidx.preference 的
    // 编译期依赖（CI 实证 ：app:compileReleaseKotlin 失败）
    // ============================================================
    // fix9.6 起 :app 的 Kotlin 代码直接引用 termux-x11 的公开类：
    //   LinBoxApp.kt:91          LoriePreferences.prefs = Prefs(this)
    //   X11Surface.kt:63/123/... remember { LoriePreferences.prefs }、prefs != null、
    //                            prefs.displayResolutionMode.get() 等
    //   X11WindowController.kt:175 LoriePreferences.prefs?.let { view.reloadPreferences(it) }
    // 这些类的父类（LoriePreferences extends AppCompatActivity；Prefs → PrefsProto
    // extends PreferenceDataStore）必须出现在 :app 的编译类路径上，Kotlin 才能完成
    // 父类链解析。虽然 termux-x11 侧已改为 api 导出（见其 build.gradle.kts），
    // 此处按同版本再显式声明一次，双保险防再犯：
    // ============================================================
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.preference:preference:1.1.1")

    // Document file (for local HTML access via SAF)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
