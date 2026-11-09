package com.tcrrry.diary;
import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Public artwork requests only. No listening timestamps, tokens or history uploads. */
final class DiaryArtwork implements AutoCloseable {
    private final File directory;private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"DiaryArtwork");t.setDaemon(true);return t;});
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(20*1024*1024){protected int sizeOf(String k,Bitmap b){return b.getAllocationByteCount();}};
    private final Map<String,List<Consumer<Bitmap>>> pending=new HashMap<>();private final Map<String,Long> missed=new ConcurrentHashMap<>();
    private volatile boolean closed,network=true,active;private volatile HttpURLConnection connection;private long nextRequest;
    DiaryArtwork(File directory){this.directory=directory;directory.mkdirs();}
    void active(boolean active){this.active=active;if(!active&&connection!=null)connection.disconnect();}
    void network(boolean allow){network=allow;if(!allow&&connection!=null)connection.disconnect();}
    void load(String id,Consumer<Bitmap> ready){if(closed||id==null||id.isEmpty())return;String key=DiaryPlugin.artKey(id);Bitmap value=cache.get(key);if(value!=null){main.post(()->ready.accept(value));return;}
        synchronized(pending){if(pending.containsKey(key)){pending.get(key).add(ready);return;}if(pending.size()>=12){main.post(()->ready.accept(null));return;}pending.put(key,new ArrayList<>(Arrays.asList(ready)));}
        io.execute(()->{Bitmap image=null;try{File file=new File(directory,key+".jpg");if(file.isFile()){file.setLastModified(System.currentTimeMillis());image=BitmapFactory.decodeFile(file.getPath());}
            if(image==null&&network&&active&&!closed&&System.currentTimeMillis()>missed.getOrDefault(key,0L)){
                if(!network||!active||closed)throw new IOException("Inactive");
                String url=id.startsWith("artist:")?artist(id.substring(7)):cover(id);if(!url.isEmpty()){byte[] bytes=read(url,6*1024*1024);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth<16000&&bounds.outHeight<16000){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(bounds.outWidth/options.inSampleSize>1400||bounds.outHeight/options.inSampleSize>1400)options.inSampleSize=options.inSampleSize==0?2:options.inSampleSize*2;image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(image!=null){File temp=new File(directory,key+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){image.compress(Bitmap.CompressFormat.JPEG,88,out);}if(!temp.renameTo(file))temp.delete();prune();}}}
                if(image==null)missed.put(key,System.currentTimeMillis()+900000);
            }
        }catch(Exception e){missed.put(key,System.currentTimeMillis()+900000);}if(image!=null)cache.put(key,image);Bitmap result=image;List<Consumer<Bitmap>> listeners;synchronized(pending){listeners=pending.remove(key);}if(listeners!=null)main.post(()->{if(!closed)for(Consumer<Bitmap> listener:listeners)listener.accept(result);});});
    }
    private String cover(String id)throws Exception{if(!id.matches("[0-9]{5,22}"))return "";for(String country:new String[]{"jp","us","cn"}){JSONObject json=new JSONObject(new String(metadata("https://itunes.apple.com/lookup?id="+id+"&country="+country),StandardCharsets.UTF_8));JSONArray a=json.optJSONArray("results");if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);if(!id.equals(String.valueOf(r.optLong("trackId"))))continue;String u=r.optString("artworkUrl100");if(!u.isEmpty())return u.replace("100x100bb","1000x1000bb");}}return "";}
    private String artist(String name)throws Exception{if(name.isEmpty()||name.length()>180)return "";JSONObject json=new JSONObject(new String(metadata("https://www.theaudiodb.com/api/v1/json/123/search.php?s="+URLEncoder.encode(name,"UTF-8")),StandardCharsets.UTF_8));JSONArray a=json.optJSONArray("artists");if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);if(!name.trim().equalsIgnoreCase(r.optString("strArtist").trim()))continue;for(String k:new String[]{"strArtistFanart","strArtistWideThumb","strArtistThumb"})if(!r.optString(k).isEmpty())return r.optString(k);}return "";}
    private byte[] metadata(String url)throws Exception{long wait=nextRequest-SystemClock.elapsedRealtime();if(wait>0)Thread.sleep(Math.min(wait,3500));nextRequest=SystemClock.elapsedRealtime()+3100;return read(url,512*1024);}
    static boolean allowed(String raw){try{URI u=new URI(raw);String h=u.getHost();return "https".equals(u.getScheme())&&u.getUserInfo()==null&&h!=null&&(h.equals("itunes.apple.com")||h.equals("theaudiodb.com")||h.endsWith(".theaudiodb.com")||h.endsWith(".mzstatic.com"));}catch(Exception e){return false;}}
    private byte[] read(String raw,int limit)throws Exception{for(int redirects=0;redirects<4;redirects++){if(closed||!active||!network||!allowed(raw))throw new IOException("Unavailable");HttpURLConnection c=(HttpURLConnection)new URL(raw).openConnection();connection=c;c.setConnectTimeout(6500);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","ListeningDiary/0.2 artwork");try{int code=c.getResponseCode();if(code>=300&&code<400){raw=new URL(new URL(raw),c.getHeaderField("Location")).toString();continue;}if(code!=200)throw new IOException("Artwork status "+code);try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(closed||!active||!network||out.size()+n>limit)throw new IOException("Artwork budget");out.write(buf,0,n);}return out.toByteArray();}}finally{c.disconnect();connection=null;}}throw new IOException("Redirect limit");}
    private void prune(){File[] files=directory.listFiles((d,n)->n.endsWith(".jpg"));if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long size=0;for(File f:files)size+=f.length();for(int i=0;i<files.length&&(size>100*1024*1024L||files.length-i>500);i++){long n=files[i].length();if(files[i].delete())size-=n;}}
    void clear(){cache.evictAll();missed.clear();}
    public void close(){closed=true;active=false;if(connection!=null)connection.disconnect();io.shutdownNow();cache.evictAll();synchronized(pending){pending.clear();}main.removeCallbacksAndMessages(null);}
}


