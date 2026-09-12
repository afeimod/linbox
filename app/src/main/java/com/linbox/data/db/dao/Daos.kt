package com.linbox.data.db.dao

import androidx.room.*
import com.linbox.data.db.entity.ShortcutEntity
import com.linbox.data.db.entity.BookmarkEntity
import com.linbox.data.db.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM shortcuts ORDER BY sortOrder ASC, createdAt DESC")
    fun observeAll(): Flow<List<ShortcutEntity>>

    @Query("SELECT * FROM shortcuts ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getAll(): List<ShortcutEntity>

    @Insert
    suspend fun insert(entity: ShortcutEntity): Long

    @Update
    suspend fun update(entity: ShortcutEntity)

    @Delete
    suspend fun delete(entity: ShortcutEntity)

    @Query("DELETE FROM shortcuts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE shortcuts SET sortOrder = :order WHERE id = :id")
    suspend fun updateOrder(id: Long, order: Int)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): BookmarkEntity?

    @Insert
    suspend fun insert(entity: BookmarkEntity): Long

    @Delete
    suspend fun delete(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("DELETE FROM history WHERE visitedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
