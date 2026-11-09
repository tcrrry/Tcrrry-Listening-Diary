package com.tcrrry.diary

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class PeriodHeroSlicesTest {
    private val october = LocalDate.of(2026, 10, 25)
    private fun span(day: LocalDate, id: String = "a", minutes: Long = 5, artist: String = "Artist", album: String = "Album"): Play {
        val start = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return Play(id, "$day:$id", id, artist, album, start, start + minutes * 60000, minutes * 60000, 240000, "UTC")
    }
    @Test fun weekContainsSevenDatesAndDoesNotBorrowAnotherDaysFavorites() {
        val monday = span(october.minusDays(6), "Monday")
        val sunday = span(october, "Sunday", artist = "Other")
        val slices = periodHeroSlices(DiaryReport(segments = listOf(monday, sunday)), october, DiaryRange.WEEK)
        assertEquals(7, slices.size)
        assertEquals("Monday", slices.first().lead?.id)
        assertEquals("Sunday", slices.last().lead?.id)
        assertTrue(slices[3].albums.isEmpty())
        assertEquals("Other", slices.last().artists.single().name)
    }
    @Test fun monthUsesClippedMondayToSundayWeeksWithoutOverlappingAdjacentMonths() {
        val slices = periodHeroSlices(DiaryReport(), october, DiaryRange.MONTH)
        assertEquals(listOf(1, 5, 12, 19, 26), slices.map { it.from.dayOfMonth })
        assertEquals(listOf(4, 11, 18, 25, 31), slices.map { it.to.dayOfMonth })
        assertEquals(listOf("一周", "二周", "三周", "四周", "五周"), slices.map { heroSliceLabel(it, DiaryRange.MONTH) })
        assertEquals(31, slices.sumOf { java.time.temporal.ChronoUnit.DAYS.between(it.from, it.to).toInt() + 1 })
    }
    @Test fun sixWeekMonthAndLeapFebruaryKeepEveryCalendarDay() {
        val six = periodHeroSlices(DiaryReport(), LocalDate.of(2026, 3, 1), DiaryRange.MONTH)
        val leap = periodHeroSlices(DiaryReport(), LocalDate.of(2024, 2, 29), DiaryRange.MONTH)
        assertEquals(6, six.size)
        assertEquals(29, leap.last().to.dayOfMonth)
    }
    @Test fun crossMidnightAndWeekBoundaryPreserveCreditedMillisecondsNotElapsedPauseTime() {
        val start = october.atTime(23, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        val crossing = span(october).copy(start = start, end = start + 120000, ms = 90001)
        val slices = periodHeroSlices(DiaryReport(segments = listOf(crossing)), october, DiaryRange.MONTH)
        assertEquals(45000, slices[3].ms)
        assertEquals(45001, slices[4].ms)
        assertEquals(crossing.ms, slices.sumOf { it.ms })
    }
    @Test fun sourceTimezoneDeterminesDayEvenWhenDeviceTimezoneDiffers() {
        val zone = ZoneId.of("Asia/Tokyo")
        val start = october.atTime(0, 10).atZone(zone).toInstant().toEpochMilli()
        val source = span(october).copy(zone = zone.id, start = start, end = start + 300000)
        val week = periodHeroSlices(DiaryReport(segments = listOf(source)), october, DiaryRange.WEEK)
        assertEquals(300000, week.last().ms)
        assertEquals(0, week[5].ms)
    }
    @Test fun albumAndArtistRankingsUseActualTimeAndConserveSeparateIdentities() {
        val spans = listOf(span(october, "1", 10), span(october, "2", 8), span(october, "3", 15, "Other"), span(october, "4", 2, album = ""))
        val slice = periodHeroSlices(DiaryReport(segments = spans), october, DiaryRange.WEEK).last()
        assertEquals("Artist", slice.albums.first().artist)
        assertEquals(18 * 60000L, slice.albums.first().ms)
        assertEquals(3, slice.albums.size)
        assertEquals("Artist", slice.artists.first().name)
        assertEquals(3, slice.artists.first().tracks)
        assertEquals(spans.sumOf { it.ms }, slice.albums.sumOf { it.ms })
        assertEquals(spans.sumOf { it.ms }, slice.artists.sumOf { it.ms })
    }
    @Test fun yearKeepsTwelveIndependentMonthsIncludingEmptyFutureMonths() {
        val slices = periodHeroSlices(DiaryReport(segments = listOf(span(october))), october, DiaryRange.YEAR)
        assertEquals((1..12).toList(), slices.map { it.from.monthValue })
        assertEquals(9, heroSliceIndex(slices, october))
        assertTrue(slices[10].albums.isEmpty())
        assertEquals("10月", heroSliceLabel(slices[9], DiaryRange.YEAR))
    }
    @Test fun heroSelectorOmitsEmptyPeriodsWithoutRenumberingWeeks(){
        val weekFour=LocalDate.of(2026,10,22)
        val report=DiaryReport(segments=listOf(span(weekFour)))
        val month=visiblePeriodHeroSlices(report,weekFour,DiaryRange.MONTH)
        assertEquals(1,month.size);assertEquals("四周",heroSliceLabel(month.single(),DiaryRange.MONTH))
        assertEquals(weekFour,visiblePeriodHeroSlices(report,weekFour,DiaryRange.WEEK).single().from)
        assertEquals(10,visiblePeriodHeroSlices(report,weekFour,DiaryRange.YEAR).single().from.monthValue)
        assertTrue(visiblePeriodHeroSlices(DiaryReport(),weekFour,DiaryRange.MONTH).isEmpty())
    }
    @Test fun stalePagerKeysRemainSafeWhenThePeriodListShrinks(){
        val slices=visiblePeriodHeroSlices(DiaryReport(segments=listOf(span(october))),october,DiaryRange.WEEK)
        assertEquals(1,slices.size)
        assertEquals(slices.single().key,periodPagerKey(slices,0))
        assertEquals("pending-page:1",periodPagerKey(slices,1))
        assertEquals("pending-page:0",periodPagerKey(emptyList(),0))
    }
}
