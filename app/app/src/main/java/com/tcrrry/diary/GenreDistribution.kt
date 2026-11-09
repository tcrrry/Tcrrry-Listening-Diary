package com.tcrrry.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Keep at most four prominent genres, with smaller genres sharing one last bar. */
internal fun genreDistributionGroups(styles:List<GenreStat>,maxRows:Int=5,minShare:Double=.05):Pair<List<GenreStat>,List<GenreStat>> {
    val genres=styles.filter{it.name!="未匹配"&&it.ms>0}.sortedByDescending{it.ms}
    val total=genres.sumOf{it.ms}.coerceAtLeast(1)
    val major=genres.filter{it.ms.toDouble()/total>=minShare}.take((maxRows-1).coerceAtLeast(1)).toMutableList()
    val minor=genres.filter{it !in major}.toMutableList()
    if(minor.size==1&&major.size<maxRows)major+=minor.removeAt(0)
    return major to minor
}

/** Percentages describe the identified genres only. */
@Composable internal fun GenreDistribution(styles:List<GenreStat>,modifier:Modifier=Modifier,maxRows:Int=5,minShare:Double=.05){
    val (major,minor)=remember(styles,maxRows,minShare){genreDistributionGroups(styles,maxRows,minShare)}
    val genres=remember(major,minor){major+minor}
    if(genres.isEmpty()){Text("暂无风格数据",modifier,fontSize=12.sp,color=Muted);return}
    val total=remember(genres){genres.sumOf{it.ms}.coerceAtLeast(1)}
    fun percent(ms:Long)=String.format(Locale.ROOT,"%.1f",ms.toDouble()*100/total).removeSuffix(".0")+"%"
    Column(modifier.fillMaxWidth().testTag("genre-distribution"),verticalArrangement=Arrangement.spacedBy(13.dp)){
        Text("已识别风格",fontSize=11.sp,color=Muted)
        major.forEachIndexed{index,stat->
            val tint=genreTint(index,Accent)
            Column(Modifier.fillMaxWidth().testTag("genre-major:${stat.name}"),verticalArrangement=Arrangement.spacedBy(7.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.size(7.dp).background(tint,CircleShape))
                    Text(stat.name,Modifier.weight(1f).padding(start=8.dp),fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
                    Text("${percent(stat.ms)} · ${shortDuration(stat.ms)}",fontSize=11.sp,color=Muted)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Muted.copy(alpha=.08f))){
                    Box(Modifier.fillMaxWidth((stat.ms.toFloat()/total).coerceIn(0f,1f)).fillMaxHeight().background(tint,CircleShape))
                }
            }
        }
        if(minor.isNotEmpty()){
            val combined=minor.sumOf{it.ms}.coerceAtLeast(1)
            Column(Modifier.fillMaxWidth().testTag("genre-minor-group"),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Muted.copy(alpha=.08f))){
                    Row(Modifier.fillMaxWidth((combined.toFloat()/total).coerceIn(0f,1f)).fillMaxHeight()){
                        minor.forEachIndexed{index,stat->Box(Modifier.weight(stat.ms.toFloat()/combined).fillMaxHeight().background(genreTint(index+major.size,Accent)))}
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min=40.dp).horizontalScroll(rememberScrollState()).testTag("genre-distribution-legend"),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically){
                    minor.forEachIndexed{index,stat->Row(verticalAlignment=Alignment.CenterVertically){
                        Box(Modifier.size(6.dp).background(genreTint(index+major.size,Accent),CircleShape))
                        Text(" ${stat.name} · ${percent(stat.ms)}",fontSize=12.sp,color=Muted,maxLines=1)
                    }}
                }
            }
        }
    }
}
