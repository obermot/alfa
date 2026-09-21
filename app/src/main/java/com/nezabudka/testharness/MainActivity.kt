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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
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
    private var allReminders by mutableStateOf<List<ReminderEntity>>(emptyList())
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
        var editTextFor by remember { mutableStateOf<ReminderEntity?>(null) }
        var editNoteFor by remember { mutableStateOf<ReminderEntity?>(null) }
        BackHandler(enabled = scheduleFor != null || editTextFor != null || editNoteFor != null || selected != null || section != "reminders") {
            when { scheduleFor != null -> scheduleFor = null; editTextFor != null -> editTextFor=null; editNoteFor != null -> editNoteFor=null; selected != null -> selected = null; else -> section = "reminders" }
        }

        MaterialTheme(colorScheme = lightColorScheme(primary=Color(0xFF087BFF),secondary=Color(0xFF087BFF),surface=Color.White,background=Color.White)) {
            Surface(Modifier.fillMaxSize(), color=Color.White) {
                when {
                    editTextFor != null -> TextEditScreen("Что напомнить?",editTextFor!!.text,{editTextFor=null}){v->updateReminderText(editTextFor!!,v);selected=editTextFor!!.copy(text=v);editTextFor=null}
                    editNoteFor != null -> TextEditScreen("Заметка",prefs.getString("note_${editNoteFor!!.id}","").orEmpty(),{editNoteFor=null}){v->prefs.edit().putString("note_${editNoteFor!!.id}",v).apply();editNoteFor=null}
                    scheduleFor != null -> ScheduleScreen(scheduleFor!!, onBack = { scheduleFor = null })
                    selected != null -> ReminderEditor(
                        reminder = selected!!,
                        onBack = { selected = null },
                        onSchedule = { scheduleFor = selected },
                        onEditText = { editTextFor = selected },
                        onEditNote = { editNoteFor = selected },
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

    private val canonicalBlue = Color(0xFF087BFF)
    private val canonicalDarkBlue = Color(0xFF001C8F)
    private val canonicalPaleBlue = Color(0xFFEAF4FF)
    private val canonicalRed = Color(0xFFFF1F24)

    @Composable
    private fun BackButton(onClick:()->Unit) {
        IconButton(onClick=onClick,modifier=Modifier.size(42.dp).background(canonicalPaleBlue,CircleShape)) {
            Icon(Icons.Filled.ArrowBack,contentDescription="Назад",tint=canonicalBlue,modifier=Modifier.size(28.dp))
        }
    }

    @Composable
    private fun HomeScreen(
        text: String, onText: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit,
        onReminder: (ReminderEntity) -> Unit, onAll: () -> Unit,
        section: String, onSection: (String) -> Unit
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).background(canonicalPaleBlue,RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center) {
                    Icon(Icons.Filled.LocalFlorist,contentDescription="Логотип Незабудки",tint=canonicalBlue,modifier=Modifier.size(42.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Незабудка",style=MaterialTheme.typography.headlineSmall,color=canonicalBlue)
                    Text("Ваши напоминания",style=MaterialTheme.typography.bodySmall,color=canonicalDarkBlue)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(if (userName.isBlank()) "Что вам напомнить?" else "$userName,\nчто вам напомнить?", style = MaterialTheme.typography.headlineSmall,color=canonicalDarkBlue)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick=onMic,
                modifier=Modifier.align(Alignment.CenterHorizontally).size(88.dp),
                shape=CircleShape,
                contentPadding=PaddingValues(0.dp),
                colors=ButtonDefaults.buttonColors(containerColor=canonicalBlue)
            ) {
                Icon(if(listening) Icons.Filled.Stop else Icons.Filled.Mic,contentDescription="Микрофон",tint=Color.White,modifier=Modifier.size(48.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Нажмите и скажите,\nили", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,color=canonicalDarkBlue)
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, modifier=Modifier.fillMaxWidth(), textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=canonicalDarkBlue)
            OutlinedTextField(
                value=text,onValueChange=onText,placeholder={Text("Написать напоминание…")},
                leadingIcon={Icon(Icons.Outlined.Image,contentDescription=null,tint=canonicalBlue)},
                trailingIcon={if(text.isNotBlank()) IconButton(onClick=onSend){Icon(Icons.Filled.Send,contentDescription="Отправить",tint=canonicalBlue)}},
                singleLine=true,modifier=Modifier.fillMaxWidth(),
                shape=RoundedCornerShape(8.dp),
                colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=canonicalBlue,unfocusedBorderColor=Color(0xFF8FC8FF))
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Text("Ближайшие напоминания",style=MaterialTheme.typography.titleMedium,color=canonicalDarkBlue)
                TextButton(onClick=onAll){Text("Все ›",color=canonicalBlue)}
            }
            LazyColumn(Modifier.weight(1f)) { items(allReminders.take(6),key={it.id}){r->ReminderRow(r,onReminder)} }
            BottomNav(section,onSection)
        }
    }

    @Composable
    private fun ReminderRow(r: ReminderEntity,onReminder:(ReminderEntity)->Unit) {
        Row(Modifier.fillMaxWidth().pointerInput(r.id){detectTapGestures(onTap={onReminder(r)})}.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(reminderIcon(r.text),contentDescription=null,tint=reminderIconColor(r.text),modifier=Modifier.size(34.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.text,style=MaterialTheme.typography.titleMedium,color=canonicalDarkBlue)
                Text(format(r.dueAt),style=MaterialTheme.typography.bodySmall,color=Color(0xFF555B86))
            }
            Switch(
                checked=r.active,onCheckedChange={setReminderEnabled(r,it)},
                colors=SwitchDefaults.colors(checkedThumbColor=Color.White,checkedTrackColor=canonicalBlue,uncheckedThumbColor=Color.White,uncheckedTrackColor=Color(0xFFD7DEEA))
            )
            IconButton(onClick={deleteReminder(r)},modifier=Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Delete,contentDescription="Удалить",tint=canonicalRed,modifier=Modifier.size(30.dp))
            }
        }
        HorizontalDivider(color=Color(0xFFE7EAF0))
    }

    private fun reminderIcon(text:String):ImageVector {
        val s=text.lowercase()
        return when {
            "таблет" in s || "лекар" in s -> Icons.Filled.Medication
            "позвон" in s || "врач" in s -> Icons.Filled.Phone
            "трен" in s -> Icons.Filled.FitnessCenter
            "куп" in s || "продукт" in s -> Icons.Filled.ShoppingCart
            "читать" in s || "книг" in s -> Icons.Filled.MenuBook
            "давлен" in s -> Icons.Filled.Favorite
            else -> Icons.Filled.Notifications
        }
    }

    private fun reminderIconColor(text:String):Color {
        val s=text.lowercase()
        return when {
            "таблет" in s || "лекар" in s -> canonicalBlue
            "позвон" in s || "врач" in s -> Color(0xFF08A94F)
            "давлен" in s -> canonicalRed
            else -> canonicalBlue
        }
    }

    @Composable
    private fun ReminderEditor(reminder:ReminderEntity,onBack:()->Unit,onSchedule:()->Unit,onEditText:()->Unit,onEditNote:()->Unit,onDelete:()->Unit) {
        var confirmDelete by remember { mutableStateOf(false) }
        if(confirmDelete) DeleteDialog(onCancel={confirmDelete=false},onDelete=onDelete)
        Column(Modifier.fillMaxSize().padding(18.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            BackButton(onBack)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment=Alignment.CenterVertically){
                Icon(reminderIcon(reminder.text),contentDescription=null,tint=reminderIconColor(reminder.text),modifier=Modifier.size(42.dp))
                Spacer(Modifier.width(12.dp));Text(reminder.text,style=MaterialTheme.typography.titleLarge,color=canonicalDarkBlue)
            }
            EditorLine(Icons.Outlined.ChatBubbleOutline,"Что напомнить?",reminder.text,onEditText)
            EditorLine(Icons.Outlined.DateRange,"Когда напомнить?",format(reminder.dueAt),onSchedule)
            EditorLine(Icons.Filled.Repeat,"Повторять напоминание?",if(reminder.recurrenceMinutes==1440L)"Каждый день" else "Не задано",onSchedule)
            var localVolume by remember { mutableFloatStateOf(prefs.getFloat("volume_${reminder.id}",prefs.getFloat("reminder_volume",1f))) }
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                Icon(Icons.Filled.VolumeUp,contentDescription=null,tint=canonicalDarkBlue,modifier=Modifier.size(30.dp));Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)){Text("Громкость",color=canonicalDarkBlue);Slider(value=localVolume,onValueChange={localVolume=it;prefs.edit().putFloat("volume_${reminder.id}",it).apply()},colors=SliderDefaults.colors(thumbColor=canonicalBlue,activeTrackColor=canonicalBlue))}
                Icon(Icons.Filled.ChevronRight,contentDescription=null,tint=canonicalDarkBlue)
            }
            HorizontalDivider(color=Color(0xFFE7EAF0))
            EditorLine(Icons.Outlined.NoteAlt,"Заметка",prefs.getString("note_${reminder.id}","").orEmpty().ifBlank{"(необязательно)"},onEditNote)
            Spacer(Modifier.weight(1f))
            Button(onClick=onBack,modifier=Modifier.fillMaxWidth().height(54.dp),colors=ButtonDefaults.buttonColors(containerColor=canonicalBlue),shape=RoundedCornerShape(8.dp)){Text("Сохранить")}
            Button(onClick={confirmDelete=true},modifier=Modifier.fillMaxWidth().height(54.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFFFECEC),contentColor=canonicalRed),shape=RoundedCornerShape(8.dp)){
                Icon(Icons.Outlined.Delete,contentDescription=null,modifier=Modifier.size(28.dp));Spacer(Modifier.width(8.dp));Text("Удалить напоминание")
            }
        }
    }

    @Composable
    private fun TextEditScreen(title:String,initial:String,onBack:()->Unit,onSave:(String)->Unit) {
        var value by remember { mutableStateOf(initial) }
        Column(Modifier.fillMaxSize().padding(18.dp).imePadding()) {
            Row(verticalAlignment=Alignment.CenterVertically){BackButton(onBack);Spacer(Modifier.width(12.dp));Text(title,style=MaterialTheme.typography.titleLarge,color=canonicalDarkBlue)}
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(value=value,onValueChange={value=it},modifier=Modifier.fillMaxWidth(),minLines=3,shape=RoundedCornerShape(8.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=canonicalBlue))
            Spacer(Modifier.height(10.dp))
            Button(onClick={onSave(value.trim())},enabled=value.isNotBlank(),modifier=Modifier.fillMaxWidth().height(52.dp),colors=ButtonDefaults.buttonColors(containerColor=canonicalBlue)){Text("Сохранить")}
        }
    }

    @Composable
    private fun EditorLine(icon:ImageVector,title:String,value:String,onClick:()->Unit) {
        Row(Modifier.fillMaxWidth().pointerInput(title){detectTapGestures(onTap={onClick()})}.padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically){
            Icon(icon,contentDescription=null,tint=canonicalDarkBlue,modifier=Modifier.size(30.dp));Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)){Text(title,color=canonicalDarkBlue);Text(value,color=Color(0xFF59618F))}
            Icon(Icons.Filled.ChevronRight,contentDescription=null,tint=canonicalDarkBlue)
        }
        HorizontalDivider(color=Color(0xFFE7EAF0))
    }

    @Composable
    private fun ScheduleScreen(reminder:ReminderEntity,onBack:()->Unit) {
        var daily by remember { mutableStateOf(reminder.recurrenceMinutes==1440L) }
        val initial = Instant.ofEpochMilli(reminder.dueAt).atZone(ZoneId.systemDefault())
        var month by remember { mutableStateOf(java.time.YearMonth.from(initial)) }
        var selectedDates by remember { mutableStateOf(linkedSetOf(initial.toLocalDate())) }
        var times by remember { mutableStateOf(mapOf(initial.toLocalDate() to initial.toLocalTime().withSecond(0).withNano(0))) }
        var editingDate by remember { mutableStateOf<LocalDate?>(null) }
        var editAll by remember { mutableStateOf(false) }
        var hour by remember { mutableIntStateOf(initial.hour) }
        var minute by remember { mutableIntStateOf(initial.minute) }

        fun openTime(date:LocalDate?, all:Boolean=false) {
            editingDate=date; editAll=all
            val t=if(all) times.values.firstOrNull() ?: initial.toLocalTime() else times[date] ?: initial.toLocalTime()
            hour=t.hour;minute=t.minute
        }
        if(editingDate!=null || editAll) AlertDialog(
            onDismissRequest={editingDate=null;editAll=false},
            title={Text(if(editAll)"Время для всех выбранных дат" else "Время напоминания",color=canonicalDarkBlue)},
            text={Column(horizontalAlignment=Alignment.CenterHorizontally){Text(if(editAll)"Изменяется время у ${selectedDates.size} выбранных дат" else editingDate?.format(DateTimeFormatter.ofPattern("d MMMM",Locale("ru"))).orEmpty(),color=Color(0xFF59618F));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                AndroidView(factory={ctx->NumberPicker(ctx).apply{minValue=0;maxValue=23;value=hour;setOnValueChangedListener{_,_,v->hour=v}}})
                Text(":",style=MaterialTheme.typography.headlineMedium)
                AndroidView(factory={ctx->NumberPicker(ctx).apply{minValue=0;maxValue=59;value=minute;setFormatter{String.format("%02d",it)};setOnValueChangedListener{_,_,v->minute=v}}})
            }}},
            confirmButton={Button(onClick={
                val t=LocalTime.of(hour,minute)
                times=if(editAll) selectedDates.associateWith{t} else times+(editingDate!! to t)
                editingDate=null;editAll=false
            }){Text("Готово")}},
            dismissButton={TextButton(onClick={editingDate=null;editAll=false}){Text("Отмена")}}
        )

        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){BackButton(onBack);Spacer(Modifier.width(10.dp));Text("Повторять напоминание?",style=MaterialTheme.typography.titleLarge,color=canonicalDarkBlue)}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){RadioButton(selected=daily,onClick={daily=true});Text("Каждый день")}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){RadioButton(selected=!daily,onClick={daily=false});Text("Выбрать дату и время")}
            if(!daily) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                    IconButton(onClick={month=month.minusMonths(1)},modifier=Modifier.size(48.dp)){Icon(Icons.Filled.ChevronLeft,contentDescription="Предыдущий месяц",tint=canonicalDarkBlue,modifier=Modifier.size(36.dp))}
                    Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale("ru"))).replaceFirstChar{it.titlecase(Locale("ru"))},style=MaterialTheme.typography.titleMedium)
                    IconButton(onClick={month=month.plusMonths(1)},modifier=Modifier.size(48.dp)){Icon(Icons.Filled.ChevronRight,contentDescription="Следующий месяц",tint=canonicalDarkBlue,modifier=Modifier.size(36.dp))}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceAround){listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach{Text(it,style=MaterialTheme.typography.labelSmall)}}
                val first=month.atDay(1);val offset=first.dayOfWeek.value-1;val days=month.lengthOfMonth()
                for(week in 0..5){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceAround){
                        for(dow in 0..6){
                            val day=week*7+dow-offset+1
                            if(day in 1..days){
                                val date=month.atDay(day);val chosen=date in selectedDates
                                TextButton(onClick={
                                    selectedDates=LinkedHashSet(selectedDates).apply{if(chosen) remove(date) else add(date)}
                                    if(!chosen && times[date]==null) times=times+(date to initial.toLocalTime().withSecond(0).withNano(0))
                                },modifier=Modifier.size(34.dp),contentPadding=PaddingValues(0.dp),colors=ButtonDefaults.textButtonColors(containerColor=if(chosen) Color(0xFF006BFF) else Color.Transparent,contentColor=if(chosen) Color.White else MaterialTheme.colorScheme.onSurface)){Text(day.toString())}
                            } else Spacer(Modifier.size(34.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("Выбранные даты и время",style=MaterialTheme.typography.titleMedium,color=canonicalDarkBlue);TextButton(onClick={if(selectedDates.isNotEmpty())openTime(null,true)}){Text("Изменить все")}}
                LazyColumn(Modifier.heightIn(max=160.dp)){
                    items(selectedDates.sorted(),key={it.toEpochDay()}){date->
                        Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                            Icon(Icons.Outlined.DateRange,contentDescription=null,tint=canonicalDarkBlue,modifier=Modifier.size(24.dp));Spacer(Modifier.width(8.dp));Text(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy",Locale("ru"))),modifier=Modifier.weight(1f),color=canonicalDarkBlue)
                            Button(onClick={openTime(date)},colors=ButtonDefaults.buttonColors(containerColor=canonicalPaleBlue,contentColor=canonicalBlue),contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp)){Text((times[date]?:initial.toLocalTime()).format(DateTimeFormatter.ofPattern("HH:mm")),style=MaterialTheme.typography.titleMedium)}
                            IconButton(onClick={selectedDates=LinkedHashSet(selectedDates).apply{remove(date)};times=times-date},modifier=Modifier.size(36.dp)){Icon(Icons.Filled.MoreVert,contentDescription="Меню даты",tint=canonicalDarkBlue)}
                        }
                    }
                }
                TextButton(onClick={val d=(selectedDates.maxOrNull()?:month.atDay(1)).plusDays(1);selectedDates=LinkedHashSet(selectedDates).apply{add(d)};times=times+(d to initial.toLocalTime().withSecond(0).withNano(0));month=java.time.YearMonth.from(d)}){Icon(Icons.Filled.AddCircle,contentDescription=null,tint=canonicalBlue);Spacer(Modifier.width(6.dp));Text("Добавить дату",color=canonicalBlue)}
            } else Spacer(Modifier.weight(1f))
            Button(onClick={
                val pairs=if(daily) listOf(initial.toLocalDate() to initial.toLocalTime()) else selectedDates.sorted().map{it to (times[it]?:initial.toLocalTime())}
                updateScheduleDates(reminder,daily,pairs);onBack()
            },enabled=daily||selectedDates.isNotEmpty(),modifier=Modifier.fillMaxWidth().height(54.dp)){Text("Готово")}
        }
    }

    @Composable
    private fun SettingsScreen(section:String,onSection:(String)->Unit) {
        var volume by remember { mutableFloatStateOf(prefs.getFloat("reminder_volume",1f)) }
        var editName by remember { mutableStateOf(false) };var nameDraft by remember { mutableStateOf(userName) }
        var dnd by remember { mutableStateOf(prefs.getBoolean("dnd_enabled",false)) }
        var dndTime by remember { mutableStateOf(false) }
        var startH by remember { mutableIntStateOf(prefs.getInt("dnd_start_h",22)) };var startM by remember { mutableIntStateOf(prefs.getInt("dnd_start_m",0)) }
        var endH by remember { mutableIntStateOf(prefs.getInt("dnd_end_h",7)) };var endM by remember { mutableIntStateOf(prefs.getInt("dnd_end_m",0)) }
        var language by remember { mutableStateOf(prefs.getString("language","Русский")?:"Русский") };var languageDialog by remember{mutableStateOf(false)}
        var about by remember { mutableStateOf(false) }
        if(editName) AlertDialog(onDismissRequest={editName=false},title={Text("Как к вам обращаться?")},text={OutlinedTextField(value=nameDraft,onValueChange={nameDraft=it},singleLine=true)},confirmButton={Button(onClick={userName=nameDraft.trim();prefs.edit().putString("user_name",userName).apply();editName=false}){Text("Сохранить")}},dismissButton={TextButton(onClick={editName=false}){Text("Отмена")}})
        if(languageDialog) AlertDialog(onDismissRequest={languageDialog=false},title={Text("Язык")},text={Column{listOf("Русский","Українська","English","Español").forEach{v->TextButton(onClick={language=v;prefs.edit().putString("language",v).apply();languageDialog=false},modifier=Modifier.fillMaxWidth()){Text(v,modifier=Modifier.fillMaxWidth())}}}},confirmButton={})
        if(dndTime) AlertDialog(onDismissRequest={dndTime=false},title={Text("Не беспокоить")},text={Column{
            Text("С");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){AndroidView(factory={ctx->NumberPicker(ctx).apply{minValue=0;maxValue=23;value=startH;setOnValueChangedListener{_,_,v->startH=v}}});AndroidView(factory={ctx->NumberPicker(ctx).apply{minValue=0;maxValue=59;value=startM;setOnValueChangedListener{_,_,v->startM=v}}})}
            Text("До");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){AndroidView(factory={ctx->NumberPicker(ctx).apply{minValue=0;maxValue=23;value=endH;setOnValueChangedListener{_,_,v->endH=v}}});AndroidView(factory={ctx->NumberPicker(ctx).apply{minValue=0;maxValue=59;value=endM;setOnValueChangedListener{_,_,v->endM=v}}})}
        }},confirmButton={Button(onClick={prefs.edit().putInt("dnd_start_h",startH).putInt("dnd_start_m",startM).putInt("dnd_end_h",endH).putInt("dnd_end_m",endM).apply();dnd=true;prefs.edit().putBoolean("dnd_enabled",true).apply();dndTime=false}){Text("Готово")}},dismissButton={TextButton(onClick={dndTime=false}){Text("Отмена")}})
        if(about) AlertDialog(onDismissRequest={about=false},title={Text("Незабудка")},text={Text("Версия 0.2.9\nПриложение голосовых и текстовых напоминаний.")},confirmButton={TextButton(onClick={about=false}){Text("ОК")}})
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){TextButton(onClick={onSection("reminders")}){Text("←",color=Color(0xFF006BFF),style=MaterialTheme.typography.headlineSmall)};Text("Настройки",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center);Spacer(Modifier.width(48.dp))}
            EditorLine("●","Как к вам обращаться?",userName.ifBlank{"Не задано"},{editName=true})
            Text("●   Громкость напоминаний");Slider(value=volume,onValueChange={volume=it;prefs.edit().putFloat("reminder_volume",it).apply()})
            EditorLine("♪","Звук напоминания","Стандартный",{})
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("☾",style=MaterialTheme.typography.titleLarge);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("Не беспокоить");Text(if(dnd)String.format("%02d:%02d – %02d:%02d",startH,startM,endH,endM) else "Выключено",color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onClick={dndTime=true}){Text("Изменить")};Switch(checked=dnd,onCheckedChange={dnd=it;prefs.edit().putBoolean("dnd_enabled",it).apply()})}
            HorizontalDivider()
            EditorLine("◎","Язык",language,{languageDialog=true})
            EditorLine("◉","Тема оформления","Светлая",{})
            EditorLine("ⓘ","О приложении","",{about=true})
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
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("Все","Активные","Выполненные","Удалённые").forEach{v->FilterChip(selected=filter==v,onClick={filter=v},label={Text(v,style=MaterialTheme.typography.labelSmall)})}}
            LazyColumn(Modifier.weight(1f)){
                val shown=when(filter){
                    "Активные"->all.filter{it.active}
                    "Выполненные"->all.filter{it.acknowledged}
                    "Удалённые"->emptyList()
                    else->all
                }
                items(shown,key={it.id}){ReminderRow(it,onReminder)}
            }
            BottomNav(section,onSection)
        }
    }

    @Composable
    private fun HistoryScreen(section:String,onSection:(String)->Unit) {
        var events by remember { mutableStateOf<List<HistoryEventEntity>>(emptyList()) }
        var filter by remember { mutableStateOf("Все") }
        LaunchedEffect(Unit){Thread{val x=AlphaDatabase.get(this@MainActivity).history().all();runOnUiThread{events=x}}.start()}
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){TextButton(onClick={onSection("reminders")}){Text("←",style=MaterialTheme.typography.headlineSmall)};Text("История",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center);Spacer(Modifier.width(48.dp))}
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

    private fun updateReminderText(r:ReminderEntity,text:String) {
        if(text.isBlank()) return
        Thread { val dao=AlphaDatabase.get(this).reminders();val cur=dao.get(r.id)?:return@Thread;dao.update(cur.copy(text=text));runOnUiThread{refresh()} }.start()
    }

    private fun setReminderEnabled(r:ReminderEntity,active:Boolean) {
        Thread {
            val dao=AlphaDatabase.get(this).reminders()
            val now=System.currentTimeMillis()
            var due=r.dueAt
            if(active && due <= now && r.recurrenceMinutes != null) {
                val step=r.recurrenceMinutes*60_000L
                while(due<=now) due+=step
            }
            val updated=r.copy(active=active,dueAt=due,lastFiredAt=null,acknowledged=false)
            dao.update(updated)
            if(active && due>now) ReminderScheduler.schedule(this,updated) else ReminderScheduler.cancel(this,r.id)
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

    private fun updateScheduleDates(r:ReminderEntity,daily:Boolean,dates:List<Pair<LocalDate,LocalTime>>) {
        val first=dates.minByOrNull{it.first.atTime(it.second)} ?: return
        val ms=first.first.atTime(first.second).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        updateSchedule(r,daily,ms)
        prefs.edit().putString("extra_dates_${r.id}",dates.drop(1).joinToString(";"){(d,t)->"${d}|${t.hour}:${t.minute}"}).apply()
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
            val dao = AlphaDatabase.get(this).reminders()
            val a = runCatching { dao.active() }.getOrDefault(emptyList())
            val all = runCatching { dao.all() }.getOrDefault(emptyList())
            runOnUiThread { reminders = a; allReminders = all }
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
