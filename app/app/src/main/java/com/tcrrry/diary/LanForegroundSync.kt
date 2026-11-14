package com.tcrrry.diary

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Runs while the diary is visible or an approved collector is actively checking in. */
internal data class LanKnownDevice(val id:String,val name:String,val lastSync:Long,val addresses:List<String> = emptyList())
internal class LanCollectorLease {
    private var until=0L
    fun touch(now:Long){until=now+45_000}
    fun clear(){until=0}
    fun active(now:Long)=now<until
}
internal class LanForegroundSync private constructor(context:Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("lan_pairing",Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    private var onStatus:(String)->Unit={}
    private var onChanged:()->Unit={}
    private var onPeers:(List<LanKnownDevice>)->Unit={}
    private var currentStatus="尚未配对"
    private var instance:Runner?=null
    @Volatile private var foreground=false
    private val collectorLease=LanCollectorLease()
    private val expireCollector=Runnable{if(!foreground&&!collectorLease.active(android.os.SystemClock.elapsedRealtime())){instance?.close();instance=null}}
    private var invitation:LanPairingInvite.Invitation?=null
    private val joining=AtomicBoolean(false)
    val paired:Boolean get()=prefs.contains("secret")
    fun bind(status:(String)->Unit,changed:()->Unit,peers:(List<LanKnownDevice>)->Unit){onStatus=status;onChanged=changed;onPeers=peers;status(if(paired&&currentStatus=="尚未配对")"正在准备局域网同步…" else currentStatus)}
    fun unbind(){onStatus={};onChanged={};onPeers={}}
    /** Called only after the provider has authenticated the approved local host. */
    fun collectorHeartbeat(){main.post{
        if(!paired)return@post
        collectorLease.touch(android.os.SystemClock.elapsedRealtime())
        main.removeCallbacks(expireCollector);main.postDelayed(expireCollector,45_100)
        if(instance==null)restart()
    }}
    fun ownName():String=prefs.getString("name",null)?.takeIf{it.isNotBlank()}?:runCatching{android.provider.Settings.Global.getString(app.contentResolver,"device_name")}.getOrNull()?.takeIf{it.isNotBlank()}?.take(48)?:android.os.Build.MODEL.take(48)
    fun rename(name:String){val value=name.filter{!it.isISOControl()}.trim().take(48);require(value.isNotEmpty()){ "设备名称不能为空" };prefs.edit().putString("name",value).apply()}
    fun manualAddress():String=prefs.getString("manual_peer","").orEmpty()
    fun manualAddress(value:String){
        val address=value.trim();val edit=prefs.edit()
        if(address.isEmpty())edit.remove("manual_peer") else{
            require(address.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))){"请输入有效的局域网 IPv4 地址"}
            val parsed=runCatching{java.net.InetAddress.getByName(address)}.getOrNull();require(parsed is Inet4Address&&parsed.isSiteLocalAddress){"请输入有效的局域网 IPv4 地址"}
            edit.putString("manual_peer",address)
        }
        edit.apply()
    }
    fun peers():List<LanKnownDevice>{
        val json=runCatching{org.json.JSONObject(prefs.getString("peers","{}")!!)}.getOrDefault(org.json.JSONObject())
        return json.keys().asSequence().mapNotNull{id->
            val row=json.optJSONObject(id)?:return@mapNotNull null;val a=row.optJSONArray("addresses")?:org.json.JSONArray()
            val addresses=(0 until minOf(a.length(),8)).map{a.optString(it)}.filter(LanPeerHints::isPrivateAddress).distinct().take(4)
            LanKnownDevice(id,row.optString("name","设备"),row.optLong("last"),addresses)
        }.sortedByDescending{it.lastSync}.toList()
    }
    private fun savePeers(all:List<LanKnownDevice>){
        val json=org.json.JSONObject();for(peer in all.take(32))json.put(peer.id,org.json.JSONObject().put("name",peer.name).put("last",peer.lastSync).put("addresses",org.json.JSONArray(peer.addresses)))
        prefs.edit().putString("peers",json.toString()).apply();main.post{onPeers(peers())}
    }
    private fun rememberPeer(id:String,name:String){
        val previous=peers();val addresses=previous.firstOrNull{it.id==id}?.addresses.orEmpty()
        savePeers(listOf(LanKnownDevice(id,name,System.currentTimeMillis(),addresses))+previous.filter{it.id!=id}.take(31))
    }
    private fun rememberHints(hints:List<LanPeerHint>,own:String,direct:String){
        savePeers(LanPeerHints.merge(peers(),hints,own,direct))
    }
    private fun notifyStatus(value:String){main.post{currentStatus=value;onStatus(value)}}
    private fun deviceId():ByteArray {
        val existing=prefs.getString("id",null)
        if(existing!=null)return Base64.getUrlDecoder().decode(existing)
        val id=ByteArray(16).also{SecureRandom().nextBytes(it)}
        check(prefs.edit().putString("id",Base64.getUrlEncoder().withoutPadding().encodeToString(id)).commit())
        return id
    }
    private fun secret():ByteArray?=prefs.getString("secret",null)?.let{Base64.getUrlDecoder().decode(it)}
    fun createCode(onFinished:()->Unit):String {
        val existing=secret()
        val key=existing?:LanSyncCrypto.newKey().also{check(prefs.edit().putString("secret",Base64.getUrlEncoder().withoutPadding().encodeToString(it)).commit())}
        if(existing==null)restart()
        invitation?.close()
        return LanPairingInvite.invite(key){main.post{onFinished()}}.also{invitation=it}.code
    }
    fun localAddresses():List<String> = runCatching{LanNetworks.interfaces().flatMap{it.inetAddresses.toList()}.filterIsInstance<Inet4Address>().filter{it.isSiteLocalAddress}.map{it.hostAddress}.filterNotNull().distinct().take(4)}.getOrDefault(emptyList())
    fun join(code:String,address:String,finished:(String?)->Unit){
        val input=code.trim()
        val target=address.trim().takeIf{it.isNotEmpty()}
        if(target!=null)require(target.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))){"请输入有效的局域网 IPv4 地址"}
        require(input.matches(Regex("[0-9]{6}"))||input.matches(Regex("LD1-[A-Za-z0-9_-]{43}"))){"配对码格式不正确"}
        check(joining.compareAndSet(false,true)){"正在寻找配对设备，请稍候"}
        notifyStatus("正在寻找配对设备…")
        Thread({
            val result=runCatching{
                val key=if(input.startsWith("LD1-"))Base64.getUrlDecoder().decode(input.substring(4))else LanPairingInvite.join(input,target)
                require(key.size==LanSyncCrypto.KEY_BYTES){"配对码格式不正确"}
                val current=secret();require(current==null||java.security.MessageDigest.isEqual(current,key)){"请先解除当前配对，再输入新的配对码"}
                check(foreground){"请保持日记打开，再重新配对"}
                val edit=prefs.edit().putString("secret",Base64.getUrlEncoder().withoutPadding().encodeToString(key));if(target!=null)edit.putString("manual_peer",target)
                check(edit.commit())
            }
            main.post{joining.set(false);result.onSuccess{restart();finished(null)}.onFailure{finished(it.message?:"配对失败，请重试")}}
        },"DiaryPairing").start()
    }
    fun start(){foreground=true;if(instance==null)restart()}
    fun hideCode(){invitation?.close();invitation=null}
    fun stop(){foreground=false;hideCode();if(!collectorLease.active(android.os.SystemClock.elapsedRealtime())){instance?.close();instance=null}}
    fun unpair(){hideCode();collectorLease.clear();main.removeCallbacks(expireCollector);instance?.close();instance=null;check(prefs.edit().remove("secret").remove("peers").remove("manual_peer").commit());main.post{onPeers(emptyList())};notifyStatus("尚未配对")}
    private fun restart(){
        instance?.close();instance=null
        val key=secret()?:return
        if(android.os.Build.VERSION.SDK_INT>=37&&app.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=android.content.pm.PackageManager.PERMISSION_GRANTED){notifyStatus("需要允许局域网权限才能发现并连接设备");return}
        if(foreground||collectorLease.active(android.os.SystemClock.elapsedRealtime()))Runner(key,deviceId()).also{instance=it;it.start()}
    }
    private inner class Runner(private val key:ByteArray,private val id:ByteArray){
        private val running=AtomicBoolean(true)
        private val active=AtomicBoolean(false)
        private val executor=Executors.newCachedThreadPool()
        @Volatile private var udp:DatagramSocket?=null
        @Volatile private var server:ServerSocket?=null
        @Volatile private var connection:Socket?=null
        private val hexId=id.joinToString(""){"%02x".format(it.toInt() and 255)}
        private val lastAttempt=HashMap<String,Long>()
        private val lastReply=HashMap<String,Long>()
        fun start(){executor.execute{
            try{
                val started=android.os.SystemClock.elapsedRealtime()
                notifyStatus("正在准备局域网同步…")
                val store=DiaryDatabase.get(app).lanStore()
                android.util.Log.i("DiaryLan","Store ready in ${android.os.SystemClock.elapsedRealtime()-started}ms")
                if(!running.get())return@execute
                val listener=ServerSocket().apply{reuseAddress=true;bind(InetSocketAddress(0));soTimeout=2000};server=listener
                if(!running.get()){listener.close();return@execute}
                val datagram=DatagramSocket(null).apply{reuseAddress=true;broadcast=true;bind(InetSocketAddress(PORT));soTimeout=2000};udp=datagram
                if(!running.get()){datagram.close();listener.close();return@execute}
                notifyStatus("等待已配对设备加入同一局域网")
                android.util.Log.i("DiaryLan","Discovery ready in ${android.os.SystemClock.elapsedRealtime()-started}ms")
                executor.execute{acceptLoop(listener,store)}
                executor.execute{broadcastLoop(datagram,listener.localPort)}
                receiveLoop(datagram,store)
            }catch(e:Exception){if(running.get()){android.util.Log.w("DiaryLan","Local listener failed: ${e.javaClass.simpleName}");notifyStatus("局域网同步暂不可用，稍后重新打开日记重试")}}
            finally{
                close()
                main.post{if(instance===this@Runner){instance=null;main.postDelayed({if(instance==null&&(foreground||collectorLease.active(android.os.SystemClock.elapsedRealtime())))restart()},8000)}}
            }
        }}
        private fun acceptLoop(listener:ServerSocket,store:LanSyncStore){
            while(running.get())try{
                val socket=listener.accept()
                if(!active.compareAndSet(false,true)){socket.close();continue}
                executor.execute{runSession(socket,store,false,null)}
            }catch(_:SocketTimeoutException){}catch(_:SocketException){break}catch(e:Exception){if(running.get())android.util.Log.w("DiaryLan","Listener stopped: ${e.javaClass.simpleName}");break}
        }
        private fun broadcastLoop(socket:DatagramSocket,port:Int){
            val data=LanPeerAnnouncement.encode(key,id,port)
            while(running.get()){
                try{
                    val addresses=LanNetworks.interfaces().flatMap{it.interfaceAddresses}.mapNotNull{it.broadcast}.filterIsInstance<Inet4Address>().distinct()
                    for(address in addresses)runCatching{socket.send(DatagramPacket(data,data.size,address,PORT))}
                    // Cross-subnet probing is opt-in: only a device configured with
                    // a manual address (e.g. WSA) probes learned private addresses.
                    val manualTarget=prefs.getString("manual_peer",null)
                    val targets=LanPeerHints.targets(manualTarget,peers(),localAddresses())
                    for(target in targets)runCatching{val address=java.net.InetAddress.getByName(target);if(address is Inet4Address&&address.isSiteLocalAddress)socket.send(DatagramPacket(data,data.size,address,PORT))}
                }catch(_:Exception){}
                try{Thread.sleep(8000)}catch(_:InterruptedException){break}
            }
        }
        private fun receiveLoop(socket:DatagramSocket,store:LanSyncStore){
            val buffer=ByteArray(512)
            while(running.get())try{
                val packet=DatagramPacket(buffer,buffer.size);socket.receive(packet)
                val remote=LanPeerAnnouncement.decode(key,id,packet.data.copyOfRange(packet.offset,packet.offset+packet.length))?:continue
                val peer=remote.id.joinToString(""){"%02x".format(it.toInt() and 255)}
                val now=System.currentTimeMillis()
                val replyKey=packet.address.hostAddress+":"+packet.port
                if(now-(lastReply[replyKey]?:0)>5000){lastReply[replyKey]=now;val reply=LanPeerAnnouncement.encode(key,id,server?.localPort?:continue);runCatching{socket.send(DatagramPacket(reply,reply.size,packet.address,packet.port))}}
                val manualTarget=prefs.getString("manual_peer",null)
                val manual=manualTarget!=null&&(packet.address.hostAddress==manualTarget||peers().any{packet.address.hostAddress in it.addresses})
                if((hexId>=peer&&!manual)||now-(lastAttempt[peer]?:0)<7000)continue
                lastAttempt[peer]=now
                executor.execute{
                    if(!manual)try{Thread.sleep(600)}catch(_:InterruptedException){return@execute}
                    if(!running.get()||!active.compareAndSet(false,true))return@execute
                    val client=Socket()
                    try{client.connect(InetSocketAddress(packet.address,remote.port),5000);runSession(client,store,true,peer)}
                    catch(_:Exception){runCatching{client.close()};active.set(false)}
                }
            }catch(_:SocketTimeoutException){}catch(_:SocketException){break}
        }
        private fun runSession(socket:Socket,store:LanSyncStore,initiator:Boolean,peer:String?){
            connection=socket
            try{
                val started=android.os.SystemClock.elapsedRealtime()
                socket.soTimeout=30000;socket.tcpNoDelay=true
                notifyStatus("正在同步局域网设备…")
                val before=app.getSharedPreferences("diary",Context.MODE_PRIVATE).getLong("data_revision",0)
                val channel=LanSyncChannel.open(socket.getInputStream(),socket.getOutputStream(),key)
                var remoteId="";var remoteName="设备"
                var processed=0L;var lastProgress=0L
                val progress:(Int)->Unit={count->processed+=count;val now=android.os.SystemClock.elapsedRealtime();if(now-lastProgress>=2000){lastProgress=now;notifyStatus("正在与 $remoteName 同步…已处理 $processed 条变动")}}
                val onPeer:(String,String)->Unit={id,name->remoteId=id;remoteName=name;notifyStatus("已连接 $name，正在同步…")}
                val hints=listOf(LanPeerHint(hexId,ownName(),localAddresses()))+peers().map{LanPeerHint(it.id,it.name,it.addresses)}
                val onHints:(List<LanPeerHint>)->Unit={incoming->rememberHints(incoming,hexId,remoteId)}
                if(initiator)LanSyncSession.initiate(channel,hexId,store,peer,progress,ownName(),onPeer,hints,onHints)else LanSyncSession.accept(channel,hexId,store,progress,ownName(),onPeer,hints,onHints)
                if(running.get()){
                    android.util.Log.i("DiaryLan","Session complete peer=$remoteName elapsed=${android.os.SystemClock.elapsedRealtime()-started}ms changes=$processed")
                    if(remoteId.isNotEmpty())rememberPeer(remoteId,remoteName)
                    notifyStatus("已与 $remoteName 同步")
                    if(before!=app.getSharedPreferences("diary",Context.MODE_PRIVATE).getLong("data_revision",0))main.post{onChanged()}
                }
            }catch(e:Exception){if(running.get()){android.util.Log.w("DiaryLan","Session interrupted: ${e.javaClass.simpleName}");notifyStatus("同步中断，设备在线时会自动重试")}}
            finally{runCatching{socket.close()};connection=null;active.set(false)}
        }
        fun close(){
            if(!running.getAndSet(false))return
            runCatching{connection?.close()};runCatching{udp?.close()};runCatching{server?.close()};executor.shutdownNow()
        }
    }
    companion object {
        private const val PORT=38987
        @Volatile private var shared:LanForegroundSync?=null
        fun shared(context:Context):LanForegroundSync=shared?:synchronized(this){shared?:LanForegroundSync(context).also{shared=it}}
    }
}
