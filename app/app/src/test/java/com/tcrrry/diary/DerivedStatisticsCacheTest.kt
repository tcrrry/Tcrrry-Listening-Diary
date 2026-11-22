package com.tcrrry.diary
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class DerivedStatisticsCacheTest {
 @Test fun readsPersistedValuesAndRejectsStaleOrCorruptFiles(){
  val c=RuntimeEnvironment.getApplication();val key="test-${System.nanoTime()}"
  DerivedStatisticsCache.write(c,key,"revision-1",hashMapOf("song" to 3))
  assertEquals(mapOf("song" to 3),DerivedStatisticsCache.read(c,key,"revision-1"))
  assertNull(DerivedStatisticsCache.read(c,key,"revision-2"))
  File(c.filesDir,"derived-statistics-v1/"+ArtworkKeys.hash(key)+".bin").writeText("interrupted cache")
  assertNull(DerivedStatisticsCache.read(c,key,"revision-1"))
  DerivedStatisticsCache.write(c,key,"revision-2",hashMapOf("song" to 4))
  assertEquals(mapOf("song" to 4),DerivedStatisticsCache.read(c,key,"revision-2"))
 }
 @Test fun artistLinksRequireMatchingSongAndExactArtistNotJustCollaborator(){
  val row=JSONObject().put("trackId","123456").put("artistName","VILLSHANA").put("artistId","987654")
  val response=JSONObject().put("results",JSONArray().put(row))
  assertEquals("987654",AppleArtistLinks.fromResponse(response,"123456","VILLSHANA"))
  assertNull(AppleArtistLinks.fromResponse(response,"123456","VILLSHANA & Mahiru"))
  assertNull(AppleArtistLinks.fromResponse(response,"222222","VILLSHANA"))
  assertNull(AppleArtistLinks.fromResponse(response,"123456","Other"))
 }
}
