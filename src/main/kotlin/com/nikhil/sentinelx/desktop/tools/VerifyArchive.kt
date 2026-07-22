package com.nikhil.sentinelx.desktop.tools

import com.nikhil.sentinelx.desktop.core.format.SxvArchive
import com.nikhil.sentinelx.desktop.core.format.SxvCrypto
import com.nikhil.sentinelx.desktop.core.format.billFilenames
import com.nikhil.sentinelx.desktop.core.format.pageFilenames
import java.io.File
import kotlin.system.exitProcess

/**
 * Verifies that a real Migration Seal from the phone parses correctly.
 *
 * Deliberately a separate tool rather than a unit test: the round-trip tests only
 * prove this code is self-consistent, because the same implementation writes and
 * reads them. Pointing it at an archive the *Android app* produced is the only
 * thing that proves the two agree.
 *
 * The password is read from the SXV_PASSWORD environment variable, never a command
 * argument — arguments are visible in `ps` output and land in shell history.
 * Intended usage keeps it out of both:
 *
 *     read -s -p "Password: " SXV_PASSWORD && export SXV_PASSWORD && echo
 *     ./gradlew verifySxv --args="/path/to/vault.sxv" -q
 *     unset SXV_PASSWORD
 *
 * Prints counts and structural facts only. No field values are ever output, so the
 * result is safe to paste into a conversation.
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: run {
        System.err.println("usage: verifySxv <path-to.sxv>   (password via \$SXV_PASSWORD)")
        exitProcess(2)
    }

    val password = System.getenv("SXV_PASSWORD")
    if (password.isNullOrEmpty()) {
        System.err.println("SXV_PASSWORD is not set. See the usage note in VerifyArchive.kt.")
        exitProcess(2)
    }

    val file = File(path)
    println("Archive : ${file.name}")
    println("Size    : ${"%,d".format(file.length())} bytes")

    val payload = try {
        SxvArchive.read(file, password.toCharArray())
    } catch (e: SxvCrypto.WrongPasswordException) {
        System.err.println("\n✗ Wrong password (or not a SentinelX archive).")
        exitProcess(1)
    } catch (e: Exception) {
        System.err.println("\n✗ Could not read archive: ${e.message}")
        exitProcess(1)
    }

    val b = payload.backup
    println("Format  : ${if (SxvCrypto.isV2(file.readPayloadEntry())) "SXV2 (600,000 iterations)" else "legacy v1 (65,536)"}")
    println("Schema  : version ${b.version}")
    println()
    println("  Logins        ${b.logins.size}")
    println("  Artifacts     ${b.artifacts.size}")
    println("  Chronicles    ${b.chronicles.size}")
    println("  Prophecies    ${b.prophecies.size}")
    println("  Ledger rows   ${b.ledger.size}")
    println("  Accounts      ${b.accounts.size}")
    println("  Images        ${payload.images.size}")
    println()

    // Integrity: do the entities and the ZIP agree about which images exist?
    val missing = payload.missingImages()
    val orphans = payload.orphanImages()
    if (missing.isEmpty()) {
        println("✓ Every referenced image is present in the archive.")
    } else {
        println("⚠ ${missing.size} referenced image(s) missing from the archive.")
    }
    if (orphans.isNotEmpty()) {
        println("  (${orphans.size} image(s) in the archive are referenced by nothing — harmless leftovers)")
    }

    // Sanity-check the delimited columns actually parse.
    val pages = b.chronicles.sumOf { it.pageFilenames().size }
    val bills = b.ledger.sumOf { it.billFilenames().size }
    println("✓ Parsed $pages chronicle page reference(s) and $bills bill reference(s).")

    // Cross-check that account IDs referenced by the ledger exist.
    val accountIds = b.accounts.map { it.id }.toSet()
    val orphanTx = b.ledger.count { it.accountId !in accountIds }
    if (orphanTx == 0) println("✓ Every ledger row points at a real account.")
    else println("⚠ $orphanTx ledger row(s) reference a missing account (accountId not in accounts).")

    println("\n✓ Archive parsed successfully.")
}

/** Re-reads just the payload entry so the version can be reported. */
private fun File.readPayloadEntry(): String =
    java.util.zip.ZipInputStream(inputStream().buffered()).use { zis ->
        generateSequence { zis.nextEntry }
            .firstOrNull { it.name == "vault_data.json" }
            ?.let { zis.readBytes().toString(Charsets.UTF_8) }
            ?: ""
    }
