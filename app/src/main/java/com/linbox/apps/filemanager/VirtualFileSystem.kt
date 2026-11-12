package com.linbox.apps.filemanager

import android.content.Context

/**
 * 虚拟文件数据
 */
data class VirtualFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val extension: String = "",
    val assetPath: String = "",  // assets 中的实际路径（用于打开 html 等）
    val realUri: android.net.Uri? = null  // 真实文件系统的 URI（用于安装 APK 等）
) {
    val sizeText: String
        get() = when {
            size < 1024 -> "${size} B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
}

/**
 * 虚拟文件系统：模拟 Windows 目录结构。
 *
 * 通过 assets/filesystem/manifest.json 读取预定义的文件树，
 * 也支持在运行时动态创建（保存到 Room 或内部存储）。
 *
 * 简化版：硬编码目录结构。
 */
class VirtualFileSystem(private val context: Context) {

    /**
     * 列出指定路径下的所有文件和文件夹。
     *
     * 路径格式：C:\\   C:\\Users\\  C:\\Users\\User\\Documents
     */
    fun list(path: String): List<VirtualFile> {
        val normalized = path.replace("\\", "/").removeSuffix("/")
        return when (normalized) {
            "C:", "C:/" -> listOf(
                VirtualFile("Users", "C:\\Users\\", true),
                VirtualFile("Program Files", "C:\\Program Files\\", true),
                VirtualFile("Windows", "C:\\Windows\\", true),
                VirtualFile("Documents and Settings", "C:\\Documents and Settings\\", true)
            )
            "C:/Users", "C:/Users/" -> listOf(
                VirtualFile("User", "C:\\Users\\User\\", true),
                VirtualFile("Public", "C:\\Users\\Public\\", true)
            )
            "C:/Users/User", "C:/Users/User/" -> listOf(
                VirtualFile("Desktop", "C:\\Users\\User\\Desktop\\", true),
                VirtualFile("Documents", "C:\\Users\\User\\Documents\\", true),
                VirtualFile("Downloads", "C:\\Users\\User\\Downloads\\", true),
                VirtualFile("Pictures", "C:\\Users\\User\\Pictures\\", true),
                VirtualFile("Music", "C:\\Users\\User\\Music\\", true),
                VirtualFile("Videos", "C:\\Users\\User\\Videos\\", true)
            )
            "C:/Users/User/Documents", "C:/Users/User/Documents/" -> listOf(
                VirtualFile("readme.txt", "C:\\Users\\User\\Documents\\readme.txt", false, 256, "txt"),
                VirtualFile("welcome.html", "C:\\Users\\User\\Documents\\welcome.html", false, 1024, "html"),
                VirtualFile("notes.txt", "C:\\Users\\User\\Documents\\notes.txt", false, 512, "txt")
            )
            "C:/Users/User/Pictures", "C:/Users/User/Pictures/" -> listOf(
                VirtualFile("wallpaper1.jpg", "C:\\Users\\User\\Pictures\\wallpaper1.jpg", false, 1024 * 1024, "jpg"),
                VirtualFile("screenshot.png", "C:\\Users\\User\\Pictures\\screenshot.png", false, 512 * 1024, "png")
            )
            "C:/Users/User/Downloads", "C:/Users/User/Downloads/" -> listOf(
                VirtualFile("setup.exe", "C:\\Users\\User\\Downloads\\setup.exe", false, 1024 * 1024 * 12, "exe"),
                VirtualFile("data.zip", "C:\\Users\\User\\Downloads\\data.zip", false, 1024 * 1024 * 5, "zip")
            )
            "C:/Program Files", "C:/Program Files/" -> listOf(
                VirtualFile("LinBox", "C:\\Program Files\\LinBox\\", true),
                VirtualFile("Common Files", "C:\\Program Files\\Common Files\\", true),
                VirtualFile("Internet Explorer", "C:\\Program Files\\Internet Explorer\\", true)
            )
            "C:/Windows" -> listOf(
                VirtualFile("System32", "C:\\Windows\\System32\\", true),
                VirtualFile("Fonts", "C:\\Windows\\Fonts\\", true),
                VirtualFile("explorer.exe", "C:\\Windows\\explorer.exe", false, 1024 * 2048, "exe"),
                VirtualFile("notepad.exe", "C:\\Windows\\notepad.exe", false, 1024 * 128, "exe")
            )
            else -> emptyList()
        }
    }

    fun parent(path: String): String? {
        val normalized = path.replace("\\", "/").trimEnd('/')
        if (normalized == "C:" || normalized == "C:/") return null
        val idx = normalized.lastIndexOf('/')
        if (idx <= 2) return "C:\\"
        return normalized.substring(0, idx).replace("/", "\\") + "\\"
    }
}
