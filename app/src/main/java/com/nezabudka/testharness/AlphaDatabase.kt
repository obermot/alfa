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

@Entity(tableName = "history_events", indices = [Index("reminderId"), Index("occurredAt")])
data class HistoryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val reminderText: String,
    val scheduledAt: Long,
    val occurredAt: Long = System.currentTimeMillis(),
    val status: String
) {
    companion object {
        const val COMPLETED = "completed"
        const val MISSED = "missed"
        const val CANCELLED = "cancelled"
    }
}

@Dao
interface ReminderDao {
    @Insert fun insert(item: ReminderEntity): Long
    @Update fun update(item: ReminderEntity)
    @Delete fun delete(item: ReminderEntity)
    @Query("DELETE FROM reminders") fun deleteAll()
    @Query("SELECT * FROM reminders WHERE id=:id") fun get(id: Long): ReminderEntity?
    @Query("SELECT * FROM reminders WHERE active=1 ORDER BY dueAt") fun active(): List<ReminderEntity>
    @Query("SELECT * FROM reminders ORDER BY dueAt") fun all(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE active=1 AND lastFiredAt IS NOT NULL ORDER BY lastFiredAt DESC LIMIT 1") fun latestFired(): ReminderEntity?
}

@Dao
interface HistoryDao {
    @Insert fun insert(item: HistoryEventEntity): Long
    @Query("SELECT * FROM history_events ORDER BY occurredAt DESC") fun all(): List<HistoryEventEntity>
    @Query("DELETE FROM history_events") fun deleteAll()
}

@Database(entities=[ReminderEntity::class, HistoryEventEntity::class], version=3, exportSchema=false)
abstract class AlphaDatabase: RoomDatabase() {
    abstract fun reminders(): ReminderDao
    abstract fun history(): HistoryDao

    companion object {
        @Volatile private var INSTANCE: AlphaDatabase? = null

        fun get(context: Context): AlphaDatabase = INSTANCE ?: synchronized(this) {
            val appContext = context.applicationContext
            val legacyRepeat = ReminderRepeatSettings.getMinutes(appContext).coerceAtLeast(0)
            val migration1To2 = object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE reminders ADD COLUMN repeatIntervalMinutes INTEGER NOT NULL DEFAULT 10")
                    database.execSQL("UPDATE reminders SET repeatIntervalMinutes = $legacyRepeat WHERE active = 1")
                }
            }
            val migration2To3 = object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("CREATE TABLE IF NOT EXISTS history_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, reminderId INTEGER NOT NULL, reminderText TEXT NOT NULL, scheduledAt INTEGER NOT NULL, occurredAt INTEGER NOT NULL, status TEXT NOT NULL)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_history_events_reminderId ON history_events(reminderId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_history_events_occurredAt ON history_events(occurredAt)")
                }
            }
            INSTANCE ?: Room.databaseBuilder(appContext, AlphaDatabase::class.java, "nezabudka_alpha.db")
                .addMigrations(migration1To2, migration2To3)
                .build()
                .also { INSTANCE = it }
        }
    }
}
