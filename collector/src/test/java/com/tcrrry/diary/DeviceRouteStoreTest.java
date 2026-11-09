package com.tcrrry.diary;
import java.io.File;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.json.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class DeviceRouteStoreTest {
 @Test public void changingRouteSplitsSegmentsWithoutChangingVisitOrDuration() throws Exception {
  File dir=new File(RuntimeEnvironment.getApplication().getCacheDir(),"device-"+System.nanoTime());
  try(DiaryStore store=new DiaryStore(dir)){
   ListeningClock clock=new ListeningClock(store::save);
   clock.accept(sample(1000000,1000,0,"Headphones"),"start");
   clock.accept(sample(1010000,11000,10000,"Speaker"),"route");
   clock.accept(sample(1020000,21000,20000,"Speaker"),"tick");
   JSONArray rows=store.syncBatch(0,32).getJSONArray("spans");
   assertEquals(2,rows.length());assertEquals("Headphones",rows.getJSONObject(0).getString("device"));
   assertEquals("Speaker",rows.getJSONObject(1).getString("device"));
   assertEquals(rows.getJSONObject(0).getString("visit"),rows.getJSONObject(1).getString("visit"));
   assertEquals(20000,rows.getJSONObject(0).getLong("ms")+rows.getJSONObject(1).getLong("ms"));
  }
 }
 @Test public void volumeChangesSplitSnapshotsAndSurviveSync() throws Exception {
  File dir=new File(RuntimeEnvironment.getApplication().getCacheDir(),"volume-"+System.nanoTime());
  try(DiaryStore store=new DiaryStore(dir)){
   ListeningClock clock=new ListeningClock(store::save);
   clock.accept(new ListeningClock.Sample("id","song","artist","album",1000000,1000,0,240000,true,1,"Speaker","audio_track",25),"start");
   clock.accept(new ListeningClock.Sample("id","song","artist","album",1010000,11000,10000,240000,true,1,"Speaker","audio_track",50),"tick");
   clock.accept(new ListeningClock.Sample("id","song","artist","album",1020000,21000,20000,240000,true,1,"Speaker","audio_track",50),"tick");
   JSONArray rows=store.syncBatch(0,32).getJSONArray("spans");assertEquals(2,rows.length());assertEquals(25,rows.getJSONObject(0).getInt("volume_pct"));assertEquals(50,rows.getJSONObject(1).getInt("volume_pct"));
  }
 }
 private ListeningClock.Sample sample(long wall,long elapsed,long pos,String device){return new ListeningClock.Sample("id","song","artist","album",wall,elapsed,pos,240000,true,1,device,"system_media");}
}
