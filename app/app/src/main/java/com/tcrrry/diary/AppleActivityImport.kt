package com.tcrrry.diary

import org.json.JSONArray
import java.io.*
import java.security.MessageDigest
import java.time.*
import java.util.zip.*

internal data class AppleTrack(val id:String,val title:String,val artist:String,val album:String,val duration:Long)
internal data class AppleRow(val key:String,val track:AppleTrack,val start:Long,val ms:Long,val zone:String,val reason:String,val originalEnd:Long,val startPosition:Long,val endPosition:Long,val device:String,var visit:String=key,val inferred:Boolean=false)
internal data class ApplePlan(val fingerprint:String,val label:String,val rows:List<AppleRow>,val ignored:Int,val duplicates:Int){
    val ms by lazy{rows.sumOf{it.ms}}
    val from by lazy{rows.minOf{Instant.ofEpochMilli(it.start).atZone(ZoneId.of(it.zone)).toLocalDate()}.toString()}
    val to by lazy{rows.maxOf{Instant.ofEpochMilli(it.start+it.ms-1).atZone(ZoneId.of(it.zone)).toLocalDate()}.toString()}
    val unknownArtist by lazy{rows.count{it.track.artist=="未知歌手"}}
    val unknownAlbum by lazy{rows.count{it.track.album.isBlank()}}
    val inferredStarts by lazy{rows.count{it.inferred}}
}
internal data class AppleBatch(val id:String,val label:String,val rows:Int,val ms:Long,val from:String,val to:String,val importedAt:Long)

/** RFC 4180 reader: quoted commas, escaped quotes and embedded line breaks are all data. */
internal class AppleCsv(reader:Reader):Closeable {
    private val input=PushbackReader(reader.buffered(),1)
    init{val first=input.read();if(first>=0&&first!=0xFEFF)input.unread(first)}
    private var chars=0L
    fun row():List<String>? {
        val values=ArrayList<String>();val cell=StringBuilder();var quoted=false;var seen=false
        while(true){
            val raw=input.read();if(raw<0){require(!quoted){"CSV引号未闭合"};if(!seen&&values.isEmpty()&&cell.isEmpty())return null;values.add(cell.toString());return values}
            require(++chars<=300_000_000L){"CSV文件过大"};seen=true;val c=raw.toChar()
            if(quoted){if(c=='"'){val next=input.read();if(next=='"'.code)cell.append('"')else{quoted=false;if(next>=0)input.unread(next)}}else cell.append(c)}
            else when(c){'"'->{if(cell.isEmpty())quoted=true else cell.append(c)};','->{values.add(cell.toString());cell.setLength(0)};'\n'->{values.add(cell.toString());return values};'\r'->{val next=input.read();if(next>=0&&next!='\n'.code)input.unread(next);values.add(cell.toString());return values};else->cell.append(c)}
            require(cell.length<=1_000_000&&values.size<=512){"CSV字段超出限制"}
        }
    }
    override fun close(){input.close()}
}
internal object AppleActivityImport {
    private val skipReasons=setOf("TRACK_SKIPPED_FORWARDS","TRACK_SKIPPED_BACKWARDS","MANUALLY_SELECTED_PLAYBACK_OF_A_DIFF_ITEM")
    fun isSkip(reason:String)=reason in skipReasons
    fun hash(value:String):String {val bytes=MessageDigest.getInstance("SHA-256").digest(value.toByteArray());val digits="0123456789abcdef";val chars=CharArray(bytes.size*2);bytes.forEachIndexed{i,b->chars[i*2]=digits[(b.toInt() and 255) ushr 4];chars[i*2+1]=digits[b.toInt() and 15]};return String(chars)}
    private fun time(value:String):Long?=runCatching{Instant.parse(value).takeIf{it.atOffset(ZoneOffset.UTC).year in 2000..2100}?.toEpochMilli()}.getOrNull()
    private fun number(value:String)=value.toDoubleOrNull()?.takeIf{it.isFinite()&&it>=0&&it<=Long.MAX_VALUE.toDouble()}?.toLong()?:0L
    private class Catalog {
        val byTitle=HashMap<String,MutableList<AppleTrack>>()
        fun add(track:AppleTrack){if(track.title.isNotBlank())byTitle.getOrPut(track.title){arrayListOf()}.let{if(it.none{v->v.id==track.id&&v.artist==track.artist&&v.album==track.album})it.add(track)}}
        fun track(title:String,album:String,artist:String,duration:Long,key:String):AppleTrack {
            val candidates=byTitle[title].orEmpty();val exact=candidates.filter{album.isNotBlank()&&it.album==album&&(artist.isBlank()||it.artist==artist)}
            val selected=(if(exact.isNotEmpty())exact else candidates.filter{artist.isBlank()||it.artist==artist}).distinctBy{it.id to it.artist}
            val known=selected.singleOrNull();val author=artist.ifBlank{known?.artist?:selected.map{it.artist}.distinct().singleOrNull().orEmpty()}.ifBlank{"未知歌手"}
            val name=title.ifBlank{"未知歌曲"};val record=known?.takeIf{it.id.matches(Regex("[0-9]{6,20}"))}
            val id=record?.id?:"apple:"+hash(if(title.isBlank())key else "$title\u0000$author\u0000$album").take(32)
            return AppleTrack(id,name,author,album.ifBlank{known?.album.orEmpty()},duration.takeIf{it>0}?:known?.duration?:0)
        }
    }
    private fun library(bytes:ByteArray,catalog:Catalog){
        require(bytes.size<=24*1024*1024){"曲库资料过大"};val tracks=JSONArray(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        for(i in 0 until tracks.length()){val t=tracks.getJSONObject(i);val id=t.optString("Apple Music Track Identifier").takeUnless{it.isBlank()||it=="null"}?:"";catalog.add(AppleTrack(id,t.optString("Title"),t.optString("Artist"),t.optString("Album"),t.optLong("Track Duration")))}
    }
    private fun daily(input:InputStream,catalog:Catalog){AppleCsv(input.reader(Charsets.UTF_8)).use{csv->val h=csv.row()?.map{it.removePrefix("\uFEFF")}?:return;val id=h.indexOf("Track Identifier");val desc=h.indexOf("Track Description");if(id<0||desc<0)return;while(true){val row=csv.row()?:break;val text=row.getOrElse(desc){""};val split=text.indexOf(" - ");if(split>0)catalog.add(AppleTrack(row.getOrElse(id){""},text.substring(split+3),text.substring(0,split),"",0))}}}
    private fun bounded(input:InputStream,limit:Int):ByteArray {val output=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(output.size().toLong()+n<=limit){"压缩包内资料过大"};output.write(buffer,0,n)};return output.toByteArray()}
    fun read(file:File,label:String,check:()->Unit={},depth:Int=0):ApplePlan {
        require(depth<=2){"压缩包嵌套过深，请选择Apple Music活动包"}
        val catalog=Catalog();val magic=file.inputStream().use{input->ByteArray(4).also{input.read(it)}}
        if(magic.contentEquals(byteArrayOf(80,75,3,4))||magic.contentEquals(byteArrayOf(80,75,5,6))){ZipFile(file).use{zip->
            val entries=zip.entries().asSequence().filter{!it.isDirectory&&!it.name.substringAfterLast('/').startsWith("._")&&!it.name.startsWith("__MACOSX/")}.take(10001).toList();require(entries.size<=10000){"压缩包文件过多"}
            entries.firstOrNull{it.name.substringAfterLast('/').equals("Apple Music Library Tracks.json.zip",true)}?.let{entry->zip.getInputStream(entry).use{stream->ZipInputStream(stream).use{nested->while(true){val n=nested.nextEntry?:break;if(!n.isDirectory&&n.name.endsWith(".json")){library(bounded(nested,24*1024*1024),catalog);break}}}}}
            entries.firstOrNull{it.name.substringAfterLast('/').equals("Apple Music Library Tracks.json",true)}?.let{zip.getInputStream(it).use{input->library(bounded(input,24*1024*1024),catalog)}}
            entries.firstOrNull{it.name.substringAfterLast('/').equals("Apple Music - Play History Daily Tracks.csv",true)}?.let{zip.getInputStream(it).use{input->daily(input,catalog)}}
            val activity=entries.firstOrNull{it.name.substringAfterLast('/').replace(Regex("[^A-Za-z]"),"").equals("AppleMusicPlayActivitycsv",true)}
            if(activity!=null)return zip.getInputStream(activity).use{parse(it,catalog,label,check)}
            val nested=entries.firstOrNull{val name=it.name.substringAfterLast('/').lowercase();name.endsWith(".zip")&&name.contains("music")&&name.contains("activity")&&!name.contains("library")}
                ?:entries.firstOrNull{it.name.substringAfterLast('/').replace(Regex("[^A-Za-z]"),"").equals("AppleMediaServiceszip",true)}
            if(nested!=null){val temp=File.createTempFile("apple-nested-",".zip",file.parentFile);try{zip.getInputStream(nested).use{input->temp.outputStream().use{out->val buffer=ByteArray(65536);var total=0L;while(true){check();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=512L*1024*1024){"内层包超过512MB"};out.write(buffer,0,n)}}};return read(temp,label,check,depth+1)}finally{temp.delete()}}
            if(entries.any{it.name.contains("Game Center/",true)}&&!entries.any{it.name.contains("Apple Music",true)})throw IllegalArgumentException("这个分包只有 Game Center 等资料，不含 Apple Music 播放记录，请选择媒体服务信息的另一部分")
            throw IllegalArgumentException("未找到逐次播放活动CSV。每日汇总不能还原精确时间线，请选择Apple Music活动包")
        }}
        return file.inputStream().use{parse(it,catalog,label,check)}
    }
    private fun parse(input:InputStream,catalog:Catalog,label:String,check:()->Unit):ApplePlan {
        val rows=ArrayList<AppleRow>();val keys=HashSet<String>();val tracks=HashMap<String,AppleTrack>();var ignored=0;var duplicates=0;var count=0
        AppleCsv(input.reader(Charsets.UTF_8)).use{csv->
            fun normalize(key:String)=key.removePrefix("\uFEFF").trim().lowercase().replace(Regex("[ _]+")," ")
            val header=csv.row()?.map{normalize(it)}?:throw IllegalArgumentException("文件为空")
            require(listOf("Event Type","Song Name","Play Duration Milliseconds").all{normalize(it) in header}&&listOf("Event End Timestamp","Event Start Timestamp").any{normalize(it) in header}){"请选择逐次播放活动CSV，不能使用每日汇总表代替"}
            val positions=listOf("Event Type","Event ID","Song Name","Album Name","Container Artist Name","Play Duration Milliseconds","Media Duration In Milliseconds","Event Start Timestamp","Event End Timestamp","UTC Offset In Seconds","End Reason Type","Start Position In Milliseconds","End Position In Milliseconds","Media Type","Device Identifier","Client Device Name","Device Type","Device OS Name").associateWith{header.indexOf(normalize(it))}
            while(true){val values=csv.row()?:break;if(++count%1024==0)check();require(count<=500000){"播放事件超过50万条，请分批导入"}
                fun v(key:String)=positions[key]?.let{values.getOrNull(it)}.orEmpty()
                if(v("Event Type")!="PLAY_END"||v("Media Type")=="VIDEO"){ignored++;continue}
                val ms=number(v("Play Duration Milliseconds"));if(ms<=0||ms>7*24*3600000L){ignored++;continue}
                val originalEnd=time(v("Event End Timestamp"));val declared=time(v("Event Start Timestamp"));val start=declared?:originalEnd?.minus(ms)
                if(start==null||start<946684800000L||start+ms>4133980800000L){ignored++;continue}
                val offset=v("UTC Offset In Seconds").toIntOrNull();val zone=runCatching{ZoneOffset.ofTotalSeconds(offset?:0).id}.getOrDefault("UTC")
                val key=hash(listOf(v("Event ID"),"$start","$ms",v("Song Name"),v("Album Name")).joinToString("|"))
                if(!keys.add(key)){duplicates++;continue}
                val t=catalog.track(v("Song Name"),v("Album Name"),v("Container Artist Name"),number(v("Media Duration In Milliseconds")),key)
                val track=tracks.getOrPut(t.id+"|"+t.title+"|"+t.artist+"|"+t.album){t}
                val device=hash(listOf(v("Device Identifier"),v("Client Device Name"),v("Device Type"),v("Device OS Name")).joinToString("|"))
                rows.add(AppleRow(key,track,start,ms,zone,v("End Reason Type"),originalEnd?:start+ms,number(v("Start Position In Milliseconds")),number(v("End Position In Milliseconds")),device,inferred=declared==null))
            }
        }
        require(rows.isNotEmpty()){"没有可按日期导入的播放结束记录"}
        rows.sortBy{it.start};val previous=HashMap<String,AppleRow>()
        for(row in rows){val old=previous[row.device];if(old!=null&&old.track.id==row.track.id&&old.reason!="NATURAL_END_OF_TRACK"&&!isSkip(old.reason)&&row.start-(old.start+old.ms) in -2000..1800000&&(kotlin.math.abs(row.startPosition-old.endPosition)<=2500||old.reason=="SCRUB_BEGIN"||old.reason=="SCRUB_END"))row.visit=old.visit;previous[row.device]=row}
        val fingerprint=hash(keys.sorted().joinToString("\n"));return ApplePlan(fingerprint,label.take(200),rows,ignored,duplicates).also{it.ms;it.from;it.to;it.unknownArtist;it.unknownAlbum;it.inferredStarts}
    }
}
