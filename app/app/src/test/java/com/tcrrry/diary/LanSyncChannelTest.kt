package com.tcrrry.diary

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LanSyncChannelTest {
    @Test fun pairedChannelsExchangeAndRejectReplay(){
        val aInput=PipedInputStream(65536);val bOutput=PipedOutputStream(aInput)
        val bInput=PipedInputStream(65536);val aOutput=PipedOutputStream(bInput)
        val capture=ByteArrayOutputStream()
        val tee=object:OutputStream(){override fun write(b:Int){aOutput.write(b);capture.write(b)};override fun write(b:ByteArray,off:Int,len:Int){aOutput.write(b,off,len);capture.write(b,off,len)};override fun flush(){aOutput.flush()}}
        val executor=Executors.newFixedThreadPool(2);val key=LanSyncCrypto.newKey()
        try{
            val af=executor.submit<LanSyncChannel>{LanSyncChannel.open(aInput,tee,key)}
            val bf=executor.submit<LanSyncChannel>{LanSyncChannel.open(bInput,bOutput,key)}
            val a=af.get(3,TimeUnit.SECONDS);val b=bf.get(3,TimeUnit.SECONDS)
            a.send("手机记录".toByteArray(Charsets.UTF_8));assertEquals("手机记录",b.receive().toString(Charsets.UTF_8))
            b.send("平板记录".toByteArray(Charsets.UTF_8));assertEquals("平板记录",a.receive().toString(Charsets.UTF_8))
            val replay=capture.toByteArray().copyOfRange(36,capture.size());aOutput.write(replay);aOutput.flush()
            assertTrue(runCatching{b.receive()}.isFailure)
        }finally{aInput.close();bInput.close();aOutput.close();bOutput.close();executor.shutdownNow()}
    }
}
