package com.tcrrry.diary

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Child horizontal controls and system navigation own their gestures first. */
internal fun Modifier.periodSwipe(c:DiaryController):Modifier = composed {
    val animationScope=rememberCoroutineScope()
    pointerInput(c,c.range,c.summary) {
    val edge=24.dp.toPx()
    awaitEachGesture {
        val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Main)
        c.cancelPeriodSettlement()
        val current=if(c.summary)4 else c.range.ordinal
        val gestureWidth=size.width.coerceAtLeast(1).toFloat()
        val tracker=VelocityTracker().apply{addPosition(down.uptimeMillis,down.position)}
        var dx=0f;var dy=0f;var owned=false;var blocked=down.position.x<edge||down.position.x>size.width-edge
        var released=false;var lastTime=down.uptimeMillis;var layoutTarget=-1
        var handedOff=false
        try {
            do {
                val event=awaitPointerEvent(PointerEventPass.Main)
                if(event.motionEvent?.actionMasked==android.view.MotionEvent.ACTION_CANCEL){blocked=true;break}
                val change=event.changes.firstOrNull{it.id==down.id}?:break
                if(event.changes.count{it.pressed}>1){blocked=true;break}
                lastTime=change.uptimeMillis
                if(!change.pressed&&change.isConsumed){blocked=true;break}
                tracker.addPosition(change.uptimeMillis,change.position)
                dx=change.position.x-down.position.x;dy=change.position.y-down.position.y
                if(change.isConsumed&&!owned){blocked=true;break}
                if(!owned&&!blocked&&maxOf(abs(dx),abs(dy))>viewConfiguration.touchSlop){
                    if(abs(dx)>abs(dy)*1.15f){
                        val target=(current+if(dx<0)1 else -1).coerceIn(0,4)
                        if(target!=current){owned=true;if(current==4||target==4)c.chooseSummaryMotion()else c.choosePeriodMotion()}else blocked=true
                    }else {blocked=true;break}
                }
                if(owned){
                    val target=(current+if(dx<0)1 else -1).coerceIn(0,4)
                    if(target<4&&target!=layoutTarget){c.choosePeriodLayout(DiaryRange.entries[target]);layoutTarget=target}
                    c.periodSwipePosition=current+(target-current)*(abs(dx)/gestureWidth).coerceIn(0f,1f)
                    change.consume()
                }
                if(!change.pressed){released=true;break}
            }while(true)
            val velocity=tracker.calculateVelocity().x
            val sameDirection=velocity*dx>0f
            val elapsed=(lastTime-down.uptimeMillis).coerceAtLeast(1)
            val quick=elapsed<250&&abs(dx)*1000f/elapsed>700.dp.toPx()
            val commit=abs(dx)>gestureWidth*.22f || (abs(dx)>viewConfiguration.touchSlop*2&&((sameDirection&&abs(velocity)>700.dp.toPx())||quick))
            if(owned&&released&&!blocked){
                val target=(current+if(dx<0)1 else -1).coerceIn(0,4)
                handedOff=true
                c.settlePeriodSwipe(animationScope,current,target,commit,-velocity/gestureWidth)
            }
        }finally{if(!handedOff)c.periodSwipePosition=null}
    }
    }
}
