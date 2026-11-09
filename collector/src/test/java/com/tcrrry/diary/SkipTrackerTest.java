package com.tcrrry.diary;import org.junit.Test;import static org.junit.Assert.*;
public class SkipTrackerTest {
 private SkipTracker.Command c(String kind){return new SkipTracker.Command(kind,"A",1,100000,1000,60000,240000);}
 @Test public void automaticAdvanceNeverCountsAndConfirmedCommandCountsOnlyOnce(){SkipTracker s=new SkipTracker();assertNull(s.observe("B",2,0,2000));s.arm(c("next"));assertNotNull(s.observe("B",2,0,2000));assertNull(s.observe("B",2,200,2200));}
 @Test public void sameSongDifferentQueueOccurrenceCountsButRestartDoesNot(){SkipTracker s=new SkipTracker();s.arm(c("queue"));assertNotNull(s.observe("A",2,0,2000));s.arm(c("previous"));assertNull(s.observe("A",1,0,2000));assertNull(s.observe("B",2,0,2200));}
 @Test public void staleOutOfOrderAndSupersededCommandsAreConservative(){SkipTracker s=new SkipTracker();s.arm(c("next"));assertNull(s.observe("A",1,60000,900));assertNotNull(s.observe("B",2,0,2000));s.arm(c("next"));assertNull(s.observe("B",2,0,10000));s.arm(c("next"));s.arm(c("next"));assertNotNull(s.observe("C",3,0,2000));assertNull(s.observe("C",3,0,2100));}
}
