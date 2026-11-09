package com.tcrrry.diary
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
class YearWeeklyTrendTest {
 @Test fun peakLabelsAreFewSeparatedAndUseActualExtremes(){
  val start=LocalDate.of(2026,1,1)
  val values=listOf(5L,20,5,3,8,5,2,14,4,1,5,6,25,7,2,9,4,1,3,4,22,4,2,6,4,1,7)
  val weeks=values.mapIndexed{i,v->YearWeek(start.plusDays(i*7L),start.plusDays(i*7L+6),v)}
  val selected=yearTrendExtrema(weeks)
  assertTrue(selected.size<=8);assertTrue(selected.size>=5)
  assertTrue(selected.zipWithNext().all{(a,b)->b-a>=3})
  assertTrue(selected.all{i->(values[i]>values[i-1]&&values[i]>=values[i+1])||(values[i]<values[i-1]&&values[i]<=values[i+1])})
  assertTrue(yearTrendExtrema(weeks.take(2)).isEmpty())
 }
 @Test fun markerMovesContinuouslyAlongCurveAndClampsAtEnds(){
  val points=listOf(androidx.compose.ui.geometry.Offset(0f,20f),androidx.compose.ui.geometry.Offset(100f,100f))
  assertEquals(0f,yearTrendPosition(points,-1f).x,.001f)
  val middle=yearTrendPosition(points,.5f);assertEquals(50f,middle.x,.001f);assertEquals(60f,middle.y,.1f)
  assertEquals(100f,yearTrendPosition(points,2f).x,.001f)
  assertTrue(yearTrendPosition(points,.51f).x>middle.x)
 }
 @Test fun weeksCoverLeapYearWithoutBorrowingAdjacentYears(){
  val values=mapOf(LocalDate.of(2024,1,1) to 10L,LocalDate.of(2024,2,29) to 20L,LocalDate.of(2024,12,31) to 30L,LocalDate.of(2025,1,1) to 999L)
  val weeks=yearWeeks(2024,values)
  assertEquals(LocalDate.of(2024,1,1),weeks.first().from);assertEquals(LocalDate.of(2024,12,31),weeks.last().to)
  assertEquals(366L,weeks.sumOf{java.time.temporal.ChronoUnit.DAYS.between(it.from,it.to)+1});assertEquals(60L,weeks.sumOf{it.ms})
  assertEquals(20L,weeks.single{LocalDate.of(2024,2,29) in it.from..it.to}.ms)
 }
}
