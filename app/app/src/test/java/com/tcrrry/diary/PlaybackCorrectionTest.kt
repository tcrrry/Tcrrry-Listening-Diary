package com.tcrrry.diary

import java.io.File
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackCorrectionTest {
    private val day = LocalDate.of(2026, 6, 15)
    private lateinit var db: DiaryDatabase

    @Before
    fun setup() {
        val c = RuntimeEnvironment.getApplication()
        for (name in listOf("instance", "projectionInstance", "projectionOwner", "discoveryInstance", "discoveryOwner")) {
            val f = DiaryDatabase::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.set(null, null)
        }
        for (name in listOf("diary.db", "diary.db-wal", "diary.db-shm")) {
            File(c.filesDir, name).delete()
        }
        c.getSharedPreferences("diary", 0).edit().clear().commit()
        db = DiaryDatabase.get(c)
    }

    private fun play(
        id: String,
        visit: String,
        startMs: Long,
        ms: Long = 180000L,
        effective: Long = 1L
    ) = Play(
        id = id,
        visit = visit,
        title = "Song-$id",
        artist = "Artist-$id",
        album = "Album-$id",
        start = startMs,
        end = startMs + ms,
        ms = ms,
        duration = 200000L,
        zone = ZoneId.systemDefault().id,
        effective = effective
    )

    private fun nativePayload(countA: Int, countB: Int): JSONObject {
        val base = day.atTime(1, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tracks = JSONArray()
            .put(JSONObject().put("id", "tA").put("title", "Sleep Track").put("artist", "Artist A").put("album", "Album A").put("duration", 200000))
            .put(JSONObject().put("id", "tB").put("title", "Normal Track").put("artist", "Artist B").put("album", "Album B").put("duration", 200000))
        val spans = JSONArray()
        var syncId = 1L
        for (i in 0 until countA) {
            val s = base + i * 240000L
            spans.put(
                JSONObject()
                    .put("id", syncId)
                    .put("sync_id", syncId++)
                    .put("visit", "vA-$i")
                    .put("track", "tA")
                    .put("day", day.toString())
                    .put("zone", ZoneId.systemDefault().id)
                    .put("start", s)
                    .put("end", s + 180000L)
                    .put("ms", 180000L)
            )
        }
        for (i in 0 until countB) {
            val s = base + (countA + i) * 240000L
            spans.put(
                JSONObject()
                    .put("id", syncId)
                    .put("sync_id", syncId++)
                    .put("visit", "vB-$i")
                    .put("track", "tB")
                    .put("day", day.toString())
                    .put("zone", ZoneId.systemDefault().id)
                    .put("start", s)
                    .put("end", s + 180000L)
                    .put("ms", 180000L)
            )
        }
        return JSONObject().put("sourceId", "native").put("tracks", tracks).put("spans", spans)
    }

    @Test
    fun normalPlaybackDoesNotTriggerAnomaly() {
        val base = 1_780_000_000_000L
        val three = (0 until 3).map { play("t1", "v$it", base + it * 240000L) }
        assertNull(analyzeDailyPlaybackAnomaly(three, "t1"))

        val scatteredFour = listOf(
            play("t1", "v1", base),
            play("t2", "v2", base + 200000L),
            play("t1", "v3", base + 400000L),
            play("t2", "v4", base + 600000L),
            play("t1", "v5", base + 800000L),
            play("t2", "v6", base + 1000000L),
            play("t1", "v7", base + 1200000L)
        )
        assertNull(analyzeDailyPlaybackAnomaly(scatteredFour, "t1"))
    }

    @Test
    fun consecutiveLoopAndHighCountTriggerWithSmartRecommendation() {
        val base = 1_780_000_000_000L

        val withNormalBeforeBurst = buildList {
            add(play("t1", "v-norm", base))
            add(play("other", "v-other", base + 300000L))
            for (i in 0 until 20) add(play("t1", "v-burst-$i", base + 600000L + i * 240000L))
        }
        val infoBurst = analyzeDailyPlaybackAnomaly(withNormalBeforeBurst, "t1")!!
        assertEquals(21, infoBurst.effectivePlays)
        assertEquals(20, infoBurst.maxConsecutivePlays)
        assertEquals(2, infoBurst.recommendedPlays)

        val pureBurst = (0 until 15).map { play("t1", "v-$it", base + it * 240000L) }
        val infoPure = analyzeDailyPlaybackAnomaly(pureBurst, "t1")!!
        assertEquals(15, infoPure.effectivePlays)
        assertEquals(15, infoPure.maxConsecutivePlays)
        assertEquals(1, infoPure.recommendedPlays)

        val scatteredNine = buildList {
            var idx = 0
            for (g in 0 until 4) {
                add(play("t1", "v-${idx++}", base + idx * 240000L))
                add(play("t1", "v-${idx++}", base + idx * 240000L))
                add(play("other", "vo-$g", base + idx * 240000L + 100000L))
            }
            add(play("t1", "v-last", base + 9_000_000L))
        }
        val infoScattered = analyzeDailyPlaybackAnomaly(scatteredNine, "t1")!!
        assertEquals(9, infoScattered.effectivePlays)
        assertEquals(2, infoScattered.maxConsecutivePlays)
        assertEquals(2, infoScattered.recommendedPlays)
    }

    @Test
    fun selectVisitsToRemoveKeepsEarliestEffectivePlays() {
        val base = 1_780_000_000_000L
        val visits = listOf(
            play("t1", "v0", base, ms = 5000L, effective = 0L),
            play("t1", "v1", base + 100000L, effective = 1L),
            play("t1", "v2", base + 300000L, effective = 1L),
            play("t1", "v3", base + 500000L, effective = 1L),
            play("t1", "v4", base + 700000L, effective = 1L)
        )
        val removeKeepTwo = selectVisitsToRemoveForCorrection(visits, 2)
        assertEquals(listOf("v3", "v4"), removeKeepTwo.map { it.visit })

        val removeAll = selectVisitsToRemoveForCorrection(visits, 0)
        assertEquals(listOf("v0", "v1", "v2", "v3", "v4"), removeAll.map { it.visit })
    }

    @Test
    fun nativeCorrectionUpdatesAllPeriodsExportsAndPreventsCollectorResurrection() {
        val c = RuntimeEnvironment.getApplication()
        val payload = nativePayload(countA = 15, countB = 2)
        db.ingest(payload)

        val dayBefore = db.readDayScreen(day, ListeningDayPolicy())!!.first
        val anomaly = analyzeDailyPlaybackAnomaly(dayBefore.history, "tA")!!
        assertEquals(15, anomaly.effectivePlays)

        val matchedA = dayBefore.history.filter { it.id == "tA" }
        val toRemove = selectVisitsToRemoveForCorrection(matchedA, 2).map { it.visit }.toSet()
        assertEquals(13, db.removeTrackVisits("tA", toRemove))

        val dayAfter = db.readDayScreen(day, ListeningDayPolicy())!!.first
        assertEquals(2, dayAfter.history.count { it.id == "tA" && it.effective > 0 })
        assertEquals(2, dayAfter.history.count { it.id == "tB" && it.effective > 0 })
        assertEquals(4, dayAfter.plays)

        for (range in listOf(DiaryRange.WEEK, DiaryRange.MONTH, DiaryRange.YEAR)) {
            val periodReport = db.readPeriodScreen(bounds(day, range), ListeningDayPolicy())!!.first
            assertEquals(2, periodReport.history.count { it.id == "tA" && it.effective > 0 })
            assertEquals(2, periodReport.history.count { it.id == "tB" && it.effective > 0 })
        }

        val allReport = DiaryDatabase.projection(c).readAllReportVersioned(ListeningDayPolicy()).first
        assertEquals(2, allReport.history.count { it.id == "tA" && it.effective > 0 })
        assertEquals(2, allReport.history.count { it.id == "tB" && it.effective > 0 })

        val csvOut = StringWriter()
        writeListeningExport(csvOut, allReport, csv = true)
        val csvText = csvOut.toString()
        assertEquals(2, csvText.lines().count { it.contains("\"Sleep Track\"") })
        assertEquals(2, csvText.lines().count { it.contains("\"Normal Track\"") })

        val backupWriter = StringWriter()
        db.writeBackup(backupWriter)
        val streamedBackup = JSONObject(backupWriter.toString())
        assertEquals(13, streamedBackup.getJSONArray("suppressed_spans").length())

        // Simulate AM++ collector re-pushing the old batch: suppressed spans must stay gone.
        db.ingest(payload, importing = false)
        assertEquals(2, db.readDayScreen(day, ListeningDayPolicy())!!.first.history.count { it.id == "tA" && it.effective > 0 })

        // Backup -> wipe -> restore -> collector re-push must also preserve suppression.
        val backup = db.backup()
        assertEquals(13, backup.getJSONArray("suppressed_spans").length())
        setup()
        db.ingest(backup, importing = true)
        db.ingest(payload, importing = false)
        assertEquals(2, db.readDayScreen(day, ListeningDayPolicy())!!.first.history.count { it.id == "tA" && it.effective > 0 })
    }

    @Test
    fun importedCorrectionUpdatesBatchesAndKeepsBackupValid() {
        val c = RuntimeEnvironment.getApplication()
        val csvFile = File.createTempFile("apple-corr-", ".csv", c.cacheDir)
        val header = "\"Event Type\",\"Event ID\",\"Song Name\",\"Album Name\",\"Container Artist Name\",\"Play Duration Milliseconds\",\"Media Duration In Milliseconds\",\"Event Start Timestamp\",\"Event End Timestamp\",\"UTC Offset In Seconds\",\"End Reason Type\",\"Start Position In Milliseconds\",\"End Position In Milliseconds\",\"Media Type\""
        val rows = buildList {
            for (i in 0 until 10) {
                val start = Instant.parse("2026-06-15T01:00:00Z").plusSeconds(i * 240L)
                val end = start.plusMillis(180000L)
                add("\"PLAY_END\",\"tC\",\"Sleep Imported\",\"Imported Album\",\"Artist C\",\"180000\",\"200000\",\"$start\",\"$end\",\"28800\",\"NATURAL_END_OF_TRACK\",\"0\",\"180000\",\"AUDIO\"")
            }
            val startD = Instant.parse("2026-06-15T03:00:00Z")
            val endD = startD.plusMillis(180000L)
            add("\"PLAY_END\",\"tD\",\"Keep Imported\",\"Imported Album\",\"Artist D\",\"180000\",\"200000\",\"$startD\",\"$endD\",\"28800\",\"NATURAL_END_OF_TRACK\",\"0\",\"180000\",\"AUDIO\"")
        }
        csvFile.writeText((listOf(header) + rows).joinToString("\r\n"))

        val plan = AppleActivityImport.read(csvFile, "Apple Corr")
        assertTrue(db.importApple(plan))

        val weekBefore = db.readPeriodScreen(bounds(day, DiaryRange.WEEK), ListeningDayPolicy())!!.first
        val cVisits = weekBefore.history.filter { it.title == "Sleep Imported" }
        assertEquals(10, cVisits.size)
        val idC = cVisits.first().id
        val idD = weekBefore.history.first { it.title == "Keep Imported" }.id
        val toRemoveC = selectVisitsToRemoveForCorrection(cVisits, 1).map { it.visit }.toSet()
        assertEquals(9, db.removeTrackVisits(idC, toRemoveC))

        val weekAfter = db.readPeriodScreen(bounds(day, DiaryRange.WEEK), ListeningDayPolicy())!!.first
        assertEquals(1, weekAfter.history.count { it.id == idC })
        assertEquals(1, weekAfter.history.count { it.id == idD })
        val allAfter = DiaryDatabase.projection(c).readAllReportVersioned(ListeningDayPolicy()).first
        assertEquals(1, allAfter.history.count { it.id == idC })

        val updatedBatch = db.appleBatches().single()
        assertEquals(2, updatedBatch.rows)
        assertEquals(360000L, updatedBatch.ms)

        val backup = db.backup()
        db.removeApple(plan.fingerprint)
        db.ingest(backup, importing = true)
        assertEquals(2, db.appleBatches().single().rows)

        // Removing remaining plays of idC and idD deletes the empty batch so backup remains valid.
        val remaining = db.readPeriodScreen(bounds(day, DiaryRange.WEEK), ListeningDayPolicy())!!.first.history
        db.removeTrackVisits(idC, remaining.filter { it.id == idC }.map { it.visit }.toSet())
        db.removeTrackVisits(idD, remaining.filter { it.id == idD }.map { it.visit }.toSet())
        assertTrue(db.appleBatches().isEmpty())

        val emptyBatchBackup = db.backup()
        setup()
        db.ingest(emptyBatchBackup, importing = true)
        assertTrue(db.appleBatches().isEmpty())
    }

    @Test
    fun nativeCorrectionCanBeUndoneCompletelyAndClearsSuppression() {
        val c = RuntimeEnvironment.getApplication()
        val payload = nativePayload(countA = 15, countB = 2)
        db.ingest(payload)

        val dayBefore = db.readDayScreen(day, ListeningDayPolicy())!!.first
        val toRemove = selectVisitsToRemoveForCorrection(dayBefore.history.filter { it.id == "tA" }, 2)
        val removedMs = toRemove.sumOf { it.ms }
        assertEquals(13, db.removeTrackVisits("tA", toRemove.map { it.visit }.toSet(), day, keptPlays = 2, removedEffective = 13, removedMs = removedMs))

        val undoInfo = db.readCorrectionUndo(day, "tA")!!
        assertEquals(2, undoInfo.keptPlays)
        assertEquals(13, undoInfo.removedEffective)
        assertEquals(13 * 180000L, undoInfo.removedMs)
        assertFalse(db.backup().has("correction_undos"))

        assertEquals(13, db.undoTrackCorrection(day, "tA"))
        assertNull(db.readCorrectionUndo(day, "tA"))
        assertEquals(0, db.backup().getJSONArray("suppressed_spans").length())

        val dayRestored = db.readDayScreen(day, ListeningDayPolicy())!!.first
        assertEquals(15, dayRestored.history.count { it.id == "tA" && it.effective > 0 })
        for (range in listOf(DiaryRange.WEEK, DiaryRange.MONTH, DiaryRange.YEAR)) {
            assertEquals(15, db.readPeriodScreen(bounds(day, range), ListeningDayPolicy())!!.first.history.count { it.id == "tA" && it.effective > 0 })
        }
        val allRestored = DiaryDatabase.projection(c).readAllReportVersioned(ListeningDayPolicy()).first
        assertEquals(15, allRestored.history.count { it.id == "tA" && it.effective > 0 })
        val csvOut = StringWriter()
        writeListeningExport(csvOut, allRestored, csv = true)
        assertEquals(15, csvOut.toString().lines().count { it.contains("\"Sleep Track\"") })
    }

    @Test
    fun importedCorrectionIncludingFullBatchRemovalCanBeUndone() {
        val c = RuntimeEnvironment.getApplication()
        val csvFile = File.createTempFile("apple-undo-", ".csv", c.cacheDir)
        val header = "\"Event Type\",\"Event ID\",\"Song Name\",\"Album Name\",\"Container Artist Name\",\"Play Duration Milliseconds\",\"Media Duration In Milliseconds\",\"Event Start Timestamp\",\"Event End Timestamp\",\"UTC Offset In Seconds\",\"End Reason Type\",\"Start Position In Milliseconds\",\"End Position In Milliseconds\",\"Media Type\""
        val rows = (0 until 10).map { i ->
            val start = Instant.parse("2026-06-15T01:00:00Z").plusSeconds(i * 240L)
            val end = start.plusMillis(180000L)
            "\"PLAY_END\",\"tC\",\"Solo Sleep\",\"Imported Album\",\"Artist C\",\"180000\",\"200000\",\"$start\",\"$end\",\"28800\",\"NATURAL_END_OF_TRACK\",\"0\",\"180000\",\"AUDIO\""
        }
        csvFile.writeText((listOf(header) + rows).joinToString("\r\n"))

        val plan = AppleActivityImport.read(csvFile, "Apple Undo Solo")
        assertTrue(db.importApple(plan))

        val weekBefore = db.readPeriodScreen(bounds(day, DiaryRange.WEEK), ListeningDayPolicy())!!.first
        val cVisits = weekBefore.history.filter { it.title == "Solo Sleep" }
        val idC = cVisits.first().id
        assertEquals(10, db.removeTrackVisits(idC, cVisits.map { it.visit }.toSet(), day, keptPlays = 0, removedEffective = 10, removedMs = 10 * 180000L))
        assertTrue(db.appleBatches().isEmpty())

        assertEquals(10, db.undoTrackCorrection(day, idC))
        assertNull(db.readCorrectionUndo(day, idC))
        assertEquals(10, db.appleBatches().single().rows)
        assertEquals(10, db.readPeriodScreen(bounds(day, DiaryRange.WEEK), ListeningDayPolicy())!!.first.history.count { it.id == idC })

        val backup = db.backup()
        db.removeApple(plan.fingerprint)
        db.ingest(backup, importing = true)
        assertEquals(10, db.appleBatches().single().rows)
    }

    @Test
    fun repeatedCorrectionMergesUndoPayloadSoSingleUndoRestoresOriginal() {
        val payload = nativePayload(countA = 20, countB = 1)
        db.ingest(payload)

        val firstDay = db.readDayScreen(day, ListeningDayPolicy())!!.first
        val trimToTen = selectVisitsToRemoveForCorrection(firstDay.history.filter { it.id == "tA" }, 10)
        assertEquals(10, db.removeTrackVisits("tA", trimToTen.map { it.visit }.toSet(), day, keptPlays = 10, removedEffective = 10, removedMs = trimToTen.sumOf { it.ms }))

        val midDay = db.readDayScreen(day, ListeningDayPolicy())!!.first
        val trimToTwo = selectVisitsToRemoveForCorrection(midDay.history.filter { it.id == "tA" }, 2)
        assertEquals(8, db.removeTrackVisits("tA", trimToTwo.map { it.visit }.toSet(), day, keptPlays = 2, removedEffective = 8, removedMs = trimToTwo.sumOf { it.ms }))

        val mergedUndo = db.readCorrectionUndo(day, "tA")!!
        assertEquals(2, mergedUndo.keptPlays)
        assertEquals(18, mergedUndo.removedEffective)

        assertEquals(18, db.undoTrackCorrection(day, "tA"))
        assertEquals(20, db.readDayScreen(day, ListeningDayPolicy())!!.first.history.count { it.id == "tA" && it.effective > 0 })
    }
}
