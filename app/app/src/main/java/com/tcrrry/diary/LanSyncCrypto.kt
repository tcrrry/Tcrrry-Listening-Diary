package com.tcrrry.diary

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated encryption for a single bounded LAN-sync message. */
internal object LanSyncCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val MAX_PLAINTEXT_BYTES = 1_048_576
    private val random = SecureRandom()
    private val context = "ListeningDiary/LAN/v1".toByteArray(Charsets.UTF_8)

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_BYTES && plaintext.size <= MAX_PLAINTEXT_BYTES)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(context)
        return nonce + cipher.doFinal(plaintext)
    }

    fun open(key: ByteArray, message: ByteArray): ByteArray {
        require(key.size == KEY_BYTES && message.size in (NONCE_BYTES + 16)..(MAX_PLAINTEXT_BYTES + NONCE_BYTES + 16))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, message, 0, NONCE_BYTES))
        cipher.updateAAD(context)
        return cipher.doFinal(message, NONCE_BYTES, message.size - NONCE_BYTES)
    }
}
