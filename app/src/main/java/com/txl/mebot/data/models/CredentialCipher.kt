package com.txl.mebot.data.models

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedCredential(val ciphertext: ByteArray, val iv: ByteArray)

interface CredentialCipher {
    fun encrypt(value: String): EncryptedCredential
    fun decrypt(value: EncryptedCredential): String
}

/** AES-GCM 的随机 IV 与认证标签防止重复密文及静默篡改。 */
class AesGcmCredentialCipher(
    purpose: String = "mebot:bailian-api-key:v1",
    private val key: (create: Boolean) -> SecretKey,
) : CredentialCipher {
    private val aad = purpose.toByteArray(Charsets.UTF_8)

    override fun encrypt(value: String): EncryptedCredential {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            cipher.updateAAD(aad)
            EncryptedCredential(cipher.doFinal(bytes), cipher.iv)
        } finally { bytes.fill(0) }
    }

    override fun decrypt(value: EncryptedCredential): String {
        require(value.iv.size == 12 && value.ciphertext.size >= 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, value.iv))
        cipher.updateAAD(aad)
        val bytes = cipher.doFinal(value.ciphertext)
        return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }
}

class AndroidKeystoreCredentialCipher(
    private val keyAlias: String = "mebot.api-credentials.v1",
    purpose: String = "mebot:bailian-api-key:v1",
) : CredentialCipher {
    private val delegate = AesGcmCredentialCipher(purpose, ::getKey)
    override fun encrypt(value: String) = delegate.encrypt(value)
    override fun decrypt(value: EncryptedCredential) = delegate.decrypt(value)

    private fun getKey(create: Boolean): SecretKey = synchronized(KeyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return@synchronized it }
        if (!create) throw CredentialUnavailable()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private companion object {
        val KeyLock = Any()
    }
}
