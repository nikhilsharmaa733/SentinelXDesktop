package com.nikhil.sentinelx.desktop.core.audit

import java.security.SecureRandom

/**
 * Generates passwords for new logins — table stakes for a password manager, and
 * something the phone app never had.
 *
 * Uses [SecureRandom], not `Random`. `Random` is seeded predictably enough that
 * generated passwords could be reconstructed by an attacker who knows roughly when
 * they were created, which would quietly undermine the entire vault.
 */
object PasswordGenerator {

    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"   // no 'l'
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"    // no 'I', no 'O'
    private const val DIGITS = "23456789"                   // no '0', no '1'
    private const val SYMBOLS = "!@#$%^&*-_=+?"

    private val random = SecureRandom()

    data class Options(
        val length: Int = 20,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        /**
         * Excludes characters that are easy to confuse when read off a screen —
         * l/1/I and 0/O. Costs a little entropy, saves a lot of retyping when a
         * password has to be entered on another device by hand.
         */
        val avoidAmbiguous: Boolean = true
    )

    fun generate(options: Options = Options()): String {
        val pools = buildList {
            add(if (options.avoidAmbiguous) LOWER else LOWER + "l")
            if (options.upper) add(if (options.avoidAmbiguous) UPPER else UPPER + "IO")
            if (options.digits) add(if (options.avoidAmbiguous) DIGITS else DIGITS + "01")
            if (options.symbols) add(SYMBOLS)
        }

        val length = options.length.coerceIn(8, 128)

        // One character from each pool first, so a "digits on" password always
        // actually contains a digit. Sampling uniformly can otherwise produce a
        // 20-character password with no digit at all, which then fails the very
        // strength check that prompted turning digits on.
        val required = pools.map { pool -> pool[random.nextInt(pool.length)] }
        val all = pools.joinToString("")
        val rest = (required.size until length).map { all[random.nextInt(all.length)] }

        return (required + rest).shuffled(random).joinToString("")
    }
}
