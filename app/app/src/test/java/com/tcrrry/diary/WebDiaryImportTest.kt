package com.tcrrry.diary
import android.content.Context
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class WebDiaryImportTest {
 @Before fun setup(){val c=RuntimeEnvironment.getApplication();val f=DiaryDatabase::class.java.getDeclaredField("instance");f.isAccessible=true;val old=f.get(null);if(old!=null){val d=DiaryDatabase::class.java.getDeclaredField("db");d.isAccessible=true;(d.get(old) as android.database.sqlite.SQLiteDatabase).close()};f.set(null,null);c.deleteDatabase("diary.db");java.io.File(c.filesDir,"diary.db").delete();java.io.File(c.filesDir,"diary.db-wal").delete();java.io.File(c.filesDir,"diary.db-shm").delete();c.getSharedPreferences("diary",Context.MODE_PRIVATE).edit().clear().commit()}
 @Test fun webFixtureImportsDifferentDaysAndReimportIsIdempotent(){
  val fixture=javaClass.classLoader!!.getResourceAsStream("web-diary-fixture.json")!!.bufferedReader().use{it.readText()};val db=DiaryDatabase.get(RuntimeEnvironment.getApplication());val input=JSONObject(fixture)
  assertEquals(2,db.ingest(input,true));assertEquals(2,db.ingest(input,true))
  val first=db.report(LocalDate.of(2026,11,15),DiaryRange.DAY).toReport();val second=db.report(LocalDate.of(2026,11,16),DiaryRange.DAY).toReport()
  assertEquals(20000L,first.ms);assertEquals(20000L,second.ms);assertEquals(2,db.backup().getJSONArray("spans").length())
  val old=db.webImportSummary()!!;assertEquals(1,old.rows);assertEquals(40000L,old.ms)
  db.rememberManualImport(fixture.toByteArray(),input,"web.json");db.rememberManualImport(fixture.toByteArray(),input,"web.json")
  val batches=db.manualBatches();assertEquals(1,batches.size);assertTrue(batches.single().label.startsWith("网页记录"));assertEquals("2026-11-15",batches.single().from);assertEquals("2026-11-16",batches.single().to)
 }
 @Test fun realExportImportsIdempotentlyWhenProvided(){
  val path=System.getProperty("diary.apple.fixture")?:return
  val input=JSONObject(java.io.File(path).readText(Charsets.UTF_8));val spans=input.getJSONArray("spans");val db=DiaryDatabase.get(RuntimeEnvironment.getApplication())
  val expected=(0 until spans.length()).groupBy{spans.getJSONObject(it).getString("day")}.mapValues{(_,ids)->ids.sumOf{spans.getJSONObject(it).getLong("ms")}}
  assertTrue(spans.length()>0);db.ingest(input,true);db.ingest(input,true)
  assertEquals(spans.length(),db.backup().getJSONArray("spans").length())
  for((day,ms) in expected)assertEquals(ms,db.report(LocalDate.parse(day),DiaryRange.DAY).toReport().ms)
  if(expected.size==1&&spans.length()>0){val day=LocalDate.parse(expected.keys.single());val report=db.report(day,DiaryRange.DAY).toReport();assertTrue(report.history.any{it.title==input.getJSONArray("tracks").getJSONObject(0).getString("title")});assertTrue(report.plays>0)}
 }
 @Test fun manualImportTypesAndDuplicateFilesAreTracked(){
  val db=DiaryDatabase.get(RuntimeEnvironment.getApplication());val span=JSONObject().put("day","2026-11-15").put("ms",1000)
  fun payload(kind:String)=JSONObject().put("spans",org.json.JSONArray().put(span)).apply{when(kind){"backup"->put("format","listening-diary-app");"plugin"->put("plugin","com.tcrrry.listeningdiary");"web"->put("sourceId","web_preview")}}
  for(kind in listOf("backup","plugin","web","other")){val value=payload(kind);db.rememberManualImport((kind+value).toByteArray(),value,"$kind.json");db.rememberManualImport((kind+value).toByteArray(),value,"$kind.json")}
  val result=db.manualBatches();assertEquals(4,result.size);assertTrue(result.any{it.label.startsWith("日记备份")});assertTrue(result.any{it.label.startsWith("插件备份")});assertTrue(result.any{it.label.startsWith("网页记录")});assertTrue(result.any{it.label.startsWith("JSON 记录")})
  val backup=db.backup();assertEquals(4,backup.getJSONArray("manual_imports").length());db.ingest(backup,true);assertEquals(4,db.manualBatches().size)
 }
}
