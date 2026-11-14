package com.tcrrry.diary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class LanDatabaseIntegrationTest {
    private fun device():DiaryDatabase {
        val base=RuntimeEnvironment.getApplication();val id=UUID.randomUUID().toString()
        val files=java.nio.file.Files.createTempDirectory("diary-lan-").toFile()
        val context=object:ContextWrapper(base){
            override fun getFilesDir()=files
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=base.getSharedPreferences("$id-$name",mode)
        }
        val constructor=DiaryDatabase::class.java.getDeclaredConstructor(Context::class.java,java.lang.Boolean.TYPE)
        constructor.isAccessible=true;return constructor.newInstance(context,false)
    }
    private fun row(source:String):JSONObject {
        val start=java.time.LocalDate.of(2026,11,14).atTime(10,0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val track=JSONObject().put("id","song").put("title","测试歌曲").put("artist","歌手").put("album","专辑").put("duration",240000)
        val span=JSONObject().put("id",1).put("visit","v1").put("track","song").put("day","2026-11-14").put("zone","UTC").put("start",start).put("end",start+60000).put("ms",60000)
        return JSONObject().put("sourceId",source).put("tracks",JSONArray().put(track)).put("spans",JSONArray().put(span))
    }
    private fun exchange(a:LanSyncStore,b:LanSyncStore,key:ByteArray,aId:String="a".repeat(32),bId:String="b".repeat(32)){
        val ai=PipedInputStream(1_048_576);val bo=PipedOutputStream(ai);val bi=PipedInputStream(1_048_576);val ao=PipedOutputStream(bi)
        val pool=Executors.newFixedThreadPool(2)
        try{
            val fa=pool.submit{LanSyncSession.initiate(LanSyncChannel.open(ai,ao,key),aId,a)}
            val fb=pool.submit{LanSyncSession.accept(LanSyncChannel.open(bi,bo,key),bId,b)}
            fa.get(20,TimeUnit.SECONDS);fb.get(20,TimeUnit.SECONDS)
        }finally{ai.close();bi.close();ao.close();bo.close();pool.shutdownNow()}
    }
    @Test fun twoDatabasesMergeSimultaneousPlaysAndRepeatedSyncDoesNotDuplicate(){
        val phone=device();val tablet=device();val key=LanSyncCrypto.newKey()
        try{
            phone.ingest(row("phone"));tablet.ingest(row("tablet"))
            val a=phone.lanStore();val b=tablet.lanStore()
            repeat(3){exchange(a,b,key)}
            // Echoed records are replaced by small cursor markers when the
            // other device already owns their latest version.
            assertTrue(a.changesAfter(0,512,b.replicaId()).any{it.optBoolean("skip")})
            assertTrue(b.changesAfter(0,512,a.replicaId()).any{it.optBoolean("skip")})
            val bounds=DayBounds("2026-11-14","2026-11-14")
            for(db in listOf(phone,tablet)){
                val report=db.readReportVersioned(bounds,ListeningDayPolicy()).first
                assertEquals(120000L,report.ms);assertEquals(2,report.plays)
            }
        }finally{
            val field=DiaryDatabase::class.java.getDeclaredField("db");field.isAccessible=true
            (field.get(phone) as SQLiteDatabase).close();(field.get(tablet) as SQLiteDatabase).close()
        }
    }

    @Test fun deletingAPlayDoesNotResurrectItFromTheOtherDevicesOldJournal(){
        val phone=device();val tablet=device();val key=LanSyncCrypto.newKey()
        val field=DiaryDatabase::class.java.getDeclaredField("db");field.isAccessible=true
        val phoneSql=field.get(phone) as SQLiteDatabase;val tabletSql=field.get(tablet) as SQLiteDatabase
        try{
            phone.ingest(row("phone"));val a=phone.lanStore();val b=tablet.lanStore()
            exchange(a,b,key)
            phoneSql.delete("spans","remote_id=?",arrayOf("phone:1"))
            repeat(3){exchange(a,b,key)}
            for(db in listOf(phoneSql,tabletSql))db.rawQuery("SELECT COUNT(*) FROM spans",null).use{
                assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))
            }
            // A real later re-import of the same identity must not be mistaken
            // for an old peer replay.
            phone.ingest(row("phone"))
            repeat(3){exchange(a,b,key)}
            for(db in listOf(phoneSql,tabletSql))db.rawQuery("SELECT COUNT(*) FROM spans",null).use{
                assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))
            }
        }finally{phoneSql.close();tabletSql.close()}
    }

    @Test fun importedHistorySurvivesMultipleBatchesAndCanBeRemovedAndReimported(){
        val phone=device();val tablet=device();val key=LanSyncCrypto.newKey()
        val field=DiaryDatabase::class.java.getDeclaredField("db");field.isAccessible=true
        val phoneSql=field.get(phone) as SQLiteDatabase;val tabletSql=field.get(tablet) as SQLiteDatabase
        try{
            val track=AppleTrack("apple-song","Song","Artist","Album",240000)
            val begin=java.time.Instant.parse("2025-01-01T10:00:00Z").toEpochMilli()
            val rows=(0 until 140).map{i->AppleRow("row-$i",track,begin+i*120000L,60000,"UTC","NATURAL_END_OF_TRACK",begin+i*120000L+60000,0,60000,"")}
            val plan=ApplePlan("f".repeat(64),"历史",rows,0,0)
            phone.importApple(plan);val a=phone.lanStore();val b=tablet.lanStore()
            repeat(2){exchange(a,b,key)}
            assertEquals(140,tablet.backup().getJSONArray("import_spans").length())
            phone.removeApple(plan.fingerprint);repeat(2){exchange(a,b,key)}
            assertEquals(0,tablet.backup().getJSONArray("import_spans").length())
            phone.importApple(plan);repeat(2){exchange(a,b,key)}
            assertEquals(140,tablet.backup().getJSONArray("import_spans").length())
        }finally{phoneSql.close();tabletSql.close()}
    }
    @Test fun threeDevicesExchangeIndependentlyAndEventuallyConverge(){
        val one=device();val two=device();val three=device();val key=LanSyncCrypto.newKey()
        val field=DiaryDatabase::class.java.getDeclaredField("db");field.isAccessible=true
        try{
            one.ingest(row("phone"));two.ingest(row("tablet"));three.ingest(row("third"))
            val a=one.lanStore();val b=two.lanStore();val c=three.lanStore()
            repeat(3){
                exchange(a,b,key,"a".repeat(32),"b".repeat(32))
                exchange(a,c,key,"a".repeat(32),"c".repeat(32))
                exchange(b,c,key,"b".repeat(32),"c".repeat(32))
            }
            val bounds=DayBounds("2026-11-14","2026-11-14")
            for(db in listOf(one,two,three)){
                val report=db.readReportVersioned(bounds,ListeningDayPolicy()).first
                assertEquals(180000L,report.ms);assertEquals(3,report.plays)
            }
        }finally{for(db in listOf(one,two,three))(field.get(db) as SQLiteDatabase).close()}
    }
    @Test fun nativeIncrementalSyncKeepsImportedHistoryCache(){
        val phone=device();val tablet=device();val key=LanSyncCrypto.newKey()
        val dbField=DiaryDatabase::class.java.getDeclaredField("db");dbField.isAccessible=true
        try{
            val track=AppleTrack("apple-song","Song","Artist","Album",240000)
            val start=java.time.Instant.parse("2025-01-01T10:00:00Z").toEpochMilli()
            val imported=ApplePlan("e".repeat(64),"历史",listOf(AppleRow("one",track,start,60000,"UTC","NATURAL_END_OF_TRACK",start+60000,0,60000,"")),0,0)
            tablet.importApple(imported);tablet.readReport(null)
            val cache=DiaryDatabase::class.java.getDeclaredField("importedCache");cache.isAccessible=true
            val original=cache.get(tablet);assertNotNull(original)
            phone.ingest(row("phone"));exchange(phone.lanStore(),tablet.lanStore(),key)
            assertSame(original,cache.get(tablet))
            val report=tablet.readReport(null);assertEquals(120000L,report.ms)
            assertSame(original,cache.get(tablet))
        }finally{(dbField.get(phone) as SQLiteDatabase).close();(dbField.get(tablet) as SQLiteDatabase).close()}
    }
}
