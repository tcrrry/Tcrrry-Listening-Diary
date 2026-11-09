package com.tcrrry.diary

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*

/** Aggregate credited time by the assigned listening day and a three-hour clock interval. */
internal fun weeklyHours(spans:List<Play>,dates:List<LocalDate>,policy:ListeningDayPolicy):List<List<Long>> {
    val start=HourlyListening.startHour(policy)
    val rows=Array(dates.size){LongArray(24)};val indices=dates.withIndex().associate{it.value to it.index}
    val zones=HashMap<String,ZoneId>();val owners=HashMap<String,LocalDate?>()
    for(span in spans){
        if(span.end<=span.start||span.ms<=0)continue
        val zone=zones.getOrPut(span.zone){runCatching{ZoneId.of(span.zone)}.getOrDefault(ZoneId.systemDefault())}
        val assigned=if(span.listeningDay.isBlank())null else owners.getOrPut(span.listeningDay){runCatching{LocalDate.parse(span.listeningDay)}.getOrNull()}
        var at=span.start;val length=span.end-span.start
        while(at<span.end){
            val local=Instant.ofEpochMilli(at).atZone(zone)
            val end=minOf(span.end,local.plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli())
            if(end<=at)break
            val owner=assigned?:if(policy.mode==ListeningDayMode.FIXED&&local.hour<start)local.toLocalDate().minusDays(1)else local.toLocalDate()
            val bucket=Math.floorMod(local.hour-start,24)
            indices[owner]?.let{row->
                val before=(span.ms*((at-span.start).toDouble()/length)).toLong()
                val after=(span.ms*((end-span.start).toDouble()/length)).toLong()
                rows[row][bucket]+=after-before
            }
            at=end
        }
    }
    return rows.map{it.toList()}
}

internal fun weeklyHeatmap(spans:List<Play>,dates:List<LocalDate>,policy:ListeningDayPolicy):List<List<Long>> = weeklyHours(spans,dates,policy).map{row->row.chunked(3).map{it.sum()}}

/** Split active intervals more finely; quiet periods keep a wider time span in one cell. */
internal fun adaptiveWeekIntervals(rows:List<List<Long>>):List<IntRange>{
    val intervals=(0 until 24 step 4).map{it until it+4}.toMutableList()
    if(rows.sumOf{it.sum()}==0L)return (0 until 24 step 3).map{it until it+3}
    while(intervals.size<10){
        val candidate=intervals.indices.filter{intervals[it].count()>1}.maxByOrNull{i->
            val range=intervals[i];rows.sumOf{r->range.sumOf{r[it]}}.toDouble()/range.count()
        }?:break
        val range=intervals[candidate];val mid=range.first+range.count()/2
        intervals[candidate]=range.first until mid;intervals.add(candidate+1,mid..range.last)
    }
    return intervals
}
private data class AdaptiveWeek(val intervals:List<IntRange>,val hours:List<List<Long>>)

@Composable internal fun WeeklyTimeHeatmap(c:DiaryController,spans:List<Play>,dates:List<LocalDate>,totals:Map<LocalDate,Long>,selected:LocalDate,select:(LocalDate)->Unit,show:(LocalDate?)->Unit){
    val policy=c.dayPolicy;val start=HourlyListening.startHour(policy)
    val today by produceState(listeningToday(ZonedDateTime.now(),policy),policy,c.dataRevision){value=c.currentListeningDay()}
    val data by produceState<AdaptiveWeek?>(null,spans,dates,policy){value=withContext(Dispatchers.Default){val hours=weeklyHours(spans,dates,policy);AdaptiveWeek(adaptiveWeekIntervals(hours),hours)}}
    val intervals=data?.intervals?: (0 until 24 step 3).map{it until it+3}
    Column(Modifier.fillMaxWidth().padding(top=18.dp).testTag("week-history-chart").testTag("weekly-time-heatmap")){
        Text("一周听歌时段",fontSize=18.sp,fontWeight=FontWeight.Bold)
        Text("按本周习惯调整时段 · 填充越满代表该时段听得越久",Modifier.padding(top=5.dp,bottom=10.dp),fontSize=10.sp,color=Muted)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)){
            Spacer(Modifier.width(18.dp))
            intervals.forEach{range->val hour=(start+range.first)%24;Text(hour.toString().padStart(2,'0'),Modifier.weight(1f),fontSize=9.sp,color=Muted,textAlign=TextAlign.Center,maxLines=1)}
            Text("总时长",Modifier.width(49.dp),fontSize=9.sp,color=Muted,textAlign=TextAlign.Center,maxLines=1)
        }
        dates.forEachIndexed{row,date->if(!date.isAfter(today))Row(Modifier.fillMaxWidth().padding(top=3.dp).testTag("period-bar:$row").press{if(!date.isAfter(today)&&(totals[date]?:0)>0){if(date==selected)show(date)else select(date)}},horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.CenterVertically){
            Text("一二三四五六日"[date.dayOfWeek.value-1].toString(),Modifier.width(18.dp),fontSize=10.sp,color=Muted,textAlign=TextAlign.Center)
            intervals.forEach{range->val ms=data?.hours?.getOrNull(row)?.let{hours->range.sumOf{hours[it]}}?:0L;val capacity=range.count()*3600000L;val amount=(ms.toFloat()/capacity).coerceIn(0f,1f);val from=(start+range.first)%24;val to=(start+range.last+1)%24
                Box(Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(4.dp)).background(Muted.copy(alpha=.10f)).semantics{contentDescription="${date.monthValue}月${date.dayOfMonth}日 ${from}点至${to}点，${shortDuration(ms)}"}){
                    if(amount>0f)Box(Modifier.fillMaxWidth().fillMaxHeight(amount.coerceAtLeast(.08f)).align(Alignment.BottomCenter).clip(RoundedCornerShape(topStart=4.dp,topEnd=4.dp,bottomStart=2.dp,bottomEnd=2.dp)).background(Brush.verticalGradient(listOf(Accent.copy(alpha=.38f),Accent.copy(alpha=.68f)))))
                }
            }
            val daily=totals[date]?:0L
            Box(Modifier.width(49.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).background(Muted.copy(alpha=.10f)).semantics{contentDescription="${date.monthValue}月${date.dayOfMonth}日总时长，${shortDuration(daily)}"},contentAlignment=Alignment.Center){
                Text(String.format(java.util.Locale.ROOT,"%.1f小时",daily/3600000.0),fontSize=8.sp,color=MaterialTheme.colorScheme.onSurface,maxLines=1)
            }
        }}
    }
}
