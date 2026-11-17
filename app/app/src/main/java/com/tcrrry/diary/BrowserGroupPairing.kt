package com.tcrrry.diary

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/** Browser pairing produces a restricted upload key, without exposing the full group key. */
internal class BrowserGroupPairing(private val code:String,private val groupKey:ByteArray,private val addresses:()->List<String> = {emptyList()},private val now:()->Long={System.currentTimeMillis()}) {
 private data class Session(val id:String,val key:ByteArray,val expires:Long,var proved:Boolean=false)
 private var session:Session?=null;private var attempts=0;private val deadline=now()+120_000
 init{require(code.matches(Regex("[0-9]{6}"))&&groupKey.size==32)}
 @Synchronized fun begin(public:ByteArray):JSONObject{
  require(now()<deadline&&attempts<5);require(public.size in 64..256);attempts++
  val peer=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(public));val pair=LanPairingInvite.keypair()
  val shared=KeyAgreement.getInstance("ECDH").apply{init(pair.private);doPhase(peer,true)}.generateSecret()
  val key=MessageDigest.getInstance("SHA-256").digest("ListeningDiary/browser-pair/v1/".toByteArray()+shared)
  val id=ByteArray(16).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it.toInt() and 255)}
  session=Session(id,key,minOf(deadline,now()+120_000));return JSONObject().put("session",id).put("public",Base64.getEncoder().encodeToString(pair.public.encoded))
 }
 @Synchronized fun prove(id:String,sealed:ByteArray){val s=session?:error("配对请求不存在");require(s.id==id&&now()<s.expires&&!s.proved);try{val proof=JSONObject(String(LanSyncCrypto.open(s.key,sealed),Charsets.UTF_8));require(proof.getString("type")=="browser-pair-proof-v1"&&MessageDigest.isEqual(proof.getString("code").toByteArray(),code.toByteArray()));s.proved=true}catch(e:Exception){session=null;throw e}}
 @Synchronized fun result(id:String):ByteArray?{val s=session?:return null;require(s.id==id&&now()<s.expires&&s.proved);session=null;return LanSyncCrypto.seal(s.key,JSONObject().put("type","browser-pair-result-v1").put("group",Base64.getUrlEncoder().withoutPadding().encodeToString(groupKey)).put("addresses",org.json.JSONArray(addresses().filter(LanPeerHints::isPrivateAddress).distinct().take(24))).toString().toByteArray())}
 @Synchronized fun cancel(){session=null}
}
