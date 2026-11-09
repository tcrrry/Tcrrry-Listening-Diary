package com.tcrrry.diary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.time.LocalDate

internal fun periodHistoryDates(range:DiaryRange,dates:List<LocalDate>,totals:Map<LocalDate,Long>,songs:Map<LocalDate,List<Play>> = emptyMap()):List<LocalDate> =
    if(range==DiaryRange.YEAR)dates.filter{month->month.dayOfMonth==1&&(totals.any{(date,ms)->date.year==month.year&&date.monthValue==month.monthValue&&ms>0}||songs.any{(date,rows)->date.year==month.year&&date.monthValue==month.monthValue&&rows.isNotEmpty()})}
    else dates.filter{(totals[it]?:0L)>0||songs[it].orEmpty().isNotEmpty()}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun PeriodHistoryPage(c:DiaryController,range:DiaryRange,dates:List<LocalDate>,songs:Map<LocalDate,List<Play>>,totals:Map<LocalDate,Long>,selected:LocalDate?,back:()->Unit,open:(Play,String)->Unit){
    BackHandler { back() }
    val periods=remember(range,dates,totals,songs){periodHistoryDates(range,dates,totals,songs)}
    val panel=LocalAlbumSurface.current;val actions=LocalDetailActions.current
    val initialIndex=selected?.let{periods.indexOf(it).takeIf{index->index>=0}}?.plus(1)?:0
    val list=rememberLazyListState(initialFirstVisibleItemIndex=initialIndex)
    LazyColumn(Modifier.fillMaxSize().historyPanel().testTag("period-history-page").semantics{this[HistoryScrollIndexKey]=list.firstVisibleItemIndex}.drawBehind{drawRect(panel())}.statusBarsPadding(),state=list,contentPadding=PaddingValues(start=17.dp,end=17.dp,bottom=30.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        stickyHeader { Column(Modifier.fillMaxWidth().drawBehind{drawRect(panel())}){Row(Modifier.press(back).padding(top=14.dp,bottom=13.dp),verticalAlignment=Alignment.CenterVertically){DiaryGlyph("back",Modifier.size(22.dp),color=Accent);Text("听歌日记",fontSize=16.sp,color=Accent)};Text("听歌历史",fontSize=34.sp,fontWeight=FontWeight.ExtraBold);Text("每${if(range==DiaryRange.YEAR)"月" else "天"}的最常听",Modifier.padding(vertical=12.dp),fontSize=13.sp,color=Muted)} }
        items(periods,key={it.toString()}){date->
            val relevant=if(range==DiaryRange.YEAR)songs.filterKeys{it.year==date.year&&it.monthValue==date.monthValue}.values.flatten() else songs[date].orEmpty()
            val ranked=remember(relevant){sortedPlays(relevant,SortOrder.COUNT)}
            val track=ranked.firstOrNull()
            val album=remember(relevant){relevant.filter{it.album.isNotBlank()}.groupBy{it.album to it.artist}.maxByOrNull{(_,v)->v.sumOf{it.effective}}}
            val artist=remember(relevant){relevant.filter{it.artist.isNotBlank()&&it.artist!="未知歌手"}.groupBy{it.artist}.maxByOrNull{(_,v)->v.sumOf{it.effective}}}
            val total=if(range==DiaryRange.YEAR)totals.filterKeys{it.year==date.year&&it.monthValue==date.monthValue}.values.sum() else totals[date]?:0L
            Column(Modifier.fillMaxWidth().testTag("period-history:$date").clip(RoundedCornerShape(20.dp)).background(Accent.copy(alpha=.05f)).padding(15.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(if(range==DiaryRange.YEAR)"${date.monthValue}月" else "${date.monthValue}月${date.dayOfMonth}日",fontSize=19.sp,fontWeight=FontWeight.Bold);Text(shortDuration(total),fontSize=12.sp,color=Muted)}
                if(track==null&&album==null&&artist==null)Text("歌曲资料未提供",Modifier.padding(top=10.dp),fontSize=13.sp,color=Muted)
                else Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    if(track!=null){val key="period-history-song:$date:${track.id}";Column(Modifier.weight(1f).testTag("period-history-song:$date").press{open(track,key)}){Artwork(c,track.id,Modifier.fillMaxWidth().aspectRatio(1f).historyCover(date.toString()).detailSource(key).clip(RoundedCornerShape(12.dp)),edge=160);Text(track.title,Modifier.padding(top=7.dp),fontSize=13.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);Text("${track.effective}次",fontSize=12.sp,color=Muted)}} else Spacer(Modifier.weight(1f))
                    if(album!=null){val (identity,items)=album;val cover=items.first();val key="period-history-album:$date:${identity.first}:${identity.second}";Column(Modifier.weight(1f).testTag("period-history-album:$date").press{actions?.album?.invoke(cover,key)}){Artwork(c,cover.id,Modifier.fillMaxWidth().aspectRatio(1f).detailSource(key).clip(RoundedCornerShape(12.dp)),edge=160);Text(identity.first,Modifier.padding(top=7.dp),fontSize=13.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);Text("${items.sumOf{it.effective}}次",fontSize=12.sp,color=Muted)}} else Spacer(Modifier.weight(1f))
                    if(artist!=null){val (name,items)=artist;val key="period-history-artist:$date:$name";Column(Modifier.weight(1f).testTag("period-history-artist:$date").press{actions?.artist?.invoke(name,key)}){Artwork(c,"artist:$name",Modifier.fillMaxWidth().aspectRatio(1f).detailSource(key).clip(RoundedCornerShape(12.dp)),edge=160);Text(name,Modifier.padding(top=7.dp),fontSize=13.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);Text("${items.sumOf{it.effective}}次",fontSize=12.sp,color=Muted)}} else Spacer(Modifier.weight(1f))
                }
            }
        }
        if(periods.isEmpty())item{Text("这个周期没有听歌记录",Modifier.padding(vertical=24.dp),color=Muted)}
    }
}

@Composable internal fun FavoriteTimelineRow(c:DiaryController,month:java.time.YearMonth,play:Play,open:(Play,String)->Unit,sourceSuffix:String="",modifier:Modifier=Modifier){
    val source="timeline:$month:${play.id}$sourceSuffix"
    Column(modifier.width(105.dp).testTag("favorite-timeline:$month").press{open(play,source)}){
        Text("${month.year}.${month.monthValue.toString().padStart(2,'0')}",fontSize=12.sp,color=Muted)
        Artwork(c,play.id,Modifier.padding(top=5.dp).size(92.dp).detailSource(source).clip(RoundedCornerShape(12.dp)),edge=160)
        Text(play.title,Modifier.padding(top=5.dp),fontSize=13.sp,maxLines=1)
        Text(play.artist,fontSize=11.sp,color=Muted,maxLines=1)
    }
}
