package com.tcrrry.diary
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class CatalogMetadataTest {
    @get:Rule val temp=TemporaryFolder()
    private fun response(row:JSONObject)=JSONObject().put("results",JSONArray().put(row))
    private fun valid()=JSONObject().put("trackId",123456).put("collectionId",654321).put("collectionName","Album").put("artistName","Artist").put("trackCount",12).put("primaryGenreName","Pop")
    @Test fun identityOnlyResponsesDoNotEraseKnownAlbumFacts(){val dir=temp.newFolder();CatalogMetadata.accept(dir,response(valid()));CatalogMetadata.accept(dir,response(JSONObject().put("trackId",123456).put("artistId",98765)));val row=CatalogMetadata.cached(dir,listOf("123456")).getJSONObject(0);assertEquals(12,row.getInt("trackCount"));assertEquals("Album",row.getString("collectionName"))}
    @Test fun changingEditionDoesNotCarryOverPreviousAlbumTrackCount(){val dir=temp.newFolder();CatalogMetadata.accept(dir,response(valid()));CatalogMetadata.accept(dir,response(JSONObject().put("trackId",123456).put("collectionId",888888)));val row=CatalogMetadata.cached(dir,listOf("123456")).getJSONObject(0);assertFalse(row.has("trackCount"));assertEquals("888888",row.optString("collectionId"))}
    @Test fun disabledNetworkUsesCacheWithoutCallingFetcher(){val dir=temp.newFolder();CatalogMetadata.accept(dir,response(valid()));val images=DiaryArtwork(dir,{_,_->throw AssertionError("Network should stay disabled")},0);try{images.active(true);images.network(false);val rows=images.summaryMetadata(listOf("123456","222222")){true};assertEquals(1,rows.length());assertEquals(12,rows.getJSONObject(0).getInt("trackCount"))}finally{images.close()}}
}
