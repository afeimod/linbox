// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    // v3.0（AnWind 全量移植）：Room KSP（Kotlin 1.9.25 配套版本）
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false
}
