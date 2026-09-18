package com.nezabudka.testharness

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity(), RecognitionListener {
    private var status by mutableStateOf("")
    private var reminders by mutableStateOf<List<ReminderEntity>>(emptyList())
    private var listening by mutableStateOf(false)
    private var modelReady by mutableStateOf(false)
    private var userName by mutableStateOf("")

    private var pendingText: String? = null
    private var pendingDate: LocalDate? = null
    private var captureNameMode = false

    private var systemRecognizer: SpeechRecognizer? = null
    private var ignoreVoiceCallbacks = false

    private var model: Model? = null
    private var voskSpeech: SpeechService? = null
    private var lastPartialText = ""

    private val prefs by lazy { getSharedPreferences("nezabudka_user", MODE_PRIVATE) }
    private val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startListen() else status = "Нет доступа к микрофону"
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userName = prefs.getString("user_name", "").orEmpty()
        modelReady = modelDir().exists() && !modelDir().list().isNullOrEmpty()
        status = if (userName.isBlank()) "Как к вам обращаться? Можно написать имя или нажать 🎤." else ""
        refresh()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { App() }
    }

    @Composable
    private fun App() {
        var text by remember { mutableStateOf("") }
        var nameDraft by remember { mutableStateOf(userName) }

        LaunchedEffect(userName) {
            if (nameDraft != userName) nameDraft = userName
        }

        MaterialTheme {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Незабудка", style = MaterialTheme.typography.headlineMedium)
                if (status.isNotBlank()) Text(status)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!modelReady) {
                        Button(onClick = { ensureModel() }) { Text("Установить русский язык") }
                    }
                    Button(onClick = { if (listening) stopListen(clearStatus = true) else startListen() }) {
                        Text(if (listening) "Стоп" else "Говорить")
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(if (pendingDate != null) "Ответьте на уточнение" else "Напишите напоминание") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (text.isNotBlank()) {
                            val accepted = handle(text, fromVoice = false)
                            if (accepted) text = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (pendingDate != null) "Ответить" else "Создать") }

                HorizontalDivider()

                Text("Как к вам обращаться", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            captureNameMode = true
                            startListen()
                        }) { Text("🎤") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { if (nameDraft.isNotBlank()) saveName(nameDraft) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить имя") }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Активные напоминания", style = MaterialTheme.typography.titleMedium)
                    if (reminders.isNotEmpty()) {
                        TextButton(onClick = { deleteAllReminders() }) { Text("Удалить все") }
                    }
                }

                LazyColumn(Modifier.weight(1f)) {
                    items(reminders, key = { it.id }) { r ->
                        val fireCount = ReminderNotifications.fireCount(this@MainActivity, r.id)
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(r.text)
                                Text(format(r.dueAt))
                                if (fireCount > 0) Text("Напомнила: $fireCount раз")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (r.lastFiredAt != null) {
                                        TextButton(onClick = { ack(r) }) { Text("Услышал(а)") }
                                    }
                                    TextButton(onClick = { snooze(r, 10) }) { Text("+10 мин") }
                                    TextButton(onClick = { deleteReminder(r) }) { Text("Удалить") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun modelDir() = File(filesDir, "models/ru")

    private fun ensureModel() {
        if (modelDir().exists() && !modelDir().list().isNullOrEmpty()) {
            modelReady = true
            status = if (userName.isBlank()) "Как к вам обращаться? Можно написать имя или нажать 🎤." else ""
            return
        }
        status = "Устанавливаю русский язык…"
        Thread {
            try {
                val d = modelDir()
                d.deleteRecursively()
                d.mkdirs()
                val z = File(cacheDir, "ru.zip")
                val c = URL(modelUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000
                c.readTimeout = 180_000
                c.inputStream.use { i -> FileOutputStream(z).use { o -> i.copyTo(o) } }
                c.disconnect()
                unzip(z, d)
                z.delete()
                runOnUiThread {
                    modelReady = true
                    status = if (userName.isBlank()) "Как к вам обращаться? Можно написать имя или нажать 🎤." else ""
                }
            } catch (e: Exception) {
                runOnUiThread { status = "Ошибка установки языка: ${e.message}" }
            }
        }.start()
    }

    private fun unzip(zip: File, target: File) {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val p = e.name.substringAfter('/', "")
                if (p.isNotEmpty()) {
                    val out = File(target, p)
                    val root = target.canonicalPath + File.separator
                    if (!out.canonicalPath.startsWith(root)) throw SecurityException("Bad zip entry")
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { z.copyTo(it) }
                    }
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
    }

    private fun startListen() {
        if (listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        ignoreVoiceCallbacks = false
        lastPartialText = ""
        if (SpeechRecognizer.isRecognitionAvailable(this)) startSystemRecognition() else startVoskRecognition()
    }

    private fun startSystemRecognition() {
        try {
            if (systemRecognizer == null) {
                systemRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
            }
            listening = true
            status = when {
                captureNameMode || userName.isBlank() -> "Скажите имя…"
                pendingDate != null -> "Слушаю ответ…"
                else -> "Слушаю…"
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            systemRecognizer?.startListening(intent)
        } catch (_: Throwable) {
            startVoskRecognition()
        }
    }

    private fun startVoskRecognition() {
        if (!modelReady) {
            status = "Для резервного голосового ввода установите русский язык"
            return
        }
        Thread {
            try {
                if (model == null) model = Model(modelDir().absolutePath)
                val service = SpeechService(Recognizer(model, 16000f), 16000f)
                voskSpeech = service
                service.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(hypothesis: String) {
                        if (!listening || ignoreVoiceCallbacks) return
                        val t = runCatching { JSONObject(hypothesis).optString("partial") }.getOrDefault("").trim()
                        if (t.isNotBlank()) {
                            lastPartialText = t
                            runOnUiThread { if (listening) status = "Слышу: $t" }
                        }
                    }
                    override fun onResult(hypothesis: String) {
                        if (!listening || ignoreVoiceCallbacks) return
                        val t = runCatching { JSONObject(hypothesis).optString("text") }.getOrDefault("").trim()
                        if (t.isNotBlank()) runOnUiThread { acceptVoiceCandidates(listOf(t)) }
                    }
                    override fun onFinalResult(hypothesis: String) {
                        if (!listening || ignoreVoiceCallbacks) return
                        val t = runCatching { JSONObject(hypothesis).optString("text") }.getOrDefault("").trim()
                        runOnUiThread {
                            if (t.isNotBlank()) acceptVoiceCandidates(listOf(t))
                            else if ((captureNameMode || userName.isBlank()) && lastPartialText.isNotBlank()) acceptVoiceCandidates(listOf(lastPartialText))
                            else finishListeningSilently()
                        }
                    }
                    override fun onError(exception: Exception) { runOnUiThread { voiceFailure() } }
                    override fun onTimeout() { runOnUiThread { voiceFailure() } }
                })
                runOnUiThread {
                    listening = true
                    status = when {
                        captureNameMode || userName.isBlank() -> "Скажите имя…"
                        pendingDate != null -> "Слушаю ответ…"
                        else -> "Слушаю…"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { voiceFailure() }
            }
        }.start()
    }

    private fun stopListen(clearStatus: Boolean = false) {
        ignoreVoiceCallbacks = true
        listening = false
        captureNameMode = false
        runCatching { systemRecognizer?.cancel() }
        runCatching { voskSpeech?.stop() }
        runCatching { voskSpeech?.shutdown() }
        voskSpeech = null
        lastPartialText = ""
        if (clearStatus) status = if (userName.isBlank()) "Как к вам обращаться? Можно написать имя или нажать 🎤." else ""
    }

    private fun finishListeningSilently() {
        ignoreVoiceCallbacks = true
        listening = false
        captureNameMode = false
        runCatching { systemRecognizer?.cancel() }
        runCatching { voskSpeech?.shutdown() }
        voskSpeech = null
        lastPartialText = ""
        if (status.startsWith("Слышу:") || status.startsWith("Слушаю") || status.startsWith("Скажите имя")) {
            status = if (userName.isBlank()) "Как к вам обращаться? Можно написать имя или нажать 🎤." else ""
        }
    }

    private fun acceptVoiceCandidates(candidates: List<String>) {
        if (!listening || ignoreVoiceCallbacks) return
        val clean = candidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            voiceFailure()
            return
        }
        val selected = chooseBestCandidate(clean)
        val accepted = handle(selected, fromVoice = true)
        if (accepted) finishListeningSilently()
    }

    private fun chooseBestCandidate(candidates: List<String>): String {
        if (captureNameMode || userName.isBlank()) return candidates.first()
        val ackWords = listOf("сделал", "сделано", "готово", "услышал", "услышала", "понял", "поняла", "хватит", "отмени", "не напоминай", "я встал", "я встала")
        candidates.firstOrNull { c -> ackWords.any { c.lowercase().replace('ё', 'е').contains(it) } }?.let { return it }
        val pd = pendingDate
        if (pd != null) candidates.firstOrNull { TemporalParser.parseTimeAnswer(it, pd) != null }?.let { return it }
        candidates.firstOrNull { TemporalParser.parseCommand(it) !is ParseResult.Invalid }?.let { return it }
        return candidates.first()
    }

    private fun voiceFailure() {
        if (ignoreVoiceCallbacks) return
        listening = false
        ignoreVoiceCallbacks = true
        val wasName = captureNameMode || userName.isBlank()
        captureNameMode = false
        status = if (wasName) "Имя не расслышала. Можно нажать 🎤 ещё раз или написать." else "Не расслышала. Можно повторить или написать."
    }

    private fun handle(raw: String, fromVoice: Boolean): Boolean {
        if (captureNameMode || userName.isBlank()) {
            saveName(raw)
            return true
        }

        val lower = raw.lowercase().replace('ё', 'е')
        val ackWords = listOf(
            "сделал", "сделано", "готово", "услышал", "услышала", "понял", "поняла",
            "хватит", "отмени", "не напоминай", "я встал", "я встала"
        )
        if (ackWords.any { lower.contains(it) }) {
            ackLatest()
            return true
        }
        if (lower.startsWith("отложи") || lower.startsWith("перенеси") || lower.contains("напомни позже")) {
            rescheduleLatest(raw)
            return true
        }

        val pd = pendingDate
        if (pd != null) {
            val at = TemporalParser.parseTimeAnswer(raw, pd)
            return if (at != null) {
                val savedText = pendingText ?: "напоминание"
                pendingDate = null
                pendingText = null
                createReminder(savedText, at, null)
                true
            } else {
                ask("Не поняла время. Скажите или напишите, например: в шесть утра.")
                false
            }
        }

        return when (val p = TemporalParser.parseCommand(raw)) {
            is ParseResult.Ready -> {
                createReminder(p.text, p.dueAt, p.recurrenceMinutes)
                true
            }
            is ParseResult.NeedTime -> {
                pendingText = p.text
                pendingDate = p.date
                ask("Во сколько напомнить?")
                true
            }
            is ParseResult.Invalid -> {
                if (fromVoice) status = "Не уверена, что правильно расслышала. Можно повторить или написать."
                else status = p.reason
                false
            }
        }
    }

    private fun saveName(raw: String) {
        val cleaned = raw
            .lowercase()
            .replace(Regex("^(меня зовут|зови меня|обращайся ко мне)\\s+"), "")
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(40)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
        if (cleaned.isBlank()) {
            status = "Имя не указано. Можно сказать его через 🎤 или написать."
            return
        }
        userName = cleaned
        captureNameMode = false
        prefs.edit().putString("user_name", cleaned).apply()
        status = ""
        speakReply("Хорошо, $cleaned.")
    }

    private fun createReminder(text: String, due: Long, rec: Long?) {
        Thread {
            val dao = AlphaDatabase.get(this).reminders()
            val id = dao.insert(ReminderEntity(text = text, dueAt = due, originalDueAt = due, recurrenceMinutes = rec))
            val r = dao.get(id)!!
            val ok = ReminderScheduler.schedule(this, r)
            runOnUiThread {
                refresh()
                if (ok) {
                    status = ""
                    speakReply("Принято. Напомню ${formatSpoken(due)}.")
                } else {
                    ask("Напоминание сохранено, но нужно разрешить точные напоминания.")
                    openExactAlarmSettings()
                }
            }
        }.start()
    }

    private fun ask(message: String) {
        status = message
        speakReply(message)
    }

    private fun speakReply(message: String, after: () -> Unit = {}) {
        var engine: TextToSpeech? = null
        val utteranceId = "dialog-${UUID.randomUUID()}"
        engine = TextToSpeech(applicationContext) { result ->
            if (result != TextToSpeech.SUCCESS) {
                runOnUiThread { after() }
                return@TextToSpeech
            }
            engine?.language = Locale("ru")
            engine?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(id: String?) {
                    engine?.shutdown()
                    runOnUiThread { after() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    engine?.shutdown()
                    runOnUiThread { after() }
                }
            })
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
            engine?.speak(message, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    private fun ack(r: ReminderEntity) {
        Thread {
            val dao = AlphaDatabase.get(this).reminders()
            ReminderScheduler.cancel(this, r.id)
            if (r.recurrenceMinutes != null) {
                var nextDue = r.originalDueAt + r.recurrenceMinutes * 60_000L
                val step = r.recurrenceMinutes * 60_000L
                while (nextDue <= System.currentTimeMillis()) nextDue += step
                val next = r.copy(dueAt = nextDue, originalDueAt = nextDue, lastFiredAt = null, acknowledged = false)
                dao.update(next)
                ReminderScheduler.schedule(this, next)
            } else {
                dao.update(r.copy(active = false, acknowledged = true))
            }
            ReminderNotifications.cancel(this, r.id)
            runOnUiThread { refresh(); status = "" }
        }.start()
    }

    private fun snooze(r: ReminderEntity, min: Int) {
        Thread {
            ReminderScheduler.cancel(this, r.id)
            val n = r.copy(dueAt = System.currentTimeMillis() + min * 60_000L, lastFiredAt = null, acknowledged = false)
            AlphaDatabase.get(this).reminders().update(n)
            ReminderScheduler.schedule(this, n)
            ReminderNotifications.cancel(this, r.id)
            runOnUiThread { refresh(); status = "" }
        }.start()
    }

    private fun deleteReminder(r: ReminderEntity) {
        Thread {
            ReminderScheduler.cancel(this, r.id)
            ReminderNotifications.cancel(this, r.id)
            AlphaDatabase.get(this).reminders().delete(r)
            runOnUiThread { refresh(); status = "" }
        }.start()
    }

    private fun deleteAllReminders() {
        Thread {
            val dao = AlphaDatabase.get(this).reminders()
            dao.active().forEach { r ->
                ReminderScheduler.cancel(this, r.id)
                ReminderNotifications.cancel(this, r.id)
            }
            dao.deleteAll()
            runOnUiThread { refresh(); status = "" }
        }.start()
    }

    private fun ackLatest() {
        Thread {
            val r = AlphaDatabase.get(this).reminders().latestFired()
            runOnUiThread {
                if (r == null) status = "Нет активного сработавшего напоминания" else ack(r)
            }
        }.start()
    }

    private fun rescheduleLatest(raw: String) {
        Thread {
            val r = AlphaDatabase.get(this).reminders().latestFired()
            if (r == null) {
                runOnUiThread { status = "Нет напоминания для переноса" }
                return@Thread
            }
            val tail = raw.substringAfter(' ', "")
            val p = TemporalParser.parseCommand("напомни ${r.text} $tail")
            if (p is ParseResult.Ready) {
                ReminderScheduler.cancel(this, r.id)
                val n = r.copy(dueAt = p.dueAt, lastFiredAt = null, acknowledged = false)
                AlphaDatabase.get(this).reminders().update(n)
                ReminderScheduler.schedule(this, n)
                ReminderNotifications.cancel(this, r.id)
                runOnUiThread {
                    refresh()
                    status = ""
                    speakReply("Перенесено. Напомню ${formatSpoken(n.dueAt)}.")
                }
            } else runOnUiThread { status = "Не поняла, на какое время перенести" }
        }.start()
    }

    private fun refresh() {
        Thread {
            val a = AlphaDatabase.get(this).reminders().active()
            runOnUiThread { reminders = a }
        }.start()
    }

    private fun format(ms: Long) = Instant.ofEpochMilli(ms)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))

    private fun formatSpoken(ms: Long): String {
        val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return z.format(DateTimeFormatter.ofPattern("dd.MM в HH:mm"))
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onError(error: Int) { runOnUiThread { voiceFailure() } }

    override fun onResults(results: Bundle?) {
        if (!listening || ignoreVoiceCallbacks) return
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        runOnUiThread { acceptVoiceCandidates(candidates) }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!listening || ignoreVoiceCallbacks) return
        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (t.isNotBlank()) {
            lastPartialText = t
            runOnUiThread { if (listening) status = "Слышу: $t" }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        ignoreVoiceCallbacks = true
        runCatching { systemRecognizer?.destroy() }
        systemRecognizer = null
        runCatching { voskSpeech?.shutdown() }
        runCatching { model?.close() }
        super.onDestroy()
    }
}
