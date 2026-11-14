package com.tcrrry.diary

import org.junit.Assert.*
import org.junit.Test

class LanSyncCryptoTest {
    @Test fun roundTripAndRandomNonce() {
        val key = LanSyncCrypto.newKey()
        val input = "设备 · 42 分钟".toByteArray(Charsets.UTF_8)
        val first = LanSyncCrypto.seal(key, input)
        val second = LanSyncCrypto.seal(key, input)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(input, LanSyncCrypto.open(key, first))
    }

    @Test fun wrongKeyAndTamperingAreRejected() {
        val key = LanSyncCrypto.newKey()
        val message = LanSyncCrypto.seal(key, "private".toByteArray())
        assertTrue(runCatching { LanSyncCrypto.open(LanSyncCrypto.newKey(), message) }.isFailure)
        message[message.lastIndex] = (message.last().toInt() xor 1).toByte()
        assertTrue(runCatching { LanSyncCrypto.open(key, message) }.isFailure)
    }

    @Test fun rejectsOversizeAndTruncatedMessages() {
        val key = LanSyncCrypto.newKey()
        assertTrue(runCatching { LanSyncCrypto.seal(key, ByteArray(LanSyncCrypto.MAX_PLAINTEXT_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { LanSyncCrypto.open(key, ByteArray(12)) }.isFailure)
    }
}
