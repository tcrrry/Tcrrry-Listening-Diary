package com.tcrrry.diary

import android.content.Context
import android.util.AtomicFile
import java.io.*
import java.time.LocalDate

/** Private, provisional startup snapshot. The database always remains authoritative. */
internal object DailyReportCache {
    private const val LIMIT=24*1024*1024
    private const val STORE_LIMIT=64L*1024*1024
    private fun folder(c:Context)=File(c.filesDir,"report-snapshots-v2").apply{mkdirs()}
    private fun file(c:Context,date:LocalDate,policy:ListeningDayPolicy,range:DiaryRange)=AtomicFile(File(folder(c),"${bounds(date,range).from}-${range.name}-${policy.mode.name}-${policy.hour}.bin"))
    private fun prune(c:Context){
        val today=LocalDate.now();val protected=listOf(today,today.minusDays(1)).map{it.toString()+"-DAY-"}
        val files=folder(c).listFiles{f->f.name.endsWith(".bin")}.orEmpty().sortedWith(compareBy<File>{f->protected.any{f.name.startsWith(it)}}.thenBy{it.lastModified()})
        var bytes=files.sumOf{it.length()};var count=files.size
        for(f in files){if(bytes<=STORE_LIMIT&&count<=12)break;val n=f.length();if(f.delete()){bytes-=n;count--}}
    }
    private data class Entry(val version:Int,val date:String,val mode:String,val hour:Int,val revision:Long,val epoch:Long,val report:DiaryReport):Serializable
    private fun file(c:Context)=AtomicFile(File(c.cacheDir,"recent-day-report-v1.bin"))
    private fun epoch(c:Context)=c.getSharedPreferences("diary",Context.MODE_PRIVATE).getLong("cache_epoch",0)
    private fun revision(c:Context)=c.getSharedPreferences("diary",Context.MODE_PRIVATE).getLong("data_revision",0)
    @Synchronized fun read(c:Context,date:LocalDate,policy:ListeningDayPolicy,range:DiaryRange=DiaryRange.DAY,requireCurrent:Boolean=false):DiaryReport?=runCatching{
        val target=file(c,date,policy,range)
        val source=if(target.baseFile.exists())target else if(range==DiaryRange.DAY)file(c)else return null
        val path=source.baseFile;if(!path.exists()||path.length()>LIMIT)return null
        val saved=ObjectInputStream(source.openRead().buffered()).use{it.readObject() as? Entry}?:return null
        if(saved.version!=2||saved.date!=bounds(date,range).from||saved.mode!=policy.mode.name||saved.hour!=policy.hour||saved.epoch!=epoch(c)||saved.revision>revision(c))return null
        if(requireCurrent&&saved.revision!=revision(c))return null
        val b=bounds(date,range)
        if(saved.report.from!=b.from||saved.report.to!=b.to)return null
        path.setLastModified(System.currentTimeMillis())
        saved.report.copy(nowTitle="",nowTrackId="")
    }.getOrNull()
    @Synchronized fun write(c:Context,date:LocalDate,policy:ListeningDayPolicy,report:DiaryReport,expectedRevision:Long,range:DiaryRange=DiaryRange.DAY){
        val selected=bounds(date,range)
        if(report.from!=selected.from||report.to!=selected.to||report.segments.size>80000)return
        runCatching{
            val rev=expectedRevision;if(revision(c)!=rev)return;val savedEpoch=epoch(c);val safe=report.copy(history=ArrayList(report.history),topArtists=ArrayList(report.topArtists),segments=ArrayList(report.segments),heat=HashSet(report.heat),dayTotals=HashMap(report.dayTotals),artistFirst=HashMap(report.artistFirst),skipsByDay=HashMap(report.skipsByDay));val entry=Entry(2,selected.from,policy.mode.name,policy.hour,rev,savedEpoch,safe)
            val bytes=ByteArrayOutputStream().also{buffer->ObjectOutputStream(buffer).use{it.writeObject(entry)}}.toByteArray()
            if(bytes.size>LIMIT||revision(c)!=rev||epoch(c)!=savedEpoch)return
            val atomic=file(c,date,policy,range);val out=atomic.startWrite()
            try{out.write(bytes);atomic.finishWrite(out);prune(c)}catch(t:Throwable){atomic.failWrite(out);throw t}
        }.onFailure{android.util.Log.w("DiaryReportCache","Snapshot write failed: ${it.javaClass.simpleName}")}
    }
}
