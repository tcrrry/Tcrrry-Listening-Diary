package com.tcrrry.diary;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

/** Verified catalog matches are an overlay; the native and imported ledgers remain untouched. */
final class TrackMetadata {
    private static final Map<String,TrackMetadata> shared=new ConcurrentHashMap<>();
    static TrackMetadata shared(File art){return shared.computeIfAbsent(art.getAbsolutePath(),key->new TrackMetadata(art));}
    private final File folder;
    private final Map<String,LinkedHashMap<String,JSONObject>> records=new HashMap<>();
    private boolean loaded;private long version;
    TrackMetadata(File art){folder=new File(art,"track-matches-v1");}
    static String binding(AppleTrackImages.Hint hint){
        try{
            byte[] data=MessageDigest.getInstance("SHA-256").digest(new JSONArray().put(hint.title).put(hint.artist).put(hint.album).put(hint.duration).toString().getBytes(StandardCharsets.UTF_8));
            char[] out=new char[data.length*2];String digits="0123456789abcdef";for(int i=0;i<data.length;i++){out[i*2]=digits.charAt((data[i]&255)>>>4);out[i*2+1]=digits.charAt(data[i]&15);}return new String(out);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private void load(){
        if(loaded)return;loaded=true;
        File[] files=folder.listFiles((d,n)->n.endsWith(".json"));if(files==null)return;
        int count=0;for(File file:files){if(++count>10000)break;
            try{if(file.length()>50000)continue;JSONObject saved=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));String id=saved.getString("id");if(!file.getName().equals(ArtworkKeys.hash(id)+".json"))continue;
                JSONObject entries=saved.getJSONObject("entries");LinkedHashMap<String,JSONObject> valid=new LinkedHashMap<>();
                Iterator<String> keys=entries.keys();while(keys.hasNext()&&valid.size()<8){String key=keys.next();JSONObject row=entries.getJSONObject(key);if(key.matches("[0-9a-f]{64}")&&row.optString("trackId").matches("[0-9]{5,22}"))valid.put(key,row);}
                if(!valid.isEmpty())records.put(id,valid);
            }catch(Exception ignored){}
        };if(!records.isEmpty())version++;
    }
    synchronized long version(){load();return version;}
    synchronized JSONObject lookup(String id,AppleTrackImages.Hint hint){load();Map<String,JSONObject> entries=records.get(id);return entries==null?null:entries.get(binding(hint));}
    synchronized JSONObject lookup(Play play){load();if(!records.containsKey(play.getId()))return null;return lookup(play.getId(),new AppleTrackImages.Hint(play.getTitle(),play.getArtist(),play.getAlbum(),play.getDuration()));}
    synchronized boolean put(String id,AppleTrackImages.Hint hint,JSONObject row){
        load();try{
            if(AppleTrackImages.match(hint,new JSONArray().put(row))==null)return false;
            JSONObject safe=new JSONObject();for(String field:new String[]{"trackId","trackName","artistId","artistName","collectionId","collectionName","trackTimeMillis","trackCount","discCount","primaryGenreName","artworkUrl100"})if(row.has(field)&&!row.isNull(field))safe.put(field,row.get(field));
            String key=binding(hint);LinkedHashMap<String,JSONObject> entries=new LinkedHashMap<>(records.getOrDefault(id,new LinkedHashMap<>()));JSONObject old=entries.get(key);if(old!=null&&old.toString().equals(safe.toString()))return false;
            entries.put(key,safe);while(entries.size()>8)entries.remove(entries.keySet().iterator().next());JSONObject packed=new JSONObject();for(Map.Entry<String,JSONObject> entry:entries.entrySet())packed.put(entry.getKey(),entry.getValue());
            folder.mkdirs();File temp=File.createTempFile("match-",".tmp",folder);try{Files.write(temp.toPath(),new JSONObject().put("id",id).put("entries",packed).toString().getBytes(StandardCharsets.UTF_8));Files.move(temp.toPath(),new File(folder,ArtworkKeys.hash(id)+".json").toPath(),StandardCopyOption.REPLACE_EXISTING);}finally{temp.delete();}
            records.put(id,entries);version++;return true;
        }catch(Exception e){return false;}
    }
    synchronized JSONArray catalog(Collection<String> ids){
        load();JSONArray out=new JSONArray();for(String id:ids){Map<String,JSONObject> entries=records.get(id);if(entries==null||entries.isEmpty())continue;JSONObject row=null;for(JSONObject candidate:entries.values())row=candidate;
            try{out.put(new JSONObject(row.toString()).put("catalogTrackId",row.optString("trackId")).put("trackId",id));}catch(JSONException ignored){}
        };return out;
    }
}
