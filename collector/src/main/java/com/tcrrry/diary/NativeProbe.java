package com.tcrrry.diary;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.*;
import dev.amenhancer.plugin.api.*;
import java.lang.reflect.*;
import java.util.*;

/** Read-only, exact 1609 contract. No host method replacement and no player controls. */
final class NativeProbe implements AutoCloseable {
    interface Listener {void sample(MediaMetadata metadata,PlaybackState state);}
    interface Events {void confirmed(SkipTracker.Event event);}
    private final Events events;private final SkipTracker skips=new SkipTracker();private volatile long observedSince;
    private String converterName;private boolean eventHooksReady;
    long observedSince(){return observedSince;}
    private final PluginContext context;private final Listener listener;private final Handler main=new Handler(Looper.getMainLooper());
    private final List<PluginRegistration> hooks=new ArrayList<>();
    private volatile boolean closed;private volatile Object service,controller;private Object callback;private Handler owner;
    private Class<?> serviceType,controllerType,queueType;
    NativeProbe(PluginContext c,Listener l,Events events){context=c;listener=l;this.events=events;}
    void install(){
        long version=context.getHostVersionCode();if(version!=1609&&version!=1613)return;
        converterName=version==1613?"com.apple.android.music.player.M":"com.apple.android.music.player.O";
        try{
            ClassLoader l=context.getHostClassLoader();serviceType=l.loadClass("com.apple.android.music.player.MediaPlaybackService");controllerType=l.loadClass("com.apple.android.music.playback.controller.MediaPlayerController");queueType=l.loadClass("com.apple.android.music.playback.model.PlayerQueueItem");
            // Verify the exact profile before installing hooks; unknown versions stay on MediaSession.
            serviceType.getDeclaredField("x");serviceType.getDeclaredField("Q");serviceType.getDeclaredField("M");
            Class<?> convert=l.loadClass(converterName);convert.getMethod("f",controllerType,queueType);convert.getMethod("b",l.loadClass("z3.y"));
            Class<?> local=l.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController");
            installCommand(local.getDeclaredMethod("skipToNextItem"),"next");
            installCommand(local.getDeclaredMethod("skipToPreviousItem"),"previous");
            installCommand(local.getDeclaredMethod("skipToQueueItemWithId",long.class),"queue");eventHooksReady=true;
            for(Method m:serviceType.getDeclaredMethods())if(m.getName().equals("onCreate")||m.getName().equals("onStartCommand")||m.getName().equals("handleMessage"))hooks.add(context.getHooks().observe(m,new PluginObserver(){@Override public void after(PluginObservation c){if(c.getThrowable()==null&&!closed&&(service!=c.getReceiver()||controller==null))main.post(()->adopt(c.getReceiver()));}}));
        }catch(Exception e){context.log("Diary: native observer unavailable; using MediaSession",null);}
    }
    private String trackId(Object player,Object queue)throws Exception{Class<?> convert=context.getHostClassLoader().loadClass(converterName);Object raw=convert.getMethod("f",controllerType,queueType).invoke(null,player,queue);Object item=convert.getMethod("b",context.getHostClassLoader().loadClass("z3.y")).invoke(null,raw);return item==null?"":String.valueOf(call(item,"getId"));}
    private void installCommand(Method method,String kind){
        hooks.add(context.getHooks().observe(method,new PluginObserver(){
            private final ThreadLocal<SkipTracker.Command> frame=new ThreadLocal<>();
            @Override public void before(PluginObservation c){frame.remove();if(closed||c.getReceiver()!=controller||observedSince==0)return;try{Object queue=call(c.getReceiver(),"getCurrentItem");if(queue==null)return;frame.set(new SkipTracker.Command(kind,trackId(c.getReceiver(),queue),((Number)call(queue,"getPlaybackQueueId")).longValue(),System.currentTimeMillis(),SystemClock.elapsedRealtime(),((Number)call(c.getReceiver(),"getCurrentPosition")).longValue(),((Number)call(c.getReceiver(),"getDuration")).longValue()));}catch(Exception ignored){}}
            @Override public void after(PluginObservation c){SkipTracker.Command command=frame.get();frame.remove();if(command==null||c.getThrowable()!=null)return;main.post(()->{if(!closed&&c.getReceiver()==controller){skips.arm(command);sample();}});}
        }));
    }
    void start(){main.post(()->{if(closed||serviceType==null||controller!=null)return;try{Class<?> t=Class.forName("android.app.ActivityThread");Method m=t.getDeclaredMethod("currentActivityThread");m.setAccessible(true);Object services=field(m.invoke(null),"mServices");if(services instanceof Map)for(Object s:((Map<?,?>)services).values())if(serviceType.isInstance(s)){adopt(s);break;}}catch(Exception ignored){}});}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static Object call(Object o,String name)throws Exception{Class<?> type=o.getClass();while(type!=null){try{Method m=type.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}catch(NoSuchMethodException e){type=type.getSuperclass();}}throw new NoSuchMethodException(name);}
    private static String text(Object o,String name){try{Object v=call(o,name);return v instanceof String?(String)v:"";}catch(Exception e){return "";}}
    private void adopt(Object s){if(closed)return;synchronized(this){if(service==s&&controller!=null)return;service=s;}try{Handler h=(Handler)field(s,"x");if(h!=null)h.post(()->attach(s,h));}catch(Exception ignored){}}
    private void attach(Object s,Handler h){if(closed||service!=s)return;try{Object found=field(field(s,"Q"),"h");if(found==null||found==controller)return;detach();controller=found;owner=h;Class<?> type=context.getHostClassLoader().loadClass("com.apple.android.music.playback.controller.MediaPlayerController$Listener");callback=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,m,args)->{String n=m.getName();if(n.equals("hashCode"))return System.identityHashCode(proxy);if(n.equals("equals"))return proxy==args[0];if(n.equals("toString"))return "Listening Diary observer";if(n.equals("onCurrentItemChanged")||n.equals("onMetadataUpdated")||n.equals("onPlaybackStateChanged")||n.equals("onSeekToPosition")||n.equals("onBufferingStateChanged")||n.equals("onPlayerStateRestored"))sample();return null;});controllerType.getMethod("addListener",type).invoke(found,callback);read();}catch(Exception ignored){}}
    void sample(){Handler h=owner;if(h!=null&&!closed)h.post(this::read);}
    // read() posts its observation first; completion follows it on the same main queue.
    void sample(Runnable complete){Handler h=owner;if(h!=null&&!closed)h.post(()->{read();main.post(complete);});else main.post(complete);}
    private void read(){if(closed||controller==null)return;try{
        Object queue=call(controller,"getCurrentItem");if(queue==null)return;
        Class<?> convert=context.getHostClassLoader().loadClass(converterName);Object raw=convert.getMethod("f",controllerType,queueType).invoke(null,controller,queue);Object item=convert.getMethod("b",context.getHostClassLoader().loadClass("z3.y")).invoke(null,raw);if(item==null)return;
        Object media=call(queue,"getItem");String id=String.valueOf(call(item,"getId"));
        MediaMetadata metadata=new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID,id).putString(MediaMetadata.METADATA_KEY_TITLE,text(media,"getTitle")).putString(MediaMetadata.METADATA_KEY_ARTIST,text(media,"getArtistName")).putString(MediaMetadata.METADATA_KEY_ALBUM,text(media,"getAlbumTitle")).putLong(MediaMetadata.METADATA_KEY_DURATION,Math.max(0,((Number)call(controller,"getDuration")).longValue())).build();
        boolean playing=Boolean.TRUE.equals(call(field(service,"M"),"isPlaying")),buffering=Boolean.TRUE.equals(call(controller,"isBuffering"));float speed=((Number)call(controller,"getPlaybackRate")).floatValue();long position=Math.max(0,((Number)call(controller,"getCurrentPosition")).longValue());
        PlaybackState state=new PlaybackState.Builder().setState(buffering?PlaybackState.STATE_BUFFERING:playing?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,position,speed,SystemClock.elapsedRealtime()).build();
        long queueId=((Number)call(queue,"getPlaybackQueueId")).longValue();long observedAt=SystemClock.elapsedRealtime();
        main.post(()->{if(!closed){
            if(eventHooksReady&&observedSince==0){android.content.SharedPreferences p=context.getApplication().getSharedPreferences("com.tcrrry.listeningdiary",0);observedSince=p.getLong("events_since",0);if(observedSince==0){observedSince=System.currentTimeMillis();p.edit().putLong("events_since",observedSince).apply();}}
            SkipTracker.Event event=skips.observe(id,queueId,position,observedAt);if(event!=null)events.confirmed(event);
            listener.sample(metadata,state);
        }});
    }catch(Exception ignored){}}
    private void detach(){if(controller!=null&&callback!=null)try{controllerType.getMethod("removeListener",callback.getClass().getInterfaces()[0]).invoke(controller,callback);}catch(Exception ignored){}controller=null;callback=null;}
    @Override public void close(){closed=true;skips.reset();for(PluginRegistration h:hooks)try{h.close();}catch(Exception ignored){}hooks.clear();Handler h=owner;if(h!=null)h.post(this::detach);service=null;main.removeCallbacksAndMessages(null);}
}
