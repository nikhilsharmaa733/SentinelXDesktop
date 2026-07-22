package com.nikhil.sentinelx.desktop.core.store

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptography for the desktop app's **own** local vault.
 *
 * Deliberately NOT the same scheme as `.sxv`. That format is fixed by the Android
 * app and must stay PBKDF2/600k forever. This format is ours, so it uses Argon2id —
 * memory-hard, and therefore far more expensive to attack with GPUs than PBKDF2,
 * which is trivially parallelised.
 *
 * Bouncy Castle's Argon2 is pure Java. `argon2-jvm` would be faster but ships native
 * binaries per platform, which complicates Windows packaging for no real benefit at
 * these parameters.
 *
 * File envelope:
 * ```
 * [ "SXVL" (4) | fmt (1) | salt (16) | iv (12) | AES-256-GCM ciphertext+tag ]
 * ```
 * The salt lives in the file because it is not secret — it exists to make precomputed
 * tables useless, and it must survive a restart to re-derive the same key.
 */
object LocalCrypto {

    private val MAGIC = "SXVL".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION: Byte = 1

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128

    // ~64 MB, 3 passes, 4 lanes. Roughly a second on a desktop CPU: slow enough to
    // hurt an attacker badly, fast enough that unlocking does not feel broken.
    private const val ARGON_MEMORY_KB = 65_536
    private const val ARGON_ITERATIONS = 3
    private const val ARGON_PARALLELISM = 4

    class WrongPasswordException : Exception("Incorrect master password.")
    class CorruptVaultException(message: String) : Exception(message)

    /** An unwrapped key held for the session. Zero it on lock. */
    class VaultKey(internal val bytes: ByteArray) {
        fun destroy() = Arrays.fill(bytes, 0)
    }

    fun deriveKey(password: CharArray, salt: ByteArray): VaultKey {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ARGON_ITERATIONS)
            .withMemoryAsKB(ARGON_MEMORY_KB)
            .withParallelism(ARGON_PARALLELISM)
            .withSalt(salt)
            .build()

        val generator = Argon2BytesGenerator().apply { init(params) }
        val key = ByteArray(KEY_BYTES)
        generator.generateBytes(password, key)
        return VaultKey(key)
    }

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /** Encrypts with a full header. Used for the metadata file. */
    fun sealDocument(plaintext: ByteArray, key: VaultKey, salt: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val ciphertext = gcm(Cipher.ENCRYPT_MODE, key, iv, plaintext)
        return MAGIC + byteArrayOf(FORMAT_VERSION) + salt + iv + ciphertext
    }

    /** Reads the salt from a sealed document without needing the key. */
    fun saltOf(sealed: ByteArray): ByteArray {
        requireHeader(sealed)
        return sealed.copyOfRange(5, 5 + SALT_BYTES)
    }

    fun openDocument(sealed: ByteArray, key: VaultKey): ByteArray {
        requireHeader(sealed)
        val ivStart = 5 + SALT_BYTES
        if (sealed.size <= ivStart + IV_BYTES) throw CorruptVaultException("Vault file is truncated.")
        val iv = sealed.copyOfRange(ivStart, ivStart + IV_BYTES)
        val ciphertext = sealed.copyOfRange(ivStart + IV_BYTES, sealed.size)
        return try {
            gcm(Cipher.DECRYPT_MODE, key, iv, ciphertext)
        } catch (e: Exception) {
            // A GCM tag failure means either the wrong key or tampering. The wrong
            // password is overwhelmingly the likely cause, so report that.
            throw WrongPasswordException()
        }
    }

    /**
     * Encrypts a single image blob. No header and no salt — the key is already
     * derived, and repeating a 21-byte header per image would be pure overhead.
     * Each blob still gets its own IV, which is what actually matters: reusing an
     * IV under one key breaks GCM catastrophically.
     */
    fun sealBlob(plaintext: ByteArray, key: VaultKey): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        return iv + gcm(Cipher.ENCRYPT_MODE, key, iv, plaintext)
    }

    fun openBlob(sealed: ByteArray, key: VaultKey): ByteArray {
        if (sealed.size <= IV_BYTES) throw CorruptVaultException("Image blob is truncated.")
        val iv = sealed.copyOfRange(0, IV_BYTES)
        return try {
            gcm(Cipher.DECRYPT_MODE, key, iv, sealed.copyOfRange(IV_BYTES, sealed.size))
        } catch (e: Exception) {
            throw CorruptVaultException("An image blob failed to decrypt.")
        }
    }

    private fun requireHeader(sealed: ByteArray) {
        if (sealed.size < 5 + SALT_BYTES + IV_BYTES) {
            throw CorruptVaultException("Vault file is too small to be valid.")
        }
        if (!sealed.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw CorruptVaultException("Not a SentinelX vault file.")
        }
        if (sealed[4] != FORMAT_VERSION) {
            throw CorruptVaultException("Vault format v${sealed[4]} is newer than this app understands.")
        }
    }

    private fun gcm(mode: Int, key: VaultKey, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(input)
    }
}
