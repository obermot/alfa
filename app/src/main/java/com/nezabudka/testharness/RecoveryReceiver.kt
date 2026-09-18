package com.nezabudka.testharness

import android.content.*

class RecoveryReceiver:BroadcastReceiver(){
 override fun onReceive(c:Context,i:Intent){
   val p=goAsync()
   Thread{
     runCatching{
       val now=System.currentTimeMillis()
       AlphaDatabase.get(c).reminders().active().forEach{r->if(r.dueAt>now)ReminderScheduler.schedule(c,r)}
     }
     p.finish()
   }.start()
 }
}
