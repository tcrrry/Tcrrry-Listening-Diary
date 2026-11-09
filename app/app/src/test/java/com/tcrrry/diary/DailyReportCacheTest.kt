package com.tcrrry.diary

import java.time.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class DailyReportCacheTest {
    private val c get()=RuntimeEnvironment.getApplication()
    @Before fun setup(){c.getSharedPreferences("diary",0).edit().putLong("data_revision",5L).putLong("cache_epoch",1L).commit();java.io.File(c.cacheDir,"recent-day-report-v1.bin").delete()}
    @Test fun cachedDayRequiresSameDatePolicyAndImportGeneration(){
        val date=LocalDate.of(2024,5,10);val policy=ListeningDayPolicy(ListeningDayMode.FIXED,4);val report=demoReport(date)
        DailyReportCache.write(c,date,policy,report,5L)
        val loaded=DailyReportCache.read(c,date,policy)
        Assert.assertNotNull(loaded);Assert.assertEquals(report.plays,loaded!!.plays);Assert.assertEquals(report.history,loaded.history)
        Assert.assertNull(DailyReportCache.read(c,date.plusDays(1),policy))
        Assert.assertNull(DailyReportCache.read(c,date,ListeningDayPolicy()))
        c.getSharedPreferences("diary",0).edit().putLong("data_revision",6L).commit()
        Assert.assertNotNull(DailyReportCache.read(c,date,policy)) // sync may advance while the snapshot is provisional
        DailyReportCache.write(c,date,policy,report,5L)
        c.getSharedPreferences("diary",0).edit().putLong("cache_epoch",2L).commit()
        Assert.assertNull(DailyReportCache.read(c,date,policy))
    }
    @Test fun datesAndPeriodsKeepIndependentPersistentSnapshots(){
        val first=LocalDate.of(2024,5,10);val second=first.minusDays(2);val policy=ListeningDayPolicy()
        val a=demoReport(first);val b=demoReport(second);val week=periodDemoReport(first,DiaryRange.WEEK)
        DailyReportCache.write(c,first,policy,a,5)
        DailyReportCache.write(c,second,policy,b,5)
        DailyReportCache.write(c,first,policy,week,5,DiaryRange.WEEK)
        Assert.assertEquals(a.plays,DailyReportCache.read(c,first,policy)!!.plays)
        Assert.assertEquals(b.from,DailyReportCache.read(c,second,policy)!!.from)
        Assert.assertEquals(week.from,DailyReportCache.read(c,first,policy,DiaryRange.WEEK)!!.from)
        Assert.assertEquals(week.ms,DailyReportCache.read(c,first,policy,DiaryRange.WEEK)!!.ms)
        Assert.assertEquals(week.ms,DailyReportCache.read(c,first.plusDays(1),policy,DiaryRange.WEEK)!!.ms)
        Assert.assertTrue(java.io.File(c.filesDir,"report-snapshots-v2").listFiles()!!.size>=3)
    }
    @Test fun corruptedCacheDoesNotPreventStartup(){
        java.io.File(c.cacheDir,"recent-day-report-v1.bin").writeBytes(byteArrayOf(1,2,3))
        Assert.assertNull(DailyReportCache.read(c,LocalDate.now(),ListeningDayPolicy()))
    }
}
