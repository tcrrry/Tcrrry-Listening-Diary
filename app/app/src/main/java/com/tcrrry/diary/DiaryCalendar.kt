package com.tcrrry.diary
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.*
import kotlinx.coroutines.launch

@Composable internal fun DiaryCalendar(visible:Boolean,c:DiaryController,dismiss:()->Unit){
    if(c.range!=DiaryRange.DAY){PeriodCalendar(visible,c,dismiss);return}
    val state=remember{MutableTransitionState(false)}
    state.targetState=visible
    if(state.currentState||state.targetState){
        var pickedText by rememberSaveable{mutableStateOf(c.day.toString())};val picked=LocalDate.parse(pickedText)
        var monthText by rememberSaveable{mutableStateOf(c.day.withDayOfMonth(1).toString())};val month=LocalDate.parse(monthText)
        val scope=rememberCoroutineScope()
        var today by remember { mutableStateOf(listeningToday(ZonedDateTime.now(),c.dayPolicy)) }
        LaunchedEffect(visible,c.dayPolicy){if(visible)today=c.currentListeningDay()}
        var travel by remember{mutableFloatStateOf(0f)}
        var picker by rememberSaveable{mutableIntStateOf(0)}
        fun shift(step:Long){monthText=LocalDate.parse(monthText).plusMonths(step).toString()}
        Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
            AnimatedVisibility(state,enter=fadeIn(tween(210))+scaleIn(spring(.88f,300f),initialScale=.92f),exit=fadeOut(tween(160))+scaleOut(tween(180),targetScale=.95f)){
                Column(Modifier.padding(horizontal=22.dp).widthIn(max=400.dp).fillMaxWidth().testTag("diary-calendar").clip(RoundedCornerShape(28.dp)).background(lerp(MaterialTheme.colorScheme.surface,LocalAlbumSurface.current.invoke(),.28f)).border(.7.dp,Muted.copy(alpha=.12f),RoundedCornerShape(28.dp)).padding(21.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("选择日期",Modifier.weight(1f),fontSize=25.sp,fontWeight=FontWeight.Bold);Text("今天",Modifier.clip(CircleShape).background(Accent.copy(alpha=.09f)).press{scope.launch{val current=c.currentListeningDay();today=current;pickedText=current.toString();monthText=current.withDayOfMonth(1).toString();picker=0}}.padding(horizontal=13.dp,vertical=8.dp).testTag("calendar-today"),fontSize=13.sp,color=Accent)}
                    Row(Modifier.fillMaxWidth().padding(top=21.dp,bottom=16.dp),verticalAlignment=Alignment.CenterVertically){
                        Box(Modifier.size(36.dp).clip(CircleShape).press{shift(-1)}.testTag("calendar-previous"),contentAlignment=Alignment.Center){DiaryGlyph("back",Modifier.size(18.dp),color=Muted,label="上个月")}
                        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.Center){
                            Text("${month.year}年 ▾",Modifier.testTag("calendar-year").clip(CircleShape).press{picker=if(picker==1)0 else 1}.padding(8.dp),fontSize=18.sp,color=if(picker==1)Accent else MaterialTheme.colorScheme.onSurface)
                            Text("${month.monthValue}月 ▾",Modifier.testTag("calendar-month").clip(CircleShape).press{picker=if(picker==2)0 else 2}.padding(8.dp),fontSize=18.sp,color=if(picker==2)Accent else MaterialTheme.colorScheme.onSurface)
                        }
                        Box(Modifier.size(36.dp).clip(CircleShape).press{shift(1)}.testTag("calendar-next"),contentAlignment=Alignment.Center){DiaryGlyph("next",Modifier.size(18.dp),color=Muted,label="下个月")}
                    }
                    if(picker==0){
                    Row(Modifier.fillMaxWidth()){listOf("一","二","三","四","五","六","日").forEach{Text(it,Modifier.weight(1f),fontSize=12.sp,color=Muted,textAlign=TextAlign.Center)}}
                    AnimatedContent(monthText,Modifier.fillMaxWidth().height(260.dp).pointerInput(Unit){detectHorizontalDragGestures(onDragStart={travel=0f},onDragEnd={if(kotlin.math.abs(travel)>60f)shift(if(travel<0)1 else -1)},onHorizontalDrag={change,amount->change.consume();travel+=amount})},transitionSpec={val dir=if(targetState>initialState)1 else -1;fadeIn(tween(240))+slideInHorizontally{it/8*dir} togetherWith fadeOut(tween(130))+slideOutHorizontally{-it/10*dir}},label="calendar month grid"){date->
                        val cells=monthCells(LocalDate.parse(date));val padded=cells+List(42-cells.size){null}
                        Column(Modifier.padding(top=8.dp)){padded.chunked(7).forEach{week->Row(Modifier.fillMaxWidth()){week.forEach{day->
                            val selected=day==picked;val isToday=day==today;val color by animateColorAsState(if(selected)Accent else Color.Transparent,tween(180),label="day selection")
                            Box(Modifier.weight(1f).height(40.dp),contentAlignment=Alignment.Center){Box(Modifier.size(36.dp).clip(CircleShape).background(color).then(if(day!=null)Modifier.testTag("calendar-day:$day").press{pickedText=day.toString()}.semantics{this.selected=selected}else Modifier),contentAlignment=Alignment.Center){if(day!=null)Text("${day.dayOfMonth}",fontSize=15.sp,color=if(selected)Color.White else if(isToday)Accent else MaterialTheme.colorScheme.onSurface,fontWeight=if(isToday||selected)FontWeight.Bold else FontWeight.Medium)}}
                        }}}}
                    }
                    }else AnimatedContent(picker,transitionSpec={fadeIn(tween(180)) togetherWith fadeOut(tween(100))},label="calendar year month picker"){mode->
                        val values=if(mode==1)(2000..maxOf(LocalDate.now().year+1,month.year)).toList() else (1..12).toList()
                        val state=rememberLazyListState(if(mode==1)((month.year-2000)/3-1).coerceAtLeast(0)else 0)
                        LazyColumn(Modifier.fillMaxWidth().height(288.dp).testTag(if(mode==1)"calendar-years"else "calendar-months"),state=state){items(values.chunked(3)){row->Row(Modifier.fillMaxWidth()){
                            row.forEach{value->Box(Modifier.weight(1f).padding(4.dp).height(48.dp).clip(RoundedCornerShape(13.dp)).background(if(value==if(mode==1)month.year else month.monthValue)Accent.copy(alpha=.12f)else Color.Transparent).testTag(if(mode==1)"calendar-year:$value"else "calendar-month:$value").press{monthText=(if(mode==1)month.withYear(value)else month.withMonth(value)).toString();if(mode==2)pickedText=LocalDate.parse(monthText).withDayOfMonth(minOf(picked.dayOfMonth,LocalDate.parse(monthText).lengthOfMonth())).toString();picker=if(mode==1)2 else 0},contentAlignment=Alignment.Center){Text(if(mode==1)"${value}年"else "${value}月",fontSize=16.sp)}}
                            repeat(3-row.size){Spacer(Modifier.weight(1f))}
                        }}}
                    }
                    Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){Text("取消",Modifier.clip(CircleShape).press(dismiss).padding(horizontal=15.dp,vertical=12.dp),fontSize=15.sp,color=Muted);Spacer(Modifier.weight(1f));Text("查看",Modifier.testTag("calendar-confirm").clip(CircleShape).background(Accent).press{c.select(picked);dismiss()}.padding(horizontal=29.dp,vertical=12.dp),fontSize=15.sp,color=Color.White)}
                }
            }
        }
    }
}
