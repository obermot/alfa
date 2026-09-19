package com.nezabudka.testharness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object ReminderRepeatSettings {
    private const val PREFS = "nezabudka_user"
    private const val KEY_REPEAT_MINUTES = "repeat_interval_minutes"
    const val DEFAULT_MINUTES = 10

    fun getMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_REPEAT_MINUTES, DEFAULT_MINUTES)
            .coerceAtLeast(1)

    fun setMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_REPEAT_MINUTES, minutes.coerceAtLeast(1))
            .apply()
    }

    fun label(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "+${h} ч ${m} мин"
            h > 0 -> "+${h} ч"
            else -> "+${m} мин"
        }
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACK = "com.nezabudka.testharness.ACTION_ACK"
        const val ACTION_SNOOZE = "com.nezabudka.testharness.ACTION_SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("reminder_id", 0L)
        if (id <= 0L) return

        val pending = goAsync()
        Thread {
            try {
                val dao = AlphaDatabase.get(context).reminders()
                val reminder = dao.get(id) ?: return@Thread
                when (intent.action) {
                    ACTION_ACK -> acknowledge(context, dao, reminder)
                    ACTION_SNOOZE -> snooze(context, dao, reminder)
                }
            } finally {
                ReminderNotifications.cancel(context, id)
                pending.finish()
            }
        }.start()
    }

    private fun acknowledge(context: Context, dao: ReminderDao, reminder: ReminderEntity) {
        ReminderScheduler.cancel(context, reminder.id)
        val recurrence = reminder.recurrenceMinutes
        if (recurrence == null) {
            dao.update(reminder.copy(active = false, acknowledged = true))
            return
        }

        val step = recurrence * 60_000L
        var nextDue = reminder.originalDueAt + step
        val now = System.currentTimeMillis()
        while (nextDue <= now) nextDue += step
        val next = reminder.copy(
            dueAt = nextDue,
            originalDueAt = nextDue,
            lastFiredAt = null,
            acknowledged = false,
            active = true
        )
        dao.update(next)
        ReminderScheduler.schedule(context, next)
    }

    private fun snooze(context: Context, dao: ReminderDao, reminder: ReminderEntity) {
        ReminderScheduler.cancel(context, reminder.id)
        val repeatMinutes = ReminderRepeatSettings.getMinutes(context)
        val next = reminder.copy(
            dueAt = System.currentTimeMillis() + repeatMinutes * 60_000L,
            lastFiredAt = null,
            acknowledged = false,
            active = true
        )
        dao.update(next)
        ReminderScheduler.schedule(context, next)
    }
}
