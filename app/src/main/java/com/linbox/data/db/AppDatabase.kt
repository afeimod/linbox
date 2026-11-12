package com.linbox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shortcutDao(): ShortcutDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao

    companion object {
        const val NAME = "linbox.db"
    }
}
