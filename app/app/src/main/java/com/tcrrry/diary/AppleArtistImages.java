package com.tcrrry.diary;
import org.json.*;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.regex.*;

/** Artist identity comes from the user's actual catalog tracks, never a free-text name search. */
final class AppleArtistImages {
    interface Gate {void await()throws Exception;}
    private final File directory;private final Map<String,List<String>> tracks=new java.util.concurrent.ConcurrentHashMap<>();
    AppleArtistImages(File directory){this.directory=directory;}
    void bind(Map<String,List<String>> values){values.forEach((name,ids)->{List<String> valid=new ArrayList<>();for(String id:ids)if(id.matches("[0-9]{5,22}")&&!valid.contains(id)&&valid.size()<3)valid.add(id);if(!valid.isEmpty())tracks.put(name,valid);});}
    String resolve(String name,DiaryArtwork.Fetcher fetcher,Gate gate)throws Exception {
        if(name.isEmpty()||name.length()>180)return "";File identity=new File(directory,ArtworkKeys.hash("apple-identity:"+name)+".artist");String artistId="";
        try{if(identity.isFile()&&identity.length()<100)artistId=new String(java.nio.file.Files.readAllBytes(identity.toPath()),StandardCharsets.UTF_8).trim();}catch(Exception ignored){}
        if(!artistId.matches("[0-9]{3,22}"))artistId=resolveIdentity(name,fetcher,gate);
        if(artistId.isEmpty())return "";
        // Use a real localized artist path: the old /artist/_/ID URL redirects many Chinese artists to /cn/new.
        Set<String> tried=new LinkedHashSet<>();
        for(String country:new String[]{"cn","us","jp"}){
            String label=country.equals("cn")?AppleTrackImages.displayName(name):name;
            String slug=URLEncoder.encode(label,StandardCharsets.UTF_8.name()).replace("+","%20");
            String address="https://music.apple.com/"+country+"/artist/"+slug+"/"+artistId;
            if(tried.add(address))try{String image=pageImage(artistId,new String(fetcher.read(address,900000),StandardCharsets.UTF_8));if(!image.isEmpty())return image;}catch(IOException ignored){}
            if(country.equals("cn"))try{
                gate.await();JSONObject canonicalInfo=new JSONObject(new String(fetcher.read("https://itunes.apple.com/lookup?id="+artistId+"&entity=musicArtist&country=cn",300000),StandardCharsets.UTF_8));JSONArray rows=canonicalInfo.optJSONArray("results");
                if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!artistId.equals(row.optString("artistId")))continue;String real=row.optString("artistLinkUrl").split("\\?",2)[0];
                    if(validArtistPage(real,artistId)&&tried.add(real)){String image=pageImage(artistId,new String(fetcher.read(real,900000),StandardCharsets.UTF_8));if(!image.isEmpty())return image;}
                }
            }catch(IOException ignored){}
        };return "";
    }
    static boolean validArtistPage(String raw,String id){try{URI uri=new URI(raw);return DiaryArtwork.allowed(raw)&&"music.apple.com".equals(uri.getHost())&&uri.getPath().matches("/[^/]+/artist/[^/]+/"+Pattern.quote(id)+"/?");}catch(Exception e){return false;}}
    private String cachedIdentity(String name){try{File f=new File(directory,ArtworkKeys.hash("apple-identity:"+name)+".artist");if(f.isFile()&&f.length()<100){String id=new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8).trim();if(id.matches("[0-9]{3,22}"))return id;}}catch(IOException ignored){}return "";}
    /** Share catalog identity work across the requested artist and nearby artists, preserving track anchors. */
    private synchronized String resolveIdentity(String name,DiaryArtwork.Fetcher fetcher,Gate gate)throws Exception{
        String cached=cachedIdentity(name);if(!cached.isEmpty())return cached;
        List<String> own=tracks.get(name);if(own==null||own.isEmpty())return "";
        LinkedHashSet<String> ids=new LinkedHashSet<>(own);
        List<String> names=new ArrayList<>(tracks.keySet());Collections.sort(names);
        for(String other:names){if(ids.size()>=20)break;if(other.equals(name)||!cachedIdentity(other).isEmpty())continue;List<String> bound=tracks.get(other);if(bound!=null&&ids.size()+bound.size()<=20)ids.addAll(bound);}
        Set<String> remaining=new LinkedHashSet<>(ids);
        for(String country:new String[]{"cn","jp","us"}){
            if(remaining.isEmpty())break;
            try{gate.await();JSONObject json=new JSONObject(new String(fetcher.read("https://itunes.apple.com/lookup?id="+String.join(",",remaining)+"&country="+country,1024*1024),StandardCharsets.UTF_8));acceptCatalog(json);
                JSONArray rows=json.optJSONArray("results");if(rows!=null)for(int i=0;i<rows.length();i++)remaining.remove(String.valueOf(rows.getJSONObject(i).optLong("trackId")));
                cached=cachedIdentity(name);if(!cached.isEmpty())return cached;
            }catch(IOException ignored){}
        }
        return cachedIdentity(name);
    }
    void acceptCatalog(JSONObject json)throws JSONException{
        CatalogMetadata.accept(directory,json);
        for(Map.Entry<String,List<String>> entry:tracks.entrySet()){
            String id=identity(json,new HashSet<>(entry.getValue()));if(id.isEmpty())continue;
            try{java.nio.file.Files.write(new File(directory,ArtworkKeys.hash("apple-identity:"+entry.getKey())+".artist").toPath(),id.getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}
        }
    }
    static String identity(JSONObject response,Set<String> requested)throws JSONException {
        Set<String> artists=new HashSet<>();JSONArray a=response.optJSONArray("results");if(a==null)return "";
        for(int i=0;i<a.length();i++){JSONObject item=a.getJSONObject(i);if(!requested.contains(String.valueOf(item.optLong("trackId"))))continue;String id=String.valueOf(item.optLong("artistId"));if(id.matches("[0-9]{3,22}"))artists.add(id);}
        return artists.size()==1?artists.iterator().next():"";
    }
    static String pageImage(String artistId,String html){
        Map<String,String> meta=new HashMap<>();Matcher tags=Pattern.compile("<meta\\b[^>]*>",Pattern.CASE_INSENSITIVE).matcher(html);
        Pattern attributes=Pattern.compile("([a-zA-Z:-]+)\\s*=\\s*([\"'])(.*?)\\2",Pattern.DOTALL);
        while(tags.find()){Map<String,String> fields=new HashMap<>();Matcher attrs=attributes.matcher(tags.group());while(attrs.find())fields.put(attrs.group(1).toLowerCase(Locale.ROOT),attrs.group(3).replace("&amp;","&"));String property=fields.get("property");if(property!=null&&fields.containsKey("content"))meta.put(property,fields.get("content"));}
        try{URI canonical=new URI(meta.getOrDefault("og:url",""));if(!"music.apple.com".equals(canonical.getHost())||!canonical.getPath().matches("/[^/]+/artist/[^/]+/"+Pattern.quote(artistId)+"/?"))return "";String image=meta.getOrDefault("og:image","");URI uri=new URI(image);if(!DiaryArtwork.allowed(image)||uri.getHost()==null||!uri.getHost().endsWith(".mzstatic.com"))return "";return image.replaceFirst("/[0-9]+x[0-9]+[a-zA-Z-]*\\.(png|jpg|jpeg)(\\?.*)?$","/600x600bb.jpg");}catch(Exception e){return "";}
    }
}
