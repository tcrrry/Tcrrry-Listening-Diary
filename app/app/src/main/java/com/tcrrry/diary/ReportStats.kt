package com.tcrrry.diary
import java.time.*
import java.time.format.DateTimeFormatter

internal data class AlbumStat(val title:String,val artist:String,val songs:List<Play>,val ms:Long):java.io.Serializable{val cover get()=songs.maxBy{it.ms};val key get()=title+"\u0000"+artist}
internal fun reportDays(r:DiaryReport):Map<LocalDate,Long>{
    if(r.dayTotals.isNotEmpty())return r.dayTotals.mapNotNull{(d,v)->runCatching{LocalDate.parse(d) to v}.getOrNull()}.toMap()
    val values=linkedMapOf<LocalDate,Long>()
    r.segments.forEach{s->val z=runCatching{ZoneId.of(s.zone)}.getOrDefault(ZoneId.systemDefault());var cursor=s.start;while(cursor<s.end){val d=Instant.ofEpochMilli(cursor).atZone(z).toLocalDate();val until=minOf(s.end,d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli());if(until<=cursor)break;values[d]=(values[d]?:0)+(until-cursor);cursor=until}}
    return values
}
internal fun reportAlbums(r:DiaryReport)=r.history.filter{it.album.isNotBlank()}.groupBy{it.album to it.artist}.map{(key,v)->AlbumStat(key.first,key.second,v,v.sumOf{it.ms})}.sortedByDescending{it.ms}
internal fun periodShift(day:LocalDate,range:DiaryRange,delta:Long)=when(range){DiaryRange.DAY->day.plusDays(delta);DiaryRange.WEEK->day.plusWeeks(delta);DiaryRange.MONTH->day.plusMonths(delta);DiaryRange.YEAR->day.plusYears(delta)}
internal fun periodCaption(day:LocalDate,range:DiaryRange)=when(range){DiaryRange.DAY->day.format(DateTimeFormatter.ofPattern("M月d日"));DiaryRange.WEEK->{val b=bounds(day,range);"${LocalDate.parse(b.from).format(DateTimeFormatter.ofPattern("M月d日"))}—${LocalDate.parse(b.to).format(DateTimeFormatter.ofPattern("M月d日"))}"};DiaryRange.MONTH->"${day.year}年${day.monthValue}月";DiaryRange.YEAR->"${day.year}年"}
internal fun reportHours(ms:Long)=String.format(java.util.Locale.ROOT,"%.1f",ms/3600000.0).removeSuffix(".0")
internal fun monthCells(day:LocalDate):List<LocalDate?>{val first=day.withDayOfMonth(1);return List(first.dayOfWeek.value-1){null}+List(day.lengthOfMonth()){first.plusDays(it.toLong())}}

internal fun periodHeader(day:LocalDate,range:DiaryRange):String=when(range){
    DiaryRange.DAY->day.format(DateTimeFormatter.ofPattern("yyyy年M月d日 · E",java.util.Locale.CHINESE))
    DiaryRange.WEEK->{val b=bounds(day,range);val first=LocalDate.parse(b.from);val last=LocalDate.parse(b.to);val start=first.format(DateTimeFormatter.ofPattern("yyyy年M月d日"));val end=last.format(DateTimeFormatter.ofPattern(if(first.year==last.year)"M月d日" else "yyyy年M月d日"));"${start}—${end} · 周"}
    DiaryRange.MONTH->"${day.year}年${day.monthValue}月 · 月"
    DiaryRange.YEAR->"${day.year}年 · 年"
}
