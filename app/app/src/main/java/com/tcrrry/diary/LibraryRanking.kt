@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.tcrrry.diary
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

data class LibraryGroup(val key:String,val title:String,val subtitle:String,val songs:List<Play>,val image:String){val ms get()=songs.sumOf{it.ms};val plays get()=songs.sumOf{it.effective}}
internal fun libraryGroups(r:DiaryReport,artists:Boolean):List<LibraryGroup> {
    return if(artists)r.history.filter{!it.imported||it.artist!="未知歌手"}.groupBy{it.artist}.map{(name,raw)->LibraryGroup("artist:$name",name,"",sortedPlays(raw,SortOrder.COUNT),"artist:$name")}.sortedWith(compareByDescending<LibraryGroup>{it.ms}.thenBy{it.title})
    else libraryAlbumGroups(reportAlbums(r))
}
internal fun libraryAlbumGroups(albums:List<AlbumStat>):List<LibraryGroup> = albums.map{album->LibraryGroup("album:${album.key}",album.title,album.artist,sortedPlays(album.songs,SortOrder.COUNT),album.cover.id)}.sortedWith(compareByDescending<LibraryGroup>{it.plays}.thenByDescending{it.ms}.thenBy{it.title})
@Composable internal fun LibraryRanking(c:DiaryController,artists:Boolean,initialArtist:String,back:()->Unit,open:(Play,String)->Unit,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,initialAlbum:Boolean=false,reportOverride:DiaryReport?=null,artistFilter:String?=null){
    BackHandler{back()}
    var tab by rememberSaveable{mutableIntStateOf(if(initialAlbum)1 else 0)}
    var expanded by rememberSaveable{mutableStateOf(if(initialArtist.isBlank())listOf<String>()else listOf("artist:$initialArtist"))}
    val detail=LocalDetailActions.current
    val r=reportOverride?:c.report;val groups=remember(r.history,artists){libraryGroups(r,artists)}
    val songs=remember(r.history){sortedPlays(r.history.filter{!it.imported||it.title!="未知歌曲"},SortOrder.COUNT)}
    val currentSongRanks=remember(songs){songs.mapIndexed{i,p->p.id to i+1}.toMap()}
    val currentGroupRanks=remember(groups){groups.mapIndexed{i,g->g.key to i+1}.toMap()}
    val oldRanks by produceState<LibraryPreviousRanks?>(null,c.day,c.range,c.dayPolicy,c.demo,c.dataRevision,artists,artistFilter,c.summary){
        if(c.summary||c.range==DiaryRange.DAY){value=null;return@produceState}
        value=null
        try{
            val previous=if(c.demo)withContext(Dispatchers.Default){periodDemoReport(periodShift(c.day,c.range,-1),c.range)}else withContext(Dispatchers.IO){DiaryDatabase.get(c.activity).readReportVersioned(bounds(periodShift(c.day,c.range,-1),c.range),c.dayPolicy).first}
            value=withContext(Dispatchers.Default){
                val scoped=if(artistFilter==null)previous else previous.copy(history=previous.history.filter{it.artist==artistFilter},segments=previous.segments.filter{it.artist==artistFilter})
                val oldSongs=sortedPlays(scoped.history.filter{!it.imported||it.title!="未知歌曲"},SortOrder.COUNT).mapIndexed{i,p->p.id to i+1}.toMap()
                val oldGroups=libraryGroups(scoped,artists).mapIndexed{i,g->g.key to i+1}.toMap()
                LibraryPreviousRanks(oldSongs,oldGroups)
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){value=null}
    }

    val list=rememberLazyListState();val songList=rememberLazyListState();val albumList=rememberLazyListState();val panel=LocalAlbumSurface.current
    var query by rememberSaveable{mutableStateOf("")};var searchVisible by rememberSaveable{mutableStateOf(false)}
    var pull by remember{mutableFloatStateOf(0f)}
    val activeList by rememberUpdatedState(if(artists)list else if(tab==0)songList else albumList)
    val threshold=with(LocalDensity.current){48.dp.toPx()}
    val reveal=remember(threshold){object:NestedScrollConnection{
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset{
            if(source==NestedScrollSource.UserInput&&activeList.firstVisibleItemIndex==0&&activeList.firstVisibleItemScrollOffset==0&&available.y>0){pull+=available.y;if(pull>threshold)searchVisible=true}
            else if(available.y<0){pull=0f;if(query.isBlank()&&available.y < -12f)searchVisible=false}
            return Offset.Zero
        }
        override suspend fun onPreFling(available:Velocity):Velocity{pull=0f;return Velocity.Zero}
    }}
    fun matches(p:Play)=p.title.contains(query,true)||p.artist.contains(query,true)||p.album.contains(query,true)
    val visibleSongs=songs.filter{query.isBlank()||matches(it)}
    val visibleGroups=groups.mapNotNull{g->if(query.isBlank()||g.title.contains(query,true)||g.subtitle.contains(query,true))g else g.songs.filter{matches(it)}.takeIf{it.isNotEmpty()}?.let{g.copy(songs=it)}}
    LaunchedEffect(initialArtist){if(artists&&initialArtist.isNotBlank()){val index=groups.indexOfFirst{it.title==initialArtist};if(index>=0)list.scrollToItem(index+2)}}
    Column(Modifier.fillMaxSize().drawBehind{drawRect(panel())}.statusBarsPadding().testTag(if(artists)"artist-ranking" else "favorite-ranking").nestedScroll(reveal)){
        Row(Modifier.padding(horizontal=20.dp,vertical=14.dp).press(back).semantics{contentDescription="返回日记"},verticalAlignment=Alignment.CenterVertically){DiaryGlyph("back",Modifier.size(22.dp),color=Accent);Text("听歌日记",fontSize=16.sp,color=Accent)}
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=16.dp),verticalAlignment=Alignment.CenterVertically){
            Text(if(artists)"常听歌手" else "常听排行",Modifier.weight(1f),fontSize=30.sp,fontWeight=FontWeight.ExtraBold)
            if(!artists)SmallTabs(listOf("歌曲","专辑"),tab){tab=it}
        }
        AnimatedContent(if(artists)2 else tab,Modifier.weight(1f),transitionSpec={val direction=if(targetState>initialState)1 else -1;fadeIn(tween(250))+slideInHorizontally{it/10*direction} togetherWith fadeOut(tween(150))+slideOutHorizontally{-it/12*direction}},label="ranking type"){mode->
            LazyColumn(state=if(mode==2)list else if(mode==0)songList else albumList,modifier=Modifier.fillMaxSize().testTag("ranking-list:$mode"),contentPadding=PaddingValues(start=17.dp,end=17.dp,bottom=30.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                item("summary"){Text(if(mode==0)"${songs.size}首歌曲 · 按播放次数" else "${groups.size}${if(artists)"位歌手" else "张专辑"}",Modifier.padding(start=4.dp,bottom=8.dp),fontSize=13.sp,color=Muted)}
                item("search"){AnimatedVisibility(searchVisible,enter=expandVertically(spring(.9f,300f))+fadeIn(tween(220)),exit=shrinkVertically(tween(180))+fadeOut(tween(130))){OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth().padding(bottom=12.dp).testTag("ranking-search"),singleLine=true,shape=RoundedCornerShape(16.dp),placeholder={Text(if(artists)"搜索歌手或歌曲" else "搜索歌曲、专辑或歌手",fontSize=15.sp,color=Muted)})}}
                if(mode==0)itemsIndexed(visibleSongs,key={_,p->p.id}){i,p->val rank=currentSongRanks[p.id]?:i+1;LibrarySong(c,p,"song-rank:${p.id}",rank,open,shared,visibility,oldRanks?.let{RankChange(rank,it.songs[p.id])})}
                else itemsIndexed(visibleGroups,key={_,g->g.key}){i,g->
                    val source="ranking-group:${g.key}"
                    val opened=query.isNotBlank()||g.key in expanded
                    val turn by animateFloatAsState(if(opened)180f else 0f,spring(.86f,300f),label="group arrow")
                    Column(Modifier.animateItem(fadeInSpec=tween(220),placementSpec=spring(.9f,300f),fadeOutSpec=tween(150)).fillMaxWidth().clip(RoundedCornerShape(21.dp)).background(Accent.copy(alpha=.045f)).animateContentSize(spring(.9f,300f))){
                        val rank=currentGroupRanks[g.key]?:i+1
                        LibraryGroupHeading(c,g,rank,source,artists,turn,Modifier.testTag("library-group:${g.key}"),oldRanks?.let{RankChange(rank,it.groups[g.key])}){
                            if(artists)detail?.artist?.invoke(g.title,source) else g.songs.firstOrNull()?.let{detail?.album?.invoke(it,source)}
                        }
                        AnimatedVisibility(opened,enter=expandVertically(spring(.9f,300f))+fadeIn(tween(220)),exit=shrinkVertically(tween(210))+fadeOut(tween(140))){Column(Modifier.padding(start=8.dp,end=8.dp,bottom=8.dp)){g.songs.forEachIndexed{rank,p->LibrarySong(c,p,"${g.key}:${p.id}",rank+1,open,shared,visibility)}}}
                    }
                }
                if((mode==0&&visibleSongs.isEmpty())||(mode!=0&&visibleGroups.isEmpty()))item{Text(if(query.isBlank())"这一段还没有记录"else "没有找到匹配记录",Modifier.padding(20.dp),color=Muted)}
            }
        }
    }
}
private data class LibraryPreviousRanks(val songs:Map<String,Int>,val groups:Map<String,Int>)
@Composable private fun LibrarySong(c:DiaryController,p:Play,key:String,rank:Int,open:(Play,String)->Unit,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,rankChange:RankChange?=null){
    Row(Modifier.fillMaxWidth().testTag("library-song:$key").clip(RoundedCornerShape(15.dp)).press{open(p,"library:$key")}.padding(horizontal=10.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.width(38.dp)){Text("$rank".padStart(2,'0'),fontSize=14.sp,color=Muted);RankChangeLabel(rankChange)}
        with(shared){Artwork(c,p.id,Modifier.size(43.dp).sharedElement(rememberSharedContentState("library:$key"),visibility,boundsTransform={_,_->spring(.9f,240f)}).clip(RoundedCornerShape(9.dp)),edge=160)}
        Column(Modifier.weight(1f).padding(horizontal=11.dp)){Text(p.title,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(p.artist,fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
        Text("${p.effective} 次",fontSize=14.sp,color=Muted)
    }
}

/** The same compact album row is used in the full ranking and its parent preview. */
@Composable private fun LibraryGroupHeading(c:DiaryController,g:LibraryGroup,rank:Int,source:String,artists:Boolean,turn:Float,modifier:Modifier=Modifier,rankChange:RankChange?=null,click:()->Unit){
    Row(modifier.fillMaxWidth().press(click).padding(14.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.width(38.dp)){Text("$rank".padStart(2,'0'),fontSize=14.sp,color=Muted);RankChangeLabel(rankChange)}
        Artwork(c,g.image,Modifier.size(47.dp).detailSource(source).clip(if(artists)CircleShape else RoundedCornerShape(10.dp)),edge=160)
        Column(Modifier.weight(1f).padding(horizontal=12.dp)){
            Text(g.title,fontSize=17.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${g.songs.size}首 · ${g.plays}次 · ${minutes(g.ms)}分钟",Modifier.padding(top=3.dp),fontSize=11.sp,color=Muted,maxLines=1)
            if(g.subtitle.isNotBlank())Text(g.subtitle,fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        DiaryGlyph("down",Modifier.size(16.dp).graphicsLayer{rotationZ=turn},color=Muted)
    }
}
@Composable internal fun FavoriteAlbumList(c:DiaryController,groups:List<LibraryGroup>,changes:Map<String,RankChange> = emptyMap(),rankOffset:Int=0,onOpen:()->Unit={}){
    val detail=LocalDetailActions.current
    Column(Modifier.fillMaxWidth().padding(top=13.dp).testTag("favorite-album-list"),verticalArrangement=Arrangement.spacedBy(9.dp)){
        if(groups.isEmpty())Text("记录中还没有专辑信息",fontSize=13.sp,color=Muted)
        groups.forEachIndexed{i,g->key(g.key){
            val source="home-ranking:${g.key}"
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp)).background(Accent.copy(alpha=.045f))){
                LibraryGroupHeading(c,g,i+1+rankOffset,source,false,0f,Modifier.testTag("favorite-album:${g.key.removePrefix("album:")}"),changes[g.key]){
                    g.songs.firstOrNull()?.let{onOpen();detail?.album?.invoke(it,source)}
                }
            }
        }}
    }
}
