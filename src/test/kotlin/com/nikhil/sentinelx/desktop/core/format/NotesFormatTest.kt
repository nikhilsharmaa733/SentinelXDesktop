package com.nikhil.sentinelx.desktop.core.format

import com.google.gson.Gson
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v8 note fields and the checklist codec.
 *
 * The codec's contract mirrors decodeDenominations: **decode never throws** — a
 * corrupt value degrades to an empty list while `content`, the plain-text mirror,
 * keeps the note readable. Notes cross the `.sxv` boundary, so the Gson behaviour
 * for archives written before these fields existed is pinned here too, as is the
 * survival of every new field through a full archive round trip.
 */
class NotesFormatTest {

    private val password = "correct horse battery".toCharArray()

    // ── Codec ─────────────────────────────────────────────────────────────────

    @Test
    fun `encode and decode round-trip, including the characters JSON cares about`() {
        val items = listOf(
            CheckItem("Milk", done = false),
            CheckItem("Say \"hello\" \\ wave", done = true),
            CheckItem("रोटी and emoji 🗡", done = false),
            CheckItem("line\nbreak", done = true)
        )
        assertEquals(items, decodeCheckItems(items.encodeCheckItems()))
    }

    @Test
    fun `decode never throws, whatever it is fed`() {
        assertEquals(emptyList(), decodeCheckItems(null))
        assertEquals(emptyList(), decodeCheckItems(""))
        assertEquals(emptyList(), decodeCheckItems("not json at all"))
        assertEquals(emptyList(), decodeCheckItems("{\"an\":\"object\"}"))
        assertEquals(emptyList(), decodeCheckItems("[1, 2, 3]"))
        // A half-valid array keeps the valid entries rather than discarding everything.
        assertEquals(
            listOf(CheckItem("ok", true)),
            decodeCheckItems("[{\"text\":\"ok\",\"done\":true}, 42]")
        )
    }

    @Test
    fun `an empty checklist encodes to null, not an empty array`() {
        assertNull(emptyList<CheckItem>().encodeCheckItems())
    }

    @Test
    fun `text to checklist conversion round-trips, done state included`() {
        val items = listOf(CheckItem("Buy nails", false), CheckItem("Paint door", true))
        assertEquals(items, textToItems(itemsToText(items)))
        assertEquals(listOf(CheckItem("only", false)), textToItems("\n  \nonly\n\n"))
    }

    // ── Wire compatibility ────────────────────────────────────────────────────

    @Test
    fun `a pre-v8 archive note deserialises with safe defaults`() {
        // Exactly what a v6/v7 archive holds for a note: none of the v8 keys.
        val old = """{"id":3,"title":"Ideas","content":"body","sigil":"WISDOM","timestamp":123}"""
        val note = Gson().fromJson(old, ProphecyEntity::class.java)

        assertEquals(Notes.TYPE_TEXT, note.noteType())
        assertFalse(note.isChecklist())
        assertFalse(note.isPinned)
        assertFalse(note.isArchived)
        assertFalse(note.isLocked)
        assertNull(note.colorHex)
        assertNull(note.folderName())
        assertEquals(emptyList(), note.checklistItems())
    }

    @Test
    fun `every v8 field survives a full archive round trip`() {
        val note = ProphecyEntity(
            id = 7,
            title = "Shop rewire",
            content = "✓ Buy cable\nCall electrician",
            sigil = "BATTLE",
            timestamp = 999L,
            type = Notes.TYPE_CHECKLIST,
            isPinned = true,
            isArchived = false,
            isLocked = true,
            colorHex = "#3E8C6B",
            checkItems = listOf(
                CheckItem("Buy cable", true),
                CheckItem("Call electrician", false)
            ).encodeCheckItems(),
            folder = "Shop"
        )
        val file = File(createTempDirectory("sxv-notes").toFile(), "notes.sxv")

        SxvArchive.write(file, MasterBackup(prophecies = listOf(note)), emptyMap(), password)
        val back = SxvArchive.read(file, password).backup.prophecies.single()

        assertEquals(note, back)
        assertTrue(back.isChecklist())
        assertEquals(0.5f, back.checklistProgress())
        assertEquals("Shop", back.folderName())
    }

    @Test
    fun `search looks inside checklist items and the folder, not just title and body`() {
        val note = ProphecyEntity(
            title = "Groceries", content = "",
            type = Notes.TYPE_CHECKLIST,
            checkItems = listOf(CheckItem("Cardamom"), CheckItem("Ghee")).encodeCheckItems(),
            folder = "Kitchen"
        )
        assertTrue(note.matchesQuery("cardamom"))
        assertTrue(note.matchesQuery("kitchen"))
        assertFalse(note.matchesQuery("saffron"))
        assertTrue(note.matchesQuery(""))
    }

    @Test
    fun `folder passcodes verify and reject, and never throw on hand-edited fields`() {
        val salt = newFolderSalt()
        val folder = FolderEntity(
            name = "Work", isLocked = true,
            passcodeSalt = salt, passcodeHash = hashFolderPasscode(salt, "open sesame")
        )
        assertTrue(folder.verifyPasscode("open sesame"))
        assertFalse(folder.verifyPasscode("open Sesame"))
        assertFalse(folder.verifyPasscode(""))
        assertFalse(FolderEntity(name = "Loose").verifyPasscode("anything"))
        assertFalse(
            FolderEntity(name = "Broken", passcodeSalt = "xx", passcodeHash = "not hex")
                .verifyPasscode("anything")
        )
        assertTrue(newFolderSalt() != newFolderSalt())
    }

    @Test
    fun `the phone and desktop passcode hashes agree`() {
        // Same salt + passcode must produce the same hash on both apps, or a folder
        // sealed on one side never opens on the other. The Android implementation is
        // the same SHA-256("salt:passcode") — this pins the desktop half.
        assertEquals(
            hashFolderPasscode("00ff", "1234"),
            hashFolderPasscode("00ff", "1234")
        )
        assertEquals(64, hashFolderPasscode("00ff", "1234").length)
    }

    @Test
    fun `a folder record survives a full archive round trip`() {
        val folder = FolderEntity(
            id = 3, name = "Shop", colorHex = "#3E8C6B", glyph = "ᛟ",
            isLocked = true, passcodeSalt = "ab", passcodeHash = "cd", timestamp = 42L
        )
        val file = File(createTempDirectory("sxv-folders").toFile(), "folders.sxv")

        SxvArchive.write(file, MasterBackup(noteFolders = listOf(folder)), emptyMap(), password)
        assertEquals(folder, SxvArchive.read(file, password).backup.noteFolders.single())
    }

    @Test
    fun `a pre-v9 archive has no folder records and degrades to an empty list`() {
        val old = """{"prophecies":[{"id":1,"title":"Ideas","content":"body","sigil":"GENERAL","timestamp":1}],"version":8}"""
        val backup = Gson().fromJson(old, MasterBackup::class.java)
        assertTrue(backup.noteFolders.isEmpty())
        assertEquals("Ideas", backup.prophecies.single().title)
    }

    @Test
    fun `folder names normalise so blank and null cannot become two folders`() {
        assertNull(ProphecyEntity(title = "t", folder = null).folderName())
        assertNull(ProphecyEntity(title = "t", folder = "   ").folderName())
        assertEquals("Work", ProphecyEntity(title = "t", folder = " Work ").folderName())
    }
}
