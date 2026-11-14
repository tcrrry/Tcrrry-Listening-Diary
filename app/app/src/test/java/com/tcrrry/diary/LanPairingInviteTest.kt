package com.tcrrry.diary

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class LanPairingInviteTest {
    @Test fun onlyMatchingShortCodeAndEphemeralKeysProduceTheSameSecret(){
        val a=LanPairingInvite.keypair();val b=LanPairingInvite.keypair()
        val sender=LanPairingInvite.sessionKey(a.private,b.public.encoded,"021735")
        val receiver=LanPairingInvite.sessionKey(b.private,a.public.encoded,"021735")
        assertArrayEquals(sender,receiver)
        assertFalse(sender.contentEquals(LanPairingInvite.sessionKey(b.private,a.public.encoded,"021736")))
        assertThrows(IllegalArgumentException::class.java){LanPairingInvite.sessionKey(a.private,b.public.encoded,"12345")}
    }
    @Test fun closingAnInviteWhileWaitingDoesNotCrashItsWorker(){
        val errors=java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler{_,error->errors.add(error)}
        try{
            val done=java.util.concurrent.CountDownLatch(1)
            val invite=LanPairingInvite.invite(LanSyncCrypto.newKey()){done.countDown()}
            Thread.sleep(400)
            invite.close()
            assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(errors.toString(),errors.isEmpty())
        }finally{Thread.setDefaultUncaughtExceptionHandler(previous)}
    }
    @Test fun unicastInviteExchangesTheGroupKeyWithoutBroadcastDiscovery(){
        val address=java.net.NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp&&!it.isLoopback}
            .flatMap{it.inetAddresses.toList()}.filterIsInstance<java.net.Inet4Address>().firstOrNull{it.isSiteLocalAddress}
        org.junit.Assume.assumeNotNull(address)
        val expected=LanSyncCrypto.newKey();val done=java.util.concurrent.CountDownLatch(1)
        val invite=LanPairingInvite.invite(expected){done.countDown()}
        try{
            val actual=LanPairingInvite.join(invite.code,address!!.hostAddress,10_000)
            assertArrayEquals(expected,actual)
            assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS))
        }finally{invite.close()}
    }
}
