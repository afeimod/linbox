package com.linbox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.linbox.data.db.dao.BookmarkDao
import com.linbox.data.db.dao.HistoryDao
import com.linbox.data.db.dao.ShortcutDao
import com.linbox.data.db.entity.BookmarkEntity
import com.linbox.data.db.entity.HistoryEntity
import com.linbox.data.db.entity.ShortcutEntity

@Database(
    entities = [
        ShortcutEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shortcutDao(): ShortcutDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao

    companion object {
        const val NAME = "linbox.db"

        /**
         * v6.2：shortcuts 表新增 config 列（游戏快捷方式的启动配置快照，
         * TEXT NOT NULL DEFAULT ''）。存量快捷方式/书签/历史全部保留 ——
         * 显式迁移优先于 fallbackToDestructiveMigration（后者仅在无迁移
         * 路径时兜底），不会清库。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE shortcuts ADD COLUMN config TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}
