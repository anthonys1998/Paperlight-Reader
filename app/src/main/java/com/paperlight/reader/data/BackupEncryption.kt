package com.paperlight.reader.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Streaming authenticated encryption for backups that explicitly contain credentials. */
internal object BackupEncryption {
    private val magic = "VBRBKUP2".toByteArray(Charsets.US_ASCII)
    private const val SaltBytes = 16
    private const val IvBytes = 12
    private const val Iterations = 180_000

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        try {
            require(password.size >= 12) { "Use a backup password with at least 12 characters." }
            val salt = ByteArray(SaltBytes).also(SecureRandom()::nextBytes)
            val iv = ByteArray(IvBytes).also(SecureRandom()::nextBytes)
            val key = derive(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            val header = DataOutputStream(output)
            header.write(magic)
            header.write(salt)
            header.write(iv)
            header.flush()
            CipherOutputStream(output, cipher).use { encrypted -> input.copyTo(encrypted, 64 * 1024) }
        } finally {
            password.fill('\u0000')
        }
    }

    /** Writes decrypted bytes for this format, or copies a legacy ZIP; returns true if encrypted. */
    fun decryptOrLegacy(input: InputStream, output: OutputStream, password: CharArray?): Boolean {
        val header = DataInputStream(input)
        val prefix = ByteArray(magic.size)
        var count = 0
        while (count < prefix.size) {
            val read = header.read(prefix, count, prefix.size - count)
            if (read < 0) break
            count += read
        }
        if (count != magic.size || !prefix.contentEquals(magic)) {
            output.write(prefix, 0, count)
            header.copyTo(output, 64 * 1024)
            password?.fill('\u0000')
            return false
        }
        val secret = requireNotNull(password) { "This backup is password-protected. Enter its password to restore it." }
        try {
            require(secret.size >= 12) { "The backup password must contain at least 12 characters." }
            val salt = ByteArray(SaltBytes)
            header.readFully(salt)
            val iv = ByteArray(IvBytes)
            header.readFully(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, derive(secret, salt), GCMParameterSpec(128, iv))
            }
            CipherInputStream(header, cipher).use { decrypted -> decrypted.copyTo(output, 64 * 1024) }
            return true
        } finally {
            secret.fill('\u0000')
        }
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, Iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
