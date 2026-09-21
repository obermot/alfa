package com.nezabudka.testharness

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reminderId = intent.getLongExtra("reminder_id", 0L)
        setContent {
            var selected by remember { mutableIntStateOf(5) }
            var custom by remember { mutableStateOf(false) }
            var hours by remember { mutableIntStateOf(2) }
            var minutes by remember { mutableIntStateOf(30) }
            MaterialTheme {
                Scaffold { pad ->
                    Column(
                        Modifier.fillMaxSize().padding(pad).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { finish() }) { Text("←") }
                            Text("Напомнить снова", style = MaterialTheme.typography.titleLarge)
                        }
                        listOf(5 to "Через 5 минут", 10 to "Через 10 минут", 30 to "Через 30 минут", 60 to "Через 1 час").forEach { (value,label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = !custom && selected == value, onClick = { custom=false; selected=value })
                                Text(label)
                            }
                            HorizontalDivider()
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = custom, onClick = { custom=true })
                            Text("Другое время")
                        }
                        if (custom) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TextButton(onClick={ hours=(hours+1)%24 }) { Text("⌃") }
                                    Text("%02d".format(hours), style=MaterialTheme.typography.headlineLarge)
                                    TextButton(onClick={ hours=(hours+23)%24 }) { Text("⌄") }
                                }
                                Text(" : ", style=MaterialTheme.typography.headlineLarge)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TextButton(onClick={ minutes=(minutes+5)%60 }) { Text("⌃") }
                                    Text("%02d".format(minutes), style=MaterialTheme.typography.headlineLarge)
                                    TextButton(onClick={ minutes=(minutes+55)%60 }) { Text("⌄") }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                val value = if (custom) (hours*60+minutes).coerceAtLeast(1) else selected
                                sendBroadcast(Intent(this@SnoozeActivity, ReminderActionReceiver::class.java)
                                    .setAction(ReminderActionReceiver.ACTION_SNOOZE)
                                    .putExtra("reminder_id", reminderId)
                                    .putExtra("snooze_minutes", value))
                                finish()
                            },
                            modifier=Modifier.fillMaxWidth().height(54.dp)
                        ) { Text("Готово") }
                    }
                }
            }
        }
    }
}
