package com.paperlight.reader.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupEncryptionTest {
    @Test
    fun encryptedBackupRoundTripsAndAuthenticates() {
        val original = "backup contents including a credential".toByteArray()
        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(original), encrypted, "very secure test password".toCharArray())

        val restored = ByteArrayOutputStream()
        assertTrue(BackupEncryption.decryptOrLegacy(
            ByteArrayInputStream(encrypted.toByteArray()), restored, "very secure test password".toCharArray()
        ))
        assertArrayEquals(original, restored.toByteArray())

        assertThrows(Exception::class.java) {
            BackupEncryption.decryptOrLegacy(
                ByteArrayInputStream(encrypted.toByteArray()), ByteArrayOutputStream(), "incorrect test password".toCharArray()
            )
        }
    }

    @Test
    fun legacyZipBytesAreCopiedWithoutPassword() {
        val oldBackup = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
        val restored = ByteArrayOutputStream()
        assertFalse(BackupEncryption.decryptOrLegacy(ByteArrayInputStream(oldBackup), restored, null))
        assertArrayEquals(oldBackup, restored.toByteArray())
    }
}
