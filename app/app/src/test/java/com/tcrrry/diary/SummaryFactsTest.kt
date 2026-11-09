package com.tcrrry.diary
import java.time.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class) @org.robolectric.annotation.Config(sdk=[35])
class SummaryFactsTest {
    private fun p(day:LocalDate,id:String="a",artist:String="Artist",album:String="Album",ms:Long=120000):Play{val start=day.atTime(12,0).toInstant(ZoneOffset.UTC).toEpochMilli();return Play(id,"$day:$id",id,artist,album,start,start+ms,ms,240000,"UTC",effective=1)}
    private fun facts(vararg p:Play)=SummaryFacts(DiaryReport(ms=p.sumOf{it.ms},history=p.toList(),segments=p.toList()))
    @Test fun quarterlyAggregationAndLowScaleKeepActualShares(){
        val monthly=preferencePoints(facts(p(LocalDate.of(2025,1,1),"a"),p(LocalDate.of(2025,3,1),"b"),p(LocalDate.of(2025,4,1),"c")),emptyMap())
        val quarters=preferenceAggregate(monthly,true)
        assertEquals(listOf(1,4),quarters.map{it.month.monthValue});assertEquals(listOf(240000L,120000L),quarters.map{it.total})
        val shares=mapOf("A" to 90L,"B" to 8L,"C" to 2L);assertEquals(.1f,preferenceLowCeiling(listOf(shares),listOf("A","B","C")),.001f)
        assertEquals(100L,preferenceVisibleTotal(shares,listOf("A","B","C")))
        val axis=preferenceAxisRange(listOf(mapOf("A" to 66L,"B" to 16L,"C" to 16L,"D" to 2L)),listOf("A","B","C","D"),"A")!!
        assertEquals(.1f,axis.low,.001f);assertEquals(.2f,axis.high,.001f)
        assertEquals(.35f,preferenceAxisPosition(.1f,axis,true),.001f)
        assertEquals(.175f,preferenceAxisPosition(.05f,axis,true),.001f)
        assertEquals(.85f,preferenceAxisPosition(.2f,axis,true),.001f)
        assertEquals(.2f,preferenceAxisPosition(.2f,axis,false),.001f)
    }
    @Test fun visiblePreferenceSharesExcludeUnknownWithoutChangingUnderlyingTime(){
        val map=mapOf("J-Pop" to 60L,"R&B" to 30L,"未匹配" to 10L)
        val layers=preferenceVisibleLayers(listOf(map),1)
        assertEquals(listOf("J-Pop","R&B"),layers)
        assertEquals(90L,preferenceVisibleTotal(map,layers));assertEquals(100L,map.values.sum())
    }
    @Test fun preferenceTimelinePreservesEmptyMonthsAndClassifiesDiscovery(){
        val f=facts(p(LocalDate.of(2025,1,1),"a"),p(LocalDate.of(2025,3,1),"a"),p(LocalDate.of(2025,6,1),"a"))
        val points=preferencePoints(f,emptyMap());assertEquals(6,points.size)
        assertEquals(120000L,points[0].exploration["探索新歌"]);assertEquals(0L,points[1].total)
        assertEquals(120000L,points[2].exploration["持续聆听"]);assertEquals(120000L,points[5].exploration["持续聆听"])
        assertEquals(points.sumOf{it.total},preferenceByYear(points).sumOf{it.total})
        val old=preferencePoints(facts(p(LocalDate.of(2025,1,1),"b"),p(LocalDate.of(2025,6,1),"b")),emptyMap())
        assertEquals(120000L,old.last().exploration["回顾老歌"])
    }
    @Test fun stackedYearGenresConserveAllListeningTimeIncludingUnknown(){
        val a=p(LocalDate.of(2024,1,1),"a").copy(title="踊り子")
        val b=p(LocalDate.of(2026,1,1),"b",artist="未知歌手").copy(title="未知歌曲",imported=true)
        val values=annualGenrePoints(facts(a,b),emptyMap())
        assertEquals(listOf(2024,2025,2026),values.map{it.year})
        assertEquals(120000L,values.first().genres["J-Pop"])
        assertEquals(0L,values[1].total);assertEquals(120000L,values.last().genres["未匹配"])
        assertEquals(a.ms+b.ms,values.sumOf{it.total})
    }
    @Test fun yearlyRingsCountIdentitiesWithinEachListeningYear(){
        val a=p(LocalDate.of(2025,12,31),"a","Artist")
        val b=p(LocalDate.of(2026,1,1),"a","Artist")
        val c=p(LocalDate.of(2026,1,2),"b","Other")
        val unknown=p(LocalDate.of(2026,1,3),"x","未知歌手").copy(title="未知歌曲",imported=true)
        val rings=allYearSlices(facts(a,b,c,unknown).days)
        assertEquals(listOf(2025,2026),rings.map{it.year})
        assertEquals(listOf(1,2),rings.map{it.artists});assertEquals(listOf(1,2),rings.map{it.songs})
        assertEquals(listOf(120000L,360000L),rings.map{it.duration})
    }
    @Test fun monthlyTrailUsesSeparateSongAlbumAndArtistLeaders(){
        val d=LocalDate.of(2026,2,1)
        val f=facts(p(d,"a","Artist A","Album A",180000),p(d.plusDays(1),"b","Artist B","Album B",120000),p(d.plusDays(2),"c","Artist B","Album B",120000))
        val entry=allTrailEntries(f).single()
        assertEquals("a",entry.song?.id);assertEquals("Album B",entry.album?.album);assertEquals("Artist B",entry.artist)
        assertEquals(180000L,entry.songMs);assertEquals(240000L,entry.albumMs);assertEquals(240000L,entry.artistMs)
    }
    @Test fun favoritesFollowListeningMonthsAndMergeCheckpoints(){
        val first=p(LocalDate.of(2025,12,31),"a",ms=20000)
        val more=first.copy(start=first.end,end=first.end+20000)
        val second=p(LocalDate.of(2026,2,1),"b",ms=90000)
        val facts=facts(first,more,second)
        assertEquals(listOf(YearMonth.of(2025,12),YearMonth.of(2026,2)),facts.monthlyFavorites.map{it.first})
        assertEquals(listOf("a","b"),facts.monthlyFavorites.map{it.second.id})
        assertEquals(1L,facts.monthlyFavorites.first().second.effective)
    }
    @Test fun missingArtistNamesKeepListeningRecordsWithoutInventingAnArtist(){val f=facts(p(LocalDate.of(2026,10,25),artist=""));assertTrue(f.artists.isEmpty());assertEquals(120000,f.within(LocalDate.of(2026,10,25),DiaryRange.MONTH).report.ms)}
    @Test fun uncertainMultiDiscCountDoesNotBecomeAnAlbumCoverageTotal(){val row=org.json.JSONObject().put("trackId","a").put("collectionId","b").put("trackCount",12).put("discCount",2);assertEquals(0,CatalogFact.parse(row).total)}
    @Test fun profileKeepsPreviousYearsAndCrossMonthReturningSongs(){val f=facts(p(LocalDate.of(2025,12,25)),p(LocalDate.of(2026,2,1)));assertEquals(2,f.artistDays("Artist"));assertEquals(2,f.songMonths("a"));assertEquals(3,f.months.size);assertTrue(f.months[YearMonth.of(2026,1)].isNullOrEmpty());assertEquals(100,f.share(240000))}
    @Test fun stageUsesOnlyItsCalendarPeriodWithoutChangingLifetime(){val f=facts(p(LocalDate.of(2025,12,25)),p(LocalDate.of(2026,10,25)),p(LocalDate.of(2026,10,26)));val stage=f.within(LocalDate.of(2026,10,25),DiaryRange.WEEK);assertEquals(120000,stage.report.ms);assertEquals(1,stage.report.plays);assertEquals(360000,f.report.ms);assertEquals("2026-10-19",stage.report.from)}
    @Test fun midnightCreditIsConservedAndCountedInBothListeningDays(){val day=LocalDate.of(2026,10,31);val start=day.atTime(23,59).toInstant(ZoneOffset.UTC).toEpochMilli();val f=facts(p(day).copy(start=start,end=start+120000,ms=90001));assertEquals(2,f.artistDays("Artist"));assertEquals(45000,f.within(day,DiaryRange.MONTH).report.ms);assertEquals(45001,f.within(day.plusDays(1),DiaryRange.MONTH).report.ms);assertEquals(90001,f.totals.values.sum())}
    @Test fun splitCheckpointsDoNotInflatePlayCount(){val day=LocalDate.of(2026,10,25);val first=p(day,ms=20000);val second=first.copy(start=first.end,end=first.end+20000);val f=facts(first,second).within(day,DiaryRange.MONTH);assertEquals(1,f.report.history.size);assertEquals(1,f.report.plays);assertEquals(40000,f.report.ms)}
    @Test fun coverageRequiresTheSameKnownCatalogEditionForEveryHeardTrack(){val day=LocalDate.of(2026,10,25);val album=facts(p(day,"a"),p(day,"b")).albums.single();val a=CatalogFact("a","10","Album","Artist",12,"Pop");val b=a.copy(id="b");assertEquals(2 to 12,albumCoverage(album,mapOf("a" to a,"b" to b)));assertNull(albumCoverage(album,mapOf("a" to a)));assertNull(albumCoverage(album,mapOf("a" to a,"b" to b.copy(albumId="20"))));assertNull(albumCoverage(album,mapOf("a" to a.copy(total=1),"b" to b.copy(total=1))))}
    @Test fun matchingEditionDoesNotAcceptDifferentAlbumOrArtistNames(){val album=facts(p(LocalDate.of(2026,10,25))).albums.single();val a=CatalogFact("a","10","Album","Artist",12,"Pop");assertNull(albumCoverage(album,mapOf("a" to a.copy(album="Album Deluxe"))));assertNull(albumCoverage(album,mapOf("a" to a.copy(artist="Other"))));assertEquals(1 to 12,albumCoverage(album,mapOf("a" to a.copy(artist="ＡＲＴＩＳＴ"))))}
    @Test fun unnamedImportKeepsTimeButDoesNotBecomeAPreferenceArtist(){
        val row=p(LocalDate.of(2025,12,1),artist="未知歌手").copy(imported=true,title="未知歌曲",album="")
        val f=facts(row);assertEquals(row.ms,f.totals.values.sum());assertTrue(f.artists.isEmpty());assertTrue(f.bands().all{it.artists.isEmpty()});assertTrue(f.songs.isEmpty())
    }
    @Test fun mainstreamGenresAndCrossYearArtistBucketsAggregateCleanly(){
        val day=LocalDate.of(2025,1,15)
        val cpop=p(day,"c",artist="周杰伦",album="范特西")
        val jpop=p(day,"j",artist="Vaundy",album="strobo").copy(title="踊り子")
        val kpop=p(day,"k",artist="NewJeans",album="Get Up").copy(title="슈퍼샤이")
        assertEquals("华语流行",normalizeMusicGenre("Mandopop",cpop))
        assertEquals("J-Pop",normalizeMusicGenre("Anime",jpop))
        assertEquals("K-Pop",normalizeMusicGenre("Pop",kpop))
        assertEquals("R&B",normalizeMusicGenre("Contemporary R&B",cpop))
        assertEquals("电子乐",normalizeMusicGenre("Electronica",cpop))
        val multiYear=facts(p(LocalDate.of(2025,1,10),artist="Vaundy"),p(LocalDate.of(2026,6,10),artist="Vaundy"))
        val buckets=multiYear.artistTimelineBuckets("Vaundy")
        assertTrue(buckets.size in 1..9)
        assertTrue(buckets.any{it.yearBadge=="2025年"})
        assertTrue(buckets.any{it.yearBadge=="2026年"})
    }
}
