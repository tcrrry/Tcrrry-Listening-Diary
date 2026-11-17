package com.tcrrry.diary
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class BrowserGroupPairingTest {
 private fun setup(code:String="123456"):Triple<BrowserGroupPairing,String,ByteArray>{
  val manager=BrowserGroupPairing(code,ByteArray(32){7},{listOf("10.0.0.1")})
  val client=LanPairingInvite.keypair();val offer=manager.begin(client.public.encoded);val peer=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(offer.getString("public"))));val shared=KeyAgreement.getInstance("ECDH").apply{init(client.private);doPhase(peer,true)}.generateSecret();val key=MessageDigest.getInstance("SHA-256").digest("ListeningDiary/browser-pair/v1/".toByteArray()+shared);return Triple(manager,offer.getString("session"),key)
 }
 @Test fun sixDigitPairingYieldsLimitedCredentialOnce(){val (p,id,key)=setup();val proof=JSONObject().put("type","browser-pair-proof-v1").put("code","123456").toString().toByteArray();p.prove(id,LanSyncCrypto.seal(key,proof));val out=JSONObject(String(LanSyncCrypto.open(key,p.result(id)!!)));assertEquals("browser-pair-result-v1",out.getString("type"));assertArrayEquals(ByteArray(32){7},Base64.getUrlDecoder().decode(out.getString("group")));assertNull(p.result(id))}
 @Test fun wrongCodeCannotBeRetriedWithinSameSession(){val (p,id,key)=setup();val proof=JSONObject().put("type","browser-pair-proof-v1").put("code","000000").toString().toByteArray();assertThrows(Exception::class.java){p.prove(id,LanSyncCrypto.seal(key,proof))};assertNull(p.result(id))}
}
