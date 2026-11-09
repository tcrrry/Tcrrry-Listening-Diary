package com.tcrrry.diary;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import org.json.*;

/** Small ID-anchored catalog cache; listening records never leave the device. */
final class CatalogMetadata {
    static void accept(File directory,JSONObject response){
        File folder=new File(directory,"catalog-facts");folder.mkdirs();JSONArray rows=response.optJSONArray("results");if(rows==null)return;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;String id=row.optString("trackId");if(!id.matches("[0-9]{5,22}")||(!row.has("collectionId")&&!row.has("primaryGenreName")))continue;
            JSONObject safe=new JSONObject();File target=new File(folder,id+".json");
            try{if(target.isFile()&&target.length()<12000)safe=new JSONObject(new String(java.nio.file.Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){}
            if(!safe.optString("collectionId").isEmpty()&&row.has("collectionId")&&!safe.optString("collectionId").equals(row.optString("collectionId")))safe=new JSONObject();
            for(String key:new String[]{"trackId","collectionId","collectionName","artistName","trackCount","discCount","primaryGenreName"})try{if(row.has(key)&&!row.isNull(key)&&!row.optString(key).isEmpty())safe.put(key,row.opt(key));}catch(JSONException ignored){}
            File temp=null;try{temp=File.createTempFile("catalog-",".tmp",folder);java.nio.file.Files.write(temp.toPath(),safe.toString().getBytes(StandardCharsets.UTF_8));java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(IOException ignored){}finally{if(temp!=null)temp.delete();}
        }
        File[] files=folder.listFiles((d,n)->n.endsWith(".json"));if(files!=null&&files.length>2000){Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(int i=0;i<files.length-2000;i++)files[i].delete();}
    }
    static JSONArray cached(File directory,Collection<String> ids){
        JSONArray result=new JSONArray();Set<String> matched=new HashSet<>();
        JSONArray overlay=TrackMetadata.shared(directory).catalog(ids);for(int i=0;i<overlay.length();i++){JSONObject row=overlay.optJSONObject(i);if(row!=null){result.put(row);matched.add(row.optString("trackId"));}}
        File folder=new File(directory,"catalog-facts");for(String id:ids){if(matched.contains(id)||!id.matches("[0-9]{5,22}"))continue;File file=new File(folder,id+".json");try{if(file.isFile()&&file.length()<12000)result.put(new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)));}catch(Exception ignored){}}return result;
    }
}
