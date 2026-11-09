/*
 * Material composition adapted from AndroidLiquidGlass / Backdrop 2.0.1
 * (Kyant, Apache-2.0) and the local AM++ LiquidBottomTabs derivative.
 * Diary adaptation: four text periods, horizontal slop ownership, cancel-safe selection,
 * independent refraction text source, velocity stretch and bounded press illumination.
 * Full license: assets/licenses/Backdrop-Apache-2.0.txt.
 */
package com.tcrrry.diary

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.*

internal val GlassPositionKey=SemanticsPropertyKey<Float>("GlassPosition")

@Composable internal fun PeriodGlassPicker(selected:Int,select:(Int)->Unit,source:Backdrop,count:Int=4,externalPosition:Float?=null,label:@Composable (Int,Boolean)->Unit){
    val current by rememberUpdatedState(selected);val commit by rememberUpdatedState(select)
    val haptic=LocalHapticFeedback.current
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val hardware=!LocalInspectionMode.current&&LocalView.current.isHardwareAccelerated
    val paper=if(dark)Color(0xff121212)else Color(0xfffafafa)
    val shape=RoundedCornerShape(50)
    val textSource=rememberLayerBackdrop();val combined=rememberCombinedBackdrop(source,textSource)
    var pressed by remember{mutableStateOf(false)};var dragging by remember{mutableStateOf(false)};var freeIndex by remember{mutableFloatStateOf(selected.toFloat())}
    val progress by animateFloatAsState(if(pressed)1f else 0f,spring(.72f,380f),label="liquid press")
    val target=if(dragging)freeIndex else externalPosition?:selected.toFloat()
    val position by animateFloatAsState(target,if(dragging||externalPosition!=null)snap()else spring(.74f,380f),label="liquid position")
    val travel=(target-position).coerceIn(-1f,1f)
    val energy=max(progress,abs(travel).coerceAtMost(1f))
    BoxWithConstraints(Modifier.fillMaxWidth().height(43.dp).testTag("period-glass").semantics{isTraversalGroup=true;this[GlassPositionKey]=position}.pointerInput(count){
        awaitEachGesture{
            val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
            var owner=down.id;val origin=down.position;var horizontal=false;var vertical=false;var cancelled=false
            pressed=true
            try{
                do{
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    if(event.motionEvent?.actionMasked==android.view.MotionEvent.ACTION_CANCEL){cancelled=true;break}
                    val change=event.changes.firstOrNull{it.id==owner}
                    if(change==null){cancelled=true;break}
                    // Compose synthesizes consumed releases without a MotionEvent for ACTION_CANCEL.
                    if(!change.pressed&&change.isConsumed){cancelled=true;break}
                    if(!change.pressed){
                        val replacement=event.changes.firstOrNull{it.pressed}
                        if(replacement!=null&&horizontal){owner=replacement.id;continue}
                        if(horizontal){change.consume();commit(freeIndex.roundToInt().coerceIn(0,count-1));haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)}
                        break
                    }
                    if(change.isConsumed&&!horizontal){cancelled=true;break}
                    val delta=change.position-origin
                    if(!horizontal&&!vertical&&max(abs(delta.x),abs(delta.y))>viewConfiguration.touchSlop){
                        if(abs(delta.x)>abs(delta.y))horizontal=true else {vertical=true;pressed=false;break}
                    }
                    if(horizontal){dragging=true;freeIndex=((change.position.x-4.dp.toPx())/((size.width-8.dp.toPx())/count.toFloat())-.5f).coerceIn(0f,(count-1).toFloat());change.consume()}
                }while(true)
            }finally{pressed=false;dragging=false;if(cancelled)freeIndex=current.toFloat()}
        }
    }){
        val cell=(maxWidth-8.dp)/count
        val track=if(hardware)Modifier.drawBackdrop(backdrop=source,shape={shape},effects={vibrancy();blur(4.dp.toPx());lens(24.dp.toPx(),24.dp.toPx())},layerBlock={val enlargement=1f+8.dp.toPx()/size.width.coerceAtLeast(1f)*progress;scaleX=enlargement;scaleY=enlargement},onDrawSurface={drawRect(paper.copy(alpha=.40f))})else Modifier.background(paper.copy(alpha=.4f),shape)
        Box(Modifier.fillMaxSize().then(track).drawWithContent{
            drawContent()
            if(energy>.001f)drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha=.22f*energy),Color.Transparent),center=Offset(4.dp.toPx()+(position+.5f)*(size.width-8.dp.toPx())/count.toFloat(),size.height/2),radius=size.height*1.5f))
        }){
            Row(Modifier.fillMaxSize().padding(4.dp)){
                repeat(count){index->Box(Modifier.weight(1f).fillMaxHeight().testTag("period-tab:$index").semantics{role=Role.Tab;this.selected=current==index}.clickable(interactionSource=null,indication=null){commit(index);haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)},contentAlignment=Alignment.Center){label(index,current==index)}}
            }
        }
        run {
            // Captured but invisible duplicate labels form the refracted foreground, never a recursive self-capture.
            Row(Modifier.padding(4.dp).fillMaxWidth().height(35.dp).alpha(0f).clearAndSetSemantics{testTag="period-refraction-source"}.then(if(hardware)Modifier.layerBackdrop(textSource)else Modifier)){
                repeat(count){index->Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer{scaleX=1f+energy*.20f;scaleY=scaleX},contentAlignment=Alignment.Center){label(index,true)}}
            }
        }
        val material=if(hardware)Modifier.drawBackdrop(
            backdrop=combined,shape={shape},effects={lens(10.dp.toPx()*energy,14.dp.toPx()*energy,chromaticAberration=true)},
            highlight={Highlight.Default.copy(alpha=.35f+.65f*energy)},
            shadow={Shadow(alpha=.18f+.6f*energy)},
            innerShadow={InnerShadow(radius=8.dp*(.3f+.7f*energy),alpha=.25f+.6f*energy)},
            layerBlock={scaleX=1f+.50f*progress+abs(travel)*.14f;scaleY=1f+.45f*progress-abs(travel)*.05f},
            onDrawSurface={drawRect((if(dark)Color.White else Color.Black).copy(alpha=.07f*(1f-energy)));drawRect(Color.White.copy(alpha=.025f*energy))}
        )else Modifier.background(paper.copy(alpha=.38f),shape).border(.7.dp,Color.White.copy(alpha=.65f),shape).shadow(4.dp,shape)
        Box(Modifier.offset{IntOffset((4.dp+cell*position).roundToPx(),4.dp.roundToPx())}.width(cell).height(35.dp).then(material).clearAndSetSemantics{testTag="period-thumb"})
    }
}
