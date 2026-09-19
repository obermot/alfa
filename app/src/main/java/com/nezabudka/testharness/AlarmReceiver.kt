package com.nezabudka.testharness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object ReminderNotifications {
    private const val CHANNEL_ID = "nezabudka_active_reminders"

    private fun notificationId(id: Long): Int = ((id % 1_000_000L).toInt().coerceAtLeast(1))

    fun show(context: Context, reminder: ReminderEntity) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Активные напоминания Незабудки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Напоминания, которые ждут подтверждения пользователя"
                enableVibration(true)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        val alarmIntent = Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AlarmActivity.EXTRA_REMINDER_ID, reminder.id)
            .putExtra(AlarmActivity.EXTRA_REMINDER_TEXT, reminder.text)
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId(reminder.id) + 10_000,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ackIntent = PendingIntent.getBroadcast(
            context,
            notificationId(reminder.id) + 20_000,
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(ReminderActionReceiver.ACTION_ACK)
                .putExtra("reminder_id", reminder.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = PendingIntent.getBroadcast(
            context,
            notificationId(reminder.id) + 30_000,
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(ReminderActionReceiver.ACTION_SNOOZE)
                .putExtra("reminder_id", reminder.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val count = fireCount(context, reminder.id)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Незабудка напомнила${if (count > 0) " ($count раз)" else ""}")
            .setContentText(reminder.text)
            .setStyle(Notification.BigTextStyle().bigText(reminder.text))
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Услышал(а)", ackIntent).build())
            .addAction(Notification.Action.Builder(null, "+10 минут", snoozeIntent).build())
            .build()

        runCatching { nm.notify(notificationId(reminder.id), notification) }
    }

    fun incrementFireCount(context: Context, id: Long): Int {
        val p = context.getSharedPreferences("reminder_fire_counts", Context.MODE_PRIVATE)
        val n = p.getInt(id.toString(), 0) + 1
        p.edit().putInt(id.toString(), n).apply()
        return n
    }

    fun fireCount(context: Context, id: Long): Int =
        context.getSharedPreferences("reminder_fire_counts", Context.MODE_PRIVATE).getInt(id.toString(), 0)

    fun cancel(context: Context, id: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notificationId(id))
        context.getSharedPreferences("reminder_fire_counts", Context.MODE_PRIVATE)
            .edit()
            .remove(id.toString())
            .apply()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("reminder_id", 0)
        if (id <= 0) return

        val pending = goAsync()
        Thread {
            try {
                val dao = AlphaDatabase.get(context).reminders()
                val reminder = dao.get(id)
                if (reminder == null || !reminder.active) {
                    pending.finish()
                    return@Thread
                }

                val firedAt = System.currentTimeMillis()
                val fired = reminder.copy(lastFiredAt = firedAt)
                dao.update(fired)
                ReminderNotifications.incrementFireCount(context, id)
                ReminderNotifications.show(context, fired)

                speak(context, fired.text) {
                    context.sendBroadcast(
                        Intent(AlarmActivity.ACTION_BEGIN_LISTEN)
                            .setPackage(context.packageName)
                            .putExtra(AlarmActivity.EXTRA_REMINDER_ID, id)
                    )
                    Thread {
                        try {
                            val fresh = dao.get(id)
                            if (fresh != null && fresh.active && fresh.lastFiredAt == firedAt) {
                                val retry = fresh.copy(
                                    dueAt = System.currentTimeMillis() + 10 * 60_000L,
                                    acknowledged = false
                                )
                                dao.update(retry)
                                ReminderScheduler.schedule(context, retry)
                            }
                        } finally {
                            pending.finish()
                        }
                    }.start()
                }
            } catch (_: Throwable) {
                pending.finish()
            }
        }.start()
    }

    private fun humanReminderText(text: String): String {
        val t = text.trim().lowercase(Locale("ru"))
        return when {
            t == "разбудить" || t == "разбуди" || t.startsWith("разбудить ") ->
                "Просыпайтесь. Пора вставать."
            t.startsWith("позвонить ") ->
                "Напоминаю: ${text.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }}."
            t.startsWith("купить ") || t.startsWith("сделать ") || t.startsWith("принять ") ->
                "Напоминаю: ${text.trim()}."
            else -> "Напоминаю: ${text.trim()}."
        }
    }

    private fun speak(context: Context, text: String, done: () -> Unit) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var focusRequest: AudioFocusRequest? = null
        val focusGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audio.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else false

        val completed = AtomicBoolean(false)
        var tts: TextToSpeech? = null
        var ringtone: Ringtone? = null

        fun finishOnce() {
            if (!completed.compareAndSet(false, true)) return
            runCatching { ringtone?.stop() }
            runCatching { tts?.shutdown() }
            if (focusGranted && focusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { audio.abandonAudioFocusRequest(focusRequest!!) }
            }
            done()
        }

        val name = context.getSharedPreferences("nezabudka_user", Context.MODE_PRIVATE)
            .getString("user_name", "")
            .orEmpty()
            .trim()
        val spoken = buildString {
            if (name.isNotBlank()) append(name).append(". ")
            append(humanReminderText(text))
            append(" Вы услышали?")
        }
        val utteranceId = "reminder-${System.currentTimeMillis()}"

        val handler = Handler(Looper.getMainLooper())
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            if (Build.VERSION.SDK_INT >= 28) ringtone?.isLooping = false
            ringtone?.play()
        }

        handler.postDelayed({
            runCatching { ringtone?.stop() }
            tts = TextToSpeech(context.applicationContext) { result ->
                if (result == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("ru")
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = finishOnce()
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = finishOnce()
                    })
                    val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
                    tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                } else finishOnce()
            }
        }, 1800L)

        handler.postDelayed({ finishOnce() }, 35_000L)
    }
}
