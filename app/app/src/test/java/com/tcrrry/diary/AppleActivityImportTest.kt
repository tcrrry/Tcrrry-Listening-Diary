package com.tcrrry.diary
import java.io.*
import java.time.*
import java.util.zip.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class AppleActivityImportTest {
    private val day=LocalDate.of(2026,10,26)
    private lateinit var db:DiaryDatabase
    @Before fun setup(){val c=RuntimeEnvironment.getApplication();val field=DiaryDatabase::class.java.getDeclaredField("instance");field.isAccessible=true;field.set(null,null);for(name in listOf("diary.db","diary.db-wal","diary.db-shm"))File(c.filesDir,name).delete();c.getSharedPreferences("diary",0).edit().clear().commit();db=DiaryDatabase.get(c)}
    private val header=listOf("Event Type","Event ID","Song Name","Album Name","Container Artist Name","Play Duration Milliseconds","Media Duration In Milliseconds","Event Start Timestamp","Event End Timestamp","UTC Offset In Seconds","End Reason Type","Start Position In Milliseconds","End Position In Milliseconds","Media Type")
    private fun csv(rows:List<List<String>>,headers:List<String> = header):String=(listOf(headers)+rows).joinToString("\r\n"){r->r.joinToString(","){"\""+it.replace("\"","\"\"")+"\""}}
    private fun row(id:String="one",title:String="Song",start:String="2025-12-01T12:00:00Z",ms:Long=60000,reason:String="NATURAL_END_OF_TRACK",startPos:Long=0)=listOf("PLAY_END",id,title,"Album","",ms.toString(),"240000",start,Instant.parse(start).plusMillis(ms).toString(),"28800",reason,startPos.toString(),(startPos+ms).toString(),"AUDIO")
    private fun file(text:String):File=File.createTempFile("apple-test-",".csv",RuntimeEnvironment.getApplication().cacheDir).apply{writeText(text)}
    private fun plan(rows:List<List<String>>)=AppleActivityImport.read(file(csv(rows)),"Apple 试用")
    private fun seedNative(){val start=day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();db.ingest(JSONObject().put("sourceId","native").put("tracks",JSONArray().put(JSONObject().put("id","song").put("title","本机歌曲").put("artist","本机歌手").put("album","本机专辑").put("duration",240000))).put("spans",JSONArray().put(JSONObject().put("id",1).put("visit","native-visit").put("track","song").put("day",day.toString()).put("zone",ZoneId.systemDefault().id).put("start",start).put("end",start+60000).put("ms",60000))))}
    private fun nativeSnapshot()=db.backup().let{b->listOf("tracks","spans","events","event_sources").associateWith{b.getJSONArray(it).toString()}}
    @Test fun directDurationMatchesImportedAndDeduplicatedLedger(){
        val p=plan(listOf(row(),row("two",start="2025-12-01T15:00:00Z")));db.importApple(p)
        val date=LocalDate.of(2025,12,1);val c=RuntimeEnvironment.getApplication()
        for(policy in listOf(ListeningDayPolicy(),ListeningDayPolicy(ListeningDayMode.FIXED,6))){
            val b=bounds(date,DiaryRange.DAY)
            assertEquals(db.readReport(b,policy).ms,PeriodDurationReader.read(c,b,policy))
        }
    }
    @Test fun importedProjectionReuseMatchesFreshCalculationAfterLiveUpdates(){
        seedNative();db.importApple(plan(listOf(row(),row("two",start="2025-12-01T15:00:00Z"))))
        val importedField=DiaryDatabase::class.java.getDeclaredField("importedProjection").apply{isAccessible=true}
        val start=day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun update(id:Int,ms:Long,title:String="本机歌曲")=JSONObject().put("sourceId","native")
            .put("tracks",JSONArray().put(JSONObject().put("id","song").put("title",title).put("artist","本机歌手").put("album","本机专辑").put("duration",240000)))
            .put("spans",JSONArray().put(JSONObject().put("id",id).put("visit","native-$id").put("track","song").put("day",day.toString()).put("zone",ZoneId.systemDefault().id).put("start",start+id*120000).put("end",start+id*120000+ms).put("ms",ms)))
        for(policy in listOf(ListeningDayPolicy(),ListeningDayPolicy(ListeningDayMode.FIXED,4),ListeningDayPolicy(ListeningDayMode.AUTO))){
            db.readReport(null,policy);val cache=importedField.get(db)
            db.ingest(update(2,30000));val reused=db.readReport(null,policy)
            if(policy.mode!=ListeningDayMode.AUTO)assertSame(cache,importedField.get(db))
            importedField.set(db,null)
            val key=DiaryDatabase::class.java.getDeclaredField("projectionKey").apply{isAccessible=true};key.set(db,null)
            val fresh=db.readReport(null,policy)
            assertEquals(fresh.ms,reused.ms);assertEquals(fresh.plays,reused.plays);assertEquals(fresh.dayTotals,reused.dayTotals)
            assertEquals(fresh.segments.map{it.start to it.ms},reused.segments.map{it.start to it.ms})
        }
        db.readReport(null);val previous=importedField.get(db);db.ingest(update(3,30000,"Changed title"));db.readReport(null);assertNotSame(previous,importedField.get(db))
    }
    @Test fun weeklyDiscoveriesUseFirstKnownListeningDayAcrossHistory(){
        val old=plan(listOf(row(id="old",title="Song")));db.importApple(old)
        val start=LocalDate.of(2025,12,8).atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val currentTrack=old.rows.single().track.id
        val batch=JSONObject().put("sourceId","native").put("tracks",JSONArray().put(JSONObject().put("id",currentTrack).put("title","Song").put("artist","Artist").put("album","Album").put("duration",240000)).put(JSONObject().put("id","new").put("title","New").put("artist","New Artist").put("album","New Album").put("duration",240000)))
        val spans=JSONArray();for((i,id) in listOf(currentTrack,"new").withIndex())spans.put(JSONObject().put("id",i+1).put("visit","v$i").put("track",id).put("day","2025-12-08").put("zone","UTC").put("start",start+i*60000).put("end",start+(i+1)*60000).put("ms",60000));batch.put("spans",spans);db.ingest(batch)
        val result=db.weeklyNewIdentities(LocalDate.of(2025,12,8),ListeningDayPolicy(),setOf(currentTrack,"new"),setOf("New Artist"))
        assertFalse(currentTrack in result.first);assertTrue("new" in result.first);assertTrue("New Artist" in result.second)
        val month=db.periodNewIdentities(LocalDate.of(2025,12,8),DiaryRange.MONTH,ListeningDayPolicy(),setOf(currentTrack,"new"),setOf("New Artist"))
        assertTrue(currentTrack in month.first);assertTrue("new" in month.first)
        val year=db.periodNewIdentities(LocalDate.of(2026,1,1),DiaryRange.YEAR,ListeningDayPolicy(),setOf(currentTrack,"new"),setOf("New Artist"))
        assertTrue(year.first.isEmpty());assertTrue(year.second.isEmpty())
    }
    @Test fun quotedCommasQuotesNewlinesBomAndHeaderOrderAreHandled(){val title="Song, \"live\"\nPart 2";val p=AppleActivityImport.read(file("\uFEFF"+csv(listOf(row(title=title)),header.map{it.lowercase()})),"UTF8");assertEquals(title,p.rows.single().track.title);assertEquals(60000,p.ms)}
    @Test fun reorderedCsvStartingWithPlayDurationIsNotMistakenForZip(){val order=listOf(5)+header.indices.filter{it!=5};val h=order.map{header[it]};val source=row();val p=AppleActivityImport.read(file(csv(listOf(order.map{source[it]}),h)),"reordered");assertEquals(60000,p.ms);assertEquals(1,p.rows.size)}
    @Test fun ignoresStartsLyricsVideosAndDuplicatesWithoutInventingMetadata(){val a=row();val p=plan(listOf(a,a,a.toMutableList().apply{this[0]="PLAY_START"},a.toMutableList().apply{this[0]="LYRIC_DISPLAY"},row("video").toMutableList().apply{this[13]="VIDEO"}));assertEquals(1,p.rows.size);assertEquals(1,p.duplicates);assertEquals(3,p.ignored);assertEquals("未知歌手",p.rows.single().track.artist)}
    @Test fun pauseResumeSharesVisitWhileNaturalRepeatCreatesAnother(){val p=plan(listOf(row("a",reason="PLAYBACK_MANUALLY_PAUSED",ms=30000),row("b",start="2025-12-01T12:02:00Z",startPos=30000),row("c",start="2025-12-01T12:04:00Z")));assertEquals(p.rows[0].visit,p.rows[1].visit);assertNotEquals(p.rows[1].visit,p.rows[2].visit)}
    @Test fun missingStartUsesEndAndDurationButMissingBothCannotInventDate(){val a=row().toMutableList().apply{this[7]="0001-01-01T00:00:00Z"};val p=plan(listOf(a));assertTrue(p.rows.single().inferred);val bad=a.toMutableList().apply{this[8]=""};assertThrows(IllegalArgumentException::class.java){plan(listOf(bad))}}
    @Test fun officialOuterMediaServicesZipPreservesLibraryAndRemovesTemporaryFile(){
        val root=RuntimeEnvironment.getApplication().cacheDir
        val inner=File.createTempFile("media-services-",".zip",root)
        ZipOutputStream(inner.outputStream()).use{z->
            z.putNextEntry(ZipEntry("Apple_Media_Services/Apple Music Activity/Apple Music Play Activity.csv"));z.write(csv(listOf(row())).toByteArray());z.closeEntry()
            z.putNextEntry(ZipEntry("Apple_Media_Services/Apple Music Activity/Apple Music Library Tracks.json"));z.write(JSONArray().put(JSONObject().put("Title","Song").put("Artist","Artist").put("Album","Album").put("Apple Music Track Identifier",123456789).put("Track Duration",240000)).toString().toByteArray());z.closeEntry()
        }
        val direct=AppleActivityImport.read(inner,"inner")
        val outer=File.createTempFile("official-outer-",".zip",root)
        ZipOutputStream(outer.outputStream()).use{z->z.putNextEntry(ZipEntry("第1部分/Apple_Media_Services.zip"));inner.inputStream().use{it.copyTo(z)};z.closeEntry()}
        val before=root.listFiles()!!.map{it.name}.toSet()
        val result=AppleActivityImport.read(outer,"outer")
        assertEquals(direct.fingerprint,result.fingerprint);assertEquals(direct.rows,result.rows)
        assertEquals("Artist",result.rows.single().track.artist)
        assertEquals(before,root.listFiles()!!.map{it.name}.toSet())
    }
    @Test fun gameCenterOnlyPartExplainsWhichPartToChoose(){
        val f=File.createTempFile("game-center-",".zip",RuntimeEnvironment.getApplication().cacheDir)
        ZipOutputStream(f.outputStream()).use{z->z.putNextEntry(ZipEntry("Game Center/Game Center Data.json"));z.write("{}".toByteArray());z.closeEntry()}
        val e=assertThrows(IllegalArgumentException::class.java){AppleActivityImport.read(f,"part2")}
        assertTrue(e.message!!.contains("另一部分"))
    }
    @Test fun actualOfficialOuterExportCanBeReadWithoutImporting(){
        val path=System.getProperty("diary.apple.fixture");Assume.assumeTrue(path!=null)
        val plan=AppleActivityImport.read(File(path),"Official outer ZIP")
        assertTrue(plan.rows.isNotEmpty());assertTrue(plan.ms>0)
        println("OFFICIAL_OUTER_CHECK rows=${plan.rows.size} from=${plan.from} to=${plan.to} matchedArtist=${plan.rows.size-plan.unknownArtist}")
    }
    @Test fun laterNativePlaybackRecoversUnknownImportedAuthorWithoutRewritingLedger(){
        val source=plan(listOf(row())).let{p->p.copy(rows=p.rows.map{it.copy(track=it.track.copy(title="本机歌曲",artist="未知歌手",album="本机专辑",duration=240000))})}
        db.importApple(source)
        assertEquals("未知歌手",db.readReport(null).history.single().artist)
        seedNative()
        assertEquals("本机歌手",db.readReport(null).history.first{it.imported}.artist)
        assertEquals(120000,db.readReport(null).ms)
        assertEquals("未知歌手",db.backup().getJSONArray("import_tracks").getJSONObject(0).getString("artist"))
    }
    @Test fun metadataZipAndNestedOfficialPackageAreRecognized(){val root=RuntimeEnvironment.getApplication().cacheDir;val inner=File.createTempFile("Apple Music Activity-",".zip",root);ZipOutputStream(inner.outputStream()).use{z->fun add(name:String,data:String){z.putNextEntry(ZipEntry(name));z.write(data.toByteArray());z.closeEntry()};add("Apple Music Activity/Apple Music Play Activity.csv",csv(listOf(row())));add("Apple Music Activity/Apple Music Library Tracks.json",JSONArray().put(JSONObject().put("Title","Song").put("Artist","Artist").put("Album","Album").put("Apple Music Track Identifier",123456789).put("Track Duration",240000)).toString())};val outer=File.createTempFile("official-",".zip",root);ZipOutputStream(outer.outputStream()).use{z->z.putNextEntry(ZipEntry("Apple Music Activity.zip"));inner.inputStream().use{it.copyTo(z)};z.closeEntry()};val p=AppleActivityImport.read(outer,"官方包");assertEquals("Artist",p.rows.single().track.artist);assertEquals("123456789",p.rows.single().track.id);assertEquals(p.fingerprint,plan(listOf(row())).fingerprint)}
    @Test fun dailyAggregateAloneIsRejected(){assertThrows(IllegalArgumentException::class.java){AppleActivityImport.read(file("Date Played,Track Description,Play Count\n20251201,Song,2"),"daily")}}
    @Test fun removalPreservesNativeTablesEvenWithCollidingSongId(){seedNative();val before=nativeSnapshot();val p=plan(listOf(row())).let{it.copy(rows=it.rows.map{r->r.copy(track=r.track.copy(id="song",title="导入标题"))})};assertTrue(db.importApple(p));assertFalse(db.importApple(p));assertEquals(60000,db.report(day,DiaryRange.DAY).toReport().ms);assertEquals("本机歌曲",db.report(day,DiaryRange.DAY).toReport().history.single().title);assertEquals(120000,db.allReport().toReport().ms);val importedDate=LocalDate.parse("2025-12-01");val versioned=db.readReportVersioned(bounds(importedDate,DiaryRange.DAY),ListeningDayPolicy());val context=RuntimeEnvironment.getApplication();DailyReportCache.write(context,importedDate,ListeningDayPolicy(),versioned.first,versioned.second);assertNotNull(DailyReportCache.read(context,importedDate,ListeningDayPolicy()));db.removeApple(p.fingerprint);assertNull(DailyReportCache.read(context,importedDate,ListeningDayPolicy()));assertEquals(before,nativeSnapshot());assertEquals(60000,db.allReport().toReport().ms);assertTrue(db.appleBatches().isEmpty());assertTrue(db.importApple(p))}
    @Test fun importedUnknownAuthorsNeverInventAnArtistOrChangeNativeCounts(){seedNative();val original=db.report(day,DiaryRange.DAY).toReport();val p=plan(listOf(row("a",title="One"),row("b",title="Two",start="2025-12-02T12:00:00Z")));db.importApple(p);val all=db.allReport().toReport();assertEquals(original.artists,all.artists);assertEquals(0,multiSongArtists(all.history));assertTrue(all.history.any{it.imported});db.removeApple(p.fingerprint);assertEquals(original.artists,db.report(day,DiaryRange.DAY).toReport().artists);assertEquals(original.tracks,db.report(day,DiaryRange.DAY).toReport().tracks)}
    @Test fun overlappingBatchesDeduplicateAndRemovingOneRevealsOther(){val first=plan(listOf(row("a")));val second=plan(listOf(row("a"),row("b",start="2025-12-02T12:00:00Z")));db.importApple(first);db.importApple(second);assertEquals(120000,db.allReport().toReport().ms);db.removeApple(first.fingerprint);assertEquals(120000,db.allReport().toReport().ms);db.removeApple(second.fingerprint);assertEquals(0,db.allReport().toReport().ms)}
    @Test fun midnightSplitConservesDurationAndDoesNotCountSkipTwice(){val p=plan(listOf(row(start="2025-12-01T15:59:50Z",ms=20000,reason="TRACK_SKIPPED_FORWARDS")));db.importApple(p);val r=db.report(LocalDate.of(2025,12,1),DiaryRange.MONTH).toReport();assertEquals(20000,r.ms);assertEquals(2,r.segments.size);assertEquals(1L,r.manualSkips);assertEquals(20000,r.segments.sumOf{it.ms})}
    @Test fun cancellationRollsBackTheWholeBatch(){seedNative();val before=nativeSnapshot();assertThrows(IllegalStateException::class.java){db.importApple(plan(listOf(row()))){throw IllegalStateException("cancel")}};assertTrue(db.appleBatches().isEmpty());assertEquals(before,nativeSnapshot());assertEquals(60000,db.allReport().toReport().ms)}
    @Test fun backupRestoreKeepsImportsRemovableAndSourceTablesSeparate(){seedNative();val before=nativeSnapshot();val p=plan(listOf(row()));db.importApple(p);val backup=db.backup();db.removeApple(p.fingerprint);db.ingest(backup,true);assertEquals(120000,db.allReport().toReport().ms);db.removeApple(p.fingerprint);assertEquals(before,nativeSnapshot())}
    @Test fun actualExportCanImportAndBeRemovedWithoutChangingNativeData(){val path=System.getProperty("diary.apple.fixture");Assume.assumeTrue(path!=null);seedNative();val before=nativeSnapshot();val begin=System.nanoTime();val p=AppleActivityImport.read(File(path),"Apple 官方历史");assertTrue(p.rows.size>60000);assertTrue(p.unknownArtist<p.rows.size);assertTrue(db.importApple(p));val typedStart=System.nanoTime();val typed=db.readReport(null);assertEquals(p.ms+60000,typed.ms)
        for(date in listOf("2021-02-07","2025-12-01","2025-12-19","2024-03-16","2020-01-01")){val selected=db.readReport(bounds(LocalDate.parse(date),DiaryRange.DAY));assertEquals(date,selected.from);assertTrue(selected.segments.all{it.listeningDay==date})}
        val checkDate=LocalDate.parse("2024-05-10");val yearly=db.readReport(bounds(checkDate,DiaryRange.YEAR));val dayReport=db.readReport(bounds(checkDate,DiaryRange.DAY));val daySongs=dailyPeriodSongs(yearly)[checkDate].orEmpty();val expected=sortedPlays(dayReport.history.filter{!it.imported||it.title!="未知歌曲"},SortOrder.COUNT)
        assertEquals(expected.associate{it.id to it.effective},daySongs.associate{it.id to it.effective});assertEquals(dayReport.ms,yearly.dayTotals[checkDate.toString()])
        val summary=SummaryFacts(typed);assertEquals(typed.ms,summary.totals.values.sum());val sink=object:java.io.Writer(){var count=0L;override fun write(c:CharArray,o:Int,n:Int){count+=n};override fun flush(){};override fun close(){}};db.writeBackup(sink);assertTrue(sink.count>1000000);println("typed report+dates+summary+stream seconds="+(System.nanoTime()-typedStart)/1e9)
        val all=db.allReport().toReport();assertEquals(p.ms+60000,all.ms);assertEquals(all.ms,all.segments.sumOf{it.ms});assertTrue(all.history.any{it.artist!="未知歌手"});assertFalse(db.importApple(p));db.removeApple(p.fingerprint);assertEquals(before,nativeSnapshot());assertEquals(60000,db.allReport().toReport().ms);val result=JSONObject().put("rows",p.rows.size).put("ms",p.ms).put("from",p.from).put("to",p.to).put("ignored",p.ignored).put("duplicates",p.duplicates).put("unknown_artist",p.unknownArtist).put("unknown_album",p.unknownAlbum).put("inferred_starts",p.inferredStarts).put("elapsed_seconds",(System.nanoTime()-begin)/1e9).put("native_data_preserved",true);System.getProperty("diary.preview.dir")?.let{File(it).mkdirs();File(it,"apple-source-analysis.json").writeText(result.toString(2))};println(result)}
    @Test fun typedReportsSwitchDatesAndEmptyDayNeverShowsPreviousData(){
        seedNative();val p=plan(listOf(row("a"),row("b",title="Other",start="2025-12-02T12:00:00Z",ms=120000)));db.importApple(p)
        for(day in listOf("2025-12-01","2025-12-02","2024-01-01","2025-12-01")){
            val date=LocalDate.parse(day);val legacy=db.report(date,DiaryRange.DAY).toReport();val typed=db.readReport(bounds(date,DiaryRange.DAY))
            assertEquals(day,typed.from);assertEquals(legacy.ms,typed.ms);assertEquals(legacy.plays,typed.plays);assertEquals(legacy.history.map{it.title},typed.history.map{it.title})
        }
        val all=db.readReport(null);assertEquals(240000,all.ms);assertEquals(db.allReport().toReport().ms,all.ms)
        db.removeApple(p.fingerprint);assertEquals(60000,db.readReport(null).ms)
    }
    @Test fun typedPolicyChangesDatesWithoutChangingTheLedgerOrSourceTimes(){
        val p=plan(listOf(row(start="2025-12-01T17:00:00Z")));db.importApple(p);val before=db.backup().toString()
        val fixed=ListeningDayPolicy(ListeningDayMode.FIXED,4)
        assertEquals(0,db.readReport(bounds(LocalDate.of(2025,12,1),DiaryRange.DAY)).ms)
        assertEquals(60000,db.readReport(bounds(LocalDate.of(2025,12,1),DiaryRange.DAY),fixed).ms)
        assertEquals(0,db.readReport(bounds(LocalDate.of(2025,12,2),DiaryRange.DAY),fixed).ms)
        assertEquals(p.rows.single().start,db.readReport(null,fixed).history.single().start)
        assertEquals(db.readReport(null,fixed).ms,SummaryFacts(db.readReport(null,fixed)).within(LocalDate.of(2025,12,1),DiaryRange.DAY).report.ms)
        assertEquals(JSONObject(before).getJSONArray("import_spans").toString(),db.backup().getJSONArray("import_spans").toString())
    }
    @Test fun streamingBackupIsValidAndKeepsImportsRemovable(){
        seedNative();val p=plan(listOf(row()));db.importApple(p)
        val writer=java.io.StringWriter();db.writeBackup(writer);val backup=JSONObject(writer.toString())
        assertEquals(db.backup().getJSONArray("import_spans").toString(),backup.getJSONArray("import_spans").toString())
        db.removeApple(p.fingerprint);db.ingest(backup,true);assertEquals(120000,db.readReport(null).ms);db.removeApple(p.fingerprint);assertEquals(60000,db.readReport(null).ms)
    }

    @Test fun verifiedArtistOverlayUpdatesEveryReportAndKeepsRawLedgerUntouched(){
        seedNative();db.ingest(JSONObject().put("sourceId","native").put("tracks",JSONArray().put(JSONObject().put("id","song").put("title","本机歌曲").put("artist","旺仔小乔").put("album","本机专辑").put("duration",240000))))
        val p=plan(listOf(row("meta-a",title="补资料回归歌曲"),row("meta-b",title="补资料回归歌曲",start="2025-12-02T12:00:00Z")));db.importApple(p)
        val before=db.backup();val date=LocalDate.of(2025,12,1);val periods=DiaryRange.entries.associateWith{db.readReport(bounds(date,it))};assertEquals(0,periods.getValue(DiaryRange.DAY).artists)
        val track=p.rows.first().track;val record=JSONObject().put("trackId",1872948412L).put("artistId",1234567).put("trackName",track.title).put("artistName","旺仔小喬").put("collectionName",track.album).put("trackTimeMillis",track.duration).put("artworkUrl100","https://is1-ssl.mzstatic.com/metadata/100x100bb.jpg")
        assertTrue(TrackMetadata.shared(db.images).put(track.id,AppleTrackImages.Hint(track.title,track.artist,track.album,track.duration),record))
        for(range in DiaryRange.entries){val result=db.readReport(bounds(date,range));val old=periods.getValue(range);assertEquals(old.ms,result.ms);assertEquals(old.plays,result.plays);assertEquals(old.tracks,result.tracks);assertEquals(1,result.artists);assertTrue(result.history.all{it.artist=="旺仔小乔"})}
        val all=db.readReport(null);assertEquals(1,all.artists);assertEquals("旺仔小乔",SummaryFacts(all).artists.single().name);assertEquals("旺仔小乔",libraryGroups(all,true).single().title)
        for(table in listOf("tracks","spans","events","import_batches","import_tracks","import_spans"))assertEquals(before.getJSONArray(table).toString(),db.backup().getJSONArray(table).toString())
        db.removeApple(p.fingerprint);assertEquals(60000,db.readReport(null).ms);assertEquals(1,db.readReport(null).history.size)
    }
    @Test fun verifiedAlbumOverlayEntersAlbumRankingWithoutChangingSourceOrPlays(){
        val data=row(title="补专辑回归歌曲").toMutableList().apply{this[3]="";this[4]="Known Artist"};val p=plan(listOf(data));db.importApple(p);val date=LocalDate.of(2025,12,1);val original=db.readReport(bounds(date,DiaryRange.DAY));assertTrue(reportAlbums(original).isEmpty())
        val track=p.rows.single().track;val record=JSONObject().put("trackId",12345678).put("trackName",track.title).put("artistName",track.artist).put("collectionName","Matched Album").put("trackTimeMillis",track.duration).put("artworkUrl100","https://is1-ssl.mzstatic.com/album/100x100bb.jpg")
        assertTrue(TrackMetadata.shared(db.images).put(track.id,AppleTrackImages.Hint(track.title,track.artist,track.album,track.duration),record))
        val updated=db.readReport(bounds(date,DiaryRange.DAY));assertEquals("Matched Album",reportAlbums(updated).single().title);assertEquals(original.ms,updated.ms);assertEquals(original.plays,updated.plays);assertEquals("",db.backup().getJSONArray("import_tracks").getJSONObject(0).getString("album"))
    }

}
