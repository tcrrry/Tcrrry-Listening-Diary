package com.tcrrry.diary;
import android.graphics.*;import android.os.*;import java.io.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DiaryArtworkTest {
    private File dir;private DiaryArtwork artwork;private byte[] jpeg;
    @Before public void setup()throws Exception{dir=new File(RuntimeEnvironment.getApplication().getCacheDir(),"art-"+System.nanoTime());dir.mkdirs();Bitmap image=Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888);image.eraseColor(Color.rgb(166,65,83));ByteArrayOutputStream out=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.JPEG,90,out);jpeg=out.toByteArray();image.recycle();}
    @After public void stop(){if(artwork!=null)artwork.close();}
    private void await(java.util.function.BooleanSupplier ready)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);while(!ready.getAsBoolean()&&System.nanoTime()<deadline){org.robolectric.shadows.ShadowLooper.idleMainLooper();Thread.sleep(10);}assertTrue("Image operation did not complete",ready.getAsBoolean());}
    private String response(String raw)throws Exception{String list=android.net.Uri.parse(raw).getQueryParameter("id");JSONArray results=new JSONArray();for(String id:list.split(","))results.put(new JSONObject().put("trackId",Long.parseLong(id)).put("artworkUrl100","https://is1-ssl.mzstatic.com/"+id+"/100x100bb.jpg"));return new JSONObject().put("results",results).toString();}
    @Test public void nativePlaybackCoverRepairsImportedIdentityOffline()throws Exception{
        String nativeId="native-song",importId="apple:old";
        java.nio.file.Files.write(new File(dir,ArtworkKeys.hash(nativeId)+".jpg").toPath(),jpeg);
        artwork=new DiaryArtwork(dir,(url,max)->{throw new AssertionError("must reuse local cover");},0);
        artwork.network(false);artwork.active(true);
        Play nativePlay=new Play(nativeId,"n","Song","Artist","Album",0,200000,200000,200000,"UTC",1,1,false,"");
        Play imported=new Play(importId,"i","Song","未知歌手","Album",0,200000,200000,200000,"UTC",1,1,true,"");
        artwork.trackHints(Arrays.asList(nativePlay,imported));AtomicBoolean ready=new AtomicBoolean();
        artwork.load(importId,160,b->ready.set(true));await(ready::get);
        assertTrue(new File(dir,ArtworkKeys.hash(importId)+".jpg").isFile());
    }
    @Test public void localPrewarmDecodesWithoutNetwork()throws Exception{
        String id="123456789";try(FileOutputStream out=new FileOutputStream(new File(dir,ArtworkKeys.hash(id)+".jpg"))){out.write(jpeg);}
        AtomicInteger requests=new AtomicInteger();artwork=new DiaryArtwork(dir,(url,max)->{requests.incrementAndGet();throw new IOException("unexpected network");},0);artwork.active(true);
        artwork.warmCached(Arrays.asList(id,"missing-image"),720);await(()->artwork.peek(id,720)!=null);
        assertTrue(artwork.peek(id,720).getWidth()<=720);assertEquals(0,requests.get());
    }
    @Test public void visibleSongsShareOneCatalogBatch()throws Exception{AtomicInteger lookups=new AtomicInteger(),finished=new AtomicInteger();artwork=new DiaryArtwork(dir,(url,max)->{if(url.contains("itunes.apple.com")){lookups.incrementAndGet();return response(url).getBytes(java.nio.charset.StandardCharsets.UTF_8);}return jpeg;},0);artwork.active(true);for(int i=0;i<8;i++)artwork.load("100000"+i,160,b->finished.incrementAndGet());await(()->finished.get()==8);assertEquals(1,lookups.get());assertEquals(160,artwork.peek("1000000",160).getWidth());}
    @Test public void slowNetworkCannotBlockLocalDiskImages()throws Exception{CountDownLatch network=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean local=new AtomicBoolean();artwork=new DiaryArtwork(dir,(url,max)->{network.countDown();release.await(5,TimeUnit.SECONDS);return response(url).getBytes(java.nio.charset.StandardCharsets.UTF_8);},0);artwork.active(true);artwork.load("1000000",160,b->{});assertTrue(network.await(2,TimeUnit.SECONDS));java.nio.file.Files.write(new File(dir,ArtworkKeys.hash("native")+".jpg").toPath(),jpeg);artwork.load("native",160,b->local.set(true));try{await(local::get);}finally{release.countDown();}}
    @Test public void cachedCoverIsImmediateAfterRowDisposal()throws Exception{AtomicInteger calls=new AtomicInteger();artwork=new DiaryArtwork(dir,(u,m)->{calls.incrementAndGet();throw new IOException("offline");},0);java.nio.file.Files.write(new File(dir,ArtworkKeys.hash("native")+".jpg").toPath(),jpeg);AtomicReference<Bitmap> first=new AtomicReference<>();AutoCloseable request=artwork.load("native",720,first::set);await(()->first.get()!=null);request.close();AtomicReference<Bitmap> restored=new AtomicReference<>();artwork.load("native",720,restored::set);assertSame(first.get(),restored.get());assertEquals(0,calls.get());}
    @Test public void cancellationDoesNotDropAnotherSubscriber()throws Exception{AtomicInteger done=new AtomicInteger();artwork=new DiaryArtwork(dir,(url,max)->url.contains("itunes.apple.com")?response(url).getBytes(java.nio.charset.StandardCharsets.UTF_8):jpeg,0);artwork.active(true);AutoCloseable cancelled=artwork.load("1000000",160,b->fail("Disposed callback fired"));cancelled.close();artwork.load("1000000",720,b->done.incrementAndGet());await(()->done.get()==1);assertNotNull(artwork.peek("1000000",720));}
    @Test public void nativeCoverCanArriveDuringAStalledLookup()throws Exception{CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean first=new AtomicBoolean(),second=new AtomicBoolean();artwork=new DiaryArtwork(dir,(url,max)->{entered.countDown();release.await(5,TimeUnit.SECONDS);throw new IOException();},0);artwork.active(true);artwork.load("1000000",720,b->first.set(true));assertTrue(entered.await(2,TimeUnit.SECONDS));java.nio.file.Files.write(new File(dir,ArtworkKeys.hash("1000000")+".jpg").toPath(),jpeg);artwork.load("1000000",160,b->second.set(true));try{await(()->first.get()&&second.get());}finally{release.countDown();}}
    @Test public void malformedRedirectHostsAreRejected(){assertFalse(DiaryArtwork.allowed("https://itunes.apple.com.evil.invalid/image"));assertFalse(DiaryArtwork.allowed("https://is1.mzstatic.com:8443/image"));assertFalse(DiaryArtwork.allowed("http://is1.mzstatic.com/image"));assertTrue(DiaryArtwork.allowed("https://is1-ssl.mzstatic.com/image"));}
    @Test public void oneStorefrontFailureDoesNotHideAnotherStoresCover()throws Exception{AtomicInteger lookups=new AtomicInteger();AtomicBoolean ready=new AtomicBoolean();artwork=new DiaryArtwork(dir,(url,max)->{if(url.contains("itunes.apple.com")){lookups.incrementAndGet();if(url.contains("country=hk"))throw new IOException("regional failure");return response(url).getBytes(java.nio.charset.StandardCharsets.UTF_8);}return jpeg;},0);artwork.active(true);artwork.load("1000000",160,b->ready.set(true));await(ready::get);assertEquals(2,lookups.get());}
    @Test public void artistPipelineIgnoresOldThirdPartyCacheAndResolvesTrackIdentity()throws Exception{
        java.nio.file.Files.write(new File(dir,ArtworkKeys.hash("artist:MIREI")+".jpg").toPath(),jpeg);
        AtomicInteger catalogs=new AtomicInteger(),pages=new AtomicInteger();AtomicBoolean ready=new AtomicBoolean();
        artwork=new DiaryArtwork(dir,(url,max)->{if(url.contains("itunes.apple.com")){catalogs.incrementAndGet();return "{results:[{trackId:1537779205,artistId:882739606}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);}if(url.contains("music.apple.com")){pages.incrementAndGet();return "<meta property='og:url' content='https://music.apple.com/cn/artist/mirei/882739606'><meta property='og:image' content='https://is1-ssl.mzstatic.com/artist/1200x630cw.png'>".getBytes(java.nio.charset.StandardCharsets.UTF_8);}return jpeg;},0);
        artwork.artistTracks(Collections.singletonMap("MIREI",Arrays.asList("1537779205")));artwork.active(true);artwork.load("artist:MIREI",160,b->ready.set(true));await(ready::get);assertEquals(1,catalogs.get());assertEquals(1,pages.get());
        assertTrue(new File(dir,ArtworkKeys.hash("apple-artist-v1:artist:MIREI")+".jpg").isFile());
    }
    @Test public void slowArtistPageDoesNotBlockFollowingArtistAndWarmupsRespectSetting()throws Exception{
        CountDownLatch slow=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean next=new AtomicBoolean();AtomicInteger catalogs=new AtomicInteger();
        artwork=new DiaryArtwork(dir,(url,max)->{if(url.contains("itunes.apple.com")){catalogs.incrementAndGet();return "{results:[{trackId:111111,artistId:777777},{trackId:222222,artistId:888888}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);}if(url.contains("music.apple.com")){String id=url.contains("777777")?"777777":"888888";if(id.equals("777777")){slow.countDown();release.await(5,TimeUnit.SECONDS);}return ("<meta property='og:url' content='https://music.apple.com/cn/artist/name/"+id+"'><meta property='og:image' content='https://is1-ssl.mzstatic.com/artist/1200x630cw.png'>").getBytes(java.nio.charset.StandardCharsets.UTF_8);}return jpeg;},0);
        Map<String,List<String>> bound=new LinkedHashMap<>();bound.put("Slow",Arrays.asList("111111"));bound.put("Next",Arrays.asList("222222"));artwork.artistTracks(bound);artwork.active(true);artwork.artistNetwork(false);artwork.prefetchArtists(bound.keySet());Thread.sleep(100);assertEquals(0,catalogs.get());
        artwork.artistNetwork(true);artwork.load("artist:Slow",160,b->{});assertTrue(slow.await(2,TimeUnit.SECONDS));artwork.load("artist:Next",160,b->next.set(true));try{await(next::get);assertEquals(1,catalogs.get());}finally{release.countDown();}
    }
    @Test public void importedLocalIdentityGetsExactEditionCoverWithoutChangingItsKey()throws Exception{
        String id="apple:local-test";AtomicBoolean ready=new AtomicBoolean();AtomicInteger searches=new AtomicInteger();
        artwork=new DiaryArtwork(dir,(url,max)->{if(url.contains("/search?")){searches.incrementAndGet();return ("{results:[{trackId:667921841,trackName:'單車',artistName:'陳奕迅',collectionName:'2013 陳奕迅 Music Life 精選',trackTimeMillis:208627,artworkUrl100:'https://is1-ssl.mzstatic.com/eason/100x100bb.jpg'},{trackId:542593707,trackName:'單車',artistName:'陳奕迅',collectionName:'Shall We Dance? Shall We Talk!',trackTimeMillis:211251,artworkUrl100:'https://is1-ssl.mzstatic.com/shall/100x100bb.jpg'}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);}return jpeg;},0);
        Play play=new Play(id,"visit","單車","陳奕迅","2013 陳奕迅 Music Life 精選",0,60000,60000,208677,"UTC",1,1,true,"");
        artwork.trackHints(Collections.singletonList(play));artwork.active(true);artwork.load(id,160,b->ready.set(true));await(ready::get);
        assertEquals(1,searches.get());assertNotNull(artwork.peek(id,160));assertEquals(id,play.getId());assertTrue(new File(dir,ArtworkKeys.hash(id)+".jpg").isFile());
    }
    @Test public void trackMatcherRejectsWrongArtistEditionDurationAndAmbiguousCovers()throws Exception{
        AppleTrackImages.Hint hint=new AppleTrackImages.Hint("單車","陳奕迅","2013 陳奕迅 Music Life 精選",208677);
        JSONObject row=new JSONObject("{trackId:667921841,trackName:'單車',artistName:'陳奕迅',collectionName:'2013 陳奕迅 Music Life 精選',trackTimeMillis:208627,artworkUrl100:'https://is1-ssl.mzstatic.com/eason/100x100bb.jpg'}");
        assertNotNull(AppleTrackImages.match(hint,new JSONArray().put(row)));
        assertNotNull(AppleTrackImages.match(hint,new JSONArray().put(new JSONObject(row.toString()).put("trackName","单车").put("artistName","陈奕迅").put("collectionName","2013 陈奕迅 Music Life 精选"))));
        assertNull(AppleTrackImages.match(hint,new JSONArray().put(new JSONObject(row.toString()).put("artistName","Other"))));
        assertNull(AppleTrackImages.match(hint,new JSONArray().put(new JSONObject(row.toString()).put("collectionName","Live"))));
        assertNull(AppleTrackImages.match(hint,new JSONArray().put(new JSONObject(row.toString()).put("trackTimeMillis",400000))));
        assertNotNull(AppleTrackImages.match(hint,new JSONArray().put(row).put(new JSONObject(row.toString()).put("trackId",1234567).put("artworkUrl100","https://is1-ssl.mzstatic.com/other/100x100bb.jpg"))));
    }

    @Test public void missingArtistRequiresExactAlbumTightDurationAndUniquePerformer()throws Exception{
        AppleTrackImages.Hint hint=new AppleTrackImages.Hint("恋爱告急","未知歌手","恋爱告急 - Single",204923);
        JSONObject row=new JSONObject("{trackId:1872948412,trackName:'戀愛告急',artistName:'旺仔小喬',collectionName:'戀愛告急 - Single',trackTimeMillis:204923,artworkUrl100:'https://is1-ssl.mzstatic.com/urgent/100x100bb.jpg'}");
        assertNotNull(AppleTrackImages.match(hint,new JSONArray().put(row)));
        assertNull(AppleTrackImages.match(hint,new JSONArray().put(new JSONObject(row.toString()).put("trackTimeMillis",220000))));
        assertNull(AppleTrackImages.match(hint,new JSONArray().put(row).put(new JSONObject(row.toString()).put("artistName","另一歌手"))));
        assertNotNull(AppleTrackImages.match(new AppleTrackImages.Hint("恋爱告急","未知歌手","",204923),new JSONArray().put(row)));
    }

    @Test public void obsoleteQueuedTrackSearchCannotDelayCurrentDate()throws Exception{
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger obsolete=new AtomicInteger();AtomicBoolean ready=new AtomicBoolean();
        artwork=new DiaryArtwork(dir,(url,max)->{
            if(!url.contains("/search?"))return jpeg;
            String title=android.net.Uri.parse(url).getQueryParameter("term").split(" ")[0];
            if(title.equals("Old0")||title.equals("Old1")){entered.countDown();release.await(5,TimeUnit.SECONDS);}
            if(title.equals("Obsolete"))obsolete.incrementAndGet();
            return ("{results:[{trackId:1234567,trackName:'"+title+"',artistName:'Artist',collectionName:'Album',trackTimeMillis:240000,artworkUrl100:'https://is1-ssl.mzstatic.com/current/100x100bb.jpg'}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        },0);
        List<Play> rows=new ArrayList<>();for(String title:new String[]{"Old0","Old1","Obsolete","Current"})rows.add(new Play("apple:"+title,title,title,"Artist","Album",0,60000,60000,240000,"UTC",1,1,true,""));
        artwork.trackHints(rows);artwork.focusTracks(rows);artwork.active(true);
        AutoCloseable first=artwork.load(rows.get(0).getId(),160,b->{}),second=artwork.load(rows.get(1).getId(),160,b->{});
        assertTrue(entered.await(2,TimeUnit.SECONDS));AutoCloseable old=artwork.load(rows.get(2).getId(),160,b->{});artwork.load(rows.get(3).getId(),160,b->ready.set(true));
        artwork.focusTracks(Collections.singletonList(rows.get(3)));first.close();second.close();old.close();release.countDown();await(ready::get);assertEquals(0,obsolete.get());
    }

    @Test public void existingCoverStillBackfillsMissingArtistAndPublishesChange()throws Exception{
        String id="apple:cached-urgent";java.nio.file.Files.write(new File(dir,ArtworkKeys.hash(id)+".jpg").toPath(),jpeg);AtomicInteger searches=new AtomicInteger(),downloads=new AtomicInteger(),changes=new AtomicInteger();
        artwork=new DiaryArtwork(dir,(url,max)->{if(url.contains("/search?")){searches.incrementAndGet();return "{results:[{trackId:1872948412,artistId:1234567,trackName:'戀愛告急',artistName:'旺仔小喬',collectionName:'戀愛告急 - Single',trackTimeMillis:204923,artworkUrl100:'https://is1-ssl.mzstatic.com/urgent/100x100bb.jpg'}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);}downloads.incrementAndGet();return jpeg;},0);
        Play p=new Play(id,"visit","恋爱告急","未知歌手","恋爱告急 - Single",0,60000,60000,204923,"UTC",1,1,true,"");artwork.onMetadataChanged(changes::incrementAndGet);artwork.trackHints(Collections.singletonList(p));artwork.active(true);artwork.focusTracks(Collections.singletonList(p));await(()->TrackMetadata.shared(dir).lookup(p)!=null);org.robolectric.shadows.ShadowLooper.idleMainLooper(1,TimeUnit.SECONDS);assertTrue(changes.get()>0);
        assertEquals(1,searches.get());assertEquals(0,downloads.get());assertEquals("旺仔小喬",new TrackMetadata(dir).lookup(p).getString("artistName"));assertArrayEquals(jpeg,java.nio.file.Files.readAllBytes(new File(dir,ArtworkKeys.hash(id)+".jpg").toPath()));
    }
    @Test public void artistRedirectToHomeRecoversViaExactCanonicalArtistLink()throws Exception{
        AtomicBoolean ready=new AtomicBoolean(),canonical=new AtomicBoolean();artwork=new DiaryArtwork(dir,(url,max)->{
            if(url.contains("entity=musicArtist"))return "{results:[{artistId:777777,artistLinkUrl:'https://music.apple.com/cn/artist/primary/777777?uo=4'}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(url.contains("itunes.apple.com"))return "{results:[{trackId:111111,artistId:777777}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(url.contains("music.apple.com")){if(url.contains("/primary/")){canonical.set(true);return "<meta property='og:url' content='https://music.apple.com/cn/artist/primary/777777'><meta property='og:image' content='https://is1-ssl.mzstatic.com/portrait/1200x630cw.png'>".getBytes(java.nio.charset.StandardCharsets.UTF_8);}return "<meta property='og:url' content='https://music.apple.com/cn/new'><meta property='og:image' content='https://music.apple.com/assets/meta/apple-music.png'>".getBytes(java.nio.charset.StandardCharsets.UTF_8);}return jpeg;
        },0);artwork.artistTracks(Collections.singletonMap("A & B",Collections.singletonList("111111")));artwork.active(true);artwork.load("artist:A & B",160,b->ready.set(true));await(ready::get);assertTrue(canonical.get());assertNotNull(artwork.peek("artist:A & B",160));
    }

}
