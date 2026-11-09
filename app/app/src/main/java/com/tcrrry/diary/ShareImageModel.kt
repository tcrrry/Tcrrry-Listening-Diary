package com.tcrrry.diary

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class ShareModule(val title:String){ OVERVIEW("收听统计"),TREND("收听趋势"),SONGS("常听歌曲"),ALBUMS("常听专辑"),ARTISTS("常听歌手"),HABITS("收听习惯"),STYLES("音乐风格") }
internal enum class ShareRanking(val label:String){ PLAYS("播放次数"),TIME("聆听时长") }
internal data class ShareImageOptions(val day:LocalDate,val range:DiaryRange,val modules:Set<ShareModule> = setOf(ShareModule.OVERVIEW,ShareModule.SONGS),val limit:Int=3,val ranking:ShareRanking=ShareRanking.PLAYS,val signature:Boolean=true){
    init{require(limit in listOf(3,5,10,20));require(modules.isNotEmpty())}
}
internal data class ShareRow(val title:String,val subtitle:String="",val value:String="",val image:String?=null,val fraction:Float=0f)
internal data class ShareSection(val module:ShareModule,val title:String,val rows:List<ShareRow> = emptyList(),val values:List<Long> = emptyList(),val labels:List<String> = emptyList(),val startRank:Int=1){
    val height: Int get()=when(module){ShareModule.OVERVIEW->148;ShareModule.TREND->216;ShareModule.HABITS->58+rows.size*42;ShareModule.STYLES->76+rows.size*47;else->64+rows.size*64}
}
internal data class ShareFeature(val id:String,val title:String,val artist:String)
internal data class ShareDocument(val options:ShareImageOptions,val caption:String,val covers:List<String>,val sections:List<ShareSection>,val demo:Boolean,val heroArtist:String="",val heroAlbum:ShareFeature?=null,val features:List<ShareFeature> = emptyList())
internal data class SharePage(val sections:List<ShareSection>,val first:Boolean){val height:Int get()=480}

/** Bound bitmap allocation and split only between complete cards (never through a ranking row). */
internal fun sharePages(document:ShareDocument):List<SharePage>{
    val body=document.sections.filter{it.module!=ShareModule.OVERVIEW}
    return (body.ifEmpty{document.sections}).mapIndexed{i,section->SharePage(listOf(section),i==0)}
}
private fun shareDuration(ms:Long)=if(ms>0&&ms<60000)"<1分钟"else if(ms>=3600000)shortDuration(ms).replace(" ","")else "${ms/60000}分钟"
internal fun shareDocument(report:DiaryReport,options:ShareImageOptions,catalog:Map<String,CatalogFact> = emptyMap(),demo:Boolean=false,use12Hour:Boolean=false,artistContext:List<Play> = report.history):ShareDocument{
    val songs=sortedPlays(report.history.filter{!it.imported||it.title!="未知歌曲"},SortOrder.COUNT)
    fun order(rows:List<ShareRow>,values:List<Pair<Long,Long>>):List<ShareRow> = rows.indices.sortedWith(compareByDescending<Int>{if(options.ranking==ShareRanking.PLAYS)values[it].first else values[it].second}.thenByDescending{if(options.ranking==ShareRanking.PLAYS)values[it].second else values[it].first}.thenBy{rows[it].title}).take(options.limit).map{rows[it]}
    fun rankValue(plays:Long,ms:Long)=if(options.ranking==ShareRanking.PLAYS)"${plays}次"else shareDuration(ms)
    val songRows=order(songs.map{ShareRow(it.title,it.artist,rankValue(it.effective,it.ms),it.id)},songs.map{it.effective to it.ms})
    val albums=libraryGroups(report,false)
    val albumRows=order(albums.map{ShareRow(it.title,"${it.subtitle} · ${it.songs.size}首",rankValue(it.plays,it.ms),it.image)},albums.map{it.plays to it.ms})
    val artists=libraryGroups(report,true)
    val artistRows=order(artists.map{ShareRow(it.title,"${it.songs.size}首歌曲",rankValue(it.plays,it.ms),it.image)},artists.map{it.plays to it.ms})
    val total=report.history.sumOf{it.ms};val sections=mutableListOf<ShareSection>()
    for(module in ShareModule.entries.filter{it in options.modules})when(module){
        ShareModule.OVERVIEW->sections+=ShareSection(module,module.title,listOf(ShareRow("有效播放",value="${report.history.sumOf{it.effective}}"),ShareRow("聆听分钟",value="${total/60000}"),ShareRow("首歌曲",value="${report.history.map{it.id}.distinct().size}"),ShareRow("位歌手",value="${report.history.map{it.artist}.distinct().size}")))
        ShareModule.SONGS,ShareModule.ALBUMS,ShareModule.ARTISTS->{
            val rows=when(module){ShareModule.SONGS->songRows;ShareModule.ALBUMS->albumRows;else->artistRows}
            if(rows.isEmpty())sections+=ShareSection(module,module.title,listOf(ShareRow("暂无可展示的记录")))
            rows.chunked(3).forEachIndexed{i,part->sections+=ShareSection(module,module.title+(if(i>0)" · 续"else ""),part,startRank=i*3+1)}
        }
        ShareModule.TREND->{
            val days=report.dayTotals.ifEmpty{reportDays(report).mapKeys{it.key.toString()}}
            val first=LocalDate.parse(bounds(options.day,options.range).from);val last=LocalDate.parse(bounds(options.day,options.range).to)
            val dates=generateSequence(first){it.plusDays(1)}.takeWhile{it<=last}.toList()
            val buckets=when(options.range){DiaryRange.DAY->creditedDailyHours(report.segments.ifEmpty{report.history}).chunked(2).map{it.sum()};DiaryRange.WEEK->dates.map{days[it.toString()]?:0};DiaryRange.MONTH->dates.groupBy{it.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))}.values.map{week->week.sumOf{days[it.toString()]?:0}};DiaryRange.YEAR->(1..12).map{m->dates.filter{it.monthValue==m}.sumOf{days[it.toString()]?:0}}}
            val labels=when(options.range){DiaryRange.DAY->(0..22 step 2).map{"$it"};DiaryRange.WEEK->listOf("一","二","三","四","五","六","日");DiaryRange.MONTH->buckets.indices.map{"${it+1}周"};DiaryRange.YEAR->(1..12).map{"${it}月"}}
            sections+=ShareSection(module,"收听趋势 · 分钟",values=buckets,labels=labels)
        }
        ShareModule.HABITS->{
            val spans=report.segments.ifEmpty{report.history};val hours=creditedDailyHours(spans)
            val peak=hours.indices.maxWithOrNull(compareBy<Int>{hours[it]+hours[(it+1)%24]}.thenBy{hours[it]})?:0
            val format=DateTimeFormatter.ofPattern((if(options.range==DiaryRange.DAY)""else"M月d日 ")+(if(use12Hour)"a h:mm"else"HH:mm"),Locale.CHINA)
            fun clock(p:Play?,last:Boolean=false)=p?.let{Instant.ofEpochMilli(if(last)it.end else it.start).atZone(runCatching{ZoneId.of(it.zone)}.getOrDefault(ZoneId.systemDefault())).format(format)}?:"—"
            sections+=ShareSection(module,module.title,listOf(ShareRow("首次收听",value=clock(report.history.minByOrNull{it.start})),ShareRow("最后收听",value=clock(report.history.maxByOrNull{it.end},true)),ShareRow("偏爱时段",value=if(total>0)"%02d:00—%02d:00 · %s".format(peak,(peak+2)%24,shareDuration(hours[peak]+hours[(peak+1)%24]))else"—"),ShareRow("聆听片段",value="${ListeningSessions.group(spans).size}段")))
        }
        ShareModule.STYLES->{
            val rows=mainstreamGenreStats(report.history,catalog,artistContext).filter{it.name!="未匹配"}.take(7).map{ShareRow(it.name,value="${if(total>0)it.ms*100/total else 0}%",fraction=if(total>0)(it.ms.toFloat()/total).coerceIn(0f,1f)else 0f)}
            rows.ifEmpty{listOf(ShareRow("暂无可用的风格统计"))}.chunked(3).forEachIndexed{i,part->sections+=ShareSection(module,"音乐风格 · 时长占比"+(if(i>0)" · 续"else ""),part)}
        }
    }
    val heroArtist=artists.maxByOrNull{it.ms}?.title.orEmpty()
    val heroAlbum=albums.filter{it.subtitle==heroArtist}.maxByOrNull{it.ms}?:albums.firstOrNull()
    val featured=(albums.filter{it.key!=heroAlbum?.key}+listOfNotNull(heroAlbum)).take(3).map{ShareFeature(it.image,it.title,it.subtitle)}
    val caption=when(options.range){
        DiaryRange.DAY->options.day.format(DateTimeFormatter.ofPattern("yyyy.MM.dd  E",Locale.CHINA))
        DiaryRange.WEEK->periodHeader(options.day,options.range)
        DiaryRange.MONTH->options.day.format(DateTimeFormatter.ofPattern("yyyy.MM"))+"  月报"
        DiaryRange.YEAR->"${options.day.year}  年报"
    }
    return ShareDocument(options,caption,featured.map{it.id},sections,demo,heroArtist,heroAlbum?.let{ShareFeature(it.image,it.title,it.subtitle)},featured)
}
