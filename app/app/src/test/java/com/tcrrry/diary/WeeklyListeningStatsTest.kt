package com.tcrrry.diary
import org.junit.Test
import org.junit.Assert.*
import java.time.*
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[35])
class WeeklyListeningStatsTest{
 private val day=LocalDate.of(2026,10,21)
 private val start=day.atTime(10,0).toInstant(ZoneOffset.UTC).toEpochMilli()
 private fun p(id:String,i:Int,album:String="A",artist:String="Artist",ms:Long=60000)=Play(id,"v$i","Song $id",artist,album,start+i*70000,start+i*70000+ms,ms,180000,"UTC",effective=if(ms>=30000)1 else 0)
 private fun report(rows:List<Play>,spans:List<Play> = rows)=DiaryReport(history=rows,segments=spans)
 @Test fun artistUsesAllTracksAndAlbumsForRunsSharesAndRank(){
  val a=p("a",0);val b=p("b",1,album="B");val other=p("c",2,artist="Other");val again=p("a",3)
  val current=report(listOf(a,b,other,again))
  val target=WeeklyTarget(a,artist=true)
  val stats=weeklyListeningStats(current,target,day)
  assertEquals(3L,stats.plays);assertEquals(180000L,stats.ms);assertEquals(2L,stats.maxConsecutive);assertEquals(120000L,stats.longestRunMs)
  assertEquals(2,stats.songs.map{it.album}.distinct().size);assertEquals(.75,stats.totalShare,.0001)
  assertEquals(1,weeklyRankings(current,target));assertEquals(2,weeklyRankings(current,WeeklyTarget(other,artist=true)))
 }
 @Test fun rankChangesCompareTheFullPreviousRankingAndHandleNewEntries(){
  val a=p("a",0);val b=p("b",1);val c=p("c",2);val previous=report(listOf(a,a.copy(visit="another"),b))
  assertEquals(1,weeklyRankings(previous,WeeklyTarget(a)));assertEquals(2,weeklyRankings(previous,WeeklyTarget(b)))
  assertNull(weeklyRankings(previous,WeeklyTarget(c)));assertEquals(1,RankChange(1,2).delta);assertEquals(-1,RankChange(2,1).delta);assertNull(RankChange(1,null).delta)
 }
 @Test fun weeklyPercentDoesNotDivideByZeroAndShowsAbsoluteDifference(){
  assertEquals("50%",weeklyPercentChange(6,4));assertEquals("50%",weeklyPercentChange(2,4));assertEquals("0%",weeklyPercentChange(4,4))
  assertEquals("新增",weeklyPercentChange(4,0));assertEquals("0%",weeklyPercentChange(0,0));assertEquals("—",weeklyPercentChange(2,null))
 }
 @Test fun artistRankMatchesTheDisplayedListeningTimeRanking(){
  val a=p("a",0,artist="A",ms=90000);val b=p("b",1,artist="B",ms=120000)
  val r=report(listOf(a,a.copy(visit="extra",ms=10000),b))
  assertEquals(2,weeklyRankings(r,WeeklyTarget(a,artist=true)));assertEquals(1,weeklyRankings(r,WeeklyTarget(b,artist=true)))
 }
 @Test fun monthUsesClippedCalendarWeeksAndExcludesOtherMonths(){
  fun on(date:String,i:Int)=p("a",i).let{r->val at=LocalDate.parse(date).atTime(10,0).toInstant(ZoneOffset.UTC).toEpochMilli();r.copy(start=at,end=at+r.ms)}
  val rows=listOf(on("2026-10-31",0),on("2026-11-01",1),on("2026-11-02",2),on("2026-11-30",3),on("2026-12-01",4))
  val stats=weeklyListeningStats(report(rows),WeeklyTarget(rows[0]),LocalDate.of(2026,11,15),DiaryRange.MONTH)
  assertEquals(6,stats.dates.size);assertEquals(LocalDate.of(2026,11,1),stats.dates.first());assertEquals(LocalDate.of(2026,11,1),stats.ends.first())
  assertEquals(LocalDate.of(2026,11,30),stats.ends.last());assertEquals(3L,stats.plays);assertEquals(3L,stats.daily.sum());assertEquals(listOf(1L,1L,0L,0L,0L,1L),stats.daily)
  assertEquals(180000L,stats.artistMs);assertEquals(180000L,stats.totalMs);assertEquals(1.0,stats.totalShare,.0001)
 }
 @Test fun leapYearHasTwelveMonthlyBucketsAndHonorsListeningDayOwnership(){
  fun on(date:String,i:Int)=p("a",i).let{r->val at=LocalDate.parse(date).atTime(10,0).toInstant(ZoneOffset.UTC).toEpochMilli();r.copy(start=at,end=at+r.ms)}
  val rows=listOf(on("2024-01-31",0),on("2024-02-29",1),on("2024-12-31",2),on("2025-01-01",3).copy(listeningDay="2024-12-31"),on("2025-01-02",4))
  val stats=weeklyListeningStats(report(rows),WeeklyTarget(rows[0]),LocalDate.of(2024,5,15),DiaryRange.YEAR)
  assertEquals(12,stats.dates.size);assertEquals(LocalDate.of(2024,2,29),stats.ends[1]);assertEquals(4L,stats.plays)
  assertEquals(1L,stats.daily[0]);assertEquals(1L,stats.daily[1]);assertEquals(2L,stats.daily[11]);assertEquals(4L,stats.daily.sum())
 }
 @Test fun lifetimeBucketsFollowListeningDayAndIncludeAllYears(){
  val a=p("a",0).copy(listeningDay="2024-12-31")
  val b=p("a",1).copy(listeningDay="2026-01-01")
  val other=p("b",2,artist="Other").copy(listeningDay="2025-04-01")
  val stats=lifetimeListeningStats(report(listOf(a,b,other)),WeeklyTarget(a))
  assertEquals(listOf(2024,2025,2026),stats.dates.map{it.year})
  assertEquals(listOf(1L,0L,1L),stats.daily);assertEquals(2L,stats.plays)
  assertEquals(120000L,stats.ms);assertEquals(180000L,stats.totalMs)
 }
 @Test fun monthlyAndYearlyComparisonsShiftTheCorrectCalendarPeriod(){
  assertEquals("上月未播放 · 本月3次",weeklyChange(3,0,DiaryRange.MONTH))
  assertEquals("去年未播放 · 本年3次",weeklyChange(3,0,DiaryRange.YEAR))
  assertEquals("与去年持平",weeklyChange(2,2,DiaryRange.YEAR))
  assertEquals("2024-02-01",bounds(periodShift(LocalDate.of(2024,3,31),DiaryRange.MONTH,-1),DiaryRange.MONTH).from)
  assertEquals("2024-02-29",bounds(periodShift(LocalDate.of(2024,3,31),DiaryRange.MONTH,-1),DiaryRange.MONTH).to)
  assertEquals("2023-01-01",bounds(periodShift(LocalDate.of(2024,2,29),DiaryRange.YEAR,-1),DiaryRange.YEAR).from)
 }
 @Test fun anOpenAlbumUsesTheVerifiedArtistInsteadOfItsOldSelectionIdentity(){
  val selected=p("a",0,artist="未知歌手")
  val fresh=selected.copy(artist="Artist")
  val target=weeklySelectionTarget(listOf(fresh),selected,true)
  assertTrue(target.matches(fresh));assertFalse(target.matches(selected))
  assertEquals(1L,weeklyListeningStats(report(listOf(fresh)),target,day).plays)
 }
 @Test fun sevenDaysCountValidPlaysAndArtistShareUsesListeningTime(){
  val song=p("a",0);val rows=listOf(song,p("a",1),p("b",2),p("c",3,artist="Other"),p("a",4,ms=10000))
  val stats=weeklyListeningStats(report(rows),WeeklyTarget(song),day)
  assertEquals(7,stats.daily.size);assertEquals(2L,stats.daily.sum());assertEquals(2L,stats.plays)
  assertEquals(130000L,stats.ms);assertEquals(190000L,stats.artistMs);assertEquals(130000.0/190000,stats.artistShare,.0001)
  assertEquals(250000L,stats.totalMs);assertEquals(130000.0/250000,stats.totalShare,.0001)
  assertEquals(10,stats.peakHour);assertEquals(song.start,stats.first!!.start);assertEquals(rows.last().end,stats.last!!.end)
 }
 @Test fun timeSharesUseTheSelectedPeriodForSongsAndAlbums(){
  for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
   val rows=listOf(p("a",0),p("b",1),p("c",2,album="Other"),p("d",3,artist="Other"),p("outside",4).copy(listeningDay="2025-01-01",ms=900000))
   val r=report(rows)
   val song=weeklyListeningStats(r,WeeklyTarget(rows[0]),day,range)
   val album=weeklyListeningStats(r,WeeklyTarget(rows[0],true),day,range)
   assertEquals(240000L,song.totalMs);assertEquals(180000L,song.artistMs)
   assertEquals(1.0/3,song.artistShare,.0001);assertEquals(.25,song.totalShare,.0001)
   assertEquals(2.0/3,album.artistShare,.0001);assertEquals(.5,album.totalShare,.0001)
   val empty=weeklyListeningStats(report(emptyList()),WeeklyTarget(rows[0]),day,range)
   assertEquals(0.0,empty.artistShare,0.0);assertEquals(0.0,empty.totalShare,0.0)
  }
 }
 @Test fun repeatsStopOnAnotherSongOrFifteenMinuteGap(){
  val a=p("a",0);val later=p("a",4).let{it.copy(start=it.start+1800000,end=it.end+1800000)}
  val rows=listOf(a,p("a",1),p("b",2),p("a",3),later)
  val stats=weeklyListeningStats(report(rows),WeeklyTarget(a),day)
  assertEquals(2L,stats.maxConsecutive);assertEquals(120000L,stats.longestRunMs)
 }
 @Test fun albumRunsRequireThreePlaysAndCountAsOneUnbrokenSegment(){
  val rows=listOf(p("a",0),p("b",1),p("c",2),p("d",3),p("x",4,album="Other"),p("a",5),p("b",6),p("c",7))
  val stats=weeklyListeningStats(report(rows),WeeklyTarget(rows.first(),true),day)
  assertEquals(2,stats.albumRuns);assertEquals(4L,stats.maxConsecutive);assertEquals(240000L,stats.longestRunMs)
 }
 @Test fun segmentedVisitIsNotCountedTwiceAndPausedTimeIsNotCredited(){
  val song=p("a",0,ms=60000)
  val segments=listOf(song.copy(ms=30000,end=song.start+30000,effective=0),song.copy(start=song.start+60000,end=song.start+90000,ms=30000,effective=0))
  val runs=weeklyListeningRuns(report(listOf(song),segments),WeeklyTarget(song))
  assertEquals(1,runs.size);assertEquals(1L,runs.single().plays);assertEquals(60000L,runs.single().ms)
 }
 @Test fun listeningDayOverrideIsUsedForAfterMidnightPlayback(){
  val song=p("a",0).copy(start=LocalDate.of(2026,10,20).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),listeningDay="2026-10-19")
  val stats=weeklyListeningStats(report(listOf(song)),WeeklyTarget(song),day)
  assertEquals(1L,stats.daily[0]);assertEquals(0L,stats.daily[1])
 }
 @Test fun weekComparisonDoesNotInventInfiniteGrowthOrFirstEverListening(){
  assertEquals("上周未播放 · 本周3次",weeklyChange(3,0));assertEquals("与上周持平",weeklyChange(0,0))
  assertEquals("增加2次 · +100%",weeklyChange(4,2));assertEquals("减少2次 · −50%",weeklyChange(2,4))
 }
 @Test fun fullCatalogIncludesUnplayedSongsAndMatchesLocalIdsByUniqueTitle(){
  val a=p("local:a",0).copy(title="One");val album=AlbumCatalogue("123456","A","Artist",2,listOf(AlbumCatalogueTrack("100001","One","Artist",1,1,180000),AlbumCatalogueTrack("100002","Two","Artist",1,2,180000)),0)
  val slices=weeklyAlbumDistribution(listOf(a),album)
  assertEquals(2,slices.size);assertEquals(1L,slices[0].count);assertEquals(0L,slices[1].count);assertTrue(slices.all{it.catalog})
 }
 @Test fun aOneHeardSongAlbumIsNotAssumedToBeASingle(){
  val song=p("a",0);assertFalse(weeklySingleRelease(WeeklyTarget(song,true),listOf(song),null,null))
  assertTrue(weeklySingleRelease(WeeklyTarget(song.copy(album="Song - Single"),true),listOf(song),null,null))
  val album=AlbumCatalogue("123456","A","Artist",1,listOf(AlbumCatalogueTrack("100001","One","Artist",1,1,180000)),0)
  assertTrue(weeklySingleRelease(WeeklyTarget(song,true),listOf(song),album,null))
 }
}
