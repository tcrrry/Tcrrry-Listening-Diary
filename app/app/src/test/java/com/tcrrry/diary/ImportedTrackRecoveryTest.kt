package com.tcrrry.diary
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class ImportedTrackRecoveryTest{
 private fun row(id:Long=1234567,url:String="https://is1-ssl.mzstatic.com/image/thumb/Music/art/100x100bb.jpg")=JSONObject().put("trackId",id).put("trackName","娃儿!莫怕").put("artistName","黄霄雲").put("collectionName","娃儿!莫怕 - Single").put("trackTimeMillis",222507).put("artworkUrl100",url)
 @Test fun repeatedVisitsKeepLatestHintsAndNativeTitleAliases(){
  val t=AppleTrackImages()
  fun play(id:String,title:String,artist:String,imported:Boolean=false)=Play(id,"v",title,artist,"Album",1,60001,60000,200000,"UTC",imported=imported)
  val nativeOld=play("native","Song A","Artist")
  val nativeNew=play("native","Song B","Artist")
  val imported=play("imported","Song A","未知歌手",true)
  t.bind(listOf(nativeOld,nativeNew,imported,imported))
  assertEquals("native",t.localSource("imported"){it=="native"})
  assertEquals("Song B",t.hint("native").title)
 }
 @Test fun roundedTimesAndDifferentArtworkSizesResolveSameEdition(){
  val h=AppleTrackImages.Hint("娃儿!莫怕","未知歌手","娃儿!莫怕 - Single",222000)
  assertNotNull(AppleTrackImages.match(h,JSONArray().put(row()).put(row(7654321,"https://is2-ssl.mzstatic.com/image/thumb/Music/art/600x600bb.jpg"))))
 }
 @Test fun missingAlbumCanResolveButAmbiguousArtistsRemainUnmatched(){
  val h=AppleTrackImages.Hint("娃儿!莫怕","未知歌手","",222000)
  assertNotNull(AppleTrackImages.match(h,JSONArray().put(row())))
  assertNull(AppleTrackImages.match(h,JSONArray().put(row()).put(row(7654321).put("artistName","Other"))))
 }
 @Test fun editedAlbumMayRecoverFromMatchingArtistTitleAndDuration(){
  val h=AppleTrackImages.Hint("娃儿!莫怕","黄霄云","自建专辑",222000)
  assertNotNull(AppleTrackImages.match(h,JSONArray().put(row())))
  assertNull(AppleTrackImages.match(h,JSONArray().put(row().put("collectionName","Live"))))
 }
 @Test fun realAppleCatalogResponsesCanRecoverTheReportedSongs(){
  val path=System.getProperty("diary.apple.fixture");org.junit.Assume.assumeTrue(path!=null)
  val cases=JSONObject(java.io.File(path).readText()).getJSONArray("cases")
  for(i in 0 until cases.length()){
   val c=cases.getJSONObject(i)
   val h=AppleTrackImages.Hint(c.getString("title"),c.getString("artist"),c.getString("album"),c.getLong("duration"))
   val result=AppleTrackImages.match(h,c.getJSONArray("results"))
   assertNotNull("catalog should recover "+h.title,result)
   println("LIVE_CATALOG_MATCH "+h.title+" -> "+result!!.optString("trackId"))
  }
 }
 @Test fun localMatchRequiresRecordingEvidence(){
  val h=AppleTrackImages.Hint("Song","未知歌手","Album",200000)
  assertTrue(AppleTrackImages.localMatch(h,AppleTrackImages.Hint("Song","Artist","Album",200400)))
  assertFalse(AppleTrackImages.localMatch(h,AppleTrackImages.Hint("Song","Artist","Live",200400)))
  assertFalse(AppleTrackImages.localMatch(h,AppleTrackImages.Hint("Other","Artist","Album",200400)))
 }
}
