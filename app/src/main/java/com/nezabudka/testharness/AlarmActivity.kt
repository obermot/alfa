package com.nezabudka.testharness

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private var autoRetryCount = 0
    private val handler = Handler(Looper.getMainLooper())

    private val listenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_BEGIN_LISTEN) return
            val id = intent.getLongExtra(EXTRA_REMINDER_ID, 0L)
            if (id != reminderId || id <= 0L) return
            scheduleReadyListen(350L)
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
                            onClick = {
                                markReadyLocally()
                                autoRetryCount = 0
                                scheduleReadyListen(150L)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Ответить голосом") }
                        Spacer(Modifier.height(12.dp))
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
                registerReceiver(listenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(listenReceiver, filter)
            }
            receiverRegistered = true
        }
        scheduleReadyListen(350L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleReadyListen(250L)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        stopRecognizer()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(listenReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun readyPrefs() = getSharedPreferences(READY_PREFS, MODE_PRIVATE)

    private fun isQuestionReady(): Boolean =
        reminderId > 0L && readyPrefs().getBoolean(reminderId.toString(), false)

    private fun markReadyLocally() {
        if (reminderId > 0L) {
            readyPrefs().edit().putBoolean(reminderId.toString(), true).apply()
        }
    }

    private fun scheduleReadyListen(delayMs: Long) {
        if (!isQuestionReady()) return
        handler.removeCallbacksAndMessages(LISTEN_TOKEN)
        handler.postAtTime(
            { consumeReadyAndListen() },
            LISTEN_TOKEN,
            SystemClock.uptimeMillis() + delayMs
        )
    }

    private fun consumeReadyAndListen() {
        if (!isQuestionReady() || recognizer != null) return
        if (!hasWindowFocus()) {
            status = "Готова слушать ответ…"
            return
        }
        startListeningForAnswer()
    }

    private fun startListeningForAnswer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Нет доступа к микрофону. Нажмите «Услышал / выключить»."
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
        status = "Включаю микрофон…"
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        }
        runCatching { recognizer?.startListening(speechIntent) }
            .onFailure {
                stopRecognizer()
                status = "Не удалось включить микрофон. Нажмите «Ответить голосом»."
            }
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

    private fun clearReadyFlag() {
        if (reminderId > 0L) {
            readyPrefs().edit().remove(reminderId.toString()).apply()
        }
    }

    private fun acknowledge() {
        clearReadyFlag()
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
        clearReadyFlag()
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

    override fun onReadyForSpeech(params: Bundle?) {
        if (reminderId > 0L) {
            readyPrefs().edit().remove(reminderId.toString()).apply()
        }
        status = "Слушаю ответ…"
    }

    override fun onBeginningOfSpeech() {
        status = "Слышу вас…"
    }

    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        status = "Распознаю ответ…"
    }

    override fun onError(error: Int) {
        stopRecognizer()
        if (autoRetryCount < 1 && hasWindowFocus()) {
            autoRetryCount++
            markReadyLocally()
            status = "Не расслышала. Пробую ещё раз…"
            scheduleReadyListen(700L)
        } else {
            autoRetryCount = 0
            status = "Не расслышала. Нажмите «Ответить голосом» и повторите."
        }
    }

    override fun onResults(results: Bundle?) {
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        stopRecognizer()
        if (candidates.any(::isAcknowledgement)) {
            acknowledge()
        } else {
            autoRetryCount = 0
            status = if (candidates.isEmpty()) {
                "Не расслышала. Нажмите «Ответить голосом» и повторите."
            } else {
                "Не поняла: «${candidates.first()}». Повторите или нажмите «Услышал / выключить»."
            }
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val heard = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (heard.isNotBlank()) status = "Слышу: $heard"
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopRecognizer()
        super.onDestroy()
    }

    companion object {
        const val ACTION_BEGIN_LISTEN = "com.nezabudka.alpha.BEGIN_ALARM_LISTEN"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
        private const val READY_PREFS = "alarm_question_ready"
        private val LISTEN_TOKEN = Any()

        fun markQuestionReady(context: Context, reminderId: Long) {
            if (reminderId <= 0L) return
            context.getSharedPreferences(READY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(reminderId.toString(), true)
                .apply()
            context.sendBroadcast(
                Intent(ACTION_BEGIN_LISTEN)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_REMINDER_ID, reminderId)
            )
        }
    }
}
