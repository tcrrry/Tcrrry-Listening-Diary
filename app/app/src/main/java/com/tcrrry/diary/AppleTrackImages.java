package com.tcrrry.diary;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

/** Resolve an imported local identity without ever replacing its ledger ID or guessing from title alone. */
final class AppleTrackImages {
    static final class Hint {
        final String title,artist,album;final long duration;
        Hint(String title,String artist,String album,long duration){this.title=title;this.artist=artist;this.album=album;this.duration=duration;}
    }
    private final Map<String,Hint> hints=new ConcurrentHashMap<>();
    private final Map<String,Map<String,Hint>> nativeByTitle=new ConcurrentHashMap<>();
    static Hint hint(Play p){return new Hint(p.getTitle(),p.getArtist(),p.getAlbum(),p.getDuration());}
    static boolean localMatch(Hint h,Hint source){
        if(!knownArtist(source.artist)||!normalized(h.title).equals(normalized(source.title)))return false;
        if(knownArtist(h.artist)&&!artistMatches(h.artist,source.artist))return false;
        if(!h.album.isBlank()&&!albumName(h.album).equals(albumName(source.album)))return false;
        if(!knownArtist(h.artist)&&h.duration<=0)return false;
        if(h.duration>0&&(source.duration<=0||Math.abs(h.duration-source.duration)>1500))return false;
        return h.duration>0||(!h.album.isBlank()&&!source.album.isBlank());
    }
    String localSource(String id,java.util.function.Predicate<String> available){
        Hint h=hints.get(id);if(h==null)return null;
        Map<String,Hint> candidates=nativeByTitle.get(normalized(h.title));if(candidates==null)return null;
        String found=null;Set<String> artists=new HashSet<>();
        for(Map.Entry<String,Hint> e:candidates.entrySet())if(!id.equals(e.getKey())&&localMatch(h,e.getValue())){artists.add(normalized(e.getValue().artist));if(available.test(e.getKey())&&(found==null||e.getKey().compareTo(found)<0))found=e.getKey();}
        return artists.size()==1?found:null;
    }
    static boolean knownArtist(String name){return !name.isBlank()&&!name.equals("未知歌手");}
    void bind(Collection<Play> plays){
        // Listening reports contain one Play per visit, often thousands for the same
        // track. Resolve each identity once while retaining the previous last-row-wins
        // behaviour independently for native sources and display hints.
        Map<String,Map<String,Play>> nativeTracks=new LinkedHashMap<>();
        Map<String,String> normalizedTitles=new HashMap<>();
        Map<String,Play> displayTracks=new LinkedHashMap<>();
        for(Play p:plays){
            if(!p.getImported()&&knownArtist(p.getArtist())&&!p.getTitle().equals("未知歌曲")){
                String title=normalizedTitles.computeIfAbsent(p.getTitle(),AppleTrackImages::normalized);
                nativeTracks.computeIfAbsent(title,k->new LinkedHashMap<>()).put(p.getId(),p);
            }
            if(!p.getTitle().isBlank()&&!p.getTitle().equals("未知歌曲")&&(knownArtist(p.getArtist())||p.getDuration()>0))displayTracks.put(p.getId(),p);
        }
        for(Map.Entry<String,Map<String,Play>> entry:nativeTracks.entrySet()){
            Map<String,Hint> bucket=nativeByTitle.computeIfAbsent(entry.getKey(),k->new ConcurrentHashMap<>());
            for(Play p:entry.getValue().values())bucket.put(p.getId(),hint(p));
        }
        for(Play p:displayTracks.values())hints.put(p.getId(),hint(p));
    }
    Hint hint(String id){return hints.get(id);}
    boolean missingDetails(String id){Hint h=hints.get(id);return h!=null&&(!knownArtist(h.artist)||h.album.isBlank());}
    static String displayName(String value){return HAN.get().transliterate(value);}
    boolean knows(String id){return hints.containsKey(id);}
    private static final ThreadLocal<android.icu.text.Transliterator> HAN=ThreadLocal.withInitial(()->android.icu.text.Transliterator.getInstance("Traditional-Simplified"));
    String artist(String id){Hint h=hints.get(id);return h==null?"":h.artist;}
    static String normalized(String s){return HAN.get().transliterate(Normalizer.normalize(s,Normalizer.Form.NFKC)).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+","");}
    static String albumName(String s){return normalized(s.replaceFirst("(?i)\\s*[-–—]\\s*(single|ep)\\s*$",""));}
    static boolean artistMatches(String recorded,String found){
        if(normalized(recorded).equals(normalized(found)))return true;
        for(String part:recorded.split("\\s*(?:&|、|;| feat\\. | featuring )\\s*"))if(normalized(part).equals(normalized(found)))return true;
        return false;
    }
    static Set<String> edition(String value){
        String name=normalized(value);Set<String> result=new HashSet<>();
        for(String token:new String[]{"live","remaster","instrumental","acoustic","伴奏","现场"})if(name.contains(token))result.add(token);
        return result;
    }
    static String imageIdentity(String url){
        try{return new java.net.URI(url).getPath().replaceFirst("/[0-9]+x[0-9]+[^/]*$","");}catch(Exception e){return url;}
    }
    static JSONObject match(Hint h,JSONArray rows)throws JSONException {
        List<JSONObject> exact=new ArrayList<>(),fallback=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(!row.optString("trackId").matches("[0-9]{5,22}")||!normalized(h.title).equals(normalized(row.optString("trackName")))||(knownArtist(h.artist)&&!artistMatches(h.artist,row.optString("artistName")))||!DiaryArtwork.allowed(row.optString("artworkUrl100")))continue;
            long duration=row.optLong("trackTimeMillis"),delta=Math.abs(duration-h.duration);
            if(h.duration>0&&(duration<=0||delta>(knownArtist(h.artist)?10000:5000)))continue;
            if(h.album.isBlank()||albumName(h.album).equals(albumName(row.optString("collectionName"))))exact.add(row);
            else if(knownArtist(h.artist)&&h.duration>0&&duration>0&&delta<=5000&&edition(h.album).equals(edition(row.optString("collectionName"))))fallback.add(row);
        }
        List<JSONObject> candidates=exact.isEmpty()?fallback:exact;
        if(candidates.isEmpty())return null;
        Set<String> artists=new HashSet<>(),images=new HashSet<>();
        for(JSONObject row:candidates){artists.add(normalized(row.optString("artistName")));images.add(imageIdentity(row.optString("artworkUrl100")));}
        if(!knownArtist(h.artist)&&(h.duration<=0||artists.size()!=1))return null;
        if(!knownArtist(h.artist)&&artists.size()!=1)return null;
        JSONObject best=candidates.get(0);
        for(JSONObject row:candidates)if(h.duration>0&&Math.abs(row.optLong("trackTimeMillis")-h.duration)<Math.abs(best.optLong("trackTimeMillis")-h.duration))best=row;
        return best;
    }
    JSONObject resolve(String id,DiaryArtwork.Fetcher fetcher,AppleArtistImages.Gate gate)throws Exception {
        Hint hint=hints.get(id);if(hint==null)return null;
        String term=URLEncoder.encode(hint.title+(knownArtist(hint.artist)?" "+hint.artist:""),StandardCharsets.UTF_8.name());
        for(String country:new String[]{"hk","cn","jp","us","tw"}){
            gate.await();
            try{
                JSONObject response=new JSONObject(new String(fetcher.read("https://itunes.apple.com/search?term="+term+"&entity=song&limit=50&country="+country,2*1024*1024),StandardCharsets.UTF_8));
                JSONArray rows=response.optJSONArray("results");if(rows==null)continue;
                JSONObject selected=match(hint,rows);if(selected!=null)return selected;
            }catch(IOException ignored){}
        };return null;
    }
}
