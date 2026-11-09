package com.tcrrry.diary
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import kotlin.math.*

internal fun arcHistoryIndex(fraction:Float,count:Int)=if(count<=1)0 else arcHistoryPosition(fraction,count).roundToInt().coerceIn(0,count-1)
@Composable internal fun CoverArcScrubber(count:Int,index:Float,choose:(Float)->Unit,finished:()->Unit){
    val latestChoose by rememberUpdatedState(choose);val latestFinished by rememberUpdatedState(finished);val accent=Accent;val muted=Muted
    Canvas(Modifier.fillMaxWidth().height(44.dp).testTag("cover-arc-scrubber").semantics{contentDescription="快速定位播放记录";progressBarRangeInfo=ProgressBarRangeInfo(index,0f..(count-1).coerceAtLeast(0).toFloat());setProgress{choose(it.coerceIn(0f,(count-1).coerceAtLeast(0).toFloat()));finished();true}}.pointerInput(count){awaitEachGesture{
        val down=awaitFirstDown(requireUnconsumed=false)
        fun selectAt(x:Float){latestChoose(arcHistoryPosition(x/size.width.coerceAtLeast(1),count))}
        selectAt(down.position.x);down.consume()
        try{do{val event=awaitPointerEvent();val change=event.changes.firstOrNull{it.id==down.id}?:break;if(!change.pressed)break;selectAt(change.position.x);change.consume()}while(true)}finally{latestFinished()}
    }}){
        fun point(f:Float):Offset{val x=f*size.width;val normalized=f*2f-1f;return Offset(x,9.dp.toPx()+normalized*normalized*19.dp.toPx())}
        val path=Path();for(step in 0..80){val p=point(step/80f);if(step==0)path.moveTo(p.x,p.y)else path.lineTo(p.x,p.y)}
        drawPath(path,muted.copy(alpha=.22f),style=Stroke(4.5.dp.toPx(),cap=StrokeCap.Round))
        val fraction=if(count>1)index/(count-1)else 0f;val active=Path();for(step in 0..60){val p=point(fraction*step/60f);if(step==0)active.moveTo(p.x,p.y)else active.lineTo(p.x,p.y)}
        drawPath(active,accent.copy(alpha=.75f),style=Stroke(4.5.dp.toPx(),cap=StrokeCap.Round));drawCircle(accent.copy(alpha=.12f),11.dp.toPx(),point(fraction));drawCircle(accent,7.5.dp.toPx(),point(fraction))
    }
}
