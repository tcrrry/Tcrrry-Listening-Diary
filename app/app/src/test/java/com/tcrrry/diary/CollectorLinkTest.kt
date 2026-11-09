package com.tcrrry.diary

import android.content.*
import android.os.Looper
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class CollectorLinkTest {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val sent=mutableListOf<Intent>()
    private lateinit var context:Context
    private lateinit var link:CollectorLink
    private var updates=0
    @Before fun setup(){context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {override fun sendBroadcast(intent:Intent){sent+=intent}};context.getSharedPreferences("diary",0).edit().clear().commit();link=CollectorLink(context,scope){updates++}}
    @After fun close(){link.stop();scope.cancel()}
    private fun advance(ms:Long){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))}
    private fun received(){val p=context.getSharedPreferences("diary",0);p.edit().putLong("sync_serial",p.getLong("sync_serial",0)+1).commit()}
    @Test fun approvedForegroundImmediatelyRequestsHostButSendingDoesNotMeanSynced(){link.start(true);assertEquals(1,sent.size);assertEquals(BridgeIdentity.HOST,sent.single().`package`);assertEquals(CollectorLink.ACTION,sent.single().action);assertTrue(sent.single().flags and Intent.FLAG_RECEIVER_FOREGROUND!=0);assertTrue(link.syncing);assertNotEquals("已收到采集器更新",link.status);received();advance(500);assertFalse(link.syncing);assertEquals("已收到采集器更新",link.status)}
    @Test fun retryRecoversCollectorThatWasNotListeningAtFirstRequest(){link.start(true);advance(2100);assertEquals(2,sent.size);received();advance(500);assertFalse(link.syncing);assertEquals("已收到采集器更新",link.status)}
    @Test fun noReplyTimesOutAndManualRetryReallySendsAnotherRequest(){link.start(true);advance(CollectorLink.WAIT_MS+100);assertEquals(3,sent.size);assertFalse(link.syncing);assertTrue(link.status!!.contains("暂未收到采集器回应"));var approvals=0;link.request{approvals++};assertEquals(1,approvals);assertEquals(4,sent.size);assertTrue(link.syncing);received();advance(500);assertFalse(link.syncing)}
    @Test fun foregroundFallbackFindsBatchEvenWhenContentNotificationWasMissed(){link.start(false);assertTrue(sent.isEmpty());received();advance(2000);assertEquals(1,updates);link.stop();received();advance(10000);assertEquals(1,updates);assertFalse(link.syncing)}
    @Test fun returningToForegroundRequestsAgainAndStartIsIdempotent(){link.start(true);link.start(true);assertEquals(1,sent.size);link.stop();advance(15000);assertEquals(1,sent.size);link.start(true);assertEquals(2,sent.size);assertTrue(link.syncing)}
    @Test fun receivedNotificationConfirmsImmediatelyWithoutWaitingForPolling(){link.start(true);received();link.received();assertFalse(link.syncing);assertEquals("已收到采集器更新",link.status);advance(15000);assertEquals(1,sent.size)}
    @Test fun oldTimestampCannotFalselyConfirmOrBlockNewReply(){context.getSharedPreferences("diary",0).edit().putLong("last_sync",Long.MAX_VALUE).commit();link.request();advance(500);assertTrue(link.syncing);received();advance(500);assertEquals("已收到采集器更新",link.status)}
    @Test fun lateRealReplyClearsTimeoutEvenWithoutANewButtonPress(){link.start(true);advance(CollectorLink.WAIT_MS+100);assertTrue(link.status!!.contains("暂未收到"));received();link.received();assertEquals("已收到采集器更新",link.status);assertFalse(link.syncing)}
    @Test fun requestCarriesUriGrantWithoutChangingBroadcastFilterMatching(){link.start(true);val intent=sent.single();assertNull(intent.data);assertEquals(BridgeIdentity.URI,intent.clipData!!.getItemAt(0).uri);assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0);assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION!=0)}
    @Test fun timeoutRecoversAutomaticallyWhileForegroundAndStopsWithScreen(){link.start(true);advance(CollectorLink.WAIT_MS+100);assertFalse(link.syncing);val before=sent.size;advance(CollectorLink.STALE_MS);assertTrue(sent.size>before);link.stop();val stopped=sent.size;advance(60000);assertEquals(stopped,sent.size)}
    @Test fun freshHeartbeatAvoidsExtraAutomaticRequests(){context.getSharedPreferences("diary",0).edit().putLong("last_sync",System.currentTimeMillis()+120000).commit();link.start(true);received();advance(60000);assertEquals(1,sent.size)}
    @Test fun approvalFailureDoesNotSendOrPretendToSucceed(){link.request{throw SecurityException()};assertTrue(sent.isEmpty());assertFalse(link.syncing);assertTrue(link.status!!.contains("连接未完成"))}
}
