package com.nikhil.sentinelx.desktop.core.format

import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Migration Seal (`.sxv`) payload encryption — the exact scheme in the Android app's
 * `util/SentinelSecurity.kt`.
 *
 *   v2 (current):  [ "SXV2" (4) | salt (16) | iv (12) | AES-256-GCM ct+tag ]  @ 600,000 iters
 *   v1 (legacy):   [            salt (16) | iv (12) | AES-256-GCM ct+tag ]  @  65,536 iters
 *
 * v1 archives predate the marker and must stay readable forever; they are never
 * written. The 4-byte marker means a legacy blob whose random salt happens to begin
 * with "SXV2" has probability 2^-32.
 *
 * This uses PBKDF2 rather than a modern memory-hard KDF because the Android format
 * fixes it. The desktop app's own local store uses Argon2id — see the store package.
 * Do not "improve" the KDF here; it would make archives the phone cannot open.
 */
object SxvCrypto {

    private val MAGIC_V2 = "SXV2".toByteArray(Charsets.US_ASCII)

    private const val ITERATIONS_V1 = 65_536
    private const val ITERATIONS_V2 = 600_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    /**
     * ⚠️ THE COMPATIBILITY TRAP.
     *
     * Android encodes with `Base64.DEFAULT`, which line-wraps at 76 characters using
     * a bare `\n`. `Base64.getDecoder()` throws `IllegalArgumentException` on those
     * newlines — a perfectly good vault would look corrupt.
     *
     * The MIME codec handles wrapping in both directions. The encoder is pinned to
     * 76/`\n` to match Android byte-for-byte; the default MIME encoder would emit
     * `\r\n`, which Android's decoder tolerates but which would make the archives
     * needlessly non-identical.
     */
    private val mimeDecoder: Base64.Decoder = Base64.getMimeDecoder()
    private val mimeEncoder: Base64.Encoder =
        Base64.getMimeEncoder(76, byteArrayOf('\n'.code.toByte()))

    class WrongPasswordException(cause: Throwable?) :
        Exception("Wrong password, or this archive is not a SentinelX Migration Seal.", cause)

    class MalformedArchiveException(message: String) : Exception(message)

    /** Encrypts to the current (v2) format. */
    fun encrypt(plaintext: String, password: CharArray): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

        val key = deriveKey(password, salt, ITERATIONS_V2)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return mimeEncoder.encodeToString(MAGIC_V2 + salt + iv + ciphertext)
        } finally {
            key.zero()
        }
    }

    /** Decrypts either format, detected by the marker. */
    fun decrypt(encoded: String, password: CharArray): String {
        val combined = try {
            mimeDecoder.decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw MalformedArchiveException("Payload is not valid base64: ${e.message}")
        }

        val isV2 = combined.size > MAGIC_V2.size &&
                combined.copyOfRange(0, MAGIC_V2.size).contentEquals(MAGIC_V2)

        val offset = if (isV2) MAGIC_V2.size else 0
        val iterations = if (isV2) ITERATIONS_V2 else ITERATIONS_V1

        // Header plus at least a GCM tag must be present.
        if (combined.size <= offset + SALT_BYTES + IV_BYTES) {
            throw MalformedArchiveException("Archive payload is truncated.")
        }

        val salt = combined.copyOfRange(offset, offset + SALT_BYTES)
        val iv = combined.copyOfRange(offset + SALT_BYTES, offset + SALT_BYTES + IV_BYTES)
        val ciphertext = combined.copyOfRange(offset + SALT_BYTES + IV_BYTES, combined.size)

        val key = deriveKey(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // GCM tag mismatch is indistinguishable from a wrong password, which is
            // the overwhelmingly likely cause. Surface that rather than a raw
            // AEADBadTagException the user cannot act on.
            throw WrongPasswordException(e)
        } finally {
            key.zero()
        }
    }

    /** True if this looks like a v2 archive — useful for diagnostics, not for control flow. */
    fun isV2(encoded: String): Boolean = runCatching {
        val head = mimeDecoder.decode(encoded).copyOfRange(0, MAGIC_V2.size)
        head.contentEquals(MAGIC_V2)
    }.getOrDefault(false)

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun SecretKey.zero() {
        runCatching { encoded }.getOrNull()?.let { Arrays.fill(it, 0) }
    }
}
