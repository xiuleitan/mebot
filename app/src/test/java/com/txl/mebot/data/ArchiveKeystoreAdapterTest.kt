package com.txl.mebot.data

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.txl.mebot.data.archive.ArchiveCipher
import com.txl.mebot.data.archive.ArchiveKeyUnavailable
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM 用 provider 替身检验生产 Keystore 适配器的参数与丢钥策略，不代替真机硬件验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArchiveKeystoreAdapterTest {
    @Before fun setUp() {
        check(Security.getProvider("AndroidKeyStore") == null)
        keys.clear(); specifications.clear()
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "Archive adapter test provider") {
            init {
                put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name)
                put("KeyGenerator.AES", TestKeyGenerator::class.java.name)
            }
        })
    }
    @After fun tearDown() { Security.removeProvider("AndroidKeyStore"); keys.clear(); specifications.clear() }

    @Test fun productionAdapterCreatesIndependent256BitRandomizedGcmKeyOnceAndReopensIt() {
        val first = ArchiveCipher.androidKeystore(); first.ensureAvailable(true)
        val specification = specifications.single()
        assertEquals("mebot.archive.v1", specification.keystoreAlias)
        assertEquals(256, specification.keySize)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), specification.blockModes)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), specification.encryptionPaddings)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, specification.purposes)
        assertTrue(specification.isRandomizedEncryptionRequired)
        val encrypted = first.encrypt("fixture-private".toByteArray(), "record:a")
        val reopened = ArchiveCipher.androidKeystore(); reopened.ensureAvailable(false)
        assertEquals("fixture-private", reopened.decrypt(encrypted, "record:a").toString(Charsets.UTF_8))
        assertEquals(1, specifications.size)
    }

    @Test fun productionAdapterDoesNotRegenerateRemovedKeyOnEncryptOrDecrypt() {
        val cipher = ArchiveCipher.androidKeystore(); cipher.ensureAvailable(true)
        val encrypted = cipher.encrypt(byteArrayOf(1), "record:a")
        keys.clear()
        assertTrue(runCatching { cipher.ensureAvailable(false) }.exceptionOrNull() is ArchiveKeyUnavailable)
        assertTrue(runCatching { cipher.encrypt(byteArrayOf(1), "record:a") }.exceptionOrNull() is ArchiveKeyUnavailable)
        assertTrue(runCatching { cipher.decrypt(encrypted, "record:a") }.exceptionOrNull() is ArchiveKeyUnavailable)
        assertEquals(1, specifications.size)
    }

    class TestKeyGenerator : KeyGeneratorSpi() {
        private lateinit var parameters: KeyGenParameterSpec
        override fun engineInit(random: SecureRandom?) = error("Use Android key parameters")
        override fun engineInit(keysize: Int, random: SecureRandom?) = error("Use Android key parameters")
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) { parameters = params as KeyGenParameterSpec }
        override fun engineGenerateKey(): SecretKey {
            specifications += parameters
            return KeyGenerator.getInstance("AES", "SunJCE").apply { init(parameters.keySize) }.generateKey().also { keys[parameters.keystoreAlias] = it }
        }
    }
    class TestKeyStore : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
        override fun engineAliases() = Collections.enumeration(keys.keys)
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = engineContainsAlias(alias)
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) = error("Use Android KeyGenerator")
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = error("Use Android KeyGenerator")
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = error("No certificates")
    }
    companion object {
        private val keys = mutableMapOf<String, SecretKey>()
        private val specifications = mutableListOf<KeyGenParameterSpec>()
    }
}
