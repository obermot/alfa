package com.nezabudka.testharness

import android.content.Context
import androidx.room.*

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val dueAt: Long,
    val originalDueAt: Long,
    val recurrenceMinutes: Long? = null,
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

@Database(entities=[ReminderEntity::class], version=1, exportSchema=false)
abstract class AlphaDatabase: RoomDatabase() {
    abstract fun reminders(): ReminderDao
    companion object {
        @Volatile private var INSTANCE: AlphaDatabase? = null
        fun get(context: Context): AlphaDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AlphaDatabase::class.java, "nezabudka_alpha.db").build().also { INSTANCE = it }
        }
    }
}
