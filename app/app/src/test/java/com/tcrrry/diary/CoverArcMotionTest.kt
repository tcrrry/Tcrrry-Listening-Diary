package com.tcrrry.diary
import org.junit.Test
import org.junit.Assert.*
class CoverArcMotionTest {
    @Test fun arcCardsKeepReducedOverlapAndRequestedDragGain(){val visible=164f*.78f;val step=164f+CoverArcSpacingDp;assertTrue((visible-step)/visible<.4f);assertEquals(4f,CoverArcDragGain,0f)}
    @Test fun frameMovesThroughIntermediatePositions(){val next=coverArcStep(0f,18f,1f/60,true);assertTrue(next>0f);assertTrue(next<18f);assertNotEquals(next,next.roundForTest(),.0001f)}
    @Test fun retargetStartsAtCurrentPositionAndDoesNotJump(){val position=coverArcStep(2f,18f,.016f,true);val reverse=coverArcStep(position,0f,.016f,true);assertTrue(reverse<position);assertTrue(reverse>0f)}
    @Test fun pauseDoesNotTurnNextFrameIntoTeleportAndReleaseConverges(){assertTrue(coverArcStep(0f,30f,5f,false)<30f);var current=0f;repeat(150){current=coverArcStep(current,14f,1f/60,false)};assertEquals(14f,current,.001f)}
}
private fun Float.roundForTest()=kotlin.math.round(this)
