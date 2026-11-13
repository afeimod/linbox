package com.linbox.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 快捷方式实体。
 * type: 1=URL, 2=本地文件, 3=应用, 4=游戏快捷方式（v6.2）
 *
 * v6.2（用户："游戏快捷方式创建和启动缺少很多设置"）：新增 config 列 ——
 * 游戏快捷方式（type=4）的**游戏专属启动配置快照**（JSON：分辨率/驱动/
 * DXVK/启动参数/环境变量等全套 ContainerData 配置字段）。空串 = 跟随容器
 * 当前配置；非空 = 启动时以容器为基底合并本快照（会话级，不回写
 * container.conf）。旧库升级经 MIGRATION_1_2 加列（默认 ''，不丢数据）。
 */
@Entity(tableName = "shortcuts")
data class ShortcutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val iconAsset: String,
    val type: Int,
    val target: String,
    val launchArgs: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val config: String = ""
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val favicon: String? = null,
    val folder: String = "default",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)
