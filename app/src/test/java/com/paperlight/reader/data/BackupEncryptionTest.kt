package com.paperlight.reader.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

class BackupEncryptionTest {
    @Test
    fun encryptedBackupRoundTripsAndAuthenticates() = runBlocking {
        val original = "backup contents including a credential".toByteArray()
        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(original), encrypted, "very secure test password".toCharArray())

        val restored = ByteArrayOutputStream()
        assertTrue(BackupEncryption.decryptOrPlainZip(
            ByteArrayInputStream(encrypted.toByteArray()), restored, "very secure test password".toCharArray()
        ))
        assertArrayEquals(original, restored.toByteArray())
    }

    @Test
    fun wrongPasswordFailsAtAuthenticatedHeaderBeforeWritingPayload() = runBlocking {
        val original = ByteArray(BackupEncryption.ChunkBytes * 2) { (it % 239).toByte() }
        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(original), encrypted, "very secure test password".toCharArray())
        val restored = ByteArrayOutputStream()
        var progressBytes = 0L

        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                BackupEncryption.decryptOrPlainZip(
                    ByteArrayInputStream(encrypted.toByteArray()), restored, "different wrong password".toCharArray()
                ) { completed, _ -> progressBytes = completed }
            }
        }

        assertTrue(failure.message.orEmpty().contains("Password is incorrect"))
        assertEquals(0, restored.size())
        assertEquals(0L, progressBytes)
    }

    @Test
    fun largeThirtyFiveMegabyteBackupStreamsWithBoundedBuffers() = runBlocking {
        val directory = Files.createTempDirectory("backup-encryption-large-").toFile()
        val source = File(directory, "source.bin")
        val encrypted = File(directory, "encrypted.vrb")
        val restored = File(directory, "restored.bin")
        try {
            FileOutputStream(source).buffered().use { output ->
                val block = ByteArray(64 * 1024) { (it % 251).toByte() }
                repeat(35 * 16) { output.write(block) }
                output.write(block, 0, 35 * 1024)
            }
            var encryptedProgress = 0L
            FileInputStream(source).buffered().use { input ->
                FileOutputStream(encrypted).buffered().use { output ->
                    BackupEncryption.encrypt(input, output, "large backup test password".toCharArray(), source.length()) { bytes, _ ->
                        encryptedProgress = bytes
                    }
                }
            }
            assertEquals(source.length(), encryptedProgress)

            var decryptedProgress = 0L
            FileInputStream(encrypted).buffered().use { input ->
                FileOutputStream(restored).buffered().use { output ->
                    assertTrue(BackupEncryption.decryptOrPlainZip(
                        input, output, "large backup test password".toCharArray(), encrypted.length()
                    ) { bytes, _ -> decryptedProgress = bytes })
                }
            }
            assertEquals(source.length(), restored.length())
            assertEquals(encrypted.length(), decryptedProgress)
            assertArrayEquals(sha256(source), sha256(restored))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun tamperingTruncationTrailingDataAndChunkReorderingAreRejected() = runBlocking {
        val original = ByteArray(BackupEncryption.ChunkBytes * 2 + 17) { (it % 197).toByte() }
        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(original), encrypted, "very secure test password".toCharArray())
        val valid = encrypted.toByteArray()

        val tamperedChunk = valid.copyOf().apply { this[HEADER_BYTES + HEADER_CHECK_RECORD_BYTES + 8] = (this[HEADER_BYTES + HEADER_CHECK_RECORD_BYTES + 8].toInt() xor 0x40).toByte() }
        val tamperedHeader = valid.copyOf().apply { this[HEADER_BYTES] = (this[HEADER_BYTES].toInt() xor 0x40).toByte() }
        assertThrows(IllegalArgumentException::class.java) { decrypt(tamperedChunk) }
        assertThrows(IllegalArgumentException::class.java) { decrypt(tamperedHeader) }
        assertThrows(IllegalArgumentException::class.java) { decrypt(valid.copyOf(valid.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { decrypt(valid + byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { decrypt(swapFirstTwoChunks(valid)) }
        assertThrows(IllegalArgumentException::class.java) { decrypt(duplicateFirstChunk(valid)) }
        Unit
    }

    @Test
    fun cancellationDuringEncryptionDoesNotWriteAnAuthenticatedEndMarker() = runBlocking {
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                BackupEncryption.encrypt(
                    ByteArrayInputStream(ByteArray(BackupEncryption.ChunkBytes * 3) { 7 }), output,
                    "large backup test password".toCharArray(),
                ) { completed, _ ->
                    if (completed >= BackupEncryption.ChunkBytes) throw CancellationException("test cancellation")
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { decrypt(output.toByteArray()) }
        Unit
    }

    @Test
    fun cancellationDuringDecryptionStopsAtChunkBoundary() = runBlocking {
        val plain = ByteArray(BackupEncryption.ChunkBytes * 3) { (it % 113).toByte() }
        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(plain), encrypted, "large backup test password".toCharArray())
        val restored = ByteArrayOutputStream()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                BackupEncryption.decryptOrPlainZip(
                    ByteArrayInputStream(encrypted.toByteArray()), restored, "large backup test password".toCharArray()
                ) { completed, _ ->
                    if (completed >= BackupEncryption.ChunkBytes) throw CancellationException("test cancellation")
                }
            }
        }
        assertEquals(BackupEncryption.ChunkBytes, restored.size())
    }

    @Test
    fun unsupportedKdfParametersAndOldEncryptedFormatAreRejected() = runBlocking {
        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(byteArrayOf(1, 2, 3)), encrypted, "very secure test password".toCharArray())
        val unsupported = encrypted.toByteArray().apply {
            this[8] = 0
            this[9] = 0
            this[10] = 0
            this[11] = 1
        }
        assertTrue(assertThrows(IllegalArgumentException::class.java) { decrypt(unsupported) }
            .message.orEmpty().contains("key-derivation"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            decrypt("VBRBKUP2".toByteArray() + ByteArray(40))
        }.message.orEmpty().contains("unsupported older format"))
    }

    @Test
    fun plainZipBytesAreCopiedWithoutPassword() = runBlocking {
        val plainZip = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
        val restored = ByteArrayOutputStream()
        var copiedBytes = 0L
        assertFalse(BackupEncryption.decryptOrPlainZip(ByteArrayInputStream(plainZip), restored, null) { completed, _ ->
            copiedBytes = completed
        })
        assertArrayEquals(plainZip, restored.toByteArray())
        assertEquals(plainZip.size.toLong(), copiedBytes)
    }

    private fun decrypt(bytes: ByteArray): ByteArray = runBlocking {
        val output = ByteArrayOutputStream()
        BackupEncryption.decryptOrPlainZip(
            ByteArrayInputStream(bytes), output, "very secure test password".toCharArray()
        )
        output.toByteArray()
    }

    private fun swapFirstTwoChunks(bytes: ByteArray): ByteArray {
        var offset = HEADER_BYTES + HEADER_CHECK_RECORD_BYTES
        val firstLength = readInt(bytes, offset)
        val first = bytes.copyOfRange(offset, offset + 4 + firstLength + TAG_BYTES)
        offset += first.size
        val secondLength = readInt(bytes, offset)
        val second = bytes.copyOfRange(offset, offset + 4 + secondLength + TAG_BYTES)
        return bytes.copyOfRange(0, HEADER_BYTES + HEADER_CHECK_RECORD_BYTES) + second + first +
            bytes.copyOfRange(offset + second.size, bytes.size)
    }

    private fun duplicateFirstChunk(bytes: ByteArray): ByteArray {
        val firstOffset = HEADER_BYTES + HEADER_CHECK_RECORD_BYTES
        val firstLength = readInt(bytes, firstOffset)
        val first = bytes.copyOfRange(firstOffset, firstOffset + 4 + firstLength + TAG_BYTES)
        val secondOffset = firstOffset + first.size
        val secondLength = readInt(bytes, secondOffset)
        val second = bytes.copyOfRange(secondOffset, secondOffset + 4 + secondLength + TAG_BYTES)
        require(first.size == second.size)
        return bytes.copyOfRange(0, secondOffset) + first + bytes.copyOfRange(secondOffset + second.size, bytes.size)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private companion object {
        const val HEADER_BYTES = 40
        const val HEADER_CHECK_RECORD_BYTES = 46 + 16
        const val TAG_BYTES = 16
    }
}
