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
import android.widget.NumberPicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    private var pendingDeleteAll = false

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
        status = if (userName.isBlank()) "Как к вам обращаться?" else ""
        refresh()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Composable
    private fun App() {
        var text by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf<ReminderEntity?>(null) }
        var section by remember { mutableStateOf("reminders") }
        var scheduleFor by remember { mutableStateOf<ReminderEntity?>(null) }

        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                when {
                    scheduleFor != null -> ScheduleScreen(scheduleFor!!, onBack = { scheduleFor = null })
                    selected != null -> ReminderEditor(
                        reminder = selected!!,
                        onBack = { selected = null },
                        onSchedule = { scheduleFor = selected },
                        onDelete = {
                            deleteReminder(selected!!)
                            selected = null
                        }
                    )
                    section == "history" -> HistoryScreen(section) { section = it }
                    section == "settings" -> SettingsScreen(section) { section = it }
                    section == "all" -> AllRemindersScreen(
                        onBack = { section = "reminders" },
                        onReminder = { selected = it },
                        section = "reminders",
                        onSection = { section = it }
                    )
                    else -> HomeScreen(
                        text = text,
                        onText = { text = it },
                        onSend = { if (text.isNotBlank() && handle(text, fromVoice = false)) text = "" },
                        onMic = { if (listening) stopListen(clearStatus = true) else startListen() },
                        onReminder = { selected = it },
                        onAll = { section = "all" },
                        section = section,
                        onSection = { section = it }
                    )
                }
            }
        }
    }

    @Composable
    private fun HomeScreen(
        text: String, onText: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit,
        onReminder: (ReminderEntity) -> Unit, onAll: () -> Unit,
        section: String, onSection: (String) -> Unit
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text("🌼  Незабудка", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text("Ваши напоминания", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(18.dp))
            Text(if (userName.isBlank()) "Что вам напомнить?" else "$userName,\nчто вам напомнить?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onMic, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).size(82.dp), shape = androidx.compose.foundation.shape.CircleShape) {
                Text(if (listening) "■" else "🎙", style = MaterialTheme.typography.headlineMedium)
            }
            Text("Нажмите и скажите,\nили", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, modifier=Modifier.fillMaxWidth(), textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedTextField(
                value=text, onValueChange=onText, placeholder={Text("Написать напоминание…")},
                trailingIcon={ if(text.isNotBlank()) TextButton(onClick=onSend){Text("➜")} },
                singleLine=true, modifier=Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text("Ближайшие напоминания", style=MaterialTheme.typography.titleMedium)
                TextButton(onClick=onAll){Text("Все ›")}
            }
            LazyColumn(Modifier.weight(1f)) {
                items(reminders.take(6), key={it.id}) { r -> ReminderRow(r, onReminder) }
            }
            BottomNav(section,onSection)
        }
    }

    @Composable
    private fun ReminderRow(r: ReminderEntity, onReminder:(ReminderEntity)->Unit) {
        Row(
            Modifier.fillMaxWidth().pointerInput(r.id){ detectTapGestures(onTap={onReminder(r)}) }.padding(vertical=8.dp),
            verticalAlignment=androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(semanticIcon(r.text), style=MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.text, style=MaterialTheme.typography.titleMedium)
                Text(format(r.dueAt), style=MaterialTheme.typography.bodySmall)
            }
            Switch(checked=r.active,onCheckedChange={toggleReminder(r,it)})
            TextButton(onClick={deleteReminder(r)}){Text("🗑")}
        }
        HorizontalDivider()
    }

    private fun semanticIcon(text:String):String {
        val s=text.lowercase()
        return when {
            "таблет" in s || "лекар" in s -> "💊"
            "позвон" in s || "врач" in s -> "☎"
            "трен" in s -> "🏋"
            "куп" in s || "продукт" in s -> "🛒"
            "читать" in s || "книг" in s -> "📖"
            else -> "🔔"
        }
    }

    @Composable
    private fun ReminderEditor(reminder:ReminderEntity,onBack:()->Unit,onSchedule:()->Unit,onDelete:()->Unit) {
        var confirmDelete by remember { mutableStateOf(false) }
        if(confirmDelete) DeleteDialog(onCancel={confirmDelete=false},onDelete=onDelete)
        Column(Modifier.fillMaxSize().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=onBack){Text("←")}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(semanticIcon(reminder.text),style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.width(12.dp));Text(reminder.text,style=MaterialTheme.typography.titleLarge)}
            EditorLine("💬","Что напомнить?",reminder.text,{})
            EditorLine("▣","Когда напомнить?",format(reminder.dueAt),onSchedule)
            EditorLine("↻","Повторять напоминание?",if(reminder.recurrenceMinutes==1440L)"Каждый день" else "Не задано",onSchedule)
            Text("🔊   Громкость"); Slider(value=1f,onValueChange={})
            EditorLine("▣","Заметка","(необязательно)",{})
            Spacer(Modifier.weight(1f))
            Button(onClick=onBack,modifier=Modifier.fillMaxWidth().height(54.dp)){Text("Сохранить")}
            Button(onClick={confirmDelete=true},modifier=Modifier.fillMaxWidth().height(54.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.errorContainer,contentColor=MaterialTheme.colorScheme.error)){Text("🗑  Удалить напоминание")}
        }
    }

    @Composable private fun EditorLine(icon:String,title:String,value:String,onClick:()->Unit) {
        Row(Modifier.fillMaxWidth().pointerInput(title){detectTapGestures(onTap={onClick()})}.padding(vertical=9.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
            Text(icon,style=MaterialTheme.typography.titleLarge);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(title);Text(value,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("›")
        };HorizontalDivider()
    }

    @Composable
    private fun ScheduleScreen(reminder:ReminderEntity,onBack:()->Unit) {
        var daily by remember { mutableStateOf(reminder.recurrenceMinutes==1440L) }
        val picker=rememberDatePickerState(initialSelectedDateMillis=reminder.dueAt)
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            TextButton(onClick=onBack){Text("←")}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){RadioButton(selected=daily,onClick={daily=true});Text("Каждый день")}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){RadioButton(selected=!daily,onClick={daily=false});Text("Выбрать дату и время")}
            if(!daily) DatePicker(state=picker,showModeToggle=false)
            Spacer(Modifier.weight(1f))
            Button(onClick={
                val selected=picker.selectedDateMillis
                updateSchedule(reminder,daily,selected)
                onBack()
            },modifier=Modifier.fillMaxWidth().height(54.dp)){Text("Готово")}
        }
    }

    @Composable
    private fun SettingsScreen(section:String,onSection:(String)->Unit) {
        var volume by remember { mutableFloatStateOf(prefs.getFloat("reminder_volume",1f)) }
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text("Настройки",style=MaterialTheme.typography.titleLarge,modifier=Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            EditorLine("♙","Как к вам обращаться?",userName.ifBlank{"Не задано"},{})
            Text("🔔   Громкость напоминаний");Slider(value=volume,onValueChange={volume=it;prefs.edit().putFloat("reminder_volume",it).apply()})
            EditorLine("♫","Звук напоминания","Стандартный",{})
            EditorLine("☾","Не беспокоить","22:00 – 07:00",{})
            EditorLine("◎","Язык","Русский",{})
            EditorLine("◉","Тема оформления","Светлая",{})
            EditorLine("ⓘ","О приложении","",{})
            Spacer(Modifier.weight(1f));BottomNav(section,onSection)
        }
    }

    @Composable
    private fun AllRemindersScreen(onBack:()->Unit,onReminder:(ReminderEntity)->Unit,section:String,onSection:(String)->Unit) {
        var all by remember { mutableStateOf<List<ReminderEntity>>(emptyList()) }
        var filter by remember { mutableStateOf("Все") }
        LaunchedEffect(reminders){ Thread{ val x=AlphaDatabase.get(this@MainActivity).reminders().all();runOnUiThread{all=x} }.start() }
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){TextButton(onClick=onBack){Text("←")};Text("Все напоминания",style=MaterialTheme.typography.titleLarge)}
            Row{listOf("Все","Активные","Выключенные").forEach{v->FilterChip(selected=filter==v,onClick={filter=v},label={Text(v)})}}
            LazyColumn(Modifier.weight(1f)){items(all.filter{filter=="Все"||(filter=="Активные"&&it.active)||(filter=="Выключенные"&&!it.active)},key={it.id}){ReminderRow(it,onReminder)}}
            BottomNav(section,onSection)
        }
    }

    @Composable
    private fun HistoryScreen(section:String,onSection:(String)->Unit) {
        var events by remember { mutableStateOf<List<HistoryEventEntity>>(emptyList()) }
        var filter by remember { mutableStateOf("Все") }
        LaunchedEffect(Unit){Thread{val x=AlphaDatabase.get(this@MainActivity).history().all();runOnUiThread{events=x}}.start()}
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text("История",style=MaterialTheme.typography.titleLarge,modifier=Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            Row{listOf("Все","Выполненные","Пропущенные","Отменённые").forEach{v->FilterChip(selected=filter==v,onClick={filter=v},label={Text(v)})}}
            LazyColumn(Modifier.weight(1f)){items(events.filter{filter=="Все"||historyLabel(it.status)==filter},key={it.id}){h->
                Row(Modifier.fillMaxWidth().padding(vertical=10.dp)){Text(if(h.status==HistoryEventEntity.COMPLETED)"✓" else if(h.status==HistoryEventEntity.MISSED)"×" else "−",style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.width(12.dp));Column{Text(h.reminderText);Text(format(h.scheduledAt)+"   "+historyLabel(h.status),style=MaterialTheme.typography.bodySmall)}};HorizontalDivider()
            }}
            BottomNav(section,onSection)
        }
    }
    private fun historyLabel(s:String)=when(s){HistoryEventEntity.COMPLETED->"Выполненные";HistoryEventEntity.MISSED->"Пропущенные";else->"Отменённые"}

    @Composable private fun DeleteDialog(onCancel:()->Unit,onDelete:()->Unit) {
        AlertDialog(onDismissRequest=onCancel,title={Text("Подтверждение удаления")},text={Text("Удалить это напоминание?\nОно больше не будет появляться.")},confirmButton={Button(onClick=onDelete,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("Удалить")}},dismissButton={OutlinedButton(onClick=onCancel){Text("Отмена")}})
    }

    @Composable
    private fun BottomNav(section:String,onSection:(String)->Unit) {
        NavigationBar {
            NavigationBarItem(selected=section=="reminders",onClick={onSection("reminders")},icon={Text("⌂")},label={Text("Напоминания")})
            NavigationBarItem(selected=section=="history",onClick={onSection("history")},icon={Text("◷")},label={Text("История")})
            NavigationBarItem(selected=section=="settings",onClick={onSection("settings")},icon={Text("⚙")},label={Text("Настройки")})
        }
    }

    private fun toggleReminder(r:ReminderEntity,active:Boolean) {
        Thread {
            val dao=AlphaDatabase.get(this).reminders()
            val updated=r.copy(active=active)
            dao.update(updated)
            if(active) ReminderScheduler.schedule(this,updated) else ReminderScheduler.cancel(this,r.id)
            runOnUiThread{refresh()}
        }.start()
    }

    private fun deleteReminder(r:ReminderEntity) {
        Thread {
            val db=AlphaDatabase.get(this)
            ReminderScheduler.cancel(this,r.id)
            ReminderNotifications.cancel(this,r.id)
            db.history().insert(HistoryEventEntity(reminderId=r.id,reminderText=r.text,scheduledAt=r.originalDueAt,status=HistoryEventEntity.CANCELLED))
            db.reminders().delete(r)
            runOnUiThread{refresh();status="Напоминание удалено"}
        }.start()
    }

    private fun updateSchedule(r:ReminderEntity,daily:Boolean,dateMillis:Long?) {
        Thread {
            val dao=AlphaDatabase.get(this).reminders()
            val current=dao.get(r.id)?:return@Thread
            val newDue=dateMillis?.let {
                val old=Instant.ofEpochMilli(current.dueAt).atZone(ZoneId.systemDefault()).toLocalTime()
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().atTime(old).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }?:current.dueAt
            ReminderScheduler.cancel(this,current.id)
            val updated=current.copy(dueAt=newDue,originalDueAt=newDue,recurrenceMinutes=if(daily)1440L else null,active=true,lastFiredAt=null,acknowledged=false)
            dao.update(updated);ReminderScheduler.schedule(this,updated)
            runOnUiThread{refresh()}
        }.start()
    }

    private fun updateRepeatInterval(reminder: ReminderEntity, minutes: Int) {
        val value = minutes.coerceAtLeast(0)
        ReminderRepeatSettings.setMinutes(this, value)
        Thread {
            val dao = AlphaDatabase.get(this).reminders()
            val current = dao.get(reminder.id) ?: return@Thread
            ReminderScheduler.cancel(this, current.id)
            val updated = if (current.lastFiredAt != null && value > 0) {
                current.copy(
                    repeatIntervalMinutes = value,
                    dueAt = System.currentTimeMillis() + value * 60_000L,
                    acknowledged = false,
                    active = true
                )
            } else {
                current.copy(repeatIntervalMinutes = value)
            }
            dao.update(updated)
            if (current.lastFiredAt == null) {
                ReminderScheduler.schedule(this, updated)
            } else if (value > 0) {
                ReminderScheduler.schedule(this, updated)
                ReminderNotifications.show(this, updated)
            } else {
                ReminderNotifications.show(this, updated)
            }
            runOnUiThread {
                refresh()
                status = if (value == 0) "Повтор отключён" else "Повтор: ${ReminderRepeatSettings.compactLabel(value)}"
            }
        }.start()
    }

    private fun openAlarm(r: ReminderEntity) {
        startActivity(
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AlarmActivity.EXTRA_REMINDER_ID, r.id)
                .putExtra(AlarmActivity.EXTRA_REMINDER_TEXT, r.text)
                .putExtra(AlarmActivity.EXTRA_REPEAT_MINUTES, r.repeatIntervalMinutes)
        )
    }

    private fun modelDir() = File(filesDir, "models/ru")

    private fun ensureModel() {
        if (modelDir().exists() && !modelDir().list().isNullOrEmpty()) {
            modelReady = true
            status = if (userName.isBlank()) "Как к вам обращаться?" else ""
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
                    status = if (userName.isBlank()) "Как к вам обращаться?" else ""
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

    private fun destroySystemRecognizer() {
        runCatching { systemRecognizer?.cancel() }
        runCatching { systemRecognizer?.destroy() }
        systemRecognizer = null
    }

    private fun startSystemRecognition() {
        try {
            destroySystemRecognizer()
            systemRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
            listening = true
            status = when {
                userName.isBlank() -> "Как к вам обращаться? Скажите имя."
                pendingDate != null || pendingDeleteAll -> "Слушаю ответ…"
                else -> "Слушаю команду…"
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
            destroySystemRecognizer()
            startVoskRecognition()
        }
    }

    private fun startVoskRecognition() {
        if (!modelReady) {
            status = "Для голосового ввода установите русский язык"
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
                            else if (userName.isBlank() && lastPartialText.isNotBlank()) acceptVoiceCandidates(listOf(lastPartialText))
                            else finishListeningSilently()
                        }
                    }
                    override fun onError(exception: Exception) { runOnUiThread { voiceFailure() } }
                    override fun onTimeout() { runOnUiThread { voiceFailure() } }
                })
                runOnUiThread {
                    listening = true
                    status = when {
                        userName.isBlank() -> "Как к вам обращаться? Скажите имя."
                        pendingDate != null || pendingDeleteAll -> "Слушаю ответ…"
                        else -> "Слушаю команду…"
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
        destroySystemRecognizer()
        runCatching { voskSpeech?.stop() }
        runCatching { voskSpeech?.shutdown() }
        voskSpeech = null
        lastPartialText = ""
        if (clearStatus) status = if (userName.isBlank()) "Как к вам обращаться?" else ""
    }

    private fun finishListeningSilently() {
        ignoreVoiceCallbacks = true
        listening = false
        destroySystemRecognizer()
        runCatching { voskSpeech?.shutdown() }
        voskSpeech = null
        lastPartialText = ""
        if (status.startsWith("Слышу:") || status.startsWith("Слушаю")) {
            status = if (userName.isBlank()) "Как к вам обращаться?" else ""
        }
    }

    private fun acceptVoiceCandidates(candidates: List<String>) {
        if (!listening || ignoreVoiceCallbacks) return
        val clean = candidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) { voiceFailure(); return }
        val selected = chooseBestCandidate(clean)
        val accepted = handle(selected, fromVoice = true)
        if (accepted) finishListeningSilently()
    }

    private fun chooseBestCandidate(candidates: List<String>): String {
        if (userName.isBlank()) return candidates.first()
        if (pendingDeleteAll) return candidates.first()
        val commandWords = listOf("отмени", "удали", "напоминания", "список", "перенеси", "отложи", "услышал", "понял")
        candidates.firstOrNull { c -> commandWords.any { c.lowercase().replace('ё', 'е').contains(it) } }?.let { return it }
        pendingDate?.let { pd -> candidates.firstOrNull { TemporalParser.parseTimeAnswer(it, pd) != null }?.let { return it } }
        candidates.firstOrNull { TemporalParser.parseCommand(it) !is ParseResult.Invalid }?.let { return it }
        return candidates.first()
    }

    private fun voiceFailure() {
        if (ignoreVoiceCallbacks) return
        listening = false
        ignoreVoiceCallbacks = true
        destroySystemRecognizer()
        status = if (userName.isBlank()) "Как к вам обращаться? Можно сказать или написать имя." else "Не расслышала. Можно повторить или написать."
    }

    private fun handle(raw: String, fromVoice: Boolean): Boolean {
        if (userName.isBlank()) { saveName(raw); return true }
        val lower = raw.lowercase().replace('ё', 'е').trim()

        if (pendingDeleteAll) {
            val yes = listOf("да", "удаляй", "удали", "подтверждаю", "верно").any { lower == it || lower.contains(it) }
            val no = listOf("нет", "не надо", "отмена", "отмени").any { lower == it || lower.contains(it) }
            return when {
                yes -> { pendingDeleteAll = false; cancelAllReminders(); true }
                no -> { pendingDeleteAll = false; status = "Удаление отменено"; speakReply("Хорошо. Не удаляю."); true }
                else -> { ask("Удалить все активные напоминания? Ответьте да или нет."); false }
            }
        }

        if (isListCommand(lower)) {
            speakActiveReminders()
            return true
        }

        if (isCancelAllCommand(lower)) {
            pendingDeleteAll = true
            ask("Удалить все активные напоминания? Ответьте да или нет.")
            return true
        }

        if (isCancelLatestCommand(lower)) {
            cancelLatestReminder()
            return true
        }

        if (lower.startsWith("отмени напоминание") || lower.startsWith("удали напоминание") || lower.startsWith("отмени про") || lower.startsWith("удали про")) {
            cancelByText(raw)
            return true
        }

        val ackWords = listOf("сделал", "сделано", "готово", "услышал", "услышала", "понял", "поняла", "хватит", "не напоминай", "я встал", "я встала")
        if (ackWords.any { lower.contains(it) }) { ackLatest(); return true }
        if (lower.startsWith("отложи") || lower.startsWith("перенеси") || lower.contains("напомни позже")) { rescheduleLatest(raw); return true }

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
            is ParseResult.Ready -> { createReminder(p.text, p.dueAt, p.recurrenceMinutes); true }
            is ParseResult.NeedTime -> { pendingText = p.text; pendingDate = p.date; ask("Во сколько напомнить?"); true }
            is ParseResult.Invalid -> {
                if (fromVoice) status = "Не поняла команду. Можно повторить или написать." else status = p.reason
                false
            }
        }
    }

    private fun isListCommand(lower: String): Boolean =
        lower.contains("какие напомин") || lower.contains("список напомин") ||
            lower.contains("что у меня заплан") || lower.contains("что у меня за напомин")

    private fun isCancelAllCommand(lower: String): Boolean =
        (lower.contains("отмени") || lower.contains("удали") || lower.contains("очисти")) &&
            lower.contains("все") && lower.contains("напомин")

    private fun isCancelLatestCommand(lower: String): Boolean =
        (lower.contains("отмени") || lower.contains("удали")) &&
            (lower.contains("последнее напомин") || lower.contains("последний будильник"))

    private fun speakActiveReminders() {
        Thread {
            val active = AlphaDatabase.get(this).reminders().active()
            runOnUiThread {
                if (active.isEmpty()) {
                    status = "Нет активных напоминаний"
                    speakReply("Активных напоминаний нет.")
                } else {
                    val summary = active.take(5).joinToString("; ") { "${it.text}, ${formatSpoken(it.dueAt)}" }
                    status = "Активных: ${active.size}"
                    speakReply("У вас ${active.size} активных напоминаний. $summary")
                }
            }
        }.start()
    }

    private fun cancelAllReminders() {
        Thread {
            val dao = AlphaDatabase.get(this).reminders()
            val active = dao.active()
            active.forEach {
                ReminderScheduler.cancel(this, it.id)
                ReminderNotifications.cancel(this, it.id)
                dao.update(it.copy(active = false, acknowledged = true))
            }
            runOnUiThread {
                refresh()
                status = "Все активные напоминания отменены"
                speakReply("Все активные напоминания отменены.")
            }
        }.start()
    }

    private fun cancelLatestReminder() {
        Thread {
            val dao = AlphaDatabase.get(this).reminders()
            val r = dao.active().maxByOrNull { it.createdAt }
            runOnUiThread {
                if (r == null) {
                    status = "Нет активных напоминаний"
                    speakReply("Активных напоминаний нет.")
                } else cancelReminder(r, "Последнее напоминание отменено")
            }
        }.start()
    }

    private fun cancelByText(raw: String) {
        val lower = raw.lowercase().replace('ё', 'е')
        val query = when {
            lower.contains(" про ") -> lower.substringAfter(" про ").trim()
            lower.startsWith("отмени напоминание") -> lower.removePrefix("отмени напоминание").trim()
            lower.startsWith("удали напоминание") -> lower.removePrefix("удали напоминание").trim()
            else -> ""
        }
        if (query.isBlank()) {
            status = "Уточните, какое напоминание отменить"
            speakReply("Какое напоминание отменить?")
            return
        }
        Thread {
            val matches = AlphaDatabase.get(this).reminders().active().filter {
                it.text.lowercase().replace('ё', 'е').contains(query)
            }
            runOnUiThread {
                when (matches.size) {
                    0 -> { status = "Не нашла такое напоминание"; speakReply("Не нашла такое напоминание.") }
                    1 -> cancelReminder(matches.first(), "Напоминание отменено")
                    else -> { status = "Нашла несколько. Уточните формулировку"; speakReply("Нашла несколько похожих напоминаний. Уточните, какое отменить.") }
                }
            }
        }.start()
    }

    private fun saveName(raw: String) {
        val cleaned = raw.lowercase()
            .replace(Regex("^(меня зовут|зови меня|обращайся ко мне)\\s+"), "")
            .trim().replace(Regex("\\s+"), " ").take(40)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
        if (cleaned.isBlank()) { status = "Как к вам обращаться?"; return }
        userName = cleaned
        prefs.edit().putString("user_name", cleaned).apply()
        status = "Имя сохранено: $cleaned"
        speakReply("Хорошо, $cleaned.")
    }

    private fun createReminder(text: String, due: Long, rec: Long?) {
        Thread {
            val dao = AlphaDatabase.get(this).reminders()
            val defaultRepeat = ReminderRepeatSettings.getMinutes(this)
            val id = dao.insert(
                ReminderEntity(
                    text = text,
                    dueAt = due,
                    originalDueAt = due,
                    recurrenceMinutes = rec,
                    repeatIntervalMinutes = defaultRepeat
                )
            )
            val r = dao.get(id)!!
            val ok = ReminderScheduler.schedule(this, r)
            runOnUiThread {
                refresh()
                if (ok) { status = ""; speakReply("Принято. Напомню ${formatSpoken(due)}.") }
                else { ask("Напоминание сохранено, но нужно разрешить точные напоминания."); openExactAlarmSettings() }
            }
        }.start()
    }

    private fun ask(message: String) { status = message; speakReply(message) }

    private fun speakReply(message: String, after: () -> Unit = {}) {
        var engine: TextToSpeech? = null
        val utteranceId = "dialog-${UUID.randomUUID()}"
        engine = TextToSpeech(applicationContext) { result ->
            if (result != TextToSpeech.SUCCESS) { runOnUiThread { after() }; return@TextToSpeech }
            engine?.language = Locale("ru")
            engine?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(id: String?) { engine?.shutdown(); runOnUiThread { after() } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { engine?.shutdown(); runOnUiThread { after() } }
            })
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
            engine?.speak(message, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    private fun cancelReminder(r: ReminderEntity, message: String) {
        Thread {
            val dao = AlphaDatabase.get(this).reminders()
            ReminderScheduler.cancel(this, r.id)
            ReminderNotifications.cancel(this, r.id)
            dao.update(r.copy(active = false, acknowledged = true))
            runOnUiThread {
                refresh()
                status = message
                speakReply(message)
            }
        }.start()
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
                dao.update(next); ReminderScheduler.schedule(this, next)
            } else dao.update(r.copy(active = false, acknowledged = true))
            ReminderNotifications.cancel(this, r.id)
            runOnUiThread { refresh(); status = "Подтверждено" }
        }.start()
    }

    private fun ackLatest() {
        Thread {
            val r = AlphaDatabase.get(this).reminders().latestFired()
            runOnUiThread { if (r == null) status = "Нет активного сработавшего напоминания" else ack(r) }
        }.start()
    }

    private fun rescheduleLatest(raw: String) {
        Thread {
            val dao = AlphaDatabase.get(this).reminders()
            val r = dao.latestFired() ?: dao.active().maxByOrNull { it.createdAt }
            if (r == null) { runOnUiThread { status = "Нет напоминания для переноса" }; return@Thread }
            val tail = raw.substringAfter(' ', "")
            val p = TemporalParser.parseCommand("напомни ${r.text} $tail")
            if (p is ParseResult.Ready) {
                ReminderScheduler.cancel(this, r.id)
                val n = r.copy(dueAt = p.dueAt, lastFiredAt = null, acknowledged = false, active = true)
                dao.update(n)
                ReminderScheduler.schedule(this, n)
                ReminderNotifications.cancel(this, r.id)
                runOnUiThread { refresh(); status = ""; speakReply("Перенесено. Напомню ${formatSpoken(n.dueAt)}.") }
            } else runOnUiThread { status = "Не поняла, на какое время перенести" }
        }.start()
    }

    private fun refresh() {
        Thread {
            val a = runCatching { AlphaDatabase.get(this).reminders().active() }.getOrDefault(emptyList())
            runOnUiThread { reminders = a }
        }.start()
    }

    private fun format(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
    private fun formatSpoken(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM в HH:mm"))

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
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
        if (t.isNotBlank()) { lastPartialText = t; runOnUiThread { if (listening) status = "Слышу: $t" } }
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        ignoreVoiceCallbacks = true
        destroySystemRecognizer()
        runCatching { voskSpeech?.shutdown() }
        runCatching { model?.close() }
        super.onDestroy()
    }
}
