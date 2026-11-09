package com.tcrrry.diary

import java.time.*

internal data class WeeklyTarget(val play:Play,val album:Boolean=false,val artist:Boolean=false){
    fun matches(p:Play)=if(artist)p.artist==play.artist else if(album)p.album==play.album&&p.artist==play.artist else p.id==play.id
    val key get()=if(artist)"artist:${play.artist}" else if(album)"album:${play.album}:${play.artist}" else "song:${play.id}"
}
internal fun weeklySelectionTarget(history:List<Play>,selected:Play,album:Boolean):WeeklyTarget{
    val latest=history.firstOrNull{it.id==selected.id}
    return WeeklyTarget(if(latest==null)selected else selected.copy(artist=latest.artist,album=latest.album.ifBlank{selected.album}),album)
}
internal data class WeeklyListeningRun(val start:Long,val end:Long,val ms:Long,val plays:Long)
internal data class WeeklyListeningStats(
    val dates:List<LocalDate>,val daily:List<Long>,val plays:Long,val ms:Long,
    val artistMs:Long,val maxConsecutive:Long,val longestRunMs:Long,val albumRuns:Int,
    val first:Play?,val last:Play?,val peakHour:Int,val peakMs:Long,val songs:List<Play>,
    val ends:List<LocalDate> = dates,val range:DiaryRange=DiaryRange.WEEK,val totalMs:Long=0
){
    val artistShare get()=if(artistMs>0)(ms.toDouble()/artistMs).coerceIn(0.0,1.0) else 0.0
    val totalShare get()=if(totalMs>0)(ms.toDouble()/totalMs).coerceIn(0.0,1.0) else 0.0
}

/** Split on another release/track or 15 minutes of silence; credit only actual listening time. */
internal fun weeklyListeningRuns(report:DiaryReport,target:WeeklyTarget):List<WeeklyListeningRun>{
    val effective=report.history.associate{(it.visit to it.id) to it.effective}
    val seen=hashSetOf<Pair<String,String>>()
    val result=mutableListOf<WeeklyListeningRun>()
    var start=0L;var end=0L;var ms=0L;var count=0L
    fun finish(){if(ms>0)result+=WeeklyListeningRun(start,end,ms,count);ms=0;count=0}
    for(p in report.segments.ifEmpty{report.history}.filter{it.ms>0&&it.end>it.start}.sortedBy{it.start}){
        if(!target.matches(p)){finish();continue}
        if(ms>0&&p.start-end>ListeningSessions.BREAK_MS)finish()
        if(ms==0L){start=p.start;end=p.end}
        end=maxOf(end,p.end);ms+=p.ms
        val key=p.visit to p.id
        if(seen.add(key))count+=effective[key]?:p.effective
    }
    finish();return result
}
internal fun detailPeriodName(range:DiaryRange)=when(range){DiaryRange.DAY->"当天";DiaryRange.WEEK->"本周";DiaryRange.MONTH->"本月";DiaryRange.YEAR->"本年"}
internal fun detailPreviousName(range:DiaryRange)=when(range){DiaryRange.DAY->"前一天";DiaryRange.WEEK->"上周";DiaryRange.MONTH->"上月";DiaryRange.YEAR->"去年"}
private fun detailListeningDay(p:Play):LocalDate = p.listeningDay.takeIf{it.isNotBlank()}?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
    ?:Instant.ofEpochMilli(p.start).atZone(runCatching{ZoneId.of(p.zone)}.getOrDefault(ZoneId.systemDefault())).toLocalDate()

/** Week by day, month by clipped calendar week, year by month. All use listening-day ownership. */
internal fun weeklyListeningStats(report:DiaryReport,target:WeeklyTarget,day:LocalDate,range:DiaryRange=DiaryRange.WEEK):WeeklyListeningStats{
    val bound=bounds(day,range);val firstDay=LocalDate.parse(bound.from);val lastDay=LocalDate.parse(bound.to)
    val dates=mutableListOf<LocalDate>();val ends=mutableListOf<LocalDate>();val bucketByDay=hashMapOf<LocalDate,Int>()
    var cursor=firstDay
    while(cursor<=lastDay){
        val end=minOf(lastDay,when(range){
            DiaryRange.MONTH->cursor.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            DiaryRange.YEAR->cursor.withDayOfMonth(cursor.lengthOfMonth())
            else->cursor
        })
        val index=dates.size;dates+=cursor;ends+=end
        var date=cursor;while(date<=end){bucketByDay[date]=index;date=date.plusDays(1)}
        cursor=end.plusDays(1)
    }
    val scopedHistory=report.history.filter{detailListeningDay(it) in firstDay..lastDay}
    val scopedSpans=report.segments.ifEmpty{report.history}.filter{detailListeningDay(it) in firstDay..lastDay}
    val songs=scopedHistory.filter{target.matches(it)}.sortedBy{it.start}
    val counts=LongArray(dates.size)
    for(p in songs){bucketByDay[detailListeningDay(p)]?.let{counts[it]+=p.effective}}
    val hours=creditedDailyHours(scopedSpans.filter{target.matches(it)})
    val peak=(0..23).maxWithOrNull(compareBy<Int>{hours[it]+hours[(it+1)%24]}.thenBy{hours[it]})?:0
    val runs=weeklyListeningRuns(report.copy(history=scopedHistory,segments=scopedSpans),target)
    return WeeklyListeningStats(dates,counts.toList(),songs.sumOf{it.effective},songs.sumOf{it.ms},scopedHistory.filter{it.artist==target.play.artist}.sumOf{it.ms},runs.maxOfOrNull{it.plays}?:0,runs.maxOfOrNull{it.ms}?:0,runs.count{it.plays>=3},songs.firstOrNull(),songs.maxByOrNull{it.end},peak,hours[peak]+hours[(peak+1)%24],songs,ends,range,scopedHistory.sumOf{it.ms})
}
/** Aggregate the complete known history in calendar-year buckets off the UI thread. */
internal fun lifetimeListeningStats(report:DiaryReport,target:WeeklyTarget):WeeklyListeningStats {
    val history=report.history
    val spans=report.segments.ifEmpty{history}
    val songs=history.filter{target.matches(it)}.sortedBy{it.start}
    val firstYear=history.minOfOrNull{detailListeningDay(it).year}?:LocalDate.now().year
    val lastYear=history.maxOfOrNull{detailListeningDay(it).year}?:firstYear
    val dates=(firstYear..lastYear).map{LocalDate.of(it,1,1)}
    val ends=dates.map{it.withDayOfYear(it.lengthOfYear())}
    val counts=LongArray(dates.size)
    songs.forEach{counts[detailListeningDay(it).year-firstYear]+=it.effective}
    val hours=creditedDailyHours(spans.filter{target.matches(it)})
    val peak=(0..23).maxWithOrNull(compareBy<Int>{hours[it]+hours[(it+1)%24]}.thenBy{hours[it]})?:0
    val runs=weeklyListeningRuns(report,target)
    return WeeklyListeningStats(dates,counts.toList(),songs.sumOf{it.effective},songs.sumOf{it.ms},history.filter{it.artist==target.play.artist}.sumOf{it.ms},runs.maxOfOrNull{it.plays}?:0,runs.maxOfOrNull{it.ms}?:0,runs.count{it.plays>=3},songs.firstOrNull(),songs.maxByOrNull{it.end},peak,hours[peak]+hours[(peak+1)%24],songs,ends,DiaryRange.YEAR,history.sumOf{it.ms})
}

internal fun weeklyChange(current:Long,previous:Long,range:DiaryRange=DiaryRange.WEEK):String{
    val delta=current-previous;val before=detailPreviousName(range);val now=detailPeriodName(range)
    if(delta==0L)return "与${before}持平"
    if(previous==0L)return "${before}未播放 · ${now}${current}次"
    val percentage=kotlin.math.abs(delta.toDouble()/previous*100)
    return "${if(delta>0)"增加"else"减少"}${kotlin.math.abs(delta)}次 · ${if(delta>0)"+"else"−"}${String.format(java.util.Locale.ROOT,"%.0f",percentage)}%"
}
internal data class WeeklyTrackSlice(val key:String,val title:String,val artist:String,val number:Int?,val count:Long,val ms:Long,val catalog:Boolean)
internal fun weeklyAlbumDistribution(songs:List<Play>,album:AlbumCatalogue?,bindings:Map<String,String> = emptyMap()):List<WeeklyTrackSlice>{
    if(album==null)return sortedPlays(songs,SortOrder.COUNT).map{WeeklyTrackSlice(it.id,it.title,it.artist,null,it.effective,it.ms,false)}
    val assigned=linkedMapOf<String,MutableList<Play>>()
    val extras=mutableListOf<Play>()
    val byId=album.tracks.associateBy{it.id}
    val byTitle=album.tracks.groupBy{normalizedCatalogName(it.title)}
    val identities=songs.distinctBy{it.id}.associate{p->
        val direct=byId[bindings[p.id]?:p.id]
        val named=if(direct==null)byTitle[normalizedCatalogName(p.title)].orEmpty().filter{p.duration<=0||it.duration<=0||kotlin.math.abs(it.duration-p.duration)<=5000}.singleOrNull() else null
        p.id to (direct?:named)
    }
    for(p in songs){
        val match=identities[p.id]
        if(match==null)extras+=p else assigned.getOrPut(match.id){mutableListOf()}.add(p)
    }
    return album.tracks.map{t->val rows=assigned[t.id].orEmpty();WeeklyTrackSlice(t.id,t.title,t.artist,t.number,rows.sumOf{it.effective},rows.sumOf{it.ms},true)}+sortedPlays(extras,SortOrder.COUNT).map{WeeklyTrackSlice(it.id,it.title,it.artist,null,it.effective,it.ms,false)}
}
internal fun weeklySingleRelease(target:WeeklyTarget,songs:List<Play>,catalog:AlbumCatalogue?,knownTotal:Int?):Boolean{
    if(!target.album)return false
    if(catalog?.complete==true)return catalog.tracks.size==1
    if(knownTotal==1)return true
    return target.play.album.contains(Regex("(?i)(?:^|\\s|[-–—])single\\s*$"))&&songs.map{it.id}.distinct().size<=1
}

internal data class RankChange(val current:Int?,val previous:Int?) {
    val delta:Int? get()=if(current!=null&&previous!=null)previous-current else null
}
internal fun weeklyRankings(report:DiaryReport,target:WeeklyTarget):Int? = when {
    target.artist -> libraryGroups(report,true).indexOfFirst{it.title==target.play.artist}.takeIf{it>=0}?.plus(1)
    target.album -> libraryAlbumGroups(reportAlbums(report)).indexOfFirst{it.title==target.play.album&&it.subtitle==target.play.artist}.takeIf{it>=0}?.plus(1)
    else -> sortedPlays(report.history.filter{!it.imported||it.title!="未知歌曲"},SortOrder.COUNT).indexOfFirst{it.id==target.play.id}.takeIf{it>=0}?.plus(1)
}
internal fun artistWeekGroups(report:DiaryReport):List<LibraryGroup> = report.history.filter{it.artist.isNotBlank()&&it.artist!="未知歌手"}.groupBy{it.artist}.map{(name,rows)->LibraryGroup("artist:$name",name,"",sortedPlays(rows,SortOrder.COUNT),"artist:$name")}.sortedWith(compareByDescending<LibraryGroup>{it.plays}.thenByDescending{it.ms}.thenBy{it.title})

internal fun weeklyPercentChange(current:Long,previous:Long?):String = when {
    previous==null -> "—"
    previous==0L -> if(current==0L)"0%" else "新增"
    else -> String.format(java.util.Locale.ROOT,"%.0f%%",kotlin.math.abs(current-previous).toDouble()*100.0/previous)
}
