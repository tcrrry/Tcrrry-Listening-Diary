package com.tcrrry.diary
import java.time.*
import org.junit.Test
import org.junit.Assert.*

class ListeningDayPolicyTest {
    private fun p(at:String,minutes:Long=60,id:String=at):Play {val start=Instant.parse(at).toEpochMilli();return Play(id,id,"Song","Artist","Album",start,start+minutes*60000,minutes*60000,240000,"UTC")}
    @Test fun fixedBoundarySplitsAtFourAndConservesEveryMillisecond(){
        val song=p("2025-12-31T03:30:00Z")
        val parts=listeningDays(listOf(song),ListeningDayPolicy(ListeningDayMode.FIXED,4))
        assertEquals(listOf("2025-12-30","2025-12-31"),parts.map{it.day.toString()})
        assertEquals(song.ms,parts.sumOf{it.play.ms});assertEquals(listOf(1800000L,1800000L),parts.map{it.play.ms})
    }
    @Test fun autoKeepsContinuousMidnightListeningUntilSix(){
        val parts=listeningDays(listOf(p("2025-12-31T23:00:00Z",480)),ListeningDayPolicy(ListeningDayMode.AUTO))
        assertEquals(listOf("2025-12-31","2025-12-31","2026-01-01"),parts.map{it.day.toString()})
        assertEquals(480*60000L,parts.sumOf{it.play.ms})
    }
    @Test fun autoFourHourSilenceStartsANewDayAndShortGapContinuesYesterday(){
        val base=p("2025-12-31T22:00:00Z",60)
        val short=listeningDays(listOf(base,p("2026-01-01T02:59:00Z")),ListeningDayPolicy(ListeningDayMode.AUTO))
        val full=listeningDays(listOf(base,p("2026-01-01T03:00:00Z")),ListeningDayPolicy(ListeningDayMode.AUTO))
        assertEquals(LocalDate.of(2025,12,31),short.last().day);assertEquals(LocalDate.of(2026,1,1),full.last().day)
    }
    @Test fun autoCannotInventAnUnobservedPreviousSession(){assertEquals(LocalDate.of(2026,1,1),listeningDays(listOf(p("2026-01-01T01:00:00Z")),ListeningDayPolicy(ListeningDayMode.AUTO)).single().day)}
    @Test fun calendarAndFixedUseRecordedTimezone(){
        val song=p("2025-12-31T17:00:00Z").copy(zone="+08:00")
        assertEquals("2026-01-01",listeningDays(listOf(song),ListeningDayPolicy()).single().day.toString())
        assertEquals("2025-12-31",listeningDays(listOf(song),ListeningDayPolicy(ListeningDayMode.FIXED,4)).single().day.toString())
    }
    @Test fun todayFollowsFixedCutoffAndAutoSilenceAcrossNewYear(){
        val at=ZonedDateTime.parse("2026-01-01T01:00:00Z")
        val yesterday=LocalDate.of(2025,12,31)
        assertEquals(yesterday,listeningToday(at,ListeningDayPolicy(ListeningDayMode.FIXED,4)))
        assertEquals(at.toLocalDate(),listeningToday(at,ListeningDayPolicy()))
        val previous=DatedPlay(yesterday,p("2025-12-31T23:30:00Z",30))
        assertEquals(yesterday,listeningToday(at,ListeningDayPolicy(ListeningDayMode.AUTO),previous))
        assertEquals(at.toLocalDate(),listeningToday(at.plusHours(3),ListeningDayPolicy(ListeningDayMode.AUTO),previous))
        assertEquals(at.toLocalDate(),listeningToday(at,ListeningDayPolicy(ListeningDayMode.AUTO)))
    }
}
