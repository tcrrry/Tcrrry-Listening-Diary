package com.tcrrry.diary;
import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import java.util.*;import java.nio.file.*;import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class AppleArtistImagesTest {
    private String page(String id,String image){return "<meta property='og:url' content='https://music.apple.com/cn/artist/mirei/"+id+"'><meta content='"+image+"' property='og:image'>";}
    @Test public void identityUsesOnlyRequestedTracks()throws Exception{JSONObject json=new JSONObject("{results:[{trackId:1537779205,artistId:882739606},{trackId:999999,artistId:888888}]}");assertEquals("882739606",AppleArtistImages.identity(json,new HashSet<>(Arrays.asList("1537779205"))));}
    @Test public void ambiguousCatalogIdentitiesAreNotGuessed()throws Exception{JSONObject json=new JSONObject("{results:[{trackId:111111,artistId:777777},{trackId:222222,artistId:888888}]}");assertEquals("",AppleArtistImages.identity(json,new HashSet<>(Arrays.asList("111111","222222"))));}
    @Test public void confirmedPageProducesSquareApplePhoto(){assertEquals("https://is1-ssl.mzstatic.com/image/artist/600x600bb.jpg",AppleArtistImages.pageImage("882739606",page("882739606","https://is1-ssl.mzstatic.com/image/artist/1200x630cw.png")));}
    @Test public void genericAndWrongArtistPagesAreRejected(){assertEquals("",AppleArtistImages.pageImage("882739606",page("123456","https://is1-ssl.mzstatic.com/image/1200x630cw.png")));assertEquals("",AppleArtistImages.pageImage("882739606","<meta property='og:url' content='https://music.apple.com/cn/new'><meta property='og:image' content='https://is1-ssl.mzstatic.com/image.png'>"));}
    @Test public void nonApplePhotosAreRejected(){assertEquals("",AppleArtistImages.pageImage("882739606",page("882739606","https://example.com/photo.jpg")));}
    @Test public void capturedMireiPageHasVerifiedArtistPhoto()throws Exception{String fixture=System.getProperty("diary.artist.fixture");if(fixture==null)return;String html=new String(Files.readAllBytes(Paths.get(fixture)),java.nio.charset.StandardCharsets.UTF_8);String url=AppleArtistImages.pageImage("882739606",html);assertTrue(url.contains("AMCArtistImages"));assertTrue(url.endsWith("600x600bb.jpg"));}
    @Test public void nearbyArtistsShareCatalogIdentityBatchAndPersistAcrossRestart()throws Exception{
        java.io.File dir=Files.createTempDirectory("artist-batch").toFile();AppleArtistImages images=new AppleArtistImages(dir);
        Map<String,List<String>> tracks=new LinkedHashMap<>();tracks.put("First",Arrays.asList("111111"));tracks.put("Next",Arrays.asList("222222"));images.bind(tracks);
        java.util.concurrent.atomic.AtomicInteger lookups=new java.util.concurrent.atomic.AtomicInteger();
        DiaryArtwork.Fetcher fetch=(url,max)->{if(url.contains("itunes.apple.com")){lookups.incrementAndGet();assertTrue(url.contains("111111"));assertTrue(url.contains("222222"));return "{results:[{trackId:111111,artistId:777777},{trackId:222222,artistId:888888}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);}String id=url.contains("777777")?"777777":"888888";return page(id,"https://is1-ssl.mzstatic.com/image/1200x630cw.png").getBytes(java.nio.charset.StandardCharsets.UTF_8);};
        assertFalse(images.resolve("First",fetch,()->{}).isEmpty());assertFalse(images.resolve("Next",fetch,()->{}).isEmpty());assertEquals(1,lookups.get());
        AppleArtistImages restarted=new AppleArtistImages(dir);assertFalse(restarted.resolve("Next",fetch,()->{}).isEmpty());assertEquals(1,lookups.get());
    }
    @Test public void albumCatalogResultCanSupplyArtistIdentityWithoutAnotherLookup()throws Exception{
        AppleArtistImages images=new AppleArtistImages(Files.createTempDirectory("artist-reuse").toFile());images.bind(Collections.singletonMap("MIREI",Arrays.asList("1537779205")));
        images.acceptCatalog(new JSONObject("{results:[{trackId:1537779205,artistId:882739606}]}"));
        String url=images.resolve("MIREI",(request,max)->{assertFalse(request.contains("itunes.apple.com"));return page("882739606","https://is1-ssl.mzstatic.com/image/1200x630cw.png").getBytes(java.nio.charset.StandardCharsets.UTF_8);},()->fail("Unexpected catalog gate"));assertFalse(url.isEmpty());
    }
}
