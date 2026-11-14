package com.tcrrry.diary

import org.junit.Assert.*
import org.junit.Test

class LanCollectorLeaseTest {
    @Test fun collectorKeepsAnExistingSessionAliveOnlyForALimitedRenewableInterval(){
        val lease=LanCollectorLease();assertFalse(lease.active(1000))
        lease.touch(1000);assertTrue(lease.active(45999));assertFalse(lease.active(46000))
        lease.touch(40000);assertTrue(lease.active(70000));assertFalse(lease.active(85000))
        lease.clear();assertFalse(lease.active(1))
    }
}
