package com.tcrrry.diary

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter

internal fun calendarWeeks(month:LocalDate):List<Pair<LocalDate,LocalDate>> {
    val first=month.withDayOfMonth(1);val last=month.withDayOfMonth(month.lengthOfMonth())
    return generateSequence(first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))){it.plusWeeks(1)}
        .takeWhile{it<=last}.map{it to it.plusDays(6)}.toList()
}
internal fun weekCalendarLabel(from:LocalDate,to:LocalDate):String {
    val a=from.format(DateTimeFormatter.ofPattern("M月d日"));val b=to.format(DateTimeFormatter.ofPattern(if(from.year==to.year)"M月d日" else "yyyy年M月d日"))
    return "$a — $b"
}

@Composable internal fun PeriodCalendar(visible:Boolean,c:DiaryController,dismiss:()->Unit){
    val transition=remember{MutableTransitionState(false)};transition.targetState=visible
    if(transition.currentState||transition.targetState){
        val range=c.range
        var selectedText by rememberSaveable(range){mutableStateOf(c.day.toString())}
        var monthText by rememberSaveable(range){mutableStateOf(c.day.withDayOfMonth(1).toString())}
        var mode by rememberSaveable(range){mutableIntStateOf(when(range){DiaryRange.WEEK->0;DiaryRange.MONTH->2;else->1})}
        val chosen=LocalDate.parse(selectedText);val month=LocalDate.parse(monthText);val scope=rememberCoroutineScope()
        val title=when(range){DiaryRange.WEEK->"选择周";DiaryRange.MONTH->"选择月份";else->"选择年份"}
        Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
            AnimatedVisibility(transition,enter=fadeIn(tween(210))+scaleIn(spring(.88f,300f),initialScale=.92f),exit=fadeOut(tween(160))+scaleOut(tween(180),targetScale=.95f)){
                Column(Modifier.padding(horizontal=22.dp).widthIn(max=400.dp).fillMaxWidth().testTag("period-calendar").clip(RoundedCornerShape(28.dp)).background(lerp(MaterialTheme.colorScheme.surface,LocalAlbumSurface.current.invoke(),.28f)).border(.7.dp,Muted.copy(alpha=.12f),RoundedCornerShape(28.dp)).padding(21.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text(title,Modifier.weight(1f),fontSize=25.sp,fontWeight=FontWeight.Bold)
                        Text(when(range){DiaryRange.WEEK->"本周";DiaryRange.MONTH->"本月";else->"今年"},Modifier.clip(CircleShape).background(Accent.copy(alpha=.09f)).press{scope.launch{val now=c.currentListeningDay();selectedText=now.toString();monthText=now.withDayOfMonth(1).toString();mode=when(range){DiaryRange.WEEK->0;DiaryRange.MONTH->2;else->1}}}.padding(horizontal=13.dp,vertical=8.dp).testTag("period-calendar-today"),fontSize=13.sp,color=Accent)
                    }
                    if(range!=DiaryRange.YEAR)Row(Modifier.fillMaxWidth().padding(top=21.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){
                        if(range==DiaryRange.WEEK)Box(Modifier.size(36.dp).clip(CircleShape).press{monthText=month.minusMonths(1).toString()}.testTag("period-calendar-previous"),contentAlignment=Alignment.Center){DiaryGlyph("back",Modifier.size(18.dp),color=Muted,label="上个月")}
                        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.Center){
                            Text("${month.year}年 ▾",Modifier.testTag("period-calendar-year").clip(CircleShape).press{mode=if(mode==1)if(range==DiaryRange.WEEK)0 else 2 else 1}.padding(8.dp),fontSize=18.sp,color=if(mode==1)Accent else MaterialTheme.colorScheme.onSurface)
                            if(range==DiaryRange.WEEK)Text("${month.monthValue}月 ▾",Modifier.testTag("period-calendar-month").clip(CircleShape).press{mode=if(mode==2)0 else 2}.padding(8.dp),fontSize=18.sp,color=if(mode==2)Accent else MaterialTheme.colorScheme.onSurface)
                        }
                        if(range==DiaryRange.WEEK)Box(Modifier.size(36.dp).clip(CircleShape).press{monthText=month.plusMonths(1).toString()}.testTag("period-calendar-next"),contentAlignment=Alignment.Center){DiaryGlyph("next",Modifier.size(18.dp),color=Muted,label="下个月")}
                    }else Spacer(Modifier.height(20.dp))
                    AnimatedContent(mode to monthText,transitionSpec={fadeIn(tween(210))+slideInHorizontally{it/14} togetherWith fadeOut(tween(120))+slideOutHorizontally{-it/18}},label="period calendar contents"){(panel,text)->
                        val current=LocalDate.parse(text)
                        when(panel){
                            0->{val weeks=calendarWeeks(current);Column(Modifier.fillMaxWidth().height(288.dp).testTag("calendar-weeks"),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                weeks.forEach{(from,to)->val active=chosen>=from&&chosen<=to
                                    Row(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(13.dp)).background(if(active)Accent.copy(alpha=.14f)else Color.Transparent).testTag("calendar-week:$from").press{selectedText=from.toString()}.padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically){
                                        Text(weekCalendarLabel(from,to),Modifier.weight(1f),fontSize=16.sp,color=if(active)Accent else MaterialTheme.colorScheme.onSurface);if(active)Text("已选",fontSize=12.sp,color=Accent)
                                    }
                                }
                            }}
                            1->{val years=(2000..maxOf(LocalDate.now().year+1,current.year)).toList();val list=rememberLazyListState(((current.year-2000)/3-1).coerceAtLeast(0))
                                LazyColumn(Modifier.fillMaxWidth().height(288.dp).testTag("period-calendar-years"),state=list){items(years.chunked(3)){row->Row(Modifier.fillMaxWidth()){
                                    row.forEach{year->Box(Modifier.weight(1f).padding(4.dp).height(48.dp).clip(RoundedCornerShape(13.dp)).background(if(year==chosen.year)Accent.copy(alpha=.14f)else Color.Transparent).testTag("period-calendar-year:$year").press{val next=current.withYear(year);monthText=next.toString();selectedText=chosen.withYear(year).toString();mode=if(range==DiaryRange.YEAR)1 else 2},contentAlignment=Alignment.Center){Text("${year}年",fontSize=16.sp,textAlign=TextAlign.Center)}}
                                    repeat(3-row.size){Spacer(Modifier.weight(1f))}
                                }}}
                            }
                            else->{Column(Modifier.fillMaxWidth().height(288.dp).testTag("period-calendar-months"),verticalArrangement=Arrangement.Center){(1..12).toList().chunked(3).forEach{row->Row(Modifier.fillMaxWidth()){
                                row.forEach{m->Box(Modifier.weight(1f).padding(4.dp).height(48.dp).clip(RoundedCornerShape(13.dp)).background(if(current.year==chosen.year&&m==chosen.monthValue)Accent.copy(alpha=.14f)else Color.Transparent).testTag("period-calendar-month:$m").press{val next=current.withMonth(m);monthText=next.toString();selectedText=next.toString();if(range==DiaryRange.WEEK)mode=0},contentAlignment=Alignment.Center){Text("${m}月",fontSize=16.sp)}}
                            }}}}
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){Text("取消",Modifier.clip(CircleShape).press(dismiss).padding(horizontal=15.dp,vertical=12.dp),fontSize=15.sp,color=Muted);Spacer(Modifier.weight(1f));Text("查看",Modifier.testTag("period-calendar-confirm").clip(CircleShape).background(Accent).press{c.select(chosen);dismiss()}.padding(horizontal=29.dp,vertical=12.dp),fontSize=15.sp,color=Color.White)}
                }
            }
        }
    }
}
