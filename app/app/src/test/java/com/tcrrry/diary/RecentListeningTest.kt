package com.tcrrry.diary
import org.junit.Test
import org.junit.Assert.*

class RecentListeningTest {
    private val now=10_000_000L
    private fun play(start:Long,id:String="A")=Play(id,"$start","同名歌曲","歌手","专辑",start,start+60000,60000,240000,"UTC")
    private fun report(songs:List<Play>,id:String="A")=DiaryReport(history=songs,segments=songs,lastSync=now,nowTrackId=id,recordState="正在记录")
    @Test fun onlyNewestMatchingVisitHasBadgeAndSameTitleCannotGuessIdentity(){val songs=listOf(play(now-180000),play(now-120000,"B"),play(now-60000));val r=report(songs);val stops=CoverCarousel.stops(songs);assertEquals(stops.first().key,recentPlayingKey(r,stops,now));assertNull(recentPlayingKey(r.copy(nowTrackId="unknown"),stops,now));assertNull(recentPlayingKey(r.copy(nowTrackId=""),stops,now))}
    @Test fun pauseBufferOrStaleHeartbeatNeverLooksLikePlayback(){val r=report(listOf(play(now-60000)));val stops=CoverCarousel.stops(r.history);assertNotNull(recentPlayingKey(r,stops,now));assertNull(recentPlayingKey(r.copy(recordState="已暂停 · 记录已保存"),stops,now));assertNull(recentPlayingKey(r.copy(recordState="缓冲中 · 暂停计时"),stops,now));assertNull(recentPlayingKey(r,stops,now+RecentPlayingLifetime));assertNull(recentPlayingKey(r,stops,now-1))}
    @Test fun historicalDayNeverShowsCurrentBadgeEvenWhenTrackMatches(){val r=report(listOf(play(now-86400000)));assertNull(recentPlayingKey(r,CoverCarousel.stops(r.history),now))}
    @Test fun neighboringRepeatsUseOneThumbnailAndKeepAllListening(){val songs=listOf(play(now-120000),play(now-60000));val r=report(songs);val stops=CoverCarousel.stops(songs);assertEquals(1,stops.size);assertEquals(2,stops.single().count);assertEquals(120000,stops.single().ms);assertEquals(stops.single().key,recentPlayingKey(r,stops,now))}
}
