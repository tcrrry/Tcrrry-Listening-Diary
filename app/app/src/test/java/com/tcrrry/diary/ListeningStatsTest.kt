package com.tcrrry.diary
import org.junit.Test
import org.junit.Assert.*
class ListeningStatsTest {
    private fun p(id:String,start:Long,ms:Long,duration:Long=240000,visit:String="$start")=Play(id,visit,"曲$id","歌手$id","同名专辑",start,start+ms,ms,duration,"UTC",effective=1)
    @Test fun estimateExcludesCompleteFinalUnknownSameTrackAndLongBreaks(){
        assertEquals(1,estimatedEarlyChanges(listOf(p("A",0,30000),p("B",30000,60000))))
        assertEquals(0,estimatedEarlyChanges(listOf(p("A",0,240000),p("B",240000,60000))))
        assertEquals(0,estimatedEarlyChanges(listOf(p("A",0,30000))))
        assertEquals(0,estimatedEarlyChanges(listOf(p("A",0,30000,0),p("B",30000,60000))))
        assertEquals(0,estimatedEarlyChanges(listOf(p("A",0,30000),p("A",30000,60000))))
        assertEquals(0,estimatedEarlyChanges(listOf(p("A",0,30000),p("B",90000,60000))))
    }
    @Test fun artistAndAlbumCountsUseDistinctTracksAndAlbumArtist(){val a=p("A",0,30000);val b=p("B",30000,60000).copy(artist=a.artist);assertEquals(0,multiSongArtists(listOf(a,a)));assertEquals(1,multiSongArtists(listOf(a,a,b)));assertEquals(2,reportAlbums(DiaryReport(history=listOf(a,p("B",30000,60000)))).size)}
    @Test fun rankingAggregatesRepeatsByCountAndKeepsAlbumsSeparate(){val a=p("A",0,30000);val b=p("B",30000,60000);val r=DiaryReport(history=listOf(a,a.copy(visit="repeat",start=100000),b));assertEquals("A",sortedPlays(r.history,SortOrder.COUNT).first().id);val groups=libraryGroups(r,false);assertEquals(2,groups.size);assertEquals(2,groups.first().plays);assertEquals(1,groups.first().songs.size)}
}
