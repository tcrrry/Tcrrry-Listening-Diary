package com.tcrrry.diary

import android.content.Context
import android.util.AtomicFile
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Preserve bulk writes and the AtomicFile-owned descriptor when closing serialization streams. */
internal class SnapshotOutputStream(destination:OutputStream):FilterOutputStream(destination){
    override fun write(b:ByteArray,off:Int,len:Int){out.write(b,off,len)}
    override fun close(){flush()}
}

/** Computed all-history snapshot. It is provisional; the ledger stays authoritative. */
internal object SummarySnapshotCache{
    private const val DISK_LIMIT=32L*1024*1024
    private const val RAW_LIMIT=256L*1024*1024
    private data class Entry(val version:Int,val mode:String,val hour:Int,val revision:Long,val epoch:Long,val facts:SummaryFacts):Serializable
    private fun file(c:Context)=AtomicFile(File(c.filesDir,"all-summary-v1.bin.gz"))
    private fun prefs(c:Context)=c.getSharedPreferences("diary",Context.MODE_PRIVATE)
    fun read(c:Context,policy:ListeningDayPolicy):SummaryFacts?=readVersioned(c,policy)?.first
    @Synchronized fun readVersioned(c:Context,policy:ListeningDayPolicy):Pair<SummaryFacts,Long>?=runCatching{
        val started=android.os.SystemClock.elapsedRealtime()
        val source=file(c);if(!source.baseFile.exists()||source.baseFile.length()>DISK_LIMIT){android.util.Log.i("DiarySummaryCache","Snapshot miss: absent or oversized");return null}
        val entry=source.openRead().use{raw->
            val bounded=object:FilterInputStream(GZIPInputStream(raw.buffered(65536),65536)){
                var used=0L
                override fun read():Int{val n=super.read();if(n>=0)used++;require(used<=RAW_LIMIT);return n}
                override fun read(b:ByteArray,off:Int,len:Int):Int{val n=super.read(b,off,len);if(n>0)used+=n;require(used<=RAW_LIMIT);return n}
            }
            ObjectInputStream(bounded).use{it.readObject() as? Entry}
        }?:return null
        if(entry.version!=1||entry.mode!=policy.mode.name||entry.hour!=policy.hour||entry.epoch!=prefs(c).getLong("cache_epoch",0)||entry.revision>prefs(c).getLong("data_revision",0)){android.util.Log.i("DiarySummaryCache","Snapshot miss: policy or ledger generation changed");return null}
        android.util.Log.i("DiarySummaryCache","Snapshot restored: rows=${entry.facts.report.segments.size} ms=${android.os.SystemClock.elapsedRealtime()-started}")
        entry.facts to entry.revision
    }.getOrElse{android.util.Log.w("DiarySummaryCache","Snapshot read failed",it);null}
    @Synchronized fun write(c:Context,policy:ListeningDayPolicy,revision:Long,facts:SummaryFacts,expectedEpoch:Long=prefs(c).getLong("cache_epoch",0)):Boolean{
        if(facts.report.segments.size>250000){android.util.Log.w("DiarySummaryCache","Snapshot skipped: segment limit");return false}
        val started=android.os.SystemClock.elapsedRealtime()
        return runCatching{
            val epoch=expectedEpoch
            if(epoch!=prefs(c).getLong("cache_epoch",0))return false
            if(revision>prefs(c).getLong("data_revision",0))return false
            android.util.Log.i("DiarySummaryCache","Snapshot writing: rows=${facts.report.segments.size}")
            val target=file(c);val raw=target.startWrite()
            try{
                val keepOpen=SnapshotOutputStream(raw)
                ObjectOutputStream(GZIPOutputStream(BufferedOutputStream(keepOpen,65536),65536)).use{it.writeObject(Entry(1,policy.mode.name,policy.hour,revision,epoch,facts))}
                require(raw.channel.size()<=DISK_LIMIT){"Summary snapshot exceeds limit"}
                require(epoch==prefs(c).getLong("cache_epoch",0)){"Summary snapshot invalidated"}
                target.finishWrite(raw)
                android.util.Log.i("DiarySummaryCache","Snapshot saved: bytes=${target.baseFile.length()} ms=${android.os.SystemClock.elapsedRealtime()-started}")
                true
            }catch(t:Throwable){target.failWrite(raw);throw t}
        }.getOrElse{android.util.Log.w("DiarySummaryCache","Snapshot write failed",it);false}
    }
}
