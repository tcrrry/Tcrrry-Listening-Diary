package com.tcrrry.diary

import org.json.JSONArray
import org.json.JSONObject

/** Storage implementation must apply a batch and its cursor atomically. */
internal interface LanSyncStore {
    fun cursor(peer:String):Long
    fun replicaId():String?=null
    fun changesAfter(cursor:Long,limit:Int,skipOrigin:String?=null):List<JSONObject>
    fun apply(peer:String,expectedCursor:Long,changes:List<JSONObject>)
}

/** Small request/ack batches; a disconnected peer resumes at its durable cursor. */
internal object LanSyncSession {
    private const val BATCH=256
    private const val MAX_BATCHES=4096
    private val validId=Regex("[a-f0-9]{32}")
    private fun send(c:LanSyncChannel,value:JSONObject)=c.send(value.toString().toByteArray(Charsets.UTF_8))
    private fun receive(c:LanSyncChannel)=JSONObject(c.receive().toString(Charsets.UTF_8))
    private fun hello(id:String,store:LanSyncStore,name:String,hints:List<LanPeerHint>)=JSONObject().put("type","hello").put("version",1).put("device",id).put("batch",BATCH).put("skip",true).put("name",name.take(48)).put("hints",LanPeerHints.encode(hints)).apply{store.replicaId()?.let{put("replica",it)}}
    private data class Peer(val id:String,val batch:Int,val replica:String?,val name:String,val hints:List<LanPeerHint>)
    private fun peer(msg:JSONObject,own:String):Peer {
        require(msg.getString("type")=="hello"&&msg.getInt("version")==1)
        val id=msg.getString("device");require(validId.matches(id)&&id!=own)
        val limit=msg.optInt("batch",64);require(limit in 1..BATCH)
        val replica=msg.optString("replica").takeIf{msg.optBoolean("skip")&&validId.matches(it)}
        val name=msg.optString("name").filter{!it.isISOControl()}.trim().take(48).ifBlank{"设备"}
        return Peer(id,limit,replica,name,LanPeerHints.decode(msg.optJSONArray("hints")))
    }
    fun initiate(c:LanSyncChannel,own:String,store:LanSyncStore,expectedPeer:String?=null,onProgress:(Int)->Unit={},ownName:String="",onPeer:(String,String)->Unit={_,_->},hints:List<LanPeerHint> = emptyList(),onHints:(List<LanPeerHint>)->Unit={}):String {
        require(validId.matches(own));send(c,hello(own,store,ownName,hints));val (remote,limit,replica,name,remoteHints)=peer(receive(c),own)
        require(expectedPeer==null||remote==expectedPeer){"Discovery identity changed"}
        onPeer(remote,name)
        onHints(remoteHints)
        pull(c,remote,store,limit,onProgress);serve(c,store,limit,replica,onProgress);return remote
    }
    fun accept(c:LanSyncChannel,own:String,store:LanSyncStore,onProgress:(Int)->Unit={},ownName:String="",onPeer:(String,String)->Unit={_,_->},hints:List<LanPeerHint> = emptyList(),onHints:(List<LanPeerHint>)->Unit={}):String {
        require(validId.matches(own));val (remote,limit,replica,name,remoteHints)=peer(receive(c),own);send(c,hello(own,store,ownName,hints))
        onPeer(remote,name)
        onHints(remoteHints)
        serve(c,store,limit,replica,onProgress);pull(c,remote,store,limit,onProgress);return remote
    }
    private fun pull(c:LanSyncChannel,remote:String,store:LanSyncStore,limit:Int,onProgress:(Int)->Unit){
        var cursor=store.cursor(remote);require(cursor>=0)
        send(c,JSONObject().put("type","pull").put("cursor",cursor))
        repeat(MAX_BATCHES){
            val response=receive(c);require(response.getString("type")=="batch")
            require(response.getLong("after")==cursor)
            val rows=response.getJSONArray("changes");require(rows.length()<=limit)
            val changes=(0 until rows.length()).map{rows.getJSONObject(it)}
            var last=cursor
            for(row in changes){val seq=row.getLong("seq");require(seq>last);last=seq}
            if(changes.isNotEmpty())store.apply(remote,cursor,changes)
            cursor=last;send(c,JSONObject().put("type","ack").put("cursor",cursor))
            if(changes.isNotEmpty())onProgress(changes.size)
            if(changes.isEmpty())return
        }
        // Yield after a bounded amount of work. The next session resumes from
        // the durable cursor instead of rejecting large historical libraries.
    }
    private fun serve(c:LanSyncChannel,store:LanSyncStore,limit:Int,skipOrigin:String?,onProgress:(Int)->Unit){
        val request=receive(c);require(request.getString("type")=="pull")
        var cursor=request.getLong("cursor");require(cursor>=0)
        repeat(MAX_BATCHES){
            val candidates=store.changesAfter(cursor,limit,skipOrigin);require(candidates.size<=limit)
            val changes=ArrayList<JSONObject>()
            // Reserve room for the envelope, separators and the channel header.
            var encodedSize=128
            for(candidate in candidates){
                val bytes=candidate.toString().toByteArray(Charsets.UTF_8).size+1
                if(encodedSize+bytes>LanSyncChannel.MAX_PAYLOAD_BYTES){require(changes.isNotEmpty()){ "Sync row exceeds the message limit" };break}
                changes.add(candidate)
                encodedSize+=bytes
            }
            var last=cursor;for(row in changes){val seq=row.getLong("seq");require(seq>last);last=seq}
            send(c,JSONObject().put("type","batch").put("after",cursor).put("changes",JSONArray(changes)))
            val ack=receive(c);require(ack.getString("type")=="ack"&&ack.getLong("cursor")==last)
            if(changes.isNotEmpty())onProgress(changes.size)
            cursor=last;if(changes.isEmpty())return
        }
        // A later session continues at the last acknowledged cursor.
    }
}
