package com.tcrrry.diary
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class ReportStatsTest {
    private val date=LocalDate.of(2026,10,25)
    private fun p(id:String,start:Long,visit:String)=Play(id,visit,id,"Artist","Album",start,start+60000,60000,240000,"UTC",effective=1)
    @Test fun monthlyCellsKeepCalendarWeekdayAndAllDates(){val cells=monthCells(date);assertEquals(3,cells.takeWhile{it==null}.size);assertEquals(31,cells.filterNotNull().size);assertEquals(date.withDayOfMonth(1),cells[3]);assertEquals(date.withDayOfMonth(31),cells.last())}
    @Test fun yearAndMonthNavigationClampLeapDates(){assertEquals(LocalDate.of(2025,2,28),periodShift(LocalDate.of(2024,2,29),DiaryRange.YEAR,1));assertEquals(LocalDate.of(2026,2,28),periodShift(LocalDate.of(2026,1,31),DiaryRange.MONTH,1))}
    @Test fun dailyTotalsSplitAtMidnightWithoutInventingTime(){val start=date.atTime(23,59).toInstant(ZoneOffset.UTC).toEpochMilli();val song=p("a",start,"1").copy(end=start+120000,ms=120000);val result=reportDays(DiaryReport(segments=listOf(song)));assertEquals(60000L,result[date]);assertEquals(60000L,result[date.plusDays(1)]);assertEquals(120000,result.values.sum())}
    @Test fun albumsDoNotMergeUnrelatedArtistsOrMissingNames(){val a=p("1",0,"1");val b=p("2",1,"2").copy(artist="Other");val c=p("3",2,"3").copy(album="");val result=reportAlbums(DiaryReport(history=listOf(a,b,c)));assertEquals(2,result.size);assertEquals(120000,result.sumOf{it.ms})}
    @Test fun onlyConsecutiveSameTrackVisitsCollapse(){val songs=listOf(p("a",0,"1"),p("a",60000,"2"),p("b",120000,"3"),p("a",180000,"4"));val groups=repeatPlays(songs);assertEquals(listOf(2,1,1),groups.map{it.songs.size});assertEquals(songs,groups.flatMap{it.songs});assertEquals(120000,groups.first().end)}
    @Test fun reverseOrderRetainsStableRepeatIdentityAndTotals(){val songs=listOf(p("a",0,"1"),p("a",60000,"2"),p("a",120000,"3"));val forward=repeatPlays(songs).single();val reverse=repeatPlays(songs.reversed()).single();assertEquals(forward.key,reverse.key);assertEquals(forward.start,reverse.start);assertEquals(forward.end,reverse.end);assertEquals(180000,reverse.songs.sumOf{it.ms})}
    @Test fun arcMapsEntireExistingCarouselWithoutChangingItsOrder(){assertEquals(0,arcHistoryIndex(0f,101));assertEquals(50,arcHistoryIndex(.5f,101));assertEquals(100,arcHistoryIndex(1f,101));assertEquals(0,arcHistoryIndex(.5f,1))}
    @Test fun darkBackgroundKeepsVisibleAlbumHue(){val blue=darkAlbumColor(androidx.compose.ui.graphics.Color.Blue,false).value;val c=darkAlbumColor(androidx.compose.ui.graphics.Color.Blue,false);val hsv=FloatArray(3);android.graphics.Color.colorToHSV(c.toArgbForTest(),hsv);assertTrue(hsv[2]>=.39f);assertTrue(hsv[1]>.3f);assertTrue(hsv[0] in 220f..260f)}
}
private fun androidx.compose.ui.graphics.Color.toArgbForTest()=android.graphics.Color.argb((alpha*255).toInt(),(red*255).toInt(),(green*255).toInt(),(blue*255).toInt())
