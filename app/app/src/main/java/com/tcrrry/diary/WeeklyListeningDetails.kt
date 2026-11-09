package com.tcrrry.diary

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.*

internal data class WeekComparison(val count:Long?=null,val loading:Boolean=true,val currentRank:Int?=null,val previousRank:Int?=null,val previousShare:Double?=null)
internal data class WeekCatalog(val album:AlbumCatalogue?=null,val bindings:Map<String,String> = emptyMap(),val knownTotal:Int?=null,val loading:Boolean=false)
internal data class WeeklyDetailUi(val target:WeeklyTarget,val stats:WeeklyListeningStats?,val comparison:WeekComparison,val catalog:WeekCatalog,val retry:()->Unit,val tracks:List<WeeklyTrackSlice>?=null,val newSongIds:Set<String> = emptySet())

@Composable internal fun rememberWeeklyDetail(c:DiaryController,target:WeeklyTarget):WeeklyDetailUi{
    val report=if(c.summary)c.summaryFacts.report else c.report;val day=c.day;val demo=c.demo;val policy=c.dayPolicy;val revision=c.dataRevision;val range=c.range
    val stats by key(range,day,target.key){produceState<WeeklyListeningStats?>(null,report,target){value=withContext(Dispatchers.Default){if(c.summary)lifetimeListeningStats(report,target)else weeklyListeningStats(report,target,day,range)}}}
    val comparison by produceState(WeekComparison(),day,range,policy,demo,revision,target){
        value=WeekComparison()
        if(c.summary)return@produceState
        try{
            val previous=if(demo)withContext(Dispatchers.Default){periodDemoReport(periodShift(day,range,-1),range)}else withContext(Dispatchers.IO){DiaryDatabase.get(c.activity).readReportVersioned(bounds(periodShift(day,range,-1),range),policy).first}
            value=withContext(Dispatchers.Default){
                val plays=previous.history.filter{target.matches(it)}
                val total=previous.history.sumOf{it.ms}
                WeekComparison(plays.sumOf{it.effective},false,weeklyRankings(report,target),weeklyRankings(previous,target),if(total>0)plays.sumOf{it.ms}.toDouble()/total else null)
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){value=WeekComparison(null,false)}
    }
    var requestEpoch by remember(target.key){mutableIntStateOf(0)}
    val catalog by produceState(WeekCatalog(),target.key,c.imageNetwork,c.foreground,day,range,requestEpoch){
        if(!target.album)return@produceState
        val art=File(c.activity.filesDir,"art");val title=target.play.album;val artist=target.play.artist
        val job=currentCoroutineContext()[Job]!!
        val initial=withContext(Dispatchers.IO){
            val songs=report.history.filter{target.matches(it)}
            val hints=CatalogMetadata.cached(art,songs.map{it.id}.distinct().take(20))
            val rows=hints.objects().filter{AlbumCatalogueStore.compatible(title,artist,it)}
            val total=AlbumCatalogueStore.trackFactTotal(rows)?.takeIf{it>=songs.map{p->p.id}.distinct().size}
            val bindings=rows.associate{it.optString("trackId") to it.optString("catalogTrackId",it.optString("trackId"))}
            Triple(AlbumCatalogueStore.cached(art,title,artist),hints,WeekCatalog(bindings=bindings,knownTotal=total))
        }
        value=initial.third.copy(album=initial.first,loading=c.imageNetwork&&c.foreground&&initial.third.knownTotal!=1)
        if(!c.imageNetwork||!c.foreground||initial.third.knownTotal==1)return@produceState
        try{
            val result=withContext(Dispatchers.IO){AlbumCatalogueStore.load(art,target,initial.second){url->
                job.ensureActive()
                c.images.albumCatalogRequest(url){job.isActive&&c.foreground&&!c.summary&&c.range==range&&c.day==day}
            }}
            value=value.copy(album=result?:value.album,loading=false)
        }catch(e:CancellationException){throw e}catch(_:Exception){value=value.copy(loading=false)}
    }
    val tracks by produceState<List<WeeklyTrackSlice>?>(null,stats,catalog.album,catalog.bindings,target.album){
        value=null
        val current=stats
        if(target.album&&current!=null)value=withContext(Dispatchers.Default){weeklyAlbumDistribution(current.songs,catalog.album,catalog.bindings).sortedWith(compareByDescending<WeeklyTrackSlice>{it.count}.thenBy{it.number?:Int.MAX_VALUE}.thenBy{it.title})}
    }
    val newIds by produceState<Set<String>>(emptySet(),report,target,day,range,policy,demo,revision){
        if(!target.artist||c.summary)return@produceState
        val ids=report.history.filter{target.matches(it)}.map{it.id}.toSet()
        value=try{if(demo)ids else withContext(Dispatchers.IO){DiaryDatabase.get(c.activity).periodNewIdentities(day,range,policy,ids,setOf(target.play.artist)).first}}catch(e:CancellationException){throw e}catch(_:Exception){emptySet()}
    }
    return WeeklyDetailUi(target,stats,comparison,catalog,retry={requestEpoch++},tracks=tracks,newSongIds=newIds)
}

internal fun LazyListScope.weeklyDetailItems(c:DiaryController,ui:WeeklyDetailUi,openArtistRanking:(Boolean)->Unit={}){
    val stats=ui.stats
    val range=stats?.range?:c.range
    val period=if(c.summary)"累计" else detailPeriodName(range);val before=detailPreviousName(range)
    if(stats==null){item("weekly-pending"){Text("正在整理${period}收听…",Modifier.height(190.dp).testTag("weekly-detail-pending"),color=Muted)};return}
    item("weekly-plays"){
        WeeklyPanel("${period}播放",Modifier.testTag("weekly-detail").semantics{stateDescription=range.name}){
            Text((if(c.summary)"全部收听 · 按年" else periodCaption(c.day,range))+(if(!c.summary&&range==DiaryRange.MONTH)" · 按周"else if(!c.summary&&range==DiaryRange.YEAR)" · 按月"else ""),fontSize=12.sp,color=Muted)
            var selected by remember(stats.dates){mutableIntStateOf(stats.daily.indices.maxByOrNull{stats.daily[it]}?:0)}
            val labels=if(c.summary)stats.dates.map{"${it.year}"} else when(range){DiaryRange.YEAR->stats.dates.map{"${it.monthValue}月"};DiaryRange.MONTH->stats.dates.indices.map{"${it+1}周"};else->listOf("一","二","三","四","五","六","日")}
            PeriodBars(stats.daily,labels,Modifier.fillMaxWidth().height(180.dp).testTag("weekly-play-chart"),selected,future={stats.dates[it]>LocalDate.now()},format={if(range==DiaryRange.YEAR)it.toString()else "${it}次"}){selected=it}
            val start=stats.dates[selected];val end=stats.ends[selected]
            val selectedLabel=if(c.summary)"${start.year}年" else if(range==DiaryRange.YEAR)"${start.monthValue}月"else "${start.monthValue}月${start.dayOfMonth}日"+(if(start!=end)"—${end.dayOfMonth}日"else "")
            Text("${selectedLabel} · ${stats.daily[selected]}次播放",Modifier.padding(top=8.dp),fontSize=12.sp,color=Muted)
        }
    }
    item("weekly-focus"){
        WeeklyPanel(modifier=Modifier.testTag("weekly-continuous")){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(14.dp)){
                WeeklyMetric("最多连续播放","${stats.maxConsecutive}次",Modifier.weight(1f))
                WeeklyMetric("最长连续聆听",if(stats.longestRunMs>0)shortDuration(stats.longestRunMs)else "—",Modifier.weight(1f))
                if(ui.target.artist)WeeklyMetric("占听歌时长","${(stats.totalShare*100).toInt()}%",Modifier.weight(1f))
            }
        }
    }
    if(!ui.target.artist)item("weekly-shares"){
        WeeklyPanel(modifier=Modifier.testTag("weekly-shares")){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(14.dp)){
                if(!ui.target.artist)WeeklyMetric("占歌手收听时长","${(stats.artistShare*100).toInt()}%",Modifier.weight(1f))
                WeeklyMetric("占听歌时长","${(stats.totalShare*100).toInt()}%",Modifier.weight(1f))
            }
        }
    }
    if(!c.summary)item("weekly-comparison"){
        WeeklyPanel("相比${before}",Modifier.testTag("weekly-comparison")){
            val previous=ui.comparison.count
            run {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.Top){
                    Column(Modifier.weight(1f).testTag("weekly-rank-comparison")){
                        Text(ui.comparison.currentRank?.let{"第${it}名"}?:"—",fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=Accent,maxLines=1)
                        Text(if(ui.comparison.loading)"正在读取${before}…" else ui.comparison.previousRank?.let{"${before}第${it}名"}?:"${before}未上榜",Modifier.padding(top=5.dp),fontSize=11.sp,color=Muted,maxLines=1)
                    }
                    Column(Modifier.weight(1f).testTag("weekly-play-comparison")){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text(weeklyPercentChange(stats.plays,previous),fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=Accent,maxLines=1)
                            if(previous!=null){val delta=stats.plays-previous;Spacer(Modifier.width(5.dp));if(delta!=0L)RoundedChangeArrow(delta>0,if(delta>0)androidx.compose.ui.graphics.Color(0xffe34560)else androidx.compose.ui.graphics.Color(0xff2c9f72));Text("${kotlin.math.abs(delta)}次",fontSize=12.sp,color=Muted,maxLines=1)}
                        }
                        Text(if(previous==null){if(ui.comparison.loading)"正在读取${before}…"else "${before}对比暂不可用"}else "${period} ${stats.plays}次 · ${before} ${previous}次",Modifier.padding(top=5.dp),fontSize=11.sp,color=Muted,maxLines=2)
                    }
                }
            }
        }
    }
    val single=weeklySingleRelease(ui.target,stats.songs,ui.catalog.album,ui.catalog.knownTotal)
    if(ui.target.artist){
        item("artist-week-rankings"){WeeklyArtistRankings(c,stats.songs,ui.newSongIds,period,openArtistRanking)}
    }
    if(ui.target.album&&!single){
        val tracks=ui.tracks.orEmpty()
        val complete=ui.catalog.album?.complete==true&&tracks.isNotEmpty()&&tracks.none{!it.catalog}
        item("weekly-album-distribution"){
            Column(Modifier.fillMaxWidth().padding(top=4.dp).testTag("weekly-album-distribution")){
                Text("曲目播放分布",fontSize=22.sp,fontWeight=FontWeight.Bold)
                if(complete){
                    val total=ui.catalog.album!!.tracks.size;val heard=tracks.count{it.count>0}
                    Text("${period}覆盖 ${heard}/${total}首 · ${if(total>0)heard*100/total else 0}%",Modifier.padding(top=7.dp).testTag("weekly-album-coverage"),fontSize=14.sp,color=Accent)
                }
                Text(if(ui.tracks==null)"正在整理曲目分布…"else if(ui.catalog.album!=null)"按${period}播放次数 · 0次表示${period}未有效播放"else if(ui.catalog.loading)"正在补全整张专辑曲目；先显示已有收听"else "当前为已听曲目，完整曲目表暂未取得",Modifier.padding(top=6.dp),fontSize=11.sp,color=Muted)
                if(!ui.catalog.loading&&!complete&&c.imageNetwork)Text("重新获取曲目 ›",Modifier.padding(top=8.dp).press(ui.retry),fontSize=12.sp,color=Accent)
            }
        }
        items(tracks.size,key={"weekly-track:${tracks[it].key}"}){index->
            val actions=LocalDetailActions.current
            val track=tracks[index]
            val source=stats.songs.firstOrNull{(ui.catalog.bindings[it.id]?:it.id)==track.key||normalizedCatalogName(it.title)==normalizedCatalogName(track.title)}
                ?:Play(track.key,"catalog:${track.key}",track.title,track.artist,ui.target.play.album,0,0,0,ui.catalog.album?.tracks?.firstOrNull{it.id==track.key}?.duration?:0,ZoneId.systemDefault().id)
            val share=if(stats.plays>0)track.count.toFloat()/stats.plays else 0f
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Muted.copy(alpha=.035f)).then(if(actions!=null)Modifier.press{actions.track(source,"weekly-track:${track.key}")}else Modifier).padding(horizontal=13.dp,vertical=11.dp).testTag("weekly-track:${track.key}")){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text(track.title,Modifier.weight(1f).padding(end=10.dp),fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("${track.count}次 · ${(share*100).toInt()}%",fontSize=11.sp,color=if(track.count>0)Accent else Muted)
                }
                Box(Modifier.padding(top=8.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(Muted.copy(alpha=.09f))){
                    val level by animateFloatAsState(share.coerceIn(0f,1f),tween(320),label="weekly track ${track.key}")
                    Box(Modifier.fillMaxWidth(level).fillMaxHeight().background(Accent,CircleShape))
                }
            }
        }
    }
    item("weekly-habits"){
        WeeklyPanel("收听习惯",Modifier.testTag("weekly-habits")){
            val formatter=DateTimeFormatter.ofPattern((if(range==DiaryRange.YEAR)"yyyy年M月d日 "else"M月d日 ")+(if(c.use12Hour)"a h:mm"else"HH:mm"),Locale.CHINA)
            fun clock(p:Play?,end:Boolean=false)=p?.let{Instant.ofEpochMilli(if(end)it.end else it.start).atZone(runCatching{ZoneId.of(it.zone)}.getOrDefault(ZoneId.systemDefault())).format(formatter)}?:"—"
            val hourFormat=DateTimeFormatter.ofPattern(if(c.use12Hour)"a h:mm"else"HH:mm",Locale.CHINA)
            val peak=if(stats.peakMs>0)"${LocalTime.of(stats.peakHour,0).format(hourFormat)}—${LocalTime.of((stats.peakHour+2)%24,0).format(hourFormat)}"else"—"
            WeeklyInfoRow("最常收听时段",peak+(if(stats.peakMs>0)" · ${stats.peakMs/60000}分钟"else ""))
            Spacer(Modifier.height(10.dp))
            WeeklyInfoRow("${period}首次",clock(stats.first))
            Spacer(Modifier.height(10.dp))
            WeeklyInfoRow("${period}最后",clock(stats.last,true))
        }
    }
}
@Composable private fun WeeklyArtistRankings(c:DiaryController,plays:List<Play>,newSongIds:Set<String>,period:String,openRanking:(Boolean)->Unit){
    var albumTab by rememberSaveable{mutableStateOf(false)}
    var allOpen by rememberSaveable{mutableStateOf(false)}
    var onlyNew by rememberSaveable{mutableStateOf(false)}
    val songs=remember(plays){sortedPlays(plays,SortOrder.COUNT)}
    val albums=remember(plays){libraryAlbumGroups(reportAlbums(DiaryReport(history=plays)))}
    val newSongs=remember(songs,newSongIds){songs.filter{it.id in newSongIds}}
    Column(Modifier.fillMaxWidth().testTag("weekly-artist-ranking")){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text("${period}收听排行",Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.Bold)
            SmallTabs(listOf("歌曲","专辑"),if(albumTab)1 else 0){albumTab=it==1}
            Text("全部 ›",Modifier.testTag("weekly-artist-all").padding(start=10.dp).press{openRanking(albumTab)},fontSize=12.sp,color=Muted)
        }
        if(albumTab)FavoriteAlbumList(c,albums.take(5)) else songs.take(5).forEachIndexed{i,p->SongReportRow(c,p,i,p.artist){}}
        if(!c.summary){
        Text("${period}新增歌曲",Modifier.padding(top=20.dp),fontSize=22.sp,fontWeight=FontWeight.Bold)
        if(newSongs.isEmpty())Text("${period}没有首次收听的歌曲",Modifier.padding(top=8.dp),fontSize=12.sp,color=Muted)
        newSongs.take(5).forEachIndexed{i,p->SongReportRow(c,p,i,p.artist){}}
        if(newSongs.size>5)Text("查看全部 ${newSongs.size} 首 ›",Modifier.padding(top=8.dp).press{albumTab=false;onlyNew=true;allOpen=true},fontSize=12.sp,color=Accent)
        }
    }
    if(allOpen)Dialog(onDismissRequest={allOpen=false}){
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).clip(RoundedCornerShape(24.dp)).background(androidx.compose.material3.MaterialTheme.colorScheme.surface).padding(18.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(onlyNew)"${period}新增歌曲" else if(albumTab)"${period}专辑排行" else "${period}歌曲排行",Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold);Text("关闭",Modifier.press{allOpen=false},fontSize=13.sp,color=Accent)}
            LazyColumn(Modifier.weight(1f).padding(top=12.dp)){
                if(albumTab&&!onlyNew)itemsIndexed(albums,key={_,g->g.key}){i,g->FavoriteAlbumList(c,listOf(g),rankOffset=i,onOpen={allOpen=false})}
                else itemsIndexed(if(onlyNew)newSongs else songs,key={_,p->p.id}){i,p->SongReportRow(c,p,i,p.artist,beforeOpen={allOpen=false}){}}
            }
        }
    }
}

@Composable private fun WeeklyPanel(title:String?=null,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp)).background(Muted.copy(alpha=.045f)).padding(17.dp)){
        if(title!=null)Text(title,Modifier.padding(bottom=11.dp),fontSize=20.sp,fontWeight=FontWeight.Bold)
        content()
    }
}
@Composable private fun WeeklyMetric(label:String,value:String,modifier:Modifier){Column(modifier){Text(label,fontSize=12.sp,color=Muted);Text(value,Modifier.padding(top=5.dp),fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=Accent)}}
@Composable private fun WeeklyInfoRow(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top){Text(label,Modifier.width(88.dp),fontSize=12.sp,color=Muted);Text(value,Modifier.weight(1f),fontSize=13.sp,maxLines=2)}}
