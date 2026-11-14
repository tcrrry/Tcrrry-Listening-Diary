package com.tcrrry.diary

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyAgreement

/** A short-lived invite uses ECDH plus a six-digit code to exchange the long-term key. */
internal object LanPairingInvite {
    private const val PORT=38988
    private const val MAGIC="ListeningDiaryPair1"
    private const val REQUEST="ListeningDiaryPairRequest1"
    private const val LIFETIME_MS=120_000L
    private const val MAX_ATTEMPTS=5
    internal fun keypair()=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
    internal fun sessionKey(privateKey:java.security.PrivateKey,publicBytes:ByteArray,code:String):ByteArray{
        require(publicBytes.size in 64..256&&code.matches(Regex("[0-9]{6}")))
        val publicKey=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes))
        val shared=KeyAgreement.getInstance("ECDH").apply{init(privateKey);doPhase(publicKey,true)}.generateSecret()
        return MessageDigest.getInstance("SHA-256").digest("ListeningDiary/invite/v1/".toByteArray()+shared+code.toByteArray(Charsets.US_ASCII))
    }
    private fun broadcasts():List<Inet4Address> = LanNetworks.interfaces().flatMap{it.interfaceAddresses}.mapNotNull{it.broadcast}.filterIsInstance<Inet4Address>().distinct()

    class Invitation internal constructor(private val secret:ByteArray,val code:String,private val finished:()->Unit):AutoCloseable{
        private val active=AtomicBoolean(true)
        private val executor=Executors.newFixedThreadPool(2)
        @Volatile private var server:ServerSocket?=null
        @Volatile private var udp:DatagramSocket?=null
        @Volatile private var connection:Socket?=null
        fun start(){executor.execute{
            try{
                val keys=keypair();val listener=ServerSocket().apply{reuseAddress=true;bind(InetSocketAddress(0));soTimeout=1500};server=listener
                if(!active.get()){listener.close();return@execute}
                val datagram=DatagramSocket(null).apply{reuseAddress=true;broadcast=true;bind(InetSocketAddress(PORT));soTimeout=500};udp=datagram
                if(!active.get()){datagram.close();listener.close();return@execute}
                val packet=JSONObject().put("magic",MAGIC).put("port",listener.localPort).put("pub",Base64.getEncoder().encodeToString(keys.public.encoded)).toString().toByteArray()
                val deadline=System.currentTimeMillis()+LIFETIME_MS
                executor.execute{var lastBroadcast=0L;val buffer=ByteArray(256);while(active.get()&&System.currentTimeMillis()<deadline){
                    val now=System.currentTimeMillis()
                    if(now-lastBroadcast>=1000){lastBroadcast=now;for(address in runCatching{broadcasts()}.getOrDefault(emptyList()))runCatching{datagram.send(DatagramPacket(packet,packet.size,address,PORT))}}
                    try{val request=DatagramPacket(buffer,buffer.size);datagram.receive(request);if(String(request.data,request.offset,request.length,Charsets.UTF_8)==REQUEST)datagram.send(DatagramPacket(packet,packet.size,request.address,request.port))}
                    catch(_:SocketTimeoutException){}catch(_:Exception){if(!active.get())break}
                }}
                var attempts=0
                while(active.get()&&System.currentTimeMillis()<deadline&&attempts<MAX_ATTEMPTS){
                    val socket=try{listener.accept()}catch(_:SocketTimeoutException){continue}catch(_:java.net.SocketException){break}
                    connection=socket;attempts++
                    try{
                        socket.soTimeout=5000
                        val input=java.io.DataInputStream(socket.getInputStream())
                        val size=input.readUnsignedShort();require(size in 64..256)
                        val public=ByteArray(size);input.readFully(public)
                        val channel=LanSyncChannel.open(input,socket.getOutputStream(),sessionKey(keys.private,public,code))
                        require(channel.receive().contentEquals("join".toByteArray()))
                        channel.send(secret);return@execute
                    }catch(_:Exception){}finally{runCatching{socket.close()};connection=null}
                }
            }catch(e:Exception){if(active.get())android.util.Log.w("DiaryLan","Pairing listener failed: ${e.javaClass.simpleName}")}
            finally{close();finished()}
        }}
        override fun close(){if(!active.getAndSet(false))return;runCatching{connection?.close()};runCatching{server?.close()};runCatching{udp?.close()};executor.shutdownNow()}
    }
    fun invite(secret:ByteArray,finished:()->Unit):Invitation{
        require(secret.size==LanSyncCrypto.KEY_BYTES)
        val code="%06d".format(java.util.Locale.ROOT,SecureRandom().nextInt(1_000_000))
        return Invitation(secret.copyOf(),code,finished).also{it.start()}
    }
    /** Finds an invite on the local network; never sends the code itself over the network. */
    fun join(code:String,target:String?=null,timeoutMs:Long=20_000):ByteArray{
        require(code.matches(Regex("[0-9]{6}")))
        val targetAddress=target?.let{java.net.InetAddress.getByName(it).also{address->require(address is Inet4Address&&address.isSiteLocalAddress)}}
        val keys=keypair();val deadline=System.currentTimeMillis()+timeoutMs
        DatagramSocket(null).use{udp->
            udp.reuseAddress=true;udp.bind(InetSocketAddress(if(targetAddress==null)PORT else 0));udp.soTimeout=1500
            val bytes=ByteArray(1024)
            val tried=mutableSetOf<String>()
            while(System.currentTimeMillis()<deadline){
                if(targetAddress!=null){val request=REQUEST.toByteArray();runCatching{udp.send(DatagramPacket(request,request.size,targetAddress,PORT))}}
                val packet=DatagramPacket(bytes,bytes.size)
                try{udp.receive(packet)}catch(_:SocketTimeoutException){continue}
                val offer=runCatching{JSONObject(String(packet.data,packet.offset,packet.length,Charsets.UTF_8))}.getOrNull()?:continue
                if(offer.optString("magic")!=MAGIC)continue
                val port=offer.optInt("port");if(port !in 1..65535)continue
                val public=runCatching{Base64.getDecoder().decode(offer.getString("pub"))}.getOrNull()?:continue
                val identity=packet.address.hostAddress+":"+port+":"+offer.optString("pub")
                if(!tried.add(identity))continue
                val secret=runCatching{
                    Socket().use{socket->
                        socket.connect(InetSocketAddress(packet.address,port),3000);socket.soTimeout=5000
                        val out=java.io.DataOutputStream(socket.getOutputStream());val encoded=keys.public.encoded
                        out.writeShort(encoded.size);out.write(encoded);out.flush()
                        val channel=LanSyncChannel.open(socket.getInputStream(),out,sessionKey(keys.private,public,code))
                        channel.send("join".toByteArray());channel.receive().also{require(it.size==LanSyncCrypto.KEY_BYTES)}
                    }
                }.getOrNull()
                if(secret!=null)return secret
            }
        }
        throw IllegalStateException("未找到配对设备，请检查配对码并保持两台设备在同一网络")
    }
}
