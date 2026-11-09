package com.tcrrry.diary
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class AlbumCatalogueTest{
 private fun response(total:Int=3)=JSONObject().put("results",JSONArray().put(JSONObject().put("wrapperType","collection").put("collectionId","123456").put("collectionName","Album").put("artistName","Artist").put("trackCount",total)).also{rows->for(i in 1..3)rows.put(JSONObject().put("kind","song").put("trackId",100000+i).put("collectionId","123456").put("trackName","Song $i").put("artistName","Artist").put("discNumber",if(i==3)2 else 1).put("trackNumber",if(i==3)1 else i).put("trackTimeMillis",180000))})
 @Test fun completeMultiDiscCatalogueAndIncompleteResponseAreDifferent(){
  val full=AlbumCatalogueStore.parse(response(),"Album","Artist")!!;assertTrue(full.complete);assertEquals(listOf(1,1,2),full.tracks.map{it.disc})
  assertFalse(AlbumCatalogueStore.parse(response(4),"Album","Artist")!!.complete)
  assertNull(AlbumCatalogueStore.parse(response(),"Another","Artist"));assertNull(AlbumCatalogueStore.parse(response(),"Album","Someone else"))
 }
 @Test fun verifiedAlbumIdCanBridgeRegionalArtistNamesWithoutAcceptingAnotherAlbum(){
  val raw=response()
  assertNotNull(AlbumCatalogueStore.parse(raw,"Album","Regional Alias","123456"))
  assertNull(AlbumCatalogueStore.parse(raw,"Another Album","Regional Alias","123456"))
  assertNull(AlbumCatalogueStore.parse(raw,"Album","Regional Alias","999999"))
 }
 @Test fun perDiscCountsCannotClassifyAnEntireMultiDiscAlbumAsASingle(){
  val row=JSONObject().put("collectionId","123456").put("trackCount",1).put("discCount",2)
  assertNull(AlbumCatalogueStore.trackFactTotal(listOf(row)))
  row.put("discCount",1);assertEquals(1,AlbumCatalogueStore.trackFactTotal(listOf(row)))
  assertNull(AlbumCatalogueStore.trackFactTotal(listOf(row,JSONObject(row.toString()).put("collectionId","654321"))))
 }
 @Test fun directoryIsCachedAndReopeningDoesNotRequestEveryTrack(){
  val art=File(RuntimeEnvironment.getApplication().filesDir,"album-test-${System.nanoTime()}")
  val target=WeeklyTarget(Play("100001","v","Song 1","Artist","Album",0,100000,100000,180000,"UTC"),true)
  val hints=JSONArray().put(JSONObject().put("collectionId","123456").put("collectionName","Album").put("artistName","Artist"))
  var calls=0
  val first=AlbumCatalogueStore.load(art,target,hints){calls++;response()}
  assertTrue(first!!.complete);assertEquals(1,calls)
  val restored=AlbumCatalogueStore.load(art,target,hints){throw AssertionError("cached catalogue must be reused")}
  assertEquals(first.tracks,restored!!.tracks)
 }
 @Test fun actualPublicStillCatalogueIncludesAllSevenSongsIfFixtureIsAvailable(){
  val file=File("../.private/preview95-catalog-real.json");Assume.assumeTrue(file.isFile)
  val album=AlbumCatalogueStore.parse(JSONObject(file.readText()),"still","MIREI","1555193619")!!
  assertTrue(album.complete);assertEquals(7,album.tracks.size)
 }
}
