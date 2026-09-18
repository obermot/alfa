package com.nezabudka.testharness

import android.app.*
import android.content.*
import android.os.Build

object ReminderScheduler {
    fun schedule(context:Context,item:ReminderEntity):Boolean{
        val am=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if(Build.VERSION.SDK_INT>=31 && !am.canScheduleExactAlarms()) return false
        val i=Intent(context,AlarmReceiver::class.java).putExtra("reminder_id",item.id)
        val pi=PendingIntent.getBroadcast(context,requestCode(item.id),i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,item.dueAt,pi)
        return true
    }
    fun cancel(context:Context,id:Long){
        val am=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi=PendingIntent.getBroadcast(context,requestCode(id),Intent(context,AlarmReceiver::class.java),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if(pi!=null){am.cancel(pi);pi.cancel()}
    }
    private fun requestCode(id:Long)=((id%Int.MAX_VALUE).toInt().coerceAtLeast(1))
}
