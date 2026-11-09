package com.tcrrry.diary

import androidx.compose.animation.core.Easing
import kotlin.math.abs

/** Monotone Hermite landing: retain release velocity and reach the endpoint at rest. */
internal class PeriodReleaseMotion(distance:Float,velocity:Float){
    private val d=abs(distance)
    private val speed=(velocity*if(distance>=0)1f else -1f).coerceAtLeast(0f)
    val durationMillis=if(speed<.05f)420 else (1500f*d/speed).toInt().coerceIn(100,520)
    internal val slope=if(d<.00001f)0f else (speed*durationMillis/1000f/d).coerceIn(0f,3f)
    val easing=Easing{t->(slope*t+(3f-2f*slope)*t*t+(slope-2f)*t*t*t).coerceIn(0f,1f)}
}
