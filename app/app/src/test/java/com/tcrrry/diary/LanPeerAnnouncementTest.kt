package com.tcrrry.diary

import org.junit.Assert.*
import org.junit.Test

class LanPeerAnnouncementTest {
    @Test fun discoveryAcceptsOnlyPairedDifferentDeviceAndRejectsTampering(){
        val key=LanSyncCrypto.newKey();val a=ByteArray(16){1};val b=ByteArray(16){2}
        val packet=LanPeerAnnouncement.encode(key,a,39771)
        val peer=LanPeerAnnouncement.decode(key,b,packet)!!
        assertArrayEquals(a,peer.id);assertEquals(39771,peer.port)
        assertNull(LanPeerAnnouncement.decode(key,a,packet))
        assertNull(LanPeerAnnouncement.decode(LanSyncCrypto.newKey(),b,packet))
        val altered=packet.copyOf();altered[7]=(altered[7].toInt() xor 1).toByte()
        assertNull(LanPeerAnnouncement.decode(key,b,altered))
        assertNull(LanPeerAnnouncement.decode(key,b,packet.copyOf(10)))
    }
}
