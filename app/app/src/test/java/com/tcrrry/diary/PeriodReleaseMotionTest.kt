package com.tcrrry.diary
import org.junit.Test
import org.junit.Assert.*
class PeriodReleaseMotionTest{
 @Test fun landingPreservesOrdinaryReleaseVelocityAndDoesNotOvershoot(){
  for(distance in listOf(.13f,.6f,-.4f))for(speed in listOf(.2f,1f,3f)){
   val v=speed*if(distance>0)1 else -1
   val m=PeriodReleaseMotion(distance,v)
   var last=0f
   for(i in 0..100){val p=m.easing.transform(i/100f);assertTrue(p>=last-.0001f&&p<=1f);last=p}
   assertEquals(1f,last,.0001f)
   if(m.slope<2.99f){val actual=m.easing.transform(.0001f)/.0001f*distance/(m.durationMillis/1000f);assertEquals(v,actual,.01f)}
  }
 }
 @Test fun fasterReleaseLandsSooner(){assertTrue(PeriodReleaseMotion(.6f,3f).durationMillis<PeriodReleaseMotion(.6f,.3f).durationMillis)}
}
