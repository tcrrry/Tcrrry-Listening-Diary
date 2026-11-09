package com.tcrrry.diary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth

internal data class AllYearSlice(val year:Int,val duration:Long,val artists:Int,val songs:Int)
internal fun allYearSlices(rows:List<ListeningDay>):List<AllYearSlice>{
    data class Acc(var duration:Long=0,val artists:MutableSet<String> = hashSetOf(),val songs:MutableSet<String> = hashSetOf())
    val years=sortedMapOf<Int,Acc>()
    rows.forEach{row->val p=row.play;val acc=years.getOrPut(row.date.year){Acc()};acc.duration+=p.ms
        if(p.artist.isNotBlank()&&p.artist!="未知歌手")acc.artists+=p.artist
        if(p.id.isNotBlank()&&p.title!="未知歌曲")acc.songs+=p.id
    }
    return years.map{(year,v)->AllYearSlice(year,v.duration,v.artists.size,v.songs.size)}
}
internal data class AllTrailEntry(val month:YearMonth,val song:Play?,val album:Play?,val artist:String?,val songMs:Long,val albumMs:Long,val artistMs:Long,val artistDays:Int,val artistTopTrack:String?)
internal fun allTrailEntries(facts:SummaryFacts):List<AllTrailEntry> = facts.months.mapNotNull{(month,rows)->
    if(rows.isEmpty())return@mapNotNull null
    val songs=rows.filter{it.play.title!="未知歌曲"}.groupBy{it.play.id}.maxByOrNull{(_,v)->v.sumOf{it.play.ms}}?.value
    val albums=rows.filter{it.play.album.isNotBlank()}.groupBy{it.play.album to it.play.artist}.maxByOrNull{(_,v)->v.sumOf{it.play.ms}}?.value
    val artists=rows.filter{it.play.artist.isNotBlank()&&it.play.artist!="未知歌手"}.groupBy{it.play.artist}.maxByOrNull{(_,v)->v.sumOf{it.play.ms}}
    val artistRows=artists?.value.orEmpty()
    val artistTrack=artistRows.filter{it.play.title!="未知歌曲"}.groupBy{it.play.id}.maxByOrNull{(_,v)->v.sumOf{it.play.ms}}?.value?.firstOrNull()?.play?.title
    AllTrailEntry(month,songs?.firstOrNull()?.play,albums?.firstOrNull()?.play,artists?.key,songs?.sumOf{it.play.ms}?:0,albums?.sumOf{it.play.ms}?:0,artistRows.sumOf{it.play.ms},artistRows.map{it.date}.distinct().size,artistTrack)
}

internal data class AnnualGenrePoint(val year:Int,val total:Long,val genres:Map<String,Long>)
internal fun annualGenrePoints(facts:SummaryFacts,catalog:Map<String,CatalogFact>):List<AnnualGenrePoint>{
    val fallback=buildArtistGenreFallback(facts.songs,catalog)
    val lookup=HashMap<String,String>();val years=sortedMapOf<Int,MutableMap<String,Long>>()
    for(row in facts.days){val p=row.play;val key=p.id+"\u0000"+p.artist+"\u0000"+p.title
        val style=lookup.getOrPut(key){if(p.imported&&p.title=="未知歌曲")"未匹配" else normalizeMusicGenre(catalog[p.id]?.genre,p,fallback[p.artist])}
        val values=years.getOrPut(row.date.year){mutableMapOf()};values[style]=(values[style]?:0L)+p.ms
    }
    if(years.isEmpty())return emptyList()
    return (years.firstKey()..years.lastKey()).map{year->val values=years[year].orEmpty();AnnualGenrePoint(year,values.values.sum(),values)}
}

internal data class PreferencePoint(val month:YearMonth,val total:Long,val artists:Map<String,Long>,val genres:Map<String,Long>,val exploration:Map<String,Long>)
internal fun preferencePoints(facts:SummaryFacts,catalog:Map<String,CatalogFact>):List<PreferencePoint>{
    val songMonths=HashMap<String,MutableSet<YearMonth>>()
    facts.days.forEach{row->if(row.play.title!="未知歌曲"&&row.play.id.isNotBlank())songMonths.getOrPut(row.play.id){hashSetOf()}.add(YearMonth.from(row.date))}
    val explorationByMonth=HashMap<Pair<String,YearMonth>,String>()
    songMonths.forEach{(id,months)->var previous:YearMonth?=null;months.sorted().forEach{month->val old=previous;explorationByMonth[id to month]=when{old==null->"探索新歌";java.time.temporal.ChronoUnit.MONTHS.between(old,month)>=4->"回顾老歌";else->"持续聆听"};previous=month}}
    val fallback=buildArtistGenreFallback(facts.songs,catalog);val cache=HashMap<String,String>()
    data class Acc(val artists:MutableMap<String,Long> = hashMapOf(),val genres:MutableMap<String,Long> = hashMapOf(),val exploration:MutableMap<String,Long> = hashMapOf())
    val acc=sortedMapOf<YearMonth,Acc>()
    fun add(map:MutableMap<String,Long>,name:String,ms:Long){map[name]=(map[name]?:0)+ms}
    facts.days.forEach{row->val p=row.play;val month=YearMonth.from(row.date);val item=acc.getOrPut(month){Acc()};val artist=p.artist.takeIf{it.isNotBlank()&&it!="未知歌手"}?:"未识别"
        val key=p.id+"\u0000"+p.artist+"\u0000"+p.title;val genre=cache.getOrPut(key){if(p.imported&&p.title=="未知歌曲")"未匹配" else normalizeMusicGenre(catalog[p.id]?.genre,p,fallback[p.artist])}
        val category=if(p.title=="未知歌曲")"未识别" else explorationByMonth[p.id to month]?:"未识别"
        add(item.artists,artist,p.ms);add(item.genres,genre,p.ms);add(item.exploration,category,p.ms)
    }
    if(acc.isEmpty())return emptyList()
    val result=mutableListOf<PreferencePoint>();var month=acc.firstKey();val last=acc.lastKey()
    while(month<=last){val a=acc[month];result+=if(a==null)PreferencePoint(month,0L,emptyMap(),emptyMap(),emptyMap())else PreferencePoint(month,a.artists.values.sum(),a.artists,a.genres,a.exploration);month=month.plusMonths(1)}
    return result
}
internal fun preferenceAggregate(points:List<PreferencePoint>,quarterly:Boolean):List<PreferencePoint>{
    return points.groupBy{if(quarterly)YearMonth.of(it.month.year,((it.month.monthValue-1)/3)*3+1) else YearMonth.of(it.month.year,1)}.toSortedMap().map{(period,rows)->
        fun merge(extract:(PreferencePoint)->Map<String,Long>):Map<String,Long>{val result=mutableMapOf<String,Long>();rows.forEach{p->extract(p).forEach{(name,ms)->result[name]=(result[name]?:0)+ms}};return result}
        PreferencePoint(period,rows.sumOf{it.total},merge{it.artists},merge{it.genres},merge{it.exploration})
    }
}
internal fun preferenceByYear(points:List<PreferencePoint>)=preferenceAggregate(points,false)
internal fun preferenceLowCeiling(maps:List<Map<String,Long>>,layers:List<String>,dominant:String?=layers.firstOrNull()):Float {
    val secondary=layers.filter{it!=dominant};if(secondary.isEmpty())return 1f
    val max=maps.maxOfOrNull{map->val total=preferenceVisibleTotal(map,layers);if(total>0)secondary.maxOf{(map[it]?:0).toFloat()/total} else 0f}?:0f
    return listOf(.05f,.1f,.2f,.25f,.5f,.75f,1f).firstOrNull{it>=max}?:1f
}
internal data class PreferenceAxis(val low:Float,val high:Float)
/** Favor a recent cluster of meaningful secondary shares rather than the many zero values. */
internal fun preferenceAxisRange(maps:List<Map<String,Long>>,layers:List<String>,dominant:String?):PreferenceAxis? {
    if(layers.size<2)return null
    val bins=FloatArray(10)
    maps.takeLast(4).forEachIndexed{index,map->val total=preferenceVisibleTotal(map,layers);if(total>0)layers.filter{it!=dominant}.forEach{name->val v=(map[name]?:0L).toFloat()/total;if(v>0f){val bin=(v*10).toInt().coerceIn(0,9);bins[bin]+=kotlin.math.sqrt(v)*(index+1)}}}
    val best=bins.indices.maxByOrNull{bins[it]}?:return null
    if(bins[best]<=0f)return null
    return PreferenceAxis(best/10f,(best+1)/10f)
}
internal fun preferenceAxisPosition(value:Float,axis:PreferenceAxis?,dynamic:Boolean):Float {
    val v=value.coerceIn(0f,1f);if(!dynamic||axis==null)return v
    val low=axis.low;val high=axis.high;val yLow=if(low<=0f)0f else .35f;val yHigh=if(high>=1f)1f else .85f
    return when{v<low&&low>0f->v/low*yLow;v<=high->yLow+(v-low)/(high-low)*(yHigh-yLow);high<1f->yHigh+(v-high)/(1f-high)*(1f-yHigh);else->1f}
}
internal fun preferenceVisibleLayers(maps:List<Map<String,Long>>,mode:Int):List<String>{
    if(mode==2)return listOf("持续聆听","回顾老歌","探索新歌")
    val sums=mutableMapOf<String,Long>()
    maps.forEach{it.forEach{(name,ms)->if(name!="未识别"&&name!="未匹配")sums[name]=(sums[name]?:0)+ms}}
    return sums.entries.sortedByDescending{it.value}.take(7).map{it.key}
}
internal fun preferenceVisibleTotal(map:Map<String,Long>,layers:List<String>):Long=layers.sumOf{map[it]?:0L}
@Composable private fun PreferenceTabGroup(labels:List<String>,selected:Int,modifier:Modifier=Modifier,onSelect:(Int)->Unit){
    val muted=Muted;val accent=Accent;val surface=MaterialTheme.colorScheme.surface
    Row(modifier.height(33.dp).clip(CircleShape).background(muted.copy(alpha=.08f)).padding(2.dp)){
        labels.forEachIndexed{i,label->val color by animateColorAsState(if(i==selected)surface.copy(alpha=.96f) else androidx.compose.ui.graphics.Color.Transparent,tween(180),label="preference tab")
            Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(color).press{onSelect(i)},contentAlignment=Alignment.Center){Text(label,fontSize=12.sp,color=if(i==selected)accent else muted)}
        }
    }
}

@Composable internal fun AllYearRings(facts:SummaryFacts,catalog:Map<String,CatalogFact>,open:()->Unit){
    val monthly by produceState<List<PreferencePoint>?>(null,facts,catalog){value=withContext(Dispatchers.Default){preferencePoints(facts,catalog)}}
    var mode by remember{mutableIntStateOf(0)};var granularity by remember{mutableIntStateOf(2)};var selected by remember(facts){mutableIntStateOf(-1)}
    val dynamicAxis=true
    val points=remember(monthly,granularity){val data=monthly.orEmpty();when(granularity){1->preferenceAggregate(data,true);2->preferenceByYear(data);else->data}}
    val maps=remember(points,mode){points.map{when(mode){0->it.artists;1->it.genres;else->it.exploration}}}
    val layers=remember(maps,mode){preferenceVisibleLayers(maps,mode)}
    val dominant=remember(maps,layers){layers.maxByOrNull{name->maps.sumOf{it[name]?:0L}}}
    val visibleLayers=layers
    val axis=remember(maps,layers,dominant){preferenceAxisRange(maps,layers,dominant)}
    val accent=Accent;val muted=Muted
    Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(muted.copy(alpha=.045f)).padding(17.dp).testTag("all-history-trail")){
        Row(Modifier.fillMaxWidth().press(open),verticalAlignment=Alignment.CenterVertically){Text("偏好轨迹",Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.Bold);Text("查看详情",fontSize=12.sp,color=muted);DiaryGlyph("next",Modifier.size(15.dp),color=muted)}
        Spacer(Modifier.height(12.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            PreferenceTabGroup(listOf("歌手","风格","探索"),mode,Modifier.weight(1.35f)){mode=it}
            PreferenceTabGroup(listOf("年","季","月"),2-granularity,Modifier.weight(1f)){granularity=2-it;selected=-1}
        }
        Text(listOf("历年最爱歌手变迁","历年音乐风格变迁","音乐探索与重温")[mode],Modifier.padding(top=15.dp),fontSize=17.sp,fontWeight=FontWeight.Bold)
        Text(if(mode==2)"新歌：首次听到 · 老歌：间隔至少三个月未听" else "按已展示的主要${if(mode==0)"歌手" else "风格"}计算占比",Modifier.padding(top=4.dp),fontSize=11.sp,color=muted)
        if(monthly==null)Text("正在整理数据…",Modifier.padding(top=16.dp),fontSize=12.sp,color=muted)
        else if(points.isEmpty())Text("暂无数据",Modifier.padding(top=16.dp),fontSize=12.sp,color=muted)
        else {
            val index=selected.takeIf{it in points.indices}?:points.lastIndex;val point=points[index]
            Canvas(Modifier.fillMaxWidth().padding(top=12.dp).height(170.dp).testTag("all-year-area").pointerInput(points){detectTapGestures{pos->val left=34.dp.toPx();val right=size.width-16.dp.toPx();selected=if(points.size==1)0 else (((pos.x-left)/(right-left))*(points.size-1)).roundToInt().coerceIn(0,points.lastIndex)}}){
                val left=34.dp.toPx();val right=size.width-16.dp.toPx();val top=8.dp.toPx();val bottom=size.height-24.dp.toPx();val height=bottom-top
                fun x(i:Int)=if(points.size==1)(left+right)/2 else left+(right-left)*i/(points.size-1)
                fun amount(map:Map<String,Long>,category:String):Long=map[category]?:0L
                visibleLayers.forEach{name->val layer=layers.indexOf(name);val values=FloatArray(points.size){i->val total=preferenceVisibleTotal(maps[i],layers);if(total>0)amount(maps[i],name).toFloat()/total else 0f}
                    fun y(v:Float)=bottom-height*preferenceAxisPosition(v,axis,dynamicAxis)
                    val path=Path();var previous=-1
                    values.forEachIndexed{i,value->if(preferenceVisibleTotal(maps[i],layers)==0L){previous=-1}else{if(previous<0)path.moveTo(x(i),y(value))else{val mid=(x(previous)+x(i))/2;path.cubicTo(mid,y(values[previous]),mid,y(value),x(i),y(value))};previous=i}}
                    val tint=genreTint(layer,accent)
                    drawPath(path,tint.copy(alpha=.8f),style=Stroke(width=2.dp.toPx(),cap=StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))
                    values.forEachIndexed{i,value->if(preferenceVisibleTotal(maps[i],layers)>0&&(i==0||preferenceVisibleTotal(maps[i-1],layers)==0L)&&(i==points.lastIndex||preferenceVisibleTotal(maps[i+1],layers)==0L))drawCircle(tint,3.dp.toPx(),Offset(x(i),y(value)))}
                }
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=muted.toArgb();textSize=9.sp.toPx();textAlign=android.graphics.Paint.Align.RIGHT}
                val ticks=if(dynamicAxis&&axis!=null)listOf(0f,axis.low/2,axis.low,(axis.low+axis.high)/2,axis.high,1f).distinct() else listOf(0f,.25f,.5f,.75f,1f)
                ticks.forEach{tick->val y=bottom-height*preferenceAxisPosition(tick,axis,dynamicAxis);val label=tick*100;val value=if(kotlin.math.abs(label-label.roundToInt())<.01f)label.roundToInt().toString() else "%.1f".format(java.util.Locale.ROOT,label);drawLine(muted.copy(alpha=.12f),Offset(left,y),Offset(right,y),.5.dp.toPx());drawContext.canvas.nativeCanvas.drawText("$value%",left-5.dp.toPx(),y+3.dp.toPx(),paint)}
                paint.textAlign=android.graphics.Paint.Align.CENTER;val stride=if(points.size>5)(points.size+4)/5 else 1
                points.forEachIndexed{i,p->if(i%stride==0||i==points.lastIndex)drawContext.canvas.nativeCanvas.drawText(when(granularity){2->"${p.month.year}";1->"${p.month.year%100}·Q${(p.month.monthValue-1)/3+1}";else->"${p.month.year%100}.${p.month.monthValue.toString().padStart(2,'0')}"},x(i),size.height-3.dp.toPx(),paint)}
            }
            Row(Modifier.fillMaxWidth().padding(top=8.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)){visibleLayers.forEach{name->val i=layers.indexOf(name);val denominator=preferenceVisibleTotal(maps[index],layers);val pct=if(denominator>0)(maps[index][name]?:0L).toDouble()*100/denominator else 0.0;Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).background(genreTint(i,accent),CircleShape));Text(" $name ${String.format(java.util.Locale.ROOT,"%.1f",pct)}%",fontSize=11.sp,color=muted)}}}
            Text("${when(granularity){2->"${point.month.year}年";1->"${point.month.year}年 第${(point.month.monthValue-1)/3+1}季度";else->"${point.month.year}年${point.month.monthValue}月"}} · ${reportHours(point.total)}小时"+(if(preferenceVisibleTotal(maps[index],layers)==0L)" · 暂无可展示分类" else ""),Modifier.padding(top=10.dp),fontSize=12.sp,color=accent,fontWeight=FontWeight.SemiBold)
        }
    }
}


