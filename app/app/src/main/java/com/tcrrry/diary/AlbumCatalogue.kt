package com.tcrrry.diary

import android.util.AtomicFile
import java.io.File
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

internal data class AlbumCatalogueTrack(val id:String,val title:String,val artist:String,val disc:Int,val number:Int,val duration:Long)
internal data class AlbumCatalogue(val id:String,val title:String,val artist:String,val total:Int,val tracks:List<AlbumCatalogueTrack>,val fetchedAt:Long){val complete get()=total>0&&tracks.size==total}
/** Release-level catalog data is independent of playback history: zero-play tracks never enter the ledger. */
internal object AlbumCatalogueStore{
    private fun folder(art:File)=File(art,"album-tracks-v1").apply{mkdirs()}
    private fun file(art:File,title:String,artist:String)=AtomicFile(File(folder(art),ArtworkKeys.hash("${normalizedCatalogName(title)}|${normalizedCatalogName(artist)}")+".json"))
    fun compatible(title:String,artist:String,row:JSONObject):Boolean =
        AppleTrackImages.albumName(title)==AppleTrackImages.albumName(row.optString("collectionName"))&&AppleTrackImages.artistMatches(artist,row.optString("artistName"))
    /** Song endpoints may expose a per-disc trackCount, which is not an album denominator. */
    fun trackFactTotal(rows:List<JSONObject>):Int?{
        if(rows.isEmpty()||rows.any{it.optInt("discCount",1)>1})return null
        val ids=rows.map{it.optString("collectionId")}.distinct()
        if(ids.size!=1||!ids.single().matches(Regex("[0-9]{5,22}")))return null
        val totals=rows.map{it.optInt("trackCount")}
        if(totals.any{it<=0})return null
        return totals.distinct().singleOrNull()
    }
    fun parse(response:JSONObject,title:String,artist:String,trustedId:String?=null):AlbumCatalogue?{
        val rows=response.optJSONArray("results")?.objects().orEmpty()
        val header=rows.firstOrNull{it.optString("wrapperType")=="collection"&&AppleTrackImages.albumName(title)==AppleTrackImages.albumName(it.optString("collectionName"))&&(compatible(title,artist,it)||it.optString("collectionId")==trustedId)}?:return null
        val id=header.optString("collectionId");if(!id.matches(Regex("[0-9]{5,22}")))return null
        val tracks=rows.filter{it.optString("kind")=="song"&&it.optString("collectionId")==id&&it.optString("trackId").matches(Regex("[0-9]{5,22}"))&&it.optString("trackName").isNotBlank()}
            .map{AlbumCatalogueTrack(it.optString("trackId"),it.optString("trackName"),it.optString("artistName"),it.optInt("discNumber",1),it.optInt("trackNumber"),it.optLong("trackTimeMillis"))}.distinctBy{it.id}.sortedWith(compareBy<AlbumCatalogueTrack>{it.disc}.thenBy{it.number})
        if(tracks.isEmpty()||tracks.size>1000)return null
        return AlbumCatalogue(id,header.optString("collectionName"),header.optString("artistName"),header.optInt("trackCount"),tracks,System.currentTimeMillis())
    }
    fun cached(art:File,title:String,artist:String):AlbumCatalogue?=runCatching{
        val f=file(art,title,artist);if(!f.baseFile.exists()||f.baseFile.length()>2*1024*1024)return null
        val data=f.openRead().bufferedReader().use{JSONObject(it.readText())}
        parse(data.getJSONObject("response"),title,artist,data.optString("verifiedId"))?.copy(fetchedAt=data.optLong("at"))
    }.getOrNull()
    private fun save(art:File,title:String,artist:String,response:JSONObject){
        val target=file(art,title,artist);val out=target.startWrite()
        try{out.write(JSONObject().put("at",System.currentTimeMillis()).put("verifiedId",response.optJSONArray("results")?.objects()?.firstOrNull{it.optString("wrapperType")=="collection"}?.optString("collectionId")).put("response",response).toString().toByteArray(Charsets.UTF_8));target.finishWrite(out)}catch(t:Throwable){target.failWrite(out);throw t}
        val files=folder(art).listFiles{f->f.name.endsWith(".json")}.orEmpty().sortedBy{it.lastModified()};var bytes=files.sumOf{it.length()}
        for((index,f) in files.withIndex()){if(files.size-index<=200&&bytes<=12*1024*1024)break;val length=f.length();if(f.delete())bytes-=length}
    }
    fun load(art:File,target:WeeklyTarget,hints:JSONArray,request:(String)->JSONObject):AlbumCatalogue?{
        val title=target.play.album;val artist=target.play.artist
        val ids=hints.objects().filter{AppleTrackImages.albumName(title)==AppleTrackImages.albumName(it.optString("collectionName"))}.map{it.optString("collectionId")}.filter{it.matches(Regex("[0-9]{5,22}"))}.distinct().take(2)
        val existing=cached(art,title,artist)?.takeIf{ids.isEmpty()||it.id in ids}
        if(existing?.complete==true&&System.currentTimeMillis()-existing.fetchedAt<7*24*3600000L)return existing
        var best=existing;var budget=6
        fun fetch(url:String):JSONObject?{if(budget--<=0)return null;return try{request(url)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){null}}
        fun lookup(id:String,country:String):AlbumCatalogue?{
            val raw=fetch("https://itunes.apple.com/lookup?id=$id&entity=song&limit=1000&country=$country")?:return null
            val value=parse(raw,title,artist,id.takeIf{it in ids})?:return null
            if(value.complete||best==null||value.tracks.size>best!!.tracks.size){save(art,title,artist,raw);best=value}
            return value
        }
        for(country in listOf("hk","jp","us","cn")){
            if(budget<=0)break
            for(id in ids){val value=lookup(id,country);if(value?.complete==true)return value}
            if(ids.isEmpty()||country=="cn"){
                val term=URLEncoder.encode("$title $artist","UTF-8")
                val search=fetch("https://itunes.apple.com/search?term=$term&entity=album&limit=10&country=$country")
                val candidates=search?.optJSONArray("results")?.objects().orEmpty().filter{compatible(title,artist,it)}.take(1)
                for(row in candidates){val id=row.optString("collectionId");if(!id.matches(Regex("[0-9]{5,22}")))continue;val value=lookup(id,country);if(value?.complete==true)return value}
            }
        }
        return best
    }
}
