@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.tcrrry.diary

import androidx.compose.animation.core.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun dailyRecordTime(play:Play,twelve:Boolean,dayOnly:Boolean=true):String {
    val zone=runCatching{ZoneId.of(play.zone)}.getOrDefault(ZoneId.systemDefault())
    val pattern=(if(dayOnly)"" else "M月d日 ")+(if(twelve)"a h:mm" else "HH:mm")
    return Instant.ofEpochMilli(play.start).atZone(zone).format(DateTimeFormatter.ofPattern(pattern,Locale.CHINA))
}

internal fun creditedDailyHours(spans:List<Play>):List<Long> {
    val hours=LongArray(24)
    for(p in spans){
        if(p.end<=p.start||p.ms<=0)continue
        val zone=runCatching{ZoneId.of(p.zone)}.getOrDefault(ZoneId.systemDefault())
        var at=p.start
        while(at<p.end){
            val local=Instant.ofEpochMilli(at).atZone(zone)
            val end=minOf(p.end,local.plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli())
            if(end<=at)break
            val before=(p.ms*((at-p.start).toDouble()/(p.end-p.start))).toLong()
            val after=(p.ms*((end-p.start).toDouble()/(p.end-p.start))).toLong()
            hours[local.hour]+=after-before;at=end
        }
    }
    return hours.toList()
}

internal fun dailyArtistSongs(plays:List<Play>,byTime:Boolean,descending:Boolean=!byTime):List<Play> =
    sortedPlays(plays,SortOrder.COUNT).let{if(byTime)it.sortedBy{p->p.start}else it}.let{if(descending==byTime)it.asReversed()else it}

internal fun dailyArtistAlbums(plays:List<Play>,byTime:Boolean,descending:Boolean=!byTime):List<AlbumStat> =
    reportAlbums(DiaryReport(history=plays)).let{albums->if(byTime)albums.sortedBy{it.songs.minOf{p->p.start}}else albums.sortedWith(compareByDescending<AlbumStat>{it.songs.sumOf{p->p.effective}}.thenByDescending{it.ms})}.let{if(descending==byTime)it.asReversed()else it}

internal fun listeningDurationComparison(current:Long,previous:Long?,previousName:String,loading:Boolean):String=when{
    previous==null -> if(loading)"正在读取${previousName}…" else "${previousName}对比暂不可用"
    previous==0L -> if(current==0L)"与${previousName}持平 · 0%" else "${previousName}无记录"
    current==previous -> "与${previousName}持平 · 0%"
    else -> "相比${previousName}${if(current>previous)"增加" else "减少"} ${weeklyPercentChange(current,previous)}"
}

/** A compact chronological history, with each song identified instead of anonymous time cells. */
internal fun LazyListScope.detailedRecordItems(c:DiaryController,plays:List<Play>,dayOnly:Boolean,showSongNames:Boolean=true,deviceParts:List<Play>?=null){
    if(dayOnly){compactDailyRecordItems(c,plays,showSongNames,deviceParts);return}
    item("record-heading"){Text("详细记录",Modifier.testTag("detail-record-heading"),fontSize=22.sp,fontWeight=FontWeight.SemiBold)}
    val records=plays.sortedBy{it.start}
    val multiple=records.map{it.id}.distinct().size>1
    items(records.size,key={"record:${records[it].visit}:${records[it].id}:${records[it].start}"}){index->
        val p=records[index]
        val source="detail-record:${p.visit}:${p.id}:${p.start}"
        val actions=LocalDetailActions.current
        Column(Modifier.fillMaxWidth().testTag("detail-record:${p.visit}")){
            Row(Modifier.fillMaxWidth().then(if(multiple)Modifier.press{actions?.track?.invoke(p,source)}else Modifier).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                Text(dailyRecordTime(p,c.use12Hour,dayOnly),Modifier.width(if(dayOnly){if(c.use12Hour)74.dp else 50.dp}else 98.dp),fontSize=11.sp,color=Muted)
                Artwork(c,p.id,Modifier.size(35.dp).detailSource(source,shape=RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)),edge=160)
                Column(Modifier.weight(1f).padding(horizontal=10.dp)){
                    Text(if(p.imported&&p.title=="未知歌曲")"Apple 未提供曲名"else p.title,fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(p.artist,fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Text(shortDuration(p.ms),fontSize=11.sp,color=Accent)
            }
            if(index<records.lastIndex)HorizontalDivider(Modifier.padding(start=if(dayOnly)50.dp else 98.dp),color=Muted.copy(alpha=.10f),thickness=.5.dp)
        }
    }
}

/** Row-major chronology, with enough width for the configured clock format and font scale. */
private fun LazyListScope.compactDailyRecordItems(c:DiaryController,plays:List<Play>,showSongNames:Boolean,deviceParts:List<Play>?){
    val config=c.activity.resources.configuration
    val minimum=if(showSongNames)168f else if(c.use12Hour)144f else 120f
    val columns=((config.screenWidthDp-50+8)/(minimum*config.fontScale.coerceAtLeast(1f)+8)).toInt().coerceIn(1,3)
    val rows=plays.sortedBy{it.start}.chunked(columns)
    item("record-heading"){Text("详细记录",Modifier.padding(top=8.dp,bottom=2.dp).testTag("detail-record-heading"),fontSize=22.sp,fontWeight=FontWeight.SemiBold)}
    items(rows.size,key={"record-row:$columns:${rows[it].first().visit}:${rows[it].first().id}:${rows[it].first().start}"}){index->
        val actions=LocalDetailActions.current
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            rows[index].forEach{p->
                val source="detail-record:${p.visit}:${p.id}:${p.start}"
                Column(Modifier.weight(1f).fillMaxHeight().testTag("detail-record:${p.visit}")
                    .clip(RoundedCornerShape(13.dp)).background(Muted.copy(alpha=.045f))
                    .then(if(showSongNames&&actions!=null)Modifier.press{actions.track(p,source)}else Modifier)
                    .padding(horizontal=12.dp,vertical=11.dp)){
                    if(showSongNames){
                        Text(if(p.imported&&p.title=="未知歌曲")"Apple 未提供曲名"else p.title,fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                        Text(dailyRecordTime(p,c.use12Hour),Modifier.weight(1f),fontSize=if(showSongNames)12.sp else 14.sp,fontWeight=if(showSongNames)FontWeight.Normal else FontWeight.Medium,color=if(showSongNames)Muted else LocalContentColor.current,maxLines=1)
                        Text(shortDuration(p.ms),Modifier.padding(start=6.dp),fontSize=if(showSongNames)11.sp else 12.sp,color=if(showSongNames)Accent else Muted,maxLines=1)
                    }
                    if(deviceParts!=null){
                        val label=visitDeviceLabel(deviceParts,p.visit,p.id)
                        if(label.isNotEmpty())Column(Modifier.padding(top=5.dp).testTag("detail-device:${p.visit}")){
                            label.lines().forEachIndexed{index,line->Text(line,Modifier.fillMaxWidth(),fontSize=11.sp,color=Muted,maxLines=if(index==0)2 else 1,overflow=TextOverflow.Ellipsis)}
                        }
                    }
                }
            }
            repeat(columns-rows[index].size){Spacer(Modifier.weight(1f))}
        }
    }
}

@Composable internal fun DailyArtistListening(c:DiaryController,plays:List<Play>){
    var albums by rememberSaveable{mutableStateOf(false)}
    var byTime by rememberSaveable{mutableStateOf(false)}
    var descending by rememberSaveable{mutableStateOf(true)}
    val songs=remember(plays,byTime,descending){dailyArtistSongs(plays,byTime,descending)}
    val groups=remember(plays,byTime,descending){dailyArtistAlbums(plays,byTime,descending)}
    val actions=LocalDetailActions.current
    Column(Modifier.fillMaxWidth().testTag("daily-artist-listening"),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("今日收听",fontSize=22.sp,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            SmallTabs(listOf("歌曲","专辑"),if(albums)1 else 0){albums=it==1}
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
                SmallTabs(listOf("次数","时间"),if(byTime)1 else 0){val next=it==1;if(next!=byTime){byTime=next;descending=!next}}
                Text(if(descending)"倒序 ⇅"else "正序 ⇅",Modifier.testTag("daily-sort-direction").semantics{stateDescription=if(descending)"倒序"else "正序"}.clip(CircleShape).background(Accent.copy(alpha=.10f)).press{descending=!descending}.padding(horizontal=10.dp,vertical=5.dp),fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=Accent)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Muted.copy(alpha=.035f)).padding(horizontal=12.dp,vertical=3.dp)){
            if(albums){
                groups.forEachIndexed{index,a->
                    val key="daily-artist-album:${a.key}"
                    val first=a.songs.minBy{it.start}
                    DailyListeningRow(c,a.cover,index,a.title,"${a.songs.map{it.id}.distinct().size}首 · ${a.songs.sumOf{it.effective}}次 · ${shortDuration(a.ms)}"+(if(byTime)" · ${dailyRecordTime(first,c.use12Hour)}" else ""),key){actions?.album?.invoke(a.cover,key)}
                    if(index<groups.lastIndex)HorizontalDivider(color=Muted.copy(alpha=.10f),thickness=.5.dp)
                }
                if(groups.isEmpty())Text("暂无已匹配专辑",Modifier.padding(12.dp),fontSize=13.sp,color=Muted)
            }else songs.forEachIndexed{index,p->
                val key="daily-artist-song:${p.id}"
                DailyListeningRow(c,p,index,p.title,"${p.effective}次 · ${shortDuration(p.ms)}"+(if(byTime)" · ${dailyRecordTime(p,c.use12Hour)}" else ""),key){actions?.track?.invoke(p,key)}
                if(index<songs.lastIndex)HorizontalDivider(color=Muted.copy(alpha=.10f),thickness=.5.dp)
            }
        }
    }
}

@Composable private fun DailyListeningRow(c:DiaryController,p:Play,index:Int,title:String,subtitle:String,key:String,click:()->Unit){
    Row(Modifier.fillMaxWidth().testTag(key).press(click).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
        Text("${index+1}".padStart(2,'0'),Modifier.width(28.dp),fontSize=13.sp,color=Muted)
        Artwork(c,p.id,Modifier.size(42.dp).detailSource(key,shape=RoundedCornerShape(9.dp)).clip(RoundedCornerShape(9.dp)),edge=160)
        Column(Modifier.weight(1f).padding(start=11.dp)){
            Text(title,fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(subtitle,Modifier.padding(top=3.dp),fontSize=11.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}

internal fun weeklyComparisonBounds(day:LocalDate,today:LocalDate):DayBounds {
    val current=bounds(day,DiaryRange.WEEK);val previous=bounds(day.minusWeeks(1),DiaryRange.WEEK)
    val cutoff=if(today.toString()>=current.from&&today.toString()<=current.to)today.minusWeeks(1).toString() else previous.to
    return DayBounds(previous.from,cutoff)
}

internal fun periodSummaryComparisonBounds(day:LocalDate,today:LocalDate,range:DiaryRange):DayBounds {
    if(range==DiaryRange.WEEK)return weeklyComparisonBounds(day,today)
    val current=bounds(day,range);val previous=bounds(periodShift(day,range,-1),range)
    if(range==DiaryRange.DAY||today.toString()<current.from||today.toString()>current.to)return previous
    val cutoff=when(range){DiaryRange.MONTH->today.minusMonths(1);DiaryRange.YEAR->today.minusYears(1);else->today}.toString()
    return DayBounds(previous.from,cutoff)
}

internal fun dailyDurationDelta(current:Long,previous:Long):String = periodDurationDelta(current,previous,"昨天")
internal fun periodDurationDelta(current:Long,previous:Long,previousName:String):String {
    val delta=current-previous
    if(delta==0L)return "与${previousName}时长相同"
    val minutes=kotlin.math.abs(delta).toDouble()/60000.0
    val value=if(minutes<0.1)"<0.1" else String.format(java.util.Locale.ROOT,"%.1f",minutes).removeSuffix(".0")
    return "${if(delta>0)"多听" else "少听"} ${value} 分钟"
}

private data class ListeningSummaryStats(val first:Play?,val hours:List<Long>,val sessions:Int,val longest:Long,val deviceShares:List<Pair<String,Long>>,val elapsed:Long,val total:Long)

@Composable internal fun DailyListeningSummary(c:DiaryController,plays:List<Play>,spans:List<Play>,modifier:Modifier=Modifier,range:DiaryRange=DiaryRange.DAY){
    if(plays.isEmpty())return
    val stats by key(c.day,range,c.dayPolicy,c.demo){produceState<ListeningSummaryStats?>(null,spans,plays,range){
        value=withContext(Dispatchers.Default){
            val parts=spans.ifEmpty{plays};val grouped=ListeningSessions.group(parts)
            ListeningSummaryStats(parts.minByOrNull{it.start},creditedDailyHours(parts),grouped.size,grouped.maxOfOrNull{it.ms}?:0L,deviceListeningTotals(parts),parts.sumOf{it.ms},plays.sumOf{it.ms}.coerceAtLeast(1))
        }
    }}
    val hours=stats?.hours?:List(24){0L}
    val peak=remember(hours){hours.indices.maxByOrNull{hours[it]}?:0}
    val sessions=stats?.sessions
    val previousName=when(range){DiaryRange.DAY->"昨天";DiaryRange.WEEK->"上周同期";DiaryRange.MONTH->"上月同期";DiaryRange.YEAR->"去年同期"}
    val comparison by key(c.day,range,c.dayPolicy,c.demo){produceState<Pair<Long?,Boolean>>(null to true,c.dataRevision){
        try{
            val previousDay=periodShift(c.day,range,-1)
            val comparisonBounds=periodSummaryComparisonBounds(c.day,c.currentListeningDay(),range)
            if(!c.demo){val cached=withContext(Dispatchers.IO){DailyDurationCache.read(c.activity,c.dayPolicy,comparisonBounds)};if(cached!=null)value=cached to true}
            val previousMs=if(c.demo)withContext(Dispatchers.Default){val report=periodDemoReport(previousDay,range);if(range!=DiaryRange.DAY)report.dayTotals.filterKeys{it>=comparisonBounds.from&&it<=comparisonBounds.to}.values.sum() else report.ms}else withContext(Dispatchers.IO){PeriodDurationReader.read(c.activity,comparisonBounds,c.dayPolicy)?:DiaryDatabase.get(c.activity).durationForBounds(comparisonBounds,c.dayPolicy)}
            value=previousMs to false
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){value=null to false}
    }}
    val facts=c.summaryFacts
    val artistContext by produceState<List<Play>>(emptyList(),plays,facts){
        value=withContext(Dispatchers.Default){
            val names=plays.map{it.artist}.toSet()
            (facts.songs.filter{it.artist in names}+plays).distinctBy{it.id}
        }
    }
    val ids=remember(artistContext){artistContext.map{it.id}.distinct()}
    val cachedMetadata by produceState<Map<String,CatalogFact>>(emptyMap(),ids,c.previewMetadataEpoch){
        value=withContext(Dispatchers.IO){CatalogMetadata.cached(java.io.File(c.activity.filesDir,"art"),ids).objects().map{CatalogFact.parse(it)}.associateBy{it.id}}
    }
    val metadata=remember(cachedMetadata,c.catalogFacts){cachedMetadata+c.catalogFacts}
    val styles by produceState(emptyList<GenreStat>(),plays,metadata,artistContext){
        value=withContext(Dispatchers.Default){mainstreamGenreStats(plays,metadata,artistContext)}
    }
    val matched=styles.filter{it.name!="未匹配"}
    Column(modifier.fillMaxWidth().testTag("daily-listening-summary").clip(RoundedCornerShape(22.dp)).background(Muted.copy(alpha=.045f)).animateContentSize(tween(180)).padding(17.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
        Text(when(range){DiaryRange.DAY->"这一天的声音";DiaryRange.WEEK->"这一周的声音";DiaryRange.MONTH->"这个月的声音";DiaryRange.YEAR->"这一年的声音"},fontSize=22.sp,fontWeight=FontWeight.Bold)
        if(range==DiaryRange.DAY)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Column(Modifier.weight(1f)){Text("开始听歌",fontSize=12.sp,color=Muted);Text(stats?.first?.let{dailyRecordTime(it,c.use12Hour)}?:"—",Modifier.padding(top=4.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
            Column(Modifier.weight(1f)){Text("聆听段数",fontSize=12.sp,color=Muted);Text(sessions?.let{"${it}段"}?:"—",Modifier.padding(top=4.dp),fontSize=16.sp,fontWeight=FontWeight.Medium)}
        }
        if(range!=DiaryRange.DAY){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Column(Modifier.weight(1f)){Text("最长连续聆听",fontSize=12.sp,color=Muted);Text(stats?.longest?.takeIf{it>0}?.let(::shortDuration)?:"—",Modifier.padding(top=4.dp),fontSize=18.sp,fontWeight=FontWeight.SemiBold)}
                Column(Modifier.weight(1f)){Text("最常听时段",fontSize=12.sp,color=Muted);Text(if(stats==null||hours[peak]<=0)"—" else "%02d:00—%02d:00".format(peak,(peak+1)%24),Modifier.padding(top=4.dp),fontSize=16.sp,fontWeight=FontWeight.SemiBold,maxLines=1)}
            }
        }
        run {
            Column(Modifier.fillMaxWidth().testTag("listening-summary-comparison")){
                Text("相比${previousName}",fontSize=12.sp,color=Muted)
                val previousValue=comparison.first;val currentValue=stats?.elapsed
                val value=if(currentValue==null||previousValue==null)"—" else if(previousValue==0L){if(currentValue==0L)"0%" else "新增"} else {
                    val delta=currentValue-previousValue
                    "${if(delta>0)"↑ " else if(delta<0)"↓ " else ""}${String.format(java.util.Locale.ROOT,"%.0f",kotlin.math.abs(delta.toDouble())*100/previousValue)}%"
                }
                Text(value,Modifier.padding(top=4.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=Accent)
                Text(if(currentValue!=null&&previousValue!=null)periodDurationDelta(currentValue,previousValue,previousName)+(if(comparison.second)" · 正在更新" else "") else if(comparison.second)"正在读取${previousName}…" else "${previousName}对比暂不可用",Modifier.padding(top=4.dp),fontSize=12.sp,color=Muted)
            }
        }
        if(stats?.deviceShares?.isNotEmpty()==true){
            val devices=stats!!.deviceShares;val totalTime=devices.sumOf{it.second}.coerceAtLeast(1)
            Column(Modifier.fillMaxWidth().testTag("daily-device-distribution"),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("设备占比",Modifier.weight(1f),fontSize=12.sp,color=Muted);if(devices.size>2)Text("左右滑动 ›",fontSize=10.sp,color=Muted)}
                Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Muted.copy(alpha=.10f))){
                    devices.forEachIndexed{index,entry->Box(Modifier.weight(entry.second.toFloat()/totalTime).fillMaxHeight().background(genreTint(index,Accent)))}
                }
                Row(Modifier.fillMaxWidth().heightIn(min=40.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically){
                    devices.forEachIndexed{index,(name,ms)->Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(6.dp).background(genreTint(index,Accent),CircleShape));Text(" $name · ${String.format(java.util.Locale.ROOT,"%.1f",ms.toDouble()*100/totalTime)}%",fontSize=12.sp,color=Muted,maxLines=1)}}
                }
            }
        }
        Text("听歌风格 · 时长分布",fontSize=15.sp,fontWeight=FontWeight.SemiBold)
        GenreDistribution(matched)

    }
}

internal fun latestDeviceLabel(parts:List<Play>):String {
    val latest=parts.maxByOrNull{it.end}?:return ""
    val name=latest.device.takeIf{it.isNotBlank()}
    val volume=latest.volumePct.takeIf{it in 0..100}?.let{"$it%"}
    return listOfNotNull(name,volume).joinToString("\n")
}
internal fun visitDeviceLabel(parts:List<Play>,visit:String,id:String):String = latestDeviceLabel(parts.filter{it.visit==visit && it.id==id})
internal fun deviceListeningTotals(parts:List<Play>):List<Pair<String,Long>> = parts.asSequence()
    .filter{it.device.isNotBlank()&&it.ms>0}.groupBy{it.device}
    .map{(device,rows)->device to rows.sumOf{it.ms}}
    .sortedWith(compareByDescending<Pair<String,Long>>{it.second}.thenBy{it.first})
internal fun mostUsedDevice(parts:List<Play>):Pair<String,Long>? = parts.asSequence()
    .filter{it.device.isNotBlank()&&it.ms>0}
    .groupBy{it.device}.map{(device,rows)->device to rows.sumOf{it.ms}}
    .maxWithOrNull(compareBy<Pair<String,Long>>{it.second}.thenByDescending{it.first})
