package com.tcrrry.diary;
import android.content.*;import android.net.Uri;import android.os.*;import android.util.Base64;import org.json.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;

/** Local ContentProvider handoff. The collector remains the durable outbox, and ACKs advance a cursor. */
final class CompanionBridge {
    static final String APP="com.tcrrry.listeningdiary.app";
    static final Uri URI=Uri.parse("content://com.tcrrry.listeningdiary.bridge/records");
    private final Context context;private final SharedPreferences preferences;private final File art;
    private final String source;private long nextAttempt;
    volatile String status="打开听歌日记 App，授权此 AM++ 后自动同步";
    CompanionBridge(Context context,File art){this.context=context;this.art=art;preferences=context.getSharedPreferences("com.tcrrry.listeningdiary.bridge",Context.MODE_PRIVATE);String saved=preferences.getString("source",null);if(saved==null){saved=UUID.randomUUID().toString();preferences.edit().putString("source",saved).commit();}source=saved;}
    String source(){return source;}
    void sync(DiaryStore store,String title,String playback,boolean force){sync(store,"",title,playback,force);}
    void sync(DiaryStore store,String trackId,String title,String playback,boolean force){sync(store,trackId,title,playback,force,0);}
    void sync(DiaryStore store,String trackId,String title,String playback,boolean force,long eventsSince){if(store==null||(!force&&SystemClock.elapsedRealtime()<nextAttempt))return;nextAttempt=SystemClock.elapsedRealtime()+15000;
        // A stable provider reference lets Android kill AM when the Diary process is updated or dies.
        // Keep only a scoped unstable client: an interrupted transfer retries without taking down playback.
        try(ContentProviderClient client=context.getContentResolver().acquireUnstableContentProviderClient(URI)){
            if(client==null)throw new IOException("Diary provider unavailable");
            Bundle hello=client.call("hello",null,null);if(hello==null||!hello.getBoolean("approved")){status="请在听歌日记 App 中允许此 AM++ 提供记录";return;}
            JSONArray needed=new JSONArray(hello.getString("neededArt","[]"));
            String generation=hello.getString("generation","");if(!generation.equals(preferences.getString("generation",""))){SharedPreferences.Editor reset=preferences.edit().putString("generation",generation).putLong("revision",0).putLong("event_revision",0);for(String key:preferences.getAll().keySet())if(key.startsWith("art_"))reset.remove(key);reset.commit();}
            long after=preferences.getLong("revision",0),eventAfter=preferences.getLong("event_revision",0);boolean eventSupport=hello.getInt("eventSupport",0)>=1;int sent=0;
            for(int batch=0;batch<(force?32:4);batch++){
                JSONObject payload=store.syncBatch(after,eventAfter,32);if(eventSupport)payload.put("eventsSince",eventsSince);else payload.remove("events");payload.put("sourceId",source).put("nowTitle",title).put("nowTrackId",trackId).put("status",playback);JSONArray tracks=payload.getJSONArray("tracks"),images=new JSONArray();
                LinkedHashSet<String> candidates=new LinkedHashSet<>();for(int i=0;i<needed.length();i++)candidates.add(needed.getString(i));
                for(int i=0;i<tracks.length();i++){String id=tracks.getJSONObject(i).getString("id");File image=new File(art,DiaryPlugin.artKey(id)+".jpg");if(image.isFile()&&preferences.getLong("art_"+DiaryPlugin.artKey(id),0)!=image.lastModified())candidates.add(id);}
                int imageBytes=0;Map<String,Long> delivered=new HashMap<>();
                for(String id:candidates){if(images.length()>=8)break;File f=new File(art,DiaryPlugin.artKey(id)+".jpg");if(!f.isFile())continue;byte[] bytes=java.nio.file.Files.readAllBytes(f.toPath());
                    if(bytes.length>120000){android.graphics.Bitmap original=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(original==null)continue;float scale=Math.min(1f,512f/Math.max(original.getWidth(),original.getHeight()));android.graphics.Bitmap resized=android.graphics.Bitmap.createScaledBitmap(original,Math.max(1,(int)(original.getWidth()*scale)),Math.max(1,(int)(original.getHeight()*scale)),true);ByteArrayOutputStream output=new ByteArrayOutputStream();resized.compress(android.graphics.Bitmap.CompressFormat.JPEG,72,output);bytes=output.toByteArray();if(resized!=original)resized.recycle();original.recycle();}
                    if(bytes.length>120000||imageBytes+bytes.length>180000)continue;
                    images.put(new JSONObject().put("id",id).put("jpeg",Base64.encodeToString(bytes,Base64.NO_WRAP)));imageBytes+=bytes.length;delivered.put("art_"+DiaryPlugin.artKey(id),f.lastModified());
                }
                payload.put("art",images);String body=payload.toString();if(body.getBytes(StandardCharsets.UTF_8).length>370000){payload.remove("art");delivered.clear();body=payload.toString();}if(body.getBytes(StandardCharsets.UTF_8).length>370000)throw new IOException("Batch exceeds local IPC budget");
                Bundle request=new Bundle();request.putString("payload",body);Bundle ack=client.call("upsert",null,request);if(ack==null||!ack.getBoolean("accepted"))throw new IOException("No local ACK");
                after=payload.getLong("revision");if(eventSupport)eventAfter=payload.getLong("eventRevision");SharedPreferences.Editor saved=preferences.edit().putLong("revision",after).putLong("event_revision",eventAfter);for(Map.Entry<String,Long> item:delivered.entrySet())saved.putLong(item.getKey(),item.getValue());saved.commit();
                JSONArray remaining=new JSONArray();for(int i=0;i<needed.length();i++)if(!delivered.containsKey("art_"+DiaryPlugin.artKey(needed.getString(i))))remaining.put(needed.getString(i));needed=remaining;int count=payload.getJSONArray("spans").length();sent+=count;if(count==0&&(!eventSupport||payload.getJSONArray("events").length()==0)&&(images.length()==0||needed.length()==0))break;
            }
            status="本地同步正常 · 已确认版本 "+after+(sent>0?" · 本轮 "+sent+" 段":"");
        }catch(Exception e){status="记录仍保存在插件内；同步未完成（"+e.getClass().getSimpleName()+"），请打开日记重新确认连接";android.util.Log.w("ListeningDiary","Local sync failed: "+e.getClass().getSimpleName());nextAttempt=SystemClock.elapsedRealtime()+15000;}
    }
}
