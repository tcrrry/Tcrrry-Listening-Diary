package com.tcrrry.diary

import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

internal data class LanPeerHint(val id:String,val name:String,val addresses:List<String>)
internal object LanPeerHints {
    private val validId=Regex("[a-f0-9]{32}")
    private val ipv4=Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
    fun isPrivateAddress(value:String):Boolean {
        if(!ipv4.matches(value))return false
        val address=runCatching{InetAddress.getByName(value)}.getOrNull()
        return address is Inet4Address&&address.isSiteLocalAddress
    }
    fun encode(hints:List<LanPeerHint>):JSONArray=JSONArray().apply{
        hints.take(32).forEach{hint->
            if(validId.matches(hint.id))put(JSONObject().put("id",hint.id).put("name",hint.name.filter{!it.isISOControl()}.take(48)).put("addresses",JSONArray(hint.addresses.filter(::isPrivateAddress).distinct().take(4))))
        }
    }
    fun decode(array:JSONArray?):List<LanPeerHint>{
        if(array==null||array.length()>32)return emptyList()
        return (0 until array.length()).mapNotNull{i->
            val row=array.optJSONObject(i)?:return@mapNotNull null
            val id=row.optString("id");if(!validId.matches(id))return@mapNotNull null
            val name=row.optString("name").filter{!it.isISOControl()}.trim().take(48).ifBlank{"设备"}
            val a=row.optJSONArray("addresses")?:JSONArray()
            val addresses=(0 until minOf(a.length(),8)).map{a.optString(it)}.filter(::isPrivateAddress).distinct().take(4)
            if(addresses.isEmpty())null else LanPeerHint(id,name,addresses)
        }.distinctBy{it.id}
    }
    fun merge(known:List<LanKnownDevice>,hints:List<LanPeerHint>,own:String,direct:String):List<LanKnownDevice>{
        val result=known.associateBy{it.id}.toMutableMap()
        for(hint in hints){
            if(hint.id==own)continue
            val old=result[hint.id]
            val addresses=if(hint.id==direct)hint.addresses else (hint.addresses+old?.addresses.orEmpty()).distinct().take(4)
            result[hint.id]=LanKnownDevice(hint.id,if(hint.id==direct||old==null)hint.name else old.name,old?.lastSync?:0,addresses)
        }
        return result.values.sortedByDescending{it.lastSync}.take(32)
    }
    fun targets(manual:String?,known:List<LanKnownDevice>,own:List<String>):List<String>{
        if(manual==null)return emptyList()
        val local=own.toSet()
        return (listOf(manual)+known.flatMap{it.addresses}).filter{it !in local&&isPrivateAddress(it)}.distinct().take(64)
    }
}

internal object LanNetworks {
    /** Avoid advertising cellular and VPN interface addresses as local peers. */
    fun isLanInterface(name:String):Boolean {
        val n=name.lowercase(java.util.Locale.ROOT)
        return listOf("wlan","wifi","swlan","eth","en","p2p","ap","rndis","usb").any{n.startsWith(it)}
    }
    fun interfaces():List<NetworkInterface> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter{it.isUp&&!it.isLoopback&&isLanInterface(it.name)}
}
