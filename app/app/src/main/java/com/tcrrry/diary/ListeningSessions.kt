package com.tcrrry.diary

internal data class ListeningSession(val key:String,val start:Long,val end:Long,val ms:Long,val songs:List<Play>):java.io.Serializable
internal object ListeningSessions {
    const val BREAK_MS=15*60*1000L
    fun resumedVisits(input:List<Play>):Set<String> = input.groupBy{it.visit to it.id}.filterValues{parts->parts.sortedBy{it.start}.zipWithNext().any{(a,b)->b.start-a.end>1500}}.keys.map{it.first}.toSet()
    fun group(input:List<Play>):List<ListeningSession>{
        val result=ArrayList<ListeningSession>();var rows=ArrayList<Play>();var lastEnd=Long.MIN_VALUE
        fun commit(){if(rows.isEmpty())return;val songs=rows.groupBy{it.visit}.values.map{parts->parts.first().copy(start=parts.minOf{it.start},end=parts.maxOf{it.end},ms=parts.sumOf{it.ms})}.sortedBy{it.start};result.add(ListeningSession("${rows.first().start}:${rows.first().visit}",rows.first().start,lastEnd,rows.sumOf{it.ms},songs));rows=ArrayList()}
        input.filter{it.end>it.start&&it.ms>0}.sortedBy{it.start}.forEach{row->if(rows.isNotEmpty()&&row.start-lastEnd>BREAK_MS)commit();rows.add(row);lastEnd=if(rows.size==1)row.end else maxOf(lastEnd,row.end)}
        commit();return result
    }
    /** Lightweight session summaries without retaining per-session Play lists in memory/snapshots. */
    fun summaries(input:List<Play>):List<ListeningSession>{
        val filtered=ArrayList<Play>(input.size)
        var sorted=true;var prevStart=Long.MIN_VALUE
        for(row in input){
            if(row.end>row.start&&row.ms>0){
                if(row.start<prevStart)sorted=false
                prevStart=row.start
                filtered.add(row)
            }
        }
        if(filtered.isEmpty())return emptyList()
        if(!sorted)filtered.sortBy{it.start}
        val result=ArrayList<ListeningSession>()
        var firstStart=0L;var firstVisit="";var lastEnd=Long.MIN_VALUE;var totalMs=0L;var count=0
        for(row in filtered){
            if(count>0&&row.start-lastEnd>BREAK_MS){
                result.add(ListeningSession("$firstStart:$firstVisit",firstStart,lastEnd,totalMs,emptyList()))
                count=0;totalMs=0L
            }
            if(count==0){firstStart=row.start;firstVisit=row.visit;lastEnd=row.end}
            else if(row.end>lastEnd)lastEnd=row.end
            totalMs+=row.ms;count++
        }
        if(count>0)result.add(ListeningSession("$firstStart:$firstVisit",firstStart,lastEnd,totalMs,emptyList()))
        return result
    }
}
