package com.tcrrry.diary

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

internal fun coverStopTime(stop:CoverStop,twelve:Boolean=false):String {
    fun clock(ms:Long)=diaryClock(ms,twelve)
    return if(stop.count>1)"${clock(stop.start)}—${clock(stop.end)} · 连听${stop.count}次" else clock(stop.play.start)
}

/** Decorative layers share the front cover's decoded bitmap, and never start additional image requests. */
@Composable internal fun FoldedCoverLayers(bitmap:android.graphics.Bitmap?,count:Int,focus:Float){
    val depth by animateFloatAsState((count-1).coerceIn(0,2).toFloat(),spring(.86f,280f),label="repeat fold depth")
    val background=MaterialTheme.colorScheme.surfaceVariant
    val arrival by animateFloatAsState(if(bitmap==null)0f else 1f,tween(220),label="stack artwork arrival")
    for(layer in 2 downTo 1){
        val alpha=(depth-layer+1).coerceIn(0f,1f)
        Canvas(Modifier.size(164.dp).graphicsLayer{
            this.alpha=alpha;scaleX=1f-layer*.05f;scaleY=scaleX
            translationY=(8-layer*4).dp.toPx()-size.height*(1f-scaleX)/2
        }.shadow(5.dp,RoundedCornerShape(17.dp)).clip(RoundedCornerShape(17.dp)).background(background)){
            bitmap?.let{image->val factor=min(size.width/image.width,size.height/image.height);val w=(image.width*factor).roundToInt();val h=(image.height*factor).roundToInt()
                drawImage(image.asImageBitmap(),dstOffset=IntOffset(((size.width-w)/2).roundToInt(),((size.height-h)/2).roundToInt()),dstSize=IntSize(w,h),alpha=arrival)
            }
            drawRect(Color.Black.copy(alpha=((1f-focus)*.22f+.025f*layer).coerceIn(0f,1f)))
        }
    }
}

@Composable internal fun FoldedCoverBadge(count:Int,modifier:Modifier=Modifier){
    AnimatedVisibility(count>1,enter=fadeIn(tween(180))+scaleIn(initialScale=.88f),exit=fadeOut(tween(120)),modifier=modifier){
        Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha=.90f)).border(.5.dp,Muted.copy(alpha=.10f),CircleShape).padding(horizontal=8.dp,vertical=4.dp)){
            AnimatedContent(count,transitionSpec={fadeIn(tween(160))+slideInVertically{it/3} togetherWith fadeOut(tween(100))},label="repeat count"){n->Text("${n}次",fontSize=11.sp,fontWeight=FontWeight.Bold,color=Accent)}
        }
    }
}

@Composable internal fun FoldedCoverContents(c:DiaryController,stop:CoverStop,open:(Play)->Unit){
    var unfolded by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){unfolded=true}
    val spread by animateFloatAsState(if(unfolded)1f else 0f,spring(.88f,260f),label="repeat cover unfolding")
    LazyColumn(Modifier.fillMaxWidth().heightIn(max=650.dp).testTag("cover-repeat-contents"),contentPadding=PaddingValues(start=22.dp,end=22.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item("title"){
            Text(stop.play.title,fontSize=25.sp,fontWeight=FontWeight.Bold)
            Text(stop.play.artist,Modifier.padding(top=4.dp),fontSize=15.sp,color=Muted)
            Text(coverStopTime(stop,c.use12Hour),Modifier.padding(top=10.dp),fontSize=14.sp,color=Accent)
        }
        item("fan"){
            Box(Modifier.fillMaxWidth().height(184.dp),contentAlignment=Alignment.Center){
                for(index in (minOf(3,stop.count)-1) downTo 0){
                    val direction=when(index){1->-1f;2->1f;else->0f}
                    Artwork(c,stop.play.id,Modifier.size(148.dp).zIndex(if(index==0)3f else 1f).graphicsLayer{
                        translationX=direction*42.dp.toPx()*spread;rotationZ=direction*7f*spread
                        scaleX=if(index==0)1f else .91f;scaleY=scaleX
                    }.shadow(9.dp,RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)))
                }
            }
        }
        item("duration"){Text("实际聆听 ${shortDuration(stop.ms)}",fontSize=13.sp,color=Muted)}
        items(stop.songs,key={it.visit}){play->
            Row(Modifier.fillMaxWidth().testTag("cover-repeat-visit:${play.visit}").press{open(play)}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                val clock=diaryClock(play.start,c.use12Hour)
                Text(clock,Modifier.width(if(c.use12Hour)75.dp else 53.dp),fontSize=14.sp,color=Muted)
                Artwork(c,play.id,Modifier.size(43.dp).clip(RoundedCornerShape(9.dp)),edge=160)
                Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(play.title,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(shortDuration(play.ms),fontSize=12.sp,color=Muted)}
                DiaryGlyph("next",Modifier.size(15.dp),color=Muted)
            }
            HorizontalDivider(color=Muted.copy(alpha=.10f),thickness=.5.dp)
        }
    }
}
