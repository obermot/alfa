package com.nezabudka.testharness

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val dueAt: Long,
    val originalDueAt: Long,
    val recurrenceMinutes: Long? = null,
    @ColumnInfo(defaultValue = "10") val repeatIntervalMinutes: Int = 10,
    val active: Boolean = true,
    val acknowledged: Boolean = false,
    val lastFiredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ReminderDao {
    @Insert fun insert(item: ReminderEntity): Long
    @Update fun update(item: ReminderEntity)
    @Delete fun delete(item: ReminderEntity)
    @Query("DELETE FROM reminders") fun deleteAll()
    @Query("SELECT * FROM reminders WHERE id=:id") fun get(id: Long): ReminderEntity?
    @Query("SELECT * FROM reminders WHERE active=1 ORDER BY dueAt") fun active(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE active=1 AND lastFiredAt IS NOT NULL ORDER BY lastFiredAt DESC LIMIT 1") fun latestFired(): ReminderEntity?
}

@Database(entities=[ReminderEntity::class], version=2, exportSchema=false)
abstract class AlphaDatabase: RoomDatabase() {
    abstract fun reminders(): ReminderDao

    companion object {
        @Volatile private var INSTANCE: AlphaDatabase? = null

        fun get(context: Context): AlphaDatabase = INSTANCE ?: synchronized(this) {
            val appContext = context.applicationContext
            val legacyRepeat = ReminderRepeatSettings.getMinutes(appContext).coerceAtLeast(0)
            val migration1To2 = object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE reminders ADD COLUMN repeatIntervalMinutes INTEGER NOT NULL DEFAULT 10"
                    )
                    database.execSQL(
                        "UPDATE reminders SET repeatIntervalMinutes = $legacyRepeat WHERE active = 1"
                    )
                }
            }
            INSTANCE ?: Room.databaseBuilder(appContext, AlphaDatabase::class.java, "nezabudka_alpha.db")
                .addMigrations(migration1To2)
                .build()
                .also { INSTANCE = it }
        }
    }
}
