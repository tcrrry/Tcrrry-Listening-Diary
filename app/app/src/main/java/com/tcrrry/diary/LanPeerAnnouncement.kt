package com.tcrrry.diary

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Fixed-size discovery packet: the shared key itself is never broadcast. */
internal object LanPeerAnnouncement {
    private const val MAGIC=0x4c444431
    private const val ID_BYTES=16
    private const val TAG_BYTES=16
    private const val BODY_BYTES=4+ID_BYTES+2
    internal data class Peer(val id:ByteArray,val port:Int)

    private fun tag(key:ByteArray,body:ByteArray):ByteArray {
        require(key.size==LanSyncCrypto.KEY_BYTES)
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(key,"HmacSHA256"))
        mac.update("ListeningDiary/discovery/v1".toByteArray(Charsets.UTF_8));return mac.doFinal(body).copyOf(TAG_BYTES)
    }
    fun encode(key:ByteArray,id:ByteArray,port:Int):ByteArray {
        require(id.size==ID_BYTES&&port in 1..65535)
        val body=ByteBuffer.allocate(BODY_BYTES).putInt(MAGIC).put(id).putShort(port.toShort()).array()
        return body+tag(key,body)
    }
    fun decode(key:ByteArray,ownId:ByteArray,packet:ByteArray):Peer? {
        require(key.size==LanSyncCrypto.KEY_BYTES&&ownId.size==ID_BYTES)
        if(packet.size!=BODY_BYTES+TAG_BYTES)return null
        val body=packet.copyOfRange(0,BODY_BYTES);val received=packet.copyOfRange(BODY_BYTES,packet.size)
        if(!MessageDigest.isEqual(tag(key,body),received))return null
        val buffer=ByteBuffer.wrap(body);if(buffer.int!=MAGIC)return null
        val id=ByteArray(ID_BYTES);buffer.get(id);val port=buffer.short.toInt() and 0xffff
        if(port==0||MessageDigest.isEqual(id,ownId))return null
        return Peer(id,port)
    }
}
