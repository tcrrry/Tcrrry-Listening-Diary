package com.tcrrry.diary
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class SummarySnapshotCacheTest{
 private val c get()=RuntimeEnvironment.getApplication()
 @Before fun setup(){c.getSharedPreferences("diary",0).edit().putLong("data_revision",5).putLong("cache_epoch",1).commit();java.io.File(c.filesDir,"all-summary-v1.bin.gz").delete()}
 @Test fun snapshotStreamKeepsBulkWritesAndDoesNotCloseAtomicDescriptor(){
  var individual=0;var bulk=0;var closed=false
  val sink=object:java.io.OutputStream(){
   override fun write(b:Int){individual++}
   override fun write(b:ByteArray,off:Int,len:Int){bulk++}
   override fun close(){closed=true}
  }
  java.io.BufferedOutputStream(SnapshotOutputStream(sink)).use{it.write(ByteArray(65536))}
  assertEquals("buffered data must not expand into per-byte I/O",0,individual)
  assertEquals(1,bulk);assertFalse("AtomicFile owns the descriptor",closed)
 }
 @Test fun computedSummaryRestoresWithoutRebuildingTheFacts(){
  val facts=SummaryFacts(periodDemoReport(LocalDate.of(2026,10,23),DiaryRange.YEAR))
  assertTrue(SummarySnapshotCache.write(c,ListeningDayPolicy(),5,facts))
  val saved=SummarySnapshotCache.read(c,ListeningDayPolicy())!!
  assertEquals(facts.report.ms,saved.report.ms);assertEquals(facts.days.size,saved.days.size)
  assertEquals(facts.monthlyFavorites,saved.monthlyFavorites);assertEquals(facts.hourlyBins,saved.hourlyBins)
  assertEquals(facts.sessions.size,saved.sessions.size);assertEquals(facts.albums.map{it.key},saved.albums.map{it.key})
  assertEquals(facts.bands(),saved.bands())
 }
 @Test fun importsPolicyChangesAndFutureRevisionsCannotRestoreStaleData(){
  val policy=ListeningDayPolicy();val facts=SummaryFacts(demoReport(LocalDate.of(2026,10,23)))
  assertTrue(SummarySnapshotCache.write(c,policy,5,facts))
  assertNull(SummarySnapshotCache.read(c,ListeningDayPolicy(ListeningDayMode.FIXED,6)))
  c.getSharedPreferences("diary",0).edit().putLong("data_revision",6).commit()
  assertNotNull(SummarySnapshotCache.read(c,policy))
  c.getSharedPreferences("diary",0).edit().putLong("cache_epoch",2).commit()
  assertNull(SummarySnapshotCache.read(c,policy))
  assertFalse(SummarySnapshotCache.write(c,policy,5,facts,1))
 }
 @Test fun hundredThousandImportedSegmentsCanRestoreComputedSummary(){
  val base=demoReport(LocalDate.of(2026,10,23)).history.first()
  val start=LocalDate.of(2021,1,1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
  val plays=(0 until 100000).map{i->val at=start+i*1900000L;base.copy(id="track:${i%2000}",visit="import:$i",title="Song ${i%2000}",artist="Artist ${i%30}",album="Album ${i%400}",start=at,end=at+180000,ms=180000,zone="UTC",imported=true)}
  val facts=SummaryFacts(DiaryReport(ms=180000L*plays.size,plays=plays.size,history=plays,segments=plays,from="2021-01-01",to="2027-01-01"))
  val writeAt=System.nanoTime()
  assertTrue(SummarySnapshotCache.write(c,ListeningDayPolicy(),5,facts))
  val writeMs=(System.nanoTime()-writeAt)/1000000
  val at=System.nanoTime();val saved=SummarySnapshotCache.read(c,ListeningDayPolicy())!!
  assertEquals(facts.days.size,saved.days.size);assertEquals(facts.report.ms,saved.report.ms)
  assertEquals(facts.monthlyFavorites,saved.monthlyFavorites);assertEquals(facts.bands(),saved.bands())
  println("SYNTHETIC_SUMMARY_CACHE rows=${plays.size} compressedBytes=${java.io.File(c.filesDir,"all-summary-v1.bin.gz").length()} writeMs=$writeMs restoreMs=${(System.nanoTime()-at)/1000000}")
 }
 @Test fun truncatedSnapshotFallsBackToLiveData(){
  java.io.File(c.filesDir,"all-summary-v1.bin.gz").writeBytes(byteArrayOf(31,-117,8,0))
  assertNull(SummarySnapshotCache.read(c,ListeningDayPolicy()))
 }
 @Test fun actualOfficialHistoryCanRoundTripComputedSummary(){
  val path=System.getProperty("diary.apple.fixture");Assume.assumeTrue(path!=null)
  val plan=AppleActivityImport.read(java.io.File(path),"official")
  val plays=plan.rows.map{r->Play(r.track.id,r.visit,r.track.title,r.track.artist,r.track.album,r.start,r.start+r.ms,r.ms,r.track.duration,r.zone,effective=DailyRows.effective(r.ms,r.track.duration),imported=true)}
  val facts=SummaryFacts(DiaryReport(ms=plan.ms,plays=plays.sumOf{it.effective}.toInt(),history=plays,segments=plays,from=plan.from,to=plan.to))
  assertTrue(SummarySnapshotCache.write(c,ListeningDayPolicy(),5,facts))
  val at=System.nanoTime();val saved=SummarySnapshotCache.read(c,ListeningDayPolicy())!!
  assertEquals(facts.report.ms,saved.report.ms);assertEquals(facts.days.size,saved.days.size)
  assertEquals(facts.monthlyFavorites,saved.monthlyFavorites)
  println("REAL_SUMMARY_CACHE rows=${plays.size} year2026=${facts.days.count{it.date.year==2026}} compressedBytes=${java.io.File(c.filesDir,"all-summary-v1.bin.gz").length()} restoreMs=${(System.nanoTime()-at)/1000000}")
 }
}
