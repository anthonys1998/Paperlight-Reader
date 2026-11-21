package com.paperlight.reader.data

import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/** Chunked authenticated encryption for backups that explicitly contain credentials. */
internal object BackupEncryption {
    private val magicV3 = "VBRBKUP3".toByteArray(Charsets.US_ASCII)
    private val magicV2 = "VBRBKUP2".toByteArray(Charsets.US_ASCII)
    private val headerCheck = "Vibe Reader encrypted backup password check v3".toByteArray(Charsets.US_ASCII)

    internal const val ChunkBytes = 256 * 1024
    private const val SaltBytes = 16
    private const val NoncePrefixBytes = 8
    private const val TagBytes = 16
    private const val Iterations = 180_000
    private const val HeaderBytes = 8 + 4 + SaltBytes + NoncePrefixBytes + 4

    suspend fun encryptingOutputStream(
        output: OutputStream,
        password: CharArray,
        totalBytes: Long? = null,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): OutputStream {
        try {
            require(password.size >= 12) { "Use a backup password with at least 12 characters." }
            val salt = ByteArray(SaltBytes).also(SecureRandom()::nextBytes)
            val noncePrefix = ByteArray(NoncePrefixBytes).also(SecureRandom()::nextBytes)
            val header = createHeader(salt, noncePrefix)
            val key = derive(password, salt)
            val data = DataOutputStream(output)
            data.write(header)
            data.write(aead(Cipher.ENCRYPT_MODE, key, nonce(noncePrefix, 0), headerAad(header)).doFinal(headerCheck))
            data.flush()
            onProgress(0L, totalBytes)
            return ChunkedEncryptingOutputStream(data, key, noncePrefix, header, coroutineContext, totalBytes, onProgress)
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun encrypt(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        totalBytes: Long? = null,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ) {
        val encrypted = encryptingOutputStream(output, password, totalBytes, onProgress)
        try {
            val buffer = ByteArray(ChunkBytes)
            while (true) {
                coroutineContext.ensureActive()
                val count = readChunk(input, buffer)
                if (count < 0) break
                encrypted.write(buffer, 0, count)
            }
            encrypted.close()
        } catch (failure: Throwable) {
            (encrypted as? ChunkedEncryptingOutputStream)?.abort()
            throw failure
        }
    }

    /** Decrypts the current format, or copies an unencrypted ZIP. Old encrypted formats are rejected. */
    suspend fun decryptOrPlainZip(
        input: InputStream,
        output: OutputStream,
        password: CharArray?,
        totalBytes: Long? = null,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): Boolean {
        val data = DataInputStream(input)
        val prefix = ByteArray(magicV3.size)
        var prefixCount = 0
        while (prefixCount < prefix.size) {
            coroutineContext.ensureActive()
            val count = data.read(prefix, prefixCount, prefix.size - prefixCount)
            if (count < 0) break
            if (count == 0) {
                val one = data.read()
                if (one < 0) break
                prefix[prefixCount++] = one.toByte()
                continue
            }
            prefixCount += count
        }

        if (prefixCount == magicV2.size && prefix.contentEquals(magicV2)) {
            password?.fill('\u0000')
            throw IllegalArgumentException("This encrypted backup uses an unsupported older format. Create a new backup with the current app version.")
        }
        if (prefixCount != magicV3.size || !prefix.contentEquals(magicV3)) {
            if (prefixCount > 0 && magicV3.copyOfRange(0, prefixCount).contentEquals(prefix.copyOfRange(0, prefixCount))) {
                password?.fill('\u0000')
                throw IllegalArgumentException("The encrypted backup is truncated or incomplete.")
            }
            password?.fill('\u0000')
            output.write(prefix, 0, prefixCount)
            copyWithProgress(data, output, totalBytes, onProgress)
            return false
        }

        val secret = requireNotNull(password) { "This backup is password-protected. Enter its password to restore it." }
        try {
            require(secret.size >= 12) { "The backup password must contain at least 12 characters." }
            val headerTail = ByteArray(HeaderBytes - magicV3.size)
            readFullyOrCorrupt(data, headerTail)
            val header = magicV3 + headerTail
            val headerInput = DataInputStream(headerTail.inputStream())
            val iterations = headerInput.readInt()
            val salt = ByteArray(SaltBytes).also(headerInput::readFully)
            val noncePrefix = ByteArray(NoncePrefixBytes).also(headerInput::readFully)
            val chunkSize = headerInput.readInt()
            require(iterations == Iterations) { "Unsupported encrypted backup key-derivation settings." }
            require(chunkSize == ChunkBytes) { "Unsupported encrypted backup chunk size." }
            val key = derive(secret, salt)
            val encryptedCheck = ByteArray(headerCheck.size + TagBytes)
            readFullyOrCorrupt(data, encryptedCheck)
            val verified = try {
                aead(Cipher.DECRYPT_MODE, key, nonce(noncePrefix, 0), headerAad(header)).doFinal(encryptedCheck)
            } catch (failure: BadPaddingException) {
                throw IllegalArgumentException("Password is incorrect, or the encrypted backup is damaged or unsupported.", failure)
            }
            require(MessageDigest.isEqual(verified, headerCheck)) {
                "Password is incorrect, or the encrypted backup is damaged or unsupported."
            }

            var chunkIndex = 0L
            var totalWritten = 0L
            onProgress(0L, totalBytes)
            while (true) {
                coroutineContext.ensureActive()
                val length = try {
                    data.readInt()
                } catch (failure: EOFException) {
                    throw IllegalArgumentException("The encrypted backup is truncated; its authenticated end marker is missing.", failure)
                }
                if (length == 0) {
                    require(chunkIndex < Int.MAX_VALUE.toLong() - 1L) { "The backup contains too many encrypted chunks." }
                    val endIndex = chunkIndex + 1L
                    val tag = ByteArray(TagBytes)
                    readFullyOrCorrupt(data, tag)
                    try {
                        aead(Cipher.DECRYPT_MODE, key, nonce(noncePrefix, endIndex.toInt()),
                            recordAad(header, type = 2, index = endIndex, length = 0)).doFinal(tag)
                    } catch (failure: BadPaddingException) {
                        throw IllegalArgumentException("The encrypted backup is damaged or has been modified.", failure)
                    }
                    require(data.read() == -1) { "The encrypted backup contains unexpected trailing data." }
                    break
                }

                require(length in 1..ChunkBytes) { "The encrypted backup contains an invalid chunk length." }
                require(chunkIndex < Int.MAX_VALUE.toLong() - 1L) { "The backup contains too many encrypted chunks." }
                val index = chunkIndex + 1L
                val encrypted = ByteArray(length + TagBytes)
                readFullyOrCorrupt(data, encrypted)
                val plain = try {
                    aead(Cipher.DECRYPT_MODE, key, nonce(noncePrefix, index.toInt()),
                        recordAad(header, type = 1, index = index, length = length)).doFinal(encrypted)
                } catch (failure: BadPaddingException) {
                    throw IllegalArgumentException("The encrypted backup is damaged or has been modified.", failure)
                }
                require(plain.size == length) { "The encrypted backup contains an invalid chunk." }
                try {
                    output.write(plain)
                    totalWritten += plain.size
                } finally {
                    Arrays.fill(plain, 0.toByte())
                }
                chunkIndex = index
                onProgress(totalWritten, totalBytes)
            }
            output.flush()
            return true
        } finally {
            secret.fill('\u0000')
        }
    }

    private fun createHeader(salt: ByteArray, noncePrefix: ByteArray): ByteArray =
        ByteArrayOutputStream(HeaderBytes).use { bytes ->
            DataOutputStream(bytes).use { data ->
                data.write(magicV3)
                data.writeInt(Iterations)
                data.write(salt)
                data.write(noncePrefix)
                data.writeInt(ChunkBytes)
            }
            bytes.toByteArray()
        }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, Iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun aead(mode: Int, key: SecretKeySpec, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }

    private fun nonce(prefix: ByteArray, counter: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).array()

    private fun headerAad(header: ByteArray): ByteArray = byteArrayOf(0) + header

    private fun recordAad(header: ByteArray, type: Int, index: Long, length: Int): ByteArray =
        ByteBuffer.allocate(header.size + 1 + 8 + 4)
            .put(header).put(type.toByte()).putLong(index).putInt(length).array()

    private suspend fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var count = input.read(buffer)
        if (count != 0) return count
        coroutineContext.ensureActive()
        val one = input.read()
        if (one < 0) return -1
        buffer[0] = one.toByte()
        count = 1
        return count
    }

    private suspend fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long?,
        onProgress: (Long, Long?) -> Unit,
    ) {
        val buffer = ByteArray(ChunkBytes)
        var copied = 0L
        onProgress(0L, totalBytes)
        while (true) {
            coroutineContext.ensureActive()
            val count = readChunk(input, buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
            onProgress(copied, totalBytes)
        }
        output.flush()
    }

    private fun readFullyOrCorrupt(input: DataInputStream, bytes: ByteArray) {
        try {
            input.readFully(bytes)
        } catch (failure: EOFException) {
            throw IllegalArgumentException("The encrypted backup is truncated or incomplete.", failure)
        }
    }

    private class ChunkedEncryptingOutputStream(
        private val output: DataOutputStream,
        private val key: SecretKeySpec,
        private val noncePrefix: ByteArray,
        private val header: ByteArray,
        private val context: kotlin.coroutines.CoroutineContext,
        private val totalBytes: Long?,
        private val onProgress: (Long, Long?) -> Unit,
    ) : OutputStream() {
        private val buffer = ByteArray(ChunkBytes)
        private var buffered = 0
        private var chunkIndex = 0L
        private var written = 0L
        private var finished = false

        override fun write(value: Int) {
            ensureWritable()
            buffer[buffered++] = value.toByte()
            if (buffered == buffer.size) flushChunk()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            ensureWritable()
            var position = offset
            var remaining = length
            while (remaining > 0) {
                ensureWritable()
                val count = minOf(remaining, buffer.size - buffered)
                bytes.copyInto(buffer, buffered, position, position + count)
                buffered += count
                position += count
                remaining -= count
                if (buffered == buffer.size) flushChunk()
            }
        }

        override fun flush() {
            ensureWritable()
            context.ensureActive()
            flushChunk()
            output.flush()
        }

        override fun close() {
            finish()
        }

        private fun ensureWritable() {
            check(!finished) { "Encrypted backup stream is already finalized." }
            context.ensureActive()
        }

        private fun flushChunk() {
            if (buffered == 0) return
            ensureWritable()
            check(chunkIndex < Int.MAX_VALUE.toLong() - 1L) { "The backup contains too many encrypted chunks." }
            val index = ++chunkIndex
            val length = buffered
            val encrypted = aead(Cipher.ENCRYPT_MODE, key, nonce(noncePrefix, index.toInt()),
                recordAad(header, type = 1, index = index, length = length)).doFinal(buffer, 0, length)
            output.writeInt(length)
            output.write(encrypted)
            written += length
            Arrays.fill(buffer, 0, length, 0.toByte())
            buffered = 0
            onProgress(written, totalBytes)
        }

        private fun finish() {
            if (finished) return
            try {
                ensureWritable()
                flushChunk()
                val endIndex = chunkIndex + 1L
                output.writeInt(0)
                output.write(aead(Cipher.ENCRYPT_MODE, key, nonce(noncePrefix, endIndex.toInt()),
                    recordAad(header, type = 2, index = endIndex, length = 0)).doFinal())
                output.flush()
                finished = true
                onProgress(written, totalBytes)
            } finally {
                if (!finished) abort()
            }
        }

        fun abort() {
            Arrays.fill(buffer, 0.toByte())
            buffered = 0
            finished = true
        }
    }
}
