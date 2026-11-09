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

/** Independent disk, batched catalog and image lanes. A failed request never clears a good bitmap. */
final class DiaryArtwork implements AutoCloseable {
    interface Fetcher { byte[] read(String url,int limit)throws Exception; }
    private final File directory;
    private final TrackMetadata metadata;
    private volatile Runnable metadataListener;
    private final Runnable metadataDelivery=()->{Runnable callback=metadataListener;if(!this.closed&&callback!=null)callback.run();};
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService disk=Executors.newSingleThreadExecutor(factory("DiaryDisk"));
    private final ScheduledExecutorService catalog=Executors.newSingleThreadScheduledExecutor(factory("DiaryCatalog"));
    private final ScheduledExecutorService identities=Executors.newScheduledThreadPool(2,factory("DiaryTrackIdentity"));
    private final ScheduledExecutorService artists=Executors.newScheduledThreadPool(3,factory("DiaryArtists"));
    private final ScheduledExecutorService downloads=Executors.newScheduledThreadPool(3,factory("DiaryImages"));
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(64*1024*1024){protected int sizeOf(String k,Bitmap b){return b.getAllocationByteCount();}};
    private final Map<String,Request> requests=new HashMap<>();
    private final LinkedHashSet<String> wanted=new LinkedHashSet<>();
    private final Set<String> resolving=new HashSet<>();
    private volatile Set<String> focused=Collections.emptySet();
    private final Map<String,AutoCloseable> warmupHandles=new ConcurrentHashMap<>();
    private final Set<String> identityWork=ConcurrentHashMap.newKeySet();
    private final Set<String> warming=ConcurrentHashMap.newKeySet();
    private final AppleTrackImages trackImages=new AppleTrackImages();
    private final Set<String> artistWarmups=ConcurrentHashMap.newKeySet();
    private final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();
    private final Map<String,String> urls=new ConcurrentHashMap<>();
    private final Map<String,Boolean> transientMisses=new ConcurrentHashMap<>();
    private final Map<String,Long> missed=new ConcurrentHashMap<>();
    private final Fetcher fetcher;private final long spacing;private final AppleArtistImages appleArtists;
    private boolean scheduled;private long nextCatalog,nextArtist;
    private volatile boolean closed,network=true,active;
    private volatile boolean artistNetwork=true;
    private static final class Listener {final int edge;final Consumer<Bitmap> callback;boolean cancelled;Listener(int edge,Consumer<Bitmap> callback){this.edge=edge;this.callback=callback;}}
    private static final class Request {final List<Listener> listeners=new ArrayList<>();boolean fetching;int failures;}
    private static ThreadFactory factory(String name){return r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;};}
    DiaryArtwork(File directory){this(directory,null,3100);}
    DiaryArtwork(File directory,Fetcher fetcher,long spacing){this.directory=directory;metadata=TrackMetadata.shared(directory);directory.mkdirs();appleArtists=new AppleArtistImages(directory);this.fetcher=fetcher==null?this::read:fetcher;this.spacing=spacing;}
    void artistNetwork(boolean on){artistNetwork=on;if(on){missed.keySet().removeIf(id->id.startsWith("artist:"));resume();}}
    void active(boolean on){active=on;if(!on)disconnect();else resume();}
    void network(boolean on){network=on;if(!on)disconnect();else{missed.clear();resume();}}
    private void disconnect(){for(HttpURLConnection c:connections)c.disconnect();}
    private void resume(){List<String> ids;synchronized(requests){ids=new ArrayList<>(requests.keySet());}for(String id:ids)disk.execute(()->local(id));}
    private String diskKey(String id){return ArtworkKeys.hash(id.startsWith("artist:")?"apple-artist-v1:"+id:id);}
    private File file(String id){return new File(directory,diskKey(id)+".jpg");}
    /** Export owns this bitmap. Read existing artwork only, without network or UI-cache side effects. */
    Bitmap exportCached(String id,int edge){
        File local=file(id);
        if(!local.isFile()){String source=trackImages.localSource(id,key->file(key).isFile());if(source!=null)local=file(source);}
        return local.isFile()?decode(local,edge):null;
    }
    void focusTracks(Collection<Play> plays){
        Set<String> next=new HashSet<>();for(Play p:plays)next.add(p.getId());focused=next;
        if(active&&network&&!closed)for(Play p:plays)if(trackImages.missingDetails(p.getId())&&metadata.lookup(p)==null)resolveTrack(p.getId());
        synchronized(wanted){wanted.removeIf(id->!desired(id));}
    }
    private boolean subscribed(String id){synchronized(requests){Request r=requests.get(id);return r!=null&&!r.listeners.isEmpty();}}
    private boolean desired(String id){return focused.contains(id)||subscribed(id);}
    void trackHints(Collection<Play> plays){
        Set<String> fresh=new HashSet<>();for(Play p:plays)if(!trackImages.knows(p.getId()))fresh.add(p.getId());trackImages.bind(plays);
        for(String id:fresh)if(trackImages.knows(id)&&missed.remove(id)!=null)disk.execute(()->local(id));
        resume();
    }
    void onMetadataChanged(Runnable listener){metadataListener=listener;}
    private void acceptMetadata(String id,JSONObject row){AppleTrackImages.Hint hint=trackImages.hint(id);if(hint!=null&&metadata.put(id,hint,row)){main.removeCallbacks(metadataDelivery);main.postDelayed(metadataDelivery,500);}}
    private final Map<String,List<String>> artistBindings=new HashMap<>();
    void artistTracks(Map<String,List<String>> bindings){
        Map<String,List<String>> resolved=new HashMap<>();JSONArray facts=new JSONArray();
        for(Map.Entry<String,List<String>> entry:bindings.entrySet()){
            List<String> ids=new ArrayList<>();for(String id:entry.getValue()){
                JSONArray rows=metadata.catalog(Collections.singletonList(id));if(rows.length()>0){JSONObject row=rows.optJSONObject(0);String actual=row.optString("catalogTrackId");if(actual.matches("[0-9]{5,22}")){ids.add(actual);try{facts.put(new JSONObject(row.toString()).put("trackId",actual));}catch(JSONException ignored){}}}
                else if(id.matches("[0-9]{5,22}"))ids.add(id);
            };resolved.put(entry.getKey(),ids);
            List<String> old; synchronized(artistBindings){old=artistBindings.put(entry.getKey(),ids);}if(!ids.equals(old))missed.remove("artist:"+entry.getKey());
        }
        appleArtists.bind(resolved);try{appleArtists.acceptCatalog(new JSONObject().put("results",facts));}catch(JSONException ignored){}
    }
    void prefetchArtists(Collection<String> names){
        Set<String> keep=new HashSet<>();for(String name:names){if(keep.size()>=10)break;keep.add("artist:"+name);}
        for(String old:new ArrayList<>(warmupHandles.keySet()))if(!keep.contains(old)){AutoCloseable handle=warmupHandles.remove(old);artistWarmups.remove(old);try{if(handle!=null)handle.close();}catch(Exception ignored){}}
        if(closed||!network||!active||!artistNetwork)return;
        for(String id:keep){if(file(id).isFile()||peek(id,160)!=null||System.currentTimeMillis()<missed.getOrDefault(id,0L)||!artistWarmups.add(id))continue;AutoCloseable handle=load(id,160,b->{artistWarmups.remove(id);warmupHandles.remove(id);});warmupHandles.put(id,handle);}
    }
    /** Warm only existing local files and keep the normal bounded bitmap cache. */
    void warmCached(Collection<String> ids,int edge){
        if(closed||!active)return;
        int size=bucket(edge);int count=0;
        for(String id:new LinkedHashSet<>(ids)){
            String key=id+":"+size;
            if(cache.get(key)!=null||!warming.add(key))continue;
            if(count++>=18){warming.remove(key);break;}
            try { disk.execute(()->{
                try {
                    if(closed||!active||cache.get(key)!=null)return;
                    File local=file(id);if(!local.isFile())return;
                    Bitmap bitmap=decode(local,size);if(bitmap!=null&&!closed)cache.put(key,bitmap);
                } finally {warming.remove(key);}
            }); } catch(RejectedExecutionException e){warming.remove(key);}
        }
    }
    private static int bucket(int edge){return edge<=160?160:edge<=320?320:edge<=720?720:1200;}
    Bitmap peek(String id,int edge){int target=bucket(edge);Bitmap fallback=null;for(int size:new int[]{160,320,720,1200}){Bitmap b=cache.get(id+":"+size);if(b!=null&&!b.isRecycled()){if(size>=target)return b;fallback=b;}}return fallback;}
    AutoCloseable load(String id,Consumer<Bitmap> ready){return load(id,720,ready);}
    AutoCloseable load(String id,int edge,Consumer<Bitmap> ready){
        if(closed||id==null||id.isEmpty())return ()->{};
        int size=bucket(edge);Bitmap current=peek(id,size);if(current!=null){ready.accept(current);if(cache.get(id+":"+size)!=null||Math.max(current.getWidth(),current.getHeight())>=size)return ()->{};}
        Listener listener=new Listener(size,ready);
        synchronized(requests){Request request=requests.get(id);if(request==null){request=new Request();requests.put(id,request);}request.listeners.add(listener);}
        // Recheck disk on every subscriber: a native cover may have arrived during an online attempt.
        disk.execute(()->local(id));
        return ()->{synchronized(requests){listener.cancelled=true;Request request=requests.get(id);if(request!=null)request.listeners.remove(listener);}};
    }
    private void local(String id){if(closed)return;File file=file(id);
        if(!file.isFile()){
            String source=trackImages.localSource(id,key->file(key).isFile());
            if(source!=null){File nativeFile=file(source);if(nativeFile.isFile())try{java.nio.file.Files.copy(nativeFile.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(IOException ignored){}}
        }
if(file.isFile()&&publish(id,file))return;if(!network||!active||(id.startsWith("artist:")&&!artistNetwork))return;
        synchronized(requests){Request request=requests.get(id);if(request==null||request.fetching||request.listeners.isEmpty())return;if(System.currentTimeMillis()<missed.getOrDefault(id,0L))return;request.fetching=true;}
        String saved=urls.get(id);if(saved==null){File urlFile=new File(directory,diskKey(id)+".url");try{if(urlFile.isFile()&&urlFile.length()<4096){String value=new String(java.nio.file.Files.readAllBytes(urlFile.toPath()),StandardCharsets.UTF_8);if(allowed(value)){saved=value;urls.put(id,saved);}}}catch(Exception ignored){}}
        if(saved!=null){download(id,saved);return;}
        if(id.startsWith("artist:")){artists.execute(()->artist(id));return;}
        if(!id.matches("[0-9]{5,22}")){if(trackImages.knows(id))resolveTrack(id);else failed(id,false);return;}
        queue(Collections.singletonList(id));
    }
    /** Resolve visible and nearby IDs together; cache URLs, never download an entire year of images. */
    void prefetch(Collection<String> ids){if(closed||!network||!active)return;List<String> fresh=new ArrayList<>();for(String id:ids){if(fresh.size()>=80)break;if(id.matches("[0-9]{5,22}")&&!file(id).isFile()&&!urls.containsKey(id)&&System.currentTimeMillis()>=missed.getOrDefault(id,0L))fresh.add(id);}queue(fresh,false);}
    private void queue(Collection<String> ids){queue(ids,true);}
    private void queue(Collection<String> ids,boolean urgent){synchronized(wanted){
        LinkedHashSet<String> additions=new LinkedHashSet<>();for(String id:ids)if(!urls.containsKey(id)&&!resolving.contains(id))additions.add(id);
        if(urgent){additions.addAll(wanted);wanted.clear();wanted.addAll(additions);}else wanted.addAll(additions);
        if(!scheduled&&!wanted.isEmpty()&&!closed){scheduled=true;catalog.schedule(this::lookup,120,TimeUnit.MILLISECONDS);}
    }}

    private void lookup(){
        List<String> batch=new ArrayList<>();synchronized(wanted){scheduled=false;Iterator<String> it=wanted.iterator();while(it.hasNext()&&batch.size()<20){String id=it.next();it.remove();if(!focused.isEmpty()&&!desired(id))continue;batch.add(id);resolving.add(id);}}
        if(!active||!network||closed){for(String id:batch)failed(id,true);synchronized(wanted){resolving.removeAll(batch);}return;}
        Set<String> remaining=new LinkedHashSet<>(batch);boolean anyResponse=false;
        for(String country:new String[]{"hk","jp","us","cn"}){
            if(remaining.isEmpty()||!active||!network||closed)break;
            try{
                pace(false);String joined=String.join(",",remaining);JSONObject json=new JSONObject(new String(fetcher.read("https://itunes.apple.com/lookup?id="+joined+"&country="+country,1024*1024),StandardCharsets.UTF_8));anyResponse=true;appleArtists.acceptCatalog(json);
                JSONArray array=json.optJSONArray("results");if(array!=null)for(int i=0;i<array.length();i++){JSONObject item=array.getJSONObject(i);String id=String.valueOf(item.optLong("trackId"));String url=item.optString("artworkUrl100").replace("100x100bb","1000x1000bb");if(remaining.contains(id)&&allowed(url)){acceptMetadata(id,item);rememberUrl(id,url);remaining.remove(id);boolean needed;synchronized(requests){Request r=requests.get(id);needed=r!=null&&!r.listeners.isEmpty();}if(needed)download(id,url);}}
            }catch(Exception e){android.util.Log.w("DiaryArtwork","Catalog "+country+": "+e.getClass().getSimpleName()+" "+e.getMessage());}
        }
        for(String id:remaining){if(desired(id)&&trackImages.knows(id))resolveTrack(id);else failed(id,!anyResponse);}
        synchronized(wanted){resolving.removeAll(batch);if(!wanted.isEmpty()&&!scheduled&&!closed){scheduled=true;catalog.schedule(this::lookup,120,TimeUnit.MILLISECONDS);}}
    }
    JSONArray summaryMetadata(Collection<String> ids,java.util.function.BooleanSupplier wanted){
        JSONArray cached=CatalogMetadata.cached(directory,ids);Set<String> remaining=new LinkedHashSet<>();for(String id:ids)if(id.matches("[0-9]{5,22}"))remaining.add(id);
        for(int i=0;i<cached.length();i++){JSONObject row=cached.optJSONObject(i);if(row!=null)remaining.remove(row.optString("trackId"));}
        if(remaining.size()>20)return cached;
        for(String country:new String[]{"hk","jp","us","cn"}){
            if(remaining.isEmpty()||closed||!active||!network||!wanted.getAsBoolean())break;
            try{pace(false);if(!wanted.getAsBoolean())break;JSONObject response=new JSONObject(new String(fetcher.read("https://itunes.apple.com/lookup?id="+String.join(",",remaining)+"&country="+country,1024*1024),StandardCharsets.UTF_8));appleArtists.acceptCatalog(response);JSONArray rows=response.optJSONArray("results");if(rows!=null)for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.getJSONObject(i);String id=row.optString("trackId");if(!remaining.contains(id))continue;
                    String url=row.optString("artworkUrl100").replace("100x100bb","1000x1000bb");if(allowed(url)){acceptMetadata(id,row);rememberUrl(id,url);boolean needed;synchronized(requests){Request request=requests.get(id);needed=request!=null&&!request.listeners.isEmpty();}if(needed)download(id,url);}
                    remaining.remove(id);
                }}catch(Exception ignored){}
        }
        return CatalogMetadata.cached(directory,ids);
    }
    JSONObject albumCatalogRequest(String url,java.util.function.BooleanSupplier wanted)throws Exception{
        URI uri=new URI(url);
        if(!"https".equals(uri.getScheme())||!"itunes.apple.com".equals(uri.getHost())||!("/lookup".equals(uri.getPath())||"/search".equals(uri.getPath())))throw new IOException("Invalid catalog URL");
        if(closed||!active||!network||!wanted.getAsBoolean())throw new IOException("Catalog no longer visible");
        pace(false);
        if(closed||!active||!network||!wanted.getAsBoolean())throw new IOException("Catalog no longer visible");
        return new JSONObject(new String(fetcher.read(url,2*1024*1024),StandardCharsets.UTF_8));
    }
    private void resolveTrack(String id){
        if(!identityWork.add(id))return;
        identities.execute(()->{try{
            if(!desired(id))return;
            if(!active||!network||closed)throw new IOException();
            AppleTrackImages.Hint hint=trackImages.hint(id);JSONObject row=hint==null?null:metadata.lookup(id,hint);if(row==null)row=trackImages.resolve(id,fetcher,()->{if(!active||!network||closed||!desired(id))throw new IOException("Request no longer visible");pace(true);});
            if(row==null){android.util.Log.i("DiaryArtwork","Identity unmatched "+id);failed(id,false);return;}
            android.util.Log.i("DiaryArtwork","Identity resolved "+id+" -> "+row.optString("trackId"));
            acceptMetadata(id,row);JSONObject response=new JSONObject().put("results",new JSONArray().put(row));
            if(AppleTrackImages.knownArtist(trackImages.artist(id)))appleArtists.bind(Collections.singletonMap(trackImages.artist(id),Collections.singletonList(row.optString("trackId"))));appleArtists.acceptCatalog(response);
            String url=row.optString("artworkUrl100").replace("100x100bb","1000x1000bb");rememberUrl(id,url);if(!file(id).isFile())download(id,url);
        }catch(Exception e){if(desired(id)){android.util.Log.w("DiaryArtwork","Track identity: "+e.getClass().getSimpleName()+" "+e.getMessage());failed(id,true);}}finally{identityWork.remove(id);synchronized(requests){Request r=requests.get(id);if(r!=null&&!desired(id))r.fetching=false;}}});
    }
    private void rememberUrl(String id,String url){urls.put(id,url);try{File path=new File(directory,diskKey(id)+".url");java.nio.file.Files.write(path.toPath(),url.getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}}
    private synchronized void pace(boolean artist)throws InterruptedException{long next=artist?nextArtist:nextCatalog;long wait=next-SystemClock.elapsedRealtime();if(wait>0)Thread.sleep(wait);if(artist)nextArtist=SystemClock.elapsedRealtime()+spacing;else nextCatalog=SystemClock.elapsedRealtime()+spacing;}
    private void artist(String id){try{if(!active||!network||!artistNetwork)throw new IOException();String url=appleArtists.resolve(id.substring(7),(urlValue,limit)->{if(!subscribed(id))throw new IOException("Artist no longer visible");return fetcher.read(urlValue,limit);},()->{if(!artistNetwork||!subscribed(id))throw new IOException();pace(false);});if(url.isEmpty()){failed(id,false);return;}rememberUrl(id,url);download(id,url);}catch(Exception e){failed(id,true);}}

    private void download(String id,String url){if(closed)return;downloads.execute(()->{
        try{
            if(!active||!network||closed||(id.startsWith("artist:")&&!artistNetwork))throw new IOException();byte[] bytes=fetcher.read(url,6*1024*1024);BitmapFactory.Options info=new BitmapFactory.Options();info.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,info);if(info.outWidth<=0||info.outHeight<=0||info.outWidth>16000||info.outHeight>16000)throw new IOException("Invalid image");
            File target=file(id),temp=File.createTempFile("image-",".tmp",directory);try{java.nio.file.Files.write(temp.toPath(),bytes);java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}finally{temp.delete();}
            android.util.Log.i("DiaryArtwork","Image saved "+id);disk.execute(()->{publish(id,target);prune();});
        }catch(Exception e){android.util.Log.w("DiaryArtwork","Image "+id+": "+e.getClass().getSimpleName()+" "+e.getMessage());failed(id,true);}
    });}
    private Bitmap decode(File file,int edge){BitmapFactory.Options info=new BitmapFactory.Options();info.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),info);if(info.outWidth<1||info.outHeight<1)return null;BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(info.outWidth,info.outHeight)/(options.inSampleSize*2)>=edge)options.inSampleSize*=2;Bitmap original=BitmapFactory.decodeFile(file.getPath(),options);if(original==null)return null;int max=Math.max(original.getWidth(),original.getHeight());if(max<=edge){original.prepareToDraw();return original;}Bitmap scaled=Bitmap.createScaledBitmap(original,Math.max(1,original.getWidth()*edge/max),Math.max(1,original.getHeight()*edge/max),true);if(scaled!=original)original.recycle();scaled.prepareToDraw();return scaled;}
    private boolean publish(String id,File file){
        Map<Integer,Bitmap> decoded=new HashMap<>();List<Listener> listeners;
        while(true){
            synchronized(requests){Request request=requests.get(id);if(request==null)return true;listeners=new ArrayList<>(request.listeners);}
            for(Listener listener:listeners){if(listener.cancelled||decoded.containsKey(listener.edge))continue;Bitmap image=cache.get(id+":"+listener.edge);if(image==null)image=decode(file,listener.edge);if(image==null)return false;decoded.put(listener.edge,image);cache.put(id+":"+listener.edge,image);}
            synchronized(requests){Request request=requests.get(id);if(request==null)return true;boolean missing=false;for(Listener listener:request.listeners)if(!listener.cancelled&&!decoded.containsKey(listener.edge))missing=true;if(missing)continue;listeners=new ArrayList<>(request.listeners);requests.remove(id);break;}
        }
        file.setLastModified(System.currentTimeMillis());missed.remove(id);final List<Listener> delivery=listeners;
        main.post(()->{if(!closed)for(Listener listener:delivery){Bitmap image=decoded.get(listener.edge);if(!listener.cancelled&&image!=null)listener.callback.accept(image);}});return true;
    }

    private void failed(String id,boolean transientFailure){
        artistWarmups.remove(id);
        synchronized(requests){Request r=requests.get(id);if(r!=null){r.fetching=false;if(transientFailure&&active&&network&&!closed&&!r.listeners.isEmpty()&&r.failures++<2){downloads.schedule(()->disk.execute(()->local(id)),r.failures==1?1500:5000,TimeUnit.MILLISECONDS);return;}if(!active||!network)return;}}
        transientMisses.put(id,transientFailure);long pause=transientFailure?30000:300000;missed.put(id,System.currentTimeMillis()+pause);
        if(!closed&&(id.matches("[0-9]{5,22}")||trackImages.knows(id)))downloads.schedule(()->{
            if(closed||!active||!network)return;
            synchronized(requests){Request pending=requests.get(id);if(pending==null||pending.listeners.isEmpty())return;pending.failures=0;}
            missed.remove(id);disk.execute(()->local(id));
        },pause+100,TimeUnit.MILLISECONDS);
    }
    static boolean allowed(String raw){try{URI u=new URI(raw);String h=u.getHost();return "https".equals(u.getScheme())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&h!=null&&(h.equals("itunes.apple.com")||h.equals("music.apple.com")||h.endsWith(".mzstatic.com"));}catch(Exception e){return false;}}
    private byte[] read(String raw,int limit)throws Exception{for(int redirect=0;redirect<4;redirect++){if(closed||!active||!network||!allowed(raw))throw new IOException("Inactive or untrusted URL");HttpURLConnection c=(HttpURLConnection)new URL(raw).openConnection();connections.add(c);c.setConnectTimeout(4500);c.setReadTimeout(6000);c.setInstanceFollowRedirects(false);boolean artistPage=new URI(raw).getHost().equals("music.apple.com")&&new URI(raw).getPath().contains("/artist/");c.setRequestProperty("User-Agent",artistPage?"Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36":"ListeningDiary/0.3 artwork");if(artistPage)c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9,en;q=0.8");try{int code=c.getResponseCode();if(code>=300&&code<400){raw=new URL(new URL(raw),c.getHeaderField("Location")).toString();continue;}if(code!=200)throw new IOException("Image HTTP "+code);try(InputStream input=c.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int count;while((count=input.read(buf))!=-1){if(closed||!active||!network)throw new IOException("Inactive");if(output.size()+count>limit){if(!artistPage)throw new IOException("Image budget");output.write(buf,0,limit-output.size());return output.toByteArray();}output.write(buf,0,count);}return output.toByteArray();}}finally{c.disconnect();connections.remove(c);}}throw new IOException("Redirect budget");}
    private void prune(){File[] files=directory.listFiles((d,n)->n.endsWith(".jpg"));if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long size=0;for(File f:files)size+=f.length();for(int i=0;i<files.length&&(size>100*1024*1024L||files.length-i>500);i++){long n=files[i].length();if(files[i].delete()){size-=n;new File(directory,files[i].getName().replace(".jpg",".url")).delete();}}}
    String phase(String id){
        if(!network)return "联网补全已关闭";
        if(!active)return "等待补全";
        if(System.currentTimeMillis()<missed.getOrDefault(id,0L))return transientMisses.getOrDefault(id,false)?"稍后重试":"暂未匹配到封面";
        if(!id.startsWith("artist:")&&!id.matches("[0-9]{5,22}")&&!trackImages.knows(id))return "原记录资料不足";
        if(identityWork.contains(id))return "正在核对资料";
        return urls.containsKey(id)?"正在加载图片":"正在补全图片";
    }
    void clear(){cache.evictAll();missed.clear();}
    public void close(){closed=true;active=false;disconnect();disk.shutdownNow();catalog.shutdownNow();identities.shutdownNow();artists.shutdownNow();downloads.shutdownNow();main.removeCallbacksAndMessages(null);synchronized(requests){requests.clear();}synchronized(wanted){wanted.clear();}cache.evictAll();}
}
