package com.nikhil.sentinelx.desktop.core.format

import com.google.gson.Gson
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SxvFormatTest {

    private val password = "correct horse battery".toCharArray()

    private fun sampleBackup() = MasterBackup(
        logins = listOf(
            LoginEntity(1, "Github", "nikhil", "hunter2"),
            // Non-ASCII must survive the UTF-8 round trip.
            LoginEntity(2, "Steam", "ravyn", "pässwörd✦")
        ),
        artifacts = listOf(
            ArtifactEntity(
                id = 1, type = "PASSPORT",
                label1 = "NIKHIL", label2 = "Z1234567", label3 = "INDIAN",
                label4 = "12/03/2001", label5 = "20/08/2031",
                secret = "1234",
                frontImageUri = "IMG_front.webp", backImageUri = "IMG_back.webp"
            )
        ),
        chronicles = listOf(
            // Pipe is the page separator AND legal in a title — the exact collision
            // that would corrupt a naive parser.
            ChronicleEntity(
                id = 1, title = "Degree | Final", year = "2023", authority = "University",
                pages = "IMG_p1.webp|IMG_p2.webp", frontImageUri = "IMG_p1.webp"
            )
        ),
        prophecies = listOf(ProphecyEntity(1, "Note", "emoji 🜏 and\nnewlines", "SECRET")),
        ledger = listOf(
            // Comma is the bill separator and equally legal in a title.
            TransactionEntity(1, 1, "Chai, samosa", 42.50, false, "FOOD", 1000L, false, "bill_1.webp")
        ),
        accounts = listOf(AccountEntity(1, "HDFC", "#D4A853", "VAULT", 1000L))
    )

    private fun sampleImages() = mapOf(
        "IMG_front.webp" to byteArrayOf(1, 2, 3),
        "IMG_back.webp" to byteArrayOf(4, 5, 6),
        "IMG_p1.webp" to byteArrayOf(7),
        "IMG_p2.webp" to byteArrayOf(8),
        "bill_1.webp" to byteArrayOf(9)
    )

    // ── Crypto ────────────────────────────────────────────────────────────────

    @Test
    fun `v2 round trips`() {
        val cipher = SxvCrypto.encrypt("hello vault", password)
        assertEquals("hello vault", SxvCrypto.decrypt(cipher, password))
        assertTrue(SxvCrypto.isV2(cipher), "encrypt() must always write the SXV2 marker")
    }

    @Test
    fun `wrong password gives a clean error, not a raw crypto exception`() {
        val cipher = SxvCrypto.encrypt("secret", password)
        assertFailsWith<SxvCrypto.WrongPasswordException> {
            SxvCrypto.decrypt(cipher, "wrong".toCharArray())
        }
    }

    @Test
    fun `truncated payload is rejected`() {
        assertFailsWith<SxvCrypto.MalformedArchiveException> {
            SxvCrypto.decrypt(Base64.getEncoder().encodeToString(ByteArray(8)), password)
        }
    }

    /**
     * The regression guard for the Base64 trap.
     *
     * Android encodes with `Base64.DEFAULT`, which line-wraps at 76 characters with a
     * bare `\n`. `Base64.getDecoder()` throws on that. This builds a payload the way
     * Android does — wrapped — and proves we can still read it.
     */
    @Test
    fun `reads line-wrapped base64 the way Android emits it`() {
        val json = Gson().toJson(sampleBackup())
        val raw = androidStyleEncryptV2(json, password)

        val wrapped = Base64.getMimeEncoder(76, byteArrayOf('\n'.code.toByte()))
            .encodeToString(raw)
        assertTrue(wrapped.contains('\n'), "fixture must actually be wrapped to be meaningful")

        // The strict decoder is what a naive implementation would reach for.
        assertFailsWith<IllegalArgumentException> { Base64.getDecoder().decode(wrapped) }

        assertEquals(json, SxvCrypto.decrypt(wrapped, password))
    }

    /**
     * Legacy v1 archives have no marker and use 65,536 iterations. They must stay
     * readable forever — a user may hold an .sxv exported before the SXV2 change.
     */
    @Test
    fun `reads legacy v1 archives without the marker`() {
        val json = """{"version":6,"logins":[{"id":7,"siteName":"Old","username":"u","password":"p"}]}"""
        val encoded = androidStyleEncryptV1(json, password)

        assertTrue(!SxvCrypto.isV2(encoded), "fixture must be markerless to test the v1 path")
        val out = Gson().fromJson(SxvCrypto.decrypt(encoded, password), MasterBackup::class.java)
        assertEquals("Old", out.logins.single().siteName)
    }

    // ── Archive ───────────────────────────────────────────────────────────────

    @Test
    fun `archive round trips with images and awkward separators intact`() {
        val dir = createTempDirectory("sxv").toFile()
        val file = File(dir, "vault.sxv")

        SxvArchive.write(file, sampleBackup(), sampleImages(), password)
        val read = SxvArchive.read(file, password)

        val original = sampleBackup()
        assertEquals(original.logins, read.backup.logins)
        assertEquals(original.artifacts, read.backup.artifacts)
        assertEquals(original.chronicles, read.backup.chronicles)
        assertEquals(original.prophecies, read.backup.prophecies)
        assertEquals(original.ledger, read.backup.ledger)
        assertEquals(original.accounts, read.backup.accounts)
        assertEquals(6, read.backup.version)

        assertEquals(sampleImages().keys, read.images.keys)
        sampleImages().forEach { (name, bytes) ->
            assertContentEquals(bytes, read.images[name], "image $name corrupted")
        }

        // A '|' in a title must not be mistaken for a page separator.
        assertEquals("Degree | Final", read.backup.chronicles.single().title)
        assertEquals(
            listOf("IMG_p1.webp", "IMG_p2.webp"),
            read.backup.chronicles.single().pageFilenames()
        )
        assertEquals("Chai, samosa", read.backup.ledger.single().title)
        assertEquals(listOf("bill_1.webp"), read.backup.ledger.single().billFilenames())

        assertTrue(read.missingImages().isEmpty(), "no referenced image should be absent")
        dir.deleteRecursively()
    }

    @Test
    fun `zip slip entry names are rejected`() {
        val dir = createTempDirectory("sxv-slip").toFile()
        val file = File(dir, "evil.sxv")

        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("vault_data.json"))
            zos.write(SxvCrypto.encrypt(Gson().toJson(MasterBackup()), password).toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("images/../../../../etc/passwd"))
            zos.write(byteArrayOf(0x13))
            zos.closeEntry()
        }

        val read = SxvArchive.read(file, password)
        assertTrue(read.images.isEmpty(), "traversing entry must not enter the image map")
        dir.deleteRecursively()
    }

    @Test
    fun `a file that is not an sxv archive fails clearly`() {
        val dir = createTempDirectory("sxv-bad").toFile()
        val file = File(dir, "notes.txt").apply { writeText("just some text") }
        assertFailsWith<SxvArchive.MalformedArchiveException> { SxvArchive.read(file, password) }
        dir.deleteRecursively()
    }

    @Test
    fun `missing images are reported rather than silently dropped`() {
        val dir = createTempDirectory("sxv-missing").toFile()
        val file = File(dir, "vault.sxv")
        // Deliberately omit one image the entities reference.
        SxvArchive.write(file, sampleBackup(), sampleImages() - "IMG_back.webp", password)
        assertEquals(setOf("IMG_back.webp"), SxvArchive.read(file, password).missingImages())
        dir.deleteRecursively()
    }

    // ── Fixtures that mimic the Android implementation ────────────────────────
    // Written against SentinelSecurity.kt directly rather than calling SxvCrypto, so
    // these tests fail if our implementation drifts from the phone's.

    private fun androidStyleEncryptV2(plaintext: String, pw: CharArray): ByteArray =
        androidStyleEncrypt(plaintext, pw, iterations = 600_000, marker = "SXV2")

    private fun androidStyleEncryptV1(plaintext: String, pw: CharArray): String =
        Base64.getMimeEncoder(76, byteArrayOf('\n'.code.toByte()))
            .encodeToString(androidStyleEncrypt(plaintext, pw, iterations = 65_536, marker = null))

    private fun androidStyleEncrypt(
        plaintext: String,
        pw: CharArray,
        iterations: Int,
        marker: String?
    ): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pw, salt, iterations, 256)).encoded,
            "AES"
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val prefix = marker?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0)
        return prefix + salt + iv + ct
    }
}
