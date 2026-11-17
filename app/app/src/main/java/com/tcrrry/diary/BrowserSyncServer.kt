package com.tcrrry.diary

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class BrowserSyncDevice(val id:String,val name:String,val lastSync:Long)
/** Browser-only local import endpoint. Payload and acknowledgement are AES-GCM authenticated. */
internal class BrowserSyncServer(private val context:Context,private val groupKey:()->ByteArray?={null},private val addresses:()->List<String> = {emptyList()},private val deviceName:()->String={android.os.Build.MODEL},private val changed:()->Unit){
 companion object {const val PORT=38989;private const val MAX_BODY=400_000}
 private val prefs=context.getSharedPreferences("browser_sync",Context.MODE_PRIVATE)
 private val main=Handler(Looper.getMainLooper());private val running=AtomicBoolean(false)
 private val executor=Executors.newSingleThreadExecutor();private val generation=java.util.concurrent.atomic.AtomicInteger();@Volatile private var listener:ServerSocket?=null
 @Volatile private var pairing:BrowserGroupPairing?=null
 fun devices():List<BrowserSyncDevice>{val json=runCatching{JSONObject(prefs.getString("devices","{}")!!)}.getOrDefault(JSONObject());return json.keys().asSequence().mapNotNull{id->val row=json.optJSONObject(id)?:return@mapNotNull null;BrowserSyncDevice(id,row.optString("name","网页端 · Chrome"),row.optLong("last"))}.sortedByDescending{it.lastSync}.take(32).toList()}
 private fun rememberDevice(id:String,name:String){val old=devices().filter{it.id!=id};val json=JSONObject();for(d in (listOf(BrowserSyncDevice(id,name,System.currentTimeMillis()))+old).take(32))json.put(d.id,JSONObject().put("name",d.name).put("last",d.lastSync));check(prefs.edit().putString("devices",json.toString()).commit())}
 val enabled:Boolean get()=prefs.getBoolean("enabled",false)||groupKey()!=null
 fun beginGroupPairing(code:String){val key=groupKey()?:error("同步组尚未准备好");pairing=BrowserGroupPairing(code,key,addresses);start()}
 fun cancelGroupPairing(){pairing?.cancel();pairing=null}
 fun pairingCode():String {val old=prefs.getString("key",null);if(old!=null)return old;val key=Base64.getUrlEncoder().withoutPadding().encodeToString(LanSyncCrypto.newKey());check(prefs.edit().putString("key",key).putBoolean("enabled",true).commit());return key}
 fun start(){if(!enabled||!running.compareAndSet(false,true))return;val current=generation.incrementAndGet();executor.execute{try{if(generation.get()!=current)return@execute;val server=ServerSocket(PORT).also{listener=it};while(running.get()&&generation.get()==current){val socket=try{server.accept()}catch(_:SocketException){break};socket.use{try{serve(it)}catch(_:Exception){}}}}catch(_:Exception){}finally{if(generation.get()==current){running.set(false);listener=null}}}}
 fun stop(){generation.incrementAndGet();running.set(false);runCatching{listener?.close()};listener=null}
 fun close(){stop();executor.shutdownNow()}
 fun revoke(){stop();prefs.edit().clear().commit()}
 private fun serve(socket:Socket){
  val peer=socket.inetAddress;if(peer !is Inet4Address||(!peer.isSiteLocalAddress&&!peer.isLoopbackAddress))return
  socket.soTimeout=8000;val input=socket.getInputStream();val header=StringBuilder();while(!header.endsWith("\r\n\r\n")){val c=input.read();require(c>=0&&header.length<8192);header.append(c.toChar())}
  val lines=header.toString().split("\r\n");val path=lines.first().split(' ').let{require(it.size==3&&it[0]=="POST"&&it[2]=="HTTP/1.1");it[1]};require(path in listOf("/diary-sync","/pair-begin","/pair-proof","/pair-result"));val headers=lines.drop(1).mapNotNull{val k=it.indexOf(':');if(k<=0)null else it.substring(0,k).trim().lowercase() to it.substring(k+1).trim()}.toMap();val len=headers["content-length"]?.toIntOrNull()?:return;require(len in 1..MAX_BODY)
  val data=ByteArray(len);var n=0;while(n<len){val got=input.read(data,n,len-n);require(got>0);n+=got}
  if(path!="/diary-sync"){
   require(len<=8192);val p=pairing?:return;val request=JSONObject(String(data,Charsets.UTF_8));val result=when(path){
    "/pair-begin"->p.begin(Base64.getDecoder().decode(request.getString("public")))
    "/pair-proof"->{p.prove(request.getString("session"),Base64.getDecoder().decode(request.getString("sealed")));JSONObject().put("pending",true)}
    else->{val sealed=p.result(request.getString("session"));if(sealed==null)JSONObject().put("pending",true)else JSONObject().put("sealed",Base64.getEncoder().encodeToString(sealed))}
   };reply(socket,headers,result.toString().toByteArray(Charsets.UTF_8));return
  }
  val decoded=Base64.getDecoder().decode(data);val candidates=listOfNotNull(groupKey(),prefs.getString("key",null)?.let{Base64.getUrlDecoder().decode(it)});var key:ByteArray?=null;var plain:ByteArray?=null
  for(candidate in candidates){val value=runCatching{LanSyncCrypto.open(candidate,decoded)}.getOrNull();if(value!=null){key=candidate;plain=value;break}}
  val selected=key?:return;val request=JSONObject(String(plain?:return,Charsets.UTF_8))
  require(request.getString("type")=="browser-import-v1");val id=request.getString("id");require(id.matches(Regex("[A-Za-z0-9_-]{16,80}")))
  val now=System.currentTimeMillis();val at=request.getLong("at");require(kotlin.math.abs(now-at)<=600_000){"请求已过期，请校准设备时间"}
  val replay=context.getSharedPreferences("browser_sync_replay",Context.MODE_PRIVATE);require(!replay.contains(id)){"重复请求"};val edit=replay.edit();for((k,v) in replay.all)if(v is Long&&now-v>1_200_000)edit.remove(k);check(edit.putLong(id,now).commit())
  val tracks=request.getJSONArray("tracks");val spans=request.getJSONArray("spans");require(tracks.length()<=128&&spans.length()<=128)
  for(i in 0 until spans.length()){val row=spans.getJSONObject(i);require(row.getString("remote_id").startsWith("web:")&&row.getString("visit").startsWith("web:"))}
  val payload=JSONObject().put("format","listening-diary-app").put("sourceId","web_preview").put("tracks",tracks).put("spans",spans)
  val count=if(spans.length()>0)DiaryDatabase.get(context).ingest(payload,true,true) else 0
  val client=request.optJSONObject("client");val clientId=client?.optString("id").orEmpty();val clientName=client?.optString("name").orEmpty().filterNot{it.isISOControl()}.trim().take(48)
  val validClient=clientId.matches(Regex("[a-f0-9]{32}"));if(validClient)rememberDevice(clientId,clientName.ifBlank{"网页端 · Chrome"})
  val reply=JSONObject().put("type","browser-ack-v1").put("id",id).put("count",count).put("deviceName",deviceName().filterNot{it.isISOControl()}.trim().take(48)).put("addresses",JSONArray(addresses().filter(LanPeerHints::isPrivateAddress).distinct().take(24))).toString().toByteArray(Charsets.UTF_8)
  val body=Base64.getEncoder().encode(LanSyncCrypto.seal(selected,reply));reply(socket,headers,body);if(count>0||validClient)main.post{changed()}
 }
 private fun reply(socket:Socket,headers:Map<String,String>,body:ByteArray){val origin=headers["origin"].orEmpty();val cors=if(origin.matches(Regex("chrome-extension://[a-p]{32}")))"Access-Control-Allow-Origin: $origin\r\n" else "";socket.getOutputStream().apply{write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n${cors}Cache-Control: no-store\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII));write(body);flush()}}
}
