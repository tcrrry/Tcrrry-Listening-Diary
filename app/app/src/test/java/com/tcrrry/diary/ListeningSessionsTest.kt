package com.tcrrry.diary
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class ListeningSessionsTest {
    private fun play(start:Long,ms:Long=60000,visit:String="$start",id:String="song")=Play(id,visit,id,"歌手","专辑",start,start+ms,ms,240000,"Asia/Shanghai")
    @Test fun fifteenMinutesExactlyStaysTogetherButLongerStartsAnother(){val first=play(1000000);val second=play(first.end+ListeningSessions.BREAK_MS);val third=play(second.end+ListeningSessions.BREAK_MS+1);assertEquals(listOf(2,1),ListeningSessions.group(listOf(third,first,second)).map{it.songs.size})}
    @Test fun pausesAreNotCreditedAsListening(){val first=play(1000000);val second=play(first.end+300000);val session=ListeningSessions.group(listOf(first,second)).single();assertEquals(120000,session.ms);assertEquals(420000,session.end-session.start)}
    @Test fun spanCheckpointsOfSameVisitAreOneSong(){val first=play(1000000,15000,"visit");val second=play(first.end,15000,"visit");val s=ListeningSessions.group(listOf(first,second)).single();assertEquals(1,s.songs.size);assertEquals(30000,s.songs[0].ms)}
    @Test fun sameSongCanAppearInSeparateSessions(){val first=play(1000000,visit="same");val second=play(first.end+1200000,visit="same");val sessions=ListeningSessions.group(listOf(first,second));assertEquals(2,sessions.size);assertNotEquals(sessions[0].key,sessions[1].key)}
    @Test fun resumeRequiresARealGapWithinTheSameVisit(){val first=play(1000000,15000,"same");val checkpoint=play(first.end,15000,"same");val resumed=play(checkpoint.end+5000,15000,"same");val repeated=play(resumed.end+5000,15000,"other");val session=ListeningSessions.group(listOf(first,checkpoint,resumed,repeated)).single();assertEquals(2,session.songs.size);assertEquals(setOf("same"),ListeningSessions.resumedVisits(listOf(first,checkpoint,resumed,repeated)));assertTrue(ListeningSessions.resumedVisits(listOf(first,checkpoint)).isEmpty())}
    @Test fun resumeIsRecognizedAcrossSeparateSessions(){val first=play(1000000,15000,"same");val resumed=play(first.end+1200000,15000,"same");assertEquals(2,ListeningSessions.group(listOf(first,resumed)).size);assertEquals(setOf("same"),ListeningSessions.resumedVisits(listOf(first,resumed)))}
    @Test fun midnightDoesNotArtificiallySplitAContinuousSession(){val start=LocalDate.of(2026,10,25).atTime(23,59).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();val first=play(start);val second=play(first.end);assertEquals(1,ListeningSessions.group(listOf(first,second)).size)}
    @Test fun hourlyBarsCreditOnlyActualSpansAndConserveTotal(){val start=LocalDate.of(2026,10,25).atTime(8,29).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();val first=play(start,120000);val second=play(first.end+600000,60000);val bins=HourlyListening.bins(listOf(first,second));assertEquals(180000,bins.sum());assertEquals(60000,bins[16]);assertEquals(120000,bins[17])}
    @Test fun emptyInputProducesNoSessionsOrActivity(){assertTrue(ListeningSessions.group(emptyList()).isEmpty());assertEquals(0,HourlyListening.bins(emptyList()).sum())}
    @Test fun hourlyCapsulesConserveListeningAcrossHalfHourBoundary(){val start=LocalDate.of(2026,10,25).atTime(8,29).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();val hours=HourlyListening.hours(listOf(play(start,120000)));assertEquals(24,hours.size);assertEquals(120000,hours[8]);assertEquals(120000,hours.sum())}
    @Test fun fullHourReachesCapacityAndMultiDayReportsUseMatchingCapacity(){val start=LocalDate.of(2026,10,25).atTime(8,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();val full=play(start,3600000);assertEquals(HourlyListening.capacity(listOf(full)),HourlyListening.hours(listOf(full))[8]);val next=play(start+86400000,3600000);assertEquals(7200000,HourlyListening.capacity(listOf(full,next)));assertEquals(7200000,HourlyListening.hours(listOf(full,next))[8])}
    @Test fun fixedSixOClockChartFollowsListeningDayAcrossMidnight(){
        val zone=ZoneId.of("Asia/Shanghai");val day=LocalDate.of(2026,11,2)
        fun at(d:LocalDate,h:Int)=d.atTime(h,0).atZone(zone).toInstant().toEpochMilli()
        val before=play(at(day,5));val start=play(at(day,6));val late=play(at(day,23));val afterMidnight=play(at(day.plusDays(1),1));val nextDay=play(at(day.plusDays(1),6))
        val policy=ListeningDayPolicy(ListeningDayMode.FIXED,6)
        val current=listeningDays(listOf(before,start,late,afterMidnight,nextDay),policy).filter{it.day==day}.map{it.play}
        val bars=HourlyListening.orderedHours(current,HourlyListening.startHour(policy))
        assertEquals(60000L,bars[0]);assertEquals(60000L,bars[17]);assertEquals(60000L,bars[19]);assertEquals(180000L,bars.sum())
        assertEquals(listOf("06","12","18","00","06"),HourlyListening.axisLabels(6,false))
        assertEquals(listOf("上午6","下午12","下午6","上午12","上午6"),HourlyListening.axisLabels(6,true))
    }
    @Test fun calendarAndAutomaticChartsKeepMidnightAxis(){
        assertEquals(0,HourlyListening.startHour(ListeningDayPolicy()))
        assertEquals(0,HourlyListening.startHour(ListeningDayPolicy(ListeningDayMode.AUTO,6)))
        assertEquals(listOf("00","06","12","18","24"),HourlyListening.axisLabels(0,false))
    }
}
