package com.tcrrry.diary

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/** One paired connection. Both endpoints must call open concurrently. */
internal class LanSyncChannel private constructor(
    private val input:DataInputStream,private val output:DataOutputStream,
    private val key:ByteArray,private val ownChallenge:ByteArray,private val peerChallenge:ByteArray
) {
    companion object {
        private const val MAGIC=0x4c443031
        private const val CHALLENGE_SIZE=32
        private const val HEADER_SIZE=CHALLENGE_SIZE+8
        const val MAX_PAYLOAD_BYTES=LanSyncCrypto.MAX_PLAINTEXT_BYTES-HEADER_SIZE
        fun open(input:InputStream,output:OutputStream,key:ByteArray):LanSyncChannel {
            require(key.size==LanSyncCrypto.KEY_BYTES)
            val source=DataInputStream(input);val sink=DataOutputStream(output)
            val challenge=ByteArray(CHALLENGE_SIZE).also{SecureRandom().nextBytes(it)}
            sink.writeInt(MAGIC);sink.write(challenge);sink.flush()
            require(source.readInt()==MAGIC){"Unsupported LAN protocol"}
            val peer=ByteArray(CHALLENGE_SIZE);source.readFully(peer)
            return LanSyncChannel(source,sink,key.copyOf(),challenge,peer)
        }
    }
    private var sendSequence=0L
    private var receiveSequence=0L
    @Synchronized fun send(payload:ByteArray){
        require(payload.size<=MAX_PAYLOAD_BYTES)
        check(sendSequence<Long.MAX_VALUE)
        val plain=ByteBuffer.allocate(HEADER_SIZE+payload.size).put(peerChallenge).putLong(++sendSequence).put(payload).array()
        val sealed=LanSyncCrypto.seal(key,plain)
        output.writeInt(sealed.size);output.write(sealed);output.flush()
    }
    fun receive():ByteArray {
        val length=input.readInt()
        require(length in (LanSyncCrypto.NONCE_BYTES+16+HEADER_SIZE)..(LanSyncCrypto.MAX_PLAINTEXT_BYTES+LanSyncCrypto.NONCE_BYTES+16))
        val sealed=ByteArray(length);input.readFully(sealed)
        val plain=LanSyncCrypto.open(key,sealed);val buffer=ByteBuffer.wrap(plain)
        val challenge=ByteArray(CHALLENGE_SIZE);buffer.get(challenge)
        require(MessageDigest.isEqual(challenge,ownChallenge)){"Wrong connection challenge"}
        val sequence=buffer.long;require(receiveSequence<Long.MAX_VALUE&&sequence==receiveSequence+1){"Out of order or replayed message"}
        receiveSequence=sequence
        return ByteArray(buffer.remaining()).also(buffer::get)
    }
}
