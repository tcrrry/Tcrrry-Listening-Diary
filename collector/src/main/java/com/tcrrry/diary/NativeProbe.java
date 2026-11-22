package com.tcrrry.diary;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.*;
import dev.amenhancer.plugin.api.*;
import java.lang.reflect.*;
import java.util.*;

/** Observes confirmed playback commands after validating a complete host contract. */
final class NativeProbe implements AutoCloseable {
    interface Listener {void sample(MediaMetadata metadata,PlaybackState state);}
    interface Events {void confirmed(SkipTracker.Event event);}
    private final Events events;private final SkipTracker skips=new SkipTracker();private volatile long observedSince;
    private NativeHostContract contract;private boolean eventHooksReady;
    long observedSince(){return observedSince;}
    private final PluginContext context;private final Listener listener;private final Handler main=new Handler(Looper.getMainLooper());
    private final List<PluginRegistration> hooks=new ArrayList<>();
    private volatile boolean closed;private volatile Object service,controller;private Object callback;private Handler owner;
    private Class<?> serviceType,controllerType,queueType;
    NativeProbe(PluginContext c,Listener l,Events events){context=c;listener=l;this.events=events;}
    void install(){
        if(closed||contract!=null)return;
        NativeHostContract resolved=NativeHostContract.resolve(context.getHostClassLoader(),context.getHostVersionCode());
        if(resolved==null){context.log("Diary: host structure not verified; using MediaSession (host "+context.getHostVersionCode()+")",null);return;}
        contract=resolved;
        try{
            installCommand(resolved.next,"next");installCommand(resolved.previous,"previous");installCommand(resolved.select,"queue");
            for(Method m:resolved.lifecycle)hooks.add(context.getHooks().observe(m,new PluginObserver(){@Override public void after(PluginObservation c){if(c.getThrowable()==null&&!closed&&(service!=c.getReceiver()||controller==null))main.post(()->adopt(c.getReceiver()));}}));
            serviceType=resolved.service;controllerType=resolved.controller;queueType=resolved.queue;eventHooksReady=true;
            android.util.Log.i("DiarySkip","Contract verified host="+context.getHostVersionCode()+" converter="+resolved.converterName+" mode="+(resolved.explicitVersion?"explicit":"structural"));
        }catch(Exception|LinkageError e){
            for(PluginRegistration registration:hooks)try{registration.close();}catch(Exception ignored){}
            hooks.clear();contract=null;serviceType=null;controllerType=null;queueType=null;eventHooksReady=false;
            context.log("Diary: native observer installation rolled back; using MediaSession",null);
        }
    }
    private String trackId(Object player,Object queue)throws Exception{Object raw=contract.convert.invoke(null,player,queue);Object item=contract.toItem.invoke(null,raw);return item==null?"":String.valueOf(call(item,"getId"));}
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
    private void adopt(Object s){if(closed)return;synchronized(this){if(service==s&&controller!=null)return;service=s;}try{Handler h=(Handler)contract.handler.get(s);if(h!=null)h.post(()->attach(s,h));}catch(Exception ignored){}}
    private void attach(Object s,Handler h){if(closed||service!=s)return;try{Object found=contract.activeController.get(contract.manager.get(s));if(found==null||found==controller)return;detach();controller=found;owner=h;Class<?> type=contract.listener;callback=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,m,args)->{String n=m.getName();if(n.equals("hashCode"))return System.identityHashCode(proxy);if(n.equals("equals"))return proxy==args[0];if(n.equals("toString"))return "Listening Diary observer";if(n.equals("onCurrentItemChanged")||n.equals("onMetadataUpdated")||n.equals("onPlaybackStateChanged")||n.equals("onSeekToPosition")||n.equals("onBufferingStateChanged")||n.equals("onPlayerStateRestored"))sample();return null;});contract.addListener.invoke(found,callback);read();}catch(Exception ignored){}}
    void sample(){Handler h=owner;if(h!=null&&!closed)h.post(this::read);}
    // read() posts its observation first; completion follows it on the same main queue.
    void sample(Runnable complete){Handler h=owner;if(h!=null&&!closed)h.post(()->{read();main.post(complete);});else main.post(complete);}
    private void read(){if(closed||controller==null)return;try{
        Object queue=call(controller,"getCurrentItem");if(queue==null)return;
        Object raw=contract.convert.invoke(null,controller,queue);Object item=contract.toItem.invoke(null,raw);if(item==null)return;
        Object media=call(queue,"getItem");String id=String.valueOf(call(item,"getId"));
        MediaMetadata metadata=new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID,id).putString(MediaMetadata.METADATA_KEY_TITLE,text(media,"getTitle")).putString(MediaMetadata.METADATA_KEY_ARTIST,text(media,"getArtistName")).putString(MediaMetadata.METADATA_KEY_ALBUM,text(media,"getAlbumTitle")).putLong(MediaMetadata.METADATA_KEY_DURATION,Math.max(0,((Number)call(controller,"getDuration")).longValue())).build();
        boolean playing=Boolean.TRUE.equals(contract.isPlaying.invoke(contract.player.get(service))),buffering=Boolean.TRUE.equals(call(controller,"isBuffering"));float speed=((Number)call(controller,"getPlaybackRate")).floatValue();long position=Math.max(0,((Number)call(controller,"getCurrentPosition")).longValue());
        PlaybackState state=new PlaybackState.Builder().setState(buffering?PlaybackState.STATE_BUFFERING:playing?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,position,speed,SystemClock.elapsedRealtime()).build();
        long queueId=((Number)call(queue,"getPlaybackQueueId")).longValue();long observedAt=SystemClock.elapsedRealtime();
        main.post(()->{if(!closed){
            if(eventHooksReady&&observedSince==0){android.content.SharedPreferences p=context.getApplication().getSharedPreferences("com.tcrrry.listeningdiary",0);observedSince=p.getLong("events_since",0);if(observedSince==0){observedSince=System.currentTimeMillis();p.edit().putLong("events_since",observedSince).apply();}android.util.Log.i("DiarySkip","Observer active for host "+context.getHostVersionCode());}
            SkipTracker.Event event=skips.observe(id,queueId,position,observedAt);if(event!=null){events.confirmed(event);android.util.Log.i("DiarySkip","Confirmed command: "+event.command.kind);}
            listener.sample(metadata,state);
        }});
    }catch(Exception ignored){}}
    private void detach(){if(controller!=null&&callback!=null)try{contract.removeListener.invoke(controller,callback);}catch(Exception ignored){}controller=null;callback=null;}
    @Override public void close(){closed=true;skips.reset();for(PluginRegistration h:hooks)try{h.close();}catch(Exception ignored){}hooks.clear();Handler h=owner;if(h!=null)h.post(this::detach);service=null;main.removeCallbacksAndMessages(null);}
}
