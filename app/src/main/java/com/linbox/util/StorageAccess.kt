package com.linbox.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * v2.19：存储权限统一判定 / 引导入口。
 *
 * LinBox 作为类 PC 桌面，文件资源管理器直接访问 /storage/emulated/0 真实
 * 文件系统，壁纸/视频壁纸也从真实文件读取：
 * - Android 11+（R）：需要 MANAGE_EXTERNAL_STORAGE（"所有文件访问"，
 *   特殊权限，只能跳系统设置页开关，无法运行时对话框授权）；
 * - Android 10 及以下：READ/WRITE_EXTERNAL_STORAGE 运行时权限
 *   （requestLegacyExternalStorage=true 已在 Manifest 声明）。
 */
object StorageAccess {

    /** 是否已拥有完整存储访问能力 */
    fun hasAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /** Android 11+：跳转本应用的"所有文件访问"系统设置页 */
    fun manageIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 兜底：部分系统不响应带包名的页面，退到通用的所有文件列表页 */
    fun manageFallbackIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android 10 及以下：运行时权限清单 */
    fun legacyPermissions(): Array<String> = buildList {
        add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()
}
