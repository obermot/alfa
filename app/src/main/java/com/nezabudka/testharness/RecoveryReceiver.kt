package com.nezabudka.testharness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                AlphaDatabase.get(context).reminders().active().forEach { reminder ->
                    ReminderScheduler.schedule(context, reminder)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
