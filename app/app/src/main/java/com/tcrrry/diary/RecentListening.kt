@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
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
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlin.math.*
import androidx.compose.ui.platform.testTag

internal const val RecentPlayingLifetime=45000L
internal val RecentPositionKey=SemanticsPropertyKey<Int>("RecentStripOffset")
internal fun recentPlayingKey(report:DiaryReport,stops:List<CoverStop>,now:Long=System.currentTimeMillis()):String? {
    if(report.nowTrackId.isBlank()||report.recordState!="正在记录"||report.lastSync<=0||now-report.lastSync !in 0 until RecentPlayingLifetime)return null
    return stops.firstOrNull{it.play.id==report.nowTrackId&&report.lastSync-it.end in 0..RecentPlayingLifetime}?.key
}

@Composable internal fun rememberRecentPlayingKey(report:DiaryReport,stops:List<CoverStop>,foreground:Boolean):String? {
    var expired by remember(report.lastSync,report.nowTrackId,report.recordState){mutableStateOf(System.currentTimeMillis()-report.lastSync !in 0 until RecentPlayingLifetime)}
    LaunchedEffect(report.lastSync,report.nowTrackId,report.recordState,foreground){
        if(foreground&&!expired){delay((report.lastSync+RecentPlayingLifetime-System.currentTimeMillis()).coerceAtLeast(0));expired=true}
    }
    return if(expired)null else recentPlayingKey(report,stops)
}

@Composable internal fun RecentListeningHeader(c:DiaryController,r:DiaryReport,history:()->Unit,open:(Play,String)->Unit,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope){
    val stops=remember(r.history,r.segments){CoverCarousel.stops(r.history,r.segments)}
    val list=rememberLazyListState()
    var followLatest by rememberSaveable{mutableStateOf(true)}
    LaunchedEffect(list){snapshotFlow{list.isScrollInProgress}.collect{moving->if(!moving)followLatest=list.firstVisibleItemIndex==0&&list.firstVisibleItemScrollOffset==0}}
    LaunchedEffect(stops.firstOrNull()?.key){if(stops.isNotEmpty()&&followLatest&&!list.isScrollInProgress)list.animateScrollToItem(0)}
    val playing=rememberRecentPlayingKey(r,stops,c.foreground)
    Row(Modifier.fillMaxWidth().height(if(stops.isEmpty())32.dp else 50.dp),verticalAlignment=Alignment.CenterVertically){
        Text("听歌历史",Modifier.testTag("day-history-title").press(history),fontSize=18.sp,fontWeight=FontWeight.Bold,maxLines=1)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}.clipToBounds().drawWithContent{
            drawContent();val left=if(list.canScrollBackward)1f else 0f;val right=if(list.canScrollForward)1f else 0f;val edge=12.dp.toPx().coerceAtMost(size.width/3)
            drawRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha=1-left),Color.White),endX=edge),blendMode=BlendMode.DstIn)
            drawRect(Brush.horizontalGradient(listOf(Color.White,Color.White.copy(alpha=1-right)),startX=size.width-edge,endX=size.width),blendMode=BlendMode.DstIn)
        }){
            LazyRow(state=list,modifier=Modifier.fillMaxSize().testTag("recent-listening-strip").semantics{this[RecentPositionKey]=list.firstVisibleItemIndex*10000+list.firstVisibleItemScrollOffset},contentPadding=PaddingValues(horizontal=3.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
                items(stops,key={it.key}){entry->
                    val source="recent:${entry.key}"
                    val opacity by animateFloatAsState(playbackOpacity(entry.songs,entry.key==playing),tween(280),label="recent playback opacity")
                    val description="${entry.play.title}，${coverStopTime(entry,c.use12Hour)}"
                    Box(Modifier.animateItem(fadeInSpec=tween(200),placementSpec=spring(.9f,300f),fadeOutSpec=tween(150)).size(46.dp).testTag("recent-cover:${entry.key}").semantics{contentDescription=description;this[PlaybackOpacityKey]=opacity}.press{open(entry.play,source)}){
                        with(shared){Artwork(c,entry.play.id,Modifier.fillMaxSize().historyCover(entry.first.visit).sharedElement(rememberSharedContentState(source),visibility,renderInOverlayDuringTransition=ownedSharedOverlay(shared,visibility),clipInOverlayDuringTransition=OverlayClip(RoundedCornerShape(17.dp)),boundsTransform={_,_->tween(360,easing=FastOutSlowInEasing)}).clip(RoundedCornerShape(10.dp)).playbackFade(opacity),edge=160)}
                        PlayingCoverBadge(entry.key==playing,c.foreground,Modifier.align(Alignment.BottomEnd).padding(2.dp))
                    }
                }
            }
        }
        Box(Modifier.testTag("day-history-link").size(28.dp).clip(CircleShape).press(history).semantics{contentDescription="听歌历史"},contentAlignment=Alignment.Center){DiaryGlyph("next",Modifier.size(17.dp),color=Muted)}
    }
}

@Composable private fun PlayingCoverBadge(playing:Boolean,animate:Boolean,modifier:Modifier){
    AnimatedVisibility(playing,modifier=modifier,enter=fadeIn(tween(180))+scaleIn(initialScale=.7f),exit=fadeOut(tween(120))+scaleOut(targetScale=.8f)){
        val phase=if(animate){val transition=rememberInfiniteTransition(label="playing cover");transition.animateFloat(0f,1f,infiniteRepeatable(tween(1100,easing=LinearEasing)),label="playing bars").value}else 0f
        val ink=Accent
        Canvas(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha=.24f)).semantics{contentDescription="正在播放"}){
            val gap=size.width*.22f;val width=size.width*.12f
            for(i in 0..2){val height=size.height*(.25f+.35f*(.5f+.5f*sin(phase*2*PI.toFloat()+i*2.1f)))
                drawRoundRect(ink,Offset(size.width*.22f+i*gap,size.height*.78f-height),Size(width,height),CornerRadius(width/2))
            }
        }
    }
}
