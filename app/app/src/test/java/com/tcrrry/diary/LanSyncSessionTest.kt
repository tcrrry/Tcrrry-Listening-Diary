package com.tcrrry.diary

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class LanSyncSessionTest {
    private class Store(private val source:String,count:Int):LanSyncStore {
        val journal=(1..count).map{JSONObject().put("seq",it).put("id","$source:$it").put("ms",60000)}
        val received=linkedMapOf<String,Long>();private val cursors=mutableMapOf<String,Long>()
        var interruptAfterCommit=false
        override fun cursor(peer:String)=cursors[peer]?:0L
        override fun changesAfter(cursor:Long,limit:Int,skipOrigin:String?)=journal.filter{it.getLong("seq")>cursor}.take(limit)
        override fun apply(peer:String,expectedCursor:Long,changes:List<JSONObject>){
            require(cursor(peer)==expectedCursor)
            changes.forEach{received[it.getString("id")]=it.getLong("ms")}
            if(changes.isNotEmpty())cursors[peer]=changes.last().getLong("seq")
            if(interruptAfterCommit){interruptAfterCommit=false;throw java.io.IOException("simulated disconnect after commit")}
        }
    }
    private fun exchange(a:Store,b:Store){
        val ai=PipedInputStream(1_048_576);val bo=PipedOutputStream(ai)
        val bi=PipedInputStream(1_048_576);val ao=PipedOutputStream(bi)
        val pool=Executors.newFixedThreadPool(2);val key=LanSyncCrypto.newKey()
        try{
            val firstName=java.util.concurrent.atomic.AtomicReference<String>()
            val secondName=java.util.concurrent.atomic.AtomicReference<String>()
            val firstHints=java.util.concurrent.atomic.AtomicReference<List<LanPeerHint>>()
            val hint=LanPeerHint("c".repeat(32),"平板",listOf("10.42.0.20"))
            val fa=pool.submit<String>{LanSyncSession.initiate(LanSyncChannel.open(ai,ao,key),"a".repeat(32),a,ownName="手机",onPeer={_,name->firstName.set(name)},onHints={firstHints.set(it)})}
            val fb=pool.submit<String>{LanSyncSession.accept(LanSyncChannel.open(bi,bo,key),"b".repeat(32),b,ownName="平板",onPeer={_,name->secondName.set(name)},hints=listOf(hint))}
            assertEquals("b".repeat(32),fa.get(10,TimeUnit.SECONDS));assertEquals("a".repeat(32),fb.get(10,TimeUnit.SECONDS))
            assertEquals("平板",firstName.get());assertEquals("手机",secondName.get())
            assertEquals(listOf(hint),firstHints.get())
        }finally{ai.close();bi.close();ao.close();bo.close();pool.shutdownNow()}
    }
    @Test fun twoDevicesExchangeManyBatchesAndRepeatWithoutDuplicates(){
        val phone=Store("phone",150);val tablet=Store("tablet",135)
        exchange(phone,tablet);assertEquals(135,phone.received.size);assertEquals(150,tablet.received.size)
        assertEquals(135L,phone.cursor("b".repeat(32)));assertEquals(150L,tablet.cursor("a".repeat(32)))
        exchange(phone,tablet);assertEquals(135,phone.received.size);assertEquals(150,tablet.received.size)
        assertEquals(285L*60000,phone.journal.sumOf{it.getLong("ms")}+phone.received.values.sum())
    }
    @Test fun resumesWhenAcknowledgementIsLostAfterACommittedBatch(){
        val phone=Store("phone",550);val tablet=Store("tablet",535)
        phone.interruptAfterCommit=true
        assertThrows(java.util.concurrent.ExecutionException::class.java){exchange(phone,tablet)}
        assertEquals(256,phone.received.size)
        assertEquals(256L,phone.cursor("b".repeat(32)))
        exchange(phone,tablet)
        assertEquals(535,phone.received.size);assertEquals(550,tablet.received.size)
        exchange(phone,tablet)
        assertEquals(535,phone.received.size);assertEquals(550,tablet.received.size)
    }
}
