package com.tcrrry.diary;
import java.lang.reflect.*;import java.util.*;import android.os.*;import dev.amenhancer.plugin.api.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import static org.junit.Assert.*;import com.apple.android.music.player.MediaPlaybackService;import com.apple.android.music.playback.controller.LocalMediaPlayerController;import com.apple.android.music.playback.model.PlayerQueueItem;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class NativeProbeTest {
 private String failRegistration;private int closedRegistrations;
 private NativeProbe probe;private final Map<String,PluginObserver> hooks=new HashMap<>();private final List<SkipTracker.Event> events=new ArrayList<>();private final List<String> samples=new ArrayList<>();private MediaPlaybackService service;private LocalMediaPlayerController player;
 private void idle(){org.robolectric.shadows.ShadowLooper.idleMainLooper();}
 private void init(long version)throws Exception{init(version,getClass().getClassLoader());}
 private void init(long version,ClassLoader loader)throws Exception{RuntimeEnvironment.getApplication().getSharedPreferences("com.tcrrry.listeningdiary",0).edit().clear().commit();PluginHooks api=(PluginHooks)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PluginHooks.class},(p,m,a)->{assertEquals("observe",m.getName());String name=((Method)a[0]).getName();if(name.equals(failRegistration))throw new IllegalStateException("registration rejected");hooks.put(name,(PluginObserver)a[1]);return (PluginRegistration)()->{closedRegistrations++;hooks.remove(name);};});PluginContext c=(PluginContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PluginContext.class},(p,m,a)->{switch(m.getName()){case "getHostVersionCode":return version;case "getHostClassLoader":return loader;case "getApplication":return RuntimeEnvironment.getApplication();case "getHooks":return api;default:return null;}});probe=new NativeProbe(c,(metadata,state)->samples.add(metadata.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)),events::add);probe.install();if(hooks.isEmpty())return;service=new MediaPlaybackService();player=(LocalMediaPlayerController)service.Q.h;Method adopt=NativeProbe.class.getDeclaredMethod("adopt",Object.class);adopt.setAccessible(true);adopt.invoke(probe,service);idle();}
 @After public void close(){if(probe!=null)probe.close();idle();}
 @Test public void new1613ProfileReadsNativeAndOnlyConfirmsCommandedQueueChange()throws Exception{init(1613);assertEquals("A",samples.get(0));assertTrue(probe.observedSince()>0);PluginObserver o=hooks.get("skipToNextItem");Method m=player.getClass().getMethod("skipToNextItem");PluginObservation call=new PluginObservation(m,player,new Object[0],null,null);o.before(call);player.queue=new PlayerQueueItem(2,"B");o.after(call);idle();assertEquals(1,events.size());assertEquals("A",events.get(0).command.from);assertEquals("B",events.get(0).to);player.queue=new PlayerQueueItem(3,"C");probe.sample();idle();assertEquals(1,events.size());}
 @Test public void previousThatOnlyRestartsCurrentItemDoesNotCount()throws Exception{init(1613);PluginObserver o=hooks.get("skipToPreviousItem");Method m=player.getClass().getMethod("skipToPreviousItem");PluginObservation call=new PluginObservation(m,player,new Object[0],null,null);o.before(call);player.position=0;o.after(call);idle();assertEquals(0,events.size());}
 @Test public void thrownNavigationDoesNotArm()throws Exception{init(1613);PluginObserver o=hooks.get("skipToNextItem");Method m=player.getClass().getMethod("skipToNextItem");o.before(new PluginObservation(m,player,new Object[0],null,null));player.queue=new PlayerQueueItem(2,"B");o.after(new PluginObservation(m,player,new Object[0],null,new IllegalStateException()));probe.sample();idle();assertTrue(events.isEmpty());}
 @Test public void host1622ConfirmsQueueSelectionAndRejectsNaturalAdvance()throws Exception{
    init(1622);
    assertEquals("A",samples.get(0));
    assertTrue(probe.observedSince()>0);
    player.queue=new PlayerQueueItem(2,"B");
    probe.sample();idle();
    assertTrue(events.isEmpty());
    PluginObserver observer=hooks.get("skipToQueueItemWithId");
    Method method=player.getClass().getMethod("skipToQueueItemWithId",long.class);
    PluginObservation call=new PluginObservation(method,player,new Object[]{3L},null,null);
    observer.before(call);
    player.queue=new PlayerQueueItem(3,"C");
    observer.after(call);idle();
    assertEquals(1,events.size());
    assertEquals("queue",events.get(0).command.kind);
    assertEquals("B",events.get(0).command.from);
    assertEquals("C",events.get(0).to);
 }
 @Test public void host1626ReadsPlaybackAndConfirmsNextButNotNaturalAdvance()throws Exception{
    init(1626);assertEquals("A",samples.get(0));assertTrue(probe.observedSince()>0);
    PluginObserver observer=hooks.get("skipToNextItem");Method method=player.getClass().getMethod("skipToNextItem");
    PluginObservation call=new PluginObservation(method,player,new Object[0],null,null);
    observer.before(call);player.queue=new PlayerQueueItem(2,"B");observer.after(call);idle();
    assertEquals(1,events.size());assertEquals("next",events.get(0).command.kind);
    player.queue=new PlayerQueueItem(3,"C");probe.sample();idle();assertEquals(1,events.size());
 }
 @Test public void uniqueCompatibleUnknownHostCanObserveWithoutVersionUpdate()throws Exception{
    ClassLoader parent=getClass().getClassLoader();
    ClassLoader compatible=new ClassLoader(parent){@Override public Class<?> loadClass(String name)throws ClassNotFoundException{
        if(name.equals("com.apple.android.music.player.M")||name.equals("com.apple.android.music.player.O"))throw new ClassNotFoundException(name);
        return super.loadClass(name);
    }};
    init(1700,compatible);assertEquals("A",samples.get(0));assertTrue(probe.observedSince()>0);
    assertNotNull(hooks.get("skipToNextItem"));
 }
 @Test public void partialHookFailureRollsBackAndDoesNotClaimCoverage()throws Exception{
    failRegistration="skipToPreviousItem";init(1626);
    assertTrue(hooks.isEmpty());assertEquals(1,closedRegistrations);assertEquals(0,probe.observedSince());
    probe.start();idle();assertTrue(samples.isEmpty());
 }
 @Test public void retains1609ProfileAndRejectsUnknownVersion()throws Exception{init(1609);assertEquals("A",samples.get(0));probe.close();hooks.clear();init(9999);assertTrue(hooks.isEmpty());assertEquals(0,probe.observedSince());}
}
