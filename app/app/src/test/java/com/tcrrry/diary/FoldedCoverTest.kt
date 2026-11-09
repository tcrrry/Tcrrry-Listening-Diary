package com.tcrrry.diary

import org.junit.Test
import org.junit.Assert.*

class FoldedCoverTest {
    private fun play(start:Long,visit:String="$start",id:String="A",ms:Long=60000)=Play(id,visit,id,"歌手","同一专辑",start,start+ms,ms,240000,"Asia/Shanghai")
    @Test fun neighboringRepeatsShareOneStopAndKeepEveryVisitAndCreditedMinute(){
        val rows=(0..2).map{play(1000000L+it*60000)}
        val stop=CoverCarousel.stops(rows.reversed()).single()
        assertEquals(3,stop.count);assertEquals(rows.sumOf{it.ms},stop.ms);assertEquals(rows.first().start,stop.start);assertEquals(rows.last().end,stop.end)
        assertEquals(rows.asReversed().map{it.visit},stop.songs.map{it.visit})
        assertEquals(1,CoverCarousel.pageCount(1))
    }
    @Test fun interveningSongAndSameAlbumNeverFoldTogether(){
        val rows=listOf(play(1000000),play(1060000),play(1120000,id="B"),play(1180000))
        val stops=CoverCarousel.stops(rows)
        assertEquals(listOf(1,1,2),stops.map{it.count});assertEquals(listOf("A","B","A"),stops.map{it.play.id})
        val keys=(0 until CoverCarousel.pageCount(stops.size)).map{CoverCarousel.stopKey(it,stops)}
        assertEquals(keys.size,keys.toSet().size)
    }
    @Test fun sameSongAfterLongStopIsSeparateButExactlyFifteenMinutesStaysTogether(){
        val first=play(1000000);val second=play(first.end+ListeningSessions.BREAK_MS);val third=play(second.end+ListeningSessions.BREAK_MS+1)
        assertEquals(listOf(1,2),CoverCarousel.stops(listOf(first,second,third)).map{it.count})
    }
    @Test fun checkpointsAreOneVisitAndActualSegmentsPreserveLongPause(){
        val a=play(1000000,"same",ms=15000);val b=play(a.end,"same",ms=15000)
        val combined=a.copy(end=b.end,ms=30000)
        assertEquals(1,CoverCarousel.stops(listOf(combined),listOf(a,b)).single().count)
        val late=play(b.end+ListeningSessions.BREAK_MS+1,"same",ms=15000)
        val stops=CoverCarousel.stops(listOf(combined.copy(end=late.end,ms=45000)),listOf(a,b,late))
        assertEquals(2,stops.size);assertNotEquals(stops[0].key,stops[1].key);assertEquals(45000,stops.sumOf{it.ms})
    }
    @Test fun appendRepeatKeepsFoldIdentityAndFiltersOtherReportRows(){
        val a=play(1000000);val b=play(a.end);val key=CoverCarousel.stops(listOf(a,b)).single().key
        val c=play(b.end);val unrelated=play(c.end,id="outside")
        val stop=CoverCarousel.stops(listOf(a,b,c),listOf(a,b,c,unrelated)).single()
        assertEquals(key,stop.key);assertEquals(3,stop.count)
    }
    @Test fun emptyHistoryHasNoStops(){assertTrue(CoverCarousel.stops(emptyList()).isEmpty())}
}
