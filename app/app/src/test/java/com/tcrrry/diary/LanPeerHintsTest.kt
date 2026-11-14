package com.tcrrry.diary

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class LanPeerHintsTest {
    @Test fun onlyPrivateBoundedIpv4HintsAreShared(){
        val id="a".repeat(32)
        val source=listOf(LanPeerHint(id,"平板",listOf("10.42.0.20","8.8.8.8","127.0.0.1","10.42.0.20","192.168.1.2")))
        val decoded=LanPeerHints.decode(LanPeerHints.encode(source))
        assertEquals(listOf("10.42.0.20","192.168.1.2"),decoded.single().addresses)
        assertEquals("平板",decoded.single().name)
        assertFalse(LanPeerHints.isPrivateAddress("10.999.0.1"))
        assertTrue(LanPeerHints.decode(JSONArray().put(JSONObject().put("id","bad"))).isEmpty())
        val oversized=JSONArray();repeat(33){oversized.put(JSONObject())}
        assertTrue(LanPeerHints.decode(oversized).isEmpty())
        assertTrue(LanNetworks.isLanInterface("wlan0"));assertTrue(LanNetworks.isLanInterface("eth0"));assertTrue(LanNetworks.isLanInterface("p2p-p2p0-0"))
        assertFalse(LanNetworks.isLanInterface("ccmni3"));assertFalse(LanNetworks.isLanInterface("tun0"))
    }
    @Test fun aVirtualDeviceLearnsOtherPeersWithoutTreatingThemAsAlreadySynced(){
        val pc="a".repeat(32);val phone="b".repeat(32);val tablet="c".repeat(32)
        val known=listOf(LanKnownDevice(phone,"手机",123,listOf("10.42.0.10")))
        val learned=LanPeerHints.merge(known,listOf(LanPeerHint(tablet,"平板",listOf("10.42.0.20"))),pc,phone)
        assertEquals(0L,learned.single{it.id==tablet}.lastSync)
        assertTrue("10.42.0.20" in LanPeerHints.targets("10.42.0.10",learned,listOf("172.20.0.10")))
        assertTrue(LanPeerHints.targets(null,learned,listOf("172.20.0.10")).isEmpty())
        val moved=LanPeerHints.merge(learned,listOf(LanPeerHint(tablet,"平板",listOf("10.0.0.141"))),pc,phone)
        assertEquals("10.0.0.141",moved.single{it.id==tablet}.addresses.first())
    }
}
