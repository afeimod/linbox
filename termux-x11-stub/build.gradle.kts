// ============================================================
// v2.22.2 内置 X11 桌面 · 编译期桩模块（termux-x11 依赖）
// ============================================================
// 背景：termux-x11 模块的 CmdEntryPoint 使用了 Android 框架隐藏 API
//（android.app.IActivityManager / android.content.IIntentSender /
//  android.content.IIntentReceiver 等），这些类不在公共 android.jar
// 中。本模块提供同签名的"桩"实现（调用即抛 STUB 异常），仅参与
// 编译（compileOnly），运行期由真实框架类提供同名方法。
// 与参考实现（jiaxinchen-max/termux-app 的 shell-loader:stub）一致。
// ============================================================
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.shell.stub"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        buildConfig = false
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
    implementation("androidx.annotation:annotation:1.7.1")
}
