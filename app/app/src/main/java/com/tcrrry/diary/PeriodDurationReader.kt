package com.tcrrry.diary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Independent read-only query so the summary need not wait for the full ledger projection. */
internal object PeriodDurationReader {
    fun read(context:Context,bound:DayBounds,policy:ListeningDayPolicy):Long? {
        if(policy.mode==ListeningDayMode.AUTO)return null
        val first=LocalDate.parse(bound.from);val after=LocalDate.parse(bound.to).plusDays(1)
        // UTC +/- two days safely covers all civil time zones and fixed cutoff hours.
        val earliest=first.minusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val latest=after.plusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val path=File(context.filesDir,"diary.db");if(!path.exists())return null
        val cutoff=if(policy.mode==ListeningDayMode.FIXED)policy.hour.coerceIn(0,12)else 0
        val sql="""
            SELECT s.start,s.end,s.ms,s.zone FROM spans s JOIN tracks t ON t.id=s.track
            WHERE s.end>? AND s.start<?
            UNION ALL
            SELECT s.start,s.end,s.ms,s.zone FROM import_spans s
            JOIN import_tracks t ON t.batch=s.batch AND t.id=s.track
            WHERE s.end>? AND s.start<? AND NOT EXISTS
            (SELECT 1 FROM import_spans earlier WHERE earlier.row_key=s.row_key AND earlier.batch<s.batch)
        """.trimIndent()
        val zones=HashMap<String,ZoneId>();var total=0L
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql,arrayOf(earliest.toString(),latest.toString(),earliest.toString(),latest.toString())).use { c ->
                while(c.moveToNext()){
                    val start=c.getLong(0);val end=c.getLong(1);val ms=c.getLong(2)
                    if(end<=start||ms<=0)continue
                    val name=c.getString(3);val zone=zones.getOrPut(name){runCatching{ZoneId.of(name)}.getOrDefault(ZoneId.systemDefault())}
                    val low=first.atTime(cutoff,0).atZone(zone).toInstant().toEpochMilli()
                    val high=after.atTime(cutoff,0).atZone(zone).toInstant().toEpochMilli()
                    val a=maxOf(start,low);val b=minOf(end,high)
                    if(b>a){val length=end-start;total+=(ms*((b-start).toDouble()/length)).toLong()-(ms*((a-start).toDouble()/length)).toLong()}
                }
            }
        }
        return total
    }
}
