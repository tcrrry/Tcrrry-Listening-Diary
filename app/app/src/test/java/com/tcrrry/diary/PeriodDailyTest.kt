package com.tcrrry.diary

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class PeriodDailyTest {
    private val day=LocalDate.of(2024,5,10)
    private fun play(id:String,visit:String,at:LocalDate,ms:Long,owner:LocalDate=at):Play {
        val start=at.atTime(1,0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return Play(id,visit,id,"歌手","专辑",start,start+ms,ms,200000,"UTC",listeningDay=owner.toString())
    }
    @Test fun temporaryRecalculationKeepsCalendarButNeverBorrowsAnotherDateOrRange(){
        val report=periodDemoReport(day,DiaryRange.MONTH);val old=computePeriod(report,day,DiaryRange.MONTH)
        assertSame(old,visiblePeriodData(null,old,day,DiaryRange.MONTH))
        assertNull(visiblePeriodData(null,old,day.plusMonths(1),DiaryRange.MONTH))
        assertNull(visiblePeriodData(null,old,day,DiaryRange.YEAR))
        val fresh=computePeriod(report.copy(ms=report.ms+1),day,DiaryRange.MONTH)
        assertSame(fresh,visiblePeriodData(fresh,old,day,DiaryRange.MONTH))
    }
    @Test fun assignedListeningDayAndVisitCountsDriveEachDaysRanking(){
        val late=day.plusDays(1)
        val report=DiaryReport(segments=listOf(play("a","one",day,20000),play("a","one",day,20000),play("a","two",late,40000,day),play("b","three",day,90000),play("c","four",late,40000)))
        val days=dailyPeriodSongs(report)
        assertEquals(listOf("a","b"),days.getValue(day).map{it.id})
        assertEquals(2L,days.getValue(day).first().effective)
        assertEquals(listOf("c"),days.getValue(late).map{it.id})
        assertEquals(2,days.size)
    }
    @Test fun equalCountsAndDurationsUseStableIdentityAndNeverBorrowEmptyDays(){
        val days=dailyPeriodSongs(DiaryReport(segments=listOf(play("z","1",day,40000),play("a","2",day,40000))))
        assertEquals(listOf("a","z"),days.getValue(day).map{it.id})
        assertFalse(days.containsKey(day.plusDays(1)))
    }
    @Test fun historyOmitsEmptyDaysAndMonthsWithoutShiftingDates(){
        val dates=(0 until 31).map{LocalDate.of(2024,5,1).plusDays(it.toLong())}
        val totals=mapOf(dates[2] to 200L,dates[10] to 0L,dates[20] to 300L)
        assertEquals(listOf(dates[2],dates[20]),periodHistoryDates(DiaryRange.MONTH,dates,totals))
        val yearDates=(1..12).map{LocalDate.of(2024,it,1)}
        assertEquals(listOf(LocalDate.of(2024,5,1)),periodHistoryDates(DiaryRange.YEAR,yearDates,totals))
        assertTrue(periodHistoryDates(DiaryRange.WEEK,dates.take(7),emptyMap()).isEmpty())
        val zero=play("zero","zero",dates[1],0)
        assertEquals(listOf(dates[1]),periodHistoryDates(DiaryRange.MONTH,dates,emptyMap(),mapOf(dates[1] to listOf(zero))))
    }
    @Test fun streakUsesOnlyDatesWithListening(){
        assertEquals(3,periodDayStreak(setOf(day,day.plusDays(1),day.plusDays(2),day.plusDays(4))))
        assertEquals(0,periodDayStreak(emptySet()))
    }
    @Test fun calendarContrastAndPlaybackOpacityAreBounded(){
        assertEquals(0f,calendarCapacity(0,10000),.001f);assertEquals(1f,calendarCapacity(10000,10000),.001f)
        assertEquals(.25f,calendarCapacity(2500,10000),.001f);assertTrue(calendarCapacity(2500,10000)>calendarCapacity(100,10000));assertEquals(1f,calendarCapacity(20000,10000),.001f)
        val fast=play("a","a",day,1000);val full=fast.copy(ms=180000)
        assertTrue(playbackOpacity(listOf(fast)) < playbackOpacity(listOf(full)))
        assertTrue(playbackOpacity(listOf(fast)) >= .4f)
        assertEquals(1f,playbackOpacity(listOf(fast.copy(duration=0))),.001f)
        assertEquals(1f,playbackOpacity(listOf(fast),isPlaying=true),.001f)
        assertTrue(playbackOpacity(listOf(fast)) < .5f)
        assertTrue(playbackOpacity(listOf(fast.copy(ms=90000))) < .8f)
        assertTrue(playbackOpacity(listOf(fast,full)) > playbackOpacity(listOf(fast)))
        assertEquals(.08f,playbackFadeBottomAlpha(.4f),.001f)
        assertEquals(1f,playbackFadeBottomAlpha(1f),.001f)
        assertTrue(playbackFadeBottomAlpha(.7f)>playbackFadeBottomAlpha(.5f))
    }
}
