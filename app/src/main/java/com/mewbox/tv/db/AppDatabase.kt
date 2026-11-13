package com.mewbox.tv.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.History
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.TrackPreference
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

    @Transaction
    suspend fun replaceAll(sites: List<Site>) {
        clear()
        upsert(sites)
    }
}

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: Config)

    @Query("SELECT * FROM config WHERE url = :url LIMIT 1")
    suspend fun find(url: String): Config?

    @Query("SELECT * FROM config WHERE url = :url LIMIT 1")
    fun observeByUrl(url: String): Flow<Config?>

    @Query("SELECT * FROM config WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): Config?

    @Query("UPDATE config SET name = :name WHERE url = :url")
    suspend fun updateName(url: String, name: String)

    @Query("UPDATE config SET home = :home WHERE url = :url")
    suspend fun updateHome(url: String, home: String)

    @Query("SELECT * FROM config ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Config>>

    @Query("SELECT * FROM config ORDER BY updatedAt DESC")
    suspend fun getAll(): List<Config>

    @Query("SELECT * FROM config ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): Config?

    @Query("DELETE FROM config WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<History>>

    @Query("SELECT * FROM history WHERE configId = :configId ORDER BY updatedAt DESC")
    fun observeByConfig(configId: String): Flow<List<History>>

    @Query("SELECT * FROM history WHERE `key` = :key")
    suspend fun get(key: String): History?

    @Query("SELECT * FROM history WHERE configId = :configId AND siteKey = :siteKey AND vodId = :vodId LIMIT 1")
    suspend fun find(configId: String, siteKey: String, vodId: String): History?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: History)

    @Query("DELETE FROM history WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM history WHERE configId = :configId")
    suspend fun clearByConfig(configId: String)

    @Query("DELETE FROM track_preference WHERE `key` IN (SELECT `key` FROM history WHERE configId = :configId) OR `key` LIKE :configId || '\\_%' ESCAPE '\\'")
    suspend fun clearTrackPreferencesByConfig(configId: String)

    @Transaction
    suspend fun clearWithTracksByConfig(configId: String) {
        clearTrackPreferencesByConfig(configId)
        clearByConfig(configId)
    }

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM track_preference WHERE `key` = :key")
    suspend fun findByKey(key: String): List<TrackPreference>

    @Query("SELECT * FROM track_preference WHERE `key` = :key AND type = :type LIMIT 1")
    suspend fun find(key: String, type: Int): TrackPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: TrackPreference)

    @Query("DELETE FROM track_preference WHERE `key` = :key AND type = :type")
    suspend fun delete(key: String, type: Int)

    @Query("DELETE FROM track_preference WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM track_preference")
    suspend fun clear()
}

@Database(entities = [Config::class, Site::class, History::class, TrackPreference::class], version = 8, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun siteDao(): SiteDao
    abstract fun configDao(): ConfigDao
    abstract fun historyDao(): HistoryDao
    abstract fun trackDao(): TrackDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE config ADD COLUMN name TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE config ADD COLUMN home TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN configUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE history ADD COLUMN configName TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE history 
                    SET configUrl = (SELECT url FROM config ORDER BY updatedAt DESC LIMIT 1),
                        configName = (SELECT COALESCE(NULLIF(name, ''), url) FROM config ORDER BY updatedAt DESC LIMIT 1)
                    WHERE configUrl = '' AND (SELECT COUNT(*) FROM config) > 0
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN opening INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE history ADD COLUMN ending INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE config ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_preference` (
                        `key` TEXT NOT NULL,
                        `type` INTEGER NOT NULL,
                        `name` TEXT NOT NULL DEFAULT '',
                        `format` TEXT NOT NULL DEFAULT '',
                        `language` TEXT,
                        `label` TEXT,
                        `width` INTEGER NOT NULL DEFAULT 0,
                        `height` INTEGER NOT NULL DEFAULT 0,
                        `isDisabled` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`key`, `type`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE config ADD COLUMN id TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE config SET id = lower(hex(randomblob(16))) WHERE id = ''")
                db.execSQL("ALTER TABLE history ADD COLUMN configId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE history
                    SET configId = COALESCE(
                        (SELECT id FROM config WHERE config.url = history.configUrl LIMIT 1),
                        ''
                    )
                    WHERE configUrl != ''
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE track_preference
                    SET `key` = COALESCE(
                        (
                            SELECT h.configId || '_' || h.siteKey || '_' || h.vodId
                            FROM history h
                            WHERE h.`key` = track_preference.`key`
                              AND h.configId != ''
                            LIMIT 1
                        ),
                        `key`
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE history
                    SET `key` = configId || '_' || siteKey || '_' || vodId
                    WHERE configId != ''
                    """.trimIndent()
                )
            }
        }
    }
}
