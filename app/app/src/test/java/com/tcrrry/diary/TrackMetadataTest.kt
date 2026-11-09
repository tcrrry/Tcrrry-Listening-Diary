package com.tcrrry.diary
import java.io.File
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class TrackMetadataTest {
    @get:Rule val temp=TemporaryFolder()
    private fun hint()=AppleTrackImages.Hint("恋爱告急","未知歌手","恋爱告急 - Single",204923)
    private fun row()=JSONObject().put("trackId",1872948412L).put("artistId",1234567).put("trackName","戀愛告急").put("artistName","旺仔小喬").put("collectionId",1872948410L).put("collectionName","戀愛告急 - Single").put("trackTimeMillis",204923).put("trackCount",1).put("primaryGenreName","Mandopop").put("artworkUrl100","https://is1-ssl.mzstatic.com/urgent/100x100bb.jpg")
    @Test fun verifiedMatchPersistsAcrossInstancesAndIsIdempotent(){
        val dir=temp.newFolder();val cache=TrackMetadata(dir);val id="apple:urgent"
        assertTrue(cache.put(id,hint(),row()));val version=cache.version();assertFalse(cache.put(id,hint(),row()));assertEquals(version,cache.version())
        assertEquals("旺仔小喬",TrackMetadata(dir).lookup(id,hint()).getString("artistName"))
        assertNull(cache.lookup(id,AppleTrackImages.Hint("Another","未知歌手","恋爱告急 - Single",204923)))
        assertNull(cache.lookup(id,AppleTrackImages.Hint("恋爱告急","未知歌手","Live",204923)))
    }
    @Test fun invalidCandidateCannotBecomeAnOverlay(){val cache=TrackMetadata(temp.newFolder());assertFalse(cache.put("apple:urgent",hint(),row().put("trackTimeMillis",209998)));assertNull(cache.lookup("apple:urgent",hint()))}
    @Test fun catalogFactsRetainTheOriginalLedgerKeyAndAcceptChineseVariants(){
        val dir=temp.newFolder();val id="apple:urgent";assertTrue(TrackMetadata.shared(dir).put(id,hint(),row()))
        val result=CatalogMetadata.cached(dir,listOf(id)).getJSONObject(0);assertEquals(id,result.getString("trackId"));assertEquals("1872948412",result.optString("catalogTrackId"))
        val p=Play(id,"visit","恋爱告急","旺仔小乔","恋爱告急 - Single",0,60000,60000,204923,"UTC",effective=1,imported=true)
        assertEquals(1 to 1,albumCoverage(AlbumStat(p.album,p.artist,listOf(p),p.ms),mapOf(id to CatalogFact.parse(result))))
    }
}
