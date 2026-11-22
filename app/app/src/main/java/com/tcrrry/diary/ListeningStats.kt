package com.tcrrry.diary
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

internal fun multiSongArtists(plays:List<Play>)=plays.filter{!it.imported||it.artist.isNotBlank()&&it.artist!="未知歌手"}.groupBy{it.artist}.count{(_,songs)->songs.map{it.id}.distinct().size>1}

@Composable internal fun ListeningStats(r:DiaryReport,modifier:Modifier=Modifier,onInfo:(String)->Unit={}){
    var changes by rememberSaveable{mutableStateOf(false)};var hours by rememberSaveable{mutableStateOf(false)}
    var albums by rememberSaveable{mutableStateOf(false)};var multiple by rememberSaveable{mutableStateOf(false)}
    val changeCount=r.manualSkips?.toString()?:"—"
    val changeLabel=if(r.manualSkips!=null)"次切歌·记录"else "切歌未采集"
    val albumCount=if(albums)remember(r.history){reportAlbums(r).size}else 0
    val artistCount=if(multiple)remember(r.history){multiSongArtists(r.history)}else 0
    val numbers=listOf(if(changes)changeCount else "${r.plays}",if(hours)reportHours(r.ms) else minutes(r.ms),if(albums)"$albumCount" else "${r.tracks}",if(multiple)"$artistCount" else "${r.artists}")
    Row(modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        SwitchStat(Modifier.weight(1f),numbers.maxOf{it.length},"stat-plays",changes,{active->if(active)changeCount to changeLabel else "${r.plays}" to "次播放"}){changes=!changes}
        SwitchStat(Modifier.weight(1f),numbers.maxOf{it.length},"stat-duration",hours,{active->if(active)reportHours(r.ms) to "小时" else minutes(r.ms) to "分钟"}){hours=!hours}
        SwitchStat(Modifier.weight(1f),numbers.maxOf{it.length},"stat-tracks",albums,{active->if(active)"$albumCount" to "张专辑" else "${r.tracks}" to "首歌曲"}){albums=!albums}
        SwitchStat(Modifier.weight(1f),numbers.maxOf{it.length},"stat-artists",multiple,{active->if(active)"$artistCount" to "位歌手·多首" else "${r.artists}" to "位歌手"}){multiple=!multiple}
    }
}
@Composable private fun SwitchStat(modifier:Modifier,maxDigits:Int,tag:String,mode:Boolean,display:(Boolean)->Pair<String,String>,toggle:()->Unit){
    val currentDisplay by rememberUpdatedState(display)
    Box(modifier.heightIn(min=72.dp).testTag(tag).press(toggle).semantics{contentDescription=when(tag){"stat-plays"->"播放次数与切歌记录切换";"stat-duration"->"分钟与小时切换";"stat-tracks"->"歌曲与专辑切换";else->"全部歌手与听过不止一首歌曲的歌手切换"}},contentAlignment=Alignment.Center){
        AnimatedContent(mode,modifier=Modifier.fillMaxWidth().height(72.dp),contentAlignment=Alignment.Center,transitionSpec={fadeIn(tween(180))+slideInVertically{it/4} togetherWith fadeOut(tween(130))+slideOutVertically{-it/4} using SizeTransform(clip=false){_,_->snap()}},label="stat switch"){active->
            val (number,caption)=currentDisplay(active)
            Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){BoxWithConstraints(Modifier.fillMaxWidth().height(38.dp),contentAlignment=Alignment.BottomCenter){val size=minOf(28f,(maxWidth.value*1.35f/maxDigits.coerceAtLeast(1))).coerceAtLeast(12f);Text(number,fontSize=size.sp,fontWeight=FontWeight.SemiBold,maxLines=1)};Text(caption,fontSize=if(caption.length>5)11.sp else 13.sp,color=Muted,textAlign=TextAlign.Center,maxLines=1)}
        }
    }
}
