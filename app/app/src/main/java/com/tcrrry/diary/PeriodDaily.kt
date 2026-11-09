package com.tcrrry.diary

import java.time.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*


/** Use the database's assigned listening day, including its cross-midnight policy. */
internal fun dailyPeriodSongs(report: DiaryReport): Map<LocalDate, List<Play>> {
    val parts = report.segments.groupBy { p ->
        runCatching { LocalDate.parse(p.listeningDay) }.getOrElse {
            Instant.ofEpochMilli(p.start).atZone(runCatching { ZoneId.of(p.zone) }.getOrDefault(ZoneId.systemDefault())).toLocalDate()
        }
    }
    return parts.mapValues { (_, segments) ->
        val visits = segments.groupBy { it.visit to it.id }.values.map { items ->
            val first = items.first(); val ms = items.sumOf { it.ms }
            first.copy(ms = ms, start = items.minOf { it.start }, end = items.maxOf { it.end }, effective = DailyRows.effective(ms, first.duration))
        }
        sortedPlays(visits.filter { !it.imported || it.title != "未知歌曲" }, SortOrder.COUNT)
            .sortedWith(compareByDescending<Play> { it.effective }.thenByDescending { it.ms }.thenBy { it.id })
    }
}

internal fun periodDayStreak(days: Set<LocalDate>): Int {
    var best = 0; var run = 0; var previous: LocalDate? = null
    days.sorted().forEach { day -> run = if (previous?.plusDays(1) == day) run + 1 else 1; best = maxOf(best, run); previous = day }
    return best
}

@Composable internal fun PeriodDailyHistory(c: DiaryController, title: String, dates: List<LocalDate>, songs: Map<LocalDate, List<Play>>, totals: Map<LocalDate, Long>, selected: LocalDate, select: (LocalDate)->Unit, show: (LocalDate?)->Unit, spans:List<Play>) {
    ReportPanel(Modifier.historyPanel()) {
        PeriodDailyStrip(c,title,dates,songs,totals,selected,select,show)
        WeeklyTimeHeatmap(c,spans,dates,totals,selected,select,show)
    }
}

@Composable internal fun PeriodDailyStrip(c: DiaryController, title: String, dates: List<LocalDate>, songs: Map<LocalDate, List<Play>>, totals: Map<LocalDate, Long>, selected: LocalDate, select: (LocalDate)->Unit, show: (LocalDate?)->Unit, monthly:Boolean=false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        Text(title, Modifier.press { show(null) }, fontSize=18.sp, fontWeight=FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        val list=rememberLazyListState()
        val left by animateFloatAsState(if(list.canScrollBackward)1f else 0f,tween(180),label="period cover left fade")
        val right by animateFloatAsState(if(list.canScrollForward)1f else 0f,tween(180),label="period cover right fade")
        val hardware=LocalView.current.isHardwareAccelerated&&!LocalInspectionMode.current
        Box(Modifier.weight(1f).height(66.dp).graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}.clipToBounds().drawWithContent{
            drawContent();val edge=12.dp.toPx().coerceAtMost(size.width/3)
            drawRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha=1-left),Color.White),endX=edge),blendMode=BlendMode.DstIn)
            drawRect(Brush.horizontalGradient(listOf(Color.White,Color.White.copy(alpha=1-right)),startX=size.width-edge,endX=size.width),blendMode=BlendMode.DstIn)
        }){
            LazyRow(state=list,modifier=Modifier.fillMaxSize().testTag("period-daily-covers"),contentPadding=PaddingValues(horizontal=3.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
                items(dates.filter { !it.isAfter(LocalDate.now()) && (totals[it] ?: 0L) > 0L }, key={it.toString()}) { date ->
                    val top=songs[date]?.firstOrNull()
                    Column(Modifier.width(46.dp).testTag("period-day:$date").graphicsLayer{
                        val info=list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==date.toString()}
                        val hidden=info?.let{maxOf(0,-it.offset,it.offset+it.size-list.layoutInfo.viewportEndOffset).toFloat()/it.size.coerceAtLeast(1)}?:0f
                        renderEffect=if(hardware&&hidden>.04f)BlurEffect(2.5.dp.toPx()*hidden,2.5.dp.toPx()*hidden,TileMode.Decal)else null
                    }.press { select(date); show(date) }, horizontalAlignment=Alignment.CenterHorizontally) {
                        if(top!=null) Artwork(c,top.id,Modifier.size(46.dp).historyCover(date.toString()).clip(RoundedCornerShape(10.dp)),edge=160)
                        else Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Muted.copy(alpha=.09f)),contentAlignment=Alignment.Center){Text("♪",fontSize=16.sp,color=Muted)}
                        Text(if(monthly)"${date.monthValue}月" else "${date.monthValue}/${date.dayOfMonth}",Modifier.padding(top=3.dp),fontSize=10.sp,color=if(date==selected) Accent else Muted,maxLines=1)
                    }
                }
            }
        }
        Box(Modifier.testTag("period-history-link").size(28.dp).clip(CircleShape).press{show(null)},contentAlignment=Alignment.Center){DiaryGlyph("next",Modifier.size(17.dp),color=Muted)}
    }
    if(!monthly)Text("${selected.monthValue}月${selected.dayOfMonth}日 · ${shortDuration(totals[selected]?:0)}",Modifier.padding(top=12.dp),fontSize=12.sp,color=Muted)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PeriodDayDetails(c:DiaryController, date:LocalDate, songs:List<Play>, total:Long, close:()->Unit, open:(Play,String)->Unit) {
    val albums=remember(songs){songs.filter{it.album.isNotBlank()}.groupBy{it.album to it.artist}.map{(k,v)->AlbumStat(k.first,k.second,v,v.sumOf{it.ms})}.sortedByDescending{it.ms}}
    val artists=remember(songs){songs.filter{it.artist.isNotBlank()&&it.artist!="未知歌手"}.groupBy{it.artist}.map{(name,v)->name to v.sumOf{it.effective}}.sortedByDescending{it.second}}
    ModalBottomSheet(onDismissRequest=close) {
        LazyColumn(Modifier.heightIn(max=620.dp).testTag("period-day-details"),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=28.dp)) {
            item { Text("${date.monthValue}月${date.dayOfMonth}日 · 听歌记录",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("已识别歌曲 ${songs.sumOf{it.effective}}次有效播放 · ${shortDuration(total)}",Modifier.padding(top=8.dp),fontSize=13.sp,color=Muted)
                Text("查看当天完整日记及时间顺序 ›",Modifier.padding(top=12.dp,bottom=6.dp).press { close();c.select(date,DiaryRange.DAY) },fontSize=14.sp,color=Accent) }
            if(songs.isEmpty()) item { Text("这一天没有可展示的歌曲记录",Modifier.padding(vertical=24.dp),color=Muted) }
            else {
                item { Text("当天最常听",Modifier.padding(top=16.dp),fontSize=19.sp,fontWeight=FontWeight.Bold) }
                itemsIndexed(songs,key={_,p->p.id}){index,p->SongReportRow(c,p,index,p.artist){close();open(p,"period-day:$date:${p.id}")}}
                if(artists.isNotEmpty())item { Text("当天常听歌手",Modifier.padding(top=18.dp),fontSize=19.sp,fontWeight=FontWeight.Bold);Text(artists.take(3).joinToString(" · "){"${it.first} ${it.second}次"},Modifier.padding(top=8.dp),fontSize=13.sp,color=Muted) }
                if(albums.isNotEmpty())item { Text("当天常听专辑",Modifier.padding(top=18.dp),fontSize=19.sp,fontWeight=FontWeight.Bold);Text(albums.take(3).joinToString(" · "){it.title},Modifier.padding(top=8.dp),fontSize=13.sp,color=Muted) }
            }
        }
    }
}
