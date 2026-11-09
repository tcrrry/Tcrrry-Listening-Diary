package com.tcrrry.diary;
import android.os.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import java.time.Duration;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class SyncCheckpointTest {
    private final Handler main=new Handler(Looper.getMainLooper());private int calls;
    private void advance(long ms){((org.robolectric.shadows.ShadowLooper)org.robolectric.shadow.api.Shadow.extract(Looper.getMainLooper())).idleFor(Duration.ofMillis(ms));}
    @Test public void blockedNativeThreadStillFlushesSavedRecords(){Runnable sample=SyncCheckpoint.bounded(main,1000,()->calls++);advance(999);assertEquals(0,calls);advance(1);assertEquals(1,calls);sample.run();assertEquals(1,calls);}
    @Test public void normalNativeSampleCancelsFallbackAndFlushesOnlyOnce(){Runnable sample=SyncCheckpoint.bounded(main,1000,()->calls++);sample.run();assertEquals(1,calls);advance(5000);sample.run();assertEquals(1,calls);}
}
