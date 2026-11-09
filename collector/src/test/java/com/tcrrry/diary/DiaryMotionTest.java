package com.tcrrry.diary;
import org.junit.Test;import static org.junit.Assert.*;
public class DiaryMotionTest {
    @Test public void springSettles(){float x=0,v=0;for(int i=0;i<180;i++){float[] r=DiaryMotion.integrate(x,v,1,1/60f);x=r[0];v=r[1];}assertEquals(1,x,.001);assertEquals(0,v,.01);}
    @Test public void reversalDoesNotJump(){float[] s=DiaryMotion.integrate(.6f,1,0,1/60f);assertTrue(s[0]>.55f&&s[0]<.7f);assertTrue(s[1]<1);}
    @Test public void suspendedFrameIsCapped(){float[] s=DiaryMotion.integrate(0,0,1,20);assertTrue(Float.isFinite(s[0]));assertTrue(s[0]>=0&&s[0]<1);}
    @Test public void artworkDomainsAreRestricted(){assertTrue(DiaryArtwork.allowed("https://is1-ssl.mzstatic.com/image/test.jpg"));assertTrue(DiaryArtwork.allowed("https://www.theaudiodb.com/images/media/test.jpg"));assertFalse(DiaryArtwork.allowed("http://www.theaudiodb.com/a"));assertFalse(DiaryArtwork.allowed("https://user:secret@itunes.apple.com/lookup"));assertFalse(DiaryArtwork.allowed("https://evil-mzstatic.com/test"));}
}
