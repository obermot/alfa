package com.nezabudka.testharness

import java.time.*

sealed class ParseResult {
    data class Ready(val text:String,val dueAt:Long,val recurrenceMinutes:Long?=null):ParseResult()
    data class NeedTime(val text:String,val date:LocalDate):ParseResult()
    data class Invalid(val reason:String):ParseResult()
}

object TemporalParser {
    private val nums = mapOf(
        "ноль" to 0,"один" to 1,"одна" to 1,"одну" to 1,"час" to 1,
        "два" to 2,"две" to 2,"три" to 3,"четыре" to 4,"пять" to 5,
        "шесть" to 6,"семь" to 7,"восемь" to 8,"девять" to 9,"десять" to 10,
        "одиннадцать" to 11,"двенадцать" to 12,"тринадцать" to 13,"четырнадцать" to 14,
        "пятнадцать" to 15,"шестнадцать" to 16,"семнадцать" to 17,"восемнадцать" to 18,
        "девятнадцать" to 19,"двадцать" to 20,"двадцать один" to 21,"двадцать два" to 22,
        "двадцать три" to 23,"двадцать четыре" to 24,"тридцать" to 30,"сорок" to 40,
        "пятьдесят" to 50
    )

    fun parseCommand(raw:String, now:ZonedDateTime=ZonedDateTime.now()):ParseResult {
        val s=normalize(raw)
        val intent=detectIntent(s)
            ?: if(hasTemporalSignal(s)) "remind" else null
            ?: return ParseResult.Invalid("Не поняла просьбу. Скажите время естественной фразой, например: «через пять минут позвонить маме».")
        val content=cleanupContent(s,intent)
        val recurrence=when{
            s.contains("каждый день")||s.contains("ежеднев") -> 1440L
            s.contains("каждую неделю")||s.contains("еженед") -> 10080L
            else -> null
        }

        parseRelative(s, now)?.let { due ->
            return ParseResult.Ready(content,due,recurrence)
        }

        val date=when{
            s.contains("послезавтра")->now.toLocalDate().plusDays(2)
            s.contains("завтра")->now.toLocalDate().plusDays(1)
            else->now.toLocalDate()
        }
        val time=parseTime(s)
        val explicitlyDated=s.contains("сегодня")||s.contains("завтра")||s.contains("послезавтра")
        if(time==null&&explicitlyDated)return ParseResult.NeedTime(content,date)
        if(time!=null){
            var dt=date.atTime(time).atZone(now.zone)
            if(!explicitlyDated&&!dt.isAfter(now))dt=dt.plusDays(1)
            return ParseResult.Ready(content,dt.toInstant().toEpochMilli(),recurrence)
        }
        return ParseResult.Invalid("Не поняла время. Например: «завтра в шесть утра» или «через минуту».")
    }

    fun parseTimeAnswer(raw:String,date:LocalDate,zone:ZoneId=ZoneId.systemDefault(),now:ZonedDateTime=ZonedDateTime.now(zone)):Long? {
        val s=normalize(raw)
        parseRelative(s,now)?.let{return it}
        return parseTime(s)?.let{date.atTime(it).atZone(zone).toInstant().toEpochMilli()}
    }

    private fun detectIntent(s:String):String?=when{
        s.contains("напом") -> "remind"
        s.contains("разбуд")||Regex("\\bбуди\\b").containsMatchIn(s) -> "wake"
        (s.contains("постав")||s.contains("установ"))&&s.contains("будильник") -> "wake"
        else -> null
    }

    private fun hasTemporalSignal(s:String):Boolean {
        if (s.contains("через ") || s.contains("сегодня") || s.contains("завтра") || s.contains("послезавтра")) return true
        if (s.contains("утр") || s.contains("вечер") || s.contains("ночи") || s.contains("дня")) return true
        if (Regex("(?:^|\\s)(?:в|на)\\s*\\d{1,2}(?::\\d{2})?(?:\\s|$)").containsMatchIn(s)) return true
        return nums.keys.any { word -> Regex("(?:в|на|около)\\s+${Regex.escape(word)}(?:\\s|$)").containsMatchIn(s) }
    }

    private fun parseRelative(s:String,now:ZonedDateTime):Long? {
        if (Regex("\\bчерез\\s+(полминуты|пол минуты|полминутки)\\b").containsMatchIn(s)) {
            return now.plusSeconds(30).toInstant().toEpochMilli()
        }
        if (Regex("\\bчерез\\s+(полчаса|пол часа)\\b").containsMatchIn(s)) {
            return now.plusMinutes(30).toInstant().toEpochMilli()
        }
        Regex("\\bчерез\\s+(минуту|минута|час)\\b").find(s)?.let { m ->
            val mins=if(m.groupValues[1].startsWith("час"))60L else 1L
            return now.plusMinutes(mins).toInstant().toEpochMilli()
        }
        val rel=Regex("\\bчерез\\s+([\\p{L}\\d]+(?:\\s+[\\p{L}\\d]+)?)\\s+(минут|минуты|минуту|час|часа|часов)\\b").find(s)
        if(rel!=null){
            val n=parseNumber(rel.groupValues[1])?:return null
            val mins=if(rel.groupValues[2].startsWith("час"))n*60L else n.toLong()
            return now.plusMinutes(mins).toInstant().toEpochMilli()
        }
        return null
    }

    private fun parseTime(s:String):LocalTime? {
        Regex("(?:в|на)\\s*(\\d{1,2})(?::(\\d{2}))?").find(s)?.let{m->
            var h=m.groupValues[1].toInt()
            val min=m.groupValues[2].ifBlank{"0"}.toInt()
            h=adjustPart(h,s)
            if(h in 0..23&&min in 0..59)return LocalTime.of(h,min)
        }
        for((word,n) in nums.entries.sortedByDescending{it.key.length}){
            if(Regex("(?:в|на|около|часов в)\\s+${Regex.escape(word)}(?:\\s|$)").find(s)!=null&&n in 0..24){
                var h=if(n==24)0 else n
                h=adjustPart(h,s)
                if(h in 0..23)return LocalTime.of(h,0)
            }
        }
        return null
    }

    private fun adjustPart(hour:Int,s:String)=when{
        (s.contains("вечер")||s.contains("дня"))&&hour in 1..11->hour+12
        s.contains("ночи")&&hour==12->0
        s.contains("утр")&&hour==12->0
        else->hour
    }

    private fun parseNumber(raw:String):Int? {
        raw.trim().toIntOrNull()?.let{return it}
        val r=raw.trim()
        nums[r]?.let{return it}
        val p=r.split(" ")
        if(p.size==2){
            val a=nums[p[0]]
            val b=nums[p[1]]
            if(a!=null&&b!=null&&a>=20&&b<10)return a+b
        }
        return null
    }

    private fun normalize(raw:String)=raw.lowercase().replace('ё','е').trim()

    private fun cleanupContent(s:String,intent:String):String {
        var x=s
        x=when(intent){
            "wake" -> x.replace(Regex("^.*?(разбуди(?:ть)?|буди|поставь(?:те)?\\s+будильник|установи(?:ть)?\\s+будильник)\\s*(меня)?\\s*"),"")
            else -> x.replace(Regex("^.*?напомни(?:ть)?\\s*(мне)?\\s*"),"")
        }
        x=x.replace(Regex("\\bчерез\\s+(полминуты|пол минуты|полминутки)\\b"),"")
        x=x.replace(Regex("\\bчерез\\s+(полчаса|пол часа)\\b"),"")
        x=x.replace(Regex("\\bчерез\\s+(минуту|минута|час)\\b"),"")
        x=x.replace(Regex("\\bчерез\\s+[\\p{L}\\d]+(?:\\s+[\\p{L}\\d]+)?\\s+(минут|минуты|минуту|час|часа|часов)\\b"),"")
        x=x.replace(Regex("\\b(сегодня|завтра|послезавтра)\\b"),"")
        x=x.replace(Regex("(?:в|на)\\s*\\d{1,2}(?::\\d{2})?"),"")
        x=x.replace(Regex("\\b(утра|утром|днем|дня|вечером|вечера|ночи)\\b"),"")
        x=x.replace(Regex("\\b(каждый день|ежедневно|каждую неделю|еженедельно)\\b"),"")
        x=x.replace(Regex("\\b(пожалуйста|пожалуй)\\b"),"")
        x=x.replace(Regex("\\s+")," ").trim(' ',',','.','-')
        return if(intent=="wake") x.ifBlank{"разбудить"} else x.ifBlank{"напоминание"}
    }
}
