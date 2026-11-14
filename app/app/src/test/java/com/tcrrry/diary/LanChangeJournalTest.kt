package com.tcrrry.diary

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class LanChangeJournalTest {
    @Test fun backfillUpdatesDeletesAndNoOpReplayAreOrdered(){
        val db=SQLiteDatabase.create(null)
        try{
            db.execSQL("CREATE TABLE tracks(id TEXT PRIMARY KEY,title TEXT)")
            db.execSQL("CREATE TABLE spans(remote_id TEXT PRIMARY KEY,ms INTEGER)")
            db.execSQL("CREATE TABLE events(remote_id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE event_sources(source TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE import_batches(id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE import_tracks(batch TEXT,id TEXT,PRIMARY KEY(batch,id))")
            db.execSQL("CREATE TABLE import_spans(batch TEXT,row_key TEXT,part INTEGER,PRIMARY KEY(batch,row_key,part))")
            db.execSQL("INSERT INTO tracks VALUES('song','title')")
            db.execSQL("INSERT INTO spans VALUES('phone:1',30000)")
            LanChangeJournal.install(db);LanChangeJournal.install(db)
            val initial=LanChangeJournal.after(db,0,100)
            assertEquals(listOf("tracks","spans"),initial.map{it.table});assertEquals("phone:1",initial.last().keys.first())
            assertEquals(30000L,LanChangeJournal.recordsAfter(db,0,100).last().row!!.getLong("ms"))
            db.execSQL("UPDATE spans SET ms=30000 WHERE remote_id='phone:1'")
            assertEquals(2,LanChangeJournal.after(db,0,100).size)
            db.execSQL("UPDATE spans SET ms=45000 WHERE remote_id='phone:1'")
            db.execSQL("INSERT INTO spans VALUES('tablet:1',30000)")
            db.execSQL("DELETE FROM spans WHERE remote_id='phone:1'")
            val delta=LanChangeJournal.after(db,initial.last().sequence,100)
            assertEquals(listOf("phone:1","tablet:1","phone:1"),delta.map{it.keys.first()})
            assertEquals(listOf(false,false,true),delta.map{it.deleted})
            assertNull(LanChangeJournal.recordsAfter(db,initial.last().sequence,100).last().row)
            assertEquals(1,LanChangeJournal.after(db,initial.last().sequence,1).size)
            val beforeRekey=delta.last().sequence
            db.execSQL("UPDATE spans SET remote_id='tablet:2' WHERE remote_id='tablet:1'")
            val rekey=LanChangeJournal.after(db,beforeRekey,10)
            assertTrue(rekey.any{it.deleted&&it.keys.first()=="tablet:1"})
            assertTrue(rekey.any{!it.deleted&&it.keys.first()=="tablet:2"})
        }finally{db.close()}
    }
}
