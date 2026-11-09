package com.tcrrry.diary

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class WeeklyListeningHeatmapTest {
    @Test fun fixedDayGroupsAfterMidnightAndCreditsOnlyActualTime(){
        val zone=ZoneId.of("Asia/Shanghai");val monday=LocalDate.of(2026,11,2)
        fun at(d:LocalDate,h:Int,m:Int=0)=d.atTime(h,m).atZone(zone).toInstant().toEpochMilli()
        fun span(start:Long,end:Long,ms:Long,owner:String)=Play("song","visit","Song","Artist","Album",start,end,ms,240000,zone.id,listeningDay=owner)
        val records=listOf(span(at(monday,8,30),at(monday,9,30),1800000,monday.toString()),span(at(monday.plusDays(1),1),at(monday.plusDays(1),2),3600000,monday.toString()),span(at(monday.plusDays(1),6),at(monday.plusDays(1),7),3600000,monday.plusDays(1).toString()))
        val rows=weeklyHeatmap(records,List(7){monday.plusDays(it.toLong())},ListeningDayPolicy(ListeningDayMode.FIXED,6))
        assertEquals(900000L,rows[0][0]);assertEquals(900000L,rows[0][1]);assertEquals(3600000L,rows[0][6]);assertEquals(3600000L,rows[1][0]);assertEquals(records.sumOf{it.ms},rows.flatten().sum())
    }
    @Test fun unassignedRecordsFollowFixedDayBoundary(){
        val zone=ZoneId.of("Asia/Shanghai");val day=LocalDate.of(2026,11,2);val instant=day.plusDays(1).atTime(1,0).atZone(zone).toInstant().toEpochMilli()
        val record=Play("s","v","S","A","X",instant,instant+60000,60000,240000,zone.id)
        val rows=weeklyHeatmap(listOf(record),listOf(day,day.plusDays(1)),ListeningDayPolicy(ListeningDayMode.FIXED,6))
        assertEquals(60000L,rows[0][6]);assertEquals(0L,rows[1].sum())
    }
    @Test fun adaptiveIntervalsCoverTheDayAndSplitActiveHours(){
        val quiet=List(7){List(24){0L}}
        assertEquals(List(8){3},adaptiveWeekIntervals(quiet).map{it.count()})
        val active=List(7){List(24){hour->if(hour in 8..11)60000L else 0L}}
        val ranges=adaptiveWeekIntervals(active)
        assertEquals((0 until 24).toList(),ranges.flatMap{it.toList()})
        assertTrue(ranges.filter{it.first in 8..11}.all{it.count()==1})
        assertTrue(ranges.any{it.count()==4})
    }
}
