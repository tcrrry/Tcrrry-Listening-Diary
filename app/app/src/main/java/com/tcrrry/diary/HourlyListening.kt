package com.tcrrry.diary
import java.time.Instant
import java.time.ZoneId

internal object HourlyListening {
    /** An hourly chart across N dates has N hours of capacity per clock-hour bucket. */
    fun capacity(spans:List<Play>):Long{
        val days=mutableSetOf<java.time.LocalDate>()
        spans.filter{it.end>it.start&&it.ms>0}.forEach{s->val zone=runCatching{ZoneId.of(s.zone)}.getOrDefault(ZoneId.systemDefault());var day=Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate();val last=Instant.ofEpochMilli(s.end-1).atZone(zone).toLocalDate();while(!day.isAfter(last)){days.add(day);day=day.plusDays(1)}}
        return days.size.coerceAtLeast(1)*3600000L
    }
    fun hours(spans:List<Play>):List<Long> = bins(spans).toList().chunked(2).map{it.sum()}

    /** Only a fixed day boundary has a single stable starting clock hour. */
    fun startHour(policy:ListeningDayPolicy):Int = if(policy.mode==ListeningDayMode.FIXED)policy.hour.coerceIn(0,12) else 0
    fun orderedHours(spans:List<Play>,startHour:Int):List<Long> {
        val hours=hours(spans);val start=Math.floorMod(startHour,24)
        return List(24){hours[(start+it)%24]}
    }
    fun axisLabels(startHour:Int,twelve:Boolean):List<String> = (0..4).map { index ->
        val start=Math.floorMod(startHour,24);val hour=(start+index*6)%24
        if(twelve){val half=if(hour<12)"上午" else "下午";half+((hour+11)%12+1)}
        else if(start==0&&index==4)"24" else hour.toString().padStart(2,'0')
    }

    /** Sum real spans into half-hour bins; a pause between spans never becomes listening time. */
    fun bins(spans:List<Play>):LongArray {
        val result=LongArray(48)
        spans.filter{it.end>it.start&&it.ms>0}.forEach{span->
            val zone=runCatching{ZoneId.of(span.zone)}.getOrDefault(ZoneId.systemDefault());var cursor=span.start
            while(cursor<span.end){val local=Instant.ofEpochMilli(cursor).atZone(zone);val minute=if(local.minute<30)30 else 60;val boundary=local.withMinute(0).withSecond(0).withNano(0).plusMinutes(minute.toLong()).toInstant().toEpochMilli();val end=minOf(span.end,boundary);if(end<=cursor)break;result[local.hour*2+if(local.minute<30)0 else 1]+=end-cursor;cursor=end}
        }
        return result
    }
}
