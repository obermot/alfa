package com.nezabudka.testharness

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class AlarmActivity : ComponentActivity(), RecognitionListener {
    private var reminderId: Long = 0L
    private var reminderText by mutableStateOf("")
    private var status by mutableStateOf("Напоминание")
    private var recognizer: SpeechRecognizer? = null
    private var receiverRegistered = false

    private val listenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_BEGIN_LISTEN) return
            val id = intent.getLongExtra(EXTRA_REMINDER_ID, 0L)
            if (id != reminderId || id <= 0L) return
            startListeningForAnswer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, 0L)
        reminderText = intent.getStringExtra(EXTRA_REMINDER_TEXT).orEmpty()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Незабудка",
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = reminderText.ifBlank { "Напоминание" },
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = { acknowledge() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Услышал / выключить") }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { snooze() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("+10 минут") }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(ACTION_BEGIN_LISTEN)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(listenReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(listenReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onStop() {
        stopRecognizer()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(listenReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun startListeningForAnswer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Нажмите «Услышал / выключить»"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status = "Голосовой ответ недоступен. Используйте кнопку."
            return
        }

        stopRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }
        status = "Слушаю ответ…"
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { recognizer?.startListening(speechIntent) }
            .onFailure { status = "Не удалось включить микрофон. Используйте кнопку." }
    }

    private fun isAcknowledgement(text: String): Boolean {
        val s = text.lowercase().replace('ё', 'е').trim()
        val phrases = listOf(
            "да", "услышал", "услышала", "понял", "поняла", "хорошо", "ладно",
            "готово", "сделал", "сделала", "все", "хватит", "отмени", "отмена",
            "не напоминай", "можно не напоминать", "я встал", "я встала",
            "я уже встал", "я уже встала"
        )
        return phrases.any { p -> s == p || s.contains(p) }
    }

    private fun acknowledge() {
        if (reminderId <= 0L) {
            finish()
            return
        }
        sendBroadcast(
            Intent(this, ReminderActionReceiver::class.java)
                .setAction(ReminderActionReceiver.ACTION_ACK)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
        )
        finishAndRemoveTask()
    }

    private fun snooze() {
        if (reminderId <= 0L) {
            finish()
            return
        }
        sendBroadcast(
            Intent(this, ReminderActionReceiver::class.java)
                .setAction(ReminderActionReceiver.ACTION_SNOOZE)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
        )
        finishAndRemoveTask()
    }

    private fun stopRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        stopRecognizer()
        status = "Не расслышала. Скажите ещё раз или нажмите кнопку."
    }

    override fun onResults(results: Bundle?) {
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        stopRecognizer()
        if (candidates.any(::isAcknowledgement)) {
            acknowledge()
        } else {
            status = if (candidates.isEmpty()) {
                "Не расслышала. Нажмите кнопку или попробуйте ещё раз."
            } else {
                "Не поняла ответ. Нажмите «Услышал / выключить» или скажите подтверждение ещё раз."
            }
        }
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        stopRecognizer()
        super.onDestroy()
    }

    companion object {
        const val ACTION_BEGIN_LISTEN = "com.nezabudka.alpha.BEGIN_ALARM_LISTEN"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
    }
}
