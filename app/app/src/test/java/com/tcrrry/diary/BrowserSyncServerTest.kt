package com.tcrrry.diary
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.Socket
import java.util.Base64

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class BrowserSyncServerTest {
 private lateinit var server:BrowserSyncServer
 @Before fun setup(){val c=RuntimeEnvironment.getApplication();val f=DiaryDatabase::class.java.getDeclaredField("instance");f.isAccessible=true;val old=f.get(null);if(old!=null){val d=DiaryDatabase::class.java.getDeclaredField("db");d.isAccessible=true;(d.get(old) as android.database.sqlite.SQLiteDatabase).close()};f.set(null,null);c.deleteDatabase("diary.db");java.io.File(c.filesDir,"diary.db").delete();java.io.File(c.filesDir,"diary.db-wal").delete();java.io.File(c.filesDir,"diary.db-shm").delete();c.getSharedPreferences("browser_sync",Context.MODE_PRIVATE).edit().clear().commit();c.getSharedPreferences("browser_sync_replay",Context.MODE_PRIVATE).edit().clear().commit();server=BrowserSyncServer(c){} }
 @After fun finish(){server.close()}
 @Test fun browserWebCryptoCiphertextIsReadable(){val text=javaClass.classLoader!!.getResourceAsStream("browser-crypto-fixture.json")!!.bufferedReader().use{it.readText()};val item=JSONObject(text);val key=Base64.getUrlDecoder().decode(item.getString("key"));val sealed=Base64.getDecoder().decode(item.getString("sealed"));assertEquals(item.getString("payload"),String(LanSyncCrypto.open(key,sealed),Charsets.UTF_8))}
 @Test fun authenticatedBrowserImportIsIdempotent(){val key=Base64.getUrlDecoder().decode(server.pairingCode());server.start();val start=1794700800000L
  val track=JSONObject().put("id","web-song").put("title","song").put("artist","artist").put("album","album").put("duration",60000)
  val span=JSONObject().put("remote_id","web:test:0").put("visit","web:visit").put("track","web-song").put("day","2026-11-15").put("zone","UTC").put("start",start).put("end",start+30000).put("ms",30000)
  val request=JSONObject().put("type","browser-import-v1").put("id","a".repeat(32)).put("at",System.currentTimeMillis()).put("client",JSONObject().put("id","c".repeat(32)).put("name","网页端 · Chrome")).put("tracks",JSONArray().put(track)).put("spans",JSONArray().put(span))
  fun send():JSONObject {var socket:Socket?=null;repeat(30){if(socket==null)try{socket=Socket("127.0.0.1",BrowserSyncServer.PORT)}catch(_:Exception){Thread.sleep(50)}};return socket!!.use{s->s.soTimeout=5000;val body=Base64.getEncoder().encode(LanSyncCrypto.seal(key,request.toString().toByteArray()));s.getOutputStream().apply{write("POST /diary-sync HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray());write(body);flush()};val all=s.getInputStream().readBytes();val boundary="\r\n\r\n".toByteArray();val index=all.indices.first{i->i+4<=all.size&&all.copyOfRange(i,i+4).contentEquals(boundary)};JSONObject(String(LanSyncCrypto.open(key,Base64.getDecoder().decode(all.copyOfRange(index+4,all.size)))))} }
  assertEquals("browser-ack-v1",send().getString("type"));assertEquals("网页端 · Chrome",server.devices().single().name);assertThrows(Exception::class.java){send()};request.put("id","b".repeat(32));assertEquals(1,send().getInt("count"));assertEquals(1,server.devices().size);assertEquals(1,DiaryDatabase.get(RuntimeEnvironment.getApplication()).backup().getJSONArray("spans").length())
 }
 @Test fun browserJoinsGroupThroughSixDigitHttpPairing(){
  val c=RuntimeEnvironment.getApplication();server.close();val restricted=ByteArray(32){9};server=BrowserSyncServer(c,{restricted}){};server.beginGroupPairing("123456");val client=LanPairingInvite.keypair()
  fun post(path:String,body:JSONObject):JSONObject{var socket:Socket?=null;repeat(30){if(socket==null)try{socket=Socket("127.0.0.1",BrowserSyncServer.PORT)}catch(_:Exception){Thread.sleep(50)}};return socket!!.use{s->s.soTimeout=5000;val bytes=body.toString().toByteArray();s.getOutputStream().apply{write("POST /$path HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray());write(bytes);flush()};val all=s.getInputStream().readBytes();val mark="\r\n\r\n".toByteArray();val pos=all.indices.first{i->i+4<=all.size&&all.copyOfRange(i,i+4).contentEquals(mark)};JSONObject(String(all.copyOfRange(pos+4,all.size)))} }
  val offer=post("pair-begin",JSONObject().put("public",Base64.getEncoder().encodeToString(client.public.encoded)));val peer=java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(offer.getString("public"))));val shared=javax.crypto.KeyAgreement.getInstance("ECDH").apply{init(client.private);doPhase(peer,true)}.generateSecret();val key=java.security.MessageDigest.getInstance("SHA-256").digest("ListeningDiary/browser-pair/v1/".toByteArray()+shared);val session=offer.getString("session")
  val proof=JSONObject().put("type","browser-pair-proof-v1").put("code","123456").toString().toByteArray();assertTrue(post("pair-proof",JSONObject().put("session",session).put("sealed",Base64.getEncoder().encodeToString(LanSyncCrypto.seal(key,proof)))).getBoolean("pending"));val result=post("pair-result",JSONObject().put("session",session));val decoded=JSONObject(String(LanSyncCrypto.open(key,Base64.getDecoder().decode(result.getString("sealed")))));assertArrayEquals(restricted,Base64.getUrlDecoder().decode(decoded.getString("group")))
 }

}
