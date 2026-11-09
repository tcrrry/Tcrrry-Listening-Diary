package com.tcrrry.diary
import android.content.Context
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class DiaryDatabaseTest {
    lateinit var db:DiaryDatabase
    private val day=LocalDate.of(2026,10,25)
    @Before fun setup(){val c=RuntimeEnvironment.getApplication();val f=DiaryDatabase::class.java.getDeclaredField("instance");f.isAccessible=true;val old=f.get(null);if(old!=null){val d=DiaryDatabase::class.java.getDeclaredField("db");d.isAccessible=true;(d.get(old) as android.database.sqlite.SQLiteDatabase).close()};f.set(null,null);c.deleteDatabase("diary.db");java.io.File(c.filesDir,"diary.db").delete();java.io.File(c.filesDir,"diary.db-wal").delete();java.io.File(c.filesDir,"diary.db-shm").delete();c.getSharedPreferences("diary",Context.MODE_PRIVATE).edit().clear().commit();db=DiaryDatabase.get(c)}
    private fun payload(id:Long=1,ms:Long=30000,source:String="phone"):JSONObject {val start=day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();return JSONObject().put("sourceId",source).put("tracks",JSONArray().put(JSONObject().put("id","song").put("title","歌曲").put("artist","歌手").put("album","专辑").put("duration",240000))).put("spans",JSONArray().put(JSONObject().put("id",id).put("visit","v$id").put("track","song").put("day",day.toString()).put("zone","UTC").put("start",start).put("end",start+ms).put("ms",ms)))}
    private fun report()=db.report(day,DiaryRange.DAY).toReport()
    @Test fun dayPreviewMatchesCreditedRecordsAcrossFixedBoundary(){
        db.ingest(payload());val policy=ListeningDayPolicy(ListeningDayMode.FIXED,6);val previous=day.minusDays(1)
        val quick=db.readDayPreview(previous,policy)!!.first;val full=db.readReport(bounds(previous,DiaryRange.DAY),policy)
        assertEquals(full.ms,quick.ms);assertEquals(full.plays,quick.plays);assertEquals(full.tracks,quick.tracks);assertEquals(full.segments.map{it.ms},quick.segments.map{it.ms})
        assertNull(db.readDayPreview(day,policy));assertNull(db.readDayPreview(previous,ListeningDayPolicy(ListeningDayMode.AUTO)))
    }
    @Test fun durationIndexMatchesReportsAndInvalidatesOnImport(){
        val c=RuntimeEnvironment.getApplication();val policy=ListeningDayPolicy(ListeningDayMode.FIXED,6)
        db.ingest(payload());val b=bounds(day.minusDays(1),DiaryRange.DAY)
        val expected=db.readReport(b,policy).ms
        assertEquals(30000L,expected);assertEquals(expected,db.durationForBounds(b,policy))
        assertEquals(expected,DailyDurationCache.read(c,policy,b))
        assertNull(DailyDurationCache.read(c,ListeningDayPolicy(),b))
        db.ingest(payload(2),true)
        assertNull(DailyDurationCache.read(c,policy,b))
        assertEquals(db.readReport(b,policy).ms,db.durationForBounds(b,policy))
    }
    @Test fun directDurationMatchesLedgerForCrossBoundaryAndTimeZones(){
        val c=RuntimeEnvironment.getApplication()
        val batch=payload();val row=batch.getJSONArray("spans").getJSONObject(0)
        val start=java.time.Instant.parse("2026-10-25T05:30:00Z").toEpochMilli()
        row.put("zone","UTC").put("start",start).put("end",start+7200000).put("ms",7200000)
        db.ingest(batch)
        for(policy in listOf(ListeningDayPolicy(),ListeningDayPolicy(ListeningDayMode.FIXED,6))) {
            for(date in listOf(day.minusDays(1),day,day.plusDays(1))) {
                val b=bounds(date,DiaryRange.DAY)
                assertEquals(db.readReport(b,policy).ms,PeriodDurationReader.read(c,b,policy))
            }
        }
        assertNull(PeriodDurationReader.read(c,bounds(day,DiaryRange.DAY),ListeningDayPolicy(ListeningDayMode.AUTO)))
    }
    private fun eventBatch():JSONObject {val at=day.atTime(12,0).atZone(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli();return JSONObject().put("sourceId","phone").put("eventsSince",at-10000).put("events",JSONArray().put(JSONObject().put("event_id","event-1").put("day",day.toString()).put("zone","UTC").put("at",at).put("kind","next").put("from_track","A").put("to_track","B").put("position",60000).put("duration",240000)))}
    @Test fun realEventsAreIdempotentAndNeverChangeListeningTime(){db.ingest(payload());val total=report().ms;val batch=eventBatch();db.ingest(batch);db.ingest(batch);assertEquals(1L,report().manualSkips);assertEquals(total,report().ms);assertNull(db.report(day.minusDays(1),DiaryRange.DAY).toReport().manualSkips);assertEquals(1L,db.report(day,DiaryRange.MONTH).toReport().manualSkips)}
    @Test fun malformedEventCannotPartiallyCommitRecords(){val batch=payload().put("events",eventBatch().getJSONArray("events"));batch.getJSONArray("events").getJSONObject(0).put("kind","automatic");assertThrows(IllegalArgumentException::class.java){db.ingest(batch)};assertEquals(0,report().ms);assertNull(report().manualSkips)}
    @Test fun eventBackupImportPreservesIdentityAndObservationStart(){db.ingest(eventBatch());val before=report().manualSkipsSince;val backup=db.backup();db.ingest(backup,true);assertEquals(1L,report().manualSkips);assertEquals(before,report().manualSkipsSince)}
    @Test fun lifetimeReportIncludesRecordsBeforeCurrentYear(){
        db.ingest(payload())
        val old=payload(2);val row=old.getJSONArray("spans").getJSONObject(0);val date=day.minusYears(1);val start=date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        row.put("day",date.toString()).put("start",start).put("end",start+30000);db.ingest(old)
        assertEquals(2,db.allReport().toReport().history.size);assertEquals(1,db.report(day,DiaryRange.YEAR).toReport().history.size)
        assertEquals(date.toString(),db.allReport().toReport().from)
    }
    @Test fun onlyAcceptedLiveBatchesAdvanceSyncAcknowledgementIncludingEmptyHeartbeat(){
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("diary",0)
        db.ingest(payload(),true);assertEquals(0,prefs.getLong("sync_serial",0))
        db.ingest(payload());assertEquals(1,prefs.getLong("sync_serial",0))
        db.ingest(JSONObject().put("sourceId","phone").put("tracks",JSONArray()).put("spans",JSONArray()).put("status","已暂停"))
        assertEquals(2,prefs.getLong("sync_serial",0));assertEquals(30000,report().ms);assertEquals("已暂停",report().recordState)
        val bad=payload().apply{getJSONArray("spans").getJSONObject(0).put("ms",-1)}
        assertThrows(IllegalArgumentException::class.java){db.ingest(bad)};assertEquals(2,prefs.getLong("sync_serial",0))
    }
    @Test fun liveIdentitySurvivesEmptyHeartbeatAndLegacySenderClearsBadge(){
        db.ingest(payload().put("nowTrackId","song").put("nowTitle","同名歌曲").put("status","正在记录"))
        assertEquals("song",report().nowTrackId)
        db.ingest(JSONObject().put("sourceId","phone").put("nowTrackId","song").put("status","已暂停 · 记录已保存"))
        assertEquals("song",report().nowTrackId);assertEquals("已暂停 · 记录已保存",report().recordState);assertEquals(30000,report().ms)
        db.ingest(JSONObject().put("sourceId","phone").put("nowTitle","同名歌曲").put("status","正在记录"))
        assertEquals("",report().nowTrackId)
    }
    @Test fun routeFieldsSurviveRetryAndBackup(){
        val batch=payload();batch.getJSONArray("spans").getJSONObject(0).put("device","Headphones").put("route_source","system_media").put("volume_pct",42)
        db.ingest(batch);db.ingest(batch)
        var segment=db.readReport(null).segments.single();assertEquals("Headphones",segment.device);assertEquals("system_media",segment.routeSource);assertEquals(42,segment.volumePct);assertEquals(30000,segment.ms)
        db.ingest(db.backup(),true);segment=db.readReport(null).segments.single();assertEquals("Headphones",segment.device);assertEquals(30000,segment.ms)
    }
    @Test fun deviceLabelUsesLatestSampleAndHidesMissingFields(){
        val base=Play("song","visit","Song","Artist","",1,2,1,100,"UTC")
        assertEquals("",latestDeviceLabel(listOf(base)))
        assertTrue(latestDeviceLabel(listOf(base.copy(device="A",volumePct=42))).contains("42%"))
        assertEquals("Very long device name\n42%",latestDeviceLabel(listOf(base.copy(device="Very long device name",routeSource="system_media",volumePct=42))))
        assertFalse(latestDeviceLabel(listOf(base.copy(device="A",end=2),base.copy(end=3,volumePct=0))).contains("A"))
    }
    @Test fun favoriteDeviceUsesCreditedTimeAndIgnoresMissingLabels(){
        val base=Play("song","visit","Song","Artist","",1,2,1,100,"UTC")
        assertEquals("A" to 100L,mostUsedDevice(listOf(base.copy(device="A",ms=60),base.copy(device="A",ms=40),base.copy(device="B",ms=90),base.copy(device="",ms=999))))
        assertNull(mostUsedDevice(listOf(base)))
    }
    @Test fun deviceDistributionIncludesEveryKnownDeviceByCreditedDuration(){
        val p=Play("s","v","Song","Artist","Album",1,101,100,100,"UTC")
        assertEquals(listOf("A" to 100L,"B" to 90L),deviceListeningTotals(listOf(p.copy(device="A",ms=60),p.copy(device="A",ms=40),p.copy(device="B",ms=90),p.copy(device="",ms=999))))
        assertTrue(deviceListeningTotals(listOf(p)).isEmpty())
    }
    @Test fun retryAndCheckpointAreIdempotent(){db.ingest(payload());db.ingest(payload());assertEquals(30000,report().ms);db.ingest(payload(ms=45000));assertEquals(45000,report().ms);assertEquals(1,report().history.size)}
    @Test fun staleBackupCannotShrinkLatestCheckpoint(){db.ingest(payload(ms=45000));db.ingest(payload(ms=30000),true);assertEquals(45000,report().ms)}
    @Test fun legacyImportIsAdoptedOnFirstSync(){val old=payload().apply{remove("sourceId")};db.ingest(old,true);db.ingest(payload(ms=45000));assertEquals(45000,report().ms);assertEquals(1,report().history.size)}
    @Test fun backupUsesStableSyncIdentity(){val backup=payload(id=7).apply{getJSONArray("spans").getJSONObject(0).put("sync_id",88)};db.ingest(backup,true);db.ingest(payload(id=88,ms=60000));assertEquals(60000,report().ms);assertEquals(1,report().history.size)}
    @Test fun invalidBatchRollsBack(){val bad=payload();bad.getJSONArray("spans").put(JSONObject(payload(2).getJSONArray("spans").getJSONObject(0).toString()).put("ms",999));assertThrows(IllegalArgumentException::class.java){db.ingest(bad)};assertEquals(0,report().history.size)}
    @Test fun importExportRoundTripDoesNotDuplicate(){db.ingest(payload());val b=db.backup();db.ingest(b,true);assertEquals(30000,report().ms)}
    @Test fun fullDayHasNoRecentLimit(){val p=payload();val spans=p.getJSONArray("spans");for(i in 2..400)spans.put(payload(i.toLong()).getJSONArray("spans").getJSONObject(0));db.ingest(p,true);assertEquals(400,report().history.size)}
    @Test fun effectiveCountsExcludeSkips(){db.ingest(payload(ms=12000));assertEquals(0,report().plays);assertEquals(12000,report().ms);db.ingest(payload(2,45000));assertEquals(1,report().plays)}
    @Test fun sortingKeepsSequenceAndGroupsOnlyWhenRequested(){db.ingest(payload());db.ingest(payload(2));val r=report();assertEquals(2,sortedPlays(r.history,SortOrder.SEQUENCE).size);val grouped=sortedPlays(r.history,SortOrder.COUNT);assertEquals(1,grouped.size);assertEquals(60000,grouped[0].ms);assertEquals(2,grouped[0].effective)}
    @Test fun calendarBoundsAreCalendarPeriods(){assertEquals(DayBounds("2026-10-19","2026-10-25"),bounds(day,DiaryRange.WEEK));assertEquals(DayBounds("2026-10-01","2026-10-31"),bounds(day,DiaryRange.MONTH));assertEquals(DayBounds("2026-01-01","2026-12-31"),bounds(day,DiaryRange.YEAR))}
    @Test fun unapprovedAppCannotPush(){val provider=Robolectric.buildContentProvider(BridgeProvider::class.java).create().get();assertThrows(SecurityException::class.java){provider.call("hello",null,null)}}
    @Test fun allNativeCoversInABatchAreSavedAndNoLongerRequested(){
        db.ingest(payload())
        assertTrue(JSONArray(db.neededArtwork()).objectsOrStrings().contains("song"))
        val jpeg="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j6VQAAAAASUVORK5CYII="
        val ids=listOf("song")+(1..5).map{"native-${System.nanoTime()}-$it"}
        val batch=payload();batch.put("art",JSONArray().apply{ids.forEach{id->put(JSONObject().put("id",id).put("jpeg",jpeg))}})
        db.ingest(batch)
        ids.forEach{id->assertTrue(java.io.File(db.images,ArtworkKeys.hash(id)+".jpg").isFile)}
        assertFalse(JSONArray(db.neededArtwork()).objectsOrStrings().contains("song"))
    }
    private fun JSONArray.objectsOrStrings()=(0 until length()).map{getString(it)}
    @Test fun providerRequiresApprovalAndPinsHostCertificate(){
        val c=RuntimeEnvironment.getApplication();val info=android.content.pm.PackageInfo().apply{packageName=BridgeIdentity.HOST;applicationInfo=android.content.pm.ApplicationInfo().apply{packageName=BridgeIdentity.HOST;uid=12345};signingInfo=android.content.pm.SigningInfo().also{Shadows.shadowOf(it).setSignatures(arrayOf(android.content.pm.Signature("12345678")))}}
        Shadows.shadowOf(c.packageManager).installPackage(info)
        org.robolectric.shadows.ShadowBinder.setCallingUid(12345)
        try {
            val provider=Robolectric.buildContentProvider(BridgeProvider::class.java).create().get()
            assertFalse(provider.call("hello",null,null).getBoolean("approved"))
            val request=android.os.Bundle().apply{putString("payload",payload().toString())}
            assertThrows(SecurityException::class.java){provider.call("upsert",null,request)}
            BridgeIdentity.approve(c)
            assertTrue(provider.call("hello",null,null).getBoolean("approved"))
            assertTrue(provider.call("upsert",null,request).getBoolean("accepted"))
            assertTrue(provider.call("upsert",null,request).getBoolean("accepted"));assertEquals(30000,report().ms)
            Shadows.shadowOf(info.signingInfo).setSignatures(arrayOf(android.content.pm.Signature("87654321")))
            assertFalse(BridgeIdentity.approved(c))
            assertThrows(SecurityException::class.java){provider.call("upsert",null,request)}
        }finally{org.robolectric.shadows.ShadowBinder.reset()}
    }
    @Test fun periodDailyTotalsAndArtistFirstUseTheWholeLedger(){db.ingest(payload());val result=db.report(day,DiaryRange.MONTH).toReport();assertEquals(30000L,result.dayTotals[day.toString()]);assertEquals(day.toString(),result.firstRecorded);assertEquals(day.toString(),result.artistFirst["歌手"])}
}
