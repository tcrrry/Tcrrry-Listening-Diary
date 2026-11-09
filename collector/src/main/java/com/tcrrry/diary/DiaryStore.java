package com.tcrrry.diary;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/** Used only on the diary worker. Own directory and schema; no host DB access. */
final class DiaryStore implements AutoCloseable {
    private final SQLiteDatabase db;
    private long revision;
    DiaryStore(File directory){
        directory.mkdirs();db=SQLiteDatabase.openOrCreateDatabase(new File(directory,"diary-v1.db"),null);
        db.enableWriteAheadLogging();
        db.execSQL("CREATE TABLE IF NOT EXISTS tracks(id TEXT PRIMARY KEY,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,duration INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS spans(id INTEGER PRIMARY KEY,visit TEXT NOT NULL,track TEXT NOT NULL,day TEXT NOT NULL,zone TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,ms INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS spans_day ON spans(day)");
        db.execSQL("CREATE INDEX IF NOT EXISTS spans_visit ON spans(visit)");
        boolean hasRevision=false,hasIdentity=false,hasDevice=false,hasRoute=false,hasVolume=false;
        try(Cursor c=db.rawQuery("PRAGMA table_info(spans)",null)){while(c.moveToNext()){hasRevision|="sync_rev".equals(c.getString(1));hasIdentity|="sync_id".equals(c.getString(1));hasDevice|="device".equals(c.getString(1));hasRoute|="route_source".equals(c.getString(1));hasVolume|="volume_pct".equals(c.getString(1));}}
        if(!hasVolume)db.execSQL("ALTER TABLE spans ADD COLUMN volume_pct INTEGER NOT NULL DEFAULT -1");
        if(!hasDevice)db.execSQL("ALTER TABLE spans ADD COLUMN device TEXT NOT NULL DEFAULT ''");
        if(!hasRoute)db.execSQL("ALTER TABLE spans ADD COLUMN route_source TEXT NOT NULL DEFAULT ''");
        if(!hasRevision)db.execSQL("ALTER TABLE spans ADD COLUMN sync_rev INTEGER NOT NULL DEFAULT 0");
        if(!hasIdentity)db.execSQL("ALTER TABLE spans ADD COLUMN sync_id INTEGER NOT NULL DEFAULT 0");
        db.execSQL("UPDATE spans SET sync_rev=id WHERE sync_rev=0");db.execSQL("UPDATE spans SET sync_id=id WHERE sync_id=0");
        db.execSQL("CREATE INDEX IF NOT EXISTS spans_sync ON spans(sync_rev)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_meta(k TEXT PRIMARY KEY,v INTEGER NOT NULL)");
        try(Cursor c=db.rawQuery("SELECT MAX(value) FROM (SELECT COALESCE(MAX(sync_rev),0) value FROM spans UNION ALL SELECT v FROM sync_meta WHERE k='revision')",null)){if(c.moveToFirst())revision=c.getLong(0);}
        db.execSQL("CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT UNIQUE NOT NULL,day TEXT NOT NULL,zone TEXT NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL,from_track TEXT NOT NULL,to_track TEXT NOT NULL,position INTEGER NOT NULL,duration INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS events_day ON events(day)");db.setVersion(1);
    }
    void save(ListeningClock.Chunk chunk){
        db.beginTransaction();try{
            ListeningClock.Sample s=chunk.song;ContentValues t=new ContentValues();t.put("id",s.id);t.put("title",s.title);t.put("artist",s.artist);t.put("album",s.album);t.put("duration",s.duration);db.insertWithOnConflict("tracks",null,t,SQLiteDatabase.CONFLICT_REPLACE);
            DaySlices.split(chunk.start,chunk.end,TimeZone.getDefault(),(day,zone,start,end)->{
                long rev=++revision;
                long id=-1,lastEnd=0;
                try(Cursor c=db.rawQuery("SELECT id,end FROM spans WHERE visit=? AND day=? AND zone=? AND device=? AND route_source=? AND volume_pct=? ORDER BY id DESC LIMIT 1",new String[]{chunk.visit,day,zone,s.device,s.routeSource,Integer.toString(s.volume)})){if(c.moveToFirst()){id=c.getLong(0);lastEnd=c.getLong(1);}}
                if(id>=0&&start==lastEnd){db.execSQL("UPDATE spans SET end=?,ms=ms+?,sync_rev=? WHERE id=?",new Object[]{end,end-start,rev,id});}
                else{ContentValues row=new ContentValues();row.put("volume_pct",s.volume);row.put("device",s.device);row.put("route_source",s.routeSource);row.put("visit",chunk.visit);row.put("track",s.id);row.put("day",day);row.put("zone",zone);row.put("start",start);row.put("end",end);row.put("ms",end-start);row.put("sync_rev",rev);row.put("sync_id",rev);db.insertOrThrow("spans",null,row);}
            });db.execSQL("INSERT OR REPLACE INTO sync_meta(k,v) VALUES('revision',?)",new Object[]{revision});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void saveEvent(SkipTracker.Event event){
        SkipTracker.Command c=event.command;ContentValues row=new ContentValues();TimeZone zone=TimeZone.getDefault();SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);date.setTimeZone(zone);
        row.put("event_id",event.id);row.put("day",date.format(new Date(c.wall)));row.put("zone",zone.getID());row.put("at",c.wall);row.put("kind",c.kind);row.put("from_track",c.from);row.put("to_track",event.to);row.put("position",Math.max(0,c.position));row.put("duration",Math.max(0,c.duration));db.insertWithOnConflict("events",null,row,SQLiteDatabase.CONFLICT_IGNORE);
    }
    private JSONArray rows(String sql,String...args)throws JSONException{
        JSONArray result=new JSONArray();try(Cursor c=db.rawQuery(sql,args)){String[] names=c.getColumnNames();while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<names.length;i++)row.put(names[i],c.getType(i)==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):c.getString(i));result.put(row);}}return result;
    }
    JSONObject report(String period,String selected)throws JSONException{
        String today=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date());
        if(selected==null||!selected.matches("\\d{4}-\\d{2}-\\d{2}"))selected=today;
        String from=selected,to=selected;
        if("7".equals(period)||"30".equals(period)){Calendar c=Calendar.getInstance();c.add(Calendar.DAY_OF_MONTH,"7".equals(period)?-6:-29);from=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(c.getTime());to=today;}
        if("all".equals(period)){from="0000-01-01";to="9999-12-31";}
        String filter=" WHERE s.day>=? AND s.day<=? ";String[] args={from,to};
        JSONObject j=new JSONObject().put("schemaVersion",1).put("today",today).put("selected",selected).put("period",period).put("zone",TimeZone.getDefault().getID());
        j.put("summary",rows("SELECT COALESCE(SUM(s.ms),0) ms,COUNT(DISTINCT s.track) tracks,COUNT(DISTINCT t.artist) artists,COUNT(DISTINCT s.day) days FROM spans s JOIN tracks t ON t.id=s.track"+filter,args).getJSONObject(0));
        j.put("plays",rows("SELECT COUNT(*) n FROM (SELECT s.visit FROM spans s JOIN tracks t ON t.id=s.track"+filter+"GROUP BY s.visit HAVING SUM(s.ms)>=CASE WHEN MAX(t.duration)>0 THEN MIN(30000,MAX(t.duration)/2) ELSE 30000 END)",args).getJSONObject(0).getLong("n"));
        j.put("songs",rows("SELECT t.id,t.title,t.artist,t.album,SUM(s.ms) ms,COUNT(DISTINCT s.visit) starts FROM spans s JOIN tracks t ON t.id=s.track"+filter+"GROUP BY t.id ORDER BY ms DESC LIMIT 50",args));
        j.put("artists",rows("SELECT t.artist,SUM(s.ms) ms,COUNT(DISTINCT t.id) tracks,MAX(s.end) last FROM spans s JOIN tracks t ON t.id=s.track"+filter+"GROUP BY t.artist ORDER BY ms DESC LIMIT 30",args));
        j.put("recent",rows("SELECT s.visit,t.id,t.title,t.artist,t.album,MIN(s.start) start,MAX(s.end) end,SUM(s.ms) ms,t.duration FROM spans s JOIN tracks t ON t.id=s.track"+filter+"GROUP BY s.visit ORDER BY start DESC LIMIT 150",args));
        j.put("daily",rows("SELECT s.visit,t.id,t.title,t.artist,t.album,MIN(s.start) start,MAX(s.end) end,SUM(s.ms) ms,t.duration,MAX(s.zone) zone FROM spans s JOIN tracks t ON t.id=s.track WHERE s.day=? GROUP BY s.visit ORDER BY start ASC",selected));
        j.put("timeline",rows("SELECT s.start,s.end,s.ms,s.zone,t.id,t.title,t.artist FROM spans s JOIN tracks t ON t.id=s.track WHERE s.day=? ORDER BY s.start LIMIT 600",selected));
        j.put("heat",rows("SELECT day,SUM(ms) ms FROM spans GROUP BY day ORDER BY day DESC LIMIT 366"));
        return j;
    }
    JSONObject backup()throws JSONException{return new JSONObject().put("schemaVersion",1).put("plugin","com.tcrrry.listeningdiary").put("exportedAt",System.currentTimeMillis()).put("tracks",rows("SELECT * FROM tracks")).put("spans",rows("SELECT * FROM spans ORDER BY id")).put("events",rows("SELECT * FROM events ORDER BY seq"));}
    JSONObject syncBatch(long after,int limit)throws JSONException{return syncBatch(after,0,limit);}
    JSONObject syncBatch(long after,long eventAfter,int limit)throws JSONException{
        JSONArray spans=rows("SELECT sync_id id,visit,track,day,zone,start,end,ms,sync_rev,device,route_source,volume_pct FROM spans WHERE sync_rev>? ORDER BY sync_rev LIMIT ?",Long.toString(after),Integer.toString(Math.max(1,Math.min(limit,64))));
        LinkedHashSet<String> keys=new LinkedHashSet<>();long next=after;for(int i=0;i<spans.length();i++){JSONObject row=spans.getJSONObject(i);keys.add(row.getString("track"));next=Math.max(next,row.getLong("sync_rev"));}
        JSONArray tracks=new JSONArray();for(String id:keys){JSONArray result=rows("SELECT * FROM tracks WHERE id=?",id);if(result.length()>0)tracks.put(result.getJSONObject(0));}
        JSONArray events=rows("SELECT * FROM events WHERE seq>? ORDER BY seq LIMIT ?",Long.toString(eventAfter),Integer.toString(Math.max(1,Math.min(limit,64))));long eventNext=eventAfter;for(int i=0;i<events.length();i++)eventNext=Math.max(eventNext,events.getJSONObject(i).getLong("seq"));
        return new JSONObject().put("schemaVersion",1).put("tracks",tracks).put("spans",spans).put("revision",next).put("events",events).put("eventRevision",eventNext);
    }
    void clear(){db.beginTransaction();try{db.delete("spans",null,null);db.delete("tracks",null,null);db.delete("events",null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    @Override public void close(){db.close();}
}
