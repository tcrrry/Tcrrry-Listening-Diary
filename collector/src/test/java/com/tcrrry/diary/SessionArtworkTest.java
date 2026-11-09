package com.tcrrry.diary;

import android.media.MediaMetadata;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SessionArtworkTest {
    private MediaMetadata track(String id,String title,String artist,String album,long duration){
        return new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID,id).putString(MediaMetadata.METADATA_KEY_TITLE,title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,artist).putString(MediaMetadata.METADATA_KEY_ALBUM,album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION,duration).build();
    }
    @Test public void exactIdentityAndTitleMatchesEvenWhenOtherFieldsMissing(){
        assertTrue(SessionArtwork.sameTrack(track("n-42","Song","","",0),track("n-42","Song","","",0)));
        assertFalse(SessionArtwork.sameTrack(track("n-42","Song","","",0),track("n-42","Other","","",0)));
    }
    @Test public void mismatchedIdsNeedExactCompleteMetadataAndCloseDuration(){
        MediaMetadata local=track("local-7","小孩 (Live)","歌手","现场专辑",210000);
        assertTrue(SessionArtwork.sameTrack(local,track("session-8","小孩 (Live)","歌手","现场专辑",211000)));
        assertFalse(SessionArtwork.sameTrack(local,track("session-8","小孩","歌手","现场专辑",211000)));
        assertFalse(SessionArtwork.sameTrack(local,track("session-8","小孩 (Live)","别的歌手","现场专辑",211000)));
        assertFalse(SessionArtwork.sameTrack(local,track("session-8","小孩 (Live)","歌手","其他专辑",211000)));
        assertFalse(SessionArtwork.sameTrack(local,track("session-8","小孩 (Live)","歌手","现场专辑",220000)));
        assertFalse(SessionArtwork.sameTrack(local,track("session-8","小孩 (Live)","歌手","现场专辑",0)));
    }
    @Test public void uriIsLimitedToContentProviders(){
        MediaMetadata valid=new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI,"content://com.apple.android.music/art/1").build();
        assertEquals(Uri.parse("content://com.apple.android.music/art/1"),SessionArtwork.contentArtUri(valid));
        for(String value:new String[]{"https://example.com/image.jpg","file:///private/image.jpg","content:///missing",""}){
            assertNull(SessionArtwork.contentArtUri(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_ART_URI,value).build()));
        }
    }
    @Test public void decodesRealImageWithBoundedSize()throws Exception{
        android.graphics.Bitmap original=android.graphics.Bitmap.createBitmap(1400,700,android.graphics.Bitmap.Config.ARGB_8888);
        original.eraseColor(0xff224488);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();original.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
        android.graphics.Bitmap decoded=SessionArtwork.decode(out.toByteArray());assertNotNull(decoded);assertTrue(decoded.getWidth()<=1280);decoded.recycle();original.recycle();
    }
    @Test public void rejectsInvalidAndOversizedImageBytes(){assertNull(SessionArtwork.decode(new byte[]{1,2,3}));assertNull(SessionArtwork.decode(new byte[8*1024*1024+1]));}
}
