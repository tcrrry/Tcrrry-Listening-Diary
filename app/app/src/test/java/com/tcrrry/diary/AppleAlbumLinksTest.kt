package com.tcrrry.diary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class AppleAlbumLinksTest {
 private fun response(artist:String="Artist",title:String="Album",url:String="https://music.apple.com/jp/album/album/123456?i=789012&uo=4")=JSONObject().put("results",JSONArray().put(JSONObject().put("trackId","789012").put("collectionId","123456").put("collectionName",title).put("artistName",artist).put("collectionViewUrl",url)))
 @Test fun usesRegionalAlbumLinkAndCachesWithoutSongQuery(){
  val dir=File(RuntimeEnvironment.getApplication().cacheDir,"album-link-${System.nanoTime()}")
  var calls=0
  val url=AppleAlbumLinks.resolve(dir,"789012","","Album","Artist"){calls++;if(it.contains("country=cn")) JSONObject().put("results",JSONArray()) else response()}
  assertEquals("https://music.apple.com/jp/album/album/123456",url);assertEquals(2,calls)
  assertEquals(url,AppleAlbumLinks.resolve(dir,"789012","","Album","Artist"){throw AssertionError("cache was not used")})
 }
 @Test fun rejectsWrongAlbumArtistAndHost(){
  assertNull(AppleAlbumLinks.fromResponse(response(title="Other"),"789012","","Album","Artist"))
  assertNull(AppleAlbumLinks.fromResponse(response(artist="Other"),"789012","","Album","Artist"))
  assertNull(AppleAlbumLinks.fromResponse(response(url="https://example.com/jp/album/album/123456"),"789012","","Album","Artist"))
 }
}
