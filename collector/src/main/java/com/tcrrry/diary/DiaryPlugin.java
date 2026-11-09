package com.tcrrry.diary;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.View;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.Toast;
import dev.amenhancer.plugin.api.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class DiaryPlugin extends AmppPlugin {
    private PluginContext runtime;private DiaryStore store;private ListeningClock clock;private NativeProbe nativeProbe;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<PluginRegistration> hooks=new ArrayList<>();private final Set<CollectorPanel> views=new HashSet<>();
    private ExecutorService worker;private volatile boolean running,enabled=true;private volatile String status="等待播放",songTitle="",songId="",songArtist="",databaseError="";
    private CompanionBridge companion;
    private CollectorRequests syncRequests;
    private AudioTrack audioTrack; private AudioManager audioManager; private AudioDeviceCallback audioCallback;
    private MediaSession session;private MediaMetadata metadata;private PlaybackState playback;private long nativeAt;
    private android.content.SharedPreferences preferences;private File artDirectory;
    private final Runnable tick=new Runnable(){public void run(){if(!running)return;if(enabled){if(nativeProbe!=null){nativeProbe.start();nativeProbe.sample();}readSession("checkpoint");}if(worker!=null)worker.execute(()->companion.sync(store,songId,songTitle,status,false,nativeProbe.observedSince()));main.postDelayed(this,15000);}};
    @Override public void onLoad(PluginContext context)throws Exception{
        if(!"com.apple.android.music".equals(context.getHostPackageName()))throw new PluginUnsupportedException("听歌日记仅支持 Apple Music");
        runtime=context;preferences=context.getApplication().getSharedPreferences("com.tcrrry.listeningdiary",Context.MODE_PRIVATE);enabled=preferences.getBoolean("enabled",true);
        artDirectory=new File(context.getDataDirectory(),"art");artDirectory.mkdirs();companion=new CompanionBridge(context.getApplication(),artDirectory);
        worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ListeningDiary");t.setDaemon(true);return t;});
        worker.execute(()->{try{store=new DiaryStore(context.getDataDirectory());clock=new ListeningClock(store::save);context.log("Diary database ready (Android WAL API)",null);}catch(Exception e){databaseError="数据库打开失败（"+e.getClass().getSimpleName()+"），请保留数据并反馈";status=databaseError;context.log("Diary database open failed",e);}});
        observe(MediaSession.class.getDeclaredMethod("setMetadata",MediaMetadata.class),c->{MediaSession owner=(MediaSession)c.getReceiver();MediaMetadata m=(MediaMetadata)c.getArguments()[0];main.post(()->{
            if(!running)return;session=owner;cacheArt(m);if(SystemClock.elapsedRealtime()-nativeAt<45000){cachePairedArt(metadata,m);return;}metadata=m;try{playback=owner.getController().getPlaybackState();}catch(Exception ignored){}record("metadata");
        });});
        observe(MediaSession.class.getDeclaredMethod("setPlaybackState",PlaybackState.class),c->{MediaSession owner=(MediaSession)c.getReceiver();PlaybackState p=(PlaybackState)c.getArguments()[0];main.post(()->{
            if(!running||SystemClock.elapsedRealtime()-nativeAt<45000)return;if(session!=null&&session!=owner)return;session=owner;try{metadata=owner.getController().getMetadata();}catch(Exception ignored){}playback=p;cacheArt(metadata);record("playback");
        });});
        observe(MediaSession.class.getDeclaredMethod("release"),c->main.post(()->{if(running&&session==c.getReceiver()){playback=null;record("release");session=null;}}));
        try { observe(AudioTrack.class.getDeclaredMethod("play"), c->main.post(()->{if(running){audioTrack=(AudioTrack)c.getReceiver();main.postDelayed(()->record("audio-route"),500);}})); } catch(Exception e) { context.log("Diary audio track observer unavailable",null); }
        nativeProbe=new NativeProbe(context,(m,p)->{if(!running||!enabled)return;nativeAt=SystemClock.elapsedRealtime();metadata=m;playback=p;cacheArt(m);try{if(session!=null)cachePairedArt(m,session.getController().getMetadata());}catch(Exception ignored){}record("native");},event->{if(!running||!enabled||worker==null)return;worker.execute(()->{try{if(store!=null){store.saveEvent(event);companion.sync(store,songId,songTitle,status,true,nativeProbe.observedSince());}}catch(Exception e){runtime.log("Diary event write failed",null);}});});nativeProbe.install();context.onClose(this::onStop);
    }
    private interface Observation {void after(PluginObservation c)throws Exception;}
    private void observe(java.lang.reflect.Method m,Observation action){hooks.add(runtime.getHooks().observe(m,new PluginObserver(){@Override public void after(PluginObservation c){if(c.getThrowable()!=null)return;try{action.after(c);}catch(Exception e){runtime.log("Diary observation unavailable",null);}}}));}
    @Override public void onStart(){running=true;if(syncRequests==null)syncRequests=new CollectorRequests(runtime.getApplication(),this::syncNow);audioManager=(AudioManager)runtime.getApplication().getSystemService(Context.AUDIO_SERVICE);
        if(audioManager!=null){audioCallback=new AudioDeviceCallback(){@Override public void onAudioDevicesAdded(AudioDeviceInfo[] d){routeChanged();}@Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d){routeChanged();}};try{audioManager.registerAudioDeviceCallback(audioCallback,main);}catch(Exception ignored){}}
        nativeProbe.start();adoptSession();main.post(tick);}
    private void routeChanged(){main.postDelayed(()->{if(running&&enabled)record("audio-route");},700);}
    private String[] outputRoute(){
        AudioDeviceInfo device=null;String source="";
        try{if(audioTrack!=null&&audioTrack.getPlayState()==AudioTrack.PLAYSTATE_PLAYING){device=audioTrack.getRoutedDevice();if(device!=null)source="audio_track";}}catch(Exception ignored){}
        if(device==null&&audioManager!=null&&Build.VERSION.SDK_INT>=33)try{java.util.List<AudioDeviceInfo> routes=audioManager.getAudioDevicesForAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());if(routes.size()==1){device=routes.get(0);source="system_media";}}catch(Exception ignored){}
        if(device==null)return new String[]{"",""};
        int type=device.getType();String name=device.getProductName()==null?"":device.getProductName().toString().trim();
        if(type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER||type==AudioDeviceInfo.TYPE_BUILTIN_EARPIECE||type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)name="\u624b\u673a\u626c\u58f0\u5668";
        if(name.length()>160)name=name.substring(0,160);if(name.isEmpty())name="\u97f3\u9891\u8bbe\u5907 ("+type+")";return new String[]{name,source};
    }
    private void syncNow(Runnable finished){
        if(!running||worker==null){finished.run();return;}
        // A suspended/dead player handler must not hold the reply hostage.
        Runnable flush=SyncCheckpoint.bounded(main,1000,()->{
            if(!running||worker==null){finished.run();return;}
            readSession("sync-request");
            try{worker.execute(()->{try{companion.sync(store,songId,songTitle,status,true,nativeProbe.observedSince());}finally{finished.run();}});}catch(RejectedExecutionException e){finished.run();}
        });
        if(enabled&&nativeProbe!=null){nativeProbe.start();main.postDelayed(()->{if(running)nativeProbe.sample(flush);else finished.run();},120);}else flush.run();
    }
    private void adoptSession(){try{MediaSessionManager manager=(MediaSessionManager)runtime.getApplication().getSystemService(Context.MEDIA_SESSION_SERVICE);for(MediaController controller:manager.getActiveSessions(null))if(runtime.getHostPackageName().equals(controller.getPackageName())){metadata=controller.getMetadata();playback=controller.getPlaybackState();cacheArt(metadata);record("attach");break;}}catch(Exception ignored){}}
    private void readSession(String reason){if(SystemClock.elapsedRealtime()-nativeAt<45000)return;if(session!=null)try{MediaController c=session.getController();metadata=c.getMetadata();playback=c.getPlaybackState();cacheArt(metadata);record(reason);}catch(Exception ignored){}}
    private static String value(MediaMetadata m,String key){String s=m==null?null:m.getString(key);return s==null?"":s;}
    static String identity(MediaMetadata m){String id=value(m,MediaMetadata.METADATA_KEY_MEDIA_ID);return !id.isEmpty()&&!"0".equals(id)?id:"local:"+value(m,MediaMetadata.METADATA_KEY_TITLE)+"|"+value(m,MediaMetadata.METADATA_KEY_ARTIST)+"|"+value(m,MediaMetadata.METADATA_KEY_ALBUM);}
    private void record(String reason){
        if(!running||!enabled)return;
        String title=value(metadata,MediaMetadata.METADATA_KEY_TITLE);if(title.trim().isEmpty()){worker.execute(()->{if(clock!=null)clock.reset();});status="等待歌曲信息";return;}
        boolean playing=playback!=null&&playback.getState()==PlaybackState.STATE_PLAYING;long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();double speed=playback==null?0:playback.getPlaybackSpeed();long position=playback==null?0:Math.max(0,playback.getPosition());long duration=metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if(playing&&playback.getLastPositionUpdateTime()>0)position+=Math.max(0,(long)((elapsed-playback.getLastPositionUpdateTime())*speed));if(duration>0)position=Math.min(position,duration);
        String[] route=outputRoute();int volume=-1;try{if(audioManager!=null){int max=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);if(max>0)volume=Math.round(100f*audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)/max);}}catch(Exception ignored){}
        ListeningClock.Sample s=new ListeningClock.Sample(identity(metadata),title,value(metadata,MediaMetadata.METADATA_KEY_ARTIST),value(metadata,MediaMetadata.METADATA_KEY_ALBUM),wall,elapsed,position,duration,playing,speed,route[0],route[1],volume);
        String sampleStatus=playing?"正在记录":playback!=null&&playback.getState()==PlaybackState.STATE_BUFFERING?"缓冲中 · 暂停计时":"已暂停 · 记录已保存";
        boolean stateChanged=!s.id.equals(songId)||!sampleStatus.equals(status);
        songTitle=title;songId=s.id;songArtist=s.artist;status=sampleStatus;
        worker.execute(()->{try{if(clock!=null)clock.accept(s,reason);companion.sync(store,s.id,s.title,sampleStatus,stateChanged,nativeProbe.observedSince());}catch(Exception e){if(clock!=null)clock.reset();status="保存失败，请导出备份并反馈";runtime.log("Diary write failed",e);}});
    }
    static String artKey(String id){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private final Set<String> artPending=new HashSet<>();
    private void cachePairedArt(MediaMetadata nativeItem,MediaMetadata sessionItem){
        try{if(SessionArtwork.sameTrack(nativeItem,sessionItem))cacheArt(sessionItem,identity(nativeItem));}catch(Exception ignored){}
    }
    private void cacheArt(MediaMetadata m){if(m!=null)cacheArt(m,identity(m));}
    private void cacheArt(MediaMetadata m,String id){
        ExecutorService executor=worker;if(!enabled||m==null||id==null||id.isEmpty()||executor==null)return;
        String key=artKey(id);File target=new File(artDirectory,key+".jpg");if(target.isFile()||artPending.contains(key))return;
        Bitmap bitmap=null;for(String name:new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART,MediaMetadata.METADATA_KEY_ART,MediaMetadata.METADATA_KEY_DISPLAY_ICON}){try{bitmap=m.getBitmap(name);}catch(Exception ignored){}if(bitmap!=null)break;}
        Uri uri=bitmap==null?SessionArtwork.contentArtUri(m):null;if(bitmap==null&&uri==null)return;
        final Bitmap supplied=bitmap;final Uri source=uri;artPending.add(key);
        try{executor.execute(()->{Bitmap loaded=null,scaled=null;try{
            Bitmap image=supplied;if(image==null){loaded=SessionArtwork.load(runtime.getApplication().getContentResolver(),source);image=loaded;}if(image==null||image.isRecycled())return;
            double factor=Math.min(1,640.0/Math.max(image.getWidth(),image.getHeight()));
            scaled=Bitmap.createScaledBitmap(image,Math.max(1,(int)(image.getWidth()*factor)),Math.max(1,(int)(image.getHeight()*factor)),true);
            File temp=new File(artDirectory,key+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){scaled.compress(Bitmap.CompressFormat.JPEG,85,out);}if(!temp.renameTo(target))temp.delete();pruneArt();
        }catch(Exception ignored){}finally{
            if(scaled!=null&&scaled!=supplied&&scaled!=loaded&&!scaled.isRecycled())scaled.recycle();
            if(loaded!=null&&!loaded.isRecycled())loaded.recycle();
            main.post(()->artPending.remove(key));
        }});}catch(RejectedExecutionException e){artPending.remove(key);}
    }
    private void pruneArt(){File[] files=artDirectory.listFiles((d,n)->n.endsWith(".jpg"));if(files==null||files.length<=500)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(int i=0;i<files.length-500;i++)files[i].delete();}
    private void setEnabled(boolean on){main.post(()->{if(!running)return;if(!on&&enabled){playback=null;record("disabled");}enabled=on;preferences.edit().putBoolean("enabled",on).apply();worker.execute(()->{if(clock!=null)clock.reset();});status=on?"等待播放事件":"记录已关闭";if(on){nativeAt=0;nativeProbe.start();nativeProbe.sample();readSession("enabled");}});}
    private void exportBackup(Context context){worker.execute(()->{Uri uri=null;try{if(store==null)throw new IOException();byte[] bytes=store.backup().put("sourceId",companion.source()).put("eventsSince",nativeProbe.observedSince()).toString(2).getBytes(StandardCharsets.UTF_8);ContentValues values=new ContentValues();String name="Listening-Diary-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".json";values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,"application/json");values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/听歌日记");values.put(MediaStore.MediaColumns.IS_PENDING,1);ContentResolver resolver=runtime.getApplication().getContentResolver();uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException();try(OutputStream out=resolver.openOutputStream(uri)){if(out==null)throw new IOException();out.write(bytes);}ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);resolver.update(uri,done,null,null);main.post(()->Toast.makeText(context,"已导出到 Download/听歌日记/"+name,Toast.LENGTH_LONG).show());}catch(Exception e){if(uri!=null)runtime.getApplication().getContentResolver().delete(uri,null,null);main.post(()->Toast.makeText(context,"导出失败，请检查存储空间",Toast.LENGTH_LONG).show());}});}
    @Override public PluginSettingsSession createSettings(Context context){
        CollectorPanel panel=new CollectorPanel(context,new CollectorPanel.Actions(){
            public String status(){return databaseError.isEmpty()?DiaryPlugin.this.status+"\n"+songTitle+"\n"+companion.status:databaseError;}
            public boolean enabled(){return enabled;}
            public void toggle(){setEnabled(!enabled);}
            public void open(){try{Intent intent=new Intent().setComponent(new ComponentName(CompanionBridge.APP,"com.tcrrry.diary.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);runtime.getApplication().startActivity(intent);}catch(Exception e){Toast.makeText(context,"请先安装桌面交付包中的听歌日记 APK",Toast.LENGTH_LONG).show();}}
            public void sync(){main.post(()->syncNow(()->{}));}
            public void export(){exportBackup(context);}
            public void close(CollectorPanel p){views.remove(p);}
        });views.add(panel);return panel;
    }
    @Override public void onStop(){if(!running&&worker==null)return;if(running&&enabled){playback=null;record("stop");}running=false;if(audioManager!=null&&audioCallback!=null)try{audioManager.unregisterAudioDeviceCallback(audioCallback);}catch(Exception ignored){}audioCallback=null;audioTrack=null;if(syncRequests!=null){syncRequests.close();syncRequests=null;}main.removeCallbacksAndMessages(null);if(nativeProbe!=null)nativeProbe.close();for(PluginRegistration h:hooks)try{h.close();}catch(Exception ignored){}hooks.clear();for(CollectorPanel v:new ArrayList<>(views))v.close();if(worker!=null){worker.execute(()->{if(store!=null){companion.sync(store,songId,songTitle,status,true,nativeProbe.observedSince());store.close();}store=null;});worker.shutdown();worker=null;}}
}

