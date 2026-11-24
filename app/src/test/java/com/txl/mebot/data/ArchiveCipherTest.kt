package com.txl.mebot.data

import com.txl.mebot.data.archive.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class ArchiveCipherTest {
    private val cipher = ArchiveCipher { SecretKeySpec(ByteArray(32) { 4 }, "AES") }

    @Test fun authenticatedEnvelopeUsesRandomIvAndRejectsTamperingRecordSwapAndWrongKey() {
        val raw = "私有证件信息".toByteArray()
        val a = cipher.encrypt(raw, "record:a"); val b = cipher.encrypt(raw, "record:a")
        assertFalse(a.contentEquals(b)); assertArrayEquals(raw, cipher.decrypt(a, "record:a"))
        assertFalse(a.toString(Charsets.UTF_8).contains("私有证件"))
        assertTrue(runCatching { cipher.decrypt(a, "record:b") }.exceptionOrNull() is ArchiveIntegrityException)
        val changed = a.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertTrue(runCatching { cipher.decrypt(changed, "record:a") }.exceptionOrNull() is ArchiveIntegrityException)
        val wrongKey = ArchiveCipher { SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        assertTrue(runCatching { wrongKey.decrypt(a, "record:a") }.isFailure)
        assertTrue(runCatching { cipher.decrypt(a.copyOfRange(0, 10), "record:a") }.isFailure)
        assertTrue(runCatching { cipher.decrypt(a.clone().apply { this[0] = 0 }, "record:a") }.isFailure)
    }

    @Test fun streamingFilesAreAuthenticatedUseSeparatePurposeAndEnforceLimit() {
        val raw = ByteArray(150_000) { (it % 251).toByte() }
        val encrypted = ByteArrayOutputStream()
        var seen = 0
        assertEquals(raw.size.toLong(), cipher.encryptStream(ByteArrayInputStream(raw), encrypted, "asset-file:asset", 200_000) { _, length -> seen += length })
        assertEquals(raw.size, seen)
        val output = ByteArrayOutputStream()
        cipher.decryptStream(ByteArrayInputStream(encrypted.toByteArray()), output, "asset-file:asset")
        assertArrayEquals(raw, output.toByteArray())
        assertTrue(runCatching { cipher.decrypt(encrypted.toByteArray(), "asset:asset") }.isFailure)
        assertTrue(runCatching { cipher.encryptStream(ByteArrayInputStream(raw), ByteArrayOutputStream(), "asset-file:asset", 10) { _, _ -> } }.isFailure)
        val changed = encrypted.toByteArray().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertTrue(runCatching { cipher.decryptStream(ByteArrayInputStream(changed), ByteArrayOutputStream(), "asset-file:asset") }.isFailure)
    }

    @Test fun encryptionAndDecryptionNeverAskToRecreateMissingKey() {
        val requests = mutableListOf<Boolean>()
        val missing = ArchiveCipher { create -> requests += create; throw ArchiveKeyUnavailable() }
        assertTrue(runCatching { missing.encrypt(byteArrayOf(1), "record:a") }.exceptionOrNull() is ArchiveKeyUnavailable)
        assertTrue(runCatching { missing.decrypt(cipher.encrypt(byteArrayOf(1), "record:a"), "record:a") }.exceptionOrNull() is ArchiveKeyUnavailable)
        assertEquals(listOf(false, false), requests)
    }
}
