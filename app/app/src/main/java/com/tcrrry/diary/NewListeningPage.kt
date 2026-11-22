package com.tcrrry.diary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun discoveryArtistMembers(name:String):List<String> =
    name.split(Regex("""\s+(?:&|feat\.|featuring)\s+|,\s+|、""",RegexOption.IGNORE_CASE)).map{it.trim()}.filter{it.isNotEmpty()}

@Composable private fun rememberNewListening(c:DiaryController,range:DiaryRange,report:DiaryReport):WeeklyExtras? {
    val data by produceState<WeeklyExtras?>(null,c.day,range,c.dayPolicy,c.demo,c.dataRevision,c.previewMetadataEpoch,report.history) {
        try {
            val candidates=withContext(Dispatchers.Default){report.history.filter{!it.imported||it.title!="未知歌曲"}}
            val songs=candidates.map{it.id}.toSet()
            val artists=candidates.map{it.artist}.filter{AppleTrackImages.knownArtist(it)}.toSet()
            val discovered=if(c.demo)songs to artists else withContext(Dispatchers.IO){
                DiaryDatabase.discovery(c.activity).periodNewIdentities(c.day,range,c.dayPolicy,songs,artists)
            }
            value=withContext(Dispatchers.Default){
                val firstTimes=candidates.groupBy{it.id}.mapValues{(_,plays)->plays.minOf{it.start}}
                WeeklyExtras(
                    sortedPlays(candidates.filter{it.id in discovered.first},SortOrder.COUNT).map{play->
                        play.copy(start=firstTimes.getValue(play.id))
                    },
                    artistWeekGroups(report).filter{it.title in discovered.second}.map{group->
                        group.copy(songs=group.songs.map{play->play.copy(start=firstTimes[play.id]?:play.start)})
                    }
                )
            }
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){value=WeeklyExtras(emptyList(),emptyList())}
    }
    return data
}

@Composable internal fun NewListeningSection(c:DiaryController,range:DiaryRange,report:DiaryReport,open:(Play,String)->Unit,all:(Boolean)->Unit) {
    val data=rememberNewListening(c,range,report)
    var artists by rememberSaveable(c.day,range){mutableStateOf(false)}
    Column(Modifier.padding(horizontal=18.dp).testTag("weekly-new")) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text("${detailPeriodName(range)}新增",Modifier.weight(1f),fontSize=23.sp,fontWeight=FontWeight.Bold)
            SmallTabs(listOf("歌曲","歌手"),if(artists)1 else 0){artists=it==1}
            Text("全部 ›",Modifier.padding(start=12.dp).testTag("all-new").press{all(artists)},fontSize=13.sp,color=Muted)
        }
        NewListeningRows(c,data,artists,5,open)
    }
}

@Composable internal fun NewListeningPage(c:DiaryController,back:()->Unit,open:(Play,String)->Unit,initialArtists:Boolean=false) {
    BackHandler{back()}
    val surface=LocalAlbumSurface.current
    val data=rememberNewListening(c,c.range,c.report)
    var artists by rememberSaveable{mutableStateOf(initialArtists)}
    val songsState=rememberLazyListState()
    val artistsState=rememberLazyListState()
    Column(Modifier.fillMaxSize().drawBehind{drawRect(surface())}.statusBarsPadding().testTag("new-listening-page")){
        Row(Modifier.padding(horizontal=20.dp,vertical=14.dp).press(back)){
            DiaryGlyph("back",Modifier.size(22.dp),color=Accent)
            Text("听歌日记",fontSize=16.sp,color=Accent)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=16.dp),verticalAlignment=Alignment.CenterVertically){
            Text("${detailPeriodName(c.range)}新增",Modifier.weight(1f),fontSize=30.sp,fontWeight=FontWeight.ExtraBold)
            SmallTabs(listOf("歌曲","歌手"),if(artists)1 else 0){artists=it==1}
        }
        if(data==null)Text("正在整理首次收听记录…",Modifier.padding(20.dp),color=Muted)
        else LazyColumn(state=if(artists)artistsState else songsState,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=10.dp)){
            if(artists){
                if(data.newArtists.isEmpty())item{Text("这段时间没有新增歌手",color=Muted)}
                itemsIndexed(data.newArtists,key={_,g->g.key}){index,group->NewArtistRow(c,group,index)}
            }else{
                if(data.newSongs.isEmpty())item{Text("这段时间没有新增歌曲",color=Muted)}
                itemsIndexed(data.newSongs,key={_,p->p.id}){index,play->NewSongRow(c,play,index,open)}
            }
        }
    }
}

@Composable private fun NewListeningRows(c:DiaryController,data:WeeklyExtras?,artists:Boolean,limit:Int,open:(Play,String)->Unit){
    if(data==null)Text("正在整理首次收听记录…",Modifier.padding(top=12.dp),fontSize=12.sp,color=Muted)
    else if(artists){
        if(data.newArtists.isEmpty())Text("这段时间没有新增歌手",Modifier.padding(top=12.dp),fontSize=12.sp,color=Muted)
        data.newArtists.take(limit).forEachIndexed{index,group->NewArtistRow(c,group,index)}
    }else{
        if(data.newSongs.isEmpty())Text("这段时间没有新增歌曲",Modifier.padding(top=12.dp),fontSize=12.sp,color=Muted)
        data.newSongs.take(limit).forEachIndexed{index,play->NewSongRow(c,play,index,open)}
    }
}

@Composable private fun NewSongRow(c:DiaryController,play:Play,index:Int,open:(Play,String)->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).press{open(play,"discovery-song:${play.id}")}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
        Text((index+1).toString().padStart(2,'0'),Modifier.width(38.dp),fontSize=14.sp,color=Muted)
        Artwork(c,play.id,Modifier.size(43.dp).clip(RoundedCornerShape(9.dp)),edge=160)
        Column(Modifier.weight(1f).padding(horizontal=11.dp)){
            Text(play.title,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(play.artist,fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(c.range!=DiaryRange.DAY)Text("首次收听 · "+dailyRecordTime(play,c.use12Hour,false),fontSize=11.sp,color=Muted)
        }
        Text("${play.effective} 次",fontSize=14.sp,color=Muted)
    }
}

@Composable private fun NewArtistRow(c:DiaryController,group:LibraryGroup,index:Int){
    val actions=LocalDetailActions.current
    val first=group.songs.minByOrNull{it.start}
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).press{actions?.artist?.invoke(group.title,"discovery-artist:${group.key}")}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
        Text((index+1).toString().padStart(2,'0'),Modifier.width(38.dp),fontSize=14.sp,color=Muted)
        Artwork(c,group.image,Modifier.size(43.dp).clip(CircleShape),edge=160)
        Column(Modifier.weight(1f).padding(horizontal=11.dp)){
            Text(group.title,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${group.songs.size}首 · ${group.plays}次",fontSize=12.sp,color=Muted)
            if(first!=null&&c.range!=DiaryRange.DAY)Text("首次收听 · "+dailyRecordTime(first,c.use12Hour,false),fontSize=11.sp,color=Muted)
        }
    }
}
