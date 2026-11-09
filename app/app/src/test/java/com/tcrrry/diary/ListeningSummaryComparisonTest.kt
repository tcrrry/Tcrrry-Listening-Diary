package com.tcrrry.diary

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningSummaryComparisonTest {
    @Test fun comparesListeningDurationWithoutDividingByZero() {
        assertEquals("相比昨天增加 50%",listeningDurationComparison(90,60,"昨天",false))
        assertEquals("相比上周减少 50%",listeningDurationComparison(30,60,"上周",false))
        assertEquals("与昨天持平 · 0%",listeningDurationComparison(60,60,"昨天",false))
        assertEquals("上周无记录",listeningDurationComparison(1,0,"上周",false))
        assertEquals("正在读取昨天…",listeningDurationComparison(1,null,"昨天",true))
        assertEquals("昨天对比暂不可用",listeningDurationComparison(1,null,"昨天",false))
    }
    @Test fun currentWeekComparesMatchingDaysAndPastWeekComparesWholeWeek(){
        val wednesday=java.time.LocalDate.of(2026,11,4)
        assertEquals(DayBounds("2026-10-26","2026-10-28"),weeklyComparisonBounds(wednesday,wednesday))
        assertEquals(DayBounds("2026-10-19","2026-10-25"),weeklyComparisonBounds(wednesday.minusWeeks(1),wednesday))
    }
    @Test fun monthAndYearUseMatchingDatesForCurrentPeriod(){
        val today=java.time.LocalDate.of(2024,3,31)
        assertEquals(DayBounds("2024-02-01","2024-02-29"),periodSummaryComparisonBounds(today,today,DiaryRange.MONTH))
        assertEquals(DayBounds("2023-01-01","2023-03-31"),periodSummaryComparisonBounds(today,today,DiaryRange.YEAR))
        assertEquals(DayBounds("2024-01-01","2024-01-31"),periodSummaryComparisonBounds(today.minusMonths(1),today,DiaryRange.MONTH))
    }
    @Test fun dailyDifferenceUsesCreditedMinutesInBothDirections(){
        assertEquals("多听 30 分钟",dailyDurationDelta(3600000,1800000))
        assertEquals("少听 0.5 分钟",dailyDurationDelta(30000,60000))
        assertEquals("与昨天时长相同",dailyDurationDelta(0,0))
    }
}
