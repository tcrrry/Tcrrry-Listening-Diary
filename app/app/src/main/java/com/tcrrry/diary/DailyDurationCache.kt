package com.tcrrry.diary

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Small provisional index; the ledger remains authoritative. */
internal object DailyDurationCache {
    private fun file(c:Context)=AtomicFile(File(c.filesDir,"daily-duration-v1.json"))
    @Synchronized fun write(c:Context,policy:ListeningDayPolicy,revision:Long,days:Map<LocalDate,List<DatedPlay>>) {
        runCatching {
            val prefs=c.getSharedPreferences("diary",Context.MODE_PRIVATE)
            if(prefs.getLong("data_revision",0)!=revision)return
            val totals=JSONObject();days.forEach{(day,parts)->totals.put(day.toString(),parts.sumOf{it.play.ms})}
            val data=JSONObject().put("mode",policy.mode.name).put("hour",policy.hour).put("epoch",prefs.getLong("cache_epoch",0)).put("revision",revision).put("totals",totals).toString().toByteArray()
            val f=file(c);val out=f.startWrite();try{out.write(data);f.finishWrite(out)}catch(t:Throwable){f.failWrite(out);throw t}
        }
    }
    @Synchronized fun read(c:Context,policy:ListeningDayPolicy,bound:DayBounds):Long?=runCatching {
        val prefs=c.getSharedPreferences("diary",Context.MODE_PRIVATE);val f=file(c)
        if(!f.baseFile.exists()||f.baseFile.length()>2_000_000)return null
        val data=JSONObject(f.openRead().bufferedReader().use{it.readText()})
        if(data.optString("mode")!=policy.mode.name||data.optInt("hour",-1)!=policy.hour||data.optLong("epoch",-1)!=prefs.getLong("cache_epoch",0)||data.optLong("revision",-1)>prefs.getLong("data_revision",0))return null
        val totals=data.getJSONObject("totals");var day=LocalDate.parse(bound.from);val end=LocalDate.parse(bound.to);var sum=0L
        while(!day.isAfter(end)){sum+=totals.optLong(day.toString(),0);day=day.plusDays(1)};sum
    }.getOrNull()
}
