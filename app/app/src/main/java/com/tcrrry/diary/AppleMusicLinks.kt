package com.tcrrry.diary

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.io.File
import java.net.URLEncoder
import java.net.URL
import org.json.JSONObject

internal object AppleAlbumLinks {
    fun fromResponse(response:JSONObject, trackId:String, albumId:String, title:String, artist:String):String? {
        val rows=response.optJSONArray("results") ?: return null
        for(i in 0 until rows.length()){
            val row=rows.optJSONObject(i) ?: continue
            val collection=row.optString("collectionId")
            if(!collection.matches(Regex("[0-9]{5,22}"))) continue
            if(row.optString("trackId")!=trackId && (albumId.isEmpty() || collection!=albumId)) continue
            if(AppleTrackImages.albumName(title)!=AppleTrackImages.albumName(row.optString("collectionName"))) continue
            if(!AppleTrackImages.artistMatches(artist,row.optString("artistName")) && collection!=albumId) continue
            val url=runCatching { java.net.URI(row.optString("collectionViewUrl")) }.getOrNull() ?: continue
            if(url.scheme!="https" || url.host!="music.apple.com" || url.userInfo!=null || url.port!=-1 || !url.path.matches(Regex("/[a-z]{2}/album/[^/]+/$collection/?"))) continue
            return "https://music.apple.com"+url.rawPath
        }
        return null
    }
    fun resolve(art:File,trackId:String,albumId:String,title:String,artist:String,request:(String)->JSONObject):String? {
        val key=ArtworkKeys.hash("album-link-v1:$trackId:$albumId:$title:$artist")
        val file=File(File(art,"album-links-v1"),"$key.txt")
        val saved=runCatching { if(file.length() in 1..2048) file.readText() else "" }.getOrDefault("")
        if(saved.matches(Regex("""https://music\.apple\.com/[a-z]{2}/album/[^/?#]+/[0-9]{5,22}/?"""))) return saved
        val id=trackId.ifEmpty{albumId}.takeIf{it.matches(Regex("[0-9]{5,22}"))} ?: return null
        for(country in listOf("cn","jp","us","hk")){
            val url=runCatching { fromResponse(request("https://itunes.apple.com/lookup?id=$id&country=$country"),trackId,albumId,title,artist) }.getOrNull() ?: continue
            runCatching { file.parentFile?.mkdirs(); file.writeText(url) }
            return url
        }
        return null
    }
}

/** Resolve artist IDs from an actual catalog song; never guess a same-name artist. */
internal object AppleArtistLinks {
    fun fromResponse(response:JSONObject,trackId:String,name:String):String? {
        val rows=response.optJSONArray("results")?:return null
        for(i in 0 until rows.length()){
            val row=rows.optJSONObject(i)?:continue
            if(row.optString("trackId")!=trackId)continue
            if(AppleTrackImages.normalized(name)!=AppleTrackImages.normalized(row.optString("artistName")))continue
            val id=row.optString("artistId")
            if(id.matches(Regex("[0-9]{3,22}")))return id
        }
        return null
    }
    fun resolve(art:File,trackId:String,name:String,request:(String)->JSONObject):String? {
        if(!trackId.matches(Regex("[0-9]{5,22}")))return null
        for(country in listOf("cn","jp","us")){
            val id=runCatching{fromResponse(request("https://itunes.apple.com/lookup?id=$trackId&country=$country"),trackId,name)}.getOrNull()?:continue
            runCatching{art.mkdirs();File(art,ArtworkKeys.hash("apple-identity:$name")+".artist").writeText(id)}
            return id
        }
        return null
    }
}

/** Reuses verified local IDs and caches regional album links after a lookup. */
internal fun openAppleMusic(c:DiaryController, kind:String, play:Play, name:String){
    Thread {
        val art=File(c.activity.filesDir,"art")
        val matched=TrackMetadata.shared(art).lookup(play)
        val trackId=matched?.optString("trackId").orEmpty().ifEmpty { play.id.takeIf{it.matches(Regex("[0-9]{5,22}"))}.orEmpty() }
        val albumId=if(kind=="album") {
            val cached=AlbumCatalogueStore.cached(art,name,play.artist)?.id.orEmpty()
            val row=CatalogMetadata.cached(art,listOf(play.id)).optJSONObject(0)
            cached.ifEmpty { if(row!=null && AlbumCatalogueStore.compatible(name,play.artist,row)) row.optString("collectionId") else "" }
        } else ""
        val albumUrl=if(kind=="album") AppleAlbumLinks.resolve(art,trackId,albumId,name,play.artist){ address ->
            val connection=URL(address).openConnection().apply { connectTimeout=3500;readTimeout=3500 }
            connection.getInputStream().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } else null
        val id=when(kind){
            "song" -> trackId
            "artist" -> {
                val cached=runCatching { File(art,ArtworkKeys.hash("apple-identity:$name")+".artist").readText().trim() }.getOrDefault("")
                cached.ifEmpty { if(matched!=null && AppleTrackImages.normalized(name)==AppleTrackImages.normalized(matched.optString("artistName"))) matched.optString("artistId") else "" }
                    .ifEmpty { AppleArtistLinks.resolve(art,trackId,name){address->
                        val connection=URL(address).openConnection().apply{connectTimeout=2500;readTimeout=2500}
                        connection.getInputStream().bufferedReader(Charsets.UTF_8).use{JSONObject(it.readText())}
                    }.orEmpty() }
            }
            "album" -> albumId
            else -> ""
        }
        val directUrl=albumUrl ?: if(id.matches(Regex("[0-9]{3,22}")) && name.isNotBlank()) {
            val slug=URLEncoder.encode(name,"UTF-8").replace("+","%20")
            "https://music.apple.com/cn/$kind/$slug/$id"
        } else null
        val search=directUrl==null&&kind=="artist"&&name.isNotBlank()
        val url=directUrl?:if(search)"https://music.apple.com/cn/search?term="+URLEncoder.encode(name,"UTF-8").replace("+","%20")else null
        c.activity.runOnUiThread {
            if(search)Toast.makeText(c.activity,"暂无直接歌手链接，已打开 Apple Music 搜索",Toast.LENGTH_SHORT).show()
            if(url==null) Toast.makeText(c.activity,"暂未找到可确认的 Apple Music 链接",Toast.LENGTH_SHORT).show()
            else try { c.activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
            catch (_:Exception) { Toast.makeText(c.activity,"无法打开 Apple Music 链接",Toast.LENGTH_SHORT).show() }
        }
    }.start()
}
