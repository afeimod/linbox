// ============================================================
// v2.22.2 内置 X11 桌面 · termux-x11 模块（X server 显示端）
// ============================================================
// 来源：jiaxinchen-max/termux-app 仓库的 termux-x11 模块
//（基于 termux/termux-x11 上游，lorie 合成器 + Xwayland 内嵌库）。
//
// 与参考实现的关键差异（LinBox 适配）：
// 1. 不启用 CMake 源码构建 —— 直接使用仓库内置的四个 ABI 预编译
//    libXlorie.so（libs/<abi>/libXlorie.so，jniLibs 指向 libs/），
//    无需拉取 xserver/pixman/libx11 等一整套 git 子模块；
// 2. Prefs.java 不再由 generatePrefs 任务在构建期从 preferences.xml
//    生成，而是把生成结果作为普通源码提交（src/main/java/com/
//    termux/x11/Prefs.java），降低用户侧构建链路复杂度；
// 3. 本模块作为 :app 的 library 依赖打包进 com.linbox 单 APK，
//    libXlorie.so 经 app 的 useLegacyPackaging=true 解压到
//    nativeLibraryDir —— CmdEntryPoint 的静态加载已补
//    LINBOX_X11_NATIVE_DIR 环境变量兜底（见 CmdEntryPoint.java）。
// ============================================================
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.x11"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        buildConfigField("String", "VERSION_NAME", "\"1.03.01-linbox\"")
        buildConfigField("String", "COMMIT", "\"linbox-x11-port\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    sourceSets {
        getByName("main") {
            // 预编译 X server（lorie + Xwayland in-process）
            jniLibs.srcDir("libs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.25")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.1")
    // ============================================================
    // fix9.7：appcompat / preference 改用 api 导出（原 implementation）
    // ============================================================
    // 本模块的公开类把这两个库的类型当父类暴露在对外 ABI 上：
    //   LoriePreferences extends AppCompatActivity
    //                    implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback
    //   LoriePreferences.PrefsProto extends PreferenceDataStore（Prefs 的父类）
    // implementation 依赖不进入消费者（:app）的编译类路径，:app 的 Kotlin
    // 代码一旦引用 LoriePreferences/Prefs（fix9.6 起在 LinBoxApp.kt:91、
    // X11Surface.kt、X11WindowController.kt 直接引用），Kotlin 编译器解析
    // 父类链即失败："unresolved supertypes: androidx.appcompat.app.
    // AppCompatActivity / androidx.preference.PreferenceDataStore"。
    // api 语义正确：父类 ABI 必须传递给消费者。
    // ============================================================
    api("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.6.0")
    api("androidx.preference:preference:1.1.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Wine 容器备份功能（TarCompressorUtils）使用，与参考实现一致
    implementation("com.github.luben:zstd-jni:1.5.2-3")
    implementation("org.tukaani:xz:1.7")
    implementation("org.apache.commons:commons-compress:1.20")
    // 框架隐藏 API 编译桩（运行期由真实框架提供）
    compileOnly(project(":termux-x11-stub"))
}
