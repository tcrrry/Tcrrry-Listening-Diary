package com.tcrrry.diary
import java.time.Instant
import org.junit.Test
import org.junit.Assert.*
class DailyListeningDetailsTest {
    @Test fun genreGroupingKeepsMajorSeparateAndCollectsAllSmallerGenres(){
        val genres=listOf(400L,300L,150L,60L,40L,30L,20L).mapIndexed{i,ms->GenreStat("风格$i",ms,emptyList(),1)}
        val (major,minor)=genreDistributionGroups(genres)
        assertEquals(listOf("风格0","风格1","风格2","风格3"),major.map{it.name})
        assertEquals(listOf("风格4","风格5","风格6"),minor.map{it.name})
        assertEquals(genres.sumOf{it.ms},(major+minor).sumOf{it.ms})
        val many=(1..10).map{GenreStat("曲风$it",100L,emptyList(),1)}
        val (large,rest)=genreDistributionGroups(many,maxRows=8,minShare=0.0)
        assertEquals(7,large.size);assertEquals(3,rest.size)
    }
    private fun play(id:String,at:String,ms:Long=60000,effective:Long=1,album:String="A"):Play {
        val start=Instant.parse(at).toEpochMilli()
        return Play(id,id,"Song $id","Artist",album,start,start+120000,ms,180000,"UTC",effective=effective)
    }
    @Test fun creditedHoursConserveTimeAcrossMidnight(){
        val p=play("a","2026-10-29T23:59:00Z")
        val hours=creditedDailyHours(listOf(p))
        assertEquals(60000L,hours.sum());assertEquals(30000L,hours[23]);assertEquals(30000L,hours[0])
        assertEquals(30000L,creditedDailyHours(listOf(p.copy(zone="Asia/Shanghai")))[7])
    }
    @Test fun dailyClockOmitsDateAndLifetimeClockKeepsIt(){
        val p=play("a","2026-10-29T13:20:00Z")
        assertEquals("13:20",dailyRecordTime(p,false,true))
        assertTrue(dailyRecordTime(p,false,false).contains("10月29日"))
    }
    @Test fun dailyGenresShareLifetimeCategoriesAndArtistFallback(){
        val known=play("known","2026-10-29T10:00:00Z")
        val missing=play("missing","2026-10-29T11:00:00Z")
        val catalog=mapOf("known" to CatalogFact("known","album","A","Artist",10,"Electronic"))
        val all=mainstreamGenreStats(listOf(known,missing),catalog)
        val daily=mainstreamGenreStats(listOf(missing),catalog,listOf(known,missing))
        assertEquals(listOf("电子乐"),all.map{it.name})
        assertEquals(all.single().name,daily.single().name)
        assertEquals(missing.ms,daily.single().ms)
        assertTrue(mainstreamGenreStats(listOf(missing.copy(title="未知歌曲",artist="未知歌手",imported=true)),emptyMap()).isEmpty())
        val kana=missing.copy(title="雨の音")
        assertEquals(normalizeMusicGenre(null,kana),mainstreamGenreStats(listOf(kana),emptyMap()).single().name)
    }
    @Test fun songsAndAlbumsCanSwitchBetweenTimeAndCount(){
        val first=play("first","2026-10-29T10:00:00Z",effective=1,album="Early")
        val last=play("last","2026-10-29T15:00:00Z",effective=3,album="Popular")
        val repeated=last.copy(visit="last-2",start=last.start+300000,end=last.end+300000,effective=2)
        val plays=listOf(last,first,repeated)
        assertEquals(listOf("last","first"),dailyArtistSongs(plays,false).map{it.id})
        assertEquals(listOf("first","last"),dailyArtistSongs(plays,true).map{it.id})
        assertEquals(listOf("Popular","Early"),dailyArtistAlbums(plays,false).map{it.title})
        assertEquals(listOf("Early","Popular"),dailyArtistAlbums(plays,true).map{it.title})
        assertEquals(5L,dailyArtistSongs(plays,false).first().effective)
        assertEquals(listOf("first","last"),dailyArtistSongs(plays,false,false).map{it.id})
        assertEquals(listOf("last","first"),dailyArtistSongs(plays,true,true).map{it.id})
        assertEquals(listOf("Early","Popular"),dailyArtistAlbums(plays,false,false).map{it.title})
        assertEquals(listOf("Popular","Early"),dailyArtistAlbums(plays,true,true).map{it.title})
    }
}
