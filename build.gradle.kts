// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    // v2.20.1：Kotlin 1.9.24→1.9.25 + KSP 1.9.25-1.0.20（官方配对）
    // 根因：CI 恢复的 Gradle 缓存中残留了「KSP 1.9.24-1.0.20 不存在」的
    // 负缓存（一次瞬时网络抖动被永久记住），换新坐标彻底绕开
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false
}
