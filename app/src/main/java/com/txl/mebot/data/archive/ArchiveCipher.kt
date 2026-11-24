package com.txl.mebot.data.archive

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ArchiveKeyUnavailable : IllegalStateException("档案密钥不可用，原数据已保留")
class ArchiveIntegrityException : IllegalStateException("档案内容认证失败，原数据已保留")

/** 初始化允许创建的时机由主库是否已有密文决定；加解密本身永不补建丢失密钥。 */
class ArchiveCipher(private val key: (create: Boolean) -> SecretKey) {
    fun ensureAvailable(allowCreate: Boolean) { key(allowCreate) }

    fun encrypt(value: ByteArray, purpose: String): ByteArray {
        val cipher = encryptionCipher(purpose)
        return Magic + cipher.iv + cipher.doFinal(value)
    }

    fun decrypt(value: ByteArray, purpose: String): ByteArray = try {
        require(value.size >= HeaderSize + 16 && value.copyOfRange(0, Magic.size).contentEquals(Magic))
        decryptionCipher(value.copyOfRange(Magic.size, HeaderSize), purpose).doFinal(value, HeaderSize, value.size - HeaderSize)
    } catch (error: ArchiveKeyUnavailable) { throw error
    } catch (_: Exception) { throw ArchiveIntegrityException() }

    fun encryptStream(input: InputStream, output: OutputStream, purpose: String, maxBytes: Long, consume: (ByteArray, Int) -> Unit): Long {
        val cipher = encryptionCipher(purpose)
        output.write(Magic); output.write(cipher.iv)
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total = Math.addExact(total, read.toLong())
                require(total <= maxBytes) { "附件超过允许大小" }
                consume(buffer, read)
                cipher.update(buffer, 0, read)?.let(output::write)
            }
            output.write(cipher.doFinal())
            return total
        } finally { buffer.fill(0) }
    }

    /** 输出只能是调用方未发布的私有暂存文件；认证完成前不得让 UI/其他 App 读取。 */
    fun decryptStream(input: InputStream, output: OutputStream, purpose: String) {
        val header = ByteArray(HeaderSize)
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read <= 0) throw ArchiveIntegrityException()
            offset += read
        }
        if (!header.copyOfRange(0, Magic.size).contentEquals(Magic)) throw ArchiveIntegrityException()
        val cipher = decryptionCipher(header.copyOfRange(Magic.size, HeaderSize), purpose)
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) cipher.update(buffer, 0, read)?.let { bytes -> try { output.write(bytes) } finally { bytes.fill(0) } }
            }
            val last = try { cipher.doFinal() } catch (_: Exception) { throw ArchiveIntegrityException() }
            try { output.write(last) } finally { last.fill(0) }
        } finally { buffer.fill(0) }
    }

    private fun encryptionCipher(purpose: String) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, key(false)); updateAAD(aad(purpose))
    }
    private fun decryptionCipher(iv: ByteArray, purpose: String) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv)); updateAAD(aad(purpose))
    }
    private fun aad(purpose: String) = "mebot:archive:v1:$purpose".toByteArray(Charsets.UTF_8)

    companion object {
        private val Magic = byteArrayOf(77, 69, 66, 65, 1)
        private const val HeaderSize = 17
        fun androidKeystore(): ArchiveCipher = ArchiveCipher(::androidKey)
        private val KeyLock = Any()
        private fun androidKey(create: Boolean): SecretKey = synchronized(KeyLock) {
            try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("mebot.archive.v1", null) as? SecretKey)?.let { return@synchronized it }
            if (!create) throw ArchiveKeyUnavailable()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("mebot.archive.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
            } catch (error: ArchiveKeyUnavailable) { throw error
            } catch (_: Exception) { throw ArchiveKeyUnavailable() }
        }
    }
}
