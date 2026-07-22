package com.nikhil.sentinelx.desktop.core.store

import com.nikhil.sentinelx.desktop.core.format.ArtifactEntity
import com.nikhil.sentinelx.desktop.core.format.LoginEntity
import com.nikhil.sentinelx.desktop.core.format.MasterBackup
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultStoreTest {

    private val dir: File = createTempDirectory("vaultstore").toFile()
    private val password = "a-strong-master-password".toCharArray()

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun backup(vararg sites: String) = MasterBackup(
        logins = sites.mapIndexed { i, s -> LoginEntity(i + 1, s, "user$i", "pw$i") }
    )

    @Test
    fun `create then unlock round trips`() {
        val store = VaultStore(dir)
        assertTrue(!store.exists)

        store.create(password, backup("Github", "Steam")).lock()
        assertTrue(store.exists)

        val session = store.unlock(password)
        val loaded = session.load()
        assertEquals(listOf("Github", "Steam"), loaded.logins.map { it.siteName })
        session.lock()
    }

    @Test
    fun `wrong password is rejected at unlock, not later`() {
        VaultStore(dir).create(password, backup("Github")).lock()
        assertFailsWith<LocalCrypto.WrongPasswordException> {
            VaultStore(dir).unlock("not-the-password".toCharArray())
        }
    }

    @Test
    fun `vault file on disk contains no plaintext`() {
        VaultStore(dir).create(password, backup("VerySecretSiteName")).lock()
        val raw = File(dir, "vault.meta").readBytes()
        assertTrue(
            !String(raw, Charsets.ISO_8859_1).contains("VerySecretSiteName"),
            "site name leaked into the sealed vault file"
        )
        // Header is deliberately readable — it carries the magic and the salt.
        assertEquals("SXVL", String(raw.copyOfRange(0, 4), Charsets.US_ASCII))
    }

    @Test
    fun `images seal and unseal, and are unreadable on disk`() {
        val session = VaultStore(dir).create(password, MasterBackup())
        val bytes = "PRETEND-WEBP-BYTES".toByteArray()
        session.putImage("IMG_a.webp", bytes)

        assertContentEquals(bytes, session.readImage("IMG_a.webp"))
        assertNull(session.readImage("IMG_missing.webp"))
        assertTrue(session.hasImage("IMG_a.webp"))

        val blob = File(dir, "images/IMG_a.webp.blob").readBytes()
        assertTrue(
            !String(blob, Charsets.ISO_8859_1).contains("PRETEND-WEBP"),
            "image bytes are readable on disk"
        )
        session.lock()
    }

    @Test
    fun `each image gets a distinct IV`() {
        val session = VaultStore(dir).create(password, MasterBackup())
        val same = "identical".toByteArray()
        session.putImage("one.webp", same)
        session.putImage("two.webp", same)

        val a = File(dir, "images/one.webp.blob").readBytes()
        val b = File(dir, "images/two.webp.blob").readBytes()
        // Identical plaintext under one key must not produce identical ciphertext —
        // IV reuse would break GCM catastrophically.
        assertTrue(!a.contentEquals(b), "IV appears to be reused across blobs")
        session.lock()
    }

    @Test
    fun `saving snapshots the previous version`() {
        val store = VaultStore(dir)
        val session = store.create(password, backup("First"))
        session.save(backup("First", "Second"))
        session.save(backup("First", "Second", "Third"))

        val versions = session.versions()
        assertTrue(versions.size >= 2, "expected snapshots, got ${versions.size}")

        // Newest snapshot is the state immediately before the last save.
        assertEquals(2, session.loadVersion(versions.first()).logins.size)
        assertEquals(3, session.load().logins.size)
        session.lock()
    }

    @Test
    fun `orphan images are pruned only when asked`() {
        val used = MasterBackup(
            artifacts = listOf(ArtifactEntity(1, "BANK", "a", "b", "c", frontImageUri = "keep.webp"))
        )
        val session = VaultStore(dir).create(password, used)
        session.putImage("keep.webp", byteArrayOf(1))
        session.putImage("orphan.webp", byteArrayOf(2))

        assertEquals(setOf("keep.webp", "orphan.webp"), session.imageNames())
        assertEquals(1, session.pruneOrphanImages(used))
        assertEquals(setOf("keep.webp"), session.imageNames())
        session.lock()
    }

    @Test
    fun `unsafe image names are refused`() {
        val session = VaultStore(dir).create(password, MasterBackup())
        assertFailsWith<IllegalArgumentException> {
            session.putImage("../../escape.webp", byteArrayOf(1))
        }
        session.lock()
    }

    @Test
    fun `a locked session refuses to be used`() {
        val session = VaultStore(dir).create(password, backup("Github"))
        session.lock()
        assertFailsWith<IllegalStateException> { session.load() }
    }

    @Test
    fun `creating over an existing vault is refused`() {
        val store = VaultStore(dir)
        store.create(password, MasterBackup()).lock()
        assertFailsWith<IllegalStateException> { store.create(password, MasterBackup()) }
    }
}
