package com.tcrrry.diary
import kotlin.math.*
import androidx.compose.ui.semantics.SemanticsPropertyKey

internal const val CoverArcDragGain=4f
internal const val CoverArcSpacingDp=-78f

internal val CoverPositionKey=SemanticsPropertyKey<Float>("CoverPosition")

/** One fractional position follows the latest finger target; no integer page jumps or restarted tweens. */
internal fun coverArcStep(current:Float,target:Float,seconds:Float,dragging:Boolean):Float{
    val dt=seconds.coerceIn(0f,.04f)
    val weight=1f-exp(-dt/(if(dragging).065f else .12f))
    return current+(target-current)*weight
}
internal fun arcHistoryPosition(fraction:Float,count:Int)=if(count<=1)0f else fraction.coerceIn(0f,1f)*(count-1)
