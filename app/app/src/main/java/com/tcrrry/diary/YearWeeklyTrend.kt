package com.tcrrry.diary

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class YearWeek(val from:LocalDate,val to:LocalDate,val ms:Long)
internal fun yearWeeks(year:Int,days:Map<LocalDate,Long>):List<YearWeek>{
    val end=LocalDate.of(year,12,31);var start=LocalDate.of(year,1,1);val result=mutableListOf<YearWeek>()
    while(start<=end){val last=minOf(end,start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)));var d=start;var total=0L;while(d<=last){total+=days[d]?:0L;d=d.plusDays(1)};result+=YearWeek(start,last,total);start=last.plusDays(1)}
    return result
}

/** Position on the same cubic curve used to draw the weekly trend. */
internal fun yearTrendPosition(points:List<Offset>,position:Float):Offset {
    if(points.size==1)return points.first()
    val p=position.coerceIn(0f,points.lastIndex.toFloat());val index=floor(p).toInt().coerceAtMost(points.lastIndex-1)
    val a=points[index];val b=points[index+1];val xFraction=p-index
    var low=0f;var high=1f
    repeat(12){val t=(low+high)/2;val x=1.5f*t-1.5f*t*t+t*t*t;if(x<xFraction)low=t else high=t}
    val t=(low+high)/2;val yFraction=t*t*(3-2*t)
    return Offset(a.x+(b.x-a.x)*xFraction,a.y+(b.y-a.y)*yFraction)
}

/** Pick several local highs and lows; final placement avoids overlapping labels. */
internal fun yearTrendExtrema(weeks:List<YearWeek>):List<Int> {
    if(weeks.size<3)return emptyList()
    val candidates=(1 until weeks.lastIndex).filter{i->
        val prev=weeks[i-1].ms;val value=weeks[i].ms;val next=weeks[i+1].ms
        (value>prev&&value>=next)||(value<prev&&value<=next)
    }
    val highs=candidates.filter{weeks[it].ms>weeks[it-1].ms}.sortedByDescending{weeks[it].ms}
    val lows=candidates.filter{weeks[it].ms<weeks[it-1].ms}.sortedBy{weeks[it].ms}
    val picked=mutableListOf<Int>()
    fun addNext(options:List<Int>){options.firstOrNull{i->i !in picked&&picked.all{kotlin.math.abs(it-i)>=3}}?.let{picked+=it}}
    repeat(4){addNext(highs);addNext(lows)}
    return picked.sorted()
}

@Composable internal fun YearWeeklyTrend(day:LocalDate,days:Map<LocalDate,Long>,onSelect:(LocalDate)->Unit){
    val today=LocalDate.now();val allWeeks=remember(day.year,days){yearWeeks(day.year,days)};val weeks=allWeeks.filter{it.from<=today}
    if(weeks.isEmpty())return
    val initial=weeks.indexOfFirst{day in it.from..it.to}.let{if(it<0)weeks.lastIndex else it}
    var target by remember(day.year){mutableFloatStateOf(initial.toFloat())}
    val selected=target.roundToInt().coerceIn(0,weeks.lastIndex)
    val position by animateFloatAsState(target.coerceIn(0f,weeks.lastIndex.toFloat()),spring(dampingRatio=.9f,stiffness=850f),label="year trend selection")
    val monthly=remember(day.year,days){LongArray(12).also{totals->days.forEach{(date,ms)->if(date.year==day.year)totals[date.monthValue-1]+=ms}}}
    val extrema=remember(weeks){yearTrendExtrema(weeks)}
    val density=LocalDensity.current;val accent=Accent;val muted=Muted
    val focusWeek=weeks[position.roundToInt().coerceIn(0,weeks.lastIndex)];val month=focusWeek.from.monthValue
    Column(Modifier.fillMaxWidth().padding(top=14.dp).testTag("year-weekly-trend")) {
        Text("${month}月 · ${reportHours(monthly[month-1])}小时",Modifier.padding(start=12.dp).testTag("year-trend-month-duration"),fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=accent)
        Text("当周 ${focusWeek.from.monthValue}/${focusWeek.from.dayOfMonth}—${focusWeek.to.monthValue}/${focusWeek.to.dayOfMonth} · ${reportHours(focusWeek.ms)}小时",Modifier.padding(start=12.dp,top=2.dp).testTag("year-trend-week-duration"),fontSize=11.sp,fontWeight=FontWeight.Medium,color=muted)
    BoxWithConstraints(Modifier.fillMaxWidth().height(196.dp)){
        val width=maxWidth;val widthPx=with(density){width.toPx()};val paddingPx=with(density){12.dp.toPx()};val spacing=if(allWeeks.size>1)(widthPx-2*paddingPx)/(allWeeks.size-1)else 1f
        fun indexAt(x:Float)=((x-paddingPx)/spacing.coerceAtLeast(1f)).coerceIn(0f,weeks.lastIndex.toFloat())
        Canvas(Modifier.fillMaxHeight().width(width).testTag("year-weekly-trend-chart").semantics{stateDescription=selected.toString()}
            .pointerInput(weeks,spacing){detectTapGestures{p->target=indexAt(p.x).roundToInt().toFloat();onSelect(weeks[target.toInt()].from)}}
            .pointerInput(weeks,spacing){detectHorizontalDragGestures(onDragEnd={target=target.roundToInt().coerceIn(0,weeks.lastIndex).toFloat();onSelect(weeks[target.toInt()].from)},onDragCancel={target=target.roundToInt().coerceIn(0,weeks.lastIndex).toFloat()}){change,_->target=indexAt(change.position.x);change.consume()}}){
            val max=(weeks.maxOfOrNull{it.ms}?:0L).coerceAtLeast(1);val bottom=size.height-20.dp.toPx();val top=16.dp.toPx();val left=12.dp.toPx()
            val points=weeks.mapIndexed{i,v->Offset(left+i*spacing,bottom-(v.ms.toFloat()/max)*(bottom-top))}
            val line=Path().apply{moveTo(points[0].x,points[0].y);for(i in 1 until points.size){val a=points[i-1];val b=points[i];val mid=(a.x+b.x)/2;cubicTo(mid,a.y,mid,b.y,b.x,b.y)}}
            val fill=Path().apply{addPath(line);lineTo(points.last().x,bottom);lineTo(points.first().x,bottom);close()}
            val dash=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),4.dp.toPx()))
            for(month in 2..12){val date=LocalDate.of(day.year,month,1);val x=left+(date.dayOfYear-1).toFloat()/(date.lengthOfYear()-1)*(size.width-2*left);drawLine(muted.copy(alpha=.20f),Offset(x,top),Offset(x,bottom),.7.dp.toPx(),pathEffect=dash)}
            drawPath(fill,Brush.verticalGradient(listOf(accent.copy(alpha=.32f),accent.copy(alpha=.015f)),startY=top,endY=bottom))
            drawLine(muted.copy(alpha=.25f),Offset(left,bottom),Offset(size.width-left,bottom),1.dp.toPx())
            drawPath(line,accent,style=Stroke(width=2.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
            points.forEach{drawCircle(accent.copy(alpha=.55f),1.35.dp.toPx(),it)}
            val focused=yearTrendPosition(points,position)
            drawLine(accent.copy(alpha=.36f),Offset(focused.x,focused.y),Offset(focused.x,bottom),1.dp.toPx(),pathEffect=dash)
            val glow=11.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=.30f),accent.copy(alpha=.12f),Color.Transparent),center=focused,radius=glow),glow,focused)
            val core=4.2.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(Color.White,accent.copy(alpha=.8f)),center=focused-Offset(core*.22f,core*.24f),radius=core*1.45f),core,focused)
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=muted.toArgb();textSize=9.sp.toPx();textAlign=android.graphics.Paint.Align.CENTER}
            val native=drawContext.canvas.nativeCanvas
            for(month in 1..12){
                val first=LocalDate.of(day.year,month,1);val end=first.plusMonths(1);val yearLength=first.lengthOfYear().toFloat()
                val x=left+((first.dayOfYear-1)+(end.toEpochDay()-first.toEpochDay())/2f)/yearLength*(size.width-2*left)
                native.drawText("${month}月",x,size.height-3.dp.toPx(),paint)
            }
            val occupied=mutableListOf<androidx.compose.ui.geometry.Rect>()
            extrema.forEach{index->
                val p=points[index];val label=reportHours(weeks[index].ms)+"小时"
                paint.textSize=9.sp.toPx();paint.textAlign=android.graphics.Paint.Align.CENTER
                val half=paint.measureText(label)/2;val x=p.x.coerceIn(half,(size.width-half).coerceAtLeast(half))
                val peak=weeks[index].ms>weeks[index-1].ms
                val y=(if(peak)p.y-6.dp.toPx() else p.y+12.dp.toPx()).coerceIn(10.dp.toPx(),bottom-3.dp.toPx())
                val bounds=androidx.compose.ui.geometry.Rect(x-half-2.dp.toPx(),y-10.dp.toPx(),x+half+2.dp.toPx(),y+3.dp.toPx())
                if(occupied.none{it.overlaps(bounds)}){native.drawText(label,x,y,paint);occupied+=bounds}
            }
        }
    }
    }
}
