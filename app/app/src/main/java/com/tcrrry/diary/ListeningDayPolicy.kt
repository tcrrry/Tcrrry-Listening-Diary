package com.tcrrry.diary

import java.time.*

enum class ListeningDayMode(val label:String) { CALENDAR("自然日 · 零点"), FIXED("固定时间"), AUTO("自动 · 深夜停听4小时") }
data class ListeningDayPolicy(val mode:ListeningDayMode=ListeningDayMode.CALENDAR,val hour:Int=4) {
    val description get()=when(mode){
        ListeningDayMode.CALENDAR->"每天 00:00 开始，按记录时区统计。"
        ListeningDayMode.FIXED->"每天 %02d:00 开始；此前的凌晨播放归到前一天。".format(hour)
        ListeningDayMode.AUTO->"00:00—06:00 的播放，若距上次播放不足4小时则延续前一天；停听满4小时开始新的一天。06:00起归当天。只依据本机已有记录判断。"
    }
}
internal data class DatedPlay(val day:LocalDate,val play:Play)

/** The day a new playback at this instant would belong to, using the same rules as projection. */
internal fun listeningToday(now:ZonedDateTime, policy:ListeningDayPolicy, previous:DatedPlay?=null):LocalDate {
    val date=now.toLocalDate()
    return when(policy.mode){
        ListeningDayMode.CALENDAR -> date
        ListeningDayMode.FIXED -> if(now.hour<policy.hour.coerceIn(0,12))date.minusDays(1) else date
        ListeningDayMode.AUTO -> if(now.hour<6 && previous?.day==date.minusDays(1) && previous.play.start<=now.toInstant().toEpochMilli() && now.toInstant().toEpochMilli()-previous.play.end<4*3600000L) previous.day else date
    }
}

/** A display-only projection. Source timestamps, row keys and import batches are never rewritten. */
internal fun listeningDays(input:List<Play>,policy:ListeningDayPolicy):List<DatedPlay> {
    val result=ArrayList<DatedPlay>(input.size)
    val zones=HashMap<String,ZoneId>()
    var previousDay:LocalDate?=null;var lastEnd=Long.MIN_VALUE
    for(span in input.sortedBy{it.start}) {
        if(span.end<=span.start||span.ms<=0)continue
        val zone=zones.getOrPut(span.zone){runCatching{ZoneId.of(span.zone)}.getOrDefault(ZoneId.systemDefault())}
        var cursor=span.start
        while(cursor<span.end){
            val local=Instant.ofEpochMilli(cursor).atZone(zone);val date=local.toLocalDate()
            val day:LocalDate;val boundary:Long
            if(policy.mode==ListeningDayMode.AUTO){
                day=if(local.hour<6&&previousDay==date.minusDays(1)&&lastEnd!=Long.MIN_VALUE&&cursor-lastEnd<4*3600000L)previousDay!! else date
                boundary=(if(local.hour<6)date.atTime(6,0)else date.plusDays(1).atStartOfDay()).atZone(zone).toInstant().toEpochMilli()
            }else{
                val cutoff=if(policy.mode==ListeningDayMode.FIXED)policy.hour.coerceIn(0,12)else 0
                day=if(local.hour<cutoff)date.minusDays(1)else date
                boundary=day.plusDays(1).atTime(cutoff,0).atZone(zone).toInstant().toEpochMilli()
            }
            val end=minOf(span.end,boundary);if(end<=cursor)break
            val length=span.end-span.start
            val ms=(span.ms*((end-span.start).toDouble()/length)).toLong()-(span.ms*((cursor-span.start).toDouble()/length)).toLong()
            if(ms>0)result+=DatedPlay(day,span.copy(start=cursor,end=end,ms=ms,listeningDay=day.toString()))
            previousDay=day;lastEnd=maxOf(lastEnd,end);cursor=end
        }
    }
    return result
}
