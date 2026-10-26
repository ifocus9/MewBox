package com.mewbox.tv.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.History
import com.mewbox.tv.bean.Site
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteDao {
    @Query("SELECT * FROM site ORDER BY priority ASC")
    fun observeAll(): Flow<List<Site>>

    @Query("SELECT * FROM site ORDER BY priority ASC")
    suspend fun getAll(): List<Site>

    @Query("SELECT * FROM site WHERE isHome = 1 LIMIT 1")
    suspend fun getHome(): Site?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sites: List<Site>)

    @Update
    suspend fun update(site: Site)

    @Query("DELETE FROM site WHERE `key` NOT IN (:keys)")
    suspend fun deleteNotIn(keys: List<String>)

    @Query("DELETE FROM site")
    suspend fun clear()

    @Query("UPDATE site SET isHome = CASE WHEN `key` = :key THEN 1 ELSE 0 END")
    suspend fun setHome(key: String)
}

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: Config)

    @Query("SELECT * FROM config ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): Config?
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<History>>

    @Query("SELECT * FROM history WHERE `key` = :key")
    suspend fun get(key: String): History?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: History)

    @Query("DELETE FROM history WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(entities = [Config::class, Site::class, History::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun siteDao(): SiteDao
    abstract fun configDao(): ConfigDao
    abstract fun historyDao(): HistoryDao
}
